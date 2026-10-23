// ============================================================================
// index.ts — LOLA Backend Worker (Cloudflare Workers + D1)
//
// Sync por HASH (catálogo completo):
//   GET  /api/v1/version       → { revision, hash }
//   GET  /api/v1/sync          → { revision, hash, products: TODO el catálogo }
//                                ADITIVO: los campos viejos { codigo_unico,
//                                nombre, precios } siguen idénticos (Android los
//                                exige) + fabrica, codigo, descripcion,
//                                costo_neto, oferta. Nunca se quitan campos.
//                                Query params ADITIVOS (sin args = byte idéntico):
//                                  · ?fields=a,b   → por fila SOLO esos campos
//                                  · ?page=N&limit=M → páginas (limit default
//                                    100); el envelope agrega page, limit,
//                                    total, totalPages y hasMore
//                                  · ?since=UTC    → solo updated_at >= ese
//                                    instante ('0' o vacío = todo); el
//                                    envelope agrega serverTime, que es lo que
//                                    el cliente manda como ?since la próxima
//                                Combinables entre sí y con ?mode. Un valor
//                                inválido → 400 con el detalle (nunca degradar
//                                en silencio).
//   GET  /api/v1/sync/v2       → contrato NUEVO. La forma depende de
//                                config.sync_mode:
//                                  · predeterminado     → TODOS los campos de la
//                                    fila: { codigo_unico, descripcion, precios[4],
//                                    categoria, fabrica, codigo, oferta,
//                                    costo_neto, costo_iva, updated_at }
//                                  · descripcion-costos → { descripcion, costo }
//                                (costo = costo_neto; sin listas, sin costo+IVA,
//                                 sin categoría, sin fábrica, sin código, sin oferta)
//                                (acepta los mismos ?fields, ?page, ?limit, ?since)
//   GET  /api/v1/sync-config   → { modo }
//   GET  /api/v1/mapa-catalogo → { revision, hash, productos } (editor de fotos)
//
// Admin (JWT):
//   POST   /api/v1/auth/login | change-password
//   POST   /api/v1/admin/products    { products, replace } → upsert o reemplazo
//   POST   /api/v1/admin/import      { filas } → importCatalogo → reemplazo
//   POST   /api/v1/admin/rollback    { backup: 'bak'|'bak2' } → restaura backup
//   POST   /api/v1/admin/sync-config { modo } → 'predeterminado'|'descripcion-costos'
//   DELETE /api/v1/admin/products/:codigo_unico
//   POST   /api/v1/admin/rehash
//
// El reemplazo (replace: true) es atómico (un solo db.batch): copia el catálogo
// activo al backup rotativo más viejo → borra products → inserta el nuevo →
// sube revisión + hash. Si algo falla, todo revierte (no se pierde el backup).
//
// LÍMITE D1 que manda el tamaño de los INSERT multi-fila:
//   "Maximum bound parameters per query = 100" → con 13 columnas por fila van
//   7 filas por statement (7 × 13 = 91 ≤ 100). No subir.
// ============================================================================

import { bearerToken, generateSalt, hashPassword, safeEqual, signJwt, verifyJwt, verifyPassword } from './auth';
import { computeCatalogHash, type ProductRef } from './hash';
import { partir } from './codigoUnico';
import { importCatalogo } from './importCatalogo';
import { derivarCostos, fabPersistida, listasDe, PRECIOS_DEFAULT, type Producto } from './modelo';
import { FIELDS_V1, FIELDS_V2, FIELDS_V2_DC, pageMeta, parseSyncQuery, pickFields, type SyncQuery } from './syncQuery';

export interface Env {
  DB: D1Database;
  ADMIN_USER?: string;     // usuario de login (wrangler secret put ADMIN_USER)
  ADMIN_PASSWORD?: string; // contraseña de login (wrangler secret put ADMIN_PASSWORD)
  JWT_SECRET?: string;     // clave de firma del JWT (wrangler secret put JWT_SECRET)
}

type Product = Producto;

const TOKEN_TTL_SEC = 24 * 60 * 60; // 24 horas
const BATCH_LIMIT = 100;            // D1 limita los batches a 100 statements
const INSERT_CHUNK = 7;             // 7 filas × 13 columnas = 91 params (techo D1: 100)
const BAK = ['products_bak', 'products_bak2'] as const;

const PRODUCT_COLS = 'codigo_unico, fabrica, codigo, descripcion, costo_neto, costo_iva, lista1, lista2, lista3, lista4, categoria, oferta, stock';
const LIST_COLS = '(codigo_unico, fabrica, codigo, descripcion, costo_neto, costo_iva, lista1, lista2, lista3, lista4, categoria, oferta, stock)';

export type SyncMode = 'predeterminado' | 'descripcion-costos';
const SYNC_MODES: SyncMode[] = ['predeterminado', 'descripcion-costos'];

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
  updated_at?: string;
}

interface RowProducto {
  codigo_unico: string;
  fabrica: string;
  codigo: string;
  descripcion: string;
  costo_neto: number;
  costo_iva: number;
  lista1: number;
  lista2: number;
  lista3: number;
  lista4: number;
  categoria: string;
  oferta: number; // 0/1 en D1, boolean en el DTO
  stock: number;
  updated_at: string;
}

async function getMetadata(db: D1Database): Promise<Meta> {
  const row = await db
    .prepare('SELECT revision_number, catalog_hash, backup_state, updated_at FROM metadata WHERE key = \'catalog\'')
    .first<{ revision_number: number; catalog_hash: string; backup_state: string | null; updated_at?: string }>();
  let newest: 'bak' | 'bak2' = 'bak2';
  try {
    const st = JSON.parse(row?.backup_state ?? '{}');
    if (st?.newest === 'bak') newest = 'bak';
  } catch {
    /* estado inválido → default */
  }
  return {
    revision: row?.revision_number ?? 0,
    hash: row?.catalog_hash ?? '',
    newest,
    updated_at: row?.updated_at ?? '',
  };
}

const metaUpdate = (db: D1Database, revision: number, hash: string, backup_state: string) =>
  db
    .prepare("UPDATE metadata SET revision_number = ?, catalog_hash = ?, backup_state = ?, updated_at = datetime('now') WHERE key = 'catalog'")
    .bind(revision, hash, backup_state);

/** Fila D1 → DTO. `descripcion` es el `nombre` de siempre; listas de columna a array. */
const rowToProduct = (r: RowProducto): Product => ({
  codigo_unico: r.codigo_unico,
  descripcion: r.descripcion,
  costo_neto: r.costo_neto,
  costo_iva: r.costo_iva,
  precios: listasDe(r),
  categoria: r.categoria ?? '',
  fabrica: r.fabrica ?? '',
  codigo: r.codigo ?? '',
  oferta: r.oferta === 1,
  stock: Number(r.stock) || 0,
});

/**
 * DTO → ProductRef para el hash canónico.
 * ⚠️  La forma canónica es { codigo_unico, nombre, precios } y está CONGELADA:
 * hay un port exacto en Android (CatalogHash.kt) con golden tests. Renombrar
 * acá rompe la integridad de todas las apps viejas.
 */
const toRef = (p: Product): ProductRef => ({
  codigo_unico: p.codigo_unico,
  nombre: p.descripcion,
  precios: p.precios.map((n) => Math.round(Number(n))),
});

async function recomputeHash(db: D1Database): Promise<string> {
  const res = await db.prepare(`SELECT ${PRODUCT_COLS} FROM products ORDER BY codigo_unico`).all<RowProducto>();
  const hash = await computeCatalogHash((res.results ?? []).map(rowToProduct).map(toRef));
  await db.prepare("UPDATE metadata SET catalog_hash = ? WHERE key = 'catalog'").bind(hash).run();
  return hash;
}

/** Genera statements INSERT multi-fila (7 filas por statement = 91 params). */
function multiInsert(db: D1Database, table: string, products: Product[]): D1PreparedStatement[] {
  const stmts: D1PreparedStatement[] = [];
  const placeholders = `(${new Array(13).fill('?').join(', ')})`;
  for (let i = 0; i < products.length; i += INSERT_CHUNK) {
    const slice = products.slice(i, i + INSERT_CHUNK);
    const values: Array<string | number> = [];
    for (const p of slice) {
      const l = p.precios;
      values.push(
        p.codigo_unico,
        fabPersistida(p.fabrica),
        p.codigo || '',
        p.descripcion,
        p.costo_neto,
        p.costo_iva,
        Number(l[0]) || 0,
        Number(l[1]) || 0,
        Number(l[2]) || 0,
        Number(l[3]) || 0,
        p.categoria || '',
        p.oferta ? 1 : 0,
        Number(p.stock) || 0,
      );
    }
    stmts.push(
      db.prepare(`INSERT INTO ${table} ${LIST_COLS} VALUES ${slice.map(() => placeholders).join(', ')}`).bind(...values),
    );
  }
  return stmts;
}

// ── Config del operador (sync_mode) ──────────────────────────────────────────

/** Modo de sync del contrato v2. Si la tabla no existe todavía → predeterminado. */
async function getSyncMode(db: D1Database): Promise<SyncMode> {
  try {
    const row = await db.prepare("SELECT value FROM config WHERE key = 'sync_mode'").first<{ value: string }>();
    return row && (SYNC_MODES as string[]).includes(row.value) ? (row.value as SyncMode) : 'predeterminado';
  } catch {
    return 'predeterminado';
  }
}

async function setSyncMode(db: D1Database, modo: SyncMode): Promise<void> {
  await db
    .prepare(
      `INSERT INTO config (key, value) VALUES ('sync_mode', ?)
       ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = datetime('now')`,
    )
    .bind(modo)
    .run();
}

// ── Config del parser (fábricas, palabras de rubro, reglas de oferta) ───────

export interface ParserConfigRow {
  version: number;
  config: Record<string, unknown>;
  updated_at: string;
}

async function getParserConfig(db: D1Database): Promise<ParserConfigRow> {
  try {
    const row = await db
      .prepare("SELECT version, config_json, updated_at FROM parser_config WHERE key = 'default'")
      .first<{ version: number; config_json: string; updated_at: string }>();
    if (row) {
      let parsed = {};
      try { parsed = JSON.parse(row.config_json || '{}'); } catch { /* vacio */ }
      return { version: row.version, config: parsed, updated_at: row.updated_at };
    }
  } catch {
    /* Si la tabla aún no existe, devuelve default */
  }
  return { version: 0, config: {}, updated_at: '' };
}

async function setParserConfig(db: D1Database, configObj: Record<string, unknown>): Promise<{ version: number }> {
  // Asegura creación de tabla si faltara
  await db.prepare(`
    CREATE TABLE IF NOT EXISTS parser_config (
      key TEXT PRIMARY KEY,
      version INTEGER NOT NULL DEFAULT 0,
      config_json TEXT NOT NULL DEFAULT '{}',
      updated_at TEXT NOT NULL DEFAULT (datetime('now'))
    )
  `).run();

  const current = await db
    .prepare("SELECT version FROM parser_config WHERE key = 'default'")
    .first<{ version: number }>();
  const nextVer = (current?.version ?? 0) + 1;
  const jsonStr = JSON.stringify(configObj);

  await db.prepare(`
    INSERT INTO parser_config (key, version, config_json, updated_at)
    VALUES ('default', ?, ?, datetime('now'))
    ON CONFLICT(key) DO UPDATE SET
      version = excluded.version,
      config_json = excluded.config_json,
      updated_at = excluded.updated_at
  `).bind(nextVer, jsonStr).run();

  return { version: nextVer };
}

// ── Autenticación ────────────────────────────────────────────────────────────

async function handleLogin(request: Request, env: Env): Promise<Response> {
  const { ADMIN_USER, JWT_SECRET } = env;
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

  // Si el import viene de un Excel sin columna de stock (stock === undefined),
  // rescatamos el stock vigente en D1 para no pisarlo con 0.
  let finalProducts = products;
  if (products.some((p) => p.stock === undefined)) {
    const prevRows = await db.prepare('SELECT codigo_unico, stock FROM products').all<{ codigo_unico: string; stock: number }>();
    const prevStock = new Map<string, number>((prevRows.results ?? []).map((r) => [r.codigo_unico, Number(r.stock) || 0]));
    finalProducts = products.map((p) => ({
      ...p,
      stock: p.stock !== undefined ? p.stock : (prevStock.get(p.codigo_unico) ?? 0),
    }));
  }

  const hash = await computeCatalogHash(finalProducts.map(toRef));

  // Backup: copia el catálogo activo al slot más viejo.
  const target = meta.newest === 'bak' ? 'bak2' : 'bak';
  const nuevoState = JSON.stringify({ newest: target });
  const bakTable = BAK[target === 'bak' ? 0 : 1];

  const batch: D1PreparedStatement[] = [
    db.prepare(`DELETE FROM ${bakTable}`),
    db.prepare(`INSERT INTO ${bakTable} ${LIST_COLS} SELECT ${PRODUCT_COLS} FROM products`),
    db.prepare('DELETE FROM products'),
    ...multiInsert(db, 'products', finalProducts),
    metaUpdate(db, newRevision, hash, nuevoState),
  ];
  await runBatch(db, batch);
  return { revision: newRevision, hash, backup: target, updated_at: new Date().toISOString().replace('T', ' ').slice(0, 19) };
}

/** Upsert parcial y/o bajas atómicas (sin wipe ni backup): edición de 1 o N productos + bajas. */
async function upsertProducts(db: D1Database, products: Product[], deletedCodes: string[] = []) {
  const meta = await getMetadata(db);
  const newRevision = meta.revision + 1;

  const res = await db.prepare(`SELECT ${PRODUCT_COLS} FROM products`).all<RowProducto>();
  const mapa = new Map<string, Product>((res.results ?? []).map((r) => [r.codigo_unico, rowToProduct(r)]));
  for (const code of deletedCodes) mapa.delete(code);
  const normalizedForUpsert: Product[] = products.map((p) => {
    const prev = mapa.get(p.codigo_unico);
    const stockFinal = p.stock !== undefined ? p.stock : (prev?.stock ?? 0);
    const merged: Product = { ...p, stock: stockFinal };
    mapa.set(p.codigo_unico, merged);
    return merged;
  });
  const hash = await computeCatalogHash([...mapa.values()].map(toRef));

  const batch: D1PreparedStatement[] = [
    metaUpdate(db, newRevision, hash, JSON.stringify({ newest: meta.newest })),
  ];
  for (const code of deletedCodes) {
    batch.push(db.prepare('DELETE FROM products WHERE codigo_unico = ?').bind(code));
  }
  for (const p of normalizedForUpsert) {
    const l = p.precios;
    batch.push(
      db
        .prepare(
          `INSERT INTO products ${LIST_COLS} VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
           ON CONFLICT(codigo_unico) DO UPDATE SET
             fabrica = excluded.fabrica,
             codigo = excluded.codigo,
             descripcion = excluded.descripcion,
             costo_neto = excluded.costo_neto,
             costo_iva = excluded.costo_iva,
             lista1 = excluded.lista1,
             lista2 = excluded.lista2,
             lista3 = excluded.lista3,
             lista4 = excluded.lista4,
             categoria = excluded.categoria,
             oferta = excluded.oferta,
             stock = excluded.stock,
             updated_at = datetime('now')`,
        )
        .bind(
          p.codigo_unico,
          fabPersistida(p.fabrica),
          p.codigo || '',
          p.descripcion,
          p.costo_neto,
          p.costo_iva,
          Number(l[0]) || 0,
          Number(l[1]) || 0,
          Number(l[2]) || 0,
          Number(l[3]) || 0,
          p.categoria || '',
          p.oferta ? 1 : 0,
          Number(p.stock) || 0,
        ),
    );
  }
  await runBatch(db, batch);
  return { revision: newRevision, hash, upserted: normalizedForUpsert.length, deleted: deletedCodes.length, updated_at: new Date().toISOString().replace('T', ' ').slice(0, 19) };
}

async function adminDeleteProduct(db: D1Database, codigoUnico: string) {
  const meta = await getMetadata(db);
  const newRevision = meta.revision + 1;

  const res = await db
    .prepare(`SELECT ${PRODUCT_COLS} FROM products WHERE codigo_unico != ?`)
    .bind(codigoUnico)
    .all<RowProducto>();
  const hash = await computeCatalogHash((res.results ?? []).map(rowToProduct).map(toRef));

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
  const res = await db.prepare(`SELECT ${PRODUCT_COLS} FROM ${chosenTable}`).all<RowProducto>();
  const hash = await computeCatalogHash((res.results ?? []).map(rowToProduct).map(toRef));

  await runBatch(db, [
    // Guardar el catálogo actual (pre-rollback) en el otro slot.
    db.prepare(`DELETE FROM ${otherTable}`),
    db.prepare(`INSERT INTO ${otherTable} ${LIST_COLS} SELECT ${PRODUCT_COLS} FROM products`),
    db.prepare('DELETE FROM products'),
    db.prepare(`INSERT INTO products ${LIST_COLS} SELECT ${PRODUCT_COLS} FROM ${chosenTable}`),
    metaUpdate(db, newRevision, hash, JSON.stringify({ newest: other })),
  ]);
  return { revision: newRevision, hash, restaurado: chosen };
}

// ── Endpoints ────────────────────────────────────────────────────────────────

/**
 * GET /api/v1/sync — contrato ADITIVO.
 * Los campos viejos { codigo_unico, nombre, precios } siguen byte idénticos:
 * Android los exige (getString("nombre") + getJSONArray("precios")). Solo se
 * AGREGAN los campos nuevos, así los clientes viejos los ignoran (org.json no
 * falla con campos de más) y los nuevos dejan de adivinar:
 *   · descripcion = nombre (misma cadena con la clave nueva)
 *   · oferta      → los 4 precios iguales se confirman con el campo, no a mano
 */
/**
 * Lee products aplicando los params aditivos (?since y ?page/?limit).
 * El orden es por codigo_unico: estable, así las páginas no se pisan.
 * serverTime se toma ANTES de leer (reloj de la D1, el mismo que escribe
 * updated_at): puede repetir filas, nunca perderlas.
 */
async function leerProducts(db: D1Database, cols: string, q: SyncQuery) {
  const where = q.since !== undefined ? ' WHERE updated_at >= ?' : '';
  const binds: (string | number)[] = q.since !== undefined ? [q.since] : [];

  let serverTime: string | undefined;
  if (q.since !== undefined) {
    serverTime = (await db.prepare("SELECT datetime('now') AS t").first<{ t: string }>())?.t ?? '';
  }

  const paginado = q.page !== undefined && q.limit !== undefined;
  let total: number | undefined;
  if (paginado) {
    const count = db.prepare(`SELECT COUNT(*) AS n FROM products${where}`);
    const row = binds.length ? await count.bind(...binds).first<{ n: number }>() : await count.first<{ n: number }>();
    total = row?.n ?? 0;
  }

  let sql = `SELECT ${cols} FROM products${where} ORDER BY codigo_unico`;
  if (paginado) {
    sql += ' LIMIT ? OFFSET ?';
    binds.push(q.limit as number, ((q.page as number) - 1) * (q.limit as number));
  }
  const stmt = binds.length ? db.prepare(sql).bind(...binds) : db.prepare(sql);
  const res = await stmt.all<RowProducto>();
  return { rows: res.results ?? [], total, serverTime };
}

/** Enriquece el envelope SOLO cuando hubo params: sin args el shape no cambia. */
function conExtras(body: Record<string, unknown>, q: SyncQuery, total?: number, serverTime?: string) {
  if (serverTime !== undefined) body.serverTime = serverTime;
  if (q.page !== undefined && q.limit !== undefined) {
    Object.assign(body, pageMeta(q.page, q.limit, total ?? 0));
  }
  return body;
}

async function handleSync(db: D1Database, params: URLSearchParams) {
  const meta = await getMetadata(db);
  const q = parseSyncQuery(params, FIELDS_V1);
  if (!q.ok) return err(q.error, 400);
  const { rows, total, serverTime } = await leerProducts(db, PRODUCT_COLS, q.query);

  const products = rows.map((r) => pickFields({
    codigo_unico: r.codigo_unico,
    nombre: r.descripcion,
    precios: listasDe(r).map((n) => Math.round(n)),
    fabrica: r.fabrica ?? '',
    codigo: r.codigo ?? '',
    descripcion: r.descripcion,
    costo_neto: r.costo_neto,
    oferta: r.oferta === 1,
    stock: Number(r.stock) || 0,
  }, q.query.fields));

  return json(conExtras({ revision: meta.revision, hash: meta.hash, updated_at: meta.updated_at, products }, q.query, total, serverTime));
}

/**
 * GET /api/v1/sync/v2 — contrato nuevo, la forma la decide config.sync_mode.
 * El envelope (revision + hash) es igual al v1: el hash sigue siendo el
 * canónico del catálogo completo, así cualquier cliente puede comparar.
 */
async function handleSyncV2(db: D1Database, params: URLSearchParams) {
  const meta = await getMetadata(db);
  const modo = await getSyncMode(db);
  const q = parseSyncQuery(params, modo === 'descripcion-costos' ? FIELDS_V2_DC : FIELDS_V2);
  if (!q.ok) return err(q.error, 400);
  // updated_at NO está en PRODUCT_COLS (esas columnas alimentan INSERT/backup):
  // se suma SOLO acá, que es donde v2 expone la fila completa.
  const { rows, total, serverTime } = await leerProducts(db, `${PRODUCT_COLS}, updated_at`, q.query);

  if (modo === 'descripcion-costos') {
    // Únicamente { descripcion, costo }. Sin listas, sin costo+IVA,
    // sin categoría, sin fábrica.
    const products = rows.map((r) => pickFields({ descripcion: r.descripcion, costo: r.costo_neto }, q.query.fields));
    return json(conExtras({ revision: meta.revision, hash: meta.hash, updated_at: meta.updated_at, format: modo, products }, q.query, total, serverTime));
  }

  const products = rows.map((r) => pickFields({
    codigo_unico: r.codigo_unico,
    descripcion: r.descripcion,
    precios: listasDe(r).map((n) => Math.round(n)),
    categoria: r.categoria ?? '',
    fabrica: r.fabrica ?? '',
    codigo: r.codigo ?? '',
    oferta: r.oferta === 1,
    stock: Number(r.stock) || 0,
    costo_neto: r.costo_neto,
    costo_iva: r.costo_iva,
    updated_at: r.updated_at,
  }, q.query.fields));
  return json(conExtras({ revision: meta.revision, hash: meta.hash, updated_at: meta.updated_at, format: modo, products }, q.query, total, serverTime));
}

/** GET /api/v1/sync-config → { modo }. Lectura libre: es solo un formato. */
async function handleSyncConfig(db: D1Database) {
  return json({ modo: await getSyncMode(db), opciones: SYNC_MODES });
}

/** POST /api/v1/admin/sync-config { modo } → { modo } (admin). */
async function handleSyncConfigWrite(request: Request, db: D1Database): Promise<Response> {
  let body: { modo?: unknown };
  try {
    body = await request.json();
  } catch {
    return err('Body inválido', 400);
  }
  const modo = body?.modo;
  if (typeof modo !== 'string' || !(SYNC_MODES as string[]).includes(modo)) {
    return err(`\`modo\` debe ser uno de: ${SYNC_MODES.join(', ')}`, 400);
  }
  await setSyncMode(db, modo as SyncMode);
  return json({ modo });
}

/** GET /api/v1/parser-config → { version, config, updated_at }. Público, lectura rápida con versión. */
async function handleParserConfig(db: D1Database) {
  const row = await getParserConfig(db);
  return json(row);
}

/** POST /api/v1/admin/parser-config { config } → { version, ok: true } (admin). */
async function handleParserConfigWrite(request: Request, db: D1Database): Promise<Response> {
  let body: { config?: unknown };
  try {
    body = await request.json();
  } catch {
    return err('Body inválido', 400);
  }
  const config = body?.config;
  if (!config || typeof config !== 'object' || Array.isArray(config)) {
    return err('`config` debe ser un objeto', 400);
  }
  const res = await setParserConfig(db, config as Record<string, unknown>);
  return json({ ok: true, version: res.version });
}

/** GET /api/v1/mapa-catalogo — para el editor de fotos (sde11). */
async function handleMapa(db: D1Database) {
  const meta = await getMetadata(db);
  const res = await db
    .prepare('SELECT codigo_unico, descripcion FROM products ORDER BY codigo_unico')
    .all<{ codigo_unico: string; descripcion: string }>();
  const productos = (res.results ?? []).map((r) => ({
    codigo_unico: r.codigo_unico,
    nombre: r.descripcion,
    modelo: partir(r.descripcion).codigo || null,
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
    colCategoria: typeof body.colCategoria === 'number' ? body.colCategoria : undefined,
    colFabrica: typeof body.colFabrica === 'number' ? body.colFabrica : undefined,
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

/**
 * Acepta DOS formas y las normaliza al modelo completo:
 *   · NUEVA  : { codigo_unico, descripcion, costo_neto?, costo_iva?,
 *               precios[4] | lista1..lista4, categoria?, fabrica?, codigo?,
 *               oferta? }
 *   · VIEJA  : { codigo_unico, nombre, precios[4] }   ← el shape de siempre
 *
 * Si no vienen los costos se DERIVAN de la L4 con los defaults (21 / 50), así
 * un cliente viejo no deja columnas en 0. Si la página mandó los costos, se
 * respetan tal cual. `codigo` y `oferta` son OPCIONALES por el mismo motivo:
 * los clientes que no los conocen quedan con '' / false (nunca NULL).
 * `fabrica` es la ÚNICA que no puede quedar vacía: ausente o en blanco →
 * SIN_FABRICA (regla de negocio: ningún artículo queda sin fábrica).
 */
function normalizeProducts(body: unknown): Product[] {
  const list: unknown[] = Array.isArray(body)
    ? body
    : body && typeof body === 'object' && Array.isArray((body as { products?: unknown }).products)
      ? (body as { products: unknown[] }).products
      : [body];

  const vacio = list.length === 0 || (list.length === 1 && (!list[0] || typeof list[0] !== 'object'));
  if (vacio) throw new Error('Body vacío: enviá un producto, un array o { products: [...] }');

  return list.map((raw) => {
    const p = raw as Record<string, unknown>;
    if (typeof p.codigo_unico !== 'string' || !p.codigo_unico.trim()) throw new Error('codigo_unico inválido');

    const descripcion = typeof p.descripcion === 'string' && p.descripcion.trim()
      ? p.descripcion.trim()
      : typeof p.nombre === 'string' && p.nombre.trim()
        ? p.nombre.trim()
        : '';
    if (!descripcion) throw new Error('descripcion (o nombre) inválido');

    // Lista de precios: array `precios` o columnas `lista1..lista4`.
    let precios: number[];
    if (Array.isArray(p.precios)) {
      if (p.precios.length !== 4) throw new Error('precios inválido: se esperan exactamente 4 listas [L1, L2, L3, L4]');
      precios = p.precios.map((n) => Math.round(Number(n)));
      if (precios.some((n) => !Number.isFinite(n))) throw new Error('precios inválido');
    } else if (['lista1', 'lista2', 'lista3', 'lista4'].every((k) => typeof p[k] === 'number')) {
      precios = ['lista1', 'lista2', 'lista3', 'lista4'].map((k) => Math.round(Number(p[k])));
      if (precios.some((n) => !Number.isFinite(n))) throw new Error('lista1..lista4 inválido');
    } else {
      throw new Error('Faltan precios: mandá `precios` [L1..L4] o `lista1..lista4`');
    }

    const derivado = derivarCostos(precios, PRECIOS_DEFAULT);
    const costo_neto = Number.isFinite(Number(p.costo_neto)) ? Number(p.costo_neto) : derivado.costo_neto;
    const costo_iva = Number.isFinite(Number(p.costo_iva)) ? Number(p.costo_iva) : derivado.costo_iva;
    const stock = p.stock !== undefined && p.stock !== null && Number.isFinite(Number(p.stock))
      ? Number(p.stock)
      : undefined;

    return {
      codigo_unico: p.codigo_unico.trim(),
      descripcion,
      costo_neto,
      costo_iva,
      precios,
      categoria: typeof p.categoria === 'string' ? p.categoria.trim() : '',
      fabrica: fabPersistida(typeof p.fabrica === 'string' ? p.fabrica : ''),
      codigo: typeof p.codigo === 'string' ? p.codigo.trim() : '',
      oferta: p.oferta === true || p.oferta === 1 || p.oferta === 'true',
      stock,
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
        // Aditivo: los campos viejos intactos + los nuevos. No quitar nada.
        res = await handleSync(env.DB, url.searchParams);
      } else if (method === 'GET' && path === '/api/v1/sync/v2') {
        res = await handleSyncV2(env.DB, url.searchParams);
      } else if (method === 'GET' && path === '/api/v1/sync-config') {
        res = await handleSyncConfig(env.DB);
      } else if (method === 'GET' && path === '/api/v1/parser-config') {
        res = await handleParserConfig(env.DB);
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
          const body = (await request.json()) as { products?: unknown; deleted?: unknown; replace?: unknown };
          const deletedCodes = Array.isArray(body?.deleted)
            ? body.deleted.map((c) => String(c).trim()).filter(Boolean)
            : [];
          if (body?.replace === true) {
            const productos = normalizeProducts(body);
            if (productos.length === 0) res = err('Reemplazo con catálogo vacío', 400);
            else res = json(await replaceCatalog(env.DB, productos));
          } else {
            const hasProducts = Array.isArray(body) ? body.length > 0 : Array.isArray(body?.products) && body.products.length > 0;
            const productos = hasProducts || deletedCodes.length === 0 ? normalizeProducts(body) : [];
            res = json(await upsertProducts(env.DB, productos, deletedCodes));
          }
        }
      } else if (method === 'POST' && path === '/api/v1/admin/import') {
        const auth = await requireAdmin(request, env);
        if (!auth.ok) res = err(auth.reason ?? 'No autorizado', 401);
        else res = await handleImport(request, env);
      } else if (method === 'POST' && path === '/api/v1/admin/sync-config') {
        const auth = await requireAdmin(request, env);
        if (!auth.ok) res = err(auth.reason ?? 'No autorizado', 401);
        else res = await handleSyncConfigWrite(request, env.DB);
      } else if (method === 'POST' && path === '/api/v1/admin/parser-config') {
        const auth = await requireAdmin(request, env);
        if (!auth.ok) res = err(auth.reason ?? 'No autorizado', 401);
        else res = await handleParserConfigWrite(request, env.DB);
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
