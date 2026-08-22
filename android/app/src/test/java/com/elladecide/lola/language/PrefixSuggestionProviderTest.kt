package com.elladecide.lola.language

import org.junit.Assert.assertEquals
import org.junit.Test

class PrefixSuggestionProviderTest {

    private val words = listOf("hola", "holanda", "hombre", "casa")
    private val provider = PrefixSuggestionProvider(words)

    @Test
    fun `completa prefijo y ordena por longitud`() {
        assertEquals(listOf("hola", "hombre", "holanda"), provider.suggest("ho", "", 3))
    }

    @Test
    fun `excluye la coincidencia exacta`() {
        assertEquals(listOf("holanda"), provider.suggest("hola", "", 3))
    }

    @Test
    fun `prefijo vacio no sugiere`() {
        assertEquals(emptyList<String>(), provider.suggest("", "", 3))
    }

    @Test
    fun `respeta el limite`() {
        assertEquals(listOf("casa"), provider.suggest("ca", "", 1))
    }

    @Test
    fun `ignora mayusculas en el prefijo`() {
        assertEquals(listOf("hola", "hombre", "holanda"), provider.suggest("HO", "", 3))
    }
}
