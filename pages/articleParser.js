/**
 * articleParser.js
 * Formato típico de la lista:  FABRICA [CODIGO] DESCRIPCION [LIQ.|OFERTA]
 *   "KAURY 8146 C. SOFT TRIANG TUL PUNT OFERTA"
 *   "AVRIL KAURY 1001 C. BRALETT TRIANG PUNT REG"
 *   "ONLY- MIJAL- ARETHA CALZA BIKER LIQ."      (varias fábricas, sin código)
 *
 * Estrategia (2 pasadas):
 *  1) Se detectan y quitan las marcas de oferta/liquidación (tolerante a mayús, acentos, abreviaturas).
 *  2) La fábrica = palabras ANTES del primer token "tipo código" (1 a 3 palabras).
 *     Las fábricas así vistas se aprenden en un diccionario, que luego sirve para
 *     las filas sin código o con código lejano (ej. "SHEDYL SHORT HOMBRE LIQ.").
 *
 * Uso:  const articulos = parseArticles(arrayDeTextos);
 */

/* ───────────── utilidades ───────────── */
const strip = (s) => s.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toUpperCase();
const normApos = (s) => s.replace(/[´`’‘]/g, "'");
const fabKey = (s) => strip(s).replace(/[^A-Z0-9]/g, ''); // BENTURA´S == BENTURA`S == BENTURA'S
const cleanTok = (t) => strip(t).replace(/[^A-Z0-9]/g, '');

/* ───────────── configuración (editá acá) ───────────── */
// Fábricas confirmadas. `alias` = otras formas de escribir la misma fábrica (se unifican bajo `nombre`).
// Siempre se reconocen, aunque aparezcan pocas veces en la lista.
export const FABRICAS = [
  { nombre: 'ACROBATA' },
  { nombre: 'ANA KENDY', alias: ['ANAKENDY', 'ANA'] },
  { nombre: 'ALMENDRA' },
  { nombre: 'AGATHA' },
  { nombre: 'AVRIL KAURY', alias: ['AVRIL', 'AVRIL MORENA'] },
  { nombre: 'ARETHA' },
  { nombre: 'BAKHOU' },
  { nombre: 'BELEN' },
  { nombre: 'BENGAL' },
  { nombre: 'BERLIN' },
  { nombre: 'BENTURA', alias: ["BENTURA'S"] },
  { nombre: 'BILBAO' },
  { nombre: 'BIDDEN', alias: ['4BIDDEN'] },
  { nombre: 'BIANCA', alias: ['BIANCA SECRETA', 'B.S', 'BIANCA S.', 'BIANCA MARE'] },
  { nombre: 'BRIGITTE' },
  { nombre: 'CALVIN KLEIN' },
  { nombre: 'CRIS LATINA', alias: ['CRISLATINA'] },
  { nombre: 'COA COA' },
  { nombre: 'DENNY', alias: ['DEENY'] },
  { nombre: 'ELEMENTO', alias: ['ELEMENTO IM'] },
  { nombre: 'FAIRUZ' },
  { nombre: 'FRASHE' },
  { nombre: 'G3' },
  { nombre: 'GRECIA' },
  { nombre: 'FLORCITA' },
  { nombre: 'INTELLIGENTBRA', alias: ['INTELLIGENT', 'INTELIGENTBRA', 'INTELIGENT'] },
  { nombre: 'ISABELLA' },
  { nombre: 'JE TAIME' },
  { nombre: 'KAURY' },
  { nombre: 'KAURY MEN' },
  { nombre: 'KAURY SUMMER' },
  { nombre: 'LARA TEENS', alias: ['LARA'] },
  { nombre: 'LANGERNA' },
  { nombre: 'LENCATEX' },
  { nombre: 'LUCIA KAMPEL' },
  { nombre: 'LODY' },
  { nombre: 'MAIA' },
  { nombre: 'MAXIMA', alias: ['MAXIMA PANTU', 'MAXIMA FREEBRA'] },
  { nombre: 'MAXIMO' },
  { nombre: 'MARCELA KOURY', alias: ['MK'] },
  { nombre: 'MARCELA KOURY AGUA' },
  { nombre: 'MAREY' },
  { nombre: 'MELIFERA' },
  { nombre: 'MIJAL' },
  { nombre: 'MISS RACHEL' },
  { nombre: 'MON CHERIE', alias: ['UOHOMO'] },
  { nombre: 'MORA' },
  { nombre: 'LAS MORAS' },
  { nombre: 'MORITA' },
  { nombre: 'NICOLETTA' },
  { nombre: 'NASTASE' },
  { nombre: 'NASAMI' },
  { nombre: 'ONLY' },
  { nombre: 'RAFF' },
  { nombre: 'ROMANETT' },
  { nombre: 'SAYKA' },
  { nombre: 'SAYER' },
  { nombre: 'SEXY LALI' },
  { nombre: 'SHEDYL' },
  { nombre: 'SIGRY' },
  { nombre: 'VRIDDA' },
  { nombre: 'VINTAGE' },
  { nombre: 'VOLPAIA' },
  { nombre: 'XY' },
  { nombre: 'OJALÁ QUE SI' },
  { nombre: 'VIENTO Y OLAS', alias: ['VYO'] },
];

// Fábricas que, cuando aparecen como ÚLTIMA palabra del texto, mandan sobre lo detectado al inicio.
// Ej.: "PEZONERAS SILICONA SR-681 - MAXIMA", "INTELLIGENTBRA 73 CONEJITO PEZONERA SR-679 MAXIMA".
// Lo detectado al inicio (marca) se conserva en el campo `marca`.
export const FABRICAS_AL_FINAL = ['MAXIMA'];

// Fábricas que se distinguen por una palabra al FINAL del texto (esa palabra se saca de la descripción).
// Ej.: "MARCELA KOURY 3535 BIKINI ARO VEDETINA AGUA" -> MARCELA KOURY AGUA
export const FABRICAS_CON_SUFIJO = [{ base: 'MARCELA KOURY', sufijo: 'AGUA', nombre: 'MARCELA KOURY AGUA' }];

export const FABRICAS_FORZADAS = FABRICAS.map((f) => f.nombre);
export const ALIAS_FABRICAS = Object.fromEntries(
  FABRICAS.flatMap((f) => (f.alias ?? []).map((a) => [fabKey(a), f.nombre]))
);
// Claves de nombres/alias conocidos: "G3" o "4BIDDEN" no deben confundirse con un código.
const NAME_KEYS = new Set([...FABRICAS.map((f) => fabKey(f.nombre)), ...Object.keys(ALIAS_FABRICAS)]);

/* ───────────── 1) Ofertas ───────────── */
// Cada regla se aplica a UNA palabra completa (no a pedazos de otra palabra).
const OFFER_RULES = [
  { tipo: 'oferta', re: /^OFE(R(T(A|AS)?)?)?$/ },            // ofe, ofer, ofert, oferta(s)
  { tipo: 'liquidacion', re: /^LIQ(UID\w*)?$/ },              // liq, liq., liquidacion(es), liquidado
  { tipo: 'promo', re: /^PROMO(S|CION|CIONES)?$/ },           // promo, promocion
  { tipo: 'remate', re: /^(REMATE|SALDOS?|OUTLET)$/ },
  { tipo: 'descuento', re: /^(DESCUENTOS?|DTO)$/ },
];
const detectOffer = (tok) => {
  const c = cleanTok(tok);
  return c ? OFFER_RULES.find((r) => r.re.test(c))?.tipo ?? null : null;
};

/* ───────────── 2) Códigos ───────────── */
// Talles/fracciones que parecen código pero no lo son: T2, T5.6, T:0/1, 3/4, X3
const isTalle = (t) =>
  /^T[:.]?\d{1,2}([.,/:]\d{1,2})*$/.test(t) || /^\d\/\d$/.test(t) || /^X\d{1,2}$/.test(t);

const isCode = (raw, prev = '') => {
  const t = strip(raw).replace(/[(),;:/.\-]+$/, '');
  if (NAME_KEYS.has(fabKey(raw))) return false; // "G3", "4BIDDEN": son fábricas
  if (/^\d$/.test(t)) return false; // dígito suelto: "PACKS X 3", "Nº 2"
  if (/^(X|T|N|NRO|TALLE)$/.test(cleanTok(prev))) return false; // "X 3", "T 2"
  return (
    /\d/.test(t) &&
    /^[A-Z]{0,3}-?\d[A-Z0-9]*([./\-:][A-Z0-9]+)*$/.test(t) && // 1008, P23, M336, 1748P, 508/E, 452-2, SR-678
    !isTalle(t)
  );
};

// Primer código válido dentro de las primeras MAX_CODE_POS palabras (después es parte de la descripción)
const MAX_CODE_POS = 5;
const findCodeIndex = (toks) => {
  const i = toks.findIndex((t, k) => isCode(t, toks[k - 1]));
  return i >= 0 && i < MAX_CODE_POS ? i : -1;
};

// Palabras de rubro que a veces se cuelan antes de la fábrica ("BOXER BAKHOU 2103", "PACK CALVIN KLEIN 2200")
const PRODUCT_WORDS = new Set(
  ('BOXER PACK PACKS MEDIAS MEDIA BOMBACHA BOMBACHAS CORPINO CORPINOS BRALETTE BRALETT CAMISON CAMISONES ' +
    'PIJAMA PIJAMAS SHORT SLIP CALZA CALZAS BODY TOP REMERA COLALESS VEDETINA VEDETINAS TANGA SOQUETE ' +
    'SOQUETES FAJA BRETEL PANTY MALLA MALLAS BIKINI CONJUNTO CULOTTE LESS PANTUFLA PEZONERA PEZONERAS').split(' ')
);
// Palabras que describen público/atributo y NUNCA son una fábrica (medidas sobre la lista real)
const GENERIC_WORDS = new Set(
  ('BABY DOLL NENA NENE DAMA DAMAS HOMBRE SENORA NINO NINOS BEBE MANGA MATERNAL SOLO SOLA SUELTA SUELTAS ' +
    'DEPORTIVO ARTICULOS ARTICULO DE X SIN CAMBIO LARGA CORTA VERANO INVIERNO ADULTO ADULTOS SILICONA TELA').split(' ')
);
const isProduct = (t) => PRODUCT_WORDS.has(cleanTok(t)) || GENERIC_WORDS.has(cleanTok(t));

/* ───────────── tokenización ───────────── */
function tokenize(text) {
  return normApos(text)
    .trim()
    .split(/\s+/)
    .flatMap((t) => {
      // "FRASHE5014/E" -> "FRASHE", "5014/E"
      const m = t.match(/^([A-Za-zÑñ]{4,})(\d[A-Za-z0-9./\-]*)$/);
      return m ? [m[1], m[2]] : [t];
    });
}

// Quita palabras de rubro al inicio/fin de un candidato a fábrica. Devuelve {fab, extra}
function stripProductWords(toks) {
  let a = 0;
  let b = toks.length;
  while (a < b - 1 && isProduct(toks[a])) a++;
  while (b > a + 1 && isProduct(toks[b - 1])) b--;
  return { fab: toks.slice(a, b), lead: toks.slice(0, a), tail: toks.slice(b) };
}

/* ───────────── diccionario de fábricas ───────────── */
export function buildKnownFabricas(textos) {
  const known = new Map(); // key -> { nombre, count }
  for (const raw of textos) {
    if (typeof raw !== 'string' || !raw.trim()) continue;
    const toks = tokenize(raw).filter((t) => !detectOffer(t));
    let ci = findCodeIndex(toks);
    if (ci === 0 && /[A-Za-z]/.test(toks[0])) ci = isCode(toks[1] ?? '', toks[0]) ? 1 : -1; // "G3 3017 BOXER"
    if (ci < 1 || ci > 3) continue;
    const P = toks.slice(0, ci);
    if (P.some((t) => /^[-/&+]$/.test(t) || /-$/.test(t))) continue; // listas de fábricas: se resuelven aparte
    const { fab } = stripProductWords(P);
    const nombre = fab.join(' ');
    const key = fabKey(nombre);
    if (!key || (fab.length === 1 && isProduct(fab[0]))) continue;
    const prev = known.get(key);
    known.set(key, { nombre: prev?.nombre ?? nombre, count: (prev?.count ?? 0) + 1, words: fab.length });
  }
  return known;
}

function longestMatch(toks, known, from = 'start') {
  for (let n = Math.min(3, toks.length); n >= 1; n--) {
    const slice = from === 'start' ? toks.slice(0, n) : toks.slice(toks.length - n);
    const hit = known.get(fabKey(slice.join(' ')));
    if (hit) return { nombre: hit.nombre, n };
  }
  return null;
}

/* ───────────── parser principal ───────────── */
export const SIN_FABRICA = 'SIN FABRICA';

/** Filas que no son artículos de una fábrica (encabezados, separadores, fragmentos): van a SIN FABRICA. */
export function isJunkRow(texto) {
  if (!texto || typeof texto !== 'string') return true;
  const t = texto.trim();
  return t.split(/\s+/).length < 2 || /^\d+\s*articulos?$/i.test(t) || /^ARTICULOS VARIOS/i.test(t);
}

const EMPTY = (rawText) => ({
  fabrica: '', fabricas: [], fabricaKey: '', marca: '', codigos: [], fabricaSugerida: '', soporteFabrica: 0, codigo: '', descripcion: '',
  esOferta: false, esLiquidacion: false, tiposOferta: [], marcas: [], codigosExtra: [], revisar: true,
  confianza: 'baja', sinFabrica: false, valido: false, rawText: rawText ?? '',
});

/**
 * @param {string} rawText
 * @param {Map} [known] diccionario de buildKnownFabricas (opcional pero recomendado)
 */
export function parseArticle(rawText, known = new Map()) {
  if (!rawText || typeof rawText !== 'string' || !rawText.trim()) return EMPTY(rawText);
  const text = rawText.trim().replace(/\s+/g, ' ');
  if (isJunkRow(text)) {
    return {
      ...EMPTY(text), fabrica: SIN_FABRICA, fabricas: [SIN_FABRICA], fabricaKey: fabKey(SIN_FABRICA),
      descripcion: text, confianza: 'media', sinFabrica: true, revisar: false,
    };
  }

  // 1) ofertas
  const marcas = [];
  const toks = [];
  for (const t of tokenize(text)) {
    const tipo = detectOffer(t);
    if (tipo) marcas.push({ tipo, texto: t });
    else toks.push(t);
  }
  const tipos = [...new Set(marcas.map((m) => m.tipo))];

  // 2) posición del código
  let ci = findCodeIndex(toks);
  let brandWithDigit = null;
  if (ci === 0 && /[A-Za-z]/.test(toks[0])) {
    // "G3 3017 BOXER" / "G3 BOXER LISO": un token con letras + número al inicio es la fábrica
    brandWithDigit = toks[0];
    ci = isCode(toks[1] ?? '', toks[0]) ? 1 : -1;
  }
  const hasCode = ci >= 0 && !(ci === 0 && /^\d+$/.test(toks[0])); // "24 articulo" no es un artículo
  const P = brandWithDigit ? [] : hasCode ? toks.slice(0, ci) : toks;
  const cleanCode = (t) => t.replace(/[(),;:/.\-]+$/, '');
  const codigo = hasCode ? cleanCode(toks[ci]) : '';
  let afterCode = hasCode ? toks.slice(ci + 1) : brandWithDigit ? toks.slice(1) : [];
  // códigos alternativos: "404 / 425", "3201 / 4202"
  const codigosExtra = [];
  while (afterCode[0] === '/' && afterCode[1] && isCode(afterCode[1], '/')) {
    codigosExtra.push(cleanCode(afterCode[1]));
    afterCode = afterCode.slice(2);
  }
  // "1011- 1851": el código termina en guion y el siguiente token es otro código
  if (hasCode && /[-/]$/.test(toks[ci]) && afterCode[0] && isCode(afterCode[0], toks[ci])) {
    codigosExtra.push(cleanCode(afterCode[0]));
    afterCode = afterCode.slice(1);
  }
  // "761/762" o "009/010/011" son varios códigos; "508/E" (variante con letra) es uno solo
  const splitCodes = (c) => {
    const parts = c.split('/');
    return parts.length > 1 && parts.every((x) => /^\d{2,}[A-Za-z]?$/.test(x)) ? parts : [c];
  };
  const codigos = [...new Set([codigo, ...codigosExtra].filter(Boolean).flatMap(splitCodes))];

  let fabricas = [];
  let lead = [];
  let resto = [];
  let confianza = 'alta';
  let sugerida = ''; // adivinanza del fallback: NO se usa como fábrica

  // 3a) listas de fábricas separadas por "-" o "/"  ("ONLY- MIJAL- ARETHA ...", "MIJAL / RACHEL ...")
  const joined = P.join(' ').replace(/\([^)]*\)/g, ' '); // el texto entre paréntesis no separa fábricas
  const hasDash = /(?<=[A-Za-zÀ-ÿ.])-\s+|\s+-\s+|(?<=[A-Za-zÀ-ÿ]{2})-(?=[A-Za-zÀ-ÿ]{2})/.test(joined);
  const hasSlash = /\s\/\s/.test(joined);
  if (!brandWithDigit && (hasDash || hasSlash)) {
    // (el primer segmento puede traer rubros: "BRALETTE BENTURA" → BENTURA)
    const segs = joined
      .split(/\s+[-\/&+]\s+|(?<=[A-Za-zÀ-ÿ.])-\s+|(?<=[A-Za-zÀ-ÿ]{2})-(?=[A-Za-zÀ-ÿ]{2})|\s*\/\s+|\s+\/\s*/)
      .map((s) => s.trim())
      .filter(Boolean);
    const accept = (seg, i) => {
      const st = seg.split(' ');
      const k = longestMatch(st, known);
      if (k) return { nombre: k.nombre, rest: st.slice(k.n) };
      if (hasDash || segs.length >= 3) {
        const { fab } = stripProductWords(st.slice(0, Math.min(2, st.length)));
        return i < segs.length - 1 ? { nombre: st.join(' '), rest: [] } : { nombre: fab[0], rest: st.slice(1) };
      }
      return null;
    };
    segs.forEach((seg, i) => {
      let r;
      if (i === 0) {
        const st = seg.split(' ');
        const k = longestMatch(st, known, 'end') ?? longestMatch(st, known, 'start');
        const { fab, lead: l0 } = stripProductWords(st);
        const soloRubro = !k && fab.length === 1 && isProduct(fab[0]); // "CAMISONES / REMERONES": no hay fábrica
        r = k ? { nombre: k.nombre, rest: [] } : soloRubro ? { nombre: '', rest: st } : { nombre: fab.join(' '), rest: [] };
        lead = lead.concat(l0.length && !k ? l0 : []);
      } else r = accept(seg, i);
      if (r) { fabricas.push(r.nombre); if (i === segs.length - 1 || r.rest.length) resto = resto.concat(r.rest); }
      else resto = resto.concat(seg.split(' '));
    });
    confianza = 'media';
  }
  // 3b) código cerca del inicio: lo anterior es la fábrica (1 a 3 palabras)
  else if (brandWithDigit) {
    fabricas = [brandWithDigit];
    // "G3" se acepta solo si aparece en varias filas; un "ABC3" aislado queda para revisión
    if ((known.get(fabKey(brandWithDigit))?.count ?? 0) < 2) confianza = 'baja';
  } else if (hasCode && P.length >= 1 && P.length <= 3) {
    const { fab, lead: l, tail } = stripProductWords(P);
    fabricas = [fab.join(' ')];
    lead = l.concat(tail);
    const hit = known.get(fabKey(fabricas[0]));
    if (fab.length === 1 && isProduct(fab[0])) { sugerida = fab[0]; fabricas = []; confianza = 'baja'; } // "SILICONA": no es una fábrica
    else if (known.size && fab.length >= 3 && (hit?.count ?? 0) < 2) confianza = 'baja';
  }
  // 3c) sin código, o código lejano: usar el diccionario
  else if (P.length) {
    let skip = 0;
    while (skip < P.length - 1 && isProduct(P[skip])) skip++;
    const pre0 = longestMatch(P.slice(skip), known, 'start');
    const pre = pre0 ? { nombre: pre0.nombre, n: pre0.n + skip } : null;
    const suf = longestMatch(P, known, 'end');
    if (pre) { fabricas = [pre.nombre]; resto = P.slice(pre.n); confianza = 'media'; }
    else if (suf) { fabricas = [suf.nombre]; lead = P.slice(0, P.length - suf.n); confianza = 'media'; }
    else {
      // fallback: saltar rubros iniciales y tomar 1 palabra (lo más frecuente en la lista)
      let i = 0;
      while (i < P.length - 1 && (isProduct(P[i]) || isTalle(strip(P[i])))) i++;
      if (isProduct(P[i]) || isTalle(strip(P[i]))) { fabricas = []; resto = P; } // "MANGA", "SOLO"…: no hay fábrica reconocible
      else { sugerida = P[i]; lead = P.slice(0, i); resto = P.slice(i + 1); }
      confianza = 'baja';
    }
  }

  const canon = (f) => ALIAS_FABRICAS[fabKey(f)] ?? known.get(fabKey(f))?.nombre ?? f; // mismo nombre siempre
  const original0 = fabricas[0] ? normApos(fabricas[0]).trim() : '';
  fabricas = fabricas.map((f) => canon(normApos(f).replace(/[-/\s]+$/, '').trim())).filter(Boolean);
  fabricas = [...new Set(fabricas)];
  const descripcionTokens = fabricas.length ? [...lead, ...resto, ...afterCode] : [...lead, ...(sugerida ? [sugerida] : []), ...resto, ...afterCode];
  let descripcion = descripcionTokens.join(' ').replace(/\s+/g, ' ').trim();
  for (const r of FABRICAS_CON_SUFIJO) {
    const last = descripcion.split(' ').pop();
    if (fabricas.length === 1 && fabKey(fabricas[0]) === fabKey(r.base) && cleanTok(last) === fabKey(r.sufijo)) {
      fabricas = [r.nombre];
      descripcion = descripcion.split(' ').slice(0, -1).join(' ');
    }
  }
  // fábrica al final del texto (MAXIMA en pezoneras, etc.)
  let marca = '';
  // sublínea absorbida por un alias ("MAXIMA PANTU" -> MAXIMA): se conserva en `marca` para distinguir códigos repetidos
  if (fabricas.length === 1 && original0.includes(' ') && fabKey(original0) !== fabKey(fabricas[0]) && fabKey(original0).startsWith(fabKey(fabricas[0]))) {
    marca = original0;
  }
  {
    const parts = descripcion.split(' ');
    const lastKey = fabKey(parts[parts.length - 1] ?? '');
    const hit = FABRICAS_AL_FINAL.find((f) => fabKey(f) === lastKey);
    const detectada = fabricas[0] ? fabKey(fabricas[0]) : '';
    if (hit && parts.length > 1 && detectada !== fabKey(hit) && !(ALIAS_FABRICAS[detectada] === hit)) {
      marca = fabricas[0] ?? '';
      fabricas = [hit];
      sugerida = '';
      parts.pop(); // saca la fábrica final
      while (parts.length && /^[-/&+]+$/.test(parts[parts.length - 1])) parts.pop(); // y el guion que la separaba
      descripcion = parts.join(' ');
      confianza = 'media';
    }
  }
  const sinFabrica = fabricas.length === 0;
  if (sinFabrica) fabricas = [SIN_FABRICA];
  const fabrica = fabricas.join(' / ');

  return {
    fabrica,
    fabricas,
    fabricaKey: fabKey(fabricas[0] ?? ''),
    codigo,                       // tal cual figura en la lista (ej. "041/042", "508/E", "A013")
    codigos,                      // lista para buscar: ["041","042"]; un solo elemento si es un código simple
    descripcion,
    esOferta: marcas.length > 0,
    esLiquidacion: tipos.includes('liquidacion'),
    tiposOferta: tipos,           // ej. ['oferta','liquidacion'] — no se pierde ningún estado
    marcas,
    codigosExtra,
    confianza,
    marca, // lo detectado al inicio cuando la fábrica real viene al final
    fabricaSugerida: normApos(sugerida), // solo si no hubo una fábrica confiable
    soporteFabrica: known.get(fabKey(fabricas[0] ?? ''))?.count ?? 0, // filas con código que respaldan la fábrica
    sinFabrica, // true => quedó en el grupo SIN FABRICA
    // revisión manual: baja confianza, salvo SIN FABRICA sin ninguna sugerencia (es un grupo intencional)
    revisar: confianza === 'baja' && (!sinFabrica || sugerida !== ''),
    valido: !sinFabrica && confianza !== 'baja',
    rawText: text,
  };
}

/** Procesa toda la lista (aprende el diccionario y luego parsea). */
export function parseArticles(textos, extraKnown = []) {
  const known = buildKnownFabricas(textos);
  for (const n of [...FABRICAS_FORZADAS, ...extraKnown]) known.set(fabKey(n), { nombre: n, count: 99 });
  for (const [alias, nombre] of Object.entries(ALIAS_FABRICAS)) known.set(alias, { nombre, count: 99 });
  return textos.map((t, i) => ({ id: i, ...parseArticle(t, known) })); // id: usalo como key en React (el código NO es único)
}

/** Resumen por fábrica: { nombre, total, ofertas } ordenado por total desc. */
export function summarizeFabricas(articulos) {
  const m = new Map();
  for (const a of articulos) {
    for (const f of a.fabricas) {
      const k = fabKey(f);
      const e = m.get(k) ?? { nombre: f, total: 0, ofertas: 0 };
      e.total++;
      if (a.esOferta) e.ofertas++;
      m.set(k, e);
    }
  }
  const sin = fabKey(SIN_FABRICA);
  return [...m.entries()]
    .sort(([ka, a], [kb, b]) => (ka === sin) - (kb === sin) || b.total - a.total) // SIN FABRICA siempre al final
    .map(([, v]) => v);
}

/* ───────────── payload final ───────────── */
/**
 * Un artículo parseado -> { fabrica, codigo, descripcion, oferta }
 *  - fabrica:     nombre canónico ("A / B" si el artículo es de varias fábricas, "SIN FABRICA" si no tiene)
 *  - codigo:      tal cual figura en la lista; si hay códigos encadenados ("404 / 425") se unen con " / "
 *  - descripcion: el resto del texto, SIN la fábrica y SIN las palabras de oferta (LIQ., OFERTA…)
 *  - oferta:      true si el texto traía OFERTA / LIQ. / PROMO / etc.
 */
export function toPayload(a) {
  // Si había una marca/sublínea absorbida por la fábrica, no se pierde: pasa a la descripción.
  //   "MAXIMA PANTU MEDIAS 100" -> fabrica MAXIMA, descripcion "PANTU MEDIAS"
  //   "BOOB TAPE ... MAXIMA"    -> fabrica MAXIMA, descripcion "BOOB TAPE ..."
  let prefijo = '';
  if (a.marca) {
    prefijo = fabKey(a.marca).startsWith(fabKey(a.fabrica)) ? a.marca.slice(a.fabrica.length).trim() : a.marca;
  }
  return {
    fabrica: a.fabrica,
    codigo: [a.codigo, ...a.codigosExtra].filter(Boolean).join(' / '),
    descripcion: [prefijo, a.descripcion].filter(Boolean).join(' ').replace(/\s+/g, ' ').trim(),
    oferta: a.esOferta,
  };
}

/** Lista de textos -> lista de payloads (hace el aprendizaje de fábricas internamente). */
export function buildPayload(textos, extraKnown = []) {
  return parseArticles(textos, extraKnown).map(toPayload);
}