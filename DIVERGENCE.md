# Where Nixie for TV differs from Glacier

Nixie for TV is a fork of [Glacier](https://github.com/Glacier-Jellyfin/glacier-androidtv) (GPL-3.0-or-later).
`upstream` is Glacier, fetch only. Base: **v0.10.1** (b276936). Nixie's changes stay in its own files and modules,
or in small hooks marked `// Nixie:` inside Glacier's files, so `git merge upstream/main` stays plain. Update this
file with every change to a Glacier file and at every upstream merge.

## Nixie's own files (no merge conflicts possible)

| Path | What |
|---|---|
| `core/designsystem/src/main/java/io/github/mbustos27/` | The theme contract, the focus frame, nixie tubes and Nixie's mark (`docs/THEMES.md`) |
| `theme/drivein`, `theme/cinematic`, `theme/editorial` | The three themes |
| `app/src/main/java/io/github/mbustos27/` | The theme registry (`NixieThemes`) |
| `app/src/test/java/io/github/mbustos27/` | The theme screenshots (`ThemeShotsTest`) |
| `app/src/main/res/values*/nixie_strings.xml` | Nixie's strings, apart from Glacier's |
| `app/src/nixie/` | The rebrand: Nixie's name, banner and icon (the `nixie` flavor's resources) |
| `tools/release-check`, `testing/`, `docs/PHASE0-OWNER.md` | From Nixie's phase 0 |
| `baselineprofile/` | The baseline profile generator (start-up and Home on the TV test server) |
| `testing/emulator/` | Emulator helpers: sign in to the TV test server by Quick Connect, approve its codes for unattended runs |
| `docs/THEMES.md`, `docs/GLACIER-BASE.md`, `DIVERGENCE.md` | Nixie's docs |

## Changes inside Glacier's files

| File | Change | Why |
|---|---|---|
| `core/designsystem/.../GlacierTokens.kt` | `GlacierColors`, `Accent.main/deep` and `GlacierShapes` read the theme's tokens first; the accent's own colours are `swatchMain/swatchDeep` | Themes change colours and radii without touching screens |
| `core/designsystem/.../Controls.kt` | `PillShape` is the theme's pill when it has one | No oblong pills in Drive-in |
| `core/designsystem/.../Focus.kt` | `focusFrame` draws the theme's `FocusLook` when it has one; `focusScale` uses the theme's `focusZoom` | Drive-in's two-tone neon ring; Cinematic's 1.1 zoom |
| `core/designsystem/.../GlacierBackground.kt` | The theme's `pageGround` slot and ground colour | Drive-in's dusk ground |
| `core/designsystem/.../Mark.kt` | `GlacierMark` and `SpinningDiamond` draw Nixie's tube | No Glacier artwork in the UI |
| `app/.../GlacierApplication.kt` | Registers the themes before anything is drawn | The theme switch |
| `app/.../MainActivity.kt` | Debug builds switch themes with `--es nixie.theme <id>` | Theme checks without a picker |
| `app/.../navigation/TopNav.kt` | `topBarChrome` and `topBarEntry` slots, with an underline for the page you are on | Drive-in's marquee bar; the plan's "underline, never a pill" |
| `app/.../ui/MediaRow.kt` | The row's content goes through `nixieRowWithEnd` (Nixie's `library/NixieRowEnd.kt`): its last item is wrapped in a box whose focus properties end Right there (`end = FocusRequester.Cancel`, tv-1.0-r1's rule), so Right on any row's last card stays; `RowEndTest`, `RowEndKeyTest` | The basics gate: Right on the last card of Home's short Up next row went to the top bar's Home; every row built on `MediaRow` (Home, a title's episodes, cast and "More like this", a collection, a person) now ends the same way. A cancelled exit on the whole row passed the JVM tests but not the emulator |
| `app/.../home/HomeScreen.kt` | Each row passes its position (`rowCount`) to `MediaRow` | Numbered headers |
| `core/designsystem/.../GlacierTypography.kt` | `GlacierText.display` uses the theme's `displayFont` | Editorial's serif |
| `app/.../home/Spotlight.kt` | `heroBackdrop`, `heroFade`, `heroPositionMarks` and `heroKicker` slots; square-cornered position marks under a theme pill | Drive-in's outdoor screen; Editorial's framed picture; no oblong pills |
| `app/.../ui/Cards.kt` | `ContinueCard` draws the theme's `stillCard` inside Glacier's frame, with Glacier's lock and hold-OK marks | Cinematic's title over the art |
| `app/.../trailer/YouTubePlayer.kt` | The trailer page loads at Nixie's app-id origin (`https://io.github.mbustos27.nixie.tv/trailer`, `NixieTrailers`) with youtube-nocookie as the embed host, where Glacier claimed its own website; `trailerPage` exposes the page to a test | Nixie never presents itself as another project's site; as Nixie for Windows since 1.4.0 (approved by the owner) |
| `app/.../ui/Backdrop.kt` | `ImagePageGround` is read each time | Follows a theme switch |
| `app/.../setup/QuickConnectScreen.kt` | `quickConnectCode` slot | Quick Connect on nixie tubes |
| `app/.../setup/SetupComponents.kt` | `setupBackdrop` slot behind `SetupLayout` | Drive-in's sign-in backdrop |
| `app/.../setup/SignInScreen.kt` | Opens Quick Connect first when the server offers it (`NixieFeatures.quickConnectFirst`) | The plan: Quick Connect first, password as the fallback |
| `app/.../settings/SettingsScreen.kt` | Settings > Appearance starts with the theme picker (Drive-in, Cinematic, Editorial; Glacier's own look in debug builds only), switching live through `NixieThemes.choose` | The owner's three themes, switchable without a restart |
| `app/.../settings/SettingsScreen.kt` | Swatches show Glacier's own colours; under a theme accent the row says it does not show | Drive-in's pink is fixed |
| `settings.gradle.kts`, `app/build.gradle.kts` | The theme modules; the screenshot test's dependencies (Robolectric, Compose UI test, Roborazzi) and `testOptions.unitTests` (resources, record mode) | |
| `app/build.gradle.kts` | The `nixie` flavor: application id `io.github.mbustos27.nixie.tv` (debug `.debug`), `nixie.version`, the update feed on Nixie's releases; `UPDATE_CHECK_AT_START` true for the release build type only (debug and staging false) | The rebrand; nothing calls Glacier's releases. The check at start from 1.0 (the owner, 2026-11-25), never in test builds, so the gate and the box's checks never meet the update dialog |
| `app/build.gradle.kts` | Release builds keep `armeabi-v7a` and `arm64-v8a` only (debug keeps every ABI) | The release checker's rule; 32-bit boxes |
| `build.gradle.kts`, `app/build.gradle.kts`, `settings.gradle.kts` | The baseline profile: the `androidx.baselineprofile` plugin (1.5.0), `profileinstaller`, the `:baselineprofile` module; its builds signed with the debug key | Start-up and Home compiled ahead of time (the plan's phase 4) |
| `gradle.properties` | `nixie.version=1.0.0`, versionCode 1000099 by Glacier's rule (major × 1,000,000 + minor × 10,000 + patch × 100 + 99 for a stable release) | Nixie for TV 1.0 (tv-1.0-prep, 2026-11-25). The test builds before it were 0.1.0 (10099) and 0.1.1-beta.1 to beta.4 (10101 to 10104, the last given on the command line), so 1.0.0 installs over every one of them as a new version |
| `app/src/nixie/res` | Every string that names Glacier, renamed (en, de, es: generated from Glacier's strings), the window colours, the banner and the icon | No Glacier name or artwork in the UI. **After an upstream merge, regenerate the strings** if a new one names Glacier (`aapt2 dump strings` on the APK must show "Glacier" only in the credit lines) |
| `app/.../AppModule.kt` | Client name "Nixie for TV"; updater check at start from `UPDATE_CHECK_AT_START` | The server's device list; the release checks at start, test builds only when asked |
| `core/jellyfin/.../DeviceProfiles.kt` | Profile name "Nixie for TV" | The server's view |
| `core/updater/.../ReleaseSelector.kt` | `nixieApkName`: only `Nixie-TV-<version>.apk` is picked, never Glacier's `glacier-androidtv*.apk` | A Glacier release can never be offered to Nixie; the name nixie-release checks |
| `core/updater/.../UpdateManager.kt` | `UpdaterConfig.autoCheckDefault` | The app module decides the check at start's default per build type |
| `core/updater/.../ReleaseFeed.kt`, `ApkDownloader.kt` | User-Agent "Nixie" | The app's name |
| `core/updater/src/test/.../ReleaseFeedTest.kt`, `ReleaseSelectorTest.kt` | The feed test names the asset as Nixie's; the selector tests pick `Nixie-TV-<version>.apk` and never Glacier's names | The tests of `ReleaseSelector`'s change above |
| `REUSE.toml` | An entry for Nixie's own files ("2026 Nixie contributors", precedence "override") and, after it, one giving Glacier's and Nixie's lines to files that began as Glacier's (the rebrand's strings, the themes' `StandIns.kt`); `testing/licences/test_reuse_toml.py` checks both | Glacier's copyright stays wherever its code does |
| `app/.../diagnostics/Diagnostics.kt` | The log's first line and file name say Nixie | The rebrand |
| `app/.../settings/SettingsScreen.kt` | About: source on `github.com/mbustos27/nixie-tv`, and a "Based on Glacier" row | The licence credit |
| `.github/` | Deleted: `release.yml`, `stores.yml` (Amazon, Play), `pages.yml` (Glacier's website), `scorecard.yml` (publishes results), `dependabot-automerge.yml`, `FUNDING.yml`, `CODEOWNERS` (Glacier's maintainer) | Nothing publishes or pings Glacier from Nixie. On a merge, a change upstream made to one of these is a modify/delete conflict: keep it deleted |
| `app/.../library/LibraryScreen.kt` | `libraryHeader` and `letterRail` hooks; the header's second line moved into `headerLine` | Phase 2 theme slots |
| `app/.../ui/FilterChip.kt` | `chip` look (colours, underline for the active chip) | Phase 2 theme slots |
| `app/.../ui/Cards.kt` (`GridCard`, `PosterCard`) | `posterArt` slot in place of the bare artwork | Phase 2 theme slots |
| `app/.../ui/Backdrop.kt` (`DetailBackdrop`) | `item.backdrop` slot | Phase 2 theme slots |
| `app/.../detail/DetailScreen.kt` | `crumb`, `title` slots; `nixiePlayArea()` on both Play rows | Phase 2 theme slots |
| `app/.../ui/Facts.kt` | `facts` slot | Phase 2 theme slots |
| `app/.../detail/DetailComponents.kt` (`EpisodeCard`) | `episodeStill` slot | Phase 2 theme slots |
| `app/.../player/PlayerScreen.kt` | `osdScrim` slot; Glacier's gradients in `glacierScrim` | Phase 2 theme slots |
| `app/.../player/PlayerOsd.kt` | `seekBar` and `times` slots; `OsdGlass` and `OsdSecondary` read each time (the theme's grey) | Phase 2 theme slots |
| `app/.../player/PlayerPrompts.kt` | `skipButton`, `upNextCountdown` slots; Up next's container through `nixiePanelChrome` | Phase 2 theme slots |
| `core/designsystem/.../OptionsPanel.kt` | The panel's fill through `nixiePanelChrome` | Phase 2 theme slots |
| `core/designsystem/.../GlacierTheme.kt` | Keeps the system's font scale (Glacier forced 1.0), up to 1.3 | Large text reaches TalkBack and low-vision users; the plan's "layouts reflow at system font scales" |
| `app/.../detail/DetailComponents.kt` (`EpisodeCard`) | The overview's box is three times the font size (two lines at 1.5 line height) instead of a fixed 48 dp | At large text the fixed box held one line |
| `core/designsystem/.../Focus.kt` (`focusRing`) | The soft drop shadow only on the Live glass tier (`NixieVisuals`) | No offscreen blur on 2 GB boxes |
| `app/.../navigation/TopNav.kt` | Glacier's bar shadow through `nixieShadow` | The same |
| `app/.../ui/Backdrop.kt` (`KenBurns`) | Still on the Flat tier, as with "reduce motion" | Low-RAM boxes |
| `app/.../music/MusicPlayerScreen.kt` | The cover's live blur only on the Live tier; elsewhere a pre-blurred (96 px, scaled up) cover | Never live blur on 2 GB boxes |
| `.gitignore` | HANDOFF, docs/history, release-check's target | Agent files never committed |
| `README.md` | Nixie's own README, with the credit to Glacier | Glacier's README markets Glacier's builds. On a merge, keep Nixie's |
| `SECURITY.md` | Nixie's own: report privately through the repository's private vulnerability reporting, the supported version, how to check a download's `.sha256` | Glacier's pointed Nixie's users to Glacier's advisories. On a merge, keep Nixie's |
| `.github/workflows/ci.yml`, `cflite-batch.yml`, `security.yml` | ci.yml runs the app's own tests (`:app:testNixieDebugUnitTest`, tv-1.0-prep) and lint (`lintNixieDebug`) and checks the nixie flavor's staging APK; the weekly schedules of fuzzing and CodeQL are gone (by hand, and CodeQL on pushes and pull requests) | Actions are off until the fork has its repository; nothing runs by itself, and CI works when they are on |

## Home stream: Home, Continue Watching, sign-in data

Nixie's own files: `core/jellyfin/src/main/java/io/github/mbustos27/nixie/tv/jellyfin/` and
`core/data/src/main/java/io/github/mbustos27/nixie/tv/data/`,
`app/src/main/java/io/github/mbustos27/nixie/tv/home/` and `app/src/main/res/values*/nixie_home_strings.xml`, with their tests
beside them.

| File | Change | Why |
|---|---|---|
| `core/jellyfin/.../Authenticator.kt` | A fresh device id per Quick Connect attempt (fixed before Initiate) and per password sign-in, carried in `SignedInUser.deviceId`; `signOut`, `tokenUserId`, `isTokenValid` take the profile's id | The server keeps one session and token per device id: profiles on one TV no longer share one |
| `core/jellyfin/build.gradle.kts` | Unit tests: Android stubs return defaults, slf4j on the test classpath | The SDK under JVM tests |
| `core/data/.../AccountState.kt` | `StoredUser.deviceId` (null for sign-ins from before) | The profile keeps its token's id |
| `core/data/.../AccountStateUpdates.kt` | `withSignIn` stores the sign-in's device id | As above |
| `core/data/.../AccountRepository.kt` | `pinDeviceId` | Migration: a sign-in from before keeps the install's id, written down |
| `core/data/.../SessionManager.kt` | The session's client goes out as the profile's id; `Session.installDeviceId`; sign-out with the profile's id | As above |
| `core/data/.../settings/SettingsSync.kt`, `app/.../settings/SettingsViewModel.kt` | Settings copies keyed by `installDeviceId` | A copy belongs to the TV, not to one sign-in |
| `core/data/.../media/HomeScreenRepository.kt`, `app/.../profiles/ProfilesViewModel.kt`, `app/.../setup/ServerConnector.kt` | The profile's device id with its token | As above |
| `core/data/.../media/HomeRepository.kt` | Reads `SettingsRepository`; Next Up asked 365 days back with `enableRewatching`; Continue Watching and Up next made by `ContinueWatchingRules` | Two rows, each title once; stale items hidden (a setting) |
| `core/data/.../media/MediaModels.kt` | `HomeContent.upNext` | The Up next row |
| `core/data/.../settings/ProfileSettings.kt` | `ProfileSettings.nixieHome` (`NixieHomeSettings`) | The home stream's settings, apart from Glacier's |
| `app/.../home/HomeViewModel.kt` | `ContinueWatchingRemovals` (remove, Undo, notices); loads go through `nixieRows` (stream H, below) | Remove from Continue watching with an Undo |
| `app/.../home/HomeScreen.kt` | Back to Home restores the card left from, also with Home still composed (`rememberReturns`), asking over a few frames (`requestFocusWhenReady`); hold OK on a Continue watching card opens `NixieRemoveSheetHost` (the held key ignored, Cancel focused, the focus back on the card or its Undo card after); a removed card is `NixieUndoCard` for 10 s; `nixieUpNextRow` after the Continue watching row; Undo hands the focus to the card it puts back (`rememberFocusHandoff`); Glacier's "back to the first card" effect gives way to `NixieKeepRowsFocus`, one hook for every Nixie row (Continue watching, Up next): the focus stays on the same card or its neighbour, no scroll back | As above; Up next shows and hides with the Continue watching row in Settings |
| `app/.../settings/SettingsScreen.kt`, `SettingsViewModel.kt` | A "Continue watching" group after Home's rows: hide stale items (never, 30, 60, 90 days), rewatching in Up next | The two settings |
| `app/.../home/HomeViewModel.kt` | The hero comes from `HeroRepository` (new unwatched titles, then Up next, never Continue watching), is made again on every refresh, and falls back to Up next | The hero rule |
| `app/.../settings/SettingsScreen.kt` | Settings › Home › Hero: an on/off switch in place of the spotlight's sources and "only unwatched" (`nixieHeroRows`) | The rule picks the titles |
| `core/data/build.gradle.kts` | OkHttp 5.5.1 (the SDK's own) as a direct dependency; unit tests: Android stubs return defaults, slf4j on the test classpath | `OkHttpSocketLink`, the server's websocket; the SDK under JVM tests (`SessionDeviceIdTest`) |
| `app/.../home/HomeViewModel.kt` | Starts `LiveUpdates`; each `UserDataChanged` patches the rows and the hero in place (`withUserData`), ends a removal's Undo when newer progress came, and asks for a quiet reload (at most one every 10 s) when a card must come or go | Rows follow the server live |
| `app/.../detail/DetailViewModel.kt` | Marking a show or season watched first notes its episodes (`WatchedUndo`); the same choice again within 10 s puts each back as it was, unless played since | Undo after marking a series or season watched |
| `app/.../home/HomeViewModel.kt` | After each load, `ServerOrder.update`: when the server's library order (`OrderedViews`, as `UserViews` hands it out) changes, the "New in" rows and "My media" cards follow it | Row order from the server |
| `core/data/.../media/MediaMapper.kt` | A box set's title goes through `TidyNames.collection` | Tidy collection names ("[HUB] Marvel" reads "Marvel"); films, shows, episodes and playlists untouched |
| `core/data/.../media/HomeRepository.kt` | Library names go through `TidyNames.libraries` (the server's names back where two would read the same) | Tidy library names: "New in", "My media", Settings |
| `app/.../navigation/TopNav.kt` | `NavTarget.Library` takes a `libraryId` and `name`; the entries go through `nixieTopBar`: up to three starred libraries in place of Movies and Shows, by name | Favourite libraries in the top bar (desktop 1.6); none starred keeps the entries by kind, never "[HUB]" hubs |
| `app/.../navigation/NavBar.kt`, `app/.../library/LibraryScreen.kt` | A starred entry opens its own library, whose page lights that entry (`nixieLibraryTarget`); in `TopNav.kt` selecting it again there does nothing (`isOwnStarredPage`) | The same; Back takes one press |
| `app/.../settings/SettingsScreen.kt` | Settings › Home › Top bar: a star per library, three at most (`nixieTopBarGroups`), kept per server and profile in `NixieHomeSettings.topBarLibraries` | The same |

## The play stream's changes (skips, the end of a title, Play labels, search)

Nixie's own files: `core/data/src/main/java/io/github/mbustos27/` (skips in `data/skips`, rules and the settings key
in `data/play`, their tests beside them in `src/test`), `app/src/main/java/io/github/mbustos27/nixie/tv/play/`,
`app/src/main/res/values*/nixie_play_strings.xml` and `app/src/test/java/io/github/mbustos27/nixie/tv/PlayShotsTest.kt`.

| File | Change | Why |
|---|---|---|
| `core/data/.../playback/Segments.kt` | `MediaSegment.source` (`SegmentSource`, default `Server`) | Skips from four sources; the Skip button says which |
| `core/data/.../settings/ProfileSettings.kt` | `nixiePlay` (`NixiePlaySettings`: online lookups off, learning on), in a block marked "play stream" | The play stream's settings in their own key |
| `app/.../player/PlayerViewModel.kt` | `SkipChain` injected; `loadExtras` adds the chapters' and a learned skip to the server's segments, then the online ones when they arrive; the ticker feeds the `SkipLearner`; the Skip button and automatic skips tell it they are the app's own | Desktop Nixie's four-source skips (server, chapters, online opt-in, learned) |
| `app/.../player/PlayerPrompts.kt` | `SkipButton`'s description names the skip's source | Desktop's tooltip, for TalkBack |
| `app/.../settings/SettingsScreen.kt` | Under Skips: "Look up skips online" (off), "Learn skips", "Forget learned skips" (`nixieSkipRows`, at the end of the file) | The skip sources' switches |
| `app/.../settings/SettingsViewModel.kt` | `SkipChain` injected; `updateNixiePlay`, `forgetLearnedSkips` | The same |
| `app/.../player/PlayerViewModel.kt` | `finish` and `onCleared` report the stop with `watched` when `CreditsRule` says the position is inside closing credits that run to the end | Desktop Nixie's credits rule: the server marks played only from about 90 % |
| `core/data/.../settings/ServerPreferences.kt` | `nextEpisodeAutoPlay`, read from the user's `EnableNextEpisodeAutoPlay` | The user's Jellyfin setting, the same in every client |
| `app/.../player/PlayerViewModel.kt` | `UpNextCountdown.autoPlay`; `onEnded` plays the next episode only when `NextEpisodeRule` says so (a show's episodes; a playlist keeps Glacier's rule); the server preferences are loaded at the start | The server's autoplay setting decides, over the local "Up next" default |
| `app/.../player/PlayerPrompts.kt` | `UpNextCard` says "Up next" with no bar when the next episode will not start by itself | No countdown to something that will not happen |
| `app/.../player/PlayerScreen.kt` | Such a card does not shrink to the countdown ring | The same |
| `core/data/.../media/MediaModels.kt`, `MediaMapper.kt` | `MediaItem.episodeNumberEnd` from the server's `IndexNumberEnd` | "S1 E4–5" for a file with several episodes |
| `app/.../detail/DetailScreen.kt` | `PlayButtons`: `playLabelText` ("Resume S1 E4", "Play S2 E1") before Glacier's label; "Play from the start" | Desktop Nixie's Play labels |
| `app/.../home/Spotlight.kt` | The hero's Play button: `playLabelText` before Glacier's label | The same, on the hero |
| `app/.../search/SearchViewModel.kt` | `NixieSearch` injected; the library search is Nixie's ranked one (`ranked`, `chip`, `expanded` in `SearchState`), Seerr's library hits join it as weak matches; `pickChip`, `showMore`; a person opens the person page (their titles) | Desktop Nixie 1.5's search: best match first, accents and punctuation ignored, type chips, "Show N more" |
| `app/.../search/SearchScreen.kt` | `SearchChips` above the grid, a "Show N more" card after the results (focus moves to the first title it shows), Seerr's titles under "All" only, "Person" under a person's name, an episode's show and SxEy under it (`searchCaption`) | The same |
| `app/build.gradle.kts` | Test dependencies: MockK 1.14.11 and kotlinx-coroutines-test, marked "play stream"; the unit tests take `testing/fakeserver` as an input | `PlayerHooksTest` drives the real `PlayerViewModel` through the play stream's hooks; `SearchServerTest` runs the search against the TV test server |

## The TV fix round: a big library (TV-S1, TV-S2)

Nixie's own files: `core/data/src/main/java/io/github/mbustos27/nixie/tv/data/library/` (`NixieLibraryPaging`: which
lists page on the server, in what order, and how pages join) and `app/src/main/java/io/github/mbustos27/nixie/tv/library/`
(`NixieGrid.kt`: the grid's cell types, artwork sizes and fetching ahead; `NixieRailLetters.kt`: the A–Z rail's
letters), with their tests beside them: `LibraryFirstPageTest` and `LibraryMovingListTest` run the real repository
against fake libraries (10,000 films; 300 that change between pages), `LibraryPagingTest` drives the real view model,
`LibraryMovesTest` the real LibraryScreen (what a move composes, Back's focus, repeated groups), `NixieMarkersTest`
checks the marks below.

| File | Change | Why |
|---|---|---|
| `core/data/.../media/LibraryRepository.kt` | `firstPage`: while a list's index is not in memory (`indexReady`: reading a saved one took 4-6 s on the emulator too) and the server's order is the app's or close to it, the first page comes straight from the server (`serverPage`: its sort, the grid's fields, one picture per kind, the count with the first page only), and `page(serverOrder = true)` continues it, from `NixieLibraryPaging.OVERLAP` titles back (every server order ends with the sort name, then the date added); `LibraryPage.serverOrder`; an index comes in small pages counted on screen when someone waits for it (a jump), in big pages two at once when nobody does (the background index), decided per request (`listing`/`index`'s `waited`, `availableLetters`' `background`; `fetchAll` uses `NixieLibraryPaging`'s sizes in place of Glacier's `WHOLE_PAGE`/`WHOLE_PARALLEL`); only the first page counted (`fetch`'s `withTotal`); several indexes kept in memory for the session (`indexCache` a map of `NixieLibraryPaging.INDEXES_KEPT`, the last used kept; Glacier kept one); `knownLetters`: the rail's letters from memory or the device only, never the server; a jump to a letter with no titles lands on the next one (`indexOfLetter`, `nearestLetter`); a saved index fetched again after a day, not 30 minutes (`INDEX_REFRESH_MS`) | TV-S1: a library of tens of thousands of films showed nothing until its whole index was in (38.6 s on the emulator against 25,000) |
| `app/.../library/LibraryViewModel.kt` | `LibraryState.serverOrder`; the first page through `firstPage`, the next ones in its order; pages appended (and prepended) without the titles already there (`NixieLibraryPaging.appended`: the grid's keys stay unique while the server's list moves); the rail's letters through Nixie's `NixieRailLetters` (in place of Glacier's letters job): after the first page, from memory or the device only (`knownLetters`): until known, a list in the server's order offers every letter, and its index comes on a jump (which first stops a background fetch of it; a newer letter cancels a jump still waiting, and the jumps share one load of the index, `jumpJob`, `indexFor`) or in the background after `INDEX_AFTER_TITLES` titles; in such a list a letter jump (or Back's title, once the index is in) starts the list anew in the app's order, and Back's title does not wait for the whole index; a sort or filter change also cancels the old list's refresh | TV-S1 |
| `app/.../library/LibraryScreen.kt` | The focused title is followed outside the screen's composition (`snapshotFlow`, and the A–Z rail's letter in `NixieOwnScope`, through `derivedStateOf`); the grid composes about two rows ahead and one behind (`rememberLazyGridState(cacheWindow)`, `NixieGridCells.cacheWindow`); each card follows only whether it is the one to focus (`derivedStateOf`) and reads the list's start when focused; content types on the cells (`NixieGridCells`); a group heading's key carries its first title's id (a group the server's order brings back further down gets its own); artwork asked at the card's size (`NixieGridArt.sized`) and the next three rows' fetched ahead (`NixiePrefetchArt`: once the grid stands still, the visible row first; cancelled when out of the rows ahead or when the screen is left, `NixiePrefetchPlan`); the sets a card asks about made once; a wait for the whole list with titles on screen shows its count under the top bar's end | TV-S2: every move in the grid composed the whole screen and every card on it again |
| `core/data/.../media/LibraryRepository.kt` (marks) | Every Nixie line marked `// Nixie` (`index`'s waited fetches and kept indexes, `fetchAll`'s page size and uncounted pages, `fetch`'s `sortBy`, `sortOrder`, `imageTypeLimit`, `enableTotalRecordCount`); `NixieMarkersTest` checks them, and `core/data/build.gradle.kts` makes the file an input of the unit tests | The review: an upstream merge shows what is Nixie's |
| `app/.../ui/Cards.kt` | `GridCard`'s sheen and `Artwork`'s fill made once, not for each card composed; `Artwork`'s fill not drawn under an opaque picture once it is in; its retry coroutine only after a failure | TV-S2 |
| `core/designsystem/.../Focus.kt` (`focusFrame`) | No border at rest when it would be transparent (also in Nixie's `themedFocusFrame`) | TV-S2: nothing drawn that cannot be seen, on every card in every frame |

## The real server: a library's first page and A–Z jump (tv-real-server, 2026-11-22)

Timed against a throwaway real Jellyfin with about 25,000 films and about 500 box sets on its own data (DESIGN-RULES
2), never a user's own server. Nixie's own files:
`app/src/main/java/io/github/mbustos27/nixie/tv/library/NixieServerList.kt` (a list in the server's order: its count
after the first page, an A–Z jump by the server's offset) and `NixieLibraryTimings.kt` (the detailed log's durations),
with `NixieLibraryTimingsTest`; `LibraryFirstPageTest`, `LibraryMovingListTest`, `LibraryPagingTest` and
`NixieLibraryPagingTest` cover the rest. "Group movies into collections" (Glacier's
`CollapseBoxSetItems`, on by default) still goes with every page: without it the list would show other titles than its
next pages and its index; it is a third of the first page here (436 ms with it, 169 ms without).

| File | Change | Why |
|---|---|---|
| `core/data/.../media/LibraryRepository.kt` | The first page from the server comes uncounted (`LibraryPage.total` is null until the list is counted); `serverPosition`: the list's count, or where an A–Z letter starts in the server's order (`NameLessThan`, limit 0, the same filters; Z to A counted from the end), each asked apart from the pages; `fetch`'s `nameLessThan`; `page`'s `overlap` (none for a page that fills in before what is loaded, starts a list anew or loads it again) | The first page took 821 ms counted, 436 ms not (DESIGN-RULES 1, 3) |
| `app/.../library/LibraryViewModel.kt` | A list from the server is counted after its first page (`NixieServerList.countAfter`), and a page that comes uncounted keeps the count known; an A–Z jump in such a list by title, its index not in memory, asks the server where the letter starts and loads the rows there in the server's order (`loadFrom(fromServer = true)`, split out of `loadAround`, false when the page did not come), the titles before it from the server too (`loadBefore`, `rowStartBefore`), before the count has come if need be; the index the other jumps wait for is `NixieRailLetters.awaitIndex`'s (was the view model's `indexFor`); the first page, the first row and each jump timed for the detailed log (`NixieLibraryTimings`) | The first A–Z jump read the whole index (52 requests of 500): 13.6 s; by offset two requests, 1.2 s (DESIGN-RULES 3, 10) |
| `app/.../library/LibraryScreen.kt` | The grid tells the view model when its first row is drawn (`withFrameNanos`) | DESIGN-RULES 10: the time to the first row on the box, without adb |
| `core/data/.../media/LibraryRepository.kt` (marks) | `fetch`'s `nameLessThan` marked; `NixieMarkersTest` checks it | The review |
| `app/.../library/LibraryScreen.kt` (posters) | A card asks for `NixieGridArt.cardUrl`: a film's, show's or collection's poster as Jellyfin's web client asks for it in a 1920-wide window (`fillHeight=320&fillWidth=213&quality=96&format=Webp`, 426 x 639 at twice the pixels), the same address the rows fetched ahead ask for | The server keeps each picture it makes by size, quality and format: the old 240 or 480 wide JPEG at quality 90 matched no other client's, so every poster was made anew on the server (14 first-sight posters about 0.35 s on the test instance, from its cache about 10 ms; the web's cached picture answers the TV's address, checked) |
| `app/.../library/LibraryViewModel.kt` (first screen) | The count and the A–Z rail's letters wait for `NixieLibraryTimings.firstScreen` (the first row drawn and its first picture, or 2 s after the row), not the first page | DESIGN-RULES 1: reading a saved index of about 25,000 films took 0.1-0.4 s and 37-43 MB of allocations on a desktop JVM (6 s on the emulator), right while the first row and its pictures came |
| `app/.../GlacierApplication.kt` | The image loader reports to `NixieImageWatch` (Coil's event listener): the first picture of the library's first rows | DESIGN-RULES 10: "First row: before the request, request, parse, on screen, first picture" in the detailed log |
| `core/jellyfin/.../JellyfinModule.kt` | The SDK's clients are `NixieTimedApiClient`s (one OkHttp factory for requests and sockets, as the SDK's default): an answer read whole is marked in the request's `NixieRequestClock`, so a page's time splits into request and parsing (`LibraryPage.timing`, `PageTiming`, `LibraryRepository.serverPage`) | DESIGN-RULES 10 |

## Unwatched and oldest-first views from the server (tv-unwatched, 2026-11-22)

The round-2 hunt on the throwaway real Jellyfin: with "Group movies into collections" on, Unwatched listed the whole
library before its first row (33.2 s on the box, about 50 requests, every open: a filtered index is never saved), and
the oldest-first sorts waited for the whole index the same way. Both now page from the server's order (DESIGN-RULES 1,
3); the index comes only in the background (the A–Z rail after a few pages; jumps go by the server's offset). On the
test instance (about 25,000 films, a user who watched 1,000): Unwatched 15.4 s to 0.18 s, oldest year first 14.9 s to
0.44 s.

| File | Change | Why |
|---|---|---|
| `core/data/.../media/LibraryRepository.kt` | `LibraryQuery.groupsCollections` is off for Unwatched too, and Glacier's `unwatchedGrouped` is gone with what served it (`listedWhole`, `wholeList`'s played filter, `fetchAll`'s and `fetch`'s `grouped`): an Unwatched list shows the unwatched titles themselves, filtered by the server (`IsUnplayed`) | Jellyfin does not group once a played filter is in the query, so Glacier grouped from the whole library; a collection would hide which of its films are unwatched (as for favourites and genres), and Jellyfin's web client shows the titles too |
| `core/data/.../media/ListArrangement.kt` | Sorted by a value going up (date added, year, rating, length), the titles without one come first (by a name, such as music's artists and albums, still last) | That is the server's order (SQLite puts a missing value first going up), so these lists page from it like the descending ones (`NixieLibraryPaging.serverSort`), and the app's order agrees once the index is in |

## Review fixes (the whole-app review, 2026-11-21)

| File | Change | Why |
|---|---|---|
| `core/data/.../settings/SettingsBackup.kt` | The settings copies on the server under `nixie-tv-settings` / client `nixie-tv` (were Glacier's `glacier-settings` / `glacier`) | A Nixie install and a Glacier install on one account never restore each other's settings |
| `app/.../diagnostics/LogShareScreen.kt`, `app/.../player/PlaybackFailure.kt` | Glacier's `ISSUES_URL` removed; "share the log" step 3 and the player's error sheet take no URL; their text (in `app/src/nixie/res`) says to share the log with whoever helps with Nixie | Nixie has no public tracker; its users' logs must never go to Glacier's |
| `app/.../GlacierApplication.kt`, `app/.../update/RestartReceiver.kt`, `core/updater/.../ApkInstaller.kt` | Log lines name no other app ("App" tag on the start line) | The log users share names Nixie only |

## Stream W: Continue watching on the TV's home screen (tv-watchnext, 2026-11-21)

Nixie's own files: `core/data/src/main/java/io/github/mbustos27/nixie/tv/data/watchnext/` (the settings key, whose rows
they are, which titles), `app/src/main/java/io/github/mbustos27/nixie/tv/channels/` (`WatchNextStore`, `NixieWatchNext`),
`app/src/main/res/values*/nixie_watchnext_strings.xml`, and their tests (`WatchNextRulesTest`; `channels/WatchNextTest`
against a fake of the system's TV provider).

| File | Change | Why |
|---|---|---|
| `core/data/.../settings/ProfileSettings.kt` | `nixieWatchNext` (`NixieWatchNextSettings`, on by default), in a block marked "tv-watchnext" | The setting's own key |
| `core/data/.../media/HomeScreenRepository.kt` | The rows belong to the profile that opened in Nixie last, while it is signed in (`WatchNextProfiles`; Glacier took the most recent signed-in profile, so a sign-out showed another profile's titles); Continue watching is Home's own, then Up next (`WatchNextRules`: stale ones hidden, each show once, Next Up 365 days back with rewatching), and empty when switched off | Nothing from another profile, ever; the same titles as Home's rows |
| `core/data/.../playback/PlaybackRepository.kt` | `nixieProgress`: each video position the server took, paused or not, and the stop of a title not finished (not music, not Live TV) | The row follows playback, written by its id URI at most once a minute, at a pause and at the stop (`NixieWatchNext.progressNow`); playback never loads the rows, and nothing uses a selection (the system's provider refuses them from apps) |
| `app/.../channels/HomeChannels.kt` | `writeWatchNext` hands Glacier's programs (`watchNextPrograms`) to `WatchNextStore`: each row's id names its profile, a second row of one title and anyone else's rows go; Glacier's "removed on the home screen, back once watched further" moved with it and now works (it set `browsable`, which `toContentValues()` drops and only the system may write; the row is written anew instead); each row's intent plays (`HomeLaunch.play`); next episodes sort behind the started titles | No duplicates after a restart; no row passes from one profile to another |
| `app/.../channels/HomeChannelsSync.kt` | `nixieStart` hands the app's flows to `NixieWatchNext.follow`: a profile that opens owns the rows; the first owner known reconciles the table once at app start, after Home (another profile's rows go; stream H, below); a sign-out, a removal or another profile empties them at once; a position the server took updates its row; the setting off removes every row. The worker writes through `NixieWatchNext.publish`, whose lock replaces Glacier's `writing` mutex, and drops rows loaded for a profile that no longer owns them. Debug builds only: `adb shell am broadcast -a io.github.mbustos27.nixie.tv.WATCH_NEXT_DUMP -p <package>` lists the app's own rows in logcat only (`WatchNext`, never the shared log file), since the shell's `content query` sees only the shell's rows; only a sender with the signature permission DUMP (the shell) reaches it | Changes without a trip to the server; a late write never brings another profile's titles back; emulator checks |
| `app/.../channels/HomeLaunch.kt` | The `play` extra | Continue watching on the home screen resumes |
| `app/.../home/HomeViewModel.kt` | A launch with `play` plays the title through the PIN gate (`nixiePlayLaunch`), else the page opens as before; Remove from Continue watching takes the title's row (`NixieWatchNext.removed`); an Undo the server took puts it back (`ContinueWatchingRemovals.putBack`, `restored`), unless the title was removed again | The same row everywhere |
| `core/data/.../nixie/tv/data/ContinueWatchingRemovals.kt` (the home stream's) | `putBack`: an Undo the server took, with no newer removal of the item, in a line marked "tv-watchnext" | The home screen's row comes back only then |
| `app/.../settings/SettingsScreen.kt`, `SettingsViewModel.kt` | Settings › Home › Continue watching ends with "Show Continue watching on the home screen" (`withNixieWatchNext`, `updateNixieWatchNext`), in blocks marked "tv-watchnext" | The owner's switch; Google TV's own home screen filters uncertified apps' rows |

## The TV fix round, stream Q (search speed TV-S4, the trailer player TV-S3; 2026-11-21)

Nixie's own files: `app/src/main/java/io/github/mbustos27/nixie/tv/trailer/` (`ReturnFocus`) and `.../tv/search/`
(`KeepBestInView`), with their tests in
`app/src/test/java/io/github/mbustos27/nixie/tv/trailer/` and `app/src/test/.../play/SearchTypingTest.kt`; the search
itself is Nixie's `core/data/.../search/NixieSearch.kt` (`results`: one request per kind, each shown as it arrives).

| File | Change | Why |
|---|---|---|
| `app/.../search/SearchViewModel.kt` | A 300 ms pause in typing (Glacier: 350); one character shows the suggestions (loaded then if they were not, `showSuggestions`) and is searched only after an 800 ms pause or on Enter (`searchNow`); the library hits show as each kind arrives (`NixieSearch.results`), at most one step every 150 ms (`COALESCE_MS`), Seerr's joining every step; a debug line with the time to the first titles (never the search term) | TV-S4: search results took far too long on a server with tens of thousands of films |
| `app/.../search/SearchScreen.kt` | `KeepBestInView`: a new first result scrolls the grid to the top while the focus is not in it; Enter on the system keyboard searches at once | Review M1: a lazy grid keeps its top card, so better matches arriving late would land above the screen |
| `app/.../trailer/TrailerScreen.kt` | The movie player's keys (OK plays or pauses, Left and Right scrub, Up, Down and Menu show the controls; Glacier: any key only woke them), its seek bar (`Timeline`: a scrub that jumps after 1.2 s or OK) in place of Glacier's own `ProgressBar` (deleted), the theme's shades (`osdScrim`) in place of its own fixed gradients, the clock, bottom block and 3 s timeout; pausing focuses the seek bar | TV-S3: the trailer controls felt "kind of weird"; the owner's decision: match the movie player. The deleted bar and gradients were the differences: on a merge, keep them out |
| `app/.../trailer/TrailerViewModel.kt` | `seekBackMs`/`seekForwardMs` from the profile's seek steps (Glacier: 10 s and 15 s); `seekTo`; `ReturnFocus.handedOver()` before "Play movie" swaps in the player | The same; Back from the movie does not land on Trailer |
| `app/src/main/res/values*/strings.xml` | `trailer_forward` ("Forward 15 seconds") deleted in en, de and es | The trailer's forward button takes the movie player's step and label (`player_forward`); on a merge, keep it out |
| `app/.../player/PlayerOsd.kt` | `Timeline` internal | The trailer's seek bar is the movie player's |
| `app/.../player/PlayerScreen.kt` | The menu key shows the controls | The same remote keys in both players |
| `app/.../detail/DetailScreen.kt` | The Trailer button takes the focus again after Back from the trailer (`rememberReturnFocus`) | TV-S3: Back returns to the movie page on the Trailer button |

## The TV fix round, stream H (Home in seconds TV-S6, Quit while Home loads TV-S10; 2026-11-22)

Nixie's own files: `core/data/.../nixie/tv/data/HomeStart.kt` (`HomeParts`, `HomeProgress`, `HomeStart.rows`: each of
Home's parts on its own, the draw rule, the counts last), `SavedHome.kt` (Home as it last showed, per profile and age
limits, in memory and in the cache folder; a profile removed from the device takes its page along), `StartLine.kt` (the start's and Home's durations for the detailed log; Home
drawn and settled for the background work), `app/.../nixie/tv/start/StartCheck.kt` (`SavedStart`, `StartMemory`,
`StartFromSaved`, `StartCheck`: the start profile from what is saved, the server checked after Home opened), and their tests
(`HomeStartTest`, `HomeRequestsTest`, `SavedHomeTest`, `home/HomeQuitTest`, `start/StartFromSavedTest`, the new cases of
`channels/WatchNextTest`).

| File | Change | Why |
|---|---|---|
| `core/data/.../media/HomeRepository.kt` | Implements `HomeParts`; `load()` gone: `views` (no counts), `counts` (asked last), `resume`, `nextUp`, `latest`, `recentAlbums`, `favoriteSongs`, `items`, `seed`; no total count on any row (`enableTotalRecordCount = false`); cards ask only for what they show (`NIXIE_CARD_FIELDS`, `NIXIE_CARD_IMAGES`, `imageTypeLimit = 1`; Continue watching no fields) | TV-S6: Home waited for every part and a count over each whole library before it drew |
| `core/data/.../SessionManager.kt` | "Used last" (`markUsed`) is saved after the session opens, in the background | An encrypted write of about 0.6 s before Home, that nothing on the way reads |
| `app/.../home/HomeViewModel.kt` | `load`/`refresh` go through `nixieRows`: the saved page first, then `HomeStart.rows` row by row; the hero asked beside the rows (`nixieHeroWhenReady`), the first draw waits up to 1 s for it; the page saved after each load; `nixieOnScreen`/`nixieAllOnScreen` for `StartLine`; the live socket starts once Home is drawn | TV-S6 |
| `app/.../home/HomeScreen.kt` | The key block of the loader is off while "Quit Nixie?" is open, and no first-focus request runs under it; Home on screen reported a frame after its content and its last row; Play takes the focus from a hero that came after the rows only if no key was pressed | TV-S10; TV-S6 |
| `app/.../profiles/ProfilesViewModel.kt` | An app start opens the start profile from what is saved (`StartFromSaved`, `SavedStart`) and prefetches its saved Home; the server, the token and the user list are checked after (`StartCheck`); "the single profile" remembers what the server's list decided (`StartMemory`) | TV-S6: up to tens of seconds on a slow box before Home opened |
| `app/.../MainActivity.kt` | `StartViewModel` opens the start profile from what is saved (`StartFromSaved`) and makes Home the first screen (the profiles screen only when the server must be asked); passes `StartCheck`'s redirects (sign-in, another server's profiles) to the screens opened from scratch; the activity's start and first frame marked (`StartLine.mark`) | As above; the cold start's split in the detailed log |
| `app/.../GlacierApplication.kt` | The accounts' file is read in the background at `onCreate`; `HomeChannelsSync` is injected lazily and built off the main thread; `onCreate`'s steps marked (`StartLine.mark`) | The first screen waited for its first read; Hilt's graph for Watch Next is not needed for the first frame |
| `app/.../channels/HomeChannelsSync.kt` | `start` waits until Home is drawn and settled (at most 30 s), off the main thread: the provider check, the periodic work, a profile's load; a worker in a process just started waits too; the load and the write are timed | TV-S6: Watch Next's load (20 s on the box) ran beside Home's |
| `app/.../nixie/tv/channels/NixieWatchNext.kt`, `WatchNextStore.kt` (tv-watchnext's) | `follow(ready)`: the start's reconcile waits for Home and for a known owner; then only another profile's rows go (`reconcile` keeps nobody's rows); with no owner known only a profile known to be gone loses its rows (`goneNow`, `removeOwner`) | The launcher's row was emptied at every start ("no profile yet" read as "another profile", DESIGN-RULES 4) |
| `core/data/.../nixie/tv/data/watchnext/WatchNext.kt` (tv-watchnext's) | `WatchNextProfiles.gone()` | As above |
| `core/data/.../nixie/tv/data/Hero.kt` (the home stream's) | `newTitles` and `pool` public; its request asks for no count and one image of each kind | The hero is asked beside the rows |

## The TV fix round, stream P (the 4K cap TV-S7, standby TV-S8, the trailer start TV-S9; 2026-11-22)

Nixie's own files: `core/data/src/main/java/io/github/mbustos27/nixie/tv/data/play/DisplayPanel.kt` (the panel's size
from the display mode), with `DisplayPanelTest`, `PanelPlaybackTest` and their fake server (`FakePlaybackServer`, a
cut-down StreamBuilder) in `core/data/src/test/java/io/github/mbustos27/nixie/tv/data/play/`;
`app/src/main/java/io/github/mbustos27/nixie/tv/play/Standby.kt` (the open that brings the player back, and the screen's
saved title and position) with `app/src/test/.../play/PlayerStandbyTest.kt`. The live updates (`LiveUserData`, Nixie's)
pause in the background and back off to five minutes (`LiveUpdatesBackgroundTest`; `LiveUserDataTest` now expects five
minutes).

| File | Change | Why |
|---|---|---|
| `core/data/.../playback/PlaybackRepository.kt` | `DisplayPanel` injected; `displaySize()` is `panel.size()`, the display mode's physical size (Glacier: `Resources.getSystem().displayMetrics`) | TV-S7: Android TV draws its UI at 1920x1080 on a 4K panel, so every 4K title the server converted (the audio only included) was asked again capped at 1080p, and ladders stopped at 1080p |
| `core/data/build.gradle.kts` | `androidx.core:core-ktx` (the version catalog's) for `DisplayCompat`; MockK 1.14.11 for the tests | The panel's real size on boxes that report the UI's size as the mode (Sony and others); `PanelPlaybackTest` stands a 4K Google TV box's decoders in for `MediaCapabilityDetector` |
| `app/.../player/PlayerViewModel.kt` | `nixieBackground`: the server gets the stop at the title's position (its transcode ends) and the player is released, the title and position kept in the screen's saved state; `nixieForeground` repeats the open there (or the open under way, as asked) with the player paused (`nixieStartPaused` in `open`); `open` remembers its arguments (`nixieLastOpen`); an `init` block restores a screen whose process went away, paused | TV-S8: a paused player was held all night in standby |
| `app/.../player/PlayerScreen.kt` | `ON_STOP` also calls `nixieBackground`; `ON_START` calls `nixieForeground` and shows the controls on the seek bar, as after a pause | The same |
| `core/data/build.gradle.kts` | `androidx.lifecycle:lifecycle-process` at the catalog's lifecycle version (the app has it already) | `LiveUpdates` follows the app's foreground state (`ProcessLifecycleOwner`) |
| `app/.../player/PlayerViewModel.kt` | `nixieTiming`: each open's start (details, the server's answer, the first frame) as one line of durations in the detailed log (`StartTiming`, Nixie's) | Design rule 10: the next check on the box gives numbers without adb |
| `app/.../trailer/TrailerViewModel.kt` | `nixieTiming`: each trailer's start (details; a server file's answer, or YouTube's web view, page, API and player; the first frame) as one line of durations | TV-S9: tens of seconds to a trailer's first frame on a slow box; rule 10 |
| `app/.../trailer/YouTubePlayer.kt` | `YouTubeListener.onYouTubeStep` (default: nothing) and `YOUTUBE_STEPS`; the web view, `onPageFinished`, and the page's `onYouTubeIframeAPIReady` and `onReady` report their step (`Glacier.onStep`, known step names only) | The same |
| `app/.../trailer/TrailerScreen.kt` | A new YouTube trailer starts with the controls hidden (Glacier: shown, then gone after 2 s), so its first frame is full screen; the loader and its key block from the screen's first frame (Glacier: once the trailers are known) | TV-S9: trailers "open small then open up full screen"; design rule 7 |
| `app/.../trailer/YouTubePlayer.kt` | The page preconnects to the embed host and `i.ytimg.com` | TV-S9: the start's connections open while the IFrame API loads |
| `app/.../trailer/YouTubePlayer.kt` | `ON_STOP` pauses the web view and its timers (`onPause`, `pauseTimers`), `ON_START` resumes them | Design rule 5: a trailer left paused in standby kept its page's 500 ms progress timer running |
| `core/data/.../playback/PlaybackRepository.kt` | The "made for the display" re-ask is skipped when only the audio is converted (`pictureOnlyCopied`, Nixie's): the picture is copied; the info panel's height is then the picture's own | A 1080p display no longer has the server re-encode a 4K picture it would copy, which the device decodes |
| `app/.../trailer/TrailerViewModel.kt` | `nixieBackground`/`nixieForeground`: a server file's player is released in the background, its place kept, and opens there again paused (`openLocal` takes a start and a paused flag) | Design rule 5, as the movie player (TV-S8) |
| `app/.../trailer/TrailerScreen.kt` | `ON_STOP` also calls `nixieBackground`, `ON_START` `nixieForeground` | The same |
| `app/.../detail/DetailScreen.kt` | `NixieTrailerWarmup(details)` on the movie or show page; the Trailer button's `nixieWarmTrailerOnFocus` | TV-S9: the YouTube player's shell (no video: the same for every title, so YouTube learns nothing about what is browsed) is made ahead once the page is drawn, and again when Trailer takes the focus (`TrailerWarmup`, Nixie's) |
| `app/.../trailer/YouTubePlayer.kt` | Takes the shell made ahead (`TrailerWarmup.take`, the relay's reports handed on as this video's, `setSubtitles` and `startVideo(id)` sent: the video's id goes to YouTube only here, on the press) before making a player; `makeYouTubePlayerAhead` (a page with no video); the page's `videoId` (null for the shell), `autoplay` flag, `startOnReady` and `startVideo`; `onYouTubeMadeAhead` (default: nothing) | TV-S9: pressing Trailer then only loads the video; Nixie's rule for outside services |
| `app/.../trailer/TrailerScreen.kt` | Claims the player made ahead while opening, lets it go if not taken (closing or stopping) | The movie page stops 60 ms before this screen is composed (emulator): `TrailerWarmup.release` waits a second for the claim |
| `app/.../trailer/TrailerViewModel.kt` | `onYouTubeMadeAhead`: the timing says "YouTube, made ahead" | Design rule 10 |

## The second fix round's hunt fixes (tv-fix-round-2, 2026-11-22)

Nixie's own files: `app/.../nixie/tv/home/NixieExitKeys.kt` (`nixieExitKeys`), with the new cases of
`home/HomeQuitTest`; `core/data/src/test/.../nixie/tv/data/library/RailLetterTest.kt`.

| File | Change | Why |
|---|---|---|
| `app/.../home/HomeScreen.kt` | The page's outermost layout takes `nixieExitKeys`: from Back on, until the focus is in "Quit Nixie?", OK quits and no other key reaches the page (Back and Escape go on); `ExitDialog` reports when the focus is in it (`onFocus`) | TV-S10, the hunt: Quit took the focus a frame after the dialog came, and an OK in between went to the entry behind it (Movies opened) |
| `core/data/.../media/NameOrder.kt` | `title`: symbols the server's sort name drops before the first letter ("&Me" as "me", "¡Three" as "three") are dropped too; a sort name that keeps a symbol or a blank first still files the title under '#' | The A–Z rail filed "&Me Archive" under '#' while the server sorts it under M: a jump to M landed on it, and it moved once the index loaded |

## Stream R: the way back to a paused title loads once (TV-S12, tv-resume-once, 2026-11-22)

Nixie's own file: `app/src/main/java/io/github/mbustos27/nixie/tv/play/PlayerReturn.kt` (which title the player screen
holds, the launcher cards it keeps, and the way back's timing line), with `PlayerReturnTest` and `ResumeOnceTest` in
`app/src/test/.../nixie/tv/play/`.

| File | Change | Why |
|---|---|---|
| `app/.../MainActivity.kt` | `StartViewModel.open`: a launcher card that plays the title the player screen holds, in the same profile, opens nothing (`PlayerReturn.keeps`); the screen brings the title back by itself | TV-S12: the card sent the app to Home from scratch, which dropped the player screen that had just opened the title again (TV-S8); Home then opened a second one: the title loaded twice more, and Home's rows once |
| `app/.../player/PlayerViewModel.kt` | Attaches to `PlayerReturn` as the screen holding its title (none while closing or failed), detaches in `onCleared`; `nixieForeground` starts the way back, `open` counts the open asked of the server and the player prepared, the first frame ends it, `finish` drops it | The same; design rule 10: the way back's duration and its counts in the detailed log |

## Stream M: a slow server, one request before the first row (TV-S13, tv-slow-server, 2026-11-22)

Nixie's own files: `app/.../nixie/tv/timing/` (`NixieKeyClock`: every OK as the input system dated it, its wait for the
main thread, when it came up and opened a screen; `NixieFrameWatch`: the main thread's long frames for 12 s after an
OK, in debug builds and while detailed logging is on; `NixieKeyWait.kt`: `NixieKeyWait` and `nixieKeyClock()`) and
`app/.../nixie/tv/trailer/TrailerHandOver.kt` (the movie page's details for its Trailer button), with their tests
(`timing/NixieKeyClockTest`, `trailer/TrailerHandOverTest`, `library/SlowServerPathsTest`: Glacier's LibraryViewModel
and the real LibraryRepository over a fake server that notes each request and what the grid held then,
`library/NixieGridCellsTest`, `core/data/.../library/LibraryRequestsTest`). Changed Nixie files: `NixieServerList`
(`jump`, `loadFrom` from LibraryViewModel, `nextFromLetter`, `sameList`, the rows' phase), `NixieRailLetters` (every letter at once in the server's order),
`NixieGrid` (`NixieGridCells.span`, `nixieRowEnd`, `NixieKeepRowsInPlace`), `NixieImageWatch` (`watchAll`),
`NixieLibraryTimings` (the lines from the key), `TrailerWarmup` (`NixieTrailerWarmup` hands the details over), `NixieLibraryPaging` (`letterStart`,
`rowStartOf`'s phase, `IDS_PER_REQUEST`).

| File | Change | Why |
|---|---|---|
| `core/data/.../media/LibraryRepository.kt` | `page`'s `fromLetter`: an A–Z jump's page from the letter itself (`NameStartsWithOrGreater` going up, `NameLessThan` the next letter going down), uncounted, no place asked first; `serverPage`'s `letter` (no size from such a page), `fetch`'s `nameFrom`; `IDS_PER_REQUEST` is `NixieLibraryPaging`'s 80 (Glacier: 40) | The first A–Z jump waited on two requests one after the other (its place, then its page): over 2 s on a box. A page in the app's order (index in memory) came as two requests at once |
| `app/.../library/LibraryViewModel.kt` | `LibraryState.anchor` (the list starts at a jump's letter, its place not known yet) and `rowPhase` (rows start at that letter); `jumpToLetter` in the server's order goes to `NixieServerList.jump`: it lands by the letter's page (`jumpTo` 0) and asks its place after, then the rows above it (`loadBefore`); the list's start needs no request by letter; Nixie's `loadFrom` (TV-S1) moved into `NixieServerList`, both run as the list's load (`asLoad`); `loadUpTo` goes on from the letter while the place is unknown and keeps its page when the place comes meanwhile (`sameList`); `refresh` waits for the place; `toStart` starts anew after a jump's rows; `reload` and `loadFrom` clear both; `jumpHandled` reports the landing on screen | DESIGN-RULES 1, 3: one request between the key and the jump's first row |
| `app/.../library/LibraryScreen.kt` | A title's cell takes `NixieGridCells.span` and its card `nixieRowEnd`: with rows that start at a jump's letter, the list's first row (reached by scrolling all the way back) ends early, its last card keeping its width | The titles loaded before a jump join whole rows: nothing on screen moves |
| `app/.../library/LibraryScreen.kt` | `NixieKeepRowsInPlace(gridState, items, positions)` after `NixiePrefetchArt`: titles that come in before those on screen leave the first one where it was (`requestScrollToItem`, in the same frame) | A jump's page starts right under the toolbar; the rows above it come once its place is known, and the grid would have kept the toolbar in place and pushed the letter's rows out of view |
| `app/.../navigation/GlacierNavHost.kt` | `Modifier.nixieKeyClock()` on the `NavHost` (every key passes it first; nothing consumed); `NixieKeyClock.navigated()` in `navigate` | Design rule 10: a stopwatch on a box said nearly three times what the page's own line said; the log now times the whole wait from the OK |
| `app/.../trailer/TrailerViewModel.kt` | The details the movie page handed over (`TrailerHandOver.take`) before asking the server (the step is then "details from the page"); the first start's line is followed by its wait from the OK (`NixieKeyWait`) | The trailer asked the server for the title's details after the press, before YouTube could start; nothing goes to YouTube before the press (TV-S9's rule) |
| `app/build.gradle.kts` | `testRuntimeOnly` slf4j-api 1.7.30, as core/data's tests | `SlowServerPathsTest` runs the SDK's own requests, which log through kotlin-logging |

## Stream D: the dev build's log straight to the PC (tv-log-pull, 2026-11-22)

Nixie's own files: `app/src/debug/` (debug builds only: `AndroidManifest.xml` declares `AdbLogReceiver`, exported
but reachable only by a sender holding `android.permission.DUMP`, the adb shell; on
`io.github.mbustos27.nixie.tv.debug.WRITE_LOG` it writes the report Download log serves to
`files/diagnostics/nixie-tv-log.txt` in the app's own storage, nothing sent anywhere), `tools/pull-tv-log.ps1`
(triggers it over adb, copies the file through `AdbLogProvider` since integration 1, before with `run-as`, removes it, disconnects; docs/RELEASING.md), and their tests: `app/src/testDebug/.../nixie/tv/diagnostics/AdbLogTest.kt` (the file is the
anonymised report) and `app/src/test/.../nixie/tv/diagnostics/AdbLogManifestTest.kt` (the receiver is in a debug
build's merged manifest only, and in no source set a release build merges).

| File | Change | Why |
|---|---|---|
| `app/.../diagnostics/Diagnostics.kt` | `fullReport()`: the whole report `shareOnNetwork` serves (`report(Int.MAX_VALUE)`), public | The debug build's adb pull writes the same anonymised report Download log serves, from the debug source set |

## Home is Continue watching and Up next; Collections in the top bar (tv-home-two-rows, TV-S11; 2026-11-22)

The owner (2026-11-22): "the goal of home tv loading should be continue watching then up next and then that on one page
basically and then an easier way to access collections". Nixie's own files: `app/.../nixie/tv/home/NixieHomePage.kt`
(Home's page: the hero shows the focused card's picture and facts in the theme's hero slots and asks for nothing; then
Continue watching and Up next, on one 1080p screen; an empty row says so in a line, both empty say what to do next),
`app/.../nixie/tv/library/NixieCollectionsPage.kt` (the Collections page's rules); `core/data/.../nixie/tv/data/HomeStart.kt`
(the two rows only, then the top bar's libraries) and `SavedHome.kt` (version 2: the two rows and the top bar's
libraries) changed; `Hero.kt` and `HeroRulesTest` deleted (the hero asks for nothing). Tests: `HomeTwoRowsTest`,
`CollectionsEntryTest`, `CollectionsPageTest` (core/data), `NixieHomeShotsTest` (Home, both empty states, the Remove sheet,
Collections and its empty line, every theme), the reworked `HomeStartTest`, `HomeRequestsTest`, `SavedHomeTest`;
`ThemeShotsTest`'s `home` draws the new page (and fits at the large text size). `ServerOrder` is no longer called from
Home (no "New in" rows or "My media" cards to order); its tests stay. The rows above about the hero rule (the home stream's
`HeroRepository` and Settings › Home › Hero, stream H's `nixieHeroWhenReady`) and `nixieUpNextRow` no longer apply.

| File | Change | Why |
|---|---|---|
| `app/.../home/HomeScreen.kt` | The content is `NixieHomePage` (in place of the spotlight and the LazyColumn of rows: the spotlight's rotation, `PosterFor`, `libraryCount` and the rows' loop are gone); `continueSubtitle` internal; the first focus is the first card (Continue watching's, else Up next's), else the top bar until a card comes and no key was pressed; the top bar's Down only with a card; Back in the rows scrolls both rows back. **On a merge, keep Glacier's Home rows out** | The owner's two rows on one page, no scrolling |
| `app/.../home/HomeViewModel.kt` | `HomeState`: `spotlight` and `nixieHeroPending` gone, `nixieContinueWatchingIn`/`nixieUpNextIn` (an empty row's line only once its answer is known); no hero, favourites or Live TV refresh, `toggleFavorite`, `playFavorite`, `playChannel`, `openLibrary` or `ServerOrder` following; `MusicController` and `HeroRepository` not injected; the saved page has no hero; settled once both rows are in | Home asks for its two rows only |
| `core/data/.../media/HomeRepository.kt` | `views()` one request (no `latestItemsExcludes`: no "New in" rows); `resume` and `nextUp` ask `Overview` and `Genres` (the hero's facts), no `MediaStreams`; `latest`, `recentAlbums`, `favoriteSongs`, `favorites`, `liveChannels` and `counts` are no longer `HomeParts` (kept, unused by Home) | Nothing for rows Home no longer shows; no counts |
| `core/data/.../media/LibraryRepository.kt` | `LibraryQuery.nixieAllCollections`, `NIXIE_COLLECTIONS_KEY` (its own `sortKey`), `nixieCollections()` (box sets of the whole server, A to Z) | The Collections page, paged from the server as a library's Collections chip |
| `app/.../navigation/TopNav.kt` | `NavTarget.Collections` and its label; `NAV_COLLECTIONS` after the library kinds in `navEntryIds`/`navTargetOf` (before Live TV and Favorites); the bar's fallback entries have it | Collections one press from the top bar |
| `app/.../navigation/NavBar.kt`, `Routes.kt` | `NavTarget.Collections` opens `LibraryRoute(Movies, collections = true)` | The same |
| `app/.../library/LibraryViewModel.kt` | Through `NixieCollectionsPage`: the page's query, its only tab, its own toggles' key (`nixieFilterKey`), its entry as the current one, no movies' memory, a first request of 24 (`nixieFirstPage`: what the first screen shows), the next pages at once | "Ask only what the first screen shows, then page" |
| `app/.../library/LibraryScreen.kt` | The page is called Collections, lights its entry, and with none on the server says so | The same |
| `app/.../settings/SettingsScreen.kt` | Settings › Home without the hero's and the rows' groups (`nixieTwoRowHome`); the Collections entry's name and line in Settings › Appearance › Top bar | No switch that does nothing |
| `app/.../nixie/tv/home/NixieHomeRows.kt` (the home stream's) | `nixieUpNextRow` gone (the page draws the row); `NixieKeepRowFocus` asks for the neighbour over a few frames (the rows' column hands the focus to its first card when the focused card goes); `NixieUndoCard` as tall as the card it stands for | The rows sit at the foot of the page: a card changing height would move them |
| `app/src/main/res/values*/nixie_home_strings.xml` | The empty lines, the all-empty message, the Collections line and setting line (en, de, es) | |
| `app/.../navigation/GlacierNavHost.kt` | The top bar's Home goes back to the Home on the back stack (`nixieBackToHome`, Nixie's `home/NixieHomeAgain.kt`) instead of clearing the back stack and building a new Home; any other way to Home (a profile that opens) is Glacier's | Stream R's finding: every Home press reloaded every row, behind a spinner |
| `app/.../home/HomeScreen.kt`, `HomeViewModel.kt` | A Home gone back to takes `FOCUS_NAV` on its return: the focus on the bar, the rows back at their first card, and `nixieBackHere` asks Continue watching and Up next again behind what it shows (`HomeStart.rows(topBar = false)`), with no spinner; tested by `NixieHomeAgainTest`, `HomeTwoRowsTest`, `HomeStartTest` | The same |
| `app/.../navigation/TopNav.kt`, `NavBar.kt`, `GlacierNavHost.kt`, `settings/SettingsScreen.kt` | `NavTarget.Libraries` (`NAV_LIBRARIES`, right after Home; the per-type entries stay), its label and Settings line; it opens `NixieLibrariesRoute`, Nixie's `library/NixieLibrariesPage.kt`: every library's card (Home's old My media cards) in the server's order, each with its count (asked after the cards are drawn, four at a time); `LibrariesReachTest` (23 libraries, every one opened from Home with the remote), the `libraries` shot | The coordinator: "a library that can't be reached is a basics bug" (a big shared server can have two dozen libraries; My media left Home) |
| `core/data/.../media/HomeRepository.kt` | `counts` merges into the libraries with `update` | The Libraries page counts a few libraries at a time |

## Integration 1: the noticed and pointed-out issues (tv-integration-1, 2026-11-23)

Nixie's own files: `app/.../nixie/tv/library/NixieImageRequests.kt` (the image loader's network fetcher, which joins
requests for a picture already coming), with `library/PosterOnceTest`.

| File | Change | Why |
|---|---|---|
| `app/.../GlacierApplication.kt` | The image loader takes its pictures through `NixieImageRequests.network()`: Coil's OkHttp fetcher with `DeDupeConcurrentRequestStrategy`, so a second request for a picture already coming waits and reads it from the disk cache | TV-S13's finding: at a cold Movies open the grid's cards (composed two rows ahead) and `NixiePrefetchArt` asked for the same posters at once, and 15 to 21 of about 50 picture requests were repeats on a slow server |
| `app/src/main/res/values*/strings.xml` (en, de, es) | `setup_intro` and `address_intro` say "the Jellyfin server you use", `seerr_request_hint` "the server" (were "your (Jellyfin) server"); Nixie's own `nixie_play_strings.xml` likewise; `ServerWordsTest` scans every module's strings, the Kotlin sources' string literals, README, PRIVACY and the issue forms | Design rule 11: the server is someone else's; the publish check found them (docs/PUBLISH-CHECKLIST.md, item 5) |
| `app/.../player/PlayerViewModel.kt`, `PlaybackFailure.kt`, `PlayerScreen.kt` | A failed playback no longer offers the log on the network at once: `PlayerUiState.logAsk` shows one line on the failure card, "Offer the log for download on this network?" with Yes and No (Nixie's `diagnostics/NixieLogQuestion.kt`, `nixie_log_strings.xml`); `answerLog` offers it only after Yes (`shareLog`, Glacier's old body); the focus goes back to Try again | The owner: the log was offered on the LAN by itself; ask first, nothing served until Yes (`LogAskTest`) |
| `app/.../MainActivity.kt` | After a crash, `StartViewModel.crashAsk` holds the question (the page `NixieCrashLogQuestion`, the focus on No, Back says No) and the first screen waits for it; `answerCrashLog(true)` offers the log (`shareCrashLog`, Glacier's old `offerCrashLog` body) | The same |
| `app/build.gradle.kts` | `-Pnixie.stagingAsDebug=true` gives the staging build (R8, optimised, the debug key) the debug build's application id; the staging build takes `src/debug`'s manifest and code too (the adb log's receiver and `AdbLogProvider`), in no release or store build (`AdbLogManifestTest`) | The owner's speed re-check on the box needs the build 1.0 ships as, installed over Nixie debug; TV-S13 found the box's slowness was largely the debug build |
| `app/src/debug/` (Nixie's, from tv-log-pull) | `AdbLogProvider`: the written log to adb's `content` command (size, bytes, removal), only for a caller holding DUMP; `tools/pull-tv-log.ps1` reads through it instead of `run-as` | `run-as` reads the app's storage only on a debuggable build, and staging is not one |

## TV 1.0, round 1 (tv-1.0-r1, 2026-11-23)

| File | Change | Why |
|---|---|---|
| `app/.../library/LibraryScreen.kt` | The list's last card takes `nixieListEnd` (Nixie's `library/NixieGrid.kt`): Right goes to the A–Z rail when it is shown, else stays (`FocusRequester.Cancel`), as Glacier's group ends and last folder do; Nixie's Libraries page does the same on its last card; `ListEndTest` | The quick basics check's Minor: Right on the last card of a short last row (Collections) or a lone card (Libraries) moved the focus up a row, to the bar |
| `app/.../library/LibraryScreen.kt` | The toolbar draws `NixieOrderButton` (Nixie's `library/NixieCollectionsPage.kt`) before the sort button, on the Collections page only: the list's direction in words ("A to Z", "Z to A"; "Newest first" and so on for the other sorts) with its arrow; OK flips it through Glacier's own `setSort` (the active sort picked again), so it is kept with the page's sort (`NIXIE_COLLECTIONS_KEY`); `Toolbar` takes `onFlipOrder`; `CollectionsOrderTest`, the `collections-z-to-a` shot | The owner, 2026-11-23: "we should be able to reverse the direction of the sort in collections". On the throwaway Jellyfin (about 500 box sets) Z to A's first 24 come in 218-280 ms, as A to Z's (212-227 ms warm) |
| `app/src/main/res/values*/nixie_home_strings.xml` | `nixie_order_*`: the order button's words (en, de, es) | |
| `app/.../settings/SettingsScreen.kt`, `SettingsViewModel.kt` | Settings › System › Diagnostics starts with **Report a problem** (`showReport`/`closeReport`, `DiagnosticsState.report`): a page (Nixie's `diagnostics/NixieReportProblem.kt`, laid out like Download log's) whose QR code opens `https://github.com/mbustos27/nixie-tv/issues/new?template=bug.yml` with `version` and `jellyfin` filled in (version numbers only: never an address, name or token); "Show" gets the focus back when it closes; `ReportProblemTest`, `ReportShotsTest` | Decided 2026-11-22 for TV 1.0 (PUBLISH-CHECKLIST item 6): a report from the TV without the app sending anything. The repository is created at 1.0, so the address leads nowhere until then |
| `app/src/nixie/res/values*/strings.xml` | `diag_share_step_attach` names Report a problem (Settings › System › Diagnostics) beside whoever helps the user | The log reaches the report through Download log on the network |
| `app/src/main/res/values*/nixie_log_strings.xml` | `nixie_report*`: Report a problem's row and page (en, de, es) | |

## The basics gate's finding: removing a saved server (tv-gate-a, 2026-11-24)

| File | Change | Why |
|---|---|---|
| `app/.../setup/ServerListScreen.kt` | A saved server's card takes `onLongClick` (Glacier's `GlacierClickable`: a long OK or the Menu key) opening `NixieServerMenu` (Nixie's `start/NixieServerMenu.kt`: the server named, "Remove from this TV"); Remove asks once (`NixieRemoveServerQuestion`, Cancel focused); Back closes either with nothing done and the focus back on the card; after a removal the list's first entry takes the focus. Found servers have no menu | The basics gate (rule 12): a server could be added and switched but not removed, and a long OK on its card opened its "Who's watching?" (the desktop 1.7.x "…" bug's shape) |
| `app/.../setup/ServerListViewModel.kt` | `openMenu`, `closeMenu`, `askRemove`, `dismissRemove`, `remove` (state `menuFor`, `removeAsked`, `refocus`); `remove` calls Nixie's `ServerRemoval` (core/data `nixie/tv/data`: the session left when it is that server's, each profile signed out on the server as its own device, its settings here forgotten, then `AccountRepository.removeServer`, Glacier's, unused until now) and says so in a toast; `ServerRemovalTest` | As above |
| `app/src/main/res/values*/nixie_strings.xml` | `nixie_server_*`: the menu, the question and the toast (en, de, es); "the server", never "your server" (rule 11) | |

## The basics gate's findings (tv-gate-b, 2026-11-24)

| File | Change | Why |
|---|---|---|
| `app/.../detail/DetailComponents.kt` | `CastCard`: the name and the role end in an ellipsis on their second line (`TextOverflow.Ellipsis`); the whole name and role stay the texts TalkBack reads; `CastNameTest` | The basics gate's text check (rule 12): a long cast name was cut at its second line with no "…" ("Maximiliano Alejandro de la", "Siobhán Ní Bhriain-O'Shaughn"), the desktop tester's "cast names" bug on TV |
| `app/.../ui/MediaRow.kt` | The row's `LazyRow` adds its content through `nixieRowWithEnd` (Nixie's `library/NixieRowEnd.kt`): its last card sits in a box whose focus properties end Right (`FocusRequester.Cancel`, tv-1.0-r1's rule for the library grid), so Right on any row's last card stays there; `RowEndTest`, `RowEndKeyTest` | The basics gate: Right on the last card of Home's short Up next row went to the top bar's Home; every row built on `MediaRow` (Home, a title's episodes, cast and "More like this", a collection, a person) now ends the same way. A cancelled exit on the row as a whole held in a JVM test but not on the emulator; `RowEndKeyTest` sends the key through the activity, as the remote does |
| `app/.../search/SearchScreen.kt` | The results grid's last card (a result, "Show N more" or a Seerr title) takes `nixieListEnd` (tv-1.0-r1's rule): Right on it stays; `RowEndTest` | The basics gate: Right on the lone last result of a short last row ("Harbour 24" of 25) went up a row |
| `app/.../detail/DetailScreen.kt` | A title's page keeps `rememberCardReturn` (Nixie's `detail/CardReturn.kt`); its episode cards (`EpisodeRow`, now `internal` for the test, takes `returnTo`) and cast cards take `cardReturn`: OK on a card remembers it with the page's saved state, and Back focuses it again once the page is back; the episodes' row scrolls to that episode, not to the first unwatched one; `CardReturnTest` | The basics gate ("Back is instant", rule 12): Back from S2 E24 landed on "Play S1 E3" with the row back at its start, and Back from a person on the cast card before. Navigation composes the page again on the way back, and nothing put the card back |
| `app/.../detail/CollectionDetail.kt` | Each title's card sits in a box taking `cardReturn`, the same rule | The basics gate: Back from a film of a collection landed on the collection's Play |
| `app/.../detail/DetailScreen.kt`, `CollectionDetail.kt`, Nixie's `library/NixieLibrariesPage.kt` | The page's root takes `cardReturnPage` (tv-fix-b, `detail/CardReturn.kt`): a key pressed on the page drops a return still waiting for its card, as does 2 s without it; an OK's card is kept only if the page is left within 1 s of it (its OK opened the next page); `CardReturnLateTest` | The review of the merged TV fixes: a card that came late (the page loaded again after the app was in the background) took the focus after the user had moved, and an OK that opened nothing was remembered too |
| `app/.../library/LibraryScreen.kt` | Back on the cards of a library opened from another page (`nixieBackToPage`, NixieGrid.kt: not a page the top bar opened; a starred library too when opened from Libraries or a title, tv-fix-b) goes straight back, one press, instead of to the tabs, the bar and back; the Libraries page's cards take `cardReturn`; `CardReturnTest` | The basics gate: Back from Shows opened on Libraries took three presses and landed on Movies |
| `app/.../navigation/Routes.kt`, `NavBar.kt`, `library/LibraryViewModel.kt` | `LibraryRoute.fromBar` (tv-fix-b): set by the top bar's library entries, read as `nixieFromBar`, so a starred library knows whether the bar opened it | The review of the merged TV fixes: a starred library opened from Libraries or a title still took several Back presses |
| `app/.../library/LibraryScreen.kt` | The grid takes `nixieHoldDown` and `NixieHeldDown` (Nixie's `library/HeldDown.kt`): Down on the last row of what is loaded, more titles to come, is held until they come, then goes down; a load that ends with nothing new, or any other key, lets it go; `JumpDownTest` | The basics gate (and a real box): Down right after an A–Z jump in Movies did nothing. Down along the jump's page reached its last row while the next page was still on its way, found no row below and was dropped |

## The review's two Majors in removing a server (tv-fix-a, 2026-11-25)

| File | Change | Why |
|---|---|---|
| `app/.../setup/ServerListViewModel.kt`, `ServerListScreen.kt` | `remove` shows "Removing …" at once (`removing`, a `BusyOverlay`; Back waits for it), runs Nixie's `ServerRemoval.start` (its own scope: the TV's part first, the sign-outs after, at once, each with a timeout), ignores Menu meanwhile, moves the focus only when it was on the removed card (`NixieServerCardFocus`), and at the end sends `UiEvent.Navigate(ServerListRoute, nixieDropServer)` | Review: Back during a slow removal (a server that is down) left a half-removed server; Menu started a second one; the focus jumped to the first entry |
| `app/.../ui/UiEvent.kt`, `app/.../navigation/GlacierNavHost.kt` | `Navigate.nixieDropServer`; the nav host's `nixieDropServerPages` (Nixie's `start/NixieServerGone.kt`) pops every page from the removed server's first ("Who's watching?", sign-in, Quick Connect) and shows the server list on top | Review: Back after the removal led to the removed server's "Who's watching?", whose sign-in did nothing |
| `app/.../setup/SignInViewModel.kt` | A page whose server is no longer saved says so (`nixie_signin_server_gone`) and goes to the server list, at its start and on Sign in | As above: never a Sign in that does nothing |

## The public repository's files (tv-repo-prep, 2026-11-22)

Nixie's own files: `PRIVACY.md`, `docs/PUBLISH-CHECKLIST.md`, and the issue forms `.github/ISSUE_TEMPLATE/bug.yml`
(Report a problem) and `feature.yml` (Suggest an idea).

| File | Change | Why |
|---|---|---|
| `fastlane/`, `website/`, `docs/badges/` | Deleted: Glacier's store listings (Google Play, Amazon), its website and its README badges | Nixie for TV is released on GitHub only and has no website. On a merge of Glacier's updates, a change to any of these is a modify/delete conflict: keep it deleted |
| `scripts/media/` (`capture-website-media.py`, `make-promo-video.py`, `make-readme-badges.py`, `make-social-preview.py`, `promo/`), `scripts/release/` (`amazon-submit.sh`, `play-listing.sh`, `store-notes.sh`), `scripts/website/` | Deleted: the store, website and promo scripts; `make-playback-samples.py`, `release-notes.sh` and the FFmpeg scripts stay | The same: on a merge, keep them deleted |
| `.github/workflows/pr-title.yml`, `.github/dependabot.yml` | Deleted: Glacier's Conventional Commits check on pull request titles, and Dependabot's weekly dependency pull requests | Nixie's history doesn't follow that convention, and Nixie takes dependency updates by merging Glacier's releases (Dependabot's alerts stay a repository setting). On a merge, keep them deleted |
| `.github/ISSUE_TEMPLATE/bug_report.yml`, `feature_request.yml` | Deleted: Glacier's two issue forms, replaced by Nixie's `bug.yml` and `feature.yml` | The app's Report a problem opens `bug.yml` by name. On a merge, keep them deleted |
| `CHANGELOG.de.md`, `CHANGELOG.es.md` | Nixie's: a note that Nixie's release notes are in English, with a link to Glacier's; no versions (the debug build still copies them, `changelogAsset`) | On a merge, keep Nixie's version |
| `CONTRIBUTING.md`, `SECURITY.md`, `CODE_OF_CONDUCT.md` (the contact), `.github/PULL_REQUEST_TEMPLATE.md`, `.github/ISSUE_TEMPLATE/config.yml`, `scripts/README.md` | Nixie's: how to report and send changes to Nixie, private vulnerability reporting on Nixie's repository, Nixie's maintainer as the contact, Nixie's checks and design rules in the PR template, the issue chooser's two links (a security problem, running a Jellyfin server) | They named Glacier's maintainers, store and release workflows. On a merge, keep Nixie's version |

## TV 1.0's release files (tv-1.0-prep, 2026-11-25)

| File | Change | Why |
|---|---|---|
| `docs/RELEASING.md` | Nixie's: the GitHub-only release (the owner signs with apksigner, `nixie-release` checks and writes the `.sha256`, `gh release create` with `Nixie-TV-<version>.apk`, its `.sha256` and `nixie-tv.apk`, the digest and short-link checks, full releases only), the signing key held by the owner, the gate, the staging build, the log pull, the updater's local test with Nixie's names; Glacier's release workflow, store versions, listings and signing secrets gone | Glacier's described a release Nixie doesn't have. On a merge, keep Nixie's version |
| `CHANGELOG.md` | Nixie's header and Nixie's versions (1.0.0 first) above `## [Glacier's releases]`, a heading that is no version: `release-notes.sh` stops there and the updater's `Changelog.parse` skips what follows it until Glacier's `## [0.10.1]`. Glacier's sections up to 0.10.1 stay below as the base | The release notes are Nixie's. On a merge, keep Nixie's header and sections; Glacier's later notes stay in Glacier's changelog, and what Nixie takes from them goes into Nixie's next section |

## 1.0.1: a server that refuses a smaller stream (tv-1.0.1, 2026-11-27)

Nixie's own files: `core/data/src/main/java/io/github/mbustos27/nixie/tv/data/play/SmallerStreamRefusals.kt` (the
refusal memory), `app/src/main/java/io/github/mbustos27/nixie/tv/play/EndsAtClock.kt`, `app/.../nixie/tv/InstallLink.kt`,
`nixie_smaller_refused` in `nixie_play_strings.xml` and `nixie_install_link` in `nixie_strings.xml` (en, de, es), their
tests (`RefusedSmallerStreamTest` with `FakeLink`, `OriginalRetryTest`, `InstallLinkTest`, `EndsAtClockTest`) and the
gate's `RefusedTranscodeGate` on the TV's fake Jellyfin (`tvserver.py --refuse-transcodes --bitrate-test-kbps`).

| File | Change | Why |
|---|---|---|
| `core/data/.../playback/PlaybackRepository.kt` | A refused smaller ask (`askAgain`: an error code or no source) keeps the answer before it, for the link's, the cap's and the display's asks; a refused ladder tier is dropped; `PlaybackSource.smallerRefused` when the kept answer is the file; a NoCompatibleStream is remembered (`refusals`), and the memory skips the ask and the wait for the link test; `downloadBps` timed from the first byte (`BandwidthProbe.Reading`), a read that timed out with no bytes at the floor, a connection or HTTP error no reading | The 1.0.1 Blocker: nothing played in Auto on a server that refuses transcodes |
| `core/data/.../playback/BandwidthProbe.kt` | `reading()` from the first byte; `needsSecond(Reading)`: a second download unless the first shows more than any stream may use, or a slow link with 64 KiB behind its first byte; `SECOND_MIN_BYTES`, `SECOND_MAX_MS` | The link test read a home Wi-Fi link as 440 kbit/s |
| `core/data/.../SessionManager.kt` | `onSignOut` hooks, run by `signOut` and `removeFromDevice` | The refusal memory is forgotten at sign-out |
| `core/data/.../settings/SettingsRepository.kt` | `SettingsState.nixieRefusedSmaller`, `refusedSmaller()`, `updateRefusedSmaller()` | The memory in the app's own settings file, never in the copy on the server |
| `app/.../player/PlayerViewModel.kt` | The notice once per title; `nothingToLower` (also for `smallerRefused`, out of `lowerQuality`'s condition); `watchRecovery` stops for it; `retryOriginal`, and the open's retry after a network error within `openRetries` | The original has nothing smaller to step down to |
| `app/.../ui/Toast.kt` | A toast stays by its length, 2.2 to 6.5 s | The notice is two sentences |
| `app/.../settings/SettingsScreen.kt` | About: "Install on another TV" (`InstallLink`), not in a store's build | The release's short link in the app |
| `app/.../player/PlayerOsd.kt`, `app/.../detail/DetailScreen.kt` | "Ends at" and the player's clock through `rememberEndsAtClock()`: the TV's 12- or 24-hour setting | They were always 24-hour |
| `app/.../trailer/TrailerScreen.kt` | The trailer's clock through `rememberEndsAtClock()` too | It shares the player's place |
| `app/detekt-baseline.xml` | `lowerQuality`'s old condition dropped: it moved to `nothingToLower` | |
