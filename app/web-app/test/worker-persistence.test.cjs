const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const initSqlJs = require('sql.js');

const workerSource = fs.readFileSync(path.join(__dirname, '..', 'sqldelight.worker.js'), 'utf8');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

function createIndexedDB() {
  let saved = null;
  let writes = 0;
  let writesStarted = 0;
  const reads = [];
  const idb = {
    createObjectStore() {},
    transaction(_store, mode) {
      const tx = {
        error: null,
        objectStore() {
          return {
            get() {
              // Each IndexedDB read returns its own structured clone too.
              const data = saved && new Uint8Array(saved);
              if (data) {
                const mode = reads.length % 3;
                const supported = mode === 0 && typeof data.buffer.transfer === 'function';
                if (mode === 1) data.buffer.transfer = undefined;
                if (mode === 2) data.buffer.transfer = () => { throw new Error('cleanup unavailable'); };
                reads.push({ data, supported });
              }
              const request = { result: data };
              queueMicrotask(() => request.onsuccess());
              return request;
            },
            put(data) {
              assert.equal(mode, 'readwrite');
              // IndexedDB snapshots its argument instead of retaining the caller's
              // mutable buffer. Keep this faithful when the worker detaches it.
              const copy = new Uint8Array(data);
              writesStarted++;
              setTimeout(() => {
                saved = copy;
                writes++;
                tx.oncomplete();
              }, 25);
              return {};
            }
          };
        }
      };
      return tx;
    }
  };
  return {
    get writes() { return writes; },
    get writesStarted() { return writesStarted; },
    get saved() { return saved; },
    reads,
    open() {
      const request = { result: idb };
      queueMicrotask(() => {
        request.onupgradeneeded();
        request.onsuccess();
      });
      return request;
    }
  };
}

async function main() {
  const indexedDB = createIndexedDB();
  const pending = new Map();
  let nextId = 0;
  const exports = [];
  const context = {
    importScripts() {},
    initSqlJs: async () => {
      const SQL = await initSqlJs({ locateFile: file => path.join(path.dirname(require.resolve('sql.js')), file) });
      return { ...SQL, Database: class extends SQL.Database {
        export() {
          const data = super.export();
          // Cover supported, older-engine and cleanup-failure behavior without globals.
          const mode = exports.length % 3;
          const supported = mode === 0 && typeof data.buffer.transfer === 'function';
          if (mode === 1) data.buffer.transfer = undefined;
          if (mode === 2) data.buffer.transfer = () => { throw new Error('cleanup unavailable'); };
          exports.push({ data, supported });
          return data;
        }
      } };
    },
    indexedDB,
    setTimeout,
    clearTimeout,
    console,
    self: {},
    postMessage(message) {
      const resolve = pending.get(message.id);
      pending.delete(message.id);
      resolve(message);
    }
  };
  vm.runInNewContext(workerSource, context, { filename: 'sqldelight.worker.js' });
  function send(action, sql, workerContext = context) {
    const id = ++nextId;
    return new Promise(resolve => {
      pending.set(id, resolve);
      workerContext.self.onmessage({ data: { id, action, sql } });
    }).then(message => {
      if (message.error) throw message.error;
      return message.results;
    });
  }

  await send('exec', 'CREATE TABLE items (id INTEGER PRIMARY KEY);');
  await sleep(150);
  const initialWrites = indexedDB.writes;
  assert.equal(initialWrites, 1);

  await send('exec', 'SELECT * FROM items;');
  await sleep(150);
  assert.equal(indexedDB.writes, initialWrites, 'reads must not persist the database');

  await send('exec', 'INSERT INTO items (id) VALUES (1);');
  for (let i = 0; i < 8; i++) {
    await send('exec', 'SELECT * FROM items;');
    await sleep(20);
  }
  assert.equal(indexedDB.writes, initialWrites + 1, 'frequent reads must not defer an earlier write');

  await send('begin_transaction');
  await send('exec', 'INSERT INTO items (id) VALUES (2);');
  await send('rollback_transaction');
  await sleep(150);
  assert.equal(indexedDB.writes, initialWrites + 1, 'rolled-back writes must not persist');

  await send('begin_transaction');
  await send('exec', 'INSERT INTO items (id) VALUES (3);');
  await send('end_transaction');
  await sleep(150);
  assert.equal(indexedDB.writes, initialWrites + 2, 'a committed transaction should persist once');
  assert.deepEqual((await send('exec', 'SELECT id FROM items ORDER BY id;')).values, [[1], [3]]);

  const startedBefore = indexedDB.writesStarted;
  await send('exec', 'INSERT INTO items (id) VALUES (4);');
  for (let i = 0; indexedDB.writesStarted === startedBefore && i < 50; i++) await sleep(5);
  assert.equal(indexedDB.writesStarted, startedBefore + 1);
  await send('exec', 'INSERT INTO items (id) VALUES (5);');
  await sleep(170);
  assert.equal(indexedDB.writes, initialWrites + 4, 'a write during persistence needs a second snapshot');
  const SQL = await initSqlJs({ locateFile: file => path.join(path.dirname(require.resolve('sql.js')), file) });
  const savedDatabase = new SQL.Database(indexedDB.saved);
  assert.deepEqual(savedDatabase.exec('SELECT id FROM items ORDER BY id;')[0].values, [[1], [3], [4], [5]]);
  savedDatabase.close();

  await send('exec', 'PRAGMA user_version = 3;');
  await sleep(150);
  assert.equal(indexedDB.writes, initialWrites + 5, 'schema version changes must persist');
  for (let reopen = 0; reopen < 3; reopen++) {
    const next = { ...context, self: {} };
    vm.runInNewContext(workerSource, next, { filename: 'sqldelight.worker.js' });
    const expected = [[1], [3], [4], [5], ...Array.from({ length: reopen }, (_, i) => [6 + i])];
    assert.deepEqual((await send('exec', 'SELECT id FROM items ORDER BY id;', next)).values, expected,
      'reopening must preserve data after supported, absent or throwing snapshot cleanup');
    assert.deepEqual((await send('exec', 'PRAGMA user_version;', next)).values, [[3]]);
    const read = indexedDB.reads[reopen];
    assert.equal(read.data.byteLength === 0, read.supported, 'startup readback releases its owned copy');
    await send('begin_transaction', undefined, next);
    await send('exec', `INSERT INTO items (id) VALUES (${6 + reopen});`, next);
    await send('end_transaction', undefined, next);
  }
  const afterReloads = new SQL.Database(indexedDB.saved);
  assert.deepEqual(afterReloads.exec('SELECT id FROM items ORDER BY id;')[0].values, [[1], [3], [4], [5], [6], [7], [8]]);
  afterReloads.close();
  if (typeof ArrayBuffer.prototype.transfer === 'function') assert.ok(exports.some(entry => entry.supported));
  assert.ok(exports.some(entry => !entry.supported));
  for (const { data, supported } of exports) {
    assert.equal(data.byteLength === 0, supported, 'completed exports detach where supported; older engines still persist');
  }
  console.log('web worker persistence checks passed');
}

main().catch(error => { console.error(error); process.exitCode = 1; });
