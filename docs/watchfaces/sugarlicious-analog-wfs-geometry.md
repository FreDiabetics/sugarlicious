# Sugarlicious Analog WFS geometry

Source: `Sugarlicious Analog.wfs`, Watch Face Studio project format `1.120909`.

The WFS file is a ZIP container. Its authoritative project model is
`honeyface.json`; the embedded preview images are only visual cross-checks.
The reference canvas is 450 x 450 with a mathematical center at (225, 225).
Runtime WFF coordinates use a 512 x 512 canvas and the scale 512 / 450.

## Extracted top-level geometry

| Element | WFS x | WFS y | WFS width | WFS height | WFF bounds |
| --- | ---: | ---: | ---: | ---: | --- |
| Graph slot | 51.8748 | 54.9999 | 346.2504 | 121.3336 | 59, 63, 394, 138 |
| Center left | 73 | 171 | 108.3334 | 108.3334 | 83, 195, 123, 123 |
| Center right | 269 | 171 | 108.3334 | 108.3334 | 306, 195, 123, 123 |
| Center bottom | 158.9996 | 247 | 132.0008 | 130.9996 | 181, 281, 150, 149 |

The graph slot and the graph image are deliberately not the same rectangle.
WFS centers the 224 x 121.3336 image at local x=61.1252, y=0.91 inside the
346.2504 x 121.3336 slot. The resulting runtime image bounds are
`129,64 255x138`; stretching it over the complete `59,63 394x138` slot changes
the WFS composition and is not permitted.

## Text and inner-slot geometry

All WFF values below use the single `512 / 450` conversion and round to the
nearest WFF design pixel. They are local to their complication slot unless
marked global.

| Element | WFS local bounds | WFS size/style | WFF local bounds | WFF size/style |
| --- | --- | --- | --- | --- |
| Center-left title | 7,25 94x27.3077 | 24, bold, centered | 8,28 107x31 | 27, bold, centered |
| Center-left text | 7,56 98x28 | 22, bold, centered | 8,64 112x32 | 25, bold, centered |
| Center-right title | 7,25 94x26.5385 | 22, bold, centered | 8,28 107x30 | 25, bold, centered |
| Center-right text | 7,56 94x28 | 22, bold, centered | 8,64 107x32 | 25, bold, centered |
| Bottom value | 5.7392,45.7142 120.5224x39.8695 | 35, bold, centered | 7,52 137x45 | 40, bold, centered |

The WFS text rectangles describe visual boxes. Mobile preview text is centered
inside those rectangles from actual `FontMetrics` rather than treating the
rectangle center as an Android baseline.

The dial artwork is generated from the authoritative 450 x 450 SVG sources
provided with the WFS revision. `indices_hours.png`, `indices_dots.png`, and
`graph_mask.png` retain that native canvas. The runtime
`sugarlicious_analog_template.png` composites those exact paths with the three
WFS complication outlines; the graph mask contributes its silhouette while
the obscuring area remains the watch face's black background.

Source SVG SHA-256 fingerprints:

- `indizies_hours.svg`: `CED1896A0090F515A39E08A66E9CAEAAF5FC4DA26BDDCF543B01579773993C67`
- `indizies_dots.svg`: `9A0768C05316DB0844E406B91AC31833E1802F81E1D322101DA2FCF503FE07B8`
- `template_graphmask.svg`: `E0CF7D73C5DDA7A6AAAF82147CF0A9CEBBA714143029236D5B7A3C541A68D330`

## Outer curved geometry

WFS uses separate mathematical circles for progress and text:

| Property | WFS | WFF |
| --- | ---: | ---: |
| Center | 225, 225 | 256, 256 |
| Progress diameter | 359 | 408 |
| Progress thickness | 15 | 17 |
| Curved-text diameter | 376 | 428 |
| Progress sweep | 48 degrees | 48 degrees |

The three explicitly present WFS progress layers start at 285 degrees and are
rotated by 0, 90 and 178 degrees. This produces WFF arcs 285-333, 15-63 and
103-151. The lower-left curved slot has no selected progress layer in the WFS
scene; its generic ranged-value renderer follows the same 48-degree geometry
at 253-205 counter-clockwise. Text uses native `TextCircular`, never rotated
straight text.

## Bottom progress geometry

The selected WFS bottom progress is a circular range centered at (60, 60)
inside a 120 x 120 circle, thickness 10, start -140 degrees, sweep 280 degrees,
clockwise, with round caps. Its WFF equivalent is centered at (75, 75), has a
137 diameter, 11 thickness, starts at 220 degrees and sweeps 280 degrees. The
source progress layer begins at local (6,7); after conversion its center is
local (75,76) in the WFF slot. The draw part remains slot-sized so the rounded
stroke caps are not clipped.

## Runtime delivery root cause

The WFS geometry reconstruction in commit `48fd475` did update the source WFF,
but the normal Wear Gradle pipeline copied that freshly built package only to
`default_watchface.apk`. `SugarliciousWatchFacePush` selects Apex from
`watchfaces/sugarlicious_analog.apk`. That selectable path was not an output of
`prepareDefaultWatchFace`, so incremental builds could retain an older file
there indefinitely (and clean builds could omit it).

The failure was reproduced on 2026-09-09 before the fix:

| Artifact | Timestamp | SHA-256 |
| --- | --- | --- |
| Fresh module / picker Apex | 2026-09-09 21:40 | `49F9356C49DE6A02880761D9380DA2D31C974BD28245F9C3EBAABCBD813A39C6` |
| Selectable Apex used by runtime | 2026-09-06 09:52 | `B1931D7DCB54C4FC43B91AF175553105FA5436CFB950366453AD5AAF9A068F95` |

`prepareDefaultWatchFace` now writes the same freshly validated APK and token
to both the system-picker path and the selectable Apex path. The Wear test
compares the complete bytes of both bundled APKs, making stale divergence a
build failure.

## Slot rendering mapping

| WFS slot | Sugarlicious slot | Renderers |
| --- | --- | --- |
| Graph Compilation | 7, Glucose graph | long text, ranged value, small image |
| Upper/Lower curved slots | 0-3 | short text, long text, ranged value, monochromatic image, small image |
| Center left/right | 4-5 | short text, long text, ranged value, monochromatic image, small image |
| Center bottom | 6 | short text, long text, ranged value, monochromatic image, small image |

Provider defaults remain Sugarlicious providers and all values continue to
come from the central validated data model. WFS data expressions are not used.
Preview and runtime share the same snapped WFF geometry values, guarded by
`SugarliciousAnalogPreviewGeometryTest`.

## Hands and AOD

The WFS archive contains hand images, but they are intentionally not imported.
The existing three Sugarlicious hand sets (`standard`, `transparent`, and
`black/gray`) remain unchanged and all pivot at the exact watch center. Second
hands remain hidden in ambient mode. The graph keeps its existing ambient hide
rule; other complications retain their WFF ambient behavior.
