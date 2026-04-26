# Waze Alerts Notifier

Android/Kotlin prototype for nearby road-alert notifications.

Current version: `0.5.0` (`versionCode 6`).

## What works

- Kotlin Android app with dashboard and settings screens.
- User-configurable monitoring, notifications, radius, refresh time, live sources, demo source, and alert-type filters.
- Experimental Waze Live Map alert source using radius-based bounding boxes.
- Optional TomTom Traffic API source for global traffic incidents when an API key is saved.
- Foreground location service for background monitoring.
- Android notification channels for monitoring and road alerts.
- Android Auto template service under the POI category.
- Waze Deep Link on alert notifications: tapping an alert opens Waze or Waze Live Map at that alert location.
- Reverse-geocoded alert addresses in the phone UI, phone notifications, and Android Auto.
- Main dashboard for radius, refresh time, active alerts, navigation, and per-alert mute controls.
- Appearance setting with System, Light, and Dark modes.
- Compact modern UI with status chips, grouped controls, and alert cards.

## Alert Sources

The app uses `AlertProvider` as a replaceable source boundary:

- `WazeLiveMapAlertProvider` calls `https://www.waze.com/live-map/api/georss` with the current location, configured radius, and auto-selected `na`, `il`, or `row` environment. It maps Waze police, camera, hazard, accident, jam, and closure reports into app categories.
- `TomTomTrafficAlertProvider` calls TomTom Traffic API v5 `incidentDetails` when a TomTom API key is saved in Settings. This is the more stable global source for accidents, jams, closures, roadworks, and hazards.
- `DemoAlertProvider` generates test alerts around the current location. It is off by default for new installs.

Official Waze developer documentation still does not expose a stable public read API for nearby Waze user reports. The Waze Live Map provider is experimental and may return HTTP 403 or change without notice. If it fails, the app keeps running and falls back to other enabled sources.

## Build

```powershell
.\gradlew.bat assembleDebug
```

The debug APK is generated at:

```text
app\build\outputs\apk\debug\app-debug.apk
```

## Install for local testing

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Open the app, grant permissions, and enable background monitoring. On Android 11+ background location may need to be granted from the system app settings screen.

## App Screens

- Main screen: radius slider, refresh time slider, active alert list, per-alert `Navigate`, and per-alert `Mute` / `Unmute`.
- Settings screen: appearance mode, Waze Live Map source, optional TomTom API key, background monitoring, global notifications, demo alert source, alert type switches, and permission/system settings shortcuts.

## Android Auto

The app declares an `androidx.car.app.category.POI` `CarAppService` and shows nearby alerts in a car-safe list template. For production distribution through Google Play, validate the app against the current Android for Cars quality requirements.

## Release

Release tags use `v<versionName>`. The current debug release asset should be named:

```text
WazeAlertsNotifier-debug-v0.5.0.apk
```
