# Before the first push of mbustos27/nixie-tv

What is left between `main` and a public `mbustos27/nixie-tv` with Nixie for TV 1.0 on it, in order. **O** is the
owner, **C** the coordinator. Agents never create the repository, push, sign, or contact anyone.

Done on `tv-repo-prep` (2026-11-22): Glacier's store listings (`fastlane/`), website (`website/`), their scripts and
badges, `pr-title.yml` and `dependabot.yml` removed; the German and Spanish changelogs reduced to notes; README,
PRIVACY (draft), SECURITY, CONTRIBUTING, the code of conduct's contact, the issue forms and the PR template written for
Nixie; REUSE.toml updated. Glacier's store, release, website and scorecard workflows were removed earlier (`ee512ee0`).

## 1. On `main`, before anything is public

1. **C:** merge `tv-repo-prep` and the TV fix round's streams into `main`.
2. **C:** a `DIVERGENCE.md` row for this branch: `fastlane/`, `website/`, the store, website and promo scripts,
   `docs/badges/`, `pr-title.yml`, `dependabot.yml` and Glacier's two issue forms are deleted (on a merge of Glacier's
   updates, a change to one of them is a modify/delete conflict: keep it deleted); `CHANGELOG.de.md`,
   `CHANGELOG.es.md`, `CONTRIBUTING.md`, `SECURITY.md`, `CODE_OF_CONDUCT.md`, the PR template and `config.yml` are
   Nixie's (keep Nixie's version). **Done** (tv-1.0-prep, 2026-11-25): DIVERGENCE.md's section "The public
   repository's files".
3. **C:** `docs/RELEASING.md` still describes Glacier's release: its workflow, store versions and listings, and
   signing secrets, all gone. Rewrite it for Nixie's GitHub-only release, and decide whether "Installing a build on the
   owner's box" (the box's local address and port) goes public. **Done** (tv-1.0-prep, 2026-11-25): the release as
   section 6 (unsigned build, the owner's apksigner, `nixie-release` check, sha256 and record, the tag alone,
   `gh release create` with the three files, the digest check, the short link, full releases only); Glacier's
   workflow, stores, listings and signing secrets gone. "Installing a build on the owner's box" stays out: the public
   doc keeps a generic certificate check before `adb install -r` and no address, port or `_box` file's steps (they
   stay in the local history, as at `aba44751`; `nixie-tv-wt\_box.md` keeps the address rules).
4. **C:** `CHANGELOG.md` is still Glacier's ("All notable changes to Glacier"). Give it Nixie's header, with Glacier's
   versions below as the base, and the 1.0 section that becomes the release notes. **Done** (tv-1.0-prep,
   2026-11-25): `## [1.0.0]` for a stranger (Install with the short link first, what Nixie for TV is, New, the basics
   gate, Known issues), all as list items so the update dialog shows them; then `## [Glacier's releases]` and Glacier's
   sections. **C fills in at the release:** `<release date>`, `<is.gd link>` and `<Downloader code>`.
5. **App stream:** design rule 11. Six English strings still say "your server": two in `nixie_play_strings.xml`
   (`nixie_skip_source_server`, `nixie_settings_lookup_online_sub`) and four in Glacier's `strings.xml`, with their
   German and Spanish. **Done** (tv-integration-1): the five the scan finds ("the server" or "the server you use"; the
   sixth counted, `signin_intro`'s "your Jellyfin account", is the user's own account), and `ServerWordsTest` keeps
   it so.
6. **App stream:** the Report a problem code in Diagnostics, opening
   `https://github.com/mbustos27/nixie-tv/issues/new?template=bug.yml`. With it, the nixie flavor's
   `diag_share_step_attach` and `player_error_log_body` can name the issue page instead of "whoever helps you with
   Nixie", and the README's "still to come" line goes. **Done** (tv-1.0-r1): Settings › System › Diagnostics ›
   Report a problem shows that address with `&version=` and `&jellyfin=` filled in (version numbers only,
   `ReportProblemTest`); `diag_share_step_attach` names Report a problem (`player_error_log_body` still says whoever
   helps); README and PRIVACY say so. The address leads nowhere until the repository is created (28 checks it).
7. **O decides, C applies:** whether 1.0 turns the automatic update check on (`UPDATE_CHECK_AT_START` in
   `app/build.gradle.kts`). README and PRIVACY then say which. **Done** (the owner, 2026-11-25: on; tv-1.0-prep): the
   release build type checks at every start, debug and staging only when asked; README and PRIVACY say what the
   check sends (GitHub's releases API only, nothing about the user) and that nothing installs without asking.
8. **C:** settle PRIVACY.md's **[not verified]** marks (the home screens, the update check, titles in the log), then
   remove the marks and the draft line. **Done** (tv-1.0-prep, 2026-11-25), from the code: the channels are written
   hidden on every box with Android's TV provider (shown only where the home screen shows channels and the user adds
   them); what a home screen does with the rows is outside Nixie; the update check as item 7. Titles in the log:
   Nixie's own lines use ids, but with Detailed logging the navigation lines (`GlacierNavHost`, "Open <route>") carry
   library, genre, folder and person names, the title a person was opened from, a sign-in page's user name, and
   Media3's event log the file's tags; an error's message can quote a server's answer. PRIVACY says so.
9. **C:** `nixie.version=1.0.0` in `gradle.properties`. **Done** (tv-1.0-prep, 2026-11-25): versionCode 1000099 by
   the same rule as 10104 (0.1.1-beta.4, given with `-Pnixie.version` on the command line), so it sorts above every
   test build.
10. **C:** CI: `ci.yml` runs `testDebugUnitTest`, which leaves out the app's own tests; add
    `:app:testNixieDebugUnitTest`. `reuse lint` runs in CI only (not run locally for this branch). **Done**
    (tv-1.0-prep, 2026-11-25): the build job runs it beside `testDebugUnitTest`; every action is still pinned by
    commit, and no workflow uses a repository secret (only the automatic `GITHUB_TOKEN`, in the fuzzing workflows).

## 2. The owner's re-check

11. **O:** the fix round on the box, with the release candidate: Home from a cold start, a library's first load and
    scroll, search, trailers, 4K playback, standby, and Quit while Home loads.
12. **O:** read README.md, PRIVACY.md, SECURITY.md, CONTRIBUTING.md and the two issue forms as a stranger would.
13. **O:** the docs that describe the owner's own sessions go public as they are: `docs/PHASE0-OWNER.md`,
    `docs/TV-SESSION.md`, `docs/TV-SESSION-RESULTS.md` (the server's size, the box's timings). Keep, trim or remove.
    **Done** (the owner, 2026-11-25: trim; tv-1.0-prep): the steps and what was learned about the app stay; the
    server's size and version, the box's model, timings and decoder list, example and real addresses, the key's
    folder, the owner's backup and agent setup are gone or said generally ("a big library of tens of thousands of
    films"). Emulator and test-server numbers stay. The same then for the code's comments, DIVERGENCE, DESIGN-RULES,
    GLACIER-BASE and FIRE-TV-PLAN, and the box's device facts, now `testing/devices/google-tv-4k-box.json` with no
    maker, model or codename.

## 3. The release key

14. **O:** `docs/PHASE0-OWNER.md` steps 2, 3 and 6, or the owner's helper, run by the owner only:
    `make-release-key.ps1` in `nixie-tv-wt\_tools` (outside the repository; it makes the key, prints the fingerprint
    and makes the two backups). keytool asks the passphrase itself, so no agent sees it; it lives only in the password
    manager. Two backups on different physical drives, hashes checked. The certificate's fingerprint goes into
    `release/signing-cert.sha256` for `nixie-release`.
15. **O and C:** the release APK built unsigned, signed by the owner (apksigner asks the passphrase), then
    `nixie-release` checks it and writes `Nixie-TV-1.0.0.apk.sha256`.

## 4. The history, just before the push

16. **Decided (the owner, 2026-11-25: "Yes, one clean commit"):** the public history is Glacier's history up to
    v0.10.1 (`b276936`) as it is, plus **one** commit, "Nixie for TV 1.0.0", whose tree is the release's `main`. The
    full local history stays on the local branch `local-history` and is never pushed; no commit is rewritten one by
    one. **C**, after the last merge into `main`, from the repository's folder:

    ```powershell
    git branch local-history main                    # the full history, local only, never pushed
    $tree = git rev-parse 'main^{tree}'
    # The author and committer: the desktop repository's author (17), for this one command.
    $name, $mail = (git -C ..\fluentfin-mpv log -1 --format='%an|%ae').Split('|')
    $env:GIT_AUTHOR_NAME = $name; $env:GIT_AUTHOR_EMAIL = $mail
    $env:GIT_COMMITTER_NAME = $name; $env:GIT_COMMITTER_EMAIL = $mail
    $commit = git commit-tree $tree -p b276936 -m 'Nixie for TV 1.0.0'
    Remove-Item Env:GIT_AUTHOR_NAME, Env:GIT_AUTHOR_EMAIL, Env:GIT_COMMITTER_NAME, Env:GIT_COMMITTER_EMAIL
    git branch public-main $commit
    git diff --quiet main public-main                # exit 0: the same tree
    ```

    Then the scans, on that one commit's diff and message (what goes public beyond Glacier's own history). Both must
    list nothing:

    ```powershell
    git show public-main --format=%B | Select-String -Pattern 'C:\\Users\\|/c/Users/|/home/'
    git show public-main --format=%B | Select-String -SimpleMatch (Get-Content ..\nixie-tv-wt\_box.txt).Split(':')[0]
    ```

    The second reads the box's address from the local file and never writes it here. If either finds something, fix
    it on `main` with a new commit and make `public-main` again (`git branch -f`).
17. **Decided (the owner, 2026-11-22: "Keep it"):** Nixie's commits keep their author address, the same as on the
    public desktop repository. The one public commit (16) has the desktop repository's author. No rewrite for authors.
18. **C:** push `public-main` as `main`, and only it: `git push origin public-main:main`. Never tags, `--all` or
    `--mirror`: Glacier's 33 tags (`v0.1.0` to `v0.10.1`), `local-history` and the work branches stay local. `upstream`
    stays fetch only. Later work builds on `public-main`, so the public history stays Glacier's plus Nixie's own
    commits after 1.0.0. **Until the local `main` is itself that history, never `git push origin main`** (as
    RELEASING.md's step 7 has it): the local `main` carries `local-history`'s commits.

## 5. The repository's settings (O creates it; settings before the first push)

19. Create `mbustos27/nixie-tv`: public, empty (no README, licence or .gitignore from GitHub), default branch `main`.
    Description: "Nixie for TV: a Jellyfin app for Android TV and Google TV, based on Glacier." Topics: `jellyfin`,
    `jellyfin-client`, `android-tv`, `google-tv`. No website.
20. **Features:** Issues on; Wiki, Projects and Discussions off; Sponsorships off.
21. **Security:** Private vulnerability reporting **on** (SECURITY.md and the issue chooser link to it); dependency
    graph and Dependabot alerts on; Dependabot security updates as the owner prefers (version updates stay off: no
    `dependabot.yml`); secret scanning and push protection on; CodeQL **default setup off** (`security.yml` runs
    CodeQL itself, and GitHub refuses the two together).
22. **Actions:** the workflows use only actions by GitHub (`actions/*`, `github/codeql-action`) and three others, all
    pinned by commit: `gradle/actions`, `google/clusterfuzzlite`, `zizmorcore/zizmor-action`. Allow those; workflow
    permissions read-only by default (each job asks for what it needs); outside contributors' pull requests wait for
    approval. None of them needs a secret, publishes to a store or deploys a site. Actions may also stay off until
    after the release, which doesn't need them.
23. **Branches:** a ruleset on `main` with no force pushes and no deletion.
24. **Labels:** `bug` and `enhancement` (GitHub's defaults) are the ones the forms use.
25. **Social preview:** optional; Nixie's own image, never Glacier's.

## 6. Push, then release

26. **C:** `git remote add origin https://github.com/mbustos27/nixie-tv.git`, then `git push origin public-main:main`
    (16, 18).
27. **C:** the first CI and Security runs green (build, tests, lint, detekt, REUSE, CodeQL) before the release.
28. **C:** the page: README renders, its links resolve, the issue chooser shows Report a problem, Suggest an idea and
    the two links, and Report a vulnerability opens. The app's Report a problem code, scanned once, opens the form with
    both versions filled in.
29. **C, after the owner's OK:** tag `v1.0.0` on `public-main` (never on the local `main`: a pushed tag takes its
    commit's whole history along) and push that tag alone; publish the release "Nixie for TV
    1.0.0" with `Nixie-TV-1.0.0.apk`, its `.sha256`, and **`nixie-tv.apk`** (the same file under a name that never
    changes, for the short link: section 7), notes from CHANGELOG.md's 1.0 section. Check that GitHub shows each APK's
    SHA-256 digest and that both match the `.sha256` (the updater refuses a release without one).
30. **O:** install the release on the box from GitHub (it sits beside Nixie debug) and open Settings › System: the
    version, Check for updates ("up to date"), and the source link to `github.com/mbustos27/nixie-tv`.

## 7. The short link for sideloading (the owner, 2026-11-24)

The owner: "when tv 1.0 releases we need to shorten the link for things like firetv sideloader link apps / or release
a short link basically". People type it with a remote into Downloader-style apps on Fire TV and Google TV, so it is
short and never changes.

31. **The stable APK address.** Every TV release attaches `nixie-tv.apk` beside `Nixie-TV-<version>.apk` (step 29), so
    `https://github.com/mbustos27/nixie-tv/releases/latest/download/nixie-tv.apk` always serves the newest build.
    - Checked 2026-11-24: the in-app updater takes only the exact name `Nixie-TV-<version>.apk`
      (`ReleaseSelector.apkOf`), so the extra asset never confuses it. `tools/release-check` checks the local file, which
      is the same file.
    - Each later release attaches it too, or `releases/latest` stops serving one. `releases/latest` skips
      pre-releases, so a beta never moves the link; only a full release does.
    - A Fire TV build variant gets its own fixed name, `nixie-firetv.apk`, from its first release.
32. **The short links (decided 2026-11-24, the owner: "Yes, both").** Both point at the stable address in 31, and
    both are made only once the release is public and that address serves the APK. Neither can be edited later, which
    is fine because the address never changes.
    - **C: `is.gd/nixietv`.** Created at https://is.gd/ with the custom alias `nixietv` (the owner's yes covers this
      one creation). No account is needed, and it's a 301 with no preview page.
    - **O: an AFTVnews Downloader code**, the number typed in Downloader on Fire TV and Google TV. The owner fills in
      the form at https://go.aftvnews.com/ with the stable address, because it has a captcha (never automated). The code
      is public. C records it here.
    - Researched and not chosen: TinyURL (an account for an alias, edits paid), a GitHub Pages meta refresh (a plain
      download tool saves the HTML), git.io (gone since 2022), a custom domain (a yearly fee).
    - Recorded: is.gd `nixietv`: created 2026-11-25, 2:26 PM, a 301 to the stable address (1.0.0 checked: over http
      and https it ends at a 200 with the APK, SHA-256 `af509938…13b5`, the `.sha256`'s); Downloader code: `6673217`
      (aftv.news/6673217), made by the owner 2026-11-25 with the stable address as its destination (checked: its page
      sends a browser to that address).
33. **C: the docs say it:**
    - README's Install section: "On the TV: type <short link> into Downloader or a similar app" (done at 1.0);
    - the 1.0 release notes, at the top (done at 1.0);
    - the Settings › System screen, beside the source link: waits for 1.0.1.
34. **C, then O: check that the short link downloads the APK:**
    - **C, from a PC:** following the link's redirects ends at a 200 with an APK, whose SHA-256 matches the `.sha256`.
    - **O, on the box:** typed into Downloader, it installs the release.
    Do this after every release, since `releases/latest` moves.

Fire TV comes later, from the same repository as a build variant ([FIRE-TV-PLAN.md](FIRE-TV-PLAN.md)).
