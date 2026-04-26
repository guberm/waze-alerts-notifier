# CLAUDE.md

This repository is an Android/Kotlin prototype for a Waze-adjacent road alert notifier.

## Current Scope

- Package: `com.mg.wazealerts`
- Current app version: `0.2.0` / `versionCode 2`
- Build target: Android SDK 36
- Minimum Android SDK: 26
- Main artifact for release testing: debug APK from `app/build/outputs/apk/debug/app-debug.apk`

## Architecture Notes

- `MainActivity` is a lightweight Android View settings screen.
- `AlertMonitorService` is a foreground location service and posts alert notifications.
- `AlertsCarAppService` exposes an Android Auto POI template screen.
- Alert data is intentionally behind `AlertProvider`.
- `DemoAlertProvider` generates local test alerts.
- `WazeOfficialAlertProvider` currently returns no alerts because the public Waze docs do not expose a read API for nearby Waze user alerts.

## Waze Integration Boundary

Use official Waze Deep Links only for opening Waze/Live Map at an alert location. Do not scrape or depend on unofficial Waze Live Map endpoints unless the user explicitly confirms the permission and terms risk.

The correct production path is to replace `WazeOfficialAlertProvider` with an authorized Waze partner feed, SDK, or another permitted incident source.

## Release Checklist

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Update `README.md`, `codex.md`, and this file if behavior or limitations changed.
3. Run `.\gradlew.bat assembleDebug`.
4. Confirm `app/build/outputs/apk/debug/app-debug.apk` exists.
5. Commit and push via SSH.
6. Create a GitHub release tag matching `v<versionName>` and attach the debug APK.
