package com.elladecide.lola.editor

/**
 * Abstracción del `android.view.inputmethod.InputConnection` para poder testear
 * el núcleo de edición en la JVM (un fake no necesita depender de android.*).
 *
 * Es la superficie mínima que el [EditorSession] necesita: commit, composing,
 * borrado, consulta de contexto y acciones del editor.
 */
interface EditorConnection {
    fun commitText(text: String, newCursorPosition: Int = 1): Boolean
    fun setComposingText(text: String, newCursorPosition: Int = 1): Boolean
    fun finishComposingText(): Boolean
    fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean
    fun getTextBeforeCursor(n: Int, flags: Int): CharSequence?
    fun getTextAfterCursor(n: Int, flags: Int): CharSequence?
    fun getSelectedText(flags: Int): CharSequence?
    fun setSelection(start: Int, end: Int): Boolean
    fun performEditorAction(actionId: Int): Boolean
    fun sendKeyEventNewline(): Boolean
}
