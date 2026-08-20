package com.elladecide.lola.keyboard

/**
 * Temporización del autorepeat (backspace/espacio). Extraída a función pura
 * para poder testear la curva de aceleración sin Compose.
 */
object AutoRepeat {

    const val INITIAL_DELAY_MS = 350L
    const val MIN_DELAY_MS = 40L
    private const val FACTOR = 0.7

    /** Siguiente espera, cada vez más corta hasta un piso mínimo. */
    fun nextDelay(current: Long): Long = (current * FACTOR).toLong().coerceAtLeast(MIN_DELAY_MS)
}
