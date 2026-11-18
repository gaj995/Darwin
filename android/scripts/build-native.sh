#!/bin/bash
set -eo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR="$PROJECT_DIR/native-build"
NDK_DIR="${ANDROID_NDK_ROOT:-$HOME/Library/Android/sdk/ndk/29.0.14206865}"
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/darwin-x86_64"
FF_VERSION=8.0.3
X264_COMMIT=b35605ace3ddf7c1a5d67a2eb553f034aef41d55
mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"
# Obtain signed FFmpeg sources from ffmpeg.org; verification instructions in THIRD_PARTY.md.
if [ ! -f "ffmpeg-$FF_VERSION.tar.xz" ]; then curl -fL "https://ffmpeg.org/releases/ffmpeg-$FF_VERSION.tar.xz" -o "ffmpeg-$FF_VERSION.tar.xz"; fi
if [ ! -d "ffmpeg-$FF_VERSION" ]; then tar xf "ffmpeg-$FF_VERSION.tar.xz"; fi
if [ ! -d x264 ]; then git clone https://code.videolan.org/videolan/x264.git x264; fi
git -C x264 checkout "$X264_COMMIT"
for ABI in "${@:-arm64-v8a}"; do
 case "$ABI" in
 arm64-v8a) TARGET=aarch64-linux-android; ARCH=aarch64; X264_ARCH=aarch64; ASM_ARGS=(); FFMPEG_ASM_ARGS=();;
 x86_64) TARGET=x86_64-linux-android; ARCH=x86_64; X264_ARCH=x86_64; ASM_ARGS=(--disable-asm); FFMPEG_ASM_ARGS=(--disable-x86asm);;
 *) echo "Unsupported ABI: $ABI"; exit 1;;
 esac
 PREFIX="$BUILD_DIR/install-$ABI"
 export CC="$TOOLCHAIN/bin/${TARGET}26-clang"
 export AR="$TOOLCHAIN/bin/llvm-ar"
 export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
 export STRIP="$TOOLCHAIN/bin/llvm-strip"
 mkdir -p "$BUILD_DIR/x264-$ABI" "$BUILD_DIR/ffmpeg-$ABI" "$PREFIX"
 cd "$BUILD_DIR/x264-$ABI"
 "$BUILD_DIR/x264/configure" --prefix="$PREFIX" --host="$TARGET" --enable-static --enable-pic --disable-cli --disable-opencl "${ASM_ARGS[@]}" --extra-cflags="-O2 -fPIC" --extra-ldflags="-Wl,-z,max-page-size=16384" > configure.log 2>&1
 make -j6 > build.log 2>&1
 make install > install.log 2>&1
 cd "$BUILD_DIR/ffmpeg-$ABI"
 PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig" "$BUILD_DIR/ffmpeg-$FF_VERSION/configure" --prefix="$PREFIX" --target-os=android --arch="$ARCH" --enable-cross-compile --cc="$CC" --cxx="$TOOLCHAIN/bin/${TARGET}26-clang++" --ar="$AR" --ranlib="$RANLIB" --strip="$STRIP" --sysroot="$TOOLCHAIN/sysroot" --enable-gpl --enable-libx264 --pkg-config-flags=--static --enable-pic --disable-shared --enable-static --disable-autodetect --disable-debug --disable-doc --disable-avdevice --disable-ffplay --enable-small --disable-protocols --enable-protocol=file,http,tcp,pipe --disable-encoders --enable-encoder=libx264,aac,webvtt,mov_text,pcm_s16le --disable-muxers --enable-muxer=mp4,webvtt,null --disable-filters --enable-filter=scale,format,overlay,aresample,anull,null,fps,setpts,asetpts --extra-cflags="-O2 -fPIC -I$PREFIX/include" --extra-ldflags="-L$PREFIX/lib -Wl,-z,max-page-size=16384" "${FFMPEG_ASM_ARGS[@]}" > configure.log 2>&1
 make -j6 ffmpeg ffprobe > build.log 2>&1
 DEST="$PROJECT_DIR/app/src/main/jniLibs/$ABI"
 mkdir -p "$DEST"
 cp ffmpeg "$DEST/libffmpeg.so"
 cp ffprobe "$DEST/libffprobe.so"
 "$STRIP" "$DEST/libffmpeg.so" "$DEST/libffprobe.so"
 echo "Built $ABI"
 done
