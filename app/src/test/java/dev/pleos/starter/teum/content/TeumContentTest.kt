package dev.pleos.starter.teum.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeumContentTest {
    @Test
    fun catalogHoldsFourSituationsWithKnownIds() {
        val all = TeumContentCatalog.all()
        assertEquals(4, all.size)
        assertEquals(
            listOf(
                SituationIds.COMMUTE_MORNING,
                SituationIds.LUNCH_BREAK,
                SituationIds.MEETING,
                SituationIds.COMMUTE_EVENING
            ),
            all.map { it.situationId }
        )
    }

    @Test
    fun stepTotalsMatchPlanBudgets() {
        assertEquals(300, TeumContentCatalog.get(SituationIds.COMMUTE_MORNING)!!.totalSeconds())
        assertEquals(1500, TeumContentCatalog.get(SituationIds.LUNCH_BREAK)!!.totalSeconds())
        assertEquals(420, TeumContentCatalog.get(SituationIds.MEETING)!!.totalSeconds())
        assertEquals(300, TeumContentCatalog.get(SituationIds.COMMUTE_EVENING)!!.totalSeconds())
    }

    @Test
    fun everyStepHasKoreanPromptAndPositiveDuration() {
        for (content in TeumContentCatalog.all()) {
            assertTrue(content.steps.isNotEmpty())
            for (step in content.steps) {
                assertTrue(step.id.isNotBlank())
                assertTrue(step.title.isNotBlank())
                assertTrue("prompt empty for ${content.situationId}/${step.id}", step.promptText.isNotBlank())
                assertTrue(step.durationSeconds > 0)
            }
        }
    }

    @Test
    fun everySituationOffersImmediateStopAndClosingQuestion() {
        for (content in TeumContentCatalog.all()) {
            assertEquals("바로 종료", content.stopNowLabel)
            assertTrue(content.closingQuestion.isNotBlank())
            assertTrue(content.closingOptions.isNotEmpty())
        }
    }

    @Test
    fun eveningActivityMatchesRecallScenario() {
        val evening = TeumContentCatalog.get(SituationIds.COMMUTE_EVENING)!!
        assertEquals("자료 요청 메일 보내기", evening.activityTitle)
        assertNotNull(evening.steps.find { it.questionOptions.isNotEmpty() })
    }

    @Test
    fun unknownSituationReturnsNull() {
        assertEquals(null, TeumContentCatalog.get("night-drive"))
    }
}
