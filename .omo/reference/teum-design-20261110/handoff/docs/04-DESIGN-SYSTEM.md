# 틈틈 디자인 시스템 v3 (Pleos 기반)

## 0. 구조: 두 개의 층

틈틈의 디자인 시스템은 두 층으로 쌓는다.

| 층 | 무엇을 정하나 | 원천 |
|---|---|---|
| **Layer 1 · Pleos Foundation** (따른다) | 화면·창 크기, 글자 위계, 간격, 라디우스, 그림자, 시맨틱 컬러 (배경·표면·텍스트·버튼·구분선), 컴포넌트의 구조·크기·상태, 아이콘 그리드, 주행 안전 규칙 | `tokens/pleos.tokens.json`, `docs/07-PLEOS-FOUNDATION.md` |
| **Layer 2 · 틈틈 Brand** (얹는다) | 빛의 색 (가면 라이트, 앰버, 문라이트), 시그니처 '틈 라이트', 엣지 라이트, 목소리 서체, 큰 숫자, 연출 모션, 카피 톤 | `tokens/tokens.json`, 이 문서 |

**원칙**: 사용자가 손으로 만지는 모든 것 (버튼, 탭, 스위치, 팝업, 토스트, 내비게이션)은 Pleos 기본 앱과 같은 크기와 동작이어야 한다. 그래야 학습 부담이 없다 (Pleos '일관성' 원칙). 틈틈다움은 **빛, 문장, 숫자, 연출**에서만 나온다.

---

## 1. 방향

> **빛 하나, 문장 하나, 버튼 하나.**

틈틈의 화면은 '보는 화면'이 아니라 **캐빈에 켜진 조명의 일부**다.

- **바탕은 Pleos 다크 표면이다** (`background/secondary` #131417). OS와 이어지는 어둠이다.
- **색은 화면이 아니라 빛에서 온다.**
  - 가면을 고르기 전에는 앱에 색이 없다. 문라이트만 쓴다.
  - 아침에 가면을 고르는 순간 그 빛이 실내 앰비언트와 앱 강조색을 함께 칠한다.
  - 저녁에 가면을 벗으면 앰버로 돌아온다.
- **대담함은 '틈 라이트' 한 곳에만 쓴다.** 나머지는 Pleos 컴포넌트 그대로, 조용하고 정확하게 둔다.

### 이렇게 보이지 않아야 한다

- 보라·파랑 그라데이션 배경
- 유리 질감
- 아이콘 + 제목 + 설명 카드 3열
- 네온 아웃라인
- 이모지
- 가면 일러스트
- Pleos 컴포넌트와 다른 크기의 '나만의 버튼'

명상 앱의 파스텔도, 계기판의 메탈릭도 아니다. 어두운 차 안에서 문틈으로 새어 드는 빛에 가깝다.

---

## 2. 시그니처: 틈 라이트 (Seam Light) — Brand

화면 가운데에 세로로 빛이 새어 나오는 틈이다. 장식이 아니라 기능으로 쓴다.

| 쓰임 | 화면 | 동작 |
|---|---|---|
| 차가 먼저 틈을 연다 | M-01 | 화면이 어두워지고 가운데 1px 선이 생겼다가 열리며, 그 빛 속에서 카드가 나온다 (900ms) |
| 호흡 | F1-03, Q1-01 | core가 들숨 1단에 20px, 2단에 28px까지 열리고, 긴 날숨 6초 동안 2px로 닫힌다. glow와 spill도 함께 움직이고, 실내 앰비언트 밝기와 같은 박자다 |
| 가면 미리보기 | F1-05 | 각 가면 카드 가운데 그 색의 미니 틈이 있다. 넘기면 틈 색이 화면 배경 빛까지 번진다 |
| 잠들기 | F2-04 → F2-05 | 60초 동안 틈이 좁아져 선이 되고, 점 하나로 줄어든다 |
| 깨우기 | F2-06 | 점이 앰버 선으로 늘어나고, 3분 동안 일출처럼 열린다 |
| 가면 벗기 | F4-02 | 가면 카드가 틈 속으로 빨려 들 듯 흐려지고, 틈 색이 앰버로 바뀐다 |
| 로고 | ON-01, 앱 아이콘 | 두 블록 사이의 세로 틈 하나 (Adaptive Icon의 foreground) |

### 구현 메모 (웹)

틈 라이트는 세 겹으로 만든다.

1. **core**: 폭 2~28px (`--aperture`), 라이트 색과 #FFFFFF를 80:20으로 섞는다. 평소에는 2~4px다.
2. **glow**: 폭 = core × 10 + 48px. `filter: blur(48px)`, opacity 0.45
3. **spill**: 화면 전체 radial-gradient. 중심이 틈이고 반경은 앱 창 폭의 60%, 라이트 색 opacity 0.14 → 0
4. **세로 페이드**: core와 glow 모두 위아래 끝이 사라지도록 `mask-image`를 건다. 그래야 '빛나는 막대'가 아니라 '틈으로 새는 빛'으로 읽힌다. 토큰 시안을 렌더링해 보니 페이드가 없으면 직사각형 막대처럼 보였다

```css
.seam { --light: var(--light-current); position: absolute; inset: 0; pointer-events: none; }
.seam__spill { background: radial-gradient(60% 90% at 50% 50%, color-mix(in oklab, var(--light) 14%, transparent), transparent 70%); }
.seam__glow  { width: calc(var(--aperture) * 10px + 48px); filter: blur(48px); background: var(--light); opacity: .45; }
.seam__core  { width: max(2px, calc(var(--aperture) * 1px)); background: color-mix(in oklab, var(--light) 80%, white); }
.seam__glow, .seam__core { -webkit-mask-image: linear-gradient(to bottom, transparent, #000 22%, #000 78%, transparent); mask-image: linear-gradient(to bottom, transparent, #000 22%, #000 78%, transparent); }
```

- Portrait 창에서는 틈 폭에 0.8을 곱한다.
- `mix-blend-mode: screen`으로 배경 위에 얹는다.
- reduced-motion이 켜져 있으면 폭은 고정하고, 밝기만 두 단계로 바꾼다.
- 주행 중 화면 (`H-02`)에는 틈 라이트를 쓰지 않는다 (Pleos 주행 중 애니메이션 금지).

---

## 3. 색

화면에 헥스 값을 직접 쓰지 않는다. Pleos 시맨틱 토큰과 틈틈 빛 토큰만 쓴다.

### 3-1. 표면과 텍스트 — Pleos 토큰 그대로

| 틈틈 별칭 | Pleos 토큰 | 다크 | 용도 |
|---|---|---|---|
| `cabin.ink` | background/secondary | #131417 | 앱 배경 |
| `cabin.shell` | surface/basic | #1D1E21 | 카드, 편지 카드 |
| `cabin.raised` | surface/high | #313236 | 선택된 카드, 팝업 배경 (background/popup) |
| `cabin.line` | divider/secondary | 흰색 10% | 1px 구분선, 카드 안쪽 테두리 |
| `ink.primary` | text/dark/primary | #FFFFFF | 본문, 제목 (18.4:1) |
| `ink.secondary` | text/dark/secondary | 흰색 84% | 보조 (13.1:1) |
| `ink.tertiary` | text/dark/tertiary | 흰색 64% | 메타, 시각 (7.9:1) |
| `ink.disabled` | text/dark/quaternary | 흰색 32% | 비활성에만 (2.9:1) |
| `btn.basic` / `btn.basicPressed` | button/basic/enabled·pressed | 흰색 5% / 10% | 보조 버튼 배경 |
| `scrim` | overlay/default | #131417 50% | 팝업 뒤 |
| `switch.on` | informative/active | #00E074 | 스위치 켜짐 (Pleos 기본 그대로) |

**테마**

- **세션 화면** (M, C, F1~F4, Q1, I): 항상 다크로 고정한다. 캐빈 조명을 낮추는 경험이라 화면도 어두워야 한다 (Pleos Static 컬러 사용 원칙과 같은 논리).
- **홈, 기록, 설정, 온보딩**: 시스템 라이트·다크 테마를 따른다 (Pleos 주간·야간 원칙). 라이트 값은 `pleos.tokens.json` → `color.light`에 있다.

### 3-2. 빛 — Brand

`--light-current`는 지금 캐빈의 빛이다. 강조색, 틈 라이트, 선택 표시, 엣지 라이트가 모두 이 값을 쓴다.

| 토큰 | 이름 | UI 값 | 앰비언트로 보내는 값 | 언제 |
|---|---|---|---|---|
| `light.moon` | 문라이트 | #C8D2E4 | #B9C6E0 | 기본값 (가면 고르기 전) |
| `light.amber` | 앰버 | #FFB45E | #FF9A2E | 기상, 가면 벗은 뒤, 도착 연출 |
| `light.sleep` | 딥 나이트 | #2C3F8F | #1A2B8C | 잠들기 전환 5% |
| `mask.calm` | 차분한 나 | #5B79FF | #2747E8 | 그날의 가면 |
| `mask.firm` | 단호한 나 | #FF5B3A | #FF3B12 | 그날의 가면 |
| `mask.warm` | 다정한 나 | #FF9381 | #FF6F5A | 그날의 가면 |
| `mask.focus` | 집중하는 나 | #DCE7FF | #E6EEFF | 그날의 가면, 리허설 집중 모드 |
| `mask.flex` | 유연한 나 | #9CC5A1 | #6FBF7A | 그날의 가면 |

### 3-3. 규칙

- 강조색은 한 화면에 하나다 (`--light-current`).
- 강조색이 들어가는 곳은 다섯 곳뿐이다: 주 버튼 배경, 틈 라이트, 선택 표시, 엣지 라이트, 숫자 하나.
- **주 버튼 텍스트는 모든 빛에서 `cabin.ink` (#131417)를 쓴다.** 대비는 모두 4.5:1 이상이다 (최저 `mask.calm` 4.9:1).
- Pleos의 Informative·Regulation 색은 원래 뜻으로만 쓴다. 브랜드 장식에 쓰지 않는다. 경고는 `informative/negative`다.
- 색만으로 뜻을 전하지 않는다. 가면은 항상 이름과 함께 보여 준다.

---

## 4. 타이포그래피

### 4-1. 서체

| 역할 | 서체 | 근거 |
|---|---|---|
| UI 전부 | **Asta Sans** | Pleos 시스템 폰트 |
| 큰 숫자 | **Manrope** 300 (Light), `tabular-nums` | Pleos 피처 폰트 (Driving view 속도, Ambient AI). 브랜드는 가벼운 웨이트로 '조용한 숫자'를 만든다 |
| 목소리 | **MaruBuri** (네이버 마루부리, OFL). 대체는 Gowun Batang | 사람에게 건네는 문장에만, 화면당 한 문장. 정차 중 세션에서만 쓴다. Pleos가 '캐릭터가 강한 폰트 지양'을 말하므로 UI 텍스트와 주행 중 화면에는 절대 쓰지 않는다 |

### 4-2. 스케일 — Pleos 위계에 매핑

모든 크기는 Connect-S px다. 다른 폼팩터는 `pleos.tokens.json` → `typography.sizeByFormFactor` 비율을 따른다.

| 틈틈 토큰 | Pleos 위계 | 크기 / 행간 | 웨이트 | 쓰는 곳 |
|---|---|---|---|---|
| `num.hero` | feature (speed 크기) | 112 / 134 | Manrope 300 | F2-01 "20", R-01 되찾은 시간 |
| `num.l` | headline.l 크기 | 60 / 80 | Manrope 300 | M-01 길이 "7분", F3-05 비교 숫자, F4-05 지표 |
| `display` | headline.s | 48 / 64 | Asta Bold | 세션 질문 ("지금 마음은 어때요?"), 모먼트 카드 제목 |
| `title` | title.m | 36 / 44 | Asta Bold | 화면 제목, 카드 제목 |
| `title.sub` | title.s | 32 / 40 | Asta Bold | 섹션 제목, 선택 카드 라벨 |
| `body.lead` | body.l | 30 / 45 (행간 1.5배) | Asta Regular | 세션 안내 문장 |
| `body` | body.m | 28 / 36 | Asta Regular | 일반 본문 |
| `body.min` | body.s | 26 / 34 | Asta Regular | 최소 본문 |
| `btn.l` | body.l Strong | 30 | Asta Bold | Basic button Large 라벨 |
| `btn.m` | body.m Strong | 28 | Asta Bold | Basic button Medium, Toggle 라벨 |
| `label` | label.m | 24 / 28 | Asta Regular | 칩, 메타, 음성 힌트 |
| `label.min` | label.s | 20 / 24 | Asta Regular | 시각, 단위 (읽어야 하는 정보의 최소) |
| `voice.xl` | — | 48 / 72 | MaruBuri 400 | 오늘의 카드 다짐, 파워 업 첫 문장 |
| `voice.l` | — | 32 / 52 | MaruBuri 400 | 격려 한 줄, 어제의 나, 내일의 나에게, 리허설 목표 |

- 버튼 라벨 크기는 Pleos 문서에 수치가 없다. 그래서 같은 위계의 Body Strong에 매핑했다. 에뮬레이터에서 기본 앱과 비교해 확인한다.
- **주행 중 화면 (`H-02`)**: 한 줄 문장은 title.l 40 Bold 이상으로 쓴다 (글자 높이 5mm 기준).
- **문장 길이**: 한 단락은 3줄 이하다 (Pleos). 세션 문장은 가능하면 한 줄, 한글 22자 이하로 쓴다.
- 자간: 제목 −0.02em, 본문 0, 숫자 −0.03em

---

## 5. 레이아웃

### 5-1. 프레임 (Connect-S 17인치 기준)

| 모드 | 크기 | 언제 | 규칙 |
|---|---|---|---|
| **Portrait** (기본) | 840 × 1184 | 1:1 분할의 한 창. Activity 기본 방향 | **핵심 기능을 여기서 완결한다.** 1단 레이아웃. 좌우 여백 40 |
| **Landscape** (확장) | 1700 × 1184 | 앱 하나가 앱 영역 전체를 쓸 때 | 넓은 영역에 부가 정보를 더한다 (예: H-01 오른쪽에 주간 기록, 세션에서 틈 라이트 양옆 여백). 좌우 여백 48 |

- 앱 창 모서리는 OS가 라디우스 40으로 자른다. 코너에 요소를 붙이지 않는다 (안쪽 여백 최소 40).
- **다른 폼팩터**: 같은 레이아웃을 `pleos.tokens.json` 값으로 스케일한다.
  - S14: 672 × 948 / 1360 × 948
  - L: 628 × 855 / 1274 × 855
  - W: 800 × 810 / 1640 × 810
- **컨테이너 쿼리 기준**: 앱 창 폭 1200 이상이면 Landscape 레이아웃이다.
- **간격**: 4, 8, 12, 16, 20, 24, 32, 40, 48 (Pleos). 화면 구획 사이는 48, 카드 안쪽 패딩은 32~48이다.

### 5-2. 내비게이션 — Pleos Page navigation

- **홈** (`H-01`): Page navigation Main 타입 (높이 80).
  - 왼쪽: 앱 아이콘 80 (라디우스 20)과 "틈틈"
  - 오른쪽 Suffix: Icon button 두 개 [기록], [설정]
- **기록·설정** (R, ST): Page navigation Depth 타입. 뒤로 아이콘 56과 화면 제목.
- **세션 중**: Page navigation을 숨긴다 (몰입). 빠져나가는 길은 우상단 Text button [끝내기] 하나다.
- **주행 중** (`H-02`): 내비게이션이 없다.

### 5-3. 세션 화면 배치

**Portrait (840 × 1184)**

```
┌──────────────────────────────┐
│ ● ● ○ ○ ○          끝내기 ✕  │  ← 단계 점 (Indicator) · Text button
│                              │
│ 지금 마음은                    │  ← display 48 / Bold, 왼쪽 정렬
│ 어때요?                       │
│                              │
│              ┃               │  ← 틈 라이트 (가운데)
│              ┃               │
│                              │
│ ┌──────────────────────────┐ │
│ │      주 버튼 (L 100)      │ │  ← 하단 전체 폭
│ └──────────────────────────┘ │
│   보조 (M 80)     보조 (M 80)  │
│ "'시작'이라고 말해도 돼요"       │  ← label 24
│━━━━━━━━━━━━━━━━━━━━──────────│  ← 엣지 라이트
└──────────────────────────────┘
```

**Landscape (1700 × 1184)**

```
┌─────────────────────────────────────────────────────────────────┐
│ ● ● ○ ○ ○                                            끝내기 ✕   │
│                                                                 │
│   지금 마음은                    ┃                               │
│   어때요?                        ┃       (빛이 번지는 자리)        │
│                                 ┃                               │
│   [ 주 버튼 L ]  보조 M  보조 M                                    │
│   "'시작'이라고 말해도 돼요"                                        │
│━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━──────────────────────────│
└─────────────────────────────────────────────────────────────────┘
```

- **운전석 쪽 원칙**: 한국 차는 운전석이 왼쪽이고 Driving view도 왼쪽이다. 그래서 Landscape에서는 주 버튼을 **왼쪽 아래**, 운전자에게 가까운 쪽에 둔다.
- 선택 카드 화면 (F1-02, F1-05, F4-03)은 틈 위에 겹쳐 가운데 정렬한다.

---

## 6. 컴포넌트 — Pleos 기반 매핑

| 틈틈 컴포넌트 | Pleos 기반 | 규격 (Connect-S) | 브랜드 변형 |
|---|---|---|---|
| **PrimaryButton** | Basic button **Large** | 높이 100, 라디우스 32, 좌우 패딩 40, 최소 폭 140, 아이콘 52, 라벨 `btn.l` | 배경 `--light-current`, 라벨 `cabin.ink`. 눌림은 명도 −8%와 scale 0.97 |
| **SecondaryButton** | Basic button **Medium** | 높이 80, 라디우스 24, 패딩 32, 최소 폭 120, 라벨 `btn.m` | 배경 button/basic/enabled (흰색 5%), 눌림 10% |
| **TextButton** | Text button M | 아이콘 32, 간격 8 | [끝내기]는 Suffix 아이콘 ✕ |
| **ChoiceCard** | Toggle button **Large** 확장 | 최소 높이 160, 라디우스 32, 패딩 32, 라벨 `title.sub` | Toggled: `cabin.raised` 배경과 테두리 4 `--light-current` |
| **Chip** | Toggle button **Small** | 높이 64, 라디우스 16, 패딩 24 | 사운드, 하루 색, 추천 칩 |
| **MomentCard (M-01)** | System popup **Medium** 비례 | 폭 808, 라디우스 48, 패딩 48, 그림자 level3 | 뒤에 틈 라이트. 헤더 아이콘 대신 큰 숫자 `num.l` |
| **ConfirmPopup** (C-02, I-02, I-03) | System popup Medium | 폭 808, 라디우스 48, 패딩 48, 버튼 Horizontal 정렬 | 그대로 쓴다 |
| **TimePopup** (F2-01a) | System popup + Tabs Solid M + Spinner Time | 탭 컨테이너 80 (10 · 15 · 20분), 스피너 패널 288 | 선택 탭에 `--light-current` |
| **SessionFrame (C-01)** | Indicator (Narrow) + Text button | 단계 점 지름 10, 현재 단계 폭 28 | 단계 점의 현재 색이 `--light-current` |
| **EdgeLight** | (가이드에 없음, 브랜드) | 하단 3px + 위로 12px 글로우 | 남은 시간에 따라 줄어든다. 탭하면 Toast "{n}분 남았어요" |
| **SeamLight** | (브랜드) | §2 | props: `aperture`, `light`, `mode` |
| **MaskCarousel** | 가로 스와이프 + Indicator | 카드 360 × 520, 라디우스 32. 가운데 카드 scale 1, 양옆 0.86 + opacity 0.4 | 카드 안: 미니 틈, 이름 `title`, 설명 `body.min` |
| **MicButton** | Icon button Circle 확장 | 지름 160, 아이콘 56 | 듣는 중에는 바깥 링이 소리 크기에 반응. 처리 중에는 Pleos Linear 로더 |
| **LiveTranscript** | — | `body.lead` | 확정 글자는 `ink.primary`, 중간 결과는 `ink.tertiary`, 키워드는 `--light-current` 밑줄 |
| **LetterCard** | Surface basic | 라디우스 32, 패딩 48 | MaruBuri `voice.*`, 상단 날짜 `label.min` |
| **TimelineToday** | — (Surface 위 구성) | 칸 높이 112 | 완료, 예정, 건너뜀, 해당 없음 4상태. 지금 칸은 `--light-current` 링 |
| **WeekStrip** | — | 칸 폭 80 | 하루 완료 수 0~4 막대 |
| **ChecklistReveal** (F2-02, C-03) | Vehicle settings list item | 항목 높이 112, 아이콘 컨테이너 80, 라디우스 24 | 0.4초 간격으로 하나씩 ✓ (informative/active) |
| **SeatAngleControl** (F2-03, ON-06) | Stepper | 420 × 100, 라디우스 24 | 위에 시트 옆모습 일러스트와 각도 `num.l` |
| **TimeWheel** (ON-05) | Spinner Time | 항목 88, 패널 M 288, 라디우스 32 | — |
| **WalkStepper** (ON-05) | Stepper | 420 × 100 | — |
| **SettingsList** (ST) | Vehicle settings list + Switch M + Dropdown M | 항목 112, Switch 폭 80 | — |
| **Toast** (C-04, T-01) | Toast popup | 최대 폭 792, 패딩 40 × 24, 라디우스 16, 아이콘 48, 하단·좌우 24 | C-04는 스탬프 아이콘이 찍히는 모션 (scale 1.2 → 1, 240ms) |
| **PageNav** | Page navigation Main / Depth | 높이 80 | — |
| **ScrollBar** | Bar · Scroll | 폭 6, 최소 높이 48 | — |

- **아이콘**: 48 그리드의 라운드 라인 아이콘 한 세트를 쓴다. 기본은 Outlined, 선택 상태는 Filled다. 버튼 안 크기는 Pleos 규격 (L 52, M 40, S 32)이고, 텍스트 라벨과 함께 쓴다.
- **그림자**: 다크 바탕에서는 카드에 그림자를 쓰지 않고 표면 단계 (basic → high)로 높이를 표현한다. 팝업과 모먼트 카드에만 Pleos level3를 쓴다.
- **피드백**: 모든 터치는 0.25초 안에 눌림 상태로 응답한다. 주 버튼에는 짧은 확인음을 낸다 (Pleos '터치 피드백' 원칙).

---

## 7. 모션

| 토큰 | 값 | 쓰는 곳 |
|---|---|---|
| `dur.micro` | 160ms | 눌림, 토글 (0.25초 응답 기준 안) |
| `dur.base` | 320ms | 단계 전이 (크로스페이드 + 8px 상승) |
| `dur.scene` | 900ms | 틈이 열리고 닫힘, M-01 등장 |
| `dur.cabin` | 2000ms | 앰비언트 색 페이드 (캐빈과 맞춤) |
| `ease.enter` | `cubic-bezier(0.22, 1, 0.36, 1)` | 등장 |
| `ease.exit` | `cubic-bezier(0.4, 0, 1, 1)` | 퇴장 |
| `ease.breath` | `cubic-bezier(0.37, 0, 0.63, 1)` | 호흡, 맥동 |

### 원칙

- **Pleos 원칙**: 애니메이션은 상태를 설명할 때만 쓴다. 주행 중에는 자동 애니메이션을 쓰지 않는다. `uxRestricted`가 켜지면 모든 모션을 즉시 멈춘다.
- **튕기지 않는다** (스프링 오버슈트 금지). 숨처럼 느리게 움직인다.
- **장면당 오케스트레이션은 하나다.**
- **화면 색 변화는 캐빈 페이드와 같은 2000ms다.** 화면과 실내가 한 몸처럼 바뀐다.
- **로딩**: STT 처리처럼 0.25초가 넘는 대기에는 Pleos 로더를 쓴다 (짧으면 Linear, 길면 Tension).
- **`prefers-reduced-motion`**: 켜지면 위치·크기 애니메이션을 끄고 opacity와 밝기만 바꾼다.

### 호흡 타이밍 (F1-03)

| 단계 | 시간 | 틈 core 폭 (Landscape) | 앰비언트 |
|---|---|---|---|
| 들숨 1 | 0–2.0s | 2 → 20px | 20 → 45% |
| 들숨 2 | 2.0–3.0s | 20 → 28px | 45 → 60% |
| 날숨 | 3.0–9.0s | 28 → 2px | 60 → 20% |
| 사이클 | 9초 | 3분 = 20회 | Portrait에서는 폭 × 0.8 |

---

## 8. 카피 톤

- **해요체, 짧게, 한 문장에 한 가지만.** "지금 마음은 어때요?"
- **Pleos 길이 기준**: 한 단락 3줄 이하. 세션 문장은 한 줄 (한글 22자 이하)이 목표다.
- **버튼은 누르면 일어나는 일을 말한다.**
  - 좋은 예: [다녀오기], [바로 자기], [여기 두고 가기], [들어가기], [끝내기]
  - 나쁜 예: [확인], [다음 단계], [완료하기]
- **같은 동작은 같은 이름이다.** [끝내기]를 누르면 팝업 버튼도 [끝내기]다.
- **숫자는 구체적으로 쓴다.** "20분 잘 수 있어요"
- **위로는 근거와 함께 한다.** "이번 주 다짐 네 개 중 세 개를 해냈어요"
- **못 한 날을 탓하지 않는다.** "오늘은 버틴 것만으로 충분해요. 내일 아침으로 옮겨 둘게요."
- **쓰지 않는 것**: 느낌표, 이모지, 명령조, 과장, 시스템 용어 ("세션", "모드")
- **오류**: 무슨 일이 생겼고 어떻게 하면 되는지 말한다. 사과하지 않는다. 예: "마이크를 쓸 수 없어요. 아래에서 골라 주세요."

---

## 9. 먼저 그릴 8개 화면의 아트 디렉션

Portrait (840 × 1184)로 먼저 완성한 뒤 Landscape (1700 × 1184)로 확장한다.

| ID | 핵심 장면 | 놓치면 안 되는 것 |
|---|---|---|
| H-01 | Page navigation 아래에 오늘의 카드 칩, 추천 카드, 오늘의 틈 타임라인. Landscape에서는 오른쪽 열에 주간 스탬프가 추가된다 | 주 버튼은 하나. 가면 색은 칩, 추천 카드 끝 빛, 타임라인 '지금' 링에만 |
| M-01 | 어두워진 창 가운데 틈이 열리고, 그 빛 속에서 System popup 비례의 카드 (808, 라디우스 48)가 떠오른다 | 큰 숫자 "7분" (Manrope 60), 주 버튼 L, 음성 힌트 |
| F1-03 | 창 전체에 틈 하나. 숨을 쉴 때 틈과 Cabin 패널 앰비언트가 함께 움직인다 | 문구 한 줄, 엣지 라이트, [눈 감고 하기] |
| F1-05 | 가면 카드를 넘길 때마다 배경 빛과 앰비언트가 그 색으로 바뀐다 | 얼굴 금지, 이름과 한 줄 설명, Indicator |
| F2-04 | 60초 동안 틈이 좁아져 점이 된다. 아래에 ChecklistReveal 스타일로 차가 하는 일 4개 | 화면이 점점 어두워져 마지막엔 거의 검정 |
| F2-06 | 창 아래에서 앰버 빛이 3분간 차오르고, Cabin 패널에서 공조, 등받이, 창문이 순서대로 움직인다 | 캐빈이 주인공. 텍스트 최소 |
| F4-03 | 하루 타임라인에 아침 가면 색, 점심 리차지, 오후 리허설이 이어지고, 아침 다짐이 MaruBuri로 다시 나온다 | 버튼 세 개 (Toggle L)의 무게가 같다. 하루 색 칩 |
| F4-05 | 근거 있는 숫자 4개 (Manrope)와 격려 한 줄 (MaruBuri), 마이크 | 자랑이 아니라 기록처럼 담담하게 |
