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

    /** Arranca una sesión de input con el tipo/opciones del editor y la conexión. */
    fun onStartInput(inputType: Int, imeOptions: Int, provider: () -> EditorConnection?) {
        connectionProvider = provider
        profile = EditorInfoClassifier.classify(inputType, imeOptions)
        composing = ""
    }

    fun onFinishInput() {
        connectionProvider = { null }
        profile = EditorProfile.EMPTY
        composing = ""
    }

    fun commitText(text: String) {
        connectionProvider()?.commitText(text, 1)
    }

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
        if (len > 0) conn.deleteSurroundingText(len, 0)
    }

    /**
     * Mueve el cursor un grapheme hacia la izquierda (delta negativo) o la
     * derecha (delta positivo). Se calcula la posición leyendo el contexto
     * antes/después del cursor (ventana [Grapheme.LOOKBACK]) y se fija con
     * `setSelection` colapsando cualquier selección previa.
     */
    fun moveCursor(delta: Int) {
        val conn = connectionProvider() ?: return
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
        when (val decision = profile.enterDecision) {
            is EnterDecision.Newline -> conn.sendKeyEventNewline()
            is EnterDecision.Action -> {
                if (!conn.performEditorAction(decision.id)) {
                    conn.sendKeyEventNewline()
                }
            }
        }
    }
}
