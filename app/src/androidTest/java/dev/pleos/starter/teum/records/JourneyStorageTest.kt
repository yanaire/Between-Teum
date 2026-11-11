package dev.pleos.starter.teum.records

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pleos.starter.teum.content.SituationIds
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JourneyStorageTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val name = "teum_journey_test_${UUID.randomUUID()}"
    private val context = object : ContextWrapper(target) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(key: String, mode: Int): SharedPreferences =
            target.getSharedPreferences(name, mode)
    }

    @After
    fun cleanup() { target.deleteSharedPreferences(name) }

    @Test
    fun consumptionAndRealFactsSurviveStoreRecreation() {
        val finish = Instant.parse("2026-11-09T21:00:00Z").toEpochMilli()
        val morning = Instant.parse("2026-11-10T08:00:00Z").toEpochMilli()
        val record = newActivityRecord(SituationIds.COMMUTE_EVENING, "Evening", 1, listOf(0),
            43, ActivityOutcome.COMPLETED, nowEpochMs = finish + 30_000,
            journeyFacts = JourneyFacts(JourneyKind.EVENING, finishedAtEpochMs = finish,
                reflection = EveningReflection("Story", "Greeting"), firstSpeech = SpeechMeasurement(3_210)))
        val repo = RecordsRepository(SharedPreferencesRecordStore(context)) { morning }
        repo.save(record)
        assertEquals(record, RecordsRepository(SharedPreferencesRecordStore(context)).get(record.id))
        assertNotNull(repo.consumeMorningReflection(record.id, ZoneId.of("UTC"), true))
        val recreated = RecordsRepository(SharedPreferencesRecordStore(context)) { morning }
        assertNull(recreated.morningReflection(ZoneId.of("UTC")))
        assertEquals(EveningReflection(consumedAtEpochMs = morning), recreated.get(record.id)?.journeyFacts?.reflection)
        assertEquals(43L, recreated.get(record.id)?.elapsedSeconds)
    }
}
