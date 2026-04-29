# codex.md

Codex should treat this repo as a small Android/Kotlin app with release artifacts published manually through GitHub releases.

## Build Commands

```powershell
.\gradlew.bat assembleDebug
```

Debug APK:

```text
app\build\outputs\apk\debug\app-debug.apk
```

Current Android version: `0.9.4` / `versionCode 14`.

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
- Android Auto support has two surfaces: `AlertsCarAppService` as an `androidx.car.app.category.POI` template app, and `AlertsMediaBrowserService` as the media-section browser service.
- `AlertsCarAppService` declares `minCarApiLevel=6` so Android Auto Coolwalk renders it as a native side panel (~1/3 of screen). The media strip automatically shares that panel when music is playing. Dropping below level 6 reverts to the full-screen compatibility mode.
- Google Maps navigation detection is notification-listener based. The app cannot read Google Maps route geometry, so route alerts are approximated by monitoring live device position while Maps navigation is active.
