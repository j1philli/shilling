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
let saving = false;

function isReadOnlySql(sql) {
  const statement = sql.replace(/^\s*(?:(?:--[^\n]*\n)|(?:\/\*[\s\S]*?\*\/))*/, "").trim();
  const semicolon = statement.indexOf(";");
  if (semicolon !== -1 && statement.slice(semicolon + 1).trim()) return false;
  return /^(SELECT|EXPLAIN)\b/i.test(statement) ||
    (/^PRAGMA\b/i.test(statement) && !statement.includes("="));
}

function armSave(delay = 100) {
  if (saveTimer === null && !saving && !inTransaction) {
    saveTimer = setTimeout(persistDatabase, delay);
  }
}

function scheduleSave() {
  dirty = true;
  armSave();
}

async function persistDatabase() {
  saveTimer = null;
  if (!dirty || !db || !idb || inTransaction || saving) return;
  dirty = false;
  saving = true;
  let retryDelay = 100;
  try {
    const foreignKeys = db.exec("PRAGMA foreign_keys;")[0]?.values[0]?.[0] ?? 1;
    const data = db.export();
    // sql.js export resets connection pragmas. Keep the scoped schema constraints active.
    db.run(`PRAGMA foreign_keys = ${foreignKeys ? "ON" : "OFF"};`);
    await saveToIndexedDB(idb, data);
  } catch (err) {
    dirty = true;
    retryDelay = 1000;
    console.error("Failed to persist database to IndexedDB:", err);
  } finally {
    saving = false;
    if (dirty) armSave(retryDelay);
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
      });
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
      if (transactionDirty) scheduleSave();
      else if (dirty) armSave();
      transactionDirty = false;
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
