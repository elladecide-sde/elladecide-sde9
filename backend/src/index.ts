// ============================================================================
// index.ts — LOLA Backend Worker (Cloudflare Workers + D1)
//
// Sync por HASH (catálogo completo):
//   GET  /api/v1/version       → { revision, hash }
//   GET  /api/v1/sync          → { revision, hash, products: TODO el catálogo }
//   GET  /api/v1/mapa-catalogo → { revision, hash, productos } (editor de fotos)
//
// Admin (JWT):
//   POST   /api/v1/auth/login | change-password
//   POST   /api/v1/admin/products  { products, replace } → upsert o reemplazo
//   POST   /api/v1/admin/import    { filas } → importCatalogo → reemplazo
//   POST   /api/v1/admin/rollback  { backup: 'bak'|'bak2' } → restaura backup
//   DELETE /api/v1/admin/products/:codigo_unico
//   POST   /api/v1/admin/rehash
//
// El reemplazo (replace: true) es atómico (un solo db.batch): copia el catálogo
// activo al backup rotativo más viejo → borra products → inserta el nuevo →
// sube revisión + hash. Si algo falla, todo revierte (no se pierde el backup).
// ============================================================================

import { bearerToken, generateSalt, hashPassword, safeEqual, signJwt, verifyJwt, verifyPassword } from './auth';
import { computeCatalogHash, type ProductRef } from './hash';
import { partir } from './codigoUnico';
import { importCatalogo } from './importCatalogo';

export interface Env {
  DB: D1Database;
  ADMIN_USER?: string;     // usuario de login (wrangler secret put ADMIN_USER)
  ADMIN_PASSWORD?: string; // contraseña de login (wrangler secret put ADMIN_PASSWORD)
  JWT_SECRET?: string;     // clave de firma del JWT (wrangler secret put JWT_SECRET)
}

interface Product extends ProductRef {}

const TOKEN_TTL_SEC = 24 * 60 * 60; // 24 horas
const BATCH_LIMIT = 100;            // D1 limita los batches a 100 statements
const BAK = ['products_bak', 'products_bak2'] as const;

const CORS: Record<string, string> = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type, Authorization',
};

const json = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' },
  });

const err = (message: string, status = 400) => json({ error: message }, status);

async function runBatch(db: D1Database, batch: D1PreparedStatement[]) {
  for (let i = 0; i < batch.length; i += BATCH_LIMIT) {
    await db.batch(batch.slice(i, i + BATCH_LIMIT));
  }
}

// ── Helpers de datos ─────────────────────────────────────────────────────────

interface Meta {
  revision: number;
  hash: string;
  newest: 'bak' | 'bak2';
}

async function getMetadata(db: D1Database): Promise<Meta> {
  const row = await db
    .prepare('SELECT revision_number, catalog_hash, backup_state FROM metadata WHERE key = \'catalog\'')
    .first<{ revision_number: number; catalog_hash: string; backup_state: string | null }>();
  let newest: 'bak' | 'bak2' = 'bak2';
  try {
    const st = JSON.parse(row?.backup_state ?? '{}');
    if (st?.newest === 'bak') newest = 'bak';
  } catch {
    /* estado inválido → default */
  }
  return { revision: row?.revision_number ?? 0, hash: row?.catalog_hash ?? '', newest };
}

const metaUpdate = (db: D1Database, revision: number, hash: string, backup_state: string) =>
  db
    .prepare("UPDATE metadata SET revision_number = ?, catalog_hash = ?, backup_state = ?, updated_at = datetime('now') WHERE key = 'catalog'")
    .bind(revision, hash, backup_state);

const rowToRef = (r: { codigo_unico: string; nombre: string; precios: string }): ProductRef => ({
  codigo_unico: r.codigo_unico,
  nombre: r.nombre,
  precios: JSON.parse(r.precios) as number[],
});

async function recomputeHash(db: D1Database): Promise<string> {
  const res = await db
    .prepare('SELECT codigo_unico, nombre, precios FROM products ORDER BY codigo_unico')
    .all<{ codigo_unico: string; nombre: string; precios: string }>();
  const hash = await computeCatalogHash((res.results ?? []).map(rowToRef));
  await db.prepare("UPDATE metadata SET catalog_hash = ? WHERE key = 'catalog'").bind(hash).run();
  return hash;
}

/** Genera statements INSERT multi-fila (una por ~100 filas). */
function multiInsert(db: D1Database, table: string, products: Product[]): D1PreparedStatement[] {
  const stmts: D1PreparedStatement[] = [];
  for (let i = 0; i < products.length; i += BATCH_LIMIT) {
    const slice = products.slice(i, i + BATCH_LIMIT);
    const placeholders = slice.map(() => '(?, ?, ?)').join(', ');
    const values: string[] = [];
    for (const p of slice) values.push(p.codigo_unico, p.nombre, JSON.stringify(p.precios));
    stmts.push(
      db.prepare(`INSERT INTO ${table} (codigo_unico, nombre, precios) VALUES ${placeholders}`).bind(...values),
    );
  }
  return stmts;
}

// ── Autenticación ────────────────────────────────────────────────────────────

async function handleLogin(request: Request, env: Env): Promise<Response> {
  const { ADMIN_USER, ADMIN_PASSWORD, JWT_SECRET } = env;
  if (!ADMIN_USER || !JWT_SECRET) {
    return err('Autenticación no configurada en el Worker', 503);
  }
  let body: { username?: unknown; password?: unknown };
  try {
    body = await request.json();
  } catch {
    return err('Body inválido', 400);
  }
  const username = typeof body.username === 'string' ? body.username : '';
  const password = typeof body.password === 'string' ? body.password : '';
  if (!safeEqual(username, ADMIN_USER)) {
    return err('Credenciales inválidas', 401);
  }
  if (!(await checkPassword(env, password))) return err('Credenciales inválidas', 401);

  const token = await signJwt(JWT_SECRET, { sub: username }, TOKEN_TTL_SEC);
  return json({ token, expires_in: TOKEN_TTL_SEC });
}

async function checkPassword(env: Env, password: string): Promise<boolean> {
  const row = await env.DB
    .prepare("SELECT password_hash, salt FROM auth WHERE key = 'admin'")
    .first<{ password_hash: string; salt: string }>();
  if (row) return verifyPassword(password, row.salt, row.password_hash);
  return env.ADMIN_PASSWORD ? safeEqual(password, env.ADMIN_PASSWORD) : false;
}

async function handleChangePassword(request: Request, env: Env): Promise<Response> {
  let body: { current_password?: unknown; new_password?: unknown };
  try {
    body = await request.json();
  } catch {
    return err('Body inválido', 400);
  }
  const current = typeof body.current_password === 'string' ? body.current_password : '';
  const nueva = typeof body.new_password === 'string' ? body.new_password : '';
  if (nueva.length < 8) return err('La nueva contraseña debe tener al menos 8 caracteres', 400);
  if (!(await checkPassword(env, current))) return err('Contraseña actual incorrecta', 401);

  const salt = generateSalt();
  const hash = await hashPassword(nueva, salt);
  await env.DB
    .prepare(
      `INSERT INTO auth (key, password_hash, salt) VALUES ('admin', ?, ?)
       ON CONFLICT(key) DO UPDATE SET
         password_hash = excluded.password_hash,
         salt = excluded.salt,
         updated_at = datetime('now')`,
    )
    .bind(hash, salt)
    .run();
  return json({ ok: true });
}

async function requireAdmin(request: Request, env: Env): Promise<{ ok: boolean; reason?: string }> {
  if (!env.JWT_SECRET) return { ok: false, reason: 'Autenticación no configurada (JWT_SECRET)' };
  const token = bearerToken(request);
  if (!token) return { ok: false, reason: 'Token requerido' };
  const payload = await verifyJwt(env.JWT_SECRET, token);
  if (!payload) return { ok: false, reason: 'Token inválido o expirado' };
  return { ok: true };
}

// ── Escritura ────────────────────────────────────────────────────────────────
// El hash se calcula en memoria y se escribe en el MISMO batch que las filas,
// así revision + hash + datos son atómicos.

/** Reemplazo completo: backup rotativo → wipe → insert nuevo (atómico). */
async function replaceCatalog(db: D1Database, products: Product[]) {
  const meta = await getMetadata(db);
  const newRevision = meta.revision + 1;
  const hash = await computeCatalogHash(products);

  // Backup: copiar el catálogo activo al slot más viejo.
  const target = meta.newest === 'bak' ? 'bak2' : 'bak';
  const nuevoState = JSON.stringify({ newest: target });

  const batch: D1PreparedStatement[] = [
    db.prepare(`DELETE FROM ${BAK[target === 'bak' ? 0 : 1]}`),
    db.prepare(
      `INSERT INTO ${BAK[target === 'bak' ? 0 : 1]} (codigo_unico, nombre, precios) SELECT codigo_unico, nombre, precios FROM products`,
    ),
    db.prepare('DELETE FROM products'),
    ...multiInsert(db, 'products', products),
    metaUpdate(db, newRevision, hash, nuevoState),
  ];
  await runBatch(db, batch);
  return { revision: newRevision, hash, backup: target };
}

/** Upsert parcial (sin wipe ni backup): edición de 1 o N productos. */
async function upsertProducts(db: D1Database, products: Product[]) {
  const meta = await getMetadata(db);
  const newRevision = meta.revision + 1;

  const res = await db
    .prepare('SELECT codigo_unico, nombre, precios FROM products')
    .all<{ codigo_unico: string; nombre: string; precios: string }>();
  const mapa = new Map<string, ProductRef>((res.results ?? []).map((r) => [r.codigo_unico, rowToRef(r)]));
  for (const p of products) mapa.set(p.codigo_unico, p);
  const hash = await computeCatalogHash([...mapa.values()]);

  const batch: D1PreparedStatement[] = [
    metaUpdate(db, newRevision, hash, JSON.stringify({ newest: meta.newest })),
  ];
  for (const p of products) {
    batch.push(
      db
        .prepare(
          `INSERT INTO products (codigo_unico, nombre, precios) VALUES (?, ?, ?)
           ON CONFLICT(codigo_unico) DO UPDATE SET
             nombre = excluded.nombre,
             precios = excluded.precios,
             updated_at = datetime('now')`,
        )
        .bind(p.codigo_unico, p.nombre, JSON.stringify(p.precios)),
    );
  }
  await runBatch(db, batch);
  return { revision: newRevision, hash };
}

async function adminDeleteProduct(db: D1Database, codigoUnico: string) {
  const meta = await getMetadata(db);
  const newRevision = meta.revision + 1;

  const res = await db
    .prepare('SELECT codigo_unico, nombre, precios FROM products WHERE codigo_unico != ?')
    .bind(codigoUnico)
    .all<{ codigo_unico: string; nombre: string; precios: string }>();
  const hash = await computeCatalogHash((res.results ?? []).map(rowToRef));

  await runBatch(db, [
    metaUpdate(db, newRevision, hash, JSON.stringify({ newest: meta.newest })),
    db.prepare('DELETE FROM products WHERE codigo_unico = ?').bind(codigoUnico),
  ]);
  return { revision: newRevision, hash };
}

/** Restaura un backup (bak | bak2). Atómico, y mantiene las 2 copias. */
async function rollbackCatalog(db: D1Database, backup: 'bak' | 'bak2') {
  const meta = await getMetadata(db);
  const chosen = backup;
  const other = chosen === 'bak' ? 'bak2' : 'bak';
  const chosenTable = BAK[chosen === 'bak' ? 0 : 1];
  const otherTable = BAK[other === 'bak' ? 0 : 1];
  const newRevision = meta.revision + 1;

  // Hash del catálogo que vamos a restaurar (antes del batch).
  const res = await db
    .prepare(`SELECT codigo_unico, nombre, precios FROM ${chosenTable}`)
    .all<{ codigo_unico: string; nombre: string; precios: string }>();
  const hash = await computeCatalogHash((res.results ?? []).map(rowToRef));

  await runBatch(db, [
    // Guardar el catálogo actual (pre-rollback) en el otro slot.
    db.prepare(`DELETE FROM ${otherTable}`),
    db.prepare(
      `INSERT INTO ${otherTable} (codigo_unico, nombre, precios) SELECT codigo_unico, nombre, precios FROM products`,
    ),
    db.prepare('DELETE FROM products'),
    db.prepare(
      `INSERT INTO products (codigo_unico, nombre, precios) SELECT codigo_unico, nombre, precios FROM ${chosenTable}`,
    ),
    metaUpdate(db, newRevision, hash, JSON.stringify({ newest: other })),
  ]);
  return { revision: newRevision, hash, restaurado: chosen };
}

// ── Endpoints ────────────────────────────────────────────────────────────────

/** GET /api/v1/sync — catálogo completo (los clientes comparan hash y reemplazan). */
async function handleSync(db: D1Database) {
  const meta = await getMetadata(db);
  const res = await db
    .prepare('SELECT codigo_unico, nombre, precios FROM products ORDER BY codigo_unico')
    .all<{ codigo_unico: string; nombre: string; precios: string }>();
  const products = (res.results ?? []).map((r) => ({
    codigo_unico: r.codigo_unico,
    nombre: r.nombre,
    precios: JSON.parse(r.precios) as number[],
  }));
  return json({ revision: meta.revision, hash: meta.hash, products });
}

/** GET /api/v1/mapa-catalogo — para el editor de fotos (sde11). */
async function handleMapa(db: D1Database) {
  const meta = await getMetadata(db);
  const res = await db
    .prepare('SELECT codigo_unico, nombre FROM products ORDER BY codigo_unico')
    .all<{ codigo_unico: string; nombre: string }>();
  const productos = (res.results ?? []).map((r) => ({
    codigo_unico: r.codigo_unico,
    nombre: r.nombre,
    modelo: partir(r.nombre).codigo || null,
  }));
  return json({ revision: meta.revision, hash: meta.hash, productos });
}

/** POST /api/v1/admin/import — fallback: el worker calcula (importCatalogo). */
async function handleImport(request: Request, env: Env): Promise<Response> {
  let body: Record<string, unknown>;
  try {
    body = await request.json();
  } catch {
    return err('Body inválido', 400);
  }
  const filas = body?.filas;
  if (!Array.isArray(filas)) {
    return err('Body inválido: falta `filas` (array de arrays del Excel)', 400);
  }

  const r = importCatalogo(filas, {
    colNombre: typeof body.colNombre === 'number' ? body.colNombre : undefined,
    colPrecio: typeof body.colPrecio === 'number' ? body.colPrecio : undefined,
    colCodigo: typeof body.colCodigo === 'number' ? body.colCodigo : undefined,
    skip: typeof body.skip === 'number' ? body.skip : undefined,
    marcas: Array.isArray(body.marcas) ? (body.marcas as string[]) : undefined,
  });

  if (r.productos.length === 0) {
    return err('No se encontraron productos válidos en las filas', 400);
  }

  const up = await replaceCatalog(env.DB, r.productos);
  return json({
    revision: up.revision,
    hash: up.hash,
    productos: r.productos.length,
    colisiones: r.colisiones,
    descartados: r.descartados.length,
    rechazadas: r.rechazadas.length,
  });
}

// ── Validación de entrada (admin) ────────────────────────────────────────────

function normalizeProducts(body: unknown): Product[] {
  const list: unknown[] = Array.isArray(body)
    ? body
    : body && typeof body === 'object' && Array.isArray((body as any).products)
      ? (body as any).products
      : [body];

  const vacio = list.length === 0 || (list.length === 1 && (!list[0] || typeof list[0] !== 'object'));
  if (vacio) throw new Error('Body vacío: enviá un producto, un array o { products: [...] }');

  return list.map((p) => {
    if (!p || typeof (p as any).codigo_unico !== 'string' || !(p as any).codigo_unico.trim())
      throw new Error('codigo_unico inválido');
    if (typeof (p as any).nombre !== 'string' || !(p as any).nombre.trim())
      throw new Error('nombre inválido');
    if (!Array.isArray((p as any).precios) || (p as any).precios.length === 0)
      throw new Error('precios inválido');
    const precios = (p as any).precios.map((n: unknown) => Math.round(Number(n)));
    if (precios.some((n: number) => Number.isNaN(n))) throw new Error('precios inválido');
    return {
      codigo_unico: (p as any).codigo_unico.trim(),
      nombre: (p as any).nombre.trim(),
      precios,
    };
  });
}

// ── Router ───────────────────────────────────────────────────────────────────

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const { pathname: path } = url;
    const method = request.method;

    if (method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS });

    let res: Response;
    try {
      if (method === 'GET' && path === '/api/v1/version') {
        res = json(await getMetadata(env.DB));
      } else if (method === 'GET' && path === '/api/v1/sync') {
        res = await handleSync(env.DB);
      } else if (method === 'GET' && path === '/api/v1/mapa-catalogo') {
        res = await handleMapa(env.DB);
      } else if (method === 'POST' && path === '/api/v1/auth/login') {
        res = await handleLogin(request, env);
      } else if (method === 'POST' && path === '/api/v1/auth/change-password') {
        const auth = await requireAdmin(request, env);
        if (!auth.ok) res = err(auth.reason ?? 'No autorizado', 401);
        else res = await handleChangePassword(request, env);
      } else if (method === 'POST' && path === '/api/v1/admin/products') {
        const auth = await requireAdmin(request, env);
        if (!auth.ok) res = err(auth.reason ?? 'No autorizado', 401);
        else {
          const body = (await request.json()) as { products?: unknown; replace?: unknown };
          const productos = normalizeProducts(body);
          if (body?.replace === true) {
            if (productos.length === 0) res = err('Reemplazo con catálogo vacío', 400);
            else res = json(await replaceCatalog(env.DB, productos));
          } else {
            res = json(await upsertProducts(env.DB, productos));
          }
        }
      } else if (method === 'POST' && path === '/api/v1/admin/import') {
        const auth = await requireAdmin(request, env);
        if (!auth.ok) res = err(auth.reason ?? 'No autorizado', 401);
        else res = await handleImport(request, env);
      } else if (method === 'POST' && path === '/api/v1/admin/rollback') {
        const auth = await requireAdmin(request, env);
        if (!auth.ok) res = err(auth.reason ?? 'No autorizado', 401);
        else {
          const body = (await request.json()) as { backup?: unknown };
          const backup = body?.backup === 'bak' ? 'bak' : body?.backup === 'bak2' ? 'bak2' : null;
          if (!backup) res = err('`backup` debe ser "bak" o "bak2"', 400);
          else res = json(await rollbackCatalog(env.DB, backup));
        }
      } else if (method === 'DELETE' && path.startsWith('/api/v1/admin/products/')) {
        const auth = await requireAdmin(request, env);
        if (!auth.ok) res = err(auth.reason ?? 'No autorizado', 401);
        else {
          const codigoUnico = decodeURIComponent(path.split('/').pop() ?? '');
          if (!codigoUnico) res = err('codigo_unico vacío', 400);
          else res = json(await adminDeleteProduct(env.DB, codigoUnico));
        }
      } else if (method === 'POST' && path === '/api/v1/admin/rehash') {
        const auth = await requireAdmin(request, env);
        if (!auth.ok) res = err(auth.reason ?? 'No autorizado', 401);
        else res = json({ hash: await recomputeHash(env.DB) });
      } else {
        res = err('Not found', 404);
      }
    } catch (e) {
      res = err(String(e instanceof Error ? e.message : e), 500);
    }

    for (const [k, v] of Object.entries(CORS)) res.headers.set(k, v);
    return res;
  },
};