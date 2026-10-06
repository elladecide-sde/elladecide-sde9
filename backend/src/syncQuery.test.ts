import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  DEFAULT_LIMIT,
  FIELDS_V1,
  normalizeSince,
  pageMeta,
  parseSyncQuery,
  pickFields,
  SINCE_EPOCH,
} from './syncQuery';

const parse = (qs: string) => parseSyncQuery(new URLSearchParams(qs), FIELDS_V1);

test('sin query params → query vacía (handler responde idéntico a antes)', () => {
  const r = parse('');
  assert.equal(r.ok, true);
  if (r.ok) assert.deepEqual(r.query, {});
});

test('fields: limpia, recorta espacios y deduplica', () => {
  const r = parse('fields=codigo_unico, descripcion ,codigo_unico,');
  assert.equal(r.ok, true);
  if (r.ok) assert.deepEqual(r.query.fields, ['codigo_unico', 'descripcion']);
});

test('fields: desconocido → error con la lista de válidos', () => {
  const r = parse('fields=nope,codigo_unico');
  assert.equal(r.ok, false);
  if (!r.ok) {
    assert.match(r.error, /nope/);
    assert.match(r.error, /codigo_unico/);
  }
});

test('fields: vacío → error', () => {
  const r = parse('fields=');
  assert.equal(r.ok, false);
});

test('page/limit: defaults (page=1, limit=100) cuando falta uno', () => {
  const a = parse('page=3');
  assert.equal(a.ok, true);
  if (a.ok) assert.deepEqual({ page: a.query.page, limit: a.query.limit }, { page: 3, limit: DEFAULT_LIMIT });

  const b = parse('limit=10');
  assert.equal(b.ok, true);
  if (b.ok) assert.deepEqual({ page: b.query.page, limit: b.query.limit }, { page: 1, limit: 10 });
});

test('page/limit: inválidos (0, negativo, decimal, texto, vacío) → error', () => {
  for (const qs of ['page=0', 'page=-1', 'page=2.5', 'page=abc', 'page=', 'limit=0', 'limit=x']) {
    const r = parse(qs);
    assert.equal(r.ok, false, `esperaba error para ${qs}`);
  }
});

test('since: 0 o vacío → época (trae todo)', () => {
  assert.equal(normalizeSince('0'), SINCE_EPOCH);
  assert.equal(normalizeSince(''), SINCE_EPOCH);
  assert.equal(normalizeSince('   '), SINCE_EPOCH);
});

test('since: ISO con T, fracción y Z → normalizado al formato de updated_at', () => {
  assert.equal(normalizeSince('2026-10-03T14:21:00Z'), '2026-10-03 14:21:00');
  assert.equal(normalizeSince('2026-10-03T14:21:00.123Z'), '2026-10-03 14:21:00');
  assert.equal(normalizeSince('2026-10-03 14:21:00'), '2026-10-03 14:21:00');
  assert.equal(normalizeSince('2026-10-03T14:21:00+00:00'), '2026-10-03 14:21:00');
});

test('since: offset no UTC → inválido (cambiaría el significado)', () => {
  assert.equal(normalizeSince('2026-10-03T14:21:00+03:00'), null);
  assert.equal(normalizeSince('2026-10-03T14:21:00-05:00'), null);
});

test('since: basura o rangos imposibles → inválido', () => {
  for (const raw of ['mañana', '2026-13-01 00:00:00', '2026-00-31 00:00:00', '2026-10-32 00:00:00', '2026-10-03 25:00:00', '2026-10-03 12:61:00', '2026-10-03 12:00:61', '2026/10/03 12:00:00']) {
    assert.equal(normalizeSince(raw), null, `esperaba inválido para ${raw}`);
  }
});

test('combinación: fields + since + paginación en la misma query', () => {
  const r = parse('fields=codigo_unico&since=2026-10-03T14:21:00Z&page=2&limit=50');
  assert.equal(r.ok, true);
  if (r.ok) {
    assert.deepEqual(r.query, {
      fields: ['codigo_unico'],
      page: 2,
      limit: 50,
      since: '2026-10-03 14:21:00',
    });
  }
});

test('pickFields: sin fields → fila completa; con fields → solo esas', () => {
  const fila = { codigo_unico: 'ABC1', descripcion: 'Tornillo', costo_neto: 10, oferta: true };
  assert.equal(pickFields(fila, undefined), fila);
  assert.deepEqual(pickFields(fila, ['codigo_unico', 'oferta']), { codigo_unico: 'ABC1', oferta: true });
});

test('pageMeta: totalPages y hasMore', () => {
  assert.deepEqual(pageMeta(2, 100, 250), { page: 2, limit: 100, total: 250, totalPages: 3, hasMore: true });
  assert.deepEqual(pageMeta(3, 100, 250), { page: 3, limit: 100, total: 250, totalPages: 3, hasMore: false });
  assert.deepEqual(pageMeta(9, 100, 0), { page: 9, limit: 100, total: 0, totalPages: 1, hasMore: false });
});
