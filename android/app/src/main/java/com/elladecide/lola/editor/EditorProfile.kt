package com.elladecide.lola.editor

/**
 * Clase del campo que la app anfitriona está pidiendo (determina qué capa de
 * teclado mostrar y qué comportamientos de edición activar).
 */
enum class FieldClass { TEXT, NUMBER, PHONE, DATETIME, NULL }

/**
 * Acción del botón Enter, espejo de `EditorInfo.IME_ACTION_*`. [id] es el valor
 * estable y público de Android para pasarlo a `performEditorAction()`.
 */
enum class EditorAction(val id: Int) {
    UNSPECIFIED(0),
    NONE(1),
    GO(2),
    SEARCH(3),
    SEND(4),
    NEXT(5),
    DONE(6),
    PREVIOUS(7);

    companion object {
        fun fromId(id: Int): EditorAction = entries.firstOrNull { it.id == id } ?: UNSPECIFIED
    }
}

/** Decisión pura de qué hacer con Enter: nueva línea o despachar una acción IME. */
sealed interface EnterDecision {
    data object Newline : EnterDecision
    data class Action(val id: Int) : EnterDecision
}

/**
 * Perfil del editor activo, derivado de `inputType` e `imeOptions`. Es puro:
 * no depende de `android.*`, así que se puede testear en la JVM.
 */
data class EditorProfile(
    val fieldClass: FieldClass,
    val isPassword: Boolean,
    val isEmail: Boolean,
    val isUri: Boolean,
    val isMultiline: Boolean,
    val suggestionsAllowed: Boolean,
    val enterAction: EditorAction,
) {
    /** Qué hacer con la tecla Enter según el campo. */
    val enterDecision: EnterDecision
        get() = when {
            isMultiline -> EnterDecision.Newline
            enterAction == EditorAction.NONE || enterAction == EditorAction.UNSPECIFIED -> EnterDecision.Newline
            else -> EnterDecision.Action(enterAction.id)
        }

    /** El catálogo LOLA solo aplica a campos de texto libre (no password/URI/…). */
    val isCatalogEligible: Boolean
        get() = fieldClass == FieldClass.TEXT && !isPassword && !isEmail && !isUri

    companion object {
        val EMPTY = EditorProfile(
            fieldClass = FieldClass.TEXT,
            isPassword = false,
            isEmail = false,
            isUri = false,
            isMultiline = false,
            suggestionsAllowed = true,
            enterAction = EditorAction.UNSPECIFIED,
        )
    }
}
