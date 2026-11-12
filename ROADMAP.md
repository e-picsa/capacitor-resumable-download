# Project Roadmap, Architecture & Testing Strategy

This document serves as the **central source of truth** for architectural strategy, Capacitor versioning, the Android testing matrix, code improvements, and automated E2E test automation for `@picsa/capacitor-resumable-download`.

---

## Executive Summary Checklist

| Track                                | Milestone                                                 |     Status      | Details / Reference                                                                                                                                                   |
| :----------------------------------- | :-------------------------------------------------------- | :-------------: | :-------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Native Architecture & Resilience** | Strict 3-file native architecture                         |    **Done**     | Zero-Android core (`HttpRangeDownloader.kt`), WorkManager (`DownloadWorker.kt`), Capacitor bridge (`ResumableDownloadPlugin.kt`).                                     |
|                                      | JVM byte-range test suite (< 1s)                          |    **Done**     | Pure Kotlin + MockWebServer in `HttpRangeDownloaderTest.kt` passing 100%.                                                                                             |
|                                      | Sidecar atomic metadata lifecycle                         |    **Done**     | `<dest>.part.meta.json` created alongside `.part`, purged on success/cancel, retained on preemption.                                                                  |
| **Code Audit & Bug Fixes**           | Completion notification wiped by WorkManager              |    **Done**     | Merged in PR #10 (uses distinct notification ID `(notificationId + 1)`).                                                                                              |
|                                      | Bring app to foreground on notification tap               |    **Done**     | Merged in PR #10 (`PendingIntent` attached to foreground & completion notifications).                                                                                 |
|                                      | Pre-API 33 permission alias mismatch                      |    **Done**     | Merged in PR #10 (`checkPermissions` / `requestPermissions` return `"notifications"`).                                                                                |
|                                      | Bridge flooding & notification rate limiting              |    **Done**     | Merged in PR #10 (active streaming throttled strictly to `elapsed >= 250`).                                                                                           |
|                                      | Unhandled `IllegalArgumentException` on bad URLs          |    **Done**     | Merged in PR #10 (caught cleanly, returning `DownloadOutcome.Failure`).                                                                                               |
|                                      | Notification ID hash overflow / zero handling             |    **Done**     | Merged in PR #10 (`(downloadId.hashCode() and 0x7fffffff).coerceAtLeast(1)`).                                                                                         |
|                                      | Cancel vs. active worker file race condition              |    **Done**     | Merged in PR #10 (checked `isCancelled()` before opening `RandomAccessFile`).                                                                                         |
|                                      | Custom notification icon support (`icon?: string`)        | **Backlogged**  | Medium priority; expose optional drawable name in `NotificationOptions`.                                                                                              |
| **Capacitor 8 / 9 Dual Support**     | Widen `peerDependencies` (`^8.0.0 \|\| ^9.0.0`)           | **In Progress** | Active in thread `[Capacitor 9 Compatibility and SDK Version Updates](t3-thread://v2/625e4d3b-cbb2-4fa1-ab4e-6b884550f9f0/mcp:2e0c4683-f142-4ac7-bfd7-18feb56ef936)`. |
|                                      | Dynamic SDK defaults (`minSdk 24`, `compileSdk 37`)       | **In Progress** | Plugin dynamically honors host app SDK settings without separate branches.                                                                                            |
|                                      | Remove deprecated `targetSdkVersion` in library           | **In Progress** | Aligns with AGP 9 build standards.                                                                                                                                    |
| **Testing Tooling & Demo App**       | Local throttled mock server (`serve-test-file.mjs`)       |    **Done**     | Merged in PR #6 (`?throttle_kbps=`, `?throttle_ms=`, strong ETags, disconnect cleanup).                                                                               |
|                                      | Plugin staleness guard (`sync-plugin.mjs --check`)        |    **Done**     | Merged in PR #6 (`yarn demo:sync` / `yarn demo:build` enforces fresh code in demo app).                                                                               |
|                                      | Multi-task demo UI with live per-row controls             |    **Done**     | Merged in PR #6 (readonly auto-derived ID, ⓘ popups, per-row delete).                                                                                                 |
|                                      | Android 7.0 (API 24) testing procedure                    |    **Done**     | Documented in `docs/demo-testing.md` §6 with Chrome 119 sideloading.                                                                                                  |
|                                      | Documentation reconciliation & cert trap fix              |    **Done**     | PR #11 (single source of truth in `docs/known-issues-and-caveats.md`).                                                                                                |
| **Automated E2E Testing & CI**       | Tier 1: Host JVM unit tests in CI                         |    **Done**     | Runs on every commit in `.github/workflows/ci.yml`.                                                                                                                   |
|                                      | Tier 2: Single-Emulator CI E2E (API 34)                   | **In Progress** | Active in thread `[Automated Android E2E Testing and CI Pipeline](t3-thread://v2/625e4d3b-cbb2-4fa1-ab4e-6b884550f9f0/mcp:a8761f68-6860-47a6-aedd-87c7d64426ef)`.     |
|                                      | Tier 3: Nightly Multi-Version Matrix (API 24, 31, 33, 34) |   **Planned**   | Scheduled for Phase 3 to test Doze, permission prompts, and network toggles.                                                                                          |

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

| Target                                    |    API Level    | Architectural Validation Focus                                                                                                                                                                 |
| :---------------------------------------- | :-------------: | :--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Android 7.0 (Legacy Floor)**            |   **API 24**    | Tests `tryNioAtomicMove` reflection fallback to `File.renameTo()`, pre-channel notifications, and older CA root store behavior (see canonical TLS note in `docs/known-issues-and-caveats.md`). |
| **Android 12 (FGS Restrictions)**         |   **API 31**    | Tests background start restrictions (`ForegroundServiceStartNotAllowedException`), validating the worker's `runCatching` fallback to in-app execution (`FOREGROUND_SERVICE_FAILED`).           |
| **Android 13 (Notification Perms)**       |   **API 33**    | Tests runtime `POST_NOTIFICATIONS` permission prompt handling, ensuring background WorkManager execution continues unimpeded if notifications are denied.                                      |
| **Android 14/15 (Strict FGS & Timeouts)** |  **API 34/35**  | Tests strict `FOREGROUND_SERVICE_TYPE_DATA_SYNC` enforcement and Android 15's 6-hour cumulative runtime limit on foreground data synchronization.                                              |
| **Physical OEM Device (Bonus)**           | **Android 14+** | **Samsung (OneUI) or Xiaomi (HyperOS)**: Tests real-world screen-off socket preemption and battery saver background task termination.                                                          |

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

### Phase 2: Single-Emulator CI E2E (In Progress)

- Set up a GitHub Actions workflow with `reactivecircus/android-emulator-runner` (API 34, Ubuntu KVM).
- Launch `scripts/serve-test-file.mjs` on the host runner.
- Route emulator network to host via `http://10.0.2.2:8080`.
- Execute automated JS test suite via headless WebView or ADB driver asserting:
  1. Start, throttle, pause at 25% progress.
  2. Disk assertion via `getFileStatus()`: `.part` exists, target does not exist.
  3. Resume: sends `Range: bytes=<savedLength>-`, finishes download.
  4. Final disk assertion: target exists with exact SHA-256 checksum, `.part` and sidecar removed.
  5. Cancel: immediately purges all partial files from disk.

### Phase 3: Nightly Multi-Version Matrix & Network Drop Simulation (Planned)

- Scheduled nightly workflow across **API 24, 31, 33, and 34**.
- Simulates connectivity drops mid-stream via `adb shell svc wifi disable` and verifies resilient resumption upon `adb shell svc wifi enable`.
- Validates notification permission denial flows on API 33+.

---

## 4. Documentation Architecture: Single Sources of Truth

To prevent drift across files, documentation responsibilities are strictly separated:

| File                                                                       | Canonical Scope & Purpose                                                                   |
| :------------------------------------------------------------------------- | :------------------------------------------------------------------------------------------ |
| **[`README.md`](README.md)**                                               | User-facing public API documentation, installation guide, and compatibility support matrix. |
| **[`AGENTS.md`](AGENTS.md)**                                               | AI developer rules, core architectural invariants, and essential terminal commands.         |
| **[`ROADMAP.md`](ROADMAP.md)**                                             | Strategic plan, executive checklists, Capacitor versioning, and test roadmap.               |
| **[`docs/demo-testing.md`](docs/demo-testing.md)**                         | Practical, step-by-step manual and `agent-device` interactive emulator workflow.            |
| **[`docs/known-issues-and-caveats.md`](docs/known-issues-and-caveats.md)** | Canonical reference for Android version quirks (TLS roots, FGS restrictions, OEM killers).  |
| **[`docs/cloudflare-cdn-setup.md`](docs/cloudflare-cdn-setup.md)**         | CDN edge rule configuration (compression disabling, strong ETags, CORS).                    |
