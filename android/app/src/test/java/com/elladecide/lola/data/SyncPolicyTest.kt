package com.elladecide.lola.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPolicyTest {

    private fun meta(count: Int = 0, hash: String = "abc", lastVerifiedAt: Long = 0L) =
        SyncMeta(revision = 1, hash = hash, count = count, lastVerifiedAt = lastVerifiedAt)

    // ── countCorrupted (chequeo barato) ────────────────────────────────────

    @Test
    fun `sin sync previo el conteo no es referencia`() {
        assertFalse(SyncPolicy.countCorrupted(meta(count = 0), localCount = 0))
        assertFalse(SyncPolicy.countCorrupted(meta(count = 0), localCount = 123))
    }

    @Test
    fun `conteo coincide - integro`() {
        assertFalse(SyncPolicy.countCorrupted(meta(count = 5), localCount = 5))
    }

    @Test
    fun `conteo difiere - corrupto`() {
        assertTrue(SyncPolicy.countCorrupted(meta(count = 5), localCount = 4))
        assertTrue(SyncPolicy.countCorrupted(meta(count = 5), localCount = 6))
    }

    // ── needsFullVerify (chequeo completo throttled) ──────────────────────

    @Test
    fun `ya verificado en proceso - no verifica`() {
        assertFalse(SyncPolicy.needsFullVerify(meta(lastVerifiedAt = 0L), verifiedThisProcess = true, now = 1000L))
    }

    @Test
    fun `nunca verificado - verifica`() {
        assertTrue(SyncPolicy.needsFullVerify(meta(lastVerifiedAt = 0L), verifiedThisProcess = false, now = 1000L))
    }

    @Test
    fun `sin sync previo no hay nada que verificar`() {
        assertFalse(
            SyncPolicy.needsFullVerify(
                meta(hash = "", lastVerifiedAt = 0L),
                verifiedThisProcess = false,
                now = 1000L,
            ),
        )
    }

    @Test
    fun `verificado hace menos del intervalo - no verifica`() {
        val now = 1000L + SyncPolicy.VERIFY_INTERVAL_MS - 1
        assertFalse(SyncPolicy.needsFullVerify(meta(lastVerifiedAt = 1000L), verifiedThisProcess = false, now = now))
    }

    @Test
    fun `verificado hace mas del intervalo - verifica`() {
        val now = 1000L + SyncPolicy.VERIFY_INTERVAL_MS
        assertTrue(SyncPolicy.needsFullVerify(meta(lastVerifiedAt = 1000L), verifiedThisProcess = false, now = now))
    }

    // ── needsServerSync ───────────────────────────────────────────────────

    @Test
    fun `hash igual - al dia`() {
        assertFalse(SyncPolicy.needsServerSync(meta(hash = "abc"), serverHash = "abc"))
    }

    @Test
    fun `hash distinto o vacio - hay que sincronizar`() {
        assertTrue(SyncPolicy.needsServerSync(meta(hash = "abc"), serverHash = "abd"))
        assertTrue(SyncPolicy.needsServerSync(meta(hash = ""), serverHash = "abc"))
    }
}