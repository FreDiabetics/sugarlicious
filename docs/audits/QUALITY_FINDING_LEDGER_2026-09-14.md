# Quality finding ledger — 2026-09-14

## Source and total

The authoritative input is 38 `lint-results-debug.sarif` reports produced by
the all-module Android Lint gate at commit `29eeb4b`. They contain 687 results:
682 warnings and 5 hints. There are no Lint errors.

| Rule | Count | Initial ownership and disposition |
|---|---:|---|
| `UnusedResources` | 311 | Mostly WFF resources referenced from raw Watch Face Format XML, which Android Lint cannot follow. Prove WFF reachability before any deletion; remove only ordinary app resources with no runtime/XML reference. |
| `UseKtx` | 102 | Repository-owned style migration. Low risk but non-functional; batch by module after correctness findings. |
| `GradleDependency` | 39 | Dependency/version-catalog maintenance. Upgrade only with release-note and regression review. |
| `DataExtractionRules` | 31 | One G7 and 30 code-free WFF manifests. Explicit backup policy must be declared per package. |
| `NotShrinkingResources` | 30 | Intentional for resource-only WFF APKs: shrinking can remove assets referenced only by raw WFF XML. Retain with narrow module rationale. |
| `HardcodedText` | 25 | Mobile layouts and preview fixtures. Move user-visible runtime strings; preview-only sample content remains scoped. |
| `UnusedAttribute` | 22 | Inspect by API/resource qualifier and remove obsolete declarations when packaging tests stay green. |
| `ObsoleteSdkInt` | 18 | Repository-owned cleanup after confirming each module minimum SDK. |
| `SetTextI18n` | 17 | Replace user-visible concatenation with resources; formatting-only editor/test values need scoped treatment. |
| `VectorRaster` | 14 | Packaging recommendation, primarily Wear resources. Do not alter launcher/notification rendering without image checks. |
| `WearRecents` | 12 | G7 Wear manifest and notification behavior. Review against current Wear task/recents requirements. |
| `NewerVersionAvailable` | 10 | Version-catalog maintenance; not an automatic upgrade authorization. |
| `IconDuplicates` | 7 | Consolidate only byte/semantic duplicates that do not cross launcher density contracts. |
| `IconLauncherShape` | 6 | Validate adaptive icon masks on phone and both round Watch targets before changing. |
| `UseCompoundDrawables` | 6 | UI simplification; preserve accessibility and touch targets. |
| `ClickableViewAccessibility` | 6 | Repository-owned correctness/accessibility issue in Mobile, Wear, G7 and `ui-shared`; high priority. |
| `AutoboxingStateCreation` | 5 | Compose performance cleanup with state-behavior tests. |
| `DrawAllocation` | 4 | G7 graph/loader hot-path allocation; high-priority energy and smoothness work. |
| `TrustAllX509TrustManager` | 4 | All four point to the same BouncyCastle 1.81 dependency JAR, not application trust-manager code. Track dependency ownership; do not suppress application source. |
| `BatteryLife` | 3 | Battery-optimization settings requests in Mobile/Wear/G7. Intentional user-initiated reliability feature; verify call timing and document locally. |
| `SmallSp` | 3 | Visual/accessibility decision requiring round-display validation. |
| `DefaultLocale` | 3 | Repository-owned deterministic formatting fix. |
| `ApplySharedPref` | 2 | Review synchronous durability requirement; keep `commit` only where immediate cross-process visibility is tested. |
| `RtlEnabled` | 1 | Manifest internationalization policy. |
| `Overdraw` | 1 | UI performance cleanup after screenshot comparison. |
| `OldTargetApi` | 1 | Dependency/manifest target review. |
| `DiscouragedApi` | 1 | Inspect exact platform behavior before migration. |
| `ModifierParameter` | 1 | Compose API style cleanup. |
| `UselessParent` | 1 | Safe layout cleanup after rendering test. |
| `VectorPath` | 1 | Visual asset correction with pixel comparison. |

## Module concentration

The primary application reports are Mobile 133, Wear 102 and G7 94. The five
Sugarlicious/Vigil WFF packages contribute 135 results. Remaining results are
spread across shared libraries and the imported code-free WFF collection.

## G7 XML parser output

G7 Lint prints 16 `Content ist nicht zulässig in Prolog` messages without a
source location while completing successfully and emitting 94 normal SARIF
results. All 15 source XML files in `g7watch/src` parse successfully as XML.
There is no repository XML failure to patch yet. The output is therefore owned
as a Lint/dependency scanner investigation; the next diagnostic step is to
isolate the detector using per-rule runs and identify whether binary dependency
inspection (notably the repeated BouncyCastle finding) produces the stderr.

## Priority order

1. accessibility, draw allocations, locale determinism and true obsolete API
   guards;
2. BLE, ProtoLayout, system chrome and notification API migrations;
3. manifest backup/recents policy and ordinary application resources;
4. dependency upgrades and mechanical KTX/style findings;
5. WFF warnings only with WFF-aware reachability and code-free validation.

## Kotlin static-analysis gate

Detekt 1.23.8 and ktlint 1.5.0 (via Gradle plugin 14.2.0) now run across every
Kotlin-bearing module on Java 21. No baseline was introduced. The initial Detekt
inventory contained 574 findings after excluding numeric-literal and line-length
style noise; the enforced, project-specific gate is now clean. Complexity metrics
that are dominated by Compose rendering, protocol parsing, or formatter-dependent
line counts remain inventory concerns rather than build failures. Dead private
members, empty branches, performance traps, malformed naming and correctness rules
remain enforced.

Ktlint initially failed 69 source-set checks and reformatted 336 Kotlin and Gradle
Kotlin files. The repository-wide `ktlintCheck` now passes. Compose naming, stable
subsystem filenames, PascalCase Compose tokens, wildcard-import policy, and maximum
line length are explicitly configured instead of hidden behind a baseline. The
format-only change was followed by a successful 1,505-task `test assembleDebug`
matrix.

During static cleanup, obsolete complication previews, graph helpers, unused graph
constants, stale pairing locals, and unused UI helpers were removed only after a
repository-wide reference check.
