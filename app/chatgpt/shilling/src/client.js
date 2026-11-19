import { pages } from "./config.mjs";

function fail(message) {
  // Compose owns and may replace document.body's contents after startup.
  let display = document.getElementById("error-display");
  if (!display) {
    display = document.createElement("div");
    display.id = "error-display";
    display.style.cssText = "position:fixed;inset:0;background:#1a1a2e;padding:24px;z-index:9999;font:16px system-ui;white-space:pre-wrap";
    document.body.appendChild(display);
  }
  display.style.display = "block";
  display.style.color = "#eee";
  display.textContent = message;
}

async function checkStorage() {
  const key = "shilling-chatgpt-storage-check";
  localStorage.setItem(key, "ok");
  if (localStorage.getItem(key) !== "ok") throw new Error("Browser settings storage is unavailable.");
  localStorage.removeItem(key);
  await new Promise((resolve, reject) => {
    const request = indexedDB.open(key, 1);
    request.onupgradeneeded = () => request.result.createObjectStore("probe");
    request.onerror = () => reject(new Error("Browser database storage is unavailable."));
    request.onsuccess = () => {
      const db = request.result;
      const tx = db.transaction("probe", "readwrite");
      tx.objectStore("probe").put("ok", "check");
      tx.oncomplete = () => { db.close(); resolve(); };
      tx.onabort = tx.onerror = () => { db.close(); reject(new Error("Browser database writes are blocked.")); };
    };
  });
}

async function start() {
  if (!isSecureContext || typeof WebAssembly !== "object" || typeof Worker !== "function") {
    throw new Error("This host cannot run Shilling's web client. Open Shilling in a supported browser.");
  }
  const canvas = document.createElement("canvas");
  const gl = canvas.getContext("webgl2");
  if (!gl) throw new Error("This host does not provide the graphics support Shilling needs. Open it in a supported browser.");
  gl.getExtension("WEBGL_lose_context")?.loseContext();
  if (typeof RTCPeerConnection !== "function") {
    throw new Error("This host does not support WebRTC. Shilling cannot sync here; open it in a supported browser.");
  }
  const peer = new RTCPeerConnection({ iceServers: [] });
  peer.createDataChannel("capability-check");
  peer.close();
  await checkStorage();
  if (!navigator.locks) throw new Error("This host cannot protect Shilling from concurrent database writes. Open it in a supported browser.");
  await navigator.locks.request("shilling-chatgpt-database", { ifAvailable: true }, async (lock) => {
    if (!lock) throw new Error("Shilling is already open in another panel or tab. Close that view, then reload this one.");
    // Host can request navigation only. It never receives database content.
    window.addEventListener("message", (event) => {
      if (event.source !== window.parent || event.source === window ||
          event.data?.type !== "shilling:navigate" || !pages.includes(event.data.page)) return;
      window.location.hash = `/${event.data.page}`;
    });
    window.addEventListener("shilling:navigation-ready", () => {
      document.documentElement.dataset.shillingReady = "true";
      // No data, routes or identities are sent to the embedding host.
      if (window.parent !== window) window.parent.postMessage({ type: "shilling:ready" }, "*");
    });
    await import("./web-app.mjs");
    // The browser releases the lock when this document is destroyed.
    await new Promise(() => {});
  });
}

window.onerror = () => { fail("Shilling encountered an error. Reload this panel or open Shilling in your browser."); };
window.addEventListener("unhandledrejection", () => fail("Shilling encountered an error. Reload this panel or open Shilling in your browser."));
start().catch((error) => fail(error.message || "Shilling could not start in this host."));
