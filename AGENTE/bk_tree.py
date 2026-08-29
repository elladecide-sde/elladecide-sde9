"""
BK-tree (Burkhard-Keller tree) para búsqueda fuzzy con distancia de edición.

Índice sobre palabras normalizadas (minúsculas, sin diacríticos).
Lookup sobre la forma normalizada; los candidatos devueltos mapean
a formas originales del diccionario.
"""

from collections import defaultdict


def edit_distance(s1: str, s2: str) -> int:
    """
    Distancia de edición óptima (Levenshtein) entre dos cadenas.
    Soporta: inserción, eliminación, sustitución, transposición.
    """
    if len(s1) < len(s2):
        s1, s2 = s2, s1

    if len(s2) == 0:
        return len(s1)

    previous_row = list(range(len(s2) + 1))
    for i, c1 in enumerate(s1):
        current_row = [i + 1]
        for j, c2 in enumerate(s2):
            insertions = previous_row[j + 1] + 1
            deletions = current_row[j] + 1
            substitutions = previous_row[j] + (c1 != c2)
            current_row.append(min(insertions, deletions, substitutions))
        previous_row = current_row

    return previous_row[len(s2)]


class BKTreeNode:
    """Nodo simple del BK-tree: (palabra_normalizada, hijos)."""

    def __init__(self, word: str):
        self.word = word
        self.children: dict[int, BKTreeNode] = {}


class BKTree:
    """
    BK-tree para búsqueda fuzzy.
    - Índice construido sobre palabras normalizadas.
    - Lookup: dado un query normalizado y un límite de distancia,
      retorna todas las palabras del árbol dentro de ese límite.
    - Regla de poda: si dist(query, node.word) = d, entonces solo
      es necesario explorar hijos en el rango [d - max_dist, d + max_dist].
    """

    def __init__(self):
        self.root: BKTreeNode | None = None

    def insert(self, word: str) -> None:
        """Inserta una palabra normalizada en el árbol."""
        norm = word.lower()
        # Normalizar quita diacríticos; aseguremos consistencia
        norm = self._strip_diacritics(norm)

        if self.root is None:
            self.root = BKTreeNode(norm)
            return

        node = self.root
        while True:
            d = edit_distance(node.word, norm)
            if d == 0:
                # Palabra ya existe; no insertar duplicada
                return
            if d not in node.children:
                node.children[d] = BKTreeNode(norm)
                return
            node = node.children[d]

    def _strip_diacritics(self, s: str) -> str:
        """Quita diacrítos tipo normalize_for_search."""
        import unicodedata
        normalized = unicodedata.normalize('NFD', s.lower())
        return ''.join(ch for ch in normalized if unicodedata.category(ch) != 'Mn')

    def search(self, word: str, max_dist: int) -> list[str]:
        """
        Busca en el árbol todas las palabras dentro de max_dist
        de la forma normalizada del query.

        Retorna lista de palabras normalizadas dentro del límite.
        """
        norm = self._strip_diacritics(word.lower())
        results: list[str] = []
        if self.root is None:
            return results
        self._search_recursive(self.root, norm, max_dist, results)
        return results

    def _search_recursive(
        self,
        node: BKTreeNode,
        query: str,
        max_dist: int,
        results: list[str],
    ) -> None:
        """Búsqueda recursiva con poda BK-tree."""
        d = edit_distance(node.word, query)
        if d <= max_dist:
            results.append(node.word)

        # Poda: solo explorar hijos cuyos límites se superpongan
        # con el rango [0, max_dist] desde la distancia actual
        lower = d - max_dist
        upper = d + max_dist

        for dist_key, child in node.children.items():
            if dist_key >= lower and dist_key <= upper:
                self._search_recursive(child, query, max_dist, results)

    def search_originals(self, word: str, max_dist: int, original_map: dict[str, list[str]]) -> list[str]:
        """
        Busca candidatos fuzzy y los mapea a formas originales del diccionario.

        word: la palabra query (se normalizará interna)
        max_dist: distancia máxima permitida
        original_map: dict mapeando forma normalizada -> [formas originales]
        Retorna lista de formas originales (sin duplicados).
        """
        norm_results = self.search(word, max_dist)
        originals: list[str] = []
        seen: set[str] = set()
        for nr in norm_results:
            for orig in original_map.get(nr, []):
                if orig not in seen:
                    seen.add(orig)
                    originals.append(orig)
        return originals


def build_bk_tree(normalized_words: list[str], original_map: dict[str, list[str]]) -> BKTree:
    """
    Construye un BK-tree a partir de una lista de palabras normalizadas
    y un mapa de forma normalizada -> formas originales del diccionario.
    """
    tree = BKTree()
    for nw in normalized_words:
        tree.insert(nw)
    return tree