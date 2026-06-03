# Changelog

## V2.7 - 2026-06-03

- Fixed symlink resolution for improved Windows and CI compatibility.
- Bumped SDK and build tool dependencies.

## V2.5 - 2026-06-03

- Stabilized dictionary and playback flows.
- Collapsed dictionary blocks for improved readability.
- Improved APK update and download reliability.

## V2.4 - 2026-06-02

- Reused the existing app task when a link is shared into Rougo instead of creating stacked app instances.
- Kept downloaded YouTube/Bilibili/Niconico videos attached to their original source category and labeled them as local copies.
- Hardened self-update downloads so stale APKs are removed, DownloadManager success is verified, and the exact downloaded APK is installed.
- Opened dictionary lookup to the partial bottom-sheet state first instead of covering the whole screen immediately.
- Tightened Play Both sync by starting both players together, correcting drift, and driving paired cursors from the original timeline.

## V2.3 - 2026-06-01

- Fixed light mode system bars so status/navigation icons switch to dark.
- Routed Bilibili and Niconico links through stream setup instead of opening raw page URLs.
- Downloads Bilibili and Niconico imports to a local playable file before opening them.
- Preserved stream HTTP headers for VLC playback when a site requires user-agent or referrer headers.
- Tightened Play Both startup so the original video/audio is started before the recorded voice.
- Changed stream download actions to show loading, then an encircled checkmark after completion.
- Updated stream labels so Bilibili/Niconico items no longer appear as generic YouTube cards.

## V2.2 - 2026-06-01

- Fixed Play Both state so the original and recorded waveform controls both switch to pause while the paired segment is active.
- Smoothed player timeline updates between VLC time events so video and recording cursors animate at the same frame cadence.
- Added on-demand YouTube caption selection from the player subtitle menu.
- Prevented Bilibili and Niconico links from getting trapped in the expiring stream resolver loop.
- Added video/link thumbnails in the library when a cover or remote thumbnail is available.
- Added a spaced download action under each stream item delete button.
- Replaced automatic onboarding with a help button in the Library header.
- Added a manual Check for Updates button in Settings.
- Saved partial recordings when repeat mode is stopped mid-attempt.
- Updated waveform and pitch overlay colors to follow the selected accent theme.
- Fixed System theme accent swatches to reflect the current system light/dark state.

## V2.1 - 2026-06-01

- Replaced the launcher icon with the new microphone/reader artwork.
- Bumped the Android app version to `V2.1` with version code `10`.
- Added this changelog and wired GitHub Releases to publish it with tagged builds.
- Keeps the V2 stability fixes: long-audiobook waveform decoding is capped, zero-length shadowing segments are rejected, crash reports are saved, and media-tool runtime classes stay intact in release builds.
- Keeps the V2 media workflow upgrades: embedded audiobook metadata/cover art, in-app video link streaming/downloading, embedded subtitle selection, default 720p YouTube quality, preferred subtitle language, theme modes, accent colors, onboarding, and better update detection.

## V2 - 2026-06-01

- Added embedded M4B audiobook metadata and cover-art loading.
- Added startup crash reporting with copyable diagnostics.
- Fixed release crashes caused by stripped media-tool runtime classes.
- Updated the installed app name to `朗語`.
