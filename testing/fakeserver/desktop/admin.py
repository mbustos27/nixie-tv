"""Nixie 1.2.0 (admin stream): the server administration and item-dialog endpoints of the fake Jellyfin, so the admin
pages and their dangerous buttons can be driven end to end against it (never a real server).

server.py calls route() for every signed-in request before its own routes; route() answers the ones below and returns
True, else returns False and server.py goes on. Every request that would change something on a real server is also
written to <out>/admin.jsonl (method, path, query and JSON body; passwords and tokens redacted), which the UI harness
reads to check that a confirmed button sent exactly one request and a cancelled one none.

- Server power: POST /System/Restart (the server then answers /System/Info with 500 for RESTART_SECONDS, as if it were
  down, and comes back), POST /System/Shutdown, POST /Library/Refresh, GET /ScheduledTasks (a realistic list),
  POST /ScheduledTasks/Running/{id}.
- Users: two extra users besides the sign-in ones ("bob", "carol"; they cannot sign in) in GET /Users and
  GET /Users/{id}; DELETE /Users/{id} removes an extra user (never one that can sign in: 403), POST /Users/New adds one,
  POST /Users/Password (a password change or reset), POST /Users/{id}/Policy.
- Libraries: GET /Library/VirtualFolders lists the library's views plus the ones added here, less the ones deleted;
  POST /Library/VirtualFolders (?name, collectionType; the body's LibraryOptions.PathInfos), DELETE ?name,
  POST /Library/VirtualFolders/Name (?name, newName), POST /Library/VirtualFolders/LibraryOptions,
  GET /Libraries/AvailableOptions, GET /Localization/Cultures and /Localization/Countries (a few each).
- Plugins: with FAKESERVER_PLAYBACK_REPORTING=1 in the environment, GET /Plugins lists the Playback Reporting plugin (its
  reports page shows; its own endpoints still answer 404, as for a plugin with no data).
- Items: GET /Items/{id}/MetadataEditor, POST /Items/{id} (the metadata editor's save: its body is logged),
  GET /Items/{id}/RemoteImages/Providers, DELETE /Videos/{id}/Subtitles/{index}; GET /Items/{id}/Images lists the
  item's images with three backdrops, and DELETE /Items/{id}/Images/{type}/{index} removes that one and renumbers the
  rest of its type, as Jellyfin does (a client that deletes by a stale index removes the wrong image).
"""

import hashlib
import json
import os
import re
import threading
import time

RESTART_SECONDS = 4.0
REDACT = {"pw", "password", "newpw", "currentpw", "currentpassword", "accesstoken", "api_key", "apikey", "token"}
ID = r"([0-9a-f]{32}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"

TASKS = [
    ("Scan Media Library", "RefreshLibrary", "Library", "Scans your media library for new files and refreshes metadata."),
    ("Extract Chapter Images", "RefreshChapterImages", "Library", "Creates thumbnails for videos that have chapters."),
    ("Generate Trickplay Images", "RefreshTrickplayImages", "Library", "Creates trickplay previews for videos in enabled libraries."),
    ("Clean Cache Directory", "DeleteCacheFiles", "Maintenance", "Deletes cache files no longer needed by the system."),
    ("Clean Log Directory", "CleanLogFiles", "Maintenance", "Deletes log files that are more than 3 days old."),
    ("Download missing subtitles", "DownloadSubtitles", "Library", "Searches the internet for missing subtitles based on metadata configuration."),
]

AVAILABLE = {
    "MetadataSavers": [{"Name": "Nfo", "DefaultEnabled": False}],
    "MetadataReaders": [{"Name": "Nfo", "DefaultEnabled": True}],
    "SubtitleFetchers": [{"Name": "Open Subtitles", "DefaultEnabled": True}],
    "LyricFetchers": [],
    "TypeOptions": [
        {"Type": t, "MetadataFetchers": [{"Name": "TheMovieDb", "DefaultEnabled": True},
                                         {"Name": "The Open Movie Database", "DefaultEnabled": False}],
         "ImageFetchers": [{"Name": "TheMovieDb", "DefaultEnabled": True}, {"Name": "Embedded Image Extractor", "DefaultEnabled": True},
                           {"Name": "Screen Grabber", "DefaultEnabled": False}],
         "SupportedImageTypes": ["Primary", "Backdrop", "Logo"], "DefaultImageOptions": []}
        for t in ("Movie", "Series", "Season", "Episode")],
}

CULTURES = [{"Name": n, "DisplayName": n, "TwoLetterISOLanguageName": two, "ThreeLetterISOLanguageName": three,
             "ThreeLetterISOLanguageNames": [three]}
            for n, two, three in (("English", "en", "eng"), ("French", "fr", "fre"), ("German", "de", "ger"), ("Spanish", "es", "spa"))]
COUNTRIES = [{"Name": two, "DisplayName": n, "TwoLetterISORegionName": two, "ThreeLetterISORegionName": three}
             for n, two, three in (("United States", "US", "USA"), ("United Kingdom", "GB", "GBR"), ("France", "FR", "FRA"))]


def _uid(name):
    return hashlib.md5(("ffh-admin-user:" + name).encode()).hexdigest()


class AdminState:
    def __init__(self, outer):
        self.lock = threading.Lock()
        self.extra_users = {_uid(n): n for n in ("bob", "carol")}
        self.added = []             # libraries added here: {"Name", "CollectionType", "Locations", "ItemId"}
        self.deleted = set()        # names
        self.renamed = {}           # old name -> new name
        self.down_until = 0.0
        self.images = {}            # item id -> [{"ImageType", "ImageTag", "Width", "Height"}] once listed or deleted from
        self.log = os.path.join(outer.out, "admin.jsonl")


def state(outer):
    with outer.lock:
        if getattr(outer, "admin_state", None) is None:
            outer.admin_state = AdminState(outer)
        return outer.admin_state


def _redact(value):
    if isinstance(value, dict):
        return {k: ("<redacted>" if k.lower() in REDACT else _redact(v)) for k, v in value.items()}
    if isinstance(value, list):
        return [_redact(v) for v in value]
    return value


def _record(handler, st, method, path):
    body = handler._body() if handler.body_bytes else None
    entry = {"t": round(time.time(), 4), "method": method, "path": path, "query": handler._redacted(), "body": _redact(body)}
    with st.lock, open(st.log, "a", encoding="utf-8") as f:
        f.write(json.dumps(entry) + "\n")


def _norm(i):
    return i.replace("-", "").lower()


def _user(outer, st, uid):
    if uid in outer.users:
        return outer.user_dto(uid)
    name = st.extra_users.get(uid)
    if name is None:
        return None
    dto = outer.user_dto(outer.user_id)
    dto = json.loads(json.dumps(dto))
    dto.update({"Name": name, "Id": uid, "HasPassword": name != "carol", "HasConfiguredPassword": name != "carol"})
    dto["Policy"]["IsAdministrator"] = False
    return dto


def _folders(outer, st):
    lib = outer.lib
    folders = [{"Name": lib.items[v]["Name"], "Locations": ["/media/" + lib.items[v]["Name"].lower()],
                "CollectionType": lib.items[v]["collection"], "ItemId": v, "LibraryOptions": {}, "RefreshStatus": "Idle"}
               for v in lib.views] + list(st.added)
    out = []
    for f in folders:
        name = st.renamed.get(f["Name"], f["Name"])
        if name in st.deleted:
            continue
        out.append(dict(f, Name=name))
    return out


SIZES = {"Primary": (1000, 1500), "Backdrop": (1920, 1080), "Logo": (800, 310), "Thumb": (1280, 720), "Banner": (1000, 185)}


def _images(outer, st, item_id):
    """The item's images, kept here once asked for: its own types plus three backdrops (deletes change this list)."""
    with st.lock:
        if item_id not in st.images:
            item = outer.lib.get(item_id)
            if not item:
                return None
            tags = dict(outer.lib.image_tags(item))
            entries = [{"ImageType": k, "ImageTag": v} for k, v in tags.items()]
            entries += [{"ImageType": "Backdrop", "ImageTag": hashlib.md5(("%s:backdrop:%d" % (item_id, i)).encode()).hexdigest()}
                        for i in range(3)]
            for e in entries:
                e["Width"], e["Height"] = SIZES.get(e["ImageType"], (1000, 1000))
            st.images[item_id] = entries
        return st.images[item_id]


def _numbered(entries):
    """ImageIndex per type, 0 up, in list order (what Jellyfin reports, and renumbers after a delete)."""
    seen, out = {}, []
    for e in entries:
        i = seen.get(e["ImageType"], 0)
        seen[e["ImageType"]] = i + 1
        out.append(dict(e, ImageIndex=i))
    return out


def route(handler, outer, method, path, low):
    """Answers an admin endpoint (True), or leaves the request to server.py (False). path has no trailing slash."""
    st = state(outer)

    def match(rx):
        return re.fullmatch(rx, low)

    def done(route_name, code=204, obj=None, record=True):
        handler.route = "admin-" + route_name
        if record:
            _record(handler, st, method, path)
        return handler._json(code, obj) if obj is not None else handler._send(code)

    def answer(route_name, obj):
        handler.route = "admin-" + route_name
        handler._json(200, obj)
        return True

    # a restart in progress: the server is "down"
    if low == "/system/info" and time.time() < st.down_until:
        handler.route = "admin-restarting"
        handler._json(500, {"error": "restarting (fake)"})
        return True

    if method == "GET" and low == "/plugins" and os.environ.get("FAKESERVER_PLAYBACK_REPORTING"):
        return answer("plugins", [{"Name": "Playback Reporting", "Version": "16.0.0.0", "Id": "5c534381-91a3-43cb-907a-35aa02eb9d2c",
                                   "CanUninstall": True, "HasImage": False, "Status": "Active"}])
    if method == "POST" and low == "/system/restart":
        st.down_until = time.time() + RESTART_SECONDS
        done("restart")
        return True
    if method == "POST" and low == "/system/shutdown":
        done("shutdown")
        return True
    if method == "POST" and low == "/library/refresh":
        done("library-refresh")
        return True
    if method == "GET" and low == "/scheduledtasks":
        now = time.strftime("%Y-%m-%dT%H:%M:%S.0000000Z", time.gmtime(time.time() - 3600))
        return answer("tasks", [{"Name": n, "State": "Idle", "Id": hashlib.md5(k.encode()).hexdigest(), "Key": k,
                                 "Category": c, "Description": d, "IsHidden": False, "Triggers": [],
                                 "LastExecutionResult": {"StartTimeUtc": now, "EndTimeUtc": now, "Status": "Completed",
                                                         "Name": n, "Key": k, "Id": hashlib.md5(k.encode()).hexdigest()}}
                                for n, k, c, d in TASKS])
    if method == "POST" and match(r"/scheduledtasks/running/[0-9a-f]{32}"):
        done("task-run")
        return True

    # users
    if method == "GET" and low == "/users":
        return answer("users", [outer.user_dto(u) for u in outer.users] + [_user(outer, st, u) for u in st.extra_users])
    mm = match(r"/users/" + ID)
    if mm and method == "GET" and _norm(mm.group(1)) in st.extra_users:
        return answer("user", _user(outer, st, _norm(mm.group(1))))
    if mm and method == "DELETE":
        uid = _norm(mm.group(1))
        if uid in outer.users:
            done("user-delete-refused", 403)       # never the users the harness signs in with
            return True
        with st.lock:
            existed = st.extra_users.pop(uid, None)
        done("user-delete", 204 if existed else 404)
        return True
    if method == "POST" and low == "/users/new":
        name = str(handler._body().get("Name") or "").strip()
        if not name or name in st.extra_users.values() or any(u["name"] == name for u in outer.users.values()):
            done("user-new-refused", 400)
            return True
        uid = _uid(name)
        with st.lock:
            st.extra_users[uid] = name
        done("user-new", 200, _user(outer, st, uid))
        return True
    if method == "POST" and low == "/users/password":
        done("user-password")
        return True
    if method == "POST" and match(r"/users/" + ID + r"/policy"):
        done("user-policy")
        return True

    # libraries
    if low == "/library/virtualfolders":
        if method == "GET":
            return answer("virtual-folders", _folders(outer, st))
        name = handler._one("name") or ""
        if method == "POST":
            body = handler._body()
            paths = [p.get("Path") for p in ((body.get("LibraryOptions") or {}).get("PathInfos") or []) if p.get("Path")]
            if not name or any(f["Name"].lower() == name.lower() for f in _folders(outer, st)):
                done("library-add-refused", 400)
                return True
            with st.lock:
                st.added.append({"Name": name, "CollectionType": handler._one("collectiontype"), "Locations": paths,
                                 "ItemId": hashlib.md5(("ffh-admin-lib:" + name).encode()).hexdigest(),
                                 "LibraryOptions": body.get("LibraryOptions") or {}, "RefreshStatus": "Idle"})
            done("library-add")
            return True
        if method == "DELETE":
            exists = any(f["Name"] == name for f in _folders(outer, st))
            if exists:
                with st.lock:
                    st.deleted.add(name)
            done("library-delete", 204 if exists else 404)
            return True
    if method == "POST" and low == "/library/virtualfolders/name":
        old, new = handler._one("name") or "", handler._one("newname") or ""
        with st.lock:
            st.renamed[old] = new
        done("library-rename")
        return True
    if method == "POST" and low == "/library/virtualfolders/libraryoptions":
        done("library-options")
        return True
    if method == "GET" and low == "/libraries/availableoptions":
        return answer("available-options", AVAILABLE)
    if method == "GET" and low == "/localization/cultures":
        return answer("cultures", CULTURES)
    if method == "GET" and low == "/localization/countries":
        return answer("countries", COUNTRIES)

    # item dialogs
    mm = match(r"/items/" + ID + r"/metadataeditor")
    if mm and method == "GET":
        return answer("metadata-editor", {
            "ParentalRatingOptions": [{"Name": n, "Value": v} for n, v in (("G", 0), ("PG", 10), ("PG-13", 13), ("R", 17))],
            "Countries": [], "Cultures": [],
            "ExternalIdInfos": [{"Name": "IMDb", "Key": "Imdb", "Type": None}, {"Name": "TheMovieDb", "Key": "Tmdb", "Type": None}]})
    mm = match(r"/items/" + ID + r"/remoteimages/providers")
    if mm and method == "GET":
        return answer("image-providers", [{"Name": "TheMovieDb", "SupportedImages": ["Primary", "Backdrop", "Logo"]}])
    mm = match(r"/items/" + ID + r"/remoteimages")
    if mm and method == "GET":
        return answer("remote-images", {"Images": [], "TotalRecordCount": 0, "Providers": ["TheMovieDb"]})
    if method == "POST" and match(r"(?:/users/" + ID + r")?/items/" + ID):
        done("item-update")
        return True
    mm = match(r"/items/" + ID + r"/images")
    if mm and method == "GET":
        entries = _images(outer, st, _norm(mm.group(1)))
        return answer("image-list", _numbered(entries) if entries is not None else [])
    mm = match(r"/items/" + ID + r"/images/([a-z]+)/(\d+)")
    if mm and method == "DELETE":
        entries = _images(outer, st, _norm(mm.group(1))) or []
        kind, index = mm.group(2).lower(), int(mm.group(3))
        with st.lock:
            of_kind = [e for e in entries if e["ImageType"].lower() == kind]
            found = index < len(of_kind)
            if found:
                entries.remove(of_kind[index])      # the rest of its type move up one
        done("image-delete" if found else "image-delete-missing", 204 if found else 404)
        return True
    if method == "DELETE" and match(r"/videos/" + ID + r"/subtitles/\d+"):
        done("subtitle-delete")
        return True
    return False
