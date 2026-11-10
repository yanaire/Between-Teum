# Active task 6 checkpoint

User requested complete local PLEOS app implementation and repeatedly requested
continuation. No commit, merge, push, branch switch or destructive worktree
cleanup is authorized. Main DOCX and unrelated `.omo` files remain protected.

Plan: `/Users/yanaire/OmO Projects/project/.omo/plans/teum-pleos-app.md`.
Worktree: `/Users/yanaire/OmO Projects/project-wt/teum-app`.
Tasks 1-5 are verified and checked; task 6 is active. F1-F4 and delivery/cleanup
remain open. Overall 5/12 todos complete. Root orchestrates; workers implement
and perform native QA. Root reads evidence and checks claims before advancing.

Active phase DAG: `dag_26981c8e-b2ff-420b-8cd0-b53534b8a4b5`.
Its name is "틈 네 시나리오 실시간 및 안전 회귀 검증".
The same run contains completed timing and rendering investigations. It
prematurely settled again when `journeys` returned a waiting status while its
fresh 300-second commute was still recording; `verify` correctly returned
needs-fix for incomplete evidence, not a new product defect.

The ORIGINAL producer `st_01a1c411` continued its existing emulator and
completed all four real-time journeys plus the safety/record cases. Its final
handoff has now arrived and its emulator, scripts, browser, forward and
recorders are stopped (actual cleanup.md read by root). Do not revive it or
repeat its default timing runs.

Root armed durable file-create monitor `mon_TS7SCBVJJH3NHD08` (`watch_1`) for:
`worktree/.omo/evidence/teum/task6-journeys-final-handoff.json`.
That watch completed. Marker says completed=false solely because of the new
sequential cue-lifecycle defect described below. All requested timing and
feasible safety/record evidence is retained; do not treat this as an unrun
timer or repeat already-passed default timers.

Current live DAG nodes: `cue-lifecycle-fix` -> `cue-gate` -> `verify`.
Added by amendment in the same task6 run. Product edits limited to UI cue
ownership and a meaningful regression; session/planner/records/vehicle/content
must stay unchanged. Independent final `verify` combines the existing actual
timing evidence with the final cue repair and its native sequential proof.

Latest root midpoint read: owner `st_01a1c476` is still active. The first
current-source combined Gradle command actually ran 28 connected cases,
27 passed and one existing lunch UI case failed because ActivityScenario's
Activity was already destroyed; Gradle exit1. Do not claim green.
`task6-cue-runtime-crash.log` contains concurrent `ai.umos.homescreen`
duplicate task-listener crashes. Root read both raw logs and instructed the
same owner to retain failures, correlate exact times, finish bounded native
audio proof, and execute one full connected suite on a fresh cold-boot
disposable overlay, without code/test/platform workaround. Only re-run
separately unfinished lint if necessary; preserve settled build/unit results.
Do not create a competing emulator owner. Cue gate and final verify remain
pending behind this owner.

Delivery preparation is recorded in `delivery-preflight.md` next to this
checkpoint, including protected DOCX/README hashes and main path inventory.
Main app folder contains build outputs only; do not purge or overwrite them.

## New source-backed cue defect

Root read actual source and `task6-fresh-audio-differential.md`.
`onStart()` constructs the cue player inside `if (source == null)`.
`finalizeRecord()` and `parkingLost()` close/null it although the Activity and
source stay started. A next explicitly started foreground session therefore
uses nullable calls with no player. Same-process lunch had visual fallback
without an identified app playback track; restart control showed a fresh
nonzero AudioFlinger track. Physical audibility remains unmeasured.

The minimal ownership repair can stop and retain an activity-owned player
across record/safety flows while releasing on onStop/onDestroy, or an equally
small correct design. No automatic cue/control replay on P return. Root sent
the same parkingLost branch finding to the active owner. Required current
source full build/lint/unit/connected checks and native same-PID two-session
audio proof, including cancellation/lifecycle coverage. Do not redo 40 minutes
of unchanged timing, but flag any repair that actually changes timing.

All four real saved records were independently read by root:
commute 300/all4, meeting 420/all5, evening 300/all5, lunch1320/all3,
all outcome=completed. Evening next action was read/acknowledged (recall
flag then false). Full no-response >=35.548 seconds, early lunch17seconds,
native D/P with calibrated zero entry/setter snapshots, actual force-stop
same record/completed counts, persisted edit, eight individual deletions and
post-relaunch [] are in scenario-results.json and task6-fresh-report.md.
All 16 source video segments and the noncontinuous joined reel decoded;
gaps are documented. Root has not yet rerun the independent final evidence
audit after this cue repair.

## Current source and settled checks

App APK SHA-256:
`281723b1719e037c7d60f25a3baf9b7fcd8becce0769188dd860dec8fb15bbb9`.
Test APK:
`59a3bab8e50ea0901db7de651522d6794c308bd443c054e38036d29cff29a9bc`.

The actual first 300-second commute exposed a precision bug: wall remaining
was floored before comparing with reserved finish seconds, starting finishing
at elapsed 269 and saving 299 seconds, interrupted, indexes [0,1,3]. It is
retained as a failure, never a passed journey.

Owner `st_01a1c36b` corrected exactly four Kotlin files: SessionEngine,
SessionTest, TeumUiController and ControllerVehicleRegressionTest. Deadline
reserve comparison and actual finishing duration now retain milliseconds.
Start samples monotonic before wall and ticks wall before monotonic to bracket
ordinary sampling latency without a tolerance. Deterministic pre-fix and
mutation failures are retained in task6-timing-fix.md and timing-*.log.

80 fresh unit tests passed. Final-source lint passed separately. All 27
connected tests finally passed in an unfiltered Gradle invocation on a visible
host-GPU COLD BOOT disposable read-only overlay. Root read the actual Gradle
footer, XML counts and independent confirmed verdict:

- `.omo/evidence/teum/task6-timing-fix.md`
- `.omo/evidence/teum/render-cold-fullsuite.md`
- `.omo/evidence/teum/render-cold-fullsuite/gradle.log`
- `.omo/evidence/teum/render-cold-fullsuite-verdict.json`

Do not rerun these settled checks without a source change or specific missing
requirement. Final build manifest must reference actual separate settled logs;
do not fabricate one combined successful invocation.

## Rendering investigation and valid environment

Warm/earlier host-GPU suites stalled after 18 passes at ActivityScenario home
launch. SwiftShader also stalled after 9 passes. Main was sampled in native
HardwareRenderer.nSyncAndDrawFrame; instrumentation waited for launch/idle.
No assertion was reached at those stalls. Root cause ownership remains
unproven; no renderer fallback or product workaround was shipped.

Fresh home alone passed in 0.752 s. In a cold-boot overlay, UI class 6/6 and
controller class 8/8 passed in fresh processes. The subsequent unfiltered full
27-suite also passed in one process. These narrow environment-specific facts
do not prove a warm snapshot renderer fix. Detailed read-only investigations:
render-source-investigation.md, render-runtime-investigation.md,
render-next-action.md, render-home-isolation.md, render-class-isolation.md.

Verified command:

```text
emulator -avd Pleos_Connect_2_1_0 -port 5554 -gpu host -feature -Vulkan -no-snapshot -read-only -no-boot-anim -camera-back none -camera-front none
```

Use a tracked background process/monitor, not a Bun child that dies when a
cell settles. The actual foreground Android user is 10.

AVD has ghost installed package metadata before exact APK reinstall. It is
valid to install the same signed app with `install --user 10 -r` in the owned
disposable overlay, then back up accessible own-app prefs with:
`run-as dev.pleos.starter --user 10`. Do not require impossible pre-install
private access, use root, or bypass denied shell/service routes. Pre-install
private bytes are unobservable; original base is preserved by overlay design.

The full-suite worker failed to restore its throwaway overlay preferences
after UTP uninstalled the app, then the overlay was discarded. That limitation
is explicit; the original shared AVD was read-only. Root and independent
verifier confirmed no devices, forwards, Gradle processes or lock holders
remained before current journey restart. No persistent user data loss claimed.

## Current journey work and remaining gates

Latest `journeys` prompt was replaced after the 27-suite pass. It uses the
verified cold-boot overlay, exact current APK, existing native semantic driver,
and performs real default timers with no source edits or accelerated clock:

- Commute 300 seconds; must now save completed with all four indexes.
- Meeting 420 seconds, actual key sentence.
- Evening 300 seconds; next action "자료 요청 메일 보내기", explicit recall.
- Lunch 1320 seconds in app plus 180 seconds external travel allowance,
  total return budget 1500; actual no response for at least 30 seconds before
  wake acknowledgement and separate restore consent.

Required additional current-APK cases: early lunch, P-to-D at zero speed then
P remains interrupted, actual force-stop/relaunch with calibrated zero command
snapshots and zero new completed records, local note/next-action persistence,
recall/edit/delete after restart, actual unsupported/manual fallback and
disabled seat. Unknown gear/independent UX and supported physical controls are
unavailable on the public console; keep injected contract tests separate and
never claim those unavailable native scenarios passed.

AudioFlinger track frames prove Android playback activity, not physical
audibility. No microphone recording, permission grants or physical car actions.

Expected final artifacts under worktree `.omo/evidence/teum/`:
qa-invocations.md, scenario-results.json, four-journeys.mp4 or complete native
segments with manifest and combined video, final-build.log/manifest,
cleanup.md, qa-verdict.json. Current scenario-results/qa-verdict can still
describe the old failed commute until the active producer writes new results.
Preserve historical failures before overwriting them.

Native driver can enter fullscreen IME. Verify separate note and next-action
values before save; earlier attempts concatenated them or silently left the
next action blank. Raw failed acknowledgements may still have opened dialogs:
inspect actual resulting state instead of silently treating them as passes.

After actual task6 confirmed verdict, run the separate final F1-F4 phase.
Only F3 owns native device; other reviews read code/artifacts. Then deliver
verified source/APK locally preserving main user files, clean owned resources,
complete plan/todos, audit goal, call update_goal complete and report elapsed.
No source/APK delivery or final F1-F4 has yet happened.
