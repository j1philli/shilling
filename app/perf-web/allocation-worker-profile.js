// Loaded before SQL.js in the isolated fixture only. Never retains SQL results
// or database snapshots. The build appends the installation call after the
// production worker has installed its own message handler.
(() => {
    const memories = [];
    const seen = new WeakSet();
    for (const name of ['instantiate', 'instantiateStreaming']) {
        const original = WebAssembly[name];
        WebAssembly[name] = async function (...args) {
            const result = await original.apply(this, args);
            for (const value of Object.values((result.instance || result).exports)) {
                if (value instanceof WebAssembly.Memory && !seen.has(value)) {
                    seen.add(value);
                    memories.push(new WeakRef(value));
                }
            }
            return result;
        };
    }
    self.installShillingAllocationProfile = enqueue => {
        const onMessage = self.onmessage;
        self.onmessage = event => {
            const phase = event.data?.shillingAllocationProfile;
            if (!phase) return onMessage(event);
            enqueue(db => {
                const pragma = name => db.exec('PRAGMA ' + name)[0].values[0][0];
                postMessage({ shillingAllocationProfile: { phase,
                    linearBytes: memories.map(ref => ref.deref()?.buffer.byteLength ?? null),
                    pageCount: pragma('page_count'), pageSize: pragma('page_size'), cacheSize: pragma('cache_size') } });
            });
        };
    };
})();
