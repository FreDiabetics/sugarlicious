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
137 diameter, 11 thickness, starts at 220 degrees and sweeps 280 degrees.

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
