# codex.md

Codex should treat this repo as Traffic Alerts Notifier, a small Android/Kotlin app with release artifacts published manually through GitHub releases.

## Build Commands

```powershell
.\gradlew.bat assembleDebug
```

Debug APK:

```text
app\build\outputs\apk\debug\app-debug.apk
```

Current Android version: `0.9.10` / `versionCode 20`.

## GitHub Workflow

- Use SSH remotes for git operations.
- Avoid pushing from the parent `C:\Users\michael.guber` git repo; this project has its own nested repository.
- If GitHub CLI reports an invalid `GITHUB_TOKEN`, clear that environment variable for the command and rely on the keyring login.

## Implementation Notes

- Real alert ingestion belongs behind `AlertProvider`.
- Alert address display is resolved centrally in `AlertRepository` with Android `Geocoder`.
- `MainActivity` is the dashboard; avoid putting global toggles back on the main screen unless the user asks.
- `SettingsActivity` is the settings surface for appearance, live sources, monitoring, notification, demo source, category filters, and permission shortcuts.
- Appearance is handled by `ThemeMode` and `UiPalette`; keep new View UI colors routed through that palette.
- Keep the phone UI compact and dashboard-like: status chips, grouped controls, and alert cards.
- Per-alert mute state is stored through `AlertStore` and must be checked before posting notifications.
- `WazeLiveMapAlertProvider` uses the unofficial Waze Live Map GeoRSS endpoint from `guberm/waze-alerts-monitor`; it must fail closed to an empty list because the endpoint can return 403 or change without notice.
- `OpenStreetMapCameraProvider` uses Overpass API for fixed speed/red-light cameras; keep radius capped and fail closed because public Overpass instances rate-limit and can be unavailable.
- `TomTomTrafficAlertProvider` is the keyed global traffic provider for incidents, roadwork, jams, and hazards.
- The demo provider is for local testing only and is off by default for new installs.
- Public Waze documentation supports Deep Links, not a stable public read API for live Waze police/camera/roadwork alerts.
- Android Auto support should stay notification-only through `AlertMonitorService` and `CarAppExtender` / `CarNotificationManager`; do not add `MediaBrowserService`, `MediaSessionCompat`, `automotive_app_desc`, or `CarAppService` unless the user explicitly accepts a full Android Auto app surface.
- Active Android Auto alert notifications should be updated with live direction arrow and distance using the same notification ID, with `setOnlyAlertOnce(true)` to avoid repeated alert sounds.
- `AlertsCarAppService` was deleted in `0.9.8`; keep Android Auto notification-only unless the user explicitly accepts a full Android Auto app surface.
- `AlertMonitorService` recalculates saved alert distances on every live location update and refreshes remote alert data only on the configured interval.
- `AlertMonitorService` throttles UI broadcasts while moving so distance-only updates stay live without repainting the screen every location tick.
- Background monitoring intentionally separates `cached_alerts` from visible `active_alerts`: fetch wide, cache briefly, then write only current in-radius alerts to the UI/Android Auto list.
- Release `0.9.7` contains the media-browser-only Android Auto entry, direction arrows for every alert, and live distance updates without waiting for remote refresh.
- Release `0.9.8` adds modernized View UI, configurable movement-cache settings, Android Auto title-level direction/distance display, and removes the fallback CarAppService.
- Release `0.9.9` media-playback Android Auto integration was a bad fit because it showed as a player and still used the large pane; current implementation should remain notification-only.
- Release `0.9.10` removes the Android Auto media-player surface, keeps Android Auto alert delivery notification-only, updates active alert notifications with live direction/distance, and keeps the phone dashboard awake while open.
- `MainActivity` keeps the phone screen awake while the dashboard is open.
- Google Maps navigation detection is notification-listener based. The app cannot read Google Maps route geometry, so route alerts are approximated by monitoring live device position while Maps navigation is active.
