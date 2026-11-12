# Demo App Agent Testing Guide

This guide gives AI agents (and humans) deterministic tooling to interact with the
example app on the `Medium_Phone` emulator and capture logs without manual tapping.
For pre-Android-8 emulators, see [Android 7 (API 24) testing](#6-android-7-api-24-testing) —
the stock System WebView there is too old to run the demo bundle.

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
# @e6=url field, @e18=dest field, @e24=Start Download.
# Task rows show live progress + throughput with per-row Pause/Resume/Cancel/Delete
# (aria-labelled "<Action> <downloadId>"); rows auto-refresh via progress events
# plus a 3s native getStatus poll — no manual refresh needed.

$AGENT fill @e6 "https://proof.ovh.net/files/10Mb.dat" --settle
$AGENT press @e24 --settle          # Start Download (new task per destination)
$AGENT snapshot -i                  # read task rows + logConsole
$AGENT scroll down 600 --settle     # rows below the fold are clipped from snapshots
```

Each destination path maps to its own task ID (derived automatically, shown readonly
under the path). Switching presets with a different destination targets a different
task — each row shows live progress, status and throughput, and pause/resume/cancel/delete
act per row. Each file keeps its own `.part` resume state, and rows auto-refresh via
progress events plus a periodic native `getStatus` poll.

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

Preset buttons (`10 MB`, `100 MB`, `Local throttled server`) fill URL/dest in one tap.
Throttled local preset uses `http://10.0.2.2:8080/large.bin?throttle_kbps=200`
(emulator loopback to host). Start the host server first:

```bash
yarn serve:test-file                # :8080, 50MB, strong ETag, Range + throttle
yarn verify:cdn http://localhost:8080/large.bin
```

The dot by the `Local throttled server` button shows whether the host test
file server is reachable (tap it for details); it probes
`http://10.0.2.2:8080/health` on launch and every 5s, so the demo itself
tells you whether the local option will work. This needs
`android.allowMixedContent` (demo-only, in `capacitor.config.ts`) plus the
server's CORS header — both already wired.

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

| Symptom                          | Log signature                                                                 | Cause / fix                                                                                                   |
| -------------------------------- | ----------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| Instant ERROR, 0 bytes           | `WM-WorkerWrapper ... IllegalArgumentException ... createNotificationChannel` | Blank `channelId` from `JSONObject.optString` → fixed by `getString+ifBlank` + worker default + `runCatching` |
| Instant ERROR, HTTP 403          | `HttpException: HTTP 403` / `Download failed`                                 | Dead demo URL (`commondatastorage...BigBuckBunny.mp4` now 403) → use `proof.ovh.net` presets or local server  |
| Completes too fast to pause      | progress jumps 0→100 in <1s                                                   | File too small/fast → use 100MB preset or `?throttle_kbps=200` local server                                   |
| Instant ERROR, 0 bytes on API 24 | `CertPathValidatorException: Trust anchor…` / `SSLHandshakeException`         | ISRG Root X1/X2 missing from 7.0 system store → use local HTTP server on API 24 (see §6c)                     |

## 5. Screenshot

```bash
# Prefer device_screenshot tool for viewing; agent-device screenshot for files:
$AGENT screenshot /tmp/demo.png
```

## 6. Android 7 (API 24) testing

Support statement: keep API 24 as the **build** floor (it's already there and
costs nothing), but treat **7.1 as the minimum test target for remote-HTTPS
scenarios**, and accept local-server-only testing on 7.0. Rationale: 7.0's
system trust store lacks the ISRG roots (§6c) — an unfixable platform gap that
7.1 closes — while Capacitor 9 will lift the floor to API 26 (Android 8)
anyway. Don't invest in 7.0-specific workarounds (custom trust anchors, legacy
JS bundles); if `older_device` is ever recreated, prefer a 7.1 image so it
represents the actual minimum supported config instead of the degraded corner.

The native plugin is API-24-safe by design (foreground-service branching,
NIO reflection fallback, `canonicalPath` checks), and `older_device` exists to
prove it. But stock API 24 emulator images ship System WebView **Chrome 53**
(2016), which predates `<script type="module">` (Chrome 61) — so the demo bundle
never executes: taps do nothing and logcat shows
`Uncaught SyntaxError: Unexpected token (` with zero `Plugin listeners ready`.
That is a fossil-WebView artifact, not a plugin bug (real devices update WebView
via the Play Store), so the fix is upgrading the WebView, not a legacy JS build.

The same procedure applies to any pre-Chrome-61 emulator image.

### 6a. Download + sideload Chrome 119 (last major supporting API 24)

```bash
# ~244MB, minAPI24 arm64 build, hosted on Cloudflare R2 (verified 200 OK,
# application/vnd.android.package-archive, strong ETag, installs cleanly)
curl -o /tmp/chrome119.apk https://pub-d7c30ed48e6840d684930ff830ba2819.r2.dev/chrome119.arm64.apk

# Sideload to the old emulator (serials can reshuffle across reboots —
# confirm with `adb devices` / device_list first, don't hardcode)
adb -s <older_device_serial> install -r /tmp/chrome119.apk   # expect: Success

# Switch the WebView implementation from stock to Chrome
adb -s <older_device_serial> shell cmd webviewupdate set-webview-implementation com.android.chrome
adb -s <older_device_serial> shell dumpsys package com.android.chrome | grep versionName
# expect: versionName=119.0.6045.194
```

No demo rebuild or reinstall needed afterwards — relaunch the app and confirm
`Plugin listeners ready.` in logcat with no `SyntaxError`.

### 6c. TLS on 7.0: use the local server, not HTTPS presets

Android 7.0's system trust store predates Let's Encrypt's ISRG roots, so **all
modern-CA HTTPS fails on API 24** with:

```text
java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.
→ exceptionClass=SSLHandshakeException, canResume=false, 0 bytes
```

Verified 2026-11-11 on `older_device`: both `proof.ovh.net` (chain ends at
ISRG Root X1) and the R2 bucket `*.r2.dev` (via ISRG Root X2, cross-signed by
X1) fail identically at the handshake. ISRG Root X1 only entered the system
store in **7.1**, so this affects 7.0 specifically — your memory was right.

Two consequences:

- The Chrome 119 upgrade does **not** fix native downloads: OkHttp uses the
  platform trust store, not Chrome's. The WebView upgrade only fixes the demo
  web UI.
- The R2 bucket does **not** help on 7.0 either. For API 24 testing, use the
  local cleartext server (`http://10.0.2.2:8080/...`, already allow-listed in
  `network_security_config.xml`) — verified working (5MB completed, plus the
  pause/resume/cancel cycle in §6b). The user's local failure was just the
  stopped server, not a device issue.

Rule of thumb: API 24 → local HTTP server only; API 25+ / modern devices →
remote HTTPS presets are fine.

### 6b. Run the download test

Same flow as §2 (use the throttled local server for a pausable window).
Verified on `older_device` 2026-11-11: 5MB completed; 20MB @50KB/s paused at
2.4MB → resumed with `resumedFromBytes=2523136` → cancelled (`.part` purged,
row → CANCELLED) → restarted @2000KB/s to full completion with target file
present (20MB) and no `.part`/sidecar leftovers.

Agent notes specific to the old WebView / snapshot helper:

- Refs renumber between snapshots and taps can silently miss — re-snapshot
  immediately before each `fill`/`press`, and confirm the effect in logcat.
- The readonly Task ID display only refreshes on `input`+`change` events;
  the Start handler always derives fresh from the destination field regardless.
- On API 24 there is no notification-permission dialog (`POST_NOTIFICATIONS`
  doesn't exist pre-33; the plugin reports granted), so Start proceeds directly.
- Delete `chrome119.apk` / `/tmp` copies afterwards — never commit the binary.
