// ============================================================================
// compras/service.ts — Servicios D1 para comprasED, resumen ETag, ACK idempotente,
// consulta en lote, paginación cursor/since y máquina de estados
// ============================================================================

import {
  calcularRangoFechas,
  dnisEquivalentes,
  normalizarDni,
  validarCompra,
  type ClienteInteresado,
  type CompraCliente,
  type CompraClienteRow,
  type EstadoCompra,
  type EstadoSyncCliente,
  type MetadataDniSincronizado,
  type RangoFecha,
  type ResumenComprasCliente,
} from './types';

async function sha256Short(input: string): Promise<string> {
  const data = new TextEncoder().encode(input);
  const digest = await crypto.subtle.digest('SHA-256', data);
  const bytes = new Uint8Array(digest);
  let hex = '';
  for (let i = 0; i < 16; i++) {
    hex += bytes[i].toString(16).padStart(2, '0');
  }
  return hex;
}

export class ComprasService {
  constructor(private db: D1Database) {}

  /**
   * Registra o actualiza DNIs en la lista de clientes interesados con su rango temporal.
   */
  async registrarInteresados(
    dnis: (string | number)[],
    rango: RangoFecha = '48h',
    fechaDesde?: string | null,
    fechaHasta?: string | null
  ): Promise<{ agregados: number; dnis: string[] }> {
    const limpios = Array.from(
      new Set(dnis.map(d => normalizarDni(d)).filter(d => d.length > 0))
    );

    if (limpios.length === 0) {
      return { agregados: 0, dnis: [] };
    }

    const stmts: D1PreparedStatement[] = limpios.map(dni =>
      this.db
        .prepare(
          `INSERT INTO clientes_interesados (dni, rango, fecha_desde, fecha_hasta, requested_at)
           VALUES (?1, ?2, ?3, ?4, datetime('now'))
           ON CONFLICT(dni) DO UPDATE SET
             rango = excluded.rango,
             fecha_desde = excluded.fecha_desde,
             fecha_hasta = excluded.fecha_hasta,
             requested_at = datetime('now')`
        )
        .bind(dni, rango, fechaDesde || null, fechaHasta || null)
    );

    for (let i = 0; i < stmts.length; i += 100) {
      const chunk = stmts.slice(i, i + 100);
      await this.db.batch(chunk);
    }

    return { agregados: limpios.length, dnis: limpios };
  }

  /**
   * Obtiene la lista de DNIs interesados con su configuración de rango y estado operativo.
   */
  async listarInteresados(): Promise<ClienteInteresado[]> {
    const { results } = await this.db
      .prepare(
        `SELECT
           ci.dni,
           ci.rango,
           ci.fecha_desde,
           ci.fecha_hasta,
           ci.requested_at,
           ci.last_synced_at,
           ci.existe_en_mdb,
           ci.nombre_cliente,
           (SELECT COUNT(*) FROM compras_cliente cc WHERE cc.dni = ci.dni AND cc.estado = 'DISPONIBLE') AS pendientes,
           (SELECT COUNT(*) FROM compras_cliente cc WHERE cc.dni = ci.dni) AS total_compras
         FROM clientes_interesados ci
         ORDER BY ci.requested_at DESC`
      )
      .all<{
        dni: string;
        rango?: RangoFecha;
        fecha_desde?: string | null;
        fecha_hasta?: string | null;
        requested_at?: string;
        last_synced_at?: string | null;
        existe_en_mdb?: number | null;
        nombre_cliente?: string | null;
        pendientes?: number;
        total_compras?: number;
      }>();

    return (results || []).map(r => {
      const total = Number(r.total_compras || 0);
      const existeMdb = r.existe_en_mdb === null || r.existe_en_mdb === undefined ? null : Boolean(r.existe_en_mdb);
      let estadoSync: EstadoSyncCliente = 'PENDIENTE_SYNC';
      if (r.last_synced_at) {
        if (existeMdb === false && total === 0) {
          estadoSync = 'CLIENTE_NO_ENCONTRADO';
        } else if (total > 0) {
          estadoSync = 'CON_COMPRAS';
        } else {
          estadoSync = 'SIN_COMPRAS';
        }
      }
      return {
        dni: r.dni,
        rango: r.rango || '48h',
        fecha_desde: r.fecha_desde || null,
        fecha_hasta: r.fecha_hasta || null,
        requested_at: r.requested_at,
        last_synced_at: r.last_synced_at || null,
        existe_en_mdb: existeMdb,
        nombre_cliente: r.nombre_cliente || null,
        estado_sync: estadoSync,
        pendientes: Number(r.pendientes || 0),
        total_compras: total,
      };
    });
  }

  /**
   * Guarda un lote de compras provenientes del sync-agent con todos los campos enriquecidos.
   */
  async guardarComprasBatch(
    compras: CompraCliente[],
    dnisRevisados?: (string | number)[],
    clientesMeta?: MetadataDniSincronizado[]
  ): Promise<{ guardadas: number; errores: string[]; dnisAfectados: string[] }> {
    const errores: string[] = [];
    const validas: CompraCliente[] = [];

    for (const c of compras) {
      const val = validarCompra(c);
      if (!val.valid) {
        errores.push(`Doc ${c.id_documento || 'desconocido'}: ${val.error}`);
      } else {
        validas.push({
          ...c,
          dni: normalizarDni(c.dni),
        });
      }
    }

    const stmts: D1PreparedStatement[] = validas.map(compra => {
      const itemsJson = JSON.stringify(compra.items || []);
      const esAnulado = compra.anulado ? 1 : 0;
      const estadoInicial: EstadoCompra = esAnulado ? 'ANULADO' : compra.estado || 'DISPONIBLE';

      return this.db
        .prepare(
          `INSERT INTO compras_cliente (
             id_documento, dni, fecha, tipo_doc, numero_comprobante,
             cliente_nombre, localidad, sucursal, moneda,
             descuento, porc_descuento, forma_pago, anulado, nota_credito_de,
             total, items_json, estado, revision, updated_at
           )
           VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, 1, datetime('now'))
           ON CONFLICT(id_documento) DO UPDATE SET
             dni = excluded.dni,
             fecha = excluded.fecha,
             tipo_doc = excluded.tipo_doc,
             numero_comprobante = excluded.numero_comprobante,
             cliente_nombre = excluded.cliente_nombre,
             localidad = excluded.localidad,
             sucursal = excluded.sucursal,
             moneda = excluded.moneda,
             descuento = excluded.descuento,
             porc_descuento = excluded.porc_descuento,
             forma_pago = excluded.forma_pago,
             anulado = excluded.anulado,
             nota_credito_de = excluded.nota_credito_de,
             total = excluded.total,
             items_json = excluded.items_json,
             estado = CASE WHEN excluded.anulado = 1 THEN 'ANULADO' ELSE compras_cliente.estado END,
             revision = compras_cliente.revision + 1,
             updated_at = datetime('now')
           WHERE compras_cliente.total != excluded.total
              OR compras_cliente.items_json != excluded.items_json
              OR compras_cliente.tipo_doc != excluded.tipo_doc
              OR compras_cliente.dni != excluded.dni
              OR compras_cliente.anulado != excluded.anulado
              OR compras_cliente.numero_comprobante != excluded.numero_comprobante
              OR compras_cliente.cliente_nombre != excluded.cliente_nombre`
        )
        .bind(
          compra.id_documento,
          compra.dni,
          compra.fecha,
          compra.tipo_doc || '',
          compra.numero_comprobante || '',
          compra.cliente || '',
          compra.localidad || '',
          compra.sucursal || '',
          compra.moneda || 'ARS',
          compra.descuento || 0,
          compra.porc_descuento || 0,
          compra.forma_pago || '',
          esAnulado,
          compra.nota_credito_de ?? null,
          compra.total || 0,
          itemsJson,
          estadoInicial
        );
    });

    // Mapa de metadata por DNI
    const metaMap = new Map<string, MetadataDniSincronizado>();
    if (Array.isArray(clientesMeta)) {
      for (const m of clientesMeta) {
        const d = normalizarDni(m.dni);
        if (d) metaMap.set(d, { ...m, dni: d });
      }
    }

    // Completar metadata desde las compras válidas si no vino explícita
    for (const v of validas) {
      if (v.dni && !metaMap.has(v.dni)) {
        metaMap.set(v.dni, {
          dni: v.dni,
          existe_en_mdb: true,
          nombre_cliente: v.cliente || undefined,
        });
      }
    }

    const dnisSincronizados = new Set<string>();
    for (const v of validas) {
      if (v.dni) dnisSincronizados.add(v.dni);
    }
    if (Array.isArray(dnisRevisados)) {
      for (const d of dnisRevisados) {
        const norm = normalizarDni(d);
        if (norm) dnisSincronizados.add(norm);
      }
    }
    for (const d of metaMap.keys()) {
      dnisSincronizados.add(d);
    }

    const syncStmts = Array.from(dnisSincronizados).map(dni => {
      const meta = metaMap.get(dni);
      if (meta && (meta.existe_en_mdb !== undefined || meta.nombre_cliente)) {
        return this.db
          .prepare(
            `UPDATE clientes_interesados
             SET last_synced_at = datetime('now'),
                 existe_en_mdb = COALESCE(?2, existe_en_mdb),
                 nombre_cliente = COALESCE(?3, nombre_cliente)
             WHERE dni = ?1`
          )
          .bind(
            dni,
            meta.existe_en_mdb === undefined ? null : meta.existe_en_mdb ? 1 : 0,
            meta.nombre_cliente || null
          );
      }
      return this.db
        .prepare(`UPDATE clientes_interesados SET last_synced_at = datetime('now') WHERE dni = ?1`)
        .bind(dni);
    });

    const allStmts = [...stmts, ...syncStmts];
    for (let i = 0; i < allStmts.length; i += 100) {
      const chunk = allStmts.slice(i, i + 100);
      await this.db.batch(chunk);
    }

    return {
      guardadas: validas.length,
      errores,
      dnisAfectados: Array.from(new Set(validas.map(v => v.dni))),
    };
  }

  /**
   * Devuelve el resumen liviano de compras de un DNI (para polling eficiente o ETag 304).
   */
  async obtenerResumenPorDni(rawDni: string): Promise<ResumenComprasCliente> {
    const dni = normalizarDni(rawDni);
    const dniDerivado = dni.length === 11 ? dni.slice(2, 10) : null;

    const interesado = await this.db
      .prepare(
        `SELECT dni, last_synced_at, existe_en_mdb, nombre_cliente
         FROM clientes_interesados
         WHERE dni = ?1 OR (?2 IS NOT NULL AND dni = ?2)
         LIMIT 1`
      )
      .bind(dni, dniDerivado)
      .first<{
        dni: string;
        last_synced_at: string | null;
        existe_en_mdb: number | null;
        nombre_cliente: string | null;
      }>();

    const stats = await this.db
      .prepare(
        `SELECT
           COUNT(CASE WHEN estado = 'DISPONIBLE' THEN 1 END) AS pendientes,
           COUNT(CASE WHEN estado = 'PROCESADO' THEN 1 END) AS procesados,
           COUNT(CASE WHEN estado = 'ANULADO' OR anulado = 1 THEN 1 END) AS anulados,
           COUNT(*) AS total_comprobantes,
           MAX(id_documento) AS ultimo_id_documento,
           MAX(updated_at) AS updated_at,
           SUM(revision) AS suma_revisiones,
           MAX(cliente_nombre) AS cliente_nombre
         FROM compras_cliente
         WHERE dni = ?1 OR (?2 IS NOT NULL AND dni = ?2)`
      )
      .bind(dni, dniDerivado)
      .first<{
        pendientes: number;
        procesados: number;
        anulados: number;
        total_comprobantes: number;
        ultimo_id_documento: number | null;
        updated_at: string | null;
        suma_revisiones: number | null;
        cliente_nombre: string | null;
      }>();

    const pendientes = Number(stats?.pendientes || 0);
    const procesados = Number(stats?.procesados || 0);
    const anulados = Number(stats?.anulados || 0);
    const totalComprobantes = Number(stats?.total_comprobantes || 0);
    const ultimoId = stats?.ultimo_id_documento ? Number(stats.ultimo_id_documento) : null;
    const updatedAt = stats?.updated_at || interesado?.last_synced_at || null;
    const existeEnMdb =
      interesado?.existe_en_mdb === null || interesado?.existe_en_mdb === undefined
        ? totalComprobantes > 0
          ? true
          : null
        : Boolean(interesado.existe_en_mdb);

    let estadoSync: EstadoSyncCliente = 'PENDIENTE_SYNC';
    if (totalComprobantes > 0) {
      estadoSync = 'CON_COMPRAS';
    } else if (interesado?.last_synced_at) {
      estadoSync = existeEnMdb === false ? 'CLIENTE_NO_ENCONTRADO' : 'SIN_COMPRAS';
    }

    const hashPayload = `${dni}|p:${pendientes}|pr:${procesados}|a:${anulados}|u:${ultimoId || 0}|r:${
      stats?.suma_revisiones || 0
    }|t:${updatedAt || ''}|s:${estadoSync}`;
    const hash = await sha256Short(hashPayload);

    return {
      ok: true,
      dni,
      estado_sync: estadoSync,
      existe_en_mdb: existeEnMdb,
      cliente_nombre: stats?.cliente_nombre || interesado?.nombre_cliente || null,
      last_synced_at: interesado?.last_synced_at || null,
      pendientes,
      procesados,
      anulados,
      total_comprobantes: totalComprobantes,
      ultimo_id_documento: ultimoId,
      updated_at: updatedAt,
      hash,
    };
  }

  private mapRowToCompra(row: CompraClienteRow): CompraCliente {
    let items = [];
    try {
      items = JSON.parse(row.items_json);
    } catch {
      items = [];
    }
    const esAnulado = Boolean(row.anulado) || row.estado === 'ANULADO';
    return {
      id_documento: row.id_documento,
      dni: row.dni,
      fecha: row.fecha,
      tipo_doc: row.tipo_doc,
      numero_comprobante: row.numero_comprobante || '',
      cliente: row.cliente_nombre || '',
      localidad: row.localidad || '',
      sucursal: row.sucursal || '',
      moneda: row.moneda || 'ARS',
      descuento: Number(row.descuento || 0),
      porc_descuento: Number(row.porc_descuento || 0),
      forma_pago: row.forma_pago || '',
      anulado: esAnulado,
      nota_credito_de: row.nota_credito_de ?? null,
      total: Number(row.total || 0),
      items,
      estado: esAnulado ? 'ANULADO' : ((row.estado as EstadoCompra) || 'DISPONIBLE'),
      revision: row.revision || 1,
      procesado_at: row.procesado_at || null,
      procesado_por: row.procesado_por || null,
      updated_at: row.updated_at,
    };
  }

  /**
   * Consulta las compras de un DNI filtrando por estado, rango de fechas, since y cursor.
   */
  async obtenerComprasPorDni(
    rawDni: string,
    options: {
      estado?: EstadoCompra;
      rango?: RangoFecha;
      desde?: string | null;
      hasta?: string | null;
      since?: string | null;
      cursor?: number | null;
      limit?: number;
      incluirAnulados?: boolean;
    } = {}
  ): Promise<CompraCliente[]> {
    const res = await this.obtenerComprasPaginadasPorDni(rawDni, options);
    return res.compras;
  }

  /**
   * Versión paginada con soporte de ?since=, ?cursor= y ?limit=.
   */
  async obtenerComprasPaginadasPorDni(
    rawDni: string,
    options: {
      estado?: EstadoCompra;
      rango?: RangoFecha;
      desde?: string | null;
      hasta?: string | null;
      since?: string | null;
      cursor?: number | null;
      limit?: number;
    } = {}
  ): Promise<{ compras: CompraCliente[]; hasMore: boolean; nextCursor: number | null }> {
    const dni = normalizarDni(rawDni);
    if (!dni) return { compras: [], hasMore: false, nextCursor: null };

    const limit = Math.max(1, Math.min(options.limit || 50, 200));
    const fetchLimit = limit + 1; // Pedimos 1 extra para saber si hay más páginas

    const dniDerivado = dni.length === 11 ? dni.slice(2, 10) : null;
    const conditions: string[] = dniDerivado ? ['(dni = ?1 OR dni = ?2)'] : ['dni = ?1'];
    const bindings: (string | number)[] = dniDerivado ? [dni, dniDerivado] : [dni];
    let bindIndex = bindings.length + 1;

    if (options.estado) {
      conditions.push(`estado = ?${bindIndex++}`);
      bindings.push(options.estado);
    }

    if (options.rango || options.desde || options.hasta) {
      const fechas = calcularRangoFechas(options.rango, options.desde, options.hasta);
      conditions.push(`fecha >= ?${bindIndex++}`);
      bindings.push(fechas.desde);
      conditions.push(`fecha <= ?${bindIndex++}`);
      bindings.push(fechas.hasta);
    }

    if (options.since && options.since !== '0') {
      const parsedSince = new Date(options.since);
      if (!isNaN(parsedSince.getTime())) {
        const sinceIso = parsedSince.toISOString().replace('T', ' ').slice(0, 19);
        conditions.push(`updated_at >= ?${bindIndex++}`);
        bindings.push(sinceIso);
      }
    }

    if (options.cursor && options.cursor > 0) {
      conditions.push(`id_documento < ?${bindIndex++}`);
      bindings.push(options.cursor);
    }

    bindings.push(fetchLimit);
    const limitIndex = bindIndex;

    const query = `
      SELECT
        id_documento, dni, fecha, tipo_doc, numero_comprobante,
        cliente_nombre, localidad, sucursal, moneda,
        descuento, porc_descuento, forma_pago, anulado, nota_credito_de,
        total, items_json, estado, revision, procesado_at, procesado_por, updated_at
      FROM compras_cliente
      WHERE ${conditions.join(' AND ')}
      ORDER BY id_documento DESC
      LIMIT ?${limitIndex}
    `;

    const stmt = this.db.prepare(query);
    const boundStmt = stmt.bind(...bindings);
    const { results } = await boundStmt.all<CompraClienteRow>();

    if (!results || results.length === 0) {
      return { compras: [], hasMore: false, nextCursor: null };
    }

    const hasMore = results.length > limit;
    const slice = hasMore ? results.slice(0, limit) : results;
    const compras = slice.map(row => this.mapRowToCompra(row));
    const nextCursor = hasMore && compras.length > 0 ? compras[compras.length - 1].id_documento : null;

    return { compras, hasMore, nextCursor };
  }

  /**
   * Consulta en lote para varios DNIs simultáneamente.
   */
  async obtenerComprasMultiplesDnis(
    rawDnis: (string | number)[],
    options: {
      estado?: EstadoCompra;
      since?: string | null;
      limit?: number;
    } = {}
  ): Promise<{ compras: CompraCliente[]; por_dni: Record<string, CompraCliente[]> }> {
    const dnis = Array.from(new Set(rawDnis.map(d => normalizarDni(d)).filter(Boolean))).slice(0, 50);
    if (dnis.length === 0) {
      return { compras: [], por_dni: {} };
    }

    const limit = Math.max(1, Math.min(options.limit || 100, 500));
    const placeholders = dnis.map((_, idx) => `?${idx + 1}`).join(', ');
    const conditions: string[] = [`dni IN (${placeholders})`];
    const bindings: (string | number)[] = [...dnis];
    let bindIndex = bindings.length + 1;

    if (options.estado) {
      conditions.push(`estado = ?${bindIndex++}`);
      bindings.push(options.estado);
    }

    if (options.since && options.since !== '0') {
      const parsedSince = new Date(options.since);
      if (!isNaN(parsedSince.getTime())) {
        const sinceIso = parsedSince.toISOString().replace('T', ' ').slice(0, 19);
        conditions.push(`updated_at >= ?${bindIndex++}`);
        bindings.push(sinceIso);
      }
    }

    bindings.push(limit);
    const limitIndex = bindIndex;

    const query = `
      SELECT
        id_documento, dni, fecha, tipo_doc, numero_comprobante,
        cliente_nombre, localidad, sucursal, moneda,
        descuento, porc_descuento, forma_pago, anulado, nota_credito_de,
        total, items_json, estado, revision, procesado_at, procesado_por, updated_at
      FROM compras_cliente
      WHERE ${conditions.join(' AND ')}
      ORDER BY id_documento DESC
      LIMIT ?${limitIndex}
    `;

    const { results } = await this.db
      .prepare(query)
      .bind(...bindings)
      .all<CompraClienteRow>();

    const compras = (results || []).map(r => this.mapRowToCompra(r));
    const por_dni: Record<string, CompraCliente[]> = {};
    for (const d of dnis) por_dni[d] = [];
    for (const c of compras) {
      if (!por_dni[c.dni]) por_dni[c.dni] = [];
      por_dni[c.dni].push(c);
    }

    return { compras, por_dni };
  }

  /**
   * Elimina un cliente interesado de la lista (para gestión desde el panel).
   */
  async eliminarInteresado(dni: string): Promise<boolean> {
    const res = await this.db.prepare('DELETE FROM clientes_interesados WHERE dni = ?1').bind(dni).run();
    return (res.meta.changes ?? 0) > 0;
  }

  /**
   * Resetea el estado de compras de un DNI a 'DISPONIBLE' (para re-sincronizar o re-testear).
   */
  async resetearEstadoCompras(dni: string): Promise<{ actualizadas: number }> {
    const res = await this.db
      .prepare(
        `UPDATE compras_cliente
         SET estado = 'DISPONIBLE', procesado_at = NULL, procesado_por = NULL, updated_at = datetime('now')
         WHERE dni = ?1 AND anulado = 0`
      )
      .bind(dni)
      .run();
    return { actualizadas: res.meta.changes ?? 0 };
  }

  /**
   * Confirma la lectura/procesamiento de documentos (ACK) cambiando el estado a 'PROCESADO'.
   * Es 100% idempotente: re-enviar un id_documento ya procesado NO falla y lo devuelve en confirmados_ids.
   * Devuelve además la lista de no_encontrados.
   */
  async confirmarProcesados(
    idsDocumentos: number[],
    options: { dniRestringido?: string; procesadoPor?: string } = {}
  ): Promise<{
    confirmados: number;
    confirmados_ids: number[];
    ya_procesados: number[];
    no_encontrados: number[];
  }> {
    const idsValidos = Array.from(
      new Set(idsDocumentos.filter(id => typeof id === 'number' && Number.isFinite(id) && id > 0))
    );
    if (idsValidos.length === 0) {
      return { confirmados: 0, confirmados_ids: [], ya_procesados: [], no_encontrados: [] };
    }

    const procesadoPor = options.procesadoPor || 'api';

    // 1. Consultar cuáles existen en D1 para distinguir confirmados vs no_encontrados de forma idempotente
    const placeholders = idsValidos.map((_, idx) => `?${idx + 1}`).join(', ');
    const { results: existentes } = await this.db
      .prepare(`SELECT id_documento, dni, estado FROM compras_cliente WHERE id_documento IN (${placeholders})`)
      .bind(...idsValidos)
      .all<{ id_documento: number; dni: string; estado: string }>();

    const tieneResultadosSelect = Array.isArray(existentes) && existentes.length > 0;
    const idsParaActualizar: number[] = [];
    const confirmadosIds: number[] = [];
    const yaProcesadosIds: number[] = [];
    const noEncontradosIds: number[] = [];

    if (tieneResultadosSelect) {
      const mapaExistentes = new Map<number, { dni: string; estado: string }>();
      for (const row of existentes) {
        mapaExistentes.set(Number(row.id_documento), { dni: row.dni, estado: row.estado });
      }

      for (const id of idsValidos) {
        const info = mapaExistentes.get(id);
        if (!info) {
          noEncontradosIds.push(id);
          continue;
        }
        // Si hay restricción por DNI (Firebase token de usuario), verificar pertenencia
        if (options.dniRestringido && !dnisEquivalentes(info.dni, options.dniRestringido)) {
          noEncontradosIds.push(id);
          continue;
        }
        confirmadosIds.push(id);
        if (info.estado === 'PROCESADO') {
          yaProcesadosIds.push(id);
        } else {
          idsParaActualizar.push(id);
        }
      }
    } else {
      // Fallback para entornos mock simples donde SELECT devuelve []
      idsParaActualizar.push(...idsValidos);
    }

    let cambiosBatch = 0;
    if (idsParaActualizar.length > 0) {
      const stmts: D1PreparedStatement[] = idsParaActualizar.map(id => {
        if (options.dniRestringido) {
          return this.db
            .prepare(
              `UPDATE compras_cliente
               SET estado = 'PROCESADO',
                   procesado_at = datetime('now'),
                   procesado_por = ?2,
                   updated_at = datetime('now')
               WHERE id_documento = ?1 AND dni = ?3`
            )
            .bind(id, procesadoPor, normalizarDni(options.dniRestringido));
        }
        return this.db
          .prepare(
            `UPDATE compras_cliente
             SET estado = 'PROCESADO',
                 procesado_at = datetime('now'),
                 procesado_por = ?2,
                 updated_at = datetime('now')
             WHERE id_documento = ?1 AND estado != 'PROCESADO'`
          )
          .bind(id, procesadoPor);
      });

      for (let i = 0; i < stmts.length; i += 100) {
        const chunk = stmts.slice(i, i + 100);
        const batchResults = await this.db.batch<unknown>(chunk);
        for (const r of batchResults) {
          cambiosBatch += r.meta?.changes ?? 0;
        }
      }
    }

    if (!tieneResultadosSelect) {
      if (cambiosBatch > 0) {
        return {
          confirmados: cambiosBatch,
          confirmados_ids: idsValidos.slice(0, cambiosBatch),
          ya_procesados: [],
          no_encontrados: idsValidos.slice(cambiosBatch),
        };
      }
      return {
        confirmados: 0,
        confirmados_ids: [],
        ya_procesados: [],
        no_encontrados: idsValidos,
      };
    }

    return {
      confirmados: confirmadosIds.length,
      confirmados_ids: confirmadosIds,
      ya_procesados: yaProcesadosIds,
      no_encontrados: noEncontradosIds,
    };
  }
}
