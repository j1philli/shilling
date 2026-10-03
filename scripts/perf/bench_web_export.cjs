// Synthetic sql.js export benchmark for the web worker's db.export() path.
// Run with: node --expose-gc scripts/perf/bench_web_export.cjs
const initSqlJs = require('sql.js');
const { performance } = require('node:perf_hooks');

(async () => {
  const SQL = await initSqlJs();
  for (const mib of [1, 10, 50]) {
    const db = new SQL.Database();
    db.run('CREATE TABLE receipt_files (id INTEGER PRIMARY KEY, bytes BLOB)');
    db.run('INSERT INTO receipt_files (bytes) VALUES (zeroblob(?))', [mib * 1024 * 1024]);
    const samples = [];
    let bytes = 0;
    for (let i = 0; i < 7; i++) {
      global.gc?.();
      const start = performance.now();
      const exported = db.export();
      samples.push(performance.now() - start);
      bytes = exported.byteLength;
    }
    samples.sort((a, b) => a - b);
    console.log(`${mib} MiB payload: ${samples[3].toFixed(2)} ms median export, ${(bytes / 1048576).toFixed(2)} MiB written`);
    db.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
