import copy
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import desktop_updates as updates
import desktop_artifacts as artifacts


def feed(build=10, version='0.1.1'):
    return {'version': f'{version}+build.{build}', 'signature': 'signed', 'sha256': 'a'*64,
            'url': f'https://github.com/j1philli/shilling/releases/download/desktop-linux-b{build}/app.AppImage',
            'commit': 'a'*40, 'build': str(build), 'notes': 'Test release'}


class Feeds(unittest.TestCase):
    def test_platform_publication_preserves_every_other_feed(self):
        state = updates.empty_state()
        result = updates.advance(state, 'linux', 'beta', feed())
        for path in state['feeds']:
            if path != 'beta/linux-x86_64.json':
                self.assertEqual(result['feeds'][path], state['feeds'][path])
        self.assertEqual(state, updates.empty_state())

    def test_mac_universal_updates_both_architectures(self):
        state = updates.advance(updates.empty_state(), 'macos', 'beta', feed())
        self.assertEqual(state['feeds']['beta/darwin-aarch64.json'], state['feeds']['beta/darwin-x86_64.json'])

    def test_numeric_build_order_does_not_downgrade(self):
        state = updates.advance(updates.empty_state(), 'linux', 'beta', feed(100))
        with self.assertRaisesRegex(ValueError, 'backwards'):
            updates.advance(state, 'linux', 'beta', feed(99))
        self.assertEqual(updates.advance(state, 'linux', 'beta', feed(100)), state)

    def test_same_version_different_artifact_is_rejected(self):
        state = updates.advance(updates.empty_state(), 'linux', 'beta', feed())
        wrong = dict(feed(), sha256='b'*64)
        with self.assertRaisesRegex(ValueError, 'different update contents'):
            updates.advance(state, 'linux', 'beta', wrong)

    def test_product_version_cannot_be_downgraded(self):
        state = updates.advance(updates.empty_state(), 'linux', 'stable', feed(10, '0.2.0'))
        with self.assertRaisesRegex(ValueError, 'backwards'):
            updates.advance(state, 'linux', 'stable', feed(999, '0.1.1'))

    def test_site_contains_all_feeds_no_spa_fallback_and_no_cache(self):
        with tempfile.TemporaryDirectory() as tmp:
            state = updates.empty_state()
            updates.write_site(tmp, state)
            for path, expected in state['feeds'].items():
                self.assertEqual(json.loads((Path(tmp)/path).read_text()), expected)
            self.assertTrue((Path(tmp)/'404.html').is_file())
            self.assertIn('no-store', (Path(tmp)/'_headers').read_text())

    def test_incomplete_or_foreign_state_fails_closed(self):
        state = updates.empty_state()
        del state['feeds']['stable/linux-x86_64.json']
        with self.assertRaises(ValueError): updates.validate_state(state)
        state = updates.advance(updates.empty_state(), 'linux', 'beta', dict(feed(), url='https://elsewhere.test/file'))
        with self.assertRaises(ValueError): updates.validate_state(state)

    def test_stable_requires_exact_candidate(self):
        candidate = {'update_version': '0.1.1+build.11', 'updater': 'app', 'assets': {'app': 'a'*64}}
        state = updates.advance(updates.empty_state(), 'linux', 'beta', feed(10))
        with patch.object(updates, 'state_from_host', return_value=state), patch.object(updates, 'load_candidate', return_value=candidate):
            with self.assertRaisesRegex(ValueError, 'exact published beta'):
                updates.prepare_stable(['linux'], '0.1.1', 'a'*40)

    def test_non_desktop_release_does_not_contact_host(self):
        with patch.object(updates, 'state_from_host') as request:
            self.assertEqual(updates.prepare_stable(['ios', 'android'], '0.1.1', 'a'*40), [])
            request.assert_not_called()

    def test_stable_rechecks_beta_and_preserves_other_channels(self):
        state = updates.advance(updates.empty_state(), 'linux', 'beta', feed())
        with patch.object(updates, 'state_from_host', return_value=state), patch.object(updates, 'deploy') as deploy:
            updates.publish_stable([('linux', feed())], 'a'*40)
            posted = deploy.call_args.args[0]
            self.assertEqual(posted['feeds']['stable/linux-x86_64.json'], feed())
            self.assertEqual(posted['feeds']['beta/linux-x86_64.json'], feed())
            with self.assertRaisesRegex(ValueError, 'changed'):
                updates.publish_stable([('linux', feed(9))], 'a'*40)
            self.assertEqual(deploy.call_count, 1)

    def test_foreign_tag_is_rejected_before_creating_release(self):
        candidate = dict(target='linux', build='10', commit='a'*40)
        with patch.dict(os.environ, {'GITHUB_TOKEN': 'test'}), patch.object(updates, 'run', return_value='b'*40 + '\trefs/tags/desktop-linux-b10\n'), patch.object(updates, 'existing_release') as release:
            with self.assertRaisesRegex(ValueError, 'another commit'):
                updates.ensure_assets(candidate, '/unused')
            release.assert_not_called()

    def test_feature_branch_cannot_publish(self):
        with patch.dict(os.environ, {'BUILD_VCS_BRANCH': 'feature'}), patch.object(sys, 'argv', ['desktop_updates.py', 'linux']):
            with self.assertRaisesRegex(ValueError, 'restricted to main'): updates.main()


class Provenance(unittest.TestCase):
    def test_tampered_or_wrong_source_artifact_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            name = 'Shilling_0.1.1_linux.AppImage'
            (root/name).write_bytes(b'tested package')
            candidate = dict(version='0.1.1', target='linux', commit='a'*40, build='10',
                update_version='0.1.1+build.10', updater=name, assets={name: artifacts.sha256(root/name)})
            (root/'Shilling_0.1.1_linux-build.json').write_text(json.dumps(candidate))
            self.assertEqual(artifacts.load_candidate(root, 'linux', '0.1.1', 'a'*40), candidate)
            with self.assertRaisesRegex(ValueError, 'source/target/version'):
                artifacts.load_candidate(root, 'linux', '0.1.1', 'b'*40)
            (root/name).write_bytes(b'tampered')
            with self.assertRaisesRegex(ValueError, 'hash mismatch'):
                artifacts.load_candidate(root, 'linux', '0.1.1', 'a'*40)


if __name__ == '__main__': unittest.main()
