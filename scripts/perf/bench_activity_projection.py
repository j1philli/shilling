#!/usr/bin/env python3
"""Run the compiled production projection and old reference on synthetic JVM data.

Run ./scripts/ci/run-jvm-tests.sh first. The Kotlin toolchain records the resolved
test classpath in its telemetry; reuse it rather than guessing dependency jars.
"""
import json
import pathlib
import subprocess
import sys

root = pathlib.Path(__file__).resolve().parents[2]
traces = sorted((root / "build/logs").glob("*/telemetry/kotlin_cli_traces.jsonl"),
                key=lambda path: path.stat().st_mtime, reverse=True)
classpath = None
java = None
for trace in traces:
    for line in trace.read_text().splitlines():
        for resource in json.loads(line).get("resourceSpans", []):
            for scope in resource.get("scopeSpans", []):
                for span in scope.get("spans", []):
                    attrs = {a["key"]: a["value"] for a in span.get("attributes", [])}
                    if span["name"] == "java-exec":
                        java = attrs["java-executable"]["stringValue"]
                    if span["name"] != "junit-platform-console-standalone":
                        continue
                    if attrs.get("working-dir", {}).get("stringValue") != str(root / "app/shared"):
                        continue
                    classpath = [v["stringValue"] for v in attrs["tests-classpath"]["arrayValue"]["values"]]
    if classpath and java:
        break
if not classpath or not java:
    sys.exit("No shared JVM test classpath found; run ./scripts/ci/run-jvm-tests.sh first")
subprocess.run([java, "-cp", ":".join(classpath),
                "finance.shilling.shared.presentation.ActivityProjectionBenchmark"], check=True, cwd=root)
