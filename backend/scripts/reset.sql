-- ============================================================================
-- reset.sql — Limpieza total de tablas (SOLO DESARROLLO / RESET)
--
-- Uso:
--   npx wrangler d1 execute lola-db --local  --file ./scripts/reset.sql
--   npx wrangler d1 execute lola-db --remote --file ./scripts/reset.sql
-- ============================================================================

DROP TABLE IF EXISTS producto_categoria;
DROP TABLE IF EXISTS categorias;
DROP TABLE IF EXISTS auth;
DROP TABLE IF EXISTS products_bak2;
DROP TABLE IF EXISTS products_bak;
DROP TABLE IF EXISTS products;
DROP TABLE IF EXISTS tombstones;
DROP TABLE IF EXISTS metadata;
