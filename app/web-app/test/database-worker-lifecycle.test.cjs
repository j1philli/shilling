const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

// Execute the exact JS boundary shipped by the Wasm app. The callback sequence
// below reproduces SQLDelight 2.4.0, including its missing error-listener cleanup.
const source = fs.readFileSync(path.join(__dirname,
  '../../shared/src@wasmJs/finance/shilling/shared/data/store/DatabaseWorker.kt'), 'utf8');
const transport = source.match(/js\("""([\s\S]*?)"""\)/)[1];

function fixture() {
  let native;
  class Worker extends EventTarget {
    constructor(url) { super(); this.url = url; this.listeners = []; native = this; }
    addEventListener(type, listener) {
      this.listeners.push({ type, listener });
      super.addEventListener(type, listener);
    }
    removeEventListener(type, listener) {
      super.removeEventListener(type, listener);
      this.listeners = this.listeners.filter(entry => entry.type !== type || entry.listener !== listener);
    }
    postMessage(message) {
      if (this.throwOnPost) throw new Error('cannot clone');
      this.lastMessage = message;
    }
    terminate() { this.terminated = true; }
    reply(data) { this.dispatchEvent(new MessageEvent('message', { data })); }
  }
  const worker = vm.runInNewContext(transport, { Worker, url: 'sqldelight.worker.js' });
  function request(id) {
    const calls = [];
    const message = event => {
      assert.equal(event.data.id, id);
      worker.removeEventListener('message', message);
      calls.push(event.data);
    };
    const error = event => {
      worker.removeEventListener('error', error);
      calls.push({ error: event.type });
    };
    worker.addEventListener('message', message);
    worker.addEventListener('error', error);
    worker.postMessage({ id, action: 'exec', sql: 'SELECT 1' });
    return {
      calls,
      cancel() {
        worker.removeEventListener('message', message);
        worker.removeEventListener('error', error);
      }
    };
  }
  return { worker, native, request };
}

test('completed SQL requests do not retain error callbacks or resume twice', () => {
  const { native, request } = fixture();
  const calls = [];
  for (let id = 0; id < 10000; id++) {
    const current = request(id);
    native.reply({ id, results: { values: [[id]] } });
    assert.equal(current.calls.length, 1);
    calls.push(current.calls);
  }
  native.dispatchEvent(new Event('error'));
  assert.ok(calls.every(items => items.length === 1), 'a later worker error must not reach completed requests');
  assert.equal(native.listeners.length, 3, 'native listeners must stay constant across SQL requests');
});

test('concurrent replies and SQL failures release only their own request', () => {
  const { native, request } = fixture();
  const first = request(1), second = request(2), third = request(3);
  native.reply({ id: 2, error: { message: 'SQL failed' } });
  native.reply({ id: 1, results: { values: [[new Uint8Array([1, 2, 3])]] } });
  assert.equal(first.calls.length, 1);
  assert.equal(second.calls[0].error.message, 'SQL failed');
  assert.equal(third.calls.length, 0);
  native.dispatchEvent(new Event('error'));
  assert.deepEqual(third.calls, [{ error: 'error' }]);
  native.reply({ id: 3, results: {} });
  assert.equal(third.calls.length, 1, 'failed request must not resume again');
});

test('cancellation discards late replies without affecting other requests', () => {
  const { native, request } = fixture();
  const cancelled = request(1), active = request(2);
  cancelled.cancel();
  native.reply({ id: 1, results: {} });
  native.dispatchEvent(new Event('messageerror'));
  assert.deepEqual(cancelled.calls, []);
  assert.deepEqual(active.calls, [{ error: 'messageerror' }]);
});

test('postMessage failure and shutdown release request callbacks', () => {
  const { native, worker, request } = fixture();
  native.throwOnPost = true;
  assert.throws(() => request(1), /cannot clone/);
  native.throwOnPost = false;
  const current = request(1); // failed id is available again
  worker.terminate();
  native.reply({ id: 1, results: {} });
  native.dispatchEvent(new Event('error'));
  assert.deepEqual(current.calls, []);
  assert.equal(native.terminated, true);
  assert.equal(native.listeners.length, 0);
});
