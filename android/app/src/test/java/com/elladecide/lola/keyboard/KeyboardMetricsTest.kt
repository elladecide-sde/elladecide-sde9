package com.elladecide.lola.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardMetricsTest {

    @Test
    fun `tecla de 52dp en pantalla tipica de 250dp`() {
        val m = KeyboardMetrics.compute(250f, 1f)
        assertEquals(52f, m.keyHeightDp, 0.01f)
    }

    @Test
    fun `no supera el maximo en pantalla alta`() {
        val m = KeyboardMetrics.compute(600f, 1f)
        assertEquals(KeyboardMetrics.MAX_KEY_HEIGHT_DP, m.keyHeightDp, 0.01f)
    }

    @Test
    fun `se encoge en pantalla baja`() {
        val m = KeyboardMetrics.compute(150f, 1f)
        assertTrue(m.keyHeightDp < 52f)
        assertTrue(m.keyHeightDp >= KeyboardMetrics.MIN_KEY_HEIGHT_DP)
    }

    @Test
    fun `respeta el minimo en pantalla muy baja`() {
        val m = KeyboardMetrics.compute(80f, 1f)
        assertEquals(KeyboardMetrics.MIN_KEY_HEIGHT_DP, m.keyHeightDp, 0.01f)
    }

    @Test
    fun `etiqueta compensa el font scale`() {
        val normal = KeyboardMetrics.compute(250f, 1f).labelSizeSp
        val grande = KeyboardMetrics.compute(250f, 2f).labelSizeSp
        assertTrue(grande < normal)
        assertEquals(normal, grande * 2f, 0.01f)
    }
}
