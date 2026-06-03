# 朗語

朗語 is a native Android app for Japanese shadowing/choursing practice. It combines local media playback, YouTube stream support, subtitle timing controls, dictionary lookup, and voice recording feedback in one focused study app.

[Download V2.7](https://github.com/kaihouguide/rougo/releases/tag/V2.7)

## Screenshots

| Library | Audiobook Player | Shadowing Review |
|:---:|:---:|:---:|
| <img src="media/screenshots/v2-library.jpg" alt="Library showing local media, YouTube item, audiobook cover art, recordings, subtitles, search, and filters" width="220"> | <img src="media/screenshots/v2-player-cover.jpg" alt="Audiobook player using embedded M4B cover art with playback controls" width="220"> | <img src="media/screenshots/v2-shadowing-waveform.jpg" alt="Shadowing screen with subtitle text, recording controls, and original versus recorded waveform comparison" width="220"> |

| Settings: Controls | Settings: Version |
|:---:|:---:|
| <img src="media/screenshots/v2-settings-controls.jpg" alt="Settings screen with light mode, dictionaries, noise cancellation, skip buttons, and subtitle offset controls" width="220"> | <img src="media/screenshots/v2-settings-version.jpg" alt="Settings screen with YouTube quality, automatic subtitles, and version information" width="220"> |

## Features

- Local audio and video library with progress tracking, search, filters, and subtitle status.
- M4B audiobook support with embedded title metadata and cover art extraction.
- VLC-backed playback for reliable audio/video handling across common media formats.
- Shadowing mode with recording playback, original audio playback, waveform comparison, and pitch-style visual overlays.
- Toggleable noise cancellation for cleaner speech recording during shadowing.
- Subtitle delay controls and configurable skip buttons for fast listening review.
- YouTube sharing/import with selectable quality and optional automatic subtitle download.
- Built-in dictionary workflow with JMdict setup and Yomitan-style dictionary import.
- Crash reporting that saves the last crash details so issues can be diagnosed later.

## Installation

1. Open the [latest release](https://github.com/kaihouguide/rougo/releases/latest).
2. Download the APK that matches your device architecture.
   - Most modern Android phones use `app-arm64-v8a-release.apk`.
   - Android emulators may need `x86` or `x86_64`.
3. Install the APK on your Android device.
4. Open 朗語 and add local media, or share a YouTube link into the app.

## Building

### Requirements

- Android Studio
- JDK 21
- Android SDK 36
- Android NDK for the native dictionary module

### Local Build

```bash
git clone https://github.com/kaihouguide/rougo.git
cd rougo
./gradlew :app:assembleRelease
```

Release APKs are generated under:

```text
app/build/outputs/apk/release/
```

## Releases

GitHub Actions builds release APKs from version tags such as `V2.7`. The published release includes separate APKs for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`.

## License

This project is licensed under the GNU General Public License v3.0. See [LICENSE](LICENSE) for details.
## I will always be thankful for Manhhao for giving me a chance to be able to make this app a reality
pull requests and help with the app is always appreciated
