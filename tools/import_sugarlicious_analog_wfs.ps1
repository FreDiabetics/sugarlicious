param([Parameter(Mandatory = $true)][string] $WfsPath)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.IO.Compression.FileSystem
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$outputDir = Join-Path $repoRoot 'watchfaces/sugarlicious-analog/src/main/res/drawable-nodpi'
$archive = [IO.Compression.ZipFile]::OpenRead((Resolve-Path $WfsPath).Path)

function Read-ArchiveText([string] $entryName) {
    $entry = $archive.GetEntry($entryName)
    if ($null -eq $entry) { throw "Missing WFS entry: $entryName" }
    $reader = [IO.StreamReader]::new($entry.Open())
    try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
}
function Read-ArchiveBitmap([string] $entryName) {
    $entry = $archive.GetEntry($entryName)
    if ($null -eq $entry) { throw "Missing WFS entry: $entryName" }
    $stream = $entry.Open()
    try { $source = [Drawing.Bitmap]::new($stream); try { return [Drawing.Bitmap]::new($source) } finally { $source.Dispose() } } finally { $stream.Dispose() }
}
function Entry-NameFromLayer($layer) {
    $uri = [string] $layer.categories.image.properties.image.value
    if (-not $uri.StartsWith('file://')) { throw "Unsupported WFS image URI: $uri" }
    return $uri.Substring('file://'.Length)
}
function Render-Layer($layer, [string] $destination) {
    $source = Read-ArchiveBitmap (Entry-NameFromLayer $layer)
    try {
        $target = [Drawing.Bitmap]::new(450,450,[Drawing.Imaging.PixelFormat]::Format32bppArgb)
        $graphics = [Drawing.Graphics]::FromImage($target)
        try {
            $graphics.Clear([Drawing.Color]::Transparent)
            $graphics.CompositingMode=[Drawing.Drawing2D.CompositingMode]::SourceCopy
            $graphics.CompositingQuality=[Drawing.Drawing2D.CompositingQuality]::HighQuality
            $graphics.InterpolationMode=[Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $graphics.PixelOffsetMode=[Drawing.Drawing2D.PixelOffsetMode]::HighQuality
            $adjustment=[double]$layer.categories.image.properties.adjustmentColor.value.l
            $brightness=[single][Math]::Max(0.0,[Math]::Min(1.0,(100.0+$adjustment)/100.0))
            $matrix=[Drawing.Imaging.ColorMatrix]::new([single[][]]@(@($brightness,0,0,0,0),@(0,$brightness,0,0,0),@(0,0,$brightness,0,0),@(0,0,0,1,0),@(0,0,0,0,1)))
            $attributes=[Drawing.Imaging.ImageAttributes]::new()
            try {$attributes.SetColorMatrix($matrix);$graphics.DrawImage($source,[Drawing.Rectangle]::new(0,0,450,450),0,0,$source.Width,$source.Height,[Drawing.GraphicsUnit]::Pixel,$attributes)} finally {$attributes.Dispose()}
        } finally {$graphics.Dispose()}
        try {$target.Save($destination,[Drawing.Imaging.ImageFormat]::Png)} finally {$target.Dispose()}
    } finally {$source.Dispose()}
}
try {
    $model=(Read-ArchiveText 'honeyface.json')|ConvertFrom-Json
    if ($model.projectFileVersion -ne '1.120909') { throw "Unexpected WFS project version: $($model.projectFileVersion)" }
    $layers=@($model.scene[0].child)
    $hours=$layers|Where-Object name -EQ 'indizies_big@2x'|Select-Object -First 1
    $dots=$layers|Where-Object name -EQ 'indizies_small@2x'|Select-Object -First 1
    $mask=$layers|Where-Object name -EQ 'background_mask@2x'|Select-Object -First 1
    if ($null -in @($hours,$dots,$mask)) { throw 'Required WFS dial layers are missing' }
    Render-Layer $hours (Join-Path $outputDir 'indices_hours.png');Render-Layer $dots (Join-Path $outputDir 'indices_dots.png');Render-Layer $mask (Join-Path $outputDir 'graph_mask.png')
    $template=[Drawing.Bitmap]::new(450,450,[Drawing.Imaging.PixelFormat]::Format32bppArgb);$graphics=[Drawing.Graphics]::FromImage($template)
    try {
        $graphics.Clear([Drawing.Color]::Black)
        foreach($name in @('graph_mask.png','indices_hours.png','indices_dots.png')){$image=[Drawing.Image]::FromFile((Join-Path $outputDir $name));try{$graphics.DrawImageUnscaled($image,0,0)}finally{$image.Dispose()}}
        $graphics.SmoothingMode=[Drawing.Drawing2D.SmoothingMode]::AntiAlias;$pen=[Drawing.Pen]::new([Drawing.Color]::FromArgb(255,136,136,136),2)
        try{$graphics.DrawEllipse($pen,73,171,108,108);$graphics.DrawEllipse($pen,269,171,108,108);$graphics.DrawEllipse($pen,158.5,248,132,132)}finally{$pen.Dispose()}
        $template.Save((Join-Path $outputDir 'sugarlicious_analog_template.png'),[Drawing.Imaging.ImageFormat]::Png)
    } finally {$graphics.Dispose();$template.Dispose()}
} finally {$archive.Dispose()}
Write-Output 'Imported authoritative Sugarlicious Analog WFS dial artwork.'
