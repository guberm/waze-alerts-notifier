<claude-mem-context>
# Memory Context

# [Waze Alerts Notifier] recent context, 2026-05-05

Legend: 🎯session 🔴bugfix 🟣feature 🔄refactor ✅change 🔵discovery ⚖️decision 🚨security_alert 🔐security_note
Format: ID TIME TYPE TITLE

Stats: 17 obs (6,382t read) | 893,242t work | 99% savings

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

### May 1, 2026

1983 8:00a 🔵 Android Auto Navigation UI Bug Report — 3 Issues Identified
1985 " 🟣 Alert card split into direction sidebar + main card layout
1986 " 🟣 Countdown timer updates every second via label patching instead of full re-render
1987 " 🔴 Android Auto notifications now fire without requiring active navigation state
1984 8:01a 🔵 Waze Alerts Notifier — Codebase Audit for 3 Active Bugs (v0.8.0)

### May 5, 2026

2010 " ✅ Released v0.9.22: XHR georss interception, warmup URL fix, notification tap opens app, nav app setting, FlareSolverr removed from Settings
2011 " 🔴 env=il cache poisoning: Waze init call with zero bbox cached as warmup response → v0.9.23
2012 " 🔵 Root cause: Waze never makes env=na on page load — only env=il init call fires → v0.9.24 injected XHR
2013 " 🔴 403 on injected env=na: our XHR fired before env=il completed (before cookies set) → v0.9.25
2014 " ✅ v0.9.25: env=na queued in wazeSetNaUrl(), fires from env=il load handler after cookies land; header logging added

Access 893k tokens of past work via get_observations([IDs]) or mem-search skill.
</claude-mem-context>
