package com.elladecide.lola.keyboard

/**
 * Temporización del autorepeat (backspace/espacio). Extraída a función pura
 * para poder testear la curva de aceleración sin Compose.
 */
object AutoRepeat {

    // Respuesta inicial breve y aceleración perceptible: evita el salto largo
    // entre el primer carácter y el comienzo del borrado sostenido.
    const val INITIAL_DELAY_MS = 280L
    const val MIN_DELAY_MS = 55L
    private const val FACTOR = 0.65

    /** Siguiente espera, cada vez más corta hasta un piso mínimo. */
    fun nextDelay(current: Long): Long = (current * FACTOR).toLong().coerceAtLeast(MIN_DELAY_MS)
}
