#!/usr/bin/env python3
"""Promote the published server image digest and verify the hosted API."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

IMAGE = 'ghcr.io/j1philli/shilling-server'


class RolloutFailed(RuntimeError):
    pass


class Coolify:
    def __init__(self):
        self.base = os.environ['COOLIFY_URL'].rstrip('/')
        if not self.base.endswith('/api/v1'):
            self.base += '/api/v1'
        self.token = os.environ['COOLIFY_TOKEN']
        self.app = os.environ['COOLIFY_APP_UUID']
        self.legacy = os.environ.get('COOLIFY_LEGACY_APP_UUID', '')
        self.public = os.environ.get('SHILLING_SERVER_URL', 'https://api.shilling.finance').rstrip('/')

    def api(self, path, method='GET', data=None):
        request = urllib.request.Request(self.base + '/' + path,
            data=None if data is None else json.dumps(data).encode(), method=method,
            headers={'Authorization': 'Bearer ' + self.token, 'Content-Type': 'application/json'})
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            # API bodies/logs may contain environment values. Do not print them.
            raise RuntimeError(f'Coolify {method} {path} returned HTTP {error.code}') from None

    def application(self, uuid=None):
        return self.api('applications/' + (uuid or self.app))

    def busy(self, uuid):
        result = self.api('deployments/applications/' + uuid + '?take=20')
        deployments = result.get('deployments', []) if isinstance(result, dict) else result
        return any(d['status'] in ('queued', 'in_progress') for d in deployments)

    def check(self):
        app = self.application()
        if app['build_pack'] != 'dockerimage':
            raise ValueError('Coolify release app must use Docker Image')
        if app.get('settings', {}).get('is_auto_deploy_enabled'):
            raise ValueError('Disable Coolify automatic Git deployments before releasing')
        if not app.get('health_check_enabled') or app.get('health_check_path') != '/health':
            raise ValueError('Coolify /health container checks must be enabled')
        if app.get('ports_exposes') != '8081':
            raise ValueError('Coolify server app must expose container port 8081')
        if urllib.parse.urlparse(self.public).hostname not in [urllib.parse.urlparse(d).hostname for d in (app.get('fqdn') or '').split(',')]:
            raise ValueError('Coolify app does not serve the configured public hostname')
        envs = self.api('applications/' + self.app + '/envs')
        runtime = {e['key']: e.get('value') for e in envs if e.get('is_runtime') and not e.get('is_preview')}
        required = ('SHILLING_AUTH_MODE', 'SHILLING_SUPABASE_URL', 'SHILLING_SUPABASE_ANON_KEY', 'SHILLING_SUPABASE_SERVICE_KEY')
        if any(key not in runtime for key in required):
            raise ValueError('Coolify release app is missing runtime Supabase configuration')
        if runtime.get('SHILLING_AUTH_MODE') and str(runtime['SHILLING_AUTH_MODE']).lower() != 'supabase':
            raise ValueError('Production must use Supabase authentication')
        if self.busy(self.app):
            raise ValueError('Another Coolify deployment is active; retry when it finishes')
        if self.legacy:
            if self.legacy == self.app:
                raise ValueError('Legacy and release app must be different')
            legacy = self.application(self.legacy)
            if legacy.get('settings', {}).get('is_auto_deploy_enabled') or self.busy(self.legacy):
                raise ValueError('Legacy server must have automatic deployments disabled and no active deployment')
        print('Coolify preflight passed: image app, hostname, health check, Supabase settings and idle deployment queue.', flush=True)
        return app

    def wait_deployment(self, uuid, app_uuid=None, timeout=900):
        deadline = time.monotonic() + timeout
        last = None
        while time.monotonic() < deadline:
            deployment = self.api('deployments/' + uuid)
            status = deployment['status']
            if status != last:
                print(f'Coolify deployment {uuid}: {status}', flush=True)
                last = status
            if status == 'finished':
                self.wait_status(app_uuid or self.app, healthy=True, timeout=120)
                return
            if status not in ('queued', 'in_progress'):
                raise RolloutFailed(f'Coolify deployment {uuid} ended with {status}')
            time.sleep(10)
        raise TimeoutError(f'Coolify deployment {uuid} timed out; inspect/cancel it in Coolify before retrying')

    def wait_status(self, uuid, healthy, timeout=120):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            status = self.application(uuid).get('status', '')
            if (healthy and status == 'running:healthy') or (not healthy and status.startswith(('exited', 'stopped'))):
                return
            time.sleep(5)
        raise TimeoutError(f'Coolify app {uuid} did not become {"healthy" if healthy else "stopped"}')

    def public_response(self, path):
        result = subprocess.run(['curl', '--fail', '--silent', '--show-error', '--max-time', '15', self.public + path],
            check=True, capture_output=True, text=True)
        return result.stdout

    def verify_public(self):
        for attempt in range(12):
            try:
                if self.public_response('/health').strip() != 'ok':
                    raise ValueError('Public health response is not ok')
                config = json.loads(self.public_response('/api/config'))
                if str(config.get('authMode', '')).lower() != 'supabase':
                    raise ValueError('Public server is not in Supabase authentication mode')
                return
            except (subprocess.CalledProcessError, ValueError):
                if attempt == 11:
                    raise RuntimeError('Public server health/Supabase checks failed') from None
                time.sleep(5)

    def deploy(self, digest, tag):
        if not re.fullmatch(r'sha256:[0-9a-f]{64}', digest):
            raise ValueError('Missing or invalid image digest from Buildx metadata')
        previous = self.check()
        old_running = bool(self.legacy and self.application(self.legacy).get('status', '').startswith('running'))
        reference = IMAGE + '@' + digest
        # The API may mask environment values. Force the user-required auth
        # mode before starting a public container, without reading secrets.
        self.api('applications/' + self.app + '/envs/bulk', 'PATCH', {'data': [{
            'key': 'SHILLING_AUTH_MODE', 'value': 'supabase', 'is_runtime': True,
            'is_buildtime': False, 'is_preview': False,
        }]})
        # Coolify represents a digest as image_name@sha256 + ':' + image_tag.
        self.api('applications/' + self.app, 'PATCH', {
            'docker_registry_image_name': IMAGE + '@sha256',
            'docker_registry_image_tag': digest.split(':', 1)[1],
            'is_auto_deploy_enabled': False,
        })
        print('Promoting server image: ' + reference, flush=True)
        selected = self.application()
        if selected.get('docker_registry_image_name') != IMAGE + '@sha256' or selected.get('docker_registry_image_tag') != digest.split(':', 1)[1]:
            raise RuntimeError('Coolify did not retain the requested image digest')
        result = self.api('deploy', 'POST', {'uuid': self.app})
        deployments = result.get('deployments', [])
        if len(deployments) != 1 or not deployments[0].get('deployment_uuid'):
            raise RuntimeError('Coolify did not queue a deployment')
        deployment = deployments[0]['deployment_uuid']
        try:
            self.wait_deployment(deployment)
        except RolloutFailed:
            # A terminal failure keeps the prior container serving. Restore
            # the saved selection too, so a retry has the correct rollback image.
            self.api('applications/' + self.app, 'PATCH', {
                'docker_registry_image_name': previous['docker_registry_image_name'],
                'docker_registry_image_tag': previous['docker_registry_image_tag'],
            })
            raise
        # First release migrates from the existing source-built app. Keep it
        # running until the image app passes Coolify's container health check.
        if old_running:
            self.api('applications/' + self.legacy + '/stop', 'POST', {})
            self.wait_status(self.legacy, healthy=False)
        try:
            self.verify_public()
        except RuntimeError:
            # Deployment has finished, so it is safe to restore the previous
            # serving app/image without racing an in-progress rollout.
            if old_running:
                restored = self.api('applications/' + self.legacy + '/restart', 'POST', {})
                self.wait_deployment(restored['deployment_uuid'], self.legacy)
                self.api('applications/' + self.app + '/stop', 'POST', {})
                self.wait_status(self.app, healthy=False)
                self.verify_public()
                raise RuntimeError('Public checks failed; restored the legacy server. Release remains a draft.') from None
            if previous.get('status') == 'running:healthy':
                self.api('applications/' + self.app, 'PATCH', {
                    'docker_registry_image_name': previous['docker_registry_image_name'],
                    'docker_registry_image_tag': previous['docker_registry_image_tag'],
                })
                restored = self.api('deploy', 'POST', {'uuid': self.app})
                self.wait_deployment(restored['deployments'][0]['deployment_uuid'])
                self.verify_public()
                raise RuntimeError('Public checks failed; restored the previous image. Release remains a draft.') from None
            raise
        current = self.application()
        if current.get('docker_registry_image_name') != IMAGE + '@sha256' or current.get('docker_registry_image_tag') != digest.split(':', 1)[1]:
            raise RuntimeError('Coolify image selection changed during deployment')
        return {'tag': tag, 'image': reference, 'app_uuid': self.app, 'deployment_uuid': deployment,
                'public_url': self.public, 'health': 'passed', 'auth_mode': 'supabase',
                'legacy_app_stopped': self.legacy if old_running else None,
                'previous_image_name': previous.get('docker_registry_image_name'),
                'previous_image_tag': previous.get('docker_registry_image_tag')}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check-only', action='store_true')
    args = parser.parse_args()
    coolify = Coolify()
    if args.check_only:
        coolify.check()
        return
    metadata = json.loads(Path('release-output/server-image.json').read_text())
    evidence = coolify.deploy(metadata['containerimage.digest'], os.environ['SHILLING_RELEASE_TAG'])
    Path('release-output/server-deployment.json').write_text(json.dumps(evidence, indent=2) + '\n')
    print('Hosted server deployment passed: ' + evidence['image'])


if __name__ == '__main__':
    try:
        main()
    except (KeyError, ValueError, RuntimeError, TimeoutError, urllib.error.URLError, subprocess.CalledProcessError) as error:
        print(f'Coolify deployment failed: {error}', file=sys.stderr)
        sys.exit(1)
