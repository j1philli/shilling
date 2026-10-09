#!/usr/bin/env python3
"""Publish immutable signed desktop assets, then atomically advance static feeds."""
import argparse
import base64
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import urllib.error
import urllib.parse
import urllib.request

from desktop_artifacts import TARGETS, load_candidate, sha256

REPO = 'j1philli/shilling'
PROJECT = 'shilling-updates'
DOMAIN = 'updates.shilling.finance'
CHANNELS = ('stable', 'beta')


def run(*args, **kwargs):
    return subprocess.run(args, check=True, text=True, capture_output=True, **kwargs).stdout


def request_json(url, headers=None):
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers or {}), timeout=60) as response:
        return json.load(response)


def cloudflare(path, method='GET', body=None):
    token = os.environ.get('CLOUDFLARE_API_TOKEN') or os.environ['CF_API_TOKEN']
    request = urllib.request.Request('https://api.cloudflare.com/client/v4' + path, method=method,
        data=None if body is None else json.dumps(body).encode(),
        headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=60) as response:
        result = json.load(response)
    if not result.get('success'):
        raise RuntimeError('Cloudflare operation failed: ' + path)
    return result['result']


def project_path():
    return '/accounts/' + os.environ['CLOUDFLARE_ACCOUNT_ID'] + '/pages/projects/' + PROJECT


def state_from_host():
    project = cloudflare(project_path())
    deployment = project.get('canonical_deployment')
    if not deployment:
        raise ValueError('Initialize the update hosting project before publishing')
    url = deployment['url']
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme != 'https' or not parsed.hostname.endswith('.' + PROJECT + '.pages.dev'):
        raise ValueError('Unexpected Pages deployment URL')
    # Read the immutable deployed snapshot, never a possibly stale custom-domain cache.
    state = request_json(url + '/state.json')
    validate_state(state)
    return state


def validate_state(state):
    if state.get('schema') != 1 or set(state) != {'schema', 'feeds'}:
        raise ValueError('Unknown update feed state')
    allowed = {f'{channel}/{platform}.json' for channel in CHANNELS for values in TARGETS.values() for platform in values}
    if set(state['feeds']) != allowed:
        raise ValueError('Missing or unexpected update feed')
    for path, feed in state['feeds'].items():
        if feed.get('version') == '0.0.0':
            continue
        if not re.fullmatch(r'\d+\.\d+\.\d+\+build\.[1-9]\d*', feed.get('version', '')):
            raise ValueError('Invalid update feed version')
        if not feed.get('signature') or not feed.get('sha256'):
            raise ValueError('Missing update signature/hash')
        if not feed.get('url', '').startswith('https://github.com/' + REPO + '/releases/download/desktop-'):
            raise ValueError('Unexpected update asset URL')


def empty_state():
    return {'schema': 1, 'feeds': {f'{channel}/{platform}.json': {
        'version': '0.0.0', 'notes': 'No updates have been published.',
        'url': 'https://github.com/' + REPO + '/releases', 'signature': ''}
        for channel in CHANNELS for values in TARGETS.values() for platform in values}}


def write_site(directory, state):
    validate_state(state)
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    (directory / 'state.json').write_text(json.dumps(state, indent=2)+'\n')
    (directory / 'index.html').write_text('<!doctype html><html lang="en"><meta charset="utf-8"><title>Shilling updates</title><h1>Shilling updates</h1><p>This site serves desktop update feeds. Check for updates inside Shilling.</p></html>')
    (directory / '404.html').write_text('<!doctype html><title>Not found</title><h1>Update feed not found</h1>')
    (directory / '_headers').write_text('/*\n  Cache-Control: no-store\n  X-Content-Type-Options: nosniff\n')
    for name, feed in state['feeds'].items():
        file = directory / name
        file.parent.mkdir(exist_ok=True)
        file.write_text(json.dumps(feed, indent=2)+'\n')


def deploy(state, commit):
    with tempfile.TemporaryDirectory(prefix='shilling-update-site-') as tmp:
        write_site(tmp, state)
        env = dict(os.environ, CLOUDFLARE_API_TOKEN=os.environ.get('CLOUDFLARE_API_TOKEN') or os.environ['CF_API_TOKEN'])
        run('npx', '--yes', 'wrangler@4.80.0', 'pages', 'deploy', tmp,
            '--project-name=' + PROJECT, '--branch=main', '--commit-hash=' + commit,
            '--commit-dirty=false', env=env)
    if state_from_host() != state:
        raise RuntimeError('Pages did not publish the expected update state; rerun to reconcile')


def verify_signature(path, signature):
    # Tauri uses base64-wrapped Minisign. Verify its Ed25519 signature over the
    # Blake2b-512 digest AND signed comment, matching minisign-verify's verifier.
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
    pub = json.loads(Path('src-tauri/tauri.conf.json').read_text())['plugins']['updater']['pubkey']
    public_lines = base64.b64decode(pub, validate=True).decode().splitlines()
    lines = base64.b64decode(signature, validate=True).decode().splitlines()
    if len(public_lines) != 2 or len(lines) != 4:
        raise ValueError('Invalid updater signing key/signature format')
    key = base64.b64decode(public_lines[1], validate=True)
    sig = base64.b64decode(lines[1], validate=True)
    if len(lines) != 4 or len(key) != 42 or len(sig) != 74 or key[:2] not in (b'Ed', b'ED') or sig[:2] != b'ED' or sig[2:10] != key[2:10] or not lines[2].startswith('trusted comment: '):
        raise ValueError('Invalid updater signing key/signature format')
    verifier = Ed25519PublicKey.from_public_bytes(key[10:])
    with open(path, 'rb') as source:
        digest = hashlib.file_digest(source, lambda: hashlib.blake2b(digest_size=64)).digest()
    verifier.verify(sig[10:], digest)
    verifier.verify(base64.b64decode(lines[3], validate=True), sig[10:] + lines[2][17:].encode())


def release_tag(candidate):
    return f"desktop-{candidate['target']}-b{candidate['build']}"


def existing_release(tag):
    result = subprocess.run(['gh', 'api', f'repos/{REPO}/releases/tags/{tag}'], text=True, capture_output=True)
    if result.returncode:
        if '(HTTP 404)' in result.stderr:
            return None
        raise RuntimeError('Cannot inspect desktop GitHub release: ' + result.stderr)
    return json.loads(result.stdout)


def ensure_assets(candidate, directory):
    os.environ['GH_TOKEN'] = os.environ['GITHUB_TOKEN']
    tag = release_tag(candidate)
    marker = '<!-- shilling-desktop:' + json.dumps(candidate, sort_keys=True, separators=(',', ':')) + ' -->'
    # Resolve annotated or lightweight tags to their commit before any writes.
    refs = run('git', 'ls-remote', 'https://github.com/' + REPO + '.git',
               'refs/tags/' + tag, 'refs/tags/' + tag + '^{}').splitlines()
    if refs:
        resolved = next((line.split()[0] for line in refs if line.endswith('^{}')), refs[0].split()[0])
        if resolved != candidate['commit']:
            raise ValueError('Desktop release tag belongs to another commit')
    release = existing_release(tag)
    if release and marker not in (release.get('body') or ''):
        raise ValueError('Desktop release already belongs to another candidate')
    if not release:
        with tempfile.NamedTemporaryFile(mode='w', suffix='.md') as notes:
            notes.write(marker + f"\nShilling {candidate['version']} · {candidate['target']} build {candidate['build']}\n"); notes.flush()
            run('gh', 'release', 'create', tag, '--repo', REPO, '--target', candidate['commit'],
                '--title', f"Shilling {candidate['version']} — {candidate['target']} build {candidate['build']}",
                '--notes-file', notes.name, '--draft', '--prerelease')
        release = existing_release(tag)
    with tempfile.TemporaryDirectory(prefix='shilling-desktop-assets-') as tmp:
        tmp = Path(tmp)
        names = {a['name'] for a in release.get('assets', [])}
        if release['draft']:
            # Incomplete drafts may be safely completed. Published assets are immutable.
            for name in candidate['assets']:
                if name not in names:
                    run('gh', 'release', 'upload', tag, str(Path(directory)/name), '--repo', REPO)
            updater = Path(directory)/candidate['updater']
            sig_name = candidate['updater'] + '.sig'
            if sig_name not in names:
                with tempfile.TemporaryDirectory(prefix='shilling-sign-') as signing:
                    import shutil
                    signing_file = Path(signing)/updater.name
                    shutil.copyfile(updater, signing_file)
                    run('npx', '--yes', '@tauri-apps/cli@2.10.0', 'signer', 'sign', str(signing_file),
                        env=dict(os.environ, TAURI_SIGNING_PRIVATE_KEY_PASSWORD=os.environ.get('TAURI_SIGNING_PRIVATE_KEY_PASSWORD', '')))
                    signature_file = Path(str(signing_file)+'.sig')
                    verify_signature(updater, signature_file.read_text().strip())
                    run('gh', 'release', 'upload', tag, str(signature_file), '--repo', REPO)
        # Verify even existing assets before completing a draft or moving any feed.
        run('gh', 'release', 'download', tag, '--repo', REPO, '--dir', str(tmp))
        for name, digest in candidate['assets'].items():
            if sha256(tmp/name) != digest:
                raise ValueError('GitHub desktop asset differs from the tested package')
        signature = (tmp/(candidate['updater']+'.sig')).read_text().strip()
        verify_signature(tmp/candidate['updater'], signature)
        if release['draft']:
            run('gh', 'release', 'edit', tag, '--repo', REPO, '--draft=false', '--latest=false')
    url = f"https://github.com/{REPO}/releases/download/{tag}/{candidate['updater']}"
    return dict(version=candidate['update_version'], notes=f"Shilling {candidate['version']} · Build {candidate['build']}",
                url=url, signature=signature, sha256=candidate['assets'][candidate['updater']],
                commit=candidate['commit'], build=candidate['build'])


def advance(state, target, channel, feed):
    result = copy.deepcopy(state)
    def order(version):
        base, _, meta = version.partition('+build.')
        return (*map(int, base.split('.')), int(meta or '0'))
    for platform in TARGETS[target]:
        path = f'{channel}/{platform}.json'
        previous = state['feeds'][path]
        if order(previous['version']) > order(feed['version']):
            raise ValueError('Refusing to move a desktop update feed backwards')
        if previous['version'] == feed['version'] and previous != feed:
            raise ValueError('Same desktop version has different update contents')
        result['feeds'][path] = feed
    return result


def prepare_stable(targets, version, commit):
    desktop = [t for t in targets if t in TARGETS]
    if not desktop:
        return []
    state = state_from_host()
    candidates = []
    for target in desktop:
        candidate = load_candidate('release-input/clients/'+target, target, version, commit)
        feed = state['feeds'][f'beta/{TARGETS[target][0]}.json']
        if feed.get('version') != candidate['update_version'] or feed.get('sha256') != candidate['assets'][candidate['updater']] or feed.get('commit') != commit:
            raise ValueError(f'{target} candidate must be the exact published beta; upload and test this chain first')
        # Fetch and verify published signature/package before any other target changes.
        with tempfile.TemporaryDirectory() as tmp:
            file = Path(tmp)/candidate['updater']
            with urllib.request.urlopen(feed['url'], timeout=120) as response:
                import shutil
                with file.open('wb') as output: shutil.copyfileobj(response, output)
            if sha256(file) != feed['sha256']:
                raise ValueError('Published desktop beta hash mismatch')
            verify_signature(file, feed['signature'])
        advance(state, target, 'stable', feed)
        candidates.append((target, feed))
    return candidates


def publish_stable(candidates, commit):
    if not candidates:
        return
    state = state_from_host()
    for target, feed in candidates:
        if state['feeds'][f'beta/{TARGETS[target][0]}.json'] != feed:
            raise ValueError('Desktop beta changed after release preflight')
        state = advance(state, target, 'stable', feed)
    deploy(state, commit)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('target', choices=TARGETS)
    args = parser.parse_args()
    if os.environ.get('BUILD_VCS_BRANCH') not in ('main', 'refs/heads/main'):
        raise ValueError('Desktop beta publication is restricted to main')
    version = Path('VERSION').read_text().strip()
    commit = run('git', 'rev-parse', 'HEAD').strip()
    directory = 'release-input/clients/' + args.target
    candidate = load_candidate(directory, args.target, version, commit)
    state = state_from_host()
    feed = ensure_assets(candidate, directory)
    state = advance(state, args.target, 'beta', feed)
    deploy(state, commit)
    output = Path('desktop-update-output'); output.mkdir(exist_ok=True)
    (output/(args.target+'.json')).write_text(json.dumps(feed, indent=2)+'\n')


if __name__ == '__main__':
    main()
