// ============================================================================
// compras/types.ts — Tipos y validaciones para el módulo de comprasED
// ============================================================================

export type EstadoCompra = 'DISPONIBLE' | 'PROCESADO';
export type RangoFecha = '48h' | '7d' | '1m' | 'personalizada';

export interface ItemCompra {
  codigo_unico: string;
  descripcion: string;
  cantidad: number;
  precio_unitario: number;
  subtotal: number;
}

export interface CompraCliente {
  id_documento: number;
  dni: string;
  fecha: string;
  tipo_doc: string;
  total: number;
  items: ItemCompra[];
  estado?: EstadoCompra;
  revision?: number;
  procesado_at?: string | null;
}

export interface CompraClienteRow {
  id_documento: number;
  dni: string;
  fecha: string;
  tipo_doc: string;
  total: number;
  items_json: string;
  estado?: string;
  revision?: number;
  procesado_at?: string | null;
  updated_at?: string;
}

export interface ClienteInteresado {
  dni: string;
  rango?: RangoFecha;
  fecha_desde?: string | null;
  fecha_hasta?: string | null;
  requested_at?: string;
  last_synced_at?: string | null;
}

/**
 * Normaliza un DNI o CUIT:
 * Remueve espacios, puntos, guiones y caracteres no numéricos.
 */
export function normalizarDni(raw: string | number | null | undefined): string {
  if (raw === null || raw === undefined) return '';
  const str = String(raw).trim();
  return str.replace(/\D/g, '');
}

/**
 * Calcula fechas ISO límites para un rango especificado.
 */
export function calcularRangoFechas(
  rango: RangoFecha = '48h',
  desdeCustom?: string | null,
  hastaCustom?: string | null,
  referencia: Date = new Date()
): { desde: string; hasta: string } {
  const hastaDate = hastaCustom ? new Date(hastaCustom) : referencia;
  let desdeDate: Date;

  if (rango === 'personalizada' && desdeCustom) {
    desdeDate = new Date(desdeCustom);
  } else if (rango === '7d') {
    desdeDate = new Date(hastaDate.getTime() - 7 * 24 * 60 * 60 * 1000);
  } else if (rango === '1m') {
    desdeDate = new Date(hastaDate.getTime() - 30 * 24 * 60 * 60 * 1000);
  } else {
    // Default 48hs
    desdeDate = new Date(hastaDate.getTime() - 48 * 60 * 60 * 1000);
  }

  return {
    desde: desdeDate.toISOString().replace('T', ' ').slice(0, 19),
    hasta: hastaDate.toISOString().replace('T', ' ').slice(0, 19),
  };
}

/**
 * Valida que una compra tenga los campos indispensables
 */
export function validarCompra(compra: Partial<CompraCliente>): { valid: boolean; error?: string } {
  if (!compra.id_documento || typeof compra.id_documento !== 'number') {
    return { valid: false, error: 'id_documento inválido o ausente' };
  }
  const dni = normalizarDni(compra.dni);
  if (!dni) {
    return { valid: false, error: 'dni inválido o ausente' };
  }
  if (!compra.fecha || typeof compra.fecha !== 'string') {
    return { valid: false, error: 'fecha inválida o ausente' };
  }
  if (!Array.isArray(compra.items)) {
    return { valid: false, error: 'items debe ser un array' };
  }
  return { valid: true };
}
