# ApeX visual rework

ApeX is a code-free Watch Face Format watchface on a normalized 512 x 512 design canvas. The 2026-09-12 rework replaces the former WFS-derived three-circle composition with a deliberate four-zone layout. Runtime, system picker and the Compose mobile preview use the same snapped geometry.

## Composition

| Zone | WFF bounds | Purpose |
| --- | ---: | --- |
| Graph | `92,68 328x140` | Wide upper history surface; the complication image uses the inset `100,76 312x124` plot area. |
| Secondary left | `62,204 132x110` | Compact therapy summary. |
| Secondary right | `318,204 132x110` | Trend, delta and age summary. |
| Glucose cluster | `146,312 220x116` | Dominant current glucose value with a restrained range progress line. |

The zones are symmetric around x=256 and remain inside the circular display envelope. The graph is no longer squeezed into a narrow image inside an oversized invisible slot. The lower glucose surface is wider than the secondary surfaces and its 55-unit type establishes the primary hierarchy.

## Visual language and persistence

- `sugarlicious_analog_template.png` is the authoritative 512 x 512 dial shared by runtime and previews.
- `tools/watchface-assets/Render-ApeX-Dial.ps1` deterministically regenerates it.
- A quiet precision ring and twelve restrained ticks replace the large numeral dial.
- Dark raised surfaces group related information; thin neutral outlines keep secondary data subordinate.
- The established ApeX orange remains the accent.
- Existing standard, transparent and black/gray hand settings and persisted configuration IDs are unchanged.

## Graph, states and data

Slot 7 continues to use `GlucoseGraphComplication`. Measured-time placement, LIVE/BACKFILL handling, session isolation, range colours and freshness semantics therefore remain in the central complication renderer; ApeX introduces no second graph or resolver algorithm. Fixed slot bounds prevent Fresh, Stale, NO_SOURCE and error content from reflowing the watchface.

The default providers remain TIR, reservoir, COB and IOB/basal on outer slots 0-3; combined IOB/COB/basal on slot 4; glucose trend/delta/age on slot 5; ranged glucose on slot 6; and glucose graph on slot 7. All slots retain their previous generic types and touch contracts.

## Preview, ambient and delivery

`SugarliciousAnalogGeometry` mirrors every new rectangle. `SugarliciousAnalogPreviewGeometryTest` guards runtime/preview geometry, symmetry, circular safe-area assumptions, direct graph placement and shared assets. Preview scenarios cover Fresh, High, Low, Stale, NO_SOURCE, sensor error, Watch Direct, Mobile, ambient composition, Galaxy Watch Ultra, Pixel Watch and a small round watch.

ApeX remains entirely declarative WFF. There are no loops, animations, service changes or new data paths. The second hand and graph remain hidden in ambient mode. The Wear build validates the release APK with Google's Watch Face Format validator and copies the same bytes to both the picker and selectable ApeX assets; `WatchFacePushControllerTest` guards that parity.
