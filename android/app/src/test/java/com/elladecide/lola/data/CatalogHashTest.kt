package com.elladecide.lola.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Paridad del port Android con el hash canónico del backend (hash.ts).
 * El golden vector es generado por el backend y fijado en src/hash.test.ts.
 */
class CatalogHashTest {

    private fun p(codigo: String, nombre: String, vararg precios: Int) =
        Product.fromPrecios(codigo, nombre, precios.toList())

    private val fixture = listOf(
        p("B-2", "Leche Entera 1L", 123, 140, 160, 180),
        p("a-10", "Aceite Girasol 900cc", 900, 1000, 1100, 1200),
        p("A-2", "Caja \"Fiesta\" 12un", 456, 500, 550, 600),
        p("104.3", "Archivo\\Prueba\nSegunda", 70, 80, 90, 100),
        p("452-1", "Té\tFrutilla", 50, 55, 60, 65),
        p("C-3", "Colisión intencional", 1, 2, 3, 4),
    )

    @Test
    fun `paridad con backend hash ts - golden`() {
        assertEquals(
            "d6a08778c06410ab22d08b11db7451b92e391d06978a89254827399afa8405ef",
            CatalogHash.compute(fixture),
        )
    }

    @Test
    fun `independiente del orden de entrada`() {
        val a = CatalogHash.compute(fixture)
        val b = CatalogHash.compute(fixture.asReversed())
        assertEquals(a, b)
    }

    @Test
    fun `un solo producto genera hash de 64 hex`() {
        val h = CatalogHash.compute(listOf(p("X-1", "Único", 100, 110, 120, 130)))
        assertEquals(64, h.length)
    }
}