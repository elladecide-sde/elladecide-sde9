import test from 'node:test';
import assert from 'node:assert/strict';
import { normalizarDni, validarCompra, type CompraCliente } from './types';

test('normalizarDni: limpia puntos, guiones y espacios', () => {
  assert.equal(normalizarDni(' 39.675.372 '), '39675372');
  assert.equal(normalizarDni('20-39675372-1'), '20396753721');
  assert.equal(normalizarDni(39675372), '39675372');
  assert.equal(normalizarDni(''), '');
  assert.equal(normalizarDni(null), '');
  assert.equal(normalizarDni(undefined), '');
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
