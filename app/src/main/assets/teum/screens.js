// Production original-design templates. Native facts are read-only; parent owns all dispatch and effects.
const SOUND_KEYS = ['RAIN','WAVES','FOREST','CAMPFIRE','SINGING_BOWL','WIND','WHITE_NOISE','NONE'];
const JOURNEY_SLOT = {MORNING:'am',NAP:'noon',PREPARATION:'meet',EVENING:'pm',QUICK_BREATHING:'q1'};
function esc(value) { return value == null || value === '' ? '—' : String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }

function minutes(seconds) { return seconds == null ? null : Math.floor(seconds / 60); }

function clock(minute) { return minute == null ? '' : String(Math.floor(minute / 60)).padStart(2, '0') + ':' + String(minute % 60).padStart(2, '0'); }

function fact(s, group, key) { const f=s.hardware?.[group]?.[key]; return esc(f?.label || ({confirmed:'확인됨',unsupported:'이 차량에서 지원하지 않아요',pending:'확인 중',failed:'요청 실패',unknown:'확인되지 않았어요'}[f?.status] || '확인되지 않았어요')); }

function factMark(s,group,key) { return s.hardware?.[group]?.[key]?.status === 'confirmed' ? I('check') : I('minus'); }

function placeControls(s) { return `<div class="places" style="margin-top:0">${[['company','work','회사'],['home','home','집']].map(([key,k,label])=>`<div class="place"><div class="place-art">${PLACEART(k)}${s.preferences?.[key]?.coordinates ? `<span class="pin">${I('check')}</span>` : ''}</div><label class="cap" for="place-${key}">${label}</label><input id="place-${key}" class="body" style="width:100%;background:transparent;border:0;color:inherit;font:inherit" data-bind="place:${key}" maxlength="200" value="${s.preferences?.[key]?.label ? esc(s.preferences[key].label) : ''}" placeholder="장소 이름 미설정"><div class="row-c">${lnk('이름 저장',null,`savePlace:${key}`)}${lnk('현재 위치 등록',null,`captureLocation:${key}`)}</div></div>`).join('')}</div><p class="cap" data-live="location.description" style="margin-top:16px">${esc(s.location?.description || '위치 수신 상태를 확인해요')}</p>`; }

function timeControls(s) { return `<div class="timepair">${[['workStartMinute','출근'],['lunchReturnMinute','점심 복귀']].map(([key,label])=>{const value=clock(s.preferences?.schedule?.[key]);return `<label class="tcard glass"><span class="cap">${label}</span><span class="timefield"><input class="timeinput" type="time" data-bind="schedule:${key}" value="${value}" aria-label="${label}"><span class="dotnum sm time24" aria-hidden="true">${value}</span></span></label>`;}).join('')}</div>`; }
const syncTimeMirrors = () => document.querySelectorAll('.timefield').forEach(field => {
  const input = field.querySelector('input'), mirror = field.querySelector('.time24');
  if (input && mirror && mirror.textContent !== input.value) mirror.textContent = input.value;
});
const syncMeetHint = () => document.querySelectorAll('.meet-time').forEach(field => {
  const input = field.querySelector('input'), value = input?.value || '', shown = field.querySelector('.meet-value');
  field.classList.toggle('unset', !value);
  if (shown && shown.textContent !== value) shown.textContent = value;
  if (input && value) input.removeAttribute('aria-describedby');
  else if (input) input.setAttribute('aria-describedby', 'meetAtHint');
});
document.addEventListener('input', event => { if (event.target.matches?.('.timeinput')) syncTimeMirrors(); if (event.target.id === 'meetAt') syncMeetHint(); });
document.addEventListener('change', event => { if (event.target.id === 'meetAt') syncMeetHint(); });
new MutationObserver(() => { syncTimeMirrors(); syncMeetHint(); }).observe(document.getElementById('scr'), { childList: true, subtree: true });

function scheduleControls(s) { return `${timeControls(s)}<div class="list glass" style="margin-top:16px"><div class="li">${liIc('footprints')}<span class="t"><b>주차장에서 자리까지</b><span>걷는 시간을 빼고 계산해요</span></span><span class="v"><button class="iconbtn" data-do="walk:-1" aria-label="1분 줄이기">${I('minus')}</button><span class="mid" style="font-size:44px">${esc(s.preferences?.schedule?.walkingReserveMinutes)}분</span><button class="iconbtn" data-do="walk:1" aria-label="1분 늘리기">${I('plus')}</button></span></div></div>${lnk('시간 저장',null,'saveSchedule')}`; }

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
const I = n => `<i data-lucide="${n}"></i>`;
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
const SEAT = (deg, ctl = false) => `<svg class="seat" viewBox="60 50 520 470" width="480" height="434" aria-hidden="true">
  ${ctl ? '<path class="arc" d="M380.4 67.9A391.3 391.3 0 0 1 701.8 219.6"/>' : ''}<path class="sil" d="${SEATPATH(deg)}"/>
  ${ctl ? `<g class="rot" style="transform:rotate(${deg - 118}deg)"><circle class="knob" cx="462" cy="74" r="13"/></g>` : ''}</svg>`;
// Original seat outline; rest and nap preferences are independent native fields.
const SEATCTL = (s, off = 0) => { const key = off ? 'napDegrees' : 'restDegrees', native = s.preferences?.backrest?.[key], deg = native == null ? null : Number(drafts.get(`backrest:${key}`) ?? native); return `<div class="stack-c seatctl" data-seat-kind="${off ? 'nap' : 'rest'}">${deg == null ? SEAT(118) : SEAT(deg, true)}
  <div class="row-c"><button class="iconbtn glass" data-do="seat:-4" aria-label="덜 눕히기">${I('minus')}</button><span class="mid" id="seatDeg">${esc(deg)}°</span><button class="iconbtn glass" data-do="seat:4" aria-label="더 눕히기">${I('plus')}</button></div>
  <p class="cap hint">${I('hand')}저장할 자세 선호값 · 차량 각도는 확인되지 않았어요</p></div>`; };
// Rest 106..146, nap 106..160. Values are designer preferences, never SDK units.
document.addEventListener('pointerdown', e => {
  const svg = e.target.closest('.seatctl .seat');
  if (!svg?.querySelector('.knob') || e.button !== 0 || !e.isPrimary || svg.classList.contains('drag')) return;
  const pt = ev => new DOMPoint(ev.clientX, ev.clientY).matrixTransform(svg.getScreenCTM().inverse());
  if (pt(e).y > 380) return;
  e.preventDefault();
  const box = svg.closest('.seatctl');
  const stop = () => {
    removed.disconnect();
    svg.onpointermove = svg.onpointerup = svg.onpointercancel = svg.onlostpointercapture = null;
    svg.classList.remove('drag');
    window.removeEventListener('pagehide', stop);
    if (svg.hasPointerCapture(e.pointerId)) svg.releasePointerCapture(e.pointerId);
  };
  const move = ev => {
    if (ev.pointerId !== e.pointerId) return;
    if (!svg.isConnected) { stop(); return; }
    const p = pt(ev), deg = Math.round(118 + Math.atan2(p.x - SP[0], SP[1] - p.y) * 180 / Math.PI - 10.26);
    ACT.seat(deg - Number.parseFloat(box.querySelector('#seatDeg').textContent));
  };
  const end = ev => { if (ev.pointerId === e.pointerId) stop(); };
  const removed = new MutationObserver(() => { if (!svg.isConnected) stop(); });
  removed.observe(document, { childList: true, subtree: true });
  window.addEventListener('pagehide', stop, { once: true });
  svg.onpointermove = move;
  svg.onpointerup = svg.onpointercancel = svg.onlostpointercapture = end;
  svg.setPointerCapture(e.pointerId);
  svg.classList.add('drag');
  move(e);
});
// 작은 데이터 그림 (veri·Health 레퍼런스): 눈금자 · 세그먼트 · 미니 막대 · 사인파 · 주간 막대 · 하루 트랙
const RULER = pos => `<div class="ruler">${Array.from({ length: 31 }, (_, i) => `<i class="${i % 5 ? '' : 'l'}"></i>`).join('')}<b style="left:${pos * 100}%"></b></div>`;
const SEGS = (on, n = 3, p = 'coral') => `<div class="segs">${Array.from({ length: n }, (_, i) => `<i ${i < on ? `style="background:linear-gradient(90deg,${PAL[p][2]},${PAL[p][0]})"` : ''}></i>`).join('')}</div>`;
const MBARS = (vs, p = 'coral') => `<div class="mbars">${vs.map(v => `<i style="height:${v * 100}%;background:linear-gradient(${PAL[p][2]},${PAL[p][0]})"></i>`).join('')}</div>`;
const SINE = x => `<svg class="sine" viewBox="0 0 240 60" preserveAspectRatio="none"><path d="M0 30C20 6 40 6 60 30S100 54 120 30 160 6 180 30 220 54 240 30" fill="none" stroke="currentColor" stroke-width="2.5"/><path d="M${x} 6l7 10h-14z" fill="var(--lime)"/></svg>`;
const BARS = (s, n = 7, lab = true) => { const days = s.week?.days || [], max = Math.max(1, ...days.map(d => d.elapsedSeconds || 0)); return `<div class="bars">${Array.from({length:n}, (_, i) => {const d=days[i], v=(d?.elapsedSeconds || 0)/max, p=PAL.home; return `<div class="bc" style="--n:${i}"><span class="bt">${v ? `<i style="height:${v*100}%;background:linear-gradient(${p[2]} 0%, ${p[0]} 55%, ${p[1]})"></i>` : ''}</span>${lab ? `<span class="cap">${['월','화','수','목','금','토','일'][i]}</span>` : ''}</div>`;}).join('')}</div>`; };
const DAYS = s => `<div class="days">${Array.from({length:7}, (_,i) => {const d=s.week?.days?.[i]; return `<button class="dy ${d?.date === s.day?.date ? 'on' : ''} ${d ? '' : 'off'}" ${d ? `data-do="day:${esc(d.date)}"` : 'disabled'}>${['월','화','수','목','금','토','일'][i]}</button>`;}).join('')}</div>`;
const DAYTRACK = (rows, now = rows.length - 1) => `<div class="daytrack"><span class="dt-line"></span>${rows.map(([k, n, t, live], i) => `<div class="dt">${MINI(k, 110)}<b>${n}</b><span class="cap"${live ? ` data-live="${esc(live)}"` : ''}>${t}</span>${i === now ? '<i class="now"></i>' : ''}</div>`).join('')}</div>`;

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
  s => DAYTRACK([['am', NAME.am, '시간 미설정'], ['noon', NAME.noon, '시간 미설정'], ['meet', NAME.meet, '시간 미설정'], ['pm', NAME.pm, '도착 확인 필요']], -1),
  s => `<div class="cabin-card">${SEAT(126)}<div class="chips">${[['armchair', '등받이'], ['lamp', '조명'], ['wind', '공조']].map(([i, t]) => `<span class="chip glass">${I(i)}${t} · ${fact(s,'cabin',i)}</span>`).join('')}</div></div>`,
  s => `<div class="week-card glass"><p class="lab">이번 주 쌓인 틈새 시간</p><span class="big">${MIN(minutes(s.week?.elapsedSeconds))}</span><div style="margin-top:28px">${BARS(s, 7)}</div></div>`];
S['ON-02'] = { aura: s => SLIDE[s.slide][2], view: s => scr({ cls: 'mid-side',
  top: bar('do:slideBack', s.slide + 1, 8, false).replace(/<span class="ph"><\/span><\/header>$/, '<button class="link" data-go="ON-03">건너뛰기</button></header>'),
  vis: `<div class="slide-vis">${SLIDEVIS[s.slide](s)}</div>`, side: `<div class="slide-copy">${copy(SLIDE[s.slide][0], SLIDE[s.slide][1])}</div>`, acts: pri('다음', null, 'slide') }) };
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
S['H-01'] = { aura: s => s.done.am ? s.mood : 'home', auraCls: 'low',
  view: s => { const h = hero(s), nk = nextKey(s), p = s.done.am ? s.mood : SIGPAL[h.k];
    return `<section class="scr home">
      <header class="top home-top"><div><h1 class="h1">${esc(s.context?.greeting || '안녕하세요')}</h1><p class="lead">${h.ctx}</p></div>
        <span class="row-c" style="gap:12px"><button class="iconbtn glass" data-go="R-01" aria-label="기록">${I('chart-no-axes-column')}</button><button class="iconbtn glass" data-go="ST" aria-label="설정">${I('settings')}</button></span></header>
      <div class="hgrid">
        <section class="hero mesh" ${MS(p)}>
          <div class="hero-gfx">${SHAPE(SIG[h.k], p, 400, { cls: 'float' })}</div>${s.done.am ? `<p class="mood-line">${SHAPE(MOODSHAPE[s.mood], s.mood, 44, { blur: 3 })}<b>${MOODS[s.mood][0]}</b><span>${esc(s.intention)}</span></p>` : ''}
          <div class="hero-row"><h2 class="h2">${h.name}</h2><span class="big">${MIN(h.big)}</span></div>
          <p class="body">${h.sub}</p>
          ${h.btn ? `<div class="acts" style="margin-top:28px">${h.now ? pri(h.btn[0], null, `start:${h.btn[1]}`) : pri(h.btn[0], h.btn[1], h.btn[2])}</div>` : ''}
        </section>
        <div class="hr">
          <div class="sec-h"><h2 class="h2">오늘</h2><button class="iconbtn glass" data-go="F3-00" aria-label="중요한 일정 추가">${I('plus')}</button></div>
          <div class="day">${SLOTS.map(([k, t, g]) => { const st = s.done[k] ? 'done' : s.skipped[k] ? 'skip' : k === nk ? 'next' : '';
            return `<button class="slot glass ${st}" ${st === 'skip' ? '' : `data-do="${s.done[k] ? 'start:' + slotGo(s, k, g) : 'begin:' + k}"`}>
            <span class="sg">${MINI(k, 88, st === '' || st === 'skip')}</span>${s.done[k] ? `<span class="badge ${s.justDone === k ? 'stamp' : ''}">${I('check')}</span>` : ''}<b>${NAME[k]}</b><span>${st === 'skip' ? '건너뜀' : esc(k === 'meet' ? s.meetAt : s.context?.slotTimes?.[k])}</span></button>`; }).join('')}</div>
          <section class="glass card only-l"><div class="row-c" style="justify-content:space-between"><p class="lab">이번 주 쌓인 틈새 시간</p><span class="mid" style="font-size:48px">${MIN(minutes(s.week?.elapsedSeconds))}</span></div><div style="margin-top:24px">${BARS(s, 5)}</div></section>
        </div>
      </div></section>`; },
};
S['H-02'] = { aura: 'still', auraCls: 'low', view: s => `<section class="scr drive"><div class="side center" style="flex:1;justify-content:center">
  <h1 class="drive-t">정차하면<br>이어서 할 수 있어요</h1><div class="dots big-dots">${SLOTS.map(([k]) => `<i class="${s.done[k] ? 'on' : ''}"></i>`).join('')}</div></div></section>` };
S['V-01'] = { ...S['H-02'], view: s => S['H-02'].view(s) + `<div class="caption glass">${I('audio-lines')}<span>집에 도착하기 전에 쉬어 갈까요?</span>${sec('쉬어 가기', null, 'begin:pm')}${lnk('나중에', null, 'resume')}</div>` };

/* ───────────── 공통: 차가 먼저 하는 제안 · 끝내기 · 원래대로 ───────────── */
const moment = o => ({ aura: o.pal, view: s => scr({ cls: 'mid-side', vis: G(o.shape, o.pal, 460, { wrap: 'float', rings: .26 }),
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
const BREATH = (back,step,of,pal,next,ms) => ({session:true,aura:pal,view:s => scr({cls:'mid-side',top:bar(previous(s, back),step,of),vis:breathGraphic(s,pal),side:`<div class="copy center"><div class="phase h1"><span data-live-breath-phase style="animation:none;opacity:1">${breathPhase(s)}</span></div><p class="lead">안내에 맞춰 편안하게 숨을 쉬어요</p></div>`,acts:next === 'F1-04' ? lnk('눈 감고 하기','F1-03a') : ''})});
S['F1-03'] = BREATH('F1-02', 3, 6, 'sunrise', 'F1-04', 18000);
S['F1-03a'] = { session: true, dark: true, aura: 'sunrise', auraCls: 'low', view: s => `<section class="scr eyes" data-do="eyesBack" role="button" tabindex="0" aria-label="호흡 화면으로 돌아가기"><div class="vis">${breathGraphic(s,'sunrise',72)}</div><p class="cap" style="text-align:center">화면을 누르면 돌아가요</p></section>` };
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
  vis: `<div class="stack-c"><div class="pick-gfx">${G(MOODSHAPE[s.pick], s.pick, 380, { wrap: 'float', rings: .25 })}</div><p class="h2 pick-name" style="margin-top:20px">${MOODS[s.pick][0]}</p><p class="body pick-desc">${MOODS[s.pick][1]}</p></div>`,
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
  vis: `<div class="wake-card glass"><p class="lab">방금 잔 시간</p><div class="row-c" style="align-items:baseline;gap:12px"><span class="dotnum">${esc(minutes(s.context?.napElapsedSeconds))}</span><span class="lead">분</span></div>${RULER(Math.min(1,(s.context?.napElapsedSeconds || 0)/1800))}<div class="row-c cap" style="justify-content:space-between;margin-top:12px"><span>0</span><span>15</span><span>30분</span></div></div>`,
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
  vis: `<div class="gfx">${SHAPE('amoeba', 'rose', 360, { liquid: true, cls: 'float' })}<span class="dotnum count over" data-live="context.countdownSeconds">${esc(s.context?.countdownSeconds)}</span></div>`,
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
function vehicleControlPanel(s, feature, title, valueLabel, note = '') {
  const control = s.hardware?.vehicleControls?.[feature] || {};
  const capability = control.capability;
  const command = control.command;
  const current = control.currentValue;
  const areaLabel = control.area === 'ROW_1_LEFT' ? '운전석 영역' : '지원 영역';
  const currentObserved = capability?.canRead === true && Number.isFinite(current);
  const key = `vehicle:${feature}`;
  const draft = drafts.has(key) ? Number(drafts.get(key)) : current;
  const draftInRange = Number.isFinite(draft) && capability &&
    draft >= capability.min && draft <= capability.max &&
    Math.abs((draft - capability.min) / capability.step -
      Math.round((draft - capability.min) / capability.step)) < 0.001;
  const target = draftInRange ? draft : current;
  const writable = capability?.canRead === true && capability.canWrite === true &&
    control.safetyReason == null && Number.isFinite(target);
  const commandText = !command ? '' : ({
    PREVIEW: `현재 ${esc(command.initial)} → 요청 ${esc(command.target)}. 확인을 눌러야 차량에 요청해요.`,
    QUEUED: '현재 값과 주차·주행 제한을 다시 확인하고 있어요.',
    REQUESTED: command.requestAcknowledged
      ? '요청 응답을 받았어요. 실제 값을 다시 읽고 있어요.'
      : '차량에 요청했어요. 실제 적용 여부는 아직 확인되지 않았어요.',
    READBACK: `실제 조회값 ${esc(command.readback)}이 요청값과 일치해요.`,
    UNKNOWN: command.readback != null && command.readback !== command.target
      ? `요청 응답은 받았지만 실제 조회값 ${esc(command.readback)}이 요청값 ${esc(command.target)}과 달라요.`
      : `실제 조회로 적용을 확인하지 못했어요. ${esc(command.reason)}`,
    FAILED: `요청 실패 · ${esc(command.reason)}`,
    MANUAL: `제어할 수 없어요 · ${esc(command.reason)}`
  })[command.stage] || '';
  return `<div class="list glass" style="margin-top:24px">
    <div class="li"><span class="t"><b>${title}</b><span>${control.safetyReason ? esc(control.safetyReason) :
      capability ? `${valueLabel} · ${areaLabel} · ${esc(capability.min)}–${esc(capability.max)} · ${esc(capability.step)} 단위` :
      esc(control.error || '차량 지원과 현재 값을 확인해 주세요')}</span></span>
      <button class="btn sec m" data-do="inspectVehicle:${feature}">현재 값 확인</button></div>
    ${currentObserved ? `<div class="li"><label class="t" for="${key}"><b>${valueLabel}</b><span>현재 ${esc(current)}</span></label>
      <input class="ambient-range" id="${key}" type="range" min="${esc(capability.min)}" max="${esc(capability.max)}" step="${esc(capability.step)}" value="${esc(target ?? capability.min)}" data-bind="${key}" aria-label="${valueLabel}" ${writable ? '' : 'disabled'}></div>` : ''}
    ${commandText ? `<div class="li"><span class="t" role="status"><span>${commandText}</span></span></div>` : ''}
    ${capability?.canRead && capability.canWrite && !control.safetyReason ? `<div class="li"><span class="t"><span>선택한 값을 미리 보고, 확인을 누른 뒤에만 변경을 요청해요.</span></span>
      <button class="btn sec m" data-do="previewVehicle:${feature}" ${draftInRange && writable ? '' : 'disabled'}>미리 보기</button></div>` : ''}
    ${command?.stage === 'PREVIEW' ? `<div class="li"><span class="t"><b>변경을 확인해 주세요</b><span>${esc(command.initial)} → ${esc(command.target)}</span></span>
      <button class="btn pri m" data-do="confirmVehicle:${esc(command.id)}">확인하고 변경</button></div>` : ''}
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
setting('ST-07', s => `<div class="list glass">${[['전동 등받이','seat'],['앰비언트 라이트','light'],['창문 위치','window'],['통풍 시트','ventilation'],['스마트글라스','glass'],['블라인드','blinds']].map(([t,k])=>`<div class="li"><span class="t"><b>${t}</b></span><span class="v">${fact(s,'capabilities',k)}</span></div>`).join('')}</div>${vehicleControlPanel(s,'AMBIENT_BRIGHTNESS','앰비언트 밝기','SDK 밝기 값')}${vehicleControlPanel(s,'WINDOW_POSITION','창문 위치 (SDK 원시값)','SDK 원시 위치 값','닫힘에 대응하는 값은 확인되지 않아, 이 값으로 닫기를 뜻하지 않아요.')}`);
setting('ST-08', s => `<div class="stack-c" style="padding:48px 0"><span class="appicon xl">${MARK(158)}</span><p class="h2" style="margin-top:24px">틈틈</p><p class="cap">버전 ${esc(s.appVersion)}</p></div><div class="list glass">${stRow('rotate-ccw','처음 안내 다시 보기','','ON-01')}</div>`);
setting('ST');


const OVERLAYS = {
  end: s => confirmModal('지금 끝낼까요?','끝내면 실제 차량 복구 상태를 확인해요',['계속하기','resume'],['끝내기','stop']),
  door: s => confirmModal('차량 안전 상태를 확인해 주세요',esc(s.context?.interruptionReason || '안전 확인 전에는 이어서 진행할 수 없어요'),['끝내기','stop'],['상태 다시 확인','readState']),
  recover: s => confirmModal('지난 휴식이 중간에 끝났어요','이전 세션을 자동으로 재개하거나 복구하지 않아요',['닫기','resume'],['복구 상태 확인','recover']),
  wipeAsk: s => confirmModal('모든 기록을 지울까요?','지운 기록은 되돌릴 수 없어요',['취소','resume'],['지우기','wipe']),
  restore: s => confirmModal('차량을 원래대로 돌릴까요?','지원과 현재 상태를 확인한 뒤 복구를 요청해요',['그대로 두기','declineRestore'],['복구 동의','approveRestore']),
  sleeptime: s => `<div class="modal-bg"><div class="modal glass"><h2 class="h2">얼마나 잘까요?</h2><p class="body" style="margin-top:8px">${esc(s.context?.napSummary)}</p><div class="time-pick"><span class="dotnum">${esc(s.sleep)}</span><span class="lead">분</span></div>${RULER((s.sleep || 0)/25)}<div class="seg3">${[10,15,20].map(n=>`<button class="${s.sleep === n ? 'on' : ''}" data-do="setsleep:${n}"><b>${n}분</b></button>`).join('')}</div><div class="acts">${pri('시간 확인',null,'closeRefresh')}</div></div></div>`,
  peek: s => `<div class="modal-bg"><div class="modal glass stack-c"><p class="lab">남은 시간</p><span class="dotnum">${esc(minutes(s.context?.napRemainingSeconds))}<span class="lead">분</span></span><div class="acts" style="width:100%">${pri('지금 일어나기',null,'wakeNow')}${sec('계속 자기',null,'resume','wide')}</div></div></div>`
};

