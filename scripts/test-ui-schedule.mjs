import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';

const assets = new URL('../app/src/main/assets/teum/', import.meta.url);
const app = readFileSync(new URL('app.js', assets), 'utf8');
const screens = readFileSync(new URL('screens.js', assets), 'utf8');
const clock = screens.slice(screens.indexOf('function clock('), screens.indexOf('function fact('));
const scheduling = app.slice(app.indexOf('function clockMinute('), app.indexOf('function slideTo('));

function fixture() {
  const sent = [], notices = [];
  const context = {
    state: { preferences: { schedule: {
      workStartMinute: 520, lunchReturnMinute: 780, walkingReserveMinutes: 5
    } } },
    // Represents a previously edited field that is no longer the native saved value.
    binding: () => '08:31',
    toast: message => notices.push(message),
    send: (action, payload) => sent.push([action, JSON.parse(JSON.stringify(payload))])
  };
  runInNewContext(clock + '\n' + scheduling, context);
  return { context, sent, notices };
}

test('wheel save retains odd minutes and the other native saved time', () => {
  const f = fixture();
  f.context.schedule(undefined, { workStartMinute: '08:37' });
  assert.deepEqual(f.sent, [['saveSchedule', {
    workStartMinute: 517, lunchReturnMinute: 780, walkingReserveMinutes: 5
  }]]);
});

test('changing lunch uses the newer native work time rather than an old draft', () => {
  const f = fixture();
  f.context.state.preferences.schedule.workStartMinute = 585;
  f.context.schedule(undefined, { lunchReturnMinute: '13:07' });
  assert.deepEqual(f.sent[0][1], {
    workStartMinute: 585, lunchReturnMinute: 787, walkingReserveMinutes: 5
  });
});

test('an unacknowledged time save is not replayed by a later walking edit', () => {
  const f = fixture();
  f.context.schedule(undefined, { workStartMinute: '08:37' });
  f.context.schedule(6);
  assert.deepEqual(f.sent[1][1], {
    workStartMinute: 520, lunchReturnMinute: 780, walkingReserveMinutes: 6
  });
});

test('invalid wheel input sends no native request', () => {
  const f = fixture();
  f.context.schedule(undefined, { workStartMinute: '25:00' });
  assert.equal(f.sent.length, 0);
  assert.equal(f.notices.length, 1);
});
