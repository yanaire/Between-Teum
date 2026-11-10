package dev.pleos.starter.teum.records

import dev.pleos.starter.teum.content.SituationIds
import dev.pleos.starter.teum.content.TeumContentCatalog
import dev.pleos.starter.teum.content.TeumRecommender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ContentScheduleRegressionTest {
    @Test
    fun nonLunchStepsAreInSessionAndHaveRequiredDurations() {
        val expected = mapOf(
            SituationIds.COMMUTE_MORNING to listOf("prepare" to 30, "breathing" to 180, "first-action" to 60, "finish" to 30),
            SituationIds.MEETING to listOf("prepare" to 30, "message" to 180, "rehearsal" to 60, "summary" to 90, "finish" to 60),
            SituationIds.COMMUTE_EVENING to listOf("prepare" to 30, "reflect" to 120, "tomorrow" to 60, "daily-action" to 60, "finish" to 30)
        )
        for ((id, steps) in expected) {
            val content = TeumContentCatalog.get(id)!!
            assertEquals(steps, content.steps.map { it.id to it.durationSeconds })
            val normal = TeumRecommender.recommend(id, content.totalSeconds() + 100, 100).primary!!
            assertEquals(content.totalSeconds() + 100, normal.totalSeconds())
            assertFalse(normal.stepSeconds.containsKey("move"))
        }
    }
}
