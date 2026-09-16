package com.elladecide.lola.language

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln1p

/** Corrector offline: BK-tree + re-ranker TFLite sensible al contexto. */
class NeuralSuggestionProvider(context: Context) : SuggestionProvider, AutoCloseable {
    private val assets = context.applicationContext.assets
    private val dictionary by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LolaDictionary.load(assets.open(DICTIONARY_ASSET))
    }
    private val vocabulary by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        assets.open(VOCABULARY_ASSET).bufferedReader().useLines { lines ->
            lines.take(VOCAB_TOP).mapIndexed { index, word -> word.trim() to index + 1 }.toMap()
        }
    }
    private val interpreterDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Interpreter(loadModel(), Interpreter.Options().apply { setNumThreads(2) })
    }
    private val interpreter by interpreterDelegate

    override fun suggest(prefix: String, contextBefore: String, limit: Int): List<String> {
        if (prefix.isBlank() || limit <= 0) return emptyList()
        val candidates = dictionary.correct(prefix)
        if (candidates.isEmpty()) return emptyList()
        val contextWords = WORD_REGEX.findAll(contextBefore.lowercase()).map { it.value }.toList().takeLast(CTX_LEN)
        val ranked = if (contextWords.isEmpty()) candidates else rerank(prefix, candidates, contextWords)
        return ranked.asSequence().map { preserveCase(prefix, it.word) }.take(limit).toList()
    }

    private fun rerank(
        query: String,
        candidates: List<CorrectionCandidate>,
        contextWords: List<String>,
    ): List<CorrectionCandidate> {
        val best = candidates.minOf { it.totalCost }
        val tier = candidates.filter { it.totalCost <= best + TIER_EPSILON }
        val rest = candidates.filter { it.totalCost > best + TIER_EPSILON }.sortedBy { it.totalCost }
        val maxLogFrequency = tier.maxOfOrNull { ln1p(it.frequency.coerceAtLeast(0).toDouble()) }
            ?.takeIf { it > 0.0 } ?: 1.0
        val contextIds = contextWords.map { vocabulary[it] ?: OOV_ID }
        return tier.sortedWith(
            compareByDescending<CorrectionCandidate> { candidate ->
                val features = typoFeatures(query, candidate.word)
                val candidateId = vocabulary[candidate.word] ?: OOV_ID
                val neural = if (candidateId == OOV_ID) 0.0 else BOOST * score(contextIds, candidateId, features)
                val typo = 1.0 - features[0]
                val frequency = ln1p(candidate.frequency.coerceAtLeast(0).toDouble()) / maxLogFrequency
                W_NEURAL * neural + W_TYPO * typo + W_FREQUENCY * frequency
            }.thenBy { it.totalCost },
        ) + rest
    }

    private fun typoFeatures(typo: String, candidate: String): FloatArray {
        val maxLength = maxOf(typo.length, candidate.length, 1)
        val typoCounts = typo.lowercase().groupingBy { it }.eachCount()
        val candidateCounts = candidate.lowercase().groupingBy { it }.eachCount()
        val shared = (typoCounts.keys + candidateCounts.keys).sumOf { char ->
            minOf(typoCounts[char] ?: 0, candidateCounts[char] ?: 0)
        }
        return floatArrayOf(
            EditDistance.between(typo, candidate).toFloat() / maxLength,
            shared.toFloat() / maxLength,
            if (typo.firstOrNull()?.lowercaseChar() == candidate.firstOrNull()?.lowercaseChar()) 1f else 0f,
            kotlin.math.abs(typo.length - candidate.length).toFloat() / maxLength,
        )
    }

    @Synchronized
    private fun score(contextIds: List<Int>, candidateId: Int, features: FloatArray): Double {
        val paddedContext = IntArray(CTX_LEN)
        contextIds.takeLast(CTX_LEN).forEachIndexed { index, id ->
            paddedContext[CTX_LEN - minOf(contextIds.size, CTX_LEN) + index] = id
        }
        val inputs = arrayOfNulls<Any>(interpreter.inputTensorCount)
        repeat(interpreter.inputTensorCount) { index ->
            when {
                "ctx" in interpreter.getInputTensor(index).name() -> inputs[index] = arrayOf(paddedContext)
                "cand" in interpreter.getInputTensor(index).name() -> inputs[index] = arrayOf(intArrayOf(candidateId))
                "feats" in interpreter.getInputTensor(index).name() -> inputs[index] = arrayOf(features)
                else -> error("Input TFLite desconocido: ${interpreter.getInputTensor(index).name()}")
            }
        }
        val output = Array(1) { FloatArray(1) }
        interpreter.runForMultipleInputsOutputs(inputs.requireNoNulls(), mutableMapOf<Int, Any>(0 to output))
        return output[0][0].toDouble()
    }

    private fun loadModel(): ByteBuffer {
        val bytes = assets.open(MODEL_ASSET).use { it.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
            put(bytes)
            rewind()
        }
    }

    override fun close() {
        if (interpreterDelegate.isInitialized()) interpreter.close()
    }

    private fun preserveCase(input: String, candidate: String): String =
        if (input.firstOrNull()?.isUpperCase() == true) candidate.replaceFirstChar { it.uppercase() } else candidate

    private companion object {
        const val DICTIONARY_ASSET = "language/dictionary.bin"
        const val MODEL_ASSET = "language/neural_ranker.tflite"
        const val VOCABULARY_ASSET = "language/palabras_comunes.txt"
        const val VOCAB_TOP = 20_000
        const val OOV_ID = 20_001
        const val CTX_LEN = 5
        const val TIER_EPSILON = 0.15
        const val BOOST = 0.6
        const val W_NEURAL = 0.45
        const val W_TYPO = 0.30
        const val W_FREQUENCY = 0.25
        val WORD_REGEX = Regex("[a-záéíóúüñ]+", RegexOption.IGNORE_CASE)
    }
}
