# Cinematic on the TV

Cinematic is the backdrop stage: the artwork is the interface. Home opens on one title whose backdrop runs
full bleed under a scrim, with the words over it at the start and the rows beneath. The chrome is held back
to one floating glass island (the top bar), and text sits on dark scrims or plates so it reads over any art.
It is Nixie for Windows' Cinematic theme (`ui\FluentFin\Themes\Cinematic`) retold for ten feet and a 2 GB box:
gradients and shapes only, no backdrop blur (the island casts one soft shadow, as Glacier's bar does), no image
files of its own, no motion beyond Glacier's.

## Tokens (`CinematicTheme.kt`, `CinematicPalette.kt`)

| Token | Value | Why |
|---|---|---|
| void / ground / deep | Night `#1A0710`, `#12050B`, `#2A0F19` | the dark stage; Night is also the text on pink |
| ice / mist | `#FFF6F3`, `#E6D5D7` | warm white and its secondary line |
| accent / deep | pink `#F96985`, deep rose `#D9506A` | Glacier draws Night on both: 6.8:1 and 4.9:1 (crimson would be 3.0:1) |
| glass | Glacier's alphas in a warm white | buttons and fields read as lit glass on the stage |
| radius / pill | 8 dp, `RoundedCornerShape(8.dp)` | no oblong pills anywhere |
| focus | 3 dp white rim in a 16 dp pink glow (4 stacked strokes) | visible from the couch, shape and colour, no blur |
| focusZoom | 1.1 | a focused element grows by a tenth (Glacier: 1.07) |

## Slots (`CinematicSlots.kt`, `CinematicStills.kt`, `CinematicFilmstrip.kt`, `CinematicDusk.kt`)

- **pageGround, the stage:** Night, a faint warm spill from above the middle (a projector's light on a dark
  room), the foot settling into the darkest ground. Three draws.
- **topBarChrome, the island:** one rounded surface of dense plum glass (Tier B: an 85 % tint, not a blur),
  a lit rim along its top, a faint sheen and a soft shadow, so it floats over a hero and the stage alike.
- **topBarEntry:** the page you are on is white with a pink underline; focus is a pink plate with Night text
  and a white rim (Night underline when it is also the page); the rest are the secondary colour.
- **rowHeader, a shelf title:** the display face at 30 with its line beside it on one baseline, then the
  horizon: a coral-to-pink hairline that fades out along the row. A heading for TalkBack.
- **heroBackdrop:** the art full bleed (Glacier's slow zoom kept), a side scrim under the words that clears
  by 84 % of the width, a band under the island at the top, and the foot settling into Night.
- **heroKicker:** while resuming, small tracked capitals in peach ("CONTINUE WATCHING") in place of the chip.
- **heroPositionMarks, the pager:** the titles' stills at the bottom right, at most seven around the current
  one; the current one larger with a white rim and the time to the next title as a pink line under it; the
  others dimmed. Decoration, as Glacier's dots are.
- **stillCard:** the 16:9 still with a shade rising from its foot, the title (display face) and its line on
  the shade, the progress under them; focus adds the sheen at the top corner over Glacier's zoom and rim.
- **quickConnectCode, the filmstrip:** one lit frame per character on a dark film base with sprocket holes
  along both edges; dim dashes until the server sends a code. TalkBack reads the characters one by one.
- **setupBackdrop, the dusk still:** a vector dusk on the road: plum sky warming only near the horizon, the
  sun setting at the end of the road at the far right (beyond the setup columns), a mesa, a far butte, a
  saguaro standing in the gutter between the two columns, a vignette.

## Phase 2: library, item pages, player (`CinematicBrowse.kt`, `CinematicItem.kt`, `CinematicPlayer.kt`)

- **Library:** the title in the display face over its line and a short horizon; chips and A–Z letters in the
  island's language (focus: a pink plate, Night text, a white rim; the chosen one: a faint plate, white text, a pink
  underline on chips); posters with a lit top edge and the sheen on focus.
- **Item pages:** the backdrop stage (art full bleed, a side scrim long enough for an 84 sp title, a band under
  the island, the foot settling into Night); the crumb in peach capitals; the title with a soft shadow; facts
  with the age rating and the picture badge on plates; a horizon over the Play area; episode stills with the
  number (pink when it is the page's episode) and the runtime on plates, a check when played, the progress
  along the foot. Two strings (episode number, runtime) in English, German and Spanish, as Glacier says them.
- **Player:** deep shades at the top and foot; the seek bar as a strip of film (a dark band with lit edges,
  pink progress, peach intro and credits, chapters as frame lines, perforations and a glowing white playhead
  while focused; keys and scrubbing stay Glacier's); times with the chapter in peach capitals; Skip on a dark
  plate with its seconds on a small plate and its time draining along the foot; Up next's countdown in peach
  capitals; cards and panels on a dense fill with a lit rim (no blur over video).

## Contrast (measured on rendered pixels, `CinematicContrastTest`)

The brightest pixel where text can sit, against each text colour; written to `contrast.txt` beside the shots.
The app's `ThemeContrastTest` checks the token pairs (page, sheet, glass, both accents) for every theme.

| Where | Worst case | Secondary text | White text | Pink / peach |
|---|---|---|---|---|
| Hero info column (80 to 980 dp) | pure white backdrop | 6.6:1 | 8.8:1 | pink 5.7:1, peach 8.6:1 |
| Still card, the words (focused, sheen on) | pure white still | 6.5:1 | 8.7:1 | (none) |
| Island, middle of the bar | pure white behind it | 9.1:1 | 12.0:1 | 3:1+ (underline only) |
| Sign-in columns (130 to 1656 dp) | the dusk still | 6.2:1 | 8.3:1 | 3.1:1 (large text only) |
| Stage | the page ground | 12.4:1 | 16.4:1 | 6.2:1 |
| Item page: title to 1260 dp, words to 980 dp | pure white backdrop | 7.9:1 | 6.9:1 (title edge) | pink 5.6:1, peach 8.5:1 |
| Player, top shade (title, clock) | pure white picture | 6.4:1 | 8.5:1 | (none) |
| Player, foot shade (times, buttons) | pure white picture | 9.8:1 | 13.0:1 | peach 7.3:1 |

Pink on the setup screens is only a focused server's name (26 sp display: large text, 3:1).

## Screenshots

- Glacier's real screens: the app's `ThemeShotsTest` (sign-in, top bar, Home) and `ThemeScreensTest` (library,
  movie, series, player), in `app\build\outputs\roborazzi\Cinematic\`.
- This module's stand-ins (`CinematicScreensTest`): the hero over a bright backdrop and over pure white, with
  the pager and sample stills; the filmstrip waiting for its code; the stage under a page.
  `./gradlew :theme:cinematic:testDebugUnitTest -Pnixie.shots=<folder>` (default
  `theme/cinematic/build/shots/cinematic`).

## What Cinematic could use next

- Nothing blocks. Later, from Windows' Cinematic: the player's tabs dropping a panel from the top, and the
  library's preview stage (the focused film filling the top of the page).
