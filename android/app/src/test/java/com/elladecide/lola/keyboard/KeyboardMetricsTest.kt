package com.elladecide.lola.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardMetricsTest {

    @Test
    fun `tecla de 46dp en 250dp`() {
        val m = KeyboardMetrics.compute(250f, 1f)
        assertEquals(46.25f, m.keyHeightDp, 0.01f)
    }

    @Test
    fun `no supera el maximo en pantalla alta`() {
        val m = KeyboardMetrics.compute(600f, 1f)
        assertEquals(KeyboardMetrics.MAX_KEY_HEIGHT_DP, m.keyHeightDp, 0.01f)
    }

    @Test
    fun `se encoge en pantalla baja sin desbordar`() {
        val m = KeyboardMetrics.compute(150f, 1f)
        assertTrue(m.keyHeightDp < KeyboardMetrics.MIN_KEY_HEIGHT_DP)
        assertTrue(m.keyHeightDp > 0f)
    }

    @Test
    fun `en ventana muy baja encoge antes que desbordar`() {
        val m = KeyboardMetrics.compute(80f, 1f)
        assertTrue(m.keyHeightDp < KeyboardMetrics.MIN_KEY_HEIGHT_DP)
        assertTrue(m.keyHeightDp > 0f)
    }

    @Test
    fun `el shell nunca desborda el presupuesto disponible`() {
        val overhead = KeyboardMetrics.STRIP_HEIGHT_DP +
            (KeyboardMetrics.SCREEN_PADDING_DP * 2 + KeyboardMetrics.SPACING_DP * (KeyboardMetrics.TOTAL_ROWS - 1))
        val h = 180f
        val m = KeyboardMetrics.compute(h, 1f)
        assertTrue(overhead + KeyboardMetrics.TOTAL_ROWS * m.keyHeightDp <= h)
    }

    @Test
    fun `el presupuesto del shell da teclas de minimo`() {
        val m = KeyboardMetrics.compute(
            KeyboardMetrics.keyBodyMinDp(KeyboardMetrics.stripHeight(1f)),
            1f,
        )
        assertEquals(KeyboardMetrics.MIN_KEY_HEIGHT_DP, m.keyHeightDp, 0.01f)
    }

    @Test
    fun `la cinta y el panel escalan con la fuente`() {
        val normal = KeyboardMetrics.compute(300f, 1f)
        val grande = KeyboardMetrics.compute(300f, 1.3f)
        assertTrue(grande.stripHeightDp > normal.stripHeightDp)
        assertTrue(grande.panelMinDp > normal.panelMinDp)
        assertTrue(grande.keyHeightDp < normal.keyHeightDp) // más overhead → tecla más chica
    }

    @Test
    fun `el panel minimo cubre los controles fijos del catalogo`() {
        // Buscador + acciones + switches de formato + selector de lista +
        // sección de descuento + barra ENVIAR (+ espaciados y padding).
        assertTrue(KeyboardMetrics.PANEL_MIN_DP >= 240f)
        assertTrue(KeyboardMetrics.PANEL_MIN_DP > 0f)
    }

    @Test
    fun `en 272dp las teclas son comodas y caben`() {
        val m = KeyboardMetrics.compute(272f, 1f)
        assertTrue(m.keyHeightDp >= KeyboardMetrics.MIN_KEY_HEIGHT_DP)
        assertTrue(m.keyHeightDp < KeyboardMetrics.MAX_KEY_HEIGHT_DP)
    }

    @Test
    fun `etiqueta compensa el font scale`() {
        val normal = KeyboardMetrics.compute(250f, 1f).labelSizeSp
        val grande = KeyboardMetrics.compute(250f, 2f).labelSizeSp
        assertTrue(grande < normal)
        // La cinta también escala con la fuente, así que la tecla encoge y la
        // compensación es >= 2x (la etiqueta nunca se recorta).
        assertTrue(normal / grande >= 2f)
    }

    @Test
    fun `el panel LOLA nunca desborda sobre la barra ENVIAR y nunca deja espacio negativo`() {
        val maxFs = KeyboardMetrics.MAX_FONT_SCALE
        assertEquals(255f, KeyboardMetrics.lolaPanelHeightDp(480f, 1f), 0.01f)
        assertEquals(543f, KeyboardMetrics.lolaPanelHeightDp(800f, 1f), 0.01f)
        assertEquals(285f, KeyboardMetrics.lolaPanelHeightDp(480f, maxFs), 0.01f)
        assertEquals(497f, KeyboardMetrics.lolaPanelHeightDp(800f, maxFs), 0.01f)

        // Invariantes: nunca espacio negativo para el teclado, y el panel nunca
        // baja de sus controles fijos (PANEL_CONTROLS_MIN_DP) cuando la ventana
        // lo permite. A fs máximo (MAX_FONT_SCALE) también se cubren 480 y 800.
        // Degeneraciones aceptadas por diseño: las teclas pueden caer por debajo
        // de MIN_USABLE_KEY_HEIGHT_DP cuando el viewport no alcanza para controles
        // del panel + barra ENVIAR + teclas usables (p. ej. h=360 fs=1 → teclas
        // 14.25; h<~303 → teclas 0). Gana la integridad del panel.
        for ((fs, h) in listOf(
            1f to 360f, 1f to 300f, 1f to 480f, 1f to 800f,
            maxFs to 480f, maxFs to 800f,
        )) {
            val panel = KeyboardMetrics.lolaPanelHeightDp(h, fs)
            val footerScaled = KeyboardMetrics.footerHeightDp(fs)
            val restoTeclado = h - panel - footerScaled
            // La barra ENVIAR siempre tiene su altura: nunca queda negativa.
            assertTrue("h=$h fs=$fs deja espacio negativo para el teclado", restoTeclado >= 0f)
            // La cinta superior SIEMPRE entra junto al panel y la barra ENVIAR.
            assertTrue(
                "h=$h fs=$fs no deja lugar para la cinta superior",
                h - panel - footerScaled - KeyboardMetrics.stripHeight(fs) >= 0f,
            )
            if (h >= KeyboardMetrics.footerHeightDp(fs) + KeyboardMetrics.PANEL_CONTROLS_MIN_DP * fs) {
                assertTrue(
                    "h=$h fs=$fs deja el panel por debajo de sus controles fijos",
                    panel >= KeyboardMetrics.PANEL_CONTROLS_MIN_DP * fs,
                )
            }
        }
    }
}