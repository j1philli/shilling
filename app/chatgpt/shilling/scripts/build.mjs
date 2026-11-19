import { build } from "esbuild";
import { cp, mkdir, readFile, rm, writeFile, access } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { resolve, join } from "node:path";

const root = fileURLToPath(new URL("../", import.meta.url));
const web = resolve(root, "../../../web-app-dist");
const dist = join(root, "dist");
const source = await readFile(join(web, "index.html"), "utf8").catch(() => {
  throw new Error("Build the shared web client first: mise run build-chatgpt");
});
for (const asset of ["web-app.mjs", "web-app.wasm", "sqldelight.worker.js", "sql-wasm.wasm"]) await access(join(web, asset));
const loader = "import './web-app.mjs';";
if (!source.includes(loader)) throw new Error("Web entrypoint changed: cannot install ChatGPT compatibility checks");
await rm(dist, { recursive: true, force: true });
await mkdir(dist, { recursive: true });
await cp(web, join(dist, "client"), { recursive: true });
// A normal web/desktop build may have populated web-app-dist. Always isolate this
// target's database even when reusing those already compiled Wasm artifacts.
const workerPath = join(dist, "client/sqldelight.worker.js");
const worker = await readFile(workerPath, "utf8");
if (!/const DB_NAME = "[^"]+";/.test(worker)) throw new Error("SQL worker database declaration changed");
await writeFile(workerPath, worker.replace(/const DB_NAME = "[^"]+";/, 'const DB_NAME = "shilling-chatgpt";'));
await writeFile(join(dist, "client/index.html"), source.replace(loader, "await import('./chatgpt-client.js');"));
await build({ entryPoints: [join(root, "src/client.js")], bundle: true, format: "esm", outfile: join(dist, "client/chatgpt-client.js"), external: ["./web-app.mjs"] });
await build({ entryPoints: [join(root, "src/widget.js")], bundle: true, format: "iife", outfile: join(dist, "widget.js") });
console.log("Built ChatGPT shell and shared Shilling client in app/chatgpt/shilling/dist");
