"""Verify the bundle manifest and bind signed/unsigned Android artifacts to source."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import urllib.request
import xml.etree.ElementTree as ET

PACKAGE = 'finance.shilling.android'
BUNDLETOOL_VERSION = '1.18.3'
BUNDLETOOL_SHA256 = 'a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29'
ANDROID = '{http://schemas.android.com/apk/res/android}'


def sha256(path):
    with open(path, 'rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def bundletool():
    cache = Path.home() / '.cache/shilling/bundletool'
    cache.mkdir(parents=True, exist_ok=True)
    jar = cache / f'bundletool-all-{BUNDLETOOL_VERSION}.jar'
    if not jar.exists() or sha256(jar) != BUNDLETOOL_SHA256:
        with tempfile.NamedTemporaryFile(dir=cache) as tmp:
            url = f'https://github.com/google/bundletool/releases/download/{BUNDLETOOL_VERSION}/{jar.name}'
            with urllib.request.urlopen(url, timeout=120) as response:
                shutil.copyfileobj(response, tmp)
            tmp.flush()
            if sha256(tmp.name) != BUNDLETOOL_SHA256:
                raise ValueError('bundletool checksum mismatch')
            shutil.copyfile(tmp.name, jar)
    return jar


def bundle_info(bundle):
    xml = subprocess.check_output(['java', '-jar', str(bundletool()), 'dump', 'manifest',
                                   '--bundle=' + str(bundle)], text=True)
    root = ET.fromstring(xml)
    sdk = root.find('uses-sdk')
    return {'package': root.get('package'), 'version': root.get(ANDROID + 'versionName'),
            'version_code': root.get(ANDROID + 'versionCode'),
            'target_sdk': int(sdk.get(ANDROID + 'targetSdkVersion', '0')) if sdk is not None else 0}


def record(bundle, version, commit, build_id, signed=False):
    bundle = Path(bundle)
    info = bundle_info(bundle)
    if info['package'] != PACKAGE or info['version'] != version:
        raise ValueError('Android package or product version does not match')
    if not re.fullmatch('[0-9a-f]{40}', commit):
        raise ValueError('Missing Android source commit')
    if not str(build_id).isdigit() or not 1 <= int(build_id) <= 2100000000:
        raise ValueError('Invalid Android version code')
    if info['version_code'] != str(build_id):
        raise ValueError('Android version code must equal its TeamCity build ID')
    if info['target_sdk'] < 36:
        raise ValueError('Google Play requires target API 36 or higher')
    return dict(info, commit=commit, teamcity_build_id=str(build_id), bundle=bundle.name,
                sha256=sha256(bundle), signed=signed)


def load_candidate(directory, version, commit, signed=True):
    directory = Path(directory)
    candidate = json.loads((directory / f'Shilling_{version}_android-build.json').read_text())
    filename = f'Shilling_{version}_android-release' + ('.aab' if signed else '-unsigned.aab')
    if candidate.get('bundle') != filename:
        raise ValueError('Unexpected Android bundle filename')
    actual = record(directory / filename, version, commit, candidate.get('teamcity_build_id'), signed)
    if actual != candidate:
        raise ValueError('Android provenance/hash does not match selected commit and bundle')
    return candidate


def write_record(directory, candidate):
    path = Path(directory) / f"Shilling_{candidate['version']}_android-build.json"
    path.write_text(json.dumps(candidate, indent=2) + '\n')


if __name__ == '__main__':
    import sys
    bundle = Path(sys.argv[1])
    candidate = record(bundle, Path('VERSION').read_text().strip(),
                       subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
                       os.environ['TEAMCITY_BUILD_ID'])
    write_record(bundle.parent, candidate)
