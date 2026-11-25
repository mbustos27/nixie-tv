# What we look for: design rules for speed, lifecycle and the way around

Nixie for TV and Nixie for desktop both follow these rules. Every change is checked against them, and so is every bug
hunt; the hunt checks them as class 12. Each rule comes from a real bug, given in brackets. The owner's checks on the
box that found most of them are in [TV-SESSION-RESULTS.md](TV-SESSION-RESULTS.md).

1. **The first screen waits only for what it shows.**
   - Saved data comes first and the rest fills in as it arrives.
   - Background work starts after the first screen is drawn: the library index, Watch Next rows, launcher channels.
   - One slow or failed row never holds the others.
   - Start-up never waits on the network when what it needs is saved.

   [Home took about a minute on a server with tens of thousands of films: tens of seconds before Home opened after
   standby, then more before its rows were in.]

2. **Time new paths against a real Jellyfin at scale.**
   - Use a throwaway instance with about 25,000 items, never a user's own server.
   - A fake server answers at once whatever is asked, so its numbers aren't evidence of speed.

   [A library's first row took 0.66 s against the fake and about 10 s on a real server.]

3. **No whole-library work on an interaction path.**
   - A count, an index, a collapse or a full read over the library happens in the background, or comes from what's
     saved, never between a key press and its answer.
   - An A–Z jump asks for its offset, not for the whole index.

   [The first A–Z jump read the whole index, 26 pages.]

4. **Nothing destructive runs on "not loaded yet".**
   - Missing data is not a decision: a cleanup, delete or reset waits until the thing it judges is known.

   [Continue watching on the launcher was emptied at every start, because "no profile yet" was read as "another
   profile".]

5. **Sleep, standby and offline release what they hold.**
   - Players, sockets and timers let go when the app is in the background, retries back off to minutes, and the
     screen comes back where it was.

   [A paused player was held all night in standby, while the live-updates socket retried every minute.]

6. **Device facts come from the real source.**
   - The display's physical mode (`Display.Mode`), not the UI's size; the decoders' real profiles.
   - Test 4K, HDR and Dolby Vision content.

   [Playback logged a 1920x1080 limit on a 3840x2160 TV.]

7. **Views open at their final size, with no jump.**
   - Time to first frame is measured, for playback and for trailers.

   [A trailer opened small, jumped to full screen, and took tens of seconds to show its first frame on a slow box.]

8. **Every page has a way up to its parent,** whatever opened it.

   [Desktop 1.6.1: an episode opened from Continue watching had no link to its series or season.]

9. **A switch turned off stops in-flight work too.**
   - Turning something off cancels what it started, and nothing it began lands afterwards.

   [Desktop 1.7.0: lists kept being requested after their source was switched off.]

10. **Detailed logs record the durations of the paths users wait on.**
    - Start-up, Home and each of its rows, a library's first page and A–Z jump, search, playback and a trailer's first
      frame.
    - Durations only: no titles or names.
    - The next check on the box then gives numbers without adb.

    [The log showed when Home and Movies opened, but not how long their parts took.]

11. **The user is not the server's admin.**
    - Advice, docs and app text never send the user to the server's dashboard, logs, library scans, plugins,
      metadata or image fixes, transcoding settings or restarts.
    - An error says what Nixie couldn't do and what the user can do on the TV. It keeps showing the saved copy when
      there is one, and points to the server's owner only when the fault really is on the server's side.
    - "The server" or "the server you use", never "your server".
    - Problems are diagnosed from Nixie's own logs and a test Jellyfin, never by asking the user to look at the server.

    [Desktop 1.7.1: the Collections page said "Check that the server is running" while every other page loaded. The
    fault was Nixie's own: a title listed twice in its saved index.]

12. **Every basic path is a release gate, at real shape.**
    - Each sign-in method (Quick Connect first) stays signed in across a restart; a server can be added, switched and
      removed; every menu item does only what it says; every row reaches its last item with the remote; Back works
      from every page; the top bar lights the right section; text fits.
    - They run on every release build on the emulator with the remote, with real-shaped data (seasons of 13 and 24
      episodes, long names, two servers, two users), in every theme. A fixture smaller than real life hides the bug.

    [Desktop 1.7.x, found by a tester: a Quick Connect sign-in forgotten at restart; a server's "…" opening its
    sign-in; the player's controls stuck almost invisible after a size change; a 13-episode season's row that couldn't
    scroll; the top bar lighting "Home" on a season page; cast names running together.]

How Nixie changes Glacier: [GLACIER-BASE.md](GLACIER-BASE.md). The themes: [THEMES.md](THEMES.md).
