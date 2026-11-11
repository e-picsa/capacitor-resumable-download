import { WebPlugin } from '@capacitor/core';
import type {
  DownloadDiagnosticEvent,
  DownloadOptions,
  DownloadProgressEvent,
  DownloadResult,
  DownloadStatus,
  FileStatus,
  PermissionStatus,
  ResumableDownloadPlugin,
} from './definitions';

interface ActiveWebDownload {
  options: DownloadOptions;
  controller: AbortController;
  bytesDownloaded: number;
  totalBytes: number;
  status: 'downloading' | 'paused' | 'completed' | 'error' | 'cancelled';
  chunks: Uint8Array[];
}

export class ResumableDownloadWeb extends WebPlugin implements ResumableDownloadPlugin {
  private activeDownloads = new Map<string, ActiveWebDownload>();

  async download(options: DownloadOptions): Promise<DownloadResult> {
    const { downloadId, url, headers = {} } = options;
    const controller = new AbortController();

    const existing = this.activeDownloads.get(downloadId);
    let bytesDownloaded = 0;
    let chunks: Uint8Array[] = [];

    if (existing && existing.status === 'paused') {
      bytesDownloaded = existing.bytesDownloaded;
      chunks = existing.chunks;
    }

    const downloadState: ActiveWebDownload = {
      options,
      controller,
      bytesDownloaded,
      totalBytes: -1,
      status: 'downloading',
      chunks,
    };
    this.activeDownloads.set(downloadId, downloadState);

    const requestHeaders: Record<string, string> = { ...headers };
    if (bytesDownloaded > 0) {
      requestHeaders['Range'] = `bytes=${bytesDownloaded}-`;
    }

    try {
      const response = await fetch(url, {
        method: 'GET',
        headers: requestHeaders,
        signal: controller.signal,
      });

      if (!response.ok && response.status !== 206) {
        throw new Error(`HTTP error ${response.status}: ${response.statusText}`);
      }

      const contentLength = response.headers.get('content-length');
      let totalBytes = contentLength ? parseInt(contentLength, 10) : -1;

      if (response.status === 206) {
        const contentRange = response.headers.get('content-range');
        if (contentRange) {
          const match = contentRange.match(/\/(\d+|\*)$/);
          if (match && match[1] !== '*') {
            totalBytes = parseInt(match[1], 10);
          }
        }
      } else if (response.status === 200 && bytesDownloaded > 0) {
        // Server ignored Range header, reset downloaded bytes
        bytesDownloaded = 0;
        downloadState.bytesDownloaded = 0;
        downloadState.chunks = [];
        this.emitDiagnostic(
          downloadId,
          'warn',
          'Server returned 200 OK for Range request. Restarting download from byte 0.',
        );
      }

      downloadState.totalBytes = totalBytes;

      const reader = response.body?.getReader();
      if (!reader) {
        throw new Error('Response body is not readable');
      }

      let lastEmitTime = Date.now();

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        if (value) {
          downloadState.chunks.push(value);
          bytesDownloaded += value.length;
          downloadState.bytesDownloaded = bytesDownloaded;

          const now = Date.now();
          if (now - lastEmitTime >= 250) {
            lastEmitTime = now;
            const progress = totalBytes > 0 ? Math.round((bytesDownloaded / totalBytes) * 100) : 0;
            this.emitProgress({
              downloadId,
              bytesDownloaded,
              totalBytes,
              progress,
              status: 'downloading',
            });
          }
        }
      }

      downloadState.status = 'completed';
      const progress = 100;
      this.emitProgress({
        downloadId,
        bytesDownloaded,
        totalBytes: bytesDownloaded,
        progress,
        status: 'completed',
      });

      // Construct a Blob URL for web inspection
      const mimeType = response.headers.get('content-type') || 'application/octet-stream';
      const blob = new Blob(downloadState.chunks as unknown as BlobPart[], { type: mimeType });
      const objectUrl = URL.createObjectURL(blob);

      return {
        downloadId,
        uri: objectUrl,
        size: bytesDownloaded,
        mimeType,
        resumed: bytesDownloaded > 0 && response.status === 206,
      };
    } catch (err: any) {
      if (err.name === 'AbortError') {
        if (downloadState.status === 'paused') {
          this.emitProgress({
            downloadId,
            bytesDownloaded,
            totalBytes: downloadState.totalBytes,
            progress:
              downloadState.totalBytes > 0
                ? Math.round((bytesDownloaded / downloadState.totalBytes) * 100)
                : 0,
            status: 'paused',
          });
          return {
            downloadId,
            uri: '',
            size: bytesDownloaded,
            resumed: true,
          };
        }
        downloadState.status = 'cancelled';
        this.emitProgress({
          downloadId,
          bytesDownloaded,
          totalBytes: downloadState.totalBytes,
          progress: 0,
          status: 'cancelled',
        });
        throw new Error(`Download ${downloadId} was cancelled.`);
      }

      downloadState.status = 'error';
      this.emitProgress({
        downloadId,
        bytesDownloaded,
        totalBytes: downloadState.totalBytes,
        progress: 0,
        status: 'error',
      });
      throw err;
    }
  }

  async pause(options: { downloadId: string }): Promise<void> {
    const download = this.activeDownloads.get(options.downloadId);
    if (download && download.status === 'downloading') {
      download.status = 'paused';
      download.controller.abort();
    }
  }

  async resume(options: { downloadId: string }): Promise<DownloadResult> {
    const download = this.activeDownloads.get(options.downloadId);
    if (!download) {
      throw new Error(`Download ${options.downloadId} not found to resume.`);
    }
    return this.download(download.options);
  }

  async cancel(options: { downloadId: string }): Promise<void> {
    const download = this.activeDownloads.get(options.downloadId);
    if (download) {
      download.status = 'cancelled';
      download.controller.abort();
      this.activeDownloads.delete(options.downloadId);
    }
  }

  async getStatus(options: { downloadId: string }): Promise<DownloadStatus> {
    const download = this.activeDownloads.get(options.downloadId);
    if (!download) {
      return {
        downloadId: options.downloadId,
        status: 'idle',
        bytesDownloaded: 0,
        totalBytes: -1,
      };
    }
    return {
      downloadId: options.downloadId,
      status: download.status,
      bytesDownloaded: download.bytesDownloaded,
      totalBytes: download.totalBytes,
    };
  }

  async getFileStatus(options: {
    destinationPath: string;
    directory?: 'DATA' | 'CACHE' | 'EXTERNAL';
  }): Promise<FileStatus> {
    return {
      path: options.destinationPath,
      exists: false,
      partExists: false,
      size: 0,
      partSize: 0,
    };
  }

  async checkPermissions(): Promise<PermissionStatus> {
    return { display: 'granted' };
  }

  async requestPermissions(): Promise<PermissionStatus> {
    return { display: 'granted' };
  }

  private emitProgress(event: DownloadProgressEvent) {
    this.notifyListeners('downloadProgress', event);
  }

  private emitDiagnostic(downloadId: string, level: 'info' | 'warn' | 'error', message: string) {
    const event: DownloadDiagnosticEvent = {
      downloadId,
      level,
      message,
      timestamp: Date.now(),
    };
    this.notifyListeners('downloadDiagnostic', event);
  }
}
