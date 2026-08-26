package com.elladecide.lola

import com.elladecide.lola.keyboard.KeyboardMode
import com.elladecide.lola.keyboard.KeyboardSize
import com.elladecide.lola.language.PlaceholderWords
import com.elladecide.lola.language.PrefixSuggestionProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Transiciones de estado del modo de teclado (spec F3 §19): CHAT ↔ LOLA,
 * COMPACT ↔ EXPANDED, y la sincronización del editor al cambiar de modo (que
 * evita el texto "colgado" en composición al volver a Chat).
 */
class KeyboardControllerTest {

    private fun controller(
        onSyncEditor: () -> Unit = {},
    ): KeyboardController = KeyboardController(
        scope = CoroutineScope(Dispatchers.Unconfined),
        onCommitText = {},
        onCommitImage = {},
        onCommitImageFile = {},
        onDelete = {},
        onEnter = {},
        onMoveCursor = {},
        onCambiarAltura = {},
        onTypeText = {},
        onReplaceComposing = {},
        onUndo = {},
        onCurrentWord = { "" },
        onDeleteWord = {},
        onSyncEditor = onSyncEditor,
        suggestionProvider = PrefixSuggestionProvider(PlaceholderWords.LIST),
    )

    @Test
    fun `toggleModo alterna CHAT y LOLA sincronizando el editor`() {
        var synced = 0
        val c = controller(onSyncEditor = { synced++ })
        assertEquals(KeyboardMode.CHAT, c.mode)
        c.toggleModo()
        assertEquals(KeyboardMode.LOLA, c.mode)
        c.toggleModo()
        assertEquals(KeyboardMode.CHAT, c.mode)
        assertEquals(2, synced)
    }

    @Test
    fun `toggleModo vuelve a compact al salir de LOLA`() {
        val c = controller()
        c.toggleModo()
        c.toggleExpandir()
        assertEquals(KeyboardSize.EXPANDED, c.size)
        c.toggleModo()
        assertEquals(KeyboardSize.COMPACT, c.size)
    }

    @Test
    fun `toggleExpandir no hace nada fuera de LOLA`() {
        val c = controller()
        c.toggleExpandir()
        assertEquals(KeyboardSize.COMPACT, c.size)
    }

    @Test
    fun `alturaDp refleja el modo y el tamano`() {
        val c = controller()
        assertEquals(KeyboardController.CHAT_HEIGHT_DP, c.alturaDp())
        c.toggleModo()
        assertEquals(KeyboardController.LOLA_COMPACT_HEIGHT_DP, c.alturaDp())
        c.toggleExpandir()
        assertEquals(KeyboardController.EXPANDED_FILL_DP, c.alturaDp())
        c.toggleExpandir()
        assertEquals(KeyboardController.LOLA_COMPACT_HEIGHT_DP, c.alturaDp())
    }

    @Test
    fun `expandida deriva de modo y tamano sin estado duplicado`() {
        val c = controller()
        assertEquals(false, c.expandida)
        c.toggleModo()
        assertEquals(false, c.expandida) // compact
        c.toggleExpandir()
        assertEquals(true, c.expandida)
        c.toggleModo() // sale de LOLA
        assertEquals(false, c.expandida)
    }
}