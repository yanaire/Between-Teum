package dev.pleos.starter.teum.journey

import dev.pleos.starter.teum.records.EveningReflection

/** User transitions only. Timed and measured transitions live in the native owner. */
internal object JourneyTransitions {
    fun apply(s: JourneySnapshot, request: JourneyRequest): JourneySnapshot {
        fun at(vararg phases: JourneyPhase) {
            if (s.phase !in phases) throw JourneyRejected("Intent is invalid at ${s.phase}")
        }
        fun go(phase: JourneyPhase) = s.copy(phase = phase)
        fun text(value: String): String {
            require(value.isNotBlank() && value.length <= 4000)
            return value
        }
        return when (request) {
            is ReconfigureEntry -> {
                at(JourneyPhase.ENTRY_REVIEW)
                if (request.choice !in s.plan.entryChoices) throw JourneyRejected("No such original entry choice")
                val selected = request.choice.plan
                val credits = s.creditedSteps()
                s.copy(plan = selected,
                    phase = if (selected.route == JourneyRoute.NAP) JourneyPhase.ENTRY_REVIEW else selected.phases.first(),
                    facts = s.facts.copy(kind = selected.kind), historicalSteps = credits,
                    completedMask = selected.maskFor(credits))
            }
            Previous -> when (s.phase) {
                JourneyPhase.MORNING_FEELING -> go(
                    if (s.plan.route == JourneyRoute.SHORT_MORNING) JourneyPhase.ENTRY_REVIEW
                    else JourneyPhase.MORNING_GREETING)
                JourneyPhase.MORNING_BREATHING, JourneyPhase.INTENTION -> go(JourneyPhase.MORNING_FEELING)
                JourneyPhase.INTENTION_REVIEW -> go(JourneyPhase.INTENTION)
                JourneyPhase.ATMOSPHERE -> go(
                    if (s.facts.intention.isNullOrBlank()) JourneyPhase.INTENTION else JourneyPhase.INTENTION_REVIEW)
                JourneyPhase.ENCOURAGEMENT -> go(
                    if (s.plan.route != JourneyRoute.SHORT_MORNING) JourneyPhase.ATMOSPHERE
                    else if (s.facts.intention.isNullOrBlank()) JourneyPhase.INTENTION else JourneyPhase.INTENTION_REVIEW)
                JourneyPhase.NAP_CHECK, JourneyPhase.SPEECH_FIRST, JourneyPhase.EVENING_ARRIVAL ->
                    go(JourneyPhase.ENTRY_REVIEW)
                JourneyPhase.NAP_POSTURE -> go(JourneyPhase.NAP_CHECK)
                JourneyPhase.SPEECH_FIRST_REVIEW -> go(JourneyPhase.SPEECH_FIRST)
                JourneyPhase.SPEECH_SECOND -> go(JourneyPhase.SPEECH_FIRST_REVIEW)
                JourneyPhase.SPEECH_COMPARISON -> go(JourneyPhase.SPEECH_SECOND)
                JourneyPhase.MUSIC_SELECTION -> go(
                    if (s.facts.secondSpeech == null) JourneyPhase.SPEECH_FIRST_REVIEW else JourneyPhase.SPEECH_COMPARISON)
                JourneyPhase.MUSIC_FOCUS -> go(JourneyPhase.MUSIC_SELECTION).copy(playback = PlaybackState.UNREQUESTED)
                JourneyPhase.EVENING_COLOR -> go(JourneyPhase.EVENING_ARRIVAL)
                JourneyPhase.EVENING_STORY -> go(JourneyPhase.EVENING_COLOR)
                JourneyPhase.EVENING_WEEK -> go(
                    if (s.plan.route == JourneyRoute.SHORT_EVENING) JourneyPhase.EVENING_COLOR else JourneyPhase.EVENING_STORY)
                JourneyPhase.EVENING_FAREWELL, JourneyPhase.EVENING_DAY_REVIEW -> go(JourneyPhase.EVENING_WEEK)
                JourneyPhase.QUICK_FEELING -> go(JourneyPhase.QUICK_BREATHING)
                else -> throw JourneyRejected("No previous control at ${s.phase}")
            }
            ReviewDay -> {
                at(JourneyPhase.EVENING_WEEK)
                go(JourneyPhase.EVENING_DAY_REVIEW)
            }
            JourneyRequest.Continue -> when (s.phase) {
                JourneyPhase.ENTRY_REVIEW -> go(s.plan.phases.first())
                JourneyPhase.MORNING_GREETING -> go(JourneyPhase.MORNING_FEELING)
                JourneyPhase.NAP_CHECK -> go(JourneyPhase.NAP_POSTURE)
                JourneyPhase.NAP_POSTURE -> go(JourneyPhase.NAP_SETTLING).copy(napStartedMs = s.elapsedMs)
                JourneyPhase.SPEECH_FIRST_REVIEW, JourneyPhase.SPEECH_COMPARISON -> go(JourneyPhase.MUSIC_SELECTION)
                JourneyPhase.EVENING_ARRIVAL -> go(JourneyPhase.EVENING_COLOR)
                JourneyPhase.EVENING_WEEK -> go(JourneyPhase.EVENING_FAREWELL)
                else -> throw JourneyRejected("This stage requires a specific intent or native observation")
            }
            is JourneyRequest.ChooseFeeling -> {
                at(JourneyPhase.MORNING_FEELING)
                go(JourneyPhase.MORNING_BREATHING).copy(feeling = request.value,
                    facts = s.facts.copy(mood = request.value.name))
            }
            is JourneyRequest.SetEyesClosed -> {
                at(JourneyPhase.MORNING_BREATHING)
                s.copy(eyesClosed = request.value)
            }
            is JourneyRequest.SubmitIntention -> {
                at(JourneyPhase.INTENTION)
                go(JourneyPhase.INTENTION_REVIEW).copy(facts = s.facts.copy(intention = text(request.text)))
            }
            JourneyRequest.ConfirmIntention -> {
                at(JourneyPhase.INTENTION_REVIEW)
                go(if (s.plan.route == JourneyRoute.SHORT_MORNING) JourneyPhase.ENCOURAGEMENT else JourneyPhase.ATMOSPHERE)
            }
            JourneyRequest.RetryIntention -> {
                at(JourneyPhase.INTENTION_REVIEW)
                go(JourneyPhase.INTENTION).copy(facts = s.facts.copy(intention = null))
            }
            JourneyRequest.SkipIntention -> {
                at(JourneyPhase.INTENTION)
                go(if (s.plan.route == JourneyRoute.SHORT_MORNING) JourneyPhase.ENCOURAGEMENT else JourneyPhase.ATMOSPHERE)
                    .copy(facts = s.facts.copy(intention = null))
            }
            is JourneyRequest.ChooseAtmosphere -> {
                at(JourneyPhase.ATMOSPHERE)
                go(JourneyPhase.ENCOURAGEMENT).copy(atmosphere = request.value,
                    facts = s.facts.copy(atmosphere = request.value.name))
            }
            JourneyRequest.Finish -> {
                at(JourneyPhase.ENCOURAGEMENT, JourneyPhase.NAP_SUMMARY, JourneyPhase.MUSIC_FOCUS, JourneyPhase.EVENING_FAREWELL)
                if (s.plan.route == JourneyRoute.NAP && !s.awakeAcknowledged) throw JourneyRejected("Wake acknowledgment required")
                s.copy(status = JourneyStatus.COMPLETED, completedMask = (1 shl s.plan.totalSteps) - 1)
            }
            JourneyRequest.Stop -> s.copy(status = JourneyStatus.INTERRUPTED, interruption = JourneyInterruption.USER)
            JourneyRequest.WakeNow -> {
                at(JourneyPhase.NAP_SETTLING, JourneyPhase.NAP_SLEEPING)
                go(JourneyPhase.NAP_WAKING).copy(napSleptMs = s.elapsedMs - requireNotNull(s.napStartedMs))
            }
            JourneyRequest.AcknowledgeAwake -> {
                at(JourneyPhase.NAP_SUMMARY, JourneyPhase.NAP_AWAKE_PROMPT)
                go(JourneyPhase.NAP_SUMMARY).copy(awakeAcknowledged = true)
            }
            JourneyRequest.RecordSpeech -> {
                at(JourneyPhase.SPEECH_FIRST, JourneyPhase.SPEECH_SECOND, JourneyPhase.INTENTION, JourneyPhase.EVENING_STORY)
                if (s.speechCapture !in setOf(SpeechCapture.IDLE, SpeechCapture.UNAVAILABLE)) throw JourneyRejected("Capture already pending")
                s.copy(speechCapture = SpeechCapture.RECORDING)
            }
            JourneyRequest.SpeechDone -> {
                at(JourneyPhase.SPEECH_FIRST, JourneyPhase.SPEECH_SECOND, JourneyPhase.INTENTION, JourneyPhase.EVENING_STORY)
                if (s.speechCapture != SpeechCapture.RECORDING) throw JourneyRejected("No recording to stop")
                s.copy(speechCapture = SpeechCapture.AWAITING_RESULT)
            }
            JourneyRequest.SpeakAgain -> {
                at(JourneyPhase.SPEECH_FIRST_REVIEW)
                go(JourneyPhase.SPEECH_SECOND).copy(speechCapture = SpeechCapture.IDLE)
            }
            is JourneyRequest.SelectMusic -> {
                at(JourneyPhase.MUSIC_SELECTION)
                s.copy(musicChoice = request.choice)
            }
            JourneyRequest.ListenMusic -> {
                at(JourneyPhase.MUSIC_SELECTION)
                if (s.musicChoice == null) throw JourneyRejected("Choose music first")
                go(JourneyPhase.MUSIC_FOCUS).copy(playback = PlaybackState.REQUESTED)
            }
            JourneyRequest.ChangeMusic -> {
                at(JourneyPhase.MUSIC_FOCUS)
                go(JourneyPhase.MUSIC_SELECTION).copy(playback = PlaybackState.UNREQUESTED)
            }
            is JourneyRequest.ChooseDayColor -> {
                at(JourneyPhase.EVENING_COLOR)
                go(if (s.plan.route == JourneyRoute.SHORT_EVENING) JourneyPhase.EVENING_WEEK else JourneyPhase.EVENING_STORY)
                    .copy(atmosphere = request.value,
                        facts = s.facts.copy(mood = request.value.name, atmosphere = request.value.name))
            }
            is JourneyRequest.SubmitStory -> {
                at(JourneyPhase.EVENING_STORY)
                go(JourneyPhase.EVENING_WEEK).copy(facts = s.facts.copy(reflection = EveningReflection(story = text(request.text))))
            }
            JourneyRequest.SkipStory -> {
                at(JourneyPhase.EVENING_STORY)
                go(JourneyPhase.EVENING_WEEK).copy(facts = s.facts.copy(reflection = null))
            }
            is JourneyRequest.ChooseQuickMood -> {
                at(JourneyPhase.QUICK_FEELING)
                s.copy(status = JourneyStatus.COMPLETED, completedMask = (1 shl s.plan.totalSteps) - 1,
                    facts = s.facts.copy(mood = request.value.name))
            }
        }
    }
}
