# Repo context — owned by this repo

Seeded once by the `repo-templates` sync; afterwards this file is owned by this
repo. Future syncs ignore it. Fill it in so audits ground findings in real
users and explicit scope instead of generic assumptions.

## Project summary

`@picsa/capacitor-resumable-download`: high-resilience Capacitor plugin (0.x,
Capacitor 8 + 9 dual support, Android 7.0/API 24 floor) for large file
downloads over unstable rural networks. HTTP Range resumption, sidecar ETag
tracking (`.part.meta.json`), and Jetpack WorkManager background workers.
Greenfield AI-first repo: strict 3-file native architecture with fast JVM
unit tests and an expanding emulator E2E matrix.

## User archetypes

- App dev shipping offline-first rural tools (e.g. PICSA field apps) that must download large content packs over intermittent links; broken means failed or corrupted downloads and re-downloads from zero.
- End user on budget/legacy hardware (API 24+) with expensive bandwidth; broken means battery drain, stalled notifications, or storage bloat from orphaned `.part` files.
- Maintainer evolving the plugin toward Capacitor 9 and multi-API E2E coverage; broken means untested Doze/permission paths and doc/code drift.

## Tech focus areas

- WorkManager lifecycle: Doze, preemption, cancel-vs-active worker races on `RandomAccessFile`, notification/foreground-service behaviour when permissions are denied.
- Sidecar ETag lifecycle: atomic cleanup on success/cancel, restart-from-zero on ETag change, orphaned `.part` handling.
- Bridge input validation (bad URLs → clean `DownloadOutcome.Failure`) and progress-throttle guarantees (250ms / 1% delta).
- Dual Capacitor 8/9 compatibility (peer ranges, deprecated bridge APIs, dynamic SDK defaults) without dropping the API 24 floor.

## Scope overrides

- Emphasise: worker lifecycle and cancellation races; permission-denied paths; ETag sidecar edge cases.
- Skip: none.
- Replace: do NOT flag the 3-file native layout (`HttpRangeDownloader.kt`, `DownloadWorker.kt`, `ResumableDownloadPlugin.kt`) as under-engineered — it is an intentional invariant (see `AGENTS.md` §1; new package layers needExplicit consensus). Judge new abstractions against it instead.

## Second-opinion preference

- Ask each time (no standing reviewer agreement yet).

## Audit file

- `AUDIT.md` at repo root (no previous audit — first run is the baseline).

## Out of scope

- `dist/`, `build/`, lockfiles, vendored code (mention only if the pipeline is broken).
- `ROADMAP.md` strategy content itself (audit the code behind it, not the plan).

## Known pain / history

- Greenfield repo; PR #10 landed a batch of audit-driven fixes (notification ID wipe, foreground `PendingIntent`, permission alias mismatch, bridge throttling, bad-URL handling, cancel race).
- In flight at time of writing: Capacitor 9 compatibility thread and automated Android E2E CI thread (Tier 2 single-emulator; Tier 3 multi-version matrix planned).
- Android 7.0 (API 24) is a hard support floor — do not recommend dropping legacy devices.
