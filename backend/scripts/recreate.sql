-- ============================================================================
-- recreate.sql — REGENERACIÓN TOTAL del catálogo (borra products + backups)
--
-- Qué BORRA:  products, products_bak, products_bak2 y metadata (revisión/hash).
-- Qué CONSERVA: auth (la contraseña), config (sync_mode) y la taxonomía
--                (categorias / producto_categoria).
--
-- Uso (producción, después hay que volver a subir el Excel):
--   npx wrangler d1 execute lola-db --remote --file ./scripts/recreate.sql
--   npm run db:init        ← recrea products con el esquema vigente + seed
--
-- Uso (dev local):
--   npx wrangler d1 execute lola-db --local --file ./scripts/recreate.sql
--   npm run db:init:local
--
-- OJO: esto deja el catálogo VACÍO hasta que se vuelva a importar.
-- ============================================================================

DROP TABLE IF EXISTS producto_categoria;
DROP TABLE IF EXISTS products_bak2;
DROP TABLE IF EXISTS products_bak;
DROP TABLE IF EXISTS products;
DROP TABLE IF EXISTS metadata;
