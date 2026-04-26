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
- The demo provider is for local testing only.
- Public Waze documentation supports Deep Links, not reading live Waze police/camera/roadwork alerts.
- Android Auto support is implemented through `androidx.car.app` as a POI template app.
