<#
.SYNOPSIS
    Safe ADB targeting guard for ROXUM-ACS emulator automation.
    Enforces absolute isolation of physical device 8096debc (Redmi Note 7)
    and guarantees commands ONLY execute on verified online emulators.
#>

param()

$script:KNOWN_PHYSICAL_SERIALS = @("8096debc")

function Get-AdbExecutable {
    $candidates = @(
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "$env:ANDROID_HOME\platform-tools\adb.exe",
        "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe",
        "C:\Users\dipak\AppData\Local\Android\Sdk\platform-tools\adb.exe"
    )

    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path $candidate)) {
            return $candidate
        }
    }

    $fromPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($fromPath) {
        return $fromPath.Source
    }

    throw "ADB_NOT_FOUND: Unable to locate adb.exe in standard Android SDK locations or PATH."
}

function Get-OnlineEmulators {
    $adb = Get-AdbExecutable
    $output = & $adb devices -l 2>&1
    
    $emulators = @()
    foreach ($line in $output) {
        $trimmed = $line.Trim()
        if ([string]::IsNullOrWhiteSpace($trimmed) -or $trimmed.StartsWith("List of devices") -or $trimmed.StartsWith("*")) {
            continue
        }
        
        $parts = $trimmed -split '\s+'
        if ($parts.Count -ge 2) {
            $serial = $parts[0]
            $status = $parts[1]
            
            # Absolute physical safety: reject known physical serials immediately
            if ($script:KNOWN_PHYSICAL_SERIALS -contains $serial) {
                continue
            }
            
            # Only consider emulators that are online and ready ("device")
            if ($serial -like "emulator-*" -and $status -eq "device") {
                $emulators += [PSCustomObject]@{
                    Serial = $serial
                    Status = $status
                    RawLine = $trimmed
                }
            }
        }
    }
    
    return $emulators
}

function Assert-SafeEmulatorTarget {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Serial
    )

    # Rule 1: Never allow physical Redmi Note 7
    if ($script:KNOWN_PHYSICAL_SERIALS -contains $Serial) {
        throw "PHYSICAL_DEVICE_SAFETY_VIOLATION: Target '$Serial' is a physical device. All operations are strictly forbidden."
    }

    # Rule 2: Target must follow emulator naming convention
    if ($Serial -notlike "emulator-*") {
        throw "INVALID_TARGET: Target '$Serial' is not an Android Virtual Device (must start with 'emulator-')."
    }

    # Rule 3: Verify the emulator is actually online
    $online = Get-OnlineEmulators
    $match = $online | Where-Object { $_.Serial -eq $Serial }
    if (-not $match) {
        throw "EMULATOR_UNAVAILABLE: Emulator '$Serial' is not currently online or ready (state != 'device')."
    }
}

function Resolve-SafeEmulatorTarget {
    param(
        [string]$ExplicitSerial = ""
    )

    if ($ExplicitSerial) {
        Assert-SafeEmulatorTarget -Serial $ExplicitSerial
        return $ExplicitSerial
    }

    $online = Get-OnlineEmulators
    if ($online.Count -eq 0) {
        throw "NO_EMULATOR_AVAILABLE: No online Android Virtual Device (emulator-*) is currently connected. Physical devices are strictly excluded."
    }

    if ($online.Count -gt 1) {
        $serials = ($online | ForEach-Object { $_.Serial }) -join ", "
        throw "AMBIGUOUS_EMULATOR_TARGET: Multiple emulators detected ($serials). Explicit -Serial parameter is required to avoid accidental targeting."
    }

    return $online[0].Serial
}

function Invoke-SafeEmulatorAdb {
    param(
        [Parameter(Mandatory = $true)]
        [string]$EmulatorSerial,
        
        [Parameter(Mandatory = $true, ValueFromRemainingArguments = $true)]
        [string[]]$AdbArgs
    )

    Assert-SafeEmulatorTarget -Serial $EmulatorSerial
    $adb = Get-AdbExecutable
    
    # Run with explicit -s targeting
    & $adb -s $EmulatorSerial @AdbArgs
}

if ($MyInvocation.MyCommand.ModuleName) {
    Export-ModuleMember -Function Get-AdbExecutable, Get-OnlineEmulators, Assert-SafeEmulatorTarget, Resolve-SafeEmulatorTarget, Invoke-SafeEmulatorAdb -ErrorAction SilentlyContinue
}
