// Optional fixture-only counters. Weak references avoid keeping runtime memory
// alive, and no application records, SQL values or formatted dates are retained.
(function () {
    let dateFormats = 0;
    const NativeDateTimeFormat = Intl.DateTimeFormat;
    Intl.DateTimeFormat = new Proxy(NativeDateTimeFormat, {
        construct(target, args, newTarget) {
            dateFormats++;
            return Reflect.construct(target, args, newTarget);
        },
        apply(target, receiver, args) {
            dateFormats++;
            return Reflect.apply(target, receiver, args);
        }
    });

    const NativeMemory = WebAssembly.Memory;
    const memories = [];
    const seen = new WeakSet();
    function record(memory) {
        if (memory instanceof NativeMemory && !seen.has(memory)) {
            seen.add(memory);
            memories.push(new WeakRef(memory));
        }
    }
    WebAssembly.Memory = new Proxy(NativeMemory, {
        construct(target, args, newTarget) {
            const memory = Reflect.construct(target, args, newTarget);
            record(memory);
            return memory;
        }
    });
    for (const name of ['instantiate', 'instantiateStreaming']) {
        const original = WebAssembly[name];
        WebAssembly[name] = async function (...args) {
            const result = await original.apply(this, args);
            for (const value of Object.values((result.instance || result).exports)) record(value);
            return result;
        };
    }
    const log = console.log;
    console.log = function (...args) {
        if (typeof args[0] === 'string' && args[0].startsWith('SHILLING_MEMORY ')) {
            console.info('SHILLING_RUNTIME_PROFILE ' + JSON.stringify({
                phase: args[0].slice('SHILLING_MEMORY '.length),
                visibility: document.visibilityState,
                dpr: devicePixelRatio, viewport: [innerWidth, innerHeight],
                dateFormats,
                // Linear capacity is not process footprint, and excludes Wasm GC objects.
                linearMemoryBytes: memories.map(ref => ref.deref()?.buffer.byteLength ?? null)
            }));
        }
        log.apply(console, args);
    };
})();
