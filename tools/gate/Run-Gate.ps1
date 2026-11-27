# SPDX-FileCopyrightText: 2026 Nixie contributors
# SPDX-License-Identifier: GPL-3.0-or-later
<#
.SYNOPSIS
  The basics gate (docs/DESIGN-RULES.md, rule 12): every basic path, on the emulator with the remote, against the
  throwaway Jellyfins. Exit 0 when every check passed, 1 when one failed (or none ran), 2 when the gate could not run.

.DESCRIPTION
  1. Build (unless -Apk or -SkipBuild): the release-like staging APK with the debug build's id
     (:app:assembleNixieStaging -Pnixie.stagingAsDebug=true) and the gate's test APK (:gate:assembleDebugAndroidTest).
  2. Servers: the main throwaway Jellyfin (F:\Dev\nixie-real-jellyfin, 8496) and the gate's second one
     (Start-SecondJellyfin.ps1, 8506), started if they do not answer; stopped again at the end only if this run
     started them (unless -KeepServers). For RefusedTranscodeGate also the TV's fake Jellyfin
     (testing\fakeserver\tvserver.py, -FakePort 8516) where the test user may not transcode, its link test at
     -FakeTestKbps; its test video is made once with mpv into -FakeMediaRoot. Always this run's own: shut down at the end.
  3. The emulator: its own (a slot of the TV gates' emulator pool, GateEmulator.ps1, read-only AVD), or -Serial for one
     already up. A desktop harness slot is held only while a theme's checks run, and given back after each theme.
     An emulator that crashes (qemu itself: 0xc0000005 in code of no module, likely its SwiftShader GPU's, in
     Windows' Application log) is started again, up to -MaxEmulatorCrashes times: the checks that finished count,
     the rest of the theme runs on the new one, and a check the emulator crashes in twice fails. Its log is kept
     (emulator-<theme>-crash<n>.log).
  4. The fixture file (servers as the emulator reaches them, the test users and their passwords from the servers'
     credential files) pushed to /data/local/tmp/nixie-gate/ for the checks, removed at the end; never printed.
  5. Installs the APK under test and the gate APK, then runs every check once per theme (-Themes): every class in
     a file named *Gate.kt under gate/src/androidTest/java (the pattern; -Only narrows it by a regex on the class
     name, -Check names Class#method checks).
  6. Report: one line per check (PASS or FAIL with its one-line reason), into the console and <Out>\gate-report.txt;
     a failed check's screenshot and window tree under <Out>\<theme>\.

.EXAMPLE
  .\tools\gate\Run-Gate.ps1                                   # build, then every check in every theme
.EXAMPLE
  .\tools\gate\Run-Gate.ps1 -Apk ..\_apk\nixie-tv-10101-staging-as-debug-48e4f38b.apk -SkipBuild -Only RowsGate -Themes DriveIn
#>
param(
    [string]$Apk,
    [switch]$SkipBuild,
    [string]$Only,
    [string[]]$Check = @(),
    [string[]]$Themes = @('DriveIn', 'Cinematic', 'Editorial'),
    [string]$Serial,
    [int]$Port = 5584,
    [string]$Out,
    [switch]$KeepServers,
    [int]$TimeoutMinutes = 90,
    [int]$SlotWaitMinutes = 60,
    [double]$MinFreeGB = 4,
    [int]$MaxEmulatorCrashes = 3,
    [string]$MainServerRoot = 'F:\Dev\nixie-real-jellyfin',
    [string]$SecondServerRoot = 'F:\Dev\nixie-gate-jellyfin2',
    # 1.0.1: the transcode-refusing fake (RefusedTranscodeGate)
    [int]$FakePort = 8516,
    [int]$FakeTestKbps = 400,
    [string]$FakeMediaRoot = 'F:\Dev\nixie-gate-fake-media',
    [string]$Mpv,
    [string]$Python = 'python'
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
. (Join-Path $PSScriptRoot 'GateEmulator.ps1')
$testPackage = 'io.github.mbustos27.nixie.tv.gate.test'
$runner = "$testPackage/androidx.test.runner.AndroidJUnitRunner"
$deviceDir = '/data/local/tmp/nixie-gate'
$deviceOut = "/sdcard/Android/data/$testPackage/files/gate"
if (-not $Out) { $Out = Join-Path $repo ("build\gate\" + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
New-Item -ItemType Directory -Force $Out | Out-Null
$report = Join-Path $Out 'gate-report.txt'
function Say([string]$Text) { $line = "{0:HH:mm:ss} {1}" -f (Get-Date), $Text; Write-Host $line; Add-Content -Path $report -Value $line -Encoding utf8 }
function Adb { $ErrorActionPreference = 'Continue'; & $GateAdb -s $script:serial @args 2>&1 | ForEach-Object { "$_" } }
# adb waits forever for a device that is gone: every call after the checks asks first.
function Test-DeviceUp { [bool]((Invoke-GateAdb devices) -match "^$([regex]::Escape($script:serial))\s+device") }

# --- 1. the checks the pattern picks up -------------------------------------------------------------------------------
$sources = Join-Path $repo 'gate\src\androidTest\java'
# <Name>Gate.kt anywhere but the kit (whose Gate.kt is the run's settings, not a check)
$classes = @(Get-ChildItem $sources -Recurse -Filter '*Gate.kt' | Where-Object { $_.Name -ne 'Gate.kt' -and $_.Directory.Name -ne 'kit' } |
    ForEach-Object {
        $relative = $_.FullName.Substring($sources.Length + 1)
        ($relative -replace '\.kt$', '') -replace '[\\/]', '.'
    } | Where-Object { -not $Only -or (($_ -split '\.')[-1] -match $Only) } | Sort-Object)
# -Check: Class#method or a whole Class, by its simple name
$targets = if ($Check.Count) {
    @($Check | ForEach-Object {
            $name, $method = $_.Split('#', 2)
            $full = $classes | Where-Object { $_ -like "*.$name" } | Select-Object -First 1
            if (-not $full) { "#missing:$name" } elseif ($method) { "$full#$method" } else { $full }
        })
} else { $classes }
if (-not $targets.Count -or ($targets | Where-Object { $_ -like '#*' })) { Say "no checks match (-Only '$Only', -Check '$($Check -join ',')')"; exit 2 }
Say "checks: $($targets.Count) class(es) or check(s): $(($targets | ForEach-Object { ($_ -split '\.')[-1] }) -join ', ')"

# --- 2. the build ------------------------------------------------------------------------------------------------------
$gateApk = Join-Path $repo 'gate\build\outputs\apk\androidTest\debug\gate-debug-androidTest.apk'
if (-not $SkipBuild) {
    $tasks = @(':gate:assembleDebugAndroidTest')
    if (-not $Apk) { $tasks = @(':app:assembleNixieStaging', '-Pnixie.stagingAsDebug=true') + $tasks }
    Say "building: $($tasks -join ' ')"
    Push-Location $repo
    try {
        & .\gradlew.bat @tasks --console=plain -q 2>&1 | Tee-Object -FilePath (Join-Path $Out 'build.log') | Out-Null
        if ($LASTEXITCODE -ne 0) { Say "the build failed: see $Out\build.log"; exit 2 }
    } finally { Pop-Location }
}
if (-not $Apk) {
    $Apk = Get-ChildItem (Join-Path $repo 'app\build\outputs\apk\nixie\staging') -Filter '*.apk' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $Apk -or -not (Test-Path $Apk)) { Say 'no APK to test: build one or pass -Apk'; exit 2 }
if (-not (Test-Path $gateApk)) { Say "no gate APK at $gateApk"; exit 2 }
$Apk = (Resolve-Path $Apk).Path
$aapt = Get-ChildItem (Join-Path $GateSdk 'build-tools') -Directory | Sort-Object { [version]$_.Name } -Descending |
    ForEach-Object { Join-Path $_.FullName 'aapt2.exe' } | Where-Object { Test-Path $_ } | Select-Object -First 1
$badging = (& $aapt dump badging $Apk 2>$null | Select-Object -First 1)
$appPackage = [regex]::Match($badging, "name='([^']+)'").Groups[1].Value
$appVersion = [regex]::Match($badging, "versionCode='([^']+)'").Groups[1].Value
if (-not $appPackage) { Say "could not read the package of $Apk"; exit 2 }
Say "under test: $(Split-Path $Apk -Leaf) ($appPackage, versionCode $appVersion)"

$startedServers = @()
$emulator = $null
$fakeProcess = $null
$fakeInfo = $null
$failed = 0; $passed = 0; $broken = $false
try {
    # --- 3. the servers ------------------------------------------------------------------------------------------------
    foreach ($s in @(@{ Name = 'main'; Url = 'http://127.0.0.1:8496'; Args = @('-File', "`"$(Join-Path $MainServerRoot 'Start-RealJellyfin.ps1')`"") },
            @{ Name = 'second'; Url = 'http://127.0.0.1:8506'; Args = @('-File', "`"$(Join-Path $PSScriptRoot 'Start-SecondJellyfin.ps1')`"", '-Root', "`"$SecondServerRoot`"") })) {
        $up = try { Invoke-RestMethod "$($s.Url)/System/Info/Public" -TimeoutSec 3 | Out-Null; $true } catch { $false }
        if (-not $up) {
            Say "starting the $($s.Name) server"
            # Its output through a file, not a pipe: the server it starts inherits the start script's handles, so a pipe
            # would stay open for as long as the server runs, and the gate would wait on it for ever (1.0.1).
            $startLog = Join-Path $Out "start-$($s.Name)-server.log"
            $starter = Start-Process -FilePath powershell -ArgumentList (@('-NoProfile') + $s.Args) -WindowStyle Hidden -PassThru `
                -RedirectStandardOutput $startLog -RedirectStandardError "$startLog.err"
            $starter.WaitForExit()
            Get-Content $startLog, "$startLog.err" -ErrorAction SilentlyContinue | Where-Object { $_ } | ForEach-Object { Say "  $_" }
            $startedServers += $s.Name
        }
    }
    $mainInfo = Invoke-RestMethod 'http://127.0.0.1:8496/System/Info/Public' -TimeoutSec 10
    $secondInfo = Invoke-RestMethod 'http://127.0.0.1:8506/System/Info/Public' -TimeoutSec 10

    # --- 3b. the transcode-refusing fake (1.0.1), only for the checks that use it ---------------------------------------
    if ($targets | Where-Object { $_ -match 'RefusedTranscodeGate' }) {
        $fakeUrl = "http://127.0.0.1:$FakePort"
        $taken = try { Invoke-RestMethod "$fakeUrl/System/Info/Public" -TimeoutSec 2 | Out-Null; $true } catch { $false }
        if ($taken) { throw "port $FakePort answers already: the refusing test server must be this run's own (pass -FakePort)" }
        $fakeOut = Join-Path $Out 'fakeserver'
        New-Item -ItemType Directory -Force $fakeOut | Out-Null
        $ready = Join-Path $fakeOut 'ready.json'
        $pythonExe = (Get-Command $Python -ErrorAction Stop).Source
        $fakeArgs = @('-B', (Join-Path $repo 'testing\fakeserver\tvserver.py'), '--profile', 'jellyfin-12', '--out', $fakeOut,
            '--media', $FakeMediaRoot, '--media-seconds', '120', '--port', $FakePort, '--ready-file', $ready, '--not-admin',
            '--refuse-transcodes', '--bitrate-test-kbps', $FakeTestKbps)
        if (-not (Test-Path (Join-Path $FakeMediaRoot 'media.json'))) {
            $mpvPath = if ($Mpv) { $Mpv } else { (Get-Command mpv -ErrorAction SilentlyContinue).Source }
            if (-not $mpvPath) { throw "the refusing test server's video is not made yet and no mpv was found: pass -Mpv" }
            Say "making the refusing test server's video once with mpv (into $FakeMediaRoot)"
            $fakeArgs += @('--mpv', $mpvPath)
        }
        $quoted = $fakeArgs | ForEach-Object { if ("$_" -match '\s') { '"' + $_ + '"' } else { "$_" } }
        $fakeProcess = Start-Process -FilePath $pythonExe -ArgumentList $quoted -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $fakeOut 'stdout.log') -RedirectStandardError (Join-Path $fakeOut 'stderr.log')
        $until = (Get-Date).AddMinutes(10)
        while (-not (Test-Path $ready) -and -not $fakeProcess.HasExited -and (Get-Date) -lt $until) { Start-Sleep -Milliseconds 500 }
        if (-not (Test-Path $ready)) { throw "the refusing test server did not start (see $fakeOut\stderr.log)" }
        $fakeInfo = Invoke-RestMethod "$fakeUrl/System/Info/Public" -TimeoutSec 10
        Say "refusing test server up at $fakeUrl (transcodes refused, link test at $FakeTestKbps kbit/s)"
    }

    # --- 4. the emulator -----------------------------------------------------------------------------------------------
    if ($Serial) {
        $script:serial = $Serial
        if (-not ((Invoke-GateAdb devices) -match "^$Serial\s+device")) { throw "no emulator at $Serial" }
        Say "emulator: $Serial (held by its own session)"
    } else {
        $emulator = Start-GateEmulator -Port $Port -Say { param($t) Say $t }
        $script:serial = $emulator.Serial
    }
    # The device's part, again after an emulator restart (a read-only emulator starts with none of it).
    $prepareDevice = {
        Adb shell svc power stayon true | Out-Null

        # --- 5. the fixture ------------------------------------------------------------------------------------------------
        $mainCred = Get-Content (Join-Path $MainServerRoot 'credentials.json') -Raw | ConvertFrom-Json
        $secondCred = Get-Content (Join-Path $SecondServerRoot 'credentials.json') -Raw | ConvertFrom-Json
        $fixture = @{
            servers = @{
                main = @{ name = $mainInfo.ServerName; address = 'http://10.0.2.2:8496' }
                second = @{ name = $secondInfo.ServerName; address = 'http://10.0.2.2:8506' }
            }
            users = @{
                basics = @{ server = 'main'; name = $mainCred.basics.username; password = $mainCred.basics.password }
                other = @{ server = 'main'; name = $mainCred.user.username; password = $mainCred.user.password }
                secondUser = @{ server = 'second'; name = $secondCred.user.username; password = $secondCred.user.password }
            }
        }
        if ($fakeInfo) {
            # The fake's generated test user (not its admin), from its own credentials file.
            $fakeCred = Get-Content (Join-Path $repo 'testing\fakeserver\desktop\credentials.json') -Raw | ConvertFrom-Json
            $fixture.servers.refusing = @{ name = $fakeInfo.ServerName; address = "http://10.0.2.2:$FakePort" }
            $fixture.users.refusingUser = @{ server = 'refusing'; name = $fakeCred.username; password = $fakeCred.password }
        }
        $local = Join-Path $env:TEMP ("nixie-gate-" + [guid]::NewGuid().ToString('N') + '.json')
        try {
            [IO.File]::WriteAllText($local, ($fixture | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
            Adb shell "mkdir -p $deviceDir" | Out-Null
            Adb push $local "$deviceDir/fixture.json" | Out-Null
        } finally { Remove-Item $local -Force -ErrorAction SilentlyContinue }

        # --- 6. install and run --------------------------------------------------------------------------------------------
        $installed = (Adb install -r -d $Apk) -join ' '
        if ($installed -notmatch 'Success') {
            Say "install over the old build failed ($installed); uninstalling $appPackage first"
            Adb uninstall $appPackage | Out-Null
            $installed = (Adb install $Apk) -join ' '
            if ($installed -notmatch 'Success') { throw "could not install $Apk`: $installed" }
        }
        $installed = (Adb install -r -t $gateApk) -join ' '
        if ($installed -notmatch 'Success') {
            Adb uninstall $testPackage | Out-Null
            $installed = (Adb install -t $gateApk) -join ' '
            if ($installed -notmatch 'Success') { throw "could not install the gate APK: $installed" }
        }
    }
    & $prepareDevice

    $themeQueue = [System.Collections.Generic.Queue[object]]::new()
    foreach ($th in $Themes) { $themeQueue.Enqueue(@{ Theme = $th; Try = 1; Done = @(); CrashedOn = $null }) }
    $crashes = 0
    while ($themeQueue.Count) {
        $item = $themeQueue.Dequeue()
        $theme = $item.Theme
        if (-not (Test-DeviceUp)) { throw "the emulator at $script:serial is gone" }
        Say "theme $theme$(if ($item.Try -gt 1) { " (again, after the emulator crashed: the $($item.Done.Count) checks done before it are not run again)" })"
        # The emulator crashed twice at about 3 GB of free RAM: wait for room first (the harness's own reading).
        if (Get-Command Wait-HarnessMemory -ErrorAction SilentlyContinue) {
            $memory = Wait-HarnessMemory -MinGB $MinFreeGB -Until (Get-Date).AddMinutes($SlotWaitMinutes) -OnWait {
                param($m) Say "  waiting for $MinFreeGB GB free (now $($m.Ram) GB RAM, $($m.Commit) GB commit)"
            }
            if (-not $memory.Ok) { Say "  running with $($memory.Ram) GB RAM free after the wait" }
        }
        Adb shell "rm -rf $deviceOut" | Out-Null
        Adb logcat -c | Out-Null
        $raw = Join-Path $Out ("instrument-$theme" + $(if ($item.Try -gt 1) { "-try$($item.Try)" }) + '.txt')
        $argList = @('-s', $script:serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', ($targets -join ','))
        # After an emulator crash: the checks that finished before it are not run again (Class#method each).
        if ($item.Done.Count) { $argList += @('-e', 'notClass', ($item.Done -join ',')) }
        $argList += @('-e', 'gate.package', $appPackage, '-e', 'gate.theme', $theme, $runner)
        # A desktop harness slot only while this theme's checks drive the emulator: given back right after them (also
        # on an error; abandoned, it is free again when this process dies).
        $runLock = Enter-GateRunSlot -WaitMinutes $SlotWaitMinutes -Say { param($t) Say "  $t" }
        $started = Get-Date
        try {
            $p = Start-Process -FilePath $GateAdb -ArgumentList $argList -NoNewWindow -PassThru -RedirectStandardOutput $raw -RedirectStandardError "$raw.err"
            if (-not $p.WaitForExit($TimeoutMinutes * 60 * 1000)) {
                Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
                Adb shell "am force-stop $testPackage" | Out-Null
                Say "  the checks did not finish in $TimeoutMinutes min: stopped"
                $failed++
            }
        } finally {
            Exit-HarnessRunSlot $runLock
            Say ("  harness slot given back after {0:N0} min of checks" -f ((Get-Date) - $started).TotalMinutes)
        }
        # The raw protocol: a block of STATUS lines per check, its code 1 at the start, then 0 (passed), -2 (failed),
        # -1 (error), -3 (ignored), -4 (assumption failed).
        $current = @{}
        $stack = $null
        $collecting = $false
        $done = @()
        $running = $null
        foreach ($line in (Get-Content $raw -Encoding UTF8 -ErrorAction SilentlyContinue)) {
            if ($line -match '^INSTRUMENTATION_STATUS: (\w+)=(.*)$') {
                $collecting = $Matches[1] -eq 'stack'
                $current[$Matches[1]] = $Matches[2]
                if ($collecting) { $stack = $Matches[2] }
            } elseif ($line -match '^INSTRUMENTATION_STATUS_CODE: (-?\d+)$') {
                $collecting = $false
                $code = [int]$Matches[1]
                $name = "$((($current['class']) -split '\.')[-1]).$($current['test'])"
                $running = if ($code -eq 1) { "$($current['class'])#$($current['test'])" } else { $null }
                if ($code -ne 1) { $done += "$($current['class'])#$($current['test'])" }
                switch ($code) {
                    0 { $passed++; Say "  PASS $name" }
                    { $_ -in -1, -2 } {
                        $failed++
                        $why = ($stack -split "`n")[0] -replace '^[\w.$]+(Failure|Error|Exception)[\w.$]*: ', ''
                        Say "  FAIL $name`: $why"
                    }
                    -3 { Say "  SKIP $name" }
                    -4 { Say "  SKIP $name (assumption)" }
                }
                if ($code -ne 1) { $current = @{}; $stack = $null }
            } elseif ($line -match '^INSTRUMENTATION_(RESULT|CODE|FAILED)') {
                $collecting = $false
                if ($line -match 'shortMsg=(.*)$' -or $line -match '^INSTRUMENTATION_FAILED: (.*)$') { $failed++; Say "  the run broke: $($Matches[1])" }
            } elseif ($collecting) {
                $stack += "`n$line"
            }
        }
        if (-not (Test-DeviceUp)) {
            # The emulator itself went away (qemu, not the app: an app's crash or ANR leaves the emulator up). Its log
            # and Windows' record of the fault are kept; the checks that finished count, and the rest run on a new one.
            $crashes++
            $runningName = if ($running) { ($running -split '[.#]')[-2..-1] -join '.' } else { 'between checks' }
            $log = Join-Path $env:TEMP "nixie-gate-emulator-$Port.log"
            if (Test-Path $log) { Copy-Item $log (Join-Path $Out "emulator-$theme-crash$crashes.log") -Force }
            $fault = Get-WinEvent -FilterHashtable @{ LogName = 'Application'; Id = 1000; StartTime = $started } -ErrorAction SilentlyContinue |
                Where-Object { $_.Message -match 'qemu-system' } | Select-Object -First 1
            $why = if ($fault -and $fault.Message -match 'Faulting module name: ([^,]+),[\s\S]*?Exception code: (0x[0-9a-fA-F]+)') {
                "qemu faulted ($($Matches[2]) in $($Matches[1]))"
            } else { 'qemu stopped' }
            Say "  the emulator crashed during $runningName ($why; its log: emulator-$theme-crash$crashes.log)"
            if (-not $emulator -or $crashes -gt $MaxEmulatorCrashes) {
                $failed++
                throw "the emulator at $script:serial went away during the $theme checks ($crashes crashes)"
            }
            $skip = @($item.Done) + @($done)
            if ($running -and $running -eq $item.CrashedOn) {
                # The same check twice: the emulator, not chance, at that point; it fails, the rest goes on.
                $failed++
                Say "  FAIL $runningName`: the emulator crashed in it twice"
                $skip += $running
            }
            Stop-GateEmulator $emulator
            $emulator = Start-GateEmulator -Port $Port -Say { param($t) Say "  $t" }
            $script:serial = $emulator.Serial
            & $prepareDevice
            $themeQueue.Enqueue(@{ Theme = $theme; Try = $item.Try + 1; Done = $skip; CrashedOn = $running })
            continue
        }
        $themeOut = Join-Path $Out $theme
        New-Item -ItemType Directory -Force $themeOut | Out-Null
        Adb pull "$deviceOut/." $themeOut | Out-Null
        # every key the checks pressed and where the focus went (Remote's trace)
        Adb logcat -d -s NixieGate:I TestRunner:I | Set-Content (Join-Path $themeOut 'keys.log') -Encoding utf8
    }
} catch {
    Say "the gate could not run: $($_.Exception.Message)"
    $broken = $true
} finally {
    if ($script:serial -and (Test-DeviceUp)) { Adb shell "rm -rf $deviceDir" | Out-Null }
    if ($emulator) { Stop-GateEmulator $emulator; Say 'emulator closed, its emulator slot given back' }
    if ($fakeProcess -and -not $fakeProcess.HasExited) {
        # This run's own process: asked to shut down, ended by its handle only if it does not.
        try { Invoke-RestMethod "http://127.0.0.1:$FakePort/__harness/shutdown" -Method Post -TimeoutSec 5 | Out-Null } catch { }
        if (-not $fakeProcess.WaitForExit(10000)) { try { $fakeProcess.Kill() } catch { } }
        Say 'refusing test server stopped'
    }
    if (-not $KeepServers) {
        if ('second' -in $startedServers) { powershell -NoProfile -File (Join-Path $PSScriptRoot 'Stop-SecondJellyfin.ps1') -Root $SecondServerRoot | Out-Null; Say 'second server stopped (this run started it)' }
        if ('main' -in $startedServers) { powershell -NoProfile -File (Join-Path $MainServerRoot 'Stop-RealJellyfin.ps1') | Out-Null; Say 'main server stopped (this run started it)' }
    }
}
if ($broken) { exit 2 }
Say "gate: $passed passed, $failed failed ($($Themes -join ', ')); report $report"
if ($failed -gt 0 -or $passed -eq 0) { exit 1 }
exit 0
