package dev.pleos.starter.teum.preferences

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedPreferencesPreferenceStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val name = "teum_preference_test_${UUID.randomUUID()}"
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @After
    fun cleanup() { context.deleteSharedPreferences(name) }

    @Test
    fun explicitSettingsPersistAcrossStorageRecreation() {
        val repo = PreferencesRepository(SharedPreferencesPreferenceStore(prefs))
        repo.setHome(SavedPlace("Chosen home", PlaceCoordinates(12.0, -45.0)))
        repo.setBackrest(BackrestPreferences(110, 145))
        repo.setRestSound(RestSound.NONE)
        repo.setVoiceGuide(false)
        val expected = repo.load()
        val recreated = SharedPreferencesPreferenceStore(context.getSharedPreferences(name, Context.MODE_PRIVATE))
        assertEquals(expected, recreated.load())
    }

    @Test
    fun corruptPayloadIsPreservedBeforeExplicitReplacement() {
        val corrupt = "{\"version\":1,\"voiceGuide\":\"yes\"}"
        prefs.edit().putString(SharedPreferencesPreferenceStore.KEY_SETTINGS, corrupt).commit()
        val store = SharedPreferencesPreferenceStore(prefs)
        assertEquals(TeumPreferences(), store.load())
        PreferencesRepository(store).setRestSound(RestSound.WIND)
        val recreated = SharedPreferencesPreferenceStore(prefs)
        assertEquals(RestSound.WIND, recreated.load().restSound)
        assertEquals(corrupt, recreated.quarantinedRaw())
        recreated.clearQuarantine()
        assertNull(recreated.quarantinedRaw())
    }

    @Test
    fun absentSettingsStayAbsentUntilAnExplicitUpdate() {
        assertEquals(TeumPreferences(), SharedPreferencesPreferenceStore(prefs).load())
        assertFalse(prefs.contains(SharedPreferencesPreferenceStore.KEY_SETTINGS))
    }
}
