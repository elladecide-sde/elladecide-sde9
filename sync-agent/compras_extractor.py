# ============================================================================
# compras_extractor.py — Extractor de compras bajo demanda (Targeted Sync)
# ============================================================================
# Consulta los DNIs interesados registrados en el Backend (D1) y extrae de
# AdmGlobal.mdb ÚNICAMENTE los comprobantes de esos DNIs dentro del rango
# configurado (48hs, 7 días, 1 mes o personalizado).
# Optimizado: Reutiliza una única conexión ODBC para todo el lote.
# ============================================================================

from __future__ import annotations

import json
import logging
import re
import urllib.error
import urllib.request
from datetime import datetime, timedelta
from decimal import Decimal
from typing import Any, Dict, List, Optional, Tuple

from codigo_unico import abrev, partir

logger = logging.getLogger("sync_agent.compras")


def normalizar_dni(raw: Any) -> str:
    if raw is None:
        return ""
    return re.sub(r"\D", "", str(raw)).strip()


def parsear_fecha_rango(
    rango: str = "48h",
    desde_custom: Optional[str] = None,
    hasta_custom: Optional[str] = None,
    referencia: Optional[datetime] = None,
) -> Tuple[datetime, datetime]:
    """Calcula fecha_desde y fecha_hasta en objetos datetime para consultas Access."""
    hasta = referencia or datetime.now()

    if hasta_custom:
        try:
            # Reemplazar Z y normalizar separador
            clean_hasta = hasta_custom.replace("Z", "").replace(" ", "T")
            hasta = datetime.fromisoformat(clean_hasta)
        except Exception:
            pass

    if rango == "personalizada" and desde_custom:
        try:
            clean_desde = desde_custom.replace("Z", "").replace(" ", "T")
            desde = datetime.fromisoformat(clean_desde)
        except Exception:
            desde = hasta - timedelta(hours=48)
    elif rango == "7d":
        desde = hasta - timedelta(days=7)
    elif rango == "1m":
        desde = hasta - timedelta(days=30)
    else:
        # Default 48 horas
        desde = hasta - timedelta(hours=48)

    return desde, hasta


class ComprasExtractor:
    def __init__(
        self,
        snapshot_mdb_path: str,
        backend_url: str,
        mdb_password: str = "toifel",
        timeout_sec: int = 20,
    ):
        self.snapshot_mdb_path = snapshot_mdb_path
        self.backend_url = backend_url.rstrip("/")
        self.mdb_password = mdb_password
        self.timeout_sec = timeout_sec

    def obtener_interesados(self) -> List[Dict[str, Any]]:
        """Consulta al backend los clientes interesados registrados por Ella."""
        url = f"{self.backend_url}/api/v1/compras/interesados"
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "SyncAgenteLola/1.0"})
            with urllib.request.urlopen(req, timeout=self.timeout_sec) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                interesados = data.get("interesados", [])
                validos = []
                for item in interesados:
                    dni = normalizar_dni(item.get("dni"))
                    if dni:
                        validos.append({
                            "dni": dni,
                            "rango": item.get("rango") or "48h",
                            "fecha_desde": item.get("fecha_desde"),
                            "fecha_hasta": item.get("fecha_hasta"),
                        })
                return validos
        except Exception as e:
            logger.warning(f"No se pudieron obtener interesados del backend: {e}")
            return []

    def _extraer_compras_con_cursor(
        self,
        cur: Any,
        dni: str,
        fecha_desde: datetime,
        fecha_hasta: datetime,
    ) -> List[Dict[str, Any]]:
        """Extrae compras usando un cursor ODBC ya abierto."""
        sql = """
            SELECT
                d.idDocumento,
                d.FechaDocumento,
                d.ValorAPagar,
                c.NroDoc,
                dd.Cantidad,
                dd.PrecioUnitario,
                dd.SubTotal,
                a.Nombre,
                dc.Nombre AS TipoComprobante
            FROM (((Documentos d
            INNER JOIN Clientes c ON d.idCliente = c.idCliente)
            INNER JOIN DocumentosDetalles dd ON d.idDocumento = dd.idDocumento)
            LEFT JOIN Articulos a ON dd.idArticulo = a.idArticulo)
            LEFT JOIN DocumentosConfig dc ON d.TipoDocumento = dc.idDoc
            WHERE d.DocAnulado = 0
              AND c.NroDoc = ?
              AND d.FechaDocumento >= ?
              AND d.FechaDocumento <= ?
            ORDER BY d.FechaDocumento DESC, d.idDocumento DESC
        """
        cur.execute(sql, (dni, fecha_desde, fecha_hasta))
        rows = cur.fetchall()

        compras_map: Dict[int, Dict[str, Any]] = {}
        for r in rows:
            id_doc = int(r[0])
            fecha_doc = r[1]
            fecha_iso = fecha_doc.isoformat() if isinstance(fecha_doc, datetime) else str(fecha_doc)
            total = float(r[2]) if isinstance(r[2], (Decimal, float, int)) else 0.0
            cliente_dni = normalizar_dni(r[3])
            cantidad = float(r[4]) if r[4] is not None else 1.0
            precio_u = float(r[5]) if isinstance(r[5], (Decimal, float, int)) else 0.0
            subtotal = float(r[6]) if isinstance(r[6], (Decimal, float, int)) else (cantidad * precio_u)
            nombre_art = (r[7] or "").strip()
            tipo_comprobante = (r[8] or "").strip()

            codigo_unico = ""
            if nombre_art:
                marca_toks, modelo, _ = partir(nombre_art)
                abrev_marca = abrev(marca_toks, 3)
                codigo_unico = f"{abrev_marca}{modelo}".strip()

            if id_doc not in compras_map:
                compras_map[id_doc] = {
                    "id_documento": id_doc,
                    "dni": cliente_dni,
                    "fecha": fecha_iso,
                    "tipo_doc": tipo_comprobante,
                    "total": total,
                    "items": [],
                }

            compras_map[id_doc]["items"].append({
                "codigo_unico": codigo_unico,
                "descripcion": nombre_art,
                "cantidad": cantidad,
                "precio_unitario": precio_u,
                "subtotal": subtotal,
            })

        return list(compras_map.values())

    def extraer_compras_cliente(
        self,
        dni: str,
        fecha_desde: datetime,
        fecha_hasta: datetime,
    ) -> List[Dict[str, Any]]:
        """Extrae compras no anuladas de un DNI abriendo conexión puntual."""
        if not dni:
            return []

        import pyodbc

        conn_str = (
            r"DRIVER={Microsoft Access Driver (*.mdb, *.accdb)};"
            f"DBQ={self.snapshot_mdb_path};"
            "Mode=Read;"
        )
        if self.mdb_password:
            conn_str += f"PWD={self.mdb_password};"

        conn = pyodbc.connect(conn_str)
        try:
            cur = conn.cursor()
            return self._extraer_compras_con_cursor(cur, dni, fecha_desde, fecha_hasta)
        finally:
            conn.close()

    def enviar_batch_al_backend(self, compras: List[Dict[str, Any]]) -> bool:
        """Envía el lote de compras al backend D1 usando urllib."""
        if not compras:
            return True

        url = f"{self.backend_url}/api/v1/compras/batch"
        try:
            chunk_size = 50
            for i in range(0, len(compras), chunk_size):
                chunk = compras[i : i + chunk_size]
                payload = json.dumps({"compras": chunk}).encode("utf-8")
                req = urllib.request.Request(
                    url,
                    data=payload,
                    headers={"Content-Type": "application/json; charset=utf-8", "User-Agent": "SyncAgenteLola/1.0"},
                    method="POST",
                )
                with urllib.request.urlopen(req, timeout=self.timeout_sec) as resp:
                    data = json.loads(resp.read().decode("utf-8"))
                    logger.info(f"Subidas {data.get('guardadas', 0)} compras al backend.")
            return True
        except Exception as e:
            logger.error(f"Error subiendo compras al backend: {e}")
            return False

    def ejecutar_ciclo(self) -> int:
        """Ciclo completo: pide interesados -> abre 1 conexión ODBC -> extrae -> sube a D1."""
        interesados = self.obtener_interesados()
        if not interesados:
            logger.info("No hay clientes interesados para sincronizar compras.")
            return 0

        logger.info(f"Sincronizando compras para {len(interesados)} clientes interesados...")
        todas_las_compras: List[Dict[str, Any]] = []

        import pyodbc

        conn_str = (
            r"DRIVER={Microsoft Access Driver (*.mdb, *.accdb)};"
            f"DBQ={self.snapshot_mdb_path};"
            "Mode=Read;"
        )
        if self.mdb_password:
            conn_str += f"PWD={self.mdb_password};"

        conn = pyodbc.connect(conn_str)
        try:
            cur = conn.cursor()
            for item in interesados:
                dni = item["dni"]
                rango = item.get("rango", "48h")
                desde, hasta = parsear_fecha_rango(
                    rango=rango,
                    desde_custom=item.get("fecha_desde"),
                    hasta_custom=item.get("fecha_hasta"),
                )
                compras_cli = self._extraer_compras_con_cursor(cur, dni, desde, hasta)
                logger.info(f"DNI {dni} (rango {rango}): {len(compras_cli)} compras encontradas.")
                todas_las_compras.extend(compras_cli)
        finally:
            conn.close()

        if todas_las_compras:
            self.enviar_batch_al_backend(todas_las_compras)

        return len(todas_las_compras)
