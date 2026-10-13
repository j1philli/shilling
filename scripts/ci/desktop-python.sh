#!/usr/bin/env bash
# Reuse the pinned cryptography dependency for updater signature verification.
set -euo pipefail
cd "$(dirname "$0")/../.."
requirements="$PWD/scripts/ci/apple-requirements.txt"
key=$(shasum -a 256 "$requirements" | cut -c1-16)
venv="${SHILLING_DESKTOP_VENV_ROOT:-$HOME/.cache/shilling/desktop-api}/$key"
if [ ! -f "$venv/.ready" ]; then
    python3 -m venv "$venv"
    "$venv/bin/python" -m pip install --disable-pip-version-check -r "$requirements"
    touch "$venv/.ready"
fi
exec "$venv/bin/python" "$@"
