// ============================================================================
// compras/routes.ts — Enrutador modular para comprasED v2:
//   · Auth Firebase ID Token (RS256 + Firestore users/{uid}.dni) por usuario
//   · x-api-key y Admin JWT para uso administrativo / backend-to-backend
//   · GET  /api/v1/compras/resumen (con ETag / 304 Not Modified y Cache-Control)
//   · GET  /api/v1/comprasED (paginación ?since=, ?limit=, ?cursor=, multi-DNI)
//   · POST /api/v1/compras/consulta (consulta en lote multi-DNI)
//   · POST /api/v1/compras/ack (idempotente: { ok, confirmados: [...], no_encontrados: [...] })
//   · GET/POST/DELETE /api/v1/compras/interesados (con estado_sync y existe_en_mdb)
//   · POST /api/v1/compras/batch (con Webhook push opcional hacia Ella)
// ============================================================================

import { bearerToken, safeEqual, verifyJwt } from '../auth';
import {
  checkRateLimit,
  isFirebaseTokenCandidate,
  verifyFirebaseTokenAndResolveDni,
} from './firebaseAuth';
import { ComprasService } from './service';
import {
  dnisEquivalentes,
  normalizarDni,
  type CompraCliente,
  type ErrorCodeCompras,
  type EstadoCompra,
  type MetadataDniSincronizado,
  type RangoFecha,
} from './types';

export interface ComprasEnv {
  DB: D1Database;
  JWT_SECRET?: string;
  COMPRAS_API_KEY?: string;
  FIREBASE_PROJECT_ID?: string;
  ELLA_WEBHOOK_URL?: string;
  ELLA_WEBHOOK_SECRET?: string;
  /** Permite inyectar fetch o clave pública en tests unitarios */
  __TEST_FETCH?: typeof fetch;
  __TEST_FIREBASE_PUBLIC_KEY?: CryptoKey;
}

export type AuthResult =
  | {
      ok: true;
      role: 'admin';
      actor: string;
    }
  | {
      ok: true;
      role: 'user';
      actor: string;
      uid: string;
      dni: string;
    }
  | {
      ok: false;
      code: ErrorCodeCompras;
      message: string;
    };

function comprasError(
  code: ErrorCodeCompras,
  message: string,
  status: number,
  corsHeaders: Record<string, string>,
  extraHeaders: Record<string, string> = {}
): Response {
  return Response.json(
    {
      ok: false,
      code,
      message,
      error: message, // Retrocompatibilidad con clientes y UI existentes
    },
    {
      status,
      headers: {
        'Cache-Control': 'no-store',
        ...corsHeaders,
        ...extraHeaders,
      },
    }
  );
}

/**
 * Valida autenticación administrativa o del SyncAgent por JWT interno (HS256)
 */
async function requireJwtAuth(request: Request, env: ComprasEnv): Promise<AuthResult> {
  if (!env.JWT_SECRET) {
    return { ok: true, role: 'admin', actor: 'dev-bootstrap' };
  }
  const token = bearerToken(request);
  if (!token) {
    return {
      ok: false,
      code: 'UNAUTHORIZED',
      message: 'Token de autorización requerido (Authorization: Bearer <token>)',
    };
  }
  const payload = await verifyJwt(env.JWT_SECRET, token);
  if (!payload) {
    return {
      ok: false,
      code: 'UNAUTHORIZED',
      message: 'Token de autorización inválido o expirado',
    };
  }
  return { ok: true, role: 'admin', actor: `admin:${payload.sub}` };
}

/**
 * Valida autenticación administrativa:
 * Acepta x-api-key (exclusivo uso administrativo) o Bearer JWT interno del Admin de LOLA.
 */
async function requireAdminOrApiKeyAuth(request: Request, env: ComprasEnv): Promise<AuthResult> {
  const apiKey = request.headers.get('x-api-key');
  if (env.COMPRAS_API_KEY && apiKey) {
    if (safeEqual(apiKey, env.COMPRAS_API_KEY)) {
      return { ok: true, role: 'admin', actor: 'api-key' };
    }
    return {
      ok: false,
      code: 'UNAUTHORIZED',
      message: 'Header x-api-key inválido',
    };
  }

  const token = bearerToken(request);
  if (token && env.JWT_SECRET && !isFirebaseTokenCandidate(token)) {
    const payload = await verifyJwt(env.JWT_SECRET, token);
    if (payload) {
      return { ok: true, role: 'admin', actor: `admin:${payload.sub}` };
    }
  }

  if (!env.COMPRAS_API_KEY && !env.JWT_SECRET && !env.FIREBASE_PROJECT_ID) {
    return { ok: true, role: 'admin', actor: 'dev-bootstrap' };
  }

  return {
    ok: false,
    code: 'UNAUTHORIZED',
    message: 'Se requiere autenticación administrativa (x-api-key o Admin Bearer JWT)',
  };
}

/**
 * Valida autenticación para endpoints de consulta/confirmación de compras:
 * 1) Si viene Authorization: Bearer <Firebase ID Token> (RS256):
 *    Verifica firma/aud contra projectId, lee users/{uid}.dni en Firestore REST
 *    con el mismo token, y restringe el acceso exclusivamente a ese DNI.
 * 2) Si viene x-api-key o Admin JWT (HS256):
 *    Otorga acceso administrativo (puede consultar cualquier DNI).
 */
async function resolveUserOrAdminAuth(request: Request, env: ComprasEnv): Promise<AuthResult> {
  const token = bearerToken(request);

  // 1. Si el Bearer token es un Firebase ID Token (RS256 con kid), validarlo contra Google + Firestore
  if (token && isFirebaseTokenCandidate(token)) {
    const fbRes = await verifyFirebaseTokenAndResolveDni(token, env.FIREBASE_PROJECT_ID, {
      fetchFn: env.__TEST_FETCH,
      customPublicKey: env.__TEST_FIREBASE_PUBLIC_KEY,
    });
    if (!fbRes.ok) {
      return {
        ok: false,
        code: (fbRes.code as ErrorCodeCompras) || 'UNAUTHORIZED',
        message: fbRes.message,
      };
    }
    return {
      ok: true,
      role: 'user',
      actor: `firebase:${fbRes.user.uid}`,
      uid: fbRes.user.uid,
      dni: fbRes.user.dni,
    };
  }

  // 2. Fallback a autenticación administrativa (x-api-key o Admin JWT HS256)
  const adminAuth = await requireAdminOrApiKeyAuth(request, env);
  if (adminAuth.ok) return adminAuth;

  return {
    ok: false,
    code: 'UNAUTHORIZED',
    message:
      'Acceso no autorizado: envíe Authorization: Bearer <Firebase ID Token> o credencial administrativa',
  };
}

/**
 * Envía notificación webhook opcional cuando ingresan compras nuevas de clientes interesados.
 */
async function notifyWebhookIfConfigured(
  env: ComprasEnv,
  payload: { event: string; dnis: string[]; guardadas: number; timestamp: string }
): Promise<void> {
  if (!env.ELLA_WEBHOOK_URL) return;
  try {
    const bodyStr = JSON.stringify(payload);
    const headers: Record<string, string> = {
      'Content-Type': 'application/json; charset=utf-8',
      'User-Agent': 'LolaWorkerWebhook/2.0',
    };

    if (env.ELLA_WEBHOOK_SECRET) {
      const key = await crypto.subtle.importKey(
        'raw',
        new TextEncoder().encode(env.ELLA_WEBHOOK_SECRET),
        { name: 'HMAC', hash: 'SHA-256' },
        false,
        ['sign']
      );
      const sigBuf = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(bodyStr));
      const sigHex = Array.from(new Uint8Array(sigBuf))
        .map(b => b.toString(16).padStart(2, '0'))
        .join('');
      headers['x-lola-signature'] = `sha256=${sigHex}`;
    }

    const fetchFn = env.__TEST_FETCH || fetch;
    await fetchFn(env.ELLA_WEBHOOK_URL, {
      method: 'POST',
      headers,
      body: bodyStr,
    });
  } catch {
    // No bloquear el flujo del sync-agent si el webhook externo falla
  }
}

export async function handleComprasRoutes(
  request: Request,
  env: ComprasEnv,
  url: URL,
  corsHeaders: Record<string, string>
): Promise<Response | null> {
  const service = new ComprasService(env.DB);
  const path = url.pathname;

  // Rate limiting básico por IP / credencial para proteger el Worker de bucles accidentales
  const clientIp = request.headers.get('cf-connecting-ip') || 'local';
  const authHeaderSlice = (
    request.headers.get('authorization') ||
    request.headers.get('x-api-key') ||
    clientIp
  ).slice(-24);
  const rate = checkRateLimit(`${clientIp}:${authHeaderSlice}`, 180, 60);
  if (!rate.allowed) {
    return comprasError(
      'RATE_LIMITED',
      `Demasiadas peticiones. Reintentá en ${rate.retryAfterSec} segundos.`,
      429,
      corsHeaders,
      { 'Retry-After': String(rate.retryAfterSec) }
    );
  }

  // ==========================================================================
  // 0. GET /api/v1/compras/resumen — Resumen liviano con ETag y 304 Not Modified
  // ==========================================================================
  if (request.method === 'GET' && path === '/api/v1/compras/resumen') {
    const auth = await resolveUserOrAdminAuth(request, env);
    if (!auth.ok) {
      return comprasError(auth.code, auth.message, 401, corsHeaders);
    }

    const rawQueryDni = url.searchParams.get('dni') || url.searchParams.get('cuit');
    const queryDni = normalizarDni(rawQueryDni);

    let targetDni = queryDni;
    if (auth.role === 'user') {
      if (queryDni && !dnisEquivalentes(queryDni, auth.dni)) {
        return comprasError(
          'FORBIDDEN_DNI',
          'El token de usuario solo tiene permiso para consultar su propio DNI',
          403,
          corsHeaders
        );
      }
      targetDni = auth.dni;
    }

    if (!targetDni) {
      return comprasError(
        'DNI_INVALIDO',
        "Parámetro 'dni' requerido. Ejemplo: /api/v1/compras/resumen?dni=39675372",
        400,
        corsHeaders
      );
    }

    const resumen = await service.obtenerResumenPorDni(targetDni);
    const etag = `W/"${resumen.hash}"`;
    const ifNoneMatch = request.headers.get('if-none-match');

    if (ifNoneMatch && (ifNoneMatch === etag || ifNoneMatch === `"${resumen.hash}"` || ifNoneMatch === resumen.hash)) {
      return new Response(null, {
        status: 304,
        headers: {
          ...corsHeaders,
          ETag: etag,
          'Cache-Control': 'private, max-age=60',
        },
      });
    }

    return Response.json(resumen, {
      status: 200,
      headers: {
        ...corsHeaders,
        ETag: etag,
        'Cache-Control': 'private, max-age=60',
      },
    });
  }

  // ==========================================================================
  // 1. GET /api/v1/comprasED — Consulta detallada con ?since, ?limit, ?cursor y multi-DNI
  // ==========================================================================
  if (request.method === 'GET' && path === '/api/v1/comprasED') {
    const auth = await resolveUserOrAdminAuth(request, env);
    if (!auth.ok) {
      return comprasError(auth.code, auth.message, 401, corsHeaders);
    }

    const rawDnisParam =
      url.searchParams.get('dnis') ||
      url.searchParams.get('dni') ||
      url.searchParams.get('cuit') ||
      '';
    const listaDnis = rawDnisParam
      .split(/[\s,;]+/)
      .map(d => normalizarDni(d))
      .filter(Boolean);

    const estadoParam = url.searchParams.get('estado')?.toUpperCase() as EstadoCompra | undefined;
    const estado =
      estadoParam === 'DISPONIBLE' || estadoParam === 'PROCESADO' || estadoParam === 'ANULADO'
        ? estadoParam
        : undefined;

    const rangoParam = url.searchParams.get('rango')?.toLowerCase() as RangoFecha | undefined;
    const rango = ['48h', '7d', '1m', 'personalizada'].includes(rangoParam || '')
      ? rangoParam
      : undefined;

    const desde = url.searchParams.get('desde');
    const hasta = url.searchParams.get('hasta');
    const since = url.searchParams.get('since');

    const limitParam = parseInt(url.searchParams.get('limit') || '50', 10);
    const limit = isNaN(limitParam) ? 50 : limitParam;

    const cursorParam = parseInt(url.searchParams.get('cursor') || '', 10);
    const cursor = isNaN(cursorParam) ? null : cursorParam;

    // Si es un usuario autenticado con Firebase ID Token, servimos únicamente su DNI
    if (auth.role === 'user') {
      if (listaDnis.length > 0 && !listaDnis.every(d => dnisEquivalentes(d, auth.dni))) {
        return comprasError(
          'FORBIDDEN_DNI',
          'El token de usuario solo puede consultar las compras de su propio DNI',
          403,
          corsHeaders
        );
      }
      const targetDni = auth.dni;
      const [paginado, resumen] = await Promise.all([
        service.obtenerComprasPaginadasPorDni(targetDni, {
          estado,
          rango,
          desde,
          hasta,
          since,
          cursor,
          limit,
        }),
        service.obtenerResumenPorDni(targetDni),
      ]);

      return Response.json(
        {
          ok: true,
          dni: targetDni,
          estado_sync: resumen.estado_sync,
          existe_en_mdb: resumen.existe_en_mdb,
          cliente_nombre: resumen.cliente_nombre,
          last_synced_at: resumen.last_synced_at,
          total_compras: paginado.compras.length,
          pendientes: resumen.pendientes,
          estado_filtrado: estado || 'TODOS',
          rango_filtrado: rango || (desde || hasta ? 'personalizada' : 'SIN_FILTRO'),
          hasMore: paginado.hasMore,
          nextCursor: paginado.nextCursor,
          serverTime: new Date().toISOString().replace('T', ' ').slice(0, 19),
          compras: paginado.compras,
        },
        {
          status: 200,
          headers: {
            ...corsHeaders,
            'Cache-Control': 'no-store',
          },
        }
      );
    }

    // Modo Admin / API Key: permite 1 o varios DNIs
    if (listaDnis.length === 0) {
      return comprasError(
        'DNI_INVALIDO',
        "Parámetro 'dni' requerido. Ejemplo: /api/v1/comprasED?dni=39675372",
        400,
        corsHeaders
      );
    }

    if (listaDnis.length > 1) {
      const lote = await service.obtenerComprasMultiplesDnis(listaDnis, {
        estado,
        since,
        limit,
      });
      return Response.json(
        {
          ok: true,
          dnis: listaDnis,
          total_compras: lote.compras.length,
          estado_filtrado: estado || 'TODOS',
          serverTime: new Date().toISOString().replace('T', ' ').slice(0, 19),
          compras: lote.compras,
          por_dni: lote.por_dni,
        },
        {
          status: 200,
          headers: {
            ...corsHeaders,
            'Cache-Control': 'no-store',
          },
        }
      );
    }

    const dni = listaDnis[0];
    const [paginado, resumen] = await Promise.all([
      service.obtenerComprasPaginadasPorDni(dni, {
        estado,
        rango,
        desde,
        hasta,
        since,
        cursor,
        limit,
      }),
      service.obtenerResumenPorDni(dni),
    ]);

    return Response.json(
      {
        ok: true,
        dni,
        estado_sync: resumen.estado_sync,
        existe_en_mdb: resumen.existe_en_mdb,
        cliente_nombre: resumen.cliente_nombre,
        last_synced_at: resumen.last_synced_at,
        total_compras: paginado.compras.length,
        pendientes: resumen.pendientes,
        estado_filtrado: estado || 'TODOS',
        rango_filtrado: rango || (desde || hasta ? 'personalizada' : 'SIN_FILTRO'),
        hasMore: paginado.hasMore,
        nextCursor: paginado.nextCursor,
        serverTime: new Date().toISOString().replace('T', ' ').slice(0, 19),
        compras: paginado.compras,
      },
      {
        status: 200,
        headers: {
          ...corsHeaders,
          'Cache-Control': 'no-store',
        },
      }
    );
  }

  // ==========================================================================
  // 1b. POST /api/v1/compras/consulta — Consulta en lote para varios DNIs (Admin)
  // ==========================================================================
  if (request.method === 'POST' && path === '/api/v1/compras/consulta') {
    const auth = await requireAdminOrApiKeyAuth(request, env);
    if (!auth.ok) {
      return comprasError(auth.code, auth.message, 401, corsHeaders);
    }

    try {
      const body = (await request.json()) as {
        dnis?: (string | number)[];
        estado?: EstadoCompra;
        since?: string;
        limit?: number;
      };
      if (!body || !Array.isArray(body.dnis) || body.dnis.length === 0) {
        return comprasError(
          'BAD_REQUEST',
          "Body JSON debe contener un array 'dnis' no vacío",
          400,
          corsHeaders
        );
      }

      const lote = await service.obtenerComprasMultiplesDnis(body.dnis, {
        estado: body.estado,
        since: body.since,
        limit: body.limit,
      });

      return Response.json(
        {
          ok: true,
          total_compras: lote.compras.length,
          serverTime: new Date().toISOString().replace('T', ' ').slice(0, 19),
          compras: lote.compras,
          por_dni: lote.por_dni,
        },
        { status: 200, headers: { ...corsHeaders, 'Cache-Control': 'no-store' } }
      );
    } catch {
      return comprasError('BAD_REQUEST', 'JSON inválido en el cuerpo de la petición', 400, corsHeaders);
    }
  }

  // ==========================================================================
  // 2. POST /api/v1/compras/ack — Confirmación idempotente con detalle de IDs
  // ==========================================================================
  if (request.method === 'POST' && path === '/api/v1/compras/ack') {
    const auth = await resolveUserOrAdminAuth(request, env);
    if (!auth.ok) {
      return comprasError(auth.code, auth.message, 401, corsHeaders);
    }

    try {
      const body = (await request.json()) as { ids_documentos?: number[] };
      if (!body || !Array.isArray(body.ids_documentos)) {
        return comprasError(
          'BAD_REQUEST',
          "Body JSON debe contener 'ids_documentos' como array numérico. Ej: { \"ids_documentos\": [190868] }",
          400,
          corsHeaders
        );
      }

      const res = await service.confirmarProcesados(body.ids_documentos, {
        dniRestringido: auth.role === 'user' ? auth.dni : undefined,
        procesadoPor: auth.actor,
      });

      return Response.json(
        {
          ok: true,
          confirmados: res.confirmados_ids,
          cantidad_confirmados: res.confirmados,
          ya_procesados: res.ya_procesados,
          no_encontrados: res.no_encontrados,
        },
        { status: 200, headers: { ...corsHeaders, 'Cache-Control': 'no-store' } }
      );
    } catch {
      return comprasError('BAD_REQUEST', 'JSON inválido en el cuerpo de la petición', 400, corsHeaders);
    }
  }

  // ==========================================================================
  // 3. POST /api/v1/compras/interesados — Registra DNIs para sincronizar
  // ==========================================================================
  if (request.method === 'POST' && path === '/api/v1/compras/interesados') {
    const auth = await resolveUserOrAdminAuth(request, env);
    if (!auth.ok) {
      return comprasError(auth.code, auth.message, 401, corsHeaders);
    }

    try {
      const body = (await request.json().catch(() => ({}))) as {
        dnis?: (string | number)[];
        rango?: RangoFecha;
        desde?: string;
        hasta?: string;
      };

      let dnisARegistrar: (string | number)[] = [];
      if (auth.role === 'user') {
        // Un usuario autenticado con Firebase puede auto-registrar su propio DNI
        if (Array.isArray(body.dnis) && body.dnis.length > 0) {
          const todosPropios = body.dnis.every(d => dnisEquivalentes(String(d), auth.dni));
          if (!todosPropios) {
            return comprasError(
              'FORBIDDEN_DNI',
              'No tenés permiso para registrar otros DNIs en la cola de sincronización',
              403,
              corsHeaders
            );
          }
        }
        dnisARegistrar = [auth.dni];
      } else {
        if (!body || !Array.isArray(body.dnis)) {
          return comprasError(
            'BAD_REQUEST',
            "Body JSON debe contener un array 'dnis'. Ejemplo: { \"dnis\": [\"39675372\"], \"rango\": \"48h\" }",
            400,
            corsHeaders
          );
        }
        dnisARegistrar = body.dnis;
      }

      const rango = ['48h', '7d', '1m', 'personalizada'].includes(body.rango || '')
        ? body.rango
        : '48h';

      const res = await service.registrarInteresados(dnisARegistrar, rango, body.desde, body.hasta);
      return Response.json(
        {
          ok: true,
          procesados: res.agregados,
          dnis: res.dnis,
          rango_configurado: rango,
        },
        { status: 200, headers: corsHeaders }
      );
    } catch {
      return comprasError('BAD_REQUEST', 'JSON inválido en el cuerpo de la petición', 400, corsHeaders);
    }
  }

  // ==========================================================================
  // 4. GET /api/v1/compras/interesados — Lista clientes interesados con estado_sync
  // ==========================================================================
  if (request.method === 'GET' && path === '/api/v1/compras/interesados') {
    const auth = await requireAdminOrApiKeyAuth(request, env);
    if (!auth.ok) {
      return comprasError(auth.code, auth.message, 401, corsHeaders);
    }

    const interesados = await service.listarInteresados();
    return Response.json(
      {
        ok: true,
        total: interesados.length,
        interesados,
      },
      { status: 200, headers: corsHeaders }
    );
  }

  // ==========================================================================
  // 4b. DELETE /api/v1/compras/interesados/:dni — Elimina cliente de la cola
  // ==========================================================================
  if (request.method === 'DELETE' && path.startsWith('/api/v1/compras/interesados/')) {
    const auth = await requireAdminOrApiKeyAuth(request, env);
    if (!auth.ok) {
      return comprasError(auth.code, auth.message, 401, corsHeaders);
    }

    const dni = normalizarDni(path.replace('/api/v1/compras/interesados/', ''));
    if (!dni) {
      return comprasError('DNI_INVALIDO', 'DNI inválido', 400, corsHeaders);
    }

    const ok = await service.eliminarInteresado(dni);
    return Response.json({ ok, dni }, { status: 200, headers: corsHeaders });
  }

  // ==========================================================================
  // 4c. POST /api/v1/compras/reset — Resetea estado de compras de un DNI a DISPONIBLE
  // ==========================================================================
  if (request.method === 'POST' && path === '/api/v1/compras/reset') {
    const auth = await requireAdminOrApiKeyAuth(request, env);
    if (!auth.ok) {
      return comprasError(auth.code, auth.message, 401, corsHeaders);
    }

    try {
      const body = (await request.json()) as { dni?: string };
      const dni = normalizarDni(body.dni);
      if (!dni) {
        return comprasError('DNI_INVALIDO', 'DNI requerido', 400, corsHeaders);
      }

      const res = await service.resetearEstadoCompras(dni);
      return Response.json(
        { ok: true, dni, actualizadas: res.actualizadas },
        { status: 200, headers: corsHeaders }
      );
    } catch {
      return comprasError('BAD_REQUEST', 'JSON inválido', 400, corsHeaders);
    }
  }

  // ==========================================================================
  // 5. POST /api/v1/compras/batch — Sync-agent sube el lote de compras extraídas
  // ==========================================================================
  if (request.method === 'POST' && path === '/api/v1/compras/batch') {
    const auth = await requireJwtAuth(request, env);
    if (!auth.ok) {
      return comprasError(auth.code, auth.message, 401, corsHeaders);
    }

    try {
      const body = (await request.json()) as {
        compras?: CompraCliente[];
        dnis_sincronizados?: (string | number)[];
        clientes_meta?: MetadataDniSincronizado[];
      };
      if (!body || !Array.isArray(body.compras)) {
        return comprasError(
          'BAD_REQUEST',
          "Body JSON debe contener un array 'compras'",
          400,
          corsHeaders
        );
      }

      const res = await service.guardarComprasBatch(
        body.compras,
        body.dnis_sincronizados,
        body.clientes_meta
      );

      if (res.guardadas > 0 && env.ELLA_WEBHOOK_URL) {
        await notifyWebhookIfConfigured(env, {
          event: 'compras.updated',
          dnis: res.dnisAfectados,
          guardadas: res.guardadas,
          timestamp: new Date().toISOString(),
        });
      }

      return Response.json(
        {
          ok: true,
          guardadas: res.guardadas,
          errores: res.errores,
        },
        { status: 200, headers: corsHeaders }
      );
    } catch {
      return comprasError(
        'BAD_REQUEST',
        'JSON inválido o error al procesar el lote',
        400,
        corsHeaders
      );
    }
  }

  return null;
}
