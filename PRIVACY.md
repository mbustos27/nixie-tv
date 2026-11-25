# Nixie for TV's privacy policy

*For Nixie for TV 1.0 and later (November 2026). Each statement was checked against the app's code.*

Nixie for TV is a free, open-source (GPL-3.0) Jellyfin app for Android TV and Google TV, based on
[Glacier](https://github.com/Glacier-Jellyfin/glacier-androidtv). It has no account of its own, no analytics, no
telemetry, no ads and no crash-reporting service. **Nixie for TV collects nothing about you.** What it keeps, it keeps
on the TV box, in the app's own storage, and on the Jellyfin server you sign in to.

Every service the box reaches sees its IP address. That can't be avoided, and each service keeps it in its logs by its
own rules.

## The Jellyfin server you use

Nixie signs in to the server you choose and plays from it, as any Jellyfin app does. The server belongs to whoever runs
it, who may not be you, and it gets what a Jellyfin app sends:

- **Signing in:** your user name and password, or a Quick Connect code you approve elsewhere. Nixie keeps the access
  token the server gives back, never the password.
- **The device:** the app's name and version ("Nixie for TV 1.0.0"), the box's name (the name set in Android's
  settings, or its maker and model) and a device id Nixie makes.
- **What you do:** what you browse and search for; what you play and how far (start, progress, pause and stop, as with
  any Jellyfin app); watched marks, favourites, and removing a title from Continue watching (which sets its position
  back to the start); requests, where the server offers them (Seerr, below).
- **What the box can play:** its decoders, display and audio outputs, so the server can choose between playing a file
  as it is and converting it.
- **A copy of your Nixie settings** for each profile, one per device, so they come back after a reinstall or on another
  TV.
- **Live updates:** an open connection while Nixie is on screen, so rows change when you watch elsewhere. It closes
  when Nixie goes to the background or the box to standby.
- **In the background:** about every 6 hours while the box is online, and after you play something, Nixie asks the
  server for the titles the TV's home screen shows (next section).
- **Finding a server:** the list of servers sends Jellyfin's discovery broadcast on the local network; only Jellyfin
  servers there answer it.
- **Send log to the server**, only when you choose it ([Logs](#logs-and-report-a-problem)).

Nixie sends nothing about the server anywhere else.

## The TV's home screen

Nixie puts the titles you're watching on the TV's home screen, as Android TV apps do:

- **Continue watching** (Android's "Watch Next" row): for each title in Nixie's Continue watching and Up next, its name
  (for an episode, the show's name with the episode's name and number), its description, year and length, where you
  stopped and when you last watched it, the address of its picture on the server, and a link that opens it in Nixie.
- **Three channels**: Continue watching, Recently added movies & shows, and New movies & shows, with the same details.
  Nixie writes them on every box that has Android's TV provider (Fire TV hasn't), created hidden: a home screen shows
  one only if it shows apps' channels at all and you add that channel in its settings.
- Only the profile that last opened in Nixie has rows there. Switching profiles removes the other profile's rows, and
  signing out empties them.
- The picture addresses carry no access token, but they do name the server's address.
- **Settings › Home › Show Continue watching on the home screen** turns the row off and removes it. The channels have
  no switch in Nixie; the home screen's own settings hide them.

The home screen is made by Google, by the box's maker or by whoever made the launcher you installed, not by Nixie. It
reads these rows from Android, which keeps them on the box. Nixie can't see or limit what a home screen does with them
after that: it follows the home screen's own rules and its maker's privacy policy. Some home screens, Google TV's own
among them, don't show the row at all.

## YouTube, for trailers

A trailer the server lists as a YouTube link plays in YouTube's embedded player (`youtube-nocookie.com`). A trailer
file on the server plays from the server, and YouTube is never asked.

- **On a film's or show's page with a YouTube trailer** (and no trailer file on the server), Nixie loads YouTube's
  player ahead of time, empty, so the trailer starts faster. That page is the same for every title and names no video:
  YouTube sees that its player was loaded, not which title you're looking at. It is let go when you leave the page.
- **Only when you press Trailer** does YouTube get the trailer's video id. The trailer screen then shows the title's
  other YouTube trailers with their pictures from YouTube's image host (`i.ytimg.com`), which gets their ids too.
- What YouTube does from there, cookies included once a video plays, follows YouTube's own rules.

## GitHub, for updates

Nixie for TV is installed from GitHub and updates itself from there; no app store is involved.

- **At every start**, Nixie asks GitHub's releases API (`api.github.com`) for the list of Nixie for TV's releases, and
  again whenever you choose **Settings › System › Check for updates**. **Check for updates automatically**, in the
  same place, is on from the start; turning it off leaves only the check you ask for.
- The request asks for that list and nothing else. It carries nothing about you beyond what any web request carries
  (the IP address, above): the User-Agent names Nixie only, without a version, and there is no account, server
  address, library, title or device detail in it.
- A new release is marked on the settings gear, shows its notes once on the home screen, and waits. Nothing downloads
  or installs by itself: only when you choose Update now does the APK download from GitHub's file host. Before
  installing, Nixie checks its SHA-256 against the digest GitHub publishes, its package name and its version;
  Android's installer then checks it is signed with the same key as the installed app, and installs it.

## Online skip lookups (off until you turn them on)

**Settings › Playback › Look up skips online** asks TheIntroDB (`api.theintrodb.org`), and for anime AniSkip
(`api.aniskip.com`), for intro and credits times when the server and the file's chapters have none. ARM
(`arm.haglund.dev`) gets only the show's id, to find it on AniSkip.

- **What is sent:** the film's or show's public id (TVDB, TMDb or IMDb; for anime the MyAnimeList, AniList or AniDB
  id), the season and episode numbers, the length, and a User-Agent naming Nixie for TV and its version.
- **Never sent:** a title, the server's address, a user name or a token.
- The answers are kept in memory until Nixie closes.

## Seerr requests (only where the server offers them)

When the server runs the Jellyfin Enhanced plugin with Seerr, search also asks Seerr, through the server. Seerr's
titles and people then show pictures from TMDb's image host (`image.tmdb.org`), which sees which pictures are asked
for. A request you make goes through the server to its Seerr.

## Logs and Report a problem

Nixie keeps a log of what it does on the box: about 800 KB at most, in the app's own storage. **Detailed logging**
(Settings › System › Diagnostics) adds playback events and navigation, and turns itself off after 24 hours.

Before a log leaves the box, the addresses and names of the servers saved on it, the names of the users signed in on
it, passwords and access tokens are taken out. Titles are not taken out; instead, Nixie's own lines name titles,
people and libraries by their ids, which mean something only on that server, and none of them names a file on the
server. A name can still reach the log in two ways:

- **With Detailed logging on**, each page you open is logged with what it was opened with: a library's, genre's or
  folder's name, a person's name with their role and the title you came from, the name a person's page searches for,
  and the user name chosen on a sign-in page. The player's event log adds the playing file's track names and its own
  tags, which may hold its title.
- **An error** is logged with the message it came with, and such a message now and then quotes part of a server's
  answer, which can hold a title.

The top of the log describes the box: its maker and model, the Android version, the display, the decoders and audio
outputs, and the server's Jellyfin version.

The log leaves the box only in these ways:

- **Download log on the network** (Settings › System › Diagnostics): while that page is open, the box offers the log
  at an address on your local network, shown as a QR code. You open it on a phone or computer on the same network, save
  the file, and attach it to an issue yourself. Nothing goes to the internet. The address has a random part that can't
  be guessed, but the download is plain HTTP on the local network, so anyone who sees the code or the network's traffic
  can fetch it while it is offered.
- **The same offer, after a question:** at the next start after Nixie closed unexpectedly, and when a title fails to
  play, Nixie asks in one line whether to offer the log on the network, with Yes and No. Nothing is offered until you
  choose Yes, and the offer stops when its dialog closes.
- **Send log to the server** (Settings › System › Diagnostics): uploads the log to the Jellyfin server you're signed in
  to. **It goes to the server admin's dashboard, not to Nixie's developers.** Use it when the server's admin is the one
  helping you. To report a problem in Nixie, use Report a problem and Download log on the network (below and above),
  or open an [issue](https://github.com/mbustos27/nixie-tv/issues).
- **Debug and test builds only:** a command over adb, from a PC connected to the box with Wireless debugging, writes the same
  anonymised log into the app's own storage for the PC to copy (`tools/pull-tv-log.ps1`). Only adb can send that
  command and read the file, and the releases don't contain it.

**Report a problem** (Settings › System › Diagnostics) shows a QR code with the address of Nixie's public Report a
problem form on GitHub. The address holds only two version numbers, Nixie's and the server's Jellyfin version, and
nothing else about the box, the server or you. The box sends nothing and opens nothing: your phone opens the form, and
what you write and send there is public.

**Delete log**, in the same place, deletes the log from the box. **Check playback** plays test files built into the
app and sends nothing.

## What is kept on the box

- **Sign-ins:** server names and addresses, user names, and access tokens, encrypted with a key from Android's
  Keystore. Signing out deletes the token.
- **Settings, the saved Home page and library indexes** (for a fast start), learned skips, pictures, and the log.
- Android's own backup of app data is off for Nixie, so none of it goes to a Google account.

To remove all of it: Android's **Settings › Apps › Nixie › Clear storage**, or uninstall Nixie.

What the server keeps (watch history, favourites, Nixie's settings copies, a log sent to it) stays on that server, and
is up to whoever runs it.

## Questions

Nixie for TV is a personal project. Ask, or report a privacy problem, in the repository's issues:
<https://github.com/mbustos27/nixie-tv/issues>. Report a security problem privately, as
[SECURITY.md](SECURITY.md) explains.
