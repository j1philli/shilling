@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
package finance.shilling.web

import finance.shilling.shared.data.backup.BackupKeyVault
import kotlinx.coroutines.await
import kotlin.js.Promise

/** Browser-managed non-extractable wrapping key; only encrypted secrets are stored in IndexedDB. */
class WebBackupKeyVault : BackupKeyVault {
    override suspend fun read(namespace: String): String? = vaultOperation("read", namespace, "").await().toString().ifEmpty { null }
    override suspend fun write(namespace: String, secret: String) { vaultOperation("write", namespace, secret).await() }
    override suspend fun delete(namespace: String) { vaultOperation("delete", namespace, "").await() }
}

private fun vaultOperation(op: String, name: String, value: String): Promise<JsString> = js("""
(async () => {
  if (!globalThis.crypto?.subtle) throw new Error('Secure key storage requires HTTPS');
  const db = await new Promise((resolve, reject) => {
    const req = indexedDB.open('shilling-secure-backup-v1', 1);
    req.onupgradeneeded = () => req.result.createObjectStore('secrets');
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(new Error('Unable to open secure key storage'));
  });
  const transact = (mode, action) => new Promise((resolve, reject) => {
    const tx = db.transaction('secrets', mode);
    let result;
    action(tx.objectStore('secrets'), v => result = v);
    tx.oncomplete = () => resolve(result);
    tx.onerror = tx.onabort = () => reject(new Error('Unable to persist recovery key'));
  });
  const get = key => transact('readonly', (store, done) => { const r = store.get(key); r.onsuccess = () => done(r.result); });
  try {
    if (op === 'delete') { await transact('readwrite', store => store.delete('secret:' + name)); return ''; }
    let key = await get('wrapping-key');
    if (!key && op === 'read') return '';
    if (!key) {
      const generated = await crypto.subtle.generateKey({name:'AES-GCM',length:256}, false, ['encrypt','decrypt']);
      key = await transact('readwrite', (store, done) => {
        const r = store.get('wrapping-key');
        r.onsuccess = () => { const chosen = r.result || generated; if (!r.result) store.put(chosen, 'wrapping-key'); done(chosen); };
      });
    }
    const aad = new TextEncoder().encode(name);
    if (op === 'read') {
      const record = await get('secret:' + name);
      if (!record) return '';
      return new TextDecoder().decode(await crypto.subtle.decrypt({name:'AES-GCM',iv:record.iv,additionalData:aad}, key, record.data));
    }
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const data = await crypto.subtle.encrypt({name:'AES-GCM',iv,additionalData:aad}, key, new TextEncoder().encode(value));
    await transact('readwrite', store => store.put({iv,data}, 'secret:' + name));
    return '';
  } finally { db.close(); }
})()
""")
