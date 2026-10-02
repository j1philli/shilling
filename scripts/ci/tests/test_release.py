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
            **{f"release-input/clients/{target}/Shilling_0.1.2_{target}.zip": target for target in release.TARGETS[2:]},
        }.items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(value)
        self.patchers = [
            patch.object(release, "ROOT", self.root),
            patch.object(release, "run", side_effect=self.fake_run),
            patch.object(release, "github_get", side_effect=self.github_get),
            patch.object(release, "load_candidate", return_value={"build_number": "42", "commit": COMMIT}),
            patch.object(release, "AppleAPI"),
            patch.object(release, "approval", return_value={"apple_build_id": "apple-42"}),
            patch.object(release, "promote"),
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
        self.assertEqual(list(manifest["assets"]), ["Shilling_0.1.2_android.zip"])

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
        (self.root / "release-input/clients/ios/Shilling_0.1.2_ios.zip").unlink()
        with self.assertRaisesRegex(ValueError, "Missing tested ios"):
            release.main()
        self.assertFalse(any(c[0] == "gh" for c in self.commands()))

    def test_stale_desktop_package_blocks_all_publication(self):
        for target, filename in (
            ("windows", "Shilling_0.1.0_x64-setup.exe"),
            ("linux", "Shilling-0.1.0-1.x86_64.rpm"),
            ("macos", "Shilling_0.1.0_universal.dmg"),
        ):
            with self.subTest(target=target):
                stale = self.root / "release-input/clients" / target / filename
                stale.write_text("stale package")
                with self.assertRaisesRegex(ValueError, "Unexpected .* package"):
                    release.main()
                self.assertFalse(any(c[0] in ("gh", "bash") for c in self.commands()))
                stale.unlink()

    def test_current_rpm_filename_is_accepted(self):
        rpm = self.root / "release-input/clients/linux/Shilling-0.1.2-1.x86_64.rpm"
        rpm.write_text("current rpm")
        release.main()
        self.assertTrue(self.published())

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

    def test_server_deploy_precedes_web_and_release_publication(self):
        release.main()
        commands = self.commands()
        server_i = next(i for i,c in enumerate(commands) if c == ("python3", "scripts/ci/deploy-coolify.py"))
        web_i = next(i for i,c in enumerate(commands) if "scripts/ci/deploy-hosted-web.sh" in c)
        self.assertLess(server_i, web_i)
        self.assertEqual(commands[-1][:3], ("gh", "release", "edit"))

    def test_web_only_does_not_touch_coolify(self):
        os.environ.update(SHILLING_RELEASE_MODE="single", SHILLING_RELEASE_TARGET="web")
        release.main()
        self.assertFalse(any("scripts/ci/deploy-coolify.py" in c for c in self.commands()))

    def test_server_deployment_failure_blocks_web_and_publication(self):
        original = self.fake_run
        def fail_deploy(*args, **kwargs):
            if args == ("python3", "scripts/ci/deploy-coolify.py"):
                raise subprocess.CalledProcessError(1, args)
            return original(*args, **kwargs)
        with patch.object(release, "run", side_effect=fail_deploy):
            with self.assertRaises(subprocess.CalledProcessError):
                release.main()
        self.assertFalse(self.published())
        self.assertFalse(any("scripts/ci/deploy-hosted-web.sh" in c for c in self.commands()))

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

    def test_pending_apple_approval_blocks_every_remote_mutation(self):
        with patch.object(release, "approval", side_effect=ValueError("iOS approval pending")):
            with self.assertRaisesRegex(ValueError, "approval pending"):
                release.main()
        self.assertFalse(any(c[0] in ("gh", "bash", "docker") for c in self.commands()))
        release.promote.assert_not_called()

    def test_preview_reports_pending_approval_without_promoting(self):
        os.environ["SHILLING_RELEASE_DRY_RUN"] = "1"
        with patch.object(release, "approval", side_effect=ValueError("iOS approval pending")):
            release.main()
        release.promote.assert_not_called()
        self.assertFalse(self.published())

    def test_ios_only_promotes_before_github_publication(self):
        os.environ.update(SHILLING_RELEASE_MODE="single", SHILLING_RELEASE_TARGET="ios")
        def promote(*args):
            self.assertFalse(self.published())
        release.promote.side_effect = promote
        release.main()
        release.promote.assert_called_once()
        self.assertTrue(self.published())
        self.assertFalse(any("publish-release-images.sh" in " ".join(c) for c in self.commands()))

    def test_apple_promotion_failure_keeps_github_draft(self):
        release.promote.side_effect = RuntimeError("Apple unavailable")
        with self.assertRaisesRegex(RuntimeError, "Apple unavailable"):
            release.main()
        self.assertFalse(self.published())

    def test_non_ios_release_never_contacts_apple(self):
        os.environ.update(SHILLING_RELEASE_MODE="single", SHILLING_RELEASE_TARGET="web")
        release.main()
        release.AppleAPI.assert_not_called()
        release.approval.assert_not_called()
        release.promote.assert_not_called()

    def test_invalid_target_is_rejected(self):
        with self.assertRaises(ValueError):
            release.selected_targets("single", "all")


if __name__ == "__main__":
    unittest.main()
