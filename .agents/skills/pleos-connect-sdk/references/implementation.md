# 구현 계약

## SDK·권한 선택

- 데이터 파일의 버전은 2026-11-02에 확인한 스냅샷이다.
- 현재 프로젝트의 Gradle 의존성과 선택한 API 문서를 먼저 대조한다.
- `CAR_*` 조회 권한과 `CONTROL_CAR_*` 제어 권한을 섞지 않는다. 최종 권한명은 각 메서드의 `@RequiresPermission`을 읽는다.
- 기능 지원과 사용 승인은 별개다. 프로젝트에서 사용할 수 없는 SDK나 승인되지 않은 제어를 정상 동작으로 보고하지 않는다.
- 앱 등록용 ID·secret은 대화나 로그에 출력하지 않는다. 기존 프로젝트의 비밀값 처리 방식을 따른다.
- Vehicle State/NaviHelper와 다른 SDK의 신청 상태는 동일하지 않다. 구체적인 기능에 착수할 때 실제 프로젝트 상태를 확인한다.

## Vehicle

1. 적절한 생명주기 소유자에서 `Vehicle(context)`를 만들고 `initialize()`를 호출한다.
2. `getEvBattery()`, `getHVAC()`, `getDoor()` 등으로 도메인 객체를 얻는다. 도메인 객체를 직접 생성하지 않는다.
3. 해당 기능의 capability를 확인한다. `AccessType`의 읽기·쓰기 가능 여부와 반환된 영역 목록을 사용한다.
4. 수치 범위는 콜백의 `Map<Area, AreaBounds<...>>`에서 얻는다. `CapabilityConfig.minValue` 같은 존재하지 않는 속성을 만들지 않는다.
5. 현재값을 읽고, 동작이 필요한 경우에만 변경 리스너를 등록한다.
6. 콜백 스레드와 기존 UI 프레임워크의 상태 갱신 규칙에 맞춰 화면을 갱신한다.
7. 등록했던 동일 리스너와 영역으로 해제하고, 사용 종료 시 `release()`한다.

Vehicle 2.3.2의 `initialize()`에 다른 SDK의 연결 콜백 오버로드가 있다고 가정하지 않는다. 현재 문서·시그니처를 확인한다.

setter의 완료 콜백이 요청값을 반환하는 경우, 그것을 새로 조회한 실제 차량 상태로 표시하지 않는다. 문서의 계약에 맞춰 상태 구독이나 현재값 조회로 실제 반영을 확인한다.

capability/get/set은 IPC 비동기 호출이다. 호출 직후 `release()`하거나 성공을 동기 반환값처럼 취급하지 않는다. 실패는 해당 기능의 오류 상태로 전달한다.

## 값과 단위

- SOC·연료 잔량: %.
- 충전 전류: A.
- 크루즈 목표 속도: m/s. 화면을 km/h로 보여주려면 변환한다.
- 총 주행거리: km. 트립 거리와 잔여거리 설정: m.
- 온도 범위의 AreaBounds: °C. °F 표시·입력은 지원되는 변환 설정과 API 오버로드를 확인한다.
- 창문·도어·풍량 범위는 콘솔의 0~100, 0~8 등을 차량 API의 고정 범위로 사용하지 않는다.
- `getLowTirePressure`는 저압 기준값이다. 현재 타이어 압력으로 표시하지 않는다.

## NaviHelper

- 목적지·경유지의 좌표, POI 식별자 등 필수 값은 실제 데이터로 준비한다.
- initialize와 listener 등록 후 요청한다. 요청 호출 성공과 경로 탐색 완료는 다르다.
- onRouteStarted/onRoutingCompleted/onError 등 필요한 이벤트로 상태를 관리한다.
- Trip 응답의 거리·ETA·예상 SOC·소비전력을 명시된 단위로 사용한다.
- `getChargerOperatorInfo`는 충전 사업자 정보다. 충전소 좌표·실시간 빈자리·요금이 필요하면 별도 데이터 연동을 확인한다.
- 종료 시 listener를 해제하고 SDK를 release한다.

## FusedLocation

- `isSupported()`는 서비스 패키지 존재 여부이며 위치 정확도 보장이 아니다.
- 위치 권한과 사용자 개인정보 설정을 확인한다.
- 현재값이 필요하면 마지막 위치를 요청하고 갱신 콜백을 구독한다. 오래된 위치의 timestamp를 무시하지 않는다.
- 영역 알림은 유효한 중심 좌표, 양수 반경, ENTER/EXIT를 지정한다. 반환된 alertId로 해제한다.
- 필요 없어진 위치 콜백·영역 알림을 해제한다.

## TTS·STT

- HYBRID/SERVER/ON_DEVICE의 가용성과 서버 등록 필요 여부를 확인한다.
- TTS는 언어·음성·AudioAttributes·audio zone과 오디오 포커스를 기능에 맞게 설정한다.
- 분할 텍스트 TTS는 마지막 조각에서 isFinal=true를 사용한다.
- STT의 `request()`는 마이크 입력 경로다.
- `sendAudio`와 `completeAudioSend`는 ON_DEVICE PCM 입력 경로다. 이 경로에서 request/onReady/onUpdatedRms가 필요하거나 발생한다고 가정하지 않는다.
- 성공·중간 결과·실패·취소·종료를 구분하고, 이벤트 리스너와 SDK를 해제한다.
- 인식된 문장은 텍스트일 뿐이다. 실행할 앱 동작은 별도로 구현한다.

## Gleo

- 사용자가 요청한 실제 앱 함수를 먼저 구현한다.
- `assets/schema.json`과 Manifest의 action filter를 준비한다.
- `activity`는 startActivity, `service`는 sendBroadcast/BroadcastReceiver다.
- ACTION_PARAM의 입력을 앱 경계에서 파싱·검증한다.
- success/asking/fail과 필요한 data를 응답 Broadcast로 반환한다.
- voice/beep/silent로 응답 출력을 선택한다. 앱 자체 음성과 Gleo 음성이 중복되지 않게 한다.
- 스키마의 최대 함수 수·지원 타입·타임아웃은 현재 가이드를 다시 확인한다. 스냅샷은 3개와 7초다.

## 주행 제한

- `CarUxRestrictionsManager`의 현재값과 변경 이벤트를 사용한다.
- DO 요구 상태와 개별 `ActiveRestrictions`를 구분한다.
- 이 프로젝트의 에뮬레이터에서는 D/R/N·0km/h도 제한 상태였으며 P 복귀 시 해제됐다.
- 그 관찰을 모든 국가·차종의 고정 규칙으로 하드코딩하지 않는다.
- 일반 앱이 주행 중 가려지는 것을 SDK 연결 실패로 오인하지 않는다.

## 화면·입력·미디어

- 기존 UI 프레임워크와 화면 구조를 유지한다.
- 입력창 있는 화면의 IME 리사이즈와 입력창 없는 Dialog의 Insets를 구분한다.
- 일반 설치 앱의 CCP 지원은 선택이다. 미지원이어도 필수 동작을 터치로 완료할 수 있어야 한다.
- 최상위 CCP Back은 앱 종료가 아니라 숨김 정책을 확인한다.
- 음악 앱은 미디어 탐색·세션·기본 재생 제어·차량 버튼 연동과 오디오 포커스를 처리한다.
- Suspend에서는 재생 중단·포커스 반환·상태 저장, Resume에서는 조건을 다시 평가한다.
- 가로·세로 또는 화면 크기가 관련되면 실제 폼 팩터에서 확인한다.

공식 가이드:

- https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission
- https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/check-capability
- https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide
- https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/music-audio-app-developement-guide
- https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/ccp-rotary-controller-development
- https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/ime-window-resize-handling-guide
