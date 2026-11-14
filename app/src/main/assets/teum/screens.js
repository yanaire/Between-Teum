// Production original-design templates. Native facts are read-only; parent owns all dispatch and effects.
const SOUND_KEYS = ['RAIN','WAVES','FOREST','CAMPFIRE','SINGING_BOWL','WIND','WHITE_NOISE','NONE'];
const JOURNEY_SLOT = {MORNING:'am',NAP:'noon',PREPARATION:'meet',EVENING:'pm',QUICK_BREATHING:'q1'};
function esc(value) { return value == null || value === '' ? '—' : String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }

function minutes(seconds) { return seconds == null ? null : Math.floor(seconds / 60); }

function clock(minute) { return minute == null ? '' : String(Math.floor(minute / 60)).padStart(2, '0') + ':' + String(minute % 60).padStart(2, '0'); }

function fact(s, group, key) { const f=s.hardware?.[group]?.[key]; return esc(f?.label || ({confirmed:'확인됨',unsupported:'이 차량에서 지원하지 않아요',pending:'확인 중',failed:'요청 실패',unknown:'확인되지 않았어요'}[f?.status] || '확인되지 않았어요')); }

function factMark(s,group,key) { return s.hardware?.[group]?.[key]?.status === 'confirmed' ? I('check') : I('minus'); }

function placeControls(s) { return `<div class="places" style="margin-top:0">${[['company','work','회사'],['home','home','집']].map(([key,k,label])=>`<div class="place"><div class="place-art">${PLACEART(k)}${s.preferences?.[key]?.coordinates ? `<span class="pin">${I('check')}</span>` : ''}</div><label class="cap" for="place-${key}">${label}</label><input id="place-${key}" class="body" style="width:100%;background:transparent;border:0;color:inherit;font:inherit" data-bind="place:${key}" maxlength="200" value="${s.preferences?.[key]?.label ? esc(s.preferences[key].label) : ''}" placeholder="장소 이름 미설정"><div class="row-c">${lnk('이름 저장',null,`savePlace:${key}`)}${lnk('현재 위치 등록',null,`captureLocation:${key}`)}</div></div>`).join('')}</div><p class="cap" data-live="location.description" style="margin-top:16px">${esc(s.location?.description || '위치 수신 상태를 확인해요')}</p>`; }

function timeControls(s) { return `<div class="timepair">${[['workStartMinute','출근'],['lunchReturnMinute','점심 복귀']].map(([key,label]) => {
  const value = clock(s.preferences?.schedule?.[key]);
  return `<button class="tcard glass" data-do="pickTime:${key}" aria-label="${label} 시간 선택"><span class="cap">${label}</span><span class="dotnum" data-schedule-minute="${key}" aria-label="${value || '미설정'}">${DOTS(value || '—', 72)}</span></button>`;
}).join('')}</div>`; }
const syncTimeMirrors = () => document.querySelectorAll('.timefield').forEach(field => {
  const input = field.querySelector('input'), mirror = field.querySelector('.time24');
  if (input && mirror && mirror.textContent !== input.value) mirror.textContent = input.value;
});
const syncMeetHint = () => document.querySelectorAll('.meet-time').forEach(field => {
  const input = field.querySelector('input'), value = input?.value || '', shown = field.querySelector('.meet-value');
  field.classList.toggle('unset', !value);
  if (shown && shown.dataset.time !== value) {
    shown.dataset.time = value;
    shown.innerHTML = value ? DOTS(value, 78) : '';
    shown.setAttribute('aria-label', value || '미설정');
  }
  if (input && value) input.removeAttribute('aria-describedby');
  else if (input) input.setAttribute('aria-describedby', 'meetAtHint');
});
document.addEventListener('input', event => { if (event.target.matches?.('.timeinput')) syncTimeMirrors(); if (event.target.id === 'meetAt') syncMeetHint(); });
document.addEventListener('change', event => { if (event.target.id === 'meetAt') syncMeetHint(); });
new MutationObserver(() => { syncTimeMirrors(); syncMeetHint(); }).observe(document.getElementById('scr'), { childList: true, subtree: true });

function scheduleControls(s) { return `${timeControls(s)}<div class="list glass" style="margin-top:16px"><div class="li">${liIc('footprints')}<span class="t"><b>주차장에서 자리까지</b><span>걷는 시간을 빼고 계산해요</span></span><span class="v"><button class="iconbtn" data-do="walk:-1" aria-label="1분 줄이기">${I('minus')}</button><span class="mid" id="walkV" style="font-size:44px">${esc(s.preferences?.schedule?.walkingReserveMinutes)}분</span><button class="iconbtn" data-do="walk:1" aria-label="1분 늘리기">${I('plus')}</button></span></div></div>`; }

function soundChoices(s) { return `<div class="sounds">${SOUNDS.map(([t,p,k],i)=>{const key=SOUND_KEYS[i],on=s.preferences?.restSound === key;return `<button class="sound glass ${on ? 'on' : ''}" data-do="sound:${key}" aria-pressed="${on}">${SHAPE(k,p,84,{blur:5})}<b>${t}</b>${I(s.sound?.status === 'PLAYING' && s.sound?.sound === key && on ? 'pause' : 'play')}</button>`;}).join('')}</div>`; }

function preferenceSwitch(s,label,flag,on,sub='',prefix='') { return `<div class="li">${prefix}<span class="t"><b>${label}</b>${sub ? `<span>${sub}</span>` : ''}</span><button class="switch ${on ? 'on' : ''}" data-do="flag:${flag}" role="switch" aria-checked="${!!on}" aria-label="${label}"></button></div>`; }

function actualTrack(s) { const rows=dayRecords(s).sort((a,b)=>(a.knownTimeEpochMs ?? Infinity)-(b.knownTimeEpochMs ?? Infinity)).slice(-4); if(!rows.length) return `<div class="daytrack"><p class="cap">이날 저장된 활동 기록이 없어요</p></div>`;
  const cells=rows.map(r=>[JOURNEY_SLOT[r.journeyFacts?.kind] || 'q1',esc(r.activityTitle),esc(s.recordLabels?.[r.id]?.time || (r.timeBasis === 'SAVED_TIME' ? '저장 시각 기준' : '시각 미표시'))]);
  const now=typeof s.localNow === 'string' && s.localNow && s.screen === 'F4-03';
  if(now) cells.push(['pm','지금',esc(s.localNow),'localNow']);
  return DAYTRACK(cells, now ? cells.length - 1 : -1); }

function recordRows(s) { return dayRecords(s).map(r=>`<div class="li"><span class="t"><b>${esc(r.activityTitle)}</b><span>${esc(minutes(r.elapsedSeconds))}분 · ${esc(({completed:'완료',interrupted:'중단',safety_interrupted:'안전 중단'})[r.outcome])}${r.timeBasis === 'SAVED_TIME' ? ' · 저장 시각 기준' : r.timeBasis === 'UNDATED' ? ' · 활동 시각 알 수 없음' : ''}</span></span></div>`).join('') || `<div class="li"><span class="t"><b>기록 없음</b><span>선택한 날의 활동 기록이 없어요</span></span></div>`; }

function breathGraphic(s,pal,size=400) { const b=s.breathing,progress=b?.cycleDurationMs > 0 ? Math.min(1,Math.max(0,b.cycleElapsedMs/b.cycleDurationMs)) : 0; return `<style>.scr .breath .shape,.scr .breath .rings{animation-play-state:paused;animation-delay:var(--native-breath-delay,0s)}</style>` + G('amoeba',pal,size,size === 72 ? {wrap:'breath',blur:4} : {wrap:'breath',liquid:true,dial:1,keep:'breath'}).replace('class="gfx breath"',`class="gfx breath" style="--native-breath-delay:-${progress*9}s"`); }

function breathPhase(s) { return esc(({INHALE:'들이쉬고',TOP_UP:'한 번 더',EXHALE:'길게 내쉬세요'})[s.breathing?.phase] || '호흡 안내 대기 중'); }

function speechValue(s,round,key) { const m=s.speech?.[round]; if(!m) return null; if(key === 'seconds') return m.recordingDurationMs == null ? null : Math.floor(m.recordingDurationMs/1000); if(key === 'pace') return m.recordingDurationMs > 0 && m.recognizedTokenCount != null ? Math.round(m.recognizedTokenCount*60000/m.recordingDurationMs) : null; return m[key]; }

function musicTitle(s,i) { return esc(s.music?.choices?.[i]?.title || '연결된 음악 없음'); }

function selectedDay(s) { return s.week?.days?.find(d=>d.date === (s.selectedDate || s.day?.date || s.context?.localDate)) || null; }
// F4-03는 지금 진행 중인 하루 마무리 화면: 앞서 둘러본 날짜가 아니라 네이티브 로컬 날짜의 기록을 보여준다.
function timelineDay(s) { return s.screen === 'F4-03' ? s.week?.days?.find(d=>d.date === s.context?.localDate) || null : selectedDay(s); }

function dayRecords(s) { const ids=timelineDay(s)?.recordIds || []; return (s.records || []).filter(r=>ids.includes(r.id)); }

function selectedIntention(s) { return dayRecords(s).find(r=>r.journeyFacts?.kind === 'MORNING' && r.journeyFacts.intention)?.journeyFacts.intention; }

function weeklyRows(s) { return (s.week?.days || []).filter(d=>d.activityCount > 0).map(d=>`<button class="li" data-do="day:${esc(d.date)}"><span class="sg">${MINI(JOURNEY_SLOT[d.completedJourneys?.[0]] || 'q1',64)}</span><span class="t"><b>${esc(d.date)}</b><span>${esc(d.activityCount)}번 저장 · ${esc(d.completedCount)}번 완료${d.savedTimeElapsedSeconds > 0 ? ' · 저장 시각 기준 포함' : ''}</span></span><span class="v">${I('chevron-right')}</span></button>`).join('') || `<div class="li"><span class="t"><b>이번 주 기록이 없어요</b></span></div>`; }

function confirmModal(h,p,a,b) { return `<div class="modal-bg"><div class="modal glass"><h2 class="h2">${h}</h2><p class="body" style="margin-top:12px">${p}</p><div class="acts">${two(sec(a[0],null,a[1]),`<button class="btn m pri-m" data-do="${esc(b[1])}">${b[0]}</button>`)}</div></div></div>`; }

// 틈틈 프로토타입 화면 정의. 화면 하나 = 마크업 하나, Portrait/Landscape는 CSS container query가 재배치한다.
// 그래픽은 shapes.js의 SHAPE(형태, 팔레트, 크기). 문구는 ux_writing_guidelines.md 기준 (해요체, 결과가 보이는 버튼, 사실 위주).
const SK = 'fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round"';
const FILLED = {
  seat: () => `<path transform="translate(-4.45 -5.69) scale(.0988)" d="${SEATPATH(118)}"/>`,
  recline: () => `<path transform="translate(-4.49 -3.31) scale(.0871)" d="${SEATPATH(132)}"/>`,
  heat: () => `<path transform="translate(11.15 9.84) scale(.068)" d="${SEATPATH(118)}"/><path ${SK} stroke-width="3" d="M7 5c-2.4 3 2.4 5 0 8s2.4 5 0 8M13 5c-2.4 3 2.4 5 0 8s2.4 5 0 8"/>`,
  vent: () => `<path transform="translate(11.15 9.84) scale(.068)" d="${SEATPATH(118)}"/><g transform="translate(11 12)"><circle r="2.2"/>${[0, 120, 240].map(a => `<path transform="rotate(${a})" d="M-1.6-2.4C-3.4-6.6-1.4-10 1.6-10S5.4-6 1.6-2.6Z"/>`).join('')}</g>`,
  light: () => `<path ${SK} stroke-width="5" d="M8 15.5c10.5-6 21.5-6 32 0"/><path ${SK} stroke-width="3.2" d="M16 22.5l-2.2 7.5M24 21v8.5M32 22.5l2.2 7.5"/><path d="M10 36h28a2.5 2.5 0 0 1 0 5H10a2.5 2.5 0 0 1 0-5z" opacity=".35"/>`,
  sun: () => `<circle cx="24" cy="24" r="8.5"/>${[0, 45, 90, 135, 180, 225, 270, 315].map(a => `<rect x="22.4" y="3.5" width="3.2" height="7" rx="1.6" transform="rotate(${a} 24 24)"/>`).join('')}`,
  dim: () => '<path d="M28.5 5.5A18.5 18.5 0 1 0 42.6 31 15 15 0 0 1 28.5 5.5z"/>',
  fan: () => `<circle cx="24" cy="24" r="3.6"/>${[0, 120, 240].map(a => `<path transform="rotate(${a} 24 24)" d="M21.2 20.6C17.6 13 20 5.5 26.4 5.5c6.6 0 8.4 9.4 1.2 15.2Z"/>`).join('')}`,
  air: () => `<path ${SK} stroke-width="3.6" d="M6 17h21a5 5 0 1 0-5-5M6 25h29a5.5 5.5 0 1 1-5.5 5.5M6 33h15"/>`,
  blinds: () => '<rect x="6" y="6" width="36" height="5" rx="2.5"/><rect x="9" y="14" width="30" height="4" rx="2"/><rect x="9" y="21" width="30" height="4" rx="2"/><rect x="9" y="28" width="30" height="4" rx="2"/><rect x="9" y="35" width="30" height="4" rx="2"/>',
  window: () => '<path d="M8.6 31 15.4 12.8A4 4 0 0 1 19.2 10H36a3 3 0 0 1 3 3v18z"/><rect x="7" y="35" width="34" height="5" rx="2.5" opacity=".35"/>',
  lock: () => `<path ${SK} stroke-width="4" d="M16 21v-5a8 8 0 0 1 16 0v5"/><path fill-rule="evenodd" d="M14 20h20a5 5 0 0 1 5 5v13a5 5 0 0 1-5 5H14a5 5 0 0 1-5-5V25a5 5 0 0 1 5-5zm10 7a2.6 2.6 0 0 0-1.5 4.7V35h3v-3.3A2.6 2.6 0 0 0 24 27z"/>`,
  park: () => '<path fill-rule="evenodd" d="M14 5h20a9 9 0 0 1 9 9v20a9 9 0 0 1-9 9H14a9 9 0 0 1-9-9V14a9 9 0 0 1 9-9zm4.5 8.5V35h4.6v-7h4.6a7.25 7.25 0 0 0 0-14.5zm4.6 4.2h4.3a3.05 3.05 0 0 1 0 6.1h-4.3z"/>',
  rain: () => `<path d="M13 29a7 7 0 0 1-.6-14A10.5 10.5 0 0 1 32.4 11.6 8.7 8.7 0 0 1 35 29z"/><path ${SK} stroke-width="3" d="M16 34l-2 5.5M24 34l-2 5.5M32 34l-2 5.5"/>`,
  volume: () => `<path d="M8 18h6.5L23 10.5a1.5 1.5 0 0 1 2.5 1.1v24.8a1.5 1.5 0 0 1-2.5 1.1L14.5 30H8a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2z"/><path ${SK} stroke-width="3.4" d="M31 18.5a7.5 7.5 0 0 1 0 11M35.5 13a14.5 14.5 0 0 1 0 22"/>`,
  mic: () => `<rect x="17.5" y="4.5" width="13" height="24" rx="6.5"/><path ${SK} stroke-width="3.4" d="M11.5 22.5a12.5 12.5 0 0 0 25 0M24 35v7M18 42h12"/>`,
  pin: () => '<path fill-rule="evenodd" d="M24 4a14.5 14.5 0 0 1 14.5 14.5C38.5 29 24 44 24 44S9.5 29 9.5 18.5A14.5 14.5 0 0 1 24 4zm0 9a5.5 5.5 0 1 0 0 11 5.5 5.5 0 0 0 0-11z"/>',
  car: () => '<path fill-rule="evenodd" d="M11.3 14.6A5 5 0 0 1 16 11.5h16a5 5 0 0 1 4.7 3.1L40 23.4V37a2.5 2.5 0 0 1-2.5 2.5h-3.5A2.5 2.5 0 0 1 31.5 37v-2h-15v2a2.5 2.5 0 0 1-2.5 2.5h-3.5A2.5 2.5 0 0 1 8 37V23.4zM14.8 22h18.4l-2.3-5.8a1.5 1.5 0 0 0-1.4-.9H18.5a1.5 1.5 0 0 0-1.4.9zM15 26.5a2.8 2.8 0 1 0 0 5.6 2.8 2.8 0 0 0 0-5.6zm18 0a2.8 2.8 0 1 0 0 5.6 2.8 2.8 0 0 0 0-5.6z"/>',
  walk: () => '<ellipse cx="15.5" cy="17" rx="5" ry="8.5" transform="rotate(-8 15.5 17)"/><ellipse cx="15" cy="32" rx="4" ry="3.6"/><ellipse cx="32.5" cy="12" rx="5" ry="8.5" transform="rotate(8 32.5 12)"/><ellipse cx="33" cy="27" rx="4" ry="3.6"/>',
  touch: () => `<circle cx="24" cy="24" r="6.5"/><circle ${SK} stroke-width="3" cx="24" cy="24" r="13" opacity=".45"/>`,
  wave: () => [[8, 12], [15, 22], [22, 32], [29, 20], [36, 12]].map(([x, h]) => `<rect x="${x}" y="${24 - h / 2}" width="4.5" height="${h}" rx="2.25"/>`).join(''),
  locate: () => `<circle cx="24" cy="24" r="6"/><circle ${SK} stroke-width="3.2" cx="24" cy="24" r="13"/><path ${SK} stroke-width="3.2" d="M24 4v6M24 38v6M4 24h6M38 24h6"/>`,
  clock: () => '<path fill-rule="evenodd" d="M24 5a19 19 0 1 1 0 38 19 19 0 0 1 0-38zm-1.7 8.5v12.4l8.4 5.4 1.8-2.8-6.8-4.4V13.5z"/>',
  bell: () => '<path d="M24 5a2.6 2.6 0 0 1 2.6 2.6v1.1A12.4 12.4 0 0 1 36.4 20.8V30l3.1 4.2a1.5 1.5 0 0 1-1.2 2.4H9.7a1.5 1.5 0 0 1-1.2-2.4l3.1-4.2v-9.2a12.4 12.4 0 0 1 9.8-12.1V7.6A2.6 2.6 0 0 1 24 5zm-5 34.5h10a5 5 0 0 1-10 0z"/>',
  shield: () => '<path fill-rule="evenodd" d="M24 4l15.5 6v11.5C39.5 32 33 40 24 44 15 40 8.5 32 8.5 21.5V10zm7.2 13.3-9.2 9.2-4.4-4.4-2.4 2.4 6.8 6.8 11.6-11.6z"/>',
  info: () => '<path fill-rule="evenodd" d="M24 5a19 19 0 1 1 0 38 19 19 0 0 1 0-38zm-2 16.5v13h4v-13zm2-8a2.6 2.6 0 1 0 0 5.2 2.6 2.6 0 0 0 0-5.2z"/>',
  palette: () => '<path fill-rule="evenodd" d="M24 5C13.5 5 5 13 5 23s8.5 20 18.5 20c2.6 0 3.9-1.9 3.9-3.7 0-1-.4-1.8-1-2.6-.6-.7-.9-1.5-.9-2.5 0-2 1.6-3.6 3.6-3.6h4.3C38.9 30.6 43 26.4 43 21 43 12.2 34.5 5 24 5zm-9 15a2.8 2.8 0 1 0 0 5.6 2.8 2.8 0 0 0 0-5.6zm4.5-7.5a2.8 2.8 0 1 0 0 5.6 2.8 2.8 0 0 0 0-5.6zm9-1.5a2.8 2.8 0 1 0 0 5.6 2.8 2.8 0 0 0 0-5.6zm6.5 6a2.8 2.8 0 1 0 0 5.6 2.8 2.8 0 0 0 0-5.6z"/>',
  play: () => '<path d="M16 10.9v26.2a2.2 2.2 0 0 0 3.3 1.9l21.1-13.1a2.2 2.2 0 0 0 0-3.8L19.3 9a2.2 2.2 0 0 0-3.3 1.9z"/>',
  pause: () => '<rect x="12" y="9" width="8" height="30" rx="2.5"/><rect x="28" y="9" width="8" height="30" rx="2.5"/>',
};
const ALIAS = { armchair: 'seat', lamp: 'light', wind: 'air', flame: 'heat', 'app-window': 'window', 'cloud-rain': 'rain', 'circle-parking': 'park', 'volume-2': 'volume', 'car-front': 'car', 'map-pin': 'pin', footprints: 'walk', 'locate-fixed': 'locate', hand: 'touch', 'audio-lines': 'wave', 'clock-3': 'clock' };
const I = n => { const k = ALIAS[n] || n; return FILLED[k] ? `<svg class="lucide fi fi-${k}" viewBox="0 0 48 48" width="24" height="24" fill="currentColor" aria-hidden="true">${FILLED[k]()}</svg>` : `<i data-lucide="${n}"></i>`; };
const at = (go, d) => `${go ? `data-go="${esc(go)}" ` : ''}${d ? `data-do="${esc(d)}"` : ''}`;
const pri = (t, go, d) => `<button class="btn pri wide" ${at(go, d)}>${t}<span class="go">${I('arrow-right')}</span></button>`;
const sec = (t, go, d, c = '') => `<button class="btn sec m ${c}" ${at(go, d)}>${t}</button>`;
const lnk = (t, go, d) => `<button class="link" ${at(go, d)}>${t}</button>`;
const two = (a, b) => `<div class="two">${a}${b}</div>`;
const closeBtn = `<button class="iconbtn glass" data-do="end" aria-label="끝내기">${I('x')}</button>`;
// 상단 바: 이전 단계 · 진행 트랙(선 위의 라임 점) · 닫기
const previous = (s, back) => {
  if (!back || !s.journeyActive) return back;
  ACT.previous = () => command('PREVIOUS');
  return 'do:previous';
};
const backAt = back => back.startsWith('do:') ? at(null, back.slice(3)) : at(back);
const bar = (back, step, of, x = true) => `<header class="top">
  ${back ? `<button class="iconbtn glass" ${backAt(back)} aria-label="이전">${I('chevron-left')}</button>` : '<span class="ph"></span>'}
  ${of ? `<div class="track" role="img" aria-label="${of}단계 중 ${step}단계">${Array.from({ length: of }, (_, i) => `<i class="${i < step - 1 ? 'done' : ''}" style="--x:${i / (of - 1)}"></i>`).join('')}<b class="dot" data-p="${(step - 1) / (of - 1)}" style="--p:${(step - 1) / (of - 1)}"></b></div>` : '<span class="grow"></span>'}
  ${x ? closeBtn : '<span class="ph"></span>'}</header>`;
const copy = (h, p = '', pre = '') => `<div class="copy">${pre}<h1 class="h1">${h}</h1>${p ? `<p class="lead">${p}</p>` : ''}</div>`;
const scr = ({ cls = '', top = '', vis = '', side = '', acts = '', extra = '' }) =>
  `<section class="scr ${cls}">${top}<div class="vis">${vis}</div><div class="side">${side}${acts ? `<div class="acts">${acts}</div>` : ''}</div>${extra}</section>`;
const mic = t => `<p class="cap hint">${I('mic')}‘${t}’라고 말해도 돼요</p>`;
const MS = p => { const c = PAL[p] || PAL.home; return `style="--m1:${c[0]};--m2:${c[1]};--m3:${c[2]}"`; };
const DOTPATH = { '—': ['M0 30H36'], '-': ['M0 30H36'],
  0: ['M18 0C28 0 36 8 36 20V40C36 52 28 60 18 60C8 60 0 52 0 40V20C0 8 8 0 18 0Z'], 1: ['M8 12L22 0', 'M22 0V60'],
  2: ['M1 15C1 6 9 0 18 0C27 0 35 6 35 15C35 22 31 27 25 33L1 60', 'M1 60H36'], 3: ['M2 9C5 3 11 0 18 0C27 0 34 6 34 14C34 22 27 28 17 28', 'M17 28C28 28 36 34 36 44C36 53 28 60 18 60C10 60 3 56 0 50'],
  4: ['M29 60V0', 'M29 0L0 40', 'M0 40H36'], 5: ['M33 0H3', 'M3 0L1 28', 'M1 28C6 24 11 22 18 22C28 22 36 30 36 41C36 52 28 60 18 60C10 60 4 57 0 51'],
  6: ['M31 4C27 1 23 0 19 0C8 0 0 10 0 24V42C0 52 8 60 18 60C28 60 36 52 36 42C36 32 28 24 18 24C12 24 7 26 4 29'], 7: ['M0 0H36', 'M36 0L12 60'],
  8: ['M18 27C10 27 4 21 4 13.5C4 6 10 0 18 0C26 0 32 6 32 13.5C32 21 26 27 18 27Z', 'M18 27C8 27 0 34 0 43.5C0 53 8 60 18 60C28 60 36 53 36 43.5C36 34 28 27 18 27Z'],
  9: ['M5 56C9 59 13 60 17 60C28 60 36 50 36 36V18C36 8 28 0 18 0C8 0 0 8 0 18C0 28 8 36 18 36C24 36 29 34 32 31'] };
let DOTPTS;
const dotPts = ch => { if (!DOTPTS) { DOTPTS = {}; const ns = 'http://www.w3.org/2000/svg', svg = document.body.appendChild(document.createElementNS(ns, 'svg')), p = svg.appendChild(document.createElementNS(ns, 'path'));
    svg.style.cssText = 'position:absolute;width:0;height:0';
    for (const [k, strokes] of Object.entries(DOTPATH)) { const pts = [];
      for (const d of strokes) { p.setAttribute('d', d); const L = p.getTotalLength(), n = Math.max(1, Math.round(L / 10)), closed = d.endsWith('Z');
        for (let i = 0; i <= n - closed; i++) { const q = p.getPointAtLength(L * i / n); if (pts.every(([x, y]) => Math.hypot(x - q.x, y - q.y) > 6)) pts.push([+q.x.toFixed(1), +q.y.toFixed(1)]); } }
      DOTPTS[k] = pts; }
    svg.remove(); }
  return DOTPTS[ch] || []; };
const DOTS = (t, h = 104) => { let x = 0, out = '';
  for (const ch of String(t)) { if (ch === ':') { out += `<circle cx="${x + 3}" cy="19" r="3.4"/><circle cx="${x + 3}" cy="41" r="3.4"/>`; x += 22; continue; }
    out += dotPts(ch).map(([a, b]) => `<circle cx="${x + a}" cy="${b}" r="3.4"/>`).join(''); x += 52; }
  return `<svg class="dots" viewBox="-4 -4 ${x - 8} 68" height="${h}" fill="currentColor" role="img" aria-label="${t}">${out}</svg>`; };
// 값이 바뀔 때(시각·분·걸음): 새 값이 흐림에서 또렷해지며 나타난다. 연달아 눌러도 마지막 값에서 다시 시작한다
const dissolve = (el, html) => { if (!el) return; el.innerHTML = html; el.getAnimations().forEach(a => a.id === 'dz' && a.cancel()); if (_RM) return;
  el.animate([{ opacity: .2, filter: 'blur(6px)' }, { opacity: 1, filter: 'blur(0)' }], { duration: 340, easing: 'cubic-bezier(.23, 1, .32, 1)' }).id = 'dz'; };
const MIN = (n, u = '분') => `${esc(n)}<sup>${esc(u)}</sup>`;

// 가는 선 조형 (Pleos 생태계 그래픽의 점선 궤도·눈금)
const RINGS = (s, o = .3) => { const R = s / 2, w = Math.round(s * 1.9);
  return `<svg class="rings" width="${w}" height="${w}" viewBox="${-w / 2} ${-w / 2} ${w} ${w}" style="opacity:${o}">
    <circle r="${R * 1.1}" stroke-width="2" stroke-dasharray="2 12"/><ellipse rx="${R * 1.75}" ry="${R * .42}" stroke-width="1.5" stroke-dasharray="4 16" opacity=".8"/></svg>`; };
const DIAL = (s, on = 1) => { const w = s + 140, r1 = s / 2 + 40, r2 = s / 2 + 58;
  return `<svg class="rings" width="${w}" height="${w}" viewBox="${-w / 2} ${-w / 2} ${w} ${w}">${Array.from({ length: 72 }, (_, i) => {
    const a = (i / 72) * Math.PI * 2 - Math.PI / 2, c = Math.cos(a), n = Math.sin(a), big = i % 6 === 0;
    return `<line x1="${c * r1}" y1="${n * r1}" x2="${c * (big ? r2 + 10 : r2)}" y2="${n * (big ? r2 + 10 : r2)}" stroke-width="${big ? 3 : 2}" opacity="${i / 72 < on ? .7 : .18}"/>`; }).join('')}</svg>`; };
const G = (kind, pal, size, o = {}) => `<div class="gfx ${o.wrap || ''}"${o.keep ? ` data-keep="${o.keep}"` : ''}>${o.rings ? RINGS(size, o.rings) : ''}${o.dial ? DIAL(size, o.dial) : ''}${SHAPE(kind, pal, size, o)}</div>`;
// 말하기·듣기 화면은 모두 같은 그림: 그 흐름의 형태 + 아래 소리 막대. on이 아니면 막대가 낮게 쉬다가 듣기 시작하면 움직인다
const VOICE = (k, pal, on, extra = '', size = 380, o = {}) => `<div class="stack-c">${G(k, pal, size, { wrap: 'float', rings: .26, ...o })}<div class="wave ${on ? 'speak' : ''}">${Array.from({ length: 24 }, (_, i) => `<i style="--h:${10 + Math.round(Math.abs(Math.sin(i * 1.7)) * 40)};--i:${i}"></i>`).join('')}</div>${extra}</div>`;
// 시트: 첨부한 시트 아이콘(551×563 그리드)의 윤곽을 좌표 그대로 옮겼다. 앞이 왼쪽, 선 하나로 된 한 덩어리.
// 118°에서 원본과 똑같다. 등받이는 뒤 아래 모서리 원(중심 SP, 반지름 44)의 중심에서 돌아서 뒷선이 늘 그 원에 접한다.
// 각도가 바뀌면 등받이 점들만 돌리고, 쿠션 윗선과 등받이 앞선이 만나는 점 J를 다시 구한다 (경로 명령은 그대로 → d 전환이 매끄럽다)
const SP = [392.3, 459];
const SEATPATH = deg => { const f = (deg - 118) * Math.PI / 180, c = Math.cos(f), sn = Math.sin(f), r = n => Math.round(n * 10) / 10;
  const R = (x, y) => [SP[0] + (x - SP[0]) * c - (y - SP[1]) * sn, SP[1] + (x - SP[0]) * sn + (y - SP[1]) * c];
  const p = (x, y) => R(x, y).map(r).join(" "), [ax, ay] = R(357, 387), t = 0.2006 + f;
  return `M122 387H${r(ax + (ay - 387) / c * sn)}L${p(357, 340)}C${p(357, 300)} ${p(372, 276)} ${p(400, 246)}L${p(436, 204)}C${p(430, 186)} ${p(428, 166)} ${p(428, 130)}` +
    `A32 32 0 0 1 ${p(460, 98)}A30 30 0 0 1 ${p(490, 128)}L${p(490, 200)}L${r(SP[0] + 44 * Math.cos(t))} ${r(SP[1] + 44 * Math.sin(t))}A44 44 0 0 1 392.3 503H146Q126 503 116 486L91 444Q86 434 86 423A36 36 0 0 1 122 387Z`; };
// 시트 연출 (18차, RonDesignLab 'Optimal' · '25 years'): 형태는 그대로 두고 선에 강약을 준다
// - 뒤에 점 그리드: 시트 가운데는 점이 크고 진하며, 밖으로 갈수록 작고 옅어진다
// - 곧게 선 106°에서 지금 각도까지 등받이가 지나온 자리에 가는 잔상 선 넷. 지금에 가까울수록 진하다
// - 윤곽 2px · 잔상 1px · 눈금 1px(10°마다 1.6px) · 지나온 호 2.5px. 색은 먹색에 화면 빛을 섞는다 (screens.css 18차 --sl)
const ARC = 'M380.4 67.9A391.3 391.3 0 0 1 701.8 219.6';
const ECHO = [.2, .4, .6, .8], echoD = deg => ECHO.map(t => SEATPATH(106 + (deg - 106) * t));
const SEATGRID = (() => { let o = ''; for (let y = 64; y <= 520; y += 20) for (let x = 64; x <= 624; x += 20) {
  const d = Math.hypot((x - 330) / 300, (y - 330) / 236); if (d < 1) o += `<circle cx="${x}" cy="${y}" r="${(1 + 1.6 * (1 - d)).toFixed(2)}" opacity="${((1 - d) ** 1.2).toFixed(2)}"/>`; }
  return o; })();
const SEAT = (deg, ctl = false) => `<svg class="seat" viewBox="60 50 520 470" width="480" height="434" aria-hidden="true"><g class="sgrid">${SEATGRID}</g>
  ${ctl ? `<path class="arc" d="${ARC}"/><path class="arc-on" pathLength="54" d="${ARC}" style="stroke-dasharray:${deg - 106} 54"/><g class="ticks">${SEATTICKS}</g>` : ''}
  <g class="echo">${echoD(deg).map((d, k) => `<path style="--k:${k}" d="${d}"/>`).join('')}</g><path class="sil" d="${SEATPATH(deg)}"/>
  ${ctl ? `<g class="rot" style="transform:rotate(${deg - 118}deg)"><g class="knob" transform="translate(462 74)"><circle r="17" class="kf"/><circle r="6" class="kd"/><path d="M-27 -7A28 28 0 0 1 -7 -27"/><path d="M27 7A28 28 0 0 1 7 27"/></g></g>` : ''}</svg>`;
// 호 위의 눈금: 등받이가 움직일 수 있는 범위(106~160°)를 2°마다, 10°마다 길고 굵게
const SEATTICKS = Array.from({ length: 28 }, (_, i) => { const a = (-1.74 + i * 2) * Math.PI / 180, l = i % 5 ? 10 : 20, p = r => `${(SP[0] + r * Math.sin(a)).toFixed(1)} ${(SP[1] - r * Math.cos(a)).toFixed(1)}`; return `<path${i % 5 ? '' : ' class="mj"'} d="M${p(379)}L${p(379 - l)}"/>`; }).join('');
// −/+ 버튼과 끌기가 같은 값을 바꾼다. off: 화면에 보이는 각도 = state.seat + off (낮잠은 14° 더 눕힌다)
const SEATCTL = (s, off = 0) => { const key = off ? 'napDegrees' : 'restDegrees', native = s.preferences?.backrest?.[key], deg = native == null ? null : Number(drafts.get(`backrest:${key}`) ?? native); return `<div class="stack-c seatctl" data-seat-kind="${off ? 'nap' : 'rest'}">${deg == null ? SEAT(118) : SEAT(deg, true)}
  <div class="row-c"><button class="iconbtn glass" data-do="seat:-4" aria-label="덜 눕히기">${I('minus')}</button><span class="mid" id="seatDeg">${esc(deg)}°</span><button class="iconbtn glass" data-do="seat:4" aria-label="더 눕히기">${I('plus')}</button></div>
  <p class="cap hint">${I('hand')}저장할 자세 선호값 · 차량 각도는 확인되지 않았어요</p></div>`; };
// 작은 데이터 그림 (veri·Health 레퍼런스): 눈금자 · 세그먼트 · 미니 막대 · 사인파 · 주간 막대 · 하루 트랙
const RULER = pos => `<div class="ruler">${Array.from({ length: 31 }, (_, i) => `<i class="${i % 5 ? '' : 'l'}"></i>`).join('')}<b style="left:${pos * 100}%"></b></div>`;
const SEGS = (on, n = 3, p = 'coral') => `<div class="segs">${Array.from({ length: n }, (_, i) => `<i ${i < on ? `style="background:linear-gradient(90deg,${PAL[p][2]},${PAL[p][0]})"` : ''}></i>`).join('')}</div>`;
const MBARS = (vs, p = 'coral') => `<div class="mbars">${vs.map(v => `<i style="height:${v * 100}%;background:linear-gradient(${PAL[p][2]},${PAL[p][0]})"></i>`).join('')}</div>`;
const SINE = x => `<svg class="sine" viewBox="0 0 240 60" preserveAspectRatio="none"><path d="M0 30C20 6 40 6 60 30S100 54 120 30 160 6 180 30 220 54 240 30" fill="none" stroke="currentColor" stroke-width="2.5"/><path d="M${x} 6l7 10h-14z" fill="var(--lime)"/></svg>`;
const BARS = (s, n = 7, lab = true) => { const days = s.week?.days || [], max = Math.max(1, ...days.map(d => d.elapsedSeconds || 0)); return `<div class="bars">${Array.from({length:n}, (_, i) => {const d=days[i], v=(d?.elapsedSeconds || 0)/max, p=PAL.home; return `<div class="bc" style="--n:${i}"><span class="bt">${v ? `<i style="height:${v*100}%;background:linear-gradient(${p[2]} 0%, ${p[0]} 55%, ${p[1]})"></i>` : ''}</span>${lab ? `<span class="cap">${['월','화','수','목','금','토','일'][i]}</span>` : ''}</div>`;}).join('')}</div>`; };
const DAYS = s => `<div class="days">${Array.from({length:7}, (_,i) => {const d=s.week?.days?.[i]; return `<button class="dy ${d?.date === s.day?.date ? 'on' : ''} ${d ? '' : 'off'}" ${d ? `data-do="day:${esc(d.date)}"` : 'disabled'}>${['월','화','수','목','금','토','일'][i]}</button>`;}).join('')}</div>`;
const DAYTRACK = (rows, now = rows.length - 1, cls = '') => `<div class="daytrack ${cls}"><span class="dt-line"></span>${rows.map(([k, n, t, live], i) => `<div class="dt" style="--i:${i}">${MINI(k, 110)}<b>${n}</b><span class="cap"${live ? ` data-live="${esc(live)}"` : ''}>${t}</span>${i === now ? '<i class="now"></i>' : ''}</div>`).join('')}</div>`;

const GAUGE = (t, ends) => { const cx = 280, cy = 430, R = 300, A = 38, B = 64, rad = a => a * Math.PI / 180;
  const pt = (a, r = R) => [+(cx + r * Math.sin(rad(a))).toFixed(1), +(cy - r * Math.cos(rad(a))).toFixed(1)];
  const arc = (a0, a1) => { const [x0, y0] = pt(a0), [x1, y1] = pt(a1); return `M${x0} ${y0}A${R} ${R} 0 0 1 ${x1} ${y1}`; };
  const ticks = Array.from({ length: 37 }, (_, i) => { const a = -A + i * A / 18, l = i % 6 ? 10 : 20, [x0, y0] = pt(a, R - 10), [x1, y1] = pt(a, R - 10 - l); return `<line x1="${x0}" y1="${y0}" x2="${x1}" y2="${y1}"/>`; }).join('');
  const dot = (a, h, r) => { const [x, y] = pt(a); return `<circle cx="${x}" cy="${y}" r="${h}" class="g-halo"/><circle cx="${x}" cy="${y}" r="${r}" class="g-dot"/>`; };
  const [kx, ky] = pt(-A + 2 * A * t);
  return `<div class="gauge"><div class="g-art"><svg viewBox="0 96 560 204" aria-hidden="true">
    <path class="g-dash" d="${arc(-B, -A)}"/><path class="g-dash" d="${arc(A, B)}"/><g class="g-tick">${ticks}</g><path class="g-arc" d="${arc(-A, A)}"/>${dot(-A, 16, 6)}${dot(A, 16, 6)}${dot(4 - B, 12, 3.5)}${dot(B - 4, 12, 3.5)}
    <g class="g-knob" transform="translate(${kx} ${ky})"><circle r="17" class="g-kf"/><circle r="6" class="g-kd"/><path d="M-27 -7A28 28 0 0 1 -7 -27"/><path d="M27 7A28 28 0 0 1 7 27"/></g></svg></div>
    <div class="g-foot cap"><span>${ends[0]}</span><span>${ends[1]}</span></div></div>`; };
// 가는 선 조형 (Neka): 기울기가 다른 궤도 여러 개, 가운데 라임 점
const WIRE = (size = 200) => { const b = [0, 34, 68, 104, 140].map((a, i) => `<ellipse cx="100" cy="100" rx="86" ry="${30 + i * 4}" transform="rotate(${a} 100 100)"/>`).join('') + '<circle class="w-dot" cx="108" cy="94" r="7"/>';
  return `<svg class="wire" viewBox="0 0 200 200" width="${size}" height="${size}" aria-hidden="true">${b}</svg>`; };
const MOODS = { calm: ['차분하게', '긴장되는 회의가 있는 날'], firm: ['단호하게', '거절하거나 협상해야 하는 날'], warm: ['다정하게', '사람을 많이 만나는 날'], focus: ['집중해서', '혼자 끝낼 일이 많은 날'], flex: ['유연하게', '일정이 자주 바뀌는 날'] };
const GREET = { '08:40': '좋은 아침이에요', '12:35': '점심 맛있게 먹었어요?', '15:40': '오후도 힘내요', '19:05': '오늘도 고생 많았어요', '19:10': '오늘도 고생 많았어요' };
const NAME = { am: '아침 준비', noon: '점심 낮잠', meet: '마음 준비', pm: '하루 마무리' };
const HM = t => { if (!t) return '시간 미설정'; const [h, m] = t.split(':').map(Number); return `${h % 12 || 12}시${m ? ` ${m}분` : ''}`; };
// 목록·입력이 주인공인 화면: Portrait는 위→아래, Landscape는 왼쪽 글·버튼 + 오른쪽 내용
const solo = (top, h, p, body, acts) => scr({ cls: 'solo', top, side: copy(h, p) + `<div class="sbody">${body}</div>`, acts });

function hero(s) {
  const d = k => s.done[k] || s.skipped[k];
  const missing = '사용 가능한 시간을 아직 확인하지 못했어요';
  const current = SLOTS.find(([k]) => k === s.slot && !d(k));
  if (current) return {
    k: current[0], ctx: esc(s.context?.summary || '일정과 차량 상태를 확인해 주세요'),
    name: NAME[current[0]], big: s.context?.availableMinutes,
    sub: esc(s.context?.scheduleSummary || missing), btn: ['시작', current[2]], now: true
  };
  const k = ['noon', 'meet', 'pm', 'am'].find(k => !d(k));
  if (k) {
    const sub = {
      am: '숨을 고르고 오늘의 다짐을 정해요',
      noon: s.preferences?.schedule?.lunchReturnMinute != null ? '점심 먹고 차에 오면 알려 드려요' : missing,
      meet: s.meetAt ? `${HM(s.meetAt)} 일정 20분 전에 알려 드려요` : missing,
      pm: '집에 도착하면 알려 드려요'
    }[k];
    return { k, ctx: `오늘 쉬는 시간이 ${SLOTS.filter(([k]) => !d(k)).length}번 남았어요`,
      name: NAME[k], big: null, sub, btn: ['시작', null, `begin:${k}`] };
  }
  const today = s.week?.days?.find(day => day.date === s.context?.localDate);
  return { k: 'pm', ctx: '내일 아침에 다시 만나요', name: '네 번 모두 쉬었어요',
    big: minutes(today?.elapsedSeconds), sub: '오늘 쉰 시간이에요', btn: ['오늘 기록 보기', 'R-02'] };
}

function picked(s, base) {
  const k = s.homePick;
  const slot = SLOTS.find(([key]) => key === k);
  if (!slot || (k === base.k && !s.done[k])) return base;
  const descriptions = {
    am: '숨을 고르고 오늘의 다짐을 정해요',
    noon: '설정한 복귀 시간에 맞춰 잠깐 쉬어요',
    meet: '할 말을 말해 보고 힘이 나는 음악을 들어요',
    pm: '집에 들어가기 전에 잠깐 쉬어 가요'
  };
  return {
    k, name: NAME[k], big: k === s.slot ? s.context?.availableMinutes : null,
    sub: descriptions[k],
    btn: [s.done[k] ? '다시 하기' : '시작', null, `begin:${k}`]
  };
}
const SLOTS = [['am', null, 'F1-01'], ['noon', null, 'F2-02'], ['meet', null, 'F3-03'], ['pm', null, 'F4-02']];
const nextKey = s => SLOTS.find(([k]) => !s.done[k] && !s.skipped[k])?.[0];
const slotGo = (s,k,g) => s.done[k] ? (k === 'meet' ? 'F3-08' : 'R-02') : g;
const S = {};
/* ───────────── 온보딩 ───────────── */
// 첫 화면: 하루 네 번의 형태가 살짝 겹쳐 하나가 된다(= 틈틈). 시작하면 흩어지고, 다음 장에서 하루 트랙 위에 따로 놓인다
// [시간대, 크기, x, y, 흩어질 방향 x, y]
const MERGE = [['noon', 310, 0, 200, -90, 60], ['am', 300, 60, 0, -80, -60], ['pm', 310, 230, 180, 90, 60], ['meet', 300, 240, -10, 80, -70]];
// 앱 아이콘(GNB·앱 정보)도 같은 배치를 줄여 쓴다
const MARK = w => { const k = w / 560; return `<span class="mk" style="width:${w}px;height:${Math.round(500 * k)}px">${MERGE.map(([s, z, x, y]) =>
  `<span style="left:${Math.round(x * k)}px;top:${Math.round(y * k)}px">${SHAPE(SIG[s], SIGPAL[s], Math.round(z * k))}</span>`).join('')}</span>`; };
S['ON-01'] = { aura: 'home', view: s => scr({ cls: 'mid-side',
  vis: `<div class="merge4">${MERGE.map(([k, z, x, y, dx, dy], i) => `<div class="gfx ${i % 2 ? 'float2' : 'float'}" style="left:${x}px;top:${y}px;--i:${i};--dx:${dx}px;--dy:${dy}px">${SHAPE(SIG[k], SIGPAL[k], z)}</div>`).join('')}</div>`,
  side: copy('틈틈', '주차한 차 안에서 5~30분,<br>쉬어 가는 앱이에요'), acts: pri('시작하기', 'ON-02') }) };
const SLIDE = [['쉴 수 있을 때<br>차가 먼저 알려 줘요', '출근 전, 점심 뒤, 중요한 일정 전, 퇴근 후에 남는 시간을 계산해요', 'sunrise'],
  ['쉬는 동안 편한 자세와<br>소리를 정해요', '차량 기능은 지원과 실제 상태를 확인해요', 'dusk'],
  ['쉰 시간은<br>차곡차곡 쌓여요', '일주일 동안 모은 시간을 볼 수 있어요', 'flex']];
const SLIDEVIS = [
  s => DAYTRACK([['am', NAME.am, '시간 미설정'], ['noon', NAME.noon, '시간 미설정'], ['meet', NAME.meet, '시간 미설정'], ['pm', NAME.pm, '도착 확인 필요']], -1, 'rise'),
  s => `<div class="cabin-card">${SEAT(126)}<div class="chips">${[['armchair', '등받이'], ['lamp', '조명'], ['wind', '공조']].map(([i, t]) => `<span class="chip glass">${I(i)}${t} · ${fact(s,'cabin',i)}</span>`).join('')}</div></div>`,
  s => `<div class="week-card glass"><p class="lab">이번 주 쌓인 틈새 시간</p><span class="big">${MIN(minutes(s.week?.elapsedSeconds))}</span><div style="margin-top:24px">${BARS(s, 7)}</div></div>`];
const SCOPY = i => copy(SLIDE[i][0], SLIDE[i][1]);
S['ON-02'] = { aura: s => SLIDE[s.slide][2], view: s => scr({ cls: 'mid-side',
  top: bar('do:slideBack', s.slide + 1, 8, false).replace(/<span class="ph"><\/span><\/header>$/, '<button class="link" data-go="ON-03">건너뛰기</button></header>'),
  vis: `<div class="slide-vis">${SLIDEVIS[s.slide](s)}</div>`, side: `<div class="slide-copy">${SLIDE.map((_, i) => `<div class="ghost" aria-hidden="true">${SCOPY(i)}</div>`).join('')}${SCOPY(s.slide)}</div>`, acts: pri('다음', null, 'slide') }) };
const liIc = i => `<span class="iconbtn glass">${I(i)}</span>`;
S['ON-03'] = { aura: 'home', auraCls: 'low', view: s => solo(bar('ON-02', 4, 8, false), '이 권한이 필요해요', '', `<div class="list glass">
    <div class="li">${liIc('car-front')}<span class="t"><b>차량 제어</b><span>시트·조명·공조를 맞출 때 써요</span></span></div>
    <div class="li">${liIc('mic')}<span class="t"><b>마이크</b><span>다짐과 하루 이야기를 말로 남길 때 써요</span></span></div>
    <div class="li">${liIc('map-pin')}<span class="t"><b>위치</b><span>회사와 집에 도착한 걸 알 때 써요</span></span></div></div>`,
  pri('권한 확인하기', null, 'permissions') + lnk('나중에 하기', 'ON-04')) };
const PLACE = (k, addr, pin) => `<div class="place"><div class="place-art">${PLACEART(k)}${pin ? `<span class="pin">${I('check')}</span>` : ''}</div><span class="cap">${k === 'home' ? '집' : '회사'}</span><b>${esc(addr)}</b></div>`;
S['ON-04'] = { aura:'home', auraCls:'low', view:s => solo(bar('ON-03',5,8,false), '회사와 집을 알려 주세요', '도착하면 쉴 수 있는 시간을 알려 드려요', placeControls(s), pri('다음','ON-05') + lnk('나중에 하기','ON-05')) };
S['ON-05'] = { aura:'home', auraCls:'low', view:s => solo(bar('ON-04',6,8,false),'평소 시간을 알려 주세요','',scheduleControls(s),pri('다음','ON-06')) };
S['ON-06'] = { aura: 'home', auraCls: 'low', view: s => scr({ cls: 'mid-side', top: bar('ON-05', 7, 8, false),
  vis: SEATCTL(s, 0),
  side: copy('쉴 때 편한<br>등받이 각도를 정해 주세요', '앱의 자세 선호값이에요. 차량 제어 지원은 별도로 확인해요'), acts: pri('이 각도로 저장', null, 'saveBackrest:onboarding') + lnk('기본값 쓰기', null, 'defaultBackrest') }) };
// 명상·휴식 때 많이 듣는 소리. 그래픽은 소리마다 다른 추상 형태
const SOUNDS = [['빗소리', 'rose', 'amoeba'], ['파도', 'calm', 'bands'], ['숲', 'flex', 'cluster'], ['모닥불', 'firm', 'drop'],
  ['싱잉볼', 'warm', 'ring'], ['바람', 'mist', 'gust'], ['백색소음', 'focus', 'orb'], ['소리 없음', 'still', 'orb']];
S['ON-07'] = { aura:'home', auraCls:'low', view:s => solo(bar('ON-06',8,8,false),'쉴 때 들을 소리를 골라 주세요','',soundChoices(s) + `<div class="list glass" style="margin-top:24px">${preferenceSwitch(s,'음성 안내','VOICE_GUIDE',s.preferences?.voiceGuide,'알림과 안내를 소리로도 들려줘요')}</div>`,pri('설정 마치기',null,'completeOnboarding')) };
S['ON-08'] = { aura: 'sunrise', view: s => scr({ cls: 'mid-side', vis: G('amoeba', 'sunrise', 440, { wrap: 'float', rings: .3, liquid: true }),
  side: copy('설정 확인', '저장된 설정과 알림 권한에 따라 안내해요'),
  acts: pri('3분 숨 고르기 해 보기', 'Q1-01') + sec('홈으로', 'H-01', null, 'wide') }) };

/* ───────────── 홈 ───────────── */
// 아침에 고른 분위기는 홈까지 이어진다: 배경 빛, 큰 카드의 색, 인사 아래 분위기와 다짐
S['H-01'] = { aura: s => s.done.am ? s.mood : 'home', auraCls: 'home',
  view: s => { const base = hero(s), h = picked(s, base), sel = s.homePick || h.k, p = s.done.am ? s.mood : SIGPAL[h.k];
    return `<section class="scr home">
      <header class="top home-top"><div><h1 class="h1">${esc(s.context?.greeting || '안녕하세요')}</h1><p class="lead">${base.ctx}</p></div>
        <span class="row-c" style="gap:12px"><button class="iconbtn glass" data-go="R-01" aria-label="기록">${I('chart-no-axes-column')}</button><button class="iconbtn glass" data-go="ST" aria-label="설정">${I('settings')}</button></span></header>
      <div class="hgrid">
        <section class="hero mesh" ${MS(p)}>
          <div class="hero-gfx">${SHAPE(SIG[h.k], p, 400, { cls: 'float' })}</div>${s.done.am ? `<p class="mood-line">${SHAPE(MOODSHAPE[s.mood], s.mood, 44, { blur: 3 })}<b>${MOODS[s.mood][0]}</b><span>${esc(s.intention)}</span></p>` : ''}
          <div class="hero-row"><h2 class="h2">${h.name}</h2><span class="big">${MIN(h.big)}</span></div>
          <p class="body">${h.sub}</p>
          ${h.btn ? `<div class="acts" style="margin-top:24px">${h.now ? pri(h.btn[0], null, `start:${h.btn[1]}`) : pri(h.btn[0], h.btn[1], h.btn[2])}</div>` : ''}
        </section>
        <div class="hr">
          <div class="sec-h"><h2 class="h2">오늘</h2><button class="iconbtn glass" data-go="F3-00" aria-label="중요한 일정 추가">${I('plus')}</button></div>
          <div class="day">${SLOTS.map(([k]) => { const st = s.done[k] ? 'done' : s.skipped[k] ? 'skip' : '', on = k === sel;
            return `<button class="slot glass ${st}${on ? ' sel' : ''}" data-do="slotPick:${k}" aria-pressed="${on}">
            <span class="sg">${MINI(k, 72, !s.done[k] && !on)}</span>${s.done[k] ? `<span class="badge ${s.justDone === k ? 'stamp' : ''}">${I('check')}</span>` : `<span class="sv">${st === 'skip' ? '건너뜀' : esc(k === 'meet' ? s.meetAt : s.context?.slotTimes?.[k])}</span>`}<b>${NAME[k]}</b></button>`; }).join('')}</div>
          <section class="glass card only-l"><div class="row-c" style="justify-content:space-between"><p class="lab">이번 주 쌓인 틈새 시간</p><span class="mid" style="font-size:48px">${MIN(minutes(s.week?.elapsedSeconds))}</span></div><div style="margin-top:24px">${BARS(s, 7)}</div></section>
        </div>
      </div></section>`; },
};
S['H-02'] = { aura: 'still', auraCls: 'low', view: s => `<section class="scr drive"><div class="drive-head">
  <p class="lab">${esc(s.hardware?.gate?.reason || '차량 상태 확인')}</p><h1 class="drive-t">정차하면<br>이어서 할 수 있어요</h1></div><div class="drive-vis" aria-hidden="true">${GAUGE(.74, ['',''])}</div><div class="dots big-dots">${SLOTS.map(([k]) => `<i class="${s.done[k] ? 'on' : ''}"></i>`).join('')}</div></section>` };
S['V-01'] = { ...S['H-02'], view: s => S['H-02'].view(s) + `<div class="caption glass">${I('audio-lines')}<span>집에 도착하기 전에 쉬어 갈까요?</span>${sec('쉬어 가기', null, 'begin:pm')}${lnk('나중에', null, 'resume')}</div>` };

/* ───────────── 공통: 차가 먼저 하는 제안 · 끝내기 · 원래대로 ───────────── */
// 제안 화면은 아직 시작 전이라 닫으면 홈으로 돌아간다 (고립되지 않게 오른쪽 위 닫기는 항상 같은 자리)
const dismissTop = () => `<header class="top"><span class="ph"></span><span class="grow"></span><button class="iconbtn glass" data-go="H-01" aria-label="닫기">${I('x')}</button></header>`;
const moment = o => ({ aura: o.pal, view: s => scr({ cls: 'mid-side', top: dismissTop(), vis: G(o.shape, o.pal, 460, { wrap: 'float', rings: .26 }),
  side: `<div class="copy"><h1 class="h1">${o.name}</h1><div class="big" style="margin:4px 0 8px">${MIN(typeof o.min === 'function' ? o.min(s) : o.min)}</div><p class="lead">${typeof o.sub === 'function' ? o.sub(s) : o.sub}</p></div>`,
  acts: pri(o.pri[0], null, o.pri[1]) + two(sec(o.a[0], o.a[1], o.a[2]), sec(o.b[0], null, o.b[1])) + mic(o.pri[0]) }) });
S['M-F1'] = moment({ pal: 'sunrise', shape: 'arch', name: '아침 준비', min: s => s.context?.availableMinutes, sub: s => esc(s.context?.scheduleSummary || '출근 시간을 아직 확인하지 못했어요'), pri: ['시작', 'start:F1-01'], a: ['3분만', null, 'short:F1-02'], b: ['오늘은 건너뛰기', 'skip:am'] });
S['F2-01'] = moment({ pal: 'dusk', shape: 'pill', name: '점심 낮잠', min: s => s.sleep, sub: s => esc(s.context?.napSummary || '낮잠 종료 시간을 확인해 주세요'), pri: ['지금 자기', 'start:F2-02'], a: ['시간 바꾸기', null, 'sleeptime'], b: ['오늘은 건너뛰기', 'skip:noon'] });
S['F3-01'] = moment({ pal: 'coral', shape: 'tri', name: '마음 준비', min: s => s.context?.availableMinutes, sub: s => esc(s.context?.meetingSummary || '중요한 일정 전에 할 말을 말해 봐요'), pri: ['시작', 'start:F3-03'], a: ['3분 숨 고르기', 'Q1-01'], b: ['오늘은 건너뛰기', 'skip:meet'] });
S['F4-01'] = moment({ pal: 'ember', shape: 'venn', name: '하루 마무리', min: s => s.context?.availableMinutes, sub: '집에 들어가기 전에 잠깐 쉬어 가요', pri: ['쉬기', 'start:F4-02'], a: ['5분만', null, 'short:F4-02'], b: ['바로 들어가기', 'skip:pm'] });
// 낮잠 시간 바꾸기: 도트 숫자 + 눈금자 (veri 레퍼런스)
S['C-03'] = { aura: 'still', view: s => { const c = s.completion || {}; return scr({ cls: 'mid-side', vis: G('orb', 'still', 300, { wrap: 'float' }),
  side: copy('차량 복구 상태를<br>확인해 주세요',esc(c.message || '실제 복구와 기록 상태를 확인하고 있어요')) + `<div class="restore">${[['armchair', '등받이'], ['lamp', '조명'], ['fan', '공조']].map(([i, t], n) => `<span class="rs glass" style="--i:${n}">${I(i)}<b>${t}</b><span class="cap">${fact(s,'restore',['seat','light','hvac'][n])}</span></span>`).join('')}</div>`,
  acts: c.restoreRequired ? pri('복구에 동의하고 요청하기',null,'approveRestore') + (c.canDeclineRestore ? sec('그대로 두기',null,'declineRestore','wide') : '') : c.canSave ? pri('기록 저장',null,'saveJourney') : c.canLeave ? pri('홈으로',null,'home') : '' }); } };

/* ───────────── 아침 준비 (F1) ───────────── */
const F1 = x => ({ session: true, aura: 'sunrise', ...x });
// 어제 저녁에 털어놓은 이야기를 아침에 한마디로 꺼낸다 (따로 남기라고 하지 않는다)
S['F1-01'] = F1({ view: s => scr({ cls: 'mid-side', top: bar(null, 1, 6),
  vis: G('arch', 'sunrise', 420, { wrap: 'float', rings: .26 }),
  side: copy(esc(s.context?.morningGreeting || '남겨 둔 어제 이야기가 없어요'), '오늘은 가볍게 시작해 봐요'), acts: pri('다음', 'F1-02') }) });
S['F1-02'] = F1({ view: s => scr({ cls: 'mid-side', top: bar(previous(s, s.short ? 'M-F1' : 'F1-01'), 2, 6),
  vis: `<div class="stones">${[['tense', '긴장돼요', 'tile', 'coral'], ['heavy', '무거워요', 'orb', 'dusk'], ['okay', '괜찮아요', 'cluster', 'flex']].map(([k, t, sh, p]) =>
    `<button class="stone" data-do="feel:${k}">${SHAPE(sh, p, 200)}<b>${t}</b></button>`).join('')}</div>`,
  side: copy('지금 기분은 어때요?', '고른 기분에 맞춰 숨쉬기 방법이 달라져요') }) });
const BREATH = (back,step,of,pal,next,ms) => ({session:true,aura:pal,view:s => scr({cls:'mid-side',top:bar(s.screen === 'F1-03a' ? 'F1-03' : previous(s, back),step,of),vis:breathGraphic(s,pal),side:`<div class="copy center"><div class="phase h1"><span data-live-breath-phase style="animation:none;opacity:1">${breathPhase(s)}</span></div><p class="lead">안내에 맞춰 편안하게 숨을 쉬어요</p></div>`})});
S['F1-03'] = BREATH('F1-02', 3, 6, 'sunrise', 'F1-04', 18000);
// Existing saved sessions may still name this route; render the current breathing view.
S['F1-03a'] = { ...S['F1-03'] };
const TX = s => `<p class="lead tx" id="tx">${esc(s.transcript)}</p>`;
const speechButton = (s, target) => s.speech?.preparing
  ? pri('마이크를 여는 중이에요', null, 'readState').replace('<button ', '<button disabled ')
  : s.speech?.processing
  ? pri('받아쓰는 중이에요', null, 'readState').replace('<button ', '<button disabled ')
  : s.speech?.recording ? pri('다 말했어요', null, 'doneTalk') : pri('말하기', null, `listen:${target}`);
S['F1-04'] = F1({ view: s => scr({ cls: 'mid-side', top: bar(previous(s, 'F1-02'), 4, 6),
  vis: VOICE('amoeba', 'sunrise', s.speech?.recording, TX(s), 400, { wrap: 'breath', liquid: true, rings: 0, dial: 1, keep: 'breath' }),
  side: copy('오늘의 다짐을<br>한마디로 말해 주세요'),
   acts: speechButton(s, 'todo') + lnk('건너뛰기', 'F1-05') }) });
S['F1-04a'] = F1({ view: s => scr({ cls: 'mid-side', top: bar('F1-04', 4, 6),
  vis: `<div class="paper-stack"><div class="paper glass back"></div><div class="paper glass"><p class="cap">오늘의 다짐</p><p class="voice" style="margin-top:20px;font-size:48px;line-height:72px">${esc(s.intention)}</p><div class="hr-line"></div><p class="cap">${esc(s.context?.intentionDateLabel)}</p></div></div>`,
  side: copy('이렇게 적었어요', '오늘 하루 홈에 띄워 둘게요'), acts: pri('좋아요', null, 'afterTodo') + sec('다시 말하기', 'F1-04', null, 'wide') }) });
S['F1-05'] = { session: true, aura: s => s.pick, view: s => scr({ cls: 'mid-side', top: bar(previous(s, 'F1-04a'), 5, 6),
  vis: `<div class="stack-c"><div class="pick-gfx">${G(MOODSHAPE[s.pick], s.pick, 380, { wrap: 'float', rings: .25 })}</div><p class="h2 pick-name" style="margin-top:24px">${MOODS[s.pick][0]}</p><p class="body pick-desc">${MOODS[s.pick][1]}</p></div>`,
  side: copy('오늘은 어떤 분위기로<br>지낼까요?', '고른 색을 홈에 적용해요. 차량 조명은 지원 상태를 확인해요') + `<div class="moods">${Object.entries(MOODS).map(([k, [n]]) =>
    `<button class="mood ${k === s.pick ? 'on' : ''}" data-do="pick:${k}" aria-pressed="${k === s.pick}">${SHAPE(MOODSHAPE[k], k, 76, { blur: 4 })}<span>${n}</span></button>`).join('')}</div>`,
  acts: pri('이 분위기로 정하기', null, 'setMood') }) };
// 고르면 큰 그래픽만 흐려졌다 선명해지며 바뀐다 (화면 전체를 다시 그리지 않는다)
// 분위기를 정하면 그 분위기에 맞는 한마디와 아침의 다짐으로 하루를 연다 → 차를 원래대로(C-03) → 홈
const CHEER = { calm: '긴장되는 건<br>잘하고 싶어서예요', firm: '오늘은 내 생각을<br>분명하게 말해도 돼요', warm: '먼저 건넨 한마디가<br>하루를 가볍게 해요', focus: '하나씩 하다 보면<br>오늘 안에 끝나요', flex: '계획이 바뀌어도<br>괜찮은 하루예요' };
S['F1-06'] = { session: true, aura: s => s.mood, view: s => scr({ cls: 'mid-side', top: bar(previous(s, s.short ? 'F1-04a' : 'F1-05'), 6, 6),
  vis: G(MOODSHAPE[s.mood], s.mood, 440, { wrap: 'float', rings: .26 }),
  side: copy(CHEER[s.mood], s.intention ? `‘${esc(s.intention)}’<br>오늘 잘 해낼 거예요` : '오늘 잘 해낼 거예요'), acts: pri('하루 시작하기', null, 'finish:am') }) };

/* ───────────── 점심 낮잠 (F2) ───────────── */
S['F2-02'] = { session: true, aura: 'dusk', view: s => scr({ cls: 'mid-side', top: bar(previous(s, 'F2-01'), 1, 5),
  vis: `<div class="checks">${[['circle-parking', '주차 상태'], ['lock', '문 상태'], ['app-window', '창문 상태'], ['cloud-rain', '소리 상태']].map(([i, t], n) =>
    `<div class="ck glass" style="--i:${n}">${I(i)}<b>${t}</b><span class="cap">${fact(s,'checks',['parking','doors','windows','sound'][n])}</span></div>`).join('')}
    <div class="ck wide mesh" ${MS('dusk')} style="--i:4"><span class="cap">일어나면</span><b>${esc(s.context?.meetingSummary)}</b></div></div>`,
  side: copy('자기 전에<br>상태를 확인해 주세요', '차량 지원과 실제 확인 상태를 보여 드려요'), acts: pri('자기 시작', null, 'toSleep') }) };
S['F2-03'] = { session: true, aura: 'dusk', view: s => scr({ cls: 'mid-side', top: bar(previous(s, 'F2-02'), 2, 5),
  vis: SEATCTL(s, 14),
  side: copy('등받이 각도를<br>정해 주세요', '낮잠 자세 선호값이에요. 실제 차량 각도는 별도 확인해요'), acts: pri('이 각도로 저장', null, 'saveSeat') }) };
S['F2-04'] = { session: true, dark: true, aura: 'night', view: s => scr({ cls: 'mid-side', top: bar(null, 3, 5),
  vis: G('orb', 'night', 440, { wrap: 'falling', liquid: true }),
  side: copy('낮잠 시간을 확인해 주세요',esc(s.context?.napSummary)) + `<div class="cabin">${[['armchair', '등받이'], ['blinds', '블라인드'], ['lamp', '조명'], ['wind', '공조']].map(([i, t], n) => `<span style="--i:${n}">${I(i)}${t} · ${fact(s,'cabin',i)}</span>`).join('')}</div>` }) };
S['F2-05'] = { session: true, dark: true, aura: 'night', auraCls: 'low', view: s => `<section class="scr sleep" data-do="peek" role="button" tabindex="0" aria-label="남은 시간 보기"><div class="vis">${G('orb', 'night', 80, { wrap: 'breath', blur: 4 })}</div><p class="cap" style="text-align:center">화면을 누르면 남은 시간을 보여 드려요</p></section>` };
S['F2-06'] = { session: true, aura: 'sunrise', auraCls: 'rise', view: s => scr({ cls: 'mid-side', top: bar(null, 4, 5),
  vis: `<div class="gfx waking dawn">${SHAPE('orb', 'night', 440, { liquid: true })}${SHAPE('orb', 'wake', 440, { liquid: true })}</div>`,
  side: copy('일어날 시간이에요', '차량 변경은 실제 확인 상태를 확인해 주세요') + `<div class="cabin">${[['sun', '조명'], ['wind', '공조'], ['armchair', '등받이'], ['app-window', '창문']].map(([i, t], n) => `<span style="--i:${n}">${I(i)}${t} · ${fact(s,'cabin',i)}</span>`).join('')}</div>` }) };
S['F2-07'] = { session: true, aura: 'sunrise', view: s => scr({ cls: 'mid-side', top: bar(null, 5, 5),
  vis: `<div class="wake-card glass"><p class="lab">방금 잔 시간</p><div class="row-c" style="align-items:baseline;gap:12px"><span class="dotnum">${DOTS(minutes(s.context?.napElapsedSeconds) ?? '—', 144)}</span><span class="lead">분</span></div>${RULER(Math.min(1,(s.context?.napElapsedSeconds || 0)/1800))}<div class="row-c cap" style="justify-content:space-between;margin-top:12px"><span>0</span><span>15</span><span>30분</span></div></div>`,
  side: copy('실제로 쉰 시간이에요', `${esc(s.context?.meetingSummary)}<br>필요하면 차량 설정을 직접 확인하고 조정해 주세요`),
  acts: pri('마치기', null, 'finish:noon') + `<button class="btn sec m wide" disabled aria-disabled="true" title="닫힘 기준을 확인할 수 없어 차량에 요청하지 않아요">${I('app-window')}닫힘 기준 미확인</button>` }) };
S['F2-07a'] = { session: true, aura: 'sunrise', auraCls: 'rise', view: s => scr({ cls: 'mid-side', top: bar(null, 5, 5),
  vis: G('orb', 'wake', 420, { wrap: 'beat', liquid: true }), side: copy('일어났어요?', '차량 복구 전에 깨어 있는지 확인해 주세요'), acts: pri('일어났어요', null, 'awake') }) };

/* ───────────── 마음 준비 (F3): 중요한 일정 직전, 할 말을 소리 내 보고 자신감이 생기는 음악으로 집중한다 ───────────── */
const backTop = to => `<header class="top"><button class="iconbtn glass" data-go="${to}" aria-label="뒤로">${I('chevron-left')}</button><span></span></header>`;
// 일정은 시각만 받는다 (무슨 일인지는 묻지 않는다)
S['F3-00'] = { aura: 'coral', auraCls: 'low', view: s => { const value = clock(s.context?.meetingMinute); return scr({ cls: 'mid-side', top: backTop('H-01'),
  vis: `<div class="row-c"><button class="iconbtn glass" data-do="meetAt:-30" aria-label="30분 앞당기기">${I('minus')}</button><span class="meet-time${value ? '' : ' unset'}"><input class="meet-input" id="meetAt" type="time" data-bind="meetingMinute" value="${value}" aria-label="중요한 일정 시각"${value ? '' : ' aria-describedby="meetAtHint"'}><span class="dotnum meet-value" aria-hidden="true">${value}</span><span class="meet-mirror" id="meetAtHint">시각 선택</span></span><button class="iconbtn glass" data-do="meetAt:30" aria-label="30분 미루기">${I('plus')}</button></div>`,
  side: copy('중요한 일정이<br>몇 시에 있어요?', '20분 전에 마음 준비를 알려 드려요'), acts: pri('저장', null, 'saveMeet') }) } };
const F3 = x => ({ session: true, aura: 'coral', ...x });
S['F3-03'] = F3({ view: s => scr({ cls: 'mid-side', top: bar(previous(s, s.round === 1 ? 'F3-01' : 'F3-04'), 1, 4),
  vis: VOICE('tri', 'coral', s.speech?.recording),
  side: s.round === 1 ? copy('꼭 해야 할 말을<br>소리 내 말해 보세요', '중요한 자리에서 꺼낼 첫마디면 충분해요') : copy('한 번 더<br>말해 보세요', '방금보다 천천히 말해 봐요'),
   acts: speechButton(s, 'round') }) });
S['F3-04'] = F3({ view:s => scr({cls:'mid-side',top:bar(previous(s, 'F3-03'),2,4),
vis:`<div class="tiles"><div class="tile glass"><p class="lab">녹음 시간</p><p class="mid">${MIN(speechValue(s,'first','seconds'),'초')}</p>${RULER(Math.min(1,(speechValue(s,'first','seconds') || 0)/90))}</div><div class="tile mesh" ${MS('coral')}><p class="lab">상대 음량 RMS</p><p class="h2" style="margin-top:12px">${esc(speechValue(s,'first','relativeLoudness')?.toFixed(3))}</p>${SEGS(Math.round((speechValue(s,'first','relativeLoudness') || 0)*4),4,'coral')}</div><div class="tile glass hot"><p class="lab">인식된 추임새</p><p class="mid">${MIN(speechValue(s,'first','recognizedFillerCount'),'번')}</p>${MBARS(speechValue(s,'first','recognizedFillerCount') == null ? [] : [Math.min(1,speechValue(s,'first','recognizedFillerCount')/10)],'coral')}</div><div class="tile glass"><p class="lab">인식 토큰 / 녹음 분</p><p class="h2" style="margin-top:12px">${esc(speechValue(s,'first','pace'))}</p>${speechValue(s,'first','pace') == null ? '<p class="cap">인식 자료 없음</p>' : SINE(Math.min(230,speechValue(s,'first','pace')))}</div></div>`,side:copy('실제로 측정한 값이에요','상대 음량은 0~1 RMS이며 보정된 dB가 아니에요'),acts:pri('한 번 더 말하기',null,'again')+sec('자신감 생기는 음악 고르기','F3-06',null,'wide')})});
S['F3-05'] = F3({view:s => scr({cls:'mid-side',top:bar(previous(s, 'F3-03'),2,4),vis:`<div class="cmp glass">${[['녹음 시간','seconds','초'],['인식된 추임새','recognizedFillerCount','번']].map(([l,key,u])=>{const a=speechValue(s,'first',key), b=speechValue(s,'second',key),max=Math.max(1,a || 0,b || 0);return `<div class="cr"><span class="lab">${l}</span><div class="pair"><i class="was" style="width:${a == null ? 0 : a/max*100}%"></i><i class="now" style="width:${b == null ? 0 : b/max*100}%"></i></div><span class="mid" style="font-size:48px">${esc(b)}<sup>${u}</sup></span></div>`;}).join('')}</div>`,side:copy('두 번의 측정 기록이에요',`녹음 시간 ${esc(speechValue(s,'first','seconds'))} → ${esc(speechValue(s,'second','seconds'))}초<br>인식된 추임새 ${esc(speechValue(s,'first','recognizedFillerCount'))} → ${esc(speechValue(s,'second','recognizedFillerCount'))}번`),acts:pri('자신감 생기는 음악 고르기','F3-06')})});
// 음악 고르기: 앨범 대신 추상 형태 커버, 고르면 큰 커버만 흐려졌다 바뀐다
const TUNES = [['피아노', '앱 내 자작 연주곡', 'halo', 'coral'], ['벨', '앱 내 자작 연주곡', 'venn', 'ember'], ['플럭', '앱 내 자작 연주곡', 'cluster', 'firm']];
const COVER = (i, size, cls = '') => { const [, , k, p] = TUNES[i]; return `<span class="cover mesh ${cls}" ${MS(p)}>${SHAPE(k, p, size, { blur: 5 })}</span>`; };
const TUNEBIG = (i,s) => `${COVER(i, 300, 'xl')}<p class="h2">${musicTitle(s,i)}</p>`;
S['F3-06'] = F3({ aura: s => TUNES[s.tune][3], view: s => scr({ cls: 'mid-side', top: bar(previous(s, s.round === 2 ? 'F3-05' : 'F3-04'), 3, 4),
  vis: `<div class="tune-big stack-c">${TUNEBIG(s.tune,s)}</div>`,
  side: copy('자신감이 생기는 음악을<br>골라 주세요', '마음 준비에 맞춘 앱 내 자작 연주곡이에요') + `<div class="list glass tunes">${TUNES.map(([t, sub], i) =>
    `<button class="li tune ${i === s.tune ? 'on' : ''}" data-do="tune:${i}" ${s.music?.choices?.[i]?.available ? '' : 'disabled'} aria-pressed="${i === s.tune}">${COVER(i, 64)}<span class="t"><b>${musicTitle(s,i)}</b><span>${esc(s.music?.choices?.[i]?.subtitle || '음악을 준비하지 못했어요')}</span></span><i class="radio"></i></button>`).join('')}</div>`,
  acts: (s.music?.choices?.[s.tune]?.available ? pri('이 음악 듣기', null, 'playMusic') : pri('음악 사용 불가', null, 'playMusic').replace('<button ', '<button disabled ')) }) });
S['F3-07'] = { session: true, dark: true, aura: s => TUNES[s.tune][3], view: s => { const [t, , k, p] = TUNES[s.tune];
  return scr({ cls: 'mid-side', top: bar('F3-06', 4, 4),
    vis: `<div class="stack-c">${G(k, p, 400, { wrap: s.music?.status === 'PLAYING' ? 'pulse' : '', rings: .3 })}<style>.scr .np::before{animation:none;transform:scaleX(0)}</style><div class="np" data-music-progress role="img" aria-label="${esc(s.music?.statusLabel || '재생 상태 미확인')}" style="background:linear-gradient(to right,var(--ink-2) ${s.music?.durationMs > 0 ? Math.min(100,100*(s.music.positionMs || 0)/s.music.durationMs) : 0}%,var(--line) 0)"><b style="animation:none;transform:translateX(${s.music?.durationMs > 0 ? Math.min(440,440*(s.music.positionMs || 0)/s.music.durationMs) : 0}px)"></b></div></div>`,
    side: copy('음악에 집중해 보세요', esc(s.music?.statusLabel || '재생 상태를 아직 확인하지 못했어요'), `<p class="lab">${esc(s.music?.title)}</p>`)
      + `<div class="cabin">${[['volume-2', '소리'], ['lamp', '조명'], ['armchair', '등받이']].map(([i, x], n) => `<span style="--i:${n}">${I(i)}${x} · ${fact(s,'cabin',i)}</span>`).join('')}</div>`,
    acts: pri('준비됐어요', null, 'finish:meet') + lnk('다른 곡 고르기', 'F3-06') }); } };
S['F3-08'] = {aura:'coral',view:s => scr({cls:'mid-side',top:backTop('H-01'),vis:G('tri','coral',400,{wrap:'float'}),side:copy(s.context?.meetingOccurred ? '등록한 일정은 어땠어요?' : '일정이 끝났다면<br>어땠는지 알려 주세요','직접 고른 의견만 기록해요. 일정이 있었는지는 미리 가정하지 않아요') + `<div class="seg3 big">${['잘 됐어요','그럭저럭','힘들었어요'].map((t,i)=>`<button data-do="meetDone:${i}">${t}</button>`).join('')}</div>`})};
/* ───────────── 하루 마무리 (F4) ───────────── */
S['F4-02'] = { session: true, aura: s => s.mood, view: s => scr({ cls: 'mid-side', top: bar(previous(s, 'F4-01'), 1, 5),
  vis: G('venn', 'ember', 460, { wrap: 'merge' }), side: copy('오늘 하루 고생했어요', '잠깐 편하게 쉬어 가요. 차량 상태는 아래에서 확인해 주세요') + `<div class="cabin">${[['armchair', '등받이'], ['lamp', '조명'], ['flame', '열선']].map(([i, t], n) => `<span style="--i:${n}">${I(i)}${t} · ${fact(s,'cabin',i)}</span>`).join('')}</div>` }) };
S['F4-03'] = { session: true, aura: s => s.dayColor || 'ember', view: s => scr({ cls: 'mid-side', top: bar(previous(s, 'F4-02'), 2, 5),
  vis: actualTrack(s),
  side: copy('오늘 하루는<br>어떤 색이었어요?', '고른 색을 기록해요. 차량 조명은 지원 상태를 확인해요') + `<div class="moods">${Object.entries(MOODS).map(([k, [n]]) => `<button class="mood" data-do="daycolor:${k}">${SHAPE(MOODSHAPE[k], k, 76, { blur: 4 })}<span>${n.replace(/하게|해서/, '')}</span></button>`).join('')}</div>` }) };
S['F4-04'] = { session: true, aura: s => s.dayColor || 'ember', view: s => scr({ cls: 'mid-side', top: bar(previous(s, 'F4-03'), 3, 5),
   vis: VOICE('venn', s.dayColor || 'ember', s.speech?.recording, TX(s)),
   side: copy('오늘 있었던 일을<br>말해 볼까요?', '생각나는 대로 편하게 말해 주세요'), acts: speechButton(s, 'vent') + lnk('건너뛰기', 'F4-05') }) };
S['F4-05'] = { session: true, aura: s => s.dayColor || 'ember', view: s => scr({ cls: 'mid-side', top: bar(previous(s, s.short ? 'F4-03' : 'F4-04'), 4, 5),
  vis: `<div class="week-card glass">${DAYS(s)}<div class="row-c" style="justify-content:space-between;align-items:flex-end;margin-top:32px"><div><p class="lab">이번 주 쌓인 틈새 시간</p><span class="big" style="font-size:96px">${MIN(minutes(s.week?.elapsedSeconds))}</span></div>
    <div class="kpis"><span><b>${esc(s.week?.streakDays)}일</b>활동 완료 연속</span><span><b>${esc(s.week?.activityCount)}번</b>저장된 활동</span></div></div><div style="margin-top:28px">${BARS(s, 7, false)}</div></div>`,
  side: copy('이번 주 기록이에요', '실제로 저장한 활동을 모았어요'),
  acts: pri('다음', 'F4-06') }) };
S['F4-06'] = { session: true, aura: 'ember', view: s => scr({ cls: 'mid-side', top: bar(previous(s, s.done.pm ? null : 'F4-05'), 5, 5),
  vis: G('venn', 'ember', 440, { wrap: 'setting' }), side: copy('하루 마무리', '마치면 실제 차량 복구 상태를 확인해 주세요'),
  acts: pri('집에 들어가기', null, 'finish:pm') }) };

/* ───────────── 3분 숨 고르기 (Q1) ───────────── */
S['Q1-01'] = { session: true, aura: 'rose', view: s => scr({ cls: 'mid-side', top: bar(null, 1, 3),
  vis: `<div class="gfx">${SHAPE('amoeba', 'rose', 360, { liquid: true, cls: 'float' })}<span class="dotnum count over" data-live-dots="context.countdownSeconds" data-dots-height="144">${DOTS(s.context?.countdownSeconds ?? '—', 144)}</span></div>`,
  side: copy('3분만 숨 고를게요', '차량 기능은 지원 여부와 확인 상태에 따라 안내해요') }) };
S['Q1-B'] = BREATH(null, 2, 3, 'rose', 'Q1-02', 18000);
S['Q1-02'] = { session: true, aura: 'rose', view: s => scr({ cls: 'mid-side', top: bar(previous(s, 'Q1-B'), 3, 3), vis: G('cluster', 'rose', 380, { wrap: 'float', rings: .25 }),
  side: copy('지금 기분을<br>골라 주세요', '고른 기분은 오늘 기록에 남아요') + `<div class="chips" style="margin-top:32px">${['답답해요', '억울해요', '지쳤어요', '괜찮아졌어요'].map(t => `<button class="chip glass" data-do="finish:q1" data-value="${t}">${t}</button>`).join('')}</div>` }) };

/* ───────────── 기록 · 설정 ───────────── */
const page = (title, body, back = 'H-01', aura = 'home') => ({ aura, auraCls: 'low', view: s => `<section class="scr page">
  <header class="top" style="justify-content:flex-start"><button class="iconbtn glass" ${backAt(typeof back === 'function' ? back(s) : back)} aria-label="뒤로">${I('chevron-left')}</button><h1 class="h2">${typeof title === 'function' ? esc(title(s)) : title}</h1></header>
  <div class="pbody">${typeof body === 'function' ? body(s) : body}</div></section>` });
const dayRow = (d, sub, k) => `<button class="li" data-go="R-02"><span class="sg">${MINI(k, 64)}</span><span class="t"><b>${d}</b><span>${sub}</span></span><span class="v">${I('chevron-right')}</span></button>`;
S['R-01'] = page('이번 주', s => `<div class="cols"><section class="glass card">${DAYS(s)}<div class="row-c" style="justify-content:space-between;align-items:flex-end;margin-top:32px"><div><p class="lab">이번 주 쌓인 틈새 시간</p><span class="big">${MIN(minutes(s.week?.elapsedSeconds))}</span></div><span class="cap">${esc(s.week?.days?.[0]?.date)} – ${esc(s.week?.days?.[6]?.date)}</span></div><div style="margin-top:32px">${BARS(s,7,false)}</div></section><div class="stack"><div class="kpi-row"><div class="kpi mesh" ${MS('sunrise')}><span class="cap">아침 준비</span><b>${esc(s.week?.morningStreakDays)}일</b><span class="cap">연속으로 했어요</span></div><div class="kpi glass"><span class="cap">이번 주</span><b>${esc(s.week?.activityCount)}번</b><span class="cap">저장된 활동</span></div></div><div class="list glass">${weeklyRows(s)}</div>${s.week?.undatedRecordIds?.length ? '<p class="cap">활동일을 확인할 수 없는 기록은 주간 날짜에 배정하지 않았어요</p>' : ''}</div></div>`);
S['R-02'] = page(s => selectedDay(s)?.date || '날짜 미선택', s => `<div class="cols"><div class="stadium sm"><div class="pass mesh" ${MS(s.mood)}><div class="pass-gfx">${SHAPE(MOODSHAPE[s.mood],s.mood,200,{blur:5})}</div><p class="cap">선택한 날의 다짐</p><p class="voice">${esc(selectedIntention(s))}</p></div></div><div class="glass card">${actualTrack(s)}<div class="list" style="margin-top:24px">${recordRows(s)}</div></div></div>`,s => s.journeyPhase === 'EVENING_DAY_REVIEW' ? previous(s, 'R-01') : 'R-01');

// 설정: Portrait는 목록 → 상세 두 화면, Landscape는 왼쪽 목록 + 오른쪽 상세 한 화면
const ST_ROWS = [['map-pin', '장소', '회사 · 집', 'ST-01'], ['clock-3', '평소 시간', '저장한 시간', 'ST-02'], ['armchair', '자세와 소리', '', 'ST-03'], ['bell', '알림', '저장한 알림 설정', 'ST-04'],
  ['palette', '분위기 색', '', 'ST-05'], ['shield', '기록과 개인정보', '', 'ST-06'], ['car-front', '이 차에서 쓸 수 있는 기능', '', 'ST-07'], ['info', '앱 정보', '', 'ST-08']];
const ST_BODY = {};
const setting = (id, body) => { const row = ST_ROWS.find(r => r[3] === id); if (body) ST_BODY[id] = body;
  S[id] = { group: 'st', aura: 'home', auraCls: 'low', view: s => { const cur = id === 'ST' ? 'ST-01' : id, d = ST_BODY[cur], title = ST_ROWS.find(r => r[3] === cur)[1];
    return `<section class="scr page md ${id === 'ST' ? 'root' : ''}">
    <header class="top" style="justify-content:flex-start"><button class="iconbtn glass p-only" data-go="${id === 'ST' ? 'H-01' : 'ST'}" aria-label="뒤로">${I('chevron-left')}</button><button class="iconbtn glass l-only" data-go="H-01" aria-label="뒤로">${I('chevron-left')}</button>
      <h1 class="h2"><span class="p-only">${id === 'ST' ? '설정' : row[1]}</span><span class="l-only">설정</span></h1></header>
    <div class="pbody"><nav class="md-nav list glass" aria-label="설정">${ST_ROWS.map(([i, t, sub, g]) => `<button class="li ${g === cur ? 'on' : ''}" data-go="${g}"><span class="iconbtn glass">${I(i)}</span><span class="t"><b>${t}</b>${sub ? `<span>${sub}</span>` : ''}</span><span class="v">${I('chevron-right')}</span></button>`).join('')}</nav>
    <div class="md-detail"><h2 class="h2 md-title">${title}</h2>${typeof d === 'function' ? d(s) : d}</div></div></section>`; } }; };
const sw = (t, sub, on = true) => `<div class="li"><span class="t"><b>${t}</b>${sub ? `<span>${sub}</span>` : ''}</span><button class="switch ${on ? 'on' : ''}" data-do="toggle" aria-label="${t}"></button></div>`;
const stRow = (i, t, sub, go) => `<button class="li" ${go ? `data-go="${go}"` : ''}><span class="iconbtn glass">${I(i)}</span><span class="t"><b>${t}</b>${sub ? `<span>${sub}</span>` : ''}</span><span class="v">${I('chevron-right')}</span></button>`;
function vehicleValueText(feature, capability, value) {
  if (!Number.isFinite(value)) return '확인되지 않음';
  if (feature === 'AMBIENT_COLOR') {
    const hex = argbHex(value);
    return hex ? `${hex} · ARGB ${value}` : `해석할 수 없는 값 ${value}`;
  }
  const label = vehicleLabel(capability, value);
  const numeric = String(value);
  return label ? `${esc(label)} (${esc(numeric)})` : esc(numeric);
}
function vehicleStatusText(control, capability, valueLabel) {
  if (control.safetyReason) return esc(control.safetyReason);
  if (control.status === 'checking') return '지원 영역과 범위를 확인하는 중이에요';
  if (control.status === 'unavailable') return '차량이 요청한 영역을 지원한다고 보고하지 않았어요';
  if (control.status === 'failed') return esc(control.error || '지원 여부를 확인하지 못했어요');
  if (control.status === 'blocked') return esc(control.safetyReason || '주차·주행 제한 확인이 필요해요');
  if (control.status === 'read_only')
    return `${valueLabel} · 조회만 지원해요 · ${esc(control.error || capability?.reason || '제어 권한을 확인하지 못했어요')}`;
  if (capability) return `${valueLabel} · ${esc(control.area)} · ${esc(capability.min)}–${esc(capability.max)} · ${esc(capability.step)} 단위`;
  return '확인 전 · 현재 값과 지원 범위를 먼저 확인해 주세요';
}
function vehicleCommandText(feature, capability, command) {
  if (!command) return '';
  const show = value => vehicleValueText(feature, capability, value);
  return ({
    PREVIEW: `현재 ${show(command.previewValue)} → 요청 예정 ${show(command.target)}. 확인을 눌러야 차량에 요청해요.`,
    QUEUED: '현재 값과 주차·주행 제한을 다시 확인하는 중이에요.',
    REQUESTED: command.requestAcknowledged
      ? '요청 응답만 받았어요. 실제 값이 반영됐는지는 아직 확인되지 않았어요.'
      : '차량에 요청했어요. 실제 반영은 아직 확인되지 않았어요.',
    READBACK: Number.isFinite(command.readback) && Number.isFinite(command.target) &&
      Math.abs(command.readback - command.target) < 1e-6
      ? `실제 조회값 ${show(command.readback)}이 요청값과 같아요.`
      : `실제 조회값 ${show(command.readback)}이 요청값 ${show(command.target)}과 달라요.`,
    UNKNOWN: `실제 조회로 적용을 확인하지 못했어요. ${esc(command.reason || '')}`,
    FAILED: `요청 실패 · ${esc(command.reason)}`,
    MANUAL: `제어할 수 없어요 · ${esc(command.reason)}`
  })[command.stage] || '';
}
function vehicleAreaChoices(feature, control, busy) {
  const areas = Array.isArray(control.areas) ? control.areas : [];
  if (!areas.length) return '';
  return `<div class="li vehicle-area-row"><span class="t"><b>영역</b><span>${control.area ? `선택한 영역 ${esc(control.area)}` : '확인할 영역을 골라 주세요'}</span></span><div class="chips">${areas.map(found =>
    `<button class="chip glass ${found.area === control.area ? 'on' : ''}" data-do="inspectVehicle:${feature}" data-area="${esc(found.area)}" aria-pressed="${found.area === control.area}" ${busy ? 'disabled' : ''}>${esc(found.area)}</button>`).join('')}</div></div>`;
}
function vehicleDraftView(feature, capability, current) {
  const key = `vehicle:${feature}`;
  const stored = drafts.has(key) ? drafts.get(key) : null;
  if (feature === 'AMBIENT_COLOR') {
    const raw = stored == null ? (argbHex(current) || '') : String(stored);
    return {raw, value:argbFromHex(raw, current)};
  }
  const value = stored == null ? current : Number(stored);
  return {raw:Number.isFinite(value) ? String(value) : '', value};
}
function vehicleValueInput(feature, capability, draft, valueLabel, enabled) {
  const key = `vehicle:${feature}`;
  const disabled = enabled ? '' : 'disabled';
  if (feature === 'AMBIENT_COLOR') return `<div class="li vehicle-value-row"><label class="t" for="${key}"><b>색상 코드</b><span>현재 색상의 알파 값은 유지하고 RGB만 변경해요</span></label>
    <input class="vehicle-color" id="${key}" type="text" inputmode="text" maxlength="7" pattern="#[0-9A-Fa-f]{6}" value="${esc(draft.raw)}" placeholder="#RRGGBB" data-bind="${key}" aria-label="${valueLabel}" ${disabled}></div>`;
  if (Array.isArray(capability.allowedValues)) return `<div class="li"><span class="t"><b>값 고르기</b><span>차량이 보고한 허용 값만 보여 드려요</span></span><div class="chips">${capability.allowedValues.map(value => {
    const on = Number.isFinite(draft.value) && Math.abs(value - draft.value) < 1e-6;
    return `<button class="chip glass ${on ? 'on' : ''}" data-do="vehicleChoice:${feature}:${value}" aria-pressed="${on}" ${disabled}>${esc(vehicleLabel(capability, value) || String(value))}</button>`; }).join('')}</div></div>`;
  const minimum = Number.isFinite(capability.min) ? capability.min : 0;
  const maximum = Number.isFinite(capability.max) ? capability.max : 100;
  const step = Number.isFinite(capability.step) && capability.step > 0 ? capability.step : 1;
  const value = Number.isFinite(draft.value) ? Math.min(maximum, Math.max(minimum, draft.value)) : minimum;
  return `<div class="li vehicle-value-row"><label class="t" for="${key}"><b>요청할 값</b><span>${esc(valueLabel)} · ${esc(minimum)}–${esc(maximum)} · ${esc(step)} 단위</span></label>
    <input class="ambient-range" id="${key}" type="range" min="${esc(minimum)}" max="${esc(maximum)}" step="${esc(step)}" value="${esc(value)}" data-bind="${key}" aria-label="${valueLabel}" ${disabled}></div>`;
}
function vehicleControlPanel(s, feature, title, valueLabel, note = '') {
  const control = s.hardware?.vehicleControls?.[feature] || {};
  const capability = control.capability;
  const command = control.command;
  const current = control.currentValue;
  const currentObserved = capability?.canRead === true && Number.isFinite(current);
  const busy = vehicleMutationsBlocked();
  const pending = control.status === 'checking';
  const writable = currentObserved && vehicleAccepts(capability, current) &&
    capability.canWrite === true && !control.safetyReason && !!control.area;
  const draft = currentObserved ? vehicleDraftView(feature, capability, current) : {raw:'', value:null};
  const acceptable = writable && !busy && !pending && vehicleAccepts(capability, draft.value);
  const commandText = vehicleCommandText(feature, capability, command);
  return `<div class="list glass vehicle-control" style="margin-top:24px">
    <div class="li"><span class="t"><b>${title}</b><span>${vehicleStatusText(control, capability, valueLabel)}</span></span>
      <button class="btn sec m" data-do="inspectVehicle:${feature}" ${busy ? 'disabled' : ''}>현재 값 확인</button></div>
    ${vehicleAreaChoices(feature, control, busy)}
    ${currentObserved ? `<div class="li"><label class="t" for="vehicle:${feature}"><b>${valueLabel}</b><span>현재 ${vehicleValueText(feature, capability, current)}</span></label>
      ${feature === 'AMBIENT_COLOR' ? `<span class="vehicle-swatch" style="background:${argbHex(current) || 'transparent'}" aria-hidden="true"></span>` : ''}</div>` : ''}
    ${currentObserved ? vehicleValueInput(feature, capability, draft, valueLabel, writable && !busy && !pending) : ''}
    ${commandText ? `<div class="li"><span class="t" role="status"><span>${commandText}</span></span></div>` : ''}
    ${writable ? `<div class="li"><span class="t"><span>요청할 값을 미리 보고, 확인을 누른 뒤에만 차량에 요청해요.</span></span>
      <button class="btn sec m" data-do="previewVehicle:${feature}" ${acceptable ? '' : 'disabled'}>미리 보기</button></div>` : ''}
    ${command?.stage === 'PREVIEW' ? `<div class="li"><span class="t"><b>변경을 확인해 주세요</b><span>${vehicleValueText(feature, capability, command.previewValue)} → ${vehicleValueText(feature, capability, command.target)}</span></span>
      <button class="btn pri m" data-do="confirmVehicle:${esc(command.id)}" ${busy ? 'disabled' : ''}>확인하고 변경</button></div>` : ''}
    ${control.error && !commandText ? `<div class="li"><span class="t" role="status"><span>${esc(control.error)}</span></span></div>` : ''}
    ${note ? `<div class="li"><span class="t"><span>${esc(note)}</span></span></div>` : ''}
  </div>`;
}
setting('ST-01', placeControls);
setting('ST-02', s => scheduleControls(s) + `<div class="list glass"><div class="li"><span class="t"><b>재택하는 날</b><span>이날은 알려 드리지 않아요</span></span></div><div class="chips" style="padding:24px">${['MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY','SUNDAY'].map((d,i)=>`<button class="chip glass ${s.preferences?.schedule?.workFromHomeDays?.includes(d) ? 'on' : ''}" data-do="workFromHomeDay:${d}" aria-pressed="${!!s.preferences?.schedule?.workFromHomeDays?.includes(d)}">${['월','화','수','목','금','토','일'][i]}</button>`).join('')}</div></div>`);
setting('ST-03', s => `<div class="list glass"><div class="li"><span class="t"><b>쉴 때 등받이</b></span><span class="v"><input class="body" type="number" min="106" max="146" data-bind="backrest:restDegrees" value="${s.preferences?.backrest?.restDegrees == null ? '' : esc(s.preferences.backrest.restDegrees)}" aria-label="쉴 때 등받이 선호값" style="width:120px;background:transparent;border:0;color:inherit;font:inherit">°</span></div><div class="li"><span class="t"><b>낮잠 등받이</b></span><span class="v"><input class="body" type="number" min="106" max="160" data-bind="backrest:napDegrees" value="${s.preferences?.backrest?.napDegrees == null ? '' : esc(s.preferences.backrest.napDegrees)}" aria-label="낮잠 등받이 선호값" style="width:120px;background:transparent;border:0;color:inherit;font:inherit">°</span></div><div class="li"><span class="t"><b>쉴 때 소리</b></span><span class="v"><select data-bind="restSound" aria-label="쉴 때 소리" style="background:transparent;border:0;color:inherit;font:inherit">${s.preferences?.restSound ? '' : '<option value="">소리 미설정</option>'}${SOUNDS.map(([t],i)=>`<option value="${SOUND_KEYS[i]}" ${s.preferences?.restSound === SOUND_KEYS[i] ? 'selected' : ''}>${t}</option>`).join('')}</select></span></div><div class="li"><span class="t"><b>깨울 때 음악</b></span><span class="v">${esc(s.music?.wakeTitle)}</span></div><div class="li"><span class="t"><b>마음 준비 음악</b></span><span class="v">${esc(s.music?.preparationTitle)}</span></div>${preferenceSwitch(s,'음성 안내','VOICE_GUIDE',s.preferences?.voiceGuide)}</div><p class="cap">앱의 자세 선호값이며 실제 SDK 각도가 아니에요 · ${fact(s,'capabilities','seat')}</p>${lnk('자세 선호값 저장',null,'saveBackrest:settings')}`);
setting('ST-04', s => `<div class="list glass">${[['am','MORNING','morning','회사에 도착하면'],['noon','NAP','nap','점심 먹고 차에 돌아오면'],['meet','PREPARATION','preparation','등록한 일정 전에'],['pm','EVENING','evening','집에 도착하면']].map(([k,flag,key,sub])=>preferenceSwitch(s,NAME[k],flag,s.preferences?.notifications?.[key],sub,`<span class="sg">${MINI(k,64)}</span>`)).join('')}${preferenceSwitch(s,'도착 전에 미리 묻기','ASK_BEFORE_HOME_ARRIVAL',s.preferences?.notifications?.askBeforeHomeArrival,'위치와 알림 권한이 필요해요')}</div>`);
setting('ST-05', s => `<div class="moodgrid">${Object.entries(MOODS).map(([k,[n,d]])=>`<button class="mg glass" data-do="atmosphere:${k.toUpperCase()}" aria-pressed="${s.preferences?.atmosphere === k.toUpperCase()}">${SHAPE(MOODSHAPE[k],k,140,{blur:5})}<b>${n}</b><span class="cap">${d}</span></button>`).join('')}</div>`);
setting('ST-06', s => `<div class="list glass"><div class="li"><span class="t"><b>기록 보관 기간</b><span>확인된 시각을 기준으로 보관해요</span></span><span class="v">1년</span></div>${preferenceSwitch(s,'저녁에 말한 내용 다음 날까지 지우기','DELETE_EVENING_STORY_AFTER_NEXT_MORNING',s.preferences?.privacy?.deleteEveningStoryAfterNextMorning,'다음 날 아침에 사용하면 지우고, 남은 이야기도 다음 날이 끝나면 지워요')}<button class="li" data-do="wipeAsk"><span class="t"><b style="color:var(--pleos-informative-negative)">모든 기록 지우기</b></span></button></div>`);
function backrestControlPanel(s) {
  const control = s.hardware?.backrestControl || {};
  const areas = Array.isArray(control.areas) ? control.areas : [];
  const blocked = vehicleMutationsBlocked() || !!control.blockedReason;
  const acknowledgements = {NONE:'없음', PENDING:'응답 대기', ACKNOWLEDGED:'응답 수신', FAILED:'실패'};
  return `<div class="list glass">
    <div class="li"><span class="t"><b>전동 등받이 · 에뮬레이터 전용</b><span>버튼을 누르면 최대 0.5초 이동 후 정지를 요청해요. 저장한 선호 각도로 이동하는 기능은 아니에요.</span></span>
      <button class="btn sec m" data-do="inspectBackrest" ${vehicleMutationsBlocked() ? 'disabled' : ''}>현재 값 확인</button></div>
    ${control.blockedReason ? `<div class="li"><span class="t"><span>${esc(control.blockedReason)}</span></span></div>` : ''}
    ${areas.map(area => `<div class="li"><span class="t"><b>${esc(area.id)}</b><span>현재 원시 위치 ${Number.isFinite(area.raw) ? esc(area.raw) : '미확인'} · 범위 ${esc(area.minRaw)}–${esc(area.maxRaw)}</span></span>
      <div class="chips"><button class="btn sec m" data-do="pulseBackrest:FORWARD" data-area="${esc(area.id)}" ${blocked || !area.writable ? 'disabled' : ''}>앞으로 0.5초</button>
      <button class="btn sec m" data-do="pulseBackrest:BACKWARD" data-area="${esc(area.id)}" ${blocked || !area.writable ? 'disabled' : ''}>뒤로 0.5초</button></div></div>`).join('')}
    <div class="li"><span class="t" role="status"><span>이동 요청: ${acknowledgements[control.requestAck] || '없음'} · 정지 요청: ${acknowledgements[control.stopAck] || '없음'}</span>
      <span>관찰된 원시 위치 변화: ${Number.isFinite(control.rawPositionDelta) ? esc(control.rawPositionDelta) : '미확인'}. 응답 수신만으로 이동·정지를 확인할 수는 없어요.</span></span>
      <button class="btn sec m" data-do="stopBackrest">정지 요청</button></div>
  </div>`;
}
setting('ST-07', s => `${backrestControlPanel(s)}${vehicleControlPanel(s,'AMBIENT_BRIGHTNESS','앰비언트 밝기','SDK 밝기 값')}${vehicleControlPanel(s,'AMBIENT_COLOR','앰비언트 색','차량 색상 값(ARGB)','입력한 #RRGGBB를 부호 있는 ARGB 정수로 바꿔 요청해요. 실제 반영은 다시 조회로 확인해요.')}${vehicleControlPanel(s,'AMBIENT_SWITCH','앰비언트 라이트 켜기·끄기','차량 허용 값')}${vehicleControlPanel(s,'WINDOW_POSITION','창문 위치 (SDK 원시값)','SDK 원시 위치 값','닫힘에 대응하는 값은 확인되지 않아, 이 값으로 닫기를 뜻하지 않아요.')}${vehicleControlPanel(s,'SEAT_VENTILATION','통풍 시트','차량 허용 단계')}${vehicleControlPanel(s,'SMART_GLASS_STATE','스마트글라스 상태','차량 허용 값')}${vehicleControlPanel(s,'SMART_GLASS_LEVEL','스마트글라스 단계','차량 허용 값')}`);
setting('ST-08', s => `<div class="stack-c" style="padding:48px 0"><span class="appicon xl">${MARK(158)}</span><p class="h2" style="margin-top:24px">틈틈</p><p class="cap">버전 ${esc(s.appVersion)}</p></div><div class="list glass">${stRow('rotate-ccw','처음 안내 다시 보기','','ON-01')}</div>`);
setting('ST');


const OVERLAYS = {
  end: s => confirmModal('지금 끝낼까요?','끝내면 실제 차량 복구 상태를 확인해요',['계속하기','resume'],['끝내기','stop']),
  door: s => confirmModal('차량 안전 상태를 확인해 주세요',esc(s.context?.interruptionReason || '안전 확인 전에는 이어서 진행할 수 없어요'),['끝내기','stop'],['상태 다시 확인','readState']),
  recover: s => confirmModal('지난 휴식이 중간에 끝났어요','이전 세션을 자동으로 재개하거나 복구하지 않아요',['닫기','resume'],['복구 상태 확인','recover']),
  wipeAsk: s => confirmModal('모든 기록을 지울까요?','지운 기록은 되돌릴 수 없어요',['취소','resume'],['지우기','wipe']),
  restore: s => confirmModal('차량을 원래대로 돌릴까요?','지원과 현재 상태를 확인한 뒤 복구를 요청해요',['그대로 두기','declineRestore'],['복구 동의','approveRestore']),
  sleeptime: s => `<div class="modal-bg"><div class="modal glass"><h2 class="h2">얼마나 잘까요?</h2><p class="body" style="margin-top:8px">${esc(s.context?.napSummary)}</p><div class="time-pick"><span class="dotnum">${DOTS(s.sleep ?? '—', 144)}</span><span class="lead">분</span></div>${RULER((s.sleep || 0)/25)}<div class="seg3">${[10,15,20].map(n=>`<button class="${s.sleep === n ? 'on' : ''}" data-do="setsleep:${n}"><b>${n}분</b></button>`).join('')}</div><div class="acts">${pri('시간 확인',null,'closeRefresh')}</div></div></div>`,
  peek: s => `<div class="modal-bg"><div class="modal glass stack-c"><p class="lab">남은 시간</p><span class="dotnum">${DOTS(minutes(s.context?.napRemainingSeconds) ?? '—', 144)}<span class="lead">분</span></span><div class="acts" style="width:100%">${pri('지금 일어나기',null,'wakeNow')}${sec('계속 자기',null,'resume','wide')}</div></div></div>`
};

