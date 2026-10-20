# Mobile settings: Samsung reference alignment

Reference: user's four-screen One UI 9 collage (Volume, Sounds/Vibration,
Connections, main Settings), supplied 2026-10-20.

Scope: Mobile settings only. Preserve therapy values, source/collector logic,
stored preferences, exports and permission flows. No private Samsung API,
copied proprietary code/assets or replacement dependencies.

## Implementation

- Public DeviceDefault DayNight theme supplies native font, radios, switches,
  dialogs and accent behavior; no app-level forced sans family/green accent.
- Main settings has a large centered title, borderless 28dp cards, 18dp inner
  padding, regular 18sp row titles, secondary text and inset separators.
- Categories open dedicated pages instead of multiple inline accordions.
  Toolbar/Back/gesture return from category to settings before overview.
  Category state survives Activity reconstruction. No app exit action added.
- Native and Compose settings share unscaled device switches and the rounded
  14dp slider track/20dp outlined thumb. Widget-specific discrete ticks remain
  available; continuous Mobile controls do not add artificial tick dots.
- Radio choice rows wrap vertically when measured content will not fit; long
  labels must not be squeezed into fixed-width chips.
- Menu controls use selected device palette and system font; therapy/graph
  preview colors stay user-owned. All color editors use the existing shared
  reconstructed picker (not a claim to use Samsung's private component).
- Nightscout configuration retains its existing save/authentication/network
  permission behavior with the same settings card/text presentation.
- Restrict the app's supplementary Back swipe to the left24dp edge so dragging
  a slider across the page cannot navigate away.

## Verification boundary

Regression checks retain notification permission and persistence assertions,
but enter the new category pages first. Added native-switch binding/no-write
and category Back tests; Activity test covers category recreation and ordering.
Screenshots are the visual reference, not a pixel-identical validation claim.
Final real-device comparison is required, including dark/light mode and large
font. Never mark exact Samsung visual parity based on compilation alone.

Source review found and resolved two follow-ups: non-touch slider input must
commit without waiting for a touch-end event, and native controls must receive
a copied configuration matching the app's selected dark/light mode. Tests
cover immediate non-touch persistence, batched touch persistence, no writes
from programmatic binding, and preserving the original device configuration.
Follow-up read-only review found no additional concrete regression.

Live comparison on SM_S948B revealed DeviceDefault's activated-control alias
still used framework blue. API31+ control overlays now explicitly use public
accent1_200 (dark) / accent1_600 (light), with pre31 fallback. A regression
failed without the overlay and passes with it. Widget/native Compose controls
must not wrap the supplied context with the base theme again, which would
erase that overlay. The large heading is height-limited in landscape. Header
accent, chevron, card spacing and child-page top inset follow the reference.

## Verified handoff

- 456 Mobile tests passed, zero failures/errors. Debug/release APKs, lint,
  detekt and ktlintCheck passed together (`mobile-oneui-handoff-gates.log`,
  BUILD SUCCESSFUL 3m45). New warnings resolved without disabling checks.
- API26 context/theme fallback is tested; API29 DayNight theme and API31
  public palette resources are version-qualified. Two existing notification
  warnings remain (UselessParent and UseKtx), outside this menu change.
- Final debug APK installed successfully on SM_S948B Android17.
- Live Samsung reference and initial Sugarlicious root/category rendering
  were inspected. Final palette-corrected visual comparison remains open:
  the device relocked during the later attempt. Full light-mode/large-font
  hardware matrix is not claimed as validated.
- Existing additional widget providers remain directly selectable per the
  user's explicit decision; no aggregate provider introduced.
