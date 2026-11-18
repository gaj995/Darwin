# Darwin

An open-source Android companion for Darwin on Samsung TV. Cast local videos, public YouTube videos and detected website streams over your home Wi-Fi.

[Download Darwin 0.4.0 for Android](https://github.com/gaj995/Darwin/releases/download/v0.4.0-preview/Darwin.apk) · [Release files and checksums](https://github.com/gaj995/Darwin/releases/tag/v0.4.0-preview)

## Install and pair

1. Download the APK on an Android phone (Android 8 or newer) and install it.
2. Open the Darwin receiver on your Samsung TV. Press the Yellow button to show its private Android pairing QR.
3. In the Android app, choose Connect TV → Scan TV QR. The scanner uses portrait orientation.
4. Keep the phone and TV on the same Wi-Fi. The source phone must remain on while it serves a video.

The download QR and private pairing QR are separate. Downloading the app from GitHub does not require a Mac. Casting local files or detected streams from the Android app also works directly over Wi-Fi. The optional Mac controller provides the TV-browser and online subtitle-search tools.

This Android app requires the Darwin TV receiver; it does not install a Samsung TV widget. The receiver protocol module source is in android/tv-receiver.

## Features

- Browser video detection, public recorded YouTube support and Android local-file casting.
- Library, favorites, resume positions and an optional automatic queue.
- TV remote with clear volume/mute controls and press-and-hold direction buttons.
- Playback timeline, available quality choices, audio tracks and subtitles.
- Saved subtitle appearance, imported SRT/VTT files and playback diagnostics.
- English and Mexican Spanish through More → Settings, remembered on the phone.

## Preview limits

DRM, source restrictions, expired links, sign-in/live YouTube and some codecs are not supported. Software conversion depends on phone performance. Large files are streamed in bounded byte ranges; a full 100 GB movie has not been validated. Converted-file seeking can reopen the output.

The public APK uses release signing. An existing development APK has a different signature and cannot be updated by installing this APK over it. Preserve that development installation and its data until a deliberate migration is available; do not uninstall it merely to change signatures.

## Build and source

Use JDK 21, Android SDK 36 and NDK 29.0.14206865. From android, build native media tools with ./scripts/build-native.sh arm64-v8a x86_64, then run ./gradlew :app:assembleDebug :app:testDebugUnitTest. See android/THIRD_PARTY.md.

The release includes the complete corresponding-source archive, exact runtime dependency source jars/POMs, full FFmpeg/x264/NewPipe/nanojson sources, third-party notices and checksums. Private signing keys and user/device data are not included.

Licensed under GPL-3.0-or-later; see LICENSE.
