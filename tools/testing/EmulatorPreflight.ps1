<#
.SYNOPSIS
    Preflight check for Android Virtual Device (AVD) and APK compatibility.
    Checks emulator status, ABI lists, and APK native architectures.
#>

[CmdletBinding()]
param(
    [string]$Serial = ""
)

$guardPath = Join-Path $PSScriptRoot "SafeAdbGuard.ps1"
. $guardPath

Write-Host "=== ROXUM-ACS Emulator Preflight Check ===" -ForegroundColor Cyan

# 1. Resolve safe target
$targetSerial = ""
try {
    $targetSerial = Resolve-SafeEmulatorTarget -ExplicitSerial $Serial
    Write-Host "[OK] Target verified as emulator: $targetSerial" -ForegroundColor Green
} catch {
    Write-Host "[ERROR] $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

$adb = Get-AdbExecutable

# 2. Query emulator properties
$release = (& $adb -s $targetSerial shell getprop ro.build.version.release).Trim()
$sdk = (& $adb -s $targetSerial shell getprop ro.build.version.sdk).Trim()
$primaryAbi = (& $adb -s $targetSerial shell getprop ro.product.cpu.abi).Trim()
$abilist = (& $adb -s $targetSerial shell getprop ro.product.cpu.abilist).Trim()

Write-Host "Emulator Info:"
Write-Host "  Android Version : $release (API $sdk)"
Write-Host "  Primary ABI     : $primaryAbi"
Write-Host "  Supported ABIs  : $abilist"

# 3. Inspect existing APKs
$apkDir = Join-Path $PSScriptRoot "..\..\core\app\build\outputs\apk\debug"
$arm64Apk = Join-Path $apkDir "android-code-studio-arm64-v8a-debug-1.0.0+gh.r04.apk"
$arm7Apk = Join-Path $apkDir "android-code-studio-armeabi-v7a-debug-1.0.0+gh.r04.apk"

Write-Host "`nAPK Native Compatibility Check:"
$supportedAbiList = $abilist -split ','

$arm64Compatible = $supportedAbiList -contains "arm64-v8a"
$arm7Compatible = ($supportedAbiList -contains "armeabi-v7a") -or ($supportedAbiList -contains "armeabi")

Write-Host "  arm64-v8a APK : $(if (Test-Path $arm64Apk) { "Present" } else { "Missing" }) | Compatible with emulator: $(if ($arm64Compatible) { "YES" } else { "NO" })"
Write-Host "  armeabi-v7a APK: $(if (Test-Path $arm7Apk) { "Present" } else { "Missing" }) | Compatible with emulator: $(if ($arm7Compatible) { "YES" } else { "NO" })"

if (-not $arm64Compatible -and -not $arm7Compatible) {
    Write-Host "`n[COMPATIBILITY BLOCKER]" -ForegroundColor Yellow
    Write-Host "  The target emulator '$targetSerial' reports supported ABIs: [$abilist]."
    Write-Host "  The ROXUM-ACS debug APKs are built for ARM architectures (arm64-v8a, armeabi-v7a)."
    Write-Host "  32-bit x86 emulator images do not support ARM binary translation."
    Write-Host "  Attempting installation will fail with INSTALL_FAILED_NO_MATCHING_ABIS (res=-113)."
    Write-Host "  Recommended Action: Use an Android 11+ (API 30+) x86_64 emulator image which includes libndk_translation."
    exit 2
}

Write-Host "`n[SUCCESS] Emulator is compatible with at least one available APK variant." -ForegroundColor Green
exit 0
