$SUGARLICIOUS_WATCHFACES = @(
    [pscustomobject]@{ Name = 'Digital'; Module = 'sugarlicious-digital'; Out = 'sugarlicious_digital'; Asset = 'sugarlicious_digital.apk' }
    [pscustomobject]@{ Name = 'ApeX'; Module = 'sugarlicious-analog'; Out = 'sugarlicious_analog'; Asset = 'sugarlicious_analog.apk' }
    [pscustomobject]@{ Name = 'Orbit'; Module = 'sugarlicious-orbit'; Out = 'sugarlicious_orbit'; Asset = 'sugarlicious_orbit.apk' }
)

$SUGARWEAR_WATCHFACES = @(
    [pscustomobject]@{ Name = 'Vigil'; Module = 'sugarlicious-direct-to-watch'; Out = 'sugarlicious_direct_to_watch'; Asset = 'sugarlicious_direct_to_watch.apk' }
)
# Validation and release builds cover both independent products.
$ACTIVE_WATCHFACES = @($SUGARLICIOUS_WATCHFACES) + @($SUGARWEAR_WATCHFACES)

$catalogModules = @($ACTIVE_WATCHFACES.Module)
$uniqueCatalogModules = @($catalogModules | Sort-Object -Unique)
if ($uniqueCatalogModules.Count -ne $catalogModules.Count) {
    throw 'Duplicate module in watchface catalog.'
}

$repositoryRoot = Split-Path $PSScriptRoot -Parent
$watchfacesRoot = Join-Path $repositoryRoot 'watchfaces'
$missingModules = @($catalogModules | Where-Object { -not (Test-Path (Join-Path $watchfacesRoot "$_/build.gradle.kts")) })
if ($missingModules.Count -gt 0) {
    throw "Active watchface module missing: $($missingModules -join ', ')."
}
