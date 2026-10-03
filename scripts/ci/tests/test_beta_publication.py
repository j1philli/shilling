"""Target routing, real Git fingerprints and delivery checkpoint regressions."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
import urllib.parse

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import publish_beta as beta
import target_inputs as targets


class TargetRulesTest(unittest.TestCase):
    def test_dependency_routing(self):
        policy = json.loads(targets.POLICY.read_text())
        cases = {
            "app/ios-app/ShillingWidgetExtension/Widget.swift": {"ios"},
            "app/ios-platform/src/IosPlatformServices.kt": {"ios"},
            "app/android-app/src/MainActivity.kt": {"android"},
            "app/shared/src@android/Driver.kt": {"android"},
            "app/shared/src@nonJvm/Sync.kt": set(policy["groups"]["clients"]),
            "app/shared/src/commonMain/data/Repository.kt": set(policy["groups"]["clients"]),
            "app/shared/src/commonMain/sqldelight/schema.sq": set(policy["groups"]["clients"]),
            "app/shared/src@wasmJs/Driver.kt": set(policy["groups"]["webBundle"]),
            "app/shared-ui/src/commonMain/Screen.kt": set(policy["groups"]["compose"]),
            "app/shared-ui/src@nativeBilling/Billing.kt": {"android"},
            "app/web-app/index.html": set(policy["groups"]["webBundle"]),
            "src-tauri/src/main.rs": set(policy["groups"]["desktop"]),
            "scripts/ci/build-desktop-macos.sh": {"macos"},
            "scripts/ci/build-ios.sh": {"ios"},
            "server/src/Server.kt": {"server"},
            "core/src/commonMain/Model.kt": set(targets.TARGETS),
            "VERSION": set(targets.TARGETS),
            "libs.versions.toml": set(targets.TARGETS),
            ".teamcity/settings.kts": set(targets.TARGETS),
            "third_party/sqldelight-kotlin-toolchain/plugin.kt": set(targets.TARGETS),
            "new-module/src/Unknown.kt": set(targets.TARGETS),
            "docs/releases.mdx": set(),
            "scripts/ci/tests/test_release.py": set(),
            "app/shared/src/commonTest/Test.kt": set(),
            "app/shared/src/commonMain/test/Fixture.kt": set(policy["groups"]["clients"]),
            "core/test@jvm/Test.kt": set(),
            "app/perf-ios/src/App.swift": set(),
        }
        for path, expected in cases.items():
            with self.subTest(path=path):
                self.assertEqual(targets.affected_targets(path, policy), expected)

    def test_unknown_target_in_policy_fails_instead_of_excluding_inputs(self):
        with self.assertRaisesRegex(ValueError, "Unknown target/group"):
            targets.affected_targets("source", {"groups": {}, "rules": [{"paths": ["*"], "targets": ["typo"]}]})


class FingerprintTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.git("init", "-q")
        self.git("config", "user.name", "CI Test")
        self.git("config", "user.email", "ci@example.invalid")
        self.git("config", "commit.gpgsign", "false")
        self.git("config", "core.hooksPath", "/dev/null")
        self.write("app/ios-app/src/App.swift", "app")
        self.write("app/android-app/src/App.kt", "app")
        self.commit()

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.repo, stderr=subprocess.STDOUT)

    def write(self, path, text):
        file = self.repo / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(text)

    def commit(self):
        self.git("add", ".")
        self.git("commit", "-qm", "test")

    def fingerprint(self):
        return targets.snapshot("ios", self.repo)["fingerprint"]

    def test_unrelated_commits_and_generated_worktree_changes_do_not_publish_ios(self):
        before = self.fingerprint()
        self.write("app/android-app/src/App.kt", "changed")
        self.write("docs/guide.md", "docs")
        self.commit()
        self.assertEqual(before, self.fingerprint())
        self.write("app/ios-app/src/App.swift", "uncommitted build number")
        self.assertEqual(before, self.fingerprint())

    def test_shared_change_rename_delete_mode_and_revert(self):
        initial = self.fingerprint()
        self.write("app/shared/src/commonMain/Model.kt", "shared")
        self.commit()
        added = self.fingerprint()
        self.assertNotEqual(initial, added)
        self.git("mv", "app/shared/src/commonMain/Model.kt", "app/shared/src/commonMain/Renamed.kt")
        self.commit()
        renamed = self.fingerprint()
        self.assertNotEqual(added, renamed)
        (self.repo / "app/shared/src/commonMain/Renamed.kt").chmod(0o755)
        self.commit()
        self.assertNotEqual(renamed, self.fingerprint())
        self.git("rm", "app/shared/src/commonMain/Renamed.kt")
        self.commit()
        self.assertEqual(initial, self.fingerprint())


class PublicationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.output = Path(self.temp.name)
        self.current = {"commit": "a" * 40, "fingerprint": "b" * 64, "inputs": {"source": "blob"}}

    def run_gate(self, previous=None, current=None, force=False):
        return beta.publish("ios", "testflight", current or self.current, previous,
                            ["publish-command"], self.output, force)

    def state(self):
        return json.loads((self.output / beta.STATE_ARTIFACT).read_text())

    @patch.object(beta.subprocess, "run")
    def test_first_delivery_then_repeated_unrelated_merges(self, run):
        self.assertEqual(self.run_gate()["action"], "publish")
        run.assert_called_once_with(["publish-command"], check=True)
        run.reset_mock()
        for commit in ("c" * 40, "d" * 40):
            result = self.run_gate(self.state(), dict(self.current, commit=commit))
            self.assertEqual(result["action"], "skip")
            self.assertEqual(self.state()["published"]["commit"], "a" * 40)
        run.assert_not_called()

    @patch.object(beta.subprocess, "run")
    def test_failed_delivery_keeps_changes_pending_and_removes_stale_state(self, run):
        self.run_gate()
        previous = self.state()
        changed = dict(self.current, commit="c" * 40, fingerprint="d" * 64, inputs={"new": "blob"})
        run.side_effect = subprocess.CalledProcessError(1, "publish-command")
        with self.assertRaises(subprocess.CalledProcessError):
            self.run_gate(previous, changed)
        self.assertFalse((self.output / beta.STATE_ARTIFACT).exists())
        run.side_effect = None
        # Next main merge has the same pending change plus an unrelated commit.
        result = self.run_gate(previous, dict(changed, commit="e" * 40))
        self.assertEqual(result["action"], "publish")
        self.assertEqual(result["changed_inputs"], ["new", "source"])

    @patch.object(beta.subprocess, "run")
    def test_force_and_wrong_channel(self, run):
        self.run_gate()
        previous = self.state()
        self.assertEqual(self.run_gate(previous, force=True)["reason"], "forced")
        with self.assertRaisesRegex(ValueError, "baseline"):
            self.run_gate(dict(previous, channel="play"))

    @patch.object(beta.subprocess, "run")
    def test_corrupt_state_never_silently_skips(self, run):
        with self.assertRaises(ValueError):
            self.run_gate({"schema": 1, "target": "ios", "channel": "testflight", "published": {}})
        run.assert_not_called()

    @patch.dict(os.environ, {"BUILD_VCS_BRANCH": "feature"})
    @patch.object(beta, "TeamCity")
    @patch.object(sys, "argv", ["publish_beta.py", "--target", "ios", "--channel", "testflight", "--", "publish"])
    def test_branch_guard_before_baseline_or_publish(self, api):
        with self.assertRaisesRegex(ValueError, "main"):
            beta.main()
        api.assert_not_called()


class BaselineTest(unittest.TestCase):
    def setUp(self):
        with patch.dict(os.environ, {"BETA_TEAMCITY_URL": "https://ci.example.invalid",
                                    "BETA_TEAMCITY_USER": "build", "BETA_TEAMCITY_PASSWORD": "secret"}):
            self.api = beta.TeamCity()

    def test_latest_successful_default_branch_build_including_skips(self):
        with patch.object(self.api, "get", side_effect=[{"build": [{"id": 123, "branchName": "main"}]}, {"state": 1}]) as get:
            self.assertEqual(self.api.previous_state("IosTestFlight"), {"state": 1})
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(get.call_args_list[0].args[0]).query)
        self.assertIn("state:finished,status:SUCCESS,branch:(default:true),personal:false,count:1", query["locator"][0])
        self.assertEqual(get.call_args_list[1].args[0], "builds/id:123/artifacts/content/beta-state.json")

    def test_empty_history(self):
        with patch.object(self.api, "get", return_value={"count": 0}):
            self.assertIsNone(self.api.previous_state("NewTrack"))

    def test_default_branch_can_have_no_logical_name(self):
        with patch.object(self.api, "get", side_effect=[{"build": [{"id": 123}]}, {"state": 1}]):
            self.assertEqual(self.api.previous_state("IosTestFlight"), {"state": 1})

    def test_only_missing_artifacts_allow_bootstrap(self):
        for status in (401, 403, 404, 500):
            error = urllib.error.HTTPError("url", status, "error", {}, None)
            self.addCleanup(error.close)
            with patch.object(beta.urllib.request, "urlopen", side_effect=error):
                if status == 404:
                    self.assertIsNone(self.api.get("builds/id:123/artifacts/content/beta-state.json"))
                else:
                    with self.assertRaises(RuntimeError):
                        self.api.get("builds/id:123/artifacts/content/beta-state.json")
                with self.assertRaises(RuntimeError):
                    self.api.get("builds?locator=test")


if __name__ == "__main__":
    unittest.main()
