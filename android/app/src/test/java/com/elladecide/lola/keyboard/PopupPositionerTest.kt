package com.elladecide.lola.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cobertura del posicionamiento del popup de long-press (spec F3 §9):
 * arriba si entra, abajo si no, clamp al viewport como último recurso, y nunca
 * sale por los bordes izquierdo/derecho.
 */
class PopupPositionerTest {

    private fun pos(
        keyLeft: Float,
        keyTop: Float = 200f,
        keyW: Float = 50f,
        keyH: Float = 50f,
        popupW: Float = 100f,
        popupH: Float = 56f,
        viewportW: Float = 360f,
        viewportH: Float = 500f,
    ) = PopupPositioner.position(keyLeft, keyTop, keyW, keyH, popupW, popupH, viewportW, viewportH)

    @Test
    fun `centra el popup sobre la tecla cuando entra arriba`() {
        val (left, top) = pos(keyLeft = 100f, keyTop = 200f)
        assertEquals(75f, left, 0.01f)
        assertEquals(144f, top, 0.01f)
    }

    @Test
    fun `abre hacia abajo cuando no hay espacio arriba`() {
        val (left, top) = pos(keyLeft = 100f, keyTop = 10f)
        assertEquals(75f, left, 0.01f)
        assertEquals(60f, top, 0.01f)
    }

    @Test
    fun `clamp al viewport cuando no hay espacio ni arriba ni abajo`() {
        val (_, top) = pos(keyLeft = 100f, keyTop = 10f, viewportH = 100f)
        assertEquals(44f, top, 0.01f)
    }

    @Test
    fun `nunca sale por el borde izquierdo`() {
        val (left, _) = pos(keyLeft = 0f)
        assertEquals(0f, left, 0.01f)
    }

    @Test
    fun `nunca sale por el borde derecho`() {
        val (left, _) = pos(keyLeft = 300f)
        assertEquals(260f, left, 0.01f)
        assertEquals(360f, left + 100f, 0.01f)
    }

    @Test
    fun `popup mas ancho que el viewport queda dentro`() {
        val (left, _) = PopupPositioner.position(
            keyLeft = 10f, keyTop = 100f, keyWidth = 50f, keyHeight = 50f,
            popupWidth = 400f, popupHeight = 56f, viewportWidth = 360f, viewportHeight = 500f,
        )
        assertEquals(0f, left, 0.01f)
    }
}
