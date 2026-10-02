"""Apple promotion and provenance regression checks; no network or publishing."""
import copy
import json
import os
from pathlib import Path
import plistlib
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import apple_store as apple
import ios_provenance as provenance

COMMIT = "a" * 40
CANDIDATE = {"version": "0.1.2", "build_number": "123", "commit": COMMIT,
             "ipa": "Shilling_0.1.2_ios.ipa", "sha256": "hash"}
BUILD = {"id": "build-123", "attributes": {"processingState": "VALID", "buildAudienceType": "APP_STORE_ELIGIBLE", "expired": False}}
VERSION = {"id": "version-id", "attributes": {"appVersionState": "PENDING_DEVELOPER_RELEASE", "releaseType": "MANUAL"}}


class ProvenanceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.ipa = self.root / CANDIDATE["ipa"]
        self.info = {"CFBundleIdentifier": provenance.BUNDLE_ID, "CFBundleShortVersionString": "0.1.2", "CFBundleVersion": "123"}
        self.write_ipa()
        self.candidate = provenance.record(self.ipa, "0.1.2", COMMIT, "123")
        self.path = self.root / "Shilling_0.1.2_ios-build.json"
        self.path.write_text(json.dumps(self.candidate))

    def write_ipa(self, widget_build="123"):
        with zipfile.ZipFile(self.ipa, "w") as z:
            z.writestr("Payload/app.app/Info.plist", plistlib.dumps(self.info))
            z.writestr("Payload/app.app/PlugIns/widget.appex/Info.plist", plistlib.dumps(dict(self.info, CFBundleVersion=widget_build)))

    def test_matching_ipa_passes(self):
        self.assertEqual(provenance.load_candidate(self.root, "0.1.2", COMMIT), self.candidate)

    def test_other_commit_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "provenance/hash"):
            provenance.load_candidate(self.root, "0.1.2", "b" * 40)

    def test_modified_ipa_is_rejected(self):
        self.info["CFBundleName"] = "changed"
        self.write_ipa()
        with self.assertRaisesRegex(ValueError, "provenance/hash"):
            provenance.load_candidate(self.root, "0.1.2", COMMIT)

    def test_mismatched_widget_is_rejected(self):
        self.write_ipa(widget_build="122")
        with self.assertRaisesRegex(ValueError, "extension versions"):
            provenance.load_candidate(self.root, "0.1.2", COMMIT)

    def test_path_traversal_is_rejected(self):
        self.candidate["ipa"] = "../other.ipa"
        self.path.write_text(json.dumps(self.candidate))
        with self.assertRaisesRegex(ValueError, "filename"):
            provenance.load_candidate(self.root, "0.1.2", COMMIT)

    def test_missing_provenance_is_rejected(self):
        self.path.unlink()
        with self.assertRaisesRegex(ValueError, "Missing iOS build provenance"):
            provenance.load_candidate(self.root, "0.1.2", COMMIT)


class AppleTests(unittest.TestCase):
    def setUp(self):
        self.api = Mock(app_id="app-id")
        self.build = copy.deepcopy(BUILD)
        self.version = copy.deepcopy(VERSION)
        self.find = patch.object(apple, "find_build", return_value=self.build).start()
        self.store = patch.object(apple, "store_version", return_value=self.version).start()
        self.addCleanup(patch.stopall)
        self.api.request.return_value = {"data": {"type": "builds", "id": "build-123"}}
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.output = Path(self.temp.name) / "receipt.json"
        patch.dict(os.environ, {"BUILD_VCS_BRANCH": "main", "ASC_INTERNAL_GROUP_ID": "group-id", "ASC_ENCRYPTION_REFERENCE_BUILD_ID": "reference"}).start()

    def test_pending_review_blocks_promotion(self):
        self.version["attributes"]["appVersionState"] = "WAITING_FOR_REVIEW"
        with self.assertRaisesRegex(ValueError, "approval pending"):
            apple.approval(self.api, CANDIDATE)
        self.assertFalse(any(c.args[0] != "GET" for c in self.api.request.call_args_list))

    def test_other_approved_build_is_rejected(self):
        self.api.request.return_value = {"data": {"id": "other-build"}}
        with self.assertRaisesRegex(ValueError, "does not match"):
            apple.approval(self.api, CANDIDATE)

    def test_internal_only_upload_is_rejected(self):
        self.build["attributes"]["buildAudienceType"] = "INTERNAL_ONLY"
        with self.assertRaisesRegex(ValueError, "not eligible"):
            apple.approval(self.api, CANDIDATE)

    def test_automatic_release_is_rejected(self):
        self.version["attributes"]["releaseType"] = "AFTER_APPROVAL"
        with self.assertRaisesRegex(ValueError, "manual release"):
            apple.approval(self.api, CANDIDATE)

    def test_approved_exact_build_is_released(self):
        approved = apple.approval(self.api, CANDIDATE)
        apple.promote(self.api, CANDIDATE, approved, self.output)
        posts = [c for c in self.api.request.call_args_list if c.args[0] == "POST"]
        self.assertEqual(len(posts), 1)
        self.assertEqual(posts[0].args[1], "/v1/appStoreVersionReleaseRequests")
        self.assertEqual(json.loads(self.output.read_text())["state"], "RELEASE_REQUESTED")

    def test_retry_does_not_release_already_released_build_again(self):
        approved = apple.approval(self.api, CANDIDATE)
        self.version["attributes"]["appVersionState"] = "READY_FOR_DISTRIBUTION"
        apple.promote(self.api, CANDIDATE, approved, self.output)
        self.assertFalse(any(c.args[0] == "POST" for c in self.api.request.call_args_list))

    def test_changed_selection_after_preflight_is_rejected(self):
        approved = apple.approval(self.api, CANDIDATE)
        approved["apple_build_id"] = "other"
        with self.assertRaisesRegex(ValueError, "changed after preflight"):
            apple.promote(self.api, CANDIDATE, approved, self.output)
        self.assertFalse(any(c.args[0] == "POST" for c in self.api.request.call_args_list))

    def test_expired_or_processing_build_is_rejected(self):
        for attrs in ({"expired": True}, {"processingState": "PROCESSING"}):
            with self.subTest(attrs=attrs):
                build = copy.deepcopy(BUILD)
                build["attributes"].update(attrs)
                with self.assertRaisesRegex(ValueError, "invalid, expired"):
                    apple.valid_build(build)

    def test_feature_branch_cannot_upload(self):
        os.environ["BUILD_VCS_BRANCH"] = "feature"
        with self.assertRaisesRegex(ValueError, "must run on main"):
            apple.upload(self.api, CANDIDATE, self.temp.name, self.output)
        self.api.request.assert_not_called()

    def test_existing_upload_is_reused_without_altool(self):
        self.api.request.return_value = {"data": {"attributes": {"isInternalGroup": True, "hasAccessToAllBuilds": True}, "relationships": {"app": {"data": {"id": "app-id"}}}}}
        with patch.object(apple, "apply_saved_compliance"), patch.object(apple.subprocess, "run") as run:
            receipt = apple.upload(self.api, CANDIDATE, self.temp.name, self.output)
        run.assert_not_called()
        self.assertEqual(receipt["apple_build_id"], "build-123")

    def test_upload_refuses_external_group(self):
        self.api.request.return_value = {"data": {"attributes": {"isInternalGroup": False}}}
        with self.assertRaisesRegex(ValueError, "internal group"):
            apple.upload(self.api, CANDIDATE, self.temp.name, self.output)

    def test_saved_encryption_answer_is_reused(self):
        self.api.request.return_value = {"data": {"attributes": {"usesNonExemptEncryption": False}, "relationships": {"app": {"data": {"id": "app-id"}}, "appEncryptionDeclaration": {"data": None}}}}
        apple.apply_saved_compliance(self.api, self.build)
        self.assertEqual(self.api.request.call_args.args[2]["data"]["attributes"], {"usesNonExemptEncryption": False})

    def test_missing_encryption_answer_blocks_distribution(self):
        self.api.request.return_value = {"data": {"attributes": {"usesNonExemptEncryption": None}, "relationships": {"app": {"data": {"id": "app-id"}}, "appEncryptionDeclaration": {"data": None}}}}
        with self.assertRaisesRegex(ValueError, "no completed encryption answer"):
            apple.apply_saved_compliance(self.api, self.build)
        self.assertEqual(self.api.request.call_count, 1)

    def test_submit_uses_manual_release_and_exact_build(self):
        self.version["attributes"]["appVersionState"] = "PREPARE_FOR_SUBMISSION"
        self.api.list.side_effect = [[], []]
        self.api.request.side_effect = [{}, {"data": {"id": "submission-id"}}, {}, {}]
        apple.submit_review(self.api, CANDIDATE)
        patch_version = self.api.request.call_args_list[0].args[2]["data"]
        self.assertEqual(patch_version["attributes"]["releaseType"], "MANUAL")
        self.assertEqual(patch_version["relationships"]["build"]["data"]["id"], "build-123")
        self.assertEqual(self.api.request.call_args.args[2]["data"]["attributes"], {"submitted": True})

    def test_submit_does_not_include_unrelated_review_items(self):
        self.version["attributes"]["appVersionState"] = "PREPARE_FOR_SUBMISSION"
        self.api.list.side_effect = [[{"id": "submission-id"}], [{"relationships": {"appStoreVersion": {"data": {"id": "other"}}}}]]
        with self.assertRaisesRegex(ValueError, "includes other items"):
            apple.submit_review(self.api, CANDIDATE)
        self.assertFalse(any(c.args[1].startswith("/v1/reviewSubmissions/") and c.args[0] == "PATCH" for c in self.api.request.call_args_list))


if __name__ == "__main__":
    unittest.main()
