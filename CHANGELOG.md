# Changelog

## [0.0.2](https://github.com/e-picsa/capacitor-resumable-download/compare/capacitor-resumable-download-v0.0.1...capacitor-resumable-download-v0.0.2) (2026-11-13)


### Features

* **android:** add Capacitor 8 and Capacitor 9 dual compatibility support ([476680d](https://github.com/e-picsa/capacitor-resumable-download/commit/476680d68788a2201aed231275546c601104f0e6))
* **android:** add Capacitor 8 and Capacitor 9 dual compatibility support ([1ce4f34](https://github.com/e-picsa/capacitor-resumable-download/commit/1ce4f3417c13ff2d4cc064641d2d90442f8f96b9))
* **demo:** multi-file task list, sync-plugin guard, local throttled test server ([a161386](https://github.com/e-picsa/capacitor-resumable-download/commit/a16138630f23c1b992afae0cc8b7acc80adb3c3b))
* **demo:** multi-file task list, sync-plugin guard, local throttled test server ([11d47d9](https://github.com/e-picsa/capacitor-resumable-download/commit/11d47d95cdd67eb7fb3e993e92f58f47e45c3cd3))
* **demo:** show local test-server reachability in the app ([3ee4b98](https://github.com/e-picsa/capacitor-resumable-download/commit/3ee4b98da6957594c86de5759b82fcf9e66f26f8))
* **demo:** show local test-server reachability in the app ([fb60496](https://github.com/e-picsa/capacitor-resumable-download/commit/fb604962961597e632cbe28db0b3b1b092d0f24e))
* **demo:** status dot + popup for local server instead of pill, 5s poll ([ed7f620](https://github.com/e-picsa/capacitor-resumable-download/commit/ed7f6200b5c2dc842ee60c3fa308fa3820ef85b7))
* **demo:** streamline UI — readonly task ID, info popups, integrated rows ([0b81c8b](https://github.com/e-picsa/capacitor-resumable-download/commit/0b81c8b4db21d3f1d8b6b7770eca9810f7754288))
* scaffold capacitor-resumable-download plugin, android engine, example app, and ci ([cd18ce2](https://github.com/e-picsa/capacitor-resumable-download/commit/cd18ce288f18a8d82b98768cbfbe2ff6f756af47))
* scaffold capacitor-resumable-download plugin, android engine, example app, and ci ([1ae99c1](https://github.com/e-picsa/capacitor-resumable-download/commit/1ae99c1dcb548deed31b3bbd44caf00a9923424c))
* **test:** add automated Android E2E testing pipeline and CI workflow ([f1b8691](https://github.com/e-picsa/capacitor-resumable-download/commit/f1b869176085c83839171ca80758b226334f05e8))
* **test:** add automated Android E2E testing pipeline and CI workflow ([9637fba](https://github.com/e-picsa/capacitor-resumable-download/commit/9637fbaa130840176482cc890dd85505833146c4))


### Bug Fixes

* **android:** address PR review findings for zero-byte files and pause state ([c18f5b3](https://github.com/e-picsa/capacitor-resumable-download/commit/c18f5b37d238b1323964639aa60249f59da6ff17))
* **android:** address reviewer feedback on observer cleanup, header error handling, and notification rate limiting ([4711b69](https://github.com/e-picsa/capacitor-resumable-download/commit/4711b69ca8c14e2c1272a82bbf4633bc77129b13))
* **android:** harden worker pause/cancel/preemption race conditions and stream telemetry ([d20448e](https://github.com/e-picsa/capacitor-resumable-download/commit/d20448e84886c11f90e72abc7838b13e368184e7))
* **android:** key activeObservers by workRequest.id to avoid eviction race on rapid retries ([732e292](https://github.com/e-picsa/capacitor-resumable-download/commit/732e2920f0c4b126f2541ac7ac601b67c5c21cf5))
* **android:** resolve Android 7-15 compatibility, preemption data loss, and structured telemetry ([74bc38b](https://github.com/e-picsa/capacitor-resumable-download/commit/74bc38bbc945993049a88df21da745353562f5c4))
* **android:** resolve Android 7-15 compatibility, preemption data loss, and structured telemetry ([9b8d2cf](https://github.com/e-picsa/capacitor-resumable-download/commit/9b8d2cf213fdd3e2c1e7325d8e73c46a95160a9c))
* **android:** resolve code audit issues and notification improvements ([977ad8c](https://github.com/e-picsa/capacitor-resumable-download/commit/977ad8cd7a02a1602fe52ab218b8a76a9a90fc6d))
* **android:** resolve code audit issues and notification improvements ([6aa9281](https://github.com/e-picsa/capacitor-resumable-download/commit/6aa92818ee67867a266693562b56980cd4e75109))
* **android:** validate ETag and totalBytes consistency on 206 resume ([06a3601](https://github.com/e-picsa/capacitor-resumable-download/commit/06a3601e06809bb21aa0f8b52124cab7f4219bf9))
* **ci,android:** install node deps in jvm test job, prevent path traversal, preserve partial on pause ([bf085fd](https://github.com/e-picsa/capacitor-resumable-download/commit/bf085fd7ba696dbca928652c469b5e428e07fa71))
* **ci:** fix loopback connectivity via adb reverse, tighten timeouts, and capture logcat before emulator shutdown ([a9f3f16](https://github.com/e-picsa/capacitor-resumable-download/commit/a9f3f16dae1f220b1e0d82c8bb0d883606809c13))
* **ci:** use single-line emulator-runner script and guarantee safe logcat teardown ([aba5176](https://github.com/e-picsa/capacitor-resumable-download/commit/aba5176b1e3563b6fcab6d28be5e6da0224d0bd4))
* **demo:** refresh derived task ID on change events too ([e3d3918](https://github.com/e-picsa/capacitor-resumable-download/commit/e3d391834dfdb5b434cf22c20e5f4057613e7fc3))
* **demo:** refresh derived task ID on change events too ([138e5e1](https://github.com/e-picsa/capacitor-resumable-download/commit/138e5e108487e4b9893a7f88788d1c3ddc41ef38))
* enforce node 24 across repo and workflows, revert pr-agent model config ([ead8afd](https://github.com/e-picsa/capacitor-resumable-download/commit/ead8afdf3d09111ef9d00bc54bc5596df363bb38))
* **scripts:** resolve throttled-send backpressure wait on client close ([692c0e0](https://github.com/e-picsa/capacitor-resumable-download/commit/692c0e034a7d199af4cfc4702f83dd84da1c55ca))
* **test:** preserve query-based resource identity and use consistent URLs in E2E tests ([0c23203](https://github.com/e-picsa/capacitor-resumable-download/commit/0c23203e4ad46ad46a7582c54c2aeb7690dd9aad))
