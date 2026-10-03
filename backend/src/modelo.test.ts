import { test } from 'node:test';
import assert from 'node:assert/strict';
import { calcularCostos, cfgDesde, derivarCostos, fabPersistida, PRECIOS_DEFAULT, SIN_FABRICA } from './modelo';

// Fórmula LEGACY tal como estaba hardcodeada en app.js / importCatalogo.ts:
//   costo = L4 / (1.21 * 1.50)
//   lista = round(costo * 1.21 * factor)
// Es la referencia: si la versión parametrizada se despeja de acá, se movieron
// precios y el hash del catálogo cambia sin que nadie lo pida.
const LEGACY_LISTAS = [2.32, 2.12, 1.65, 1.5];
function legacyPrecios(L4: number): number[] {
  const IVA = 1 + 21 / 100;
  const IVA_INVERSA = 1 + 21 / 100;
  const LISTA4_FACTOR = IVA_INVERSA * (1 + 50 / 100);
  const costo = L4 / LISTA4_FACTOR;
  return LEGACY_LISTAS.map((f) => Math.round(costo * IVA * f));
}

test('defaults: resultado IDÉNTICO al cálculo legacy (nada cambia de valores)', () => {
  for (let L4 = 1; L4 <= 5000; L4++) {
    const ahora = calcularCostos(L4, PRECIOS_DEFAULT);
    assert.deepEqual(ahora.precios, legacyPrecios(L4), `L4=${L4}`);
  }
});

test('defaults: costo_neto = L4 / (1,21 × 1,50) y costo_iva = L4 / 1,50', () => {
  for (const L4 of [1, 25, 999, 12345.67]) {
    const c = calcularCostos(L4, PRECIOS_DEFAULT);
    assert.equal(c.costo_neto, L4 / (1.21 * 1.5));
    assert.equal(c.costo_iva, L4 / 1.5);
    // Cadenas equivalentes (matemáticamente, no bit a bit).
    assert.ok(Math.abs(c.costo_neto - c.costo_iva / 1.21) < 1e-9);
  }
});

test('la L4 se deriva del factor inversa: el precio del Excel vuelve como Lista 4', () => {
  const cfg = cfgDesde({ ...PRECIOS_DEFAULT, inversaLista: 50 });
  assert.equal(cfg.listas[3], 1.5);
  for (const L4 of [100, 1899, 45000]) {
    const c = calcularCostos(L4, cfg);
    assert.equal(c.precios[3], Math.round(L4), `L4=${L4}`);
  }
});

test('IVA configurable: cambia las listas pero no el costo neto', () => {
  const base = calcularCostos(1000, PRECIOS_DEFAULT);
  const al27 = calcularCostos(1000, cfgDesde({ ...PRECIOS_DEFAULT, iva: 27 }));
  assert.notDeepEqual(al27.precios, base.precios);
  assert.equal(al27.costo_neto, base.costo_neto); // el costo sale de la L4, no del IVA
});

test('factor de la L4 configurable: cambia el costo neto', () => {
  const base = calcularCostos(1000, PRECIOS_DEFAULT);
  const al60 = calcularCostos(1000, cfgDesde({ ...PRECIOS_DEFAULT, inversaLista: 60 }));
  assert.ok(al60.costo_neto < base.costo_neto); // factor más alto → costo más bajo
  assert.equal(al60.costo_iva, 1000 / 1.6);
});

test('derivarCostos recupera los costos desde las 4 listas', () => {
  const c = calcularCostos(2500, PRECIOS_DEFAULT);
  const d = derivarCostos(c.precios, PRECIOS_DEFAULT);
  // Redondeo de la L4: se tolera ±1 porque `precios` viene redondeado a entero.
  assert.ok(Math.abs(d.costo_iva - c.costo_iva) <= 1);
  assert.ok(Math.abs(d.costo_neto - c.costo_neto) <= 1);
});

test('cfgDesde completa el factor de la L4 aunque venga un array de 3', () => {
  const cfg = cfgDesde({ listas: [2, 1.9, 1.7] });
  assert.equal(cfg.listas.length, 4);
  assert.equal(cfg.listas[3], 1.5);
});

test('fábrica: vacía o en blanco → SIN_FABRICA (ningún artículo queda sin fábrica)', () => {
  assert.equal(SIN_FABRICA, 'SIN FABRICA');
  assert.equal(fabPersistida(''), SIN_FABRICA);
  assert.equal(fabPersistida('   '), SIN_FABRICA);
  assert.equal(fabPersistida(SIN_FABRICA), SIN_FABRICA);
  assert.equal(fabPersistida('BAKHOU'), 'BAKHOU');
  assert.equal(fabPersistida('  BAKHOU  '), 'BAKHOU');
});
