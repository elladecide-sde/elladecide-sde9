# ============================================================================
# mdb_extractor.py — Extractor directo de AdmGlobal.MDB → Modelo LOLA D1
# ============================================================================

from __future__ import annotations

import hashlib
import json
import logging
import os
import subprocess
from typing import Any, Dict, List, Optional

from codigo_unico import asignar_codigos, es_oferta, nombre_normalizado, partir


def _safe_float(val: Any, default: float = 0.0) -> float:
    if val is None or isinstance(val, (dict, list)):
        return default
    try:
        return float(val)
    except (ValueError, TypeError):
        return default


def _safe_int(val: Any, default: int = 0) -> int:
    if val is None or isinstance(val, (dict, list)):
        return default
    try:
        return int(float(val))
    except (ValueError, TypeError):
        return default


class MDBExtractor:
    def __init__(self, mdb_password: str = "toifel", marcas: Optional[List[str]] = None):
        self.mdb_password = mdb_password
        self.marcas = marcas or []

    def _fetch_via_pyodbc(self, snapshot_mdb_path: str) -> tuple[Dict[int, str], Dict[int, str], List[tuple]]:
        import pyodbc

        conn_str = (
            r"DRIVER={Microsoft Access Driver (*.mdb, *.accdb)};"
            f"DBQ={snapshot_mdb_path};"
            "Mode=Read;"
        )
        if self.mdb_password:
            conn_str += f"PWD={self.mdb_password};"

        conn = pyodbc.connect(conn_str)
        try:
            cur = conn.cursor()
            categorias_map = self._load_lookup_map(cur, "Categorias", "idCategoria", "Nombre")
            fabricantes_map = self._load_lookup_map(cur, "Fabricantes", "idFabricante", "Nombre")

            query = """
                SELECT
                    idArticulo,
                    Nombre,
                    Stock,
                    Costo,
                    Iva,
                    PrecioPublico,
                    PrecioPublico2,
                    PrecioPublico3,
                    PrecioPublico4,
                    Codigo,
                    idCategoria,
                    idFabricante,
                    UltimaActualizacion
                FROM Articulos
                WHERE Activo = 1 OR Activo = -1 OR Activo = True
            """
            cur.execute(query)
            raw_rows = [tuple(r) for r in cur.fetchall()]
            return categorias_map, fabricantes_map, raw_rows
        finally:
            conn.close()

    def _fetch_via_windows_native_jet(
        self, snapshot_mdb_path: str
    ) -> tuple[Dict[int, str], Dict[int, str], List[tuple]]:
        """
        Fallback 100% nativo de Windows: usa el proveedor `Microsoft.Jet.OLEDB.4.0`
        de 32 bits integrado de fábrica en Windows (`SysWOW64`), manejando `DBNull`
        y cargando también `Categorias` y `Fabricantes` para que el resultado sea
        100% idéntico al de `pyodbc`.
        """
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
$mdb = $env:LOLA_MDB_PATH
$pwd = $env:LOLA_MDB_PWD
$providers = @(
  "Provider=Microsoft.Jet.OLEDB.4.0;Data Source=$mdb;Mode=Read;Jet OLEDB:Database Password=$pwd;",
  "Provider=Microsoft.ACE.OLEDB.12.0;Data Source=$mdb;Mode=Read;Jet OLEDB:Database Password=$pwd;"
)
$conn = $null
foreach ($cs in $providers) {
  try {
    $c = New-Object System.Data.OleDb.OleDbConnection($cs)
    $c.Open()
    $conn = $c
    break
  } catch {}
}
if ($null -eq $conn) {
  throw "No se pudo abrir el MDB con Jet.OLEDB.4.0 ni ACE.OLEDB.12.0 (verificá la clave del MDB)"
}
function Read-Lookup($c, $sql) {
  $map = @{}
  try {
    $cmd = $c.CreateCommand()
    $cmd.CommandText = $sql
    $r = $cmd.ExecuteReader()
    while ($r.Read()) {
      if (-not $r.IsDBNull(0) -and -not $r.IsDBNull(1)) {
        $map[[string][int]$r.GetValue(0)] = ([string]$r.GetValue(1)).Trim()
      }
    }
    $r.Close()
  } catch {}
  return $map
}
function ValOrNull($r, $i) {
  if ($r.IsDBNull($i)) { return $null }
  return $r.GetValue($i)
}
try {
  $cats = Read-Lookup $conn "SELECT idCategoria, Nombre FROM Categorias"
  $fabs = Read-Lookup $conn "SELECT idFabricante, Nombre FROM Fabricantes"

  $cmd = $conn.CreateCommand()
  $cmd.CommandText = "SELECT idArticulo, Nombre, Stock, Costo, Iva, PrecioPublico, PrecioPublico2, PrecioPublico3, PrecioPublico4, Codigo, idCategoria, idFabricante, UltimaActualizacion FROM Articulos WHERE Activo = 1 OR Activo = -1 OR Activo = True"
  $rd = $cmd.ExecuteReader()
  $rows = New-Object System.Collections.Generic.List[object]
  while ($rd.Read()) {
    $rows.Add(@(
      (ValOrNull $rd 0),
      $(if ($rd.IsDBNull(1)) { "" } else { [string]$rd.GetValue(1) }),
      (ValOrNull $rd 2),
      (ValOrNull $rd 3),
      (ValOrNull $rd 4),
      (ValOrNull $rd 5),
      (ValOrNull $rd 6),
      (ValOrNull $rd 7),
      (ValOrNull $rd 8),
      $(if ($rd.IsDBNull(9)) { "" } else { [string]$rd.GetValue(9) }),
      (ValOrNull $rd 10),
      (ValOrNull $rd 11),
      $(if ($rd.IsDBNull(12)) { "" } else { [string]$rd.GetValue(12) })
    ))
  }
  $rd.Close()
  $out = @{
    categorias = $cats
    fabricantes = $fabs
    rows = $rows
  }
  ConvertTo-Json -InputObject $out -Compress -Depth 4
} finally {
  $conn.Close()
}
"""
        env = os.environ.copy()
        env["LOLA_MDB_PATH"] = os.path.abspath(snapshot_mdb_path)
        env["LOLA_MDB_PWD"] = self.mdb_password
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
            raise RuntimeError(f"Fallback nativo Jet OLEDB falló: {res.stderr.strip()}")
        payload = json.loads(res.stdout.strip() or "{}")
        cats_raw = payload.get("categorias") or {}
        fabs_raw = payload.get("fabricantes") or {}
        rows_raw = payload.get("rows") or []
        categorias_map = {_safe_int(k): str(v) for k, v in cats_raw.items() if v}
        fabricantes_map = {_safe_int(k): str(v) for k, v in fabs_raw.items() if v}
        return categorias_map, fabricantes_map, [tuple(r) for r in rows_raw]

    def _load_lookup_map(self, cursor: Any, table: str, id_col: str, name_col: str) -> Dict[int, str]:
        result: Dict[int, str] = {}
        try:
            cursor.execute(f"SELECT {id_col}, {name_col} FROM {table}")
            for row in cursor.fetchall():
                if row[0] is not None and row[1]:
                    result[_safe_int(row[0])] = str(row[1]).strip()
        except Exception:
            pass
        return result

    def extract_active_products(self, snapshot_mdb_path: str) -> List[Dict[str, Any]]:
        try:
            categorias_map, fabricantes_map, raw_rows = self._fetch_via_pyodbc(snapshot_mdb_path)
        except Exception as odbc_err:
            msg = str(odbc_err)
            if "IM002" in msg or "Data source name not found" in msg or "No se encuentra el nombre del origen de datos" in msg or isinstance(odbc_err, ImportError):
                logging.info("Usando motor Jet OLEDB 32-bit nativo de Windows...")
                categorias_map, fabricantes_map, raw_rows = self._fetch_via_windows_native_jet(snapshot_mdb_path)
            else:
                raise

        valid_rows: List[Dict[str, Any]] = []
        seen_norm: set[str] = set()

        for r in raw_rows:
            nombre = str(r[1] or "").strip()
            if len(nombre) <= 3:
                continue
            try:
                float(nombre)
                continue
            except ValueError:
                pass

            l1 = _safe_float(r[5], 0.0)
            l2 = _safe_float(r[6], 0.0)
            l3 = _safe_float(r[7], 0.0)
            l4 = _safe_float(r[8], 0.0)
            if l4 <= 0 and l1 <= 0:
                continue

            norm = nombre_normalizado(nombre)
            if not norm or norm in seen_norm:
                continue
            seen_norm.add(norm)

            stock = _safe_float(r[2], 0.0)
            costo_neto = _safe_float(r[3], 0.0)
            iva_pct = _safe_float(r[4], 21.0) if r[4] is not None and str(r[4]) != "" else 21.0
            if costo_neto <= 0 and l4 > 0:
                costo_iva = l4 / 1.50
                costo_neto = l4 / (1.50 * (1.0 + iva_pct / 100.0))
            else:
                costo_iva = costo_neto * (1.0 + iva_pct / 100.0)

            codigo_comercial = str(r[9] or "").strip() if r[9] is not None else ""
            id_cat = _safe_int(r[10], 0)
            id_fab = _safe_int(r[11], 0)
            ultima_act = r[12].isoformat() if hasattr(r[12], "isoformat") else str(r[12] or "")

            valid_rows.append({
                "id_articulo": _safe_int(r[0], 0),
                "nombre": nombre,
                "norm": norm,
                "stock": round(stock, 2),
                "costo_neto": round(costo_neto, 4),
                "costo_iva": round(costo_iva, 4),
                "l1": round(l1),
                "l2": round(l2),
                "l3": round(l3),
                "l4": round(l4),
                "codigo_comercial": codigo_comercial,
                "categoria": categorias_map.get(id_cat, ""),
                "fabrica_db": fabricantes_map.get(id_fab, ""),
                "ultima_actualizacion": ultima_act,
            })

        codigos_map, _ = asignar_codigos(
            [item["nombre"] for item in valid_rows],
            marcas=self.marcas,
        )

        productos: List[Dict[str, Any]] = []
        for item in valid_rows:
            codigo_unico = codigos_map[item["norm"]]
            oferta = es_oferta(item["nombre"])
            if oferta:
                precio_oferta = item["l4"] if item["l4"] > 0 else item["l1"]
                precios = [precio_oferta, precio_oferta, precio_oferta, precio_oferta]
            else:
                precios = [item["l1"], item["l2"], item["l3"], item["l4"]]

            marca_partes, modelo_detectado, _ = partir(item["nombre"], marcas=self.marcas)
            fabrica = item["fabrica_db"] or (" ".join(marca_partes) if marca_partes else "SIN FABRICA")
            codigo = item["codigo_comercial"] or modelo_detectado or ""

            prod_dto = {
                "codigo_unico": codigo_unico,
                "descripcion": item["nombre"],
                "costo_neto": item["costo_neto"],
                "costo_iva": item["costo_iva"],
                "precios": precios,
                "categoria": item["categoria"],
                "fabrica": fabrica,
                "codigo": codigo,
                "oferta": oferta,
                "stock": item["stock"],
            }
            row_hash = hashlib.sha256(
                json.dumps(prod_dto, sort_keys=True, ensure_ascii=False).encode("utf-8")
            ).hexdigest()

            productos.append({
                "dto": prod_dto,
                "row_hash": row_hash,
                "ultima_actualizacion": item["ultima_actualizacion"],
            })

        logging.info(f"Extraídos {len(productos)} artículos activos desde el snapshot MDB.")
        return productos
