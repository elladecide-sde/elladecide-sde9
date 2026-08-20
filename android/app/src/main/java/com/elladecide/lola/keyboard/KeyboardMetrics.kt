package com.elladecide.lola.keyboard

/**
 * Métricas adaptativas del teclado, data-driven y puras (solo `Float`). Se
 * calculan a partir de la altura disponible y del `fontScale` para que el
 * teclado no desborde en pantallas chicas ni en landscape.
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

        /** Filas de letras/símbolos + fila inferior. */
        const val KEY_ROWS = 3
        const val BOTTOM_ROW = 1
        const val TOTAL_ROWS = KEY_ROWS + BOTTOM_ROW

        /** Relación entre la etiqueta y el alto de tecla (la etiqueta cabe). */
        const val LABEL_KEY_RATIO = 0.34f

        /**
         * Overhead vertical fijo: padding arriba/abajo + separación entre las
         * [TOTAL_ROWS] filas.
         */
        private const val OVERHEAD_DP =
            SCREEN_PADDING_DP * 2 + SPACING_DP * (TOTAL_ROWS - 1)

        /**
         * Calcula las métricas para [availableHeightDp] (alto real de la ventana
         * del IME) y [fontScale]. La tecla se encoge si no entra en el alto; la
         * etiqueta se dimensiona como fracción del alto de tecla y se compensa
         * con el font scale para que nunca se recorte (el teclado no escala con
         * el font del sistema, como Gboard).
         */
        fun compute(availableHeightDp: Float, fontScale: Float): KeyboardMetrics {
            val key = ((availableHeightDp - OVERHEAD_DP) / TOTAL_ROWS)
                .coerceIn(MIN_KEY_HEIGHT_DP, MAX_KEY_HEIGHT_DP)
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
