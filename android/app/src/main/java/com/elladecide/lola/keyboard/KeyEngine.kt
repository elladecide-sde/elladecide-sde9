package com.elladecide.lola.keyboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.elladecide.lola.editor.EditorAction
import com.elladecide.lola.editor.EditorProfile

/**
 * Efecto que una tecla produce sobre el mundo exterior (la capa superior decide
 * a dónde enrutarlo: búsqueda LOLA o app anfitriona).
 */
sealed interface KeyEffect {
    data class Commit(val text: String) : KeyEffect
    data object Delete : KeyEffect
    data object Enter : KeyEffect
    data object MoveLeft : KeyEffect
    data object MoveRight : KeyEffect
    data object Undo : KeyEffect
    data object ToggleLola : KeyEffect
}

/**
 * Motor de teclas: estado del teclado base (capa + shift) y resolución de cada
 * tecla a un [KeyEffect]. No conoce android.*, así que es testeable en la JVM.
 *
 * [layer] y [shift] son snapshot state (`mutableStateOf`) para que Compose
 * recomponga al cambiar de capa/estado; la lógica (labels, pesos, transiciones)
 * sigue siendo pura y determinista. La UI renderiza a partir de
 * [layout]/[bottomRow] y consume [press].
 */
class KeyEngine {

    var layer by mutableStateOf(KeyboardLayer.LETTERS)
        private set

    var shift by mutableStateOf(ShiftState.MINUSCULAS)
        private set

    val shiftActive: Boolean get() = shift.isActive

    /** Body de la capa actual (letras, símbolos o navegación). */
    fun layout(): List<List<Key>> = when (layer) {
        KeyboardLayer.LETTERS -> Layouts.LETTERS
        KeyboardLayer.SYMBOLS -> Layouts.SYMBOLS
        KeyboardLayer.CURSOR -> Layouts.CURSOR
        KeyboardLayer.EMOJI -> Layouts.LETTERS
    }

    /** Fila inferior compartida (123 · coma · emoji · espacio · punto · enter). */
    fun bottomRow(): List<Key> = Layouts.BOTTOM_ROW

    /** Alternativas de long-press según la capa, respetando shift en letras. */
    fun alternatives(key: Key): List<String> {
        if (key.type != KeyType.CHAR) return emptyList()
        val base = when (layer) {
            KeyboardLayer.LETTERS -> Layouts.ALTERNATIVES[key.char?.lowercase()] ?: return emptyList()
            KeyboardLayer.SYMBOLS -> Layouts.SYMBOL_ALTERNATIVES[key.char] ?: return emptyList()
            else -> return emptyList()
        }
        return if (shiftActive && layer == KeyboardLayer.LETTERS) base.map { it.uppercase() } else base
    }

    /** Etiqueta visible de la tecla (el Enter refleja la acción del editor). */
    fun label(key: Key, profile: EditorProfile): String = when (key.type) {
        KeyType.CHAR -> {
            val c = key.char ?: ""
            if (shiftActive && layer == KeyboardLayer.LETTERS) c.uppercase() else c
        }
        KeyType.SHIFT -> if (shift == ShiftState.BLOQUEO) "⇪" else "⇧"
        KeyType.BACKSPACE -> "⌫"
        KeyType.SPACE -> "␣"
        KeyType.ENTER -> enterLabel(profile)
        KeyType.LAYER -> if (layer == KeyboardLayer.SYMBOLS) "ABC" else "123"
        KeyType.EMOJI -> "😀"
        KeyType.LOLA -> "LOLA"
        KeyType.LEFT -> "◀"
        KeyType.RIGHT -> "▶"
        KeyType.UNDO -> "↶"
        KeyType.COMMA -> ","
        KeyType.DOT -> "."
        KeyType.CURSOR -> "◀▶"
        KeyType.BACK -> "ABC"
    }

    /** Peso relativo de la tecla en su fila (el espacio domina la fila inferior). */
    fun weight(key: Key): Float = when (key.type) {
        KeyType.CHAR -> 1f
        KeyType.SHIFT, KeyType.BACKSPACE -> 1.5f
        KeyType.SPACE -> 4f
        KeyType.ENTER -> 2f
        KeyType.LAYER, KeyType.EMOJI, KeyType.LOLA, KeyType.CURSOR -> 1.5f
        KeyType.LEFT, KeyType.RIGHT, KeyType.UNDO -> 1f
        KeyType.COMMA, KeyType.DOT -> 1.5f
        KeyType.BACK -> 2f
    }

    /** Resalta la tecla (shift activo, capa emoji, capa cursor). */
    fun isActive(key: Key): Boolean = when (key.type) {
        KeyType.SHIFT -> shiftActive
        KeyType.EMOJI -> layer == KeyboardLayer.EMOJI
        KeyType.LAYER -> layer == KeyboardLayer.SYMBOLS
        KeyType.CURSOR -> layer == KeyboardLayer.CURSOR
        else -> false
    }

    /**
     * Descripción legible de la tecla para accesibilidad (TalkBack). A
     * diferencia de [label], usa el nombre de la acción, no el glifo visual.
     */
    fun contentDescription(key: Key, profile: EditorProfile): String = when (key.type) {
        KeyType.CHAR -> key.char ?: ""
        KeyType.SHIFT -> "Mayúsculas"
        KeyType.BACKSPACE -> "Retroceso"
        KeyType.SPACE -> "Espacio"
        KeyType.ENTER -> enterDescription(profile)
        KeyType.LAYER -> if (layer == KeyboardLayer.SYMBOLS) "Letras" else "Números y símbolos"
        KeyType.EMOJI -> "Emojis"
        KeyType.LOLA -> "Modo LOLA"
        KeyType.LEFT -> "Mover cursor a la izquierda"
        KeyType.RIGHT -> "Mover cursor a la derecha"
        KeyType.UNDO -> "Deshacer"
        KeyType.COMMA -> "Coma"
        KeyType.DOT -> "Punto"
        KeyType.CURSOR -> "Teclado de cursor"
        KeyType.BACK -> "Volver al teclado de letras"
    }

    /** Estado a anunciar (o null) cuando la tecla está activa/pulsada. */
    fun stateDescription(key: Key): String? = when {
        key.type == KeyType.SHIFT && shift == ShiftState.BLOQUEO -> "Bloqueo de mayúsculas activado"
        key.type == KeyType.SHIFT && shift == ShiftState.MAYUSCULA -> "Mayúscula para una letra"
        key.type == KeyType.EMOJI && layer == KeyboardLayer.EMOJI -> "Teclado de emojis activo"
        key.type == KeyType.LAYER && layer == KeyboardLayer.SYMBOLS -> "Teclado de símbolos activo"
        key.type == KeyType.CURSOR && layer == KeyboardLayer.CURSOR -> "Teclado de cursor activo"
        else -> null
    }

    private fun enterDescription(profile: EditorProfile): String {
        if (profile.isMultiline) return "Nueva línea"
        return when (profile.enterAction) {
            EditorAction.DONE -> "Listo"
            EditorAction.GO -> "Ir"
            EditorAction.NEXT -> "Siguiente"
            EditorAction.SEARCH -> "Buscar"
            EditorAction.SEND -> "Enviar"
            EditorAction.PREVIOUS -> "Anterior"
            EditorAction.NONE, EditorAction.UNSPECIFIED -> "Nueva línea"
        }
    }

    /** Fija mayúsculas (doble tap / long-press sobre Shift). */
    fun lockShift() {
        shift = ShiftState.BLOQUEO
    }

    /**
     * Consume el shift temporal (una sola mayúscula) sin tocar el bloqueo.
     * Útil al elegir una alternativa de long-press, que ya llega capitalizada.
     */
    fun consumeTemporaryShift() {
        if (shift == ShiftState.MAYUSCULA) shift = ShiftState.MINUSCULAS
    }

    /**
     * Procesa una tecla: muta el estado interno (shift/capa) y devuelve el
     * efecto que la capa superior debe ejecutar, o null si es solo de estado.
     */
    fun press(key: Key): KeyEffect? = when (key.type) {
        KeyType.CHAR -> {
            val text = key.char ?: return null
            val result = if (layer == KeyboardLayer.LETTERS) shift.capitalize(text) else text
            if (layer == KeyboardLayer.LETTERS && shift == ShiftState.MAYUSCULA) {
                shift = ShiftState.MINUSCULAS
            }
            KeyEffect.Commit(result)
        }
        KeyType.SHIFT -> { shift = shift.toggle(); null }
        KeyType.BACKSPACE -> KeyEffect.Delete
        KeyType.SPACE -> KeyEffect.Commit(" ")
        KeyType.ENTER -> KeyEffect.Enter
        KeyType.LAYER -> {
            layer = if (layer == KeyboardLayer.SYMBOLS) KeyboardLayer.LETTERS else KeyboardLayer.SYMBOLS
            null
        }
        KeyType.EMOJI -> {
            layer = if (layer == KeyboardLayer.EMOJI) KeyboardLayer.LETTERS else KeyboardLayer.EMOJI
            null
        }
        KeyType.LOLA -> KeyEffect.ToggleLola
        KeyType.LEFT -> KeyEffect.MoveLeft
        KeyType.RIGHT -> KeyEffect.MoveRight
        KeyType.UNDO -> KeyEffect.Undo
        KeyType.COMMA -> KeyEffect.Commit(",")
        KeyType.DOT -> KeyEffect.Commit(".")
        KeyType.CURSOR -> {
            layer = if (layer == KeyboardLayer.CURSOR) KeyboardLayer.LETTERS else KeyboardLayer.CURSOR
            null
        }
        KeyType.BACK -> {
            layer = KeyboardLayer.LETTERS
            null
        }
    }

    private fun enterLabel(profile: EditorProfile): String {
        if (profile.isMultiline) return "⏎"
        return when (profile.enterAction) {
            EditorAction.DONE -> "✓"
            EditorAction.GO -> "→"
            EditorAction.NEXT -> "↓"
            EditorAction.SEARCH -> "🔍"
            EditorAction.SEND -> "➤"
            EditorAction.PREVIOUS -> "↑"
            EditorAction.NONE, EditorAction.UNSPECIFIED -> "⏎"
        }
    }
}