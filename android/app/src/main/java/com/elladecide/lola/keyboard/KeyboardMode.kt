package com.elladecide.lola.keyboard

/**
 * Modo de funcionamiento del teclado (estado explícito, sin booleanos
 * ambiguos como `isLola`).
 */
enum class KeyboardMode {
    /** Teclado normal: predicciones, suggestion strip y toolbar dinámica. */
    CHAT,

    /** Teclado LOLA: sin predicciones, cinta estable, acceso a catálogo. */
    LOLA,
}
