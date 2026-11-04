"""Build Music's optional Android native adapter; no Apple libraries are inputs."""
import argparse
import hashlib
import json
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'app/src/main/cpp/apple-runtime'


def source_hash():
    h = hashlib.sha256()
    for p in sorted(SOURCE.rglob('*')):
        if p.is_file():
            h.update(p.relative_to(SOURCE).as_posix().encode() + b'\0' + p.read_bytes())
    return h.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', type=pathlib.Path)
    parser.add_argument('--jdk', type=pathlib.Path)
    parser.add_argument('--target', choices=['arm64-v8a', 'x86_64', 'host'], required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    sources = [SOURCE / 'jni_bridge.cpp'] + [SOURCE / 'apple' / (name + '.cpp') for name in ['auth', 'decrypt', 'loader', 'playback', 'runtime', 'tokens']]
    flags = ['-std=c++17', '-O2', '-fPIC', '-shared', '-fvisibility=hidden', '-I' + str(SOURCE), '-Wl,--exclude-libs,ALL']
    if args.target == 'host':
        assert args.jdk
        compiler = 'g++'
        flags += ['-I' + str(args.jdk / 'include'), '-I' + str(args.jdk / 'include/linux'), '-pthread', '-ldl']
        out = args.output / 'host'
    else:
        assert args.ndk
        tool = args.ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
        triple = 'aarch64-linux-android' if args.target == 'arm64-v8a' else 'x86_64-linux-android'
        compiler = str(tool / (triple + '30-clang++'))
        flags += ['-static-libstdc++', '-Wl,-z,max-page-size=16384', '-llog', '-ldl']
        out = args.output / 'jni' / args.target
    out.mkdir(parents=True, exist_ok=True)
    binary = out / 'libmusicappleJNI.so'
    subprocess.run([compiler, *map(str, sources), *flags, '-o', str(binary)], check=True)
    if args.target != 'host': subprocess.run([str(tool / 'llvm-strip'), '--strip-unneeded', str(binary)], check=True)
    (out / 'build-info.json').write_text(json.dumps({'sourceSha256': source_hash(), 'minimumAndroidApi': 30,
        'target': args.target, 'containsAppleLibraries': False, 'adapterSha256': hashlib.sha256(binary.read_bytes()).hexdigest()}, indent=2) + '\n')
    print(binary)


if __name__ == '__main__': main()
