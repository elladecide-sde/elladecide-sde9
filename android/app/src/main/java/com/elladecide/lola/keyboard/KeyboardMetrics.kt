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
 * NADA es un dp clavado: la tecla, la cinta superior, el mínimo del panel LOLA
 * y la etiqueta se DERIVAN del alto disponible y del `fontScale`. La cinta y el
 * panel escalan con la fuente (los textos son la unidad de medida); el teclado
 * encoge sus teclas si el presupuesto baja. El `ImeRootView` clampa la ventana
 * al área real, así nunca desborda.
 */
data class KeyboardMetrics(
    val keyHeightDp: Float,
    val spacingDp: Float,
    val screenPaddingDp: Float,
    val popupCellDp: Float,
    val popupHeightDp: Float,
    val labelSizeSp: Float,
    /** Altura de la cinta superior (toolbar/sugerencias), escala con la fuente. */
    val stripHeightDp: Float,
    /** Mínimo del panel de catálogo en LOLA, escala con la fuente. */
    val panelMinDp: Float,
) {

    companion object {
        const val MAX_KEY_HEIGHT_DP = 56f
        const val MIN_KEY_HEIGHT_DP = 36f

        /** Mínimo usable de alto de tecla: el piso del panel LOLA puede robarle
         *  al teclado solo hasta este punto; nunca colapsa el shell (spec F3). */
        const val MIN_USABLE_KEY_HEIGHT_DP = 28f
        const val SPACING_DP = 3f
        const val SCREEN_PADDING_DP = 6f
        const val POPUP_CELL_DP = 48f
        const val POPUP_HEIGHT_DP = 56f

        /** Base de la cinta superior (se multiplica por el font scale). */
        const val STRIP_HEIGHT_DP = 44f

        /** Base del mínimo del panel LOLA (se multiplica por el font scale).
         *  Cubre los controles fijos del catálogo: buscador, acciones, switches
         *  de formato, selector de lista y sección de descuento. */
        const val PANEL_MIN_DP = 256f

        /** Alto mínimo de los controles fijos del panel (buscador + acciones +
         *  switches + selector + descuento) para que NUNCA desborden sobre la
         *  barra ENVIAR en ventanas chicas. */
        const val PANEL_CONTROLS_MIN_DP = 190f

        /** Altura fija de la barra ENVIAR, fuera del panel LOLA (siempre visible). */
        const val FOOTER_BAR_DP = 48f

        /** Tope de escala de cinta/panel: no explotan con fuentes enormes. */
        const val MAX_FONT_SCALE = 1.5f

        /** Filas de letras/símbolos + fila inferior. */
        const val KEY_ROWS = 3
        const val BOTTOM_ROW = 1
        const val TOTAL_ROWS = KEY_ROWS + BOTTOM_ROW

        /** Relación entre la etiqueta y el alto de tecla (la etiqueta cabe). */
        const val LABEL_KEY_RATIO = 0.34f

        /** Overhead fijo del cuerpo: padding arriba/abajo + separación entre filas. */
        private const val OVERHEAD_KEY_BODY_DP =
            SCREEN_PADDING_DP * 2 + SPACING_DP * (TOTAL_ROWS - 1)

        /** Cinta superior escalada por la fuente (acotada a [MAX_FONT_SCALE]). */
        fun stripHeight(fontScale: Float): Float =
            STRIP_HEIGHT_DP * fontScale.coerceIn(1f, MAX_FONT_SCALE)

        /** Mínimo del panel LOLA escalado por la fuente (acotado). */
        fun panelMin(fontScale: Float): Float =
            PANEL_MIN_DP * fontScale.coerceIn(1f, MAX_FONT_SCALE)

        /** Altura de la barra ENVIAR (fuera del panel), escala con la fuente. */
        fun footerHeightDp(fontScale: Float): Float = FOOTER_BAR_DP * fontScale.coerceIn(1f, MAX_FONT_SCALE)

        /**
         * Alto mínimo que consume el SHELL del teclado (cinta + 4 filas al
         * mínimo + padding + espaciados). Es el presupuesto que el teclado
         * reclama siempre; en modo LOLA el panel de catálogo se lleva todo lo
         * que quede por encima de esto (spec F3: adaptarse al viewport real,
         * nunca desbordar).
         */
        fun keyBodyMinDp(stripHeightDp: Float): Float =
            stripHeightDp + OVERHEAD_KEY_BODY_DP + TOTAL_ROWS * MIN_KEY_HEIGHT_DP

        /** Alto del shell del teclado (cinta + filas + padding + espaciados)
         *  para un alto de tecla dado. Permite acotar cuánto puede robarle el
         *  piso del panel LOLA al teclado sin bajar de un mínimo usable. */
        fun keyBodyForKeyHeight(stripHeightDp: Float, keyHeightDp: Float): Float =
            stripHeightDp + OVERHEAD_KEY_BODY_DP + TOTAL_ROWS * keyHeightDp

        /** Alto del panel LOLA (sin la barra ENVIAR, que vive fuera del panel).
         *  Política: la integridad del panel (sus controles fijos y la barra ENVIAR)
         *  tiene prioridad sobre el tamaño de las teclas. Cuando el viewport alcanza
         *  para ambos, las teclas conservan MIN_USABLE_KEY_HEIGHT_DP; cuando no alcanza
         *  (landscape/fuente grande), ceden las teclas, no el panel. La reserva de la
         *  barra ENVIAR también escala con la fuente (igual que cinta y panel). */
        fun lolaPanelHeightDp(availableHeightDp: Float, fontScale: Float): Float {
            val fs = fontScale.coerceIn(1f, MAX_FONT_SCALE)
            val strip = stripHeight(fs)
            val footer = footerHeightDp(fontScale)
            val budget = (availableHeightDp - footer - keyBodyMinDp(strip)).coerceAtLeast(0f)
            val upper = (availableHeightDp - footer - keyBodyForKeyHeight(strip, MIN_USABLE_KEY_HEIGHT_DP)).coerceAtLeast(0f)
            val floor = PANEL_CONTROLS_MIN_DP * fs
            // La cinta superior (toolbar) y la barra ENVIAR SIEMPRE entran en el viewport:
            // el panel nunca puede comerse su espacio.
            val stripCap = (availableHeightDp - footer - strip).coerceAtLeast(0f)
            return budget.coerceAtLeast(panelMin(fs))
                .coerceAtMost(maxOf(upper, floor))
                .coerceAtMost(stripCap)
        }

        /**
         * Calcula las métricas para [availableHeightDp] (alto real de la ventana
         * del IME) y [fontScale].
         *
         * La tecla se encoge si no entra en el alto: si el presupuesto es menor
         * que el mínimo cómodo, la tecla baja de [MIN_KEY_HEIGHT_DP] antes que
         * desbordar el viewport (spec F3 §4). [MAX_KEY_HEIGHT_DP] sigue siendo
         * tope. La etiqueta se dimensiona como fracción del alto de tecla y se
         * compensa con el font scale para que nunca se recorte. La cinta y el
         * panel mínimo escalan con la fuente (más alto con fuentes grandes).
         */
        fun compute(availableHeightDp: Float, fontScale: Float): KeyboardMetrics {
            val strip = stripHeight(fontScale)
            val overhead = strip + OVERHEAD_KEY_BODY_DP
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
                stripHeightDp = strip,
                panelMinDp = panelMin(fontScale),
            )
        }
    }
}