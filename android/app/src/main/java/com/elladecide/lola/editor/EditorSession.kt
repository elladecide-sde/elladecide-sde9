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

    /** Último texto committeado por el tipeo (palabra + separador), objetivo del undo. */
    private var undoableText: String? = null

    /** Arranca una sesión de input con el tipo/opciones del editor y la conexión. */
    fun onStartInput(inputType: Int, imeOptions: Int, provider: () -> EditorConnection?) {
        connectionProvider = provider
        profile = EditorInfoClassifier.classify(inputType, imeOptions)
        composing = ""
        undoableText = null
    }

    fun onFinishInput() {
        connectionProvider = { null }
        profile = EditorProfile.EMPTY
        composing = ""
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
        }
        conn.commitText(text, 1)
        undoableText = null
    }

    /**
     * Tipeo con límite de palabra: las letras/dígitos/apóstrofe se acumulan en
     * composición (para predecir y reemplazar); el espacio/puntuación finaliza
     * la palabra en composición y commitea el separador. La unidad de undo queda
     * como "palabra + separador" para que deshacer la deje completa.
     */
    fun typeText(text: String) {
        val conn = connectionProvider() ?: return
        if (text.isEmpty()) return
        if (text.all { it.isWordChar() }) {
            composing += text
            conn.setComposingText(composing, 1)
        } else {
            val word = composing
            if (word.isNotEmpty()) {
                conn.finishComposingText()
                composing = ""
            }
            conn.commitText(text, 1)
            undoableText = word + text
        }
    }

    /** Reemplaza la palabra en composición (selección de una sugerencia). */
    fun replaceComposingText(text: String) {
        val conn = connectionProvider() ?: return
        composing = text
        conn.setComposingText(text, 1)
    }

    /** Palabra actual en composición (vacía si no hay composición activa). */
    fun currentWord(): String = composing

    fun setComposingText(text: String) {
        connectionProvider()?.setComposingText(text, 1)
        composing = text
    }

    fun finishComposing() {
        connectionProvider()?.finishComposingText()
        composing = ""
    }

    /**
     * Borra hacia atrás un grapheme completo. Si hay composición activa, borra
     * dentro de ella; al vaciarla, la finaliza. Si no, lee el contexto antes del
     * cursor y borra exactamente el último cluster.
     */
    fun deleteBackwards() {
        val conn = connectionProvider() ?: return
        if (composing.isNotEmpty()) {
            val shortened = Grapheme.removeLastCluster(composing)
            conn.setComposingText(shortened, 1)
            composing = shortened
            if (shortened.isEmpty()) conn.finishComposingText()
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
     * Mueve el cursor un grapheme hacia la izquierda (delta negativo) o la
     * derecha (delta positivo). Se calcula la posición leyendo el contexto
     * antes/después del cursor (ventana [Grapheme.LOOKBACK]) y se fija con
     * `setSelection` colapsando cualquier selección previa.
     */
    fun moveCursor(delta: Int) {
        val conn = connectionProvider() ?: return
        // Con composición activa, el cursor "lógico" vive dentro del composing;
        // se finaliza la palabra (se commitea) para que la navegación opere
        // sobre texto committeado y el setSelection quede consistente.
        if (composing.isNotEmpty()) {
            conn.finishComposingText()
            composing = ""
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
            conn.setComposingText("", 1)
            conn.finishComposingText()
            return
        }
        val text = undoableText ?: return
        conn.deleteSurroundingText(text.length, 0)
        undoableText = null
    }

    /** Carácter que forma parte de una palabra (letra, dígito o apóstrofe). */
    private fun Char.isWordChar(): Boolean = isLetterOrDigit() || this == '\''
}
