<#
.SYNOPSIS
  静态检查所有 layout XML 的布局参数。

.DESCRIPTION
  Android 在 inflate 时，若某个子视图既没有显式声明 layout_width / layout_height，
  所引用的 style 里也没有提供，就会抛
  "You must supply a layout_width attribute"，
  表现为「一进入该页面就闪退」。aapt2 编译期不会报这个错，只能在运行时炸，
  所以这里在构建前做一次静态检查。

  用法：powershell -ExecutionPolicy Bypass -File tools\check-layouts.ps1
  全部通过退出码 0，否则 1。
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$layoutDir = Join-Path $repoRoot 'app\src\main\res\layout'
$valuesDir = Join-Path $repoRoot 'app\src\main\res\values'

if (-not (Test-Path $layoutDir)) { throw "找不到布局目录：$layoutDir" }

# 跳过不需要布局参数的标签
$skipTags = @('merge', 'include', 'requestFocus', 'item', 'style')

function Test-HasLayoutAttr {
    param($Node, [string]$Name)
    foreach ($candidate in @($Name, ($Name -replace '^android:', ''))) {
        if ($Node.HasAttribute($candidate)) { return $true }
    }
    # 带命名空间的属性在某些加载方式下只有 LocalName
    foreach ($attr in $Node.Attributes) {
        if ($attr.LocalName -eq ($Name -replace '^android:', '')) { return $true }
    }
    return $false
}

# ---------------------------------------------------------------- 收集 style 定义
$styles = @{}
if (Test-Path $valuesDir) {
    foreach ($file in Get-ChildItem $valuesDir -Filter '*.xml') {
        [xml]$doc = Get-Content $file.FullName -Raw -Encoding UTF8
        if (-not $doc.resources) { continue }
        foreach ($style in $doc.resources.style) {
            $attrs = @{}
            foreach ($item in $style.item) {
                if ($item.name) { $attrs[$item.name] = $true }
            }
            if ($style.name) { $styles[$style.name] = $attrs }
        }
    }
}

# ---------------------------------------------------------------- 检查布局
$problems = New-Object System.Collections.Generic.List[string]
$checkedViews = 0
$checkedFiles = 0

foreach ($file in Get-ChildItem $layoutDir -Filter '*.xml') {
    $checkedFiles++
    [xml]$doc = Get-Content $file.FullName -Raw -Encoding UTF8
    foreach ($node in $doc.SelectNodes('//*')) {
        $tag = $node.Name
        if ($skipTags -contains $tag) { continue }
        $checkedViews++

        $hasWidth = Test-HasLayoutAttr $node 'android:layout_width'
        $hasHeight = Test-HasLayoutAttr $node 'android:layout_height'
        if ($hasWidth -and $hasHeight) { continue }

        $styleAttrs = @{}
        if ($node.HasAttribute('style')) {
            $styleName = $node.GetAttribute('style') -replace '^@style/', ''
            if ($styles.ContainsKey($styleName)) { $styleAttrs = $styles[$styleName] }
        }

        if (-not $hasWidth -and -not $styleAttrs.ContainsKey('android:layout_width')) {
            $problems.Add(("{0}: <{1}> 缺少 android:layout_width" -f $file.Name, $tag))
        }
        if (-not $hasHeight -and -not $styleAttrs.ContainsKey('android:layout_height')) {
            $problems.Add(("{0}: <{1}> 缺少 android:layout_height" -f $file.Name, $tag))
        }
    }
}

Write-Host ("==> 布局检查：{0} 个文件 / {1} 个视图元素" -f $checkedFiles, $checkedViews) -ForegroundColor Cyan
if ($problems.Count -gt 0) {
    Write-Host '    !! 以下视图缺少布局参数，运行时会闪退：' -ForegroundColor Red
    foreach ($p in $problems) { Write-Host "       $p" -ForegroundColor Red }
    exit 1
}
Write-Host '    OK  所有视图都有布局参数' -ForegroundColor Green
exit 0
