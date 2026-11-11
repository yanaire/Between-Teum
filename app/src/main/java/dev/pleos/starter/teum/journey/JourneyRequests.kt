package dev.pleos.starter.teum.journey

/** Browser may express user intent only. No timestamps, safety, outcomes or measurements. */
sealed interface JourneyRequest {
    data object Continue : JourneyRequest
    data class ChooseFeeling(val value: Feeling) : JourneyRequest
    data class SetEyesClosed(val value: Boolean) : JourneyRequest
    data class SubmitIntention(val text: String) : JourneyRequest
    data object ConfirmIntention : JourneyRequest
    data object RetryIntention : JourneyRequest
    data object SkipIntention : JourneyRequest
    data class ChooseAtmosphere(val value: JourneyAtmosphere) : JourneyRequest
    data object Finish : JourneyRequest
    data object Stop : JourneyRequest
    data object WakeNow : JourneyRequest
    data object AcknowledgeAwake : JourneyRequest
    data object RecordSpeech : JourneyRequest
    data object SpeechDone : JourneyRequest
    data object SpeakAgain : JourneyRequest
    data class SelectMusic(val choice: MusicChoice) : JourneyRequest
    data object ListenMusic : JourneyRequest
    data object ChangeMusic : JourneyRequest
    data class ChooseDayColor(val value: JourneyAtmosphere) : JourneyRequest
    data class SubmitStory(val text: String) : JourneyRequest
    data object SkipStory : JourneyRequest
    data class ChooseQuickMood(val value: QuickMood) : JourneyRequest
}

/** Effects are requests, never evidence that playback, recording or vehicle control succeeded. */
sealed interface JourneyEffect {
    data object StopOwnedEffects : JourneyEffect
    data class PhaseEntered(val phase: JourneyPhase) : JourneyEffect
    data class CaptureSpeech(val token: JourneyToken, val round: Int) : JourneyEffect
    data class FinishSpeech(val token: JourneyToken, val round: Int) : JourneyEffect
    data class CaptureText(val token: JourneyToken, val target: JourneyTextTarget) : JourneyEffect
    data class FinishText(val token: JourneyToken, val target: JourneyTextTarget) : JourneyEffect
    data class PlayMusic(val token: JourneyToken, val choice: MusicChoice) : JourneyEffect
    data object WakeReminder : JourneyEffect
}

/** Native callback correlation; refreshes only when a phase changes. Never trust a web token as a native result. */
data class JourneyToken(val sessionId: String, val revision: Long)
enum class JourneyTextTarget { INTENTION, STORY }
data class JourneyUpdate(val snapshot: JourneySnapshot, val effects: List<JourneyEffect> = emptyList())

/** Stage rejection is an expected protocol error, including stale screen requests. */
class JourneyRejected(message: String) : IllegalStateException(message)

/** Mood changes cadence, not fabricated completion or any hardware state. */
data class BreathingCadence(val inhaleMs: Long, val extraInhaleMs: Long, val exhaleMs: Long)

fun JourneySnapshot.breathingCadence(): BreathingCadence = when (feeling) {
    Feeling.TENSE -> BreathingCadence(4000, 1000, 6000)
    Feeling.HEAVY -> BreathingCadence(4000, 0, 6000)
    Feeling.OKAY, null -> BreathingCadence(4000, 0, 4000)
}
