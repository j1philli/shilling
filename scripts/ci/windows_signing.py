#!/usr/bin/env python3
"""Azure Artifact Signing for Tauri's Linux cross-build (no Azure CLI required)."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import uuid

SECRET = Path("/run/secrets/windows_signing")
JSIGN = "/opt/jsign/jsign.jar"
TRUST = "/opt/jsign/trust.pem"
FIELDS = {
    "tenant_id": "WINDOWS_SIGNING_TENANT_ID",
    "client_id": "WINDOWS_SIGNING_CLIENT_ID",
    "client_secret": "WINDOWS_SIGNING_CLIENT_SECRET",
    "endpoint": "WINDOWS_SIGNING_ENDPOINT",
    "account": "WINDOWS_SIGNING_ACCOUNT",
    "profile": "WINDOWS_SIGNING_PROFILE",
}


def validate(config):
    for key, env in FIELDS.items():
        value = config.get(key)
        if not isinstance(value, str) or not value.strip() or value.startswith("%"):
            raise ValueError(f"Configure {env} before requesting a signed Windows build")
    for key in ("tenant_id", "client_id"):
        try:
            uuid.UUID(config[key])
        except ValueError:
            raise ValueError(f"{FIELDS[key]} must be a UUID") from None
    if not re.fullmatch(r"https://[a-z0-9]+\.codesigning\.azure\.net/?", config["endpoint"]):
        raise ValueError("WINDOWS_SIGNING_ENDPOINT must be an HTTPS Azure codesigning endpoint")
    for key in ("account", "profile"):
        if not re.fullmatch(r"[A-Za-z][A-Za-z0-9-]{1,98}[A-Za-z0-9]", config[key]):
            raise ValueError(f"Invalid {FIELDS[key]}")
    return config


def prepare(destination, env):
    # Fail closed before starting Docker. The dedicated TeamCity signing job also
    # limits branch selection; this check is defense in depth, not a secret boundary.
    if env.get("BUILD_VCS_BRANCH") not in ("main", "refs/heads/main"):
        raise ValueError("Windows publisher signing is restricted to main")
    config = validate({key: env.get(name, "") for key, name in FIELDS.items()})
    with open(os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
        json.dump(config, stream)


def access_token(config):
    data = urllib.parse.urlencode({
        "client_id": config["client_id"], "client_secret": config["client_secret"],
        "grant_type": "client_credentials", "scope": "https://codesigning.azure.net/.default",
    }).encode()
    request = urllib.request.Request(
        f"https://login.microsoftonline.com/{config['tenant_id']}/oauth2/v2.0/token",
        data=data, headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            token = json.load(response).get("access_token")
    except urllib.error.HTTPError as error:
        # Never log the response body, credentials, or access token.
        raise ValueError(f"Azure authentication failed (HTTP {error.code}); check tenant/client/secret") from None
    except (urllib.error.URLError, TimeoutError, ValueError):
        raise ValueError("Azure authentication failed; check connectivity and Azure credentials") from None
    if not isinstance(token, str) or not token:
        raise ValueError("Azure authentication returned no access token")
    return token


def sign(path):
    config = validate(json.loads(SECRET.read_text()))
    token = access_token(config)
    env = {**os.environ, "SHILLING_AZURE_SIGNING_TOKEN": token}
    # Jsign 7.6 accepts env: passwords. Neither credentials nor tokens appear in
    # process arguments, the Dockerfile, image layers, or build output.
    subprocess.run([
        "java", "-jar", JSIGN, "--storetype", "TRUSTEDSIGNING",
        "--keystore", config["endpoint"], "--alias", f"{config['account']}/{config['profile']}",
        "--storepass", "env:SHILLING_AZURE_SIGNING_TOKEN", "--alg", "SHA-256",
        "--name", "Shilling", "--url", "https://shilling.finance",
        "--tsaurl", "http://timestamp.acs.microsoft.com", "--tsmode", "RFC3161",
        str(Path(path).resolve()),
    ], env=env, check=True)
    subprocess.run(["osslsigncode", "verify", "-CAfile", TRUST, "-TSA-CAfile", TRUST,
                    "-in", str(Path(path).resolve())], check=True)


def bundle(signed):
    config = {"build": {"beforeBuildCommand": ""}, "bundle": {"targets": ["nsis"]}}
    if signed:
        validate(json.loads(SECRET.read_text()))
        config["bundle"]["windows"] = {"signCommand": {
            "cmd": "python3", "args": [str(Path(__file__).resolve()), "sign", "%1"],
        }}
    subprocess.run([
        "tauri", "build", "--runner", "cargo-xwin", "--target", "x86_64-pc-windows-msvc",
        "--config", json.dumps(config),
    ], check=True)
    if signed:
        # Tauri restores the unsigned build binary after bundling. Verify the
        # signed payload INSIDE the installer, not that restored build output.
        installers = list(Path("target/x86_64-pc-windows-msvc/release/bundle/nsis").glob("*.exe"))
        if not installers:
            raise ValueError("Missing signed NSIS installer")
        for path in installers:
            subprocess.run(["osslsigncode", "verify", "-CAfile", TRUST, "-TSA-CAfile", TRUST,
                            "-in", str(path)], check=True)
            with tempfile.TemporaryDirectory() as directory:
                subprocess.run(["7z", "x", "-y", f"-o{directory}", str(path)],
                               check=True, capture_output=True)
                payloads = list(Path(directory).rglob("shilling.exe"))
                if len(payloads) != 1:
                    raise ValueError("Expected exactly one Shilling executable inside the NSIS installer")
                subprocess.run(["osslsigncode", "verify", "-CAfile", TRUST, "-TSA-CAfile", TRUST,
                                "-in", str(payloads[0])], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("prepare").add_argument("destination")
    commands.add_parser("sign").add_argument("path")
    commands.add_parser("bundle").add_argument("--signed", action="store_true")
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            prepare(args.destination, os.environ)
        elif args.command == "sign":
            sign(args.path)
        else:
            bundle(args.signed)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        # CalledProcessError contains only non-secret arguments by design.
        print(f"Windows signing: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
