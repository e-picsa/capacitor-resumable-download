import { Filesystem, Directory } from '@capacitor/filesystem';
import {
  ResumableDownload,
  type DownloadProgressEvent,
  type DownloadDiagnosticEvent,
} from '@picsa/capacitor-resumable-download';

const urlInput = document.getElementById('urlInput') as HTMLInputElement;
const destInput = document.getElementById('destInput') as HTMLInputElement;
const derivedId = document.getElementById('derivedId') as HTMLElement;
const notificationToggle = document.getElementById('notificationToggle') as HTMLInputElement;

const btnStart = document.getElementById('btnStart') as HTMLButtonElement;
const btnFileStatus = document.getElementById('btnFileStatus') as HTMLButtonElement;
const btnClearLogs = document.getElementById('btnClearLogs') as HTMLButtonElement;
const btnExportLogs = document.getElementById('btnExportLogs') as HTMLButtonElement;
const preset10MB = document.getElementById('preset10MB') as HTMLButtonElement;
const preset100MB = document.getElementById('preset100MB') as HTMLButtonElement;
const presetLocal = document.getElementById('presetLocal') as HTMLButtonElement;

const diskDetails = document.getElementById('diskDetails') as HTMLDivElement;
const taskList = document.getElementById('taskList') as HTMLDivElement;
const logConsole = document.getElementById('logConsole') as HTMLDivElement;
const localServerDot = document.getElementById('localServerDot') as HTMLSpanElement;
const localServerInfo = document.getElementById('localServerInfo') as HTMLParagraphElement;

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
const busy = new Set<string>();

const logHistory: string[] = [];

// Expose on window for automated E2E testing and console inspection
declare global {
  interface Window {
    ResumableDownload: typeof ResumableDownload;
    Filesystem: typeof Filesystem;
    Directory: typeof Directory;
    __tasks: Map<string, TaskState>;
    __e2eProgressEvents: DownloadProgressEvent[];
    __e2eDiagnosticEvents: DownloadDiagnosticEvent[];
    __clearE2EEvents: () => void;
  }
}

window.ResumableDownload = ResumableDownload;
window.Filesystem = Filesystem;
window.Directory = Directory;
window.__tasks = tasks;
window.__e2eProgressEvents = [];
window.__e2eDiagnosticEvents = [];
window.__clearE2EEvents = () => {
  window.__e2eProgressEvents = [];
  window.__e2eDiagnosticEvents = [];
};

function log(message: string, level: 'info' | 'warn' | 'error' | 'success' = 'info') {
  const time = new Date().toLocaleTimeString();
  const entryText = `[${time}] ${message}`;
  logHistory.push(`[${level.toUpperCase()}] ${entryText}`);
  // Mirror to console so agents can read via `adb logcat -s Capacitor/Console`.
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

function refreshDerivedId() {
  derivedId.textContent = slugifyDestination(destInput.value);
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

function formatBytes(bytes: number): string {
  if (bytes <= 0) return '0 B';
  const mb = bytes / (1024 * 1024);
  return mb >= 10 ? `${Math.round(mb)} MB` : `${mb.toFixed(1)} MB`;
}

function formatThroughput(bps: number): string {
  if (bps <= 0) return '—';
  const kbps = bps / 1024;
  return kbps >= 1024 ? `${(kbps / 1024).toFixed(1)} MB/s` : `${Math.round(kbps)} KB/s`;
}

const LOCAL_SERVER_HEALTH_URL = 'http://10.0.2.2:8080/health';

// Probes the host test server so the UI can show whether the local preset
// will work. Manual timeout (not AbortSignal.timeout) for old WebViews.
async function checkLocalServer() {
  localServerDot.className = 'dot checking';
  localServerInfo.textContent = 'Checking for the local test server…';
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 3000);
  try {
    const res = await fetch(LOCAL_SERVER_HEALTH_URL, { signal: controller.signal });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const health = (await res.json()) as { ok?: boolean; size?: number };
    if (!health.ok) throw new Error('unhealthy');
    const size = typeof health.size === 'number' ? formatBytes(health.size) : null;
    localServerDot.className = 'dot reachable';
    localServerInfo.textContent = size
      ? `Reachable — serving a ${size} test file with artificial slowdown, ideal for pause/resume testing.`
      : 'Reachable.';
  } catch {
    localServerDot.className = 'dot unreachable';
    localServerInfo.textContent =
      'Unreachable — make sure the test server is running on the host computer, then retry.';
  } finally {
    clearTimeout(timer);
  }
}

setInterval(checkLocalServer, 5000);

function rowActions(status: string): Array<{ action: string; label: string; enabled: boolean }> {
  if (status === 'downloading') {
    return [
      { action: 'pause', label: 'Pause', enabled: true },
      { action: 'resume', label: 'Resume', enabled: false },
      { action: 'cancel', label: 'Cancel', enabled: true },
      { action: 'delete', label: 'Delete', enabled: true },
    ];
  }
  if (status === 'paused' || status === 'error') {
    return [
      { action: 'pause', label: 'Pause', enabled: false },
      { action: 'resume', label: 'Resume', enabled: true },
      { action: 'cancel', label: 'Cancel', enabled: true },
      { action: 'delete', label: 'Delete', enabled: true },
    ];
  }
  if (status === 'completed') {
    return [
      { action: 'pause', label: 'Pause', enabled: false },
      { action: 'resume', label: 'Resume', enabled: false },
      { action: 'cancel', label: 'Cancel', enabled: false },
      { action: 'delete', label: 'Delete', enabled: true },
    ];
  }
  return [
    { action: 'pause', label: 'Pause', enabled: false },
    { action: 'resume', label: 'Resume', enabled: true },
    { action: 'cancel', label: 'Cancel', enabled: false },
    { action: 'delete', label: 'Delete', enabled: true },
  ];
}

function buildRow(task: TaskState): HTMLDivElement {
  const row = document.createElement('div');
  row.className = 'task';
  row.dataset.downloadId = task.downloadId;

  const head = document.createElement('div');
  head.className = 'task-head';

  const name = document.createElement('span');
  name.className = 'task-name';
  name.textContent = task.downloadId;

  const badge = document.createElement('span');
  badge.className = `badge ${task.status}`;
  badge.dataset.role = 'status';
  badge.textContent = task.status;

  head.append(name, badge);

  const barWrap = document.createElement('div');
  barWrap.className = 'progress-bar-container task-bar';
  const bar = document.createElement('div');
  bar.className = 'progress-bar-fill';
  bar.dataset.role = 'bar';
  bar.style.width = `${task.progress}%`;
  barWrap.appendChild(bar);

  const meta = document.createElement('div');
  meta.className = 'task-meta';
  meta.dataset.role = 'meta';

  const actions = document.createElement('div');
  actions.className = 'task-actions';
  for (const { action, label } of rowActions(task.status)) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'btn-xs';
    btn.dataset.action = action;
    btn.textContent = label;
    btn.setAttribute('aria-label', `${label} ${task.downloadId}`);
    actions.appendChild(btn);
  }

  row.append(head, barWrap, meta, actions);
  updateRow(row, task);
  return row;
}

function updateRow(row: HTMLDivElement, task: TaskState) {
  const badge = row.querySelector<HTMLElement>('[data-role="status"]');
  if (badge) {
    badge.className = `badge ${task.status}`;
    badge.textContent = task.status;
  }
  const bar = row.querySelector<HTMLElement>('[data-role="bar"]');
  if (bar) bar.style.width = `${task.progress}%`;
  const meta = row.querySelector<HTMLElement>('[data-role="meta"]');
  if (meta) {
    const total = task.totalBytes > 0 ? formatBytes(task.totalBytes) : '?';
    meta.textContent = `${task.progress}% · ${formatBytes(task.bytesDownloaded)} / ${total} · ${formatThroughput(task.throughputBytesPerSecond)}`;
  }
  const enabled = new Map(rowActions(task.status).map((a) => [a.action, a.enabled]));
  row.querySelectorAll<HTMLButtonElement>('[data-action]').forEach((btn) => {
    btn.disabled = !enabled.get(btn.dataset.action ?? '');
  });
}

function renderTasks() {
  if (tasks.size === 0) {
    taskList.innerHTML =
      '<div class="task-empty">No downloads yet. Configure above and press Start Download.</div>';
    return;
  }
  taskList.innerHTML = '';
  for (const task of tasks.values()) {
    taskList.appendChild(buildRow(task));
  }
  console.log(`[TASKS] n=${tasks.size} keys=${[...tasks.keys()].join(',')}`);
}

function patchRow(task: TaskState) {
  const row = taskList.querySelector<HTMLDivElement>(`[data-download-id="${task.downloadId}"]`);
  if (row) updateRow(row, task);
  else renderTasks();
}

// Attach listeners
async function setupPluginListeners() {
  await ResumableDownload.addListener('downloadProgress', (event: DownloadProgressEvent) => {
    window.__e2eProgressEvents.push(event);
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
      persistTasks();
      renderTasks();
      return;
    }
    task.status = event.status;
    task.progress = event.progress;
    task.bytesDownloaded = event.bytesDownloaded;
    task.totalBytes = event.totalBytes;
    task.throughputBytesPerSecond = event.throughputBytesPerSecond ?? 0;
    patchRow(task);
  });

  await ResumableDownload.addListener('downloadDiagnostic', (diag: DownloadDiagnosticEvent) => {
    window.__e2eDiagnosticEvents.push(diag);
    const codeTag = diag.code ? `[${diag.code}] ` : '';
    log(
      `[${diag.downloadId}] [${diag.level.toUpperCase()}] ${codeTag}${diag.message}`,
      diag.level === 'error' ? 'error' : 'warn',
    );
  });
}

// Poll native status so rows stay accurate even when progress events stop
// (e.g. after reload, or the worker finishing while the app was backgrounded).
setInterval(async () => {
  if (busy.size > 0) return;
  for (const task of tasks.values()) {
    if (task.status !== 'downloading' && task.status !== 'paused' && task.status !== 'error') {
      continue;
    }
    try {
      const status = await ResumableDownload.getStatus({ downloadId: task.downloadId });
      if (
        status.status !== task.status ||
        status.bytesDownloaded !== task.bytesDownloaded ||
        (status.totalBytes > 0 && status.totalBytes !== task.totalBytes)
      ) {
        task.status = status.status;
        task.bytesDownloaded = status.bytesDownloaded;
        if (status.totalBytes > 0) {
          task.totalBytes = status.totalBytes;
          task.progress = Math.min(100, Math.round((task.bytesDownloaded / task.totalBytes) * 100));
        }
        patchRow(task);
      }
    } catch {
      // Task unknown natively; leave the last known state.
    }
  }
}, 3000);

async function runDownload(task: TaskState) {
  busy.add(task.downloadId);
  try {
    const result = await ResumableDownload.download({
      downloadId: task.downloadId,
      url: task.url,
      destinationPath: task.destinationPath,
      directory: 'DATA',
      notification: {
        enabled: notificationToggle.checked,
        title: `Downloading ${task.destinationPath.split('/').pop() ?? task.downloadId}`,
        subtitle: 'Resumable Download Demo',
      },
    });
    task.status = result.status === 'paused' ? 'paused' : 'completed';
    task.bytesDownloaded = result.size;
    if (result.status === 'completed') {
      task.progress = 100;
      log(
        `[${task.downloadId}] Completed (${formatBytes(result.size)}, resumed: ${result.resumed}).`,
        'success',
      );
    } else {
      log(
        `[${task.downloadId}] Paused at ${formatBytes(result.size)} — partial file kept.`,
        'warn',
      );
    }
  } catch (err: any) {
    task.status = 'error';
    log(`[${task.downloadId}] Failed: ${err.message}`, 'error');
  } finally {
    busy.delete(task.downloadId);
    patchRow(task);
  }
}

async function runResume(task: TaskState) {
  if (task.status === 'downloading' || busy.has(task.downloadId)) return;
  busy.add(task.downloadId);
  task.status = 'downloading';
  patchRow(task);
  try {
    const result = (await ResumableDownload.resume({
      downloadId: task.downloadId,
      url: task.url,
      destinationPath: task.destinationPath,
    })) as { status: string; uri: string; size: number };
    if (result.status === 'paused') {
      task.status = 'paused';
      task.bytesDownloaded = result.size;
      log(
        `[${task.downloadId}] Paused at ${formatBytes(result.size)} — partial file kept.`,
        'warn',
      );
    } else {
      task.status = 'completed';
      task.progress = 100;
      log(`[${task.downloadId}] Completed (${formatBytes(result.size)}).`, 'success');
    }
  } catch (err: any) {
    task.status = 'error';
    log(`[${task.downloadId}] Resume failed: ${err.message}`, 'error');
  } finally {
    busy.delete(task.downloadId);
    patchRow(task);
  }
}

btnStart.addEventListener('click', async () => {
  const url = urlInput.value.trim();
  const destinationPath = destInput.value.trim();
  if (!url || !destinationPath) {
    alert('Please enter both URL and destination path.');
    return;
  }
  const downloadId = slugifyDestination(destinationPath);
  refreshDerivedId();

  const existing = tasks.get(downloadId);
  if (existing && (existing.status === 'downloading' || busy.has(downloadId))) {
    log(`[${downloadId}] Already downloading.`, 'warn');
    return;
  }

  if (notificationToggle.checked) {
    try {
      await ResumableDownload.requestPermissions();
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
  if (task.status !== 'downloading') {
    task.status = 'downloading';
    task.progress = 0;
    task.bytesDownloaded = 0;
    task.totalBytes = -1;
    task.throughputBytesPerSecond = 0;
  }
  tasks.set(downloadId, task);
  persistTasks();
  renderTasks();
  log(`[${downloadId}] Starting download.`, 'info');
  await runDownload(task);
});

taskList.addEventListener('click', async (event) => {
  const btn = (event.target as HTMLElement).closest<HTMLButtonElement>('[data-action]');
  if (!btn || btn.disabled) return;
  const row = btn.closest<HTMLDivElement>('[data-download-id]');
  const task = row ? tasks.get(row.dataset.downloadId!) : undefined;
  if (!task) return;

  const action = btn.dataset.action;
  if (action === 'pause') {
    try {
      await ResumableDownload.pause({ downloadId: task.downloadId });
      log(`[${task.downloadId}] Pausing…`, 'info');
    } catch (err: any) {
      task.status = 'error';
      patchRow(task);
      log(`[${task.downloadId}] Pause failed: ${err.message}`, 'error');
    }
  } else if (action === 'resume') {
    await runResume(task);
  } else if (action === 'cancel') {
    try {
      await ResumableDownload.cancel({ downloadId: task.downloadId });
      task.status = 'cancelled';
      task.progress = 0;
      task.bytesDownloaded = 0;
      log(`[${task.downloadId}] Cancelled — partial files purged.`, 'warn');
    } catch (err: any) {
      log(`[${task.downloadId}] Cancel failed: ${err.message}`, 'error');
    }
    patchRow(task);
  } else if (action === 'delete') {
    try {
      await ResumableDownload.cancel({ downloadId: task.downloadId });
    } catch {
      // Best effort: task may already be finished or unknown natively.
    }
    try {
      await Filesystem.deleteFile({ path: task.destinationPath, directory: Directory.Data });
      log(`[${task.downloadId}] File deleted from device.`, 'warn');
    } catch {
      log(`[${task.downloadId}] No file on device to delete.`, 'info');
    }
    tasks.delete(task.downloadId);
    persistTasks();
    renderTasks();
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
    log(`Disk check: target=${fileStat.exists}, part=${fileStat.partExists}`, 'info');
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

function applyPreset(url: string, dest: string) {
  urlInput.value = url;
  destInput.value = dest;
  refreshDerivedId();
  log(`Preset loaded: ${dest} — press Start Download.`, 'info');
}

preset10MB.addEventListener('click', () => {
  applyPreset('https://proof.ovh.net/files/10Mb.dat', 'downloads/10Mb.dat');
});

preset100MB.addEventListener('click', () => {
  applyPreset('https://proof.ovh.net/files/100Mb.dat', 'downloads/100Mb.dat');
});

presetLocal.addEventListener('click', () => {
  applyPreset('http://10.0.2.2:8080/large.bin?throttle_kbps=200', 'downloads/large.bin');
});

destInput.addEventListener('input', refreshDerivedId);
// 'change' covers programmatic fills (e.g. a11y-driven input) that don't emit 'input'
// on older WebViews.
destInput.addEventListener('change', refreshDerivedId);

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
refreshDerivedId();
renderTasks();
checkLocalServer();
setupPluginListeners()
  .then(() => log('Plugin listeners ready.', 'success'))
  .catch((e) => log(`Failed to register listeners: ${e.message}`, 'error'));
