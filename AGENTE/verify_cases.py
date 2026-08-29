"""Verifica los 3 casos objetivo end-to-end usando el modelo h5 directo
(evita el problema del Flex delegate del interprete TFLite liviano, que es
un tema de runtime de inferencia, no del modelo en si)."""
from __future__ import annotations
import sys
from pathlib import Path
ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))

import numpy as np
import tensorflow as tf

from train_ranker import build_vocab, pad_ctx, typo_features, CTX_LEN
from integrator import make_corrector

word_to_id, oov_id = build_vocab()
model = tf.keras.models.load_model(str(ROOT / "generated" / "neural_ranker.h5"), safe_mode=False)


def score(ctx_ids, cand_id, feats):
    ctx = pad_ctx(ctx_ids)
    ctx_arr = np.array([ctx], dtype=np.int32)
    cand_arr = np.array([[int(cand_id)]], dtype=np.int32)
    feats_arr = np.array([feats], dtype=np.float32)
    out = model.predict([ctx_arr, cand_arr, feats_arr], verbose=0)
    return float(out[0, 0])


def neural_rerank_sim(query, cands, context_words, boost=0.6):
    """Usa la MISMA logica que integrator._neural_rerank, pero con `score`
    apuntando al modelo .h5 (evita el problema del interprete TFLite liviano
    sin Flex delegate). Reimplementamos el cuerpo en vez de monkeypatchear
    infer_ranker para no arrastrar el import perezoso de TF-Lite."""
    if not context_words:
        return cands
    ctx_ids = [word_to_id.get(w.lower(), oov_id) for w in context_words][-CTX_LEN:]
    best = min(c.total_cost() for c in cands)
    EPS = 0.15
    scored = []
    for c in cands:
        cid = word_to_id.get(c.word, oov_id)
        feats = typo_features(query, c.word)
        ed_norm = feats[0]
        s = score(ctx_ids, cid, feats)
        scored.append((c, s, c.total_cost(), ed_norm, cid == oov_id))
    tier = [x for x in scored if x[2] <= best + EPS]
    rest = [x for x in scored if x[2] > best + EPS]
    max_log_freq = max((np.log1p(x[0].frequency) for x in tier), default=0.0) or 1.0
    W_NEURAL, W_TYPO, W_FREQ = 0.45, 0.30, 0.25

    def _final(x):
        c, s, _, ed_norm, is_oov = x
        typo_score = 1.0 - ed_norm
        score_neuronal = 0.0 if is_oov else boost * s
        freq_score = np.log1p(c.frequency) / max_log_freq
        return score_neuronal * W_NEURAL + typo_score * W_TYPO + freq_score * W_FREQ

    tier.sort(key=lambda x: (_final(x), -x[2]), reverse=True)
    rest.sort(key=lambda x: x[2])
    return [c for c, *_ in tier] + [c for c, *_ in rest]


CASES = [
    ("ouedo", "puedo", ["quiero"]),
    ("xausqr", "causar", ["quiero"]),
    ("juefos", "juegos", ["quiero"]),
]

corrector = make_corrector()
print(f"{'typo':10} {'target':10} {'ctx=0':10} {'ctx>=1':10}")
for typo, target, ctx in CASES:
    cands0 = corrector.correct(typo)
    top0 = cands0[0].word if cands0 else "?"

    cands1 = corrector.correct(typo)
    reranked = neural_rerank_sim(typo, cands1, ctx)
    top1 = reranked[0].word if reranked else "?"

    ok0 = "OK" if top0 == target else f"FALLA({top0})"
    ok1 = "OK" if top1 == target else f"FALLA({top1})"
    print(f"{typo:10} {target:10} {ok0:10} {ok1:10}")
