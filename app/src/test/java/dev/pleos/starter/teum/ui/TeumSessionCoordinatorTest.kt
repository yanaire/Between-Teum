package dev.pleos.starter.teum.ui

import dev.pleos.starter.teum.records.ActivityOutcome
import dev.pleos.starter.teum.vehicle.*
import org.junit.Assert.*
import org.junit.Test

class TeumSessionCoordinatorTest {
    private val feature = VehicleFeature.HVAC_TEMPERATURE

    @Test fun shortChangeRevokesFullPreviewAndCannotDispatchVehicleControls() {
        CoordinatorFixture().use { f ->
            f.previewAndConsent()
            val old = requireNotNull(f.state.setup)
            f.send(TeumSessionAction.UpdateSetup(old.copy(minutes = 10)))
            val inspections = f.port.inspections
            f.send(TeumSessionAction.PreviewFeature(feature, 24.0))
            assertEquals(inspections, f.port.inspections)
            assertTrue(f.state.featureCommands.isEmpty())
            f.send(TeumSessionAction.Start)
            assertNull(f.state.active)
            f.send(TeumSessionAction.ConfirmShort(true))
            f.send(TeumSessionAction.Start)
            assertFalse(requireNotNull(f.state.active).schedule.vehicleControlsAllowed)
            assertTrue(f.port.deferred.isEmpty())
            assertTrue(f.port.sent.isEmpty())
        }
    }

    @Test fun finishAndForegroundStopRevokeDeferredDispatch() {
        for (stop in listOf(false, true)) {
            CoordinatorFixture().use { f ->
                f.previewAndConsent()
                f.send(TeumSessionAction.Start)
                assertEquals(1, f.port.deferred.size)
                if (stop) f.owner.onStop() else f.send(TeumSessionAction.FinishNow)
                f.port.deferred.single().invoke()
                assertTrue(f.port.sent.isEmpty())
                if (stop) {
                    // Source close follows foreground interruption and must not reclassify it.
                    f.owner.onSafety(VehicleSafety())
                    val marker = requireNotNull(f.markers.load())
                    assertFalse(marker.restricted)
                    assertFalse(marker.restoredAndReadyToRecord)
                    assertEquals(Screen.RECOVERY, f.state.screen)
                    assertTrue(f.port.closed)
                } else assertEquals(Screen.RESTORE, f.state.screen)
            }
        }
    }

    @Test fun restoreRequiresWakeSeparateConsentOriginAndFreshSafety() {
        CoordinatorFixture().use { f ->
            f.previewAndConsent()
            f.send(TeumSessionAction.Start)
            f.port.deferred.removeAt(0).invoke()
            assertEquals(listOf(24.0), f.port.sent)
            f.send(TeumSessionAction.FinishNow)
            val inspections = f.port.inspections
            f.send(TeumSessionAction.PreviewRestore(feature))
            assertEquals(inspections, f.port.inspections)
            f.send(TeumSessionAction.WakeAcknowledgement(true))
            f.send(TeumSessionAction.PreviewRestore(feature))
            assertEquals(inspections, f.port.inspections)
            f.send(TeumSessionAction.RestoreConsent(true))
            f.send(TeumSessionAction.PreviewRestore(feature))
            assertEquals(inspections + 1, f.port.inspections)
            f.send(TeumSessionAction.ConfirmRestoreFeature(feature))
            assertTrue(f.port.deferred.isEmpty())
            f.send(TeumSessionAction.RestoreOriginAck(feature, true))
            f.send(TeumSessionAction.RestoreConsent(false))
            f.send(TeumSessionAction.ConfirmRestoreFeature(feature))
            assertTrue(f.port.deferred.isEmpty())
            f.send(TeumSessionAction.RestoreConsent(true))
            f.send(TeumSessionAction.PreviewRestore(feature))
            f.send(TeumSessionAction.WakeAcknowledgement(false))
            assertFalse(f.owner.restoreFeatureAllowed())
            f.send(TeumSessionAction.ConfirmRestoreFeature(feature))
            assertTrue(f.port.deferred.isEmpty())
            f.send(TeumSessionAction.WakeAcknowledgement(true))
            f.send(TeumSessionAction.PreviewRestore(feature))
            f.owner.onSafety(VehicleSafety())
            f.send(TeumSessionAction.ConfirmRestoreFeature(feature))
            assertTrue(f.port.deferred.isEmpty())
            assertEquals(listOf(24.0), f.port.sent)
        }
    }

    @Test fun manualOverrideSurvivesRestorePreview() {
        CoordinatorFixture().use { f ->
            f.previewAndConsent()
            f.send(TeumSessionAction.Start)
            f.port.deferred.removeAt(0).invoke()
            f.port.manualChange(25.0)
            f.send(TeumSessionAction.FinishNow)
            f.send(TeumSessionAction.WakeAcknowledgement(true))
            f.send(TeumSessionAction.RestoreConsent(true))
            f.send(TeumSessionAction.RestoreOriginAck(feature, true))
            f.send(TeumSessionAction.PreviewRestore(feature))
            f.send(TeumSessionAction.ConfirmRestoreFeature(feature))
            assertEquals(CommandStage.MANUAL, f.state.restoreCommands[feature]?.stage)
            assertTrue(f.port.deferred.isEmpty())
            assertEquals(25.0, f.port.current, 0.0)
        }
    }

    @Test fun explicitRecordValuesPersistWithoutWidgetsAndSecondSessionKeepsCues() {
        CoordinatorFixture().use { f ->
            f.send(TeumSessionAction.Start)
            f.send(TeumSessionAction.FinishNow)
            f.approveRecord()
            val values = RecordEditable("  actual note  ", " next action ", true)
            f.send(TeumSessionAction.EditRecord(values))
            f.send(TeumSessionAction.SetSound(false))
            assertEquals(values, f.state.editable)
            f.send(TeumSessionAction.SaveRecord(f.state.editable))
            val saved = f.repo.listAll().single()
            assertEquals("actual note", saved.note)
            assertEquals("next action", saved.nextAction)
            assertTrue(saved.recallAccepted)
            assertFalse(saved.historyConsentAtSave)
            assertEquals(ActivityOutcome.INTERRUPTED, saved.outcome)
            assertNull(f.markers.load())
            f.send(TeumSessionAction.SetSound(true))
            f.send(TeumSessionAction.OpenSetup("lunch-break"))
            f.effects.clear()
            f.send(TeumSessionAction.Start)
            assertEquals(Screen.SESSION, f.state.screen)
            assertEquals(1, f.effects.filterIsInstance<TeumSessionEffect.Cue>().size)
        }
    }

    @Test fun recoveredMarkerNeverCompletesOrReplaysEvenAfterFullElapsed() {
        CoordinatorFixture().use { f ->
            f.previewAndConsent()
            f.send(TeumSessionAction.Start)
            val marker = requireNotNull(f.markers.load())
            f.markers.save(marker.copy(elapsedSeconds = marker.timedTotalSeconds,
                progressSeconds = marker.timedTotalSeconds))
            f.owner.onDestroy()
            val recovered = TeumSessionCoordinator(f.repo, f.markers, f.clock)
            recovered.onCreate()
            recovered.onStart(f.port)
            recovered.dispatch(TeumSessionAction.OpenRecoveryRecord)
            recovered.dispatch(TeumSessionAction.Start)
            recovered.dispatch(TeumSessionAction.ConfirmRestore)
            recovered.dispatch(TeumSessionAction.SaveRecord(RecordEditable()))
            assertEquals(ActivityOutcome.INTERRUPTED, f.repo.listAll().single().outcome)
            assertEquals(1, f.port.deferred.size)
            f.port.deferred.single().invoke()
            assertTrue(f.port.sent.isEmpty())
            recovered.onDestroy()
        }
    }

    @Test fun retainedStateCannotBeMutatedByRendererOrLaterActions() {
        CoordinatorFixture().use { f ->
            val before = f.state
            assertThrows(UnsupportedOperationException::class.java) {
                (before.featureTargets as MutableMap)[feature] = 29.0
            }
            assertThrows(UnsupportedOperationException::class.java) {
                (requireNotNull(before.setup).meta.quickMinutes as MutableList).clear()
            }
            f.send(TeumSessionAction.UpdateSetup(requireNotNull(before.setup).copy(minutes = 10)))
            assertEquals(25, before.setup?.minutes)
            assertEquals(10, f.state.setup?.minutes)
        }
    }
}
