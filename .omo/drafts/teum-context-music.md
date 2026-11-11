---
slug: teum-context-music
status: reviewed
intent: clear
review_required: true
plan_path: .omo/plans/teum-context-music.md
plan_sha256: 2012442f10582bb70b97215249de8def31b26b9b360a58783467855c2637d50e
review_round_id: 33bca34f-ed5a-48e3-ae5c-fd3b650ce870
review_round_limit: 5
pending-action: deliver approved plan; implementation requires separate execution
review:
  plan_reviewer:
    status: approved
    workspace_root: /Users/yanaire/OmO Projects/project
    runtime_home: null
    target: .omo/plans/teum-context-music.md
    round_id: 33bca34f-ed5a-48e3-ae5c-fd3b650ce870
    plan_sha256: 2012442f10582bb70b97215249de8def31b26b9b360a58783467855c2637d50e
    launch_id: 3de0cc69-30a5-4ada-9109-4845cdd8eb59
    session: st_01a1c569
    result: "OKAY; no blockers; task st_01a1c569; parent rehashed unchanged plan via no-follow descriptor chain"
approach: "OpenCode Jev 분위기 판단 + Apple Music 검색/재생 우선 검증 + 개발용 키 보호 중계 서버; 새 결제·유료 전환·외부 배포 없음; 검증 실패 시 기능 완성 주장 금지"
---

# Draft: teum-context-music

## Affected user and ideal state
대상: 주차 중 틈의 네 활동을 사용하는 운전자, 디자인 팀, 이후 구현·검증할 개발자.
현재는 시간표와 음성 안내만 있고 음악 선택·재생은 없다.

| Row | Property / gap | Reason |
| --- | --- | --- |
| IS-1 | 활동과 현재 차량 상황에 맞는 실제 음악 후보를 AI가 선택 | 요청한 상황 기반 추천 경험 |
| IS-2 | 승인한 서비스에서 실제 선곡과 재생이 관측되고 사용자가 정지·변경 가능 | 추천 문자열이나 앱 열기를 재생으로 오인하지 않음 |
| IS-3 | 사용자가 동의한 데이터만 이용하고 철회·삭제가 다음 요청에 반영 | 개인 기록과 메모 보호 |
| IS-4 | 시간표·기록·주차 안전·안내 음성이 정상 유지 | 기존 핵심 기능 보존 |
| IS-5 | 올바른 GitHub 최신 main에서 독립 기능 브랜치로 개발 가능 | 기존 병렬 작업과 main 보존 |
| GAP-1 | 기록과 활동 데이터는 있으나 음악 취향·음원 후보·AI 연결이 없음 | 후보 생성과 추천 계약 필요 |
| GAP-2 | 음악 서비스 인증·검색·재생 계약이 없음 | 서비스·계정·권한 결정과 실제 접근 증거 필요 |
| GAP-3 | 로컬 이력 동의만 있고 외부 AI 전달 동의는 없음 | 전송 필드와 비용·서버 범위 소유자 결정 필요 |
| GAP-4 | TTS는 있으나 음악과의 오디오 포커스 검증이 없음 | 안내·음악 충돌과 종료 동작 QA 필요 |
| GAP-5 | 공유 작업 트리에 별도 검증 변경이 진행 중이며 origin도 잘못된 저장소를 가리킬 수 있음 | 대상 원격 명시·독립 작업 트리 필요 |
<!-- Who this output touches (a customer, another programmer, a program or agent consuming it - often more than one) and how each uses it today and will use it after. -->
<!-- IS-n | property of the ideal state: nothing snags, feels odd, regresses, or degrades for them | reason -->
<!-- GAP-n | difference between IS-n and today | reason | closed by todo(s) -->

## Components (topology ledger)
| Component | Outcome | Status | Evidence |
| --- | --- | --- | --- |
| C1 Context + consent | 최소 차량·활동 정보와 승인된 이력만 구성 | active | records/ActivityRecord.kt, RecordsRepository.kt |
| C2 AI decision | 검증된 후보/분위기 중 적합한 선택 | active | TypeSafe 공식 API 또는 사용자 확정 엔진 |
| C3 Music provider | 실제 검색·계정·재생 가능 항목 확인 및 실행 | active | 공식 공급자 계약 조사 중 |
| C4 UI + audio lifecycle | 추천·재생 상태·중지와 기존 안내 유지 | active | ui/TeumCuePlayer.kt, TeumUiController.kt |
| C5 Branch + QA | 별도 브랜치와 실제 앱 시나리오 검증 | active | Git 원격 최신 eabb788; 기존 QA 자료 |
<!-- Lock the SHAPE before depth. One row per top-level component that can succeed or fail independently. -->
<!-- id | outcome (one line) | status: active|deferred | evidence path -->

## Open assumptions (announced defaults)
| Assumption | Proposed default | Rationale | Reversible |
| --- | --- | --- | --- |
| Cost authorization | No purchases, billing changes, paid-model fallback or paid enrollment | Budget question timed out; absence is not authorization | Owner can authorize later |
| Provider | Apple Music SDK/API; validate account entitlement and AAOS compatibility before dependent integration | Official Android native playback exists; other reviewed routes have material restrictions | Failure blocks playback, no silent provider substitution |
| AI boundary | Jev selects locally defined music-intent categories; no Apple catalog or listening data transmitted to Jev absent explicit verified permission | Owner approved Teum summaries, not provider content sharing | Yes with new contract/consent |
| Credential ownership | Development relay holds Console key and Apple signing key outside APK; no cloud deployment in this request | Avoid shipping shared secrets | Confirmed only by plan-approach approval |
| Playback authorization | Explicit per-session AI music start action, then automated selection/playback | Meets requested automation with user intent | Owner may request another trigger |
| Audience/scale | Existing single-user parked Teum development workflow; no account platform/public service rollout | Literal feature-branch development request | Public rollout needs separate contract |
| Stack | Existing Kotlin/View app; minimal development relay, exact runtime and paths settled in full plan | Preserve current application and UI | Yes |
| Tests | Tests-after plus real target playback evidence and existing regression suite | Existing JUnit/instrumentation conventions | Owner may request TDD |
<!-- Record any default you adopt instead of asking, so the user can veto it at the gate. -->
<!-- assumption | adopted default | rationale | reversible? -->

## Findings (cited - path:lines)
2026-11-10 read-only baseline:
- Current workspace branch feature/document-final-review, dirty .omo/boulder.json, .omo/ulw-execute/active-task6.md, .omo/ulw-execute/ledger.jsonl. These are not this task's files.
- App worktree branch teum/implementation at 51df40b; correct GitHub main is eabb78884392c6d089a60f8d447c4c9e641d7745. No fetch/branch switch performed for planning.
- records/RecordsRepository.kt:51-78 stores a local history consent and gates suggestFromHistory; no external sharing permission.
- records/ActivityRecord.kt:18-35 contains note and nextAction free text, duration, situation and outcome; no music preference data.
- content/TeumRecommender.kt:69-125 returns a deterministic activity timetable, not AI music.
- AndroidManifest.xml:5-7 has vehicle permissions only, no INTERNET or media service.
- Official Gleo Core Concepts defines Gleo -> app functions -> callback. This is not evidence for app -> general LLM inference or app -> Melon playback.
  https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/core-concepts
- Music/Audio guide: explicit action/system resume required; no playback from bind or profile change. MediaAgent schema-free control described as a future update. Auxiliary music alone is not Music/Audio category eligibility.
  https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/music-audio-app-developement-guide
- Jev exists as a TypeSafe decision model, state + typed questions -> structured result, not catalog/search/player.
  https://docs.typesafe.ai/introduction
  https://docs.typesafe.ai/api/system-one
- Context7 tool discovery returned a schema but invocation was unavailable; primary official docs retrieved directly instead.
- User clarification: "jev 는 opencode의 jev-1.13-free 모델을 쓰려고 해".
  Selected engine is OpenCode Console jev-1.13-free, not a direct Gleo LLM.
- Official OpenCode Console models page, directly fetched 2026-11-10:
  endpoint https://opencode.ai/inference/systemone/v1/systemone with OpenCode Console API key;
  state plus questions of type choice/noul/score, returns structured decisions. The Jev section explicitly says use jev-1.13-free for limited-time free usage. General endpoint guidance restricts free-tier CHAT models to OpenCode; Jev is System One, so app use eligibility must be verified rather than assuming either prohibited or allowed.
  Privacy section states zero retention/no training except enumerated providers; Jev is not listed as an exception. This is published policy, not an independently audited retention guarantee.
  https://opencode.ai/docs/console/models
- Official 2026-10-19 Playground v2.6.0 release note explicitly discontinues the LLM SDK, directing Gleo integrations to Gleo Interface. Direct app-to-Gleo inference must not be assumed.
  https://document.pleos.ai/en/docs/pleos-only/release-note/playground-release-note
- Directly read task6-cue-verification.md and task6-cue-lifecycle-fix.md: local cue regression was repaired but full connected runs have failures/stalls; native cancellation and physical audibility remain unproven. Old build/unit success is not the current full gate.
- Explore integration suggestions are advisory: existing history consent is NOT sufficient for AI cloud sharing; requiring parked-only for every media command and always stopping provider playback on Activity.onStop are product policies to resolve, not established platform requirements.

## Decisions (with rationale)
AI model selection resolved: opencode/jev-1.13-free for OpenCode model identifiers; request-body model jev-1.13-free for System One endpoint. No paid-model substitution or fallback without owner authorization.
Jev selects/scores supplied candidate music or intent categories; provider search/catalog and actual playback remain separate.
Intent: CLEAR (context-aware AI music selection and playback is the stated result).
Review: required by default. No user opt-out.
Stance: one-by-one (no qualified planning-style memory episode found).
Announced CLEAR/review-required and five-component topology to user. Source-selection RESOLVED: user chose "연동 가능한 서비스 선택 (Recommended)". Select a provider with official search/playback contract; Melon is not mandatory. Provider fees/account choices remain owner decisions. This is not plan approval.
Tier: LIGHT for this planning session; implementation crosses network, credentials and audio lifecycle and requires HEAVY execution coverage.
Planning only; proposed branch feature/teum-context-music created only by later execution, from correct remote main.
Test strategy proposal: tests-after using existing JUnit + instrumentation and actual PLEOS playback evidence. Provider mock evidence alone cannot satisfy real playback.

## Scope IN
Future feature branch from fresh correct remote main; context minimization and separate runtime consent; OpenCode System One integration; Apple Music authorization/catalog/native playback gated by official runtime/entitlement checks; UI playback controls; coordinated cues/focus; failure and stale-response handling; regression tests; real emulator proof; README and resource cleanup.
Early provider/AI live-access proof is a prerequisite, not a reduced feature deliverable. A failed prerequisite means execution cannot claim the requested end state; no recommendation-only stub, app-launch shortcut or mock success.

## Scope OUT (Must NOT have)
Do not edit app code, create feature branch, commit, push, merge or operate devices during planning.
Do not fabricate a Gleo inference endpoint, Melon private-playlist API, playback receipt or permissions.
Do not overwrite shared QA state, modify user data, infer emotional/medical state from sensors, or silently send notes externally.

## Open questions
1. RESOLVED: user specified OpenCode jev-1.13-free.
2. Which actual music source/account must be supported? Prior conversation favors existing Melon/personal playlists but no contract confirmed.
   UPDATE: user delegates officially integrated provider selection. Librarian follow-up comparing Apple Music, Spotify, licensed open catalog.
3. Cloud data field boundary and operating budget/backend ownership.
   DATA SCOPE RESOLVED: user chose "상황·사용이력 요약만 (Recommended)". Only situation, duration, outcome and coarse allowed vehicle context may be sent. Do not send note, nextAction, exact location, personal identifiers or raw history. Current user consent is required separately from existing history-consent flag; withdrawal invalidates pending requests.
4. Explicit smart-music action vs persistent opt-in automatic playback; unrelated existing music must not be silently replaced.

## Advisory synthesis
Ultrabrain st_01a1c4a0 completed both original and model-update advisory. Validate catalog membership and entitlements before Jev and again before dispatch. Reject stale requests after consent/account/session/context generation changes. Treat selected, requested, provider-reported playing and actual playback evidence as distinct states. No global media keys, no silent paid fallback, no auto-replay on restart. Cloud key should stay outside APK; backend ownership still a product decision.
Architect st_01a1c49f remains pending. No implementation or native tests were run.
UPDATE: architect completed read-only advisory. Keep context composition separate from model and playback; shared API key must not ship in APK. Existing safety source is Park/UX only, not arbitrary vehicle telemetry. Teum onStop interrupts unfinished activity, so a provider handoff that backgrounds Teum is not a transparent playback integration. Gleo callback success must not claim playback merely on request submission. Parent accepts these boundaries, but does not adopt unsolicited Gleo actions as required scope; the user has selected Jev and has not requested new voice functionality in this feature plan.
Provider check: official Spotify Developer Policy prohibits AI ingestion of Spotify Content and voice-control applications; do not plan candidate metadata ingestion into Jev under ordinary terms. Apple Music publicly supports Android native in-app playback and catalog/library APIs but AAOS runtime, program membership, user subscription and contract still require verification. No paid subscription or developer enrollment authorized.
Provider librarian st_01a1c49e completed follow-up. Apple Music is a validation candidate, NOT a confirmed AAOS solution or an approved cost commitment. Spotify has content-analysis, AI-ingestion and voice-control restrictions (parent directly read official policy). Jamendo public stream API is not sufficient evidence of commercial in-car rights or private playlists. No provider is proven end-to-end on target head unit. A plan must not count conditional API experiments as fulfilled automatic playback.
All four research/advisory children terminal; no active child or device/process owned.

## Owner-question state
integration_budget timed out without answer. Do not ask it again this turn and do not infer permission for any spending.
Proceed with no new financial commitments as constraint. Credentials/subscriptions required by Apple must be independently available; absence stops dependent execution rather than prompting purchases or changing scope silently.
Backend and playback trigger are explicit proposed approach choices in the approval brief. User may reject/change them. No real credentials or records accessed.

## Approval gate
status: reviewed
User replied "계속 진행해" after the explicit plan-only approval question. Approval authorizes the proposed plan and independent review, not implementation, payments, enrollment, deployment, merge or push.
Approach brief presented with Apple Music validation, Jev intent-category decision, development key-protecting relay, explicit AI music start, tests-after + native proof. New cost/cloud deployment not authorized.
Approval authorizes plan creation only. Next: scaffold full plan -> mandatory plan-consultant gap analysis -> exact tasks/QA -> independent plan-reviewer approval.
<!-- When exploration is exhausted and unknowns are answered, set status: awaiting-approval. -->
<!-- That durable record is the loop guard: on a later turn read it and resume at the gate instead of re-running exploration. -->

## Review receipt
Plan-reviewer st_01a1c569: OKAY. Result confirms users/ideal states/task-scenario mapping and hard prerequisites.
Parent verified current plan SHA256 matches 2012442f10582bb70b97215249de8def31b26b9b360a58783467855c2637d50e with descriptor-relative no-follow read. Receipt identity/round/target unchanged.
Structural check: expected eight sections in order, 12 implementation rows and4 final rows, all required task fields present, no fill markers, no retained music WAV artifacts.
