#!/usr/bin/env python3
"""Set the shared product version and optionally advance store build numbers."""

import argparse
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version", help="New MAJOR.MINOR.PATCH product version")
    parser.add_argument("--android", action="store_true", help="Increase Android versionCode for a Play upload")
    parser.add_argument("--ios", action="store_true", help="Increase iOS build number for an App Store upload")
    args = parser.parse_args()
    if not re.fullmatch(r"(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)", args.version):
        parser.error("version must be MAJOR.MINOR.PATCH")

    subprocess.run(["python3", str(ROOT / "scripts/ci/check-version.py")], check=True)
    old = (ROOT / "VERSION").read_text().strip()
    changes: dict[Path, str] = {}

    def replace(path: str, pattern: str, value: str, count: int = 1) -> None:
        file = ROOT / path
        source = changes.get(file, file.read_text())
        result, found = re.subn(pattern, lambda match: match.group(1) + value + match.group(2), source, flags=re.MULTILINE)
        if found != count:
            raise RuntimeError(f"Expected {count} version field(s) in {path}, found {found}")
        changes[file] = result

    # Match a field and its surrounding context rather than every occurrence
    # of the old number (dependency versions can happen to be identical).
    replace("package.json", rf'(\A\{{\s*"name": "shilling",\s*"version": "){re.escape(old)}(")', args.version)
    replace("package-lock.json", rf'(\A\{{\s*"name": "shilling",\s*"version": "){re.escape(old)}(")', args.version)
    replace("package-lock.json", rf'("packages": \{{\s*"": \{{\s*"name": "shilling",\s*"version": "){re.escape(old)}(")', args.version)
    replace("src-tauri/tauri.conf.json", rf'(\A\{{[\s\S]*?"version": "){re.escape(old)}(")', args.version)
    replace("src-tauri/Cargo.toml", rf'(\[package\]\s*name = "shilling"\s*version = "){re.escape(old)}(")', args.version)
    replace("src-tauri/Cargo.lock", rf'(\[\[package\]\]\s*name = "shilling"\s*version = "){re.escape(old)}(")', args.version)
    replace("app/android-app/module.yaml", rf'(^\s+versionName: "){re.escape(old)}("\s*$)', args.version)
    replace("app/ios-app/module.xcodeproj/project.pbxproj", rf'(^\s*MARKETING_VERSION = ){re.escape(old)}(;)', args.version, count=2)
    for path in ("app/ios-app/src/Info.plist", "app/ios-app/ShillingWidgetExtension/Info.plist"):
        replace(path, rf'(<key>CFBundleShortVersionString</key>\s*<string>){re.escape(old)}(</string>)', args.version)

    if args.android:
        path = "app/android-app/module.yaml"
        match = re.search(r"^\s+versionCode: (\d+)\s*$", changes[ROOT / path], re.MULTILINE)
        if match is None:
            raise RuntimeError("Android versionCode is missing")
        replace(path, rf'(^\s+versionCode: ){match.group(1)}(\s*$)', str(int(match.group(1)) + 1))

    if args.ios:
        project = "app/ios-app/module.xcodeproj/project.pbxproj"
        plist_paths = ("app/ios-app/src/Info.plist", "app/ios-app/ShillingWidgetExtension/Info.plist")
        numbers = [int(value) for value in re.findall(r"CURRENT_PROJECT_VERSION = (\d+);", changes[ROOT / project])]
        for path in plist_paths:
            numbers += [int(value) for value in re.findall(r"<key>CFBundleVersion</key>\s*<string>(\d+)</string>", changes[ROOT / path])]
        if len(numbers) != 4:
            raise RuntimeError("Expected two Xcode and two plist iOS build numbers")
        next_build = str(max(numbers) + 1)
        replace(project, r'(^\s*CURRENT_PROJECT_VERSION = )\d+(;)', next_build, count=2)
        for path in plist_paths:
            replace(path, r'(<key>CFBundleVersion</key>\s*<string>)\d+(</string>)', next_build)

    changes[ROOT / "VERSION"] = args.version + "\n"
    for file, content in changes.items():
        file.write_text(content)
    subprocess.run(["python3", str(ROOT / "scripts/ci/check-version.py")], check=True)
    print(f"Set Shilling version to {args.version}")


if __name__ == "__main__":
    main()
