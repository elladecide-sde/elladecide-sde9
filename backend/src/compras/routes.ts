// ============================================================================
// compras/routes.ts — Enrutador modular para comprasED, estados y seguridad
// ============================================================================

import { bearerToken, safeEqual, verifyJwt } from '../auth';
import { ComprasService } from './service';
import { normalizarDni, type CompraCliente, type EstadoCompra, type RangoFecha } from './types';

export interface ComprasEnv {
  DB: D1Database;
  JWT_SECRET?: string;
  COMPRAS_API_KEY?: string;
}

/**
 * Valida autenticación por JWT (SyncAgent / Admin)
 */
async function requireJwtAuth(request: Request, env: ComprasEnv): Promise<{ ok: boolean; reason?: string }> {
  if (!env.JWT_SECRET) {
    // Si no está configurado JWT_SECRET en dev, permite continuar con warning
    return { ok: true };
  }
  const token = bearerToken(request);
  if (!token) return { ok: false, reason: 'Token de autorización requerido (Authorization: Bearer <token>)' };
  const payload = await verifyJwt(env.JWT_SECRET, token);
  if (!payload) return { ok: false, reason: 'Token de autorización inválido o expirado' };
  return { ok: true };
}

/**
 * Valida autenticación para la app de Ella:
 * Acepta x-api-key (si COMPRAS_API_KEY está configurada) o Bearer JWT.
 */
async function requireEllaOrAdminAuth(request: Request, env: ComprasEnv): Promise<{ ok: boolean; reason?: string }> {
  const apiKey = request.headers.get('x-api-key');
  if (env.COMPRAS_API_KEY && apiKey) {
    if (safeEqual(apiKey, env.COMPRAS_API_KEY)) {
      return { ok: true };
    }
  }

  // Fallback a JWT
  if (env.JWT_SECRET) {
    const token = bearerToken(request);
    if (token) {
      const payload = await verifyJwt(env.JWT_SECRET, token);
      if (payload) return { ok: true };
    }
  }

  // Si no se configuró ninguna clave de protección en el Worker (modo bootstrap/dev)
  if (!env.COMPRAS_API_KEY && !env.JWT_SECRET) {
    return { ok: true };
  }

  return {
    ok: false,
    reason: 'Acceso no autorizado: se requiere header x-api-key o Authorization: Bearer <token>',
  };
}

export async function handleComprasRoutes(
  request: Request,
  env: ComprasEnv,
  url: URL,
  corsHeaders: Record<string, string>
): Promise<Response | null> {
  const service = new ComprasService(env.DB);
  const path = url.pathname;

  // 1. GET /api/v1/comprasED?dni=...&estado=DISPONIBLE&rango=48h
  if (request.method === 'GET' && path === '/api/v1/comprasED') {
    const auth = await requireEllaOrAdminAuth(request, env);
    if (!auth.ok) {
      return Response.json({ error: auth.reason }, { status: 401, headers: corsHeaders });
    }

    const rawDni = url.searchParams.get('dni') || url.searchParams.get('cuit');
    const dni = normalizarDni(rawDni);

    if (!dni) {
      return Response.json(
        { error: "Parámetro 'dni' requerido. Ejemplo: /api/v1/comprasED?dni=39675372" },
        { status: 400, headers: corsHeaders }
      );
    }

    const estadoParam = url.searchParams.get('estado')?.toUpperCase() as EstadoCompra | undefined;
    const estado = estadoParam === 'DISPONIBLE' || estadoParam === 'PROCESADO' ? estadoParam : undefined;

    const rangoParam = url.searchParams.get('rango')?.toLowerCase() as RangoFecha | undefined;
    const rango = ['48h', '7d', '1m', 'personalizada'].includes(rangoParam || '') ? rangoParam : undefined;

    const desde = url.searchParams.get('desde');
    const hasta = url.searchParams.get('hasta');

    const limitParam = parseInt(url.searchParams.get('limit') || '50', 10);
    const limit = isNaN(limitParam) ? 50 : limitParam;

    const compras = await service.obtenerComprasPorDni(dni, {
      estado,
      rango,
      desde,
      hasta,
      limit,
    });

    return Response.json(
      {
        dni,
        total_compras: compras.length,
        estado_filtrado: estado || 'TODOS',
        rango_filtrado: rango || (desde && hasta ? 'personalizada' : 'SIN_FILTRO'),
        compras,
      },
      { status: 200, headers: corsHeaders }
    );
  }

  // 2. POST /api/v1/compras/ack (Ella confirma recepción cambiando estado a PROCESADO)
  if (request.method === 'POST' && path === '/api/v1/compras/ack') {
    const auth = await requireEllaOrAdminAuth(request, env);
    if (!auth.ok) {
      return Response.json({ error: auth.reason }, { status: 401, headers: corsHeaders });
    }

    try {
      const body = (await request.json()) as { ids_documentos?: number[] };
      if (!body || !Array.isArray(body.ids_documentos)) {
        return Response.json(
          { error: "Body JSON debe contener 'ids_documentos' como array numérico. Ej: { \"ids_documentos\": [190868] }" },
          { status: 400, headers: corsHeaders }
        );
      }

      const res = await service.confirmarProcesados(body.ids_documentos);
      return Response.json(
        { ok: true, confirmados: res.confirmados },
        { status: 200, headers: corsHeaders }
      );
    } catch {
      return Response.json(
        { error: 'JSON inválido en el cuerpo de la petición' },
        { status: 400, headers: corsHeaders }
      );
    }
  }

  // 3. POST /api/v1/compras/interesados (Ella informa los DNIs y el rango de fechas)
  if (request.method === 'POST' && path === '/api/v1/compras/interesados') {
    const auth = await requireEllaOrAdminAuth(request, env);
    if (!auth.ok) {
      return Response.json({ error: auth.reason }, { status: 401, headers: corsHeaders });
    }

    try {
      const body = (await request.json()) as {
        dnis?: (string | number)[];
        rango?: RangoFecha;
        desde?: string;
        hasta?: string;
      };
      if (!body || !Array.isArray(body.dnis)) {
        return Response.json(
          { error: "Body JSON debe contener un array 'dnis'. Ejemplo: { \"dnis\": [\"39675372\"], \"rango\": \"48h\" }" },
          { status: 400, headers: corsHeaders }
        );
      }

      const rango = ['48h', '7d', '1m', 'personalizada'].includes(body.rango || '')
        ? body.rango
        : '48h';

      const res = await service.registrarInteresados(body.dnis, rango, body.desde, body.hasta);
      return Response.json(
        { ok: true, procesados: res.agregados, rango_configurado: rango },
        { status: 200, headers: corsHeaders }
      );
    } catch {
      return Response.json(
        { error: 'JSON inválido en el cuerpo de la petición' },
        { status: 400, headers: corsHeaders }
      );
    }
  }

  // 4. GET /api/v1/compras/interesados (Sync-agent consulta a quién sincronizar y con qué rango)
  if (request.method === 'GET' && path === '/api/v1/compras/interesados') {
    const auth = await requireEllaOrAdminAuth(request, env);
    if (!auth.ok) {
      return Response.json({ error: auth.reason }, { status: 401, headers: corsHeaders });
    }

    const interesados = await service.listarInteresados();
    return Response.json(
      {
        total: interesados.length,
        interesados,
      },
      { status: 200, headers: corsHeaders }
    );
  }

  // 4b. DELETE /api/v1/compras/interesados/:dni (Elimina cliente interesado desde el panel)
  if (request.method === 'DELETE' && path.startsWith('/api/v1/compras/interesados/')) {
    const auth = await requireEllaOrAdminAuth(request, env);
    if (!auth.ok) {
      return Response.json({ error: auth.reason }, { status: 401, headers: corsHeaders });
    }

    const dni = normalizarDni(path.replace('/api/v1/compras/interesados/', ''));
    if (!dni) {
      return Response.json({ error: 'DNI inválido' }, { status: 400, headers: corsHeaders });
    }

    const ok = await service.eliminarInteresado(dni);
    return Response.json({ ok, dni }, { status: 200, headers: corsHeaders });
  }

  // 4c. POST /api/v1/compras/reset (Resetea estado de compras de un DNI a DISPONIBLE)
  if (request.method === 'POST' && path === '/api/v1/compras/reset') {
    const auth = await requireEllaOrAdminAuth(request, env);
    if (!auth.ok) {
      return Response.json({ error: auth.reason }, { status: 401, headers: corsHeaders });
    }

    try {
      const body = (await request.json()) as { dni?: string };
      const dni = normalizarDni(body.dni);
      if (!dni) {
        return Response.json({ error: 'DNI requerido' }, { status: 400, headers: corsHeaders });
      }

      const res = await service.resetearEstadoCompras(dni);
      return Response.json({ ok: true, dni, actualizadas: res.actualizadas }, { status: 200, headers: corsHeaders });
    } catch {
      return Response.json({ error: 'JSON inválido' }, { status: 400, headers: corsHeaders });
    }
  }

  // 5. POST /api/v1/compras/batch (Sync-agent sube el lote de compras extraídas)
  if (request.method === 'POST' && path === '/api/v1/compras/batch') {
    const auth = await requireJwtAuth(request, env);
    if (!auth.ok) {
      return Response.json({ error: auth.reason }, { status: 401, headers: corsHeaders });
    }

    try {
      const body = (await request.json()) as { compras?: CompraCliente[] };
      if (!body || !Array.isArray(body.compras)) {
        return Response.json(
          { error: "Body JSON debe contener un array 'compras'" },
          { status: 400, headers: corsHeaders }
        );
      }

      const res = await service.guardarComprasBatch(body.compras);
      return Response.json(
        {
          ok: true,
          guardadas: res.guardadas,
          errores: res.errores,
        },
        { status: 200, headers: corsHeaders }
      );
    } catch {
      return Response.json(
        { error: 'JSON inválido o error al procesar el lote' },
        { status: 400, headers: corsHeaders }
      );
    }
  }

  return null;
}
