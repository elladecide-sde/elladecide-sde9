# LOLA — Interfaz de subida de catálogo (Cloudflare Pages)

Web estática para subir el catálogo de LOLA: parsea el **Excel de precios (Lista 4)**
directo en el navegador (SheetJS) y lo sube al backend Worker.

## Qué hace

1. Elegís el Excel de precios (`.xlsx`/`.xls`).
2. Configurás las columnas (nombre, precio, código) y filas de header.
3. **Generar catálogo** → parsea en el navegador, calcula las 4 listas y genera `codigo_unico`.
4. **Subir a la nube** → `POST /api/v1/admin/products` al Worker, con `Authorization: Bearer <token>`.

La lógica de precios es un port 1:1 de la extensión (`content.js`) y del generador
Python: `costo = precioLista4 / (1.21 × 1.50)` → `lista_i = round(costo × 1.21 × factor)`.

## Requisito previo: credenciales JWT

El Worker administra en modo **fail-closed**: sin `JWT_SECRET` configurado no
acepta subidas. La web primero hace `POST /api/v1/auth/login` (usuario +
contraseña) y guarda el token en `localStorage` (no se manda al repo).

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

La primera vez crea el proyecto; después actualiza. URL: `https://lola-catalogo.pages.dev`.

## Pendiente (marcado en app.js)

- `codigo_unico`: regla de abreviación + modelo sin confirmar del todo (ver TODO).
- **Método alternativo**: consulta a la base **Access** (fallback), a agregar después.
  El Excel queda como método primario.

## Estructura

```
pages/
├── index.html        ← UI de subida
├── app.js            ← parseo + precios + codigo_unico + subida
├── styles.css        ← tema oscuro LOLA
└── xlsx.full.min.js  ← SheetJS (copiado de la extensión)
```
