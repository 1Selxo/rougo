# V2.5 Release Walkthrough

I have successfully prepared and triggered the V2.5 release for Rougo.

## Changes Made

### Build Configuration
- Updated [build.gradle.kts](file:///C:/Users/basel/MyApplication/app/build.gradle.kts) to bump `versionName` to `"V2.5"` and `versionCode` to `14`.

### Documentation
- Updated [CHANGELOG.md](file:///C:/Users/basel/MyApplication/CHANGELOG.md) to include an entry for V2.5 with the following highlights:
    - Stabilized dictionary and playback flows.
    - Collapsed dictionary blocks for improved readability.
    - Improved APK update and download reliability.

## Release Process
1. **Local Build Verification**: Ran `gradle_build(":app:assembleRelease")` to ensure the project still builds correctly with the new version configuration.
2. **Commit and Push**: Committed the version and changelog changes and pushed them to the `master` branch.
3. **Tag and Release Trigger**: Created a git tag `V2.5` and pushed it to GitHub. This triggers the `Build and Release` GitHub Actions workflow defined in `.github/workflows/release.yml`, which will automatically build the APKs and create a GitHub Release.

## Verification Summary
- [x] Version bumped to V2.5 in `build.gradle.kts`.
- [x] Changelog updated with V2.5 entry.
- [x] Local release build succeeded.
- [x] Changes pushed to GitHub `master`.
- [x] Tag `V2.5` pushed to GitHub, triggering the release workflow.
