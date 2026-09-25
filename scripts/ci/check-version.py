#!/usr/bin/env python3
"""Keep the product version aligned across the platform manifests."""

import argparse
import json
import plistlib
import re
import sys
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read_json(path: str) -> dict:
    return json.loads((ROOT / path).read_text())


def read_toml(path: str) -> dict:
    with (ROOT / path).open("rb") as source:
        return tomllib.load(source)


def read_plist(path: str) -> dict:
    with (ROOT / path).open("rb") as source:
        return plistlib.load(source)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", help="Release tag to validate, for example v0.1.0-beta.2")
    args = parser.parse_args()

    version = (ROOT / "VERSION").read_text().strip()
    if not re.fullmatch(r"(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)", version):
        parser.error("VERSION must contain exactly MAJOR.MINOR.PATCH")

    values = {
        "package.json": read_json("package.json")["version"],
        "package-lock.json": read_json("package-lock.json")["version"],
        "package-lock.json root package": read_json("package-lock.json")["packages"][""]["version"],
        "src-tauri/tauri.conf.json": read_json("src-tauri/tauri.conf.json")["version"],
        "src-tauri/Cargo.toml": read_toml("src-tauri/Cargo.toml")["package"]["version"],
        "app/ios-app/src/Info.plist": read_plist("app/ios-app/src/Info.plist")["CFBundleShortVersionString"],
        "app/ios-app/ShillingWidgetExtension/Info.plist": read_plist("app/ios-app/ShillingWidgetExtension/Info.plist")["CFBundleShortVersionString"],
    }

    cargo_packages = read_toml("src-tauri/Cargo.lock")["package"]
    shilling_packages = [package for package in cargo_packages if package["name"] == "shilling"]
    if len(shilling_packages) != 1:
        parser.error("Cargo.lock must contain exactly one shilling package")
    values["src-tauri/Cargo.lock shilling package"] = shilling_packages[0]["version"]

    android_module = (ROOT / "app/android-app/module.yaml").read_text()
    android_version = re.search(r"^\s+versionName:\s*[\"']?([^\"'\s]+)", android_module, re.MULTILINE)
    android_code = re.search(r"^\s+versionCode:\s*(\d+)\s*$", android_module, re.MULTILINE)
    if android_version is None or android_code is None or int(android_code.group(1)) < 1:
        parser.error("Android must declare versionName and a positive versionCode")
    values["app/android-app/module.yaml"] = android_version.group(1)

    xcode_project = (ROOT / "app/ios-app/module.xcodeproj/project.pbxproj").read_text()
    marketing_versions = re.findall(r"\bMARKETING_VERSION = ([^;]+);", xcode_project)
    if not marketing_versions:
        parser.error("Xcode project has no MARKETING_VERSION")
    for index, marketing_version in enumerate(marketing_versions, start=1):
        values[f"Xcode MARKETING_VERSION #{index}"] = marketing_version

    mismatches = [f"{name}: {value}" for name, value in values.items() if value != version]
    if mismatches:
        print(f"Expected product version {version}; found mismatches:", file=sys.stderr)
        for mismatch in mismatches:
            print(f"  {mismatch}", file=sys.stderr)
        return 1

    if args.tag:
        tag_pattern = re.compile(
            r"v(?P<base>(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*))"
            r"(?:-(?:alpha|beta|rc)\.(?:0|[1-9]\d*))?"
        )
        tag = tag_pattern.fullmatch(args.tag)
        if tag is None or tag.group("base") != version:
            print(f"Release tag {args.tag!r} must match VERSION {version} (optionally -alpha.N, -beta.N, or -rc.N)", file=sys.stderr)
            return 1

    print(f"Version check passed: {version}" + (f" ({args.tag})" if args.tag else ""))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
