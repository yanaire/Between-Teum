# Local delivery preflight

## Later authoritative inventory

Root re-inventoried main after cue gate confirmation, while task6 final
reconciliation was running. The main workspace now contains the app source,
Gradle wrapper/configuration, SDK skill, AGENTS.md and reports. A direct
comparison against `task6-native-swap/source-after.json` found50 of51
protected files identical. The sole difference is
`app/src/androidTest/java/dev/pleos/starter/teum/ui/TeumUiTest.kt`:
main `bfb505f2c419860862112e045401c9b25b46c45ecb56219a63fe2702b84109a0`,
reviewed worktree
`9d746596e105246703a5ac550cc198ef8cf26de112cc5ad4d2e611d1362648e1`.
The main test is the earlier cue-regression version. Before delivery, inspect
the actual test diff and preserve any newer concurrent changes; sync only
the verified in-scope increment. Do not blanket-copy the whole worktree.

Root subsequently ran `diff -u` on those actual two test files. The complete
diff contains four vehicle-observation imports and extends only
`cueRemainsAvailableForSecondSessionAfterRecording`: inject safety loss,
assert terminal state/no replay on safe return, save safety-interrupted record,
explicitly start again and assert a live nonclosed player. No prior assertion
is removed or changed. This is the verified current regression increment;
main has no additional difference in this file at the observed hashes.
Recheck for intervening user changes before applying only that increment.

Main `gradlew` mode is100755. Main debug APK at
`app/build/outputs/apk/debug/app-debug.apk` is absent; an explicit reviewed
APK deliverable is still owed.

The protected DOCX still hashes exactly to53a7cb... below. README.md changed
outside this root's edits and now describes the app, screenshot, development
setup and separately unimplemented music/Gleo features. Its current hash is
`8aa26b2328a6c0bcbb46cd5d127811fcd8ab8898a350e497cbe3c79d28bcedf0`.
Root read it. Preserve it; do not restore its original hash or overwrite it.
Use TEUM_README.md for this task's verified delivery/test instructions,
including actual supported GLAsyncSwap-off QA environment and capability
limits. Existing source and README changes do not authorize adding music.

The original inventory below is historical and is superseded by this one.

Root observed these paths while task6 cue repair was still running.
This is delivery preparation, not a task6 or final-review pass.

Main workspace: `/Users/yanaire/OmO Projects/project`.
Verified app worktree: `/Users/yanaire/OmO Projects/project-wt/teum-app`.

Main currently has `app/build/generated` and `app/build/intermediates`,
but no app source or Gradle entry points at the workspace root. Preserve
these existing build paths and every unrelated file. Main also contains
`.gradle`, `.kotlin`, `.idea`, `assets/icon.svg`, `.omo`, and the unusual
file `]*bounds=[^]*`; their ownership is not established by this preflight.
Do not remove or overwrite them as part of delivery.

Protected user DOCX:
`teum-pleos-app-개발계획서.docx`
SHA-256 `53a7cb9461f5fc6459294c016aabbf9a9c3c521e3c9396706d468db8ecb5621d`.

Existing README.md SHA-256:
`314ee03ac70b1150d3d356cc1603dbe21368deca8bc8c0523892a25f8ee5c1bb`.
Its content is the original project title and OmO attribution.
Use a separate TEUM_README.md for delivery instructions.

The worktree contains app sources, Gradle wrapper and configuration,
`.agents` SDK skill, AGENTS.md, assets, build reports and evidence.
After task6 and F1-F4 pass, the delivery owner must inventory again,
copy only app source/build entry points into absent destinations,
and deliver the exact reviewed APK under an explicit local artifact path.
Keep large historical QA videos and source evidence discoverable through
documented worktree paths rather than treating stale build output as proof.
Keep the worktree containing uncommitted verified source intact.
No commit, merge, branch switch, push, or PR is authorized.

Final delivery verification must compare source/APK hashes, confirm wrapper
executable permission and usable local SDK configuration, confirm all
protected hashes remain identical, and inspect the final instructions.
Owned temporary resource cleanup is a separate remaining todo. Do not
purge user Gradle caches or remove pre-existing main build directories.
