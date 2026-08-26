package com.elladecide.lola.keyboard

/**
 * Posiciona el popup de long-press dentro del viewport real del IME.
 *
 * Reglas (spec F3):
 *   1. Intentar ARRIBA de la tecla.
 *   2. Si no hay espacio arriba, ABAJO.
 *   3. Si tampoco hay espacio abajo, clamp al viewport.
 *   4. NUNCA queda parcialmente fuera (ni vertical ni horizontal).
 *   5. NUNCA se corta por el borde izquierdo o derecho.
 *
 * Es puro (solo números) y JVM-testable; la UI no hace offsets a mano.
 */
object PopupPositioner {

    /**
     * Offset del popup en el mismo sistema de coordenadas que la tecla.
     *
     * @param keyLeft borde izquierdo de la tecla (coordenadas del root).
     * @param keyTop borde superior de la tecla (coordenadas del root).
     * @param keyWidth ancho de la tecla.
     * @param keyHeight alto de la tecla.
     * @param popupWidth ancho del popup (celdas × cantidad de alternativas).
     * @param popupHeight alto del popup.
     * @param viewportWidth ancho del viewport (root).
     * @param viewportHeight alto del viewport (root).
     */
    fun position(
        keyLeft: Float,
        keyTop: Float,
        keyWidth: Float,
        keyHeight: Float,
        popupWidth: Float,
        popupHeight: Float,
        viewportWidth: Float,
        viewportHeight: Float,
    ): Pair<Float, Float> {
        // Horizontal: centrar sobre la tecla y clampear al viewport.
        val left = (keyLeft + (keyWidth - popupWidth) / 2f)
            .coerceIn(0f, (viewportWidth - popupWidth).coerceAtLeast(0f))

        // Vertical: arriba si entra, si no abajo, si no clamp al viewport.
        val top = when {
            keyTop - popupHeight >= 0f -> keyTop - popupHeight
            keyTop + keyHeight + popupHeight <= viewportHeight -> keyTop + keyHeight
            else -> (viewportHeight - popupHeight).coerceAtLeast(0f)
        }
        return left to top
    }
}
