param(
    [Parameter(Position = 0)]
    [ValidateSet("mobile", "wear", "g7", "all", "wfp")]
    [string]$Target = "mobile",
    [switch]$Test,
    [switch]$NoPull,
    [string]$PhoneSerial,
    [string]$WatchSerial
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0
. (Join-Path $PSScriptRoot 'tools/watchface-catalog.ps1')

function Assert-LastExitCode([string]$Step) {
    if ($LASTEXITCODE -ne 0) {
        throw "$Step failed with exit code $LASTEXITCODE"
    }
}

function Get-ConnectedAdbDevices {
    & adb start-server | Out-Host
    Assert-LastExitCode "ADB start"
    $serials = @(
        & adb devices |
            Select-Object -Skip 1 |
            ForEach-Object {
                if ($_ -match '^([^\s]+)\s+device(?:\s|$)') { $matches[1] }
            }
    )
    Assert-LastExitCode "ADB device query"

    foreach ($serial in $serials) {
        $characteristics = ((& adb -s $serial shell getprop ro.build.characteristics) | Out-String).Trim()
        Assert-LastExitCode "ADB characteristics query for $serial"
        $model = ((& adb -s $serial shell getprop ro.product.model) | Out-String).Trim()
        Assert-LastExitCode "ADB model query for $serial"
        [pscustomobject]@{
            Serial = $serial
            Model = $model
            IsWatch = $characteristics -match '(^|,)watch(,|$)'
        }
    }
}

function Resolve-AdbDevice(
    [object[]]$Devices,
    [ValidateSet("phone", "watch")]
    [string]$Kind,
    [string]$RequestedSerial
) {
    $expectWatch = $Kind -eq "watch"
    if ($RequestedSerial) {
        $requested = @($Devices | Where-Object { $_.Serial -eq $RequestedSerial })
        if ($requested.Count -ne 1) { throw "Requested $Kind '$RequestedSerial' is not an active ADB device." }
        if ($requested[0].IsWatch -ne $expectWatch) { throw "Requested device '$RequestedSerial' is not a $Kind." }
        return $requested[0].Serial
    }

    $candidates = @($Devices | Where-Object { $_.IsWatch -eq $expectWatch })
    if (($Kind -eq "phone") -and ($candidates.Count -gt 1)) {
        $usbCandidates = @($candidates | Where-Object { $_.Serial -notmatch '(_adb-tls-connect|:\d+$)' })
        if ($usbCandidates.Count -eq 1) { return $usbCandidates[0].Serial }
    }
    if ($candidates.Count -ne 1) {
        $connected = ($Devices | ForEach-Object { "$($_.Serial) ($($_.Model))" }) -join ", "
        if (-not $connected) { $connected = "none" }
        throw "Expected exactly one $kind, found $($candidates.Count). Connected: $connected"
    }
    return $candidates[0].Serial
}

function Test-WatchFacePushAssetsStale {
    $generated = ".\app-wear\build\generated\watchfacePushAssets\watchfaces"
    $required = @(
        $ACTIVE_WATCHFACES | ForEach-Object {
            "$($_.Out).apk"
            "$($_.Out)_token.txt"
        }
    )

    $defaultTokenResource = ".\app-wear\build\generated\watchfacePushRes\values\default_watchface_token.xml"
    $defaultApk = ".\app-wear\build\generated\watchfacePushAssets\default_watchface.apk"
    if (-not (Test-Path $defaultTokenResource) -or -not (Test-Path $defaultApk)) { return $true }
    foreach ($name in $required) {
        if (-not (Test-Path (Join-Path $generated $name))) { return $true }
    }

    $actual = @(Get-ChildItem -LiteralPath $generated -File | ForEach-Object Name)
    if (@(Compare-Object $required $actual).Count -gt 0) { return $true }

    $watchFaceSources = @(
        $ACTIVE_WATCHFACES | ForEach-Object {
            Get-ChildItem (Join-Path '.\watchfaces' $_.Module) -Recurse -File
        }
    ) | Where-Object { $_.FullName -notmatch '[\\/](build|\.gradle)[\\/]' }

    $sourceNewest = @(
        $watchFaceSources
        Get-Item .\tools\watchface-push\Prepare-WatchFacePushAssets.ps1
    ) | Sort-Object LastWriteTime -Descending | Select-Object -First 1

    $assetOldest = @(
        $required | ForEach-Object { Get-Item (Join-Path $generated $_) }
        Get-Item $defaultApk
        Get-Item $defaultTokenResource
    ) | Sort-Object LastWriteTime | Select-Object -First 1

    return $sourceNewest.LastWriteTime -gt $assetOldest.LastWriteTime
}

if (-not $NoPull) {
    $dirty = @(git status --porcelain)
    Assert-LastExitCode "git status"
    if ($dirty.Count -gt 0) { throw "Local changes exist. Commit or stash them first, or use -NoPull intentionally." }
    Write-Host "Syncing GitHub..."
    git pull --ff-only
    Assert-LastExitCode "git pull"
}

$needsPhone = $Target -in @("mobile", "all")
$needsWatch = $Target -in @("wear", "g7", "all", "wfp")
$adbDevices = @(Get-ConnectedAdbDevices)

$phone = $null
if ($needsPhone) {
    $phone = Resolve-AdbDevice $adbDevices "phone" $PhoneSerial
    Write-Host "Phone: $phone"
}

$watch = $null
if ($needsWatch) {
    $watch = Resolve-AdbDevice $adbDevices "watch" $WatchSerial
    Write-Host "Watch: $watch"
}

$effectiveTarget = $Target
$installWatchFaces = $Target -in @("all", "wfp")
$needsWatchFaceAssets = $Target -in @("wear", "all", "wfp")
if ($needsWatchFaceAssets -and (($Target -eq "wfp") -or (Test-WatchFacePushAssetsStale))) {
    Write-Host "Preparing current Watch Face Push assets..."
    & .\tools\watchface-push\Prepare-WatchFacePushAssets.ps1
    if (-not $?) { throw "Watch Face Push asset preparation failed" }
}
if ($Target -eq "wfp") { $effectiveTarget = "wear" }

[string[]]$gradleTasks = @()
if ($effectiveTarget -eq "mobile") {
    if ($Test) { $gradleTasks += ":app-mobile:testDebugUnitTest" }
    $gradleTasks += ":app-mobile:assembleDebug"
} elseif ($effectiveTarget -eq "wear") {
    if ($Test) { $gradleTasks += ":app-wear:testDebugUnitTest" }
    $gradleTasks += ":app-wear:assembleDebug"
} elseif ($effectiveTarget -eq "g7") {
    if ($Test) {
        $gradleTasks += ":dexcom-g7:test"
        $gradleTasks += ":g7watch:testDebugUnitTest"
    }
    $gradleTasks += ":g7watch:assembleDebug"
} elseif ($effectiveTarget -eq "all") {
    if ($Test) {
        $gradleTasks += ":app-mobile:testDebugUnitTest"
        $gradleTasks += ":app-wear:testDebugUnitTest"
        $gradleTasks += ":complications:testDebugUnitTest"
        $gradleTasks += ":dexcom-g7:test"
        $gradleTasks += ":g7watch:testDebugUnitTest"
    }
    $gradleTasks += ":app-mobile:assembleDebug"
    $gradleTasks += ":app-wear:assembleDebug"
    $gradleTasks += ":g7watch:assembleDebug"
} else {
    throw "Unsupported target: $effectiveTarget"
}

Write-Host "Running Gradle tasks:"
foreach ($task in $gradleTasks) { Write-Host "  $task" }
& .\gradlew.bat @gradleTasks
Assert-LastExitCode "Gradle"

if (($effectiveTarget -eq "mobile") -or ($effectiveTarget -eq "all")) {
    $mobileApk = Get-ChildItem .\app-mobile\build\outputs\apk\debug\*.apk | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($null -eq $mobileApk) { throw "Mobile APK not found" }
    Write-Host "Installing Mobile on $phone..."
    adb -s $phone install -r $mobileApk.FullName
    Assert-LastExitCode "Mobile install"
    adb -s $phone shell am force-stop app.aapswear
    Assert-LastExitCode "Mobile force-stop"
    adb -s $phone shell am start -n app.aapswear/.mobile.MainActivity | Out-Host
    Assert-LastExitCode "Mobile start"
}

if (($effectiveTarget -eq "wear") -or ($effectiveTarget -eq "g7") -or ($effectiveTarget -eq "all")) {
    if (($effectiveTarget -eq "g7") -or ($effectiveTarget -eq "all")) {
        $g7WatchApk = Get-ChildItem .\g7watch\build\outputs\apk\debug\*.apk | Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($null -eq $g7WatchApk) { throw "G7 Watch Collector APK not found" }
        Write-Host "Installing G7 Watch Collector on $watch..."
        adb -s $watch install -r $g7WatchApk.FullName
        Assert-LastExitCode "G7 Watch Collector install"
    }

    if (($effectiveTarget -ne "wear") -and ($effectiveTarget -ne "all")) {
        Write-Host ""
        Write-Host "OK: $effectiveTarget built and installed."
        exit 0
    }

    $wearApk = Get-ChildItem .\app-wear\build\outputs\apk\debug\*.apk | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($null -eq $wearApk) { throw "Wear APK not found" }
    Write-Host "Installing Wear on $watch..."
    adb -s $watch install -r $wearApk.FullName
    Assert-LastExitCode "Wear install"
    adb -s $watch shell am force-stop app.aapswear
    Assert-LastExitCode "Wear force-stop"
    adb -s $watch shell am start -n app.aapswear/.wear.WearActivity | Out-Host
    Assert-LastExitCode "Wear start"

    if ($installWatchFaces) {
        Write-Host "Installing Sugarlicious watchfaces on $watch..."
        & .\tools\install-sugarlicious-watchfaces.ps1 -WatchSerial $watch -Adb ((Get-Command adb).Source)
        if (-not $?) { throw "Sugarlicious watchface installation failed" }
    }
}

Write-Host ""
Write-Host "OK: $effectiveTarget built and installed."
