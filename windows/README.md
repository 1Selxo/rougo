# Rougo for Windows

This module is a Windows desktop port of the Android Rougo app. It uses Compose Desktop for the UI, keeps the Android library item JSON shape, and bundles the media tools needed for a normal install:

- `yt-dlp.exe` for stream resolution, downloads, playlists, and captions.
- `ffmpeg.exe` and `ffprobe.exe` for metadata, cover extraction, recording conversion, and waveform analysis.
- `std.rnnn` RNNoise model for Windows recording noise suppression.
- Portable VLC 3.0.23 for embedded audio/video playback through VLCJ.
- JavaFX WebView for the in-app YouTube browser.

Packaged Windows builds do not require separate VLC, FFmpeg, or yt-dlp installation.

## Requirements

- Windows 10 or newer.
- JDK 21 with `jpackage.exe` for installer builds.
  - Android Studio's bundled JBR is enough for compilation/tests, but not enough for `packageDistributionForCurrentOS`.
- Network access during the first packaging/resource-prep build so Gradle can fetch bundled tools.

## Build And Run

From the repository root:

```powershell
.\gradlew.bat -p windows run
```

Compile and test:

```powershell
.\gradlew.bat -p windows compileKotlin
.\gradlew.bat -p windows test
```

Prepare bundled tools:

```powershell
.\gradlew.bat -p windows prepareBundledTools
```

Build an MSI/EXE installer with a full JDK 21 on `JAVA_HOME`:

```powershell
$env:JAVA_HOME = "C:\Path\To\JDK-21"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat -p windows packageDistributionForCurrentOS
```

Outputs are generated under `windows/build/compose/binaries/`.

## Data Locations

Windows app data is stored under:

```text
%APPDATA%\Rougo
```

Important files and folders:

- `rougo_library.json`: Android-compatible library `items` array.
- `app_prefs.json`: theme, accent, player, and YouTube settings.
- `hoshi_engine.json`: dictionary settings and ordering.
- `downloads/`: downloaded media.
- `recordings/`: shadowing recordings.
- `subtitles/`: downloaded or copied subtitles.
- `covers/`: cached artwork.
- `hoshi_dicts/`: imported Yomitan dictionaries in Windows JSONL form.

## Compatibility Notes

The library JSON fields match Android (`id`, `title`, `mediaUri`, `subtitleUri`, `progress`, `duration`, `recordings`, `sourceUrl`, `formatId`, metadata fields, folders, and playlists). Existing records can be migrated by placing the Android `items` array into `%APPDATA%\Rougo\rougo_library.json`.

Android `content://` media URIs are not usable on Windows. Copy the media/subtitle files to Windows and update or re-import those items. YouTube stream/download records remain compatible because they keep the original `sourceUrl`.

Yomitan ZIP import is compatible. The Android Hoshi native binary dictionary cache is not reused directly; Windows imports the same ZIP into a desktop JSONL index.

## Platform Differences

- Android runtime permission dialogs become Windows file picker and OS microphone permission behavior.
- Android notification channels and media-session controls are adapted to Windows tray notifications for update/download/export events plus in-window playback controls.
- Android live microphone noise effects are adapted to bundled FFmpeg RNNoise neural speech suppression during Windows recording conversion, with a spectral denoise fallback if the model is unavailable.
- Android APK self-install updates become a GitHub release check that opens the release/asset URL in the browser.
- Android share intents become command-line URL intake for packaged associations or pasted links inside the app.

See `../docs/WINDOWS_PARITY_CHECKLIST.md` for the screen-by-screen parity checklist.
