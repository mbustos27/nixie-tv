# Security policy

## Reporting a vulnerability

Please don't report a security problem in a public issue. Report it privately through GitHub's private vulnerability
reporting: **[Report a vulnerability](https://github.com/mbustos27/nixie-tv/security/advisories/new)** (the Security
tab of this repository). Only the maintainer sees the report, and the conversation stays private until a fix is out.
There is no e-mail address for reports.

Please include:

- the Nixie for TV version (Settings › System) and the box with its Android version;
- what someone could do, and how: the steps, or a proof of concept;
- what you think would fix it, if you know.

Leave out any server address, user name, password or token, also from logs and screenshots.

Nixie for TV is based on Glacier. A problem in code the two share may affect Glacier too, but please don't send
problems you found in Nixie to Glacier's maintainers: they can't fix Nixie. If you believe Glacier is affected as
well, say so in your report.

## Supported versions

Only the newest release receives fixes.

## Verifying a download

Each release is one APK, `Nixie-TV-<version>.apk`, published with its SHA-256 in `Nixie-TV-<version>.apk.sha256`.
Compare the two before installing by hand:

```powershell
(Get-FileHash Nixie-TV-1.0.0.apk -Algorithm SHA256).Hash.ToLower()
Get-Content Nixie-TV-1.0.0.apk.sha256
```

```sh
sha256sum -c Nixie-TV-1.0.0.apk.sha256
```

The in-app updater checks the SHA-256 digest GitHub publishes for the file, the package name and the version before
installing. Android then refuses any update that isn't signed with the same key as the installed app.
