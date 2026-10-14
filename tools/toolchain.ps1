<#
.SYNOPSIS
  解析 / 下载 / 缓存构建「局域网唤醒」APK 所需的 JDK 与 Android SDK 构件。

.DESCRIPTION
  优先复用本机已有 JDK（含 FinalShell 自带的完整 JDK 17），否则从镜像下载。
  Android SDK 只需要 build-tools（aapt2 / d8 / zipalign / apksigner）与 platform（android.jar），
  不需要 Gradle，也不需要 commandlinetools（省掉 150MB 下载）。

  下载源顺序：腾讯云 AndroidSDK 镜像 -> dl.google.com 官方源。

.PARAMETER ToolchainDir
  工具链缓存目录，默认 <仓库根>\.toolchain

.PARAMETER Force
  忽略已有缓存，强制重新下载与解压。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\toolchain.ps1
#>
[CmdletBinding()]
param(
    [string]$ToolchainDir,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue

$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $ToolchainDir) { $ToolchainDir = Join-Path $repoRoot '.toolchain' }
$ToolchainDir = [System.IO.Path]::GetFullPath($ToolchainDir)

$sdkBaseUrls = @(
    'https://mirrors.cloud.tencent.com/AndroidSDK/',
    'https://dl.google.com/android/repository/'
)

# 已实测可用的文件名（按优先级），前者失败自动尝试后者
$buildToolsZips = @('build-tools_r34-windows.zip', 'build-tools_r33-windows.zip')
$platformZips = @('platform-34-ext7_r03.zip', 'platform-35_r02.zip')
$jdkZipUrls = @(
    'https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip'
)

function Write-Step([string]$msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Write-Ok([string]$msg) { Write-Host "    OK  $msg" -ForegroundColor Green }
function Write-Warn2([string]$msg) { Write-Host "    !!  $msg" -ForegroundColor Yellow }

function Get-RemoteFile {
    param([string[]]$Urls, [string]$Destination, [string]$What)

    if ((Test-Path $Destination) -and -not $Force) {
        Write-Ok "$What 已缓存：$Destination"
        return $true
    }
    $dir = Split-Path -Parent $Destination
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    $part = "$Destination.part"
    if (Test-Path $part) { Remove-Item $part -Force }

    $webClient = New-Object System.Net.WebClient
    $webClient.Headers.Add('User-Agent', 'Mozilla/5.0 (wol-toolchain)')
    try {
        foreach ($url in $Urls) {
            try {
                Write-Host "    下载 $url" -ForegroundColor DarkGray
                $webClient.DownloadFile($url, $part)
                $size = (Get-Item $part).Length
                if ($size -lt 1024) { throw "文件过小（$size 字节），可能是错误页面" }
                Move-Item -Path $part -Destination $Destination -Force
                Write-Ok ("{0}：{1:N1} MB" -f $What, ($size / 1MB))
                return $true
            }
            catch {
                Write-Warn2 ("{0} 失败：{1}" -f $url, $_.Exception.Message)
                if (Test-Path $part) { Remove-Item $part -Force -ErrorAction SilentlyContinue }
            }
        }
    }
    finally {
        $webClient.Dispose()
    }
    return $false
}

function Expand-ZipFast {
    param([string]$ZipPath, [string]$TargetDir)
    if (Test-Path $TargetDir) { Remove-Item $TargetDir -Recurse -Force }
    New-Item -ItemType Directory -Path $TargetDir -Force | Out-Null
    [System.IO.Compression.ZipFile]::ExtractToDirectory($ZipPath, $TargetDir)
}

function Find-First {
    param([string]$Root, [string]$Filter)
    if (-not (Test-Path $Root)) { return $null }
    Get-ChildItem -Path $Root -Recurse -Filter $Filter -File -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
}

function Test-JdkUsable {
    <#
      判断某个 JDK 是否可用于构建：
      javac 通过 jdk.zipfs 提供的 NIO 文件系统读取 android.jar / d8.jar，
      而一些 jlink 裁剪过的运行时（例如第三方软件自带的 jre）缺少该模块，
      会报 "No file system provider is available to handle this file: xxx.jar"。
    #>
    param([string]$JdkDir)
    $javaExe = Join-Path $JdkDir 'bin\java.exe'
    if (-not (Test-Path $javaExe)) { return $false }
    $modules = & $javaExe --list-modules 2>$null
    if ($LASTEXITCODE -ne 0) { return $false }
    foreach ($m in $modules) {
        if ($m -like 'jdk.zipfs*') { return $true }
    }
    return $false
}

# ---------------------------------------------------------------- JDK
Write-Step '解析 JDK'
$jdkCandidates = @(
    (Join-Path $ToolchainDir 'jdk'),
    'D:\Program Files\FinalShell\jre',
    $env:JAVA_HOME
) | Where-Object { $_ -and (Test-Path (Join-Path $_ 'bin\javac.exe')) }

$jdkHome = $null
foreach ($cand in $jdkCandidates) {
    $full = [System.IO.Path]::GetFullPath($cand)
    if (Test-JdkUsable -JdkDir $full) {
        $jdkHome = $full
        Write-Ok "使用本机 JDK：$jdkHome"
        break
    }
    Write-Warn2 "跳过不可用的 JDK（缺少 jdk.zipfs，javac 无法读取 jar）：$full"
}

if (-not $jdkHome) {
    Write-Warn2 '未找到可用 JDK，开始下载 OpenJDK 17（约 190MB）'
    $jdkZip = Join-Path $ToolchainDir 'downloads\openjdk17.zip'
    if (-not (Get-RemoteFile -Urls $jdkZipUrls -Destination $jdkZip -What 'OpenJDK 17')) {
        throw 'JDK 下载失败，请检查网络，或手动安装 JDK 17 后设置 JAVA_HOME 再重试。'
    }
    $jdkExtract = Join-Path $ToolchainDir 'jdk-extract'
    Expand-ZipFast -ZipPath $jdkZip -TargetDir $jdkExtract
    $bin = Find-First -Root $jdkExtract -Filter 'javac.exe'
    if (-not $bin) { throw 'JDK 解压后未找到 javac.exe。' }
    $inner = Split-Path -Parent (Split-Path -Parent $bin)
    $jdkTarget = Join-Path $ToolchainDir 'jdk'
    if (Test-Path $jdkTarget) { Remove-Item $jdkTarget -Recurse -Force }
    Move-Item -Path $inner -Destination $jdkTarget -Force
    Remove-Item $jdkExtract -Recurse -Force -ErrorAction SilentlyContinue
    $jdkHome = $jdkTarget
    Write-Ok "JDK 解压完成：$jdkHome"
}

$javac = Join-Path $jdkHome 'bin\javac.exe'
$keytool = Join-Path $jdkHome 'bin\keytool.exe'
$java = Join-Path $jdkHome 'bin\java.exe'
foreach ($p in @($javac, $keytool, $java)) {
    if (-not (Test-Path $p)) { throw "JDK 不完整，缺少：$p" }
}

# ---------------------------------------------------------------- Android SDK
Write-Step '解析 Android SDK（build-tools + platform）'
$buildToolsDir = Join-Path $ToolchainDir 'sdk\build-tools'
$platformDir = Join-Path $ToolchainDir 'sdk\platforms'
$downloadDir = Join-Path $ToolchainDir 'downloads'

$aapt2 = Find-First -Root $buildToolsDir -Filter 'aapt2.exe'
$zipalign = Find-First -Root $buildToolsDir -Filter 'zipalign.exe'
$d8Jar = Find-First -Root $buildToolsDir -Filter 'd8.jar'
$apksignerJar = Find-First -Root $buildToolsDir -Filter 'apksigner.jar'
$androidJar = Find-First -Root $platformDir -Filter 'android.jar'
$coreForSystem = Find-First -Root $platformDir -Filter 'core-for-system-modules.jar'

if ($Force -or -not $aapt2 -or -not $d8Jar -or -not $zipalign -or -not $apksignerJar) {
    $zip = $null
    foreach ($name in $buildToolsZips) {
        $candidate = Join-Path $downloadDir $name
        $urls = $sdkBaseUrls | ForEach-Object { $_ + $name }
        if (Get-RemoteFile -Urls $urls -Destination $candidate -What "build-tools ($name)") { $zip = $candidate; break }
    }
    if (-not $zip) { throw 'build-tools 下载失败（腾讯镜像与 Google 官方源均不可用）。' }
    Write-Host '    解压 build-tools ...' -ForegroundColor DarkGray
    Expand-ZipFast -ZipPath $zip -TargetDir $buildToolsDir
    $aapt2 = Find-First -Root $buildToolsDir -Filter 'aapt2.exe'
    $zipalign = Find-First -Root $buildToolsDir -Filter 'zipalign.exe'
    $d8Jar = Find-First -Root $buildToolsDir -Filter 'd8.jar'
    $apksignerJar = Find-First -Root $buildToolsDir -Filter 'apksigner.jar'
}

if (-not $androidJar) {
    $zip = $null
    foreach ($name in $platformZips) {
        $candidate = Join-Path $downloadDir $name
        $urls = $sdkBaseUrls | ForEach-Object { $_ + $name }
        if (Get-RemoteFile -Urls $urls -Destination $candidate -What "platform ($name)") { $zip = $candidate; break }
    }
    if (-not $zip) { throw 'platform (android.jar) 下载失败。' }
    Write-Host '    解压 platform ...' -ForegroundColor DarkGray
    Expand-ZipFast -ZipPath $zip -TargetDir $platformDir
    $androidJar = Find-First -Root $platformDir -Filter 'android.jar'
    $coreForSystem = Find-First -Root $platformDir -Filter 'core-for-system-modules.jar'
}

foreach ($p in @($aapt2, $zipalign, $d8Jar, $apksignerJar, $androidJar)) {
    if (-not $p -or -not (Test-Path $p)) { throw "工具链构件缺失：$p" }
}

$manifest = [ordered]@{
    generatedAt   = (Get-Date).ToString('s')
    repoRoot      = $repoRoot
    toolchainDir  = $ToolchainDir
    jdkHome       = $jdkHome
    java          = $java
    javac         = $javac
    keytool       = $keytool
    aapt2         = $aapt2
    zipalign      = $zipalign
    d8Jar         = $d8Jar
    apksignerJar  = $apksignerJar
    androidJar    = $androidJar
    coreForSystem = $coreForSystem
}
$manifestPath = Join-Path $ToolchainDir 'toolchain.json'
$manifest | ConvertTo-Json -Depth 4 | Set-Content -Path $manifestPath -Encoding UTF8
Write-Ok "工具链清单：$manifestPath"

# ---------------------------------------------------------------- 自检
# 原生程序会把用法/警告写到 stderr，PowerShell 5.1 在 Stop 策略下会把它当成终止错误，
# 因此自检阶段改用 Continue，并逐个检查 $LASTEXITCODE。
Write-Step '工具链自检'
$ErrorActionPreference = 'Continue'
$lastExit = 0
$failed = @()

function Invoke-Tool {
    param([string]$Label, [scriptblock]$Block)
    Write-Host "    [$Label]" -ForegroundColor DarkGray
    & $Block
    if ($LASTEXITCODE -ne 0) { $script:failed += "$Label (exit $LASTEXITCODE)" }
}

Invoke-Tool 'javac' { & $javac -version }
Invoke-Tool 'aapt2' { & $aapt2 version }
Invoke-Tool 'd8' { & $java -cp $d8Jar com.android.tools.r8.D8 --version }
Write-Host "    [zipalign] $zipalign" -ForegroundColor DarkGray
& $zipalign 2>&1 | Select-Object -First 2
Invoke-Tool 'apksigner' { & $java -cp $apksignerJar com.android.apksigner.ApkSignerTool --version }

if ($failed.Count -gt 0) {
    Write-Warn2 ("以下组件自检未通过：{0}" -f ($failed -join '; '))
}
$ErrorActionPreference = 'Stop'

Write-Step '完成'
Write-Host "    JDK        : $jdkHome"
Write-Host "    android.jar: $androidJar"
Write-Host "    build-tools: $(Split-Path -Parent $aapt2)"
