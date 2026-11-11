// 틈틈 프로토타입 엔진: Pleos 셸, 라우터, 데모 컨트롤
const $ = s => document.querySelector(s);
const state = {
  theme: 'light', mode: 'split', view: 'display', gear: 'P', time: '08:40',
  slot: 'am', done: { am: false, noon: false, meet: false, pm: false },
  screen: null, prev: null, slide: 0, feel: null, mood: 'firm', pick: 'firm',
  intention: '서두르지 않고 하나씩 해내기', sleep: 20, seatSaved: false, round: 1, dayColor: null,
  skipped: {}, short: false, walk: 3, seat: 118, homeSet: false, justDone: null, lastP: null,
  interrupted: false, meetAt: '16:00', tune: 0,
};
let timers = [];
const later = (ms, fn) => timers.push(setTimeout(fn, ms));
const clearTimers = () => { timers.forEach(clearTimeout); timers = []; };

/* ── 셸 ─────────────────────────────────────────────── */
const ICON = n => `<i data-lucide="${n}"></i>`;
const navMock = () => `<div class="navmock">
  <svg viewBox="0 0 840 1184" preserveAspectRatio="none"><g stroke="var(--pleos-divider-tertiary)" stroke-width="22" fill="none" stroke-linecap="round">
  <path d="M-20 820 L860 560"/><path d="M180 -20 L300 1200"/><path d="M-20 300 L860 420"/><path d="M620 -20 L560 1200"/></g>
  <path d="M300 1200 L268 760 L610 655 L585 360" stroke="var(--pleos-text-dark-primary)" stroke-width="14" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
  <circle cx="268" cy="760" r="22" fill="var(--pleos-text-dark-primary)"/></svg>
  <div class="eta">집까지 18분<small>19:10 도착 · 9.2km</small></div></div>`;

function displayHTML() {
  const d = state.gear === 'D';
  return `<div class="display" data-theme="${state.theme}" data-mode="${state.mode}">
    <div class="sbar"><span class="ic">${ICON('lock')}</span><span class="ic">${ICON('search')}</span><span class="ic">${ICON('bell')}</span>
      <span style="flex:1"></span><span class="ic">${ICON('user-round')}</span><span class="ic">${ICON('bluetooth')}</span><span class="ic">${ICON('signal')}</span><span class="clock">${state.time}</span></div>
    <div class="dview"><div class="gear">${['P','R','N','D'].map(g => g === state.gear ? `<b>${g}</b>` : g).join('')}</div>
      <div class="spd">${d ? 48 : 0}<small>km/h</small></div><div class="meta">${ICON('battery-medium')} 78% · 412km</div></div>
    <div class="appzone">
      <div class="win" style="left:0;width:${state.mode === 'full' ? 1700 : 840}px">${appHTML()}</div>
      <div class="win side">${navMock()}</div>
    </div>
    <div class="gnb"><span class="clim">22.0°</span>
      <span class="apps"><span class="ai">${ICON('navigation')}</span><span class="ai">${ICON('music-2')}</span><span class="ai">${ICON('phone')}</span><span class="ai on appicon">${MARK(66)}</span><span class="ai">${ICON('layout-grid')}</span></span>
      <span class="clim">22.0°</span></div>
  </div>`;
}
const appHTML = () => `<div class="app" id="app" data-theme="${state.theme}" data-aura="home"><div class="aura" id="aura"><i></i><i></i><i></i></div><div id="scr"></div><div id="ov"></div></div>`;

function renderShell() {
  const W = state.view === 'display' ? 2560 : (state.mode === 'full' ? 1700 : 840);
  const H = state.view === 'display' ? 1440 : 1184;
  $('#stage').innerHTML = `<div class="scaler" id="scaler" style="width:${W}px;height:${H}px">${state.view === 'display' ? displayHTML()
    : `<div class="win" style="position:relative;width:${W}px;border-radius:40px">${appHTML()}</div>`}</div>`;
  fit();
  if (state.screen) paint(state.screen, true);
  icons();
}
function fit() {
  const el = $('#scaler'); if (!el) return;
  const st = $('#stage').getBoundingClientRect(), W = el.offsetWidth, H = el.offsetHeight;
  if (st.width < 50 || st.height < 50) return; // 패널이 숨겨진 채 로드되면 크기가 0 → 보일 때 다시 맞춘다
  const s = Math.min((st.width - 24) / W, (st.height - 24) / H);
  el.style.transform = `translate(${(st.width - W * s) / 2}px, ${(st.height - H * s) / 2}px) scale(${s})`;
}
new ResizeObserver(fit).observe(document.getElementById('stage'));
const icons = () => window.lucide && lucide.createIcons();

/* ── 라우터 ─────────────────────────────────────────── */
function go(id) {
  if (!S[id]) return console.warn('no screen', id);
  clearTimers(); closeOverlay();
  state.prev = state.screen; state.screen = id;
  $('#app')?.style.setProperty('--dir', state.back ? -1 : 1); state.back = false; // 뒤로 갈 때는 반대 방향으로 넘어간다
  const sc = S[id];
  sc.enter?.(state);
  const old = $('#scr .scr'), keep = old?.querySelector('[data-keep]'), from = keep && { key: keep.dataset.keep, r: keep.getBoundingClientRect() };
  // 설정처럼 목록과 상세가 한 화면에 함께 보일 때(창 폭 1200↑)는 화면 전체를 바꾸지 않고 상세만 바꾼다
  if (sc.group && sc.group === S[state.prev]?.group && $('#app').offsetWidth >= 1200) {
    paint(id, true); $('#scr .md-detail')?.animate([{ opacity: 0, transform: 'translateY(8px)' }, { opacity: 1, transform: 'none' }], { duration: 240, easing: 'cubic-bezier(0.23, 1, 0.32, 1)' });
  } else if (old) { old.classList.add('out'); later(200, () => { paint(id); glide(from); }); } else paint(id);
}
// 앞뒤 화면에 같은 그래픽(data-keep)이 있으면 그 자리에서 새 자리로 미끄러지듯 이어 준다
function glide(from) {
  const to = from && $(`#scr [data-keep="${from.key}"]`); if (!to || matchMedia('(prefers-reduced-motion: reduce)').matches) return;
  to.closest('.vis')?.style.setProperty('animation', 'none'); // 이어지는 그래픽은 새로 나타나는 연출 없이 자리만 옮긴다
  const r = to.getBoundingClientRect(), k = r.width / to.offsetWidth || 1;
  to.animate([{ transform: `translate(${(from.r.x - r.x) / k}px, ${(from.r.y - r.y) / k}px)` }, { transform: 'none' }], { duration: 640, easing: 'cubic-bezier(0.23, 1, 0.32, 1)' });
}
function paint(id, quiet) {
  const sc = S[id], app = $('#app'); if (!app) return;
  app.dataset.theme = sc.dark ? 'dark' : state.theme; // 잠드는 화면은 테마와 상관없이 어둡게
  app.dataset.aura = typeof sc.aura === 'function' ? sc.aura(state) : (sc.aura || 'home');
  $('#aura').className = 'aura ' + (sc.auraCls || '');
  app.classList.toggle('driving', state.gear === 'D');
  $('#scr').innerHTML = sc.view(state);
  if (quiet) { $('#scr .scr')?.style.setProperty('animation', 'none'); $('#scr .scr')?.classList.add('quiet'); }
  icons();
  const dot = $('#scr .track .dot');
  if (dot) { const p = +dot.dataset.p, w = dot.parentElement.offsetWidth;
    if (state.lastP != null && state.lastP !== p && !quiet) dot.animate([{ transform: `translateX(${state.lastP * w}px)` }, { transform: `translateX(${p * w}px)` }], { duration: 480, easing: 'cubic-bezier(0.23, 1, 0.32, 1)' });
    state.lastP = p; } else state.lastP = null;
  sc.after?.(state);
  if (sc.auto && !quiet) later(sc.auto[0], () => go(typeof sc.auto[1] === 'function' ? sc.auto[1](state) : sc.auto[1]));
}
const refresh = () => paint(state.screen, true);

/* ── 오버레이 ───────────────────────────────────────── */
function overlay(html) { $('#ov').innerHTML = html; icons(); }
function closeOverlay(anim) { const o = $('#ov'); if (!o || !o.firstElementChild) return; if (!anim) { o.innerHTML = ''; return; }
  o.firstElementChild.classList.add('leaving'); setTimeout(() => { if (o.querySelector('.leaving')) o.innerHTML = ''; }, 200); }
function toast(html, ms = 3000) {
  const t = document.createElement('div'); t.className = 'toast glass'; t.innerHTML = html;
  $('#app')?.appendChild(t); icons(); setTimeout(() => { t.classList.add('out'); setTimeout(() => t.remove(), 240); }, ms);
}

/* ── 클릭 ───────────────────────────────────────────── */
document.addEventListener('click', e => {
  const c = e.target.closest('[data-c]');
  if (c) return control(c.dataset.c);
  const t = e.target.closest('[data-go],[data-do]');
  if (!t) { S[state.screen]?.tap?.(state); return; }
  state.back = !!t.closest('.top') && /이전|뒤로/.test(t.getAttribute('aria-label') || '');
  if (t.dataset.do) ACT[t.dataset.do.split(':')[0]]?.(t.dataset.do.split(':')[1], t);
  if (t.dataset.go) go(t.dataset.go);
});

/* ── 그래픽 만지기: 누르면 살짝 눌리고, 끌면 고무줄처럼 따라오다가 놓으면 튕겨 돌아온다 ── */
const rubber = (d, lim = 150) => d * lim * .55 / (lim + .55 * Math.abs(d));
document.addEventListener('pointerdown', e => {
  const g = e.target.closest('.gfx, .hero-gfx'); if (!g || state.gear === 'D' || e.target.closest('button, .seat, .modal')) return;
  const k = g.getBoundingClientRect().width / g.offsetWidth || 1, m = new DOMMatrix(getComputedStyle(g).transform), x0 = e.clientX, y0 = e.clientY;
  g.getAnimations().forEach(a => a.id === 'snap' && a.cancel()); // 돌아오는 중에 다시 잡으면 그 자리에서 이어진다
  const set = (dx, dy) => { g.style.transition = 'none'; g.style.transform = `translate(${m.e + rubber(dx / k)}px, ${m.f + rubber(dy / k)}px) scale(.95)`; };
  const move = ev => set(ev.clientX - x0, ev.clientY - y0);
  const up = () => { removeEventListener('pointermove', move); removeEventListener('pointerup', up); removeEventListener('pointercancel', up);
    const from = g.style.transform; g.style.transform = ''; g.style.transition = '';
    g.animate([{ transform: from }, { transform: 'none' }], { duration: 640, easing: 'cubic-bezier(.34, 1.56, .64, 1)' }).id = 'snap'; };
  set(0, 0); addEventListener('pointermove', move); addEventListener('pointerup', up); addEventListener('pointercancel', up);
});

/* ── 데모 컨트롤 (차에서 일어나는 일을 흉내) ──────────── */
const SCENE = {
  am:   { time: '08:40', slot: 'am',   pre: {},                                              moment: 'M-F1' },
  noon: { time: '12:35', slot: 'noon', pre: { am: true },                                    moment: 'F2-01' },
  meet: { time: '15:40', slot: 'meet', pre: { am: true, noon: true },                        moment: 'F3-01' },
  pm:   { time: '19:05', slot: 'pm',   pre: { am: true, noon: true, meet: true },            moment: 'V-01' },
};
function control(c) {
  if (c === 'restart') { Object.assign(state, { time: '08:40', slot: 'am', done: { am: false, noon: false, meet: false, pm: false }, gear: 'P', slide: 0, seatSaved: false, round: 1, interrupted: false, skipped: {}, short: false, homeSet: false, dayColor: null, meetAt: '16:00', tune: 0 }); renderShell(); go('ON-01'); }
  else if (SCENE[c]) {
    const sc = SCENE[c]; Object.assign(state, { time: sc.time, slot: sc.slot, gear: 'P', interrupted: false, skipped: {}, short: false, round: 1, dayColor: null, tune: 0 });
    Object.assign(state.done, { am: false, noon: false, meet: false, pm: false }, sc.pre);
    renderShell();
    if (c === 'pm') { state.gear = 'D'; renderShell(); go('V-01'); }
    else { go('H-01'); later(1400, () => go(sc.moment)); }
  }
  else if (c === 'split' || c === 'full') { state.mode = c; renderShell(); }
  else if (c === 'light' || c === 'dark') { state.theme = c; renderShell(); }
  else if (c === 'display' || c === 'app') { state.view = c; renderShell(); }
  else if (c === 'drive') { state.gear = state.gear === 'D' ? 'P' : 'D'; onGear(); }
  else if (c === 'door') { if (S[state.screen]?.session) ACT.door(); }
  renderCtrl();
}
function onGear() {
  if (state.gear === 'D') { if (S[state.screen]?.session) state.interrupted = true; renderShell(); go('H-02'); }
  else { renderShell(); go('H-01'); if (state.interrupted) later(700, () => ACT.recover()); }
}
function renderCtrl() {
  const b = (c, t, on) => `<button data-c="${c}" aria-pressed="${!!on}">${t}</button>`;
  $('#ctrl').innerHTML = `${b('restart', '처음부터')}<span class="sep"></span>
    <span class="g">${b('am', '08:40 출근', state.slot === 'am' && state.time === '08:40')}${b('noon', '12:35 점심', state.time === '12:35')}${b('meet', '15:40 일정 전', state.time === '15:40')}${b('pm', '19:05 귀가', state.slot === 'pm')}</span><span class="sep"></span>
    <span class="g">${b('split', '분할 1:1', state.mode === 'split')}${b('full', '전체 2:0', state.mode === 'full')}</span>
    <span class="g">${b('light', '라이트', state.theme === 'light')}${b('dark', '다크', state.theme === 'dark')}</span>
    <span class="g">${b('drive', state.gear === 'D' ? '주행 중 → 정차' : '주행 시작', state.gear === 'D')}${b('door', '문 열기')}</span>
    <span class="g">${b('display', '17" 화면', state.view === 'display')}${b('app', '앱만 크게', state.view === 'app')}</span>`;
}

// 주소로 바로 열기: ?s=F1-05&theme=dark&mode=full&view=app&done=am,noon&slot=noon
const q = new URLSearchParams(location.search);
['theme', 'mode', 'view', 'slot', 'time'].forEach(k => q.get(k) && (state[k] = q.get(k)));
(q.get('done') || '').split(',').filter(Boolean).forEach(k => state.done[k] = true);
renderCtrl(); renderShell(); go(q.get('s') || 'ON-01');
