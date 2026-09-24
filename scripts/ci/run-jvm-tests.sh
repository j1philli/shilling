#!/usr/bin/env bash
set -euo pipefail

echo "=== Shilling JVM tests ==="
echo "Running Amper tests for modules: shared, server; platform: jvm"

report_roots=(
    "build/reports/shared/jvm"
    "build/reports/server/jvm"
)

clear_reports() {
    rm -rf "${report_roots[@]}"
}

reports_are_successful() {
    for root in "${report_roots[@]}"; do
        if [ ! -d "$root" ]; then
            echo "Missing test report directory: $root" >&2
            return 1
        fi
    done

    scripts/ci/accept-successful-junit-reports.sh
}

max_attempts="${CI_RETRY_ATTEMPTS:-3}"
delay_seconds="${CI_RETRY_DELAY_SECONDS:-45}"
attempt=1

while true; do
    clear_reports
    echo "Attempt $attempt/$max_attempts: ./kotlin test -m shared -m server -p jvm --format=teamcity"

    set +e
    ./kotlin test -m shared -m server -p jvm --format=teamcity
    amper_status=$?
    set -e

    if reports_are_successful; then
        if [ "$amper_status" -ne 0 ]; then
            echo "Amper exited with $amper_status after successful JUnit reports; treating as Amper post-test internal failure."
        fi
        exit 0
    fi

    if [ "$attempt" -ge "$max_attempts" ]; then
        echo "JVM tests failed after $max_attempts attempts." >&2
        exit 1
    fi

    echo "JVM test attempt failed; retrying in ${delay_seconds}s..."
    sleep "$delay_seconds"
    attempt=$((attempt + 1))
done
