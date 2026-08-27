# LOLA Backend — Cloudflare Worker + D1

Sync **offline-first** del catálogo de LOLA, por **revisión + hash**.

- **D1** = SQLite serverless (la base).
- **Worker** = TypeScript (el runtime de la API; Go no es primera clase en Workers).

## Estructura

```
backend/
├── schema.sql          ← tablas: metadata, products, tombstones
├── src/
│   ├── index.ts        ← Worker: rutas version/sync/admin
│   └── hash.ts         ← hash canónico SHA-256 del catálogo
├── scripts/bump.sql    ← bump manual de revisión (fallback)
├── wrangler.jsonc      ← config del Worker + binding D1
└── package.json
```

## Identidad: `codigo_unico`

La clave primaria del catálogo es **`codigo_unico`**, generada de forma
determinística por el **import** (fuente de verdad), derivada de la marca + el
código de artículo:

```
"BAKHOU 2000 LISO ALGODON HOMBRE"  →  "BAK2000"
   BAK   (2000)                        BAK + 2000
```

- Es **estable** (no depende de un contador ni del orden del Excel).
- Productos sin modelo (los "LIQ.") usan la descripción para desambiguar.
- Las colisiones se resuelven agregando palabras posteriores al modelo,
  creciendo las abreviaciones y, de último, con un sufijo hash (ver
  `src/codigoUnico.ts`). **No hay orden-dependencia.**
- El `codigo` del `catalog.js` de la extensión (prefijo + contador) **NO es
  identidad**; es solo un separador interno.

> El matcheo de imágenes es el consumidor de `codigo_unico`: el editor de fotos
> (sde11) renombra cada foto `CODIGO_UNICO_COLOR.jpg` contra el mapa que
> exporta el import (`mapa-catalogo.json`). La búsqueda del USUARIO sigue siendo
> fuzzy por nombre; el `codigo_unico` es la llave interna, nunca la de búsqueda.

## Import Excel → D1 (fuente de verdad)

```bash
# Solo calcular (dry-run): genera catalogo.json + mapa-catalogo.json
npm run import -- ../catálogo.xlsx --out ./salida

# Subir al Worker (local o producción)
npm run import -- ../catálogo.xlsx --api http://localhost:8787 --user admin --password devpass
```

El Excel de ADM viene con **2 campos**: nombre (todo mezclado: marca + modelo +
descripción) y precio de Lista 4. El import hace la inversa
(`costo = precio / (1.21 * 1.50)`), recalcula las 4 listas y marca como precio
fijo los nombres que terminan en `.`, `oferta` o `liq`.

Flags útiles: `--col-nombre`, `--col-precio`, `--skip`, `--marcas=archivo.json`
(lista de carpetas de `IMAGENES-LOLA` para códigos más limpios), `--dry-run`,
`--chunk` (sube en lotes de 100 por request).

Salidas en `--out`:
- `catalogo.json` — los productos listos para D1 (`{ codigo_unico, nombre, precios }`).
- `mapa-catalogo.json` — el mapa `{ codigo_unico, nombre, modelo }` para sde11
  (mismo shape que `GET /api/v1/mapa-catalogo`).

> **sde11 consume el mapa** (no lo recalcula). Lo baja del Worker cuando la
> `revision` cambió, o usa el `mapa-catalogo.json` local del import. Así hay
> UNA sola fuente de verdad para `codigo_unico` y no hay ambigüedad.

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
| GET | `/api/v1/version` | `{ revision: number, hash: string }` |
| GET | `/api/v1/sync?last_revision=N` | `{ revision, hash, products: [], deleted: [] }` |
| GET | `/api/v1/mapa-catalogo` | `{ revision, hash, productos: [{ codigo_unico, nombre, modelo }] }` |

`products[]` = `{ codigo_unico, nombre, precios: [L1, L2, L3, L4] }` (solo los tocados desde `N`).
`deleted[]` = lista de `codigo_unico` borrados desde `N`.

### Admin (autenticación JWT)

La administración usa **JWT HS256** (ver `src/auth.ts`):

1. `POST /api/v1/auth/login { username, password }` → `{ token, expires_in }` (24h).
2. Mandá `Authorization: Bearer <token>` en cada request admin.

| Método | Ruta | Body | Efecto |
| --- | --- | --- | --- |
| POST | `/api/v1/auth/login` | `{ username, password }` | token JWT |
| POST | `/api/v1/auth/change-password` | `{ current_password, new_password }` | re-hashea la contraseña en D1 |
| POST | `/api/v1/admin/products` | 1 producto, array, o `{ products: [] }` | upsert + bump + hash |
| DELETE | `/api/v1/admin/products/:codigo_unico` | — | tombstone + bump + hash |
| POST | `/api/v1/admin/rehash` | — | recalcula hash (tras editar D1 a mano) |

**Seguridad:** la administración es **fail-closed**. Si `JWT_SECRET` no está
configurado, NADIE puede administrar (devuelve 401/503). No hay modo "abierto".
La contraseña se verifica contra `auth.password_hash` (PBKDF2 + salt) si ya se
cambió; si no, contra el secret `ADMIN_PASSWORD`.

## Flujo de sincronización (cliente)

```
1. GET /version           → { revision, hash }
2. hash_local == hash?    → FIN (offline, 0 requests más)
3. revision_local < ?     → GET /sync?last_revision=revision_local → aplicar deltas
4. revision igual pero hash distinto (corrupción/drift)
                          → GET /sync?last_revision=0 → rebuild completo
```

## Hash canónico (para mantenerlo estable)

Ver `src/hash.ts`. Reglas: ordenar por `codigo_unico` → serializar
`{codigo_unico, nombre, precios}` con precios redondeados → `JSON.stringify`
sin espacios → SHA-256 hex.

## Bump de revisión

- **Camino normal:** usá los endpoints admin (POST/DELETE). Suben `revision_number +1`,
  estampan las filas tocadas y recalculan el hash en la MISMA transacción.
- **Camino manual:** `scripts/bump.sql` sube solo el contador; después pegale a
  `/api/v1/admin/rehash` para regenerar el hash.

## Pendiente (siguiente fase)

- Conectar el cliente Android (Room) a este contrato (apuntar `base_url` al Worker).
- Portar el consumidor del `mapa-catalogo.json` al editor de fotos (sde11):
  renombrar cada foto a `CODIGO_UNICO_COLOR.jpg`.
