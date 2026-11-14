// The document owns presentation. Android owns permissions, phases, time and saved facts.
const $ = selector => document.querySelector(selector);
const state = { screen: null, slide: 0 };
const ACT = {};
const springOf = (zeta, response, n = 48) => {
  const w = 2 * Math.PI / response, critical = zeta >= 1;
  const wd = critical ? 0 : w * Math.sqrt(1 - zeta * zeta);
  const duration = critical ? 9.23 / w : Math.log(1000) / (zeta * w);
  const x = t => critical ? 1 - (1 + w * t) * Math.exp(-w * t) :
    1 - Math.exp(-zeta * w * t) * (Math.cos(wd * t) + zeta * w / wd * Math.sin(wd * t));
  return {ease:`linear(${Array.from({length:n + 1}, (_, i) =>
    i === n ? '1' : x(duration * i / n).toFixed(4)).join(', ')})`, ms:Math.round(duration * 1000)};
};
const SPRING = springOf(.86, .52), POP = springOf(.62, .38);
const RISE = springOf(1, .4), SHEET = springOf(.8, .3);
for (const [name, spring] of Object.entries({spring:SPRING, 'spring-pop':POP, rise:RISE, sheet:SHEET})) {
  document.documentElement.style.setProperty(`--${name}`, spring.ease);
  document.documentElement.style.setProperty(`--${name === 'spring-pop' ? 'pop' : name}-ms`, `${spring.ms}ms`);
}
const reducedMotion = () => matchMedia('(prefers-reduced-motion: reduce)').matches;
const freeze = node => {
  if (!node.getAnimations().length) return;
  const computed = getComputedStyle(node);
  node.style.opacity = computed.opacity;
  node.style.transform = computed.transform;
};
const LENS = {c:[100,100,15,35,8,34],p:[300,100,13,37,7,26],b:[500,100,7,42,4,14]};
document.body.insertAdjacentHTML('beforeend', `<svg width="0" height="0" style="position:absolute" aria-hidden="true">${Object.entries(LENS).map(([key,[width,height,inset,radius,blur,scale]]) => {
  const map = `<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 ${width} ${height}' preserveAspectRatio='none'><defs><linearGradient id='x'><stop offset='0' stop-color='#000'/><stop offset='1' stop-color='#f00'/></linearGradient><linearGradient id='y' x2='0' y2='1'><stop offset='0' stop-color='#000'/><stop offset='1' stop-color='#0f0'/></linearGradient><filter id='b'><feGaussianBlur stdDeviation='${blur}'/></filter></defs><rect width='${width}' height='${height}' fill='url(#x)'/><rect width='${width}' height='${height}' fill='url(#y)' style='mix-blend-mode:screen'/><rect x='${inset}' y='${inset}' width='${width - 2 * inset}' height='${height - 2 * inset}' rx='${radius}' fill='rgb(128,128,128)' filter='url(#b)'/></svg>`;
  return `<filter id="lens-${key}" x="0" y="0" width="1" height="1" color-interpolation-filters="sRGB"><feImage href="data:image/svg+xml,${encodeURIComponent(map)}" x="0" y="0" width="100%" height="100%" preserveAspectRatio="none" result="m"/><feDisplacementMap in="SourceGraphic" in2="m" scale="${scale}" xChannelSelector="R" yChannelSelector="G"/></filter>`;
}).join('')}</svg>`);
if (navigator.userAgentData?.brands?.some(brand => brand.brand === 'Chromium'))
  document.documentElement.classList.add('refract');
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
let pendingPrevious = new Map();
let inFlightPresentation = null;
let pendingPresentationRefresh = false;
const arrivalAdmissions = new Set();
let arrivalDeferred = [];
let graphicGesture = null;
let seatGesture = null;
let timePicker = null;
let homeSelectionSequence = 0;
let lastProgress = null;
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
      arrivalAdmissions.has(nonce) || arrivalReservation || graphicGesture || seatGesture || timePicker || state.screen !== 'H-01' ||
      state.journeyToken || document.visibilityState !== 'visible' || !document.hasFocus() ||
      !$('#scr .scr.home') || $('#scr .scr.leaving') || activeOverlay || $('#ov').firstElementChild ||
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
  const requestId = `${epoch}-${++requestSequence}`;
  const expectsPreviousReceipt = action === 'journeyCommand' && payload.command === 'PREVIOUS';
  if (expectsPreviousReceipt) {
    const pending = {requestId, epoch, journeyId: token?.sessionId ?? null,
      revision: token?.revision ?? null, fromScreen: state.screen};
    for (const [id, entry] of pendingPrevious) {
      if (entry.epoch !== pending.epoch || entry.journeyId !== pending.journeyId ||
          entry.revision !== pending.revision || entry.fromScreen !== pending.fromScreen)
        pendingPrevious.delete(id);
    }
    pendingPrevious.set(requestId, pending);
    if (pendingPrevious.size > 8) pendingPrevious.delete(pendingPrevious.keys().next().value);
  }
  try {
    window.teum.postMessage(JSON.stringify({
      requestId, epoch, action, ...payload, ...correlation, ...origin
    }));
  } catch (error) {
    if (expectsPreviousReceipt) pendingPrevious.delete(requestId);
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

function paint(id, quiet = false, old = null) {
  const screen = S[id];
  if (!screen) return;
  clearGraphicGesture();
  clearSeatGesture();
  const app = $('#app');
  const theme = screen.dark ? 'dark' : state.theme;
  const flip = old && app.dataset.theme !== theme;
  if (flip) old.dataset.theme = app.dataset.theme;
  app.dataset.theme = theme;
  app.dataset.aura = typeof screen.aura === 'function' ? screen.aura(state) : (screen.aura || 'home');
  $('#aura').className = `aura ${screen.auraCls || ''}`;
  app.classList.toggle('driving', state.restricted === true);
  fitWindow();
  const box = $('#scr'), template = document.createElement('template');
  template.innerHTML = screen.view(state);
  const incoming = template.content.firstElementChild;
  if (incoming) incoming.dataset.route = id;
  const focused = document.activeElement;
  const focusKey = box.contains(focused) ? focused.dataset.bind : null;
  const selection = focusKey && ['text', 'search', 'tel', 'url', 'password'].includes(focused.type)
    ? [focused.selectionStart, focused.selectionEnd] : null;
  if (old) {
    const before = old.querySelector(':scope > .top'), after = incoming?.querySelector(':scope > .top');
    const bare = header => header && !header.matches('.home-top') && !header.querySelector('h1,h2');
    const signature = node => node.matches('.track') ? 'track' : node.matches('button')
      ? (node.getAttribute('aria-label') || node.textContent).replace(/이전|뒤로/, '<').replace(/끝내기|닫기/, 'x') : '';
    if (bare(before) && bare(after)) {
      before.classList.add('keep'); after.classList.add('keep');
      [...after.children].forEach((node, index) => {
        const previous = before.children[index];
        const same = !flip && !!previous && signature(previous) === signature(node);
        previous?.classList.toggle('same', same);
        node.classList.toggle('fresh', !same);
      });
    }
    if (old.classList.contains('entering')) {
      [...old.children].forEach(freeze);
      old.classList.remove('entering');
    }
    old.classList.add('leaving');
    old.inert = true;
    box.prepend(template.content);
    setTimeout(() => { old.remove(); scheduleArrivalAdmission(); }, 220);
  } else box.replaceChildren(template.content);
  incoming?.querySelectorAll('[data-bind]').forEach(node => {
    if (drafts.has(node.dataset.bind)) node.value = drafts.get(node.dataset.bind);
  });
  if (quiet) incoming?.classList.add('quiet');
  else incoming?.classList.add('entering');
  if (focusKey && quiet) {
    const replacement = [...incoming.querySelectorAll('[data-bind]')].find(node => node.dataset.bind === focusKey);
    replacement?.focus({preventScroll:true});
    if (replacement && selection) replacement.setSelectionRange(...selection);
  }
  icons();
  const dot = incoming?.querySelector('.track .dot');
  if (dot) {
    const progress = Number(dot.dataset.p);
    if (!quiet && lastProgress !== null && lastProgress !== progress && !reducedMotion()) {
      const width = dot.parentElement.offsetWidth;
      dot.animate([{transform:`translateX(${lastProgress * width}px)`},
        {transform:`translateX(${progress * width}px)`}], {duration:SPRING.ms, easing:SPRING.ease});
    }
    lastProgress = progress;
  } else lastProgress = null;
  patchLive();
  scheduleArrivalAdmission();
  return incoming;
}

function present(id, quiet, direction = 1) {
  clearGraphicGesture();
  clearSeatGesture();
  clearTimers();
  if (!quiet) closeOverlay();
  const generation = ++renderGeneration;
  inFlightPresentation = null;
  pendingPresentationRefresh = false;
  $('#scr').querySelectorAll(':scope > .leaving').forEach(node => node.remove());
  const old = $('#scr > .scr');
  if (!quiet && old && S[id].group && S[id].group === S[old.dataset.route]?.group &&
      $('#app').offsetWidth >= 1200 && old.querySelector('.md-detail')) {
    const template = document.createElement('template');
    template.innerHTML = S[id].view(state);
    const next = template.content.firstElementChild, detail = next.querySelector('.md-detail');
    old.className = next.className;
    old.dataset.route = id;
    old.querySelector('.top').replaceWith(next.querySelector('.top'));
    const links = [...next.querySelectorAll('.md-nav button')];
    old.querySelectorAll('.md-nav button').forEach((node,index) => { node.className = links[index].className; });
    old.querySelector('.md-detail').replaceWith(detail);
    detail.querySelectorAll('[data-bind]').forEach(node => {
      if (drafts.has(node.dataset.bind)) node.value = drafts.get(node.dataset.bind);
    });
    detail.animate(reducedMotion() ? [{opacity:0},{opacity:1}] :
      [{opacity:0,transform:'translateY(8px)'},{opacity:1,transform:'none'}],
    {duration:240,easing:'cubic-bezier(.23,1,.32,1)'});
    clearPresentationDirection();
    icons();
    patchLive();
    return;
  }
  const keep = old?.querySelector('[data-keep]');
  const from = keep && { key: keep.dataset.keep, rect: keep.getBoundingClientRect() };
  if (!old || quiet) {
    clearPresentationDirection();
    paint(id, true);
    return;
  }
  $('#app').style.setProperty('--dir', String(direction));
  inFlightPresentation = {screen: id, generation};
  const incoming = paint(id, false, old);
  requestAnimationFrame(() => {
    if (generation !== renderGeneration) return;
    const entrances = [...incoming.children].flatMap(node => node.getAnimations())
      .filter(animation => Number.isFinite(animation.effect?.getComputedTiming().endTime));
    Promise.allSettled(entrances.map(animation => animation.finished)).then(() => {
      if (generation !== renderGeneration) return;
      incoming.classList.remove('entering');
      clearPresentationDirection();
      inFlightPresentation = null;
      if (pendingPresentationRefresh) {
        pendingPresentationRefresh = false;
        present(state.screen, true);
      }
      scheduleArrivalAdmission();
    });
  });
  const to = from && [...incoming.querySelectorAll('[data-keep]')].find(node => node.dataset.keep === from.key);
  if (to && !reducedMotion()) {
    const source = keep.getAnimations({subtree:true}), used = new Set();
    to.getAnimations({subtree:true}).forEach(animation => {
      const previous = source.find(candidate => !used.has(candidate) &&
        animation.animationName && candidate.animationName === animation.animationName);
      if (previous) { used.add(previous); animation.currentTime = previous.currentTime; }
    });
    keep.style.visibility = 'hidden';
    to.closest('.vis')?.style.setProperty('animation', 'none');
    for (let node = to; node.parentElement && !node.matches('.vis'); node = node.parentElement)
      [...node.parentElement.children].forEach(sibling => { if (sibling !== node) sibling.classList.add('x-fade'); });
    const rect = to.getBoundingClientRect(), scale = rect.width / to.offsetWidth || 1;
    to.animate([
      { transform: `translate(${(from.rect.x - rect.x) / scale}px, ${(from.rect.y - rect.y) / scale}px)` },
      { transform: 'none' }
    ], { duration: SPRING.ms, easing: SPRING.ease });
  }
}

function clearPresentationDirection() {
  $('#app').style.removeProperty('--dir');
}

const refresh = () => paint(state.screen, true);
function overlay(html) {
  if (html === overlayMarkup) return;
  if (activeOverlay === 'sleeptime' && $('#ov .time-pick')) {
    const template = document.createElement('template');
    template.innerHTML = html;
    const next = template.content;
    const number = $('#ov .time-pick .dotnum'), updated = next.querySelector('.time-pick .dotnum');
    if (number && updated) {
      overlayMarkup = html;
      if (number.innerHTML !== updated.innerHTML) dissolve(number,updated.innerHTML);
      const ruler = $('#ov .ruler b'), nextRuler = next.querySelector('.ruler b');
      if (ruler && nextRuler) ruler.style.cssText = nextRuler.style.cssText;
      for (const selector of ['.modal > .body','.modal > .acts']) {
        const before = $('#ov').querySelector(selector), after = next.querySelector(selector);
        if (before && after && before.innerHTML !== after.innerHTML) before.innerHTML = after.innerHTML;
      }
      const choices = [...next.querySelectorAll('.seg3 button')];
      $('#ov').querySelectorAll('.seg3 button').forEach((node,index) => {
        node.className = choices[index].className;
        node.setAttribute('aria-pressed',String(node.classList.contains('on')));
      });
      icons();
      patchLive();
      return;
    }
  }
  clearGraphicGesture();
  clearSeatGesture();
  clearTimePicker();
  overlayMarkup = html;
  $('#ov').innerHTML = html;
  $('#app').classList.toggle('focus', !!$('#ov .modal-bg'));
  icons();
  patchLive();
}
function closeOverlay(animated = false) {
  activeOverlay = null;
  overlayMarkup = null;
  clearTimePicker();
  $('#app').classList.remove('focus');
  const node = $('#ov');
  if (!node.firstElementChild) return;
  if (!animated) { node.replaceChildren(); scheduleArrivalAdmission(); return; }
  node.firstElementChild.classList.add('leaving');
  const leaving = node.firstElementChild;
  setTimeout(() => { leaving.remove(); scheduleArrivalAdmission(); }, 200);
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
  document.querySelectorAll('[data-live-dots]').forEach(node => {
    const value = node.dataset.liveDots.split('.').reduce((current, key) => current?.[key], state);
    const text = typeof value === 'string' || typeof value === 'number' ? String(value) : '—';
    if (node.dataset.shownValue === text) return;
    node.dataset.shownValue = text;
    dissolve(node, text === '—' ? text : DOTS(text, Number(node.dataset.dotsHeight) || 104));
  });
  document.querySelectorAll('[data-schedule-minute]').forEach(node => {
    const minute = state.preferences?.schedule?.[node.dataset.scheduleMinute];
    const text = Number.isInteger(minute) && minute >= 0 && minute <= 1439 ? clock(minute) : '—';
    if (node.dataset.shownValue === text) return;
    node.dataset.shownValue = text;
    dissolve(node, text === '—' ? text : DOTS(text, 60));
    node.closest('.tcard')?.setAttribute('aria-label',
      `${node.closest('.tcard').querySelector('.cap')?.textContent || '시간'} 시간 바꾸기, 현재 ${text}`);
  });
  const walk = $('#walkV'), walking = state.preferences?.schedule?.walkingReserveMinutes;
  if (walk && walk.textContent !== `${walking ?? '—'}분`) dissolve(walk, `${walking ?? '—'}분`);
  document.querySelectorAll('[data-music-progress]').forEach(node => {
    const ratio = state.music?.durationMs > 0
      ? Math.max(0, Math.min(1, (state.music.positionMs || 0) / state.music.durationMs)) : 0;
    node.style.background = `linear-gradient(to right,var(--ink-2) ${ratio * 100}%,var(--line) 0)`;
    node.setAttribute('aria-label', state.music?.statusLabel || '재생 상태 미확인');
    node.querySelector('b').style.transform = `translateX(${ratio * 440}px)`;
  });
  // Native updates can change timers without replacing a focused input or restarting entry motion.
  document.querySelectorAll('[data-live]').forEach(node => {
    const path = node.dataset.live.split('.');
    const value = path.reduce((current, key) => current?.[key], state);
    if ((typeof value === 'string' || typeof value === 'number') && node.textContent !== String(value))
      node.textContent = String(value);
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

function previousReceiptMatches(receipt, message) {
  if (receipt === null || typeof receipt !== 'object' || Array.isArray(receipt) ||
      typeof receipt.requestId !== 'string') return null;
  const pending = pendingPrevious.get(receipt.requestId);
  const before = state.journeyToken;
  const after = message.state.journeyToken;
  const matches = pending !== undefined &&
    Number.isSafeInteger(receipt.requestEpoch) && receipt.requestEpoch === pending.epoch &&
      receipt.requestEpoch === message.epoch &&
    typeof receipt.journeyId === 'string' && receipt.journeyId === pending.journeyId &&
      before?.sessionId === pending.journeyId && after?.sessionId === pending.journeyId &&
    Number.isSafeInteger(pending.revision) && Number.isSafeInteger(receipt.fromRevision) &&
      receipt.fromRevision === pending.revision && before?.revision === pending.revision &&
    Number.isSafeInteger(receipt.toRevision) && after?.revision === receipt.toRevision &&
      receipt.toRevision === pending.revision + 1 &&
    typeof receipt.fromScreen === 'string' && receipt.fromScreen === pending.fromScreen &&
      receipt.fromScreen === state.screen &&
    typeof receipt.toScreen === 'string' && receipt.toScreen === message.state.screen;
  return matches ? pending : null;
}

function previousExpectationStillBefore(pending, next) {
  const token = next?.journeyToken;
  return next?.journeyActive === true &&
    token?.sessionId === pending.journeyId && token?.revision === pending.revision &&
    next.screen === pending.fromScreen;
}

function retirePreviousExpectations(next) {
  for (const [id, pending] of pendingPrevious) {
    if (!previousExpectationStillBefore(pending, next)) pendingPrevious.delete(id);
  }
}

window.teumReceive = message => {
  if (message?.type !== 'state' || !Number.isSafeInteger(message.epoch) ||
      typeof message.state?.screen !== 'string' || !S[message.state.screen]) return;
  if (Number.isSafeInteger(epoch) && message.epoch < epoch) return;
  const epochChanged = message.epoch !== epoch;
  if (epochChanged) {
    pendingPrevious.clear();
    releaseArrivalReservation(true);
    arrivalAdmissions.clear();
  }
  const previous = state.screen;
  const receipt = message.previousTransition;
  const reverse = previousReceiptMatches(receipt, message) !== null;
  if (receipt !== undefined && typeof receipt?.requestId === 'string')
    pendingPrevious.delete(receipt.requestId);
  // Same-owner/same-revision/same-screen ticks keep every correlation for that before state.
  retirePreviousExpectations(message.state);
  const selection = { pick: state.pick, tune: state.tune, slide: state.slide, homePick: state.homePick };
  epoch = message.epoch;
  Object.assign(state, message.state);
  if (graphicGesture && !graphicGesture.valid()) clearGraphicGesture();
  if (seatGesture && !seatGesture.valid()) clearSeatGesture();
  if (previous === state.screen) Object.assign(state, selection);
  if (epochChanged || state.screen !== 'H-01') state.homePick = null;
  if (timePicker && (timePicker.epoch !== epoch || timePicker.screen !== state.screen ||
      state.restricted || state.permissions?.requestPending)) closeOverlay();
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
    case 'F4-03':
      // Live day facts only; localNow is patched through data-live so a clock tick never rebuilds the screen.
      metadata = [state.week, state.records, state.recordLabels, state.context?.localDate, state.dayColor];
      break;
  }
  const presentation = JSON.stringify(metadata);
  const routeChanged = previous !== state.screen;
  const presentationChanged = lastPresentation !== presentation;
  const changed = routeChanged || lastRenderRevision !== state.renderRevision ||
    presentationChanged;
  lastRenderRevision = state.renderRevision;
  lastPresentation = presentation;
  // A same-target publication during its real route presentation updates live facts without
  // cancelling the incoming screen. Full metadata refreshes wait until that presentation settles.
  const sameTargetInFlight = !routeChanged && !epochChanged &&
    inFlightPresentation?.generation === renderGeneration &&
    inFlightPresentation.screen === state.screen;
  if (changed && sameTargetInFlight) {
    // Revision-only updates still carry rendered state outside data-live; refresh after this
    // presentation settles. Unchanged ticks fall through to patchLive only.
    pendingPresentationRefresh = true;
    patchLive();
  } else if (changed) {
    present(state.screen, previous === state.screen, reverse ? -1 : 1);
  } else patchLive();
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
function schedule(walking = state.preferences.schedule.walkingReserveMinutes ?? -1, override = {}) {
  const current = state.preferences.schedule;
  const work = clockMinute(override.workStartMinute ?? clock(current.workStartMinute));
  const lunch = clockMinute(override.lunchReturnMinute ?? clock(current.lunchReturnMinute));
  if (work === null || lunch === null) { toast('시간을 확인해 주세요'); return; }
  send('saveSchedule', { workStartMinute: work, lunchReturnMinute: lunch, walkingReserveMinutes: walking });
}
function slideTo(direction) {
  const visual = $('#scr .slide-vis'), copyBox = $('#scr .slide-copy');
  if (!visual || !copyBox) { refresh(); return; }
  $('#app').dataset.aura = SLIDE[state.slide][2];
  $('#app').style.setProperty('--dir', String(direction));
  const swap = (box, html) => {
    box.querySelectorAll(':scope > .x-out').forEach(node => node.remove());
    box.querySelectorAll(':scope > :not(.ghost)').forEach(node => {
      freeze(node);
      node.classList.remove('x-in');
      node.classList.add('x-out');
      node.inert = true;
      setTimeout(() => node.remove(), 200);
    });
    const template = document.createElement('template');
    template.innerHTML = html;
    [...template.content.children].forEach(node => node.classList.add('x-in'));
    box.append(template.content);
  };
  swap(visual, SLIDEVIS[state.slide](state));
  swap(copyBox, SCOPY(state.slide));
  const track = $('#scr .track'), template = document.createElement('template');
  template.innerHTML = bar('x', state.slide + 1, 8);
  const nextTrack = template.content.querySelector('.track');
  if (track && nextTrack) {
    track.replaceWith(nextTrack);
    const dot = nextTrack.querySelector('.dot'), progress = Number(dot.dataset.p);
    if (lastProgress !== null && !reducedMotion()) {
      const width = nextTrack.offsetWidth;
      dot.animate([{transform:`translateX(${lastProgress * width}px)`},
        {transform:`translateX(${progress * width}px)`}], {duration:SPRING.ms, easing:SPRING.ease});
    }
    lastProgress = progress;
  }
  icons();
  patchLive();
}
function setSeat(degrees) {
  const box = $('#scr .seatctl');
  if (!box || state.restricted || !Number.isFinite(degrees)) return;
  const nap = box.dataset.seatKind === 'nap', field = nap ? 'napDegrees' : 'restDegrees';
  if (!Number.isFinite(state.preferences?.backrest?.[field])) return;
  const value = Math.max(106, Math.min(nap ? 160 : 146, Math.round(degrees)));
  drafts.set(`backrest:${field}`, String(value));
  box.querySelector('.rot')?.style.setProperty('transform', `rotate(${value - 118}deg)`);
  box.querySelector('.sil')?.setAttribute('d', SEATPATH(value));
  box.querySelectorAll('.echo path').forEach((node, index) => {
    node.setAttribute('d', SEATPATH(106 + (value - 106) * [.2,.4,.6,.8][index]));
  });
  box.querySelector('.arc-on')?.style.setProperty('stroke-dasharray', `${value - 106} 54`);
  const label = box.querySelector('#seatDeg');
  if (label) label.textContent = `${value}°`;
}
const scheduleField = key => ({workAt:'workStartMinute', lunchAt:'lunchReturnMinute',
  workStartMinute:'workStartMinute', lunchReturnMinute:'lunchReturnMinute'})[key];
function clearTimePicker() {
  if (!timePicker) return;
  timePicker.events.abort();
  timePicker = null;
}
function pickTime(key) {
  const field = scheduleField(key);
  if (!field || state.restricted) return;
  const saved = clockMinute(clock(state.preferences?.schedule?.[field]));
  const selected = Number.isInteger(saved) && saved >= 0 ? saved : 0;
  const label = field === 'workStartMinute' ? '출근' : '점심 복귀';
  const wheel = (length, kind) => `<div class="wheel" data-k="${kind}" role="group" aria-label="${kind === 'h' ? '시' : '분'}"><div class="wheel-in">${Array.from({length}, (_, i) => {
    const value = String(i).padStart(2, '0');
    return `<button class="wv" data-v="${value}" aria-label="${i}${kind === 'h' ? '시' : '분'}">${DOTS(value,52)}</button>`;
  }).join('')}</div></div>`;
  overlay(`<div class="modal-bg"><div class="modal glass picker" role="dialog" aria-modal="true" aria-label="${label} 시간"><h2 class="h2">${label} 시간</h2>
    <div class="wheels"><i class="band"></i>${wheel(24,'h')}<span class="wcolon">${DOTS(':',52)}</span>${wheel(60,'m')}</div>
    <div class="acts">${two(sec('취소',null,'resume'),`<button class="btn m pri-m" data-do="saveTime:${field}">저장</button>`)}</div></div></div>`);
  const owner = {field, epoch, screen:state.screen, root:$('#ov .picker'), events:new AbortController()};
  timePicker = owner;
  owner.root.querySelectorAll('.wheel').forEach(wheelNode =>
    spin(wheelNode, wheelNode.dataset.k === 'h' ? Math.floor(selected / 60) : selected % 60, owner));
}
function spin(wheel, selected, owner) {
  const items = [...wheel.querySelectorAll('.wv')], height = 96, signal = owner.events.signal;
  const valid = () => timePicker === owner && owner.root.isConnected && owner.epoch === epoch &&
    owner.screen === state.screen && !state.restricted;
  const paintWheel = () => {
    const center = wheel.scrollTop / height;
    items.forEach((item, index) => {
      const distance = index - center, magnitude = Math.min(Math.abs(distance), 2.6);
      item.style.transform = reducedMotion() ? 'none' :
        `perspective(700px) rotateX(${-distance * 16}deg) scale(${1 - magnitude * .12})`;
      item.style.opacity = String(1 - magnitude * .3);
      item.classList.toggle('sel', Math.abs(distance) < .5);
      item.setAttribute('aria-pressed', String(Math.abs(distance) < .5));
      item.tabIndex = Math.abs(distance) < .5 ? 0 : -1;
    });
  };
  const select = index => {
    if (!valid()) return;
    wheel.scrollTo({top:Math.max(0,Math.min(items.length - 1,index)) * height,
      behavior:reducedMotion() ? 'instant' : 'smooth'});
  };
  wheel.scrollTop = selected * height;
  paintWheel();
  wheel.addEventListener('scroll', paintWheel, {signal});
  let moved = false;
  wheel.addEventListener('click', event => {
    const item = event.target.closest('.wv');
    if (item && !moved) select(items.indexOf(item));
  }, {signal});
  wheel.addEventListener('keydown', event => {
    const delta = {ArrowUp:-1,ArrowDown:1,PageUp:-5,PageDown:5}[event.key];
    if (delta === undefined) return;
    event.preventDefault();
    select(Math.round(wheel.scrollTop / height) + delta);
  }, {signal});
  wheel.addEventListener('pointerdown', event => {
    if (event.pointerType !== 'mouse' || !event.isPrimary || event.button !== 0 || !valid()) return;
    const y = event.clientY, start = wheel.scrollTop;
    const scale = wheel.getBoundingClientRect().height / wheel.offsetHeight || 1;
    const history = [[event.timeStamp,event.clientY]], drag = new AbortController();
    signal.addEventListener('abort', () => {
      drag.abort();
      if (wheel.hasPointerCapture(event.pointerId)) wheel.releasePointerCapture(event.pointerId);
    }, {once:true});
    moved = false;
    wheel.setPointerCapture(event.pointerId);
    wheel.classList.add('drag');
    wheel.addEventListener('pointermove', move => {
      if (move.pointerId !== event.pointerId || !valid()) return;
      if (Math.abs(move.clientY - y) > 6) moved = true;
      wheel.scrollTop = start - (move.clientY - y) / scale;
      history.push([move.timeStamp,move.clientY]);
      if (history.length > 5) history.shift();
    }, {signal:drag.signal});
    const finish = up => {
      if (up.pointerId !== event.pointerId) return;
      drag.abort();
      if (wheel.hasPointerCapture(event.pointerId)) wheel.releasePointerCapture(event.pointerId);
      wheel.classList.remove('drag');
      const clicked = event.target.closest('.wv');
      if (!moved && up.type !== 'pointercancel' && clicked) {
        select(items.indexOf(clicked));
        return;
      }
      const [time, position] = history[0];
      const velocity = up.type === 'pointercancel' ? 0 :
        (up.clientY - position) / scale / Math.max(up.timeStamp - time,1);
      select(Math.round((wheel.scrollTop - velocity * 99) / height));
    };
    wheel.addEventListener('pointerup', finish, {signal:drag.signal});
    wheel.addEventListener('pointercancel', finish, {signal:drag.signal});
  }, {signal});
}
function saveTime(key) {
  const field = scheduleField(key), owner = timePicker;
  if (!owner || owner.field !== field || owner.epoch !== epoch || owner.screen !== state.screen ||
      state.restricted || !owner.root.isConnected) return;
  const values = [...owner.root.querySelectorAll('.wheel')].map(wheel => {
    const items = wheel.querySelectorAll('.wv');
    const index = Math.max(0,Math.min(items.length - 1,Math.round(wheel.scrollTop / 96)));
    return Number(items[index].dataset.v);
  });
  const minute = values[0] * 60 + values[1];
  // The card continues to display native saved facts until the SaveSchedule response arrives.
  schedule(undefined,{[field]:clock(minute)});
  closeOverlay(true);
}
function selectHome(key) {
  if (!['am','noon','meet','pm'].includes(key) || state.screen !== 'H-01' || state.restricted) return;
  state.homePick = key;
  const current = $('#scr > .home:not(.leaving)');
  if (!current) return;
  const template = document.createElement('template');
  template.innerHTML = S['H-01'].view(state);
  const next = template.content.firstElementChild;
  const slots = [...next.querySelectorAll('.slot')];
  current.querySelectorAll('.slot').forEach((node,index) => {
    const replacement = slots[index];
    node.className = replacement.className;
    node.setAttribute('aria-pressed', replacement.getAttribute('aria-pressed'));
    const shape = node.querySelector('.sg .shape'), nextShape = replacement.querySelector('.sg .shape');
    if (shape && nextShape) shape.classList.toggle('dim',nextShape.classList.contains('dim'));
  });
  const hero = current.querySelector('.hero'), replacement = next.querySelector('.hero');
  if (!hero || !replacement) return;
  const sequence = ++homeSelectionSequence, generation = renderGeneration;
  hero.style.cssText = replacement.style.cssText;
  [...hero.children].forEach(node => {
    if (node.matches('.mood-line')) return;
    freeze(node);
    node.classList.remove('dz-in');
    node.classList.add('dz-out');
    node.inert = true;
  });
  const swap = () => {
    if (sequence !== homeSelectionSequence || generation !== renderGeneration || !hero.isConnected) return;
    hero.querySelectorAll(':scope > .dz-out').forEach(node => node.remove());
    [...replacement.children].forEach(node => {
      if (node.matches('.mood-line')) return;
      node.classList.add('dz-in');
      hero.append(node);
    });
    icons();
    patchLive();
  };
  if (reducedMotion()) swap(); else later(170,swap);
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
    box.animate(reducedMotion() ? [{opacity:0},{opacity:1}] :
      [{opacity:0,filter:'blur(6px)'},{opacity:1,filter:'blur(0)'}],
      {duration:320,easing:'cubic-bezier(0.23,1,0.32,1)'});
  }
}
function vehicleControl(feature) {
  return state.hardware?.vehicleControls?.[feature] || {};
}
// Native rejects every preview and area change while a command is queued or requested.
function vehicleMutationsBlocked() {
  return state.hardware?.backrestControl?.busy === true ||
    Object.values(state.hardware?.vehicleControls || {}).some(control =>
    control?.command?.stage === 'QUEUED' || control?.command?.stage === 'REQUESTED');
}
function vehicleAccepts(capability, value) {
  return !!capability && Number.isFinite(value) && Number.isFinite(capability.min) &&
    Number.isFinite(capability.max) && Number.isFinite(capability.step) && capability.step > 0 &&
    value >= capability.min && value <= capability.max &&
    Math.abs((value - capability.min) / capability.step -
      Math.round((value - capability.min) / capability.step)) < 0.001 &&
    (!Array.isArray(capability.allowedValues) ||
      capability.allowedValues.some(allowed => Math.abs(allowed - value) < 1e-6));
}
// Change RGB without inventing or normalizing the SDK's observed alpha byte.
function argbFromHex(text, observed) {
  const match = /^#([0-9a-fA-F]{6})$/.exec(String(text ?? '').trim());
  if (!match || !Number.isInteger(observed) || observed < -2147483648 || observed > 2147483647) return null;
  return ((observed & 0xFF000000) | Number.parseInt(match[1], 16)) | 0;
}
function argbHex(value) {
  if (!Number.isInteger(value) || value < -2147483648 || value > 2147483647) return null;
  return '#' + ((value >>> 0) & 0xFFFFFF).toString(16).padStart(6, '0').toUpperCase();
}
function vehicleLabel(capability, value) {
  for (const [key, label] of Object.entries(capability?.valueLabels || {}))
    if (Number(key) === value) return label;
  return null;
}
function vehicleDraftValue(feature) {
  const control = vehicleControl(feature);
  const capability = control.capability;
  if (vehicleMutationsBlocked())
    return {ok:false, message:'차량 응답을 확인하는 중이에요. 끝난 뒤 다시 시도해 주세요.'};
  if (!capability || control.safetyReason || !Number.isFinite(control.currentValue))
    return {ok:false, message:'차량의 현재 값과 지원 범위를 먼저 확인해 주세요'};
  if (!capability.canWrite) return {ok:false, message:capability.reason || '이 설정은 제어할 수 없어요'};
  const key = `vehicle:${feature}`;
  const input = document.querySelector(`[data-bind="${key}"]`);
  const raw = drafts.has(key) ? drafts.get(key) : (input?.value ?? control.currentValue);
  if (feature === 'AMBIENT_COLOR') {
    const value = argbFromHex(raw, control.currentValue);
    if (value === null) return {ok:false, message:'#RRGGBB 형식의 색을 입력해 주세요'};
    if (!vehicleAccepts(capability, value)) return {ok:false, message:'차량이 허용하는 색 범위를 확인해 주세요'};
    return {ok:true, value};
  }
  const value = Number(raw);
  if (!Number.isFinite(value)) return {ok:false, message:'차량 값을 확인해 주세요'};
  if (!vehicleAccepts(capability, value)) return {ok:false, message:'차량이 허용하는 범위와 단위를 확인해 주세요'};
  return {ok:true, value};
}

Object.assign(ACT, {
  slide: () => { if (state.slide === 2) go('ON-03'); else { state.slide++; slideTo(1); } },
  slideBack: () => { if (state.slide === 0) go('ON-01'); else { state.slide--; slideTo(-1); } },
  slotPick: selectHome,
  pickTime,
  saveTime,
  permissions: () => overlay(`<div class="modal-bg"><div class="modal glass">
    <h2 class="h2">필요할 때 권한을 확인해요</h2>
    <p class="body" style="margin-top:12px">말로 기록할 때 마이크를, 현재 장소를 등록할 때 위치를 요청해요.</p>
    <div class="acts">${two(sec('마이크 권한',null,'permission:MICROPHONE'),
      sec('위치 권한',null,'permission:LOCATION'))}${pri('계속하기','ON-04')}</div>
    </div></div>`),
  permission: permission => send('requestPermission', {permission}),
  inspectVehicle: (feature, node) => {
    if (vehicleMutationsBlocked()) { toast('차량 응답을 확인하는 중이에요. 끝난 뒤 다시 시도해 주세요.'); return; }
    const area = node?.dataset.area;
    if (area && area !== vehicleControl(feature).area) drafts.delete(`vehicle:${feature}`);
    send('inspectVehicle', area ? {feature, area} : {feature});
  },
  vehicleChoice: payload => {
    const index = payload.lastIndexOf(':');
    if (index < 0 || vehicleMutationsBlocked()) return;
    const feature = payload.slice(0, index), value = Number(payload.slice(index + 1));
    if (!feature || !Number.isFinite(value)) return;
    drafts.set(`vehicle:${feature}`, String(value));
    refresh();
  },
  previewVehicle: feature => {
    const draft = vehicleDraftValue(feature);
    if (!draft.ok) { toast(draft.message); return; }
    send('previewVehicle', {feature, value:draft.value});
  },
  confirmVehicle: previewId => {
    const id = Number(previewId);
    if (!Number.isSafeInteger(id) || id <= 0) return;
    send('confirmVehicle', {previewId:id});
  },
  inspectBackrest: () => {
    if (!vehicleMutationsBlocked()) send('inspectBackrest');
  },
  pulseBackrest: (direction, node) => {
    if (vehicleMutationsBlocked()) return;
    const area = node?.dataset.area;
    if (area && (direction === 'FORWARD' || direction === 'BACKWARD')) {
      send('pulseBackrest', {area, direction});
    }
  },
  stopBackrest: () => send('stopBackrest'),
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
    setSeat(current + Number(delta));
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
    $('[data-bind="meetingMinute"]').dispatchEvent(new Event('input',{bubbles:true}));
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
    state.tune = index;
    document.querySelectorAll('#scr .tune').forEach(node => {
      const selected = node.dataset.do === `tune:${index}`;
      node.classList.toggle('on',selected);
      node.setAttribute('aria-pressed',String(selected));
    });
    const visual = $('#scr .tune-big');
    if (visual) dissolve(visual,TUNEBIG(index,state));
    $('#app').dataset.aura = TUNES[index][3];
    const play = $('#scr [data-do="playMusic"]');
    if (play) {
      play.disabled = false;
      play.removeAttribute('aria-disabled');
      play.firstChild.textContent = '이 음악 듣기';
    }
    icons();
  },
  playMusic: () => {
    journeyInput('MUSIC_CHOICE',['SUPPORT_SONG','RECENT','RECOMMENDATION'][state.tune]);
    command('PLAY_MUSIC');
  }
});

const rubber = (d, lim = 150) => d * lim * .55 / (lim + .55 * Math.abs(d));
function graphicEligible(g) {
  return g.isConnected && $('#scr').contains(g) && !g.closest('.scr.leaving') &&
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
function clearSeatGesture() {
  const owner = seatGesture;
  if (!owner) return;
  seatGesture = null;
  owner.events.abort();
  owner.observer.disconnect();
  owner.svg.classList.remove('drag');
  if (owner.svg.hasPointerCapture(owner.pointerId)) owner.svg.releasePointerCapture(owner.pointerId);
}
document.addEventListener('pointerdown', event => {
  const svg = event.target.closest('.seatctl .seat');
  if (!event.isTrusted || !event.isPrimary || event.button !== 0 || !svg || !graphicEligible(svg)) return;
  const matrix = svg.getScreenCTM();
  if (!matrix) return;
  const point = input => new DOMPoint(input.clientX,input.clientY).matrixTransform(matrix.inverse());
  if (point(event).y > 380) return;
  event.preventDefault();
  clearSeatGesture();
  const owner = {svg, epoch, generation:renderGeneration, pointerId:event.pointerId,
    events:new AbortController(), root:svg.closest('.scr')};
  owner.valid = () => seatGesture === owner && epoch === owner.epoch &&
    renderGeneration === owner.generation && svg.closest('.scr') === owner.root && graphicEligible(svg);
  owner.observer = new MutationObserver(() => { if (!owner.valid()) clearSeatGesture(); });
  owner.observer.observe($('#scr'),{childList:true,subtree:true});
  seatGesture = owner;
  const move = input => {
    if (input.pointerId !== owner.pointerId) return;
    if (!owner.valid()) { clearSeatGesture(); return; }
    const position = point(input);
    setSeat(118 + Math.atan2(position.x - SP[0],SP[1] - position.y) * 180 / Math.PI - 10.26);
  };
  const finish = input => {
    if (input.pointerId !== owner.pointerId) return;
    clearSeatGesture();
    scheduleArrivalAdmission();
  };
  svg.classList.add('drag');
  svg.addEventListener('pointermove',move,{signal:owner.events.signal});
  svg.addEventListener('pointerup',finish,{signal:owner.events.signal});
  svg.addEventListener('pointercancel',finish,{signal:owner.events.signal});
  svg.addEventListener('lostpointercapture',finish,{signal:owner.events.signal});
  svg.setPointerCapture(event.pointerId);
  move(event);
});
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
      {duration:POP.ms,easing:POP.ease});
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

const RIPPLE = '.btn,.iconbtn,.chip,.slot,.sound,.mood,.stone,.seg3 button,button.li,.dy,.place.empty,.tcard,.tune,.choice';
document.addEventListener('pointerdown', event => {
  const button = event.target.closest(RIPPLE);
  if (!event.isPrimary || event.button !== 0 || !button || button.disabled ||
      button.closest('[inert]') || state.restricted || reducedMotion()) return;
  const rect = button.getBoundingClientRect(), scale = rect.width / button.offsetWidth || 1;
  const x = (event.clientX - rect.left) / scale, y = (event.clientY - rect.top) / scale;
  const diameter = 2 * Math.hypot(Math.max(x,button.offsetWidth - x),Math.max(y,button.offsetHeight - y));
  const ripple = document.createElement('span'), wave = document.createElement('i');
  ripple.className = 'rip';
  ripple.setAttribute('aria-hidden','true');
  Object.assign(wave.style,{left:`${x}px`,top:`${y}px`,width:`${diameter}px`,height:`${diameter}px`});
  ripple.append(wave);
  button.append(ripple);
  setTimeout(() => ripple.remove(),900);
});

document.addEventListener('input',event => {
  const key = event.target.dataset.bind;
  if (key) drafts.set(key,event.target.value);
});
document.addEventListener('change',event => {
  if (event.target.dataset.bind === 'restSound') send('selectSound',{sound:event.target.value});
});
document.addEventListener('keydown',event => {
  if (event.key === 'Escape' && timePicker) { event.preventDefault(); closeOverlay(true); return; }
  if (!['Enter',' '].includes(event.key) || !event.target.matches('[role="button"][data-do]')) return;
  event.preventDefault(); event.target.click();
});

document.addEventListener('click', event => {
  const target = event.target.closest('[data-go],[data-do]');
  if (!target || target.disabled || target.closest('[inert],.scr.leaving,.dz-out,.x-out')) return;
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
window.addEventListener('pagehide', () => {
  clearGraphicGesture(); clearSeatGesture(); clearTimePicker();
  releaseArrivalReservation(true); pendingPrevious.clear();
});
window.addEventListener('focus', scheduleArrivalAdmission);
window.addEventListener('blur', () => {
  clearGraphicGesture(); clearSeatGesture();
  if (timePicker) closeOverlay();
  releaseArrivalReservation(true);
});
document.addEventListener('focusout', scheduleArrivalAdmission);
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState !== 'visible') {
    clearGraphicGesture(); clearSeatGesture(); releaseArrivalReservation(true);
    if (timePicker) closeOverlay();
  }
  else scheduleArrivalAdmission();
});
fitWindow();
send('state');
