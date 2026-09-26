# Wear Root-Cause Final — 2026-09-26

## Baseline and architecture

- Base: local `main` `d588a266`; `origin/main` `1919ace0` is contained in it.
- Continued candidate: `feature/master-system-stability`, based exactly on the same local `main`.
- Runtime paths traced: Mobile canonical state → bounded Wear protocol → Message/DataItem → persisted Wear state → resolver → targeted complications/tiles; shared CGM graph input → shared renderer → App/Tile surface.

## Root causes

- Typography: ProtoLayout uses the platform system face; previous source-string parity was insufficient. The candidate calibrates supported real weights against the app system face and covers glucose/meta/status text.
- Insulin activity: scale selection previously did not consistently derive from the visible activity stream. The candidate uses the independent `INSULIN_ACTIVITY` axis and visible history/prediction values across all four modes.
- Graph Tile: unsupported/incorrect inline-pixel delivery and divergent container geometry caused visible mismatch. The candidate uses the shared model/renderer, supported encoded inline pixels and one clipped full-surface geometry.
- Startup/reconnect: the same payload is intentionally sent through two transports, but the Wear callback decoded the complete payload synchronously before revision deduplication. It now rejects an already committed byte-identical payload before decode, and decode/merge/persistence/fan-out execute in one IO mutex.

## Quantitative code evidence

| Operation per identical dual delivery | Before | After |
|---|---:|---:|
| Callback-thread full JSON decodes | 2 | 0 |
| Total full JSON decodes | 2 | 1 |
| History merges/persistence/fan-out | 1 | 1 |
| Durable and immediate delivery guarantees | 2 transports | unchanged |

The regression payload is 90,000 bytes, matching the protocol ceiling. Hardware wall-clock, heap, reboot, crash and long-run measurements remain open until the watch is reachable.

## Quality gate

- 853 tests: 853 passed, 0 failures, 0 errors, 0 skipped across 150 reports.
- `test`, `assembleDebug`, Mobile/Wear/G7 Release assemblies, `lint`, `detekt` and `ktlintCheck`: successful in the final 1,103-task run (43 executed, 1,060 up-to-date).
- `git diff --check`: clean; line-ending notices are repository configuration notices, not whitespace errors.

## Hardware boundary

The Galaxy Watch was not present in the final ADB or mDNS inventory. No APK was installed and no cold-start, reboot, process-kill, heap, ANR, typography-on-device or live-CGM claim is made for this candidate. Those checks remain **HARDWARE-TEST OPEN**.

## Integrity and recovery

The fingerprint is written only after canonical persistence. Revision ordering, bounded history, LIVE/backfill identity, resolver freshness, boot receivers, sticky services and Bluetooth recovery remain intact. No polling, restart loop or history deletion was added.
