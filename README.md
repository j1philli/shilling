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

Development is done on macOS (Apple silicon or Intel). You need:

- [Homebrew](https://brew.sh/) and the Xcode command-line tools (`xcode-select --install`)
- Xcode 15 or later with an iOS Simulator runtime, for the iOS app only
- Android Studio with the Android SDK 37 platform and an emulator, for the Android app only

### 1. Install the tools

```sh
brew bundle                                      # just, direnv, node@22, openjdk@21, rust, imagemagick
cargo install tauri-cli --version "^2" --locked  # desktop shell
```

If you already manage Rust with [rustup](https://rustup.rs/), its toolchain in `~/.cargo/bin` takes precedence over Homebrew's.

### 2. Load the project environment

`node@22` and `openjdk@21` are keg-only in Homebrew, so they are not on your `PATH` by default. The repository's `.envrc` adds them (and `~/.cargo/bin`) when you enter the directory. [Hook direnv into your shell](https://direnv.net/docs/hook.html) once, for example with `eval "$(direnv hook zsh)"` in `~/.zshrc`, then allow the project:

```sh
cd shilling
direnv allow
```

The build checks for patched runtimes: Node.js 22.23.4+ (or 24.22.1+ / 26.12.1+) and Java 21.0.13+ (or 25.0.5+). Without direnv, put a supported `node` on your `PATH` and set `JAVA_HOME` yourself before running `just`.

### 3. Set up and run

```sh
just setup     # Java runtime, npm dependencies, iOS WebRTC framework
just desktop   # build the web bundle and launch the Tauri desktop app
```

`just setup` downloads a pinned JDK 25 into `~/.local/share/shilling/` (it does not change your system Java; `.envrc` selects it), runs `npm install`, and downloads `WebRTC.xcframework` for iOS. The first build also downloads the Kotlin Toolchain and its dependencies, so it takes several minutes.

Other targets:

| Command | What it does |
| --- | --- |
| `just web` | Serve the web build at `http://localhost:3000` and rebuild on source changes |
| `just ios` | Build and launch the native iOS app in the Simulator |
| `just android` | Build and install on an emulator; set `ANDROID_AVD` to your emulator name (default `Pixel_9_Pro_XL_API_35`). Expects the SDK at `~/Library/Android/sdk` |
| `just server` | Run the signaling server on port 8081 |
| `just build-all` | Compile every module for every platform |

Development builds use a separate `shilling-dev` database and dev icons, so they don't touch data from a production install.

### Sync between devices

Start the signaling server in a separate terminal:

```sh
just server
```

It defaults to self-hosted mode on port 8081 and needs no Supabase credentials. Clients only connect to signaling over HTTPS/WSS, including debug builds and localhost, so put a TLS-terminating reverse proxy (for example on port 8443) in front of port 8081 with a certificate every device trusts. See [HTTPS is required for signaling](docs/self-hosting.mdx#https-is-required-for-signaling). In the app, choose **Self-hosted** and enter that `https://` URL on each device.

The self-hosted server does not authenticate household joins; operate it only in an environment where you control access. For optional TURN configuration and hosted Supabase setup, see the [server overview](docs/server/overview.mdx) and [hosted auth guide](docs/hosted-auth.mdx).

## Development

```sh
just test                # architecture guard and JVM tests
just guard-architecture  # WebRTC, signaling, and Store5 invariant checks
```

The main modules are `app/shared` (data and sync), `app/shared-ui` (Compose UI), the platform apps under `app/`, `core` (shared protocol models), and `server` (signaling and hosted metadata). More details are in the [getting started guide](docs/getting-started.mdx) and [server docs](docs/server/overview.mdx).

Release numbers and the current target publishing status are in the
[release guide](docs/releases.mdx). Run `just check-version` before tagging.

## Contributing

Issues and pull requests are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) before changing sync, server, or data flow code.

For security issues, follow [SECURITY.md](SECURITY.md).

## License

Shilling is licensed under the [GNU Affero General Public License v3.0 only](LICENSE) (`AGPL-3.0-only`). If you distribute a modified version or offer a modified version as a network service, the license has source-sharing requirements. Contributions upstream are welcome, but the license does not require a fork to submit a pull request here.

Bundled and inlined third-party components retain their own licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Optional product analytics is described in the [usage analytics privacy notice](docs/privacy.mdx) and [analytics setup guide](docs/product-analytics.mdx).
