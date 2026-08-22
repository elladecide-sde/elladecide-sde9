package com.elladecide.lola.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphemeTest {

    @Test
    fun `ascii un caracter`() {
        assertEquals(1, Grapheme.lastClusterLength("a"))
        assertEquals(1, Grapheme.lastClusterLength("hola"))
    }

    @Test
    fun `acento precompuesto es un cluster`() {
        assertEquals(1, Grapheme.lastClusterLength("é"))
    }

    @Test
    fun `marca combinante es un solo cluster de dos code units`() {
        assertEquals(2, Grapheme.lastClusterLength("e\u0301"))
    }

    @Test
    fun `surrogate pair no se parte`() {
        assertTrue(Grapheme.lastClusterLength("😀") >= 2)
    }

    @Test
    fun `removeLastCluster quita el cluster completo`() {
        assertEquals("hol", Grapheme.removeLastCluster("hola"))
        assertEquals("", Grapheme.removeLastCluster("é"))
        assertEquals("", Grapheme.removeLastCluster("😀"))
        assertEquals("", Grapheme.removeLastCluster("e\u0301"))
    }

    @Test
    fun `firstClusterLength mide el primer cluster`() {
        assertEquals(1, Grapheme.firstClusterLength("abc"))
        assertEquals(2, Grapheme.firstClusterLength("😀x"))
        assertEquals(2, Grapheme.firstClusterLength("e\u0301x"))
        assertEquals(0, Grapheme.firstClusterLength(""))
    }

    @Test
    fun `cadena vacia`() {
        assertEquals(0, Grapheme.lastClusterLength(""))
        assertEquals("", Grapheme.removeLastCluster(""))
    }

    @Test
    fun `wordLengthBackwards borra la palabra`() {
        assertEquals(4, Grapheme.wordLengthBackwards("hola"))
        assertEquals(5, Grapheme.wordLengthBackwards("hola mundo"))
        assertEquals(6, Grapheme.wordLengthBackwards("hola mundo "))
    }

    @Test
    fun `wordLengthBackwards salta espacio y borra la palabra previa`() {
        assertEquals(5, Grapheme.wordLengthBackwards("hola "))
    }

    @Test
    fun `wordLengthBackwards puntuacion borra un cluster`() {
        assertEquals(1, Grapheme.wordLengthBackwards("hola."))
    }

    @Test
    fun `wordLengthBackwards emoji borra el grapheme completo`() {
        assertEquals(2, Grapheme.wordLengthBackwards("hola 😀"))
        assertEquals(3, Grapheme.wordLengthBackwards("hola 😀 "))
    }

    @Test
    fun `wordLengthBackwards vacio`() {
        assertEquals(0, Grapheme.wordLengthBackwards(""))
    }
}
