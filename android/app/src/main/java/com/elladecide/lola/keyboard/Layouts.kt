package com.elladecide.lola.keyboard

/**
 * Layouts data-driven del teclado base. Son puros (solo [Key]) para poder
 * testear su integridad en la JVM (todas las letras, sin duplicados, filas
 * balanceadas).
 *
 * Estructura (spec F3 corregida tras prueba real):
 *   TOOLBAR (cinta superior: herramientas — nunca controles de escritura)
 *   body (LETTERS / SYMBOLS / CURSOR)
 *   BOTTOM_ROW (123 · coma · emoji · espacio · punto · enter)
 *
 * El teclado de navegación (cursor izq./der., volver, deshacer) vive en
 * [CURSOR], accesible desde la cinta con su acrónimo (no suelto en un menú).
 */
object Layouts {

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

    /**
     * Teclado de navegación dedicado (lo que antes eran controles sueltos):
     * cursor izquierda/derecha, deshacer, volver al teclado principal y acceso
     * directo a emojis/modo LOLA.
     */
    val CURSOR: List<List<Key>> = listOf(
        listOf(Key.UNDO, Key.LEFT, Key.RIGHT),
        listOf(Key.BACK, Key.EMOJI, Key.LOLA),
    )

    /** Fila inferior del teclado principal (spec: 123 | , | 😀 | ESPACIO | . | ↵). */
    val BOTTOM_ROW: List<Key> = listOf(
        Key.LAYER, Key.COMMA, Key.EMOJI, Key.SPACE, Key.DOT, Key.ENTER,
    )

    /**
     * Alternativas de long-press en la capa de letras. Claves en minúscula;
     * con shift activo se capitalizan. Números sobre la fila QWERTY superior
     * (ya no hay fila numérica permanente) + tildes y variantes.
     */
    val ALTERNATIVES: Map<String, List<String>> = mapOf(
        "q" to listOf("1"),
        "w" to listOf("2"),
        "e" to listOf("3", "é", "è", "ê", "ë", "ē"),
        "r" to listOf("4"),
        "t" to listOf("5"),
        "y" to listOf("6", "ý", "ÿ"),
        "u" to listOf("7", "ú", "ù", "û", "ü", "ū"),
        "i" to listOf("8", "í", "ì", "î", "ï", "ī"),
        "o" to listOf("9", "ó", "ò", "ô", "ö", "õ", "ø"),
        "p" to listOf("0"),
        "a" to listOf("á", "à", "â", "ä", "ã", "å"),
        "s" to listOf("ß", "ś", "š"),
        "d" to listOf("ð", "đ"),
        "c" to listOf("ç", "ć", "č"),
        "n" to listOf("ñ", "ń"),
        "z" to listOf("ż", "ź", "ž"),
        "l" to listOf("ł", "ĺ"),
    )

    /**
     * Alternativas de long-press en la capa de símbolos (spec §4): interrogación,
     * exclamación, coma, punto, signos de apertura y variantes frecuentes.
     */
    val SYMBOL_ALTERNATIVES: Map<String, List<String>> = mapOf(
        "?" to listOf("¿"),
        "!" to listOf("¡"),
        "," to listOf("،"),
        "." to listOf("…", "·", "•"),
        "-" to listOf("–", "—", "−"),
        "_" to listOf("—", "–"),
        "'" to listOf("’", "‘", "´"),
        "\"" to listOf("“", "”", "«", "»"),
        "(" to listOf("[", "{", "«"),
        ")" to listOf("]", "}", "»"),
        ":" to listOf(";"),
        "/" to listOf("\\"),
        "$" to listOf("€", "£", "¥", "¢"),
        "%" to listOf("‰"),
        "#" to listOf("№"),
        "&" to listOf("§"),
        "*" to listOf("×", "•"),
        "+" to listOf("±"),
        "=" to listOf("≈", "≠", "±"),
        "0" to listOf("°"),
    )
}