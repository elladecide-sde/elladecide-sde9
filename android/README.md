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
        ├── editor/                   ← núcleo IME (puro, testeable en JVM)
        │   ├── EditorSession.kt      ← commit / composing / grapheme-delete / Enter
        │   ├── EditorInfoClassifier.kt ← inputType + imeOptions → EditorProfile
        │   ├── EditorConnection.kt   ← abstracción del InputConnection
        │   ├── AndroidEditorConnection.kt ← adaptador al InputConnection real
        │   ├── EditorProfile.kt      ← FieldClass / EditorAction / EnterDecision
        │   └── Grapheme.kt           ← borrado por grapheme cluster
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

## Qué funciona hoy (refactor IME F1–F6)

- **Núcleo IME** (F1): `EditorSession` + `EditorInfoClassifier` — borrado por
  grapheme cluster, Enter según `imeOptions`, composición y clasificación de
  `EditorInfo` (texto/número/teléfono/email/URI/password). Unit tests JVM en
  `app/src/test/java/.../editor/`.
- **Teclado base** (F2): teclas data-driven, shift de 3 estados, long-press con
  alternativas, autorepeat y navegación de cursor (incluye el **cursor glide**
  dentro de la palabra en composición, estilo Gboard).
- **Shell Compose** (F3): resize sin recrear el input view, métricas adaptativas,
  haptics y animación de popup.
- **Lenguaje** (F4): composición por palabra, barra de sugerencias (sin diccionario
  real todavía — llega vía contrato `SuggestionProvider`) y undo de una unidad.
  Privacidad opt-out: las sugerencias se apagan en password y si el usuario las
  desactiva en ajustes.
- **Emoji + gestos** (F5): panel de emojis con categorías + recientes persistidos,
  **tonos de piel** (long-press, tipo Gboard) y **búsqueda por nombre**,
  swipe-delete (borrar palabra) y cursor glide en la barra espaciadora.
- **Cursor dentro del composing** (F6 parcial): el cursor navega dentro de la
  palabra en composición, tipear inserta en esa posición y borrar respeta el
  cluster anterior al cursor.
- **Carrito**: agregá varios productos y elegí lista (1-4 o "todas") por ítem.
- **Formato del mensaje** (port de los switches de `buildLineParts` v3.2):
  lectura LC/LL, concepto N/U (stepper de cantidad), texto de descuento
  ON/OFF, valor LISTA/DESCUENTO y descuento **TODOS / MANUAL por ítem**.
- **Descuento global** (0/10/15/20%) + oferta (sin descuento, con recargo tarjeta)
  + **recargo +15% tarjeta** como opción.
- **Totales** tarjeta/efectivo.
- **Ajustes** (⚙): `base_url` del backend, secret (Bearer) y modo de imágenes.
- Enviar el pedido completo:
  - modo **TEXTO** vía `commitText()`.
  - modo **PNG** vía `commitContent()` (imagen con filas + caja de TOTAL, estilo
    de la extensión). Fallback a portapapeles si la app no acepta imágenes.
- **Imágenes**: botón 📂 para elegir la carpeta (SAF, persistida), matcheo
  producto→imagen por convención (fábrica + modelo + fallback), miniatura **lazy
  con Coil** (como Google Photos) y botón 📷 para insertar la foto vía `commitContent()`.
- El usuario SIEMPRE aprieta "Enviar" a mano.

## Siguiente

- [ ] **Sync real**: apuntar `base_url` (Ajustes) a tu Worker y seedear D1
      (el `codigo_unico` se genera con la regla pendiente de backend/schema.sql).
- [ ] **Neurona/diccionario**: enchufar al contrato `SuggestionProvider` cuando el
      diccionario binario esté listo (proyecto aparte).

### Hecho en esta tanda

- **Emoji skin-tones + búsqueda**: long-press en emojis de mano/gesto abre el
  popup con los 5 tonos de piel (reusa el gesto de swipe de las teclas) y
  buscador por nombre sin distinguir acentos/mayúsculas.
- **Switches de `buildLineParts`** (port completo de LOLA v3.2): lectura
  LC/LL, concepto N/U (con stepper de cantidad), texto de descuento ON/OFF,
  valor LISTA/DESCUENTO y descuento **TODOS / MANUAL por ítem**. Se persisten.
- **Pantalla de configuración** (⚙ en el panel LOLA): `base_url`, secret
  (Bearer opcional para el sync) y modo de imágenes.

## Tests

Unit tests (JVM, sin emulador):

```bash
cd android && ./gradlew testDebugUnitTest
```

Cubren `EditorInfoClassifier`, `Grapheme` y `EditorSession` (con un
`FakeEditorConnection` en memoria).

## Nota sobre sync

El `base_url` por defecto es `http://10.0.2.2:8787` (emulador → localhost del
host). Se puede cambiar en la **pantalla de ajustes** del teclado (⚙). Si
levantás el backend con `npm run dev`, el teclado sincroniza solo; si no hay
backend, usa el seed offline. En producción, poné la URL del Worker en Ajustes.
