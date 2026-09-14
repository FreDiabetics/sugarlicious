# Project hardening final report — 2026-09-14

## Scope and repository state

- Branch: `feature/sugarwear-product-and-vigil-recovery`
- Starting commit: `5e30c411c423a0753977c4bac3f6d8de5c96f8b0`
- Implementation commits are listed in the Git history following that commit.
- No merge, push or automatic change to `main` was performed.

## Implemented corrections

- Repeated Wear Data Layer delivery is rejected before persistence, resolver,
  alert, tile and complication side effects while therapy-only changes remain
  deliverable.
- GATT callbacks are isolated by connection generation and expected
  characteristic; stale write results cannot fail the active write. Formerly
  unlimited callback queues now have explicit bounds.
- Wear Tile requests no longer block their callback thread with `runBlocking`;
  lifecycle-owned coroutines complete cancellable futures instead.
- The retired `G7BackfillSync` no-op and its call paths were removed.
- Canonical CGM history now has a deterministic total order across input
  permutations, including equal measurement timestamps from different sensor
  sessions.
- Low-risk Kotlin and Android deprecation warnings were removed or narrowly
  documented where a compatibility inspection has no replacement. Five WFF
  modules no longer request ineffective minification for debuggable builds.
- CI now runs Android Lint for every module, reviews newly introduced vulnerable
  dependencies and emits an SPDX JSON SBOM.

## Verification evidence

- Baseline before edits: `test assembleDebug` plus Mobile/Wear/G7 lint succeeded
  across 1,565 tasks.
- Clean final matrix after functional changes: 2,754 tasks, successful in
  15 minutes 23 seconds. It included all unit tests, all debug assemblies,
  complete Android Lint and the CI release-WFF matrix.
- Post-clean targeted warning regression: 528 tasks, successful in 12 minutes
  29 seconds after correcting the deliberately observed red notification test.
- Current committed end state: `test assembleDebug lint`, 1,865 tasks,
  successful in 5 minutes 58 seconds.
- Official WFF validator accepted every Watch Face Format XML document.
- Code-free verification passed for all 29 release Watchface APKs.
- `git diff --check` reported no whitespace errors on the final working tree.

## Findings that are not falsely declared fixed

- Android Lint has zero errors but still reports 682 warnings and 5 hints across
  the complete multi-module tree. The previous headline counts covered only
  three application modules; the new full-project gate makes the larger total
  visible. These require classification and incremental remediation rather than
  a blanket baseline or suppression.
- Kotlin compilation still identifies API migrations in the G7/Wear runtime:
  legacy BLE callback access, a legacy `connectGatt` overload, Android 15 system
  bar colour setters, ProtoLayout image builders and pre-channel notification
  priority. Changing these blindly can alter pairing, edge-to-edge layout,
  Tiles or alert delivery and was not presented as a safe cleanup.
- Robolectric, Conscrypt, AndroidX DataStore and ProtoLayout emit JDK native
  access/`Unsafe` warnings. They are dependency/toolchain upgrade work, not
  application warnings that should be hidden by source suppressions.
- G7 Lint writes 16 XML parser messages without source locations while still
  generating successful reports. The messages remain an explicit tooling
  investigation item.
- Detekt/ktlint, hosted secret scanning, production signing, crash/ANR telemetry,
  staged rollout and rollback rehearsal are not yet established.

## Hardware and release status

No ADB device was connected during the final check. Consequently this work does
not claim real-device BLE recovery, battery, AOD, round-display, crown scrolling
or sensor handoff acceptance. A live sensor was not unpaired as a side effect of
software verification.

PR #68 is the only open pull request. It targets `main`, is conflict-free, and
remains a Draft; therefore it was not treated as a finished PR and was not
merged. PR #65 and PR #54 are closed without merge and are not silently treated
as eligible work. Recent completed PRs targeting `main` are already merged.
