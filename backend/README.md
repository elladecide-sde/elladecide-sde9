# LOLA Backend — Cloudflare Worker + D1

Sync **offline-first** del catálogo de LOLA, por **HASH + catálogo completo**.

- **D1** = SQLite serverless (la base).
- **Worker** = TypeScript (el runtime de la API).
- **Clientes** = teclado Android, editor de fotos (sde11), extensión, futuras apps:
  todos hablan el mismo contrato `version → sync`.

## Estructura

```
backend/
├── schema.sql              ← esquema D1: metadata, config, products, backups, auth, categorias
├── src/
│   ├── index.ts            ← Worker: rutas version/sync v1+v2/sync-config/admin
│   ├── hash.ts             ← hash canónico SHA-256 del catálogo (NO tocar)
│   ├── hash.test.ts        ← golden tests del hash (paridad Android)
│   ├── codigoUnico.ts      ← regla de codigo_unico (paridad exacta con pages/codigoUnico.js)
│   ├── codigoUnico.test.ts ← tests del generador de códigos
│   ├── modelo.ts           ← modelo de producto + fórmula de precios (fuente de verdad)
│   ├── modelo.test.ts      ← tests de derivación de costos/listas
│   ├── importCatalogo.ts   ← núcleo compartido: parseo + precios + códigos (+ categoria/fabrica)
│   ├── importCatalogo.test.ts
│   └── auth.ts             ← JWT, PBKDF2, comparación segura
├── scripts/
│   ├── import-catalogo.ts  ← CLI (dry-run + subida + mapa para sde11)
│   ├── migrate.sql         ← migraciones idempotentes (backups + seed)
│   ├── reset.sql           ← DROP total de tablas (reseteo de base)
│   └── bump.sql            ← bump manual de revisión (fallback)
├── wrangler.jsonc          ← config del Worker + binding D1
└── package.json            ← scripts db:init / db:migrate / db:reset / typecheck / test
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

## Modelo de producto (v2)

El dato base es **descripción + costo**; de ahí se derivan y se conservan todos
los cálculos (fuente de verdad: `src/modelo.ts`):

```
costo_iva  = Lista 4 / (1 + inversaLista/100)      = Lista 4 / 1,50
costo_neto = costo_iva / (1 + IVA/100)             = Lista 4 / (1,50 × 1,21)
Lista n    = round(costo_neto × (1 + IVA/100) × factor_n)
```

Nada se descarta: `costo_neto`, `costo_iva` y las 4 listas viven en la fila
(`products` tiene 12 columnas). Además de `categoria` y `fabrica`, la fila
guarda `codigo` (código de fábrica/comercial, ej. `"404 / 425"`) y `oferta`
(0/1; si es oferta, las 4 listas iguales al precio de oferta). Esos dos campos
**solo viajan en v2** (modo `predeterminado`): el sync v1 es contrato
congelado. IVA y factores son configurables (CLI `--iva`, `--listas`…; UI en
`pages/`).

## Sync (por HASH, catálogo completo)

```
1. GET /api/v1/version → { revision, hash }
2. hash_local == hash → FIN (0 requests más)
3. hash distinto → GET /api/v1/sync (v1) o /api/v1/sync/v2 → catálogo COMPLETO
   → el cliente reemplaza su base local
```

- **`GET /api/v1/sync` (v1)** → contrato **ADITIVO**: los campos viejos
  `{ codigo_unico, nombre, precios[4] }` siguen byte idénticos (Android los
  exige) y se les AGREGAN `fabrica`, `codigo`, `descripcion`, `costo_neto`,
  `oferta`. Los clientes viejos ignoran lo nuevo; los nuevos dejan de
  adivinar (4 precios iguales → leer `oferta`).
- **`GET /api/v1/sync/v2`** → contrato nuevo, la forma depende de
  `config.sync_mode` (ver abajo).

No hay tombstones ni revisión por artículo: el reemplazo es el modelo. Cuando los
precios cambian, cambian TODOS, así que el resync total es el camino natural
(~300 KB, 1-2 s sobre wifi). El hash evita bajar si nada cambió.

### Modo de sync (config en D1)

La fila `config.sync_mode` define cómo responde `/api/v1/sync/v2`:

| Modo | `GET /api/v1/sync/v2` devuelve por producto |
|------|----------------------------------------------|
| `predeterminado` (default) | **Todos los campos de la fila**: `{ codigo_unico, descripcion, precios[4], categoria, fabrica, codigo, oferta, costo_neto, costo_iva, updated_at }` |
| `descripcion-costos` | `{ descripcion, costo }` (`costo` = `costo_neto`; sin listas, sin categoría, sin fábrica, sin código, sin oferta) |

Se lee con `GET /api/v1/sync-config` (libre) y se escribe con
`POST /api/v1/admin/sync-config { modo }` (JWT). **No versiona el catálogo**
(no sube revisión ni cambia el hash) y `/api/v1/sync` (v1) jamás lo mira: es
solo el formato del contrato nuevo.

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
Lista 4 → inversa (`costo_iva = L4 / (1 + inversa/100)`, default 1,50) →
`costo_neto = costo_iva / (1 + IVA/100)` (default 21%) → 4 listas con los
factores configurados. Oferta (termina en `.`, `oferta` o `liq`) → las 4
iguales al precio del Excel.

### Flags del CLI (`scripts/import-catalogo.ts`)

| Flag | Default | Descripción |
|------|---------|-------------|
| `--col-nombre=N` | `0` | Columna del nombre |
| `--col-precio=N` | `1` | Columna del precio (Lista 4) |
| `--col-codigo=N` | `-1` (auto) | Columna de código opcional |
| `--col-categoria=N` | `-1` (vacía) | Columna de categoría |
| `--col-fabrica=N` | `-1` (vacía) | Columna de fábrica |
| `--skip=N` | `1` | Filas de header a saltar (`0` si no hay) |
| `--iva=N` | `21` | IVA en % |
| `--inversa-iva=N` | `21` | Inversa del IVA en % (para volver al costo neto) |
| `--inversa-lista=N` | `50` | Inversa de la L4 en % (`50` → dividir por 1,50) |
| `--listas=a,b,c,d` | `2.32,2.12,1.65,1.50` | Factores de L1..L4 |
| `--oferta-keywords=x,y` | `oferta,liq` | Palabras que marcan oferta (además de terminar en `.`) |
| `--marcas=archivo.json` | — | Lista de carpetas de `IMAGENES-LOLA` para códigos más limpios |
| `--api=URL` | — | Si se pasa, sube al Worker (requiere `--user`/`--password`) |
| `--user=U` / `--password=P` | — | Credenciales de login del Worker |
| `--out=DIR` | `scripts/salida` | Directorio de salidas |

Salidas en `--out`: `catalogo.json` (para D1) y `mapa-catalogo.json` (para sde11,
mismo shape que `GET /api/v1/mapa-catalogo`).

## Setup (una sola vez)

```bash
cd backend
npm install
npx wrangler login

# 1) Crear la base D1 y copiar el database_id al wrangler.jsonc
npx wrangler d1 create lola-db

# 2) Si la base es VIEJA (sin columna backup_state), migrar antes de inicializar:
npx wrangler d1 execute lola-db --remote --command \
  "ALTER TABLE metadata ADD COLUMN backup_state TEXT NOT NULL DEFAULT '{\"newest\":\"bak2\"}'"

# 3) Crear las tablas (schema.sql crea TODO incluyendo backups + seed)
npm run db:init:local     # dev (SQLite local)
npm run db:init           # producción

# 4) Migración idempotente (backups + seed, seguro correr múltiples veces)
npm run db:migrate:local  # dev
npm run db:migrate        # producción

# 5) Limpieza total (SOLO DEV / RESET COMPLETO si necesitás arrancar de cero)
npm run db:reset:local    # dev
npm run db:reset          # producción

# 5b) REGENERAR solo el catálogo (borra products + backups, conserva auth y
#     config, recrea con el esquema vigente — después volvé a subir el Excel)
npm run db:recreate:local # dev
npm run db:recreate       # producción

# 6) Secrets de admin (JWT)
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

| Método | Ruta | Auth | Qué hace |
|--------|-----|------|----------|
| GET | `/api/v1/version` | libre | `{ revision, hash }` |
| GET | `/api/v1/sync` | libre | Catálogo completo — **v1 aditivo** (viejos + campos nuevos) |
| GET | `/api/v1/sync/v2` | libre | Catálogo completo — contrato nuevo según `sync_mode` |
| GET | `/api/v1/sync-config` | libre | `{ modo, opciones }` |
| GET | `/api/v1/mapa-catalogo` | libre | Mapa para el editor de fotos (sde11) |
| POST | `/api/v1/auth/login` | credenciales | JWT (24 h) |
| POST | `/api/v1/auth/change-password` | JWT | Cambia la contraseña admin |
| POST | `/api/v1/admin/products` | JWT | Upsert o reemplazo (`replace: true`) |
| POST | `/api/v1/admin/import` | JWT | Importa filas de Excel (el worker calcula) |
| POST | `/api/v1/admin/sync-config` | JWT | Cambia el modo de sync (`{ modo }`) |
| POST | `/api/v1/admin/rollback` | JWT | Restaura `bak` / `bak2` |
| DELETE | `/api/v1/admin/products/:codigo_unico` | JWT | Borra un producto |
| POST | `/api/v1/admin/rehash` | JWT | Recalcula el hash |

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
      "precios": [1200, 1100, 1000, 900],
      "fabrica": "BAKHOU",
      "codigo": "2103",
      "descripcion": "BAKHOU 2000 LISO ALGODON HOMBRE",
      "costo_neto": 517.02,
      "oferta": false
    }
  ]
}
```

`precios` = `[L1, L2, L3, L4]` (Lista 1 a 4, enteros redondeados).
`nombre` y `descripcion` son la misma cadena con dos claves (compatibilidad).
`oferta: true` ⇒ las 4 listas vienen iguales en `precios` — confiar en el
campo, no en la comparación.

### GET /api/v1/sync/v2

Catálogo completo en el **contrato nuevo**. La forma de cada producto depende de
`config.sync_mode` (ver "Modo de sync"). `GET /api/v1/sync` (v1) no cambia.

**Headers:** ninguno especial.

```bash
curl http://localhost:8787/api/v1/sync/v2
```

Modo `predeterminado` (default):

```json
{
  "revision": 42,
  "hash": "a1b2c3d4...",
  "format": "predeterminado",
  "products": [
    {
      "codigo_unico": "BAK2000",
      "descripcion": "BAKHOU 2000 LISO ALGODON HOMBRE",
      "precios": [1200, 1100, 1000, 900],
      "categoria": "",
      "fabrica": "BAKHOU",
      "codigo": "2103",
      "oferta": false,
      "costo_neto": 517.02,
      "costo_iva": 625.6,
      "updated_at": "2026-10-02 21:03:11"
    }
  ]
}
```

Modo `descripcion-costos`:

```json
{
  "revision": 42,
  "hash": "a1b2c3d4...",
  "format": "descripcion-costos",
  "products": [
    { "descripcion": "BAKHOU 2000 LISO ALGODON HOMBRE", "costo": 517.02 }
  ]
}
```

`costo` = `costo_neto` (sin IVA). En este modo no hay listas, costo+IVA,
categoría, fábrica, código ni oferta.

### GET /api/v1/sync-config

Devuelve el modo de sync vigente y las opciones válidas. Lectura libre: es solo
un formato.

**Headers:** ninguno especial.

```bash
curl http://localhost:8787/api/v1/sync-config
```

```json
{ "modo": "predeterminado", "opciones": ["predeterminado", "descripcion-costos"] }
```

### POST /api/v1/admin/sync-config

Cambia el modo de sync. **No versiona el catálogo** (no toca revisión ni hash):
solo cambia la forma en que responde `/api/v1/sync/v2`.

**Headers requeridos:**
```
Content-Type: application/json
Authorization: Bearer <token>
```

```bash
curl -X POST http://localhost:8787/api/v1/admin/sync-config \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer eyJhbGci..." \
  -d '{"modo": "descripcion-costos"}'
```

```json
{ "modo": "descripcion-costos" }
```

Errores:
- `400` → `modo` inválido (debe ser `predeterminado` o `descripcion-costos`)
- `401` → token inválido/expirado

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
| `config` | Ajustes del operador: `sync_mode` (no versiona el catálogo) |
| `products` | Catálogo activo (PK: `codigo_unico`) |
| `products_bak` | Backup rotativo 1 (mismo esquema que `products`) |
| `products_bak2` | Backup rotativo 2 (mismo esquema que `products`) |
| `auth` | Credenciales admin (hash PBKDF2 + salt) |
| `categorias` | Taxonomía (reservada, pendiente de poblar) |
| `producto_categoria` | Relación producto → categoría |

### Columnas de `products` (y sus backups — 12 columnas, idénticas)

| Columna | Tipo | Descripción |
|---------|------|-------------|
| `codigo_unico` | TEXT PK | Identidad estable del producto |
| `fabrica` | TEXT | Se envía en v2 (`predeterminado`) |
| `codigo` | TEXT | Código de fábrica/comercial (`"404 / 425"`). **No** es identidad |
| `descripcion` | TEXT | Nombre completo (antes `nombre`) |
| `costo_neto` | REAL | Sin IVA: `L4 / (1,50 × 1,21)` |
| `costo_iva` | REAL | Costo + IVA: `L4 / 1,50` |
| `lista1` … `lista4` | REAL | Las 4 listas |
| `categoria` | TEXT | Rubro del producto (**no** confundir con `oferta`) |
| `oferta` | INTEGER 0/1 | En oferta → las 4 listas iguales al precio de oferta |
| `updated_at` | TEXT | `datetime('now')` |

`metadata`: `key`, `revision_number`, `catalog_hash`, `backup_state`, `updated_at`.
`config`: `key`, `value`, `updated_at` (fila sembrada: `sync_mode = 'predeterminado'`).

> Si cambiás el modelo, cambialo en `schema.sql` **y** en `scripts/migrate.sql`
> (las tablas de backup son copias exactas; el `INSERT ... SELECT` del worker
> falla si no calzan las columnas).

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

1. `ALTER TABLE` → agrega `backup_state` si falta (base vieja, tolera error)
2. `schema.sql` → estructura completa (idempotente)
3. `migrate.sql` → backups + seed (idempotente)
4. `wrangler deploy` → deploy del Worker + `secret put` de ADMIN_USER /
   ADMIN_PASSWORD / JWT_SECRET (solo si el secreto de GitHub correspondiente
   está definido)

Secrets de GitHub requeridos:
- `CLOUDFLARE_API_TOKEN`
- `CLOUDFLARE_ACCOUNT_ID`
- `ADMIN_USER` (opcional)
- `ADMIN_PASSWORD` (opcional)
- `JWT_SECRET` (opcional)

## Pendiente

- Categorías: poblar `categorias` (seed desde los "tipos" de sde11) + filtro en
  el teclado. Las tablas ya están reservadas en el esquema.
