// ============================================================================
// auth.ts — Autenticación JWT (HS256) para los endpoints admin.
//
// Por qué JWT y no un secret compartido:
//   - El secreto maestro (JWT_SECRET) vive SOLO en el Worker, nunca en el
//     navegador. El browser recibe un token firmado de corta vida.
//   - El token se firma con HMAC-SHA256 vía Web Crypto (crypto.subtle).
//   - La contraseña se compara en tiempo constante (anti timing-attack).
//
// Flujo:
//   1. POST /api/v1/auth/login { username, password } → { token }
//   2. El cliente manda Authorization: Bearer <token> en cada request admin.
//   3. verifyJwt valida firma + expiración.
// ============================================================================

const encoder = new TextEncoder();
const decoder = new TextDecoder();

function base64urlEncode(bytes: Uint8Array): string {
  let bin = '';
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function base64urlDecode(s: string): Uint8Array {
  const b64 = s.replace(/-/g, '+').replace(/_/g, '/');
  const pad = b64 + '='.repeat((4 - (b64.length % 4)) % 4);
  const bin = atob(pad);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return bytes;
}

async function hmacKey(secret: string, usage: 'sign' | 'verify'): Promise<CryptoKey> {
  return crypto.subtle.importKey(
    'raw',
    encoder.encode(secret),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    [usage],
  );
}

/** Firma un JWT HS256 con expiración. */
export async function signJwt(
  secret: string,
  payload: Record<string, unknown>,
  expiresInSec: number,
): Promise<string> {
  const header = { alg: 'HS256', typ: 'JWT' };
  const now = Math.floor(Date.now() / 1000);
  const body = { ...payload, iat: now, exp: now + expiresInSec };
  const h = base64urlEncode(encoder.encode(JSON.stringify(header)));
  const p = base64urlEncode(encoder.encode(JSON.stringify(body)));
  const key = await hmacKey(secret, 'sign');
  const sig = await crypto.subtle.sign('HMAC', key, encoder.encode(`${h}.${p}`));
  return `${h}.${p}.${base64urlEncode(new Uint8Array(sig))}`;
}

/** Verifica firma y expiración. Devuelve el payload o null. */
export async function verifyJwt(secret: string, token: string): Promise<Record<string, unknown> | null> {
  const parts = token.split('.');
  if (parts.length !== 3) return null;
  const [h, p, sig] = parts;
  try {
    const key = await hmacKey(secret, 'verify');
    const ok = await crypto.subtle.verify('HMAC', key, base64urlDecode(sig), encoder.encode(`${h}.${p}`));
    if (!ok) return null;
    const payload = JSON.parse(decoder.decode(base64urlDecode(p))) as Record<string, unknown>;
    if (typeof payload.exp !== 'number' || payload.exp < Math.floor(Date.now() / 1000)) return null;
    return payload;
  } catch {
    return null;
  }
}

/** Comparación en tiempo constante (evita timing-attack en credenciales). */
export function safeEqual(a: string, b: string): boolean {
  const aa = encoder.encode(a);
  const bb = encoder.encode(b);
  if (aa.length !== bb.length) return false;
  let diff = 0;
  for (let i = 0; i < aa.length; i++) diff |= aa[i] ^ bb[i];
  return diff === 0;
}

/** Extrae el token de `Authorization: Bearer <token>`. */
export function bearerToken(request: Request): string | null {
  const auth = request.headers.get('Authorization');
  if (!auth) return null;
  const m = auth.match(/^Bearer\s+(.+)$/i);
  return m ? m[1].trim() : null;
}
