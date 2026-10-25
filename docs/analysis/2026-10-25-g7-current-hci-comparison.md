# G7: current original-app HCI comparison (2026-10-25)

## Evidence boundaries

The secondary Galaxy S24 runs the user-provided G7 1.6.1 test APK without Frida Gadget. The same signing certificate allowed replacing the Gadget build without clearing app data. Gadget was removed for the controlled comparison after startup ANRs and a hidden-API exception in obfuscated code. A specific anti-instrumentation trigger has not been proven.

The user confirmed that the Watch was outside sensor range during the failed collection windows around 18:43 and 18:48. Those failures cannot be used as evidence of an in-range collector regression.

Private HCI captures, bugreports and Watch diagnostic snapshots remain outside the repository under the user's reverse_engineering/captures/2026-10-25 directory. No credentials or raw medical packets are committed.

## HCI collection

- HCI logging: Enabled (full, not filtered); stack logging: Verbose.
- Android's always-available BLE scan kept the stack alive during an earlier simple toggle. Temporarily disabling that preference permitted confirmed OFF -> ON; its original value was restored immediately.
- Samsung's additional HCI socket switch is enabled, but TCP 8872 still closes immediately on this build. Live socket export is not claimed operational.
- A subsequent bugreport contains FS/data/log/bt/btsnoop_hci.log and its rotated .last file. These are actual full captures; unlike the embedded diagnostic summary, neither contains truncated records.
- Initial full files: current 96,814 bytes, rotated 344,559 bytes. The subsequent stable-cycle export contains a current file of 866,542 bytes. Its rotated file is the same older capture and must not be counted as independent evidence.
- Samsung vendor diagnostic packets and ACL continuation fragments must not be interpreted as standalone ATT PDUs. Attribution uses the observed sensor connection handle and actual ATT channel. Absolute HCI timestamps require correlation with Android wall-clock logs before comparison; relative protocol timings are used here.

## Observed protocol versus SugarWear

| Operation | Original application capture | Existing SugarWear implementation |
| --- | --- | --- |
| Current glucose | Control write 0x4e, matching indication | GLUCOSE_REQUEST = 0x4e; validate and publish live reading before history |
| Plain history | Control write 0x59, 9-byte request | G7CollectorBackfillProtocol.request builds the same opcode and little-endian clocks |
| Plain history delivery | 18-byte notification, then 9-byte notification: three records | AndroidG7Ble splits complete multiples of RECORD_BYTES = 9 |
| History range | 600-second inclusive range; three records, each 300 seconds apart; all within requested bounds | Bounded start/end and per-record sensor-clock validation |
| History completion | 0x59 control indication after the notifications, about 116 ms after request | Existing stream consumes records until 0x59 completion with bounded timeout guards |
| Other history transfer | 0x51 plus a separate longer transfer | Not interchangeable with the plain 0x59 path; encrypted payloads are not guessed or fed into the plain parser |

## Watch diagnostic snapshot

The saved 192-attempt history contained 131 successes, 49 recoverable errors, six cancellations and six fatal errors. These counters alone do not identify a current code defect. Earlier failures include missing advertisements, GATT 133, interrupted processes and authentication failures; the latter must be correlated with sensor/session changes rather than combined into one radio-failure category.

Six consecutive successful validated live cycles were recorded through 18:38 before the subsequently confirmed out-of-range period. Automatic recovery after returning to range was observed at 18:57:13: attempt 9261 completed SUCCESS_FRESH, with a validated live sample stored locally, without a manual reconnect or sensor reset.

## Completion status

- Full current HCI export: verified through actual untruncated files.
- Original-app current-read and plain-backfill wire format versus existing code: verified as described above.
- Frida decryption/application hooks: not active and not claimed complete.
- New collector behavior fix: none justified solely by these captures. Do not change authentication, scan timing or packet decoding based on the out-of-range failures.
- End-to-end recovery after the user-confirmed range loss: observed successfully in attempt 9261.
- Historical failure audit remains open: this capture does not justify claiming every older failure fixed.


## Follow-up: proved Vigil update and stale-history defects

The user observed a signal-loss label and only one old dot after an otherwise successful collection. Two actual code paths explain these symptoms:

1. `resolveWatchDirect` deliberately enters NO_SOURCE when the latest direct reading is stale. The history merge used only `canonicalSource == WATCH_G7_DIRECT` as its inclusion gate, so it threw away all persisted direct history precisely at signal loss. The header retained its last value, and the graph appended that single value as its only dot. WATCH_ONLY now retains direct history independently of freshness; NO_SOURCE and the original measurement timestamps remain unchanged. Regression coverage includes six graph windows and explicitly asserts NO_SOURCE.
2. Database publication notified Sugarlicious Wear (`app.aapswear`) and tiles, but the active Vigil WFF points to providers hosted by SugarWear (`app.aapswear.g7watch`). The compatibility receiver requested components in its own Sugarlicious package. Consequently, the actual providers could retain old images until their next system refresh. The existing post-commit/coalesced database publication now directly requests the Vigil providers in the collector package as well. No timer or extra polling is introduced, and historical batches retain their single fanout.

The Vigil signal-loss label is explicitly red in active and ambient rendering. This does not change sensor state, authentication, freshness, alarm thresholds or timestamps. Regression tests verify red pixels and immediate requests for the collector-hosted header, graph and ambient graph components.


### Verification and device proof

The initial update/history fix passed 49 targeted tests (provider outcome, canonical history, Vigil rendering and collector database), build, Detekt, ktlint and Lint (no issues). Installed on Galaxy Watch Ultra without sensor reset. At 19:13:20 attempt 9265 completed SUCCESS_FRESH; the immediately captured Vigil frame showed 0m and the retained multi-point history. Raw screenshots remain private.

Live provisional publication is additionally coalesced until the matching persisted sensor state has been saved. This prevents the first update racing an UNKNOWN/previous error state before optional backfill completes. A regression checks that a coalesced pair of inserts produces no early Vigil request and exactly one header request after the block.


## Follow-up: collector radio recovery fixes

Two additional defects were found in the real AndroidG7Collector loop (not UI code):

1. A cached earlier GATT-133 exception could replace a later terminal failure after a successful fallback scan/reconnect. A real Robolectric BluetoothGatt sequence reproduces two 133 responses, one successful discovery, then missing G7 service. Before the fix it incorrectly returned G7-GATT-133; after the fix it returns G7-GATT-204 and closes every platform client. Authentication errors at the same terminal boundary likewise retain their actual cause rather than being hidden behind stale radio state. Existing empty-fallback classification and bounded retries are preserved.
2. The bond-reconnect catch delayed and published RECOVERING before its finally block closed the old GATT client. It now closes immediately on entering that catch, before persistence, delay or another connection. A regression injects the real bond-reconnect exception at the registered-client boundary and checks that the platform client is closed when RECOVERING is delivered. Existing cleanup remains idempotent.

These are code-path/reproduction-proven defects; the retained historical logs do not permit assigning every old 133 or authentication error to them. Out-of-range failures, package replacement and low-memory process deaths remain separately classified. No sensor reset, bond deletion, authentication bypass, unbounded scan or new polling was introduced.


Radio-fix validation: the reproduced terminal-cause test failed on the previous implementation (expected G7-GATT-204, returned G7-GATT-133) and passed after removing the stale exception replacement. Both new radio regressions and the adjacent GATT critical-path/recovery/reliability tests passed. Collector build, Detekt, ktlint and Lint passed with no lint issues. The APK was installed with no active collection attempt and over two minutes remaining before the next window. The first scheduled cycle after installation (attempt 9269, started 19:33:02) completed SUCCESS_FRESH and returned to WAITING_FOR_NEXT_READING. Real-device GATT-133/bond-loss injection was not performed on the active sensor; those branches are covered by the controlled BluetoothGatt regression tests.
