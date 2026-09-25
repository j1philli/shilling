FROM rust:1.90-bookworm AS builder

ENV DEBIAN_FRONTEND=noninteractive
ENV CARGO_BUILD_JOBS=8
ENV XWIN_CACHE_DIR=/usr/local/cargo/xwin-cache

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential ca-certificates clang curl file libssl-dev lld llvm \
    nodejs npm nsis pkg-config wget xz-utils \
    && rm -rf /var/lib/apt/lists/* \
    && ln -s "$(find /usr/bin -maxdepth 1 -name 'llvm-rc-*' | sort -V | tail -1)" /usr/local/bin/llvm-rc

RUN npm install --global @tauri-apps/cli@2.10.0 \
    && rustup target add x86_64-pc-windows-msvc \
    && cargo install --locked cargo-xwin

WORKDIR /workspace
COPY src-tauri/ src-tauri/
COPY web-app-dist/ web-app-dist/
COPY app_logo.png app_logo.png

RUN tauri icon app_logo.png --output src-tauri/icons

WORKDIR /workspace/src-tauri
RUN --mount=type=cache,target=/usr/local/cargo/registry \
    --mount=type=cache,target=/usr/local/cargo/git \
    --mount=type=cache,target=/usr/local/cargo/xwin-cache \
    --mount=type=cache,target=/workspace/src-tauri/target \
    tauri build --runner cargo-xwin --target x86_64-pc-windows-msvc \
      --bundles nsis --config '{"build":{"beforeBuildCommand":""}}' && \
    mkdir -p /out && \
    cp target/x86_64-pc-windows-msvc/release/bundle/nsis/*.exe /out/

FROM scratch
COPY --from=builder /out/ /
