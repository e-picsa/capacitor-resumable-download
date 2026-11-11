import {
  ResumableDownload,
  type DownloadProgressEvent,
  type DownloadDiagnosticEvent,
} from '@picsa/capacitor-resumable-download';

const urlInput = document.getElementById('urlInput') as HTMLInputElement;
const destInput = document.getElementById('destInput') as HTMLInputElement;
const downloadIdInput = document.getElementById('downloadIdInput') as HTMLInputElement;
const notificationToggle = document.getElementById('notificationToggle') as HTMLInputElement;

const btnStart = document.getElementById('btnStart') as HTMLButtonElement;
const btnPause = document.getElementById('btnPause') as HTMLButtonElement;
const btnResume = document.getElementById('btnResume') as HTMLButtonElement;
const btnCancel = document.getElementById('btnCancel') as HTMLButtonElement;
const btnCheckStatus = document.getElementById('btnCheckStatus') as HTMLButtonElement;
const btnFileStatus = document.getElementById('btnFileStatus') as HTMLButtonElement;
const btnClearLogs = document.getElementById('btnClearLogs') as HTMLButtonElement;
const btnExportLogs = document.getElementById('btnExportLogs') as HTMLButtonElement;
const btnRefreshAll = document.getElementById('btnRefreshAll') as HTMLButtonElement;
const preset10MB = document.getElementById('preset10MB') as HTMLButtonElement;
const preset100MB = document.getElementById('preset100MB') as HTMLButtonElement;
const presetLocal = document.getElementById('presetLocal') as HTMLButtonElement;

const lblStatus = document.getElementById('lblStatus') as HTMLSpanElement;
const lblSelected = document.getElementById('lblSelected') as HTMLSpanElement;
const lblProgress = document.getElementById('lblProgress') as HTMLSpanElement;
const lblThroughput = document.getElementById('lblThroughput') as HTMLSpanElement;
const lblBytes = document.getElementById('lblBytes') as HTMLSpanElement;
const progressBar = document.getElementById('progressBar') as HTMLDivElement;
const diskDetails = document.getElementById('diskDetails') as HTMLDivElement;
const taskList = document.getElementById('taskList') as HTMLDivElement;
const logConsole = document.getElementById('logConsole') as HTMLDivElement;

interface TaskState {
  downloadId: string;
  url: string;
  destinationPath: string;
  status: string;
  progress: number;
  bytesDownloaded: number;
  totalBytes: number;
  throughputBytesPerSecond: number;
}

const tasks = new Map<string, TaskState>();
let selectedId: string | null = null;
let downloadIdDirty = false;

const logHistory: string[] = [];

function log(message: string, level: 'info' | 'warn' | 'error' | 'success' = 'info') {
  const time = new Date().toLocaleTimeString();
  const entryText = `[${time}] ${message}`;
  logHistory.push(`[${level.toUpperCase()}] ${entryText}`);
  // Mirror to console so agents can read via `adb logcat -s Capacitor/Console`
  // and to Android logcat via native emitDiagnostic telemetry.
  console.log(entryText);
  const entry = document.createElement('div');
  entry.className = `log-entry ${level}`;
  entry.textContent = entryText;
  logConsole.prepend(entry);
}

function slugifyDestination(dest: string): string {
  return (
    dest
      .trim()
      .replace(/^[./\\]+/, '')
      .replace(/[^a-zA-Z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '')
      .slice(0, 64) || 'download'
  );
}

function persistTasks() {
  try {
    localStorage.setItem(
      'demo-tasks',
      JSON.stringify(
        [...tasks.values()].map((t) => ({
          downloadId: t.downloadId,
          url: t.url,
          destinationPath: t.destinationPath,
        })),
      ),
    );
  } catch {
    // Storage may be unavailable; task list simply won't survive reload.
  }
}

function getSelectedTask(): TaskState | null {
  return (selectedId && tasks.get(selectedId)) || null;
}

function renderTasks() {
  const header = document.querySelector('.tasks-card h2');
  if (header) header.textContent = `Downloads (${tasks.size})`;
  if (tasks.size === 0) {
    taskList.innerHTML =
      '<div class="task-empty">No downloads yet. Configure above and press Start Download.</div>';
    console.log('[TASKS] n=0');
    return;
  }
  taskList.innerHTML = '';
  for (const task of tasks.values()) {
    const row = document.createElement('button');
    row.type = 'button';
    row.className = `task-row${task.downloadId === selectedId ? ' selected' : ''}`;
    row.dataset.downloadId = task.downloadId;
    row.setAttribute(
      'aria-label',
      `Select download ${task.downloadId}, status ${task.status}, ${task.progress}%`,
    );

    const name = document.createElement('span');
    name.className = 'task-name';
    name.textContent = task.downloadId;

    const meta = document.createElement('span');
    meta.className = `badge ${task.status}`;
    meta.textContent = `${task.status} ${task.progress}%`;

    row.append(name, meta);
    row.addEventListener('click', () => selectTask(task.downloadId));
    taskList.appendChild(row);
  }
  console.log(`[TASKS] n=${tasks.size} keys=${[...tasks.keys()].join(',')}`);
}

function renderMonitor(task: TaskState | null) {
  if (!task) {
    lblSelected.textContent = '';
    lblStatus.textContent = 'idle';
    lblStatus.className = 'badge';
    lblProgress.textContent = '0%';
    lblThroughput.textContent = '0 KB/s';
    lblBytes.textContent = '0 MB / 0 MB';
    progressBar.style.width = '0%';
    updateButtons('idle');
    return;
  }
  lblSelected.textContent = `(${task.downloadId})`;
  lblStatus.textContent = task.status;
  lblStatus.className = `badge ${task.status}`;
  lblProgress.textContent = `${task.progress}%`;
  progressBar.style.width = `${task.progress}%`;
  lblBytes.textContent = `${formatBytes(task.bytesDownloaded)} / ${
    task.totalBytes > 0 ? formatBytes(task.totalBytes) : 'Unknown'
  }`;
  if (task.throughputBytesPerSecond > 0) {
    lblThroughput.textContent = `${Math.round(task.throughputBytesPerSecond / 1024)} KB/s`;
  }
  updateButtons(task.status);
}

function updateButtons(status: string) {
  if (status === 'downloading') {
    btnStart.disabled = true;
    btnPause.disabled = false;
    btnResume.disabled = true;
    btnCancel.disabled = false;
  } else if (status === 'paused' || status === 'error') {
    // Error keeps .part on disk (SOCKET_DROP_RESUMABLE), so Resume stays available.
    btnStart.disabled = false;
    btnPause.disabled = true;
    btnResume.disabled = false;
    btnCancel.disabled = false;
  } else {
    btnStart.disabled = false;
    btnPause.disabled = true;
    btnResume.disabled = true;
    btnCancel.disabled = selectedId == null;
  }
}

function selectTask(downloadId: string) {
  selectedId = downloadId;
  const task = tasks.get(downloadId);
  if (task) {
    urlInput.value = task.url;
    destInput.value = task.destinationPath;
    downloadIdInput.value = task.downloadId;
    downloadIdDirty = true;
    renderMonitor(task);
    log(`Selected download: ${downloadId} (${task.status}, ${task.progress}%)`, 'info');
  }
  renderTasks();
}

function formatBytes(bytes: number): string {
  if (bytes <= 0) return '0 B';
  const mb = bytes / (1024 * 1024);
  return `${mb.toFixed(1)} MB`;
}

// Attach listeners
async function setupPluginListeners() {
  await ResumableDownload.addListener('downloadProgress', (event: DownloadProgressEvent) => {
    let task = tasks.get(event.downloadId);
    if (!task) {
      task = {
        downloadId: event.downloadId,
        url: urlInput.value.trim(),
        destinationPath: destInput.value.trim(),
        status: 'downloading',
        progress: 0,
        bytesDownloaded: 0,
        totalBytes: -1,
        throughputBytesPerSecond: 0,
      };
      tasks.set(event.downloadId, task);
      if (!selectedId) selectedId = event.downloadId;
    }
    task.status = event.status;
    task.progress = event.progress;
    task.bytesDownloaded = event.bytesDownloaded;
    task.totalBytes = event.totalBytes;
    task.throughputBytesPerSecond = event.throughputBytesPerSecond ?? 0;

    renderTasks();
    if (event.downloadId === selectedId) renderMonitor(task);
    log(
      `[${event.downloadId}] Progress: ${event.progress}% (${formatBytes(event.bytesDownloaded)}) - ${event.status}`,
      'info',
    );
  });

  await ResumableDownload.addListener('downloadDiagnostic', (diag: DownloadDiagnosticEvent) => {
    const codeTag = diag.code ? `[${diag.code}] ` : '';
    log(
      `[${diag.downloadId}] [DIAGNOSTIC ${diag.level.toUpperCase()}] ${codeTag}${diag.message}`,
      diag.level === 'error' ? 'error' : 'warn',
    );
  });
}

function readForm() {
  const url = urlInput.value.trim();
  const destinationPath = destInput.value.trim();
  const downloadId = (downloadIdInput.value.trim() || slugifyDestination(destinationPath)).slice(
    0,
    64,
  );
  return { url, destinationPath, downloadId };
}

btnStart.addEventListener('click', async () => {
  const { url, destinationPath, downloadId } = readForm();

  if (!url || !destinationPath) {
    alert('Please enter both URL and destination path.');
    return;
  }

  const existing = tasks.get(downloadId);
  if (existing && existing.status === 'downloading') {
    log(`[${downloadId}] Already downloading — pause it before restarting.`, 'warn');
    selectTask(downloadId);
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

  const task: TaskState = existing ?? {
    downloadId,
    url,
    destinationPath,
    status: 'downloading',
    progress: 0,
    bytesDownloaded: 0,
    totalBytes: -1,
    throughputBytesPerSecond: 0,
  };
  task.url = url;
  task.destinationPath = destinationPath;
  task.status = 'downloading';
  tasks.set(downloadId, task);
  selectedId = downloadId;
  downloadIdInput.value = downloadId;
  persistTasks();
  renderTasks();
  renderMonitor(task);

  log(`[${downloadId}] Starting download from ${url}`, 'info');

  try {
    const result = await ResumableDownload.download({
      downloadId,
      url,
      destinationPath,
      directory: 'DATA',
      notification: {
        enabled: notificationToggle.checked,
        title: `Downloading ${destinationPath.split('/').pop() ?? downloadId}`,
        subtitle: 'Resumable Download Demo',
      },
    });

    if (result.status === 'paused') {
      log(
        `[${downloadId}] Download paused at ${formatBytes(result.size)}. Partial file preserved on disk.`,
        'warn',
      );
      task.status = 'paused';
    } else {
      log(
        `[${downloadId}] Download completed successfully! URI: ${result.uri} (Resumed: ${result.resumed})`,
        'success',
      );
      task.status = 'completed';
      task.progress = 100;
      task.bytesDownloaded = result.size;
    }
  } catch (err: any) {
    log(`[${downloadId}] Download failed: ${err.message}`, 'error');
    task.status = 'error';
  }
  renderTasks();
  if (selectedId === downloadId) renderMonitor(task);
});

async function actOnSelected(
  action: 'pause' | 'resume' | 'cancel',
  fn: (id: string) => Promise<unknown>,
) {
  const task = getSelectedTask();
  if (!task) {
    alert('Select a download from the list first.');
    return;
  }
  log(
    `[${task.downloadId}] ${action === 'pause' ? 'Pausing' : action === 'resume' ? 'Resuming' : 'Cancelling'} download...`,
    'info',
  );
  try {
    if (action === 'resume') {
      const result = (await ResumableDownload.resume({
        downloadId: task.downloadId,
        url: task.url,
        destinationPath: task.destinationPath,
      })) as { status: string; uri: string; size: number };
      if (result.status === 'paused') {
        log(
          `[${task.downloadId}] Download paused at ${formatBytes(result.size)}. Partial file preserved on disk.`,
          'warn',
        );
        task.status = 'paused';
      } else {
        log(`[${task.downloadId}] Resumed download finished! URI: ${result.uri}`, 'success');
        task.status = 'completed';
      }
    } else {
      await fn(task.downloadId);
      if (action === 'pause') {
        task.status = 'paused';
        log(`[${task.downloadId}] Download paused. Partial file preserved on disk.`, 'warn');
      } else {
        task.status = 'idle';
        task.progress = 0;
        task.bytesDownloaded = 0;
        task.totalBytes = -1;
        log(`[${task.downloadId}] Download cancelled and .part files purged.`, 'warn');
      }
    }
  } catch (err: any) {
    log(`[${task.downloadId}] ${action} error: ${err.message}`, 'error');
    if (action === 'resume') task.status = 'error';
  }
  renderTasks();
  renderMonitor(task);
}

btnPause.addEventListener('click', () =>
  actOnSelected('pause', (id) => ResumableDownload.pause({ downloadId: id })),
);

btnResume.addEventListener('click', () =>
  actOnSelected('resume', async () => {
    /* handled inside actOnSelected to pass url/destinationPath */
  }),
);

btnCancel.addEventListener('click', () =>
  actOnSelected('cancel', (id) => ResumableDownload.cancel({ downloadId: id })),
);

btnCheckStatus.addEventListener('click', async () => {
  const task = getSelectedTask();
  if (!task) {
    alert('Select a download from the list first.');
    return;
  }
  try {
    const status = await ResumableDownload.getStatus({ downloadId: task.downloadId });
    task.status = status.status;
    task.bytesDownloaded = status.bytesDownloaded;
    if (status.totalBytes > 0) task.totalBytes = status.totalBytes;
    log(
      `[${task.downloadId}] Current task status: ${status.status}, bytes: ${status.bytesDownloaded}`,
      'info',
    );
  } catch (err: any) {
    log(`[${task.downloadId}] Status check error: ${err.message}`, 'error');
  }
  renderTasks();
  renderMonitor(task);
});

btnRefreshAll.addEventListener('click', async () => {
  if (tasks.size === 0) {
    log('No downloads to refresh.', 'warn');
    return;
  }
  for (const task of tasks.values()) {
    try {
      const status = await ResumableDownload.getStatus({ downloadId: task.downloadId });
      task.status = status.status;
      task.bytesDownloaded = status.bytesDownloaded;
      if (status.totalBytes > 0) task.totalBytes = status.totalBytes;
      if (task.totalBytes > 0 && task.bytesDownloaded >= 0) {
        task.progress = Math.min(100, Math.round((task.bytesDownloaded / task.totalBytes) * 100));
      }
    } catch (err: any) {
      log(`[${task.downloadId}] Refresh error: ${err.message}`, 'error');
    }
  }
  log(`Refreshed ${tasks.size} download(s).`, 'info');
  renderTasks();
  renderMonitor(getSelectedTask());
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

btnExportLogs.addEventListener('click', () => {
  // Dump full history to console for `adb logcat -s Capacitor/Console` capture by agents.
  console.log(`[EXPORT_LOGS_BEGIN] ${logHistory.length} entries`);
  for (const line of logHistory) {
    console.log(line);
  }
  console.log('[EXPORT_LOGS_END]');
  log(`Exported ${logHistory.length} log lines to console (logcat).`, 'success');
});

function applyPreset(url: string, dest: string, message: string) {
  urlInput.value = url;
  destInput.value = dest;
  downloadIdInput.value = slugifyDestination(dest);
  downloadIdDirty = false;
  const existing = tasks.get(downloadIdInput.value);
  if (existing) {
    // Jump to the existing task for this file so progress/resume follows it.
    selectTask(existing.downloadId);
    log(
      `Preset loaded — showing existing task ${existing.downloadId} (${existing.status}).`,
      'info',
    );
  } else {
    selectedId = null;
    renderMonitor(null);
    renderTasks();
    log(message, 'info');
  }
}

preset10MB.addEventListener('click', () => {
  applyPreset(
    'https://proof.ovh.net/files/10Mb.dat',
    'downloads/10Mb.dat',
    'Preset loaded: 10 MB quick test (verified 206 + strong ETag).',
  );
});

preset100MB.addEventListener('click', () => {
  applyPreset(
    'https://proof.ovh.net/files/100Mb.dat',
    'downloads/100Mb.dat',
    'Preset loaded: 100 MB pause/resume test (slower, easier to pause mid-flight).',
  );
});

presetLocal.addEventListener('click', () => {
  applyPreset(
    'http://10.0.2.2:8080/large.bin?throttle_kbps=200',
    'downloads/large.bin',
    'Preset loaded: local throttled server. Run `yarn serve:test-file` on host first.',
  );
});

destInput.addEventListener('input', () => {
  if (!downloadIdDirty) downloadIdInput.value = slugifyDestination(destInput.value);
});

downloadIdInput.addEventListener('input', () => {
  downloadIdDirty = true;
});

// Restore known tasks across reloads so files can be resumed later.
function restoreTasks() {
  try {
    const raw = localStorage.getItem('demo-tasks');
    if (!raw) return;
    const saved = JSON.parse(raw) as Array<{
      downloadId: string;
      url: string;
      destinationPath: string;
    }>;
    for (const s of saved) {
      if (!s.downloadId) continue;
      tasks.set(s.downloadId, {
        downloadId: s.downloadId,
        url: s.url,
        destinationPath: s.destinationPath,
        status: 'idle',
        progress: 0,
        bytesDownloaded: 0,
        totalBytes: -1,
        throughputBytesPerSecond: 0,
      });
    }
  } catch {
    // Corrupt storage — start fresh.
  }
}

// Initialize listeners on launch
restoreTasks();
renderTasks();
renderMonitor(null);
setupPluginListeners()
  .then(() => log('ResumableDownload plugin listeners ready.', 'success'))
  .catch((e) => log(`Failed to register listeners: ${e.message}`, 'error'));
