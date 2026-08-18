// app.js — LOLA catálogo: login JWT + parseo de Excel en el navegador + subida.
// La lógica de parseo y precios es un port 1:1 de la extensión (content.js
// `generateFromSync`) y del generador Python.

const API_BASE = 'https://lola-backend.elladecide.workers.dev';

// CONFIG — idéntico a CONFIG_DEFAULTS de la extensión.
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

// ── codigo_unico: abreviación del nombre + código de artículo (modelo) ──────
// TODO (regla pendiente de confirmar): "BELEN CONJUNTO ARMADO 1420" → "BELCONARM1420".
function generarCodigoUnico(nombre) {
  const tokens = String(nombre)
    .toUpperCase()
    .replace(/[^A-Z0-9\s]/g, ' ')
    .split(/\s+/)
    .filter(Boolean);

  const idxModelo = tokens.findIndex((t) => /\d{2,}/.test(t));
  let palabras;
  let modelo;
  if (idxModelo === -1) {
    palabras = tokens.slice(0, 4);
    modelo = '';
  } else {
    palabras = tokens.slice(0, idxModelo);
    modelo = (tokens[idxModelo].match(/\d+/) || [''])[0];
  }
  const abreviacion = palabras.map((w) => w.slice(0, 3)).join('');
  return abreviacion + modelo;
}

// ── Parseo del Excel (port de generateFromSync) ─────────────────────────────
function parseExcel(rows, colNombre, colPrecio, colCodigo, skipRows) {
  const productos = [];
  const vistos = new Set();
  const usados = new Set();

  for (let i = skipRows; i < rows.length; i++) {
    const row = rows[i];
    if (!Array.isArray(row)) continue;

    const nombre = String(row[colNombre] ?? '').trim();
    if (!nombre || nombre.length <= 3) continue;
    if (!isNaN(Number(nombre))) continue;

    const precio = parseFloat(row[colPrecio]);
    if (!precio || precio <= 0) continue;

    let codigoUnico = '';
    if (colCodigo >= 0) {
      const raw = String(row[colCodigo] ?? '').trim();
      if (raw && raw.toLowerCase() !== 'none') {
        codigoUnico = isNaN(Number(raw)) ? raw : String(parseInt(raw, 10));
      }
    }
    if (!codigoUnico) codigoUnico = generarCodigoUnico(nombre);

    let unico = codigoUnico;
    let n = 1;
    while (usados.has(unico)) unico = `${codigoUnico}-${n++}`;
    usados.add(unico);

    const key = nombre.toUpperCase();
    if (vistos.has(key)) continue;
    vistos.add(key);

    const precios = esOferta(nombre)
      ? [Math.round(precio), Math.round(precio), Math.round(precio), Math.round(precio)]
      : calcularPrecios(precio);

    productos.push({ codigo_unico: unico, nombre, precios });
  }
  return productos;
}

// ── Subida al backend (POST /api/v1/admin/products con Bearer) ──────────────
async function subirCatalogo(productos) {
  const token = getToken();
  if (!token) throw new Error('Sesión expirada. Volvé a ingresar.');
  const res = await fetch(`${API_BASE}/api/v1/admin/products`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({ products: productos }),
  });
  if (!res.ok) {
    let msg = `HTTP ${res.status}`;
    try { msg = (await res.json()).error || msg; } catch (e) { /* ignore */ }
    if (res.status === 401) { clearToken(); renderAuth(); }
    throw new Error(msg);
  }
  return res.json(); // { revision, hash }
}

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
  if (!u || !p) { setStatus('Ingresá usuario y contraseña', false); return; }
  try {
    setStatus('Ingresando…', true);
    const token = await login(u, p);
    setToken(token);
    $('password').value = '';
    renderAuth();
    setStatus('', true);
  } catch (e) {
    setStatus('❌ ' + e.message, false);
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

$('excel').addEventListener('change', (e) => {
  const f = e.target.files[0];
  $('fileInfo').textContent = f ? `${f.name} (${(f.size / 1024).toFixed(0)} KB)` : 'Ningún archivo seleccionado';
  productosGenerados = null;
  $('subir').disabled = true;
  $('preview').hidden = true;
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

    productosGenerados = parseExcel(rows, colNombre, colPrecio, colCodigo, skipRows);
    if (!productosGenerados.length) { setStatus('No se encontraron productos válidos. Revisá las columnas.', false); return; }

    renderPreview(productosGenerados);
    $('subir').disabled = false;
    setStatus(`✅ ${productosGenerados.length} productos generados — listos para subir`, true);
  } catch (err) {
    setStatus('❌ ' + err.message, false);
  }
});

$('subir').addEventListener('click', async () => {
  if (!productosGenerados || !productosGenerados.length) return;
  try {
    setStatus('Subiendo…', true);
    const r = await subirCatalogo(productosGenerados);
    setStatus(`☁️ Catálogo subido — revisión ${r.revision} · hash ${(r.hash || '').slice(0, 8)}…`, true);
  } catch (err) {
    setStatus('❌ ' + err.message, false);
  }
});

function renderPreview(productos) {
  const body = $('previewBody');
  body.innerHTML = '';
  const muestra = productos.slice(0, 10);
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
  if (productos.length > 10) {
    const más = document.createElement('p');
    más.className = 'muted';
    más.textContent = `… y ${productos.length - 10} más.`;
    body.appendChild(más);
  }
  $('preview').hidden = false;
}

// Inicial
renderAuth();
