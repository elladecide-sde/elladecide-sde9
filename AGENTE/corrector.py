"""
Corrector fuzzy + normalización de diacríticos para Lola Keyboard.
Versión simplificada para testing.
"""

from __future__ import annotations

from normalize import normalize_for_search, diacritic_cost, DIACRITIC_COST_DEFAULT, is_ambiguous_pair, AMBIGUOUS_PAIRS
from bk_tree import BKTree, build_bk_tree, edit_distance


class Candidate:
    """Representa un candidato con metadata para ranking."""
    __slots__ = ['word', 'edit_distance', 'edit_cost', 'diacritic_cost', 'frequency']

    def __init__(self, word: str, edit_distance: int, edit_cost: float,
                 diacritic_cost: float, frequency: int = 0):
        self.word = word
        self.edit_distance = edit_distance
        self.edit_cost = edit_cost
        self.diacritic_cost = diacritic_cost
        self.frequency = frequency

    def total_cost(self) -> float:
        return self.edit_cost + self.diacritic_cost

    def __lt__(self, other):
        if self.total_cost() != other.total_cost():
            return self.total_cost() < other.total_cost()
        return self.frequency > other.frequency

    def __repr__(self):
        return f"C('{self.word}',dist={self.edit_distance},cost={self.edit_cost:.2f}+{self.diacritic_cost:.2f})"


def build_bk_tree_from_dict(
    dictionary_words: list[str],
    frequencies: list[int] | None = None,
) -> tuple[BKTree, dict[str, list[str]], dict[str, int]]:
    """
    Construye el BK-tree, el mapa original y el mapa de frecuencias.

    dictionary_words: lista de palabras (con diacríticos originales).
    frequencies: lista paralela de frecuencias enteras. Si es None o más
                 corta que dictionary_words, las palabras sin frecuencia
                 quedan con frecuencia 0.
    Retorna (tree, original_map, freq_map).
      - original_map: normalizado -> [formas originales]
      - freq_map:     palabra_original -> frecuencia
    """
    original_map: dict[str, list[str]] = {}
    freq_map: dict[str, int] = {}

    for i, word in enumerate(dictionary_words):
        norm = normalize_for_search(word)
        if norm not in original_map:
            original_map[norm] = []
        if word not in original_map[norm]:
            original_map[norm].append(word)
        # frecuencia: tomar la máxima si la misma palabra aparece varias veces
        freq = frequencies[i] if frequencies and i < len(frequencies) else 0
        if freq > freq_map.get(word, 0):
            freq_map[word] = freq

    normalized_words = [normalize_for_search(w) for w in dictionary_words]
    tree = build_bk_tree(normalized_words, original_map)
    return tree, original_map, freq_map


def correct_word(word: str, tree: BKTree, original_map: dict[str, list[str]],
                 max_dist: int | None = None,
                 edit_cost: float = 1.0,
                 keyboard_cost: float = 0.5,
                 diacritic_cost_multiplier: float = 1.0,
                 freq_map: dict[str, int] | None = None) -> list[Candidate]:
    """
    Corrige una palabra usando el BK-tree, mapa original y frecuencias.

    freq_map: palabra_original -> frecuencia (del dictionary.bin).
              Si es None, todas las frecuencias quedan en 0 (comportamiento
              anterior — útil para tests sin diccionario real).
    Retorna lista de Candidate ordenados por costo total (desempate por frecuencia).
    """
    freq_map = freq_map or {}

    # Paso 1: lookup exacto
    norm = normalize_for_search(word)
    if word in original_map.get(norm, []):
        freq = freq_map.get(word, 0)
        return [Candidate(word, 0, 0.0, 0.0, freq)]

    # Paso 2: fuzzy search
    if max_dist is None:
        L = len(norm)
        if L <= 4:
            max_dist = 1
        elif L <= 8:
            max_dist = 2
        else:
            max_dist = 2

    norm_results = tree.search(word, max_dist)

    candidates: list[Candidate] = []
    seen_words: set[str] = set()

    for norm_word in norm_results:
        for orig in original_map.get(norm_word, []):
            if orig in seen_words:
                continue
            seen_words.add(orig)

            # BUG ORIGINAL: comparaba norm_word contra normalize_for_search(orig),
            # pero orig normaliza exactamente a norm_word por construccion de
            # original_map -> dist siempre 0, sin importar la distancia real al
            # typo del usuario. Debe compararse contra la forma normalizada de
            # la palabra que escribio el usuario (`norm`, ya calculada arriba).
            dist = edit_distance(norm, norm_word)
            base_edit_cost = edit_cost * dist
            dc = diacritic_cost(word, orig) * diacritic_cost_multiplier
            kc = keyboard_cost  # simplified

            freq = freq_map.get(orig, 0)

            candidates.append(Candidate(
                word=orig,
                edit_distance=dist,
                edit_cost=base_edit_cost,
                diacritic_cost=dc,
                frequency=freq
            ))

    candidates.sort()
    return candidates