// ============================================================================
// hash.ts — Hash canónico del catálogo (SHA-256)
//
// La forma canónica DEBE ser estable y determinística para que:
//   - el Worker y cualquier script calculen el MISMO hash,
//   - y (a futuro) el cliente Android pueda verificarlo si hace falta.
//
// Reglas de la forma canónica:
//   1. Ordenar productos por `codigo_unico` (localeCompare).
//   2. Cada producto se serializa como { codigo_unico, nombre, precios } con
//      precios redondeados a entero (Math.round).
//   3. Se hace JSON.stringify de la lista ordenada SIN espacios extra.
//   4. SHA-256 sobre los bytes UTF-8, en hexadecimal.
// ============================================================================

export interface ProductRef {
  codigo_unico: string;
  nombre: string;
  precios: number[];
}

export async function computeCatalogHash(products: ProductRef[]): Promise<string> {
  const sorted = [...products].sort((a, b) => a.codigo_unico.localeCompare(b.codigo_unico));
  const canonical = JSON.stringify(
    sorted.map((p) => ({
      codigo_unico: p.codigo_unico,
      nombre: p.nombre,
      precios: p.precios.map((x) => Math.round(x)),
    })),
  );

  const data = new TextEncoder().encode(canonical);
  const digest = await crypto.subtle.digest('SHA-256', data);

  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}
