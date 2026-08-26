package com.elladecide.lola.keyboard

/**
 * Layouts data-driven del teclado base. Son puros (solo [Key]) para poder
 * testear su integridad en la JVM (todas las letras, sin duplicados, filas
 * balanceadas).
 *
 * Estructura (spec F3):
 *   TOOLBAR (cinta superior: tools o sugerencias)
 *   NUMBER_ROW (solo capa de letras)
 *   body (LETTERS / SYMBOLS / AUX)
 *   BOTTOM_ROW (coma · espacio · punto · enter)
 *
 * Los controles auxiliares (cursor, undo, etc.) viven en [AUX], accesible
 * desde la cinta, para no ocupar la fila inferior del teclado principal.
 */
object Layouts {

    /** Fila numérica permanente encima de las letras (capa LETTERS). */
    val NUMBER_ROW: List<Key> = "1234567890".map { Key.char(it.toString()) }

    /** QWERTY español completo (3 filas + ñ). */
    val LETTERS: List<List<Key>> = listOf(
        "qwertyuiop".map { Key.char(it.toString()) },
        "asdfghjklñ".map { Key.char(it.toString()) },
        listOf(Key.SHIFT) + "zxcvbnm".map { Key.char(it.toString()) } + listOf(Key.BACKSPACE),
    )

    /** Símbolos: fila numérica + signos + puntuación. Los acentos (á é í ó ú ñ)
     *  se acceden por long-press en las vocales de la capa de letras. */
    val SYMBOLS: List<List<Key>> = listOf(
        "1234567890".map { Key.char(it.toString()) },
        "@#$%&*+-()".map { Key.char(it.toString()) },
        "_.!?,:'\"/".map { Key.char(it.toString()) } + listOf(Key.BACKSPACE),
    )

    /** Fila inferior del teclado principal: coma · espacio · punto · enter. */
    val BOTTOM_ROW: List<Key> = listOf(
        Key.COMMA, Key.SPACE, Key.DOT, Key.ENTER,
    )

    /** Teclado secundario con los controles auxiliares (cursor, undo, etc.). */
    val AUX: List<List<Key>> = listOf(
        listOf(Key.UNDO, Key.LEFT, Key.RIGHT),
        listOf(Key.BACK, Key.EMOJI, Key.LOLA),
    )

    /**
     * Alternativas de long-press en la capa de letras (números en la fila
     * superior + tildes en vocales, como Gboard). Claves en minúscula.
     */
    val ALTERNATIVES: Map<String, List<String>> = mapOf(
        "q" to listOf("1"),
        "w" to listOf("2"),
        "e" to listOf("3", "é", "è", "ê", "ë", "ē"),
        "r" to listOf("4"),
        "t" to listOf("5"),
        "y" to listOf("6"),
        "u" to listOf("7", "ú", "ù", "û", "ü", "ū"),
        "i" to listOf("8", "í", "ì", "î", "ï", "ī"),
        "o" to listOf("9", "ó", "ò", "ô", "ö", "õ", "ø"),
        "p" to listOf("0"),
        "a" to listOf("á", "à", "â", "ä", "ã", "å"),
        "c" to listOf("ç"),
        "n" to listOf("ñ"),
    )
}
