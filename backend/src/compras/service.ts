// ============================================================================
// compras/service.ts — Servicios D1 para comprasED y clientes interesados
// ============================================================================

import {
  normalizarDni,
  validarCompra,
  type ClienteInteresado,
  type CompraCliente,
  type CompraClienteRow,
} from './types';

export class ComprasService {
  constructor(private db: D1Database) {}

  /**
   * Registra o actualiza DNIs en la lista de clientes interesados.
   */
  async registrarInteresados(dnis: (string | number)[]): Promise<{ agregados: number }> {
    const limpios = Array.from(
      new Set(dnis.map(d => normalizarDni(d)).filter(d => d.length > 0))
    );

    if (limpios.length === 0) {
      return { agregados: 0 };
    }

    const stmts: D1PreparedStatement[] = limpios.map(dni =>
      this.db
        .prepare(
          `INSERT INTO clientes_interesados (dni, requested_at)
           VALUES (?1, datetime('now'))
           ON CONFLICT(dni) DO UPDATE SET requested_at = datetime('now')`
        )
        .bind(dni)
    );

    // D1 batch soporta hasta 100 statements por llamada
    for (let i = 0; i < stmts.length; i += 100) {
      const chunk = stmts.slice(i, i + 100);
      await this.db.batch(chunk);
    }

    return { agregados: limpios.length };
  }

  /**
   * Obtiene la lista de DNIs interesados (para que el sync-agent sepa a quién consultar en MDB)
   */
  async listarInteresados(): Promise<ClienteInteresado[]> {
    const { results } = await this.db
      .prepare(
        `SELECT dni, requested_at, last_synced_at
         FROM clientes_interesados
         ORDER BY requested_at DESC`
      )
      .all<ClienteInteresado>();

    return results || [];
  }

  /**
   * Guarda un lote de compras provenientes del sync-agent.
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
          `INSERT INTO compras_cliente (id_documento, dni, fecha, tipo_doc, total, items_json, updated_at)
           VALUES (?1, ?2, ?3, ?4, ?5, ?6, datetime('now'))
           ON CONFLICT(id_documento) DO UPDATE SET
             dni = excluded.dni,
             fecha = excluded.fecha,
             tipo_doc = excluded.tipo_doc,
             total = excluded.total,
             items_json = excluded.items_json,
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

    // Actualizar también last_synced_at de los DNIs procesados
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
   * Consulta las compras de un DNI específico (para el endpoint comprasED)
   */
  async obtenerComprasPorDni(rawDni: string, limit: number = 50): Promise<CompraCliente[]> {
    const dni = normalizarDni(rawDni);
    if (!dni) return [];

    const safeLimit = Math.max(1, Math.min(limit, 200));

    const { results } = await this.db
      .prepare(
        `SELECT id_documento, dni, fecha, tipo_doc, total, items_json
         FROM compras_cliente
         WHERE dni = ?1
         ORDER BY fecha DESC
         LIMIT ?2`
      )
      .bind(dni, safeLimit)
      .all<CompraClienteRow>();

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
      };
    });
  }
}
