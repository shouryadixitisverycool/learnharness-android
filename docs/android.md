# Android build and installation

LearnHarness for Android runs the existing LearnHarness server and interface locally on an ARM64 phone. It needs no desktop server, Termux app or ADB connection after installation. Provider requests still use the provider configured in the app and require internet access.

The documented minimum is **Android 13 on ARM64 (`arm64-v8a`)**. Device evidence is narrower than that target:

| Device and Android version | Evidence |
| --- | --- |
| Xiaomi 2411DRN47I, Android 16 | Previously exercised on an earlier build. The current `main` revision has not been rechecked on hardware. |
| Other models and Android versions | Not verified. |

Treat the minimum version as a build target, not a promise that every device has been tested.

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
- On launch, the runtime package is reused only when its install marker and server entrypoints are present; an incomplete extraction is discarded and unpacked again.
- A foreground service keeps accepted work running while the interface detaches. When the app is backgrounded, it stops the local server after ten idle minutes; active work is rechecked every minute. Android can still stop data-sync work at its system time limit; reopening the app restarts the local server and upstream recovery behavior applies.
- File imports and Markdown, SVG and HTML exports use Android's document picker. Exports are limited to 25 MB.
- General settings can create a password-encrypted backup of sessions, knowledge, preferences and prompt overrides. Restore checks the archive before offering merge or replace. Saved provider credentials, subscription sign-ins, diagnostics and temporary jobs are excluded; users reconnect providers after restoring.
- Browser-based subscription sign-in opens in the phone's browser and returns through the local callback listener.
- Demo checks run in a separate WebView with file access and the app bridge disabled. Requests are capped at 4 MiB, diagnostics at 64 entries of 512 characters per category, probe results at 256 KiB, and responses at 1 MiB; the runner reports console errors, page errors, failed resources and asynchronous probe results.
- Android Back dismisses the active menu or dialog before navigating. Menus, settings, information panels, chains controls and chat layout adapt to small viewports and keyboard resizing.

Desktop-only local CLI providers and Electron's desktop updater are not Android features. Install a newer build to update.

## Checks and limits

The source suite covers the local app capability check, bounded Android demo diagnostics/transport, export picker bridge, draft isolation, atomic JSON writes, runtime-extraction readiness and encrypted backup validation/restore recovery. Android CI runs JVM unit tests, assembles and lints the debug APK, and validates app identity, private-data exclusion and 16 KB ELF alignment. The previous device check exercised installation, provider connection, lesson generation, demos, keyboard resizing, Back navigation and opening the document picker. The picker save/import round trip was not completed, and the current build has not been installed on hardware. A synthetic browser test completed encrypted backup creation, preview and restore. It checked 320 CSS pixels with a simulated 200% root text size; this does not verify Android system font scaling or physical touch dimensions.

Local browser sweeps found no page-wide horizontal overflow on Home at 320, 360, 640, 800 and 1024 CSS pixels; Settings at 320, 360, 640 and 800 pixels; and Chains at 320, 360 and 800 pixels. Samples included an 800×480 short-landscape viewport, but do not cover every app surface, phone, tablet, font scale or Android window mode. TalkBack acceptance, release signing and Android 15 background timeout behavior remain unverified.

The ChatGPT subscription-backed lesson was tested using a user-authorized account connection. This repository does not add API-key billing. Provider access, model availability and account terms remain controlled by each provider.
