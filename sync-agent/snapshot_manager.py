# ============================================================================
# snapshot_manager.py — Copia atómica en caliente + Rotación de Backups locales
# ============================================================================

from __future__ import annotations

import logging
import os
import shutil
import time

JET_HEADER_MAGIC = b"\x00\x01\x00\x00Standard Jet DB"
ACE_HEADER_MAGIC = b"\x00\x01\x00\x00Standard ACE DB"


def detect_access_lock(mdb_path: str) -> bool:
    """Devuelve True si AdmGlobal tiene abierto el archivo (.ldb / .laccdb)."""
    base, _ = os.path.splitext(mdb_path)
    for ext in (".ldb", ".laccdb"):
        lock_path = base + ext
        if os.path.exists(lock_path):
            logging.info(
                f"AdmGlobal en uso detectado ({os.path.basename(lock_path)}). "
                "Copiando instantánea en caliente sin bloquear AdmGlobal."
            )
            return True
    return False


class SnapshotManager:
    def __init__(
        self,
        latest_dir: str,
        mdb_password: str = "toifel",
        backups_dir: str = "",
        max_backups: int = 15,
    ):
        self.latest_dir = latest_dir
        self.backups_dir = backups_dir
        self.max_backups = max_backups
        self.snapshot_name = "AdmGlobal_snapshot.mdb"
        self.snapshot_path = os.path.join(latest_dir, self.snapshot_name)
        self.mdb_password = mdb_password

    def create_snapshot(self, source_mdb: str, max_retries: int = 3) -> str:
        if not os.path.exists(source_mdb):
            raise FileNotFoundError(f"No se encontró el archivo MDB fuente: {source_mdb}")

        detect_access_lock(source_mdb)
        os.makedirs(self.latest_dir, exist_ok=True)
        tmp_path = os.path.join(self.latest_dir, f"_tmp_snapshot_{os.getpid()}.mdb")

        last_err = None
        for attempt in range(1, max_retries + 1):
            try:
                shutil.copy2(source_mdb, tmp_path)
                if os.path.getsize(tmp_path) < 4096:
                    raise RuntimeError("La copia temporal es inválida o está vacía.")

                self._validate_mdb(tmp_path)
                os.replace(tmp_path, self.snapshot_path)
                logging.info(f"Snapshot atómico validado en {self.snapshot_path} (intento {attempt})")
                return self.snapshot_path
            except Exception as e:
                last_err = e
                logging.warning(f"Intento {attempt}/{max_retries} de snapshot falló: {e}")
                if os.path.exists(tmp_path):
                    try:
                        os.remove(tmp_path)
                    except OSError:
                        pass
                if attempt < max_retries:
                    time.sleep(1.5 * attempt)

        raise RuntimeError(f"No se pudo crear snapshot íntegro tras {max_retries} intentos: {last_err}")

    def rotate_backup(self) -> None:
        """Guarda copia histórica en backups/ cuando hubo cambios reales y rota las más viejas."""
        if not self.backups_dir or not os.path.exists(self.snapshot_path):
            return
        try:
            os.makedirs(self.backups_dir, exist_ok=True)
            ts = time.strftime("%Y%m%d_%H%M%S")
            dst = os.path.join(self.backups_dir, f"AdmGlobal_{ts}.mdb")
            shutil.copy2(self.snapshot_path, dst)

            files = sorted(
                [
                    os.path.join(self.backups_dir, f)
                    for f in os.listdir(self.backups_dir)
                    if f.lower().endswith(".mdb")
                ],
                key=os.path.getmtime,
            )
            while len(files) > self.max_backups:
                oldest = files.pop(0)
                try:
                    os.remove(oldest)
                except OSError:
                    pass
        except Exception as e:
            logging.warning(f"No se pudo rotar backup local: {e}")

    def _validate_mdb(self, mdb_path: str) -> None:
        # 1. Validación de firma binaria de cabecera Jet 4.0 / ACE
        with open(mdb_path, "rb") as f:
            header = f.read(32)
        if not (header.startswith(JET_HEADER_MAGIC) or header.startswith(ACE_HEADER_MAGIC)):
            raise ValueError("El archivo no tiene una cabecera Microsoft Access (Jet/ACE) válida.")

        # 2. Si pyodbc está disponible, validamos apertura y tabla Articulos.
        #    Solo ignoramos el error si el driver ODBC 64-bit no está instalado (IM002);
        #    cualquier error de contraseña o página corrupta se propaga para reintentar.
        try:
            import pyodbc
        except ImportError:
            return

        conn_str = (
            r"DRIVER={Microsoft Access Driver (*.mdb, *.accdb)};"
            f"DBQ={mdb_path};"
            "Mode=Read;"
        )
        if self.mdb_password:
            conn_str += f"PWD={self.mdb_password};"

        try:
            conn = pyodbc.connect(conn_str, timeout=10)
            try:
                cur = conn.cursor()
                cur.execute("SELECT TOP 1 idArticulo FROM Articulos")
                cur.fetchone()
            finally:
                conn.close()
        except Exception as e:
            msg = str(e)
            if "IM002" in msg or "Data source name not found" in msg or "No se encuentra el nombre del origen de datos" in msg:
                logging.info("Driver ODBC 64-bit no instalado; la lectura validará vía Jet OLEDB 32-bit nativo.")
                return
            raise RuntimeError(f"Snapshot MDB inválido o contraseña incorrecta: {e}")
