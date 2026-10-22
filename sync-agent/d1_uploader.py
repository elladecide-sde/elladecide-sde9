# ============================================================================
# d1_uploader.py — Cliente HTTP hacia el Worker de LOLA (Cloudflare D1)
# ============================================================================

from __future__ import annotations

import json
import logging
import urllib.error
import urllib.request
from typing import Any, Dict, List


class D1Uploader:
    def __init__(self, api_url: str, username: str, password: str):
        self.api_url = api_url.rstrip("/")
        self.username = username
        self.password = password

    def _post_json(self, path: str, payload: Dict[str, Any], token: str = "") -> Dict[str, Any]:
        url = f"{self.api_url}{path}"
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers = {
            "Content-Type": "application/json; charset=utf-8",
            "User-Agent": "Lola-AdmGlobal-SyncAgent/2.0",
        }
        if token:
            headers["Authorization"] = f"Bearer {token}"

        req = urllib.request.Request(url, data=data, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                raw = resp.read().decode("utf-8")
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as e:
            err_body = ""
            try:
                err_body = e.read().decode("utf-8")
            except Exception:
                pass
            raise RuntimeError(f"HTTP {e.code} en {path}: {err_body or e.reason}")
        except urllib.error.URLError as e:
            raise RuntimeError(f"Error de red conectando a {url}: {e.reason}")

    def login(self) -> str:
        if not self.api_url:
            raise ValueError("apiUrl no está configurada en settings.json")
        res = self._post_json(
            "/api/v1/auth/login",
            {"username": self.username, "password": self.password},
        )
        token = res.get("token", "")
        if not token:
            raise RuntimeError("Login exitoso pero el Worker no devolvió token JWT.")
        return token

    def upload_delta(
        self,
        upserts: List[Dict[str, Any]],
        deleted: List[str],
    ) -> Dict[str, Any]:
        """Envía solo los productos modificados/nuevos y los códigos dados de baja."""
        token = self.login()
        logging.info(
            f"Subiendo DELTA a D1: {len(upserts)} modificados/nuevos, {len(deleted)} bajas..."
        )
        return self._post_json(
            "/api/v1/admin/products",
            {
                "products": upserts,
                "deleted": deleted,
                "replace": False,
            },
            token=token,
        )

    def upload_full_replace(self, products: List[Dict[str, Any]]) -> Dict[str, Any]:
        """Envía el catálogo completo con rotación de backup en D1."""
        token = self.login()
        logging.info(f"Subiendo REEMPLAZO COMPLETO a D1 ({len(products)} artículos)...")
        return self._post_json(
            "/api/v1/admin/products",
            {
                "products": products,
                "replace": True,
            },
            token=token,
        )
