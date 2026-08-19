package com.elladecide.lola.editor

import java.text.BreakIterator

/**
 * Utilidades de segmentación por grapheme cluster. Borrar por grapheme (y no
 * por unidad UTF-16) evita corromper emojis, pares sustitutos (surrogate pairs)
 * y caracteres con marcas combinantes (é, ñ, etc.).
 *
 * Usa `java.text.BreakIterator.getCharacterInstance()`, disponible en JVM y en
 * Android, por lo que la lógica es testeable sin android.*.
 */
object Grapheme {

    /** Ventana máxima de caracteres leídos hacia atrás para medir el último cluster. */
    const val LOOKBACK = 128

    /** Longitud en unidades UTF-16 del último grapheme cluster de [beforeCursor]. */
    fun lastClusterLength(beforeCursor: CharSequence): Int {
        if (beforeCursor.isEmpty()) return 0
        val it = BreakIterator.getCharacterInstance()
        it.setText(beforeCursor.toString())
        val end = it.last()
        val start = it.previous()
        return end - start
    }

    /** Devuelve [text] sin el último grapheme cluster. */
    fun removeLastCluster(text: String): String {
        if (text.isEmpty()) return text
        val it = BreakIterator.getCharacterInstance()
        it.setText(text)
        val end = it.last()
        val start = it.previous()
        return text.substring(0, start)
    }
}
