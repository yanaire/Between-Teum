# 차량 목업과 데모 셸

프로토타입은 브라우저에서 돌아가기 때문에 차가 없다. 그래서 **차량 상태 (입력)**와 **캐빈 명령 (출력)**을 모두 목업으로 만들고, 데모 셸에 시각화한다.

인터페이스 이름과 구조는 실제 SDK 호출로 바꾸기 쉽게 짠다. 개발자가 Kotlin으로 옮길 때 그대로 매핑할 수 있어야 한다.

---

## 1. 데모 셸 구성

데모 셸은 **Pleos Connect-S 17인치 화면 전체 (2560 × 1440)**를 축소해 보여 준다. 시스템 UI까지 그려야 시안과 시연 영상이 '진짜 차 화면'처럼 보이고, 앱 창 크기도 정확해진다. 치수는 `07-PLEOS-FOUNDATION.md` §2에 있다.

```
┌──────────────────────────── DISPLAY (2560×1440, 화면에 맞게 축소) ─────────────────────────────┐
│ Status bar 96 (시계 · 네트워크 · 프로필 — 목업)                                                │
├──────────────┬──────────────────────────────────────────────────────────────────────────────┤
│ Driving view │  [틈틈 Portrait 840×1184]  20  [내비 목업 Portrait 840×1184]                     │
│ 840          │   또는                                                                        │
│ 속도·기어 PRND │  [틈틈 Landscape 1700×1184]                                                   │
│ (vehicleStore│                                                                               │
│  값으로 표시)  │                                                                               │
├──────────────┴──────────────────────────────────────────────────────────────────────────────┤
│ GNB 160 (공조 24.0 · 앱 슬롯 · App library · 최근 앱 — 목업, 틈틈 아이콘 포함)                     │
└────────────────────────────────────────────────────────────────────────────────────────────┘
   └─ 디스플레이 테두리에 앰비언트 색이 은은하게 번진다 (캐빈 분위기)

┌ CABIN ────────────────────┐  ┌ SIMULATOR ───────────────────────────────┐
│ 앰비언트 빛 띠 (색·밝기)     │  │ 시나리오 프리셋 ▾   화면 점프 [ID ▾]       │
│ 시트 옆모습 ∠ 112°          │  │ 시간 08:40 [1x][12x][60x] ▶ ❚❚          │
│ 공조 24℃ · 풍량 2          │  │ 기어 P R N D · 차속 · 주차 브레이크         │
│ 창문 2cm · 블라인드         │  │ 운전석 · 동승석 · 뒷좌석 착석               │
│ 실내등 ○  문 잠김 ●         │  │ 문 열기 · 시동 끄기 · 차종 EV/HEV/ICE      │
│ 음성 자막 "20분 뒤에…"       │  │ 창 모드 Portrait / Landscape              │
└───────────────────────────┘  │ 폼팩터 S-17 / S-14.6 / L-12.9 / W          │
                               │ 테마 Dark / Light · capability 토글        │
                               └──────────────────────────────────────────┘
```

- **창 모드**: Portrait를 고르면 틈틈이 왼쪽 창 (primary)에 840 × 1184로, 오른쪽 창에 내비 목업이 뜬다. Landscape를 고르면 틈틈이 1700 × 1184를 차지한다.
- **폼팩터 전환**: S-17이 기본이다. S-14.6, L-12.9, W를 고르면 `pleos.tokens.json`의 디스플레이, 앱 창, 상태바, GNB 값으로 셸 전체를 다시 그린다. 레이아웃이 깨지는지 확인하는 용도다.
- **Driving view 목업**: `vehicleStore`의 기어와 차속을 Manrope Bold 112 (Pleos feature.speed)로 보여 준다. 주행 시뮬레이션이 실감 나고, H-02 전환도 자연스럽게 보인다.
- **시연 영상 모드** (`?present=1`): 시뮬레이터 컨트롤을 숨기고, 디스플레이와 Cabin 패널만 보이게 한다.
- **화면 하나만 보기** (`?frame=app`): 시스템 UI 없이 앱 창만 1:1 크기로 띄운다. 시안 이미지를 뽑을 때 쓴다.
- **음성 자막 바**: TTS 문장을 자막으로 보여 준다. V-01처럼 화면 없는 장면도 여기서 보인다.

---

## 2. 차량 상태 (입력)

```ts
export type Gear = 'P' | 'R' | 'N' | 'D';
export type Place = 'office' | 'home' | 'client' | 'other';

export interface VehicleState {
  ignition: 'off' | 'acc' | 'on';
  gear: Gear;
  speedKph: number;
  parkingBrake: boolean;
  uxRestricted: boolean;               // CarUxRestrictions: 주행 중이면 true

  seats: {
    driver:    { occupied: boolean; belted: boolean; reclineDeg: number; heat: 0|1|2|3; vent: 0|1|2|3 };
    passenger: { occupied: boolean };
    rearLeft:  { occupied: boolean };
    rearRight: { occupied: boolean };
  };
  doors: { anyOpen: boolean; driverOpen: boolean; locked: boolean };
  windows: { driverOpenCm: number; blind: 'open' | 'closed' | 'na'; smartGlass: 'clear' | 'dim' | 'na' };
  hvac: { tempC: number; fan: number /*0-8*/; recirc: boolean; ac: boolean };
  light: { ambientHex: string; ambientPct: number /*0-100*/; cabinLight: boolean };

  location: { place: Place; label?: string };
  navi: { destination?: Place | string; etaMin?: number; arrived: boolean };
  outsideTempC: number;

  capabilities: {
    powerRecline: boolean; seatMemory: boolean; seatVent: boolean;
    ambient: boolean; blind: boolean; smartGlass: boolean;
    powertrain: 'EV' | 'HEV' | 'ICE';
  };
}
```

### 파생 판단 (domain)

```ts
export const isParked = (v: VehicleState) =>
  v.gear === 'P' && v.speedKph === 0 && v.parkingBrake && v.seats.driver.occupied;

export const hasCompanion = (v: VehicleState) =>
  v.seats.passenger.occupied || v.seats.rearLeft.occupied || v.seats.rearRight.occupied;
```

틈 판단 `detectGap(v, now, profile, day) → GapOffer | null`은 PRD 표 6-1a와 6-1b를 그대로 코드로 옮긴다.

```ts
type GapType = 'F1' | 'F2' | 'F3' | 'F4';
interface GapOffer { type: GapType; minutesLeft: number; programMin: number; variant: 'full' | 'short' | 'micro' }

// F2 리차지 시간
const rechargeMin = (returnAt: Date, now: Date, walk = 3, wakeUp = 2) =>
  Math.min(20, minutesBetween(now, returnAt) - walk - wakeUp);   // 7 미만이면 '눈 감고 쉬기'
```

---

## 3. 캐빈 명령 (출력)

```ts
export interface CabinCommands {
  // 스냅샷: 세션 시작 시 저장, C-03에서 복원
  saveSnapshot(): CabinSnapshot;
  restore(s: CabinSnapshot, onStep?: (item: 'seat'|'light'|'hvac'|'window') => void): Promise<void>;

  setAmbient(hex: string, pct: number, fadeMs?: number): void;
  setCabinLight(on: boolean): void;
  setRecline(deg: number, opts?: { speed: 'slow' | 'normal' }): Promise<void>;   // P + 차속 0 + 착석일 때만
  stopSeat(): void;
  setSeatHeat(level: 0|1|2|3): void;
  setSeatVent(level: 0|1|2|3): void;
  setHvac(p: Partial<{ tempC: number; fan: number; recirc: boolean; ac: boolean }>): void;
  setWindow(openCm: number): Promise<void>;
  setBlind(state: 'open' | 'closed'): Promise<void>;
  setSmartGlass(state: 'clear' | 'dim'): void;
  lockDoors(): void;

  speak(text: string, opts?: { interrupt?: boolean }): Promise<void>;       // TTS → 음성 자막 바
  listen(opts: { partial: boolean }): AsyncIterable<{ text: string; final: boolean }>; // STT
  play(track: AudioTrack, opts?: { fadeInMs?: number; loop?: boolean }): void;
  stopAudio(fadeOutMs?: number): void;
}
```

모든 명령은 먼저 capability를 확인한다. 미지원이면 아무 일도 하지 않고 `T-01` 안내 이벤트를 낸다. 시트 명령은 `isParked`가 false면 거부하고 `I-01`로 보낸다.

### SDK 매핑 (개발자 참고)

| 목업 | 실제 | 비고 |
|---|---|---|
| `gear`, `speedKph` | AAOS 차량 속성 (`CarPropertyManager`) | 호환성 표에서 지원 여부 확인 |
| `uxRestricted` | `CarUxRestrictionsManager` | |
| `parkingBrake` | Vehicle SDK · Brake | |
| `seats.*.occupied`, `belted`, `reclineDeg`, `heat`, `vent` / `setRecline`, `setSeatHeat`, `setSeatVent`, `saveSnapshot` (시트) | Vehicle SDK · Seat | 제어 권한 승인 필요 여부, 차종별 전동 등받이 |
| `hvac` / `setHvac` | Vehicle SDK · HVAC | |
| `light` / `setAmbient`, `setCabinLight` | Vehicle SDK · Light | 밝기 변경 빈도 제한 확인 (호흡 동기화) |
| `windows` / `setWindow`, `setBlind`, `setSmartGlass` | Vehicle SDK · Window | 블라인드·스마트글라스는 차종별 |
| `doors` / `lockDoors` | Vehicle SDK · Door | |
| `navi` | NaviHelper SDK | 도착 이벤트 수신 방식 |
| `speak` | TextToSpeech SDK | |
| `listen` | SpeechToText SDK | 중간 결과 지원 여부, 군말 표기 여부 |
| `location` | Android 위치 API (FusedLocation 대신) | 회사·집 반경 200m 지오펜스 |
| `play` | Android 오디오 (MediaPlayer, ExoPlayer) | 사운드와 기상송은 앱 번들 |

---

## 4. 시나리오 프리셋

시뮬레이터 드롭다운에서 고르면 상태와 시각이 한 번에 세팅된다.

| 프리셋 | 시각 | 상태 | 기대 결과 |
|---|---|---|---|
| ① 출근 도착 | 월 08:40 | 회사, P, 브레이크, 운전석만, 출근 09:00, 전날 '내일의 나에게' 있음 | 2초 후 M-01 (F1, 7분) |
| ② 점심 후 | 12:35 | 회사, P, 운전석만, 복귀 13:00, 외기 18℃, EV | F2-01 "20분" |
| ③ 미팅 전 | 15:40 | 거래처 (내비 도착), P, 등록 일정 16:00 "A사 신제품 제안" | F3-01 |
| ④ 귀가 주행 | 19:05 | D, 60km/h, 내비 목적지 집, ETA 5분 | H-02와 V-01 음성 자막. 5분 뒤 (배속) 자동 도착 → P → F4-01 |
| ⑤ 다음 날 아침 | 화 08:38 | ①과 같음 | F1-01에서 어젯밤 메시지 재생 |
| ⑥ 동승자 | 아무 때 | 동승석 착석 | 사적 단계 숨김, T-01 |
| ⑦ 첫 실행 | 아무 때 | 온보딩 전 | ON-01 |
| ⑧ 하루 루프 데모 | 08:40 → 19:10 | ① → ② → ③ → ④ → ⑤를 자동 진행 (12x) | PT용 3분 시연 |

**즉시 이벤트 버튼**: [기어 D로] → I-01, [문 열기] → I-02, [시동 끄기], [동승자 탑승], [블라인드 없음 차종]

---

## 5. 하루 메모리 (데이터 모델)

```ts
type MaskId = 'calm' | 'firm' | 'warm' | 'focus' | 'flex';
type Mood = 'tense' | 'heavy' | 'okay';

interface DayRecord {
  date: string;                                  // '2026-11-10'
  mood?: Mood;
  mask?: MaskId;                                 // F1-05 → 그날의 --light-current
  intention?: { text: string; source: 'voice' | 'chip' | 'carryover' };
  intentionResult?: 'done' | 'half' | 'not';     // F4-03
  dayWord?: string;                              // F1을 건너뛴 날 F4-03, Q1-02
  dayColor?: MaskId;                             // F4-03 하루 색
  moments: MomentLog[];
  keepNotes: { id: string; text: string; createdAt: string }[];   // F4-04a '다시 보기'만 (버린 것은 저장하지 않음)
  letterToTomorrow?: { text: string; audioBlobId?: string };      // F4-05a → 다음 날 F1-01
}

interface MomentLog {
  id: string;
  type: 'F1' | 'F2' | 'F3' | 'F4' | 'Q1';
  startedAt: string; endedAt?: string;
  status: 'done' | 'stopped' | 'skipped';
  minutes: number;                               // 되찾은 시간 합계에 더한다
  meta?: Record<string, unknown>;                // F2: 잔 분 / F3: 지표 / F4: 길이
}

interface ImportantMoment {                      // F3-00
  id: string; title: string; at: string; counterpart?: string;
  keywords: [string, string, string]; questions?: string[];
  goal?: string;
  rehearsals?: { sec: number; keywordsHit: number; fillers: number | null; pace: 'slow' | 'good' | 'fast' }[];
  after?: { rating: 'good' | 'okay' | 'hard'; note?: string };    // F3-08
}

interface Profile {
  office?: GeoPoint; home?: GeoPoint;
  startAt: '09:00'; lunchReturnAt: '13:00'; walkMin: 3;
  sleepReclineDeg?: number; restReclineDeg?: number;
  sound: 'rain' | 'white' | 'forest' | 'none'; wakeSong?: string; powerSong?: string;
  voiceGuide: boolean;
  gapToggles: Record<'F1'|'F2'|'F3'|'F4'|'V01', boolean>;
}
```

**루프 규칙**

- 그날의 `mask`가 정해지면 `--light-current`를 그 가면 색으로 바꾼다. 자정이 지나면 `light.moon`으로 초기화한다.
- `intentionResult`가 `half`나 `not`이면 다음 날 F1-04 추천 칩에 '이월'로 띄운다.
- `letterToTomorrow`가 있으면 다음 날 첫 F1에서 F1-01을 보여 준다.
- 저장은 프로토타입에서는 `localStorage`, 실제 앱에서는 Room을 쓴다. 키는 `teum.day.<date>`, `teum.profile`, `teum.moments`다.

---

## 6. 캐빈 연출 타임라인 (cue)

세션은 '캐빈 큐' 배열로 정의하고, 타임라인 엔진이 시간 배속에 맞춰 실행한다. 화면 단계와 캐빈 큐를 분리하면 디자이너가 연출 타이밍만 따로 조정할 수 있다.

```ts
interface CabinCue { at: number /*세션 시작 기준 초, 음수 = 끝에서부터*/; cmd: keyof CabinCommands; args: unknown[] }
```

### F1 가면 쓰기 (7분)

| 시점 | 명령 |
|---|---|
| 도착 | `setAmbient(warmLow #FFB45E, 15%, 2000)` (시작 전 은은한 도착 연출) |
| 0s ([시작]) | `saveSnapshot()`, `setRecline(+5°, slow)` |
| F1-03 동안 | 9초마다 `setAmbient(current, 20→45→60→20%)` (호흡 박자), `setHvac({fan: 1})` |
| F1-05 카드 넘김 | `setAmbient(mask.cabinHex, 50%, 1000)` |
| F1-06 | `setAmbient(focus #E6EEFF, 70%, 2000)`, `setRecline(snapshot)`, `setHvac({recirc: false, tempC: -1})`, `setSeatHeat(0)`, `speak("오늘의 한 가지, …")` |

### F2 리차지 (20분 = 1200초)

| 시점 | 명령 |
|---|---|
| F2-02 | `doors.locked` 확인 (아니면 [잠그기] → `lockDoors()`), `windows` 확인 |
| 0s (F2-04 시작) | `saveSnapshot()`, `setRecline(sleepDeg, slow)`, `setBlind('closed')`, `setSmartGlass('dim')`, `setCabinLight(false)`, `setAmbient(sleep #1A2B8C, 5%, 2000)`, `setHvac({tempC: 24, fan: 2, recirc: false})`, 계절별 `setSeatVent(1)` 또는 `setSeatHeat(1)`, `play(sound, {fadeInMs: 8000, loop: true})`, `speak("20분 뒤에 깨워 드릴게요")` |
| 30s | `setAmbient(sleep, 0%, 4000)` |
| −180s (3분 전) | `setAmbient(amber #FF9A2E, 1→60%, 180000)`, `play(birds, {fadeInMs: 20000})` |
| −120s (2분 전) | `setHvac({tempC: 22, fan: 3, recirc: false})`, 여름이면 `setSeatVent(2)` |
| −60s (1분 전) | `setRecline(snapshot, slow)`, `setBlind('open')`, `setSmartGlass('clear')` |
| 0s (종료) | `setWindow(2)`, `speak("1시 5분 전이에요. 개운하게 일어나요.")`, `play(wakeSong)` |
| +60s 무응답 | F2-07a: `play(wakeSong, louder)`, `setCabinLight(true)`, `speak` 반복 |

### F3 리허설 (10분)

| 시점 | 명령 |
|---|---|
| F3-02 | `saveSnapshot()`, `setAmbient(focus #E6EEFF, 70%, 1500)`, `setRecline(upright)`, `setHvac({tempC: 22})` |
| F3-03 | 녹음 중에는 `stopAudio()`, 앰비언트 고정 |
| F3-07 | `play(powerSong, {fadeInMs: 1500})`, `setAmbient(mask.cabinHex, 60%, 2000)` 후 비트에 맞춰 ±8% 맥동, `setHvac(snapshot)`, `speak("첫 문장만 기억하세요.")` |

### F4 가면 벗기 (10분)

| 시점 | 명령 |
|---|---|
| V-01 (주행 중) | `speak("도착하면 10분 쉬었다 들어갈까요?")`, `listen()` |
| F4-02 | `saveSnapshot()`, `setAmbient(mask → amber #FF9A2E, 25%, 30000)`, `setRecline(+10°, slow)`, 계절별 열선 또는 통풍 1, `speak("이제 가면 벗어도 돼요.")` |
| F4-03 하루 색 선택 | `setAmbient(dayColor.cabinHex, 25%, 2000)` |
| F4-04a 버리기 | `setAmbient(current, 25→8%, 4000)` |
| F4-05 | `speak(격려 문장)` |
| F4-06 | `setAmbient(off)`, `setCabinLight(true)`, `restore(snapshot)`, `speak("이제 가족에게 가요. 내일 아침에 만나요.")` |

---

## 7. 음성과 오디오 (프로토타입)

- **TTS**: `speechSynthesis` (ko-KR)를 쓴다. 쓸 수 없으면 자막만 보여 주고 문장 길이에 비례해 대기한다.
- **STT**: `webkitSpeechRecognition` (ko-KR, interimResults)을 쓴다. 쓸 수 없으면 데모용 '가짜 받아쓰기'를 쓴다. 미리 정한 문장이 한 글자씩 타이핑된다. 예: "기획안 초안 끝내기", "안녕하세요, 오늘 A사 신제품 제안을 맡은…"
- **오디오 자산** (`/public/audio`): 빗소리, 백색소음, 숲, 새소리 (기상), 완료음, 스탬프음, 기상송, 파워 송. 저작권이 없는 음원을 쓴다. 파워 송은 저음이 강한 인스트루멘털이다.
- **음량 규칙**: 음성 안내가 나오는 동안 배경음을 30%로 낮춘다 (덕킹).
