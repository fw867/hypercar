<#
.SYNOPSIS
  HyperCar 本地一键出包（Windows + pwsh）。

.DESCRIPTION
  默认使用 D:\devtools 下的工具链（可用环境变量 HYPERCAR_DEVTOOLS 覆盖）：
    D:\devtools\jdk17             JDK 17（Temurin）
    D:\devtools\android-sdk       Android SDK（platform 35 / build-tools 35.0.0 / NDK r27c）
    D:\devtools\cargo, rustup     Rust（host 工具链 x86_64-pc-windows-gnu）
  流程：cargo-ndk 交叉编译 libhypercar_core.so → gradlew 出包。

  两个 Windows 坑（脚本已自动处理）：
   1. 仓库路径含中文时，AGP 直接拒绝构建（"project path contains non-ASCII
      characters"），GNU ld 也读不了中文路径下的 .o。所以会把仓库映射到一个
      ASCII 目录联接（默认 D:\devtools\hypercar-repo），在那里构建。
   2. Maven 仓库偶发挂死：~/.gradle/init.d/00-mirrors.gradle 已配华为云/腾讯云镜像。

.PARAMETER Release
  出 release 包。需要仓库根存在 keystore.properties，否则只会产出未签名包。

.PARAMETER SkipRust
  跳过 Rust 交叉编译（只改 Kotlin 时用，直接复用上次编好的 .so）。

.PARAMETER SkipHub
跳过 PC 侧 hub 的宿主构建（默认会重新构建并覆盖 tools\hypercar-hub.exe）
.PARAMETER BuildNumber
  正式包构建号（同 CI 的 run_number）：versionName 变成 0.1.0.<N>、versionCode=10000+N。
  本地出正式包时给一个不小于当前线上版本的号，才能覆盖安装。需要 keystore.properties。

.EXAMPLE
  pwsh -File tools\build-local.ps1
  pwsh -File tools\build-local.ps1 -Release -BuildNumber 10500
  pwsh -File tools\build-local.ps1 -SkipRust
#>
param(
  [switch]$Release,
  [switch]$SkipRust,
  [switch]$SkipHub,
  [int]$BuildNumber = 0
)

$ErrorActionPreference = 'Stop'
# 原生命令（cargo/gradlew）往 stderr 打进度是正常行为，别被 Stop 策略当成致命错误。
# 统一走 Invoke-Native：临时切回 Continue、把 stderr 并进普通输出、只认退出码。
$PSNativeCommandUseErrorActionPreference = $false

function Invoke-Native {
  param([string]$Exe, [string[]]$Arguments)
  $prev = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    & $Exe @Arguments 2>&1 | ForEach-Object { Write-Host $_ }
    return $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $prev
  }
}

$repo = Split-Path -Parent $PSScriptRoot
$devtools = if ($env:HYPERCAR_DEVTOOLS) { $env:HYPERCAR_DEVTOOLS } else { 'D:\devtools' }

$jdk = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { Join-Path $devtools 'jdk17' }
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $devtools 'android-sdk' }

foreach ($p in @($jdk, $sdk)) {
  if (-not (Test-Path $p)) { throw "missing: $p  (set HYPERCAR_DEVTOOLS or JAVA_HOME/ANDROID_HOME)" }
}

$env:JAVA_HOME = $jdk
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:PATH = "$(Join-Path $jdk 'bin');$env:PATH"

$ndkRoot = Join-Path $sdk 'ndk'
$ndk = $null
if (Test-Path $ndkRoot) {
  $ndk = Get-ChildItem $ndkRoot -Directory | Sort-Object Name -Descending | Select-Object -First 1
  if ($ndk) {
    $env:ANDROID_NDK_HOME = $ndk.FullName
    $env:ANDROID_NDK_ROOT = $ndk.FullName
  }
}

# ---- 非 ASCII 路径 → 目录联接（AGP 与 GNU ld 都不吃中文路径）----
$root = $repo
$needAsciiBuildDir = $false
if ($repo -match '[^\x00-\x7F]') {
  $needAsciiBuildDir = $true
  $link = Join-Path $devtools 'hypercar-repo'
  $ok = $false
  if (Test-Path $link) {
    $item = Get-Item $link -Force
    $ok = ($item.LinkType -eq 'Junction') -and ("$($item.Target)" -eq $repo)
    if (-not $ok) { (Get-Item $link -Force).Delete() }
  }
  if (-not $ok) {
    New-Item -ItemType Junction -Path $link -Target $repo | Out-Null
    Write-Host "已创建 ASCII 目录联接: $link -> $repo"
  }
  $root = $link
}

Write-Host "JDK    : $jdk"
Write-Host "SDK    : $sdk"
if ($ndk) { Write-Host "NDK    : $($ndk.Name)" }
Write-Host "构建目录: $root"

if (-not $SkipRust) {
  $cargoHome = if ($env:CARGO_HOME) { $env:CARGO_HOME } else { Join-Path $devtools 'cargo' }
  $rustupHome = if ($env:RUSTUP_HOME) { $env:RUSTUP_HOME } else { Join-Path $devtools 'rustup' }
  if (-not (Test-Path (Join-Path $cargoHome 'bin\cargo.exe'))) { throw "cargo not found under $cargoHome (set CARGO_HOME)" }
  $env:CARGO_HOME = $cargoHome
  $env:RUSTUP_HOME = $rustupHome
  $env:PATH = "$(Join-Path $cargoHome 'bin');$env:PATH"
  $env:RUSTUP_NO_UPDATE_CHECK = '1'
  if ($needAsciiBuildDir) {
    # target 目录也放纯 ASCII 路径：GNU ld 会从 cargo 的元数据里拿到 .rlib 的绝对路径，
    # 中文路径它读不了（报 "ld: cannot find ...\软件开发\...\libsyn-xxx.rlib"）。
    $env:CARGO_TARGET_DIR = Join-Path $devtools 'build\hypercar-core'
    New-Item -ItemType Directory -Force -Path $env:CARGO_TARGET_DIR | Out-Null
    Write-Host "Rust target: $env:CARGO_TARGET_DIR"
  }

  Write-Host "`n==> cargo ndk build --release (arm64-v8a)" -ForegroundColor Cyan
  Push-Location (Join-Path $root 'rust-core')
  try {
    $code = Invoke-Native 'cargo' @('ndk', '-t', 'arm64-v8a', '-o', '../app/src/main/jniLibs', 'build', '--release')
    if ($code -ne 0) { throw "Rust 交叉编译失败（exit=$code）" }
  } finally {
    Pop-Location
  }
  $so = Join-Path $root 'app\src\main\jniLibs\arm64-v8a\libhypercar_core.so'
  if (-not (Test-Path $so)) { throw "Rust 产物缺失: $so" }
  Write-Host ("so     : {0:N0} KB  {1}" -f ((Get-Item $so).Length / 1KB), (Get-Item $so).LastWriteTime)

  # ---- PC 侧联调工具：宿主（Windows）构建，产物直接落到 tools\ 供入库/双击 ----
  # 与 Android 的 arm64 交叉编译互不影响（不同 target、不同 CARGO_TARGET_DIR），
  # 放这里是为了「改了 Rust 就不会忘带更新 exe」——之前 el repo 里的 exe 就是这样过期的。
$hubDir = Join-Path $root 'rust-core\hub'
if ((-not $SkipHub) -and (Test-Path $hubDir)) {
    Write-Host "`n==> cargo build --release（hub，宿主 Windows）" -ForegroundColor Cyan
    $hubTarget = Join-Path $devtools 'build\hypercar-hub'
    New-Item -ItemType Directory -Force -Path $hubTarget | Out-Null
    # embed-resource 需要 windres（MinGW）：优先用 HYPERCAR_MINGW，其次常见路径
    $mingw = if ($env:HYPERCAR_MINGW) { $env:HYPERCAR_MINGW } else { Join-Path $devtools 'mingw64\mingw64\bin' }
    if (Test-Path $mingw) { $env:PATH = "$mingw;$env:PATH" }
    $savedTarget = $env:CARGO_TARGET_DIR
    $env:CARGO_TARGET_DIR = $hubTarget
    Push-Location (Join-Path $root 'rust-core\hub')
    try {
      $code = Invoke-Native 'cargo' @('build', '--release')
      if ($code -ne 0) { throw "hub 构建失败（exit=$code）。缺 windres 时设 HYPERCAR_MINGW，或用 -SkipHub 跳过" }
    } finally {
      Pop-Location
      $env:CARGO_TARGET_DIR = $savedTarget
    }
    $hubExe = Join-Path $hubTarget 'release\hypercar-hub.exe'
    if (-not (Test-Path $hubExe)) { throw "hub 产物缺失: $hubExe" }
    $hubDest = Join-Path $root 'tools\hypercar-hub.exe'
    Copy-Item $hubExe $hubDest -Force
    Write-Host ("hub    : {0:N0} KB  {1}  → tools\hypercar-hub.exe" -f ((Get-Item $hubDest).Length / 1KB), (Get-Item $hubDest).LastWriteTime)
  } else {
    Write-Host "`n==> 跳过 hub 构建（-SkipHub 或 hub 源码已移除）" -ForegroundColor Yellow
  }
} else {
  Write-Host "`n==> 跳过 Rust（-SkipRust）" -ForegroundColor Yellow
}

$task = if ($Release) { ':app:assembleRelease' } else { ':app:assembleDebug' }
if ($Release -and -not (Test-Path (Join-Path $root 'keystore.properties'))) {
  Write-Warning "没有 keystore.properties：release 产物是未签名包（app-release-unsigned.apk），装不上。"
}

$gradleArgs = @($task, '--console=plain')
if ($BuildNumber -gt 0) {
  # 与 CI 同一套版本号规则：versionName=0.1.0.<N>、versionCode=10000+N。
  # 本地出正式包时给一个 ≥ 当前 CI run_number 的数字，才能覆盖安装线上包。
  if (-not (Test-Path (Join-Path $root 'keystore.properties'))) {
    throw "-BuildNumber 需要正式签名材料（keystore.properties），否则 Gradle 会按设计直接失败"
  }
  $gradleArgs += "-Phypercar.buildNumber=$BuildNumber"
}

Write-Host "`n==> gradlew $($gradleArgs -join ' ')" -ForegroundColor Cyan
Push-Location $root
try {
  $code = Invoke-Native '.\gradlew.bat' $gradleArgs
  if ($code -ne 0) { throw "Gradle 构建失败（exit=$code）" }
} finally {
  Pop-Location
}

Write-Host "`n==> APK" -ForegroundColor Green
Get-ChildItem (Join-Path $root 'app\build\outputs\apk') -Recurse -Filter *.apk -ErrorAction SilentlyContinue |
  Sort-Object LastWriteTime -Descending |
  Select-Object -First 5 @{n='APK';e={$_.FullName}}, @{n='MB';e={[math]::Round($_.Length / 1MB, 1)}}, LastWriteTime |
  Format-Table -AutoSize

