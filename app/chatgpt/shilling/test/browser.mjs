// Local MCP Apps host simulation. Does not claim ChatGPT production compatibility.
import assert from "node:assert/strict";
import express from "express";
import { fileURLToPath } from "node:url";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import { chromium, expect } from "@playwright/test";
import { build } from "esbuild";
import { once } from "node:events";
import { createHttpApp } from "../src/server.mjs";
import { widgetHtml } from "../src/mcp.mjs";

const config = { origin: "http://127.0.0.1", bind: "127.0.0.1" };
const { app, script } = await createHttpApp(config);
app.use("/standalone", express.static(fileURLToPath(new URL("../../../../web-app-dist/", import.meta.url))));
const http = app.listen(0, "127.0.0.1");
await once(http, "listening");
config.origin = `http://127.0.0.1:${http.address().port}`;
const host = await build({ stdin: { contents: `
import { AppBridge, PostMessageTransport } from '@modelcontextprotocol/ext-apps/app-bridge';
const frame = document.getElementById('widget');
const bridge = new AppBridge(null, {name:'Test host',version:'1'}, {openLinks:{}});
window.bridge = bridge;
bridge.oninitialized = () => bridge.sendToolResult({content:[],structuredContent:{page:'plan',dataAccess:'client-only'}});
await bridge.connect(new PostMessageTransport(frame.contentWindow, frame.contentWindow));
frame.srcdoc = await (await fetch('/test-widget')).text();
`, resolveDir: process.cwd() }, bundle: true, format: "esm", write: false });
app.get("/test-host.js", (_req, res) => res.type("js").send(host.outputFiles[0].text));
app.get("/test-widget", (_req, res) => res.type("html").send(widgetHtml(config.origin, script)));
app.get("/test-host", (_req, res) => res.type("html").send(`<html><body style="margin:0"><iframe id="widget" style="width:100vw;height:100vh;border:0" sandbox="allow-scripts allow-same-origin allow-forms allow-popups allow-downloads"></iframe><script type="module" src="/test-host.js"></script></body></html>`));
const protocol = new Client({name:'http-smoke',version:'1'});
await protocol.connect(new StreamableHTTPClientTransport(new URL(`${config.origin}/mcp`)));
assert.equal((await protocol.listTools()).tools.length, 2);
assert.equal((await protocol.callTool({name:'open_shilling',arguments:{}})).structuredContent.page, 'home');
await protocol.close();
assert.equal((await fetch(`${config.origin}/mcp`, {method:'POST',headers:{origin:'https://untrusted.example'}})).status,403);
const browser = await chromium.launch({ args: ["--use-angle=swiftshader", "--enable-unsafe-swiftshader", "--disable-features=WebRtcHideLocalIpsWithMdns", "--allow-loopback-in-peer-connection"] });
try {
  const context = await browser.newContext({ viewport: { width: 1200, height: 850 } });
  // Chromium exposes loopback candidates only with interface enumeration enabled.
  // Granting permission here records no media and is confined to disposable tests.
  await context.grantPermissions(['microphone']);
  context.setDefaultTimeout(15000);
  const household = `chatgpt-browser-${crypto.randomUUID()}`;
  const seed = (household) => {
    if (!/^\/(client|standalone)\//.test(location.pathname)) return;
    if (!localStorage.getItem('shilling_onboarding_completed')) {
      localStorage.setItem('shilling_onboarding_completed', 'true');
      localStorage.setItem('shilling_deployment_selection', 'SELF_HOSTED');
      localStorage.setItem('shilling_server_url', 'http://localhost:8081');
      localStorage.setItem('shilling_local_household_id', household);
    }
  };
  await context.addInitScript(seed, household);
  const page = await context.newPage();
  const errors = [];
  const trace = (label, page) => page.on('console', m => {
    if (process.env.SHILLING_TEST_TRACE === '1') console.log(label, m.text());
  });
  trace('embedded', page);
  page.on("pageerror", (error) => { errors.push(error.message); console.error(error.stack); });
  page.on("console", (message) => { if (message.type() === "error") console.error(message.text()); });
  const standalone = process.env.SHILLING_TEST_STANDALONE === '1';
  await page.goto(`${config.origin}/${standalone ? 'standalone/#/plan' : 'test-host'}`);
  const client = standalone ? page : page.frameLocator("#widget").frameLocator("#client");
  if (standalone) {
    await client.locator('canvas').waitFor({timeout:60000});
    await page.waitForTimeout(3000);
    await client.locator('body').evaluate(() => {location.hash='/plan';});
  }
  await client.locator("canvas").waitFor({ timeout: 60000 });
  await new Promise((resolve) => setTimeout(resolve, 3000));
  assert.equal(await client.locator("#error-display").isVisible(), false);
  await page.screenshot({ path: "/tmp/shilling-chatgpt-client.png" });
  console.log(`${standalone ? "Standalone" : "Embedded"} shared Wasm client rendered; screenshot /tmp/shilling-chatgpt-client.png`);
  await expect(client.getByRole('button', { name: 'Accounts', exact: true })).toBeVisible();
  await client.locator('body').evaluate(() => { location.hash='/account/new'; });
  await client.getByRole('textbox').nth(0).fill('ChatGPT test account');
  await page.waitForTimeout(200);
  await client.getByRole('textbox').nth(1).fill('123.45');
  await page.waitForTimeout(300);
  // Compose's accessibility elements sit behind its canvas. Invoke their
  // semantic click action so canvas animation does not redirect test pointer hits.
  await client.getByRole('button', { name: 'Save', exact: true }).dispatchEvent('click');
  await expect(client.getByText('New account', {exact:true})).not.toBeVisible();
  await client.getByRole('button', { name: 'Accounts', exact: true }).dispatchEvent('click');
  await expect(client.getByRole('button', {name:/ChatGPT test account \$123\.45/})).toBeVisible();
  // The shared SQL worker persists writes on a short debounce.
  await page.waitForTimeout(500);
  await page.reload();
  if (!standalone) await client.locator('html[data-shilling-ready=true]').waitFor({timeout:60000});
  else {
    await client.locator('canvas').waitFor({timeout:60000});
    await page.waitForTimeout(3000);
    await client.locator('body').evaluate(() => {location.hash='/plan';});
  }
  await page.waitForTimeout(1000);
  await client.getByRole('button', { name: 'Accounts', exact: true }).dispatchEvent('click');
  await page.waitForTimeout(500);
  await expect(client.getByRole('button', {name:/ChatGPT test account \$123\.45/})).toBeVisible();
  if (process.argv.includes("--peer-sync") || process.env.SHILLING_TEST_PEER_SYNC === "1") {
    // A separate storage partition is a distinct device. Only the real signaling
    // server and existing WebRTC transport may move the new account between them.
    const peerContext = await browser.newContext({viewport:{width:1200,height:850}});
    await peerContext.grantPermissions(['microphone']);
    peerContext.setDefaultTimeout(15000);
    await peerContext.addInitScript(seed, household);
    const peerPage = await peerContext.newPage();
    peerPage.on("pageerror", error => errors.push(error.message));
    trace('peer', peerPage);
    await peerPage.goto(`${config.origin}/client/#/plan`);
    await peerPage.locator('html[data-shilling-ready=true]').waitFor({timeout:60000});
    await peerPage.locator('body').evaluate(() => { location.hash='/plan'; });
    await peerPage.waitForTimeout(1000);
    await peerPage.getByRole('button', {name:'Accounts',exact:true}).dispatchEvent('click');
    await expect(peerPage.getByRole('button',{name:/ChatGPT test account/})).toBeVisible({timeout:30000});
    await peerPage.locator('body').evaluate(() => {location.hash='/account/new';});
    await peerPage.getByRole('textbox').nth(0).fill('Peer-created account');
    await peerPage.waitForTimeout(300);
    await peerPage.getByRole('button',{name:'Save',exact:true}).dispatchEvent('click');
    await expect(client.getByRole('button',{name:/Peer-created account/})).toBeVisible({timeout:30000});
    await peerPage.reload();
    await peerPage.locator('html[data-shilling-ready=true]').waitFor({timeout:60000});
    await peerPage.locator('body').evaluate(() => {location.hash='/plan';});
    await peerPage.waitForTimeout(1000);
    await peerPage.getByRole('button',{name:'Accounts',exact:true}).dispatchEvent('click');
    await expect(peerPage.getByRole('button',{name:/Peer-created account/})).toBeVisible();
    await expect(peerPage.getByRole('button',{name:/ChatGPT test account/})).toBeVisible();
    // A persisted row alone does not prove the replacement connection works.
    await client.locator('body').evaluate(() => {location.hash='/account/new';});
    await client.getByRole('textbox').nth(0).fill('After reconnect');
    await page.waitForTimeout(300);
    await client.getByRole('button',{name:'Save',exact:true}).dispatchEvent('click');
    await expect(peerPage.getByRole('button',{name:/After reconnect/})).toBeVisible({timeout:30000});
    await peerContext.close();
    console.log('Account synchronized to a separate browser device over WebRTC.');
  }
  await page.setViewportSize({width:390,height:844});
  await page.waitForTimeout(500);
  await expect(client.locator('canvas')).toBeVisible();
  assert.equal(await client.locator('#error-display').isVisible(),false);
  await page.screenshot({path:'/tmp/shilling-chatgpt-mobile.png'});
  if (!standalone) {
    const second = await context.newPage();
    await second.goto(`${config.origin}/client/`);
    await expect(second.locator('#error-display')).toContainText('already open');
    await page.close();
    await second.reload();
    await second.locator('html[data-shilling-ready=true]').waitFor({timeout:60000});
    assert.equal(await second.locator('#error-display').isVisible(),false);
    await second.close();
  }
  const unsupported = await browser.newContext();
  await unsupported.addInitScript(() => { window.RTCPeerConnection = undefined; });
  const unsupportedPage = await unsupported.newPage();
  await unsupportedPage.goto(`${config.origin}/client/`);
  await expect(unsupportedPage.locator('#error-display')).toContainText('does not support WebRTC');
  await unsupported.close();
  console.log(`${standalone ? 'Standalone comparison' : 'Embedded client'} browser checks passed.`);
  assert.deepEqual(errors, []);
} finally {
  await browser.close();
  await new Promise((resolve) => http.close(resolve));
}
