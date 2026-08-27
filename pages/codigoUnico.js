// codigoUnico.js — port 1:1 de backend/src/codigoUnico.ts (FUENTE DE VERDAD de
// la página). Expone window.LolaCodigo. No tocar: debe coincidir EXACTO con el
// TypeScript del backend para que la web y el CLI generen los mismos códigos.
(function () {
  'use strict';

  const LIMPIAR = /[^A-Z0-9.\-]/g;

  function tokens(nombre) {
    return String(nombre).toUpperCase().replace(LIMPIAR, ' ').split(/\s+/).filter(Boolean);
  }

  function nombreNormalizado(nombre) {
    return tokens(nombre).join(' ');
  }

  function prefijoMarca(tokensNombre, marcas) {
    const candidatas = marcas
      .map(tokens)
      .filter((t) => t.length)
      .sort((a, b) => b.length - a.length);
    for (const cand of candidatas) {
      if (cand.length > tokensNombre.length) continue;
      let ok = true;
      for (let i = 0; i < cand.length; i++) {
        if (cand[i] !== tokensNombre[i]) { ok = false; break; }
      }
      if (ok) return cand;
    }
    return [];
  }

  function partir(nombre, opts) {
    opts = opts || {};
    const t = tokens(nombre);
    let marca = [];
    if (opts.marcas && opts.marcas.length) marca = prefijoMarca(t, opts.marcas);
    const marcaPalabras = Math.max(1, opts.marcaPalabras ?? 1);
    if (!marca.length) marca = t.slice(0, marcaPalabras);
    const restoBase = t.slice(marca.length);
    const idxModelo = restoBase.findIndex((tok) => /\d{2,}/.test(tok));
    if (idxModelo >= 0) {
      return { marca, codigo: restoBase[idxModelo], resto: restoBase.slice(idxModelo + 1) };
    }
    return { marca, codigo: '', resto: restoBase };
  }

  function abrev(palabras, len) {
    len = len ?? 3;
    return palabras.map((w) => w.slice(0, len)).join('');
  }

  function codigoEnNivel(p, resto, len) {
    return abrev(p.marca, len) + p.codigo + abrev(p.resto.slice(0, resto), len);
  }

  function hashHex(texto) {
    let h = 0x811c9dc5;
    for (let i = 0; i < texto.length; i++) {
      h ^= texto.charCodeAt(i);
      h = Math.imul(h, 0x01000193) >>> 0;
    }
    return h.toString(16).toUpperCase().padStart(8, '0');
  }

  function asignarCodigos(items, opts) {
    opts = opts || {};
    const maxResto = opts.maxResto ?? 6;
    const maxLen = opts.maxLen ?? 8;

    const porNombre = new Map();
    const descartados = [];
    for (const it of items) {
      const k = nombreNormalizado(it.nombre);
      if (!porNombre.has(k)) porNombre.set(k, it);
      else descartados.push(it.nombre);
    }

    const pendientes = new Map();
    for (const [k, it] of porNombre) pendientes.set(k, partir(it.nombre, opts));

    const codigos = new Map();
    for (let resto = 0; resto <= maxResto; resto++) {
      for (let len = 3; len <= maxLen; len++) {
        if (pendientes.size === 0) break;
        const buckets = new Map();
        for (const [k, p] of pendientes) {
          const c = codigoEnNivel(p, resto, len);
          if (!buckets.has(c)) buckets.set(c, []);
          buckets.get(c).push(k);
        }
        for (const [c, ks] of buckets) {
          if (ks.length === 1) {
            codigos.set(ks[0], c);
            pendientes.delete(ks[0]);
          }
        }
      }
      if (pendientes.size === 0) break;
    }

    for (const [k, p] of pendientes) {
      const base = codigoEnNivel(p, maxResto, maxLen);
      codigos.set(k, base + '-' + hashHex(k).slice(-4));
    }

    return { codigos, descartados };
  }

  window.LolaCodigo = { tokens, nombreNormalizado, partir, abrev, asignarCodigos };
})();