#!/usr/bin/env python3
"""Pinned minimal shared LGPL decoder build; no downloaded shell scripts."""
import argparse, hashlib, json, os, pathlib, shutil, subprocess, tarfile

SHA256 = "7481306bddb81f4d4b42bfde4b39ea4fa097d7fa5b8cdba377142c88172b4af9"
ROOT = pathlib.Path(__file__).resolve().parents[1]
def main():
    p = argparse.ArgumentParser()
    p.add_argument("--archive", type=pathlib.Path, required=True)
    p.add_argument("--output", type=pathlib.Path, required=True)
    p.add_argument("--target", choices=["host", "arm64-v8a", "x86_64"], required=True)
    p.add_argument("--ndk", type=pathlib.Path)
    p.add_argument("--jdk", type=pathlib.Path)
    p.add_argument("--jni-only", action="store_true", help="Rebuild adapter against the already verified local FFmpeg build")
    a = p.parse_args()
    assert hashlib.sha256(a.archive.read_bytes()).hexdigest() == SHA256, "Wrong FFmpeg source archive"
    out = a.output.resolve(); out.mkdir(parents=True, exist_ok=True)
    source = out / "FFmpeg-n6.1.6"
    if not source.exists():
        with tarfile.open(a.archive) as archive:
            archive.extractall(out, filter="data")
    build = out / ("build-" + a.target); build.mkdir(exist_ok=True)
    prefix = build / "install"
    def run(args): subprocess.run([str(x) for x in args], cwd=build, check=True)
    options = [source / "configure", "--prefix=" + str(prefix), "--disable-everything",
        "--disable-gpl", "--disable-nonfree", "--disable-version3", "--enable-shared", "--disable-static",
        "--disable-programs", "--disable-doc", "--disable-debug", "--disable-network",
        "--disable-avdevice", "--disable-avformat", "--disable-avfilter", "--disable-postproc",
        "--disable-swscale", "--enable-swresample", "--enable-avcodec", "--enable-avutil",
        "--enable-decoder=ac3,eac3,alac", "--disable-x86asm", "--enable-small", "--extra-cflags=-fPIC",
        "--extra-ldflags=-Wl,-z,max-page-size=16384"]
    includes = []
    if a.target == "host":
        assert a.jdk, "Pass --jdk for the host JNI build"
        compiler = "g++"
        shim = build / "shim/android"; shim.mkdir(parents=True, exist_ok=True)
        (shim / "log.h").write_text('#pragma once\n#include <stdarg.h>\n#include <stdio.h>\n#define ANDROID_LOG_ERROR 6\n#define ANDROID_LOG_DEBUG 3\nstatic inline int __android_log_print(int, const char*, const char* format, ...) { va_list args; va_start(args,format); int n=vfprintf(stderr,format,args); va_end(args); return n; }\n')
        includes = ["-I" + str(a.jdk / "include"), "-I" + str(a.jdk / "include/linux"), "-I" + str(shim.parent)]
        dest = out / "host"
    else:
        assert a.ndk, "Pass --ndk for Android"
        tool = a.ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
        arch, triple = ("aarch64", "aarch64-linux-android") if a.target == "arm64-v8a" else ("x86_64", "x86_64-linux-android")
        compiler = str(tool / (triple + "26-clang++"))
        options += ["--target-os=android", "--arch=" + arch, "--enable-cross-compile",
            "--cc=" + str(tool / (triple + "26-clang")), "--cxx=" + compiler,
            "--ar=" + str(tool / "llvm-ar"), "--ranlib=" + str(tool / "llvm-ranlib"),
            "--strip=" + str(tool / "llvm-strip")]
        dest = out / "jni" / a.target
    if not a.jni_only: run(options)
    config = (build / "config.h").read_text()
    for name in ["GPL", "GPLV3", "VERSION3", "NONFREE"]:
        assert f"#define CONFIG_{name} 0" in config, "Incompatible FFmpeg configuration: " + name
    if not a.jni_only:
        run(["make", "-j2"]); run(["make", "install"])
    dest.mkdir(parents=True, exist_ok=True)
    for lib in (prefix / "lib").glob("*.so*"):
        shutil.copy2(lib, dest / lib.name)
    run([compiler, "-std=c++17", "-shared", "-fPIC", "-O2", "-Wl,-z,max-page-size=16384", *includes,
        "-I" + str(prefix / "include"), ROOT / "src/main/jni/ffmpeg_jni.cc", "-L" + str(prefix / "lib"),
        "-lavcodec", "-lavutil", "-lswresample", *([] if a.target == "host" else ["-llog", "-static-libstdc++"]),
        ("-Wl,--disable-new-dtags,-rpath,$ORIGIN" if a.target == "host" else "-Wl,-rpath,$ORIGIN"), "-o", dest / "libffmpegJNI.so"])
    if a.target != "host":
        for library in dest.glob("*.so"):
            run([tool / "llvm-strip", "--strip-unneeded", library])
    (dest / "build-info.json").write_text(json.dumps({"ffmpeg": "n6.1.6", "sourceSha256": SHA256,
        "sourceUrl": "https://codeload.github.com/FFmpeg/FFmpeg/tar.gz/refs/tags/n6.1.6",
        "target": a.target, "configure": [str(x) for x in options], "license": "LGPL-2.1-or-later",
        "decoders": ["ac3", "eac3", "alac"]}, indent=2) + "\n")
    print("Built shared LGPL AC-3/E-AC-3 decoder:", dest)

if __name__ == "__main__":
    main()
