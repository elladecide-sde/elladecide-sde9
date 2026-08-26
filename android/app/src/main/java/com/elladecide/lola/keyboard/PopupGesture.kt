package com.elladecide.lola.keyboard

/**
 * Fases del popup de long-press (spec F3 §11). Estado explícito, sin estados
 * ambiguos; al cerrar se limpia la selección y el gesto.
 */
enum class PopupPhase { IDLE, LONG_PRESS_ACTIVE, OPTION_SELECTED, CANCELLED }

/**
 * Máquina de estados del popup de long-press. Pura (sin Compose) y
 * JVM-testable.
 *
 * Transiciones:
 *   IDLE → [start] → LONG_PRESS_ACTIVE
 *   LONG_PRESS_ACTIVE → [moveTo] (índice ≥ 0) → OPTION_SELECTED
 *   OPTION_SELECTED → [moveTo] (índice < 0, dedo fuera) → LONG_PRESS_ACTIVE
 *   (cualquier estado activo) → [cancel] → CANCELLED → IDLE
 *   (cualquier estado activo) → [commit] → devuelve la selección → IDLE
 */
class PopupGesture {
    var phase: PopupPhase = PopupPhase.IDLE
        private set

    var selected: Int = -1
        private set

    /** Abre el popup (long-press detectado). */
    fun start() {
        phase = PopupPhase.LONG_PRESS_ACTIVE
        selected = -1
    }

    /** Actualiza la alternativa resaltada; índice < 0 = dedo fuera del popup. */
    fun moveTo(index: Int) {
        if (phase != PopupPhase.LONG_PRESS_ACTIVE && phase != PopupPhase.OPTION_SELECTED) return
        selected = index
        phase = if (index >= 0) PopupPhase.OPTION_SELECTED else PopupPhase.LONG_PRESS_ACTIVE
    }

    /** Cierra con selección (al levantar el dedo). Devuelve la alternativa elegida. */
    fun commit(): Int {
        val s = selected
        reset()
        return s
    }

    /** Cancela (back, gesto cancelado, tap fuera): sin selección, limpia todo. */
    fun cancel() {
        phase = PopupPhase.CANCELLED
        selected = -1
        reset()
    }

    private fun reset() {
        phase = PopupPhase.IDLE
        selected = -1
    }
}

/**
 * Geometría de selección del popup: dado el dedo (coordenadas del root) y la
 * caja del popup, devuelve el índice de celda o -1 si el dedo quedó FUERA del
 * popup (se deselecciona).
 */
object PopupGeometry {
    fun cellIndexAt(
        fingerX: Float,
        popupLeft: Float,
        popupWidth: Float,
        cellWidth: Float,
        count: Int,
    ): Int {
        if (fingerX < popupLeft || fingerX >= popupLeft + popupWidth) return -1
        return ((fingerX - popupLeft) / cellWidth).toInt().coerceIn(0, count - 1)
    }
}
