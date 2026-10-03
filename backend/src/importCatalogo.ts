// ============================================================================
// importCatalogo.ts — Núcleo COMPARTIDO del import Excel → catálogo.
//
// Lo usan por igual:
//   - scripts/import-catalogo.ts  (CLI: dry-run, archivos locales, upload)
//   - Worker POST /api/v1/admin/import  (la web pages/ manda las filas acá)
//
// De esta forma el parseo del Excel, el cálculo de costos y las 4 listas y la
// generación de `codigo_unico` viven en UNA sola implementación: no hay
// ambigüedad entre la web y el CLI. (La regla de códigos está en
// src/codigoUnico.ts; la fórmula de precios está en src/modelo.ts.)
// ============================================================================

import { asignarCodigos, nombreNormalizado, partir } from './codigoUnico';
import { calcularCostos, cfgDesde, PRECIOS_DEFAULT, type PreciosCfg, type Producto } from './modelo';

export interface OpcionesImport {
  colNombre?: number;
  colPrecio?: number;
  colCodigo?: number;
  /** Columna de categoría (opcional). -1 / ausente = vacío. */
  colCategoria?: number;
  /** Columna de fábrica (opcional). -1 / ausente = vacío. */
  colFabrica?: number;
  skip?: number;
  marcas?: string[];
  iva?: number;
  inversaIva?: number;
  inversaLista?: number;
  listas?: number[];
  ofertaKeywords?: string[];
}

/** El modelo completo: nada se descarta (costos + listas + categoria/fabrica). */
export type ProductoImport = Producto;

export interface EntradaMapa {
  codigo_unico: string;
  nombre: string;
  modelo: string | null;
}

export interface ResultadoImport {
  productos: ProductoImport[];
  mapa: EntradaMapa[];
  descartados: string[];
  rechazadas: Array<{ fila: number; motivo: string }>;
  colisiones: number;
}

const DEFAULT_OFERTA = ['oferta', 'liq'];

/* ── Marcas de oferta (espejo fiel de pages/articleParser.js) ───────────────
 * Cada regla se aplica a UNA palabra completa, tolerante a mayús/acentos y a
 * puntuación ("LIQ." → LIQ). El backend así decide IGUAL que el parser de la
 * web: un mismo Excel no produce ofertas distintas según quién lo suba. */
const stripUp = (s: string) => s.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toUpperCase();
const cleanTok = (s: string) => stripUp(s).replace(/[^A-Z0-9]/g, '');
const OFFER_RULES: RegExp[] = [
  /^OFE(R(T(A|AS)?)?)?$/,        // ofe, ofer, ofert, oferta(s)
  /^LIQ(UID\w*)?$/,              // liq, liq., liquidacion(es), liquidado
  /^PROMO(S|CION|CIONES)?$/,     // promo, promocion
  /^(REMATE|SALDOS?|OUTLET)$/,
  /^(DESCUENTOS?|DTO)$/,
];
function esMarcaOferta(nombre: string): boolean {
  return nombre.split(/\s+/).some((t) => OFFER_RULES.some((re) => re.test(cleanTok(t))));
}

/** Filtros y regla de precios espejo del generador de la página. */
export function importCatalogo(filas: unknown[][], opts: OpcionesImport = {}): ResultadoImport {
  const colNombre = opts.colNombre ?? 0;
  const colPrecio = opts.colPrecio ?? 1;
  const colCodigo = opts.colCodigo ?? -1;
  const colCategoria = opts.colCategoria ?? -1;
  const colFabrica = opts.colFabrica ?? -1;
  const skip = opts.skip ?? 1;
  const ofertaKeywords = opts.ofertaKeywords ?? DEFAULT_OFERTA;

  const cfg: PreciosCfg = cfgDesde({
    iva: opts.iva,
    inversaIva: opts.inversaIva,
    inversaLista: opts.inversaLista,
    listas: opts.listas ?? PRECIOS_DEFAULT.listas,
  });

  function esOferta(nombre: string): boolean {
    const n = nombre.trim();
    if (n.endsWith('.')) return true;
    const low = n.toLowerCase();
    return ofertaKeywords.some((kw) => low.endsWith(kw));
  }

  /** Columna opcional: -1 o vacío → ''. */
  function leerTexto(row: unknown[], col: number): string {
    if (col < 0) return '';
    const v = row[col];
    if (v === undefined || v === null) return '';
    const s = String(v).trim();
    return s.toLowerCase() === 'none' ? '' : s;
  }

  // ── Parseo de filas ──
  const filasOk: Array<{ nombre: string; precio: number; codigoExcel: string; categoria: string; fabrica: string }> = [];
  const rechazadas: Array<{ fila: number; motivo: string }> = [];
  for (let i = skip; i < filas.length; i++) {
    const row = filas[i];
    if (!Array.isArray(row)) continue;

    const nombre = String(row[colNombre] ?? '').trim();
    if (!nombre || nombre.toLowerCase() === 'none' || nombre.length <= 3) {
      rechazadas.push({ fila: i + 1, motivo: 'nombre vacío o corto' });
      continue;
    }
    if (!Number.isNaN(Number(nombre))) {
      rechazadas.push({ fila: i + 1, motivo: 'número suelto' });
      continue;
    }

    const precio = Number(row[colPrecio]);
    if (!Number.isFinite(precio) || precio <= 0) {
      rechazadas.push({ fila: i + 1, motivo: 'precio inválido' });
      continue;
    }

    let codigoExcel = '';
    if (colCodigo >= 0) {
      const raw = String(row[colCodigo] ?? '').trim();
      if (raw && raw.toLowerCase() !== 'none') {
        codigoExcel = Number.isNaN(Number(raw)) ? raw : String(Math.trunc(Number(raw)));
      }
    }
    filasOk.push({
      nombre,
      precio,
      codigoExcel,
      categoria: leerTexto(row, colCategoria),
      fabrica: leerTexto(row, colFabrica),
    });
  }

  // ── Dedup por nombre normalizado ──
  const porNombre = new Map<string, (typeof filasOk)[number]>();
  const descartados: string[] = [];
  for (const f of filasOk) {
    const k = nombreNormalizado(f.nombre);
    if (!porNombre.has(k)) porNombre.set(k, f);
    else descartados.push(f.nombre);
  }
  const unicas = [...porNombre.values()];

  // ── codigo_unico (determinístico, fuente de verdad) ──
  const { codigos } = asignarCodigos(
    unicas.map((f) => ({ nombre: f.nombre })),
    { marcas: opts.marcas },
  );

  const productos: ProductoImport[] = [];
  for (const f of unicas) {
    // Regla uniforme: la L4 del Excel (o el precio de oferta) entra a la
    // fórmula y de ahí salen costo_iva, costo_neto y las 4 listas.
    const c = calcularCostos(f.precio, cfg);
    // Oferta = unión de AMBAS detecciones (más certero): sufijos configurables
    // (termina en '.', 'oferta', 'liq') + marcas tipo PROMO/REMATE/DESCUENTO.
    // Si es oferta, manda el precio del Excel: las 4 listas quedan iguales.
    const oferta = esOferta(f.nombre) || esMarcaOferta(f.nombre);
    const precios = oferta ? Array(4).fill(Math.round(f.precio)) : c.precios;
    productos.push({
      codigo_unico: codigos.get(nombreNormalizado(f.nombre))!,
      descripcion: f.nombre,
      costo_neto: c.costo_neto,
      costo_iva: c.costo_iva,
      precios,
      categoria: f.categoria,
      fabrica: f.fabrica,
      codigo: f.codigoExcel,
      oferta,
    });
  }

  const mapa: EntradaMapa[] = unicas.map((f) => {
    const partes = partir(f.nombre, { marcas: opts.marcas });
    return {
      codigo_unico: codigos.get(nombreNormalizado(f.nombre))!,
      nombre: f.nombre,
      modelo: partes.codigo || null,
    };
  });

  const colisiones = productos.length - new Set(productos.map((p) => p.codigo_unico)).size;

  return { productos, mapa, descartados, rechazadas, colisiones };
}
