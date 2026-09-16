"""Contrato mínimo compartido por la implementación Python y el port Kotlin."""

from normalize import normalize_for_search
from bk_tree import edit_distance


def test_spanish_normalization_contract() -> None:
    assert normalize_for_search("ÁRBOL") == "arbol"
    assert normalize_for_search("pingüino") == "pinguino"
    assert normalize_for_search("AÑO") == "año"
    assert normalize_for_search("año") != normalize_for_search("ano")


def test_model_compatible_levenshtein_contract() -> None:
    assert edit_distance("casa", "cosa") == 1
    assert edit_distance("ca", "ac") == 2
