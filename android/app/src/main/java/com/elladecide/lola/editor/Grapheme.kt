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

    /** Longitud en unidades UTF-16 del primer grapheme cluster de [afterCursor]. */
    fun firstClusterLength(afterCursor: CharSequence): Int {
        if (afterCursor.isEmpty()) return 0
        val it = BreakIterator.getCharacterInstance()
        it.setText(afterCursor.toString())
        return it.next()
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

    /**
     * Longitud (en UTF-16) a borrar hacia atrás para eliminar la palabra a la
     * izquierda del cursor: espacios en blanco finales + la palabra que los
     * precede (o un único cluster no-palabra, p.ej. puntuación o emoji).
     * Grapheme-safe: opera por cluster, no por code unit.
     */
    fun wordLengthBackwards(beforeCursor: CharSequence): Int {
        val text = beforeCursor.toString()
        if (text.isEmpty()) return 0

        val clusters = mutableListOf<Int>()
        val it = BreakIterator.getCharacterInstance()
        it.setText(text)
        var b = it.first()
        while (b != BreakIterator.DONE) {
            clusters.add(b)
            b = it.next()
        }
        clusters.add(text.length)

        fun clusterAt(i: Int): String = text.substring(clusters[i], clusters[i + 1])

        var idx = clusters.size - 2 // índice del último cluster
        // 1. saltar espacios en blanco finales
        while (idx >= 0 && clusterAt(idx).all { it.isWhitespace() }) idx--
        if (idx < 0) return text.length // todo espacio en blanco
        // 2. palabra (run de word-chars) o un único cluster no-palabra
        if (Word.isWord(clusterAt(idx))) {
            while (idx >= 0 && Word.isWord(clusterAt(idx))) idx--
        } else {
            idx--
        }
        val startDel = if (idx < 0) 0 else clusters[idx + 1]
        return text.length - startDel
    }
}
