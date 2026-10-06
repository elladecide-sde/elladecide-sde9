export const SINCE_EPOCH = '1970-01-01 00:00:00';
export const DEFAULT_LIMIT = 100;

/** Campos que admite ?fields= en GET /api/v1/sync. */
export const FIELDS_V1 = [
  'codigo_unico', 'nombre', 'precios', 'fabrica', 'codigo', 'descripcion', 'costo_neto', 'oferta',
] as const;

/** Campos que admite ?fields= en GET /api/v1/sync/v2 (modo predeterminado). */
export const FIELDS_V2 = [
  'codigo_unico', 'descripcion', 'precios', 'categoria', 'fabrica', 'codigo',
  'oferta', 'costo_neto', 'costo_iva', 'updated_at',
] as const;

/** Campos que admite ?fields= en GET /api/v1/sync/v2 (modo descripcion-costos). */
export const FIELDS_V2_DC = ['descripcion', 'costo'] as const;

export interface SyncQuery {
  fields?: string[];
  since?: string;
  page?: number;
  limit?: number;
}

export type SyncQueryResult = { ok: true; query: SyncQuery } | { ok: false; error: string };

export interface PageMeta {
  page: number;
  limit: number;
  total: number;
  totalPages: number;
  hasMore: boolean;
}

const SINCE_RE = /^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})$/;

/**
 * Normaliza ?since= a 'YYYY-MM-DD HH:MM:SS' UTC (el formato exacto de
 * datetime('now') en la columna updated_at). Acepta ISO con T, fracción y Z
 * o offset +00:00; '0' o vacío = época (trae todo). Devuelve null si es
 * inválido: comparar strings con un formato distinto mentiría en silencio.
 */
export function normalizeSince(raw: string): string | null {
  const v = raw.trim();
  if (v === '' || v === '0') return SINCE_EPOCH;

  let s = v.replace('T', ' ');
  const off = s.match(/(Z|[+-]\d{2}:?\d{2})$/i);
  if (off) {
    const esUtc = off[1].toUpperCase() === 'Z' || /^[+-]00:?00$/i.test(off[1]);
    if (!esUtc) return null;
    s = s.slice(0, s.length - off[1].length);
  }
  s = s.replace(/\.\d+$/, '').trim();

  const m = s.match(SINCE_RE);
  if (!m) return null;
  const n = (x: string) => Number(x);
  if (n(m[2]) < 1 || n(m[2]) > 12 || n(m[3]) < 1 || n(m[3]) > 31) return null;
  if (n(m[4]) > 23 || n(m[5]) > 59 || n(m[6]) > 59) return null;
  return s;
}

function intParam(name: string, raw: string): { value: number } | { error: string } {
  const t = raw.trim();
  if (!/^\d+$/.test(t) || Number(t) < 1) {
    return { error: `\`${name}\` debe ser un entero ≥ 1 (recibido: ${JSON.stringify(raw)})` };
  }
  return { value: Number(t) };
}

/**
 * Parsea los query params ADITIVOS de sync (?fields, ?page, ?limit, ?since).
 * Sin params → query vacía: el handler responde idéntico a antes de existir.
 * Cualquier valor inválido → 400 explícito (nunca degradar en silencio).
 */
export function parseSyncQuery(params: URLSearchParams, allowedFields: readonly string[]): SyncQueryResult {
  const query: SyncQuery = {};

  const rawFields = params.get('fields');
  if (rawFields !== null) {
    const list = [...new Set(rawFields.split(',').map((f) => f.trim()).filter(Boolean))];
    if (list.length === 0) {
      return { ok: false, error: `\`fields\` no puede estar vacío. Válidos: ${allowedFields.join(', ')}` };
    }
    const malos = list.filter((f) => !allowedFields.includes(f));
    if (malos.length > 0) {
      return { ok: false, error: `\`fields\` admite: ${allowedFields.join(', ')} (recibido: ${malos.join(', ')})` };
    }
    query.fields = list;
  }

  if (params.has('page') || params.has('limit')) {
    const page = params.has('page')
      ? intParam('page', params.get('page') ?? '')
      : { value: 1 };
    if ('error' in page) return { ok: false, error: page.error };
    const limit = params.has('limit')
      ? intParam('limit', params.get('limit') ?? '')
      : { value: DEFAULT_LIMIT };
    if ('error' in limit) return { ok: false, error: limit.error };
    query.page = page.value;
    query.limit = limit.value;
  }

  if (params.has('since')) {
    const s = normalizeSince(params.get('since') ?? '');
    if (s === null) {
      return { ok: false, error: '`since` debe ser UTC (ej: 2026-10-03T14:21:00Z), 0 o vacío (trae todo)' };
    }
    query.since = s;
  }

  return { ok: true, query };
}

/** Deja en la fila SOLO los campos pedidos. Sin fields → la fila completa. */
export function pickFields<T extends object>(row: T, fields?: string[]): Partial<T> {
  if (!fields) return row;
  const out: Partial<T> = {};
  for (const f of fields) {
    if (Object.prototype.hasOwnProperty.call(row, f)) out[f as keyof T] = row[f as keyof T];
  }
  return out;
}

export function pageMeta(page: number, limit: number, total: number): PageMeta {
  return {
    page,
    limit,
    total,
    totalPages: Math.max(1, Math.ceil(total / limit)),
    hasMore: page * limit < total,
  };
}
