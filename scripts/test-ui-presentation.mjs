import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';

const assets = new URL('../app/src/main/assets/teum/', import.meta.url);
const app = readFileSync(new URL('app.js', assets), 'utf8');
const screens = readFileSync(new URL('screens.js', assets), 'utf8');
const picked = screens.slice(screens.indexOf('function picked('), screens.indexOf('const SLOTS'));
const openJourney = app.slice(app.indexOf('function openJourney('), app.indexOf('function visualPick('));
const dots = screens.slice(screens.indexOf('const DOTS ='), screens.indexOf('const dissolve'));
const patchLive = app.slice(app.indexOf('function patchLive('), app.indexOf('function previousReceiptMatches('));
const home = screens.slice(screens.indexOf("S['H-01'] ="), screens.indexOf("S['H-02'] ="));

test('initial home selection matches the native current card even before earlier slots are done', () => {
  const context = {
    S: {},
    SLOTS: [['am', null, 'F1-01'], ['noon', null, 'F2-02']],
    NAME: { am: '아침 준비', noon: '점심 낮잠' },
    SIG: { am: 'dome', noon: 'bean' },
    SIGPAL: { am: 'sunrise', noon: 'dusk' },
    hero: () => ({ k: 'noon', ctx: 'native', name: '점심 낮잠', big: 20, sub: '', btn: null }),
    nextKey: () => 'am',
    SHAPE: () => '', MINI: () => '', MS: () => '', I: () => '', BARS: () => '',
    MIN: value => String(value), minutes: () => 0, esc: value => String(value ?? '')
  };
  runInNewContext(picked + '\n' + home, context);
  const markup = context.S['H-01'].view({
    homePick: null, slot: 'noon', done: {}, skipped: {}, context: {}, week: {}
  });
  assert.match(markup, /data-do="slotPick:noon" aria-pressed="true"/);
  assert.match(markup, /data-do="slotPick:am" aria-pressed="false"/);
});

test('each completed tile offers a native replay request without changing completion data', () => {
  for (const key of ['am', 'noon', 'meet', 'pm']) {
    const sent = [], routes = [];
    const state = { homePick: key, slot: key, done: { [key]: true }, context: {} };
    const before = JSON.stringify(state);
    const context = {
      SLOTS: [['am', null, 'F1-01'], ['noon', null, 'F2-02'],
        ['meet', null, 'F3-03'], ['pm', null, 'F4-02']],
      NAME: { am: '아침', noon: '낮잠', meet: '준비', pm: '저녁' },
      send: (action, payload) => sent.push([action, JSON.parse(JSON.stringify(payload))]),
      go: route => routes.push(route)
    };
    runInNewContext(picked + '\n' + openJourney, context);
    const card = context.picked(state, { k: key });
    assert.equal(card.btn[0], '다시 하기');
    assert.equal(card.btn[2], `begin:${key}`);
    assert.equal(sent.length, 0);
    context.openJourney(card.btn[2].slice('begin:'.length));
    assert.deepEqual(sent, [['openJourney', { journey: key }]]);
    assert.deepEqual(routes, []);
    assert.equal(JSON.stringify(state), before);
  }
});

function liveFixture(value) {
  const node = { dataset: { liveDots: 'context.countdownSeconds', dotsHeight: '144' }, innerHTML: '' };
  const context = {
    state: { context: { countdownSeconds: value } },
    document: { querySelectorAll: selector => selector === '[data-live-dots]' ? [node] : [] },
    $: () => null,
    patchEntryControls: () => {},
    // SVG path measurement and animation are browser boundaries; the real DOTS
    // template and patchLive implementation generate the accessibility markup.
    dotPts: () => [[1, 1]],
    dissolve: (element, html) => { element.innerHTML = html; }
  };
  runInNewContext(dots + '\n' + patchLive, context);
  return { context, node };
}

test('successive native countdown values update the SVG accessibility label', () => {
  const { context, node } = liveFixture(10);
  context.patchLive();
  assert.match(node.innerHTML, /aria-label="10"/);
  context.state.context.countdownSeconds = 9;
  context.patchLive();
  assert.match(node.innerHTML, /aria-label="9"/);
  assert.doesNotMatch(node.innerHTML, /aria-label="10"/);
});

test('unknown countdown remains a visible dash instead of an empty SVG', () => {
  const { context, node } = liveFixture(null);
  context.patchLive();
  assert.equal(node.innerHTML, '—');
});
