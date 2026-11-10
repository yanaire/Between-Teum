package dev.pleos.starter.teum.session

enum class SessionState {
    CONFIGURING, PREPARING, ACTIVE, FINISHING, AWAITING_RESTORE,
    RESTORING, COMPLETED, INTERRUPTED, RESTRICTED
}

enum class Outcome { COMPLETED, EARLY_FINISH, SAFETY_INTERRUPTED, RECOVERED_INTERRUPTED }

/** Independent epoch wall time and monotonic time since boot, both in milliseconds. */
data class ClockReading(val wallMillis: Long, val elapsedMillis: Long)

/**
 * A process-local state machine. The caller owns parking validation, cancellation,
 * persistence, notifications, and every vehicle command.
 */
class SessionEngine private constructor(
    val schedule: Schedule,
    private val started: ClockReading,
    initialState: SessionState
) {
    var state: SessionState = initialState
        private set
    var outcome: Outcome? = null
        private set
    var actualFinishWallMillis: Long? = null
        private set
    var actualElapsedMillis: Long? = null
        private set
    private var lastElapsedMillis: Long = started.elapsedMillis

    companion object {
        fun start(schedule: Schedule, now: ClockReading, shortConfirmed: Boolean): SessionEngine {
            require(now.elapsedMillis >= 0 && now.wallMillis >= 0)
            require(schedule.kind != ScheduleKind.SHORT || shortConfirmed)
            require(now.wallMillis >= schedule.startWallMillis && now.wallMillis <= schedule.deadlineWallMillis)
            return SessionEngine(schedule, now, SessionState.PREPARING).also { it.reevaluate(now) }
        }

        /** Never resumes pending work or produces a completed outcome. */
        fun recoverInterrupted(schedule: Schedule, now: ClockReading): SessionEngine {
            require(now.elapsedMillis >= 0 && now.wallMillis >= 0)
            return SessionEngine(schedule, now, SessionState.INTERRUPTED).also {
                it.outcome = Outcome.RECOVERED_INTERRUPTED
            }
        }
    }

    /** Call on ticks and foreground resume with fresh wall and monotonic readings. */
    fun reevaluate(now: ClockReading): SessionState {
        observe(now)
        if (state !in setOf(SessionState.PREPARING, SessionState.ACTIVE, SessionState.FINISHING)) return state
        val elapsed = (now.elapsedMillis - started.elapsedMillis) / 1000
        val wallRemainingMillis = (schedule.deadlineWallMillis - now.wallMillis).coerceAtLeast(0)
        val reserveMillis = (schedule.finishSeconds + schedule.travelSeconds) * 1000
        state = when {
            state == SessionState.FINISHING -> SessionState.FINISHING
            wallRemainingMillis <= reserveMillis -> SessionState.FINISHING
            elapsed >= schedule.prepareSeconds + schedule.activitySeconds -> SessionState.FINISHING
            elapsed >= schedule.prepareSeconds -> SessionState.ACTIVE
            else -> SessionState.PREPARING
        }
        return state
    }

    /** Caller must cancel activity, notifications and pending commands immediately. */
    fun finishEarly(now: ClockReading): SessionState {
        observe(now)
        if (state in setOf(SessionState.PREPARING, SessionState.ACTIVE, SessionState.FINISHING)) {
            outcome = Outcome.EARLY_FINISH
            state = SessionState.AWAITING_RESTORE
        }
        return state
    }

    /** Explicit observation that finishing work is done; a deadline is not completion. */
    fun finishingDone(now: ClockReading): SessionState {
        observe(now)
        check(state == SessionState.FINISHING)
        state = SessionState.AWAITING_RESTORE
        return state
    }

    /** Caller must freshly verify parking and UX, then obtain explicit consent. */
    fun approveRestore(now: ClockReading, parkingAndUxTrusted: Boolean): SessionState {
        observe(now)
        check(state == SessionState.AWAITING_RESTORE)
        if (parkingAndUxTrusted) state = SessionState.RESTORING
        return state
    }

    /** Also used when restoration is deliberately skipped; no command is issued here. */
    fun restoreFinished(now: ClockReading): SessionState {
        observe(now)
        check(state == SessionState.RESTORING || state == SessionState.AWAITING_RESTORE)
        state = SessionState.COMPLETED
        if (outcome == null) outcome = Outcome.COMPLETED
        actualFinishWallMillis = now.wallMillis
        actualElapsedMillis = now.elapsedMillis - started.elapsedMillis
        return state
    }

    /** The integration must cancel pending work and block all new/restore commands. */
    fun parkingLost(now: ClockReading): SessionState {
        observe(now)
        if (state !in setOf(SessionState.COMPLETED, SessionState.INTERRUPTED, SessionState.RESTRICTED)) {
            state = SessionState.RESTRICTED
            outcome = Outcome.SAFETY_INTERRUPTED
            actualFinishWallMillis = now.wallMillis
            actualElapsedMillis = now.elapsedMillis - started.elapsedMillis
        }
        return state
    }

    private fun observe(now: ClockReading) {
        require(now.wallMillis >= 0 && now.elapsedMillis >= lastElapsedMillis)
        lastElapsedMillis = now.elapsedMillis
    }
}
