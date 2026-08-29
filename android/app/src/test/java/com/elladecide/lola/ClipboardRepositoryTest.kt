package com.elladecide.lola

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardRepositoryTest {

    private val NOW = 1_000_000L

    private fun item(text: String, ageMs: Long = 0L) =
        ClipboardRepository.ClipItem(text, NOW - ageMs)

    @Test
    fun `add inserta al frente y deduplica`() {
        val r = ClipboardRepository.add(listOf(item("a"), item("b")), "a", NOW)
        assertEquals(listOf("a", "b"), r.map { it.text })
        assertEquals(2, r.size)
    }

    @Test
    fun `add descarta texto vacio`() {
        assertEquals(
            emptyList<ClipboardRepository.ClipItem>(),
            ClipboardRepository.add(emptyList(), "   ", NOW),
        )
    }

    @Test
    fun `add respeta el maximo y saca el mas viejo`() {
        val items = (1..ClipboardRepository.MAX_ITEMS).map { item("t$it") }
        val r = ClipboardRepository.add(items, "nuevo", NOW)
        assertEquals(ClipboardRepository.MAX_ITEMS, r.size)
        assertEquals("nuevo", r.first().text)
        assertTrue(r.none { it.text == "t${ClipboardRepository.MAX_ITEMS}" })
    }

    @Test
    fun `purge quita lo vencido`() {
        val items = listOf(item("viejo", ClipboardRepository.TTL_MS + 1), item("nuevo", 1000L))
        assertEquals(listOf("nuevo"), ClipboardRepository.purge(items, NOW).map { it.text })
    }

    @Test
    fun `remove borra un item puntual`() {
        val items = listOf(item("a"), item("b"), item("c"))
        assertEquals(listOf("a", "c"), ClipboardRepository.remove(items, "b").map { it.text })
    }
}