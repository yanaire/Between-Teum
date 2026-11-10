package dev.pleos.starter.teum.records

import dev.pleos.starter.teum.content.SituationIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordsRepositoryTest {
    private fun repo(now: Long = 1795000000000L): RecordsRepository {
        var clock = now
        return RecordsRepository(InMemoryRecordStore()) { clock++ }
    }

    private fun completed(idSuffix: String = ""): ActivityRecord = ActivityRecord(
        id = "rec-completed$idSuffix",
        situationId = SituationIds.COMMUTE_EVENING,
        activityTitle = "자료 요청 메일 보내기",
        totalSteps = 5,
        completedStepIndexes = listOf(0, 1, 2, 3, 4),
        elapsedSeconds = 287,
        outcome = ActivityOutcome.COMPLETED,
        nextAction = "다음 출근 때 회신 확인",
        recallAccepted = true,
        createdAtEpochMs = 1795000000000L,
        updatedAtEpochMs = 1795000000000L
    )

    @Test
    fun saveAndRecreateKeepsRecords() {
        val store = InMemoryRecordStore()
        RecordsRepository(store).save(completed())
        val recreated = RecordsRepository(store)
        assertEquals(1, recreated.listAll().size)
        assertEquals("자료 요청 메일 보내기", recreated.listAll()[0].activityTitle)
    }

    @Test
    fun interruptedIsNeverReportedFullyCompleted() {
        val r = repo()
        val interrupted = completed().copy(
            id = "rec-partial",
            outcome = ActivityOutcome.INTERRUPTED,
            completedStepIndexes = listOf(0, 1)
        )
        r.save(interrupted)
        assertFalse(r.get("rec-partial")!!.isFullyCompleted())
    }

    @Test
    fun safetyInterruptedStoresPartialProgress() {
        val r = repo()
        val record = completed().copy(
            id = "rec-safety",
            outcome = ActivityOutcome.SAFETY_INTERRUPTED,
            completedStepIndexes = listOf(0)
        )
        r.save(record)
        val loaded = r.get("rec-safety")!!
        assertEquals(ActivityOutcome.SAFETY_INTERRUPTED, loaded.outcome)
        assertEquals(1, loaded.completedStepCount())
        assertFalse(loaded.isFullyCompleted())
    }

    @Test
    fun completedWithMissingStepsIsRejected() {
        val r = repo()
        var failed = false
        try {
            r.save(completed().copy(completedStepIndexes = listOf(0, 1)))
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
        assertTrue(r.listAll().isEmpty())
    }

    @Test
    fun historySuggestionRequiresExplicitConsent() {
        val r = repo()
        r.save(completed())
        assertFalse(r.isHistoryConsent())
        assertNull(r.suggestFromHistory(SituationIds.COMMUTE_EVENING))
        r.setHistoryConsent(true)
        assertTrue(r.isHistoryConsent())
        assertEquals("rec-completed", r.suggestFromHistory(SituationIds.COMMUTE_EVENING)!!.id)
        r.setHistoryConsent(false)
        assertNull(r.suggestFromHistory(SituationIds.COMMUTE_EVENING))
    }

    @Test
    fun recallNeedsExplicitChoiceAndBlankNextActionIsHidden() {
        val r = repo()
        r.save(completed())
        r.save(completed("silent").copy(id = "rec-silent", nextAction = "", recallAccepted = true))
        r.save(completed("declined").copy(id = "rec-declined", recallAccepted = false))
        val pending = r.pendingRecalls()
        assertEquals(listOf("rec-completed"), pending.map { it.id })
        r.dismissRecall("rec-completed")
        assertTrue(r.pendingRecalls().isEmpty())
        assertEquals("다음 출근 때 회신 확인", r.get("rec-completed")!!.nextAction)
    }

    @Test
    fun updateEditsNoteNextActionAndRecallChoice() {
        val r = repo()
        r.save(completed().copy(note = "", nextAction = "", recallAccepted = false))
        val updated = r.update("rec-completed", "첨부 확인함", "회신 확인", true)!!
        assertEquals("첨부 확인함", updated.note)
        assertEquals("회신 확인", updated.nextAction)
        assertTrue(updated.recallAccepted)
        assertEquals(1, r.pendingRecalls().size)
        assertNull(r.update("missing", "n", "a", false))
    }

    @Test
    fun deleteRemovesRecordAndSurvivesRecreation() {
        val store = InMemoryRecordStore()
        val r = RecordsRepository(store)
        r.save(completed())
        assertTrue(r.delete("rec-completed"))
        assertFalse(r.delete("rec-completed"))
        assertTrue(RecordsRepository(store).listAll().isEmpty())
    }

    @Test
    fun unknownSituationIsRejected() {
        val r = repo()
        var failed = false
        try {
            r.save(completed().copy(situationId = "night-drive"))
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }
}
