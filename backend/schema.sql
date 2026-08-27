-- ============================================================================
-- LOLA Backend — Esquema D1 (SQLite serverless)
-- Sync offline-first por revisión + hash de catálogo.
--
-- Aplicar con:
--   npx wrangler d1 execute lola-db --local  --file ./schema.sql   (dev)
--   npx wrangler d1 execute lola-db --remote --file ./schema.sql   (producción)
-- ============================================================================

-- ── metadata: fila singleton con el estado global de sincronización ─────────
--   revision_number : contador monótono. Sube +1 en CADA cambio (batch).
--   catalog_hash    : SHA-256 del catálogo canónico (ver src/hash.ts).
--                     Permite detectar "estoy al día" con UNA comparación.
CREATE TABLE IF NOT EXISTS metadata (
  key             TEXT PRIMARY KEY,          -- siempre 'catalog'
  revision_number INTEGER NOT NULL DEFAULT 0,
  catalog_hash    TEXT NOT NULL DEFAULT '',
  updated_at      TEXT NOT NULL DEFAULT (datetime('now'))
);

-- ── products: catálogo ──────────────────────────────────────────────────────
--   codigo_unico       : CLAVE ÚNICA Y ESTABLE del producto. Se GENERA de forma
--                        determinística en el import Excel → D1 (scripts/
--                        import-catalogo.ts + src/codigoUnico.ts), que es la
--                        FUENTE DE VERDAD. Regla:
--                          abrev(marca) + codigo_de_artículo
--                          "BAKHOU 2000 LISO ALGODON HOMBRE" → "BAK2000"
--                        Sin modelo (ej. los "LIQ."): entra la descripción.
--                        Colisiones → se agregan palabras posteriores al modelo,
--                        se crecen las abreviaciones y, de último, un sufijo
--                        hash. NUNCA usa posición ni contador: no depende del
--                        orden del Excel. Ver src/codigoUnico.ts.
--                        ⚠️  El "codigo" auto-generado del catalog.js
--                        (prefijo + contador, ej. "ALM01002") NO es identidad:
--                        cambia si se regenera el Excel en otro orden. Es solo
--                        un separador interno de la extensión.
--   precios            : JSON array de 4 números [L1, L2, L3, L4].
--   updated_at_revision: revisión en la que esta fila se tocó por última vez.
CREATE TABLE IF NOT EXISTS products (
  codigo_unico         TEXT PRIMARY KEY,
  nombre               TEXT NOT NULL,
  precios              TEXT NOT NULL,        -- JSON: [p1, p2, p3, p4]
  updated_at_revision  INTEGER NOT NULL,
  updated_at           TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_products_revision ON products(updated_at_revision);

-- ── tombstones: borrados lógicos ────────────────────────────────────────────
--   Cuando un producto se elimina, se saca de products y se deja una lápida
--   acá con la revisión del borrado. El cliente usa esto para borrarlo local.
CREATE TABLE IF NOT EXISTS tombstones (
  codigo_unico         TEXT PRIMARY KEY,
  deleted_at_revision  INTEGER NOT NULL,
  deleted_at           TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_tombstones_revision ON tombstones(deleted_at_revision);

-- ── auth: credenciales de administración ────────────────────────────────────
--   La contraseña se guarda HASEADA (PBKDF2-HMAC-SHA256 + salt), nunca en plano.
--   Si esta fila NO existe, el login cae al secret ADMIN_PASSWORD (bootstrap);
--   al cambiar la contraseña se crea/actualiza esta fila y D1 pasa a mandar.
CREATE TABLE IF NOT EXISTS auth (
  key           TEXT PRIMARY KEY,          -- siempre 'admin'
  password_hash TEXT NOT NULL,             -- hex
  salt          TEXT NOT NULL,             -- hex
  updated_at    TEXT NOT NULL DEFAULT (datetime('now'))
);

-- ── Seed del singleton de metadata ──────────────────────────────────────────
INSERT INTO metadata (key, revision_number, catalog_hash)
VALUES ('catalog', 0, '')
ON CONFLICT(key) DO NOTHING;
