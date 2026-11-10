package dev.pleos.starter.teum.records

import dev.pleos.starter.teum.content.SituationIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordJsonTest {
    private fun sample(): ActivityRecord = ActivityRecord(
        id = "rec-1",
        situationId = SituationIds.COMMUTE_EVENING,
        activityTitle = "자료 요청 메일 보내기",
        totalSteps = 5,
        completedStepIndexes = listOf(0, 1, 2, 3, 4),
        elapsedSeconds = 287,
        outcome = ActivityOutcome.COMPLETED,
        note = "수신자 \"김 대리\" 확인\n첨부 있음",
        nextAction = "다음 출근 때 회신 확인",
        recallAccepted = true,
        historyConsentAtSave = false,
        createdAtEpochMs = 1795000000000L,
        updatedAtEpochMs = 1795000000000L
    )

    @Test
    fun roundTripPreservesKoreanQuotesAndNewlines() {
        val decoded = RecordJson.decode(RecordJson.encode(sample()))
        assertEquals(sample(), decoded)
    }

    @Test
    fun roundTripPreservesListOfRecords() {
        val records = listOf(sample(), sample().copy(id = "rec-2", outcome = ActivityOutcome.INTERRUPTED))
        assertEquals(records, RecordJson.decodeAll(RecordJson.encodeAll(records)))
    }

    @Test
    fun malformedStoredDataReturnsEmptyWithoutThrowing() {
        assertEquals(emptyList<ActivityRecord>(), RecordJson.decodeAll(""))
        assertEquals(emptyList<ActivityRecord>(), RecordJson.decodeAll("   "))
        assertEquals(emptyList<ActivityRecord>(), RecordJson.decodeAll("not json at all"))
        assertEquals(emptyList<ActivityRecord>(), RecordJson.decodeAll("{\"id\":\"x\"}"))
        assertEquals(emptyList<ActivityRecord>(), RecordJson.decodeAll("[{\"id\":\"x\"}"))
        assertEquals(
            emptyList<ActivityRecord>(),
            RecordJson.decodeAll("[${RecordJson.encode(sample())}")
        )
        assertEquals(
            emptyList<ActivityRecord>(),
            RecordJson.decodeAll("[${RecordJson.encode(sample())}, {broken]")
        )
        assertEquals(
            emptyList<ActivityRecord>(),
            RecordJson.decodeAll("[${RecordJson.encode(sample().copy(outcome = "done"))}]")
        )
    }

    @Test
    fun singleMalformedDecodeReturnsNull() {
        assertNull(RecordJson.decode(""))
        assertNull(RecordJson.decode("{broken"))
    }

    @Test
    fun trailingGarbageAfterValidObjectIsRejected() {
        val valid = RecordJson.encode(sample())
        assertEquals(emptyList<ActivityRecord>(), RecordJson.decodeAll("[$valid GARBAGE]"))
        assertEquals(emptyList<ActivityRecord>(), RecordJson.decodeAll("[$valid, GARBAGE]"))
        assertEquals(emptyList<ActivityRecord>(), RecordJson.decodeAll("GARBAGE [$valid]"))
    }

    @Test
    fun strictSyntaxAndIntegerBoundariesRejectForgedRecords() {
        val valid = RecordJson.encode(sample())
        val invalid = listOf(
            "[$valid$valid]",
            "[$valid,]",
            "[${valid.replace(",\"situationId\"", " \"situationId\"")}]",
            "[${valid.replace("\"elapsedSeconds\":287", "\"elapsedSeconds\":2.9")}]",
            "[${valid.replace("\"elapsedSeconds\":287", "\"elapsedSeconds\":9223372036854775808")}]",
            "[${valid.replace("[0,1,2,3,4]", "[4294967296,1,2,3,4]")}]",
            "[${valid.replace("[0,1,2,3,4]", "[0.0,1,2,3,4]")}]",
            "[${valid.replace("\"totalSteps\":5", "\"totalSteps\":4294967301")}]",
            "[$valid] GARBAGE"
        )
        for (raw in invalid) {
            assertTrue("accepted $raw", RecordJson.isMalformedArray(raw))
            assertTrue(RecordJson.decodeAll(raw).isEmpty())
        }
    }

    @Test
    fun malformedDetectorDistinguishesFreshEmptyFromCorrupt() {
        assertFalse(RecordJson.isMalformedArray(""))
        assertFalse(RecordJson.isMalformedArray("   "))
        assertFalse(RecordJson.isMalformedArray("[]"))
        assertFalse(RecordJson.isMalformedArray("[ \n ]"))
        assertFalse(RecordJson.isMalformedArray("[${RecordJson.encode(sample())}]"))
        assertTrue(RecordJson.isMalformedArray("not json at all"))
        assertTrue(RecordJson.isMalformedArray("{broken [[["))
        assertTrue(
            RecordJson.isMalformedArray("[${RecordJson.encode(sample())} GARBAGE]")
        )
    }

    @Test
    fun validationRejectsCompletedWithMissingSteps() {
        var failed = false
        try {
            sample().copy(outcome = ActivityOutcome.COMPLETED, completedStepIndexes = listOf(0)).validate()
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }
}
