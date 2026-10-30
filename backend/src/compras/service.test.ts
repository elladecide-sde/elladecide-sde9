import test from 'node:test';
import assert from 'node:assert/strict';
import { ComprasService } from './service';
import type { CompraCliente } from './types';

// Mock simple de D1PreparedStatement y D1Database para pruebas en memoria
class MockPreparedStatement {
  constructor(private sql: string, private params: unknown[] = []) {}

  bind(...args: unknown[]) {
    return new MockPreparedStatement(this.sql, args);
  }

  async all() {
    return { results: [] };
  }

  async first() {
    return null;
  }
}

class MockD1Database {
  public batchCalls: unknown[][] = [];
  public prepareCalls: string[] = [];

  prepare(query: string) {
    this.prepareCalls.push(query);
    return new MockPreparedStatement(query);
  }

  async batch(statements: unknown[]) {
    this.batchCalls.push(statements);
    return statements.map(() => ({ results: [], success: true }));
  }
}

test('ComprasService: registrarInteresados procesa y deduplica DNIs con rango', async () => {
  const mockDb = new MockD1Database();
  const service = new ComprasService(mockDb as unknown as D1Database);

  const res = await service.registrarInteresados([' 39.675.372 ', '39675372', '40-123-456'], '7d');
  assert.equal(res.agregados, 2); // 39675372 duplicado se unifica
  assert.equal(mockDb.batchCalls.length, 1);
  assert.equal(mockDb.batchCalls[0].length, 2);
});

test('ComprasService: registrarInteresados ignora vacíos o inválidos', async () => {
  const mockDb = new MockD1Database();
  const service = new ComprasService(mockDb as unknown as D1Database);

  const res = await service.registrarInteresados(['', '   ', '---']);
  assert.equal(res.agregados, 0);
  assert.equal(mockDb.batchCalls.length, 0);
});

test('ComprasService: guardarComprasBatch separa válidas e inicializa estado DISPONIBLE', async () => {
  const mockDb = new MockD1Database();
  const service = new ComprasService(mockDb as unknown as D1Database);

  const compras: CompraCliente[] = [
    {
      id_documento: 1001,
      dni: '39.675.372',
      fecha: '2026-10-30T10:00:00',
      tipo_doc: 'Factura A',
      total: 5000,
      items: [
        {
          codigo_unico: 'BAK2000',
          descripcion: 'BOXER BAKHOU 2000',
          cantidad: 1,
          precio_unitario: 5000,
          subtotal: 5000,
        },
      ],
    },
    {
      id_documento: 0, // inválido
      dni: '123',
      fecha: '2026-10-30',
      tipo_doc: '',
      total: 0,
      items: [],
    },
  ];

  const res = await service.guardarComprasBatch(compras);
  assert.equal(res.guardadas, 1);
  assert.equal(res.errores.length, 1);
  assert.equal(mockDb.batchCalls.length, 1);
});

test('ComprasService: confirmarProcesados cambia estado a PROCESADO con timestamp', async () => {
  const mockDb = new MockD1Database();
  const service = new ComprasService(mockDb as unknown as D1Database);

  const res = await service.confirmarProcesados([1001, 1002]);
  assert.equal(res.confirmados, 2);
  assert.equal(mockDb.batchCalls.length, 1);
  assert.equal(mockDb.batchCalls[0].length, 2);
});
