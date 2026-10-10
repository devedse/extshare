# extshare

Quick Settings tile to mirror the main screen to a foldable’s external/cover display. Built for quick toggling on devices like the Motorola Razr 50 Ultra.

## ✨ Features
- Quick Settings tile: tap to start/stop mirroring instantly.
- MediaProjection-based capture for full-screen mirroring.
- External display routing via `Presentation` + `VirtualDisplay`.
- In-app controls to request permission, start, and stop.
- Foreground service with low-priority notification.

## Getting Started

### Requirements
- Android device with external/cover display support (tested target: Razr 50 Ultra).
- Android SDK installed locally (`/home/r/android-sdk` configured in `local.properties`).
- Java 17+.

### Build the APK
```bash
./gradlew assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

### CI builds
GitHub Actions (`.github/workflows/githubactionsbuilds.yml`) builds every push. Each run gets a build number `N` from a tag counter, producing version `1.0.N` (`versionCode = N`), and uploads `ExtShare-1.0.N.apk` as an artifact.

Every push to `main` also publishes a GitHub release `1.0.N` with the APK. To sign it with your own key, add these repository secrets:
- `ANDROID_KEYSTORE_BASE64` — `base64 -w0 release.keystore`
- `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`

Without them the APK is signed with the CI runner's throwaway debug key, which differs per build, so installing a newer release over an older one fails with a signature mismatch (uninstall first).

### Install & Use
1) Install the APK on your device.  
2) Add the “Mirror screen” tile to Quick Settings.  
3) First tap asks for screen-capture permission (required once).  
4) Toggle the tile (or use the app buttons) to mirror to the external display.  
5) Toggle off to stop the foreground service.

### Notes
- If you see a warning about `compileSdk 35`, it is expected with AGP 8.5.0; you can suppress it via `android.suppressUnsupportedCompileSdk=35` in `gradle.properties` if desired.
- Mirroring stops automatically if the external display disappears.

## Project Layout
- `app/src/main/java/org/arnor/extshare` — activities, tile service, media-projection service, presentation.
- `app/src/main/res` — layouts, themes, icons, strings.
- `app/build.gradle.kts` — Android app config and dependencies.

## License
MIT (feel free to adapt for your device or ROM tools).
