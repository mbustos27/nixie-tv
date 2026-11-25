"""The TV test server on synthetic media (no mpv needed), for the app's JVM tests:

    python -B serve_synthetic.py <empty dir> [profile]

Writes <dir>/media (test_tvserver.make_media and its media.json), serves with tvserver.py's profile (jellyfin-12 by
default) and writes <dir>/ready.json ({url, port, pid}) once listening. Sign in with Quick Connect and
/__harness/quickconnect/approve: the test never needs the credentials file. Standard library only.
"""

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.dont_write_bytecode = True
sys.path.insert(0, HERE)

import tvserver  # noqa: E402
from test_tvserver import make_media  # noqa: E402


def main(argv):
    root = os.path.abspath(argv[0])
    profile = argv[1] if len(argv) > 1 else "jellyfin-12"
    media = os.path.join(root, "media")
    meta = make_media(media)
    with open(os.path.join(media, "media.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f)
    return tvserver.main(["--profile", profile, "--out", os.path.join(root, "out"), "--media", media,
                          "--ready-file", os.path.join(root, "ready.json")])


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
