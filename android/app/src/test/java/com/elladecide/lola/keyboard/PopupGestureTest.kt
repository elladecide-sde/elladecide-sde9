package com.elladecide.lola.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Máquina de estados del popup de long-press (spec F3 §7, §8 y §11):
 * permanece abierto mientras el dedo está presionado, la selección se hace por
 * deslizamiento, se commitea al levantar y se limpia todo al cancelar.
 */
class PopupGestureTest {

    @Test
    fun `start abre el gesto sin seleccion`() {
        val g = PopupGesture()
        g.start()
        assertEquals(PopupPhase.LONG_PRESS_ACTIVE, g.phase)
        assertEquals(-1, g.selected)
    }

    @Test
    fun `moveTo sobre una celda selecciona la alternativa`() {
        val g = PopupGesture().also { it.start() }
        g.moveTo(2)
        assertEquals(PopupPhase.OPTION_SELECTED, g.phase)
        assertEquals(2, g.selected)
    }

    @Test
    fun `sacar el dedo del popup deselecciona sin cerrar`() {
        val g = PopupGesture().also { it.start() }
        g.moveTo(1)
        g.moveTo(-1)
        assertEquals(PopupPhase.LONG_PRESS_ACTIVE, g.phase)
        assertEquals(-1, g.selected)
    }

    @Test
    fun `commit devuelve la seleccion y limpia el estado`() {
        val g = PopupGesture().also { it.start() }
        g.moveTo(0)
        assertEquals(0, g.commit())
        assertEquals(PopupPhase.IDLE, g.phase)
        assertEquals(-1, g.selected)
    }

    @Test
    fun `commit sin seleccion devuelve -1`() {
        val g = PopupGesture().also { it.start() }
        assertEquals(-1, g.commit())
        assertEquals(PopupPhase.IDLE, g.phase)
    }

    @Test
    fun `cancel limpia todo sin committear`() {
        val g = PopupGesture().also { it.start() }
        g.moveTo(3)
        g.cancel()
        assertEquals(PopupPhase.IDLE, g.phase)
        assertEquals(-1, g.selected)
        assertEquals(-1, g.commit())
    }

    @Test
    fun `moveTo es no-op fuera de un gesto activo`() {
        val g = PopupGesture()
        g.moveTo(0)
        assertEquals(PopupPhase.IDLE, g.phase)
        assertEquals(-1, g.selected)

        g.start()
        g.cancel()
        g.moveTo(1)
        assertEquals(PopupPhase.IDLE, g.phase)
        assertEquals(-1, g.selected)
    }

    @Test
    fun `la seleccion puede moverse de una celda a otra`() {
        val g = PopupGesture().also { it.start() }
        g.moveTo(0)
        g.moveTo(1)
        g.moveTo(2)
        assertEquals(2, g.selected)
        assertNotEquals(0, g.selected)
    }
}

class PopupGeometryTest {

    @Test
    fun `dedo en la primera celda da indice 0`() {
        assertEquals(0, PopupGeometry.cellIndexAt(10f, popupLeft = 0f, popupWidth = 150f, cellWidth = 50f, count = 3))
    }

    @Test
    fun `dedo en la ultima celda da el ultimo indice`() {
        assertEquals(2, PopupGeometry.cellIndexAt(149f, popupLeft = 0f, popupWidth = 150f, cellWidth = 50f, count = 3))
    }

    @Test
    fun `dedo fuera por la izquierda devuelve -1`() {
        assertEquals(-1, PopupGeometry.cellIndexAt(-1f, popupLeft = 0f, popupWidth = 150f, cellWidth = 50f, count = 3))
    }

    @Test
    fun `dedo fuera por la derecha devuelve -1`() {
        assertEquals(-1, PopupGeometry.cellIndexAt(151f, popupLeft = 0f, popupWidth = 150f, cellWidth = 50f, count = 3))
    }
}
