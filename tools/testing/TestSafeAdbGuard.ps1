<#
.SYNOPSIS
    Unit verification test for SafeAdbGuard.ps1.
    Proves that physical device 8096debc cannot be targeted, non-emulators are rejected,
    and missing emulators abort safely.
#>

$guardPath = Join-Path $PSScriptRoot "SafeAdbGuard.ps1"
. $guardPath

$passed = 0
$failed = 0

function Assert-Test([string]$testName, [bool]$condition) {
    if ($condition) {
        Write-Host "  [PASS] $testName" -ForegroundColor Green
        $script:passed++
    } else {
        Write-Host "  [FAIL] $testName" -ForegroundColor Red
        $script:failed++
    }
}

Write-Host "=== Running SafeAdbGuard Verification Suite ===" -ForegroundColor Cyan

# Test 1: Explicit target 8096debc must throw PHYSICAL_DEVICE_SAFETY_VIOLATION
$threwPhysical = $false
try {
    Assert-SafeEmulatorTarget -Serial "8096debc"
} catch {
    if ($_.Exception.Message -like "*PHYSICAL_DEVICE_SAFETY_VIOLATION*") {
        $threwPhysical = $true
    }
}
Assert-Test "Rejects physical phone serial 8096debc with PHYSICAL_DEVICE_SAFETY_VIOLATION" $threwPhysical

# Test 2: Non-emulator device name must throw INVALID_TARGET
$threwInvalid = $false
try {
    Assert-SafeEmulatorTarget -Serial "pixel_phone_xyz"
} catch {
    if ($_.Exception.Message -like "*INVALID_TARGET*") {
        $threwInvalid = $true
    }
}
Assert-Test "Rejects non-emulator target string with INVALID_TARGET" $threwInvalid

# Test 3: Non-existent emulator must throw EMULATOR_UNAVAILABLE
$threwUnavailable = $false
try {
    Assert-SafeEmulatorTarget -Serial "emulator-9999"
} catch {
    if ($_.Exception.Message -like "*EMULATOR_UNAVAILABLE*") {
        $threwUnavailable = $true
    }
}
Assert-Test "Rejects offline/non-existent emulator-9999 with EMULATOR_UNAVAILABLE" $threwUnavailable

# Test 4: Physical device 8096debc is never present in Get-OnlineEmulators
$onlineList = Get-OnlineEmulators
$hasPhysical = ($onlineList | Where-Object { $_.Serial -eq "8096debc" }).Count -gt 0
Assert-Test "Get-OnlineEmulators strictly excludes physical phone serial 8096debc" (-not $hasPhysical)

# Test 5: Resolve-SafeEmulatorTarget resolves verified online emulator
$resolvedSerial = ""
try {
    $resolvedSerial = Resolve-SafeEmulatorTarget
} catch {
    Write-Host "  Note: Resolve-SafeEmulatorTarget caught: $($_.Exception.Message)"
}
$resolvedIsEmulator = ($resolvedSerial -like "emulator-*")
Assert-Test "Resolve-SafeEmulatorTarget resolves only an emulator-* serial ($resolvedSerial)" $resolvedIsEmulator

# Test 6: Invoke-SafeEmulatorAdb with physical device must abort before executing
$threwInvokePhysical = $false
try {
    Invoke-SafeEmulatorAdb -EmulatorSerial "8096debc" "shell" "getprop"
} catch {
    if ($_.Exception.Message -like "*PHYSICAL_DEVICE_SAFETY_VIOLATION*") {
        $threwInvokePhysical = $true
    }
}
Assert-Test "Invoke-SafeEmulatorAdb prevents command dispatch to physical device 8096debc" $threwInvokePhysical

Write-Host "=== Suite Results: $passed Passed, $failed Failed ===" -ForegroundColor $(if ($failed -eq 0) { "Green" } else { "Red" })

if ($failed -gt 0) {
    exit 1
}
exit 0
