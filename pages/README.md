# LOLA — Interfaz de subida de catálogo (Cloudflare Pages)

Web estática para subir el catálogo de LOLA. **La página es la generadora**: lee
el Excel (SheetJS), calcula las 4 listas y genera `codigo_unico` en el navegador
(`codigoUnico.js`, port EXACTO del backend). El Worker es el mensajero que
**reemplaza** el catálogo con backup rotativo.

## Qué hace

1. Elegís el Excel de precios (`.xlsx`/`.xls`).
2. Configurás columnas (nombre, precio, código) y filas de header.
3. **Generar catálogo** → parsea en el navegador, calcula las 4 listas y genera
   `codigo_unico` (misma regla que el CLI: `abrev(marca) + modelo`).
4. **Subir a la nube** → `POST /api/v1/admin/products` con `{ products, replace: true }`
   → el Worker hace backup rotativo + borra + inserta, atómico.

La lógica de precios es un port 1:1 del generador Python:
`costo = precioLista4 / (1.21 × 1.50)` → `lista_i = round(costo × 1.21 × factor)`.
Oferta/liquidación (nombre termina en `.`, `oferta` o `liq`) → las 4 listas
iguales al precio del Excel.

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
├── index.html        ← UI de subida
├── codigoUnico.js    ← regla de codigo_unico (port 1:1 del backend)
├── app.js            ← login + parseo + precios + subida (replace)
├── styles.css        ← tema oscuro LOLA
└── xlsx.full.min.js  ← SheetJS (copiado de la extensión)
```