package dev.pleos.starter.teum.journey

import dev.pleos.starter.teum.records.ActivityOutcome
import org.junit.Assert.*
import org.junit.Test

class JourneyTimingTest {
    @Test fun wallClockJumpDoesNotAdvanceBreathing() {
        val f = JourneyFixture().apply { breathing() }
        f.clock.wall += 3_600_000
        f.clock.monotonic += 179_999
        val result = f.runtime.tick().snapshot
        assertEquals(JourneyPhase.MORNING_BREATHING, result.phase)
        assertEquals(179_999L, result.elapsedMs)
        assertEquals(1L, f.runtime.remainingMs)
    }

    @Test fun breathingAdvancesAtExactMonotonicBoundaryWithoutCompletingJourney() {
        val f = JourneyFixture().apply { breathing() }
        f.tick(179_999)
        val result = f.tick(1).snapshot
        assertEquals(JourneyPhase.INTENTION, result.phase)
        assertEquals(JourneyStatus.ACTIVE, result.status)
        assertNull(result.facts.finishedAtEpochMs)
    }

    @Test fun closingEyesDoesNotRestartOrShortenBreathing() {
        val f = JourneyFixture().apply { breathing(); tick(170_000) }
        f.send(JourneyRequest.SetEyesClosed(true))
        assertEquals("F1-03a", f.state.screenId)
        val result = f.tick(10_000).snapshot
        assertEquals(JourneyPhase.INTENTION, result.phase)
        assertFalse(result.eyesClosed)
        assertEquals(180_000L, result.elapsedMs)
    }

    @Test fun quickCountdownAndBreathingEachUseActualBoundary() {
        val f = JourneyFixture(JourneyRoute.QUICK_BREATHING)
        assertEquals(JourneyPhase.QUICK_COUNTDOWN, f.tick(2999).snapshot.phase)
        assertEquals(JourneyPhase.QUICK_BREATHING, f.tick(1).snapshot.phase)
        assertEquals(JourneyPhase.QUICK_BREATHING, f.tick(179_999).snapshot.phase)
        assertEquals(JourneyPhase.QUICK_FEELING, f.tick(1).snapshot.phase)
        assertEquals(JourneyStatus.ACTIVE, f.state.status)
    }

    @Test fun everyNapSelectionUsesFullSelectedMonotonicTimer() {
        for (minutes in listOf(10, 15, 20)) {
            val f = JourneyFixture(JourneyRoute.NAP, minutes).apply { nap() }
            assertEquals(JourneyPhase.NAP_SETTLING, f.tick(6999).snapshot.phase)
            assertEquals(JourneyPhase.NAP_SLEEPING, f.tick(1).snapshot.phase)
            f.tick(minutes * 60_000L - 7001)
            assertEquals(1L, f.runtime.remainingMs)
            val woke = f.tick(1).snapshot
            assertEquals(JourneyPhase.NAP_WAKING, woke.phase)
            assertEquals(minutes * 60_000L, woke.napSleptMs)
            assertFalse(woke.awakeAcknowledged)
        }
    }

    @Test fun delayedNapTickDoesNotConsumeUnobservedGradualWake() {
        val f = JourneyFixture(JourneyRoute.NAP, 10).apply { nap() }
        val late = f.tick(620_000).snapshot
        assertEquals(JourneyPhase.NAP_WAKING, late.phase)
        assertEquals(0L, late.phaseElapsedMs)
        assertEquals(JourneyPhase.NAP_WAKING, f.tick(7999).snapshot.phase)
        assertEquals(JourneyPhase.NAP_SUMMARY, f.tick(1).snapshot.phase)
        assertThrows(JourneyRejected::class.java) { f.send(JourneyRequest.Finish) }
    }

    @Test fun earlyWakeCountsOnlyActualSleepAndRequiresAcknowledgment() {
        val f = JourneyFixture(JourneyRoute.NAP).apply { nap(); tick(12_345) }
        f.send(JourneyRequest.WakeNow)
        assertEquals(12_345L, f.state.napSleptMs)
        assertThrows(JourneyRejected::class.java) { f.send(JourneyRequest.AcknowledgeAwake) }
        f.tick(8000)
        f.send(JourneyRequest.AcknowledgeAwake)
        val result = f.send(JourneyRequest.Finish).snapshot.toActivityRecord(f.clock.wall)
        assertEquals(20L, result.elapsedSeconds)
        assertEquals(ActivityOutcome.COMPLETED, result.outcome)
    }

    @Test fun missingWakeAcknowledgmentPromptsWithoutCompletingOrClaimingSoundSuccess() {
        val f = JourneyFixture(JourneyRoute.NAP, 10).apply { nap(); tick(600_000); tick(8000) }
        assertEquals(JourneyPhase.NAP_SUMMARY, f.tick(24_999).snapshot.phase)
        val result = f.tick(1)
        assertEquals(JourneyPhase.NAP_AWAKE_PROMPT, result.snapshot.phase)
        assertEquals(listOf(JourneyEffect.StopOwnedEffects, JourneyEffect.PhaseEntered(JourneyPhase.NAP_AWAKE_PROMPT),
            JourneyEffect.WakeReminder), result.effects)
        assertEquals(JourneyStatus.ACTIVE, result.snapshot.status)
        assertThrows(JourneyRejected::class.java) { f.send(JourneyRequest.Finish) }
    }

    @Test fun terminalElapsedAndFinishStayFrozenAcrossTickAndLaterSave() {
        val f = JourneyFixture().apply { intention(); send(JourneyRequest.SkipIntention) }
        f.send(JourneyRequest.ChooseAtmosphere(JourneyAtmosphere.FOCUS))
        f.clock.advance(1234)
        val finished = f.send(JourneyRequest.Finish).snapshot
        f.clock.advance(900_000)
        val saved = f.runtime.tick().snapshot.toActivityRecord(f.clock.wall)
        assertEquals(181L, saved.elapsedSeconds)
        assertEquals(1_800_000_000_000L, saved.journeyFacts?.startedAtEpochMs)
        assertEquals(1_800_000_181_234L, saved.journeyFacts?.finishedAtEpochMs)
        assertEquals(1_800_001_081_234L, saved.createdAtEpochMs)
        assertEquals(finished.elapsedMs, requireNotNull(f.runtime.state).elapsedMs)
        assertEquals(finished.recordId, saved.id)
    }

    @Test fun backgroundInterruptionNeverAccruesLaterTimeOrPromotesCompletion() {
        val f = JourneyFixture().apply { breathing(); tick(1234) }
        f.clock.advance(1000)
        f.runtime.interrupt(JourneyInterruption.BACKGROUND)
        f.clock.advance(99_000)
        val result = f.runtime.tick().snapshot.toActivityRecord(f.clock.wall)
        assertEquals(2L, result.elapsedSeconds)
        assertEquals(ActivityOutcome.INTERRUPTED, result.outcome)
        assertEquals(1_800_000_002_234L, result.journeyFacts?.finishedAtEpochMs)
        assertThrows(JourneyRejected::class.java) { f.send(JourneyRequest.Continue) }
    }

    @Test fun monotonicRollbackFailsClosedAtLastObservedDuration() {
        val f = JourneyFixture().apply { breathing(); tick(5000) }
        f.clock.monotonic = 1
        val result = f.runtime.tick().snapshot
        assertEquals(JourneyInterruption.CLOCK, result.interruption)
        assertEquals(5000L, result.elapsedMs)
        assertNull(result.facts.finishedAtEpochMs)
    }

    @Test fun previousReentryKeepsActualElapsedWithoutCompletingAbandonedBreathing() {
        val f = JourneyFixture().apply { breathing(); tick(25_000) }
        val before = f.state
        val returned = f.send(Previous).snapshot
        assertEquals(before.completedMask, returned.completedMask)
        assertEquals(25_000L, returned.elapsedMs)
        f.clock.advance(5000)
        f.send(JourneyRequest.ChooseFeeling(Feeling.HEAVY))
        assertEquals(180_000L, f.runtime.remainingMs)
        assertEquals(JourneyPhase.MORNING_BREATHING, f.tick(179_999).snapshot.phase)
        val after = f.tick(1).snapshot
        assertEquals(JourneyPhase.INTENTION, after.phase)
        assertEquals(210_000L, after.elapsedMs)
        assertEquals(before.sessionId, after.sessionId)
        assertEquals(before.recordId, after.recordId)
        assertNull(after.facts.finishedAtEpochMs)
        println("NATIVE_BACK_FACT {\"case\":\"timing-210000\",\"before\":${JourneySnapshotCodec.encode(before)},\"after\":${JourneySnapshotCodec.encode(after)}}")
        val stopped = f.send(JourneyRequest.Stop).snapshot.toActivityRecord(f.clock.wall)
        assertEquals(210L, stopped.elapsedSeconds)
        assertEquals(ActivityOutcome.INTERRUPTED, stopped.outcome)
    }

    @Test fun quickPreviousStartsAnotherFullBreathingIntervalWithoutNewCountdown() {
        val f = JourneyFixture(JourneyRoute.QUICK_BREATHING).apply { tick(3000); tick(180_000) }
        val first = f.state
        f.send(Previous)
        assertEquals(180_000L, f.runtime.remainingMs)
        assertEquals(JourneyPhase.QUICK_BREATHING, f.tick(179_999).snapshot.phase)
        val second = f.tick(1).snapshot
        assertEquals(JourneyPhase.QUICK_FEELING, second.phase)
        assertEquals(363_000L, second.elapsedMs)
        assertEquals(first.sessionId, second.sessionId)
        assertEquals(first.recordId, second.recordId)
        assertEquals(first.completedMask, second.completedMask)
        assertEquals(JourneyStatus.ACTIVE, second.status)
        assertNull(second.facts.mood)
        assertNull(second.facts.finishedAtEpochMs)
    }

    @Test fun previousReentersGreetingAndArrivalWithTheirOwnFullBudgets() {
        for ((route, budget) in listOf(JourneyRoute.MORNING to 7000L,
            JourneyRoute.EVENING to 6500L, JourneyRoute.SHORT_EVENING to 6500L)) {
            val f = JourneyFixture(route)
            val initial = f.state.phase
            f.tick(budget)
            val before = f.state
            f.send(Previous)
            assertEquals(initial, f.state.phase)
            assertEquals(initial, f.tick(budget - 1).snapshot.phase)
            val after = f.tick(1).snapshot
            assertEquals(before.phase, after.phase)
            assertEquals(budget * 2, after.elapsedMs)
            assertEquals(before.sessionId, after.sessionId)
            assertEquals(before.recordId, after.recordId)
        }
    }

    @Test fun reviewTimeNeverConsumesTheResumedArrivalBudgetOrStartsNap() {
        for (route in listOf(JourneyRoute.EVENING, JourneyRoute.SHORT_EVENING, JourneyRoute.NAP)) {
            val f = JourneyFixture(route)
            f.clock.advance(2000)
            f.send(Previous)
            assertEquals(JourneyPhase.ENTRY_REVIEW, f.tick(60_000).snapshot.phase)
            assertNull(f.runtime.remainingMs)
            f.send(JourneyRequest.Continue)
            assertEquals(62_000L, f.state.elapsedMs)
            assertEquals(0L, f.state.phaseElapsedMs)
            if (route == JourneyRoute.NAP) {
                assertEquals(JourneyPhase.NAP_CHECK, f.state.phase)
                assertNull(f.state.napStartedMs)
                assertNull(f.state.napSleptMs)
            } else {
                assertEquals(JourneyPhase.EVENING_ARRIVAL, f.tick(6499).snapshot.phase)
                assertEquals(JourneyPhase.EVENING_COLOR, f.tick(1).snapshot.phase)
                assertEquals(68_500L, f.state.elapsedMs)
            }
        }
    }

    @Test fun reselectedNapStartsItsOwnFullBudgetAfterReviewWithoutResettingTotal() {
        for ((choice, minutes) in listOf(EntryChoice.NAP_10 to 10, EntryChoice.NAP_15 to 15, EntryChoice.NAP_20 to 20)) {
            val f = JourneyFixture(JourneyRoute.NAP)
            f.send(Previous)
            f.clock.advance(25_000)
            val before = f.state
            f.send(ReconfigureEntry(choice))
            assertNull(f.state.napStartedMs)
            assertEquals(JourneyPhase.ENTRY_REVIEW, f.tick(5000).snapshot.phase)
            f.send(JourneyRequest.Continue)
            f.nap()
            assertEquals(30_000L, f.state.napStartedMs)
            assertEquals(minutes * 60_000L, f.runtime.remainingMs)
            f.tick(minutes * 60_000L - 1)
            assertEquals(1L, f.runtime.remainingMs)
            val after = f.tick(1).snapshot
            assertEquals(JourneyPhase.NAP_WAKING, after.phase)
            assertEquals(minutes * 60_000L, after.napSleptMs)
            assertEquals(30_000L + minutes * 60_000L, after.elapsedMs)
            assertEquals(before.sessionId, after.sessionId)
            assertNull(after.facts.finishedAtEpochMs)
            println("NATIVE_ENTRY_FACT {\"case\":\"nap-budget-$minutes\",\"before\":${JourneySnapshotCodec.encode(before)},\"after\":${JourneySnapshotCodec.encode(after)}}")
        }
    }

    @Test fun preparationAlternativeCountsPriorWorkAndRequiresFreshQuickCountdownAndBreathing() {
        val f = JourneyFixture(JourneyRoute.PREPARATION)
        f.send(JourneyRequest.RecordSpeech)
        val capture = f.runtime.token
        f.clock.advance(1750)
        f.send(JourneyRequest.SpeechDone)
        f.runtime.speechMeasured(capture, dev.pleos.starter.teum.records.SpeechMeasurement(1750))
        f.send(Previous)
        f.send(Previous)
        val before = f.state
        f.clock.advance(2000)
        f.send(ReconfigureEntry(EntryChoice.QUICK))
        assertEquals(0, f.state.completedMask)
        assertEquals(JourneyPhase.QUICK_COUNTDOWN, f.tick(2999).snapshot.phase)
        assertEquals(JourneyPhase.QUICK_BREATHING, f.tick(1).snapshot.phase)
        assertEquals(JourneyPhase.QUICK_BREATHING, f.tick(179_999).snapshot.phase)
        f.tick(1)
        val after = f.send(JourneyRequest.ChooseQuickMood(QuickMood.BETTER)).snapshot
        assertEquals(186_750L, after.elapsedMs)
        assertEquals(before.facts.firstSpeech, after.facts.firstSpeech)
        assertEquals(before.facts.startedAtEpochMs, after.facts.startedAtEpochMs)
        assertEquals(before.sessionId, after.sessionId)
        assertEquals(before.recordId, after.recordId)
        assertEquals(dev.pleos.starter.teum.records.JourneyKind.QUICK_BREATHING, after.facts.kind)
        assertEquals(7, after.completedMask)
        assertEquals(186L, after.toActivityRecord(f.clock.wall).elapsedSeconds)
        println("NATIVE_ENTRY_FACT {\"case\":\"quick-after-preparation\",\"before\":${JourneySnapshotCodec.encode(before)},\"after\":${JourneySnapshotCodec.encode(after)}}")
    }
}
