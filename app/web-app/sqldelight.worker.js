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
    request.onsuccess = () => resolve();
    request.onerror = () => reject(request.error);
  });
}

let db = null;
let idb = null;
let saveTimer = null;
let inTransaction = false;

function scheduleSave() {
  if (inTransaction) return;
  if (saveTimer !== null) clearTimeout(saveTimer);
  saveTimer = setTimeout(persistDatabase, 100);
}

function persistDatabase() {
  saveTimer = null;
  if (!db || !idb) return;
  const data = db.export();
  saveToIndexedDB(idb, data).catch((err) => {
    console.error("Failed to persist database to IndexedDB:", err);
  });
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
}

function onModuleReady() {
  const data = this.data;

  switch (data && data.action) {
    case "exec":
      if (!data["sql"]) {
        throw new Error("exec: Missing query string");
      }

      // Make schema creation idempotent so Schema.create() is safe on a persisted DB
      let sql = data.sql;
      sql = sql.replace(/\bCREATE\s+TABLE\b(?!\s+IF\s+NOT\s+EXISTS)/gi, "CREATE TABLE IF NOT EXISTS");
      sql = sql.replace(/\bCREATE\s+INDEX\b(?!\s+IF\s+NOT\s+EXISTS)/gi, "CREATE INDEX IF NOT EXISTS");

      const results = db.exec(sql, data.params)[0] ?? { values: [] };
      scheduleSave();
      return postMessage({
        id: data.id,
        results: results
      });
    case "begin_transaction":
      inTransaction = true;
      return postMessage({
        id: data.id,
        results: db.exec("BEGIN TRANSACTION;")
      })
    case "end_transaction": {
      const txResults = db.exec("END TRANSACTION;");
      inTransaction = false;
      scheduleSave();
      return postMessage({
        id: data.id,
        results: txResults
      })
    }
    case "rollback_transaction":
      inTransaction = false;
      return postMessage({
        id: data.id,
        results: db.exec("ROLLBACK TRANSACTION;")
      })
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
self.onmessage = (event) => {
  return sqlModuleReady
    .then(onModuleReady.bind(event))
    .catch(onError.bind(event));
}
