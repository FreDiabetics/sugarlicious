# Settings organization and deliberate collector pause

## Behavior

- SugarWear overview and system status expose pause/resume independently of full sensor unlink. Pause retains pairing, credentials, session and history, cancels collection and scheduled recovery/alarm work, and persists across boot/source changes.
- Lifecycle generation and cycle ownership reject pre-pause callbacks even after a quick resume. A paused direct stream remains graph history but cannot become the current source. Provider/tile consumers are notified when pause changes.
- Mobile appearance and widget controls are grouped by the object they configure, including glucose, trend, delta, age, units, TIR, graph and therapy. Native settings sections become separate cards. Existing persistence keys remain in use; glucose/trend text sizes moved into their appearance cards.
- Sugarlicious Wear and SugarWear appearance/direct-to-watch settings use the same object grouping and shared Wear controls. Slider drag regression checks preserve vertical scrolling without committing a setting.
- Mobile submenus open with a compact header. Upward scroll fades the title before revealing the fixed back-button circle. Pulling down at the top crossfades to a modest expanded title. Expansion occupies actual header space; no artificial trailing scroll space is added. Root settings retain their larger opening header.

## Energy scope

Pause removes unnecessary radio/recovery work while deliberately disconnected. Existing bounded collection windows, retries and radio recovery remain unchanged. No claim of measured battery improvement is made; actual consumption and visual fidelity require hardware validation.

## Verification status

Targeted tests cover collector pause/resume races, automatic recovery respecting pause, alarm suppression, provider source selection, header crossfade timing, pull gestures on nonclickable content, menu refresh position and Wear slider scrolling. Device installation and visual/pause-resume tests remain pending until WLAN debugging is reachable.

The previously built high/very-high Mobile glucose and dot-color correction is included in the Mobile build. It had intentionally not been installed while the user was away.

## Local results

- Mobile, Sugarlicious Wear and SugarWear debug builds succeeded. Lint and Detekt passed for all three apps; final Lint reports contain no warnings/errors. Formatting passed across Mobile, Wear, SugarWear, shared UI and complications.
- 38 Mobile header/navigation/gesture tests and 4 shared Wear control tests passed.
- Full complications suite: 89 tests passed, including the corrected Vigil hands-visibility preview fixture.
- Full SugarWear suite: 337 tests executed; three outdated layout fixtures initially failed. After updating the expected element cards and explicitly enabling the active-value fixture, all 26 tests in that layout class passed on rerun; the other 311 had passed the full run. No production change was needed for those three test failures.
- Review also found pull gestures starting on nonclickable content were not intercepted. The touch-handler fix has a passing regression test. Transfer-log title clearance now uses measured system-scaled typography, like widget settings.
- Both previous WLAN debugging addresses and mDNS discovery were unavailable. No current hardware installation, visual comparison or battery measurement is claimed.
