// Opt-in synthetic benchmark diagnostics. Count rows, never retain result arrays
// or parameter values. This observes the production worker; it performs no I/O.
(function () {
    const NativeWorker = window.Worker;
    const totals = new Map();
    let snapshots = {count: 0, bytes: 0};
    window.Worker = class extends NativeWorker {
        constructor(...args) {
            super(...args);
            this.requests = new Map();
            this.addEventListener('message', event => {
                if (event.data?.shillingPersistenceProfile) {
                    snapshots = event.data.shillingPersistenceProfile;
                    return;
                }
                const sql = this.requests.get(event.data?.id);
                this.requests.delete(event.data?.id);
                if (!sql) return;
                const count = event.data.results?.values?.length || 0;
                const previous = totals.get(sql) || { calls: 0, rows: 0, largestReply: 0 };
                previous.calls++;
                previous.rows += count;
                previous.largestReply = Math.max(previous.largestReply, count);
                totals.set(sql, previous);
            });
            this.addEventListener('error', () => this.requests.clear());
        }
        postMessage(message, ...rest) {
            this.requests.set(message.id, message.sql || message.action);
            super.postMessage(message, ...rest);
        }
        terminate() { this.requests.clear(); super.terminate(); }
    };
    const log = console.log;
    console.log = function (...args) {
        if (typeof args[0] === 'string' && args[0].startsWith('SHILLING_MEMORY ')) {
            const queries = Array.from(totals, ([sql, stats]) => ({sql: sql.replace(/\s+/g, ' ').slice(0, 160), ...stats}));
            console.info('SHILLING_SQL_PROFILE ' + JSON.stringify({phase: args[0].slice('SHILLING_MEMORY '.length),
                calls: queries.reduce((n, q) => n + q.calls, 0),
                rows: queries.reduce((n, q) => n + q.rows, 0),
                commits: totals.get('end_transaction')?.calls || 0,
                snapshots,
                queries: queries.sort((a, b) => b.rows - a.rows).slice(0, 12)}));
        }
        log.apply(console, args);
    };
})();
