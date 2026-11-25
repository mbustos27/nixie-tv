# The owner's TV session for Nixie for TV 1.0

About 20 minutes, on a Google TV box. The owner drives the TV with the remote; the coordinator runs the PC commands
when the owner says so. Agents never see the Quick Connect code, the owner's password or token. Nothing here signs,
publishes or pushes anything.

## Before (the owner, 2 minutes)
1. On the TV: **Settings > System > Developer options > Wireless debugging**, turn it on (it turns off when the TV
   restarts). Note the address and port shown there (not the pairing one; the PC is paired once, as in
   `docs\PHASE0-OWNER.md` step 4).
2. Tell the coordinator the address and port, or run on the PC:

   ```powershell
   $adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
   & $adb connect <address>:<port>
   & $adb devices        # the TV shows as "device"
   ```

## Install (the coordinator, 1 minute)
3. Install the debug build of `main` (it installs as **Nixie debug**, beside nothing else):

   ```powershell
   & $adb -s <address>:<port> install -r app\build\outputs\apk\nixie\debug\app-nixie-debug.apk
   ```

## Sign in (the owner, 2 minutes)
4. Open **Nixie debug** from Your apps. Choose the server, then **Quick Connect**: the code shows on the nixie tubes.
   Approve it yourself in Jellyfin on your phone or a browser (Settings > Quick Connect). The code is yours alone.

## The checks (the owner, about 12 minutes; say "good" or what looked wrong)
5. **Drive-in** (the default):
   - Home: the hero, Continue Watching, Up next, Nixie's rows; move along a row, open a title, press **Back**: you land
     on the card you left. Remove a Continue Watching card, then **Undo**: the card and its focus come back.
   - A library: the A–Z rail, a filter.
   - A movie page and a series page (season picker, episodes).
   - **Play** a movie: seek, the audio and subtitle menus, Back. If something has **DTS or TrueHD** audio, play it: it
     should sound right (the app decodes it when the box can't pass it through).
   - An episode with an intro: **Skip intro**; let one end: the **Up next** card.
   - A **trailer** on a movie page (sound on is fine here; it's your TV), then Back.
   - **Search**: a few letters, then a title.
6. **Settings > Appearance:** switch to **Cinematic**, look at Home, a movie page and the player; then **Editorial**
   (its item pages print the picture in one dark rose tone: keep it, or ask for it softer).
7. Anything that felt slow, stuttered, or looked off: say where.

## While you browse (the coordinator, read-only)
8. With the owner's OK at the time, the coordinator reads the app's frame timing and memory from the TV, nothing else:

   ```powershell
   & $adb -s <address>:<port> shell dumpsys gfxinfo io.github.mbustos27.nixie.tv.debug
   & $adb -s <address>:<port> shell dumpsys meminfo io.github.mbustos27.nixie.tv.debug
   ```

## After (1 minute)
9. Keep **Nixie debug** to use, or remove it: `& $adb -s <address>:<port> uninstall io.github.mbustos27.nixie.tv.debug`.
10. `& $adb disconnect <address>:<port>`. Wireless debugging can stay on or be turned off.

## Then, the owner's two decisions for the release (both made)
- **The release key** (`docs\PHASE0-OWNER.md` steps 2, 3 and 6, about 10 minutes): create it, back it up on two drives,
  sign the release.
- **Where TV releases live:** the GitHub repository `mbustos27/nixie-tv`, which the updater expects: a public GPL-3.0
  fork of Glacier, credited in the README and About.
