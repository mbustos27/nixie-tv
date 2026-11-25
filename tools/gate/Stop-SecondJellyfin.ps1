# SPDX-FileCopyrightText: 2026 Nixie contributors
# SPDX-License-Identifier: GPL-3.0-or-later
# Stops the gate's second server: only the process Start-SecondJellyfin.ps1 recorded (pid and start time).
param([string]$Root = 'F:\Dev\nixie-gate-jellyfin2')
$state = Join-Path $Root 'run\state.json'
if (-not (Test-Path $state)) { 'second server: nothing recorded'; return }
$s = Get-Content $state -Raw | ConvertFrom-Json
$p = Get-Process -Id $s.pid -ErrorAction SilentlyContinue
if ($p -and $p.StartTime.ToFileTimeUtc() -eq [long]$s.started) {
    Stop-Process -Id $p.Id -Force
    'second server stopped'
} else {
    'second server was not running'
}
Remove-Item $state -ErrorAction SilentlyContinue
