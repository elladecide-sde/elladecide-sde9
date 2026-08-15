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

La clave primaria del catálogo es **`codigo_unico`**, generado de forma
determinística al migrar el Excel → D1, derivado del nombre + el código de
artículo (modelo):

```
"BELEN CONJUNTO ARMADO 1420"  →  "BELCONARM1420"
   BEL   CON   ARM   1420        BELCONARM + 1420
```

- Es **estable** (no depende de un contador ni del orden del Excel).
- El `codigo` del `catalog.js` de la extensión (prefijo + contador) **NO es
  identidad**; es solo un separador interno.

> El matcheo de imágenes es OTRO tema y se mantiene con el método simple
> (fábrica + modelo + fallback de descripciones) para que el vendedor no tenga
> que nombrar sus archivos con un código largo.

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

# 3) Secret de admin
#    Local: creá backend/.dev.vars con  ADMIN_SECRET=devsecret
#    Producción: npx wrangler secret put ADMIN_SECRET
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

`products[]` = `{ codigo_unico, nombre, precios: [L1, L2, L3, L4] }` (solo los tocados desde `N`).
`deleted[]` = lista de `codigo_unico` borrados desde `N`.

### Admin (header `X-Admin-Secret: <secret>`)

| Método | Ruta | Body | Efecto |
| --- | --- | --- | --- |
| POST | `/api/v1/admin/products` | 1 producto, array, o `{ products: [] }` | upsert + bump + hash |
| DELETE | `/api/v1/admin/products/:codigo_unico` | — | tombstone + bump + hash |
| POST | `/api/v1/admin/rehash` | — | recalcula hash (tras editar D1 a mano) |

**Seguridad:** la administración es **fail-closed**. Si `ADMIN_SECRET` no está
configurado, NADIE puede administrar (devuelve 401). No hay modo "abierto".

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

- Script de **import/migración** Excel → D1, que genere `codigo_unico` con la
  regla `abreviación(nombre) + modelo` (ej. BELCONARM1420).
- Conectar el cliente Android (Room) a este contrato.
