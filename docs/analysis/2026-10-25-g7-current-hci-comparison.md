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
