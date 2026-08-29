// app.js — LOLA catálogo: login JWT + parseo de Excel en el navegador + subida.
//
// La PÁGINA es la generadora (SheetJS + LolaCodigo + precios), el worker es el
// mensajero que reemplaza el catálogo (replace). La regla de codigo_unico vive
// en codigoUnico.js, port EXACTO del backend (verificado con el catálogo real).
//
// Precios (espejo del generador Python / importCatalogo):
//   costo = precioLista4 / (1.21 * 1.50) → 4 listas. Oferta (termina en ".",
//   "oferta" o "liq") → las 4 iguales al precio del Excel.

const API_BASE = 'https://lola-backend.elladecide.workers.dev';

// CONFIG — idéntico a CONFIG_DEFAULTS de la extensión / configuracion.txt.
const CONFIG = {
  iva: 21,
  inversaLista: 50,
  inversaIva: 21,
  listas: [2.32, 2.12, 1.65, 1.50],
  ofertaKeywords: ['oferta', 'liq'],
};

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

// ── Lógica de precios (port de esOferta + calcularPrecios) ──────────────────
function esOferta(nombre) {
  const n = String(nombre).trim();
  if (n.endsWith('.')) return true;
  const low = n.toLowerCase();
  return CONFIG.ofertaKeywords.some((kw) => low.endsWith(kw));
}

function calcularPrecios(precioLista4) {
  const IVA = 1 + CONFIG.iva / 100;
  const IVA_INVERSA = 1 + CONFIG.inversaIva / 100;
  const LISTA4_FACTOR = IVA_INVERSA * (1 + CONFIG.inversaLista / 100); // 1.21 * 1.50
  const costo = precioLista4 / LISTA4_FACTOR;
  return CONFIG.listas.map((f) => Math.round(costo * IVA * f));
}

// ── Parseo + generación (espejo de importCatalogo, con LolaCodigo) ──────────
function procesarCatalogo(rows, colNombre, colPrecio, colCodigo, skipRows) {
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
    filasOk.push({ nombre, precio, codigoExcel });
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

  const productos = unicas.map((f) => ({
    codigo_unico: codigos.get(LolaCodigo.nombreNormalizado(f.nombre)),
    nombre: f.nombre,
    precios: esOferta(f.nombre)
      ? Array(4).fill(Math.round(f.precio))
      : calcularPrecios(f.precio),
  }));

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
const $ = (id) => document.getElementById(id);
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
    const skipRows = parseInt($('skipRows').value, 10) || 0;

    const r = procesarCatalogo(rows, colNombre, colPrecio, colCodigo, skipRows);
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
  const muestra = r.productos.slice(0, 10);
  const tabla = document.createElement('table');
  tabla.innerHTML = '<thead><tr><th>Código</th><th>Nombre</th><th>L1</th><th>L2</th><th>L3</th><th>L4</th></tr></thead>';
  const tbody = document.createElement('tbody');
  muestra.forEach((p) => {
    const tr = document.createElement('tr');
    tr.innerHTML = `<td>${p.codigo_unico}</td><td>${p.nombre}</td>` +
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

// Inicial
renderAuth();