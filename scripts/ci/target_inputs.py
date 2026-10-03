"""Conservative target input fingerprints, independent of CI build numbers/signing."""
import fnmatch
import hashlib
import json
from pathlib import Path
import subprocess

POLICY = Path(__file__).with_name("target-inputs.json")
TARGETS = ("ios", "android", "web", "linux", "windows", "macos", "server")


def affected_targets(path, policy):
    for rule in policy["rules"]:
        if any(fnmatch.fnmatchcase(path, pattern) for pattern in rule["paths"]):
            targets = {target for name in rule["targets"]
                       for target in policy["groups"].get(name, [name])}
            if not targets <= set(TARGETS):
                raise ValueError(f"Unknown target/group in input policy: {targets - set(TARGETS)}")
            return targets
    # New files/modules/configuration must not silently miss publication.
    return set(TARGETS)


def snapshot(target, repo="."):
    if target not in TARGETS:
        raise ValueError(f"Unknown target: {target}")
    policy = json.loads(POLICY.read_text())
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip()
    tree = subprocess.check_output(["git", "ls-tree", "-rz", "--full-tree", commit], cwd=repo)
    inputs = {}
    for entry in tree.split(b"\0"):
        if not entry:
            continue
        metadata, raw_path = entry.split(b"\t", 1)
        path = raw_path.decode("utf-8")
        if target in affected_targets(path, policy):
            # Include mode/type as well as object ID: deletes, renames, executable
            # bits, symlinks and submodule revisions all affect the fingerprint.
            inputs[path] = metadata.decode("ascii")
    fingerprint = hashlib.sha256(json.dumps(inputs, sort_keys=True).encode()).hexdigest()
    return {"commit": commit, "fingerprint": fingerprint, "inputs": inputs}
