# 요청에서 SDK 고르기

파일명은 대소문자를 구분한다. 기능 파일의 `methods`와 동작 플래그를 읽은 뒤 현재 SDK 시그니처를 확인한다.

| 사용자가 말한 기능 | 먼저 읽을 파일 | 구분할 점 |
| --- | --- | --- |
| 배터리, 충전 잔량, SOC, 충전 시작·한도, 전비 | `references/vehicle/EvBattery.json` | SOC와 배터리 에너지·용량은 다른 값 |
| 기름, 연료 잔량, 연비 | `references/vehicle/Fuel.json` | 순간·현재·누적·주유 이후 구분 |
| 문, 도어, 잠금, 글로브 박스 | `references/vehicle/Door.json` | 도어 위치와 잠금은 별도 |
| 창문, 윈도우, 블라인드, 스마트 글라스 | `references/vehicle/Window.json` | 영역·위치·잠금·패널 제어 구분 |
| 에어컨, 공조, 실내 온도, 풍량, 성에 제거 | `references/vehicle/HVAC.json` | 현재 온도와 목표 온도 구분 |
| 좌석, 시트, 탑승, 안전벨트 체결, 통풍, 열선 | `references/vehicle/Seat.json` | 좌석 이동·메모리·탑승·공조 구분 |
| 조명, 앰비언트, 실내등, 안개등, 상향등 | `references/vehicle/Light.json` | 스위치 명령과 실제 상태 구분 |
| 주행거리, 트립, 주행 시간, 운행 기록 | `references/vehicle/Odometer.json` | 저장·일별 집계는 앱 로직 |
| 경고등, 에어백, 워셔액, 오일, 미착용 경고 | `references/vehicle/Safety.json` | 신호 조회를 정비 진단으로 과장하지 않음 |
| 타이어, 공기압 | `references/vehicle/Tire.json` + `references/compatibility.md` | 저압 기준값은 현재 공기압이 아님 |
| 브레이크를 밟았는지 | `references/vehicle/Brake.json` | 페달 작동 상태 조회; 제동 제어 API가 아님 |
| 주행 모드, 크루즈 상태·목표 속도 | `references/vehicle/DrivingMode.json` | 공개 목록은 조회 중심; 자동운전 구현으로 확대하지 않음 |
| 조향각, 핸들 열선 | `references/vehicle/SteeringWheel.json` | 전륜 조향각과 콘솔의 스티어링 입력을 구분 |
| 미러 열선 | `references/vehicle/SideMirror.json` | 미러 각도 조절 기능으로 추정하지 않음 |
| 방향지시등 상태 | `references/vehicle/TurnSignal.json` | 조회·구독 API |
| 와이퍼 | `references/vehicle/Wiper.json` | 스위치 설정과 실제 상태 구분 |
| 하이패스, ETCS 안내 음량 | `references/vehicle/Toll.json` | 요금소 시나리오 주입은 콘솔 쪽 기능 |
| 차대번호, VIN, CRN | `references/vehicle/CarInfo.json` | 개인정보·식별 정보의 실제 사용 목적 확인 |
| 섭씨·화씨 표시 단위 | `references/vehicle/Display.json` | 공개 목록에 단위 변경 setter는 없음 |
| 목적지, 경유지, 길 안내, 내비, Trip | `references/modules/NaviHelper.json` | 요청 전달과 경로 완료 이벤트 구분 |
| 충전소 찾기·추천 | `references/modules/NaviHelper.json`, 필요시 `references/vehicle/EvBattery.json` | 충전소 위치·요금·가용 정보의 공급자는 별도 |
| 위치, 특정 지역 진입·이탈, 지오펜스 | `references/modules/FusedLocation.json` | 지원 확인·권한·개인정보 설정 확인 |
| 글 읽기, 음성 안내, TTS | `references/modules/TextToSpeech.json` | 오디오 포커스·출력 존·언어·음성 |
| 음성 입력, 받아쓰기, 녹음 파일 인식, STT | `references/modules/SpeechToText.json` | 마이크 경로와 ON_DEVICE PCM 경로 구분 |
| Gleo야 …, 음성으로 앱 기능 실행 | `references/modules/Gleo.json` | 앱 함수와 Intent·Callback 구현 |
| 주행 중 버튼 숨기기, 주차 화면, 운전 중 잠금 | `references/implementation.md`의 주행 제한 | `CarUxRestrictionsManager`; 속도만으로 판단하지 않음 |
| 차속·기어 자체를 앱에서 읽기 | `references/compatibility.md` | 현재 공식 AAOS/API 계약 확인; 오래된 Vehicle 예제를 바로 쓰지 않음 |
| 음악, 라디오, 팟캐스트, 오디오북 | `references/implementation.md`의 미디어 | MediaBrowserService/MediaSession, 포커스, 차량 버튼 |
| 회전 노브, Back, 키보드, 가로·세로 화면 | `references/implementation.md`의 화면·입력 | CCP·IME·폼 팩터 가이드 적용 |

## 요청을 나누는 예

- “배터리가 20% 아래면 알려줘”: SOC 조회·구독 + 앱의 임계값 로직 + 현재 주행 상태에 맞는 알림.
- “실내를 24도로 맞추는 버튼”: 목표 온도 설정 + 좌석 영역·허용 범위 + 제어 권한.
- “자주 가는 장소를 경유지로 추가”: 앱의 저장 목록 + NaviHelper 경유지 요청·결과 이벤트.
- “Gleo로 메모를 추가”: 메모 저장 로직 + Gleo Action·Callback. STT를 별도로 붙일 필요가 있는지 먼저 구분.
- “주차하면 자세한 설정을 보여줘”: 플랫폼 제한 상태를 관찰해 UI를 전환. 기어·속도값만으로 독자 규칙을 만들지 않음.
