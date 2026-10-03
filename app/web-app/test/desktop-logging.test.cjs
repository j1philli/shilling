const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

const html = fs.readFileSync(path.join(__dirname, '../index.html'), 'utf8');
const bridge = html.match(/<script>\s*(\/\/ Bound desktop log IPC:[\s\S]*?)<\/script>/)[1];
const turn = () => new Promise(resolve => setImmediate(resolve));

function fixture(invoke) {
  const messages = [];
  const console = Object.fromEntries(['log', 'info', 'warn', 'error'].map(level =>
    [level, (...args) => messages.push({ level, args })]));
  const window = invoke ? { __TAURI__: { core: { invoke } } } : {};
  vm.runInNewContext(bridge, { window, console });
  return { console, messages };
}

test('browser console remains usable without Tauri', () => {
  const { console, messages } = fixture();
  const object = { value: 42 };
  console.log('hello', object);
  assert.deepEqual(messages, [{ level: 'log', args: ['hello', object] }]);
});

test('an import-sized burst has one IPC in flight and a bounded backlog', async () => {
  const calls = [], pending = [];
  let inFlight = 0, peak = 0;
  const { console } = fixture((command, entry) => {
    assert.equal(command, 'plugin:log|log');
    calls.push(entry);
    peak = Math.max(peak, ++inFlight);
    return new Promise(resolve => pending.push(() => { inFlight--; resolve(); }));
  });
  for (let i = 0; i < 10000; i++) console.log('row ' + i);
  console.error('latest error ' + 'x'.repeat(20000));
  await turn();
  assert.equal(calls.length, 1);
  let rounds = 0;
  while (pending.length && rounds++ < 140) {
    pending.shift()();
    await turn();
  }
  assert.equal(peak, 1);
  assert.equal(pending.length, 0, 'queue must finish draining');
  assert.ok(calls.length <= 129, 'overflow must be dropped, not retained for later IPC');
  assert.ok(calls.some(entry => entry.message.startsWith('Dropped ') && entry.level === 4));
  assert.ok(calls.some(entry => entry.message.startsWith('latest error ') && entry.level === 5));
  assert.ok(calls.every(entry => entry.message.length <= 8192));
});

test('info/warnings/errors preserve their levels and rejected IPC does not block the queue', async () => {
  const calls = [];
  const { console } = fixture((command, entry) => {
    calls.push(entry);
    if (calls.length === 1) throw new Error('plugin unavailable');
    return Promise.reject(new Error('rejected'));
  });
  console.info('info'); console.warn('warning'); console.error('error');
  await turn();
  assert.deepEqual(calls.map(entry => entry.level), [3, 4, 5]);
  assert.deepEqual(calls.map(entry => entry.message), ['info', 'warning', 'error']);
});
