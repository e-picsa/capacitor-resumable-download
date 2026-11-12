# Project Roadmap, Architecture & Testing Strategy

This document serves as the **central source of truth** for architectural strategy, Capacitor versioning, the Android testing matrix, immediate code improvements, and long-term E2E test automation for `@picsa/capacitor-resumable-download`.

---

## 1. Capacitor Major Versioning & Branching Strategy

### The Capacitor 8 vs. Capacitor 9 Landscape

- **Why Capacitor 9 Bumps `minSdkVersion` to 26 (Android 8.0)**:
  - **AndroidX & AGP Baselines**: Google's core libraries (`androidx.activity:1.13.0`, `androidx.appcompat:1.8.0`) and AGP 9.4+ enforce API 26 as their runtime floor.
  - **Java 8+ Bytecode & Desugaring**: API 26 introduced native ART support for `java.time`, `java.nio.file`, and default interface methods without requiring complex D8 core library desugaring backports.
  - **System WebView Status**: Android 7.0/7.1 devices lost Google Chrome support in late 2023 (Chrome 119 was the final release).
- **For Our Production Application (e-PICSA)**:
  - **Hold on Capacitor 8.x**: Because e-PICSA serves smallholder farmers across Sub-Saharan Africa who rely on budget secondary-market hardware (Android 7.0/7.1, API 24/25) and offline sideloaded APKs, the application will remain on Capacitor 8.
  - **Google Play Target SDK Compliance**: When Google Play requires SDK 37 in late 2025/2026, we increment `compileSdkVersion = 37` and `targetSdkVersion = 37` in `android/variables.gradle` on the Capacitor 8 base, rather than prematurely abandoning API 24/25 users.

### Plugin Strategy: Single-Branch Dual Support (Cap 8 & Cap 9)

Rather than splitting into fragmented `8.x` and `9.x` branches, the plugin will maintain a **single codebase** supporting both Capacitor 8 and Capacitor 9:

1. **Widened Peer Dependencies**:
   ```json
   "peerDependencies": {
     "@capacitor/core": "^8.0.0 || ^9.0.0"
   }
   ```
2. **Dynamic Fallback in `android/build.gradle`**:
   ```groovy
   defaultConfig {
       minSdkVersion project.hasProperty('minSdkVersion') ? rootProject.ext.minSdkVersion : 24
   }
   ```
   Because host applications inject `rootProject.ext.minSdkVersion`, a Capacitor 8 app compiles the plugin at API 24, while a Capacitor 9 app compiles it at API 26.
3. **No Deprecated Bridge APIs**: The plugin strictly utilizes `@CapacitorPlugin` annotations and modern `PluginCall` patterns, avoiding the deprecated `@NativePlugin` internals dropped in Capacitor 9.
4. **Release Please & Branching**:
   - `main` remains the primary release branch tracked by Release Please.
   - Dual-branch maintenance (`8.x` vs `9.x`) is avoided unless a future AGP or Kotlin toolchain breaking change makes single-branch compilation impossible.

---

## 2. Core Android Test Matrix

Testing every minor Android version is unnecessary because behavioral constraints accumulate monotonically. The testing suite focuses on **4 strategic OS targets** plus **1 physical OEM family**:

| Target                                    |    API Level    | Architectural Validation Focus                                                                                                                                                       |
| :---------------------------------------- | :-------------: | :----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Android 7.0 (Legacy Floor)**            |   **API 24**    | Tests `tryNioAtomicMove` reflection fallback to `File.renameTo()`, pre-channel notifications, and older CA root store behavior (see TLS caveat below).                               |
| **Android 12 (FGS Restrictions)**         |   **API 31**    | Tests background start restrictions (`ForegroundServiceStartNotAllowedException`), validating the worker's `runCatching` fallback to in-app execution (`FOREGROUND_SERVICE_FAILED`). |
| **Android 13 (Notification Perms)**       |   **API 33**    | Tests runtime `POST_NOTIFICATIONS` permission prompt handling, ensuring background WorkManager execution continues unimpeded if notifications are denied.                            |
| **Android 14/15 (Strict FGS & Timeouts)** |  **API 34/35**  | Tests strict `FOREGROUND_SERVICE_TYPE_DATA_SYNC` enforcement and Android 15's 6-hour cumulative runtime limit on foreground data synchronization.                                    |
| **Physical OEM Device (Bonus)**           | **Android 14+** | **Samsung (OneUI) or Xiaomi (HyperOS)**: Tests real-world screen-off socket preemption and battery saver background task termination.                                                |

> [!WARNING]
> **Android 7.0 (API 24) TLS / Let's Encrypt & R2 Quirk**: Android 7.0's 148-cert system trust store contains no ISRG Root X1/X2, Let's Encrypt, or Cloudflare root certificates. All modern-CA HTTPS downloads fail with `SSLHandshakeException` on API 24. For Android 7.0 testing, always use the local cleartext HTTP test server (`http://10.0.2.2:8080`), and use Android 7.1+ for remote HTTPS scenarios. Full details are consolidated in [`docs/known-issues-and-caveats.md`](docs/known-issues-and-caveats.md) and [`docs/demo-testing.md`](docs/demo-testing.md) §6c.

---

## 3. Automated E2E & CI Roadmap

```
           ▲
          / \     Tier 3: Nightly Multi-Version Matrix (API 24, 31, 33, 34)
         /---\    • Screen off / Doze simulation, network toggles, permission denial
        /     \   Tier 2: Single-Emulator CI E2E (API 34 on every PR)
       /-------\  • Throttled local server, start/pause/resume/cancel, .part lifecycle
      /         \ Tier 1: Deterministic Host JVM Tests (< 1s on every commit)
     /___________\ • Pure Kotlin + OkHttp MockWebServer (RFC 9110 rules, byte-ranges)
```

### Phase 1: Local Test Server & Deterministic Tooling (Delivered)

- **Deterministic HTTP Server** (`scripts/serve-test-file.mjs`): Supports `?throttle_kbps=`, `?throttle_ms=`, strong ETags, and graceful client disconnect resolution.
- **Sync Guard Script** (`scripts/sync-plugin.mjs --check`): Prevents stale `node_modules` copies in `example-app` via `yarn demo:sync` and `yarn demo:build`.
- **Agent Testing Guide** (`docs/demo-testing.md`): Step-by-step automation instructions via `agent-device` and logcat inspection.

### Phase 2: Single-Emulator CI E2E (Next Milestone)

- Set up a GitHub Actions workflow with `reactivecircus/android-emulator-runner` (API 34, Ubuntu KVM).
- Launch `scripts/serve-test-file.mjs` on the host runner.
- Route emulator network to host via `http://10.0.2.2:8080`.
- Execute automated JS test suite via headless WebView or ADB driver asserting:
  1. Start, throttle, pause at 25% progress.
  2. Disk assertion via `getFileStatus()`: `.part` exists, target does not exist.
  3. Resume: sends `Range: bytes=<savedLength>-`, finishes download.
  4. Final disk assertion: target exists with exact SHA-256 checksum, `.part` and sidecar removed.
  5. Cancel: immediately purges all partial files from disk.

### Phase 3: Nightly Multi-Version Matrix & Network Drop Simulation

- Scheduled nightly workflow across **API 24, 31, 33, and 34**.
- Simulates connectivity drops mid-stream via `adb shell svc wifi disable` and verifies resilient resumption upon `adb shell svc wifi enable`.
- Validates notification permission denial flows on API 33+.

---

## 4. Current Code Audit & Implementation Tracking

The following improvements were identified during the codebase audit and dispatched for immediate implementation:

### High Priority (Functional & Crash Fixes)

1. **WorkManager Wiping Completion Notification**:
   - `DownloadWorker.kt`: `showCompletionNotification` currently uses `notificationId`, which WorkManager cancels as soon as `doWork()` finishes.
   - _Fix_: Use a separate ID for completion (e.g. `(notificationId + 1)` or `"done_$downloadId".hashCode() and 0x7fffffff`).
2. **Missing `setContentIntent` on Notification Tap**:
   - `DownloadWorker.kt`: Tapping the foreground or completion notification currently does nothing.
   - _Fix_: Attach `PendingIntent` using `context.packageManager.getLaunchIntentForPackage(...)`.
3. **Permission Alias Mismatch (`display` vs `notifications`)**:
   - `ResumableDownloadPlugin.kt`: `checkPermissions` and `requestPermissions` return `{ "display": "granted" }` on pre-API 33 devices, whereas the plugin declares `alias = "notifications"`.
   - _Fix_: Change `"display"` to `"notifications"`.
4. **Bridge Flooding & Notification Rate Limiting**:
   - `HttpRangeDownloader.kt`: Throttling condition `if (elapsed >= 250 || delta >= 1)` bypasses the 250ms limit on fast transfers.
   - _Fix_: Throttle active streaming strictly by time (`if (elapsed >= 250)`).
5. **Malformed URL `IllegalArgumentException`**:
   - `HttpRangeDownloader.kt`: OkHttp's `Request.Builder().url()` throws `IllegalArgumentException` on invalid URLs, which currently crashes the coroutine without reporting error status.
   - _Fix_: Wrap URL builder in `try/catch` and return `DownloadOutcome.Failure(..., canResume = false)`.

### Medium Priority (Lifecycle & Edge Cases)

6. **Notification Small Icon Customization**:
   - Add optional `icon?: string` to `NotificationOptions` allowing applications to pass a custom drawable name, with fallback to system drawables.
7. **`notificationId` Hash Code Zero / Overflow**:
   - `notificationId = (downloadId.hashCode() and 0x7fffffff).coerceAtLeast(1)`.
8. **`cancel()` vs. Active Worker Race**:
   - Check `if (isCancelled())` immediately before creating or writing to the `.part` file in `HttpRangeDownloader`.

---

## 5. Architectural Invariants & AI Governance

To maintain velocity while preserving codebase stability across multi-agent turns:

1. **Strict 3-File Native Architecture**:
   - `android/src/main/java/io/picsa/plugins/download/HttpRangeDownloader.kt` (pure Kotlin, 0 Android imports).
   - `android/src/main/java/io/picsa/plugins/download/DownloadWorker.kt` (WorkManager execution engine).
   - `android/src/main/java/io/picsa/plugins/download/ResumableDownloadPlugin.kt` (Capacitor bridge).
2. **Fast JVM Feedback**: Always verify core download logic via `./gradlew test` (runs in < 1 second).
3. **Synchronized Documentation**: Any change to API parameters or runtime behavior must update [`README.md`](file:///Users/chrismclarke/Apps/e-picsa/capacitor-resumable-download/README.md), [`AGENTS.md`](file:///Users/chrismclarke/Apps/e-picsa/capacitor-resumable-download/AGENTS.md), [`docs/demo-testing.md`](file:///Users/chrismclarke/Apps/e-picsa/capacitor-resumable-download/docs/demo-testing.md), and [`docs/known-issues-and-caveats.md`](file:///Users/chrismclarke/Apps/e-picsa/capacitor-resumable-download/docs/known-issues-and-caveats.md).
