# @picsa/capacitor-resumable-download

[![CI](https://github.com/e-picsa/capacitor-resumable-download/actions/workflows/ci.yml/badge.svg)](https://github.com/e-picsa/capacitor-resumable-download/actions/workflows/ci.yml)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

A high-resilience Capacitor plugin for **large file downloads over unstable, low-bandwidth, and intermittent mobile networks**.

Built specifically for offline-first rural deployments (such as PICSA agricultural extension tools), this plugin solves the critical failure modes of `Filesystem.downloadFile()` by utilizing **HTTP Range requests, sidecar ETag tracking, and Android Jetpack WorkManager background workers**.

---

## Compatibility

| Plugin Version | Capacitor Version | Status         |
| :------------- | :---------------- | :------------- |
| `0.x.x`        | `8.x.x`           | Active support |
| `0.x.x`        | `9.x.x`           | Active support |

---

## Why This Plugin?

| Challenge                         | Standard `Filesystem.downloadFile()`                                                 | `@picsa/capacitor-resumable-download`                                                                                     |
| :-------------------------------- | :----------------------------------------------------------------------------------- | :------------------------------------------------------------------------------------------------------------------------ |
| **Connection Drop Mid-Download**  | Download fails; entire file must be re-downloaded from 0 bytes.                      | Emits socket drop error; `.part` file is kept intact; next attempt resumes from byte offset via `Range: bytes=<offset>-`. |
| **App Minimized / Screen Off**    | WebView execution is frozen; OS terminates the download socket.                      | Android Jetpack `WorkManager` continues download in the background with optional Foreground Service notification.         |
| **CDN ETag Changes Mid-Transfer** | Risk of corrupted hybrid files (half old version, half new version).                 | Sidecar `.part.meta.json` validates `If-Range: <etag>`. If origin file changed, gracefully restarts clean from byte 0.    |
| **Bridge Flooding**               | Frequent progress callbacks choke Capacitor JS bridge.                               | Progress callbacks throttled to max 1 update per 250ms or 1% delta.                                                       |
| **Memory Footprint**              | Reading downloaded data into JavaScript `Blob` causes Out-Of-Memory on 100MB+ files. | Streams directly to native disk (`Directory.Data` or `Directory.Cache`). Returns native file path.                        |

---

## Installation

```bash
npm install @picsa/capacitor-resumable-download
npx cap sync
```

---

## Android Setup

### Permissions

The plugin declares the following permissions in its `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
```

For Android 13+ (API 33+), prompt the user for notification permissions before enabling foreground notifications:

```ts
import { ResumableDownload } from '@picsa/capacitor-resumable-download';

const permission = await ResumableDownload.requestPermissions();
```

---

## Usage

### 1. Basic Resumable Download

```ts
import { ResumableDownload } from '@picsa/capacitor-resumable-download';

// Listen to progress
const progressSub = await ResumableDownload.addListener('downloadProgress', (event) => {
  console.log(
    `Download ${event.downloadId}: ${event.progress}% (${event.bytesDownloaded}/${event.totalBytes} bytes)`,
  );
});

// Start download
const result = await ResumableDownload.download({
  downloadId: 'farmer-training-video',
  url: 'https://cdn.example.com/videos/climate-smart-agriculture.mp4',
  destinationPath: 'downloads/climate-smart-agriculture.mp4',
  directory: 'DATA', // 'DATA' (default) | 'CACHE' | 'EXTERNAL'
  notification: {
    enabled: true,
    title: 'Downloading Training Video',
    subtitle: 'Climate-Smart Agriculture Guide',
  },
});

console.log('File saved to native URI:', result.uri);
```

### 2. Pause, Resume, and Cancel

```ts
// Pause download
await ResumableDownload.pause({ downloadId: 'farmer-training-video' });

// Resume download (resumes from existing .part file)
await ResumableDownload.resume({ downloadId: 'farmer-training-video' });

// Cancel and delete temporary .part files
await ResumableDownload.cancel({ downloadId: 'farmer-training-video' });
```

### 3. Check File & Partial Status on Disk

```ts
const status = await ResumableDownload.getFileStatus({
  destinationPath: 'downloads/climate-smart-agriculture.mp4',
  directory: 'DATA',
});

console.log('Target file exists:', status.exists);
console.log('Partial download in progress:', status.partExists);
console.log('Bytes downloaded so far:', status.partSize);
```

### 4. Telemetry & Sentry Integration

The plugin emits structured telemetry events (`downloadDiagnostic`) with machine-readable codes and context. Consumer applications can pipe these directly to Sentry as breadcrumbs or issues:

```ts
import * as Sentry from '@sentry/capacitor'; // or any monitoring client

// Forward diagnostic events to Sentry breadcrumbs
await ResumableDownload.addListener('downloadDiagnostic', (event) => {
  if (event.level === 'error') {
    Sentry.captureMessage(`[Download] ${event.message}`, {
      level: 'error',
      extra: { downloadId: event.downloadId, code: event.code, ...event.context },
    });
  } else {
    Sentry.addBreadcrumb({
      category: 'download',
      message: event.message,
      level: event.level,
      data: { code: event.code, ...event.context },
    });
  }
});

// Structured error rejections
try {
  await ResumableDownload.download(options);
} catch (err: any) {
  // err.code is the Java exception class (e.g. 'SocketTimeoutException', 'HttpException')
  // err.data contains { downloadId, exceptionClass, canResume, bytesDownloaded, httpStatus }
  Sentry.captureException(err, { extra: err.data });
}
```

---

## Verifying CDN Byte-Range Support

To test whether your CDN or Cloudflare endpoint properly supports HTTP 206 Partial Content and strong ETags:

```bash
yarn verify:cdn https://cdn.example.com/videos/sample.mp4
```

See [docs/cloudflare-cdn-setup.md](docs/cloudflare-cdn-setup.md) for full Cloudflare configuration guidance.

---

## Architecture Overview

1. **`HttpRangeDownloader.kt`**: Pure Kotlin streaming engine with zero Android framework dependencies. Manages `.part` and `.part.meta.json` sidecar files. Handles HTTP 206, 200, and 416 responses. Tested with fast JVM `MockWebServer` tests.
2. **`DownloadWorker.kt`**: Android Jetpack `WorkManager` `CoroutineWorker`. Provides resilient background execution and optional foreground progress notification.
3. **`ResumableDownloadPlugin.kt`**: Capacitor plugin bridge exposing TypeScript contracts to Angular / React / Vue.

---

## License

GPL-3.0
