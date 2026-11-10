package dev.pleos.starter.teum.records

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pleos.starter.teum.content.SituationIds
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedPreferencesRecordStoreTest {
    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    private fun freshStore(): SharedPreferencesRecordStore {
        context.getSharedPreferences(
            SharedPreferencesRecordStore.PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit().clear().apply()
        return SharedPreferencesRecordStore(context)
    }

    @After
    fun cleanUp() {
        context.getSharedPreferences(
            SharedPreferencesRecordStore.PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit().clear().apply()
    }

    @Test
    fun saveSurvivesStoreRecreation() {
        val first = freshStore()
        val repository = RecordsRepository(first)
        repository.save(
            newActivityRecord(
                situationId = SituationIds.COMMUTE_EVENING,
                activityTitle = "자료 요청 메일 보내기",
                totalSteps = 5,
                completedStepIndexes = listOf(0, 1, 2, 3, 4),
                elapsedSeconds = 287,
                outcome = ActivityOutcome.COMPLETED,
                nextAction = "다음 출근 때 회신 확인",
                recallAccepted = true
            )
        )
        val recreated = RecordsRepository(SharedPreferencesRecordStore(context))
        val loaded = recreated.listAll()
        assertEquals(1, loaded.size)
        assertEquals("자료 요청 메일 보내기", loaded[0].activityTitle)
        assertEquals(1, recreated.pendingRecalls().size)
    }

    @Test
    fun deletePersistsAcrossRecreation() {
        val repository = RecordsRepository(freshStore())
        val saved = repository.save(
            newActivityRecord(
                situationId = SituationIds.MEETING,
                activityTitle = "미팅 핵심 문장 정리",
                totalSteps = 5,
                completedStepIndexes = listOf(0, 1),
                elapsedSeconds = 120,
                outcome = ActivityOutcome.INTERRUPTED
            )
        )
        assertTrue(repository.delete(saved.id))
        val recreated = RecordsRepository(SharedPreferencesRecordStore(context))
        assertTrue(recreated.listAll().isEmpty())
    }

    @Test
    fun malformedStoredDataLoadsEmptyWithoutCrash() {
        freshStore()
        context.getSharedPreferences(
            SharedPreferencesRecordStore.PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit().putString(SharedPreferencesRecordStore.KEY_RECORDS, "{broken[[[").apply()
        val recreated = RecordsRepository(SharedPreferencesRecordStore(context))
        assertTrue(recreated.listAll().isEmpty())
        assertTrue(recreated.pendingRecalls().isEmpty())
    }

    @Test
    fun corruptRawSurvivesSubsequentSaveInQuarantine() {
        freshStore()
        val corrupt = "[${RecordJson.encode(
            newActivityRecord(
                situationId = SituationIds.COMMUTE_EVENING,
                activityTitle = "자료 요청 메일 보내기",
                totalSteps = 5,
                completedStepIndexes = listOf(0, 1, 2, 3, 4),
                elapsedSeconds = 287,
                outcome = ActivityOutcome.COMPLETED
            )
        )} GARBAGE]"
        context.getSharedPreferences(
            SharedPreferencesRecordStore.PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit().putString(SharedPreferencesRecordStore.KEY_RECORDS, corrupt).apply()
        val store = SharedPreferencesRecordStore(context)
        assertTrue(RecordsRepository(store).listAll().isEmpty())
        assertEquals(corrupt, store.quarantinedRaw())
        val repository = RecordsRepository(store)
        repository.save(
            newActivityRecord(
                situationId = SituationIds.MEETING,
                activityTitle = "미팅 핵심 문장 정리",
                totalSteps = 5,
                completedStepIndexes = listOf(0, 1),
                elapsedSeconds = 120,
                outcome = ActivityOutcome.INTERRUPTED
            )
        )
        assertEquals(1, repository.listAll().size)
        assertEquals(corrupt, store.quarantinedRaw())
        assertEquals(
            corrupt,
            SharedPreferencesRecordStore(context).quarantinedRaw()
        )
        SharedPreferencesRecordStore(context).clearQuarantine()
        assertNull(SharedPreferencesRecordStore(context).quarantinedRaw())
        assertEquals(1, RecordsRepository(SharedPreferencesRecordStore(context)).listAll().size)
    }

    @Test
    fun historyConsentDefaultsFalseAndPersistsAcrossRecreation() {
        val first = freshStore()
        assertFalse(RecordsRepository(first).isHistoryConsent())
        RecordsRepository(first).setHistoryConsent(true)
        assertTrue(RecordsRepository(SharedPreferencesRecordStore(context)).isHistoryConsent())
    }

    @Test
    fun missingCommaPayloadIsQuarantinedBeforeSave() {
        freshStore()
        val record = newActivityRecord(
            situationId = SituationIds.MEETING,
            activityTitle = "미팅 준비",
            totalSteps = 1,
            completedStepIndexes = listOf(0),
            elapsedSeconds = 2,
            outcome = ActivityOutcome.COMPLETED
        )
        val raw = "[${RecordJson.encode(record)}${RecordJson.encode(record)}]"
        context.getSharedPreferences(
            SharedPreferencesRecordStore.PREFS_NAME, Context.MODE_PRIVATE
        ).edit().putString(SharedPreferencesRecordStore.KEY_RECORDS, raw).commit()
        val store = SharedPreferencesRecordStore(context)
        RecordsRepository(store).save(record)
        val recreated = SharedPreferencesRecordStore(context)
        assertEquals(raw, recreated.quarantinedRaw())
        assertEquals(listOf(record), recreated.loadAll())
    }
}
