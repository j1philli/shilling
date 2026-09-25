FROM rust:1.90-bookworm AS builder

ENV DEBIAN_FRONTEND=noninteractive
ENV CARGO_BUILD_JOBS=8
ENV APPIMAGE_EXTRACT_AND_RUN=1

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential ca-certificates curl file libayatana-appindicator3-dev \
    libdbus-1-dev libfuse2 libssl-dev libwebkit2gtk-4.1-dev libxdo-dev \
    librsvg2-dev nodejs npm patchelf pkg-config rpm wget xz-utils \
    && rm -rf /var/lib/apt/lists/*

RUN npm install --global @tauri-apps/cli@2.10.0

WORKDIR /workspace
COPY src-tauri/ src-tauri/
COPY web-app-dist/ web-app-dist/
COPY app_logo.png app_logo.png

RUN tauri icon app_logo.png --output src-tauri/icons

WORKDIR /workspace/src-tauri
RUN --mount=type=cache,target=/usr/local/cargo/registry \
    --mount=type=cache,target=/usr/local/cargo/git \
    --mount=type=cache,target=/workspace/src-tauri/target \
    tauri build --bundles deb,rpm,appimage \
      --config '{"build":{"beforeBuildCommand":""}}' && \
    mkdir -p /out && \
    cp target/release/bundle/deb/*.deb /out/ && \
    cp target/release/bundle/rpm/*.rpm /out/ && \
    cp target/release/bundle/appimage/*.AppImage /out/

FROM scratch
COPY --from=builder /out/ /
