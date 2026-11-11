package dev.pleos.starter.teum.journey

import dev.pleos.starter.teum.content.SituationIds
import dev.pleos.starter.teum.records.ActivityOutcome
import dev.pleos.starter.teum.records.ActivityRecord
import dev.pleos.starter.teum.records.JourneyFacts
import dev.pleos.starter.teum.records.JourneyKind
import dev.pleos.starter.teum.records.newActivityRecord
import dev.pleos.starter.teum.session.ClockReading

/** Adapt the host's SessionClock with JourneyClock { sessionClock.reading() }. */
fun interface JourneyClock { fun reading(): ClockReading }

enum class JourneyRoute { MORNING, SHORT_MORNING, NAP, PREPARATION, EVENING, SHORT_EVENING, QUICK_BREATHING }
enum class JourneyStatus { ACTIVE, COMPLETED, INTERRUPTED, SAFETY_INTERRUPTED, RECOVERED_INTERRUPTED }
enum class Feeling { TENSE, HEAVY, OKAY }
enum class JourneyAtmosphere { CALM, FIRM, WARM, FOCUS, FLEX }
enum class QuickMood { STIFLED, WRONGED, TIRED, BETTER }
enum class MusicChoice { SUPPORT_SONG, RECENT, RECOMMENDATION }
enum class PlaybackState { UNREQUESTED, REQUESTED, PLAYING, ENDED, UNAVAILABLE }
enum class SpeechCapture { IDLE, RECORDING, AWAITING_RESULT, UNAVAILABLE }
enum class JourneyInterruption { USER, BACKGROUND, SAFETY, CLOCK }

/** Native intents choose a legal predecessor, never a browser-supplied phase. */
data object Previous : JourneyRequest
data object ReviewDay : JourneyRequest
data class ReconfigureEntry(val choice: EntryChoice) : JourneyRequest

/** Only choices present on the original invitations, not arbitrary route replacement. */
enum class EntryChoice(val route: JourneyRoute, val napMinutes: Int = 20) {
    MORNING_FULL(JourneyRoute.MORNING), MORNING_SHORT(JourneyRoute.SHORT_MORNING),
    NAP_10(JourneyRoute.NAP, 10), NAP_15(JourneyRoute.NAP, 15), NAP_20(JourneyRoute.NAP, 20),
    PREPARATION(JourneyRoute.PREPARATION), QUICK(JourneyRoute.QUICK_BREATHING),
    EVENING_FULL(JourneyRoute.EVENING), EVENING_SHORT(JourneyRoute.SHORT_EVENING);

    val plan: JourneyPlan get() = JourneyPlan(route, napMinutes)
}

enum class JourneyPhase(val screenId: String?, val step: Int) {
    MORNING_GREETING("F1-01", 0), MORNING_FEELING("F1-02", 1),
    MORNING_BREATHING("F1-03", 2), INTENTION("F1-04", 3),
    INTENTION_REVIEW("F1-04a", 3), ATMOSPHERE("F1-05", 4), ENCOURAGEMENT("F1-06", 5),
    NAP_CHECK("F2-02", 0), NAP_POSTURE("F2-03", 1),
    NAP_SETTLING("F2-04", 2), NAP_SLEEPING("F2-05", 2),
    NAP_WAKING("F2-06", 3), NAP_SUMMARY("F2-07", 4), NAP_AWAKE_PROMPT("F2-07a", 4),
    SPEECH_FIRST("F3-03", 0), SPEECH_FIRST_REVIEW("F3-04", 1),
    SPEECH_SECOND("F3-03", 0), SPEECH_COMPARISON("F3-05", 1),
    MUSIC_SELECTION("F3-06", 2), MUSIC_FOCUS("F3-07", 3),
    EVENING_ARRIVAL("F4-02", 0), EVENING_COLOR("F4-03", 1),
    EVENING_STORY("F4-04", 2), EVENING_WEEK("F4-05", 3), EVENING_FAREWELL("F4-06", 4),
    QUICK_COUNTDOWN("Q1-01", 0), QUICK_BREATHING("Q1-B", 1), QUICK_FEELING("Q1-02", 2),
    ENTRY_REVIEW(null, -1), EVENING_DAY_REVIEW("R-02", 3)
}

data class JourneyPlan(val route: JourneyRoute, val napMinutes: Int = 20) {
    init {
        require(napMinutes in setOf(10, 15, 20))
        require(route == JourneyRoute.NAP || napMinutes == 20)
    }
    val nominalMinutes: Int get() = when (route) {
        JourneyRoute.MORNING -> 7
        JourneyRoute.SHORT_MORNING, JourneyRoute.QUICK_BREATHING -> 3
        JourneyRoute.NAP -> napMinutes
        JourneyRoute.PREPARATION, JourneyRoute.EVENING -> 10
        JourneyRoute.SHORT_EVENING -> 5
    }
    val kind: JourneyKind get() = when (route) {
        JourneyRoute.MORNING -> JourneyKind.MORNING
        JourneyRoute.SHORT_MORNING, JourneyRoute.QUICK_BREATHING -> JourneyKind.QUICK_BREATHING
        JourneyRoute.NAP -> JourneyKind.NAP
        JourneyRoute.PREPARATION -> JourneyKind.PREPARATION
        JourneyRoute.EVENING, JourneyRoute.SHORT_EVENING -> JourneyKind.EVENING
    }
    val situationId: String get() = when (kind) {
        JourneyKind.MORNING, JourneyKind.QUICK_BREATHING -> SituationIds.COMMUTE_MORNING
        JourneyKind.NAP -> SituationIds.LUNCH_BREAK
        JourneyKind.PREPARATION -> SituationIds.MEETING
        JourneyKind.EVENING -> SituationIds.COMMUTE_EVENING
    }
    val title: String get() = when (kind) {
        JourneyKind.MORNING -> "아침 준비"
        JourneyKind.QUICK_BREATHING -> "3분 숨 고르기"
        JourneyKind.NAP -> "점심 낮잠"
        JourneyKind.PREPARATION -> "마음 준비"
        JourneyKind.EVENING -> "하루 마무리"
    }
    val phases: List<JourneyPhase> get() = when (route) {
        JourneyRoute.MORNING, JourneyRoute.SHORT_MORNING -> listOf(
            JourneyPhase.MORNING_GREETING, JourneyPhase.MORNING_FEELING, JourneyPhase.MORNING_BREATHING,
            JourneyPhase.INTENTION, JourneyPhase.INTENTION_REVIEW, JourneyPhase.ATMOSPHERE, JourneyPhase.ENCOURAGEMENT)
            .filter { route != JourneyRoute.SHORT_MORNING || it !in setOf(JourneyPhase.MORNING_GREETING, JourneyPhase.ATMOSPHERE) }
        JourneyRoute.NAP -> listOf(JourneyPhase.NAP_CHECK, JourneyPhase.NAP_POSTURE,
            JourneyPhase.NAP_SETTLING, JourneyPhase.NAP_SLEEPING, JourneyPhase.NAP_WAKING,
            JourneyPhase.NAP_SUMMARY, JourneyPhase.NAP_AWAKE_PROMPT)
        JourneyRoute.PREPARATION -> listOf(JourneyPhase.SPEECH_FIRST, JourneyPhase.SPEECH_FIRST_REVIEW,
            JourneyPhase.SPEECH_SECOND, JourneyPhase.SPEECH_COMPARISON, JourneyPhase.MUSIC_SELECTION, JourneyPhase.MUSIC_FOCUS)
        JourneyRoute.EVENING, JourneyRoute.SHORT_EVENING -> listOf(JourneyPhase.EVENING_ARRIVAL,
            JourneyPhase.EVENING_COLOR, JourneyPhase.EVENING_STORY, JourneyPhase.EVENING_WEEK, JourneyPhase.EVENING_FAREWELL)
            .filter { route != JourneyRoute.SHORT_EVENING || it != JourneyPhase.EVENING_STORY }
        JourneyRoute.QUICK_BREATHING -> listOf(JourneyPhase.QUICK_COUNTDOWN, JourneyPhase.QUICK_BREATHING, JourneyPhase.QUICK_FEELING)
    } + when (route) {
        JourneyRoute.SHORT_MORNING, JourneyRoute.NAP, JourneyRoute.PREPARATION -> listOf(JourneyPhase.ENTRY_REVIEW)
        JourneyRoute.EVENING, JourneyRoute.SHORT_EVENING ->
            listOf(JourneyPhase.ENTRY_REVIEW, JourneyPhase.EVENING_DAY_REVIEW)
        JourneyRoute.MORNING, JourneyRoute.QUICK_BREATHING -> emptyList()
    }
    val totalSteps: Int get() = phases.filter { it.step >= 0 }.map { it.step }.distinct().size
    fun stepIndex(phase: JourneyPhase): Int =
        phases.filter { it.step >= 0 }.map { it.step }.distinct().indexOf(phase.step)
    /** Canonical phase names keep credit meaningful when positional step indexes change. */
    val steps: List<JourneyPhase> get() = phases.filter { it.step >= 0 }.distinctBy { it.step }
    val entryChoices: List<EntryChoice> get() = when (route) {
        JourneyRoute.SHORT_MORNING -> listOf(EntryChoice.MORNING_FULL, EntryChoice.MORNING_SHORT)
        JourneyRoute.NAP -> listOf(EntryChoice.NAP_10, EntryChoice.NAP_15, EntryChoice.NAP_20)
        JourneyRoute.PREPARATION -> listOf(EntryChoice.PREPARATION, EntryChoice.QUICK)
        JourneyRoute.EVENING, JourneyRoute.SHORT_EVENING -> listOf(EntryChoice.EVENING_FULL, EntryChoice.EVENING_SHORT)
        JourneyRoute.MORNING, JourneyRoute.QUICK_BREATHING -> emptyList()
    }

    fun maskFor(credits: Set<JourneyPhase>): Int = steps.foldIndexed(0) { index, mask, phase ->
        if (phase in credits) mask or (1 shl index) else mask
    }
}

/** All timing here is last observed foreground time, never a planned budget. */
data class JourneySnapshot(
    val plan: JourneyPlan,
    val sessionId: String,
    val recordId: String,
    val phase: JourneyPhase,
    val status: JourneyStatus,
    val facts: JourneyFacts,
    val elapsedMs: Long = 0,
    val phaseStartedMs: Long = 0,
    val observedWallMs: Long,
    val completedMask: Int = 0,
    val feeling: Feeling? = null,
    val atmosphere: JourneyAtmosphere? = null,
    val musicChoice: MusicChoice? = null,
    val playback: PlaybackState = PlaybackState.UNREQUESTED,
    val speechCapture: SpeechCapture = SpeechCapture.IDLE,
    val awakeAcknowledged: Boolean = false,
    val eyesClosed: Boolean = false,
    val revision: Long = 0,
    val interruption: JourneyInterruption? = null,
    val napStartedMs: Long? = null,
    val napSleptMs: Long? = null,
    val historicalSteps: Set<JourneyPhase>? = null
) {
    val active: Boolean get() = status == JourneyStatus.ACTIVE
    val screenId: String get() = when {
        eyesClosed -> "F1-03a"
        phase == JourneyPhase.ENTRY_REVIEW -> when (plan.route) {
            JourneyRoute.SHORT_MORNING -> "M-F1"
            JourneyRoute.NAP -> "F2-01"
            JourneyRoute.PREPARATION -> "F3-01"
            JourneyRoute.EVENING, JourneyRoute.SHORT_EVENING -> "F4-01"
            JourneyRoute.MORNING, JourneyRoute.QUICK_BREATHING -> error("Plan has no entry review")
        }
        else -> requireNotNull(phase.screenId)
    }
    val phaseElapsedMs: Long get() = elapsedMs - phaseStartedMs
    fun creditedSteps(): Set<JourneyPhase> = historicalSteps ?: plan.steps.filterIndexed { index, _ ->
        completedMask and (1 shl index) != 0
    }.toSet()

    fun validate() {
        require(sessionId.isNotBlank() && recordId.isNotBlank())
        require(facts.nativeActivityId == sessionId && facts.kind == plan.kind)
        facts.validate()
        require(facts.startedAtEpochMs != null)
        require(phase in plan.phases && elapsedMs >= 0 && phaseStartedMs in 0..elapsedMs)
        require(observedWallMs > 0 && revision >= 0)
        require(completedMask in 0 until (1 shl plan.totalSteps))
        historicalSteps?.let { credits ->
            val canonical = JourneyRoute.entries.flatMap { JourneyPlan(it).steps }.toSet()
            require(credits.all { it in canonical })
            require(plan.maskFor(credits) == completedMask)
        }
        require(!active || (facts.finishedAtEpochMs == null && interruption == null))
        require(status != JourneyStatus.COMPLETED || facts.finishedAtEpochMs != null)
        require(status != JourneyStatus.COMPLETED || completedMask == (1 shl plan.totalSteps) - 1)
        require(!eyesClosed || phase == JourneyPhase.MORNING_BREATHING)
        require(napStartedMs == null || (plan.route == JourneyRoute.NAP && napStartedMs in 0..elapsedMs))
        require(napSleptMs == null || (napStartedMs != null && napSleptMs in 0..elapsedMs - napStartedMs))
        require(!awakeAcknowledged || plan.route == JourneyRoute.NAP)
    }

    fun toActivityRecord(savedAtEpochMs: Long): ActivityRecord {
        validate()
        check(!active)
        val outcome = when (status) {
            JourneyStatus.COMPLETED -> ActivityOutcome.COMPLETED
            JourneyStatus.SAFETY_INTERRUPTED -> ActivityOutcome.SAFETY_INTERRUPTED
            JourneyStatus.INTERRUPTED, JourneyStatus.RECOVERED_INTERRUPTED -> ActivityOutcome.INTERRUPTED
            JourneyStatus.ACTIVE -> error("Active journey has no final record")
        }
        return newActivityRecord(
            situationId = plan.situationId, activityTitle = plan.title, totalSteps = plan.totalSteps,
            completedStepIndexes = (0 until plan.totalSteps).filter { completedMask and (1 shl it) != 0 },
            elapsedSeconds = elapsedMs / 1000, outcome = outcome, nowEpochMs = savedAtEpochMs,
            journeyFacts = facts
        ).copy(id = recordId)
    }
}
