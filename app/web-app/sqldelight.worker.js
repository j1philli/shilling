importScripts("sql-wasm.js");

const DB_NAME = "shilling";
const DB_STORE = "database";
const DB_KEY = "sqlite";

function openIndexedDB() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, 1);
    request.onupgradeneeded = () => {
      request.result.createObjectStore(DB_STORE);
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

function loadFromIndexedDB(idb) {
  return new Promise((resolve, reject) => {
    const tx = idb.transaction(DB_STORE, "readonly");
    const store = tx.objectStore(DB_STORE);
    const request = store.get(DB_KEY);
    request.onsuccess = () => resolve(request.result ?? null);
    request.onerror = () => reject(request.error);
  });
}

function saveToIndexedDB(idb, data) {
  return new Promise((resolve, reject) => {
    const tx = idb.transaction(DB_STORE, "readwrite");
    const store = tx.objectStore(DB_STORE);
    const request = store.put(data, DB_KEY);
    tx.oncomplete = () => resolve();
    tx.onabort = () => reject(tx.error);
    tx.onerror = () => reject(tx.error);
    request.onerror = () => reject(request.error);
  });
}

let db = null;
let idb = null;
let saveTimer = null;
let inTransaction = false;
let transactionDirty = false;
let dirty = false;
let saving = null;
let persistenceFailure = null;

function isReadOnlySql(sql) {
  const statement = sql.replace(/^\s*(?:(?:--[^\n]*\n)|(?:\/\*[\s\S]*?\*\/))*/, "").trim();
  const semicolon = statement.indexOf(";");
  if (semicolon !== -1 && statement.slice(semicolon + 1).trim()) return false;
  return /^(SELECT|EXPLAIN)\b/i.test(statement) ||
    (/^PRAGMA\b/i.test(statement) && !statement.includes("="));
}

// sql.js returns BLOB columns as fresh Uint8Arrays. Transfer their backing
// buffers to the app instead of cloning large receipt files between threads.
function resultBuffers(results) {
  const buffers = new Set();
  for (const result of results) {
    for (const row of result.values) {
      for (const value of row) {
        if (value instanceof Uint8Array && value.buffer instanceof ArrayBuffer) {
          buffers.add(value.buffer);
        }
      }
    }
  }
  return [...buffers];
}

function armSave(delay = 100) {
  if (saveTimer === null && !saving && !inTransaction && !persistenceFailure) {
    saveTimer = setTimeout(() => {
      saveTimer = null;
      persistDatabase().catch(err => {
        console.error("Failed to persist database to IndexedDB:", err);
        if (!persistenceFailure) armSave(1000);
      });
    }, delay);
  }
}

function scheduleSave() {
  dirty = true;
  armSave();
}

function persistDatabase() {
  if (saving) return saving;
  if (!dirty || !db || !idb || inTransaction) return Promise.resolve();
  dirty = false;
  saving = (async () => {
    const foreignKeys = db.exec("PRAGMA foreign_keys;")[0]?.values[0]?.[0] ?? 1;
    const data = db.export();
    // sql.js export resets connection pragmas. Keep scoped constraints active.
    db.run(`PRAGMA foreign_keys = ${foreignKeys ? "ON" : "OFF"};`);
    try {
      await saveToIndexedDB(idb, data);
    } finally {
      // export() owns a standalone copy, separate from SQL.js's Wasm heap.
      // IndexedDB has consumed its structured clone; don't retain this full-DB
      // backing buffer until the worker's next garbage collection. Older
      // engines simply collect it normally. Cleanup must never fail a commit.
      try { data.buffer.transfer?.(0); } catch (_) {}
    }
  })().catch(err => {
    dirty = true;
    throw err;
  }).finally(() => { saving = null; });
  return saving.then(() => { if (dirty) armSave(); });
}

async function flushDatabase() {
  // A transaction is acknowledged only after its snapshot is durable. Wait for
  // an older snapshot first so it cannot overwrite this commit out of order.
  try {
    if (saving) await saving;
    if (saveTimer !== null) clearTimeout(saveTimer);
    saveTimer = null;
    await persistDatabase();
  } catch (err) {
    // SQLite has committed in memory. Do not continue from this divergent state
    // or silently retry a failed migration; a fresh worker reloads durable data.
    persistenceFailure = err;
    if (saveTimer !== null) clearTimeout(saveTimer);
    saveTimer = null;
    throw err;
  }
}

async function createDatabase() {
  const SQL = await initSqlJs({ locateFile: file => file });
  idb = await openIndexedDB();
  const saved = await loadFromIndexedDB(idb);
  if (saved) {
    db = new SQL.Database(saved);
  } else {
    db = new SQL.Database();
  }
  db.run("PRAGMA foreign_keys = ON;");
}

async function onModuleReady() {
  const data = this.data;
  if (persistenceFailure) throw persistenceFailure;

  switch (data && data.action) {
    case "exec":
      if (!data["sql"]) {
        throw new Error("exec: Missing query string");
      }

      // Schema creation and migrations are versioned by DatabaseBootstrap.
      const sql = data.sql;

      const results = db.exec(sql, data.params)[0] ?? { values: [] };
      if (!isReadOnlySql(sql)) {
        if (inTransaction) transactionDirty = true;
        else scheduleSave();
      }
      return postMessage({
        id: data.id,
        results: results
      }, resultBuffers([results]));
    case "begin_transaction":
      const beginResults = db.exec("BEGIN TRANSACTION;");
      inTransaction = true;
      transactionDirty = false;
      return postMessage({
        id: data.id,
        results: beginResults
      })
    case "end_transaction": {
      const txResults = db.exec("END TRANSACTION;");
      inTransaction = false;
      if (transactionDirty) dirty = true;
      transactionDirty = false;
      await flushDatabase();
      return postMessage({
        id: data.id,
        results: txResults
      })
    }
    case "rollback_transaction": {
      const rollbackResults = db.exec("ROLLBACK TRANSACTION;");
      inTransaction = false;
      transactionDirty = false;
      if (dirty) armSave();
      return postMessage({
        id: data.id,
        results: rollbackResults
      })
    }
    default:
      throw new Error(`Unsupported action: ${data && data.action}`);
  }
}

function onError(err) {
  return postMessage({
    id: this.data.id,
    error: err
  });
}

const sqlModuleReady = createDatabase()
let requests = Promise.resolve();
self.onmessage = (event) => {
  requests = requests.then(() => sqlModuleReady)
    .then(onModuleReady.bind(event))
    .catch(onError.bind(event));
}
