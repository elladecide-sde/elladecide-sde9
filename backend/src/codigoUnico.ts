// ============================================================================
// codigoUnico.ts — Generador determinístico de `codigo_unico`
//
// Regla (fuente de verdad: el import Excel → D1):
//   codigo_unico = abrev(marca) + codigo_de_artículo
//     "BAKHOU 2000 LISO ALGODON HOMBRE"  →  BAK2000
//   Sin modelo (ej. los "LIQ."):
//     codigo_unico = abrev(marca) + abrev(primeras palabras de la descripción)
//
// La desambiguación es FUNCIÓN PURA del nombre y del set completo:
//   1. Modelo COMPLETO: se captura el primer token con 2+ dígitos entero
//      ("452-1", "104.3", "1002E"), no solo los dígitos.
//   2. Colisión → se agregan palabras posteriores al modelo (una a una).
//   3. Sigue colisionando → se crecen las abreviaciones (3→4→5…).
//   4. Residual (nombres idénticos tras normalizar) → sufijo de 4 hex de
//      un hash FNV-1a del nombre normalizado.
//
// NUNCA se usa posición ni contador: el resultado no depende del orden del
// Excel. Este archivo es deliberadamente PURO (sin imports de Node/Workers)
// para poder portarse 1:1 a Python en el editor de fotos (sde11).
// ============================================================================

export interface ProductoBase {
  nombre: string;
}

export interface Partes {
  marca: string[];
  codigo: string;
  resto: string[];
}

export interface OpcionesCodigo {
  /** Lista de marcas (nombres de carpeta). Longest-prefix match sobre el nombre. */
  marcas?: string[];
  /** Palabras a usar como marca si no hay match con `marcas`. Default: 1. */
  marcaPalabras?: number;
  /** Máximo de palabras posteriores al modelo que entran en la desambiguación. */
  maxResto?: number;
  /** Máximo largo de cada abreviación durante el crecimiento. */
  maxLen?: number;
}

const LIMPIAR = /[^A-Z0-9.\-]/g;

/** Tokeniza: MAYÚSCULAS, solo A-Z 0-9 . - , separados por espacio. */
export function tokens(nombre: string): string[] {
  return String(nombre).toUpperCase().replace(LIMPIAR, ' ').split(/\s+/).filter(Boolean);
}

/** Nombre normalizado (clave de dedup y de ordenamiento determinístico). */
export function nombreNormalizado(nombre: string): string {
  return tokens(nombre).join(' ');
}

function prefijoMarca(tokensNombre: string[], marcas: string[]): string[] {
  const candidatas = marcas
    .map((m) => tokens(m))
    .filter((t) => t.length > 0)
    .sort((a, b) => b.length - a.length); // longest primero
  for (const cand of candidatas) {
    if (cand.length > tokensNombre.length) continue;
    let ok = true;
    for (let i = 0; i < cand.length; i++) {
      if (cand[i] !== tokensNombre[i]) {
        ok = false;
        break;
      }
    }
    if (ok) return cand;
  }
  return [];
}

/** Separa un nombre en marca / codigo (modelo) / resto. */
export function partir(nombre: string, opts: OpcionesCodigo = {}): Partes {
  const t = tokens(nombre);

  let marca: string[] = [];
  if (opts.marcas?.length) marca = prefijoMarca(t, opts.marcas);
  const marcaPalabras = Math.max(1, opts.marcaPalabras ?? 1);
  if (!marca.length) marca = t.slice(0, marcaPalabras);

  const restoBase = t.slice(marca.length);
  const idxModelo = restoBase.findIndex((tok) => /\d{2,}/.test(tok));

  if (idxModelo >= 0) {
    return {
      marca,
      codigo: restoBase[idxModelo],
      resto: restoBase.slice(idxModelo + 1),
    };
  }
  return { marca, codigo: '', resto: restoBase };
}

/** Abrevia cada palabra a `len` caracteres y concatena. */
export function abrev(palabras: string[], len = 3): string {
  return palabras.map((w) => w.slice(0, len)).join('');
}

function codigoEnNivel(p: Partes, resto: number, len: number): string {
  return abrev(p.marca, len) + p.codigo + abrev(p.resto.slice(0, resto), len);
}

/** FNV-1a 32-bit → hex. Determinístico y portable (mismo resultado en Python). */
function hashHex(texto: string): string {
  let h = 0x811c9dc5;
  for (let i = 0; i < texto.length; i++) {
    h ^= texto.charCodeAt(i);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return h.toString(16).toUpperCase().padStart(8, '0');
}

export interface ResultadoCodigo {
  codigos: Map<string, string>;
  descartados: string[];
}

/**
 * Asigna `codigo_unico` a todo el set de forma determinística y sin
 * dependencia del orden de entrada. Dos nombres que normalizan igual
 * comparten código (y el segundo se reporta como descartado).
 */
export function asignarCodigos(items: ProductoBase[], opts: OpcionesCodigo = {}): ResultadoCodigo {
  const maxResto = opts.maxResto ?? 6;
  const maxLen = opts.maxLen ?? 8;

  const porNombre = new Map<string, ProductoBase>();
  const descartados: string[] = [];
  for (const it of items) {
    const k = nombreNormalizado(it.nombre);
    if (!porNombre.has(k)) porNombre.set(k, it);
    else descartados.push(it.nombre);
  }

  const pendientes = new Map<string, Partes>();
  for (const [k, it] of porNombre) pendientes.set(k, partir(it.nombre, opts));

  const codigos = new Map<string, string>();

  for (let resto = 0; resto <= maxResto; resto++) {
    for (let len = 3; len <= maxLen; len++) {
      if (pendientes.size === 0) break;
      const buckets = new Map<string, string[]>();
      for (const [k, p] of pendientes) {
        const c = codigoEnNivel(p, resto, len);
        const arr = buckets.get(c);
        if (arr) arr.push(k);
        else buckets.set(c, [k]);
      }
      for (const [c, ks] of buckets) {
        if (ks.length === 1) {
          codigos.set(ks[0], c);
          pendientes.delete(ks[0]);
        }
      }
    }
    if (pendientes.size === 0) break;
  }

  // Residual: nombres idénticos tras el mayor intento → sufijo hash.
  for (const [k, p] of pendientes) {
    const base = codigoEnNivel(p, maxResto, maxLen);
    codigos.set(k, base + '-' + hashHex(k).slice(-4));
  }

  return { codigos, descartados };
}