package dev.pleos.starter.teum.content

/** Deterministic outcome of a recommendation request. */
enum class RecommendDecision {
    START_NORMAL,
    START_SHORT_CONFIRM,
    REFUSE
}

/** Concrete time plan offered to the user. */
data class TimePlan(
    val situationId: String,
    val label: String,
    val stepSeconds: LinkedHashMap<String, Int>,
    val travelSeconds: Int
) {
    fun totalSeconds(): Int = stepSeconds.values.sum() + travelSeconds
}

/** Primary offer plus fallback. alternative is null only when nothing shorter can be offered. */
data class Recommendation(
    val decision: RecommendDecision,
    val primary: TimePlan?,
    val alternative: TimePlan?,
    val reason: String
)

object TeumRecommender {
    private const val SHORT_PREPARE = 30
    private const val SHORT_ACTIVITY = 60
    private const val SHORT_WRAP = 60

    private const val LUNCH_SHORT_PREPARE = 60
    private const val LUNCH_SHORT_ACTIVITY = 180
    private const val LUNCH_SHORT_WRAP = 180

    /**
     * Nontravel seconds of the normal timetable per the user scenarios
     * (morning 300, meeting 420, evening 300, lunch 1320). The lunch content
     * carries a 180-second "move" step for external user travel; that step is
     * excluded here and the caller's [travelSeconds] is added exactly once.
     */
    fun normalBaseSeconds(situationId: String): Int? = when (situationId) {
        SituationIds.COMMUTE_MORNING -> 300
        SituationIds.MEETING -> 420
        SituationIds.COMMUTE_EVENING -> 300
        SituationIds.LUNCH_BREAK -> 1320
        else -> null
    }

    /** Nontravel seconds of the normal timetable (lunch 1320: content travel step excluded). */
    fun normalTotalSeconds(situationId: String): Int? =
        normalBaseSeconds(situationId)

    fun shortTotalSeconds(situationId: String, travelSeconds: Int): Int? {
        if (travelSeconds < 0) return null
        val base = when (situationId) {
            SituationIds.LUNCH_BREAK ->
                LUNCH_SHORT_PREPARE + LUNCH_SHORT_ACTIVITY + LUNCH_SHORT_WRAP
            SituationIds.COMMUTE_MORNING, SituationIds.MEETING, SituationIds.COMMUTE_EVENING ->
                SHORT_PREPARE + SHORT_ACTIVITY + SHORT_WRAP
            else -> return null
        }
        val total = base.toLong() + travelSeconds.toLong()
        if (total > Int.MAX_VALUE) return null
        return total.toInt()
    }

    fun recommend(
        situationId: String,
        availableSeconds: Int,
        travelSeconds: Int = 0
    ): Recommendation {
        val content = TeumContentCatalog.get(situationId)
            ?: return Recommendation(RecommendDecision.REFUSE, null, null, "알 수 없는 상황입니다.")
        if (availableSeconds < 0 || travelSeconds < 0) {
            return Recommendation(RecommendDecision.REFUSE, null, null, "시간 값이 올바르지 않습니다.")
        }
        val normalBase = normalBaseSeconds(situationId) ?: return Recommendation(
            RecommendDecision.REFUSE, null, null, "알 수 없는 상황입니다."
        )
        // Long-domain checked arithmetic: Int + Int always fits Long, so a huge
        // travel can never wrap negative; it only makes both needs unaffordable.
        val normalNeed = normalBase.toLong() + travelSeconds.toLong()
        val shortBase = if (situationId == SituationIds.LUNCH_BREAK) {
            LUNCH_SHORT_PREPARE + LUNCH_SHORT_ACTIVITY + LUNCH_SHORT_WRAP
        } else {
            SHORT_PREPARE + SHORT_ACTIVITY + SHORT_WRAP
        }
        val shortNeed = shortBase.toLong() + travelSeconds.toLong()
        val available = availableSeconds.toLong()
        if (available >= normalNeed) {
            val primary = normalPlan(content, travelSeconds)
            check(primary.totalSeconds().toLong() <= available) { "normal plan exceeds available" }
            return Recommendation(
                RecommendDecision.START_NORMAL,
                primary,
                shortPlan(content, travelSeconds),
                "정상 시간표가 가능합니다. 더 짧게 하고 싶으면 짧은 활동을 고르세요."
            )
        }
        if (available >= shortNeed) {
            val primary = shortPlan(content, travelSeconds)
            check(primary.totalSeconds().toLong() <= available) { "short plan exceeds available" }
            return Recommendation(
                RecommendDecision.START_SHORT_CONFIRM,
                primary,
                null,
                "정상 시간표에는 시간이 부족합니다. 짧은 활동으로 시작하려면 확인하세요."
            )
        }
        return Recommendation(
            RecommendDecision.REFUSE,
            null,
            null,
            "시간이 부족합니다. 최소 ${shortNeed}초가 필요합니다."
        )
    }

    private fun normalPlan(content: SituationContent, travelSeconds: Int): TimePlan {
        val steps = LinkedHashMap<String, Int>()
        // Lunch content carries a 180-second external-travel "move" step; drop it
        // and carry the caller's travel exactly once in travelSeconds instead.
        // Other situations keep their in-session finishing steps.
        val timetableSteps = if (content.situationId == SituationIds.LUNCH_BREAK) {
            content.steps.filter { it.id != "move" }
        } else {
            content.steps
        }
        timetableSteps.forEach { steps[it.id] = it.durationSeconds }
        return TimePlan(content.situationId, "정상 시간표", steps, travelSeconds)
    }

    private fun shortPlan(content: SituationContent, travelSeconds: Int): TimePlan {
        val steps = LinkedHashMap<String, Int>()
        if (content.situationId == SituationIds.LUNCH_BREAK) {
            steps["prepare"] = LUNCH_SHORT_PREPARE
            steps["activity"] = LUNCH_SHORT_ACTIVITY
            steps["wrap"] = LUNCH_SHORT_WRAP
        } else {
            steps["prepare"] = SHORT_PREPARE
            steps["activity"] = SHORT_ACTIVITY
            steps["wrap"] = SHORT_WRAP
        }
        return TimePlan(content.situationId, "짧은 활동", steps, travelSeconds)
    }
}
