package dev.pleos.starter.teum.vehicle

import org.junit.Assert.*
import org.junit.Test

class BrakeReadEpochTest {
    @Test fun unsafeThenSafeRejectsOldReadEvenWhenSafetyGenerationRepeats() {
        val owner = BrakeReadEpoch()
        val old = owner.current()
        owner.invalidate()
        assertFalse(owner.accepts(old, 100, 101))
        assertTrue(owner.accepts(owner.current(), 100, 101))
    }

    @Test fun delayedAndFutureRequestsCannotFreshenPedalOnDelivery() {
        val owner = BrakeReadEpoch()
        val token = owner.current()
        assertTrue(owner.accepts(token, 100, 3100))
        assertFalse(owner.accepts(token, 100, 3101))
        assertFalse(owner.accepts(token, 101, 100))
        assertFalse(owner.accepts(token, -1, 100))
    }
}
