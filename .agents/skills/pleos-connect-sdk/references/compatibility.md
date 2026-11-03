# 버전·공개 범위 확인

이 스킬은 2026-11-02의 공식 문서와 설치된 Vehicle 2.3.2를 대조했다. 공개 문서의 19개 도메인·458개 메서드를 102개 기능으로 묶은 목록이며, 현재 버전의 존재·지원·권한을 확인하는 출발점이다.

## 자동으로 사용하지 않을 항목

다음 이름은 당시 SDK 파일에만 확인됐고 공개 API 목차에는 없었다. 금지된 API라고 단정하지도, 승인된 공개 API라고 단정하지도 않는다. 관련 요청에서는 현재 공식 문서, 현재 SDK 계약과 허용된 AAOS 대안을 먼저 확인한다.

| 영역 | 이름 |
| --- | --- |
| CarInfo | getCapabilityInfo |
| EvBattery | getEvBatteryLevel, getEvBatteryCapacity, isEvChargePortOpen, register/unregisterEvBatteryLevel, register/unregisterEvChargePortOpen |
| Odometer | getRangeRemainingDistance, register/unregisterRangeRemainingListener |
| Tire | checkTirePressureCapabilities, getTirePressure, register/unregisterTirePressure |
| Gear | Vehicle.getGear, getCurrentGearSelectionState, register/unregisterGearSelection |

Vehicle 예제의 `getCurrentSpeedDisplayUnit`은 당시 Display 클래스에 없었다. 예제의 이름만 보고 메서드를 생성하지 않는다.

## 문서와 콘솔을 구분

- 콘솔의 배터리 에너지·용량 입력이 앱의 공개 getter 존재를 보장하지 않는다.
- 콘솔의 크루즈/Full autonomous 상태 주입은 자율주행 제어 API가 아니다.
- 공개 문서에 getter나 setter가 없는 기능은 임의로 반대 동작을 만들어 내지 않는다.
- SDK 서비스가 설치돼 있는 것과 프로젝트가 해당 SDK를 사용할 수 있는 것은 다르다.

## 환경 메모

이 프로젝트는 Gradle 8.10.2를 JDK 21로 빌드했다. Android Studio 내장 Java 25와 이 Gradle 조합은 호환되지 않았다.

Emulator 37.2.13에서 host와 SwiftShader 실행의 비정상 종료를 관찰했다. 후반 촬영은 `-gpu host -feature -Vulkan -camera-back none -camera-front none` 조건에서 진행했으나 장시간 안정성이 입증된 설정은 아니다. 실패 원인을 가리지 않고 기록하며, 새 프로젝트에 이 옵션을 무조건 적용하지 않는다.

일부 API 페이지는 텍스트 추출 시 제목만 보였다. 실제 본문은 문서 사이트의 정적 Dokka HTML 데이터에 포함돼 있었다. 제목만 읽고 지원 기능을 추측하지 말고, 공식 원문·Context7·현재 SDK 시그니처 중 적절한 근거를 확보한다.
