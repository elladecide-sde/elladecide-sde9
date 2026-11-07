// ============================================================================
// compras/firebaseAuth.ts — Verificación de Firebase ID Token (RS256) +
// resolución de DNI vía Firestore REST API (users/{uid}.dni) + Rate Limiter
// ============================================================================

import { normalizarDni } from './types';

export interface FirebaseUserContext {
  uid: string;
  projectId: string;
  dni: string;
  email?: string;
}

interface JwkKey {
  kid: string;
  kty: string;
  alg: string;
  use: string;
  n: string;
  e: string;
}

interface JwkCache {
  keys: Map<string, CryptoKey>;
  expiresAt: number;
}

interface DniCacheEntry {
  dni: string;
  expiresAt: number;
}

const GOOGLE_JWK_URL =
  'https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com';

let jwkCache: JwkCache | null = null;
const dniCache = new Map<string, DniCacheEntry>();
const DNI_CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutos

function b64urlToBytes(s: string): Uint8Array {
  const pad = s.length % 4 === 2 ? '==' : s.length % 4 === 3 ? '=' : '';
  const b64 = s.replace(/-/g, '+').replace(/_/g, '/') + pad;
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/**
 * Detecta si un Bearer token tiene estructura de Firebase ID Token (RS256 con kid)
 * en lugar del JWT interno HS256 del admin de LOLA.
 */
export function isFirebaseTokenCandidate(token: string): boolean {
  const parts = token.split('.');
  if (parts.length !== 3) return false;
  try {
    const header = JSON.parse(new TextDecoder().decode(b64urlToBytes(parts[0]))) as {
      alg?: string;
      kid?: string;
    };
    return header.alg === 'RS256' && typeof header.kid === 'string' && header.kid.length > 0;
  } catch {
    return false;
  }
}

async function getGooglePublicKey(
  kid: string,
  fetchFn: typeof fetch = fetch
): Promise<CryptoKey | null> {
  const now = Date.now();
  if (jwkCache && jwkCache.expiresAt > now && jwkCache.keys.has(kid)) {
    return jwkCache.keys.get(kid) || null;
  }

  const res = await fetchFn(GOOGLE_JWK_URL);
  if (!res.ok) return null;

  let maxAgeSec = 3600;
  const cc = res.headers.get('cache-control') || '';
  const m = cc.match(/max-age=(\d+)/i);
  if (m) maxAgeSec = Math.max(60, parseInt(m[1], 10));

  const body = (await res.json()) as { keys?: JwkKey[] };
  const map = new Map<string, CryptoKey>();

  for (const jwk of body.keys || []) {
    if (jwk.kty === 'RSA' && jwk.kid) {
      const cryptoKey = await crypto.subtle.importKey(
        'jwk',
        jwk,
        { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
        false,
        ['verify']
      );
      map.set(jwk.kid, cryptoKey);
    }
  }

  jwkCache = {
    keys: map,
    expiresAt: now + maxAgeSec * 1000,
  };

  return map.get(kid) || null;
}

/**
 * Extrae el valor string/integer de un campo de documento Firestore REST.
 */
function extractFirestoreField(fields: Record<string, unknown> | undefined, key: string): string {
  if (!fields || typeof fields !== 'object') return '';
  const raw = fields[key] as
    | { stringValue?: string; integerValue?: string | number }
    | undefined;
  if (!raw) return '';
  if (typeof raw.stringValue === 'string') return raw.stringValue;
  if (raw.integerValue !== undefined) return String(raw.integerValue);
  return '';
}

/**
 * Consulta users/{uid} en Firestore REST API usando el mismo Firebase ID token del usuario.
 */
export async function fetchDniFromFirestore(
  projectId: string,
  uid: string,
  idToken: string,
  fetchFn: typeof fetch = fetch
): Promise<string> {
  const cacheKey = `${projectId}:${uid}`;
  const now = Date.now();
  const cached = dniCache.get(cacheKey);
  if (cached && cached.expiresAt > now) {
    return cached.dni;
  }

  const url = `https://firestore.googleapis.com/v1/projects/${encodeURIComponent(
    projectId
  )}/databases/(default)/documents/users/${encodeURIComponent(uid)}`;

  const res = await fetchFn(url, {
    method: 'GET',
    headers: {
      Authorization: `Bearer ${idToken}`,
      Accept: 'application/json',
    },
  });

  if (!res.ok) {
    throw new Error(`No se pudo leer users/${uid} en Firestore (HTTP ${res.status})`);
  }

  const doc = (await res.json()) as { fields?: Record<string, unknown> };
  const rawDni =
    extractFirestoreField(doc.fields, 'dni') ||
    extractFirestoreField(doc.fields, 'cuit') ||
    extractFirestoreField(doc.fields, 'documento');

  const dni = normalizarDni(rawDni);
  if (!dni) {
    throw new Error(`El documento users/${uid} en Firestore no contiene un campo 'dni' válido`);
  }

  dniCache.set(cacheKey, { dni, expiresAt: now + DNI_CACHE_TTL_MS });
  return dni;
}

/**
 * Verifica el Firebase ID Token (RS256 + aud/iss/exp/sub) y obtiene el DNI del usuario
 * desde el claim del token o consultando users/{uid}.dni en Firestore REST.
 */
export async function verifyFirebaseTokenAndResolveDni(
  token: string,
  expectedProjectId?: string,
  options: {
    fetchFn?: typeof fetch;
    customPublicKey?: CryptoKey;
  } = {}
): Promise<{ ok: true; user: FirebaseUserContext } | { ok: false; code: string; message: string }> {
  const fetchFn = options.fetchFn || fetch;
  const parts = token.split('.');
  if (parts.length !== 3) {
    return { ok: false, code: 'UNAUTHORIZED', message: 'Formato de Firebase ID Token inválido' };
  }

  let header: { alg?: string; kid?: string };
  let payload: {
    iss?: string;
    aud?: string;
    sub?: string;
    exp?: number;
    iat?: number;
    email?: string;
    dni?: string | number;
  };

  try {
    header = JSON.parse(new TextDecoder().decode(b64urlToBytes(parts[0])));
    payload = JSON.parse(new TextDecoder().decode(b64urlToBytes(parts[1])));
  } catch {
    return { ok: false, code: 'UNAUTHORIZED', message: 'No se pudo decodificar el Firebase ID Token' };
  }

  if (header.alg !== 'RS256' || !header.kid) {
    return { ok: false, code: 'UNAUTHORIZED', message: 'El token debe estar firmado con RS256 y poseer kid' };
  }

  const nowSec = Math.floor(Date.now() / 1000);
  if (typeof payload.exp !== 'number' || payload.exp <= nowSec) {
    return { ok: false, code: 'TOKEN_EXPIRED', message: 'El Firebase ID Token ha expirado' };
  }
  if (typeof payload.iat === 'number' && payload.iat > nowSec + 300) {
    return { ok: false, code: 'UNAUTHORIZED', message: 'El token tiene un iat en el futuro inválido' };
  }

  const uid = typeof payload.sub === 'string' ? payload.sub.trim() : '';
  if (!uid) {
    return { ok: false, code: 'UNAUTHORIZED', message: 'El token no contiene un UID (sub) válido' };
  }

  const projectId = (expectedProjectId || payload.aud || '').trim();
  if (!projectId) {
    return { ok: false, code: 'UNAUTHORIZED', message: 'No se pudo determinar el projectId de Firebase (aud)' };
  }

  if (expectedProjectId && payload.aud !== expectedProjectId) {
    return {
      ok: false,
      code: 'UNAUTHORIZED',
      message: `Audience (aud) inválida: se esperaba '${expectedProjectId}'`,
    };
  }

  const expectedIss = `https://securetoken.google.com/${projectId}`;
  if (payload.iss !== expectedIss) {
    return {
      ok: false,
      code: 'UNAUTHORIZED',
      message: `Issuer (iss) inválido: se esperaba '${expectedIss}'`,
    };
  }

  try {
    const pubKey = options.customPublicKey || (await getGooglePublicKey(header.kid, fetchFn));
    if (!pubKey) {
      return { ok: false, code: 'UNAUTHORIZED', message: 'No se encontró la clave pública JWK para verificar el token' };
    }

    const signingInput = new TextEncoder().encode(`${parts[0]}.${parts[1]}`);
    const signature = b64urlToBytes(parts[2]);
    const valid = await crypto.subtle.verify(
      'RSASSA-PKCS1-v1_5',
      pubKey,
      signature as unknown as BufferSource,
      signingInput
    );

    if (!valid) {
      return { ok: false, code: 'UNAUTHORIZED', message: 'Firma criptográfica del Firebase ID Token inválida' };
    }
  } catch {
    return { ok: false, code: 'UNAUTHORIZED', message: 'Error verificando la firma del Firebase ID Token' };
  }

  // 1. Si el claim dni ya viene en el token, lo usamos; si no, leemos users/{uid}.dni en Firestore REST
  let dni = normalizarDni(payload.dni);
  if (!dni) {
    try {
      dni = await fetchDniFromFirestore(projectId, uid, token, fetchFn);
    } catch (e) {
      return {
        ok: false,
        code: 'DNI_NO_CONFIGURADO',
        message: e instanceof Error ? e.message : 'No se pudo obtener el DNI del usuario desde Firestore',
      };
    }
  }

  return {
    ok: true,
    user: {
      uid,
      projectId,
      dni,
      email: payload.email,
    },
  };
}

// ============================================================================
// Rate Limiter liviano por ventana deslizante (en memoria del Isolate)
// ============================================================================

interface RateBucket {
  count: number;
  resetAt: number;
}

const rateBuckets = new Map<string, RateBucket>();

export function checkRateLimit(
  key: string,
  limitPerWindow = 120,
  windowSec = 60
): { allowed: boolean; retryAfterSec: number; remaining: number } {
  const now = Date.now();
  const bucket = rateBuckets.get(key);

  if (!bucket || bucket.resetAt <= now) {
    rateBuckets.set(key, { count: 1, resetAt: now + windowSec * 1000 });
    return { allowed: true, retryAfterSec: 0, remaining: limitPerWindow - 1 };
  }

  if (bucket.count >= limitPerWindow) {
    const retryAfterSec = Math.max(1, Math.ceil((bucket.resetAt - now) / 1000));
    return { allowed: false, retryAfterSec, remaining: 0 };
  }

  bucket.count += 1;
  return { allowed: true, retryAfterSec: 0, remaining: limitPerWindow - bucket.count };
}

export function clearCachesForTests(): void {
  jwkCache = null;
  dniCache.clear();
  rateBuckets.clear();
}
