#!/usr/bin/env node
/**
 * scripts/run-e2e.mjs
 *
 * Automated end-to-end (E2E) test runner for @picsa/capacitor-resumable-download.
 * Runs deterministic tests against an Android emulator or device via ADB and
 * the Chrome DevTools Protocol (CDP) on the Capacitor WebView.
 *
 * Requirements:
 *   - Node >= 22 (built-in WebSocket & fetch)
 *   - Android device or emulator connected via `adb`
 *
 * Usage:
 *   yarn test:e2e
 *   node scripts/run-e2e.mjs --skip-build
 *   node scripts/run-e2e.mjs --test "cancel"
 */

import { spawn, execSync } from 'node:child_process';
import http from 'node:http';
import net from 'node:net';
import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const REPO_ROOT = path.resolve(__dirname, '..');

// --- CLI Arguments Parsing ---
const args = process.argv.slice(2);
function getArg(name, fallback = null) {
  const prefix = `${name}=`;
  for (let i = 0; i < args.length; i++) {
    if (args[i] === name) return args[i + 1] ?? fallback;
    if (args[i].startsWith(prefix)) return args[i].slice(prefix.length);
  }
  return fallback;
}
const hasFlag = (name) => args.includes(name);

if (hasFlag('--help') || hasFlag('-h')) {
  console.log(`
Usage: node scripts/run-e2e.mjs [options]

Options:
  --port <port>         Mock HTTP server port (default: 8080)
  --size-mb <mb>        Test file size in MB (default: 5)
  --throttle <kbps>     Throttle speed for pause tests in KB/s (default: 300)
  --serial <serial>     Target ADB device serial (default: auto-detect)
  --skip-build          Skip rebuilding the plugin and demo app
  --skip-install        Skip installing APK to the device
  --keep-server         Do not stop the mock server on exit
  --test <filter>       Run only tests whose names contain <filter>
  --help, -h            Show this help message
`);
  process.exit(0);
}

const SERVER_PORT = parseInt(getArg('--port', '8080'), 10);
const FILE_SIZE_MB = parseInt(getArg('--size-mb', '3'), 10);
const THROTTLE_KBPS = parseInt(getArg('--throttle', '400'), 10);
const SPECIFIED_SERIAL = getArg('--serial', null);
const SKIP_BUILD = hasFlag('--skip-build');
const SKIP_INSTALL = hasFlag('--skip-install');
const KEEP_SERVER = hasFlag('--keep-server');
const TEST_FILTER = getArg('--test', '').toLowerCase();

const APP_PACKAGE = 'io.picsa.plugins.download.example';
const APP_ACTIVITY = `${APP_PACKAGE}/.MainActivity`;
const APK_PATH = path.join(
  REPO_ROOT,
  'example-app',
  'android',
  'app',
  'build',
  'outputs',
  'apk',
  'debug',
  'app-debug.apk',
);

// Formatting helpers
const c = {
  reset: '\x1b[0m',
  bold: '\x1b[1m',
  green: '\x1b[32m',
  red: '\x1b[31m',
  yellow: '\x1b[33m',
  cyan: '\x1b[36m',
  gray: '\x1b[90m',
};
function logStep(msg) {
  console.log(`${c.bold}${c.cyan}▶${c.reset} ${c.bold}${msg}${c.reset}`);
}
function logPass(msg) {
  console.log(`  ${c.green}✓${c.reset} ${msg}`);
}
function logFail(msg) {
  console.log(`  ${c.red}✗${c.reset} ${msg}`);
}
function logInfo(msg) {
  console.log(`  ${c.gray}${msg}${c.reset}`);
}

// Find a free TCP port on host
async function getFreePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.listen(0, '127.0.0.1', () => {
      const port = srv.address().port;
      srv.close(() => resolve(port));
    });
    srv.on('error', reject);
  });
}

// Sleep helper
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// Run ADB command
function adb(cmd, serial = null, timeoutMs = 60000) {
  const serialFlag = serial ? `-s ${serial}` : '';
  const fullCmd = `adb ${serialFlag} ${cmd}`.trim();
  try {
    return execSync(fullCmd, {
      encoding: 'utf8',
      stdio: ['pipe', 'pipe', 'pipe'],
      timeout: timeoutMs,
    }).trim();
  } catch (err) {
    const stderr = err.stderr ? err.stderr.toString() : err.message;
    throw new Error(`ADB failed: "${fullCmd}" -> ${stderr.trim()}`);
  }
}

// Detect ADB device
function detectDevice() {
  if (SPECIFIED_SERIAL) return SPECIFIED_SERIAL;
  const output = adb('devices');
  const lines = output
    .split('\n')
    .slice(1)
    .map((l) => l.trim())
    .filter(Boolean);
  const devices = lines
    .map((line) => {
      const [serial, state] = line.split(/\s+/);
      return { serial, state };
    })
    .filter((d) => d.state === 'device');

  if (devices.length === 0) {
    throw new Error(
      'No active Android device or emulator detected. Boot an emulator (e.g. Medium_Phone) or connect a device.',
    );
  }
  // Prefer emulator if multiple
  const emu = devices.find((d) => d.serial.startsWith('emulator-'));
  return (emu ?? devices[0]).serial;
}

// Check if mock server is up
async function isServerRunning(port) {
  return new Promise((resolve) => {
    const req = http.get(`http://127.0.0.1:${port}/health`, { timeout: 1000 }, (res) => {
      let data = '';
      res.on('data', (chunk) => (data += chunk));
      res.on('end', () => {
        try {
          const json = JSON.parse(data);
          resolve(json.ok === true ? json : false);
        } catch {
          resolve(false);
        }
      });
    });
    req.on('error', () => resolve(false));
    req.on('timeout', () => {
      req.destroy();
      resolve(false);
    });
  });
}

// Ensure mock server is running
let serverChildProcess = null;
async function ensureServer() {
  const existing = await isServerRunning(SERVER_PORT);
  if (existing) {
    logInfo(
      `Mock server already running on :${SERVER_PORT} (size=${existing.size}B, etag=${existing.etag})`,
    );
    return existing;
  }

  logStep(`Starting mock test server on :${SERVER_PORT} (${FILE_SIZE_MB}MB)...`);
  serverChildProcess = spawn(
    process.execPath,
    [
      path.join(REPO_ROOT, 'scripts', 'serve-test-file.mjs'),
      '--port',
      String(SERVER_PORT),
      '--size-mb',
      String(FILE_SIZE_MB),
    ],
    { stdio: ['ignore', 'pipe', 'pipe'], detached: false },
  );

  serverChildProcess.stdout.on('data', (d) => process.stdout.write(`[mock-server] ${d}`));
  serverChildProcess.stderr.on('data', (d) => process.stderr.write(`[mock-server] ${d}`));

  const start = Date.now();
  while (Date.now() - start < 10000) {
    const health = await isServerRunning(SERVER_PORT);
    if (health) {
      logPass(`Mock server listening on http://localhost:${SERVER_PORT}`);
      return health;
    }
    await sleep(250);
  }
  throw new Error(`Failed to start mock test server within 10s on port ${SERVER_PORT}`);
}

// CDP Client implementation
class CdpClient {
  constructor(wsUrl) {
    this.wsUrl = wsUrl;
    this.ws = null;
    this.nextId = 1;
    this.pending = new Map();
  }

  async connect(timeoutMs = 10000) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('CDP connection timeout')), timeoutMs);
      this.ws = new WebSocket(this.wsUrl);

      this.ws.addEventListener('open', () => {
        clearTimeout(timer);
        resolve();
      });

      this.ws.addEventListener('error', (err) => {
        clearTimeout(timer);
        reject(new Error(`CDP WebSocket error: ${err.message}`));
      });

      this.ws.addEventListener('message', (event) => {
        try {
          const msg = JSON.parse(event.data);
          if (msg.id && this.pending.has(msg.id)) {
            const { resolve, reject } = this.pending.get(msg.id);
            this.pending.delete(msg.id);
            if (msg.error) {
              reject(new Error(`CDP error: ${msg.error.message}`));
            } else {
              resolve(msg.result);
            }
          }
        } catch (err) {
          console.error('Failed to parse CDP message:', err);
        }
      });
    });
  }

  async evaluate(expression, timeoutMs = 25000) {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(
          new Error(`CDP evaluate timed out after ${timeoutMs}ms: "${expression.slice(0, 80)}..."`),
        );
      }, timeoutMs);

      this.pending.set(id, {
        resolve: (result) => {
          clearTimeout(timer);
          if (result?.exceptionDetails) {
            const desc =
              result.exceptionDetails.exception?.description || result.exceptionDetails.text;
            reject(new Error(`Eval error: ${desc}`));
          } else {
            resolve(result?.result?.value);
          }
        },
        reject: (err) => {
          clearTimeout(timer);
          reject(err);
        },
      });

      this.ws.send(
        JSON.stringify({
          id,
          method: 'Runtime.evaluate',
          params: {
            expression,
            awaitPromise: true,
            returnByValue: true,
          },
        }),
      );
    });
  }

  close() {
    if (this.ws) {
      try {
        this.ws.close();
      } catch {}
      this.ws = null;
    }
  }
}

// Connect to WebView CDP on device
async function connectToWebView(serial) {
  logStep('Connecting to Capacitor WebView via Chrome DevTools Protocol...');
  // Find webview unix socket
  let socketName = null;
  const startWait = Date.now();
  while (Date.now() - startWait < 15000) {
    const unixSockets = adb('shell cat /proc/net/unix', serial);
    const match = unixSockets.match(/@(webview_devtools_remote_\d+)/);
    if (match) {
      socketName = match[1];
      break;
    }
    await sleep(500);
  }

  if (!socketName) {
    throw new Error(
      'Could not find @webview_devtools_remote socket on device. Is the debug app running?',
    );
  }
  logInfo(`Found WebView socket: ${socketName}`);

  const hostPort = await getFreePort();
  adb(`forward tcp:${hostPort} localabstract:${socketName}`, serial);

  // Fetch JSON targets
  let targets = null;
  const jsonStart = Date.now();
  while (Date.now() - jsonStart < 10000) {
    try {
      const res = await fetch(`http://127.0.0.1:${hostPort}/json/list`);
      if (res.ok) {
        targets = await res.json();
        if (Array.isArray(targets) && targets.length > 0) break;
      }
    } catch {}
    await sleep(300);
  }

  if (!targets || targets.length === 0) {
    throw new Error('No inspectable WebView pages found via CDP.');
  }

  const page =
    targets.find(
      (t) => t.type === 'page' && (t.url.includes('localhost') || t.title.includes('Resumable')),
    ) ?? targets[0];
  logInfo(`Attaching to page: "${page.title}" (${page.url})`);

  const client = new CdpClient(page.webSocketDebuggerUrl);
  await client.connect();
  logPass('Attached to WebView CDP WebSocket.');

  // Wait until window.ResumableDownload is defined
  const waitPluginStart = Date.now();
  while (Date.now() - waitPluginStart < 15000) {
    const ready = await client.evaluate('typeof window.ResumableDownload !== "undefined"');
    if (ready) break;
    await sleep(250);
  }

  return { client, hostPort };
}

// Main test suite
async function main() {
  const startTime = Date.now();
  console.log(`\n${c.bold}=== Capacitor Resumable Download Automated E2E Runner ===${c.reset}\n`);

  let serial = null;
  let client = null;
  let hostPort = null;

  const testResults = [];
  function recordTest(name, passed, durationMs, error = null) {
    testResults.push({ name, passed, durationMs, error });
    if (passed) {
      logPass(`${c.bold}${name}${c.reset} (${durationMs}ms)`);
    } else {
      logFail(`${c.bold}${name}${c.reset} (${durationMs}ms): ${error?.message || error}`);
    }
  }

  try {
    // 1. Ensure mock server
    const serverHealth = await ensureServer();
    const EXPECTED_SIZE = serverHealth.size;
    const EXPECTED_ETAG = serverHealth.etag;

    // 2. Detect device
    logStep('Detecting Android device...');
    serial = detectDevice();
    logPass(`Selected device: ${serial}`);

    // 3. Port reversal (maps emulator's localhost:8080 to host's localhost:8080)
    logStep(
      `Configuring reverse port forwarding (device :${SERVER_PORT} -> host :${SERVER_PORT})...`,
    );
    adb(`reverse tcp:${SERVER_PORT} tcp:${SERVER_PORT}`, serial);
    logPass('Reverse port forwarded.');

    // 4. Build if required
    if (!SKIP_BUILD) {
      if (!fs.existsSync(APK_PATH)) {
        logStep('Debug APK not found. Running yarn demo:build...');
        execSync('yarn demo:build', { cwd: REPO_ROOT, stdio: 'inherit' });
      } else {
        logInfo('Using existing built APK (--skip-build to prevent rebuild checks)');
      }
    }

    // 5. Install if required
    if (!SKIP_INSTALL) {
      logStep(`Installing APK on ${serial}...`);
      adb(`install -r "${APK_PATH}"`, serial);
      logPass('APK installed successfully.');
    }

    // 6. Grant notification permissions and launch
    logStep('Granting POST_NOTIFICATIONS and launching app...');
    try {
      adb(`shell pm grant ${APP_PACKAGE} android.permission.POST_NOTIFICATIONS`, serial);
    } catch {}
    adb(`shell am start -W -n ${APP_ACTIVITY}`, serial);
    await sleep(1500);
    logPass('App launched.');

    // 7. Connect via CDP
    const cdpConn = await connectToWebView(serial);
    client = cdpConn.client;
    hostPort = cdpConn.hostPort;

    // Base download URL that routes to host test server:
    // Dynamically probe candidate loopback endpoints from inside WebView:
    // 127.0.0.1 (via adb reverse), localhost (via adb reverse), or 10.0.2.2 (QEMU gateway)
    logStep('Detecting reachable test server host from device...');
    const candidateHosts = [
      `127.0.0.1:${SERVER_PORT}`,
      `localhost:${SERVER_PORT}`,
      `10.0.2.2:${SERVER_PORT}`,
    ];
    let workingHost = null;
    for (const host of candidateHosts) {
      try {
        const ok = await client.evaluate(`
          fetch('http://${host}/health')
            .then(r => r.json())
            .then(d => d.ok === true)
            .catch(() => false)
        `);
        if (ok === true) {
          workingHost = host;
          break;
        }
      } catch {}
    }
    if (!workingHost) {
      throw new Error(
        `Android device cannot reach mock server on any candidate loopback host (${candidateHosts.join(', ')}). Check reverse port forwarding and network security config.`,
      );
    }
    logPass(`Device connected to test server via: http://${workingHost}`);
    const baseUrl = `http://${workingHost}/large.bin`;

    // -----------------------------------------------------------------------
    // TEST 1 & 2: Start Throttled Download, Pause, and Verify .part / metadata
    // -----------------------------------------------------------------------
    if (!TEST_FILTER || 'throttle pause and file status'.includes(TEST_FILTER)) {
      const testName = 'Start throttled download, pause in-flight, verify .part state';
      const t0 = Date.now();
      try {
        const downloadId = 'e2e-pause-test';
        const destPath = 'downloads/e2e-pause.bin';

        // Clear prior test artifacts (both active download and existing target file)
        await client.evaluate(`
          (async () => {
            window.__clearE2EEvents();
            await window.ResumableDownload.cancel({ downloadId: '${downloadId}' }).catch(() => {});
            await window.Filesystem.deleteFile({ path: '${destPath}', directory: window.Directory.Data }).catch(() => {});
          })()
        `);

        // Start throttled download in background (non-blocking in CDP)
        const throttleUrl = `${baseUrl}?throttle_kbps=${THROTTLE_KBPS}`;
        await client.evaluate(`
          window.__activeState = { done: false, error: null };
          window.ResumableDownload.download({
            downloadId: '${downloadId}',
            url: '${throttleUrl}',
            destinationPath: '${destPath}',
            directory: 'DATA'
          }).then(() => { window.__activeState.done = true; })
            .catch(err => { window.__activeState.done = true; window.__activeState.error = err.message || String(err); });
          undefined;
        `);

        // Wait until download has made partial progress (> 5% and >= 100KB)
        let paused = false;
        const waitStart = Date.now();
        let lastBytes = 0;
        while (Date.now() - waitStart < 15000) {
          const state = await client.evaluate('window.__activeState');
          if (state?.error) {
            throw new Error(`Download call rejected: ${state.error}`);
          }
          const diags = await client.evaluate(`
            window.__e2eDiagnosticEvents.filter(d => d.downloadId === '${downloadId}' && d.level === 'error')
          `);
          if (diags.length > 0) {
            throw new Error(`Download error diagnostic: ${diags[0].message} (${diags[0].code})`);
          }

          const events = await client.evaluate(`
            window.__e2eProgressEvents.filter(e => e.downloadId === '${downloadId}')
          `);
          if (events.length > 0) {
            const latest = events[events.length - 1];
            lastBytes = latest.bytesDownloaded;
            if (latest.status === 'error') {
              throw new Error(`Download failed with error event: ${latest.message || 'unknown'}`);
            }
            if (latest.bytesDownloaded >= 100 * 1024 || latest.progress >= 5) {
              await client.evaluate(
                `window.ResumableDownload.pause({ downloadId: '${downloadId}' })`,
              );
              paused = true;
              break;
            }
            if (latest.status === 'completed') {
              throw new Error(
                `Download completed before pause could be executed (size=${latest.bytesDownloaded})`,
              );
            }
          }
          await sleep(100);
        }

        if (!paused) {
          throw new Error(`Download did not reach 10% within 15s (lastBytes=${lastBytes})`);
        }

        await sleep(600); // Allow worker to finalize pause state

        // Verify status is paused
        const status = await client.evaluate(`
          window.ResumableDownload.getStatus({ downloadId: '${downloadId}' })
        `);
        if (status.status !== 'paused') {
          throw new Error(`Expected status to be "paused", got "${status.status}"`);
        }

        // Test 2 file assertions: getFileStatus
        const fileStatus = await client.evaluate(`
          window.ResumableDownload.getFileStatus({
            destinationPath: '${destPath}',
            directory: 'DATA'
          })
        `);

        if (fileStatus.exists) {
          throw new Error('Target destination file should NOT exist while download is paused');
        }
        if (!fileStatus.partExists) {
          throw new Error('.part temporary file MUST exist while download is paused');
        }
        if (fileStatus.partSize <= 0) {
          throw new Error(`partSize must be > 0, got ${fileStatus.partSize}`);
        }
        if (!fileStatus.etag) {
          throw new Error(`Expected saved ETag in meta sidecar, got: ${fileStatus.etag}`);
        }

        recordTest(testName, true, Date.now() - t0);
      } catch (err) {
        recordTest(testName, false, Date.now() - t0, err);
      }
    }

    // -----------------------------------------------------------------------
    // TEST 3 & 4: Resume from .part with Range header and Complete
    // -----------------------------------------------------------------------
    if (!TEST_FILTER || 'resume and completion'.includes(TEST_FILTER)) {
      const testName = 'Resume from .part with Range header and verify full completion';
      const t0 = Date.now();
      try {
        const downloadId = 'e2e-pause-test';
        const destPath = 'downloads/e2e-pause.bin';
        const throttleUrl = `${baseUrl}?throttle_kbps=${THROTTLE_KBPS}`;

        // Clear event buffer so we only inspect resume events
        await client.evaluate('window.__clearE2EEvents()');

        // Resume download (non-blocking in CDP)
        await client.evaluate(`
          window.__activeState = { done: false, error: null };
          window.ResumableDownload.resume({
            downloadId: '${downloadId}',
            url: '${throttleUrl}',
            destinationPath: '${destPath}'
          }).then(() => { window.__activeState.done = true; })
            .catch(err => { window.__activeState.done = true; window.__activeState.error = err.message || String(err); });
          undefined;
        `);

        // Wait for download to finish
        let completed = false;
        let resumedFromBytes = 0;
        const waitStart = Date.now();
        while (Date.now() - waitStart < 25000) {
          const state = await client.evaluate('window.__activeState');
          if (state?.error) {
            throw new Error(`Resume call rejected: ${state.error}`);
          }
          const events = await client.evaluate(`
            window.__e2eProgressEvents.filter(e => e.downloadId === '${downloadId}')
          `);
          for (const ev of events) {
            if (ev.resumedFromBytes && ev.resumedFromBytes > 0) {
              resumedFromBytes = ev.resumedFromBytes;
            }
            if (ev.status === 'completed') {
              completed = true;
              break;
            }
          }
          if (completed) break;
          await sleep(250);
        }

        if (!completed) {
          throw new Error('Resumed download did not complete within 25s');
        }
        if (resumedFromBytes <= 0) {
          throw new Error(`Expected resumedFromBytes > 0 on resume, got ${resumedFromBytes}`);
        }

        // Verify final file assertions
        const finalStatus = await client.evaluate(`
          window.ResumableDownload.getFileStatus({
            destinationPath: '${destPath}',
            directory: 'DATA'
          })
        `);

        if (!finalStatus.exists) {
          throw new Error('Target destination file MUST exist upon completion');
        }
        if (finalStatus.partExists) {
          throw new Error('.part file MUST be purged upon completion');
        }
        if (finalStatus.size !== EXPECTED_SIZE) {
          throw new Error(`File size mismatch: expected ${EXPECTED_SIZE}, got ${finalStatus.size}`);
        }

        recordTest(testName, true, Date.now() - t0);
      } catch (err) {
        recordTest(testName, false, Date.now() - t0, err);
      }
    }

    // -----------------------------------------------------------------------
    // TEST 5: Cancel Active Download and Purge Partial Files
    // -----------------------------------------------------------------------
    if (!TEST_FILTER || 'cancel'.includes(TEST_FILTER)) {
      const testName = 'Cancel active download and assert immediate file purging';
      const t0 = Date.now();
      try {
        const downloadId = 'e2e-cancel-test';
        const destPath = 'downloads/e2e-cancel.bin';

        await client.evaluate(`
          (async () => {
            window.__clearE2EEvents();
            await window.ResumableDownload.cancel({ downloadId: '${downloadId}' }).catch(() => {});
            await window.Filesystem.deleteFile({ path: '${destPath}', directory: window.Directory.Data }).catch(() => {});
          })()
        `);

        // Start throttled download non-blocking (throttle to ~150KB/s to guarantee in-flight cancellation)
        const cancelThrottleKbps = 150;
        await client.evaluate(`
          window.__activeState = { done: false, error: null };
          window.ResumableDownload.download({
            downloadId: '${downloadId}',
            url: '${baseUrl}?throttle_kbps=${cancelThrottleKbps}',
            destinationPath: '${destPath}',
            directory: 'DATA'
          }).then(() => { window.__activeState.done = true; })
            .catch(err => { window.__activeState.done = true; window.__activeState.error = err.message || String(err); });
          undefined;
        `);

        // Wait until download has started receiving data
        let started = false;
        const waitStart = Date.now();
        while (Date.now() - waitStart < 10000) {
          const state = await client.evaluate('window.__activeState');
          if (state?.error) {
            throw new Error(`Cancel test download rejected: ${state.error}`);
          }
          const events = await client.evaluate(`
            window.__e2eProgressEvents.filter(e => e.downloadId === '${downloadId}')
          `);
          if (events.length > 0) {
            const latest = events[events.length - 1];
            if (latest.status === 'completed') {
              throw new Error(
                `Download completed before cancellation could occur (size=${latest.bytesDownloaded})`,
              );
            }
            if (latest.bytesDownloaded > 0) {
              started = true;
              break;
            }
          }
          await sleep(100);
        }

        if (!started) {
          throw new Error('Download did not produce progress before cancel');
        }

        // Call cancel
        await client.evaluate(`
          window.ResumableDownload.cancel({ downloadId: '${downloadId}' })
        `);

        await sleep(600);

        // Verify status
        const status = await client.evaluate(`
          window.ResumableDownload.getStatus({ downloadId: '${downloadId}' })
        `);
        if (status.status !== 'cancelled') {
          throw new Error(`Expected status "cancelled", got "${status.status}"`);
        }

        // Verify partial files are purged
        const fileStatus = await client.evaluate(`
          window.ResumableDownload.getFileStatus({
            destinationPath: '${destPath}',
            directory: 'DATA'
          })
        `);

        if (fileStatus.exists) {
          throw new Error('Destination file should NOT exist after cancel');
        }
        if (fileStatus.partExists) {
          throw new Error('.part file MUST be purged from disk after cancel');
        }

        recordTest(testName, true, Date.now() - t0);
      } catch (err) {
        recordTest(testName, false, Date.now() - t0, err);
      }
    }

    // -----------------------------------------------------------------------
    // TEST 6: Network Drop Mid-Stream and Resilient Recovery
    // -----------------------------------------------------------------------
    if (!TEST_FILTER || 'network drop'.includes(TEST_FILTER) || 'recovery'.includes(TEST_FILTER)) {
      const testName = 'Simulate mid-stream network drop and resume recovery';
      const t0 = Date.now();
      try {
        const downloadId = 'e2e-drop-test';
        const destPath = 'downloads/e2e-drop.bin';
        const dropThresholdBytes = 512 * 1024; // 512KB

        await client.evaluate(`
          (async () => {
            window.__clearE2EEvents();
            await window.ResumableDownload.cancel({ downloadId: '${downloadId}' }).catch(() => {});
            await window.Filesystem.deleteFile({ path: '${destPath}', directory: window.Directory.Data }).catch(() => {});
          })()
        `);

        // Start download with server instructed to abort after 512KB
        const dropUrl = `${baseUrl}?throttle_kbps=${THROTTLE_KBPS}&drop_after_bytes=${dropThresholdBytes}`;
        await client.evaluate(`
          window.__activeState = { done: false, error: null };
          window.ResumableDownload.download({
            downloadId: '${downloadId}',
            url: '${dropUrl}',
            destinationPath: '${destPath}',
            directory: 'DATA'
          }).then(() => { window.__activeState.done = true; })
            .catch(err => { window.__activeState.done = true; window.__activeState.error = err.message || String(err); });
          undefined;
        `);

        // Wait for connection drop and diagnostic event or partial file write
        let dropDetected = false;
        const waitStart = Date.now();
        while (Date.now() - waitStart < 15000) {
          const diags = await client.evaluate(`
            window.__e2eDiagnosticEvents.filter(d => d.downloadId === '${downloadId}')
          `);
          const dropDiag = diags.find(
            (d) =>
              d.code === 'SOCKET_DROP_RESUMABLE' ||
              d.code === 'NETWORK_ERROR' ||
              d.message?.toLowerCase().includes('stream') ||
              d.message?.toLowerCase().includes('socket'),
          );
          if (dropDiag) {
            dropDetected = true;
            break;
          }
          await sleep(250);
        }

        await sleep(600);

        // Verify .part file has the bytes saved before the drop
        const partStatus = await client.evaluate(`
          window.ResumableDownload.getFileStatus({
            destinationPath: '${destPath}',
            directory: 'DATA'
          })
        `);

        if (!partStatus.partExists) {
          throw new Error('Partial .part file MUST be retained after network drop');
        }
        // Network buffers / chunking may deliver slightly less than the exact threshold
        const minExpectedSavedBytes = 384 * 1024;
        if (partStatus.partSize < minExpectedSavedBytes) {
          throw new Error(
            `Expected partSize >= ${minExpectedSavedBytes} bytes saved before drop, got ${partStatus.partSize}`,
          );
        }

        // Resume using same URL (server allows completion on Range request)
        await client.evaluate('window.__clearE2EEvents()');
        await client.evaluate(`
          window.__activeState = { done: false, error: null };
          window.ResumableDownload.resume({
            downloadId: '${downloadId}',
            url: '${dropUrl}',
            destinationPath: '${destPath}'
          }).then(() => { window.__activeState.done = true; })
            .catch(err => { window.__activeState.done = true; window.__activeState.error = err.message || String(err); });
          undefined;
        `);

        // Await completion
        let recovered = false;
        let resumeOffset = 0;
        const resumeStart = Date.now();
        while (Date.now() - resumeStart < 25000) {
          const events = await client.evaluate(`
            window.__e2eProgressEvents.filter(e => e.downloadId === '${downloadId}')
          `);
          for (const ev of events) {
            if (ev.resumedFromBytes && ev.resumedFromBytes >= minExpectedSavedBytes) {
              resumeOffset = ev.resumedFromBytes;
            }
            if (ev.status === 'completed') {
              recovered = true;
              break;
            }
          }
          if (recovered) break;
          await sleep(250);
        }

        if (!recovered) {
          throw new Error('Download failed to complete after resuming from network drop');
        }

        const finalStatus = await client.evaluate(`
          window.ResumableDownload.getFileStatus({
            destinationPath: '${destPath}',
            directory: 'DATA'
          })
        `);

        if (!finalStatus.exists || finalStatus.size !== EXPECTED_SIZE) {
          throw new Error(
            `Final file size mismatch after recovery: expected ${EXPECTED_SIZE}, got ${finalStatus.size}`,
          );
        }

        recordTest(testName, true, Date.now() - t0);
      } catch (err) {
        recordTest(testName, false, Date.now() - t0, err);
      }
    }
  } finally {
    // Teardown
    if (serial) {
      try {
        adb('logcat -d -v time > /tmp/e2e-logcat.txt', serial, 10000);
        logInfo('Captured logcat snapshot to /tmp/e2e-logcat.txt');
      } catch {}
    }

    if (client) {
      client.close();
    }
    if (serial) {
      try {
        adb(`reverse --remove tcp:${SERVER_PORT}`, serial, 5000);
      } catch {}
      if (hostPort) {
        try {
          adb(`forward --remove tcp:${hostPort}`, serial, 5000);
        } catch {}
      }
    }

    if (serverChildProcess && !KEEP_SERVER) {
      serverChildProcess.kill('SIGTERM');
    }
  }

  // Summary
  const totalDuration = ((Date.now() - startTime) / 1000).toFixed(2);
  const passedCount = testResults.filter((r) => r.passed).length;
  const failedCount = testResults.filter((r) => !r.passed).length;

  console.log(`\n${c.bold}=== Test Results Summary ===${c.reset}`);
  console.log(
    `Total: ${testResults.length} | Passed: ${c.green}${passedCount}${c.reset} | Failed: ${failedCount > 0 ? c.red + failedCount + c.reset : 0} (${totalDuration}s)\n`,
  );

  if (failedCount > 0) {
    process.exit(1);
  }
}

main().catch((err) => {
  console.error(`\n${c.red}${c.bold}E2E Test Runner Failed:${c.reset}`, err.message);
  if (serverChildProcess && !KEEP_SERVER) {
    serverChildProcess.kill('SIGTERM');
  }
  process.exit(1);
});
