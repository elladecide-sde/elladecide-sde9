package com.elladecide.lola.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardMetricsTest {

    @Test
    fun `tecla de 46dp en 250dp sin fila numerica`() {
        val m = KeyboardMetrics.compute(250f, 1f, showNumberRow = false)
        assertEquals(46.5f, m.keyHeightDp, 0.01f)
    }

    @Test
    fun `fila numerica reserva altura y encoge la tecla`() {
        val conNum = KeyboardMetrics.compute(250f, 1f, showNumberRow = true)
        val sinNum = KeyboardMetrics.compute(250f, 1f, showNumberRow = false)
        assertTrue(conNum.keyHeightDp < sinNum.keyHeightDp)
        assertTrue(conNum.keyHeightDp < KeyboardMetrics.MIN_KEY_HEIGHT_DP)
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
            (KeyboardMetrics.SCREEN_PADDING_DP * 2 + KeyboardMetrics.SPACING_DP * (KeyboardMetrics.TOTAL_ROWS - 1)) +
            (KeyboardMetrics.NUMBER_ROW_HEIGHT_DP + KeyboardMetrics.SPACING_DP)
        val h = 180f
        val m = KeyboardMetrics.compute(h, 1f, showNumberRow = true)
        assertTrue(overhead + KeyboardMetrics.TOTAL_ROWS * m.keyHeightDp <= h)
    }

    @Test
    fun `el presupuesto del shell da teclas de minimo con fila numerica`() {
        val m = KeyboardMetrics.compute(KeyboardMetrics.MIN_KEY_BODY_DP, 1f, showNumberRow = true)
        assertEquals(KeyboardMetrics.MIN_KEY_HEIGHT_DP, m.keyHeightDp, 0.01f)
    }

    @Test
    fun `en 272dp con fila numerica las teclas son comodas y caben`() {
        val m = KeyboardMetrics.compute(272f, 1f, showNumberRow = true)
        assertTrue(m.keyHeightDp >= KeyboardMetrics.MIN_KEY_HEIGHT_DP)
        assertTrue(m.keyHeightDp < KeyboardMetrics.MAX_KEY_HEIGHT_DP)
    }

    @Test
    fun `etiqueta compensa el font scale`() {
        val normal = KeyboardMetrics.compute(250f, 1f).labelSizeSp
        val grande = KeyboardMetrics.compute(250f, 2f).labelSizeSp
        assertTrue(grande < normal)
        assertEquals(normal, grande * 2f, 0.01f)
    }
}
