#!/usr/bin/env python3
"""Build, sign, notarize and verify a universal DMG using the Mac agent's keychain."""
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess


def run(*args, **kwargs):
    return subprocess.run(args, check=True, text=True, **kwargs)


def configuration(env):
    if env.get("BUILD_VCS_BRANCH") not in ("main", "refs/heads/main"):
        raise ValueError("macOS publisher signing is restricted to main")
    names = ("MACOS_APP_IDENTITY", "MACOS_KEYCHAIN_PATH", "MACOS_KEYCHAIN_PASSWORD_FILE",
             "MACOS_NOTARY_KEY_PATH", "MACOS_NOTARY_KEY_ID", "MACOS_NOTARY_ISSUER")
    for name in names:
        if not env.get(name) or env[name].startswith("%"):
            raise ValueError(f"Configure {name} before requesting a signed build")
    return {**env, "APPLE_SIGNING_IDENTITY": env["MACOS_APP_IDENTITY"],
            "APPLE_API_KEY": env["MACOS_NOTARY_KEY_ID"],
            "APPLE_API_ISSUER": env["MACOS_NOTARY_ISSUER"],
            "APPLE_API_KEY_PATH": env["MACOS_NOTARY_KEY_PATH"], "CI": "true"}


def main():
    output = Path("desktop-artifacts/macos-signed")
    shutil.rmtree(output, ignore_errors=True)
    env = configuration(os.environ)
    env["PATH"] = str(Path.home() / ".cargo/bin") + os.pathsep + env["PATH"]
    for name in ("MACOS_KEYCHAIN_PATH", "MACOS_KEYCHAIN_PASSWORD_FILE", "MACOS_NOTARY_KEY_PATH"):
        if not Path(env[name]).is_file():
            raise ValueError(f"Runner file missing: {name}")
    for name in ("index.html", "web-app.wasm"):
        if not (Path("web-app-dist") / name).is_file():
            raise ValueError(f"Missing Web Build artifact: {name}")
    keychain = env["MACOS_KEYCHAIN_PATH"]
    # Keep the existing shared runner search list intact after this job. Tauri
    # uses the search list when signing with an already-installed identity.
    previous = shlex.split(run("security", "list-keychains", "-d", "user", capture_output=True).stdout)
    password = Path(env["MACOS_KEYCHAIN_PASSWORD_FILE"]).read_text().strip()
    result = subprocess.run(["security", "unlock-keychain", "-p", password, keychain],
                            capture_output=True)
    if result.returncode:
        # Do not include CalledProcessError's password-bearing command arguments.
        raise ValueError("Could not unlock the Mac signing keychain")
    bundle = Path("src-tauri/target/universal-apple-darwin/release/bundle")
    shutil.rmtree(bundle, ignore_errors=True)
    try:
        run("security", "list-keychains", "-d", "user", "-s", keychain,
            *(p for p in previous if p != keychain))
        run("cargo", "tauri", "build", "--bundles", "dmg", "--target", "universal-apple-darwin",
            "--config", '{"build":{"beforeBuildCommand":""},"bundle":{"macOS":{"hardenedRuntime":true}}}', env=env)
        apps, dmgs = list((bundle / "macos").glob("*.app")), list((bundle / "dmg").glob("*.dmg"))
        if len(apps) != 1 or len(dmgs) != 1:
            raise ValueError("Expected one universal app and one DMG")
        run("codesign", "--verify", "--deep", "--strict", str(apps[0]))
        run("xcrun", "stapler", "validate", str(apps[0]))
        run("spctl", "--assess", "--type", "execute", str(apps[0]))
        dmg = dmgs[0]
        run("codesign", "--force", "--timestamp", "--keychain", keychain,
            "--sign", env["MACOS_APP_IDENTITY"], str(dmg))
        result = run("xcrun", "notarytool", "submit", str(dmg), "--key", env["MACOS_NOTARY_KEY_PATH"],
                     "--key-id", env["MACOS_NOTARY_KEY_ID"], "--issuer", env["MACOS_NOTARY_ISSUER"],
                     "--wait", "--timeout", "30m", "--output-format", "json", capture_output=True)
        report = json.loads(result.stdout)
        if report.get("status") != "Accepted":
            raise ValueError(f"DMG notarization was not accepted; submission {report.get('id')}")
        run("xcrun", "stapler", "staple", str(dmg))
        run("xcrun", "stapler", "validate", str(dmg))
        run("codesign", "--verify", "--strict", str(dmg))
        output.mkdir(parents=True)
        shutil.copy2(dmg, output / dmg.name)
        (output / "notarization.json").write_text(json.dumps(report, indent=2) + "\n")
    finally:
        run("security", "list-keychains", "-d", "user", "-s", *previous)


if __name__ == "__main__":
    main()
