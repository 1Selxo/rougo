# Windows Parity Checklist

Use this checklist for side-by-side Android and Windows verification.

## Library

- Library title, summary counts, empty state, and no-match state match Android copy and layout.
- Add local media opens a picker, reads metadata/artwork, asks for optional subtitles, and saves the same item fields.
- Search filters title and metadata.
- Filters: All, Audio, Video, YouTube, Local.
- Sort: Recent, Title, Progress, Recordings.
- Stream/download link dialog detects normal video links and playlist links.
- Playlist import creates a playlist group plus ordered children.
- Folder create, rename, collapse/expand, move item, and delete flows work.
- YouTube download/delete-download state updates item `mediaUri` while preserving `sourceUrl`.

## YouTube Browser And Import

- In-app YouTube browser opens `https://m.youtube.com/`.
- Back, home, stop/refresh controls behave like Android.
- Selecting a playable YouTube URL opens Rougo setup instead of playing inside the browser.
- Selecting a YouTube playlist URL opens playlist import instead of stream setup.
- YouTube click/history interception works for normal navigation and single-page navigation.
- Preferred quality fast path opens directly when configured.
- Ask path shows caption choices and stream format choices.
- Bilibili and Niconico links follow Android's download-before-playback flow.
- Download path stores local media under `%APPDATA%\Rougo\downloads`.
- Playlist import uses yt-dlp flat playlist data and preserves child order.

## Player

- Player opens local files, downloaded files, and stream URLs.
- VLC playback supports play/pause, skip backward/forward, stop, seek, and end-of-media restart.
- Progress and duration persist to the library record.
- Video displays in the top pane; audio displays artwork or fallback music icon.
- SRT, VTT, and ASS subtitles parse and follow playback time.
- Subtitle visibility and subtitle delay controls match Android behavior.
- Player subtitle menu includes show/hide, custom subtitle import, YouTube caption choices, and embedded subtitle track controls.
- Custom subtitle import updates `subtitleUri`.
- YouTube caption download updates `subtitleUri`.
- Subtitle word tap opens dictionary lookup and pauses playback.

## Shadowing

- Start shadowing records microphone audio while source plays.
- Stop shadowing saves a segment with start/end time and file path.
- Latest recording card displays original/recorded waveforms and pitch traces.
- Play original segment, play recorded segment, share/export, delete, and backlog work.
- Repeat segment mode records attempts, plays attempts, increments attempt count, and honors "Save repeat recordings".

## Dictionary

- First launch downloads/imports JMdict when no dictionary is present.
- Yomitan ZIP import works for term and pitch dictionaries.
- Dictionary order, delete, block collapse, target language, and noise cancellation settings persist.
- Dictionary block-collapse settings honor Android's legacy nested-collapse preference key.
- Lookup uses Japanese prefix search and language-specific deinflectors for English, Chinese, Korean, Arabic, German, Spanish, French, Italian, and Russian.
- Structured glossary JSON is rendered as readable text.
- Pitch positions display diagram rows when present.

## Settings

- Theme: Dark, Black (OLED), Light, System.
- Accent swatches match Android options.
- Target language menu matches Android options.
- Noise cancellation toggle persists and affects Windows recording conversion.
- Skip buttons and subtitle offset sliders persist with the same ranges.
- YouTube quality, auto subtitles, and subtitle language settings persist.
- Version card checks GitHub releases and offers update browser handoff.

## Data/API Compatibility

- `rougo_library.json` round-trips the Android item schema.
- YouTube APIs and schemas are still yt-dlp JSON outputs.
- Local app data is under `%APPDATA%\Rougo`.
- Existing Android `content://` URIs require re-importing files on Windows.

## Verification Commands

```powershell
.\gradlew.bat -p windows compileKotlin
.\gradlew.bat -p windows test
.\gradlew.bat -p windows prepareBundledTools
```

Installer packaging additionally requires a full JDK 21 with `jpackage.exe`:

```powershell
.\gradlew.bat -p windows packageDistributionForCurrentOS
```
