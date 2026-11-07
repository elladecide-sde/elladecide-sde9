# ============================================================================
# compras_extractor.py — Extractor de compras bajo demanda (Targeted Sync v2)
# ============================================================================
# Consulta los DNIs interesados registrados en el Backend (D1) y extrae de
# AdmGlobal.mdb los comprobantes enriquecidos de esos DNIs dentro del rango
# configurado (48hs, 7 días, 1 mes o personalizado):
#   · Cliente (nombre), localidad, sucursal, número de comprobante legible y moneda
#   · Descuento, % descuento, forma de pago, IVA por ítem
#   · Estado anulado y referencia nota_credito_de (idDocAsociado)
#   · Fábrica y código separados por ítem para matchear 1:1 con el catálogo
#   · Verificación de existencia del DNI en AdmGlobal (existe_en_mdb)
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

TIPOS_NOTA_CREDITO = {7, 11, 12, 22, 23, 24, 28}


def normalizar_dni(raw: Any) -> str:
    if raw is None:
        return ""
    return re.sub(r"\D", "", str(raw)).strip()


def _safe_float(val: Any, default: float = 0.0) -> float:
    if val is None:
        return default
    if isinstance(val, (Decimal, float, int)):
        return float(val)
    try:
        return float(str(val).replace(",", "."))
    except Exception:
        return default


def inferir_forma_pago(pago_efectivo: float, pago_tarjeta: float, cuotas: int = 0) -> str:
    ef = pago_efectivo > 0
    tj = pago_tarjeta > 0
    if ef and tj:
        return f"MIXTO ({cuotas} cuotas)" if cuotas > 1 else "MIXTO"
    if tj:
        return f"TARJETA ({cuotas} cuotas)" if cuotas > 1 else "TARJETA"
    if ef:
        return "EFECTIVO"
    return "CTA_CTE"


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
            clean_hasta = hasta_custom.strip().replace("Z", "").replace(" ", "T")
            hasta = datetime.fromisoformat(clean_hasta)
        except Exception:
            pass

    if (rango == "personalizada" or desde_custom) and desde_custom:
        try:
            clean_desde = desde_custom.strip().replace("Z", "").replace(" ", "T")
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
        token: str = "",
        mdb_password: str = "toifel",
        timeout_sec: int = 20,
    ):
        self.snapshot_mdb_path = snapshot_mdb_path
        self.backend_url = backend_url.rstrip("/")
        self.token = token
        self.mdb_password = mdb_password
        self.timeout_sec = timeout_sec

    def _headers(self) -> Dict[str, str]:
        h = {
            "Content-Type": "application/json; charset=utf-8",
            "User-Agent": "SyncAgenteLola/2.0",
        }
        if self.token:
            h["Authorization"] = f"Bearer {self.token}"
        return h

    def obtener_interesados(self) -> List[Dict[str, Any]]:
        """Consulta al backend los clientes interesados registrados por Ella o Admin."""
        url = f"{self.backend_url}/api/v1/compras/interesados"
        try:
            req = urllib.request.Request(url, headers=self._headers())
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

    def _cargar_mapa_fabricantes(self, cur: Any) -> Dict[int, str]:
        mapa: Dict[int, str] = {}
        try:
            cur.execute("SELECT idFabricante, Nombre FROM Fabricantes")
            for row in cur.fetchall():
                if row[0] is not None and row[1]:
                    mapa[int(row[0])] = str(row[1]).strip()
        except Exception:
            pass
        return mapa

    def _verificar_cliente_en_mdb(self, cur: Any, dni: str) -> Tuple[bool, str]:
        """Verifica si el DNI/CUIT existe en la tabla Clientes de AdmGlobal.MDB."""
        try:
            sql = """
                SELECT TOP 1 Nom1, Nom2, Localidad
                FROM Clientes
                WHERE NroDoc = ? OR CUIT = ?
            """
            cur.execute(sql, (dni, dni))
            row = cur.fetchone()
            if row:
                nom1 = str(row[0] or "").strip()
                nom2 = str(row[1] or "").strip()
                nombre = f"{nom1} {nom2}".strip()
                return True, nombre
        except Exception:
            pass
        return False, ""

    def _extraer_compras_con_cursor(
        self,
        cur: Any,
        dni: str,
        fecha_desde: datetime,
        fecha_hasta: datetime,
        fabricantes_map: Optional[Dict[int, str]] = None,
    ) -> List[Dict[str, Any]]:
        """Extrae compras (incluyendo anuladas y notas de crédito) usando un cursor ODBC abierto."""
        fab_map = fabricantes_map or {}
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
                dc.Nombre AS TipoComprobante,
                c.CUIT,
                d.PrefijoDocumento,
                d.NroDocumento,
                c.Nom1,
                c.Nom2,
                d.NombreClienteEventual,
                c.Localidad,
                d.idCaja,
                d.idEmpresa,
                d.Descuento,
                d.PorcDescuento,
                d.PagoEfectivo,
                d.PagoTarjeta,
                d.CuotasTarjeta,
                d.DocAnulado,
                d.TipoDocumento,
                d.idDocAsociado,
                dd.Iva,
                a.Iva,
                a.Codigo,
                a.idFabricante
            FROM (((Documentos d
            INNER JOIN Clientes c ON d.idCliente = c.idCliente)
            INNER JOIN DocumentosDetalles dd ON d.idDocumento = dd.idDocumento)
            LEFT JOIN Articulos a ON dd.idArticulo = a.idArticulo)
            LEFT JOIN DocumentosConfig dc ON d.TipoDocumento = dc.idDoc
            WHERE (c.NroDoc = ? OR c.CUIT = ?)
              AND d.FechaDocumento >= ?
              AND d.FechaDocumento <= ?
            ORDER BY d.FechaDocumento DESC, d.idDocumento DESC
        """
        cur.execute(sql, (dni, dni, fecha_desde, fecha_hasta))
        rows = cur.fetchall()

        compras_map: Dict[int, Dict[str, Any]] = {}
        for r in rows:
            id_doc = int(r[0])
            fecha_doc = r[1]
            fecha_iso = fecha_doc.isoformat() if isinstance(fecha_doc, datetime) else str(fecha_doc)
            total = _safe_float(r[2])
            cantidad = _safe_float(r[4], 1.0)
            precio_u = _safe_float(r[5], 0.0)
            subtotal = _safe_float(r[6], cantidad * precio_u)
            nombre_art = str(r[7] or "").strip()
            tipo_comprobante = str(r[8] or "").strip()

            prefijo_doc = str(r[10] or "").strip() if len(r) > 10 else ""
            nro_doc_comp = str(r[11] or "").strip() if len(r) > 11 else ""
            numero_comprobante = (
                f"{prefijo_doc}-{nro_doc_comp}"
                if prefijo_doc and nro_doc_comp
                else (nro_doc_comp or str(id_doc))
            )

            nom1 = str(r[12] or "").strip() if len(r) > 12 else ""
            nom2 = str(r[13] or "").strip() if len(r) > 13 else ""
            nom_eventual = str(r[14] or "").strip() if len(r) > 14 else ""
            cliente_nombre = f"{nom1} {nom2}".strip() or nom_eventual

            localidad = str(r[15] or "").strip() if len(r) > 15 else ""
            id_caja = int(r[16]) if len(r) > 16 and r[16] is not None else 1
            id_empresa = int(r[17]) if len(r) > 17 and r[17] is not None else 1
            sucursal = f"Sucursal {id_empresa} - Caja {id_caja}"

            descuento_doc = _safe_float(r[18] if len(r) > 18 else 0.0)
            porc_descuento = _safe_float(r[19] if len(r) > 19 else 0.0)

            pago_ef = _safe_float(r[20] if len(r) > 20 else 0.0)
            pago_tj = _safe_float(r[21] if len(r) > 21 else 0.0)
            cuotas_tj = int(r[22]) if len(r) > 22 and r[22] is not None else 0
            forma_pago = inferir_forma_pago(pago_ef, pago_tj, cuotas_tj)

            doc_anulado = bool(r[23]) if len(r) > 23 and r[23] is not None else False
            tipo_doc_id = int(r[24]) if len(r) > 24 and r[24] is not None else 0
            id_doc_asociado = int(r[25]) if len(r) > 25 and r[25] is not None else 0
            nota_credito_de: Optional[int] = None
            if id_doc_asociado > 0:
                nota_credito_de = id_doc_asociado
            elif tipo_doc_id in TIPOS_NOTA_CREDITO:
                nota_credito_de = 0

            iva_item = _safe_float(
                r[26] if len(r) > 26 and r[26] is not None else (r[27] if len(r) > 27 else 21.0),
                21.0,
            )
            cod_articulo_mdb = str(r[28] or "").strip() if len(r) > 28 else ""
            id_fab = int(r[29]) if len(r) > 29 and r[29] is not None else 0

            codigo_unico = ""
            fabrica_item = fab_map.get(id_fab, "")
            codigo_item = cod_articulo_mdb

            if nombre_art:
                marca_toks, modelo, _ = partir(nombre_art)
                abrev_marca = abrev(marca_toks, 3)
                codigo_unico = f"{abrev_marca}{modelo}".strip()
                if not fabrica_item and marca_toks:
                    fabrica_item = " ".join(marca_toks).upper()
                if not codigo_item and modelo:
                    codigo_item = modelo

            if id_doc not in compras_map:
                compras_map[id_doc] = {
                    "id_documento": id_doc,
                    "dni": dni,
                    "fecha": fecha_iso,
                    "tipo_doc": tipo_comprobante,
                    "numero_comprobante": numero_comprobante,
                    "cliente": cliente_nombre,
                    "localidad": localidad,
                    "sucursal": sucursal,
                    "moneda": "ARS",
                    "descuento": descuento_doc,
                    "porc_descuento": porc_descuento,
                    "forma_pago": forma_pago,
                    "anulado": doc_anulado,
                    "nota_credito_de": nota_credito_de,
                    "total": total,
                    "items": [],
                }

            compras_map[id_doc]["items"].append({
                "codigo_unico": codigo_unico,
                "fabrica": fabrica_item or "SIN FABRICA",
                "codigo": codigo_item,
                "descripcion": nombre_art,
                "cantidad": cantidad,
                "precio_unitario": precio_u,
                "subtotal": subtotal,
                "iva": iva_item,
                "descuento": round(subtotal * (porc_descuento / 100.0), 2) if porc_descuento > 0 else 0.0,
            })

        return list(compras_map.values())

    def extraer_compras_cliente(
        self,
        dni: str,
        fecha_desde: datetime,
        fecha_hasta: datetime,
    ) -> List[Dict[str, Any]]:
        """Extrae compras de un DNI abriendo conexión puntual."""
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
            fab_map = self._cargar_mapa_fabricantes(cur)
            return self._extraer_compras_con_cursor(cur, dni, fecha_desde, fecha_hasta, fab_map)
        finally:
            conn.close()

    def enviar_batch_al_backend(
        self,
        compras: List[Dict[str, Any]],
        dnis_sincronizados: Optional[List[str]] = None,
        clientes_meta: Optional[List[Dict[str, Any]]] = None,
    ) -> bool:
        """Envía el lote de compras y metadata de clientes al backend D1 usando urllib con Bearer JWT."""
        url = f"{self.backend_url}/api/v1/compras/batch"
        try:
            if not compras:
                if dnis_sincronizados or clientes_meta:
                    payload = json.dumps({
                        "compras": [],
                        "dnis_sincronizados": dnis_sincronizados or [],
                        "clientes_meta": clientes_meta or [],
                    }).encode("utf-8")
                    req = urllib.request.Request(
                        url,
                        data=payload,
                        headers=self._headers(),
                        method="POST",
                    )
                    with urllib.request.urlopen(req, timeout=self.timeout_sec) as resp:
                        logger.info(
                            f"Actualizada última sincronización para {len(dnis_sincronizados or [])} clientes."
                        )
                return True

            chunk_size = 50
            for i in range(0, len(compras), chunk_size):
                chunk = compras[i : i + chunk_size]
                body_data: Dict[str, Any] = {"compras": chunk}
                if i == 0:
                    if dnis_sincronizados:
                        body_data["dnis_sincronizados"] = dnis_sincronizados
                    if clientes_meta:
                        body_data["clientes_meta"] = clientes_meta

                payload = json.dumps(body_data).encode("utf-8")
                req = urllib.request.Request(
                    url,
                    data=payload,
                    headers=self._headers(),
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
        dnis_revisados = [item["dni"] for item in interesados if item.get("dni")]
        clientes_meta: List[Dict[str, Any]] = []

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
            fab_map = self._cargar_mapa_fabricantes(cur)
            for item in interesados:
                dni = item["dni"]
                rango = item.get("rango", "48h")
                existe, nombre_cli = self._verificar_cliente_en_mdb(cur, dni)
                desde, hasta = parsear_fecha_rango(
                    rango=rango,
                    desde_custom=item.get("fecha_desde"),
                    hasta_custom=item.get("fecha_hasta"),
                )
                compras_cli = self._extraer_compras_con_cursor(cur, dni, desde, hasta, fab_map)
                if compras_cli and not existe:
                    existe = True
                    nombre_cli = compras_cli[0].get("cliente", "")
                clientes_meta.append({
                    "dni": dni,
                    "existe_en_mdb": existe,
                    "nombre_cliente": nombre_cli,
                })
                logger.info(
                    f"DNI {dni} (rango {rango}, existe={existe}): {len(compras_cli)} compras encontradas."
                )
                todas_las_compras.extend(compras_cli)
        finally:
            conn.close()

        # Notifica al backend (tanto si hay compras como si solo se actualiza last_synced_at / existe_en_mdb)
        self.enviar_batch_al_backend(
            todas_las_compras,
            dnis_sincronizados=dnis_revisados,
            clientes_meta=clientes_meta,
        )

        return len(todas_las_compras)
