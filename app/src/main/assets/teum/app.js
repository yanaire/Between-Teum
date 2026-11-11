// The document owns presentation. Android owns permissions, phases, time and saved facts.
const $ = selector => document.querySelector(selector);
const state = { screen: null, slide: 0 };
const ACT = {};
let epoch = window.__TEUM_EPOCH__;
let requestSequence = 0;
let renderGeneration = 0;
let lastRenderRevision = null;
let lastPresentation = null;
let timers = [];
const drafts = new Map();
let activeOverlay = null;
let overlayMarkup = null;
let arrivalReservation = null;
let arrivalEntryWait = null;
const arrivalAdmissions = new Set();
let arrivalDeferred = [];
let graphicGesture = null;
function releaseArrivalReservation(discard = false) {
  if (discard && arrivalEntryWait) {
    arrivalAdmissions.add(arrivalEntryWait.nonce);
    arrivalEntryWait = null;
  }
  arrivalReservation = null;
  const deferred = arrivalDeferred;
  arrivalDeferred = [];
  if (!discard) deferred.forEach(action => action());
}
function tryArrivalAdmission() {
  const nonce = state.arrival?.admissionNonce;
  if (typeof nonce !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(nonce) ||
      arrivalAdmissions.has(nonce) || arrivalReservation || graphicGesture || state.screen !== 'H-01' ||
      state.journeyToken || document.visibilityState !== 'visible' || !document.hasFocus() ||
      !$('#scr .scr.home') || $('#scr .scr.out') || activeOverlay || $('#ov').firstElementChild ||
      state.permissions?.requestPending ||
      document.activeElement?.matches('input,textarea,select,[data-bind]')) return;
  arrivalAdmissions.add(nonce);
  arrivalReservation = nonce;
  send('admitArrival', {nonce});
}
function scheduleArrivalAdmission() {
  const nonce = state.arrival?.admissionNonce;
  const root = $('#scr .scr.home');
  const pending = arrivalEntryWait;
  if (pending && (pending.nonce !== nonce || pending.epoch !== epoch || state.screen !== 'H-01')) {
    arrivalAdmissions.add(pending.nonce);
    arrivalEntryWait = null;
  }
  if (typeof nonce !== 'string' || arrivalAdmissions.has(nonce) || !root ||
      state.screen !== 'H-01' || root.classList.contains('out')) return;
  if (arrivalEntryWait?.root === root && arrivalEntryWait.generation === renderGeneration) return;
  const owner = {nonce, root, epoch, generation:renderGeneration};
  arrivalEntryWait = owner;
  const finite = root.getAnimations({subtree:true}).filter(animation =>
    animation.effect && animation.effect.getTiming().iterations !== Infinity &&
    Number.isFinite(animation.effect.getComputedTiming().endTime) &&
    (animation.pending || animation.playState === 'running' || animation.playState === 'paused'));
  Promise.all(finite.map(animation => animation.finished)).then(() => {
    if (arrivalEntryWait !== owner) return;
    arrivalEntryWait = null;
    if (root.isConnected && $('#scr .scr.home') === root && epoch === owner.epoch &&
        renderGeneration === owner.generation && state.arrival?.admissionNonce === nonce)
      tryArrivalAdmission();
  }, error => {
    if (arrivalEntryWait === owner) {
      arrivalEntryWait = null;
    }
    if (!(error instanceof DOMException && error.name === 'AbortError')) throw error;
  });
}
const later = (ms, fn) => timers.push(setTimeout(fn, ms));
const clearTimers = () => {
  timers.forEach(clearTimeout);
  timers = [];
  document.querySelectorAll('#app > .toast').forEach(node => node.remove());
};
const icons = () => window.lucide && window.lucide.createIcons();

function fitWindow() {
  // The supplied artwork has two logical canvases. WebView's density can differ
  // from the car window's physical pixels; fit the same canvas without reflowing it.
  const viewportWidth = document.documentElement.clientWidth;
  const viewportHeight = document.documentElement.clientHeight;
  const width = viewportWidth > viewportHeight ? 1700 : 840;
  const height = 1184;
  const scale = Math.min(viewportWidth / width, viewportHeight / height);
  const app = $('#app');
  app.style.width = `${width}px`;
  app.style.height = `${height}px`;
  app.style.transformOrigin = '0 0';
  app.style.transform = `translate(${(viewportWidth - width * scale) / 2}px, ${(viewportHeight - height * scale) / 2}px) scale(${scale})`;
  document.body.style.background = getComputedStyle(app).backgroundColor;
}

function send(action, payload = {}) {
  if (arrivalReservation && action !== 'admitArrival') {
    arrivalDeferred.push(() => send(action, payload));
    return;
  }
  if (!Number.isSafeInteger(epoch) || !window.teum) {
    connectionError();
    return;
  }
  const token = state.journeyToken;
  const correlation = token ? { journeyId: token.sessionId, journeyRevision: token.revision } : {};
  const arrival = state.arrival?.token;
  const origin = typeof arrival === 'string' ? {arrivalToken:arrival} : {};
  try {
    window.teum.postMessage(JSON.stringify({
      requestId: `${epoch}-${++requestSequence}`, epoch, action, ...payload, ...correlation, ...origin
    }));
  } catch (error) {
    releaseArrivalReservation(true);
    if (error instanceof Error) { connectionError(); return; }
    throw error;
  }
}

function connectionError() {
  releaseArrivalReservation(true);
  const message = document.createElement('p');
  message.className = 'lead';
  message.setAttribute('role', 'alert');
  message.textContent = '앱 연결을 확인할 수 없어요. 앱을 다시 열어 주세요.';
  $('#scr').replaceChildren(message);
}

// A route is an intent. Native checks the current stage before publishing its route.
function go(id) {
  if (reviewingEntry() && state.screen === 'F3-01' && id === 'Q1-01') {
    chooseEntry('QUICK');
    return;
  }
  send('navigate', { route: id });
}

function paint(id, quiet = false) {
  const screen = S[id];
  if (!screen) return;
  clearGraphicGesture();
  const app = $('#app');
  app.dataset.theme = screen.dark ? 'dark' : state.theme;
  app.dataset.aura = typeof screen.aura === 'function' ? screen.aura(state) : (screen.aura || 'home');
  $('#aura').className = `aura ${screen.auraCls || ''}`;
  app.classList.toggle('driving', state.restricted === true);
  fitWindow();
  $('#scr').innerHTML = screen.view(state);
  document.querySelectorAll('[data-bind]').forEach(node => {
    if (drafts.has(node.dataset.bind)) node.value = drafts.get(node.dataset.bind);
  });
  if (quiet) $('#scr .scr')?.classList.add('quiet');
  icons();
  patchLive();
  scheduleArrivalAdmission();
}

function present(id, quiet) {
  clearGraphicGesture();
  clearTimers();
  if (!quiet) closeOverlay();
  const generation = ++renderGeneration;
  const old = $('#scr .scr');
  const keep = old?.querySelector('[data-keep]');
  const from = keep && { key: keep.dataset.keep, rect: keep.getBoundingClientRect() };
  if (!old || quiet || matchMedia('(prefers-reduced-motion: reduce)').matches) {
    paint(id, true);
    return;
  }
  old.classList.add('out');
  later(200, () => {
    if (generation !== renderGeneration) return;
    paint(id);
    const to = from && document.querySelector(`#scr [data-keep="${from.key}"]`);
    if (!to) return;
    to.closest('.vis')?.style.setProperty('animation', 'none');
    const rect = to.getBoundingClientRect(), scale = rect.width / to.offsetWidth || 1;
    to.animate([
      { transform: `translate(${(from.rect.x - rect.x) / scale}px, ${(from.rect.y - rect.y) / scale}px)` },
      { transform: 'none' }
    ], { duration: 640, easing: 'cubic-bezier(0.23, 1, 0.32, 1)' });
  });
}

const refresh = () => paint(state.screen, true);
function overlay(html) {
  if (html === overlayMarkup) return;
  clearGraphicGesture();
  overlayMarkup = html;
  $('#ov').innerHTML = html;
  icons();
  patchEntryControls();
}
function closeOverlay(animated = false) {
  activeOverlay = null;
  overlayMarkup = null;
  const node = $('#ov');
  if (!node.firstElementChild) return;
  if (!animated) { node.replaceChildren(); scheduleArrivalAdmission(); return; }
  node.firstElementChild.classList.add('leaving');
  later(200, () => { node.querySelector('.leaving')?.remove(); scheduleArrivalAdmission(); });
}
function toast(text, ms = 3000) {
  const node = document.createElement('div');
  node.className = 'toast glass';
  node.textContent = text;
  $('#app').appendChild(node);
  later(ms, () => { node.classList.add('out'); later(240, () => node.remove()); });
}

function patchLive() {
  patchEntryControls();
  // Native updates can change timers without replacing a focused input or restarting entry motion.
  document.querySelectorAll('[data-live]').forEach(node => {
    const path = node.dataset.live.split('.');
    const value = path.reduce((current, key) => current?.[key], state);
    if (typeof value === 'string' || typeof value === 'number') node.textContent = String(value);
  });
  const breathing = state.breathing;
  document.querySelectorAll('[data-live-breath-phase]').forEach(node => {
    node.textContent = ({INHALE:'들이쉬고',TOP_UP:'한 번 더',EXHALE:'길게 내쉬세요'})[breathing?.phase] || '호흡 안내 대기 중';
  });
  if (breathing && Number.isFinite(breathing.cycleElapsedMs) && breathing.cycleDurationMs > 0) {
    document.querySelectorAll('.breath .shape,.breath .rings').forEach(node => {
      node.getAnimations().forEach(animation => {
        animation.pause();
        animation.currentTime = 9000 * breathing.cycleElapsedMs / breathing.cycleDurationMs;
      });
    });
  }
}

window.teumReceive = message => {
  if (message?.type !== 'state' || !Number.isSafeInteger(message.epoch) ||
      typeof message.state?.screen !== 'string' || !S[message.state.screen]) return;
  if (Number.isSafeInteger(epoch) && message.epoch < epoch) return;
  const epochChanged = message.epoch !== epoch;
  if (epochChanged) {
    releaseArrivalReservation(true);
    arrivalAdmissions.clear();
  }
  const previous = state.screen;
  const selection = { pick: state.pick, tune: state.tune, slide: state.slide };
  epoch = message.epoch;
  Object.assign(state, message.state);
  if (graphicGesture && !graphicGesture.valid()) clearGraphicGesture();
  if (previous === state.screen) Object.assign(state, selection);
  // These read-only screens also depend on native metadata published between route revisions.
  // Keep second-by-second journey fields out so forms and breathing retain their DOM identity.
  let metadata = null;
  switch (state.screen) {
    case 'H-01':
      metadata = [state.slot, state.mood, state.intention, state.done, state.skipped,
        state.justDone, state.meetAt, state.week, state.context?.localDate,
        state.context?.greeting, state.context?.summary, state.context?.availableMinutes,
        state.context?.scheduleSummary, state.context?.slotTimes];
      break;
    case 'R-01':
      metadata = [state.week, state.day?.date];
      break;
    case 'R-02':
      metadata = [state.week, state.records, state.recordLabels, state.mood,
        state.selectedDate, state.day?.date, state.context?.localDate];
      break;
  }
  const presentation = JSON.stringify(metadata);
  const changed = previous !== state.screen || lastRenderRevision !== state.renderRevision ||
    lastPresentation !== presentation;
  lastRenderRevision = state.renderRevision;
  lastPresentation = presentation;
  if (changed) present(state.screen, previous === state.screen);
  else patchLive();
  if (activeOverlay && previous === state.screen) overlay(OVERLAYS[activeOverlay](state));
  if (state.notice) toast(state.notice);
  if (arrivalReservation && state.arrival?.admissionNonce !== arrivalReservation)
    releaseArrivalReservation();
  scheduleArrivalAdmission();
};

const command = command => send('journeyCommand', { command });
const journeyInput = (field, value) => send('journeyInput', { field, value: String(value) });
function reviewingEntry() {
  return state.journeyActive === true && state.journeyPhase === 'ENTRY_REVIEW' &&
    state.entry?.reviewing === true;
}
function originalEntryChoice(node) {
  const action = node.dataset.do;
  if (action?.startsWith('setsleep:')) return `NAP_${action.slice(9)}`;
  return {
    'start:F1-01':'MORNING_FULL', 'short:F1-02':'MORNING_SHORT',
    'start:F2-02':state.entry?.selectedChoice, 'start:F3-03':'PREPARATION',
    'start:F4-02':'EVENING_FULL', 'short:F4-02':'EVENING_SHORT'
  }[action] || (state.screen === 'F3-01' && node.dataset.go === 'Q1-01' ? 'QUICK' : null);
}
function entryChoiceAvailable(choice) {
  return state.entry?.canReconfigure === true && state.entry.choices?.[choice]?.enabled === true;
}
function patchEntryControls() {
  if (!reviewingEntry()) return;
  document.querySelectorAll('#scr button,#ov button').forEach(node => {
    const choice = originalEntryChoice(node);
    if (!choice) return;
    node.disabled = !entryChoiceAvailable(choice);
    node.setAttribute('aria-disabled', String(node.disabled));
    node.title = node.disabled ? state.entry.choices?.[choice]?.reason || '현재 선택할 수 없는 항목이에요' : '';
  });
}
function chooseEntry(choice, resume = false) {
  if (!entryChoiceAvailable(choice)) {
    toast(state.entry?.choices?.[choice]?.reason || '현재 선택할 수 없는 항목이에요');
    return;
  }
  if (resume && state.entry.selectedChoice === choice) command('NEXT');
  else send('reconfigureEntry', {choice});
}
const showOverlay = key => { activeOverlay = key; overlay(OVERLAYS[key](state)); };
const binding = name => {
  const input = document.querySelector(`[data-bind="${name}"]`);
  return input ? input.value : drafts.get(name);
};
function clockMinute(value) {
  if (!value) return -1;
  if (!/^\d{2}:\d{2}$/.test(value)) return null;
  const [hour, minute] = value.split(':').map(Number);
  return hour < 24 && minute < 60 ? hour * 60 + minute : null;
}
function schedule(walking = state.preferences.schedule.walkingReserveMinutes ?? -1) {
  const current = state.preferences.schedule;
  const work = clockMinute(binding('schedule:workStartMinute') ?? clock(current.workStartMinute));
  const lunch = clockMinute(binding('schedule:lunchReturnMinute') ?? clock(current.lunchReturnMinute));
  if (work === null || lunch === null) { toast('시간을 확인해 주세요'); return; }
  send('saveSchedule', { workStartMinute: work, lunchReturnMinute: lunch, walkingReserveMinutes: walking });
}
function saveBackrest(defaultRest = null) {
  const current = state.preferences.backrest;
  const rest = defaultRest ?? Number(binding('backrest:restDegrees') ?? current.restDegrees);
  const nap = Number(binding('backrest:napDegrees') ?? current.napDegrees);
  if (!Number.isInteger(rest) || rest < 106 || rest > 146 ||
      !Number.isInteger(nap) || nap < 106 || nap > 160) {
    toast('등받이 선호값 범위를 확인해 주세요'); return;
  }
  send('saveBackrest', { restDegrees: rest, napDegrees: nap });
}
function openJourney(route, short = false) {
  if (route === 'R-02' || route === 'F3-08') { go(route); return; }
  const journey = { 'F1-01':'am', 'F1-02':'am', 'F2-02':'noon',
    'F3-03':'meet', 'F4-02':'pm', 'Q1-01':'quick', am:'am', noon:'noon', meet:'meet', pm:'pm' }[route];
  if (journey) send(short ? 'openShortJourney' : 'openJourney', { journey });
}
function visualPick(key) {
  if (!MOODS[key]) return;
  state.pick = key;
  $('#app').dataset.aura = key;
  document.querySelectorAll('.mood').forEach(node => {
    const selected = node.dataset.do === `pick:${key}`;
    node.classList.toggle('on', selected); node.setAttribute('aria-pressed', String(selected));
  });
  const box = $('.pick-gfx');
  if (box) {
    box.innerHTML = G(MOODSHAPE[key], key, 380, {wrap:'float',rings:.25});
    $('.pick-name').textContent = MOODS[key][0];
    $('.pick-desc').textContent = MOODS[key][1];
    box.animate([{opacity:0,filter:'blur(6px)'},{opacity:1,filter:'blur(0)'}],
      {duration:320,easing:'cubic-bezier(0.23,1,0.32,1)'});
  }
}
Object.assign(ACT, {
  slide: () => { if (state.slide === 2) go('ON-03'); else { state.slide++; refresh(); } },
  slideBack: () => { if (state.slide === 0) go('ON-01'); else { state.slide--; refresh(); } },
  permissions: () => overlay(`<div class="modal-bg"><div class="modal glass">
    <h2 class="h2">필요할 때 권한을 확인해요</h2>
    <p class="body" style="margin-top:12px">말로 기록할 때 마이크를, 현재 장소를 등록할 때 위치를 요청해요.</p>
    <div class="acts">${two(sec('마이크 권한',null,'permission:MICROPHONE'),
      sec('위치 권한',null,'permission:LOCATION'))}${pri('계속하기','ON-04')}</div>
    </div></div>`),
  permission: permission => send('requestPermission', {permission}),
  completeOnboarding: () => send('completeOnboarding'),
  captureLocation: place => send('captureLocation', {home:place === 'home'}),
  savePlace: place => send('savePlaceLabel', {home:place === 'home',label:binding(`place:${place}`) ?? ''}),
  saveSchedule: () => schedule(),
  walk: delta => schedule(Math.max(1,Math.min(10,(state.preferences.schedule.walkingReserveMinutes ?? 0) + Number(delta)))),
  saveBackrest: () => saveBackrest(),
  defaultBackrest: () => saveBackrest(118),
  saveSeat: () => saveBackrest(),
  seat: delta => {
    const nap = $('.seatctl')?.dataset.seatKind === 'nap';
    const key = `backrest:${nap ? 'napDegrees' : 'restDegrees'}`;
    const current = Number(drafts.get(key) ?? state.preferences.backrest[nap ? 'napDegrees' : 'restDegrees']);
    const value = Math.max(106,Math.min(nap ? 160 : 146,current + Number(delta)));
    drafts.set(key,String(value));
    $('.seatctl .rot')?.style.setProperty('transform',`rotate(${value - 118}deg)`);
    $('.seatctl .sil')?.setAttribute('d',SEATPATH(value));
    $('#seatDeg').textContent = `${value}°`;
  },
  sound: sound => send('selectSound',{sound}),
  flag: flag => {
    const p = state.preferences;
    const values = {VOICE_GUIDE:p.voiceGuide,MORNING:p.notifications.morning,
      NAP:p.notifications.nap,PREPARATION:p.notifications.preparation,EVENING:p.notifications.evening,
      ASK_BEFORE_HOME_ARRIVAL:p.notifications.askBeforeHomeArrival,
      DELETE_EVENING_STORY_AFTER_NEXT_MORNING:p.privacy.deleteEveningStoryAfterNextMorning};
    send('setPreferenceFlag',{flag,enabled:!values[flag]});
  },
  atmosphere: atmosphere => send('selectAtmosphere',{atmosphere}),
  workFromHomeDay: day => send('workFromHomeDay',
    {day,enabled:!state.preferences.schedule.workFromHomeDays.includes(day)}),
  start: (route, node) => reviewingEntry()
    ? chooseEntry(originalEntryChoice(node), true) : openJourney(route),
  begin: journey => openJourney(journey),
  short: (route, node) => reviewingEntry()
    ? chooseEntry(originalEntryChoice(node)) : openJourney(route,true),
  skip: journey => send('skipSuggestion',{journey}),
  feel: value => journeyInput('FEELING',value.toUpperCase()),
  listen: () => command('RECORD_SPEECH'),
  doneTalk: () => command('STOP_SPEECH'),
  again: () => command('RETRY_SPEECH'),
  afterTodo: () => command('NEXT'),
  pick: visualPick,
  setMood: () => journeyInput('ATMOSPHERE',state.pick.toUpperCase()),
  daycolor: value => journeyInput('ATMOSPHERE',value.toUpperCase()),
  toSleep: () => command('NEXT'),
  finish: (journey,node) => {
    if (journey === 'q1') {
      const mood = {'답답해요':'STIFLED','억울해요':'WRONGED','지쳤어요':'TIRED','괜찮아졌어요':'BETTER'}[node.dataset.value];
      if (mood) journeyInput('FEELING',mood);
      return;
    }
    if (journey === 'noon') send('acknowledgeWake');
    command('NEXT');
  },
  awake: () => send('acknowledgeWake'),
  wakeNow: () => command('WAKE_NOW'),
  eyesBack: () => command('OPEN_EYES'),
  meetAt: delta => {
    const current = clockMinute(binding('meetingMinute'));
    if (current === -1 || current === null) { $('[data-bind="meetingMinute"]').focus(); return; }
    const minute = Math.max(0,Math.min(1439,current + Number(delta)));
    drafts.set('meetingMinute',clock(minute));
    $('[data-bind="meetingMinute"]').value = clock(minute);
  },
  saveMeet: () => {
    const minute = clockMinute(binding('meetingMinute'));
    if (minute === null || minute === -1) { toast('일정 시각을 골라 주세요'); return; }
    journeyInput('MEETING_MINUTE',minute);
  },
  meetDone: value => send('meetingFeedback',{value:['well','okay','hard'][Number(value)]}),
  sleeptime: () => showOverlay('sleeptime'),
  setsleep: value => {
    if (reviewingEntry() && !entryChoiceAvailable(`NAP_${value}`)) return;
    journeyInput('NAP_MINUTES',value);
  },
  closeRefresh: () => closeOverlay(true),
  peek: () => showOverlay('peek'),
  end: () => showOverlay('end'),
  door: () => showOverlay('door'),
  recover: () => go('C-03'),
  resume: () => closeOverlay(true),
  stop: () => send('finishEarly'),
  approveRestore: () => send('approveRestore',{consent:true}),
  declineRestore: () => send('approveRestore',{consent:false}),
  saveJourney: () => send('saveJourney'),
  home: () => go('H-01'),
  readState: () => send('state'),
  day: date => send('selectDay',{date}),
  wipeAsk: () => showOverlay('wipeAsk'),
  wipe: () => send('clearRecords'),
  tune: value => {
    const index = Number(value);
    if (!state.music?.choices?.[index]?.available) return;
    state.tune = index; refresh();
  },
  playMusic: () => {
    journeyInput('MUSIC_CHOICE',['SUPPORT_SONG','RECENT','RECOMMENDATION'][state.tune]);
    command('PLAY_MUSIC');
  }
});

const rubber = (d, lim = 150) => d * lim * .55 / (lim + .55 * Math.abs(d));
function graphicEligible(g) {
  return g.isConnected && $('#scr').contains(g) && !g.closest('.scr.out') &&
    state.hardware?.checks?.parking?.status === 'confirmed' && state.restricted !== true &&
    !state.permissions?.requestPending && !activeOverlay && !$('#ov').firstElementChild &&
    document.visibilityState === 'visible' && document.hasFocus();
}
function clearGraphicGesture() {
  const owner = graphicGesture;
  if (!owner) return;
  graphicGesture = null;
  owner.events.abort();
  owner.observer.disconnect();
  owner.media.removeEventListener('change', owner.reduce);
  if (owner.pointerId !== null && owner.g.hasPointerCapture(owner.pointerId))
    owner.g.releasePointerCapture(owner.pointerId);
  owner.snap?.cancel();
  owner.g.style.transform = owner.transform;
  owner.g.style.transition = owner.transition;
}
document.addEventListener('pointerdown', event => {
  const g = event.target.closest('.gfx, .hero-gfx');
  if (!event.isTrusted || !event.isPrimary || event.button !== 0 || !g ||
      event.target.closest('button, .seat, .modal') || !graphicEligible(g) ||
      (graphicGesture && graphicGesture.pointerId !== null)) return;
  const k = g.getBoundingClientRect().width / g.offsetWidth || 1;
  const m = new DOMMatrix(getComputedStyle(g).transform), x0 = event.clientX, y0 = event.clientY;
  clearGraphicGesture();
  g.getAnimations().forEach(animation => { if (animation.id === 'snap') animation.cancel(); });
  const owner = {
    g, target:event.target, root:g.closest('.scr'), epoch, generation:renderGeneration,
    pointerId:event.pointerId, transform:g.style.transform, transition:g.style.transition,
    events:new AbortController(), media:matchMedia('(prefers-reduced-motion: reduce)'), snap:null
  };
  owner.valid = () => graphicGesture === owner && epoch === owner.epoch &&
    renderGeneration === owner.generation && owner.target.isConnected &&
    g.contains(owner.target) && g.closest('.scr') === owner.root && graphicEligible(g);
  const idle = () => {
    if (graphicGesture !== owner) return;
    const valid = owner.valid();
    clearGraphicGesture();
    if (valid) scheduleArrivalAdmission();
  };
  owner.reduce = () => { if (owner.media.matches && owner.snap) idle(); };
  owner.media.addEventListener('change', owner.reduce);
  owner.observer = new MutationObserver(() => { if (!owner.valid()) clearGraphicGesture(); });
  owner.observer.observe($('#scr'), {childList:true,subtree:true});
  graphicGesture = owner;
  const set = (dx, dy) => {
    g.style.transition = 'none';
    g.style.transform = `translate(${m.e + rubber(dx / k)}px, ${m.f + rubber(dy / k)}px) scale(.95)`;
  };
  const move = ev => {
    if (ev.pointerId !== owner.pointerId) return;
    if (!owner.valid()) { clearGraphicGesture(); return; }
    set(ev.clientX - x0, ev.clientY - y0);
  };
  const up = ev => {
    if (ev.pointerId !== owner.pointerId) return;
    if (!owner.valid()) { clearGraphicGesture(); return; }
    const pointerId = owner.pointerId;
    owner.pointerId = null;
    owner.events.abort();
    if (g.hasPointerCapture(pointerId)) g.releasePointerCapture(pointerId);
    const from = g.style.transform;
    g.style.transform = owner.transform;
    g.style.transition = owner.transition;
    if (owner.media.matches) { idle(); return; }
    owner.snap = g.animate([{transform:from},{transform:'none'}],
      {duration:640,easing:'cubic-bezier(.34, 1.56, .64, 1)'});
    owner.snap.id = 'snap';
    owner.snap.finished.then(idle, error => {
      if (error instanceof DOMException && error.name === 'AbortError') { idle(); return; }
      if (graphicGesture === owner) clearGraphicGesture();
      throw error;
    });
  };
  window.addEventListener('pointermove', move, {signal:owner.events.signal});
  window.addEventListener('pointerup', up, {signal:owner.events.signal});
  window.addEventListener('pointercancel', up, {signal:owner.events.signal});
  g.addEventListener('lostpointercapture', up, {signal:owner.events.signal});
  g.setPointerCapture(event.pointerId);
  set(0, 0);
});

document.addEventListener('input',event => {
  const key = event.target.dataset.bind;
  if (key) drafts.set(key,event.target.value);
});
document.addEventListener('change',event => {
  if (event.target.dataset.bind === 'restSound') send('selectSound',{sound:event.target.value});
});
document.addEventListener('keydown',event => {
  if (!['Enter',' '].includes(event.key) || !event.target.matches('[role="button"][data-do]')) return;
  event.preventDefault(); event.target.click();
});

document.addEventListener('click', event => {
  const target = event.target.closest('[data-go],[data-do]');
  if (!target || target.disabled) return;
  if (target.dataset.do) {
    const [action, ...parts] = target.dataset.do.split(':');
    const handler = ACT[action];
    if (arrivalReservation && handler) {
      arrivalDeferred.push(() => handler(parts.join(':'), target));
      return;
    }
    if (handler) handler(parts.join(':'), target);
    else toast('이 동작을 연결할 수 없어요');
    return;
  }
  if (target.dataset.go) {
    if (state.journeyActive && target.closest('#scr .top') &&
        (state.journeyPhase === 'INTENTION_REVIEW' && target.dataset.go === 'F1-04' ||
         state.journeyPhase === 'MUSIC_FOCUS' && target.dataset.go === 'F3-06')) {
      command('PREVIOUS');
      return;
    }
    go(target.dataset.go);
  }
});

window.addEventListener('resize', fitWindow);
window.addEventListener('pagehide', () => { clearGraphicGesture(); releaseArrivalReservation(true); });
window.addEventListener('focus', scheduleArrivalAdmission);
window.addEventListener('blur', () => { clearGraphicGesture(); releaseArrivalReservation(true); });
document.addEventListener('focusout', scheduleArrivalAdmission);
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState !== 'visible') { clearGraphicGesture(); releaseArrivalReservation(true); }
  else scheduleArrivalAdmission();
});
fitWindow();
send('state');
