# SPDX-FileCopyrightText: 2026 Nixie contributors
# SPDX-License-Identifier: GPL-3.0-or-later
<#
.SYNOPSIS
  The gate's second server: a small throwaway Jellyfin of its own (never anyone's server), made on first use and
  started hidden on loopback.

.DESCRIPTION
  Runs the throwaway Jellyfin's server files (-ServerFrom, read only) with its own folders under -Root, on 127.0.0.1
  -Port only (discovery, UPnP, IPv6 and remote access off; the emulator reaches it at 10.0.2.2:<Port>). Requests to
  the internet go to the main throwaway's refusing proxy (127.0.0.1:8498), as there.
  The first start runs Jellyfin's setup through its API: the server name -Name, an admin and one test user (gate2)
  with random passwords kept in <Root>\credentials.json (read by the gate's tools only, never printed), and a Movies
  library of -Films synthetic films (.strm pointing nowhere and a movie.nfo; no images, no metadata fetchers).
  Writes <Root>\run\state.json (pid and start time) for Stop-SecondJellyfin.ps1. Prints one line.
#>
param(
    [string]$Root = 'F:\Dev\nixie-gate-jellyfin2',
    [string]$ServerFrom = 'F:\Dev\nixie-real-jellyfin\server',
    [int]$Port = 8506,
    [string]$Name = 'Nixie gate second server',
    [int]$Films = 30
)
$ErrorActionPreference = 'Stop'
$base = "http://127.0.0.1:$Port"
$state = Join-Path $Root 'run\state.json'
$credFile = Join-Path $Root 'credentials.json'

function Test-Ours($procId, $started) {
    $p = Get-Process -Id $procId -ErrorAction SilentlyContinue
    $p -and $p.StartTime.ToFileTimeUtc() -eq [long]$started
}
function New-Secret { -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 22 | ForEach-Object { [char]$_ }) }
function Get-Header([string]$Token) {
    $h = 'MediaBrowser Client="Nixie gate", Device="gate-setup", DeviceId="nixie-gate-setup", Version="1"'
    if ($Token) { $h += ", Token=`"$Token`"" }
    @{ Authorization = $h }
}
function Invoke-Jf([string]$Method, [string]$Path, $Body = $null, [string]$Token = $null) {
    $req = @{ Method = $Method; Uri = "$base$Path"; Headers = (Get-Header $Token); TimeoutSec = 30 }
    if ($null -ne $Body) { $req.Body = ($Body | ConvertTo-Json -Depth 10); $req.ContentType = 'application/json' }
    # 503 while Jellyfin is still starting up, after its public info already answers
    for ($try = 1; ; $try++) {
        try { return Invoke-RestMethod @req }
        catch {
            $code = try { [int]$_.Exception.Response.StatusCode } catch { 0 }
            if ($code -ne 503 -or $try -ge 90) { throw }
            Start-Sleep -Seconds 2
        }
    }
}

$running = $false
if (Test-Path $state) {
    $s = Get-Content $state -Raw | ConvertFrom-Json
    $running = Test-Ours $s.pid $s.started
}
$fresh = -not (Test-Path (Join-Path $Root 'config\system.xml'))
foreach ($d in 'config', 'data', 'cache', 'log', 'run', 'media\Movies') { New-Item -ItemType Directory -Force (Join-Path $Root $d) | Out-Null }

if ($fresh) {
    # Loopback only, before the first start, so it never listens on 8096 or the network.
    @"
<?xml version="1.0" encoding="utf-8"?>
<NetworkConfiguration xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
  <BaseUrl />
  <EnableHttps>false</EnableHttps>
  <RequireHttps>false</RequireHttps>
  <InternalHttpPort>$Port</InternalHttpPort>
  <InternalHttpsPort>$($Port + 1)</InternalHttpsPort>
  <PublicHttpPort>$Port</PublicHttpPort>
  <PublicHttpsPort>$($Port + 1)</PublicHttpsPort>
  <AutoDiscovery>false</AutoDiscovery>
  <EnableUPnP>false</EnableUPnP>
  <EnableIPv4>true</EnableIPv4>
  <EnableIPv6>false</EnableIPv6>
  <EnableRemoteAccess>false</EnableRemoteAccess>
  <LocalNetworkSubnets />
  <LocalNetworkAddresses>
    <string>127.0.0.1</string>
  </LocalNetworkAddresses>
  <KnownProxies />
  <IgnoreVirtualInterfaces>true</IgnoreVirtualInterfaces>
  <EnablePublishedServerUriByRequest>false</EnablePublishedServerUriByRequest>
  <PublishedServerUriBySubnet />
  <RemoteIPFilter />
  <IsRemoteIPFilterBlacklist>false</IsRemoteIPFilterBlacklist>
</NetworkConfiguration>
"@ | Set-Content (Join-Path $Root 'config\network.xml') -Encoding utf8
    # The films: titles from a fixed list, years 1990 on; nothing points to a real file.
    $words = 'Harbor', 'Lantern', 'Meridian', 'Orchard', 'Quarry', 'Saltmarsh', 'Tinder', 'Upland', 'Vesper', 'Willow'
    for ($i = 1; $i -le $Films; $i++) {
        $title = "Second $($words[($i - 1) % $words.Count]) $i"
        $dir = Join-Path $Root "media\Movies\$title ($(1989 + $i))"
        New-Item -ItemType Directory -Force $dir | Out-Null
        Set-Content (Join-Path $dir "$title.strm") 'http://127.0.0.1:9/none.mkv' -Encoding ascii
        "<?xml version=`"1.0`" encoding=`"utf-8`"?><movie><title>$title</title><year>$(1989 + $i)</year><runtime>95</runtime></movie>" |
            Set-Content (Join-Path $dir 'movie.nfo') -Encoding utf8
    }
}

$p = $null
if ($running) {
    $p = Get-Process -Id $s.pid
} else {
    $env:HTTP_PROXY = 'http://127.0.0.1:8498'
    $env:HTTPS_PROXY = 'http://127.0.0.1:8498'
    $env:NO_PROXY = '127.0.0.1,localhost'
    $exe = Join-Path $ServerFrom 'jellyfin.exe'
    $argv = @('--datadir', "`"$Root\data`"", '--configdir', "`"$Root\config`"", '--cachedir', "`"$Root\cache`"",
        '--logdir', "`"$Root\log`"", '--ffmpeg', "`"$ServerFrom\ffmpeg.exe`"", '--webdir', "`"$ServerFrom\jellyfin-web`"")
    $p = Start-Process -FilePath $exe -ArgumentList $argv -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $Root 'log\stdout.log') -RedirectStandardError (Join-Path $Root 'log\stderr.log')
    @{ pid = $p.Id; started = $p.StartTime.ToFileTimeUtc() } | ConvertTo-Json | Set-Content $state -Encoding utf8
}
$info = $null
for ($i = 0; $i -lt 180 -and -not $info; $i++) {
    try { $info = Invoke-RestMethod "$base/System/Info/Public" -TimeoutSec 2 } catch { Start-Sleep -Seconds 1 }
    if ($p.HasExited) { throw "the second Jellyfin exited ($($p.ExitCode)); see $Root\log" }
}
if (-not $info) { throw 'the second Jellyfin did not answer within 180 s' }

# Ready once its API answers (503 until the start-up is through); set up once, when no credentials were written yet.
Invoke-Jf GET '/Users/Public' | Out-Null
if (-not (Test-Path $credFile)) {
    $admin = @{ username = 'gateadmin'; password = (New-Secret) }
    $user = @{ username = 'gate2'; password = (New-Secret) }
    Invoke-Jf POST '/Startup/Configuration' @{ ServerName = $Name; UICulture = 'en-US'; MetadataCountryCode = 'US'; PreferredMetadataLanguage = 'en' } | Out-Null
    Invoke-Jf GET '/Startup/User' | Out-Null
    Invoke-Jf POST '/Startup/User' @{ Name = $admin.username; Password = $admin.password } | Out-Null
    Invoke-Jf POST '/Startup/RemoteAccess' @{ EnableRemoteAccess = $false; EnableAutomaticPortMapping = $false } | Out-Null
    Invoke-Jf POST '/Startup/Complete' | Out-Null
    $auth = Invoke-Jf POST '/Users/AuthenticateByName' @{ Username = $admin.username; Pw = $admin.password }
    $token = $auth.AccessToken
    Invoke-Jf POST '/Users/New' @{ Name = $user.username; Password = $user.password } $token | Out-Null
    $libraryOptions = @{ LibraryOptions = @{ EnableInternetProviders = $false; EnableRealtimeMonitor = $false; MetadataSavers = @();
            PathInfos = @(@{ Path = (Join-Path $Root 'media\Movies') }) } }
    Invoke-Jf POST '/Library/VirtualFolders?name=Movies&collectionType=movies&refreshLibrary=true' $libraryOptions $token | Out-Null
    Invoke-Jf POST '/Sessions/Logout' $null $token | Out-Null
    @{ admin = $admin; user = $user } | ConvertTo-Json | Set-Content $credFile -Encoding utf8
}
"second server up at $base (Jellyfin $($info.Version), pid $($p.Id))"
