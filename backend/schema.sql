-- ============================================================================
-- LOLA Backend — Esquema D1 (SQLite serverless)
-- Sync por HASH: cada cliente pregunta GET /version (hash) y si cambió baja el
-- catálogo completo (GET /sync). Sin tombstones ni revisión por artículo.
--
-- MODELO DE PRODUCTO (v2): el dato base es la DESCRIPCIÓN + el COSTO, y de ahí
-- se derivan y se conservan TODOS los cálculos:
--
--   costo_iva = Lista 4 / (1 + inversaLista/100)        = Lista 4 / 1,50
--   costo_neto = costo_iva / (1 + IVA/100)              = Lista 4 / (1,50 × 1,21)
--   Lista n    = round(costo_neto × (1 + IVA/100) × factor_n)
--
-- Nada se descarta: costo_neto, costo_iva y las 4 listas viven en la fila.
-- `codigo` y `oferta` viajan en sync v2 (modo predeterminado) y también en v1:
-- ese contrato es ADITIVO ({ codigo_unico, nombre, precios } siguen intactos,
-- se le AGREGAN fabrica, codigo, descripcion, costo_neto, oferta).
--
-- Aplicar con:
--   npx wrangler d1 execute lola-db --local  --file ./schema.sql   (dev)
--   npx wrangler d1 execute lola-db --remote --file ./schema.sql   (producción)
--
-- REGENERAR la base (borra el catálogo activo y sus backups, conserva auth y
-- config, y recrea con este esquema — después volvé a subir el Excel):
--   npx wrangler d1 execute lola-db --remote --file ./scripts/recreate.sql
--   npm run db:init
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

-- ── config: ajustes del operador (NO versionan el catálogo) ────────────────
--   sync_mode : 'predeterminado' | 'descripcion-costos'
--     · predeterminado     → GET /api/v1/sync/v2 responde TODA la fila:
--                            { codigo_unico, descripcion, precios[4], categoria,
--                              fabrica, codigo, oferta, costo_neto, costo_iva,
--                              updated_at }
--     · descripcion-costos → GET /api/v1/sync/v2 responde { descripcion, costo }
--   GET /api/v1/sync (v1) NUNCA mira esta fila (es aditivo y va siempre igual).
CREATE TABLE IF NOT EXISTS config (
  key        TEXT PRIMARY KEY,
  value      TEXT NOT NULL,
  updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);

INSERT INTO config (key, value) VALUES ('sync_mode', 'predeterminado')
ON CONFLICT(key) DO NOTHING;

-- ── products: catálogo activo ───────────────────────────────────────────────
--   codigo_unico : CLAVE ÚNICA Y ESTABLE, generada por el import
--                  (scripts/import-catalogo.ts + src/codigoUnico.ts), que es
--                  la FUENTE DE VERDAD. Regla: abrev(marca) + modelo.
--                  "BAKHOU 2000 LISO ALGODON HOMBRE" → "BAK2000".
--                  Sigue siendo la PK: es la identidad y el eje del hash.
--   ⚠️  El "codigo" auto-generado del catalog.js (prefijo + contador) NO es
--   identidad: cambia con el orden del Excel. Es solo un separador interno.
CREATE TABLE IF NOT EXISTS products (
  codigo_unico TEXT PRIMARY KEY,
  fabrica      TEXT NOT NULL DEFAULT 'SIN FABRICA', -- se envía en v2; NUNCA vacía (regla: todo artículo tiene fábrica)
  codigo       TEXT NOT NULL DEFAULT '',  -- código de fábrica/comercial ("404 / 425"), NO es identidad
  descripcion  TEXT NOT NULL,
  costo_neto   REAL NOT NULL DEFAULT 0,   -- sin IVA: Lista 4 / (1,50 × 1,21)
  costo_iva    REAL NOT NULL DEFAULT 0,   -- costo + IVA: Lista 4 / 1,50
  lista1       REAL NOT NULL DEFAULT 0,
  lista2       REAL NOT NULL DEFAULT 0,
  lista3       REAL NOT NULL DEFAULT 0,
  lista4       REAL NOT NULL DEFAULT 0,
  categoria    TEXT NOT NULL DEFAULT '',  -- rubro (NO confundir con oferta)
  oferta       INTEGER NOT NULL DEFAULT 0,-- 1 = artículo en oferta → las 4 listas iguales
  stock        REAL NOT NULL DEFAULT 0,   -- stock disponible en AdmGlobal
  updated_at   TEXT NOT NULL DEFAULT (datetime('now'))
);

-- ── Backups rotativos (2 versiones anteriores del catálogo) ─────────────────
--   Mismo esquema que products (13 columnas). Antes de cada wipe, el worker
--   copia el catálogo activo al backup más viejo (según
--   metadata.backup_state). Sirven para deshacer un upload equivocado vía
--   POST /api/v1/admin/rollback.
CREATE TABLE IF NOT EXISTS products_bak (
  codigo_unico TEXT PRIMARY KEY,
  fabrica      TEXT NOT NULL DEFAULT 'SIN FABRICA',
  codigo       TEXT NOT NULL DEFAULT '',
  descripcion  TEXT NOT NULL,
  costo_neto   REAL NOT NULL DEFAULT 0,
  costo_iva    REAL NOT NULL DEFAULT 0,
  lista1       REAL NOT NULL DEFAULT 0,
  lista2       REAL NOT NULL DEFAULT 0,
  lista3       REAL NOT NULL DEFAULT 0,
  lista4       REAL NOT NULL DEFAULT 0,
  categoria    TEXT NOT NULL DEFAULT '',
  oferta       INTEGER NOT NULL DEFAULT 0,
  stock        REAL NOT NULL DEFAULT 0,
  updated_at   TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS products_bak2 (
  codigo_unico TEXT PRIMARY KEY,
  fabrica      TEXT NOT NULL DEFAULT 'SIN FABRICA',
  codigo       TEXT NOT NULL DEFAULT '',
  descripcion  TEXT NOT NULL,
  costo_neto   REAL NOT NULL DEFAULT 0,
  costo_iva    REAL NOT NULL DEFAULT 0,
  lista1       REAL NOT NULL DEFAULT 0,
  lista2       REAL NOT NULL DEFAULT 0,
  lista3       REAL NOT NULL DEFAULT 0,
  lista4       REAL NOT NULL DEFAULT 0,
  categoria    TEXT NOT NULL DEFAULT '',
  oferta       INTEGER NOT NULL DEFAULT 0,
  stock        REAL NOT NULL DEFAULT 0,
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
--   products.categoria es un texto libre de momento; esta jerarquía es el
--   destino cuando se pueble la taxonomía.
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

-- ── parser_config: config editable del articleParser ────────────────────────
--   version     : contador monotónico que el cliente usa como clave de caché.
--   config_json : JSON con fabricas, product_words, generic_words, offer_rules, etc.
CREATE TABLE IF NOT EXISTS parser_config (
  key         TEXT PRIMARY KEY,          -- siempre 'default'
  version     INTEGER NOT NULL DEFAULT 0,
  config_json TEXT NOT NULL DEFAULT '{}',
  updated_at  TEXT NOT NULL DEFAULT (datetime('now'))
);
INSERT INTO parser_config (key)
VALUES ('default')
ON CONFLICT(key) DO NOTHING;

-- ── Seed del singleton de metadata ──────────────────────────────────────────
INSERT INTO metadata (key)
VALUES ('catalog')
ON CONFLICT(key) DO NOTHING;
