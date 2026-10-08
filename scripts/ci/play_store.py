#!/usr/bin/env python3
"""Publish Android internal betas; manually promote the exact tested Play bundle."""
import argparse
from contextlib import contextmanager
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request

from android_provenance import PACKAGE, load_candidate

API = 'https://androidpublisher.googleapis.com/androidpublisher/v3/applications/' + PACKAGE
UPLOAD = 'https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/' + PACKAGE


class PlayAPI:
    def __init__(self):
        self.token = None
        self.expires = 0

    def access_token(self):
        import jwt
        if self.token and time.time() < self.expires:
            return self.token
        credentials = json.loads(os.environ['PLAY_SERVICE_ACCOUNT_JSON'])
        now = int(time.time())
        assertion = jwt.encode({'iss': credentials['client_email'], 'iat': now, 'exp': now + 3600,
            'scope': 'https://www.googleapis.com/auth/androidpublisher',
            'aud': 'https://oauth2.googleapis.com/token'}, credentials['private_key'],
            algorithm='RS256', headers={'kid': credentials['private_key_id']})
        data = urllib.parse.urlencode({'grant_type': 'urn:ietf:params:oauth:grant-type:jwt-bearer',
                                      'assertion': assertion}).encode()
        request = urllib.request.Request('https://oauth2.googleapis.com/token', data=data)
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                result = json.load(response)
        except urllib.error.HTTPError as error:
            # Do not include authentication response bodies/credentials in logs.
            raise RuntimeError(f'Google authentication failed: HTTP {error.code}') from None
        self.token, self.expires = result['access_token'], now + int(result['expires_in']) - 60
        return self.token

    def request(self, method, path, body=None, bundle=None):
        if not path.startswith('/edits'):
            raise ValueError('Unexpected Play API path')
        url = (UPLOAD if bundle else API) + path
        data = Path(bundle).read_bytes() if bundle else (None if body is None else json.dumps(body).encode())
        request = urllib.request.Request(url, data=data, method=method, headers={
            'Authorization': 'Bearer ' + self.access_token(),
            'Content-Type': 'application/octet-stream' if bundle else 'application/json'})
        # A mutation timeout may have succeeded. A rerun reconciles version/hash.
        try:
            with urllib.request.urlopen(request, timeout=300 if bundle else 60) as response:
                raw = response.read()
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as error:
            detail = error.read().decode()[:2000]
            raise RuntimeError(f'Play {method} {path}: HTTP {error.code}: {detail}') from None


@contextmanager
def edit(api):
    path = '/edits/' + api.request('POST', '/edits', {})['id']
    try:
        yield path
    finally:
        # Committed edits no longer exist. Cleanup must not hide an earlier failure.
        try:
            api.request('DELETE', path)
        except (RuntimeError, OSError):
            pass


def versions(track, status=None):
    return {str(code) for release in track.get('releases', [])
            if status is None or release.get('status') == status
            for code in release.get('versionCodes', [])}


def matching_bundle(api, path, candidate, required=True):
    rows = api.request('GET', path + '/bundles').get('bundles', [])
    matching = [b for b in rows if str(b['versionCode']) == candidate['version_code']]
    if not matching:
        if required:
            raise ValueError('Android candidate is not uploaded to Play; upload and test this exact chain first')
        return None
    if len(matching) != 1 or matching[0].get('sha256', '').lower() != candidate['sha256']:
        raise ValueError('Play bundle hash differs from the selected signed Android artifact')
    return matching[0]


def track(api, path, name):
    return api.request('GET', path + '/tracks/' + name)


def check_production(api, path, candidate):
    matching_bundle(api, path, candidate)
    production = track(api, path, 'production')
    code = candidate['version_code']
    if any(int(v) > int(code) for v in versions(production)):
        raise ValueError('A newer Android version is already in production; refusing to downgrade')
    if code in versions(production, 'completed'):
        return {'ready': True, 'already_promoted': True, 'version_code': code}
    if any(r.get('status') in ('draft', 'inProgress', 'halted') for r in production.get('releases', [])):
        raise ValueError('Resolve the existing production draft/staged rollout in Play Console first')
    if code not in versions(track(api, path, 'internal'), 'completed'):
        raise ValueError('Exact Android version is not active on internal testing; select the tested chain')
    return {'ready': True, 'already_promoted': False, 'version_code': code}


def readiness(api, candidate):
    # Play requires a temporary edit for reads; no track updates/uploads/commit.
    with edit(api) as path:
        return check_production(api, path, candidate)


def update_track(api, path, name, candidate):
    api.request('PUT', path + '/tracks/' + name, {'track': name, 'releases': [{
        'name': f"{candidate['version']} ({candidate['version_code']})",
        'versionCodes': [candidate['version_code']], 'status': 'completed'}]})
    api.request('POST', path + ':validate')
    api.request('POST', path + ':commit')


def receipt(output, candidate, channel):
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(dict(candidate, track=channel), indent=2) + '\n')


def upload(api, candidate, directory, output):
    with edit(api) as path:
        existing = matching_bundle(api, path, candidate, required=False)
        current = track(api, path, 'internal')
        code = candidate['version_code']
        if any(int(v) > int(code) for v in versions(current)):
            raise ValueError('A newer Android internal beta exists; refusing to move the track backwards')
        if code not in versions(current, 'completed'):
            if not existing:
                uploaded = api.request('POST', path + '/bundles?uploadType=media',
                                       bundle=Path(directory) / candidate['bundle'])
                if str(uploaded.get('versionCode')) != code or uploaded.get('sha256', '').lower() != candidate['sha256']:
                    raise ValueError('Play upload version/hash differs from selected Android bundle')
            update_track(api, path, 'internal', candidate)
    receipt(output, candidate, 'internal')


def promote(api, candidate, output):
    with edit(api) as path:
        state = check_production(api, path, candidate)
        if not state['already_promoted']:
            update_track(api, path, 'production', candidate)
    receipt(output, candidate, 'production')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['upload', 'check'])
    args = parser.parse_args()
    if args.command == 'upload' and os.environ.get('BUILD_VCS_BRANCH') not in ('main', 'refs/heads/main'):
        raise ValueError('Play beta uploads are restricted to main')
    version = Path('VERSION').read_text().strip()
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    directory = 'release-input/clients/android'
    candidate = load_candidate(directory, version, commit)
    api = PlayAPI()
    if args.command == 'upload':
        upload(api, candidate, directory, 'play-output/internal.json')
        print(f"Play internal: {version} ({candidate['version_code']})")
    else:
        print(json.dumps(readiness(api, candidate), indent=2))


if __name__ == '__main__':
    main()
