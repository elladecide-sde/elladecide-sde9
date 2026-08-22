package com.elladecide.lola.language

/**
 * Contrato del motor de sugerencias/predicción. Es puro (sin android.*) para
 * poder testearse en la JVM y para que cualquier implementación —incluida la
 * neurona binaria externa que se enchufará a futuro— cumpla la misma superficie.
 *
 * La implementación debe ser determinista, local (sin red) y NO persistir ni
 * aprender del texto del usuario salvo que se active explícitamente (privacidad).
 */
interface SuggestionProvider {

    /**
     * Devuelve candidatos ordenados (mejor primero) para [prefix], la palabra
     * en composición. [contextBefore] es el texto ya committeado antes de la
     * palabra, para predicción sensible al contexto (puede ser vacío).
     */
    fun suggest(prefix: String, contextBefore: String, limit: Int): List<String>
}
