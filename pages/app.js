// app.js — LOLA catálogo: parseo de Excel en el navegador + subida al backend.
// La lógica de parseo y precios es un port 1:1 de la extensión (content.js
// `generateFromSync`) y del generador Python (DOBLECLIC PARA GENERAR CATALOGO.py).

// TODO: hacer configurable (por ahora apunta al Worker desplegado).
const API_BASE = 'https://lola-backend.elladecide.workers.dev';

// CONFIG — idéntico a CONFIG_DEFAULTS de la extensión.
const CONFIG = {
  iva: 21,
  inversaLista: 50,
  inversaIva: 21,
  listas: [2.32, 2.12, 1.65, 1.50],
  ofertaKeywords: ['oferta', 'liq'],
};

// ── Lógica de precios (port de esOferta + calcularPrecios) ────────────────
function esOferta(nombre) {
  const n = String(nombre).trim();
  if (n.endsWith('.')) return true;
  const low = n.toLowerCase();
  return CONFIG.ofertaKeywords.some((kw) => low.endsWith(kw));
}

// Excel trae el precio de Lista 4 → se hace ingeniería inversa al costo y se
// recalculan las 4 listas. Oferta: las 4 listas = precio del Excel.
function calcularPrecios(precioLista4) {
  const IVA = 1 + CONFIG.iva / 100;
  const IVA_INVERSA = 1 + CONFIG.inversaIva / 100;
  const LISTA4_FACTOR = IVA_INVERSA * (1 + CONFIG.inversaLista / 100); // 1.21 * 1.50
  const costo = precioLista4 / LISTA4_FACTOR;
  return CONFIG.listas.map((f) => Math.round(costo * IVA * f));
}

// ── codigo_unico: abreviación del nombre + código de artículo (modelo) ────
// TODO (regla pendiente de confirmar): "BELEN CONJUNTO ARMADO 1420" → "BELCONARM1420".
// Se abrevia cada palabra ANTES del modelo (3 letras) y se le pega el modelo.
// Sin modelo (ej. "LIQ." sin número): se abrevian las primeras palabras.
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

// ── Parseo del Excel (port de generateFromSync) ───────────────────────────
function parseExcel(rows, colNombre, colPrecio, colCodigo, skipRows) {
  const productos = [];
  const vistos = new Set(); // dedup por nombre en mayúsculas
  const usados = new Set();  // garantiza unicidad de codigo_unico

  for (let i = skipRows; i < rows.length; i++) {
    const row = rows[i];
    if (!Array.isArray(row)) continue;

    const nombre = String(row[colNombre] ?? '').trim();
    if (!nombre || nombre.length <= 3) continue;
    if (!isNaN(Number(nombre))) continue;

    const precio = parseFloat(row[colPrecio]);
    if (!precio || precio <= 0) continue;

    // Código: si hay columna de código, usarla; si no, generar codigo_unico.
    let codigoUnico = '';
    if (colCodigo >= 0) {
      const raw = String(row[colCodigo] ?? '').trim();
      if (raw && raw.toLowerCase() !== 'none') {
        codigoUnico = isNaN(Number(raw)) ? raw : String(parseInt(raw, 10));
      }
    }
    if (!codigoUnico) codigoUnico = generarCodigoUnico(nombre);

    // Garantizar unicidad (colisión → sufijo).
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

// ── Subida al backend (POST /api/v1/admin/products) ───────────────────────
async function subirCatalogo(productos, secret) {
  const res = await fetch(`${API_BASE}/api/v1/admin/products`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Admin-Secret': secret,
    },
    body: JSON.stringify({ products: productos }),
  });
  if (!res.ok) {
    let msg = `HTTP ${res.status}`;
    try { msg = (await res.json()).error || msg; } catch (e) { /* ignore */ }
    throw new Error(msg);
  }
  return res.json(); // { revision, hash }
}

// ── UI ────────────────────────────────────────────────────────────────────
const $ = (id) => document.getElementById(id);
let productosGenerados = null;

function setStatus(msg, ok) {
  const el = $('status');
  el.textContent = msg;
  el.className = 'status ' + (ok ? 'ok' : 'err');
}

$('secret').value = localStorage.getItem('lola_secret') || '';
$('secret').addEventListener('change', (e) => localStorage.setItem('lola_secret', e.target.value));

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
  const secret = $('secret').value.trim();
  if (!secret) { setStatus('Ingresá el secret de administrador', false); return; }

  try {
    setStatus('Subiendo…', true);
    const r = await subirCatalogo(productosGenerados, secret);
    setStatus(`☁️ Catálogo subido — revisión ${r.revision} · hash ${r.hash.slice(0, 8)}…`, true);
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
