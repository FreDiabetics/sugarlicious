param(
    [string]$Source = 'C:/Users/typ1d/Desktop/Sugarlicious/Watchfaces/ic_Sugarlicious.svg',
    [string]$SugarWearSource = 'C:/Users/typ1d/Desktop/Sugarlicious/Watchfaces/ic_sugarwear.svg'
)
$ErrorActionPreference = 'Stop'
python (Join-Path $PSScriptRoot 'import_app_brand_icons.py') --sugarlicious $Source --sugarwear $SugarWearSource
if ($LASTEXITCODE -ne 0) { throw 'Brand import failed' }
