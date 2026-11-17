const fs = require("node:fs"),
  path = require("node:path"),
  { execFileSync } = require("node:child_process");
const { chromium } = require(
  process.cwd() + "/scripts/ci/smoke/node_modules/playwright",
);
// macOS only. Run from the repository root after installing scripts/ci/smoke.
// ROOT contains fixture/, production/, seed.sqlite, completed.sqlite and their
// *-settings.json files. Only use synthetic snapshots created by the Store5 fixture.
// Serve those asset directories with COOP/COEP headers on loopback port 18193.
// Usage: node scripts/perf/profile_web_memory.cjs ROOT full|production|empty LABEL [DPR]
const root = path.resolve(process.argv[2] || ""),
  mode = process.argv[3],
  label = process.argv[4];
if (
  process.argv.length < 5 ||
  !["full", "production", "empty"].includes(mode) ||
  !/^[a-z0-9-]+$/.test(label || "")
)
  throw Error(
    "Usage: profile_web_memory.cjs ROOT full|production|empty LABEL [DPR]",
  );
const dpr = Number(process.argv[5] || 1);
if (![1, 2].includes(dpr)) throw Error("DPR must be 1 or 2");
const profile = path.join(root, label + "-profile");
if (fs.existsSync(profile))
  throw Error(
    "Use a new label: refusing to modify an existing browser profile",
  );
const prod = mode === "production" || mode === "empty",
  empty = mode === "empty",
  seed = prod ? "completed" : "seed",
  base = "http://127.0.0.1:18193/" + (prod ? "production" : "fixture");
const options = {
  channel: "chrome",
  headless: false,
  chromiumSandbox: true,
  viewport: { width: 1200, height: 900 },
  deviceScaleFactor: dpr,
  args: ["--window-size=1200,900"],
};
function snapshots(processInfo) {
  return JSON.parse(
    execFileSync(
      "python3",
      [
        path.join(__dirname, "process_footprint.py"),
        ...processInfo.map((p) => String(p.id)),
      ],
      { encoding: "utf8", timeout: 10000 },
    ),
  );
}
let ownedContext;
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
(async () => {
  let context = await chromium.launchPersistentContext(profile, options);
  ownedContext = context;
  let page = context.pages()[0];
  await page.goto(base + "/bootstrap.html");
  await page.evaluate(
    async ({ data, settings, name }) => {
      for (const [k, v] of Object.entries(settings)) localStorage.setItem(k, v);
      if (!data) return;
      const bytes = Uint8Array.from(atob(data), (c) => c.charCodeAt(0));
      await new Promise((resolve, reject) => {
        const r = indexedDB.open(name, 1);
        r.onupgradeneeded = () => r.result.createObjectStore("database");
        r.onerror = () => reject(r.error);
        r.onblocked = () => reject(Error("IndexedDB blocked"));
        r.onsuccess = () => {
          const d = r.result,
            t = d.transaction("database", "readwrite");
          t.objectStore("database").put(bytes, "sqlite");
          t.oncomplete = () => {
            d.close();
            resolve();
          };
          t.onerror = () => reject(t.error);
        };
      });
    },
    {
      data: empty
        ? null
        : fs.readFileSync(root + "/" + seed + ".sqlite").toString("base64"),
      settings: JSON.parse(
        fs.readFileSync(root + "/" + seed + "-settings.json"),
      ),
      name: prod ? "shilling" : "shilling-memory-fixture-v1",
    },
  );
  await context.close();
  context = await chromium.launchPersistentContext(profile, options);
  ownedContext = context;
  page = context.pages()[0];
  const cdp = await context.browser().newBrowserCDPSession(),
    pcdp = await context.newCDPSession(page);
  await pcdp.send("Performance.enable");
  const result = {
    label,
    browserVersion: context.browser().version(),
    mode,
    metric:
      "macOS proc_pid_rusage physical footprint MiB (validated against vmmap); process snapshots sequential",
    notes:
      "Whole-browser headline excludes the separate CDP tracing service. It includes browser UI, spare renderer and shared browser services, so it is not incremental per-tab memory. No forced garbage collection.",
    configuration: {
      viewport: options.viewport,
      deviceScaleFactor: dpr,
      headless: false,
    },
    phases: [],
    complete: false,
    errors: [],
  };
  const save = () =>
    fs.writeFileSync(
      root + "/" + label + ".json",
      JSON.stringify(result, null, 2) + "\n",
    );
  async function measure(name) {
    const snapshotStartedMs = Date.now() - started;
    const { processInfo } = await cdp.send("SystemInfo.getProcessInfo");
    const sizes = snapshots(processInfo);
    const processes = processInfo.map((p) => ({
      pid: p.id,
      type: p.type,
      ...sizes[p.id],
    }));
    const metrics = await pcdp.send("Performance.getMetrics");
    const sample = {
      name,
      snapshotStartedMs,
      partial: processes.some((p) => p.error),
      appRendererFootprintMiB: processes.find(
        (p) => p.pid === result.appRendererPid,
      )?.footprintMiB,
      elapsedMs: Date.now() - started,
      processes,
      totalFootprintMiB: processes.reduce(
        (a, p) => a + (p.footprintMiB || 0),
        0,
      ),
      browserFootprintExcludingProfilerMiB: processes
        .filter((p) => p.type !== "tracing.mojom.TracingService")
        .reduce((s, p) => s + (p.footprintMiB || 0), 0),
      rendererFootprintMiB: processes
        .filter((p) => p.type === "renderer")
        .reduce((a, p) => a + (p.footprintMiB || 0), 0),
      mainJsHeapBytes: metrics.metrics.find((v) => v.name === "JSHeapUsedSize")
        ?.value,
    };
    result.phases.push(sample);
    save();
    console.log(JSON.stringify(sample));
  }
  let queue = Promise.resolve(),
    finished = false;
  const started = Date.now();
  page.on("pageerror", (e) => {
    result.errors.push(e.message);
    console.error("PAGE ERROR", e.message);
  });
  page.on("console", (m) => {
    const t = m.text();
    if (!t.startsWith("SHILLING_MEMORY ")) return;
    const phase = t.slice(16);
    console.log(phase);
    if (phase.startsWith("READY ")) queue = queue.then(() => measure(phase));
    if (phase === "COMPLETE")
      queue = queue.then(() => {
        finished = true;
        result.complete = true;
        save();
      });
    if (phase.startsWith("ERROR ")) {
      result.errors.push(phase);
      finished = true;
      save();
    }
  });
  await wait(15000);
  await measure("blank_browser_15s");
  await page.goto(base + "/index.html", { timeout: 60000 });
  const traceEvents = [];
  pcdp.on("Tracing.dataCollected", (e) => traceEvents.push(...e.value));
  await pcdp.send("Tracing.start", {
    categories: "devtools.timeline",
    transferMode: "ReportEvents",
  });
  await page.evaluate(() =>
    console.timeStamp("shilling-renderer-identification"),
  );
  const traceDone = new Promise((resolve) =>
    pcdp.once("Tracing.tracingComplete", resolve),
  );
  await pcdp.send("Tracing.end");
  await traceDone;
  result.appRendererPid = traceEvents.find(
    (e) =>
      e.name === "TimeStamp" &&
      e.args?.data?.message === "shilling-renderer-identification",
  )?.pid;
  if (!result.appRendererPid)
    throw Error("Could not identify the app renderer");
  result.configuration.page = await page.evaluate(() => ({
    userAgent: navigator.userAgent,
    dpr: devicePixelRatio,
    width: innerWidth,
    height: innerHeight,
    tauri: typeof window.__TAURI__,
    crossOriginIsolated,
    visibility: document.visibilityState,
    canvas: document.querySelector("canvas")
      ? {
          width: document.querySelector("canvas").width,
          height: document.querySelector("canvas").height,
        }
      : null,
  }));
  save();
  if (prod) {
    await page
      .getByRole("button", { name: "Activity", exact: true })
      .waitFor({ timeout: 60000 });
    await wait(30000);
    await measure("READY production_home");
    await wait(60000);
    await measure("READY production_idle");
    result.complete = true;
    save();
  } else {
    const deadline = Date.now() + 660000;
    while (!finished && Date.now() < deadline) await wait(1000);
    await queue;
    if (!finished) throw Error("Workload timeout");
  }
  await page.screenshot({ path: root + "/" + label + ".png" });
  const worker = page.workers().find((w) => w.url().includes("sqldelight"));
  result.workerUrls = page.workers().map((w) => w.url());
  if (!worker) throw Error("SQL worker not found");
  result.integrity = await worker.evaluate(() => ({
    integrity: db.exec("PRAGMA integrity_check")[0].values[0][0],
    postings: db.exec("SELECT COUNT(*) FROM postings")[0].values[0][0],
    imported: db.exec(
      "SELECT COUNT(*) FROM postings WHERE title LIKE 'Synthetic CSV transaction %'",
    )[0].values[0][0],
    versions: db.exec(
      "SELECT COUNT(*) FROM change_log v JOIN postings p ON v.entity_id=p.id AND v.household_id=p.space_id WHERE p.title LIKE 'Synthetic CSV transaction %' AND v.entity_type='POSTING'",
    )[0].values[0][0],
  }));
  result.verified =
    result.complete &&
    result.integrity.integrity === "ok" &&
    result.errors.length === 0 &&
    !result.phases.some((phase) => phase.partial);
  save();
  if (!result.verified)
    throw Error(
      "Workload, page, process sampling or integrity validation failed",
    );
  await context.close();
  console.log("COMPLETE", label);
})().catch(async (e) => {
  console.error(e);
  if (ownedContext) await ownedContext.close().catch(() => {});
  process.exitCode = 1;
});
