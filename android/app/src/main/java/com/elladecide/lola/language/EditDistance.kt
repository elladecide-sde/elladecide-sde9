package com.elladecide.lola.language

/** Levenshtein clásico, idéntico al usado para entrenar el modelo Python. */
internal object EditDistance {
    fun between(left: String, right: String): Int {
        var longer = left
        var shorter = right
        if (longer.length < shorter.length) {
            longer = right
            shorter = left
        }
        if (shorter.isEmpty()) return longer.length

        var previous = IntArray(shorter.length + 1) { it }
        longer.forEachIndexed { i, first ->
            val current = IntArray(shorter.length + 1)
            current[0] = i + 1
            shorter.forEachIndexed { j, second ->
                current[j + 1] = minOf(
                    previous[j + 1] + 1,
                    current[j] + 1,
                    previous[j] + if (first == second) 0 else 1,
                )
            }
            previous = current
        }
        return previous[shorter.length]
    }
}
