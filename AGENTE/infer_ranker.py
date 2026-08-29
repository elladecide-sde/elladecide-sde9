"""Inferencia del neural_ranker.tflite (re-ranker context-aware).

Helper para usar desde el benchmark. Maneja inputs por nombre y contexto
variable (padding a izquierda con 0).

Uso:
  .venv-neural\Scripts\python.exe infer_ranker.py
"""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))

import tensorflow as tf

TFLITE = ROOT / "generated" / "neural_ranker.tflite"
CTX_LEN = 5
FEAT_DIM = 4
PAD_ID = 0

_interp = None
_idx = {}


def _load():
    global _interp, _idx
    if _interp is None:
        _interp = tf.lite.Interpreter(model_path=str(TFLITE))
        _interp.allocate_tensors()
        for d in _interp.get_input_details():
            nm = d["name"]
            if "ctx" in nm:
                _idx["ctx"] = d["index"]
            elif "cand" in nm:
                _idx["cand"] = d["index"]
            elif "feats" in nm:
                _idx["feats"] = d["index"]
    return _interp


def score(ctx_ids: list[int], cand_id: int, feats: list[float]) -> float:
    """P(cand | ctx, feats). feats = 4 floats del typo (ver train_ranker.typo_features)."""
    interp = _load()
    ctx = list(ctx_ids[-CTX_LEN:]) if ctx_ids else []
    ctx = [PAD_ID] * (CTX_LEN - len(ctx)) + ctx
    ctx_arr = np.array([ctx], dtype=np.int32)
    cand_arr = np.array([[int(cand_id)]], dtype=np.int32)
    feats_arr = np.array([list(feats[:FEAT_DIM]) + [0.0] * (FEAT_DIM - len(feats))], dtype=np.float32)
    interp.set_tensor(_idx["ctx"], ctx_arr)
    interp.set_tensor(_idx["cand"], cand_arr)
    interp.set_tensor(_idx["feats"], feats_arr)
    interp.invoke()
    out = interp.get_output_details()[0]
    return float(interp.get_tensor(out["index"])[0, 0])


def main() -> int:
    # validacion
    print("[input details]")
    for d in _load().get_input_details():
        print("  ", d["name"], d["shape"], d["dtype"])
    cid_casa = 1  # 'de' suele ser id 1; para probar usamos un id conocido
    # buscar id de 'casa' desde palabras_comunes
    for i, line in enumerate(open(ROOT / "data" / "palabras_comunes.txt", encoding="utf-8")):
        if line.strip() == "casa":
            cid_casa = i + 1
            break
    print(f"P(casa | ctx vacio, feats casa) = {score([], cid_casa, [0,1,1,0]):.4f}")
    print(f"P(casa | 'la', feats casa)      = {score([cid_casa - 1], cid_casa, [0,1,1,0]):.4f}")
    print(f"P(casa | 'la','casa', feats casa)= {score([cid_casa - 1, cid_casa], cid_casa, [0,1,1,0]):.4f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
