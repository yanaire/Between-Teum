package dev.pleos.starter.teum.session

enum class Situation { COMMUTE, LUNCH, MEETING, GOING_HOME }
enum class ScheduleKind { FULL, SHORT }

sealed class TimeInput {
    data class Duration(val seconds: Long) : TimeInput()
    data class Deadline(val epochMillis: Long) : TimeInput()
}

data class PlanRequest(
    val situation: Situation,
    val startWallMillis: Long,
    val time: TimeInput,
    val travelSeconds: Long
)

data class Schedule(
    val situation: Situation,
    val kind: ScheduleKind,
    val startWallMillis: Long,
    val deadlineWallMillis: Long,
    val prepareSeconds: Long,
    val activitySeconds: Long,
    val finishSeconds: Long,
    val travelSeconds: Long
) {
    val activityStartWallMillis: Long get() = startWallMillis + prepareSeconds * 1000
    val activityEndWallMillis: Long get() = activityStartWallMillis + activitySeconds * 1000
    val readyWallMillis: Long get() = activityEndWallMillis + finishSeconds * 1000
    val plannedReturnWallMillis: Long get() = readyWallMillis + travelSeconds * 1000
    val requiresShortConfirmation: Boolean get() = kind == ScheduleKind.SHORT
    val vehicleControlsAllowed: Boolean get() = kind == ScheduleKind.FULL
}

sealed class PlanResult {
    data class Available(val schedule: Schedule) : PlanResult()
    data class Invalid(val reason: String) : PlanResult()
    data class Insufficient(val minimumSeconds: Long) : PlanResult()
}

object Planner {
    fun plan(request: PlanRequest): PlanResult {
        if (request.startWallMillis < 0 || request.travelSeconds < 0) {
            return PlanResult.Invalid("negative start or travel")
        }
        return try {
            val travelMillis = Math.multiplyExact(request.travelSeconds, 1000L)
            val deadline = when (val time = request.time) {
                is TimeInput.Duration -> {
                    if (time.seconds < 0) return PlanResult.Invalid("negative duration")
                    Math.addExact(request.startWallMillis, Math.multiplyExact(time.seconds, 1000L))
                }
                is TimeInput.Deadline -> time.epochMillis
            }
            val available = Math.subtractExact(deadline, request.startWallMillis)
            if (available < 0) return PlanResult.Invalid("deadline before start")
            val (prepare, fullActivity, finish) = when (request.situation) {
                Situation.COMMUTE -> Triple(30L, 240L, 30L)
                Situation.LUNCH -> Triple(60L, 1080L, 180L)
                Situation.MEETING -> Triple(30L, 330L, 60L)
                Situation.GOING_HOME -> Triple(30L, 240L, 30L)
            }
            val shortFinish = if (request.situation == Situation.LUNCH) 180L else 60L
            val shortActivity = if (request.situation == Situation.LUNCH) 180L else 60L
            val minimum = Math.addExact(prepare + shortActivity + shortFinish, request.travelSeconds)
            val fullMillis = Math.addExact((prepare + fullActivity + finish) * 1000L, travelMillis)
            val minimumMillis = Math.multiplyExact(minimum, 1000L)
            if (available < minimumMillis) return PlanResult.Insufficient(minimum)
            val full = available >= fullMillis
            val actualFinish = if (full) finish else shortFinish
            val activity = if (full) fullActivity else
                minOf(fullActivity, (available - travelMillis) / 1000 - prepare - actualFinish)
            PlanResult.Available(Schedule(request.situation, if (full) ScheduleKind.FULL else ScheduleKind.SHORT,
                request.startWallMillis, deadline, prepare, activity, actualFinish, request.travelSeconds))
        } catch (_: ArithmeticException) {
            PlanResult.Invalid("time out of range")
        }
    }
}
