# Nixie for TV on Glacier: what Glacier gives, what Nixie adds, the new estimate

Since 2026-11-21 Nixie for TV is a local fork of [Glacier](https://github.com/Glacier-Jellyfin/glacier-androidtv)
v0.10.1 (GPL-3.0-or-later; Kotlin, Compose for TV, Media3 1.11.1, Jellyfin SDK 1.9.4), rebranded, with Nixie's
themes as a layer over it (`docs/THEMES.md`, `DIVERGENCE.md`). This page measures Glacier against the TV plan
(`docs\reports\Nixie Google TV architecture.md` in the Windows repo) and lists what Nixie still has to add.

In Glacier paths below, `…/` is `src/main/java/io/github/glacier_jellyfin/androidtv/`.

## One change of plan: Jellyfin 12 only

Glacier uses SDK 1.9.4 and supports **Jellyfin 12.0 and newer** (`core/jellyfin/…/core/jellyfin/ServerInfo.kt`);
older servers show greyed out and connect only after a confirmation. The plan pinned SDK 1.8.12 to serve 10.11 too.
The servers Nixie was checked on run 12.x, so this costs nothing today. Tests and the basics gate run against Jellyfin
12.4 (the fake server's `jellyfin-12` profile and the gate's throwaway servers); the `jellyfin-10.11` and
`jellyfin-10.10` profiles and the 10.11.11 QA server are below the minimum, and no app test or gate check uses them.

## The plan's phases 1 to 4 against Glacier

| Plan item | Glacier | Evidence |
|---|---|---|
| Network and version probe | Done (minimum 12.0) | `core/jellyfin/…/ServerInfo.kt` |
| Token store | Done | `core/data/…/KeystoreCipher.kt`, `EncryptedJsonSerializer.kt` |
| Websocket (rows patched from `UserDataChanged`) | Missing | nothing in the sources |
| Quick Connect | Done, one device id for the whole install | `Authenticator.kt`, `app/…/setup/QuickConnectScreen.kt` |
| Password sign-in | Done | `app/…/setup/SignInScreen.kt` |
| Home with an offline cache | Partly: loads live, no Room | `core/data/…/media/HomeRepository.kt` |
| Hero, rows | Done (hero source differs, below) | `app/…/home/Spotlight.kt`, `HomeScreen.kt` |
| Continue Watching cleanup with Undo | Missing | |
| Focus and Back rules | Done: Back from a row goes to the top and the bar; Back on Home asks before quitting | `HomeScreen.kt`; CHANGELOG 0.6.2 |
| Libraries with A–Z, sort, filter | Done | `app/…/library/LibraryScreen.kt`, `SortControls.kt` |
| Search with the microphone | Partly: on-screen keys; voice through the system keyboard | `core/designsystem/…/SystemTextInput.kt` |
| Movie, series, season, episode pages | Done | `app/…/detail/DetailScreen.kt` |
| Play label | Partly: "Resume · N min left" or "Play" | `DetailScreen.kt` |
| Device profile, PlaybackInfo | Done | `core/jellyfin/…/playback/DeviceProfiles.kt`, `core/data/…/playback/PlaybackRepository.kt` |
| FFmpeg decoder (DTS, TrueHD on boxes without them) | Done in code; the native part needs building (below) | `core/ffmpeg` |
| Styled ASS subtitles | Done, with a burn-in setting | `core/player/…/GlacierPlayer.kt` |
| Track rules | Done | `core/player/…/TrackControl.kt`, `core/data/…/media/TrackSelections.kt` |
| Playback reporting | Done, one Stopped per session; the credits rule partly | `PlaybackRepository.kt` |
| Skips | Partly: the server's media segments only | `PlaybackRepository.kt` |
| Next-episode countdown | Done (local setting) | `app/…/player/PlayerPrompts.kt` (`UpNextCard`) |
| Trickplay | Done | `app/…/player/PlayerOsd.kt` |
| Frame-rate and audio-output settings | Done | `app/…/player/FrameRateMatch.kt`; `HdAudioOutput` |
| MediaSession | Partly: music only, not video | `app/…/music/MusicService.kt` |
| Baseline profile, R8 | Partly: R8 and resource shrinking, no baseline profile | `app/build.gradle.kts` |
| Low-RAM check | Missing | |
| Watch Next | Done, for home screens that show it (Projectivy, Plume, LtvLauncher); Google TV's own filters uncertified apps (phase 0). Nixie's Continue watching then Up next, one profile's rows (gone at a sign-out or a switch), positions as they play, Remove and Undo, a setting (stream W, `DIVERGENCE.md`) | `app/…/channels/HomeChannels.kt`, `nixie/tv/channels/NixieWatchNext.kt` |
| Release checks | Nixie's `tools/release-check` (Glacier releases through its CI, removed here) | |
| Update checker | Done; points at Nixie's future releases (`mbustos27/nixie-tv`), no check at start; picks only `Nixie-TV-<version>.apk` (its `.sha256` beside it), never Glacier's names | `core/updater/…/ReleaseSelector.kt` |

Beyond the plan, Glacier also brings profiles with PINs and age limits, music, photos, live TV with a guide, Seerr
requests, trailers, a playback check of the box's formats, and log sharing to the user's own server.

## What Nixie still adds

### The looks

The three themes (Drive-in the default, Cinematic, Editorial) as tokens and slots over Glacier's screens
(`docs/THEMES.md`). Phase 1 covers Drive-in's sign-in, top bar and Home; libraries, item pages and the player's chrome
need new slots in phase 2.

### Nixie desktop's features that Glacier lacks (the gap list)

Sizes: S under half an agent-day, M about a day, L more.

| Feature | Nixie desktop | Glacier v0.10.1 | Size |
|---|---|---|---|
| Remove from Continue Watching, with Undo | Remove sets the position to 0; Undo for about 10 s unless newer progress came in (1.2.0); Reset progress with Undo (1.5.0) | No remove action (`HomeRepository.kt` only lists) | M |
| Hide stale Continue Watching | Not played for 30, 60 or 90 days (1.2.0) | Missing | S |
| Continue Watching and Up next as two rows | Each title shows once; Next Up looks back 365 days (1.2.0, 1.4.1) | One merged row (`mergeContinueWatching`), no `nextUpDateCutoff` | S |
| Rewatching in Up next | With a Settings switch (1.4.1, 1.5.0) | No `enableRewatching` | S |
| Hero rule | New unwatched titles, then Next Up, never Continue Watching (1.2.0) | Default source is Continue Watching (`ProfileSettings.kt`) | S |
| Row order from the server | `OrderedViews` and `LatestItemsExcludes` | Excludes yes; order is a local setting | S |
| Rows updated live from `UserDataChanged` | Progress, watched and favourite patched in place | No websocket; Home refreshes after the player (`HomeViewModel.kt`) | M |
| Four-source skips | Server segments, chapter names, online lookups (opt-in), learned skips; split-season fix (1.5.0); learned skips not offered where the intro moves (1.5.1) | Server segments only | L (chapters S, online M, learned M) |
| Credits count as watched | Stopping inside closing credits that run to the end counts as watched (1.2.0) | Play next and Skip credits mark it played; Back reports the raw position (`PlayerViewModel.kt`) | S |
| The server's autoplay setting | `EnableNextEpisodeAutoPlay` (1.2.0) | Local `UpNextMode` only | S |
| Next-episode details | Next episode leads, Watch credits beside it, countdown in place of Skip credits (1.2.0, 1.4.0) | Done (`UpNextCard`); only its theming is left | S |
| Play labels | "Resume S1 E4", "Play S2 E1", "Play from the start", "S1 E4–5" (1.2.0) | "Resume · N min left" or "Play"; season and episode on the hero only | S |
| A device id per sign-in | One per copy (1.5.1); the plan wants a new one per Quick Connect attempt, since the server keeps one token per device id | One device id for the install, shared by all profiles (`core/jellyfin/…/JellyfinModule.kt`) | M |
| Undo after marking a series or season watched | Yes (1.2.0) | No Undo | S |
| Search ranking | Best match first, accents and punctuation ignored, type chips, "Show N more", a person opens "With <name>" (1.5.0) | The server's search plus people and genres, merged (`SearchRepository.kt`) | M |
| Tidy names | "[HUB] Marvel" shows as "Marvel" (1.6) | Names as the server gives them | S |
| Favourite libraries in the top bar | Stars (1.6) | Entries can be hidden and reordered | S |
| Made by Nixie rows, smart collections | "Highly rated, not watched", "Unfinished series", a seasonal row (1.6) | Missing | M rows, L collections |
| Quality choice and playback stats | In the player, home and away defaults, a stats panel (1.6) | Max bitrate setting, automatic steps, an Info panel | S |
| Skip rules for repeated extras | Per-show rules with a margin (1.6, in progress) | Missing | L, after 1.0 |

Already equal: Home refreshing after the player, one Stopped report per playback, big libraries in pages, trailers,
Seerr in search. Left out as desktop-only: the window, the installer updater and its Sandbox test, the mpv bridge,
keyboard shortcuts and Narrator, the Windows certificate store, server administration, the Diary.

### The FFmpeg decoder's native part

`core/ffmpeg` builds `libffmpegJNI.so` when FFmpeg's headers and static libraries sit in
`core/ffmpeg/src/main/jni/ffmpeg/` (gitignored). On this PC (2026-11-21): Glacier's `ffmpeg-android` CI artifact
(FFmpeg 8.1.3, AAC, AC-3, E-AC-3, TrueHD, DTS, FLAC, ALAC, Vorbis, Opus, MP3; 18 MB, run 44706370663, the same
`build-ffmpeg.sh` as v0.10.1) unpacked there, with NDK 28.2.13676358 and CMake 3.31.6 from sdkmanager. Debug builds
now carry the decoder for all four ABIs; release builds keep `armeabi-v7a` and `arm64-v8a` only. A common 4K Google
TV box has no DTS passthrough, so a release needs it, and `nixie-release check` fails a release without it. A fresh
clone repeats those two steps (`core/ffmpeg/README.md`).

## The phases, re-estimated (agent-days)

| Phase | Was | Now | What is left |
|---|---|---|---|
| 1. Foundation | 4–5 | 2–3 | Done 2026-11-21: theme contract, Drive-in sign-in, top bar and Home, the emulator exit check (Quick Connect by D-pad against the fake server, Home in Drive-in, Back rules). Left, in the feature streams: a device id per Quick Connect attempt, Continue Watching cleanup with Undo, live row updates over the websocket |
| 2. Browse and player | 7–9 | 4–6 | Drive-in slots for libraries, item pages and the player's chrome; the FFmpeg build (done 2026-11-21); Play labels, the credits rule, the server's autoplay setting; the skip chain beyond segments; video MediaSession; voice search |
| 3. Cinematic, Editorial, switching | 4–6 | 3–5 | Both themes on the same slots (started 2026-11-21), Settings > Appearance with the picker, glass tiers, screenshots at two font scales |
| 4. Harden and release | 3–4 | 2–3 | Baseline profile, the low-RAM check on the Chromecast, the updater on Nixie's asset names, a reviewer pass, the owner's couch pass |
| Nixie's own features | (in 1–2) | 3–4 | The gap list's S and M items not named above |
| **Total** | **19–25** | **14–21** | About 4–6 calendar days with parallel streams |

## Merging Glacier's updates

- Merge `upstream/main` at each Glacier release that builds: `git fetch upstream --tags`, then
  `git merge <tag>` (or `upstream/main`). `upstream`'s push URL is `DISABLED`; nothing is ever pushed or reported
  there.
- Nixie's changes stay in Nixie's own files and modules (the theme layer, `app/src/nixie`, `tools`, `testing`) or in
  small hooks marked `// Nixie:`. A conflict should only ever touch a hook line.
- After each merge: build `:app:assembleNixieDebug`, run the unit tests, check `aapt2 dump strings` shows "Glacier"
  only in the credit (regenerate `app/src/nixie/res/values*/strings.xml` if Glacier added strings naming itself),
  keep deleted CI files deleted, and update `DIVERGENCE.md` (the base tag at its top, and any hook that moved).

## Hardening for TV 1.0 (phases 3 and 4, 2026-11-21)

- **Large text:** Glacier's theme now follows the TV's text size up to 1.3; every theme's shots run at 1.0 and 1.3.
- **Glass tiers:** `NixieVisuals` picks Live, Fake or Flat from the box's memory at start. 2 GB boxes (1.9 GB usable)
  and the 2 GB Chromecast get Fake: no live blur and no soft shadows, pre-blurred (96 px) pictures where a blur was.
  Flat (low-RAM devices) also stops the backdrops' slow zoom.
- **Low memory:** on a 2 GB emulator (the Fake tier, detected), Home, a library, a movie and a series page held at
  about 185 MB PSS with no out-of-memory kill. Frame timing on the emulator's software GPU says nothing about a box;
  the owner's TV session reads `dumpsys gfxinfo` there.
- **Baseline profile:** `:baselineprofile` generates start-up and Home on the emulator against the TV test server
  (`testing/emulator/Approve-QuickConnect.ps1` approves its sign-in); the release APK carries it
  (`assets/dexopt/baseline.prof`). Regenerate after big UI changes with
  `.\gradlew.bat :app:generateNixieReleaseBaselineProfile`.
- **Updater:** assets `Nixie-TV-<version>.apk` and `.sha256`, the same names in nixie-release; no check at start; the
  feed on `mbustos27/nixie-tv` (not made yet).

