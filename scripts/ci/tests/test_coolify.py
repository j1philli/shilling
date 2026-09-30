import copy
import importlib.util
import os
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('coolify_release', Path(__file__).resolve().parents[1] / 'deploy-coolify.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
DIGEST = 'sha256:' + 'a' * 64


class CoolifyTests(unittest.TestCase):
    def setUp(self):
        self.calls = []
        self.app = {'uuid': 'new', 'build_pack': 'dockerimage', 'status': 'exited:unhealthy',
                    'docker_registry_image_name': module.IMAGE, 'docker_registry_image_tag': 'previous',
                    'settings': {'is_auto_deploy_enabled': False}, 'health_check_enabled': True,
                    'health_check_path': '/health', 'ports_exposes': '8081', 'fqdn': 'http://api.shilling.finance'}
        self.legacy = {'uuid': 'old', 'status': 'running:healthy', 'settings': {'is_auto_deploy_enabled': False}}
        self.env_patch = patch.dict(os.environ, {'COOLIFY_URL': 'http://coolify.test', 'COOLIFY_TOKEN': 'test',
            'COOLIFY_APP_UUID': 'new', 'COOLIFY_LEGACY_APP_UUID': 'old'})
        self.env_patch.start()
        self.client = module.Coolify()
        self.api_patch = patch.object(self.client, 'api', side_effect=self.api)
        self.api_patch.start()
        self.public_patch = patch.object(self.client, 'verify_public')
        self.public = self.public_patch.start()
        self.wait_patch = patch.object(self.client, 'wait_deployment', side_effect=self.finish)
        self.wait = self.wait_patch.start()

    def tearDown(self):
        for p in (self.wait_patch, self.public_patch, self.api_patch, self.env_patch):
            p.stop()

    def finish(self, uuid, app_uuid=None):
        self.calls.append(('finished', uuid))
        (self.legacy if app_uuid == 'old' else self.app)['status'] = 'running:healthy'

    def api(self, path, method='GET', data=None):
        self.calls.append((method, path, data))
        if path == 'applications/new':
            if data:
                self.app.update({k:v for k,v in data.items() if k.startswith('docker_registry_')})
            return copy.deepcopy(self.app)
        if path == 'applications/old':
            return copy.deepcopy(self.legacy)
        if '/envs' in path:
            # The real API masks values when the token lacks read:sensitive.
            return [{'key': key, 'is_runtime': True} for key in ('SHILLING_AUTH_MODE', 'SHILLING_SUPABASE_URL',
                'SHILLING_SUPABASE_ANON_KEY', 'SHILLING_SUPABASE_SERVICE_KEY')]
        if path.startswith('deployments/applications/'):
            return {'deployments': []}
        if path == 'deploy' and method == 'POST' and data == {'uuid': 'new'}:
            return {'deployments': [{'deployment_uuid': 'deployment'}]}
        if path == 'applications/old/stop':
            self.legacy['status'] = 'exited:unhealthy'
            return {}
        if path == 'applications/new/stop':
            self.app['status'] = 'exited:unhealthy'
            return {}
        if path == 'applications/old/restart':
            return {'deployment_uuid': 'restore-old'}
        raise AssertionError(path)

    def test_preflight_is_read_only_and_accepts_masked_values(self):
        self.client.check()
        self.assertTrue(all(c[0] == 'GET' for c in self.calls))

    def test_first_release_pins_digest_and_stops_legacy_only_after_healthy(self):
        evidence = self.client.deploy(DIGEST, 'v0.1.1')
        self.assertEqual(evidence['image'], module.IMAGE + '@' + DIGEST)
        self.assertEqual(self.app['docker_registry_image_tag'], 'a' * 64)
        self.assertEqual(self.app['docker_registry_image_name'], module.IMAGE + '@sha256')
        stop = next(i for i,c in enumerate(self.calls) if c[:2] == ('POST', 'applications/old/stop'))
        finished = self.calls.index(('finished', 'deployment'))
        self.assertLess(finished, stop)
        self.public.assert_called_once()

    def test_failed_rollout_does_not_stop_legacy(self):
        self.wait.side_effect = module.RolloutFailed('deployment failed')
        with self.assertRaisesRegex(RuntimeError, 'deployment failed'):
            self.client.deploy(DIGEST, 'v0.1.1')
        self.assertFalse(any(c[:2] == ('POST', 'applications/old/stop') for c in self.calls))
        self.assertEqual(self.app['docker_registry_image_tag'], 'previous')

    def test_public_check_failure_restores_legacy(self):
        self.public.side_effect = [RuntimeError('public failed'), None]
        with self.assertRaisesRegex(RuntimeError, 'restored the legacy server'):
            self.client.deploy(DIGEST, 'v0.1.1')
        self.assertEqual(self.legacy['status'], 'running:healthy')
        self.assertEqual(self.app['status'], 'exited:unhealthy')

    def test_later_public_failure_restores_previous_image(self):
        self.legacy['status'] = 'exited:unhealthy'
        self.app['status'] = 'running:healthy'
        self.public.side_effect = [RuntimeError('public failed'), None]
        with self.assertRaisesRegex(RuntimeError, 'restored the previous image'):
            self.client.deploy(DIGEST, 'v0.1.1')
        self.assertEqual(self.app['docker_registry_image_tag'], 'previous')
        self.assertFalse(any(c[:2] == ('POST', 'applications/old/restart') for c in self.calls))

    def test_invalid_digest_makes_no_api_calls(self):
        with self.assertRaises(ValueError):
            self.client.deploy('latest', 'v0.1.1')
        self.assertEqual(self.calls, [])

    def test_auto_deployment_blocks_release(self):
        self.app['settings']['is_auto_deploy_enabled'] = True
        with self.assertRaisesRegex(ValueError, 'automatic Git'):
            self.client.check()

    def test_public_checks_require_plaintext_health_and_supabase(self):
        self.public_patch.stop()
        with patch.object(self.client, 'public_response', side_effect=['ok', '{"authMode":"SUPABASE"}']):
            self.client.verify_public()
        with patch.object(self.client, 'public_response', side_effect=['ok', '{"authMode":"NONE"}'] * 12), patch.object(module.time, 'sleep'):
            with self.assertRaisesRegex(RuntimeError, 'Supabase checks failed'):
                self.client.verify_public()

    def test_deployment_poll_rejects_failed_status(self):
        self.wait_patch.stop()
        with patch.object(self.client, 'api', return_value={'status': 'failed'}):
            with self.assertRaisesRegex(RuntimeError, 'ended with failed'):
                self.client.wait_deployment('failed-deployment')


if __name__ == '__main__':
    unittest.main()
