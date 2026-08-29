package com.elladecide.lola.data

import java.security.MessageDigest

/**
 * Port EXACTO de backend/src/hash.ts: hash canónico SHA-256 del catálogo.
 *
 * La paridad está garantizada por [CatalogHashTest] con golden vectors generados
 * por el backend. Reglas de la forma canónica:
 *   1. Ordenar por [Product.codigoUnico] con String.compareTo (unidades UTF-16),
 *      idéntico a la comparación por code units del backend (no localeCompare).
 *   2. Serializar cada producto como
 *      `{"codigo_unico":..,"nombre":..,"precios":[...]}` sin espacios, precios
 *      como enteros y keys en ese orden exacto.
 *   3. Escapar strings como JSON.stringify: \" \\ \b \f \n \r \t y \u00xx.
 *   4. SHA-256 de los bytes UTF-8, en hexadecimal.
 */
object CatalogHash {

    fun compute(products: List<Product>): String {
        val canonical = canonicalJson(products.sortedBy { it.codigoUnico })
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun canonicalJson(sorted: List<Product>): String {
        val sb = StringBuilder()
        sb.append('[')
        for ((i, p) in sorted.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append("{\"codigo_unico\":")
            quote(sb, p.codigoUnico)
            sb.append(",\"nombre\":")
            quote(sb, p.nombre)
            sb.append(",\"precios\":[")
            sb.append(p.preciosList().joinToString(","))
            sb.append("]}")
        }
        sb.append(']')
        return sb.toString()
    }

    /** Escapa un string exactamente como JSON.stringify (unidades UTF-16). */
    private fun quote(sb: StringBuilder, value: String) {
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
    }
}