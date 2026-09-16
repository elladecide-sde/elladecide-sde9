# LOLA Backend — Cloudflare Worker + D1

Sync **offline-first** del catálogo de LOLA, por **HASH + catálogo completo**.

- **D1** = SQLite serverless (la base).
- **Worker** = TypeScript (el runtime de la API).
- **Clientes** = teclado Android, editor de fotos (sde11), extensión, futuras apps:
  todos hablan el mismo contrato `version → sync`.

## Estructura

```
backend/
├── schema.sql              ← tablas: metadata, products, products_bak, products_bak2, auth, categorias
├── src/
│   ├── index.ts            ← Worker: rutas version/sync/admin (+ rollback)
│   ├── hash.ts             ← hash canónico SHA-256 del catálogo
│   ├── hash.test.ts        ← golden tests del hash (paridad Android)
│   ├── codigoUnico.ts      ← regla de codigo_unico (paridad exacta con pages/codigoUnico.js)
│   ├── codigoUnico.test.ts ← tests del generador de códigos
│   ├── importCatalogo.ts   ← núcleo compartido: parseo + precios + códigos
│   └── auth.ts             ← JWT, PBKDF2, comparación segura
├── scripts/
│   ├── import-catalogo.ts  ← CLI (dry-run + subida + mapa para sde11)
│   ├── migrate.sql         ← migraciones idempotentes (backups + seed)
│   └── bump.sql            ← bump manual de revisión (fallback)
├── wrangler.jsonc          ← config del Worker + binding D1
└── package.json
```

## Identidad: `codigo_unico`

La clave primaria del catálogo es **`codigo_unico`**, generada de forma
determinística por el import, derivada de la marca + el código de artículo:

```
"BAKHOU 2000 LISO ALGODON HOMBRE"  →  "BAK2000"
   BAK   (2000)                        BAK + 2000
```

- **Estable** (no depende de un contador ni del orden del Excel).
- Productos sin modelo (los "LIQ.") usan la descripción para desambiguar.
- Colisiones → se agregan palabras posteriores al modelo, se creen las
  abreviaciones y, de último, un sufijo hash (ver `src/codigoUnico.ts`).
- `pages/codigoUnico.js` es port 1:1 (verificado en paridad exacta). **La página
  genera y el worker solo reemplaza.**

> El editor de fotos (sde11) consume `GET /api/v1/mapa-catalogo` y renombra cada
> foto `CODIGO_UNICO_COLOR.jpg`. La búsqueda del usuario sigue siendo fuzzy por
> nombre; el `codigo_unico` es la llave interna.

## Sync (por HASH, catálogo completo)

```
1. GET /api/v1/version → { revision, hash }
2. hash_local == hash → FIN (0 requests más)
3. hash distinto → GET /api/v1/sync → catálogo COMPLETO → el cliente reemplaza su base local
```

No hay tombstones ni revisión por artículo: el reemplazo es el modelo. Cuando los
precios cambian, cambian TODOS, así que el resync total es el camino natural
(~300 KB, 1-2 s sobre wifi). El hash evita bajar si nada cambió.

## Reemplazo con backups (2 versiones anteriores)

Cada subida con `replace: true` (web o CLI) hace en UN batch atómico:

1. Copia el catálogo activo al **backup rotativo más viejo** (`products_bak` o
   `products_bak2`).
2. Borra `products`.
3. Inserta el catálogo nuevo.
4. Sube revisión + hash.

Si algo falla, todo revierte (el backup no se pierde). Y si subiste un Excel
equivocado: `POST /api/v1/admin/rollback` restaura la versión anterior con su
hash → los clientes resincronizan solos.

## Import Excel → D1

```bash
# Solo calcular (dry-run): genera catalogo.json + mapa-catalogo.json
npm run import -- ../catálogo.xlsx --out ./salida

# Subir al Worker (reemplazo completo)
npm run import -- ../catálogo.xlsx --api http://localhost:8787 --user admin --password devpass
```

El Excel de ADM viene con **2 campos**: nombre (todo mezclado) y precio de
Lista 4 → inversa (`costo = precio / (1.21 * 1.50)`) → 4 listas. Oferta (termina
en `.`, `oferta` o `liq`) → las 4 iguales.

Flags: `--col-nombre`, `--col-precio`, `--skip`, `--marcas=archivo.json`
(lista de carpetas de `IMAGENES-LOLA` para códigos más limpios), `--out`.

Salidas en `--out`: `catalogo.json` (para D1) y `mapa-catalogo.json` (para sde11,
mismo shape que `GET /api/v1/mapa-catalogo`).

## Setup (una sola vez)

```bash
cd backend
npm install
npx wrangler login

# 1) Crear la base D1 y copiar el database_id al wrangler.jsonc
npx wrangler d1 create lola-db

# 2) Crear las tablas (schema.sql crea TODO incluyendo backups + seed)
npm run db:init:local     # dev (SQLite local)
npm run db:init           # producción

# 3) Migración idempotente (backups + seed, seguro correr múltiples veces)
npm run db:migrate:local  # dev
npm run db:migrate        # producción

# 4) Si la base es VIEJA (sin columna backup_state), correr UNA VEZ:
npx wrangler d1 execute lola-db --remote --command \
  "ALTER TABLE metadata ADD COLUMN backup_state TEXT NOT NULL DEFAULT '{\"newest\":\"bak2\"}'"

# 5) Secrets de admin (JWT)
#    Local: creá backend/.dev.vars con
#      ADMIN_USER=admin
#      ADMIN_PASSWORD=devpass
#      JWT_SECRET=devsecret
#    Producción:
#      npx wrangler secret put ADMIN_USER
#      npx wrangler secret put ADMIN_PASSWORD
#      npx wrangler secret put JWT_SECRET
```

## Correr

```bash
npm run dev      # local: http://localhost:8787
npm run deploy   # producción
```

## Contrato de API

Todas las respuestas son `Content-Type: application/json; charset=utf-8` con
`Cache-Control: no-store`. El Worker envía CORS headers en TODAS las respuestas:

```
Access-Control-Allow-Origin: *
Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS
Access-Control-Allow-Headers: Content-Type, Authorization
```

### GET /api/v1/version

Devuelve la revisión y el hash actual. Los clientes lo usan para decidir si
necesitan sincronizar.

**Headers:** ninguno especial.

```bash
curl http://localhost:8787/api/v1/version
```

```json
{ "revision": 42, "hash": "a1b2c3d4..." }
```

### GET /api/v1/sync

Catálogo completo. El cliente reemplaza toda su base local con la respuesta.

**Headers:** ninguno especial.

```bash
curl http://localhost:8787/api/v1/sync
```

```json
{
  "revision": 42,
  "hash": "a1b2c3d4...",
  "products": [
    {
      "codigo_unico": "BAK2000",
      "nombre": "BAKHOU 2000 LISO ALGODON HOMBRE",
      "precios": [1200, 1100, 1000, 900]
    }
  ]
}
```

`precios` = `[L1, L2, L3, L4]` (Lista 1 a 4, enteros redondeados).

### GET /api/v1/mapa-catalogo

Mapa simplificado para el editor de fotos (sde11). No incluye precios.

**Headers:** ninguno especial.

```bash
curl http://localhost:8787/api/v1/mapa-catalogo
```

```json
{
  "revision": 42,
  "hash": "a1b2c3d4...",
  "productos": [
    {
      "codigo_unico": "BAK2000",
      "nombre": "BAKHOU 2000 LISO ALGODON HOMBRE",
      "modelo": "2000"
    }
  ]
}
```

### POST /api/v1/auth/login

Autenticación. Devuelve un JWT válido por 24 horas.

**Headers requeridos:**
```
Content-Type: application/json
```

```bash
curl -X POST http://localhost:8787/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "admin", "password": "devpass"}'
```

```json
{ "token": "eyJhbGci...", "expires_in": 86400 }
```

Errores:
- `401` → credenciales inválidas
- `503` → `JWT_SECRET` no configurado en el Worker

### POST /api/v1/auth/change-password

Cambia la contraseña del admin. Hashea con PBKDF2 + salt.

**Headers requeridos:**
```
Content-Type: application/json
Authorization: Bearer <token>
```

```bash
curl -X POST http://localhost:8787/api/v1/auth/change-password \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer eyJhbGci..." \
  -d '{"current_password": "devpass", "new_password": "nuevapass123"}'
```

```json
{ "ok": true }
```

Errores:
- `400` → nueva contraseña menor a 8 caracteres
- `401` → contraseña actual incorrecta o token inválido

### POST /api/v1/admin/products

Upsert parcial o reemplazo completo del catálogo.

**Headers requeridos:**
```
Content-Type: application/json
Authorization: Bearer <token>
```

**Body** (acepta 3 formatos):
```json
// Formato 1: array directo
[{ "codigo_unico": "BAK2000", "nombre": "...", "precios": [1,2,3,4] }]

// Formato 2: objeto con wrapper
{ "products": [{ "codigo_unico": "BAK2000", "nombre": "...", "precios": [1,2,3,4] }] }

// Formato 3: reemplazo completo
{ "products": [...], "replace": true }
```

**Upsert parcial** (default): actualiza o inserta los productos enviados sin tocar el resto.

```bash
curl -X POST http://localhost:8787/api/v1/admin/products \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer eyJhbGci..." \
  -d '{"products": [{"codigo_unico": "BAK2000", "nombre": "BAKHOU 2000", "precios": [1200,1100,1000,900]}]}'
```

```json
{ "revision": 43, "hash": "e5f6g7h8..." }
```

**Reemplazo** (`replace: true`): borra todo el catálogo y reemplaza. Antes de borrar,
copia al backup rotativo. Atómico (un solo batch).

```bash
curl -X POST http://localhost:8787/api/v1/admin/products \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer eyJhbGci..." \
  -d '{"products": [...], "replace": true}'
```

```json
{ "revision": 43, "hash": "e5f6g7h8...", "backup": "bak" }
```

### POST /api/v1/admin/import

Importa desde filas de Excel (el worker calcula `codigo_unico` y precios).
Funciona igual que el CLI pero remoto.

**Headers requeridos:**
```
Content-Type: application/json
Authorization: Bearer <token>
```

**Body:**
```json
{
  "filas": [["BAKHOU 2000 LISO ALGODON HOMBRE", 1500], ...],
  "colNombre": 0,
  "colPrecio": 1,
  "skip": 0,
  "marcas": ["BAKHOU", "LAundry"]
}
```

| Campo | Tipo | Requerido | Descripción |
|-------|------|-----------|-------------|
| `filas` | `array[][]` | ✅ | Filas del Excel como arrays de celdas |
| `colNombre` | `number` | ❌ | Índice de columna del nombre (default: auto-detectar) |
| `colPrecio` | `number` | ❌ | Índice de columna del precio (default: auto-detectar) |
| `colCodigo` | `number` | ❌ | Índice de columna del código (default: none) |
| `skip` | `number` | ❌ | Filas a saltar al inicio (default: 0) |
| `marcas` | `string[]` | ❌ | Lista de marcas para limpiar códigos |

```bash
curl -X POST http://localhost:8787/api/v1/admin/import \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer eyJhbGci..." \
  -d '{"filas": [["BAKHOU 2000", 1500], ["LAundry 500", 800]]}'
```

```json
{
  "revision": 43,
  "hash": "e5f6g7h8...",
  "productos": 2,
  "colisiones": 0,
  "descartados": 0,
  "rechazadas": 0
}
```

### POST /api/v1/admin/rollback

Restaura una versión anterior del catálogo (backup).

**Headers requeridos:**
```
Content-Type: application/json
Authorization: Bearer <token>
```

**Body:**
```json
{ "backup": "bak" }
```

`backup` debe ser `"bak"` o `"bak2"`.

```bash
curl -X POST http://localhost:8787/api/v1/admin/rollback \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer eyJhbGci..." \
  -d '{"backup": "bak"}'
```

```json
{ "revision": 44, "hash": "i9j0k1l2...", "restaurado": "bak" }
```

### DELETE /api/v1/admin/products/:codigo_unico

Borra un producto por su `codigo_unico`.

**Headers requeridos:**
```
Authorization: Bearer <token>
```

```bash
curl -X DELETE http://localhost:8787/api/v1/admin/products/BAK2000 \
  -H "Authorization: Bearer eyJhbGci..."
```

```json
{ "revision": 43, "hash": "e5f6g7h8..." }
```

### POST /api/v1/admin/rehash

Recalcula el hash del catálogo. Usar después de tocar D1 a mano.

**Headers requeridos:**
```
Authorization: Bearer <token>
```

```bash
curl -X POST http://localhost:8787/api/v1/admin/rehash \
  -H "Authorization: Bearer eyJhbGci..."
```

```json
{ "hash": "e5f6g7h8..." }
```

## Seguridad

- **Autenticación fail-closed:** sin `JWT_SECRET`, NADIE administra.
- **Contraseña hasheada:** PBKDF2-HMAC-SHA256 + salt, almacenada en `auth`.
  Si `auth` está vacía, usa el secret `ADMIN_PASSWORD` como fallback.
- **JWT:** 24 horas de TTL. Firma y verificación con `JWT_SECRET`.
- **CORS:** `*` (abierto). El backend es público para el sync; la admin
  requiere token.
- **Rate limiting:** no implementado (Cloudflare lo maneja en el edge).

## Tablas D1

| Tabla | Propósito |
|-------|-----------|
| `metadata` | Singleton: `revision_number`, `catalog_hash`, `backup_state` |
| `products` | Catálogo activo (PK: `codigo_unico`) |
| `products_bak` | Backup rotativo 1 |
| `products_bak2` | Backup rotativo 2 |
| `auth` | Credenciales admin (hash PBKDF2 + salt) |
| `categorias` | Taxonomía (reservada, pendiente de poblar) |
| `producto_categoria` | Relación producto → categoría |

## Hash canónico

Ver `src/hash.ts`: ordenar por `codigo_unico` con comparación por **unidades
UTF-16** (portable, NO `localeCompare`) → serializar
`{codigo_unico, nombre, precios}` con precios redondeados → `JSON.stringify`
sin espacios → SHA-256 hex. El cliente Android verifica la integridad local con
un port exacto (`CatalogHash.kt`, golden tests en `hash.test.ts`).

## Bump de revisión

- **Camino normal:** endpoints admin (POST/DELETE/import/rollback): `revision +1`
  + hash recalculado en el MISMO batch.
- **Manual:** `scripts/bump.sql` sube el contador; después `POST /api/v1/admin/rehash`.

## CI/CD

El workflow `.github/workflows/backend.yml` se ejecuta al pushear a `main`
con cambios en `backend/`:

1. `schema.sql` → estructura completa (idempotente)
2. `migrate.sql` → backups + seed (idempotente)
3. `ALTER TABLE` → agrega `backup_state` si falta (base vieja, tolera error)
4. `wrangler deploy` → deploy del Worker

Secrets de GitHub requeridos:
- `CLOUDFLARE_API_TOKEN`
- `CLOUDFLARE_ACCOUNT_ID`
- `ADMIN_USER` (opcional)
- `ADMIN_PASSWORD` (opcional)
- `JWT_SECRET` (opcional)

## Pendiente

- Categorías: poblar `categorias` (seed desde los "tipos" de sde11) + filtro en
  el teclado. Las tablas ya están reservadas en el esquema.
