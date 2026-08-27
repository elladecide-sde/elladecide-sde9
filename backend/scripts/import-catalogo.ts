#!/usr/bin/env tsx
// ============================================================================
// import-catalogo.ts — Import Excel (ADM) → catálogo LOLA (D1 vía Worker)
//
// El parseo + precios + codigo_unico viven en src/importCatalogo.ts, el MISMO
// núcleo que usa la web (pages/ → POST /api/v1/admin/import). Así CLI y web
// producen exactamente los mismos códigos.
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
// ============================================================================

import { readFile } from 'node:fs/promises';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import XLSX from 'xlsx';
import { importCatalogo, type OpcionesImport } from '../src/importCatalogo';

// ── Args ─────────────────────────────────────────────────────────────────────

function flag(name: string, def: string): string {
  const argv = process.argv.slice(2);
  const i = argv.findIndex((x) => x === `--${name}`);
  if (i >= 0 && argv[i + 1] && !argv[i + 1].startsWith('--')) return argv[i + 1];
  const a = argv.find((x) => x.startsWith(`--${name}=`));
  return a ? a.slice(name.length + 3) : def;
}
const positional = process.argv.slice(2).filter((x) => !x.startsWith('--'));

const ARCHIVO = positional[0];
if (!ARCHIVO) {
  console.error('Uso: npm run import -- <archivo.xlsx> [flags]');
  process.exit(1);
}

const OPTS: OpcionesImport = {
  colNombre: Number(flag('col-nombre', '0')),
  colPrecio: Number(flag('col-precio', '1')),
  colCodigo: Number(flag('col-codigo', '-1')),
  skip: Number(flag('skip', '1')),
  iva: Number(flag('iva', '21')),
  inversaIva: Number(flag('inversa-iva', '21')),
  inversaLista: Number(flag('inversa-lista', '50')),
  listas: flag('listas', '2.32,2.12,1.65,1.50').split(',').map((s) => Number(s.trim())),
  ofertaKeywords: flag('oferta-keywords', 'oferta,liq').split(',').map((s) => s.trim().toLowerCase()),
};
const API = flag('api', '');
const USER = flag('user', '');
const PASSWORD = flag('password', '');
const OUT_DIR = flag('out', 'scripts/salida');
const MARCAS = flag('marcas', '');

function leerFilas(path: string): unknown[][] {
  const wb = XLSX.readFile(path);
  const hoja = wb.Sheets[wb.SheetNames[0]];
  return XLSX.utils.sheet_to_json(hoja, { header: 1, defval: '' }) as unknown[][];
}

// ── Principal ───────────────────────────────────────────────────────────────

async function main() {
  console.log(`📄 Leyendo ${ARCHIVO}…`);
  const filas = leerFilas(ARCHIVO);

  if (MARCAS) {
    OPTS.marcas = JSON.parse(await readFile(MARCAS, 'utf8'));
  }

  const r = importCatalogo(filas, OPTS);

  console.log(`   Filas útiles: ${r.productos.length} | rechazadas: ${r.rechazadas.length}`);
  console.log(`\n✅ ${r.productos.length} productos · ${r.productos.length - r.colisiones} codigo_unico únicos · colisiones residuales: ${r.colisiones}`);
  if (r.descartados.length) console.log(`   ⚠️  ${r.descartados.length} duplicados normalizados descartados (${r.descartados[0]} …)`);
  if (r.rechazadas.length) {
    const top = r.rechazadas.slice(0, 5).map((x) => `fila ${x.fila}: ${x.motivo}`).join(' · ');
    console.log(`   ⚠️  ${r.rechazadas.length} filas rechazadas (${top})`);
  }

  // Salidas locales.
  mkdirSync(OUT_DIR, { recursive: true });
  const catalogoPath = join(OUT_DIR, 'catalogo.json');
  const mapaPath = join(OUT_DIR, 'mapa-catalogo.json');
  writeFileSync(catalogoPath, JSON.stringify(r.productos, null, 2), 'utf8');
  writeFileSync(
    mapaPath,
    JSON.stringify({ version: 1, generado: new Date().toISOString(), productos: r.mapa }, null, 2),
    'utf8',
  );
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
  // Reemplazo completo: el worker hace backup rotativo + wipe + insert en un
  // solo batch atómico (el límite de 100 statements lo maneja el worker).
  console.log(`   ⬆️  Subiendo ${r.productos.length} productos (reemplazo completo)…`);
  const res = await fetch(`${API}/api/v1/admin/products`, {
    method: 'POST',
    headers,
    body: JSON.stringify({ products: r.productos, replace: true }),
  });
  if (!res.ok) {
    const texto = await res.text();
    console.error(`\n❌ Upload falló (${res.status}): ${texto}`);
    process.exit(1);
  }
  const up = (await res.json()) as { revision: number; hash: string; backup: string };
  console.log(`\n🎉 Catálogo subido a D1 — revisión ${up.revision} · backup ${up.backup}`);
}

main().catch((e) => {
  console.error('\n❌', e instanceof Error ? e.message : e);
  process.exit(1);
});