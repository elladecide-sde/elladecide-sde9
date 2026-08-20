package com.elladecide.lola.keyboard

/**
 * Tecla del teclado base, data-driven y pura (sin android.* ni Compose).
 * [char] aplica solo a [KeyType.CHAR].
 */
data class Key(val type: KeyType, val char: String? = null) {

    companion object {
        fun char(c: String): Key = Key(KeyType.CHAR, c)

        val SHIFT = Key(KeyType.SHIFT)
        val BACKSPACE = Key(KeyType.BACKSPACE)
        val SPACE = Key(KeyType.SPACE)
        val ENTER = Key(KeyType.ENTER)
        val LAYER = Key(KeyType.LAYER)
        val EMOJI = Key(KeyType.EMOJI)
        val LOLA = Key(KeyType.LOLA)
        val LEFT = Key(KeyType.LEFT)
        val RIGHT = Key(KeyType.RIGHT)
    }
}
