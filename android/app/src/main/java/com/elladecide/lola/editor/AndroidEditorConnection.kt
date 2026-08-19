package com.elladecide.lola.editor

import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.InputConnection

/**
 * Adaptador que envuelve el `InputConnection` real del sistema en la interfaz
 * [EditorConnection]. Acá vive toda la interacción con android.*; el resto del
 * núcleo de edición queda puro y testeable.
 */
class AndroidEditorConnection(private val ic: InputConnection?) : EditorConnection {

    override fun commitText(text: String, newCursorPosition: Int): Boolean =
        ic?.commitText(text, newCursorPosition) ?: false

    override fun setComposingText(text: String, newCursorPosition: Int): Boolean =
        ic?.setComposingText(text, newCursorPosition) ?: false

    override fun finishComposingText(): Boolean = ic?.finishComposingText() ?: false

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
        ic?.deleteSurroundingText(beforeLength, afterLength) ?: false

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? =
        ic?.getTextBeforeCursor(n, flags)

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? =
        ic?.getTextAfterCursor(n, flags)

    override fun getSelectedText(flags: Int): CharSequence? = ic?.getSelectedText(flags)

    override fun setSelection(start: Int, end: Int): Boolean = ic?.setSelection(start, end) ?: false

    override fun performEditorAction(actionId: Int): Boolean =
        ic?.performEditorAction(actionId) ?: false

    /**
     * Nueva línea vía eventos de tecla, con las mismas flags que
     * `InputMethodService.sendDownUpKeyEvents()` (FLAG_SOFT_KEYBOARD +
     * FLAG_KEEP_TOUCH_MODE) para que el editor la trate como entrada del IME.
     */
    override fun sendKeyEventNewline(): Boolean {
        val conn = ic ?: return false
        val now = SystemClock.uptimeMillis()
        val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, 0, 0, 0, flags)
        val up = KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0, 0, 0, 0, flags)
        return conn.sendKeyEvent(down) && conn.sendKeyEvent(up)
    }
}
