# Shilling

Shilling is a household budgeting app built with Kotlin Multiplatform. Plan recurring income and expenses, track what cleared, and see what is coming up in a Friday-to-Friday weekly view. It has desktop (Tauri), web, iOS, and Android clients.

The app stores budget data on each device. Optional device sync uses WebRTC peer-to-peer data channels. The server helps peers connect and, in hosted mode, provides authentication and household metadata. It does not store or relay budget entries or receipts. Application data passes through the Store5 repository layer.

## Current status

This repository is under active development. The hosted web app is at
[app.shilling.finance](https://app.shilling.finance). For your own installation,
use the [self-hosted Docker Compose guide](docs/self-hosting.mdx). See the
[GA launch plan](plans/20260311-161413-ga-launch-plan.md) for known product work
before a stable release.

## Try it locally

[mise](https://mise.jdx.dev/) installs everything the build needs at pinned, checksum-verified versions (Node.js, Java, Rust, the Tauri CLI) and runs the project tasks. It doesn't change your system-wide tools.

### 1. Install mise

```sh
curl https://mise.run | sh     # or: brew install mise
```

Optionally, [activate it in your shell](https://mise.jdx.dev/getting-started.html#activate-mise) (for example `eval "$(mise activate zsh)"` in `~/.zshrc`) so `node`, `java` and `cargo` resolve to the project versions whenever you're in the repository. `mise run` works without it.

### 2. Clone and run

```sh
git clone https://github.com/j1philli/shilling.git
cd shilling
mise trust        # allow this repository's mise.toml
mise install      # pinned tools from mise.lock
mise run desktop  # build the web bundle and launch the Tauri desktop app
```

Tasks prepare what they need: npm packages are reinstalled whenever `package-lock.json` changes, and iOS tasks download `WebRTC.xcframework`. The first build also downloads the Kotlin Toolchain and its dependencies, so it takes several minutes. Run `mise tasks` to list every task.

| Command | What it does |
| --- | --- |
| `mise run web` | Serve the web build at `http://localhost:3000` and rebuild on source changes |
| `mise run ios` | Build and launch the native iOS app in the Simulator |
| `mise run android` | Build and install on an emulator; set `ANDROID_AVD` to your emulator name (default `Pixel_9_Pro_XL_API_35`) and `ANDROID_HOME` if the SDK isn't at `~/Library/Android/sdk` |
| `mise run server` | Run the signaling server on port 8081 |
| `mise run sync-proxy` | Serve the signaling server over HTTPS on port 8443 |
| `mise run build-all` | Compile every module for every platform |

Development builds use a separate `shilling-dev` database and dev icons, so they don't touch data from a production install.

### Platform SDKs

mise can't install Apple's or Google's SDKs. You only need them for the mobile apps:

- **iOS:** macOS with Xcode 15 or later and an iOS Simulator runtime.
- **Android:** Android Studio with the Android SDK 37 platform and an emulator.

`mise doctor project` checks for both and says what to install.

macOS is the tested development platform. The web, desktop, server and Android tasks are expected to work on Linux once [Tauri's Linux prerequisites](https://v2.tauri.app/start/prerequisites/#linux) are installed, but this isn't regularly tested.

### Sync between devices

Clients only connect to signaling over HTTPS/WSS, including debug builds and localhost. Run the server and an HTTPS proxy in two terminals:

```sh
mise run server      # self-hosted mode on port 8081, no Supabase credentials needed
mise run sync-proxy  # https://<this machine>:8443 -> the server
```

`sync-proxy` creates a certificate with [mkcert](https://github.com/FiloSottile/mkcert), trusts its local CA on this Mac (it asks for your password the first time) and in a booted iOS Simulator, and prints the URLs to use. In the app, choose **Self-hosted** and enter one of them on each device. Real phones and the Android emulator must also trust the printed `rootCA.pem`; see [HTTPS is required for signaling](docs/self-hosting.mdx#https-is-required-for-signaling). `mise run homelab` starts the server, the proxy, the desktop app and the iOS Simulator together.

The self-hosted server does not authenticate household joins; operate it only in an environment where you control access. For optional TURN configuration and hosted Supabase setup, see the [server overview](docs/server/overview.mdx) and [hosted auth guide](docs/hosted-auth.mdx).

## Development

```sh
mise run test                # architecture guard and JVM tests
mise run guard-architecture  # WebRTC, signaling, and Store5 invariant checks
```

The main modules are `app/shared` (data and sync), `app/shared-ui` (Compose UI), the platform apps under `app/`, `core` (shared protocol models), and `server` (signaling and hosted metadata). More details are in the [getting started guide](docs/getting-started.mdx) and [server docs](docs/server/overview.mdx).

Tool versions live in `mise.toml`. To update one, change it there and run `mise lock` to refresh the checksums in `mise.lock`.

Release numbers and the current target publishing status are in the
[release guide](docs/releases.mdx). Run `mise run check-version` before tagging.

## Contributing

Issues and pull requests are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) before changing sync, server, or data flow code.

For security issues, follow [SECURITY.md](SECURITY.md).

## License

Shilling is licensed under the [GNU Affero General Public License v3.0 only](LICENSE) (`AGPL-3.0-only`). If you distribute a modified version or offer a modified version as a network service, the license has source-sharing requirements. Contributions upstream are welcome, but the license does not require a fork to submit a pull request here.

Bundled and inlined third-party components retain their own licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Optional product analytics is described in the [usage analytics privacy notice](docs/privacy.mdx) and [analytics setup guide](docs/product-analytics.mdx).
