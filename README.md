<p align="center">
  <img src="android/app/src/main/res/drawable-nodpi/app_icon.png" alt="LearnHarness app icon" width="112">
</p>

<h1 align="center">LearnHarness for Android</h1>

<p align="center">An unofficial Android app for <a href="https://github.com/amosblomqvist/learnharness">LearnHarness</a>, created by <a href="https://github.com/amosblomqvist">Amos Blomqvist</a>.</p>

The app connects to your own self-hosted LearnHarness server, the server mode from the [shouryadixitisverycool/learnharness](https://github.com/shouryadixitisverycool/learnharness) fork (`docs/self-hosting.md` there). Sessions, the knowledge graph, settings and provider keys all live on the server, so the phone, the desktop app and any browser see the same state. The phone stores only the server's address and your sign-in.

Versions before 1.0.0 ran a complete LearnHarness server on the phone. That edition is in this repository's history up to commit `23b7d4c`.

## Compatibility

- Android 13 or newer, any CPU architecture
- A LearnHarness server with server mode on, reachable over HTTPS (for example through `tailscale serve`)

## What works

- Everything the server's web app does, laid out for narrow screens
- A native Connect to Server screen that checks the address is a LearnHarness server before saving it
- Settings → General → Server shows the server, with Connect… to change it and Sign out
- If the server can't be reached, a screen offers Try again or Change server
- Android Back closes the open menu, dialog or sidebar before going back
- Exports save through Android's document picker (up to 25 MB), and file imports use it too
- Dictation through the page's microphone, after Android asks for permission

## Build and install

See [Android build and installation](docs/android.md).

## Upstream and credits

LearnHarness's learning engine, web interface and app icon come from the upstream project by **Amos Blomqvist**. The original standalone Android adaptation was made by **Vyas Devgna**. This is not an official release and is not endorsed by the upstream developer. See [CREDITS.md](CREDITS.md).

## License

The source is distributed under the [GNU Affero General Public License v3.0](LICENSE), following the upstream project.
