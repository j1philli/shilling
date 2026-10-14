import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import urllib.error

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("windows_signing", ROOT / "scripts/ci/windows_signing.py")
signing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(signing)


def config():
    return dict(tenant_id="11111111-1111-1111-1111-111111111111",
                client_id="22222222-2222-2222-2222-222222222222",
                client_secret="unit-test-only-secret", endpoint="https://eus.codesigning.azure.net",
                account="shillingsigning", profile="shillingwindows")


class WindowsSigningTests(unittest.TestCase):
    def test_prepare_requires_main_and_complete_credentials(self):
        env = {name: config()[key] for key, name in signing.FIELDS.items()}
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "secret.json"
            for branch in ("feature/signing", "", "<default>"):
                with self.subTest(branch=branch), self.assertRaisesRegex(ValueError, "restricted to main"):
                    signing.prepare(destination, {**env, "BUILD_VCS_BRANCH": branch})
                self.assertFalse(destination.exists())
            with self.assertRaisesRegex(ValueError, "WINDOWS_SIGNING_CLIENT_SECRET"):
                signing.prepare(destination, {**env, "BUILD_VCS_BRANCH": "main", "WINDOWS_SIGNING_CLIENT_SECRET": ""})
            signing.prepare(destination, {**env, "BUILD_VCS_BRANCH": "main"})
            self.assertEqual(stat.S_IMODE(destination.stat().st_mode), 0o600)
            self.assertEqual(json.loads(destination.read_text()), config())

    def test_rejects_credential_exfiltration_endpoints_and_invalid_ids(self):
        for endpoint in ("http://eus.codesigning.azure.net", "https://evil.example", "https://eus.codesigning.azure.net.evil.example", "https://eus.codesigning.azure.net/@evil"):
            with self.subTest(endpoint=endpoint), self.assertRaises(ValueError):
                signing.validate({**config(), "endpoint": endpoint})
        for field in ("tenant_id", "client_id", "account", "profile"):
            with self.subTest(field=field), self.assertRaises(ValueError):
                signing.validate({**config(), field: "../bad"})

    def test_oauth_uses_codesigning_scope_and_does_not_print_error_body(self):
        response = io.BytesIO(b'{"access_token":"test-token"}')
        with patch.object(signing.urllib.request, "urlopen", return_value=response) as request:
            self.assertEqual(signing.access_token(config()), "test-token")
            form = signing.urllib.parse.parse_qs(request.call_args.args[0].data.decode())
            self.assertEqual(form["scope"], ["https://codesigning.azure.net/.default"])
        error = urllib.error.HTTPError("https://login.microsoftonline.com", 401, "test", {}, io.BytesIO(b"secret-detail"))
        with patch.object(signing.urllib.request, "urlopen", side_effect=error):
            with self.assertRaisesRegex(ValueError, "HTTP 401") as raised:
                signing.access_token(config())
            self.assertNotIn("secret-detail", str(raised.exception))

    def test_sign_keeps_token_out_of_arguments_and_verifies(self):
        with tempfile.TemporaryDirectory() as directory:
            secret = Path(directory) / "secret"
            secret.write_text(json.dumps(config()))
            binary = Path(directory) / "Shilling setup.exe"
            with patch.object(signing, "SECRET", secret), patch.object(signing, "access_token", return_value="private-token"), patch.object(signing.subprocess, "run") as run:
                signing.sign(binary)
            self.assertEqual(run.call_count, 2)
            command = run.call_args_list[0].args[0]
            self.assertNotIn("private-token", " ".join(command))
            self.assertNotIn(config()["client_secret"], " ".join(command))
            self.assertEqual(command[-1], str(binary.resolve()))
            self.assertIn("env:SHILLING_AZURE_SIGNING_TOKEN", command)
            self.assertIn("RFC3161", command)
            self.assertEqual(run.call_args_list[0].kwargs["env"]["SHILLING_AZURE_SIGNING_TOKEN"], "private-token")
            self.assertEqual(run.call_args_list[1].args[0][:2], ["osslsigncode", "verify"])

    def test_signing_failure_never_falls_back_to_unsigned(self):
        with tempfile.TemporaryDirectory() as directory:
            secret = Path(directory) / "secret"
            secret.write_text(json.dumps(config()))
            with patch.object(signing, "SECRET", secret), patch.object(signing, "access_token", return_value="token"), patch.object(signing.subprocess, "run", side_effect=subprocess.CalledProcessError(1, ["java"])) as run:
                with self.assertRaises(subprocess.CalledProcessError):
                    signing.sign("app.exe")
                self.assertEqual(run.call_count, 1)

    def test_unsigned_bundle_needs_no_secret_or_signing_command(self):
        with patch.object(signing.subprocess, "run") as run:
            signing.bundle(False)
        settings = json.loads(run.call_args.args[0][-1])
        self.assertNotIn("windows", settings["bundle"])
        self.assertEqual(settings["bundle"]["targets"], ["nsis"])

    def test_signed_bundle_installs_hook_and_requires_payload_and_installer_verification(self):
        with tempfile.TemporaryDirectory() as directory:
            secret = Path(directory) / "secret"
            secret.write_text(json.dumps(config()))
            installer = Path(directory) / "Shilling setup.exe"
            payload = Path(directory) / "extracted/shilling.exe"
            with patch.object(signing, "SECRET", secret), patch.object(Path, "glob", return_value=[installer]), patch.object(Path, "rglob", return_value=[payload]), patch.object(signing.subprocess, "run") as run:
                signing.bundle(True)
            command = run.call_args_list[0].args[0]
            self.assertNotIn("--no-sign", command)
            settings = json.loads(command[-1])
            hook = settings["bundle"]["windows"]["signCommand"]
            self.assertEqual(hook["cmd"], "python3")
            self.assertEqual(hook["args"][-2:], ["sign", "%1"])
            verified = [call.args[0][-1] for call in run.call_args_list[1:] if call.args[0][0] == "osslsigncode"]
            self.assertEqual(verified, [str(installer), str(payload)])
            with patch.object(signing, "SECRET", secret), patch.object(Path, "glob", return_value=[installer]), patch.object(signing.subprocess, "run", side_effect=[None, subprocess.CalledProcessError(1, ["osslsigncode"])]):
                with self.assertRaises(subprocess.CalledProcessError):
                    signing.bundle(True)

    def test_preflight_failure_removes_stale_installers(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "desktop-artifacts/windows"
            output.mkdir(parents=True)
            (output / "old-unsigned.exe").write_text("fixture")
            result = subprocess.run(["bash", str(ROOT / "scripts/ci/build-desktop-windows.sh")],
                                    cwd=root, env={**os.environ, "SHILLING_WINDOWS_SIGNED": "invalid"},
                                    capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(list(output.iterdir()), [])

    def test_build_context_excludes_caches_and_secrets_and_disables_signing_cache(self):
        # Exercise the real host wrapper against a Docker stand-in. It inspects
        # context before the wrapper's cleanup and emits one fake installer.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ("src-tauri/target", "src-tauri/gen", "web-app-dist", "scripts/ci", "deploy/desktop", "bin"):
                (root / name).mkdir(parents=True, exist_ok=True)
            for name in ("web-app-dist/index.html", "web-app-dist/web-app.wasm", "src-tauri/target/old.exe", "src-tauri/gen/old", "src-tauri/Cargo.toml"):
                (root / name).write_text("fixture")
            for name in ("scripts/ci/windows_signing.py", "deploy/desktop/windows-cross.Dockerfile"):
                (root / name).write_bytes((ROOT / name).read_bytes())
            docker = root / "bin/docker"
            docker.write_text('''#!/usr/bin/env python3
import json, pathlib, sys
if sys.argv[1:] == ['buildx', 'version']: sys.exit(0)
args = sys.argv[1:]
context = pathlib.Path(args[-1])
assert not (context / 'src-tauri/target').exists()
assert not (context / 'src-tauri/gen').exists()
assert 'unit-test-only-secret' not in str(args)
assert args[args.index('--no-cache-filter') + 1] == 'package'
secret = pathlib.Path(args[args.index('--secret') + 1].split('src=')[1])
assert secret.parent != context
assert json.loads(secret.read_text())['client_secret'] == 'unit-test-only-secret'
pathlib.Path('secret-path.txt').write_text(str(secret))
output = pathlib.Path(args[args.index('--output') + 1].split('dest=')[1])
(output / 'Shilling_0.1.3_x64-setup.exe').write_text('fixture')
''')
            docker.chmod(0o755)
            env = {**os.environ, **{name: config()[key] for key, name in signing.FIELDS.items()},
                   "PATH": str(root / "bin") + os.pathsep + os.environ["PATH"],
                   "SHILLING_WINDOWS_SIGNED": "1", "BUILD_VCS_BRANCH": "main"}
            subprocess.run(["bash", str(ROOT / "scripts/ci/build-desktop-windows.sh")], cwd=root, env=env, check=True, capture_output=True)
            self.assertFalse(Path((root / "secret-path.txt").read_text()).exists())


if __name__ == "__main__":
    unittest.main()
