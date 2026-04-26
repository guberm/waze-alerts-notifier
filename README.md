# Waze Alerts Notifier

Android/Kotlin prototype for nearby road-alert notifications.

Current version: `0.3.0` (`versionCode 4`).

## What works

- Kotlin Android app with a simple settings screen.
- User-configurable monitoring, notifications, radius, demo source, and alert-type filters.
- Foreground location service for background monitoring.
- Android notification channels for monitoring and road alerts.
- Android Auto template service under the POI category.
- Waze Deep Link on alert notifications: tapping an alert opens Waze or Waze Live Map at that alert location.
- Reverse-geocoded alert addresses in phone notifications and Android Auto.
- Main dashboard for radius, refresh time, active alerts, navigation, and per-alert mute controls.
- Separate settings screen for background monitoring, notifications, demo source, alert type filters, and permissions.

## Waze data limitation

The public Waze documentation currently exposes Deep Links for launching Waze and partner/transport integrations, but not a public API for reading nearby Waze user alerts such as police, cameras, hazards, or roadworks.

Because of that, the app uses `AlertProvider` as a replaceable source boundary:

- `DemoAlertProvider` generates test alerts around the current location.
- `WazeOfficialAlertProvider` is intentionally empty until a permitted Waze partner API/feed/SDK source is available.

Do not wire this app to unofficial Waze Live Map endpoints without confirming permission and terms. The correct production path is to replace `WazeOfficialAlertProvider` with an authorized data source.

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

## App screens

- Main screen: radius slider, refresh time slider, active alert list, per-alert `Navigate`, and per-alert `Mute` / `Unmute`.
- Settings screen: background monitoring, global notifications, demo alert source, alert type switches, and permission/system settings shortcuts.

## Android Auto

The app declares an `androidx.car.app.category.POI` `CarAppService` and shows nearby alerts in a car-safe list template. For production distribution through Google Play, validate the app against the current Android for Cars quality requirements.

## Release

Release tags use `v<versionName>`. The current debug release asset should be named:

```text
WazeAlertsNotifier-debug-v0.3.0.apk
```
