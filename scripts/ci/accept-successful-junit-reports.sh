#!/usr/bin/env bash
set -euo pipefail

report_count=0
test_count=0

while IFS= read -r -d '' report; do
    report_count=$((report_count + 1))

    if grep -Eq 'failures="[1-9][0-9]*"|errors="[1-9][0-9]*"' "$report"; then
        echo "Test failures/errors found in $report" >&2
        exit 1
    fi

    tests=$(sed -n 's/.* tests="\([0-9][0-9]*\)".*/\1/p' "$report" | head -1)
    if [ -n "$tests" ]; then
        test_count=$((test_count + tests))
    fi
done < <(find build/reports -path '*/jvm/TEST-*.xml' -type f -print0 2>/dev/null)

if [ "$report_count" -eq 0 ]; then
    echo "No JUnit XML reports were produced." >&2
    exit 1
fi

if [ "$test_count" -eq 0 ]; then
    echo "JUnit reports did not contain any executed tests." >&2
    exit 1
fi

echo "JUnit reports are successful: $test_count tests across $report_count report files."
