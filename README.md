# LOLA para Android — IME (teclado) + Backend Cloudflare

Port nativo de la extensión LOLA (WhatsApp Web) a un **teclado Android (IME)** que busca productos y autocompleta texto o inserta imágenes, con sync **offline-first** contra Cloudflare D1.

> `elladecide-sde7` es SOLO referencia (buscador, dos modos de resultado PRECIOS: texto y PNG, y matcheo de imágenes). Este repo es la implementación nueva.

## Estructura

```
├── backend/     ← Cloudflare Worker (TypeScript) + D1 (SQLite)   ✅ fase 1
├── android/     ← IME Kotlin + Compose + Room                    ✅ fases 2-5
└── pages/       ← interfaz web de subida de catálogo (Pages)     ✅
```

## Estado

- [x] **Fase 1 — Backend**: Worker + esquema D1 + sync por revisión/hash + bump.
- [x] **Fase 2 — Android**: `InputMethodService` + Room + buscador portado.
- [x] **Fase 3 — Inserción**: `commitText()` (texto) y `commitContent()` (PNG) funcionando.
- [x] **Fase 4 — Carrito**: carrito + lista + descuentos + totales (texto y PNG).
- [x] **Fase 5 — Imágenes**: carpeta local (SAF) + matcheo fábrica/modelo + inserción.

## Principios

- **Offline-first**: una vez sincronizado, el teclado no necesita red.
- **Anti-ban**: sin `AccessibilityServices`; el usuario siempre aprieta "Enviar".
- **Permisos mínimos**: nada de SMS/contactos/notificaciones.

## Pendientes conocidos

- Conectar el cliente Android (Room) al Worker real (apuntar `base_url`).
- Seed inicial de D1 con el catálogo real (2573 productos) vía `npm run import`.
- Portar el consumidor del `mapa-catalogo.json` al editor de fotos (sde11).
