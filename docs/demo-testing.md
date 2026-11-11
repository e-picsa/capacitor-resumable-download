# Demo App Agent Testing Guide

This guide gives AI agents (and humans) deterministic tooling to interact with the
example app on the `Medium_Phone` emulator and capture logs without manual tapping.

## 1. Prerequisites

```bash
yarn demo:build    # plugin build -> sync-plugin -> example-app build -> cap sync -> assembleDebug
yarn demo:install  # adb install (add `-s emulator-5554` if multiple devices)
```

Never run `npx cap sync` / `./gradlew assembleDebug` directly after editing
`android/` or `dist/` — yarn v1 copies `file:..` deps, so always go through
`yarn demo:sync` (refreshes the example-app copy) or the build fails fast on staleness.

App ID: `io.picsa.plugins.download.example`

## 2. Drive the UI (agent-device, no coordinates)

```bash
AGENT=/Users/chrismclarke/.t3/userdata/device/targets/<session>/agent-device
# (re)launch — find current path via device_open if missing
$AGENT open io.picsa.plugins.download.example --relaunch
$AGENT snapshot -i
# @e6=url field, @e18=dest field, @e20=download ID field, @e24=Start, @e25=Pause,
# @e26=Resume, @e27=Cancel, @e28=Get Status, @e29=Check Disk File, @e31=Refresh All
# Task rows: "Select download <id>, status <s>, <p>%" — tap to select (monitor follows selection)

$AGENT fill @e6 "https://proof.ovh.net/files/10Mb.dat" --settle
$AGENT press @e24 --settle          # Start Download (new task per download ID)
$AGENT snapshot -i                  # read task rows + Live Monitor + logConsole
```

Each URL/destination maps to its own download ID (auto-derived from destination,
editable). Switching presets with a different destination targets a different task —
progress, pause, and resume follow the selected row, and each file keeps its own
`.part` resume state. Use Refresh All to re-query native `getStatus` per task.

## Agent snapshot caveats (learned the hard way)

- **First Start prompts for notification permission**: a system dialog
  ("Allow Resumable Download Demo to send you notifications?") appears and blocks
  the download until answered. Accept it with `$AGENT alert accept` (or press Allow),
  then the pending `download()` proceeds.
- **Below-fold content is clipped from snapshots**: the task list's second row and
  the logs-card buttons fall outside the snapshot viewport on small screens.
  `$AGENT scroll down 600 --settle`, then snapshot again to reach them.
- **Refs go stale while downloading**: progress events prepend log DOM nodes every
  ~250ms, so captured refs expire fast (taps then miss or hit the status bar and
  open the notification shade). Pause the active download first for a stable tree,
  or re-snapshot immediately before each tap. If the shade opens, `$AGENT back`
  dismisses it.
- **Task rows prove multi-file state**: `[TASKS] n=2 keys=a,b` in logcat confirms
  the JS registry; if a row isn't in the snapshot, scroll before concluding it's missing.

Preset buttons (`preset10MB`, `preset100MB`, `presetLocal`) fill URL/dest in one tap.
Throttled local preset uses `http://10.0.2.2:8080/large.bin?throttle_kbps=200`
(emulator loopback to host). Start the host server first:

```bash
yarn serve:test-file                # :8080, 50MB, strong ETag, Range + throttle
yarn verify:cdn http://localhost:8080/large.bin
```

## 3. Logs (three layers, all agent-readable)

```bash
# a) Native plugin + WorkManager (root-cause layer)
adb -s emulator-5554 logcat -d -s ResumableDownload:V WM-WorkerWrapper:E Capacitor:V

# b) JS console mirror (every demo log() also console.log()s)
adb -s emulator-5554 logcat -d -s Capacitor/Console:V | grep -E "EXPORT_LOGS|Progress|DIAGNOSTIC|Starting|failed|completed"

# c) In-app logConsole (via snapshot, no adb needed)
$AGENT snapshot -i | grep -A2 logConsole
# or tap "Export Logs" (@e?) to dump full history to logcat between
# [EXPORT_LOGS_BEGIN] ... [EXPORT_LOGS_END] markers
```

Key tags/codes:

- `ResumableDownload` — native `emitDiagnostic` (Log.i/w/e) + progress
- `WM-WorkerWrapper` — WorkManager crashes (e.g. `IllegalArgumentException` in
  `createNotificationChannel` = blank channel ID bug, now fixed)
- Diagnostic codes: `FOREGROUND_SERVICE_FAILED`, `NOTIFICATION_PERMISSION_MISSING`,
  `RANGE_RESET_200`, `RANGE_NOT_SATISFIABLE_416`, `WEAK_ETAG_DETECTED`,
  `WORKER_PREEMPTED`, `SOCKET_DROP_RESUMABLE`, `NOTIFICATION_CHANNEL_FAILED`

## 4. Known failure signatures

| Symptom                     | Log signature                                                                 | Cause / fix                                                                                                   |
| --------------------------- | ----------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| Instant ERROR, 0 bytes      | `WM-WorkerWrapper ... IllegalArgumentException ... createNotificationChannel` | Blank `channelId` from `JSONObject.optString` → fixed by `getString+ifBlank` + worker default + `runCatching` |
| Instant ERROR, HTTP 403     | `HttpException: HTTP 403` / `Download failed`                                 | Dead demo URL (`commondatastorage...BigBuckBunny.mp4` now 403) → use `proof.ovh.net` presets or local server  |
| Completes too fast to pause | progress jumps 0→100 in <1s                                                   | File too small/fast → use 100MB preset or `?throttle_kbps=200` local server                                   |

## 5. Screenshot

```bash
# Prefer device_screenshot tool for viewing; agent-device screenshot for files:
$AGENT screenshot /tmp/demo.png
```
