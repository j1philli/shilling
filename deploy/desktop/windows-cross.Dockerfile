# syntax=docker/dockerfile:1
FROM rust:1.90-bookworm AS toolchain

ENV DEBIAN_FRONTEND=noninteractive
ENV CARGO_BUILD_JOBS=8
ENV XWIN_CACHE_DIR=/usr/local/cargo/xwin-cache

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential ca-certificates clang curl file libssl-dev lld llvm \
    nodejs npm nsis pkg-config wget xz-utils python3 default-jre-headless osslsigncode \
    && rm -rf /var/lib/apt/lists/* \
    && ln -s "$(find /usr/bin -maxdepth 1 -name 'llvm-rc-*' | sort -V | tail -1)" /usr/local/bin/llvm-rc

RUN npm install --global @tauri-apps/cli@2.10.0 \
    && rustup target add x86_64-pc-windows-msvc \
    && cargo install --locked cargo-xwin --version 0.23.1

# Pin the signer and Microsoft's public code-signing trust anchor. These are
# public files, not our signing private key (which never leaves Azure).
RUN apt-get update && apt-get install -y --no-install-recommends p7zip-full \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /opt/jsign \
    && curl -fLsS --retry 3 https://github.com/ebourg/jsign/releases/download/7.6/jsign-7.6.jar -o /opt/jsign/jsign.jar \
    && echo '6cf58aa080e7ff82656f320cff8d4a3bff30408df8766806dcba3740dd839fa5  /opt/jsign/jsign.jar' | sha256sum -c - \
    && curl -fLsS --retry 3 'https://www.microsoft.com/pkiops/certs/Microsoft%20Identity%20Verification%20Root%20Certificate%20Authority%202020.crt' -o /opt/jsign/root.der \
    && echo '5367f20c7ade0e2bca790915056d086b720c33c1fa2a2661acf787e3292e1270  /opt/jsign/root.der' | sha256sum -c - \
    && openssl x509 -inform DER -in /opt/jsign/root.der -out /opt/jsign/root.pem \
    && cat /etc/ssl/certs/ca-certificates.crt /opt/jsign/root.pem > /opt/jsign/trust.pem

COPY windows_signing.py /opt/shilling/windows_signing.py

FROM toolchain AS package
ARG SHILLING_WINDOWS_SIGNED=0
WORKDIR /workspace
COPY src-tauri/ src-tauri/
COPY web-app-dist/ web-app-dist/

WORKDIR /workspace/src-tauri
RUN --mount=type=cache,target=/usr/local/cargo/registry \
    --mount=type=cache,target=/usr/local/cargo/git \
    --mount=type=cache,target=/usr/local/cargo/xwin-cache \
    --mount=type=cache,target=/workspace/src-tauri/target,sharing=locked \
    --mount=type=secret,id=windows_signing \
    rm -rf target/x86_64-pc-windows-msvc/release/bundle && \
    rm -f target/x86_64-pc-windows-msvc/release/shilling.exe \
      target/x86_64-pc-windows-msvc/release/deps/shilling-* && \
    case "$SHILLING_WINDOWS_SIGNED" in \
      0) python3 /opt/shilling/windows_signing.py bundle ;; \
      1) python3 /opt/shilling/windows_signing.py bundle --signed ;; \
      *) echo 'SHILLING_WINDOWS_SIGNED must be 0 or 1' >&2; exit 1 ;; \
    esac && \
    mkdir -p /out && \
    cp target/x86_64-pc-windows-msvc/release/bundle/nsis/*.exe /out/

FROM scratch
COPY --from=package /out/ /
