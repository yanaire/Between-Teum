package dev.pleos.starter.teum.journey

/** A late tick enters the next observed phase now; it never consumes an unseen wake phase. */
internal object JourneyTimers {
    const val BREATHING_MS = 180_000L
    const val WAKE_MS = 8_000L

    fun advance(s: JourneySnapshot): JourneySnapshot = when (s.phase) {
        JourneyPhase.MORNING_GREETING -> if (s.phaseElapsedMs >= 7000) s.copy(phase = JourneyPhase.MORNING_FEELING) else s
        JourneyPhase.MORNING_BREATHING -> if (s.phaseElapsedMs >= BREATHING_MS) s.copy(phase = JourneyPhase.INTENTION) else s
        JourneyPhase.QUICK_COUNTDOWN -> if (s.phaseElapsedMs >= 3000) s.copy(phase = JourneyPhase.QUICK_BREATHING) else s
        JourneyPhase.QUICK_BREATHING -> if (s.phaseElapsedMs >= BREATHING_MS) s.copy(phase = JourneyPhase.QUICK_FEELING) else s
        JourneyPhase.NAP_SETTLING, JourneyPhase.NAP_SLEEPING -> {
            val slept = s.elapsedMs - requireNotNull(s.napStartedMs)
            when {
                slept >= s.plan.napMinutes * 60_000L -> s.copy(phase = JourneyPhase.NAP_WAKING, napSleptMs = slept)
                s.phase == JourneyPhase.NAP_SETTLING && s.phaseElapsedMs >= 7000 -> s.copy(phase = JourneyPhase.NAP_SLEEPING)
                else -> s
            }
        }
        JourneyPhase.NAP_WAKING -> if (s.phaseElapsedMs >= WAKE_MS) s.copy(phase = JourneyPhase.NAP_SUMMARY) else s
        JourneyPhase.NAP_SUMMARY -> if (!s.awakeAcknowledged && s.phaseElapsedMs >= 25_000) s.copy(phase = JourneyPhase.NAP_AWAKE_PROMPT) else s
        JourneyPhase.EVENING_ARRIVAL -> if (s.phaseElapsedMs >= 6500) s.copy(phase = JourneyPhase.EVENING_COLOR) else s
        else -> s
    }

    fun remainingMs(s: JourneySnapshot): Long? = when (s.phase) {
        JourneyPhase.NAP_SETTLING, JourneyPhase.NAP_SLEEPING ->
            (s.plan.napMinutes * 60_000L - (s.elapsedMs - requireNotNull(s.napStartedMs))).coerceAtLeast(0)
        JourneyPhase.NAP_WAKING -> (WAKE_MS - s.phaseElapsedMs).coerceAtLeast(0)
        JourneyPhase.MORNING_BREATHING, JourneyPhase.QUICK_BREATHING ->
            (BREATHING_MS - s.phaseElapsedMs).coerceAtLeast(0)
        JourneyPhase.QUICK_COUNTDOWN -> (3000 - s.phaseElapsedMs).coerceAtLeast(0)
        else -> null
    }
}
