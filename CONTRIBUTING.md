# Contributing to Nixie for TV

Thanks for your interest. Nixie for TV is a personal project with one maintainer, who reviews every change and
decides what goes in. This page explains how to help without anyone's time going to waste. Everyone taking part is
expected to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Problems and ideas

Use the [issue forms](https://github.com/mbustos27/nixie-tv/issues/new/choose). For a problem, attach the log from
the TV: **Settings › System › Diagnostics › Download log on the network** saves it on a phone on the same network,
with server addresses, names, user names, passwords and tokens taken out. Never paste a server address, user name or
token: this is a public page.

Security problems go privately, never into an issue: see [SECURITY.md](SECURITY.md).

Nixie for TV is based on [Glacier](https://github.com/Glacier-Jellyfin/glacier-androidtv). Please report problems
you find in Nixie here, never to Glacier's authors: they can't fix Nixie, even in code the two share.

## Pull requests

Small fixes can go straight to a pull request. For anything larger, open an issue first and wait until the approach
is agreed, so the work isn't done for nothing. The maintainer reviews, may ask for changes, and merges or closes the
pull request.

- **Build and test** with
  `.\gradlew.bat testDebugUnitTest :app:testNixieDebugUnitTest detekt lintDebug lintNixieDebug :app:assembleNixieDebug`
  (`./gradlew` outside Windows). The root `testDebugUnitTest` leaves out the app's own tests, hence the second task.
- **Tests come with the change**, as unit tests under the module's `src/test`. Tests never touch a real server: the
  fake Jellyfin server in [testing/](testing/README.md) answers them. Timing a path users wait on needs a throwaway
  Jellyfin with tens of thousands of titles, never someone's own server ([docs/DESIGN-RULES.md](docs/DESIGN-RULES.md),
  rule 2).
- **Use a D-pad**, a remote or the emulator's, not a mouse, and check a visible change in each theme.
- **Check the design rules:** [docs/DESIGN-RULES.md](docs/DESIGN-RULES.md) lists what every change is checked against
  (the first screen waits only for what it shows, standby releases what it holds, every page has a way up, the user is
  not the server's admin, and more).
- **Keep Glacier's files mergeable.** Nixie's own code lives in its own packages (`io.github.mbustos27.nixie.tv`) and
  modules (`theme/`). A change to one of Glacier's files is a small hook marked `// Nixie:`, with a row in
  [DIVERGENCE.md](DIVERGENCE.md), so Glacier's updates keep merging
  ([docs/GLACIER-BASE.md](docs/GLACIER-BASE.md#merging-glaciers-updates)).
- **Style:** follow the surrounding code; `.editorconfig` sets the formatting, and `detekt` checks it
  (`-PdetektAutoCorrect` fixes formatting findings).
- **New files** get an entry in [REUSE.toml](REUSE.toml): Nixie's own files under "Nixie contributors", and
  third-party files with their licence. CI runs `reuse lint`.
- Keep a pull request to one change.

## Translations

Nixie for TV is in English, German and Spanish, and follows the TV's language. Its texts are in three places:

- Nixie's own: `app/src/main/res/values/nixie_*strings.xml`;
- Glacier's, renamed for Nixie: `app/src/nixie/res/values/strings.xml`;
- Glacier's own: `app/src/main/res/values/strings.xml`. These are best translated in Glacier, and arrive here with
  its updates.

To add or improve a language, copy the files to `values-<lang>` beside them (the code Android uses, such as `fr` or
`pt-rBR`), translate the texts, keep placeholders such as `%1$s` exactly as they are, and leave out anything marked
`translatable="false"`. Say in the pull request whether you are a native speaker. Texts that are missing appear in
English.

## Language

Everything in this repository is written in English: code, identifiers, comments, documentation and commit messages.
Translated app texts are the only exception.

## Licence

By contributing, you agree that your contributions are licensed under the GNU General Public License v3.0 or later,
as the rest of Nixie for TV and Glacier.
