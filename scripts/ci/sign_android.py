#!/usr/bin/env python3
"""Sign an existing main-chain bundle; private material never enters artifacts."""
import base64
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

from android_provenance import load_candidate, record, write_record


def main():
    if os.environ.get('BUILD_VCS_BRANCH') not in ('main', 'refs/heads/main'):
        raise ValueError('Android signing is restricted to main')
    version = Path('VERSION').read_text().strip()
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    source = Path('android-input')
    candidate = load_candidate(source, version, commit, signed=False)
    output = Path('android-signed')
    shutil.rmtree(output, ignore_errors=True)
    output.mkdir()
    bundle = output / f'Shilling_{version}_android-release.aab'
    shutil.copyfile(source / candidate['bundle'], bundle)
    # A private temporary directory is outside the checkout/artifact roots.
    with tempfile.TemporaryDirectory(prefix='shilling-sign-') as tmp:
        key = Path(tmp) / 'upload.p12'
        key.write_bytes(base64.b64decode(os.environ['ANDROID_UPLOAD_KEY_BASE64'], validate=True))
        key.chmod(0o600)
        args = ['-keystore', str(key), '-storetype', 'PKCS12',
                '-storepass:env', 'ANDROID_UPLOAD_KEY_PASSWORD']
        subprocess.run(['jarsigner', *args, '-keypass:env', 'ANDROID_UPLOAD_KEY_PASSWORD',
                        '-sigalg', 'SHA256withRSA', '-digestalg', 'SHA-256',
                        str(bundle), 'shilling-upload'], check=True)
        # Supplying the keystore trusts the expected self-signed upload certificate.
        subprocess.run(['jarsigner', '-verify', '-strict', *args, str(bundle), 'shilling-upload'], check=True)
    signed = record(bundle, version, commit, candidate['version_code'], signed=True)
    write_record(output, signed)
    shutil.copyfile(source / f'Shilling_{version}_android-debug.apk', output / f'Shilling_{version}_android-debug.apk')
    print(f"Signed Android {version} ({signed['version_code']}), SHA-256 {signed['sha256']}")


if __name__ == '__main__':
    main()
