-- ============================================================================
-- migrate.sql — Creación de backups + seed (idempotente)
--
-- Seguro de correr múltiples veces. schema.sql crea la estructura base.
-- Este archivo agrega las tablas de backup y el seed inicial.
--
-- IMPORTANTE: las tablas de backup son COPIAS exactas de `products` (mismo
-- esquema de 10 columnas). Si cambiás el modelo, cambialas ACÁ y en
-- schema.sql — si no, el INSERT ... SELECT del worker va a fallar.
--
-- Base vieja sin backup_state → correr UNA VEZ:
--   npx wrangler d1 execute lola-db --remote \
--     --command "ALTER TABLE metadata ADD COLUMN backup_state TEXT NOT NULL DEFAULT '{\"newest\":\"bak2\"}'"
--
-- Luego correr este archivo normalmente.
--
--   npx wrangler d1 execute lola-db --remote --file=./scripts/migrate.sql
-- ============================================================================

-- Tablas de backup (IF NOT EXISTS = seguro para bases frescas y viejas).
CREATE TABLE IF NOT EXISTS products_bak (
  codigo_unico TEXT PRIMARY KEY,
  descripcion  TEXT NOT NULL,
  costo_neto   REAL NOT NULL DEFAULT 0,
  costo_iva    REAL NOT NULL DEFAULT 0,
  lista1       REAL NOT NULL DEFAULT 0,
  lista2       REAL NOT NULL DEFAULT 0,
  lista3       REAL NOT NULL DEFAULT 0,
  lista4       REAL NOT NULL DEFAULT 0,
  categoria    TEXT NOT NULL DEFAULT '',
  fabrica      TEXT NOT NULL DEFAULT '',
  updated_at   TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS products_bak2 (
  codigo_unico TEXT PRIMARY KEY,
  descripcion  TEXT NOT NULL,
  costo_neto   REAL NOT NULL DEFAULT 0,
  costo_iva    REAL NOT NULL DEFAULT 0,
  lista1       REAL NOT NULL DEFAULT 0,
  lista2       REAL NOT NULL DEFAULT 0,
  lista3       REAL NOT NULL DEFAULT 0,
  lista4       REAL NOT NULL DEFAULT 0,
  categoria    TEXT NOT NULL DEFAULT '',
  fabrica      TEXT NOT NULL DEFAULT '',
  updated_at   TEXT NOT NULL DEFAULT (datetime('now'))
);

-- Tabla de config (sync_mode) si schema.sql no la creó.
CREATE TABLE IF NOT EXISTS config (
  key        TEXT PRIMARY KEY,
  value      TEXT NOT NULL,
  updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);
INSERT INTO config (key, value) VALUES ('sync_mode', 'predeterminado')
ON CONFLICT(key) DO NOTHING;

-- Seed de metadata si no existe (base fresca sin el INSERT de schema.sql).
INSERT OR IGNORE INTO metadata (key)
VALUES ('catalog');

-- Poblar bak con el catálogo activo SOLO si bak está vacío (primera migración).
INSERT INTO products_bak (codigo_unico, descripcion, costo_neto, costo_iva, lista1, lista2, lista3, lista4, categoria, fabrica, updated_at)
SELECT codigo_unico, descripcion, costo_neto, costo_iva, lista1, lista2, lista3, lista4, categoria, fabrica, updated_at FROM products
WHERE (SELECT COUNT(*) FROM products_bak) = 0;
