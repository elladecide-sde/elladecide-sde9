-- ============================================================================
-- bump.sql — Bump manual del número de revisión (fallback)
--
-- ⚠️  ESTE SCRIPT SOLO SUBE EL CONTADOR. NO recalcula el catalog_hash
--     (SHA-256 no existe en SQLite). Si tocás datos a mano, después de correr
--     esto pegale a POST /api/v1/admin/rehash para regenerar el hash, o usá
--     directamente los endpoints admin (POST/DELETE) que hacen todo junto.
--
-- Uso:
--   npx wrangler d1 execute lola-db --remote --file ./scripts/bump.sql
-- ============================================================================

UPDATE metadata
SET revision_number = revision_number + 1,
    updated_at = datetime('now')
WHERE key = 'catalog';

SELECT revision_number, catalog_hash FROM metadata WHERE key = 'catalog';
