#!/usr/bin/env python3
"""Two manual TeamCity release buttons; promote artifacts from a tested chain."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile

# Keep direct script execution and import-based workflow tests consistent.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from ios_provenance import load_candidate
from apple_store import AppleAPI, approval, promote
from desktop_updates import prepare_stable, publish_stable

REPO = "j1philli/shilling"
TARGETS = ("server", "web", "android", "ios", "linux", "windows", "macos")
ROOT = Path(__file__).resolve().parents[2]


def run(*args, capture=False, env=None):
    return subprocess.run(args, check=True, text=True, capture_output=capture, env=env).stdout


def selected_targets(mode, target):
    if mode == "full":
        return list(TARGETS)
    if mode == "single" and target in TARGETS:
        return [target]
    raise ValueError("Choose full release or a valid single target")


def github_get(endpoint):
    result = subprocess.run(["gh", "api", endpoint], text=True, capture_output=True)
    if result.returncode:
        if "(HTTP 404)" in result.stderr:
            return None
        raise RuntimeError(f"GitHub request failed for {endpoint}: {result.stderr}")
    return json.loads(result.stdout)


def collect_assets(targets, version, output):
    assets = []
    for target in targets:
        if target == "web":
            source = Path("web-app-dist")
            for name in ("index.html", "web-app.wasm"):
                if not (source / name).is_file():
                    raise ValueError(f"Missing web artifact: {name}")
            if "SHILLING_SELF_HOSTED_ONLY" in (source / "index.html").read_text():
                raise ValueError("Web artifact is not the original hosted bundle")
            if (source / "web-app.wasm").stat().st_size > 26214400:
                raise ValueError("Web WASM exceeds Cloudflare Pages' 25 MiB limit")
            archive = output / f"Shilling_{version}_web.zip"
            with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as z:
                for path in sorted(source.rglob("*")):
                    if path.is_file():
                        z.write(path, path.relative_to(source))
            assets.append(archive)
        elif target == "server":
            source = Path("release-input/server/server-jvm-executable.jar")
            if not source.is_file():
                raise ValueError("Missing tested server JAR")
            dest = output / f"Shilling_{version}_server.jar"
            shutil.copy2(source, dest)
            assets.append(dest)
        else:
            sources = sorted(p for p in Path(f"release-input/clients/{target}").rglob("*") if p.is_file())
            if not sources:
                raise ValueError(f"Missing tested {target} packages")
            for source in sources:
                match = re.match(r"^Shilling[_-](\d+\.\d+\.\d+)[_-]", source.name)
                if not match or match.group(1) != version:
                    raise ValueError(f"Unexpected {target} package for version {version}: {source.name}")
                dest = output / source.name
                if dest.exists():
                    raise ValueError(f"Duplicate release filename: {source.name}")
                shutil.copy2(source, dest)
                assets.append(dest)
    return assets


def release_notes(manifest):
    targets = manifest["targets"]
    marker = f"<!-- shilling-release:{manifest['commit']}:{','.join(targets)} -->"
    lines = [marker, f"Shilling {manifest['version']}", "", "Included targets: " + ", ".join(targets) + ".", ""]
    for target in ("server", "web"):
        if target in targets:
            lines.append(f"- `{target}` image: `ghcr.io/j1philli/shilling-{target}:{manifest['tag']}` (Linux amd64/arm64).")
    if "web" in targets:
        lines.append("- Hosted web: https://app.shilling.finance (deployed by this release).")
    lines += ["", "Package status:"]
    caveats = {
        "android": "Android: debug-signed APK and unsigned release AAB; no Play upload.",
        "ios": ("iOS: promotes the tested, Apple-approved build to the App Store. The signed IPA is retained for provenance and is not directly installable from GitHub."
                if any(name.endswith("_ios.ipa") for name in manifest.get("assets", {}))
                else "iOS: unsigned development app archive; not installable on an iPhone. No TestFlight/App Store upload."),
        "macos": "macOS: unsigned universal DMG; not notarized.",
        "windows": "Windows: unsigned x86_64 NSIS installer.",
        "linux": "Linux: x86_64 AppImage, Debian and RPM packages.",
    }
    lines += ["- " + caveats[t] for t in targets if t in caveats]
    if "server" in targets:
        lines.append("- Hosted signaling server: https://api.shilling.finance (deployed by this release using the published image digest).")
    lines += ["", f"Source: `{manifest['commit']}`. Artifact hashes are in `release-manifest.json`."]
    return "\n".join(lines) + "\n", marker


def main():
    os.chdir(ROOT)
    targets = selected_targets(os.environ.get("SHILLING_RELEASE_MODE", ""), os.environ.get("SHILLING_RELEASE_TARGET", ""))
    dry_run = os.environ.get("SHILLING_RELEASE_DRY_RUN", "0")
    if dry_run not in ("0", "1"):
        raise ValueError("SHILLING_RELEASE_DRY_RUN must be 0 or 1")
    version = Path("VERSION").read_text().strip()
    run("python3", "scripts/ci/check-version.py")
    tag = "v" + version
    commit = run("git", "rev-parse", "HEAD", capture=True).strip()
    branch = os.environ.get("BUILD_VCS_BRANCH", "")
    if dry_run == "0" and branch not in ("main", "refs/heads/main", tag, "refs/tags/" + tag):
        raise ValueError("Release buttons must run on main or the matching version tag")
    output = Path("release-output")
    shutil.rmtree(output, ignore_errors=True)
    output.mkdir()
    assets = collect_assets(targets, version, output)
    if "server" in targets and "web" in targets:
        shutil.copy2("deploy/self-host/compose.yaml", output / "compose.yaml")
        (output / "shilling.env.example").write_text(f"SHILLING_VERSION={tag}\n")
        assets += [output / "compose.yaml", output / "shilling.env.example"]
    ios_candidate = None
    if "ios" in targets:
        ios_candidate = load_candidate("release-input/clients/ios", version, commit)
    manifest = {"version": version, "tag": tag, "commit": commit, "targets": targets,
                "teamcity_build": os.environ.get("TEAMCITY_BUILD_ID"),
                "assets": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in assets}}
    if ios_candidate:
        manifest["ios_candidate"] = ios_candidate
    manifest_path = output / "release-manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    assets.append(manifest_path)
    notes, marker = release_notes(manifest)
    notes_path = output / "release-notes.md"
    notes_path.write_text(notes)
    print(f"Release {tag} from {commit}: {', '.join(targets)}", flush=True)
    print(notes, flush=True)
    # An unapproved or mismatched iOS build blocks the entire release before
    # tags, drafts, images, or hosted deployments are changed. Preview reports
    # approval readiness but remains usable while Apple is reviewing the app.
    apple_api = AppleAPI() if ios_candidate else None
    ios_approval = None
    if ios_candidate:
        try:
            ios_approval = approval(apple_api, ios_candidate)
        except ValueError as error:
            if dry_run != "1":
                raise
            print(f"iOS release blocked: {error}", flush=True)
        (output / "ios-approval.json").write_text(json.dumps(ios_approval or {"ready": False}, indent=2) + "\n")
    desktop_candidates = []
    try:
        desktop_candidates = prepare_stable(targets, version, commit)
    except ValueError as error:
        if dry_run != "1":
            raise
        print(f"Desktop release blocked: {error}", flush=True)
    if "server" in targets:
        run("python3", "scripts/ci/deploy-coolify.py", "--check-only")
    if dry_run == "1":
        print("Preview complete: artifacts collected and validated; no tags, images, releases or deployments were changed.")
        return

    # Verify credentials and branch/tag provenance before making any remote changes.
    if not os.environ.get("GITHUB_TOKEN"):
        raise ValueError("Missing TeamCity env.GITHUB_TOKEN")
    os.environ["GH_TOKEN"] = os.environ["GITHUB_TOKEN"]
    images = [t for t in ("server", "web") if t in targets]
    if images and not os.environ.get("GHCR_TOKEN"):
        raise ValueError("Missing TeamCity env.GHCR_TOKEN")
    if images:
        run("docker", "buildx", "version")
    if "web" in targets:
        for name in ("CLOUDFLARE_ACCOUNT_ID", "CLOUDFLARE_PAGES_PROJECT"):
            if not os.environ.get(name):
                raise ValueError(f"Missing TeamCity env.{name}")
        if not (os.environ.get("CLOUDFLARE_API_TOKEN") or os.environ.get("CF_API_TOKEN")):
            raise ValueError("Missing Cloudflare API token")
    run("git", "fetch", "--quiet", f"https://github.com/{REPO}.git", "refs/heads/main")
    run("git", "merge-base", "--is-ancestor", commit, "FETCH_HEAD")
    ref = github_get(f"repos/{REPO}/git/ref/tags/{tag}")
    if ref:
        run("git", "fetch", "--quiet", f"https://github.com/{REPO}.git", "refs/tags/" + tag)
        if run("git", "rev-parse", "FETCH_HEAD^{commit}", capture=True).strip() != commit:
            raise ValueError(f"{tag} already belongs to another commit; bump VERSION in a PR first")
    release = github_get(f"repos/{REPO}/releases/tags/{tag}")
    if release:
        if marker not in (release.get("body") or ""):
            raise ValueError(f"{tag} already belongs to another release selection; bump VERSION first")
        if not release["draft"]:
            # Resume a failed feed deployment after the GitHub release was published.
            publish_stable(desktop_candidates, commit)
            print("This release is already published; update feeds reconciled.")
            return
    if not ref:
        run("gh", "api", f"repos/{REPO}/git/refs", "--method", "POST", "-f", "ref=refs/tags/" + tag,
            "-f", "sha=" + commit, capture=True)
    if not release:
        args = ["gh", "release", "create", tag, "--repo", REPO, "--verify-tag", "--draft", "--title", tag,
                "--notes-file", str(notes_path)]
        # GitHub prerelease labeling is independent of App Store approval.
        if version.startswith("0."):
            args.append("--prerelease")
        run(*args)
    run("gh", "release", "upload", tag, *map(str, assets), "--repo", REPO, "--clobber")
    release_env = dict(os.environ, SHILLING_RELEASE_TAG=tag, SHILLING_RELEASE_COMMIT=commit,
                       SHILLING_RELEASE_TARGETS=",".join(images))
    if images:
        run("bash", "scripts/ci/publish-release-images.sh", env=release_env)
    if "server" in targets:
        run("python3", "scripts/ci/deploy-coolify.py", env=release_env)
        run("gh", "release", "upload", tag, "release-output/server-deployment.json", "--repo", REPO, "--clobber")
    if "web" in targets:
        run("bash", "scripts/ci/deploy-hosted-web.sh", env=release_env)
    if ios_candidate:
        promote(apple_api, ios_candidate, ios_approval, output / "ios-deployment.json")
        run("gh", "release", "upload", tag, "release-output/ios-deployment.json", "--repo", REPO, "--clobber")
    # Publish the release last. A failure above leaves a resumable draft.
    run("gh", "release", "edit", tag, "--repo", REPO, "--draft=false", "--latest=" + ("false" if version.startswith("0.") else "true"))
    publish_stable(desktop_candidates, commit)
    print(f"Released {tag}: https://github.com/{REPO}/releases/tag/{tag}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"Release failed: {error}", file=sys.stderr)
        sys.exit(1)
