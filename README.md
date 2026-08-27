# LOLA para Android — IME (teclado) + Backend Cloudflare

Port nativo de la extensión LOLA (WhatsApp Web) a un **teclado Android (IME)** que busca productos y autocompleta texto o inserta imágenes, con sync **offline-first** contra Cloudflare D1 por **hash + catálogo completo**.

> `elladecide-sde7` es SOLO referencia (buscador, dos modos de resultado PRECIOS: texto y PNG, y matcheo de imágenes). Este repo es la implementación nueva.

## Estructura

```
├── backend/     ← Cloudflare Worker (TypeScript) + D1 (SQLite)   ✅
├── android/     ← IME Kotlin + Compose + Room                    ✅ fases 2-5
└── pages/       ← web de subida de catálogo (genera + replace)   ✅
```

## Estado

- [x] **Fase 1 — Backend**: Worker + D1 + sync por hash + reemplazo con 2 backups + rollback.
- [x] **Fase 2 — Android**: `InputMethodService` + Room + buscador portado.
- [x] **Fase 3 — Inserción**: `commitText()` (texto) y `commitContent()` (PNG).
- [x] **Fase 4 — Carrito**: carrito + lista + descuentos + totales (texto y PNG).
- [x] **Fase 5 — Imágenes**: carpeta local (SAF) + matcheo fábrica/modelo + inserción.
- [x] **Import Excel → D1**: regla `codigo_unico` (abrev(marca) + modelo), web (pages) y CLI.
- [x] **Editor de fotos (sde11)**: consume `mapa-catalogo` y renombra `CODIGO_UNICO_COLOR.jpg`.

## Principios

- **Offline-first**: una vez sincronizado, el teclado no necesita red.
- **Anti-ban**: sin `AccessibilityServices`; el usuario siempre aprieta "Enviar".
- **Permisos mínimos**: nada de SMS/contactos/notificaciones.

## Pendientes conocidos

- Categorías en la nube (tablas reservadas): seed desde los "tipos" de sde11 + filtro en el teclado.
- Neurona/diccionario del usuario → enchufar al `SuggestionProvider` (contrato ya definido).