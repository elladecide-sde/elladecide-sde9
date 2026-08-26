package com.elladecide.lola.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Estado observable del popup y su cierre por Back (spec F3 §8B): el token de
 * cancelación evita commits fantasma del gesto en curso.
 */
class PopupControllerTest {

    private fun info(seleccion: Int = -1) = PopupInfo(
        alternativas = listOf("á", "à", "â"),
        leftPx = 10,
        topPx = 20,
        cellPx = 48,
        heightPx = 56,
        seleccion = seleccion,
    )

    @Test
    fun `mostrar abre el popup`() {
        val c = PopupController()
        assertFalse(c.visible)
        assertNull(c.info)
        c.mostrar(info())
        assertTrue(c.visible)
        assertNotNull(c.info)
        assertEquals(0, c.cancelToken)
    }

    @Test
    fun `seleccionar actualiza la alternativa resaltada`() {
        val c = PopupController().also { it.mostrar(info()) }
        c.seleccionar(2)
        assertEquals(2, c.info!!.seleccion)
    }

    @Test
    fun `cerrar limpia el popup`() {
        val c = PopupController().also { it.mostrar(info()) }
        c.cerrar()
        assertFalse(c.visible)
        assertNull(c.info)
    }

    @Test
    fun `back con popup abierto lo cierra e incrementa el token`() {
        val c = PopupController().also { it.mostrar(info()) }
        val consumido = c.cancelarExterno()
        assertTrue(consumido)
        assertNull(c.info)
        assertEquals(1, c.cancelToken)
    }

    @Test
    fun `back sin popup no consume ni toca el token`() {
        val c = PopupController()
        val consumido = c.cancelarExterno()
        assertFalse(consumido)
        assertEquals(0, c.cancelToken)
    }
}
