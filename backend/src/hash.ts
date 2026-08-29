// ============================================================================
// hash.ts — Hash canónico del catálogo (SHA-256)
//
// La forma canónica DEBE ser estable, determinística y PORTABLE para que:
//   - el Worker y cualquier script calculen el MISMO hash,
//   - y el cliente Android pueda verificarlo localmente (integridad) con un
//     port EXACTO (CatalogHash.kt, golden tests que prueban la paridad).
//
// Reglas de la forma canónica:
//   1. Ordenar productos por `codigo_unico` con comparación por unidades UTF-16
//      (equivale a comparar los code points, case-sensitive). NO se usa
//      localeCompare: depende del locale/ICU del runtime y no es portable
//      (Kotlin String.compareTo usa las mismas reglas que a < b en JS).
//   2. Cada producto se serializa como { codigo_unico, nombre, precios } con
//      precios redondeados a entero (Math.round).
//   3. JSON.stringify de la lista ordenada SIN espacios extra. Los strings se
//      escapan como JSON.stringify: \" \\ \b \f \n \r \t y \u00XX minúscula.
//   4. SHA-256 sobre los bytes UTF-8, en hexadecimal.
// ============================================================================

export interface ProductRef {
  codigo_unico: string;
  nombre: string;
  precios: number[];
}

/** Comparación por unidades UTF-16 — idéntica a String.compareTo de Kotlin. */
function compareCodeUnits(a: string, b: string): number {
  if (a < b) return -1;
  if (a > b) return 1;
  return 0;
}

/** Serialización canónica: JSON de la lista ordenada, sin espacios. */
export function canonicalCatalogJson(products: ProductRef[]): string {
  const sorted = [...products].sort((a, b) => compareCodeUnits(a.codigo_unico, b.codigo_unico));
  return JSON.stringify(
    sorted.map((p) => ({
      codigo_unico: p.codigo_unico,
      nombre: p.nombre,
      precios: p.precios.map((x) => Math.round(x)),
    })),
  );
}

export async function computeCatalogHash(products: ProductRef[]): Promise<string> {
  const data = new TextEncoder().encode(canonicalCatalogJson(products));
  const digest = await crypto.subtle.digest('SHA-256', data);

  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}
