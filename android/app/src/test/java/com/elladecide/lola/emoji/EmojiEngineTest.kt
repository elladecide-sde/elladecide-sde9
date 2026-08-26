package com.elladecide.lola.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiEngineTest {

    @Test
    fun `categoria por defecto es smileys`() {
        assertEquals(EmojiCategory.SMILEYS, EmojiEngine().category)
    }

    @Test
    fun `seleccionar categoria cambia los emojis`() {
        val e = EmojiEngine()
        e.selectCategory(EmojiCategory.FOOD)
        assertEquals(EmojiData.byCategory(EmojiCategory.FOOD), e.emojis())
    }

    @Test
    fun `recordRecent mueve al frente sin duplicados`() {
        val e = EmojiEngine()
        e.recordRecent("😀")
        e.recordRecent("🔥")
        e.recordRecent("😀")
        assertEquals(listOf("😀", "🔥"), e.recentsList())
    }

    @Test
    fun `recordRecent respeta el maximo`() {
        val e = EmojiEngine()
        repeat(30) { e.recordRecent("e$it") }
        assertEquals(EmojiEngine.MAX_RECENTS, e.recentsList().size)
    }

    @Test
    fun `recientes vacios muestran lista vacia`() {
        val e = EmojiEngine()
        e.selectCategory(EmojiCategory.RECENT)
        assertEquals(emptyList<Emoji>(), e.emojis())
    }

    @Test
    fun `recientes se resuelven a emojis del catalogo`() {
        val e = EmojiEngine()
        e.restoreRecents(listOf("😀", "🔥"))
        e.selectCategory(EmojiCategory.RECENT)
        assertEquals(listOf("😀", "🔥"), e.emojis().map { it.glyph })
    }

    @Test
    fun `el catalogo curado tiene emojis en todas las categorias`() {
        EmojiCategory.entries.filter { it != EmojiCategory.RECENT }.forEach { cat ->
            assertEquals(false, EmojiData.byCategory(cat).isEmpty())
        }
    }

    @Test
    fun `buscar filtra por nombre en todas las categorias`() {
        val e = EmojiEngine()
        e.selectCategory(EmojiCategory.FOOD)
        e.buscar("corazón")
        val glyphs = e.emojis().map { it.glyph }
        assertTrue(glyphs.contains("❤️"))
        // La búsqueda cruza la categoría actual.
        assertTrue(glyphs.none { it == "🍎" })
    }

    @Test
    fun `limpiar busqueda restaura la categoria`() {
        val e = EmojiEngine()
        e.selectCategory(EmojiCategory.FOOD)
        e.buscar("corazón")
        e.limpiarBusqueda()
        assertEquals(EmojiData.byCategory(EmojiCategory.FOOD), e.emojis())
    }

    @Test
    fun `busqueda vacia no altera la categoria`() {
        val e = EmojiEngine()
        e.selectCategory(EmojiCategory.SMILEYS)
        e.buscar("")
        assertEquals(EmojiData.byCategory(EmojiCategory.SMILEYS), e.emojis())
    }
}
