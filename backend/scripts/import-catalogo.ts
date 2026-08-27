#!/usr/bin/env tsx
// ============================================================================
// import-catalogo.ts — Import Excel (ADM) → catálogo LOLA (D1 vía Worker)
//
// Lógica espejo del generador Python (sde7) y de la extensión:
//   - Campo 1 = nombre (todo mezclado: marca + modelo + descripción).
//   - Campo 2 = precio de Lista 4 → inversa → costo → 4 listas.
//   - Oferta/liquidación (nombre termina en "." o "oferta"/"liq"):
//     las 4 listas = precio del Excel.
//   - codigo_unico generado con src/codigoUnico.ts (determinístico, sin
//     dependencia del orden). FUENTE DE VERDAD del catálogo.
//
// Uso:
//   # Solo calcular (dry-run): genera catalogo.json + mapa-catalogo.json
//   npm run import -- archivo.xlsx --out ./salida
//
//   # Subir al Worker:
//   npm run import -- archivo.xlsx --api http://localhost:8787 \
//     --user admin --password devpass
//
// Flags:
//   --col-nombre=N      (default 0)
//   --col-precio=N      (default 1)
//   --col-codigo=N      (default -1 = auto; columna de código opcional)
//   --skip=N            (default 1 = fila de encabezados; 0 si no hay)
//   --marcas=archivo.json   lista de marcas (nombres de carpeta) para
//                           códigos más limpios
//   --iva=21 --inversa-iva=21 --inversa-lista=50
//   --listas=2.32,2.12,1.65,1.50
//   --oferta-keywords=oferta,liq
//   --api=URL --user=U --password=P
//   --out=DIR           (default: scripts/salida)
//   --chunk=N           (default 100: productos por POST)
// ============================================================================

import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import XLSX from 'xlsx';
import { asignarCodigos, nombreNormalizado, partir } from '../src/codigoUnico.ts';

// ── Args ─────────────────────────────────────────────────────────────────────

function flag(name: string, def: string): string {
  const argv = process.argv.slice(2);
  const i = argv.findIndex((x) => x === `--${name}`);
  if (i >= 0 && argv[i + 1] && !argv[i + 1].startsWith('--')) return argv[i + 1];
  const a = argv.find((x) => x.startsWith(`--${name}=`));
  return a ? a.slice(name.length + 3) : def;
}
const positional = process.argv.slice(2).filter((x) => !x.startsWith('--'));

const COL_NOMBRE = Number(flag('col-nombre', '0'));
const COL_PRECIO = Number(flag('col-precio', '1'));
const COL_CODIGO = Number(flag('col-codigo', '-1'));
const SKIP = Number(flag('skip', '1'));
const IVA = Number(flag('iva', '21'));
const INV_IVA = Number(flag('inversa-iva', '21'));
const INV_LISTA = Number(flag('inversa-lista', '50'));
const LISTAS = flag('listas', '2.32,2.12,1.65,1.50')
  .split(',')
  .map((s) => Number(s.trim()));
const OFERTA_KW = flag('oferta-keywords', 'oferta,liq').split(',').map((s) => s.trim().toLowerCase());
const API = flag('api', '');
const USER = flag('user', '');
const PASSWORD = flag('password', '');
const OUT_DIR = flag('out', 'scripts/salida');
const CHUNK = Number(flag('chunk', '100'));
const MARCAS = flag('marcas', '');

const ARCHIVO = positional[0];
if (!ARCHIVO) {
  console.error('Uso: npm run import -- <archivo.xlsx> [flags]');
  process.exit(1);
}

const IVA_F = 1 + IVA / 100;
const LISTA4_FACTOR = (1 + INV_IVA / 100) * (1 + INV_LISTA / 100); // 1.21 * 1.50

// ── Lógica de precios (espejo del generador Python) ─────────────────────────

function esOferta(nombre: string): boolean {
  const n = nombre.trim();
  if (n.endsWith('.')) return true;
  const low = n.toLowerCase();
  return OFERTA_KW.some((kw) => low.endsWith(kw));
}

function calcularPrecios(precioLista4: number): number[] {
  const costo = precioLista4 / LISTA4_FACTOR;
  return LISTAS.map((f) => Math.round(costo * IVA_F * f));
}

// ── Lectura del Excel ───────────────────────────────────────────────────────

function leerFilas(path: string): unknown[][] {
  const wb = XLSX.readFile(path);
  const hoja = wb.Sheets[wb.SheetNames[0]];
  return XLSX.utils.sheet_to_json(hoja, { header: 1, defval: '' }) as unknown[][];
}

function parseExcel(filas: unknown[][]) {
  const filasOk: Array<{ nombre: string; precio: number; codigoExcel: string }> = [];
  const rechazadas: Array<{ fila: number; motivo: string }> = [];

  for (let i = SKIP; i < filas.length; i++) {
    const row = filas[i];
    if (!Array.isArray(row)) continue;

    const nombre = String(row[COL_NOMBRE] ?? '').trim();
    if (!nombre || nombre.toLowerCase() === 'none' || nombre.length <= 3) {
      rechazadas.push({ fila: i + 1, motivo: 'nombre vacío o corto' });
      continue;
    }
    if (!Number.isNaN(Number(nombre))) {
      rechazadas.push({ fila: i + 1, motivo: 'número suelto' });
      continue;
    }

    const precio = Number(row[COL_PRECIO]);
    if (!Number.isFinite(precio) || precio <= 0) {
      rechazadas.push({ fila: i + 1, motivo: 'precio inválido' });
      continue;
    }

    let codigoExcel = '';
    if (COL_CODIGO >= 0) {
      const raw = String(row[COL_CODIGO] ?? '').trim();
      if (raw && raw.toLowerCase() !== 'none') {
        codigoExcel = Number.isNaN(Number(raw)) ? raw : String(Math.trunc(Number(raw)));
      }
    }

    filasOk.push({ nombre, precio, codigoExcel });
  }

  return { filasOk, rechazadas };
}

// ── Principal ───────────────────────────────────────────────────────────────

async function main() {
  console.log(`📄 Leyendo ${ARCHIVO}…`);
  const filas = leerFilas(ARCHIVO);
  const { filasOk, rechazadas } = parseExcel(filas);
  console.log(`   Filas útiles: ${filasOk.length} | rechazadas: ${rechazadas.length}`);

  const marcas = MARCAS ? JSON.parse(await import('node:fs/promises').then((m) => m.readFile(MARCAS, 'utf8'))) : undefined;

  // Dedup por nombre normalizado (fuera de asignarCodigos, para el reporte).
  const porNombre = new Map<string, (typeof filasOk)[number]>();
  const duplicados: string[] = [];
  for (const f of filasOk) {
    const k = nombreNormalizado(f.nombre);
    if (!porNombre.has(k)) porNombre.set(k, f);
    else duplicados.push(f.nombre);
  }
  const unicas = [...porNombre.values()];

  const { codigos } = asignarCodigos(unicas.map((f) => ({ nombre: f.nombre })), { marcas });

  const productos: Array<{ codigo_unico: string; nombre: string; precios: number[] }> = [];
  for (const f of unicas) {
    productos.push({
      codigo_unico: codigos.get(nombreNormalizado(f.nombre))!,
      nombre: f.nombre,
      precios: esOferta(f.nombre)
        ? [Math.round(f.precio), Math.round(f.precio), Math.round(f.precio), Math.round(f.precio)]
        : calcularPrecios(f.precio),
    });
  }

  const unicosCod = new Map<string, number>();
  for (const p of productos) unicosCod.set(p.codigo_unico, (unicosCod.get(p.codigo_unico) ?? 0) + 1);
  let colisiones = 0;
  for (const n of unicosCod.values()) if (n > 1) colisiones += n - 1;

  console.log(`\n✅ ${productos.length} productos · ${unicosCod.size} codigo_unico únicos · colisiones residuales: ${colisiones}`);
  if (duplicados.length) console.log(`   ⚠️  ${duplicados.length} duplicados normalizados descartados (${duplicados[0]} …)`);
  if (rechazadas.length) {
    const top = rechazadas.slice(0, 5).map((r) => `fila ${r.fila}: ${r.motivo}`).join(' · ');
    console.log(`   ⚠️  ${rechazadas.length} filas rechazadas (${top})`);
  }

  // Salidas locales.
  mkdirSync(OUT_DIR, { recursive: true });
  const catalogoPath = join(OUT_DIR, 'catalogo.json');
  const mapaPath = join(OUT_DIR, 'mapa-catalogo.json');
  writeFileSync(catalogoPath, JSON.stringify(productos, null, 2), 'utf8');
  const mapa = {
    version: 1,
    generado: new Date().toISOString(),
    productos: unicas.map((f) => {
      const partes = partir(f.nombre, { marcas });
      return {
        codigo_unico: codigos.get(nombreNormalizado(f.nombre)),
        nombre: f.nombre,
        marca: partes.marca.join(' '),
        modelo: partes.codigo || null,
      };
    }),
  };
  writeFileSync(mapaPath, JSON.stringify(mapa, null, 2), 'utf8');
  console.log(`   📦 ${catalogoPath}\n   🗺️  ${mapaPath} (para sde11)`);

  if (!API) {
    console.log('\n   (dry-run: sin --api no se sube. Pasá --api --user --password para subir.)');
    return;
  }

  // Login + upload.
  const login = await fetch(`${API}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: USER, password: PASSWORD }),
  });
  if (!login.ok) {
    const texto = await login.text();
    console.error(`\n❌ Login falló (${login.status}): ${texto}`);
    process.exit(1);
  }
  const { token } = (await login.json()) as { token: string };

  const headers = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${token}`,
  };
  let subidos = 0;
  for (let i = 0; i < productos.length; i += CHUNK) {
    const chunk = productos.slice(i, i + CHUNK);
    const res = await fetch(`${API}/api/v1/admin/products`, {
      method: 'POST',
      headers,
      body: JSON.stringify({ products: chunk }),
    });
    if (!res.ok) {
      const texto = await res.text();
      console.error(`\n❌ Upload chunk ${i / CHUNK} falló (${res.status}): ${texto}`);
      process.exit(1);
    }
    subidos += chunk.length;
    console.log(`   ⬆️  ${subidos}/${productos.length}`);
  }
  console.log('\n🎉 Catálogo subido a D1.');
}

main().catch((e) => {
  console.error('\n❌', e instanceof Error ? e.message : e);
  process.exit(1);
});