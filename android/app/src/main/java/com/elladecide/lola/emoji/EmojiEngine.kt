package com.elladecide.lola.emoji

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Estado del panel de emojis (categoría seleccionada + recientes). Puro en su
 * lógica (solo depende de [EmojiData]); el estado es snapshot-state para que
 * Compose recomponga al cambiar de categoría o al registrar un reciente.
 */
class EmojiEngine {

    var category by mutableStateOf(EmojiCategory.SMILEYS)
        private set

    private var recents by mutableStateOf<List<String>>(emptyList())

    /** Categorías visibles (RECENT primero, siempre). */
    fun categories(): List<EmojiCategory> = EmojiCategory.entries

    fun selectCategory(c: EmojiCategory) {
        category = c
    }

    /** Emojis de la categoría actual (los recientes resueltos a [Emoji]). */
    fun emojis(): List<Emoji> = when (category) {
        EmojiCategory.RECENT -> recents.mapNotNull(EmojiData::find)
        else -> EmojiData.byCategory(category)
    }

    /** Registra un glifo como reciente (más reciente primero, sin duplicados). */
    fun recordRecent(glyph: String) {
        recents = (listOf(glyph) + recents.filter { it != glyph }).take(MAX_RECENTS)
    }

    /** Inicializa los recientes persistidos (más reciente primero). */
    fun restoreRecents(list: List<String>) {
        recents = list.distinct().take(MAX_RECENTS)
    }

    /** Recientes en orden (para persistir). */
    fun recentsList(): List<String> = recents

    companion object {
        const val MAX_RECENTS = 24
    }
}
