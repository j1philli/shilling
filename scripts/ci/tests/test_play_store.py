"""Exercise Play edits without contacting Google or publishing real releases."""
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import play_store as play
import android_provenance as provenance

CANDIDATE = dict(version='0.1.1', version_code='42', sha256='a'*64, bundle='Shilling_0.1.1_android-release.aab')


def release(code='42', status='completed'):
    return {'versionCodes': [code], 'status': status}


class FakeAPI:
    def __init__(self):
        self.calls = []
        self.bundles = [{'versionCode': 42, 'sha256': 'a'*64}]
        self.tracks = {'internal': {'releases': [release()]}, 'production': {'releases': []}}
        self.fail_commit = False

    def request(self, method, path, body=None, bundle=None):
        self.calls.append((method, path, body, bundle))
        if path == '/edits':
            return {'id': 'edit-1'}
        if path.endswith('/bundles'):
            return {'bundles': self.bundles}
        if '?uploadType=' in path:
            return {'versionCode': 42, 'sha256': 'a'*64}
        if method == 'GET' and '/tracks/' in path:
            return copy.deepcopy(self.tracks[path.split('/')[-1]])
        if path.endswith(':commit') and self.fail_commit:
            raise RuntimeError('network timeout')
        return {}


class PlayTests(unittest.TestCase):
    def setUp(self):
        self.api = FakeAPI()
        self.temp = tempfile.TemporaryDirectory()
        self.output = Path(self.temp.name)/'receipt.json'

    def tearDown(self):
        self.temp.cleanup()

    def mutations(self):
        return [c for c in self.api.calls if c[0] == 'PUT' or c[1].endswith(':commit') or c[3]]

    def test_readiness_does_not_upload_update_or_commit(self):
        self.assertTrue(play.readiness(self.api, CANDIDATE)['ready'])
        self.assertFalse(self.mutations())
        self.assertEqual(self.api.calls[-1][:2], ('DELETE', '/edits/edit-1'))

    def test_wrong_bundle_hash_blocks_promotion(self):
        self.api.bundles[0]['sha256'] = 'b'*64
        with self.assertRaisesRegex(ValueError, 'hash differs'):
            play.promote(self.api, CANDIDATE, self.output)
        self.assertFalse(self.mutations())
        self.assertFalse(self.output.exists())

    def test_absent_bundle_cannot_promote(self):
        self.api.bundles = []
        with self.assertRaisesRegex(ValueError, 'not uploaded'):
            play.promote(self.api, CANDIDATE, self.output)
        self.assertFalse(self.mutations())

    def test_unreleased_internal_candidate_blocks_promotion(self):
        self.api.tracks['internal']['releases'] = [release(status='draft')]
        with self.assertRaisesRegex(ValueError, 'not active'):
            play.promote(self.api, CANDIDATE, self.output)
        self.assertFalse(self.mutations())

    def test_different_internal_candidate_blocks_promotion(self):
        self.api.tracks['internal']['releases'] = [release('43')]
        with self.assertRaisesRegex(ValueError, 'not active'):
            play.promote(self.api, CANDIDATE, self.output)
        self.assertFalse(self.mutations())

    def test_production_reuses_bundle_and_never_uploads(self):
        play.promote(self.api, CANDIDATE, self.output)
        put = next(c for c in self.api.calls if c[0] == 'PUT')
        self.assertTrue(put[1].endswith('/tracks/production'))
        self.assertEqual(put[2]['releases'][0]['versionCodes'], ['42'])
        self.assertEqual(put[2]['releases'][0]['status'], 'completed')
        self.assertFalse(any(c[3] for c in self.api.calls))
        self.assertEqual(json.loads(self.output.read_text())['sha256'], CANDIDATE['sha256'])

    def test_already_promoted_is_idempotent_even_after_internal_advances(self):
        self.api.tracks['production']['releases'] = [release()]
        self.api.tracks['internal']['releases'] = [release('43')]
        play.promote(self.api, CANDIDATE, self.output)
        self.assertFalse(self.mutations())
        self.assertTrue(self.output.exists())

    def test_newer_production_blocks_downgrade(self):
        self.api.tracks['production']['releases'] = [release('43')]
        with self.assertRaisesRegex(ValueError, 'downgrade'):
            play.promote(self.api, CANDIDATE, self.output)
        self.assertFalse(self.mutations())

    def test_existing_rollout_is_not_overwritten(self):
        for status in ('draft', 'inProgress', 'halted'):
            self.api.tracks['production']['releases'] = [release('41', status)]
            with self.assertRaisesRegex(ValueError, 'existing production'):
                play.promote(self.api, CANDIDATE, self.output)
        self.assertFalse(self.mutations())

    def test_commit_failure_does_not_write_success_receipt(self):
        self.api.fail_commit = True
        with self.assertRaisesRegex(RuntimeError, 'timeout'):
            play.promote(self.api, CANDIDATE, self.output)
        self.assertFalse(self.output.exists())
        self.assertEqual(self.api.calls[-1][0], 'DELETE')

    def test_internal_retry_reuses_existing_version(self):
        play.upload(self.api, CANDIDATE, self.temp.name, self.output)
        self.assertFalse(self.mutations())
        self.assertTrue(self.output.exists())

    def test_internal_first_upload_validates_then_commits(self):
        self.api.bundles = []
        self.api.tracks['internal']['releases'] = []
        play.upload(self.api, CANDIDATE, self.temp.name, self.output)
        paths = [c[1] for c in self.api.calls]
        self.assertLess(paths.index('/edits/edit-1:validate'), paths.index('/edits/edit-1:commit'))
        self.assertEqual(sum(bool(c[3]) for c in self.api.calls), 1)

    def test_internal_does_not_move_backwards(self):
        self.api.tracks['internal']['releases'] = [release('43')]
        with self.assertRaisesRegex(ValueError, 'backwards'):
            play.upload(self.api, CANDIDATE, self.temp.name, self.output)
        self.assertFalse(self.mutations())


class ProvenanceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.directory = Path(self.temp.name)
        self.bundle = self.directory / CANDIDATE['bundle']
        self.bundle.write_bytes(b'original bundle')
        self.info = {'package': provenance.PACKAGE, 'version': '0.1.1', 'version_code': '42', 'target_sdk': 36}
        self.patch = patch.object(provenance, 'bundle_info', return_value=self.info)
        self.patch.start()
        self.candidate = provenance.record(self.bundle, '0.1.1', 'a'*40, '42', True)
        provenance.write_record(self.directory, self.candidate)

    def tearDown(self):
        self.patch.stop()
        self.temp.cleanup()

    def test_matching_signed_candidate(self):
        self.assertEqual(provenance.load_candidate(self.directory, '0.1.1', 'a'*40), self.candidate)

    def test_modified_bundle_rejected(self):
        self.bundle.write_bytes(b'different bundle')
        with self.assertRaisesRegex(ValueError, 'provenance/hash'):
            provenance.load_candidate(self.directory, '0.1.1', 'a'*40)

    def test_wrong_commit_rejected(self):
        with self.assertRaisesRegex(ValueError, 'provenance/hash'):
            provenance.load_candidate(self.directory, '0.1.1', 'b'*40)

    def test_wrong_package_build_number_and_target_sdk_rejected(self):
        for field, value in [('package', 'another.app'), ('version_code', '43'), ('target_sdk', 35)]:
            with self.subTest(field=field), patch.dict(self.info, {field: value}):
                with self.assertRaises(ValueError):
                    provenance.load_candidate(self.directory, '0.1.1', 'a'*40)


if __name__ == '__main__':
    unittest.main()
