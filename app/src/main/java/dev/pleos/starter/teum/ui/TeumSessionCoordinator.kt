package dev.pleos.starter.teum.ui

import dev.pleos.starter.teum.content.RecommendDecision
import dev.pleos.starter.teum.content.Recommendation
import dev.pleos.starter.teum.content.SituationContent
import dev.pleos.starter.teum.content.SituationIds
import dev.pleos.starter.teum.content.TeumContentCatalog
import dev.pleos.starter.teum.content.TeumRecommender
import dev.pleos.starter.teum.content.TimePlan
import dev.pleos.starter.teum.records.newActivityRecord
import dev.pleos.starter.teum.records.RecordsRepository
import dev.pleos.starter.teum.session.ClockReading
import dev.pleos.starter.teum.session.PlanRequest
import dev.pleos.starter.teum.session.Outcome
import dev.pleos.starter.teum.session.PlanResult
import dev.pleos.starter.teum.session.Planner
import dev.pleos.starter.teum.session.Schedule
import dev.pleos.starter.teum.session.ScheduleKind
import dev.pleos.starter.teum.session.SessionEngine
import dev.pleos.starter.teum.session.SessionState
import dev.pleos.starter.teum.session.TimeInput
import dev.pleos.starter.teum.vehicle.CommandStage
import dev.pleos.starter.teum.vehicle.Parking
import dev.pleos.starter.teum.vehicle.SafeVehicleAdapter
import dev.pleos.starter.teum.vehicle.SettingCapability
import dev.pleos.starter.teum.vehicle.VehicleCommand
import dev.pleos.starter.teum.vehicle.VehicleFeature
import dev.pleos.starter.teum.vehicle.VehicleSafety
import dev.pleos.starter.teum.vehicle.VehicleSetting
import java.util.Calendar

import dev.pleos.starter.teum.vehicle.VehicleSettingsPort

enum class Screen { HOME, SETUP, SESSION, RESTORE, RECORD, HISTORY, SETTINGS, RECOVERY }

enum class TimeMode { DURATION, DEADLINE }

data class SetupState(
    val meta: SituationMeta,
    val mode: TimeMode = TimeMode.DURATION,
    val minutes: Int = meta.defaultMinutes,
    val travelMinutes: Int = meta.defaultTravelMinutes,
    val arrivalHour: Int = 13,
    val arrivalMinute: Int = 0,
    val useAlternative: Boolean = false,
    val shortConfirmed: Boolean = false
)

data class SetupComputed(
    val deadlineWallMillis: Long,
    val availableSeconds: Int,
    val recommendation: Recommendation,
    val plan: TimePlan?,
    val schedule: Schedule?,
    val refusal: String?
)

sealed class FeatureUi {
    object Checking : FeatureUi()
    data class Ready(val current: Double, val capability: SettingCapability) : FeatureUi()
    data class Manual(val reason: String) : FeatureUi()
}

data class AppliedChange(
    val setting: VehicleSetting,
    val initial: Double,
    val target: Double,
    val restored: Boolean = false
)

private class RunningSession(
    val meta: SituationMeta,
    val content: SituationContent,
    val schedule: Schedule,
    val segments: List<Segment>,
    val engine: SessionEngine,
    val startedElapsedMillis: Long,
    val startedWallMillis: Long
) {
    var elapsedMillis: Long = 0
    var elapsedSeconds: Long = 0
    var finished: Boolean = false
    var restricted: Boolean = false
    var finalElapsedSeconds: Long = 0
    var finalWallMillis: Long = 0
    var deadlineFinishStartedMillis: Long? = null
    var progressAtDeadline: Long? = null
    val timedTotalSeconds: Long get() = segments.sumOf { it.seconds.toLong() }
}

data class Banner(val text: String, val clay: Boolean, val tag: String = "banner")

/** Platform hosts serialize lifecycle, safety callbacks and actions on their UI thread. */
interface SessionMarkers {
    fun load(): SessionSnapshot?
    fun save(snapshot: SessionSnapshot)
    fun clear()
}

interface SessionClock {
    fun reading(): ClockReading
    fun startReading(): ClockReading = reading()
}

/** Values only: the renderer never receives the mutable engine. */
data class ActiveSessionState(
    val meta: SituationMeta,
    val schedule: Schedule,
    val segments: List<Segment>,
    val engineState: SessionState,
    val elapsedMillis: Long,
    val elapsedSeconds: Long,
    val finished: Boolean,
    val restricted: Boolean,
    val finalElapsedSeconds: Long,
    val finalWallMillis: Long,
    val deadlineFinishStartedMillis: Long?,
    val progressAtDeadline: Long?
) {
    val timedTotalSeconds: Long get() = segments.sumOf { it.seconds.toLong() }
    val displayElapsedSeconds: Long get() = deadlineFinishStartedMillis?.let {
        schedule.prepareSeconds + schedule.activitySeconds + (elapsedMillis - it).coerceAtLeast(0) / 1000L
    } ?: elapsedSeconds
    val stepIndex: Int get() = stepIndexAt(segments, displayElapsedSeconds)
}

data class TeumSessionState(
    val screen: Screen,
    val banner: Banner?,
    val safety: VehicleSafety,
    val safetySeen: Boolean,
    val setup: SetupState?,
    val active: ActiveSessionState?,
    val draft: RecordDraft?,
    val recovery: SessionSnapshot?,
    val editable: RecordEditable,
    val soundEnabled: Boolean,
    val restoreConsent: Boolean,
    val manualRestoreAck: Boolean,
    val awakeAcknowledged: Boolean,
    val cueCount: Int,
    val featureStatus: Map<VehicleFeature, FeatureUi>,
    val featureTargets: Map<VehicleFeature, Double>,
    val featureCommands: Map<VehicleFeature, VehicleCommand>,
    val applyConsent: Map<VehicleFeature, Boolean>,
    val restoreCommands: Map<VehicleFeature, VehicleCommand>,
    val restoreOriginAck: Map<VehicleFeature, Boolean>,
    val applied: Map<VehicleFeature, AppliedChange>
)

sealed interface TeumSessionAction {
    data class OpenSetup(val situationId: String) : TeumSessionAction
    data class UpdateSetup(val value: SetupState) : TeumSessionAction
    data class ConfirmShort(val confirmed: Boolean) : TeumSessionAction
    data object Start : TeumSessionAction
    data object Tick : TeumSessionAction
    data object FinishNow : TeumSessionAction
    data class WakeAcknowledgement(val acknowledged: Boolean) : TeumSessionAction
    data class SetSound(val enabled: Boolean) : TeumSessionAction
    data object RefreshFeatures : TeumSessionAction
    data class PreviewFeature(val feature: VehicleFeature, val value: Double) : TeumSessionAction
    data class ApplyConsent(val feature: VehicleFeature, val accepted: Boolean) : TeumSessionAction
    data class RestoreConsent(val accepted: Boolean) : TeumSessionAction
    data class ManualRestoreAck(val accepted: Boolean) : TeumSessionAction
    data class RestoreOriginAck(val feature: VehicleFeature, val accepted: Boolean) : TeumSessionAction
    data class PreviewRestore(val feature: VehicleFeature) : TeumSessionAction
    data class ConfirmRestoreFeature(val feature: VehicleFeature) : TeumSessionAction
    data object ConfirmRestore : TeumSessionAction
    data object RequireRestore : TeumSessionAction
    data object RecordRestricted : TeumSessionAction
    data class EditRecord(val values: RecordEditable) : TeumSessionAction
    data class SaveRecord(val values: RecordEditable) : TeumSessionAction
    data object DiscardRecord : TeumSessionAction
    data object OpenRecoveryRecord : TeumSessionAction
    data object DiscardRecovery : TeumSessionAction
    data object Home : TeumSessionAction
    data object History : TeumSessionAction
    data object Settings : TeumSessionAction
}

sealed interface TeumSessionEffect {
    data object Render : TeumSessionEffect
    data object RequestRender : TeumSessionEffect
    data object UpdateLive : TeumSessionEffect
    data class Timer(val running: Boolean) : TeumSessionEffect
    data class Cue(val text: String, val urgent: Boolean = false) : TeumSessionEffect
    data object StopCue : TeumSessionEffect
    data class SoundPreference(val enabled: Boolean) : TeumSessionEffect
}

// allow: SIZE_OK - Extracted session state machine remains together in this scoped increment; no renderer or platform lifecycle objects.
class TeumSessionCoordinator(
    private val repo: RecordsRepository,
    private val snapshots: SessionMarkers,
    private val clock: SessionClock
) {
    private var screen = Screen.HOME
    private var banner: Banner? = null
    private var safety = VehicleSafety()
    private var safetySeen = false
    private var port: VehicleSettingsPort? = null
    private var adapter: SafeVehicleAdapter? = null

    private var active: RunningSession? = null
    private var setup: SetupState? = null
    private var draft: RecordDraft? = null
    private var recovery: SessionSnapshot? = null
    private var editable = RecordEditable()

    private var soundEnabled = true
    private var restoreConsent = false
    private var manualRestoreAck = false
    private var awakeAcknowledged = false
    private var cueCount = 0
    private var lastLunchCueSecond = -1
    private var lastPersistedSecond = -1L


    private val featureStatus = mutableMapOf<VehicleFeature, FeatureUi>()
    private val featureTargets = mutableMapOf<VehicleFeature, Double>()
    private val featureCommands = mutableMapOf<VehicleFeature, VehicleCommand>()
    private val featurePreviewIds = mutableMapOf<VehicleFeature, Long>()
    private val applyConsent = mutableMapOf<VehicleFeature, Boolean>()
    private val restoreCommands = mutableMapOf<VehicleFeature, VehicleCommand>()
    private val restoreOriginAck = mutableMapOf<VehicleFeature, Boolean>()
    private val applied = mutableMapOf<VehicleFeature, AppliedChange>()

    private var lastRenderedStep = -1

    var onEffect: ((TeumSessionEffect) -> Unit)? = null

    val state: TeumSessionState get() = TeumSessionState(
        screen, banner, safety, safetySeen, setup, active?.let { session ->
            ActiveSessionState(session.meta.copy(quickMinutes = frozenList(session.meta.quickMinutes)),
                session.schedule, frozenList(session.segments), session.engine.state,
                session.elapsedMillis, session.elapsedSeconds, session.finished, session.restricted,
                session.finalElapsedSeconds, session.finalWallMillis,
                session.deadlineFinishStartedMillis, session.progressAtDeadline)
        }, draft?.let { it.copy(segments = frozenList(it.segments)) }, recovery, editable,
        soundEnabled, restoreConsent, manualRestoreAck, awakeAcknowledged, cueCount,
        frozenMap(featureStatus), frozenMap(featureTargets), frozenMap(featureCommands),
        frozenMap(applyConsent), frozenMap(restoreCommands), frozenMap(restoreOriginAck), frozenMap(applied)
    )

    private fun <K, V> frozenMap(values: Map<K, V>): Map<K, V> =
        java.util.Collections.unmodifiableMap(LinkedHashMap(values))

    private fun <T> frozenList(values: List<T>): List<T> =
        java.util.Collections.unmodifiableList(values.toList())

    fun dispatch(action: TeumSessionAction) {
        when (action) {
            is TeumSessionAction.OpenSetup -> if (screen == Screen.HOME) {
                metaOf(action.situationId)?.let(::openSetup)
            }
            is TeumSessionAction.UpdateSetup -> {
                val current = setup ?: return
                val value = action.value
                require(value.minutes in 1..180 && value.travelMinutes in 0..60)
                require(value.arrivalHour in 0..23 && value.arrivalMinute in 0..59)
                invalidateFeaturePreviews()
                setup = value.copy(meta = current.meta, shortConfirmed = false)
                render()
            }
            is TeumSessionAction.ConfirmShort -> {
                setup = setup?.copy(shortConfirmed = action.confirmed)
                render()
            }
            TeumSessionAction.Start -> if (screen == Screen.SETUP) startSession()
            TeumSessionAction.Tick -> if (screen == Screen.SESSION) tick()
            TeumSessionAction.FinishNow -> if (screen == Screen.SESSION) finishNow()
            is TeumSessionAction.WakeAcknowledgement -> if (active != null) {
                awakeAcknowledged = action.acknowledged
                if (action.acknowledged) {
                    playCue("깨어 있음을 확인했습니다. 복원은 다음 화면에서 직접 동의해야 합니다.")
                } else {
                    adapter?.cancelPending()
                }
                render()
            }
            is TeumSessionAction.SetSound -> {
                soundEnabled = action.enabled
                emit(TeumSessionEffect.SoundPreference(action.enabled))
                render()
            }
            TeumSessionAction.RefreshFeatures -> refreshFeatureStatus()
            is TeumSessionAction.PreviewFeature -> {
                featureTargets[action.feature] = action.value
                previewFeature(action.feature, action.value)
            }
            is TeumSessionAction.ApplyConsent -> applyConsent[action.feature] = action.accepted
            is TeumSessionAction.RestoreConsent -> {
                restoreConsent = action.accepted
                if (!action.accepted) adapter?.cancelPending()
                updateLive()
            }
            is TeumSessionAction.ManualRestoreAck -> { manualRestoreAck = action.accepted; updateLive() }
            is TeumSessionAction.RestoreOriginAck -> restoreOriginAck[action.feature] = action.accepted
            is TeumSessionAction.PreviewRestore -> if (screen == Screen.RESTORE) previewRestore(action.feature)
            is TeumSessionAction.ConfirmRestoreFeature -> if (screen == Screen.RESTORE) confirmRestoreFeature(action.feature)
            TeumSessionAction.ConfirmRestore -> if (screen == Screen.RESTORE) confirmRestore()
            TeumSessionAction.RequireRestore -> if (screen == Screen.RESTORE) {
                banner = Banner("복원 확인을 거친 뒤 기록할 수 있습니다.", clay = false)
                render()
            }
            TeumSessionAction.RecordRestricted -> if (screen == Screen.RESTORE && active?.restricted == true) recordRestricted()
            is TeumSessionAction.EditRecord -> if (screen == Screen.RECORD) editable = action.values
            is TeumSessionAction.SaveRecord -> if (screen == Screen.RECORD) saveRecord(action.values)
            TeumSessionAction.DiscardRecord -> if (screen == Screen.RECORD) discardRecord()
            TeumSessionAction.OpenRecoveryRecord -> if (screen == Screen.RECOVERY) openRecoveryRecord()
            TeumSessionAction.DiscardRecovery -> if (screen == Screen.RECOVERY) {
                snapshots.clear(); recovery = null; screen = Screen.HOME; render()
            }
            TeumSessionAction.Home -> if (screen in setOf(Screen.HOME, Screen.SETUP, Screen.HISTORY, Screen.SETTINGS)) {
                setup = null
                invalidateFeaturePreviews()
                screen = Screen.HOME
                render()
            }
            TeumSessionAction.History -> if (screen == Screen.HOME) { screen = Screen.HISTORY; banner = null; render() }
            TeumSessionAction.Settings -> if (screen == Screen.HOME) { screen = Screen.SETTINGS; banner = null; render() }
        }
    }

    /** Host owns the transport; coordinator exclusively owns its safe command adapter. */
    fun onStart(settingsPort: VehicleSettingsPort?) {
        adapter?.close()
        port = settingsPort
        adapter = settingsPort?.let { SafeVehicleAdapter(it, { reading().elapsedMillis }, ::onCommand) }
        adapter?.updateSafety(safety)
        if (active != null) startTimer()
        if (screen == Screen.SETUP) {
            featureCommands.clear(); featurePreviewIds.clear(); applyConsent.clear()
            refreshFeatureStatus()
        }
        if (screen == Screen.RESTORE) {
            restoreCommands.clear()
            banner = Banner("화면을 떠난 동안 복원 정보가 초기화되었습니다. 설정은 직접 확인하세요.", clay = false)
        }
        render()
    }

    fun onStop() {
        stopTimer()
        emit(TeumSessionEffect.StopCue)
        adapter?.cancelPending()
        val session = active
        if (session != null && !session.finished) {
            val snapshot = snapshotOf(session, "앱이 화면을 떠나 타이머가 중단되었습니다.")
            snapshots.save(snapshot)
            recovery = snapshot
            active = null
            screen = Screen.RECOVERY
            awakeAcknowledged = false
            lastLunchCueSecond = -1
        }
        adapter?.close()
        adapter = null
        port = null
    }

    fun onDestroy() {
        stopTimer()
        emit(TeumSessionEffect.StopCue)
        adapter?.close()
        adapter = null
        port = null
    }

    private fun discardRecord() {
        snapshots.clear()
        recovery = null
        draft = null
        active = null
        editable = RecordEditable()
        screen = Screen.HOME
        banner = Banner("기록을 저장하지 않았습니다.", clay = false)
        render()
    }

    private fun emit(effect: TeumSessionEffect) { onEffect?.invoke(effect) }
    private fun render() = emit(TeumSessionEffect.Render)
    private fun requestRender() = emit(TeumSessionEffect.RequestRender)
    private fun updateLive() = emit(TeumSessionEffect.UpdateLive)
    private fun startTimer() = emit(TeumSessionEffect.Timer(true))
    private fun stopTimer() = emit(TeumSessionEffect.Timer(false))

    private fun reading(): ClockReading = clock.reading()

    fun onCreate(sound: Boolean = true) {
        soundEnabled = sound
        val saved = snapshots.load()
        if (saved != null) {
            recovery = saved
            if (saved.restoredAndReadyToRecord) {
                draft = draftFromSnapshot(saved, ready = true)
                screen = Screen.RECORD
                editable = RecordEditable()
                banner = Banner("저장 전에 앱이 중단되었습니다. 실제 진행을 확인하고 기록하세요.", clay = false)
            } else {
                screen = Screen.RECOVERY
            }
        }
        render()
    }

    fun onSafety(observation: VehicleSafety) {
        safety = observation
        safetySeen = true
        adapter?.updateSafety(observation)
        val session = active
        if (session != null && !session.finished &&
            observation.blockedReason(reading().elapsedMillis) != null
        ) {
            parkingLost()
            return
        }
        updateLive()
    }

    private fun parkingLost() {
        val session = active ?: return
        if (session.finished) return
        val now = reading()
        stopTimer()
        emit(TeumSessionEffect.StopCue)
        adapter?.cancelPending()
        try {
            session.engine.parkingLost(now)
        } catch (e: RuntimeException) {
            // The UI still fails closed even if the state machine is already terminal.
        }
        session.restricted = true
        session.finished = true
        session.finalElapsedSeconds = session.elapsedSeconds
        session.finalWallMillis = now.wallMillis
        snapshots.save(snapshotOf(session, "주차/주행 제한 신뢰를 잃어 제어를 중단했습니다.", restricted = true))
        screen = Screen.RESTORE
        banner = Banner("주차 상태를 확인할 수 없어 제어를 중단했습니다. 자동 복원은 시도하지 않습니다.", clay = true)
        render()
    }

    // ---------------------------------------------------------------- setup

    private fun openSetup(meta: SituationMeta) {
        val calendar = Calendar.getInstance().apply { timeInMillis = reading().wallMillis; add(Calendar.MINUTE, meta.defaultMinutes) }
        var minute = ((calendar.get(Calendar.MINUTE) + 4) / 5) * 5
        if (minute >= 60) {
            calendar.add(Calendar.HOUR_OF_DAY, 1)
            minute -= 60
        }
        setup = SetupState(
            meta = meta.copy(quickMinutes = frozenList(meta.quickMinutes)),
            arrivalHour = calendar.get(Calendar.HOUR_OF_DAY),
            arrivalMinute = minute
        )
        featureStatus.clear()
        featureStatus[VehicleFeature.SEAT_POSITION] = FeatureUi.Manual(SEAT_REASON)
        featureTargets.clear()
        featureCommands.clear()
        featurePreviewIds.clear()
        applyConsent.clear()
        screen = Screen.SETUP
        banner = null
        refreshFeatureStatus()
        render()
    }

    private fun refreshFeatureStatus() {
        featureStatus[VehicleFeature.HVAC_TEMPERATURE] = FeatureUi.Checking
        featureStatus[VehicleFeature.AMBIENT_BRIGHTNESS] = FeatureUi.Checking
        featureStatus[VehicleFeature.SEAT_POSITION] = FeatureUi.Manual(SEAT_REASON)
        val settingsPort = port
        if (settingsPort == null) {
            val reason = "차량 설정 연동을 시작할 수 없습니다. 차량 메뉴에서 직접 조정하세요."
            featureStatus[VehicleFeature.HVAC_TEMPERATURE] = FeatureUi.Manual(reason)
            featureStatus[VehicleFeature.AMBIENT_BRIGHTNESS] = FeatureUi.Manual(reason)
            requestRender()
            return
        }
        settingsPort.inspect(VehicleSetting(VehicleFeature.HVAC_TEMPERATURE, DRIVER_AREA)) { result ->
            featureStatus[VehicleFeature.HVAC_TEMPERATURE] = result.fold(
                onSuccess = { inspection ->
                    if (featureTargets[VehicleFeature.HVAC_TEMPERATURE] == null) {
                        featureTargets[VehicleFeature.HVAC_TEMPERATURE] = inspection.value
                    }
                    FeatureUi.Ready(inspection.value, inspection.capability)
                },
                onFailure = { FeatureUi.Manual(featureReason(it)) }
            )
            requestRender()
        }
        settingsPort.inspect(VehicleSetting(VehicleFeature.AMBIENT_BRIGHTNESS, DRIVER_AREA)) { result ->
            featureStatus[VehicleFeature.AMBIENT_BRIGHTNESS] = result.fold(
                onSuccess = { inspection ->
                    if (featureTargets[VehicleFeature.AMBIENT_BRIGHTNESS] == null) {
                        featureTargets[VehicleFeature.AMBIENT_BRIGHTNESS] = inspection.value
                    }
                    FeatureUi.Ready(inspection.value, inspection.capability)
                },
                onFailure = { FeatureUi.Manual(featureReason(it)) }
            )
            requestRender()
        }
    }

    private fun featureReason(error: Throwable): String {
        val message = error.message ?: error.javaClass.simpleName
        return when {
            message.contains("SecurityException") || message.contains("Permission") ->
                "이 기기에서 읽기/제어 권한이 없습니다. 차량 메뉴에서 직접 조정하세요."
            message.contains("PropertyNotAvailable") || message.contains("unsupported") ||
                message.contains("not supported") ->
                "이 기기에서 지원되지 않습니다. 차량 메뉴에서 직접 조정하세요."
            message.contains("Timeout") -> "차량 응답이 없습니다. 차량 메뉴에서 직접 조정하세요."
            else -> "지원 여부를 확인할 수 없습니다: ${message.take(90)}"
        }
    }

    fun computeSetup(state: SetupState, nowWall: Long): SetupComputed {
        val travel = state.travelMinutes * 60
        val deadline = if (state.mode == TimeMode.DURATION) {
            nowWall + state.minutes * 60_000L
        } else {
            resolveDeadline(state.arrivalHour, state.arrivalMinute, nowWall)
        }
        val available = ((deadline - nowWall) / 1000L)
            .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        val recommendation = TeumRecommender.recommend(state.meta.id, available, travel)
        val chosen = if (state.useAlternative && recommendation.alternative != null) {
            recommendation.alternative
        } else {
            recommendation.primary
        }
        if (chosen == null) {
            return SetupComputed(deadline, available, recommendation, null, null, recommendation.reason)
        }
        val time = when {
            recommendation.decision == RecommendDecision.START_SHORT_CONFIRM || state.useAlternative ->
                TimeInput.Duration(chosen.totalSeconds().toLong())
            state.mode == TimeMode.DEADLINE -> TimeInput.Deadline(deadline)
            else -> TimeInput.Duration(state.minutes * 60L)
        }
        return when (val result = Planner.plan(
            PlanRequest(state.meta.session, nowWall, time, travel.toLong())
        )) {
            is PlanResult.Available ->
                SetupComputed(deadline, available, recommendation, chosen, result.schedule, null)
            is PlanResult.Insufficient ->
                SetupComputed(
                    deadline, available, recommendation, chosen, null,
                    "시간이 부족합니다. 최소 ${formatDuration(result.minimumSeconds)}가 필요합니다."
                )
            is PlanResult.Invalid ->
                SetupComputed(deadline, available, recommendation, chosen, null, "시간을 계산할 수 없습니다.")
        }
    }

    fun startBlockedReason(computed: SetupComputed, state: SetupState): String? {
        val schedule = computed.schedule ?: return computed.refusal ?: "시간이 부족합니다."
        if (schedule.kind == ScheduleKind.SHORT && !state.shortConfirmed) {
            return "짧은 활동으로 시작하려면 아래 확인이 필요합니다."
        }
        val blocked = safety.blockedReason(reading().elapsedMillis)
        if (blocked != null) {
            return "주차 P와 주행 제한 없음이 확인되어야 시작할 수 있습니다. ${safetyReason(blocked)}"
        }
        return null
    }

    private fun startSession() {
        val state = setup ?: return
        // Bracket normal wall elapsed inside monotonic elapsed; ticks sample wall first.
        val now = clock.startReading()
        val nowWall = now.wallMillis
        val computed = computeSetup(state, nowWall)
        val schedule = computed.schedule ?: run {
            banner = Banner(computed.refusal ?: "시간이 부족합니다.", clay = true)
            render()
            return
        }
        if (schedule.kind == ScheduleKind.SHORT && !state.shortConfirmed) return
        val blocked = safety.blockedReason(now.elapsedMillis)
        if (blocked != null) {
            banner = Banner("주차 P와 주행 제한 없음이 확인되어야 시작할 수 있습니다. ${safetyReason(blocked)}", clay = true)
            render()
            return
        }
        if (!schedule.vehicleControlsAllowed) {
            invalidateFeaturePreviews()
        }
        val engine = try {
            SessionEngine.start(schedule, now, shortConfirmed = state.shortConfirmed)
        } catch (e: IllegalArgumentException) {
            banner = Banner("시작 시각이 계획 범위를 벗어났습니다. 시간을 다시 확인하세요.", clay = true)
            render()
            return
        }
        val content = TeumContentCatalog.get(state.meta.id) ?: return
        val segments = buildSegments(
            state.meta.id, schedule.kind, schedule.prepareSeconds,
            schedule.activitySeconds, schedule.finishSeconds
        )
        applied.clear()
        restoreCommands.clear()
        restoreConsent = false
        manualRestoreAck = false
        awakeAcknowledged = false
        cueCount = 0
        lastLunchCueSecond = -1
        lastPersistedSecond = -1
        val session = RunningSession(
            meta = state.meta, content = content, schedule = schedule, segments = segments,
            engine = engine, startedElapsedMillis = now.elapsedMillis, startedWallMillis = nowWall
        )
        active = session
        setup = null
        screen = Screen.SESSION
        snapshots.save(snapshotOf(session, "세션을 시작했습니다."))
        if (schedule.vehicleControlsAllowed) {
            featureCommands.values.filter { it.stage == CommandStage.PREVIEW }.toList().forEach { command ->
                adapter?.confirm(command.id, applyConsent[command.setting.feature] == true)
            }
        }
        banner = null
        lastRenderedStep = -1
        startTimer()
        render()
        playStepCue(segments.firstOrNull())
    }

    // -------------------------------------------------------------- session

    private fun displayElapsed(session: RunningSession): Long {
        val start = session.deadlineFinishStartedMillis ?: return session.elapsedSeconds
        return session.schedule.prepareSeconds + session.schedule.activitySeconds +
            (session.elapsedMillis - start).coerceAtLeast(0) / 1000L
    }

    private fun sessionStep(session: RunningSession): Int =
        stepIndexAt(session.segments, displayElapsed(session))

    private fun tick() {
        tick(reading())
    }

    private fun tick(now: ClockReading) {
        val session = active ?: return
        if (session.finished) return
        session.elapsedMillis = (now.elapsedMillis - session.startedElapsedMillis).coerceAtLeast(0)
        session.elapsedSeconds = session.elapsedMillis / 1000L
        try {
            session.engine.reevaluate(now)
        } catch (e: RuntimeException) {
            stopTimer()
            emit(TeumSessionEffect.StopCue)
            adapter?.cancelPending()
            val snapshot = snapshotOf(session, "타이머 상태를 확인할 수 없어 활동이 중단되었습니다.")
            snapshots.save(snapshot)
            recovery = snapshot
            active = null
            screen = Screen.RECOVERY
            banner = Banner("타이머 오류로 활동을 중단했습니다. 차량 설정은 직접 확인하세요.", clay = true)
            render()
            return
        }
        adapter?.tick()
        if (safety.blockedReason(now.elapsedMillis) != null) {
            parkingLost()
            return
        }
        val activityEnd = session.schedule.prepareSeconds + session.schedule.activitySeconds
        if (session.engine.state == SessionState.FINISHING &&
            session.elapsedSeconds < activityEnd && session.deadlineFinishStartedMillis == null
        ) {
            session.deadlineFinishStartedMillis = session.elapsedMillis
            session.progressAtDeadline = session.elapsedSeconds
            adapter?.cancelPending()
        }
        val step = sessionStep(session)
        if (step != lastRenderedStep) {
            lastRenderedStep = step
            playStepCue(session.segments.getOrNull(step))
            render()
            return
        }
        if (session.meta.id == SituationIds.LUNCH_BREAK && !awakeAcknowledged) {
            lunchCueTick(session)
        }
        val finishStarted = session.deadlineFinishStartedMillis
        if ((if (finishStarted == null) session.elapsedSeconds >= session.timedTotalSeconds
              else session.elapsedMillis - finishStarted >= session.schedule.finishSeconds * 1000L) &&
            session.engine.state == SessionState.FINISHING
        ) {
            completeTimedSession(session, now)
            return
        }
        if (session.elapsedSeconds - lastPersistedSecond >= 5) {
            lastPersistedSecond = session.elapsedSeconds
            snapshots.save(snapshotOf(session, "진행 중"))
        }
        updateLive()
    }

    private fun completeTimedSession(session: RunningSession, now: ClockReading) {
        stopTimer()
        emit(TeumSessionEffect.StopCue)
        try {
            session.engine.finishingDone(now)
        } catch (e: IllegalStateException) {
            // Already past finishing; no synthetic completion is created.
        }
        session.finished = true
        session.finalElapsedSeconds = session.elapsedSeconds
        session.finalWallMillis = now.wallMillis
        snapshots.save(snapshotOf(session, if (session.progressAtDeadline == null)
            "활동 시간이 실제로 모두 지났습니다." else "남은 시간 부족으로 활동을 일찍 마무리했습니다."))
        screen = Screen.RESTORE
        render()
        playCue(if (session.progressAtDeadline == null) "활동을 마쳤습니다. 복원을 확인하세요."
            else "남은 시간이 부족해 활동을 일찍 마무리했습니다. 복원을 확인하세요.")
    }

    private fun finishNow() {
        val session = active ?: return
        if (session.finished) return
        val now = reading()
        session.elapsedMillis = (now.elapsedMillis - session.startedElapsedMillis).coerceAtLeast(0)
        session.elapsedSeconds = session.elapsedMillis / 1000L
        stopTimer()
        emit(TeumSessionEffect.StopCue)
        adapter?.cancelPending()
        try {
            session.engine.finishEarly(now)
        } catch (e: IllegalStateException) {
            // Idempotent cancellation; the record still reflects actual progress.
        }
        session.finished = true
        session.finalElapsedSeconds = session.elapsedSeconds
        session.finalWallMillis = now.wallMillis
        snapshots.save(snapshotOf(session, "사용자가 지금 마무리를 선택했습니다."))
        screen = Screen.RESTORE
        render()
    }

    private fun playStepCue(segment: Segment?) {
        segment ?: return
        if (!soundEnabled) return
        emit(TeumSessionEffect.Cue("${segment.title}. ${segment.prompt}"))
        if (screen == Screen.SESSION) updateLive()
    }

    private fun playCue(text: String) {
        if (!soundEnabled) return
        emit(TeumSessionEffect.Cue(text))
    }

    private fun lunchCueTick(session: RunningSession) {
        if (!soundEnabled || awakeAcknowledged) return
        val wakeIndex = session.segments.indexOfFirst { it.stepId == "wake" || it.stepId == "wrap" }
        if (wakeIndex < 0) return
        val wakeStart = segmentStart(session.segments, wakeIndex)
        val wakeEnd = segmentEnd(session.segments, wakeIndex)
        val elapsed = displayElapsed(session)
        if (elapsed < wakeStart) return
        val remaining = (wakeEnd - elapsed).toInt()
        if (remaining < 0) return
        val scheduled = setOf(180, 120, 90, 60, 45, 30, 20, 10, 5)
        val due = remaining in scheduled || (remaining <= 60 && remaining % 10 == 0)
        if (!due || remaining == lastLunchCueSecond) return
        lastLunchCueSecond = remaining
        cueCount++
        emit(TeumSessionEffect.Cue("복귀 준비 시간입니다. 깨어 있으면 화면의 깨어 있음 확인을 눌러 주세요.", remaining <= 60))
        updateLive()
    }

    private fun snapshotOf(
        session: RunningSession, reason: String, restricted: Boolean = session.restricted
    ): SessionSnapshot = SessionSnapshot(
        situationId = session.meta.id,
        kind = session.schedule.kind.name,
        startWallMillis = session.schedule.startWallMillis,
        deadlineWallMillis = session.schedule.deadlineWallMillis,
        prepareSeconds = session.schedule.prepareSeconds,
        activitySeconds = session.schedule.activitySeconds,
        finishSeconds = session.schedule.finishSeconds,
        travelSeconds = session.schedule.travelSeconds,
        elapsedSeconds = session.elapsedSeconds,
        wallMillis = reading().wallMillis,
        stage = if (session.finished) "FINISHED" else session.engine.state.name,
        reason = reason,
        restricted = restricted,
        restoredAndReadyToRecord = false,
        awakeAcknowledged = awakeAcknowledged,
        progressSeconds = session.progressAtDeadline ?: session.elapsedSeconds,
        deadlineFinishCompleted = session.deadlineFinishStartedMillis?.let {
            session.elapsedMillis - it >= session.schedule.finishSeconds * 1000L
        } == true
    )

    // -------------------------------------------------------------- restore

    private fun confirmRestore() {
        val session = active ?: return
        if (session.restricted) {
            recordRestricted()
            return
        }
        if (!restoreConsent) return
        if (session.meta.id == SituationIds.LUNCH_BREAK && !awakeAcknowledged) {
            banner = Banner("깨어 있음 확인이 필요합니다. 확인 전에는 복원하지 않습니다.", clay = true)
            render()
            return
        }
        val now = reading()
        val blocked = safety.blockedReason(now.elapsedMillis)
        if (blocked != null) {
            banner = Banner("복원을 확인할 수 없습니다: ${safetyReason(blocked)}", clay = true)
            render()
            return
        }
        if (restoreCommands.values.any {
                it.stage in setOf(CommandStage.PREVIEW, CommandStage.QUEUED, CommandStage.REQUESTED)
            }) {
            banner = Banner("복원 요청이 처리 중입니다. 실제 읽기 확인을 기다리세요.", clay = false)
            render()
            return
        }
        if (applied.values.any { !it.restored } && !manualRestoreAck) {
            banner = Banner("남은 설정을 직접 확인했다는 항목에 동의해야 기록할 수 있습니다.", clay = true)
            render()
            return
        }
        try {
            session.engine.approveRestore(now, true)
            session.engine.restoreFinished(now)
        } catch (e: IllegalStateException) {
            banner = Banner("복원 상태를 확정할 수 없습니다. 다시 확인하세요.", clay = true)
            render()
            return
        }
        // Keep the activity elapsed frozen at finish/cancel; restore-screen waiting
        // must never turn an early finish into a completed record.
        session.finalWallMillis = session.engine.actualFinishWallMillis ?: now.wallMillis
        finalizeRecord(session)
    }

    private fun recordRestricted() {
        val session = active ?: return
        finalizeRecord(session)
    }

    private fun finalizeRecord(session: RunningSession) {
        val elapsed = if (session.finalElapsedSeconds > 0) session.finalElapsedSeconds else session.elapsedSeconds
        val progress = session.progressAtDeadline ?: elapsed
        val allTimed = session.engine.outcome != Outcome.EARLY_FINISH &&
            session.progressAtDeadline == null && elapsed >= session.timedTotalSeconds
        draft = RecordDraft(
            situationId = session.meta.id,
            segments = session.segments,
            elapsedSeconds = elapsed,
            plannedReturnWallMillis = session.schedule.plannedReturnWallMillis,
            actualFinishWallMillis = session.finalWallMillis,
            safetyInterrupted = session.restricted,
            recovered = false,
            allTimedPassed = allTimed,
            restoredBeforeSave = !session.restricted,
            progressSeconds = progress,
            deadlineFinishCompleted = session.deadlineFinishStartedMillis?.let {
                session.elapsedMillis - it >= session.schedule.finishSeconds * 1000L
            } == true
        )
        snapshots.save(
            SessionSnapshot(
                situationId = session.meta.id,
                kind = session.schedule.kind.name,
                startWallMillis = session.schedule.startWallMillis,
                deadlineWallMillis = session.schedule.deadlineWallMillis,
                prepareSeconds = session.schedule.prepareSeconds,
                activitySeconds = session.schedule.activitySeconds,
                finishSeconds = session.schedule.finishSeconds,
                travelSeconds = session.schedule.travelSeconds,
                elapsedSeconds = elapsed,
                wallMillis = session.finalWallMillis,
                stage = if (session.engine.outcome == Outcome.EARLY_FINISH) "EARLY_FINISH"
                    else session.engine.state.name,
                reason = if (session.restricted) "안전 중단 후 기록 대기" else "복원 확인 후 기록 대기",
                restricted = session.restricted,
                restoredAndReadyToRecord = true,
                awakeAcknowledged = awakeAcknowledged,
                progressSeconds = progress,
                deadlineFinishCompleted = session.deadlineFinishStartedMillis?.let {
                    session.elapsedMillis - it >= session.schedule.finishSeconds * 1000L
                } == true
            )
        )
        active = null
        stopTimer()
        emit(TeumSessionEffect.StopCue)
        editable = RecordEditable()
        screen = Screen.RECORD
        banner = null
        render()
    }

    private fun draftFromSnapshot(snapshot: SessionSnapshot, ready: Boolean): RecordDraft {
        val meta = metaOf(snapshot.situationId) ?: TEUM_SITUATIONS.first()
        val segments = buildSegments(
            snapshot.situationId, ScheduleKind.valueOf(snapshot.kind),
            snapshot.prepareSeconds, snapshot.activitySeconds, snapshot.finishSeconds
        )
        val allTimed = snapshot.stage != "EARLY_FINISH" &&
            snapshot.progressSeconds >= snapshot.timedTotalSeconds
        return RecordDraft(
            situationId = snapshot.situationId,
            segments = segments,
            elapsedSeconds = snapshot.elapsedSeconds,
            plannedReturnWallMillis = runCatching {
                snapshot.toSchedule(meta.session).plannedReturnWallMillis
            }.getOrNull(),
            actualFinishWallMillis = snapshot.wallMillis,
            safetyInterrupted = snapshot.restricted,
            recovered = !ready,
            allTimedPassed = allTimed,
            restoredBeforeSave = ready && !snapshot.restricted,
            progressSeconds = snapshot.progressSeconds,
            deadlineFinishCompleted = snapshot.deadlineFinishCompleted
        )
    }

    // --------------------------------------------------------------- record

    private fun saveRecord(values: RecordEditable) {
        val current = draft ?: return
        val note = values.note.trim()
        val next = values.nextAction.trim()
        val recall = values.recall
        val outcome = draftOutcome(current)
        val completed = current.actualCompletedIndexes()
        val title = metaOf(current.situationId)?.activityTitle ?: "활동"
        try {
            repo.save(
                newActivityRecord(
                    situationId = current.situationId,
                    activityTitle = title,
                    totalSteps = current.totalSteps,
                    completedStepIndexes = completed,
                    elapsedSeconds = current.elapsedSeconds,
                    outcome = outcome,
                    note = note,
                    nextAction = next,
                    recallAccepted = recall && next.isNotBlank(),
                    historyConsentAtSave = repo.isHistoryConsent()
                )
            )
        } catch (e: IllegalArgumentException) {
            banner = Banner("기록을 저장할 수 없습니다: ${e.message}", clay = true)
            render()
            return
        }
        snapshots.clear()
        recovery = null
        draft = null
        active = null
        editable = RecordEditable()
        screen = Screen.HOME
        banner = Banner("기록을 저장했습니다.", clay = false)
        render()
    }

    private fun openRecoveryRecord() {
        val snapshot = recovery ?: return
        draft = draftFromSnapshot(snapshot, snapshot.restoredAndReadyToRecord)
        recovery = null
        editable = RecordEditable()
        screen = Screen.RECORD
        banner = Banner(
            if (snapshot.restoredAndReadyToRecord) {
                "저장 전에 앱이 중단되었습니다. 실제 진행을 확인하고 기록하세요."
            } else {
                "앱이 중단되어 마지막으로 확인된 진행만 기록합니다."
            },
            clay = false
        )
        render()
    }

    private fun invalidateFeaturePreviews() {
        adapter?.cancelPending()
        featureCommands.clear()
        featurePreviewIds.clear()
        applyConsent.clear()
    }

    private fun onCommand(command: VehicleCommand) {
        val feature = command.setting.feature
        if (!command.isRestore) {
            featureCommands[feature] = command
            if (command.stage == CommandStage.PREVIEW) featurePreviewIds[feature] = command.id
            if (command.stage == CommandStage.READBACK) {
                val initial = command.initial
                if (initial != null) {
                    applied[feature] = AppliedChange(
                        command.setting, initial, command.readback ?: command.target
                    )
                }
            }
        } else {
            restoreCommands[feature] = command
            if (command.stage == CommandStage.READBACK) applied[feature]?.let { applied[feature] = it.copy(restored = true) }
        }
        when (screen) {
            Screen.SETUP, Screen.RESTORE -> requestRender()
            Screen.SESSION -> updateLive()
            else -> Unit
        }
    }

    private fun previewFeature(feature: VehicleFeature, value: Double) {
        val state = setup ?: return
        if (computeSetup(state, reading().wallMillis).schedule?.vehicleControlsAllowed != true) {
            invalidateFeaturePreviews()
            requestRender()
            return
        }
        val vehicleAdapter = adapter ?: return
        invalidateFeaturePreviews()
        featurePreviewIds[feature] = vehicleAdapter.previewChange(
            VehicleSetting(feature, DRIVER_AREA), value
        )
        requestRender()
    }

    fun restoreFeatureAllowed(): Boolean {
        val session = active ?: return false
        return !session.restricted && restoreConsent &&
            (session.meta.id != SituationIds.LUNCH_BREAK || awakeAcknowledged) &&
            safety.blockedReason(reading().elapsedMillis) == null
    }

    private fun previewRestore(feature: VehicleFeature) {
        if (!restoreFeatureAllowed()) return
        val change = applied[feature] ?: return
        adapter?.previewRestore(change.setting)
        requestRender()
    }

    private fun confirmRestoreFeature(feature: VehicleFeature) {
        if (!restoreFeatureAllowed()) return
        val command = restoreCommands[feature] ?: return
        if (command.requiresOriginConfirmation && restoreOriginAck[feature] != true) {
            banner = Banner("앱 외 변경 여부를 확인했다는 항목에 먼저 동의하세요.", clay = true)
            render()
            return
        }
        adapter?.confirm(command.id, true, confirmUnknownOrigin = true)
        requestRender()
    }

    // --------------------------------------------------------------- render

    private fun safetyReason(raw: String): String = when {
        raw.contains("disconnected", ignoreCase = true) -> "차량 서비스에 연결되지 않았습니다."
        raw.contains("stale", ignoreCase = true) -> "차량 상태 확인이 지연되었습니다."
        raw.contains("not verified Park", ignoreCase = true) ||
            raw.contains("Gear is not", ignoreCase = true) -> "주차 P가 확인되지 않았습니다."
        raw.contains("POWERTRAIN", ignoreCase = true) -> "주차 정보 읽기 권한이 없습니다."
        raw.contains("UX restrictions active", ignoreCase = true) -> "주행 제한이 활성화되어 있습니다."
        else -> "주차와 주행 제한을 확인할 수 없습니다."
    }

    companion object {
        const val DRIVER_AREA = "ROW_1_LEFT"
        const val SEAT_REASON = "안전 정지 계약과 OEM 동작이 검증되지 않아 좌석 이동은 제공하지 않습니다."
    }
}
