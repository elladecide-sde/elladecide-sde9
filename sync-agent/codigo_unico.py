# ============================================================================
# codigo_unico.py — Port 1:1 de backend/src/codigoUnico.ts + reglas de oferta
# ============================================================================

from __future__ import annotations

import re
import unicodedata
from typing import Dict, List, Optional, Tuple

LIMPIAR_RE = re.compile(r"[^A-Z0-9.\-]")
MODELO_RE = re.compile(r"\d{2,}")

OFFER_RULES = [
    re.compile(r"^OFE(R(T(A|AS)?)?)?$"),
    re.compile(r"^LIQ(UID\w*)?$"),
    re.compile(r"^PROMO(S|CION|CIONES)?$"),
    re.compile(r"^(REMATE|SALDOS?|OUTLET)$"),
    re.compile(r"^(DESCUENTOS?|DTO)$"),
]
DEFAULT_OFERTA_SUFFIX = ("oferta", "liq")


def tokens(nombre: str) -> List[str]:
    limpio = LIMPIAR_RE.sub(" ", str(nombre or "").upper())
    return [t for t in limpio.split() if t]


def nombre_normalizado(nombre: str) -> str:
    return " ".join(tokens(nombre))


def _prefijo_marca(tokens_nombre: List[str], marcas: List[str]) -> List[str]:
    candidatas = [tokens(m) for m in marcas]
    candidatas = [c for c in candidatas if len(c) > 0]
    candidatas.sort(key=len, reverse=True)
    for cand in candidatas:
        if len(cand) > len(tokens_nombre):
            continue
        if tokens_nombre[: len(cand)] == cand:
            return cand
    return []


def partir(
    nombre: str,
    marcas: Optional[List[str]] = None,
    marca_palabras: int = 1,
) -> Tuple[List[str], str, List[str]]:
    """Separa un nombre en (marca, codigo_modelo, resto)."""
    t = tokens(nombre)
    marca: List[str] = []
    if marcas:
        marca = _prefijo_marca(t, marcas)
    mp = max(1, marca_palabras)
    if not marca:
        marca = t[:mp]

    resto_base = t[len(marca) :]
    idx_modelo = -1
    for i, tok in enumerate(resto_base):
        if MODELO_RE.search(tok):
            idx_modelo = i
            break

    if idx_modelo >= 0:
        return marca, resto_base[idx_modelo], resto_base[idx_modelo + 1 :]
    return marca, "", resto_base


def abrev(palabras: List[str], length: int = 3) -> str:
    return "".join(w[:length] for w in palabras)


def _codigo_en_nivel(partes: Tuple[List[str], str, List[str]], resto: int, length: int) -> str:
    marca, codigo, resto_list = partes
    return abrev(marca, length) + codigo + abrev(resto_list[:resto], length)


def hash_hex(texto: str) -> str:
    """FNV-1a 32-bit -> hex (8 chars uppercase). Idéntico a codigoUnico.ts."""
    h = 0x811C9DC5
    for ch in texto:
        h ^= ord(ch)
        h = (h * 0x01000193) & 0xFFFFFFFF
    return f"{h:08X}"


def asignar_codigos(
    nombres: List[str],
    marcas: Optional[List[str]] = None,
    max_resto: int = 6,
    max_len: int = 8,
) -> Tuple[Dict[str, str], List[str]]:
    """
    Retorna (mapa {nombre_normalizado: codigo_unico}, lista_descartados).
    """
    por_nombre: Dict[str, str] = {}
    descartados: List[str] = []
    for n in nombres:
        k = nombre_normalizado(n)
        if not k:
            continue
        if k not in por_nombre:
            por_nombre[k] = n
        else:
            descartados.append(n)

    pendientes: Dict[str, Tuple[List[str], str, List[str]]] = {
        k: partir(orig, marcas=marcas) for k, orig in por_nombre.items()
    }
    codigos: Dict[str, str] = {}

    for resto in range(0, max_resto + 1):
        for length in range(3, max_len + 1):
            if not pendientes:
                break
            buckets: Dict[str, List[str]] = {}
            for k, p in pendientes.items():
                c = _codigo_en_nivel(p, resto, length)
                buckets.setdefault(c, []).append(k)
            for c, ks in buckets.items():
                if len(ks) == 1:
                    k_unico = ks[0]
                    codigos[k_unico] = c
                    del pendientes[k_unico]
        if not pendientes:
            break

    for k, p in pendientes.items():
        base = _codigo_en_nivel(p, max_resto, max_len)
        codigos[k] = f"{base}-{hash_hex(k)[-4:]}"

    return codigos, descartados


def _strip_up(s: str) -> str:
    nfd = unicodedata.normalize("NFD", s)
    sin_acentos = "".join(c for c in nfd if unicodedata.category(c) != "Mn")
    return sin_acentos.upper()


def _clean_tok(s: str) -> str:
    return re.sub(r"[^A-Z0-9]", "", _strip_up(s))


def es_oferta(nombre: str, oferta_keywords: Tuple[str, ...] = DEFAULT_OFERTA_SUFFIX) -> bool:
    n = (nombre or "").strip()
    if not n:
        return False
    if n.endswith("."):
        return True
    low = n.lower()
    if any(low.endswith(kw) for kw in oferta_keywords):
        return True
    for tok in n.split():
        cleaned = _clean_tok(tok)
        if any(rule.match(cleaned) for rule in OFFER_RULES):
            return True
    return False
