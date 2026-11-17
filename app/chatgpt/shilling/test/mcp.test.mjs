import test from "node:test";
import assert from "node:assert/strict";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { configFromEnv, pageFromPath } from "../src/config.mjs";
import { createServer, resourceUri } from "../src/mcp.mjs";

test("launch tools accept empty entrypoint arguments, advertise surfaces, and expose no entity APIs", async (t) => {
  const server = createServer(configFromEnv({}), "");
  const client = new Client({ name: "test", version: "1" });
  const [a, b] = InMemoryTransport.createLinkedPair();
  await server.connect(a);
  await client.connect(b);
  t.after(async () => { await client.close(); await server.close(); });
  const { tools } = await client.listTools();
  assert.deepEqual(tools.map((tool) => tool.name), ["open_shilling", "open_budget_panel"]);
  assert.equal(tools[0]._meta["openai/ui"].entrypoints[0].type, "global");
  assert.equal(tools[1]._meta["openai/ui"].entrypoints[0].type, "thread");
  for (const tool of tools) {
    assert.equal(tool.annotations.readOnlyHint, true);
    const result = await client.callTool({ name: tool.name, arguments: {} });
    assert.equal(result.isError, undefined);
    assert.equal(result.structuredContent.dataAccess, "client-only");
  }
  const result = await client.callTool({ name: "open_shilling", arguments: { page: "https://attacker.example/" } });
  assert.equal(result.isError, true);
  const resource = await client.readResource({ uri: resourceUri });
  assert.deepEqual(resource.contents[0]._meta.ui.csp.frameDomains, ["http://localhost:8787"]);
  assert.equal(resource.contents[0].mimeType, "text/html;profile=mcp-app");
});

test("public origin and navigation reject arbitrary destinations and embedded credentials", () => {
  for (const url of ["http://example.com", "https://user:pass@example.com", "https://example.com/path", "https://example.com/?token=x"]) {
    assert.throws(() => configFromEnv({ SHILLING_CHATGPT_PUBLIC_URL: url }));
  }
  assert.equal(configFromEnv({ SHILLING_CHATGPT_PUBLIC_URL: "https://chatgpt.shilling.finance" }).origin, "https://chatgpt.shilling.finance");
  assert.equal(pageFromPath("/plan"), "plan");
  for (const path of ["//evil.example", "/plan?token=x", "/account/private-id", null]) assert.equal(pageFromPath(path), "home");
});
