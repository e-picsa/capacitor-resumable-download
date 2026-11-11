import type { PluginListenerHandle } from '@capacitor/core';

export interface DownloadOptions {
  /**
   * Unique identifier for tracking, pausing, resuming, or cancelling this download task.
   */
  downloadId: string;

  /**
   * Remote source URL (e.g. Cloudflare CDN, Supabase Storage, or S3).
   */
  url: string;

  /**
   * Relative or absolute destination path inside the specified Capacitor directory.
   * Example: 'picsa_extension/resources/intro.mp4'
   */
  destinationPath: string;

  /**
   * Target Capacitor directory storage location. Defaults to 'DATA'.
   * - DATA: App's private internal storage directory (persistent, not accessible by other apps).
   * - CACHE: App's cache directory (persistent until cleared by user or OS under storage pressure).
   * - EXTERNAL: App-specific external storage directory.
   */
  directory?: 'DATA' | 'CACHE' | 'EXTERNAL';

  /**
   * Optional custom HTTP request headers to accompany the request (e.g. Authorization).
   */
  headers?: Record<string, string>;

  /**
   * Optional Android system notification configuration.
   * If enabled, WorkManager attempts to promote the task to a foreground service with a persistent
   * progress notification so downloading continues seamlessly when the app is minimized.
   * If notification permissions are not granted, it gracefully falls back to in-app execution.
   */
  notification?: {
    enabled: boolean;
    title?: string;
    subtitle?: string;
    channelName?: string;
    channelId?: string;
    smallIcon?: string;
  };
}

export interface DownloadProgressEvent {
  downloadId: string;
  bytesDownloaded: number;
  totalBytes: number; // -1 if total length is unknown or chunked
  progress: number; // 0..100
  status: 'downloading' | 'paused' | 'completed' | 'error' | 'cancelled';
  resumedFromBytes?: number;
  throughputBytesPerSecond?: number;
}

export interface DownloadDiagnosticEvent {
  downloadId: string;
  level: 'info' | 'warn' | 'error';
  message: string;
  timestamp: number;
}

export interface DownloadResult {
  downloadId: string;
  uri: string; // Native file:// URI matching Filesystem format
  size: number;
  mimeType?: string;
  resumed: boolean;
}

export interface DownloadStatus {
  downloadId: string;
  status: 'idle' | 'downloading' | 'paused' | 'completed' | 'error' | 'cancelled';
  bytesDownloaded: number;
  totalBytes: number;
  uri?: string;
}

export interface FileStatus {
  path: string;
  exists: boolean;
  partExists: boolean;
  size: number;
  partSize: number;
  etag?: string;
}

export interface PermissionStatus {
  display: 'prompt' | 'prompt-with-rationale' | 'granted' | 'denied';
}

export interface ResumableDownloadPlugin {
  /**
   * Initiates or resumes a file download task.
   * If a matching .part file and .part.meta.json exist, sends Range and If-Range headers to resume.
   */
  download(options: DownloadOptions): Promise<DownloadResult>;

  /**
   * Pauses an active download task without deleting the temporary .part file or metadata.
   */
  pause(options: { downloadId: string }): Promise<void>;

  /**
   * Resumes a paused download task.
   */
  resume(options: { downloadId: string }): Promise<DownloadResult>;

  /**
   * Cancels a download task and cleans up any partial `.part` and `.part.meta.json` files.
   */
  cancel(options: { downloadId: string }): Promise<void>;

  /**
   * Retrieves the current download status of an active or recent download task.
   */
  getStatus(options: { downloadId: string }): Promise<DownloadStatus>;

  /**
   * Inspects the local filesystem to determine if the destination file or partial download exists.
   */
  getFileStatus(options: {
    destinationPath: string;
    directory?: 'DATA' | 'CACHE' | 'EXTERNAL';
  }): Promise<FileStatus>;

  /**
   * Checks the status of Android notification permissions (POST_NOTIFICATIONS).
   */
  checkPermissions(): Promise<PermissionStatus>;

  /**
   * Requests Android notification permissions (POST_NOTIFICATIONS).
   */
  requestPermissions(): Promise<PermissionStatus>;

  /**
   * Adds a listener for download progress events.
   * Throttled to max 1 event per 250ms or 1% delta to avoid bridge congestion.
   */
  addListener(
    eventName: 'downloadProgress',
    listenerFunc: (progress: DownloadProgressEvent) => void,
  ): Promise<PluginListenerHandle>;

  /**
   * Adds a listener for telemetry / diagnostic events (e.g. notification fallback warnings, HTTP resets).
   */
  addListener(
    eventName: 'downloadDiagnostic',
    listenerFunc: (diagnostic: DownloadDiagnosticEvent) => void,
  ): Promise<PluginListenerHandle>;

  /**
   * Removes all listeners for this plugin.
   */
  removeAllListeners(): Promise<void>;
}
