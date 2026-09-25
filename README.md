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

On macOS, install [Homebrew](https://brew.sh/) and the required command-line tools:

```sh
brew bundle
cargo install tauri-cli
just setup
just desktop
```

`just setup` installs npm dependencies, generates app icons, and downloads the iOS WebRTC framework. It requires Xcode command-line tools and a working Java 21 installation. The desktop command also needs the Tauri build dependencies for your operating system. For the browser development build, run `just web` instead. To run the iOS simulator or Android emulator, use `just ios` or `just android` after installing the corresponding SDK and simulator/emulator.

To try sync between devices, start the signaling server in a separate terminal:

```sh
just server
```

It defaults to self-hosted mode on port 8081 and needs no Supabase credentials. In the app, choose **Self-hosted** and enter a server URL reachable from each device. The self-hosted server does not authenticate household joins; operate it only in an environment where you control access. For optional TURN configuration and hosted Supabase setup, see the [server overview](docs/server/overview.mdx) and [hosted auth guide](docs/hosted-auth.mdx).

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
