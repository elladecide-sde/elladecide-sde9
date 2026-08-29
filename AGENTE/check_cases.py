import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from integrator import make_corrector

c = make_corrector()
cases = [("ouedo", "puedo", ["que"]), ("xausqr", "causar", ["quiero"]),
         ("juefos", "juegos", ["mis"])]
for typo, target, ctx in cases:
    base = c.correct(typo)[:3]
    rrk = c.correct_ctx(typo, ctx)[:3]
    rrk0 = c.correct_ctx(typo, [])[:3]
    print(f"=== {typo} -> {target} (ctx={ctx})")
    print("  baseline:", [(w.word, round(w.total_cost(), 3)) for w in base])
    print("  rerank :", [(w.word, round(w.total_cost(), 3)) for w in rrk])
    print("  rerank0:", [(w.word, round(w.total_cost(), 3)) for w in rrk0])
    print("  FIX ctx>=1:", "SI" if rrk[0].word == target else "NO",
          "| FIX ctx=0:", "SI" if rrk0[0].word == target else "NO")
