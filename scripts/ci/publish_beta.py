#!/usr/bin/env python3
"""Run a beta publisher only when its target inputs changed since successful delivery."""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

from target_inputs import TARGETS, snapshot

STATE_ARTIFACT = "beta-state.json"


class TeamCity:
    def __init__(self):
        self.url = os.environ["BETA_TEAMCITY_URL"].rstrip("/")
        credentials = os.environ["BETA_TEAMCITY_USER"] + ":" + os.environ["BETA_TEAMCITY_PASSWORD"]
        self.authorization = "Basic " + base64.b64encode(credentials.encode()).decode()

    def get(self, path):
        request = urllib.request.Request(self.url + "/httpAuth/app/rest/" + path,
                                        headers={"Accept": "application/json", "Authorization": self.authorization})
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code == 404 and "/artifacts/content/" in path:
                return None  # Legacy run or cleaned-up artifact: publish to establish a baseline.
            # Never confuse an auth/server error with unchanged inputs.
            raise RuntimeError(f"TeamCity baseline lookup failed: HTTP {error.code}") from None

    def previous_state(self, build_type):
        if not re.fullmatch(r"[A-Za-z0-9_-]+", build_type):
            raise ValueError("Invalid TeamCity build configuration ID")
        query = urllib.parse.urlencode({
            "locator": f"buildType:{build_type},state:finished,status:SUCCESS,branch:(default:true),personal:false,count:1",
            "fields": "count,build(id,branchName)",
        })
        rows = self.get("builds?" + query).get("build", [])
        if not rows:
            return None
        # TeamCity can omit the logical name on default-branch custom runs.
        # The locator already restricts these to the default branch.
        if rows[0].get("branchName", "main") != "main":
            raise ValueError("Beta baseline must belong to main")
        return self.get(f"builds/id:{int(rows[0]['id'])}/artifacts/content/{STATE_ARTIFACT}")


def validate_state(state, target, channel):
    if state is None:
        return
    if (state.get("schema") != 1 or state.get("target") != target or state.get("channel") != channel
            or not isinstance(state.get("published"), dict)):
        raise ValueError("Invalid beta baseline (schema/target/channel)")
    published = state["published"]
    if (not re.fullmatch(r"[0-9a-f]{64}", str(published.get("fingerprint", "")))
            or not re.fullmatch(r"[0-9a-f]{40,64}", str(published.get("commit", "")))
            or not isinstance(published.get("inputs"), dict)):
        raise ValueError("Invalid beta publication fingerprint")


def publish(target, channel, current, previous, command, output, force=False):
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    state_file = output / STATE_ARTIFACT
    # Agent checkout directories can be reused after failures.
    state_file.unlink(missing_ok=True)
    validate_state(previous, target, channel)
    baseline = previous["published"] if previous else None
    changed = sorted(path for path in set(current["inputs"]) | set(baseline["inputs"] if baseline else {})
                     if current["inputs"].get(path) != (baseline["inputs"].get(path) if baseline else None))
    needed = force or baseline is None or current["fingerprint"] != baseline["fingerprint"]
    reason = "forced" if force else "no-baseline" if baseline is None else "inputs-changed" if needed else "unchanged"
    decision = {"target": target, "channel": channel, "commit": current["commit"],
                "action": "publish" if needed else "skip", "reason": reason, "changed_inputs": changed,
                "previous_published_commit": baseline["commit"] if baseline else None}
    (output / "beta-decision.json").write_text(json.dumps(decision, indent=2) + "\n")
    print(f"Beta {target}/{channel}: {decision['action']} ({reason}, {len(changed)} changed inputs)", flush=True)
    if needed:
        subprocess.run(command, check=True)
    # Carry the delivered baseline through skipped jobs. Failed publishers never
    # advance it; the next successful matrix still sees all unpublished changes.
    state = {"schema": 1, "target": target, "channel": channel,
             "published": current if needed else baseline}
    state_file.write_text(json.dumps(state, indent=2, sort_keys=True) + "\n")
    return decision


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", choices=TARGETS, required=True)
    parser.add_argument("--channel", required=True)
    parser.add_argument("--output", default="beta-output")
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("A publishing command is required after --")
    if os.environ.get("BUILD_VCS_BRANCH") != "main":
        raise ValueError("Beta publication must run on main")
    force = os.environ.get("SHILLING_BETA_FORCE", "0")
    if force not in ("0", "1"):
        raise ValueError("SHILLING_BETA_FORCE must be 0 or 1")
    # Clear stale output before API failures too.
    (Path(args.output) / STATE_ARTIFACT).unlink(missing_ok=True)
    (Path(args.output) / "beta-decision.json").unlink(missing_ok=True)
    current = snapshot(args.target)
    previous = TeamCity().previous_state(os.environ["BETA_TEAMCITY_BUILD_TYPE"])
    publish(args.target, args.channel, current, previous, command, args.output, force == "1")


if __name__ == "__main__":
    try:
        main()
    except (KeyError, ValueError, RuntimeError, OSError, subprocess.CalledProcessError) as error:
        print(f"Beta publication failed: {error}", file=sys.stderr)
        sys.exit(1)
