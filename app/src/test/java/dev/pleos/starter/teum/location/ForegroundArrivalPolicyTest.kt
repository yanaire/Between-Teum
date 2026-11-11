package dev.pleos.starter.teum.location

import dev.pleos.starter.teum.preferences.*
import dev.pleos.starter.teum.vehicle.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ForegroundArrivalPolicyTest {
    @Test fun unadmittedEvaluationCannotConsumeDailyPresentation() {
        val store = Store()
        val engine = policy(store)
        suppressed(engine.evaluate(facts(), allowNewOffer = false), "Presentation not admitted")
        assertTrue(store.value.entries.values.none { it.token.isNotEmpty() })
        assertTrue(engine.evaluate(facts()) is ArrivalDecision.Offer)
    }

    @Test fun refreshWithoutAdmissionCannotMintAnotherSlot() {
        val store = Store()
        val engine = policy(store)
        val original = offer(engine.evaluate(facts()))
        assertEquals(original.token, offer(engine.evaluate(facts(), false)).token)
        val next = facts().copy(meeting = MeetingFact("selected", instant.plusSeconds(1200), zone))
        suppressed(engine.evaluate(next, false), "Presentation not admitted")
        assertFalse(store.value.entries.containsKey(JourneyNotification.PREPARATION))
    }

    @Test fun excludedMorningCannotConsumeTokenOrBlockMeetingPriority() {
        val store = Store()
        val engine = policy(store)
        val excluded = facts().copy(excludedSlots = setOf(JourneyNotification.MORNING))
        suppressed(engine.evaluate(excluded), "Slot already completed or skipped")
        assertTrue(store.value.entries.isEmpty())
        assertEquals(JourneyNotification.PREPARATION, offer(engine.evaluate(
            excluded.copy(meeting = MeetingFact("selected", instant.plusSeconds(1200), zone)))).slot)
    }

    private val zone = ZoneId.of("Asia/Seoul")
    private val instant = Instant.parse("2026-11-10T23:00:00Z") // Wednesday 08:00
    private val now = instant.toEpochMilli()
    private val elapsed = 500_000L
    private val settings = TeumPreferences(
        company = SavedPlace("Office", PlaceCoordinates(37.0, 127.0)),
        home = SavedPlace("Home", PlaceCoordinates(38.0, 128.0)),
        schedule = DailySchedule(8 * 60 + 40, 13 * 60, 5)
    )

    private class Store : ArrivalLedgerStore {
        var value = ArrivalLedger()
        override fun load() = value
        override fun persist(ledger: ArrivalLedger) { value = ledger }
    }

    private fun repository(value: TeumPreferences = settings) =
        PreferencesRepository(InMemoryPreferenceStore(value))

    private fun safety(time: Long = elapsed) = VehicleSafety(
        true, ParkingObservation(Parking.PARKED, time),
        UxObservation(false, 0, time), 7
    )

    private fun facts(time: Long = now) = ArrivalFacts(
        true, LocationState.Ready(LocationFix(37.0, 127.0, 0.7f, time)),
        safety(elapsed + time - now), BrakeFact(true, elapsed + time - now, 7),
        elapsed + time - now, zone
    )

    private fun policy(store: Store = Store(), clock: Clock = Clock.fixed(instant, zone)) =
        ForegroundArrivalPolicy(repository(), store, clock)

    private fun offer(decision: ArrivalDecision) =
        (decision as ArrivalDecision.Offer).suggestion

    private fun suppressed(decision: ArrivalDecision, reason: String) =
        assertEquals(reason, (decision as ArrivalDecision.Suppressed).reason)

    @Test fun freshOfficeFixOffersActualRemainingTimeWithoutPrecisionClaim() {
        val result = offer(policy().evaluate(facts()))
        assertEquals(JourneyNotification.MORNING, result.slot)
        assertEquals(35L, result.availableMinutes)
        assertEquals(0.0, result.distanceMeters, 0.000001)
        assertFalse(result.precisionVerified)
    }

    @Test fun activeOfferKeepsTokenAndRecreationSuppressesDuplicate() {
        val store = Store()
        val engine = policy(store)
        val first = offer(engine.evaluate(facts()))
        assertEquals(first.token, offer(engine.evaluate(facts())).token)
        suppressed(policy(store).evaluate(facts()), "Already presented today")
    }

    @Test fun lifecycleStopCancelsOfferWithoutReofferingOnResume() {
        val engine = policy()
        engine.evaluate(facts())
        suppressed(engine.evaluate(facts().copy(foreground = false)), "Background")
        suppressed(engine.evaluate(facts()), "Already presented today")
    }

    @Test fun deniedMissingAndInvalidLocationsNeverOffer() {
        val states = listOf(
            LocationState.PermissionDenied, LocationState.Missing, LocationState.Unsupported,
            LocationState.Waiting, LocationState.Stopped,
            LocationState.Failed(SecurityException("privacy unknown")),
            LocationState.Invalid(FixError.CONFIDENCE)
        )
        states.forEach { suppressed(policy().evaluate(facts().copy(location = it)), "Location unavailable") }
    }

    @Test fun freshnessAndFutureBoundariesValidateActualTimestamps() {
        val cases = listOf(
            0L to FixError.TIMESTAMP, now + 1 to FixError.FUTURE,
            now - 30_001 to FixError.STALE, now - 30_000 to null
        )
        cases.forEach { (timestamp, expected) ->
            assertEquals(expected, LocationFix(37.0, 127.0, 1f, timestamp).error(now))
        }
    }

    @Test fun invalidCoordinatesAndNonfiniteConfidenceAreRejectedWithoutInventedScale() {
        assertEquals(FixError.COORDINATES, LocationFix(91.0, 127.0, 1f, now).error(now))
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach {
            assertEquals(FixError.CONFIDENCE, LocationFix(37.0, 127.0, it, now).error(now))
        }
        // No range/units are documented. A finite value cannot be called meters or percent.
        assertNull(LocationFix(37.0, 127.0, 500f, now).error(now))
    }

    @Test fun staleFixCannotBePassedByWrappingItInReady() {
        val value = facts().copy(location = LocationState.Ready(LocationFix(37.0, 127.0, 1f, now - 30_001)))
        suppressed(policy().evaluate(value), "Location STALE")
    }

    @Test fun driveUnknownStaleUxAndDisconnectCancelPendingOffer() {
        val variants = listOf(
            safety().copy(connected = false) to "Vehicle service disconnected",
            safety().copy(parking = ParkingObservation(Parking.NOT_PARKED, elapsed, "D at zero")) to "D at zero",
            safety().copy(parking = ParkingObservation()) to "No authoritative gear observation",
            safety().copy(parking = ParkingObservation(Parking.PARKED, elapsed - 3001)) to "Parking observation stale",
            safety().copy(ux = UxObservation(false, 0, elapsed - 3001)) to "UX restrictions stale",
            safety().copy(ux = UxObservation(true, 1, elapsed)) to "UX restrictions active"
        )
        variants.forEach { (value, reason) ->
            val engine = policy()
            engine.evaluate(facts())
            suppressed(engine.evaluate(facts().copy(safety = value)), reason)
            suppressed(engine.evaluate(facts()), "Already presented today")
        }
    }

    @Test fun brakeMustBePressedFreshAndFromCurrentSafetyGeneration() {
        val cases = listOf(
            BrakeFact(null, elapsed, 7), BrakeFact(false, elapsed, 7),
            BrakeFact(true, elapsed - 3001, 7), BrakeFact(true, elapsed + 1, 7),
            BrakeFact(true, elapsed, 6)
        )
        cases.forEach {
            suppressed(policy().evaluate(facts().copy(brake = it)), "Fresh pressed brake unavailable")
        }
        assertTrue(policy().evaluate(facts().copy(brake = BrakeFact(true, elapsed - 3000, 7)))
            is ArrivalDecision.Offer)
    }

    @Test fun timezoneMismatchAndWorkFromHomeDoNotUseWrongLocalDay() {
        suppressed(policy().evaluate(facts().copy(userZone = ZoneId.of("America/Los_Angeles"))),
            "Timezone mismatch")
        val repo = repository(settings.copy(schedule = settings.schedule.copy(
            workFromHomeDays = setOf(java.time.DayOfWeek.WEDNESDAY))))
        suppressed(ForegroundArrivalPolicy(repo, Store(), Clock.fixed(instant, zone))
            .evaluate(facts()), "Work from home day")
    }

    @Test fun proximityUsesMetersAndHysteresisPreservesVisitUntilExit() {
        val store = Store()
        val engine = policy(store)
        val first = offer(engine.evaluate(facts()))
        fun atMeters(meters: Double) = facts().copy(location = LocationState.Ready(
            LocationFix(37.0 + Math.toDegrees(meters / 6_371_000.0), 127.0, 1f, now)))
        assertEquals(first.token, offer(engine.evaluate(atMeters(200.0))).token)
        suppressed(engine.evaluate(atMeters(251.0)), "Outside arrival radius")
        suppressed(engine.evaluate(atMeters(149.0)), "Already presented today")
        assertEquals(2L, store.value.entries.getValue(JourneyNotification.MORNING).visit)
        suppressed(policy().evaluate(atMeters(151.0)), "Outside arrival radius")
        assertEquals(150.0, ForegroundArrivalPolicy.distanceMeters(
            PlaceCoordinates(0.0, 0.0), PlaceCoordinates(Math.toDegrees(150.0 / 6_371_000.0), 0.0)), 1e-8)
    }

    @Test fun skipDeclineAndCompletionRemainDurableAcrossRecreation() {
        listOf(SuggestionOutcome.SKIPPED, SuggestionOutcome.DECLINED, SuggestionOutcome.COMPLETED)
            .forEach { outcome ->
                val store = Store()
                val engine = policy(store)
                val suggestion = offer(engine.evaluate(facts()))
                assertTrue(engine.resolve(suggestion, outcome))
                assertEquals(outcome, store.value.entries.getValue(JourneyNotification.MORNING).outcome)
                suppressed(policy(store).evaluate(facts()), "Already presented today")
                assertEquals(now + 1_800_000, store.value.cooldownUntilMillis)
            }
    }

    @Test fun backwardClockAndStorageFailureNeverDeliverNewOffer() {
        val store = Store()
        store.value = ArrivalLedger(lastEvaluatedMillis = now + 1)
        suppressed(policy(store).evaluate(facts()), "Clock moved backward")
        val failing = object : ArrivalLedgerStore {
            override fun load() = ArrivalLedger()
            override fun persist(ledger: ArrivalLedger) { throw IllegalStateException("disk full") }
        }
        assertThrows(IllegalStateException::class.java) {
            ForegroundArrivalPolicy(repository(), failing, Clock.fixed(instant, zone)).evaluate(facts())
        }
    }

    @Test fun actualMeetingOverridesMorningButWrongZoneDoesNotOffer() {
        val meeting = MeetingFact("user-event", instant.plusSeconds(1200), zone)
        val suggestion = offer(policy().evaluate(facts().copy(meeting = meeting)))
        assertEquals(JourneyNotification.PREPARATION, suggestion.slot)
        assertEquals(20L, suggestion.availableMinutes)
        suppressed(policy().evaluate(facts().copy(meeting = meeting.copy(zone = ZoneId.of("UTC")))),
            "Meeting timezone mismatch")
    }

    @Test fun lunchRequiresReserveAndEveningRequiresActualHomeCoordinates() {
        val lunch = Instant.parse("2026-11-11T03:00:00Z")
        val lunchSuggestion = offer(policy(clock = Clock.fixed(lunch, zone))
            .evaluate(facts(lunch.toEpochMilli())))
        assertEquals(JourneyNotification.NAP, lunchSuggestion.slot)
        assertEquals(55L, lunchSuggestion.availableMinutes)
        val evening = Instant.parse("2026-11-11T09:00:00Z")
        val eveningFacts = facts(evening.toEpochMilli()).copy(location =
            LocationState.Ready(LocationFix(38.0, 128.0, 1f, evening.toEpochMilli())))
        val eveningSuggestion = offer(policy(clock = Clock.fixed(evening, zone)).evaluate(eveningFacts))
        assertEquals(JourneyNotification.EVENING, eveningSuggestion.slot)
        assertEquals(360L, eveningSuggestion.availableMinutes)
    }

    @Test fun scheduleUnsetsDisabledSlotsAndElapsedReserveSuppress() {
        val cases = listOf(
            settings.copy(company = SavedPlace()) to "Company unset",
            settings.copy(schedule = settings.schedule.copy(walkingReserveMinutes = null)) to "Walking reserve unset",
            settings.copy(notifications = NotificationPreferences(morning = false)) to "Slot disabled",
            settings.copy(schedule = settings.schedule.copy(workStartMinute = 8 * 60 + 5)) to "No available time"
        )
        cases.forEach { (value, reason) ->
            suppressed(ForegroundArrivalPolicy(repository(value), Store(), Clock.fixed(instant, zone))
                .evaluate(facts()), reason)
        }
    }

    @Test fun newLocalDateAllowsOfferAndCooldownBlocksAnotherSlot() {
        val store = Store()
        val engine = policy(store)
        val suggestion = offer(engine.evaluate(facts()))
        engine.resolve(suggestion, SuggestionOutcome.SKIPPED)
        val meeting = MeetingFact("new", instant.plusSeconds(1200), zone)
        suppressed(policy(store).evaluate(facts().copy(meeting = meeting)), "Cooldown")
        val tomorrow = instant.plusSeconds(86400)
        assertTrue(policy(store, Clock.fixed(tomorrow, zone))
            .evaluate(facts(tomorrow.toEpochMilli())) is ArrivalDecision.Offer)
    }

    @Test fun cooldownEndsAtExactDeadlineForAnotherEligibleSlot() {
        val store = Store()
        val engine = policy(store)
        engine.resolve(offer(engine.evaluate(facts())), SuggestionOutcome.DECLINED)
        val deadline = instant.plusSeconds(1800)
        val meeting = MeetingFact("explicit", deadline.plusSeconds(1200), zone)
        val atDeadline = facts(deadline.toEpochMilli()).copy(meeting = meeting)
        assertEquals(JourneyNotification.PREPARATION, offer(policy(store,
            Clock.fixed(deadline, zone)).evaluate(atDeadline)).slot)
    }

    @Test fun matchingUserTimezoneUsesItsOwnDateAndSchedule() {
        val losAngeles = ZoneId.of("America/Los_Angeles")
        val engine = ForegroundArrivalPolicy(repository(), Store(), Clock.fixed(instant, losAngeles))
        suppressed(engine.evaluate(facts().copy(userZone = losAngeles)), "Outside app schedule window")
    }

    @Test fun changingSavedPlaceCannotBypassDailySuppression() {
        val repo = repository()
        val store = Store()
        val engine = ForegroundArrivalPolicy(repo, store, Clock.fixed(instant, zone))
        engine.evaluate(facts())
        repo.setCompany(SavedPlace("Updated", PlaceCoordinates(37.0001, 127.0)))
        suppressed(engine.evaluate(facts()), "Already presented today")
    }
}
