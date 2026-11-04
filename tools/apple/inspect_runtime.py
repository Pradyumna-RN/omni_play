"""Inspect a user-supplied Apple Music APK/APKM without loading its code.

Compares native-library bytes to wrapper-v2's pinned compatibility profile.
A match is not a successful account, FPS, playback or Android compatibility test.
"""
from __future__ import annotations
import argparse, hashlib, io, json, pathlib, zipfile
PROFILE = pathlib.Path(__file__).with_name('LIBS_VERSION.json')
MAX_MEMBER = 256 * 1024 * 1024
MAX_TOTAL = 1024 * 1024 * 1024

def inspect(path: pathlib.Path, arch: str, profile: dict) -> dict:
    expected = profile['libs'][arch]
    seen = {name: set() for name in expected}
    total = 0
    def apk(archive: zipfile.ZipFile):
        nonlocal total
        prefix = f'lib/{arch}/'
        for entry in archive.infolist():
            if not entry.filename.startswith(prefix): continue
            name = entry.filename[len(prefix):]
            if name not in expected: continue
            if entry.file_size > MAX_MEMBER: raise ValueError('A native library is too large')
            total += entry.file_size
            if total > MAX_TOTAL: raise ValueError('The package exceeds the inspection budget')
            h = hashlib.sha256()
            with archive.open(entry) as stream:
                count = 0
                while chunk := stream.read(65536):
                    count += len(chunk)
                    if count > MAX_MEMBER: raise ValueError('A native library exceeds its size bound')
                    h.update(chunk)
            seen[name].add(h.hexdigest())
    with zipfile.ZipFile(path) as bundle:
        apk(bundle)
        nested = [e for e in bundle.infolist() if e.filename.lower().endswith('.apk')]
        if len(nested) > 32: raise ValueError('Too many split APKs')
        for entry in nested:
            if entry.file_size > MAX_MEMBER: raise ValueError('A split APK is too large')
            total += entry.file_size
            if total > MAX_TOTAL: raise ValueError('The bundle exceeds the inspection budget')
            with bundle.open(entry) as raw:
                data = raw.read(MAX_MEMBER + 1)
            if len(data) > MAX_MEMBER: raise ValueError('A split APK exceeds its size bound')
            with zipfile.ZipFile(io.BytesIO(data)) as split: apk(split)
    checks = {name: {'matches': hashes == {digest}, 'found': bool(hashes), 'ambiguous': len(hashes) > 1}
              for name, digest in expected.items() for hashes in [seen[name]]}
    return {'profile': profile['apple_music'], 'architecture': arch,
            'compatibleNativeBytes': all(c['matches'] for c in checks.values()),
            'libraries': checks, 'runtimeLoaded': False, 'accountTested': False,
            'playbackTested': False, 'proprietaryFilesExtracted': False}

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('package', type=pathlib.Path);p.add_argument('--arch', choices=['arm64-v8a','x86_64'], default='arm64-v8a')
    a=p.parse_args();result=inspect(a.package,a.arch,json.loads(PROFILE.read_text()))
    print(json.dumps(result,indent=2));return 0 if result['compatibleNativeBytes'] else 2
if __name__=='__main__':raise SystemExit(main())
