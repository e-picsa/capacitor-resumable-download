# Audit Report — @picsa/capacitor-resumable-download

> Generated with `.agents/skills/repo-audit/SKILL.md`. Do not commit unless asked.
> Date: 2026-11-12. Base commit: `f1b869176085c83839171ca80758b226334f05e8`. Scope: full.
> Context: `.agents/skills/repo-audit/repo-context.md` filled.
> Previous audit: none.
> Second opinion: `opencode-go/muse-spark-1.3-contributor` (`xhigh`), 5 top P1 claims spot-checked (`Agree` on all 5) + 5 gap candidates raised (G1–G5).

## Top summary

**Strengths (5):**

- **Zero-Android pure Kotlin core (`android/src/main/java/io/picsa/plugins/download/HttpRangeDownloader.kt:20-766`)**: Keeps HTTP Range logic, ETag sidecars, and `.part` I/O decoupled from Android SDK APIs, enabling a 16-test OkHttp `MockWebServer` suite (`android/src/test/java/io/picsa/plugins/download/HttpRangeDownloaderTest.kt:18-708`) that executes in `0.164s` on host JVM.
- **Resilient WorkManager + foreground fallback (`android/src/main/java/io/picsa/plugins/download/DownloadWorker.kt:155-189`)**: Attempts `setForeground()` with `FOREGROUND_SERVICE_TYPE_DATA_SYNC` inside `runCatching` (re-throwing `CancellationException`), allowing downloads to continue in-app when `POST_NOTIFICATIONS` is denied or Android 12+ background restrictions apply.
- **Thoughtful Capacitor 8/9 dual-compatibility guardrails (`android/build.gradle:27-44`, `android/variables.gradle:19-40`, `android/proguard-rules.pro:1-21`)**: Preserves `safeExtGet` fallbacks (`minSdk 24`, `androidxCoreVersion 1.17.0`), omits `targetSdk` in the library module for AGP 9, and ships consumer ProGuard/R8 keep rules.
- **Deterministic local & E2E test tooling (`scripts/serve-test-file.mjs`, `scripts/run-e2e.mjs`, `scripts/sync-plugin.mjs`)**: Provides a local throttled/drop-capable HTTP server, a staleness guard for Yarn v1 `file:..` copies, and a zero-dependency CDP-over-ADB E2E runner wired into `.github/workflows/e2e-android.yml`.
- **Strong AI-first documentation (`AGENTS.md:1-129`, `docs/demo-testing.md`, `docs/known-issues-and-caveats.md`)**: Documents architectural invariants, canonical demo commands, and OS-specific caveats (API 24 TLS root store, Chrome 119 WebView floor, OEM socket preemption).

**Priority issues (ordered by value relative to effort):**

1. **[P1] `PermissionStatus` contract & Web fallback mismatch Android's `"notifications"` key** — TypeScript consumers checking `perm.display` get `undefined` on Android while `perm.notifications` fails typechecking (`src/definitions.ts:113-115`, `android/src/main/java/io/picsa/plugins/download/ResumableDownloadPlugin.kt:25,419,430`, `CONFIRMED`, first seen 2026-11-12).
2. **[P1] `resume()` silently drops partial `url`, `headers`, and `notification` overrides unless `destinationPath` is also passed** — Breaks token/URL refresh flows on Android (`android/src/main/java/io/picsa/plugins/download/ResumableDownloadPlugin.kt:243-271`, `src/definitions.ts:94`, `CONFIRMED`, first seen 2026-11-12).
3. **[P1] `getStatus()` always returns `totalBytes: -1` and reports `"paused"` after cancelling a paused download** — Breaks post-reload progress bars and cancel-after-pause UI state (`android/src/main/java/io/picsa/plugins/download/ResumableDownloadPlugin.kt:334-363,284-305`, `CONFIRMED`, first seen 2026-11-12).
4. **[P1] `HttpRangeDownloader` stale ETag on 200 reset, fallthrough after repeated representation mismatch, and unvalidated `206` offset/truncation** — Can persist stale ETags, corrupt `.part` on repeated 206 mismatch, or succeed on truncated `/*` responses (`android/src/main/java/io/picsa/plugins/download/HttpRangeDownloader.kt:348-391,419,593-613`, `CONFIRMED`, first seen 2026-11-12).
5. **[P1] `yarn test:e2e` silently installs stale APK if `app-debug.apk` already exists** — Skips `yarn demo:build` and `sync-plugin.mjs --check`, risking false passes on stale code (`scripts/run-e2e.mjs:422-429`, `CONFIRMED`, first seen 2026-11-12).
6. **[P1] Concurrent `download()`/`resume()` replacement races on `activeCalls`, `pausedTasks`, and `RandomAccessFile`** — Overlapping starts for the same `downloadId` can remove the new call from `activeCalls` and resolve the old promise as `"paused"` (`android/src/main/java/io/picsa/plugins/download/DownloadWorker.kt:55-93,254`, `android/src/main/java/io/picsa/plugins/download/ResumableDownloadPlugin.kt:457-503`, `CONFIRMED`, first seen 2026-11-12).
7. **[P1] Capacitor 9 dual compatibility is untested in CI** — `package.json` advertises `^8.0.0 || ^9.0.0`, but CI only builds against Capacitor 8 (`package.json:65-70`, `.github/workflows/ci.yml:28-91`, `LIKELY`, first seen 2026-11-12).

**Highest-leverage recommendation:**

- **Fix the Android bridge contract & state-machine bugs (`PermissionStatus`, partial `resume()`, `getStatus()`, and `DownloadWorker` call-identity guards) together with `HttpRangeDownloader` edge-case fixes**, led by the plugin maintainer — this closes all runtime contract mismatches and partial-file edge cases for PICSA app consumers without altering the 3-file native architecture.

## Scope and coverage

- **Dirs read:** `src/` (`index.ts`, `definitions.ts`, `web.ts`), `android/` (`build.gradle`, `variables.gradle`, `gradle.properties`, `proguard-rules.pro`, `gradle/wrapper/gradle-wrapper.properties`, `src/main/AndroidManifest.xml`, `HttpRangeDownloader.kt`, `DownloadWorker.kt`, `ResumableDownloadPlugin.kt`, `HttpRangeDownloaderTest.kt`), `scripts/` (`run-e2e.mjs`, `serve-test-file.mjs`, `sync-plugin.mjs`, `verify-cdn.mjs`), `example-app/` (`package.json`, `tsconfig.json`, `capacitor.config.ts`, `src/main.ts`, `android/build.gradle`, `android/variables.gradle`, `android/app/build.gradle`, `android/app/src/main/AndroidManifest.xml`, `network_security_config.xml`), `.github/workflows/` (`ci.yml`, `e2e-android.yml`, `pr-preview.yml`, `release-please.yml`), `docs/` (`known-issues-and-caveats.md`, `e2e-testing.md`, `demo-testing.md`, `cloudflare-cdn-setup.md`), root config (`package.json`, `tsconfig.json`, `rollup.config.mjs`, `README.md`, `AGENTS.md`, `ROADMAP.md`, `.nvmrc`, `.node-version`, `.pr_agent.toml`, `release-please-config.json`, `.release-please-manifest.json`, `.gitignore`).
- **Skipped:** `dist/`, `android/build/`, `example-app/dist/`, `example-app/android/build/`, `node_modules/` (per out-of-scope rules, except spot-reading `node_modules/@capacitor/android/.../Plugin.java` and `JSObject.java` to verify Capacitor bridge behavior).
- **Commands run:**
  - `yarn lint`: passed (`0.61s`, Prettier check across `.ts`, `.js`, `.mjs`, `.json`, `.md`).
  - `yarn build`: passed (`1.40s`, `tsc` + `rollup`).
  - `yarn test`: passed (`16` tests in `HttpRangeDownloaderTest`, `0` skipped, `0` failures, `0.164s`).
  - `yarn lint:android`: passed (`12s`, `0 errors, 10 warnings`: 3x `ObsoleteSdkInt`, 6x `GradleDependency`, 1x `AndroidGradlePluginVersion`).
  - `cd example-app && yarn build`: passed (`0.75s`, `tsc && vite build`).
  - `node scripts/sync-plugin.mjs --check`: exited `1` (expected in local workspace when `example-app/node_modules` has not been re-synced; 9 files stale).
  - `yarn test:e2e`: skipped (no Android emulator booted in this audit pass; `scripts/run-e2e.mjs` and `.github/workflows/e2e-android.yml` audited statically).
- **Issues mined (read-only):** `gh issue list --limit 20` returned 1 open issue: `#14` ("Update example-app demo to Capacitor 9 and test against Capacitor 9 emulator", opened 2026-11-11).
- **Assumptions:** User archetypes, API 24 build floor, and the strict 3-file native layout constraint are taken directly from `.agents/skills/repo-audit/repo-context.md` and `AGENTS.md`. Stack currency checked as of `2026-11-12` (`@capacitor/core` 8.6.0 latest / 9.0.0-beta.0 prerelease, `androidx.work` 2.12.0, `okhttp` 5.5.1).

## Delta since last audit

No previous audit — this is the baseline.

## User impact and functionality

Archetypes (goal, constraint, what "broken" means):

- **Offline-first rural app developer** — ships large media/content packs in Capacitor 8/9 apps (e.g. PICSA) over intermittent links; constrained by Capacitor bridge contracts and expired auth/presigned URLs; broken means failed/corrupted downloads, broken TypeScript types, or re-downloads from byte 0.
- **End user on budget/legacy Android hardware (API 24+)** — downloads multi-MB files on metered rural networks; constrained by OS battery/Doze limits, low RAM/storage, and denied permissions; broken means battery drain, missing/stuck notifications, or orphaned `.part` files.
- **Plugin maintainer** — evolves the plugin toward Capacitor 9 dual support and automated multi-API E2E coverage; constrained by the 3-file native layout and Yarn v1 `file:..` copying; broken means silent stale builds, CI blind spots, and doc/code drift.

Gap findings (missing, painful, or mismatched — not just broken):

- **F1 [P1, CONFIRMED, H/S]** `PermissionStatus` contract & Web fallback mismatch Android's `"notifications"` key — `src/definitions.ts:113-115`, `src/web.ts:252-258` vs `android/src/main/java/io/picsa/plugins/download/ResumableDownloadPlugin.kt:23-26,419,430`; TypeScript defines `PermissionStatus` as `{ display: ... }` and Web returns `{ display: 'granted' }`, while Android's `@Permission(alias = "notifications")` and pre-API-33 overrides return `{ notifications: 'granted' }`. App developers calling `checkPermissions()` / `requestPermissions()` get `undefined` for `.display` on Android and fail `tsc` if reading `.notifications`. Next step: change `PermissionStatus` and `ResumableDownloadWeb` to `{ notifications: ... }`.
- **F6 [P1, CONFIRMED, M/S]** `notification.smallIcon` declared in TypeScript API is silently ignored on Android — `src/definitions.ts:45` vs `android/src/main/java/io/picsa/plugins/download/ResumableDownloadPlugin.kt:186-190` and `android/src/main/java/io/picsa/plugins/download/DownloadWorker.kt:216,335,369`; `smallIcon?: string` is never read from JS options and `DownloadWorker` hardcodes `android.R.drawable.stat_sys_download` / `stat_sys_download_done`. Additionally, `DownloadWorker.kt:215` suppresses MB progress text (`"12.3 MB / 50.0 MB"`) whenever `subtitle` is non-null. Next step: resolve `smallIcon` via `context.resources.getIdentifier(...)` with safe fallback (or remove from `DownloadOptions` until implemented), and append or fall back cleanly for progress text when `subtitle` is set.
- **F13 [P2, CONFIRMED, M/M]** Presigned URL token rotation breaks `.part` resume — `src/definitions.ts:10`, `android/src/main/java/io/picsa/plugins/download/HttpRangeDownloader.kt:148-159`; exact `sidecar.url != request.url` equality protects query-addressed resources (commit `0c23203`), but purges `.part` and restarts from byte 0 whenever S3/R2/Supabase presigned URLs rotate query signature tokens on `resume()`. Next step: consider an optional `resourceKey` (or opt-in `ignoreQueryParams`) field on `DownloadOptions` so signed-URL callers can preserve `.part` identity without breaking query-based endpoints.

## Architecture

- The intentional 3-file native layout (`HttpRangeDownloader.kt`, `DownloadWorker.kt`, `ResumableDownloadPlugin.kt`) is clean and respected per `.agents/skills/repo-audit/repo-context.md` overrides (`AGENTS.md:9-19`).
- **F9 [P1, LIKELY, H/M]** Capacitor 9 dual compatibility is untested in CI and `lintDebug` carries obsolete checks — `package.json:65-70`, `.github/workflows/ci.yml:28-91`, `.github/workflows/e2e-android.yml:3-10`; `package.json` declares `@capacitor/core: ^8.0.0 || ^9.0.0`, and `android/build.gradle:27-44` + `android/variables.gradle:24-26` keep `safeExtGet` fallbacks (note `variables.gradle:25` defines `compileSdkVersion = 36` while `build.gradle:27` uses fallback `37`), yet CI only installs `@capacitor/core@^8.0.0` and never compiles or tests against Capacitor 9 (`9.0.0-beta.0` / AGP 9 / R8 full mode; tracked in open issue `#14`). Stack currency check (2026-11-12): `androidx.work:work-runtime-ktx` is `2.9.1` vs `2.12.0` stable (2.12.0 raises minSdk to 24, matching this plugin), `okhttp` is `4.12.0` vs `5.5.1` stable, and `AGP` in `android/build.gradle:7` is `8.7.2` vs `9.5.1` (staying on `8.7.2` / `4.12.0` / `2.9.1` is justified while Capacitor 8 AGP compatibility is required, so no forced upgrade is recommended, only CI coverage for Cap 9). Next step: add a CI matrix/job verifying Cap 9 compilation and R8 keep rules, and resolve open issue `#14`.

## Code quality

- **F11 [P2, CONFIRMED, M/S]** Web fallback (`src/web.ts`) leaks completed/errored chunk arrays in memory and diverges on event/status parity — `src/web.ts:47,121-143,165-184,239-250`; `this.activeDownloads` never deletes completed or errored entries (only `cancel()` deletes at line 217), retaining `Uint8Array[]` chunks indefinitely; progress events omit `resumedFromBytes` and `throughputBytesPerSecond` and reset `progress` to `0` on `error`/`cancelled`; `getFileStatus` is a stub returning `{ exists: false }`. Next step: remove/clear chunk buffers upon completion or error in `src/web.ts` and align progress snapshot fields.
- Grouped minor code-quality notes: `DownloadWorker.kt:227,346,378` repeats `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)` (flagged 3x by `ObsoleteSdkInt` lint since `minSdk` is 24); `DownloadWorker.kt:385` calls `notificationManager.notify(...)` outside `runCatching` in `showCompletionNotification` (unlike line 234); `ResumableDownloadPlugin.kt:158,464` posts `activeObservers` registration and teardown separately to `Looper.getMainLooper()`, which can leak an observer if `handleOnDestroy()` runs between `enqueueWorker` posting and its runnable executing.

## Bugs

- **F2 [P1, CONFIRMED, H/S]** `resume()` silently ignores partial `url`, `headers`, `directory`, and `notification` overrides unless `destinationPath` is also supplied — `android/src/main/java/io/picsa/plugins/download/ResumableDownloadPlugin.kt:243-271` vs `src/definitions.ts:94` and `src/web.ts:196-210`; `ResumableDownloadPlugin.resume()` wraps all option merging in `if (!url.isNullOrBlank() && !destRelativePath.isNullOrBlank())`. Calling `resume({ downloadId, headers: { Authorization: '...' } })` or `resume({ downloadId, url: newUrl })` without `destinationPath` silently reuses stale `DownloadConfig` values. Next step: merge each provided field independently onto `config` when `config != null`.
- **F3 [P1, CONFIRMED, H/S]** `getStatus()` always returns `totalBytes: -1` and reports `"paused"` after cancelling a paused download — `android/src/main/java/io/picsa/plugins/download/ResumableDownloadPlugin.kt:334-363,284-305`, `android/src/main/java/io/picsa/plugins/download/DownloadWorker.kt:274-286`; (1) `getStatus()` hardcodes `put("totalBytes", -1)` at line 360 instead of reading `.part.meta.json` or `KEY_OUTPUT_SIZE` on completion, breaking progress restoration in `example-app/src/main.ts:324-331`; (2) `DownloadWorker` exits pause with `Result.success(KEY_OUTPUT_STATUS to "paused")`, so calling `cancel()` later cannot transition WorkManager's already-`SUCCEEDED` `WorkInfo` to `CANCELLED`, leaving `getStatus()` returning `"paused"`. Next step: read `totalBytes` from sidecar/output data in `getStatus()`, and handle cancelled paused tasks in `cancel()` / `getStatus()` (e.g. check `DownloadWorker.isCancelled(downloadId)` or prune/track cancelled state).
- **F4 [P1, CONFIRMED, H/S]** `HttpRangeDownloader` carries stale ETag onto `200 OK` resets, falls through after repeated `206` representation mismatch, and skips `206` offset/truncation checks — `android/src/main/java/io/picsa/plugins/download/HttpRangeDownloader.kt:348-391,417-429,593-613`; (1) line 419 sets `etag = etagHeader ?: sidecar?.etag` even on `200 OK`, re-saving an old stale ETag if a reset `200` response omits `ETag`; (2) lines 376-388 delete `.part`/`.part.meta.json` on `RANGE_REPRESENTATION_CHANGED` but, when `retryOn416Count >= 1`, fall through with stale `partLength > 0`, `resumed = true`, and `raf.seek(currentBytes)` instead of returning `DownloadOutcome.Failure`; (3) for `206`, `Content-Range` start byte is not verified to equal `partLength`, and `Content-Length` is ignored when `Content-Range` ends with `/*`, allowing truncated clean-EOF `206` streams to report `Success`; (4) failure exits at lines 424, 595, and 607 omit `onProgress(..., Status.ERROR)`. Next step: fix all four paths in `HttpRangeDownloader.kt` and add `MockWebServer` unit tests in `HttpRangeDownloaderTest.kt`.
- **F5 [P1, CONFIRMED, H/M]** Overlapping `download()`/`resume()` calls for the same `downloadId` race on `activeCalls`, `pausedTasks`, and `RandomAccessFile` — `android/src/main/java/io/picsa/plugins/download/DownloadWorker.kt:55-93,254,274-286`, `android/src/main/java/io/picsa/plugins/download/ResumableDownloadPlugin.kt:213-216,457-503`; `ExistingWorkPolicy.REPLACE` starts a replacement worker while the prior worker's `finally { unregisterCall(downloadId) }` (line 254) can remove the new worker's `activeCalls[downloadId]`, `pausedTasks[downloadId]` is not cleared on `Paused` exit (`DownloadWorker.kt:274`), and the replaced `WorkRequest`'s observer (`ResumableDownloadPlugin.kt:493-502`) resolves the superseded JS promise as `{ status: "paused", resumed: true }`. Next step: make `unregisterCall(downloadId, call)` compare-and-remove (`activeCalls.remove(downloadId, call)`), avoid clearing `pausedTasks` prematurely across concurrent workers, and reject or deduplicate active `downloadId` calls cleanly.

## Security

- **F7 [P2, CONFIRMED, M/S]** Path containment check in `resolveDestinationFile()` allows `"."` / `"/"`, writing `.part` outside `filesDir` — source `ResumableDownloadPlugin.kt:175` (`destinationPath`) → sink `ResumableDownloadPlugin.kt:560` (`if (filePath != canonicalBase.canonicalPath && !filePath.startsWith(basePrefix))`) → `HttpRangeDownloader.kt:141` (`File(destFile.absolutePath + ".part")`). Passing `"."` or `"/"` resolves `destFile` to `filesDir` itself, placing `files.part` and `files.part.meta.json` in the parent app data directory and failing at completion when trying to overwrite the `files` directory. Next step: reject `filePath == canonicalBase.canonicalPath` in `resolveDestinationFile()`.
- **F10 [P2, LIKELY, M/S]** Auth headers persisted in plaintext storage and demo-only DOM XSS — (1) source `src/definitions.ts:31` (`headers`) → sink `ResumableDownloadPlugin.kt:205,442,572` writes custom headers (e.g. `Authorization`) in plaintext to `SharedPreferences` (`picsa_resumable_downloads`) and WorkManager's SQLite DB, and never clears `SharedPreferences` on `FAILED`; `example-app/android/app/src/main/AndroidManifest.xml:5` also has `android:allowBackup="true"`. (2) In `example-app/src/main.ts:519`, `fileStat.path` (derived from `destInput.value`) is interpolated into `diskDetails.innerHTML` (demo-only self-XSS, not in the plugin package). Next step: clear sensitive `headersJson` or document backup exclusion rules for consuming apps, and switch `example-app/src/main.ts:515-520` to `textContent` DOM nodes.

## AI-first workflow

- **F8 [P1, CONFIRMED, H/S]** `yarn test:e2e` silently runs a stale APK whenever `app-debug.apk` already exists, and Test 6 skips key assertions — `scripts/run-e2e.mjs:422-429,813-890` vs `docs/e2e-testing.md:80`; `if (!fs.existsSync(APK_PATH))` causes `yarn test:e2e` (without `--skip-build`) to skip `yarn demo:build` and `sync-plugin.mjs --check` whenever an old APK exists locally, silently testing stale Kotlin/JS code. In Test 6, `dropDetected` (line 813) and `resumeOffset` (line 869) are recorded but never asserted. Next step: always invoke `yarn demo:build` when `--skip-build` is false, and assert `dropDetected` and `resumeOffset >= minExpectedSavedBytes` in Test 6.
- **F12 [P2, CONFIRMED, L/S]** Documentation & CI workflow drift — `README.md:12-38` duplicates the `## Compatibility` table twice; `README.md:28` and `src/definitions.ts:164` claim `"250ms or 1% delta"` throttling while `HttpRangeDownloader.kt:518-519` uses strict `elapsed >= 250`; `docs/known-issues-and-caveats.md:70` cites a nonexistent `STARTED` status; `package.json:57` `yarn lint` only checks Prettier (no ESLint / `example-app` sync check in `ci.yml`); `.github/workflows/e2e-android.yml:3-10` lacks `paths-ignore` (unlike `ci.yml`), running emulator E2E on docs-only PRs; `AGENTS.md` omits `yarn install` / Node 24 prerequisite and Conventional Commit rules for `release-please`. Next step: deduplicate `README.md`, align throttle/status docs, add `paths-ignore` to `e2e-android.yml`, and add setup + commit conventions to `AGENTS.md`.

## Roadmap (fixed schema — the table is the single source)

Value H/M/L: H removes a crash, data loss, or blocker for a primary archetype
or a major maintenance burden; M is clear pain for a subset of users or
meaningful toil removed; L is nice-to-have with limited reach. Effort S/M/L
(never days): S is likely one file, covered by automated tests, easy to
review; M is a few files or needs extra test/review care; L touches many
files or needs heavy human oversight.

| ID  | Type      | Title                                                                                 | Beneficiary                | Value | Effort | Depends on | Done when                                                                                                                           | From     |
| --- | --------- | ------------------------------------------------------------------------------------- | -------------------------- | ----- | ------ | ---------- | ----------------------------------------------------------------------------------------------------------------------------------- | -------- |
| R1  | Quick win | Fix `PermissionStatus` & Web key mismatch (`notifications`)                           | Rural app devs             | H     | S      | none       | `PermissionStatus` & `src/web.ts` use `notifications`; `yarn build` & `example-app` `tsc` pass                                      | F1       |
| R2  | Quick win | Fix partial option merging in `ResumableDownloadPlugin.resume()`                      | Rural app devs             | H     | S      | none       | `resume({ downloadId, headers })` / `resume({ downloadId, url })` merges onto saved `DownloadConfig`                                | F2       |
| R3  | Quick win | Fix `getStatus()` `totalBytes` & cancel-after-pause status                            | Rural app devs & end users | H     | S      | none       | `getStatus()` reads sidecar/output `totalBytes` and returns `"cancelled"` after `cancel()` on paused work                           | F3       |
| R4  | Quick win | Fix `HttpRangeDownloader` 200 ETag reset, mismatch exit, `206` check, error events    | End users & app devs       | H     | S      | none       | 4 edge cases fixed with passing `MockWebServer` tests in `HttpRangeDownloaderTest.kt`                                               | F4       |
| R5  | Quick win | Fix `scripts/run-e2e.mjs` stale-APK bypass & Test 6 assertions                        | Maintainers & AI agents    | H     | S      | none       | `yarn test:e2e` runs `demo:build` unless `--skip-build`; Test 6 asserts `dropDetected` & `resumeOffset`                             | F8       |
| R6  | Quick win | Reject `"."`/`"/"` root path in `resolveDestinationFile()` & fix demo `innerHTML`     | App devs & end users       | M     | S      | none       | `resolveDestinationFile(".")` throws `IllegalArgumentException`; `example-app` uses `textContent`                                   | F7, F10  |
| R7  | Strategic | Fix concurrent `download()`/`resume()` replacement races in `DownloadWorker` & plugin | App devs & end users       | H     | M      | R2, R3     | `unregisterCall` uses identity CAS; active `downloadId` calls deduplicated or cleanly sequenced without false `"paused"` resolution | F5       |
| R8  | Quick win | Implement or remove `notification.smallIcon` & improve `subtitle` progress display    | App devs & end users       | M     | S      | none       | `smallIcon` resolves app drawable with safe fallback (or removed from types); progress MB shown cleanly                             | F6       |
| R9  | Strategic | Add Capacitor 9 CI verification & tighten CI/lint hygiene                             | Maintainers                | H     | M      | none       | CI verifies build/R8 against Cap 9 (`#14`), `e2e-android.yml` has `paths-ignore`, 3 `ObsoleteSdkInt` warnings resolved              | F9       |
| R10 | Quick win | Clean up `src/web.ts` memory leak & align docs/AI workflow guides                     | App devs & AI agents       | M     | S      | R1         | `activeDownloads` clears buffers on finish/error; `README.md`, `definitions.ts`, and `AGENTS.md` aligned                            | F11, F12 |
| R11 | Strategic | Support stable `resourceKey` (or opt-in query stripping) for presigned URLs           | Rural app devs & end users | M     | M      | R4         | Signed URLs with rotated tokens resume `.part` without breaking query-identified endpoints                                          | F13      |

**Quick wins:** R1, R2, R3, R4, R5, R6, R8, R10

**Strategic:** R7, R9, R11

Strategic notes:

- **R7** — what breaks today: overlapping `download()`/`resume()` calls for one `downloadId` can unregister the new worker's OkHttp `Call` and resolve the old promise as `"paused"`; what it unblocks: safe idempotent UI retries under flaky rural connectivity; cost of deferral: intermittent un-cancellable workers and `.part` races in production.
- **R9** — what breaks today: Capacitor 9 (`^9.0.0`) compatibility is declared in `package.json` without CI verification; what it unblocks: safe releases as Capacitor 9 reaches GA without breaking Capacitor 8 / API 24; cost of deferral: undetected AGP 9 / R8 regressions and wasted emulator CI minutes on doc PRs.
- **R11** — what breaks today: S3/R2/Supabase presigned URLs lose `.part` progress when tokens expire before `resume()`; what it unblocks: multi-day rural downloads behind short-lived signed URLs; cost of deferral: full re-downloads from byte 0 on token refresh.

### Task prompts

- R1: "In `src/definitions.ts` and `src/web.ts`, update `PermissionStatus` and `ResumableDownloadWeb` to use `notifications` instead of `display` to match `ResumableDownloadPlugin.kt`, and verify with `yarn build` (F1)."
- R2: "In `android/src/main/java/io/picsa/plugins/download/ResumableDownloadPlugin.kt`, update `resume()` to merge partial `url`, `directory`, `destinationPath`, `headers`, and `notification` fields onto existing `DownloadConfig` even when `destinationPath` is omitted (F2)."
- R3: "In `android/src/main/java/io/picsa/plugins/download/ResumableDownloadPlugin.kt`, populate `totalBytes` in `getStatus()` from `.part.meta.json` or completed output size, and ensure `getStatus()` reports `'cancelled'` after `cancel()` on a paused task (F3)."
- R4: "In `android/src/main/java/io/picsa/plugins/download/HttpRangeDownloader.kt` and `HttpRangeDownloaderTest.kt`, clear stale ETag on 200 resets, return `Failure` when `RANGE_REPRESENTATION_CHANGED` repeats, validate `206` `Content-Range` offset & `Content-Length`, emit `Status.ERROR` on all failures, and add MockWebServer tests (F4)."
- R5: "In `scripts/run-e2e.mjs`, run `yarn demo:build` whenever `--skip-build` is omitted (even if `app-debug.apk` exists) and assert `dropDetected` and `resumeOffset >= minExpectedSavedBytes` in Test 6 (F8)."
- R6: "In `android/src/main/java/io/picsa/plugins/download/ResumableDownloadPlugin.kt`, reject `filePath == canonicalBase.canonicalPath` in `resolveDestinationFile()`, and in `example-app/src/main.ts` replace `diskDetails.innerHTML` with safe DOM `textContent` construction (F7, F10)."
- R7: "In `android/src/main/java/io/picsa/plugins/download/DownloadWorker.kt` and `ResumableDownloadPlugin.kt`, guard `activeCalls` removal by `Call` identity (`remove(downloadId, call)`), clear pause state cleanly, guard `activeObservers` against post-destroy registration, and prevent `ExistingWorkPolicy.REPLACE` from falsely resolving superseded calls as `'paused'` (F5, F12)."
- R8: "In `ResumableDownloadPlugin.kt` and `DownloadWorker.kt`, pass `notification.smallIcon` and resolve drawable resource ID with fallback to `stat_sys_download` (or remove `smallIcon` from `src/definitions.ts` if deferred), and preserve progress text in notification when `subtitle` is set (F6)."
- R9: "Add a Capacitor 9 (`@capacitor/core@9.0.0-beta.0`) build/lint CI check in `.github/workflows/ci.yml` (advancing issue #14), add `paths-ignore` to `.github/workflows/e2e-android.yml`, and remove the 3 `ObsoleteSdkInt` (`VERSION_CODES.M`) branches in `DownloadWorker.kt` (F9)."
- R10: "In `src/web.ts`, free `chunks` and delete finished/errored entries from `activeDownloads` while aligning progress fields; deduplicate `README.md` Compatibility table, align `250ms` throttle docs in `README.md`/`src/definitions.ts`/`docs/known-issues-and-caveats.md`, and add Node 24/Conventional Commit guidance to `AGENTS.md` (F11, F12)."
- R11: "Add an optional `resourceKey?: string` (or `ignoreQueryParams?: boolean`) option to `DownloadOptions`, propagate it to `MetaSidecar` in `HttpRangeDownloader.kt`, and add a MockWebServer test verifying `.part` resumption across rotated presigned URL tokens (F13)."

## Second opinion (one-shot, no debate)

| Claim                                                                                                                                                                                     | Verdict | Reviewer note                                                                                                                                                                                                                                                                                                                      | Human double-check                                                                                                                               |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| [P1] `PermissionStatus` contract & Web fallback mismatch Android `"notifications"` key — `src/definitions.ts:113-115`, `src/web.ts:252-258` vs `ResumableDownloadPlugin.kt:23-26,419,430` | Agree   | `definitions.ts:113-115` is `{ display: ... }` and `web.ts:252-258` returns `{ display: 'granted' }`, while Kotlin `@Permission(alias="notifications")` + pre-API-33 overrides return `{ notifications: 'granted' }`. TS consumers get `undefined` on Android.                                                                     | Run `checkPermissions()` on API 33+ emulator + web and log raw keys; confirm Capacitor `Plugin.java` permission-result keying by alias.          |
| [P1] `resume()` drops partial `url`/`headers`/`notification` overrides unless `destinationPath` also passed — `ResumableDownloadPlugin.kt:243-271`                                        | Agree   | Merge is gated by `if (!url.isNullOrBlank() && !destRelativePath.isNullOrBlank())` at line 245; single-field `resume({downloadId, headers})` or `resume({downloadId, url})` silently reuses stale `DownloadConfig`. `web.ts:196-210` merges correctly, confirming Android-only divergence.                                         | Manually call `resume()` with only `headers` changed on device; verify stale `Authorization` is sent.                                            |
| [P1] `getStatus()` hardcodes `totalBytes: -1`, reports `"paused"` after cancel-after-pause — `ResumableDownloadPlugin.kt:334-363,284-305`, `DownloadWorker.kt:274-286`                    | Agree   | Line 360 is literally `put("totalBytes", -1)`; pause exits via `Result.success(... "paused")` so a later `cancel()` cannot move an already-`SUCCEEDED` `WorkInfo` to `CANCELLED`, and the `SUCCEEDED` branch returns `"paused"` unconditionally.                                                                                   | Pause → `cancel()` → `getStatus()` on emulator; check `WorkInfo` state in logcat vs returned status.                                             |
| [P1] `HttpRangeDownloader` stale ETag on 200, fallthrough after repeated 206 mismatch, unvalidated 206 offset/truncation — `HttpRangeDownloader.kt:348-391,417-429,593-613`               | Agree   | Line 419 `etagHeader ?: sidecar?.etag` runs on the 200 path; lines 374-375 delete files but on `retryOn416Count>=1` fall through with stale `partLength` + `raf.seek()`; no `Content-Range` start-offset check; `/*` → `totalBytes=-1` bypasses size check at 593; lines 424/595/607 return `Failure` without `onProgress(ERROR)`. | Write `MockWebServer` repros: 200-without-ETag after 206-with-ETag; double representation-change; 206 with wrong start offset / `/*` truncation. |
| [P1] `yarn test:e2e` skips build when APK exists — `scripts/run-e2e.mjs:422-429`                                                                                                          | Agree   | `if (!fs.existsSync(APK_PATH))` guards `yarn demo:build`; with an existing APK it logs "Using existing built APK" and never runs `sync-plugin.mjs --check`, so stale Kotlin is silently tested. Test 6 `dropDetected`/`resumeOffset` recorded-but-unasserted also confirmed at lines 813/869.                                      | Delete APK vs keep stale APK, run `yarn test:e2e` without flags, confirm no rebuild.                                                             |

**Gap candidates (misses the draft may have):**

- **G1 — `getStatus()` blocks on `ListenableFuture.get()` on the bridge thread — `android/src/main/java/io/picsa/plugins/download/ResumableDownloadPlugin.kt:315-317` — why it matters:** `getWorkInfosForUniqueWork(...).get()` is a synchronous blocking call on the Capacitor `PluginMethod` thread; under WorkManager DB contention this risks slow calls/ANR depending on thread dispatch, and could use an async listener or background executor. (Severity ~P1/P2)
- **G2 — Native `mimeType` dropped, web parity break — `android/src/main/java/io/picsa/plugins/download/DownloadWorker.kt:257-272` + `android/src/main/java/io/picsa/plugins/download/ResumableDownloadPlugin.kt:474-482` vs `src/definitions.ts:89` + `src/web.ts:132-143` — why it matters:** `DownloadOutcome.Success` carries `mimeType`, but worker `workDataOf` has no MIME key and plugin `SUCCEEDED` response omits it, while web returns it. (Severity ~P2)
- **G3 — E2E `--test <filter>` with zero matches exits 0 — `scripts/run-e2e.mjs:487,602,684,782,938-950` — why it matters:** A typo (e.g. `--test e2e`) runs zero tests and exits `0` (`Total: 0 | Passed: 0`), giving a false green in CI/local runs. (Severity ~P2)
- **G4 — Web `download()` overwrites in-flight same-`downloadId` without aborting — `src/web.ts:26-47` — why it matters:** Only `paused` state reuses buffers; a second `download()` while `downloading` orphans the first `AbortController`/fetch loop, which keeps emitting `downloadProgress` for the same ID. (Severity ~P2/P3)
- **G5 — `notificationId = downloadId.hashCode()` collision — `android/src/main/java/io/picsa/plugins/download/DownloadWorker.kt:122,364-365` — why it matters:** Distinct `downloadId`s with colliding hashes (or `h` vs `h+1` for completion notification) can overwrite or cancel another download's notification. (Severity ~P3)

**Human double-check list:**

1. Permission keys on real API 24, 33, 35 devices (`checkPermissions`/`requestPermissions` raw payloads).
2. `resume()` with rotated presigned URL / refreshed `Authorization` header only.
3. Pause → `cancel()` → `getStatus()` + `getFileStatus()` sequence; confirm `.part` purged and status is `"cancelled"`.
4. 200-without-ETag reset and double-416/double-representation-change paths with `MockWebServer`.
5. `yarn test:e2e` staleness: touch Kotlin, keep old APK, rerun without `--skip-build`.
6. G1–G3 above: `getStatus()` latency under WorkManager DB load, `mimeType` absence on Android success, `--test nonsense` zero-run exit code.

- Reviewer: `opencode-go/muse-spark-1.3-contributor` (`xhigh`), 2026-11-12. Checked: `.agents/skills/repo-audit/repo-context.md`, `HttpRangeDownloader.kt`, `DownloadWorker.kt`, `ResumableDownloadPlugin.kt`, `src/definitions.ts`, `src/web.ts`, `scripts/run-e2e.mjs`, `android/build.gradle`, `android/variables.gradle` (full reads before opening `AUDIT.md`; top 5 P1 claims spot-checked).
- Skipped by reviewer: no commands run (`yarn build/test/lint`, emulator E2E); `HttpRangeDownloaderTest.kt`, `serve-test-file.mjs`, `sync-plugin.mjs`, `example-app/*`, workflows, and `dist/` not re-read; no network/TLS or Doze/FGS lifecycle testing.
- Conflicts and gaps are left to the human; draft was changed only for: none (all 5 top claims agreed; G1–G5 added verbatim to Second opinion).

## Appendix — commands and outputs

```text
$ yarn lint
Checking formatting...
All matched files use Prettier code style!
✨  Done in 0.61s.

$ yarn build
$ yarn clean && yarn build:types && yarn build:bundle
dist/esm/index.js → dist/plugin.js, dist/plugin.cjs.js...
created dist/plugin.js, dist/plugin.cjs.js in 25ms
✨  Done in 1.40s.

$ yarn test
> Task :testDebugUnitTest
BUILD SUCCESSFUL
TEST-io.picsa.plugins.download.HttpRangeDownloaderTest.xml: tests="16" skipped="0" failures="0" errors="0" time="0.164"

$ yarn lint:android
> Task :lintDebug
0 errors, 10 warnings
- android/build.gradle:7 [AndroidGradlePluginVersion] 8.7.2 vs 9.5.1
- android/build.gradle:98,99,100,105,106,108 [GradleDependency]
- android/src/main/java/io/picsa/plugins/download/DownloadWorker.kt:227,346,378 [ObsoleteSdkInt]
BUILD SUCCESSFUL in 12s

$ (cd example-app && yarn build)
dist/index.html                  4.45 kB │ gzip:  1.46 kB
dist/assets/index-CQuq9X8e.css   6.66 kB │ gzip:  1.70 kB
dist/assets/web-CSRJztro.js      7.21 kB │ gzip:  1.98 kB
dist/assets/web-BEJmK8KF.js     18.69 kB │ gzip:  4.28 kB
dist/assets/index-BmaTNseL.js   58.51 kB │ gzip: 10.91 kB
✓ built in 85ms
✨  Done in 0.75s.

$ node scripts/sync-plugin.mjs --check
plugin sync: STALE example-app copy — run `yarn demo:sync` first.
  - package.json
  - android/build.gradle
  - android/gradle.properties
  - android/proguard-rules.pro
  - android/src/main/java/io/picsa/plugins/download/DownloadWorker.kt
  - android/src/main/java/io/picsa/plugins/download/HttpRangeDownloader.kt
  - android/src/main/java/io/picsa/plugins/download/ResumableDownloadPlugin.kt
  - android/src/test/java/io/picsa/plugins/download/HttpRangeDownloaderTest.kt
  - android/variables.gradle
(exit code 1)

$ gh issue list --limit 20
#14  Update example-app demo to Capacitor 9 and test against Capacitor 9 emulator (Open)
```
