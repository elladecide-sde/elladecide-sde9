package com.elladecide.lola.search

import com.elladecide.lola.data.Product
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchEngineTest {

    private fun p(codigo: String, nombre: String) =
        Product.fromPrecios(codigo, nombre, listOf(100, 110, 120, 130))

    private val cat = listOf(
        p("A-1", "Leche Entera 1L"),
        p("A-2", "Leche Descremada 1L"),
        p("B-1", "Aceite Girasol 900cc"),
    )

    private val idx = SearchEngine.index(cat)

    @Test
    fun `busqueda vacia devuelve todo el catalogo`() {
        assertEquals(
            cat.map { it.codigoUnico },
            SearchEngine.searchIndexed(idx, "").map { it.codigoUnico },
        )
    }

    @Test
    fun `AND por tokens en cualquier orden`() {
        val a = SearchEngine.searchIndexed(idx, "leche 1l").map { it.codigoUnico }
        val b = SearchEngine.searchIndexed(idx, "1l leche").map { it.codigoUnico }
        assertEquals(a, b)
        assertEquals(listOf("A-1", "A-2"), a)
    }

    @Test
    fun `sin match devuelve vacio`() {
        assertEquals(emptyList<Product>(), SearchEngine.searchIndexed(idx, "xyzzy"))
    }

    @Test
    fun `paridad entre la API original y el indice`() {
        for (q in listOf("", "leche", "aceite 900", "leche 1l", "zz", "LEChE")) {
            assertEquals(
                SearchEngine.search(cat, q).map { it.codigoUnico },
                SearchEngine.searchIndexed(idx, q).map { it.codigoUnico },
            )
        }
    }
}