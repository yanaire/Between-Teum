# teum-context-music - Work Plan

## TL;DR (For humans)
<!-- Fill this LAST, after the detailed plan below is written, so it summarizes the REAL plan. -->
<!-- Plain English for a non-engineer: NO file paths, NO todo numbers, NO wave/agent/tool names. -->

**Who this is for and what changes for them:** 주차 중 틈을 사용하는 사람이 음악 시작을 누르면 현재 활동과 허용한 사용이력 요약을 바탕으로 음악을 선택하고 틈 화면 안에서 듣습니다.

**What you'll get:** 음악 추천·재생·중지 화면, 기록 사용 동의, 키를 보호하는 개발용 중계 서버, 실제 차량 에뮬레이터 검증 자료입니다. 먼저 무료 AI 호출 자격과 음악 서비스의 계정·기기 호환성을 검증하며, 이 전제가 실패하면 완료로 처리하지 않습니다.

**Why this approach:** Jev는 음악 분위기 판단에 사용하고 실제 음원 검색과 재생은 Apple Music에 맡깁니다. 공급자 콘텐츠와 개인 메모는 AI에 보내지 않아 전송 범위를 줄입니다.

**What it will NOT do:** 새 결제·유료 모델 전환·외부 서버 배포·사용자 기록 초기화를 하지 않습니다. 사용자가 시작하지 않은 자동 재생이나 다른 앱 음악의 임의 제어도 하지 않습니다.

**Effort:** Large
<!-- Effort is exactly ONE band, never hours/days. Quick = single edit, minutes of agent work; Short = one focused change, a few files; Medium = multi-file feature in one session; Large = several waves, one long session; XL = multi-session or architectural work. A written duration is rewritten to a band. -->
**Risk:** High - 무료 AI의 외부 앱 이용 자격, 기존 음악 계정 및 PLEOS에서의 실제 재생이 아직 검증되지 않았습니다.
**Decisions to sanity-check:** AI는 요약만 사용, Apple Music 우선 검증, 개발용 중계, 사용자 시작 이후 선곡·재생. 필요한 기존 계정이 없으면 결제하지 않고 중단합니다.

Your next move: 독립 계획 검토 후 별도 실행을 시작합니다. 이 문서는 구현이나 지출 승인이 아닙니다.

---

> TL;DR (machine): Large; High; intent=clear; review_required=true; Jev + Apple Music context-aware playback; capability-gated execution.

## Scope
### Affected user and ideal state
<!-- From the draft ledger: who this touches and how they use the result, then one IS row per property with its reason, then one GAP row per difference with its reason. Every todo below closes a GAP row; ## Success criteria proves every IS row. -->
**Affected user:** 주차 중 틈의 출근·점심·미팅·퇴근 활동을 사용하는 운전자와 이 기능을 구현·검증하는 개발자. 지금은 활동 안내만 제공하며, 구현 후에는 사용자가 음악 시작을 누르면 현재 상황에 맞는 음악을 선택하고 앱 안에서 재생한다.

| Row | Statement | Reason |
| --- | --- | --- |
| IS-1 | Jev가 허용한 차량·활동·이력 요약으로 음악 의도를 판단 | 상황 기반 음악 선택 |
| IS-2 | 실제 공급자 음원을 틈 화면을 유지하며 재생·일시정지·중지 | 링크 열기와 재생을 혼동하지 않음 |
| IS-3 | 별도 동의, 최소 전송, 철회·삭제의 즉시 무효화 | 개인 기록 보호 |
| IS-4 | 타이머·기록·안전·음성 안내가 보존되고 음악이 이를 방해하지 않음 | 기존 앱의 핵심 경험 보존 |
| IS-5 | 올바른 원격 main 기반의 별도 브랜치와 재현 가능한 검증 증거 | 병렬 작업과 main 보존 |
| GAP-1 | 기존 TeumRecommender는 활동 시간표만 계산 | 별도 음악 의도 판단 필요 |
| GAP-2 | 공식 음악 검색·계정·재생 연결과 권한 증거가 없음 | 실제 공급자 계약·기기 동작 확인 필요 |
| GAP-3 | 기존 이력 동의는 로컬 추천용이며 클라우드 동의가 없음 | 외부 전송용 명확한 경계 필요 |
| GAP-4 | TTS/알림음만 있고 음악 포커스·생명주기 검증이 없음 | 안내와 음악의 충돌 방지 |
| GAP-5 | 공유 작업 트리에 진행 중인 변경과 오래된 이력·증거가 혼재 | 최신 기준점과 변경 소유권 필요 |

### Must have
승인 범위: OpenCode `jev-1.13-free`, 공식 연동 가능한 음악 서비스 선택 위임, 상황·사용이력 요약만 AI에 전달. Apple Music을 우선 검증하고, 틈 전경 화면 안의 Android MusicKit 재생을 사용한다. 별도 Gleo 음성 액션은 이번 범위에 추가하지 않는다.

이것은 **계약 검증을 선행하는 전체 기능 개발 계획**이다. Jev 이용 자격, 기존 Apple 개발자 자격·사용자 구독·SDK 접근, PLEOS 호환성이 확보되지 않으면 해당 선행 작업은 BLOCKED이며 종속 작업을 시작하지 않는다. 계정 가입·결제·서비스 교체를 임의로 수행하지 않는다. 이러한 중단을 기능 구현 완료로 보고하지 않는다.

기본 경로: 사용자 `AI 음악 시작` → 신선한 주차/UX와 클라우드 동의 확인 → 로컬 요약 생성 → 개발용 relay → Jev의 고정 음악 의도 선택 → Apple 카탈로그 검색 → 재생 자격이 확인된 정확한 곡 → 재생 상태 확인. Apple 노래·아티스트·플레이리스트·청취 이력·ID는 Jev 입력에 포함하지 않는다. 기존 플레이리스트를 서비스 간 이동하지 않는다.

**확정된 개발 계약**
- Kotlin/View 화면·기존 패키지 `dev.pleos.starter`를 유지한다. 새 코드는 `teum/music/`, 개발 중계는 `services/music-relay/`에 둔다. SDK/Gradle 업그레이드는 하지 않는다.
- 서비스 경계는 `POST /v1/music-intent`, `GET /v1/apple-token`, `GET /healthz`. 개발 실행은 `bun run services/music-relay/server.ts`, 기본 listen `127.0.0.1:8787`. 운영 배포는 범위 밖이다. relay bearer는 시작 시 OS 난수32bytes로 생성하고 환경 변수로 주입; debug 설정 화면에서 세션별 입력·메모리 보관하며 자동 백업/영구 저장하지 않는다. 재시작 시 재입력이 필요하다.
- `/v1/apple-token`은 같은 bearer 필수, body 없음, response `{developerToken:string, expiresAtEpochSeconds:integer}`. 기존 Apple private key로 ES256 서명, 만료는 발급 후3600초 이하. cache-control no-store, 로그/디스크 저장0. client는 만료60초 전 이후 필요한 요청에서 명시적으로 새 token 조회, 네트워크 오류 시 자동 재시도 없음. Music User Token은 SDK 요구대로 기기 전용 보안 저장소에 보관, 로그아웃/account 변경 시 삭제하며 서버에 보내지 않는다. Apple SDK의 token 주입·갱신 메서드는 task3 계약으로 고정한다.
- Android release에는 HTTP 주소나 공유 API 키를 넣지 않는다. debug 전용 설정으로 `http://127.0.0.1:8787` 및 `adb reverse tcp:8787 tcp:8787`를 사용한다. relay는 bearer 개발 세션 토큰을 요구하며 이것은 배포용 사용자 인증이 아니다. Console 키와 Apple 서명 키는 OS 보안 저장소 또는 프로세스 환경에서만 주입하고 로그/증거에서 제거한다.
- request body: `version:1`, `requestId`(요청별 임의 값), `situation`(`commute-morning`, `lunch-break`, `meeting`, `commute-evening`), `remainingSeconds`(0..86400), `parking:"parked"`, `uxRestricted:false`, `history`(동의 시 최근 30일·최대 20건의 활동 종류별 완료 횟수와 이용시간 합). `history`는 위 네 ID를 key로 하고 각 값은 `{completedCount:0..20, elapsedSeconds:0..1728000}`인 object이며 동의한 기록이 없으면 `{}`다. 미래 timestamp와 30일보다 오래된 기록은 제외한다. 기록 ID, 날짜 원문, note, nextAction, 위치, 계정 ID는 거부한다. AI 전달 state에는 requestId도 넣지 않는다. `history` 사용은 기존 이력 동의와 새 AI 요약 동의가 모두 켜진 때만 허용한다. AI 동의가 없으면 요청하지 않는다.
- Jev는 `choice` 하나로 `calm`, `focus`, `uplift`, `wind_down` 중 선택한다. 상황 설명과 사용자 요약만 사용한다. 설명 문구는 로컬 의도 설명이며 AI가 생성한 이유로 표시하지 않는다. 빈/잘못된 결과는 실패로 표시하며 규칙 선곡이나 유료 모델로 숨겨 대체하지 않는다.
- relay response: `version:1`, `requestId`, `intent`, `expiresInSeconds:15`. 검색어는 순서대로 `calm instrumental`, `instrumental focus`, `uplifting instrumental`, `relaxing instrumental`. 사용자 storefront를 사용하며 최대 10개 노래 결과에서 explicit 아님과 재생 entitlement를 확인한 첫 결과를 고른다. 정렬은 공급자 반환 순서이며 적합성을 보증하지 않는다. 같은 세션/intent의 `다음 곡`은 이미 재생한 ID와 현재 ID를 제외하고 다음 유효 결과를 선택한다. 후보 소진 시 안내만 하고 이전 곡을 다시 시작하지 않는다. `다른 음악 찾기`는 새 판단·검색을 하되 세션에서 이미 재생한 ID 제외 규칙을 유지한다. 결과 0이면 재생하지 않는다.
- 동시 요청은 하나만 유효하다. session/context/consent/account/request generation이 바뀌면 이전 응답을 폐기하고 발송 직전 다시 검사한다. 중지·곡 변경·앱 background·P 상실·UX 제한·종료·철회 시 pending 작업 취소. 소유한 player만 정지하며 시스템 전체 media key나 타 앱 재생 제어를 사용하지 않는다.
- 음악은 전경 활동에 한정한다. onStop/onDestroy에서 음악·relay 작업을 종료하며 자동 재개하지 않는다. 타이머의 기존 background/recovery 의미는 변경하지 않는다. 재시작 후에는 새 사용자가 누른 시작이 필요하다.
- Apple 계정 연결은 홈의 설정/세션 시작 전 setup에서만 실행한다. SDK 인증 UI가 별도 Activity를 열 수 있으므로 진행 중 세션에서는 로그인/재로그인 UI를 실행하지 않는다. token 만료·인증 취소는 음악만 unavailable로 표시하고 현재 타이머를 계속 유지한다. credentials가 제공되지 않았거나 MFA 등 자동 검증 불가이면 preflight blocker, 사람의 로그인 완료를 가정한 PASS 금지.
- 회원 인증·entitlement 오류는 별도 표시한다. `재생 요청 중`과 SDK가 관측한 `재생 중`을 구별한다. 관측 불가일 때 성공 문구를 표시하지 않는다. 지연된 SDK play 명령을 실제로 취소/중지할 수 있는 계약은 task 3의 필수 조건이다.

**근거와 기준점**
- 정식 저장소: `https://github.com/yanaire/pleos-connect-starter`, 조사 당시 main `eabb788`. 실행 시 최신 main을 다시 읽는다. 현 origin `yanaire/project`에 쓰지 않는다.
- 현재 소스 조사: `/Users/yanaire/OmO Projects/project-wt/teum-app/app/src/main/java/dev/pleos/starter/teum/`.
- `records/ActivityRecord.kt:18-35`, `records/RecordsRepository.kt:51-78`: 로컬 기록/이력 동의.
- `content/TeumRecommender.kt:69-125`: 음악이 아닌 활동 시간표.
- `vehicle/VehicleSafety.kt:18-38`: Park/UX 및 3000ms freshness.
- `ui/TeumUiController.kt:190-250,294-327,642-674`: 전경·중단·안내 소유권.
- `ui/TeumCuePlayer.kt:11-121`: TTS와 알림음. 음악 포커스 구현 없음.
- `.omo/evidence/teum/task6-cue-final-verification.md` 및 `task6-cue-verdict.json`(위 앱 worktree): 최신 독립 감사는 GLAsyncSwap-disabled 조건에서 전체28 connected·기존80 unit 및 cue ownership gate를 confirmed로 기록한다. 이전 실패 기록은 `task6-cue-verification.md`에 보존돼 있다. 이 진단 설정의 성공을 일반 renderer 원인 해결로 확대하지 않는다. 물리적 가청성과 live TTS interruption은 미측정. 최신 remote 코드와 동일하다고 가정하지 않는다.
- 공식 API: `https://opencode.ai/docs/console/models`, `https://docs.typesafe.ai/primitives/choice`, `https://developer.apple.com/musickit/`, `https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/music-audio-app-developement-guide`.
- `https://developer.spotify.com/policy`의 AI ingestion/분석/voice 제한 때문에 이번 공급자로 선택하지 않는다. Melon 공개 cross-app API는 확인되지 않았다.
- Gleo LLM SDK 중단: `https://document.pleos.ai/en/docs/pleos-only/release-note/playground-release-note`의 2026-10-19 항목. Gleo를 일반 추론 API로 사용하지 않는다.
- 계약 상충 가능성: `https://opencode.ai/legal/terms-of-service`의 internal-use, programmatic extraction 제한과 unpaid content 개선 이용 조항을 API 문서와 함께 검증해야 한다. 성공한 HTTP 응답만으로 외부 앱 relay 이용 자격이나 무보관을 확정하지 않는다. 해결 전에는 합성 probe만 허용하고 실제 사용이력 전송은 금지한다.
- Apple MusicKit 계약 `https://developer.apple.com/support/terms/apple-developer-program-license-agreement/`의 콘텐츠 download/modify 제한과 표준 play/pause/skip 조건을 확인한다. 영구 음원 캡처 권리를 가정하지 않는다.

### Must NOT have (guardrails, anti-slop, scope boundaries)
새 결제·유료 가입·유료 모델 전환·외부 서버 배포·실차 조작·기록 초기화·main 직접 변경·자동 push/merge 금지. Apple의 기존 개인 플레이리스트 접근·편집 및 서비스 간 이전은 이번 승인 경로에 포함하지 않는다; 이번 검색 대상은 Apple 카탈로그이고 개인화 근거는 승인한 틈 요약이다. AI에 자유 입력 메모, 다음 행동, 정확한 위치, 공급자 콘텐츠나 계정 정보를 보내지 않는다. 추론 결과가 안전 규칙이나 동의를 재정의하지 않는다. 무조건 성공 mock, 딥링크 실행만으로 재생 성공 주장, 글로벌 미디어 제어, 자동 retry/replay, 앱 시작·profile 변경에서의 자동 재생을 넣지 않는다.
기존 실패 테스트를 삭제·skip·약화하지 않는다. 병렬 QA 변경과 기존 cue 수정은 가져오거나 되돌리지 않는다. 현 기능의 계약 검증을 빌미로 기존 앱의 전체 리팩터링을 하지 않는다.

## Verification strategy
> Zero human intervention - all verification is agent-executed.
- Test decision: tests-after. Android는 기존 JUnit/instrumentation, relay는 Bun test. 선택 기준과 계약 검증은 로컬 fixture로 검증하되 실제 Jev·MusicKit 재생을 대신하지 않는다.
- Evidence root `E=.omo/evidence/teum-context-music/<runId>`를 독립 worktree 안에 만들고 절대 경로, revision, APK hash, 시간, SDK 버전을 `manifest.json`에 고정한다. 아래 명령의 `$E`, `$SERIAL`, `$RUN_TMP`, `$WORK`는 task 1 manifest의 실제 값으로만 치환한다.
- 전체 회귀 명령: `./gradlew --no-daemon assembleDebug lintDebug testDebugUnitTest connectedDebugAndroidTest`와 `bun test ./services/music-relay/`. monitor로 실행하고 exit code와 실제 unit/connected XML의 실행수·실패수·skip 수를 보존한다. 초기 전체 gate 실패는 pre-existing으로 구분하되 최종 gate 실패를 숨기지 않는다.
- 기기 소유권을 확인한 전용 disposable emulator/app-data sandbox를 생성한다. 기존 TeumUiTest는 prefs 전체를 비울 수 있으므로 **기존 사용자 기기에서 전체 suite를 실행하지 않는다**. 원본 AVD나 사용자 계정 데이터 복제 없이 깨끗한 자기 소유 sandbox와 사전 제공된 테스트 자격만 사용한다. 필요한 계정이 제공·인증되지 않았으면 credentials-required로 중단한다. 구매·계정 생성이나 사람의 로그인 작업을 QA 성공으로 가정하지 않는다.
- 한 기기와 Gradle build/output에는 한 소유자만 있다. 모든 설치·connected·콘솔·probe는 동일한 task-owned lock을 잡고 직렬 실행한다. baseline task1의 확정 결과를 변경 전 반복하지 않는다.
- 앱 GUI: `adb -s "$SERIAL" shell am start -W -n dev.pleos.starter/.MainActivity`, UIAutomator 의미 기반 선택자로 직접 동작시키고 `adb -s "$SERIAL" exec-out screencap -p`와 action log를 보존한다. instrumentation 입력 주입과 실제 차량 상태 변경은 따로 표시한다.
- Android track frames 또는 SDK playing callback만으로 물리적 가청성을 주장하지 않는다. SDK 곡 ID,1초 간격 position3회 증가, 해당 UID 출력 frame, cue-free 구간의 시스템 출력 신호 측정 및 Stop 후 신호 소멸을 시간 정렬한다. SDK·약관상 측정 허용을 task3에서 먼저 검증한다. 출력은 transient RAM에서 RMS/peak/timestamp로 처리하고 PCM/WAV 등 곡 녹음을 저장하지 않는다. cue/타 앱 오디오가 섞이면 해당 음악의 oracle이 아니며 독점 측정 구간을 다시 구성한다. 적법한 output 측정을 할 수 없으면 audible 기준은 미검증이고 완료가 아니다.

## Execution strategy
### Parallel execution waves
> Target 5-8 todos per wave. Fewer than 3 (except the final) means you under-split.
Wave A: 1 기준점·전체 baseline 확정 후 2 API와 4 읽기 전용 증거 정리를 병렬 실행. 3 MusicKit probe는 baseline 기기 owner 종료 후 단독 기기 사용. 4는 baseline을 소비하며 unchanged connected suite를 다시 실행하지 않는다. 2/3의 hard gate 통과 전 외부 연동 구현을 시작하지 않는다.
Wave B: 5 요약·동의, 6 relay, 7 공급자 player, 8 audio coordination을 파일 소유권을 나눠 병렬 실행. 8은 7의 확정 계약만 소비한다.
Wave C: 9 orchestration → 10 UI → 11 실제 통합·회귀 → 12 문서·정리. 공유 Controller 수정은 10만 소유한다.
Final: F1/F2/F4 읽기 검토는 병렬, 기기 조작 F3는 다른 기기 조작 종료 후 단독 실행. task12는 문서·중간 자원 정리만 수행하고 인증된 전용 emulator는 F3까지 보존; F3 종료 후 executor가 최종 teardown receipt를 남기고 F1이 이를 확인한다.
Topological planning/review only in current session. Future executor categories are below. No cooperating team is necessary when write ownership is disjoint; coordinator owns shared manifest/Gradle changes and resolves contracts before fan-out.

### Dependency matrix
| Todo | Depends on | Blocks | Can parallelize with |
| --- | --- | --- | --- |
| 1 | none | 2,3,4 | none |
| 2 | 1 | 6,9 | 3,4 |
| 3 | 1 | 7,8,9 | 2,4 |
| 4 | 1 | 8,11 | 2,3 |
| 5 | 1,2 | 6,9 | 6,7 |
| 6 | 2,3,5 boundary specified | 9 | 5,7,8 |
| 7 | 3 | 8,9 | 5,6 |
| 8 | 3,4,7 contract | 10 | 5,6 |
| 9 | 5,6,7 | 10 | 8 |
| 10 | 8,9 | 11 | none |
| 11 | 10 | 12,F1-F4 | none |
| 12 | 11 | F1-F4 | none |

## Todos
> Implementation + Test = ONE todo. Never separate.
<!-- APPEND TASK BATCHES BELOW THIS LINE WITH edit/apply_patch - never rewrite the headers above. -->
- [x] 1. 올바른 원격 기준점과 독립 작업공간 확정
  Recommended task executor category: deep-low - shared dirty work and provenance must remain intact.
  What to do / Must NOT do: `gh api repos/yanaire/pleos-connect-starter/commits/main --jq .sha`로 최신 SHA 조회. explicit HTTPS 원격을 fetch하고 `git worktree add -b feature/teum-context-music "$WORK" "$BASE"`로 자기 소유 작업공간 생성. 기존 branch가 있으면 소유자/목표 불일치 시 중단하고 덮어쓰지 않는다. 모든 git은 git-master 지침 준수. SDK·JDK·Bun·serial과 기존 자격 증명 존재 여부만 기록하고 값은 노출하지 않는다. 앱 루트의 source SHA와 dirty sibling path 목록 기록. 키/구독이 없으면 2/3 해당 gate는 중단.
  Closes: GAP-5. Parallelization: A first; blocks 2-12.
  References: 위 기준점, `.agents/skills/pleos-connect-sdk/SKILL.md`, `app/build.gradle.kts`, 기존 README, `.omo/drafts/teum-context-music.md`.
  Acceptance criteria: 생성한 브랜치 base가 올바른 원격 fetch/query SHA와 일치. setup 작업자는 기존 root/sibling의 product/test/user 파일·branch·index를 쓰기·삭제·복원·stage·commit·switch하지 않는다. 허용된 공유 Git 변경은 지정 원격 fetch와 새 branch/worktree 등록뿐이다. 자기 소유 source/build/test 입력은 immutable BASE와 비교하고 baseline 전체 Gradle 명령과 실제 XML 결과를 보존한다. 테스트 전용 AVD 생성·serial, 깨끗한 데이터 소유권, 실행 lock, teardown 명령을 manifest에 기록; 기존 기기는 untouched. 공유 dirty 상태는 전후 관측을 그대로 보존하고, 동시 root .omo/다른 소유자의 변경은 실제 소유권 근거와 대조한다. 추적되지 않는 구간 변경은 unknown으로 남기며 전체 diff가 동일했다고 주장하지 않는다.
  QA scenarios: `git -C "$WORK" merge-base --is-ancestor "$BASE" HEAD` exit0; 작업자의 실제 명령·쓰기 경로를 독립 검사해 기존 공유 파일·branch·index에 작업자 귀속 변경0 확인. task-owned 입력을 `git show "$BASE":<path>`와 byte 비교. shared HEAD/branch/index 및 dirty 전후 관측과 동시 변경 소유자 근거/미확정 한계를 기록. 원격 불일치 predicate probe는 worktree 생성 전에 거부하되 실제 생성 guard 통합 테스트로 과장하지 않는다. Evidence `$E/01-baseline.json`, `$E/01-build.log`, `$E/01-independent-verdict.json`.
  Execution amendment T1-PRESERVE: 원래 global diff-equality 검사는 권한 있는 동시 작업까지 금지하는 잘못된 oracle이었다. 독립 검토 st_01a1c57d가 executor non-interference로 한정하도록 요구했다. 원래 실패 비교는 보존하며 product/test를 수정하거나 이미 통과한 QA를 반복하지 않는다.
  Commit: N; branch setup and evidence only.

- [~] 2. OpenCode Jev 실제 호출과 이용 계약 검증
  Recommended task executor category: unspecified-high - live API and privacy boundary.
  What to do / Must NOT do: 공식 endpoint/model/choice response schema와 free-tier app-client 허용 범위를 검증하고 `$E/contracts/jev.md`에 날짜·응답 필드 고정. API docs와 이용약관의 internal-use/programmatic-output/unpaid-data 조항 상충을 해결하는 공식 근거가 필요하다. 불명확한 계약은 지원 확인 필요로 차단하며 Chat API나 OpenCode CLI를 우회 proxy로 사용하지 않는다. 사람에게 연락/약관 수락은 별도 승인 없이는 수행하지 않는다. 키는 이미 제공된 secret만 사용. 건강·개인 이력 없는 합성 state로만 probe. 무보관·학습미사용·과거 데이터 삭제를 무조건 보장하지 않고 확정 정책에 맞는 동의 문구를 task5에 전달한다.
  Closes: GAP-1,GAP-3. Parallelization: A; depends 1; blocks 6,9.
  References: OpenCode Console models Jev section, TypeSafe Choice docs, draft engine decision.
  Acceptance criteria: 승인된 무료 route에서 실제 2xx typed Choice 한 건; key 미제공/잘못된 token은 명확한 실패; endpoint 외부 앱 이용 자격의 문서 근거. 하나라도 불충족이면 해당 gate BLOCKED.
  QA scenarios: 보안 HTTP client로 `POST https://opencode.ai/inference/systemone/v1/systemone`, body `{"model":"jev-1.13-free","state":"Parked lunch break, 1200 seconds, no history.","questions":{"music_intent":{"type":"choice","instructions":"Choose suitable instrumental music intent.","criteria":{"calm":"Rest quietly","focus":"Prepare for focused work","uplift":"Start with energy","wind_down":"End the day calmly"}}}}`. PASS=실제 문서 schema의 선택값이 네 키 중 하나. 같은 요청 token=`invalid-probe-token`은 non-2xx이며 성공 해석0. Evidence `$E/02-http-redacted.json`, `$E/02-contract.md`. 실제 key/header를 출력하지 않는다.
  Commit: N; contract/probe evidence, no inference adapter yet.

- [~] 3. Apple Music 계정·약관·PLEOS 실제 재생 검증
  Recommended task executor category: deep-low - SDK/runtime facts decide feasibility.
  What to do / Must NOT do: 기존 개발자 자격·서명 키·구독·SDK 다운로드 자격 확인. 실제 Android SDK 패키지를 읽고 auth/search/entitlement/play/state/stop/audio-attribute/cancellation 메서드를 `$E/contracts/apple-music.md`에 artifact hash와 함께 고정한다. MusicKit/서비스 약관상 차량 앱과 로컬 의도→검색→재생 사용, metadata 표시 규칙, token 수명, category 적합성과 출력 측정 허용을 확인한다. 불명확하면 BLOCKED. 앱 보조 음악을 Music/Audio 카테고리로 속여 등록하지 않는다. 인증은 timer 없는 probe/setup에서만 수행한다.
  Probe layout: `$WORK/.omo/probes/music-kit/{settings.gradle.kts,build.gradle.kts,app/src/main/,app/src/androidTest/java/dev/pleos/starter/musicprobe/MusicKitProbeTest.kt}`. package/applicationId `dev.pleos.starter.musicprobe`, 실제 SDK 바이너리는 task-owned private path에서만 참조. 기존 Gradle wrapper/JDK를 사용하되 independent build outputs, install package와 data를 분리. probe용 단기 token도 기존 키에서만 생성하고 로그에 넣지 않는다. probe와 relay 제품 코드 중복을 남기지 않는다.
  Closes: GAP-2,GAP-4. Parallelization: A; depends 1; blocks 7,8,9.
  References: Apple MusicKit 공식 Android 다운로드와 SDK docs, Apple Music API authorization/catalog docs, PLEOS music policy, Activity onStop contract.
  Acceptance criteria: target PLEOS에서 이미 허가된 계정의 실제 곡 검색·정확한 ID 재생·position 증가·정지 관측. cancel/stop 후 지연 start 없음. 해당 SDK로 이것을 보장 못 하면 provider gate 실패, 새 서비스로 임의 변경 금지.
  QA scenarios: `./gradlew -p .omo/probes/music-kit connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.pleos.starter.musicprobe.MusicKitProbeTest#playsResolvedTrackAndStops`; SDK track ID와 1초 간격 position3회 증가, stop 후3초간 증가0, foreground Activity 유지 PASS. 동일 class의 `#latePlayCannotSurviveCancellation`은 stop 이후 playback0. 미인증 account에서는 인증필요, command0. 권한이 확인된 transient output 측정과 screen/action log 동시 보존. Evidence `$E/03-provider-contract.md`, `$E/03-playback.*`; raw music data 저장0.
  Commit: N; owned probe source와 contract는 private `.omo` evidence에 보존, 설치된 probe package 및 test hook은 제거. SDK binary/keys 미포함.

- [~] 4. 기존 안전·안내·데이터 회귀 기준 확정
  Recommended task executor category: unspecified-high - baseline evidence and existing failures.
  What to do / Must NOT do: remote 기준 Activity/CuePlayer lifetime과 sibling 최신 cue fix 차이를 읽고 별도 기록. 기존 수정 자동 반입 금지. 최신 cue-final audit의 confirmed 범위와 남은 live TTS/가청성 한계를 보존. 기본 타이머·record outcome·recover semantics 고정. task1의 새 baseline과 disposable data ownership을 소비. renderer/connected 기존 실패가 새 branch에서도 재현되면 원인·영향을 기록하고 음악 QA와 구분한다.
  Closes: GAP-4,GAP-5. Parallelization: A; depends 1; blocks 8,11.
  References: `TeumUiController`, `TeumCuePlayer`, `VehicleSafety`, 기존 Session/Records/Vehicle unit 및 ControllerVehicleRegression/TeumUiTest.
  Acceptance criteria: 실제 baseline 단위·connected 결과와 owned record ID 목록. 과거 성공/실패 로그를 현재 결과로 복사하지 않음.
  QA scenarios: task1 `./gradlew testDebugUnitTest connectedDebugAndroidTest`의 실제 XML을 파싱하고 실행수·실패수 대조; 재실행하지 않는다. task1 홈/활동 screenshot 소비. 원본 사용자 AVD untouched, 테스트 sandbox만 prefs 정리 허용. Evidence `$E/04-regression-baseline.md`.
  Commit: N; baseline investigation.

- [~] 5. 최소 요약과 AI 동의 경계 구현
  Recommended task executor category: unspecified-high - explicit privacy/data contract.
  What to do / Must NOT do: `music/MusicContext.kt`, `MusicConsentStore.kt`와 JUnit tests 소유. 기존 record schema를 바꾸지 않고 별도 opt-in 기본 off. 최근30일 최대20건을 집계하되 user text·IDs·exact timestamps 제거. 기존 history consent off이면 history 없음; AI consent off이면 네트워크0. 철회/삭제/수정/profile 변경은 generation 증가와 cached/pending result 폐기. 실제 음악 감상 기록을 틈 활동 완료로 저장하지 않는다.
  Closes: GAP-1,GAP-3. Parallelization: B; depends 1,2; contracts from Scope; blocks 6,9.
  References: RecordsRepository, ActivityRecord, SharedPreferencesRecordStore, Scope request shape 및 task2 확정 데이터 처리·retention 정책. task2 통과 전 실제 기록 전송은 불가.
  Acceptance criteria: `./gradlew testDebugUnitTest --tests '*MusicContextTest' --tests '*MusicConsentTest'` exit0; 기존 records tests 보존.
  QA scenarios: 동의한20건+미래·31일 경계 fixture에서 payload allowlist 일치; note=`DO_NOT_SEND_SENTINEL`/nextAction/record IDs가 전송 경계에 없음; 철회 후 serializer 호출 또는 HTTP 발송0. Evidence `$E/05-context-tests.xml`.
  Commit: Y on feature branch | Add consented music context summaries.

- [~] 6. 키를 보호하는 개발용 Jev 중계 구현
  Recommended task executor category: unspecified-high - bounded external request service.
  What to do / Must NOT do: `services/music-relay/server.ts`, `server.test.ts`, `README.md`, lock/config 파일만 소유. Bun HTTP builtins로 Scope routes, bearer dev token, body max16KiB, strict allowlist, 8s upstream timeout, free model 고정, request validation. 키·state·records 로그 금지, 메모리 결과 cache 없음, no retry. 바인딩127.0.0.1, health body `{"status":"ok"}`. UI 요청은 최대12s 후 종료. upstream contract는 task2의 실제 schema만 사용. Scope의 `GET /v1/apple-token` 계약과 task3 확인 SDK 요구를 필수 구현; 외부 user tokens를 relay에 보내지 않는다.
  Closes: GAP-1,GAP-3. Parallelization: B; depends 2,3,5 contract; blocks 9.
  References: task2 contract, Scope request/response, Bun skill, Apple developer-token docs.
  Acceptance criteria: `bun test ./services/music-relay/` all pass; live Jev happy proof preserved; no paid fallback.
  QA scenarios: owned monitor로 server 시작 후 `curl -i http://127.0.0.1:8787/healthz` 200; missing bearer→401 on both protected routes; 보안 HTTP client로 `POST http://127.0.0.1:8787/v1/music-intent`, payload `{"version":1,"requestId":"qa-lunch-1","situation":"lunch-break","remainingSeconds":1200,"parking":"parked","uxRestricted":false,"history":{}}` + ephemeral bearer→200 typed intent; 같은 payload에 `note:"DO_NOT_SEND_SENTINEL"` 추가→400, timeout/401/429 upstream→non-success body와 paid calls0. `GET /v1/apple-token`의 JWT signature/expiry≤3600s, no-store 및 expired-token refresh unit 검증. inspect logs sentinel0. Evidence `$E/06-http.txt`(token redacted), `$E/06-tests.xml`, server cleanup receipt.
  Commit: Y | Add development relay for Jev music intent.

- [~] 7. Apple Music 검색과 앱 내 재생 어댑터 구현
  Recommended task executor category: unspecified-high - verified native provider integration.
  What to do / Must NOT do: `music/AppleMusicPlayer.kt`, `MusicCatalog.kt`, provider tests 소유. task3 고정 SDK만 사용. auth token handling, storefront catalog search, explicit filter, entitlement, exact-track playback, state callback, owned stop/cancel 구현. SDK terms가 요구하는 title/artist/artwork attribution은 UI 모델로 전달하되 Jev에 보내지 않는다. callback 성공과 playing 상태 분리. 앱 전경 유지 및 다른 player 비간섭.
  Closes: GAP-2. Parallelization: B; depends 3; blocks 8,9.
  References: task3 artifact-hashed SDK method contract and approved license, Scope query mapping.
  Acceptance criteria: provider adapter unit/instrumentation PASS; 실제 authenticated track 재생/정지. internet/dependency changes는 coordinator가 task3 확정 목록만 Gradle/Manifest에 반영.
  QA scenarios: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.pleos.starter.teum.music.AppleMusicPlayerTest`; empty search, explicit-only, expired subscription, wrong account, delayed callback 각각 command0 또는 정지, false-playing0. Live known-track ID+position advancement와 stop capture. Evidence `$E/07-provider.*`.
  Commit: Y | Add verified Apple Music playback adapter.

- [~] 8. 음악과 안내 음성의 오디오 소유권 연결
  Recommended task executor category: deep-low - native focus/lifecycle correctness.
  What to do / Must NOT do: `music/MusicAudioCoordinator.kt`와 관련 tests, 필요한 최소 CuePlayer audio attribute 및 completion seam만 소유. SDK가 focus를 소유하면 별도 경쟁 MUSIC focus를 잡지 않는다. TTS는 utterance ID 기반 started/ended/cancelled/error 알림을 UtteranceProgressListener로 main에 전달하며 speak()의 Boolean을 완료로 사용하지 않는다. Tone은 기존220/400ms 종료 callback과 stop/release 취소 callback을 별도로 발생시키며 중복종료 무시. TTS/알림음 동안 음악 일시정지, ended 후 여전히 동일 소유권·동의·전경·안전이면 재개; cancelled/error에서는 자동 재개하지 않는다. 전화/내비/영구 focus loss에서는 SDK/AAOS 및 PLEOS stream 정책 준수, user pause 뒤 gain/late cue completion은 재개 안 함. 종료·P 상실·onStop 후 음악과 pending cue-dependent 재개0. 기존 cue 소유권 버그가 변경 부분에 재현되면 원인을 최소 수정하되 sibling 변경을 통째로 가져오지 않는다.
  Closes: GAP-4. Parallelization: B; depends 3,4,7 contract; blocks 10.
  References: CuePlayer, Controller cue call sites, native SDK audio ownership, `https://source.android.com/docs/automotive/audio/audio-focus`, `https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/audio-focus-development-guide/audio-focus-development`.
  Acceptance criteria: unit + real native focus/cancellation evidence; 가청성과 SDK playing 분리.
  QA scenarios: `MusicAudioTest#cuePausesOwnedMusicAndResumesOnlyWhenAuthorized`, `#stopAndFocusGainNeverResurrectPlayback`, `#lateCueCompletionAfterUserPauseCannotResume` instrumentation. 허용된 transient 측정으로 음악 구간→음성 구간→허가된 음악 재개와 provider timeline 대조, raw song recording0. native focus loss/reject 입력 방법은 task3 SDK contract에 구체화; 주입 test와 native 분리. Evidence `$E/08-signal.jsonl`, `$E/08-events.jsonl`, `$E/08-screen.png`.
  Commit: Y | Coordinate music playback with existing audio cues.

- [~] 9. 상황 판단부터 재생까지 취소 가능한 흐름 구현
  Recommended task executor category: deep-high - cross-boundary dispatch authority invariant.
  What to do / Must NOT do: `music/MusicSessionCoordinator.kt`, `MusicRelayClient.kt`, tests만 소유. main-thread serialized transitions `idle,requesting,searching,starting,playing,paused,failed,stopped`; captured generations와 fresh safety 검사. request/response identity,15s expiry, no duplicate dispatch, one live operation. 12s UI request budget 후 취소하며 server8s와 분리. unknown/stale/invalid 값이나 user takeover면 dispatch0. 프로세스 복구 snapshot은 음악 명령을 저장/replay하지 않는다.
  Closes: GAP-1,GAP-2,GAP-3,GAP-4. Parallelization: C; depends 5,6,7; blocks 10.
  References: Scope invariants, VehicleSafety, SessionEngine recovery, provider cancellation proof.
  Acceptance criteria: `./gradlew testDebugUnitTest --tests '*MusicSessionCoordinatorTest'` exit0; asynchronous boundary failures 명시.
  QA scenarios: 순서 뒤집힌 두 응답, deletion/withdrawal/account change/P loss at each stage, context age2999/3000/3001ms와 future observation, command accepted 직후 stop then delayed callback. PASS=only newest permitted operation starts, stop 이후 late-playing0. malformed JSON/unknown enum/huge state→command0. Evidence `$E/09-state-tests.xml`.
  Commit: Y | Orchestrate cancellable context-aware music selection.

- [~] 10. 틈 화면에 음악 시작과 재생 제어 통합
  Recommended task executor category: visual-engineering - existing car UI integration.
  What to do / Must NOT do: shared `TeumUiController` 수정 유일 소유자. 기존 설정/활동 화면에 AI 음악 opt-in 설명, `AI 음악 시작`, `일시정지`, `다시 재생`, `다음 곡`, `음악 중지`, `다른 음악 찾기` 추가. 버튼 tag 각각 `music_start`, `music_pause`, `music_resume`, `music_next`, `music_stop`, `music_refresh`. `음악 계정 연결`은 설정/setup에서 timer 시작 전에만 제공. 활동 중 만료시 연결필요 안내만 표시, auth Activity 실행0. 시작 누르기 전 계정/동의/주차 상태 표시. provider metadata 표시, busy/error/empty/unknown 구별. 취소/끝내기/기록화면/stop/destroy 이벤트를 coordinator에 연결. 기존 activity 타이머와 restore consent는 그대로 유지. shared manifest/debug config는 coordinator가 관리.
  Closes: GAP-1,GAP-2,GAP-3,GAP-4. Parallelization: C; depends 8,9; blocks 11.
  References: 기존 TeumTheme/View 패턴, Controller lifecycle, Scope labels, task4 baseline.
  Acceptance criteria: 실제 target 기본 가로 및 PLEOS 분할 최소 지원 창에서 버튼/글자 겹침0. 사용자가 음악 시작을 누르기 전 inference/play0.
  QA scenarios: GUI `AI 음악 시작` click→requesting→playing, pause→paused, stop→stopped; fixture A,B는 initial A→다음곡 B→exhausted이며 반복 A dispatch0. no-consent/unauthenticated/empty search→명확한 상태+play0. `TeumMusicUiTest#expiredMusicAuthDoesNotBackgroundOrStopTimer`에서 인증취소/만료 동안 auth Activity0, timer 진행 확인. 각각 action log와 screenshot. `#foregroundMusicControlsPreserveSession` instrument와 native 조작 둘 다 보존. Evidence `$E/10-default.png`, `$E/10-split.png`, `$E/10-actions.jsonl`.
  Commit: Y | Add contextual music controls to Teum activities.

- [~] 11. 실제 음악 흐름과 기존 기능 전체 회귀 검증
  Recommended task executor category: deep-low - integrated real-surface verification.
  What to do / Must NOT do: 전용 emulator에서 합성 owned history와 별도 동의로 네 상황의 선곡→정확한 곡→position/audio→정지 확인. inferred intent의 적합성은 실제 출력 기록이며 건강·감정 정확도 보장 아님. 데이터 철회/삭제·P 상실·background·force-stop·사용자 pause·타 앱 focus 모두 검증. baseline 기존 실패와 새 실패를 구분, 원인 해결 없이 retry 성공만으로 닫지 않음. owned records만 청소.
  Closes: GAP-1..5. Parallelization: C; depends 10; blocks 12,F1-F4.
  References: tasks2-10 artifacts; 기존 SessionTest/RecordsRepositoryTest/ControllerVehicleRegressionTest/TeumUiTest.
  Acceptance criteria: whole Gradle + Bun suite exit0 and failure/error/skip0 for intended tests; 실제 사용자 음악 시나리오 PASS. unsupported native scenario는 PASS 아님. task4 pre-existing failure가 완료 판단을 막으면 명시적 blocker로 남긴다.
  QA scenarios: `./gradlew --no-daemon assembleDebug lintDebug testDebugUnitTest connectedDebugAndroidTest`; `bun test ./services/music-relay/`. Native `am start`와 UI 태그 동작, `adb shell am force-stop dev.pleos.starter` 후 재실행→추론/재생 자동 replay0. test-owned record 삭제/동의 철회 도중 응답→play0; 안전 입력은 기존 공개 콘솔 실제 invocation을 manifest에 먼저 고정하고 P→D→P 수행, 자동 재개0. 4-situation silent screen/video/relay redacted payload and SDK+transient-signal timeline correlation. Evidence `$E/11-verdict.json`, `$E/11-build.log`, `$E/11-music-silent.mp4`, `$E/11-signal.jsonl`. 콘텐츠 표시 캡처 권한은 task3에서 확정.
  Commit: Y for any regression tests with relevant fixes | Verify contextual music and existing session behavior.

- [~] 12. 문서와 증거를 정리하고 소유 자원 해제
  Recommended task executor category: writing - accurate handoff and cleanup.
  What to do / Must NOT do: README에 실제 신규 화면과 API consent/account/dev-relay 실행 설명, 제한·keys 제외·무료 route 한시성, background stop 동작을 반영. 현재 task-owned relay/probe/forward와 transient measurement process/cache를 정리. 인증된 전용 emulator와 테스트 계정 sandbox는 F3까지 보존하고 이 소유권을 F3에 인계; 원본 사용자 기기는 건드리지 않음. licensed music 녹음은 처음부터 만들지 않음. branch와 계획 파일은 보존. PR/push/merge는 별도 명시 지시 없으면 하지 않음.
  Closes: GAP-5. Parallelization: C; depends 11; blocks F1-F4.
  References: README screenshots conventions, task1 resource manifest, all evidence.
  Acceptance criteria: 이미지 링크 유효, 아키텍처/제약이 관측 결과와 일치, owned PID absent/port8787 free/owned adb reverse absent/owned temp paths absent receipt.
  QA scenarios: README render에서 신규 screenshot 직접 확인; `lsof -iTCP:8787 -sTCP:LISTEN` owned listener0; `adb -s "$SERIAL" reverse --list` task forward0; source secrets 검사에 실제 token0. 필요한 retained feature worktree는 예외 목록에 소유권·보존 이유 기록. Evidence `$E/12-cleanup.md`, `$E/12-readme.png`.
  Commit: Y | Document contextual music setup and verified screens.

## Final verification wave
> Run after ALL todos. ALL must APPROVE. Read-only audits may run in parallel; device work has one owner. No additional human approval is required to report verified completion; implementation start and external writes require their own authorization.
- [~] F1. Plan compliance audit
  Read full diff, current revision and all 12 task receipts. Verify provider/AI preflight actually passed, no unauthorized spending/data/cloud scope, correct remote and branch. `git diff "$BASE"...HEAD --stat` plus trace every GAP to evidence. Reject mock-only playback. Evidence `$E/f1-compliance.md`. Read-only, no implementation.
- [~] F2. Code quality review
  Inspect request invalidation, secrets, debug-only transport, focus ownership, SDK state distinctions and full test XML. Verify existing assertions retained. Evidence `$E/f2-quality.md`. Read-only; findings returned to respective owner.
- [~] F3. Real manual QA
  Run after task11 device owner exits, using task12's retained owned authenticated sandbox. Independently execute GUI music start→pause→stop and one revoke-during-request case using installed final APK; compare track ID/position/transient signal and screenshot/action log. Do not erase non-owned prefs. Create own relay/forward then remove; no raw song capture. Finally shut down only the owned emulator, remove its disposable userdata/sandbox, SDK temp paths and run caches; read-back PID/port/path/forward receipts for F1. Evidence `$E/f3-qa/`, `$E/final-cleanup.md`. If credentials/permitted output measurement unavailable, NOT APPROVED.
- [~] F4. Ideal-state fidelity
  Independently map every IS row to observed current-build behavior and artifact timestamps. Confirm summary payload excludes prohibited fields; playback truly occurs in Teum foreground; old timer/record flows still work. Any shortfall becomes a new numbered repair task, never an approved limitation. Evidence `$E/f4-fidelity.md`.

## Execution disposition — 2026-11-10
Only task1 is FullyDone. Rows2-12 and F1-F4 are unmet [~], not accepted limitations or feature approvals. Owner confirmed integration credentials currently not prepared. Task2 lacks key/live Choice/official external-app eligibility; task3 lacks usable Apple identity/key/SDK/authorized account and verified terms/category/output prerequisites; task4 retains missing historical sandbox record UUIDs and partial screen evidence. Tasks5-11 are unimplemented transitive dependents. Task12 closure evidence and F1/F2/F4 audits are complete only as audits of blocked work; actual F3 music QA was not run. Independent owned-resource cleanup is confirmed; the branch/worktree/evidence are intentionally retained. See `.omo/evidence/teum-context-music/music-exec-01a1c46f/final-disposition/` in the feature worktree.
Existing session authorization covers implementation and disposable verification after prerequisites clear. No fresh implementation approval or commit is required to resume; commits/external writes still need explicit authorization. Historical sandbox IDs cannot be reconstructed or invented. The feature remains incomplete.

## Commit strategy
Execution only: every task's Commit:Y means **conditional on explicit commit authorization in that execution session**, never an authorization from this plan. Without it retain local edits and draft the history-matching subject. With it, atomic verified increments on `feature/teum-context-music`. No commits in current planning session. Never commit credentials, SDK artifacts without redistribution rights or music captures. Before each commit inspect exact staged diff and tests; preserve unrelated changes. Plan footer `Plan: .omo/plans/teum-context-music.md`. Future push/PR/merge needs explicit authorization; correct repository only. All final verification receipts required before reporting completion.

## Success criteria
> One row per IS row. The plan is complete only when every IS row has a delivering todo and a proving QA scenario; F4 checks the delivered behavior against these rows 1:1, and a shortfall becomes new `- [ ] N.` rows, never a note.
| IS | Delivering todo(s) | Proving QA scenario | Evidence |
| --- | --- | --- | --- |
| IS-1 | 2,5,6,9,10,11 | real Jev typed intent→catalog→GUI music start across four situations | 02,05,06,09,11 artifacts |
| IS-2 | 3,7,8,10,11 | exact track/position advancement + loopback; stop and late-cancel | 03,07,08,10,11 artifacts |
| IS-3 | 5,6,9,11 | default-off, prohibited-field rejection, midflight revoke/delete dispatch0 | 05,06,09,11 artifacts |
| IS-4 | 4,8,10,11 | TTS/focus overlap, P loss/background/restart, existing whole-suite regression | 04,08,10,11 artifacts |
| IS-5 | 1,12,F1-F4 | remote SHA/branch ownership, live current artifacts, cleanup and full review | 01,12,f1-f4 artifacts |
