import { test } from 'node:test';
import assert from 'node:assert/strict';
import { canonicalCatalogJson, computeCatalogHash } from './hash';

// Fixture con casos que ejercitan la forma canónica:
//  - códigos con . - dígitos y mayúsculas/minúsculas (orden por code units)
//  - nombre con acento, comilla, backslash, salto de línea y tab (escapado)
// Los códigos son ÚNICOS (PRIMARY KEY del schema), como en un catálogo real.
const FIXTURE = [
  { codigo_unico: 'B-2', nombre: 'Leche Entera 1L', precios: [123, 140, 160, 180] },
  { codigo_unico: 'a-10', nombre: 'Aceite Girasol 900cc', precios: [900, 1000, 1100, 1200] },
  { codigo_unico: 'A-2', nombre: 'Caja "Fiesta" 12un', precios: [456, 500, 550, 600] },
  { codigo_unico: '104.3', nombre: 'Archivo\\Prueba\nSegunda', precios: [70, 80, 90, 100] },
  { codigo_unico: '452-1', nombre: 'Té\tFrutilla', precios: [50, 55, 60, 65] },
  { codigo_unico: 'C-3', nombre: 'Colisión intencional', precios: [1, 2, 3, 4] },
];

test('forma canónica: orden por code units UTF-16 + JSON sin espacios', () => {
  assert.equal(
    canonicalCatalogJson(FIXTURE),
    String.raw`[{"codigo_unico":"104.3","nombre":"Archivo\\Prueba\nSegunda","precios":[70,80,90,100]},{"codigo_unico":"452-1","nombre":"Té\tFrutilla","precios":[50,55,60,65]},{"codigo_unico":"A-2","nombre":"Caja \"Fiesta\" 12un","precios":[456,500,550,600]},{"codigo_unico":"B-2","nombre":"Leche Entera 1L","precios":[123,140,160,180]},{"codigo_unico":"C-3","nombre":"Colisión intencional","precios":[1,2,3,4]},{"codigo_unico":"a-10","nombre":"Aceite Girasol 900cc","precios":[900,1000,1100,1200]}]`,
  );
});

test('hash estable y determinístico (golden — el port de Android debe replicarlo)', async () => {
  const a = await computeCatalogHash(FIXTURE);
  const b = await computeCatalogHash([...FIXTURE].reverse());
  assert.equal(a, 'd6a08778c06410ab22d08b11db7451b92e391d06978a89254827399afa8405ef');
  assert.equal(b, a); // el orden de entrada no debe alterar el hash
});