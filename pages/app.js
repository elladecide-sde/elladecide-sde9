// app.js — LOLA catálogo: login JWT + parseo de Excel en el navegador + subida.
//
// La PÁGINA es la generadora (SheetJS + LolaCodigo + precios), el worker es el
// mensajero que reemplaza el catálogo (replace). La regla de codigo_unico vive
// en codigoUnico.js, port EXACTO del backend (verificado con el catálogo real).
//
// El panel está dividido en 4 secciones (tabs):
//   · Inventario      → lista completa del catálogo + buscador fuzzy + editar/borrar
//   · Catálogo        → Excel → generar → subir (replace) + rollback
//   · Configuración   → precios (IVA/factores), modo de sync y contraseña
//   · Administración  → parser de artículos: fábrica / código / descripción /
//                       oferta por fila + aplicar las fábricas al catálogo
//
// Precios (espejo de backend/src/modelo.ts):
//   costo + IVA = Lista 4 / (1 + inversa/100)        → Lista 4 / 1,50
//   costo neto  = (costo + IVA) / (1 + IVA/100)      → Lista 4 / (1,50 × 1,21)
//   Lista n     = round(costo neto × (1 + IVA/100) × factor_n)
// Nada se descarta: cada producto guarda costo_neto, costo_iva y las 4 listas.
// Oferta (termina en ".", "oferta" o "liq") → las 4 iguales al precio del Excel,
// pero el costo se deriva igual.

const API_BASE = 'https://lola-backend.elladecide.workers.dev';

const $ = (id) => document.getElementById(id);

// ── Config de precios (editable, persistida en localStorage) ────────────────
// Defaults = los valores que siempre estuvieron hardcodeados.
const CFG_KEY = 'lola_precios_cfg';
const CFG_DEFAULT = { iva: 21, inversaLista: 50, l1: 2.32, l2: 2.12, l3: 1.65 };
const OFERTA_KEYWORDS = ['oferta', 'liq'];
// Fábrica de respaldo (espejo de articleParser.js y del backend): ningún
// artículo del catálogo queda sin fábrica.
const SIN_FABRICA = 'SIN FABRICA';

function cargarCfg() {
  try {
    const raw = JSON.parse(localStorage.getItem(CFG_KEY) || 'null');
    if (raw && typeof raw === 'object') {
      const n = (v, d) => (Number.isFinite(Number(v)) ? Number(v) : d);
      return {
        iva: n(raw.iva, CFG_DEFAULT.iva),
        inversaLista: n(raw.inversaLista, CFG_DEFAULT.inversaLista),
        l1: n(raw.l1, CFG_DEFAULT.l1),
        l2: n(raw.l2, CFG_DEFAULT.l2),
        l3: n(raw.l3, CFG_DEFAULT.l3),
      };
    }
  } catch (e) { /* cfg corrupta → defaults */ }
  return { ...CFG_DEFAULT };
}

function guardarCfg(cfg) {
  localStorage.setItem(CFG_KEY, JSON.stringify(cfg));
}

let CFG = cargarCfg();

// La inversa del IVA (el 1,21 con el que se DIVIDE para volver al costo neto)
// es el MISMO IVA que se multiplica: por eso sale de un solo control.
// La L4 se deriva del factor inversa (1 + inversa/100), no se edita a mano.
function cfgFormula(c) {
  return {
    iva: c.iva,
    inversaIva: c.iva,
    inversaLista: c.inversaLista,
    listas: [c.l1, c.l2, c.l3, 1 + c.inversaLista / 100],
  };
}

// ── Autenticación (JWT) ─────────────────────────────────────────────────────
const TOKEN_KEY = 'lola_token';
const getToken = () => localStorage.getItem(TOKEN_KEY) || '';
const setToken = (t) => localStorage.setItem(TOKEN_KEY, t);
const clearToken = () => localStorage.removeItem(TOKEN_KEY);

async function login(username, password) {
  const res = await fetch(`${API_BASE}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  if (!res.ok) {
    let msg = `HTTP ${res.status}`;
    try { msg = (await res.json()).error || msg; } catch (e) { /* ignore */ }
    throw new Error(msg);
  }
  return (await res.json()).token;
}

/**
 * Llamada autenticada al worker. Centraliza el Bearer, el manejo de errores y
 * el cierre de sesión automático en 401 (el token vive 24 h).
 */
async function apiAdmin(path, { method = 'POST', body } = {}) {
  const token = getToken();
  if (!token) {
    renderAuth();
    throw new Error('Sesión expirada. Volvé a ingresar.');
  }
  const res = await fetch(`${API_BASE}${path}`, {
    method,
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!res.ok) {
    let msg = `HTTP ${res.status}`;
    try { msg = (await res.json()).error || msg; } catch (e) { /* ignore */ }
    if (res.status === 401) { clearToken(); renderAuth(); }
    throw new Error(msg);
  }
  return res.json();
}

// ── Lógica de precios (port de esOferta + calcularCostos) ───────────────────
function esOferta(nombre) {
  const n = String(nombre).trim();
  if (n.endsWith('.')) return true;
  const low = n.toLowerCase();
  return OFERTA_KEYWORDS.some((kw) => low.endsWith(kw));
}

/**
 * Fórmula completa a partir de la Lista 4 del Excel.
 * Devuelve TODO: costo neto, costo + IVA y las 4 listas (nada se descarta).
 *
 * El orden de operaciones es el ORIGINAL a propósito: `L4 / (1,21 × 1,50)` NO
 * es bit a bit igual que `(L4 / 1,50) / 1,21` en coma flotante, y un redondeo
 * distinto movería ~1,7% de los precios y el hash del catálogo.
 */
function calcularPrecios(precioLista4) {
  const cfg = cfgFormula(CFG);
  const IVA = 1 + cfg.iva / 100;
  const INVERSA_IVA = 1 + cfg.inversaIva / 100;
  const INVERSA_LISTA = 1 + cfg.inversaLista / 100;
  const costo_iva = precioLista4 / INVERSA_LISTA;                        // Lista 4 / 1,50
  const costo_neto = precioLista4 / (INVERSA_IVA * INVERSA_LISTA);       // / (1,21 × 1,50)
  const precios = cfg.listas.map((f) => Math.round(costo_neto * IVA * f));
  return { costo_neto, costo_iva, precios };
}

// ── Parseo + generación (espejo de importCatalogo, con LolaCodigo) ──────────
function leerTexto(row, col) {
  if (!(col >= 0)) return '';
  const v = row[col];
  if (v === undefined || v === null) return '';
  const s = String(v).trim();
  return s.toLowerCase() === 'none' ? '' : s;
}

function procesarCatalogo(rows, colNombre, colPrecio, colCodigo, colCategoria, colFabrica, skipRows) {
  const filasOk = [];
  const rechazadas = [];

  for (let i = skipRows; i < rows.length; i++) {
    const row = rows[i];
    if (!Array.isArray(row)) continue;

    const nombre = String(row[colNombre] ?? '').trim();
    if (!nombre || nombre.toLowerCase() === 'none' || nombre.length <= 3) {
      rechazadas.push({ fila: i + 1, motivo: 'nombre vacío o corto' });
      continue;
    }
    if (!Number.isNaN(Number(nombre))) {
      rechazadas.push({ fila: i + 1, motivo: 'número suelto' });
      continue;
    }

    const precio = Number(row[colPrecio]);
    if (!Number.isFinite(precio) || precio <= 0) {
      rechazadas.push({ fila: i + 1, motivo: 'precio inválido' });
      continue;
    }

    let codigoExcel = '';
    if (colCodigo >= 0) {
      const raw = String(row[colCodigo] ?? '').trim();
      if (raw && raw.toLowerCase() !== 'none') {
        codigoExcel = Number.isNaN(Number(raw)) ? raw : String(Math.trunc(Number(raw)));
      }
    }
    filasOk.push({
      nombre,
      precio,
      codigoExcel,
      categoria: leerTexto(row, colCategoria),
      fabrica: leerTexto(row, colFabrica),
    });
  }

  // Dedup por nombre normalizado (mismo criterio que el backend).
  const porNombre = new Map();
  const descartados = [];
  for (const f of filasOk) {
    const k = LolaCodigo.nombreNormalizado(f.nombre);
    if (!porNombre.has(k)) porNombre.set(k, f);
    else descartados.push(f.nombre);
  }
  const unicas = [...porNombre.values()];

  // codigo_unico determinístico (regla de la página = backend).
  const { codigos } = LolaCodigo.asignarCodigos(unicas.map((f) => ({ nombre: f.nombre })));

  // Parser de artículos (fábrica · código · oferta). Corre acá adentro (nunca
  // al cargar el archivo): window.LolaParser lo define el módulo ESM de
  // articleParser.js y este handler siempre es un click posterior a la carga.
  // Sin parser (o cargando) se sigue el flujo de siempre: el Excel manda solo.
  const P = window.LolaParser;
  const parsed = P ? P.buildPayload(unicas.map((f) => f.nombre)) : null;

  // Modelo interno COMPLETO: costo neto + costo con IVA + las 4 listas +
  // categoría + fábrica + código + oferta. Nada se tira.
  const productos = unicas.map((f, i) => {
    const c = calcularPrecios(f.precio);
    const pl = parsed ? parsed[i] : null;
    // Oferta = unión de AMBAS detecciones (más certero): sufijos de siempre
    // (termina en '.', 'oferta', 'liq') + marcas del parser (PROMO, REMATE…).
    const oferta = esOferta(f.nombre) || !!(pl && pl.oferta);
    // Fábrica/código: el Excel primero (dato fuente); si viene vacío, el parser.
    // "SIN FABRICA" es una fábrica MÁS: si ni el Excel ni el parser traen
    // marca, se guarda esa — ningún artículo queda sin fábrica.
    const fabParser = pl ? pl.fabrica : '';
    return {
      codigo_unico: codigos.get(LolaCodigo.nombreNormalizado(f.nombre)),
      descripcion: f.nombre, // descripción CRUDA: búsquedas y v1 intactos
      costo_neto: c.costo_neto,
      costo_iva: c.costo_iva,
      precios: oferta ? Array(4).fill(Math.round(f.precio)) : c.precios,
      categoria: f.categoria,
      fabrica: f.fabrica || fabParser || SIN_FABRICA,
      codigo: f.codigoExcel || (pl ? pl.codigo : ''),
      oferta,
    };
  });

  const colisiones = productos.length - new Set(productos.map((p) => p.codigo_unico)).size;

  return { productos, descartados, rechazadas, colisiones };
}

// ── Subida al backend (reemplazo completo, atómico en el worker) ────────────
async function subirCatalogo(productos) {
  return apiAdmin('/api/v1/admin/products', { body: { products: productos, replace: true } });
}

// ── Rollback (restaurar un backup del Worker) ───────────────────────────────
async function rollbackCatalogo(backup) {
  return apiAdmin('/api/v1/admin/rollback', { body: { backup } });
}

function setRollbackStatus(msg, ok) {
  const el = $('rollbackStatus');
  el.textContent = msg;
  el.className = 'status ' + (ok ? 'ok' : 'err');
}

function bindRollback(btnId, backup, label) {
  $(btnId).addEventListener('click', async () => {
    if (!confirm(`¿Restaurar el catálogo desde ${label}?\n\nEl catálogo actual se guarda en el otro backup (no se pierde) y sube la revisión: todas las apps se van a resincronizar solas.`)) return;
    try {
      setRollbackStatus(`Restaurando ${label}…`, true);
      const r = await rollbackCatalogo(backup);
      inventario = null; // el catálogo cambió por completo
      setRollbackStatus(`✅ Restaurado ${r.restaurado} — revisión ${r.revision} · hash ${(r.hash || '').slice(0, 8)}…`, true);
    } catch (e) {
      setRollbackStatus('❌ ' + e.message, false);
    }
  });
}

bindRollback('rollbackBak', 'bak', 'bak (el último reemplazo)');
bindRollback('rollbackBak2', 'bak2', 'bak2 (el anterior)');

// ── Cambio de contraseña ────────────────────────────────────────────────────
async function changePassword(currentPassword, newPassword) {
  return apiAdmin('/api/v1/auth/change-password', {
    body: { current_password: currentPassword, new_password: newPassword },
  });
}

// ── UI base ─────────────────────────────────────────────────────────────────
let productosGenerados = null;

function setStatus(msg, ok) {
  const el = $('status');
  el.textContent = msg;
  el.className = 'status ' + (ok ? 'ok' : 'err');
}

function setLoginStatus(msg, ok) {
  const el = $('loginStatus');
  el.textContent = msg;
  el.className = 'status ' + (ok ? 'ok' : 'err');
}

// ── Secciones (tabs) ────────────────────────────────────────────────────────
const VISTAS = ['inventario', 'catalogo', 'config', 'admin'];
let vistaActual = 'inventario';

function setVista(v) {
  if (!VISTAS.includes(v)) return;
  vistaActual = v;
  for (const n of VISTAS) $('view-' + n).hidden = n !== v;
  document.querySelectorAll('.tab').forEach((b) => b.classList.toggle('active', b.dataset.vista === v));

  if (v === 'inventario' && inventario === null) cargarInventario();
  if (v === 'config') cargarModoSync();
}

document.querySelectorAll('.tab').forEach((b) => {
  b.addEventListener('click', () => setVista(b.dataset.vista));
});

function renderAuth() {
  const logged = !!getToken();
  $('view-login').hidden = logged;
  $('tabs').hidden = !logged;
  $('sessionBlock').hidden = !logged;
  if (!logged) {
    productosGenerados = null;
    inventario = null;
    adminItems = null;
    adminPendientes = null;
    vistaActual = 'inventario';
    for (const n of VISTAS) $('view-' + n).hidden = true;
    $('invSearch').value = '';
    $('preview').hidden = true;
    $('subir').disabled = true;
    ocultarResultadosAdmin();
    $('adminAplicar').disabled = true;
    setAdminStatus('', true);
    setStatus('', true);
  } else {
    setVista(vistaActual);
  }
}

$('loginBtn').addEventListener('click', async () => {
  const u = $('username').value.trim();
  const p = $('password').value;
  if (!u || !p) { setLoginStatus('Ingresá usuario y contraseña', false); return; }
  try {
    setLoginStatus('Ingresando…', true);
    const token = await login(u, p);
    setToken(token);
    $('password').value = '';
    renderAuth();
    setLoginStatus('', true);
  } catch (e) {
    setLoginStatus('❌ ' + e.message, false);
  }
});

$('logoutBtn').addEventListener('click', () => {
  clearToken();
  renderAuth();
});

$('changePwdBtn').addEventListener('click', () => {
  const b = $('changePwdBlock');
  b.hidden = !b.hidden;
  $('changePwdStatus').textContent = '';
  $('changePwdStatus').className = 'status';
});

$('savePwdBtn').addEventListener('click', async () => {
  const current = $('currentPwd').value;
  const nueva = $('newPwd').value;
  const nueva2 = $('newPwd2').value;
  const st = $('changePwdStatus');
  if (!current || !nueva) { st.textContent = 'Completá todos los campos'; st.className = 'status err'; return; }
  if (nueva.length < 8) { st.textContent = 'La contraseña debe tener al menos 8 caracteres'; st.className = 'status err'; return; }
  if (nueva !== nueva2) { st.textContent = 'Las contraseñas no coinciden'; st.className = 'status err'; return; }
  try {
    await changePassword(current, nueva);
    st.textContent = '✅ Contraseña actualizada';
    st.className = 'status ok';
    $('currentPwd').value = '';
    $('newPwd').value = '';
    $('newPwd2').value = '';
    $('changePwdBlock').hidden = true;
  } catch (e) {
    st.textContent = '❌ ' + e.message;
    st.className = 'status err';
  }
});

// ── Sección Catálogo: Excel → generar → subir ───────────────────────────────
function setArchivo(f) {
  $('fileInfo').textContent = f ? `📄 ${f.name} (${(f.size / 1024).toFixed(0)} KB)` : '';
  $('dzTitle').textContent = f ? f.name : 'Arrastrá el Excel acá';
  productosGenerados = null;
  $('subir').disabled = true;
  $('preview').hidden = true;
}

$('excel').addEventListener('change', (e) => setArchivo(e.target.files[0]));

// Drag & drop
const dz = $('dropzone');
['dragover', 'dragenter'].forEach((ev) => dz.addEventListener(ev, (e) => { e.preventDefault(); dz.classList.add('dragging'); }));
['dragleave', 'drop'].forEach((ev) => dz.addEventListener(ev, (e) => { e.preventDefault(); dz.classList.remove('dragging'); }));
dz.addEventListener('drop', (e) => {
  const f = e.dataTransfer.files && e.dataTransfer.files[0];
  if (f) {
    $('excel').files = e.dataTransfer.files;
    setArchivo(f);
  }
});

$('generar').addEventListener('click', async () => {
  const file = $('excel').files[0];
  if (!file) { setStatus('Seleccioná un archivo Excel primero', false); return; }
  if (!window.XLSX) { setStatus('Lector de Excel no cargado', false); return; }

  try {
    setStatus('Procesando…', true);
    const data = new Uint8Array(await file.arrayBuffer());
    const wb = XLSX.read(data, { type: 'array' });
    const ws = wb.Sheets[wb.SheetNames[0]];
    const rows = XLSX.utils.sheet_to_json(ws, { header: 1 });

    const colNombre = parseInt($('colNombre').value, 10) || 1;
    const colPrecio = parseInt($('colPrecio').value, 10) || 2;
    const colCodigo = parseInt($('colCodigo').value, 10) || -1;
    const colCategoria = parseInt($('colCategoria').value, 10);
    const colFabrica = parseInt($('colFabrica').value, 10);
    const skipRows = parseInt($('skipRows').value, 10) || 0;

    const r = procesarCatalogo(
      rows, colNombre, colPrecio, colCodigo,
      Number.isFinite(colCategoria) ? colCategoria : -1,
      Number.isFinite(colFabrica) ? colFabrica : -1,
      skipRows,
    );
    if (!r.productos.length) { setStatus('No se encontraron productos válidos. Revisá las columnas.', false); return; }

    productosGenerados = r.productos;
    renderPreview(r);
    $('subir').disabled = false;

    let msg = `✅ ${r.productos.length} productos · ${r.productos.length - r.colisiones} códigos únicos · ${r.descartados.length} duplicados`;
    if (r.rechazadas.length) msg += ` · ${r.rechazadas.length} filas rechazadas`;
    setStatus(msg + ' — listos para subir', true);
  } catch (err) {
    setStatus('❌ ' + err.message, false);
  }
});

$('subir').addEventListener('click', async () => {
  if (!productosGenerados || !productosGenerados.length) return;
  try {
    setStatus('Subiendo (reemplazo del catálogo)…', true);
    const r = await subirCatalogo(productosGenerados);
    inventario = null; // el catálogo cambió por completo
    setStatus(`☁️ Catálogo reemplazado — revisión ${r.revision} · hash ${(r.hash || '').slice(0, 8)}… · backup ${r.backup}`, true);
  } catch (err) {
    setStatus('❌ ' + err.message, false);
  }
});

function renderPreview(r) {
  const body = $('previewBody');
  body.innerHTML = '';
  const fmt = (n) => Number(n).toLocaleString('es-AR', { maximumFractionDigits: 2 });
  const muestra = r.productos.slice(0, 10);
  const tabla = document.createElement('table');
  const thead = document.createElement('thead');
  const trh = document.createElement('tr');
  ['Código', 'Descripción', 'Costo neto', 'Costo+IVA', 'L1', 'L2', 'L3', 'L4'].forEach((t) => {
    const th = document.createElement('th');
    th.textContent = t;
    trh.appendChild(th);
  });
  thead.appendChild(trh);
  tabla.appendChild(thead);

  const tbody = document.createElement('tbody');
  muestra.forEach((p) => {
    const tr = document.createElement('tr');
    const celdas = [
      p.codigo_unico,
      p.descripcion,
      fmt(p.costo_neto),
      fmt(p.costo_iva),
      ...p.precios.map((x) => Number(x).toLocaleString('es-AR')),
    ];
    celdas.forEach((t) => {
      const td = document.createElement('td');
      td.textContent = t;
      tr.appendChild(td);
    });
    tbody.appendChild(tr);
  });
  tabla.appendChild(tbody);
  body.appendChild(tabla);
  if (r.productos.length > 10) {
    const más = document.createElement('p');
    más.className = 'muted';
    más.textContent = `… y ${r.productos.length - 10} más.`;
    body.appendChild(más);
  }
  $('preview').hidden = false;
}

// ── Sección Configuración: precios ──────────────────────────────────────────
function setCfgStatus(msg, ok) {
  const el = $('cfgStatus');
  el.textContent = msg;
  el.className = 'status ' + (ok ? 'ok' : 'err');
}

function pintarCfg() {
  $('cfgIva').value = CFG.iva;
  $('cfgInversa').value = CFG.inversaLista;
  $('cfgL1').value = CFG.l1;
  $('cfgL2').value = CFG.l2;
  $('cfgL3').value = CFG.l3;
}

function cfgDeUI() {
  const n = (id) => Number($(id).value);
  return { iva: n('cfgIva'), inversaLista: n('cfgInversa'), l1: n('cfgL1'), l2: n('cfgL2'), l3: n('cfgL3') };
}

/** Los productos ya generados quedaron calculados con la config vieja: se tiran. */
function invalidarGenerado(motivo) {
  if (!productosGenerados) return;
  productosGenerados = null;
  $('subir').disabled = true;
  $('preview').hidden = true;
  setStatus(motivo, false);
}

$('cfgGuardar').addEventListener('click', () => {
  const c = cfgDeUI();
  const malos = Object.entries(c).filter(([k, v]) => !Number.isFinite(v) || v < 0);
  if (malos.length) { setCfgStatus(`Revisá: "${malos[0][0]}" tiene que ser un número ≥ 0`, false); return; }
  if (1 + c.iva / 100 <= 0 || 1 + c.inversaLista / 100 <= 0) {
    setCfgStatus('Ni el IVA ni el factor de la L4 pueden ser ≤ -100%', false);
    return;
  }
  CFG = c;
  guardarCfg(CFG);
  invalidarGenerado('Configuración de precios cambiada — volvé a generar el catálogo');
  setCfgStatus(
    `✅ Guardado — costo neto = Lista 4 / (${(1 + c.inversaLista / 100).toFixed(4)} × ${(1 + c.iva / 100).toFixed(4)})`,
    true,
  );
});

$('cfgReset').addEventListener('click', () => {
  CFG = { ...CFG_DEFAULT };
  guardarCfg(CFG);
  pintarCfg();
  invalidarGenerado('Valores por defecto restaurados — volvé a generar el catálogo');
  setCfgStatus('↺ Restaurado a los valores por defecto (21% / 50% / 2.32 · 2.12 · 1.65)', true);
});

// ── Sección Configuración: modo de sync (config guardada en D1) ─────────────
function setSyncStatus(msg, ok) {
  const el = $('syncStatus');
  el.textContent = msg;
  el.className = 'status ' + (ok ? 'ok' : 'err');
}

async function cargarModoSync() {
  try {
    const res = await fetch(`${API_BASE}/api/v1/sync-config`);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const j = await res.json();
    $('syncMode').value = j.modo;
    setSyncStatus(`Modo actual: ${j.modo} — /api/v1/sync (v1) queda intacto`, true);
  } catch (e) {
    setSyncStatus('⚠️ No se pudo leer el modo de sync: ' + e.message, false);
  }
}

$('syncGuardar').addEventListener('click', async () => {
  const modo = $('syncMode').value;
  try {
    setSyncStatus('Aplicando…', true);
    const j = await apiAdmin('/api/v1/admin/sync-config', { body: { modo } });
    setSyncStatus(`✅ Modo "${j.modo}" — /api/v1/sync/v2 responde { ${j.modo === 'descripcion-costos' ? 'descripcion, costo' : 'todos los campos: descripcion, precios[], costos, categoria, fabrica, codigo, oferta, updated_at'} }`, true);
    inventario = null; // el modo define qué campos trae el inventario
  } catch (e) {
    setSyncStatus('❌ ' + e.message, false);
  }
});

// ════════════════════════════════════════════════════════════════════════════
// Sección Inventario — lista completa + buscador fuzzy token por token
// ════════════════════════════════════════════════════════════════════════════

let inventario = null;      // [{ codigo_unico, descripcion, precios, categoria, fabrica, _hay, _words, _cod }]
let invFiltrados = [];      // subconjunto ordenado según la búsqueda
let invVisible = 0;         // cuántos ya están en el DOM
let invSinMeta = false;     // modo de sync sin categoría/fábrica
let invCargando = false;
const INV_PAGINA = 120;

function setInvStatus(msg, ok) {
  const el = $('invStatus');
  el.textContent = msg;
  el.className = 'status ' + (ok ? 'ok' : 'err');
}

/** Minúsculas + sin acentos: "Cafetera" y "CAFETERA" y "cafetera" pegan igual. */
function normalizar(s) {
  return String(s ?? '').toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '');
}

const hayDe = (p) => normalizar([p.codigo_unico, p.descripcion, p.categoria, p.fabrica, p.codigo].filter(Boolean).join(' '));

/** Índice de búsqueda precalculado: hay (texto pegado) + palabras sueltas + código. */
const indexar = (p) => {
  const hay = hayDe(p);
  return { ...p, _hay: hay, _words: hay.split(/\s+/), _cod: normalizar(p.codigo_unico) };
};

/**
 * Levenshtein CON CORTE: devuelve max+1 en cuanto la fila se pasa, así no
 * sigue gastando CPU en palabras que ya no pueden competir.
 */
function editarDistCorte(a, b, max) {
  const la = a.length;
  const lb = b.length;
  if (Math.abs(la - lb) > max) return max + 1;
  let prev = new Array(lb + 1);
  let cur = new Array(lb + 1);
  for (let j = 0; j <= lb; j++) prev[j] = j;
  for (let i = 1; i <= la; i++) {
    cur[0] = i;
    let minFila = i;
    for (let j = 1; j <= lb; j++) {
      const costo = a[i - 1] === b[j - 1] ? 0 : 1;
      const v = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + costo);
      cur[j] = v;
      if (v < minFila) minFila = v;
    }
    if (minFila > max) return max + 1;
    const t = prev; prev = cur; cur = t;
  }
  return prev[lb];
}

/**
 * Score de UN token contra un artículo.
 *   · 100+  → el token está como substring (bonus si arranca una palabra o si
 *             está al principio: "cocina" pega antes en "cocina express").
 *   · 30-45 → el token pegó contra UNA palabra con ≤ 1 error (≥4 letras) o
 *             ≤ 2 errores (≥7 letras): "esprex" → "espresso".
 *   · 0     → no pegó: el token descarta el artículo (AND entre tokens).
 */
function scoreToken(tok, item) {
  const text = item._hay;
  const i = text.indexOf(tok);
  if (i >= 0) {
    const prevChar = i === 0 ? ' ' : text[i - 1];
    const alInicioPalabra = !/[a-z0-9]/.test(prevChar);
    return 100 + (alInicioPalabra ? 60 : 0) + Math.max(0, 40 - i);
  }
  if (tok.length < 4) return 0;
  const max = tok.length >= 7 ? 2 : 1;
  let mejor = max + 1;
  for (const w of item._words) {
    if (Math.abs(w.length - tok.length) > max) continue;
    const d = editarDistCorte(tok, w, max);
    if (d < mejor) {
      mejor = d;
      if (mejor === 0 || (max === 1 && mejor === 1)) break;
    }
  }
  return mejor <= max ? 60 - mejor * 15 : 0;
}

/**
 * Búsqueda difusa POR TOKENS: la query se parte por espacios y TODOS los
 * tokens tienen que pegar (AND), en cualquier orden y sin importar
 * mayúsculas/acentos. Devuelve el puntaje total o -1 si descarta.
 */
function fuzzyScore(query, item) {
  const q = normalizar(query).trim();
  if (!q) return 0;
  let total = 0;
  for (const tok of q.split(/\s+/)) {
    const s = scoreToken(tok, item);
    if (s <= 0) return -1;
    total += s;
  }
  return total;
}

async function cargarInventario() {
  if (invCargando) return;
  invCargando = true;
  setInvStatus('Cargando inventario…', true);
  $('invMeta').textContent = 'Cargando…';
  try {
    let items = [];
    let rev = '';
    let hash = '';
    let sinMeta = false;

    const res = await fetch(`${API_BASE}/api/v1/sync/v2`);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const j = await res.json();

    if (j.format === 'predeterminado' && Array.isArray(j.products)) {
      rev = j.revision; hash = j.hash;
      items = j.products.map((p) => ({
        codigo_unico: String(p.codigo_unico ?? ''),
        descripcion: String(p.descripcion ?? ''),
        precios: Array.isArray(p.precios) ? p.precios : [],
        costo_neto: Number(p.costo_neto) || 0,
        costo_iva: Number(p.costo_iva) || 0,
        categoria: p.categoria || '',
        fabrica: p.fabrica || '',
        codigo: p.codigo || '',
        oferta: p.oferta === true,
      }));
    } else {
      // Modo "descripcion-costos": no trae código ni metadatos → contrato v1
      // (congelado, siempre trae codigo_unico + nombre + precios).
      sinMeta = true;
      const r1 = await fetch(`${API_BASE}/api/v1/sync`);
      if (!r1.ok) throw new Error(`HTTP ${r1.status}`);
      const j1 = await r1.json();
      rev = j1.revision; hash = j1.hash;
      items = (j1.products || []).map((p) => ({
        codigo_unico: String(p.codigo_unico ?? ''),
        descripcion: String(p.nombre ?? ''),
        precios: Array.isArray(p.precios) ? p.precios : [],
        costo_neto: Number(p.costo_neto) || 0,
        costo_iva: 0, // el contrato v1 no expone costo+IVA
        categoria: '',
        fabrica: '',
        codigo: '',
        oferta: false,
      }));
    }

    inventario = items.map(indexar);
    invSinMeta = sinMeta;

    const meta = `rev ${rev} · hash ${String(hash || '').slice(0, 8)}…`;
    $('invMeta').textContent = `${inventario.length} artículos · ${meta}`;
    aplicarBusqueda();
    setInvStatus(sinMeta
      ? '⚠️ El modo de sync actual no expone categoría/fábrica: se pueden ver y borrar, pero no editar.'
      : '', !sinMeta);
  } catch (e) {
    inventario = inventario || [];
    $('invMeta').textContent = 'Sin conexión con el backend';
    aplicarBusqueda();
    setInvStatus('❌ No se pudo cargar el inventario: ' + e.message, false);
  } finally {
    invCargando = false;
  }
}

function aplicarBusqueda() {
  if (!inventario) return;
  const q = $('invSearch').value;
  const normQ = normalizar(q).trim();

  if (!normQ) {
    invFiltrados = inventario.slice().sort((a, b) => a.descripcion.localeCompare(b.descripcion, 'es'));
  } else {
    const conScore = [];
    for (const p of inventario) {
      const s = fuzzyScore(normQ, p);
      if (s < 0) continue;
      conScore.push({ p, s: p._cod === normQ ? s + 1000 : s });
    }
    conScore.sort((a, b) => b.s - a.s || a.p.descripcion.localeCompare(b.p.descripcion, 'es'));
    invFiltrados = conScore.map((x) => x.p);
  }

  invVisible = INV_PAGINA;
  const total = inventario.length;
  $('invCount').textContent = normQ
    ? `${invFiltrados.length} de ${total} artículos`
    : `${total} artículos`;
  pintarInventario();
}

function filaInventario(p) {
  const row = document.createElement('div');
  row.className = 'inv-row';
  row.dataset.codigo = p.codigo_unico;

  const main = document.createElement('div');
  main.className = 'inv-main';
  const code = document.createElement('span');
  code.className = 'inv-code';
  code.textContent = p.codigo_unico;
  const desc = document.createElement('span');
  desc.className = 'inv-desc';
  desc.textContent = p.descripcion;
  const tags = document.createElement('span');
  tags.className = 'inv-tags';
  tags.textContent = [p.categoria, p.fabrica, p.codigo, p.oferta ? '🔥 OFERTA' : ''].filter(Boolean).join(' · ');
  main.append(code, desc, tags);

  const precios = document.createElement('div');
  precios.className = 'inv-precios';
  const fmtCosto = (n) => Number(n).toLocaleString('es-AR', { maximumFractionDigits: 2 });
  const chipCosto = (label, valor) => {
    const chip = document.createElement('span');
    chip.className = 'precio-chip costo';
    const b = document.createElement('b');
    b.textContent = label;
    chip.append(b, document.createTextNode(valor));
    return chip;
  };
  if (Number(p.costo_neto) > 0) precios.appendChild(chipCosto('Costo neto', fmtCosto(p.costo_neto)));
  if (Number(p.costo_iva) > 0) precios.appendChild(chipCosto('Costo+IVA', fmtCosto(p.costo_iva)));
  (p.precios || []).forEach((n, i) => {
    const chip = document.createElement('span');
    chip.className = 'precio-chip';
    const b = document.createElement('b');
    b.textContent = `L${i + 1}`;
    chip.append(b, document.createTextNode(Number(n).toLocaleString('es-AR')));
    precios.appendChild(chip);
  });

  const acc = document.createElement('div');
  acc.className = 'inv-acc';
  const bEdit = document.createElement('button');
  bEdit.type = 'button';
  bEdit.className = 'btn ghost';
  bEdit.dataset.accion = 'editar';
  bEdit.textContent = '✏️';
  bEdit.title = 'Editar';
  const bDel = document.createElement('button');
  bDel.type = 'button';
  bDel.className = 'btn ghost';
  bDel.dataset.accion = 'borrar';
  bDel.textContent = '🗑️';
  bDel.title = 'Eliminar';
  if (invSinMeta) {
    bEdit.disabled = true;
    bEdit.title = 'No disponible con el modo de sync actual (Configuración → modo)';
  }
  acc.append(bEdit, bDel);

  row.append(main, precios, acc);
  return row;
}

function pintarInventario() {
  const cont = $('invList');
  cont.innerHTML = '';
  $('invMore').hidden = true;

  if (!inventario) return;

  if (!inventario.length) {
    cont.innerHTML = '<div class="empty"><div class="empty-ico">📦</div>' +
      '<p class="empty-title">El catálogo está vacío</p>' +
      '<p class="muted">Generá y subí uno desde <strong>📂 Catálogo</strong>.</p></div>';
    return;
  }

  if (!invFiltrados.length) {
    cont.innerHTML = '<div class="empty"><div class="empty-ico">🔍</div>' +
      '<p class="empty-title">Sin resultados</p>' +
      '<p class="muted">Ningún artículo coincide con esa búsqueda.</p></div>';
    return;
  }

  const frag = document.createDocumentFragment();
  for (let i = 0; i < invVisible && i < invFiltrados.length; i++) {
    frag.appendChild(filaInventario(invFiltrados[i]));
  }
  cont.appendChild(frag);

  if (invFiltrados.length > invVisible) {
    $('invMore').hidden = false;
    $('invMoreBtn').textContent = `Ver más resultados (${invFiltrados.length - invVisible} restantes)`;
  }
}

let invTimer = null;
$('invSearch').addEventListener('input', () => {
  clearTimeout(invTimer);
  invTimer = setTimeout(aplicarBusqueda, 140);
});
$('invSearch').addEventListener('keydown', (e) => {
  if (e.key === 'Escape') { $('invSearch').value = ''; aplicarBusqueda(); }
});
$('invMoreBtn').addEventListener('click', () => {
  invVisible += INV_PAGINA;
  pintarInventario();
});
$('invRecargar').addEventListener('click', () => { inventario = null; cargarInventario(); });

$('invList').addEventListener('click', (e) => {
  const btn = e.target.closest('button[data-accion]');
  if (!btn) return;
  const row = btn.closest('.inv-row');
  const p = inventario && inventario.find((x) => x.codigo_unico === row.dataset.codigo);
  if (!p) return;
  if (btn.dataset.accion === 'editar') abrirEdicion(p);
  else borrarProducto(p);
});

// ── Edición de un artículo ──────────────────────────────────────────────────
function setEdStatus(msg, ok) {
  const el = $('edStatus');
  el.textContent = msg;
  el.className = 'status ' + (ok ? 'ok' : 'err');
}

const edPrecios = () => [1, 2, 3, 4].map((i) => Number($(`edL${i}`).value));

// ── Costos en el modal: neto ⇄ IVA, editables, precisión completa ───────────
// El INPUT muestra 2 decimales; la verdad vive en las variables (así un artículo
// sin edición no se redondea al guardar). `edCostoManual` protege el costo
// editado a mano de que la L4 lo pise al tipear.
let edCostoManual = false;
let edCostoNeto = 0;
let edCostoIva = 0;

const costoFmt = (n) => (Number.isFinite(n) && n > 0 ? (Math.round(n * 100) / 100).toFixed(2) : '');

function pintarCostosEdicion() {
  $('edCostoNeto').value = costoFmt(edCostoNeto);
  $('edCostoIva').value = costoFmt(edCostoIva);
}

// El vínculo entre ambos campos es el IVA de la config: neto × (1 + IVA) = IVA.
$('edCostoNeto').addEventListener('input', () => {
  edCostoManual = true;
  edCostoNeto = Number($('edCostoNeto').value);
  edCostoIva = edCostoNeto > 0 ? edCostoNeto * (1 + CFG.iva / 100) : 0;
  $('edCostoIva').value = costoFmt(edCostoIva);
});

$('edCostoIva').addEventListener('input', () => {
  edCostoManual = true;
  edCostoIva = Number($('edCostoIva').value);
  edCostoNeto = edCostoIva > 0 ? edCostoIva / (1 + CFG.iva / 100) : 0;
  $('edCostoNeto').value = costoFmt(edCostoNeto);
});

// Tocar la L4 sigue derivando los costos… salvo que el usuario los haya
// editado a mano: en ese caso el costo manda y la L4 no lo pisa.
$('edL4').addEventListener('input', () => {
  if (edCostoManual) return;
  const l4 = Number($('edL4').value);
  if (!Number.isFinite(l4) || l4 <= 0) return;
  const c = calcularPrecios(l4);
  edCostoNeto = c.costo_neto;
  edCostoIva = c.costo_iva;
  pintarCostosEdicion();
});

/**
 * Lista n calculada DESDE el costo neto vigente (espejo de `calcularPrecios`,
 * pero con el costo como entrada). En oferta mandan las 4: todas quedan en el
 * valor de la L4 (factor inversa), igual que en el resto del panel.
 */
function listaDesdeCosto(n) {
  const cfg = cfgFormula(CFG);
  const enOferta = esOferta($('edDesc').value) || $('edOferta').checked;
  const factor = cfg.listas[enOferta ? 3 : n - 1];
  return Math.round(edCostoNeto * (1 + cfg.iva / 100) * factor);
}

// R por lista: recalcular ESA lista desde el costo (title "recalcular" +
// leyenda visible debajo del botón).
[1, 2, 3, 4].forEach((n) => {
  $(`edRL${n}`).addEventListener('click', () => {
    if (!(edCostoNeto > 0)) { setEdStatus('Cargá un costo neto > 0 (o una L4 válida) para recalcular', false); return; }
    $(`edL${n}`).value = listaDesdeCosto(n);
    setEdStatus(`↺ L${n} recalculada desde el costo — guardá para aplicar`, true);
  });
});

function abrirEdicion(p) {
  $('edCodigo').value = p.codigo_unico;
  $('edDesc').value = p.descripcion;
  (p.precios || []).slice(0, 4).forEach((n, i) => { $(`edL${i + 1}`).value = n; });
  for (let i = (p.precios || []).length; i < 4; i++) $(`edL${i + 1}`).value = '';
  $('edCat').value = p.categoria || '';
  $('edFab').value = p.fabrica || SIN_FABRICA;
  $('edCodFab').value = p.codigo || '';
  $('edOferta').checked = !!p.oferta;

  // Costos: mandan los GUARDADOS; si el artículo no trae, se derivan de la L4.
  edCostoManual = false;
  const guardado = Number(p.costo_neto);
  const l4 = Number($('edL4').value);
  if (Number.isFinite(guardado) && guardado > 0) {
    edCostoNeto = guardado;
    edCostoIva = Number(p.costo_iva) > 0 ? Number(p.costo_iva) : guardado * (1 + CFG.iva / 100);
  } else if (Number.isFinite(l4) && l4 > 0) {
    const c = calcularPrecios(l4);
    edCostoNeto = c.costo_neto;
    edCostoIva = c.costo_iva;
  } else {
    edCostoNeto = 0;
    edCostoIva = 0;
  }
  pintarCostosEdicion();

  setEdStatus('', true);
  $('editModal').hidden = false;
  $('edDesc').focus();
  $('edDesc').select();
}

function cerrarEdicion() {
  $('editModal').hidden = true;
}

$('edCerrar').addEventListener('click', cerrarEdicion);
$('edCancelar').addEventListener('click', cerrarEdicion);
$('editModal').addEventListener('click', (e) => { if (e.target === $('editModal')) cerrarEdicion(); });
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && !$('editModal').hidden) cerrarEdicion();
});

/** Recalcula L1..L3 desde el costo vigente (las 4 iguales si es oferta). */
$('edRecalc').addEventListener('click', () => {
  if (!(edCostoNeto > 0)) { setEdStatus('Cargá un costo neto > 0 (o una L4 válida) para recalcular', false); return; }
  if (esOferta($('edDesc').value) || $('edOferta').checked) {
    const l4 = listaDesdeCosto(4);
    for (let i = 1; i <= 4; i++) $(`edL${i}`).value = l4;
  } else {
    for (let i = 1; i <= 3; i++) $(`edL${i}`).value = listaDesdeCosto(i);
  }
  setEdStatus('↺ Listas recalculadas desde el costo — guardá para aplicar', true);
});

// Tildar Oferta = la regla "la oferta manda los precios": las 4 listas quedan
// iguales a la L4 (si no hay L4 cargada no se hace nada).
$('edOferta').addEventListener('change', () => {
  if (!$('edOferta').checked) return;
  const l4 = Number($('edL4').value);
  if (!Number.isFinite(l4) || l4 <= 0) return;
  for (let i = 1; i <= 4; i++) $(`edL${i}`).value = Math.round(l4);
  setEdStatus('🔥 En oferta: las 4 listas quedaron iguales a la L4 — guardá para aplicar', true);
});

$('edGuardar').addEventListener('click', async () => {
  const codigo = $('edCodigo').value;
  const desc = $('edDesc').value.trim();
  const l = edPrecios();
  if (!desc) { setEdStatus('La descripción no puede quedar vacía', false); return; }
  if (l.some((n) => !Number.isFinite(n) || n <= 0)) { setEdStatus('Los 4 precios tienen que ser números > 0', false); return; }

  const producto = {
    codigo_unico: codigo,
    descripcion: desc,
    precios: l.map((n) => Math.round(n)),
    categoria: $('edCat').value.trim(),
    fabrica: $('edFab').value.trim(),
    codigo: $('edCodFab').value.trim(),
    oferta: $('edOferta').checked,
  };
  // El costo es dato EDITABLE: se manda tal cual (precisión completa). Solo se
  // deriva de la L4 si quedó vacío/inválido (nunca dejar costos en 0).
  let neto = edCostoNeto;
  let iva = edCostoIva;
  if (!(neto > 0) || !(iva > 0)) {
    const c = calcularPrecios(l[3]);
    neto = c.costo_neto;
    iva = c.costo_iva;
  }
  producto.costo_neto = neto;
  producto.costo_iva = iva;

  try {
    setEdStatus('Guardando…', true);
    const r = await apiAdmin('/api/v1/admin/products', { body: { products: [producto] } });
    const i = inventario.findIndex((x) => x.codigo_unico === codigo);
    if (i >= 0) inventario[i] = indexar(producto);
    aplicarBusqueda();
    cerrarEdicion();
    setInvStatus(`✅ "${desc}" actualizado — revisión ${r.revision} · las apps se resincronizan solas`, true);
  } catch (e) {
    setEdStatus('❌ ' + e.message, false);
  }
});

// ── Eliminación de un artículo ──────────────────────────────────────────────
async function borrarProducto(p) {
  const seguro = confirm(
    `¿Eliminar "${p.descripcion}" (${p.codigo_unico})?\n\n` +
    'Se baja del catálogo activo y sube la revisión: todas las apps se resincronizan solas.',
  );
  if (!seguro) return;
  try {
    setInvStatus(`Eliminando ${p.codigo_unico}…`, true);
    const r = await apiAdmin(`/api/v1/admin/products/${encodeURIComponent(p.codigo_unico)}`, { method: 'DELETE' });
    inventario = inventario.filter((x) => x.codigo_unico !== p.codigo_unico);
    aplicarBusqueda();
    setInvStatus(`🗑️ "${p.descripcion}" eliminado — revisión ${r.revision}`, true);
  } catch (e) {
    setInvStatus('❌ ' + e.message, false);
  }
}

// ════════════════════════════════════════════════════════════════════════════
// Sección Administración — parser de artículos (fábrica · código · oferta)
// ════════════════════════════════════════════════════════════════════════════
// articleParser.js vive en pages/ como ESM puro y index.html lo importa con un
// <script type="module"> que lo deja en window.LolaParser. Ese módulo se evalúa
// DESPUÉS que app.js (clásico): por eso solo se pide adentro de los handlers,
// nunca al cargar el archivo.

let adminItems = null;      // parseArticles() sobre las descripciones del inventario
let adminPendientes = null; // [{ i, producto, conDuda }] listos para aplicar
const ADMIN_MOSTRAR = 50;   // filas visibles en la tabla de vista previa

function setAdminStatus(msg, ok) {
  const el = $('adminStatus');
  el.textContent = msg;
  el.className = 'status ' + (ok ? 'ok' : 'err');
}

function ocultarResultadosAdmin() {
  for (const id of ['adminStats', 'adminFabs', 'adminPreview', 'adminLegend']) $(id).hidden = true;
}

function pintarAdminPreview(payload, items) {
  const cont = $('adminPreview');
  cont.innerHTML = '';

  const thead = document.createElement('thead');
  const trh = document.createElement('tr');
  ['Fábrica', 'Código', 'Descripción', 'Oferta'].forEach((t) => {
    const th = document.createElement('th');
    th.textContent = t;
    trh.appendChild(th);
  });
  thead.appendChild(trh);

  const tbody = document.createElement('tbody');
  payload.slice(0, ADMIN_MOSTRAR).forEach((x, i) => {
    const tdFab = document.createElement('td');
    if (items[i].revisar) {
      const w = document.createElement('span');
      w.className = 'admin-warn';
      w.textContent = '⚠️ ';
      w.title = 'Confianza baja: revisalo a mano en 📦 Inventario';
      tdFab.appendChild(w);
    }
    tdFab.appendChild(document.createTextNode(x.fabrica));
    const tdCod = document.createElement('td');
    tdCod.textContent = x.codigo || '—';
    const tdDesc = document.createElement('td');
    tdDesc.textContent = x.descripcion;
    const tdOferta = document.createElement('td');
    tdOferta.textContent = x.oferta ? 'Sí' : '—';
    const tr = document.createElement('tr');
    tr.append(tdFab, tdCod, tdDesc, tdOferta);
    tbody.appendChild(tr);
  });

  const tabla = document.createElement('table');
  tabla.append(thead, tbody);
  cont.appendChild(tabla);
  cont.hidden = false;

  const legend = $('adminLegend');
  const mostradas = Math.min(ADMIN_MOSTRAR, payload.length);
  legend.textContent = mostradas < payload.length
    ? `⚠️ = confianza baja (revisalo en 📦 Inventario). Se muestran ${mostradas} de ${payload.length} filas.`
    : '⚠️ = confianza baja (revisalo en 📦 Inventario).';
  legend.hidden = false;
}

async function analizarInventario() {
  const P = window.LolaParser;
  if (!P) { setAdminStatus('❌ articleParser.js no cargó — recargá la página', false); return; }
  try {
    $('adminAnalizar').disabled = true;
    $('adminAplicar').disabled = true;
    ocultarResultadosAdmin();
    adminPendientes = null;
    setAdminStatus('Analizando las descripciones…', true);

    if (inventario === null) await cargarInventario();
    if (inventario === null && invCargando) {
      setAdminStatus('El inventario se está cargando en otra pestaña — probá de nuevo en un segundo', false);
      return;
    }
    if (!inventario || !inventario.length) {
      adminItems = null;
      setAdminStatus('El inventario está vacío — subí un catálogo desde 📂 Catálogo', false);
      return;
    }

    adminItems = P.parseArticles(inventario.map((p) => p.descripcion));
    const payload = adminItems.map(P.toPayload);
    const total = payload.length;
    const conFabrica = payload.filter((x) => x.fabrica !== P.SIN_FABRICA).length;
    const ofertas = payload.filter((x) => x.oferta).length;
    const aRevisar = adminItems.filter((x) => x.revisar).length;

    // Chips por fábrica (SIN FABRICA al final, ya lo ordena summarizeFabricas)
    const fabs = $('adminFabs');
    fabs.innerHTML = '';
    for (const f of P.summarizeFabricas(adminItems)) {
      const chip = document.createElement('span');
      chip.className = 'pill' + (f.nombre === P.SIN_FABRICA ? ' sin' : '');
      chip.textContent = `${f.nombre} · ${f.total}`;
      if (f.ofertas) {
        const b = document.createElement('b');
        b.textContent = ` (${f.ofertas} ofertas)`;
        chip.appendChild(b);
      }
      fabs.appendChild(chip);
    }
    fabs.hidden = false;

    const stats = $('adminStats');
    stats.innerHTML = '';
    for (const t of [
      `${total} filas`,
      `${conFabrica} con fábrica detectada`,
      `${total - conFabrica} sin marca → se guarda SIN FABRICA`,
      `${ofertas} ofertas`,
      `${aRevisar} a revisar`,
    ]) {
      const s = document.createElement('span');
      s.className = 'pill';
      s.textContent = t;
      stats.appendChild(s);
    }
    stats.hidden = false;

    pintarAdminPreview(payload, adminItems);

    // Pendientes a aplicar: solo COMPLETAR lo que falta (fábrica y/o código),
    // nunca pisar lo ya cargado. La oferta se recalcula con la regla combinada
    // (sufijos + marcas del parser) y, si es oferta, mandan los precios.
    adminPendientes = [];
    let sinPrecios = 0;
    inventario.forEach((p, i) => {
      const it = adminItems[i];
      const x = payload[i];
      const fabCargada = (p.fabrica || '').trim();
      const codCargado = (p.codigo || '').trim();
      const faltaFabrica = !fabCargada; // SIN FABRICA es una fábrica válida: se completa igual
      const faltaCodigo = !codCargado && !!x.codigo;
      if (!faltaFabrica && !faltaCodigo) return;
      const precios = (p.precios || []).map((n) => Math.round(Number(n)));
      if (!Number.isFinite(precios[3]) || precios[3] <= 0) { sinPrecios++; return; }
      const oferta = !!(p.oferta || x.oferta);
      const finales = oferta ? Array(4).fill(precios[3]) : precios; // la oferta manda los precios
      // Los costos GUARDADOS se preservan (el usuario puede haberlos editado
      // a mano): solo se derivan de la L4 si el artículo no trae costo.
      const netoGuardado = Number(p.costo_neto);
      const conCosto = Number.isFinite(netoGuardado) && netoGuardado > 0;
      const c = conCosto ? null : calcularPrecios(finales[3]);
      adminPendientes.push({
        i,
        conDuda: it.revisar,
        producto: {
          codigo_unico: p.codigo_unico,
          descripcion: p.descripcion,
          precios: finales,
          categoria: p.categoria || '',
          fabrica: fabCargada || it.fabrica,
          codigo: codCargado || x.codigo,
          oferta,
          costo_neto: conCosto ? netoGuardado : c.costo_neto,
          costo_iva: conCosto
            ? (Number(p.costo_iva) > 0 ? Number(p.costo_iva) : netoGuardado * (1 + CFG.iva / 100))
            : c.costo_iva,
        },
      });
    });

    const n = adminPendientes.length;
    const dudas = adminPendientes.filter((x) => x.conDuda).length;
    let msg = `✅ ${total} filas · ${n} artículos para completar`;
    if (dudas) msg += ` · ${dudas} con confianza baja`;
    if (sinPrecios) msg += ` · ${sinPrecios} sin precios válidos`;
    if (invSinMeta) {
      $('adminAplicar').disabled = true;
      setAdminStatus(`${msg} · ⚠️ modo de sync sin metadatos: aplicar está bloqueado (pisaría categorías)`, false);
    } else {
      $('adminAplicar').disabled = n === 0;
      setAdminStatus(msg, true);
    }
  } catch (e) {
    setAdminStatus('❌ ' + e.message, false);
  } finally {
    $('adminAnalizar').disabled = false;
  }
}

$('adminAnalizar').addEventListener('click', analizarInventario);

$('adminAplicar').addEventListener('click', async () => {
  if (!adminPendientes || !adminPendientes.length) { setAdminStatus('No hay artículos pendientes de completar', false); return; }
  if (invSinMeta) {
    setAdminStatus('⚠️ El modo de sync actual no expone categoría/fábrica: aplicar pisaría las categorías con un string vacío. Cambiá a "predeterminado" en ⚙️ Configuración.', false);
    return;
  }
  const n = adminPendientes.length;
  const dudas = adminPendientes.filter((x) => x.conDuda).length;
  const seguro = confirm(
    `¿Completar ${n} artículos del catálogo?\n\n` +
    '· Se completan fábricas y códigos que faltan (los ya cargados no se tocan).\n' +
    '· Sin marca detectada → se guarda SIN FABRICA: ningún artículo queda sin fábrica.\n' +
    '· Las ofertas detectadas igualan las 4 listas a la L4 (la oferta manda los precios).\n' +
    (dudas ? `· ${dudas} tienen confianza baja (⚠️ en la tabla).\n` : '') +
    '· Los costos ya guardados se conservan; si faltan, se derivan de la L4 con la configuración actual.\n\n' +
    'Sube la revisión: todas las apps se resincronizan solas.',
  );
  if (!seguro) return;
  try {
    setAdminStatus(`Aplicando ${n} artículos…`, true);
    const r = await apiAdmin('/api/v1/admin/products', { body: { products: adminPendientes.map((x) => x.producto) } });
    for (const x of adminPendientes) inventario[x.i] = indexar(x.producto);
    aplicarBusqueda();
    adminPendientes = null;
    $('adminAplicar').disabled = true;
    setAdminStatus(`✅ ${n} artículos completados (fábrica · código · oferta) — revisión ${r.revision} · las apps se resincronizan solas`, true);
  } catch (e) {
    setAdminStatus('❌ ' + e.message, false);
  }
});

// ── Inicial ─────────────────────────────────────────────────────────────────
pintarCfg();
renderAuth();
