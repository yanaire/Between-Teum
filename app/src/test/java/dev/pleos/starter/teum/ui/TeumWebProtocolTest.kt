package dev.pleos.starter.teum.ui

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.pleos.starter.teum.journey.*
import dev.pleos.starter.teum.preferences.TeumPreferences
import dev.pleos.starter.teum.preferences.*
import dev.pleos.starter.teum.location.*
import dev.pleos.starter.teum.vehicle.*
import dev.pleos.starter.teum.session.ClockReading
import dev.pleos.starter.teum.records.SpeechMeasurement
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class TeumWebProtocolTest {
    @Test fun backrestRequestsAcceptOnlyExplicitDirectionAndNativeTiming() {
        assertEquals(TeumWebAction.InspectBackrest,
            TeumWebProtocol.decode("""{"requestId":"s1","epoch":2,"action":"inspectBackrest"}""")?.action)
        assertEquals(TeumWebAction.StopBackrest,
            TeumWebProtocol.decode("""{"requestId":"s2","epoch":2,"action":"stopBackrest"}""")?.action)
        for (direction in BackrestDirection.entries) {
            assertEquals(TeumWebAction.PulseBackrest("0:ROW_1_LEFT", direction),
                TeumWebProtocol.decode("""{"requestId":"s3","epoch":2,"action":"pulseBackrest","area":"0:ROW_1_LEFT","direction":"${direction.name}"}""")?.action)
        }
        for (payload in listOf(
            """"area":"0:ROW_1_LEFT","direction":"STOP"""",
            """"area":"","direction":"FORWARD"""",
            """"area":"0:ROW_1_LEFT","direction":"FORWARD","pulseMillis":10000""",
            """"area":"0:ROW_1_LEFT","direction":"FORWARD","parked":true"""
        )) {
            assertNull(TeumWebProtocol.decode("""{"requestId":"s4","epoch":2,"action":"pulseBackrest",$payload}"""))
        }
    }

    @Test fun committedPreviousReceiptNeverSurvivesStateReplayOrMutation() {
        val f = JourneyFixture().apply {
            intention()
            send(JourneyRequest.SubmitIntention("Keep accepted text"))
        }
        val before = f.state
        val request = TeumWebRequest("previous-replay", 7,
            TeumWebAction.JourneyCommand(WebJourneyCommand.PREVIOUS),
            WebJourneyToken(before.sessionId, before.revision))
        val after = f.runtime.request(f.runtime.token, Previous).snapshot
        val receipt = requireNotNull(TeumWebProtocol.previousTransition(request, before, after))
        val state = project(f)
        val original = state.toString()
        val direct = TeumWebProtocol.stateEnvelope(state, 7, receipt)
        val replay = TeumWebProtocol.stateEnvelope(state, 7)
        assertFalse("A state-only replay must not carry the previous request", replay.has("previousTransition"))
        assertFalse("Cached state must not retain a receipt", replay.getAsJsonObject("state").has("previousTransition"))
        assertEquals(original, state.toString())
        assertEquals(setOf("type", "epoch", "state", "previousTransition"), direct.keySet())
        assertEquals(setOf("requestId", "requestEpoch", "journeyId", "fromRevision",
            "toRevision", "fromScreen", "toScreen"), direct.getAsJsonObject("previousTransition").keySet())
        assertEquals("previous-replay", direct.getAsJsonObject("previousTransition")["requestId"].asString)
        assertEquals(after.revision, direct.getAsJsonObject("previousTransition")["toRevision"].asLong)
        assertEquals("F1-04", direct.getAsJsonObject("previousTransition")["toScreen"].asString)
        assertEquals("Keep accepted text", after.facts.intention)
    }

    private fun previousRequest(before: JourneySnapshot) = TeumWebRequest("previous-" + before.revision, 7,
        TeumWebAction.JourneyCommand(WebJourneyCommand.PREVIOUS),
        WebJourneyToken(before.sessionId, before.revision))

    @Test fun receiptUsesRealCommittedBranchesAndCurrentNativeDestinations() {
        val fixtures = listOf(
            JourneyFixture().apply { send(JourneyRequest.Continue) },
            JourneyFixture(JourneyRoute.SHORT_MORNING),
            JourneyFixture().apply { intention(); send(JourneyRequest.SubmitIntention("Retained")); send(JourneyRequest.ConfirmIntention) },
            JourneyFixture().apply { intention(); send(JourneyRequest.SkipIntention) },
            JourneyFixture(JourneyRoute.NAP),
            JourneyFixture(JourneyRoute.PREPARATION),
            JourneyFixture(JourneyRoute.PREPARATION).apply {
                send(JourneyRequest.RecordSpeech)
                val capture = runtime.token
                clock.advance(100)
                send(JourneyRequest.SpeechDone)
                runtime.speechMeasured(capture, SpeechMeasurement(100, relativeLoudness = 0.25))
                send(JourneyRequest.SpeakAgain)
            },
            JourneyFixture(JourneyRoute.EVENING).apply {
                tick(6500); send(JourneyRequest.ChooseDayColor(JourneyAtmosphere.WARM))
                send(JourneyRequest.SubmitStory("Retained story")); send(ReviewDay)
            },
            JourneyFixture(JourneyRoute.SHORT_EVENING).apply {
                tick(6500); send(JourneyRequest.ChooseDayColor(JourneyAtmosphere.CALM))
            })
        for (f in fixtures) {
            val before = f.state
            val request = previousRequest(before)
            val after = f.runtime.request(f.runtime.token, Previous).snapshot
            val receipt = requireNotNull(TeumWebProtocol.previousTransition(request, before, after))
            val frame = TeumWebProtocol.stateEnvelope(project(f), request.epoch, receipt)
            val wire = frame.getAsJsonObject("previousTransition")
            assertEquals(before.sessionId, wire["journeyId"].asString)
            assertEquals(before.revision, wire["fromRevision"].asLong)
            assertEquals(before.revision + 1, wire["toRevision"].asLong)
            assertEquals(before.screenId, wire["fromScreen"].asString)
            assertEquals(after.screenId, wire["toScreen"].asString)
            assertEquals(after.facts, before.facts)
            assertEquals(before.completedMask, after.completedMask)
            println("NATIVE_PREVIOUS_RECEIPT {\"before\":${JourneySnapshotCodec.encode(before)},\"after\":${JourneySnapshotCodec.encode(after)},\"frame\":$frame}")
        }
    }

    @Test fun receiptCannotBeProducedByFailedUnsupportedOrStaleRequests() {
        val f = JourneyFixture().apply { send(JourneyRequest.Continue) }
        val before = f.state
        val raw = f.store.raw
        val request = previousRequest(before)
        f.clock.advance(99)
        f.store.failWrites = true
        assertThrows(java.io.IOException::class.java) { f.runtime.request(f.runtime.token, Previous) }
        assertEquals(before, f.state)
        assertEquals(raw, f.store.raw)
        assertNull(TeumWebProtocol.previousTransition(request, before, f.state))
        f.store.failWrites = false
        val accepted = f.runtime.request(f.runtime.token, Previous).snapshot
        assertTrue(TeumWebProtocol.previousTransition(request, before, accepted) != null)
        assertEquals(before.elapsedMs + 99, accepted.elapsedMs)
        assertThrows(JourneyRejected::class.java) {
            f.runtime.request(JourneyToken(before.sessionId, before.revision), Previous)
        }
        assertNull(TeumWebProtocol.previousTransition(request, accepted, f.state))
        val unsupported = JourneyFixture()
        val original = unsupported.state
        assertThrows(JourneyRejected::class.java) { unsupported.send(Previous) }
        assertNull(TeumWebProtocol.previousTransition(previousRequest(original), original, unsupported.state))
    }

    @Test fun receiptExcludesNonPreviousAndMismatchedNativeCorrelation() {
        val f = JourneyFixture()
        val before = f.state
        val after = f.send(JourneyRequest.Continue).snapshot
        val next = TeumWebRequest("next", 7, TeumWebAction.JourneyCommand(WebJourneyCommand.NEXT),
            WebJourneyToken(before.sessionId, before.revision))
        assertNull(TeumWebProtocol.previousTransition(next, before, after))
        val previous = previousRequest(after)
        val returned = f.send(Previous).snapshot
        for (request in listOf(previous.copy(action = TeumWebAction.ReadState),
            previous.copy(journeyToken = null),
            previous.copy(journeyToken = WebJourneyToken("another-owner", after.revision)),
            previous.copy(journeyToken = WebJourneyToken(after.sessionId, after.revision + 1)))) {
            assertNull(TeumWebProtocol.previousTransition(request, after, returned))
        }
    }

    @Test fun clockInterruptionIsNotAnAcceptedPreviousTransition() {
        val f = JourneyFixture().apply { send(JourneyRequest.Continue) }
        val before = f.state
        f.clock.monotonic = 0
        val interrupted = f.send(Previous).snapshot
        assertEquals(JourneyInterruption.CLOCK, interrupted.interruption)
        assertNull(TeumWebProtocol.previousTransition(previousRequest(before), before, interrupted))
        assertFalse(TeumWebProtocol.stateEnvelope(project(f), 7).has("previousTransition"))
    }

    @Test fun receiptEnvelopeRequiresCurrentEpochOwnerRevisionAndActualRoute() {
        val f = JourneyFixture().apply { send(JourneyRequest.Continue) }
        val before = f.state
        val after = f.send(Previous).snapshot
        val receipt = requireNotNull(TeumWebProtocol.previousTransition(previousRequest(before), before, after))
        val state = project(f)
        val wrongStates = listOf(
            state.deepCopy().apply { addProperty("screen", "H-02") },
            state.deepCopy().apply { addProperty("screen", "C-03") },
            state.deepCopy().apply { addProperty("journeyActive", false) },
            state.deepCopy().apply { remove("journeyToken") },
            state.deepCopy().apply { getAsJsonObject("journeyToken").addProperty("sessionId", "another-owner") },
            state.deepCopy().apply { getAsJsonObject("journeyToken").addProperty("revision", after.revision + 1) })
        for (wrong in wrongStates) {
            val encoded = TeumWebProtocol.stateEnvelope(wrong, 7, receipt)
            assertFalse(encoded.has("previousTransition"))
            assertEquals(wrong, encoded.getAsJsonObject("state"))
        }
        assertFalse(TeumWebProtocol.stateEnvelope(state, 8, receipt).has("previousTransition"))
        assertTrue(TeumWebProtocol.stateEnvelope(state, 7, receipt).has("previousTransition"))
        assertFalse(TeumWebProtocol.stateEnvelope(state, 7).has("previousTransition"))
    }

    @Test fun outboundReceiptFieldsCannotBeSuppliedAsPreviousIntents() {
        for (extra in listOf("\"previousTransition\":{}", "\"previousTransition\":\"invented\"",
            "\"requestEpoch\":7", "\"fromRevision\":1", "\"toRevision\":2",
            "\"fromScreen\":\"F1-04a\"", "\"toScreen\":\"F1-04\"")) {
            assertNull(TeumWebProtocol.decode("""{"requestId":"forged","epoch":7,"action":"journeyCommand","command":"PREVIOUS","journeyId":"native-session","journeyRevision":1,$extra}"""))
        }
    }

    @Test fun entrySelectionHasItsOwnStrictCorrelatedCommand() {
        val decoded = TeumWebProtocol.decode(
            """{"requestId":"entry","epoch":7,"action":"reconfigureEntry","choice":"MORNING_FULL","journeyId":"native-session","journeyRevision":3}""")
        assertEquals("ReconfigureEntry", decoded?.action?.javaClass?.simpleName)
        assertEquals(WebJourneyToken("native-session", 3), decoded?.journeyToken)
        for (correlation in listOf("", ""","journeyId":"native-session"""", ""","journeyRevision":3""")) {
            assertNull(TeumWebProtocol.decode(
                """{"requestId":"entry","epoch":7,"action":"reconfigureEntry","choice":"MORNING_FULL"$correlation}"""))
        }
    }

    @Test fun previousRequiresCompleteNativeJourneyCorrelation() {
        val decoded = TeumWebProtocol.decode(
            """{"requestId":"previous","epoch":7,"action":"journeyCommand","command":"PREVIOUS","journeyId":"native-session","journeyRevision":3}""")
        assertEquals("PREVIOUS", (decoded?.action as? TeumWebAction.JourneyCommand)?.command?.name)
        assertEquals(WebJourneyToken("native-session", 3), decoded?.journeyToken)
        for (correlation in listOf("", ""","journeyId":"native-session"""", ""","journeyRevision":3""")) {
            assertNull(TeumWebProtocol.decode(
                """{"requestId":"previous","epoch":7,"action":"journeyCommand","command":"PREVIOUS"$correlation}"""))
        }
    }

    @Test fun arrivalAdmissionAndOriginStayDistinctFromJourneyCorrelation() {
        assertEquals(TeumWebAction.AdmitArrival("native-nonce"),
            TeumWebProtocol.decode("""{"requestId":"a","epoch":7,"action":"admitArrival","nonce":"native-nonce"}""")?.action)
        val token = "2026-11-10/Asia/Seoul/MORNING/1/12345"
        val request = TeumWebProtocol.decode(
            """{"requestId":"a","epoch":7,"action":"openJourney","journey":"am","arrivalToken":"$token"}""")
        assertEquals(token, request?.arrivalToken)
        assertNull(request?.journeyToken)
        assertEquals(TeumWebAction.OpenJourney(WebJourney.MORNING), request?.action)
    }

    @Test fun malformedAdmissionAndArrivalTokensCannotCrossTheBoundary() {
        for (payload in listOf(
            """"action":"admitArrival","nonce":"" """,
            """"action":"admitArrival","nonce":"x","parked":true""",
            """"action":"admitArrival","nonce":"${"a".repeat(65)}" """,
            """"action":"openJourney","journey":"am","arrivalToken":"" """,
            """"action":"openJourney","journey":"am","arrivalToken":"<script>" """,
            """"action":"openJourney","journey":"am","arrivalToken":"${"a".repeat(257)}" """
        )) assertNull(payload, TeumWebProtocol.decode("""{"requestId":"a","epoch":7,$payload}"""))
    }

    @Test
    fun recordInputRetainsLiteralUserTextAndExplicitConsent() {
        val raw = """{"requestId":"r-1","epoch":7,"action":"saveRecord","note":"<img src=x> 오늘","nextAction":"메일 보내기","recall":false}"""
        val decoded = TeumWebProtocol.decode(raw)
        assertEquals(
            TeumWebRequest("r-1", 7, TeumWebAction.SaveRecord("<img src=x> 오늘", "메일 보내기", false)),
            decoded
        )
    }

    @Test
    fun unknownActionsAndForgedNativeFactsAreRejected() {
        val payloads = listOf(
            """{"requestId":"x","epoch":1,"action":"setGear","value":0}""",
            """{"requestId":"x","epoch":1,"action":"state","parked":true}""",
            """{"requestId":"x","epoch":1,"action":"approveRestore","consent":"true"}""",
            """{"requestId":"x","epoch":1,"action":"previewVehicle","feature":"WINDOW","value":2}""",
            """{"requestId":"x","epoch":1,"action":"previewVehicle","feature":"HVAC_TEMPERATURE","value":1e999}""",
            """{"requestId":"x","epoch":1,"action":"navigate","route":"javascript:alert(1)"}""",
            """{"requestId":"x","epoch":1,"action":"selectDay","date":"2026-02-30"}""",
            """{"requestId":"x","epoch":1,"action":"selectDay","date":"2026-11-10T12:00:00"}"""
        )
        payloads.forEach { assertNull(it, TeumWebProtocol.decode(it)) }
    }

    @Test
    fun malformedDuplicateNestedAndTrailingJsonAreRejected() {
        val payloads = listOf(
            """{"requestId":"x","epoch":1,"action":"state","action":"clearRecords"}""",
            """{"requestId":"x","epoch":1,"action":"state",}""",
            """{"requestId":"x","epoch":1,"action":"state"}{}""",
            """{"requestId":"x","epoch":1,"action":"state","payload":{"action":"clearRecords"}}""",
            """{"requestId":"x","epoch":1 "action":"state"}"""
        )
        payloads.forEach { assertNull(it, TeumWebProtocol.decode(it)) }
    }

    @Test
    fun fractionalWrappedAndNegativeEpochsAreRejected() {
        listOf("1.5", "1e3", "9223372036854775808", "-1").forEach { epoch ->
            assertNull(TeumWebProtocol.decode("""{"requestId":"x","epoch":$epoch,"action":"state"}"""))
        }
    }

    @Test
    fun utf8LimitRejectsPayloadWhoseCharacterCountStillFits() {
        val raw = " ".repeat(29_000) +
            """{"requestId":"x","epoch":1,"action":"saveRecord","note":"${"가".repeat(2000)}","nextAction":"","recall":false}"""
        assertEquals(true, raw.length < 32_768)
        assertNull(TeumWebProtocol.decode(raw))
    }

    @Test
    fun journeyIdentityAndPreviewTokenRemainDistinctTypedIntents() {
        assertEquals(
            TeumWebAction.OpenJourney(WebJourney.QUICK),
            TeumWebProtocol.decode("""{"requestId":"q","epoch":2,"action":"openJourney","journey":"quick"}""")?.action
        )
        assertEquals(
            TeumWebAction.InspectVehicle(WebVehicleFeature.AMBIENT_BRIGHTNESS),
            TeumWebProtocol.decode("""{"requestId":"v","epoch":2,"action":"inspectVehicle","feature":"AMBIENT_BRIGHTNESS"}""")?.action
        )
        assertEquals(
            TeumWebAction.InspectVehicle(WebVehicleFeature.WINDOW_POSITION),
            TeumWebProtocol.decode("""{"requestId":"w","epoch":2,"action":"inspectVehicle","feature":"WINDOW_POSITION"}""")?.action
        )
        assertEquals(
            TeumWebAction.ConfirmVehicle(42),
            TeumWebProtocol.decode("""{"requestId":"v","epoch":2,"action":"confirmVehicle","previewId":42}""")?.action
        )
        assertNull(
            TeumWebProtocol.decode("""{"requestId":"v","epoch":2,"action":"inspectVehicle","feature":"WINDOW"}""")
        )
    }

    @Test
    fun vehicleFeatureNamesStayAlignedWithTheNativeContract() {
        assertEquals(VehicleFeature.entries.map { it.name }.sorted(),
            WebVehicleFeature.entries.map { it.name }.sorted())
    }

    @Test
    fun vehicleInspectDecodesEverySupportedFeatureAndItsOptionalArea() {
        for (feature in WebVehicleFeature.entries) {
            assertEquals(
                TeumWebAction.InspectVehicle(feature),
                TeumWebProtocol.decode(
                    """{"requestId":"v","epoch":2,"action":"inspectVehicle","feature":"${feature.name}"}""")?.action
            )
            assertEquals(
                TeumWebAction.InspectVehicle(feature, "ROW_1_LEFT"),
                TeumWebProtocol.decode(
                    """{"requestId":"v","epoch":2,"action":"inspectVehicle","feature":"${feature.name}","area":"ROW_1_LEFT"}""")?.action
            )
        }
        assertEquals(
            TeumWebAction.PreviewVehicle(WebVehicleFeature.AMBIENT_COLOR, -16_201_420.0),
            TeumWebProtocol.decode(
                """{"requestId":"v","epoch":2,"action":"previewVehicle","feature":"AMBIENT_COLOR","value":-16201420}""")?.action
        )
        for (unknown in listOf("AMBIENT", "SEAT_MOVEMENT", "GLASS", "SMART_GLASS", "WINDOW", "")) {
            assertNull(unknown, TeumWebProtocol.decode(
                """{"requestId":"v","epoch":2,"action":"inspectVehicle","feature":"$unknown"}"""))
        }
    }

    @Test
    fun vehicleAreaIsBoundedPrintableAndOnlyUsedForInspection() {
        val maximum = "A".repeat(256)
        assertEquals(
            TeumWebAction.InspectVehicle(WebVehicleFeature.SMART_GLASS_LEVEL, maximum),
            TeumWebProtocol.decode(
                """{"requestId":"v","epoch":2,"action":"inspectVehicle","feature":"SMART_GLASS_LEVEL","area":"$maximum"}""")?.action
        )
        fun inspect(area: String) =
            "{\"requestId\":\"v\",\"epoch\":2,\"action\":\"inspectVehicle\",\"feature\":\"WINDOW_POSITION\",\"area\":$area}"
        for (area in listOf("\"\"", "\"" + "A".repeat(257) + "\"",
            "\"ROW\\u0000LEFT\"", "\"ROW\\nLEFT\"", "7", "null")) {
            assertNull(area, TeumWebProtocol.decode(inspect(area)))
        }
        assertNull(TeumWebProtocol.decode(
            """{"requestId":"v","epoch":2,"action":"previewVehicle","feature":"WINDOW_POSITION","value":50,"area":"ROW_1_LEFT"}"""))
        assertNull(TeumWebProtocol.decode(
            """{"requestId":"v","epoch":2,"action":"confirmVehicle","previewId":4,"area":"ROW_1_LEFT"}"""))
    }

    @Test
    fun preferenceUpdatesDistinguishUnsetScheduleAndIndependentDesignerAngles() {
        assertEquals(
            TeumWebAction.SaveSchedule(null, 780, 7),
            TeumWebProtocol.decode("""{"requestId":"s","epoch":2,"action":"saveSchedule","workStartMinute":-1,"lunchReturnMinute":780,"walkingReserveMinutes":7}""")?.action
        )
        assertEquals(
            TeumWebAction.SaveBackrest(110, 150),
            TeumWebProtocol.decode("""{"requestId":"s","epoch":2,"action":"saveBackrest","restDegrees":110,"napDegrees":150}""")?.action
        )
        val invalid = listOf(
            """"action":"saveSchedule","workStartMinute":1440,"lunchReturnMinute":780,"walkingReserveMinutes":7""",
            """"action":"saveSchedule","workStartMinute":-2,"lunchReturnMinute":780,"walkingReserveMinutes":7""",
            """"action":"saveSchedule","workStartMinute":530,"lunchReturnMinute":780,"walkingReserveMinutes":11""",
            """"action":"saveBackrest","restDegrees":105,"napDegrees":150""",
            """"action":"saveBackrest","restDegrees":110,"napDegrees":161""",
            """"action":"selectSound","sound":"REMOTE_MUSIC"""",
            """"action":"setPreferenceFlag","flag":"PARKED","enabled":true""",
            """"action":"requestPermission","permission":"CONTROL_CAR_SEATS"""",
            """"action":"journeyCommand","command":"COMPLETE_WITHOUT_TIMER"""",
            """"action":"journeyInput","field":"ELAPSED_SECONDS","value":"420""""
        )
        invalid.forEach {
            assertNull(it, TeumWebProtocol.decode("""{"requestId":"s","epoch":2,$it}"""))
        }
    }

    @Test
    fun journeyCorrelationRequiresACompleteBoundedNativePhaseToken() {
        assertEquals(
            WebJourneyToken("native-session-7", 3),
            TeumWebProtocol.decode("""{"requestId":"x","epoch":1,"action":"journeyCommand","command":"NEXT","journeyId":"native-session-7","journeyRevision":3}""")?.journeyToken
        )
        val tokens = listOf(
            """"journeyId":"native-session-7"""",
            """"journeyRevision":3""",
            """"journeyId":"native-session-7","journeyRevision":-1""",
            """"journeyId":"native-session-7","journeyRevision":3.0""",
            """"journeyId":"","journeyRevision":3"""
        )
        tokens.forEach {
            assertNull(TeumWebProtocol.decode("""{"requestId":"x","epoch":1,"action":"journeyCommand","command":"NEXT",$it}"""))
        }
    }

    @Test fun previousCannotCarryBrowserDestinationsOrNativeFacts() {
        for (forged in listOf(
            """"route":"F1-01"""", """"phase":"MORNING_GREETING"""",
            """"elapsedMs":180000""", """"completedMask":63""",
            """"status":"COMPLETED"""", """"firstSpeech":"invented""""
        )) assertNull(TeumWebProtocol.decode(
            """{"requestId":"previous","epoch":7,"action":"journeyCommand","command":"PREVIOUS","journeyId":"native-session","journeyRevision":3,$forged}"""))
    }

    private fun project(f: JourneyFixture, arrival: ArrivalSuggestion? = null, remainingMs: Long? = f.runtime.remainingMs): JsonObject {
        val zone = ZoneId.of("UTC")
        val day = Instant.ofEpochMilli(f.clock.wall).atZone(zone).toLocalDate()
        val data = JsonParser.parseString(
            """{"records":[{"id":"saved-morning","journeyFacts":{"kind":"MORNING","intention":"Earlier saved intention"}}],"week":{"days":[{"date":"$day","completedJourneys":["MORNING"],"recordIds":["saved-morning"]}]}}""").asJsonObject
        return TeumWebProjection().project(data,
            if (f.state.active) requireNotNull(TeumWebRoute.fromId(f.state.screenId)) else TeumWebRoute.C_03,
            7, f.clock.wall, zone, TeumPreferences(), f.state, remainingMs,
            day, emptySet(), null, 20, "light", JsonObject(), JsonObject(), "unit-fixture",
            JsonObject(), null, null, arrival)
    }

    @Test fun projectionPublishesNativeLocalClockForTheCurrentEndpoint() {
        val f = JourneyFixture()
        assertEquals("08:00", project(f)["localNow"].asString)
        f.clock.advance(42 * 60_000)
        assertEquals("08:42", project(f)["localNow"].asString)
    }

    @Test fun projectionPublishesTheOwnedEntryPlanWithoutInventingAnOffer() {
        for (route in listOf(JourneyRoute.SHORT_MORNING, JourneyRoute.NAP,
            JourneyRoute.PREPARATION, JourneyRoute.EVENING, JourneyRoute.SHORT_EVENING)) {
            val f = JourneyFixture(route)
            f.clock.advance(2345)
            f.send(Previous)
            val projected = project(f)
            assertEquals("ENTRY_REVIEW", projected["journeyPhase"].asString)
            assertTrue(projected["journeyActive"].asBoolean)
            val entry = projected.getAsJsonObject("entry")
            assertTrue(entry["reviewing"].asBoolean)
            assertEquals(route.name, entry["plan"].asString)
            assertFalse(entry["canChangePlan"].asBoolean)
            assertEquals(2L, entry["elapsedSeconds"].asLong)
            assertEquals(f.state.sessionId, projected.getAsJsonObject("journeyToken")["sessionId"].asString)
            assertFalse(projected.has("arrival"))
            f.runtime.interrupt(JourneyInterruption.BACKGROUND)
            val stopped = project(f)
            assertFalse(stopped["journeyActive"].asBoolean)
            assertFalse(stopped.getAsJsonObject("entry")["reviewing"].asBoolean)
            assertEquals("C-03", stopped["screen"].asString)
        }
    }

    @Test fun musicSelectionRoundUsesAcceptedMeasurementsWithoutMislabelingFirstCapture() {
        for (rounds in listOf(1, 2)) {
            val f = JourneyFixture(JourneyRoute.PREPARATION)
            repeat(rounds) { index ->
                if (index == 1) f.send(JourneyRequest.SpeakAgain)
                f.send(JourneyRequest.RecordSpeech)
                val capture = f.runtime.token
                f.clock.advance(1000)
                f.send(JourneyRequest.SpeechDone)
                f.runtime.speechMeasured(capture, SpeechMeasurement(1000))
            }
            f.send(JourneyRequest.Continue)
            val selection = project(f)
            assertEquals("F3-06", selection["screen"].asString)
            assertEquals(rounds, selection["round"].asInt)
            val after = f.send(Previous).snapshot
            assertEquals(if (rounds == 2) JourneyPhase.SPEECH_COMPARISON
                else JourneyPhase.SPEECH_FIRST_REVIEW, after.phase)
            if (rounds == 2) { f.send(Previous); f.send(Previous) }
            f.send(Previous)
            assertEquals(JourneyPhase.SPEECH_FIRST, f.state.phase)
            assertEquals(1, project(f)["round"].asInt)
            assertEquals(rounds == 2, f.state.facts.secondSpeech != null)
        }
    }

    @Test fun missingCurrentIntentionDoesNotBorrowAnEarlierSavedRecognition() {
        val f = JourneyFixture().apply { intention(); send(JourneyRequest.SkipIntention) }
        assertTrue(project(f)["intention"].isJsonNull)
        f.send(Previous)
        val projected = project(f)
        assertEquals("F1-04", projected["screen"].asString)
        assertTrue(projected["intention"].isJsonNull)
        assertTrue(projected["transcript"].isJsonNull)
    }

    private fun entrySafety(time: Long) = VehicleSafety(true,
        ParkingObservation(Parking.PARKED, time), UxObservation(false, 0, time), 7)

    private val entryZone = ZoneId.of("UTC")
    private val entryPreferences = TeumPreferences(schedule = DailySchedule(9 * 60, 13 * 60, 3))

    @Test fun entryCommandRejectsInventedControlsAndBrowserSuppliedNativeState() {
        for (choice in EntryChoice.entries) {
            val decoded = TeumWebProtocol.decode(
                """{"requestId":"entry","epoch":7,"action":"reconfigureEntry","choice":"${choice.name}","journeyId":"native-session","journeyRevision":3}""")
            assertEquals(TeumWebAction.ReconfigureEntry(choice), decoded?.action)
        }
        for (extra in listOf(""""route":"MORNING"""", """"deadline":9999999999999""",
            """"parked":true""", """"historicalSteps":"EVENING_STORY"""", """"elapsedMs":600000""",
            """"completedMask":63""", """"nativeActivityId":"other"""")) {
            assertNull(TeumWebProtocol.decode(
                """{"requestId":"entry","epoch":7,"action":"reconfigureEntry","choice":"MORNING_FULL","journeyId":"native-session","journeyRevision":3,$extra}"""))
        }
        for (choice in listOf("NAP_11", "SOUND_RAIN", "MEETING_TIME", "SEAT_ANGLE", "FULL_QUICK", "")) {
            assertNull(TeumWebProtocol.decode(
                """{"requestId":"entry","epoch":7,"action":"reconfigureEntry","choice":"$choice","journeyId":"native-session","journeyRevision":3}"""))
        }
    }

    @Test fun actualEntryControlsUseBoundedPlansAndGenuineKnownDeadlineInputs() {
        for (route in listOf(JourneyRoute.SHORT_MORNING, JourneyRoute.NAP, JourneyRoute.PREPARATION,
            JourneyRoute.EVENING, JourneyRoute.SHORT_EVENING)) {
            val f = JourneyFixture(route).apply { send(Previous) }
            val meeting = MeetingFact("chosen", Instant.ofEpochMilli(f.clock.wall + 1_200_000), entryZone)
            for (choice in f.state.plan.entryChoices) {
                assertEquals(choice.plan, validateEntryChoice(f.state, choice, entryPreferences, meeting,
                    f.clock.reading(), entrySafety(f.clock.monotonic), entryZone))
            }
            for (choice in EntryChoice.entries.filter { it !in f.state.plan.entryChoices }) {
                assertThrows(IllegalArgumentException::class.java) {
                    validateEntryChoice(f.state, choice, entryPreferences, meeting, f.clock.reading(),
                        entrySafety(f.clock.monotonic), entryZone)
                }
            }
        }
    }

    @Test fun entrySelectionRequiresRemainingTimeAtTheExecutionBoundary() {
        for (expiredBy in listOf(0L, 1L)) {
            val f = JourneyFixture(JourneyRoute.PREPARATION).apply { send(Previous) }
            val meeting = MeetingFact("actual-selected-meeting",
                Instant.ofEpochMilli(f.clock.wall + 183_000), entryZone)
            f.clock.advance(expiredBy)
            val before = f.state
            val raw = f.store.raw
            if (expiredBy == 0L) {
                assertEquals(JourneyPlan(JourneyRoute.QUICK_BREATHING),
                    validateEntryChoice(f.state, EntryChoice.QUICK, entryPreferences, meeting,
                        f.clock.reading(), entrySafety(f.clock.monotonic), entryZone))
            } else assertThrows(IllegalArgumentException::class.java) {
                validateEntryChoice(f.state, EntryChoice.QUICK, entryPreferences, meeting,
                    f.clock.reading(), entrySafety(f.clock.monotonic), entryZone)
            }
            assertEquals(before, f.state)
            assertEquals(raw, f.store.raw)
        }
    }

    @Test fun entrySelectionRejectsMissingExpiredAmbiguousAndUnsafeNativeFacts() {
        val f = JourneyFixture(JourneyRoute.SHORT_MORNING).apply { send(Previous) }
        val safe = entrySafety(f.clock.monotonic)
        for (settings in listOf(TeumPreferences(),
            entryPreferences.copy(schedule = DailySchedule(9 * 60, 13 * 60, null)),
            entryPreferences.copy(schedule = DailySchedule(7 * 60, 13 * 60, 3)),
            entryPreferences.copy(schedule = entryPreferences.schedule.copy(workFromHomeDays =
                setOf(Instant.ofEpochMilli(f.clock.wall).atZone(entryZone).dayOfWeek))))) {
            assertThrows(IllegalArgumentException::class.java) {
                validateEntryChoice(f.state, EntryChoice.MORNING_FULL, settings, null, f.clock.reading(), safe, entryZone)
            }
        }
        for (unsafe in listOf(VehicleSafety(), safe.copy(connected = false),
            entrySafety(f.clock.monotonic - 3001), safe.copy(ux = UxObservation(true, 1, f.clock.monotonic)))) {
            assertThrows(IllegalStateException::class.java) {
                validateEntryChoice(f.state, EntryChoice.MORNING_FULL, entryPreferences, null, f.clock.reading(), unsafe, entryZone)
            }
        }
        for ((instant, minute) in listOf("2030-03-10T06:00:00Z" to 150, "2030-11-03T04:00:00Z" to 90)) {
            assertThrows(IllegalArgumentException::class.java) {
                validateEntryChoice(f.state, EntryChoice.MORNING_FULL,
                    entryPreferences.copy(schedule = DailySchedule(minute, null, 3)), null,
                    ClockReading(Instant.parse(instant).toEpochMilli(), f.clock.monotonic), safe,
                    ZoneId.of("America/New_York"))
            }
        }
        val prep = JourneyFixture(JourneyRoute.PREPARATION).apply { send(Previous) }
        for (meeting in listOf(null, MeetingFact("wrong-zone",
            Instant.ofEpochMilli(prep.clock.wall + 1_200_000), ZoneId.of("Asia/Seoul")))) {
            assertThrows(IllegalArgumentException::class.java) {
                validateEntryChoice(prep.state, EntryChoice.PREPARATION, entryPreferences, meeting,
                    prep.clock.reading(), entrySafety(prep.clock.monotonic), entryZone)
            }
        }
        // The original evening controls choose a duration; no deadline is fabricated for them.
        val evening = JourneyFixture(JourneyRoute.EVENING).apply { send(Previous) }
        assertEquals(JourneyPlan(JourneyRoute.SHORT_EVENING),
            validateEntryChoice(evening.state, EntryChoice.EVENING_SHORT, TeumPreferences(), null,
                evening.clock.reading(), entrySafety(evening.clock.monotonic), entryZone))
    }

    @Test fun projectionPublishesSelectedValuesWithoutCallingThePlanDurationAvailableTime() {
        val f = JourneyFixture(JourneyRoute.NAP).apply { send(Previous) }
        fun summaryNumbers(state: JsonObject) = Regex("\\d+")
            .findAll(state.getAsJsonObject("context")["napSummary"].asString)
            .map { it.value.toInt() }.toList()
        for ((choice, minutes) in listOf(EntryChoice.NAP_10 to 10, EntryChoice.NAP_15 to 15, EntryChoice.NAP_20 to 20)) {
            f.send(ReconfigureEntry(choice))
            val projected = project(f)
            val entry = projected.getAsJsonObject("entry")
            assertTrue(entry["canReconfigure"].asBoolean)
            assertFalse(entry["canChangePlan"].asBoolean)
            assertEquals(choice.name, entry["selectedChoice"].asString)
            assertEquals(minutes, entry["napMinutes"].asInt)
            assertEquals(minutes, entry["minutes"].asInt)
            assertEquals(minutes, projected["sleep"].asInt)
            assertTrue(projected.getAsJsonObject("context")["availableMinutes"].isJsonNull)
            assertEquals(listOf(minutes), summaryNumbers(projected))
            val arrival = ArrivalSuggestion("test", JourneyNotification.NAP, "2030-01-07", 1, 0.0, 35, 1f)
            val offered = project(f, arrival)
            assertEquals(35L, offered.getAsJsonObject("context")["availableMinutes"].asLong)
            assertEquals(listOf(35, minutes), summaryNumbers(offered))
            val timing = project(f, arrival, 12_345)
            assertEquals(listOf(12), summaryNumbers(timing))
            assertEquals(12L, timing.getAsJsonObject("context")["napRemainingSeconds"].asLong)
        }
    }

    @Test fun actualT9LedgerDeclinesAlternativesButRetainsMatchingKindCorrelation() {
        data class Case(val slot: JourneyNotification, val route: JourneyRoute, val choice: EntryChoice, val decline: Boolean)
        val cases = listOf(
            Case(JourneyNotification.MORNING, JourneyRoute.SHORT_MORNING, EntryChoice.MORNING_FULL, true),
            Case(JourneyNotification.PREPARATION, JourneyRoute.PREPARATION, EntryChoice.QUICK, true),
            Case(JourneyNotification.PREPARATION, JourneyRoute.PREPARATION, EntryChoice.PREPARATION, false),
            Case(JourneyNotification.NAP, JourneyRoute.NAP, EntryChoice.NAP_15, false),
            Case(JourneyNotification.EVENING, JourneyRoute.EVENING, EntryChoice.EVENING_SHORT, false),
            Case(JourneyNotification.EVENING, JourneyRoute.SHORT_EVENING, EntryChoice.EVENING_FULL, false)
        )
        for (case in cases) {
            val clock = TestJourneyClock().apply {
                wall = Instant.parse(if (case.slot == JourneyNotification.EVENING)
                    "2030-01-07T18:00:00Z" else "2030-01-07T08:00:00Z").toEpochMilli()
            }
            val runtime = JourneyRuntime(clock, TestJourneyStore())
            runtime.start(JourneyPlan(case.route))
            runtime.request(runtime.token, Previous)
            val point = PlaceCoordinates(37.0, 127.0)
            val settings = TeumPreferences(company = SavedPlace("Synthetic office", point),
                home = SavedPlace("Synthetic home", point), schedule = when (case.slot) {
                    JourneyNotification.MORNING -> DailySchedule(8 * 60 + 40, null, 3)
                    JourneyNotification.NAP -> DailySchedule(null, 8 * 60 + 40, 3)
                    else -> DailySchedule()
                })
            val ledger = object : ArrivalLedgerStore {
                var value = ArrivalLedger()
                override fun load() = value
                override fun persist(ledger: ArrivalLedger) { value = ledger }
            }
            val policy = ForegroundArrivalPolicy(PreferencesRepository(InMemoryPreferenceStore(settings)),
                ledger, Clock.fixed(Instant.ofEpochMilli(clock.wall), entryZone))
            val meeting = if (case.slot == JourneyNotification.PREPARATION)
                MeetingFact("explicit", Instant.ofEpochMilli(clock.wall + 1_200_000), entryZone) else null
            val offered = (policy.evaluate(ArrivalFacts(true,
                LocationState.Ready(LocationFix(37.0, 127.0, 0.7f, clock.wall)),
                entrySafety(clock.monotonic), BrakeFact(true, clock.monotonic, 7),
                clock.monotonic, entryZone, meeting)) as ArrivalDecision.Offer).suggestion
            assertEquals(case.slot, offered.slot)
            val before = requireNotNull(runtime.state)
            assertEquals(case.choice.plan, validateEntryChoice(before, case.choice, settings, meeting,
                clock.reading(), entrySafety(clock.monotonic), entryZone))
            val decline = entryDeclinesArrival(offered, before.plan, case.choice.plan)
            assertEquals(case.decline, decline)
            if (decline) assertTrue(policy.resolve(offered, SuggestionOutcome.DECLINED))
            val after = runtime.request(runtime.token, ReconfigureEntry(case.choice)).snapshot
            assertEquals(before.sessionId, after.sessionId)
            assertEquals(if (decline) SuggestionOutcome.DECLINED else SuggestionOutcome.PRESENTED,
                ledger.value.entries[case.slot]?.outcome)
            assertEquals(if (decline) clock.wall + ForegroundArrivalPolicy.COOLDOWN_MILLIS else 0L,
                ledger.value.cooldownUntilMillis)
            assertFalse(ledger.value.entries.values.any { it.outcome == SuggestionOutcome.COMPLETED })
            println("NATIVE_ENTRY_FACT {\"case\":\"T9-${case.slot}-${case.choice}\",\"outcome\":\"${ledger.value.entries[case.slot]?.outcome}\",\"before\":${JourneySnapshotCodec.encode(before)},\"after\":${JourneySnapshotCodec.encode(after)}}")
        }
    }
}
