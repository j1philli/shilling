// Optional synthetic-fixture diagnostics. These are capacities/cache counters,
// not the process footprint or a measurement of Kotlin's Wasm GC heap.
(() => {
    const workers = [];
    const NativeWorker = window.Worker;
    window.Worker = new Proxy(NativeWorker, {
        construct(target, args, newTarget) {
            const worker = Reflect.construct(target, args, newTarget);
            workers.push(new WeakRef(worker));
            worker.addEventListener('message', event => {
                if (event.data?.shillingAllocationProfile) {
                    console.info('SHILLING_ALLOCATION_SQL ' + JSON.stringify(event.data.shillingAllocationProfile));
                }
            });
            return worker;
        }
    });
    let skia;
    import('./skiko.mjs').then(module => { skia = module; });
    const log = console.log;
    console.log = function (...args) {
        if (typeof args[0] === 'string' && args[0].startsWith('SHILLING_MEMORY READY ')) {
            const phase = args[0].slice('SHILLING_MEMORY '.length);
            for (const ref of workers) ref.deref()?.postMessage({ shillingAllocationProfile: phase });
            const names = ['GetFontCacheUsed', 'GetFontCacheLimit', 'GetResourceCacheTotalBytesUsed',
                'GetResourceCacheTotalByteLimit', 'GetFontCacheCountUsed'];
            const counters = skia ? Object.fromEntries(names.map(name =>
                [name, String(skia['org_jetbrains_skia_GraphicsKt__1n' + name]())])) : null;
            console.info('SHILLING_ALLOCATION_MAIN ' + JSON.stringify({ phase, skia: counters,
                dpr: devicePixelRatio, viewport: [innerWidth, innerHeight], visibility: document.visibilityState }));
        }
        log.apply(console, args);
    };
})();
