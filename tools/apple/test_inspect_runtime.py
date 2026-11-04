from __future__ import annotations

import hashlib
import io
import os
import pathlib
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from inspect_runtime import inspect
from native_metadata import inspect_elf, summarize


class MetadataSummaryTest(unittest.TestCase):
    def test_empty_or_invalid_images_never_pass(self):
        for images in ({}, {'test.so': {'digest': {'validElf': False}}}):
            with self.subTest(images=images):
                report = summarize(images, set())
                self.assertFalse(report['validElfImages'])
                self.assertFalse(report['matchesRequestedArchitecture'])
                self.assertFalse(report['loadSegments16KiBAligned'])
                self.assertFalse(report['runtimeCompatibilityVerified'])

    def test_private_android_dependency_is_not_assumed_public(self):
        images = {'probe.so': {'digest': {
            'validElf': True, 'matchesRequestedArchitecture': True,
            'loadSegments16KiBAligned': True,
            'neededLibraries': ['libc.so', 'libandroid_runtime.so', 'libc++_shared.so'],
        }}}
        report = summarize(images, {'probe.so', 'libc++_shared.so'})
        self.assertEqual(['libandroid_runtime.so'], report['dependenciesNotInPackageOrPublicNdk30'])
        self.assertFalse(report['runtimeCompatibilityVerified'])

    def test_dependency_name_presence_is_not_runtime_verification(self):
        images = {'probe.so': {'digest': {
            'validElf': True, 'matchesRequestedArchitecture': True,
            'loadSegments16KiBAligned': True, 'neededLibraries': ['libandroid_runtime.so'],
        }}}
        report = summarize(images, {'libandroid_runtime.so'})
        self.assertEqual([], report['dependenciesNotInPackageOrPublicNdk30'])
        self.assertFalse(report['runtimeCompatibilityVerified'])


class ArchiveInspectionTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = pathlib.Path(self.tmp.name)

    @staticmethod
    def profile(data):
        return {'apple_music': {'testFixture': True}, 'libs': {
            'arm64-v8a': {'libprobe.so': hashlib.sha256(data).hexdigest()},
        }}

    def write_zip(self, path, entries):
        with zipfile.ZipFile(path, 'w') as archive:
            for name, data in entries: archive.writestr(name, data)
        return path

    def test_non_elf_hash_match_is_not_a_playback_claim(self):
        data = b'fixture, not executable code'
        path = self.write_zip(self.root / 'test.apk', [('lib/arm64-v8a/libprobe.so', data)])
        report = inspect(path, 'arm64-v8a', self.profile(data), pathlib.Path('/unused'))
        self.assertTrue(report['compatibleNativeBytes'])
        self.assertFalse(report['nativeMetadata']['validElfImages'])
        for key in ('runtimeLoaded', 'accountTested', 'playbackTested', 'proprietaryFilesExtracted'):
            self.assertFalse(report[key])

    def test_missing_requested_abi_is_not_a_match(self):
        data = b'fixture'
        path = self.write_zip(self.root / 'test.apk', [('lib/x86_64/libprobe.so', data)])
        report = inspect(path, 'arm64-v8a', self.profile(data), pathlib.Path('/unused'))
        self.assertFalse(report['compatibleNativeBytes'])
        self.assertFalse(report['libraries']['libprobe.so']['found'])
        self.assertFalse(report['nativeMetadata']['validElfImages'])

    def test_apkm_splits_are_read_without_writing_package_contents(self):
        data = b'fixture'
        split = io.BytesIO()
        with zipfile.ZipFile(split, 'w') as archive:
            archive.writestr('lib/arm64-v8a/libprobe.so', data)
            archive.writestr('../../not-extracted', b'ignored')
        path = self.write_zip(self.root / 'test.apkm', [('split.apk', split.getvalue())])
        before = path.read_bytes()
        report = inspect(path, 'arm64-v8a', self.profile(data))
        self.assertTrue(report['compatibleNativeBytes'])
        self.assertEqual(before, path.read_bytes())
        self.assertEqual([path], list(self.root.iterdir()))
        self.assertFalse(report['proprietaryFilesExtracted'])

    def test_conflicting_split_libraries_are_ambiguous(self):
        data = b'first'
        split = io.BytesIO()
        with zipfile.ZipFile(split, 'w') as archive:
            archive.writestr('lib/arm64-v8a/libprobe.so', b'different')
        path = self.write_zip(self.root / 'test.apkm', [
            ('lib/arm64-v8a/libprobe.so', data), ('split.apk', split.getvalue()),
        ])
        report = inspect(path, 'arm64-v8a', self.profile(data), pathlib.Path('/unused'))
        self.assertFalse(report['compatibleNativeBytes'])
        self.assertTrue(report['libraries']['libprobe.so']['ambiguous'])
        self.assertEqual(['libprobe.so'], report['nativeMetadata']['ambiguousLibraryNames'])

    def test_package_size_budget_is_enforced(self):
        data = b'fixture'
        path = self.write_zip(self.root / 'test.apk', [('lib/arm64-v8a/libprobe.so', data)])
        with patch('inspect_runtime.MAX_TOTAL', len(data) - 1):
            with self.assertRaisesRegex(ValueError, 'inspection budget'):
                inspect(path, 'arm64-v8a', self.profile(data))

    def test_split_count_is_bounded(self):
        path = self.write_zip(self.root / 'test.apkm', [(f'{i}.apk', b'ignored') for i in range(33)])
        with self.assertRaisesRegex(ValueError, 'Too many split APKs'):
            inspect(path, 'arm64-v8a', self.profile(b''))


class RealNdkElfTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        ndk = os.environ.get('ANDROID_NDK_HOME')
        if not ndk: raise unittest.SkipTest('Set ANDROID_NDK_HOME to run real ELF integration tests')
        cls.bin = pathlib.Path(ndk) / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
        cls.tool = cls.bin / 'llvm-readobj'
        if not cls.tool.is_file(): raise unittest.SkipTest('Linux NDK LLVM toolchain unavailable')
        cls.tmp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.tmp.cleanup)
        cls.root = pathlib.Path(cls.tmp.name)
        dependency = cls.root / 'dependency.c'
        dependency.write_text('int audit_fixture_dependency(void) { return 7; }\n')
        probe = cls.root / 'probe.c'
        probe.write_text('extern int audit_fixture_dependency(void);\n'
                         'int audit_fixture(void) { return audit_fixture_dependency(); }\n')
        cls.payloads = {}
        for arch, triple in [('arm64-v8a', 'aarch64-linux-android'), ('x86_64', 'x86_64-linux-android')]:
            compiler = cls.bin / f'{triple}30-clang'
            target = cls.root / arch
            target.mkdir()
            common = [str(compiler), '-shared', '-fPIC', '-nostdlib']
            subprocess.run(common + ['-Wl,-z,max-page-size=16384', '-Wl,-soname,libandroid_runtime.so',
                                     str(dependency), '-o', str(target / 'libandroid_runtime.so')],
                           check=True, capture_output=True)
            for page_size in (4096, 16384):
                output = target / f'probe-{page_size}.so'
                subprocess.run(common + [f'-Wl,-z,max-page-size={page_size}', '-Wl,-soname,libprobe.so',
                                         str(probe), '-L' + str(target), '-landroid_runtime', '-o', str(output)],
                               check=True, capture_output=True)
                cls.payloads[arch, page_size] = output.read_bytes()

    def test_real_arm64_and_x86_64_shared_library_headers(self):
        for arch in ('arm64-v8a', 'x86_64'):
            with self.subTest(arch=arch):
                report = inspect_elf(self.payloads[arch, 16384], arch, self.tool)
                self.assertTrue(report['validElf'], report)
                self.assertTrue(report['matchesRequestedArchitecture'])
                self.assertTrue(report['loadSegments16KiBAligned'])
                self.assertIn('libandroid_runtime.so', report['neededLibraries'])

    def test_real_4k_library_is_distinguished_from_16k(self):
        report = inspect_elf(self.payloads['arm64-v8a', 4096], 'arm64-v8a', self.tool)
        self.assertTrue(report['validElf'])
        self.assertFalse(report['loadSegments16KiBAligned'])

    def test_renamed_x86_library_does_not_pass_arm64(self):
        report = inspect_elf(self.payloads['x86_64', 16384], 'arm64-v8a', self.tool)
        self.assertTrue(report['validElf'])
        self.assertFalse(report['matchesRequestedArchitecture'])

    def test_truncated_elf_is_rejected(self):
        report = inspect_elf(self.payloads['arm64-v8a', 16384][:40], 'arm64-v8a', self.tool)
        self.assertFalse(report['validElf'])

    def test_elf_tool_failure_is_not_reported_as_valid(self):
        report = inspect_elf(self.payloads['arm64-v8a', 16384], 'arm64-v8a', self.root / 'absent-tool')
        self.assertFalse(report['validElf'])

    def test_real_dependency_and_metadata_are_only_static_evidence(self):
        payload = self.payloads['arm64-v8a', 16384]
        package = self.root / 'generated-fixture.apk'
        with zipfile.ZipFile(package, 'w') as archive:
            archive.writestr('lib/arm64-v8a/libprobe.so', payload)
        report = inspect(package, 'arm64-v8a', ArchiveInspectionTest.profile(payload), self.tool)
        self.assertTrue(report['compatibleNativeBytes'])
        metadata = report['nativeMetadata']
        self.assertTrue(metadata['validElfImages'])
        self.assertEqual(['libandroid_runtime.so'], metadata['dependenciesNotInPackageOrPublicNdk30'])
        self.assertFalse(metadata['runtimeCompatibilityVerified'])
        self.assertFalse(report['runtimeLoaded'])
        self.assertFalse(report['playbackTested'])


if __name__ == '__main__': unittest.main()
