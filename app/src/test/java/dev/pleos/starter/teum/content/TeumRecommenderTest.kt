package dev.pleos.starter.teum.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeumRecommenderTest {
    @Test
    fun normalPreferredWhenFullBudgetFits() {
        val rec = TeumRecommender.recommend(SituationIds.COMMUTE_MORNING, 300, 0)
        assertEquals(RecommendDecision.START_NORMAL, rec.decision)
        assertNotNull(rec.primary)
        assertEquals(300, rec.primary!!.totalSeconds())
        assertNotNull(rec.alternative)
        assertEquals(150, rec.alternative!!.totalSeconds())
    }

    @Test
    fun shortBoundaryAccepts150Refuses149WithoutTravel() {
        val accept = TeumRecommender.recommend(SituationIds.COMMUTE_MORNING, 150, 0)
        assertEquals(RecommendDecision.START_SHORT_CONFIRM, accept.decision)
        assertEquals(150, accept.primary!!.totalSeconds())

        val refuse = TeumRecommender.recommend(SituationIds.COMMUTE_MORNING, 149, 0)
        assertEquals(RecommendDecision.REFUSE, refuse.decision)
        assertNull(refuse.primary)
    }

    @Test
    fun meetingAndEveningShareShortBoundary() {
        assertEquals(
            RecommendDecision.START_SHORT_CONFIRM,
            TeumRecommender.recommend(SituationIds.MEETING, 150, 0).decision
        )
        assertEquals(
            RecommendDecision.REFUSE,
            TeumRecommender.recommend(SituationIds.MEETING, 149, 0).decision
        )
        assertEquals(
            RecommendDecision.START_NORMAL,
            TeumRecommender.recommend(SituationIds.MEETING, 420, 0).decision
        )
        assertEquals(
            RecommendDecision.START_SHORT_CONFIRM,
            TeumRecommender.recommend(SituationIds.COMMUTE_EVENING, 150, 0).decision
        )
        assertEquals(
            RecommendDecision.REFUSE,
            TeumRecommender.recommend(SituationIds.COMMUTE_EVENING, 149, 0).decision
        )
    }

    @Test
    fun lunchShortBoundaryIncludesTravel() {
        val accept = TeumRecommender.recommend(SituationIds.LUNCH_BREAK, 600, 180)
        assertEquals(RecommendDecision.START_SHORT_CONFIRM, accept.decision)
        assertEquals(600, accept.primary!!.totalSeconds())

        assertEquals(
            RecommendDecision.REFUSE,
            TeumRecommender.recommend(SituationIds.LUNCH_BREAK, 599, 180).decision
        )
        assertEquals(
            RecommendDecision.START_NORMAL,
            TeumRecommender.recommend(SituationIds.LUNCH_BREAK, 1500, 180).decision
        )
    }

    @Test
    fun unknownSituationAndNegativeTimeRefuse() {
        assertEquals(
            RecommendDecision.REFUSE,
            TeumRecommender.recommend("night-drive", 900, 0).decision
        )
        assertEquals(
            RecommendDecision.REFUSE,
            TeumRecommender.recommend(SituationIds.COMMUTE_MORNING, -1, 0).decision
        )
        assertEquals(
            RecommendDecision.REFUSE,
            TeumRecommender.recommend(SituationIds.COMMUTE_MORNING, 300, -5).decision
        )
    }

    @Test
    fun recommendationIsDeterministic() {
        val first = TeumRecommender.recommend(SituationIds.LUNCH_BREAK, 700, 180)
        val second = TeumRecommender.recommend(SituationIds.LUNCH_BREAK, 700, 180)
        assertEquals(first, second)
    }

    @Test
    fun normalReservesCallerTravel_commute300Travel100IsShort() {
        val rec = TeumRecommender.recommend(SituationIds.COMMUTE_MORNING, 300, 100)
        assertEquals(RecommendDecision.START_SHORT_CONFIRM, rec.decision)
        assertNotNull(rec.primary)
        assertTrue(
            "primary ${rec.primary!!.totalSeconds()} must fit available 300",
            rec.primary!!.totalSeconds() <= 300
        )
    }

    @Test
    fun normalReservesCallerTravel_lunch1500Travel600IsShort() {
        val rec = TeumRecommender.recommend(SituationIds.LUNCH_BREAK, 1500, 600)
        assertEquals(RecommendDecision.START_SHORT_CONFIRM, rec.decision)
        assertNotNull(rec.primary)
        assertTrue(
            "primary ${rec.primary!!.totalSeconds()} must fit available 1500",
            rec.primary!!.totalSeconds() <= 1500
        )
    }

    @Test
    fun normalWithTravelFitsWhenBudgetCoversBoth() {
        val rec = TeumRecommender.recommend(SituationIds.COMMUTE_MORNING, 400, 100)
        assertEquals(RecommendDecision.START_NORMAL, rec.decision)
        assertEquals(400, rec.primary!!.totalSeconds())
    }

    @Test
    fun lunchNormalReplacesContentTravelStepWithInput() {
        val rec = TeumRecommender.recommend(SituationIds.LUNCH_BREAK, 1500, 180)
        assertEquals(RecommendDecision.START_NORMAL, rec.decision)
        assertEquals(1500, rec.primary!!.totalSeconds())
    }

    @Test
    fun hugeTravelRefusesInsteadOfOverflowing() {
        val short = TeumRecommender.recommend(SituationIds.COMMUTE_MORNING, 150, Int.MAX_VALUE)
        assertEquals(RecommendDecision.REFUSE, short.decision)
        assertNull(short.primary)
        val normal = TeumRecommender.recommend(SituationIds.LUNCH_BREAK, Int.MAX_VALUE, Int.MAX_VALUE)
        assertEquals(RecommendDecision.REFUSE, normal.decision)
        assertNull(normal.primary)
    }
}
