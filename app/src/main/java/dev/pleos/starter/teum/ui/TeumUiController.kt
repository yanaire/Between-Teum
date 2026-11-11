package dev.pleos.starter.teum.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.pleos.starter.teum.content.RecommendDecision
import dev.pleos.starter.teum.content.SituationIds
import dev.pleos.starter.teum.content.TeumContentCatalog
import dev.pleos.starter.teum.content.TimePlan
import dev.pleos.starter.teum.records.ActivityOutcome
import dev.pleos.starter.teum.records.ActivityRecord
import dev.pleos.starter.teum.records.RecordsRepository
import dev.pleos.starter.teum.records.SharedPreferencesRecordStore
import dev.pleos.starter.teum.session.ClockReading
import dev.pleos.starter.teum.session.ScheduleKind
import dev.pleos.starter.teum.session.SessionState
import dev.pleos.starter.teum.vehicle.AaosSafetySource
import dev.pleos.starter.teum.vehicle.CommandStage
import dev.pleos.starter.teum.vehicle.Parking
import dev.pleos.starter.teum.vehicle.PleosSettingsPort
import dev.pleos.starter.teum.vehicle.VehicleFeature
import dev.pleos.starter.teum.vehicle.VehicleSafety

// allow: SIZE_OK - Existing native renderer retained verbatim for this extraction; visual port belongs to the host increment.
class TeumUiController(
    private val activity: Activity,
    private val root: ViewGroup,
    sessionCoordinator: TeumSessionCoordinator? = null
) {
    private val theme = TeumTheme(activity)
    private val repo = RecordsRepository(SharedPreferencesRecordStore(activity))
    private val recordStore = SharedPreferencesRecordStore(activity)
    private val snapshots = SessionSnapshotStore(activity)
    private val uiPrefs = activity.getSharedPreferences("teum_ui_v1", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())

    private var source: AaosSafetySource? = null
    private var port: PleosSettingsPort? = null
    private var cue: TeumCuePlayer? = null

    val coordinator = (sessionCoordinator ?: TeumSessionCoordinator(repo, snapshots, object : SessionClock {
        override fun reading() = ClockReading(System.currentTimeMillis(), SystemClock.elapsedRealtime())
        override fun startReading(): ClockReading {
            val elapsed = SystemClock.elapsedRealtime()
            return ClockReading(System.currentTimeMillis(), elapsed)
        }
    })).also { it.onEffect = ::onSessionEffect }

    private val screen get() = coordinator.state.screen
    private val banner get() = coordinator.state.banner
    private val safety get() = coordinator.state.safety
    private val safetySeen get() = coordinator.state.safetySeen
    private val active get() = coordinator.state.active
    private val setup get() = coordinator.state.setup
    private val draft get() = coordinator.state.draft
    private val recovery get() = coordinator.state.recovery
    private val editable get() = coordinator.state.editable
    private val soundEnabled get() = coordinator.state.soundEnabled
    private val restoreConsent get() = coordinator.state.restoreConsent
    private val manualRestoreAck get() = coordinator.state.manualRestoreAck
    private val awakeAcknowledged get() = coordinator.state.awakeAcknowledged
    private val cueCount get() = coordinator.state.cueCount
    private val featureStatus get() = coordinator.state.featureStatus
    private val featureTargets get() = coordinator.state.featureTargets
    private val featureCommands get() = coordinator.state.featureCommands
    private val applyConsent get() = coordinator.state.applyConsent
    private val restoreCommands get() = coordinator.state.restoreCommands
    private val restoreOriginAck get() = coordinator.state.restoreOriginAck
    private val applied get() = coordinator.state.applied

    private val liveUpdaters = mutableListOf<() -> Unit>()
    private var renderQueued = false

    private val ticker = object : Runnable {
        override fun run() {
            if (active != null && screen == Screen.SESSION) {
                coordinator.dispatch(TeumSessionAction.Tick)
                handler.postDelayed(this, 1_000)
            }
        }
    }

    private fun onSessionEffect(effect: TeumSessionEffect) {
        when (effect) {
            TeumSessionEffect.Render -> render()
            TeumSessionEffect.RequestRender -> requestRender()
            TeumSessionEffect.UpdateLive -> updateLive()
            is TeumSessionEffect.Timer -> if (effect.running) startTimer() else stopTimer()
            is TeumSessionEffect.Cue -> {
                cue?.speak(effect.text)
                cue?.beep(urgent = effect.urgent)
            }
            TeumSessionEffect.StopCue -> cue?.stop()
            is TeumSessionEffect.SoundPreference -> {
                uiPrefs.edit().putBoolean(KEY_SOUND, effect.enabled).apply()
                if (effect.enabled) cue?.beep()
            }
        }
    }

    fun onCreate() = coordinator.onCreate(uiPrefs.getBoolean(KEY_SOUND, true))

    fun onStart() {
        if (source == null) {
            try {
                source = AaosSafetySource(activity, coordinator::onSafety).also { it.start() }
            } catch (e: RuntimeException) {
                source = null
            }
            try {
                port = PleosSettingsPort(activity)
            } catch (e: RuntimeException) {
                port = null
            }
            cue = TeumCuePlayer(activity).also { player ->
                player.listener = { if (screen == Screen.SETTINGS) requestRender() else updateLive() }
            }
        }
        coordinator.onStart(port)
    }

    fun onStop() {
        // Classify foreground interruption before source.close() emits disconnected.
        coordinator.onStop()
        cue?.close()
        cue = null
        source?.close()
        source = null
        port = null
    }

    fun onDestroy() {
        coordinator.onDestroy()
        cue?.close()
        cue = null
        source?.close()
        source = null
        port = null
    }

    private fun refreshFeatureStatus() = coordinator.dispatch(TeumSessionAction.RefreshFeatures)
    private fun computeSetup(state: SetupState, nowWall: Long) = coordinator.computeSetup(state, nowWall)
    private fun startBlockedReason(computed: SetupComputed, state: SetupState) = coordinator.startBlockedReason(computed, state)
    private fun displayElapsed(session: ActiveSessionState) = session.displayElapsedSeconds
    private fun finishNow() = coordinator.dispatch(TeumSessionAction.FinishNow)
    private fun acknowledgeWake() = coordinator.dispatch(TeumSessionAction.WakeAcknowledgement(true))
    private fun recordRestricted() = coordinator.dispatch(TeumSessionAction.RecordRestricted)
    private fun previewFeature(feature: VehicleFeature, value: Double) =
        coordinator.dispatch(TeumSessionAction.PreviewFeature(feature, value))
    private fun restoreFeatureAllowed() = coordinator.restoreFeatureAllowed()

    fun onConfigurationChanged() {
        render()
    }

    fun onBackPressed(): Boolean {
        when (screen) {
            Screen.HOME -> return false
            Screen.SETUP -> {
                coordinator.dispatch(TeumSessionAction.Home)
            }
            Screen.HISTORY, Screen.SETTINGS -> {
                coordinator.dispatch(TeumSessionAction.Home)
            }
            Screen.SESSION -> confirmFinish()
            Screen.RESTORE -> {
                if (active?.restricted == true) recordRestricted() else {
                    coordinator.dispatch(TeumSessionAction.RequireRestore)
                }
            }
            Screen.RECORD -> confirmDiscardRecord()
            Screen.RECOVERY -> confirmDiscardRecovery()
        }
        return true
    }

    private fun confirmFinish() {
        AlertDialog.Builder(activity)
            .setTitle("지금 마무리할까요?")
            .setMessage("진행 중인 화면 안내와 알림이 멈추고 복원 확인으로 이동합니다. 활동은 중도 종료로 기록됩니다.")
            .setPositiveButton("지금 마무리") { _, _ -> finishNow() }
            .setNegativeButton("계속하기", null)
            .show()
    }

    private fun confirmDiscardRecord() {
        AlertDialog.Builder(activity)
            .setTitle("기록을 저장하지 않을까요?")
            .setMessage("저장하지 않으면 이번 활동의 실제 진행과 시간은 남지 않습니다.")
            .setPositiveButton("저장하지 않고 나가기") { _, _ ->
                coordinator.dispatch(TeumSessionAction.DiscardRecord)
            }
            .setNegativeButton("계속 기록하기", null)
            .show()
    }

    private fun confirmDiscardRecovery() {
        AlertDialog.Builder(activity)
            .setTitle("중단된 활동을 닫을까요?")
            .setMessage("기록 없이 닫으면 이 활동의 진행은 남지 않습니다. 차량 명령은 다시 보내지 않습니다.")
            .setPositiveButton("기록 없이 닫기") { _, _ ->
                coordinator.dispatch(TeumSessionAction.DiscardRecovery)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // -------------------------------------------------------------- history

    private fun deleteRecord(record: ActivityRecord) {
        AlertDialog.Builder(activity)
            .setTitle("이 기록을 삭제할까요?")
            .setMessage("${formatDateTime(record.createdAtEpochMs)} 기록이 기기에서 삭제됩니다.")
            .setPositiveButton("삭제") { _, _ ->
                repo.delete(record.id)
                render()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun editRecord(record: ActivityRecord) {
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(theme.dp(TeumSpace.XL), theme.dp(TeumSpace.S), theme.dp(TeumSpace.XL), 0)
        }
        val note = theme.editText("메모를 직접 적어 주세요", "edit_note")
        note.setText(record.note)
        val next = theme.editText("다음 행동을 직접 적어 주세요", "edit_next", singleLine = true)
        next.setText(record.nextAction)
        val recall = theme.checkBox(
            "다음 출근 때 이 기록을 다시 볼게요", "edit_recall", record.recallAccepted
        ) {}
        column.addView(note)
        column.addView(theme.space(TeumSpace.S))
        column.addView(next)
        column.addView(recall)
        AlertDialog.Builder(activity)
            .setTitle("기록 수정")
            .setView(column)
            .setPositiveButton("저장") { _, _ ->
                repo.update(
                    record.id,
                    note.text.toString().trim(),
                    next.text.toString().trim(),
                    recall.isChecked && next.text.toString().trim().isNotBlank()
                )
                render()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun confirmClearAll() {
        val quarantine = recordStore.quarantinedRaw() != null
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(theme.dp(TeumSpace.XL), theme.dp(TeumSpace.S), theme.dp(TeumSpace.XL), 0)
        }
        val quarantineCheck = theme.checkBox(
            "격리된 손상 데이터도 함께 삭제", "clear_quarantine_included", quarantine
        ) {}
        if (quarantine) {
            column.addView(theme.text(
                "손상되어 격리된 저장 데이터가 있습니다.", TeumText.CAPTION, theme.clay
            ))
            column.addView(quarantineCheck)
        }
        AlertDialog.Builder(activity)
            .setTitle("모든 기록을 삭제할까요?")
            .setMessage("이 기기에 저장된 기록과 이력 동의 설정이 삭제됩니다.")
            .setView(column)
            .setPositiveButton("모두 삭제") { _, _ ->
                repo.clearAll()
                repo.setHistoryConsent(false)
                if (quarantine && quarantineCheck.isChecked) recordStore.clearQuarantine()
                render()
                Toast.makeText(activity, "기록을 모두 삭제했습니다.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun dismissRecall(record: ActivityRecord) {
        repo.dismissRecall(record.id)
        render()
    }

    // ------------------------------------------------------------- commands

    private fun requestRender() {
        if (renderQueued) return
        renderQueued = true
        handler.post {
            renderQueued = false
            render()
        }
    }

    private fun updateLive() {
        liveUpdaters.toList().forEach { runCatching { it() } }
    }

    private fun render() {
        liveUpdaters.clear()
        renderQueued = false
        root.removeAllViews()
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
        }
        val widthDp = activity.resources.configuration.screenWidthDp
        val horizontal = if (widthDp > TeumSize.CONTENT_MAX) {
            (widthDp - TeumSize.CONTENT_MAX) / 2
        } else {
            TeumSize.GUTTER
        }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(theme.dp(horizontal), theme.dp(TeumSpace.XL), theme.dp(horizontal), theme.dp(TeumSpace.XXXL))
        }
        scroll.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        column.tag = "screen_" + screen.name.lowercase()
        banner?.let { column.addView(renderBanner(it)) }
        when (screen) {
            Screen.HOME -> renderHome(column, widthDp >= TeumSize.WIDE_BREAK)
            Screen.SETUP -> renderSetup(column, widthDp >= TeumSize.WIDE_BREAK)
            Screen.SESSION -> renderSession(column)
            Screen.RESTORE -> renderRestore(column)
            Screen.RECORD -> renderRecord(column)
            Screen.HISTORY -> renderHistory(column)
            Screen.SETTINGS -> renderSettings(column)
            Screen.RECOVERY -> renderRecovery(column)
        }
        updateLive()
    }

    private fun renderBanner(value: Banner): View =
        theme.text(value.text, TeumText.BODY, if (value.clay) theme.clay else theme.pineDeep).apply {
            tag = value.tag
            background = theme.rounded(
                if (value.clay) theme.claySoft else theme.pineSoft, TeumRadius.CONTROL
            )
            setPadding(theme.dp(TeumSpace.L), theme.dp(TeumSpace.M), theme.dp(TeumSpace.L), theme.dp(TeumSpace.M))
            layoutParams = theme.lp(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                0f, 0, TeumSpace.XS, 0, TeumSpace.L
            )
        }

    private fun addTopBar(
        column: LinearLayout, title: String, subtitle: String? = null,
        backTag: String = "back_button", back: (() -> Unit)? = null
    ) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (back != null) row.addView(theme.quietButton("<  홈", backTag) { back() })
        val titles = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(theme.text(title, TeumText.TITLE))
        subtitle?.let { titles.addView(theme.text(it, TeumText.CAPTION, theme.inkMuted)) }
        row.addView(titles, theme.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(row, theme.lp(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            0f, 0, if (back == null) TeumSpace.S else 0, 0, TeumSpace.L
        ))
    }

    private fun safetyState(): Pair<String, Pair<Int, Int>> {
        val blocked = safety.blockedReason(SystemClock.elapsedRealtime())
        return when {
            !safetySeen || (!safety.connected && safety.generation == 0L) ->
                "차량 확인 중" to (theme.surfaceAlt to theme.inkMuted)
            blocked == null -> "P · 제한 없음" to (theme.pineSoft to theme.pineDeep)
            else -> "안전 확인 필요" to (theme.claySoft to theme.clay)
        }
    }

    private fun safetyReason(raw: String): String = when {
        raw.contains("disconnected", ignoreCase = true) -> "차량 서비스에 연결되지 않았습니다."
        raw.contains("stale", ignoreCase = true) -> "차량 상태 확인이 지연되었습니다."
        raw.contains("not verified Park", ignoreCase = true) ||
            raw.contains("Gear is not", ignoreCase = true) -> "주차 P가 확인되지 않았습니다."
        raw.contains("POWERTRAIN", ignoreCase = true) -> "주차 정보 읽기 권한이 없습니다."
        raw.contains("UX restrictions active", ignoreCase = true) -> "주행 제한이 활성화되어 있습니다."
        else -> "주차와 주행 제한을 확인할 수 없습니다."
    }

    private fun safetyPill(tag: String): TextView {
        val pill = theme.pill("차량 확인 중", theme.surfaceAlt, theme.inkMuted, tag)
        var last: Pair<String, Pair<Int, Int>>? = null
        liveUpdaters += {
            val state = safetyState()
            if (state != last) {
                last = state
                pill.text = state.first
                pill.setTextColor(state.second.second)
                pill.background = theme.rounded(state.second.first, TeumRadius.PILL)
            }
        }
        return pill
    }

    // ------------------------------------------------------------------ home

    private fun renderHome(column: LinearLayout, wide: Boolean) {
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(theme.text("틈", TeumText.DISPLAY))
        brand.addView(theme.text(
            activity.getString(dev.pleos.starter.R.string.teum_tagline),
            TeumText.CAPTION, theme.inkMuted
        ))
        header.addView(brand, theme.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(safetyPill("safety_pill"))
        column.addView(header, theme.lp(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            0f, 0, 0, 0, TeumSpace.XXL
        ))
        column.addView(theme.sectionLabel("지금 어떤 틈인가요"))
        column.addView(theme.text(
            "하나를 고르면 시간과 복귀를 먼저 확인합니다.", TeumText.BODY, theme.inkMuted
        ), theme.lp(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            0f, 0, TeumSpace.XS, 0, TeumSpace.L
        ))
        if (wide) {
            TEUM_SITUATIONS.chunked(2).forEach { pair ->
                val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                pair.forEachIndexed { index, meta ->
                    row.addView(situationCard(meta), theme.lp(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                        0, 0, if (index == pair.lastIndex) 0 else TeumSpace.M, 0
                    ))
                }
                if (pair.size == 1) row.addView(View(activity), theme.lp(0, 1, 1f))
                column.addView(row, theme.lp(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    0f, 0, 0, 0, TeumSpace.M
                ))
            }
        } else {
            TEUM_SITUATIONS.forEach { column.addView(situationCard(it)) }
        }

        val recalls = repo.pendingRecalls()
        if (recalls.isNotEmpty()) {
            column.addView(theme.sectionLabel("다음에 다시 볼 기록"), theme.lp(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                0f, 0, TeumSpace.L, 0, TeumSpace.S
            ))
            recalls.take(3).forEach { record ->
                val card = theme.card(TeumSpace.L)
                val meta = metaOf(record.situationId)
                card.addView(theme.text(meta?.title ?: "활동", TeumText.LABEL, theme.inkMuted))
                card.addView(theme.text(record.nextAction, TeumText.HEADING))
                card.addView(theme.text(
                    formatDateTime(record.createdAtEpochMs), TeumText.CAPTION, theme.inkMuted
                ))
                card.addView(theme.secondaryButton("확인함", "recall_dismiss_${record.id}") {
                    dismissRecall(record)
                }, theme.lp(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    0f, 0, TeumSpace.M, 0, 0
                ))
                column.addView(card)
            }
        }

        val footer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        footer.addView(theme.quietButton("기록  ${repo.listAll().size}", "history_button", theme.pine) {
            coordinator.dispatch(TeumSessionAction.History)
        })
        footer.addView(theme.quietButton("설정 · 지원 기능", "settings_button") {
            coordinator.dispatch(TeumSessionAction.Settings)
        })
        footer.addView(View(activity), theme.lp(0, 1, 1f))
        column.addView(footer, theme.lp(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            0f, TeumSpace.S, 0, 0, TeumSpace.S
        ))
        val reason = theme.text("", TeumText.CAPTION, theme.clay).apply { tag = "safety_reason" }
        var lastReason: String? = null
        liveUpdaters += {
            val blocked = safety.blockedReason(SystemClock.elapsedRealtime())
            val next = when {
                blocked == null -> "주차 P와 주행 제한 없음이 확인되었습니다. 시작할 수 있습니다."
                !safety.connected -> "차량 서비스 연결을 확인하는 중입니다. 확인 전에는 시작할 수 없습니다."
                else -> safetyReason(blocked) + " 확인 전에는 시작할 수 없습니다."
            }
            if (next != lastReason) {
                lastReason = next
                reason.text = next
                reason.setTextColor(if (blocked == null) theme.pine else theme.clay)
            }
        }
        column.addView(reason)
    }

    private fun situationCard(meta: SituationMeta): LinearLayout {
        val card = theme.clickableCard("situation_${meta.id}") { coordinator.dispatch(TeumSessionAction.OpenSetup(meta.id)) }
        card.layoutParams = theme.lp(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            0f, 0, 0, 0, TeumSpace.M
        )
        card.minimumHeight = theme.dp(152)
        val top = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val badge = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = theme.rounded(theme.pineSoft, TeumRadius.CONTROL)
            addView(theme.icon(meta.iconRes, null, theme.pine, TeumSize.SITUATION_ICON))
        }
        top.addView(badge, theme.lp(theme.dp(52), theme.dp(52)))
        top.addView(theme.text(meta.title, TeumText.HEADING), theme.lp(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, TeumSpace.L, 0, TeumSpace.S, 0
        ))
        top.addView(theme.pill("기본 ${meta.defaultMinutes}분", theme.surfaceAlt, theme.inkMuted))
        card.addView(top)
        card.addView(theme.text(meta.tagline, TeumText.BODY, theme.inkMuted), theme.lp(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            0f, 0, TeumSpace.M, 0, TeumSpace.S
        ))
        card.addView(theme.text("시간 정하기  >", TeumText.LABEL, theme.pine))
        return card
    }

    // ----------------------------------------------------------------- setup

    private fun renderSetup(column: LinearLayout, wide: Boolean) {
        val state = setup ?: return
        addTopBar(column, state.meta.title + " 시간 정하기", "시작 전에 시간표와 차량 설정을 확인합니다.") {
            coordinator.dispatch(TeumSessionAction.Home)
        }
        val now = System.currentTimeMillis()
        val computed = computeSetup(state, now)
        if (wide) {
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            val left = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val right = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            left.addView(timeCard(state, computed))
            left.addView(recommendationCard(state, computed))
            left.addView(featureCard())
            right.addView(timelineCard(state, computed))
            right.addView(startCard(state, computed))
            row.addView(left, theme.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f, 0, 0, TeumSpace.L, 0))
            row.addView(right, theme.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            column.addView(row)
        } else {
            column.addView(timeCard(state, computed))
            column.addView(recommendationCard(state, computed))
            column.addView(timelineCard(state, computed))
            column.addView(featureCard())
            column.addView(startCard(state, computed))
        }
    }

    private fun mutateSetup(block: (SetupState) -> SetupState) {
        setup?.let { coordinator.dispatch(TeumSessionAction.UpdateSetup(block(it))) }
    }

    private fun timeCard(state: SetupState, computed: SetupComputed): LinearLayout {
        val card = theme.card()
        card.addView(theme.sectionLabel("시간"))
        val modeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val durationChip = theme.chip("남은 시간", "mode_duration", state.mode == TimeMode.DURATION) {
            mutateSetup { it.copy(mode = TimeMode.DURATION) }
        }
        val deadlineChip = theme.chip("도착 시각", "mode_deadline", state.mode == TimeMode.DEADLINE) {
            mutateSetup { it.copy(mode = TimeMode.DEADLINE) }
        }
        modeRow.addView(durationChip, theme.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, 0, 0, TeumSpace.XS, 0))
        modeRow.addView(deadlineChip, theme.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, TeumSpace.XS, 0, 0, 0))
        card.addView(modeRow)

        if (state.mode == TimeMode.DURATION) {
            card.addView(stepperRow(
                "${state.minutes}분", "minutes_value", "minutes_minus", "minutes_plus",
                onMinus = { mutateSetup { it.copy(minutes = (it.minutes - 1).coerceAtLeast(1)) } },
                onPlus = { mutateSetup { it.copy(minutes = (it.minutes + 1).coerceAtMost(180)) } }
            ))
            card.addView(theme.text(
                "이동 시간까지 포함한 총 남은 시간입니다.", TeumText.CAPTION, theme.inkMuted
            ))
            val quick = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            val scroll = HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                addView(quick)
            }
            state.meta.quickMinutes.forEach { minutes ->
                quick.addView(theme.chip("${minutes}분", "quick_$minutes", state.minutes == minutes) {
                    mutateSetup { it.copy(minutes = minutes) }
                })
            }
            card.addView(scroll, theme.lp(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                0f, 0, TeumSpace.M, 0, 0
            ))
        } else {
            val arrival = theme.secondaryButton(
                "도착 ${formatClock(computed.deadlineWallMillis)}", "arrival_button"
            ) {
                TimePickerDialog(activity, { _, hour, minute ->
                    mutateSetup {
                        it.copy(arrivalHour = hour, arrivalMinute = minute)
                    }
                }, state.arrivalHour, state.arrivalMinute, true).show()
            }
            card.addView(arrival, theme.lp(
                ViewGroup.LayoutParams.MATCH_PARENT, theme.dp(TeumSize.TOUCH), 0f,
                0, TeumSpace.M, 0, TeumSpace.XS
            ))
            card.addView(theme.text(
                "${formatDateTime(computed.deadlineWallMillis)}까지 · 남은 " +
                    formatDuration(computed.availableSeconds.toLong()),
                TeumText.CAPTION, theme.inkMuted
            ))
        }

        val travelRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        travelRow.addView(theme.text("이동 시간", TeumText.LABEL, theme.inkMuted),
            theme.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        travelRow.addView(theme.quietButton("-", "travel_minus", theme.pine) {
            mutateSetup { it.copy(travelMinutes = (it.travelMinutes - 1).coerceAtLeast(0)) }
        })
        travelRow.addView(theme.text("${state.travelMinutes}분", TeumText.HEADING).apply {
            tag = "travel_value"
            gravity = Gravity.CENTER
            minWidth = theme.dp(56)
        })
        travelRow.addView(theme.quietButton("+", "travel_plus", theme.pine) {
            mutateSetup { it.copy(travelMinutes = (it.travelMinutes + 1).coerceAtMost(60)) }
        })
        card.addView(travelRow, theme.lp(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            0f, TeumSpace.L, 0, 0, 0
        ))
        card.addView(theme.text("이동은 한 번만 계산합니다.", TeumText.CAPTION, theme.inkMuted))
        return card
    }

    private fun stepperRow(
        value: String, valueTag: String, minusTag: String, plusTag: String,
        onMinus: () -> Unit, onPlus: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = theme.lp(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                0f, 0, TeumSpace.M, 0, 0
            )
        }
        val minus = theme.secondaryButton("-", minusTag) { onMinus() }
        minus.textSize = 24f
        val plus = theme.secondaryButton("+", plusTag) { onPlus() }
        plus.textSize = 24f
        row.addView(minus, theme.lp(theme.dp(64), theme.dp(TeumSize.TOUCH)))
        row.addView(theme.text(value, TeumText.TITLE).apply {
            tag = valueTag
            gravity = Gravity.CENTER
        }, theme.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(plus, theme.lp(theme.dp(64), theme.dp(TeumSize.TOUCH)))
        return row
    }

    private fun recommendationCard(state: SetupState, computed: SetupComputed): LinearLayout {
        val card = theme.card()
        card.tag = "recommendation"
        card.addView(theme.sectionLabel("추천 활동"))
        val plan = computed.plan
        if (plan == null) {
            card.addView(theme.text("시작할 수 없습니다", TeumText.HEADING, theme.clay))
            card.addView(theme.text(computed.refusal ?: computed.recommendation.reason, TeumText.BODY))
            return card
        }
        card.addView(theme.text(state.meta.activityTitle, TeumText.HEADING))
        card.addView(theme.text(
            "${plan.label} · ${formatDuration(plan.totalSeconds().toLong())}",
            TeumText.BODY, theme.inkMuted
        ), theme.lp(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            0f, 0, TeumSpace.XS, 0, 0
        ))
        card.addView(theme.text(stepSummary(plan), TeumText.CAPTION, theme.inkMuted), theme.lp(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            0f, 0, TeumSpace.S, 0, TeumSpace.S
        ))
        card.addView(theme.text(computed.recommendation.reason, TeumText.BODY))
        if (computed.recommendation.decision == RecommendDecision.START_NORMAL) {
            val alternative = computed.recommendation.alternative
            if (alternative != null) {
                if (!state.useAlternative) {
                    card.addView(theme.secondaryButton(
                        "짧은 활동으로 바꾸기 (${formatDuration(alternative.totalSeconds().toLong())})",
                        "short_alt_button"
                    ) {
                        mutateSetup {
                            it.copy(useAlternative = true)
                        }
                    }, theme.lp(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        0f, 0, TeumSpace.M, 0, 0
                    ))
                } else {
                    card.addView(theme.quietButton("정상 시간표로 돌아가기", "short_normal_button") {
                        mutateSetup {
                            it.copy(useAlternative = false)
                        }
                    })
                }
            }
        }
        if (computed.schedule?.kind == ScheduleKind.SHORT) {
            card.addView(theme.checkBox(
                "짧은 활동으로 시작하는 데 동의합니다", "short_confirm", state.shortConfirmed
            ) { checked ->
                coordinator.dispatch(TeumSessionAction.ConfirmShort(checked))
            })
        }
        if (repo.isHistoryConsent()) {
            val suggestion = repo.suggestFromHistory(state.meta.id)
            if (suggestion != null) {
                val box = theme.card(TeumSpace.L, theme.surfaceAlt, null)
                box.addView(theme.text("지난 완료 기록", TeumText.CAPTION, theme.inkMuted))
                box.addView(theme.text(
                    suggestion.nextAction.ifBlank { suggestion.note.ifBlank { suggestion.activityTitle } },
                    TeumText.BODY
                ))
                box.addView(theme.text(
                    "이력 추천에 동의한 상태에서만 표시됩니다.", TeumText.MICRO, theme.inkMuted
                ))
                card.addView(box, theme.lp(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    0f, TeumSpace.M, 0, 0, 0
                ))
            }
        }
        return card
    }

    private fun stepSummary(plan: TimePlan): String {
        val content = TeumContentCatalog.get(plan.situationId)
        return plan.stepSeconds.entries.joinToString(" · ") { (id, seconds) ->
            val title = content?.steps?.firstOrNull { it.id == id }?.title
                ?: when (id) {
                    "prepare" -> "준비"
                    "activity" -> "짧은 활동"
                    "wrap" -> "마무리"
                    else -> id
                }
            "$title ${formatDuration(seconds.toLong())}"
        }
    }

    private fun timelineCard(state: SetupState, computed: SetupComputed): LinearLayout {
        val card = theme.card()
        card.addView(theme.sectionLabel("미리보기"))
        val schedule = computed.schedule
        if (schedule == null) {
            card.addView(theme.text("시간표를 만들 수 없습니다.", TeumText.BODY, theme.clay))
            return card
        }
        val segments = buildSegments(
            state.meta.id, schedule.kind, schedule.prepareSeconds,
            schedule.activitySeconds, schedule.finishSeconds
        )
        segments.forEach { segment ->
            val at = formatClock(schedule.startWallMillis + segmentStart(segments, segment.index) * 1000L)
            card.addView(timelineRow(at, segment.title, formatDuration(segment.seconds.toLong())))
        }
        if (schedule.travelSeconds > 0) {
            card.addView(timelineRow(
                formatClock(schedule.readyWallMillis), "복귀 이동", formatDuration(schedule.travelSeconds)
            ))
        }
        card.addView(theme.divider(), theme.lp(
            ViewGroup.LayoutParams.MATCH_PARENT, 1, 0f, 0, TeumSpace.S, 0, TeumSpace.S
        ))
        card.addView(theme.labeledRow(
            "활동 종료 예정", formatClock(schedule.readyWallMillis), "planned_ready", theme.ink
        ))
        card.addView(theme.labeledRow(
            "복귀 예정", formatClock(schedule.plannedReturnWallMillis), "planned_return", theme.pineDeep
        ))
        card.addView(theme.text(
            "예정 시각은 목표이며 실제 완료를 보장하지 않습니다.", TeumText.CAPTION, theme.inkMuted
        ), theme.lp(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            0f, TeumSpace.XS, 0, 0, 0
        ))
        card.tag = "timeline"
        return card
    }

    private fun timelineRow(time: String, title: String, duration: String): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = theme.lp(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                0f, 0, TeumSpace.XXS, 0, TeumSpace.XXS
            )
            addView(theme.text(time, TeumText.LABEL, theme.inkMuted).apply {
                minWidth = theme.dp(64)
            })
            addView(theme.text(title, TeumText.BODY),
                theme.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, TeumSpace.M))
            addView(theme.text(duration, TeumText.CAPTION, theme.inkMuted))
        }

    private fun featureCard(): LinearLayout {
        val card = theme.card()
        card.addView(theme.sectionLabel("차량 설정"))
        addFeatureBlock(card, VehicleFeature.HVAC_TEMPERATURE, "실내 온도", 0.5, 17.0, 27.0) { value ->
            String.format(java.util.Locale.KOREA, "%.1f°C", value)
        }
        card.addView(theme.divider(), theme.lp(
            ViewGroup.LayoutParams.MATCH_PARENT, 1, 0f, 0, TeumSpace.M, 0, TeumSpace.M
        ))
        addFeatureBlock(card, VehicleFeature.AMBIENT_BRIGHTNESS, "실내 조명 밝기", 5.0, 0.0, 100.0) { value ->
            "${value.toInt()}%"
        }
        card.addView(theme.divider(), theme.lp(
            ViewGroup.LayoutParams.MATCH_PARENT, 1, 0f, 0, TeumSpace.M, 0, TeumSpace.M
        ))
        val seatRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        seatRow.addView(theme.text("좌석 위치", TeumText.LABEL), theme.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        seatRow.addView(theme.pill("비활성", theme.claySoft, theme.clay, "seat_pill"))
        card.addView(seatRow)
        card.addView(theme.text(SEAT_REASON, TeumText.CAPTION, theme.inkMuted), theme.lp(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            0f, TeumSpace.XS, 0, 0, 0
        ))
        card.addView(theme.text(
            "지원이 확인된 설정만 앱이 변경합니다. 지원되지 않으면 차량 메뉴에서 직접 조정하세요.",
            TeumText.CAPTION, theme.inkMuted
        ), theme.lp(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            0f, TeumSpace.S, 0, 0, 0
        ))
        return card
    }

    private fun addFeatureBlock(
        parent: LinearLayout, feature: VehicleFeature, title: String,
        step: Double, min: Double, max: Double, format: (Double) -> String
    ) {
        val tag = when (feature) {
            VehicleFeature.HVAC_TEMPERATURE -> "hvac"
            VehicleFeature.AMBIENT_BRIGHTNESS -> "ambient"
            VehicleFeature.SEAT_POSITION -> "seat"
        }
        val status = featureStatus[feature] ?: FeatureUi.Checking
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(theme.text(title, TeumText.LABEL), theme.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(when (status) {
            is FeatureUi.Ready -> if (status.capability.canRead && status.capability.canWrite)
                theme.pill("지원", theme.pineSoft, theme.pineDeep, "${tag}_pill")
            else theme.pill("수동", theme.claySoft, theme.clay, "${tag}_pill")
            is FeatureUi.Manual -> theme.pill("수동", theme.claySoft, theme.clay, "${tag}_pill")
            FeatureUi.Checking -> theme.pill("확인 중", theme.surfaceAlt, theme.inkMuted, "${tag}_pill")
        })
        parent.addView(head)

        when (status) {
            is FeatureUi.Checking -> parent.addView(theme.text(
                "이 기기의 지원 여부를 확인하는 중입니다.", TeumText.CAPTION, theme.inkMuted
            ))
            is FeatureUi.Manual -> {
                parent.addView(theme.text(status.reason, TeumText.CAPTION, theme.inkMuted), theme.lp(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    0f, TeumSpace.XS, 0, 0, 0
                ))
                parent.addView(theme.quietButton("지원 다시 확인", "check_$tag", theme.pine) {
                    refreshFeatureStatus()
                })
            }
            is FeatureUi.Ready -> {
                val capability = status.capability
                if (!capability.canRead || !capability.canWrite) {
                    parent.addView(theme.text(
                        "읽기/쓰기 지원이 확인되지 않아 수동으로 조정해야 합니다. ${capability.reason}",
                        TeumText.CAPTION, theme.inkMuted
                    ))
                    parent.addView(theme.quietButton("지원 다시 확인", "check_$tag", theme.pine) {
                        refreshFeatureStatus()
                    })
                    return
                }
                if (setup?.let { computeSetup(it, System.currentTimeMillis()).schedule?.vehicleControlsAllowed } != true) {
                    parent.addView(theme.text(
                        "짧은 활동이나 시간표가 없는 경우 차량 설정은 직접 조정하세요.",
                        TeumText.CAPTION, theme.inkMuted
                    ))
                    return
                }
                parent.addView(theme.text(
                    "현재 ${format(status.current)} · 범위 ${format(capability.min)}–${format(capability.max)}",
                    TeumText.CAPTION, theme.inkMuted
                ), theme.lp(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    0f, TeumSpace.XS, 0, 0, 0
                ))
                val target = featureTargets[feature] ?: status.current
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = theme.lp(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        0f, TeumSpace.XS, 0, TeumSpace.XS, 0
                    )
                }
                row.addView(theme.quietButton("-", "${tag}_minus", theme.pine) {
                    val next = (target - step).coerceAtLeast(min)
                    previewFeature(feature, next)
                })
                row.addView(theme.text(format(target), TeumText.HEADING).apply {
                    this.tag = "${tag}_value"
                    gravity = Gravity.CENTER
                }, theme.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(theme.quietButton("+", "${tag}_plus", theme.pine) {
                    val next = (target + step).coerceAtMost(max)
                    previewFeature(feature, next)
                })
                parent.addView(row)
                val command = featureCommands[feature]
                if (command == null || command.stage == CommandStage.FAILED ||
                    command.stage == CommandStage.MANUAL || command.stage == CommandStage.UNKNOWN
                ) {
                    parent.addView(theme.secondaryButton("변경 미리보기", "${tag}_preview") {
                        previewFeature(feature, featureTargets[feature] ?: status.current)
                    }, theme.lp(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        0f, 0, TeumSpace.S, 0, 0
                    ))
                    if (command != null) {
                        parent.addView(theme.text(
                            "변경을 확인할 수 없습니다: ${command.reason}", TeumText.CAPTION, theme.clay
                        ))
                    }
                } else when (command.stage) {
                    CommandStage.PREVIEW -> {
                        val initial = command.initial ?: status.current
                        parent.addView(theme.text(
                            "현재 ${format(initial)} → 시작 시 ${format(command.target)}",
                            TeumText.BODY
                        ), theme.lp(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                            0f, TeumSpace.S, 0, 0, 0
                        ))
                        parent.addView(theme.checkBox(
                            "이 변경을 시작과 함께 적용하는 데 동의합니다", "${tag}_consent",
                            applyConsent[feature] == true
                        ) { checked -> coordinator.dispatch(TeumSessionAction.ApplyConsent(feature, checked)) })
                    }
                    CommandStage.QUEUED, CommandStage.REQUESTED -> parent.addView(theme.text(
                        "요청 처리 중 · 실제 적용은 읽기 확인 전입니다.", TeumText.CAPTION, theme.inkMuted
                    ))
                    CommandStage.READBACK -> parent.addView(theme.text(
                        "읽기 확인: ${format(command.readback ?: command.target)}",
                        TeumText.CAPTION, theme.pineDeep
                    ))
                    else -> Unit
                }
            }
        }
    }

    private fun startCard(state: SetupState, computed: SetupComputed): LinearLayout {
        val card = theme.card()
        card.addView(theme.sectionLabel("시작"))
        val schedule = computed.schedule
        if (schedule == null) {
            card.addView(theme.text("시작할 수 없습니다", TeumText.HEADING, theme.clay))
            card.addView(theme.text(computed.refusal ?: "시간을 다시 확인하세요.", TeumText.BODY))
            return card
        }
        card.addView(theme.text(
            "예정 복귀 ${formatClock(schedule.plannedReturnWallMillis)}", TeumText.TITLE
        ))
        card.addView(theme.text(
            "가이드 종료 ${formatClock(schedule.readyWallMillis)} · 실제 완료는 복원 확인 뒤 기록합니다.",
            TeumText.CAPTION, theme.inkMuted
        ), theme.lp(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            0f, TeumSpace.XS, 0, 0, 0
        ))
        val blocked = startBlockedReason(computed, state)
        val start = theme.primaryButton("${state.meta.title} 시작", "start_button") { coordinator.dispatch(TeumSessionAction.Start) }
        start.minHeight = theme.dp(TeumSize.PRIMARY)
        start.minimumHeight = theme.dp(TeumSize.PRIMARY)
        theme.setEnabledLook(start, blocked == null)
        card.addView(start, theme.lp(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.dp(TeumSize.PRIMARY), 0f,
            0, TeumSpace.M, 0, TeumSpace.S
        ))
        val helper = theme.text(blocked ?: "주차 P와 제한 없음이 확인되었습니다.", TeumText.CAPTION,
            if (blocked == null) theme.pine else theme.clay).apply { tag = "start_helper" }
        card.addView(helper)
        var lastHelper: String? = null
        liveUpdaters += {
            val latest = computeSetup(state, System.currentTimeMillis())
            val reason = startBlockedReason(latest, state)
            if (reason != lastHelper) {
                lastHelper = reason
                theme.setEnabledLook(start, reason == null)
                helper.text = reason ?: "주차 P와 제한 없음이 확인되었습니다."
                helper.setTextColor(if (reason == null) theme.pine else theme.clay)
            }
        }
        return card
    }

    // --------------------------------------------------------------- session

    private fun renderSession(column: LinearLayout) {
        val session = active ?: return
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(theme.pill("활동 중", theme.pineSoft, theme.pineDeep, "session_state"))
        header.addView(View(activity), theme.lp(0, 1, 1f))
        header.addView(safetyPill("session_safety_pill"))
        header.addView(theme.quietButton(
            if (soundEnabled) "소리 끄기" else "소리 켜기", "session_sound"
        ) {
            coordinator.dispatch(TeumSessionAction.SetSound(!soundEnabled))
        })
        column.addView(header, theme.lp(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            0f, 0, 0, 0, TeumSpace.L
        ))
        column.addView(theme.text(session.meta.title, TeumText.TITLE))
        val remaining = theme.text("00:00", TeumText.DISPLAY).apply { tag = "session_remaining" }
        column.addView(remaining, theme.lp(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            0f, 0, TeumSpace.XS, 0, 0
        ))
        val phase = theme.text("", TeumText.LABEL, theme.inkMuted).apply { tag = "session_phase" }
        column.addView(phase)
        val progressTrack = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = theme.rounded(theme.line, TeumRadius.PILL)
            clipToOutline = true
            tag = "session_progress"
        }
        val progressFill = View(activity).apply { setBackgroundColor(theme.pine) }
        val progressRest = View(activity)
        progressTrack.addView(progressFill, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0f))
        progressTrack.addView(progressRest, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        column.addView(progressTrack, theme.lp(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.dp(10), 0f,
            0, TeumSpace.S, 0, TeumSpace.XL
        ))

        val step = session.stepIndex
        val segment = session.segments.getOrNull(step)
        val stepCard = theme.card()
        stepCard.addView(theme.text(
            "단계 ${step + 1} / ${session.segments.size}", TeumText.CAPTION, theme.inkMuted
        ).apply { tag = "session_step_index" })
        stepCard.addView(theme.text(segment?.title ?: "마무리", TeumText.HEADING).apply {
            tag = "session_step_title"
        })
        stepCard.addView(theme.text(segment?.prompt ?: "", TeumText.BODY).apply {
            tag = "session_prompt"
        }, theme.lp(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            0f, TeumSpace.S, 0, 0, 0
        ))
        stepCard.addView(theme.text(
            segment?.let { formatDuration(it.seconds.toLong()) } ?: "",
            TeumText.CAPTION, theme.inkMuted
        ))
        column.addView(stepCard)

        val railScroll = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false }
        val railRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        session.segments.forEach { item ->
            val selected = item.index == step
            railRow.addView(theme.pill(
                item.title,
                if (selected) theme.pineSoft else theme.surfaceAlt,
                if (selected) theme.pineDeep else theme.inkMuted
            ), theme.lp(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                0f, 0, 0, TeumSpace.S, 0
            ))
        }
        railScroll.addView(railRow)
        column.addView(railScroll, theme.lp(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            0f, 0, TeumSpace.S, 0, TeumSpace.L
        ))

        if (session.meta.id == SituationIds.LUNCH_BREAK && !session.restricted) {
            val wakeRelevant = session.engineState == SessionState.FINISHING ||
                segment?.stepId == "wake" || segment?.stepId == "wrap" ||
                session.elapsedSeconds >= session.timedTotalSeconds - 180
            if (wakeRelevant) {
                val wake = theme.card(
                    TeumSpace.L, if (awakeAcknowledged) theme.pineSoft else theme.claySoft, null
                )
                wake.addView(theme.text(
                    if (awakeAcknowledged) "깨어 있음을 확인했습니다" else "깨우기 안내",
                    TeumText.HEADING, if (awakeAcknowledged) theme.pineDeep else theme.clay
                ))
                wake.addView(theme.text(
                    if (awakeAcknowledged) {
                        "복원은 다음 화면에서 따로 동의합니다. 자동으로 좌석을 움직이지 않습니다."
                    } else {
                        "천천히 눈을 뜨고, 깨어 있으면 확인을 눌러 주세요. 응답이 없으면 좌석과 설정을 자동으로 복원하지 않습니다."
                    },
                    TeumText.BODY
                ), theme.lp(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    0f, TeumSpace.S, 0, 0, 0
                ))
                wake.addView(theme.text(
                    "알림 ${cueCount}회 · 화면 안내 유지", TeumText.CAPTION, theme.inkMuted
                ).apply { tag = "session_cue_count" })
                if (!awakeAcknowledged) {
                    wake.addView(theme.primaryButton("깨어 있음 확인", "ack_wake_button") {
                        acknowledgeWake()
                    }, theme.lp(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        0f, TeumSpace.S, 0, 0, 0
                    ))
                }
                column.addView(wake)
            }
        }

        val statusCard = theme.card(TeumSpace.L, theme.surfaceAlt, null)
        val safetyValue = theme.text(safetyState().first, TeumText.LABEL, theme.pineDeep)
        statusCard.addView(theme.labeledRow("주차·제한", safetyState().first, "session_safety_value"))
        statusCard.addView(theme.labeledRow(
            "설정 변경", if (applied.isEmpty()) "없음" else "${applied.size}개 확인됨", "session_change_count"
        ))
        val ttsValue = theme.text("", TeumText.CAPTION, theme.inkMuted).apply { tag = "session_tts_status" }
        statusCard.addView(ttsValue)
        column.addView(statusCard)
        liveUpdaters += {
            statusCard.findViewWithTag<TextView>("session_safety_value")?.text = safetyState().first
            statusCard.findViewWithTag<TextView>("session_change_count")?.text =
                if (applied.isEmpty()) "없음" else "${applied.size}개 확인됨"
            ttsValue.text = when {
                cue == null -> "소리 안내 사용 불가 · 화면 안내"
                cue?.isReady() == true -> "음성 안내 사용 가능"
                cue?.status() == TeumCuePlayer.Status.CHECKING -> "음성 엔진 확인 중"
                else -> "음성 미지원 · 알림음 시도 및 화면 안내"
            }
        }

        val finish = theme.dangerButton("지금 마무리", "finish_now_button") { finishNow() }
        finish.minHeight = theme.dp(TeumSize.PRIMARY)
        finish.minimumHeight = theme.dp(TeumSize.PRIMARY)
        column.addView(finish, theme.lp(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.dp(TeumSize.PRIMARY), 0f,
            0, TeumSpace.M, 0, TeumSpace.S
        ))
        column.addView(theme.text(
            "지금 마무리하면 화면 안내와 알림이 멈추고 복원 확인으로 이동합니다.",
            TeumText.CAPTION, theme.inkMuted
        ))
        liveUpdaters += {
            val current = active
            if (current != null) {
                val total = current.timedTotalSeconds
                val remain = (total - displayElapsed(current)).coerceAtLeast(0)
                remaining.text = formatMmSs(remain)
                phase.text = when (current.engineState) {
                    SessionState.PREPARING -> "준비 단계"
                    SessionState.ACTIVE -> "활동 단계"
                    SessionState.FINISHING -> "마무리 · 복원 준비"
                    SessionState.AWAITING_RESTORE -> "복원 대기"
                    else -> current.engineState.name
                }
                val fraction = if (total <= 0) 1f
                else (current.elapsedSeconds.toFloat() / total).coerceIn(0f, 1f)
                (progressFill.layoutParams as LinearLayout.LayoutParams).weight = fraction
                (progressRest.layoutParams as LinearLayout.LayoutParams).weight = 1f - fraction
                progressTrack.requestLayout()
            }
        }
    }

    // --------------------------------------------------------------- restore

    private fun renderRestore(column: LinearLayout) {
        val session = active ?: return
        addTopBar(
            column,
            if (session.restricted) "제어 제한" else "복원 확인",
            if (session.restricted) {
                "안전 중단은 완료로 기록되지 않습니다."
            } else {
                "차량 설정과 주변을 확인한 뒤 기록합니다."
            }
        )
        if (session.restricted) {
            val notice = theme.card(TeumSpace.L, theme.claySoft, null)
            notice.addView(theme.text("제어를 중단했습니다", TeumText.HEADING, theme.clay))
            notice.addView(theme.text(
                "주차 또는 주행 제한 신뢰를 잃어 새 설정과 복원 명령을 보내지 않았습니다. 자동 복원은 없습니다.",
                TeumText.BODY
            ))
            column.addView(notice)
            val record = theme.primaryButton("안전 중단으로 기록", "restricted_record_button") {
                recordRestricted()
            }
            record.minHeight = theme.dp(TeumSize.PRIMARY)
            record.minimumHeight = theme.dp(TeumSize.PRIMARY)
            column.addView(record, theme.lp(
                ViewGroup.LayoutParams.MATCH_PARENT, theme.dp(TeumSize.PRIMARY), 0f,
                0, TeumSpace.M, 0, 0
            ))
            return
        }
        val summary = theme.card(TeumSpace.L)
        summary.addView(theme.labeledRow("상황", session.meta.title))
        summary.addView(theme.labeledRow(
            "실제 진행", formatDuration(session.finalElapsedSeconds), "actual_elapsed"
        ))
        summary.addView(theme.labeledRow(
            "가이드 종료", formatClock(session.schedule.readyWallMillis), "guide_end"
        ))
        summary.addView(theme.labeledRow(
            "복귀 예정", formatClock(session.schedule.plannedReturnWallMillis), "target_return"
        ))
        summary.addView(theme.text(
            "복원 완료 시각은 확인을 마친 뒤 실제 시각으로 기록됩니다.", TeumText.CAPTION, theme.inkMuted
        ))
        column.addView(summary)

        val settingsCard = theme.card()
        settingsCard.addView(theme.sectionLabel("변경한 차량 설정"))
        if (applied.isEmpty()) {
            settingsCard.addView(theme.text("앱이 변경한 차량 설정이 없습니다.", TeumText.BODY))
        } else {
            applied.forEach { (feature, change) ->
                settingsCard.addView(theme.text(
                    featureLabel(feature) + "  " + formatSetting(feature, change.initial) +
                        " → " + formatSetting(feature, change.target),
                    TeumText.BODY
                ))
                if (change.restored) {
                    settingsCard.addView(theme.pill("복원 확인", theme.pineSoft, theme.pineDeep))
                } else {
                    when (restoreCommands[feature]?.stage) {
                        CommandStage.PREVIEW -> {
                            settingsCard.addView(theme.text(
                                "초기값 ${formatSetting(feature, change.initial)}로 복원",
                                TeumText.CAPTION, theme.inkMuted
                            ))
                            settingsCard.addView(theme.checkBox(
                                "앱 외 변경 여부를 알 수 없음을 확인했습니다",
                                "restore_origin_${feature.name}", restoreOriginAck[feature] == true
                            ) { checked -> coordinator.dispatch(TeumSessionAction.RestoreOriginAck(feature, checked)) })
                            val button = theme.secondaryButton(
                                "복원 확인", "restore_confirm_${feature.name}"
                            ) { coordinator.dispatch(TeumSessionAction.ConfirmRestoreFeature(feature)) }
                            settingsCard.addView(button)
                            liveUpdaters += { theme.setEnabledLook(button, restoreFeatureAllowed()) }
                        }
                        CommandStage.QUEUED, CommandStage.REQUESTED -> settingsCard.addView(theme.text(
                            "복원 요청 처리 중 · 읽기 확인 전입니다.", TeumText.CAPTION, theme.inkMuted
                        ))
                        CommandStage.FAILED, CommandStage.UNKNOWN, CommandStage.MANUAL ->
                            settingsCard.addView(theme.text(
                                "복원하지 않았습니다: ${restoreCommands[feature]?.reason}",
                                TeumText.CAPTION, theme.clay
                            ))
                        else -> {
                            val button = theme.secondaryButton(
                                "복원 미리보기", "restore_preview_${feature.name}"
                            ) { coordinator.dispatch(TeumSessionAction.PreviewRestore(feature)) }
                            settingsCard.addView(button)
                            liveUpdaters += { theme.setEnabledLook(button, restoreFeatureAllowed()) }
                        }
                    }
                }
            }
        }
        column.addView(settingsCard)

        if (session.meta.id == SituationIds.LUNCH_BREAK) {
            val gate = theme.card(
                TeumSpace.L, if (awakeAcknowledged) theme.pineSoft else theme.claySoft, null
            )
            gate.addView(theme.text(
                if (awakeAcknowledged) "깨어 있음을 확인했습니다" else "깨어 있음 확인이 필요합니다",
                TeumText.HEADING, if (awakeAcknowledged) theme.pineDeep else theme.clay
            ))
            gate.addView(theme.text(
                if (awakeAcknowledged) "복원은 아래에서 별도로 동의합니다."
                else "응답이 없으면 좌석과 설정을 자동으로 복원하지 않습니다. 확인 후에 복원 동의를 받습니다.",
                TeumText.BODY
            ))
            if (!awakeAcknowledged) {
                gate.addView(theme.primaryButton("깨어 있음 확인", "ack_wake_restore_button") {
                    acknowledgeWake()
                }, theme.lp(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    0f, TeumSpace.S, 0, 0, 0
                ))
            }
            column.addView(gate)
        }

        val consentCard = theme.card()
        consentCard.addView(theme.sectionLabel("복원 동의"))
        consentCard.addView(theme.text(
            "이 단계는 별도의 복원 동의입니다. 응답이 없으면 아무것도 보내지 않습니다.",
            TeumText.BODY
        ))
        consentCard.addView(theme.checkBox(
            "주차 상태와 주변을 확인했고 복원을 진행하는 데 동의합니다", "restore_consent", restoreConsent
        ) { checked ->
            coordinator.dispatch(TeumSessionAction.RestoreConsent(checked))
        })
        if (applied.values.any { !it.restored }) {
            consentCard.addView(theme.checkBox(
                "남은 설정은 차량 메뉴에서 직접 확인하겠습니다", "manual_restore_ack", manualRestoreAck
            ) { checked ->
                coordinator.dispatch(TeumSessionAction.ManualRestoreAck(checked))
            })
        }
        val confirm = theme.primaryButton("복원 확인하고 기록", "restore_confirm_button") {
            coordinator.dispatch(TeumSessionAction.ConfirmRestore)
        }
        confirm.minHeight = theme.dp(TeumSize.PRIMARY)
        confirm.minimumHeight = theme.dp(TeumSize.PRIMARY)
        consentCard.addView(confirm, theme.lp(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.dp(TeumSize.PRIMARY), 0f,
            0, TeumSpace.M, 0, TeumSpace.S
        ))
        val helper = theme.text("", TeumText.CAPTION, theme.inkMuted).apply { tag = "restore_helper" }
        consentCard.addView(helper)
        column.addView(consentCard)

        liveUpdaters += {
            val blocked = safety.blockedReason(SystemClock.elapsedRealtime())
            val pending = restoreCommands.values.any {
                it.stage in setOf(CommandStage.PREVIEW, CommandStage.QUEUED, CommandStage.REQUESTED)
            }
            val wakeOk = session.meta.id != SituationIds.LUNCH_BREAK || awakeAcknowledged
            val manualOk = !applied.values.any { !it.restored } || manualRestoreAck
            val enabled = restoreConsent && blocked == null && wakeOk && manualOk && !pending
            theme.setEnabledLook(confirm, enabled)
            helper.text = when {
                blocked != null -> "복원을 확인할 수 없습니다: ${safetyReason(blocked)}"
                !wakeOk -> "깨어 있음 확인 후 복원 동의를 진행하세요."
                pending -> "복원 요청의 읽기 확인을 기다리세요."
                !manualOk -> "남은 설정을 직접 확인했다는 항목에 동의하세요."
                !restoreConsent -> "복원 동의가 필요합니다. 응답이 없으면 아무것도 보내지 않습니다."
                else -> "복원 확인 후 실제 진행과 시간으로 기록합니다."
            }
        }
    }

    private fun stageLabel(stage: String): String = when (stage) {
        "PREPARING" -> "준비"
        "ACTIVE" -> "활동"
        "FINISHING" -> "마무리"
        "AWAITING_RESTORE" -> "복원 대기"
        "RESTORING" -> "복원 중"
        "COMPLETED" -> "완료"
        "RESTRICTED" -> "제한"
        "INTERRUPTED", "FINISHED" -> "중단"
        else -> stage
    }

    private fun featureLabel(feature: VehicleFeature): String = when (feature) {
        VehicleFeature.HVAC_TEMPERATURE -> "실내 온도"
        VehicleFeature.AMBIENT_BRIGHTNESS -> "실내 조명"
        VehicleFeature.SEAT_POSITION -> "좌석"
    }

    private fun formatSetting(feature: VehicleFeature, value: Double): String = when (feature) {
        VehicleFeature.HVAC_TEMPERATURE -> String.format(java.util.Locale.KOREA, "%.1f°C", value)
        VehicleFeature.AMBIENT_BRIGHTNESS -> "${value.toInt()}%"
        VehicleFeature.SEAT_POSITION -> value.toInt().toString()
    }

    // ---------------------------------------------------------------- record

    private fun bindRecordText(view: EditText, values: (String) -> RecordEditable) {
        view.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                coordinator.dispatch(TeumSessionAction.EditRecord(values(text?.toString().orEmpty())))
            }
            override fun afterTextChanged(text: android.text.Editable?) = Unit
        })
    }

    private fun renderRecord(column: LinearLayout) {
        val current = draft ?: return
        addTopBar(column, "기록", "실제 진행과 시간만 기록합니다.") { confirmDiscardRecord() }
        val outcome = draftOutcome(current)
        val verdict = theme.text(
            outcomeLabel(outcome), TeumText.HEADING,
            if (outcome == ActivityOutcome.COMPLETED) theme.pineDeep else theme.clay
        ).apply { tag = "record_verdict" }
        val verdictCard = theme.card(
            TeumSpace.L,
            if (outcome == ActivityOutcome.COMPLETED) theme.pineSoft else theme.claySoft,
            null
        )
        verdictCard.addView(verdict)
        verdictCard.addView(theme.text(
            when (outcome) {
                ActivityOutcome.COMPLETED ->
                    "모든 시간 단계가 실제로 지났습니다."
                ActivityOutcome.SAFETY_INTERRUPTED -> "안전 중단은 완료로 기록되지 않습니다."
                else -> "시간이 남은 채 종료되어 중도 종료로 기록합니다."
            },
            TeumText.BODY
        ))
        column.addView(verdictCard)

        val facts = theme.card(TeumSpace.L)
        facts.addView(theme.labeledRow("상황", metaOf(current.situationId)?.title ?: current.situationId))
        facts.addView(theme.labeledRow(
            "실제 이용 시간", formatDuration(current.elapsedSeconds), "record_elapsed"
        ))
        facts.addView(theme.labeledRow(
            "복귀 예정", current.plannedReturnWallMillis?.let { formatClock(it) } ?: "-"
        ))
        facts.addView(theme.labeledRow(
            "기록 시각", current.actualFinishWallMillis?.let { formatClockSeconds(it) } ?: "-"
        ))
        facts.addView(theme.text(
            if (current.restoredBeforeSave) "복원 확인 뒤 기록 시각으로 남깁니다."
            else "복원 확인 없이 마지막으로 확인된 진행만 남깁니다.",
            TeumText.CAPTION, theme.inkMuted
        ))
        column.addView(facts)

        val steps = theme.card()
        steps.addView(theme.sectionLabel("단계 진행"))
        val actual = current.actualCompletedIndexes().toSet()
        current.segments.forEach { segment ->
            val done = segment.index in actual
            steps.addView(theme.labeledRow(
                "${segment.index + 1}. ${segment.title}",
                if (done) "완료" else "남음",
                null,
                if (done) theme.pine else theme.clay
            ))
        }
        if (current.situationId == SituationIds.LUNCH_BREAK) {
            steps.addView(theme.text(
                "점심의 외부 복귀 이동은 기록 단계에서 제외됩니다.", TeumText.CAPTION, theme.inkMuted
            ))
        }
        column.addView(steps)

        val memo = theme.card()
        val meeting = current.situationId == SituationIds.MEETING
        memo.addView(theme.sectionLabel(if (meeting) "핵심 문장" else "메모"))
        memo.addView(theme.text(
            if (meeting) "미팅에서 전할 한 문장을 직접 적어 주세요."
            else "남기고 싶은 내용을 직접 적어 주세요. 비워 두어도 됩니다.",
            TeumText.CAPTION, theme.inkMuted
        ))
        val note = theme.editText(
            if (meeting) "핵심 문장" else "메모 (선택)", "record_note",
            singleLine = meeting, minLines = 2
        )
        note.setText(editable.note)
        bindRecordText(note) { editable.copy(note = it) }
        memo.addView(note, theme.lp(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            0f, TeumSpace.S, 0, 0, 0
        ))
        memo.addView(theme.text("다음 행동 (선택)", TeumText.LABEL, theme.inkMuted), theme.lp(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            0f, TeumSpace.M, 0, 0, 0
        ))
        val next = theme.editText("직접 적어 주세요", "record_next", singleLine = true)
        next.setText(editable.nextAction)
        bindRecordText(next) { editable.copy(nextAction = it) }
        memo.addView(next, theme.lp(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            0f, TeumSpace.XS, 0, 0, 0
        ))
        val recall = theme.checkBox(
            "다음 출근 때 이 기록을 다시 볼게요", "record_recall", editable.recall
        ) { checked -> coordinator.dispatch(TeumSessionAction.EditRecord(editable.copy(recall = checked))) }
        memo.addView(recall)
        memo.addView(theme.text(
            "다음 행동을 적고 직접 선택할 때만 다시 보기 목록에 남습니다.",
            TeumText.CAPTION, theme.inkMuted
        ))
        column.addView(memo)

        val save = theme.primaryButton("기록 저장", "record_save_button") { coordinator.dispatch(TeumSessionAction.SaveRecord(editable)) }
        save.minHeight = theme.dp(TeumSize.PRIMARY)
        save.minimumHeight = theme.dp(TeumSize.PRIMARY)
        column.addView(save, theme.lp(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.dp(TeumSize.PRIMARY), 0f,
            0, TeumSpace.S, 0, 0
        ))
    }

    private fun outcomeLabel(outcome: String): String = when (outcome) {
        ActivityOutcome.COMPLETED -> "완료로 기록"
        ActivityOutcome.SAFETY_INTERRUPTED -> "안전 중단으로 기록"
        else -> "중도 종료로 기록"
    }

    private fun outcomePillLabel(outcome: String): String = when (outcome) {
        ActivityOutcome.COMPLETED -> "완료"
        ActivityOutcome.SAFETY_INTERRUPTED -> "안전 중단"
        else -> "중도 종료"
    }

    // --------------------------------------------------------------- history

    private fun renderHistory(column: LinearLayout) {
        addTopBar(column, "기록", "기기 안에만 저장됩니다.") {
            coordinator.dispatch(TeumSessionAction.Home)
        }
        val consentCard = theme.card(TeumSpace.L)
        consentCard.addView(theme.text("이력 사용", TeumText.HEADING))
        consentCard.addView(theme.text(
            "완료한 기록에서 다음 행동을 제안할 때만 사용합니다. 기본은 꺼짐입니다.",
            TeumText.CAPTION, theme.inkMuted
        ))
        consentCard.addView(theme.switchRow(
            "이전 기록으로 다음 행동 제안", "history_consent", repo.isHistoryConsent()
        ) { checked ->
            repo.setHistoryConsent(checked)
            render()
        })
        column.addView(consentCard)

        if (recordStore.quarantinedRaw() != null) {
            val card = theme.card(TeumSpace.L, theme.claySoft, null)
            card.addView(theme.text("손상된 저장 데이터가 격리되어 있습니다", TeumText.HEADING, theme.clay))
            card.addView(theme.text(
                "자동으로 삭제하지 않았습니다. 전체 삭제에서 함께 지울 수 있습니다.",
                TeumText.BODY
            ))
            card.addView(theme.secondaryButton("격리 데이터 삭제", "quarantine_clear") {
                AlertDialog.Builder(activity)
                    .setTitle("격리된 데이터를 삭제할까요?")
                    .setMessage("손상된 원본 데이터를 복구할 수 없게 삭제합니다.")
                    .setPositiveButton("삭제") { _, _ ->
                        recordStore.clearQuarantine()
                        render()
                    }
                    .setNegativeButton("취소", null)
                    .show()
            })
            column.addView(card)
        }

        val records = repo.listAll()
        column.addView(theme.sectionLabel("저장된 기록 ${records.size}"))
        if (records.isEmpty()) {
            column.addView(theme.text(
                "아직 기록이 없습니다. 활동을 마치면 여기에 남습니다.", TeumText.BODY, theme.inkMuted
            ))
        }
        records.forEach { record ->
            val meta = metaOf(record.situationId)
            val card = theme.card(TeumSpace.L)
            val head = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(theme.text(meta?.title ?: record.situationId, TeumText.HEADING),
                theme.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(theme.pill(
                outcomePillLabel(record.outcome),
                if (record.outcome == ActivityOutcome.COMPLETED) theme.pineSoft else theme.claySoft,
                if (record.outcome == ActivityOutcome.COMPLETED) theme.pineDeep else theme.clay,
                "history_outcome"
            ))
            card.addView(head)
            card.addView(theme.text(
                "${formatDateTime(record.createdAtEpochMs)} · " +
                    "${record.completedStepCount()}/${record.totalSteps} 단계 · " +
                    "실제 ${formatDuration(record.elapsedSeconds)}",
                TeumText.CAPTION, theme.inkMuted
            ))
            if (record.note.isNotBlank()) {
                card.addView(theme.text(record.note, TeumText.BODY), theme.lp(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    0f, TeumSpace.S, 0, 0, 0
                ))
            }
            if (record.nextAction.isNotBlank()) {
                card.addView(theme.text("다음 행동: ${record.nextAction}", TeumText.BODY, theme.pineDeep))
            }
            if (record.recallAccepted) {
                card.addView(theme.pill("다음 출근 때 다시 보기", theme.skySoft, theme.sky))
            }
            val actions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(theme.quietButton("수정", "edit_${record.id}", theme.pine) { editRecord(record) })
            actions.addView(theme.quietButton("삭제", "delete_${record.id}", theme.clay) { deleteRecord(record) })
            card.addView(actions)
            column.addView(card)
        }
        column.addView(theme.quietButton("모든 기록 삭제", "history_clear_all", theme.clay) {
            confirmClearAll()
        })
    }

    // -------------------------------------------------------------- settings

    private fun renderSettings(column: LinearLayout) {
        addTopBar(column, "설정 · 지원 기능", "이 기기에서 실제로 확인된 것만 표시합니다.") {
            coordinator.dispatch(TeumSessionAction.Home)
        }
        val safetyCard = theme.card()
        safetyCard.addView(theme.sectionLabel("차량 안전 상태"))
        safetyCard.addView(theme.labeledRow(
            "주차",
            when (safety.parking.state) {
                Parking.PARKED -> "P 확인"
                Parking.NOT_PARKED -> "P 아님"
                Parking.UNKNOWN -> "확인 불가"
            },
            "settings_parking"
        ))
        safetyCard.addView(theme.labeledRow(
            "주행 제한",
            when {
                safety.ux.requiresDistractionOptimization == null -> "확인 불가"
                safety.ux.requiresDistractionOptimization == true -> "제한 있음"
                else -> "제한 없음"
            },
            "settings_ux"
        ))
        safetyCard.addView(theme.labeledRow(
            "서비스", if (safety.connected) "연결됨" else "연결 안 됨", "settings_connected"
        ))
        val reasonView = theme.text("", TeumText.CAPTION, theme.inkMuted).apply {
            tag = "settings_safety_reason"
        }
        safetyCard.addView(reasonView)
        liveUpdaters += {
            val blocked = safety.blockedReason(SystemClock.elapsedRealtime())
            reasonView.text = if (blocked == null) "주차 P와 제한 없음이 신선하게 확인되었습니다."
            else safetyReason(blocked)
            reasonView.setTextColor(if (blocked == null) theme.pine else theme.clay)
        }
        column.addView(safetyCard)

        val features = theme.card()
        features.addView(theme.sectionLabel("지원 기능과 권한"))
        listOf(
            VehicleFeature.HVAC_TEMPERATURE to "실내 온도",
            VehicleFeature.AMBIENT_BRIGHTNESS to "실내 조명",
            VehicleFeature.SEAT_POSITION to "좌석 위치"
        ).forEach { (feature, title) ->
            val status = if (feature == VehicleFeature.SEAT_POSITION) {
                FeatureUi.Manual(SEAT_REASON)
            } else {
                featureStatus[feature] ?: FeatureUi.Checking
            }
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(theme.text(title, TeumText.LABEL), theme.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(theme.pill(
                when (status) {
                    is FeatureUi.Ready -> if (status.capability.canRead && status.capability.canWrite) "지원" else "수동"
                    is FeatureUi.Manual -> "수동"
                    FeatureUi.Checking -> "확인 중"
                },
                when (status) {
                    is FeatureUi.Ready -> if (status.capability.canRead && status.capability.canWrite) theme.pineSoft else theme.claySoft
                    is FeatureUi.Manual -> theme.claySoft
                    FeatureUi.Checking -> theme.surfaceAlt
                },
                when (status) {
                    is FeatureUi.Ready -> if (status.capability.canRead && status.capability.canWrite) theme.pineDeep else theme.clay
                    is FeatureUi.Manual -> theme.clay
                    FeatureUi.Checking -> theme.inkMuted
                }
            ))
            features.addView(row)
            when (status) {
                is FeatureUi.Ready -> features.addView(theme.text(
                    "현재 ${formatSetting(feature, status.current)} · " +
                        "범위 ${formatSetting(feature, status.capability.min)}–" +
                        formatSetting(feature, status.capability.max),
                    TeumText.CAPTION, theme.inkMuted
                ))
                is FeatureUi.Manual -> features.addView(theme.text(status.reason, TeumText.CAPTION, theme.inkMuted))
                FeatureUi.Checking -> features.addView(theme.text(
                    "확인 중입니다.", TeumText.CAPTION, theme.inkMuted
                ))
            }
            val readPermission = when (feature) {
                VehicleFeature.HVAC_TEMPERATURE -> "pleos.car.permission.CAR_CLIMATE"
                VehicleFeature.AMBIENT_BRIGHTNESS -> "pleos.car.permission.CAR_LIGHTS"
                VehicleFeature.SEAT_POSITION -> "pleos.car.permission.CAR_SEATS"
            }
            val controlPermission = when (feature) {
                VehicleFeature.HVAC_TEMPERATURE -> "pleos.car.permission.CONTROL_CAR_CLIMATE"
                VehicleFeature.AMBIENT_BRIGHTNESS -> "pleos.car.permission.CONTROL_CAR_LIGHTS"
                VehicleFeature.SEAT_POSITION -> "pleos.car.permission.CONTROL_CAR_SEATS"
            }
            val readGranted = activity.checkSelfPermission(readPermission) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            val controlGranted = activity.checkSelfPermission(controlPermission) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            features.addView(theme.text(
                "읽기 권한 ${if (readGranted) "있음" else "없음"} · " +
                    "제어 권한 ${if (controlGranted) "있음" else "없음"}",
                TeumText.MICRO, theme.inkMuted
            ))
            if (feature != VehicleFeature.SEAT_POSITION) {
                val tag = if (feature == VehicleFeature.HVAC_TEMPERATURE) "check_hvac" else "check_ambient"
                features.addView(theme.quietButton("지원 확인", tag, theme.pine) { refreshFeatureStatus() })
            }
            features.addView(theme.divider(), theme.lp(
                ViewGroup.LayoutParams.MATCH_PARENT, 1, 0f, 0, TeumSpace.S, 0, TeumSpace.S
            ))
        }
        column.addView(features)

        val manual = theme.card(TeumSpace.L, theme.surfaceAlt, null)
        manual.addView(theme.text("수동 설정 안내", TeumText.HEADING))
        manual.addView(theme.text(
            "이 기기에서는 온도와 조명 값을 읽거나 바꿀 수 없습니다. 차량 메뉴에서 직접 조정한 뒤 앱으로 돌아오세요. 앱은 실제 읽기 확인 없이 적용됐다고 표시하지 않습니다.",
            TeumText.BODY
        ))
        column.addView(manual)

        val audio = theme.card()
        audio.addView(theme.sectionLabel("소리 안내"))
        audio.addView(theme.switchRow("단계 알림음", "settings_sound", soundEnabled) { checked ->
            coordinator.dispatch(TeumSessionAction.SetSound(checked))
        })
        val ttsStatus = theme.text("", TeumText.BODY, theme.inkMuted).apply { tag = "settings_tts" }
        audio.addView(ttsStatus)
        liveUpdaters += {
            ttsStatus.text = when (cue?.status()) {
                TeumCuePlayer.Status.READY -> "한국어 음성 안내를 사용할 수 있습니다."
                TeumCuePlayer.Status.FALLBACK -> "한국어 음성을 사용할 수 없어 알림음을 시도하고 화면 안내를 표시합니다."
                TeumCuePlayer.Status.CHECKING -> "음성 엔진을 확인하는 중입니다. 화면 안내는 항상 표시됩니다."
                null -> "소리 안내를 사용할 수 없습니다. 화면 안내는 표시됩니다."
            }
        }
        audio.addView(theme.text("마이크 녹음은 하지 않습니다.", TeumText.CAPTION, theme.inkMuted))
        column.addView(audio)

        val lifecycle = theme.card(TeumSpace.L, theme.surfaceAlt, null)
        lifecycle.addView(theme.text("화면을 벗어나면", TeumText.HEADING))
        lifecycle.addView(theme.text(
            "타이머는 앱이 화면에 있을 때만 진행됩니다. 화면을 떠나면 세션이 중단으로 표시되며, 알람이 복귀를 보장하지 않습니다.",
            TeumText.BODY
        ))
        column.addView(lifecycle)
    }

    // -------------------------------------------------------------- recovery

    private fun renderRecovery(column: LinearLayout) {
        val snapshot = recovery ?: return
        val ready = snapshot.restoredAndReadyToRecord
        addTopBar(
            column,
            if (ready) "저장하지 못한 기록" else "이전 활동이 중단되었습니다",
            "차량 명령은 다시 보내지 않으며 완료로 자동 기록하지 않습니다."
        )
        val meta = metaOf(snapshot.situationId)
        val facts = theme.card(TeumSpace.L)
        facts.addView(theme.labeledRow("상황", meta?.title ?: snapshot.situationId))
        facts.addView(theme.labeledRow(
            "마지막 진행", formatDuration(snapshot.elapsedSeconds), "recovery_elapsed"
        ))
        facts.addView(theme.labeledRow("마지막 단계", stageLabel(snapshot.stage), "recovery_stage"))
        facts.addView(theme.labeledRow(
            "중단 사유", snapshot.reason.ifBlank { "확인되지 않음" }, "recovery_reason"
        ))
        column.addView(facts)
        if (snapshot.restricted) {
            column.addView(theme.text(
                "주차/주행 제한 신뢰를 잃어 제어가 제한된 세션입니다. 안전 중단으로만 기록할 수 있습니다.",
                TeumText.BODY, theme.clay
            ), theme.lp(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                0f, TeumSpace.S, 0, TeumSpace.M
            ))
        }
        column.addView(theme.text(
            "앱이 화면을 떠나거나 종료되어 타이머가 멈췄습니다. 진행 중이던 알림과 예약은 취소되었고, 이전 명령은 재생되지 않습니다.",
            TeumText.BODY
        ), theme.lp(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            0f, 0, 0, 0, TeumSpace.L
        ))
        val primary = theme.primaryButton(
            if (ready) "기록 저장 화면 열기" else "중단으로 기록 남기기", "recovery_record_button"
        ) { coordinator.dispatch(TeumSessionAction.OpenRecoveryRecord) }
        primary.minHeight = theme.dp(TeumSize.PRIMARY)
        primary.minimumHeight = theme.dp(TeumSize.PRIMARY)
        column.addView(primary, theme.lp(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.dp(TeumSize.PRIMARY), 0f,
            0, TeumSpace.S, 0, 0
        ))
        column.addView(theme.quietButton("기록 없이 닫기", "recovery_discard_button") {
            confirmDiscardRecovery()
        })
    }

    // ---------------------------------------------------------------- helpers

    private fun startTimer() {
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 1_000)
    }

    private fun stopTimer() {
        handler.removeCallbacks(ticker)
    }

    private companion object {
        const val KEY_SOUND = "sound_enabled_v1"
        const val SEAT_REASON =
            TeumSessionCoordinator.SEAT_REASON
    }
}
