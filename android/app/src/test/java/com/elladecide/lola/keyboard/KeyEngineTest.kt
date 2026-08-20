package com.elladecide.lola.keyboard

import com.elladecide.lola.editor.EditorAction
import com.elladecide.lola.editor.EditorProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyEngineTest {

    @Test
    fun `layout de letras tiene las 27 letras sin duplicados`() {
        val chars = Layouts.LETTERS.flatten().filter { it.type == KeyType.CHAR }.map { it.char!! }
        assertEquals(27, chars.size)
        assertEquals(27, chars.toSet().size)
        "qwertyuiopasdfghjklñzxcvbnm".forEach { assertTrue(it.toString() in chars) }
    }

    @Test
    fun `layout de simbolos cubre los 10 digitos`() {
        val chars = Layouts.SYMBOLS.flatten().filter { it.type == KeyType.CHAR }.map { it.char!! }
        "0123456789".forEach { assertTrue(it.toString() in chars) }
    }

    @Test
    fun `layout de simbolos no tiene shift y tiene puntuacion`() {
        val all = Layouts.SYMBOLS.flatten()
        assertTrue(all.none { it.type == KeyType.SHIFT })
        val chars = all.filter { it.type == KeyType.CHAR }.map { it.char!! }
        listOf(".", ",", "?", "!", ":", "_", "/").forEach { assertTrue(it in chars) }
    }

    @Test
    fun `fila inferior contiene cursor enter espacio lola layer y emoji`() {
        val types = Layouts.BOTTOM_ROW.map { it.type }
        assertTrue(KeyType.LEFT in types)
        assertTrue(KeyType.RIGHT in types)
        assertTrue(KeyType.ENTER in types)
        assertTrue(KeyType.SPACE in types)
        assertTrue(KeyType.LOLA in types)
        assertTrue(KeyType.LAYER in types)
        assertTrue(KeyType.EMOJI in types)
    }

    @Test
    fun `shift cicla tres estados`() {
        val e = KeyEngine()
        assertEquals(ShiftState.MINUSCULAS, e.shift)
        e.press(Key.SHIFT)
        assertEquals(ShiftState.MAYUSCULA, e.shift)
        e.press(Key.SHIFT)
        assertEquals(ShiftState.MINUSCULAS, e.shift)
    }

    @Test
    fun `lock shift fija mayusculas y el tap desbloquea`() {
        val e = KeyEngine()
        e.lockShift()
        assertEquals(ShiftState.BLOQUEO, e.shift)
        e.press(Key.SHIFT)
        assertEquals(ShiftState.MINUSCULAS, e.shift)
    }

    @Test
    fun `shift temporal capitaliza una letra y se consume`() {
        val e = KeyEngine()
        e.press(Key.SHIFT)
        assertEquals(KeyEffect.Commit("A"), e.press(Key.char("a")))
        assertEquals(ShiftState.MINUSCULAS, e.shift)
    }

    @Test
    fun `bloqueo capitaliza sin consumirse`() {
        val e = KeyEngine()
        e.lockShift()
        assertEquals(KeyEffect.Commit("A"), e.press(Key.char("a")))
        assertEquals(ShiftState.BLOQUEO, e.shift)
        assertEquals(KeyEffect.Commit("B"), e.press(Key.char("b")))
    }

    @Test
    fun `espacio backspace y enter producen efectos`() {
        val e = KeyEngine()
        assertEquals(KeyEffect.Commit(" "), e.press(Key.SPACE))
        assertEquals(KeyEffect.Delete, e.press(Key.BACKSPACE))
        assertEquals(KeyEffect.Enter, e.press(Key.ENTER))
    }

    @Test
    fun `teclas de cursor producen efectos de movimiento`() {
        val e = KeyEngine()
        assertEquals(KeyEffect.MoveLeft, e.press(Key.LEFT))
        assertEquals(KeyEffect.MoveRight, e.press(Key.RIGHT))
    }

    @Test
    fun `layer alterna letras y simbolos y cambia la etiqueta`() {
        val e = KeyEngine()
        assertEquals(KeyboardLayer.LETTERS, e.layer)
        e.press(Key.LAYER)
        assertEquals(KeyboardLayer.SYMBOLS, e.layer)
        assertEquals("ABC", e.label(Key.LAYER, EditorProfile.EMPTY))
        e.press(Key.LAYER)
        assertEquals(KeyboardLayer.LETTERS, e.layer)
        assertEquals("123", e.label(Key.LAYER, EditorProfile.EMPTY))
    }

    @Test
    fun `en simbolos shift no capitaliza`() {
        val e = KeyEngine()
        e.press(Key.LAYER)
        e.press(Key.SHIFT)
        assertEquals(KeyEffect.Commit("n"), e.press(Key.char("n")))
    }

    @Test
    fun `consumeTemporaryShift consume el temporal pero no el bloqueo`() {
        val e = KeyEngine()
        e.press(Key.SHIFT) // MAYUSCULA (una mayúscula pendiente)
        e.consumeTemporaryShift()
        assertEquals(ShiftState.MINUSCULAS, e.shift)

        e.lockShift() // BLOQUEO
        e.consumeTemporaryShift()
        assertEquals(ShiftState.BLOQUEO, e.shift) // no toca el bloqueo
    }

    @Test
    fun `emoji alterna la capa y resalta la tecla`() {
        val e = KeyEngine()
        e.press(Key.EMOJI)
        assertEquals(KeyboardLayer.EMOJI, e.layer)
        assertTrue(e.isActive(Key.EMOJI))
        e.press(Key.EMOJI)
        assertEquals(KeyboardLayer.LETTERS, e.layer)
    }

    @Test
    fun `alternativas de a incluyen tilde y se capitalizan con shift`() {
        val e = KeyEngine()
        assertTrue("á" in e.alternatives(Key.char("a")))
        e.lockShift()
        assertTrue("Á" in e.alternatives(Key.char("a")))
    }

    @Test
    fun `enter label refleja accion del editor`() {
        val e = KeyEngine()
        assertEquals("⏎", e.label(Key.ENTER, EditorProfile.EMPTY))
        assertEquals("✓", e.label(Key.ENTER, EditorProfile.EMPTY.copy(enterAction = EditorAction.DONE)))
        assertEquals("➤", e.label(Key.ENTER, EditorProfile.EMPTY.copy(enterAction = EditorAction.SEND)))
        assertEquals("⏎", e.label(Key.ENTER, EditorProfile.EMPTY.copy(isMultiline = true)))
    }
}
