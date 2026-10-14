<#
.SYNOPSIS
  运行纯逻辑层单元测试（MAC 解析 / 魔术包结构 / 子网广播计算 / 飞书指令解析）。

.DESCRIPTION
  直接复用 app\src\main\java 下不依赖 Android 的类（MacAddress、WolPacket、NetMath、FeishuCommand），
  用 JDK 编译并在 JVM 上运行 tools\jvmtest 下的两个测试类。
  全部通过退出码为 0，否则为 1。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\test-packet.ps1
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Continue'

$repoRoot = Split-Path -Parent $PSScriptRoot
$toolchainJson = Join-Path $repoRoot '.toolchain\toolchain.json'

if (-not (Test-Path $toolchainJson)) {
    Write-Host '==> 未找到工具链，先执行 tools\toolchain.ps1' -ForegroundColor Cyan
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'toolchain.ps1')
    if (-not (Test-Path $toolchainJson)) { throw '工具链准备失败。' }
}

$tool = Get-Content $toolchainJson -Raw -Encoding UTF8 | ConvertFrom-Json
$javac = $tool.javac
$java = $tool.java

$outDir = Join-Path $repoRoot 'build\jvmtest'
if (Test-Path $outDir) { Remove-Item $outDir -Recurse -Force }
New-Item -ItemType Directory -Path $outDir -Force | Out-Null

$appJava = Join-Path $repoRoot 'app\src\main\java\com\wol\app'
$sources = @(
    (Join-Path $appJava 'MacAddress.java'),
    (Join-Path $appJava 'WolPacket.java'),
    (Join-Path $appJava 'NetMath.java'),
    (Join-Path $appJava 'Texts.java'),
    (Join-Path $appJava 'Device.java'),
    (Join-Path $appJava 'FeishuCommand.java'),
    (Join-Path $appJava 'FeishuErrors.java'),
    (Join-Path $appJava 'FeishuTexts.java'),
    (Join-Path $PSScriptRoot 'jvmtest\WolPacketTest.java'),
    (Join-Path $PSScriptRoot 'jvmtest\FeishuCommandTest.java'),
    (Join-Path $PSScriptRoot 'jvmtest\FeishuTextsTest.java')
)

Write-Host '==> 编译测试代码' -ForegroundColor Cyan
& $javac -encoding UTF-8 -d $outDir @sources
if ($LASTEXITCODE -ne 0) { throw '测试代码编译失败。' }

$failed = 0
foreach ($suite in @('WolPacketTest', 'FeishuCommandTest', 'FeishuTextsTest')) {
    Write-Host "==> 运行 $suite" -ForegroundColor Cyan
    & $java -cp $outDir $suite
    if ($LASTEXITCODE -ne 0) { $failed++ }
}

Write-Host ''
if ($failed -gt 0) {
    Write-Host "==> 单元测试失败（$failed 个套件）" -ForegroundColor Red
    exit 1
}
Write-Host '==> 单元测试全部通过' -ForegroundColor Green
