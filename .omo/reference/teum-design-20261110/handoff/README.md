# 틈틈 핸드오프 패키지

Pleos Connect 공모전 출품작 「틈틈」의 기획을 Claude Code에서 하이파이 UI로 이어서 만들기 위한 패키지다.

## 시작하는 법

1. 이 폴더를 새 프로젝트 폴더로 쓰거나, 프로젝트 루트에 그대로 복사한다. `CLAUDE.md`가 루트에 있어야 Claude Code가 매번 자동으로 읽는다.
2. 터미널에서 이 폴더로 이동한 뒤 `claude`를 실행한다.
3. 아래 프롬프트를 단계별로 붙여 넣는다.

```
.
├── CLAUDE.md                 Claude Code가 자동으로 읽는 작업 가이드 (규칙, 스택, 작업 순서)
├── README.md                 이 파일
├── tokens/
│   ├── pleos.tokens.json     Pleos 원본 토큰 (컬러 129개, 폼팩터별 크기, 컴포넌트 규격)
│   ├── tokens.json           틈틈 브랜드 층 (빛, 틈 라이트, 목소리 서체)
│   └── tokens.css            두 JSON에서 생성한 CSS 변수 (바로 import)
└── docs/
    ├── 01-PRD.md             제품 요구사항
    ├── 02-IA.md              IA 트리, 레이아웃 모드, 화면 68개 목록
    ├── 03-FLOWS.md           생명주기·기능별 플로우와 화면별 스펙
    ├── 04-DESIGN-SYSTEM.md   비주얼 방향, 틈 라이트, 컴포넌트, 모션, 카피
    ├── 05-VEHICLE-MOCK.md    차량 목업, 데모 셸, 시나리오 프리셋, 캐빈 연출 타임라인
    ├── 06-RESEARCH.md        근거 출처와 덱 S3·S4 문안
    ├── 07-PLEOS-FOUNDATION.md Pleos UI 가이드 분석 (폼팩터, 창 정책, 타이포, 컬러, 컴포넌트 25종)
    └── reference/deck-v1.pdf 기획 덱 9장
```

## 단계별 프롬프트

### 1. 토대

```
CLAUDE.md와 docs 전체를 읽고, 작업 순서 1단계 '토대'를 만들어 줘.
Vite + React + TS로 프로젝트를 세팅하고, tokens/tokens.css를 전역으로 import해.
07-PLEOS-FOUNDATION §8 규격대로 Pleos 기본 컴포넌트 (버튼 L·M·S, Toggle, Tabs, Switch, Stepper, Spinner, System popup, Toast, Page navigation)를 먼저 만들고 한 페이지에 모아 보여 줘.
데모 셸은 05-VEHICLE-MOCK §1대로 Connect-S 2560×1440 화면 전체 (상태바, Driving view, GNB 목업)와 Cabin 패널, 시뮬레이터로 만들어 줘.
SeamLight 컴포넌트를 04-DESIGN-SYSTEM §2대로 만들고 호흡 모드로 띄워 줘.
끝나면 Portrait (840×1184)와 Landscape (1700×1184) 스크린샷을 찍어서 확인해.
```

### 2. 우선 시안 8개

```
02-IA.md §6의 우선 화면 8개를 03-FLOWS.md 스펙대로 하이파이로 만들어 줘.
순서는 H-01 → M-01 → F1-03 → F1-05 → F2-04 → F2-06 → F4-03 → F4-05.
화면마다 Cabin 패널에 차의 동작이 보이게 하고, 하나 끝날 때마다 Portrait/Landscape 스크린샷으로 점검해.
Portrait에서 핵심을 먼저 완결하고 Landscape는 확장으로 만들어.
04-DESIGN-SYSTEM.md §1의 '이렇게 보이지 않아야 한다'를 어기지 않았는지 스스로 검토해.
```

### 3. P0 루프

```
P0 나머지 화면을 만들고, 05-VEHICLE-MOCK.md §4 프리셋 ⑧ '하루 루프 데모'가 끊김 없이 돌게 해 줘.
F1 → F2 → F4 → 다음 날 F1-01 메시지 재생까지 이어져야 하고, 기어 D, 문 열기 이벤트로 I-01, I-02도 확인해.
```

### 4. P1과 데모 모드

```
F3 리허설, Q1, 기록 R, 설정 ST, H-03, I-03을 만들고,
?present=1 시연 영상 모드와 12x 시간 빨리감기를 마무리해 줘.
```

### 화면 하나만 고칠 때

```
F1-05를 03-FLOWS.md와 04-DESIGN-SYSTEM.md §9 기준으로 다시 봐 줘.
카드 넘김 때 배경 빛과 Cabin 앰비언트가 2초 페이드로 같이 바뀌는지 확인하고, 어색한 부분을 고쳐.
```

## 개발자에게 넘길 때

- 화면 스펙: `docs/03-FLOWS.md`
- SDK 매핑과 데이터 모델: `docs/05-VEHICLE-MOCK.md` §3, §5
- 토큰: `tokens/pleos.tokens.json` (Pleos 원본), `tokens/tokens.json` (브랜드)
- Pleos 규격: `docs/07-PLEOS-FOUNDATION.md`

Pleos 토큰은 Figma 원본 변수에서 뽑은 값이라 Pleos 기본 앱과 같은 크기와 색이 나온다. Compose의 `Color`, `TextStyle`, `Dp`로 옮기면 된다 (Connect-S에서 1px = 1dp).
