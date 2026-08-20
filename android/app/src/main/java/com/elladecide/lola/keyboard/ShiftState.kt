package com.elladecide.lola.keyboard

/**
 * Estado de shift de 3 niveles, como Gboard:
 * minúsculas → una mayúscula → bloqueo → minúsculas.
 */
enum class ShiftState {
    MINUSCULAS, MAYUSCULA, BLOQUEO;

    /** True si las letras se muestran en mayúscula (shift temporal o bloqueado). */
    val isActive: Boolean get() = this != MINUSCULAS

    /** Transición de un tap sobre Shift. */
    fun toggle(): ShiftState = when (this) {
        MINUSCULAS -> MAYUSCULA
        MAYUSCULA -> MINUSCULAS
        BLOQUEO -> MINUSCULAS
    }

    /** Fija mayúsculas (doble tap o long-press). */
    fun lock(): ShiftState = BLOQUEO

    /** Aplica shift a [text] (solo relevante en la capa de letras). */
    fun capitalize(text: String): String = if (isActive) text.uppercase() else text
}
