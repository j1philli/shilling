@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package finance.shilling.shared.data.store

import org.w3c.dom.Worker

/**
 * Request-scoped transport for SQLDelight 2.4.0's WebWorkerDriver.
 *
 * Its Wasm WorkerWrapper leaves an error listener (and the completed coroutine)
 * attached after every successful SQL request. Keep SQLDelight's driver, bindings,
 * transactions and Store5 integration, but release BOTH callbacks on completion.
 * This facade is private to the driver's register-message/register-error/postMessage
 * protocol; it is not a general-purpose DOM Worker replacement.
 *
 * Revisit when upgrading SQLDelight:
 * https://github.com/sqldelight/sqldelight/blob/2.4.0/drivers/web-worker-driver/src/wasmJsMain/kotlin/app/cash/sqldelight/driver/worker/WorkerWrapper.kt
 */
fun createDatabaseWorker(url: String): Worker = requestScopedWorker(url).unsafeCast<Worker>()

private fun requestScopedWorker(url: String): JsAny = js("""(function() {
    const worker = new Worker(url);
    const pending = new Map();
    const callbacks = new Map();
    let registering = {};

    function release(id) {
        const request = pending.get(id);
        if (request) {
            pending.delete(id);
            callbacks.delete(request.message);
            callbacks.delete(request.error);
        }
        return request;
    }

    function failAll(event) {
        for (const id of Array.from(pending.keys())) {
            const request = release(id);
            if (request) request.error(event);
        }
    }

    function onMessage(event) {
        // The SQL worker replies once with the original request id, including SQL
        // failures. Late responses to cancelled requests have no callback to run.
        const request = release(event.data?.id);
        if (request) request.message(event);
    }
    worker.addEventListener('message', onMessage);
    worker.addEventListener('error', failAll);
    worker.addEventListener('messageerror', failAll);

    return {
        addEventListener(type, callback) {
            if (type !== 'message' && type !== 'error') {
                throw new Error('Unsupported SQLDelight listener: ' + type);
            }
            registering[type] = callback;
        },
        removeEventListener(type, callback) {
            if (registering[type] === callback) delete registering[type];
            if (callbacks.has(callback)) release(callbacks.get(callback));
        },
        postMessage(message) {
            const request = registering;
            registering = {};
            if (!request.message || !request.error || pending.has(message.id)) {
                throw new Error('Invalid SQLDelight worker request');
            }
            pending.set(message.id, request);
            callbacks.set(request.message, message.id);
            callbacks.set(request.error, message.id);
            try {
                worker.postMessage(message);
            } catch (error) {
                release(message.id);
                throw error;
            }
        },
        terminate() {
            pending.clear();
            callbacks.clear();
            registering = {};
            worker.removeEventListener('message', onMessage);
            worker.removeEventListener('error', failAll);
            worker.removeEventListener('messageerror', failAll);
            worker.terminate();
        }
    };
})()""")
