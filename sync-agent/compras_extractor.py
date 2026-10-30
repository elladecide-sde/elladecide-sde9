# ============================================================================
# compras_extractor.py — Extractor de compras bajo demanda (Targeted Sync)
# ============================================================================
# Consulta los DNIs interesados registrados en el Backend (D1) y extrae de
# AdmGlobal.mdb ÚNICAMENTE los comprobantes de esos DNIs.
# ============================================================================

from __future__ import annotations

import json
import logging
import re
import urllib.error
import urllib.request
from datetime import datetime
from decimal import Decimal
from typing import Any, Dict, List, Optional

from codigo_unico import abrev, partir

logger = logging.getLogger("sync_agent.compras")


def normalizar_dni(raw: Any) -> str:
    if raw is None:
        return ""
    return re.sub(r"\D", "", str(raw)).strip()


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

    def obtener_dnis_interesados(self) -> List[str]:
        """Consulta al backend los DNIs registrados por la app de Ella."""
        url = f"{self.backend_url}/api/v1/compras/interesados"
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "SyncAgenteLola/1.0"})
            with urllib.request.urlopen(req, timeout=self.timeout_sec) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                interesados = data.get("interesados", [])
                dnis = [normalizar_dni(item.get("dni")) for item in interesados]
                return [d for d in dnis if d]
        except Exception as e:
            logger.warning(f"No se pudieron obtener DNIs interesados del backend: {e}")
            return []

    def extraer_compras_dnis(self, dnis: List[str], max_meses: int = 12) -> List[Dict[str, Any]]:
        """Extrae compras no anuladas de los DNIs dados desde AdmGlobal.mdb."""
        if not dnis:
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

            # Preparamos los placeholders para los DNIs
            placeholders = ",".join(["?"] * len(dnis))
            sql = f"""
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
                  AND c.NroDoc IN ({placeholders})
                ORDER BY d.FechaDocumento DESC, d.idDocumento DESC
            """
            cur.execute(sql, *dnis)
            rows = cur.fetchall()

            # Agrupamos los detalles por documento
            compras_map: Dict[int, Dict[str, Any]] = {}
            for r in rows:
                id_doc = int(r[0])
                fecha_doc = r[1]
                fecha_iso = fecha_doc.isoformat() if isinstance(fecha_doc, datetime) else str(fecha_doc)
                total = float(r[2]) if isinstance(r[2], (Decimal, float, int)) else 0.0
                dni = normalizar_dni(r[3])
                cantidad = float(r[4]) if r[4] is not None else 1.0
                precio_u = float(r[5]) if isinstance(r[5], (Decimal, float, int)) else 0.0
                subtotal = float(r[6]) if isinstance(r[6], (Decimal, float, int)) else (cantidad * precio_u)
                nombre_art = (r[7] or "").strip()
                tipo_comprobante = (r[8] or "").strip()

                # Derivar codigo_unico para el artículo comprado
                codigo_unico = ""
                if nombre_art:
                    marca_toks, modelo, _ = partir(nombre_art)
                    abrev_marca = abrev(marca_toks, 3)
                    codigo_unico = f"{abrev_marca}{modelo}".strip()

                if id_doc not in compras_map:
                    compras_map[id_doc] = {
                        "id_documento": id_doc,
                        "dni": dni,
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
        finally:
            conn.close()

    def enviar_batch_al_backend(self, compras: List[Dict[str, Any]]) -> bool:
        """Envía el lote de compras al backend D1 usando urllib (cero dependencias externas)."""
        if not compras:
            return True

        url = f"{self.backend_url}/api/v1/compras/batch"
        try:
            chunk_size = 100
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
        """Ciclo completo: pide DNIs -> extrae MDB -> sube a D1."""
        dnis = self.obtener_dnis_interesados()
        if not dnis:
            logger.info("No hay DNIs interesados para sincronizar.")
            return 0

        logger.info(f"Sincronizando compras para {len(dnis)} DNIs interesados...")
        compras = self.extraer_compras_dnis(dnis)
        logger.info(f"Se extrajeron {len(compras)} compras en total.")

        if compras:
            self.enviar_batch_al_backend(compras)
        return len(compras)
