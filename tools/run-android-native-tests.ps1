param(
    [Parameter(Mandatory = $true)][string]$TestBinary,
    [Parameter(Mandatory = $true)][string]$Serial,
    [string[]]$TestArguments = @('--test-threads=1'),
    [string]$NdkPath = $env:ANDROID_NDK_HOME,
    [string]$AdbPath,
    [switch]$PlatformTeardown
)
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $TestBinary -PathType Leaf)) { throw "Missing test binary: $TestBinary" }
if (-not $AdbPath) {
    $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
    if ($adbCommand) { $AdbPath = $adbCommand.Source }
    elseif ($env:ANDROID_HOME) { $AdbPath = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe' }
    else { throw 'Set ANDROID_HOME or pass -AdbPath.' }
}
function Invoke-Adb([string[]]$AdbArguments) {
    & $AdbPath -s $Serial @AdbArguments
    if ($LASTEXITCODE -ne 0) { throw "adb failed ($LASTEXITCODE): $AdbArguments" }
}
$abi = & $AdbPath -s $Serial shell getprop ro.product.cpu.abi
if ($LASTEXITCODE -ne 0 -or $abi.Trim() -ne 'arm64-v8a') { throw 'This runner requires a connected arm64-v8a Android device.' }
# Test arguments cross adb's remote shell; admit libtest filters/options only.
foreach ($argument in $TestArguments) {
    if ($argument -notmatch '^[A-Za-z0-9_:.,=+/-]+$') { throw "Unsupported test argument: $argument" }
}

$token = [Guid]::NewGuid().ToString('N')
$localDirectory = Join-Path ([IO.Path]::GetTempPath()) "hypercar-native-tests-$token"
$remoteDirectory = "/data/local/tmp/hypercar-native-tests-$token"
$library = Join-Path $localDirectory 'libtest-exit.so'
$testExit = 1
$remoteCreated = $false
try {
    if (-not $PlatformTeardown) {
        if (-not $NdkPath) { throw 'Set ANDROID_NDK_HOME or pass -NdkPath.' }
        $clang = Join-Path $NdkPath 'toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android28-clang.cmd'
        if (-not (Test-Path -LiteralPath $clang)) { throw 'Set ANDROID_NDK_HOME or pass -NdkPath.' }
        New-Item -ItemType Directory -Path $localDirectory | Out-Null
        & $clang (Join-Path $PSScriptRoot 'android-native-test-exit.c') -shared -fPIC '-Wl,-z,max-page-size=16384' -o $library
        if ($LASTEXITCODE -ne 0) { throw "Exit adapter build failed: $LASTEXITCODE" }
    }
    Invoke-Adb -AdbArguments @('shell', 'mkdir', $remoteDirectory)
    $remoteCreated = $true
    Invoke-Adb -AdbArguments @('push', $TestBinary, "$remoteDirectory/tests")
    Invoke-Adb -AdbArguments @('shell', 'chmod', '755', "$remoteDirectory/tests")
    $command = "$remoteDirectory/tests"
    if (-not $PlatformTeardown) {
        Invoke-Adb -AdbArguments @('push', $library, "$remoteDirectory/libtest-exit.so")
        $command = "LD_PRELOAD=$remoteDirectory/libtest-exit.so $command"
    }
    & $AdbPath -s $Serial shell "$command $($TestArguments -join ' ')"
    $testExit = $LASTEXITCODE
} finally {
    if ($remoteCreated) {
        & $AdbPath -s $Serial shell rm -f "$remoteDirectory/tests" "$remoteDirectory/libtest-exit.so" | Out-Null
        & $AdbPath -s $Serial shell rmdir $remoteDirectory | Out-Null
    }
    # Only the two explicitly created paths; no recursive filesystem deletion.
    if (Test-Path -LiteralPath $library) { Remove-Item -LiteralPath $library }
    if (Test-Path -LiteralPath $localDirectory) { Remove-Item -LiteralPath $localDirectory }
}
exit $testExit
