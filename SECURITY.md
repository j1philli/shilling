# Security policy

Shilling is under active development. Security fixes target the default branch; no older release branches are supported yet.

Please report a suspected vulnerability through GitHub's **Report a vulnerability** button on the repository's Security page. Keep exploit details out of public issues and pull requests. If private vulnerability reporting is not available, open an issue asking the maintainers for a private contact method without including the vulnerability details.

For bugs that are not security sensitive, use a regular GitHub issue.

## Dependency review — October 21, 2026

The patched artifacts pass the checks below. **The live environment is not yet
cleared:** the agent JVM update remains open. The patched production server
is deployed, and the Cloudflare tunnel has recovered. Machine-readable evidence is in
[`docs/security-verification-2026-10-21.json`](docs/security-verification-2026-10-21.json).

### Changes

- Require Node 22.23.4+, 24.22.1+, or 26.12.1+ within those release lines,
  matching the [October 21 security releases](https://nodejs.org/en/blog/vulnerability/october-2026-security-releases).
  Installation, web builds, CI health checks, and build containers enforce this.
- Pin checksum-verified Oracle Java 25.0.5.0.2 archives, released October 21,
  while Temurin/Corretto/Zulu images lag the
  [October security update](https://www.oracle.com/java/technologies/javase/25-0-5-relnotes.html).
  Server images use a current Wolfi base. `just setup-java` installs Java beside
  system tools; CI checks reject unpatched Java 21/25 versions.
- Select Jackson core/databind 2.22.3 instead of vulnerable JWT/JWKS transitives
  at 2.22.0, removing ten advisory matches from the server dependency graph.
- Backport the upstream GLib iterator safety fix for
  [RUSTSEC-2024-0429](https://rustsec.org/advisories/RUSTSEC-2024-0429.html).
  Tauri currently requires the older GLib API. See
  [provenance and the exact change](THIRD_PARTY_NOTICES.md#glib-rust-bindings-security-backport).
  The Linux Cargo graph and desktop build contexts resolve the local fixed source.
- Rebuild Caddy 2.11.7 using Go 1.27.3 and `golang.org/x/net` 0.60.0.
  The prior official image contained affected Go/HTTP and Alpine package versions.
  The replacement runs without root on a minimal static base.

The Java images include Oracle's bundled license files. This changes the Java
vendor from Temurin; retain those files when repackaging.

### Verification

Both ARM64 and AMD64 server images have zero Trivy OS/Java advisory matches.
The final Caddy image has zero OS matches and one module-level warning:
[GO-2026-5932](https://pkg.go.dev/vuln/GO-2026-5932), covering deprecated OpenPGP.
Caddy does not import that package: the dependency import graph excludes it, and
source-level `govulncheck` reports zero affected vulnerabilities and zero
vulnerable imported packages. A binary/module-only scan still reports the module.
No blanket advisory suppression was added.

The repository scan, including npm development dependencies, found no matches in
npm, Swift, or declared Maven dependencies beyond the version-only GLib finding.
The vendored GLib differs from the checksum-verified upstream crate only in the
two-line safety fix. Earlier OSV queries of resolved server (133 coordinates)
and client (475 coordinates) graphs, Python requirements, and pinned Swift
packages found no other matches after Jackson was patched.

Passed: 37 server tests on patched Java; 16 web/runtime-version tests; 71 CI Python
tests; 11 GLib iterator tests with release optimizations (also passed on Linux CI); all four packaged
browser tests (onboarding, welcome/sign-in, hosted authentication, and WebRTC
exchange); macOS release compilation; shell syntax and architecture checks.

Kotlin's generated npm cache also contains `ws 8.20.1`. It is not copied into
`web-app-dist`; the generated browser import module imports Skiko, js-joda and
Wasm builtins, and uses the browser's native WebSocket. The generated Node-only
package is not part of the shipped browser/desktop bundle. RustSec maintenance
warnings for `paste`, `proc-macro-error`, `zbus_names`, `zvariant`, and
`zvariant_derive` remain upstream migration work, separate from the fixed GLib
safety defect.

### Live checks and remaining work

- A multi-architecture security candidate is published at
  `ghcr.io/j1philli/shilling-server:security-20261021`; the exact digest and patch
  provenance are in the JSON evidence. Registry verification is recorded separately
  from local image scans. TeamCity personal build 3619 finished successfully,
  and both architectures of its immutable registry digest were scanned independently.
- The production origin returns healthy responses in Supabase auth mode, and its
  current image is the verified Java 25.0.5.0.2 candidate. The existing release
  helper deployed it and passed public health/Supabase checks on October 22.
  Missing and invalid tokens are rejected by live signaling with close code 1008.
  The old image reference is retained for rollback.
- The `unraid` tunnel has four active connections as of October 22. The public
  API returns HTTP 200 for `/health` and `/api/config`; error 1033 has cleared.
- TeamCity agent 1 now has checksum-verified Node 22.23.4 and Java 25.0.5.0.2
  installed beside system tools. The new health scripts prefer these. Its
  already-running agent JVM still reports Corretto 21.0.10 and needs a safe
  restart with the updated Java selected.
- Local Node defaults now select 22.23.4, and this project's direnv configuration
  prefers the installed patched Java. Existing unrelated processes were not stopped.

These checks do not certify installed mobile apps, end-user operating systems,
or every embedded native library. Windows/Linux installers and mobile releases
have not been rebuilt in this review. The local TeamCity DSL generator also has
an existing Maven/JDOM incompatibility. Personal build 3619 passed on the live
Linux agent. The source branch is now pushed; import and validation of the
Git-managed branch settings remain separate from that personal build.

The source changes are committed unsigned at the user's explicit request after
the configured 1Password signing request expired. The Git signing configuration
remains enabled. CI personal builds tested and published the explicit source patch.
