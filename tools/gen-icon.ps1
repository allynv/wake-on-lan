<#
.SYNOPSIS
  生成应用启动图标（各密度 PNG），使用 .NET System.Drawing，完全离线。

.DESCRIPTION
  API 26+ 设备使用 res\mipmap-anydpi-v26 下的自适应矢量图标；
  这里的 PNG 供 API 23-25 的旧设备与部分启动器兜底使用。
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$repoRoot = Split-Path -Parent $PSScriptRoot
$resDir = Join-Path $repoRoot 'app\src\main\res'

$targets = @(
    @{ Dir = 'mipmap-mdpi'; Size = 48 },
    @{ Dir = 'mipmap-hdpi'; Size = 72 },
    @{ Dir = 'mipmap-xhdpi'; Size = 96 },
    @{ Dir = 'mipmap-xxhdpi'; Size = 144 },
    @{ Dir = 'mipmap-xxxhdpi'; Size = 192 }
)

# 闪电形状（0-1 归一化坐标）
$bolt = @(
    @(0.55, 0.17),
    @(0.29, 0.56),
    @(0.45, 0.56),
    @(0.39, 0.83),
    @(0.72, 0.42),
    @(0.53, 0.42)
)

function New-RoundedPath {
    param([int]$Size, [single]$Radius)
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $Radius * 2
    $w = $Size - 1
    $h = $Size - 1
    $path.AddArc(0, 0, $d, $d, 180, 90)
    $path.AddArc($w - $d, 0, $d, $d, 270, 90)
    $path.AddArc($w - $d, $h - $d, $d, $d, 0, 90)
    $path.AddArc(0, $h - $d, $d, $d, 90, 90)
    $path.CloseFigure()
    return $path
}

foreach ($target in $targets) {
    $size = [int]$target.Size
    $dir = Join-Path $resDir $target.Dir
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    $file = Join-Path $dir 'ic_launcher.png'

    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([System.Drawing.Color]::Transparent)

    $bgBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 34, 197, 94))
    $fgBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)

    $path = New-RoundedPath -Size $size -Radius ($size * 0.22)
    $g.FillPath($bgBrush, $path)

    $points = New-Object 'System.Drawing.PointF[]' $bolt.Count
    for ($i = 0; $i -lt $bolt.Count; $i++) {
        $points[$i] = New-Object System.Drawing.PointF(
            ([single]($bolt[$i][0] * $size)),
            ([single]($bolt[$i][1] * $size)))
    }
    $g.FillPolygon($fgBrush, $points)

    $g.Dispose()
    $bmp.Save($file, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    $path.Dispose()
    $bgBrush.Dispose()
    $fgBrush.Dispose()
    Write-Host "    生成 $file" -ForegroundColor DarkGray
}

Write-Host '    图标生成完成' -ForegroundColor Green
