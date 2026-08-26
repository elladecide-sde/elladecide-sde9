package com.elladecide.lola

import com.elladecide.lola.data.Product
import com.elladecide.lola.keyboard.Key
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

    @Test
    fun `comprometerAlternativa consume el shift temporal y commitea`() {
        var escrito = ""
        val c = KeyboardController(
            scope = CoroutineScope(Dispatchers.Unconfined),
            onCommitText = {},
            onCommitImage = {},
            onCommitImageFile = {},
            onDelete = {},
            onEnter = {},
            onMoveCursor = {},
            onCambiarAltura = {},
            onTypeText = { escrito += it },
            onReplaceComposing = {},
            onUndo = {},
            onCurrentWord = { "" },
            onDeleteWord = {},
            onSyncEditor = {},
            suggestionProvider = PrefixSuggestionProvider(PlaceholderWords.LIST),
        )
        c.press(Key.SHIFT) // shift temporal (una mayúscula)
        assertEquals(true, c.keyEngine.shiftActive)
        c.comprometerAlternativa("Á") // alternativa ya capitalizada por el motor
        assertEquals("Á", escrito)
        assertEquals(false, c.keyEngine.shiftActive) // shift consumido (spec §12)
    }

    @Test
    fun `comprometerAlternativa no toca el bloqueo de mayusculas`() {
        val c = controller()
        c.fijarShift() // BLOQUEO
        assertEquals(true, c.keyEngine.shiftActive)
        c.comprometerAlternativa("Á")
        assertEquals(true, c.keyEngine.shiftActive) // el bloqueo sigue activo
    }

    // ── Descuento TODOS / MANUAL y cantidades (switches de buildLineParts) ──

    @Test
    fun `modo manual hereda el descuento global y al volver a todos lo limpia`() {
        val c = controller()
        c.addToCart(producto, 0)
        c.setDescuentoModo(DescuentoModo.MANUAL)
        assertEquals(15, c.cart[0].descuento) // heredó el global (default 15)
        c.editarItem(0)
        c.setPendingDescuento(20)
        c.aplicarItemDescuento()
        assertEquals(20, c.cart[0].descuento) // override individual aplicado
        c.setDescuentoModo(DescuentoModo.TODOS)
        assertEquals(null, c.cart[0].descuento) // limpio: vuelve a mandar el global
    }

    @Test
    fun `descuento manual del item se refleja en el total`() {
        val c = controller()
        c.addToCart(producto, 0)
        c.setDescuentoModo(DescuentoModo.MANUAL)
        c.editarItem(0)
        c.setPendingDescuento(20)
        c.aplicarItemDescuento()
        // base 1000 con 20% → 800 (y no el 15% global → 850)
        assertEquals(800, c.totalEfectivo)
    }

    @Test
    fun `pasar de concepto U a N resetea las cantidades`() {
        val c = controller()
        c.addToCart(producto, 0)
        c.setConcepto(Concepto.U)
        c.cambiarCantidad(0, 3) // 1 + 3 = 4
        assertEquals(4, c.cart[0].qty)
        c.setConcepto(Concepto.N)
        assertEquals(1, c.cart[0].qty) // reset a 1 (como la extensión)
    }

    private val producto: Product =
        Product.fromPrecios("TEST", "TEST 100", listOf(1000, 2000, 3000, 4000))
}