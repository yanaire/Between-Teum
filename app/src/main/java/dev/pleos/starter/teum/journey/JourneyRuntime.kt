package dev.pleos.starter.teum.journey

import dev.pleos.starter.teum.records.JourneyFacts
import dev.pleos.starter.teum.records.HistoryPolicy
import dev.pleos.starter.teum.records.RecordRetention
import dev.pleos.starter.teum.records.SpeechMeasurement
import dev.pleos.starter.teum.session.ClockReading
import java.util.UUID

/** One serialized owner. Host must gate start on trusted safety and no legacy owner. */
class JourneyRuntime(private val clock: JourneyClock, private val snapshots: JourneySnapshotStore) {
    var state: JourneySnapshot? = null
        private set
    private var lastMonotonicMs = 0L
    val token: JourneyToken get() = requireNotNull(state).let { JourneyToken(it.sessionId, it.revision) }
    val remainingMs: Long? get() = state?.takeIf { it.active }?.let(JourneyTimers::remainingMs)

    fun start(plan: JourneyPlan): JourneyUpdate {
        check(state == null) { "Save/discard the previous journey first" }
        check(snapshots.load() == null) { "Resolve durable recovery before starting" }
        val now = clock.reading()
        require(now.wallMillis > 0 && now.elapsedMillis >= 0)
        val id = UUID.randomUUID().toString()
        val initial = JourneySnapshot(
            plan = plan, sessionId = id, recordId = UUID.randomUUID().toString(),
            phase = plan.phases.first(), status = JourneyStatus.ACTIVE,
            facts = JourneyFacts(kind = plan.kind, nativeActivityId = id, startedAtEpochMs = now.wallMillis),
            observedWallMs = now.wallMillis
        )
        initial.validate()
        snapshots.save(JourneySnapshotCodec.encode(initial))
        state = initial
        lastMonotonicMs = now.elapsedMillis
        return JourneyUpdate(initial, listOf(JourneyEffect.PhaseEntered(initial.phase)))
    }

    fun request(token: JourneyToken, intent: JourneyRequest): JourneyUpdate {
        val before = requireActive(token)
        val now = clock.reading()
        val observed = observe(before, now) ?: return clockInterrupted()
        val candidate = JourneyTransitions.apply(observed, intent)
        return commit(candidate, now, intent)
    }

    /** Native scheduler only. JavaScript timers cannot advance or complete a phase. */
    fun tick(): JourneyUpdate {
        val before = requireNotNull(state)
        if (!before.active) return JourneyUpdate(before)
        val now = clock.reading()
        val observed = observe(before, now) ?: return clockInterrupted()
        return commit(JourneyTimers.advance(observed), now)
    }

    /** Parent cancels effects BEFORE calling, including when persistence throws. Never resumes. */
    fun interrupt(reason: JourneyInterruption): JourneyUpdate {
        val before = requireNotNull(state)
        if (!before.active) return JourneyUpdate(before)
        val now = clock.reading()
        val observed = observe(before, now) ?: before
        val terminal = observed.copy(
            status = if (reason == JourneyInterruption.SAFETY) JourneyStatus.SAFETY_INTERRUPTED else JourneyStatus.INTERRUPTED,
            interruption = reason, eyesClosed = false,
            facts = observed.facts.copy(finishedAtEpochMs = now.wallMillis.takeIf {
                it > 0 && it >= requireNotNull(observed.facts.startedAtEpochMs)
            })
        )
        // Fail closed in memory even when the storage adapter cannot write.
        state = terminal
        snapshots.save(JourneySnapshotCodec.encode(terminal))
        return JourneyUpdate(terminal, listOf(JourneyEffect.StopOwnedEffects))
    }

    /** Process death cannot establish finish time. Keep only the last persisted foreground duration. */
    fun recover(): JourneyUpdate? {
        check(state == null)
        val raw = snapshots.load() ?: return null
        val saved = JourneySnapshotCodec.decode(raw)
        val recovered = if (saved.active) saved.copy(
            status = JourneyStatus.RECOVERED_INTERRUPTED, interruption = JourneyInterruption.BACKGROUND,
            eyesClosed = false, speechCapture = SpeechCapture.UNAVAILABLE,
            playback = if (saved.playback == PlaybackState.UNREQUESTED) saved.playback else PlaybackState.UNAVAILABLE
        ) else saved
        recovered.validate()
        snapshots.save(JourneySnapshotCodec.encode(recovered))
        state = recovered
        return JourneyUpdate(recovered, listOf(JourneyEffect.StopOwnedEffects))
    }

    fun speechMeasured(token: JourneyToken, measurement: SpeechMeasurement): JourneyUpdate {
        val s = requireActive(token)
        if (s.speechCapture != SpeechCapture.AWAITING_RESULT) throw JourneyRejected("Speech stop not requested")
        measurement.validate()
        val now = clock.reading()
        val observed = observe(s, now) ?: return clockInterrupted()
        val next = when (s.phase) {
            JourneyPhase.SPEECH_FIRST -> observed.copy(phase = JourneyPhase.SPEECH_FIRST_REVIEW,
                speechCapture = SpeechCapture.IDLE, facts = observed.facts.copy(firstSpeech = measurement))
            JourneyPhase.SPEECH_SECOND -> observed.copy(phase = JourneyPhase.SPEECH_COMPARISON,
                speechCapture = SpeechCapture.IDLE, facts = observed.facts.copy(secondSpeech = measurement))
            else -> throw JourneyRejected("No native speech round here")
        }
        return commit(next, now)
    }

    fun speechUnavailable(token: JourneyToken): JourneyUpdate {
        val s = requireActive(token)
        if (s.phase !in setOf(JourneyPhase.SPEECH_FIRST, JourneyPhase.SPEECH_SECOND, JourneyPhase.INTENTION, JourneyPhase.EVENING_STORY) ||
            s.speechCapture !in setOf(SpeechCapture.RECORDING, SpeechCapture.AWAITING_RESULT)) {
            throw JourneyRejected("No speech capture pending")
        }
        val now = clock.reading()
        val observed = observe(s, now) ?: return clockInterrupted()
        return commit(observed.copy(speechCapture = SpeechCapture.UNAVAILABLE), now)
    }

    fun textRecognized(token: JourneyToken, text: String): JourneyUpdate {
        val s = requireActive(token)
        if (s.speechCapture !in setOf(SpeechCapture.RECORDING, SpeechCapture.AWAITING_RESULT)) {
            throw JourneyRejected("No text capture pending")
        }
        val intent = when (s.phase) {
            JourneyPhase.INTENTION -> JourneyRequest.SubmitIntention(text)
            JourneyPhase.EVENING_STORY -> JourneyRequest.SubmitStory(text)
            else -> throw JourneyRejected("No native text input here")
        }
        return request(token, intent)
    }

    fun playbackObserved(token: JourneyToken, observation: PlaybackState): JourneyUpdate {
        val s = requireActive(token)
        if (s.phase != JourneyPhase.MUSIC_FOCUS ||
            observation !in setOf(PlaybackState.PLAYING, PlaybackState.ENDED, PlaybackState.UNAVAILABLE)) {
            throw JourneyRejected("Invalid playback observation")
        }
        if (s.playback in setOf(PlaybackState.ENDED, PlaybackState.UNAVAILABLE)) throw JourneyRejected("Playback already terminal")
        val now = clock.reading()
        val observed = observe(s, now) ?: return clockInterrupted()
        return commit(observed.copy(playback = observation), now)
    }

    fun clear() {
        check(state?.active != true)
        snapshots.clear()
        state = null
    }

    /** Redacts expired text without advancing time, phases or recovery status. */
    fun maintainReflection(policy: HistoryPolicy) {
        val before = state ?: return
        val facts = RecordRetention.retainReflection(before.facts, clock.reading().wallMillis, policy)
        if (facts == before.facts) return
        val retained = before.copy(facts = facts)
        snapshots.save(JourneySnapshotCodec.encode(retained))
        state = retained
    }

    private fun requireActive(token: JourneyToken): JourneySnapshot {
        val s = state ?: throw JourneyRejected("No journey")
        if (!s.active || token != this.token) throw JourneyRejected("Inactive or stale journey")
        return s
    }

    private fun observe(s: JourneySnapshot, now: ClockReading): JourneySnapshot? {
        if (now.elapsedMillis < lastMonotonicMs || now.wallMillis <= 0 ||
            now.wallMillis < requireNotNull(s.facts.startedAtEpochMs)) return null
        return s.copy(elapsedMs = Math.addExact(s.elapsedMs, now.elapsedMillis - lastMonotonicMs),
            observedWallMs = now.wallMillis)
    }

    private fun clockInterrupted(): JourneyUpdate {
        val s = requireNotNull(state).copy(status = JourneyStatus.INTERRUPTED,
            interruption = JourneyInterruption.CLOCK, eyesClosed = false)
        state = s
        snapshots.save(JourneySnapshotCodec.encode(s))
        return JourneyUpdate(s, listOf(JourneyEffect.StopOwnedEffects))
    }

    private fun commit(candidate: JourneySnapshot, now: ClockReading, intent: JourneyRequest? = null): JourneyUpdate {
        val old = requireNotNull(state)
        val moved = candidate.phase != old.phase
        val reconfigured = intent is ReconfigureEntry
        val revised = moved || reconfigured || intent == JourneyRequest.RecordSpeech
        val completedStep = moved && intent != Previous && intent != ReviewDay &&
            old.phase !in setOf(JourneyPhase.ENTRY_REVIEW, JourneyPhase.EVENING_DAY_REVIEW)
        var next = candidate.copy(
            phaseStartedMs = if (moved) candidate.elapsedMs else candidate.phaseStartedMs,
            completedMask = if (completedStep) candidate.completedMask or (1 shl old.plan.stepIndex(old.phase)) else candidate.completedMask,
            revision = if (revised) Math.addExact(old.revision, 1) else old.revision,
            speechCapture = if (moved) SpeechCapture.IDLE else candidate.speechCapture,
            eyesClosed = if (moved) false else candidate.eyesClosed
        )
        candidate.historicalSteps?.let { history ->
            val credits = when {
                next.status == JourneyStatus.COMPLETED -> history + next.plan.steps
                completedStep -> history + old.plan.steps[old.plan.stepIndex(old.phase)]
                else -> history
            }
            next = next.copy(historicalSteps = credits, completedMask = next.plan.maskFor(credits))
        }
        if (!next.active) next = next.copy(facts = next.facts.copy(finishedAtEpochMs = now.wallMillis))
        next.validate()
        snapshots.save(JourneySnapshotCodec.encode(next))
        state = next
        lastMonotonicMs = now.elapsedMillis
        val effects = mutableListOf<JourneyEffect>()
        if (moved || reconfigured || !next.active) effects += JourneyEffect.StopOwnedEffects
        if (moved && next.active) effects += JourneyEffect.PhaseEntered(next.phase)
        val callbackToken = this.token
        val round = if (next.phase == JourneyPhase.SPEECH_SECOND) 2 else 1
        val textTarget = when (next.phase) {
            JourneyPhase.INTENTION -> JourneyTextTarget.INTENTION
            JourneyPhase.EVENING_STORY -> JourneyTextTarget.STORY
            else -> null
        }
        when (intent) {
            JourneyRequest.RecordSpeech -> effects += if (textTarget == null) JourneyEffect.CaptureSpeech(callbackToken, round)
                else JourneyEffect.CaptureText(callbackToken, textTarget)
            JourneyRequest.SpeechDone -> effects += if (textTarget == null) JourneyEffect.FinishSpeech(callbackToken, round)
                else JourneyEffect.FinishText(callbackToken, textTarget)
            JourneyRequest.ListenMusic -> effects += JourneyEffect.PlayMusic(callbackToken, requireNotNull(next.musicChoice))
            else -> Unit
        }
        if (moved && next.phase == JourneyPhase.NAP_AWAKE_PROMPT) effects += JourneyEffect.WakeReminder
        return JourneyUpdate(next, effects.toList())
    }
}
