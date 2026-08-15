// ============================================================================
// index.ts — LOLA Backend Worker (Cloudflare Workers + D1)
//
// Endpoints públicos:
//   GET  /api/v1/version                 → { revision, hash }
//   GET  /api/v1/sync?last_revision=N    → { revision, hash, products, deleted }
//
// Endpoints admin (protegidos con header X-Admin-Secret):
//   POST   /api/v1/admin/products             → upsert de 1 o N productos + bump + hash
//   DELETE /api/v1/admin/products/:codigo_unico→ borrado (tombstone) + bump + hash
//   POST   /api/v1/admin/rehash               → recalcula el hash (tras tocar D1 a mano)
//
// El "bump de revisión" es el corazón del sync: sube revision_number +1, estampa
// las filas tocadas con esa revisión y recalcula el hash del catálogo, todo en
// UNA transacción (db.batch).
// ============================================================================

import { computeCatalogHash, type ProductRef } from './hash';

export interface Env {
  DB: D1Database;
  ADMIN_SECRET?: string; // `npx wrangler secret put ADMIN_SECRET` (o .dev.vars en local)
}

interface Product extends ProductRef {}

const CORS: Record<string, string> = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type, X-Admin-Secret',
};

const json = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' },
  });

const err = (message: string, status = 400) => json({ error: message }, status);

// ── Helpers de datos ─────────────────────────────────────────────────────────

async function getMetadata(db: D1Database) {
  const row = await db
    .prepare("SELECT revision_number, catalog_hash FROM metadata WHERE key = 'catalog'")
    .first<{ revision_number: number; catalog_hash: string }>();
  return { revision: row?.revision_number ?? 0, hash: row?.catalog_hash ?? '' };
}

async function recomputeHash(db: D1Database): Promise<string> {
  const res = await db
    .prepare('SELECT codigo_unico, nombre, precios FROM products ORDER BY codigo_unico')
    .all<{ codigo_unico: string; nombre: string; precios: string }>();
  const products: ProductRef[] = (res.results ?? []).map((r) => ({
    codigo_unico: r.codigo_unico,
    nombre: r.nombre,
    precios: JSON.parse(r.precios) as number[],
  }));
  const hash = await computeCatalogHash(products);
  await db.prepare("UPDATE metadata SET catalog_hash = ? WHERE key = 'catalog'").bind(hash).run();
  return hash;
}

// ── Admin: escritura + bump ──────────────────────────────────────────────────
// El hash se calcula en memoria a partir del estado POST-escritura y se escribe
// en el MISMO batch que las filas, así revision + hash + filas son atómicos
// (sin la ventana donde revisión subió pero hash quedó viejo).

async function adminUpsertProducts(db: D1Database, products: Product[]) {
  const meta = await getMetadata(db);
  const newRevision = meta.revision + 1;

  // Estado post-escritura en memoria: clave → producto.
  const res = await db
    .prepare('SELECT codigo_unico, nombre, precios FROM products')
    .all<{ codigo_unico: string; nombre: string; precios: string }>();
  const mapa = new Map<string, ProductRef>(
    (res.results ?? []).map((r) => [
      r.codigo_unico,
      { codigo_unico: r.codigo_unico, nombre: r.nombre, precios: JSON.parse(r.precios) as number[] },
    ]),
  );
  for (const p of products) mapa.set(p.codigo_unico, p);
  const hash = await computeCatalogHash([...mapa.values()]);

  const batch: D1PreparedStatement[] = [
    db
      .prepare("UPDATE metadata SET revision_number = ?, catalog_hash = ?, updated_at = datetime('now') WHERE key = 'catalog'")
      .bind(newRevision, hash),
  ];
  for (const p of products) {
    batch.push(
      db
        .prepare(
          `INSERT INTO products (codigo_unico, nombre, precios, updated_at_revision)
           VALUES (?, ?, ?, ?)
           ON CONFLICT(codigo_unico) DO UPDATE SET
             nombre = excluded.nombre,
             precios = excluded.precios,
             updated_at_revision = excluded.updated_at_revision,
             updated_at = datetime('now')`,
        )
        .bind(p.codigo_unico, p.nombre, JSON.stringify(p.precios), newRevision),
    );
  }
  await db.batch(batch);
  return { revision: newRevision, hash };
}

async function adminDeleteProduct(db: D1Database, codigoUnico: string) {
  const meta = await getMetadata(db);
  const newRevision = meta.revision + 1;

  // Estado post-escritura en memoria (sin el producto borrado).
  const res = await db
    .prepare('SELECT codigo_unico, nombre, precios FROM products WHERE codigo_unico != ?')
    .bind(codigoUnico)
    .all<{ codigo_unico: string; nombre: string; precios: string }>();
  const restantes: ProductRef[] = (res.results ?? []).map((r) => ({
    codigo_unico: r.codigo_unico,
    nombre: r.nombre,
    precios: JSON.parse(r.precios) as number[],
  }));
  const hash = await computeCatalogHash(restantes);

  await db.batch([
    db
      .prepare("UPDATE metadata SET revision_number = ?, catalog_hash = ?, updated_at = datetime('now') WHERE key = 'catalog'")
      .bind(newRevision, hash),
    db.prepare('DELETE FROM products WHERE codigo_unico = ?').bind(codigoUnico),
    db
      .prepare(
        `INSERT INTO tombstones (codigo_unico, deleted_at_revision)
         VALUES (?, ?)
         ON CONFLICT(codigo_unico) DO UPDATE SET deleted_at_revision = excluded.deleted_at_revision`,
      )
      .bind(codigoUnico, newRevision),
  ]);
  return { revision: newRevision, hash };
}

// ── Sync ─────────────────────────────────────────────────────────────────────

async function handleSync(db: D1Database, url: URL) {
  // last_revision inválido (NaN, negativo, texto) → 0 (envía todo). Evita
  // bindear NaN a SQLite (comportamiento indefinido).
  const raw = url.searchParams.get('last_revision') ?? '0';
  const parsed = Number.parseInt(raw, 10);
  const last = Number.isFinite(parsed) && parsed >= 0 ? parsed : 0;

  const meta = await getMetadata(db);

  if (last >= meta.revision) {
    return json({ revision: meta.revision, hash: meta.hash, products: [], deleted: [] });
  }

  const productsRes = await db
    .prepare('SELECT codigo_unico, nombre, precios FROM products WHERE updated_at_revision > ?')
    .bind(last)
    .all<{ codigo_unico: string; nombre: string; precios: string }>();

  const deletedRes = await db
    .prepare('SELECT codigo_unico FROM tombstones WHERE deleted_at_revision > ?')
    .bind(last)
    .all<{ codigo_unico: string }>();

  const products = (productsRes.results ?? []).map((r) => ({
    codigo_unico: r.codigo_unico,
    nombre: r.nombre,
    precios: JSON.parse(r.precios) as number[],
  }));
  const deleted = (deletedRes.results ?? []).map((r) => r.codigo_unico);

  return json({ revision: meta.revision, hash: meta.hash, products, deleted });
}

// ── Validación de entrada (admin) ────────────────────────────────────────────

function normalizeProducts(body: unknown): Product[] {
  const list: unknown[] = Array.isArray(body)
    ? body
    : body && typeof body === 'object' && Array.isArray((body as any).products)
      ? (body as any).products
      : [body];

  const vacio =
    list.length === 0 || (list.length === 1 && (!list[0] || typeof list[0] !== 'object'));
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

// FAIL CLOSED: sin ADMIN_SECRET configurado, NADIE puede administrar.
// (Antes era fail-open: devolvía true sin secret → cualquiera borraba el catálogo.)
function requireAdmin(request: Request, env: Env): { ok: boolean; reason?: string } {
  if (!env.ADMIN_SECRET) return { ok: false, reason: 'Administración deshabilitada: configurá ADMIN_SECRET' };
  if (request.headers.get('x-admin-secret') !== env.ADMIN_SECRET) return { ok: false, reason: 'No autorizado' };
  return { ok: true };
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
        res = await handleSync(env.DB, url);
      } else if (method === 'POST' && path === '/api/v1/admin/products') {
        const auth = requireAdmin(request, env);
        if (!auth.ok) res = err(auth.reason ?? 'No autorizado', 401);
        else res = json(await adminUpsertProducts(env.DB, normalizeProducts(await request.json())));
      } else if (method === 'DELETE' && path.startsWith('/api/v1/admin/products/')) {
        const auth = requireAdmin(request, env);
        if (!auth.ok) res = err(auth.reason ?? 'No autorizado', 401);
        else {
          const codigoUnico = decodeURIComponent(path.split('/').pop() ?? '');
          if (!codigoUnico) res = err('codigo_unico vacío', 400);
          else res = json(await adminDeleteProduct(env.DB, codigoUnico));
        }
      } else if (method === 'POST' && path === '/api/v1/admin/rehash') {
        const auth = requireAdmin(request, env);
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
