# Vigil Watchface

`Vigil` is the renamed, in-place successor to the former G6-style package. The application id and face-selection index intentionally remain stable so existing installations and selections migrate without being reset.

The face is deliberately bound to `WATCH_DIRECT` and never uses AndroidAPS or Mobile CGM as a fallback. It consumes the validated G7 Watch Collector model, timestamps, trend, delta, quality and freshness. Missing, stale and sensor-error data are shown as `NO_SOURCE`, `STALE` or `SIGNAL LOSS`; no synthetic glucose or delta is created.

The fixed layout is:

- glucose and the shared Sugarlicious trend arrow at the top;
- delta followed by the unit in secondary text directly below;
- the shared CGM graph in the middle;
- the face-local graph duration and real event age below the graph;
- time at the bottom, with no content below it.

The graph reuses `SharedWearCgmGraphRenderer`, including the common fixed glucose scale, confirmed HIGH/TARGET/LOW excursion fills, range classification, axes, clipping, colors, dots and rounded border. Tapping the graph or its information row cycles the existing `1h`, `3h`, `6h`, `12h`, and `24h` durations and stores the value only in the Direct-to-Watch preference surface.

Tapping the complete glucose block opens the exported launcher activity of `app.aapswear.g7watch` explicitly. It does not open the Sugarlicious Wear main screen.

Trend appearance uses the shared `TrendArrowStyle` and `TrendArrowStylePreferences` schema (fill, outline enabled, outline color, outline thickness, size and alpha) with a separate Direct-to-Watch preference file. Light and dark modes resolve independently. The graph is intentionally hidden in ambient mode, preserving the existing WFF AOD policy; glucose, trend, delta/unit and time remain visible.

The G7 Watch Collector settings contain a dedicated `Direct to Watch` category with the subtitle `Watchface`. Its graph controls cover the time scale, CGM point size and outline, graph background, LOW/TARGET/HIGH areas, LOW/IN-RANGE/HIGH points, boundary lines, axes, now line and border. Trend size, fill, outline, outline color, thickness and alpha are configurable there as separate Light/Dark profiles. The values are transferred through a signature-protected explicit app channel and affect only Vigil.

> This watchface is intentionally bound to WATCH_DIRECT and does not use AndroidAPS/Mobile as a CGM fallback.
