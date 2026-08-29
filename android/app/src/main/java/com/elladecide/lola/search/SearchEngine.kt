package com.elladecide.lola.search

import com.elladecide.lola.data.Product
import java.text.Normalizer

/**
 * Motor de búsqueda local — port 1:1 del buscador fuzzy de la extensión
 * (content.js). Busca SOLO en `nombre` (el código no es contenido buscable).
 *
 * Pipeline por token: normaliza tildes (NFD) → matcheo AND → ranking:
 *   5 = palabra exacta, 4 = prefijo, 3 = substring, 2 = typo, 0 = no match.
 * Tolerancia de typo acotada por largo del token (Levenshtein):
 *   ≤3 letras → 0 ediciones, 4-5 → 1, 6+ → 2.
 */
object SearchEngine {

    private fun stripAccents(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("[\\u0300-\\u036f]"), "")

    fun tokenize(s: String): List<String> =
        stripAccents(s.lowercase())
            .replace(Regex("[.\\-_/]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }

    // Distancia de edición acotada. Devuelve 3 si la diferencia de largo ya
    // excede el máximo permitido (corta el DP temprano).
    private fun levenshtein(a: String, b: String): Int {
        val la = a.length
        val lb = b.length
        if (kotlin.math.abs(la - lb) > 2) return 3
        var prev = IntArray(lb + 1) { it }
        for (i in 1..la) {
            val cur = IntArray(lb + 1)
            cur[0] = i
            for (j in 1..lb) {
                cur[j] = minOf(
                    prev[j] + 1,
                    cur[j - 1] + 1,
                    prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
                )
            }
            prev = cur
        }
        return prev[lb]
    }

    private fun typoLimit(t: String): Int = when {
        t.length <= 3 -> 0
        t.length <= 5 -> 1
        else -> 2
    }

    private fun matchToken(t: String, words: List<String>, joined: String): Int {
        if (t in words) return 5
        if (t.length >= 2 && words.any { it.startsWith(t) }) return 4
        if (t.length >= 3 && joined.contains(t)) return 3
        val lim = typoLimit(t)
        if (lim > 0) {
            for (w in words) {
                if (w.length >= 3 && levenshtein(t, w) <= lim) return 2
            }
        }
        return 0
    }

    /** Producto pre-tokenizado: se computa UNA vez al cargar el catálogo. */
    data class IndexedProduct(
        val product: Product,
        val words: List<String>,
        val joined: String,
    )

    /** Pre-tokeniza el catálogo completo (en background, al cargar). */
    fun index(products: List<Product>): List<IndexedProduct> =
        products.map { p ->
            val words = tokenize(p.nombre)
            IndexedProduct(p, words, words.joinToString(" "))
        }

    private fun scoreWords(words: List<String>, joined: String, tokens: List<String>): Int {
        var total = 0
        for (t in tokens) {
            val s = matchToken(t, words, joined)
            if (s == 0) return 0 // todos los tokens tienen que matchear (AND)
            total += s
        }
        return total
    }

    /** Búsqueda sobre un índice pre-tokenizado (rápida, sin NFD por llamada). */
    fun searchIndexed(index: List<IndexedProduct>, query: String): List<Product> {
        if (query.isBlank()) return index.map { it.product }
        val tokens = tokenize(query)
        return index
            .map { it to scoreWords(it.words, it.joined, tokens) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first.product }
    }

    /** API original (tokeniza en cada llamada): se mantiene para compatibilidad. */
    fun search(products: List<Product>, query: String): List<Product> {
        if (query.isBlank()) return products
        return searchIndexed(index(products), query)
    }
}
