<#
.SYNOPSIS
    Collects structured logs and logcat artifacts from a verified online emulator.
    Enforces SafeAdbGuard to prevent any commands from touching physical devices.
#>

[CmdletBinding()]
param(
    [string]$Serial = "",
    [string]$OutputDir = "",
    [int]$Lines = 500
)

$guardPath = Join-Path $PSScriptRoot "SafeAdbGuard.ps1"
. $guardPath

$targetSerial = Resolve-SafeEmulatorTarget -ExplicitSerial $Serial

if (-not $OutputDir) {
    $OutputDir = Join-Path $PSScriptRoot "..\..\build\reports\emulator_logs"
}

if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
}

$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$logFile = Join-Path $OutputDir "logcat_${targetSerial}_${timestamp}.txt"

Write-Host "Collecting last $Lines lines of logcat from $targetSerial..." -ForegroundColor Cyan

$rawLogs = Invoke-SafeEmulatorAdb -EmulatorSerial $targetSerial "logcat" "-d" "-t" "$Lines"
$rawLogs | Set-Content -Path $logFile -Encoding UTF8

Write-Host "[SUCCESS] Logs written to: $logFile" -ForegroundColor Green
exit 0
