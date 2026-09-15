# Gen Dex

Gen Dex is a Kotlin + Jetpack Compose Android utility for quickly reaching and, when explicitly authorized, changing Android display scaling so the device reports the requested **Smallest width** value.

## What the project does

- Shows the current `smallestScreenWidthDp` when Android exposes it to the app.
- Shows Android version, manufacturer, model, density and resolution.
- Provides **800 DP — DESKTOP MODE**, **DEFAULT — RESTORE**, custom DP, Developer Settings and Rotation Settings buttons.
- Adds official launcher app shortcuts: **Set 800 DP**, **Restore Previous**, **Developer Settings**.
- Never reports success unless the post-change configuration verifies the requested value.
- Uses Shizuku only after the user explicitly authorizes it, providing a shell/ADB-identity bridge on supported devices.
- Falls back to Android Developer Options when privileged access is unavailable or the device rejects the operation.

Android documents `ACTION_APPLICATION_DEVELOPMENT_SETTINGS` as the intent for application-development-related settings. Android's official shortcut APIs support static, dynamic and pinned shortcuts on supported launchers.

## Direct 800 dp behavior

The privileged path uses the supported shell command `wm density` through Shizuku. It calculates a density override from the physical display's shortest dimension and the requested dp value, then verifies the resulting `Configuration.smallestScreenWidthDp`.

This is intentionally best-effort because manufacturers can customize display scaling. The app will show failure/manual-action status rather than claiming that 800 dp was applied.

## Restore behavior

Before the first Gen Dex change, the app records whether a density override already existed and what density was active. Restore either returns that exact density or calls `wm density reset` when there was no prior override.

## Shizuku setup

1. Install and start Shizuku.
2. Authorize Gen Dex in Shizuku when prompted.
3. On Android 11+, Shizuku can be started using the device's wireless debugging flow; on some configurations a computer/ADB is required.
4. Tap **800 DP — DESKTOP MODE** again.

Shizuku is a separate prerequisite and is not a VPN. Gen Dex does not embed or impersonate a VPN service.

## Build

Open this folder in Android Studio and let Gradle sync. The project targets API 36, minimum API 23, and uses Jetpack Compose.

The environment used to generate this project did not include a local Gradle/Android SDK installation, so the APK has not been compiled here.


## GitHub Actions

The project includes `.github/workflows/main.yml`. On GitHub, open **Actions → Build Gen Dex → Run workflow**. The workflow uses GitHub-hosted Ubuntu runners, Java 17, and Gradle 8.13, then uploads `GenDex-debug-apk`.
