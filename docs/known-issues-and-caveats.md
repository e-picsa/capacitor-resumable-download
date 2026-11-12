# Known Issues, Platform Caveats & Operational Quirks

This document provides a consolidated reference for OS quirks, network security constraints, background execution rules, and testing gotchas across Android versions and device manufacturers.

---

## 1. Android Version-Specific Behaviors

### Android 7.0 (API 24) vs. Android 7.1.1 (API 25): The TLS / Let's Encrypt Root CA Trap

- **The Quirk**: Android 7.0 and 7.1.0 only shipped with the legacy `DST Root CA X3` root certificate. Android **7.1.1** added `ISRG Root X1`. In September 2021, `DST Root CA X3` expired and Let's Encrypt ended their cross-signed compatibility chain. Consequently, **HTTPS endpoints whose certificate chains terminate at `ISRG Root X1` or `ISRG Root X2`—including default Let's Encrypt hosts (such as `proof.ovh.net`) and default Cloudflare R2 bucket hostnames (`*.r2.dev`)—fail on Android 7.0 with `SSLHandshakeException` (`CertPathValidatorException: Trust anchor for certification path not found`)**. On-device inspection of Android 7.0's 148-cert system trust store confirms that neither ISRG Root X1/X2 nor modern Let's Encrypt roots exist. (HTTPS endpoints terminating at older legacy CAs still in Android 7.0's store, such as DigiCert Global Root CA, remain functional).
- **Resolution for API 24 Testing**: For Android 7.0 testing, do not rely on default `*.r2.dev` or Let's Encrypt HTTPS endpoints. Use the local cleartext HTTP test server (`http://10.0.2.2:8080/large.bin?throttle_kbps=200`), which is allow-listed in `network_security_config.xml`. See [docs/demo-testing.md](demo-testing.md#6c-tls-on-70-use-the-local-server-not-https-presets) §6c for the practical testing workflow. Pre-Chrome-61 emulator images also require sideloading Chrome 119 (§6a) to execute modern JS modules.

### Android 8.0 (API 26): Notification Channels & Atomic Move

- **Notification Channels**: Starting in API 26, posting a notification without a valid `NotificationChannel` throws an `IllegalArgumentException`. The channel ID must be non-blank.
- **Atomic File Rename (`tryNioAtomicMove`)**: `java.nio.file.Files.move(..., ATOMIC_MOVE)` was introduced in API 26. On API 24/25, the plugin gracefully catches `NoClassDefFoundError` / `NoSuchMethodError` via reflection and falls back to `File.renameTo()`.

### Android 12 (API 31): Background Foreground Service Restrictions

- **The Restriction**: Apps cannot start Foreground Services while running in the background. Calling `setForeground()` from a worker triggered while backgrounded throws `ForegroundServiceStartNotAllowedException`.
- **Plugin Handling**: Wrapped in `runCatching` in `DownloadWorker.kt`. If the promotion fails, the download gracefully falls back to standard in-app execution, emitting a `FOREGROUND_SERVICE_FAILED` diagnostic event instead of crashing.

### Android 13 (API 33): Runtime `POST_NOTIFICATIONS` Permission

- **The Restriction**: Notifications require explicit user authorization at runtime.
- **Plugin Handling**:
  - If permission is denied or not requested, WorkManager continues downloading in the background.
  - Pre-API 33 devices auto-grant notifications; the plugin's `checkPermissions()` / `requestPermissions()` must resolve `notifications: "granted"`.

### Android 14 (API 34): Strict `foregroundServiceType` Enforcement

- **The Restriction**: Any service calling `startForeground()` must declare its `foregroundServiceType` in `AndroidManifest.xml` and pass the matching type to `ForegroundInfo`.
- **Plugin Handling**: The plugin's manifest declares `FOREGROUND_SERVICE_DATA_SYNC` and merges the service definition onto WorkManager:
  ```xml
  <service
      android:name="androidx.work.impl.foreground.SystemForegroundService"
      android:foregroundServiceType="dataSync"
      tools:node="merge" />
  ```

### Android 15+ (API 35/36): 6-Hour `dataSync` Runtime Limit

- **The Restriction**: Android 15 imposes a cumulative **6-hour timeout per 24 hours** on `dataSync` foreground services. Exceeding this limit throws `ForegroundServiceTimeoutException`.
- **Plugin Handling**: Long multi-GB downloads on 2G/3G connections approaching this threshold will need eventual migration to WorkManager's `setExpedited()` or Android 14's User-Initiated Data Transfer (`JobInfo.Builder.setUserInitiated(true)`).

---

## 2. Hardware & OEM Constraints (Stock vs. Custom ROMs)

### Aggressive Background Socket Termination (Samsung OneUI / Xiaomi HyperOS)

- **The Quirk**: Stock AOSP emulators adhere strictly to Android Open Source project specifications. In contrast, physical devices from Samsung, Xiaomi, Transsion (Tecno/Infinix), and Huawei implement proprietary battery managers that terminate active TCP sockets within 10–30 seconds of screen lock, even for active Foreground Services.
- **Plugin Mitigation**:
  - The plugin catches mid-stream socket disconnects, preserves the partial `.part` file, records `SOCKET_DROP_RESUMABLE` telemetry, and prepares for transparent resumption on the next attempt.
  - Physical OEM device testing (particularly Samsung or Xiaomi on Android 14/15) is required to validate screen-off socket recovery.

---

## 3. Capacitor Bridge & WebView Constraints

### Chrome / System WebView Bottleneck on Android 7.0–7.1

- Google Chrome dropped Android 7.0/7.1 support with Chrome 119 in late 2023.
- Nougat devices will never receive newer Chromium WebView updates. Frontend code inside the Capacitor WebView must be compatible with ES2020 and avoid CSS features unsupported in Chrome 119.

### Bridge Rate Limiting (Progress Flooding)

- Emitting Capacitor bridge events (`notifyListeners`) at high frequency (>20 events/sec) chokes the JavaScript event loop and causes UI stuttering.
- In `HttpRangeDownloader.kt`, progress snapshots are strictly throttled to a minimum interval of 250ms during active streaming, with unconditional snapshots emitted only upon state transitions (`STARTED`, `COMPLETED`, `PAUSED`, `ERROR`).

---

## 4. Testing & Emulator Tooling Caveats

### Yarn v1 `file:..` Dependency Staleness

- In `example-app/package.json`, `@picsa/capacitor-resumable-download` is linked via `"file:.."`.
- Yarn v1 **copies** the package directory into `node_modules` during install rather than symlinking. Any edits to `android/` or `dist/` do not propagate to the demo app unless synced via `yarn demo:sync` or `scripts/sync-plugin.mjs`.

### Agent Automation Gotchas (`agent-device`)

1. **Notification Shade Interception**: Tapping near the top status bar (y < 120px) pulls down the Android notification shade. If opened, press `$AGENT back` to dismiss.
2. **Snapshot Viewport Clipping**: On small screen emulators, items below the viewport fold (such as the second download task row) are omitted from the snapshot accessibility tree. Use `$AGENT scroll down 600 --settle` before reading refs.
3. **Ref Expiration During Active Transfers**: Prepending live progress log entries every 250ms refreshes DOM nodes and invalidates UI element refs (`@e24`). Pause active downloads before issuing interactive UI commands, or re-snapshot immediately before each press.
