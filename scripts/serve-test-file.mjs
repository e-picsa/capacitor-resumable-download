#!/usr/bin/env node
/**
 * scripts/serve-test-file.mjs
 *
 * Local deterministic test server for pause/resume demo testing.
 * Serves a generated binary file with proper HTTP Range support,
 * strong ETag, Accept-Ranges, and optional speed throttling.
 *
 * Usage:
 *   yarn serve:test-file                    # serves 50MB file on :8080
 *   yarn serve:test-file --size-mb 100 --port 8080
 *
 * Emulator access (Android emulator loopback to host):
 *   http://10.0.2.2:8080/large.bin
 *   http://10.0.2.2:8080/large.bin?throttle_kbps=200   # ~200 KB/s, ideal for pause/resume
 *
 * Verify with:
 *   yarn verify:cdn http://localhost:8080/large.bin
 *
 * Endpoints:
 *   GET /large.bin              - full or partial content (Range, If-Range supported)
 *   HEAD /large.bin             - headers only (Accept-Ranges, ETag, Content-Length)
 *   GET /health                 - { ok: true, size, etag }
 */

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const args = process.argv.slice(2);
function argValue(name, fallback) {
  const idx = args.findIndex((a) => a === name || a.startsWith(name + '='));
  if (idx === -1) return fallback;
  const a = args[idx];
  if (a.includes('=')) return a.split('=').slice(1).join('=');
  return args[idx + 1] ?? fallback;
}

const PORT = parseInt(argValue('--port', '8080'), 10);
const SIZE_MB = parseInt(argValue('--size-mb', '50'), 10);
const FILE_NAME = 'large.bin';
const FILE_PATH = path.join(process.cwd(), '.tmp-test-server', FILE_NAME);

// Ensure deterministic test file exists (random bytes, regenerated only if size mismatches)
function ensureTestFile() {
  const sizeBytes = SIZE_MB * 1024 * 1024;
  fs.mkdirSync(path.dirname(FILE_PATH), { recursive: true });
  if (fs.existsSync(FILE_PATH) && fs.statSync(FILE_PATH).size === sizeBytes) {
    return;
  }
  console.log(`Generating ${SIZE_MB}MB test file at ${FILE_PATH}...`);
  const fd = fs.openSync(FILE_PATH, 'w');
  const chunk = crypto.randomBytes(1024 * 1024);
  for (let i = 0; i < SIZE_MB; i++) {
    fs.writeSync(fd, chunk, 0, chunk.length, i * chunk.length);
  }
  fs.closeSync(fd);
  console.log('Test file generated.');
}

ensureTestFile();
const FILE_SIZE = fs.statSync(FILE_PATH).size;
// Strong ETag derived from size + mtime (stable across requests until file regenerates)
const mtime = fs.statSync(FILE_PATH).mtimeMs;
const ETAG = `"${crypto.createHash('md5').update(`${FILE_SIZE}-${mtime}`).digest('hex')}"`;

function sendHeaders(res, status, headers) {
  res.writeHead(status, headers);
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`);
  console.log(
    `${req.method} ${url.pathname}${url.search} Range=${req.headers.range ?? '-'} If-Range=${req.headers['if-range'] ?? '-'}`,
  );

  if (url.pathname === '/health') {
    sendHeaders(res, 200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true, size: FILE_SIZE, etag: ETAG }));
    return;
  }

  if (url.pathname !== '/large.bin' && url.pathname !== `/${FILE_NAME}`) {
    sendHeaders(res, 404, { 'Content-Type': 'text/plain' });
    res.end('Not found. Use /large.bin');
    return;
  }

  const commonHeaders = {
    'Accept-Ranges': 'bytes',
    ETag: ETAG,
    'Content-Type': 'application/octet-stream',
    'Cache-Control': 'no-store',
  };

  if (req.method === 'HEAD') {
    sendHeaders(res, 200, { ...commonHeaders, 'Content-Length': String(FILE_SIZE) });
    res.end();
    return;
  }

  const throttleKbps = parseInt(url.searchParams.get('throttle_kbps') ?? '0', 10);
  const throttleMsPerChunk = parseInt(url.searchParams.get('throttle_ms') ?? '0', 10);
  const rangeHeader = req.headers.range;
  const ifRange = req.headers['if-range'];

  let start = 0;
  let end = FILE_SIZE - 1;
  let isPartial = false;

  if (rangeHeader) {
    const m = rangeHeader.match(/bytes=(\d*)-(\d*)/);
    if (!m) {
      sendHeaders(res, 416, { ...commonHeaders, 'Content-Range': `bytes */${FILE_SIZE}` });
      res.end();
      return;
    }
    // If-Range mismatch -> ignore Range, send 200 full (per RFC 9110)
    if (ifRange && ifRange !== ETAG) {
      console.log(`  If-Range mismatch (${ifRange} != ${ETAG}), sending 200 full`);
    } else {
      const [, s, e] = m;
      start = s === '' ? Math.max(0, FILE_SIZE - parseInt(e, 10)) : parseInt(s, 10);
      end = e === '' ? FILE_SIZE - 1 : parseInt(e, 10);
      if (Number.isNaN(start) || Number.isNaN(end) || start > end || start >= FILE_SIZE) {
        sendHeaders(res, 416, { ...commonHeaders, 'Content-Range': `bytes */${FILE_SIZE}` });
        res.end();
        return;
      }
      end = Math.min(end, FILE_SIZE - 1);
      isPartial = true;
    }
  }

  const contentLength = end - start + 1;
  if (isPartial) {
    sendHeaders(res, 206, {
      ...commonHeaders,
      'Content-Range': `bytes ${start}-${end}/${FILE_SIZE}`,
      'Content-Length': String(contentLength),
    });
  } else {
    sendHeaders(res, 200, { ...commonHeaders, 'Content-Length': String(FILE_SIZE) });
  }

  if (throttleKbps > 0 || throttleMsPerChunk > 0) {
    // Throttled send: sequential chunk loop with sleep to simulate slow rural network.
    // Avoids stream pause/resume deadlocks on large Range requests.
    (async () => {
      const fd = fs.openSync(FILE_PATH, 'r');
      const CHUNK = 32 * 1024;
      const buf = Buffer.alloc(CHUNK);
      let offset = start;
      try {
        while (offset <= end) {
          if (res.destroyed || res.writableEnded) break;
          const toRead = Math.min(CHUNK, end - offset + 1);
          const read = fs.readSync(fd, buf, 0, toRead, offset);
          if (read <= 0) break;
          const chunk = buf.subarray(0, read);
          const bytesPerSec = throttleKbps > 0 ? throttleKbps * 1024 : chunk.length;
          const delayMs =
            throttleMsPerChunk > 0
              ? throttleMsPerChunk
              : Math.max(5, Math.round((read / bytesPerSec) * 1000));
          await new Promise((resolve) => setTimeout(resolve, delayMs));
          if (res.destroyed || res.writableEnded) break;
          const ok = res.write(chunk);
          if (!ok) {
            // Resolve on close too: if the client disconnects while
            // backpressured, 'drain' never fires and the fd would leak.
            await new Promise((resolve) => {
              const onDrain = () => {
                res.off('close', onClose);
                resolve();
              };
              const onClose = () => {
                res.off('drain', onDrain);
                resolve();
              };
              res.once('drain', onDrain);
              res.once('close', onClose);
            });
          }
          offset += read;
        }
        if (!res.destroyed && !res.writableEnded) res.end();
      } catch (err) {
        console.error('Throttled send error:', err.message);
        res.destroy(err);
      } finally {
        fs.closeSync(fd);
      }
    })();
  } else {
    fs.createReadStream(FILE_PATH, { start, end }).pipe(res);
  }
});

server.listen(PORT, '0.0.0.0', () => {
  console.log('');
  console.log('=== Local resumable-download test server ===');
  console.log(`File: ${FILE_PATH} (${(FILE_SIZE / 1048576).toFixed(1)} MB)`);
  console.log(`ETag: ${ETAG}`);
  console.log(`Health: http://localhost:${PORT}/health`);
  console.log(`Full speed (host): http://localhost:${PORT}/large.bin`);
  console.log(`Full speed (emulator): http://10.0.2.2:${PORT}/large.bin`);
  console.log(
    `Throttled (emulator, ~200KB/s): http://10.0.2.2:${PORT}/large.bin?throttle_kbps=200`,
  );
  console.log(`Verify: yarn verify:cdn http://localhost:${PORT}/large.bin`);
  console.log('');
});
