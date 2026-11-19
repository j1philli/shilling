# Shilling ChatGPT client

This target opens the complete shared Kotlin/Wasm Shilling client from a ChatGPT Work sidebar or conversation panel. Accounts, schedules, activity, imports and receipts use the existing Compose UI and Store5 repositories. Device synchronization remains WebRTC. The MCP service serves static application assets and navigation only; it has no household database, budget tools, backup access or entity relay.

This is a development integration, not a published or production-certified ChatGPT plugin. Local browser tests simulate an MCP Apps host. Real ChatGPT desktop, Work web and mobile still need host acceptance testing, particularly storage partitioning, WebRTC, authentication and file pickers.

## Build and run

From the repository root, with the normal Shilling toolchain and Node 22 or later:

```sh
mise run chatgpt
```

This compiles the shared web app, installs locked plugin dependencies, builds the host shell and starts `http://localhost:8787/mcp`. Open `http://localhost:8787/client/` to inspect the same client directly. `mise run build-chatgpt` builds without starting the server. The Kotlin build uses Debug; use the normal release web build before `npm --prefix app/chatgpt/shilling run build` for deployment.

If `web-app-dist` already contains a current compiled web client, only the packaging step is necessary:

```sh
npm --prefix app/chatgpt/shilling ci
npm --prefix app/chatgpt/shilling run build
npm --prefix app/chatgpt/shilling start
```

Packaging always changes the copied SQL worker's database name to `shilling-chatgpt`. Build outputs are ignored by Git. The original web source and standalone web/desktop behavior are preserved.

## Connect to ChatGPT

For Work web, host this service on a stable HTTPS origin (for example a dedicated Shilling subdomain). Configure `SHILLING_CHATGPT_PUBLIC_URL` to that origin, without a path. A reverse proxy must forward `/mcp`, `/client/`, `/icon.svg` and `/healthz` to this process. Do not apply `X-Frame-Options: DENY/SAMEORIGIN` or the standalone web server's cross-origin isolation headers to `/client/`.

```sh
SHILLING_CHATGPT_PUBLIC_URL=https://chatgpt.example.com \
SHILLING_CHATGPT_BIND=0.0.0.0 \
npm --prefix app/chatgpt/shilling start
```

In ChatGPT developer mode, connect the HTTPS `/mcp` endpoint with no MCP authentication. These tools are public launchers, not authenticated data access. The embedded Shilling client handles its own normal sign-in or self-hosted onboarding; credentials and tokens must never enter tool arguments or results. A private development tunnel does not establish that ChatGPT can reach the separately embedded client origin.

The local `.codex-plugin/plugin.json` and `.mcp.json` support desktop plugin development. The stdio command starts the same asset service on port 8787; install the complete directory after building and installing dependencies. This does not establish loopback iframe support in every desktop host. Stop an existing `npm start` process before launching the stdio plugin, or configure a separate port and matching public URL. No personal marketplace is modified by repository build commands.

For public distribution, replace the local stdio MCP configuration with the deployed HTTPS connection and follow OpenAI's current submission process. A dedicated origin, verified publisher/domain, published policies, reviewer account, test cases and review are still needed. The included manifest is for local development, not a complete public submission.

An optional runtime container packages prebuilt assets:

```sh
docker build -t shilling-chatgpt app/chatgpt/shilling
docker run --rm -p 8787:8787 \
  -e SHILLING_CHATGPT_PUBLIC_URL=https://chatgpt.example.com \
  shilling-chatgpt
```

Terminate HTTPS at your reverse proxy. No household data volume is attached to this container.

## Host behavior

- `open_shilling({})` opens Home from the sidebar. `open_budget_panel({})` opens Plan as a conversation tab. Both accept an optional `page`: `home`, `plan`, `activity`, `receipts`, `settings`, or `import`.
- Entrypoints use the full-screen MCP Apps resource. Deep links support those same top-level paths; private entity IDs and arbitrary URLs are not accepted.
- A small bridge forwards navigation only. The client sends one data-free readiness notification after its navigation graph mounts. There is no automatic model context export or conversational editing in this version; users edit through the full UI.
- The client checks secure context, Wasm, Web Workers, WebGL2, WebRTC data-channel creation, writable browser storage and Web Locks before starting. Unsupported hosts show an explanation. No alternative sync transport is added.
- A Web Lock allows one active client per origin/storage partition. Opening a second panel shows a message instead of risking independent SQLite instances overwriting each other. Close the first panel and reload the second to switch.
- A mounted client can operate offline with its cached data. The host may destroy it, stop synchronization, partition storage or clear data. A new storage partition is a new device, not guaranteed access to a standalone browser's existing database. Keep another synced device. `Open in browser` may open a different storage partition.
- Receipts, camera capture, popups and downloads depend on host permissions and require acceptance testing. No plugin-specific file API transports are used.

## Verification

### Post-merge status (November 17, 2026)

The merged client builds and the 206-test JVM suite, MCP protocol tests, architecture
guard, and native loopback WebRTC probe pass. Browser acceptance is currently
blocked: the harness below still seeds an HTTP signaling URL rejected by main's
HTTPS-only policy and expects an `Accounts` control that it cannot find in the
current UI. Earlier browser/sync passes described below predate this merge and
must not be used as evidence for the current target. Update the harness for the
current onboarding/navigation and trusted HTTPS signaling, then rerun embedded
and standalone scenarios before claiming local browser coverage. Real ChatGPT
and cross-network acceptance remain outstanding as well.

```sh
mise run test-chatgpt
npm --prefix app/chatgpt/shilling exec -- playwright install chromium
# Start the local signaling backend (browser harness requires the fixes above):
SHILLING_AUTH_MODE=none ./kotlin run --module server
# Expose signaling over trusted HTTPS; see the root README for certificate setup:
mise run sync-proxy
# In another terminal, after building:
npm --prefix app/chatgpt/shilling run test:browser
# Two-device sync and reconnection:
npm --prefix app/chatgpt/shilling run test:sync
# Run the same scenario with the standalone web client as the first device:
SHILLING_TEST_STANDALONE=1 npm --prefix app/chatgpt/shilling run test:sync
# Isolate native WebRTC from application code when diagnosing networking:
npm --prefix app/chatgpt/shilling run test:webrtc
mise run guard-architecture
```

The protocol tests check empty entrypoint arguments, resource declarations, navigation validation and the absence of entity APIs. Browser tests exercise HTTP MCP requests, a sandboxed MCP Apps host, account creation and persistence, concurrent-panel exclusion, and missing WebRTC support. The sync test uses two isolated browser devices and the real signaling server to check account backfill, changes in both directions, persistence after reopening, and a new change after reconnection. `SHILLING_TEST_TRACE=1` prints diagnostic logs containing synthetic test data.

The initial failure exposed a shared WebRTC race: delayed events from an old connection could close its replacement. Connection and channel identity checks now ignore stale callbacks. A separate native WebRTC probe also reproduced the test machine's local networking failure. Headless tests therefore enable loopback candidates, disable mDNS address hiding, and grant microphone permission in disposable test contexts to enable interface enumeration. They never call `getUserMedia` or capture audio. Software WebGL is used for headless rendering. None of these test settings changes the shipped client or its permissions; same-machine tests do not establish cross-network or actual ChatGPT compatibility.

Before release, verify the real host can render the full UI, pair with another device, sync an edit in both directions, reopen persisted data, and recover when storage or a peer is unavailable. Repeat at mobile sizes and with host file permissions denied. Do not advertise platform parity until those checks pass.

## References

- [MCP Extensions specification](https://github.com/openai/mcp-extensions/blob/main/docs/spec.md)
- [MCP Apps UI guide](https://developers.openai.com/plugins/build/chatgpt-ui)
- [Plugin embedding policy](https://developers.openai.com/plugins/plugin-guidelines#iframes-and-embedded-pages)
- [Submission requirements](https://developers.openai.com/plugins/deploy/submission)
