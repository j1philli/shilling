// Diagnostic SQL range-read candidate, intentionally not used by production SQL storage.
// Run with: node --expose-gc scripts/perf/bench_receipt_reads.cjs
const initSqlJs = require('sql.js');
const {performance} = require('node:perf_hooks');
(async () => {
  const SQL = await initSqlJs();
  for (const mib of [1, 10, 50]) {
    const db = new SQL.Database();
    db.run('CREATE TABLE files(id TEXT PRIMARY KEY, bytes BLOB)');
    db.run("INSERT INTO files VALUES ('r', zeroblob(?))", [mib * 1048576]);
    const results = [];
    for (let pass = 0; pass < 3; pass++) for (const range of [false, true]) {
      global.gc?.();
      let bytes = 0;
      const started = performance.now();
      const query = db.prepare(range
        ? 'SELECT CAST(substr(bytes,CAST(? AS INTEGER),CAST(? AS INTEGER)) AS BLOB) FROM files WHERE id=?'
        : 'SELECT bytes FROM files WHERE id=?');
      try {
        if (range) for (let offset = 0; offset < mib * 1048576; offset += 262144) {
          query.bind([String(offset + 1), '262144', 'r']);
          if (query.step()) bytes += query.get()[0].length;
          query.reset();
        } else {
          query.bind(['r']);
          if (query.step()) bytes = query.get()[0].length;
        }
      } finally { query.free(); }
      if (bytes !== mib * 1048576) throw Error('Missing synthetic receipt bytes');
      results.push({range, milliseconds: performance.now() - started, bytes});
    }
    console.log(JSON.stringify({mib, results}));
    db.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
