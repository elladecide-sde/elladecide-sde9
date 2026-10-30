// ============================================================================
// compras/service.ts — Servicios D1 para comprasED, clientes y máquina de estados
// ============================================================================

import {
  calcularRangoFechas,
  normalizarDni,
  validarCompra,
  type ClienteInteresado,
  type CompraCliente,
  type CompraClienteRow,
  type EstadoCompra,
  type RangoFecha,
} from './types';

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
  ): Promise<{ agregados: number }> {
    const limpios = Array.from(
      new Set(dnis.map(d => normalizarDni(d)).filter(d => d.length > 0))
    );

    if (limpios.length === 0) {
      return { agregados: 0 };
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

    return { agregados: limpios.length };
  }

  /**
   * Obtiene la lista de DNIs interesados con su configuración de rango para el sync-agent.
   */
  async listarInteresados(): Promise<ClienteInteresado[]> {
    const { results } = await this.db
      .prepare(
        `SELECT dni, rango, fecha_desde, fecha_hasta, requested_at, last_synced_at
         FROM clientes_interesados
         ORDER BY requested_at DESC`
      )
      .all<ClienteInteresado>();

    return results || [];
  }

  /**
   * Guarda un lote de compras provenientes del sync-agent.
   * Por defecto, las compras entran con estado 'DISPONIBLE' y revisión 1 (o incrementan revisión).
   */
  async guardarComprasBatch(compras: CompraCliente[]): Promise<{ guardadas: number; errores: string[] }> {
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

    if (validas.length === 0) {
      return { guardadas: 0, errores };
    }

    const stmts: D1PreparedStatement[] = validas.map(compra => {
      const itemsJson = JSON.stringify(compra.items || []);
      return this.db
        .prepare(
          `INSERT INTO compras_cliente (
             id_documento, dni, fecha, tipo_doc, total, items_json, estado, revision, updated_at
           )
           VALUES (?1, ?2, ?3, ?4, ?5, ?6, 'DISPONIBLE', 1, datetime('now'))
           ON CONFLICT(id_documento) DO UPDATE SET
             dni = excluded.dni,
             fecha = excluded.fecha,
             tipo_doc = excluded.tipo_doc,
             total = excluded.total,
             items_json = excluded.items_json,
             revision = compras_cliente.revision + 1,
             updated_at = datetime('now')`
        )
        .bind(
          compra.id_documento,
          compra.dni,
          compra.fecha,
          compra.tipo_doc || '',
          compra.total || 0,
          itemsJson
        );
    });

    // Actualizar last_synced_at de los DNIs procesados
    const dnisSincronizados = Array.from(new Set(validas.map(v => v.dni)));
    const syncStmts = dnisSincronizados.map(dni =>
      this.db
        .prepare(
          `UPDATE clientes_interesados SET last_synced_at = datetime('now') WHERE dni = ?1`
        )
        .bind(dni)
    );

    const allStmts = [...stmts, ...syncStmts];
    for (let i = 0; i < allStmts.length; i += 100) {
      const chunk = allStmts.slice(i, i + 100);
      await this.db.batch(chunk);
    }

    return { guardadas: validas.length, errores };
  }

  /**
   * Consulta las compras de un DNI filtrando por estado opcional (ej: 'DISPONIBLE') o rango de fechas.
   */
  async obtenerComprasPorDni(
    rawDni: string,
    options: {
      estado?: EstadoCompra;
      rango?: RangoFecha;
      desde?: string | null;
      hasta?: string | null;
      limit?: number;
    } = {}
  ): Promise<CompraCliente[]> {
    const dni = normalizarDni(rawDni);
    if (!dni) return [];

    const limit = Math.max(1, Math.min(options.limit || 50, 200));
    const conditions: string[] = ['dni = ?1'];
    const bindings: (string | number)[] = [dni];
    let bindIndex = 2;

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

    bindings.push(limit);
    const limitIndex = bindIndex;

    const query = `
      SELECT id_documento, dni, fecha, tipo_doc, total, items_json, estado, revision, procesado_at
      FROM compras_cliente
      WHERE ${conditions.join(' AND ')}
      ORDER BY fecha DESC
      LIMIT ?${limitIndex}
    `;

    const stmt = this.db.prepare(query);
    const boundStmt = stmt.bind(...bindings);
    const { results } = await boundStmt.all<CompraClienteRow>();

    if (!results || results.length === 0) {
      return [];
    }

    return results.map(row => {
      let items = [];
      try {
        items = JSON.parse(row.items_json);
      } catch {
        items = [];
      }
      return {
        id_documento: row.id_documento,
        dni: row.dni,
        fecha: row.fecha,
        tipo_doc: row.tipo_doc,
        total: row.total,
        items,
        estado: (row.estado as EstadoCompra) || 'DISPONIBLE',
        revision: row.revision || 1,
        procesado_at: row.procesado_at || null,
      };
    });
  }

  /**
   * Confirma la lectura/procesamiento de documentos (ACK) cambiando el estado a 'PROCESADO'.
   */
  async confirmarProcesados(idsDocumentos: number[]): Promise<{ confirmados: number }> {
    const idsValidos = idsDocumentos.filter(id => typeof id === 'number' && id > 0);
    if (idsValidos.length === 0) {
      return { confirmados: 0 };
    }

    const stmts: D1PreparedStatement[] = idsValidos.map(id =>
      this.db
        .prepare(
          `UPDATE compras_cliente
           SET estado = 'PROCESADO',
               procesado_at = datetime('now'),
               updated_at = datetime('now')
           WHERE id_documento = ?1`
        )
        .bind(id)
    );

    for (let i = 0; i < stmts.length; i += 100) {
      const chunk = stmts.slice(i, i + 100);
      await this.db.batch(chunk);
    }

    return { confirmados: idsValidos.length };
  }
}
