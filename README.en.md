# Imminence

[简体中文](README.md) · **English**

A tiny overlay that shows, at all times, how long you have spent in the app you are currently
looking at — counted for today.

One draggable pill: `45min`, and `1h12min` once you pass an hour.

## Features

- **Foreground only.** Whatever app is in front, the pill shows its accumulated time today.
- **Battery first.** No wake locks, no 1 Hz polling, no network permission. Everything stops
  when the screen goes off.
- **Self-healing.** A killed process, a reboot or a manual restart never loses or double-counts
  a second — the day is recomputed from the system event log.
- **Fully local.** No internet permission at all.

## How it works

The displayed unit is minutes, so the app never needs a per-second timer.

1. `UsageStatsManager.queryEvents()` yields exact timestamps for `ACTIVITY_RESUMED`,
   `SCREEN_INTERACTIVE` / `SCREEN_NON_INTERACTIVE` and the keyguard events.
2. `TimelineEngine` replays them into intervals: when an event arrives at `ts`,
   `[previous event, ts)` is credited to the app that was in the foreground.
3. The in-progress slice is added on read, so the number grows by itself with no extra wake-ups.
4. `MinuteTicker` schedules exactly one `postDelayed` for the moment the counter crosses the
   next whole minute.
5. With the screen off there is no timer at all — and none is needed, because Doze only ever
   applies while the screen is off. Requirement and system scheduling boundary coincide.

## Permissions

| Permission | Purpose | Required |
| --- | --- | --- |
| Usage access | The only data source: app switch events | Yes |
| Display over other apps | The overlay window | Yes |
| Notifications | Foreground service notification (Android 13+) | Recommended |
| Ignore battery optimizations | Stay unfrozen when idle; lets the watchdog restart the service | Recommended |
| Autostart | Restore tracking after a reboot | Depends on ROM |

## Requirements

Android 8.0 (API 26) or newer.

## Build

The repository intentionally ships without the Gradle wrapper binary. Either:

```bash
gradle wrapper --gradle-version 8.9   # once, if you have a local Gradle
./gradlew :app:assembleDebug
```

or just open the project in Android Studio, which adds the wrapper on first sync.

```bash
./gradlew :app:testDebugUnitTest      # TimelineEngine / DaySplit unit tests
```

## Layout

```
core/     TrackerService, TimelineEngine, ForegroundSampler, MinuteTicker, BootReceiver, WatchdogReceiver
data/     UsageStore (memory + SQLite), Days, DaySplit, Prefs
overlay/  OverlayController (single pill window, drag, long-press to hide)
perm/     Permissions (usage access, overlay, notifications, battery, autostart)
ui/       MainActivity (onboarding + start/stop)
```

## Known limitations

- **Split screen / picture-in-picture**: `ACTIVITY_RESUMED` fires for both windows, so the
  "last resumed" heuristic can attribute time to the wrong side. A platform API limitation.
- **Midnight**: a session that began before local midnight is not replayed after the process
  restarts; only the current day is reconstructed.
- **OEM ROMs**: some systems kill foreground services regardless. The boot receiver,
  `START_STICKY` and a 15-minute watchdog are the fallbacks.

## License

MIT — see [LICENSE](LICENSE).
