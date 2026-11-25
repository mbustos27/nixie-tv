# SPDX-FileCopyrightText: 2026 Nixie contributors
# SPDX-License-Identifier: GPL-3.0-or-later
<#
  Dot-sourced by Run-Gate.ps1 and Start-GateEmulator.ps1: the gate's emulator and its two kinds of slot.

  - The emulator itself takes a slot of the TV gates' own pool (Local\NixieTvGateEmulator, NIXIE_TV_EMULATORS slots,
    2 by default: one emulator per TV gate stream, about 3 GB of RAM each on this 31 GB PC beside the desktop
    harness's runs), for its whole life. It holds no desktop harness slot while it waits between runs.
  - A check run (Run-Gate.ps1, one theme's checks) takes one of the desktop harness's slots (fluentfin-mpv
    tools\ui-harness\offscreen\prepare.ps1, read only, Enter-HarnessRunSlot / Exit-HarnessRunSlot) only while the
    checks drive the emulator, and gives it back at once after them, on an error, or (abandoned) when it dies.
  Both are named mutexes, taken and given back on the same thread. AVD Nixie_TV_API34 read-only (-read-only: nothing
  the gate installs or changes stays in the AVD, and the other stream's emulator of the same AVD runs beside it),
  windowless and silent. Only the emulator this process started is closed.
#>
$GateSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$GateAdb = Join-Path $GateSdk 'platform-tools\adb.exe'
# The desktop app's harness (its slot lock), found as a fluentfin-mpv checkout beside a folder above this one, or set
# with NIXIE_HARNESS_PREPARE; no machine's own path is written here.
$GatePrepare = $env:NIXIE_HARNESS_PREPARE
if (-not $GatePrepare) {
    $dir = Split-Path -Parent $PSScriptRoot
    while ($dir -and -not $GatePrepare) {
        $candidate = Join-Path (Split-Path -Parent $dir) 'fluentfin-mpv\tools\ui-harness\offscreen\prepare.ps1'
        if (Test-Path $candidate) { $GatePrepare = $candidate }
        $up = Split-Path -Parent $dir
        $dir = if ($up -and $up -ne $dir) { $up } else { $null }
    }
    if (-not $GatePrepare) { $GatePrepare = 'fluentfin-mpv\tools\ui-harness\offscreen\prepare.ps1 (not found; set NIXIE_HARNESS_PREPARE)' }
}

# The harness's slot functions, read only, at this file's scope (dot-sourced inside a function they would end with it,
# and Stop-GateEmulator could not give the slot back).
if (Test-Path $GatePrepare) { . $GatePrepare }

# adb with its error lines dropped: Windows PowerShell turns a native program's stderr into errors that stop a script.
function Invoke-GateAdb {
    $ErrorActionPreference = 'Continue'
    & $GateAdb @args 2>$null
}

$GateEmulatorPool = 'Local\NixieTvGateEmulator'

# how many TV gate emulators at once: NIXIE_TV_EMULATORS (1 to 4), else 2
function Get-GateEmulatorSlots([string]$Value = $env:NIXIE_TV_EMULATORS) {
    if (-not "$Value".Trim()) { return 2 }
    $n = 0
    if (-not [int]::TryParse("$Value".Trim(), [ref]$n) -or $n -lt 1 -or $n -gt 4) { throw "NIXIE_TV_EMULATORS=${Value}: give 1 to 4" }
    $n
}

# A desktop harness slot for one check run, waiting up to $WaitMinutes: the lock, or throws. Give it back with
# Exit-HarnessRunSlot as soon as the checks are through.
function Enter-GateRunSlot([int]$WaitMinutes = 60, [scriptblock]$Say = { param($t) Write-Host $t }) {
    if (-not (Get-Command Enter-HarnessRunSlot -ErrorAction SilentlyContinue)) { throw "the harness's prepare.ps1 is not at $GatePrepare (NIXIE_HARNESS_PREPARE names another)" }
    & $Say 'waiting for a desktop harness slot for the checks'
    $lock = Enter-HarnessRunSlot -Slots (Get-HarnessRunSlots) -Wait ([TimeSpan]::FromMinutes($WaitMinutes))
    if (-not $lock) { throw "no desktop harness slot came free in $WaitMinutes min" }
    & $Say "harness slot $($lock.Slot) taken for the checks"
    $lock
}

# Starts the emulator holding a slot of the TV gates' emulator pool (no desktop harness slot): { Serial, Lock,
# Process } or throws. $Say gets one line per step.
function Start-GateEmulator([int]$Port = 5584, [int]$WaitMinutes = 45, [scriptblock]$Say = { param($t) Write-Host $t },
    [string]$Log = (Join-Path $env:TEMP "nixie-gate-emulator-$Port.log")) {
    if (-not (Get-Command Enter-HarnessRunSlot -ErrorAction SilentlyContinue)) { throw "the harness's prepare.ps1 is not at $GatePrepare (NIXIE_HARNESS_PREPARE names another)" }

    $serial = "emulator-$Port"
    if ((Invoke-GateAdb devices) -match "^$serial\s") { throw "$serial is taken by another emulator: pass another -Port" }
    # The emulator runs read-only instances of an AVD only beside other read-only ones: wait out a writable one.
    $deadline = (Get-Date).AddMinutes($WaitMinutes)
    $writable = { @(Get-CimInstance Win32_Process -Filter "Name like 'qemu-system%'" | Where-Object { $_.CommandLine -match 'Nixie_TV_API34' -and $_.CommandLine -notmatch '-read-only' }) }
    if ((& $writable).Count) { & $Say 'waiting for another stream''s (writable) Nixie_TV_API34 emulator to close' }
    while ((& $writable).Count -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 15 }
    if ((& $writable).Count) { throw "a writable Nixie_TV_API34 emulator still runs after $WaitMinutes min" }
    & $Say 'waiting for a TV gate emulator slot'
    $lock = Enter-HarnessRunSlot -Slots (Get-GateEmulatorSlots) -Wait ([TimeSpan]::FromMinutes($WaitMinutes)) -Name $GateEmulatorPool
    if (-not $lock) { throw "no TV gate emulator slot came free in $WaitMinutes min" }
    $emulator = $null
    try {
        & $Say "emulator slot $($lock.Slot) taken; starting Nixie_TV_API34 on $serial"
        $emulator = Start-Process -FilePath (Join-Path $GateSdk 'emulator\emulator.exe') -PassThru -WindowStyle Hidden -ArgumentList @(
            '-avd', 'Nixie_TV_API34', '-port', "$Port", '-read-only', '-no-audio', '-no-window', '-no-snapshot-save', '-no-boot-anim'
        ) -RedirectStandardOutput $Log -RedirectStandardError "$Log.err"
        $booted = $false
        for ($i = 0; $i -lt 120 -and -not $booted; $i++) {
            if ($emulator.HasExited) { throw "the emulator exited ($($emulator.ExitCode)): $((Get-Content $Log -Tail 3) -join ' / ')" }
            $booted = ((Invoke-GateAdb -s $serial shell getprop sys.boot_completed) -join '').Trim() -eq '1'
            if (-not $booted) { Start-Sleep -Seconds 3 }
        }
        if (-not $booted) { throw 'the emulator did not boot in 6 min' }
        # No animations while the gate runs: the checks read what is shown, not how it moves there (read-only AVD:
        # nothing of this stays).
        foreach ($s in 'window_animation_scale', 'transition_animation_scale', 'animator_duration_scale') {
            Invoke-GateAdb -s $serial shell settings put global $s 0 | Out-Null
        }
        Invoke-GateAdb -s $serial shell input keyevent KEYCODE_WAKEUP | Out-Null
        [pscustomobject]@{ Serial = $serial; Lock = $lock; Process = $emulator }
    } catch {
        Stop-GateEmulator ([pscustomobject]@{ Serial = $serial; Lock = $lock; Process = $emulator })
        throw
    }
}

# Closes the emulator this process started (emulator.exe and its own qemu child), then gives its emulator slot back.
function Stop-GateEmulator($Emulator) {
    if (-not $Emulator) { return }
    try {
        if ($Emulator.Process) {
            $children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId = $($Emulator.Process.Id)" -ErrorAction SilentlyContinue)
            Invoke-GateAdb -s $Emulator.Serial emu kill | Out-Null
            for ($i = 0; $i -lt 20 -and -not $Emulator.Process.HasExited; $i++) { Start-Sleep -Seconds 1 }
            if (-not $Emulator.Process.HasExited) { Stop-Process -Id $Emulator.Process.Id -Force -ErrorAction SilentlyContinue }
            foreach ($c in $children) {
                $p = Get-Process -Id $c.ProcessId -ErrorAction SilentlyContinue
                if ($p -and $p.StartTime -ge $Emulator.Process.StartTime) { Stop-Process -Id $c.ProcessId -Force -ErrorAction SilentlyContinue }
            }
        }
    } finally {
        if ($Emulator.Lock) { Exit-HarnessRunSlot $Emulator.Lock }
    }
}
