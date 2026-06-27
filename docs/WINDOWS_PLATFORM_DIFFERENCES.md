# Windows Platform Differences

These are the known unavoidable differences from Android.

## Permissions

Android shows runtime permission prompts for microphone, notifications, package installation, and content URI access. Windows uses desktop file pickers and OS-level microphone privacy prompts instead. No app-specific notification permission prompt is shown.

## Notifications

Android notification channels and Android media-session controls do not map 1:1 to a Compose Desktop window. The Windows port uses system tray notifications for update/download/export events and keeps playback controls in the player screen. Full Windows media-key/session integration remains platform-specific follow-up work.

## Noise Cancellation

Android enables platform microphone effects such as noise suppressor, echo cancellation, and automatic gain control when the setting is on. Windows does not expose the same Android audio session effects through Java Sound, so the port keeps the same setting and runs the recorded voice through bundled FFmpeg `arnndn` RNNoise neural speech suppression with a bundled model during recording conversion. If the model is unavailable, Windows falls back to FFmpeg spectral and non-local-means denoise filters.

## Local URIs

Android can persist `content://` document permissions. Windows cannot dereference Android content URIs. The JSON schema remains compatible, but local media and subtitle files must exist on Windows paths or be re-imported.

## Dictionary Cache

Android uses the Hoshi JNI binary dictionary cache. The Windows port imports the same Yomitan ZIP files into JSONL indexes under `%APPDATA%\Rougo\hoshi_dicts`. User-facing dictionary import/search behavior is preserved, but the on-disk cache format is platform-specific.

## Updates

Android downloads an APK and launches package installation. Windows checks GitHub releases and opens the release asset/page in the browser so the user can install the Windows build.

## Share Intents

Android receives `ACTION_SEND` and `ACTION_VIEW` intents. Windows accepts URLs passed as command-line arguments and supports pasted links in the Library dialog. File/protocol association can be added by the installer once product identifiers are finalized.
