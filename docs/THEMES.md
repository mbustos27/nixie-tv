# Nixie's themes on Glacier

Nixie for TV is Glacier's app with Nixie's looks laid over it. A theme never rewrites a Glacier screen: it gives
**tokens** that Glacier's design system reads, and **slots** that Glacier's screens call through small hooks marked
`// Nixie:`. Whatever a theme leaves unset stays Glacier's own, so upstream screens keep working and keep merging.

## The contract (`core/designsystem`, package `io.github.mbustos27.nixie.tv.designsystem`)

| File | What it holds |
|---|---|
| `NixieTheme.kt` | `NixieThemeId` (DriveIn, Cinematic, Editorial, Glacier), `NixieThemeSpec(id, tokens, slots)`, the `NixieTheme` switch (`register`, `use`, `current`), `NixieBrand`, `NixieFeatures` |
| `NixieTokens.kt` | `NixieTokens`: colours (void, deep, four glass values, ice, mist), the accent pair, one radius, the pill shape, `FocusLook`, the ground, `displayFont`, `focusZoom`. Unspecified or null keeps Glacier's value |
| `NixieSlots.kt` | `NixieSlots`: `pageGround`, `topBarChrome`, `topBarEntry` (returns `NavEntryLook`), `rowHeader`, `rowHeaderAt` (with the row's position), `heroBackdrop`, `heroFade`, `heroPositionMarks`, `heroKicker`, `stillCard`, `quickConnectCode`, `setupBackdrop`. Null keeps Glacier's drawing |
| `NixieFocus.kt` | `themedFocusFrame`, the focus frame a `FocusLook` draws (stacked strokes, no blur) |
| `NixieScreenSlots.kt` | Phase 2, reached as `slots.browse`, `slots.item`, `slots.player`: `BrowseSlots` (`libraryHeader`, `chip`, `letterRail`, `posterArt`), `ItemSlots` (`backdrop`, `crumb`, `title`, `facts`, `playArea`, `episodeStill`), `PlayerSlots` (`osdScrim`, `seekBar`, `times`, `skipButton`, `upNextCountdown` (seconds null = no countdown, autoplay off), `panelChrome`); the `Artwork` type |
| `NixieModifiers.kt` | `underline` (a look's mark), `nixiePlayArea`, `nixiePanelChrome` |
| `NixieVisuals.kt` | `GlassTier` (Live, Fake, Flat) and `VisualCapabilities` (`liveBlur`, `shadows`, `ambientMotion`, `blurredImageWidth`), detected at start from the box's memory; `NixieVisuals.preBlurred(url, width)`; `nixieShadow { }` gates a soft shadow. Themes read `NixieVisuals.current` and never blur live below Live. Debug: `--es nixie.glass Fake` |
| `NixieTubes.kt` | Nixie tubes any theme may use (`NixieReadout`, `NixieTube`), Nixie's mark and loading tube |

`NixieTheme.current` is snapshot state: everything that read a token or a slot is redrawn when it changes, so a theme
switch needs no restart, and every ViewModel, the back stack and the focus survive it.

Rules for a theme:
- Text reads at 4.5:1 or better. `ThemeContrastTest` (app) checks every filled-in theme's page, sheet, glass and
  accent pairs, including Glacier's text on the accent's deep shade (primary buttons).
- Slots take plain values and callbacks only, never a repository or a ViewModel.
- The plan's shape rules: no side rail, no oblong pills (radius 8 at most where width is not height), glass on chrome
  only, focus never by colour alone, text at least 4.5:1.
- The contract grows by adding a slot or token with a null or Unspecified default. Changing an existing one is a
  breaking change: tell every theme's owner first.

## Writing a theme

Each theme is its own module: `theme/drivein`, `theme/cinematic`, `theme/editorial` (namespace
`io.github.mbustos27.nixie.tv.theme.<id>`). The module exposes one value, `<Name>Theme: NixieThemeSpec`, and keeps
everything else inside the module. The app registers all three in
`app/src/main/java/io/github/mbustos27/nixie/tv/NixieThemes.kt`; a theme stream edits only its own module.

Glacier's hooks that read the contract (each marked `// Nixie:`):

| Hook | File |
|---|---|
| Colours, accent, radii | `core/designsystem/.../GlacierTokens.kt` |
| Pill shape | `core/designsystem/.../Controls.kt` |
| Focus frame and zoom | `core/designsystem/.../Focus.kt` |
| Page ground | `core/designsystem/.../GlacierBackground.kt` |
| Mark and loading tube | `core/designsystem/.../Mark.kt` |
| Top bar chrome and entries | `app/.../navigation/TopNav.kt` |
| Row header (and its position) | `app/.../ui/MediaRow.kt`, `app/.../home/HomeScreen.kt` |
| Display face | `core/designsystem/.../GlacierTypography.kt` |
| Home hero backdrop, its fade, position marks, kicker | `app/.../home/Spotlight.kt` |
| Still card (Continue Watching) | `app/.../ui/Cards.kt` |
| Quick Connect code | `app/.../setup/QuickConnectScreen.kt` |
| Setup backdrop | `app/.../setup/SetupComponents.kt` |
| Library heading, A–Z rail | `app/.../library/LibraryScreen.kt` (`Header`, `RailLetter`) |
| Chips (library filters, season picker) | `app/.../ui/FilterChip.kt` |
| Poster art (grid and rows) | `app/.../ui/Cards.kt` (`GridCard`, `PosterCard`) |
| Item backdrop | `app/.../ui/Backdrop.kt` (`DetailBackdrop`) |
| Crumb, title, Play area | `app/.../detail/DetailScreen.kt` |
| Facts line | `app/.../ui/Facts.kt` |
| Episode still | `app/.../detail/DetailComponents.kt` (`EpisodeCard`) |
| OSD shade | `app/.../player/PlayerScreen.kt` (`osdScrim`) |
| Seek bar, times, OSD glass and grey | `app/.../player/PlayerOsd.kt` |
| Skip button, Up next countdown and card chrome | `app/.../player/PlayerPrompts.kt` |
| Track menus' panel | `core/designsystem/.../OptionsPanel.kt` |

## Switching themes

- **On a debug build:** `adb shell am start -n io.github.mbustos27.nixie.tv.debug/io.github.glacier_jellyfin.androidtv.MainActivity --es nixie.theme Cinematic`
  (`DriveIn`, `Cinematic`, `Editorial` or `Glacier`). The choice is kept on the device until the next one.
- **In code and tests:** `NixieTheme.register(spec)` then `NixieTheme.use(NixieThemeId.Cinematic)`.
- **In Settings > Appearance:** the Theme row offers Drive-in (the default), Cinematic and Editorial, and Glacier's
  original look in debug builds only (`NixieThemes.pickable`). The switch is live: the focus stays on the picker and
  every page keeps its data (`ThemeSwitchTest`).

## Checking a theme without the emulator

`app/src/test/java/io/github/mbustos27/nixie/tv/ThemeShotsTest.kt` draws every registered theme's sign-in (Quick
Connect), top bar and Home with Glacier's real composables and sample data, on the JVM (Robolectric 4.18 with native
graphics, Roborazzi 1.78.0), at 1920 x 1080 in D-pad mode, with the element a user lands on focused:

```powershell
.\gradlew.bat :app:testNixieDebugUnitTest --tests "*ThemeShotsTest*"
# app\build\outputs\roborazzi\<DriveIn|Cinematic|Editorial|Glacier>\<sign-in|top-bar|home>.png
```

`ThemeScreensTest` does the same for phase 2's screens (`library`, `movie`, `series`, `player`: the OSD with the Skip
button and the Up next card), from Glacier's public parts where its page layouts are private, reaching every slot
of `slots.browse`, `slots.item` and `slots.player`.

Both run every screen twice per theme: at the default text size and at Android's large text (font scale 1.3, which
Android 14 applies non-linearly: large type grows less than small type). The large-text images are
`<theme>\<screen>@1.3x.png` beside the others; a theme's text must fit at both.

The images are written on every run and compared with nothing yet; the test fails only when a screen cannot be drawn
or the focus does not land. A new slot gets its screen here with the hook that calls it.

