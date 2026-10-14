<#
.SYNOPSIS
  一键构建「局域网唤醒」APK（不依赖 Gradle / Android Studio）。

.DESCRIPTION
  流水线：aapt2 compile -> aapt2 link -> javac -> d8 -> 组装 APK -> zipalign -> apksigner 签名。
  首次运行若缺少工具链，会先调用 tools\toolchain.ps1 自动下载 JDK / Android SDK。

.PARAMETER OutDir
  APK 输出目录，默认 <仓库根>\dist

.PARAMETER VersionName
  版本名，默认 1.0.0

.PARAMETER VersionCode
  版本号，默认 1

.PARAMETER MinSdk
  最低 API，默认 23

.PARAMETER TargetSdk
  目标 API，默认 34

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\build.ps1
#>
[CmdletBinding()]
param(
    [string]$OutDir,
    [string]$VersionName = '1.0.0',
    [int]$VersionCode = 1,
    [int]$MinSdk = 23,
    [int]$TargetSdk = 34
)

$ErrorActionPreference = 'Continue'
Add-Type -AssemblyName System.IO.Compression -ErrorAction SilentlyContinue
Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue

$repoRoot = Split-Path -Parent $PSScriptRoot
$appDir = Join-Path $repoRoot 'app'
$resDir = Join-Path $appDir 'src\main\res'
$javaDir = Join-Path $appDir 'src\main\java'
$manifest = Join-Path $appDir 'src\main\AndroidManifest.xml'
$buildDir = Join-Path $repoRoot 'build'
if (-not $OutDir) { $OutDir = Join-Path $repoRoot 'dist' }
$toolchainJson = Join-Path $repoRoot '.toolchain\toolchain.json'
$keystore = Join-Path $repoRoot 'keys\wol-release.jks'
$ksPass = 'wol123456'

function Write-Step([string]$msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Write-Ok([string]$msg) { Write-Host "    OK  $msg" -ForegroundColor Green }

function Invoke-Native {
    param([string]$Exe, [string[]]$Arguments, [string]$Label)
    & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Label 失败（exit code $LASTEXITCODE）"
    }
}

function New-ZipArchive {
    param([string]$Path)
    if (Test-Path $Path) { Remove-Item $Path -Force }
    $archive = [System.IO.Compression.ZipFile]::Open($Path, [System.IO.Compression.ZipArchiveMode]::Create)
    if ($null -eq $archive) { throw "无法创建压缩包：$Path" }
    return $archive
}

function Test-ArscStored {
    <#
      解析 zip 中央目录，确认 resources.arsc 使用 STORED（method 0）。
      Android 11+（targetSdk >= 30）强制要求该文件不压缩且 4 字节对齐。
    #>
    param([string]$ApkPath)
    $bytes = [System.IO.File]::ReadAllBytes($ApkPath)
    $eocd = -1
    for ($i = $bytes.Length - 22; $i -ge 0; $i--) {
        if ($bytes[$i] -eq 0x50 -and $bytes[$i + 1] -eq 0x4B -and
            $bytes[$i + 2] -eq 0x05 -and $bytes[$i + 3] -eq 0x06) {
            $eocd = $i
            break
        }
    }
    if ($eocd -lt 0) { return $false }
    $count = [BitConverter]::ToUInt16($bytes, $eocd + 10)
    $offset = [BitConverter]::ToUInt32($bytes, $eocd + 16)
    for ($n = 0; $n -lt $count; $n++) {
        if ([BitConverter]::ToUInt32($bytes, $offset) -ne 0x02014B50) { return $false }
        $method = [BitConverter]::ToUInt16($bytes, $offset + 10)
        $nameLen = [BitConverter]::ToUInt16($bytes, $offset + 28)
        $extraLen = [BitConverter]::ToUInt16($bytes, $offset + 30)
        $commentLen = [BitConverter]::ToUInt16($bytes, $offset + 32)
        $name = [System.Text.Encoding]::UTF8.GetString($bytes, $offset + 46, $nameLen)
        if ($name -eq 'resources.arsc') { return ($method -eq 0) }
        $offset += 46 + $nameLen + $extraLen + $commentLen
    }
    return $false
}

# ---------------------------------------------------------------- 工具链
if (-not (Test-Path $toolchainJson)) {
    Write-Step '未找到工具链，先执行 tools\toolchain.ps1'
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'toolchain.ps1')
    if (-not (Test-Path $toolchainJson)) { throw '工具链准备失败。' }
}
$tool = Get-Content $toolchainJson -Raw -Encoding UTF8 | ConvertFrom-Json
$aapt2 = $tool.aapt2
$zipalign = $tool.zipalign
$d8Jar = $tool.d8Jar
$apksignerJar = $tool.apksignerJar
$androidJar = $tool.androidJar
$java = $tool.java
$javac = $tool.javac
$keytool = $tool.keytool

foreach ($p in @($aapt2, $zipalign, $d8Jar, $apksignerJar, $androidJar, $java, $javac, $keytool)) {
    if (-not (Test-Path $p)) { throw "工具链文件缺失：$p（可删除 .toolchain 后重试）" }
}
Write-Step "工具链就绪（android.jar: $(Split-Path -Parent $androidJar)）"

# ---------------------------------------------------------------- 目录准备
if (Test-Path $buildDir) { Remove-Item $buildDir -Recurse -Force }
New-Item -ItemType Directory -Path $buildDir -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $buildDir 'gen') -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $buildDir 'classes') -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $buildDir 'dex') -Force | Out-Null
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }

# ---------------------------------------------------------------- 图标
$iconProbe = Join-Path $resDir 'mipmap-xxhdpi\ic_launcher.png'
if (-not (Test-Path $iconProbe)) {
    Write-Step '生成启动图标 PNG'
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'gen-icon.ps1')
    if (-not (Test-Path $iconProbe)) { throw '图标生成失败。' }
    Write-Ok '图标已生成'
}

# ---------------------------------------------------------------- 布局静态检查
# aapt2 编译期不会发现「视图缺少 layout_width/height」这种运行时会闪退的问题
Write-Step '静态检查布局参数'
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'check-layouts.ps1')
if ($LASTEXITCODE -ne 0) { throw '布局静态检查未通过（运行时会闪退），已中止构建。' }

# ---------------------------------------------------------------- 资源编译 / 链接
$resZip = Join-Path $buildDir 'resources.zip'
$baseApk = Join-Path $buildDir 'base.apk'
Write-Step 'aapt2 compile（资源编译）'
Invoke-Native $aapt2 @('compile', '--dir', $resDir, '-o', $resZip) 'aapt2 compile'
Write-Ok $resZip

Write-Step 'aapt2 link（资源链接 + 生成 R.java）'
Invoke-Native $aapt2 @(
    'link',
    '-o', $baseApk,
    '-I', $androidJar,
    '--manifest', $manifest,
    '-R', $resZip,
    '--java', (Join-Path $buildDir 'gen'),
    '--min-sdk-version', "$MinSdk",
    '--target-sdk-version', "$TargetSdk",
    '--version-code', "$VersionCode",
    '--version-name', $VersionName,
    '--auto-add-overlay'
) 'aapt2 link'
Write-Ok $baseApk

# ---------------------------------------------------------------- javac
Write-Step 'javac（编译 Java 源码）'
$sources = New-Object System.Collections.Generic.List[string]
Get-ChildItem -Path (Join-Path $buildDir 'gen') -Recurse -Filter '*.java' | ForEach-Object { $sources.Add($_.FullName) }
Get-ChildItem -Path $javaDir -Recurse -Filter '*.java' | ForEach-Object { $sources.Add($_.FullName) }
if ($sources.Count -eq 0) { throw '没有找到任何 Java 源码。' }

$javacArgs = New-Object System.Collections.Generic.List[string]
$javacArgs.Add('-Xlint:-options')
$javacArgs.Add('-Xlint:deprecation')
$javacArgs.Add('-encoding')
$javacArgs.Add('UTF-8')
$javacArgs.Add('-source')
$javacArgs.Add('8')
$javacArgs.Add('-target')
$javacArgs.Add('8')
$javacArgs.Add('-bootclasspath')
$javacArgs.Add($androidJar)
$javacArgs.Add('-classpath')
$javacArgs.Add($androidJar)
$javacArgs.Add('-d')
$javacArgs.Add((Join-Path $buildDir 'classes'))
foreach ($s in $sources) { $javacArgs.Add($s) }

Invoke-Native $javac $javacArgs.ToArray() 'javac'
Write-Ok "$($sources.Count) 个源文件编译完成"

# ---------------------------------------------------------------- d8
Write-Step 'd8（Java 字节码 -> DEX）'
$classesJar = Join-Path $buildDir 'classes.jar'
$jar = New-ZipArchive $classesJar
Get-ChildItem -Path (Join-Path $buildDir 'classes') -Recurse -Filter '*.class' | ForEach-Object {
    $rel = $_.FullName.Substring((Join-Path $buildDir 'classes').Length).TrimStart('\')
    $rel = $rel -replace '\\', '/'
    $entry = $jar.CreateEntry($rel, [System.IO.Compression.CompressionLevel]::Optimal)
    $entryStream = $entry.Open()
    $fileStream = [System.IO.File]::OpenRead($_.FullName)
    $fileStream.CopyTo($entryStream)
    $fileStream.Close()
    $entryStream.Close()
}
$jar.Dispose()
Invoke-Native $java @('-cp', $d8Jar, 'com.android.tools.r8.D8', '--min-api', "$MinSdk", '--lib', $androidJar, '--output', (Join-Path $buildDir 'dex'), $classesJar) 'd8'
$dexFile = Join-Path $buildDir 'dex\classes.dex'
if (-not (Test-Path $dexFile)) { throw "d8 未生成 classes.dex。" }
Write-Ok ("classes.dex {0:N1} KB" -f ((Get-Item $dexFile).Length / 1KB))

# ---------------------------------------------------------------- 组装 APK
# 注意：不能用 .NET 的 ZipArchive —— 它即使在 NoCompression 下仍会写成 deflate，
# 而 Android 11+ 要求 resources.arsc 必须是 STORED。这里交给 tools\ZipBuilder.java。
Write-Step '组装 APK（合并资源与 classes.dex，resources.arsc 保持 STORED）'
$zipBuilderDir = Join-Path $buildDir 'tools'
New-Item -ItemType Directory -Path $zipBuilderDir -Force | Out-Null
Invoke-Native $javac @('-encoding', 'UTF-8', '-d', $zipBuilderDir, (Join-Path $PSScriptRoot 'ZipBuilder.java')) 'javac (ZipBuilder)'
$unsignedApk = Join-Path $buildDir 'unsigned.apk'
Invoke-Native $java @('-cp', $zipBuilderDir, 'ZipBuilder', $baseApk, $dexFile, $unsignedApk) 'ZipBuilder'
if (-not (Test-Path $unsignedApk)) { throw 'APK 组装失败。' }
Write-Ok $unsignedApk

# ---------------------------------------------------------------- zipalign / 签名
Write-Step 'zipalign（4 字节对齐）'
$alignedApk = Join-Path $buildDir 'aligned.apk'
Invoke-Native $zipalign @('-f', '-p', '4', $unsignedApk, $alignedApk) 'zipalign'
Write-Ok $alignedApk

if (-not (Test-Path $keystore)) {
    Write-Step '生成自签名证书 keys\wol-release.jks'
    New-Item -ItemType Directory -Path (Split-Path -Parent $keystore) -Force | Out-Null
    Invoke-Native $keytool @(
        '-genkeypair', '-v',
        '-keystore', $keystore,
        '-alias', 'wol',
        '-keyalg', 'RSA',
        '-keysize', '2048',
        '-validity', '10950',
        '-storepass', $ksPass,
        '-keypass', $ksPass,
        '-dname', 'CN=WolWake, OU=Personal, O=Personal, L=Local, ST=Local, C=CN'
    ) 'keytool'
    Write-Ok $keystore
}

Write-Step 'apksigner（v1 + v2 签名）'
$finalApk = Join-Path $OutDir ("WolWake-{0}.apk" -f $VersionName)
if (Test-Path $finalApk) { Remove-Item $finalApk -Force }
$idsigFile = "$finalApk.idsig"
if (Test-Path $idsigFile) { Remove-Item $idsigFile -Force }
Invoke-Native $java @(
    '-cp', $apksignerJar, 'com.android.apksigner.ApkSignerTool', 'sign',
    '--ks', $keystore,
    '--ks-pass', "pass:$ksPass",
    '--key-pass', "pass:$ksPass",
    '--min-sdk-version', "$MinSdk",
    '--v1-signing-enabled', 'true',
    '--v2-signing-enabled', 'true',
    # 关闭 v4 签名，避免多出一个 .idsig 文件（只有 adb 增量安装才需要）
    '--v4-signing-enabled', 'false',
    '--out', $finalApk,
    $alignedApk
) 'apksigner sign'
if (Test-Path $idsigFile) { Remove-Item $idsigFile -Force }
Write-Ok $finalApk

# ---------------------------------------------------------------- 自检
Write-Step 'APK 自检'
Write-Host '    [apksigner verify]' -ForegroundColor DarkGray
& $java -cp $apksignerJar com.android.apksigner.ApkSignerTool verify --verbose --print-certs $finalApk
$verifyExit = $LASTEXITCODE

Write-Host '    [aapt2 dump badging]' -ForegroundColor DarkGray
& $aapt2 dump badging $finalApk

if (Test-ArscStored $finalApk) {
    Write-Ok 'resources.arsc 为 STORED（满足 Android 11+ 安装要求）'
}
else {
    Write-Host '    !! resources.arsc 被压缩，Android 11+ 会拒绝安装' -ForegroundColor Yellow
    $verifyExit = 1
}

Write-Step '完成'
Write-Host ("    APK : {0}" -f $finalApk)
Write-Host ("    体积: {0:N1} KB" -f ((Get-Item $finalApk).Length / 1KB))
if ($verifyExit -ne 0) {
    Write-Host '    !! 签名校验未通过，请检查上面的输出' -ForegroundColor Yellow
    exit 1
}
exit 0
