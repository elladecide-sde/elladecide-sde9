package com.elladecide.lola.keyboard

/**
 * Semántica de una tecla del teclado base, independiente de la UI (Compose).
 * Determina qué hace [KeyEngine] al procesarla.
 */
enum class KeyType {
    CHAR,
    SHIFT,
    BACKSPACE,
    SPACE,
    ENTER,
    LAYER,
    EMOJI,
    LOLA,
    LEFT,
    RIGHT,
}
