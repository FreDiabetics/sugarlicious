param([string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$arguments = @((Join-Path $PSScriptRoot 'render_orbit_hands.py'))
if ($OutputDirectory) { $arguments += @('--output', $OutputDirectory) }
& python @arguments
if ($LASTEXITCODE -ne 0) { throw 'Orbit hand rendering failed' }
