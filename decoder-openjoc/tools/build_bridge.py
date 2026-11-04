"""Build the app's JNI adapter against verified, speaker-only OpenJOC artifacts."""
from __future__ import annotations
import argparse, hashlib, json, pathlib, shutil, subprocess
ROOT = pathlib.Path(__file__).resolve().parents[1]
PIN = 'e3e330a2000f8d0bced3460756473c8774eb16f0'
def digest(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    p=argparse.ArgumentParser();p.add_argument('--artifacts',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True)
    p.add_argument('--java-home',type=pathlib.Path,required=True);p.add_argument('--ndk',type=pathlib.Path);a=p.parse_args()
    source=a.artifacts.resolve();out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
    assert (source/'SOURCE_COMMIT').read_text().strip()==PIN
    for line in (source/'SHA256SUMS').read_text().splitlines():
        sha,name=line.split(maxsplit=1);path=source/name.strip().removeprefix('*');assert path.resolve().is_relative_to(source)
        assert digest(path)==sha,str(path)
    assert (source/'openjoc.h').read_bytes()==(ROOT/'src/main/jni/include/openjoc.h').read_bytes()
    results={}
    targets=[('host','host',pathlib.Path(shutil.which('g++')))]
    if a.ndk:
        tool=a.ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin'
        targets += [('arm64-v8a','jni/arm64-v8a',tool/'aarch64-linux-android26-clang++'),('x86_64','jni/x86_64',tool/'x86_64-linux-android26-clang++')]
    for abi,relative,compiler in targets:
        dest=out/relative;dest.mkdir(parents=True,exist_ok=True)
        shutil.copy2(source/relative/'libopenjoc_capi.so',dest/'libopenjoc_capi.so')
        flags=['-std=c++17','-O2','-fPIC','-fvisibility=hidden','-shared','-Wall','-Wextra','-I'+str(ROOT/'src/main/jni/include')]
        if abi=='host':flags += ['-I'+str(a.java_home/'include'),'-I'+str(a.java_home/'include/linux'),'-Wl,-rpath,$ORIGIN']
        else:flags += ['-static-libstdc++','-Wl,-z,max-page-size=16384','-Wl,--exclude-libs,ALL']
        lib=dest/'libopenjocJNI.so'
        subprocess.run([str(compiler),*flags,str(ROOT/'src/main/jni/openjoc_jni.cpp'),'-L'+str(dest),'-lopenjoc_capi','-o',str(lib)],check=True)
        results[abi]={f.name:digest(f) for f in dest.glob('*.so')}
    report={'sourceCommit':PIN,'version':'0.20.1','abi':'1.7-experimental','speakerLayout':'7.1.4','embeddedHrtf':False,
            'bridgeSha256':digest(ROOT/'src/main/jni/openjoc_jni.cpp'),'featurePatchSha256':digest(source/'speaker-only-build.patch'),'artifacts':results}
    (out/'native-build.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
if __name__=='__main__':main()
