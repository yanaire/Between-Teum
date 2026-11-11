// 틈틈 프로토타입 화면 정의. 화면 하나 = 마크업 하나, Portrait/Landscape는 CSS container query가 재배치한다.
// 그래픽은 shapes.js의 SHAPE(형태, 팔레트, 크기). 문구는 ux_writing_guidelines.md 기준 (해요체, 결과가 보이는 버튼, 사실 위주).
const I = n => `<i data-lucide="${n}"></i>`;
const at = (go, d) => `${go ? `data-go="${go}" ` : ''}${d ? `data-do="${d}"` : ''}`;
const pri = (t, go, d) => `<button class="btn pri wide" ${at(go, d)}>${t}<span class="go">${I('arrow-right')}</span></button>`;
const sec = (t, go, d, c = '') => `<button class="btn sec m ${c}" ${at(go, d)}>${t}</button>`;
const lnk = (t, go, d) => `<button class="link" ${at(go, d)}>${t}</button>`;
const two = (a, b) => `<div class="two">${a}${b}</div>`;
const closeBtn = `<button class="iconbtn glass" data-do="end" aria-label="끝내기">${I('x')}</button>`;
// 상단 바: 이전 단계 · 진행 트랙(선 위의 라임 점) · 닫기
const bar = (back, step, of, x = true) => `<header class="top">
  ${back ? `<button class="iconbtn glass" ${back.startsWith('do:') ? `data-do="${back.slice(3)}"` : `data-go="${back}"`} aria-label="이전">${I('chevron-left')}</button>` : '<span class="ph"></span>'}
  ${of ? `<div class="track" role="img" aria-label="${of}단계 중 ${step}단계">${Array.from({ length: of }, (_, i) => `<i class="${i < step - 1 ? 'done' : ''}" style="--x:${i / (of - 1)}"></i>`).join('')}<b class="dot" data-p="${(step - 1) / (of - 1)}" style="--p:${(step - 1) / (of - 1)}"></b></div>` : '<span class="grow"></span>'}
  ${x ? closeBtn : '<span class="ph"></span>'}</header>`;
const copy = (h, p = '', pre = '') => `<div class="copy">${pre}<h1 class="h1">${h}</h1>${p ? `<p class="lead">${p}</p>` : ''}</div>`;
const scr = ({ cls = '', top = '', vis = '', side = '', acts = '', extra = '' }) =>
  `<section class="scr ${cls}">${top}<div class="vis">${vis}</div><div class="side">${side}${acts ? `<div class="acts">${acts}</div>` : ''}</div>${extra}</section>`;
const mic = t => `<p class="cap hint">${I('mic')}‘${t}’라고 말해도 돼요</p>`;
const MS = p => { const c = PAL[p] || PAL.home; return `style="--m1:${c[0]};--m2:${c[1]};--m3:${c[2]}"`; };
const MIN = (n, u = '분') => `${n}<sup>${u}</sup>`;

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
// −/+ 버튼과 끌기가 같은 값을 바꾼다. off: 화면에 보이는 각도 = state.seat + off (낮잠은 14° 더 눕힌다)
const SEATCTL = (s, off = 0) => `<div class="stack-c seatctl" data-off="${off}">${SEAT(s.seat + off, true)}
  <div class="row-c"><button class="iconbtn glass" data-do="seat:-4" aria-label="덜 눕히기">${I('minus')}</button><span class="mid" id="seatDeg">${s.seat + off}°</span><button class="iconbtn glass" data-do="seat:4" aria-label="더 눕히기">${I('plus')}</button></div>
  <p class="cap hint">${I('hand')}등받이를 끌어서 맞춰도 돼요</p></div>`;
// 기준 각도(state.seat)는 106~146°(106°면 등받이 뒷선이 수직), 낮잠은 +14°라서 화면에 보이는 각도는 늘 106~160° 안에 있다
const setSeat = d => { const box = document.querySelector('.seatctl'); if (!box) return; const off = +box.dataset.off;
  state.seat = Math.min(146, Math.max(106, Math.round(d) - off)); d = state.seat + off; box.querySelector('.rot').style.transform = `rotate(${d - 118}deg)`; box.querySelector('.sil').style.d = `path("${SEATPATH(d)}")`; box.querySelector('#seatDeg').textContent = d + '°'; };
// 등받이 끌기: 쿠션 위쪽을 누르면 시작, 피벗에서 손가락까지의 각도가 곧 등받이 각도
document.addEventListener('pointerdown', e => { const svg = e.target.closest('.seatctl .seat'); if (!svg) return;
  const pt = ev => new DOMPoint(ev.clientX, ev.clientY).matrixTransform(svg.getScreenCTM().inverse());
  if (pt(e).y > 380) return; e.preventDefault();
  const move = ev => { const p = pt(ev); setSeat(118 + Math.atan2(p.x - SP[0], SP[1] - p.y) * 180 / Math.PI - 10.26); };
  svg.setPointerCapture(e.pointerId); svg.classList.add('drag'); move(e);
  svg.onpointermove = move; svg.onpointerup = svg.onpointercancel = () => { svg.onpointermove = svg.onpointerup = svg.onpointercancel = null; svg.classList.remove('drag'); }; });
// 작은 데이터 그림 (veri·Health 레퍼런스): 눈금자 · 세그먼트 · 미니 막대 · 사인파 · 주간 막대 · 하루 트랙
const RULER = pos => `<div class="ruler">${Array.from({ length: 31 }, (_, i) => `<i class="${i % 5 ? '' : 'l'}"></i>`).join('')}<b style="left:${pos * 100}%"></b></div>`;
const SEGS = (on, n = 3, p = 'coral') => `<div class="segs">${Array.from({ length: n }, (_, i) => `<i ${i < on ? `style="background:linear-gradient(90deg,${PAL[p][2]},${PAL[p][0]})"` : ''}></i>`).join('')}</div>`;
const MBARS = (vs, p = 'coral') => `<div class="mbars">${vs.map(v => `<i style="height:${v * 100}%;background:linear-gradient(${PAL[p][2]},${PAL[p][0]})"></i>`).join('')}</div>`;
const SINE = x => `<svg class="sine" viewBox="0 0 240 60" preserveAspectRatio="none"><path d="M0 30C20 6 40 6 60 30S100 54 120 30 160 6 180 30 220 54 240 30" fill="none" stroke="currentColor" stroke-width="2.5"/><path d="M${x} 6l7 10h-14z" fill="var(--lime)"/></svg>`;
const WEEKV = [['월', .75, 'calm'], ['화', .5, 'warm'], ['수', 1, 'flex'], ['목', .75, 'firm'], ['금', 0], ['토', 0], ['일', 0]];
const BARS = (n = 7, lab = true) => `<div class="bars">${WEEKV.slice(0, n).map(([d, v, p], i) => `<div class="bc" style="--n:${i}"><span class="bt">${v ? `<i style="height:${v * 100}%;background:linear-gradient(${PAL[p][2]} 0%, ${PAL[p][0]} 55%, ${PAL[p][1]})"></i>` : ''}</span>${lab ? `<span class="cap">${d}</span>` : ''}</div>`).join('')}</div>`;
const DAYS = (today = 3) => `<div class="days">${['월', '화', '수', '목', '금', '토', '일'].map((d, i) => `<button class="dy ${i === today ? 'on' : ''} ${i > today ? 'off' : ''}" ${i <= today ? 'data-go="R-02"' : ''}>${d}</button>`).join('')}</div>`;
const DAYTRACK = (rows, now = rows.length - 1) => `<div class="daytrack"><span class="dt-line"></span>${rows.map(([k, n, t], i) => `<div class="dt">${MINI(k, 110)}<b>${n}</b><span class="cap">${t}</span>${i === now ? '<i class="now"></i>' : ''}</div>`).join('')}</div>`;

const MOODS = { calm: ['차분하게', '긴장되는 회의가 있는 날'], firm: ['단호하게', '거절하거나 협상해야 하는 날'], warm: ['다정하게', '사람을 많이 만나는 날'], focus: ['집중해서', '혼자 끝낼 일이 많은 날'], flex: ['유연하게', '일정이 자주 바뀌는 날'] };
const GREET = { '08:40': '좋은 아침이에요', '12:35': '점심 맛있게 먹었어요?', '15:40': '오후도 힘내요', '19:05': '오늘도 고생 많았어요', '19:10': '오늘도 고생 많았어요' };
const NAME = { am: '아침 준비', noon: '점심 낮잠', meet: '마음 준비', pm: '하루 마무리' };
const HM = t => { const [h, m] = t.split(':').map(Number); return `${h % 12 || 12}시${m ? ` ${m}분` : ''}`; };
// 목록·입력이 주인공인 화면: Portrait는 위→아래, Landscape는 왼쪽 글·버튼 + 오른쪽 내용
const solo = (top, h, p, body, acts) => scr({ cls: 'solo', top, side: copy(h, p) + `<div class="sbody">${body}</div>`, acts });

function hero(s) {
  const d = k => s.done[k] || s.skipped[k];
  // ctx는 인사 아래 한 줄로 들어간다 (카드 위에 따로 라벨을 달지 않는다)
  const now = (k, ctx, big, sub, go) => ({ k, ctx, name: NAME[k], big, sub, btn: ['시작', go], now: true });
  if (s.slot === 'am' && !d('am')) return now('am', '출근까지 20분 남았어요', 7, '숨을 고르고 오늘의 다짐을 정해요', 'F1-01');
  if (s.slot === 'noon' && !d('noon')) return now('noon', '1시 복귀까지 25분 남았어요', 20, '1시 복귀에 맞춰 깨워 드려요', 'F2-02');
  if (s.slot === 'meet' && !d('meet')) return now('meet', `${HM(s.meetAt)} 일정까지 20분 남았어요`, 10, '할 말을 말해 보고 힘이 나는 음악을 들어요', 'F3-03');
  if (s.slot === 'pm' && !d('pm')) return now('pm', '집에 도착했어요', 10, '집에 들어가기 전에 잠깐 쉬어 가요', 'F4-02');
  const ctx = `오늘 쉬는 시간이 ${SLOTS.filter(([k]) => !d(k)).length}번 남았어요`;
  const nx = (k, big, sub) => ({ k, ctx, name: NAME[k], big, sub, btn: ['시작', null, `begin:${k}`] });
  if (!d('noon')) return nx('noon', 20, '점심 먹고 차에 오면 알려 드려요');
  if (!d('meet')) return nx('meet', 10, `${HM(s.meetAt)} 일정 20분 전에 알려 드려요`);
  if (!d('pm')) return nx('pm', 10, '집에 도착하면 알려 드려요');
  return { k: 'pm', ctx: '내일 아침에 다시 만나요', name: '네 번 모두 쉬었어요', big: 47, sub: '오늘 쉰 시간이에요', btn: ['오늘 기록 보기', 'R-02'] };
}
const SLOTS = [['am', '08:40', 'F1-01'], ['noon', '12:35', 'F2-02'], ['meet', '16:00', 'F3-03'], ['pm', '집 도착', 'F4-02']];
const nextKey = s => SLOTS.find(([k]) => !s.done[k] && !s.skipped[k])?.[0];
const slotGo = (s, k, g) => s.done[k] ? (k === 'meet' ? 'F3-08' : 'R-02') : g;

const S = {};
const ACT = {};
ACT.noop = () => {};
ACT.start = to => { state.short = false; go(to); };
// 홈에서 다음 차례를 직접 시작: 시계와 시간대를 그 차례로 옮기고 첫 화면으로 간다
ACT.begin = k => { Object.assign(state, { time: k === 'pm' ? '19:10' : SCENE[k].time, slot: k, short: false, round: 1 }); renderShell(); renderCtrl(); go(SLOTS.find(([x]) => x === k)[2]); };
ACT.short = to => { state.short = true; go(to); };
ACT.skip = k => { state.skipped[k] = true; go('H-01'); later(500, () => toast(`${MINI(k, 64)}오늘 ${NAME[k]}는 건너뛰었어요<small>내일 다시 알려 드릴게요</small>`)); };

/* ───────────── 온보딩 ───────────── */
// 첫 화면: 하루 네 번의 형태가 살짝 겹쳐 하나가 된다(= 틈틈). 시작하면 흩어지고, 다음 장에서 하루 트랙 위에 따로 놓인다
// [시간대, 크기, x, y, 흩어질 방향 x, y]
const MERGE = [['noon', 310, 0, 200, -90, 60], ['am', 300, 60, 0, -80, -60], ['pm', 310, 230, 180, 90, 60], ['meet', 300, 240, -10, 80, -70]];
// 앱 아이콘(GNB·앱 정보)도 같은 배치를 줄여 쓴다
const MARK = w => { const k = w / 560; return `<span class="mk" style="width:${w}px;height:${Math.round(500 * k)}px">${MERGE.map(([s, z, x, y]) =>
  `<span style="left:${Math.round(x * k)}px;top:${Math.round(y * k)}px">${SHAPE(SIG[s], SIGPAL[s], Math.round(z * k))}</span>`).join('')}</span>`; };
S['ON-01'] = { aura: 'home', view: () => scr({ cls: 'mid-side',
  vis: `<div class="merge4">${MERGE.map(([k, z, x, y, dx, dy], i) => `<div class="gfx ${i % 2 ? 'float2' : 'float'}" style="left:${x}px;top:${y}px;--i:${i};--dx:${dx}px;--dy:${dy}px">${SHAPE(SIG[k], SIGPAL[k], z)}</div>`).join('')}</div>`,
  side: copy('틈틈', '주차한 차 안에서 5~30분,<br>쉬어 가는 앱이에요'), acts: pri('시작하기', 'ON-02') }) };
const SLIDE = [['쉴 수 있을 때<br>차가 먼저 알려 줘요', '출근 전, 점심 뒤, 중요한 일정 전, 퇴근 후에 남는 시간을 계산해요', 'sunrise'],
  ['쉬는 동안 시트와 조명을<br>맞춰 줘요', '끝나면 원래대로 돌려놓아요', 'dusk'],
  ['쉰 시간은<br>차곡차곡 쌓여요', '일주일 동안 모은 시간을 볼 수 있어요', 'flex']];
const SLIDEVIS = [
  () => DAYTRACK([['am', NAME.am, '08:40'], ['noon', NAME.noon, '12:35'], ['meet', NAME.meet, '16:00'], ['pm', NAME.pm, '19:10']], -1),
  () => `<div class="cabin-card">${SEAT(126)}<div class="chips">${[['armchair', '등받이'], ['lamp', '조명'], ['wind', '공조']].map(([i, t]) => `<span class="chip glass">${I(i)}${t}</span>`).join('')}</div></div>`,
  () => `<div class="week-card glass"><p class="lab">이번 주 쌓인 틈새 시간</p><span class="big">${MIN(74)}</span><div style="margin-top:28px">${BARS(7)}</div></div>`];
S['ON-02'] = { aura: s => SLIDE[s.slide][2], view: s => scr({ cls: 'mid-side',
  top: bar('do:slideBack', s.slide + 1, 8, false).replace(/<span class="ph"><\/span><\/header>$/, '<button class="link" data-go="ON-03">건너뛰기</button></header>'),
  vis: `<div class="slide-vis">${SLIDEVIS[s.slide]()}</div>`, side: `<div class="slide-copy">${copy(SLIDE[s.slide][0], SLIDE[s.slide][1])}</div>`, acts: pri('다음', null, 'slide') }) };
const slideTo = dir => { const app = document.querySelector('#app'); app.dataset.aura = SLIDE[state.slide][2];
  const v = document.querySelector('.slide-vis'), c = document.querySelector('.slide-copy'), t = document.querySelector('.track'); if (!v) return refresh();
  v.innerHTML = SLIDEVIS[state.slide](); c.innerHTML = copy(SLIDE[state.slide][0], SLIDE[state.slide][1]); icons();
  if (t) t.outerHTML = bar('x', state.slide + 1, 8).match(/<div class="track"[\s\S]*?<\/div>/)[0];
  [v, c].forEach((el, i) => el.animate([{ opacity: 0, transform: `translateX(${dir * 48}px)` }, { opacity: 1, transform: 'none' }], { duration: 420, delay: i * 40, easing: 'cubic-bezier(0.23, 1, 0.32, 1)', fill: 'backwards' })); };
ACT.slide = () => { if (state.slide >= 2) { go('ON-03'); return; } state.slide++; slideTo(1); };
ACT.slideBack = () => { if (state.slide === 0) return go('ON-01'); state.slide--; slideTo(-1); };
const liIc = i => `<span class="iconbtn glass">${I(i)}</span>`;
S['ON-03'] = { aura: 'home', auraCls: 'low', view: () => solo(bar('ON-02', 4, 8, false), '이 권한이 필요해요', '', `<div class="list glass">
    <div class="li">${liIc('car-front')}<span class="t"><b>차량 제어</b><span>시트·조명·공조를 맞출 때 써요</span></span></div>
    <div class="li">${liIc('mic')}<span class="t"><b>마이크</b><span>다짐과 하루 이야기를 말로 남길 때 써요</span></span></div>
    <div class="li">${liIc('map-pin')}<span class="t"><b>위치</b><span>회사와 집에 도착한 걸 알 때 써요</span></span></div></div>`,
  pri('허용하기', 'ON-04')) };
const PLACE = (k, addr, pin) => `<div class="place"><div class="place-art">${PLACEART(k)}${pin ? `<span class="pin">${I('check')}</span>` : ''}</div><span class="cap">${k === 'home' ? '집' : '회사'}</span><b>${addr}</b></div>`;
S['ON-04'] = { aura: 'home', auraCls: 'low', view: s => solo(bar('ON-03', 5, 8, false), '회사와 집을 알려 주세요', '도착하면 쉴 수 있는 시간을 알려 드려요', `<div class="places">
    ${PLACE('work', '강남구 테헤란로', true)}
    ${s.homeSet ? PLACE('home', '성동구 왕십리로', true)
      : `<button class="place empty" data-do="setHome"><div class="place-art">${I('locate-fixed')}</div><span class="cap">집</span><b>지금 위치로 등록</b></button>`}</div>`,
  pri('다음', 'ON-05') + lnk('나중에 하기', 'ON-05')) };
ACT.setHome = () => { state.homeSet = true; refresh(); };
S['ON-05'] = { aura: 'home', auraCls: 'low', view: s => solo(bar('ON-04', 6, 8, false), '평소 시간을 알려 주세요', '', `<div class="timepair">
    <div class="tcard glass"><span class="cap">출근</span><span class="dotnum sm">09:00</span></div><div class="tcard glass"><span class="cap">점심 복귀</span><span class="dotnum sm">13:00</span></div></div>
    <div class="list glass" style="margin-top:16px"><div class="li">${liIc('footprints')}<span class="t"><b>주차장에서 자리까지</b><span>걷는 시간을 빼고 계산해요</span></span><span class="v"><button class="iconbtn" data-do="walk:-1" aria-label="1분 줄이기">${I('minus')}</button><span class="mid" style="font-size:44px">${s.walk}분</span><button class="iconbtn" data-do="walk:1" aria-label="1분 늘리기">${I('plus')}</button></span></div></div>
    <p class="body" style="margin-top:24px">08:40에 도착하면 아침 준비를 알려 드려요</p>`,
  pri('다음', 'ON-06')) };
ACT.walk = n => { state.walk = Math.min(10, Math.max(1, state.walk + +n)); refresh(); };
S['ON-06'] = { aura: 'home', auraCls: 'low', view: s => scr({ cls: 'mid-side', top: bar('ON-05', 7, 8, false),
  vis: SEATCTL(s, 0),
  side: copy('쉴 때 편한<br>등받이 각도를 정해 주세요', '정차 중일 때만 등받이가 움직여요'), acts: pri('이 각도로 저장', 'ON-07') + lnk('기본값 쓰기', 'ON-07') }) };
ACT.seat = n => setSeat(state.seat + +document.querySelector('.seatctl').dataset.off + +n);
// 명상·휴식 때 많이 듣는 소리. 그래픽은 소리마다 다른 추상 형태
const SOUNDS = [['빗소리', 'rose', 'amoeba'], ['파도', 'calm', 'bands'], ['숲', 'flex', 'cluster'], ['모닥불', 'firm', 'drop'],
  ['싱잉볼', 'warm', 'ring'], ['바람', 'mist', 'gust'], ['백색소음', 'focus', 'orb'], ['소리 없음', 'still', 'orb']];
S['ON-07'] = { aura: 'home', auraCls: 'low', view: () => solo(bar('ON-06', 8, 8, false), '쉴 때 들을 소리를 골라 주세요', '', `<div class="sounds">${SOUNDS.map(([t, p, k], i) =>
    `<button class="sound glass ${i === 0 ? 'on' : ''}" data-do="pickOne">${SHAPE(k, p, 84, { blur: 5 })}<b>${t}</b>${I(i === 0 ? 'pause' : 'play')}</button>`).join('')}</div>
    <div class="list glass" style="margin-top:24px"><div class="li">${liIc('volume-2')}<span class="t"><b>음성 안내</b><span>알림과 안내를 소리로도 들려줘요</span></span><button class="switch on" data-do="toggle" aria-label="음성 안내"></button></div></div>`,
  pri('다음', 'ON-08')) };
ACT.pickOne = (_, el) => el.parentElement.querySelectorAll(':scope > button').forEach(c => c.classList.toggle('on', c === el));
ACT.toggle = (_, el) => el.classList.toggle('on');
S['ON-08'] = { aura: 'sunrise', view: () => scr({ cls: 'mid-side', vis: G('amoeba', 'sunrise', 440, { wrap: 'float', rings: .3, liquid: true }),
  side: copy('설정을 마쳤어요', '내일 아침 회사에 도착하면 알려 드릴게요'),
  acts: pri('3분 숨 고르기 해 보기', 'Q1-01') + sec('홈으로', 'H-01', null, 'wide') }) };

/* ───────────── 홈 ───────────── */
// 아침에 고른 분위기는 홈까지 이어진다: 배경 빛, 큰 카드의 색, 인사 아래 분위기와 다짐
S['H-01'] = { aura: s => s.done.am ? s.mood : 'home', auraCls: 'low',
  view: s => { const h = hero(s), nk = nextKey(s), p = s.done.am ? s.mood : SIGPAL[h.k];
    return `<section class="scr home">
      <header class="top home-top"><div><h1 class="h1">${GREET[s.time] || '안녕하세요'}</h1><p class="lead">${h.ctx}</p></div>
        <span class="row-c" style="gap:12px"><button class="iconbtn glass" data-go="R-01" aria-label="기록">${I('chart-no-axes-column')}</button><button class="iconbtn glass" data-go="ST" aria-label="설정">${I('settings')}</button></span></header>
      <div class="hgrid">
        <section class="hero mesh" ${MS(p)}>
          <div class="hero-gfx">${SHAPE(SIG[h.k], p, 400, { cls: 'float' })}</div>${s.done.am ? `<p class="mood-line">${SHAPE(MOODSHAPE[s.mood], s.mood, 44, { blur: 3 })}<b>${MOODS[s.mood][0]}</b><span>${s.intention}</span></p>` : ''}
          <div class="hero-row"><h2 class="h2">${h.name}</h2><span class="big">${MIN(h.big)}</span></div>
          <p class="body">${h.sub}</p>
          ${h.btn ? `<div class="acts" style="margin-top:28px">${h.now ? pri(h.btn[0], null, `start:${h.btn[1]}`) : pri(h.btn[0], h.btn[1], h.btn[2])}</div>` : ''}
        </section>
        <div class="hr">
          <div class="sec-h"><h2 class="h2">오늘</h2><button class="iconbtn glass" data-go="F3-00" aria-label="중요한 일정 추가">${I('plus')}</button></div>
          <div class="day">${SLOTS.map(([k, t, g]) => { const st = s.done[k] ? 'done' : s.skipped[k] ? 'skip' : k === nk ? 'next' : '';
            return `<button class="slot glass ${st}" ${st === 'skip' ? '' : `data-do="${s.done[k] ? 'start:' + slotGo(s, k, g) : 'begin:' + k}"`}>
            <span class="sg">${MINI(k, 88, st === '' || st === 'skip')}</span>${s.done[k] ? `<span class="badge ${s.justDone === k ? 'stamp' : ''}">${I('check')}</span>` : ''}<b>${NAME[k]}</b><span>${st === 'skip' ? '건너뜀' : k === 'meet' ? s.meetAt : t}</span></button>`; }).join('')}</div>
          <section class="glass card only-l"><div class="row-c" style="justify-content:space-between"><p class="lab">이번 주 쌓인 틈새 시간</p><span class="mid" style="font-size:48px">${MIN(74)}</span></div><div style="margin-top:24px">${BARS(5)}</div></section>
        </div>
      </div></section>`; },
  after: s => { if (s.stamp) { const [n, sub, k] = s.stamp; s.stamp = null; toast(`${SHAPE(SIG[k] || 'amoeba', SIGPAL[k] || 'rose', 72, { blur: 4 })}${n}${sub ? `<small>${sub}</small>` : ''}`); } later(900, () => { s.justDone = null; }); } };
S['H-02'] = { aura: 'still', auraCls: 'low', view: s => `<section class="scr drive"><div class="side center" style="flex:1;justify-content:center">
  <h1 class="drive-t">정차하면<br>이어서 할 수 있어요</h1><div class="dots big-dots">${SLOTS.map(([k]) => `<i class="${s.done[k] ? 'on' : ''}"></i>`).join('')}</div></div></section>` };
S['V-01'] = { ...S['H-02'], after: () => overlay(`<div class="caption glass">${I('audio-lines')}<span>집에 도착하면 10분 쉬었다 들어갈까요?<br><span class="cap">‘응’ 또는 ‘아니’라고 말해 주세요</span></span></div>`),
  auto: [5200, s => { s.gear = 'P'; s.time = '19:10'; renderShell(); renderCtrl(); return 'F4-01'; }] };

/* ───────────── 공통: 차가 먼저 하는 제안 · 끝내기 · 원래대로 ───────────── */
const moment = o => ({ aura: o.pal, view: s => scr({ cls: 'mid-side', vis: G(o.shape, o.pal, 460, { wrap: 'float', rings: .26 }),
  side: `<div class="copy"><h1 class="h1">${o.name}</h1><div class="big" style="margin:4px 0 8px">${MIN(typeof o.min === 'function' ? o.min(s) : o.min)}</div><p class="lead">${typeof o.sub === 'function' ? o.sub(s) : o.sub}</p></div>`,
  acts: pri(o.pri[0], null, o.pri[1]) + two(sec(o.a[0], o.a[1], o.a[2]), sec(o.b[0], null, o.b[1])) + mic(o.pri[0]) }) });
S['M-F1'] = moment({ pal: 'sunrise', shape: 'arch', name: '아침 준비', min: 7, sub: '출근까지 20분 남았어요', pri: ['시작', 'start:F1-01'], a: ['3분만', null, 'short:F1-02'], b: ['오늘은 건너뛰기', 'skip:am'] });
S['F2-01'] = moment({ pal: 'dusk', shape: 'pill', name: '점심 낮잠', min: s => s.sleep, sub: '1시 복귀에 맞춰 깨워 드려요', pri: ['지금 자기', 'start:F2-02'], a: ['시간 바꾸기', null, 'sleeptime'], b: ['오늘은 건너뛰기', 'skip:noon'] });
S['F3-01'] = moment({ pal: 'coral', shape: 'tri', name: '마음 준비', min: 10, sub: s => `${HM(s.meetAt)} 일정 전에 할 말을 말해 보고 힘이 나는 음악을 들어요`, pri: ['시작', 'start:F3-03'], a: ['3분 숨 고르기', 'Q1-01'], b: ['오늘은 건너뛰기', 'skip:meet'] });
S['F4-01'] = moment({ pal: 'ember', shape: 'venn', name: '하루 마무리', min: 10, sub: '집에 들어가기 전에 잠깐 쉬어 가요', pri: ['10분 쉬기', 'start:F4-02'], a: ['5분만', null, 'short:F4-02'], b: ['바로 들어가기', 'skip:pm'] });
// 낮잠 시간 바꾸기: 도트 숫자 + 눈금자 (veri 레퍼런스)
ACT.sleeptime = () => overlay(`<div class="modal-bg"><div class="modal glass"><h2 class="h2">얼마나 잘까요?</h2><p class="body" style="margin-top:8px">1시 복귀에 맞춰 깨워 드려요</p>
  <div class="time-pick"><span class="dotnum">${state.sleep}</span><span class="lead">분</span></div>${RULER(state.sleep / 25)}
  <div class="seg3">${[10, 15, 20].map(n => `<button class="${state.sleep === n ? 'on' : ''}" data-do="setsleep:${n}"><b>${n}분</b></button>`).join('')}</div>
  <div class="acts">${pri(`${state.sleep}분 자기`, null, 'closeRefresh')}</div></div></div>`, true);
ACT.setsleep = n => { state.sleep = +n; ACT.sleeptime(); };
ACT.closeRefresh = () => { closeOverlay(true); later(200, refresh); };
const confirmModal = (h, p, a, b) => overlay(`<div class="modal-bg"><div class="modal glass"><h2 class="h2">${h}</h2><p class="body" style="margin-top:12px">${p}</p>
  <div class="acts">${two(sec(a[0], null, a[1]), `<button class="btn m pri-m" data-do="${b[1]}">${b[0]}</button>`)}</div></div></div>`);
ACT.end = () => confirmModal('지금 끝낼까요?', '차를 원래대로 돌려놓을게요', ['계속하기', 'resume'], ['끝내기', 'stop']);
ACT.resume = () => closeOverlay(true);
ACT.stop = () => { state.stamp = null; go('C-03'); };
ACT.finish = k => { if (k !== 'q1') state.done[k] = true; state.justDone = k; state.stamp = { am: ['아침 준비를 마쳤어요', '4일째 이어 가고 있어요', 'am'], noon: ['점심 낮잠을 마쳤어요', '이번 주 2번째예요', 'noon'], meet: ['마음 준비를 마쳤어요', '일정이 끝나면 어땠는지 물어볼게요', 'meet'], pm: ['하루 마무리를 마쳤어요', '내일 아침에 다시 만나요', 'pm'], q1: ['3분 숨 고르기를 마쳤어요', '', 'q1'] }[k]; go('C-03'); };
ACT.door = () => confirmModal('문이 열려서 잠시 멈췄어요', '30초 안에 닫으면 이어서 해요', ['끝내기', 'stop'], ['이어서 하기', 'resume']);
ACT.recover = () => { state.interrupted = false; confirmModal('지난 휴식이 중간에 끝났어요', '등받이가 젖혀져 있어요. 원래대로 돌릴까요?', ['그대로 두기', 'resume'], ['원래대로 돌리기', 'stop']); };
S['C-03'] = { aura: 'still', view: () => scr({ cls: 'mid-side', vis: G('orb', 'still', 300, { wrap: 'float' }),
  side: copy('차를 원래대로<br>돌려놓는 중이에요') + `<div class="restore">${[['armchair', '등받이'], ['lamp', '조명'], ['fan', '공조']].map(([i, t], n) => `<span class="rs glass" style="--i:${n}">${I(i)}<b>${t}</b><span class="ok">${I('check')}</span></span>`).join('')}</div>` }),
  auto: [3400, 'H-01'] };

/* ───────────── 아침 준비 (F1) ───────────── */
const F1 = x => ({ session: true, aura: 'sunrise', ...x });
// 어제 저녁에 털어놓은 이야기를 아침에 한마디로 꺼낸다 (따로 남기라고 하지 않는다)
S['F1-01'] = F1({ view: () => scr({ cls: 'mid-side', top: bar(null, 1, 6),
  vis: G('arch', 'sunrise', 420, { wrap: 'float', rings: .26 }),
  side: copy('어제는 좀<br>서운한 하루였죠', '오늘은 가볍게 시작해 봐요'), acts: pri('다음', 'F1-02') }), auto: [7000, 'F1-02'] });
S['F1-02'] = F1({ view: s => scr({ cls: 'mid-side', top: bar(s.short ? 'M-F1' : 'F1-01', 2, 6),
  vis: `<div class="stones">${[['tense', '긴장돼요', 'tile', 'coral'], ['heavy', '무거워요', 'orb', 'dusk'], ['okay', '괜찮아요', 'cluster', 'flex']].map(([k, t, sh, p]) =>
    `<button class="stone" data-do="feel:${k}">${SHAPE(sh, p, 200)}<b>${t}</b></button>`).join('')}</div>`,
  side: copy('지금 기분은 어때요?', '고른 기분에 맞춰 숨쉬기 방법이 달라져요') }) });
ACT.feel = k => { state.feel = k; go('F1-03'); };
const BREATH = (back, step, of, pal, next, ms) => ({ session: true, aura: pal, view: () => scr({ cls: 'mid-side', top: bar(back, step, of),
  vis: G('amoeba', pal, 400, { wrap: 'breath', liquid: true, dial: 1, keep: 'breath' }),
  side: `<div class="copy center"><div class="phase h1"><span>들이쉬고</span><span>한 번 더</span><span>길게 내쉬세요</span></div><p class="lead">조명이 밝아질 때 들이쉬고, 어두워질 때 내쉬어요</p></div>`,
  acts: next === 'F1-04' ? lnk('눈 감고 하기', 'F1-03a') : '' }), auto: [ms, next] });
S['F1-03'] = BREATH('F1-02', 3, 6, 'sunrise', 'F1-04', 18000);
S['F1-03'].auto = [18000, s => s.short ? 'F1-04' : 'F1-04'];
S['F1-03a'] = { session: true, dark: true, aura: 'sunrise', auraCls: 'low', view: () => `<section class="scr eyes"><div class="vis">${G('amoeba', 'sunrise', 72, { wrap: 'breath', blur: 4 })}</div><p class="cap" style="text-align:center">화면을 누르면 돌아가요</p></section>`, tap: () => go('F1-03'), auto: [12000, 'F1-04'] };
const TX = '<p class="lead tx" id="tx"></p>';
S['F1-04'] = F1({ view: () => scr({ cls: 'mid-side', top: bar('F1-02', 4, 6),
  vis: VOICE('amoeba', 'sunrise', false, TX, 400, { wrap: 'breath', liquid: true, rings: 0, dial: 1, keep: 'breath' }),
  side: copy('오늘의 다짐을<br>한마디로 말해 주세요'),
  acts: pri('말하기', null, 'listen:todo') + lnk('건너뛰기', 'F1-05') }) });
ACT.listen = key => { const map = { todo: ['서두르지 않고 하나씩 해내기', 'F1-04a'], vent: ['오늘 회의에서 내 의견이 묻힌 것 같아서 좀 서운했어', 'F4-05'] }[key];
  const tx = document.querySelector('#tx'); let i = 0; if (!tx) return; document.querySelector('.wave')?.classList.add('speak');
  const tick = () => { tx.textContent = map[0].slice(0, ++i); if (i < map[0].length) later(60, tick); else if (map[1]) later(900, () => go(map[1])); };
  tick(); };
S['F1-04a'] = F1({ view: s => scr({ cls: 'mid-side', top: bar('F1-04', 4, 6),
  vis: `<div class="paper-stack"><div class="paper glass back"></div><div class="paper glass"><p class="cap">오늘의 다짐</p><p class="voice" style="margin-top:20px;font-size:48px;line-height:72px">${s.intention}</p><div class="hr-line"></div><p class="cap">11월 13일 목요일 08:46</p></div></div>`,
  side: copy('이렇게 적었어요', '오늘 하루 홈에 띄워 둘게요'), acts: pri('좋아요', null, 'afterTodo') + sec('다시 말하기', 'F1-04', null, 'wide') }) });
ACT.afterTodo = () => go(state.short ? 'F1-06' : 'F1-05');
S['F1-05'] = { session: true, aura: s => s.pick, view: s => scr({ cls: 'mid-side', top: bar('F1-04a', 5, 6),
  vis: `<div class="stack-c"><div class="pick-gfx">${G(MOODSHAPE[s.pick], s.pick, 380, { wrap: 'float', rings: .25 })}</div><p class="h2 pick-name" style="margin-top:20px">${MOODS[s.pick][0]}</p><p class="body pick-desc">${MOODS[s.pick][1]}</p></div>`,
  side: copy('오늘은 어떤 분위기로<br>지낼까요?', '고른 색으로 차 안 조명과 홈 화면을 맞춰요') + `<div class="moods">${Object.entries(MOODS).map(([k, [n]]) =>
    `<button class="mood ${k === s.pick ? 'on' : ''}" data-do="pick:${k}" aria-pressed="${k === s.pick}">${SHAPE(MOODSHAPE[k], k, 76, { blur: 4 })}<span>${n}</span></button>`).join('')}</div>`,
  acts: pri('이 분위기로 정하기', null, 'setMood') }) };
// 고르면 큰 그래픽만 흐려졌다 선명해지며 바뀐다 (화면 전체를 다시 그리지 않는다)
const crossfade = (box, html, then) => { if (!box) return refresh();
  const out = box.animate([{ opacity: 1 }, { opacity: 0, transform: 'scale(.97)', filter: 'blur(6px)' }], { duration: 140, easing: 'ease-in', fill: 'forwards' });
  out.onfinish = () => { box.innerHTML = html; then?.();
    box.animate([{ opacity: 0, transform: 'scale(.94)', filter: 'blur(8px)' }, { opacity: 1, transform: 'none', filter: 'blur(0)' }], { duration: 320, easing: 'cubic-bezier(0.23, 1, 0.32, 1)' }); out.cancel(); }; };
ACT.pick = k => { if (k === state.pick) return; state.pick = k;
  document.querySelector('#app').dataset.aura = k;
  document.querySelectorAll('.mood').forEach(b => { const on = b.dataset.do === 'pick:' + k; b.classList.toggle('on', on); b.setAttribute('aria-pressed', on); });
  crossfade(document.querySelector('.pick-gfx'), G(MOODSHAPE[k], k, 380, { wrap: 'float', rings: .25 }), () => {
    document.querySelector('.pick-name').textContent = MOODS[k][0]; document.querySelector('.pick-desc').textContent = MOODS[k][1]; }); };
ACT.setMood = () => { state.mood = state.pick; go('F1-06'); };
// 분위기를 정하면 그 분위기에 맞는 한마디와 아침의 다짐으로 하루를 연다 → 차를 원래대로(C-03) → 홈
const CHEER = { calm: '긴장되는 건<br>잘하고 싶어서예요', firm: '오늘은 내 생각을<br>분명하게 말해도 돼요', warm: '먼저 건넨 한마디가<br>하루를 가볍게 해요', focus: '하나씩 하다 보면<br>오늘 안에 끝나요', flex: '계획이 바뀌어도<br>괜찮은 하루예요' };
S['F1-06'] = { session: true, aura: s => s.mood, view: s => scr({ cls: 'mid-side', top: bar(s.short ? 'F1-04a' : 'F1-05', 6, 6),
  vis: G(MOODSHAPE[s.mood], s.mood, 440, { wrap: 'float', rings: .26 }),
  side: copy(CHEER[s.mood], `‘${s.intention}’<br>오늘 잘 해낼 거예요`), acts: pri('하루 시작하기', null, 'finish:am') }) };

/* ───────────── 점심 낮잠 (F2) ───────────── */
S['F2-02'] = { session: true, aura: 'dusk', view: () => scr({ cls: 'mid-side', top: bar('F2-01', 1, 5),
  vis: `<div class="checks">${[['circle-parking', '기어 P'], ['lock', '문 잠김'], ['app-window', '창문 닫힘'], ['cloud-rain', '빗소리 켬']].map(([i, t], n) =>
    `<div class="ck glass" style="--i:${n}">${I(i)}<b>${t}</b><span class="ok">${I('check')}</span></div>`).join('')}
    <div class="ck wide mesh" ${MS('dusk')} style="--i:4"><span class="cap">일어나면</span><b>13:30 팀 회의까지 35분 남아요</b></div></div>`,
  side: copy('자기 전에 확인했어요', '차 상태를 계속 지켜볼게요'), acts: pri('자기 시작', null, 'toSleep') }) };
ACT.toSleep = () => go(state.seatSaved ? 'F2-04' : 'F2-03');
S['F2-03'] = { session: true, aura: 'dusk', view: s => scr({ cls: 'mid-side', top: bar('F2-02', 2, 5),
  vis: SEATCTL(s, 14),
  side: copy('등받이 각도를<br>정해 주세요', '한 번 정하면 다음부터 이 각도로 맞춰요'), acts: pri('이 각도로 저장', null, 'saveSeat') }) };
ACT.saveSeat = () => { state.seatSaved = true; go('F2-04'); };
S['F2-04'] = { session: true, dark: true, aura: 'night', view: s => scr({ cls: 'mid-side', top: bar(null, 3, 5),
  vis: G('orb', 'night', 440, { wrap: 'falling', liquid: true }),
  side: copy(`${s.sleep}분 뒤에 깨워 드릴게요`) + `<div class="cabin">${[['armchair', '등받이 눕힘'], ['blinds', '블라인드 닫음'], ['lamp', '조명 끔'], ['wind', '24℃ 약풍']].map(([i, t], n) => `<span style="--i:${n}">${I(i)}${t}</span>`).join('')}</div>` }),
  auto: [7000, 'F2-05'] };
S['F2-05'] = { session: true, dark: true, aura: 'night', auraCls: 'low', view: () => `<section class="scr sleep"><div class="vis">${G('orb', 'night', 80, { wrap: 'breath', blur: 4 })}</div><p class="cap" style="text-align:center">화면을 누르면 남은 시간을 보여 드려요</p></section>`,
  tap: () => ACT.peek(), auto: [9000, 'F2-06'] };
ACT.peek = () => overlay(`<div class="modal-bg"><div class="modal glass stack-c"><p class="lab">남은 시간</p><span class="dotnum">14<span class="lead">분</span></span>
  <div class="acts" style="width:100%">${pri('지금 일어나기', 'F2-06')}${sec('계속 자기', null, 'resume', 'wide')}</div></div></div>`, true);
S['F2-06'] = { session: true, aura: 'sunrise', auraCls: 'rise', view: () => scr({ cls: 'mid-side', top: bar(null, 4, 5),
  vis: `<div class="gfx waking dawn">${SHAPE('orb', 'night', 440, { liquid: true })}${SHAPE('orb', 'wake', 440, { liquid: true })}</div>`,
  side: copy('천천히 깨우는 중이에요') + `<div class="cabin">${[['sun', '조명 밝힘'], ['wind', '22℃ 바람'], ['armchair', '등받이 세움'], ['app-window', '창문 2cm 열림']].map(([i, t], n) => `<span style="--i:${n}">${I(i)}${t}</span>`).join('')}</div>` }),
  auto: [8000, 'F2-07'] };
S['F2-07'] = { session: true, aura: 'sunrise', view: s => scr({ cls: 'mid-side', top: bar(null, 5, 5),
  vis: `<div class="wake-card glass"><p class="lab">방금 잔 시간</p><div class="row-c" style="align-items:baseline;gap:12px"><span class="dotnum">${s.sleep}</span><span class="lead">분</span></div>${RULER(s.sleep / 30)}<div class="row-c cap" style="justify-content:space-between;margin-top:12px"><span>0</span><span>15</span><span>30분</span></div></div>`,
  side: copy('낮잠이 끝났어요', '13:30 팀 회의까지 35분 남았어요'),
  acts: pri('마치기', null, 'finish:noon') + `<button class="btn sec m wide" data-do="closeWin">${I('app-window')}창문 닫기</button>` }), auto: [25000, 'F2-07a'] };
ACT.closeWin = (_, el) => { el.innerHTML = `${I('check')}창문을 닫았어요`; el.disabled = true; icons(); };
S['F2-07a'] = { session: true, aura: 'sunrise', auraCls: 'rise', view: () => scr({ cls: 'mid-side', top: bar(null, 5, 5),
  vis: G('orb', 'wake', 420, { wrap: 'beat', liquid: true }), side: copy('일어났어요?', '소리를 키우고 실내등을 켰어요'), acts: pri('일어났어요', 'F2-07') }) };

/* ───────────── 마음 준비 (F3): 중요한 일정 직전, 할 말을 소리 내 보고 자신감이 생기는 음악으로 집중한다 ───────────── */
const backTop = to => `<header class="top"><button class="iconbtn glass" data-go="${to}" aria-label="뒤로">${I('chevron-left')}</button><span></span></header>`;
// 일정은 시각만 받는다 (무슨 일인지는 묻지 않는다)
S['F3-00'] = { aura: 'coral', auraCls: 'low', view: s => scr({ cls: 'mid-side', top: backTop('H-01'),
  vis: `<div class="row-c"><button class="iconbtn glass" data-do="meetAt:-30" aria-label="30분 앞당기기">${I('minus')}</button><span class="dotnum tm" id="meetAt">${s.meetAt}</span><button class="iconbtn glass" data-do="meetAt:30" aria-label="30분 미루기">${I('plus')}</button></div>`,
  side: copy('중요한 일정이<br>몇 시에 있어요?', '20분 전에 마음 준비를 알려 드려요'), acts: pri('저장', null, 'saveMeet') + mic('4시') }) };
ACT.meetAt = n => { const [h, m] = state.meetAt.split(':').map(Number), t = Math.min(20 * 60, Math.max(9 * 60, h * 60 + m + +n));
  state.meetAt = `${String(Math.floor(t / 60)).padStart(2, '0')}:${String(t % 60).padStart(2, '0')}`; document.querySelector('#meetAt').textContent = state.meetAt; };
ACT.saveMeet = () => { go('H-01'); later(600, () => toast(`${MINI('meet', 72)}${HM(state.meetAt)} 일정을 저장했어요<small>20분 전에 알려 드릴게요</small>`)); };

const F3 = x => ({ session: true, aura: 'coral', ...x });
S['F3-03'] = F3({ view: s => scr({ cls: 'mid-side', top: bar(s.round === 1 ? 'F3-01' : 'F3-04', 1, 4),
  vis: VOICE('tri', 'coral', true),
  side: s.round === 1 ? copy('꼭 해야 할 말을<br>소리 내 말해 보세요', '중요한 자리에서 꺼낼 첫마디면 충분해요') : copy('한 번 더<br>말해 보세요', '방금보다 천천히 말해 봐요'),
  acts: pri('다 말했어요', null, 'doneTalk') }) });
ACT.doneTalk = () => go(state.round === 1 ? 'F3-04' : 'F3-05');
S['F3-04'] = F3({ view: () => scr({ cls: 'mid-side', top: bar('F3-03', 2, 4),
  vis: `<div class="tiles">
    <div class="tile glass"><p class="lab">말한 시간</p><p class="mid">${MIN(72, '초')}</p>${RULER(.8)}</div>
    <div class="tile mesh" ${MS('coral')}><p class="lab">목소리 크기</p><p class="h2" style="margin-top:12px">적당해요</p>${SEGS(3, 4, 'coral')}</div>
    <div class="tile glass hot"><p class="lab">‘음’ 같은 말</p><p class="mid">${MIN(4, '번')}</p>${MBARS([.35, .8, .5, .95], 'coral')}</div>
    <div class="tile glass"><p class="lab">속도</p><p class="h2" style="margin-top:12px">조금 빨라요</p>${SINE(170)}</div></div>`,
  side: copy('또렷하게 잘 들렸어요', '‘음’ 같은 말만 조금 줄이면 더 좋아요'), acts: pri('한 번 더 말하기', null, 'again') + sec('자신감 생기는 음악 고르기', 'F3-06', null, 'wide') }) });
ACT.again = () => { state.round = 2; go('F3-03'); };
S['F3-05'] = F3({ view: () => scr({ cls: 'mid-side', top: bar('F3-03', 2, 4),
  vis: `<div class="cmp glass">${[['말한 시간', 72, 58, '초', 90], ['‘음’ 같은 말', 4, 1, '번', 5]].map(([l, a, b, u, m]) =>
    `<div class="cr"><span class="lab">${l}</span><div class="pair"><i class="was" style="width:${a / m * 100}%"></i><i class="now" style="width:${b / m * 100}%"></i></div><span class="mid" style="font-size:48px">${b}<sup>${u}</sup></span></div>`).join('')}</div>`,
  side: copy('아까보다 훨씬<br>편안하게 들렸어요', '말한 시간은 14초 줄고, ‘음’은 4번에서 1번이 됐어요'), acts: pri('자신감 생기는 음악 고르기', 'F3-06') }) });

// 음악 고르기: 앨범 대신 추상 형태 커버, 고르면 큰 커버만 흐려졌다 바뀐다
const TUNES = [['내 응원곡', '지난번에 고른 곡', 'halo', 'coral'], ['최근에 들은 곡', '오늘 출근길에 들었어요', 'venn', 'ember'], ['힘이 나는 음악', '추천', 'cluster', 'firm']];
const COVER = (i, size, cls = '') => { const [, , k, p] = TUNES[i]; return `<span class="cover mesh ${cls}" ${MS(p)}>${SHAPE(k, p, size, { blur: 5 })}</span>`; };
const TUNEBIG = i => `${COVER(i, 300, 'xl')}<p class="h2">${TUNES[i][0]}</p>`;
S['F3-06'] = F3({ aura: s => TUNES[s.tune][3], view: s => scr({ cls: 'mid-side', top: bar(s.round === 2 ? 'F3-05' : 'F3-04', 3, 4),
  vis: `<div class="tune-big stack-c">${TUNEBIG(s.tune)}</div>`,
  side: copy('자신감이 생기는 음악을<br>골라 주세요', '들으면 힘이 나는 곡이면 좋아요') + `<div class="list glass tunes">${TUNES.map(([t, sub], i) =>
    `<button class="li tune ${i === s.tune ? 'on' : ''}" data-do="tune:${i}" aria-pressed="${i === s.tune}">${COVER(i, 64)}<span class="t"><b>${t}</b><span>${sub}</span></span><i class="radio"></i></button>`).join('')}</div>`,
  acts: pri('이 음악 듣기', 'F3-07') }) });
ACT.tune = i => { i = +i; if (i === state.tune) return; state.tune = i; document.querySelector('#app').dataset.aura = TUNES[i][3];
  document.querySelectorAll('.tune').forEach((b, n) => { b.classList.toggle('on', n === i); b.setAttribute('aria-pressed', n === i); });
  crossfade(document.querySelector('.tune-big'), TUNEBIG(i)); };
S['F3-07'] = { session: true, dark: true, aura: s => TUNES[s.tune][3], view: s => { const [t, , k, p] = TUNES[s.tune];
  return scr({ cls: 'mid-side', top: bar('F3-06', 4, 4),
    vis: `<div class="stack-c">${G(k, p, 400, { wrap: 'pulse', rings: .3 })}<div class="np" role="img" aria-label="재생 중"><b></b></div></div>`,
    side: copy('음악에 집중해 보세요', '눈을 감아도 괜찮아요. 곡이 끝나면 알려 드릴게요', `<p class="lab">${t}</p>`)
      + `<div class="cabin">${[['volume-2', '소리 키움'], ['lamp', '조명 따뜻하게'], ['armchair', '등받이 세움']].map(([i, x], n) => `<span style="--i:${n}">${I(i)}${x}</span>`).join('')}</div>`,
    acts: pri('준비됐어요', null, 'finish:meet') + lnk('다른 곡 고르기', 'F3-06') }); } };
S['F3-08'] = { aura: 'coral', view: s => scr({ cls: 'mid-side', top: backTop('H-01'), vis: G('tri', 'coral', 400, { wrap: 'float' }),
  side: copy(`${HM(s.meetAt)} 일정은 어땠어요?`, '저녁에 하루를 돌아볼 때 같이 보여 드려요') + `<div class="seg3 big">${['잘 됐어요', '그럭저럭', '힘들었어요'].map(t => `<button data-do="meetDone">${t}</button>`).join('')}</div>` }) };
ACT.meetDone = () => { go('H-01'); later(500, () => toast(`${MINI('meet', 64)}기록을 남겼어요`)); };

/* ───────────── 하루 마무리 (F4) ───────────── */
S['F4-02'] = { session: true, aura: s => s.mood, view: s => scr({ cls: 'mid-side', top: bar('F4-01', 1, 5),
  vis: G('venn', 'ember', 460, { wrap: 'merge' }), side: copy('오늘 하루 고생했어요', '등받이를 조금 눕히고 조명을 낮췄어요') + `<div class="cabin">${[['armchair', '등받이 10° 눕힘'], ['lamp', '조명 낮춤'], ['flame', '열선 1단']].map(([i, t], n) => `<span style="--i:${n}">${I(i)}${t}</span>`).join('')}</div>` }),
  after: () => later(900, () => { const a = document.querySelector('#app'); if (a) a.dataset.aura = 'ember'; }), auto: [6500, 'F4-03'] };
S['F4-03'] = { session: true, aura: s => s.dayColor || 'ember', view: s => scr({ cls: 'mid-side', top: bar('F4-02', 2, 5),
  vis: DAYTRACK([['am', NAME.am, '08:41'], ['noon', '낮잠 20분', '12:36'], ['meet', NAME.meet, '15:42'], ['pm', '지금', '19:10']]),
  side: copy('오늘 하루는<br>어떤 색이었어요?', '고른 색으로 차 안 조명을 바꿔요') + `<div class="moods">${Object.entries(MOODS).map(([k, [n]]) => `<button class="mood" data-do="daycolor:${k}">${SHAPE(MOODSHAPE[k], k, 76, { blur: 4 })}<span>${n.replace(/하게|해서/, '')}</span></button>`).join('')}</div>` }) };
ACT.daycolor = (k, el) => { state.dayColor = k; document.querySelector('#app').dataset.aura = k; el.classList.add('on'); later(900, () => go(state.short ? 'F4-05' : 'F4-04')); };
S['F4-04'] = { session: true, aura: s => s.dayColor || 'ember', view: s => scr({ cls: 'mid-side', top: bar('F4-03', 3, 5),
  vis: VOICE('venn', s.dayColor || 'ember', false, TX),
  side: copy('오늘 있었던 일을<br>말해 볼까요?', '생각나는 대로 편하게 말해 주세요'), acts: pri('말하기', null, 'listen:vent') + lnk('건너뛰기', 'F4-05') }) };
S['F4-05'] = { session: true, aura: s => s.dayColor || 'ember', view: s => scr({ cls: 'mid-side', top: bar(s.short ? 'F4-03' : 'F4-04', 4, 5),
  vis: `<div class="week-card glass">${DAYS(3)}<div class="row-c" style="justify-content:space-between;align-items:flex-end;margin-top:32px"><div><p class="lab">이번 주 쌓인 틈새 시간</p><span class="big" style="font-size:96px">${MIN(74)}</span></div>
    <div class="kpis"><span><b>4일</b>아침 준비 연속</span><span><b>13번</b>쉬었어요</span></div></div><div style="margin-top:28px">${BARS(7, false)}</div></div>`,
  side: copy('이번 주 기록이에요', '지난주보다 18분 더 쌓였어요'),
  acts: pri('다음', 'F4-06') }) };
S['F4-06'] = { session: true, aura: 'ember', view: s => scr({ cls: 'mid-side', top: bar(s.done.pm ? null : 'F4-05', 5, 5),
  vis: G('venn', 'ember', 440, { wrap: 'setting' }), side: copy('하루 마무리를 마쳤어요', '실내등을 켜고 등받이를 원래대로 돌릴게요'),
  acts: pri('집에 들어가기', null, 'finish:pm') }) };

/* ───────────── 3분 숨 고르기 (Q1) ───────────── */
S['Q1-01'] = { session: true, aura: 'rose', view: () => scr({ cls: 'mid-side', top: bar(null, 1, 3),
  vis: `<div class="gfx">${SHAPE('amoeba', 'rose', 360, { liquid: true, cls: 'float' })}<span class="dotnum count over">3</span></div>`,
  side: copy('3분만 숨 고를게요', '조명을 낮추고 시트를 편하게 맞출게요') }),
  after: () => { let n = 3; const tick = () => { const el = document.querySelector('.count'); if (el && n > 1) { el.textContent = --n; later(900, tick); } }; later(900, tick); }, auto: [2800, 'Q1-B'] };
S['Q1-B'] = BREATH(null, 2, 3, 'rose', 'Q1-02', 18000);
S['Q1-02'] = { session: true, aura: 'rose', view: () => scr({ cls: 'mid-side', top: bar('Q1-B', 3, 3), vis: G('cluster', 'rose', 380, { wrap: 'float', rings: .25 }),
  side: copy('지금 기분을<br>골라 주세요', '고른 기분은 오늘 기록에 남아요') + `<div class="chips" style="margin-top:32px">${['답답해요', '억울해요', '지쳤어요', '괜찮아졌어요'].map(t => `<button class="chip glass" data-do="finish:q1">${t}</button>`).join('')}</div>` }) };

/* ───────────── 기록 · 설정 ───────────── */
const page = (title, body, back = 'H-01', aura = 'home') => ({ aura, auraCls: 'low', view: s => `<section class="scr page">
  <header class="top" style="justify-content:flex-start"><button class="iconbtn glass" data-go="${back}" aria-label="뒤로">${I('chevron-left')}</button><h1 class="h2">${title}</h1></header>
  <div class="pbody">${typeof body === 'function' ? body(s) : body}</div></section>` });
const dayRow = (d, sub, k) => `<button class="li" data-go="R-02"><span class="sg">${MINI(k, 64)}</span><span class="t"><b>${d}</b><span>${sub}</span></span><span class="v">${I('chevron-right')}</span></button>`;
S['R-01'] = page('이번 주', () => `<div class="cols"><section class="glass card">${DAYS(3)}<div class="row-c" style="justify-content:space-between;align-items:flex-end;margin-top:32px"><div><p class="lab">이번 주 쌓인 틈새 시간</p><span class="big">${MIN(74)}</span></div><span class="cap">11월 10일 – 16일</span></div><div style="margin-top:32px">${BARS(7, false)}</div></section>
  <div class="stack"><div class="kpi-row"><div class="kpi mesh" ${MS('sunrise')}><span class="cap">아침 준비</span><b>4일</b><span class="cap">연속으로 했어요</span></div><div class="kpi glass"><span class="cap">이번 주</span><b>13번</b><span class="cap">쉬었어요</span></div></div>
  <div class="list glass">${dayRow('목요일 · 오늘', '4번 쉬었어요 · 단호하게', 'am')}${dayRow('수요일', '4번 쉬었어요 · 유연하게', 'meet')}${dayRow('화요일', '2번 쉬었어요 · 다정하게', 'noon')}</div></div></div>`);
S['R-02'] = page('11월 13일 목요일', s => `<div class="cols"><div class="stadium sm"><div class="pass mesh" ${MS(s.mood)}><div class="pass-gfx">${SHAPE(MOODSHAPE[s.mood], s.mood, 200, { blur: 5 })}</div><p class="cap">오늘의 다짐 · ${MOODS[s.mood][0]}</p><p class="voice">${s.intention}</p></div></div>
  <div class="glass card">${DAYTRACK([['am', NAME.am, '08:41'], ['noon', '낮잠', '12:36'], ['meet', NAME.meet, '15:42'], ['pm', '마무리', '19:10']], -1)}
  <div class="list" style="margin-top:24px">${[[NAME.meet, '2번 말해 봤어요 · 4시 일정은 잘 됐어요'], ['점심 낮잠', '20분 쉬었어요'], ['아침 기분', '긴장돼요']].map(([n, d]) => `<div class="li"><span class="t"><b>${n}</b><span>${d}</span></span></div>`).join('')}</div></div></div>`, 'R-01');

// 설정: Portrait는 목록 → 상세 두 화면, Landscape는 왼쪽 목록 + 오른쪽 상세 한 화면
const ST_ROWS = [['map-pin', '장소', '회사 · 집', 'ST-01'], ['clock-3', '평소 시간', '출근 09:00 · 점심 복귀 13:00', 'ST-02'], ['armchair', '자세와 소리', '', 'ST-03'], ['bell', '알림', '4개 모두 켜짐', 'ST-04'],
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
setting('ST-01', () => `<div class="places" style="margin-top:0">${PLACE('work', '강남구 테헤란로')}${PLACE('home', '성동구 왕십리로')}</div>`);
setting('ST-02', `<div class="timepair" style="margin-top:0"><div class="tcard glass"><span class="cap">출근</span><span class="dotnum sm">09:00</span></div><div class="tcard glass"><span class="cap">점심 복귀</span><span class="dotnum sm">13:00</span></div></div><div class="list glass"><div class="li"><span class="t"><b>주차장에서 자리까지</b></span><span class="v">3분</span></div><div class="li"><span class="t"><b>재택하는 날</b><span>이날은 알려 드리지 않아요</span></span><span class="v">금요일</span></div></div>`);
setting('ST-03', `<div class="list glass"><div class="li"><span class="t"><b>쉴 때 등받이</b></span><span class="v">118°</span></div><div class="li"><span class="t"><b>낮잠 등받이</b></span><span class="v">132°</span></div><div class="li"><span class="t"><b>쉴 때 소리</b></span><span class="v">빗소리</span></div><div class="li"><span class="t"><b>깨울 때 음악</b></span><span class="v">아침 산책</span></div><div class="li"><span class="t"><b>마음 준비 음악</b></span><span class="v">내 응원곡</span></div>${sw('음성 안내', '')}</div>`);
setting('ST-04', `<div class="list glass">${[['am', '회사에 도착하면'], ['noon', '점심 먹고 차에 돌아오면'], ['meet', '등록한 일정 20분 전에'], ['pm', '집에 도착하면']].map(([k, d]) => `<div class="li"><span class="sg">${MINI(k, 64)}</span><span class="t"><b>${NAME[k]}</b><span>${d}</span></span><button class="switch on" data-do="toggle" aria-label="${NAME[k]}"></button></div>`).join('')}${sw('도착 전에 미리 묻기', '집 도착 5분 전에 한 번 물어봐요')}</div>`);
setting('ST-05', `<div class="moodgrid">${Object.entries(MOODS).map(([k, [n, d]]) => `<div class="mg glass">${SHAPE(MOODSHAPE[k], k, 140, { blur: 5 })}<b>${n}</b><span class="cap">${d}</span></div>`).join('')}</div>`);
setting('ST-06', `<div class="list glass"><div class="li"><span class="t"><b>기록 보관 기간</b><span>모든 기록은 이 차에만 저장해요</span></span><span class="v">1년</span></div>${sw('저녁에 말한 내용 하루 뒤 지우기', '다음 날 아침 인사에만 쓰고 지워요')}<button class="li" data-do="wipeAsk"><span class="t"><b style="color:var(--pleos-informative-negative)">모든 기록 지우기</b></span></button></div>`);
ACT.wipeAsk = () => confirmModal('모든 기록을 지울까요?', '지운 기록은 되돌릴 수 없어요', ['취소', 'resume'], ['지우기', 'wipe']);
ACT.wipe = () => { closeOverlay(true); later(300, () => toast(`${I('trash-2')}모든 기록을 지웠어요`)); };
setting('ST-07', `<div class="list glass">${[['전동 등받이', 1], ['앰비언트 라이트', 1], ['통풍 시트', 1], ['스마트글라스', 1], ['블라인드', 0]].map(([t, ok]) => `<div class="li"><span class="t"><b>${t}</b>${ok ? '' : '<span>햇빛 가리개를 펼쳐 달라고 안내해요</span>'}</span><span class="v ${ok ? 'ok' : ''}">${ok ? I('check') + '쓸 수 있어요' : '안내로 대신해요'}</span></div>`).join('')}</div>`);
setting('ST-08', `<div class="stack-c" style="padding:48px 0"><span class="appicon xl">${MARK(158)}</span><p class="h2" style="margin-top:24px">틈틈</p><p class="cap">버전 0.9</p></div><div class="list glass">${stRow('rotate-ccw', '처음 안내 다시 보기', '', 'ON-01')}</div>`);
setting('ST');
