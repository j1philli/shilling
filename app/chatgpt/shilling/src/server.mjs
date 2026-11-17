import express from "express";
import { once } from "node:events";
import { readFile, access } from "node:fs/promises";
import { fileURLToPath, pathToFileURL } from "node:url";
import { join } from "node:path";
import { createMcpExpressApp } from "@modelcontextprotocol/sdk/server/express.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { configFromEnv } from "./config.mjs";
import { createServer } from "./mcp.mjs";

const root = fileURLToPath(new URL("../", import.meta.url));

export async function createHttpApp(config) {
  const script = await readFile(join(root, "dist/widget.js"), "utf8");
  await access(join(root, "dist/client/web-app.wasm"));
  const host = new URL(config.origin).hostname;
  const app = createMcpExpressApp({ host: config.bind, allowedHosts: [host, "localhost", "127.0.0.1", "[::1]"] });
  app.disable("x-powered-by");
  app.use((_req, res, next) => {
    res.setHeader("X-Content-Type-Options", "nosniff");
    res.setHeader("Referrer-Policy", "no-referrer");
    res.setHeader("Cache-Control", "no-store");
    next();
  });
  app.post("/mcp", async (req, res) => {
    // No user identity, entity data, cookies, or per-chat state in this service.
    if (req.headers.origin && req.headers.origin !== config.origin && req.headers.origin !== "https://chatgpt.com") {
      res.status(403).end(); return;
    }
    const server = createServer(config, script);
    const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined, enableJsonResponse: true });
    res.on("close", () => { transport.close().catch(() => {}); server.close().catch(() => {}); });
    try {
      await server.connect(transport);
      await transport.handleRequest(req, res, req.body);
    } catch {
      if (!res.headersSent) res.status(500).json({ jsonrpc: "2.0", id: null, error: { code: -32603, message: "MCP request failed" } });
    }
  });
  app.all("/mcp", (_req, res) => res.status(405).set("Allow", "POST").end());
  app.get("/healthz", (_req, res) => res.json({ status: "ok", target: "shilling-chatgpt" }));
  app.get("/icon.svg", (_req, res) => res.sendFile(join(root, "assets/icon.svg")));
  app.use("/client", (_req, res, next) => {
    // Do not copy the standalone server's COOP/COEP/CORP same-origin headers:
    // this target is embedded and uses workers, not SharedArrayBuffer.
    res.setHeader("Content-Security-Policy", "frame-ancestors 'self' https://chatgpt.com https://*.chatgpt.com https://*.oaiusercontent.com");
    next();
  }, express.static(join(root, "dist/client"), { dotfiles: "deny", etag: false, lastModified: false, maxAge: 0 }));
  app.get("/", (_req, res) => res.redirect("/client/"));
  return { app, script };
}

async function main() {
  const config = configFromEnv();
  const { app, script } = await createHttpApp(config);
  const http = app.listen(config.port, config.bind, () => console.error(`Shilling ChatGPT target: ${config.origin}/mcp`));
  await once(http, "listening");
  let stdio;
  if (process.argv.includes("--stdio")) {
    stdio = createServer(config, script);
    await stdio.connect(new StdioServerTransport());
    process.stdin.on("end", () => http.close());
  }
  const stop = async () => { await stdio?.close(); http.close(); };
  process.on("SIGINT", stop);
  process.on("SIGTERM", stop);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => { console.error(error.message); process.exitCode = 1; });
}
