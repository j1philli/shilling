// Run the actual worker and sql.js engine with an IndexedDB transaction harness.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const initSqlJs = require('sql.js');
let persisted = null;
let failNextCommit = false;
const indexedDB = { open() {
  const request = {};
  const database = { createObjectStore() {}, transaction(_name, mode) {
    const tx = { error: new Error('Injected IndexedDB commit failure') };
    tx.objectStore = () => ({ get() {
      const get = {};
      setTimeout(() => { get.result = persisted && new Uint8Array(persisted); get.onsuccess(); }, 0);
      return get;
    }, put(bytes) {
      const put = {};
      const copy = new Uint8Array(bytes);
      setTimeout(() => {
        if (failNextCommit) { failNextCommit = false; tx.onabort(); }
        else { persisted = copy; tx.oncomplete(); }
      }, 0);
      return put;
    } });
    return tx;
  } };
  setTimeout(() => { request.result = database; request.onsuccess(); }, 0);
  return request;
} };
function worker() {
  const pending = new Map();
  let id = 0;
  const context = vm.createContext({ self: {}, importScripts() {}, initSqlJs: options => initSqlJs({ ...options, wasmBinary: fs.readFileSync(require.resolve("sql.js/dist/sql-wasm.wasm")) }), indexedDB, setTimeout, clearTimeout, console,
    postMessage(message) { pending.get(message.id)(message); pending.delete(message.id); } });
  vm.runInContext(fs.readFileSync('app/web-app/sqldelight.worker.js', 'utf8'), context);
  return { async request(action, sql) {
    const message = await new Promise(resolve => { const key = ++id; pending.set(key, resolve); context.self.onmessage({ data: { id: key, action, sql, params: [] } }); });
    if (message.error) throw message.error;
    return message.results;
  } };
}
(async () => {
  const first = worker();
  const legacy = fs.readFileSync('app/shared/test@jvm/finance/shilling/shared/data/LegacySchemaFixture.kt', 'utf8').split('"""')[1];
  await first.request('exec', legacy);
  await first.request('exec', "INSERT INTO accounts VALUES ('a','Original',42); INSERT INTO postings VALUES ('p',NULL,'EXPENSE','a',1,5,NULL,'Coffee',NULL); INSERT INTO receipts VALUES ('r','p','legacy','receipt.jpg',1,NULL,NULL,NULL); INSERT INTO receipt_files VALUES ('r',X'010203',3,'image/jpeg'); PRAGMA user_version=1;");
  await first.request('begin_transaction');
  await first.request('end_transaction');
  const original = new Uint8Array(persisted);
  await first.request('exec', 'PRAGMA foreign_keys=OFF;');
  await first.request('begin_transaction');
  await first.request('exec', fs.readFileSync('app/shared/src/commonMain/sqldelight/finance/shilling/shared/db/1.sqm', 'utf8'));
  await first.request('rollback_transaction');
  assert.deepEqual(persisted, original, 'rollback must not persist half a migration');
  await first.request('begin_transaction');
  await first.request('exec', fs.readFileSync('app/shared/src/commonMain/sqldelight/finance/shilling/shared/db/1.sqm', 'utf8'));
  await first.request('exec', 'PRAGMA user_version=2;');
  failNextCommit = true;
  await assert.rejects(first.request('end_transaction'), /Injected/);
  assert.deepEqual(persisted, original, 'failed IndexedDB commit must leave durable legacy data');
  const retry = worker();
  await retry.request('exec', 'PRAGMA foreign_keys=OFF;');
  await retry.request('begin_transaction');
  await retry.request('exec', fs.readFileSync('app/shared/src/commonMain/sqldelight/finance/shilling/shared/db/1.sqm', 'utf8'));
  await retry.request('exec', 'PRAGMA user_version=2;');
  await retry.request('end_transaction');
  const reopened = worker();
  assert.equal((await reopened.request('exec', 'PRAGMA user_version;')).values[0][0], 2);
  assert.equal((await reopened.request('exec', "SELECT balance FROM accounts WHERE space_id='__local__' AND id='a';")).values[0][0], 42);
  assert.equal((await reopened.request('exec', "SELECT hex(file_bytes) FROM receipt_files WHERE space_id='__local__' AND receipt_id='r';")).values[0][0], '010203');
  await reopened.request('exec', "INSERT INTO accounts VALUES ('other','a','Other space',99);");
  await reopened.request('begin_transaction');
  await reopened.request('end_transaction');
  assert.equal((await reopened.request('exec', 'PRAGMA foreign_keys;')).values[0][0], 1, 'export must preserve foreign key enforcement');
  await assert.rejects(reopened.request('exec', "INSERT INTO receipts VALUES ('other','bad','p','file','file',1,NULL,NULL,NULL);"), /FOREIGN KEY/);
  console.log('Web migration, rollback, failed persistence, reload, scoped keys and foreign keys passed.');
})().catch(error => { console.error(error); process.exitCode = 1; });
