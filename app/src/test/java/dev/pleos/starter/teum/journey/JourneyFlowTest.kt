package dev.pleos.starter.teum.journey

import dev.pleos.starter.teum.records.ActivityOutcome
import dev.pleos.starter.teum.records.JourneyKind
import dev.pleos.starter.teum.records.SpeechMeasurement
import dev.pleos.starter.teum.records.RecordJson
import org.junit.Assert.*
import org.junit.Test

class JourneyFlowTest {
    @Test fun fullMorningPreservesRealFeelingIntentionAndChosenAtmosphere() {
        val f = JourneyFixture().apply { intention() }
        f.send(JourneyRequest.SubmitIntention("I will ask one clear question."))
        assertEquals("F1-04a", f.state.screenId)
        f.send(JourneyRequest.ConfirmIntention)
        f.send(JourneyRequest.ChooseAtmosphere(JourneyAtmosphere.WARM))
        val final = f.send(JourneyRequest.Finish).snapshot
        assertEquals(JourneyAtmosphere.WARM, final.atmosphere)
        val saved = requireNotNull(RecordJson.decode(RecordJson.encode(final.toActivityRecord(f.clock.wall))))
        assertEquals("WARM", saved.journeyFacts?.atmosphere)
        assertEquals("TENSE", final.facts.mood)
        assertEquals("I will ask one clear question.", final.facts.intention)
        assertEquals(JourneyKind.MORNING, final.facts.kind)
        assertTrue(final.toActivityRecord(f.clock.wall).isFullyCompleted())
        assertEquals(180_000L, final.elapsedMs)
    }

    @Test fun userCannotSkipBreathingOrForceCompletionWithNext() {
        val f = JourneyFixture().apply { breathing() }
        for (intent in listOf(JourneyRequest.Continue, JourneyRequest.Finish, JourneyRequest.SkipIntention,
            JourneyRequest.SubmitStory("wrong route"), JourneyRequest.AcknowledgeAwake)) {
            assertThrows(JourneyRejected::class.java) { f.send(intent) }
        }
        assertEquals(JourneyPhase.MORNING_BREATHING, f.state.phase)
        assertEquals(JourneyStatus.ACTIVE, f.state.status)
    }

    @Test fun moodCadencesDifferWhileTimerRemainsNative() {
        val expected = listOf(BreathingCadence(4000, 1000, 6000), BreathingCadence(4000, 0, 6000), BreathingCadence(4000, 0, 4000))
        for ((index, feeling) in Feeling.entries.withIndex()) {
            val f = JourneyFixture()
            f.send(JourneyRequest.Continue)
            f.send(JourneyRequest.ChooseFeeling(feeling))
            assertEquals(expected[index], f.state.breathingCadence())
            assertEquals(180_000L, f.runtime.remainingMs)
        }
    }

    @Test fun shortMorningAndQuickNeverCreateFullMorningIdentity() {
        val short = JourneyFixture(JourneyRoute.SHORT_MORNING).apply { intention() }
        short.send(JourneyRequest.SubmitIntention("Actual short intention"))
        short.send(JourneyRequest.ConfirmIntention)
        assertEquals(JourneyPhase.ENCOURAGEMENT, short.state.phase)
        val shortFacts = short.send(JourneyRequest.Finish).snapshot.facts
        val quick = JourneyFixture(JourneyRoute.QUICK_BREATHING).apply { tick(3000); tick(180_000) }
        val quickFacts = quick.send(JourneyRequest.ChooseQuickMood(QuickMood.BETTER)).snapshot.facts
        assertEquals(JourneyKind.QUICK_BREATHING, shortFacts.kind)
        assertEquals(JourneyKind.QUICK_BREATHING, quickFacts.kind)
        assertEquals("BETTER", quickFacts.mood)
        assertNull(quickFacts.intention)
    }

    @Test fun intentionRetryAndSkipRemoveRejectedText() {
        val f = JourneyFixture().apply { intention() }
        f.send(JourneyRequest.SubmitIntention("Rejected draft"))
        f.send(JourneyRequest.RetryIntention)
        f.send(JourneyRequest.SkipIntention)
        assertNull(f.state.facts.intention)
        assertEquals(JourneyPhase.ATMOSPHERE, f.state.phase)
    }

    @Test fun nativeTextCaptureRoutesRecognizedIntentionWithoutFakeTranscript() {
        val f = JourneyFixture().apply { intention() }
        val capture = f.send(JourneyRequest.RecordSpeech)
        val effect = capture.effects.single() as JourneyEffect.CaptureText
        assertEquals(JourneyTextTarget.INTENTION, effect.target)
        assertNull(capture.snapshot.facts.intention)
        f.send(JourneyRequest.SpeechDone)
        val result = f.runtime.textRecognized(effect.token, "Real recognized words").snapshot
        assertEquals(JourneyPhase.INTENTION_REVIEW, result.phase)
        assertEquals("Real recognized words", result.facts.intention)
    }

    @Test fun preparationWaitsForSeparateNativeMeasurementsAndPreservesUnknowns() {
        val f = JourneyFixture(JourneyRoute.PREPARATION)
        assertThrows(JourneyRejected::class.java) { f.send(JourneyRequest.SpeechDone) }
        val firstToken = (f.send(JourneyRequest.RecordSpeech).effects.single() as JourneyEffect.CaptureSpeech).token
        assertThrows(JourneyRejected::class.java) { f.runtime.speechMeasured(firstToken, SpeechMeasurement(100)) }
        f.clock.advance(1000)
        f.send(JourneyRequest.SpeechDone)
        assertEquals(JourneyPhase.SPEECH_FIRST, f.state.phase)
        f.runtime.speechMeasured(firstToken, SpeechMeasurement(1000, recognizedText = "first words", recognizedTokenCount = 2))
        assertEquals(JourneyPhase.SPEECH_FIRST_REVIEW, f.state.phase)
        f.send(JourneyRequest.SpeakAgain)
        val secondToken = (f.send(JourneyRequest.RecordSpeech).effects.single() as JourneyEffect.CaptureSpeech).token
        f.clock.advance(750)
        f.send(JourneyRequest.SpeechDone)
        f.runtime.speechMeasured(secondToken, SpeechMeasurement(750, relativeLoudness = 0.2))
        assertEquals(JourneyPhase.SPEECH_COMPARISON, f.state.phase)
        assertEquals(1000L, f.state.facts.firstSpeech?.recordingDurationMs)
        assertEquals(750L, f.state.facts.secondSpeech?.recordingDurationMs)
        assertNull(f.state.facts.firstSpeech?.relativeLoudness)
        assertNull(f.state.facts.secondSpeech?.recognizedFillerCount)
        assertThrows(JourneyRejected::class.java) { f.runtime.speechMeasured(firstToken, SpeechMeasurement(99)) }
        f.send(JourneyRequest.Continue)
        f.send(JourneyRequest.SelectMusic(MusicChoice.SUPPORT_SONG))
        val play = f.send(JourneyRequest.ListenMusic).effects.filterIsInstance<JourneyEffect.PlayMusic>().single()
        f.runtime.playbackObserved(play.token, PlaybackState.PLAYING)
        f.clock.advance(10_000)
        f.runtime.playbackObserved(play.token, PlaybackState.ENDED)
        assertEquals(JourneyStatus.ACTIVE, f.state.status)
        val finished = f.send(JourneyRequest.Finish).snapshot
        assertEquals(11_750L, finished.elapsedMs)
        assertEquals(JourneyKind.PREPARATION, finished.facts.kind)
        assertEquals(PlaybackState.ENDED, finished.playback)
    }

    @Test fun failedCaptureDoesNotInventMeasurementAndCanBeRetried() {
        val f = JourneyFixture(JourneyRoute.PREPARATION)
        val first = (f.send(JourneyRequest.RecordSpeech).effects.single() as JourneyEffect.CaptureSpeech).token
        f.runtime.speechUnavailable(first)
        val retry = (f.send(JourneyRequest.RecordSpeech).effects.single() as JourneyEffect.CaptureSpeech).token
        assertNotEquals(first, retry)
        assertNull(f.state.facts.firstSpeech)
        assertThrows(JourneyRejected::class.java) { f.runtime.speechUnavailable(first) }
    }

    @Test fun musicRequestDoesNotClaimPlaybackAndStalePlaybackCannotAffectNewSelection() {
        val f = JourneyFixture(JourneyRoute.PREPARATION)
        f.send(JourneyRequest.RecordSpeech)
        f.send(JourneyRequest.SpeechDone)
        f.runtime.speechMeasured(f.runtime.token, SpeechMeasurement(0))
        f.send(JourneyRequest.Continue)
        assertThrows(JourneyRejected::class.java) { f.send(JourneyRequest.ListenMusic) }
        f.send(JourneyRequest.SelectMusic(MusicChoice.RECOMMENDATION))
        val result = f.send(JourneyRequest.ListenMusic)
        val effect = result.effects.filterIsInstance<JourneyEffect.PlayMusic>().single()
        assertEquals(PlaybackState.REQUESTED, result.snapshot.playback)
        f.runtime.playbackObserved(effect.token, PlaybackState.UNAVAILABLE)
        assertEquals(PlaybackState.UNAVAILABLE, f.state.playback)
        f.send(JourneyRequest.ChangeMusic)
        assertThrows(JourneyRejected::class.java) { f.runtime.playbackObserved(effect.token, PlaybackState.PLAYING) }
    }

    @Test fun eveningStoryHasSingleOwnedReflectionHomeAndNoInventedGreeting() {
        val f = JourneyFixture(JourneyRoute.EVENING)
        f.tick(6500)
        f.send(JourneyRequest.ChooseDayColor(JourneyAtmosphere.CALM))
        f.send(JourneyRequest.SubmitStory("Actual story\nwith \"quotes\"."))
        f.send(JourneyRequest.Continue)
        val record = f.send(JourneyRequest.Finish).snapshot.toActivityRecord(f.clock.wall + 5000)
        assertEquals("Actual story\nwith \"quotes\".", record.journeyFacts?.reflection?.story)
        assertNull(record.journeyFacts?.reflection?.greeting)
        assertEquals("", record.note)
        assertEquals("", record.nextAction)
        assertEquals(6L, record.elapsedSeconds)
        assertEquals(JourneyKind.EVENING, record.journeyFacts?.kind)
        assertEquals("CALM", record.journeyFacts?.atmosphere)
    }

    @Test fun shortEveningSkipsStoryWithoutPretendingFiveMinutesElapsed() {
        val f = JourneyFixture(JourneyRoute.SHORT_EVENING)
        f.send(JourneyRequest.Continue)
        f.send(JourneyRequest.ChooseDayColor(JourneyAtmosphere.FLEX))
        assertEquals(JourneyPhase.EVENING_WEEK, f.state.phase)
        f.send(JourneyRequest.Continue)
        val record = f.send(JourneyRequest.Finish).snapshot.toActivityRecord(f.clock.wall)
        assertEquals(0L, record.elapsedSeconds)
        assertNull(record.journeyFacts?.reflection)
        assertEquals(ActivityOutcome.COMPLETED, record.outcome)
    }

    @Test fun safetyInterruptionIsTerminalAndCannotBePromotedByUserFinish() {
        val f = JourneyFixture(JourneyRoute.EVENING)
        f.send(JourneyRequest.Continue)
        f.send(JourneyRequest.ChooseDayColor(JourneyAtmosphere.FIRM))
        f.send(JourneyRequest.SkipStory)
        f.send(JourneyRequest.Continue)
        val result = f.runtime.interrupt(JourneyInterruption.SAFETY)
        assertEquals(listOf(JourneyEffect.StopOwnedEffects), result.effects)
        assertEquals(ActivityOutcome.SAFETY_INTERRUPTED, result.snapshot.toActivityRecord(f.clock.wall).outcome)
        assertThrows(JourneyRejected::class.java) { f.send(JourneyRequest.Finish) }
    }

    @Test fun stalePhaseTokenCannotSubmitOtherwiseValidNewPhaseIntent() {
        val f = JourneyFixture()
        val old = f.runtime.token
        f.send(JourneyRequest.Continue)
        assertThrows(JourneyRejected::class.java) { f.runtime.request(old, JourneyRequest.ChooseFeeling(Feeling.OKAY)) }
        assertNull(f.state.feeling)
    }

    private fun JourneyFixture.measureRound(ms: Long = 1000) {
        val capture = send(JourneyRequest.RecordSpeech).effects.filterIsInstance<JourneyEffect.CaptureSpeech>().single()
        clock.advance(ms)
        send(JourneyRequest.SpeechDone)
        runtime.speechMeasured(capture.token, SpeechMeasurement(ms, relativeLoudness = 0.25))
    }

    private fun JourneyFixture.eveningWeek(story: Boolean = false) {
        tick(6500)
        send(JourneyRequest.ChooseDayColor(JourneyAtmosphere.WARM))
        if (state.plan.route != JourneyRoute.SHORT_EVENING) {
            if (story) send(JourneyRequest.SubmitStory("Retained fixture story")) else send(JourneyRequest.SkipStory)
        }
    }

    @Test fun previousEdgesKeepOwnedFactsAndHistoricalProgress() {
        data class Edge(val label: String, val route: JourneyRoute, val enter: JourneyFixture.() -> Unit,
            val from: JourneyPhase, val to: JourneyPhase, val screen: String)
        val edges = listOf(
            Edge("B01-full", JourneyRoute.MORNING, { send(JourneyRequest.Continue) },
                JourneyPhase.MORNING_FEELING, JourneyPhase.MORNING_GREETING, "F1-01"),
            Edge("B01-short", JourneyRoute.SHORT_MORNING, {},
                JourneyPhase.MORNING_FEELING, JourneyPhase.ENTRY_REVIEW, "M-F1"),
            Edge("B02", JourneyRoute.MORNING, { breathing(); tick(25_000) },
                JourneyPhase.MORNING_BREATHING, JourneyPhase.MORNING_FEELING, "F1-02"),
            Edge("B03", JourneyRoute.MORNING, { intention() },
                JourneyPhase.INTENTION, JourneyPhase.MORNING_FEELING, "F1-02"),
            Edge("B04-accepted", JourneyRoute.MORNING,
                { intention(); send(JourneyRequest.SubmitIntention("Retained intention")); send(JourneyRequest.ConfirmIntention) },
                JourneyPhase.ATMOSPHERE, JourneyPhase.INTENTION_REVIEW, "F1-04a"),
            Edge("B04-no-text", JourneyRoute.MORNING, { intention(); send(JourneyRequest.SkipIntention) },
                JourneyPhase.ATMOSPHERE, JourneyPhase.INTENTION, "F1-04"),
            Edge("B05-full", JourneyRoute.MORNING,
                { intention(); send(JourneyRequest.SkipIntention); send(JourneyRequest.ChooseAtmosphere(JourneyAtmosphere.FOCUS)) },
                JourneyPhase.ENCOURAGEMENT, JourneyPhase.ATMOSPHERE, "F1-05"),
            Edge("B05-short-accepted", JourneyRoute.SHORT_MORNING,
                { intention(); send(JourneyRequest.SubmitIntention("Retained short intention")); send(JourneyRequest.ConfirmIntention) },
                JourneyPhase.ENCOURAGEMENT, JourneyPhase.INTENTION_REVIEW, "F1-04a"),
            Edge("B05-short-no-text", JourneyRoute.SHORT_MORNING, { intention(); send(JourneyRequest.SkipIntention) },
                JourneyPhase.ENCOURAGEMENT, JourneyPhase.INTENTION, "F1-04"),
            Edge("B06", JourneyRoute.NAP, {}, JourneyPhase.NAP_CHECK, JourneyPhase.ENTRY_REVIEW, "F2-01"),
            Edge("B07", JourneyRoute.NAP, { send(JourneyRequest.Continue) },
                JourneyPhase.NAP_POSTURE, JourneyPhase.NAP_CHECK, "F2-02"),
            Edge("B08-first", JourneyRoute.PREPARATION, {}, JourneyPhase.SPEECH_FIRST, JourneyPhase.ENTRY_REVIEW, "F3-01"),
            Edge("B08-second", JourneyRoute.PREPARATION, { measureRound(); send(JourneyRequest.SpeakAgain) },
                JourneyPhase.SPEECH_SECOND, JourneyPhase.SPEECH_FIRST_REVIEW, "F3-04"),
            Edge("B09", JourneyRoute.PREPARATION, { measureRound(); send(JourneyRequest.SpeakAgain); measureRound(750) },
                JourneyPhase.SPEECH_COMPARISON, JourneyPhase.SPEECH_SECOND, "F3-03"),
            Edge("B10-first", JourneyRoute.PREPARATION, { measureRound(); send(JourneyRequest.Continue) },
                JourneyPhase.MUSIC_SELECTION, JourneyPhase.SPEECH_FIRST_REVIEW, "F3-04"),
            Edge("B10-second", JourneyRoute.PREPARATION,
                { measureRound(); send(JourneyRequest.SpeakAgain); measureRound(750); send(JourneyRequest.Continue) },
                JourneyPhase.MUSIC_SELECTION, JourneyPhase.SPEECH_COMPARISON, "F3-05"),
            Edge("B11-full", JourneyRoute.EVENING, {}, JourneyPhase.EVENING_ARRIVAL, JourneyPhase.ENTRY_REVIEW, "F4-01"),
            Edge("B11-short", JourneyRoute.SHORT_EVENING, {}, JourneyPhase.EVENING_ARRIVAL, JourneyPhase.ENTRY_REVIEW, "F4-01"),
            Edge("B12-full", JourneyRoute.EVENING, { tick(6500) },
                JourneyPhase.EVENING_COLOR, JourneyPhase.EVENING_ARRIVAL, "F4-02"),
            Edge("B12-short", JourneyRoute.SHORT_EVENING, { tick(6500) },
                JourneyPhase.EVENING_COLOR, JourneyPhase.EVENING_ARRIVAL, "F4-02"),
            Edge("B13", JourneyRoute.EVENING, { tick(6500); send(JourneyRequest.ChooseDayColor(JourneyAtmosphere.WARM)) },
                JourneyPhase.EVENING_STORY, JourneyPhase.EVENING_COLOR, "F4-03"),
            Edge("B14-story", JourneyRoute.EVENING, { eveningWeek(true) },
                JourneyPhase.EVENING_WEEK, JourneyPhase.EVENING_STORY, "F4-04"),
            Edge("B14-no-story", JourneyRoute.EVENING, { eveningWeek() },
                JourneyPhase.EVENING_WEEK, JourneyPhase.EVENING_STORY, "F4-04"),
            Edge("B14-short", JourneyRoute.SHORT_EVENING, { eveningWeek() },
                JourneyPhase.EVENING_WEEK, JourneyPhase.EVENING_COLOR, "F4-03"),
            Edge("B15-full", JourneyRoute.EVENING, { eveningWeek(true); send(JourneyRequest.Continue) },
                JourneyPhase.EVENING_FAREWELL, JourneyPhase.EVENING_WEEK, "F4-05"),
            Edge("B15-short", JourneyRoute.SHORT_EVENING, { eveningWeek(); send(JourneyRequest.Continue) },
                JourneyPhase.EVENING_FAREWELL, JourneyPhase.EVENING_WEEK, "F4-05"),
            Edge("B16", JourneyRoute.QUICK_BREATHING, { tick(3000); tick(180_000) },
                JourneyPhase.QUICK_FEELING, JourneyPhase.QUICK_BREATHING, "Q1-B"),
            Edge("D17-full", JourneyRoute.EVENING, { eveningWeek(true); send(ReviewDay) },
                JourneyPhase.EVENING_DAY_REVIEW, JourneyPhase.EVENING_WEEK, "F4-05"),
            Edge("D17-short", JourneyRoute.SHORT_EVENING, { eveningWeek(); send(ReviewDay) },
                JourneyPhase.EVENING_DAY_REVIEW, JourneyPhase.EVENING_WEEK, "F4-05"),
            Edge("intention-review-header", JourneyRoute.MORNING,
                { intention(); send(JourneyRequest.SubmitIntention("Not an explicit retry")) },
                JourneyPhase.INTENTION_REVIEW, JourneyPhase.INTENTION, "F1-04"),
            Edge("first-result-header", JourneyRoute.PREPARATION, { measureRound() },
                JourneyPhase.SPEECH_FIRST_REVIEW, JourneyPhase.SPEECH_FIRST, "F3-03")
        )
        for (edge in edges) {
            val f = JourneyFixture(edge.route).apply(edge.enter)
            assertEquals(edge.label, edge.from, f.state.phase)
            val before = f.state
            val oldToken = f.runtime.token
            f.clock.advance(1234)
            val update = f.send(Previous)
            val after = update.snapshot
            assertEquals(edge.label, edge.to, after.phase)
            assertEquals(edge.label, edge.screen, after.screenId)
            assertEquals(edge.label, before.plan, after.plan)
            assertEquals(edge.label, before.sessionId, after.sessionId)
            assertEquals(edge.label, before.recordId, after.recordId)
            assertEquals(edge.label, before.facts, after.facts)
            assertEquals(edge.label, before.completedMask, after.completedMask)
            assertEquals(edge.label, before.elapsedMs + 1234, after.elapsedMs)
            assertEquals(edge.label, before.revision + 1, after.revision)
            assertEquals(edge.label, JourneyStatus.ACTIVE, after.status)
            assertEquals(edge.label, SpeechCapture.IDLE, after.speechCapture)
            assertNull(after.facts.finishedAtEpochMs)
            assertEquals(edge.label,
                listOf(JourneyEffect.StopOwnedEffects, JourneyEffect.PhaseEntered(edge.to)), update.effects)
            assertThrows(JourneyRejected::class.java) { f.runtime.request(oldToken, JourneyRequest.Stop) }
            println("NATIVE_BACK_FACT {\"case\":\"${edge.label}\",\"before\":${JourneySnapshotCodec.encode(before)},\"after\":${JourneySnapshotCodec.encode(after)}}")
        }
    }

    @Test fun entryReviewResumesTheSamePlanWithoutCreditingProgress() {
        val totals = mapOf(JourneyRoute.MORNING to 6, JourneyRoute.SHORT_MORNING to 4,
            JourneyRoute.NAP to 5, JourneyRoute.PREPARATION to 4, JourneyRoute.EVENING to 5,
            JourneyRoute.SHORT_EVENING to 4, JourneyRoute.QUICK_BREATHING to 3)
        for ((route, total) in totals) {
            val f = JourneyFixture(route)
            assertEquals(total, f.state.plan.totalSteps)
            if (route in setOf(JourneyRoute.MORNING, JourneyRoute.QUICK_BREATHING)) {
                assertFalse(JourneyPhase.ENTRY_REVIEW in f.state.plan.phases)
                continue
            }
            val initial = f.state
            f.send(Previous)
            val review = f.tick(30_000).snapshot
            assertEquals(JourneyPhase.ENTRY_REVIEW, review.phase)
            assertNull(f.runtime.remainingMs)
            assertThrows(IllegalStateException::class.java) { f.runtime.start(JourneyPlan(route)) }
            val resumed = f.send(JourneyRequest.Continue).snapshot
            assertEquals(initial.phase, resumed.phase)
            assertEquals(initial.sessionId, resumed.sessionId)
            assertEquals(initial.recordId, resumed.recordId)
            assertEquals(initial.facts, resumed.facts)
            assertEquals(initial.completedMask, resumed.completedMask)
            assertEquals(30_000L, resumed.elapsedMs)
            assertEquals(0L, resumed.phaseElapsedMs)
            assertNull(resumed.napStartedMs)
            assertEquals(total, resumed.plan.totalSteps)
        }
    }

    @Test fun previousCancelsPendingCaptureWithoutErasingAcceptedResults() {
        for (target in listOf(JourneyPhase.INTENTION, JourneyPhase.EVENING_STORY,
            JourneyPhase.SPEECH_FIRST, JourneyPhase.SPEECH_SECOND)) {
            for (captureState in listOf(SpeechCapture.RECORDING, SpeechCapture.AWAITING_RESULT, SpeechCapture.UNAVAILABLE)) {
                val f = when (target) {
                    JourneyPhase.INTENTION -> JourneyFixture().apply {
                        intention(); send(JourneyRequest.SubmitIntention("Keep accepted intention")); send(Previous)
                    }
                    JourneyPhase.EVENING_STORY -> JourneyFixture(JourneyRoute.EVENING).apply {
                        eveningWeek(true); send(Previous)
                    }
                    else -> JourneyFixture(JourneyRoute.PREPARATION).apply {
                        measureRound(); send(JourneyRequest.SpeakAgain); measureRound(750); send(Previous)
                        if (target == JourneyPhase.SPEECH_FIRST) { send(Previous); send(Previous) }
                    }
                }
                assertEquals(target, f.state.phase)
                f.send(JourneyRequest.RecordSpeech)
                val captureToken = f.runtime.token
                when (captureState) {
                    SpeechCapture.AWAITING_RESULT -> f.send(JourneyRequest.SpeechDone)
                    SpeechCapture.UNAVAILABLE -> f.runtime.speechUnavailable(captureToken)
                    else -> Unit
                }
                val before = f.state
                val after = f.send(Previous).snapshot
                assertEquals(before.facts, after.facts)
                assertEquals(SpeechCapture.IDLE, after.speechCapture)
                assertThrows(JourneyRejected::class.java) {
                    f.runtime.speechMeasured(captureToken, SpeechMeasurement(50))
                }
                assertThrows(JourneyRejected::class.java) { f.runtime.textRecognized(captureToken, "Late replacement") }
                assertThrows(JourneyRejected::class.java) { f.runtime.speechUnavailable(captureToken) }
                assertEquals(after, f.state)
            }
        }
    }

    @Test fun dayReviewCannotCreditOrFinishTheActiveEvening() {
        for (route in listOf(JourneyRoute.EVENING, JourneyRoute.SHORT_EVENING)) {
            val f = JourneyFixture(route).apply { eveningWeek(route == JourneyRoute.EVENING) }
            val before = f.state
            val entered = f.send(ReviewDay).snapshot
            assertEquals("R-02", entered.screenId)
            assertEquals(before.completedMask, entered.completedMask)
            assertEquals(before.facts, entered.facts)
            println("NATIVE_BACK_FACT {\"case\":\"D17-entry-$route\",\"before\":${JourneySnapshotCodec.encode(before)},\"after\":${JourneySnapshotCodec.encode(entered)}}")
            f.tick(1000)
            val stayed = f.tick(49_000).snapshot
            assertEquals(JourneyPhase.EVENING_DAY_REVIEW, stayed.phase)
            assertEquals("R-02", stayed.screenId)
            assertEquals(entered.revision, stayed.revision)
            assertEquals(before.elapsedMs + 50_000, stayed.elapsedMs)
            assertThrows(JourneyRejected::class.java) { f.send(JourneyRequest.Continue) }
            assertThrows(JourneyRejected::class.java) { f.send(JourneyRequest.Finish) }
            assertThrows(JourneyRejected::class.java) { f.send(ReviewDay) }
            val reviewToken = f.runtime.token
            val returned = f.send(Previous).snapshot
            assertEquals(JourneyPhase.EVENING_WEEK, returned.phase)
            assertEquals(before.completedMask, returned.completedMask)
            assertEquals(before.facts, returned.facts)
            assertEquals(before.sessionId, returned.sessionId)
            assertEquals(before.recordId, returned.recordId)
            assertThrows(JourneyRejected::class.java) { f.runtime.request(reviewToken, JourneyRequest.Continue) }
        }
        for (route in JourneyRoute.entries) {
            val f = JourneyFixture(route)
            val before = f.state
            assertThrows(JourneyRejected::class.java) { f.send(ReviewDay) }
            assertEquals(before, f.state)
        }
    }

    @Test fun previousRejectsPhasesWithoutAnOriginalPreviousControl() {
        val fixtures = listOf(
            JourneyFixture(),
            JourneyFixture(JourneyRoute.QUICK_BREATHING),
            JourneyFixture(JourneyRoute.QUICK_BREATHING).apply { tick(3000) },
            JourneyFixture(JourneyRoute.NAP).apply { nap() },
            JourneyFixture(JourneyRoute.NAP).apply { nap(); tick(7000) },
            JourneyFixture(JourneyRoute.NAP, 10).apply { nap(); tick(600_000) },
            JourneyFixture(JourneyRoute.NAP, 10).apply { nap(); tick(600_000); tick(8000) },
            JourneyFixture(JourneyRoute.NAP, 10).apply { nap(); tick(600_000); tick(8000); tick(25_000) },
            JourneyFixture(JourneyRoute.NAP).apply { send(Previous) }
        )
        for (f in fixtures) {
            val before = f.state
            val raw = f.store.raw
            assertThrows(JourneyRejected::class.java) { f.send(Previous) }
            assertEquals(before, f.state)
            assertEquals(raw, f.store.raw)
        }
    }

    @Test fun previousFromMusicFocusRetainsMeasurementsAndRejectsLatePlayback() {
        val f = JourneyFixture(JourneyRoute.PREPARATION).apply {
            measureRound()
            send(JourneyRequest.Continue)
            send(JourneyRequest.SelectMusic(MusicChoice.SUPPORT_SONG))
        }
        val playback = f.send(JourneyRequest.ListenMusic).effects.filterIsInstance<JourneyEffect.PlayMusic>().single()
        f.runtime.playbackObserved(playback.token, PlaybackState.PLAYING)
        val before = f.state
        val update = f.send(Previous)
        assertEquals(JourneyPhase.MUSIC_SELECTION, update.snapshot.phase)
        assertEquals(PlaybackState.UNREQUESTED, update.snapshot.playback)
        assertEquals(before.facts, update.snapshot.facts)
        assertEquals(before.completedMask, update.snapshot.completedMask)
        assertEquals(listOf(JourneyEffect.StopOwnedEffects, JourneyEffect.PhaseEntered(JourneyPhase.MUSIC_SELECTION)),
            update.effects)
        assertThrows(JourneyRejected::class.java) { f.runtime.playbackObserved(playback.token, PlaybackState.ENDED) }
    }

    private fun reviewedEntry(route: JourneyRoute): JourneyFixture = JourneyFixture(route).apply {
        when (route) {
            JourneyRoute.SHORT_MORNING -> {
                intention(); send(JourneyRequest.SubmitIntention("Retain accepted entry intention"))
                repeat(3) { send(Previous) }
            }
            JourneyRoute.NAP -> { send(JourneyRequest.Continue); send(Previous); send(Previous) }
            JourneyRoute.PREPARATION -> {
                measureRound(); send(JourneyRequest.SpeakAgain); measureRound(750)
                repeat(4) { send(Previous) }
            }
            JourneyRoute.EVENING -> { eveningWeek(true); repeat(4) { send(Previous) } }
            JourneyRoute.SHORT_EVENING -> {
                eveningWeek(); send(JourneyRequest.Continue); repeat(4) { send(Previous) }
            }
            else -> error("No original entry review")
        }
    }

    @Test fun everyOriginalEntryChoicePreservesOwnerAndMapsCreditByPerformedStep() {
        data class Choice(val route: JourneyRoute, val choice: EntryChoice, val mask: Int)
        val cases = listOf(
            Choice(JourneyRoute.SHORT_MORNING, EntryChoice.MORNING_FULL, 14),
            Choice(JourneyRoute.SHORT_MORNING, EntryChoice.MORNING_SHORT, 7),
            Choice(JourneyRoute.NAP, EntryChoice.NAP_10, 1),
            Choice(JourneyRoute.NAP, EntryChoice.NAP_15, 1),
            Choice(JourneyRoute.NAP, EntryChoice.NAP_20, 1),
            Choice(JourneyRoute.PREPARATION, EntryChoice.PREPARATION, 3),
            Choice(JourneyRoute.PREPARATION, EntryChoice.QUICK, 0),
            Choice(JourneyRoute.EVENING, EntryChoice.EVENING_FULL, 7),
            Choice(JourneyRoute.EVENING, EntryChoice.EVENING_SHORT, 3),
            Choice(JourneyRoute.SHORT_EVENING, EntryChoice.EVENING_FULL, 11),
            Choice(JourneyRoute.SHORT_EVENING, EntryChoice.EVENING_SHORT, 7)
        )
        for (case in cases) {
            val f = reviewedEntry(case.route)
            val before = f.state
            val token = f.runtime.token
            f.clock.advance(1234)
            val update = f.send(ReconfigureEntry(case.choice))
            val after = update.snapshot
            assertEquals(case.choice.plan, after.plan)
            assertEquals(case.mask, after.completedMask)
            assertEquals(before.sessionId, after.sessionId)
            assertEquals(before.recordId, after.recordId)
            assertEquals(before.facts.copy(kind = case.choice.plan.kind), after.facts)
            assertEquals(before.feeling, after.feeling)
            assertEquals(before.atmosphere, after.atmosphere)
            assertEquals(before.elapsedMs + 1234, after.elapsedMs)
            assertEquals(before.revision + 1, after.revision)
            assertEquals(JourneyStatus.ACTIVE, after.status)
            assertEquals(SpeechCapture.IDLE, after.speechCapture)
            assertNull(after.facts.finishedAtEpochMs)
            if (case.route == JourneyRoute.NAP) {
                assertEquals(JourneyPhase.ENTRY_REVIEW, after.phase)
                assertNull(after.napStartedMs)
                assertNull(f.runtime.remainingMs)
                assertEquals(listOf(JourneyEffect.StopOwnedEffects), update.effects)
            } else {
                assertEquals(case.choice.plan.phases.first(), after.phase)
                assertEquals(0L, after.phaseElapsedMs)
                assertEquals(listOf(JourneyEffect.StopOwnedEffects, JourneyEffect.PhaseEntered(after.phase)), update.effects)
            }
            assertThrows(JourneyRejected::class.java) { f.runtime.request(token, JourneyRequest.Stop) }
            assertThrows(JourneyRejected::class.java) { f.runtime.speechMeasured(token, SpeechMeasurement(99)) }
            assertEquals(after, JourneySnapshotCodec.decode(requireNotNull(f.store.raw)))
            println("NATIVE_ENTRY_FACT {\"case\":\"${case.route}-${case.choice}\",\"before\":${JourneySnapshotCodec.encode(before)},\"after\":${JourneySnapshotCodec.encode(after)}}")
        }
    }

    @Test fun eveningRoundTripKeepsHiddenStoryCreditWithoutAwardingUnperformedStory() {
        for ((route, expected) in listOf(JourneyRoute.EVENING to 7, JourneyRoute.SHORT_EVENING to 11)) {
            val f = reviewedEntry(route)
            val before = f.state
            f.send(ReconfigureEntry(EntryChoice.EVENING_SHORT))
            f.send(Previous)
            val after = f.send(ReconfigureEntry(EntryChoice.EVENING_FULL)).snapshot
            assertEquals(expected, after.completedMask)
            assertEquals(before.facts, after.facts)
            assertEquals(route == JourneyRoute.EVENING, JourneyPhase.EVENING_STORY in after.creditedSteps())
            assertEquals(route == JourneyRoute.SHORT_EVENING, JourneyPhase.EVENING_WEEK in after.creditedSteps())
            println("NATIVE_ENTRY_FACT {\"case\":\"roundtrip-$route\",\"before\":${JourneySnapshotCodec.encode(before)},\"after\":${JourneySnapshotCodec.encode(after)}}")
        }
    }

    @Test fun entryNextAndExplicitSkipPreserveAcceptedFactsAndDoNotCreateAnotherOwner() {
        for (route in listOf(JourneyRoute.SHORT_MORNING, JourneyRoute.NAP, JourneyRoute.PREPARATION,
            JourneyRoute.EVENING, JourneyRoute.SHORT_EVENING)) {
            for (skip in listOf(false, true)) {
                val f = reviewedEntry(route)
                val before = f.state
                f.clock.advance(1234)
                val after = f.send(if (skip) JourneyRequest.Stop else JourneyRequest.Continue).snapshot
                assertEquals(before.plan, after.plan)
                assertEquals(before.sessionId, after.sessionId)
                assertEquals(before.recordId, after.recordId)
                assertEquals(before.completedMask, after.completedMask)
                assertEquals(before.elapsedMs + 1234, after.elapsedMs)
                if (skip) {
                    assertEquals(JourneyStatus.INTERRUPTED, after.status)
                    assertEquals(before.facts.copy(finishedAtEpochMs = f.clock.wall), after.facts)
                    assertEquals(ActivityOutcome.INTERRUPTED, after.toActivityRecord(f.clock.wall).outcome)
                } else {
                    assertEquals(before.facts, after.facts)
                    assertEquals(before.plan.phases.first(), after.phase)
                    assertEquals(JourneyStatus.ACTIVE, after.status)
                }
                println("NATIVE_ENTRY_FACT {\"case\":\"${if (skip) "skip" else "next"}-$route\",\"before\":${JourneySnapshotCodec.encode(before)},\"after\":${JourneySnapshotCodec.encode(after)}}")
            }
        }
    }

    @Test fun entryChoicesCannotBeUsedOutsideTheirOriginalReviewOrAcrossUnrelatedInvitations() {
        for (route in JourneyRoute.entries) {
            val f = JourneyFixture(route)
            val before = f.state
            assertThrows(JourneyRejected::class.java) { f.send(ReconfigureEntry(EntryChoice.QUICK)) }
            assertEquals(before, f.state)
        }
        for (route in listOf(JourneyRoute.SHORT_MORNING, JourneyRoute.NAP, JourneyRoute.PREPARATION,
            JourneyRoute.EVENING, JourneyRoute.SHORT_EVENING)) {
            val f = reviewedEntry(route)
            for (choice in EntryChoice.entries.filter { it !in f.state.plan.entryChoices }) {
                val before = f.state
                val raw = f.store.raw
                assertThrows(JourneyRejected::class.java) { f.send(ReconfigureEntry(choice)) }
                assertEquals(before, f.state)
                assertEquals(raw, f.store.raw)
            }
        }
    }
}
