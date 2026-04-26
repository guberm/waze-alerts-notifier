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

## GitHub Workflow

- Use SSH remotes for git operations.
- Avoid pushing from the parent `C:\Users\michael.guber` git repo; this project has its own nested repository.
- If GitHub CLI reports an invalid `GITHUB_TOKEN`, clear that environment variable for the command and rely on the keyring login.

## Implementation Notes

- Real alert ingestion belongs behind `AlertProvider`.
- Alert address display is resolved centrally in `AlertRepository` with Android `Geocoder`.
- `MainActivity` is the dashboard; avoid putting global toggles back on the main screen unless the user asks.
- `SettingsActivity` is the settings surface for monitoring, notification, demo source, category filters, and permission shortcuts.
- Appearance is handled by `ThemeMode` and `UiPalette`; keep new View UI colors routed through that palette.
- Keep the phone UI compact and dashboard-like: status chips, grouped controls, and alert cards.
- Per-alert mute state is stored through `AlertStore` and must be checked before posting notifications.
- The demo provider is for local testing only.
- Public Waze documentation supports Deep Links, not reading live Waze police/camera/roadwork alerts.
- Android Auto support is implemented through `androidx.car.app` as a POI template app.
