package dev.pleos.starter.teum.location

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pleos.starter.teum.preferences.JourneyNotification
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeArrivalLedgerStoreTest {
    @Test fun nativeRecreationPreservesVisitOutcomeAndCooldown() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "arrival_test_${UUID.randomUUID()}"
        try {
            val storage = context.getSharedPreferences(namespace, 0)
            val ledger = ArrivalLedger(mapOf(JourneyNotification.MORNING to VisitEntry(
                "2026-11-11", "target-hash", 2, true, "token", SuggestionOutcome.SKIPPED
            )), 123456L, 120000L)
            NativeArrivalLedgerStore(storage).persist(ledger)
            assertEquals(ledger, NativeArrivalLedgerStore(
                context.getSharedPreferences(namespace, 0)).load())
        } finally {
            context.deleteSharedPreferences(namespace)
        }
    }
}
