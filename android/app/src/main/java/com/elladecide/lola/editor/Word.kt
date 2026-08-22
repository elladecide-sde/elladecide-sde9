package com.elladecide.lola.editor

/**
 * Predicados de límite de palabra, compartidos entre el tipeo ([EditorSession])
 * y el borrado por palabra ([Grapheme.wordLengthBackwards]). Puro, sin android.*.
 */
object Word {

    /** Carácter que forma parte de una palabra (letra, dígito o apóstrofe). */
    fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '\''

    /** True si todo [text] son caracteres de palabra. */
    fun isWord(text: String): Boolean = text.isNotEmpty() && text.all(::isWordChar)
}
