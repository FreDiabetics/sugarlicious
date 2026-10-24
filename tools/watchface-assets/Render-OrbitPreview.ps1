param()
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName PresentationCore
Add-Type -AssemblyName WindowsBase
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$output = Join-Path $repo 'watchfaces/sugarlicious-orbit/src/main/res/drawable-nodpi/preview.png'
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($output)) | Out-Null
$visual = [Windows.Media.DrawingVisual]::new()
$dc = $visual.RenderOpen()
function Brush([string]$color) { [Windows.Media.BrushConverter]::new().ConvertFromString($color) }
function Arc([double]$cx, [double]$cy, [double]$r, [double]$a, [double]$b, [double]$width, [string]$color, [bool]$round = $true) {
    $geometry = [Windows.Media.StreamGeometry]::new()
    $path = $geometry.Open()
    $rad = [Math]::PI / 180
    $path.BeginFigure([Windows.Point]::new($cx + $r*[Math]::Sin($a*$rad), $cy - $r*[Math]::Cos($a*$rad)), $false, $false)
    $path.ArcTo([Windows.Point]::new($cx + $r*[Math]::Sin($b*$rad), $cy - $r*[Math]::Cos($b*$rad)), [Windows.Size]::new($r,$r), 0, (($b-$a) -gt 180), [Windows.Media.SweepDirection]::Clockwise, $true, $false)
    $path.Close()
    $pen = [Windows.Media.Pen]::new((Brush $color), $width)
    $pen.StartLineCap = $pen.EndLineCap = $(if($round){[Windows.Media.PenLineCap]::Round}else{[Windows.Media.PenLineCap]::Flat})
    $dc.DrawGeometry($null,$pen,$geometry)
}
$fontFamily = [Windows.Media.FontFamily]::new([Uri]((Join-Path $repo 'watchfaces/sugarlicious-orbit/src/main/res/font/') -replace '\\','/'), './#Inter Tight')
$font = [Windows.Media.Typeface]::new($fontFamily,[Windows.FontStyles]::Normal,[Windows.FontWeights]::SemiBold,[Windows.FontStretches]::Normal)
function Text([string]$value, [double]$cx, [double]$cy, [double]$size, [string]$color) {
    $text = [Windows.Media.FormattedText]::new($value,[Globalization.CultureInfo]::GetCultureInfo('de-DE'),[Windows.FlowDirection]::LeftToRight,$font,$size,(Brush $color),1.0)
    $dc.DrawText($text,[Windows.Point]::new($cx-$text.Width/2,$cy-$text.Height/2))
}
function CurveText([string]$value, [double]$r, [double]$start, [double]$end, [double]$size, [string]$color) {
    $widths = @()
    foreach($character in $value.ToCharArray()) {
        $glyph = [Windows.Media.FormattedText]::new([string]$character,[Globalization.CultureInfo]::GetCultureInfo('de-DE'),[Windows.FlowDirection]::LeftToRight,$font,$size,(Brush $color),1.0)
        $widths += $glyph.WidthIncludingTrailingWhitespace
    }
    $total = ($widths | Measure-Object -Sum).Sum
    $cursor = -$total/2
    $center = ($start+$end)/2
    $bottom = $center -gt 90 -and $center -lt 270
    for($i=0;$i -lt $value.Length;$i++) {
        $offset = ($cursor+$widths[$i]/2)/$r*180/[Math]::PI
        $a = $center + $(if($bottom){-$offset}else{$offset})
        $x = 256+$r*[Math]::Sin($a*[Math]::PI/180)
        $y = 256-$r*[Math]::Cos($a*[Math]::PI/180)
        $dc.PushTransform([Windows.Media.RotateTransform]::new(($a+$(if($bottom){180}else{0})),$x,$y))
        Text ([string]$value[$i]) $x $y $size $color
        $dc.Pop()
        $cursor += $widths[$i]
    }
}
function IconFromVector([string]$name, [double]$cx, [double]$cy, [double]$size, [double]$angle) {
    [xml]$vector=Get-Content (Join-Path $repo "ui-shared/src/main/res/drawable/$name.xml") -Raw
    $ns='http://schemas.android.com/apk/res/android'
    $vw=[double]$vector.DocumentElement.GetAttribute('viewportWidth',$ns)
    $vh=[double]$vector.DocumentElement.GetAttribute('viewportHeight',$ns)
    $dc.PushTransform([Windows.Media.RotateTransform]::new($angle,$cx,$cy))
    $dc.PushTransform([Windows.Media.TranslateTransform]::new($cx-$size/2,$cy-$size/2))
    $dc.PushTransform([Windows.Media.ScaleTransform]::new($size/$vw,$size/$vh))
    foreach($path in $vector.SelectNodes('//path')) {
        $parent=$path.ParentNode
        $grouped=$parent.Name -eq 'group'
        if($grouped) {$dc.PushTransform([Windows.Media.TranslateTransform]::new([double]$parent.GetAttribute('translateX',$ns),[double]$parent.GetAttribute('translateY',$ns)))}
        $dc.DrawGeometry((Brush '#FFD0B4'),$null,[Windows.Media.Geometry]::Parse($path.GetAttribute('pathData',$ns)))
        if($grouped){$dc.Pop()}
    }
    $dc.Pop();$dc.Pop();$dc.Pop()
}
$dc.DrawEllipse((Brush '#301202'),$null,[Windows.Point]::new(256,256),256,256)
Arc 256 256 144.213 270 450 41.529 '#362B25' $false
Arc 256 256 95.289 270 450 56.32 '#241D19' $false
$dc.DrawEllipse((Brush '#200C02'),$null,[Windows.Point]::new(256,256),67,67)
Arc 256 256 95.57 92 180 40.96 '#362B25' $false
Arc 256 256 144.5 92 180 40.96 '#362B25' $false
foreach($angles in @(@(284,341),@(19,76),@(103,166))) {
    Arc 256 256 218.45 $angles[0] $angles[1] 17.07 '#523222'
    if($angles[0] -ne 19){Arc 256 256 218.45 $angles[0] ($angles[0]+0.33*($angles[1]-$angles[0])) 17.07 '#FF5F0D'}
}
for($i=0;$i -lt 60;$i++) {
    $a=$i*6*[Math]::PI/180
    $length=if($i%15 -eq 0){12}elseif($i%5 -eq 0){7}else{3}
    $pen=[Windows.Media.Pen]::new((Brush '#FF935C'),$(if($i%5 -eq 0){6}else{3}))
    $pen.StartLineCap=$pen.EndLineCap=[Windows.Media.PenLineCap]::Round
    $dc.DrawLine($pen,[Windows.Point]::new(256+(251-$length)*[Math]::Sin($a),256-(251-$length)*[Math]::Cos($a)),[Windows.Point]::new(256+251*[Math]::Sin($a),256-251*[Math]::Cos($a)))
}
$template = [Windows.Media.Imaging.BitmapImage]::new([Uri](Join-Path $repo 'watchfaces/sugarlicious-analog/src/main/res/drawable-nodpi/sugarlicious_analog_template.png'))
foreach($glyph in @(@(196,6,55,27,256,61),@(209,419,33,27,256,466),@(3,209,27,32,55,256))) {
    $crop=[Windows.Media.Imaging.CroppedBitmap]::new($template,[Windows.Int32Rect]::new($glyph[0],$glyph[1],$glyph[2],$glyph[3]))
    $w=$glyph[2]*1.7; $h=$glyph[3]*1.7
    if($glyph[0] -eq 3){$dc.PushTransform([Windows.Media.RotateTransform]::new(90,$glyph[4],$glyph[5]))}
    $dc.DrawImage($crop,[Windows.Rect]::new($glyph[4]-$w/2,$glyph[5]-$h/2,$w,$h))
    if($glyph[0] -eq 3){$dc.Pop()}
}
$dc.DrawRoundedRectangle((Brush '#38291E'),$null,[Windows.Rect]::new(426,233,58,37),6,6)
Text '23' 455 251 30 '#FFD5C0'
IconFromVector 'ic_iob' 74.45 203.94 32 -74
IconFromVector 'ic_cob' 436.46 307.75 32 286
$dc.PushTransform([Windows.Media.RotateTransform]::new(21,323.68,79.68))
$dc.DrawRoundedRectangle((Brush '#FFD0B4'),$null,[Windows.Rect]::new(316.68,68.68,14,22),2,2)
$dc.DrawRectangle((Brush '#FFD0B4'),$null,[Windows.Rect]::new(320.68,65.68,6,4))
$dc.Pop()
CurveText '0.6U' 187.73 300 344 32 '#FFD0B4'
CurveText '33%' 187.73 33 78 32 '#FFD0B4'
CurveText '15g' 187.73 166 113 32 '#FFD0B4'
CurveText 'Freitag 10:08' 142.79 280 440 30 '#FF935C'
CurveText '23. Oktober' 96.71 280 440 30 '#999999'
$dc.DrawEllipse((Brush '#26201B'),$null,[Windows.Point]::new(148,368),81,81)
Arc 148 368 67.13 351 500 13.65 '#523222'
Arc 148 368 67.13 220 331.5 13.65 '#FF5F0D'
Text '123' 148 365 39 '#E3E3E3'
Text '→' 148 420 32 '#FF935C'
CurveText '+5 2m' 95.57 173 99 30 '#FFD0B4'
CurveText '11:00' 144.5 173 99 30 '#FFD0B4'
for($i=0;$i -le 3;$i++) {
    $a=(20+5*$i)*[Math]::PI/180
    $width=5*512/450
    $height=$(if($i -eq 3){21}else{15})*512/450
    $outside=199.5*512/450-$width/2
    $inside=199.5*512/450-$height+$width/2
    $pen=[Windows.Media.Pen]::new((Brush '#FF5F0D'),$width)
    $pen.StartLineCap=$pen.EndLineCap=[Windows.Media.PenLineCap]::Round
    $dc.DrawLine($pen,[Windows.Point]::new(256+$inside*[Math]::Sin($a),256-$inside*[Math]::Cos($a)),[Windows.Point]::new(256+$outside*[Math]::Sin($a),256-$outside*[Math]::Cos($a)))
}
$assets=Join-Path $repo 'watchfaces/sugarlicious-analog/src/main/res/drawable-nodpi'
foreach($hand in @(@('hour_hand.png',304.0),@('minute_hand.png',48.0),@('second_hand.png',192.0))) {
    $file=Join-Path $assets $hand[0]
    $image=[Windows.Media.Imaging.BitmapImage]::new([Uri]$file)
    $dc.PushTransform([Windows.Media.RotateTransform]::new($hand[1],256,256))
    $dc.DrawImage($image,[Windows.Rect]::new(0,0,512,512))
    $dc.Pop()
}
$dc.Close()
$bitmap=[Windows.Media.Imaging.RenderTargetBitmap]::new(512,512,96,96,[Windows.Media.PixelFormats]::Pbgra32)
$bitmap.Render($visual)
$encoder=[Windows.Media.Imaging.PngBitmapEncoder]::new()
$encoder.Frames.Add([Windows.Media.Imaging.BitmapFrame]::Create($bitmap))
$stream=[IO.File]::Open($output,[IO.FileMode]::Create)
try{$encoder.Save($stream)}finally{$stream.Dispose()}
Write-Output $output
