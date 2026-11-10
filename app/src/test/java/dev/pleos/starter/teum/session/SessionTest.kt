package dev.pleos.starter.teum.session

import org.junit.Assert.*
import org.junit.Test

class SessionTest {
    private val noon = 12 * 3600_000L + 35 * 60_000L
    private fun plan(situation: Situation, seconds: Long, travel: Long): Schedule =
        (Planner.plan(PlanRequest(situation, noon, TimeInput.Duration(seconds), travel)) as PlanResult.Available).schedule

    @Test fun lunchDeadlineUsesTravelOnce() {
        val result = Planner.plan(PlanRequest(Situation.LUNCH, noon, TimeInput.Deadline(13 * 3600_000L), 180))
        val schedule = (result as PlanResult.Available).schedule
        assertEquals(ScheduleKind.FULL, schedule.kind)
        assertEquals(noon + 60_000, schedule.activityStartWallMillis)
        assertEquals(noon + 19 * 60_000, schedule.activityEndWallMillis)
        assertEquals(noon + 22 * 60_000, schedule.readyWallMillis)
        assertEquals(13 * 3600_000L, schedule.plannedReturnWallMillis)
    }

    @Test fun fullBudgetsAndMinimumBoundaries() {
        data class Case(val situation: Situation, val prepare: Long, val activity: Long, val finish: Long, val travel: Long)
        for ((situation, prepare, activity, finish, travel) in listOf(
            Case(Situation.COMMUTE, 30, 240, 30, 30), Case(Situation.LUNCH, 60, 1080, 180, 180),
            Case(Situation.MEETING, 30, 330, 60, 60), Case(Situation.GOING_HOME, 30, 240, 30, 30)
        )) {
            val total = prepare + activity + finish + travel
            val full = plan(situation, total, travel)
            assertEquals(ScheduleKind.FULL, full.kind)
            assertEquals(prepare, full.prepareSeconds)
            assertEquals(activity, full.activitySeconds)
            assertEquals(finish, full.finishSeconds)
            assertEquals(travel, full.travelSeconds)
            assertEquals(noon + prepare * 1000, full.activityStartWallMillis)
            assertEquals(noon + (prepare + activity) * 1000, full.activityEndWallMillis)
            assertEquals(noon + (prepare + activity + finish) * 1000, full.readyWallMillis)
            assertEquals(noon + total * 1000, full.plannedReturnWallMillis)
            assertEquals(ScheduleKind.SHORT, plan(situation, total - 1, travel).kind)
            val minimum = if (situation == Situation.LUNCH) 600L else 150L
            val minimumTravel = if (situation == Situation.LUNCH) 180L else 0L
            val short = plan(situation, minimum, minimumTravel)
            assertEquals(ScheduleKind.SHORT, short.kind)
            assertEquals(if (situation == Situation.LUNCH) 60L else 30L, short.prepareSeconds)
            assertEquals(if (situation == Situation.LUNCH) 180L else 60L, short.activitySeconds)
            assertEquals(if (situation == Situation.LUNCH) 180L else 60L, short.finishSeconds)
            assertFalse(short.vehicleControlsAllowed)
            assertTrue(short.requiresShortConfirmation)
            assertTrue(Planner.plan(PlanRequest(situation, noon, TimeInput.Duration(minimum - 1), minimumTravel)) is PlanResult.Insufficient)
        }
    }

    @Test fun invalidValuesAndMidnight() {
        assertTrue(Planner.plan(PlanRequest(Situation.LUNCH, noon, TimeInput.Duration(-1), 0)) is PlanResult.Invalid)
        assertTrue(Planner.plan(PlanRequest(Situation.LUNCH, noon, TimeInput.Deadline(noon - 1), 0)) is PlanResult.Invalid)
        assertTrue(Planner.plan(PlanRequest(Situation.LUNCH, noon, TimeInput.Duration(1000), -1)) is PlanResult.Invalid)
        assertTrue(Planner.plan(PlanRequest(Situation.LUNCH, noon, TimeInput.Duration(Long.MAX_VALUE), 0)) is PlanResult.Invalid)
        val start = 23 * 3600_000L + 55 * 60_000L
        val s = (Planner.plan(PlanRequest(Situation.COMMUTE, start, TimeInput.Deadline(start + 600_000), 30)) as PlanResult.Available).schedule
        assertEquals(start + 330_000, s.plannedReturnWallMillis)
    }

    @Test fun fractionalTicksDoNotShortenAnyFullActivity() {
        val observed = mutableListOf<SessionState>()
        for (situation in Situation.entries) {
            val total = when (situation) {
                Situation.LUNCH -> 1500L
                Situation.MEETING -> 420L
                else -> 300L
            }
            val s = plan(situation, total, if (situation == Situation.LUNCH) 180 else 0)
            val boundary = (s.prepareSeconds + s.activitySeconds) * 1000
            for (remaining in listOf(999L, 1L)) {
                val engine = SessionEngine.start(s, ClockReading(noon, 12_345), false)
                observed += engine.reevaluate(ClockReading(
                    noon + boundary - remaining, 12_345 + boundary - remaining
                ))
            }
        }
        assertEquals(List(8) { SessionState.ACTIVE }, observed)
    }

    @Test fun independentFractionalClocksKeepTheirOwnBoundaries() {
        val s = plan(Situation.COMMUTE, 300, 0)
        for ((wall, mono) in listOf(269_001L to 269_999L, 269_999L to 269_001L)) {
            val engine = SessionEngine.start(s, ClockReading(noon, 12_345), false)
            assertEquals(SessionState.ACTIVE,
                engine.reevaluate(ClockReading(noon + wall, 12_345 + mono)))
            // Monotonic activity ends even when wall time moved backwards.
            assertEquals(SessionState.FINISHING,
                engine.reevaluate(ClockReading(noon - 1000, 12_345 + 270_000)))
        }
    }

    @Test fun exactAndDelayedTicksFinishEveryFullActivity() {
        for (situation in Situation.entries) {
            val travel = if (situation == Situation.LUNCH) 180L else 30L
            val total = when (situation) {
                Situation.LUNCH -> 1500L
                Situation.MEETING -> 450L
                else -> 330L
            }
            val s = plan(situation, total, travel)
            val boundary = (s.prepareSeconds + s.activitySeconds) * 1000
            for (late in listOf(0L, 1L, 2501L)) {
                val engine = SessionEngine.start(s, ClockReading(noon, 12_345), false)
                assertEquals(SessionState.FINISHING, engine.reevaluate(
                    ClockReading(noon + boundary + late, 12_345 + boundary + late)))
                assertNull(engine.actualFinishWallMillis)
            }
        }
    }

    @Test fun actualWallAdvanceConsumesActivityButNeverInventsCompletion() {
        for (situation in Situation.entries) {
            val total = when (situation) {
                Situation.LUNCH -> 1500L
                Situation.MEETING -> 420L
                else -> 300L
            }
            val s = plan(situation, total, if (situation == Situation.LUNCH) 180 else 0)
            val wallBoundary = s.deadlineWallMillis - (s.finishSeconds + s.travelSeconds) * 1000
            val engine = SessionEngine.start(s, ClockReading(noon, 12_345), false)
            assertEquals(SessionState.ACTIVE,
                engine.reevaluate(ClockReading(wallBoundary - 1, 12_345 + 100_001)))
            assertEquals(SessionState.FINISHING,
                engine.reevaluate(ClockReading(wallBoundary, 12_345 + 100_002)))
            assertEquals(SessionState.FINISHING,
                engine.reevaluate(ClockReading(noon, 12_345 + 100_003)))
            assertNull(engine.actualFinishWallMillis)
            assertNull(engine.outcome)
        }
    }

    @Test fun elapsedAndWallClockAreIndependentAndFinishIsObserved() {
        val s = plan(Situation.LUNCH, 1500, 180)
        val engine = SessionEngine.start(s, ClockReading(noon, 1000), false)
        assertEquals(SessionState.ACTIVE, engine.reevaluate(ClockReading(noon - 600_000, 61_000)))
        assertEquals(SessionState.FINISHING, engine.reevaluate(ClockReading(noon + 20 * 60_000, 62_000)))
        assertNull(engine.actualFinishWallMillis)
        assertEquals(SessionState.FINISHING, engine.reevaluate(ClockReading(noon, 63_000)))
        engine.finishingDone(ClockReading(noon + 25 * 60_000, 64_000))
        assertEquals(SessionState.AWAITING_RESTORE, engine.approveRestore(ClockReading(noon + 25 * 60_000, 65_000), false))
        engine.approveRestore(ClockReading(noon + 25 * 60_000, 66_000), true)
        engine.restoreFinished(ClockReading(noon + 26 * 60_000, 67_000))
        assertEquals(noon + 26 * 60_000, engine.actualFinishWallMillis)
        assertEquals(66_000L, engine.actualElapsedMillis)
        assertEquals(Outcome.COMPLETED, engine.outcome)
    }

    @Test fun earlyCancelIsIdempotentAndParkingLossBlocksRestore() {
        val s = plan(Situation.COMMUTE, 330, 30)
        val engine = SessionEngine.start(s, ClockReading(noon, 0), false)
        assertEquals(SessionState.AWAITING_RESTORE, engine.finishEarly(ClockReading(noon + 1000, 1000)))
        assertEquals(SessionState.AWAITING_RESTORE, engine.finishEarly(ClockReading(noon + 2000, 2000)))
        assertEquals(Outcome.EARLY_FINISH, engine.outcome)
        assertEquals(SessionState.RESTRICTED, engine.parkingLost(ClockReading(noon + 3000, 3000)))
        assertEquals(SessionState.RESTRICTED, engine.finishEarly(ClockReading(noon + 4000, 4000)))
        assertEquals(Outcome.SAFETY_INTERRUPTED, engine.outcome)
        assertThrows(IllegalStateException::class.java) { engine.approveRestore(ClockReading(noon + 5000, 5000), true) }
    }

    @Test fun recoveryNeverReplaysOrCompletes() {
        val s = plan(Situation.LUNCH, 1500, 180)
        val recovered = SessionEngine.recoverInterrupted(s, ClockReading(noon + 600_000, 50))
        assertEquals(SessionState.INTERRUPTED, recovered.reevaluate(ClockReading(noon + 700_000, 60)))
        assertEquals(SessionState.INTERRUPTED, recovered.finishEarly(ClockReading(noon + 700_000, 60)))
        assertEquals(Outcome.RECOVERED_INTERRUPTED, recovered.outcome)
        assertNull(recovered.actualFinishWallMillis)
        assertThrows(IllegalStateException::class.java) { recovered.restoreFinished(ClockReading(noon + 700_000, 60)) }
    }

    @Test fun shortNeedsConfirmationAndElapsedCannotGoBackwards() {
        val s = plan(Situation.COMMUTE, 150, 0)
        assertThrows(IllegalArgumentException::class.java) { SessionEngine.start(s, ClockReading(noon, 100), false) }
        val engine = SessionEngine.start(s, ClockReading(noon, 100), true)
        assertThrows(IllegalArgumentException::class.java) { engine.reevaluate(ClockReading(noon, 99)) }
    }
}
