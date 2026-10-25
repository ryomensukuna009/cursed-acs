param(
    [string]$Serial = "",
    [string]$OutputDir = ""
)

$guardPath = Join-Path $PSScriptRoot "SafeAdbGuard.ps1"
. $guardPath

if (-not $OutputDir) {
    $OutputDir = Join-Path $PSScriptRoot "..\..\build\reports\emulator_smoke"
}
if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
}

$reportFile = Join-Path $OutputDir "EMULATOR_SMOKE_TEST_REPORT.json"

Write-Host "======================================================" -ForegroundColor Cyan
Write-Host " ROXUM-ACS - EMULATOR AUTOMATION SMOKE TEST RUNNER   " -ForegroundColor Cyan
Write-Host "======================================================" -ForegroundColor Cyan

$targetSerial = ""
try {
    $targetSerial = Resolve-SafeEmulatorTarget -ExplicitSerial $Serial
    Write-Host "[OK] Target verified as emulator: $targetSerial" -ForegroundColor Green
} catch {
    Write-Host "[BLOCKED] $($_.Exception.Message)" -ForegroundColor Red
    $result = [PSCustomObject]@{
        status = "BLOCKED"
        reason = $_.Exception.Message
        timestamp = (Get-Date).ToString("o")
    }
    $result | ConvertTo-Json -Depth 4 | Set-Content $reportFile
    exit 1
}

$preflight = Join-Path $PSScriptRoot "EmulatorPreflight.ps1"
$preflightOutput = & powershell -File $preflight -Serial $targetSerial
$preflightExit = $LASTEXITCODE

if ($preflightExit -ne 0) {
    Write-Host "[BLOCKED] Emulator $targetSerial is ABI-incompatible with current debug APKs." -ForegroundColor Yellow
    $result = [PSCustomObject]@{
        status = "BLOCKED"
        target = $targetSerial
        reason = "INSTALL_FAILED_NO_MATCHING_ABIS: Target emulator lacks ARM binary translation."
        preflightLog = ($preflightOutput -join "`n")
        timestamp = (Get-Date).ToString("o")
    }
    $result | ConvertTo-Json -Depth 4 | Set-Content $reportFile
    Write-Host "Report saved to: $reportFile"
    exit 2
}

Write-Host "[SUCCESS] Preflight passed. Ready for automated test scenarios on $targetSerial." -ForegroundColor Green
exit 0
