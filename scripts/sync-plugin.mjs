#!/usr/bin/env node
/**
 * scripts/sync-plugin.mjs
 *
 * Copies the locally built plugin (android/ + dist/ + package.json) into the
 * example-app's node_modules copy that Capacitor actually builds from.
 *
 * Why this exists: yarn v1 `file:..` dependencies are COPIED at install time,
 * not symlinked. Editing `android/` or rebuilding `dist/` at the repo root does
 * NOT reach `example-app/node_modules/@picsa/capacitor-resumable-download/`,
 * so `./gradlew assembleDebug` silently builds stale native code. This script
 * makes the refresh explicit and fast (no full `yarn install --force`).
 *
 * Usage:
 *   node scripts/sync-plugin.mjs          # copy plugin -> example-app
 *   node scripts/sync-plugin.mjs --check  # exit 1 if stale (for CI / pre-build guards)
 */
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const ROOT = process.cwd();
const PLUGIN = 'capacitor-resumable-download';
const DEST = path.join(ROOT, 'example-app', 'node_modules', '@picsa', PLUGIN);

// Files that must be identical between repo root and the example-app copy.
const SYNCED_PATHS = ['package.json', 'dist', 'android'];

function hashFile(filePath) {
  return crypto.createHash('sha256').update(fs.readFileSync(filePath)).digest('hex');
}

function* walkFiles(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    // Skip Gradle / build outputs inside android/ — generated, not source.
    if (dir.includes(`${path.sep}android${path.sep}`) === false && entry.name === 'build') continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (['build', '.gradle', '.kotlin', 'node_modules'].includes(entry.name)) continue;
      yield* walkFiles(full);
    } else if (entry.isFile()) {
      yield full;
    }
  }
}

function snapshot(baseDir) {
  const out = new Map();
  for (const rel of SYNCED_PATHS) {
    const full = path.join(baseDir, rel);
    if (!fs.existsSync(full)) {
      out.set(rel, '<MISSING>');
      continue;
    }
    if (fs.statSync(full).isFile()) {
      out.set(rel, hashFile(full));
    } else {
      for (const f of walkFiles(full)) {
        out.set(path.relative(baseDir, f), hashFile(f));
      }
    }
  }
  return out;
}

const checkOnly = process.argv.includes('--check');

const rootSnap = snapshot(ROOT);
const destSnap = fs.existsSync(DEST) ? snapshot(DEST) : new Map([['<dir>', '<MISSING>']]);

const diffs = [];
for (const [key, hash] of rootSnap) {
  if (destSnap.get(key) !== hash) diffs.push(key);
}
for (const key of destSnap.keys()) {
  if (!rootSnap.has(key)) diffs.push(`${key} (orphaned in example-app copy)`);
}

if (diffs.length === 0) {
  console.log('plugin sync: example-app copy is up to date.');
  process.exit(0);
}

if (checkOnly) {
  console.error('plugin sync: STALE example-app copy — run `yarn demo:sync` first.');
  for (const d of diffs.slice(0, 20)) console.error(`  - ${d}`);
  if (diffs.length > 20) console.error(`  ... and ${diffs.length - 20} more`);
  process.exit(1);
}

// Guard: dist must be built before syncing, otherwise example-app gets no JS.
if (!fs.existsSync(path.join(ROOT, 'dist', 'esm', 'index.js'))) {
  console.error('plugin sync: dist/ missing — run `yarn build` first.');
  process.exit(1);
}

fs.mkdirSync(DEST, { recursive: true });
for (const rel of SYNCED_PATHS) {
  const src = path.join(ROOT, rel);
  const dst = path.join(DEST, rel);
  fs.rmSync(dst, { recursive: true, force: true });
  fs.cpSync(src, dst, { recursive: true });
}
console.log(
  `plugin sync: refreshed ${SYNCED_PATHS.join(', ')} -> example-app/node_modules/@picsa/${PLUGIN}/`,
);
