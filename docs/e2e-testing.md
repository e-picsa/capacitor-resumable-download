# Automated Android End-to-End (E2E) Testing Guide

This guide documents the automated E2E testing pipeline for `@picsa/capacitor-resumable-download`. It is designed for consumption by both AI agents and human developers.

---

## 1. Architectural Overview

The test pipeline exercises the complete production stack—from the JavaScript/TypeScript Capacitor plugin bridge, through Android's `WorkManager` background engine and `OkHttp` range downloader, down to disk persistence and sidecar metadata verification.

```
┌────────────────────────────────────────────────────────────────────────┐
│ Host Machine / CI Runner (Node 24)                                     │
│                                                                        │
│   ┌────────────────────────────────┐   ┌────────────────────────────┐  │
│   │ scripts/serve-test-file.mjs    │   │ scripts/run-e2e.mjs        │  │
│   │ • Deterministic test payload   │   │ • Test orchestration       │  │
│   │ • Range, If-Range, strong ETag │   │ • CDP WebSocket evaluation │  │
│   │ • Speed throttling & drop test │   │ • Self-validating asserts  │  │
│   └───────────────┬────────────────┘   └─────────────┬──────────────┘  │
│                   │ :8080                            │                 │
│                   │ (adb reverse / loopback 10.0.2.2)│                 │
└───────────────────┼──────────────────────────────────┼─────────────────┘
                    │                                  │ CDP over ADB (9223)
┌───────────────────┼──────────────────────────────────┼─────────────────┐
│ Android Emulator  ▼                                  ▼                 │
│   ┌────────────────────────────────────────────────────────────────┐   │
│   │ example-app (Capacitor WebView)                                │   │
│   │ • window.ResumableDownload bridge API                          │   │
│   │ • downloadProgress & downloadDiagnostic event buffers          │   │
│   └───────────────────────────────┬────────────────────────────────┘   │
│                                   │ Capacitor Plugin Bridge            │
│   ┌───────────────────────────────▼────────────────────────────────┐   │
│   │ Native Android Plugin & WorkManager Engine                     │   │
│   │ • ResumableDownloadPlugin.kt (Capacitor bridge methods)        │   │
│   │ • DownloadWorker.kt (WorkManager task execution & foreground)  │   │
│   │ • HttpRangeDownloader.kt (OkHttp Range streaming & .part I/O)  │   │
│   └───────────────────────────────┬────────────────────────────────┘   │
│                                   │ Disk Storage                       │
│   ┌───────────────────────────────▼────────────────────────────────┐   │
│   │ App Sandbox: files/downloads/                                  │   │
│   │ • [dest].part & [dest].part.meta.json (during download/pause)  │   │
│   │ • [dest] (atomic move upon completion; .part purged on cancel) │   │
│   └────────────────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Test Scenarios Covered

The automated test runner (`scripts/run-e2e.mjs`) deterministically asserts the five core lifecycle and resilience behaviors defined in `ROADMAP.md` §3:

| #   | Scenario                                                | What It Validates                                                                                                                                                                           |
| --- | ------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | **Start & Throttled Pause**                             | Starts streaming a throttled test payload (`?throttle_kbps=300`), waits for partial progress (> 10%), calls `pause()`, and verifies `getStatus()` reports `"paused"`.                       |
| 2   | **Partial File Assertions via `getFileStatus()`**       | Inspects app storage while paused: asserts the destination file does **not** exist yet, the `.part` file **does** exist with `partSize > 0`, and the strong ETag sidecar is recorded.       |
| 3   | **Resume from `.part` with HTTP Range**                 | Resumes the paused download: asserts `resumedFromBytes > 0` (proving `Range: bytes=<offset>-` was sent and accepted), and monitors through to status `"completed"`.                         |
| 4   | **Final File Assertions upon Completion**               | Inspects app storage upon completion: asserts the destination file exists with the exact expected byte size, and that temporary `.part` and sidecar files have been cleanly deleted.        |
| 5   | **Cancel Active Download & File Purging**               | Starts an active download, awaits progress, calls `cancel()`, asserts `getStatus()` reports `"cancelled"`, and verifies that all `.part` and sidecar files are immediately wiped from disk. |
| 6   | **Mid-Stream Network Drop & Resilient Resume Recovery** | Simulates an abrupt connection drop mid-stream via `?drop_after_bytes=524288`, verifies partial `.part` bytes are retained on disk, calls `resume()`, and asserts complete recovery.        |

---

## 3. Running Locally

### Prerequisites

1. Node.js `>= 22` (uses native `WebSocket` and `fetch` with zero external runner dependencies).
2. Android SDK with `adb` installed and on your `$PATH`.
3. An active Android emulator or physical device connected (verify with `adb devices`).
   - If using T3 Code: open `Medium_Phone` via the Device panel or `device_open`.
   - If using standard Android Studio: launch any AVD (e.g. `emulator -avd Medium_Phone`).

### Quick Start

Run the automated E2E test runner with a single command:

```bash
# 1. Build and run all E2E tests against the active device/emulator
yarn test:e2e
```

Execution takes **~10–15 seconds** total.

### CLI Options

The test runner script (`scripts/run-e2e.mjs`) supports several flags for rapid local development:

```bash
# Skip rebuild if example-app and plugin have already been built
node scripts/run-e2e.mjs --skip-build

# Skip rebuild and reinstall if APK is already running on the device
node scripts/run-e2e.mjs --skip-build --skip-install

# Target a specific device serial when multiple devices are attached
node scripts/run-e2e.mjs --serial emulator-5554

# Run only a specific test scenario (e.g. cancel, drop, pause)
node scripts/run-e2e.mjs --test "cancel"
node scripts/run-e2e.mjs --test "network drop"

# Use custom test payload size or throttle speed
node scripts/run-e2e.mjs --size-mb 10 --throttle 500
```

---

## 4. Continuous Integration (GitHub Actions)

The workflow is configured in [`.github/workflows/e2e-android.yml`](../.github/workflows/e2e-android.yml):

- **Trigger**: Runs on `push` and `pull_request` against `main`, and can be triggered on demand via `workflow_dispatch`.
- **Environment**: Ubuntu Linux with KVM hardware virtualization enabled (`/dev/kvm`).
- **Emulator Runner**: Uses `reactivecircus/android-emulator-runner@v2` configured with:
  - API Level: `34` (Android 14)
  - Target: `google_apis`
  - Architecture: `x86_64`
  - Profile: `pixel_6`
  - Headless options: `-no-window -gpu swiftshader_indirect -no-audio -no-boot-anim`
- **Execution Flow**:
  1. Installs Node 24 and Java 17.
  2. Builds the plugin and example app APK (`yarn demo:build`).
  3. Boots the API 34 emulator in KVM.
  4. Runs `node scripts/run-e2e.mjs --skip-build`.
  5. Captures and uploads `adb logcat` on failure for instant diagnostics.

---

## 5. Debugging & Troubleshooting

### Viewing Real-Time Logs during E2E Execution

Open a separate terminal to watch the native Android plugin logs and JavaScript console logs:

```bash
# Native WorkManager, downloader, and diagnostic events
adb logcat -s ResumableDownload:V WM-WorkerWrapper:E

# Capacitor JavaScript bridge and demo console logs
adb logcat -s Capacitor/Console:V
```

### Manually Inspecting the Test Server

To test server endpoints directly from your host machine:

```bash
# Health check
curl -s http://localhost:8080/health

# Byte range request
curl -s -i -H "Range: bytes=0-1048575" http://localhost:8080/large.bin

# Test network drop after 512KB
curl -s -i "http://localhost:8080/large.bin?drop_after_bytes=524288"
```

### Inspecting Storage Directly via ADB

If an assertion fails, you can inspect the app's internal storage sandbox:

```bash
# List downloaded files and .part files
adb shell run-as io.picsa.plugins.download.example ls -la /data/user/0/io.picsa.plugins.download.example/files/downloads/

# Read the sidecar JSON
adb shell run-as io.picsa.plugins.download.example cat /data/user/0/io.picsa.plugins.download.example/files/downloads/e2e-pause.bin.part.meta.json
```
