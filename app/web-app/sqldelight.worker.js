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

function scheduleSave() {
  if (inTransaction) return;
  if (saveTimer !== null) clearTimeout(saveTimer);
  saveTimer = setTimeout(() => persistDatabase().catch(err => console.error("Failed to persist database:", err)), 100);
}

async function persistDatabase() {
  saveTimer = null;
  if (!db || !idb || inTransaction) return;
  const foreignKeys = db.exec("PRAGMA foreign_keys;")[0]?.values[0]?.[0] ?? 1;
  const data = db.export();
  // sql.js export closes/reopens the database and resets connection pragmas.
  db.run(`PRAGMA foreign_keys = ${foreignKeys ? "ON" : "OFF"};`);
  await saveToIndexedDB(idb, data);
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
      scheduleSave();
      return postMessage({
        id: data.id,
        results: results
      });
    case "begin_transaction":
      if (saveTimer !== null) { clearTimeout(saveTimer); saveTimer = null; }
      inTransaction = true;
      return postMessage({
        id: data.id,
        results: db.exec("BEGIN TRANSACTION;")
      })
    case "end_transaction": {
      const txResults = db.exec("END TRANSACTION;");
      inTransaction = false;
      await persistDatabase();
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
let requests = Promise.resolve();
self.onmessage = (event) => {
  requests = requests.then(() => sqlModuleReady)
    .then(onModuleReady.bind(event))
    .catch(onError.bind(event));
}
