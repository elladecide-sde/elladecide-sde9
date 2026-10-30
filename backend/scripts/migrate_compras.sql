-- ============================================================================
-- migrate_compras.sql — Tablas para módulo comprasED (100% aditivo e idempotente)
--
-- Aplicar con:
--   npx wrangler d1 execute lola-db --local  --file ./scripts/migrate_compras.sql
--   npx wrangler d1 execute lola-db --remote --file ./scripts/migrate_compras.sql
-- ============================================================================

-- Clientes cuyos comprobantes deben ser sincronizados
CREATE TABLE IF NOT EXISTS clientes_interesados (
  dni            TEXT PRIMARY KEY,
  requested_at   TEXT NOT NULL DEFAULT (datetime('now')),
  last_synced_at TEXT
);

-- Comprobantes agrupados por compra y asociados a un DNI
CREATE TABLE IF NOT EXISTS compras_cliente (
  id_documento   INTEGER PRIMARY KEY,
  dni            TEXT NOT NULL,
  fecha          TEXT NOT NULL,
  tipo_doc       TEXT NOT NULL DEFAULT '',
  total          REAL NOT NULL DEFAULT 0,
  items_json     TEXT NOT NULL DEFAULT '[]',
  updated_at     TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_compras_dni ON compras_cliente(dni);
