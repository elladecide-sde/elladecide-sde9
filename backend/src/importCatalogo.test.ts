import { test } from 'node:test';
import assert from 'node:assert/strict';
import { importCatalogo } from './importCatalogo';

const FILAS = [
  ['DESCRIPCION', 'PRECIO', 'CATEGORIA', 'FABRICA'],                       // header (skip=1)
  ['BAKHOU 2000 LISO ALGODON HOMBRE', 1800, 'BOMBACHA', 'BAKHOU'],
  ['OTRA MARCA 452 PANTALON', 2500, 'PANTALON', 'OTRA'],
];

test('import: conserva costo_neto, costo_iva y las 4 listas (nada se descarta)', () => {
  const r = importCatalogo(FILAS, { colNombre: 0, colPrecio: 1, skip: 1 });
  assert.equal(r.productos.length, 2);
  for (const p of r.productos) {
    assert.equal(typeof p.descripcion, 'string');
    assert.ok(p.descripcion.length > 0);
    assert.ok(Number.isFinite(p.costo_neto) && p.costo_neto > 0, 'costo_neto');
    assert.ok(Number.isFinite(p.costo_iva) && p.costo_iva > 0, 'costo_iva');
    assert.equal(p.precios.length, 4, 'lista1..lista4');
    assert.ok(p.precios.every((n) => Number.isFinite(n) && n > 0));
    // costo + IVA tiene que ser menor que la L4 y mayor que el costo neto.
    assert.ok(p.costo_neto < p.costo_iva);
    assert.ok(p.costo_iva < p.precios[3]);
  }
});

test('import: carga categoria y fabrica desde las columnas pedidas', () => {
  const r = importCatalogo(FILAS, { colNombre: 0, colPrecio: 1, skip: 1, colCategoria: 2, colFabrica: 3 });
  const porDesc = new Map(r.productos.map((p) => [p.descripcion, p]));
  assert.equal(porDesc.get('BAKHOU 2000 LISO ALGODON HOMBRE')?.categoria, 'BOMBACHA');
  assert.equal(porDesc.get('BAKHOU 2000 LISO ALGODON HOMBRE')?.fabrica, 'BAKHOU');
  assert.equal(porDesc.get('OTRA MARCA 452 PANTALON')?.categoria, 'PANTALON');
  assert.equal(porDesc.get('OTRA MARCA 452 PANTALON')?.fabrica, 'OTRA');
});

test('import: sin columnas de categoría/fábrica quedan vacías (no inventadas)', () => {
  const r = importCatalogo(FILAS, { colNombre: 0, colPrecio: 1, skip: 1 });
  assert.ok(r.productos.every((p) => p.categoria === '' && p.fabrica === ''));
});

test('import: oferta (nombre que termina en ".") → 4 listas iguales, costos derivados', () => {
  const filas = [
    ['h', 'p'],
    ['PRODUCTO EN OFERTA 999.', 1200],
  ];
  const r = importCatalogo(filas, { colNombre: 0, colPrecio: 1, skip: 1 });
  assert.equal(r.productos.length, 1);
  const p = r.productos[0];
  assert.deepEqual(p.precios, [1200, 1200, 1200, 1200]);
  assert.ok(p.costo_iva > 0 && p.costo_neto > 0, 'los costos igual se derivan');
  assert.ok(Math.abs(p.costo_iva - 1200 / 1.5) < 1e-9);
});
