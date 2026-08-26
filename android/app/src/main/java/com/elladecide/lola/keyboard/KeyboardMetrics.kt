package com.elladecide.lola.keyboard

/**
 * Métricas adaptativas del teclado, data-driven y puras (solo `Float`). Se
 * calculan a partir de la altura disponible y del `fontScale` para que el
 * teclado no desborde en pantallas chicas ni en landscape.
 *
 * Estructura vertical (spec F3 corregida):
 *   STRIP (toolbar o sugerencias — geometría estable, una u otra)
 *   body (filas de teclas)
 *   BOTTOM_ROW (123 · coma · emoji · espacio · punto · enter)
 *
 * Toda la geometría del shell (alto de tecla, espaciados, popup, tamaño de
 * etiqueta) se deriva acá en vez de hardcodearse en los Composable, de modo
 * que se puede testear en la JVM y adaptarse al contenedor real.
 */
data class KeyboardMetrics(
    val keyHeightDp: Float,
    val spacingDp: Float,
    val screenPaddingDp: Float,
    val popupCellDp: Float,
    val popupHeightDp: Float,
    val labelSizeSp: Float,
) {

    companion object {
        const val MAX_KEY_HEIGHT_DP = 52f
        const val MIN_KEY_HEIGHT_DP = 36f
        const val SPACING_DP = 4f
        const val SCREEN_PADDING_DP = 8f
        const val POPUP_CELL_DP = 48f
        const val POPUP_HEIGHT_DP = 56f

        /** Cinta superior: toolbar o sugerencias (geometría estable). */
        const val STRIP_HEIGHT_DP = 44f

        /** Filas de letras/símbolos + fila inferior. */
        const val KEY_ROWS = 3
        const val BOTTOM_ROW = 1
        const val TOTAL_ROWS = KEY_ROWS + BOTTOM_ROW

        /** Relación entre la etiqueta y el alto de tecla (la etiqueta cabe). */
        const val LABEL_KEY_RATIO = 0.34f

        /** Overhead fijo del cuerpo: padding arriba/abajo + separación entre filas. */
        private const val OVERHEAD_KEY_BODY_DP =
            SCREEN_PADDING_DP * 2 + SPACING_DP * (TOTAL_ROWS - 1)

        /**
         * Alto mínimo que consume el SHELL del teclado (strip + 4 filas al
         * mínimo + padding + espaciados). Es el presupuesto que el teclado
         * reclama siempre; en modo LOLA el panel de catálogo se lleva todo lo
         * que quede por encima de esto (spec F3: adaptarse al viewport real,
         * nunca desbordar).
         */
        const val MIN_KEY_BODY_DP =
            STRIP_HEIGHT_DP + OVERHEAD_KEY_BODY_DP + TOTAL_ROWS * MIN_KEY_HEIGHT_DP

        /** Alto mínimo reservado al panel de catálogo en modo LOLA. Cubre sus
         * controles fijos (buscador + fila de acciones + barra ENVIAR) para que
         * nunca queden inaccesibles (spec §15); el área de resultados se comprime
         * y scrollea, y el teclado encoge sus teclas vía `compute()`.
         */
        const val PANEL_MIN_DP = 168f

        /**
         * Calcula las métricas para [availableHeightDp] (alto real de la ventana
         * del IME) y [fontScale].
         *
         * La tecla se encoge si no entra en el alto: si el presupuesto es menor
         * que el mínimo cómodo, la tecla baja de [MIN_KEY_HEIGHT_DP] antes que
         * desbordar el viewport (spec F3 §4: adaptarse al espacio disponible,
         * nunca quedar recortado). [MAX_KEY_HEIGHT_DP] sigue siendo tope. La
         * etiqueta se dimensiona como fracción del alto de tecla y se compensa
         * con el font scale para que nunca se recorte.
         */
        fun compute(
            availableHeightDp: Float,
            fontScale: Float,
        ): KeyboardMetrics {
            val overhead = STRIP_HEIGHT_DP + OVERHEAD_KEY_BODY_DP
            val key = ((availableHeightDp - overhead) / TOTAL_ROWS)
                .coerceAtMost(MAX_KEY_HEIGHT_DP)
                .coerceAtLeast(0f)
            return KeyboardMetrics(
                keyHeightDp = key,
                spacingDp = SPACING_DP,
                screenPaddingDp = SCREEN_PADDING_DP,
                popupCellDp = POPUP_CELL_DP,
                popupHeightDp = POPUP_HEIGHT_DP,
                labelSizeSp = (key * LABEL_KEY_RATIO) / fontScale.coerceAtLeast(1f),
            )
        }
    }
}