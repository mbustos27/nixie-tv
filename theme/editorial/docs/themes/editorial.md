# Editorial on the TV

Editorial is Nixie's magazine: type-led, serif headings, hairline rules and the sunset rule, on a page of ink (the
desktop theme's dark variant; a white page is no friend of a living room at night). It is the third theme on the
contract in `core/designsystem`. Everything it leaves unset is Glacier's: the focus zoom, the hero's position marks
(square-cornered under the theme's pill) and the still cards.

## What it looks like

| Where | Slot or token | Editorial |
|---|---|---|
| Colours | tokens | Ink `#160610` page, panel `#2A0C18`, text `#FFEFF2`, secondary `#C9B3B8`, figures `#FFC2AE`, kickers `#F7AA9C`. Hot pink `#F96985` is what you can act on; its deep shade `#E44A6B` is light enough for ink text (5:1); text on pink is ink |
| Shapes | `radius`, `pill` | One radius of 8, no oblong pills |
| Display type | `displayFont` | Glacier's display text (the hero's title, the setup headings, page titles) in the serif |
| Glass | `glass*` | Flat tints of white over the ink: a field or a button is a ruled box, not glass |
| Focus | `focus` | A printer's double rule: a 3 dp paper-white rule on the element and a 7 dp pink band outside it, plus Glacier's 1.07 zoom. No glow |
| Page ground | `pageGround` | Ink darkening towards the bottom, with the sunset rule (five flat bands, maroon to peach) along the top edge |
| Top bar | `topBarChrome` | A dense panel strip, radius 8, with the sunset rule along its foot |
| Top-bar tabs | `topBarEntry` | At rest: secondary text. The page you are on: text colour, pink underline. Focused: ink on paper in a pink frame (the fill changes, not only a colour); the underline turns crimson so it still shows on paper |
| Row titles | `rowHeaderAt`, `rowHeader` | A section head: where the page gives the row's position (Home), its number first ("01", "02", in the figures' colour, not read aloud), then the title in the serif (34), the subtitle in small capitals and a hairline from them to the page edge on their baseline |
| Home's hero | `heroBackdrop` | A spread: Glacier's title, facts and buttons stand on plain ink on the start side; the picture is a framed 16:9 plate (768 x 432, 120 from the top, 80 from the end edge) with the sunset rule along its top. The slow zoom moves inside the still frame. Text never sits on the picture, so its contrast does not depend on it, and there is no fade at the hero's foot (`heroFade` = `{}`): the plate keeps its whole frame |
| Hero kicker | `heroKicker` | "CONTINUE WATCHING" in the kickers' small capitals, in place of Glacier's chip |
| Quick Connect | `quickConnectCode` | The code as serif figures on rules, like a form's blanks, in two groups of three for reading across the room; dashes on bare rules until the server sends one. Read aloud digit by digit |
| Sign-in backdrop | `setupBackdrop` | A magazine's opening page: the NIXIE masthead and its rule at the top, a column rule between the intro and the form, and the Googie arc (coral, decoration) with its paper spark under the intro |

The serif is the system's (Noto Serif on Android TV), so the theme ships no font file. All drawing is flat shapes and
strokes: no blur, no live glass, nothing a 2 GB box would pay for.

## Library, item pages and the player (phase 2)

`EditorialScreens.kt` fills `slots.browse`, `slots.item` and `slots.player`:

| Where | Slot | Editorial |
|---|---|---|
| Library heading | `browse.libraryHeader` | The title in the serif (56), the count or folder path under it as an italic standfirst, a hairline under both |
| Filters, seasons | `browse.chip` | As the top bar's tabs: a ruled box at rest, the chosen one underlined in pink, the focused one ink on paper in a pink frame |
| A-Z rail | `browse.letterRail` | Secondary letters, the letter in view in the figures' colour on a panel tab, the focused one ink on paper; empty letters faint |
| Posters | `browse.posterArt` | A plate: the art on the panel inside a hairline frame, as Home's picture |
| Item picture | `item.backdrop` | The picture printed in one ink: multiplied by a dusty rose, washed with ink (62% everywhere, deeper at the start) and fading into the page. Text can stand anywhere on it at 4.5:1 or better, whatever the picture |
| Crumb | `item.crumb` | A short pink tab, then the section in the kickers' small capitals |
| Title | `item.title` | The serif, two lines at most, no shadow |
| Facts | `item.facts` | A colophon: small capitals between hairline column rules, the rating first in the figures' colour with its star |
| Play area | `item.playArea` | Under a hairline, as a byline under its rule |
| Episode stills | `item.episodeStill` | The number in the serif's figures ("01") and the length ("42′") on ink rising at the foot, progress along it, a paper check when watched; neither number is read aloud (the title under the card says them) |
| OSD shade | `player.osdScrim` | Ink, deep where the text stands; the sunset rule along the top edge is the player's only colour |
| Seek bar | `player.seekBar` | A ruler: hairline track, pink played part, intro and credits in peach, chapter ticks, a paper column rule for the playhead. Keys and scrubbing stay Glacier's |
| Times | `player.times` | The serif's figures, the chapter between them as an italic standfirst |
| Skip | `player.skipButton` | An ink plate with a paper edge, paper with ink text when focused, the seconds left after a column rule and the segment's progress as a pink rule along its foot |
| Up next | `player.upNextCountdown`, `player.panelChrome` | The countdown in the kickers' small capitals; cards and track panels on the solid panel under the sunset rule |

`EditorialPhase2Test` measures the item picture, an episode still's foot and the OSD's shades over a pure white
picture (every text colour at 4.5:1 or better) and checks every phase 2 slot is filled. The app's `ThemeScreensTest`
draws the library, movie, series and player screens (`app\build\outputs\roborazzi\Editorial\`).
## Checks

`./gradlew :theme:editorial:test` (add `-PnixieShotsDir=<folder>` to keep the PNGs elsewhere than
`theme/editorial/build/editorial-shots`):
- `EditorialTokensTest`: radius 8 and the pill shape, every colour and slot set, the serif, the section numbers,
  every text pair at 4.5:1 or better (including a tab over a white picture), and a focus that changes the fill, not
  only a colour. The app's `ThemeContrastTest` checks the same floor on Glacier's surfaces.
- `EditorialScreensTest`: sign-in (Quick Connect, with and without a code), the top bar's states and Home at 1920 x
  1080 under Robolectric's native graphics, and the hero's contrast measured on the drawn pixels with a pure white
  picture, with the plate's foot left unfaded. The screens are stand-ins copied from Glacier's layout around each hook (`StandIns.kt`), because the app
  module cannot be a library's test dependency; the app's own Roborazzi tests replace them once they are on main.

The app's `ThemeShotsTest` draws the same three screens with Glacier's real composables
(`app\build\outputs\roborazzi\Editorial\`); both agree.

## Contract additions it uses

Asked by this stream and added on main on 2026-11-21: `displayFont`, `rowHeaderAt` (Home passes each shown row's
position from 0) and `heroFade`.
