package com.elladecide.lola.language

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class CorrectionCandidate(
    val word: String,
    val editDistance: Int,
    val totalCost: Double,
    val frequency: Int,
)

/** Parser del binario LOLA y corrector fuzzy puro. */
internal class LolaDictionary private constructor(
    private val tree: BkTree,
    private val originals: Map<String, List<String>>,
    private val frequencies: Map<String, Int>,
) {
    fun correct(word: String): List<CorrectionCandidate> {
        val normalized = SpanishNormalizer.normalize(word)
        if (normalized.isEmpty()) return emptyList()
        if (word in originals[normalized].orEmpty()) {
            return listOf(CorrectionCandidate(word, 0, 0.0, frequencies[word] ?: 0))
        }
        val maxDistance = if (normalized.length <= 4) 1 else 2
        return tree.search(normalized, maxDistance)
            .flatMap { found -> originals[found].orEmpty().map { it to found } }
            .distinctBy { it.first }
            .map { (original, found) ->
                val distance = EditDistance.between(normalized, found)
                CorrectionCandidate(original, distance, distance.toDouble(), frequencies[original] ?: 0)
            }
            .sortedWith(compareBy<CorrectionCandidate> { it.totalCost }.thenByDescending { it.frequency })
    }

    companion object {
        fun load(input: InputStream): LolaDictionary {
            val data = input.use { it.readBytes() }
            require(data.size >= HEADER_SIZE) { "dictionary.bin truncado" }
            require(data.copyOfRange(0, 4).contentEquals("LOLA".toByteArray())) {
                "Magic inválido en dictionary.bin"
            }
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val wordCount = buffer.getInt(8)
            val stringOffset = buffer.getInt(12)
            require(wordCount >= 0 && HEADER_SIZE + wordCount.toLong() * ENTRY_SIZE <= data.size) {
                "Entries inválidas en dictionary.bin"
            }
            require(stringOffset in HEADER_SIZE..data.size) { "String table inválida" }

            val words = ArrayList<String>(wordCount)
            var cursor = stringOffset
            repeat(wordCount) {
                val start = cursor
                while (cursor < data.size && data[cursor] != 0.toByte()) cursor++
                require(cursor < data.size) { "String table truncada en entrada $it" }
                words += data.copyOfRange(start, cursor).toString(Charsets.UTF_8)
                cursor++
            }

            val tree = BkTree()
            val originals = linkedMapOf<String, MutableList<String>>()
            val frequencies = mutableMapOf<String, Int>()
            words.forEachIndexed { index, word ->
                val normalized = SpanishNormalizer.normalize(word)
                val variants = originals.getOrPut(normalized) { mutableListOf() }
                if (word !in variants) variants += word
                val frequency = buffer.getInt(HEADER_SIZE + index * ENTRY_SIZE + 4)
                frequencies[word] = maxOf(frequencies[word] ?: 0, frequency)
            }
            originals.keys.forEach(tree::insert)
            return LolaDictionary(tree, originals, frequencies)
        }

        private const val HEADER_SIZE = 64
        private const val ENTRY_SIZE = 10
    }
}
