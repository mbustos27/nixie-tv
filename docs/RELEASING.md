# Releasing

Nixie for TV is released on GitHub only, from [mbustos27/nixie-tv](https://github.com/mbustos27/nixie-tv): no app
store, no release workflow and no signing secrets. The maintainer builds the APK unsigned, the owner signs it with the
release key, `nixie-release` checks it and writes its `.sha256`, and the maintainer publishes it with `gh`.

## Versions

The version is `nixie.version` in `gradle.properties` (the `nixie` flavor's; Glacier's `glacier.version` beside it is
not used by Nixie's builds). Releases are `MAJOR.MINOR.PATCH`; betas, when there are any, `MAJOR.MINOR.PATCH-beta.N`.
A test build can take another version on the command line (`-Pnixie.version=1.0.1-beta.1`) without changing the file.

Android's `versionCode` is derived from the version by Glacier's rule, so it always increases, also from a beta to its
release:

```
versionCode = major * 1_000_000 + minor * 10_000 + patch * 100 + (N for beta.N, 99 for a release)
```

| Version | versionCode |
|---|---|
| `0.1.1-beta.4` (the last test build before 1.0) | 10104 |
| `1.0.0` | 1000099 |
| `1.0.1` | 1000199 |
| `1.1.0-beta.1` | 1010001 |

Limits: `minor` and `patch` ≤ 99, `N` between 1 and 98. The rule exists twice, in `app/build.gradle.kts`
(`versionCodeOf`) and in `core/updater` (`AppVersion`); keep them identical. `nixie-release` refuses a versionCode that
isn't above the last one in `release/released.txt`.

## How the app updates itself

1. The release reads `https://api.github.com/repos/mbustos27/nixie-tv/releases` at every start, and whenever the user
   chooses Settings › System › Check for updates. Check for updates automatically, in the same place, turns the check
   at start off. Debug and staging builds check only when asked. The last result stands in while the check runs and
   when it fails (offline start).
2. It picks the newest release its channel allows: Stable ignores pre-releases, Beta considers all. A release is
   skipped when its tag does not parse, when the pre-release flag disagrees with the tag, or when it has no
   `Nixie-TV-<version>.apk` (that exact name: `nixie-tv.apk` and Glacier's names are never taken). Downgrades never
   happen: after switching from Beta back to Stable, the app waits for the next release.
3. The update dialog on the home screen shows the release's text on GitHub (the notes), once per version; Settings ›
   System shows them too. Only after the user confirms is the APK downloaded.
4. Before installing, the app checks the SHA-256 digest GitHub reports for the APK (a release without one is refused),
   the package name and the `versionCode`. Android itself rejects an APK signed with a different key.
5. Android's package installer installs it. It asks once for permission to install apps from Nixie, and ends Nixie
   while it replaces it.

## Publishing a release (the maintainer, with the owner for the signature)

1. **The basics gate** on the release commit (below). A failed check stops the release until it is fixed.
2. **The notes.** Move the `[Unreleased]` entries in `CHANGELOG.md` into a new section `## [<version>] - <date>`.
   That section becomes the release's text on GitHub, which the app's update dialog shows: write it for someone
   installing Nixie. `scripts/release/release-notes.sh <version>` prints it. Nixie's notes are in English only;
   `CHANGELOG.de.md` and `CHANGELOG.es.md` hold no versions.
3. **The version.** `nixie.version=<version>` in `gradle.properties`. Commit both on `main`.
4. **The unsigned APK**, with the FFmpeg decoder's native part in place
   ([core/ffmpeg/README.md](../core/ffmpeg/README.md)):

   ```powershell
   .\gradlew.bat :app:assembleNixieRelease
   # app\build\outputs\apk\nixie\release\app-nixie-release-unsigned.apk: ARM only (armeabi-v7a, arm64-v8a)
   ```

5. **The owner signs it.** Only the owner holds the release key (Signing key, below). apksigner asks for its
   passphrase itself, so nobody else sees it:

   ```powershell
   & "<build-tools>\apksigner.bat" sign --ks <the release keystore> --ks-key-alias nixie-tv `
       --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false `
       --out Nixie-TV-<version>.apk app\build\outputs\apk\nixie\release\app-nixie-release-unsigned.apk
   ```

6. **`nixie-release` checks it and writes the `.sha256`** (`tools/release-check`; it never signs and never reads a
   key):

   ```powershell
   cd tools\release-check; cargo build --release; cd ..\..   # once
   $nr = 'tools\release-check\target\release\nixie-release.exe'
   & $nr check  Nixie-TV-<version>.apk   # every line PASS
   & $nr sha256 Nixie-TV-<version>.apk   # writes Nixie-TV-<version>.apk.sha256
   & $nr record Nixie-TV-<version>.apk   # the checks again, then the version into release\released.txt
   ```

   `check` compares the signing certificate with `release/signing-cert.sha256` and checks the manifest, the
   versionCode, the ABIs, the FFmpeg decoder, alignment and size. Commit `release/released.txt`; the APK and its
   `.sha256` are never committed (`*.apk` is ignored).
7. **The tag**, on `main` after it is pushed, pushed alone: never `--tags`, since Glacier's tags stay local.

   ```powershell
   git push origin main
   git tag v<version>
   git push origin v<version>
   ```

8. **The release**, with three files: the APK, its `.sha256`, and the same APK under the name that never changes,
   `nixie-tv.apk`:

   ```powershell
   Copy-Item Nixie-TV-<version>.apk nixie-tv.apk
   bash scripts/release/release-notes.sh <version> > "$env:TEMP\nixie-tv-notes.md"
   gh release create v<version> Nixie-TV-<version>.apk Nixie-TV-<version>.apk.sha256 nixie-tv.apk `
       --verify-tag --title "Nixie for TV <version>" --notes-file "$env:TEMP\nixie-tv-notes.md"
   ```

   `https://github.com/mbustos27/nixie-tv/releases/latest/download/nixie-tv.apk`, and the short link in README's
   Install section that points there, now serve this release. Every release attaches `nixie-tv.apk`, or that address
   stops serving one. The updater takes only `Nixie-TV-<version>.apk`, so the second copy never confuses it.
9. **The digest check.** GitHub publishes each file's SHA-256, and the updater refuses a release without one. Both
   APKs must show `sha256:` followed by the hash in the `.sha256`:

   ```powershell
   gh api repos/mbustos27/nixie-tv/releases/tags/v<version> --jq '.assets[] | "\(.name) \(.digest)"'
   Get-Content Nixie-TV-<version>.apk.sha256
   ```

10. **The short link.** From a PC, following its redirects must end at the APK, whose SHA-256 matches the `.sha256`:

    ```powershell
    curl.exe -sSfL -o "$env:TEMP\nixie-tv-check.apk" <the short link>
    (Get-FileHash "$env:TEMP\nixie-tv-check.apk" -Algorithm SHA256).Hash.ToLower()
    ```

    Then, typed into Downloader on a box, it must install the release. Do this after every release, since
    `releases/latest` moves.

**Full releases only reach everyone.** `releases/latest`, and with it `nixie-tv.apk` and the short link, skips
pre-releases, and so does the updater's Stable channel. Publish every release meant for everyone as a full release (no
`--prerelease`). A beta (`-beta.N`), when there is one, goes up with `--prerelease`: only the Beta channel sees it, and
the link keeps serving the last full release.

### Raising the minimum server version

When a release needs a newer Jellyfin version (see
[ARCHITECTURE.md](ARCHITECTURE.md#server-compatibility)), change it in all of these places and say in the changelog
that servers have to be updated:

- `MinimumServerVersion` in `core/jellyfin` `ServerInfo.kt`, and `ServerInfoTest.kt`
- `setup_unsupported_body` in `app/src/main/res/values{,-de,-es}/strings.xml` and in the nixie flavor's
  `app/src/nixie/res/values{,-de,-es}/strings.xml`
- `README.md` (the Install table) and `docs/ARCHITECTURE.md`

## Signing key

The release key signs every Nixie for TV release, for good. Android installs an update only when it is signed with the
same key, so **if the key or its passphrase is lost, installed copies can no longer be updated**: everyone has to
uninstall (losing settings and sign-ins) and install again.

- It never enters the repository, CI or a workflow, and no build reads it: the owner signs each release by hand
  (step 5 above). The owner made it as in [PHASE0-OWNER.md](PHASE0-OWNER.md), steps 2 and 3.
- The passphrase lives in the owner's password manager only; keytool and apksigner ask for it themselves.
- Two backups on different physical drives, with their hashes checked.
- The certificate's SHA-256 fingerprint, which is public, is in `release/signing-cert.sha256`; `nixie-release check`
  fails on an APK signed with any other key.

Glacier's `signingConfigs` (from `GLACIER_KEYSTORE_*` environment variables) and its `amazon` and `play` store build
types stay in `app/build.gradle.kts` as Glacier's code, unused: with none of those variables set the release APK comes
out unsigned, and Nixie for TV builds no store version.

## The basics gate (every release build)

Rule 12 of `docs/DESIGN-RULES.md`: every basic path is a release gate. The gate's checks (`gate/src/androidTest`, one
class per `*Gate.kt` file) drive the build with the remote's keys on the emulator against the throwaway Jellyfins
(never anyone's server): Quick Connect and password sign-ins across a restart and a force-stop, Sign out, a second
user, profiles and PIN, a second server added, switched and removed, every menu and dialog, every row to its last
item, Back, the top bar, text fitting. A failed check fails the build like a test:

```powershell
.\gradlew.bat :gate:basicsGate "-Pnixie.stagingAsDebug=true"   # or .\tools\gate\Run-Gate.ps1
# builds the staging APK (debug id) and the checks, takes a harness slot and the read-only emulator, starts the
# throwaway servers if needed, runs every check in Drive-in, Cinematic and Editorial, exit 1 on a failure
.\tools\gate\Run-Gate.ps1 -Apk <apk> -SkipBuild -Only AccountsGate -Themes DriveIn   # one class, a given APK
```

Each run writes `build\gate\<time>\gate-report.txt` (one line per check) and, per theme, a failed check's
screenshot, its window tree and the key trace (`keys.log`). A revert check is the same command with `-Apk` naming an
older or patched build: the check must fail on it.

## A release-like build for a box

A debug build runs without R8 and without the baseline profile, which is slow on a TV box's processor. For speed
checks on a real box, build the optimised staging type (R8, resources shrunk, the release's ProGuard rules and
baseline profile, signed with the debug key) with the dev build's application id, so it installs over Nixie debug,
keeps its sign-ins and still takes `tools\pull-tv-log.ps1`:

```powershell
.\gradlew.bat :app:assembleNixieStaging -Pnixie.stagingAsDebug=true
# app\build\outputs\apk\nixie\staging\app-nixie-staging.apk: io.github.mbustos27.nixie.tv.debug, the debug key
```

It is not debuggable, so `run-as` and a debugger don't work on it, and it carries every ABI (the release keeps ARM
only). Without the property it is `io.github.mbustos27.nixie.tv.staging`, beside the dev build. Like debug, it never
checks for updates by itself.

Before `adb install -r`, compare the installed app's certificate with the new APK's (`apksigner verify
--print-certs`): Android refuses an update signed with another key, and the only way around that is an uninstall,
which loses the app's sign-ins. Going back to a debug build of the same versionCode is an `adb install -r` too. After
installing, open it once (the profile installer puts the baseline profile in place), then
`adb shell cmd package compile -m speed-profile -f io.github.mbustos27.nixie.tv.debug`: the box compiles it now, as it
otherwise would at its next idle maintenance.

## Pulling the log from a box

From a PC, one script copies a dev or staging build's log into a file, with no steps on the TV: the report Download
log serves (the device summary, then the log with server addresses and names, user names and tokens replaced).

```powershell
.\tools\pull-tv-log.ps1 -Device <host:port>           # a box over Wireless debugging
.\tools\pull-tv-log.ps1 -Device emulator-5554         # the emulator
```

1. Without `-Device`, it reads the box's address from a local file outside the repository (`-BoxFile`); the
   repository never holds a box's address. If the connection fails, it stops and says Wireless debugging's port has
   probably changed: Android picks a new port when Wireless debugging is turned off and on, or the box restarts.
2. It sends `io.github.mbustos27.nixie.tv.debug.WRITE_LOG` to the dev build. The receiver (`AdbLogReceiver`, in
   `app/src/debug`, which the release-like staging build also takes; in no release or store build) takes it only from
   a sender holding `android.permission.DUMP`, the adb shell, and writes `files/diagnostics/nixie-tv-log.txt` in the
   app's own storage. Nothing leaves the box by itself.
3. It waits for the file (60 s, `-TimeoutSeconds`) and copies it with `adb exec-out content read --uri
   content://<package>.adblog/log` (`AdbLogProvider`, beside the receiver, also only for a caller holding DUMP:
   `run-as` would read the app's storage only on a debuggable build, and staging is not one) to
   `nixie-tv-log-<yyyyMMdd-HHmm>.txt` in a folder outside the repository (`-Out`, never one inside a repository),
   removes it on the box and disconnects.
4. It prints the file's path, size and line count, never the log.

On a release build the script says so, and the log comes from Settings › System › Diagnostics › Download log.

## Testing the updater locally

Debug builds can read their releases from a local copy instead of GitHub, also from a `file://` address inside the
app's own storage. They don't check at start, so use Settings › System › Check for updates:

```sh
# 1. The "new" version: note its SHA-256 and size for the feed.
./gradlew :app:assembleNixieDebug -Pnixie.version=1.0.1
cp app/build/outputs/apk/nixie/debug/app-nixie-debug.apk Nixie-TV-1.0.1.apk

# 2. The installed version, reading the local feed.
pkg=io.github.mbustos27.nixie.tv.debug
./gradlew :app:assembleNixieDebug -Pnixie.version=1.0.0 "-Pglacier.updateFeed=file:///data/user/0/$pkg/files/updtest/releases.json"
adb install -r app/build/outputs/apk/nixie/debug/app-nixie-debug.apk

# 3. Feed and APK into the app's storage.
adb push releases.json Nixie-TV-1.0.1.apk /data/local/tmp/
adb shell "run-as $pkg mkdir -p files/updtest && run-as $pkg cp /data/local/tmp/releases.json /data/local/tmp/Nixie-TV-1.0.1.apk files/updtest/"
```

`releases.json` has the shape of the GitHub API response: `tag_name`, `prerelease`, `published_at`, `body` and
`assets` with `name` (`Nixie-TV-1.0.1.apk`), `browser_download_url` (here a `file://` address in `files/updtest/`),
`size` and `digest` (`sha256:<hex>`).
