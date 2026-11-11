# AGENTS.md — Developer & AI Agent Guidelines

Welcome to `@picsa/capacitor-resumable-download`. This repository is designed from first principles for **rapid, AI-first iterative development and maximum resilience on low-bandwidth rural connections**.

---

## 1. Core Architecture Invariants

1. **Strict 3-File Native Architecture**:
   - `android/src/main/java/io/picsa/plugins/download/HttpRangeDownloader.kt`
   - `android/src/main/java/io/picsa/plugins/download/DownloadWorker.kt`
   - `android/src/main/java/io/picsa/plugins/download/ResumableDownloadPlugin.kt`
   - _Do not create sprawling package hierarchies or multi-layer abstraction classes without consensus._

2. **Zero-Android Core (`HttpRangeDownloader.kt`)**:
   - `HttpRangeDownloader.kt` must remain **pure Kotlin + OkHttp + java.io**.
   - No `android.content.Context`, `android.net.Uri`, or Android SDK imports.
   - All HTTP Range resumption logic, socket exception handling, `.part` management, and progress throttling live here.
   - This allows 100% test coverage using **OkHttp `MockWebServer` running on the host JVM in < 2 seconds** via `./gradlew test`.

3. **Unified Execution via Jetpack WorkManager (`DownloadWorker.kt`)**:
   - Uses `CoroutineWorker` as the execution engine.
   - When foreground notification is enabled (`notification.enabled: true`), worker calls `setForeground(ForegroundInfo(...))` wrapped inside `runCatching { ... }`.
   - If notification permissions (`POST_NOTIFICATIONS`) are missing or background start restrictions apply, the error is caught, logged as diagnostic telemetry, and the worker seamlessly proceeds downloading in-app without crashing.

4. **Sidecar ETag Persistence (`<destination>.part.meta.json`)**:
   - Stored directly alongside `<destination>.part`.
   - Payload: `{ "etag": "...", "url": "...", "totalBytes": 1234567 }`.
   - Atomically cleaned up on completion or cancellation. Zero SQLite or SharedPreferences state to leak.

5. **Client-Managed Queueing**:
   - The native plugin is a clean, stateless-per-task executor (`download`, `pause`, `resume`, `cancel`, `getStatus`).
   - Concurrency limits, retries, and task queues remain in the client JS layer (e.g. `p-queue`).

---

## 2. Essential Commands

### Build & Typecheck

```bash
yarn build
```

### Run JVM Unit Tests (< 2s execution)

```bash
yarn test
# or directly:
cd android && ./gradlew test
```

### Verify Remote CDN Byte-Range & Strong ETags

```bash
yarn verify:cdn <url>
```

### Serve Local Deterministic Test File (Range + ETag + throttle)

```bash
yarn serve:test-file                 # 50MB on :8080, emulator via http://10.0.2.2:8080/large.bin?throttle_kbps=200
yarn serve:test-file --size-mb 5 --port 8080
```

### Demo App (canonical commands — use these, not manual cap/gradle steps)

```bash
yarn demo:sync     # plugin build -> sync-plugin -> example-app build -> cap sync
yarn demo:build    # demo:sync + stale-check + assembleDebug
yarn demo:install  # adb install -r <apk> (add `-s <serial>` for a specific device)
```

> Why `scripts/sync-plugin.mjs` exists: yarn v1 `file:..` deps are **copied** into
> `example-app/node_modules` at install time, not symlinked — so Capacitor/Gradle
> silently build stale native code after editing `android/`. `demo:sync` refreshes
> the copy; `demo:build` fails fast via `--check` if it is stale. Documenting alone
> is not sufficient because the failure mode is silent (new JS, old Kotlin).

See [docs/demo-testing.md](docs/demo-testing.md) for agent-driven emulator workflow
(snapshot/press/fill via `agent-device`, logcat tags, and pause/resume presets).

### Code Formatting & Linting

```bash
yarn fmt
yarn lint
yarn lint:android # Android SDK 24-35 compatibility check
```

---

## 3. Testing Matrix

When modifying download or resume behavior, ensure the MockWebServer test suite in `android/src/test/java/io/picsa/plugins/download/HttpRangeDownloaderTest.kt` passes:

- **HTTP 206 Partial Content**: Verifies correct `Range: bytes=<offset>-` and `If-Range: <etag>` headers sent, and bytes appended accurately.
- **HTTP 200 OK Reset**: Verifies that when a server does not honor range or ETag changed, the `.part` file is truncated and re-downloaded from byte 0.
- **HTTP 416 Range Not Satisfiable**: Verifies that when byte range is invalid or server file changed, the corrupt `.part` is removed and re-downloaded (with max 1 retry).
- **Mid-Stream Socket Drop**: Simulates dropped Wi-Fi / cellular data mid-transfer, asserts the `.part` retains valid bytes, and verifies subsequent resume finishes the entire payload without corruption.
- **Worker Preemption vs. Explicit Cancellation**: Asserts that system preemption / worker stops preserve `.part` files for resumption, whereas explicit user `cancel()` purges them.
- **Weak ETag & Identity Encoding**: Asserts that weak ETags (`W/"..."`) omit `If-Range` per RFC 9110 with diagnostic emission, and `Accept-Encoding: identity` is sent.
