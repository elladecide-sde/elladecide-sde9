// ============================================================================
// compras/routes.ts — Enrutador modular para comprasED y sincronización
// ============================================================================

import { ComprasService } from './service';
import { normalizarDni, type CompraCliente } from './types';

export async function handleComprasRoutes(
  request: Request,
  env: { DB: D1Database },
  url: URL,
  corsHeaders: Record<string, string>
): Promise<Response | null> {
  const service = new ComprasService(env.DB);
  const path = url.pathname;

  // 1. GET /api/v1/comprasED?dni=...
  if (request.method === 'GET' && path === '/api/v1/comprasED') {
    const rawDni = url.searchParams.get('dni') || url.searchParams.get('cuit');
    const dni = normalizarDni(rawDni);

    if (!dni) {
      return Response.json(
        { error: "Parámetro 'dni' requerido. Ejemplo: /api/v1/comprasED?dni=39675372" },
        { status: 400, headers: corsHeaders }
      );
    }

    const limitParam = parseInt(url.searchParams.get('limit') || '50', 10);
    const limit = isNaN(limitParam) ? 50 : limitParam;

    const compras = await service.obtenerComprasPorDni(dni, limit);
    return Response.json(
      {
        dni,
        total_compras: compras.length,
        compras,
      },
      { status: 200, headers: corsHeaders }
    );
  }

  // 2. POST /api/v1/compras/interesados (Ella informa los DNIs que le interesan)
  if (request.method === 'POST' && path === '/api/v1/compras/interesados') {
    try {
      const body = await request.json() as { dnis?: (string | number)[] };
      if (!body || !Array.isArray(body.dnis)) {
        return Response.json(
          { error: "Body JSON debe contener un array 'dnis'. Ejemplo: { \"dnis\": [\"39675372\"] }" },
          { status: 400, headers: corsHeaders }
        );
      }

      const res = await service.registrarInteresados(body.dnis);
      return Response.json(
        { ok: true, procesados: res.agregados },
        { status: 200, headers: corsHeaders }
      );
    } catch {
      return Response.json(
        { error: 'JSON inválido en el cuerpo de la petición' },
        { status: 400, headers: corsHeaders }
      );
    }
  }

  // 3. GET /api/v1/compras/interesados (Sync-agent consulta a quién sincronizar)
  if (request.method === 'GET' && path === '/api/v1/compras/interesados') {
    const interesados = await service.listarInteresados();
    return Response.json(
      {
        total: interesados.length,
        interesados,
      },
      { status: 200, headers: corsHeaders }
    );
  }

  // 4. POST /api/v1/compras/batch (Sync-agent sube el lote de compras extraídas)
  if (request.method === 'POST' && path === '/api/v1/compras/batch') {
    try {
      const body = await request.json() as { compras?: CompraCliente[] };
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

  // Si no coincide con ninguna ruta de compras, retorna null para que siga el flujo normal
  return null;
}
