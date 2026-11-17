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

  for (const value of ['OFF', 'ON', '0', '1']) {
    await send('exec', `/* connection setting */ PRAGMA foreign_keys = ${value};`);
    assert.equal((await send('exec', 'PRAGMA foreign_keys;')).values[0][0],
      value === 'ON' || value === '1' ? 1 : 0, 'enforcement must still change');
  }
  await sleep(150);
  assert.equal(indexedDB.writes, initialWrites, 'connection-only pragmas must not export the database');
  assert.equal(exports.length, initialWrites, 'connection setup must not even allocate an export');

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
  await send('exec', 'PRAGMA user_version(4);');
  await sleep(150);
  assert.equal(indexedDB.writes, initialWrites + 6, 'parenthesized pragma assignments must persist too');
  await send('begin_transaction');
  await send('exec', 'PRAGMA foreign_keys = ON; PRAGMA user_version = 5;');
  await send('end_transaction');
  assert.equal(indexedDB.writes, initialWrites + 7, 'a write after a connection pragma must persist');
  let lastContext = context;
  for (let reopen = 0; reopen < 3; reopen++) {
    const next = { ...context, self: {} };
    lastContext = next;
    vm.runInNewContext(workerSource, next, { filename: 'sqldelight.worker.js' });
    const expected = [[1], [3], [4], [5], ...Array.from({ length: reopen }, (_, i) => [6 + i])];
    assert.deepEqual((await send('exec', 'SELECT id FROM items ORDER BY id;', next)).values, expected,
      'reopening must preserve data after supported, absent or throwing snapshot cleanup');
    assert.deepEqual((await send('exec', 'PRAGMA user_version;', next)).values, [[5]]);
    const read = indexedDB.reads[reopen];
    assert.equal(read.data.byteLength === 0, read.supported, 'startup readback releases its owned copy');
    await send('begin_transaction', undefined, next);
    await send('exec', `INSERT INTO items (id) VALUES (${6 + reopen});`, next);
    await send('end_transaction', undefined, next);
  }
  const afterReloads = new SQL.Database(indexedDB.saved);
  assert.deepEqual(afterReloads.exec('SELECT id FROM items ORDER BY id;')[0].values, [[1], [3], [4], [5], [6], [7], [8]]);
  afterReloads.close();

  const cteSend = (action, sql) => send(action, sql, lastContext);
  await cteSend('begin_transaction');
  await cteSend('exec', 'CREATE TABLE cte_items (id INTEGER PRIMARY KEY, value INTEGER);');
  await cteSend('exec', 'INSERT INTO cte_items VALUES (1, 10);');
  await cteSend('exec', 'CREATE VIEW cte_view AS SELECT id, value FROM cte_items;');
  await cteSend('exec', 'CREATE TABLE cte_sequence (id INTEGER PRIMARY KEY AUTOINCREMENT, value TEXT UNIQUE);');
  await cteSend('exec', "INSERT INTO cte_sequence(value) VALUES ('existing');");
  await cteSend('exec', `CREATE TRIGGER cte_view_insert INSTEAD OF INSERT ON cte_view
    BEGIN INSERT INTO cte_items VALUES (NEW.id, NEW.value); END;`);
  await cteSend('end_transaction');
  for (let i = 0; i < 5; i++) {
    assert.deepEqual((await cteSend('exec', `/* Home-style projection */ WITH recent AS MATERIALIZED (
      SELECT * FROM cte_items ORDER BY id DESC LIMIT 3
    ) SELECT * FROM recent;`)).values, [[1, 10]]);
    assert.deepEqual((await cteSend('exec', `-- Recursive read
      WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<3)
      SELECT SUM(x) FROM n;`)).values, [[6]]);
    await sleep(30);
  }
  await sleep(150);

  for (const sql of [
    'WITH x(id) AS (VALUES(2)) INSERT INTO cte_items SELECT id, 10 FROM x RETURNING id;',
    'WITH x(id) AS (SELECT id FROM cte_items) UPDATE cte_items SET value=20 WHERE id IN (SELECT id FROM x);',
    'WITH x(id) AS (VALUES(2)) DELETE FROM cte_items WHERE id IN (SELECT id FROM x);',
    'WITH x(id) AS (VALUES(1)) INSERT OR REPLACE INTO cte_items SELECT id, 30 FROM x;',
    'WITH x(id) AS (VALUES(3)) INSERT INTO cte_view SELECT id, 40 FROM x;'
  ]) {
    const before = indexedDB.writes;
    await cteSend('begin_transaction');
    await cteSend('exec', sql);
    await cteSend('end_transaction');
    assert.equal(indexedDB.writes, before + 1, `CTE mutation must persist: ${sql}`);
  }
  const beforeRollback = indexedDB.writes;
  await cteSend('begin_transaction');
  await cteSend('exec', 'WITH x(id) AS (VALUES(5)) INSERT INTO cte_items SELECT id, 50 FROM x;');
  await cteSend('rollback_transaction');
  await sleep(150);
  assert.equal(indexedDB.writes, beforeRollback, 'rolled-back CTE mutations must not persist');
  await cteSend('begin_transaction');
  await cteSend('exec', 'WITH x AS (SELECT 1) SELECT * FROM x; PRAGMA user_version = 6;');
  await cteSend('end_transaction');
  assert.equal(indexedDB.writes, beforeRollback + 1, 'a write following a CTE read must persist');
  const afterCtes = new SQL.Database(indexedDB.saved);
  assert.deepEqual(afterCtes.exec('SELECT * FROM cte_items ORDER BY id;')[0].values, [[1, 30], [3, 40]]);
  assert.deepEqual(afterCtes.exec('PRAGMA user_version;')[0].values, [[6]]);
  afterCtes.close();
  // SQLite can advance AUTOINCREMENT despite zero total_changes(). A generic
  // row-counter-only CTE classifier would silently discard this persistent write.
  const beforeIgnoredInsert = indexedDB.writes;
  await cteSend('begin_transaction');
  const beforeChanges = (await cteSend('exec', 'SELECT total_changes();')).values[0][0];
  await cteSend('exec', "WITH x(value) AS (VALUES('existing')) INSERT OR IGNORE INTO cte_sequence(value) SELECT value FROM x;");
  assert.equal((await cteSend('exec', 'SELECT total_changes();')).values[0][0], beforeChanges);
  assert.deepEqual((await cteSend('exec', "SELECT seq FROM sqlite_sequence WHERE name='cte_sequence';")).values, [[2]]);
  await cteSend('end_transaction');
  assert.equal(indexedDB.writes, beforeIgnoredInsert + 1, 'CTE writes must persist even when their row counter is unchanged');
  const afterIgnoredInsert = new SQL.Database(indexedDB.saved);
  assert.deepEqual(afterIgnoredInsert.exec("SELECT seq FROM sqlite_sequence WHERE name='cte_sequence';")[0].values, [[2]]);
  afterIgnoredInsert.close();
  if (typeof ArrayBuffer.prototype.transfer === 'function') assert.ok(exports.some(entry => entry.supported));
  assert.ok(exports.some(entry => !entry.supported));
  for (const { data, supported } of exports) {
    assert.equal(data.byteLength === 0, supported, 'completed exports detach where supported; older engines still persist');
  }
  console.log('web worker persistence checks passed');
}

main().catch(error => { console.error(error); process.exitCode = 1; });
