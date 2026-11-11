package dev.pleos.starter.teum.journey

import dev.pleos.starter.teum.records.ActivityOutcome
import dev.pleos.starter.teum.records.EveningReflection
import dev.pleos.starter.teum.records.HistoryPolicy
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class JourneyPersistenceTest {
    @Test fun expiredUnsavedReflectionIsRedactedDurablyWithoutChangingActivityEvidence() {
        val f = JourneyFixture(JourneyRoute.EVENING).apply {
            send(JourneyRequest.Continue)
            send(JourneyRequest.ChooseDayColor(JourneyAtmosphere.WARM))
            send(JourneyRequest.SubmitStory("Private unsaved reflection"))
        }
        val before = f.state
        val zone = ZoneId.of("UTC")
        val expiry = Instant.ofEpochMilli(requireNotNull(before.facts.startedAtEpochMs))
            .atZone(zone).toLocalDate().plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        f.clock.wall = expiry - 1
        f.runtime.maintainReflection(HistoryPolicy(zone, true))
        assertEquals(before, f.state)
        f.clock.wall = expiry
        f.runtime.maintainReflection(HistoryPolicy(zone, false))
        assertEquals(before, f.state)
        f.runtime.maintainReflection(HistoryPolicy(zone, true))
        val expected = before.copy(facts = before.facts.copy(reflection = EveningReflection()))
        assertEquals(expected, f.state)
        assertEquals(expected, JourneySnapshotCodec.decode(requireNotNull(f.store.raw)))
        val recovered = requireNotNull(JourneyRuntime(f.clock, f.store).recover()).snapshot
        assertEquals(EveningReflection(), recovered.facts.reflection)
        assertEquals(before.elapsedMs, recovered.elapsedMs)
        assertEquals(before.phase, recovered.phase)
        assertEquals(before.recordId, recovered.recordId)
    }

    @Test fun failedReflectionRedactionPreservesThePreviousOwnerAndCheckpoint() {
        val f = JourneyFixture(JourneyRoute.EVENING).apply {
            send(JourneyRequest.Continue)
            send(JourneyRequest.ChooseDayColor(JourneyAtmosphere.CALM))
            send(JourneyRequest.SubmitStory("Still awaiting durable redaction"))
        }
        val before = f.state
        val raw = f.store.raw
        f.clock.advance(3 * 86_400_000L)
        f.store.failWrites = true
        assertThrows(IOException::class.java) {
            f.runtime.maintainReflection(HistoryPolicy(ZoneId.of("UTC"), true))
        }
        assertEquals(before, f.state)
        assertEquals(raw, f.store.raw)
    }

    @Test fun activeRecoveryFreezesLastCheckpointWithoutFabricatingFinishOrResuming() {
        val f = JourneyFixture().apply { intention(); send(JourneyRequest.SubmitIntention("Persist this")) }
        val id = f.state.sessionId
        val recordId = f.state.recordId
        f.clock.advance(600_000)
        val owner = JourneyRuntime(f.clock, f.store)
        val recovered = requireNotNull(owner.recover())
        assertEquals(JourneyStatus.RECOVERED_INTERRUPTED, recovered.snapshot.status)
        assertEquals(180_000L, recovered.snapshot.elapsedMs)
        assertNull(recovered.snapshot.facts.finishedAtEpochMs)
        assertEquals("Persist this", recovered.snapshot.facts.intention)
        assertEquals(id, recovered.snapshot.sessionId)
        assertEquals(recordId, recovered.snapshot.toActivityRecord(f.clock.wall).id)
        assertEquals(listOf(JourneyEffect.StopOwnedEffects), recovered.effects)
        assertThrows(JourneyRejected::class.java) { owner.request(owner.token, JourneyRequest.ConfirmIntention) }
    }

    @Test fun finishedRecoveryPreservesFinishAndStableRecordIdentityAcrossSaveRetry() {
        val f = JourneyFixture(JourneyRoute.QUICK_BREATHING).apply { tick(3000); tick(180_000) }
        val done = f.send(JourneyRequest.ChooseQuickMood(QuickMood.TIRED)).snapshot
        f.clock.advance(100_000)
        val recovered = requireNotNull(JourneyRuntime(f.clock, f.store).recover()).snapshot
        val firstSave = recovered.toActivityRecord(f.clock.wall)
        val retry = recovered.toActivityRecord(f.clock.wall + 1000)
        assertEquals(done.facts, recovered.facts)
        assertEquals(ActivityOutcome.COMPLETED, retry.outcome)
        assertEquals(done.recordId, firstSave.id)
        assertEquals(firstSave.id, retry.id)
        assertEquals(183L, retry.elapsedSeconds)
    }

    @Test fun liveLifecycleInterruptionPersistsActualFinalReadingForRecreation() {
        val f = JourneyFixture(JourneyRoute.NAP).apply { nap() }
        f.clock.advance(12_999)
        val stopped = f.runtime.interrupt(JourneyInterruption.BACKGROUND).snapshot
        f.clock.advance(100_000)
        val recovered = requireNotNull(JourneyRuntime(f.clock, f.store).recover()).snapshot
        assertEquals(stopped, recovered)
        assertEquals(12_999L, recovered.elapsedMs)
        assertEquals(1_800_000_012_999L, recovered.facts.finishedAtEpochMs)
    }

    @Test fun failedTransitionWriteDoesNotPublishUnpersistedUserFacts() {
        val f = JourneyFixture().apply { intention() }
        val before = f.state
        val raw = f.store.raw
        f.store.failWrites = true
        assertThrows(IOException::class.java) { f.send(JourneyRequest.SubmitIntention("Unsaved")) }
        assertEquals(before, f.state)
        assertEquals(raw, f.store.raw)
    }

    @Test fun failedSafetyWriteStillLeavesMemoryTerminalAndBlocksAllNewEffects() {
        val f = JourneyFixture(JourneyRoute.PREPARATION)
        f.store.failWrites = true
        assertThrows(IOException::class.java) { f.runtime.interrupt(JourneyInterruption.SAFETY) }
        assertEquals(JourneyStatus.SAFETY_INTERRUPTED, f.state.status)
        assertThrows(JourneyRejected::class.java) { f.send(JourneyRequest.RecordSpeech) }
    }

    @Test fun failedStartWriteDoesNotCreateAnActiveOwner() {
        val store = TestJourneyStore().apply { failWrites = true }
        val runtime = JourneyRuntime(TestJourneyClock(), store)
        assertThrows(IOException::class.java) { runtime.start(JourneyPlan(JourneyRoute.MORNING)) }
        assertNull(runtime.state)
    }

    @Test fun corruptStoreIsPreservedAndCannotBeBypassedByStartingAgain() {
        val store = TestJourneyStore().apply { raw = "{\"version\":99}" }
        val owner = JourneyRuntime(TestJourneyClock(), store)
        assertThrows(IllegalArgumentException::class.java) { owner.recover() }
        assertEquals("{\"version\":99}", store.raw)
        assertThrows(IllegalStateException::class.java) { owner.start(JourneyPlan(JourneyRoute.MORNING)) }
        assertNull(owner.state)
    }

    @Test fun codecRoundTripsAllRealFactsAndRejectsMalformedStorage() {
        val f = JourneyFixture(JourneyRoute.EVENING).apply {
            send(JourneyRequest.Continue)
            send(JourneyRequest.ChooseDayColor(JourneyAtmosphere.WARM))
            send(JourneyRequest.SubmitStory("Unicode 이야기\n\"quoted\""))
        }
        val raw = JourneySnapshotCodec.encode(f.state)
        assertEquals(f.state, JourneySnapshotCodec.decode(raw))
        val invalid = listOf(
            raw + "{}",
            raw.replace("\"version\":1", "\"version\":2"),
            raw.replace("\"version\":1", "\"version\":\"1\""),
            raw.replace("\"version\":1", "\"version\":1,\"version\":1"),
            raw.replace("\"elapsedMs\":0", "\"elapsedMs\":0.5"),
            raw.replace("\"elapsedMs\":0", "\"elapsedMs\":-1"),
            raw.replace("\"elapsedMs\":0", "\"elapsedMs\":9223372036854775808"),
            raw.replace("\"phaseStartedMs\":0", "\"phaseStartedMs\":1"),
            raw.replace("\"completedMask\":7", "\"completedMask\":999999"),
            raw.replace("\"status\":\"ACTIVE\"", "\"status\":\"COMPLETED\""),
            raw.replace("\"napMinutes\":20", "\"napMinutes\":11"),
            raw.replace("\"phase\":\"EVENING_WEEK\"", "\"phase\":\"NAP_SLEEPING\""),
            raw.replace("\"eyesClosed\":false", "\"eyesClosed\":true"),
            raw.replace("\"eyesClosed\":false", "\"eyesClosed\":\"false\"")
        )
        for (input in invalid) {
            assertNotEquals(raw, input)
            assertThrows(InvalidJourneySnapshot::class.java) { JourneySnapshotCodec.decode(input) }
        }
    }

    @Test fun independentJourneysHaveUniqueNativeIdsAndUnsavedOwnerCannotBeOverwritten() {
        val first = JourneyFixture()
        val second = JourneyFixture()
        assertNotEquals(first.state.sessionId, second.state.sessionId)
        assertThrows(IllegalStateException::class.java) { first.runtime.start(JourneyPlan(JourneyRoute.NAP)) }
        assertThrows(IllegalStateException::class.java) { first.runtime.clear() }
        first.send(JourneyRequest.Stop)
        val id = first.state.sessionId
        first.runtime.clear()
        assertNull(first.store.raw)
        assertNotEquals(id, first.runtime.start(JourneyPlan(JourneyRoute.NAP)).snapshot.sessionId)
    }

    @Test fun badMeasurementNeverPublishesPartialSpeechFacts() {
        val f = JourneyFixture(JourneyRoute.PREPARATION)
        f.send(JourneyRequest.RecordSpeech)
        f.send(JourneyRequest.SpeechDone)
        assertThrows(IllegalArgumentException::class.java) {
            f.runtime.speechMeasured(f.runtime.token, dev.pleos.starter.teum.records.SpeechMeasurement(100, 101))
        }
        assertNull(f.state.facts.firstSpeech)
        assertEquals(JourneyPhase.SPEECH_FIRST, f.state.phase)
    }

    @Test fun bothReviewPhasesRoundTripAndRecoverAsInterruptedOwners() {
        val cases = listOf(JourneyRoute.SHORT_MORNING to false, JourneyRoute.NAP to false,
            JourneyRoute.PREPARATION to false, JourneyRoute.EVENING to false,
            JourneyRoute.SHORT_EVENING to false, JourneyRoute.EVENING to true,
            JourneyRoute.SHORT_EVENING to true)
        for ((route, dayReview) in cases) {
            val f = JourneyFixture(route)
            if (dayReview) {
                f.tick(6500)
                f.send(JourneyRequest.ChooseDayColor(JourneyAtmosphere.FLEX))
                if (route == JourneyRoute.EVENING) f.send(JourneyRequest.SubmitStory("Retained review fixture"))
            } else if (route == JourneyRoute.PREPARATION) {
                f.send(JourneyRequest.RecordSpeech)
                val capture = f.runtime.token
                f.clock.advance(750)
                f.send(JourneyRequest.SpeechDone)
                f.runtime.speechMeasured(capture, dev.pleos.starter.teum.records.SpeechMeasurement(750))
                f.send(Previous)
            }
            f.clock.advance(2345)
            f.send(if (dayReview) ReviewDay else Previous)
            val before = f.state
            assertEquals(before, JourneySnapshotCodec.decode(requireNotNull(f.store.raw)))
            f.clock.advance(600_000)
            val owner = JourneyRuntime(f.clock, f.store)
            val recovered = requireNotNull(owner.recover())
            assertEquals(JourneyStatus.RECOVERED_INTERRUPTED, recovered.snapshot.status)
            assertEquals(before.phase, recovered.snapshot.phase)
            assertEquals(before.plan, recovered.snapshot.plan)
            assertEquals(before.elapsedMs, recovered.snapshot.elapsedMs)
            assertEquals(before.completedMask, recovered.snapshot.completedMask)
            assertEquals(before.facts, recovered.snapshot.facts)
            assertEquals(before.sessionId, recovered.snapshot.sessionId)
            assertEquals(before.recordId, recovered.snapshot.recordId)
            assertNull(recovered.snapshot.facts.finishedAtEpochMs)
            assertEquals(ActivityOutcome.INTERRUPTED, recovered.snapshot.toActivityRecord(f.clock.wall).outcome)
            assertEquals(listOf(JourneyEffect.StopOwnedEffects), recovered.effects)
            assertThrows(JourneyRejected::class.java) { owner.request(owner.token, Previous) }
            assertThrows(JourneyRejected::class.java) { owner.request(owner.token, JourneyRequest.Continue) }
            println("NATIVE_BACK_FACT {\"case\":\"recovery-$route-$dayReview\",\"before\":${JourneySnapshotCodec.encode(before)},\"after\":${JourneySnapshotCodec.encode(recovered.snapshot)}}")
        }
    }

    @Test fun failedPreviousReviewAndEntryResumeWritesKeepThePreviousCheckpoint() {
        for (action in listOf("previous", "day", "resume")) {
            val f = when (action) {
                "previous" -> JourneyFixture().apply { breathing(); tick(1234) }
                "day" -> JourneyFixture(JourneyRoute.SHORT_EVENING).apply {
                    tick(6500); send(JourneyRequest.ChooseDayColor(JourneyAtmosphere.CALM))
                }
                else -> JourneyFixture(JourneyRoute.NAP).apply { send(Previous) }
            }
            val before = f.state
            val raw = f.store.raw
            f.clock.advance(99)
            f.store.failWrites = true
            val intent = when (action) {
                "previous" -> Previous
                "day" -> ReviewDay
                else -> JourneyRequest.Continue
            }
            assertThrows(IOException::class.java) { f.send(intent) }
            assertEquals(before, f.state)
            assertEquals(raw, f.store.raw)
            f.store.failWrites = false
            val accepted = f.send(intent).snapshot
            assertEquals(before.elapsedMs + 99, accepted.elapsedMs)
            assertEquals(before.completedMask, accepted.completedMask)
        }
    }

    @Test fun oldVersionOneSnapshotStillLoadsAndInvalidReviewMembershipStaysIntact() {
        val old = """{"version":1,"route":"MORNING","napMinutes":20,"sessionId":"old-native","recordId":"old-record","phase":"MORNING_GREETING","status":"ACTIVE","facts":{"kind":"MORNING","nativeActivityId":"old-native","startedAtEpochMs":1800000000000},"elapsedMs":1234,"phaseStartedMs":0,"observedWallMs":1800000001234,"completedMask":0,"playback":"UNREQUESTED","speechCapture":"IDLE","awakeAcknowledged":false,"eyesClosed":false,"revision":0}"""
        val decoded = JourneySnapshotCodec.decode(old)
        assertEquals("old-native", decoded.sessionId)
        assertEquals("old-record", decoded.recordId)
        assertEquals(1234L, decoded.elapsedMs)
        assertEquals(6, decoded.plan.totalSteps)
        val oldStore = TestJourneyStore().apply { raw = old }
        val recovered = requireNotNull(JourneyRuntime(TestJourneyClock(), oldStore).recover()).snapshot
        assertEquals(JourneyStatus.RECOVERED_INTERRUPTED, recovered.status)
        assertEquals(1234L, recovered.elapsedMs)
        for (phase in listOf("ENTRY_REVIEW", "EVENING_DAY_REVIEW", "UNKNOWN_REVIEW")) {
            val invalid = old.replace("MORNING_GREETING", phase)
            val store = TestJourneyStore().apply { raw = invalid }
            assertThrows(InvalidJourneySnapshot::class.java) { JourneyRuntime(TestJourneyClock(), store).recover() }
            assertEquals(invalid, store.raw)
        }
    }

    @Test fun lifecycleInterruptionFreezesBothReviewOwnersAndStopsTheirEffects() {
        for (dayReview in listOf(false, true)) {
            for (reason in listOf(JourneyInterruption.BACKGROUND, JourneyInterruption.SAFETY)) {
                val f = if (dayReview) JourneyFixture(JourneyRoute.SHORT_EVENING).apply {
                    tick(6500); send(JourneyRequest.ChooseDayColor(JourneyAtmosphere.CALM)); send(ReviewDay)
                } else JourneyFixture(JourneyRoute.NAP).apply { send(Previous) }
                val before = f.state
                f.clock.advance(1234)
                val stopped = f.runtime.interrupt(reason)
                assertEquals(before.elapsedMs + 1234, stopped.snapshot.elapsedMs)
                assertEquals(before.completedMask, stopped.snapshot.completedMask)
                assertEquals(listOf(JourneyEffect.StopOwnedEffects), stopped.effects)
                assertEquals(if (reason == JourneyInterruption.SAFETY) JourneyStatus.SAFETY_INTERRUPTED
                    else JourneyStatus.INTERRUPTED, stopped.snapshot.status)
                f.clock.advance(99_000)
                assertEquals(stopped.snapshot, f.runtime.tick().snapshot)
                assertThrows(JourneyRejected::class.java) { f.send(Previous) }
                assertEquals(stopped.snapshot,
                    requireNotNull(JourneyRuntime(f.clock, f.store).recover()).snapshot)
            }
        }
    }

    @Test fun failedEntrySelectionKeepsTheOriginalOwnerTokenAndDurableCheckpoint() {
        val cases = listOf(JourneyRoute.SHORT_MORNING to EntryChoice.MORNING_FULL,
            JourneyRoute.NAP to EntryChoice.NAP_15, JourneyRoute.PREPARATION to EntryChoice.QUICK,
            JourneyRoute.EVENING to EntryChoice.EVENING_SHORT,
            JourneyRoute.SHORT_EVENING to EntryChoice.EVENING_FULL)
        for ((route, choice) in cases) {
            val f = JourneyFixture(route).apply { send(Previous) }
            val before = f.state
            val token = f.runtime.token
            val raw = f.store.raw
            f.clock.advance(1234)
            f.store.failWrites = true
            assertThrows(IOException::class.java) { f.send(ReconfigureEntry(choice)) }
            assertEquals(before, f.state)
            assertEquals(token, f.runtime.token)
            assertEquals(raw, f.store.raw)
            f.store.failWrites = false
            val after = f.send(ReconfigureEntry(choice)).snapshot
            assertEquals(1234L, after.elapsedMs)
            assertEquals(before.sessionId, after.sessionId)
            println("NATIVE_ENTRY_FACT {\"case\":\"save-retry-$route\",\"before\":${JourneySnapshotCodec.encode(before)},\"after\":${JourneySnapshotCodec.encode(after)}}")
        }
    }

    @Test fun semanticHistorySurvivesRecoveryInBothReviewPhasesWithoutResuming() {
        for (dayReview in listOf(false, true)) {
            val f = JourneyFixture(JourneyRoute.EVENING)
            f.tick(6500)
            f.send(JourneyRequest.ChooseDayColor(JourneyAtmosphere.WARM))
            f.send(JourneyRequest.SubmitStory("Retained before plan change"))
            repeat(4) { f.send(Previous) }
            f.send(ReconfigureEntry(EntryChoice.EVENING_SHORT))
            f.send(Previous)
            if (dayReview) {
                f.send(JourneyRequest.Continue)
                f.tick(6500)
                f.send(JourneyRequest.ChooseDayColor(JourneyAtmosphere.WARM))
                f.send(ReviewDay)
            }
            val before = f.state
            val raw = requireNotNull(f.store.raw)
            assertEquals(before, JourneySnapshotCodec.decode(raw))
            assertTrue(JourneyPhase.EVENING_STORY in before.creditedSteps())
            f.clock.advance(600_000)
            val owner = JourneyRuntime(f.clock, f.store)
            val after = requireNotNull(owner.recover()).snapshot
            assertEquals(JourneyStatus.RECOVERED_INTERRUPTED, after.status)
            assertEquals(before.plan, after.plan)
            assertEquals(before.phase, after.phase)
            assertEquals(before.facts, after.facts)
            assertEquals(before.elapsedMs, after.elapsedMs)
            assertEquals(before.historicalSteps, after.historicalSteps)
            assertEquals(before.completedMask, after.completedMask)
            assertEquals(before.sessionId, after.sessionId)
            assertEquals(before.recordId, after.recordId)
            assertNull(after.facts.finishedAtEpochMs)
            assertThrows(JourneyRejected::class.java) { owner.request(owner.token, ReconfigureEntry(EntryChoice.EVENING_FULL)) }
            println("NATIVE_ENTRY_FACT {\"case\":\"history-recovery-$dayReview\",\"before\":${JourneySnapshotCodec.encode(before)},\"after\":${JourneySnapshotCodec.encode(after)}}")
        }
    }

    @Test fun malformedOrMisalignedSemanticCreditFailsClosedWithoutErasingStorage() {
        val f = JourneyFixture(JourneyRoute.NAP).apply { send(Previous); send(ReconfigureEntry(EntryChoice.NAP_15)) }
        val raw = requireNotNull(f.store.raw)
        for (credit in listOf("UNKNOWN", "NAP_CHECK,NAP_CHECK", "NAP_SLEEPING", "NAP_CHECK")) {
            val corrupt = raw.replace("\"historicalSteps\":\"\"", "\"historicalSteps\":\"$credit\"")
            assertNotEquals(raw, corrupt)
            val store = TestJourneyStore().apply { this.raw = corrupt }
            assertThrows(InvalidJourneySnapshot::class.java) { JourneyRuntime(f.clock, store).recover() }
            assertEquals(corrupt, store.raw)
        }
    }
}
