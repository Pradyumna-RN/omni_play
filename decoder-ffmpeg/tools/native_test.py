#!/usr/bin/env python3
"""Build and run the host JNI decode-loop regression tests under AddressSanitizer.

Use the install prefix produced by `build.py --target host` (or any FFmpeg 6.1
development install with ac3/eac3 decoders):

  python3 decoder-ffmpeg/tools/native_test.py --ffmpeg-prefix OUT/build-host/install --jdk "$JAVA_HOME"
"""
import argparse, os, pathlib, subprocess, sys, tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
REPO = ROOT.parent

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--ffmpeg-prefix", type=pathlib.Path, required=True,
                   help="FFmpeg prefix containing include/ and lib/")
    p.add_argument("--jdk", type=pathlib.Path, default=os.environ.get("JAVA_HOME"),
                   help="JDK home for jni.h (defaults to JAVA_HOME)")
    p.add_argument("--cxx", default="g++")
    p.add_argument("--recording", type=pathlib.Path,
                   help="Optional clear 48 kHz 5.1 E-AC-3 recording, stream-copied to raw EC3")
    a = p.parse_args()
    assert a.jdk, "Pass --jdk or set JAVA_HOME"
    prefix = a.ffmpeg_prefix.resolve()
    with tempfile.TemporaryDirectory() as temp:
        temp = pathlib.Path(temp)
        shim = temp / "android"; shim.mkdir()
        (shim / "log.h").write_text(
            "#pragma once\n#include <stdarg.h>\n#include <stdio.h>\n"
            "#define ANDROID_LOG_ERROR 6\n#define ANDROID_LOG_DEBUG 3\n"
            "static inline int __android_log_print(int, const char*, const char* f, ...) "
            "{ va_list a; va_start(a,f); int n=vfprintf(stderr,f,a); va_end(a); fputc('\\n',stderr); return n; }\n")
        binary = temp / "decode_packet_test"
        jdk = pathlib.Path(a.jdk)
        subprocess.run([a.cxx, "-std=c++17", "-O1", "-g", "-fsanitize=address,undefined",
            "-fno-sanitize-recover=undefined", "-I" + str(temp), "-I" + str(jdk / "include"),
            "-I" + str(jdk / "include" / "linux"), "-I" + str(prefix / "include"),
            str(ROOT / "src/test/native/decode_packet_test.cc"), "-L" + str(prefix / "lib"),
            "-Wl,-rpath," + str(prefix / "lib"), "-lavcodec", "-lavutil", "-lswresample",
            "-o", str(binary)], check=True)
        audio = REPO / "dsp/src/test/resources/audio"
        env = dict(os.environ, ASAN_OPTIONS="detect_leaks=1")
        recordings = [str(a.recording.resolve())] if a.recording else []
        result = subprocess.run([str(binary), str(audio / "owned-51.eac3"), str(audio / "owned-stereo.eac3"), *recordings], env=env)
        sys.exit(result.returncode)

if __name__ == "__main__":
    main()
