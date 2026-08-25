package com.elladecide.lola.editor

/**
 * Sesión de edición: el núcleo IME puro. Mantiene el perfil del editor activo y
 * la conexión (resuelta a demanda), y expone las operaciones de edición
 * correctas:
 *
 *  - commit / composing (composición de texto).
 *  - borrado por grapheme cluster (no rompe emojis ni combinantes).
 *  - Enter según `imeOptions` (acción IME o nueva línea).
 *
 * El [LolaImeService] lo alimenta en `onStartInput` con el `EditorInfo` y un
 * proveedor del `InputConnection` real; el resto de la app solo llama a estos
 * métodos, sin saber de android.view.inputmethod.
 */
class EditorSession {

    private var connectionProvider: () -> EditorConnection? = { null }

    var profile: EditorProfile = EditorProfile.EMPTY
        private set

    private var composing = ""

    /**
     * Posición del cursor dentro del composing (offset en unidades UTF-16, en
     * `0..composing.length`). Permite navegar por el interior de la palabra en
     * composición sin commitearla, como hace Gboard.
     */
    private var composingCursor = 0

    /** Último texto committeado por el tipeo (palabra + separador), objetivo del undo. */
    private var undoableText: String? = null

    /** Arranca una sesión de input con el tipo/opciones del editor y la conexión. */
    fun onStartInput(inputType: Int, imeOptions: Int, provider: () -> EditorConnection?) {
        connectionProvider = provider
        profile = EditorInfoClassifier.classify(inputType, imeOptions)
        composing = ""
        composingCursor = 0
        undoableText = null
    }

    fun onFinishInput() {
        connectionProvider = { null }
        profile = EditorProfile.EMPTY
        composing = ""
        composingCursor = 0
        undoableText = null
    }

    /**
     * Commit directo (emoji, mensaje LOLA): no participa de la composición de
     * palabras ni del undo. Si hay composición activa, la finaliza primero para
     * no perderla (el `commitText` del sistema reemplazaría el composing).
     */
    fun commitText(text: String) {
        val conn = connectionProvider() ?: return
        if (composing.isNotEmpty()) {
            conn.finishComposingText()
            composing = ""
            composingCursor = 0
        }
        conn.commitText(text, 1)
        undoableText = null
    }

    /**
     * Tipeo con límite de palabra: las letras/dígitos/apóstrofe se acumulan en
     * composición (para predecir y reemplazar); el espacio/puntuación finaliza
     * la palabra en composición y commitea el separador. La unidad de undo queda
     * como "palabra + separador" para que deshacer la deje completa. Si el
     * cursor está dentro del composing, la letra se inserta en esa posición.
     */
    fun typeText(text: String) {
        val conn = connectionProvider() ?: return
        if (text.isEmpty()) return
        if (Word.isWord(text)) {
            val at = composingCursor.coerceIn(0, composing.length)
            val merged = composing.substring(0, at) + text + composing.substring(at)
            updateComposing(merged, at + text.length)
        } else {
            val word = composing
            if (word.isNotEmpty()) {
                conn.finishComposingText()
                composing = ""
                composingCursor = 0
            }
            conn.commitText(text, 1)
            undoableText = word + text
        }
    }

    /** Reemplaza la palabra en composición (selección de una sugerencia). */
    fun replaceComposingText(text: String) {
        val conn = connectionProvider() ?: return
        composing = text
        composingCursor = text.length
        conn.setComposingText(text, 1)
    }

    /** Palabra actual en composición (vacía si no hay composición activa). */
    fun currentWord(): String = composing

    fun setComposingText(text: String) {
        connectionProvider()?.setComposingText(text, 1)
        composing = text
        composingCursor = text.length
    }

    fun finishComposing() {
        connectionProvider()?.finishComposingText()
        composing = ""
        composingCursor = 0
    }

    /**
     * Borra hacia atrás un grapheme completo. Si hay composición activa, borra
     * el cluster anterior a la posición del cursor (no necesariamente el
     * último); al vaciarla, la finaliza. Con el cursor al inicio del composing,
     * lo finaliza y borra el texto committeado previo. Si no, lee el contexto
     * antes del cursor y borra exactamente el último cluster.
     */
    fun deleteBackwards() {
        val conn = connectionProvider() ?: return
        if (composing.isNotEmpty()) {
            if (composingCursor > 0) {
                val head = composing.substring(0, composingCursor)
                val del = Grapheme.lastClusterLength(head)
                updateComposing(
                    composing.substring(0, composingCursor - del) + composing.substring(composingCursor),
                    composingCursor - del,
                )
            } else {
                conn.finishComposingText()
                composing = ""
                composingCursor = 0
                deleteBackwards()
            }
            return
        }
        val before = conn.getTextBeforeCursor(Grapheme.LOOKBACK, 0) ?: return
        val len = Grapheme.lastClusterLength(before)
        if (len > 0) {
            conn.deleteSurroundingText(len, 0)
            // Borrar texto committeado invalida el snapshot de undo (el cursor
            // y el buffer ya no coinciden con lo registrado).
            undoableText = null
        }
    }

    /**
     * Borra la palabra completa a la izquierda del cursor (grapheme-safe, como
     * Ctrl+Backspace). Si hay composición activa, la elimina entera.
     */
    fun deleteWord() {
        val conn = connectionProvider() ?: return
        if (composing.isNotEmpty()) {
            composing = ""
            composingCursor = 0
            conn.setComposingText("", 1)
            conn.finishComposingText()
            undoableText = null
            return
        }
        val before = conn.getTextBeforeCursor(Grapheme.LOOKBACK, 0) ?: return
        val len = Grapheme.wordLengthBackwards(before)
        if (len > 0) {
            conn.deleteSurroundingText(len, 0)
            undoableText = null
        }
    }

    /**
     * Mueve el cursor un grapheme hacia la izquierda (delta negativo) o la
     * derecha (delta positivo). Con composición activa navega DENTRO del
     * composing (manteniéndola, estilo Gboard): el cursor se reposiciona sobre
     * la palabra en composición y las sugerencias siguen aplicándose al texto
     * completo. Al llegar al borde del composing, lo finaliza (commitea) y
     * continúa sobre el texto ya committeado.
     */
    fun moveCursor(delta: Int) {
        val conn = connectionProvider() ?: return
        if (composing.isNotEmpty()) {
            when {
                delta < 0 && composingCursor > 0 -> {
                    val step = Grapheme.lastClusterLength(composing.substring(0, composingCursor))
                    updateComposing(composing, composingCursor - step)
                }
                delta > 0 && composingCursor < composing.length -> {
                    val step = Grapheme.firstClusterLength(composing.substring(composingCursor))
                    updateComposing(composing, composingCursor + step)
                }
                else -> {
                    // Borde del composing: se finaliza (se commitea) para que la
                    // navegación siga sobre texto committeado y el setSelection
                    // quede consistente.
                    conn.finishComposingText()
                    composing = ""
                    composingCursor = 0
                    undoableText = null
                    moveCursor(delta)
                }
            }
            return
        }
        // Mover el cursor invalida el snapshot de undo (el cursor ya no está
        // justo después de la última palabra committeada).
        undoableText = null
        val before = conn.getTextBeforeCursor(Grapheme.LOOKBACK, 0)?.toString() ?: return
        val after = conn.getTextAfterCursor(Grapheme.LOOKBACK, 0)?.toString() ?: ""
        val cursorPos = before.length
        val target = when {
            delta < 0 -> (cursorPos - Grapheme.lastClusterLength(before)).coerceAtLeast(0)
            delta > 0 -> (cursorPos + Grapheme.firstClusterLength(after))
                .coerceAtMost(cursorPos + after.length)
            else -> cursorPos
        }
        conn.setSelection(target, target)
    }

    /**
     * Enter según el editor: si el campo es multiline o no declara acción,
     * manda nueva línea; si declara una acción (Done/Go/Next/Search/Send), la
     * despacha y, si el editor no la maneja, cae a nueva línea.
     */
    fun sendEnter() {
        val conn = connectionProvider() ?: return
        if (composing.isNotEmpty()) {
            conn.finishComposingText()
            composing = ""
            composingCursor = 0
        }
        undoableText = null
        when (val decision = profile.enterDecision) {
            is EnterDecision.Newline -> conn.sendKeyEventNewline()
            is EnterDecision.Action -> {
                if (!conn.performEditorAction(decision.id)) {
                    conn.sendKeyEventNewline()
                }
            }
        }
    }

    /**
     * Deshace el último texto committeado por el tipeo (palabra + separador).
     * Si hay composición activa, la elimina completa (equivale a deshacer la
     * palabra en curso).
     */
    fun undo() {
        val conn = connectionProvider() ?: return
        if (composing.isNotEmpty()) {
            composing = ""
            composingCursor = 0
            conn.setComposingText("", 1)
            conn.finishComposingText()
            return
        }
        val text = undoableText ?: return
        conn.deleteSurroundingText(text.length, 0)
        undoableText = null
    }

    /**
     * Actualiza el texto en composición y posiciona el cursor en [cursorWithin]
     * (offset en unidades UTF-16 dentro del composing). `newCursorPosition` de
     * `setComposingText` solo posiciona en el inicio (0) o el final (1) del
     * texto, así que para un cursor interior se reenvía el composing con el
     * cursor al final y se fija la selección absoluta (el inicio del composing
     * se deriva del texto antes del cursor menos el largo del composing).
     */
    private fun updateComposing(text: String, cursorWithin: Int) {
        val conn = connectionProvider() ?: return
        composing = text
        composingCursor = cursorWithin
        if (text.isEmpty()) {
            conn.setComposingText("", 1)
            conn.finishComposingText()
            return
        }
        conn.setComposingText(text, 1)
        val before = conn.getTextBeforeCursor(Grapheme.LOOKBACK, 0)?.length ?: return
        val start = (before - text.length).coerceAtLeast(0)
        conn.setSelection(start + cursorWithin, start + cursorWithin)
    }
}
