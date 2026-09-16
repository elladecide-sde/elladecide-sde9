package com.elladecide.lola.language

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class LolaDictionaryTest {
    @Test
    fun `parsea binario y prioriza frecuencia en empate`() {
        val dictionary = LolaDictionary.load(binary(listOf("ruedo", "puedo", "año", "ano"), listOf(1, 2413, 9, 8)))
        assertEquals("puedo", dictionary.correct("ouedo").first().word)
        assertEquals("año", dictionary.correct("año").single().word)
        assertEquals("ano", dictionary.correct("ano").single().word)
    }

    private fun binary(words: List<String>, frequencies: List<Int>): ByteArrayInputStream {
        val strings = words.flatMap { it.toByteArray(Charsets.UTF_8).toList() + 0.toByte() }.toByteArray()
        val stringOffset = 64 + words.size * 10
        val buffer = ByteBuffer.allocate(stringOffset + strings.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("LOLA".toByteArray())
        buffer.position(8)
        buffer.putInt(words.size)
        buffer.putInt(stringOffset)
        buffer.position(64)
        words.indices.forEach { index ->
            buffer.putInt(0)
            buffer.putInt(frequencies[index])
            buffer.putShort(0)
        }
        buffer.position(stringOffset)
        buffer.put(strings)
        return ByteArrayInputStream(buffer.array())
    }
}
