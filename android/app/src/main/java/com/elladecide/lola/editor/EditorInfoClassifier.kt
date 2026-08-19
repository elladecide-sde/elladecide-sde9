package com.elladecide.lola.editor

/**
 * Clasificador puro de `EditorInfo`: mapea `inputType` + `imeOptions` a un
 * [EditorProfile] sin tocar clases de Android (solo enteros), para poder
 * testear la lógica en la JVM.
 *
 * Los valores son los públicos y estables de `android.text.InputType` y
 * `android.view.inputmethod.EditorInfo` (no cambian entre APIs).
 */
object EditorInfoClassifier {

    // ── android.text.InputType (valores estables) ──────────────────────────
    private const val MASK_CLASS = 0x0000000f
    private const val MASK_VARIATION = 0x00000ff0
    private const val MASK_FLAGS = 0x00fff000

    private const val CLASS_NULL = 0x00000000
    private const val CLASS_TEXT = 0x00000001
    private const val CLASS_NUMBER = 0x00000002
    private const val CLASS_PHONE = 0x00000003
    private const val CLASS_DATETIME = 0x00000004

    private const val VAR_URI = 0x00000010
    private const val VAR_EMAIL = 0x00000020
    private const val VAR_PASSWORD = 0x00000080
    private const val VAR_VISIBLE_PASSWORD = 0x00000090
    private const val VAR_WEB_PASSWORD = 0x000000e0
    private const val NUMBER_VAR_PASSWORD = 0x00000010

    private const val FLAG_MULTI_LINE = 0x00020000
    private const val FLAG_IME_MULTI_LINE = 0x00040000
    private const val FLAG_NO_SUGGESTIONS = 0x00080000

    // ── android.view.inputmethod.EditorInfo (valores estables) ─────────────
    private const val IME_MASK_ACTION = 0x000000ff
    private const val IME_FLAG_NO_ENTER_ACTION = 0x40000000

    fun classify(inputType: Int, imeOptions: Int): EditorProfile {
        val klass = inputType and MASK_CLASS
        val variation = inputType and MASK_VARIATION
        val flags = inputType and MASK_FLAGS

        val fieldClass = when (klass) {
            CLASS_NUMBER -> FieldClass.NUMBER
            CLASS_PHONE -> FieldClass.PHONE
            CLASS_DATETIME -> FieldClass.DATETIME
            CLASS_TEXT -> FieldClass.TEXT
            else -> FieldClass.NULL
        }

        val isPassword = when (fieldClass) {
            FieldClass.TEXT -> variation == VAR_PASSWORD ||
                variation == VAR_VISIBLE_PASSWORD ||
                variation == VAR_WEB_PASSWORD
            FieldClass.NUMBER -> variation == NUMBER_VAR_PASSWORD
            else -> false
        }

        val isEmail = fieldClass == FieldClass.TEXT && variation == VAR_EMAIL
        val isUri = fieldClass == FieldClass.TEXT && variation == VAR_URI
        val isMultiline = (flags and FLAG_MULTI_LINE) != 0 || (flags and FLAG_IME_MULTI_LINE) != 0
        val suggestionsAllowed = !isPassword && (flags and FLAG_NO_SUGGESTIONS) == 0

        val noEnterAction = (imeOptions and IME_FLAG_NO_ENTER_ACTION) != 0
        val enterAction = if (noEnterAction) {
            EditorAction.NONE
        } else {
            EditorAction.fromId(imeOptions and IME_MASK_ACTION)
        }

        return EditorProfile(
            fieldClass = fieldClass,
            isPassword = isPassword,
            isEmail = isEmail,
            isUri = isUri,
            isMultiline = isMultiline,
            suggestionsAllowed = suggestionsAllowed,
            enterAction = enterAction,
        )
    }
}
