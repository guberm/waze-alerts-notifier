# CLAUDE.md

This repository is an Android/Kotlin prototype for Traffic Alerts Notifier, a Waze-adjacent road alert notifier.

## Current Scope

- Package: `com.mg.wazealerts`
- Current app version: `0.9.17` / `versionCode 27`
- Build target: Android SDK 36
- Minimum Android SDK: 26
- Main artifact for release testing: debug APK from `app/build/outputs/apk/debug/app-debug.apk`

## Architecture Notes

- `MainActivity` is the operational dashboard for active alerts, navigation, and per-alert mute controls.
- `SettingsActivity` owns appearance, scan radius, refresh cadence, live sources, background monitoring, notification, demo source, alert type, and permission controls.
- `ThemeMode` and `UiPalette` provide System, Light, and Dark rendering for the View-based UI.
- The phone UI intentionally uses compact status chips, grouped control panels, and repeated alert cards rather than large plain settings rows.
- Phone alert cards use a dedicated adjacent direction/distance card on the left; keep it aligned with the alert card height rather than moving arrow/distance back into the alert title row.
- `AlertMonitorService` is a foreground location service and posts alert notifications.
- Android Auto support is notification-only: do not register `MediaBrowserService`, `MediaSessionCompat`, `automotive_app_desc`, or `CarAppService` unless the user explicitly accepts a full Android Auto app surface.
- `AlertMonitorService` posts car-compatible alert notifications with live direction arrow, relative direction, distance, address, and Waze deep link.
- Android Auto road-alert notifications must not depend on Google Maps notification detection; Waze/Android Auto notification delivery should work whenever monitoring, location, and notification permission are active.
- `AlertsCarAppService` was removed in `0.9.8`; do not reintroduce a `CarAppService`/template entry unless the user accepts the larger Android Auto template pane behavior.
- `AlertMonitorService` refreshes remote alert data on the configured interval with a wider cache radius, then recalculates and saves only visible active alerts on every live location update.
- `AlertMonitorService` throttles visible-alert UI broadcasts while moving: alert identity changes still broadcast immediately, but distance-only updates are bucketed narrowly enough to keep phone distance labels smooth.
- Release `0.9.7` shipped the Android Auto media-browser-only entry, per-alert direction arrows, and live distance recalculation between remote alert refreshes.
- Release `0.9.8` shipped the modernized phone UI, configurable movement-cache settings, Android Auto title-level direction/distance display, and full removal of the fallback CarAppService.
- Release `0.9.9` first tried media-playback Android Auto integration; that was rejected because it showed as a player and still used the large pane. Keep the corrected path notification-only.
- Release `0.9.10` removes the Android Auto media-player surface, keeps Android Auto alert delivery notification-only, updates active alert notifications with live direction/distance, and keeps the phone dashboard awake while open.
- Release `0.9.11` smooths countdown/distance UI updates without full-screen rerenders, moves phone arrow/distance into an adjacent same-height card, and posts Android Auto alerts as ongoing navigation-category car notifications independent of Google Maps detection.
- Release `0.9.12` moves the Controls panel (scan radius and refresh cadence sliders) from `MainActivity` to `SettingsActivity`, and fixes Android Auto notification delivery by switching from `CarAppExtender`/`CarNotificationManager` (which require a registered `CarAppService`) to `MessagingStyle` with `CATEGORY_MESSAGE`.
- Release `0.9.17` fixes `WazeWebViewFetcher` cookie timing: debounces `onPageFinished` (800ms after last redirect) and extends JS init wait to 5s so Waze session cookies are fully set before the georss fetch.
- Release `0.9.16` replaces FlareSolverr as the default Waze fetch method with a `WazeWebViewFetcher`: a hidden `WebView` that loads `waze.com/live-map/`, waits 2 s for JS cookies to be set, then executes `fetch()` from within the browser context via `evaluateJavascript` + `@JavascriptInterface`; FlareSolverr remains available if a URL is configured. `WazeLiveMapAlertProvider` now takes `Context`; `AlertRepository` exposes `destroy()` which is called from `AlertMonitorService.onDestroy()`.
- Release `0.9.15` adds FlareSolverr session warmup: before the first API call, loads `waze.com/live-map/` via the same session to establish Waze cookies; resets warmup flag on HTML response so the next cycle retries; logs first 300 chars of solution body for diagnosis.
- Release `0.9.14` adds `android:usesCleartextTraffic="true"` to allow HTTP traffic to the FlareSolverr proxy (Android 9+ blocks cleartext by default).
- Release `0.9.13` adds FlareSolverr proxy support to `WazeLiveMapAlertProvider` to bypass Cloudflare/bot-detection on the Waze Live Map API; fixes Android Auto `MessagingStyle` notifications not appearing by adding a required `RemoteInput` reply action via `NotificationActionReceiver`; adds full error logging to `WazeLiveMapAlertProvider` so Waze fetch failures are visible in the in-app log.
- `MainActivity` keeps the screen awake while the phone dashboard is open.
- `MapsNavigationListener` detects active Google Maps navigation notifications; route alerts are approximated around the live device position because Google Maps does not expose third-party route geometry.
- Alert data is intentionally behind `AlertProvider`.
- `AlertRepository` enriches provider alerts with reverse-geocoded addresses before display.
- `AlertStore` persists the latest visible active alerts, a short-lived wider movement cache, muted alert IDs, and passed alert IDs.
- Movement-cache defaults live in `AppSettings`: 20 minutes, 15 km minimum cache radius, 50 km maximum cache radius, 5x radius expansion, and 24 visible alerts.
- `WazeLiveMapAlertProvider` calls the unofficial Waze Live Map GeoRSS endpoint (`waze.com/live-map/api/georss`) with radius-derived bbox and `na`/`il`/`row` environment selection. Default method is `WazeWebViewFetcher` (hidden WebView, JS fetch with cookies). Optional FlareSolverr proxy via `AppSettings.flareSolverrUrl`. All fetch errors logged via `AppLogger` tag `WazeLiveMap`.
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
