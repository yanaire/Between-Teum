<img src="assets/icon.svg" width="64" height="64" alt="">

# 현대차공모전

Created in [OmO](https://omo.dev).

## Pleos 개발환경

이 프로젝트는 Pleos Connect용 Kotlin Android 앱의 시작점입니다.
기본 화면과 Vehicle SDK 의존성을 포함하며, 차량 API 호출이나 제어 권한은 아직 추가하지 않았습니다.

### 설치 현황

2026-11-02 기준:

- 설치 완료: Android Studio 2026.2, Android 명령줄 도구 22.0, Temurin JDK 21.0.13.
- SDK 설치 완료: API 34/35, Build-Tools 35.0.0, Platform-Tools 37.0.1, Emulator 37.2.13.
- Pleos 설치 완료: Connect 2.1.0 ARM64 이미지, Vehicle Service 2.3.22.
- AVD: `Pleos_Connect_2_1_0`, RAM 4GB, 2560x1440, GPU host, Cold boot.
- 빌드 검증: `./gradlew --no-daemon clean assembleDebug lintDebug` 성공, lint `No issues found`.
- 실행 검증: Android 14 / ARM64 부팅, APK 설치 성공, `MainActivity` 실행 및 화면 확인.
- 로컬 실행 증거: `.omo/evidence/pleos-setup.png` (Git 제외).

이 화면은 개발환경 확인용입니다. Vehicle SDK의 실제 API 호출 검증은 앱 기능을 구현할 때 수행합니다.

### 프로젝트 버전

| 항목 | 버전 |
| --- | --- |
| Vehicle SDK | 2.3.2 |
| Android Gradle Plugin | 8.8.2 |
| Kotlin | 2.0.0 |
| Gradle wrapper | 8.10.2 |
| compileSdk | 35 |
| targetSdk | 35 |
| minSdk | 28 |
| Java/Kotlin 바이트코드 | 17 |
| 실행 대상 | Pleos Connect 2.1.0, Android API 34, ARM64 |

빌드 도구는 공식 Vehicle SDK Sample App의 조합을 기준으로 선택했습니다.
`compileSdk` 35는 빌드용이며, API 34 에뮬레이터에서 실행할 수 있습니다.
`targetSdk` 35는 공식 Sample App과 동일하며, 실제 배포 전에는 최신 배포 요건을 확인하세요.

### 로컬 경로

- Android Studio: `/Applications/Android Studio.app`
- 빌드용 Java: Temurin JDK 21.0.13 (`$HOME/Library/Java/JavaVirtualMachines/jdk-21.0.13+9/Contents/Home`)
- Android SDK: `$HOME/Library/Android/sdk`
- SDK 명령줄 도구: `$ANDROID_HOME/cmdline-tools/latest/bin`
- Gradle의 SDK 경로: `local.properties` (Git 제외)
- Android Studio의 Gradle JDK: `.idea/gradle.xml`과 `.gradle/config.properties` (Git 제외)
- 셸 환경변수: `~/.zprofile`의 `Pleos / Android development` 항목

### Android Studio에서 열기

1. Android Studio에서 이 디렉터리를 엽니다.
2. Gradle JDK는 `GRADLE_LOCAL_JAVA_HOME`으로 설정되어 있으며, 설치된 Temurin JDK 21을 참조합니다.
3. SDK 및 Gradle 동기화가 끝나면 실행 대상으로 `Pleos_Connect_2_1_0`을 선택합니다.
4. `app` 실행 구성을 Run 합니다.

Android Studio 내장 Java 25는 Gradle 8.10.2와 호환되지 않으므로 빌드용 JDK로 선택하지 마세요.
이 저장소를 다른 컴퓨터에 복사하면 `local.properties`와 로컬 JDK 설정을 해당 컴퓨터 경로로 지정해야 합니다.

### 에뮬레이터 실행

Android Studio의 Device Manager에서 `Pleos_Connect_2_1_0`을 실행하거나 다음 명령을 사용합니다.

```sh
source ~/.zprofile
emulator -avd Pleos_Connect_2_1_0 -no-snapshot -gpu host
```

설치 검증에 사용한 에뮬레이터가 이미 실행 중이라면 다시 실행하지 않아도 됩니다.
차량 상태 테스트 도구 `ai.pleos.connectwebconsole`도 시스템 이미지에 포함되어 있습니다.

### 터미널에서 빌드

새 로그인 터미널을 열거나 `source ~/.zprofile`로 환경변수를 적용합니다.

```sh
./gradlew assembleDebug lintDebug
./gradlew installDebug
adb -s emulator-5554 shell am start -n dev.pleos.starter/.MainActivity
```

APK 경로: `app/build/outputs/apk/debug/app-debug.apk`
여러 기기를 연결한 경우 `adb devices`에서 대상 시리얼을 확인하세요.

### 확인된 도구 안내

- AGP 8.8.2와 최신 SDK 도구 조합에서 `SDK XML versions up to 3 ... version 4` 안내 경고가 출력됩니다.
  경고를 숨기지 않았으며, 이 조합으로 클린 빌드·lint·Pleos에서의 앱 실행을 검증했습니다.
- 최신 Android 명령줄 도구는 `sdkmanager` 대신 `android sdk` 사용을 안내합니다.
  설치된 `sdkmanager`와 `avdmanager`로 SDK 설치와 AVD 생성이 정상 동작하는 것을 확인했습니다.

차량 API를 사용할 때는 필요한 권한만 Manifest에 선언하고,
Capability 확인 및 비동기 결과 처리를 구현해야 합니다.
SDK 의존성이 포함됐다는 사실만으로 Vehicle Service 연결이나 API 사용 권한이 검증되는 것은 아닙니다.

### 공식 문서

- [개발 환경 구성](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)
- [Vehicle SDK Sample App](https://document.pleos.ai/docs/pleos-only/tutorial/vehicle-sdk-sample-app)
- [SDK 릴리즈 노트](https://document.pleos.ai/docs/pleos-only/release-note/sdk-release-note)
