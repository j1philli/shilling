// Fixture only: separate module/runtime startup from application startup.
// Keep the production Wasm binaries and import object unchanged. There is no
// forced GC, private WebKit API, or application database access in these stages.
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const status = document.createElement('p');
status.style.cssText = 'font: 20px system-ui; padding: 32px';
status.textContent = 'Preparing WebContent allocation diagnostic…';
document.body.append(status);

async function checkpoint(phase) {
    status.textContent = `WebContent allocation diagnostic: ${phase}`;
    await delay(15000);
    console.info('SHILLING_BOOTSTRAP ' + JSON.stringify({
        phase, visibility: document.visibilityState,
        dpr: devicePixelRatio, viewport: [innerWidth, innerHeight]
    }));
    console.log('SHILLING_MEMORY READY ' + phase);
    await delay(15000);
}

try {
    await delay(20000); // Allow the native profiler to attach.
    await checkpoint('bootstrap_empty');
    let module = await WebAssembly.compileStreaming(fetch('./perf-web.wasm'), {
        builtins: ['js-string'], importedStringConstants: "'"
    });
    await checkpoint('bootstrap_kotlin_compiled');
    const { importObject, setWasmExports } = await import('./perf-web.import-object.mjs');
    await (await import('./skiko.mjs')).awaitSkiko;
    await checkpoint('bootstrap_imports_skia');
    const instance = await WebAssembly.instantiate(module, importObject);
    // The generated loader exposes memory through a compatibility proxy. Its
    // import object only reads wasmExports.memory; use that same memory here.
    setWasmExports({ memory: importObject.intrinsics.memory });
    module = null;
    await checkpoint('bootstrap_kotlin_instantiated');
    status.remove();
    instance.exports._start();
    // The ordinary fixture now opens Home and completes its idle workload.
} catch (error) {
    status.textContent = String(error);
    console.log('SHILLING_MEMORY ERROR ' + (error.stack || error));
}
