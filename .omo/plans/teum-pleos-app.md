# teum-pleos-app - Work Plan

> CANCELLED by the user on 2026-11-10. Superseded by
> `.omo/plans/teum-design-port.md`. Preserve historical evidence and unchecked
> items; do not resume this plan or interpret cancellation as passing its gates.

## TL;DR (For humans)
<!-- Fill this LAST, after the detailed plan below is written, so it summarizes the REAL plan. -->
<!-- Plain English for a non-engineer: NO file paths, NO todo numbers, NO wave/agent/tool names. -->

**Who this is for and what changes for them:** 자차 출퇴근 직장인이 주차 중 짧은 시간을 준비·활동·복귀 흐름으로 사용합니다.

**What you'll get:** 출근·점심·미팅·퇴근 세션, 중도 마무리, 안전한 설정 복원 확인, 기기 내 실천 기록을 갖춘 차량용 앱과 검증 자료입니다.

**Why this approach:** 기존 스타터를 보존·활용하고 실제 지원되는 차량 기능만 연결합니다. 제어 불가 기능은 직접 설정 안내로 대체합니다.

**What it will NOT do:** 사용자 확인 없는 좌석 이동, 주행 제한 우회, 기상·정시 복귀 보장, 실제 제어처럼 보이는 모의 성공을 제공하지 않습니다.

**Effort:** Large
<!-- Effort is exactly ONE band, never hours/days. Quick = single edit, minutes of agent work; Short = one focused change, a few files; Medium = multi-file feature in one session; Large = several waves, one long session; XL = multi-session or architectural work. A written duration is rewritten to a band. -->
**Risk:** High - 차량 제어 접근 권한과 안전 정지 계약이 미확인입니다.
**Decisions to sanity-check:** 로컬 저장, 계정·서버·음성 녹음 제외, 제어 미지원 시 수동 설정 안내.

Your next move: 계획 검토 후 별도 실행 요청. 이 문서는 구현 시작 권한이 아닙니다.

---

> TL;DR (machine): Large; High; PLEOS parked micro-moment app; review_required: true.

## Scope
### Affected user and ideal state
<!-- From the draft ledger: who this touches and how they use the result, then one IS row per property with its reason, then one GAP row per difference with its reason. Every todo below closes a GAP row; ## Success criteria proves every IS row. -->
**Affected user:** 반복되는 짧은 차량 체류를 사용하는 직장인과 시연을 검증하는 심사자. 현재 기능은 기획 상태이며 실행 가능한 앱은 검증되지 않았습니다.

| Row | Statement | Reason |
| --- | --- | --- |
| IS-1 | 네 활동과 복귀 시간표를 끝까지 사용 | 선택·시간 관리 부담 감소 |
| IS-2 | 지원 기능과 동의 범위에서만 제어·복원 | 안전·자율성 |
| IS-3 | 중도 종료는 활동 명령 취소 후 확인 복원, 주차 신뢰 상실은 신규 설정·복원 차단 | 주행 제한 준수 |
| IS-4 | 완료·중단 기록과 다음 행동을 로컬에서 확인 | 실제 실천만 기록 |
| GAP-1 | main에는 앱 소스가 없고 스타터는 로컬 브랜치에 있음 | 기존 작업 보존 |
| GAP-2 | 제어 권한·지원·정지 계약 미확인 | 거짓 성공 방지 |
| GAP-3 | 세션·기록·안전 흐름 미구현 | 시나리오 구현 필요 |

### Must have
홈 → 상황·복귀 시각/기간·이동 여유 → 추천 한 가지와 변경 설정 확인 → 시작 → 단계 진행 → 종료/중단 → 복원 확인 → 기록.
출근: 30+180+60+30=300초. 점심: 준비60+휴식1080+깨우기/복원180+이동180=1500초.
미팅: 30+180+60+90+60=420초. 퇴근: 30+120+60+60+30=300초.
점심 기준 12:35 시작, 12:36 휴식, 12:54 종료 안내, 12:57 하차 준비 목표, 13:00 복귀 목표. 실제 완료는 별도 표시.
시간 부족 시 짧은 활동 제안 또는 시작 불가. 지연은 활동 시간을 줄이되 안전 복원 시간을 강제로 압축하지 않음.
짧은 활동의 하한: 출근·미팅·퇴근은 활동60초, 준비30초와 마무리60초 예약; 차량 제어는 생략하고 수동 안내. 이동0이면 총150초는 수락,149초는 거부. 점심 짧은 휴식은 활동180초, 준비60초와 마무리180초 예약; 이동180초 포함 총600초는 수락,599초는 거부. 정상 시간표가 맞으면 정상 시간표를 우선하고 짧은 활동은 별도 확인 후 시작.
일반 중도 종료: 활동·알림·예약 제어를 취소한 뒤 주차 조건을 다시 확인하고 사용자 복원 승인을 받음. 안전 중단: 신규 설정·복원 명령을 차단하고, 이미 움직이는 장치에는 검증된 안전 정지 API만 허용. 안전 정지는 신규 설정0 주장과 별도로 로그에 기록. 정지 API·OEM 동작이 검증되지 않으면 해당 이동 제어 자체를 활성화하지 않음.
### Must NOT have (guardrails, anti-slop, scope boundaries)
도착 시 자동 좌석 이동, 무응답 시 좌석 복원, 창문·급격한 온도로 깨우기, 권한 우회, 속도0을 주차로 간주, 자동 명령 재시도/재생, 건강·수면 측정 주장 금지.
초기 범위에 계정·서버·클라우드 분석·마이크 녹음·결제 없음. 외부 유료 서비스나 실제 차량 조작은 별도 승인 필요.

## Verification strategy
> Zero human intervention - all verification is agent-executed.
- Test decision: tests-after. 스타터의 테스트 프레임워크를 유지하고 없으면 JUnit 순수 Kotlin 상태/시간 테스트와 Android 계측 UI 테스트를 추가.
- 실제 에뮬레이터에서 기본 시간으로 네 세션을 끝까지 실행. 테스트용 가상 시계는 자동 테스트에만 사용하며 실제 타이머 증거로 대체하지 않음.
- API 가용성은 공식 문서, 설치 SDK 시그니처, 실제 권한 응답, 차량 상태 readback을 구분해서 기록.
- Evidence: .omo/evidence/teum/ 아래 지정 파일. 실행 시작 시각, 대상 SDK, emulator serial, 코드 revision을 기록하고 이전 증거를 덮어쓰지 않음.

## Execution strategy
### Parallel execution waves
단계별 별도 mass-ulw run: 기반(1) → 병렬 핵심(2,3,4; 소유 파일 분리) → 통합 UI(5) → 실기 검증(6) → 최종 감사.
계획 수립은 읽기 전용 explore/librarian와 architect/ultrabrain 자문. 제품 구현은 이 세션에서 하지 않음.
각 run은 생산자와 그 결과를 검증하는 종속 노드를 함께 정의. 파일로 계약을 인계하고 lead가 증거를 재확인. 공통 Gradle/Manifest 수정은 1만 소유.
> Target 5-8 todos per wave. Fewer than 3 (except the final) means you under-split.

### Dependency matrix
| Todo | Depends on | Blocks | Can parallelize with |
| --- | --- | --- | --- |
| 1 | 없음 | 2,3,4 | 없음 |
| 2 | 1 | 5 | 3,4 |
| 3 | 1 | 5 | 2,4 |
| 4 | 1 | 5 | 2,3 |
| 5 | 2,3,4 | 6 | 없음 |
| 6 | 5 | F1-F4 | 없음 |

## Todos
> Implementation + Test = ONE todo. Never separate.
<!-- APPEND TASK BATCHES BELOW THIS LINE WITH edit/apply_patch - never rewrite the headers above. -->
- [x] 1. 기존 스타터와 SDK 계약을 검증해 실행 기반 확정
  Recommended task executor category: unspecified-high
  What to do / Must NOT do: git status와 두 feature 브랜치의 diff를 읽고 사용자 변경을 보존. 깨끗한 별도 작업 공간에 스타터를 통합. 패키지·Activity·Gradle·기존 테스트 위치를 `.omo/evidence/teum/baseline.md`에 확정. `.agents/skills/pleos-connect-sdk/`의 feature-map, implementation, compatibility 및 Seat/HVAC/조명/주차 관련 JSON을 읽고 실제 SDK와 대조. snapshot을 최신으로 간주하지 않음. 실제 대상 emulator serial과 launch component도 기록.
  Closes: GAP-1, GAP-2
  Parallelization: Wave 1 | Blocked by: 없음 | Blocks: 2,3,4
  References: README.md:1-5; feature/pleos-starter-sdk-guide:.agents/skills/pleos-connect-sdk/SKILL.md; 같은 브랜치의 app/build.gradle.kts (Vehicle2.3.2, namespace dev.pleos.starter), app/src/main/java/dev/pleos/starter/MainActivity.kt (Activity/XML), app/src/main/res/layout/activity_main.xml; feature/android-build-artifacts; https://document.pleos.ai/en/docs/pleos-only/create-my-project
  Acceptance criteria: `./gradlew assembleDebug lintDebug` exit 0, APK 설치·실행 성공. 현재 주차 신호와 권한 지원표 생성.
  QA scenarios: 실제 emulator에 `adb -s SERIAL install -r APK` 후 baseline에 확정한 component를 `adb -s SERIAL shell am start -W -n COMPONENT`로 실행. PASS 화면 표시. API 접근 가능 시에만 권한 없는 제어 요청의 실제 거부를 캡처. API 자체가 없으면 disabled UI·수동 안내와 어댑터 주입 거부 테스트를 증거로 남기고 실제 SDK 거부는 미검증으로 기록. 거부가 성공으로 표시되면 FAIL. SERIAL/APK/COMPONENT는 이 단계에서 발견한 실제 값으로 후속 manifest에 고정하고 추측 금지.
  Evidence: .omo/evidence/teum/baseline.md, baseline-build.log, baseline-screen.png, capabilities.json.
  Commit: Y, 사용자의 별도 커밋 허용 시에만; chore(pleos): establish verified starter baseline.

- [x] 2. 상황별 시간표와 세션 상태 전이 구현
  Recommended task executor category: unspecified-high
  What to do / Must NOT do: app/src/main/java/dev/pleos/starter/teum/session/ 소유. 기존 package가 다르면 1의 manifest에 실제 경로 매핑. 순수 Kotlin planner와 상태 머신 구현. 상태는 configuring, preparing, active, finishing, awaitingRestore, restoring, completed, interrupted, restricted. 복귀=목적 장소 도착; 이동을 정확히 한 번 차감. monotonic 경과와 wall deadline을 분리하고 재개 시 deadline 재평가. 시간이 음수면 시작 차단, 지연 시 짧은 활동, 복원 완료 시각은 관측 결과만 표시.
  Closes: GAP-3
  Parallelization: Wave 2 | Blocked by: 1 | Blocks: 5
  References: 본 문서 Must have 시간표; .omo/drafts/teum-pleos-app.md.
  Acceptance criteria: `./gradlew testDebugUnitTest` exit 0; 네 시간표 합과 1초 부족 경계, 자정·시계 변경·앱 복귀·중도 종료를 검증.
  QA scenarios: unit runner로 lunch(12:35,13:00,180초) 입력 → 12:36/12:54/12:57/13:00 정확히 일치 PASS. 위 짧은 활동 정책으로 출근·미팅·퇴근 가용150초 수락/149초 거부, 점심 가용600초·이동180초 수락/599초 거부 PASS. 타이머 지연을 실제 완료로 오인하면 FAIL.
  Evidence: .omo/evidence/teum/timing-tests.log.
  Commit: Y, 별도 허용 시; feat(session): add deadline-aware activity planning.

- [x] 3. 주차 안전과 차량 제어 복원 어댑터 구현
  Recommended task executor category: deep-high
  What to do / Must NOT do: app/src/main/java/dev/pleos/starter/teum/vehicle/ 소유. 검증한 SDK API만 사용. parked 신호와 UX 제한을 별도로 평가하고 unknown은 차단. 기능·권한·영역·범위 확인, 시작 전 snapshot과 변경 미리보기, 응답/readback 구분. 좌석은 시작·복원 별도 명시 확인, 정지 수단과 OEM 안전 계약 검증 전 자동 이동 비활성. 명령 발송 직전에도 안전 상태 재확인. 주차 신뢰 상실 시 세션/예약 명령 취소; 자동 복원·재연결 replay 금지. 공조·조명은 앱 변경 이후 외부 변경 없는 항목만 복원, 출처 불명은 확인. 실제 제어 불가 시 수동 안내와 미지원 상태.
  Closes: GAP-2, GAP-3
  Parallelization: Wave 2 | Blocked by: 1 | Blocks: 5
  References: SDK 스킬 implementation/compatibility 및 1의 capabilities.json; 공식 driving-restriction-development-guide.
  Acceptance criteria: 제어 테스트와 `./gradlew testDebugUnitTest` PASS. 요청 성공과 실제 적용, 부분 실패, timeout을 각각 구분. SDK로 실행 중 이동 정지가 검증되지 않으면 좌석 자동 제어를 제공하지 않음.
  QA scenarios: emulator P 상태에서 허용된 기능만 시작 → readback 또는 미지원 안내 PASS. 콘솔에서 P→D 및 unknown 주입 → 신규 설정·복원 명령0, 좌석 자동 복원0 PASS. 검증된 안전 정지만 별도 허용·기록. manual HVAC 변경 후 복원 → 그 항목 유지 PASS. 외부 변경 출처 미지원이면 복원 확인 표시 PASS. 실행 중 중지 지원 기능은 실제 정지 후 readback; 검증 불가면 capability disabled PASS.
  Evidence: .omo/evidence/teum/vehicle-actions.jsonl, parking-loss.png, manual-override.png, stop-contract.md.
  Execution evidence boundary: core-verdict confirms native Park/UX observations and adapter library tests. Actual UI P→D/unknown, fallback and screenshots remain mandatory tasks5/6; these GUI scenarios are not claimed passed by this checkbox.
  Commit: Y, 별도 허용 시; feat(vehicle): guard control and conditional restoration.

- [x] 4. 활동 콘텐츠와 로컬 실천 기록 구현
  Recommended task executor category: unspecified-low
  What to do / Must NOT do: app/src/main/java/dev/pleos/starter/teum/records/ 및 content/ 소유. 네 상황의 단계와 한 가지 활동, 추천 변경, 바로 종료, 메모/선택형 질문. 기록은 실제 완료 단계·실제 이용시간·완료/중도/안전중단 구분. 다음 행동은 다음 출근에서 사용자가 선택해 조회. 기기 내부 저장, 수정/삭제 제공, 선택적 이력 추천 동의 기본 꺼짐. 음성 SDK 미지원이면 화면과 로컬 안내음; 앱 실행 중 지원 Android TTS 사용은 실제 대상 가용성과 정책 확인 후에만.
  Closes: GAP-3
  Parallelization: Wave 2 | Blocked by: 1 | Blocks: 5
  References: draft IS-1/IS-4; 본 문서 시간표·범위.
  Acceptance criteria: `./gradlew testDebugUnitTest` PASS. 완료/중도 기록, 재실행 지속성, 삭제, 동의 전 이력 기반 추천 안 함 검증.
  QA scenarios: 퇴근에서 '자료 요청 메일 보내기' 저장 → 다음 출근에서 선택 조회 PASS. 중도 종료 → 전체 완료로 표시하지 않음 PASS. 기록 삭제 후 재실행 → 미표시 PASS.
  Evidence: .omo/evidence/teum/records-tests.log, recall.png, deletion.png.
  Commit: Y, 별도 허용 시; feat(records): save local activity outcomes.

- [x] 5. 차량 화면에서 공통 흐름과 네 상황 통합
  Recommended task executor category: visual-engineering
  What to do / Must NOT do: app/src/main/java/dev/pleos/starter/teum/ui/ 소유. 기존 화면 프레임워크 유지. 홈 네 상황, 시간/복귀 시각·이동 여유, 활동과 설정 미리보기, 단계 한 가지, 남은 시간, 목표/실제 완료 구분, 지금 마무리, 복원 확인·중지, 기록. 앱 프로세스 재시작은 상태 복구 안내만 하고 명령 자동 재생 금지. 음성/알림 실패를 숨기지 않으며 무응답은 좌석 비이동. 주행 제한 화면으로 전환.
  Closes: GAP-3
  Parallelization: Wave 3 | Blocked by: 2,3,4 | Blocks: 6
  References: 각 생산자 계약과 baseline manifest; 네 사용 시나리오.
  Acceptance criteria: `./gradlew assembleDebug lintDebug testDebugUnitTest` PASS. 실제 emulator에서 네 세션 화면 및 버튼 동작. 차량 가로 기본·지원 최소 창 크기에서 잘림/겹침 없음.
  QA scenarios: 설치 실행 후 '점심 휴식'→13:00→이동3분→미리보기→시작→지금 마무리→복원 확인→기록. PASS 실제 이용시간과 중도 표시. 주차 상태 소실 시 같은 surface에서 제한 화면과 제어 차단 PASS. UIAutomator semantic selectors를 text 기준으로 사용하며 실제 component/serial/console invocation은 baseline manifest에서 고정.
  Evidence: .omo/evidence/teum/ui-actions.jsonl 및 각 화면 스크린샷.
  Commit: Y, 별도 허용 시; feat(ui): integrate parked micro-moment journeys.

- [x] 6. 실제 에뮬레이터에서 전체 시나리오와 회귀 검증
  Recommended task executor category: unspecified-high
  What to do / Must NOT do: 제품 코드 수정 없이 먼저 검증. 발견한 결함은 소유 단계로 반환하고 재검증. 런타임 cache/port/context/PID는 시작 시 등록하고 종료 receipt 남김. 에뮬레이터를 이미 사용자가 켰다면 임의 종료하지 않음.
  Closes: GAP-1, GAP-2, GAP-3
  Parallelization: Wave 4 | Blocked by: 5 | Blocks: F1-F4
  References: 1의 baseline과 2-5의 증거. 정확한 명령·UI selector·console 입력을 qa-invocations.md에 실행 전 기록.
  Acceptance criteria: `./gradlew assembleDebug lintDebug testDebugUnitTest connectedDebugAndroidTest` exit 0. 실제 기본 타이머로 5/25/7/5분 journey 완료와 도중 취소·P 상실·권한 거부·무응답·수동 변경 PASS. 빠른 시계 테스트로 실제 타이머 증거 대체 금지.
  QA scenarios: 출근5분 완료 후 '회의 자료 첫 장 확인'; 점심12:35 시작에서 목표 시간표 검증; 미팅7분 핵심 문장 입력; 퇴근5분 다음 행동 저장 후 출근 재조회. 각 PASS=실제 단계 전환·기록·시간 증거. 실제 시각이 다르면 같은 상대 시간 예산을 입력하고 시작 기준시각을 기록. 점심 중 복귀 요청→지금 마무리 PASS. 무응답→좌석 미이동 PASS. P 상실→restricted PASS. 제어 불가 환경은 수동 대체 검증 결과로 명시하고 자동 제어 검증으로 표기하지 않음.
  Evidence: .omo/evidence/teum/qa-invocations.md, four-journeys.mp4, scenario-results.json, final-build.log, cleanup.md.
  Restart QA: P에서 세션 시작 후 `adb -s SERIAL shell am force-stop dev.pleos.starter`; `adb -s SERIAL shell am start -W -n dev.pleos.starter/.MainActivity`; SDK 재연결 완료 후 fresh confirmation 전에 vehicle-actions.jsonl의 신규 설정·복원 명령 수0, 완료 기록 새로 생성0, interrupted/recovery 안내 표시가 PASS. 중단·재연결·동의 전후 구간과 실제 명령을 scenario-results.json에 기록. 자동 테스트에서는 pending command가 있는 상태의 프로세스 재생성도 검증. 주입 테스트와 실제 프로세스 종료 증거를 구분.
  Commit: Y, 별도 허용 시; test(qa): capture scenario and safety verification.

- [ ] 7. 최종 심사에서 발견한 공조·조명 제어 권한 선언 누락 보완
  Closes: GAP-2 / IS-2. Existing PleosSettingsPort requires CONTROL_CAR_CLIMATE and CONTROL_CAR_LIGHTS, but the manifest omitted both. Declare exactly those two permissions; retain all runtime permission, capability, consent, parking, readback and disabled-seat checks.
  Acceptance: fresh assembly/lint/unit/full connected command exit0, actual packaged and installed requested-permission inspection, native fallback or genuinely granted supported behavior captured by F3, and independent F1 reconciliation. A declaration is not a grant or physical-control proof. No permission bypass or physical vehicle manipulation.
  Evidence: .omo/evidence/teum/final-permission-source-ready.json, final-fresh-execution.md, f3-qa.md and f1-compliance.md.

## Final verification wave
> Runs in parallel after ALL todos. ALL must APPROVE. Surface results and wait for the user's explicit okay before declaring complete.
- [ ] F1. Plan compliance audit
  Recommended task executor category: unspecified-high
  Read the complete diff and scenario-results.json. PASS only when all IS rows and six producer tasks have matching captured evidence; reject unsupported control claims and unrelated edits. Evidence: .omo/evidence/teum/f1-compliance.md.
- [ ] F2. Code quality review
  Recommended task executor category: unspecified-high
  Inspect lifecycle release, pending command invalidation, local data isolation and diagnostics. Read final-build.log and test reports; every invoked task must execute rather than NO-SOURCE for intended tests. PASS when no criterion-affecting defect remains. Evidence: .omo/evidence/teum/f2-quality.md.
- [x] F3. Real manual QA
  Recommended task executor category: unspecified-high
  Independently read video/action log timestamps and execute the failure cases from qa-invocations.md on the same emulator build. PASS only with screenshots, command output and cleanup receipt. Evidence: .omo/evidence/teum/f3-qa.md.
- [ ] F4. Ideal-state fidelity
  Recommended task executor category: unspecified-high
  Compare each IS row with its scenario evidence, actual unsupported capabilities and user-facing wording. PASS when every supported path works and all unavailable paths are honestly disclosed. Evidence: .omo/evidence/teum/f4-fidelity.md.

## Commit strategy
사용자가 실행 세션에서 허용한 경우에만 검증 단위별 커밋. 이 계획 세션은 커밋하지 않음. 현재 history convention을 읽고 맞추며 unrelated 변경은 제외. Plan footer는 본 계획 경로.

## Success criteria
> One row per IS row. The plan is complete only when every IS row has a delivering todo and a proving QA scenario; F4 checks the delivered behavior against these rows 1:1, and a shortfall becomes new `- [ ] N.` rows, never a note.
| IS | Delivering todo(s) | Proving QA scenario | Evidence |
| --- | --- | --- | --- |
| IS-1 | 2,5,6 | 실제 네 journey 및 시간표 | .omo/evidence/teum/scenario-results.json |
| IS-2 | 1,3,5,6 | 권한 거부·무응답·수동 변경·중지 | .omo/evidence/teum/vehicle-actions.jsonl |
| IS-3 | 2,3,5,6 | 지금 마무리·P 상실·재시작 replay 없음 | .omo/evidence/teum/scenario-results.json |
| IS-4 | 4,5,6 | 다음 행동 선택 조회·중단 기록·삭제 | .omo/evidence/teum/records-tests.log |
