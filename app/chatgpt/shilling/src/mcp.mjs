import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { registerAppResource, registerAppTool, RESOURCE_MIME_TYPE } from "@modelcontextprotocol/ext-apps/server";
import { OpenAIExtensions } from "@openai/mcp-extensions/server";
import { z } from "zod";
import { pages } from "./config.mjs";

export const resourceUri = "ui://shilling/client-v1.html";

export function widgetHtml(origin, script) {
  const config = JSON.stringify({ origin }).replaceAll("<", "\\u003c");
  return `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Shilling</title><style>
html,body{margin:0;height:100%;font:13px system-ui;color:var(--color-text-primary,#222);background:var(--color-background-primary,#fff)}
body{display:flex;flex-direction:column;height:100dvh;min-height:400px}
header{display:flex;align-items:center;gap:12px;padding:10px 14px;border-bottom:1px solid #8884;flex-wrap:wrap}
#status{flex:1;min-width:180px}button{font:inherit;color:inherit;background:transparent;border:1px solid #8886;border-radius:7px;padding:6px 10px;cursor:pointer}
iframe{flex:1;width:100%;min-height:0;border:0}
</style></head><body><header><strong>Shilling</strong><span id="status">Sync with another device to protect your data. Browser storage can be cleared.</span><button id="reload">Reload</button><button id="open">Open in browser</button></header>
<iframe id="client" title="Shilling budget client" allow="clipboard-write" referrerpolicy="no-referrer"></iframe>
<script id="config" type="application/json">${config}</script><script>${script.replaceAll("</script", "<\\/script")}</script></body></html>`;
}

export function createServer(config, script) {
  const server = new McpServer({ name: "shilling", version: "0.1.0" }, {
    instructions: "Shilling opens a full interactive budgeting client. These tools only open screens; they cannot read budgets, confirm saved changes, or perform financial actions. Users sign in and edit inside the client. Never claim a tool has read or changed household data.",
  });
  new OpenAIExtensions(server);
  registerAppResource(server, "shilling-client", resourceUri, {}, async () => ({ contents: [{
    uri: resourceUri, mimeType: RESOURCE_MIME_TYPE,
    text: widgetHtml(config.origin, script),
    _meta: {
      ui: { domain: config.origin, csp: { connectDomains: [], resourceDomains: [], frameDomains: [config.origin] } },
      "openai/ui": { preferredDisplayMode: "fullscreen", availableDisplayModes: ["fullscreen"] },
    },
  }] }));
  for (const [name, title, entrypoint, defaultPage] of [
    ["open_shilling", "Shilling", "global", "home"],
    ["open_budget_panel", "Budget workspace", "thread", "plan"],
  ]) {
    registerAppTool(server, name, {
      title,
      description: "Open the full Shilling client at a selected screen. The user can manage their budget there. This tool does not retrieve or modify budget data.",
      inputSchema: { page: z.enum(pages).optional() },
      outputSchema: { page: z.enum(pages), dataAccess: z.literal("client-only") },
      annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false },
      securitySchemes: [{ type: "noauth" }],
      icons: [{ src: `${config.origin}/icon.svg`, mimeType: "image/svg+xml" }],
      _meta: { ui: { resourceUri }, "openai/ui": { entrypoints: [{ type: entrypoint }] } },
    }, async ({ page = defaultPage }) => ({
      structuredContent: { page, dataAccess: "client-only" },
      content: [{ type: "text", text: `Opened Shilling's ${page} screen. Budget data and edits are available only inside the client.` }],
    }));
  }
  return server;
}
