#!/usr/bin/env python3
"""Reject generic Xcode archives and mismatched iOS app/extension versions."""
import plistlib
from pathlib import Path
import sys


def check_archive(archive, version):
    archive = Path(archive)
    with (archive / "Info.plist").open("rb") as f:
        info = plistlib.load(f)
    properties = info.get("ApplicationProperties", {})
    if properties.get("ApplicationPath") != "Applications/ios-app.app":
        raise ValueError("Xcode produced a generic archive, not an exportable iOS application archive")
    applications = archive / "Products/Applications"
    if {p.name for p in applications.iterdir()} != {"ios-app.app"}:
        raise ValueError("Archive contains extra products beside the iOS app")
    app = applications / "ios-app.app"
    bundles = [app, *sorted((app / "PlugIns").glob("*.appex"))]
    builds = set()
    for bundle in bundles:
        with (bundle / "Info.plist").open("rb") as f:
            metadata = plistlib.load(f)
        if metadata.get("CFBundleShortVersionString") != version:
            raise ValueError(f"Wrong product version in {bundle.name}")
        build = metadata.get("CFBundleVersion")
        if not build:
            raise ValueError(f"Missing build number in {bundle.name}")
        builds.add(build)
    if len(builds) != 1:
        raise ValueError("App and extension build numbers differ")
    print(f"iOS application archive verified: version {version}, build {builds.pop()}")


if __name__ == "__main__":
    check_archive(*sys.argv[1:])
