# LOLA Android — Teclado IME

Port nativo de la extensión LOLA a un **teclado Android** (`InputMethodService`).
Busca productos del catálogo y autocompleta mensajes de precio en WhatsApp
Business (y cualquier app con campo de texto).

## Stack

- **Kotlin 2.0 + Jetpack Compose** (UI del teclado).
- **Room** (SQLite local, offline-first).
- **InputMethodService** (API nativa de teclados; sin AccessibilityServices).

## Estructura

```
android/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradle/libs.versions.toml + wrapper/
└── app/src/main/
    ├── AndroidManifest.xml           ← servicio IME (BIND_INPUT_METHOD)
    ├── assets/seed_catalog.json      ← seed offline de ejemplo (10 productos)
    ├── res/xml/method.xml
    └── java/com/elladecide/lola/
        ├── LolaImeService.kt         ← InputMethodService + ComposeView
        ├── KeyboardController.kt     ← estado + orquestación catálogo/sync
        ├── Mensaje.kt                ← formato de moneda + nombres de lista
        ├── Precio.kt                 ← carrito + motor de precios (buildMessage)
        ├── PrecioPngRenderer.kt     ← renderiza el PNG (filas + caja de TOTAL)
        ├── FolderPickerActivity.kt  ← selector de carpeta de imágenes (SAF)
        ├── data/                     ← Room + imágenes
        │   ├── SyncRepository.kt     ← sync contra el backend Cloudflare
        │   ├── SeedCatalog.kt        ← seed inicial desde assets
        │   └── ImageRepository.kt    ← escaneo + matcheo de imágenes
        ├── search/SearchEngine.kt    ← buscador fuzzy portado
        └── ui/KeyboardScreen.kt      ← UI Compose
```

## Requisitos

- Android Studio (con SDK 34) y JDK 17.
- Al abrir el proyecto, Android Studio genera el `gradle-wrapper.jar` que falta
  (no está versionado; si avisa, dale "Fix Gradle wrapper").

## Correr

1. `File → Open` → seleccioná la carpeta `android/`.
2. Dejá que sincronice Gradle.
3. Corré en un emulador o dispositivo (API 26+).
4. Habilitá el teclado: **Ajustes → Sistema → Idiomas e introducción →
   Teclado virtual → LOLA**, y seleccionalo como teclado activo.
5. Abrí WhatsApp (o cualquier campo de texto) → tocá el input → aparecerá LOLA.

## Qué funciona hoy (fases 2 + 3 + carrito)

- Buscador local (tokenizador + AND + ranking con typo) — port 1:1 de la extensión.
- **Carrito**: agregá varios productos y elegí lista (1-4 o "todas") por ítem.
- **Descuento global** (0/10/15/20%) + oferta (sin descuento, con recargo tarjeta).
- **Totales** tarjeta/efectivo.
- Enviar el pedido completo:
  - modo **TEXTO** vía `commitText()`.
  - modo **PNG** vía `commitContent()` (imagen con filas + caja de TOTAL, estilo
    de la extensión). Fallback a portapapeles si la app no acepta imágenes.
- **Imágenes**: botón 📂 para elegir la carpeta (SAF, persistida), matcheo
  producto→imagen por convención (fábrica + modelo + fallback), miniatura **lazy
  con Coil** (como Google Photos) y botón 📷 para insertar la foto vía `commitContent()`.
- El usuario SIEMPRE aprieta "Enviar" a mano.

## Siguiente

- [ ] **Descuento MANUAL por ítem + concepto N/U + valorModo** (switches restantes
      de `buildLineParts`).
- [ ] **Pantalla de configuración**: BASE_URL, secret, modo de imágenes.
- [ ] **Sync real**: apuntar `SyncRepository.BASE_URL` a tu Worker y seedear D1
      (el `codigo_unico` se genera con la regla pendiente de backend/schema.sql).

## Nota sobre sync

`SyncRepository.BASE_URL = "http://10.0.2.2:8787"` (emulador → localhost del
host). Si levantás el backend con `npm run dev`, el teclado sincroniza solo; si
no hay backend, usa el seed offline. En producción, poné la URL del Worker.
