# 🤖 LOLA — Agente de Sincronización Local (`sync-agent`)

Agente de escritorio en Python para Windows encargado de sincronizar de forma autónoma y silenciosa el catálogo del sistema de gestión local (**AdmGlobal / Microsoft Access MDB**) con la nube (**Cloudflare D1 via Worker Backend**).

---

## 🎯 ¿Qué hace el Agente?

1. **Monitoreo Silencioso:** Corre en segundo plano (System Tray) sin interrumpir el trabajo de caja ni del ERP.
2. **Snapshot Atómico en Caliente:** Para no trabar las ventas ni provocar errores de *"Base de datos bloqueada"* en Microsoft Access, copia `AdmGlobal.mdb` en caliente a un área aislada (`consumo/AdmGlobal_snapshot.mdb`).
3. **Extracción y Normalización:** Lee artículos activos, calcula costos y precios de listas (L1..L4), asigna categorías/fábricas y genera el `codigo_unico` determinístico.
4. **Delta Engine (Diferenciales Inteligentes):** Compara los artículos extraídos contra un estado local (`state/catalog_state.json`). Si nada cambió, hace `SKIP` (0 peticiones pesadas a la nube). Si cambiaron artículos, calcula altas, bajas y modificaciones.
5. **Subida Segura a Cloudflare D1:** Envía las diferencias a la API del backend (`/api/v1/admin/products`) autenticado por JWT.
6. **Bóveda Histórica y Rotación Eficiente:** Si hubo cambios reales y los backups están activados, **mueve** el archivo de consumo a `backups/` (rename instantáneo en NTFS sin duplicar ~860 MB en disco) y poda las versiones más viejas según el límite configurado (`maxBackups`).

---

## 🗺️ Mapa de Arquitectura y Dependencias

```mermaid
flowchart TD
    subgraph Local_PC["🖥️ PC Local (Windows - Mostrador / Servidor)"]
        ERP["💼 AdmGlobal.MDB (Microsoft Access ERP)"]
        Agent["⚙️ sync-agent (Python / SyncAgenteLola.exe)"]
        Consumo["📁 consumo/ (Snapshot efímero de trabajo)"]
        Backups["🗄️ backups/ (Histórico rotativo: 0 a 15)"]
        State["📝 state/catalog_state.json (Memoria de cambios)"]

        ERP -.->|Copia en caliente| Consumo
        Consumo -->|Extrae datos| Agent
        Agent -->|Calcula diferencias| State
        Agent -->|Si hubo cambios: MOVE| Backups
    end

    subgraph Cloudflare["☁️ Ecosistema Cloudflare"]
        Backend["⚡ LOLA Backend (Worker API)"]
        D1[("🗄️ Cloudflare D1 (SQLite)")]
        Pages["🌐 LOLA Panel (Cloudflare Pages Web)"]
        Android["📱 App LOLA (Teclado Android)"]

        Backend <--> D1
        Pages <-->|Admin / Catálogo| Backend
        Android <-->|Sync offline-first| Backend
    end

    Agent -->|POST /api/v1/admin/products (JWT)| Backend
```

---

## 🧩 Componentes del Agente

| Archivo | Responsabilidad Arquitectónica |
| :--- | :--- |
| **`main.py`** | Núcleo del agente, interfaz gráfica (Tkinter), menú en bandeja del sistema (System Tray vía `pystray`), planificador de tareas (`timer`) y persistencia en `settings.json`. |
| **`snapshot_manager.py`** | Creación atómica de copias en caliente (`AdmGlobal_snapshot.mdb`), validación binaria de cabeceras Jet/ACE, rotación por `shutil.move` y limpieza total cuando `maxBackups = 0`. |
| **`mdb_extractor.py`** | Extracción directa de tablas de Access (`Articulos`, `Categorias`, `Fabricantes`) usando ODBC 64-bit (`pyodbc`) con fallback a motor nativo Windows Jet OLEDB/ADO. |
| **`delta_engine.py`** | Cálculo de diferencias hash artículo por artículo. Evita resincronizaciones completas y saturación del ancho de banda. |
| **`d1_uploader.py`** | Cliente HTTP hacia el Worker: login JWT, subida en lotes (chunks) de altas/modificaciones/bajas y verificación de respuesta. |
| **`codigo_unico.py`** | Generador determinístico de clave primaria (port 1:1 idéntico al backend y la web). |
| **`excel_extractor.py`** | Soporte de contingencia para procesar listas en Excel (`.xlsx` Lista 4) en modo manual. |
| **`startup_manager.py`** | Registro en Windows para iniciar silenciosamente con el sistema (`HKCU\Software\Microsoft\Windows\CurrentVersion\Run`). |
| **`test_backups.py`** | Suite de pruebas automatizadas para validar persistencia de configuración, rotación sin fugas de disco y modo OFF. |

---

## ⚙️ Configuración (`config/settings.json`)

```json
{
  "syncMode": "automatica",
  "mdbPath": "C:\\Ruta\\A\\AdmGlobal.MDB",
  "mdbPassword": "clave",
  "consumoDir": "consumo",
  "apiUrl": "https://lola-backend.elladecide.workers.dev",
  "apiUser": "admin",
  "apiPassword": "tu_password",
  "syncOnStartup": true,
  "syncIntervalMinutes": 60,
  "maxBackups": 3
}
```

### Opciones de Retención de Backups (`maxBackups`)
* **`0 (OFF)`**: Máximo ahorro de disco. No se acumula ningún histórico en `backups/` y el snapshot temporal de `consumo/` se autodestruye tras sincronizar (0 MB de base de datos en reposo).
* **`1` a `15`**: Conserva hasta $N$ versiones históricas de cambios reales.
* **`3 (Predeterminado)`**: Conserva los últimos 3 cambios importantes ($\sim 2.5\text{ GB}$ en disco), suficiente para restauraciones ante fallos humanos sin saturar el almacenamiento.

---

## 📦 Requisitos y Dependencias

* **Sistema Operativo:** Windows 10 / 11 / Server (64-bit).
* **Python:** 3.8 o superior (si se ejecuta desde código fuente).
* **Driver Access:** Microsoft Access Database Engine 2016 o 2010 Redistributable (64-bit) o Jet 4.0 nativo de Windows.
* **Librerías Python (`requirements.txt`):**
  ```text
  pyodbc>=5.0.0
  pyinstaller>=6.0.0
  pystray>=0.19.5
  pillow>=10.4.0
  ```

---

## 🚀 Comandos Útiles

```bash
# Correr tests unitarios del agente
cd sync-agent
python -m unittest test_backups.py

# Iniciar agente en modo desarrollo con ventana visible
python main.py

# Iniciar agente en segundo plano (arranque de Windows)
python main.py --background
```
