import {
  ResumableDownload,
  type DownloadProgressEvent,
  type DownloadDiagnosticEvent,
} from '@picsa/capacitor-resumable-download';

const urlInput = document.getElementById('urlInput') as HTMLInputElement;
const destInput = document.getElementById('destInput') as HTMLInputElement;
const notificationToggle = document.getElementById('notificationToggle') as HTMLInputElement;

const btnStart = document.getElementById('btnStart') as HTMLButtonElement;
const btnPause = document.getElementById('btnPause') as HTMLButtonElement;
const btnResume = document.getElementById('btnResume') as HTMLButtonElement;
const btnCancel = document.getElementById('btnCancel') as HTMLButtonElement;
const btnCheckStatus = document.getElementById('btnCheckStatus') as HTMLButtonElement;
const btnFileStatus = document.getElementById('btnFileStatus') as HTMLButtonElement;
const btnClearLogs = document.getElementById('btnClearLogs') as HTMLButtonElement;

const lblStatus = document.getElementById('lblStatus') as HTMLSpanElement;
const lblProgress = document.getElementById('lblProgress') as HTMLSpanElement;
const lblThroughput = document.getElementById('lblThroughput') as HTMLSpanElement;
const lblBytes = document.getElementById('lblBytes') as HTMLSpanElement;
const progressBar = document.getElementById('progressBar') as HTMLDivElement;
const diskDetails = document.getElementById('diskDetails') as HTMLDivElement;
const logConsole = document.getElementById('logConsole') as HTMLDivElement;

const CURRENT_DOWNLOAD_ID = 'demo-video-task';

function log(message: string, level: 'info' | 'warn' | 'error' | 'success' = 'info') {
  const time = new Date().toLocaleTimeString();
  const entry = document.createElement('div');
  entry.className = `log-entry ${level}`;
  entry.textContent = `[${time}] ${message}`;
  logConsole.prepend(entry);
}

function updateUiState(status: string) {
  lblStatus.textContent = status;
  lblStatus.className = `badge ${status}`;

  if (status === 'downloading') {
    btnStart.disabled = true;
    btnPause.disabled = false;
    btnResume.disabled = true;
    btnCancel.disabled = false;
  } else if (status === 'paused') {
    btnStart.disabled = true;
    btnPause.disabled = true;
    btnResume.disabled = false;
    btnCancel.disabled = false;
  } else {
    btnStart.disabled = false;
    btnPause.disabled = true;
    btnResume.disabled = true;
    btnCancel.disabled = true;
  }
}

function formatBytes(bytes: number): string {
  if (bytes <= 0) return '0 B';
  const mb = bytes / (1024 * 1024);
  return `${mb.toFixed(1)} MB`;
}

// Attach listeners
async function setupPluginListeners() {
  await ResumableDownload.addListener('downloadProgress', (event: DownloadProgressEvent) => {
    if (event.downloadId !== CURRENT_DOWNLOAD_ID) return;

    lblProgress.textContent = `${event.progress}%`;
    progressBar.style.width = `${event.progress}%`;

    const downloadedStr = formatBytes(event.bytesDownloaded);
    const totalStr = event.totalBytes > 0 ? formatBytes(event.totalBytes) : 'Unknown';
    lblBytes.textContent = `${downloadedStr} / ${totalStr}`;

    if (event.throughputBytesPerSecond) {
      const kbps = Math.round(event.throughputBytesPerSecond / 1024);
      lblThroughput.textContent = `${kbps} KB/s`;
    }

    updateUiState(event.status);
    log(`Progress: ${event.progress}% (${downloadedStr}) - ${event.status}`, 'info');
  });

  await ResumableDownload.addListener('downloadDiagnostic', (diag: DownloadDiagnosticEvent) => {
    const codeTag = diag.code ? `[${diag.code}] ` : '';
    log(
      `[DIAGNOSTIC ${diag.level.toUpperCase()}] ${codeTag}${diag.message}`,
      diag.level === 'error' ? 'error' : 'warn',
    );
  });
}

btnStart.addEventListener('click', async () => {
  const url = urlInput.value.trim();
  const destinationPath = destInput.value.trim();

  if (!url || !destinationPath) {
    alert('Please enter both URL and destination path.');
    return;
  }

  // Request permissions if notification enabled
  if (notificationToggle.checked) {
    try {
      const perm = await ResumableDownload.requestPermissions();
      log(`Notification permission state: ${perm.display}`, 'info');
    } catch (e: any) {
      log(`Permission request failed: ${e.message}`, 'warn');
    }
  }

  log(`Starting download from ${url}`, 'info');
  updateUiState('downloading');

  try {
    const result = await ResumableDownload.download({
      downloadId: CURRENT_DOWNLOAD_ID,
      url,
      destinationPath,
      directory: 'DATA',
      notification: {
        enabled: notificationToggle.checked,
        title: 'Downloading Big Buck Bunny',
        subtitle: 'Resumable Mobile Demo',
      },
    });

    if (result.status === 'paused') {
      log(
        `Download paused at ${formatBytes(result.size)}. Partial file preserved on disk.`,
        'warn',
      );
      updateUiState('paused');
    } else {
      log(
        `Download completed successfully! URI: ${result.uri} (Resumed: ${result.resumed})`,
        'success',
      );
      updateUiState('completed');
    }
  } catch (err: any) {
    log(`Download failed: ${err.message}`, 'error');
    updateUiState('error');
  }
});

btnPause.addEventListener('click', async () => {
  log('Pausing download...', 'warn');
  try {
    await ResumableDownload.pause({ downloadId: CURRENT_DOWNLOAD_ID });
    updateUiState('paused');
    log('Download paused. Partial file preserved on disk.', 'warn');
  } catch (err: any) {
    log(`Pause error: ${err.message}`, 'error');
  }
});

btnResume.addEventListener('click', async () => {
  log('Resuming download...', 'info');
  updateUiState('downloading');
  try {
    const url = urlInput.value.trim();
    const destinationPath = destInput.value.trim();
    const result = await ResumableDownload.resume({
      downloadId: CURRENT_DOWNLOAD_ID,
      url,
      destinationPath,
    });
    if (result.status === 'paused') {
      log(
        `Download paused at ${formatBytes(result.size)}. Partial file preserved on disk.`,
        'warn',
      );
      updateUiState('paused');
    } else {
      log(`Resumed download finished! URI: ${result.uri}`, 'success');
      updateUiState('completed');
    }
  } catch (err: any) {
    log(`Resume error: ${err.message}`, 'error');
    updateUiState('error');
  }
});

btnCancel.addEventListener('click', async () => {
  log('Cancelling download and cleaning up partial files...', 'error');
  try {
    await ResumableDownload.cancel({ downloadId: CURRENT_DOWNLOAD_ID });
    updateUiState('idle');
    progressBar.style.width = '0%';
    lblProgress.textContent = '0%';
    lblBytes.textContent = '0 MB / 0 MB';
    log('Download cancelled and .part files purged.', 'warn');
  } catch (err: any) {
    log(`Cancel error: ${err.message}`, 'error');
  }
});

btnCheckStatus.addEventListener('click', async () => {
  try {
    const status = await ResumableDownload.getStatus({ downloadId: CURRENT_DOWNLOAD_ID });
    log(`Current task status: ${status.status}, bytes: ${status.bytesDownloaded}`, 'info');
  } catch (err: any) {
    log(`Status check error: ${err.message}`, 'error');
  }
});

btnFileStatus.addEventListener('click', async () => {
  const destinationPath = destInput.value.trim();
  try {
    const fileStat = await ResumableDownload.getFileStatus({
      destinationPath,
      directory: 'DATA',
    });

    diskDetails.innerHTML = `
      <div><strong>Target File Exists:</strong> ${fileStat.exists} (${formatBytes(fileStat.size)})</div>
      <div><strong>.part File Exists:</strong> ${fileStat.partExists} (${formatBytes(fileStat.partSize)})</div>
      <div><strong>Stored ETag:</strong> ${fileStat.etag || 'None'}</div>
      <div><strong>Full Path:</strong> <code>${fileStat.path}</code></div>
    `;
    log(`Disk check: targetExists=${fileStat.exists}, partExists=${fileStat.partExists}`, 'info');
  } catch (err: any) {
    log(`Disk inspection error: ${err.message}`, 'error');
  }
});

btnClearLogs.addEventListener('click', () => {
  logConsole.innerHTML = '';
});

// Initialize listeners on launch
setupPluginListeners()
  .then(() => log('ResumableDownload plugin listeners ready.', 'success'))
  .catch((e) => log(`Failed to register listeners: ${e.message}`, 'error'));
