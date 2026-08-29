"""Normalización para búsqueda fuzzy: quita diacríticos manteniendo minúsculas.
Determinística, sin estado, idempotente.

Constantes exportadas:
  DIACRITIC_COST_DEFAULT: float = 0.1
    Costo base por diferencia de diacríticos en el cálculo total.
"""

import unicodedata

# Costo de diacríticos: cuánto "pesa" la diferencia de tildes entre
# la forma normalizada de entrada y la forma original candidata.
# Valor inicial: 0.1 (mucho menor que un edit_cost de sustitución, que default en 1.0)
DIACRITIC_COST_DEFAULT: float = 0.1

# Tabla de pares ambiguos por significado: forma normalizada -> listade formas originales válidas.
# Estas palabras, al normalizarse, colisionan con otra palabra distinta.
# No las autocorregimos sin contexto; las dejamos como candidatos ambos.
AMBIGUOUS_PAIRS: dict[str, list[str]] = {
    "de": ["de", "dé"],
    "si": ["si", "sí"],
    "el": ["el", "él"],
    "tu": ["tu", "tú"],
    "mas": ["mas", "más"],
    "mi": ["mi", "mí"],
    "se": ["se", "sé"],
    "te": ["te", "té"],
    "que": ["que", "qué"],
    "como": ["como", "cómo"],
    "cuando": ["cuando", "cuándo"],
    "donde": ["donde", "dónde"],
    "adonde": ["adonde", "adonde"],
    "quien": ["quien", "quién"],
    "quienes": ["quienes", "quiénes"],
    "cual": ["cual", "cuál"],
    "cuales": ["cuales", "cuáles"],
    "cuanto": ["cuanto", "cuánto"],
    "cuanta": ["cuanta", "cuánta"],
    "cuantos": ["cuantos", "cuántos"],
    "cuantas": ["cuantas", "cuántas"],
    "aun": ["aun", "aún"],
}


def normalize_for_search(word: str) -> str:
    """
    Normaliza una palabra para búsqueda fuzzy:
    - Minúsculas
    - Quita diacríticos (á→a, é→e, í→i, ó→o, ú→u, ü→u)
    - NO toca la ñ (es un fonema propio, no un error)

    Returns str siempre minúscula sin diacríticos (excepto ñ).
    """
    if not word:
        return ""
    normalized = unicodedata.normalize('NFD', word.lower())
    result = ''.join(ch for ch in normalized if unicodedata.category(ch) != 'Mn')
    return result


def normalize_text(text: str) -> str:
    """
    Función de compatibilidad con build_ngrams pipeline.
    Procesa una línea completa de texto para extraer tokens.
    Convierte a minúsculas y mantiene letras latinas con diacríticos
    y espacios, para compatibilidad con el pipeline existente de n-gramas.
    """
    result = text.lower()
    # Mantener solo letras (incluyendo diacríticos latinos) y espacios
    filtered = ''.join(ch for ch in result if ch.isalpha() or ch.isspace() or ch in '.¿?!,;:—')
    return filtered


def is_ambiguous_normalized(normalized: str) -> bool:
    """Verifica si una forma normalizada está en la tabla de pares ambiguos."""
    return normalized in AMBIGUOUS_PAIRS


def get_ambiguous_options(normalized: str) -> list[str]:
    """Obtiene las opciones originales para un par ambiguo normalizado."""
    return AMBIGUOUS_PAIRS.get(normalized, [])


def diacritic_cost(normalized_input: str, original_candidate: str) -> float:
    """
    Calcula el costo por diferencias de diacríticos entre la forma normalizada
    de la entrada y la forma original candidata.

    Si la forma normalizada de la entrada coincide exactamente con la normalizada
    de la candidata, costo = 0.
    Si difieren en algún diacrítico, costo = DIACRITIC_COST_DEFAULT.

    Este costo se ADDITA al edit_cost (sobre formas normalizadas), no lo reemplaza.
    """
    norm_input = normalize_for_search(normalized_input)
    norm_cand = normalize_for_search(original_candidate)

    if norm_input == norm_cand:
        return 0.0

    if norm_cand in AMBIGUOUS_PAIRS and norm_input in AMBIGUOUS_PAIRS.get(norm_cand, []):
        return DIACRITIC_COST_DEFAULT * 0.3

    return DIACRITIC_COST_DEFAULT


def is_ambiguous_pair(normalized1: str, normalized2: str) -> bool:
    """Verifica si dos formas normalizadas pertenecen a un par ambiguo de significado."""
    n1 = normalize_for_search(normalized1)
    n2 = normalize_for_search(normalized2)

    if n1 in AMBIGUOUS_PAIRS and n2 in AMBIGUOUS_PAIRS[n1]:
        return True
    if n2 in AMBIGUOUS_PAIRS and n1 in AMBIGUOUS_PAIRS[n2]:
        return True
    return False