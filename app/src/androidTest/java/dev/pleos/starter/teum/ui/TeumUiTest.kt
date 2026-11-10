package dev.pleos.starter.teum.ui

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pleos.starter.MainActivity
import dev.pleos.starter.R
import dev.pleos.starter.teum.records.ActivityOutcome
import dev.pleos.starter.teum.records.RecordsRepository
import dev.pleos.starter.teum.records.SharedPreferencesRecordStore
import dev.pleos.starter.teum.records.newActivityRecord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TeumUiTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun clearState() {
        clearPrefs()
    }

    @After
    fun closeState() {
        if (::scenario.isInitialized) scenario.close()
        clearPrefs()
    }

    private fun clearPrefs() {
        listOf("teum_records_private", "teum_session_v1", "teum_ui_v1").forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForTag("situation_lunch-break", 20_000)
    }

    private fun view(tag: String): View? {
        var result: View? = null
        scenario.onActivity { activity ->
            result = activity.findViewById<ViewGroup>(R.id.teum_root).findViewWithTag(tag)
        }
        return result
    }

    private fun text(tag: String): String? = (view(tag) as? TextView)?.text?.toString()

    private fun click(tag: String) {
        var failure: Throwable? = null
        scenario.onActivity { activity ->
            val target = activity.findViewById<ViewGroup>(R.id.teum_root).findViewWithTag<View>(tag)
            if (target == null) {
                failure = AssertionError("missing view: $tag")
            } else if (!target.isEnabled) {
                failure = AssertionError("view disabled: $tag")
            } else {
                target.performClick()
            }
        }
        failure?.let { throw it }
    }

    private fun setText(tag: String, value: String) {
        var failure: Throwable? = null
        scenario.onActivity { activity ->
            val target = activity.findViewById<ViewGroup>(R.id.teum_root).findViewWithTag<EditText>(tag)
            if (target == null) failure = AssertionError("missing edit text: $tag")
            else target.setText(value)
        }
        failure?.let { throw it }
    }

    private fun waitForTag(tag: String, timeoutMs: Long = 25_000) {
        waitUntil("tag $tag", timeoutMs) { view(tag) != null }
    }

    private fun waitEnabled(tag: String, timeoutMs: Long = 25_000) {
        waitUntil("enabled $tag", timeoutMs) { view(tag)?.isEnabled == true }
    }

    private fun waitUntil(description: String, timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(25)
        }
        fail("Timed out waiting for $description")
    }

    private fun containsText(needle: String): Boolean {
        var found = false
        scenario.onActivity { activity ->
            found = collect(activity.findViewById<ViewGroup>(R.id.teum_root)).any { it.contains(needle) }
        }
        return found
    }

    private fun collect(view: View): List<String> {
        val out = mutableListOf<String>()
        if (view is TextView && view.text?.isNotBlank() == true) out += view.text.toString()
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) out += collect(view.getChildAt(index))
        }
        return out
    }

    private fun openLunchSetup() {
        click("situation_lunch-break")
        waitForTag("start_button")
    }

    @Test
    fun cueRemainsAvailableForSecondSessionAfterRecording() {
        launch()
        openLunchSetup()
        waitEnabled("start_button")
        click("start_button")
        waitForTag("finish_now_button")
        click("finish_now_button")
        waitForTag("ack_wake_restore_button")
        click("ack_wake_restore_button")
        click("restore_consent")
        waitEnabled("restore_confirm_button")
        click("restore_confirm_button")
        waitForTag("record_save_button")
        click("record_save_button")
        waitForTag("situation_lunch-break")
        assertEquals(1, SharedPreferencesRecordStore(context).loadAll().size)

        openLunchSetup()
        waitEnabled("start_button")
        click("start_button")
        waitForTag("finish_now_button")
        scenario.onActivity { activity ->
            val ui = activity.javaClass.getDeclaredField("ui").apply { isAccessible = true }.get(activity)
            val cue = ui.javaClass.getDeclaredField("cue").apply { isAccessible = true }.get(ui)
            assertNotNull("second session must have a live cue player", cue)
            val closed = cue!!.javaClass.getDeclaredField("closed").apply { isAccessible = true }
            assertFalse("second session cue player must not be closed", closed.getBoolean(cue))
            val session = ui.javaClass.getDeclaredField("active").apply { isAccessible = true }.get(ui)!!
            val segments = (session.javaClass.getDeclaredField("segments").apply { isAccessible = true }
                .get(session) as List<*>).filterIsInstance<Segment>()
            val wake = segments.indexOfFirst { it.stepId == "wake" || it.stepId == "wrap" }
            assertTrue(wake >= 0)
            session.javaClass.getDeclaredField("elapsedSeconds").apply { isAccessible = true }
                .setLong(session, (segmentEnd(segments, wake) - 60).toLong())
            ui.javaClass.getDeclaredMethod("lunchCueTick", session.javaClass).apply { isAccessible = true }
                .invoke(ui, session)
            assertEquals(1, ui.javaClass.getDeclaredField("cueCount").apply { isAccessible = true }.getInt(ui))
        }
    }

    @Test
    fun homeShowsFourSituationsAndFreshSafety() {
        launch()
        for (id in listOf("commute-morning", "lunch-break", "meeting", "commute-evening")) {
            assertNotNull("missing situation $id", view("situation_$id"))
        }
        waitUntil("safety ready", 25_000) {
            text("safety_pill")?.contains("제한 없음") == true
        }
        assertTrue(text("safety_reason")!!.contains("시작할 수 있습니다"))
    }

    @Test
    fun setupShowsTimelineSeatDisabledAndTruthfulManualSettings() {
        launch()
        openLunchSetup()
        assertNotNull(view("recommendation"))
        assertNotNull(view("timeline"))
        assertEquals("비활성", text("seat_pill"))
        assertNull(view("seat_plus"))
        waitUntil("HVAC reported manual", 25_000) { text("hvac_pill") == "수동" }
        waitUntil("ambient reported manual", 25_000) { text("ambient_pill") == "수동" }
        assertTrue(containsText("차량 메뉴에서 직접 조정하세요"))
        waitEnabled("start_button")
        assertNotNull(view("planned_return"))
    }

    @Test
    fun lunchEarlyFinishRecordsInterruptedWithNoteAndRecall() {
        launch()
        openLunchSetup()
        waitEnabled("start_button")
        click("quick_10")
        waitForTag("short_confirm")
        assertFalse("short start must require confirmation", view("start_button")!!.isEnabled)
        click("short_confirm")
        waitEnabled("start_button")
        click("start_button")
        waitForTag("finish_now_button")
        click("finish_now_button")
        waitForTag("ack_wake_restore_button")
        click("ack_wake_restore_button")
        waitForTag("restore_consent")
        click("restore_consent")
        waitEnabled("restore_confirm_button")
        click("restore_confirm_button")
        waitForTag("record_save_button")
        assertEquals("중도 종료로 기록", text("record_verdict"))
        assertNull(view("record_claim_completed"))
        setText("record_note", "점심 짧은 휴식 메모")
        setText("record_next", "오후 집중 업무 시작")
        click("record_recall")
        click("record_save_button")
        waitForTag("situation_lunch-break")
        val record = SharedPreferencesRecordStore(context).loadAll().single()
        assertEquals(ActivityOutcome.INTERRUPTED, record.outcome)
        assertEquals(emptyList<Int>(), record.completedStepIndexes)
        assertTrue(containsText("기록을 저장했습니다"))
        assertTrue(containsText("다음에 다시 볼 기록"))
        assertTrue(containsText("오후 집중 업무 시작"))
        click("history_button")
        waitForTag("history_consent")
        assertFalse("history consent must default off", (view("history_consent") as Switch).isChecked)
        assertTrue(containsText("중도 종료"))
        assertEquals("중도 종료", text("history_outcome"))
        assertTrue(containsText("점심 짧은 휴식 메모"))
        assertTrue(containsText("다음 출근 때 다시 보기"))
    }

    @Test
    fun recoveryMarkerShowsInterruptedWithoutCompletion() {
        val now = System.currentTimeMillis()
        SessionSnapshotStore(context).save(
            SessionSnapshot(
                situationId = "lunch-break",
                kind = "FULL",
                startWallMillis = now - 120_000,
                deadlineWallMillis = now + 1_320_000,
                prepareSeconds = 60,
                activitySeconds = 1080,
                finishSeconds = 180,
                travelSeconds = 180,
                elapsedSeconds = 120,
                wallMillis = now,
                stage = "INTERRUPTED",
                reason = "앱이 화면을 떠나 타이머가 중단되었습니다.",
                restricted = false,
                restoredAndReadyToRecord = false,
                awakeAcknowledged = false
            )
        )
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForTag("recovery_record_button")
        assertTrue(containsText("이전 활동이 중단되었습니다"))
        assertTrue(containsText("다시 보내지 않으며"))
        click("recovery_record_button")
        waitForTag("record_save_button")
        assertEquals("중도 종료로 기록", text("record_verdict"))
        assertFalse(text("record_verdict")!!.contains("완료"))
    }

    @Test
    fun historyPillShowsSafetyInterruptedWithoutDanglingSuffix() {
        RecordsRepository(SharedPreferencesRecordStore(context)).save(
            newActivityRecord(
                situationId = "lunch-break",
                activityTitle = "점심 짧은 휴식",
                totalSteps = 3,
                completedStepIndexes = emptyList(),
                elapsedSeconds = 16,
                outcome = ActivityOutcome.SAFETY_INTERRUPTED
            )
        )
        launch()
        click("history_button")
        waitForTag("history_consent")
        assertEquals("안전 중단", text("history_outcome"))
        assertFalse(containsText("안전 중단으"))
        assertTrue(containsText("0/3 단계"))
        assertTrue(containsText("실제 16초"))
    }

    @Test
    fun historyConsentDefaultsOffAndPersistsOnlyWhenChosen() {
        launch()
        click("history_button")
        waitForTag("history_consent")
        assertFalse((view("history_consent") as Switch).isChecked)
        click("history_consent")
        waitUntil("consent on", 10_000) { (view("history_consent") as? Switch)?.isChecked == true }
        assertTrue(context.getSharedPreferences("teum_records_private", Context.MODE_PRIVATE)
            .getBoolean("history_consent_v1", false))
    }
}
