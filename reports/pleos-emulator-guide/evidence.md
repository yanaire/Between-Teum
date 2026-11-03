# Pleos 에뮬레이터 실험 증거

실험일: 2026-11-02 (호스트 날짜).
대상: Pleos Connect 2.1.0 / Android 14 ARM64 / Emulator 37.2.13.
Vehicle SDK 2.3.2, Vehicle Service 2.3.22.
앱 `dev.pleos.starter`에는 DO 선언과 차량 API 호출 코드가 없다.

## 직접 관찰한 상태 전환

| 조건 | Current Driving State | DO required | ActiveRestrictions |
| --- | --- | --- | --- |
| P, 0km/h | 0 | false | 0 |
| D, 0km/h | 1 | true | 80 |
| D, 가속 후 이동 | 2 | true | 80 |
| D, 다시 0km/h | 1 | true | 80 |
| R, 0km/h | 1 | true | 80 |
| N, 0km/h | 1 | true | 80 |
| P 복귀 | 0 | false | 0 |

- `dumpsys car_service`로 값을 읽었다. 진단상의 안전 지역은 `android.car.drivingsafetyregion.all`.
- 설치된 Android API 34의 `android.car.jar` 공개 상수: NO_VIDEO=16, NO_SETUP=64. 80=0x50=16|64.
- 가속 페달을 실제로 눌러 콘솔 30km/h 도달을 DOM 변화 이벤트로 관찰한 뒤 놓았다.
- `04-driving-starter.png`의 실제 화면 표시 속도는 16km/h다. 캡처 시각 차이를 30km/h로 잘못 표기하지 않았다.
- `06-driving-relaunch.png`는 D·0km/h 상태에서 일반 앱에 "주행 중에는 사용할 수 없습니다"가 남아 있는 화면이다.
- R/N은 진단값을 확인했다. 후진 이동 또는 후방 카메라 입력은 검증하지 않았다.
- 최종 정상 주차 화면은 `22-parked-ready.png`다.

## 직접 조작한 기능

- 공조: Off -> On, 운전석 목표 온도 22 -> 24°C, 풍량 3 -> 5.
  콘솔에서 세 값, 차량 화면에서 24.0°C와 A/C 표시를 확인했다.
- 에너지: SOC 100 -> 25%, 잔여거리 0 -> 120000m.
  차량의 배터리 잔량 도형과 120km 표시를 확인했다. 두 입력은 독립 설정이며 에너지 물리 모델 검증이 아니다.
- 차체: 앞왼쪽 문 Open, 앞왼쪽 창문 50%, 프렁크 Open.
  콘솔 상태와 차량의 문 그림·프렁크 경고를 확인했다. 유리의 3D 열림 비율은 계측하지 않았다.
- 프렁크가 열린 상태의 기어 변경 후 확인 창이 나타났다. 값을 복원한 뒤에도 확인 창이 남아 직접 버튼을 눌렀다.
- 마지막에 P, 도어/프렁크/창문 닫힘, 공조 Off, 온도 22°C, 풍량 3, SOC 100%, 잔여거리 0m로 복원했다.

## 콘솔의 실제 목록

콘솔 DOM에서 48개 카드와 7개 도메인을 세었으며 각 필터를 직접 열었다.

- Vehicle (1): Notification 1.
- Motion (5): Gear Selection, Brake Pedal, Odometer, Steering Angle, Steering Wheel Heat.
- ADAS (2): Cruise Control State, Cruise Target Speed.
- Convenience (20): ETCS, 도어/트렁크/창문, 조명, 와이퍼, 탑승 상태, 글로브 박스 관련 카드.
- Safety (1): Telltales.
- Energy (8): 잔여거리, 에너지, SOC, 용량, 충전 전류/퍼센트 한도, 충전 스위치/포트.
- Climate (11): 공조 전원/자동/듀얼, 냉방, 순환, 풍량/방향, 제상, 온도, 좌석 열선/통풍, 공기청정.

카드 존재만 확인한 기능을 실제 앱 API 검증 성공으로 표시하지 않았다.

## 실행 중 확인된 한계

- 기존 host 실행과 SwiftShader 실행이 각각 exit 139로 종료됐다.
- SwiftShader는 차량 3D 표현 일부가 비어 있었다.
- 이후 `-gpu host -feature -Vulkan -camera-back none -camera-front none`으로 실행해 후반 캡처를 진행했다.
  이것으로 장시간 안정성이 증명된 것은 아니다. AVD 영구 설정은 변경하지 않았다.
- Headless 테스트 브라우저에서 Highway는 WebGL 2 필요 오류가 났다. Forest는 실제로 정상 표시됐다.
- 공식 ADB 페이지의 `hmg_car_service`는 해당 이미지에 존재하지 않았다.
- `cmd car_service help`는 non-user build가 필요하다는 SecurityException을 반환했다.
  root나 권한 우회를 시도하지 않았다. 공식 콘솔과 허용된 `dumpsys`만 사용했다.

## 산출물

- `guide.html`: 편집 가능한 보고서 원본.
- `Pleos_Emulator_Field_Guide_KO.pdf`: 공유용 PDF.
- `assets/`: 실제 원본 캡처. 중간 전환 상태·경고 화면도 보존돼 있으므로 최종 PDF 캡션을 기준으로 해석한다.
- `qa/page-*.png`: 최종 PDF의 전 페이지 렌더.
- `render-pages.swift`: PDFKit으로 전 페이지를 렌더링하고 선택 가능한 텍스트 수를 확인하는 도구.

실차, Gleo 음성 호출, GPS, 미디어 포커스, 다중 디스플레이, 제3자 차량 API 권한은 이번 실험에서 검증하지 않았다.

## 최종 PDF 검수

- A4 10쪽을 PDFKit으로 모두 렌더링했다. 모든 페이지에서 선택 가능한 텍스트가 추출됐다.
- 브라우저에서 한글 폰트와 10개 삽입 이미지의 로드를 확인했다.
- 최종 DOM에서 본문과 바닥글의 간격은 모두 양수였으며, 겹침이 없었다.
- 본문 검수와 전 페이지 시각 검수를 독립적으로 수행한 두 검수자 모두 PASS를 반환했다.
- 01~10쪽 전체의 한글, 표, 캡션, 스크린샷, 출처 줄바꿈과 페이지 경계를 직접 확인했다.
- 원본 캡처의 작은 UI 글씨는 캡션과 본문으로 설명했으며, PDF에 고해상도 이미지를 포함했다.
- 테스트 브라우저와 종료된 임시 프로필을 정리했다. 앱 소스와 AVD 영구 설정은 이번 보고서 작업에서 수정하지 않았다.
