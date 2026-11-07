// ============================================================================
// compras/types.ts — Tipos, modelos enriquecidos y validaciones para comprasED
// ============================================================================

export type EstadoCompra = 'DISPONIBLE' | 'PROCESADO' | 'ANULADO';
export type RangoFecha = '48h' | '7d' | '1m' | 'personalizada';
export type EstadoSyncCliente =
  | 'PENDIENTE_SYNC'
  | 'CLIENTE_NO_ENCONTRADO'
  | 'SIN_COMPRAS'
  | 'CON_COMPRAS';

export type ErrorCodeCompras =
  | 'DNI_INVALIDO'
  | 'DNI_NO_CONFIGURADO'
  | 'NO_REGISTRADO'
  | 'UNAUTHORIZED'
  | 'TOKEN_EXPIRED'
  | 'FORBIDDEN_DNI'
  | 'BAD_REQUEST'
  | 'RATE_LIMITED'
  | 'INTERNAL_ERROR';

export interface ItemCompra {
  codigo_unico: string;
  fabrica?: string;
  codigo?: string;
  descripcion: string;
  cantidad: number;
  precio_unitario: number;
  subtotal: number;
  iva?: number;
  descuento?: number;
}

export interface CompraCliente {
  id_documento: number;
  dni: string;
  fecha: string;
  tipo_doc: string;
  numero_comprobante?: string;
  cliente?: string;
  localidad?: string;
  sucursal?: string;
  moneda?: string;
  descuento?: number;
  porc_descuento?: number;
  forma_pago?: string;
  anulado?: boolean;
  nota_credito_de?: number | null;
  total: number;
  items: ItemCompra[];
  estado?: EstadoCompra;
  revision?: number;
  procesado_at?: string | null;
  procesado_por?: string | null;
  updated_at?: string;
}

export interface CompraClienteRow {
  id_documento: number;
  dni: string;
  fecha: string;
  tipo_doc: string;
  numero_comprobante?: string;
  cliente_nombre?: string;
  localidad?: string;
  sucursal?: string;
  moneda?: string;
  descuento?: number;
  porc_descuento?: number;
  forma_pago?: string;
  anulado?: number;
  nota_credito_de?: number | null;
  total: number;
  items_json: string;
  estado?: string;
  revision?: number;
  procesado_at?: string | null;
  procesado_por?: string | null;
  updated_at?: string;
}

export interface ClienteInteresado {
  dni: string;
  rango?: RangoFecha;
  fecha_desde?: string | null;
  fecha_hasta?: string | null;
  requested_at?: string;
  last_synced_at?: string | null;
  existe_en_mdb?: boolean | null;
  nombre_cliente?: string | null;
  estado_sync?: EstadoSyncCliente;
  pendientes?: number;
  total_compras?: number;
}

export interface ResumenComprasCliente {
  ok: true;
  dni: string;
  estado_sync: EstadoSyncCliente;
  existe_en_mdb: boolean | null;
  cliente_nombre: string | null;
  last_synced_at: string | null;
  pendientes: number;
  procesados: number;
  anulados: number;
  total_comprobantes: number;
  ultimo_id_documento: number | null;
  updated_at: string | null;
  hash: string;
}

export interface MetadataDniSincronizado {
  dni: string;
  existe_en_mdb?: boolean;
  nombre_cliente?: string;
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
 * Verifica si dos DNIs/CUITs pertenecen a la misma persona
 * (ej: DNI 32685684 vs CUIT 20326856849).
 */
export function dnisEquivalentes(a: string, b: string): boolean {
  const d1 = normalizarDni(a);
  const d2 = normalizarDni(b);
  if (!d1 || !d2) return false;
  if (d1 === d2) return true;
  if (d1.length === 11 && d1.slice(2, 10) === d2) return true;
  if (d2.length === 11 && d2.slice(2, 10) === d1) return true;
  return false;
}

/**
 * Calcula fechas ISO límites para un rango especificado.
 * Sanitiza fechas inválidas para evitar RangeError y auto-detecta rangos personalizados.
 */
export function calcularRangoFechas(
  rango: RangoFecha = '48h',
  desdeCustom?: string | null,
  hastaCustom?: string | null,
  referencia: Date = new Date()
): { desde: string; hasta: string } {
  let hastaDate: Date;
  if (hastaCustom) {
    const parsed = new Date(hastaCustom);
    hastaDate = isNaN(parsed.getTime()) ? referencia : parsed;
  } else {
    hastaDate = referencia;
  }

  // Si mandan desdeCustom o hastaCustom, inferir rango personalizado
  const esPersonalizada = rango === 'personalizada' || Boolean(desdeCustom || hastaCustom);

  let desdeDate: Date;
  if (esPersonalizada && desdeCustom) {
    const parsedDesde = new Date(desdeCustom);
    if (!isNaN(parsedDesde.getTime())) {
      desdeDate = parsedDesde;
    } else {
      // Fallback seguro a 48hs si mandaron string inválido
      desdeDate = new Date(hastaDate.getTime() - 48 * 60 * 60 * 1000);
    }
  } else if (rango === '7d') {
    desdeDate = new Date(hastaDate.getTime() - 7 * 24 * 60 * 60 * 1000);
  } else if (rango === '1m') {
    desdeDate = new Date(hastaDate.getTime() - 30 * 24 * 60 * 60 * 1000);
  } else {
    // Default 48hs
    desdeDate = new Date(hastaDate.getTime() - 48 * 60 * 60 * 1000);
  }

  // Invertir si desde es posterior a hasta
  if (desdeDate.getTime() > hastaDate.getTime()) {
    const temp = desdeDate;
    desdeDate = hastaDate;
    hastaDate = temp;
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
