"""Release control-flow checks: publishing is deliberately mocked, never networked."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("release", Path(__file__).resolve().parents[1] / "release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
COMMIT = "a" * 40


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.original = Path.cwd()
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.calls = []
        self.ref = None
        self.existing_release = None
        self.fail_images = False
        self.tag_commit = COMMIT
        for name, value in {
            "VERSION": "0.1.2\n", "web-app-dist/index.html": "hosted web", "web-app-dist/web-app.wasm": "wasm",
            "release-input/server/server-jvm-executable.jar": "jar", "deploy/self-host/compose.yaml": "services: {}",
            **{f"release-input/clients/{target}/{target}.zip": target for target in release.TARGETS[2:]},
        }.items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(value)
        self.patchers = [
            patch.object(release, "ROOT", self.root),
            patch.object(release, "run", side_effect=self.fake_run),
            patch.object(release, "github_get", side_effect=self.github_get),
            patch.dict(os.environ, {"SHILLING_RELEASE_MODE": "full", "SHILLING_RELEASE_DRY_RUN": "0",
                "BUILD_VCS_BRANCH": "main", "GITHUB_TOKEN": "test", "GHCR_TOKEN": "test",
                "CLOUDFLARE_ACCOUNT_ID": "test", "CLOUDFLARE_API_TOKEN": "test",
                "CLOUDFLARE_PAGES_PROJECT": "test"}, clear=True),
        ]
        for p in self.patchers:
            p.start()

    def tearDown(self):
        os.chdir(self.original)
        for p in reversed(self.patchers):
            p.stop()
        self.temp.cleanup()

    def fake_run(self, *args, capture=False, env=None):
        self.calls.append((args, env))
        if args[:2] == ("git", "rev-parse"):
            return (self.tag_commit if args[2] == "FETCH_HEAD^{commit}" else COMMIT) + "\n"
        if self.fail_images and "scripts/ci/publish-release-images.sh" in args:
            raise subprocess.CalledProcessError(1, args)
        return ""

    def github_get(self, endpoint):
        return self.ref if "/git/ref/" in endpoint else self.existing_release

    def commands(self):
        return [args for args, _ in self.calls]

    def published(self):
        return any(c[:3] == ("gh", "release", "edit") for c in self.commands())

    def test_full_release_publishes_every_target_and_deploys_web_last(self):
        release.main()
        manifest = json.loads((self.root / "release-output/release-manifest.json").read_text())
        self.assertEqual(manifest["targets"], list(release.TARGETS))
        self.assertIn("compose.yaml", manifest["assets"])
        commands = self.commands()
        image_i = next(i for i,c in enumerate(commands) if "scripts/ci/publish-release-images.sh" in c)
        web_i = next(i for i,c in enumerate(commands) if "scripts/ci/deploy-hosted-web.sh" in c)
        self.assertLess(image_i, web_i)
        self.assertEqual(commands[-1][:3], ("gh", "release", "edit"))
        self.assertEqual(self.calls[image_i][1]["SHILLING_RELEASE_TARGETS"], "server,web")
        self.assertEqual((self.root / "web-app-dist/index.html").read_text(), "hosted web")

    def test_single_client_never_publishes_images_or_deploys_web(self):
        os.environ.update(SHILLING_RELEASE_MODE="single", SHILLING_RELEASE_TARGET="android")
        release.main()
        self.assertTrue(self.published())
        self.assertFalse(any(c[0] == "bash" for c in self.commands()))
        manifest = json.loads((self.root / "release-output/release-manifest.json").read_text())
        self.assertEqual(list(manifest["assets"]), ["android.zip"])

    def test_single_server_never_deploys_web(self):
        os.environ.update(SHILLING_RELEASE_MODE="single", SHILLING_RELEASE_TARGET="server")
        release.main()
        self.assertFalse(any("scripts/ci/deploy-hosted-web.sh" in c for c in self.commands()))
        self.assertFalse((self.root / "release-output/compose.yaml").exists())

    def test_preview_makes_no_remote_changes(self):
        os.environ["SHILLING_RELEASE_DRY_RUN"] = "1"
        release.main()
        self.assertTrue((self.root / "release-output/release-manifest.json").is_file())
        self.assertFalse(any(c[0] in ("gh", "bash", "docker") for c in self.commands()))

    def test_missing_artifact_fails_before_any_publishing(self):
        (self.root / "release-input/clients/ios/ios.zip").unlink()
        with self.assertRaisesRegex(ValueError, "Missing tested ios"):
            release.main()
        self.assertFalse(any(c[0] == "gh" for c in self.commands()))

    def test_feature_branch_cannot_publish(self):
        os.environ["BUILD_VCS_BRANCH"] = "feature/test"
        with self.assertRaisesRegex(ValueError, "must run on main"):
            release.main()
        self.assertFalse(any(c[0] == "gh" for c in self.commands()))

    def test_other_target_release_cannot_be_overwritten(self):
        self.existing_release = {"body": "other selection", "draft": True}
        with self.assertRaisesRegex(ValueError, "another release selection"):
            release.main()
        self.assertFalse(any(c[0] == "gh" for c in self.commands()))

    def test_existing_completed_release_is_no_op(self):
        self.existing_release = {"body": f"<!-- shilling-release:{COMMIT}:{','.join(release.TARGETS)} -->", "draft": False}
        release.main()
        self.assertFalse(any(c[0] == "gh" for c in self.commands()))

    def test_failed_image_publish_leaves_draft_and_does_not_deploy(self):
        self.fail_images = True
        with self.assertRaises(subprocess.CalledProcessError):
            release.main()
        self.assertFalse(self.published())
        self.assertFalse(any("scripts/ci/deploy-hosted-web.sh" in c for c in self.commands()))

    def test_matching_draft_can_resume(self):
        self.ref = {"object": {"sha": COMMIT}}
        self.existing_release = {"body": f"<!-- shilling-release:{COMMIT}:{','.join(release.TARGETS)} -->", "draft": True}
        release.main()
        self.assertTrue(self.published())
        self.assertFalse(any(c[:3] == ("gh", "release", "create") for c in self.commands()))

    def test_existing_tag_cannot_be_moved_to_another_commit(self):
        self.ref = {"object": {"sha": "b" * 40}}
        self.tag_commit = "b" * 40
        with self.assertRaisesRegex(ValueError, "another commit"):
            release.main()
        self.assertFalse(any(c[0] == "gh" for c in self.commands()))

    def test_single_web_only_publishes_web_image_and_deploys(self):
        os.environ.update(SHILLING_RELEASE_MODE="single", SHILLING_RELEASE_TARGET="web")
        release.main()
        image_call = next(c for c in self.calls if "scripts/ci/publish-release-images.sh" in c[0])
        self.assertEqual(image_call[1]["SHILLING_RELEASE_TARGETS"], "web")
        self.assertTrue(any("scripts/ci/deploy-hosted-web.sh" in c for c in self.commands()))
        self.assertFalse((self.root / "release-output/compose.yaml").exists())

    def test_invalid_target_is_rejected(self):
        with self.assertRaises(ValueError):
            release.selected_targets("single", "all")


if __name__ == "__main__":
    unittest.main()
