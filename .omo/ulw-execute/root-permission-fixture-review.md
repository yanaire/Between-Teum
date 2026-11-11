# Root inspection of the permission fixture correction

The root directly inspected the current source and original build XML after
the producer reported that the second fixture run had settled. This note
does not independently accept the producer's overall cleanup or final gate.

Actual XML under the worktree:
- app/build/test-results/testDebugUnitTest: 10 files, 80 testcases.
- app/build/outputs/androidTest-results/connected/debug: 21 testcases.
- permissionProbe/build/outputs/androidTest-results/connected/debug: 7 cases.
- All three groups have zero failure, error and skipped elements.

Both relocated test files have identical SHA-256 to the retained original
task6-native-swap/source-before.json inventory, and their former paths are absent:
- HvacPermissionProbeTest.kt:
  f8d51ea251c121944f833bfea8e10c005de04a67f309744d29b54865947a76bf.
- VehicleObservationTest.kt:
  657d1a3500ecb3c0e44a683c466e65f07e2c375a45fa8cf17b32946e2e86c56b.

Root read permissionProbe/build.gradle.kts: its main source set directly
includes the existing app vehicle source directory; no copied implementation
is required. It uses Vehicle2.3.2, android.car, the existing Android test
versions and a separate dev.pleos.teum.permissionprobe applicationId.
Its manifest requests CAR_POWERTRAIN, CAR_CLIMATE and CAR_LIGHTS, with no
CONTROL permission. Settings includes the app and permissionProbe modules.

Root subsequently read the actual v2 command, complete Gradle task tail,
gradle.exit (0) and cleanup.log: BUILD SUCCESSFUL in57s, 147 actionable tasks
actually executed, including app unit, both lint and both connected tasks.
Owned PID/ports were absent, no forwards, lock free and private AVD removed.
Actual current feature APK hash matched the v2 marker:
bf975dfac77481a3851d19431c338252f7ead0d405908e89198b387ac1d8b252.
The v2 directory is a documented copy of the single completed green retry,
not a second test execution.

The canonical finished.json still pointed at the historical failure despite
the correct new report. Root preserved the existing historical failed marker
and updated only this current pointer to the verified v2 result, including
explicit generation and raw evidence path; old failed raw logs remain intact.

Still required: F3 on the newly permission-declaring feature APK, all four
actual independent approvals, local source/APK delivery and final cleanup.
These settled checks must not trigger another test run.
