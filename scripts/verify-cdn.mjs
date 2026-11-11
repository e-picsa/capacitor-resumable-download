#!/usr/bin/env node

/**
 * scripts/verify-cdn.mjs
 *
 * Verifies that a remote CDN / Storage URL correctly supports HTTP Range requests,
 * returns HTTP 206 Partial Content, does NOT compress binary assets with Gzip/Brotli,
 * and supplies a strong ETag (no W/"..." weak ETags) for reliable resume.
 *
 * Usage:
 *   node scripts/verify-cdn.mjs <url>
 *   yarn verify:cdn <url>
 */

const targetUrl = process.argv[2];

if (!targetUrl) {
  console.error('\x1b[31mError: Target URL parameter is required.\x1b[0m');
  console.log('\nUsage:');
  console.log('  yarn verify:cdn <url>');
  console.log('  node scripts/verify-cdn.mjs https://cdn.example.com/videos/sample.mp4\n');
  process.exit(1);
}

const colors = {
  reset: '\x1b[0m',
  green: '\x1b[32m',
  red: '\x1b[31m',
  yellow: '\x1b[33m',
  cyan: '\x1b[36m',
  bold: '\x1b[1m',
};

function pass(label) {
  console.log(`  ${colors.green}✔ [PASS]${colors.reset} ${label}`);
}

function fail(label, remedy) {
  console.log(`  ${colors.red}✖ [FAIL]${colors.reset} ${label}`);
  if (remedy) {
    console.log(`    ${colors.yellow}↳ Fix:${colors.reset} ${remedy}`);
  }
}

async function runVerification() {
  console.log(
    `\n${colors.bold}${colors.cyan}=== CDN / Storage Byte-Range Verification ===${colors.reset}`,
  );
  console.log(`Target: ${colors.cyan}${targetUrl}${colors.reset}\n`);

  let failureCount = 0;

  // Step 1: Send HEAD request to check basic headers and Accept-Ranges
  console.log(`${colors.bold}1. Checking HEAD Response Headers...${colors.reset}`);
  let headRes;
  try {
    headRes = await fetch(targetUrl, { method: 'HEAD' });
  } catch (err) {
    fail(
      `Failed to connect to ${targetUrl}: ${err.message}`,
      'Check network connection or URL correctness.',
    );
    process.exit(1);
  }

  const acceptRanges = headRes.headers.get('accept-ranges');
  if (acceptRanges && acceptRanges.toLowerCase().includes('bytes')) {
    pass(`Accept-Ranges header is present: "${acceptRanges}"`);
  } else {
    fail(
      `Missing or invalid Accept-Ranges header (got: "${acceptRanges}")`,
      'Configure server/CDN to send "Accept-Ranges: bytes". In Cloudflare, ensure byte-range requests are enabled.',
    );
    failureCount++;
  }

  const headEtag = headRes.headers.get('etag');
  if (!headEtag) {
    fail(
      'Missing ETag header in HEAD response',
      'Ensure origin server or CDN generates an ETag for this resource.',
    );
    failureCount++;
  } else if (headEtag.startsWith('W/')) {
    fail(
      `Weak ETag detected: "${headEtag}"`,
      'Cloudflare automatic compression (Brotli/Gzip) weakens ETags with W/ prefix. Disable Brotli/Gzip compression for binary file paths via Cloudflare Compression Rules to preserve strong ETags.',
    );
    failureCount++;
  } else {
    pass(`Strong ETag present: ${headEtag}`);
  }

  // Step 2: Send Range GET for bytes 0-1023
  console.log(
    `\n${colors.bold}2. Testing HTTP 206 Partial Content (bytes=0-1023)...${colors.reset}`,
  );
  let rangeRes;
  try {
    rangeRes = await fetch(targetUrl, {
      method: 'GET',
      headers: {
        Range: 'bytes=0-1023',
      },
    });
  } catch (err) {
    fail(`GET request with Range failed: ${err.message}`);
    process.exit(1);
  }

  if (rangeRes.status === 206) {
    pass(`Server returned HTTP 206 Partial Content`);
  } else if (rangeRes.status === 200) {
    fail(
      `Server returned HTTP 200 OK instead of HTTP 206`,
      'The CDN or server ignored the Range header and sent the entire file. Enable byte-range support on your CDN/origin server.',
    );
    failureCount++;
  } else {
    fail(`Unexpected HTTP status code: ${rangeRes.status} ${rangeRes.statusText}`);
    failureCount++;
  }

  const contentRange = rangeRes.headers.get('content-range');
  if (contentRange && contentRange.startsWith('bytes 0-1023/')) {
    pass(`Content-Range header matches expected byte slice: "${contentRange}"`);
  } else {
    fail(
      `Invalid or missing Content-Range header: "${contentRange}"`,
      'The server must return Content-Range: bytes 0-1023/<totalSize> when serving HTTP 206.',
    );
    failureCount++;
  }

  const contentEncoding = rangeRes.headers.get('content-encoding');
  if (contentEncoding && (contentEncoding.includes('gzip') || contentEncoding.includes('br'))) {
    fail(
      `Content-Encoding header detected: "${contentEncoding}"`,
      'CDN is compressing this range request with gzip/brotli. Dynamic compression interferes with byte-level streaming and resume offsets. Add a Cloudflare Compression Rule to disable compression for this path.',
    );
    failureCount++;
  } else {
    pass('No dynamic Content-Encoding (compression is disabled)');
  }

  // Step 3: Test If-Range Header
  console.log(`\n${colors.bold}3. Testing If-Range validation...${colors.reset}`);
  if (headEtag && !headEtag.startsWith('W/')) {
    const ifRangeRes = await fetch(targetUrl, {
      method: 'GET',
      headers: {
        Range: 'bytes=1024-2047',
        'If-Range': headEtag,
      },
    });

    if (ifRangeRes.status === 206) {
      pass(`If-Range with matching ETag returned HTTP 206 Partial Content`);
    } else {
      fail(
        `If-Range returned status ${ifRangeRes.status} instead of 206`,
        'Server failed to honor matching If-Range header.',
      );
      failureCount++;
    }
  } else {
    console.log(
      `  ${colors.yellow}⚠ [SKIP]${colors.reset} Skipping If-Range test due to missing/weak ETag.`,
    );
  }

  // Summary
  console.log(`\n${colors.bold}=== Verification Summary ===${colors.reset}`);
  if (failureCount === 0) {
    console.log(
      `${colors.green}${colors.bold}ALL CHECKS PASSED!${colors.reset} This URL fully supports reliable HTTP byte-range resumable downloads.\n`,
    );
    process.exit(0);
  } else {
    console.log(
      `${colors.red}${colors.bold}${failureCount} check(s) failed.${colors.reset} Refer to docs/cloudflare-cdn-setup.md for configuration steps.\n`,
    );
    process.exit(1);
  }
}

runVerification();
