# SPDX-FileCopyrightText: 2026 Nixie contributors
# SPDX-License-Identifier: GPL-3.0-or-later
<#
.SYNOPSIS
  Signs a Nixie build on the emulator in to the TV test server by Quick Connect, with the remote's keys only.

.DESCRIPTION
  For emulator checks: clears the app's data, opens it, enters the test server's address, lets the app open Quick
  Connect (Nixie's default), and approves the code the way a signed-in phone would, through the test server's
  harness endpoint (POST /__harness/quickconnect/approve). Only for testing/fakeserver on this PC: no password, no
  token and no account of anyone's server passes through here, and the app itself has no sign-in shortcut.

.EXAMPLE
  .\testing\emulator\Sign-InTestServer.ps1 -Serial emulator-5584 -Port 18412
#>
param(
    [string]$Serial = 'emulator-5584',
    [int]$Port = 18412,
    [string]$Package = 'io.github.mbustos27.nixie.tv.debug'
)
$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$server = "http://127.0.0.1:$Port"

function Send-Key([string]$Key, [double]$Wait = 0.8) {
    & $adb -s $Serial shell input keyevent "KEYCODE_$Key" | Out-Null
    Start-Sleep -Milliseconds ([int]($Wait * 1000))
}

& $adb -s $Serial shell pm clear $Package | Out-Null
& $adb -s $Serial shell am start -n "$Package/io.github.glacier_jellyfin.androidtv.MainActivity" | Out-Null
Start-Sleep -Seconds 7
Send-Key DPAD_CENTER 3      # "Enter server address manually"
Send-Key DPAD_CENTER 2      # the address field opens the system keyboard
& $adb -s $Serial shell input text "10.0.2.2:$Port" | Out-Null
Start-Sleep -Milliseconds 1500
Send-Key ENTER 2.5          # closes the keyboard; the focus moves to Connect
Send-Key DPAD_CENTER 7      # connects; Nixie opens Quick Connect first

$code = $null
for ($i = 0; $i -lt 10 -and -not $code; $i++) {
    $waiting = Invoke-RestMethod "$server/__harness/quickconnect"
    $code = @($waiting | Where-Object { -not $_.approved }) | Select-Object -Last 1 -ExpandProperty code
    if (-not $code) { Start-Sleep -Seconds 1 }
}
if (-not $code) { throw 'No Quick Connect code waiting on the test server' }
Invoke-RestMethod -Method Post -ContentType 'application/json' -Body (@{ code = $code } | ConvertTo-Json) `
    "$server/__harness/quickconnect/approve" | Out-Null
Start-Sleep -Seconds 9
"signed in (code approved by the test server's harness)"
