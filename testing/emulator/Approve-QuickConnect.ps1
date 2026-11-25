# SPDX-FileCopyrightText: 2026 Nixie contributors
# SPDX-License-Identifier: GPL-3.0-or-later
<#
.SYNOPSIS
  Approves every Quick Connect code waiting on the TV test server, for a while: the phone's part, for unattended runs
  (the baseline profile generator signs in this way).

.DESCRIPTION
  Polls GET /__harness/quickconnect on the TV test server (testing/fakeserver, 127.0.0.1 only) every second and
  approves each waiting code with POST /__harness/quickconnect/approve, for -Minutes. Only the test server: it has no
  way to reach anyone's real server, and no password or token passes through it.
#>
param([int]$Port = 18412, [int]$Minutes = 15)
$server = "http://127.0.0.1:$Port"
$until = (Get-Date).AddMinutes($Minutes)
while ((Get-Date) -lt $until) {
    try {
        # Assigned first: Windows PowerShell hands a JSON array back as one object until it is unrolled.
        $list = Invoke-RestMethod "$server/__harness/quickconnect"
        foreach ($waiting in $list) {
            if ($waiting.approved) { continue }
            Invoke-RestMethod -Method Post -ContentType 'application/json' -Body (@{ code = $waiting.code } | ConvertTo-Json) `
                "$server/__harness/quickconnect/approve" | Out-Null
            "approved a code at $(Get-Date -Format T)"
        }
    } catch { }
    Start-Sleep -Seconds 1
}
