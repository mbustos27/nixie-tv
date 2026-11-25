# Nixie for TV

**Nixie for TV is a Jellyfin app for Android TV and Google TV: Nixie's themes on the big screen, with a player built
for the remote.** It is the TV sibling of [Nixie for Windows](https://github.com/mbustos27/nixie).

**Nixie for TV is based on [Glacier](https://github.com/Glacier-Jellyfin/glacier-androidtv) by its authors, under the
GPL-3.0.** Glacier brings the app itself: sign-in, libraries, item pages, search, the Media3 player with its track
rules, skips, trickplay and next-episode countdown, profiles, music, photos and live TV. Nixie adds its themes as a
layer over Glacier's screens, and its own features on top. Glacier's own README, store listings and website belong to
Glacier; see its repository. Please don't report problems in Nixie for TV to Glacier's authors: they can't fix Nixie.

## Install

| You need | |
|---|---|
| A TV box or TV | Android TV or Google TV, Android 9 or newer, an ARM processor (32- or 64-bit) |
| A server | A Jellyfin server, version 12.0 or newer, and an account on it |

Nixie for TV comes from this repository's [releases](https://github.com/mbustos27/nixie-tv/releases) only; it is not
in an app store. Each release has three files:

- **`Nixie-TV-<version>.apk`**, the app.
- **`Nixie-TV-<version>.apk.sha256`**, its checksum ([SECURITY.md](SECURITY.md) shows how to compare them).
- **`nixie-tv.apk`**, the same app under a name that never changes, so
  `https://github.com/mbustos27/nixie-tv/releases/latest/download/nixie-tv.apk` is always the newest release.

**On the TV:** type `is.gd/nixietv` into Downloader or a similar downloader app, or enter the code `6673217` in
Downloader. Either way you get the newest release (Android asks once to let that app install apps). Or install the APK
with a file-manager app on the box, or from a computer with `adb install Nixie-TV-<version>.apk`. It installs beside
Glacier and doesn't share its settings.
Fire TV comes later ([docs/FIRE-TV-PLAN.md](docs/FIRE-TV-PLAN.md)).

**Updates:** Nixie checks for a new release at every start (Settings › System › Check for updates automatically turns
that off; Check for updates checks at once). It asks GitHub's releases API for this repository's releases and nothing
else, and sends nothing about you beyond what any web request carries: no server, account, library or device facts. A
new release shows its notes and waits for you; nothing downloads or installs until you choose Update now. Nixie takes
only `Nixie-TV-<version>.apk`, and checks its SHA-256 against the digest GitHub publishes, its package name and its
version before installing. Android then refuses any update that isn't signed with the same key as the installed app.

## The themes

Settings › Appearance › Theme switches at once, and your place is kept.

- **Drive-in**, the default: a desert-sunset palette, warm glass on the bar and buttons, a two-tone neon focus ring,
  and nixie tubes for numbers.
- **Cinematic**: a night-dark page, glass on the bar, and a white-rimmed focus frame in a wide glow, easy to follow from
  the couch.
- **Editorial**: an ink page with serif headings, flat tints instead of glass, and a focus frame like a printer's
  double rule.

Debug builds also offer Glacier's own look, for comparing.

## What it does

- **Home** shows Continue watching, then Up next.
- **Big libraries open fast:** the first page shows while the rest loads, and the A–Z rail jumps without reading the
  whole library first.
- **Search** shows each kind of result as it arrives.
- **Playback** on the box's own decoders: 4K, HDR10 and Dolby Vision where the TV shows them, DTS and TrueHD decoded
  in the app when the box can't, styled ASS subtitles, trickplay, frame-rate matching and the next-episode countdown.
  The player lets go in standby and comes back paused where you were.
- **Skips:** intros and credits from the server, from the file's chapters, and learned from what you skip by hand.
  Looking them up online stays off until you turn it on.
- **Continue watching:** hold OK on a title to remove it, with Undo for 10 seconds. The same row can show on the TV's
  home screen, where the home screen supports it.
- **Trailers** from the server, or from YouTube.
- **Profiles** with PINs and age limits, Quick Connect, music, photos, live TV with a guide, and requests where the
  server offers Seerr.
- **English, German and Spanish**, following the TV's language.

## Report a problem

1. On the TV, open **Settings › System › Diagnostics**. For a playback problem, turn on **Detailed logging** first and
   make the problem happen again.
2. Choose **Report a problem** and scan the code with a phone. It opens the **Report a problem** form here, with the
   app's and the server's versions filled in. The TV sends nothing.
3. For the log, choose **Download log on the network** and scan that code with a phone on the same network. The phone
   saves the log as a text file (server addresses and names, signed-in users' names, passwords and access tokens are
   taken out first; [PRIVACY.md](PRIVACY.md#logs-and-report-a-problem) says what stays). Drag it into the form.

Without a phone, [open an issue](https://github.com/mbustos27/nixie-tv/issues/new/choose) with the **Report a problem**
form. **Send log to the server**, in the same place, sends the log to the server admin's dashboard, not here.

## Privacy, in short

No account, analytics, ads or telemetry. What leaves the box:

- **The Jellyfin server you use** gets what any Jellyfin app sends: sign-in, what you play and how far, watched marks
  and favourites, and a copy of your Nixie settings.
- **The TV's home screen** gets your Continue watching titles, as Android TV apps do (Settings › Home turns it off).
- **YouTube:** on a page with a YouTube trailer, Nixie loads YouTube's player empty; YouTube gets the video's id only
  when you press Trailer.
- **GitHub** is asked for new releases at every start (you can turn it off), with nothing about you.
- **Online skip lookups** send a show's public id, the episode's number and its length, only once you turn them on.
- **Logs** leave the box only when you fetch or send one.

All of it, with what each service gets: [PRIVACY.md](PRIVACY.md).

## Build

Needs the Android SDK (platform 37) and JDK 21 (Android Studio's own works), set for the shell only:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:assembleNixieDebug testDebugUnitTest :app:testNixieDebugUnitTest
# app\build\outputs\apk\nixie\debug\app-nixie-debug.apk
```

- One flavor, `nixie`: application id `io.github.mbustos27.nixie.tv`, debug builds `.debug` beside it.
- The version is `nixie.version` in `gradle.properties`, versionCode by Glacier's rule
  (major × 1,000,000 + minor × 10,000 + patch × 100 + beta, 99 for a stable release).
- The FFmpeg audio decoder (DTS, TrueHD) needs its native part built first: [core/ffmpeg/README.md](core/ffmpeg/README.md).
- Debug builds switch themes with `adb shell am start -n io.github.mbustos27.nixie.tv.debug/io.github.glacier_jellyfin.androidtv.MainActivity --es nixie.theme Cinematic`.

## Development

- [CONTRIBUTING.md](CONTRIBUTING.md): how to report, suggest and send changes.
- [docs/DESIGN-RULES.md](docs/DESIGN-RULES.md): what every change and bug hunt looks for (speed on big servers,
  standby, device facts, the way up from any page, the user is not the server's admin).
- [docs/GLACIER-BASE.md](docs/GLACIER-BASE.md): what Glacier covers and what Nixie adds.
- [DIVERGENCE.md](DIVERGENCE.md): every change to a Glacier file.
- [docs/THEMES.md](docs/THEMES.md): how the themes plug into Glacier's screens.
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): Glacier's module layout, which Nixie keeps.
- [testing/README.md](testing/README.md): the fake Jellyfin server's profiles. Tests never touch a real server.
- `tools/release-check`: `nixie-release`, the release checks (signature, manifest, versionCode, ABIs, the FFmpeg
  decoder, alignment, size, `.sha256`). It never signs and never reads a key.

## Glacier's updates

`upstream` is Glacier, fetch only. Each Glacier release that builds is merged with `git merge`; Nixie's changes stay
in its own modules and in hooks marked `// Nixie:` (the rule: [docs/GLACIER-BASE.md](docs/GLACIER-BASE.md#merging-glaciers-updates)).

## Licence

GPL-3.0-or-later ([LICENSE](LICENSE)), as Glacier. Every copyright and REUSE notice is kept: Glacier's files are
"Glacier contributors", Nixie's own files "Nixie contributors" ([REUSE.toml](REUSE.toml)); third-party parts keep
their licences, with the texts in [LICENSES/](LICENSES/). Nixie for TV is not affiliated with the Jellyfin project or
with Glacier's authors. Android TV and Google TV are trademarks of Google LLC.
