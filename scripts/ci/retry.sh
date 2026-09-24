#!/usr/bin/env bash
# Runs a command with retries for transient network failures during dependency resolution.
set -euo pipefail

max_attempts="${CI_RETRY_ATTEMPTS:-3}"
delay_seconds="${CI_RETRY_DELAY_SECONDS:-45}"
attempt=1

while true; do
    echo "Attempt $attempt/$max_attempts: $*"
    if "$@"; then
        exit 0
    fi

    if [ "$attempt" -ge "$max_attempts" ]; then
        echo "Command failed after $max_attempts attempts: $*" >&2
        exit 1
    fi

    echo "Command failed; retrying in ${delay_seconds}s..."
    sleep "$delay_seconds"
    attempt=$((attempt + 1))
done
