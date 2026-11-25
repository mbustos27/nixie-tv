"""REUSE.toml gives the right copyright to the fork's files: Glacier's and Nixie's on files that began as copies of
Glacier's, Nixie's alone only on files Nixie wrote. A stand-in for `reuse lint` where the reuse tool is not installed;
it resolves REUSE.toml as REUSE 3.x does (the last matching table with precedence "override" decides; `*` stays inside
one folder, `**` crosses them). Standard library only:

    python -B -m unittest discover -s testing/licences -p "test_*.py"
"""

import os
import re
import tomllib
import unittest

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
NIXIE = "2026 Nixie contributors"
GLACIER = "Glacier contributors"


def pattern(glob):
    out = ""
    i = 0
    while i < len(glob):
        if glob.startswith("**", i):
            out += ".*"
            i += 2
        elif glob[i] == "*":
            out += "[^/]*"
            i += 1
        else:
            out += re.escape(glob[i])
            i += 1
    return re.compile(out)


def annotations():
    with open(os.path.join(REPO, "REUSE.toml"), "rb") as f:
        tables = tomllib.load(f)["annotations"]
    for table in tables:
        paths = table["path"] if isinstance(table["path"], list) else [table["path"]]
        texts = table["SPDX-FileCopyrightText"]
        yield [pattern(p) for p in paths], table.get("precedence", "closest"), texts if isinstance(texts, list) else [texts]


def copyright_of(path):
    """The copyright lines REUSE.toml gives `path` (repository-relative, with /)."""
    found = []
    for patterns, precedence, texts in annotations():
        if any(p.fullmatch(path) for p in patterns):
            found = texts if precedence == "override" else found + texts
    return found


class ReuseTomlTests(unittest.TestCase):
    def test_files_copied_from_glacier_keep_glacier_copyright(self):
        copied = ["app/src/nixie/res/values/strings.xml", "app/src/nixie/res/values-de/strings.xml",
                  "app/src/nixie/res/values-es/strings.xml"]
        for root, _dirs, files in os.walk(os.path.join(REPO, "theme")):
            copied += [os.path.relpath(os.path.join(root, f), REPO).replace(os.sep, "/") for f in files
                       if f == "StandIns.kt"]
        self.assertGreaterEqual(len(copied), 5)
        for path in copied:
            with self.subTest(path):
                self.assertEqual(sorted(copyright_of(path)), sorted([GLACIER, NIXIE]))

    def test_files_nixie_wrote_carry_nixie_alone(self):
        for path in ["core/designsystem/src/main/java/io/github/mbustos27/nixie/tv/designsystem/NixieTheme.kt",
                     "theme/drivein/src/main/java/io/github/mbustos27/nixie/tv/theme/drivein/DriveInTheme.kt",
                     "app/src/nixie/res/drawable/banner.xml", "DIVERGENCE.md", "testing/fakeserver/tvserver.py"]:
            with self.subTest(path):
                self.assertEqual(copyright_of(path), [NIXIE])

    def test_glacier_files_stay_glacier(self):
        self.assertEqual(copyright_of("app/src/main/res/values/strings.xml"), [GLACIER])

    def test_every_file_that_says_it_was_copied_from_glacier_keeps_glacier(self):
        said = re.compile(r"copied from (glacier|the app's layout)", re.IGNORECASE)
        for top in ("theme", "app/src/main/java/io/github/mbustos27", "app/src/test/java/io/github/mbustos27",
                    "core/designsystem/src/main/java/io/github/mbustos27"):
            for root, _dirs, files in os.walk(os.path.join(REPO, top)):
                if os.sep + "build" in root:
                    continue
                for f in files:
                    if not f.endswith((".kt", ".xml")):
                        continue
                    full = os.path.join(root, f)
                    with open(full, encoding="utf-8", errors="replace") as handle:
                        if not said.search(handle.read()):
                            continue
                    path = os.path.relpath(full, REPO).replace(os.sep, "/")
                    with self.subTest(path):
                        self.assertIn(GLACIER, copyright_of(path))


if __name__ == "__main__":
    unittest.main()
