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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transiciones de estado del modo de teclado (spec F3 Â§19): CHAT â†” LOLA,
 * COMPACT â†” EXPANDED, y la sincronizaciÃ³n del editor al cambiar de modo (que
 * evita el texto "colgado" en composiciÃ³n al volver a Chat).
 */
class KeyboardControllerTest {

    private fun controller(
        onSyncEditor: () -> Unit = {},
        onDelete: () -> Unit = {},
        onCurrentWord: () -> String = { "" },
        onContextBefore: () -> String = { "" },
    ): KeyboardController = KeyboardController(
        scope = CoroutineScope(Dispatchers.Unconfined),
        onCommitText = {},
        onCommitImage = {},
        onCommitImageFile = {},
        onDelete = onDelete,
        onEnter = {},
        onMoveCursor = {},
        onCambiarAltura = {},
        onTypeText = {},
        onReplaceComposing = {},
        onUndo = {},
        onCurrentWord = onCurrentWord,
        onContextBefore = onContextBefore,
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
    fun `autorepeat borra sin releer contexto hasta soltar`() {
        var borrados = 0
        var lecturasDeContexto = 0
        val c = controller(
            onDelete = { borrados++ },
            onCurrentWord = { "hola" },
            onContextBefore = { lecturasDeContexto++; "" },
        )

        repeat(8) { c.borrarDuranteRepeticion() }
        assertEquals(8, borrados)
        assertEquals(0, lecturasDeContexto)

        c.finalizarBorradoRepetido()
        assertEquals(1, lecturasDeContexto)
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
        c.press(Key.SHIFT) // shift temporal (una mayÃºscula)
        assertEquals(true, c.keyEngine.shiftActive)
        c.comprometerAlternativa("Ã") // alternativa ya capitalizada por el motor
        assertEquals("Ã", escrito)
        assertEquals(false, c.keyEngine.shiftActive) // shift consumido (spec Â§12)
    }

    @Test
    fun `comprometerAlternativa no toca el bloqueo de mayusculas`() {
        val c = controller()
        c.fijarShift() // BLOQUEO
        assertEquals(true, c.keyEngine.shiftActive)
        c.comprometerAlternativa("Ã")
        assertEquals(true, c.keyEngine.shiftActive) // el bloqueo sigue activo
    }

    // â”€â”€ Descuento TODOS / MANUAL y cantidades (switches de buildLineParts) â”€â”€

    @Test
    fun `modo manual hereda el descuento global y al volver a todos lo limpia`() {
        val c = controller()
        c.addToCart(producto, 0)
        c.cambiarDescuentoModo(DescuentoModo.MANUAL)
        assertEquals(15, c.cart[0].descuento) // heredÃ³ el global (default 15)
        c.editarItem(0)
        c.seleccionarDescuentoPendiente(20)
        c.aplicarItemDescuento()
        assertEquals(20, c.cart[0].descuento) // override individual aplicado
        c.cambiarDescuentoModo(DescuentoModo.TODOS)
        assertEquals(null, c.cart[0].descuento) // limpio: vuelve a mandar el global
    }

    @Test
    fun `descuento manual del item se refleja en el total`() {
        val c = controller()
        c.addToCart(producto, 0)
        c.cambiarDescuentoModo(DescuentoModo.MANUAL)
        c.editarItem(0)
        c.seleccionarDescuentoPendiente(20)
        c.aplicarItemDescuento()
        // base 1000 con 20% â†’ 800 (y no el 15% global â†’ 850)
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
        assertEquals(1, c.cart[0].qty) // reset a 1 (como la extensiÃ³n)
    }

    private val producto: Product =
        Product.fromPrecios("TEST", "TEST 100", listOf(1000, 2000, 3000, 4000))

    // ── Lista fijada (fijarLista) y clamp de addToCart ───────────────────

    @Test
    fun `fijarLista coerce fuera de rango en menos uno hasta la ultima lista`() {
        val c = controller()
        c.fijarLista(99)
        assertEquals(3, c.defaultListaIndex)
        c.fijarLista(-5)
        assertEquals(-1, c.defaultListaIndex)
    }

    @Test
    fun `fijarLista limpia expandedCodigo`() {
        val c = controller()
        c.toggleExpanded(producto.codigoUnico)
        assertEquals(producto.codigoUnico, c.expandedCodigo)
        c.fijarLista(0)
        assertEquals(null, c.expandedCodigo)
    }

    @Test
    fun `fijarLista con lista migra todo el carrito`() {
        val c = controller()
        c.addToCart(producto, 0)
        c.addToCart(producto, 1)
        c.fijarLista(2)
        assertTrue(c.cart.all { it.listaIndex == 2 })
    }

    @Test
    fun `fijarLista con todas deja el carrito como estaba`() {
        val c = controller()
        c.addToCart(producto, 0)
        c.addToCart(producto, 3)
        c.fijarLista(-1)
        assertEquals(listOf(0, 3), c.cart.map { it.listaIndex })
    }

    @Test
    fun `addToCart clampa listaIndex a la ultima lista del producto`() {
        val c = controller()
        c.addToCart(producto, 99)
        assertEquals(3, c.cart[0].listaIndex)
    }

    @Test
    fun `fijarLista con lista fija clampea por producto con menos precios`() {
        val c = controller()
        val p2 = Product.fromPrecios("TEST2", "TEST2 X", listOf(1000, 2000))
        c.addToCart(p2, 0)
        c.fijarLista(3)
        assertEquals(1, c.cart[0].listaIndex) // clampeado a preciosList().lastIndex
    }
}
