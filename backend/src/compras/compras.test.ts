import test from 'node:test';
import assert from 'node:assert/strict';
import { calcularRangoFechas, normalizarDni, validarCompra, type CompraCliente } from './types';

test('normalizarDni: limpia puntos, guiones y espacios', () => {
  assert.equal(normalizarDni(' 39.675.372 '), '39675372');
  assert.equal(normalizarDni('20-39675372-1'), '20396753721');
  assert.equal(normalizarDni(39675372), '39675372');
  assert.equal(normalizarDni(''), '');
  assert.equal(normalizarDni(null), '');
  assert.equal(normalizarDni(undefined), '');
});

test('calcularRangoFechas: calcula ventanas 48h, 7d, 1m y personalizada', () => {
  const ref = new Date('2026-10-30T12:00:00.000Z');

  // 48h (default)
  const r48 = calcularRangoFechas('48h', null, null, ref);
  assert.equal(r48.hasta, '2026-10-30 12:00:00');
  assert.equal(r48.desde, '2026-10-28 12:00:00');

  // 7d
  const r7d = calcularRangoFechas('7d', null, null, ref);
  assert.equal(r7d.desde, '2026-10-23 12:00:00');

  // 1m (30 días)
  const r1m = calcularRangoFechas('1m', null, null, ref);
  assert.equal(r1m.desde, '2026-09-30 12:00:00');

  // Personalizada
  const custom = calcularRangoFechas('personalizada', '2026-08-01T00:00:00.000Z', '2026-08-15T23:59:59.000Z', ref);
  assert.equal(custom.desde, '2026-08-01 00:00:00');
  assert.equal(custom.hasta, '2026-08-15 23:59:59');

  // Infiere rango personalizado cuando se pasan fechas sin rango explícito
  const inferida = calcularRangoFechas(undefined, '2026-08-01T00:00:00.000Z', '2026-08-15T23:59:59.000Z', ref);
  assert.equal(inferida.desde, '2026-08-01 00:00:00');
  assert.equal(inferida.hasta, '2026-08-15 23:59:59');

  // Resistencia a strings inválidos (no debe lanzar RangeError: Invalid time value)
  const invalida = calcularRangoFechas('personalizada', 'saraza', 'otra-saraza', ref);
  assert.equal(invalida.hasta, '2026-10-30 12:00:00');
  assert.equal(invalida.desde, '2026-10-28 12:00:00');
});

test('validarCompra: rechaza compras incompletas o sin items', () => {
  const sinId: Partial<CompraCliente> = { dni: '39675372', fecha: '2026-10-20', items: [] };
  assert.equal(validarCompra(sinId).valid, false);

  const sinDni: Partial<CompraCliente> = { id_documento: 123, fecha: '2026-10-20', items: [] };
  assert.equal(validarCompra(sinDni).valid, false);

  const sinItems: Partial<CompraCliente> = { id_documento: 123, dni: '39675372', fecha: '2026-10-20' };
  assert.equal(validarCompra(sinItems).valid, false);

  const valida: CompraCliente = {
    id_documento: 190868,
    dni: '39675372',
    fecha: '2026-10-20T10:00:00',
    tipo_doc: 'Factura',
    total: 15000,
    items: [
      {
        codigo_unico: 'BAK2000',
        descripcion: 'ARTICULO TEST',
        cantidad: 2,
        precio_unitario: 7500,
        subtotal: 15000,
      },
    ],
  };
  assert.equal(validarCompra(valida).valid, true);
});
