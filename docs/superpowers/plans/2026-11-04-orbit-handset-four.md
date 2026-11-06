# Orbit handset four

The fourth Orbit hand option (stable ID `3`) uses the four user-supplied SVG masters in
`design-assets/watchfaces/orbit/hands_4/`. All layers keep their original full 512 × 512
canvas and the rotation center at (256,256).

- `1hours_hand.svg`: white hour outline
- `2minutes_hand.svg`: white minute outline
- `4second_hand.svg`: white second hand (original upload `4seconds_hand.svg`)
- `3dots.svg`: fixed layered hub, including its top dot

No additional `5topdot.svg`, accent masks, dynamic hand tinting, or per-hand color
customizations are used by option 3. The hub appears over the seconds layer to keep
the integrated top dot visible. The other Orbit hand options, color configurations,
seconds controls, and AOD rules remain unchanged.

The canonical hand sprites and option preview are generated with the existing
`importOrbitHandset` maintenance pipeline. Updates are restricted to the fourth
Orbit hand option. This update was requested for direct installation without executing
the optional verification suite.
