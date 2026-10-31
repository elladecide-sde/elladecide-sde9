import test from 'node:test';
import assert from 'node:assert/strict';
import { signJwt } from '../auth';
import { handleComprasRoutes, type ComprasEnv } from './routes';

const JWT_SECRET = 'super-secret-key-para-tests-de-seguridad-12345';
const COMPRAS_API_KEY = 'ella-secret-api-key-test-2026';

class MockD1Database {
  prepare() {
    return {
      bind: () => ({
        all: async () => ({ results: [] }),
        first: async () => null,
      }),
    };
  }
  async batch() {
    return [];
  }
}

test('handleComprasRoutes: rechaza requests sin auth cuando las claves están configuradas', async () => {
  const env: ComprasEnv = {
    DB: new MockD1Database() as unknown as D1Database,
    JWT_SECRET,
    COMPRAS_API_KEY,
  };

  // 1. GET /api/v1/compras/interesados sin Bearer token -> 401
  const reqInteresados = new Request('https://test.local/api/v1/compras/interesados', { method: 'GET' });
  const resInteresados = await handleComprasRoutes(reqInteresados, env, new URL(reqInteresados.url), {});
  assert.equal(resInteresados?.status, 401);

  // 2. GET /api/v1/comprasED sin API key ni Bearer -> 401
  const reqCompras = new Request('https://test.local/api/v1/comprasED?dni=39675372', { method: 'GET' });
  const resCompras = await handleComprasRoutes(reqCompras, env, new URL(reqCompras.url), {});
  assert.equal(resCompras?.status, 401);

  // 3. POST /api/v1/compras/batch sin Bearer token -> 401
  const reqBatch = new Request('https://test.local/api/v1/compras/batch', {
    method: 'POST',
    body: JSON.stringify({ compras: [] }),
  });
  const resBatch = await handleComprasRoutes(reqBatch, env, new URL(reqBatch.url), {});
  assert.equal(resBatch?.status, 401);
});

test('handleComprasRoutes: permite acceso a comprasED con x-api-key válida', async () => {
  const env: ComprasEnv = {
    DB: new MockD1Database() as unknown as D1Database,
    JWT_SECRET,
    COMPRAS_API_KEY,
  };

  const req = new Request('https://test.local/api/v1/comprasED?dni=39675372', {
    method: 'GET',
    headers: { 'x-api-key': COMPRAS_API_KEY },
  });
  const res = await handleComprasRoutes(req, env, new URL(req.url), {});
  assert.equal(res?.status, 200);
});

test('handleComprasRoutes: permite acceso a compras/batch con Bearer JWT válido', async () => {
  const env: ComprasEnv = {
    DB: new MockD1Database() as unknown as D1Database,
    JWT_SECRET,
    COMPRAS_API_KEY,
  };

  const token = await signJwt(JWT_SECRET, { sub: 'admin' }, 3600);
  const req = new Request('https://test.local/api/v1/compras/batch', {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}` },
    body: JSON.stringify({ compras: [] }),
  });
  const res = await handleComprasRoutes(req, env, new URL(req.url), {});
  assert.equal(res?.status, 200);
});
