# Third-party components and source builds

Darwin Android's source is provided under GPL-3.0-or-later, consistent with its NewPipeExtractor dependency. Keep license notices and corresponding source with any public binary release. This preview is local; no public release has been published.

| Component | Version / revision | License | Upstream |
| --- | --- | --- | --- |
| NewPipeExtractor | v0.27.1 | GPL-3.0-or-later | https://github.com/TeamNewPipe/NewPipeExtractor |
| FFmpeg | 8.0.3 | GPL-2.0-or-later for this build with x264 | https://ffmpeg.org/download.html |
| x264 | b35605ace3ddf7c1a5d67a2eb553f034aef41d55 | GPL-2.0-or-later | https://code.videolan.org/videolan/x264 |
| NanoHTTPD | 2.3.1 | BSD-3-Clause | https://github.com/NanoHttpd/nanohttpd |
| OkHttp | 4.12.0 | Apache-2.0 | https://github.com/square/okhttp |
| ZXing Android Embedded | 4.3.0 | Apache-2.0 | https://github.com/journeyapps/zxing-android-embedded |
| AndroidX / Compose | See Gradle dependency catalog | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |

`scripts/build-native.sh` downloads the exact upstream sources and builds both FFmpeg tools as Android PIE executables. The installed NDK is 29.0.14206865. Commands and configuration flags are in the script. No native source patches were used. The selected codecs, muxers, protocols, and filters are intentionally limited; this is not every optional FFmpeg codec library.

The FFmpeg 8.0.3 archive used for this build was verified against its upstream detached signature with fingerprint `FCF986EA15E6E293A5644F10B4322F04D67658D8`. Verify downloads again when rebuilding; retain the original archives, the x264 revision, build script, and full corresponding source alongside a public APK release. `native-build/` retains those sources locally and is excluded from Git to avoid committing generated objects.

Before publishing: include all dependency notices and corresponding GPL source, use a preserved release signing key, and test on a physical Android device and TV. Do not publish pairing keys, the Mac controller access code, signing credentials, or local project configuration.
