package com.elladecide.lola.language

/**
 * Implementación mínima y determinista de [SuggestionProvider]: completa el
 * prefijo contra una lista de palabras (las más cortas primero). Sirve como
 * PLACEHOLDER mientras llega la neurona binaria; no hace predicción sensible
 * al contexto ni aprendizaje.
 */
class PrefixSuggestionProvider(private val words: List<String>) : SuggestionProvider {

    override fun suggest(prefix: String, contextBefore: String, limit: Int): List<String> {
        if (prefix.isBlank()) return emptyList()
        return words.asSequence()
            .filter { it.length != prefix.length && it.startsWith(prefix, ignoreCase = true) }
            .sortedBy { it.length }
            .take(limit)
            .toList()
    }
}
