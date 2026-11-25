# The desktop fake server, pinned

These files are a copy of `tools/ui-harness/fakeserver` in the fluentfin-mpv repository (desktop Nixie, the same
owner, GPL-3.0-or-later like this repository) at commit **fa95aa0** ("Nixie 1.5.1: the first fix release",
2026-11-21): `server.py`, `library.py`, `search.py`, `media.py`, `imagegen.py`, `admin.py`, `release.py` and
`credentials.json` (the fake server's own test account; read by the tools, never printed).

They are not edited here. `tvserver.py` runs them with the TV's profiles laid over them, so a change in the desktop
repository can no longer break the TV's tests or its sign-in scripts (it did on 2026-11-21: bug hunt M5).

To take a newer desktop fake: copy the same files from a fluentfin-mpv commit
(`git -C <fluentfin-mpv> show <commit>:tools/ui-harness/fakeserver/<file>`), change the commit above, and run
`python -B -m unittest discover -s testing/fakeserver -p "test_*.py"` and the app's `SearchServerTest`; the
signature test there says when an override in `tvserver.py` no longer fits the desktop's methods.
