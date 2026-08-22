$ACTIVE_WATCHFACES = @(
    [pscustomobject]@{ Name = 'Digital'; Module = 'sugarlicious-digital'; Out = 'sugarlicious_digital'; Asset = 'sugarlicious_digital.apk' }
    [pscustomobject]@{ Name = 'Analog'; Module = 'sugarlicious-analog'; Out = 'sugarlicious_analog'; Asset = 'sugarlicious_analog.apk' }
    [pscustomobject]@{ Name = 'Orbit'; Module = 'sugarlicious-orbit'; Out = 'sugarlicious_orbit'; Asset = 'sugarlicious_orbit.apk' }
    [pscustomobject]@{ Name = 'Rings'; Module = 'sugarlicious-rings'; Out = 'sugarlicious_rings'; Asset = 'sugarlicious_rings.apk' }
    [pscustomobject]@{ Name = 'Graph'; Module = 'sugarlicious-graph'; Out = 'sugarlicious_graph'; Asset = 'sugarlicious_graph.apk' }
    [pscustomobject]@{ Name = 'G6 Style'; Module = 'sugarlicious-g6-style'; Out = 'sugarlicious_g6_style'; Asset = 'sugarlicious_g6_style.apk' }
)

# These packages remain buildable and validated so regressions are caught. They are deliberately
# excluded from generated Push assets, the app selection surface, installers, and release bundles.
$LEGACY_WATCHFACES = @(
    [pscustomobject]@{ Name = 'AAPS BigChart'; Module = 'aaps-big-chart'; Out = 'aaps_big_chart'; Asset = 'aaps_big_chart.apk' }
    [pscustomobject]@{ Name = 'AAPS Circle'; Module = 'aaps-circle'; Out = 'aaps_circle'; Asset = 'aaps_circle.apk' }
    [pscustomobject]@{ Name = 'AAPS Cockpit'; Module = 'aaps-cockpit'; Out = 'aaps_cockpit'; Asset = 'aaps_cockpit.apk' }
    [pscustomobject]@{ Name = 'AAPS Community'; Module = 'aaps-community'; Out = 'aaps_community'; Asset = 'aaps_community.apk' }
    [pscustomobject]@{ Name = 'AAPS Digital Style'; Module = 'aaps-digital-style'; Out = 'aaps_digital_style'; Asset = 'aaps_digital_style.apk' }
    [pscustomobject]@{ Name = 'AAPS Large'; Module = 'aaps-large'; Out = 'aaps_large'; Asset = 'aaps_large.apk' }
    [pscustomobject]@{ Name = 'AAPS NoChart'; Module = 'aaps-no-chart'; Out = 'aaps_no_chart'; Asset = 'aaps_no_chart.apk' }
    [pscustomobject]@{ Name = 'AAPS Standard'; Module = 'aaps-standard'; Out = 'aaps_standard'; Asset = 'aaps_standard.apk' }
    [pscustomobject]@{ Name = 'AAPS V2'; Module = 'aaps-v2'; Out = 'aaps_v2'; Asset = 'aaps_v2.apk' }
    [pscustomobject]@{ Name = 'AAPS V2 TT DarkOnly'; Module = 'aaps-v2-tt-dark'; Out = 'aaps_v2_tt_dark'; Asset = 'aaps_v2_tt_dark.apk' }
    [pscustomobject]@{ Name = 'AAPS V4'; Module = 'aaps-v4'; Out = 'aaps_v4'; Asset = 'aaps_v4.apk' }
    [pscustomobject]@{ Name = 'AIMICO'; Module = 'aimico'; Out = 'aimico'; Asset = 'aimico.apk' }
    [pscustomobject]@{ Name = 'Analog G-Watch'; Module = 'analog-g-watch'; Out = 'analog_g_watch'; Asset = 'analog_g_watch.apk' }
    [pscustomobject]@{ Name = 'Blue Ring'; Module = 'blue-ring'; Out = 'blue_ring'; Asset = 'blue_ring.apk' }
    [pscustomobject]@{ Name = 'Digital Big Graph'; Module = 'digital-big-graph'; Out = 'digital_big_graph'; Asset = 'digital_big_graph.apk' }
    [pscustomobject]@{ Name = 'Digital G-Watch'; Module = 'digital-g-watch'; Out = 'digital_g_watch'; Asset = 'digital_g_watch.apk' }
    [pscustomobject]@{ Name = 'Gears'; Module = 'gears'; Out = 'gears'; Asset = 'gears.apk' }
    [pscustomobject]@{ Name = 'Gota'; Module = 'gota'; Out = 'gota'; Asset = 'gota.apk' }
    [pscustomobject]@{ Name = 'LuckyLoopKoeln'; Module = 'lucky-loop-koeln'; Out = 'lucky_loop_koeln'; Asset = 'lucky_loop_koeln.apk' }
    [pscustomobject]@{ Name = 'P-Zero'; Module = 'p-zero'; Out = 'p_zero'; Asset = 'p_zero.apk' }
    [pscustomobject]@{ Name = 'Robby'; Module = 'robby'; Out = 'robby'; Asset = 'robby.apk' }
    [pscustomobject]@{ Name = 'Simple Digital'; Module = 'simple-digital'; Out = 'simple_digital'; Asset = 'simple_digital.apk' }
    [pscustomobject]@{ Name = 'AAPS SteamPunk'; Module = 'steam-punk'; Out = 'steam_punk'; Asset = 'steam_punk.apk' }
)

$ALL_WATCHFACES = @($ACTIVE_WATCHFACES) + @($LEGACY_WATCHFACES)
