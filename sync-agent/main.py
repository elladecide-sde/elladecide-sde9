# ============================================================================
# main.py — Agente Sincronizador AdmGlobal (.mdb / Excel Lista 4) → LOLA D1
#
# Diseñado para correr en la VM de AdmGlobal:
#   - Cuando corre de forma automática (`--once`), NO abre ventana, ejecuta el
#     snapshot + delta en ~2 segundos y se cierra (0 MB de RAM en reposo).
#   - Cuando el usuario abre el .exe con doble clic, muestra el Panel de
#     Configuración completo:
#       · Modo: Automática (MDB + Stock) / Manual (Excel Lista 4)
#       · En cada Inicio: ON / OFF (ON predeterminado)
#       · Actualización periódica: 5, 10, 15, 30, 45, 60, 120 min (60 predeterminado)
#       · Selección de archivo MDB (o carpeta + nombre) y clave del MDB ("toifel")
#       · Configuración del Worker D1: URL, Usuario Admin y Clave Admin (+ botón Probar)
# ============================================================================

from __future__ import annotations

import json
import logging
import os
import re
import socket
import sys
import threading
import tkinter as tk
import uuid
from datetime import datetime, timedelta
from tkinter import filedialog, messagebox, ttk
from typing import Any, Dict

if getattr(sys, "frozen", False):
    BASE_DIR = os.path.dirname(sys.executable)
else:
    BASE_DIR = os.path.dirname(os.path.abspath(__file__))

sys.path.insert(0, BASE_DIR)

from d1_uploader import D1Uploader
from delta_engine import DeltaEngine
from excel_extractor import ExcelExtractor
from mdb_extractor import MDBExtractor
from snapshot_manager import SnapshotManager
from startup_manager import (
    configure_periodic_task,
    create_uninstall_batch,
    set_run_on_startup,
    uninstall_all,
)

INTERVAL_OPTIONS = [0, 5, 10, 15, 30, 45, 60, 120, 240]
DEFAULT_INTERVAL = 60
SYNC_MODES = ("automatica", "manual")
WINDOW_TITLE = "LOLA · Agente Sincronizador AdmGlobal → Cloudflare D1"


def interval_to_label(mins: int) -> str:
    """Convierte minutos a texto amigable para el selector."""
    if mins <= 0:
        return "OFF"
    if mins == 240:
        return "240 min (4h)"
    return f"{mins} min"


def label_to_interval(label: str) -> int:
    """Parsea el texto del selector a minutos enteros (OFF -> 0)."""
    lbl = (label or "").strip().upper()
    if lbl.startswith("OFF"):
        return 0
    m = re.match(r"^(\d+)", lbl)
    if m:
        val = int(m.group(1))
        return val if val in INTERVAL_OPTIONS else DEFAULT_INTERVAL
    return DEFAULT_INTERVAL


class SyncAlreadyRunningError(RuntimeError):
    """Lanzada cuando otra sincronización ya tiene tomado el lock del agente."""
    pass


class InstanceCoordinator:
    """
    Coordina múltiples sesiones RDP o equipos que acceden a la misma carpeta / VM.
    Solo UNA instancia puede ser 'Master' (activa, con cronómetro y subida).
    Las demás instancias quedan en 'Modo Observador / Solo Lectura' con banner y
    botón '⚡ Ejecutar aquí / Tomar control' como hace el navegador.
    """

    def __init__(self, state_dir: str, heartbeat_timeout: int = 25):
        self.state_dir = state_dir
        self.coordinator_file = os.path.join(state_dir, "active_instance.json")
        self.heartbeat_timeout = heartbeat_timeout
        self.instance_id = str(uuid.uuid4())[:8]
        self.hostname = socket.gethostname()
        self.username = os.environ.get("USERNAME") or os.environ.get("USER") or "usuario"
        self.pid = os.getpid()
        self.is_master = False

    def read_active_info(self) -> Dict[str, Any]:
        if not os.path.exists(self.coordinator_file):
            return {}
        try:
            with open(self.coordinator_file, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            return {}

    def is_peer_alive(self, data: Optional[Dict[str, Any]] = None) -> bool:
        if data is None:
            data = self.read_active_info()
        if not data or not data.get("instance_id"):
            return False
        if data.get("instance_id") == self.instance_id:
            return True
        try:
            last_hb = float(data.get("last_heartbeat", 0))
            now_ts = datetime.now().timestamp()
            return (now_ts - last_hb) < self.heartbeat_timeout
        except Exception:
            return False

    def claim_master(self) -> None:
        os.makedirs(self.state_dir, exist_ok=True)
        payload = {
            "instance_id": self.instance_id,
            "hostname": self.hostname,
            "username": self.username,
            "pid": self.pid,
            "started_at": datetime.now().isoformat(timespec="seconds"),
            "last_heartbeat": datetime.now().timestamp(),
        }
        tmp = self.coordinator_file + f".tmp.{self.pid}"
        try:
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(payload, f, indent=2, ensure_ascii=False)
            os.replace(tmp, self.coordinator_file)
            self.is_master = True
            logging.info(
                f"Rol MASTER adquirido por esta instancia ({self.hostname}\\{self.username}, PID {self.pid})."
            )
        except Exception as e:
            logging.warning(f"No se pudo guardar active_instance.json: {e}")

    def heartbeat(self) -> bool:
        """Actualiza el latido. Si otra instancia tomó el control, devuelve False."""
        data = self.read_active_info()
        if not data or data.get("instance_id") == self.instance_id:
            self.claim_master()
            return True
        else:
            self.is_master = False
            return False

    def release(self) -> None:
        """Libera el rol si éramos el master."""
        data = self.read_active_info()
        if data.get("instance_id") == self.instance_id:
            try:
                os.remove(self.coordinator_file)
            except Exception:
                pass
        self.is_master = False


class AgentLock:
    """Evita que dos corridas silenciosas se pisen al mismo tiempo (con detección de lock huérfano)."""

    def __init__(self, state_dir: str, stale_seconds: int = 600):
        self.lock_path = os.path.join(state_dir, "agent.lock")
        self.stale_seconds = stale_seconds

    def acquire(self) -> bool:
        if os.path.exists(self.lock_path):
            try:
                age = datetime.now().timestamp() - os.path.getmtime(self.lock_path)
                if age < self.stale_seconds:
                    with open(self.lock_path, "r", encoding="utf-8") as f:
                        pid_str = f.read().strip()
                    if pid_str.isdigit() and int(pid_str) != os.getpid():
                        logging.warning(f"Lock activo detectado (PID {pid_str}, hace {int(age)}s). Abortando corrida duplicada.")
                        return False
            except Exception:
                pass
        try:
            with open(self.lock_path, "w", encoding="utf-8") as f:
                f.write(str(os.getpid()))
            return True
        except Exception:
            return False

    def release(self) -> None:
        try:
            if os.path.exists(self.lock_path):
                os.remove(self.lock_path)
        except Exception:
            pass


class AgentConfig:
    def __init__(self, base_dir: str):
        self.base_dir = base_dir
        self.config_dir = os.path.join(base_dir, "config")
        self.latest_dir = os.path.join(base_dir, "latest")
        self.backups_dir = os.path.join(base_dir, "backups")
        self.state_dir = os.path.join(base_dir, "state")
        self.exports_dir = os.path.join(base_dir, "exports")
        self.logs_dir = os.path.join(base_dir, "logs")
        self.settings_path = os.path.join(self.config_dir, "settings.json")

        self.consumo_dir = os.path.join(base_dir, "consumo")
        for d in (
            self.config_dir,
            self.consumo_dir,
            self.latest_dir,
            self.backups_dir,
            self.state_dir,
            self.exports_dir,
            self.logs_dir,
        ):
            os.makedirs(d, exist_ok=True)

        self.sync_mode = "automatica"  # 'automatica' (MDB) | 'manual' (Excel Lista 4)
        default_mdb = r"C:\Users\elladecide2\Documents\GitHub\elladecide-sde9\backend\AdmGlobal.MDB"
        self.mdb_folder = os.path.dirname(os.path.abspath(default_mdb))
        self.mdb_filename = os.path.basename(default_mdb)
        self.mdb_password = "toifel"
        self.excel_path = ""
        self.excel_col_nombre = 1
        self.excel_col_precio = 2
        self.api_url = "https://lola-backend.elladecide.workers.dev"
        self.api_user = "admin"
        self.api_password = ""
        self.sync_on_startup = True  # ON predeterminado
        self.sync_interval_minutes = DEFAULT_INTERVAL  # 60 min predeterminado
        self.load()

    @property
    def mdb_full_path(self) -> str:
        """Ruta resuelta del archivo MDB original (carpeta de búsqueda + nombre de archivo fijado)."""
        return os.path.join(self.mdb_folder, self.mdb_filename)

    def load(self) -> None:
        if not os.path.exists(self.settings_path):
            self.save()
            return
        try:
            with open(self.settings_path, "r", encoding="utf-8") as f:
                data = json.load(f)
            mode = data.get("syncMode", "automatica")
            self.sync_mode = mode if mode in SYNC_MODES else "automatica"

            if "mdbFolder" in data and "mdbFilename" in data:
                self.mdb_folder = data["mdbFolder"]
                self.mdb_filename = data["mdbFilename"]
            elif "mdbPath" in data:
                p = data["mdbPath"]
                if os.path.isdir(p):
                    self.mdb_folder = p
                    self.mdb_filename = "AdmGlobal.MDB"
                else:
                    self.mdb_folder = os.path.dirname(os.path.abspath(p))
                    self.mdb_filename = os.path.basename(p) or "AdmGlobal.MDB"

            self.consumo_dir = data.get("consumoDir", os.path.join(self.base_dir, "consumo"))
            os.makedirs(self.consumo_dir, exist_ok=True)

            self.mdb_password = data.get("mdbPassword", "toifel")
            self.excel_path = data.get("excelPath", "")
            self.excel_col_nombre = int(data.get("excelColNombre", 1))
            self.excel_col_precio = int(data.get("excelColPrecio", 2))
            self.api_url = data.get("apiUrl", self.api_url)
            self.api_user = data.get("apiUser", self.api_user)
            self.api_password = data.get("apiPassword", self.api_password)
            self.sync_on_startup = bool(data.get("syncOnStartup", True))
            interval = int(data.get("syncIntervalMinutes", DEFAULT_INTERVAL))
            self.sync_interval_minutes = interval if interval in INTERVAL_OPTIONS else DEFAULT_INTERVAL
        except Exception as e:
            logging.warning(f"Error leyendo settings.json, usando valores por defecto: {e}")

    def save(self) -> None:
        payload = {
            "syncMode": self.sync_mode,
            "mdbFolder": self.mdb_folder,
            "mdbFilename": self.mdb_filename,
            "mdbPath": self.mdb_full_path,
            "consumoDir": self.consumo_dir,
            "mdbPassword": self.mdb_password,
            "excelPath": self.excel_path,
            "excelColNombre": self.excel_col_nombre,
            "excelColPrecio": self.excel_col_precio,
            "apiUrl": self.api_url,
            "apiUser": self.api_user,
            "apiPassword": self.api_password,
            "syncOnStartup": self.sync_on_startup,
            "syncIntervalMinutes": self.sync_interval_minutes,
        }
        with open(self.settings_path, "w", encoding="utf-8") as f:
            json.dump(payload, f, indent=2, ensure_ascii=False)


def setup_logging(logs_dir: str, silent: bool = False) -> str:
    os.makedirs(logs_dir, exist_ok=True)
    log_path = os.path.join(logs_dir, "sync.log")
    handlers: list[logging.Handler] = [logging.FileHandler(log_path, encoding="utf-8")]
    if not silent and sys.stdout is not None:
        handlers.append(logging.StreamHandler(sys.stdout))
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(message)s",
        handlers=handlers,
    )
    return log_path


def _process_and_upload(
    config: AgentConfig,
    extracted: list[Dict[str, Any]],
    force_full: bool,
    source_label: str,
) -> Dict[str, Any]:
    delta_engine = DeltaEngine(config.state_dir)
    diff = delta_engine.compute_diff(extracted, force_full=force_full)

    latest_json = os.path.join(config.exports_dir, "catalogo_actual.json")
    with open(latest_json, "w", encoding="utf-8") as f:
        json.dump([item["dto"] for item in extracted], f, indent=2, ensure_ascii=False)

    if not diff.has_changes:
        prev = delta_engine.load_previous_state()
        logging.info(f"[{source_label}] Sin cambios respecto a la versión previa. Subida omitida.")
        return {
            "source": source_label,
            "mode": "skip",
            "upserts": 0,
            "deleted": 0,
            "total": diff.total_active,
            "revision": prev.get("revision", 0),
        }

    if not config.api_url or not config.api_password:
        logging.warning(
            f"[{source_label}] Credenciales API no configuradas: exportado en local ({len(diff.upserts)} cambios)."
        )
        return {
            "source": source_label,
            "mode": f"{diff.mode} (local)",
            "upserts": len(diff.upserts),
            "deleted": len(diff.deleted),
            "total": diff.total_active,
            "revision": delta_engine.load_previous_state().get("revision", 0),
        }

    uploader = D1Uploader(config.api_url, config.api_user, config.api_password)
    if diff.mode == "replace":
        resp = uploader.upload_full_replace(diff.upserts)
    else:
        resp = uploader.upload_delta(diff.upserts, diff.deleted)

    revision = int(resp.get("revision", 0))
    cat_hash = str(resp.get("hash", ""))
    delta_engine.save_state(extracted, revision=revision, catalog_hash=cat_hash)

    return {
        "source": source_label,
        "mode": diff.mode,
        "upserts": len(diff.upserts),
        "deleted": len(diff.deleted),
        "total": diff.total_active,
        "revision": revision,
    }


def execute_sync_mdb(config: AgentConfig, force_full: bool = False) -> Dict[str, Any]:
    """Modo Automático: Copia de AdmGlobal.MDB a carpeta de consumo → Snapshot aislada → Delta Engine → D1."""
    file_lock = AgentLock(config.state_dir)
    if not file_lock.acquire():
        raise SyncAlreadyRunningError("Ya hay una sincronización en curso. Se omite para no pisar procesos.")
    try:
        source_mdb = config.mdb_full_path
        if not os.path.exists(source_mdb):
            raise FileNotFoundError(
                f"No se encontró el archivo '{config.mdb_filename}' en la carpeta de búsqueda:\n{config.mdb_folder}"
            )

        os.makedirs(config.consumo_dir, exist_ok=True)
        # El agente copia a consumo_dir y trabaja 100% aislado sobre esa copia
        snap_mgr = SnapshotManager(
            config.consumo_dir,
            config.mdb_password,
            backups_dir=config.backups_dir,
        )
        snapshot_path = snap_mgr.create_snapshot(source_mdb)

        extractor = MDBExtractor(mdb_password=config.mdb_password)
        extracted = extractor.extract_active_products(snapshot_path)
        res = _process_and_upload(config, extracted, force_full=force_full, source_label="MDB")
        if res.get("mode") != "skip":
            snap_mgr.rotate_backup()
        return res
    finally:
        file_lock.release()


def execute_sync_excel(config: AgentConfig, excel_path: str, force_full: bool = False) -> Dict[str, Any]:
    """Modo Manual: Excel de Lista 4 → Cálculo de 4 listas y costos → Delta Engine → D1."""
    file_lock = AgentLock(config.state_dir)
    if not file_lock.acquire():
        raise SyncAlreadyRunningError("Ya hay una sincronización en curso. Se omite para no pisar procesos.")
    try:
        extractor = ExcelExtractor(
            col_nombre=config.excel_col_nombre,
            col_precio=config.excel_col_precio,
        )
        extracted = extractor.extract_products(excel_path)
        return _process_and_upload(config, extracted, force_full=force_full, source_label="EXCEL")
    finally:
        file_lock.release()


class SyncAppWindow(tk.Tk):
    def __init__(self, config: AgentConfig, start_hidden: bool = False):
        super().__init__()
        self.config_obj = config
        self.title(WINDOW_TITLE)
        self.geometry("640x600")
        self.minsize(580, 420)
        self.resizable(True, True)
        self.configure(bg="#0f172a")

        self._sync_lock = threading.Lock()
        self._timer_id = None
        self._countdown_job = None
        self._last_sync_time: Optional[datetime] = None
        # Ancla del ciclo periódico: momento del último intento (exitoso o no).
        # Evita bucles de reintento cada 1.5s cuando un sync falla.
        self._cycle_anchor: Optional[datetime] = None
        self._next_sync_time: Optional[datetime] = None
        self._show_mdb_pwd = False
        self._show_api_pwd = False

        self.coordinator = InstanceCoordinator(self.config_obj.state_dir)
        peer_data = self.coordinator.read_active_info()
        if self.coordinator.is_peer_alive(peer_data):
            if peer_data.get("instance_id") == self.coordinator.instance_id:
                self.coordinator.claim_master()
            else:
                if start_hidden:
                    logging.info(
                        f"Instancia activa en {peer_data.get('hostname')}\\{peer_data.get('username')}. "
                        "Saliendo de la ejecución en segundo plano para no competir."
                    )
                    sys.exit(0)
                self.coordinator.is_master = False
        else:
            self.coordinator.claim_master()

        self._heartbeat_job = None

        self.bind("<Unmap>", self._on_window_unmap)
        self.bind("<Map>", self._on_window_map)
        self.protocol("WM_DELETE_WINDOW", self._on_window_close)

        self._build_ui()
        self._on_path_changed()
        create_uninstall_batch(self.config_obj.base_dir)
        self._apply_os_schedules()
        self._refresh_mode_ui()
        self._refresh_state_summary()
        self._refresh_role_ui()
        self._schedule_next_timer()
        self._heartbeat_tick()

        if start_hidden:
            if not self.coordinator.is_master:
                sys.exit(0)
            self.withdraw()
            logging.info("Agente iniciado en segundo plano (--background).")
            # Disparar chequeo inicial a los 3 segundos
            self.after(3000, lambda: self.trigger_sync(force_full=False))

    def _build_ui(self) -> None:
        # 1. Cabecera fija (arriba)
        hdr = tk.Frame(self, bg="#1e293b", padx=16, pady=8)
        hdr.pack(side="top", fill="x")
        tk.Label(
            hdr,
            text="🐱 LOLA · Agente Sincronizador (0% Recursos en Reposo)",
            font=("Segoe UI", 11, "bold"),
            fg="#f8fafc",
            bg="#1e293b",
        ).pack(anchor="w")
        tk.Label(
            hdr,
            text="Corre en segundo plano sin ventana cuando se ejecuta por Inicio o Tarea Programada",
            font=("Segoe UI", 8),
            fg="#94a3b8",
            bg="#1e293b",
        ).pack(anchor="w", pady=(1, 0))

        # 1.5 Banner Multi-Sesión / Coordinación (Solo Lectura vs Master Activo)
        self.role_banner = tk.Frame(self, bg="#064e3b", padx=14, pady=5)
        self.role_banner.pack(side="top", fill="x")

        self.role_status_lbl = tk.Label(
            self.role_banner,
            text="● Sincronizador activo en este equipo",
            font=("Segoe UI", 9, "bold"),
            bg="#064e3b",
            fg="#4ade80",
            anchor="w",
        )
        self.role_status_lbl.pack(side="left", fill="x", expand=True)

        self.btn_take_control = tk.Button(
            self.role_banner,
            text="⚡ Ejecutar aquí / Tomar control",
            font=("Segoe UI", 9, "bold"),
            bg="#eab308",
            fg="#0f172a",
            activebackground="#facc15",
            relief="flat",
            padx=10,
            pady=3,
            cursor="hand2",
            command=self._on_take_control,
        )

        # 2. Acciones principales fijadas (abajo, siempre visibles)
        actions = tk.Frame(self, bg="#0f172a")
        actions.pack(side="bottom", fill="x", padx=12, pady=(6, 8))

        self.btn_sync_delta = tk.Button(
            actions,
            text="⚡ Sincronizar Cambios (Delta)",
            font=("Segoe UI", 10, "bold"),
            bg="#2563eb",
            fg="#ffffff",
            activebackground="#1d4ed8",
            relief="flat",
            padx=12,
            pady=7,
            cursor="hand2",
            command=lambda: self.trigger_sync(force_full=False),
        )
        self.btn_sync_delta.pack(side="left", fill="x", expand=True, padx=(0, 6))

        self.btn_sync_full = tk.Button(
            actions,
            text="☁️ Subida Completa",
            font=("Segoe UI", 10),
            bg="#334155",
            fg="#f8fafc",
            activebackground="#475569",
            relief="flat",
            padx=12,
            pady=7,
            cursor="hand2",
            command=lambda: self.trigger_sync(force_full=True),
        )
        self.btn_sync_full.pack(side="left", padx=(0, 6))

        tk.Button(
            actions,
            text="📄 Logs",
            font=("Segoe UI", 10),
            bg="#334155",
            fg="#f8fafc",
            relief="flat",
            padx=10,
            pady=7,
            cursor="hand2",
            command=self._open_logs,
        ).pack(side="right", padx=(4, 0))

        tk.Button(
            actions,
            text="🛑 Salir",
            font=("Segoe UI", 10),
            bg="#475569",
            fg="#f8fafc",
            activebackground="#334155",
            relief="flat",
            padx=10,
            pady=7,
            cursor="hand2",
            command=self._quit_completely,
        ).pack(side="right", padx=(4, 0))

        tk.Button(
            actions,
            text="🗑️ Desinstalar",
            font=("Segoe UI", 10),
            bg="#7f1d1d",
            fg="#fecaca",
            activebackground="#991b1b",
            relief="flat",
            padx=10,
            pady=7,
            cursor="hand2",
            command=self._confirm_uninstall,
        ).pack(side="right")

        # 3. Contenedor central con scrollbar
        container = tk.Frame(self, bg="#0f172a")
        container.pack(side="top", fill="both", expand=True, padx=4, pady=2)

        self.canvas = tk.Canvas(container, bg="#0f172a", highlightthickness=0)
        self.scrollbar = ttk.Scrollbar(container, orient="vertical", command=self.canvas.yview)
        self.scrollable_frame = tk.Frame(self.canvas, bg="#0f172a")

        self.scrollable_frame.bind(
            "<Configure>",
            lambda e: self.canvas.configure(scrollregion=self.canvas.bbox("all")),
        )
        self.canvas_window = self.canvas.create_window((0, 0), window=self.scrollable_frame, anchor="nw")

        def _on_canvas_configure(event):
            self.canvas.itemconfig(self.canvas_window, width=event.width)

        self.canvas.bind("<Configure>", _on_canvas_configure)
        self.canvas.configure(yscrollcommand=self.scrollbar.set)

        self.canvas.pack(side="left", fill="both", expand=True)
        self.scrollbar.pack(side="right", fill="y")

        def _on_mousewheel(event):
            self.canvas.yview_scroll(int(-1 * (event.delta / 120)), "units")

        self.canvas.bind_all("<MouseWheel>", _on_mousewheel)

        # 4. Estado en vivo + Cuenta regresiva + Barra de progreso
        status_card = tk.Frame(self.scrollable_frame, bg="#1e293b", padx=14, pady=8)
        status_card.pack(fill="x", padx=10, pady=(4, 4))

        self.status_lbl = tk.Label(
            status_card,
            text="● Listo para sincronizar",
            font=("Segoe UI", 10, "bold"),
            fg="#38bdf8",
            bg="#1e293b",
        )
        self.status_lbl.pack(anchor="w")

        self.version_lbl = tk.Label(
            status_card,
            text="Revisión D1: — · Última sync: —",
            font=("Segoe UI", 9),
            fg="#cbd5e1",
            bg="#1e293b",
        )
        self.version_lbl.pack(anchor="w", pady=(1, 2))

        # Indicador de cuenta regresiva
        countdown_box = tk.Frame(status_card, bg="#1e293b")
        countdown_box.pack(fill="x", pady=(2, 2))

        self.countdown_lbl = tk.Label(
            countdown_box,
            text="⏳ Próxima sincronización: calculando...",
            font=("Segoe UI", 9, "bold"),
            fg="#facc15",
            bg="#1e293b",
            anchor="w",
        )
        self.countdown_lbl.pack(fill="x")

        # Barra de progreso visual para el intervalo programado
        style = ttk.Style()
        try:
            style.theme_use("clam")
        except Exception:
            pass
        style.configure(
            "Sync.Horizontal.TProgressbar",
            troughcolor="#0f172a",
            background="#2563eb",
            bordercolor="#1e293b",
            lightcolor="#38bdf8",
            darkcolor="#1d4ed8",
        )
        self.sync_progress = ttk.Progressbar(
            countdown_box,
            orient="horizontal",
            mode="determinate",
            style="Sync.Horizontal.TProgressbar",
        )
        self.sync_progress.pack(fill="x", pady=(3, 2))

        # 1. Selector de Modo: Automática (MDB) vs Manual (Excel Lista 4)
        mode_card = tk.LabelFrame(
            self.scrollable_frame,
            text=" 🔄 Modo de Sincronización ",
            font=("Segoe UI", 9, "bold"),
            fg="#e2e8f0",
            bg="#0f172a",
            padx=12,
            pady=6,
        )
        mode_card.pack(fill="x", padx=10, pady=4)

        mode_row = tk.Frame(mode_card, bg="#0f172a")
        mode_row.pack(fill="x")

        self.btn_mode_auto = tk.Button(
            mode_row,
            text="🤖 Automática (MDB + Stock)",
            font=("Segoe UI", 9, "bold"),
            relief="flat",
            padx=10,
            pady=5,
            cursor="hand2",
            command=lambda: self._set_mode("automatica"),
        )
        self.btn_mode_auto.pack(side="left", fill="x", expand=True, padx=(0, 6))

        self.btn_mode_manual = tk.Button(
            mode_row,
            text="📊 Manual (Excel Lista 4)",
            font=("Segoe UI", 9, "bold"),
            relief="flat",
            padx=10,
            pady=5,
            cursor="hand2",
            command=lambda: self._set_mode("manual"),
        )
        self.btn_mode_manual.pack(side="left", fill="x", expand=True)

        # 2. Configuración del estado de la DB ("En cada Inicio" + Intervalo)
        cfg_card = tk.LabelFrame(
            self.scrollable_frame,
            text=" ⚙️ Estado y Frecuencia de la DB ",
            font=("Segoe UI", 9, "bold"),
            fg="#e2e8f0",
            bg="#0f172a",
            padx=12,
            pady=6,
        )
        cfg_card.pack(fill="x", padx=10, pady=4)

        row_startup = tk.Frame(cfg_card, bg="#0f172a")
        row_startup.pack(fill="x", pady=3)
        tk.Label(
            row_startup,
            text="En cada Inicio de la PC (ON / OFF):",
            font=("Segoe UI", 9),
            fg="#f1f5f9",
            bg="#0f172a",
        ).pack(side="left")

        self.startup_btn = tk.Button(
            row_startup,
            text="ON" if self.config_obj.sync_on_startup else "OFF",
            width=8,
            font=("Segoe UI", 9, "bold"),
            fg="#ffffff",
            bg="#16a34a" if self.config_obj.sync_on_startup else "#475569",
            relief="flat",
            cursor="hand2",
            command=self._toggle_startup,
        )
        self.startup_btn.pack(side="right")

        row_interval = tk.Frame(cfg_card, bg="#0f172a")
        row_interval.pack(fill="x", pady=3)
        tk.Label(
            row_interval,
            text="Actualización periódica silenciosa (min):",
            font=("Segoe UI", 9),
            fg="#f1f5f9",
            bg="#0f172a",
        ).pack(side="left")

        self.interval_var = tk.StringVar(value=interval_to_label(self.config_obj.sync_interval_minutes))
        self.interval_combo = ttk.Combobox(
            row_interval,
            textvariable=self.interval_var,
            values=[interval_to_label(m) for m in INTERVAL_OPTIONS],
            state="readonly",
            width=14,
            font=("Segoe UI", 9),
        )
        self.interval_combo.pack(side="right")
        self.interval_combo.bind("<<ComboboxSelected>>", self._on_interval_changed)

        # 3. Origen de Datos (Archivo MDB + Clave MDB / o Archivo Excel)
        self.source_card = tk.LabelFrame(
            self.scrollable_frame,
            text=" 🗄️ Archivo de Origen (MDB / Excel) ",
            font=("Segoe UI", 9, "bold"),
            fg="#e2e8f0",
            bg="#0f172a",
            padx=12,
            pady=6,
        )
        self.source_card.pack(fill="x", padx=10, pady=4)

        # Sub-bloque MDB (Búsqueda + Fijar archivo + Carpeta de Consumo aislada)
        self.mdb_frame = tk.Frame(self.source_card, bg="#0f172a")

        # 1. Carpeta de búsqueda
        tk.Label(
            self.mdb_frame,
            text="1. Carpeta de búsqueda (donde está instalada la base de AdmGlobal):",
            font=("Segoe UI", 9),
            fg="#cbd5e1",
            bg="#0f172a",
        ).pack(anchor="w", pady=(2, 1))

        folder_row = tk.Frame(self.mdb_frame, bg="#0f172a")
        folder_row.pack(fill="x", pady=(0, 4))
        self.mdb_folder_entry = tk.Entry(
            folder_row,
            font=("Consolas", 9),
            bg="#1e293b",
            fg="#f8fafc",
            insertbackground="#f8fafc",
            relief="flat",
        )
        self.mdb_folder_entry.insert(0, self.config_obj.mdb_folder)
        self.mdb_folder_entry.pack(side="left", fill="x", expand=True, ipady=3, padx=(0, 6))
        self.mdb_folder_entry.bind("<KeyRelease>", lambda e: self._on_path_changed())

        tk.Button(
            folder_row,
            text="📁 Carpeta Origen…",
            font=("Segoe UI", 9),
            bg="#334155",
            fg="#f8fafc",
            relief="flat",
            padx=8,
            cursor="hand2",
            command=self._browse_mdb_folder,
        ).pack(side="right")

        # 2. Nombre del archivo / Buscador para fijar
        tk.Label(
            self.mdb_frame,
            text="2. Archivo base (.mdb) — escribí el nombre o buscalo para fijarlo:",
            font=("Segoe UI", 9),
            fg="#cbd5e1",
            bg="#0f172a",
        ).pack(anchor="w", pady=(2, 1))

        file_row = tk.Frame(self.mdb_frame, bg="#0f172a")
        file_row.pack(fill="x", pady=(0, 2))
        self.mdb_filename_entry = tk.Entry(
            file_row,
            font=("Consolas", 9),
            bg="#1e293b",
            fg="#f8fafc",
            insertbackground="#f8fafc",
            relief="flat",
        )
        self.mdb_filename_entry.insert(0, self.config_obj.mdb_filename)
        self.mdb_filename_entry.pack(side="left", fill="x", expand=True, ipady=3, padx=(0, 6))
        self.mdb_filename_entry.bind("<KeyRelease>", lambda e: self._on_path_changed())

        tk.Button(
            file_row,
            text="🔍 Buscar / Fijar Archivo…",
            font=("Segoe UI", 9),
            bg="#334155",
            fg="#f8fafc",
            relief="flat",
            padx=8,
            cursor="hand2",
            command=self._browse_mdb_file,
        ).pack(side="right")

        self.resolved_path_lbl = tk.Label(
            self.mdb_frame,
            text="",
            font=("Segoe UI", 8),
            fg="#94a3b8",
            bg="#0f172a",
            anchor="w",
        )
        self.resolved_path_lbl.pack(fill="x", pady=(0, 5))

        # 3. Carpeta de consumo (Staging / Copia aislada)
        tk.Label(
            self.mdb_frame,
            text="3. Carpeta de consumo (aquí se copia el MDB para no tocar jamás el original):",
            font=("Segoe UI", 9),
            fg="#cbd5e1",
            bg="#0f172a",
        ).pack(anchor="w", pady=(2, 1))

        consumo_row = tk.Frame(self.mdb_frame, bg="#0f172a")
        consumo_row.pack(fill="x", pady=(0, 2))
        self.consumo_entry = tk.Entry(
            consumo_row,
            font=("Consolas", 9),
            bg="#1e293b",
            fg="#f8fafc",
            insertbackground="#f8fafc",
            relief="flat",
        )
        self.consumo_entry.insert(0, self.config_obj.consumo_dir)
        self.consumo_entry.pack(side="left", fill="x", expand=True, ipady=3, padx=(0, 6))

        tk.Button(
            consumo_row,
            text="📁 Carpeta Consumo…",
            font=("Segoe UI", 9),
            bg="#334155",
            fg="#f8fafc",
            relief="flat",
            padx=8,
            cursor="hand2",
            command=self._browse_consumo_dir,
        ).pack(side="right")

        tk.Label(
            self.mdb_frame,
            text="🛡️ AdmGlobal original intacto: el agente copia a consumo y ejecuta consultas solo sobre esa copia.",
            font=("Segoe UI", 8, "italic"),
            fg="#38bdf8",
            bg="#0f172a",
        ).pack(anchor="w", pady=(0, 5))

        # 4. Clave MDB
        mdb_pwd_row = tk.Frame(self.mdb_frame, bg="#0f172a")
        mdb_pwd_row.pack(fill="x", pady=(2, 2))
        tk.Label(
            mdb_pwd_row,
            text="Clave de AdmGlobal.MDB:",
            font=("Segoe UI", 9),
            fg="#cbd5e1",
            bg="#0f172a",
        ).pack(side="left")
        self.btn_toggle_mdb_pwd = tk.Button(
            mdb_pwd_row,
            text="👁",
            font=("Segoe UI", 8),
            bg="#334155",
            fg="#f8fafc",
            relief="flat",
            padx=6,
            command=self._toggle_mdb_pwd_vis,
        )
        self.btn_toggle_mdb_pwd.pack(side="right")
        self.mdb_pwd_entry = tk.Entry(
            mdb_pwd_row,
            font=("Consolas", 9),
            bg="#1e293b",
            fg="#f8fafc",
            insertbackground="#f8fafc",
            relief="flat",
            show="•",
            width=22,
        )
        self.mdb_pwd_entry.insert(0, self.config_obj.mdb_password)
        self.mdb_pwd_entry.pack(side="right", ipady=3, padx=(0, 4))

        # Sub-bloque Excel
        self.excel_frame = tk.Frame(self.source_card, bg="#0f172a")
        tk.Label(
            self.excel_frame,
            text="Archivo Excel de Lista 4 (.xls / .xlsx — Col B: Nombre, Col C: Lista 4):",
            font=("Segoe UI", 9),
            fg="#94a3b8",
            bg="#0f172a",
        ).pack(anchor="w")
        xls_sub = tk.Frame(self.excel_frame, bg="#0f172a")
        xls_sub.pack(fill="x", pady=(2, 0))
        self.excel_entry = tk.Entry(
            xls_sub,
            font=("Consolas", 9),
            bg="#1e293b",
            fg="#f8fafc",
            insertbackground="#f8fafc",
            relief="flat",
        )
        self.excel_entry.insert(0, self.config_obj.excel_path)
        self.excel_entry.pack(side="left", fill="x", expand=True, ipady=4, padx=(0, 6))
        tk.Button(
            xls_sub,
            text="📂 Elegir Excel…",
            font=("Segoe UI", 9),
            bg="#334155",
            fg="#f8fafc",
            relief="flat",
            padx=8,
            cursor="hand2",
            command=self._browse_excel,
        ).pack(side="right")

        # 4. Conexión al Worker (Cloudflare D1)
        api_card = tk.LabelFrame(
            self.scrollable_frame,
            text=" ☁️ Conexión al Worker (Cloudflare D1) ",
            font=("Segoe UI", 9, "bold"),
            fg="#e2e8f0",
            bg="#0f172a",
            padx=12,
            pady=6,
        )
        api_card.pack(fill="x", padx=10, pady=4)

        url_row = tk.Frame(api_card, bg="#0f172a")
        url_row.pack(fill="x", pady=3)
        tk.Label(
            url_row,
            text="URL del Worker:",
            width=15,
            anchor="w",
            font=("Segoe UI", 9),
            fg="#cbd5e1",
            bg="#0f172a",
        ).pack(side="left")
        self.api_url_entry = tk.Entry(
            url_row,
            font=("Consolas", 9),
            bg="#1e293b",
            fg="#f8fafc",
            insertbackground="#f8fafc",
            relief="flat",
        )
        self.api_url_entry.insert(0, self.config_obj.api_url)
        self.api_url_entry.pack(side="left", fill="x", expand=True, ipady=3)

        cred_row = tk.Frame(api_card, bg="#0f172a")
        cred_row.pack(fill="x", pady=3)
        tk.Label(
            cred_row,
            text="Usuario Admin:",
            width=15,
            anchor="w",
            font=("Segoe UI", 9),
            fg="#cbd5e1",
            bg="#0f172a",
        ).pack(side="left")
        self.api_user_entry = tk.Entry(
            cred_row,
            font=("Consolas", 9),
            bg="#1e293b",
            fg="#f8fafc",
            insertbackground="#f8fafc",
            relief="flat",
            width=16,
        )
        self.api_user_entry.insert(0, self.config_obj.api_user)
        self.api_user_entry.pack(side="left", ipady=3, padx=(0, 10))

        tk.Label(
            cred_row,
            text="Clave Admin:",
            font=("Segoe UI", 9),
            fg="#cbd5e1",
            bg="#0f172a",
        ).pack(side="left")
        self.btn_toggle_api_pwd = tk.Button(
            cred_row,
            text="👁",
            font=("Segoe UI", 8),
            bg="#334155",
            fg="#f8fafc",
            relief="flat",
            padx=6,
            command=self._toggle_api_pwd_vis,
        )
        self.btn_toggle_api_pwd.pack(side="right")
        self.api_pwd_entry = tk.Entry(
            cred_row,
            font=("Consolas", 9),
            bg="#1e293b",
            fg="#f8fafc",
            insertbackground="#f8fafc",
            relief="flat",
            show="•",
        )
        self.api_pwd_entry.insert(0, self.config_obj.api_password)
        self.api_pwd_entry.pack(side="left", fill="x", expand=True, ipady=3, padx=(4, 4))

        save_row = tk.Frame(api_card, bg="#0f172a")
        save_row.pack(fill="x", pady=(6, 2))
        tk.Button(
            save_row,
            text="💾 Guardar Configuración y Programación",
            font=("Segoe UI", 9, "bold"),
            bg="#0ea5e9",
            fg="#ffffff",
            activebackground="#0284c7",
            relief="flat",
            padx=10,
            pady=5,
            cursor="hand2",
            command=self._save_all_from_ui,
        ).pack(side="left", fill="x", expand=True, padx=(0, 6))

        tk.Button(
            save_row,
            text="🔌 Probar Login Worker",
            font=("Segoe UI", 9),
            bg="#334155",
            fg="#f8fafc",
            relief="flat",
            padx=10,
            pady=5,
            cursor="hand2",
            command=self._test_worker_login,
        ).pack(side="right")

    def _toggle_mdb_pwd_vis(self) -> None:
        self._show_mdb_pwd = not self._show_mdb_pwd
        self.mdb_pwd_entry.configure(show="" if self._show_mdb_pwd else "•")

    def _toggle_api_pwd_vis(self) -> None:
        self._show_api_pwd = not self._show_api_pwd
        self.api_pwd_entry.configure(show="" if self._show_api_pwd else "•")

    def _sync_fields_to_config(self) -> None:
        self.config_obj.mdb_folder = self.mdb_folder_entry.get().strip()
        self.config_obj.mdb_filename = self.mdb_filename_entry.get().strip() or "AdmGlobal.MDB"
        self.config_obj.consumo_dir = self.consumo_entry.get().strip() or os.path.join(self.config_obj.base_dir, "consumo")
        self.config_obj.mdb_password = self.mdb_pwd_entry.get().strip() or "toifel"
        self.config_obj.excel_path = self.excel_entry.get().strip()
        self.config_obj.api_url = self.api_url_entry.get().strip()
        self.config_obj.api_user = self.api_user_entry.get().strip() or "admin"
        self.config_obj.api_password = self.api_pwd_entry.get()
        self.config_obj.save()

    def _on_path_changed(self) -> None:
        folder = self.mdb_folder_entry.get().strip()
        fname = self.mdb_filename_entry.get().strip()
        full = os.path.join(folder, fname) if folder and fname else ""
        if full and os.path.exists(full):
            try:
                sz_mb = round(os.path.getsize(full) / (1024 * 1024), 2)
                self.resolved_path_lbl.configure(
                    text=f"✓ Archivo detectado ({sz_mb} MB): {full}",
                    fg="#4ade80",
                )
            except Exception:
                self.resolved_path_lbl.configure(
                    text=f"✓ Archivo detectado: {full}",
                    fg="#4ade80",
                )
        else:
            self.resolved_path_lbl.configure(
                text=f"⚠ Archivo NO encontrado: {full or '(ruta incompleta)'}",
                fg="#f87171",
            )

    def _apply_os_schedules(self) -> None:
        is_auto = self.config_obj.sync_mode == "automatica"
        is_periodic = is_auto and (self.config_obj.sync_interval_minutes > 0)
        set_run_on_startup(
            enabled=is_auto and self.config_obj.sync_on_startup,
            script_path=__file__,
            is_periodic=is_periodic,
        )
        # Eliminamos cualquier tarea en schtasks para evitar duplicaciones
        # y dejar el control 100% en el proceso residente (--background)
        configure_periodic_task(
            enabled=False,
            interval_minutes=0,
            script_path=__file__,
        )

    def _save_all_from_ui(self) -> None:
        self._sync_fields_to_config()
        self._apply_os_schedules()
        self.status_lbl.configure(
            text="✓ Configuración guardada e inicio de Windows actualizado",
            fg="#4ade80",
        )

    def _test_worker_login(self) -> None:
        self._sync_fields_to_config()
        if not self.config_obj.api_url or not self.config_obj.api_password:
            messagebox.showwarning("Faltan datos", "Completá la URL del Worker y la Clave Admin primero.")
            return
        try:
            up = D1Uploader(self.config_obj.api_url, self.config_obj.api_user, self.config_obj.api_password)
            up.login()
            messagebox.showinfo("Conexión Exitosa", "✅ Login JWT exitoso contra el Worker de LOLA.")
        except Exception as e:
            messagebox.showerror("Error de Conexión", f"No se pudo autenticar contra el Worker:\n{e}")

    def _set_mode(self, mode: str) -> None:
        if mode not in SYNC_MODES:
            return
        self.config_obj.sync_mode = mode
        self._sync_fields_to_config()
        self._apply_os_schedules()
        self._refresh_mode_ui()
        self._schedule_next_timer()

    def _refresh_mode_ui(self) -> None:
        is_auto = self.config_obj.sync_mode == "automatica"
        self.btn_mode_auto.configure(
            bg="#2563eb" if is_auto else "#1e293b",
            fg="#ffffff" if is_auto else "#94a3b8",
        )
        self.btn_mode_manual.configure(
            bg="#2563eb" if not is_auto else "#1e293b",
            fg="#ffffff" if not is_auto else "#94a3b8",
        )
        self.mdb_frame.pack_forget()
        self.excel_frame.pack_forget()
        if is_auto:
            self.mdb_frame.pack(fill="x")
        else:
            self.excel_frame.pack(fill="x")

    def _toggle_startup(self) -> None:
        self.config_obj.sync_on_startup = not self.config_obj.sync_on_startup
        self._sync_fields_to_config()
        self._apply_os_schedules()
        if self.config_obj.sync_on_startup:
            self.startup_btn.configure(text="ON", bg="#16a34a")
        else:
            self.startup_btn.configure(text="OFF", bg="#475569")

    def _on_interval_changed(self, _event: Any = None) -> None:
        mins = label_to_interval(self.interval_var.get())
        if mins in INTERVAL_OPTIONS:
            self.config_obj.sync_interval_minutes = mins
            self._sync_fields_to_config()
            self._apply_os_schedules()
            self._schedule_next_timer()
            if mins == 0:
                logging.info("Actualización periódica desactivada (OFF). Solo corre al iniciar Windows.")
                self.status_lbl.configure(
                    text="ℹ Frecuencia en OFF: el agente solo sincronizará al iniciar Windows",
                    fg="#38bdf8",
                )
            else:
                logging.info(f"Intervalo de actualización ajustado a {mins} minutos.")
                self.status_lbl.configure(
                    text=f"✓ Intervalo actualizado: cada {mins} min (y al iniciar Windows)",
                    fg="#4ade80",
                )

    def _browse_mdb_folder(self) -> None:
        init_dir = self.mdb_folder_entry.get().strip()
        if not os.path.exists(init_dir):
            init_dir = self.config_obj.base_dir
        folder = filedialog.askdirectory(
            title="Seleccionar Carpeta donde está AdmGlobal",
            initialdir=init_dir,
        )
        if folder:
            self.mdb_folder_entry.delete(0, tk.END)
            self.mdb_folder_entry.insert(0, os.path.normpath(folder))
            self._on_path_changed()
            self._sync_fields_to_config()

    def _browse_mdb_file(self) -> None:
        init_dir = self.mdb_folder_entry.get().strip()
        if not os.path.exists(init_dir):
            init_dir = self.config_obj.base_dir
        path = filedialog.askopenfilename(
            title="Seleccionar archivo base AdmGlobal.MDB",
            initialdir=init_dir,
            filetypes=[("Microsoft Access DB", "*.mdb *.MDB *.accdb"), ("Todos los archivos", "*.*")],
        )
        if path:
            norm_path = os.path.normpath(path)
            self.mdb_folder_entry.delete(0, tk.END)
            self.mdb_folder_entry.insert(0, os.path.dirname(norm_path))
            self.mdb_filename_entry.delete(0, tk.END)
            self.mdb_filename_entry.insert(0, os.path.basename(norm_path))
            self._on_path_changed()
            self._sync_fields_to_config()

    def _browse_consumo_dir(self) -> None:
        init_dir = self.consumo_entry.get().strip()
        if not os.path.exists(init_dir):
            init_dir = self.config_obj.base_dir
        folder = filedialog.askdirectory(
            title="Seleccionar Carpeta de Consumo (Staging / Copia aislada)",
            initialdir=init_dir,
        )
        if folder:
            self.consumo_entry.delete(0, tk.END)
            self.consumo_entry.insert(0, os.path.normpath(folder))
            self._sync_fields_to_config()

    def _browse_excel(self) -> None:
        path = filedialog.askopenfilename(
            title="Seleccionar Excel de Lista 4",
            filetypes=[("Archivos Excel", "*.xls *.xlsx *.xlsm"), ("Todos los archivos", "*.*")],
        )
        if path:
            self.excel_entry.delete(0, tk.END)
            self.excel_entry.insert(0, path)
            self._sync_fields_to_config()

    def _refresh_role_ui(self) -> None:
        if self.coordinator.is_master:
            self.role_banner.configure(bg="#064e3b")
            self.role_status_lbl.configure(
                text=f"● Sincronizador Activo: esta máquina ({self.coordinator.hostname}\\{self.coordinator.username})",
                bg="#064e3b",
                fg="#4ade80",
            )
            self.btn_take_control.pack_forget()
            self.btn_sync_delta.configure(state="normal")
            self.btn_sync_full.configure(state="normal")
        else:
            peer = self.coordinator.read_active_info()
            host = peer.get("hostname", "otra máquina")
            user = peer.get("username", "otra sesión")
            pid = peer.get("pid", "")
            if self.coordinator.is_peer_alive(peer):
                self.role_banner.configure(bg="#451a03")
                self.role_status_lbl.configure(
                    text=f"🔒 Sincronizando en: {host} ({user}, PID {pid}) · Esta ventana está en SOLO LECTURA",
                    bg="#451a03",
                    fg="#fde047",
                )
            else:
                self.role_banner.configure(bg="#1e293b")
                self.role_status_lbl.configure(
                    text="ℹ️ El sincronizador anterior está inactivo. Podés tomar el control con el botón de la derecha.",
                    bg="#1e293b",
                    fg="#38bdf8",
                )
            self.btn_take_control.pack(side="right")
            self.btn_sync_delta.configure(state="disabled")
            self.btn_sync_full.configure(state="disabled")
            self.sync_progress["value"] = 0
            self.countdown_lbl.configure(
                text="⏸ Modo Solo Lectura (el cronómetro y la sincronización corren en el otro equipo)",
                fg="#fde047",
            )
            if self._timer_id is not None:
                self.after_cancel(self._timer_id)
                self._timer_id = None

    def _on_take_control(self) -> None:
        self.coordinator.claim_master()
        self._refresh_role_ui()
        self._schedule_next_timer()
        messagebox.showinfo(
            "Control Asumido",
            f"✅ Esta máquina ({self.coordinator.hostname}\\{self.coordinator.username}) asumió el rol de Sincronizador Activo.\n\n"
            "Las demás sesiones o equipos pasarán a Modo Solo Lectura automáticamente."
        )

    def _heartbeat_tick(self) -> None:
        if self.coordinator.is_master:
            still_master = self.coordinator.heartbeat()
            if not still_master:
                logging.warning("El rol MASTER fue transferido a otra sesión. Cambiando a Modo Solo Lectura.")
                self._refresh_role_ui()
                try:
                    if self.wm_state() == "withdrawn":
                        logging.info("Instancia en segundo plano ya no es master. Cerrando proceso silenciosamente.")
                        self.destroy()
                        sys.exit(0)
                except Exception:
                    pass
        else:
            self._refresh_role_ui()

        self._heartbeat_job = self.after(5000, self._heartbeat_tick)

    def _on_window_unmap(self, event: Any) -> None:
        if event.widget == self:
            # Ventana minimizada a la barra de tareas: cancelamos el tick para CERO consumo de CPU
            if self._countdown_job is not None:
                self.after_cancel(self._countdown_job)
                self._countdown_job = None

    def _on_window_map(self, event: Any) -> None:
        if event.widget == self:
            # Ventana restaurada al primer plano: reanudamos visualmente si corresponde
            if self._countdown_job is None and self.config_obj.sync_mode == "automatica" and self.config_obj.sync_interval_minutes > 0:
                self._update_countdown_tick()

    def _format_last_sync(self) -> str:
        if not self._last_sync_time:
            return ""
        try:
            diff = int((datetime.now() - self._last_sync_time).total_seconds())
            if diff < 0:
                diff = 0
            if diff < 60:
                return f"hace {diff}s"
            elif diff < 3600:
                return f"hace {diff // 60}m"
            else:
                return f"hace {diff // 3600}h {(diff % 3600) // 60}m"
        except Exception:
            return ""

    def _schedule_next_timer(self) -> None:
        if self._timer_id is not None:
            self.after_cancel(self._timer_id)
            self._timer_id = None
        if self._countdown_job is not None:
            self.after_cancel(self._countdown_job)
            self._countdown_job = None

        if not self.coordinator.is_master:
            self.sync_progress["value"] = 0
            self.countdown_lbl.configure(
                text="⏸ Modo Solo Lectura (el cronómetro y la sincronización corren en el otro equipo)",
                fg="#fde047",
            )
            return

        is_auto = self.config_obj.sync_mode == "automatica"
        mins = self.config_obj.sync_interval_minutes

        if is_auto and mins > 0:
            now = datetime.now()
            if self._cycle_anchor:
                expected_next = self._cycle_anchor + timedelta(minutes=mins)
                if now >= expected_next:
                    # El tiempo del ciclo ya venció! Sincronizamos en breve
                    self._next_sync_time = now
                    self.sync_progress["value"] = 100
                    self.countdown_lbl.configure(
                        text="⚡ Ciclo vencido · Sincronizando en este momento...",
                        fg="#38bdf8",
                    )
                    self._timer_id = self.after(1500, self._on_timer_tick)
                    return
                else:
                    self._next_sync_time = expected_next
                    rem_ms = max(1000, int((expected_next - now).total_seconds() * 1000))
                    self._timer_id = self.after(rem_ms, self._on_timer_tick)
                    self._update_countdown_tick()
                    return
            else:
                self._next_sync_time = now + timedelta(minutes=mins)
                ms = mins * 60 * 1000
                self._timer_id = self.after(ms, self._on_timer_tick)
                self._update_countdown_tick()
        else:
            self._next_sync_time = None
            self.sync_progress["value"] = 0
            ago_str = self._format_last_sync()
            if not is_auto:
                msg = "⏸ Modo Manual (Excel Lista 4) — Sincronización a demanda"
            else:
                msg = "⏸ Frecuencia automática en OFF (solo corre al iniciar Windows o manual)"
            if ago_str:
                msg += f" · Última: {ago_str}"
            self.countdown_lbl.configure(text=msg, fg="#94a3b8")

    def _on_window_close(self) -> None:
        if self.coordinator.is_master and self.config_obj.sync_mode == "automatica" and self.config_obj.sync_interval_minutes > 0:
            self.withdraw()
            logging.info(
                f"Ventana oculta a segundo plano. El agente sigue sincronizando cada {self.config_obj.sync_interval_minutes} min."
            )
        else:
            self.destroy()
            sys.exit(0)

    def _quit_completely(self) -> None:
        if self.config_obj.sync_mode == "automatica" and self.config_obj.sync_interval_minutes > 0:
            ans = messagebox.askyesno(
                "Cerrar Agente",
                f"El agente está configurado para sincronizar cada {self.config_obj.sync_interval_minutes} minutos.\n\n"
                "¿Deseás cerrar la aplicación por completo?\n\n"
                "(La sincronización periódica en segundo plano se detendrá hasta que vuelvas a abrir el agente o inicies Windows).",
                icon="question",
            )
            if not ans:
                return
        self.destroy()
        sys.exit(0)

    def _update_countdown_tick(self) -> None:
        if self._countdown_job is not None:
            self.after_cancel(self._countdown_job)
            self._countdown_job = None

        # Si la ventana está minimizada (iconic), congelamos el ciclo visual (0% CPU)
        try:
            if self.wm_state() in ("iconic", "withdrawn"):
                return
        except Exception:
            pass

        is_auto = self.config_obj.sync_mode == "automatica"
        mins = self.config_obj.sync_interval_minutes

        if not is_auto or mins <= 0 or not self._next_sync_time:
            self.sync_progress["value"] = 0
            return

        now = datetime.now()
        total_sec = mins * 60
        rem_sec = int((self._next_sync_time - now).total_seconds())

        if rem_sec <= 0:
            self.sync_progress["value"] = 100
            self.countdown_lbl.configure(
                text="⚡ Sincronizando en este momento...",
                fg="#38bdf8",
            )
        else:
            elapsed_sec = total_sec - rem_sec
            pct = max(0.0, min(100.0, (elapsed_sec / total_sec) * 100.0))
            self.sync_progress["value"] = pct

            m = rem_sec // 60
            s = rem_sec % 60
            txt = f"⏳ Próxima sync en: {m}m {s:02d}s (cada {mins} min)"
            ago_str = self._format_last_sync()
            if ago_str:
                txt += f" · Última: {ago_str}"
            self.countdown_lbl.configure(text=txt, fg="#facc15")

        self._countdown_job = self.after(1000, self._update_countdown_tick)

    def _on_timer_tick(self) -> None:
        if self.config_obj.sync_mode == "automatica":
            self.trigger_sync(force_full=False)
        self._schedule_next_timer()

    def _refresh_state_summary(self) -> None:
        engine = DeltaEngine(self.config_obj.state_dir)
        st = engine.load_previous_state()
        rev = st.get("revision", "—")
        upd = st.get("updated_at", "Nunca")
        total = st.get("total_items", 0)
        self.version_lbl.configure(
            text=f"Revisión D1: #{rev} · Artículos activos: {total} · Última sync: {upd}"
        )
        if upd and upd != "Nunca" and not self._last_sync_time:
            try:
                self._last_sync_time = datetime.fromisoformat(upd)
                if self._cycle_anchor is None:
                    self._cycle_anchor = self._last_sync_time
            except Exception:
                pass

    def trigger_sync(self, force_full: bool = False) -> None:
        if not self.coordinator.is_master:
            messagebox.showwarning(
                "Modo Solo Lectura",
                "Esta ventana está en Modo Solo Lectura porque otra sesión o equipo tiene el control activo.\n\n"
                "Para sincronizar desde aquí, hacé clic en '⚡ Ejecutar aquí / Tomar control' arriba.",
            )
            return

        if not self._sync_lock.acquire(blocking=False):
            return

        self._sync_fields_to_config()
        self._cycle_anchor = datetime.now()

        is_auto = self.config_obj.sync_mode == "automatica"
        if not is_auto and not self.config_obj.excel_path:
            self._sync_lock.release()
            messagebox.showwarning("Modo Manual", "Seleccioná primero un archivo Excel (.xls / .xlsx) de Lista 4.")
            return

        self.btn_sync_delta.configure(state="disabled")
        self.btn_sync_full.configure(state="disabled")
        self.status_lbl.configure(
            text="⏳ Procesando catálogo y calculando diferencias...",
            fg="#facc15",
        )

        def worker():
            try:
                if is_auto:
                    res = execute_sync_mdb(self.config_obj, force_full=force_full)
                else:
                    res = execute_sync_excel(
                        self.config_obj,
                        self.config_obj.excel_path,
                        force_full=force_full,
                    )
                self.after(0, lambda: self._on_sync_done(True, res))
            except Exception as e:
                logging.exception("Error durante la sincronización:")
                self.after(0, lambda: self._on_sync_done(False, {"error": str(e)}))
            finally:
                self._sync_lock.release()

        threading.Thread(target=worker, daemon=True).start()

    def _on_sync_done(self, ok: bool, res: Dict[str, Any]) -> None:
        self.btn_sync_delta.configure(state="normal")
        self.btn_sync_full.configure(state="normal")
        if not ok:
            err = str(res.get("error", ""))
            if "otra sincronización" in err.lower():
                self.status_lbl.configure(
                    text="ℹ Sincronización omitida: otra tarea está en curso. El ciclo programado no se altera.",
                    fg="#38bdf8",
                )
            else:
                self.status_lbl.configure(text=f"✗ Error: {err[:65]}", fg="#f87171")
            return

        src = res.get("source", "")
        mode = res.get("mode", "")
        upserts = res.get("upserts", 0)
        deleted = res.get("deleted", 0)
        total = res.get("total", 0)
        now_str = datetime.now().strftime("%H:%M:%S")

        if mode == "skip":
            self.status_lbl.configure(
                text=f"✓ [{src}] Al día ({now_str}) · Sin cambios en los {total} artículos",
                fg="#4ade80",
            )
        else:
            self.status_lbl.configure(
                text=f"✓ [{src}] Sync {mode.upper()} ({now_str}) · +{upserts} cambiados, -{deleted} bajas (Total: {total})",
                fg="#4ade80",
            )
        self._last_sync_time = datetime.now()
        self._refresh_state_summary()
        self._schedule_next_timer()

    def _open_logs(self) -> None:
        log_file = os.path.join(self.config_obj.logs_dir, "sync.log")
        if os.path.exists(log_file):
            os.startfile(log_file)
        else:
            messagebox.showinfo("Logs", "Aún no se ha generado el archivo de log.")

    def _confirm_uninstall(self) -> None:
        ans = messagebox.askyesno(
            "Desinstalar Agente",
            "¿Deseás desinstalar el Agente de LOLA por completo de este equipo?\n\n"
            "Esto realizará las siguientes acciones:\n"
            "  1. Eliminar el inicio silencioso con Windows (HKCU Run).\n"
            "  2. Eliminar la tarea periódica programada (schtasks).\n\n"
            "Además, se mantendrá 'Desinstalar.bat' en la carpeta por si "
            "también deseás limpiar los logs y eliminar el archivo ejecutable.\n\n"
            "¿Confirmar desinstalación ahora?",
            icon="warning",
        )
        if not ans:
            return

        ok = uninstall_all(__file__)
        create_uninstall_batch(self.config_obj.base_dir)
        if ok:
            messagebox.showinfo(
                "Desinstalación Exitosa",
                "✅ El agente fue desvinculado del sistema Windows exitosamente.\n\n"
                "Ya no se ejecutará al iniciar ni en segundo plano.\n"
                "Para borrar la carpeta por completo podés ejecutar 'Desinstalar.bat'.",
            )
            self.destroy()
        else:
            messagebox.showwarning(
                "Aviso",
                "Se intentó desvincular el agente pero hubo un detalle en Windows.\n"
                "Podés ejecutar 'Desinstalar.bat' como Administrador para completar la limpieza.",
            )

    def destroy(self) -> None:
        if self._heartbeat_job is not None:
            try:
                self.after_cancel(self._heartbeat_job)
            except Exception:
                pass
            self._heartbeat_job = None
        if self._timer_id is not None:
            try:
                self.after_cancel(self._timer_id)
            except Exception:
                pass
            self._timer_id = None
        if self._countdown_job is not None:
            try:
                self.after_cancel(self._countdown_job)
            except Exception:
                pass
            self._countdown_job = None
        try:
            self.coordinator.release()
        except Exception:
            pass
        super().destroy()


if __name__ == "__main__":
    if "--uninstall" in sys.argv:
        cfg = AgentConfig(BASE_DIR)
        setup_logging(cfg.logs_dir, silent=True)
        ok = uninstall_all(__file__)
        create_uninstall_batch(cfg.base_dir)
        print("Desinstalación de tareas y registro completada exitosamente.")
        sys.exit(0 if ok else 1)

    is_silent_once = "--once" in sys.argv
    is_background = "--background" in sys.argv
    cfg = AgentConfig(BASE_DIR)
    setup_logging(cfg.logs_dir, silent=(is_silent_once or is_background))

    if is_silent_once:
        coord = InstanceCoordinator(cfg.state_dir)
        peer = coord.read_active_info()
        if coord.is_peer_alive(peer) and peer.get("instance_id") != coord.instance_id:
            logging.info(
                f"Sincronización omitida (--once): instancia activa en {peer.get('hostname')}\\{peer.get('username')} (PID {peer.get('pid')})."
            )
            sys.exit(0)
        full = "--full" in sys.argv
        try:
            if cfg.sync_mode == "manual" and cfg.excel_path:
                execute_sync_excel(cfg, cfg.excel_path, force_full=full)
            else:
                execute_sync_mdb(cfg, force_full=full)
            sys.exit(0)
        except SyncAlreadyRunningError as e:
            logging.info(f"{e} Se espera estrictamente al próximo ciclo programado.")
            sys.exit(0)
        except Exception as e:
            logging.error(f"Error en ejecución silenciosa (--once): {e}")
            sys.exit(1)
    else:
        # Si el usuario abre la app y ya hay una instancia corriendo en segundo plano, la restauramos y traemos al frente
        # (y si se lanza --background teniendo ya una instancia viva, no se duplica)
        try:
            import ctypes
            hwnd = ctypes.windll.user32.FindWindowW(None, WINDOW_TITLE)
            if hwnd:
                if not is_background:
                    ctypes.windll.user32.ShowWindow(hwnd, 9)  # SW_RESTORE
                    ctypes.windll.user32.SetForegroundWindow(hwnd)
                sys.exit(0)
        except SystemExit:
            raise
        except Exception:
            pass

        app = SyncAppWindow(cfg, start_hidden=is_background)
        app.mainloop()
