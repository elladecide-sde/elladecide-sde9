# ============================================================================
# excel_extractor.py — Extractor Manual de Excel (Lista 4) → Modelo LOLA D1
# ============================================================================

from __future__ import annotations

import hashlib
import json
import logging
import os
import xml.etree.ElementTree as ET
import zipfile
from typing import Any, Dict, List, Optional, Tuple

from codigo_unico import asignar_codigos, es_oferta, nombre_normalizado, partir

LISTA_FACTORS = (2.32, 2.12, 1.65, 1.50)
IVA_DEFAULT = 21.0
INVERSA_IVA_DEFAULT = 21.0
INVERSA_LISTA_DEFAULT = 50.0


def calcular_costos_l4(
    precio_l4: float,
    iva: float = IVA_DEFAULT,
    inversa_iva: float = INVERSA_IVA_DEFAULT,
    inversa_lista: float = INVERSA_LISTA_DEFAULT,
    factores: Tuple[float, ...] = LISTA_FACTORS,
) -> Tuple[float, float, List[int]]:
    """Fórmula espejo bit a bit de `calcularCostos` en backend/src/modelo.ts."""
    iva_f = 1.0 + iva / 100.0
    inversa_iva_f = 1.0 + inversa_iva / 100.0
    inversa_lista_f = 1.0 + inversa_lista / 100.0

    costo_iva = precio_l4 / inversa_lista_f
    costo_neto = precio_l4 / (inversa_iva_f * inversa_lista_f)
    precios = [int(round(costo_neto * iva_f * f)) for f in factores]
    return costo_neto, costo_iva, precios


class ExcelExtractor:
    def __init__(
        self,
        col_nombre: int = 1,
        col_precio: int = 2,
        skip_rows: int = 0,
        marcas: Optional[List[str]] = None,
    ):
        self.col_nombre = col_nombre
        self.col_precio = col_precio
        self.skip_rows = skip_rows
        self.marcas = marcas or []

    def _read_xlsx_stdlib(self, path: str) -> List[List[Any]]:
        """Lector nativo de .xlsx usando solo librería estándar (zipfile + XML)."""
        ns = {"main": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
        with zipfile.ZipFile(path, "r") as z:
            shared_strings: List[str] = []
            if "xl/sharedStrings.xml" in z.namelist():
                root_ss = ET.fromstring(z.read("xl/sharedStrings.xml"))
                for si in root_ss.findall("main:si", ns):
                    texts = [t.text or "" for t in si.findall(".//main:t", ns)]
                    shared_strings.append("".join(texts))

            sheet_files = sorted(
                n for n in z.namelist() if n.startswith("xl/worksheets/sheet") and n.endswith(".xml")
            )
            if not sheet_files:
                raise RuntimeError("El archivo .xlsx no contiene hojas de cálculo.")

            root_sh = ET.fromstring(z.read(sheet_files[0]))
            rows_out: List[List[Any]] = []

            def col_to_idx(cell_ref: str) -> int:
                letters = "".join(ch for ch in cell_ref if ch.isalpha()).upper()
                idx = 0
                for ch in letters:
                    idx = idx * 26 + (ord(ch) - ord("A") + 1)
                return max(0, idx - 1)

            for row_el in root_sh.findall(".//main:sheetData/main:row", ns):
                cells_map: Dict[int, Any] = {}
                max_c = 0
                for c_el in row_el.findall("main:c", ns):
                    ref = c_el.attrib.get("r", "A1")
                    c_idx = col_to_idx(ref)
                    max_c = max(max_c, c_idx)
                    c_type = c_el.attrib.get("t", "")
                    v_el = c_el.find("main:v", ns)
                    is_el = c_el.find("main:is", ns)
                    val: Any = ""
                    if c_type == "s" and v_el is not None and v_el.text is not None:
                        s_idx = int(v_el.text)
                        val = shared_strings[s_idx] if 0 <= s_idx < len(shared_strings) else ""
                    elif c_type == "inlineStr" and is_el is not None:
                        val = "".join(t.text or "" for t in is_el.findall(".//main:t", ns))
                    elif v_el is not None and v_el.text is not None:
                        val = v_el.text
                    cells_map[c_idx] = val
                row_list = [cells_map.get(i, "") for i in range(max_c + 1)]
                rows_out.append(row_list)
            return rows_out

    def _read_rows(self, excel_path: str) -> List[List[Any]]:
        ext = os.path.splitext(excel_path)[1].lower()

        if ext == ".xls":
            try:
                import xlrd  # type: ignore

                wb = xlrd.open_workbook(excel_path)
                sh = wb.sheet_by_index(0)
                return [[sh.cell_value(r, c) for c in range(sh.ncols)] for r in range(sh.nrows)]
            except ImportError:
                pass

        if ext in (".xlsx", ".xlsm"):
            try:
                return self._read_xlsx_stdlib(excel_path)
            except Exception as e:
                logging.warning(f"Lector stdlib xlsx falló ({e}), intentando vía ODBC...")

        # Fallback en Windows vía Driver ODBC de Excel (64-bit) o Jet OLEDB (32-bit)
        try:
            import pyodbc

            conn_str = (
                r"DRIVER={Microsoft Excel Driver (*.xls, *.xlsx, *.xlsm, *.xlsb)};"
                f"DBQ={os.path.abspath(excel_path)};"
                "ReadOnly=1;HDR=NO;"
            )
            conn = pyodbc.connect(conn_str)
            try:
                cur = conn.cursor()
                tables = [row.table_name for row in cur.tables()]
                if not tables:
                    raise RuntimeError("No se encontraron hojas en el archivo Excel.")
                sheet = tables[0]
                cur.execute(f"SELECT * FROM [{sheet}]")
                return [list(r) for r in cur.fetchall()]
            finally:
                conn.close()
        except Exception:
            import subprocess

            ps_x86 = os.path.join(
                os.environ.get("SystemRoot", r"C:\Windows"),
                "SysWOW64",
                "WindowsPowerShell",
                "v1.0",
                "powershell.exe",
            )
            if not os.path.exists(ps_x86):
                ps_x86 = "powershell.exe"
            ps_script = r"""
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$xls = $env:LOLA_XLS_PATH
$cs = "Provider=Microsoft.Jet.OLEDB.4.0;Data Source=$xls;Extended Properties='Excel 8.0;HDR=NO;IMEX=1';"
$conn = New-Object System.Data.OleDb.OleDbConnection($cs)
$conn.Open()
try {
  $schema = $conn.GetOleDbSchemaTable([System.Data.OleDb.OleDbSchemaGuid]::Tables, $null)
  $sheet = $schema.Rows[0]["TABLE_NAME"]
  $cmd = $conn.CreateCommand()
  $cmd.CommandText = "SELECT * FROM [$sheet]"
  $rd = $cmd.ExecuteReader()
  $rows = New-Object System.Collections.Generic.List[object]
  while ($rd.Read()) {
    $r = New-Object System.Collections.Generic.List[object]
    for ($i = 0; $i -lt $rd.FieldCount; $i++) {
      $r.Add($(if ($rd.IsDBNull($i)) { "" } else { [string]$rd.GetValue($i) }))
    }
    $rows.Add($r)
  }
  $rd.Close()
  ConvertTo-Json -InputObject $rows -Compress -Depth 4
} finally {
  $conn.Close()
}
"""
            env = os.environ.copy()
            env["LOLA_XLS_PATH"] = os.path.abspath(excel_path)
            creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
            res = subprocess.run(
                [ps_x86, "-NoProfile", "-NonInteractive", "-Command", ps_script],
                capture_output=True,
                text=True,
                encoding="utf-8",
                env=env,
                creationflags=creationflags,
            )
            if res.returncode != 0:
                raise RuntimeError(f"No se pudo leer el archivo Excel ({excel_path}): {res.stderr.strip()}")
            return json.loads(res.stdout.strip() or "[]")

    def extract_products(self, excel_path: str) -> List[Dict[str, Any]]:
        if not os.path.exists(excel_path):
            raise FileNotFoundError(f"No existe el archivo Excel: {excel_path}")

        rows = self._read_rows(excel_path)
        valid_rows: List[Dict[str, Any]] = []
        seen_norm: set[str] = set()

        for i in range(self.skip_rows, len(rows)):
            row = rows[i]
            if len(row) <= max(self.col_nombre, self.col_precio):
                continue

            raw_nombre = row[self.col_nombre]
            if raw_nombre is None:
                continue
            nombre = str(raw_nombre).strip()
            if len(nombre) <= 3 or nombre.lower() == "none":
                continue
            try:
                float(nombre)
                continue
            except ValueError:
                pass

            raw_precio = row[self.col_precio]
            try:
                precio_l4 = float(str(raw_precio).replace(",", ".").strip())
            except (ValueError, TypeError):
                continue
            if precio_l4 <= 0:
                continue

            norm = nombre_normalizado(nombre)
            if not norm or norm in seen_norm:
                continue
            seen_norm.add(norm)

            valid_rows.append({
                "nombre": nombre,
                "norm": norm,
                "precio_l4": precio_l4,
            })

        codigos_map, _ = asignar_codigos(
            [item["nombre"] for item in valid_rows],
            marcas=self.marcas,
        )

        productos: List[Dict[str, Any]] = []
        for item in valid_rows:
            codigo_unico = codigos_map[item["norm"]]
            costo_neto, costo_iva, precios_calc = calcular_costos_l4(item["precio_l4"])
            oferta = es_oferta(item["nombre"])
            precios = [int(round(item["precio_l4"]))] * 4 if oferta else precios_calc

            marca_partes, modelo_detectado, _ = partir(item["nombre"], marcas=self.marcas)
            fabrica = " ".join(marca_partes) if marca_partes else "SIN FABRICA"

            prod_dto = {
                "codigo_unico": codigo_unico,
                "descripcion": item["nombre"],
                "costo_neto": round(costo_neto, 4),
                "costo_iva": round(costo_iva, 4),
                "precios": precios,
                "categoria": "",
                "fabrica": fabrica,
                "codigo": modelo_detectado or "",
                "oferta": oferta,
            }
            row_hash = hashlib.sha256(
                json.dumps(prod_dto, sort_keys=True, ensure_ascii=False).encode("utf-8")
            ).hexdigest()

            productos.append({
                "dto": prod_dto,
                "row_hash": row_hash,
                "ultima_actualizacion": "",
            })

        logging.info(f"Extraídos {len(productos)} artículos desde Excel Lista 4 ({excel_path}).")
        return productos
