package com.elladecide.lola.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorInfoClassifierTest {

    // Replicas locales de los valores estables de InputType/EditorInfo.
    private val CLASS_TEXT = 0x00000001
    private val CLASS_NUMBER = 0x00000002
    private val CLASS_PHONE = 0x00000003
    private val CLASS_DATETIME = 0x00000004
    private val VAR_URI = 0x00000010
    private val VAR_EMAIL = 0x00000020
    private val VAR_PASSWORD = 0x00000080
    private val VAR_VISIBLE_PASSWORD = 0x00000090
    private val VAR_WEB_PASSWORD = 0x000000e0
    private val NUMBER_VAR_PASSWORD = 0x00000010
    private val FLAG_MULTI_LINE = 0x00020000
    private val FLAG_NO_SUGGESTIONS = 0x00080000
    private val ACTION_NONE = 0x00000001
    private val ACTION_GO = 0x00000002
    private val ACTION_SEND = 0x00000004
    private val ACTION_DONE = 0x00000006
    private val FLAG_NO_ENTER_ACTION = 0x40000000

    @Test
    fun `texto plano por defecto`() {
        val p = EditorInfoClassifier.classify(CLASS_TEXT, 0)
        assertEquals(FieldClass.TEXT, p.fieldClass)
        assertFalse(p.isPassword)
        assertTrue(p.suggestionsAllowed)
        assertTrue(p.isCatalogEligible)
    }

    @Test
    fun `password de texto se detecta y deshabilita sugerencias`() {
        val p = EditorInfoClassifier.classify(CLASS_TEXT or VAR_PASSWORD, 0)
        assertTrue(p.isPassword)
        assertFalse(p.suggestionsAllowed)
        assertFalse(p.isCatalogEligible)
    }

    @Test
    fun `password web y visible tambien son password`() {
        assertTrue(EditorInfoClassifier.classify(CLASS_TEXT or VAR_WEB_PASSWORD, 0).isPassword)
        assertTrue(EditorInfoClassifier.classify(CLASS_TEXT or VAR_VISIBLE_PASSWORD, 0).isPassword)
    }

    @Test
    fun `password numerico se detecta`() {
        val p = EditorInfoClassifier.classify(CLASS_NUMBER or NUMBER_VAR_PASSWORD, 0)
        assertTrue(p.isPassword)
        assertEquals(FieldClass.NUMBER, p.fieldClass)
    }

    @Test
    fun `email y uri`() {
        assertTrue(EditorInfoClassifier.classify(CLASS_TEXT or VAR_EMAIL, 0).isEmail)
        assertTrue(EditorInfoClassifier.classify(CLASS_TEXT or VAR_URI, 0).isUri)
    }

    @Test
    fun `clases number phone datetime`() {
        assertEquals(FieldClass.NUMBER, EditorInfoClassifier.classify(CLASS_NUMBER, 0).fieldClass)
        assertEquals(FieldClass.PHONE, EditorInfoClassifier.classify(CLASS_PHONE, 0).fieldClass)
        assertEquals(FieldClass.DATETIME, EditorInfoClassifier.classify(CLASS_DATETIME, 0).fieldClass)
    }

    @Test
    fun `multilinea por flag`() {
        assertTrue(EditorInfoClassifier.classify(CLASS_TEXT or FLAG_MULTI_LINE, 0).isMultiline)
    }

    @Test
    fun `flag sin sugerencias`() {
        assertFalse(EditorInfoClassifier.classify(CLASS_TEXT or FLAG_NO_SUGGESTIONS, 0).suggestionsAllowed)
    }

    @Test
    fun `accion done`() {
        val p = EditorInfoClassifier.classify(CLASS_TEXT, ACTION_DONE)
        assertEquals(EditorAction.DONE, p.enterAction)
        assertEquals(EnterDecision.Action(6), p.enterDecision)
    }

    @Test
    fun `accion send`() {
        val p = EditorInfoClassifier.classify(CLASS_TEXT, ACTION_SEND)
        assertEquals(EditorAction.SEND, p.enterAction)
        assertEquals(EnterDecision.Action(4), p.enterDecision)
    }

    @Test
    fun `accion go`() {
        assertEquals(EditorAction.GO, EditorInfoClassifier.classify(CLASS_TEXT, ACTION_GO).enterAction)
    }

    @Test
    fun `accion none fuerza newline`() {
        assertEquals(EnterDecision.Newline, EditorInfoClassifier.classify(CLASS_TEXT, ACTION_NONE).enterDecision)
    }

    @Test
    fun `no enter action fuerza newline`() {
        val p = EditorInfoClassifier.classify(CLASS_TEXT, FLAG_NO_ENTER_ACTION)
        assertEquals(EnterDecision.Newline, p.enterDecision)
    }

    @Test
    fun `multilinea fuerza newline aunque haya accion`() {
        val p = EditorInfoClassifier.classify(CLASS_TEXT or FLAG_MULTI_LINE, ACTION_DONE)
        assertEquals(EnterDecision.Newline, p.enterDecision)
    }

    @Test
    fun `input null se clasifica como null y sin catalog`() {
        val p = EditorInfoClassifier.classify(0, 0)
        assertEquals(FieldClass.NULL, p.fieldClass)
        assertFalse(p.isCatalogEligible)
    }
}
