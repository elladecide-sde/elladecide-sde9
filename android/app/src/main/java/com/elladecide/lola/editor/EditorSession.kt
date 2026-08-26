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
 * El [LolaImeService] lo alimenta en `onStartInput` con el `EditorInfo`, un
 * proveedor del `InputConnection` real, y con [onSelectionChanged] cada vez que
 * el editor reporta su selección (onUpdateSelection).
 *
 * UNA ÚNICA FUENTE DE VERDAD: la selección real del editor, reportada por
 * [onSelectionChanged]. Las operaciones NO derivan posiciones absolutas de
 * `getTextBeforeCursor` (que trunca en [Grapheme.LOOKBACK] caracteres y rompe
 * el cursor en textos largos): el inicio de la composición se guarda como
 * offset ABSOLUTO ([composingStart]) y el cursor de navegación se mueve sobre
 * ese valor real.
 */
class EditorSession {

    private var connectionProvider: () -> EditorConnection? = { null }

    var profile: EditorProfile = EditorProfile.EMPTY
        private set

    /** Selección real del editor (fuente de verdad, sincronizada por onUpdateSelection). */
    private var selectionStart = 0
    private var selectionEnd = 0

    /** Composición activa: ocupa [composingStart, composingStart + composing.length). */
    private var composing = ""
    private var composingStart = -1

    /** Posición del cursor dentro del composing (offset UTF-16 en `0..composing.length`). */
    private var composingCursor = 0

    /** Último texto committeado por el tipeo (palabra + separador), objetivo del undo. */
    private var undoableText: String? = null

    private val isComposing: Boolean get() = composingStart >= 0
    private val composingEnd: Int get() = composingStart + composing.length

    /** Arranca una sesión de input con el tipo/opciones del editor y la conexión. */
    fun onStartInput(inputType: Int, imeOptions: Int, provider: () -> EditorConnection?) {
        connectionProvider = provider
        profile = EditorInfoClassifier.classify(inputType, imeOptions)
        selectionStart = 0
        selectionEnd = 0
        composing = ""
        composingStart = -1
        composingCursor = 0
        undoableText = null
    }

    fun onFinishInput() {
        connectionProvider = { null }
        profile = EditorProfile.EMPTY
        selectionStart = 0
        selectionEnd = 0
        composing = ""
        composingStart = -1
        composingCursor = 0
        undoableText = null
    }

    /**
     * El editor reporta su selección real (`InputMethodService.onUpdateSelection`).
     * Si el usuario tocó fuera del composing o hizo una selección de rango, la
     * composición se finaliza (ya no aplica); si sigue dentro, se actualiza la
     * posición interna del cursor (navegación por glide).
     */
    fun onSelectionChanged(newStart: Int, newEnd: Int) {
        selectionStart = newStart
        selectionEnd = newEnd
        if (!isComposing) return
        if (newStart != newEnd) {
            finishComposing()
        } else if (newStart < composingStart || newStart > composingEnd) {
            finishComposing()
        } else {
            composingCursor = (newStart - composingStart).coerceIn(0, composing.length)
        }
    }

    /**
     * Resincroniza el estado interno con el editor (p.ej. al cambiar de modo
     * CHAT ↔ LOLA, spec F3 §16): finaliza la composición activa para que el
     * texto quede completamente editable y no haya un composing "colgado" que
     * desincronice borrados posteriores.
     */
    fun syncEditor() {
        val conn = connectionProvider() ?: return
        if (isComposing) {
            conn.finishComposingText()
            composing = ""
            composingStart = -1
            composingCursor = 0
        }
    }

    /**
     * Commit directo (emoji, mensaje LOLA): no participa de la composición de
     * palabras ni del undo. Si hay composición activa, la finaliza primero para
     * no perderla (el `commitText` del sistema reemplazaría el composing).
     */
    fun commitText(text: String) {
        val conn = connectionProvider() ?: return
        if (isComposing) finishComposing()
        conn.commitText(text, 1)
        selectionStart += text.length
        selectionEnd = selectionStart
        undoableText = null
    }

    /**
     * Tipeo con límite de palabra: las letras/dígitos/apóstrofe se acumulan en
     * composición (para predecir y reemplazar); el espacio/puntuación finaliza
     * la palabra en composición y commitea el separador. La unidad de undo queda
     * como "palabra + separador".
     *
     * Solo extiende la composición activa si realmente existe (fuente de verdad
     * = selección reportada): si el usuario movió el cursor a otra parte, se
     * empieza una composición fresca en la posición real.
     */
    fun typeText(text: String) {
        val conn = connectionProvider() ?: return
        if (text.isEmpty()) return
        if (Word.isWord(text)) {
            if (isComposing) {
                val at = composingCursor.coerceIn(0, composing.length)
                val merged = composing.substring(0, at) + text + composing.substring(at)
                updateComposing(merged, at + text.length)
            } else {
                startComposing(text)
            }
        } else {
            val word = composing
            if (isComposing) finishComposing()
            conn.commitText(text, 1)
            selectionStart += text.length
            selectionEnd = selectionStart
            undoableText = word + text
        }
    }

    /** Reemplaza la palabra en composición (selección de una sugerencia). */
    fun replaceComposingText(text: String) {
        val conn = connectionProvider() ?: return
        if (!isComposing) {
            startComposing(text)
            return
        }
        composing = text
        composingCursor = text.length
        conn.setComposingText(text, 1)
        selectionStart = composingStart + text.length
        selectionEnd = selectionStart
    }

    /** Palabra actual en composición (vacía si no hay composición activa). */
    fun currentWord(): String = composing

    fun setComposingText(text: String) {
        if (text.isEmpty()) {
            finishComposing()
            return
        }
        if (isComposing) {
            composing = text
            composingCursor = text.length
            connectionProvider()?.setComposingText(text, 1)
            selectionStart = composingStart + text.length
            selectionEnd = selectionStart
        } else {
            startComposing(text)
        }
    }

    /** Finaliza (commitea) la composición activa. */
    fun finishComposing() {
        connectionProvider()?.finishComposingText()
        composing = ""
        composingStart = -1
        composingCursor = 0
    }

    /**
     * Arranca una composición en la posición REAL del cursor (selectionStart,
     * reportada por el editor), sin derivarla de getTextBeforeCursor truncado.
     */
    private fun startComposing(text: String) {
        val conn = connectionProvider() ?: return
        composing = text
        composingStart = selectionStart
        composingCursor = text.length
        conn.setComposingText(text, 1)
        selectionStart = composingStart + text.length
        selectionEnd = selectionStart
    }

    /**
     * Actualiza el texto en composición y posiciona el cursor en [cursorWithin]
     * (offset UTF-16 dentro del composing). Usa [composingStart] absoluto para
     * `setSelection`, no una posición derivada de una ventana truncada.
     */
    private fun updateComposing(text: String, cursorWithin: Int) {
        val conn = connectionProvider() ?: return
        composing = text
        composingCursor = cursorWithin
        if (text.isEmpty()) {
            conn.setComposingText("", 1)
            conn.finishComposingText()
            composingStart = -1
            return
        }
        conn.setComposingText(text, 1)
        val abs = composingStart + cursorWithin
        conn.setSelection(abs, abs)
        selectionStart = abs
        selectionEnd = abs
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
        if (isComposing) {
            if (composingCursor > 0) {
                val head = composing.substring(0, composingCursor)
                val del = Grapheme.lastClusterLength(head)
                updateComposing(
                    composing.substring(0, composingCursor - del) + composing.substring(composingCursor),
                    composingCursor - del,
                )
            } else {
                finishComposing()
                deleteBackwards()
            }
            return
        }
        val before = conn.getTextBeforeCursor(Grapheme.LOOKBACK, 0) ?: return
        val len = Grapheme.lastClusterLength(before)
        if (len > 0) {
            conn.deleteSurroundingText(len, 0)
            selectionStart = (selectionStart - len).coerceAtLeast(0)
            selectionEnd = selectionStart
            undoableText = null
        }
    }

    /**
     * Borra la palabra completa a la izquierda del cursor (grapheme-safe, como
     * Ctrl+Backspace). Si hay composición activa, la elimina entera.
     */
    fun deleteWord() {
        val conn = connectionProvider() ?: return
        if (isComposing) {
            conn.setComposingText("", 1)
            conn.finishComposingText()
            composing = ""
            composingStart = -1
            composingCursor = 0
            undoableText = null
            return
        }
        val before = conn.getTextBeforeCursor(Grapheme.LOOKBACK, 0) ?: return
        val len = Grapheme.wordLengthBackwards(before)
        if (len > 0) {
            conn.deleteSurroundingText(len, 0)
            selectionStart = (selectionStart - len).coerceAtLeast(0)
            selectionEnd = selectionStart
            undoableText = null
        }
    }

    /**
     * Mueve el cursor un grapheme hacia la izquierda (delta negativo) o la
     * derecha (delta positivo). Con composición activa navega DENTRO del
     * composing (manteniéndola, estilo Gboard). Al llegar al borde del
     * composing, lo finaliza (commitea) y continúa sobre el texto committeado.
     * Sin composición, parte de la posición real reportada por el editor.
     */
    fun moveCursor(delta: Int) {
        val conn = connectionProvider() ?: return
        if (isComposing) {
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
                    finishComposing()
                    moveCursor(delta)
                }
            }
            return
        }
        // Mover el cursor invalida el snapshot de undo.
        undoableText = null
        val before = conn.getTextBeforeCursor(Grapheme.LOOKBACK, 0)?.toString() ?: ""
        val after = conn.getTextAfterCursor(Grapheme.LOOKBACK, 0)?.toString() ?: ""
        // `before`/`after` solo miden CLUSTERS (siempre dentro de la ventana);
        // la posición absoluta sale de selectionStart (fuente de verdad), que no
        // se trunca con textos largos.
        val pos = selectionStart
        val target = when {
            delta < 0 -> (pos - Grapheme.lastClusterLength(before)).coerceAtLeast(0)
            delta > 0 -> (pos + Grapheme.firstClusterLength(after)).coerceAtMost(pos + after.length)
            else -> pos
        }
        conn.setSelection(target, target)
        selectionStart = target
        selectionEnd = target
    }

    /**
     * Enter según el editor: si el campo es multiline o no declara acción,
     * manda nueva línea; si declara una acción (Done/Go/Next/Search/Send), la
     * despacha y, si el editor no la maneja, cae a nueva línea.
     */
    fun sendEnter() {
        val conn = connectionProvider() ?: return
        if (isComposing) finishComposing()
        undoableText = null
        when (val decision = profile.enterDecision) {
            is EnterDecision.Newline -> {
                conn.sendKeyEventNewline()
                selectionStart += 1
                selectionEnd = selectionStart
            }
            is EnterDecision.Action -> {
                if (!conn.performEditorAction(decision.id)) {
                    conn.sendKeyEventNewline()
                    selectionStart += 1
                    selectionEnd = selectionStart
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
        if (isComposing) {
            val start = composingStart
            conn.setComposingText("", 1)
            conn.finishComposingText()
            composing = ""
            composingStart = -1
            composingCursor = 0
            selectionStart = start
            selectionEnd = start
            return
        }
        val text = undoableText ?: return
        conn.deleteSurroundingText(text.length, 0)
        selectionStart = (selectionStart - text.length).coerceAtLeast(0)
        selectionEnd = selectionStart
        undoableText = null
    }
}