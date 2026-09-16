package com.elladecide.lola.language

import java.text.Normalizer
import java.util.Locale

/** Normalización compartida por el diccionario y las features del corrector. */
internal object SpanishNormalizer {
    fun normalize(word: String): String = buildString(word.length) {
        word.lowercase(Locale.ROOT).forEach { char ->
            if (char == 'ñ') {
                append(char)
            } else {
                Normalizer.normalize(char.toString(), Normalizer.Form.NFD)
                    .forEach { decomposed ->
                        if (Character.getType(decomposed) != Character.NON_SPACING_MARK.toInt()) {
                            append(decomposed)
                        }
                    }
            }
        }
    }
}
