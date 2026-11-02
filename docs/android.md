# Android build and installation

LearnHarness for Android runs the existing LearnHarness server and interface locally on an ARM64 phone. It needs no desktop server, Termux app or ADB connection after installation. Provider requests still use the provider configured in the app and require internet access.

The current build supports **Android 13 or newer on ARM64 (`arm64-v8a`)**. It was exercised on a Xiaomi 2411DRN47I running Android 16. Other device models and Android versions have not been verified.

## Install

Build the APK using the steps below, or install a copy supplied by the project maintainer. To install a locally built APK over an existing installation while keeping its data, use:

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n ai.learnharness.android/.MainActivity
```

You can also copy `android/app/build/outputs/apk/debug/app-debug.apk` to the phone and open it there. Android may ask you to allow installation from that file app.

This is a debug-signed direct-install build, not a Play Store release. Android requires an update to use the same signing key as the installed APK. Keep your signing key for future updates; it is not included in this repository. Uninstalling or clearing app storage erases learning records and provider connections.

## Build from source

The commands below target Linux. Install Node.js 26 with npm, Java 17 or newer, Android SDK 36, Python 3, Rustup, and the `ar`, `readelf`, `bsdtar` and `patchelf` tools. Set `ANDROID_HOME` to the SDK directory. Network access is needed to install npm dependencies and fetch the checksum-pinned Android runtime packages and source.

```bash
npm ci
npm run build
node scripts/build-server.mjs
python3 android/prepare.py
./android/gradlew -p android :app:assembleDebug :app:lintDebug
```

`android/prepare.py` checks the pinned Termux Node.js 26 runtime and native libraries against `android/runtime/termux-lock.json`. It builds resvg from a checksum-pinned upstream source revision using Rust 1.99.1 and Android NDK 29, with 16 KB ELF page alignment; the Android canvas binary is pinned by the npm lockfile's integrity hash. The packaging step includes the production server and required runtime files, restores Next.js package symlinks, and preserves license notices. It excludes host-platform native binaries. CI inspects every packaged ARM64 ELF library and fails on incorrect machine type, missing app identity, private data paths or ELF segments aligned below 16 KB.

## Android behavior

- The app starts Node from its packaged native library directory and binds the local server to `127.0.0.1:43871`. A per-launch random cookie plus host and origin checks gate the server.
- Learning records and provider connections stay in app-private storage. Android cloud backup and device transfer are disabled for that profile.
- A foreground service keeps accepted work running while the interface detaches. When the app is backgrounded, it stops the local server after ten idle minutes; active work is rechecked every minute. Android can still stop data-sync work at its system time limit; reopening the app restarts the local server and upstream recovery behavior applies.
- File imports and Markdown, SVG and HTML exports use Android's document picker. Exports are limited to 25 MB.
- Browser-based subscription sign-in opens in the phone's browser and returns through the local callback listener.
- Demo checks run in a separate WebView with file access and the app bridge disabled. Requests and responses are bounded; the runner reports console errors, page errors, failed resources and asynchronous probe results.
- Android Back dismisses the active menu or dialog before navigating. Menus, settings, information panels, chains controls and chat layout adapt to small viewports and keyboard resizing.

Desktop-only local CLI providers and Electron's desktop updater are not Android features. Install a newer build to update.

## Checks and limits

The source suite covers the local app capability check, Android demo transport, export picker bridge, draft isolation and atomic JSON writes. Android CI assembles and lints the debug APK and validates app identity, private-data exclusion and 16 KB ELF alignment. The prior device check exercised installation, provider connection, lesson generation, demos, keyboard resizing, Back navigation and opening the document picker. The picker save/import round trip was not completed, and device validation covers only the hardware and OS version listed above. The responsive browser audit covered widths from 320 to 1024 CSS pixels, including a short landscape viewport; it does not verify every phone, tablet, font scale or Android window mode. Backup/restore, TalkBack acceptance, release signing and Android 15 background timeout behavior remain unverified.

The ChatGPT subscription-backed lesson was tested using a user-authorized account connection. This repository does not add API-key billing. Provider access, model availability and account terms remain controlled by each provider.
