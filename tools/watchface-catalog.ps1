$ACTIVE_WATCHFACES = @(
    [pscustomobject]@{ Name = 'Digital'; Module = 'sugarlicious-digital'; Out = 'sugarlicious_digital'; Asset = 'sugarlicious_digital.apk' }
    [pscustomobject]@{ Name = 'ApeX'; Module = 'sugarlicious-analog'; Out = 'sugarlicious_analog'; Asset = 'sugarlicious_analog.apk' }
    [pscustomobject]@{ Name = 'Vigil'; Module = 'sugarlicious-direct-to-watch'; Out = 'sugarlicious_direct_to_watch'; Asset = 'sugarlicious_direct_to_watch.apk' }
)

# These packages remain buildable and validated so regressions are caught. They are deliberately
# excluded from generated Push assets, the app selection surface, installers, and release bundles.
# Keep every active product WFF inside the release and validation matrix. test-wff is a
# purpose-built validator fixture and is intentionally not a distributable watchface.
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
