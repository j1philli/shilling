#!/usr/bin/env python3
"""Record exact desktop package hashes and embed the CI identity in the shell."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

TARGETS = {'linux': ('linux-x86_64',), 'windows': ('windows-x86_64',),
           'macos': ('darwin-x86_64', 'darwin-aarch64')}
SUFFIXES = {'linux': '.AppImage', 'windows': '.exe', 'macos': '.app.tar.gz'}


def sha256(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def identity():
    version = Path('VERSION').read_text().strip()
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    build = os.environ.get('TEAMCITY_BUILD_ID', '0')
    if not re.fullmatch(r'[1-9][0-9]*|0', build):
        raise ValueError('Desktop build ID must be numeric')
    return version, commit, build


def load_candidate(directory, target, version, commit):
    directory = Path(directory)
    candidate = json.loads((directory / f'Shilling_{version}_{target}-build.json').read_text())
    if (candidate.get('target'), candidate.get('version'), candidate.get('commit')) != (target, version, commit):
        raise ValueError('Desktop candidate does not match the selected source/target/version')
    build = candidate.get('build', '')
    if not re.fullmatch(r'[1-9][0-9]*', build):
        raise ValueError('Desktop candidate needs a TeamCity build ID')
    if candidate.get('update_version') != f'{version}+build.{build}':
        raise ValueError('Unexpected desktop update version')
    assets = candidate.get('assets', {})
    updater = candidate.get('updater', '')
    if updater not in assets or not updater.endswith(SUFFIXES[target]):
        raise ValueError('Missing desktop updater artifact')
    for name, digest in assets.items():
        if Path(name).name != name or not name.startswith(f'Shilling_{version}_') or sha256(directory / name) != digest:
            raise ValueError('Desktop artifact filename/hash mismatch')
    actual = {p.name for p in directory.iterdir() if p.is_file() and not p.name.endswith('-build.json')}
    if actual != set(assets):
        raise ValueError('Unexpected or missing desktop artifacts')
    return candidate


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['prepare', 'record'])
    parser.add_argument('target', choices=TARGETS)
    args = parser.parse_args()
    version, commit, build = identity()
    if args.command == 'prepare':
        Path('src-tauri/desktop-build.txt').write_text(build + '\n')
        return
    output = Path('desktop-artifacts') / args.target
    # Normalize names across Tauri's DMG/NSIS/RPM conventions. Product version
    # stays unchanged; the immutable release tag and embedded build distinguish CI runs.
    assets = {}
    updater = None
    for path in sorted(output.iterdir()):
        if not path.is_file():
            continue
        if path.name.endswith('.sig') or path.name.endswith('-build.json'):
            raise ValueError('Build output must contain unsigned updater artifacts only')
        suffix = next((s for s in ('.app.tar.gz', '.AppImage', '.dmg', '.exe', '.deb', '.rpm') if path.name.endswith(s)), None)
        if not suffix:
            raise ValueError('Unknown desktop package ' + path.name)
        name = f'Shilling_{version}_{args.target}' + suffix
        dest = output / name
        if dest != path and dest.exists():
            raise ValueError('Duplicate desktop package')
        path.rename(dest)
        assets[name] = sha256(dest)
        if suffix == SUFFIXES[args.target]:
            updater = name
    if not updater:
        raise ValueError('Desktop updater artifact was not built')
    candidate = dict(version=version, commit=commit, build=build, target=args.target,
                     update_version=f'{version}+build.{build}', updater=updater, assets=assets)
    (output / f'Shilling_{version}_{args.target}-build.json').write_text(json.dumps(candidate, indent=2)+'\n')


if __name__ == '__main__':
    main()
