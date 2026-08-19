package com.elladecide.lola.editor

/**
 * Fake en memoria de [EditorConnection] para tests JVM. Modela un buffer de
 * texto con un cursor, composición y registro de acciones, sin tocar android.*.
 */
class FakeEditorConnection(initial: String = "") : EditorConnection {

    private val sb = StringBuilder(initial)
    private var cursor = initial.length
    private var composingRange: IntRange? = null

    val text: String get() = sb.toString()
    val cursorPos: Int get() = cursor

    var lastAction: Int? = null
        private set
    var actionHandled: Boolean = true
    var newlineCount: Int = 0
        private set

    override fun commitText(text: String, newCursorPosition: Int): Boolean {
        removeComposing()
        sb.insert(cursor, text)
        cursor += text.length
        return true
    }

    override fun setComposingText(text: String, newCursorPosition: Int): Boolean {
        removeComposing()
        if (text.isEmpty()) return true
        sb.insert(cursor, text)
        composingRange = cursor until (cursor + text.length)
        cursor += text.length
        return true
    }

    override fun finishComposingText(): Boolean {
        composingRange = null
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val start = (cursor - beforeLength).coerceAtLeast(0)
        val end = (cursor + afterLength).coerceAtMost(sb.length)
        sb.delete(start, end)
        cursor = start
        return true
    }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
        val start = (cursor - n).coerceAtLeast(0)
        return sb.substring(start, cursor)
    }

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? {
        val end = (cursor + n).coerceAtMost(sb.length)
        return sb.substring(cursor, end)
    }

    override fun getSelectedText(flags: Int): CharSequence? = null

    override fun setSelection(start: Int, end: Int): Boolean {
        cursor = end
        return true
    }

    override fun performEditorAction(actionId: Int): Boolean {
        lastAction = actionId
        return actionHandled
    }

    override fun sendKeyEventNewline(): Boolean {
        newlineCount++
        removeComposing()
        sb.insert(cursor, "\n")
        cursor += 1
        return true
    }

    private fun removeComposing() {
        val r = composingRange ?: return
        sb.delete(r.first, r.last + 1)
        cursor = r.first
        composingRange = null
    }
}
