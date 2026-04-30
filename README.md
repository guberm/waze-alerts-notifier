# Traffic Alerts Notifier

Android/Kotlin prototype for nearby road-alert notifications.

Current version: `0.9.8` (`versionCode 18`).

## What works

- Kotlin Android app with dashboard and settings screens.
- User-configurable monitoring, notifications, radius, refresh time, live sources, demo source, and alert-type filters.
- Experimental Waze Live Map alert source using radius-based bounding boxes.
- OpenStreetMap/Overpass fixed-camera source for speed cameras and red-light cameras.
- Optional TomTom Traffic API source for global traffic incidents when an API key is saved.
- Foreground location service for background monitoring.
- Android notification channels for monitoring and road alerts.
- Android Auto support through the media-browser entry, so alerts stay in the media-side surface.
- Google Maps navigation notification detection for route-adjacent native alerts around the live device position.
- Waze Deep Link on alert notifications: tapping an alert opens Waze or Waze Live Map at that alert location.
- Reverse-geocoded alert addresses in the phone UI, phone notifications, and Android Auto.
- Wide movement cache: while monitoring, the app fetches a larger alert area and only displays currently relevant alerts inside the selected radius.
- Movement cache controls in Settings for cache time, min/max cache radius, radius expansion, and visible alert limit.
- Direction + live distance chips in the phone UI and Android Auto media list rows.
- Main dashboard for radius, refresh time, active alerts, navigation, and per-alert mute controls.
- Appearance setting with System, Light, and Dark modes.
- Compact modern UI with status chips, grouped controls, and alert cards.

## Alert Sources

The app uses `AlertProvider` as a replaceable source boundary:

- `WazeLiveMapAlertProvider` calls `https://www.waze.com/live-map/api/georss` with the current location, configured radius, and auto-selected `na`, `il`, or `row` environment. It maps Waze police, camera, hazard, accident, jam, and closure reports into app categories.
- `OpenStreetMapCameraProvider` calls Overpass API for fixed camera data tagged as `highway=speed_camera` or `type=enforcement` with `enforcement=maxspeed`, `traffic_signals`, `red_light_camera`, or `average_speed`. It is enabled by default and caps its Overpass search radius at 25 km.
- `TomTomTrafficAlertProvider` calls TomTom Traffic API v5 `incidentDetails` when a TomTom API key is saved in Settings. This is the more stable global source for accidents, jams, closures, roadworks, and hazards.
- `DemoAlertProvider` generates test alerts around the current location. It is off by default for new installs.

Official Waze developer documentation still does not expose a stable public read API for nearby Waze user reports. The Waze Live Map provider is experimental and may return HTTP 403 or change without notice. If it fails, the app keeps running and falls back to other enabled sources. OpenStreetMap camera coverage depends on local mapping quality and represents fixed cameras, not temporary/mobile police traps.

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

- Main screen: radius slider (crash-safe drag), refresh time slider, active alert list, per-alert `Navigate`, and per-alert `Mute` / `Unmute`.
- **Navigation panel** (auto-shown when Google Maps is navigating): current geocoded address, route step text, nearest alert with distance, bearing direction chip, and "Ahead only" toggle.
- **Log screen** ("Log" button in header): in-app log viewer with Clear / Copy / Export.
- Settings screen: appearance mode, Waze Live Map source, OpenStreetMap cameras, optional TomTom API key, background monitoring, global notifications, demo alert source, alert type switches, and permission/system settings shortcuts.

## Android Auto

The app declares an Android media browser service so Android Auto treats road alerts as a media-side surface instead of opening the app as the large template pane. The media browser exposes the latest stored active alerts as playable items with live distance and direction; selecting one opens the Waze deep link for that alert.

Android Auto and the head unit still own final split-screen sizing, but the app no longer registers a template `CarAppService` entry that can be promoted into the larger template pane.

The APK no longer contains the fallback `CarAppService` class, leaving only the media browser service as the Android Auto entrypoint.

Google Maps route geometry is not exposed to third-party apps. When notification access is granted, `MapsNavigationListener` detects active Google Maps navigation notifications, starts monitoring if enabled, and `AlertMonitorService` posts native road-alert notifications around the live device position.

During movement, remote providers are queried with a wider cache radius than the visible radius. Cached alerts live briefly and are re-filtered on every location update, so Android Auto and the phone UI show only current in-radius alerts while the app keeps nearby upcoming alerts ready without another network refresh.

## Release

Release tags use `v<versionName>`. The current debug release asset should be named:

```text
TrafficAlertsNotifier-debug-v0.9.8.apk
```
