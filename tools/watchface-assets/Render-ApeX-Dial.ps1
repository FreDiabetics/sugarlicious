param(
    [string]$Output = (Join-Path $PSScriptRoot "..\..\watchfaces\sugarlicious-analog\src\main\res\drawable-nodpi\sugarlicious_analog_template.png")
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$size = 512
$bitmap = [System.Drawing.Bitmap]::new($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$graphics.Clear([System.Drawing.Color]::Black)

function Pen([string]$color, [float]$width) {
    $value = [System.Drawing.Pen]::new([System.Drawing.ColorTranslator]::FromHtml($color), $width)
    $value.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $value.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    return $value
}

$quiet = Pen "#3FFFFFFF" 2
$graphics.DrawEllipse($quiet, 22, 22, 468, 468)
$quiet.Dispose()

$major = Pen "#FFFFFFFF" 6
$minor = Pen "#99FFFFFF" 5
for ($index = 0; $index -lt 12; $index++) {
    $angle = ($index * 30 - 90) * [Math]::PI / 180
    $inner = if (($index % 3) -eq 0) { 222 } else { 226 }
    $pen = if (($index % 3) -eq 0) { $major } else { $minor }
    $graphics.DrawLine(
        $pen,
        [float](256 + [Math]::Cos($angle) * $inner),
        [float](256 + [Math]::Sin($angle) * $inner),
        [float](256 + [Math]::Cos($angle) * 238),
        [float](256 + [Math]::Sin($angle) * 238)
    )
}
$major.Dispose()
$minor.Dispose()

function RoundedRect([System.Drawing.Graphics]$g, [System.Drawing.RectangleF]$rect, [float]$radius, [string]$fill, [string]$stroke, [float]$strokeWidth) {
    $path = [System.Drawing.Drawing2D.GraphicsPath]::new()
    $diameter = $radius * 2
    $path.AddArc($rect.X, $rect.Y, $diameter, $diameter, 180, 90)
    $path.AddArc($rect.Right - $diameter, $rect.Y, $diameter, $diameter, 270, 90)
    $path.AddArc($rect.Right - $diameter, $rect.Bottom - $diameter, $diameter, $diameter, 0, 90)
    $path.AddArc($rect.X, $rect.Bottom - $diameter, $diameter, $diameter, 90, 90)
    $path.CloseFigure()
    $brush = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml($fill))
    $outline = Pen $stroke $strokeWidth
    $g.FillPath($brush, $path)
    $g.DrawPath($outline, $path)
    $brush.Dispose(); $outline.Dispose(); $path.Dispose()
}

RoundedRect $graphics ([System.Drawing.RectangleF]::new(92, 68, 328, 140)) 12 "#111416" "#3FFFFFFF" 2
RoundedRect $graphics ([System.Drawing.RectangleF]::new(62, 204, 132, 110)) 14 "#0B0D0E" "#3FFFFFFF" 2
RoundedRect $graphics ([System.Drawing.RectangleF]::new(318, 204, 132, 110)) 14 "#0B0D0E" "#3FFFFFFF" 2
RoundedRect $graphics ([System.Drawing.RectangleF]::new(146, 312, 220, 116)) 18 "#0B0D0E" "#65EB600A" 3
$accent = Pen "#FFEB600A" 4
$graphics.DrawLine($accent, 190, 320, 322, 320)
$accent.Dispose()

$graphics.Dispose()
$directory = Split-Path -Parent $Output
[System.IO.Directory]::CreateDirectory($directory) | Out-Null
$bitmap.Save($Output, [System.Drawing.Imaging.ImageFormat]::Png)
$bitmap.Dispose()
Write-Host "Rendered ApeX dial: $Output"
