package com.elladecide.lola.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Búsqueda por nombre y variantes de tono de piel del catálogo curado. */
class EmojiDataTest {

    // ── Búsqueda ───────────────────────────────────────────────────────────

    @Test
    fun `busqueda vacia devuelve lista vacia`() {
        assertEquals(emptyList<Emoji>(), EmojiData.search(""))
        assertEquals(emptyList<Emoji>(), EmojiData.search("   "))
    }

    @Test
    fun `busca por nombre sin distinguir acentos ni mayusculas`() {
        val porAcento = EmojiData.search("corazón")
        val porNormal = EmojiData.search("corazon")
        assertEquals(porAcento.map { it.glyph }, porNormal.map { it.glyph })
        assertTrue(porAcento.any { it.glyph == "❤️" || it.glyph == "💔" })
    }

    @Test
    fun `busca con multiples tokens en cualquier orden`() {
        val a = EmojiData.search("roto corazon").map { it.glyph }
        val b = EmojiData.search("corazon roto").map { it.glyph }
        assertEquals(a, b)
        assertTrue(a.contains("💔"))
    }

    @Test
    fun `busqueda sin resultados devuelve vacio`() {
        assertEquals(emptyList<Emoji>(), EmojiData.search("xyzzy"))
    }

    @Test
    fun `la busqueda cruza todas las categorias`() {
        val mano = EmojiData.search("pulgar").map { it.glyph }
        assertTrue(mano.contains("👍"))
        val cafe = EmojiData.search("café").map { it.glyph }
        assertTrue(cafe.contains("☕"))
    }

    // ── Tonos de piel ──────────────────────────────────────────────────────

    @Test
    fun `emoji con tono de piel devuelve base mas 5 tonos`() {
        val v = EmojiData.skinToneVariants("👍")
        assertEquals(6, v.size)
        assertEquals("👍", v[0])
        assertEquals("👍🏻", v[1])
        assertEquals("👍🏿", v[5])
    }

    @Test
    fun `emoji sin tono de piel devuelve vacio`() {
        assertEquals(emptyList<String>(), EmojiData.skinToneVariants("😀"))
    }

    @Test
    fun `glifo desconocido devuelve vacio`() {
        assertEquals(emptyList<String>(), EmojiData.skinToneVariants("XX"))
    }

    @Test
    fun `resolve conserva el glifo base de una variante con tono`() {
        assertEquals("👍", EmojiData.resolve("👍🏽")?.glyph)
    }

    @Test
    fun `resolve devuelve el emoji exacto si existe`() {
        assertEquals("😀", EmojiData.resolve("😀")?.glyph)
    }

    @Test
    fun `todos los emojis soportan tonos estan marcados`() {
        // Cualquier emoji marcado skinTone=true debe producir variantes.
        EmojiData.all().filter { it.skinTone }.forEach { e ->
            assertTrue(EmojiData.skinToneVariants(e.glyph).size == 6)
        }
    }
}