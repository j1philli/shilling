import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("macos_signing", Path(__file__).parents[1] / "build-desktop-macos-signed.py")
mac = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mac)


class MacSigningTest(unittest.TestCase):
    def config(self):
        return dict(BUILD_VCS_BRANCH="main", MACOS_APP_IDENTITY="Developer ID Application",
                    MACOS_KEYCHAIN_PATH="keychain", MACOS_KEYCHAIN_PASSWORD_FILE="password",
                    MACOS_NOTARY_KEY_PATH="key.p8", MACOS_NOTARY_KEY_ID="key-id",
                    MACOS_NOTARY_ISSUER="issuer", PATH=os.environ["PATH"])

    def test_fail_closed_and_map_notary_credentials(self):
        config = self.config()
        mapped = mac.configuration(config)
        self.assertEqual(mapped["APPLE_API_KEY_PATH"], "key.p8")
        self.assertEqual(mapped["APPLE_SIGNING_IDENTITY"], "Developer ID Application")
        with self.assertRaises(ValueError):
            mac.configuration({**config, "BUILD_VCS_BRANCH": "feature"})
        with self.assertRaises(ValueError):
            mac.configuration({**config, "MACOS_NOTARY_KEY_ID": ""})

    def test_failed_build_restores_keychain_and_clears_stale_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            old = Path.cwd()
            os.chdir(directory)
            try:
                for name in ("keychain", "password", "key.p8"):
                    Path(name).write_text("test-only")
                Path("web-app-dist").mkdir()
                for name in ("index.html", "web-app.wasm"):
                    (Path("web-app-dist") / name).touch()
                output = Path("desktop-artifacts/macos-signed")
                output.mkdir(parents=True)
                (output / "old.dmg").touch()
                with patch.dict(os.environ, self.config()), patch.object(mac.subprocess, "run", return_value=subprocess.CompletedProcess([], 0)), patch.object(mac, "run") as run:
                    run.side_effect = [subprocess.CompletedProcess([], 0, stdout='"login.keychain-db"'), None,
                                       subprocess.CalledProcessError(1, ["cargo"]), None]
                    with self.assertRaises(subprocess.CalledProcessError):
                        mac.main()
                    self.assertEqual(run.call_args.args, ("security", "list-keychains", "-d", "user", "-s", "login.keychain-db"))
                    self.assertFalse(output.exists())
            finally:
                os.chdir(old)
