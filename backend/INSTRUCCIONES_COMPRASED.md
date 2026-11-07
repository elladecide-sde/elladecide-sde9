# Guía de Integración: API comprasED v2 & Targeted Sync

Documentación técnica para agentes y desarrolladores de **Ella** para interactuar con el módulo de compras (`comprasED` v2), autenticación por usuario vía Firebase ID Token + Firestore REST, resúmenes con ETag (`304 Not Modified`), paginación y sincronización bajo demanda.

---

## 1. Modelo de Autenticación

El Worker de LOLA soporta dos modos de autenticación diferenciados por rol:

### A. Autenticación por Usuario (App de Ella — Recomendado en Clientes)
```http
Authorization: Bearer <Firebase_ID_Token>
```
1. El Worker verifica la firma criptográfica `RS256` del token contra las claves públicas JWK de Google (`securetoken@system.gserviceaccount.com`) y valida `aud` e `iss` contra `FIREBASE_PROJECT_ID`.
2. Con el mismo token del usuario, el Worker lee `users/{uid}.dni` directamente en **Firestore REST API** (o el custom claim `dni` si viniera en el token) y lo cachea por 5 minutos.
3. **Aislamiento estricto por DNI:** El usuario solo puede consultar, resumir, registrar o confirmar (`ack`) compras de **su propio DNI** (no necesita siquiera mandar `?dni=` en la URL). Si intenta pasar otro `?dni=` distinto al suyo, recibe `403 Forbidden` (`code: "FORBIDDEN_DNI"`).

### B. Autenticación Administrativa (Panel / Backend-to-Backend)
```http
x-api-key: <COMPRAS_API_KEY>
```
*(O `Authorization: Bearer <LOLA_Admin_JWT>`)*
Reservada **exclusivamente para uso administrativo o servidor a servidor**. Permite consultar cualquier DNI, hacer consultas en lote (`POST /api/v1/compras/consulta`), listar todos los interesados y resetear estados.

---

## 2. Especificación de Endpoints

### 1) Resumen Liviano (`GET /api/v1/compras/resumen`)
Ideal para polling liviano o comprobación previa antes de descargar el detalle.

* **Endpoint:** `GET /api/v1/compras/resumen` (o `?dni=32685684` en modo admin)
* **Headers soportados:**
  * `Authorization: Bearer <Firebase_ID_Token>` (o `x-api-key` admin)
  * `If-None-Match: W/"<hash>"` (opcional, para recibir `304 Not Modified` sin cuerpo si nada cambió)
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
  * Valores posibles de `estado_sync`:
    * `"PENDIENTE_SYNC"`: Registrado, esperando el próximo ciclo del agente local.
    * `"CLIENTE_NO_ENCONTRADO"`: El agente ya sincronizó y verificó que el DNI no figura en `AdmGlobal.MDB`.
    * `"SIN_COMPRAS"`: El cliente existe en `AdmGlobal.MDB`, ya fue sincronizado, pero no registra compras en el rango.
    * `"CON_COMPRAS"`: Tiene comprobantes sincronizados.

---

### 2) Detalle de Compras (`GET /api/v1/comprasED`)
Recupera los comprobantes enriquecidos con paginación por cursor y filtro incremental `?since=`.

* **Endpoint:** `GET /api/v1/comprasED`
* **Query Params:**
  * `dni` (opcional con Firebase Token; requerido con `x-api-key`). Soporta también múltiples DNIs separados por coma en modo admin (`?dnis=32685684,39675372`).
  * `estado` (opcional): `"DISPONIBLE"`, `"PROCESADO"` o `"ANULADO"`.
  * `since` (opcional): Timestamp ISO UTC (`updated_at >= since`) para traer solo novedades.
  * `limit` (opcional, default `50`, máx `200`).
  * `cursor` (opcional): `id_documento` devuelto en `nextCursor` para pedir la página siguiente.
  * `rango` / `desde` / `hasta` (opcional): Filtro por fecha de emisión del comprobante.
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
        "numero_comprobante": "0000-00078984",
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

### 3) Confirmación Idempotente (`POST /api/v1/compras/ack`)
Marca comprobantes como `PROCESADO`. Es **100% idempotente**: re-enviar un `id_documento` que ya estaba procesado no falla y lo incluye en `confirmados` (además de detallarlo en `ya_procesados`).

* **Endpoint:** `POST /api/v1/compras/ack`
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

---

### 4) Consulta en Lote (`POST /api/v1/compras/consulta`) — Admin
Permite consultar varios DNIs en una sola petición administrativa.

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

---

### 5) Errores Consistentes y Rate Limiting
Todas las respuestas de error devuelven un objeto estructurado:
```json
{
  "ok": false,
  "code": "DNI_INVALIDO | DNI_NO_CONFIGURADO | UNAUTHORIZED | TOKEN_EXPIRED | FORBIDDEN_DNI | BAD_REQUEST | RATE_LIMITED",
  "message": "Descripción legible del problema",
  "error": "Descripción legible del problema"
}
```
Si se supera el límite de peticiones por ventana, el Worker responde `429 Too Many Requests` con el header `Retry-After`.
