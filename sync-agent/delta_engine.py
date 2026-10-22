# ============================================================================
# delta_engine.py — Motor de Versiones y Cambios Atómicos (Deltas)
# ============================================================================

from __future__ import annotations

import hashlib
import json
import os
from datetime import datetime
from typing import Any, Dict, List


def _canonical_row_hash(dto: Dict[str, Any], fallback_stock: float = 0.0) -> str:
    normalized = dict(dto)
    if "stock" not in normalized or normalized["stock"] is None:
        normalized["stock"] = round(float(fallback_stock), 2)
    else:
        normalized["stock"] = round(float(normalized["stock"]), 2)
    return hashlib.sha256(
        json.dumps(normalized, sort_keys=True, ensure_ascii=False).encode("utf-8")
    ).hexdigest()


class DeltaResult:
    def __init__(
        self,
        mode: str,  # 'skip' | 'delta' | 'replace'
        upserts: List[Dict[str, Any]],
        deleted: List[str],
        total_active: int,
    ):
        self.mode = mode
        self.upserts = upserts
        self.deleted = deleted
        self.total_active = total_active

    @property
    def has_changes(self) -> bool:
        return self.mode != "skip"


class DeltaEngine:
    def __init__(self, state_dir: str):
        self.state_dir = state_dir
        self.state_file = os.path.join(state_dir, "catalog_state.json")

    def load_previous_state(self) -> Dict[str, Any]:
        if not os.path.exists(self.state_file):
            return {}
        try:
            with open(self.state_file, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            return {}

    def compute_diff(
        self,
        extracted_items: List[Dict[str, Any]],
        force_full: bool = False,
    ) -> DeltaResult:
        all_dtos = [item["dto"] for item in extracted_items]
        total = len(all_dtos)

        prev = self.load_previous_state()
        prev_items: Dict[str, str] = prev.get("items", {})
        prev_stocks: Dict[str, float] = prev.get("stocks", {})

        if force_full or not prev_items:
            return DeltaResult(
                mode="replace",
                upserts=all_dtos,
                deleted=[],
                total_active=total,
            )

        upserts: List[Dict[str, Any]] = []
        current_codes: set[str] = set()

        for item in extracted_items:
            dto = item["dto"]
            code = dto["codigo_unico"]
            current_codes.add(code)

            r_hash = _canonical_row_hash(dto, fallback_stock=prev_stocks.get(code, 0.0))
            item["row_hash"] = r_hash

            if prev_items.get(code) != r_hash:
                upserts.append(dto)

        deleted = [code for code in prev_items.keys() if code not in current_codes]

        if not upserts and not deleted:
            return DeltaResult(mode="skip", upserts=[], deleted=[], total_active=total)

        # Si cambió más del 40% del catálogo, conviene un replace completo con backup rotativo
        if total > 0 and (len(upserts) + len(deleted)) > total * 0.40:
            return DeltaResult(
                mode="replace",
                upserts=all_dtos,
                deleted=[],
                total_active=total,
            )

        return DeltaResult(
            mode="delta",
            upserts=upserts,
            deleted=deleted,
            total_active=total,
        )

    def save_state(
        self,
        extracted_items: List[Dict[str, Any]],
        revision: int,
        catalog_hash: str,
    ) -> None:
        os.makedirs(self.state_dir, exist_ok=True)
        prev = self.load_previous_state()
        prev_stocks: Dict[str, float] = prev.get("stocks", {})

        items_map: Dict[str, str] = {}
        stocks_map: Dict[str, float] = {}
        for item in extracted_items:
            dto = item["dto"]
            code = dto["codigo_unico"]
            st_val = dto.get("stock")
            effective_stock = round(float(st_val), 2) if st_val is not None else float(prev_stocks.get(code, 0.0))
            stocks_map[code] = effective_stock
            items_map[code] = _canonical_row_hash(dto, fallback_stock=effective_stock)

        payload = {
            "revision": revision,
            "catalog_hash": catalog_hash,
            "updated_at": datetime.now().isoformat(timespec="seconds"),
            "total_items": len(items_map),
            "items": items_map,
            "stocks": stocks_map,
        }
        tmp_path = self.state_file + ".tmp"
        with open(tmp_path, "w", encoding="utf-8") as f:
            json.dump(payload, f, indent=2, ensure_ascii=False)
        os.replace(tmp_path, self.state_file)
