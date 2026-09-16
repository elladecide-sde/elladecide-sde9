package com.elladecide.lola

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elladecide.lola.ui.KeyboardScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * Smoke de instrumentación del shell Compose (F3).
 *
 * Cubre el "benchmark/recomposition smoke" y la "accessibility smoke" del DoD
 * de F3: (1) las teclas exponen semantics para TalkBack, (2) un tap ejecuta el
 * efecto correcto, y (3) tipear en modo expandido no dispara una recomposición
 * descontrolada del árbol.
 */
@RunWith(AndroidJUnit4::class)
class KeyboardInstrumentationTest {

    @get:Rule
    val rule = createComposeRule()

    private fun controller(onType: (String) -> Unit = {}): KeyboardController =
        KeyboardController(
            scope = CoroutineScope(Dispatchers.Main),
            onCommitText = {},
            onCommitImage = {},
            onCommitImageFile = {},
            onDelete = {},
            onEnter = {},
            onMoveCursor = {},
            onTypeText = onType,
            onReplaceComposing = {},
            onUndo = {},
            onCurrentWord = { "" },
            onDeleteWord = {},
            onSyncEditor = {},
            suggestionProvider = com.elladecide.lola.language.PrefixSuggestionProvider(
                com.elladecide.lola.language.PlaceholderWords.LIST,
            ),
        )

    @Test
    fun keysExposeSemantics() {
        rule.setContent { KeyboardScreen(controller()) }
        rule.onNodeWithContentDescription("a").assertExists()
        rule.onNodeWithContentDescription("Retroceso").assertExists()
        rule.onNodeWithContentDescription("Espacio").assertExists()
    }

    @Test
    fun tappingAKeyFeedsComposing() {
        // F4: una letra en modo contraído acumula en composing (para sugerir),
        // no se commitea directo. El tap debe llegar a onTypeText.
        var typed = ""
        rule.setContent { KeyboardScreen(controller { typed += it }) }
        rule.onNodeWithContentDescription("a").performClick()
        assertEquals("a", typed)
    }

    @Test
    fun typingInExpandedModeRecomposesBoundedly() {
        val c = controller()
        c.toggleExpandir() // modo LOLA: tipear alimenta la búsqueda (query/results)

        val recompositions = AtomicInteger(0)
        rule.setContent {
            // La raíz lee el mismo estado que cambia al tipear, así participa de
            // la recomposición de la búsqueda y podemos contarla.
            @Suppress("UNUSED_EXPRESSION")
            c.query
            SideEffect { recompositions.incrementAndGet() }
            KeyboardScreen(c)
        }
        rule.waitForIdle()
        val baseline = recompositions.get()

        rule.runOnUiThread { c.tecla("a") }
        rule.waitForIdle()
        rule.runOnUiThread { c.tecla("b") }
        rule.waitForIdle()

        val delta = recompositions.get() - baseline
        assertTrue(
            "dos teclas deberían recomponer de forma acotada, delta=$delta",
            delta <= 2,
        )
    }
}
