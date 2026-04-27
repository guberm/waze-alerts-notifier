# CLAUDE.md

This repository is an Android/Kotlin prototype for a Waze-adjacent road alert notifier.

## Current Scope

- Package: `com.mg.wazealerts`
- Current app version: `0.7.0` / `versionCode 8`
- Build target: Android SDK 36
- Minimum Android SDK: 26
- Main artifact for release testing: debug APK from `app/build/outputs/apk/debug/app-debug.apk`

## Architecture Notes

- `MainActivity` is the operational dashboard for radius, refresh time, active alerts, navigation, and per-alert mute controls.
- `SettingsActivity` owns appearance, live sources, background monitoring, notification, demo source, alert type, and permission controls.
- `ThemeMode` and `UiPalette` provide System, Light, and Dark rendering for the View-based UI.
- The phone UI intentionally uses compact status chips, grouped control panels, and repeated alert cards rather than large plain settings rows.
- `AlertMonitorService` is a foreground location service and posts alert notifications.
- `AlertsCarAppService` exposes an Android Auto POI template screen.
- Alert data is intentionally behind `AlertProvider`.
- `AlertRepository` enriches provider alerts with reverse-geocoded addresses before display.
- `AlertStore` persists the latest active alerts and muted alert IDs.
- `WazeLiveMapAlertProvider` calls the unofficial Waze Live Map GeoRSS endpoint with radius-derived bbox and `na`/`il`/`row` environment selection.
- `OpenStreetMapCameraProvider` calls Overpass API for fixed speed/red-light camera data from OpenStreetMap tags and caps searches at 25 km.
- `TomTomTrafficAlertProvider` calls TomTom Traffic API v5 when a user saves a TomTom API key.
- `DemoAlertProvider` generates local test alerts and is off by default for new installs.

## Waze Integration Boundary

Use official Waze Deep Links for opening Waze/Live Map at an alert location. The user explicitly accepted the permission/terms risk for an experimental Waze Live Map source; keep that provider isolated and fail closed because it can return 403 or change without notice.

The stable production path is the keyed TomTom/HERE provider or another authorized traffic incident source. Waze Live Map and public Overpass are best-effort only.

## Release Checklist

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Update `README.md`, `codex.md`, and this file if behavior or limitations changed.
3. Run `.\gradlew.bat assembleDebug`.
4. Confirm `app/build/outputs/apk/debug/app-debug.apk` exists.
5. Commit and push via SSH.
6. Create a GitHub release tag matching `v<versionName>` and attach the debug APK.
