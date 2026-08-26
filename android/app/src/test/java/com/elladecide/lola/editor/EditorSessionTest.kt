package com.elladecide.lola.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditorSessionTest {

    private val CLASS_TEXT = 0x00000001
    private val FLAG_MULTI_LINE = 0x00020000
    private val ACTION_DONE = 0x00000006

    private fun session(
        conn: FakeEditorConnection,
        inputType: Int = CLASS_TEXT,
        imeOptions: Int = 0,
    ): EditorSession = EditorSession().also {
        it.onStartInput(inputType, imeOptions) { conn }
        // El editor reporta su selección inicial (onUpdateSelection).
        it.onSelectionChanged(conn.text.length, conn.text.length)
    }

    @Test
    fun `commit escribe a traves de la conexion`() {
        val fake = FakeEditorConnection()
        session(fake).commitText("hola")
        assertEquals("hola", fake.text)
    }

    @Test
    fun `delete ascii borra un solo caracter`() {
        val fake = FakeEditorConnection("hola")
        session(fake).deleteBackwards()
        assertEquals("hol", fake.text)
    }

    @Test
    fun `delete borra un grapheme completo emoji`() {
        val fake = FakeEditorConnection("hola 😀")
        session(fake).deleteBackwards()
        assertEquals("hola ", fake.text)
    }

    @Test
    fun `delete borra marca combinante completa`() {
        val fake = FakeEditorConnection("cafe\u0301")
        session(fake).deleteBackwards()
        assertEquals("caf", fake.text)
    }

    @Test
    fun `delete no rompe con buffer vacio`() {
        val fake = FakeEditorConnection("")
        session(fake).deleteBackwards()
        assertEquals("", fake.text)
    }

    @Test
    fun `delete dentro de composicion`() {
        val fake = FakeEditorConnection()
        val s = session(fake)
        s.setComposingText("ab")
        s.deleteBackwards()
        assertEquals("a", fake.text)
        s.deleteBackwards()
        assertEquals("", fake.text)
        s.deleteBackwards() // composición ya vacía → no-op
        assertEquals("", fake.text)
    }

    @Test
    fun `delete despues de composicion borra del buffer real`() {
        val fake = FakeEditorConnection("prefijo")
        val s = session(fake)
        s.setComposingText("x")
        s.deleteBackwards() // vacía y finaliza composición "x"
        s.deleteBackwards() // borra "o" de "prefijo"
        assertEquals("prefij", fake.text)
    }

    @Test
    fun `enter en multilinea manda newline`() {
        val fake = FakeEditorConnection()
        val s = session(fake, inputType = CLASS_TEXT or FLAG_MULTI_LINE)
        s.sendEnter()
        assertEquals(1, fake.newlineCount)
        assertNull(fake.lastAction)
    }

    @Test
    fun `enter con accion done la despacha`() {
        val fake = FakeEditorConnection()
        val s = session(fake, imeOptions = ACTION_DONE)
        s.sendEnter()
        assertEquals(6, fake.lastAction)
        assertEquals(0, fake.newlineCount)
    }

    @Test
    fun `enter con accion no manejada cae a newline`() {
        val fake = FakeEditorConnection().apply { actionHandled = false }
        val s = session(fake, imeOptions = ACTION_DONE)
        s.sendEnter()
        assertEquals(6, fake.lastAction)
        assertEquals(1, fake.newlineCount)
    }

    @Test
    fun `finish input resetea el perfil`() {
        val fake = FakeEditorConnection()
        val s = session(fake, imeOptions = ACTION_DONE)
        assertEquals(EditorAction.DONE, s.profile.enterAction)
        s.onFinishInput()
        assertEquals(EditorProfile.EMPTY, s.profile)
        s.deleteBackwards() // sin conexión → no-op sin crash
        assertEquals("", fake.text)
    }

    @Test
    fun `mover cursor a la izquierda`() {
        val fake = FakeEditorConnection("hola")
        session(fake).moveCursor(-1)
        assertEquals(3, fake.cursorPos)
    }

    @Test
    fun `mover cursor a la derecha`() {
        val fake = FakeEditorConnection("hola")
        val s = session(fake)
        s.moveCursor(-1)
        s.moveCursor(1)
        assertEquals(4, fake.cursorPos)
    }

    @Test
    fun `mover izquierda salta un grapheme completo`() {
        val fake = FakeEditorConnection("hola 😀")
        session(fake).moveCursor(-1)
        assertEquals(5, fake.cursorPos) // "hola " son 5 code units
    }

    @Test
    fun `mover izquierda al inicio no hace nada`() {
        val fake = FakeEditorConnection("hola")
        val s = session(fake)
        repeat(10) { s.moveCursor(-1) }
        assertEquals(0, fake.cursorPos)
    }

    @Test
    fun `mover derecha al final no hace nada`() {
        val fake = FakeEditorConnection("hola")
        val s = session(fake)
        repeat(10) { s.moveCursor(1) }
        assertEquals(4, fake.cursorPos)
    }

    @Test
    fun `typeText acumula letras en composicion`() {
        val fake = FakeEditorConnection()
        val s = session(fake)
        s.typeText("h")
        s.typeText("o")
        assertEquals("ho", fake.text)
        assertEquals("ho", s.currentWord())
    }

    @Test
    fun `typeText espacio commitea palabra y espacio`() {
        val fake = FakeEditorConnection()
        val s = session(fake)
        s.typeText("h")
        s.typeText("o")
        s.typeText(" ")
        assertEquals("ho ", fake.text)
        assertEquals("", s.currentWord())
    }

    @Test
    fun `typeText puntuacion commitea palabra y puntuacion`() {
        val fake = FakeEditorConnection()
        val s = session(fake)
        s.typeText("h")
        s.typeText("i")
        s.typeText("!")
        assertEquals("hi!", fake.text)
    }

    @Test
    fun `replaceComposingText reemplaza la palabra en composicion`() {
        val fake = FakeEditorConnection()
        val s = session(fake)
        s.typeText("h")
        s.typeText("o")
        s.replaceComposingText("hola")
        assertEquals("hola", fake.text)
        assertEquals("hola", s.currentWord())
    }

    @Test
    fun `undo borra palabra y separador`() {
        val fake = FakeEditorConnection()
        val s = session(fake)
        s.typeText("h")
        s.typeText("o")
        s.typeText(" ")
        assertEquals("ho ", fake.text)
        s.undo()
        assertEquals("", fake.text)
    }

    @Test
    fun `undo sin palabra borra el separador`() {
        val fake = FakeEditorConnection()
        val s = session(fake)
        s.typeText(" ")
        s.typeText(" ")
        s.undo()
        assertEquals(" ", fake.text)
    }

    @Test
    fun `undo con composicion activa la elimina completa`() {
        val fake = FakeEditorConnection()
        val s = session(fake)
        s.typeText("h")
        s.typeText("o")
        assertEquals("ho", fake.text)
        s.undo()
        assertEquals("", fake.text)
    }

    @Test
    fun `mover cursor izquierda dentro del composing lo mantiene en composicion`() {
        val fake = FakeEditorConnection()
        val s = session(fake)
        s.typeText("h")
        s.typeText("o")
        s.typeText("l")
        s.typeText("a")
        s.moveCursor(-1)
        assertEquals("hola", fake.text)
        assertEquals(3, fake.cursorPos)
        assertEquals("hola", s.currentWord())
    }

    @Test
    fun `mover cursor derecha dentro del composing restaura la posicion`() {
        val fake = FakeEditorConnection()
        val s = session(fake)
        s.typeText("hola")
        s.moveCursor(-1)
        s.moveCursor(-1)
        assertEquals(2, fake.cursorPos)
        s.moveCursor(1)
        assertEquals(3, fake.cursorPos)
        assertEquals("hola", s.currentWord())
    }

    @Test
    fun `mover izquierda en el inicio del composing lo commitea y sigue`() {
        val fake = FakeEditorConnection()
        val s = session(fake)
        s.typeText("ho")
        s.moveCursor(-1)
        s.moveCursor(-1) // cursor al inicio del composing (0)
        assertEquals("ho", s.currentWord())
        s.moveCursor(-1) // sale del composing → lo commitea
        assertEquals("ho", fake.text)
        assertEquals("", s.currentWord())
        assertEquals(0, fake.cursorPos)
    }

    @Test
    fun `mover derecha en el final del composing lo commitea y sigue`() {
        val fake = FakeEditorConnection("hola ")
        val s = session(fake)
        s.typeText("mundo")
        s.moveCursor(-1)
        assertEquals(9, fake.cursorPos)
        s.moveCursor(1) // al final del composing
        assertEquals("mundo", s.currentWord())
        s.moveCursor(1) // sale del composing
        assertEquals("hola mundo", fake.text)
        assertEquals("", s.currentWord())
        assertEquals(10, fake.cursorPos)
    }

    @Test
    fun `typeText inserta dentro del composing en la posicion del cursor`() {
        val fake = FakeEditorConnection()
        val s = session(fake)
        s.typeText("hola")
        s.moveCursor(-1) // cursor entre "hol" y "a"
        s.typeText("e")
        assertEquals("holea", fake.text)
        assertEquals(4, fake.cursorPos)
        assertEquals("holea", s.currentWord())
    }

    @Test
    fun `deleteBackwards borra el cluster antes del cursor dentro del composing`() {
        val fake = FakeEditorConnection()
        val s = session(fake)
        s.typeText("hola")
        s.moveCursor(-1) // cursor entre "hol" y "a"
        s.deleteBackwards() // borra "l"
        assertEquals("hoa", fake.text)
        assertEquals(2, fake.cursorPos)
        assertEquals("hoa", s.currentWord())
    }

    @Test
    fun `deleteBackwards al inicio del composing borra el texto committeado previo`() {
        val fake = FakeEditorConnection("pre ")
        val s = session(fake)
        s.typeText("hola")
        repeat(4) { s.moveCursor(-1) } // cursor al inicio del composing
        s.deleteBackwards() // commitea "hola" y borra el espacio previo
        assertEquals("prehola", fake.text)
        assertEquals(3, fake.cursorPos)
        assertEquals("", s.currentWord())
    }

    @Test
    fun `commit directo finaliza la composicion previa`() {
        val fake = FakeEditorConnection()
        val s = session(fake)
        s.typeText("h")
        s.typeText("o")
        s.commitText("!")
        assertEquals("ho!", fake.text)
    }

    @Test
    fun `deleteWord borra la palabra completa`() {
        val fake = FakeEditorConnection("hola mundo")
        session(fake).deleteWord()
        assertEquals("hola ", fake.text)
    }

    @Test
    fun `deleteWord borra palabra y espacio previo`() {
        val fake = FakeEditorConnection("hola mundo ")
        session(fake).deleteWord()
        assertEquals("hola ", fake.text)
    }

    @Test
    fun `deleteWord con composicion la elimina`() {
        val fake = FakeEditorConnection()
        val s = session(fake)
        s.typeText("h")
        s.typeText("o")
        s.deleteWord()
        assertEquals("", fake.text)
    }

    @Test
    fun `deleteWord sin texto es no-op`() {
        val fake = FakeEditorConnection("")
        session(fake).deleteWord()
        assertEquals("", fake.text)
    }

    // ── Cursor: escribir en el medio / final / inicio (spec §19) ─────────────

    @Test
    fun `escribir despues de mover el cursor al medio inserta ahi`() {
        val fake = FakeEditorConnection("hola mundo")
        val s = session(fake)
        fake.setSelection(5, 5) // el editor mueve el cursor (tap)
        s.onSelectionChanged(5, 5)
        s.typeText("X")
        assertEquals("hola Xmundo", fake.text)
        assertEquals(6, fake.cursorPos)
    }

    @Test
    fun `escribir despues de mover el cursor al final permanece al final`() {
        val fake = FakeEditorConnection("hola mundo")
        val s = session(fake)
        fake.setSelection(10, 10)
        s.onSelectionChanged(10, 10)
        s.typeText("X")
        assertEquals("hola mundoX", fake.text)
        assertEquals(11, fake.cursorPos)
    }

    @Test
    fun `escribir despues de mover el cursor al inicio inserta ahi`() {
        val fake = FakeEditorConnection("hola")
        val s = session(fake)
        fake.setSelection(0, 0)
        s.onSelectionChanged(0, 0)
        s.typeText("x")
        assertEquals("xhola", fake.text)
        assertEquals(1, fake.cursorPos)
    }

    @Test
    fun `escribir tras varias lineas no vuelve el cursor atras`() {
        val largo = "a".repeat(150) + " "
        val fake = FakeEditorConnection(largo) // 151 chars: excede LOOKBACK
        val s = session(fake)
        s.typeText("b")
        s.typeText("c")
        assertEquals(largo + "bc", fake.text)
        assertEquals(largo.length + 2, fake.cursorPos)
    }

    @Test
    fun `mover cursor en texto largo usa la posicion real no la ventana truncada`() {
        val largo = "a".repeat(150)
        val fake = FakeEditorConnection(largo)
        val s = session(fake)
        s.moveCursor(-1)
        assertEquals(149, fake.cursorPos)
    }

    @Test
    fun `borrar en el medio despues de mover el cursor`() {
        val fake = FakeEditorConnection("hola mundo")
        val s = session(fake)
        fake.setSelection(5, 5)
        s.onSelectionChanged(5, 5)
        s.deleteBackwards()
        assertEquals("holamundo", fake.text)
        assertEquals(4, fake.cursorPos)
    }

    // ── Sincronización con el editor (una única fuente de verdad) ────────────

    @Test
    fun `tocar fuera del composing lo finaliza y el tipeo inserta ahi`() {
        val fake = FakeEditorConnection("pre")
        val s = session(fake)
        s.typeText("hola") // composing en [3,7)
        assertEquals("prehola", fake.text)
        fake.setSelection(2, 2) // tap fuera del composing
        s.onSelectionChanged(2, 2)
        assertEquals("", s.currentWord())
        s.typeText("X")
        assertEquals("prXehola", fake.text)
        assertEquals(3, fake.cursorPos)
    }

    @Test
    fun `seleccion de rango finaliza la composicion`() {
        val fake = FakeEditorConnection()
        val s = session(fake)
        s.typeText("hola")
        s.onSelectionChanged(0, 2)
        assertEquals("", s.currentWord())
    }

    @Test
    fun `syncEditor finaliza el composing y deja el texto editable`() {
        val fake = FakeEditorConnection()
        val s = session(fake)
        s.typeText("lola")
        assertEquals("lola", fake.text)
        assertEquals("lola", s.currentWord())
        s.syncEditor() // LOLA → CHAT: finaliza la composición
        assertEquals("lola", fake.text)
        assertEquals("", s.currentWord())
        s.deleteBackwards()
        assertEquals("lol", fake.text)
        s.deleteBackwards()
        assertEquals("lo", fake.text)
    }

    @Test
    fun `cambio de modo no pierde el texto ni el cursor`() {
        val fake = FakeEditorConnection("pre ")
        val s = session(fake)
        s.typeText("hola")
        assertEquals(8, fake.cursorPos)
        s.syncEditor()
        assertEquals("pre hola", fake.text)
        assertEquals(8, fake.cursorPos)
    }
}
