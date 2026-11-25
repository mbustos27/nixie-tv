# SPDX-FileCopyrightText: 2026 Nixie contributors
# SPDX-License-Identifier: GPL-3.0-or-later
<#
.SYNOPSIS
  Pulls the dev build's anonymised log from the TV into a file on this PC, with no steps on the TV.

.DESCRIPTION
  Connects to the box over Wireless debugging, asks Nixie for TV's dev build to write the report Download log serves
  (the device summary, then the log with server addresses and names, user names and tokens replaced) into its own
  storage, copies that file here through adb's content command, deletes it on the box and disconnects. The app does
  nothing by itself: only adb's shell can send the request and read the file (the receiver and the provider need
  DUMP), and only the dev (debug) build and the release-like staging build have them.

  Works on the dev build and on the staging build (built with -Pnixie.stagingAsDebug=true it has the dev build's id;
  otherwise give -Package its own); on a release build it says so. Prints the file's path, size and line count, never
  the log. See docs/RELEASING.md, "Pulling the log from a box".

.PARAMETER Device
  The box over Wireless debugging as host:port, or only its new port (the host from -BoxFile), or a serial adb
  already lists, such as emulator-5554. Default: the address in -BoxFile.

.PARAMETER BoxFile
  Where the box's address is kept, outside every repository: one line, host:port. Default:
  ~\Dev\nixie-tv-wt\_box.txt.

.PARAMETER Out
  The folder for the log; never inside a repository. Default: _owner-logs beside the worktrees,
  ~\Dev\nixie-tv-wt\_owner-logs.

.PARAMETER Package
  The dev build's application id.

.PARAMETER TimeoutSeconds
  How long to wait for the app to write the log.

.EXAMPLE
  .\tools\pull-tv-log.ps1
  The box at the address in _box.txt.

.EXAMPLE
  .\tools\pull-tv-log.ps1 -Device <new port>
  Wireless debugging's port changed: the new one read off the box's Wireless debugging screen (then put it in
  _box.txt).

.EXAMPLE
  .\tools\pull-tv-log.ps1 -Device emulator-5554 -Out $env:TEMP\tv-logs
#>
[CmdletBinding()]
param(
    [string]$Device,
    [string]$BoxFile = (Join-Path $HOME 'Dev\nixie-tv-wt\_box.txt'),
    [string]$Out = (Join-Path $HOME 'Dev\nixie-tv-wt\_owner-logs'),
    [string]$Package = 'io.github.mbustos27.nixie.tv.debug',
    [ValidateRange(5, 600)] [int]$TimeoutSeconds = 60
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2

$Action = 'io.github.mbustos27.nixie.tv.debug.WRITE_LOG'
# The file under the app's own storage (AdbLogReceiver.PATH; written as a .part first, then renamed), as AdbLogProvider
# hands it to adb's content command: run-as would read it only on a debuggable build, and staging is not one.
$LogUri = "content://$Package.adblog/log"

# adb's content command prints a provider's failure (an unknown authority, an exception) instead of failing.
function Test-ContentFailed($Result) {
    $Result.Code -ne 0 -or "$($Result.Out) $($Result.Err)" -match '(?i)exception|unknown (authority|uri)|could not find provider|error while'
}

function Find-Adb {
    $onPath = Get-Command adb -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($onPath) { return $onPath.Source }
    foreach ($sdk in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))) {
        if (-not $sdk) { continue }
        $candidate = Join-Path $sdk 'platform-tools\adb.exe'
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    throw 'adb was not found: install the Android SDK platform-tools or put adb on PATH.'
}

# Runs adb without PowerShell touching its output: { Code, Out, Err }. With -ToFile, stdout goes byte for byte into
# that file. A call that outlasts -Seconds is ended (only the adb client this script started).
function Invoke-Adb([string[]]$Arguments, [string]$ToFile, [int]$Seconds = 30) {
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $script:Adb
    $info.Arguments = ($Arguments | ForEach-Object { if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ } }) -join ' '
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::Start($info)
    $stream = $null
    try {
        $errors = $process.StandardError.ReadToEndAsync()
        if ($ToFile) {
            $stream = [System.IO.File]::Create($ToFile)
            $output = $process.StandardOutput.BaseStream.CopyToAsync($stream)
        } else {
            $output = $process.StandardOutput.ReadToEndAsync()
        }
        if (-not $process.WaitForExit($Seconds * 1000)) {
            $process.Kill()
            throw "adb $($Arguments -join ' ') took longer than $Seconds s."
        }
        $output.Wait()
        $text = if ($ToFile) { '' } else { $output.Result }
        [pscustomobject]@{ Code = $process.ExitCode; Out = "$text".Trim(); Err = "$($errors.Result)".Trim() }
    } finally {
        if ($stream) { $stream.Dispose() }
        $process.Dispose()
    }
}

# Walks up from $Path: true when a folder on the way is a git repository or worktree.
function Test-InRepository([string]$Path) {
    for ($dir = $Path; $dir; $dir = Split-Path -Path $dir -Parent) {
        if (Test-Path -LiteralPath (Join-Path $dir '.git')) { return $true }
    }
    $false
}

# The box's address in $Path: its first line that is not blank, host:port; $null when there is none.
function Read-BoxAddress([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $null }
    $line = @(Get-Content -LiteralPath $Path | ForEach-Object { ($_ -replace '^﻿', '').Trim() } | Where-Object { $_ }) |
        Select-Object -First 1
    if ("$line" -match '^[^:\s]+:\d{1,5}$') { $line } else { $null }
}

$exitCode = 0
$network = $false
$serial = $Device
$cleanUp = $false
try {
    $script:Adb = Find-Adb
    # The box's address is never in the repository: it comes from -BoxFile, or from -Device.
    $box = Read-BoxAddress $BoxFile
    if (-not $Device) {
        if (-not $box) { throw "No address for the box: put it in $BoxFile, one line, host:port, or give -Device." }
        $serial = $box
    } elseif ($Device -match '^\d{1,5}$') {
        if (-not $box) { throw "-Device $Device is only a port; its host comes from $BoxFile, which has no address. Put it there, one line, host:port." }
        $serial = ($box -replace ':\d+$', '') + ":$Device"
    }
    $network = $serial -match '^[^:]+:\d{1,5}$'

    $outDir = [System.IO.Path]::GetFullPath($Out)
    if (Test-InRepository $outDir) {
        throw "-Out $outDir is inside a repository; the log stays out of every repository. Give a folder outside one."
    }

    if ($network) {
        $connect = Invoke-Adb @('connect', $serial)
        # adb connect says how it went on stdout and exits 0 either way.
        if ("$($connect.Out)" -notmatch '(?m)^(connected to|already connected to) ') {
            $why = "$($connect.Out) $($connect.Err)".Trim()
            if ($why -match 'authenticate') {
                throw "$serial refused this PC's debugging key ($why). The box has to allow this PC once."
            }
            throw ("Could not connect to $serial ($why). Wireless debugging's port has changed: Android picks a new one " +
                "when the box restarts or Wireless debugging is turned off and on. Read the new port off the box's " +
                "Wireless debugging screen, " +
                "run again with -Device <port>, and put the new address in $BoxFile.")
        }
    }
    $state = Invoke-Adb @('-s', $serial, 'get-state')
    # The last line: a starting adb server may print its own lines first.
    if (($state.Out -split '\r?\n')[-1].Trim() -ne 'device') {
        $why = "$($state.Out) $($state.Err)".Trim()
        if ($network) { throw "$serial is connected but not ready ($why). Run again; if it stays so, the box has to allow this PC." }
        throw "$serial is not attached ($why). 'adb devices' lists what is."
    }

    $installed = Invoke-Adb @('-s', $serial, 'shell', 'pm', 'path', $Package)
    if ($installed.Out -notmatch '(?m)^package:') {
        $release = $Package -replace '\.debug$', ''
        $other = if ($release -ne $Package) { Invoke-Adb @('-s', $serial, 'shell', 'pm', 'path', $release) } else { $null }
        if ($other -and $other.Out -match '(?m)^package:') {
            throw ("Only the release build ($release) is on $serial. The log comes this way only from the dev or " +
                "staging build; on the release build it is Settings > System > Diagnostics > Download log.")
        }
        throw "Nixie for TV's dev build ($Package) is not installed on $serial."
    }

    # This also clears a file an earlier run left. A build from before the provider (tv-integration-1) has none.
    $clear = Invoke-Adb @('-s', $serial, 'shell', 'content', 'delete', '--uri', $LogUri)
    if (Test-ContentFailed $clear) {
        $why = "$($clear.Out) $($clear.Err)".Trim()
        throw "$Package on $serial does not hand out its log ($why): install a dev or staging build from tv-integration-1 on."
    }
    $cleanUp = $true

    # -f 32: FLAG_INCLUDE_STOPPED_PACKAGES, so it reaches an app that has not run since it was installed or stopped.
    $sent = Invoke-Adb @('-s', $serial, 'shell', 'am', 'broadcast', '-a', $Action, '-p', $Package, '-f', '32') -Seconds ($TimeoutSeconds + 10)
    if ("$($sent.Out)" -notmatch 'Broadcast completed') {
        throw "The request did not reach $Package on $serial ($("$($sent.Out) $($sent.Err)".Trim()))."
    }

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $size = -1L
    while ($true) {
        # "Row: 0 _display_name=nixie-tv-log.txt, _size=<bytes>" once the file is there; "No result found." before.
        $stat = Invoke-Adb @('-s', $serial, 'shell', 'content', 'query', '--uri', $LogUri)
        if (-not (Test-ContentFailed $stat) -and $stat.Out -match '_size=(\d+)') { $size = [long]$Matches[1]; break }
        if ((Get-Date) -ge $deadline) { break }
        Start-Sleep -Milliseconds 500
    }
    if ($size -lt 0) {
        throw ("No log came within $TimeoutSeconds s. The dev build on $serial may predate the adb log receiver " +
            "(tv-log-pull): install a newer dev build, or look in logcat for the AdbLog tag.")
    }

    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    $stamp = Get-Date -Format 'yyyyMMdd-HHmm'
    $local = Join-Path $outDir "nixie-tv-log-$stamp.txt"
    for ($n = 2; Test-Path -LiteralPath $local; $n++) { $local = Join-Path $outDir "nixie-tv-log-$stamp-$n.txt" }
    $copy = Invoke-Adb @('-s', $serial, 'exec-out', 'content', 'read', '--uri', $LogUri) -ToFile $local -Seconds 120
    $got = (Get-Item -LiteralPath $local).Length
    if ($copy.Code -ne 0 -or $got -ne $size) {
        Remove-Item -LiteralPath $local -Force
        throw "The copy came short: $got of $size bytes ($($copy.Err)). Run again."
    }

    $lines = [System.IO.File]::ReadAllLines($local).Count
    $kb = [math]::Round($got / 1KB, 1)
    Write-Output "Log pulled from ${serial}: $local"
    Write-Output "$got bytes ($kb KB), $lines lines"
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    $exitCode = 1
} finally {
    if ($cleanUp) {
        $removed = Invoke-Adb @('-s', $serial, 'shell', 'content', 'delete', '--uri', $LogUri)
        if (Test-ContentFailed $removed) {
            [Console]::Error.WriteLine("The log could not be removed from ${serial}: $LogUri ($($removed.Out) $($removed.Err))")
            $exitCode = 1
        }
    }
    # Also after a failed connect, which can leave the address listed as offline.
    if ($network -and $script:Adb) { Invoke-Adb @('disconnect', $serial) | Out-Null }
}
exit $exitCode
