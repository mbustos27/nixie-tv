# The owner's TV session: results (2026-11-21, evening)

On a Google TV box over Wireless debugging, with the debug build of `7f1c9f6` installed as **Nixie debug**. The owner
signed in to the server they use with Quick Connect; no agent saw the code. The owner drove the TV, and the
coordinator only installed the app and read frame timing and memory (with the owner's OK at the time).

## What the owner checked
| Check | Result |
|---|---|
| Episodes (pages, season picker) | **Good**: "episodes feel responsive" |
| Playing a movie, seek, subtitles | **Good**: "movie and subtitles are smooth" |
| Typing in search | **Good** |
| Editorial | **Good**: "editorial looks decent" |
| First load of a library | **Slow** (finding TV-S1) |
| Scrolling down a library | **Slow** (finding TV-S2) |
| Trailer controls | **"Kind of weird"**, no details (finding TV-S3) |
| Search results | **"Takes wayyyy too long"** (finding TV-S4) |
| DTS or TrueHD audio | Not tested (none came up) |
| Skip intro, Up next | Not seen (an intro needs the server's markers; Up next needs an episode played to its end) |
| Cinematic | No comment |

## Frame timing and memory (`dumpsys gfxinfo` and `meminfo`, three reads)

What the three reads over the session taught (the box's own numbers are left out):

- **The library scroll** was the one place with many janky frames, about one in nine. The causes were slow UI-thread
  work and slow draw commands, not images: composing and laying out the poster grid is the cost.
- **Memory is fine.** A large Java heap in the first read was uncollected garbage, a small fraction of it after
  collection; graphics memory grew after playback and two theme changes, as expected.
- **Typical frames ran at about 30 a second.** Check the box's output refresh rate before treating that as a problem.
  The modern jank count (deadline misses) stayed under 4 %.

## Findings for the TV fix round
- **TV-S1, a library's first load is slow** on a big library (tens of thousands of films). Show the first page while
  the rest pages in, as desktop Nixie's big-library reading does (paged requests, a small first page, `Fields` kept
  to what the grid needs).
  - **Fixed** (stream L, `tv-library-perf`: 7fe7103, 883a4c8): while a library's index is not in memory, its first
    page of 70 comes straight from the server in the server's order (only the grid's fields, the count with the
    first page only) and the next pages follow it; the index the A–Z rail needs comes in pages of 1,000, two at
    once. A sort or filter change cancels the old list's page, refresh and index. On the emulator (debug build,
    the TV test server with 25,000 films, one session for both): the first row came **28.7 s** after OK on Movies
    before, **0.68 s** after; reopened after a restart (index saved on the device), the first page's data came
    after **3.7 s** before, **0.30 s** after. An earlier session: 30.9 s and 6.1 s before, 0.80 s and 0.24 s after.
  - **The review's follow-ups** (232bd7c, 40f0811, 4d90858, 9beb3ee, cb6a622, d1abb552, 4d95433b): each title once
    in the grid while the server's list moves (pages join without repeats, start 5 titles back, and every server
    order ends with the sort name and the date added), so a film added between pages no longer crashes it; a wait
    for the index comes in small pages counted on screen, only the background index in big pages; opening a list
    asks the server for no index (the rail's letters from memory or the device; the index on a jump, or in the
    background after 280 titles), and four indexes stay in memory for the session; a repeated group gets its own
    heading. In one emulator session after them: first row **26.5 s → 0.66 s**, after a restart **3.6 s → 0.22 s**.
- **TV-S2, scrolling a library is slow:** about one janky frame in nine, from UI-thread and draw work.
  - **Fixed in code, not proven on the emulator** (stream L: 5c2c9ff, 9ea202a, f252f4d): a move in the grid used to
    compose the whole library screen and every card on screen again (the A–Z rail's letter and the focus memory
    were read by the screen). Now a move composes only the two cards it moves between: `LibraryMovesTest` counts,
    for three moves, the screen 3 → **0** times and the cells 42 → **6**. The cells have content types, the grid
    composes about two rows ahead, artwork is asked at the card's size (240 wide for a 216-pixel poster) and the
    next three rows' artwork is fetched ahead; nothing is drawn under an opaque poster or as a transparent border.
    The screenshots are byte-identical to before. The emulator cannot show it: its frames wait on its own
    GL drawing (a trace: about 90 ms of render-thread flush per traced frame), the host CPU was at 96–100 % with
    other runs, and its numbers moved more between sessions than between builds. Paced D-pad scroll, janky
    frames: before 19 % and 37 % (two sessions), after 39 % and 41 %; held-key bursts: before 39 % and 47 %, after
    33 % and 39 %. The under-5 % target stays open for the owner's re-check on the box. After the review's
    follow-ups (posters fetched ahead are cancelled when no longer ahead or when the screen is left, the visible row
    first): paced 17 % before, 21 % after; bursts 38 % before, 30 % after; UI-thread frames over 16.6 ms 19 % and
    20 % (paced), 22 % and 20 % (bursts): the same noise.
  - Give the poster cells stable keys and content types.
  - Make each card cheaper to compose (no per-cell allocations, no layout passes from text measurement).
  - Prefetch the next rows' artwork at the grid's poster size.
  - Measure with the emulator's frame timing before and after.
- **TV-S3, the trailer controls feel odd.** The owner gave no details. Make the trailer player behave like the movie
  player:
  - the same control layout and remote keys (OK plays or pauses, Left and Right seek);
  - Back closes the trailer and returns to the movie page, with the focus on the Trailer button.

  Compare the two in the emulator to find what differs.

  **Fixed:** (stream Q, `5a9eafb`) what differed (seen on the emulator with a local trailer), now the movie player's:
  - with the controls hidden, OK and Left/Right only woke them; now OK plays or pauses and Left/Right scrub at once;
  - the seek bar jumped at every press by a fixed 10 s back and 15 s forward, and OK on it did nothing; now the knob
    scrubs by the profile's seek steps and the jump follows after 1.2 s or OK;
  - pausing left the focus on Play (now on the seek bar); the controls hid after 5 s (now 3 s);
  - its shades, seek bar, times and clock were its own, not the theme's; now the movie player's, through the theme;
  - the menu key now shows the controls, in both players;
  - Back from the trailer landed on Play; now on the Trailer button of the movie page.

  YouTube trailers still move into their frame top right while the controls show (YouTube forbids drawing over its
  player). On the emulator: OK on the bare trailer now pauses with the seek bar focused (before: the controls came
  up, still playing, with the focus on Pause); Right scrubs; Menu shows the controls; Back lands on Trailer.
- **TV-S4, search results take far too long** (typing is fine, so it's the query). Do as desktop 1.5's search did:
  - wait for a pause in typing;
  - one request with a small `Limit` per type, and only the needed `Fields`;
  - show results as they arrive;
  - cancel a request when the text changes.

  **Fixed:** (stream Q, `c99cb9f`) one request per kind (shows, movies, episodes, collections, albums: 12 to 24 each,
  only `SortName`, one image, no total) plus people, all at once, each shown as it answers; titles by a matching person
  or genre (weak matches) asked for only after them; a 300 ms pause; nothing asked for one letter; the search for
  earlier text cancelled with its requests. The ranking is unchanged.
  - Before: the page waited for one request over five kinds (limit 60) plus Glacier's search (titles again, people,
    genres, then their titles). Against the 25,000-film test server, that set took 0.78 to 0.97 s on the server alone
    before anything showed; now the first kind is back in 26 to 35 ms and all kinds in 0.49 to 0.64 s.
  - On the emulator against the same server, from the last key to the first result on screen (the first poster asked
    for), for "silent", "harbor" and "glass": **before 3.12, 2.56 and 2.52 s; after 1.03, 0.87 and 0.88 s**
    (`e4234cf`: 1.12, 0.89 and 0.81 s). That includes the 300 ms pause; the app's log puts the first titles 0.22 to
    0.65 s after the requests start. The target (under 1 s) holds for two terms of three; "silent", with the most
    matches, takes 1.0 to 1.1 s.
  - `dumpsys gfxinfo` over the typing and the results (8 s each): before 44 to 63 % janky frames (p50 48 to 53 ms),
    after 48 to 91 % (p50 48 to 77 ms). Not a clean comparison: the runs were in different sessions on the
    emulator's software rendering, with the host's CPU at 96 to 100 % from other streams. The results now land in a
    few steps; `e4234cf` sends no step for an empty answer and keeps the grid's list when the shown titles did not
    change. Worth a look on the box.
  - After the review (`4da58f0` to `cb1aa10`): steps reach the screen at most every 150 ms and the grid keeps the best
    match in view; episodes wait for the shows; one failed request no longer fails the search; one character is
    searched after an 800 ms pause or on Enter. Measured again in **one** session, with the host calmer (CPU 29 %
    at the start), before and after side by side: **before 2.12, 2.10 and 2.16 s; after 0.72, 0.78 and 0.89 s**, all
    three under 1 s. Janky frames over the same 8 s: before 21.0, 10.9 and 12.3 %; after 18.7, 12.6 and 9.4 % (p50
    34 to 36 ms in both): no worse than before.
- **TV-S6, Home takes far too long on every start** (the owner, 2026-11-22, build 10099; a Blocker). The box's
  detailed log: tens of seconds from the app's start to Home after standby, then more before the rows were in; Watch
  Next emptied the launcher's rows before the profile was restored and again at Home, and refilled them only long
  after the start.
  - **The cause:** the start waited on the server three times before Home (its id, its user list for "the single
    profile", the token), each waiting on the network after standby; Home then waited for every part before it drew
    (the libraries with a total count each, a query over a whole library; resume, Up next, the favorites, "New in" per
    library, music, Live TV; then the hero's own request), every list asking for a total count and for the overview,
    genres and streams of every card; Watch Next's load and the start's cleanup ran beside it, and the cleanup read "no
    profile yet" as "another profile".
  - **Fixed** (stream H, `tv-home-start`): c8a74380, 293f523b, de0ffddb, 13bf6207. The start
    opens the profile from what is saved and checks the server after Home opened; Home draws at once from the page it
    saved last (per profile and age limits), then each row replaces its part as it answers, on its own (with nothing
    saved, Home draws once Continue watching is in); no row asks for a total count, each asks only for what its cards
    show; the libraries' counts come last; the hero is asked beside the rows; Watch Next's load, the channels and the
    start's cleanup wait for Home, and the cleanup waits for a known profile and removes only another profile's rows.
    The detailed log now has the durations of the start's steps, each Home row, the hero, Home on screen and the
    channels' load and write (no titles, no names).
  - **Measured on a real Jellyfin** (the throwaway test instance, about 25,000 films; the test user with 24 titles under
    way, 30 watched, 6 favorites). The requests alone, from the PC, three loads each: main's Home 0.22 to 0.33 s, all
    at once; now the first row's data 77 to 87 ms and everything, counts included, 140 to 150 ms. On the emulator
    (debug build; the host's CPU at about 88 % from other runs, so the numbers move), from the launch to Home's first
    row on screen and to all of Home (main draws everything at once):

    | Start | main `aff8d55b` | `tv-home-start` first row | all of Home |
    |---|---|---|---|
    | Cold | 3.6, 3.7, 10.5, 5.6 s | 4.5, 3.9, 3.7, 4.6 s | 4.7, 4.2, 3.9, 4.9 s |
    | Warm (the app still running) | 2.6, 1.4, 1.8 s | 0.84, 1.01, 0.71 s | 1.06, 1.26, 0.89 s |
    | Cold, the server 400 ms away (a proxy) | 6.8, 7.0, 7.2 s | 5.1, 4.9, 4.5 s | 6.3, 6.1, 5.6 s |
    | Warm, the server 400 ms away | 3.9, 3.9 s | 0.87, 0.85 s | 2.00, 2.12 s |

    With nothing saved (the first start, or after clearing): 3.5 to 4.0 s cold, 5.3 s with the server 400 ms away. The
    targets hold warm (first row under 2 s, all of Home under 5 s); cold, the first row stays at 3.7 to 4.6 s on this
    emulator, and that is no longer the server's: the first screen is chosen 1.6 s after the process starts, the profile
    opens at 1.8 s (0.6 s earlier than before the warm-ups), and Home's first frame with its rows then takes 0.8 to
    1.2 s to compose in the debug build. Main's cold time grows with the server's distance (each of its three start
    requests and its two rounds of Home requests waits for it); the new start waits for none of them. On a box, the
    long wait before Home and the channels' load are off the way to Home. Worth the owner's re-check: the start to
    Home's first row, and the launcher's Continue watching staying put across a restart.
  - **The cold start, split** (the coordinator's follow-up; the detailed log now has each mark). Debug build, five cold
    starts, ms since the process started: Application.onCreate begins at 360–430 (the platform and the startup
    providers), is done at 590–710 (Hilt about 150, now built off the main thread: 80–130 on a host twice as loaded),
    the activity's content is set at 660–780, its first frame at 1,170–1,420; the start profile opens from what is
    saved and Home is the first screen at 1,500–1,730 (the profiles screen is no longer on the way: 0.3 to 0.5 s);
    Home draws its saved page at 2,090–2,510 and its first row is on screen at 3,260–3,690 (before these: 3.7 to
    4.6 s). Up to the first frame that is the floor here: an empty Compose app's debug build takes 2.1 to 2.9 s to its
    first frame on this emulator (Nixie 1.75 to 1.94 s, `am start -W`). The release-like `staging` build (R8, its
    baseline profile): first frame at 170–210 ms, Home's saved page drawn at 640–900 ms (an empty Compose release
    app: 0.57 to 0.9 s to its first frame), first row on screen at 2.0 to 2.5 s; the gap is the emulator's software GL
    drawing Home's first two frames (Android's "Davey" lines: 1.39 s and 0.99 s, 0.85 s of it in SwapBuffers), not
    the app's work. What is left in the app: the first read of the encrypted accounts (about 0.4 s in staging, the
    Keystore), on the way to the first screen.
- **TV-S10, "Quit Nixie?" does not quit while Home is still loading** (the owner: Back from a trailer to Home, still
  loading, Back again: the dialog came up but Quit did nothing until the rows were in). Major.
  - **The cause:** Home's loader blocks every key but Back while nothing is drawn, and the dialog sits inside it, so
    OK on Quit was swallowed (Back reaches the dialog through the dispatcher, not as a key).
  - **Fixed** (stream H, c8a74380): the loader's key block is off while the dialog is open, no first-focus request
    runs under it, and Home opened again (the top bar's Home builds a new page) draws at once from what it showed
    last. `HomeQuitTest`: Back while Home loads, then OK on Quit quits at once; rows coming in under the dialog leave
    Quit focused. With the key block back, the first test fails.

- **TV-S7, a 1080p cap on a 4K TV** (a box's log, 2026-11-22: every playback logged `Transcodes for a display of
  1920x1080` on a 4K TV). **Confirmed, for transcodes**: Glacier took the UI's size
  (`Resources.getSystem().displayMetrics`) for the display, and Android TV draws its UI at 1920x1080 on a 4K panel.
  - Direct play was never capped: its profile takes the decoders' own limits (4096x4096 for HEVC on that box), so a 4K
    file the box decodes played as it is.
  - But a 4K title the server had to convert was asked again with the picture capped at 1920x1080
    (`PlaybackRepository.openNow`, "made for the display"). A film whose audio alone was converted (its picture copied)
    then had its picture converted down to 1080p: worse picture, and a 4K re-encode the server didn't need to do. A
    picture the server converts anyway (Dolby Vision profile 5, HDR on a display without it) came at 1080p, and the
    adaptive ladder's tiers stopped at 1080p.

  **Fixed:** (stream P, `tv-player-4k`: 228c58fe) the display mode's physical size (`Display.Mode` through `DisplayCompat`, as the
  version chooser and the info panel already read it), in Nixie's `DisplayPanel`; the UI's size only where the mode
  says nothing or less. `PanelPlaybackTest` opens titles through the real `PlaybackRepository` against a fake server
  that decides like Jellyfin's StreamBuilder, with a 4K box's measured decoders:
  - on the 4K panel, a 4K HEVC Main 10 HDR10 film whose DTS-HD MA track is converted keeps its 4K picture (copied);
  - Dolby Vision profile 5, and HDR10 on an SDR display, are converted at the film's size;
  - a 1080p display still caps the conversion at 1920x1080;
  - a 4K HEVC Main 10 film plays directly on either panel, with one request;
  - Dolby Vision plays directly only where the display shows it, and the ranges offered are the display's, as before.

  With the UI's size put back, three of the six fail (the picture converted at 1920x1080). On the emulator, with its
  UI made smaller than its panel (`wm size 1280x720` on the 1920x1080 display, as Android TV's 1080p UI sits on a 4K
  panel), playback logs `Transcodes for a display of 1920x1080`: the panel, where Glacier read 1280x720. A 4K panel
  needs the box: the emulator has none (nor HEVC Main 10).

  **And on a 1080p display** (the coordinator's follow-up): a 4K picture the device decodes, whose audio alone the
  server converts, is no longer asked again at 1080p. The server copies it (Glacier: it re-encoded it to 1080p, far
  more work for someone else's server), and the decoder scales it down. A picture the server converts anyway (HDR on a
  display without it, Dolby Vision profile 5) still comes at 1080p. `PanelPlaybackTest` has both; without the rule the
  copy case fails.

- **TV-S8, the player held all night in standby** (a box's log, 2026-11-22). Playback was paused at night and the
  picture's surface went to 0x0, but the player was held until the morning; the live-updates socket redialled every
  minute all night (`UnknownHostException`).

  **Fixed:** (stream P, `tv-player-4k`: 8481c88c)
  - When the app is stopped (Home, standby, the screensaver), the player screen tells the server where the title
    stands (which ends its transcode) and releases the player. When the app comes back, the same title opens at the
    same place, paused, with its tracks, the controls up on the seek bar. Stopped before the first frame, the open is
    made again as it was asked. The title and position are also in the screen's saved state, so a screen restored after
    its process went away opens paused at the same place.
  - The live updates close their socket while the app is in the background and try nothing until it comes back, then
    connect at once. A sign-in whose token was refused stays stopped. In front, the wait between tries grows to five
    minutes (it was one).
  - `PlayerStandbyTest` (the real `PlayerViewModel`, Media3 faked) and `LiveUpdatesBackgroundTest`; with the release
    or the background rule taken out, two of five and two of four fail.
  - On the emulator, playing at 1:25: Home logs `the player is released at 85 s` and `live updates pause`; back in
    the app, `opening the title again at 85 s, paused` and the live updates connect again, the title paused at 1:25
    with the seek bar focused. The sleep key (standby) and waking do the same (released and back at 90 s).

- **TV-S9, a trailer takes far too long and opens small** (the owner on the box, 2026-11-22: it opened only after a
  long wait, small at first, then full screen).
  - **The path.** Each trailer's start now goes to the detailed log, step by step, as durations (design rule 10, `131eb5a0`).
    On the emulator (the TV test server, its local trailer, and a YouTube trailer):
    | Start | Steps (seconds from the press) |
    |---|---|
    | Server file | details 0.13 to 0.16, server answer 0.76 to 0.89, first frame 2.2 to 3.0 |
    | YouTube, first in the app (nothing cached) | web view 2.71, IFrame API 3.89, player ready 5.85, page 7.10, first frame 8.79 |
    | YouTube, again (warm) | web view 0.54, IFrame API 1.12, player ready 2.38, page 2.55, first frame 5.06 |

    So YouTube is the slow path: the web view's first creation (2.7 s, on the main thread: the screen froze on what
    was there before), then YouTube's own page, API and player. A box slower than the PC's emulator multiplies these;
    the next check with detailed logging gives the box's own split (`Trailer start (YouTube): ...`).
  - **The jump.** A new trailer showed the controls, and YouTube forbids drawing over its player, so its first frame
    came in the small frame top right; 2 s later the controls left and it grew to full screen. The full-screen picture
    came about 2.4 s after the first frame.

  **Fixed:** (stream P, `tv-player-4k`: d05bca85, 612ae2ff)
  - A YouTube trailer starts without the controls, so its first frame is full screen (a key brings them, and the
    frame, as before). On the emulator: the loader, then the first frame full screen, no small frame (before: the small
    frame, full screen 2 s later).
  - The loader (backdrop, spinner, the trailer's name) shows at full size from the screen's first frame, not once the
    title's trailers are known.
  - The page preconnects to the embed host and YouTube's image host. On the emulator, warm, no measurable gain: first
    frame 5.06 s before, 5.05 s after.
  - In the background the trailer's web view and its timers pause (design rule 5). A server file's player is released,
    its place kept, and opens there again paused when the app comes back, as the movie player (`TrailerStandbyTest`;
    without the release it fails).
  - `TrailerStartTest`, `TrailerTimingTest`, `StartTimingTest`; with the start rule or the timing hook taken out, they
    fail.
  - **Made ahead** (the coordinator's follow-up: on a slow box the cold path is most of the wait). Once a movie or show
    page is drawn (design rule 1), and again when its Trailer button takes the focus, the page makes the player's
    **shell** ahead: one web view, the page, YouTube's IFrame API and player with no video. That is the same for every
    title, so YouTube learns nothing about what is browsed (Nixie's rule for outside services: nothing about the
    library or what is looked at leaves unless asked for). Pressing Trailer hands the shell to the trailer screen, which
    names the video then (`startVideo`); the timing line says "YouTube, made ahead". The shell is let go a second
    after the page stops or is left (the trailer screen claims it in that second), nothing is made in the background,
    and a title with a trailer file on the server (played first, Glacier's order) makes nothing.

    A first version cued the title's own video ahead (2.38 s cold); it told YouTube which titles were browsed, trailer
    played or not, so it was replaced by the shell the same day (`d8ac851d`).

    On the emulator (8 s on the page before pressing Trailer; cold = the app's data cleared and the emulator rebooted;
    cached = the second press):

    | | Cold, first frame | Cached, first frame |
    |---|---|---|
    | Before | 5.45 s (web view 0.82, API 1.71, player ready 2.76) | 2.69 s |
    | Shell made ahead | **3.14 s** (web view, API and the empty player ready before the press) | 2.81 s |

    Home on the page lets the shell go, and the return makes it again. `TrailerWarmupTest` checks, through the movie
    page's composable, that nothing the shell loads or runs carries the video's id before the press (with the title
    named in the shell, it fails) and that every title's shell is the same page; `TrailerStartTest` that the trailer
    screen takes the shell and names the video; without the hand-over or the second's grace, they fail.
  - Left: YouTube's own start after the hand-over (the video, about 2 s on the emulator) is not ours to shorten.

- **TV-S12, back to a paused title "loaded 3 times"** (the owner's check of build 10101, item 7: pause, Home, come
  back: "same place but it loaded 3 times").
  - **The cause** (the owner's detailed log, one pause, Home and return): the way back was the launcher's Continue
    watching, a new intent for the activity. When the app came back, the player screen opened the title again by itself,
    paused (TV-S8: "Back in front ... at 1330 s"). 51 ms later the card's intent sent the app to Home from scratch
    (`StartViewModel.open`: Home with everything under it dropped), which dropped that screen while it opened ("In the
    background: the player is released"); Home was drawn and asked for all its rows, took the card and opened a second
    player screen, which asked the server and prepared a player again, and played instead of waiting paused. So: a
    player screen that loads, Home, and a player screen that loads again. The log has this twice, once for each time the
    owner went Home and back.
  - **Fixed:** (stream R, `tv-resume-once`) the player screen says which title it holds (`PlayerReturn`, Nixie's). A
    card that plays that title, in the profile signed in, opens nothing: the screen brings the title back by itself,
    once, paused where it stood. A card for another title, or a title's page, opens as before.
  - The way back goes to the detailed log as one line of a duration and counts (design rule 10): `Way back to the
    player: first frame 1.84 s, 1 open asked of the server, 1 player prepared`. A repeat on the box shows as 2.
  - `ResumeOnceTest` runs the real player screen and view model, `StartViewModel`'s handling of the card and the
    activity's routing of it, in a NavHost on a real activity whose lifecycle each path drives (the server and Media3
    faked, counting). Each path back asks the server for one open, prepares one player (one media item set), reports
    one start at the place it stood and asks for no details, on the same screen, without Home:

    | Path | Before (main) | After |
    |---|---|---|
    | Home, then the card (the activity starts, then the intent: the box's order) | 2 opens, 2 players, 1 details, a second screen, Home | 1 open, 1 player |
    | Standby, waking to the launcher, then the card (the intent first) | 1 open, 1 player, 1 details, a second screen (playing, not paused), Home | 1 open, 1 player, the same screen, paused |
    | Another app in front, then closed | 1 open, 1 player | the same; a panel over the player (paused only) loads nothing |
    | Back, then the title again | one stop, then 1 open, 1 player, 1 details on a new screen | the same |

    With the card rule taken out, the first two fail; with the stop's once-only line (`source = null` in `finish`) taken
    out, the Back path reports the stop twice and fails; with the release moved to a pause, a panel over the player
    releases it and the third fails. `PlayerReturnTest`: which cards are kept (another title, its page, another profile,
    no profile, a screen closing or failed: not kept), and the line.
  - On the emulator (the TV test server on the harness's silent test video; the card sent as the launcher sends it,
    `OPEN_TITLE` with `play`; a build with the card rule off as "before"), counted from the logcat and the server's
    request log for each way back:

    | Path | Before | After |
    |---|---|---|
    | Home, then the card | "Back in front", released 50 ms later, Home drawn, a second player screen: PlaybackInfo 2, details 2, 1 player prepared, start 1, progress 2; the line says `2 opens asked of the server, 1 player prepared` | no Home, no new screen: PlaybackInfo 1, details 0, 1 player prepared, start 1, paused at the place; `1 open asked of the server, 1 player prepared`, first frame 1.00 s |
    | Back, then the card | Home and a new screen (the player was closed): PlaybackInfo 1, details 2, 1 player, start 1 | the same |
    | Standby and waking (the emulator wakes into Nixie) | PlaybackInfo 1, details 0, 1 player, start 1 | the same |
    | Settings opened over Nixie, then Back | PlaybackInfo 1, details 0, 1 player, start 1 | the same |

    Each player prepared is one media item set and three range reads of its stream. On the emulator and in the
    owner's log alike, the dropped screen's open was cancelled after its PlaybackInfo and before it made a player; the
    JVM test, which lets that open finish before the card arrives, has it prepare one as well.
  - Checked and left: releasing only when the whole app goes to the background (`ProcessLifecycleOwner`) instead of
    on the screen's own stop. Without the card's drop the screen stops only when the app does (the player opens nothing
    above itself), so it would remove no load, and it would hold the player 0.7 s longer on every Home.
- **TV-S13, Movies, Unwatched, the first A–Z jump and a trailer were slow against a remote server** (the owner's
  8-point check of build 10101, 2026-11-22). By the stopwatch several seconds each on the box, against about 1 s on
  the throwaway Jellyfin. The box's detailed log (its debug build) put a good part of it outside the requests: one
  request each, then parsing and drawing the row, both slow on a debug build on a slow processor.
  - **What each path waited on, one request after another** (10101's code, and the requests in the proxy's log): Movies
    1 (its first page, uncounted, nothing before it), Unwatched 1, the first A–Z jump 2 (where the letter starts, then
    the page there; 3 going Z to A before the list was counted), a trailer 1 (the title's details, before YouTube could
    start; 2 for a trailer file on the server). A page in the app's order (the index in memory) was 2 requests at once.
    So for Movies and Unwatched the server's share was already one request: the stopwatch's other seconds are not
    requests.
  - **Fixed:**
    - **The A–Z jump** asks for the letter's own page first (`NameStartsWithOrGreater`, or `NameLessThan` the next
      letter going down), one request, and lands on its first title at the start of a row; where it sits in the list
      comes after it (limit 0), then the rows above it. Those come in whole rows from the letter's, and the titles on
      screen stay where they are (`NixieKeepRowsInPlace`); scrolled all the way back up, the list's first row is short.
      The proxy's log, per jump: before, the place (0.40–0.94 s) and then the page (0.59–1.17 s); after, the page
      (0.39–1.16 s), and after the landing the place and the rows above.
    - **The trailer** asks the server nothing after the press: the movie page hands its details over (in memory; the
      video's id still goes to YouTube only on the press). The step reads "details from the page 0.00 s" (before 0.26 to
      0.32 s through the proxy).
    - A page in the app's order is one request of up to 80 ids (Glacier: 40, so two at once for a page of 70).
    - The A–Z rail offers every letter at once in a list in the server's order; a saved index of 25,000 films took the
      emulator 6 s to read, and the rail stayed away meanwhile.
    - **The whole wait from the OK, in the detailed log** (design rule 10): "First row from the key: … (the key waited
      N ms for the main thread, came up at …, the page opened at …, the screen made at …, the list asked at …, request,
      parse, page in at …, row on screen at …, its pictures at …); main thread from the key to the row: N frames over
      50 ms, longest …, … in all", the same for each A–Z jump ("landed at …, on screen at …") and the trailer ("Trailer
      from the key: …"). The main thread's long frames are watched for 12 s after an OK, in debug builds and with
      detailed logging on.
  - **The proxy** (a local delay proxy outside the repository, loopback only, logging no key or token), set so the
    emulator's request times match the box's log (the coordinator: the log, not the stopwatch): every list request
    delayed 0.2–0.4 s on top of the throwaway's own time, pictures 0.5 s, a title's details 0.2 s. Through it, 10101 on
    the emulator came out close to the box's requests: the first page's request 0.55–1.72 s, Unwatched's 0.68–1.69 s,
    the first jump 1.23–2.25 s, the details 0.26–0.32 s. The throwaway itself moved with the PC's load (its first page
    alone 0.45 to 1.3 s), so each run's own request time is given below.
  - **The numbers** (the emulator, debug builds, the same session; 10101 against this branch; from the OK, its
    `IntegMark` on the device's clock, to the first row on screen; medians, the runs in brackets; the request alone in
    the last column, before / after, since the test server's own time moved with the PC's load from run to run):

    | Path | Through the proxy: 10101 | this branch | Without it: 10101 | this branch | Its request, proxy / none |
    |---|---|---|---|---|---|
    | Movies, cold (a new process) | 1.39 s (2) | 1.81 s (4) | 1.85 s | 0.88 s | 0.67 / 1.18 s; 1.03 / 0.23 s |
    | Movies, warm | 0.94 s (2) | 1.45 s (4) | 1.40 s | 1.00 s | 0.57 / 1.03 s; 0.79 / 0.30 s |
    | Unwatched, cold | 1.92 s (2) | 1.04 s (4) | 0.84 s | 1.61 s | 1.42 / 0.68 s; 0.36 / 0.99 s |
    | Unwatched, warm | 1.64 s (2) | 1.05 s (4) | 0.79 s | 2.20 s | 1.28 / 0.70 s; 0.32 / 1.55 s |
    | First A–Z jump to M, cold: landed | 1.48 s (3: 1.23 to 2.25) | 0.86 s (4) | 1.37 s | 0.35 s | 2 requests / 1 |
    | its titles on screen | – | 1.35 s (4) | – | 1.07 s | |
    | First A–Z jump, warm: landed | 1.22 s (2) | 0.94 s (4) | 1.75 s | 0.37 s | 2 / 1 |
    | its titles on screen | – | 1.29 s (4) | – | 0.96 s | |
    | Trailer, first in the app: first frame | 5.09 s (2: 1.74, 8.43) | 2.62 s (4) | 4.48 s | 2.28 s | details 0.27 s / none |
    | Trailer, again | 2.76 s (2) | 2.18 s (4) | 3.76 s | 4.37 s | 0.26–0.32 s / none |

    Movies and Unwatched ask the same one request before and after: their differences are the server's (the last
    column); what the app adds to it, from the OK to the row less the request, stayed 0.3 to 0.8 s. The jump's landing
    lost one request, 0.3 to 1.4 s; "on screen" adds 0.4 to 0.7 s of composing a new page of cards (only this branch
    logs it). A trailer's first frame after the press is now YouTube's alone: the page made the player's shell ahead
    and handed over the details, so the screen goes straight to `startVideo`; YouTube's own share, from the press to its
    first frame, was 1.4 to 4.1 s here (8.0 s once, the first video after the emulator started), the server's 0.
  - **Where the box's other seconds go.** The emulator, with the new line, from the OK to Movies' first row on screen
    (cold, four runs): the key reached the app at once (2 ms), the page opened at 7–14 ms, the screen was made at
    84–188 ms, the request and its parsing took 0.8–2.1 s, the row was drawn 0.25–0.47 s after the page came, and its
    pictures 0.8–1.0 s after the row; the main thread spent 0.67–0.76 s of it in long frames (2.8 s in one run while the
    PC was loaded). The jump's landing took 0.5–0.7 s more to be on screen (a new page of cards composed; 0.4–0.8 s
    of long frames). So, apart from the request, the app's own share is main-thread work (composing in a debug build),
    and the pictures after the row. On the box, which runs the debug build (no R8, no baseline profile: the emulator's
    debug build took 6 to 7 times the staging build's time to its first frame, TV-S6) on a slower CPU (it parsed the
    same page of 70 titles slower than the emulator), those shares grow 2 to 3 times; and its stopwatch may stop at
    the pictures. Nothing in 10101's log covers the time before the page's opening; the new line does: the next check
    with detailed logging gives the box's own split, the key's wait for the main thread first. Worth checking on the
    box: a staging build beside the debug one.
  - **Seen, not changed:** at a cold Movies open 15 to 21 of about 50 picture requests are repeats: the grid composes
    two rows ahead and fetches their posters, and `NixiePrefetchArt` fetches the same rows' posters at the same time
    (Coil does not join requests in flight). The first row's pictures come first, so its time is not hurt, but rows 3 to
    5 ask a slow server twice. A fix: fetch ahead only beyond the rows the grid composes.
- **Integration 1, the noticed and pointed-out issues** (tv-integration-1, 2026-11-23; the owner: "Fix all noticed
  issues and pointed out issues").
  - **Fixed: each poster is asked of the server once** (TV-S13's repeats). The app's image loader joins requests for a
    picture already coming (Coil's `DeDupeConcurrentRequestStrategy`, `NixieImageRequests`): the second waits and reads
    the picture from the disk cache, whoever asks (the grid's cards, the rows fetched ahead, Home's rows). The rows
    fetched ahead keep their place, so a row that scrolls in is still ready. `PosterOnceTest`: a row of seven posters,
    each asked by its card and by the fetch ahead at once, from the app's own image loader against a slow local server:
    7 requests (before: 14).
  - **Fixed: no text says "your server"** (design rule 11; the publish check's finding). The skip source, the online
    lookup setting, the setup and address pages and Seerr's request hint say "the server" or "the server you use", in
    English, German and Spanish. `ServerWordsTest`, like the desktop's: every module's strings in the three languages,
    the string literals of the Kotlin sources (comments left out) and README, PRIVACY and the issue forms, for "your
    server" and "check that the server is running".
  - **Fixed: the log goes on the network only after Yes.** After a failed playback the card asks in one line, "Offer
    the log for download on this network?", with Yes and No (Try again keeps the first focus); after a crash the same
    question, with when it happened, comes before the first screen, its focus on No and Back saying No. Nothing is
    served until Yes; Download log in Settings › System is the user's own choice and asks nothing. `LogAskTest` (the
    real view models, Diagnostics counting its offers; the card's buttons; a stray OK on the crash question offers
    nothing): with the old offer-at-once put back, four of its six tests fail. PRIVACY.md says so.
  - **Added: a release-like build for the owner's re-check** (TV-S13: the box's slowness was largely the debug build
    on a slow processor). `assembleNixieStaging -Pnixie.stagingAsDebug=true`: R8, shrunk, the release's rules and its
    baseline profile, signed with the debug key, with the debug build's id `io.github.mbustos27.nixie.tv.debug`
    (the same debug certificate as the installed build), so it installs over Nixie debug and keeps its sign-ins; not
    debuggable, every ABI. The PC log pull works on it too: the staging build takes the adb receiver (still only for a
    sender holding DUMP; in no release or store build, `AdbLogManifestTest`), and the script reads the file through
    `AdbLogProvider` and adb's `content` command, since `run-as` needs a debuggable build (`AdbLogProviderTest`).
  - **Basics (class 13), all passed** on the emulator with that build (48e4f38b, installed over Nixie debug) against
    the throwaway Jellyfin as `tvbasics`: a Quick Connect sign-in stays after two force-stops and a start from the
    launcher, and again after Remove profile and a new Quick Connect; Continue watching's card dialog (Cancel,
    Remove, Undo) and Remove profile (Back, Cancel, "Remove, keep settings on the server") each do only what they
    say; Continue watching (12th card), Libraries, Collections (the last of several hundred, by the remote) and More
    like this (12th) reach their last item on screen; Back leaves every top-bar page, Settings and its pages, a title
    page, the player and the failure card (its log question: No offers nothing). `tools\pull-tv-log.ps1` pulled the staging build's
    log (15 KB, no address or name in it, nothing left behind). Minor, queued: Right on the last card of a short last
    row (Collections) or a lone card (Libraries) moves the focus up a row, to the bar, instead of staying. **Fixed**
    (tv-1.0-r1, `ListEndTest`): it stays, or goes to the A–Z rail when the page shows one.

## Decisions (the owner delegated them, 2026-11-21: "make all these decisions")
1. **Cinematic:** no change of its own. The owner's direction for every TV theme is "keep the design philosophy of
   desktop". The TV themes follow the desktop's design (really glassy, themed by default, radius 8, no pills), and
   Cinematic is checked against that in the emulator.
2. **The trailer controls:** match the movie player, as in TV-S3.
3. **Nixie debug stays installed** on the TV for the next checks. It signs in only with the owner's own Quick Connect.
4. **The last frame-timing read** was taken (read 3), and the PC disconnected.
5. **The release key:** made at TV 1.0's release, not now, because 1.0 waits for the fix round. The owner runs
   `docs\PHASE0-OWNER.md` steps 2 and 3:
   - keytool asks the passphrase itself, so no agent sees it, and it lives only in the owner's password manager;
   - the key is backed up on two different physical drives, with the hashes checked;
   - the fingerprint is recorded for the release checker.
6. **Where TV releases live:** a public GitHub repository, `mbustos27/nixie-tv`, which the updater already expects. It
   is a GPL-3.0 fork of Glacier, credited in the README and in About.
   - It's created when TV 1.0 is ready to release, after the fix round.
   - The public history is Glacier's plus one clean commit for Nixie for TV 1.0.0, checked for a home-folder path
     before the push (the owner, 2026-11-25; `docs/PUBLISH-CHECKLIST.md`, item 16).
   - No upstream contact.
7. **TV-S11, Home is Continue watching, then Up next, and Collections has its own place** (the owner, 2026-11-22,
   testing build 10101 on the box: "the goal of home tv loading should be continue watching then up next and then that
   on one page basically and then an easier way to access collections").
   - **Home:** two rows, Continue watching then Up next, on one 1080p screen in every theme, with no scrolling (also at
     the large text size). The hero is no row of its own: it shows the focused card's picture (in the theme's hero:
     Drive-in's outdoor screen, Editorial's plate, Cinematic's full bleed) and its facts, and asks the server for
     nothing. Favourites, "New in" per library, recent albums, favourite songs, My media and the Live TV rows are gone
     from Home; their places are the top bar's Favorites, Movies/Shows/Music (each kind's libraries) and Live TV
     entries. An empty row says so in a line; with both empty, Home says to open a library from the top bar. Settings
     › Home no longer offers the hero's and the rows' settings (they had nothing left to change).
   - **What Home asks:** Continue watching and Up next, each a page of 16 with the overview and genre (the hero's facts)
     and no count, then the top bar's libraries in one request. Before: those two, the favourites, the libraries and the
     profile's "latest" list, the hero's new titles, "New in" per library, then a count per library.
   - **Collections:** an entry in the top bar after the libraries. It opens every collection on the server (box sets),
     A to Z, in a library's grid, with its own sort and toggles: the first request asks for 24 (the first screen), the
     next pages follow in the server's order. With none on the server, a line says so. A library's own Collections chip
     stays. An order button before the sort button flips it to Z to A and back, and the page keeps it (the owner,
     2026-11-23; tv-1.0-r1, `CollectionsOrderTest`).
   - **Each library from the top bar:** the bar lists each library *type* (since desktop 1.6, so a server with many
     libraries never fills it), and up to three libraries starred have their own entry. With My media gone from Home, a
     library that was not starred could not be reached, so (the coordinator: "a library that can't be reached is a
     basics bug") the bar has a **Libraries** entry right after Home, one press from it; the per-type entries stay. It
     opens every library's card, as My media had them, in the server's order, each with its count (asked after the
     cards are drawn, four at a time). With 23 libraries the bar has nine entries (`CollectionsEntryTest`), and
     `LibrariesReachTest` opens each of 23 libraries from Home with the remote alone. The cards stay without a picture
     (the coordinator's recommendation, shipped as the owner didn't answer); that can change if the owner wants one, at
     one request per library.

   **Done:** (stream T, `tv-home-two-rows`: 340e63a8, 8029641d, 5249c4de, 4dfbe8b4). Measured on the throwaway real
   Jellyfin (about 25,000 films, about 500 box sets; the test user with 24 titles under way and no shows, so Up next
   shows its empty line), debug builds on the emulator, main (`121685b8`) and this branch in one session, the PC
   otherwise idle:

   | | main | `tv-home-two-rows` |
   |---|---|---|
   | Home's first row on screen, cold (from the launch) | 2.61, 2.64, 2.66, 2.70 s | 2.80, 2.80, 2.86, 2.87 s |
   | All of Home, cold | 3.00, 3.04, 3.07, 3.20 s | 2.91, 2.92, 2.96, 2.97 s |
   | Home's first row, warm (the app still running) | 1.03, 1.07, 1.19 s | 0.64, 0.70, 1.28 s |
   | All of Home, warm | 1.28, 1.33, 1.45 s | 0.70, 0.78, 1.35 s |
   | Collections' first row, cold (from OK) | 0.85, 0.89, 0.89 s (Movies, then its Collections chip) | 0.82, 0.82, 0.84, 0.85, 0.86 s (the top bar's entry) |
   | Collections' first row, warm | 0.88, 0.95, 1.20 s (a later session: the branch there 0.62, 0.71, 0.96 s) | 0.68, 0.73, 0.75, 0.84 s |

   - The first row comes 0.15 to 0.2 s later cold: the hero area is always drawn now, and on this server (no pictures)
     main drew no hero at all. All of Home comes sooner, and warm Home in about half the time: main still asked for the
     favourites, "New in", the hero's titles and a count. Stream H's baseline (cold first row 2.0 to 2.2 s, warm 0.5 to
     0.9 s) was another emulator session; the before column above is the same session as the after one.
   - The requests alone, from the PC, both sets as each build sends them (2 x 5 loads): the first row 46 to 79 ms either
     way; all of Home 159 to 229 ms with main's 7 requests and a count, 123 to 175 ms with this branch's 3 (Continue
     watching, Up next, then the top bar's libraries). On a slow remote server, at seconds a request, that is the
     difference between seven requests and two before the rows are all in.
   - Collections: the first request (24 of about 500) took the server 206 to 219 ms (the Movies chip's 70: 321 to
     362 ms); under 2 s cold, the target, with room.
   - Libraries, with the throwaway Jellyfin split to 23 libraries for the run (its Movies library and 22 added of 30
     films each, removed afterwards), from OK on the entry: the cards on screen cold 0.39, 0.41, 0.44 s and every count
     in 0.92, 0.94, 0.95 s; warm 0.25, 0.30, 0.30 s and 0.65, 0.68, 0.70 s. (Main had no such page: My media was a
     row of Home, under its other rows.)
   - **The top bar's Home** (the coordinator, from stream R's finding: Home reloaded every row at each Home press) now
     goes back to the Home on the back stack, which shows what it has at once, with no spinner, and asks only for
     Continue watching and Up next behind it (`NixieHomeAgainTest`, `HomeTwoRowsTest`).
   - Tests: `HomeTwoRowsTest`, `CollectionsEntryTest`, `CollectionsPageTest`, `NixieHomeAgainTest`,
     `LibrariesReachTest`, the reworked `HomeStartTest`, `HomeRequestsTest`, `SavedHomeTest`; twelve revert checks,
     each test failing with its rule taken out.
     Screenshots of Home (with an Undo card, the hero on Up next, Continue watching empty, Up next empty, both empty) and
     Collections (and its line with none) and Libraries in every theme: `NixieHomeShotsTest`; `ThemeShotsTest`'s Home fits at the
     large text size too.
