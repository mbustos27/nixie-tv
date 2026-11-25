"""Nixie 1.5 (search): the fake server's search, answering as Jellyfin 12.4 does (docs/research/search.md).

- /Items?searchTerm= (the built-in SqlSearchProvider): the cleaned name (no accents, lower case, punctuation as spaces)
  holds the cleaned term, or the original title holds the raw term (LIKE, case-insensitive), or the sort name holds the
  term shaped as a sort name; one substring, in the order typed. Scored exact 100, start 80, a word start 75, else 50;
  answered by score, then sort name (any sortBy is ignored, as 12.4 does); the providers are asked for three times the
  limit (100 with none), so TotalRecordCount is at most that.
- A term that cleans to nothing ("%", "_") falls through to the database filter, whose LIKE wildcards match everything.
- /Search/Hints: the same matching under one limit, people in unless includePeople=false; an empty term is a 400.
- /Persons?searchTerm=: people whose cleaned name holds the cleaned term.
- mode "loose" (FAKESERVER_SEARCH_MODE=loose, or POST /__harness/search {"mode": "loose"}): any word of the term, as a
  search plugin (an external provider) may answer: a real server with one gave "it's always sunny" hundreds of films.
Stdlib only.
"""

import re
import unicodedata

SORT_REMOVE_WORDS = ("the", "a", "an")
SORT_REMOVE_CHARS = (",", "&", "-", "{", "}", "'")
SORT_REPLACE_CHARS = (".", "+", "%")


def no_accents(text):
    return "".join(c for c in unicodedata.normalize("NFKD", text or "") if not unicodedata.combining(c))


def clean(text):
    """12.x's GetCleanValue: no accents, lower case, every mark a space, single spaces."""
    lowered = no_accents(text).lower()
    return re.sub(r"\s+", " ", "".join(c if c.isalnum() or c.isspace() else " " for c in lowered)).strip()


def sort_shape(text):
    """BaseItem.GetSortName with the default rules: the articles, removed and replaced characters, numbers to 10 digits."""
    s = (text or "").strip().lower()
    for word in SORT_REMOVE_WORDS:
        if s.startswith(word + " "):
            s = s[len(word) + 1:]
        s = s.replace(" " + word + " ", " ")
        if s.endswith(" " + word):
            s = s[:-(len(word) + 1)]
    for ch in SORT_REMOVE_CHARS:
        s = s.replace(ch, "")
    for ch in SORT_REPLACE_CHARS:
        s = s.replace(ch, " ")
    s = re.sub(r"\d+", lambda m: m.group(0).rjust(10, "0"), s)
    return no_accents(s)


def like(pattern, text):
    """SQLite LIKE: % any run, _ any one character, case-insensitive for ASCII."""
    rx = "".join(".*" if c == "%" else "." if c == "_" else re.escape(c) for c in pattern)
    return re.fullmatch(rx, text or "", re.IGNORECASE | re.DOTALL) is not None


class Search:
    def __init__(self, mode="stock"):
        self.mode = mode if mode in ("stock", "loose") else "stock"

    def score(self, item, term, sort_name):
        """The item's score for the term (higher is better), or None when it does not match."""
        raw = no_accents((term or "").strip())
        c = clean(raw)
        name = clean(item.get("Name"))
        if not c:
            # nothing left after cleaning: the database filter's LIKE, wildcards and all ("%" matches everything)
            return 50.0 if raw and like("%" + raw + "%", item.get("Name") or "") else None
        if self.mode == "loose":
            words = [w for w in c.split() if len(w) >= 2]
            hits = sum(1 for w in words if w in name.split())
            if not hits and c not in name:
                return None
            return 100.0 if name == c else 50.0 + 10.0 * hits
        original = item.get("original")
        shape = sort_shape(raw)
        found = c in name or (original and like("%" + raw + "%", original)) or (shape and like("%" + shape + "%", sort_name))
        if not found:
            return None
        if name == c:
            return 100.0
        if name.startswith(c):
            return 80.0
        if (c + " ") in name:
            return 75.0
        return 50.0

    def rank(self, pool, term, sort_name, limit):
        """The matching items, best first, cut to the providers' candidates (three times the limit, 100 with none)."""
        scored = []
        for item in pool:
            s = self.score(item, term, sort_name(item))
            if s is not None:
                scored.append((s, item))
        cap = limit * 3 if limit is not None else 100
        scored.sort(key=lambda x: (-x[0], x[1]["Id"]))
        kept = scored[:cap]
        kept.sort(key=lambda x: (-x[0], sort_name(x[1])))
        return [item for _, item in kept]


def hint(lib, item):
    """A SearchHint as /Search/Hints answers it (no user data, no card fields)."""
    kinds = {"movie": "Movie", "series": "Series", "season": "Season", "episode": "Episode", "person": "Person",
             "boxset": "BoxSet", "video": "Video", "musicvideo": "MusicVideo", "musicalbum": "MusicAlbum", "audio": "Audio"}
    out = {"ItemId": item["Id"], "Id": item["Id"], "Name": item["Name"], "Type": kinds.get(item["kind"], "Folder"),
           "IsFolder": item["kind"] in ("series", "season", "boxset"), "MatchedTerm": None,
           "ProductionYear": item.get("year"), "PrimaryImageTag": "p" + item["Id"][:8],
           "MediaType": "Video" if item["kind"] in ("movie", "episode") else "Unknown"}
    if item["kind"] == "episode":
        series = lib.items.get(item.get("series")) or {}
        out.update(Series=series.get("Name"), IndexNumber=item.get("index"), ParentIndexNumber=item.get("parent_index"))
    return out
