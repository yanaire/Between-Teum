# Local delivery preflight

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
