-- ============================================================================
-- migrate.sql — Migración del esquema viejo (revisión por artículo + tombstones)
-- al nuevo (sync por hash + catálogo completo + 2 backups).
--
-- Solo correr UNA vez si ya inicializaste D1 con el esquema anterior:
--   npx wrangler d1 execute lola-db --local  --file ./scripts/migrate.sql   (dev)
--   npx wrangler d1 execute lola-db --remote --file ./scripts/migrate.sql   (producción)
--
-- Base fresca → NO hace falta: schema.sql ya crea todo.
-- ============================================================================

-- metadata: columna de estado de la rotación de backups.
ALTER TABLE metadata ADD COLUMN backup_state TEXT NOT NULL DEFAULT '{"newest":"bak2"}';

-- Tablas de backup rotativo (idénticas a products).
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

-- tombstones ya no se usa (el reemplazo es el modelo). Se deja la tabla, pero
-- vacía de datos si la hubiera quedado de la versión anterior.
DELETE FROM tombstones;

-- El catálogo vigente en la base vieja pasa a ser el primer backup, para que
-- el rollback tenga algo a donde volver antes del primer reemplazo.
INSERT INTO products_bak (codigo_unico, nombre, precios, updated_at)
SELECT codigo_unico, nombre, precios, updated_at FROM products;