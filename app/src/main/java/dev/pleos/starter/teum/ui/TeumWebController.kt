package dev.pleos.starter.teum.ui

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import dev.pleos.starter.teum.audio.NativeSpeechCapture
import dev.pleos.starter.teum.audio.RecognitionOutcome
import dev.pleos.starter.teum.audio.RestSoundPlayer
import dev.pleos.starter.teum.audio.AndroidMusicOutput
import dev.pleos.starter.teum.audio.MusicPlayback
import dev.pleos.starter.teum.audio.OriginalMusic
import dev.pleos.starter.teum.audio.SpeechCaptureResult
import dev.pleos.starter.teum.journey.*
import dev.pleos.starter.teum.location.LocationState
import dev.pleos.starter.teum.location.PleosLocationSource
import dev.pleos.starter.teum.location.*
import dev.pleos.starter.teum.preferences.*
import dev.pleos.starter.teum.records.RecordsRepository
import dev.pleos.starter.teum.records.HistoryPolicy
import dev.pleos.starter.teum.records.SharedPreferencesRecordStore
import dev.pleos.starter.teum.session.ClockReading
import dev.pleos.starter.teum.vehicle.AaosSafetySource
import dev.pleos.starter.teum.vehicle.CabinObservation
import dev.pleos.starter.teum.vehicle.CabinReadStatus
import dev.pleos.starter.teum.vehicle.CabinPositionStatus
import dev.pleos.starter.teum.vehicle.PleosCabinSource
import dev.pleos.starter.teum.vehicle.VehicleSafety
import dev.pleos.starter.teum.vehicle.PleosBrakeSource
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.Executors

internal fun CabinObservation.capabilityFact(): JsonObject = JsonObject().apply {
    // "confirmed" enables a physical control in the original WebView.
    addProperty("status", status.name.lowercase())
    addProperty("label", when (status) {
        CabinReadStatus.STOPPED -> "조회 중지 · 자동 제어 미연결"
        CabinReadStatus.PENDING -> "지원·조회 확인 중 · 자동 제어 미연결"
        CabinReadStatus.DENIED -> "조회 접근 거부 · 자동 제어 미연결"
        CabinReadStatus.UNSUPPORTED -> "지원 영역 없음 · 자동 제어 미연결"
        CabinReadStatus.UNREADABLE -> "읽기 미지원 · 자동 제어 미연결"
        CabinReadStatus.FAILED -> "지원 조회 실패 · 자동 제어 미연결"
        CabinReadStatus.READABLE -> "읽기 지원 ${areas.size}개 영역 · 자동 제어 미연결"
    })
    addProperty("accessType", access)
    addProperty("readPermissionGranted", permissionGranted)
    addProperty("writeReady", false)
    addProperty("error", error)
    add("areas", JsonArray().apply {
        areas.values.forEach { area ->
            add(JsonObject().apply {
                add("identity", JsonArray().apply { area.identity.forEach { add(it) } })
                addProperty("rawMin", area.rawMin)
                addProperty("rawMax", area.rawMax)
                addProperty("rawPosition", area.rawPosition)
                addProperty("unit", "SDK_RAW_POSITION")
                addProperty("status", area.status.name.lowercase())
                addProperty("error", area.error)
            })
        }
    })
}

internal fun CabinObservation.positionFact(): JsonObject {
    if (status != CabinReadStatus.READABLE) return capabilityFact()
    val position = when {
        areas.values.any { it.status == CabinPositionStatus.FAILED } -> "failed"
        areas.values.all { it.status == CabinPositionStatus.OBSERVED } -> "observed"
        else -> "unknown"
    }
    return JsonObject().apply {
        addProperty("status", position)
        // User-facing copy only: area identities and SDK integer values stay in capabilityFact().
        addProperty("label", when {
            position == "failed" -> "현재 값을 읽지 못했어요"
            position == "observed" -> "현재 값 확인됨"
            areas.values.any { it.status == CabinPositionStatus.PENDING } -> "현재 값 확인 중"
            else -> "현재 값 확인 안 됨"
        })
    }
}

/**
 * One serialized native owner for the original UI. Event clocks are captured before
 * queueing, so storage latency cannot add background time to an activity.
 */
class TeumWebController(private val activity: Activity, root: ViewGroup) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val preferences = PreferencesRepository(SharedPreferencesPreferenceStore(activity))
    private val recordStore = SharedPreferencesRecordStore(activity)
    private val records = RecordsRepository(recordStore)
    private val data = TeumWebData(preferences, records)
    private val uiStore = TeumWebUiStore(activity)
    private val oldMarkers = SessionSnapshotStore(activity)
    private var reading = nativeClock()
    private val runtime = JourneyRuntime(JourneyClock { reading }, TeumJourneySnapshotStore(activity))
    private val projection = TeumWebProjection()
    private val permissions = TeumWebPermissions(activity) { submit { publish(true) } }
    private val host: TeumWebViewHost = TeumWebViewHost(activity, ::onRequest) {
        arrivalDocumentAlive = false
        foregroundEpoch++
        brakeSource?.close()
        brakeSource = null
        stopOutputs()
        submit {
            cancelArrival()
            runtime.state?.takeIf { it.active }?.let { runtime.interrupt(JourneyInterruption.BACKGROUND) }
        }
    }
    @Volatile private var foreground = false
    @Volatile private var destroyed = false
    @Volatile private var effectToken: JourneyToken? = null
    @Volatile private var audioPhase: NativeSpeechCapture.Phase? = null
    private var speechToken: JourneyToken? = null
    @Volatile private var musicSafety = VehicleSafety()
    private var musicObservation: MusicPlayback.Observation? = null
    private val musicRenderer = Executors.newSingleThreadExecutor()
    private val music = MusicPlayback(
        { token -> AndroidMusicOutput(activity, musicRenderer) {
            foreground && !destroyed && arrivalDocumentAlive && effectToken == token &&
                musicSafety.blockedReason(SystemClock.elapsedRealtime()) == null
        } }
    ) { observed ->
        submit {
            val current = runtime.state
            if (!foreground || current?.active != true || runtime.token != observed.token ||
                current.phase != JourneyPhase.MUSIC_FOCUS) return@submit
            val changed = current.playback != observed.state
            musicObservation = observed
            if (changed && observed.state != PlaybackState.REQUESTED)
                update(runtime.playbackObserved(observed.token, observed.state))
            publish(changed)
        }
    }
    private var soundState: RestSoundPlayer.State = RestSoundPlayer.State.Stopped
    private val sounds = RestSoundPlayer(activity) { observed ->
        submit {
            val changed = soundState != observed
            soundState = observed
            if (observed is RestSoundPlayer.State.Unavailable)
                notice = "소리를 출력하지 못했어요. 차량의 오디오 상태를 확인해 주세요."
            publish(changed)
        }
    }
    private val speech = NativeSpeechCapture(activity, onResult = { result ->
        audioPhase = null
        val token = speechToken
        submit {
            if (token == null || runtime.state?.active != true || runtime.token != token) return@submit
            when (result) {
                is SpeechCaptureResult.Completed -> {
                    val phase = requireNotNull(runtime.state).phase
                    if (phase in setOf(JourneyPhase.INTENTION, JourneyPhase.EVENING_STORY)) {
                        val text = result.measurement.recognizedText
                        if (text.isNullOrBlank()) {
                            update(runtime.speechUnavailable(token))
                            notice = "받아쓰기를 완료하지 못했어요. 다시 말하거나 건너뛸 수 있어요."
                        } else update(runtime.textRecognized(token, text))
                    } else {
                        // A real capture can reach its native maximum before the user presses Done.
                        if (runtime.state?.speechCapture == SpeechCapture.RECORDING)
                            update(runtime.request(token, JourneyRequest.SpeechDone))
                        update(runtime.speechMeasured(token, result.measurement))
                        if (result.recognition != RecognitionOutcome.Recognized)
                            notice = "녹음 시간과 상대 음량을 측정했어요. 인식되지 않은 항목은 비워 두었어요."
                    }
                }
                is SpeechCaptureResult.Failed -> {
                    update(runtime.speechUnavailable(token))
                    notice = "마이크 입력을 완료하지 못했어요. 권한과 입력 상태를 확인해 주세요."
                }
                SpeechCaptureResult.Cancelled -> Unit
            }
            publish(true)
        }
    }, onPhase = { phase ->
        audioPhase = phase
        submit { publish(true) }
    })
    private var cue: TeumCuePlayer? = null
    private var safetySource: AaosSafetySource? = null
    private var location: PleosLocationSource? = null
    private var locationState: LocationState = LocationState.Stopped
    @Volatile private var cabinSource: PleosCabinSource? = null
    @Volatile private var cabinGeneration = 0L
    private var cabin = mapOf("seat" to CabinObservation(), "windows" to CabinObservation())
    private var cabinCleanupErrors = emptyList<String>()
    private var safety = VehicleSafety()
    private var route = TeumWebRoute.ON_01
    private var revision = 0L
    private var napMinutes = 20
    private var selectedDate = LocalDate.now()
    private var cachedData: JsonObject? = null
    private var cachedDay: LocalDate? = null
    private var notice: String? = null
    private var recoveryError = false
    private var savedTerminal = false
    private var presentedReflectionId: String? = null
    @Volatile private var foregroundEpoch = 0L
    @Volatile private var arrivalDocumentAlive = true
    @Volatile private var windowFocused = false
    @Volatile private var brakeSource: PleosBrakeSource? = null
    private var brake = BrakeFact(null, -1, -1)
    private var brakeError: String? = null
    private var brakeCleanupErrors = emptyList<String>()
    private val arrivalStore = NativeArrivalLedgerStore(activity)
    private var arrivalZone = ZoneId.systemDefault()
    private var arrivalPolicy = ForegroundArrivalPolicy(preferences, arrivalStore, Clock.system(arrivalZone))
    private var arrivalOffer: ArrivalSuggestion? = null
    private var arrivalOrigin: Pair<ArrivalSuggestion, String>? = null
    private var arrivalNonce: String? = null
    private var arrivalHostEpoch = -1L
    private var arrivalFailure: String? = null
    private var focusObserver: ViewTreeObserver? = null
    private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
        windowFocused = focused
        submit { publish(arrivalStep()) }
    }
    private val ticker = object : Runnable {
        override fun run() {
            if (!foreground || destroyed) return
            location?.refresh()
            cabinSource?.refresh()
            brakeSource?.refresh()
            submit {
                if (runtime.state?.active == true) {
                    if (safety.blockedReason(reading.elapsedMillis) != null) {
                        main.post(::stopOutputs)
                        update(runtime.interrupt(JourneyInterruption.SAFETY))
                    } else update(runtime.tick())
                }
                publish(arrivalStep())
            }
            main.postDelayed(this, 1000)
        }
    }

    init {
        root.addView(host.view, ViewGroup.LayoutParams(-1, -1))
    }

    fun onCreate() {
        host.load()
        submit {
            try {
                runtime.recover()?.let(::update)
            } catch (error: InvalidJourneySnapshot) {
                recoveryError = true
                notice = "이전 활동의 저장 상태를 읽을 수 없어요. 원본 데이터는 보존했어요."
            }
            route = if (runtime.state != null || oldMarkers.load() != null || recoveryError)
                TeumWebRoute.C_03 else if (uiStore.onboardingCompleted()) TeumWebRoute.H_01 else TeumWebRoute.ON_01
            publish(true)
        }
    }

    fun onStart() {
        val owner = ++foregroundEpoch
        foreground = true
        windowFocused = host.view.hasWindowFocus()
        focusObserver = host.view.viewTreeObserver.also {
            it.addOnWindowFocusChangeListener(focusListener)
        }
        cue = TeumCuePlayer(activity)
        brakeSource = PleosBrakeSource(activity) { observed ->
            submit {
                brakeCleanupErrors = (brakeCleanupErrors + observed.cleanupErrors).distinct()
                if (!foreground || owner != foregroundEpoch ||
                    brakeSource?.revision != observed.revision) return@submit
                brake = BrakeFact(observed.pressed, observed.observedAtElapsedMillis, observed.safetyGeneration)
                if (observed.error != null) brakeError = observed.error
                else if (observed.pressed != null) brakeError = null
                publish(arrivalStep())
            }
        }
        safetySource = AaosSafetySource(activity) { observed ->
            if (owner != foregroundEpoch) return@AaosSafetySource
            musicSafety = observed
            brakeSource?.observeSafety(observed)
            if (observed.blockedReason(SystemClock.elapsedRealtime()) != null) stopOutputs()
            submit {
                if (!foreground || owner != foregroundEpoch) return@submit
                val previousBlock = safety.blockedReason(reading.elapsedMillis)
                safety = observed
                if (runtime.state?.active == true && safety.blockedReason(reading.elapsedMillis) != null)
                    update(runtime.interrupt(JourneyInterruption.SAFETY))
                val arrivalChanged = arrivalStep()
                publish(previousBlock != safety.blockedReason(reading.elapsedMillis) || arrivalChanged)
            }
        }.also { it.start() }
        location = PleosLocationSource(activity, Clock.systemDefaultZone()) { observed ->
            submit {
                if (!foreground || owner != foregroundEpoch) return@submit
                locationState = observed
                publish(arrivalStep())
            }
        }.also { it.start() }
        val cabinEpoch = ++cabinGeneration
        cabinSource = PleosCabinSource(activity) { sourceRevision, observed, cleanupErrors ->
            submit {
                cabinCleanupErrors = (cabinCleanupErrors + cleanupErrors).distinct()
                if (cabinEpoch != cabinGeneration) return@submit
                if (foreground && cabinSource?.revision != sourceRevision) return@submit
                val previous = cabin
                cabin = if (foreground) observed else observed.mapValues { CabinObservation() }
                publish(route == TeumWebRoute.ST_07 && cabin != previous)
            }
        }
        cabinSource?.start()
        submit {
            cachedData = null
            publish(true)
            // Replace any pre-stop cached invitation before reopening the message boundary.
            main.post { if (foreground && owner == foregroundEpoch && arrivalDocumentAlive) host.resume() }
        }
        main.post(ticker)
    }

    fun onStop() {
        foreground = false
        foregroundEpoch++
        windowFocused = false
        focusObserver?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focusListener)
        focusObserver = null
        brakeSource?.close()
        brakeSource = null
        main.removeCallbacks(ticker)
        stopOutputs()
        host.suspend()
        submit {
            cancelArrival()
            runtime.state?.takeIf { it.active }?.let { update(runtime.interrupt(JourneyInterruption.BACKGROUND)) }
            publish(true)
        }
        safetySource?.close()
        safetySource = null
        location?.close()
        location = null
        cabinSource?.close()
        cabinSource = null
        cue?.close()
        cue = null
    }

    fun onDestroy() {
        foregroundEpoch++
        brakeSource?.close()
        brakeSource = null
        focusObserver?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focusListener)
        focusObserver = null
        destroyed = true
        main.removeCallbacks(ticker)
        speech.close()
        sounds.close()
        music.close()
        musicRenderer.shutdownNow()
        permissions.close()
        cue?.close()
        safetySource?.close()
        location?.close()
        cabinSource?.close()
        cabinSource = null
        host.close()
        worker.shutdown()
    }

    fun onConfigurationChanged() = submit { publish(true) }
    fun onPermissionResult(requestCode: Int): Boolean {
        val handled = permissions.onResult(requestCode)
        if (handled) {
            location?.start()
            cabinSource?.refresh()
            submit { publish(true) }
        }
        return handled
    }

    fun onBackPressed(): Boolean {
        submit {
            if (runtime.state?.active == true && runtime.state?.phase == JourneyPhase.EVENING_DAY_REVIEW) {
                check(foreground && safety.blockedReason(reading.elapsedMillis) == null)
                update(runtime.request(runtime.token, Previous))
                publish(true)
            } else main.post {
                host.view.evaluateJavascript(
                    "if(state.screen==='H-01'){window.teum.postMessage(JSON.stringify({requestId:'back-'+Date.now(),epoch:${
                        host.epoch
                    },action:'navigate',route:'H-01'}));}else if(state.entry?.reviewing||S[state.screen]?.session){ACT.end();}else{go('H-01');}", null
                )
            }
        }
        return true
    }

    private fun submit(previousReceipt: () -> PreviousTransition? = { null }, action: () -> Unit) {
        if (destroyed) return
        val eventReading = nativeClock()
        worker.execute {
            reading = eventReading
            try {
                action()
            } catch (error: JourneyRejected) {
                notice = "현재 단계에서 할 수 없는 요청이에요."
                publish(true, previousReceipt())
            } catch (error: IllegalArgumentException) {
                arrivalNonce = null
                notice = "입력값이나 저장된 상태를 확인할 수 없어요."
                publish(true, previousReceipt())
            } catch (error: IllegalStateException) {
                arrivalNonce = null
                main.post(::stopOutputs)
                notice = "요청을 완료하지 못했어요. 저장·연결 상태를 확인해 주세요."
                publish(true, previousReceipt())
            }
        }
    }

    private fun onRequest(request: TeumWebRequest) {
        var committedPrevious: PreviousTransition? = null
        submit(previousReceipt = { committedPrevious }) {
            if (!foreground || request.epoch != host.epoch) return@submit
            val action = request.action
            if (action is TeumWebAction.AdmitArrival) {
                admitArrival(request, action.nonce)
                return@submit
            }
            if (request.arrivalToken != null && action != TeumWebAction.ReadState)
                check(request.arrivalToken == arrivalOffer?.token) { "Stale arrival origin" }
            val current = runtime.state
            if (current?.active == true && action != TeumWebAction.ReadState) {
                token(request)
                val permitted = action is TeumWebAction.Navigate || action is TeumWebAction.JourneyCommand ||
                    action is TeumWebAction.JourneyInput || action == TeumWebAction.FinishEarly ||
                    action is TeumWebAction.ReconfigureEntry && current.phase == JourneyPhase.ENTRY_REVIEW ||
                    action is TeumWebAction.SkipSuggestion && current.phase == JourneyPhase.ENTRY_REVIEW ||
                    action == TeumWebAction.AcknowledgeWake ||
                    action is TeumWebAction.SelectDay && current.phase == JourneyPhase.EVENING_WEEK &&
                        route == TeumWebRoute.F4_05 ||
                    action is TeumWebAction.SaveBackrest && current.phase == JourneyPhase.NAP_POSTURE
                if (!permitted) throw JourneyRejected("Intent is outside the active phase")
            }
            if (data.applyPreference(action)) {
                cachedData = null
                when (action) {
                    is TeumWebAction.SelectSound -> {
                        val stop = (soundState as? RestSoundPlayer.State.Playing)?.sound == action.sound ||
                            (soundState as? RestSoundPlayer.State.Preparing)?.sound == action.sound
                        main.post {
                            if (foreground && !destroyed) {
                                speech.cancel()
                                if (stop) sounds.stop() else sounds.play(action.sound)
                            }
                        }
                    }
                    is TeumWebAction.SaveBackrest -> when (route) {
                        TeumWebRoute.ON_06 -> route = TeumWebRoute.ON_07
                        TeumWebRoute.F2_03 -> journey(request, JourneyRequest.Continue)
                        else -> Unit
                    }
                    else -> Unit
                }
            } else when (action) {
                TeumWebAction.ReadState -> Unit
                is TeumWebAction.Navigate -> navigate(request, action.route)
                is TeumWebAction.OpenJourney -> startFromArrival(request, action)
                is TeumWebAction.ReconfigureEntry -> reconfigureEntry(request, action.choice)
                is TeumWebAction.JourneyCommand -> command(request, action.command) { committedPrevious = it }
                is TeumWebAction.JourneyInput -> input(request, action)
                TeumWebAction.FinishEarly -> journey(request, JourneyRequest.Stop)
                TeumWebAction.AcknowledgeWake -> journey(request, JourneyRequest.AcknowledgeAwake)
                TeumWebAction.SaveJourney -> saveJourney(request)
                is TeumWebAction.SelectDay -> {
                    require(cachedData?.getAsJsonObject("week")?.getAsJsonArray("days")
                        ?.any { it.asJsonObject["date"].asString == action.date.toString() } == true)
                    if (current != null) journey(request, ReviewDay) else route = TeumWebRoute.R_02
                    selectedDate = action.date
                }
                is TeumWebAction.SkipSuggestion -> {
                    if (current?.active == true && current.phase == JourneyPhase.ENTRY_REVIEW) {
                        val expected = when (current.plan.route) {
                            JourneyRoute.SHORT_MORNING -> WebJourney.MORNING
                            JourneyRoute.NAP -> WebJourney.LUNCH
                            JourneyRoute.PREPARATION -> WebJourney.PREPARATION
                            JourneyRoute.EVENING, JourneyRoute.SHORT_EVENING -> WebJourney.EVENING
                            else -> throw JourneyRejected("No original entry skip")
                        }
                        require(action.journey == expected)
                        val origin = arrivalOrigin?.takeIf { it.second == current.sessionId }
                        // Commit the interrupted owner before publishing or writing shell/offer metadata.
                        val stopped = runtime.request(token(request), JourneyRequest.Stop)
                        update(stopped)
                        if (origin != null) resolveArrival(origin.first, SuggestionOutcome.SKIPPED)
                        else uiStore.skip(slot(action.journey), localDay())
                    } else {
                        check(runtime.state == null)
                        val offered = arrivalOffer
                        if (offered != null) {
                            check(request.arrivalToken == offered.token && arrivalSlot(offered.slot) == slot(action.journey))
                            resolveArrival(offered, SuggestionOutcome.SKIPPED)
                        } else uiStore.skip(slot(action.journey), localDay())
                        route = TeumWebRoute.H_01
                    }
                }
                is TeumWebAction.MeetingFeedback -> {
                    check(uiStore.saveMeetingFeedback(action.value, reading.wallMillis))
                    route = TeumWebRoute.H_01
                }
                is TeumWebAction.RequestPermission -> main.post {
                    if (foreground && !destroyed) permissions.request(action.permission)
                }
                TeumWebAction.CompleteOnboarding -> { uiStore.completeOnboarding(); route = TeumWebRoute.ON_08 }
                is TeumWebAction.CaptureLocation -> {
                    if (activity.checkSelfPermission(PleosLocationSource.PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                        main.post {
                            if (foreground && !destroyed) permissions.request(WebPermission.LOCATION)
                        }
                        notice = "위치 권한을 확인한 뒤 현재 위치 등록을 다시 눌러 주세요."
                    } else {
                        val coordinates = location?.capture()
                        if (coordinates == null) notice = locationDescription(location?.state ?: LocationState.Stopped)
                        else {
                            val prefs = preferences.load()
                            val previous = if (action.home) prefs.home else prefs.company
                            data.saveCapturedPlace(action.home, previous.copy(coordinates = coordinates))
                            cachedData = null
                            notice = "확인한 현재 좌표를 저장했어요."
                        }
                    }
                }
                TeumWebAction.ClearRecords -> {
                    check(runtime.state?.active != true)
                    records.clearAll()
                    recordStore.clearQuarantine()
                    cachedData = null
                    notice = "저장된 기록을 지웠어요."
                }
                is TeumWebAction.DeleteRecord -> { records.delete(action.recordId); cachedData = null }
                is TeumWebAction.HistoryConsent -> records.setHistoryConsent(action.enabled)
                is TeumWebAction.SelectRecall -> { records.dismissRecall(action.recordId); cachedData = null }
                is TeumWebAction.ApproveRestore ->
                    notice = "이 활동에서 자동으로 바꾼 차량 설정이 없어요. 차량 상태를 직접 확인해 주세요."
                is TeumWebAction.SoundEnabled -> {
                    preferences.setVoiceGuide(action.enabled); cachedData = null
                }
                is TeumWebAction.PreviewVehicle, is TeumWebAction.ConfirmVehicle ->
                    notice = "이 화면의 차량 제어는 아직 연결되지 않았어요."
                is TeumWebAction.SaveRecord -> error("A legacy record cannot be created from an original journey")
                else -> error("Preference intent was not handled by its native owner")
            }
            publish(true, committedPrevious)
            committedPrevious = null
        }
    }

    private fun start(action: TeumWebAction.OpenJourney) {
        check(foreground && safety.blockedReason(reading.elapsedMillis) == null)
        check(!recoveryError && oldMarkers.load() == null)
        val kind = when (action.journey) {
            WebJourney.MORNING -> if (action.short) JourneyRoute.SHORT_MORNING else JourneyRoute.MORNING
            WebJourney.LUNCH -> JourneyRoute.NAP
            WebJourney.PREPARATION -> JourneyRoute.PREPARATION
            WebJourney.EVENING -> if (action.short) JourneyRoute.SHORT_EVENING else JourneyRoute.EVENING
            WebJourney.QUICK -> JourneyRoute.QUICK_BREATHING
        }
        savedTerminal = false
        update(runtime.start(JourneyPlan(kind, if (kind == JourneyRoute.NAP) napMinutes else 20)))
    }

    private fun token(request: TeumWebRequest): JourneyToken {
        val supplied = request.journeyToken ?: throw JourneyRejected("Missing journey correlation")
        val token = JourneyToken(supplied.sessionId, supplied.revision)
        if (runtime.state == null || token != runtime.token) throw JourneyRejected("Stale journey correlation")
        return token
    }

    private fun journey(request: TeumWebRequest, intent: JourneyRequest,
        onPreviousCommitted: ((JourneySnapshot, JourneySnapshot) -> Unit)? = null) {
        check(foreground && safety.blockedReason(reading.elapsedMillis) == null)
        if (intent == JourneyRequest.Continue && runtime.state?.phase == JourneyPhase.ENTRY_REVIEW) {
            check(!recoveryError && oldMarkers.load() == null && arrivalFailure == null)
            val owner = requireNotNull(runtime.state)
            validateEntryChoice(owner, EntryChoice.entries.first { it.plan == owner.plan },
                preferences.load(), uiStore.meetingFact(), nativeClock(), safety, ZoneId.systemDefault())
        }
        val correlation = token(request)
        val before = requireNotNull(runtime.state)
        val committed = runtime.request(correlation, intent)
        if (intent == Previous) onPreviousCommitted?.invoke(before, committed.snapshot)
        update(committed)
    }

    private fun reconfigureEntry(request: TeumWebRequest, choice: EntryChoice) {
        check(foreground && !recoveryError && oldMarkers.load() == null && arrivalFailure == null)
        val correlation = token(request)
        val owner = requireNotNull(runtime.state)
        val selected = validateEntryChoice(owner, choice, preferences.load(), uiStore.meetingFact(),
            nativeClock(), safety, ZoneId.systemDefault())
        val declinedOrigin = arrivalOrigin?.takeIf { it.second == owner.sessionId &&
            entryDeclinesArrival(it.first, owner.plan, selected) }
        update(runtime.request(correlation, ReconfigureEntry(choice)))
        declinedOrigin?.let { (offered, _) ->
            arrivalOrigin = null
            resolveArrival(offered, SuggestionOutcome.DECLINED)
        }
    }

    private fun command(request: TeumWebRequest, command: WebJourneyCommand,
        acceptedPrevious: (PreviousTransition) -> Unit) {
        val phase = runtime.state?.phase ?: throw JourneyRejected("No journey")
        val intent = when (command) {
            WebJourneyCommand.PREVIOUS -> Previous
            WebJourneyCommand.START -> throw JourneyRejected("A journey starts only from an explicit native start")
            WebJourneyCommand.NEXT -> when (phase) {
                JourneyPhase.INTENTION_REVIEW -> JourneyRequest.ConfirmIntention
                JourneyPhase.ENCOURAGEMENT, JourneyPhase.NAP_SUMMARY,
                JourneyPhase.MUSIC_FOCUS, JourneyPhase.EVENING_FAREWELL -> JourneyRequest.Finish
                else -> JourneyRequest.Continue
            }
            WebJourneyCommand.SKIP -> if (phase == JourneyPhase.INTENTION)
                JourneyRequest.SkipIntention else JourneyRequest.SkipStory
            WebJourneyCommand.RECORD_SPEECH -> JourneyRequest.RecordSpeech
            WebJourneyCommand.STOP_SPEECH -> JourneyRequest.SpeechDone
            WebJourneyCommand.RETRY_SPEECH -> if (phase == JourneyPhase.INTENTION_REVIEW)
                JourneyRequest.RetryIntention else JourneyRequest.SpeakAgain
            WebJourneyCommand.PLAY_MUSIC -> JourneyRequest.ListenMusic
            WebJourneyCommand.CHANGE_MUSIC -> JourneyRequest.ChangeMusic
            WebJourneyCommand.WAKE_NOW -> JourneyRequest.WakeNow
            WebJourneyCommand.CLOSE_EYES -> JourneyRequest.SetEyesClosed(true)
            WebJourneyCommand.OPEN_EYES -> JourneyRequest.SetEyesClosed(false)
        }
        journey(request, intent) { before, after ->
            TeumWebProtocol.previousTransition(request, before, after)?.let(acceptedPrevious)
        }
    }

    private fun input(request: TeumWebRequest, action: TeumWebAction.JourneyInput) {
        when (action.field) {
            WebJourneyField.NAP_MINUTES -> {
                val minutes = action.value.toInt().also { require(it in setOf(10, 15, 20)) }
                if (runtime.state != null) reconfigureEntry(request,
                    EntryChoice.entries.single { it.route == JourneyRoute.NAP && it.napMinutes == minutes })
                else napMinutes = minutes
            }
            WebJourneyField.MEETING_MINUTE -> {
                check(runtime.state == null)
                uiStore.saveMeeting(action.value.toInt(), reading.wallMillis, ZoneId.systemDefault())
                route = TeumWebRoute.H_01
            }
            else -> {
                val phase = runtime.state?.phase ?: throw JourneyRejected("No journey")
                val intent = when (action.field) {
                    WebJourneyField.FEELING -> if (phase == JourneyPhase.QUICK_FEELING)
                        JourneyRequest.ChooseQuickMood(QuickMood.valueOf(action.value))
                    else JourneyRequest.ChooseFeeling(Feeling.valueOf(action.value))
                    WebJourneyField.INTENTION -> JourneyRequest.SubmitIntention(action.value)
                    WebJourneyField.STORY -> JourneyRequest.SubmitStory(action.value)
                    WebJourneyField.ATMOSPHERE -> if (phase == JourneyPhase.EVENING_COLOR)
                        JourneyRequest.ChooseDayColor(JourneyAtmosphere.valueOf(action.value))
                    else JourneyRequest.ChooseAtmosphere(JourneyAtmosphere.valueOf(action.value))
                    WebJourneyField.MUSIC_CHOICE -> JourneyRequest.SelectMusic(MusicChoice.valueOf(action.value))
                    else -> error("Setup input cannot reach an active journey")
                }
                journey(request, intent)
                if (action.field == WebJourneyField.ATMOSPHERE && phase == JourneyPhase.ATMOSPHERE) {
                    preferences.setAtmosphere(Atmosphere.valueOf(action.value)); cachedData = null
                }
            }
        }
    }

    private fun navigate(request: TeumWebRequest, target: TeumWebRoute) {
        val current = runtime.state
        if (current != null) {
            if (!current.active) {
                check(target == TeumWebRoute.C_03 || savedTerminal && target == TeumWebRoute.H_01)
                route = target
                return
            }
            if (target.id == current.screenId) return
            val intent = when {
                target == TeumWebRoute.F1_03a -> JourneyRequest.SetEyesClosed(true)
                current.eyesClosed && target == TeumWebRoute.F1_03 -> JourneyRequest.SetEyesClosed(false)
                current.phase == JourneyPhase.INTENTION && target == TeumWebRoute.F1_05 -> JourneyRequest.SkipIntention
                current.phase == JourneyPhase.INTENTION_REVIEW && target == TeumWebRoute.F1_04 -> JourneyRequest.RetryIntention
                current.phase == JourneyPhase.EVENING_STORY && target == TeumWebRoute.F4_05 -> JourneyRequest.SkipStory
                current.phase == JourneyPhase.MUSIC_FOCUS && target == TeumWebRoute.F3_06 -> JourneyRequest.ChangeMusic
                current.phase == JourneyPhase.SPEECH_FIRST_REVIEW && target == TeumWebRoute.F3_03 -> JourneyRequest.SpeakAgain
                current.phase == JourneyPhase.INTENTION_REVIEW && target == TeumWebRoute.F1_05 -> JourneyRequest.ConfirmIntention
                current.phase == JourneyPhase.MORNING_GREETING && target == TeumWebRoute.F1_02 -> JourneyRequest.Continue
                current.phase in setOf(JourneyPhase.SPEECH_FIRST_REVIEW, JourneyPhase.SPEECH_COMPARISON) &&
                    target == TeumWebRoute.F3_06 -> JourneyRequest.Continue
                current.phase == JourneyPhase.EVENING_WEEK && target == TeumWebRoute.F4_06 -> JourneyRequest.Continue
                else -> throw JourneyRejected("The route is not an authorized phase transition")
            }
            journey(request, intent)
            return
        }
        if (target == TeumWebRoute.Q1_01) {
            startFromArrival(request, TeumWebAction.OpenJourney(WebJourney.QUICK))
            return
        }
        check(!SENSITIVE_ROUTES.contains(target))
        arrivalOffer?.takeIf { target != route }?.let {
            check(request.arrivalToken == it.token)
            resolveArrival(it, SuggestionOutcome.DECLINED)
        }
        if (target != route) arrivalNonce = null
        if (target != route) main.post(::stopOutputs)
        route = target
    }

    private fun saveJourney(request: TeumWebRequest) {
        runtime.state?.let { snapshot ->
            token(request)
            check(!snapshot.active)
            records.save(snapshot.toActivityRecord(reading.wallMillis))
            runtime.clear()
        } ?: run {
            check(!recoveryError && oldMarkers.load() != null)
            val legacy = TeumSessionCoordinator(records, oldMarkers, object : SessionClock {
                override fun reading() = this@TeumWebController.reading
            })
            legacy.onCreate()
            legacy.dispatch(TeumSessionAction.OpenRecoveryRecord)
            check(legacy.state.screen == Screen.RECORD)
            legacy.dispatch(TeumSessionAction.SaveRecord(legacy.state.editable))
            check(oldMarkers.load() == null)
            legacy.onDestroy()
        }
        savedTerminal = true
        cachedData = null
        route = TeumWebRoute.C_03
        notice = "실제 진행한 활동 기록을 저장했어요."
    }

    private fun update(update: JourneyUpdate) {
        val previous = route
        presentedReflectionId?.takeIf {
            foreground && previous == TeumWebRoute.F1_01 && update.snapshot.active &&
                update.snapshot.phase == JourneyPhase.MORNING_FEELING
        }?.let { reflectionId ->
            records.consumeMorningReflection(reflectionId, ZoneId.systemDefault(),
                preferences.load().privacy.deleteEveningStoryAfterNextMorning)
            cachedData = null
        }
        effectToken = runtime.token.takeIf { update.snapshot.active }
        route = if (update.snapshot.active) requireNotNull(TeumWebRoute.fromId(update.snapshot.screenId)) else TeumWebRoute.C_03
        if (route != TeumWebRoute.F1_01) presentedReflectionId = null
        if (route != previous) revision++
        val sound = preferences.load().restSound
        update.effects.forEach { effect ->
            when (effect) {
                JourneyEffect.StopOwnedEffects -> main.post(::stopOutputs)
                is JourneyEffect.PhaseEntered -> if (effect.phase in setOf(
                    JourneyPhase.MORNING_BREATHING, JourneyPhase.QUICK_BREATHING,
                    JourneyPhase.NAP_SETTLING, JourneyPhase.NAP_SLEEPING
                )) main.post { if (foreground && !destroyed) sounds.play(sound) }
                is JourneyEffect.CaptureSpeech -> capture(effect.token)
                is JourneyEffect.CaptureText -> capture(effect.token)
                is JourneyEffect.FinishSpeech, is JourneyEffect.FinishText ->
                    main.post { if (!destroyed) speech.finish() }
                is JourneyEffect.PlayMusic -> {
                    val piece = OriginalMusic.select(update.snapshot.plan.kind, effect.choice)
                    val owner = foregroundEpoch
                    main.post {
                        if (foreground && !destroyed && owner == foregroundEpoch && effectToken == effect.token) {
                            sounds.stop()
                            cue?.stop()
                            music.play(effect.token, piece)
                        }
                    }
                }
                JourneyEffect.WakeReminder -> main.post {
                    if (foreground && !destroyed) { cue?.beep(true); if (preferences.load().voiceGuide) cue?.speak("일어났어요?") }
                }
            }
        }
        arrivalOrigin?.takeIf { it.second == update.snapshot.sessionId }?.let { (offered, _) ->
            if (!update.snapshot.active) {
                arrivalOrigin = null
                if (update.snapshot.status == JourneyStatus.COMPLETED &&
                    update.snapshot.plan.kind.name == offered.slot.name) {
                    try { check(arrivalPolicy.resolve(offered, SuggestionOutcome.COMPLETED)) }
                    catch (error: IllegalStateException) {
                        failArrival(error)
                        notice = "활동은 끝났지만 도착 제안 결과를 저장하지 못했어요."
                    }
                }
            }
        }
    }

    private fun capture(token: JourneyToken) = main.post {
        if (foreground && !destroyed && effectToken == token) {
            sounds.stop()
            music.stop()
            cue?.stop()
            if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                submit {
                    if (runtime.state?.active == true && runtime.token == token) {
                        update(runtime.speechUnavailable(token))
                        notice = "마이크 권한을 확인한 뒤 말하기를 다시 눌러 주세요."
                        publish(true)
                    }
                }
                permissions.request(WebPermission.MICROPHONE)
                return@post
            }
            speechToken = token
            speech.start()
        }
    }

    private fun stopOutputs() {
        audioPhase = null
        speech.cancel()
        sounds.stop()
        music.stop()
        cue?.stop()
    }

    private fun arrivalSlot(slot: JourneyNotification): String = when (slot) {
        JourneyNotification.MORNING -> "am"
        JourneyNotification.NAP -> "noon"
        JourneyNotification.PREPARATION -> "meet"
        JourneyNotification.EVENING -> "pm"
    }

    private fun arrivalRoute(slot: JourneyNotification): TeumWebRoute = when (slot) {
        JourneyNotification.MORNING -> TeumWebRoute.M_F1
        JourneyNotification.NAP -> TeumWebRoute.F2_01
        JourneyNotification.PREPARATION -> TeumWebRoute.F3_01
        JourneyNotification.EVENING -> TeumWebRoute.F4_01
    }

    private fun cancelArrival(): Boolean {
        val ownedRoute = arrivalOffer?.let { arrivalRoute(it.slot) == route } == true
        arrivalPolicy.cancel()
        arrivalOffer = null
        arrivalNonce = null
        brake = if (foreground) brake else BrakeFact(null, -1, -1)
        if (ownedRoute) route = TeumWebRoute.H_01
        return ownedRoute
    }

    private fun arrivalHomeAvailable(): Boolean =
        foreground && arrivalDocumentAlive && windowFocused && arrivalFailure == null &&
            route == TeumWebRoute.H_01 &&
            runtime.state == null && !recoveryError && oldMarkers.load() == null &&
            uiStore.onboardingCompleted()

    private fun arrivalFacts(): ArrivalFacts {
        val now = System.currentTimeMillis()
        val day = Instant.ofEpochMilli(now).atZone(arrivalZone).toLocalDate()
        val snapshot = if (cachedDay == day) cachedData else null
        val actual = snapshot ?: data.snapshot(now, arrivalZone)
        val completed = actual.getAsJsonObject("week").getAsJsonArray("days")
            .map { it.asJsonObject }.firstOrNull { it["date"].asString == day.toString() }
            ?.getAsJsonArray("completedJourneys")?.map { it.asString }.orEmpty()
        val skipped = uiStore.skippedOn(day)
        val excluded = JourneyNotification.entries.filter {
            it.name in completed || arrivalSlot(it) in skipped
        }.toSet()
        return ArrivalFacts(foreground && arrivalDocumentAlive && windowFocused,
            locationState, safety, brake, SystemClock.elapsedRealtime(), arrivalZone,
            uiStore.meetingFact(), excluded)
    }

    private fun failArrival(error: RuntimeException) {
        arrivalFailure = "${error.javaClass.simpleName}: ${error.message}"
        cancelArrival()
        notice = "도착 제안을 확인하거나 저장하지 못했어요. 기존 상태는 보존했어요."
    }

    private fun arrivalDecision(allowNewOffer: Boolean): ArrivalDecision {
        if (arrivalFailure != null) return ArrivalDecision.Suppressed("Arrival evaluation unavailable")
        return try { arrivalPolicy.evaluate(arrivalFacts(), allowNewOffer) }
        catch (error: RuntimeException) {
            failArrival(error)
            ArrivalDecision.Suppressed("Arrival evaluation unavailable")
        }
    }

    /** Worker-only. Runtime event clocks remain untouched; arrival checks use execution time. */
    private fun arrivalStep(): Boolean {
        if (arrivalNonce != null && arrivalHostEpoch != host.epoch) arrivalNonce = null
        if (ZoneId.systemDefault() != arrivalZone) {
            val changed = cancelArrival()
            arrivalZone = ZoneId.systemDefault()
            arrivalPolicy = ForegroundArrivalPolicy(preferences, arrivalStore, Clock.system(arrivalZone))
            return changed
        }
        if (!foreground || !arrivalDocumentAlive || !windowFocused || runtime.state != null)
            return cancelArrival()
        val offered = arrivalOffer
        if (offered != null) {
            if (route != arrivalRoute(offered.slot)) return cancelArrival()
            when (val result = arrivalDecision(allowNewOffer = false)) {
                is ArrivalDecision.Offer -> arrivalOffer = result.suggestion
                is ArrivalDecision.Suppressed -> return cancelArrival()
            }
            return false
        }
        if (!arrivalHomeAvailable()) {
            arrivalNonce = null
            return false
        }
        val probe = arrivalDecision(allowNewOffer = false)
        if (probe is ArrivalDecision.Suppressed && probe.reason == "Presentation not admitted") {
            if (arrivalNonce == null) {
                arrivalNonce = java.util.UUID.randomUUID().toString()
                arrivalHostEpoch = host.epoch
            }
        } else arrivalNonce = null
        return false
    }

    private fun admitArrival(request: TeumWebRequest, nonce: String) {
        if (nonce != arrivalNonce || request.epoch != arrivalHostEpoch) return
        arrivalNonce = null
        if (!arrivalHomeAvailable()) { publish(false); return }
        when (val decision = arrivalDecision(allowNewOffer = true)) {
            is ArrivalDecision.Offer -> {
                arrivalOffer = decision.suggestion
                route = arrivalRoute(decision.suggestion.slot)
                publish(true)
            }
            is ArrivalDecision.Suppressed -> publish(false)
        }
    }

    private fun resolveArrival(offered: ArrivalSuggestion, outcome: SuggestionOutcome) {
        try { check(arrivalPolicy.resolve(offered, outcome)) { "Arrival outcome was not recorded" } }
        catch (error: RuntimeException) { failArrival(error); throw error }
        arrivalOffer = null
        arrivalNonce = null
    }

    private fun startFromArrival(request: TeumWebRequest, action: TeumWebAction.OpenJourney) {
        val offered = arrivalOffer
        if (offered != null) {
            check(request.arrivalToken == offered.token)
            val validated = arrivalDecision(allowNewOffer = false)
            check(validated is ArrivalDecision.Offer && validated.suggestion.token == offered.token) {
                cancelArrival()
                "Arrival eligibility changed"
            }
            check(action.journey == WebJourney.QUICK || arrivalSlot(offered.slot) == slot(action.journey))
            if (action.journey == WebJourney.LUNCH)
                check(napMinutes <= validated.suggestion.availableMinutes) { "Selected nap exceeds available time" }
        }
        start(action)
        arrivalNonce = null
        if (offered != null) {
            arrivalPolicy.cancel()
            arrivalOffer = null
            if (entryDeclinesArrival(offered, requireNotNull(runtime.state).plan, requireNotNull(runtime.state).plan)) {
                try { check(arrivalPolicy.resolve(offered, SuggestionOutcome.DECLINED)) }
                catch (error: RuntimeException) { failArrival(error) }
            } else arrivalOrigin = offered to requireNotNull(runtime.state).sessionId
        }
    }

    private fun publish(changed: Boolean, previous: PreviousTransition? = null) {
        if (changed) revision++
        val zone = ZoneId.systemDefault()
        val today = localDay()
        if (cachedData == null || cachedDay != today) {
            cachedData = data.snapshot(reading.wallMillis, zone)
            cachedDay = today
        }
        val prefs = preferences.load()
        runtime.maintainReflection(HistoryPolicy(zone, prefs.privacy.deleteEveningStoryAfterNextMorning))
        val completion = JsonObject().apply {
            addProperty("restoreRequired", false)
            addProperty("canSave", !recoveryError && (runtime.state?.active == false || oldMarkers.load() != null))
            addProperty("canLeave", savedTerminal)
            addProperty("message", when {
                recoveryError -> "이전 저장 상태를 확인하지 못했어요. 원본 데이터는 보존했어요."
                oldMarkers.load() != null -> "이전 활동은 자동 재개하지 않아요. 진행한 내용만 기록할 수 있어요."
                savedTerminal -> "활동 기록을 저장했어요. 이 활동에서 자동 변경한 차량 설정은 없어요."
                else -> "활동이 끝났어요. 실제 진행한 내용을 저장해 주세요. 자동 변경한 차량 설정은 없어요."
            })
        }
        val hardware = JsonObject().apply {
            add("capabilities", JsonObject().apply {
                cabin.forEach { (key, observed) ->
                    add(key, observed.capabilityFact().apply {
                        addProperty("readPermission", if (key == "seat") PleosCabinSource.SEATS else PleosCabinSource.WINDOWS)
                    })
                }
            })
            add("cabin", JsonObject().apply {
                add("armchair", cabin.getValue("seat").positionFact())
                add("app-window", cabin.getValue("windows").positionFact())
            })
            add("cabinCleanupErrors", com.google.gson.JsonArray().apply {
                cabinCleanupErrors.forEach { add(it) }
            })
            add("checks", JsonObject().apply {
                add("windows", cabin.getValue("windows").positionFact())
                add("parking", JsonObject().apply {
                    addProperty("status", if (safety.blockedReason(reading.elapsedMillis) == null) "confirmed" else "unknown")
                    addProperty("label", if (safety.blockedReason(reading.elapsedMillis) == null) "주차·사용 제한 확인됨" else "주차·사용 제한 확인 필요")
                })
                add("sound", JsonObject().apply {
                    addProperty("status", if (soundState is RestSoundPlayer.State.Playing) "confirmed" else "unknown")
                    addProperty("label", when (soundState) {
                        is RestSoundPlayer.State.Playing -> "합성 휴식 소리 재생 요청됨"
                        RestSoundPlayer.State.Silent -> "소리 없음"
                        is RestSoundPlayer.State.Unavailable -> "소리 출력 불가"
                        else -> "재생하지 않음"
                    })
                })
            })
        }
        val automaticLedger = if (arrivalFailure == null) try { arrivalStore.load() }
            catch (error: RuntimeException) { failArrival(error); null } else null
        val automaticSkips = automaticLedger?.entries?.filter { (_, entry) ->
            entry.date == today.toString() && entry.outcome == SuggestionOutcome.SKIPPED
        }?.keys?.map(::arrivalSlot)?.toSet().orEmpty()
        val actualRoute = if (foreground && safety.blockedReason(reading.elapsedMillis) != null &&
            route != TeumWebRoute.C_03) TeumWebRoute.H_02 else route
        val reflection = if (foreground && actualRoute == TeumWebRoute.F1_01)
            records.morningReflection(zone) else null
        presentedReflectionId = reflection?.recordId
        val projected = projection.project(requireNotNull(cachedData), actualRoute, revision,
            reading.wallMillis, zone, prefs, runtime.state, runtime.remainingMs,
            selectedDate, uiStore.skippedOn(today) + automaticSkips, uiStore.meetingAtEpochMs(), napMinutes,
            if (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES) "dark" else "light",
            hardware, permissions.snapshot(), activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty(),
            completion, reflection?.let { it.greeting ?: it.story }, notice, arrivalOffer)
        projected.getAsJsonObject("entry").add("choices", JsonObject().apply {
            runtime.state?.takeIf { it.active && it.phase == JourneyPhase.ENTRY_REVIEW }?.let { owner ->
                val now = nativeClock()
                owner.plan.entryChoices.forEach { choice ->
                    add(choice.name, JsonObject().apply {
                        addProperty("route", choice.route.name)
                        addProperty("minutes", choice.plan.nominalMinutes)
                        val reason = try {
                            check(foreground && !recoveryError && arrivalFailure == null && oldMarkers.load() == null) {
                                "Entry ownership or arrival outcome unavailable"
                            }
                            validateEntryChoice(owner, choice, prefs, uiStore.meetingFact(), now, safety, zone)
                            null
                        } catch (error: IllegalArgumentException) { error.message }
                        catch (error: IllegalStateException) { error.message }
                        addProperty("enabled", reason == null)
                        addProperty("reason", reason)
                    })
                }
            }
        })
        projected.add("arrival", JsonObject().apply {
            addProperty("admissionNonce", arrivalNonce)
            addProperty("token", arrivalOffer?.token)
            addProperty("slot", arrivalOffer?.let { arrivalSlot(it.slot) })
            addProperty("brake", brake.pressed)
            addProperty("brakeObservedAtElapsedMillis", brake.observedAtElapsedMillis)
            addProperty("brakeError", brakeError)
            add("brakeCleanupErrors", JsonArray().apply { brakeCleanupErrors.forEach { add(it) } })
            addProperty("error", arrivalFailure)
            addProperty("ledgerAvailable", automaticLedger != null)
        })
        projected.addProperty("restricted", actualRoute == TeumWebRoute.H_02)
        projected.add("location", JsonObject().apply {
            val observed = locationState
            addProperty("status", observed.javaClass.simpleName)
            addProperty("description", locationDescription(observed))
            if (observed is LocationState.Invalid) addProperty("reason", observed.reason.name)
            if (observed is LocationState.Failed) addProperty("errorType", observed.exception.javaClass.simpleName)
        })
        projected.getAsJsonObject("speech").apply {
            addProperty("recording", foreground && audioPhase == NativeSpeechCapture.Phase.RECORDING)
            addProperty("processing", foreground && audioPhase == NativeSpeechCapture.Phase.RECOGNIZING)
            addProperty("preparing", runtime.state?.speechCapture == SpeechCapture.RECORDING && audioPhase == null)
        }
        projected.add("sound", JsonObject().apply {
            addProperty("status", if (soundState is RestSoundPlayer.State.Playing) "PLAYING" else "STOPPED")
            addProperty("sound", when (val observed = soundState) {
                is RestSoundPlayer.State.Playing -> observed.sound.name
                is RestSoundPlayer.State.Preparing -> observed.sound.name
                RestSoundPlayer.State.Silent -> RestSound.NONE.name
                RestSoundPlayer.State.Stopped, is RestSoundPlayer.State.Unavailable -> null
            })
        })
        projected.add("music", musicProjection(runtime.state, musicObservation))
        notice = null
        main.post { if (!destroyed) host.publishState(projected, previous) }
    }

    private fun locationDescription(observed: LocationState): String = when (observed) {
        LocationState.Stopped -> "위치 조회가 중지됐어요."
        LocationState.Unsupported -> "이 차량에서 위치 서비스를 지원하지 않아요."
        LocationState.PermissionDenied -> "위치 조회 권한이 필요해요."
        LocationState.Waiting -> "차량의 현재 위치를 기다리고 있어요."
        LocationState.Missing -> "위치 서비스에 현재 좌표가 없어요."
        is LocationState.Invalid -> "받은 위치 자료가 유효하지 않아요."
        is LocationState.Ready -> "현재 위치 자료를 받았어요."
        is LocationState.Failed -> "위치 서비스 요청을 완료하지 못했어요."
    }

    private fun localDay(): LocalDate =
        Instant.ofEpochMilli(reading.wallMillis).atZone(ZoneId.systemDefault()).toLocalDate()

    private fun slot(journey: WebJourney): String = when (journey) {
        WebJourney.MORNING -> "am"; WebJourney.LUNCH -> "noon"
        WebJourney.PREPARATION -> "meet"; WebJourney.EVENING -> "pm"
        WebJourney.QUICK -> error("Quick breathing is not a daily suggestion")
    }

    companion object {
        private fun nativeClock() = ClockReading(System.currentTimeMillis(), SystemClock.elapsedRealtime())
        private val SENSITIVE_ROUTES = TeumWebRoute.entries.filter {
            it.id.startsWith("F1-") || it.id.startsWith("F2-") && it != TeumWebRoute.F2_01 ||
                it.id.startsWith("F3-") && it !in setOf(TeumWebRoute.F3_00, TeumWebRoute.F3_01, TeumWebRoute.F3_08) ||
                it.id.startsWith("F4-") && it != TeumWebRoute.F4_01 || it.id.startsWith("Q1-")
        }.toSet()
    }
}
