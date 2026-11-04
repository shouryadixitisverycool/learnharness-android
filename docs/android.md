# Android build and installation

The app is a native host around a WebView that loads your LearnHarness server. It has no bundled server, Node runtime or native libraries. Set up the server first: `docs/self-hosting.md` in [shouryadixitisverycool/learnharness](https://github.com/shouryadixitisverycool/learnharness).

## Server requirements

- Server mode on (`LH_SERVER_PASSWORD` set).
- An HTTPS address. The app refuses plain `http://`, because the password and session cookie would travel unencrypted. On Tailscale, `tailscale serve --bg --https=8446 http://127.0.0.1:3000` on the server gives it `https://<server>.<tailnet>.ts.net:8446`. Tailscale must be connected on the phone.
- If the server runs with `LH_APPS_ONLY=1`, it must be a version that admits `LearnHarnessAndroid/<version>`.

## Build

Install Java 17 or newer and Android SDK 36, then set `ANDROID_HOME` to the SDK directory.

```bash
android/gradlew -p android testDebugUnitTest assembleDebug lintDebug
```

The APK is `android/app/build/outputs/apk/debug/app-debug.apk`. It's a debug-signed direct-install build, not a Play Store release. An update has to be signed with the same key as the installed copy, so keep the debug keystore (`~/.android/debug.keystore` on the build machine).

## Install

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

Or copy the APK to the phone and open it there. Android may ask you to allow installs from that app.

The 1.0.0 server shell keeps the app id `ai.learnharness.android`. A pre-1.0 standalone build signed with another key has to be uninstalled first, and that erases its on-phone data.

## Behavior

- **First launch.** The app asks for the server address. It checks that `<address>/login` is a LearnHarness sign-in page, then saves the address. You sign in with the server password on the page itself, and the session cookie lasts 30 days.
- **User agent.** The WebView's User-Agent ends with `LearnHarnessAndroid/<version>`. Servers with apps-only mode let that tag in, and the web app uses it for Android-specific layout.
- **Navigation.** The WebView stays on the server's origin. Other links open in the default browser.
- **Bridges.** The page gets a message port, delivered only to the main frame and never to demo iframes. It carries two things:
  - `window.lhAndroidSaveText(name, text, mime)` for exports
  - `window.lh.server` (`current`, `connect`, `signOut`) for Settings → General → Server. Sign out clears the app's cookies and reloads to the sign-in page.
- **Back.** It closes the topmost menu, dialog or chains overlay, then an open sidebar, then goes back in history, then sends the app to the background.
- **Server errors.** If the server can't be reached or its certificate is invalid, a native screen offers Try again or Change server.
- **What the phone stores.** Only the address (app preferences) and WebView cookies and storage. Android cloud backup and device transfer are disabled.
