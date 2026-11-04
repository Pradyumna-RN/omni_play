"""Read ELF metadata with the NDK's LLVM tool; never load a supplied library."""
from __future__ import annotations

import json
import pathlib
import subprocess

# Public native libraries available at Music's minimum Android API (30).
# Everything else needs a separately verified dependency, not an assumed /system path.
NDK_30_LIBRARIES = frozenset({
    'libaaudio.so', 'libamidi.so', 'libandroid.so', 'libbinder_ndk.so',
    'libcamera2ndk.so', 'libc.so', 'libdl.so',
    'libEGL.so', 'libGLESv1_CM.so', 'libGLESv2.so', 'libGLESv3.so',
    'libjnigraphics.so', 'liblog.so', 'libm.so', 'libmediandk.so',
    'libnativewindow.so', 'libneuralnetworks.so', 'libOpenMAXAL.so', 'libOpenSLES.so', 'libstdc++.so',
    'libsync.so', 'libvulkan.so', 'libz.so',
})
ARCHITECTURES = {'arm64-v8a': 'aarch64', 'x86_64': 'x86_64'}


def inspect_elf(data: bytes, arch: str, tool: pathlib.Path) -> dict:
    """LLVM reads bytes on stdin. No temporary/extracted native file is created."""
    if not data.startswith(b'\x7fELF'):
        return {'validElf': False, 'error': 'Not an ELF image'}
    try:
        result = subprocess.run(
            [str(tool), '--file-headers', '--program-headers', '--needed-libs',
             '--elf-output-style=JSON', '-'],
            input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            timeout=15, check=False,
        )
        if result.returncode != 0 or result.stderr or len(result.stdout) > 1024 * 1024:
            return {'validElf': False, 'error': 'LLVM rejected the ELF image or emitted diagnostics'}
        objects = json.loads(result.stdout)
        if not isinstance(objects, list) or len(objects) != 1:
            raise ValueError('Expected one ELF image')
        obj = objects[0]
        header = obj['ElfHeader']
        summary = obj['FileSummary']
        segments = [entry['ProgramHeader'] for entry in obj['ProgramHeaders']
                    if entry['ProgramHeader']['Type']['Value'] == 1]
        needed = obj['NeededLibraries']
        if not isinstance(needed, list) or not all(isinstance(n, str) for n in needed):
            raise ValueError('Invalid native dependencies')
        if not segments or not str(header['Type']).startswith('SharedObject'):
            raise ValueError('Expected a shared library with load segments')
        if any(s['Offset'] < 0 or s['FileSize'] < 0 or
               s['Offset'] + s['FileSize'] > len(data) or s['MemSize'] < s['FileSize']
               for s in segments):
            raise ValueError('Load segment outside image')
        aligned = all(
            s['Alignment'] >= 16384 and (s['Alignment'] & (s['Alignment'] - 1)) == 0 and
            s['Offset'] % 16384 == s['VirtualAddress'] % 16384
            for s in segments
        )
        return {
            'validElf': True,
            'elfArchitecture': summary['Arch'],
            'matchesRequestedArchitecture': (
                summary['Arch'] == ARCHITECTURES[arch] and
                header['Ident']['Class']['Value'] == 2 and
                header['Ident']['DataEncoding']['Value'] == 1
            ),
            'loadSegments16KiBAligned': aligned,
            'neededLibraries': sorted(set(needed)),
        }
    except (OSError, subprocess.TimeoutExpired, ValueError, KeyError, TypeError):
        return {'validElf': False, 'error': 'ELF inspection could not complete'}


def summarize(images: dict[str, dict[str, dict]], bundled_names: set[str]) -> dict:
    records = [record for versions in images.values() for record in versions.values()]
    unresolved = sorted({dependency for record in records
                         for dependency in record.get('neededLibraries', [])
                         if dependency not in bundled_names and dependency not in NDK_30_LIBRARIES})
    return {
        'validElfImages': bool(records) and all(r['validElf'] for r in records),
        'matchesRequestedArchitecture': bool(records) and all(
            r.get('matchesRequestedArchitecture', False) for r in records),
        'loadSegments16KiBAligned': bool(records) and all(
            r.get('loadSegments16KiBAligned', False) for r in records),
        'ambiguousLibraryNames': sorted(name for name, versions in images.items() if len(versions) > 1),
        'dependenciesNotInPackageOrPublicNdk30': unresolved,
        'images': {name: list(versions.values()) for name, versions in sorted(images.items())},
        'runtimeCompatibilityVerified': False,
    }
