// ============================================================================
// compras/types.ts — Tipos y validaciones para el módulo de comprasED
// ============================================================================

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
}

export interface CompraClienteRow {
  id_documento: number;
  dni: string;
  fecha: string;
  tipo_doc: string;
  total: number;
  items_json: string;
  updated_at?: string;
}

export interface ClienteInteresado {
  dni: string;
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
