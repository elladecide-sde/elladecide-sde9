-- ============================================================================
-- LOLA Backend — Esquema D1 (SQLite serverless)
-- Sync por HASH: cada cliente pregunta GET /version (hash) y si cambió baja el
-- catálogo completo (GET /sync). Sin tombstones ni revisión por artículo.
--
-- Aplicar con:
--   npx wrangler d1 execute lola-db --local  --file ./schema.sql   (dev)
--   npx wrangler d1 execute lola-db --remote --file ./schema.sql   (producción)
-- ============================================================================

-- ── metadata: fila singleton con el estado global ───────────────────────────
--   revision_number : contador monótono. Sube +1 en CADA cambio.
--   catalog_hash    : SHA-256 del catálogo canónico (ver src/hash.ts). Es LA
--                     referencia de "estoy al día": los clientes comparan este
--                     hash con el local y solo bajan si cambió.
--   backup_state    : JSON { "newest": "bak"|"bak2" } → a cuál de las dos
--                     tablas de backup rotar la próxima vez.
CREATE TABLE IF NOT EXISTS metadata (
  key             TEXT PRIMARY KEY,          -- siempre 'catalog'
  revision_number INTEGER NOT NULL DEFAULT 0,
  catalog_hash    TEXT NOT NULL DEFAULT '',
  backup_state    TEXT NOT NULL DEFAULT '{"newest":"bak2"}',
  updated_at      TEXT NOT NULL DEFAULT (datetime('now'))
);

-- ── products: catálogo activo ───────────────────────────────────────────────
--   codigo_unico : CLAVE ÚNICA Y ESTABLE, generada por el import
--                  (scripts/import-catalogo.ts + src/codigoUnico.ts), que es
--                  la FUENTE DE VERDAD. Regla: abrev(marca) + modelo.
--                  "BAKHOU 2000 LISO ALGODON HOMBRE" → "BAK2000".
--   precios      : JSON array de 4 números [L1, L2, L3, L4].
--   ⚠️  El "codigo" auto-generado del catalog.js (prefijo + contador) NO es
--   identidad: cambia con el orden del Excel. Es solo un separador interno.
CREATE TABLE IF NOT EXISTS products (
  codigo_unico TEXT PRIMARY KEY,
  nombre       TEXT NOT NULL,
  precios      TEXT NOT NULL,        -- JSON: [p1, p2, p3, p4]
  updated_at   TEXT NOT NULL DEFAULT (datetime('now'))
);

-- ── Backups rotativos (2 versiones anteriores del catálogo) ─────────────────
--   Idénticas a products. Antes de cada wipe, el worker copia el catálogo
--   activo al backup más viejo (según metadata.backup_state). Sirven para
--   deshacer un upload equivocado vía POST /api/v1/admin/rollback.
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

-- ── categorias: taxonomía del catálogo (reservada) ─────────────────────────
--   PENDIENTE de poblar: el seed inicial saldrá de los "tipos" del diccionario
--   de sde11 (BOMBACHA, BODY, CORPIÑO, PACKS…) y la curación se hará en la
--   web. La tabla queda reservada para no sufrir migraciones después.
CREATE TABLE IF NOT EXISTS categorias (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  nombre     TEXT NOT NULL UNIQUE,
  slug       TEXT NOT NULL UNIQUE,
  parent_id  INTEGER REFERENCES categorias(id),   -- jerarquía (opcional)
  orden      INTEGER NOT NULL DEFAULT 0,
  updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_categorias_parent ON categorias(parent_id);

--   Relación producto → categoría. El sync traerá las categorías del producto
--   cuando se pueble esta taxonomía.
CREATE TABLE IF NOT EXISTS producto_categoria (
  codigo_unico TEXT NOT NULL REFERENCES products(codigo_unico) ON DELETE CASCADE,
  categoria_id INTEGER NOT NULL REFERENCES categorias(id),
  PRIMARY KEY (codigo_unico, categoria_id)
);
CREATE INDEX IF NOT EXISTS idx_pc_categoria ON producto_categoria(categoria_id);

-- ── Seed del singleton de metadata ──────────────────────────────────────────
INSERT INTO metadata (key, revision_number, catalog_hash, backup_state)
VALUES ('catalog', 0, '', '{"newest":"bak2"}')
ON CONFLICT(key) DO NOTHING;