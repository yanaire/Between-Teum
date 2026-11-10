package dev.pleos.starter.teum.ui

import android.content.Context
import dev.pleos.starter.R
import dev.pleos.starter.teum.content.SituationIds
import dev.pleos.starter.teum.content.TeumContentCatalog
import dev.pleos.starter.teum.session.Schedule
import dev.pleos.starter.teum.session.ScheduleKind
import dev.pleos.starter.teum.session.Situation
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** UI metadata per situation. Activity titles are general labels, never the
 *  user's example phrase; no note or next action is pre-filled. */
data class SituationMeta(
    val id: String,
    val session: Situation,
    val title: String,
    val tagline: String,
    val activityTitle: String,
    val defaultMinutes: Int,
    val defaultTravelMinutes: Int,
    val iconRes: Int,
    val quickMinutes: List<Int>
)

val TEUM_SITUATIONS: List<SituationMeta> = listOf(
    SituationMeta(
        SituationIds.COMMUTE_MORNING, Situation.COMMUTE, "출근 준비",
        "도착 전, 오늘의 첫 행동을 정리합니다.", "출근 전 준비 활동",
        5, 0, R.drawable.ic_teum_commute, listOf(5, 10, 15)
    ),
    SituationMeta(
        SituationIds.LUNCH_BREAK, Situation.LUNCH, "점심 휴식",
        "총 25분: 준비 1분, 휴식 18분, 깨기 3분, 복귀 이동 3분입니다.", "점심 휴식",
        25, 3, R.drawable.ic_teum_lunch, listOf(10, 15, 25, 30)
    ),
    SituationMeta(
        SituationIds.MEETING, Situation.MEETING, "미팅 준비",
        "핵심 한 문장을 정리하고 들어갑니다.", "미팅 준비 활동",
        7, 0, R.drawable.ic_teum_meeting, listOf(7, 10, 15)
    ),
    SituationMeta(
        SituationIds.COMMUTE_EVENING, Situation.GOING_HOME, "퇴근 정리",
        "오늘을 닫고 내일 할 일 하나를 남깁니다.", "퇴근 정리 활동",
        5, 0, R.drawable.ic_teum_evening, listOf(5, 10, 15)
    )
)

fun metaOf(id: String?): SituationMeta? = TEUM_SITUATIONS.firstOrNull { it.id == id }

/** One timed segment shown in the step UI and used for actual progress. */
data class Segment(
    val index: Int,
    val stepId: String,
    val title: String,
    val prompt: String,
    val seconds: Int,
    val external: Boolean = false
)

/** Lunch's external move is travel, not an in-app timed segment. */
fun buildSegments(
    situationId: String,
    kind: ScheduleKind,
    prepareSeconds: Long,
    activitySeconds: Long,
    finishSeconds: Long
): List<Segment> {
    val content = TeumContentCatalog.get(situationId) ?: return emptyList()
    if (kind == ScheduleKind.FULL) {
        val steps = if (situationId == SituationIds.LUNCH_BREAK) {
            content.steps.filter { it.id != "move" }
        } else {
            content.steps
        }
        return steps.mapIndexed { index, step ->
            Segment(index, step.id, step.title, step.promptText, step.durationSeconds)
        }
    }
    val middle = content.steps.firstOrNull {
        it.id == "rest" || it.id == "message" || it.id == "breathing" || it.id == "reflect"
    }?.promptText ?: content.activityTitle
    return listOf(
        Segment(0, "prepare", "준비", content.steps.firstOrNull()?.promptText
            ?: "편하게 앉아 준비하세요.", prepareSeconds.toInt()),
        Segment(1, "activity", "짧은 활동", middle, activitySeconds.toInt()),
        Segment(2, "wrap", "마무리", "주변을 정리하고 복귀를 준비하세요.", finishSeconds.toInt())
    )
}

fun segmentStart(segments: List<Segment>, index: Int): Int =
    segments.take(index).sumOf { it.seconds }

fun segmentEnd(segments: List<Segment>, index: Int): Int =
    segments.take(index + 1).sumOf { it.seconds }

fun stepIndexAt(segments: List<Segment>, elapsedSeconds: Long): Int {
    if (segments.isEmpty()) return 0
    val elapsed = elapsedSeconds.coerceAtLeast(0)
    var start = 0
    for (segment in segments) {
        val end = start + segment.seconds
        if (elapsed < end) return segment.index
        start = end
    }
    return segments.last().index
}

fun completedIndexes(segments: List<Segment>, elapsedSeconds: Long): List<Int> {
    val out = mutableListOf<Int>()
    var start = 0
    for (segment in segments) {
        val end = start + segment.seconds
        if (elapsedSeconds >= end) out += segment.index
        start = end
    }
    return out
}

fun formatClock(epochMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(epochMillis))

fun formatClockSeconds(epochMillis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(epochMillis))

fun formatDateTime(epochMillis: Long): String =
    SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(epochMillis))

fun formatDuration(totalSeconds: Long): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val rest = seconds % 60
    return when {
        hours > 0 -> "${hours}시간 ${minutes}분"
        minutes > 0 && rest > 0 -> "${minutes}분 ${rest}초"
        minutes > 0 -> "${minutes}분"
        else -> "${rest}초"
    }
}

fun formatMmSs(totalSeconds: Long): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    return String.format(Locale.KOREA, "%02d:%02d", seconds / 60, seconds % 60)
}

fun resolveDeadline(hour: Int, minute: Int, nowWallMillis: Long): Long {
    val calendar = Calendar.getInstance().apply {
        timeInMillis = nowWallMillis
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    if (calendar.timeInMillis <= nowWallMillis) calendar.add(Calendar.DAY_OF_YEAR, 1)
    return calendar.timeInMillis
}

/** A durable marker. It never carries commands or a resume token. */
data class SessionSnapshot(
    val situationId: String,
    val kind: String,
    val startWallMillis: Long,
    val deadlineWallMillis: Long,
    val prepareSeconds: Long,
    val activitySeconds: Long,
    val finishSeconds: Long,
    val travelSeconds: Long,
    val elapsedSeconds: Long,
    val wallMillis: Long,
    val stage: String,
    val reason: String,
    val restricted: Boolean,
    val restoredAndReadyToRecord: Boolean,
    val awakeAcknowledged: Boolean,
    val progressSeconds: Long = elapsedSeconds,
    val deadlineFinishCompleted: Boolean = false
) {
    fun toSchedule(situation: Situation): Schedule = Schedule(
        situation, ScheduleKind.valueOf(kind), startWallMillis, deadlineWallMillis,
        prepareSeconds, activitySeconds, finishSeconds, travelSeconds
    )

    val timedTotalSeconds: Long get() = prepareSeconds + activitySeconds + finishSeconds
}

object SessionSnapshotCodec {
    private const val VERSION = 1

    fun encode(snapshot: SessionSnapshot): String = JsonObject().apply {
        addProperty("version", VERSION)
        addProperty("situationId", snapshot.situationId)
        addProperty("kind", snapshot.kind)
        addProperty("startWallMillis", snapshot.startWallMillis)
        addProperty("deadlineWallMillis", snapshot.deadlineWallMillis)
        addProperty("prepareSeconds", snapshot.prepareSeconds)
        addProperty("activitySeconds", snapshot.activitySeconds)
        addProperty("finishSeconds", snapshot.finishSeconds)
        addProperty("travelSeconds", snapshot.travelSeconds)
        addProperty("elapsedSeconds", snapshot.elapsedSeconds)
        addProperty("progressSeconds", snapshot.progressSeconds)
        addProperty("deadlineFinishCompleted", snapshot.deadlineFinishCompleted)
        addProperty("wallMillis", snapshot.wallMillis)
        addProperty("stage", snapshot.stage)
        addProperty("reason", snapshot.reason)
        addProperty("restricted", snapshot.restricted)
        addProperty("restoredAndReadyToRecord", snapshot.restoredAndReadyToRecord)
        addProperty("awakeAcknowledged", snapshot.awakeAcknowledged)
    }.toString()

    fun decode(raw: String?): SessionSnapshot? {
        if (raw.isNullOrBlank()) return null
        return try {
            val root = JsonParser.parseString(raw)
            if (!root.isJsonObject) return null
            val json = root.asJsonObject
            if (json.get("version")?.asInt != VERSION) return null
            val situationId = json.get("situationId")?.asString ?: return null
            val kind = json.get("kind")?.asString ?: return null
            if (metaOf(situationId) == null || kind !in setOf("FULL", "SHORT")) return null
            fun requiredLong(name: String): Long {
                val element = json.get(name) ?: throw IllegalArgumentException(name)
                if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
                    throw IllegalArgumentException(name)
                }
                return element.asLong
            }
            fun optionalBoolean(name: String, fallback: Boolean): Boolean {
                val element = json.get(name) ?: return fallback
                if (!element.isJsonPrimitive || !element.asJsonPrimitive.isBoolean) {
                    throw IllegalArgumentException(name)
                }
                return element.asBoolean
            }
            val snapshot = SessionSnapshot(
                situationId = situationId,
                kind = kind,
                startWallMillis = requiredLong("startWallMillis"),
                deadlineWallMillis = requiredLong("deadlineWallMillis"),
                prepareSeconds = requiredLong("prepareSeconds"),
                activitySeconds = requiredLong("activitySeconds"),
                finishSeconds = requiredLong("finishSeconds"),
                travelSeconds = requiredLong("travelSeconds"),
                elapsedSeconds = requiredLong("elapsedSeconds"),
                wallMillis = requiredLong("wallMillis"),
                stage = json.get("stage")?.asString ?: "INTERRUPTED",
                reason = json.get("reason")?.asString.orEmpty(),
                restricted = optionalBoolean("restricted", false),
                restoredAndReadyToRecord = optionalBoolean("restoredAndReadyToRecord", false),
                awakeAcknowledged = optionalBoolean("awakeAcknowledged", false),
                progressSeconds = if (json.has("progressSeconds")) requiredLong("progressSeconds")
                    else requiredLong("elapsedSeconds"),
                deadlineFinishCompleted = optionalBoolean("deadlineFinishCompleted", false)
            )
            if (snapshot.startWallMillis < 0 || snapshot.elapsedSeconds < 0 || snapshot.progressSeconds < 0 ||
                snapshot.prepareSeconds < 0 || snapshot.activitySeconds < 0 ||
                snapshot.finishSeconds < 0 || snapshot.travelSeconds < 0 ||
                snapshot.wallMillis < 0 || snapshot.deadlineWallMillis < 0
            ) null else snapshot
        } catch (e: RuntimeException) {
            null
        }
    }
}

class SessionSnapshotStore(context: Context) {
    private val prefs = context.getSharedPreferences("teum_session_v1", Context.MODE_PRIVATE)

    fun load(): SessionSnapshot? = SessionSnapshotCodec.decode(prefs.getString(KEY, null))

    fun save(snapshot: SessionSnapshot) {
        prefs.edit().putString(KEY, SessionSnapshotCodec.encode(snapshot)).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "unfinished_v1"
    }
}

/** Values needed to finish a record after restore or recovery. */
data class RecordDraft(
    val situationId: String,
    val segments: List<Segment>,
    val elapsedSeconds: Long,
    val plannedReturnWallMillis: Long?,
    val actualFinishWallMillis: Long?,
    val safetyInterrupted: Boolean,
    val recovered: Boolean,
    val allTimedPassed: Boolean,
    val restoredBeforeSave: Boolean,
    val progressSeconds: Long = elapsedSeconds,
    val deadlineFinishCompleted: Boolean = false
) {
    val totalSteps: Int get() = segments.size
    fun actualCompletedIndexes(): List<Int> =
        (completedIndexes(segments, progressSeconds) +
            if (deadlineFinishCompleted) listOfNotNull(segments.lastOrNull()?.index) else emptyList())
            .distinct().sorted()
    fun allIndexes(): List<Int> = segments.map { it.index }
}

fun draftOutcome(draft: RecordDraft): String = when {
    draft.safetyInterrupted -> dev.pleos.starter.teum.records.ActivityOutcome.SAFETY_INTERRUPTED
    draft.recovered -> dev.pleos.starter.teum.records.ActivityOutcome.INTERRUPTED
    draft.allTimedPassed -> dev.pleos.starter.teum.records.ActivityOutcome.COMPLETED
    else -> dev.pleos.starter.teum.records.ActivityOutcome.INTERRUPTED
}

data class RecordEditable(
    var note: String = "",
    var nextAction: String = "",
    var recall: Boolean = false
)
