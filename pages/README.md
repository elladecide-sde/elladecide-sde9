# LOLA — Panel de catálogo (Cloudflare Pages)

Web estática para administrar el catálogo de LOLA. **La página es la generadora**:
lee el Excel (SheetJS), calcula las 4 listas y genera `codigo_unico` en el
navegador (`codigoUnico.js`, port EXACTO del backend). El Worker es el mensajero
que **reemplaza** el catálogo con backup rotativo.

## Secciones

El panel es una SPA con 4 tabs (visibles solo con sesión iniciada):

| Tab | Qué hace |
| --- | --- |
| **📦 Inventario** | Catálogo completo, buscador fuzzy, editar y eliminar artículos |
| **📂 Catálogo** | Excel → columnas → generar → subir (replace) + rollback |
| **⚙️ Configuración** | Precios (IVA/factores), modo de sync y contraseña |
| **🗂️ Administración** | Categorías / fábricas *(todavía vacía)* |

### Inventario

- Lee `GET /api/v1/sync/v2` (una sola vez; se reutiliza al volver a la tab) y
  pagina el listado de a 120 filas.
- **Buscador fuzzy token por token**: la query se parte por espacios y *todos*
  los tokens tienen que pegar (AND), en cualquier orden. Normaliza a minúsculas
  y sin acentos. Por token:
  1. substring sobre `codigo + descripción + categoría + fábrica`
     (bonus si arranca una palabra → "cocina" pega antes en "cocina express");
  2. si no, tolerancia a typos contra palabras sueltas: Levenshtein con corte
     ≤ 1 error (tokens ≥ 4 letras) o ≤ 2 errores (≥ 7 letras) →
     "express" pega en "espresso", "cafetra" en "cafetera";
  3. si no, el token descarta el artículo.
- **Editar** → modal con descripción, L1..L4, categoría y fábrica. Los costos se
  derivan de la L4 con la config de precios actual y se guardan con
  `POST /api/v1/admin/products` (**upsert parcial**, sin `replace`).
  Botón "Recalcula L1..L3 desde la L4".
- **Eliminar** → `DELETE /api/v1/admin/products/:codigo_unico`.
- Ambas acciones suben la revisión: las apps se resincronizan solas.
- Si el modo de sync es `descripcion-costos`, v2 no trae código ni metadatos →
  el inventario cae al contrato v1 (solo lectura + borrado) y **bloquea la
  edición** para no pisar categoría/fábrica con strings vacíos.

## Generación y subida

1. Elegís el Excel de precios (`.xlsx`/`.xls`).
2. Configurás columnas (nombre, precio, código, categoría, fábrica) y filas de header.
3. En **Configuración** ajustás **Precios**: IVA %, factor inverso de la L4 y los
   factores de L1..L3 (persisten en `localStorage`; defaults 21 / 50 / 2,32 /
   2,12 / 1,65).
4. En **Configuración** elegís el **Modo de sync** (`predeterminado` o
   `descripcion-costos`) → `POST /api/v1/admin/sync-config`; el worker lo guarda
   en `config` y queda para `GET /api/v1/sync/v2`.
5. **Generar catálogo** → parsea en el navegador, calcula costo neto, costo+IVA
   y las 4 listas, y genera `codigo_unico` (misma regla que el CLI:
   `abrev(marca) + modelo`).
6. **Subir a la nube** → `POST /api/v1/admin/products` con
   `{ products, replace: true }` → el Worker hace backup rotativo + borra +
   inserta, atómico.

La lógica de precios es un port 1:1 de `backend/src/modelo.ts`:

```
costo+IVA  = Lista 4 / (1 + inversa/100)          (inversa 50 → /1,50)
costo neto = (costo + IVA) / (1 + IVA/100)        (IVA 21 → /1,21)
Lista n    = round(costo neto × (1 + IVA/100) × factor_n)
```

IVA y factores vienen de la config (no están hardcodeados). Oferta/liquidación
(nombre termina en `.`, `oferta` o `liq`) → las 4 listas iguales al precio del
Excel.

La vista previa muestra las **primeras 10 filas** del modelo (Código, Descripción,
Costo neto, Costo+IVA, L1..L4) — la lista completa y editable está en
**Inventario**. La subida manda el modelo completo
(`codigo_unico`, `descripcion`, `costo_neto`, `costo_iva`, `precios`,
`categoria`, `fabrica`) — el worker acepta también el shape viejo
(`nombre` + `precios`) por compatibilidad.

## Una sola forma

- `pages/codigoUnico.js` y `backend/src/codigoUnico.ts` están verificados en
  **paridad exacta** (mismos códigos sobre el catálogo real). NO tocar uno sin
  el otro.
- Fallback: `POST /api/v1/admin/import` (el Worker calcula si mandás las filas).
- Último recurso: parseo local (CLI `npm run import`).

## Requisito previo: credenciales JWT

El Worker administra en modo **fail-closed**: sin `JWT_SECRET` configurado no
acepta subidas. La web primero hace `POST /api/v1/auth/login` y guarda el token
en `localStorage`.

```bash
cd backend
npx wrangler secret put ADMIN_USER
npx wrangler secret put ADMIN_PASSWORD
npx wrangler secret put JWT_SECRET
```

## Correr local

```bash
npx serve pages          # o cualquier server estático
```

## Deploy a Cloudflare Pages

```bash
npx wrangler pages deploy pages --project-name=lola-catalogo
```

URL: `https://lola-catalogo.pages.dev`.

## Estructura

```
pages/
├── index.html        ← UI: login + tabs (Inventario / Catálogo / Config / Admin) + modal de edición
├── codigoUnico.js    ← regla de codigo_unico (port 1:1 del backend)
├── app.js            ← login + navegación + inventario/buscador + precios + subida (replace)
├── styles.css        ← tema oscuro LOLA
└── xlsx.full.min.js  ← SheetJS (copiado de la extensión)
```