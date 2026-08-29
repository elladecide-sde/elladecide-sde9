"""Benchmark context-aware: baseline (fuzzy) vs neural_ranker con contexto real.

A diferencia de benchmark_booster.py (que usaba contexto NEUTRO), aca cada caso
se extrae de una oracion real de es.sentences.txt:
  - se elige una palabra objetivo (conocida, larga) y se la corrompe con
    generate_typo (mismo metodo del dataset).
  - el CONTEXTO son las palabras previas REALES de la oracion (a menudo 1 o 2),
    mapeadas a ids (padding a izquierda si hace falta).
  - se corrige con el corrector fuzzy y se compara:
      * baseline: top-1 de correct_word
      * re-ranker: candidatos re-ordenados por P(cand | ctx) del neural_ranker.

Uso:
  .venv-neural\Scripts\python.exe benchmark_ranker.py --n 300
"""

from __future__ import annotations

import argparse
import random
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))

import numpy as np
from train_ranker import build_vocab, VOCAB_TOP, CTX_LEN, PAD_ID
from gen_typos import generate_typo, QWERTY_ES
from integrator import make_corrector, correct_with_context

SENTENCES = ROOT / "generated" / "corpus" / "es.sentences.txt"
TOKEN_RE = re.compile(r"[a-záéíóúñüA-ZÁÉÍÓÚÑÜ]+")
OOB_ID = VOCAB_TOP + 1  # id de OOV usado en train_ranker


def tok(line: str) -> list[str]:
    return TOKEN_RE.findall(line.lower())


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=300)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--max-lines", type=int, default=40000)
    args = ap.parse_args()

    word_to_id, oov_id = build_vocab()
    corrector = make_corrector()
    rng = random.Random(args.seed)

    # recolectar casos (target + contexto previo REAL como palabras)
    cases = []
    lines_read = 0
    with SENTENCES.open("r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            lines_read += 1
            if lines_read > args.max_lines:
                break
            words = tok(line)
            ids = [word_to_id.get(w, oov_id) for w in words]
            for i in range(len(words)):
                if ids[i] == oov_id:
                    continue
                if len(words[i]) < 3:
                    continue
                prev_words = words[max(0, i - CTX_LEN):i]   # contexto real, variable
                cases.append((words[i], prev_words))
    rng.shuffle(cases)
    cases = cases[: args.n * 5]  # sobreamos para filtrar los que fallen

    b_top1 = x_top1 = 0
    used = 0
    ctx_len_hist = {}
    b_corr = {}
    x_corr = {}
    failures = []
    for target, prev_words in cases:
        if used >= args.n:
            break
        miss = generate_typo(target, rng)
        if miss == target:
            continue
        cands = corrector.correct(miss)
        if not cands:
            continue
        used += 1
        clen = len(prev_words)
        ctx_len_hist[clen] = ctx_len_hist.get(clen, 0) + 1
        b_corr.setdefault(clen, 0)
        x_corr.setdefault(clen, 0)

        base_top = cands[0].word
        if base_top == target:
            b_top1 += 1
            b_corr[clen] += 1

        # re-rank A TRAVES DEL PIPELINE INTEGRADO (integrator.correct_with_context)
        re_cands = correct_with_context(miss, prev_words)
        re_top = re_cands[0].word if re_cands else None

        if re_top == target:
            x_top1 += 1
            x_corr[clen] += 1
        elif re_top != base_top:
            # falla distinta a la del baseline -> la neurona cambio algo
            failures.append((miss, target, base_top, re_top, clen))

    total = used
    print("=" * 64)
    print("BENCHMARK CONTEXT-AWARE: baseline vs neural_ranker (pipeline integrado)")
    print("=" * 64)
    print(f"Casos evaluados         : {total}")
    print(f"Baseline Top-1          : {b_top1}/{total} = {b_top1/total*100:.1f}%")
    print(f"Re-ranker Top-1         : {x_top1}/{total} = {x_top1/total*100:.1f}%")
    print(f"Delta                   : {(x_top1-b_top1)/total*100:+.1f} pp")
    print("Desglose por largo de contexto (Base / Re-rank):")
    for k in sorted(ctx_len_hist):
        bk = b_corr.get(k, 0)
        xk = x_corr.get(k, 0)
        nk = ctx_len_hist[k]
        bp = bk / nk * 100 if nk else 0
        xp = xk / nk * 100 if nk else 0
        print(f"   ctx={k}: n={nk:3d}  base={bp:5.1f}%  rerank={xp:5.1f}%  ({xp-bp:+.1f}pp)")
    print("-" * 64)
    print(f"Ejemplos de falla del re-ranker (entrada | esperado | base | rerank | ctx):")
    for ex in failures[:15]:
        print(f"   {ex[0]:<14} {ex[1]:<14} {ex[2]:<14} {ex[3]:<14} ctx={ex[4]}")
    print("=" * 64)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
