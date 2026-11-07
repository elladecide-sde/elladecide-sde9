import test from 'node:test';
import assert from 'node:assert/strict';
import { signJwt } from '../auth';
import { clearCachesForTests } from './firebaseAuth';
import { handleComprasRoutes, type ComprasEnv } from './routes';

const JWT_SECRET = 'super-secret-key-para-tests-de-seguridad-12345';
const COMPRAS_API_KEY = 'ella-secret-api-key-test-2026';
const FIREBASE_PROJECT_ID = 'ella-decide-firebase-prod';

function b64urlEncode(bytes: Uint8Array): string {
  let bin = '';
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function b64urlJson(obj: unknown): string {
  return b64urlEncode(new TextEncoder().encode(JSON.stringify(obj)));
}

async function createMockFirebaseIdToken(
  privateKey: CryptoKey,
  uid: string,
  projectId = FIREBASE_PROJECT_ID,
  extraClaims: Record<string, unknown> = {}
): Promise<string> {
  const nowSec = Math.floor(Date.now() / 1000);
  const header = { alg: 'RS256', kid: 'test-firebase-kid-1', typ: 'JWT' };
  const payload = {
    iss: `https://securetoken.google.com/${projectId}`,
    aud: projectId,
    sub: uid,
    iat: nowSec - 10,
    exp: nowSec + 3600,
    ...extraClaims,
  };
  const signingInput = `${b64urlJson(header)}.${b64urlJson(payload)}`;
  const sig = await crypto.subtle.sign(
    'RSASSA-PKCS1-v1_5',
    privateKey,
    new TextEncoder().encode(signingInput)
  );
  return `${signingInput}.${b64urlEncode(new Uint8Array(sig))}`;
}

class MockD1Database {
  public mockRows: unknown[] = [];

  prepare(sql: string) {
    return {
      bind: (...params: unknown[]) => ({
        all: async () => {
          if (sql.includes('SELECT id_documento, dni, estado FROM compras_cliente')) {
            return {
              results: [
                { id_documento: 189130, dni: '32685684', estado: 'DISPONIBLE' },
                { id_documento: 189131, dni: '32685684', estado: 'PROCESADO' },
              ],
            };
          }
          return { results: this.mockRows };
        },
        first: async () => {
          if (sql.includes('COUNT(CASE WHEN estado')) {
            return {
              pendientes: 2,
              procesados: 5,
              anulados: 0,
              total_comprobantes: 7,
              ultimo_id_documento: 189130,
              updated_at: '2026-11-07 12:00:00',
              suma_revisiones: 7,
              cliente_nombre: 'ERIKA MARIEL IBARRA RUIZ',
            };
          }
          return {
            dni: String(params[0] || '32685684'),
            last_synced_at: '2026-11-07 12:00:00',
            existe_en_mdb: 1,
            nombre_cliente: 'ERIKA MARIEL IBARRA RUIZ',
          };
        },
        run: async () => ({ meta: { changes: 1 } }),
      }),
    };
  }
  async batch(stmts: unknown[]) {
    return stmts.map(() => ({ results: [], success: true, meta: { changes: 1 } }));
  }
}

test('handleComprasRoutes: rechaza requests sin auth con errores consistentes { ok: false, code, message }', async () => {
  clearCachesForTests();
  const env: ComprasEnv = {
    DB: new MockD1Database() as unknown as D1Database,
    JWT_SECRET,
    COMPRAS_API_KEY,
    FIREBASE_PROJECT_ID,
  };

  const reqInteresados = new Request('https://test.local/api/v1/compras/interesados', { method: 'GET' });
  const resInteresados = await handleComprasRoutes(reqInteresados, env, new URL(reqInteresados.url), {});
  assert.equal(resInteresados?.status, 401);
  const errBody = (await resInteresados?.json()) as { ok: boolean; code: string; message: string };
  assert.equal(errBody.ok, false);
  assert.equal(errBody.code, 'UNAUTHORIZED');

  const reqCompras = new Request('https://test.local/api/v1/comprasED?dni=39675372', { method: 'GET' });
  const resCompras = await handleComprasRoutes(reqCompras, env, new URL(reqCompras.url), {});
  assert.equal(resCompras?.status, 401);

  const reqBatch = new Request('https://test.local/api/v1/compras/batch', {
    method: 'POST',
    body: JSON.stringify({ compras: [] }),
  });
  const resBatch = await handleComprasRoutes(reqBatch, env, new URL(reqBatch.url), {});
  assert.equal(resBatch?.status, 401);
});

test('handleComprasRoutes: permite acceso administrativo con x-api-key válida', async () => {
  clearCachesForTests();
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

test('handleComprasRoutes: verifica Firebase ID Token (RS256), lee users/{uid}.dni en Firestore REST y restringe al propio DNI', async () => {
  clearCachesForTests();
  const keyPair = await crypto.subtle.generateKey(
    {
      name: 'RSASSA-PKCS1-v1_5',
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: 'SHA-256',
    },
    true,
    ['sign', 'verify']
  );

  const fbToken = await createMockFirebaseIdToken(keyPair.privateKey, 'uid_erika_123');

  let firestoreAuthHeaderUsed = '';
  const mockFetch: typeof fetch = async (input, init) => {
    const urlStr = String(input);
    if (urlStr.includes('firestore.googleapis.com') && urlStr.includes('/documents/users/uid_erika_123')) {
      firestoreAuthHeaderUsed = String((init?.headers as Record<string, string>)?.Authorization || '');
      return Response.json({
        name: `projects/${FIREBASE_PROJECT_ID}/databases/(default)/documents/users/uid_erika_123`,
        fields: {
          dni: { stringValue: '32.685.684' },
        },
      });
    }
    return new Response('Not found', { status: 404 });
  };

  const env: ComprasEnv = {
    DB: new MockD1Database() as unknown as D1Database,
    JWT_SECRET,
    COMPRAS_API_KEY,
    FIREBASE_PROJECT_ID,
    __TEST_FETCH: mockFetch,
    __TEST_FIREBASE_PUBLIC_KEY: keyPair.publicKey,
  };

  // 1. Consulta sin ?dni= -> resuelve automáticamente el DNI 32685684 desde Firestore
  const reqOwn = new Request('https://test.local/api/v1/comprasED', {
    method: 'GET',
    headers: { Authorization: `Bearer ${fbToken}` },
  });
  const resOwn = await handleComprasRoutes(reqOwn, env, new URL(reqOwn.url), {});
  assert.equal(resOwn?.status, 200);
  assert.equal(firestoreAuthHeaderUsed, `Bearer ${fbToken}`);
  const dataOwn = (await resOwn?.json()) as { ok: boolean; dni: string; estado_sync: string };
  assert.equal(dataOwn.ok, true);
  assert.equal(dataOwn.dni, '32685684');

  // 2. Intento de consultar otro DNI con el token de Erika -> 403 FORBIDDEN_DNI
  const reqOther = new Request('https://test.local/api/v1/comprasED?dni=99999999', {
    method: 'GET',
    headers: { Authorization: `Bearer ${fbToken}` },
  });
  const resOther = await handleComprasRoutes(reqOther, env, new URL(reqOther.url), {});
  assert.equal(resOther?.status, 403);
  const errOther = (await resOther?.json()) as { ok: boolean; code: string };
  assert.equal(errOther.ok, false);
  assert.equal(errOther.code, 'FORBIDDEN_DNI');
});

test('handleComprasRoutes: GET /api/v1/compras/resumen soporta ETag y 304 Not Modified', async () => {
  clearCachesForTests();
  const env: ComprasEnv = {
    DB: new MockD1Database() as unknown as D1Database,
    COMPRAS_API_KEY,
  };

  const req1 = new Request('https://test.local/api/v1/compras/resumen?dni=32685684', {
    method: 'GET',
    headers: { 'x-api-key': COMPRAS_API_KEY },
  });
  const res1 = await handleComprasRoutes(req1, env, new URL(req1.url), {});
  assert.equal(res1?.status, 200);
  const etag = res1?.headers.get('ETag') || '';
  assert.ok(etag.startsWith('W/"'));

  // Segunda consulta con If-None-Match -> 304 Not Modified
  const req2 = new Request('https://test.local/api/v1/compras/resumen?dni=32685684', {
    method: 'GET',
    headers: {
      'x-api-key': COMPRAS_API_KEY,
      'If-None-Match': etag,
    },
  });
  const res2 = await handleComprasRoutes(req2, env, new URL(req2.url), {});
  assert.equal(res2?.status, 304);
});

test('handleComprasRoutes: POST /api/v1/compras/ack es idempotente y devuelve confirmados y no_encontrados', async () => {
  clearCachesForTests();
  const env: ComprasEnv = {
    DB: new MockD1Database() as unknown as D1Database,
    COMPRAS_API_KEY,
  };

  const req = new Request('https://test.local/api/v1/compras/ack', {
    method: 'POST',
    headers: { 'x-api-key': COMPRAS_API_KEY, 'Content-Type': 'application/json' },
    body: JSON.stringify({ ids_documentos: [189130, 189131, 999999] }),
  });
  const res = await handleComprasRoutes(req, env, new URL(req.url), {});
  assert.equal(res?.status, 200);
  const body = (await res?.json()) as {
    ok: boolean;
    confirmados: number[];
    ya_procesados: number[];
    no_encontrados: number[];
  };
  assert.equal(body.ok, true);
  assert.deepEqual(body.confirmados, [189130, 189131]);
  assert.deepEqual(body.ya_procesados, [189131]);
  assert.deepEqual(body.no_encontrados, [999999]);
});

test('handleComprasRoutes: permite acceso a compras/batch con Bearer JWT válido', async () => {
  clearCachesForTests();
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
