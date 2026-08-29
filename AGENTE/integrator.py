"""
Integración del corrector fuzzy + normalización de diacríticos con el diccionario existente.

Flujo según especificación Sección 8:
  entrada del usuario
      ↓
  lookup exacto (forma original, tal cual)
      ↓
    ¿existe? ──sí──→ palabra válida, no hacer fuzzy search
          │no
          ↓
  normalize_for_search(entrada)
      ↓
  fuzzy candidate generation (sobre índice normalizado)
      ↓
  cálculo de edit_cost + keyboard_cost + diacritic_cost
      ↓
  ranking provisorio (señales disponibles)
      ↓
  candidatos válidos, con ORTOGRAFÍA ORIGINAL del diccionario (tildes incluidas)
"""

from __future__ import annotations

from normalize import normalize_for_search, diacritic_cost, DIACRITIC_COST_DEFAULT
from corrector import build_bk_tree_from_dict, Candidate, correct_word
from bk_tree import edit_distance
import os, struct
import numpy as np

# Diccionario real del proyecto: ruta relativa desde el directorio del script
DICT_PATH = "generated/dictionary.bin"

def _parse_dictionary_bin(path: str) -> tuple[list[str], list[int]]:
    """
    Parsea generated/dictionary.bin.
    Formato: header LOLA (64B) + entries (10B c/u) + string table (null-terminated UTF-8).
    Cada entry: word_offset u32 + frecuencia u32 + flags u16.
    Retorna (words, frequencies) — listas paralelas del mismo largo.
    """
    words: list[str] = []
    frequencies: list[int] = []

    with open(path, "rb") as f:
        header = f.read(64)
        magic = header[0:4]
        if magic != b"LOLA":
            raise ValueError(f"Magic no esperado: {magic}")
        word_count = int.from_bytes(header[8:12], "little")
        string_offset = int.from_bytes(header[12:16], "little")

        # Leer entries (10B cada una): word_offset(4) + freq(4) + flags(2)
        # Las entries empiezan justo después del header de 64B.
        entries_offset = 64
        f.seek(entries_offset)
        raw_entries = f.read(word_count * 10)

        # Leer string table completa
        f.seek(string_offset)
        string_table_data = f.read()
        raw_strings = string_table_data.split(b"\x00")

        for i in range(word_count):
            entry = raw_entries[i * 10: i * 10 + 10]
            if len(entry) < 10:
                break
            freq = int.from_bytes(entry[4:8], "little")

            if i < len(raw_strings) and raw_strings[i]:
                try:
                    word = raw_strings[i].decode("utf-8")
                    words.append(word)
                    frequencies.append(freq)
                except Exception:
                    pass

    return words, frequencies


def load_dictionary_words() -> tuple[list[str], list[int]]:
    """
    Carga palabras y frecuencias del diccionario generado.
    Retorna (words, frequencies) — listas paralelas.
    El fallback también devuelve tupla para que el caller no necesite
    distinguir entre el caso real y el de testing.
    """
    dict_abs_path = os.path.abspath(DICT_PATH)
    if os.path.exists(dict_abs_path):
        return _parse_dictionary_bin(dict_abs_path)

    # Fallback: diccionario reducido para testing (frecuencias ficticias)
    fallback_words = [
        "camión", "canción", "árbol", "lápiz", "también", "después",
        "sí", "él", "tú", "qué",
        "hola", "mundo", "casa", "mesa", "silla", "puerta",
        "libro",
    ]
    fallback_freqs = [100, 80, 60, 40, 90, 70, 200, 150, 120, 110,
                      500, 400, 600, 300, 250, 350, 180]
    return fallback_words, fallback_freqs


def make_corrector() -> object:
    """
    Crea el corrector integrado con el diccionario del proyecto.
    Retorna un objeto con método correct(word) que retorna lista de Candidate.
    """
    dict_words, dict_freqs = load_dictionary_words()
    tree, original_map, freq_map = build_bk_tree_from_dict(dict_words, dict_freqs)

    class IntegratedCorrector:
        def correct(self, word: str) -> list[Candidate]:
            return correct_word(word, tree, original_map, edit_cost=1.0,
                                keyboard_cost=0.5, diacritic_cost_multiplier=1.0,
                                freq_map=freq_map)

        def correct_ctx(self, word: str, context_words: list[str] | None = None,
                         boost: float = 0.6) -> list[Candidate]:
            """Igual que correct() pero re-ordena con el neural_ranker usando el
            typo y el contexto previo. La neurona siempre se aplica: con features
            del typo puede desempatar sin contexto; con contexto, lo combina."""
            cands = correct_word(word, tree, original_map, edit_cost=1.0,
                                keyboard_cost=0.5, diacritic_cost_multiplier=1.0,
                                freq_map=freq_map)
            return _neural_rerank(word, cands, context_words, boost)

    return IntegratedCorrector()


# ============================================================
# RE-RANKER NEURAL (context-aware) - integracion al pipeline real
# ============================================================
# TF se importa de forma perezosa (solo al re-rankear) para no romper los
# entornos que no tienen TensorFlow (p.ej. .venv usado por los tests).
_RANKER_VOCAB: dict[str, int] | None = None
_RANKER_OOV: int | None = None
# Peso de las features del typo dentro de la banda de empate (inferencia).
_RANKER_ALPHA = float(os.environ.get("NEURAL_ALPHA", "0.3"))


def _get_ranker_vocab() -> tuple[dict[str, int], int]:
    global _RANKER_VOCAB, _RANKER_OOV
    if _RANKER_VOCAB is None:
        from train_ranker import build_vocab
        _RANKER_VOCAB, _RANKER_OOV = build_vocab()
    return _RANKER_VOCAB, _RANKER_OOV


def _typo_features(typo: str, cand: str) -> list[float]:
    """4 floats (edicion/long normalizada, chars compartidos, 1er char, long diff).
    Espejo de train_ranker.typo_features para usar en inferencia."""
    from collections import Counter
    lt, lc = len(typo), len(cand)
    m = max(lt, lc, 1)
    ed = edit_distance(typo, cand)
    ed_norm = ed / m
    ct, cc = Counter(typo.lower()), Counter(cand.lower())
    shared = sum(min(ct[ch], cc[ch]) for ch in set(ct) | set(cc))
    shared_frac = shared / m
    first = 1.0 if (typo and cand and typo[0].lower() == cand[0].lower()) else 0.0
    len_diff = abs(lt - lc) / m
    return [ed_norm, shared_frac, first, len_diff]


def _neural_rerank(query: str, cands: list, context_words: list[str] | None, boost: float = 0.6):
    # Sin contexto el modelo actual no generaliza bien (lo hemos medido: el
    # re-rank a ciegas empeora el baseline). Hibrido: solo re-rankeamos cuando
    # hay contexto previo real.
    if not context_words:
        return cands
    from infer_ranker import score as rank_score
    word_to_id, oov_id = _get_ranker_vocab()
    ctx_ids = [word_to_id.get(w.lower(), oov_id) for w in context_words][-5:]
    # La neurona SOLO desempatara dentro de la misma banda de distancia de edicion.
    # Nunca debe sobre-escribir un candidato claramente mas cercano al typo.
    best = min(c.total_cost() for c in cands)
    EPS = 0.15
    scored = []
    for c in cands:
        cid = word_to_id.get(c.word, oov_id)
        feats = _typo_features(query, c.word)
        ed_norm = feats[0]
        s = rank_score(ctx_ids, cid, feats)
        scored.append((c, s, c.total_cost(), ed_norm, cid == oov_id))
    tier = [x for x in scored if x[2] <= best + EPS]
    rest = [x for x in scored if x[2] > best + EPS]
    # dentro de la banda: score combinado neuronal + features del typo + frecuencia.
    #
    # CASO OOV: si el candidato no esta en el vocabulario top-20k (cid==oov_id),
    # su embedding es identico al de CUALQUIER otra palabra fuera de vocabulario
    # (colision de ID). La red no puede haber aprendido nada especifico de esa
    # palabra via contexto -- el score neuronal para ese candidato refleja un
    # patron generico "OOV despues de este contexto", no una preferencia real
    # por esa palabra puntual. Por eso el termino neuronal se anula (no se
    # resta del peso total: simplemente aporta 0), y el candidato se juzga por
    # ortografia + frecuencia real, igual que cualquier otro.
    #
    # FRECUENCIA: antes de este fix, dentro de la banda de empate solo se
    # usaban typo-features + neurona. Eso fallaba en casos donde AMBAS señales
    # quedan empatadas entre un candidato comun y uno rarisimo (ej. 'puedo'
    # freq=2413 vs 'ruedo' freq=1, mismo edit_distance=1 al typo 'ouedo'): la
    # neurona, con poquisimas muestras de entrenamiento para la palabra rara,
    # puede dar un score ruidoso y ganarle a la palabra correcta. Frecuencia
    # real actua como prior estabilizador. Se normaliza con log1p dentro de
    # la banda (no globalmente) para no aplastar la escala entre 0 y 1.
    max_log_freq = max((np.log1p(x[0].frequency) for x in tier), default=0.0) or 1.0
    W_NEURAL, W_TYPO, W_FREQ = 0.45, 0.30, 0.25

    def _final(x):
        c, s, _, ed_norm, is_oov = x
        typo_score = 1.0 - ed_norm
        score_neuronal = 0.0 if is_oov else boost * s
        freq_score = np.log1p(c.frequency) / max_log_freq
        return (score_neuronal * W_NEURAL + typo_score * W_TYPO
                + freq_score * W_FREQ)
    tier.sort(key=lambda x: (_final(x), -x[2]), reverse=True)
    # fuera de la banda: por distancia de edicion (fuzzy clasico)
    rest.sort(key=lambda x: x[2])
    return [c for c, *_ in tier] + [c for c, *_ in rest]


def correct_with_context(word: str, context_words: list[str] | None = None,
                         boost: float = 0.6) -> list:
    """API de alto nivel: corrige `word` usando el contexto previo real.

    El teclado debe pasar las palabras anteriores (aunque sean 1 o 2). Si no
    hay contexto, se comporta como el corrector fuzzy clasico.
    """
    return get_corrector().correct_ctx(word, context_words, boost)

# Instancia global para usar en el pipeline
_corrector: object | None = None

def get_corrector() -> object:
    """Singleton: retorna el corrector integrado (creado bajo demanda)."""
    global _corrector
    if _corrector is None:
        _corrector = make_corrector()
    return _corrector

def correct(word: str) -> list[Candidate]:
    """Función conveniente: corrige una palabra usando el diccionario integrado."""
    return get_corrector().correct(word)