# Informe de Auditoría Técnica — Módulo comprasED

Fecha: 2026-10-30  
Alcance: Backend (`backend/src/compras/`), Base de Datos D1 (`schema.sql`, `migrate_compras.sql`), Extractor Python (`sync-agent/compras_extractor.py`).

---

## 1. Hallazgos Críticos de Seguridad

### 🔴 SEC-01: Endpoints de Escritura Públicos sin Autenticación
* **Ubicación:** `backend/src/compras/routes.ts`
* **Severidad:** **ALTA**
* **Vulnerabilidad:** 
  - `POST /api/v1/compras/batch`: Cualquier cliente o script que conozca la URL puede enviar JSON malicioso e inyectar o sobrescribir compras ficticias en la base de datos D1.
  - `POST /api/v1/compras/ack`: Cualquier usuario que adivine un `id_documento` puede marcarlo como `PROCESADO`, haciendo que el cliente legítimo nunca reciba sus compras.
  - `GET /api/v1/compras/interesados`: Expone públicamente la lista de DNIs que están interactuando con la app de Ella.
* **Solución Arquitectónica:**
  - El sync-agent ya cuenta con autenticación por token JWT (`D1Uploader.login()`).
  - Proteger `POST /api/v1/compras/batch` y `GET /api/v1/compras/interesados` exigiendo Bearer Token (rol Admin / SyncAgent).
  - Proteger `POST /api/v1/compras/ack` y `POST /api/v1/compras/interesados` mediante un Shared API Secret con la app de Ella (header `x-api-key: ...`) o JWT de servicio.

---

## 2. Hallazgos de Concurrencia y Performance

### 🟡 PERF-01: Conexión ODBC repetida en loop (N+1)
* **Ubicación:** `sync-agent/compras_extractor.py` (`ejecutar_ciclo`)
* **Severidad:** **MEDIA**
* **Detalle Técnico:**
  - Si hay 50 DNIs registrados, el agente ejecutaba `extraer_compras_cliente` abriendo y cerrando conexiones ODBC a `AdmGlobal_snapshot.mdb` 50 veces por ciclo.
* **Solución Arquitectónica:**
  - Abrir la conexión `pyodbc.connect` una única vez por ciclo y reutilizar el cursor para todos los clientes interesados.

### 🟡 PERF-02: Límite de Payload en Batch de D1
* **Ubicación:** `backend/src/compras/service.ts`
* **Severidad:** **BAJA / PREVENTIVA**
* **Detalle Técnico:**
  - D1 tiene un límite estricto de 100 statements por batch. Ya implementamos chunking por lotes de 100 en `service.ts`, pero es importante asegurar que el cliente Python envíe bloques que no excedan el tamaño de memoria del Worker (máx 128MB).

---

## 3. Bugs Potenciales y Edge Cases

### 🟢 BUG-01: Fechas con offsets horarios / formatos no ISO
* **Ubicación:** `sync-agent/compras_extractor.py` (`parsear_fecha_rango`)
* **Detalle:** Si Ella envía `2026-10-30T10:00:00-03:00` o fechas con formatos con `/`, `datetime.fromisoformat` de Python 3.8 o TypeScript Date pueden diferir.
* **Solución:** Sanitizar cadenas de fecha y normalizar a UTC/ISO antes del filtrado SQL.
