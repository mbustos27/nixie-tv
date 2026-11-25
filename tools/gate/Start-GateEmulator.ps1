# SPDX-FileCopyrightText: 2026 Nixie contributors
# SPDX-License-Identifier: GPL-3.0-or-later
<#
.SYNOPSIS
  Keeps the gate's emulator up between check runs: Run-Gate.ps1 -Serial uses it.

.DESCRIPTION
  Starts the emulator as GateEmulator.ps1 does, in a slot of the TV gates' own emulator pool (no desktop harness
  slot: each Run-Gate.ps1 call takes one only while its checks run), and keeps it until <Dir>\release.flag appears or
  -MaxMinutes pass, then closes it and gives its emulator slot back. <Dir>\status.txt says where it is ("ready emulator-5584 slot 2 ..." once usable).
  Run it in the background; never leave it running: write release.flag when done.
#>
param(
    [Parameter(Mandatory)] [string]$Dir,
    [int]$MaxMinutes = 90,
    [int]$Port = 5584
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'GateEmulator.ps1')
New-Item -ItemType Directory -Force $Dir | Out-Null
$status = Join-Path $Dir 'status.txt'
$flag = Join-Path $Dir 'release.flag'
Remove-Item $flag -ErrorAction SilentlyContinue
function Set-Status([string]$Text) { Set-Content -Path $status -Value ("{0:HH:mm:ss} {1}" -f (Get-Date), $Text) -Encoding utf8 }
$emulator = $null
try {
    $emulator = Start-GateEmulator -Port $Port -Say { param($t) Set-Status $t }
    Set-Status "ready $($emulator.Serial) emulator slot $($emulator.Lock.Slot) pid $($emulator.Process.Id)"
    $end = (Get-Date).AddMinutes($MaxMinutes)
    while (-not (Test-Path $flag) -and (Get-Date) -lt $end -and -not $emulator.Process.HasExited) { Start-Sleep -Seconds 5 }
    if ($emulator.Process.HasExited) { Set-Status "the emulator stopped by itself (exit $($emulator.Process.ExitCode)); slot released" }
    Set-Status 'closing the emulator'
} catch {
    Set-Status "gave up: $($_.Exception.Message)"
    throw
} finally {
    Stop-GateEmulator $emulator
    if ($emulator) { Set-Status 'done: emulator closed, emulator slot released' }
}
