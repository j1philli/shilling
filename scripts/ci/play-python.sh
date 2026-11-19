#!/usr/bin/env bash
# Play uses the same pinned JWT/cryptography dependencies as Apple.
set -euo pipefail
cd "$(dirname "$0")/../.."
requirements="$PWD/scripts/ci/apple-requirements.txt"
key=$(shasum -a 256 "$requirements" | cut -c1-16)
venv="${SHILLING_PLAY_VENV_ROOT:-$HOME/.cache/shilling/play-api}/$key"
if [ ! -f "$venv/.ready" ]; then
    python3 -m venv "$venv"
    "$venv/bin/python" -m pip install --disable-pip-version-check -r "$requirements"
    touch "$venv/.ready"
fi
exec "$venv/bin/python" "$@"
