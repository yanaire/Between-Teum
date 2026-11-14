package dev.pleos.starter.teum.vehicle

/**
 * Main-thread confined with the Android port; no saved/replayed commands.
 * A preview ID identifies one consent, one snapshot and one safety epoch.
 * The UI must render the preview before calling confirm; it cannot supply P.
 */
class SafeVehicleAdapter(
    private val port: VehicleSettingsPort,
    private val nowMillis: () -> Long,
    private val onCommand: (VehicleCommand) -> Unit = {}
) : AutoCloseable {
    private data class Entry(
        val initial: Double,
        var lastApplied: Double? = null,
        var overridden: Boolean = false,
        var unknownOrigin: Boolean = true,
        var revision: Long = 0
    )
    private data class Pending(
        var command: VehicleCommand,
        val epoch: Long,
        val created: Long,
        val expectedCurrent: Double,
        val revision: Long,
        var confirmedAt: Long? = null,
        var executionAllowed: () -> Boolean = { true },
        var observingSince: Long? = null,
        var dispatchPermitted: Boolean = false,
        var readingBack: Boolean = false,
        var nextReadAt: Long = 0
    )
    private var safety = VehicleSafety()
    private var epoch = 0L
    private var nextId = 0L
    private var closed = false
    private var invalidating = false
    private val entries = mutableMapOf<VehicleSetting, Entry>()
    private val pending = mutableMapOf<Long, Pending>()
    private val preparing = mutableMapOf<Long, Pair<VehicleCommand, Long>>()
    private val subscriptions = mutableMapOf<VehicleSetting, AutoCloseable>()

    companion object {
        /** Bounded human confirmation deadline, independent from the 3-second execution window. */
        const val CONFIRM_WINDOW_MILLIS = 30_000L
        const val OBSERVATION_WINDOW_MILLIS = 5_000L
        private const val READBACK_INTERVAL_MILLIS = 500L
    }

    fun updateSafety(next: VehicleSafety) {
        val losesTrust = next.generation != safety.generation ||
            next.blockedReason(nowMillis()) != null || safety.blockedReason(nowMillis()) != null
        safety = next
        if (losesTrust) {
            invalidate("Parking or UX trust lost", forgetSnapshots = true)
        }
    }

    fun blockedReason(): String? = when {
        closed -> "Adapter closed"
        invalidating -> "Commands being cancelled"
        else -> safety.blockedReason(nowMillis())
    }

    fun previewChange(setting: VehicleSetting, value: Double): Long = preview(setting, value, false)
    fun previewRestore(setting: VehicleSetting): Long = preview(setting, null, true)

    private fun preview(setting: VehicleSetting, desired: Double?, restore: Boolean): Long {
        val id = ++nextId
        val base = VehicleCommand(id, setting, desired ?: entries[setting]?.initial ?: Double.NaN,
            entries[setting]?.initial, CommandStage.MANUAL, "", restore)
        fun reject(reason: String) {
            preparing.remove(id)
            onCommand(base.copy(reason = reason))
        }
        blockedReason()?.let { reject(it); return id }
        if (setting.feature == VehicleFeature.SEAT_POSITION) {
            reject("Seat disabled: safe stop and OEM contract unverified")
            return id
        }
        if (restore && entries[setting]?.lastApplied == null) {
            reject("No verified app change to restore")
            return id
        }
        // One outstanding consent/command per area; never replace an in-flight setter.
        if (pending.values.any { it.command.setting == setting } ||
            preparing.values.any { it.first.setting == setting }) {
            reject("Setting already has a pending command")
            return id
        }
        val token = epoch
        val started = nowMillis()
        preparing[id] = base to started
        if (!subscriptions.containsKey(setting)) {
            subscriptions[setting] = port.subscribe(setting) { value, origin -> observe(setting, value, origin) }
        }
        port.inspect(setting) { result ->
            if (token != epoch || closed || !preparing.containsKey(id)) return@inspect
            val inspected = result.getOrElse { reject("Read/capability failed: ${it.message}"); return@inspect }
            blockedReason()?.let { reject(it); return@inspect }
            if (!VehicleSafety.fresh(started, nowMillis())) {
                reject("Preview read timed out")
                return@inspect
            }
            val capability = inspected.capability
            val target = desired ?: entries[setting]?.initial
            if (!capability.canRead || !capability.canWrite || target == null || !capability.accepts(target) ||
                !capability.accepts(inspected.value)) {
                reject("Manual mode: ${capability.reason}; unsupported value or area")
                return@inspect
            }
            val entry = entries.getOrPut(setting) { Entry(inspected.value) }
            if (restore && (entry.overridden || inspected.value != entry.lastApplied)) {
                entry.overridden = true
                reject("Manual change preserved; restoration disabled")
                return@inspect
            }
            val command = base.copy(target = target, initial = entry.initial, stage = CommandStage.PREVIEW,
                previewValue = inspected.value,
                reason = "Confirm ${if (restore) "restoration" else "change"}",
                requiresOriginConfirmation = restore && entry.unknownOrigin)
            preparing.remove(id)
            pending[id] = Pending(command, token, nowMillis(), inspected.value, entry.revision)
            onCommand(command)
        }
        return id
    }

    fun confirm(
        id: Long,
        consent: Boolean,
        confirmUnknownOrigin: Boolean = false,
        executionAllowed: () -> Boolean = { true }
    ) {
        val operation = pending[id] ?: return
        if (operation.command.stage != CommandStage.PREVIEW) return
        if (!consent || (operation.command.requiresOriginConfirmation && !confirmUnknownOrigin)) {
            finish(operation, CommandStage.FAILED, "Explicit consent required")
            return
        }
        if (!confirmationOpen(operation) || !executionAllowed()) {
            finish(operation, CommandStage.FAILED, "Consent or safety expired")
            return
        }
        operation.confirmedAt = nowMillis()
        operation.executionAllowed = executionAllowed
        report(operation, CommandStage.QUEUED, "Checking current capability and snapshot")
        port.inspect(operation.command.setting) { result ->
            if (!pending.containsKey(id)) return@inspect
            val inspected = result.getOrElse {
                finish(operation, CommandStage.FAILED, "Preflight failed: ${it.message}")
                return@inspect
            }
            val entry = entries[operation.command.setting]
            if (!valid(operation) || !inspected.capability.canRead || !inspected.capability.canWrite ||
                !inspected.capability.accepts(operation.command.target) ||
                inspected.value != operation.expectedCurrent || entry?.revision != operation.revision ||
                (operation.command.isRestore && entry.overridden)) {
                if (inspected.value != operation.expectedCurrent) entry?.overridden = true
                finish(operation, CommandStage.FAILED, "Current setting, capability or safety changed")
                return@inspect
            }
            port.write(operation.command.setting, operation.command.target, permit = {
                val allowed = pending[id] === operation && valid(operation) &&
                    entries[operation.command.setting]?.revision == operation.revision
                if (allowed) report(operation, CommandStage.REQUESTED, "Dispatch requested; application not yet verified")
                // onCommand may synchronously cancel, close, or report a manual/safety change.
                val finalPermit = allowed && pending[id] === operation && valid(operation) &&
                    entries[operation.command.setting]?.revision == operation.revision
                operation.dispatchPermitted = finalPermit
                finalPermit
            }) { result ->
                if (!pending.containsKey(id)) return@write
                result.fold(onSuccess = {
                    operation.command = operation.command.copy(requestAcknowledged = true)
                    if (!valid(operation)) {
                        finish(operation, CommandStage.UNKNOWN, "Acknowledgement arrived after execution expired")
                    } else {
                        operation.observingSince = nowMillis()
                        report(operation, CommandStage.REQUESTED, "Request acknowledged; reading actual setting")
                        readBack(operation)
                    }
                }, onFailure = {
                    finish(operation, if (it is VehicleCallbackTimeout) CommandStage.UNKNOWN else CommandStage.FAILED,
                        "Request failed: ${it.message}")
                })
            }
        }
    }

    private fun readBack(operation: Pending) {
        if (pending[operation.command.id] !== operation || operation.readingBack) return
        if (!valid(operation)) {
            finish(operation, CommandStage.UNKNOWN, "Observation expired or consent revoked")
            return
        }
        operation.readingBack = true
        port.inspect(operation.command.setting) readback@{ read ->
            if (pending[operation.command.id] !== operation) return@readback
            operation.readingBack = false
            val inspected = read.getOrNull()
            val actual = inspected?.value
            operation.command = operation.command.copy(readback = actual)
            if (!valid(operation) || inspected == null || !inspected.capability.canRead ||
                !inspected.capability.accepts(inspected.value) ||
                entries[operation.command.setting]?.revision != operation.revision) {
                finish(operation, CommandStage.UNKNOWN, "No matching fresh readback")
            } else if (actual == operation.command.target) {
                entries[operation.command.setting]?.let {
                    it.lastApplied = actual
                    // A matching independent read proves state, not who changed it.
                    it.unknownOrigin = true
                }
                finish(operation, CommandStage.READBACK, "Setting readback matches request")
            } else {
                operation.nextReadAt = nowMillis() + READBACK_INTERVAL_MILLIS
                report(operation, CommandStage.REQUESTED, "Waiting for observed value; no setter retry")
            }
        }
    }

    private fun observe(setting: VehicleSetting, value: Double?, origin: ChangeOrigin) {
        val entry = entries[setting] ?: return
        val requested = pending.values.firstOrNull {
            it.command.setting == setting && it.command.stage == CommandStage.REQUESTED
        }
        val expected = requested?.command?.target ?: entry.lastApplied ?: entry.initial
        if (origin == ChangeOrigin.MANUAL || value == null ||
            (requested?.dispatchPermitted != true && value != expected)) {
            entry.overridden = true
            entry.revision++
        }
        if (origin != ChangeOrigin.APP) entry.unknownOrigin = true
    }

    private fun confirmationOpen(operation: Pending): Boolean {
        val created = operation.created
        val now = nowMillis()
        return !closed && operation.epoch == epoch && operation.confirmedAt == null &&
            blockedReason() == null && now >= created && now - created <= CONFIRM_WINDOW_MILLIS
    }

    private fun valid(operation: Pending): Boolean {
        val confirmedAt = operation.confirmedAt ?: return false
        val now = nowMillis()
        val observingSince = operation.observingSince
        val withinDeadline = if (observingSince == null) VehicleSafety.fresh(confirmedAt, now)
            else now >= observingSince && now - observingSince <= OBSERVATION_WINDOW_MILLIS
        return !closed && operation.epoch == epoch && operation.executionAllowed() &&
            blockedReason() == null && withinDeadline
    }

    /** Call from a foreground heartbeat. Timeouts never become success or retries. */
    fun tick() {
        if (blockedReason() != null) {
            invalidate("Safety observation expired", forgetSnapshots = true)
            return
        }
        pending.values.toList().filter { !confirmationOpen(it) && !valid(it) }.forEach {
            finish(it, if (it.command.stage == CommandStage.REQUESTED) CommandStage.UNKNOWN else CommandStage.FAILED,
                "Command timed out; no retry")
        }
        pending.values.toList().filter {
            it.observingSince != null && !it.readingBack && nowMillis() >= it.nextReadAt
        }.forEach(::readBack)
        preparing.toMap().forEach { (id, waiting) ->
            if (!VehicleSafety.fresh(waiting.second, nowMillis())) {
                preparing.remove(id)
                onCommand(waiting.first.copy(stage = CommandStage.UNKNOWN, reason = "Preview read timed out"))
            }
        }
    }

    /** Normal finish keeps verified snapshots for a new, explicitly confirmed restore preview. */
    fun cancelPending() = invalidate("Cancelled; no replay", forgetSnapshots = false)

    private fun invalidate(reason: String, forgetSnapshots: Boolean) {
        ++epoch
        if (invalidating) return
        invalidating = true
        val preparingCopy = preparing.values.toList()
        preparing.clear()
        if (forgetSnapshots) entries.clear()
        preparingCopy.forEach { onCommand(it.first.copy(stage = CommandStage.FAILED, reason = reason)) }
        pending.values.toList().forEach {
            finish(it, if (it.command.stage == CommandStage.REQUESTED) CommandStage.UNKNOWN else CommandStage.FAILED, reason)
        }
        invalidating = false
    }

    private fun report(operation: Pending, stage: CommandStage, reason: String) {
        operation.command = operation.command.copy(stage = stage, reason = reason)
        onCommand(operation.command)
    }

    private fun finish(operation: Pending, stage: CommandStage, reason: String) {
        pending.remove(operation.command.id)
        report(operation, stage, reason)
    }

    override fun close() {
        if (closed) return
        closed = true
        invalidate("Adapter closed", forgetSnapshots = true)
        subscriptions.values.forEach { it.close() }
        subscriptions.clear()
        port.close()
    }
}
