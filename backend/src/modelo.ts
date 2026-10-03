// ============================================================================
// modelo.ts — Modelo de producto + regla de precios (fuente de verdad backend)
//
// Es el espejo TS del cálculo que vive en pages/app.js (el navegador no puede
// importar este módulo, así que ahí queda portado a mano con la misma fórmula).
//
// Fórmula (la que manda, de punta a punta):
//
//   costo + IVA = Lista 4 / (1 + inversaLista/100)          = Lista 4 / 1,50
//   costo neto  = (costo + IVA) / (1 + IVA/100)             = Lista 4 / (1,50 × 1,21)
//   Lista n     = round(costo neto × (1 + IVA/100) × factor_n)
//
// NADA se descarta: costo_neto, costo_iva y las 4 listas conviven en la fila.
// ============================================================================

/** DTO de producto: lo que entra por /admin/products y lo que exporta el import. */
export interface Producto {
  codigo_unico: string;
  descripcion: string;
  costo_neto: number;
  costo_iva: number;
  /** [L1, L2, L3, L4] — enteros redondeados, en el MISMO orden de siempre. */
  precios: number[];
  categoria: string;
  fabrica: string;
  /**
   * Código de fábrica/comercial (columna "Código" del Excel o el del parser:
   * "041 / 042"). Opcional por compat: los clientes viejos no lo mandan y
   * queda ''.
   */
  codigo?: string;
  /**
   * true → artículo en oferta: las 4 listas iguales al precio de oferta.
   * Opcional por compat (mismo motivo que `codigo`); en D1 se persiste 0/1.
   */
  oferta?: boolean;
}

export interface PreciosCfg {
  /** % que se AÑADE al costo para llegar a la Lista 1..4 (default 21). */
  iva: number;
  /** % de IVA embebido en la Lista 4, que se le saca para volver al costo (default 21). */
  inversaIva: number;
  /** % que separa "costo + IVA" de la Lista 4 (default 50 → factor 1,50). */
  inversaLista: number;
  /** Factores de L1..L4. L4 se DERIVA de inversaLista (1 + inversaLista/100). */
  listas: number[];
}

/** Defaults: los valores que hoy están hardcodeados en app.js y en el CLI. */
export const PRECIOS_DEFAULT: PreciosCfg = {
  iva: 21,
  inversaIva: 21,
  inversaLista: 50,
  listas: [2.32, 2.12, 1.65, 1.5],
};

export interface CostoCalculado {
  /** costo + IVA  = Lista 4 / 1,50 */
  costo_iva: number;
  /** costo neto    = Lista 4 / (1,50 × 1,21) */
  costo_neto: number;
  /** [L1..L4] enteros redondeados. */
  precios: number[];
}

/** Normaliza una config parcial y le completa el factor de la L4. */
export function cfgDesde(parcial: Partial<PreciosCfg>): PreciosCfg {
  const cfg: PreciosCfg = {
    iva: Number(parcial.iva ?? PRECIOS_DEFAULT.iva),
    inversaIva: Number(parcial.inversaIva ?? PRECIOS_DEFAULT.inversaIva),
    inversaLista: Number(parcial.inversaLista ?? PRECIOS_DEFAULT.inversaLista),
    listas: Array.isArray(parcial.listas) && parcial.listas.length >= 3
      ? [...parcial.listas]
      : [...PRECIOS_DEFAULT.listas],
  };
  // La L4 SIEMPRE se deriva del factor inversa: si no, un cambio de inversa
  // deja de devolver el precio del Excel como Lista 4.
  cfg.listas[3] = 1 + cfg.inversaLista / 100;
  return cfg;
}

/**
 * Regla completa de precios a partir del precio de Lista 4 del Excel.
 * (Ofertas: el precio del Excel entra igual y las 4 listas quedan iguales,
 * pero el costo se deriva igual — no se descarta ningún cálculo.)
 *
 * OJO con el orden de operaciones: `L4 / (1,21 × 1,50)` NO es bit a bit igual
 * que `(L4 / 1,50) / 1,21` en coma flotante (medido: 3518 de 200.000 precios
 * redondean distinto). Acá se conserva el orden ORIGINAL del cálculo, así el
 * resultado es idéntico al de siempre y no se mueve el hash del catálogo.
 */
export function calcularCostos(precioLista4: number, cfg: PreciosCfg = PRECIOS_DEFAULT): CostoCalculado {
  const c = cfg.listas.length >= 4 ? cfg : cfgDesde(cfg);
  const ivaF = 1 + c.iva / 100;
  const inversaIvaF = 1 + c.inversaIva / 100;
  const inversaListaF = 1 + c.inversaLista / 100;
  const costo_iva = precioLista4 / inversaListaF;                          // L4 / 1,50
  const costo_neto = precioLista4 / (inversaIvaF * inversaListaF);         // L4 / (1,21 × 1,50)
  const precios = c.listas.map((f) => Math.round(costo_neto * ivaF * f));
  return { costo_iva, costo_neto, precios };
}

/**
 * Regla inversa: recuperar costo + IVA y costo neto desde las 4 listas ya
 * calculadas. Es la misma fórmula (la L4 es la entrada), por eso el backend
 * puede poblar las columnas aunque un cliente viejo mande solo `precios`.
 */
export function derivarCostos(precios: number[], cfg: PreciosCfg = PRECIOS_DEFAULT): { costo_neto: number; costo_iva: number } {
  const { costo_neto, costo_iva } = calcularCostos(Number(precios[3]) || 0, cfg);
  return { costo_neto, costo_iva };
}

/** [L1..L4] desde un registro plano lista1..lista4 (columnas de D1). */
export const listasDe = (r: { lista1: number; lista2: number; lista3: number; lista4: number }): number[] => [
  r.lista1,
  r.lista2,
  r.lista3,
  r.lista4,
];

// ── Fábrica de respaldo ─────────────────────────────────────────────────────
// "SIN FABRICA" es una fábrica MÁS (espejo de pages/articleParser.js): si ni
// el Excel ni el parser traen marca, el producto se guarda con este valor.
// REGLA DE NEGOCIO: ningún artículo del catálogo queda sin fábrica.
export const SIN_FABRICA = 'SIN FABRICA';

/** Normaliza la fábrica ANTES de persistirla: vacía o en blanco → SIN_FABRICA. */
export const fabPersistida = (fabrica: string): string => (fabrica || '').trim() || SIN_FABRICA;
