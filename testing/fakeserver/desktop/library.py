"""The fake server's library: generated once from a seed (the same names, ids and artwork every run), the user's
watch state, and the queries FluentFin and the backend make (Jellyfin's DTO shapes).

Views: Movies (movies), Shows (tvshows), Documentaries (movies), Anime (tvshows). Every playable item streams the
same test video, so its runtime is that video's. A few items are fixed "showcase" ones the harness navigates to;
their names sort first in their library, so they are on the first page.
"""

import datetime
import hashlib
import random
import threading
import uuid

import search as search_mod   # 1.5 search

TICKS = 10_000_000

ADJECTIVES = ["Silent", "Crimson", "Hidden", "Last", "Broken", "Golden", "Distant", "Frozen", "Burning", "Quiet",
              "Hollow", "Restless", "Electric", "Painted", "Forgotten", "Velvet", "Iron", "Midnight", "Lonely", "Wild",
              "Paper", "Glass", "Northern", "Sleeping", "Wandering", "Bright", "Secret", "Endless", "Falling", "Copper"]
NOUNS = ["Harbor", "Garden", "Signal", "Winter", "River", "Kingdom", "Machine", "Lighthouse", "Orchard", "Station",
         "Mirror", "Frontier", "Archive", "Comet", "Valley", "Promise", "Carnival", "Engine", "Canyon", "Island",
         "Letter", "Empire", "Shadow", "Horizon", "Theory", "Circus", "Tide", "Forest", "Bridge", "Crown"]
PLACES = ["Avalon", "Port Ellery", "Northgate", "Saltmarsh", "Cedar Falls", "Ravenmoor", "Kestrel Bay", "Old Mill",
          "Lowtown", "Brightwater", "Ashford", "Marrow Hill"]
FIRST = ["Ada", "Ben", "Clara", "Dev", "Elena", "Felix", "Grace", "Hugo", "Iris", "Jonah", "Kira", "Leo", "Maya",
         "Nico", "Olive", "Pavel", "Quinn", "Rosa", "Sami", "Tess", "Umar", "Vera", "Wes", "Yara", "Zane"]
LAST = ["Abbott", "Brandt", "Castillo", "Dorsey", "Ellison", "Fujita", "Garrow", "Hale", "Ibarra", "Jensen",
        "Kowalski", "Lindqvist", "Moreau", "Nakamura", "Okafor", "Petrov", "Quill", "Rasmussen", "Sato", "Thorne",
        "Ueda", "Varga", "Whitlock", "Xu", "Yilmaz", "Zielinski"]
GENRES = ["Action", "Adventure", "Animation", "Comedy", "Crime", "Drama", "Family", "Fantasy", "History", "Horror",
          "Mystery", "Romance", "Science Fiction", "Thriller", "War", "Western"]
DOC_GENRES = ["Documentary", "History", "Nature", "Science", "Music", "Sport"]
TAGS = ["based on novel", "time travel", "heist", "coming of age", "road trip", "found family", "space",
        "small town", "revenge", "dystopia", "friendship", "survival"]
MOVIE_RATINGS = ["G", "PG", "PG-13", "R", "NR"]
TV_RATINGS = ["TV-Y7", "TV-PG", "TV-14", "TV-MA"]
STUDIOS = ["Northlight Pictures", "Blue Kite Films", "Harborview Studios", "Paper Moon", "Ironwood Media",
           "Studio Kestrel", "Lantern House", "Seventh Wave"]

VIEWS = [  # name, collection type, how many top-level items
    ("Movies", "movies", 320),
    ("Shows", "tvshows", 72),
    ("Documentaries", "movies", 48),
    ("Anime", "tvshows", 28),
]

# Nixie 1.3.1: the showcase movie's (fake) YouTube trailer id
TRAILER_ID = "hArNeSs0001"

SHOWCASE = {"movie": "A Quiet Harbor", "series": "A Signal in Winter", "anime": "A Comet Over Avalon",
            "documentary": "A Field Guide to Rivers"}


def _id(*parts):
    return hashlib.md5(("ffh:" + ":".join(str(p) for p in parts)).encode("utf-8")).hexdigest()


def _tag(item_id, kind):
    # Nixie 1.4.0: an image's tag (its kind is capitalised: Primary, Backdrop, Chapter3) changes with the flat art
    # (imagegen.ART), so a client asks for the new picture; "etag" and "src" never do
    if kind[:1].isupper():
        import imagegen
        kind += imagegen.art_key()
    return hashlib.md5(("tag:%s:%s" % (item_id, kind)).encode()).hexdigest()


def norm_id(value):
    """An id as the server keeps it: 32 lower-case hex digits, from N or D format."""
    return (value or "").replace("-", "").lower()


def iso(dt):
    return dt.strftime("%Y-%m-%dT%H:%M:%S.0000000Z")


class Library:
    def __init__(self, server_id, user_id, runtime_ticks, seed=20261115, now=None):
        self.server_id = server_id
        self.user_id = user_id
        self.runtime = runtime_ticks
        #: how long the end credits (the Outro segment) run, to the end; POST /__harness/segments changes it
        self.outro_seconds = 40
        #: Nixie 1.4.0: the showcase movie's trailer link instead of the harness's YouTube id (POST /__harness/trailer {"url"})
        self.trailer_url = None
        self.now = now or datetime.datetime(2026, 11, 14, 20, 0, 0)
        self.lock = threading.RLock()
        self.items = {}          # id -> item dict
        self.children = {}       # id -> [child ids] in order
        self.views = []          # view ids
        self.people = {}         # id -> person item
        self.loose = []          # 1.5.1 fixes (BH-150-m7): searchable items in no library view (FAKESERVER_SEARCH=sunny)
        self.user = {}           # item id -> user data dict (played, position, favorite, play_count, last_played)
        self.rng = random.Random(seed)
        self._names = set()
        # 1.5 search: how /Items?searchTerm=, /Search/Hints and /Persons match (search.py): Jellyfin 12.4's built-in
        # provider, or "loose" (any word, as a search plugin may answer; FAKESERVER_SEARCH_MODE)
        import os
        self.search = search_mod.Search(os.environ.get("FAKESERVER_SEARCH_MODE", "stock"))
        self._build()

    # ---------------------------------------------------------------- generation
    def _name(self, kind):
        rng = self.rng
        for _ in range(1000):
            pattern = rng.randrange(6)
            a, n, n2, p = rng.choice(ADJECTIVES), rng.choice(NOUNS), rng.choice(NOUNS), rng.choice(PLACES)
            name = ["The %s %s" % (a, n), "%s of %s" % (n, p), "%s %ss" % (a, n), "Return to %s" % p,
                    "The %s and the %s" % (n, n2), "%s %s" % (a, p)][pattern]
            if kind == "documentary":
                name = rng.choice(["Inside the %s", "The Story of the %s", "Life on the %s", "Chasing the %s"]) % n + \
                    (" (Part %d)" % rng.randint(2, 3) if rng.random() < 0.1 else "")
            if name not in self._names and not name.startswith("A "):
                self._names.add(name)
                return name
        raise RuntimeError("ran out of names")

    def _overview(self, name, kind):
        rng = self.rng
        who = "%s %s" % (rng.choice(FIRST), rng.choice(LAST))
        what = rng.choice(["a missing map", "an old promise", "a stolen engine", "a letter never sent",
                           "the last ferry", "a broken treaty", "a voice on the radio", "a forgotten song"])
        where = rng.choice(PLACES)
        if kind == "documentary":
            return "A close look at %s, filmed over four seasons around %s." % (name.lower(), where)
        return ("When %s finds %s in %s, a quiet life turns into a race against time. %s follows the people who "
                "cannot let it go, and what it costs them." % (who, what, where, name))

    def _person(self):
        rng = self.rng
        name = "%s %s" % (rng.choice(FIRST), rng.choice(LAST))
        pid = _id("person", name)
        if pid not in self.people:
            self.people[pid] = {"Id": pid, "Name": name, "Type": "Person", "kind": "person", "created": self.now,
                                "parent": None, "view": None}
            self.items[pid] = self.people[pid]
        return self.people[pid]

    def _common(self, item_id, kind, name, parent, view, created, year, genres, rating):
        rng = self.rng
        cast = []
        for i in range(rng.randint(4, 9)):
            person = self._person()
            cast.append({"Name": person["Name"], "Id": person["Id"], "Role": "%s %s" % (rng.choice(FIRST), rng.choice(LAST)),
                         "Type": "Actor" if i < 6 else rng.choice(["Director", "Writer", "Producer"]),
                         "PrimaryImageTag": _tag(person["Id"], "Primary")})
        return {"Id": item_id, "kind": kind, "Name": name, "parent": parent, "view": view, "created": created,
                "year": year, "premiere": datetime.datetime(year, rng.randint(1, 12), rng.randint(1, 28)),
                "genres": genres, "tags": rng.sample(TAGS, rng.randint(0, 3)), "rating": rating,
                "community": round(rng.uniform(4.8, 9.1), 1), "critic": rng.randint(28, 99),
                "people": cast, "studios": rng.sample(STUDIOS, rng.randint(1, 2)),
                "overview": self._overview(name, kind), "tagline": rng.choice(
                    ["Some doors stay open.", "Nothing stays buried.", "Every road ends somewhere.",
                     "The tide always returns.", "Listen closely.", ""])}

    def rename_views(self, renames):
        """The harness renames libraries (POST /__harness/libraries): the ids of the views renamed."""
        changed = []
        for vid in self.views:
            new = renames.get(self.items[vid]["Name"])
            if new:
                self.items[vid]["Name"] = str(new)
                changed.append(vid)
        return changed

    def add_views(self, names):
        """The harness adds empty libraries of movies (POST /__harness/libraries): their ids."""
        added = []
        for name in names:
            vid = _id("view", name)
            if vid in self.items:
                continue
            self.items[vid] = {"Id": vid, "kind": "view", "Name": name, "collection": "movies", "parent": None,
                               "view": vid, "created": self.now}
            self.views.append(vid)
            self.children[vid] = []
            added.append(vid)
        return added

    def _build(self):
        rng = self.rng
        start = self.now - datetime.timedelta(days=3 * 365)
        span = (self.now - start).total_seconds()
        for view_name, ctype, count in VIEWS:
            vid = _id("view", view_name)
            self.items[vid] = {"Id": vid, "kind": "view", "Name": view_name, "collection": ctype, "parent": None,
                               "view": vid, "created": start}
            self.views.append(vid)
            self.children[vid] = []
            doc = view_name == "Documentaries"
            for n in range(count):
                showcase = None
                if n == 0:
                    showcase = {"Movies": "movie", "Shows": "series", "Anime": "anime",
                                "Documentaries": "documentary"}[view_name]
                name = SHOWCASE[showcase] if showcase else self._name("documentary" if doc else ctype)
                created = start + datetime.timedelta(seconds=rng.uniform(0, span))
                if showcase:
                    created = self.now - datetime.timedelta(days=2 + len(self.views))
                year = rng.randint(1978, 2026)
                if ctype == "movies":
                    mid = _id("movie", view_name, name)
                    genres = rng.sample(DOC_GENRES if doc else GENRES, rng.randint(1, 3))
                    if doc and "Documentary" not in genres:
                        genres.insert(0, "Documentary")
                    item = self._common(mid, "movie", name, vid, vid, created, year, genres, rng.choice(MOVIE_RATINGS))
                    item["showcase"] = showcase
                    self.items[mid] = item
                    self.children[vid].append(mid)
                else:
                    self._series(vid, view_name, name, created, year, showcase)
            self.children[vid].sort(key=lambda i: self.sort_name(self.items[i]))
        self._seed_user_data()
        # UXCAP (the library and collections review's captures): env switches for the library/collections UX capture; off by default, and generated
        # after everything else with their own random sources, so the default fixture is unchanged
        import os
        if os.environ.get("FAKESERVER_COLLECTIONS"):
            self._collections()
        big = self.int_of(os.environ.get("FAKESERVER_BIG_LIBRARY"), 0)
        if big > 0:
            self._big_library(big)
        # 1.4.0 topbar: FAKESERVER_EXTRA_VIEWS=<n> adds n small movie libraries (the top bar's fold with 9 views)
        extra = self.int_of(os.environ.get("FAKESERVER_EXTRA_VIEWS"), 0)
        for name in self.EXTRA_VIEW_NAMES[:max(0, extra)]:
            self._small_library(name, 8)
        # season speed: FAKESERVER_BIG_SERIES=<seasons>x<episodes> (or <seasons>, 20 episodes each) adds a second showcase
        # series to Shows, BIG_SERIES_NAME, for switching between many long seasons (tools\ui-harness\offscreen\
        # then-season-switch.ps1); "A Signal in Winter" stays the first card and the showcase
        big_series = self.big_series_size(os.environ.get("FAKESERVER_BIG_SERIES"))
        if big_series:
            self._big_series(*big_series)
        # 1.5 search: FAKESERVER_SEARCH=sunny adds the search titles (a show and its near-misses, a person)
        if os.environ.get("FAKESERVER_SEARCH") == "sunny":
            self._search_fixture()

    # 1.5 search: "it's always sunny" as the owner typed it, among films that share its words
    SEARCH_FILMS = ["It's Always Fair Weather", "Always", "Sunny", "It", "It's a Wonderful Life", "Little Miss Sunshine",
                    "Sunny Side Up", "Always Be My Maybe", "It Follows", "Philadelphia", "The Philadelphia Story",
                    "Amélie", "M*A*S*H", "Se7en", "WALL·E"]
    SEARCH_WORDS = ["Harbor", "Engine", "Lantern", "Orchard", "Station", "Signal", "Window", "Weather", "Summer",
                    "Mirror", "Garden", "Highway"]
    SEARCH_PERSON = "Sunny Holm"

    def _search_fixture(self):
        rng = random.Random(150)
        movies, shows = _id("view", "Movies"), _id("view", "Shows")
        person = {"Id": _id("person", self.SEARCH_PERSON), "Name": self.SEARCH_PERSON, "Type": "Person", "kind": "person",
                  "created": self.now, "parent": None, "view": None}
        self.people[person["Id"]] = person
        self.items[person["Id"]] = person
        names = list(self.SEARCH_FILMS)
        for word in self.SEARCH_WORDS:
            names += ["Sunny %s" % word, "Always %s" % word, "It Came from the %s" % word]
        for n, name in enumerate(names):
            mid = _id("movie", "Movies", "search", name)
            created = self.now - datetime.timedelta(days=rng.randint(30, 900))
            item = self._common(mid, "movie", name, movies, movies, created, rng.randint(1934, 2025),
                                rng.sample(GENRES, rng.randint(1, 2)), rng.choice(MOVIE_RATINGS))
            item["showcase"] = None
            if name == "Amélie":
                item["original"] = "Le Fabuleux Destin d'Amélie Poulain"
            if n % 7 == 0:
                item["people"].insert(0, {"Name": person["Name"], "Id": person["Id"], "Role": "Herself", "Type": "Actor",
                                          "PrimaryImageTag": _tag(person["Id"], "Primary")})
            self.items[mid] = item
            self.children[movies].append(mid)
        gang = ["The Gang Gets Started", "The Gang Finds a Dead Guy", "Charlie Gets Crippled", "The Gang Goes Jihad",
                "Hundred Dollar Baby", "The Gang Gives Back"]
        self._series(shows, "Shows", "It's Always Sunny in Philadelphia", self.now - datetime.timedelta(days=40), 2005,
                     None, seasons=2, episodes=6, episode_name=lambda e: gang[(e - 1) % len(gang)])
        self._series(shows, "Shows", "Sunny Days", self.now - datetime.timedelta(days=60), 2017, None, seasons=1,
                     episodes=3, episode_name=lambda e: ["Always Sunny", "It's a Sunny Day", "Rain"][e - 1])
        for vid in (movies, shows):
            self.children[vid].sort(key=lambda i: self.sort_name(self.items[i]))
        # 1.5.1 fixes (BH-150-m7): a home video, a music video, an album and its song, which search found none of; in no
        # view, so no library page, tab or Home row changes (only search, the hints and /Items by id or type see them)
        album = _id("musicalbum", "Sunny Side Songs")
        for kind, name in (("video", "Sunny Beach Day"), ("musicvideo", "Sunny Afternoon"), ("musicalbum", "Sunny Side Songs"),
                           ("audio", "Sunny Morning")):
            iid = album if kind == "musicalbum" else _id(kind, name)
            # (no self.rng: the search runs' other titles stay as they were)
            item = {"Id": iid, "kind": kind, "Name": name, "parent": None, "view": None, "showcase": None,
                    "created": self.now - datetime.timedelta(days=20), "year": 2019, "premiere": datetime.datetime(2019, 6, 1),
                    "genres": ["Music"], "tags": [], "rating": "", "community": 7.0, "critic": 70, "people": [], "studios": [],
                    "overview": "%s, for search." % name, "tagline": ""}
            if kind == "audio":
                item.update(album=album, album_name="Sunny Side Songs", parent=album)
            self.items[iid] = item
            self.loose.append(iid)
        self.children[album] = [_id("audio", "Sunny Morning")]

    # UXCAP (the library and collections review's captures): a "Collections" view (boxsets) of BoxSets made of the Movies library's titles
    COLLECTION_NAMES = ["The Harbor Collection", "Kestrel Bay Trilogy", "Midnight Stories", "The Northgate Saga",
                        "Copper and Glass", "Winter Films", "Ravenmoor Quartet", "The Lighthouse Pictures",
                        "Saltmarsh Double Feature", "Electric Machines", "Frontier Classics", "Endless Summer Set"]

    def _collections(self):
        rng = random.Random(4242)
        vid = _id("view", "Collections")
        self.items[vid] = {"Id": vid, "kind": "view", "Name": "Collections", "collection": "boxsets", "parent": None,
                           "view": vid, "created": self.now - datetime.timedelta(days=900)}
        self.views.append(vid)
        self.children[vid] = []
        movies_view = _id("view", "Movies")
        pool = list(self.children[movies_view])
        showcase = next(i for i in pool if self.items[i].get("showcase") == "movie")
        for n, name in enumerate(self.COLLECTION_NAMES):
            bid = _id("boxset", name)
            members = rng.sample(pool, rng.choice([2, 3, 3, 4, 5, 6, 8, 12]))
            if n == 0 and showcase not in members:
                members[0] = showcase
            members.sort(key=lambda i: (self.items[i]["premiere"], self.sort_name(self.items[i])))
            created = self.now - datetime.timedelta(days=rng.randint(3, 700))
            years = [self.items[m]["year"] for m in members]
            genres = sorted({g for m in members for g in self.items[m]["genres"]})[:3]
            self.items[bid] = {"Id": bid, "kind": "boxset", "Name": name, "parent": vid, "view": vid, "created": created,
                               "year": min(years), "premiere": min(self.items[m]["premiere"] for m in members),
                               "genres": genres, "tags": [], "rating": self.items[members[0]]["rating"], "people": [],
                               "studios": [], "overview": "%d films, %d-%d." % (len(members), min(years), max(years)),
                               "tagline": "", "community": None, "critic": None}
            self.children[vid].append(bid)
            self.children[bid] = members
        self.children[vid].sort(key=lambda i: self.sort_name(self.items[i]))
        # Nixie 1.4.0 (shared stream): a "Playlists" view with one playlist, its items in the user's own order (neither by name
        # nor by date: the page must keep it); a boxset-like item whose Type is Playlist
        pvid = _id("view", "Playlists")
        self.items[pvid] = {"Id": pvid, "kind": "view", "Name": "Playlists", "collection": "playlists", "parent": None,
                            "view": pvid, "created": self.now - datetime.timedelta(days=800)}
        self.views.append(pvid)
        pid = _id("playlist", "Rainy Sunday")
        members = rng.sample(pool, 5)
        years = [self.items[m]["year"] for m in members]
        self.items[pid] = {"Id": pid, "kind": "boxset", "playlist": True, "Name": "Rainy Sunday", "parent": pvid, "view": pvid,
                           "created": self.now - datetime.timedelta(days=12), "year": min(years),
                           "premiere": min(self.items[m]["premiere"] for m in members), "genres": [], "tags": [],
                           "rating": self.items[members[0]]["rating"], "people": [], "studios": [],
                           "overview": "Five films for a wet afternoon, in the order they were added.", "tagline": "",
                           "community": None, "critic": None}
        self.children[pvid] = [pid]
        self.children[pid] = members
        # Nixie 1.4.0 integration (item 15, real playlists): a playlist as servers make them, of episodes and films mixed, in
        # the user's order; one of a single item; and an empty one (the page's empty state)
        shows = _id("view", "Shows")
        episodes = [i for s in self.children.get(shows, []) for season in self.children.get(s, [])
                    for i in self.children.get(season, [])][:40]
        mixed = rng.sample(episodes, min(3, len(episodes))) + rng.sample(pool, 2)
        rng.shuffle(mixed)
        for name, kids, days, overview in (("Season Openers", mixed, 30, "First episodes and a couple of films, for a long evening."),
                                           ("One for the Road", rng.sample(pool, 1), 5, ""),
                                           ("Nothing Yet", [], 1, "")):
            plid = _id("playlist", name)
            years = [self.items[m]["year"] for m in kids if "year" in self.items[m]] or [self.now.year]
            self.items[plid] = {"Id": plid, "kind": "boxset", "playlist": True, "Name": name, "parent": pvid, "view": pvid,
                                "created": self.now - datetime.timedelta(days=days), "year": min(years),
                                "premiere": min((self.items[m]["premiere"] for m in kids if "premiere" in self.items[m]),
                                                default=self.now - datetime.timedelta(days=days)),
                                "genres": [], "tags": [], "rating": None, "people": [], "studios": [], "overview": overview,
                                "tagline": "", "community": None, "critic": None}
            self.children[pvid].append(plid)
            self.children[plid] = list(kids)
        self.children[pvid].sort(key=lambda i: self.sort_name(self.items[i]))

    def collections_of(self, item_id):
        """Nixie 1.4.0: the box sets an item is in (GET /Items/{id}/Collections, Jellyfin 10.11)."""
        return [self.items[b] for b, kids in self.children.items()
                if b in self.items and self.items[b]["kind"] == "boxset" and not self.items[b].get("playlist") and item_id in kids]

    # 1.4.0 topbar: the extra views' names (FAKESERVER_EXTRA_VIEWS), long and short, as real servers name them
    EXTRA_VIEW_NAMES = ["Concerts", "Kids and Family", "Stand-up Specials", "Classics", "Home Videos", "4K Remasters"]

    def _small_library(self, view_name, n):
        saved = self.rng
        self.rng = random.Random(sum(map(ord, view_name)))
        try:
            rng = self.rng
            vid = _id("view", view_name)
            start = self.now - datetime.timedelta(days=3 * 365)
            self.items[vid] = {"Id": vid, "kind": "view", "Name": view_name, "collection": "movies", "parent": None,
                               "view": vid, "created": start}
            self.views.append(vid)
            self.children[vid] = []
            for _ in range(n):
                name = self._name("movies")
                mid = _id("movie", view_name, name)
                item = self._common(mid, "movie", name, vid, vid, start + datetime.timedelta(days=rng.randint(0, 900)),
                                    rng.randint(1950, 2026), rng.sample(GENRES, rng.randint(1, 3)), rng.choice(MOVIE_RATINGS))
                item["showcase"] = None
                self.items[mid] = item
                self.children[vid].append(mid)
            self.children[vid].sort(key=lambda i: self.sort_name(self.items[i]))
        finally:
            self.rng = saved

    # UXCAP (the library and collections review's captures): a big movies library ("Archive") of n titles, for density and scrolling
    def _big_library(self, n):
        saved = self.rng
        self.rng = random.Random(9090)
        try:
            rng = self.rng
            vid = _id("view", "Archive")
            start = self.now - datetime.timedelta(days=6 * 365)
            span = (self.now - start).total_seconds()
            self.items[vid] = {"Id": vid, "kind": "view", "Name": "Archive", "collection": "movies", "parent": None,
                               "view": vid, "created": start}
            self.views.append(vid)
            self.children[vid] = []
            # 1.4.0 integration (the big-library stream: tens of thousands of films): past the generator's few thousand
            # names,
            # sequels ("The Silent Harbor 2"), so FAKESERVER_BIG_LIBRARY=25000 works; a smaller library is as it was
            sequels, bases = None, None
            for _ in range(n):
                if sequels is None:
                    try:
                        name = self._name("movies")
                    except RuntimeError:
                        sequels, bases = {}, sorted(self._names)
                if sequels is not None:
                    base = rng.choice(bases)
                    sequels[base] = sequels.get(base, 1) + 1
                    name = "%s %d" % (base, sequels[base])
                    while name in self._names:
                        sequels[base] += 1
                        name = "%s %d" % (base, sequels[base])
                    self._names.add(name)
                created = start + datetime.timedelta(seconds=rng.uniform(0, span))
                year = rng.randint(1930, 2026)
                mid = _id("movie", "Archive", name)
                item = self._common(mid, "movie", name, vid, vid, created, year, rng.sample(GENRES, rng.randint(1, 3)),
                                    rng.choice(MOVIE_RATINGS))
                item["showcase"] = None
                self.items[mid] = item
                self.children[vid].append(mid)
                r = rng.random()
                if r < 0.3:
                    self._ud(mid).update(played=True, play_count=1,
                                         last_played=self.now - datetime.timedelta(days=rng.randint(2, 900)))
            self.children[vid].sort(key=lambda i: self.sort_name(self.items[i]))
        finally:
            self.rng = saved

    # season speed: the big series (FAKESERVER_BIG_SERIES); its name sorts right after "A Signal in Winter"
    BIG_SERIES_NAME = "A Tide of Seasons"

    @classmethod
    def big_series_size(cls, value):
        """FAKESERVER_BIG_SERIES's (seasons, episodes): "10x20", or "10" for 20 episodes each; None when unset or not
        positive (each count at most 99)."""
        text = (value or "").strip().lower()
        if not text:
            return None
        seasons, _, episodes = text.partition("x")
        s, e = cls.int_of(seasons, 0), cls.int_of(episodes, 20) if episodes else 20
        return (min(s, 99), min(e, 99)) if s > 0 and e > 0 else None

    def _big_series(self, seasons, episodes):
        """Many long seasons, every episode with a name of its own in the series (a row shows which season it is) and a
        Primary image; nothing watched, so the page opens on Season 1. Made after everything else with a random source of
        its own (the default fixture is unchanged), old enough to stay out of Home's "New in Shows"."""
        saved = self.rng
        self.rng = random.Random(5150)
        try:
            vid = _id("view", "Shows")
            # never a film's or a show's name (a card of one on the page must not read as an episode)
            names = ["The %s %s" % (a, n) for a in ADJECTIVES for n in NOUNS if "The %s %s" % (a, n) not in self._names]
            self.rng.shuffle(names)
            taken = iter(names)

            def unique(_):
                try:
                    return next(taken)
                except StopIteration:   # more than 900 episodes: numbered
                    return "Chapter %d" % self.rng.randrange(10 ** 6)

            self._series(vid, "Shows", self.BIG_SERIES_NAME, self.now - datetime.timedelta(days=3000), 2010, "big_series",
                         seasons=seasons, episodes=episodes, episode_name=unique)
            self.children[vid].sort(key=lambda i: self.sort_name(self.items[i]))
        finally:
            self.rng = saved

    def big_series_fixture(self):
        """The big series for GET /__harness/fixture: its seasons in order, each with its episodes' ids and names; None
        when FAKESERVER_BIG_SERIES is off."""
        sid = next((i for i, item in self.items.items() if item.get("showcase") == "big_series"), None)
        if not sid:
            return None
        return {"id": sid, "name": self.items[sid]["Name"], "library": "Shows",
                "seasons": [{"id": s, "name": self.items[s]["Name"], "index": self.items[s]["index"],
                             "episodes": [{"id": e, "name": self.items[e]["Name"], "index": self.items[e]["index"]}
                                          for e in self.children[s]]}
                            for s in self.children[sid]]}

    def _series(self, vid, view_name, name, created, year, showcase, seasons=None, episodes=None, episode_name=None):
        rng = self.rng
        sid = _id("series", view_name, name)
        genres = rng.sample(["Animation"] + GENRES if view_name == "Anime" else GENRES, rng.randint(1, 3))
        if view_name == "Anime" and "Animation" not in genres:
            genres.insert(0, "Animation")
        series = self._common(sid, "series", name, vid, vid, created, year, genres, rng.choice(TV_RATINGS))
        series["showcase"] = showcase
        series["status"] = rng.choice(["Continuing", "Ended"])
        self.items[sid] = series
        self.children[vid].append(sid)
        self.children[sid] = []
        if seasons is None:
            seasons = 3 if showcase else rng.choice([1, 1, 2, 2, 3, 3, 4, 5, 6])
        for s in range(1, seasons + 1):
            season_id = _id("season", sid, s)
            season_year = min(2026, year + s - 1)
            self.items[season_id] = {"Id": season_id, "kind": "season", "Name": "Season %d" % s, "parent": sid,
                                     "view": vid, "series": sid, "index": s, "created": created, "year": season_year,
                                     "premiere": datetime.datetime(season_year, 9, 1), "overview": "",
                                     "genres": [], "tags": [], "rating": series["rating"], "people": [], "studios": []}
            self.children[sid].append(season_id)
            self.children[season_id] = []
            for e in range(1, (episodes or (8 if showcase else rng.randint(6, 13))) + 1):
                eid = _id("episode", season_id, e)
                ep_name = episode_name(e) if episode_name else \
                    rng.choice(["The %s", "%s", "A %s Apart", "Under the %s", "After the %s", "The %s Problem"]) % rng.choice(NOUNS)
                ep_created = created + datetime.timedelta(days=(s - 1) * 120 + e * 7)
                if ep_created > self.now:
                    ep_created = self.now - datetime.timedelta(hours=rng.randint(1, 500))
                ep = self._common(eid, "episode", ep_name, season_id, vid, ep_created, season_year, series["genres"],
                                  series["rating"])
                ep.update(series=sid, season=season_id, index=e, parent_index=s,
                          premiere=datetime.datetime(season_year, 9, 1) + datetime.timedelta(days=7 * (e - 1)))
                ep["people"] = ep["people"][:4]
                self.items[eid] = ep
                self.children[season_id].append(eid)

    def _seed_user_data(self):
        rng = random.Random(7)
        movies = [i for i in self.items.values() if i["kind"] == "movie"]
        for m in movies:
            if m.get("showcase"):
                continue
            r = rng.random()
            if r < 0.25:
                self._ud(m["Id"]).update(played=True, play_count=rng.randint(1, 3),
                                         last_played=self.now - datetime.timedelta(days=rng.randint(2, 600)))
            elif r < 0.29:
                self._ud(m["Id"]).update(position=int(self.runtime * rng.uniform(0.15, 0.8)),
                                         last_played=self.now - datetime.timedelta(hours=rng.randint(2, 300)))
            if rng.random() < 0.08:
                self._ud(m["Id"])["favorite"] = True
        series = [i for i in self.items.values() if i["kind"] == "series"]
        for s in series:
            if s.get("showcase") == "series":
                # S1E1-E3 watched, E4 next up, nothing resumable: playing the showcase episode starts at 0
                eps = self.episodes_of(s["Id"])
                for k, e in enumerate(eps[:3]):
                    self._ud(e["Id"]).update(played=True, play_count=1,
                                             last_played=self.now - datetime.timedelta(days=3, hours=-k))
                continue
            if s.get("showcase") or rng.random() > 0.3:
                continue
            eps = self.episodes_of(s["Id"])
            watched = rng.randint(1, max(1, len(eps) - 1))
            when = self.now - datetime.timedelta(days=rng.randint(1, 60))
            for k, e in enumerate(eps[:watched]):
                self._ud(e["Id"]).update(played=True, play_count=1, last_played=when + datetime.timedelta(minutes=k))
            if rng.random() < 0.35 and watched < len(eps):
                self._ud(eps[watched]["Id"]).update(position=int(self.runtime * rng.uniform(0.2, 0.7)),
                                                    last_played=when + datetime.timedelta(minutes=watched + 1))
            if rng.random() < 0.1:
                self._ud(s["Id"])["favorite"] = True

    def _ud(self, item_id):
        return self.user.setdefault(item_id, {"played": False, "position": 0, "favorite": False, "play_count": 0,
                                              "last_played": None})

    # ---------------------------------------------------------------- lookups
    def get(self, item_id):
        return self.items.get(norm_id(item_id))

    def showcase(self):
        found = {}
        for item in self.items.values():
            if item.get("showcase"):
                found[item["showcase"]] = item
        series = found["series"]
        season = self.items[self.children[series["Id"]][0]]
        episode = self.items[self.children[season["Id"]][0]]
        # Nixie 1.4.0 (R in a grid): three letters ending in R that start a Movies title ("Mir" of "Mirror of Avalon"), for
        # the keys matrix's "m", "i", "r" within a second (R a letter of the word, not Refresh)
        movies = sorted(self.items[i]["Name"] for i in self.children.get(found["movie"]["view"], [])
                        if self.items[i]["kind"] == "movie")
        r_word = next((n[:3] for n in movies if len(n) > 3 and n[:3].isalpha() and n[2] in "rR" and n[0] not in "rR"
                       and not n.startswith("The ")), None)
        return {"movie": {"id": found["movie"]["Id"], "name": found["movie"]["Name"], "library": "Movies", "r_word": r_word},
                "series": {"id": series["Id"], "name": series["Name"], "library": "Shows"},
                "season": {"id": season["Id"], "name": season["Name"]},
                "episode": {"id": episode["Id"], "name": episode["Name"], "index": 1},
                "anime": {"id": found["anime"]["Id"], "name": found["anime"]["Name"], "library": "Anime"},
                "documentary": {"id": found["documentary"]["Id"], "name": found["documentary"]["Name"],
                                "library": "Documentaries"},
                "views": [self.items[v]["Name"] for v in self.views],
                # UXCAP: the collections (FAKESERVER_COLLECTIONS) and the big library (FAKESERVER_BIG_LIBRARY), when on
                "collections": [{"id": b, "name": self.items[b]["Name"], "count": len(self.children[b]),
                                 "first": self.items[self.children[b][0]]["Name"]}
                                for v in self.views if self.items[v].get("collection") == "boxsets" for b in self.children[v]],
                "big": [{"name": self.items[v]["Name"], "count": len(self.children[v])} for v in self.views
                        if self.items[v]["Name"] == "Archive"],
                # season speed: the big series (FAKESERVER_BIG_SERIES), None when off
                "big_series": self.big_series_fixture()}

    def episodes_of(self, series_id, season_id=None):
        out = []
        for season in self.children.get(series_id, []):
            if season_id and season != season_id:
                continue
            out.extend(self.items[e] for e in self.children.get(season, []))
        return out

    def descendants(self, item_id):
        out = []
        stack = list(reversed(self.children.get(item_id, [])))
        while stack:
            cid = stack.pop()
            out.append(self.items[cid])
            stack.extend(reversed(self.children.get(cid, [])))
        return out

    @staticmethod
    def sort_name(item):
        name = item["Name"]
        # Jellyfin's own sort names: episodes and seasons by their numbers, so SortName lists them in order
        if item["kind"] == "episode":
            return "%03d - %04d - %s" % (item["parent_index"], item["index"], name.lower())
        if item["kind"] == "season":
            return "%04d" % item["index"]
        for article in ("The ", "A ", "An "):
            if name.startswith(article) and item["kind"] in ("movie", "series"):
                # Jellyfin strips leading articles; the showcase names keep theirs ("A ...") to sort first
                return name[len(article):].lower() if article != "A " else " " + name.lower()
        return name.lower()

    # ---------------------------------------------------------------- user data
    def user_data(self, item):
        iid = item["Id"]
        with self.lock:
            ud = dict(self.user.get(iid) or {"played": False, "position": 0, "favorite": False, "play_count": 0,
                                             "last_played": None})
        out = {"PlaybackPositionTicks": ud["position"], "PlayCount": ud["play_count"], "IsFavorite": ud["favorite"],
               "Played": ud["played"], "Key": iid, "ItemId": iid}
        if ud["last_played"]:
            out["LastPlayedDate"] = iso(ud["last_played"])
        if ud["position"] and self.runtime:
            out["PlayedPercentage"] = round(100.0 * ud["position"] / self.runtime, 2)
        if item["kind"] in ("series", "season", "boxset"):
            eps = ([self.items[c] for c in self.children.get(iid, [])] if item["kind"] == "boxset"   # UXCAP
                   else self.episodes_of(item["series"] if item["kind"] == "season" else iid,
                                         iid if item["kind"] == "season" else None))
            unplayed = sum(1 for e in eps if not (self.user.get(e["Id"]) or {}).get("played"))
            out["UnplayedItemCount"] = unplayed
            out["Played"] = unplayed == 0
        return out

    def report(self, item_id, position_ticks=None, stopped=False):
        """A playback report: the resume point, and played once past 90 % (Jellyfin's default)."""
        item = self.get(item_id)
        if not item:
            return None
        with self.lock:
            ud = self._ud(item["Id"])
            ud["last_played"] = _utcnow()
            if position_ticks is not None:
                ud["position"] = max(0, int(position_ticks))
            if stopped and self.runtime and ud["position"] >= 0.9 * self.runtime:
                ud.update(played=True, position=0, play_count=ud["play_count"] + 1)
        return item

    def set_played(self, item_id, played):
        item = self.get(item_id)
        if not item:
            return None
        targets = [item] + (self.descendants(item["Id"]) if item["kind"] in ("series", "season", "boxset") else [])
        with self.lock:
            for t in targets:
                if t["kind"] in ("movie", "episode"):
                    ud = self._ud(t["Id"])
                    ud.update(played=played, position=0, last_played=_utcnow() if played else None,
                              play_count=ud["play_count"] + 1 if played else ud["play_count"])
        return item

    def set_position(self, item_id, ticks):
        """The user's resume point, as Jellyfin's POST /UserItems/{id}/UserData sets it (0: out of Continue Watching)."""
        item = self.get(item_id)
        if item and item["kind"] in ("movie", "episode"):
            with self.lock:
                self._ud(item["Id"])["position"] = max(0, int(ticks))
        return item

    def set_favorite(self, item_id, favorite):
        item = self.get(item_id)
        if item:
            with self.lock:
                self._ud(item["Id"])["favorite"] = favorite
        return item

    # ---------------------------------------------------------------- DTOs
    def image_tags(self, item):
        iid, kind = item["Id"], item["kind"]
        tags = {"Primary": _tag(iid, "Primary")}
        if kind in ("movie", "series"):
            tags.update(Logo=_tag(iid, "Logo"), Thumb=_tag(iid, "Thumb"), Banner=_tag(iid, "Banner"))
        if kind == "boxset":   # UXCAP
            tags.update(Thumb=_tag(iid, "Thumb"))
        return tags

    def dto(self, item, detail=False):
        from imagegen import blurhash_cached as blurhash
        iid, kind = item["Id"], item["kind"]
        types = {"view": "CollectionFolder", "movie": "Movie", "series": "Series", "season": "Season",
                 "episode": "Episode", "person": "Person", "boxset": "BoxSet",
                 # 1.5.1 fixes (BH-150-m7)
                 "video": "Video", "musicvideo": "MusicVideo", "musicalbum": "MusicAlbum", "audio": "Audio"}
        d = {"Name": item["Name"], "ServerId": self.server_id, "Id": iid, "Etag": _tag(iid, "etag")[:16],
             "DateCreated": iso(item["created"]), "CanDelete": False, "CanDownload": kind in ("movie", "episode"),
             "SortName": self.sort_name(item), "Type": "Playlist" if item.get("playlist") else types[kind], "LocationType": "FileSystem",
             "IsFolder": kind in ("view", "series", "season", "boxset"), "ImageTags": {}, "BackdropImageTags": [],
             "ImageBlurHashes": {}}
        if item.get("original"):   # 1.5 search: the original title (fields=OriginalTitle)
            d["OriginalTitle"] = item["original"]
        if kind == "person":
            d.update(ImageTags={"Primary": _tag(iid, "Primary")}, PrimaryImageAspectRatio=2 / 3,
                     ImageBlurHashes={"Primary": {_tag(iid, "Primary"): blurhash(iid + "Primary")}})
            return d
        if kind == "view":
            d.update(CollectionType=item["collection"], ImageTags={"Primary": _tag(iid, "Primary")},
                     PrimaryImageAspectRatio=16 / 9, ChildCount=len(self.children.get(iid, [])),
                     ImageBlurHashes={"Primary": {_tag(iid, "Primary"): blurhash(iid + "Primary")}},
                     Path="/media/%s" % item["Name"].lower(), ParentId=None)
            return d
        tags = self.image_tags(item)
        d["ImageTags"] = tags
        d["ImageBlurHashes"] = {k: {v: blurhash(iid + k)} for k, v in tags.items()}
        d["ParentId"] = item["parent"]
        d["UserData"] = self.user_data(item)
        d["PremiereDate"] = iso(item["premiere"])
        d["ProductionYear"] = item["year"]
        d["OfficialRating"] = item["rating"]
        d["Genres"] = item["genres"]
        d["GenreItems"] = [{"Name": g, "Id": _id("genre", g)} for g in item["genres"]]
        d["Tags"] = item["tags"]
        d["Overview"] = item["overview"]
        if kind == "boxset":   # UXCAP: Jellyfin's BoxSet: a folder of films, with a backdrop
            d.update(PrimaryImageAspectRatio=2 / 3, ChildCount=len(self.children.get(iid, [])),
                     MediaType="Video" if item.get("playlist") else "Unknown",   # 1.4.0: a video playlist, as Jellyfin says
                     BackdropImageTags=[_tag(iid, "Backdrop")], CommunityRating=None)
            d["ImageBlurHashes"]["Backdrop"] = {d["BackdropImageTags"][0]: blurhash(iid + "Backdrop")}
            d.pop("CommunityRating")
        if kind in ("movie", "series", "episode"):
            d["CommunityRating"] = item["community"]
            d["BackdropImageTags"] = [_tag(iid, "Backdrop")] if kind != "episode" else []
            if d["BackdropImageTags"]:
                d["ImageBlurHashes"]["Backdrop"] = {d["BackdropImageTags"][0]: blurhash(iid + "Backdrop")}
        if kind in ("video", "musicvideo", "audio", "musicalbum"):   # 1.5.1 fixes (BH-150-m7)
            d.update(MediaType={"audio": "Audio", "musicalbum": "Unknown"}.get(kind, "Video"), RunTimeTicks=self.runtime,
                     PrimaryImageAspectRatio=1.0 if kind in ("audio", "musicalbum") else 16 / 9,
                     IsFolder=kind == "musicalbum")
            if kind == "audio":
                d.update(AlbumId=item["album"], Album=item["album_name"])
        if kind == "movie":
            d.update(CriticRating=item["critic"], PrimaryImageAspectRatio=2 / 3, MediaType="Video",
                     VideoType="VideoFile", RunTimeTicks=self.runtime, Container="mkv", HasSubtitles=True,
                     Width=1280, Height=720, IsHD=True)
        elif kind == "series":
            d.update(PrimaryImageAspectRatio=2 / 3, Status=item["status"], AirDays=["Sunday"],
                     RunTimeTicks=self.runtime, ChildCount=len(self.children.get(iid, [])),
                     RecursiveItemCount=len(self.episodes_of(iid)), MediaType="Unknown")
            if item["status"] == "Ended":
                d["EndDate"] = iso(datetime.datetime(min(2026, item["year"] + 4), 6, 1))
        elif kind in ("season", "episode"):
            series = self.items[item["series"]]
            d.update(SeriesId=series["Id"], SeriesName=series["Name"], SeriesPrimaryImageTag=_tag(series["Id"], "Primary"),
                     ParentBackdropItemId=series["Id"], ParentBackdropImageTags=[_tag(series["Id"], "Backdrop")],
                     ParentLogoItemId=series["Id"], ParentLogoImageTag=_tag(series["Id"], "Logo"),
                     ParentThumbItemId=series["Id"], ParentThumbImageTag=_tag(series["Id"], "Thumb"),
                     IndexNumber=item["index"])
            if kind == "season":
                d.update(PrimaryImageAspectRatio=2 / 3, ChildCount=len(self.children.get(iid, [])), MediaType="Unknown")
            else:
                season = self.items[item["season"]]
                d.update(SeasonId=season["Id"], SeasonName=season["Name"], ParentIndexNumber=item["parent_index"],
                         PrimaryImageAspectRatio=16 / 9, MediaType="Video", VideoType="VideoFile",
                         RunTimeTicks=self.runtime, Container="mkv", HasSubtitles=True, Width=1280, Height=720)
        if detail:
            d["People"] = item["people"]
            d["Studios"] = [{"Name": s, "Id": _id("studio", s)} for s in item["studios"]]
            d["ProviderIds"] = {"Tmdb": str(int(iid[:6], 16)), "Imdb": "tt%07d" % (int(iid[6:12], 16) % 10000000)}
            d["ExternalUrls"] = []
            d["Taglines"] = [item["tagline"]] if item.get("tagline") else []
            # Nixie 1.3.1: the showcase movie has a YouTube trailer (the app plays it in its theatre window from the fake
            # server's own page, GET /__harness/trailer/<id>: no run ever contacts YouTube)
            d["RemoteTrailers"] = [{"Url": self.trailer_url or "https://www.youtube.com/watch?v=" + TRAILER_ID, "Name": "Trailer"}] \
                if item.get("showcase") == "movie" else []
            d["LockedFields"] = []
            d["LockData"] = False
            d["Path"] = self.path(item)
            if kind in ("movie", "episode"):
                d["Chapters"] = self.chapters(item)
        return d

    # 1.5 season: Jellyfin sends only the image types a request asks for (EnableImageTypes; EnableImages=false: none): an
    # item's own ImageTags and backdrops, and those it inherits from its show (ParentLogo..., ParentBackdrop..., ParentThumb...).
    # Without the parameter, every type (Jellyfin's default). This fake sent the show's logo tag in every list, so it never
    # showed that a list asked without Logo gave a season page a header that changed a moment after opening (review M1).
    PARENT_IMAGES = {"logo": ("ParentLogoItemId", "ParentLogoImageTag"), "backdrop": ("ParentBackdropItemId", "ParentBackdropImageTags"),
                     "thumb": ("ParentThumbItemId", "ParentThumbImageTag")}

    @classmethod
    def keep_image_types(cls, d, query):
        enable = [v.lower() for v in query.get("enableimages", [])]
        asked = [t.strip().lower() for v in query.get("enableimagetypes", []) for t in v.split(",") if t.strip()]
        if "false" in enable:
            asked, limited = [], True
        else:
            limited = bool(asked)
        if not limited or not isinstance(d, dict):
            return d
        d["ImageTags"] = {k: v for k, v in (d.get("ImageTags") or {}).items() if k.lower() in asked}
        d["ImageBlurHashes"] = {k: v for k, v in (d.get("ImageBlurHashes") or {}).items() if k.lower() in asked}
        if "backdrop" not in asked:
            d["BackdropImageTags"] = []
        for kind, fields in cls.PARENT_IMAGES.items():
            if kind not in asked:
                for field in fields:
                    d.pop(field, None)
        return d
    # end 1.5 season

    def path(self, item):
        kind = item["kind"]
        if kind == "movie":
            view = self.items[item["view"]]["Name"].lower()
            return "/media/%s/%s (%d)/%s (%d).mkv" % (view, item["Name"], item["year"], item["Name"], item["year"])
        if kind == "episode":
            series = self.items[item["series"]]
            return "/media/%s/%s/Season %02d/%s S%02dE%02d.mkv" % (
                self.items[item["view"]]["Name"].lower(), series["Name"], item["parent_index"], series["Name"],
                item["parent_index"], item["index"])
        if kind == "series":
            return "/media/%s/%s" % (self.items[item["view"]]["Name"].lower(), item["Name"])
        return ""

    def chapters(self, item):
        names = ["Opening", "The Arrival", "Complications", "Turning Point", "Confrontation", "Credits"]
        step = self.runtime // len(names)
        return [{"StartPositionTicks": k * step, "Name": n, "ImageTag": _tag(item["Id"], "Chapter%d" % k),
                 "ImageDateModified": iso(item["created"])} for k, n in enumerate(names)]

    def segments(self, item):
        """Intro and credits as MediaSegments: episodes have both, movies only credits."""
        rt = self.runtime
        out = []
        if item["kind"] == "episode":
            out.append({"Id": _id("seg", item["Id"], "intro"), "ItemId": item["Id"], "Type": "Intro",
                        "StartTicks": 10 * TICKS, "EndTicks": 40 * TICKS})
        if item["kind"] in ("episode", "movie"):
            out.append({"Id": _id("seg", item["Id"], "outro"), "ItemId": item["Id"], "Type": "Outro",
                        "StartTicks": max(0, rt - int(self.outro_seconds * TICKS)), "EndTicks": rt})
        return out

    # ---------------------------------------------------------------- queries
    def query(self, q):
        """GET /Items: q holds lower-cased keys with lists of values."""
        def one(key, default=None):
            v = q.get(key)
            return v[0] if v else default

        def many(key):
            return [x for v in q.get(key, []) for x in v.split(",") if x]

        def flag(key):
            v = one(key)
            return None if v is None else v.lower() == "true"

        types = {t.lower() for t in many("includeitemtypes")}
        exclude = {t.lower() for t in many("excludeitemtypes")}
        recursive = flag("recursive") or False
        parent = norm_id(one("parentid"))
        ids = [norm_id(i) for i in many("ids")]
        term = (one("searchterm") or "").lower()

        if ids:
            pool = [self.items[i] for i in ids if i in self.items]
        elif parent:
            if parent not in self.items:
                return {"Items": [], "TotalRecordCount": 0, "StartIndex": 0}
            pool = self.descendants(parent) if recursive else [self.items[c] for c in self.children.get(parent, [])]
        elif recursive or term:
            pool = [d for v in self.views for d in self.descendants(v)] + list(self.people.values()) +                 [self.items[i] for i in self.loose]   # 1.5.1 fixes (BH-150-m7)
        else:
            pool = [self.items[v] for v in self.views]

        kind_of = {"movie": "movie", "series": "series", "season": "season", "episode": "episode",
                   "person": "person", "collectionfolder": "view", "boxset": "boxset",
                   "playlist": "boxset",   # 1.4.0: the fake's playlist is a boxset-like item (Type Playlist)
                   "video": "video", "musicvideo": "musicvideo", "musicalbum": "musicalbum", "audio": "audio"}   # 1.5.1 fixes
        # UXCAP: a film in a collection is under two views; a recursive pool lists it once
        seen = set()
        pool = [i for i in pool if not (i["Id"] in seen or seen.add(i["Id"]))]
        if types:
            wanted = {kind_of.get(t) for t in types}
            pool = [i for i in pool if i["kind"] in wanted]
        elif term:
            pool = [i for i in pool if i["kind"] in ("movie", "series", "episode", "person", "boxset")]
        else:
            pool = [i for i in pool if i["kind"] != "person"]
        if exclude:
            gone = {kind_of.get(t) for t in exclude}
            pool = [i for i in pool if i["kind"] not in gone]
        # 1.5 search: matched as 12.4 matches (search.py), not as one plain substring of the name
        raw_term = one("searchterm") or ""
        # 1.5 search: a person's titles (/Items?personIds=)
        person_ids = {norm_id(p) for p in many("personids")}
        if person_ids:
            pool = [i for i in pool if any(norm_id(p.get("Id")) in person_ids for p in i.get("people") or [])]
        for key, field in (("genres", "genres"), ("tags", "tags")):
            values = {x for v in q.get(key, []) for x in v.split("|") if x}
            if values:
                pool = [i for i in pool if values & set(i.get(field) or [])]
        years = {int(y) for y in many("years") if y.isdigit()}
        if years:
            pool = [i for i in pool if i.get("year") in years]
        ratings = set(x for v in q.get("officialratings", []) for x in v.split("|") if x)
        if ratings:
            pool = [i for i in pool if i.get("rating") in ratings]
        filters = {f.lower() for f in many("filters")}
        if flag("isfavorite") or "isfavorite" in filters:
            pool = [i for i in pool if (self.user.get(i["Id"]) or {}).get("favorite")]
        if flag("isplayed") is True or "isplayed" in filters:
            pool = [i for i in pool if self.user_data(i).get("Played")]
        if flag("isplayed") is False or "isunplayed" in filters:
            pool = [i for i in pool if not self.user_data(i).get("Played")]
        if "isresumable" in filters:
            pool = [i for i in pool if (self.user.get(i["Id"]) or {}).get("position")]
        if many("mediatypes"):
            if "video" in {m.lower() for m in many("mediatypes")}:
                pool = [i for i in pool if i["kind"] in ("movie", "episode")]
        if one("namestartswith"):
            prefix = one("namestartswith").lower()
            pool = [i for i in pool if self.sort_name(i).lstrip().startswith(prefix)]
        start = self.int_of(one("startindex"), 0)
        limit = self.int_of(one("limit"), None)
        if term:
            # 1.5 search: by score, then sort name (12.4 ignores sortBy when searching); at most three times the limit
            pool = self.search.rank(pool, raw_term, self.sort_name, limit)
        elif not (all(i["kind"] == "view" for i in pool) and not many("sortby")):   # views keep their order
            pool = self.sort(pool, many("sortby"), (one("sortorder") or "Ascending").split(",")[0])
        total = len(pool)
        page = pool[start:start + limit if limit is not None else None]
        # 1.5 search (review m5): SortName and OriginalTitle only when asked for (fields=), as Jellyfin answers: a client
        # that stops asking for them sees them go
        fields = {f.lower() for f in many("fields")}
        return {"Items": [self.with_fields(self.dto(i), fields) for i in page], "TotalRecordCount": total, "StartIndex": start}

    @staticmethod
    def with_fields(d, fields):
        """1.5 search: a /Items answer's item: the optional fields Jellyfin sends only when asked for."""
        if "sortname" not in fields:
            d.pop("SortName", None)
        if "originaltitle" not in fields:
            d.pop("OriginalTitle", None)
        return d

    # 1.5 search ---------------------------------------------------------------------------------------------------
    def hints(self, q):
        """GET /Search/Hints: None for a missing term (the server's 400), else its answer (one limit, people in)."""
        term = (q.get("searchterm") or [""])[0]
        if not term.strip():
            return None
        include_people = (q.get("includepeople") or ["true"])[0].lower() != "false"
        kinds = {"movie", "series", "season", "episode", "boxset", "video", "musicvideo", "musicalbum", "audio"} |             ({"person"} if include_people else set())   # 1.5.1 fixes (BH-150-m7): videos and music too, as Jellyfin
        pool = [i for i in self.items.values() if i["kind"] in kinds]
        limit = self.int_of((q.get("limit") or [None])[0], None)
        ranked = self.search.rank(pool, term, self.sort_name, None)
        if limit is not None:
            ranked = ranked[:limit]
        return {"SearchHints": [search_mod.hint(self, i) for i in ranked], "TotalRecordCount": len(ranked)}

    def persons(self, q):
        """GET /Persons: people whose name holds the term (all of them without one)."""
        term = (q.get("searchterm") or [""])[0]
        pool = list(self.people.values())
        if term.strip():
            c = search_mod.clean(term)
            pool = [p for p in pool if c and c in search_mod.clean(p["Name"])]
        pool.sort(key=lambda p: p["Name"].lower())
        limit = self.int_of((q.get("limit") or [None])[0], None)
        page = pool[:limit] if limit is not None else pool
        return {"Items": [self.dto(p) for p in page], "TotalRecordCount": len(pool), "StartIndex": 0}

    @staticmethod
    def int_of(value, default):
        try:
            return int(value)
        except (TypeError, ValueError):
            return default

    def sort(self, pool, keys, order):
        keys = [k.lower() for k in keys] or ["sortname"]

        def key(i):
            ud = self.user.get(i["Id"]) or {}
            out = []
            for k in keys:
                if k in ("sortname", "name"):
                    out.append(self.sort_name(i))
                elif k == "datecreated":
                    out.append(i["created"].timestamp())
                elif k == "premieredate":
                    out.append(i.get("premiere", i["created"]).timestamp())
                elif k == "productionyear":
                    out.append(i.get("year") or 0)
                elif k == "communityrating":
                    out.append(i.get("community") or 0)
                elif k == "criticrating":
                    out.append(i.get("critic") or 0)
                elif k == "dateplayed" or k == "datelastcontentadded":
                    out.append((ud.get("last_played") or datetime.datetime(1970, 1, 1)).timestamp())
                elif k == "playcount":
                    out.append(ud.get("play_count", 0))
                elif k in ("indexnumber", "parentindexnumber"):
                    out.append((i.get("parent_index") or 0, i.get("index") or 0))
                elif k == "random":
                    out.append(random.random())
                else:
                    out.append(0)
            return out
        return sorted(pool, key=key, reverse=order.lower().startswith("desc"))

    def resume(self, limit=12, media_video=True, parent=None):
        pool = [i for i in self.items.values() if i["kind"] in ("movie", "episode")
                and (self.user.get(i["Id"]) or {}).get("position")]
        if parent:
            allowed = {d["Id"] for d in self.descendants(norm_id(parent))}
            pool = [i for i in pool if i["Id"] in allowed]
        pool.sort(key=lambda i: self.user[i["Id"]].get("last_played") or datetime.datetime(1970, 1, 1), reverse=True)
        return {"Items": [self.dto(i) for i in pool[:limit]], "TotalRecordCount": len(pool), "StartIndex": 0}

    def next_up(self, limit=24, series_id=None, rewatching=False):
        rows = []
        never = datetime.datetime(1970, 1, 1)
        for s in self.items.values():
            if s["kind"] != "series" or (series_id and s["Id"] != norm_id(series_id)):
                continue
            eps = self.episodes_of(s["Id"])
            ud = [self.user.get(e["Id"]) or {} for e in eps]
            last = None
            for k, e in enumerate(eps):
                if ud[k].get("played"):
                    last = k
            # sg-cw: EnableRewatching, as Jellyfin's TVSeriesManager: also the next *watched* episode after the one last
            # played (by date), so a show watched again comes back; both candidates sorted by their last-played date
            if rewatching and last is not None:
                played = [k for k in range(len(eps)) if ud[k].get("played")]
                recent = max(played, key=lambda k: (ud[k].get("last_played") or never, k))
                again = next((k for k in range(recent + 1, len(eps)) if ud[k].get("played") and not ud[k].get("position")), None)
                if again is not None:
                    rows.append((ud[recent].get("last_played") or never, eps[again]))
            if last is None or last + 1 >= len(eps):
                if series_id and last is None and eps:
                    rows.append((never, eps[0]))
                continue
            nxt = eps[last + 1]
            if ud[last + 1].get("position"):
                continue   # resumable: in Continue Watching instead (EnableResumable=false)
            rows.append((ud[last].get("last_played") or never, nxt))
        rows.sort(key=lambda r: r[0], reverse=True)
        return {"Items": [self.dto(e) for _, e in rows[:limit]], "TotalRecordCount": len(rows), "StartIndex": 0}

    def latest(self, parent, limit=16):
        view = self.get(parent)
        if not view or view["kind"] != "view":
            return []
        kids = [self.items[c] for c in self.children[view["Id"]]]
        if view["collection"] == "tvshows":
            def newest(s):
                eps = self.episodes_of(s["Id"])
                return max((e["created"] for e in eps), default=s["created"])
            kids.sort(key=newest, reverse=True)
        else:
            kids.sort(key=lambda i: i["created"], reverse=True)
        return [self.dto(i) for i in kids[:limit]]

    def similar(self, item_id, limit=12):
        item = self.get(item_id)
        if not item or not item.get("view"):
            return {"Items": [], "TotalRecordCount": 0, "StartIndex": 0}
        if item["kind"] in ("season", "episode"):
            item = self.items[item["series"]]
        peers = [self.items[c] for c in self.children[item["view"]] if c != item["Id"]]
        genres = set(item.get("genres") or [])
        peers.sort(key=lambda p: (-len(genres & set(p.get("genres") or [])), self.sort_name(p)))
        return {"Items": [self.dto(p) for p in peers[:limit]], "TotalRecordCount": len(peers), "StartIndex": 0}

    def filters(self, parent):
        pool = self.descendants(norm_id(parent)) if parent else [i for i in self.items.values() if i["kind"] != "person"]
        pool = [i for i in pool if i["kind"] in ("movie", "series")]
        return {"Genres": sorted({g for i in pool for g in i["genres"]}),
                "Tags": sorted({t for i in pool for t in i["tags"]}),
                "OfficialRatings": sorted({i["rating"] for i in pool}),
                "Years": sorted({i["year"] for i in pool}, reverse=True)}

    def seasons(self, series_id):
        s = self.get(series_id)
        kids = [self.items[c] for c in self.children.get(s["Id"], [])] if s else []
        return {"Items": [self.dto(k) for k in kids], "TotalRecordCount": len(kids), "StartIndex": 0}

    def episodes(self, series_id, season_id=None, start=0, limit=None):
        s = self.get(series_id)
        eps = self.episodes_of(s["Id"], norm_id(season_id) or None) if s else []
        page = eps[start:start + limit if limit else None]
        return {"Items": [self.dto(e) for e in page], "TotalRecordCount": len(eps), "StartIndex": start}

    def counts(self):
        kinds = {}
        for i in self.items.values():
            kinds[i["kind"]] = kinds.get(i["kind"], 0) + 1
        return kinds


def _utcnow():
    return datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None)


def new_guid():
    return uuid.uuid4().hex
