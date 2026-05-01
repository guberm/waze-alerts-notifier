<claude-mem-context>
# Memory Context

# [Waze Alerts Notifier] recent context, 2026-05-01

Legend: 🎯session 🔴bugfix 🟣feature 🔄refactor ✅change 🔵discovery ⚖️decision 🚨security_alert 🔐security_note
Format: ID TIME TYPE TITLE
Fetch details: get_observations([IDs]) | Search: mem-search skill

Stats: refreshed manually from current repo work

### May 1, 2026
1972 🔴 v0.9.11 fixes jerky countdown and live distance UI updates by updating labels in place instead of rebuilding the full phone screen.
1973 🔴 Phone alert arrow/distance now lives in a dedicated adjacent card aligned with the alert notification card height.
1974 🔴 Android Auto alert notifications now post as ongoing navigation-category car notifications and are no longer gated by Google Maps navigation detection.

### Apr 30, 2026
1943 1:57p 🔴 Direction arrow missing in phone UI alert chips
1944 " 🔴 Direction + distance added to Android Auto media item titles
1945 " 🟣 Movement cache parameters made user-configurable via Settings
1946 " ✅ AlertsCarAppService permanently deleted from codebase
1947 " ✅ Released Waze Alerts Notifier v0.9.8 to GitHub
1956 " 🔵 Pocket Casts Android Auto reference: foregroundServiceType and media metadata are required
1957 " 🔴 Android Auto 66% screen fix: foregroundServiceType + media metadata + PAUSED state
1965 " 🔵 Android Auto v0.9.9 fix confirmed ineffective — still 66% and now shows as player
1966 5:29p 🔵 AlertsMediaBrowserService.kt v0.9.9 full implementation confirmed via source read
1967 " 🔵 New Claude session replayed the entire v0.9.9 release flow after context compaction
1968 5:30p 🔵 automotive_app_desc.xml declares media use — root cause candidate for Android Auto player classification
1969 " 🔵 AlertMonitorService.kt v0.9.9 full implementation confirmed — broadcast throttle constants
1970 5:44p 🔴 Android Auto corrected to notification-only dynamic alerts; media player surface removed
1971 " ✅ Phone dashboard now keeps screen awake while open

Access 713k tokens of past work via get_observations([IDs]) or mem-search skill.
</claude-mem-context>
