# LOLA Backend — Cloudflare Worker + D1

Sync **offline-first** del catálogo de LOLA, por **HASH + catálogo completo**.

- **D1** = SQLite serverless (la base).
- **Worker** = TypeScript (el runtime de la API).
- **Clientes** = teclado Android, editor de fotos (sde11), extensión, futuras apps:
  todos hablan el mismo contrato `version → sync`.

## Estructura

```
backend/
├── schema.sql          ← tablas: metadata, products, products_bak, products_bak2, auth, categorias
├── src/
│   ├── index.ts        ← Worker: rutas version/sync/admin (+ rollback)
│   ├── hash.ts         ← hash canónico SHA-256 del catálogo
│   ├── codigoUnico.ts  ← regla de codigo_unico (paridad exacta con pages/codigoUnico.js)
│   └── importCatalogo.ts ← núcleo compartido: parseo + precios + códigos
├── scripts/import-catalogo.ts  ← CLI (dry-run + subida + mapa para sde11)
├── scripts/bump.sql    ← bump manual de revisión (fallback)
├── wrangler.jsonc      ← config del Worker + binding D1
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
- Colisiones → se agregan palabras posteriores al modelo, se crecen las
  abreviaciones y, de último, un sufijo hash (ver `src/codigoUnico.ts`).
- `pages/codigoUnico.js` es port 1:1 (verificado en paridad exacta). **La página
  genera y el worker solo reemplaza.**

> El editor de fotos (sde11) consume `GET /api/v1/mapa-catalogo` y renombra cada
> foto `CODIGO_UNICO_COLOR.jpg`. La búsqueda del usuario sigue siendo fuzzy por
> nombre; el `codigo_unico` es la llave interna.

## Sync (por HASH, catálogo completo)

```
1. GET /version → { revision, hash }
2. hash_local == hash → FIN (0 requests más)
3. hash distinto → GET /sync → catálogo COMPLETO → el cliente reemplaza su base local
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

# 2) Crear las tablas
npm run db:init:local     # dev (SQLite local)
npm run db:init           # producción
#    ¿Ya inicializaste con el esquema viejo? Corré la migración UNA vez:
npm run db:migrate        # npx wrangler d1 execute lola-db --file ./scripts/migrate.sql

# 3) Secrets de admin (JWT)
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

### Público

| Método | Ruta | Respuesta |
| --- | --- | --- |
| GET | `/api/v1/version` | `{ revision, hash }` |
| GET | `/api/v1/sync` | `{ revision, hash, products: [catálogo completo] }` |
| GET | `/api/v1/mapa-catalogo` | `{ revision, hash, productos: [{ codigo_unico, nombre, modelo }] }` |

`products[]` = `{ codigo_unico, nombre, precios: [L1, L2, L3, L4] }`.

### Admin (autenticación JWT)

1. `POST /api/v1/auth/login { username, password }` → `{ token, expires_in }` (24h).
2. `Authorization: Bearer <token>` en cada request admin.

| Método | Ruta | Body | Efecto |
| --- | --- | --- | --- |
| POST | `/api/v1/auth/login` | `{ username, password }` | token JWT |
| POST | `/api/v1/auth/change-password` | `{ current_password, new_password }` | re-hashea la contraseña |
| POST | `/api/v1/admin/products` | `{ products: [...], replace?: true }` | upsert parcial o reemplazo + backup |
| POST | `/api/v1/admin/import` | `{ filas: [[...]], ... }` | fallback: el worker calcula (importCatalogo) + reemplazo |
| POST | `/api/v1/admin/rollback` | `{ backup: 'bak' \| 'bak2' }` | restaura una versión anterior |
| DELETE | `/api/v1/admin/products/:codigo_unico` | — | borra + bump + hash |
| POST | `/api/v1/admin/rehash` | — | recalcula hash (tras tocar D1 a mano) |

**Seguridad:** administración **fail-closed**. Sin `JWT_SECRET`, NADIE administra.
La contraseña se verifica contra `auth.password_hash` (PBKDF2 + salt) si ya se
cambió; si no, contra el secret `ADMIN_PASSWORD`.

## Hash canónico

Ver `src/hash.ts`: ordenar por `codigo_unico` con comparación por **unidades
UTF-16** (portable, NO `localeCompare`) → serializar
`{codigo_unico, nombre, precios}` con precios redondeados → `JSON.stringify`
sin espacios → SHA-256 hex. El cliente Android verifica la integridad local con
un port exacto (`CatalogHash.kt`, golden tests en `hash.test.ts`).

## Bump de revisión

- **Camino normal:** endpoints admin (POST/DELETE/import/rollback): `revision +1`
  + hash recalculado en el MISMO batch.
- **Manual:** `scripts/bump.sql` sube el contador; después `/api/v1/admin/rehash`.

## Pendiente

- Categorías: poblar `categorias` (seed desde los "tipos" de sde11) + filtro en
  el teclado. Las tablas ya están reservadas en el esquema.