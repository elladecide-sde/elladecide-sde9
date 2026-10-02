// app.js — LOLA catálogo: login JWT + parseo de Excel en el navegador + subida.
//
// La PÁGINA es la generadora (SheetJS + LolaCodigo + precios), el worker es el
// mensajero que reemplaza el catálogo (replace). La regla de codigo_unico vive
// en codigoUnico.js, port EXACTO del backend (verificado con el catálogo real).
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

  // Modelo interno COMPLETO: costo neto + costo con IVA + las 4 listas +
  // categoría + fábrica. Nada se tira.
  const productos = unicas.map((f) => {
    const c = calcularPrecios(f.precio);
    return {
      codigo_unico: codigos.get(LolaCodigo.nombreNormalizado(f.nombre)),
      descripcion: f.nombre,
      costo_neto: c.costo_neto,
      costo_iva: c.costo_iva,
      precios: esOferta(f.nombre) ? Array(4).fill(Math.round(f.precio)) : c.precios,
      categoria: f.categoria,
      fabrica: f.fabrica,
    };
  });

  const colisiones = productos.length - new Set(productos.map((p) => p.codigo_unico)).size;

  return { productos, descartados, rechazadas, colisiones };
}

// ── Subida al backend (reemplazo completo, atómico en el worker) ────────────
async function subirCatalogo(productos) {
  const token = getToken();
  if (!token) throw new Error('Sesión expirada. Volvé a ingresar.');
  const res = await fetch(`${API_BASE}/api/v1/admin/products`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({ products: productos, replace: true }),
  });
  if (!res.ok) {
    let msg = `HTTP ${res.status}`;
    try { msg = (await res.json()).error || msg; } catch (e) { /* ignore */ }
    if (res.status === 401) { clearToken(); renderAuth(); }
    throw new Error(msg);
  }
  return res.json(); // { revision, hash, backup }
}

// ── Rollback (restaurar un backup del Worker) ───────────────────────────────
async function rollbackCatalogo(backup) {
  const token = getToken();
  if (!token) throw new Error('Sesión expirada. Volvé a ingresar.');
  const res = await fetch(`${API_BASE}/api/v1/admin/rollback`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({ backup }),
  });
  if (!res.ok) {
    let msg = `HTTP ${res.status}`;
    try { msg = (await res.json()).error || msg; } catch (e) { /* ignore */ }
    if (res.status === 401) { clearToken(); renderAuth(); }
    throw new Error(msg);
  }
  return res.json(); // { revision, hash, restaurado }
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
  const token = getToken();
  if (!token) throw new Error('Sesión expirada. Volvé a ingresar.');
  const res = await fetch(`${API_BASE}/api/v1/auth/change-password`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({ current_password: currentPassword, new_password: newPassword }),
  });
  if (!res.ok) {
    let msg = `HTTP ${res.status}`;
    try { msg = (await res.json()).error || msg; } catch (e) { /* ignore */ }
    throw new Error(msg);
  }
  return res.json();
}

// ── UI ──────────────────────────────────────────────────────────────────────
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

function renderAuth() {
  const logged = !!getToken();
  $('loginBlock').hidden = logged;
  $('sessionBlock').hidden = !logged;
  $('uploadArea').hidden = !logged;
  if (!logged) productosGenerados = null;
  if (logged) cargarModoSync();
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
  setStatus('', true);
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
  tabla.innerHTML =
    '<thead><tr><th>Código</th><th>Descripción</th><th>Costo neto</th><th>Costo+IVA</th><th>L1</th><th>L2</th><th>L3</th><th>L4</th></tr></thead>';
  const tbody = document.createElement('tbody');
  muestra.forEach((p) => {
    const tr = document.createElement('tr');
    tr.innerHTML = `<td>${p.codigo_unico}</td><td>${p.descripcion}</td>` +
      `<td>${fmt(p.costo_neto)}</td><td>${fmt(p.costo_iva)}</td>` +
      p.precios.map((x) => `<td>${x.toLocaleString('es-AR')}</td>`).join('');
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

// ── Config de precios (UI) ──────────────────────────────────────────────────
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

// ── Modo de sync (config guardada en D1) ────────────────────────────────────
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
  const token = getToken();
  if (!token) { setSyncStatus('Sesión expirada. Volvé a ingresar.', false); return; }
  try {
    setSyncStatus('Aplicando…', true);
    const res = await fetch(`${API_BASE}/api/v1/admin/sync-config`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({ modo }),
    });
    if (!res.ok) {
      let msg = `HTTP ${res.status}`;
      try { msg = (await res.json()).error || msg; } catch (e) { /* ignore */ }
      if (res.status === 401) { clearToken(); renderAuth(); }
      throw new Error(msg);
    }
    const j = await res.json();
    setSyncStatus(`✅ Modo "${j.modo}" — /api/v1/sync/v2 responde { ${j.modo === 'descripcion-costos' ? 'descripcion, costo' : 'codigo_unico, descripcion, precios[], categoria, fabrica'} }`, true);
  } catch (e) {
    setSyncStatus('❌ ' + e.message, false);
  }
});

// Inicial
pintarCfg();
renderAuth();
