package com.elladecide.lola.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SpanishNormalizerTest {
    @Test
    fun `quita tildes y conserva enie`() {
        assertEquals("arbol", SpanishNormalizer.normalize("ÁRBOL"))
        assertEquals("pinguino", SpanishNormalizer.normalize("pingüino"))
        assertEquals("año", SpanishNormalizer.normalize("AÑO"))
        assertNotEquals(SpanishNormalizer.normalize("año"), SpanishNormalizer.normalize("ano"))
    }

    @Test
    fun `distancia replica contrato del modelo sin transposicion especial`() {
        assertEquals(1, EditDistance.between("casa", "cosa"))
        assertEquals(2, EditDistance.between("ca", "ac"))
    }
}
