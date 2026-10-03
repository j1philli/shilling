"""Bind the exact signed IPA to its source commit and TeamCity build."""
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import subprocess
import sys
import zipfile

BUNDLE_ID = "finance.shilling.app"


def ipa_info(ipa):
    with zipfile.ZipFile(ipa) as z:
        names = [n for n in z.namelist() if re.fullmatch(r"Payload/[^/]+\.app/Info.plist", n)]
        if len(names) != 1:
            raise ValueError("IPA must contain exactly one application")
        info = plistlib.loads(z.read(names[0]))
        for name in z.namelist():
            if re.fullmatch(r"Payload/[^/]+\.app/PlugIns/[^/]+\.appex/Info.plist", name):
                widget = plistlib.loads(z.read(name))
                for field in ("CFBundleVersion", "CFBundleShortVersionString"):
                    if widget.get(field) != info.get(field):
                        raise ValueError("IPA app and extension versions differ")
        return info


def record(ipa, version, commit, build_id):
    ipa = Path(ipa)
    info = ipa_info(ipa)
    if info.get("CFBundleIdentifier") != BUNDLE_ID or info.get("CFBundleShortVersionString") != version:
        raise ValueError("IPA bundle ID or product version does not match")
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Missing source commit")
    if str(info.get("CFBundleVersion")) != str(build_id):
        raise ValueError("IPA build number must equal its TeamCity build ID")
    return {"version": version, "build_number": str(build_id), "commit": commit,
            "bundle_id": BUNDLE_ID, "teamcity_build_id": str(build_id),
            "ipa": ipa.name, "sha256": hashlib.sha256(ipa.read_bytes()).hexdigest()}


def load_candidate(directory, version, commit):
    directory = Path(directory)
    path = directory / f"Shilling_{version}_ios-build.json"
    if not path.is_file():
        raise ValueError("Missing iOS build provenance; use an iOS build from the TestFlight workflow")
    candidate = json.loads(path.read_text())
    filename = candidate.get("ipa", "")
    if filename != f"Shilling_{version}_ios.ipa":
        raise ValueError("Unexpected IPA filename")
    actual = record(directory / filename, version, commit, candidate.get("build_number"))
    if candidate != actual:
        raise ValueError("iOS candidate provenance/hash does not match the selected commit and IPA")
    return candidate


if __name__ == "__main__":
    ipa = Path(sys.argv[1])
    version = Path("VERSION").read_text().strip()
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    candidate = record(ipa, version, commit, os.environ["TEAMCITY_BUILD_ID"])
    (ipa.parent / f"Shilling_{version}_ios-build.json").write_text(json.dumps(candidate, indent=2) + "\n")
