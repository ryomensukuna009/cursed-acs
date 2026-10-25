<#
.SYNOPSIS
    Installs and launches ROXUM-ACS on a verified online Android Virtual Device.
    Enforces SafeAdbGuard to prevent any commands from touching physical devices.
#>

[CmdletBinding()]
param(
    [string]$Serial = "",
    [ValidateSet("arm64-v8a", "armeabi-v7a")]
    [string]$AbiVariant = "arm64-v8a"
)

$guardPath = Join-Path $PSScriptRoot "SafeAdbGuard.ps1"
. $guardPath

$targetSerial = Resolve-SafeEmulatorTarget -ExplicitSerial $Serial
Write-Host "Target emulator: $targetSerial" -ForegroundColor Cyan

# Preflight check
$preflightScript = Join-Path $PSScriptRoot "EmulatorPreflight.ps1"
& powershell -File $preflightScript -Serial $targetSerial
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ABORT] Preflight check failed or emulator is ABI-incompatible." -ForegroundColor Red
    exit $LASTEXITCODE
}

$apkDir = Join-Path $PSScriptRoot "..\..\core\app\build\outputs\apk\debug"
$apkName = if ($AbiVariant -eq "arm64-v8a") {
    "android-code-studio-arm64-v8a-debug-1.0.0+gh.r04.apk"
} else {
    "android-code-studio-armeabi-v7a-debug-1.0.0+gh.r04.apk"
}
$apkPath = Join-Path $apkDir $apkName

if (-not (Test-Path $apkPath)) {
    Write-Host "[ERROR] APK file not found at: $apkPath" -ForegroundColor Red
    exit 1
}

Write-Host "Installing $apkName on $targetSerial..."
Invoke-SafeEmulatorAdb -EmulatorSerial $targetSerial "install" "-r" $apkPath

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] Installation failed on emulator $targetSerial." -ForegroundColor Red
    exit 1
}

Write-Host "Launching SplashActivity on $targetSerial..."
Invoke-SafeEmulatorAdb -EmulatorSerial $targetSerial "shell" "am" "start" "-n" "com.tom.rv2ide/.activities.SplashActivity"

Write-Host "[SUCCESS] Application launched on $targetSerial." -ForegroundColor Green
exit 0
