# Pleos Connect UI 가이드 분석 (Foundation)

이 문서는 Pleos Connect UI Design Guide 전체를 분석해 틈틈에 필요한 값만 정리한 레퍼런스다.

**분석한 자료**

- 가이드 웹 페이지 34개: 소개, 레이아웃, 창 정책, 컬러, 타이포, 아이콘, 앱 아이콘, 애니메이션, 그림자, 컴포넌트 25종
- '주행 안전을 고려한 설계', '앱 구조 및 동작 모델' 페이지
- 가이드가 배포하는 Figma 원본 두 개 (v2.1.5)
  - `Pleos_Connect_design_assets_215.fig`: 컬러 변수 722개, 폼팩터별 크기 변수 452개, 텍스트 스타일
  - `Pleos_Connect_display_guide_215.fig`: 폼팩터 표, 창 크기 변수

웹 페이지의 수치 대부분은 이미지로만 나와 있다. 그래서 숫자는 **Figma 원본의 변수 값**을 기준으로 삼았다. 원본 값을 정리한 토큰은 `tokens/pleos.tokens.json`에 있다.

> 단위: 모든 px는 **Connect-S 17인치 (160dpi, 1px = 1dp)** 기준이다. 다른 폼팩터 값은 `pleos.tokens.json`의 `S14`, `L`, `W`, `WF` 열을 쓴다.

---

## 1. 기본 원칙 (가이드 원문 요약)

| 원칙 | 틈틈이 지킬 것 |
|---|---|
| **사용성과 안전성** | 운전 중에는 동영상, 움직이는 이미지, 텍스트 자동 스크롤로 시선을 끌지 않는다. 한 손으로 단순하게 조작할 수 있어야 한다 |
| **일관성** | 아이콘, 용어, 상호작용 패턴을 Pleos 기본 UI와 맞춰 학습 부담을 줄인다 |
| **즉시성** | 정보는 1~2초 안에 파악되어야 한다. 입력 후 0.25초 안에 응답하고, 더 걸리면 응답 중 상태를 보여 준다 |
| **대비** | 아이콘, 텍스트, 이미지의 대비는 4.5:1 이상이어야 한다. 중요한 정보는 강한 대비로 보여 주고, 과도한 색은 피한다 |
| **글자 크기** | 중요 정보 (속도, 길 안내, 경고)는 글자 높이 5mm 이상, 보조 텍스트는 3mm 이상이다. Android 최소는 12sp다 |
| **문장 길이** | 한 메시지는 영문 기준 120자 또는 24단어 이하, 한 줄은 80자 이하, 한 단락은 3줄 이하다 |
| **아이콘** | 국제적으로 통용되는 아이콘을 텍스트 라벨과 함께 쓴다. 터치하면 햅틱이나 음성으로 피드백한다 |
| **알림** | 운전자에게 필요할 때만 보낸다. 광고는 금지다 |
| **주간·야간** | 조명 환경에 따라 밝기와 대비를 조정한다 (라이트·다크 테마) |
| **음성** | Gleo AI 음성 상호작용을 적극 검토한다. 음성 대화는 수동으로도 시작, 종료, 중단할 수 있어야 한다 |
| **확장성** | 다국어와 여러 해상도·비율에 유연하게 대응한다 |

**160dpi에서 글자 높이 환산**: 1mm ≈ 6.3px이다. 한글 글리프 높이는 font-size의 약 0.8배다.

- 5mm ≈ font-size 40px (Title L) 이상
- 3mm ≈ font-size 24px (Label M) 이상

---

## 2. 디스플레이와 폼팩터

| ID | 이름 | 비율 | 인치 | DPI | 해상도 | Landscape 앱 창 | Portrait 앱 창 |
|---|---|---|---|---|---|---|---|
| **S** | Connect-S-17 (기본) | 16:9 | 17 | 160 | 2560 × 1440 | **1700 × 1184** | **840 × 1184** |
| S156 | Connect-S-15.6 | 16:9 | 15.6 | 160 | 2172 × 1222 | 1408 × 1018 | 692 × 1018 |
| S14 | Connect-S-14.6 | 16:9 | 14.6 | 200 | 2048 × 1152 | 1360 × 948 | 672 × 948 |
| L | Connect-L-12.9 (Connect-S로 재분류) | 16:9 | 12.9 | 160 | 1920 × 1080 | 1274 × 855 | 628 × 855 |
| W | Connect-W-Standard | 9:2 | 24.6 | 160 | 3840 × 850 | 1640 × 810 | 800 × 810 |
| WX | Connect-W-Extended | 24:9 | 24.6 | 160 | 3840 × 1440 | 1640 × 1400 | 800 × 1400 |
| WF | Connect-WF (Waterfall) | 32:9 | 18.5 | 200 | 3072 × 816 | 1256 × 800 | 648 × 800 |

### Connect-S 17인치 화면 해부 (2560 × 1440)

```
┌──────────────────────────────────────────────────────────────────────────┐
│ Status bar  96                                                           │
├───────────────┬──────────────────────────────────────────────────────────┤
│               │  ┌────────────────────┐ 20 ┌────────────────────┐        │
│ Driving view  │  │  App (portrait)    │    │  App (portrait)    │        │
│ 840 wide      │  │  840 × 1184        │    │  840 × 1184        │        │
│ (텔테일·속도·   │  │  radius 40         │    │                    │        │
│  차량 상태)     │  └────────────────────┘    └────────────────────┘        │
│               │  └──────── Landscape 앱 하나면 1700 × 1184 ─────────┘     │
├───────────────┴──────────────────────────────────────────────────────────┤
│ GNB  160  (운전석 공조 · 앱 슬롯 · App library · 최근 앱 · 보조석 공조)       │
└──────────────────────────────────────────────────────────────────────────┘
```

- **세로 합계**: Status bar 96 + 앱 영역 1184 + GNB 160 = 1440
- **가로 합계**: Driving view 840 + 앱 영역 1700 (840 + 간격 20 + 840) + 20 = 2560
- **앱 창**: 모서리 라디우스 40, 창 사이 간격 20
- **Status bar** (높이 96)
  - 왼쪽: 도어락, 검색, 알림 센터, 통화 상태, 개인정보 표시
  - 오른쪽: 프로필, 블루투스, 네트워크, 시계
  - 메뉴 버튼은 80 정사각형, 아이콘 56
- **GNB** (높이 160)
  - 운전석 공조, 차량 설정, 편집 슬롯 (운전석 시트 열선)
  - 내비 고정, 편집 슬롯 최대 3개, App library, 최근 앱 2개
  - 보조석 편집 슬롯, 보조석 공조
  - 앱 아이콘은 84 정사각형, 라디우스 24
- **Connect-W**: GNB가 세로 막대 (폭 140)로 바뀌고, Status bar는 운전석 GNB와 앱 영역 사이에 세로로 들어간다

---

## 3. 창 정책과 앱 동작 모델

- **앱 창 수**
  - Connect-S는 앱 둘이면 1:1, 하나면 2:0이고, 기본값은 1:1이다.
  - Connect-W는 2:1 또는 1:2이고, 기본값은 2:1이다.
  - 어느 경우든 한 번에 최대 2개다.
- **Portrait가 기본이다.**
  - `screenOrientation`을 따로 지정하지 않으면 Activity는 portrait로 실행되고 window 하나를 쓴다.
  - landscape로 지정하면 window 두 개를 합쳐 쓴다.
- **설계 원칙**: Portrait에서 핵심 기능과 주요 정보를 완결하고, Landscape에서는 넓어진 영역에 부가 정보와 확장 기능을 더한다. 두 형태를 각각 설계한다.
- **새 앱의 방향**: 새 앱은 지금 떠 있는 앱의 방향을 따른다.
  - landscape 앱 하나가 있으면 새 앱도 landscape로 뜬다.
  - portrait 둘이 있으면 새 앱은 primary window에 portrait로 뜬다.
- **위치·크기 변경**: 사용자는 세 손가락 스와이프나 window bar로 위치를 바꾸고, landscape와 portrait를 전환한다. Window bar는 손잡이 140 × 8이다.
- **백그라운드**: 화면에서 사라진 앱은 종료되지 않고 백그라운드에 남는다.
  - 포그라운드로 돌아오면 상태를 복원한다.
  - 백그라운드에서는 애니메이션, 화면 갱신, 불필요한 센서 수집을 멈춘다.
- **시스템 레이어**: Status bar, GNB 패널 (App library, 공조), 키보드는 앱보다 위 레이어다. 사용 중에는 앱을 가릴 수 있다.
- **Driving view**: 운전자 가까이에 고정되어 있다. 앱이 가리거나 닫을 수 없다.
- **OSD**: 스티어링 휠 물리 버튼을 조작하면 상태 패널이 뜬다. Connect-S에서는 GNB 영역에 뜨고, 2초 뒤 사라진다.

---

## 4. 타이포그래피

- **시스템 폰트**: Asta Sans (한글·영문)
- **피처 폰트**: Manrope. Driving view 속도, Ambient AI 말풍선 같은 특수 숫자·문구에 쓴다.
- **브랜드 폰트**: 브랜드는 아이덴티티에 맞는 폰트를 정할 수 있다. 단, 캐릭터가 강한 폰트는 지양한다. OEM 테마는 Hyundai, Kia, Genesis Sans UI를 쓴다.

| 토큰 | 크기 / 행간 | 웨이트 |
|---|---|---|
| headline.l | 60 / 80 | ExtraBold, Bold |
| headline.m | 56 / 72 | ExtraBold, Bold |
| headline.s | 48 / 64 | ExtraBold, Bold |
| title.l | 40 / 52 | ExtraBold, Bold, Regular |
| title.m | 36 / 44 | ExtraBold, Bold, Regular |
| title.s | 32 / 40 | ExtraBold, Bold, Regular |
| body.l | 30 / 38 | Bold (Strong), Regular (Normal) |
| body.m | 28 / 36 | Bold, Regular |
| body.s | 26 / 34 | Bold, Regular |
| label.m | 24 / 28 | Regular |
| label.s | 20 / 24 | Regular |
| feature.speed | 112 / 134, 자간 −3% | Manrope Bold |
| feature.bubble | 30 / 48 | Manrope ExtraBold, Bold |

- **폼팩터별 크기**: `pleos.tokens.json` → `typography.sizeByFormFactor`에 있다. 예를 들어 size_sp level5 24px은 L에서 21, W에서 20, WF에서 26이다.
- **위계 규칙**: 같은 위계의 정보에는 컴포넌트가 달라도 같은 크기를 쓴다. 보조 텍스트는 위계를 분명히 구분한다.

---

## 5. 컬러

### 5-1. 구조

| 층 | 내용 |
|---|---|
| **Scale (basic)** | `basic/00`~`basic/900`. 다크에서는 순서가 뒤집힌다 (다크 `basic/00` = #131417, `basic/900` = #FFFFFF) |
| **Alpha** | `alpha/dark/50~500`, `alpha/light/50~500`. 배경 위 겹침용 |
| **Static** | `static/dark/100~400`, `static/light/100~400`. 테마와 상관없이 고정. 오버레이 위 요소에 쓴다 |
| **System (시맨틱)** | Background, Surface, Text, Icon, Button, Divider, Dim, Overlay, Field, Switch, Controller, Informative, Regulation 등 |

### 5-2. 틈틈이 쓰는 시맨틱 토큰

| 토큰 | 다크 | 라이트 | 용도 |
|---|---|---|---|
| background/secondary | #131417 | #FFFFFF | 앱 기본 배경 (가장 낮은 층) |
| background/primary | #1D1E21 | #F7F8FA | 구획 배경 |
| background/popup | #313236 | #FFFFFF | 팝업 |
| surface/basic | #1D1E21 | #FFFFFF | 카드 |
| surface/high | #313236 | #EDEEF2 | 강조 카드, 선택 |
| surface/accent | #44464E | #E0E1E6 | 더 강한 강조 |
| text/dark/primary | #FFFFFF | #131417 | 본문 (대비 18.4:1) |
| text/dark/secondary | 흰색 84% | 검정 84% | 보조 (13.1:1) |
| text/dark/tertiary | 흰색 64% | 검정 64% | 메타 (7.9:1) |
| text/dark/quaternary | 흰색 32% | 검정 32% | 비활성만 (2.9:1) |
| button/basic/enabled | 흰색 5% | 검정 5% | 기본 버튼 배경 |
| button/basic/pressed | 흰색 10% | 검정 10% | 눌림 |
| button/filled/enabled | #E0E1E6 | #44464E | 채운 버튼 (OS 기본 강조) |
| divider/primary·secondary·tertiary | 흰색 5·10·20% | 검정 5·10·20% | 구분선 |
| overlay/default | #131417 50% | #131417 20% | 팝업 뒤 딤 |
| informative/active | #00E074 | #02C265 | 켜짐, 활성 (스위치 on) |
| informative/positive | #0082FF | #0064FF | 정보 |
| informative/negative | #FF4C28 | #FE3D16 | 경고, 종료 |
| regulation/red·orange·yellow·green·blue | #FF4339, #FF8A00, #FFC224, #55F165, #0082FF | 각 라이트 값 | 법규 색. 앱이 임의 용도로 쓰지 않는다 |

전체 129개 토큰의 라이트·다크 값은 `pleos.tokens.json` → `color`에 있다.

---

## 6. 간격, 라디우스, 그림자, 선

- **간격**: 4, 8, 12, 16, 20, 24, 32, 40, 48. 프리미티브는 spacing-03 = 8, 04 = 12, 05 = 16, 07 = 32다.
- **라디우스**: 4, 8, 16, 20, 24, 32, 999 (원)
  - 앱 창 40
  - 팝업과 시스템 알림 48
  - 큰 버튼 32, 중간 버튼 24, 작은 버튼 16
- **선**: 1, 2, 3, 4, 6
- **그림자**

| 레벨 | 값 | 용도 |
|---|---|---|
| level1 | y6 blur24 검정 8% | 가장 낮은 elevation |
| level2 | y8 blur32 검정 16% + y2 blur2 검정 8% | 중간 |
| level3 | y20 blur40 검정 24% + y2 blur4 검정 20% | 가장 높은 elevation (팝업) |

- **정사각 단계**: 32, 36, 40, 44, 52, 56, 60, 72, 80, 100. 아이콘과 썸네일 크기에 쓴다.

---

## 7. 아이콘, 앱 아이콘, 애니메이션

- **아이콘**: 48 × 48dp 그리드로 한 벌을 만들고, 필요한 크기로 조정한다. 스타일은 Filled와 Outlined 두 가지다. 텍스트 라벨과 함께 쓴다.
- **앱 아이콘**: Android Adaptive Icon 형식이다. Foreground (로고)와 Background 레이어로 나누고, 브랜드별 마스킹을 따른다. GNB에서는 84px, 라디우스 24로 보인다.
- **애니메이션**: 상태를 설명하기 위한 용도로만 쓴다.
  - 로더는 두 가지다: Linear (짧은 로딩), Tension (긴 로딩).
  - 주행 중에는 시선을 끄는 자동 애니메이션을 최소화한다.

---

## 8. 컴포넌트 (Connect-S 크기)

| 컴포넌트 | 크기·형태 | 상태·속성 | 틈틈에서 쓰는 곳 |
|---|---|---|---|
| **Basic button** | L: 높이 100, 라디우스 32, 좌우 패딩 40, 최소 폭 140, 아이콘 52. M: 80, 라디우스 24, 패딩 32, 최소 120, 아이콘 40. S: 64, 라디우스 16, 패딩 24, 최소 100, 아이콘 32 | Pressed, Disabled, Loading. Prefix·Suffix 아이콘 | 주 버튼 (L), 보조 버튼 (M) |
| **Toggle button** | Basic과 같은 크기 L, M, S | Toggled, Pressed, Disabled. Floating (그림자) | 기분 선택 (F1-02), 사운드 칩, 시간 칩 (F2-01a), 하루 색 |
| **Text button** | M: 아이콘 32, 간격 8. S: 아이콘 28, 간격 4 | Pressed, Disabled, Loading | [끝내기], [오늘은 패스], [건너뛰기] |
| **Icon button** | M 80 × 80 (아이콘 56), S 64 × 64 (아이콘 44). 스타일 Basic, Plain, Circle. 둥근 사각 라디우스 20·16 | Pressed, Disabled | 마이크 보조, 화면 헤더 아이콘 |
| **Keypad** | 높이 108, 최소 폭 200, 라디우스 32, 아이콘 48 | Primary, Secondary, Special. Text 또는 Voice | 사용하지 않음 (시스템 키보드 사용) |
| **Tabs** | Solid L: 컨테이너 100 (라디우스 32, 패딩 8), 탭 84 (라디우스 24). Solid M: 컨테이너 80 (라디우스 24), 탭 64 (라디우스 16). Line: 선택 밑줄 4, 탭 좌우 패딩 14 | Enable, Pressed, Selected. Box/Line, Fitted | R-01 주 전환, 세션 길이 선택 (10·15·20분) |
| **Switch** | M: 폭 80, 손잡이 32, 패딩 8, 라디우스 16. S: 64, 손잡이 28 | Checked, Pressed, Disabled, Invalid. 켜짐 색 informative/active | ST-04 틈 알림 켜고 끄기, 음성 안내 |
| **Checkbox** | 컨테이너 L 64 / M 48, 라디우스 12, 테두리 4 | Checked, Pressed, Disabled | ON-03 권한 항목 |
| **Radio** | 컨테이너 L 64 / M 48 | Selected, Pressed, Disabled, Invalid | ST-03 기본 사운드 |
| **Dropdown** | M: 높이 100, 라디우스 32. S: 80, 라디우스 28. Prefix (앱 아이콘·아이콘), Suffix (스위치·아이콘) | Opened, Pressed, Disabled | ST-02 요일별 예외 |
| **Text field** | M: 높이 100, 라디우스 24, 패딩 32, 테두리 3, 접미 아이콘 48. S: 80, 테두리 2 | Value, Focused, Disabled, Read only, Invalid. Text, Tel, Url, Email, Password | ON-04 주소 검색, F3-00 제목 (음성 우선) |
| **Number field** | L 140 × 180, M 128 정사각, 라디우스 32, 커서 4 × 48 | 위와 같음 | 사용하지 않음 |
| **Spinner (wheel)** | 항목 높이 88, 패널 M 288 / L 384, 라디우스 32, 시간 최소 폭 480, 날짜 540 | Date, Time | ON-05 출근·점심 복귀 시각, F2-01a 복귀 시각 |
| **Controllers** | Continuous slider: 높이 L 100 / M 80 / S 72, 라디우스 32, 손잡이 라디우스 20. Centered slider: 트랙 8, 손잡이 48 (눌림 56). Stepper: 420 × 100, 라디우스 24 | Pressed·Dragging. Min, Max, Step | ON-05 걷는 시간 (Stepper), F2-03 등받이 각도 (Stepper) |
| **Indicator** | 현재 값, 이전·다음 값 | Present. Wide, Narrow | ON-02 컨셉 카드, F1-05 가면 카드 위치 |
| **Page navigation** | 높이 80, 앱 아이콘 80 (라디우스 20), 뒤로 아이콘 56, 좌우 패딩 40, 그룹 간격 48 | Main, Depth (뒤로), Primary·Secondary 메뉴, Subtitle, Suffix 아이콘 | 홈·기록·설정 상단 내비게이션 |
| **System popup** | M 폭 808, L 1200, 최대 높이 1152, 라디우스 48, 패딩 48, 헤더 패딩 32, 아이콘 80, 닫기 48, 테두리 2 | Header (아이콘, 부제, 설명), Body, 버튼 정렬 Single·Vertical·Horizontal | C-02 끝내기 확인, F2-01a 시간 바꾸기, I-02, I-03, M-01 카드 크기 기준 |
| **Toast popup** | 최대 폭 792 (메시지 712), 패딩 40 × 24, 라디우스 16, 아이콘 48, 앱 패널 하단·좌우 24 여백 | 일시 알림 | C-04 스탬프, T-01 안내 |
| **System notification** | 폭 700, 라디우스 48, 패딩 24·32, 이미지 100, 앱 아이콘 48 | App icon, Profile, Image 타입. 버튼 정렬 | V-01과 함께 쓰는 확장 (선택) |
| **Bar** | Window bar 손잡이 140 × 8. Scroll bar 폭 6, 최소 높이 48 | Dragging, Pressed, Disabled. Light, Dark | 긴 목록 스크롤 (R-03, ST) |
| **Widgets** | 손잡이, 페이지 표시, 제목·액션, 정보 영역 | 아래로 끌어 숨김 | 확장: '오늘의 틈' 위젯 (P2) |
| **Vehicle settings list** | 항목 높이 112, 아이콘 컨테이너 80, 라디우스 24, 리스트 라디우스 32 | — | ST 설정 목록, ST-07 차량 지원 현황 |

**가이드에 없는 것 → 틈틈의 처리**

| 없는 것 | 대신 쓸 것 |
|---|---|
| 하단 시트 | System popup (가운데 모달) |
| 원형 진행 링 | 엣지 라이트 (틈틈 브랜드 요소, 가로 선) |
| 카드 캐러셀 | 가로 스와이프 + Indicator |
| 큰 마이크 버튼 | Icon button Circle을 키운 브랜드 변형 (지름 160) |

---

## 9. 주행 안전 설계 (앱 기획·설계 가이드)

- `CarUxRestrictions`로 주행 제한 상태를 확인하고, 제한 대상 기능의 UI와 동작을 줄이거나 끈다.
- 주행 중에는 동영상, 애니메이션, 움직이는 이미지를 최소화한다. 장식적이거나 반복적으로 움직이는 요소는 쓰지 않는다.
- 자주 쓰는 기능은 첫 화면과 상단에 둔다. 드롭다운과 다단 메뉴는 남용하지 않는다.
- 큰 터치 영역, 높은 대비, 최소 텍스트를 쓴다. 버튼 크기와 간격은 UI 가이드를 따른다.
- 키보드 입력보다 프리셋, 최근 항목, 음성 입력을 먼저 쓴다.
- 운전에 중요한 오디오 안내가 나오면 오디오 포커스 정책에 따라 미디어를 멈추거나 줄인다.

---

## 출처

- [Pleos Connect UI Design Guide](https://document.pleos.ai/docs/connect/guide/docs-design/Introduction/intro)와 하위 33개 페이지
- [주행 안전을 고려한 설계](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driver-distraction-guideline)
- [앱 구조 및 동작 모델](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model)
- 가이드 하단 Design Resources Download의 Figma 파일 두 개 (v2.1.5)
