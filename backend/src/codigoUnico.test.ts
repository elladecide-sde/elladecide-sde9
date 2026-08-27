import { test } from 'node:test';
import assert from 'node:assert/strict';
import { asignarCodigos, nombreNormalizado, partir, abrev, tokens } from './codigoUnico.ts';

function asignar(nombres: string[], opts?: Parameters<typeof asignarCodigos>[1]) {
  return asignarCodigos(nombres.map((nombre) => ({ nombre })), opts).codigos;
}

test('tokens normaliza y limpia', () => {
  assert.deepEqual(tokens("BENTURA'S 804 BABY DOLL LIQ."), ['BENTURA', 'S', '804', 'BABY', 'DOLL', 'LIQ.']);
});

test('partir extrae marca + modelo completo', () => {
  const p = partir('BIDDEN 452-1 JULIETTE LESS BOMBACHA LIQ.');
  assert.deepEqual(p.marca, ['BIDDEN']);
  assert.equal(p.codigo, '452-1');
  assert.deepEqual(p.resto, ['JULIETTE', 'LESS', 'BOMBACHA', 'LIQ.']);
});

test('partir sin modelo deja todo en resto', () => {
  const p = partir('SHEDYL BODY LIQ.');
  assert.equal(p.codigo, '');
  assert.deepEqual(p.marca, ['SHEDYL']);
  assert.deepEqual(p.resto, ['BODY', 'LIQ.']);
});

test('partir usa la lista de marcas (longest prefix)', () => {
  const p = partir('AVRIL KAURY 100 PACKS REGULABLE PUNT', {
    marcas: ['AVRIL KAURY', 'AVRIL'],
  });
  assert.deepEqual(p.marca, ['AVRIL', 'KAURY']);
  assert.equal(p.codigo, '100');
});

test('abrev corta cada palabra', () => {
  assert.equal(abrev(['BAKHOU']), 'BAK');
  assert.equal(abrev(['AVRIL', 'KAURY']), 'AVRKAU');
});

test('regla base: abrev(marca) + modelo', () => {
  const c = asignar(['BAKHOU 2000 LISO ALGODON HOMBRE']);
  assert.equal(c.get(nombreNormalizado('BAKHOU 2000 LISO ALGODON HOMBRE')), 'BAK2000');
});

test('modelo completo separa sub-variantes', () => {
  const c = asignar([
    'BIDDEN 452-1 JULIETTE LESS BOMBACHA LIQ.',
    'BIDDEN 452-11 HILO ABERTURA BOMBACHA LIQ.',
  ]);
  const a = c.get(nombreNormalizado('BIDDEN 452-1 JULIETTE LESS BOMBACHA LIQ.'));
  const b = c.get(nombreNormalizado('BIDDEN 452-11 HILO ABERTURA BOMBACHA LIQ.'));
  assert.equal(a, 'BID452-1');
  assert.equal(b, 'BID452-11');
  assert.notEqual(a, b);
});

test('sin modelo: entra la descripción para separar', () => {
  const c = asignar(['SHEDYL BODY LIQ.', 'SHEDYL CALZA 3/4']);
  const a = c.get(nombreNormalizado('SHEDYL BODY LIQ.'));
  const b = c.get(nombreNormalizado('SHEDYL CALZA 3/4'));
  assert.ok(a && b);
  assert.ok(a.startsWith('SHEBOD'));
  assert.ok(b.startsWith('SHECAL'));
  assert.notEqual(a, b);
});

test('variantes con mismo marca+modelo se separan por resto', () => {
  const c = asignar([
    'CRIS LATINA 1002 C. SOFT MICRO BASE LESS',
    'CRIS LATINA 1002 SOFT MICRO BASE LESS',
    'CRIS LATINA 1002E C. SOFT MICRO',
  ]);
  const cods = new Set([
    c.get(nombreNormalizado('CRIS LATINA 1002 C. SOFT MICRO BASE LESS')),
    c.get(nombreNormalizado('CRIS LATINA 1002 SOFT MICRO BASE LESS')),
    c.get(nombreNormalizado('CRIS LATINA 1002E C. SOFT MICRO')),
  ]);
  assert.equal(cods.size, 3);
});

test('determinismo: mismo input → mismo resultado', () => {
  const nombres = ['BAKHOU 2000 LISO', 'BELEN 3765 SOFT', 'SHEDYL BODY LIQ.', 'SHEDYL CALZA 3/4'];
  const a = asignar(nombres);
  const b = asignar(nombres);
  for (const n of nombres) {
    assert.equal(a.get(nombreNormalizado(n)), b.get(nombreNormalizado(n)));
  }
});

test('independencia del orden: shuffle no cambia los códigos', () => {
  const nombres = [
    'BAKHOU 2000 LISO ALGODON HOMBRE',
    'BELEN CONJUNTO ARMADO 1420',
    'BIDDEN 452-1 JULIETTE LESS BOMBACHA LIQ.',
    'BIDDEN 452-11 HILO ABERTURA BOMBACHA LIQ.',
    'SHEDYL BODY LIQ.',
    'SHEDYL CALZA 3/4',
    'CRIS LATINA 1002 C. SOFT MICRO BASE LESS',
    'CRIS LATINA 1002E C. SOFT MICRO',
  ];
  const a = asignar(nombres);
  const b = asignar([...nombres].reverse());
  for (const n of nombres) {
    const k = nombreNormalizado(n);
    assert.equal(a.get(k), b.get(k), `divergencia en: ${n}`);
  }
});

test('duplicados normalizados: uno se descarta y comparten código', () => {
  const { codigos, descartados } = asignarCodigos([
    { nombre: "BENTURA'S ARMADO/SOFT LIQ." },
    { nombre: 'BENTURA´S ARMADO/SOFT LIQ.' },
  ]);
  assert.equal(descartados.length, 1);
  assert.equal(codigos.size, 1);
});

test('hash fallback: colisión irresoluble con maxResto=0', () => {
  const { codigos } = asignarCodigos(
    [{ nombre: 'Z 100 ALFA' }, { nombre: 'Z 100 BETA' }],
    { maxResto: 0, maxLen: 3 },
  );
  const a = codigos.get(nombreNormalizado('Z 100 ALFA'));
  const b = codigos.get(nombreNormalizado('Z 100 BETA'));
  assert.ok(a && b);
  assert.match(a, /^Z100-[0-9A-F]{4}$/);
  assert.match(b, /^Z100-[0-9A-F]{4}$/);
  assert.notEqual(a, b);
});

test('código completo del catálogo real: 0 colisiones', () => {
  const nombres = [
    'AGATHA DAMA BIKINIS LIQ.',
    'BELEN 3765 C. SOFT PUNT PUSH BRETEL LESS',
    'BRIGITTE 1084 C. SOFT PUNTILLA LESS',
    'AVRIL KAURY 1003 C. SOFT TRIANG PUNT LESS',
    'BAKHOU 2000 LISO ALGODON HOMBRE',
    'BENTURA\'S 804 BABY DOLL LIQ.',
    'BIDDEN 110 SECRETARIA SEXY DISFRAZ LIQ.',
    'BOXER ACROBATA 5017 ESTAMPADO CAMUFLADO HOMBRE',
    'BELEN 1610 PACKS COLALESS ALGODON PUNTILLA',
    'ALMENDRA 202 EXTENSOR FINO',
  ];
  const { codigos } = asignarCodigos(nombres.map((nombre) => ({ nombre })));
  assert.equal(codigos.size, new Set(codigos.values()).size);
});