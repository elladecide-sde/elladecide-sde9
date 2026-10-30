-- ============================================================================
-- migrate_compras.sql — Migración idempotente para comprasED con estados y rangos
-- Compatible tanto con bases limpias como con bases que ya tenían las tablas creadas.
-- ============================================================================

CREATE TABLE IF NOT EXISTS clientes_interesados (
  dni            TEXT PRIMARY KEY,
  rango          TEXT NOT NULL DEFAULT '48h',
  fecha_desde    TEXT,
  fecha_hasta    TEXT,
  requested_at   TEXT NOT NULL DEFAULT (datetime('now')),
  last_synced_at TEXT
);

CREATE TABLE IF NOT EXISTS compras_cliente (
  id_documento   INTEGER PRIMARY KEY,
  dni            TEXT NOT NULL,
  fecha          TEXT NOT NULL,
  tipo_doc       TEXT NOT NULL DEFAULT '',
  total          REAL NOT NULL DEFAULT 0,
  items_json     TEXT NOT NULL DEFAULT '[]',
  estado         TEXT NOT NULL DEFAULT 'DISPONIBLE',
  revision       INTEGER NOT NULL DEFAULT 1,
  procesado_at   TEXT,
  updated_at     TEXT NOT NULL DEFAULT (datetime('now'))
);

-- Si la tabla ya existía sin las nuevas columnas, las agregamos de forma segura:
ALTER TABLE clientes_interesados ADD COLUMN rango TEXT NOT NULL DEFAULT '48h';
ALTER TABLE clientes_interesados ADD COLUMN fecha_desde TEXT;
ALTER TABLE clientes_interesados ADD COLUMN fecha_hasta TEXT;

ALTER TABLE compras_cliente ADD COLUMN estado TEXT NOT NULL DEFAULT 'DISPONIBLE';
ALTER TABLE compras_cliente ADD COLUMN revision INTEGER NOT NULL DEFAULT 1;
ALTER TABLE compras_cliente ADD COLUMN procesado_at TEXT;

CREATE INDEX IF NOT EXISTS idx_compras_dni ON compras_cliente(dni);
CREATE INDEX IF NOT EXISTS idx_compras_estado ON compras_cliente(estado);
CREATE INDEX IF NOT EXISTS idx_compras_dni_estado ON compras_cliente(dni, estado);
