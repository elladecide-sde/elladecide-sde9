package com.elladecide.lola.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoRepeatTest {

    @Test
    fun `la espera decrece monotonicamente`() {
        var d = AutoRepeat.INITIAL_DELAY_MS
        val seq = mutableListOf<Long>()
        repeat(20) {
            d = AutoRepeat.nextDelay(d)
            seq.add(d)
        }
        seq.zipWithNext().forEach { (a, b) -> assertTrue(b <= a) }
    }

    @Test
    fun `la espera no baja del piso minimo`() {
        var d = AutoRepeat.INITIAL_DELAY_MS
        repeat(100) { d = AutoRepeat.nextDelay(d) }
        assertEquals(AutoRepeat.MIN_DELAY_MS, d)
    }
}
