-- ============================================================================
-- migrate.sql — Creación de backups + seed (idempotente)
--
-- Seguro de correr múltiples veces. schema.sql crea la estructura base.
-- Este archivo agrega las tablas de backup y el seed inicial.
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
  nombre       TEXT NOT NULL,
  precios      TEXT NOT NULL,
  updated_at   TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS products_bak2 (
  codigo_unico TEXT PRIMARY KEY,
  nombre       TEXT NOT NULL,
  precios      TEXT NOT NULL,
  updated_at   TEXT NOT NULL DEFAULT (datetime('now'))
);

-- Seed de metadata si no existe (base fresca sin el INSERT de schema.sql).
INSERT OR IGNORE INTO metadata (key)
VALUES ('catalog');

-- Poblar bak con el catálogo activo SOLO si bak está vacío (primera migración).
INSERT INTO products_bak (codigo_unico, nombre, precios, updated_at)
SELECT codigo_unico, nombre, precios, updated_at FROM products
WHERE (SELECT COUNT(*) FROM products_bak) = 0;
