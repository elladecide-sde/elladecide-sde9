// ============================================================================
// importCatalogo.ts — Núcleo COMPARTIDO del import Excel → catálogo.
//
// Lo usan por igual:
//   - scripts/import-catalogo.ts  (CLI: dry-run, archivos locales, upload)
//   - Worker POST /api/v1/admin/import  (la web pages/ manda las filas acá)
//
// De esta forma el parseo del Excel, el cálculo de las 4 listas y la generación
// de `codigo_unico` viven en UNA sola implementación: no hay ambigüedad entre
// la web y el CLI. (La regla de códigos está en src/codigoUnico.ts.)
// ============================================================================

import { asignarCodigos, nombreNormalizado, partir } from './codigoUnico';

export interface OpcionesImport {
  colNombre?: number;
  colPrecio?: number;
  colCodigo?: number;
  skip?: number;
  marcas?: string[];
  iva?: number;
  inversaIva?: number;
  inversaLista?: number;
  listas?: number[];
  ofertaKeywords?: string[];
}

export interface ProductoImport {
  codigo_unico: string;
  nombre: string;
  precios: number[];
}

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

const DEFAULT_LISTAS = [2.32, 2.12, 1.65, 1.5];
const DEFAULT_OFERTA = ['oferta', 'liq'];

/** Filtros y regla de precios espejo del generador Python (sde7). */
export function importCatalogo(filas: unknown[][], opts: OpcionesImport = {}): ResultadoImport {
  const colNombre = opts.colNombre ?? 0;
  const colPrecio = opts.colPrecio ?? 1;
  const colCodigo = opts.colCodigo ?? -1;
  const skip = opts.skip ?? 1;
  const iva = opts.iva ?? 21;
  const inversaIva = opts.inversaIva ?? 21;
  const inversaLista = opts.inversaLista ?? 50;
  const listas = opts.listas ?? DEFAULT_LISTAS;
  const ofertaKeywords = opts.ofertaKeywords ?? DEFAULT_OFERTA;

  const ivaF = 1 + iva / 100;
  const lista4Factor = (1 + inversaIva / 100) * (1 + inversaLista / 100); // 1.21 * 1.50

  function esOferta(nombre: string): boolean {
    const n = nombre.trim();
    if (n.endsWith('.')) return true;
    const low = n.toLowerCase();
    return ofertaKeywords.some((kw) => low.endsWith(kw));
  }

  function calcularPrecios(precioLista4: number): number[] {
    const costo = precioLista4 / lista4Factor;
    return listas.map((f) => Math.round(costo * ivaF * f));
  }

  // ── Parseo de filas ──
  const filasOk: Array<{ nombre: string; precio: number; codigoExcel: string }> = [];
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
    filasOk.push({ nombre, precio, codigoExcel });
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
    productos.push({
      codigo_unico: codigos.get(nombreNormalizado(f.nombre))!,
      nombre: f.nombre,
      precios: esOferta(f.nombre)
        ? Array(4).fill(Math.round(f.precio))
        : calcularPrecios(f.precio),
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