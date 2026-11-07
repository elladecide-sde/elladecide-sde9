# Guía de Integración: API comprasED v2 & Targeted Sync

Documentación técnica integral para desarrolladores y agentes de **Ella** para interactuar con el módulo de compras (`comprasED` v2), autenticación por usuario con Firebase ID Token + Firestore REST, verificación con ETag (`304 Not Modified`), paginación por cursor, ACK idempotente, datos enriquecidos y sincronización bajo demanda desde AdmGlobal.

---

## 1. Modelo de Autenticación y Seguridad

El Worker de LOLA provee una separación estricta de privilegios según el tipo de cliente:

### A. Autenticación por Usuario (App Móvil / Web de Ella — Recomendado en Clientes)
```http
Authorization: Bearer <Firebase_ID_Token>
```
1. **Verificación Criptográfica:** El Worker obtiene y cachea las claves públicas oficiales de Google (`securetoken@system.gserviceaccount.com`), verificando la firma `RS256`, expiración (`exp`), emisión (`iat`), emisor (`iss`) y audiencia (`aud`).
2. **Resolución Automática del DNI:**
   - Si el token contiene un custom claim `dni`, se utiliza directamente.
   - De lo contrario, el Worker consulta automáticamente la API REST de Firestore:
     `GET https://firestore.googleapis.com/v1/projects/{projectId}/databases/(default)/documents/users/{uid}`
     enviando **el mismo Bearer token del usuario** (sin necesidad de almacenar credenciales de Service Account en Cloudflare y respetando las Firestore Security Rules del usuario). El vínculo `uid -> dni` se cachea en memoria por 5 minutos.
3. **Aislamiento Estricto por DNI (`FORBIDDEN_DNI`):**
   - El usuario solo tiene acceso a comprobantes de **su propio DNI**.
   - No es necesario que el cliente envíe `?dni=` en la query string (el Worker lo resuelve automáticamente).
   - Si el cliente envía explícitamente un `?dni=` o intenta confirmar (`ack`) documentos que no correspondan a su DNI, la API responde `403 Forbidden` (`code: "FORBIDDEN_DNI"`).

### B. Autenticación Administrativa (Panel Web / Backend-to-Backend / Sync-Agent)
```http
x-api-key: <COMPRAS_API_KEY>
```
*(Opcionalmente `Authorization: Bearer <LOLA_Admin_JWT>`)*
- Reservada **exclusivamente para tareas administrativas o comunicación servidor a servidor**.
- Permite consultar cualquier DNI, realizar consultas en lote (`POST /api/v1/compras/consulta`), listar todos los clientes interesados y resetear estados para pruebas.

---

## 2. Especificación de Endpoints

### 1) Resumen Liviano (`GET /api/v1/compras/resumen`)
Diseñado para comprobaciones periódicas o al abrir la app antes de realizar la descarga completa de comprobantes.

* **Endpoint:** `GET /api/v1/compras/resumen`
* **Query Params:**
  * `dni` (opcional si se usa Firebase ID Token; requerido si se usa `x-api-key`).
* **Headers soportados:**
  * `Authorization: Bearer <Firebase_ID_Token>` (o `x-api-key: <KEY>`)
  * `If-None-Match: W/"<hash>"` (opcional: permite recibir `304 Not Modified` sin cuerpo si los datos no cambiaron).
* **Cache-Control:** `private, max-age=60`
* **Respuesta (`200 OK`):**
  ```json
  {
    "ok": true,
    "dni": "32685684",
    "estado_sync": "CON_COMPRAS",
    "existe_en_mdb": true,
    "cliente_nombre": "ERIKA MARIEL IBARRA RUIZ",
    "last_synced_at": "2026-11-07 13:20:00",
    "pendientes": 2,
    "procesados": 10,
    "anulados": 0,
    "total_comprobantes": 12,
    "ultimo_id_documento": 189130,
    "updated_at": "2026-11-07 13:20:00",
    "hash": "8a4f2c901e7b3d12a091bcdef1234567"
  }
  ```
* **Respuesta (`304 Not Modified`):**
  * Si el hash provisto en `If-None-Match` coincide con el estado actual, el servidor responde con status 304, cabecera `ETag` y **cuerpo vacío**, ahorrando ancho de banda y lecturas.
* **Valores de `estado_sync`:**
  * `"PENDIENTE_SYNC"`: El DNI está registrado en la cola de sincronización, a la espera del próximo ciclo de extracción del sync-agent local.
  * `"CLIENTE_NO_ENCONTRADO"`: El sync-agent ya corrió y constató que el DNI no figura en la base de datos de AdmGlobal (`existe_en_mdb: false`).
  * `"SIN_COMPRAS"`: El cliente existe en AdmGlobal, fue sincronizado, pero no registra compras en la ventana de tiempo configurada.
  * `"CON_COMPRAS"`: El cliente posee comprobantes disponibles o procesados en D1.

---

### 2) Detalle de Compras (`GET /api/v1/comprasED`)
Recupera los comprobantes enriquecidos con soporte de paginación por cursor y filtro incremental `?since=`.

* **Endpoint:** `GET /api/v1/comprasED`
* **Query Params:**
  * `dni` (opcional con Firebase Token; requerido en modo admin). Soporta múltiples DNIs separados por coma en modo admin (`?dnis=32685684,39675372`).
  * `estado` (opcional): `"DISPONIBLE"`, `"PROCESADO"` o `"ANULADO"`. Si no se especifica, devuelve todos.
  * `since` (opcional): Timestamp ISO UTC (`updated_at >= since`) para traer únicamente novedades.
  * `limit` (opcional, default `50`, máximo `200`): Cantidad máxima de comprobantes por página.
  * `cursor` (opcional): `id_documento` obtenido en `nextCursor` para solicitar la página subsiguiente.
  * `rango` / `desde` / `hasta` (opcional): Filtro por fecha de emisión del comprobante (`"48h"`, `"7d"`, `"1m"`, `"personalizada"`).
* **Cache-Control:** `no-store`
* **Respuesta (`200 OK`):**
  ```json
  {
    "ok": true,
    "dni": "32685684",
    "estado_sync": "CON_COMPRAS",
    "existe_en_mdb": true,
    "cliente_nombre": "ERIKA MARIEL IBARRA RUIZ",
    "last_synced_at": "2026-11-07 13:20:00",
    "total_compras": 1,
    "pendientes": 1,
    "estado_filtrado": "DISPONIBLE",
    "rango_filtrado": "SIN_FILTRO",
    "hasMore": false,
    "nextCursor": null,
    "serverTime": "2026-11-07 16:30:00",
    "compras": [
      {
        "id_documento": 189130,
        "dni": "32685684",
        "fecha": "2026-09-03T20:22:02",
        "tipo_doc": "Ticket B",
        "numero_comprobante": "0001-00078984",
        "cliente": "ERIKA MARIEL IBARRA RUIZ",
        "localidad": "CAPITAL",
        "sucursal": "Sucursal 1 - Caja 1",
        "moneda": "ARS",
        "descuento": 17154.3,
        "porc_descuento": 15,
        "forma_pago": "EFECTIVO",
        "anulado": false,
        "nota_credito_de": null,
        "total": 97207.8,
        "estado": "DISPONIBLE",
        "revision": 1,
        "procesado_at": null,
        "procesado_por": null,
        "updated_at": "2026-11-07 13:20:00",
        "items": [
          {
            "codigo_unico": "KAUP160",
            "fabrica": "KAURY",
            "codigo": "P160",
            "descripcion": "KAURY P160 PACKS CULOTTE LESS ALGODON",
            "cantidad": 1,
            "precio_unitario": 10891.49,
            "subtotal": 10891.49,
            "iva": 21,
            "descuento": 1633.72
          }
        ]
      }
    ]
  }
  ```

---

### 3) Confirmación Idempotente / ACK (`POST /api/v1/compras/ack`)
Marca los comprobantes especificados como `PROCESADO`.
Es **100% idempotente**: re-enviar comprobantes que ya fueron procesados no arroja error y los confirma satisfactoriamente.

* **Endpoint:** `POST /api/v1/compras/ack`
* **Headers:** `Authorization: Bearer <Firebase_ID_Token>` o `x-api-key: <KEY>`
* **Body:**
  ```json
  {
    "ids_documentos": [189130, 189131, 999999]
  }
  ```
* **Respuesta (`200 OK`):**
  ```json
  {
    "ok": true,
    "confirmados": [189130, 189131],
    "cantidad_confirmados": 2,
    "ya_procesados": [189131],
    "no_encontrados": [999999]
  }
  ```
* **Auditoría:** La base registra automáticamente en `procesado_por` el actor (`"firebase:<uid>"` o `"api-key"`) y en `procesado_at` la fecha y hora UTC de confirmación.

---

### 4) Registrar Clientes para Sincronizar (`POST /api/v1/compras/interesados`)
Informa al backend qué clientes deben ser extraídos desde la base de datos de AdmGlobal en el próximo ciclo del agente local.

* **Endpoint:** `POST /api/v1/compras/interesados`
* **Headers:** `Authorization: Bearer <Firebase_ID_Token>` o `x-api-key: <KEY>`
* **Uso desde App de Ella (Firebase ID Token):**
  Un usuario autenticado puede auto-registrar su propio DNI enviando:
  ```json
  {
    "rango": "48h"
  }
  ```
  *(El Worker asume automáticamente el DNI resuelto del token).*
* **Uso Administrativo (`x-api-key`):**
  Permite registrar uno o múltiples DNIs:
  ```json
  {
    "dnis": ["32685684", "39675372"],
    "rango": "48h"
  }
  ```
  * Opciones de `rango`:
    * `"48h"` (predeterminado): Comprobantes de las últimas 48 horas.
    * `"7d"`: Últimos 7 días.
    * `"1m"`: Últimos 30 días.
    * `"personalizada"`: Requiere `"desde": "YYYY-MM-DD 00:00:00"` y `"hasta": "YYYY-MM-DD 23:59:59"`.
* **Respuesta (`200 OK`):**
  ```json
  {
    "ok": true,
    "procesados": 1,
    "dnis": ["32685684"],
    "rango_configurado": "48h"
  }
  ```

---

### 5) Listar Clientes en Cola de Sincronización (`GET /api/v1/compras/interesados`) — Admin
Lista todos los clientes registrados para sincronización con su estado operativo.

* **Headers:** `x-api-key: <COMPRAS_API_KEY>` (o Bearer JWT Admin)
* **Respuesta (`200 OK`):**
  ```json
  {
    "ok": true,
    "total": 1,
    "interesados": [
      {
        "dni": "32685684",
        "rango": "48h",
        "requested_at": "2026-11-07 12:00:00",
        "last_synced_at": "2026-11-07 13:20:00",
        "existe_en_mdb": true,
        "nombre_cliente": "ERIKA MARIEL IBARRA RUIZ",
        "estado_sync": "CON_COMPRAS",
        "pendientes": 2,
        "total_compras": 12
      }
    ]
  }
  ```

---

### 6) Eliminar Cliente de la Cola (`DELETE /api/v1/compras/interesados/:dni`) — Admin
Elimina un DNI de la lista activa de sincronización.

* **Headers:** `x-api-key: <COMPRAS_API_KEY>`
* **Respuesta (`200 OK`):**
  ```json
  {
    "ok": true,
    "dni": "32685684"
  }
  ```

---

### 7) Consulta en Lote (`POST /api/v1/compras/consulta`) — Admin
Permite consultar comprobantes de múltiples DNIs en una única petición sin realizar N llamadas individuales.

* **Headers:** `x-api-key: <COMPRAS_API_KEY>`
* **Body:**
  ```json
  {
    "dnis": ["32685684", "39675372"],
    "estado": "DISPONIBLE",
    "since": "2026-11-01T00:00:00Z",
    "limit": 100
  }
  ```
* **Respuesta (`200 OK`):**
  ```json
  {
    "ok": true,
    "total_compras": 3,
    "serverTime": "2026-11-07 16:35:00",
    "compras": [ ... ],
    "por_dni": {
      "32685684": [ ... ],
      "39675372": [ ... ]
    }
  }
  ```

---

### 8) Resetear Estado de Compras a DISPONIBLE (`POST /api/v1/compras/reset`) — Admin
Pasa todas las compras no anuladas de un DNI al estado `DISPONIBLE`, limpiando `procesado_at` y `procesado_por`. Es sumamente útil durante el desarrollo o pruebas de homologación.

* **Headers:** `x-api-key: <COMPRAS_API_KEY>`
* **Body:** `{"dni": "32685684"}`
* **Respuesta (`200 OK`):**
  ```json
  {
    "ok": true,
    "dni": "32685684",
    "actualizadas": 5
  }
  ```

---

## 3. Notificación Push por Webhook (Opcional)

Si en el Worker se configuran las variables:
- `ELLA_WEBHOOK_URL`: URL del endpoint o Cloud Function receptora en Ella.
- `ELLA_WEBHOOK_SECRET` (opcional): Clave secreta para validación de firma HMAC.

Cada vez que el sync-agent local sube comprobantes nuevos en `/batch`, el Worker envía automáticamente un `POST` con la carga:
```json
{
  "event": "compras.updated",
  "dnis": ["32685684"],
  "guardadas": 2,
  "timestamp": "2026-11-07T16:30:00.000Z"
}
```
Si se configuró `ELLA_WEBHOOK_SECRET`, la petición incluye el encabezado de seguridad:
```http
x-lola-signature: sha256=<HMAC_SHA256_HEX>
```
Esto elimina por completo la necesidad de polling continuo desde la app o backend de Ella.

---

## 4. Errores Consistentes y Rate Limiting

Todas las respuestas de error utilizan un formato homogéneo:
```json
{
  "ok": false,
  "code": "DNI_INVALIDO",
  "message": "Descripción amigable del error",
  "error": "Descripción amigable del error"
}
```

### Códigos de Error Posibles:
| Código | HTTP Status | Causa |
| :--- | :---: | :--- |
| `DNI_INVALIDO` | `400` | El DNI provisto contiene caracteres inválidos o está vacío. |
| `DNI_NO_CONFIGURADO` | `400` | No se pudo determinar el DNI asociado al usuario o petición. |
| `BAD_REQUEST` | `400` | Carga JSON malformada o parámetros requeridos ausentes. |
| `UNAUTHORIZED` | `401` | Token ausente, firma inválida o `x-api-key` errónea. |
| `TOKEN_EXPIRED` | `401` | El ID Token de Firebase ha expirado (renovar en cliente). |
| `FORBIDDEN_DNI` | `403` | El usuario intentó consultar o confirmar compras de un DNI ajeno. |
| `RATE_LIMITED` | `429` | Se superó el límite de llamadas por ventana de tiempo. Incluye header `Retry-After: <segundos>`. |

---

## 5. Flujo de Integración Recomendado para la App de Ella

Para maximizar el rendimiento y minimizar el consumo de datos y batería:

1. **Auto-registro del DNI (una sola vez o al iniciar sesión):**
   ```http
   POST /api/v1/compras/interesados
   Authorization: Bearer <Firebase_ID_Token>
   Content-Type: application/json

   { "rango": "48h" }
   ```
2. **Chequeo liviano con ETag:**
   ```http
   GET /api/v1/compras/resumen
   Authorization: Bearer <Firebase_ID_Token>
   If-None-Match: W/"<ultimo_hash_guardado>"
   ```
   - Si responde `304 Not Modified`: **No hay cambios**. No se gasta red ni se realizan lecturas en D1.
   - Si responde `200 OK`: Se actualiza el hash local. Si `pendientes > 0`, se procede a la descarga.
3. **Descarga de comprobantes pendientes:**
   ```http
   GET /api/v1/comprasED?estado=DISPONIBLE&limit=50
   Authorization: Bearer <Firebase_ID_Token>
   ```
4. **Procesamiento y Confirmación (ACK):**
   Una vez persistidos los comprobantes en el dispositivo o en la base de Ella:
   ```http
   POST /api/v1/compras/ack
   Authorization: Bearer <Firebase_ID_Token>
   Content-Type: application/json

   { "ids_documentos": [189130] }
   ```
   La llamada es **idempotente**, por lo que ante reintentos de red no fallará y confirmará la recepción exitosa.
