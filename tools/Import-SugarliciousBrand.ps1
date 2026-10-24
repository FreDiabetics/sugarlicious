param([string]$Source = 'C:/Users/typ1d/Desktop/Sugarlicious/App Icon/ic_Sugarlicious.svg')
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
[xml]$svg = Get-Content -LiteralPath $Source -Raw
$path = $svg.DocumentElement.SelectSingleNode("*[local-name()='path']").GetAttribute('d')
if ($svg.DocumentElement.GetAttribute('viewBox') -ne '0 0 66 66' -or -not $path) { throw 'Expected supplied 66x66 single-path artwork.' }
$targets = @(
    @('app-mobile','drawable/ic_launcher_foreground.xml',108,108,'#FF5FC479'),
    @('app-mobile','drawable/ic_launcher_monochrome.xml',108,108,'#FFFFFFFF'),
    @('app-mobile','drawable/ic_foreground.xml',108,108,'#FF5FC479'),
    @('app-mobile','drawable/ic_sugarlicious_monochrome.xml',108,108,'#FFFFFFFF'),
    @('app-mobile','drawable/ic_notification.xml',24,66,'#FFFFFFFF'),
    @('app-mobile','drawable/ic_notification_outlined.xml',24,66,'#FFFFFFFF'),
    @('app-mobile','drawable-anydpi/ic_notification.xml',24,66,'#FFFFFFFF'),
    @('app-mobile','drawable-anydpi/ic_notification_outlined.xml',24,66,'#FFFFFFFF'),
    @('app-wear','drawable/ic_foreground.xml',108,108,'#FF5FC479'),
    @('app-wear','drawable/ic_monochrome_outlined.xml',24,108,'#FFFFFFFF'),
    @('app-wear','drawable/ic_notification.xml',24,66,'#FFFFFFFF')
)
foreach ($entry in $targets) {
    $module, $relative, $size, $viewport, $color = $entry
    $inset = if ($viewport -eq 108) { 21 } else { 0 }
    $xml = @"
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="${size}dp" android:height="${size}dp" android:viewportWidth="$viewport" android:viewportHeight="$viewport">
    <group android:translateX="$inset" android:translateY="$inset">
        <path android:fillColor="$color" android:pathData="$path" />
    </group>
</vector>
"@
    [IO.File]::WriteAllText((Join-Path $repo "$module/src/main/res/$relative"), $xml + "`n", [Text.UTF8Encoding]::new($false))
}
# Replace the old editable brand masters as well, so later imports cannot revive them.
foreach ($file in Get-ChildItem (Join-Path $repo 'design-assets/app-icons') -Filter '*.svg') {
    $color = if ($file.Name -match 'foreground|logo_big') { '#5fc479' } elseif ($file.Name -match 'inverted') { '#ffffff' } else { '#000000' }
    $master = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 66 66"><path fill="' + $color + '" d="' + $path + '"/></svg>'
    [IO.File]::WriteAllText($file.FullName, $master + "`n", [Text.UTF8Encoding]::new($false))
}
[IO.File]::WriteAllText((Join-Path $repo 'design-assets/app-icons/ic_sugarlicious.svg'), $svg.OuterXml + "`n", [Text.UTF8Encoding]::new($false))

# Regenerate store images directly from the same vector, no bitmap tracing or redesign.
Add-Type -AssemblyName PresentationCore
Add-Type -AssemblyName WindowsBase
$visual = [Windows.Media.DrawingVisual]::new()
$drawing = $visual.RenderOpen()
$drawing.PushTransform([Windows.Media.ScaleTransform]::new(512/108,512/108))
$drawing.PushTransform([Windows.Media.TranslateTransform]::new(21,21))
$drawing.DrawGeometry([Windows.Media.BrushConverter]::new().ConvertFromString('#5FC479'), $null, [Windows.Media.Geometry]::Parse('F1 ' + $path))
$drawing.Pop(); $drawing.Pop(); $drawing.Close()
$bitmap = [Windows.Media.Imaging.RenderTargetBitmap]::new(512,512,96,96,[Windows.Media.PixelFormats]::Pbgra32)
$bitmap.Render($visual)
foreach ($module in @('app-mobile','app-wear')) {
    $encoder = [Windows.Media.Imaging.PngBitmapEncoder]::new()
    $encoder.Frames.Add([Windows.Media.Imaging.BitmapFrame]::Create($bitmap))
    $stream = [IO.File]::Create((Join-Path $repo "$module/src/main/ic_launcher-playstore.png"))
    try { $encoder.Save($stream) } finally { $stream.Dispose() }
}
Write-Host "Imported supplied brand path into $($targets.Count) runtime vectors and editable/store assets."
