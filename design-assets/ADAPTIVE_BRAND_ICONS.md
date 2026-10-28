# Adaptive brand layers

The original supplied 108 x 108 SVG layers are preserved byte for byte in
`app-icons/adaptive108` (Sugarlicious) and `sugarwear/adaptive108` (SugarWear).
`combined.svg` composes the full background and centered 66-unit foreground motif for store artwork and review.

Reimport using `python tools/import_adaptive_brand_icons.py --source-dir PATH`.
The separate legacy `import_app_brand_icons.py` and 66 x 66 masters remain
available for the old input format; do not use that importer for layered108 input.

Launcher foreground and adaptive monochrome vectors use the exact supplied
path inside a 108 x 108 viewport, with scale 66/108 and translation (21,21).
Their optical sizes are identical. The original art fills108 vertically; this
standard import transform keeps the visible motif within21..87 without editing
any supplied path coordinates. Sugarlicious background retains the source108 rounded rectangle geometry
and all gradient coordinates/stops. Both backgrounds are now genuine vector
rectangles and gradients without embedded or linked raster images.

Notification and in-app assets remain white symbols with their original optical
wrappers. A named inner group maps the new108 symbol bounds back to the former
66-coordinate symbol bounds (Sugarlicious 3..63; SugarWear 5..61).

The contact sheet shows unmodified source background and foreground separately,
and the final adaptive composition with the safe66-unit motif.

The unchanged SugarWear source has a 4,936-character symbol path. Its static, cached VectorDrawable paths carry a local VectorPath lint exception to preserve the exact supplied geometry; other lint checks remain enabled. The importer reproduces this exception only for paths over the detector's 3,000-character threshold.
