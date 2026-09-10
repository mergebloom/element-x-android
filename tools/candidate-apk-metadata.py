#!/usr/bin/env python3
"""Inspect packaged validation APKs, including actual DEX revision; no runtime claims."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile
from loguru import logger
logger.remove()
from androguard.core.apk import APK
from androguard.core.dex import DEX

SIGNER = 'b0b051dc565c812fe17f6f3e945b4d79047123ab0da61286769eb2949197130e'
PACKAGE = 'io.element.android.x.debug'


def inspect(path, tools, expected_sha=None):
    def run(*args):
        result = subprocess.run([str(tools / args[0]), *args[1:]], capture_output=True, text=True, check=True)
        return result.stdout + result.stderr
    signatures = run('apksigner', 'verify', '--verbose', '--print-certs', str(path))
    print(signatures, flush=True)
    certs = re.findall(r'^Signer .* certificate SHA-256 digest: ([0-9a-f]{64})$', signatures, re.MULTILINE)
    assert certs == [SIGNER], certs
    run('zipalign', '-c', '-P', '16', '4', str(path))
    apk = APK(str(path))
    assert apk.get_package() == PACKAGE
    code = int(apk.get_androidversion_code())
    fields = {}
    with zipfile.ZipFile(path) as archive:
        assert archive.testzip() is None
        abis = sorted({n.split('/')[1] for n in archive.namelist() if n.startswith('lib/') and n.endswith('.so')})
        classname = 'Lio/element/android/x/BuildConfig;'
        for name in archive.namelist():
            if not re.fullmatch(r'classes\d*\.dex', name):
                continue
            blob = archive.read(name)
            if classname.encode() not in blob:
                continue
            dex = DEX(blob)
            for cls in dex.get_classes():
                if cls.get_name() == classname:
                    for field in cls.get_fields():
                        if field.get_name() in {'GIT_REVISION', 'GIT_BRANCH_NAME', 'VERSION_CODE', 'VERSION_NAME', 'APPLICATION_ID', 'BUILD_TYPE'}:
                            value = field.get_init_value()
                            fields[field.get_name()] = value.get_value() if value else None
            del dex, blob
    if expected_sha:
        assert re.fullmatch(r'[0-9a-f]{40}', expected_sha)
        assert fields['GIT_REVISION'] == expected_sha[:8], fields
        assert fields['BUILD_TYPE'] == 'debug'
        assert fields['APPLICATION_ID'] == PACKAGE
        assert code > 202609022, code
        assert apk.get_androidversion_name() == '26.09.1-reasoning.2'
    with path.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    return {'filename': path.name, 'bytes': path.stat().st_size, 'sha256': digest,
            'package': PACKAGE, 'versionCode': code, 'versionName': apk.get_androidversion_name(),
            'minSdk': apk.get_min_sdk_version(), 'targetSdk': apk.get_target_sdk_version(),
            'debuggable': apk.get_android_manifest_xml().find('application').get('{http://schemas.android.com/apk/res/android}debuggable') == 'true',
            'abis': abis, 'signer_sha256': SIGNER, 'signature_verification': 'passed', 'alignment_16k': 'passed',
            'embedded_build_config': fields}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--sha')
    parser.add_argument('--apk', type=Path, action='append', required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--tools', type=Path)
    args = parser.parse_args()
    tools = args.tools or Path(os.environ['ANDROID_HOME']) / 'build-tools' / '36.0.0'
    assert (tools / 'apksigner').is_file(), tools
    rows = [inspect(p, tools, args.sha) for p in args.apk]
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps({'source_sha': args.sha, 'artifacts': rows, 'production_qualified': False}, indent=2) + '\n')
    print(json.dumps(rows, indent=2))


if __name__ == '__main__':
    main()
