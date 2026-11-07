#!/usr/bin/env bash
# Windows (MSYS2 + Android NDK Windows toolchain) equivalent of build.py: the same FFmpeg n6.1.6
# configure options, then the same JNI link. Usage (from an MSYS2 shell with make installed):
#   NDK=/c/Users/me/AppData/Local/Android/Sdk/ndk/27.2.12479018 WORK=/c/build/ffmpeg \
#     decoder-ffmpeg/tools/build-windows.sh arm64-v8a|x86_64
# WORK must contain ffmpeg-n6.1.6.tar.gz (SHA-256 in build.py). Output: $WORK/jni/<abi>/*.so
set -euo pipefail
TARGET=$1
: "${NDK:?set NDK to the Android NDK directory}" "${WORK:?set WORK to a build directory holding ffmpeg-n6.1.6.tar.gz}"
export PATH=/usr/bin:/c/Windows/System32
export TEMP=${TEMP:-/tmp} TMP=${TMP:-/tmp} TMPDIR=${TMPDIR:-/tmp}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
TC=$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin
cd "$WORK"
[ -d FFmpeg-n6.1.6 ] || tar xzf ffmpeg-n6.1.6.tar.gz
if [ "$TARGET" = arm64-v8a ]; then ARCH=aarch64; TRIPLE=aarch64-linux-android; else ARCH=x86_64; TRIPLE=x86_64-linux-android; fi
BUILD=$WORK/build-$TARGET; mkdir -p "$BUILD"; cd "$BUILD"
PREFIX=$BUILD/install
../FFmpeg-n6.1.6/configure --prefix="$PREFIX" --disable-everything \
  --disable-gpl --disable-nonfree --disable-version3 --enable-shared --disable-static \
  --disable-programs --disable-doc --disable-debug --disable-network \
  --disable-avdevice --disable-avformat --disable-avfilter --disable-postproc \
  --disable-swscale --enable-swresample --enable-avcodec --enable-avutil \
  --enable-decoder=ac3,eac3,alac --disable-x86asm --enable-small --extra-cflags=-fPIC \
  --extra-ldflags=-Wl,-z,max-page-size=16384 \
  --target-os=android --arch=$ARCH --enable-cross-compile \
  --cc="$TC/clang.exe --target=${TRIPLE}26" --cxx="$TC/clang++.exe --target=${TRIPLE}26" \
  --ar="$TC/llvm-ar.exe" --ranlib="$TC/llvm-ranlib.exe" --strip="$TC/llvm-strip.exe" --nm="$TC/llvm-nm.exe"
for n in GPL GPLV3 VERSION3 NONFREE; do grep -q "#define CONFIG_$n 0" config.h || { echo "Incompatible config: $n"; exit 1; }; done
make -j4
make install
DEST=$WORK/jni/$TARGET; mkdir -p "$DEST"
cp -P "$PREFIX"/lib/*.so* "$DEST"/ 2>/dev/null || true
"$TC/clang++.exe" --target=${TRIPLE}26 -std=c++17 -shared -fPIC -O2 -Wl,-z,max-page-size=16384 \
  -I"$PREFIX/include" "$ROOT/src/main/jni/ffmpeg_jni.cc" -L"$PREFIX/lib" -lavcodec -lavutil -lswresample -llog -static-libstdc++ \
  -Wl,-rpath,'$ORIGIN' -o "$DEST/libffmpegJNI.so"
for f in "$DEST"/*.so; do "$TC/llvm-strip.exe" --strip-unneeded "$f"; done
ls -l "$DEST"
