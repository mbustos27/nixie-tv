"""The TV app's test server: the Windows app's fake Jellyfin server, used as it is (read only, never edited), with a
server profile from profiles.json laid over it. Standard library only.

    python -B tvserver.py --profile jellyfin-12 --out <dir> --media <dir> [any option of the desktop server.py]

- --profile jellyfin-12 | jellyfin-10.11 | jellyfin-10.10 (profiles.json): the version /System/Info answers, the
  token rules (strict = Jellyfin 12, legacy = 10.x), whether GET /QuickConnect/Initiate still works (12 answers
  405), and one token per DeviceId (a sign-in revokes the earlier token of the same DeviceId; for Quick Connect the
  DeviceId is the one sent with Initiate). The profile's --auth wins over one given on the command line.
- Every DeviceProfile posted with PlaybackInfo is written to <out>/device-profiles.jsonl, so a test can compare
  what the app sent with testing/devices/*.device-profile.json.
- The desktop server is nixie-tv's own pinned copy in desktop/ (desktop/SOURCE.md names the fluentfin-mpv commit it
  came from), so a change in the desktop repository never breaks the TV's tests. --desktop-fakeserver DIR (or
  NIXIE_DESKTOP_FAKESERVER) runs another copy instead, to try a newer one. Nothing is written there (no bytecode).
- The server listens on 127.0.0.1 only. The emulator reaches it at 10.0.2.2; a real TV through `adb reverse`.
- NIXIE_TV_LOCAL_TRAILER=1: the showcase movie also has a local trailer file (its own test video), so the trailer
  player can be checked on the emulator without YouTube. Off by default.
- --refuse-transcodes (Nixie TV 1.0.1): a server where the test user may not transcode, as a shared server answers: a
  PlaybackInfo whose MaxStreamingBitrate is below a file's bitrate gets that file with nothing allowed and the
  ErrorCode NoCompatibleStream (route "playbackinfo-refused" in the summary); an unlimited one plays the file.
- --bitrate-test-kbps N (Nixie TV 1.0.1): GET /Playback/BitrateTest answers at N kbit/s, a slow link for the app's
  link test only; the streams themselves are not slowed.
- GET /__harness/tv (127.0.0.1 only): the server's id, the test user's id and the showcase movie's id, for a check
  that opens a title the way the TV's home screen does.
"""

import argparse
import json
import os
import re
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
# The desktop fake server, pinned (desktop/SOURCE.md): never the desktop repository's live checkout.
DEFAULT_DESKTOP = os.path.join(HERE, "desktop")
ENV_DESKTOP = "NIXIE_DESKTOP_FAKESERVER"
DEVICE_RE = re.compile(r'DeviceId="([^"]*)"')
PLAYBACK_INFO_RE = re.compile(r"/items/([0-9a-f]{32}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/playbackinfo")
# Nixie TV fix round (TV-S3): an opt-in local trailer for the showcase movie, for emulator checks without YouTube.
ENV_LOCAL_TRAILER = "NIXIE_TV_LOCAL_TRAILER"
LOCAL_TRAILERS_RE = re.compile(r"(?:/users/[0-9a-f-]+)?/items/([0-9a-f]{32}|[0-9a-f-]{36})/localtrailers")


def load_profiles(path=os.path.join(HERE, "profiles.json")):
    with open(path, encoding="utf-8") as f:
        return {k: v for k, v in json.load(f).items() if not k.startswith("_")}


def load_desktop(folder):
    """Imports the desktop server.py from `folder` without writing anything into it."""
    folder = os.path.abspath(folder)
    if not os.path.isfile(os.path.join(folder, "server.py")):
        raise SystemExit("tvserver: no server.py in %s (set %s or pass --desktop-fakeserver)" % (folder, ENV_DESKTOP))
    sys.dont_write_bytecode = True
    if folder not in sys.path:
        sys.path.insert(0, folder)
    import server  # noqa: E402  (the desktop fake server)
    return server


def tv_server_class(desktop, profile, refuse_transcodes=False, bitrate_test_kbps=0):
    """A subclass of the desktop Server with `profile` applied (and the 1.0.1 options: --refuse-transcodes,
    --bitrate-test-kbps)."""

    class TvServer(desktop.Server):
        tv_profile = profile
        tv_refuse_transcodes = refuse_transcodes
        tv_bitrate_test_kbps = bitrate_test_kbps

        def __init__(self, *args, **kwargs):
            kwargs["auth"] = profile["auth"]
            super().__init__(*args, **kwargs)
            # token -> DeviceId it was issued to (never logged). Its own name: the desktop's token_device (1.5.1) holds
            # (device, client) pairs for its own rule, and a shared name broke both (bug hunt M5).
            self.tv_token_device = {}
            self.version = profile["version"]
            self.profile_log = open(os.path.join(self.out, "device-profiles.jsonl"), "a", encoding="utf-8")
            self.profile_lock = threading.Lock()

        def user_dto(self, uid):
            """The desktop's user, with the policy fields Jellyfin 12 always sends: SDK 1.9.4 (Glacier's) refuses a
            UserDto whose Policy lacks any of its required members, where the desktop's C# client does not care."""
            dto = super().user_dto(uid)
            policy = dto.setdefault("Policy", {})
            for key, value in JELLYFIN_12_POLICY.items():
                policy.setdefault(key, value)
            return dto

        def bind_token(self, token, device_id):
            """Called with self.lock held: one token per DeviceId when the profile says so."""
            if profile["oneTokenPerDeviceId"] and device_id:
                for old, dev in list(self.tv_token_device.items()):
                    if dev == device_id and old != token:
                        self.tokens.pop(old, None)
                        self.token_device.pop(old, None)
                        del self.tv_token_device[old]
            self.tv_token_device[token] = device_id
            # The desktop's own map (1.5.1) must agree, or its next sign-in on the header's DeviceId would end a Quick
            # Connect token that belongs to the Initiate DeviceId.
            if token in self.token_device:
                self.token_device[token] = (device_id, self.token_device[token][1])

        def record_profile(self, item_id, body):
            dp = body.get("DeviceProfile") if isinstance(body, dict) else None
            if dp is None:
                return
            with self.profile_lock:
                self.profile_log.write(json.dumps({"t": round(time.time(), 3), "item": item_id, "profile": dp}) + "\n")
                self.profile_log.flush()

        def _handler(self):
            base = super()._handler()
            outer = self

            class TvHandler(base):
                def _route(self, method, path):
                    low = (path.rstrip("/") or "/").lower()
                    if low == "/quickconnect/initiate" and method == "GET" and not profile["getInitiate"]:
                        self.route = "quickconnect"
                        return self._json(405, None)        # Jellyfin 12 removed GET Initiate
                    if low == "/items/suggestions" and method == "GET":
                        self.route = "suggestions"
                        if not self._need_auth():
                            return None
                        return self._json(200, suggestions(outer.lib, self.q))
                    m = LOCAL_TRAILERS_RE.fullmatch(low)
                    if m and method == "GET" and os.environ.get(ENV_LOCAL_TRAILER):
                        self.route = "extras"
                        if not self._need_auth():
                            return None
                        return self._json(200, local_trailers(outer.lib, m.group(1)))
                    if low == "/playback/bitratetest" and outer.tv_bitrate_test_kbps > 0:
                        self.route = "bitrate-test"
                        return self._slow_bitrate_test()
                    if low == "/__harness/tv" and method == "GET":
                        self.route = "harness"
                        movie = showcase_movie(outer.lib)
                        return self._json(200, {"server_id": outer.server_id, "user_id": outer.user_id,
                                                "movie_id": movie})
                    m = PLAYBACK_INFO_RE.fullmatch(low)
                    if m and method == "POST":
                        outer.record_profile(m.group(1), self._body())
                    if method == "POST" and low in ("/users/authenticatebyname", "/users/authenticatewithquickconnect"):
                        device_id = self._tv_device_id(low)
                        with outer.lock:
                            before = set(outer.tokens)
                        result = super()._route(method, path)
                        # Tests sign in one at a time, so the new token is the one this request made.
                        with outer.lock:
                            for token in set(outer.tokens) - before:
                                outer.bind_token(token, device_id)
                        return result
                    return super()._route(method, path)

                def _slow_bitrate_test(self):
                    """The link test at --bitrate-test-kbps: the bytes go out in 4 KiB pieces, paced from the start."""
                    size = min(int(self._one("size", "500000")), 10_000_000)
                    self.status = 200
                    self.send_response(200)
                    self.send_header("Content-Type", "application/octet-stream")
                    self.send_header("Content-Length", str(size))
                    self.end_headers()
                    started, sent, rate = time.monotonic(), 0, outer.tv_bitrate_test_kbps * 1000
                    try:
                        while sent < size:
                            n = min(4096, size - sent)
                            self.wfile.write(b"\0" * n)
                            self.wfile.flush()
                            sent += n
                            wait = started + sent * 8 / rate - time.monotonic()
                            if wait > 0:
                                time.sleep(wait)
                    except (ConnectionError, OSError):
                        pass    # the app stops a test once it has its reading
                    self.sent = sent

                def _json(self, code, obj):
                    if outer.tv_refuse_transcodes and code == 200 and getattr(self, "route", None) == "playbackinfo":
                        if refuse_smaller(obj, self._body(), self._one("maxstreamingbitrate")):
                            self.route = "playbackinfo-refused"
                    complete_media_streams(obj)
                    if os.environ.get(ENV_LOCAL_TRAILER):
                        count_local_trailer(outer.lib, obj)
                    # Jellyfin 12's SessionInfoDto members that SDK 1.9.4 requires (the desktop's fake leaves them out).
                    session = obj.get("SessionInfo") if isinstance(obj, dict) else None
                    if isinstance(session, dict):
                        for key, value in JELLYFIN_12_SESSION.items():
                            session.setdefault(key, value)
                        session.setdefault("LastPlaybackCheckIn", session.get("LastActivityDate"))
                    return super()._json(code, obj)

                def _tv_device_id(self, low):
                    """The DeviceId a sign-in's token belongs to (its own name: the desktop's _device_id, 1.5.1, takes no
                    argument and is called by the desktop's sign-in)."""
                    if low.endswith("withquickconnect"):
                        # Quick Connect binds the token to the DeviceId sent with Initiate
                        entry = outer.quick.get(str(self._body().get("Secret") or ""))
                        return entry["device_id"] if entry else ""
                    auth = self.headers.get("Authorization") or ""
                    if not auth and profile["auth"] == desktop.AUTH_LEGACY:
                        auth = self.headers.get("X-Emby-Authorization") or ""
                    m = DEVICE_RE.search(auth)
                    return m.group(1) if m else ""

            return TvHandler

        def write_summary(self):
            super().write_summary()
            with open(os.path.join(self.out, "profile.json"), "w", encoding="utf-8") as f:
                json.dump(dict(profile, url=self.url), f, indent=2)

    return TvServer


def refuse_smaller(obj, body, query_max=None):
    """--refuse-transcodes: a PlaybackInfo answer (obj) to a request whose MaxStreamingBitrate (body, or the query's)
    is below a file's bitrate refuses that file, as a server where the user may not transcode does: nothing allowed,
    no transcoding URL, and ErrorCode NoCompatibleStream when no file is left. True when it refused one."""
    if not isinstance(obj, dict):
        return False
    limit = body.get("MaxStreamingBitrate") if isinstance(body, dict) else None
    if limit is None:
        limit = query_max
    try:
        limit = None if limit is None else int(limit)
    except (TypeError, ValueError):
        limit = None
    sources = [s for s in obj.get("MediaSources") or [] if isinstance(s, dict)]
    if limit is None or not sources:
        return False
    refused = [s for s in sources if int(s.get("Bitrate") or 0) > limit]
    for source in refused:
        source.update(SupportsDirectPlay=False, SupportsDirectStream=False, SupportsTranscoding=False)
        source.pop("TranscodingUrl", None)
    if refused and len(refused) == len(sources):
        obj["ErrorCode"] = "NoCompatibleStream"
    return bool(refused)


def showcase_movie(lib):
    """The id of the desktop library's showcase movie (the one with the YouTube trailer), or None."""
    return next((i for i, item in lib.items.items() if item.get("showcase") == "movie"), None)


def local_trailers(lib, item_id):
    """GET /Items/<id>/LocalTrailers with NIXIE_TV_LOCAL_TRAILER set: the showcase movie has one trailer file, its own
    test video under the name "Teaser", so the trailer player can be checked without YouTube. Other titles have none."""
    movie = showcase_movie(lib)
    if movie is None or item_id.replace("-", "") != movie.replace("-", ""):
        return []
    dto = dict(lib.dto(lib.items[movie]), Name="Teaser", Type="Trailer")
    return [dto]


def count_local_trailer(lib, obj):
    """With NIXIE_TV_LOCAL_TRAILER set, the showcase movie says it has one local trailer (LocalTrailerCount), wherever
    the answer carries it."""
    movie = showcase_movie(lib)
    items = obj.get("Items") if isinstance(obj, dict) and isinstance(obj.get("Items"), list) else [obj]
    for item in items:
        if isinstance(item, dict) and movie and str(item.get("Id", "")).replace("-", "") == movie.replace("-", ""):
            item["LocalTrailerCount"] = 1


def suggestions(lib, q):
    """GET /Items/Suggestions as Jellyfin 12 answers it (SuggestionsController; the desktop's fake has no such route,
    and the search page asks for it before anything is typed): the user's items of the kinds in `type` (and
    `mediaType`), from all libraries, at most `limit` from `startIndex`, as a BaseItemDto query result. Jellyfin orders
    them at random; here they keep the library's order, so a test can count on them."""
    sub = {"recursive": ["true"], "limit": q.get("limit") or ["10"], "startindex": q.get("startindex") or ["0"]}
    if q.get("type"):
        sub["includeitemtypes"] = q["type"]
    if q.get("mediatype"):
        sub["mediatypes"] = q["mediatype"]
    return lib.query(sub)


# Jellyfin 12's MediaStream members SDK 1.9.4 requires and the desktop's fake does not send (found on the emulator).
JELLYFIN_12_STREAM = {
    "IsHearingImpaired": False, "IsOriginal": False, "IsInterlaced": False, "IsDefault": False, "IsForced": False,
    "IsExternal": False, "IsTextSubtitleStream": False, "SupportsExternalStream": False, "VideoRange": "Unknown",
    "VideoRangeType": "Unknown", "AudioSpatialFormat": "None", "IsAVC": False,
}
# Jellyfin 12's MediaSourceInfo members SDK 1.9.4 requires.
JELLYFIN_12_SOURCE = {
    "Protocol": "File", "Type": "Default", "IsRemote": False, "ReadAtNativeFramerate": False, "IgnoreDts": False,
    "IgnoreIndex": False, "GenPtsInput": False, "SupportsTranscoding": True, "SupportsDirectStream": True,
    "SupportsDirectPlay": True, "IsInfiniteStream": False, "UseMostCompatibleTranscodingProfile": False,
    "RequiresOpening": False, "RequiresClosing": False, "RequiresLooping": False, "SupportsProbing": True,
    "TranscodingSubProtocol": "http", "HasSegments": False,
}


def complete_media_streams(obj, depth=0):
    """Fills JELLYFIN_12_STREAM into every MediaStreams entry and JELLYFIN_12_SOURCE into every MediaSources entry of an
    answer, wherever they nest (items, PlaybackInfo); the fake's own values win."""
    if depth > 12:
        return
    if isinstance(obj, dict):
        sources = obj.get("MediaSources")
        if isinstance(sources, list):
            for source in sources:
                if isinstance(source, dict):
                    for key, value in JELLYFIN_12_SOURCE.items():
                        source.setdefault(key, value)
        streams = obj.get("MediaStreams")
        if isinstance(streams, list):
            for stream in streams:
                if isinstance(stream, dict):
                    for key, value in JELLYFIN_12_STREAM.items():
                        stream.setdefault(key, value)
        for value in obj.values():
            if isinstance(value, (dict, list)):
                complete_media_streams(value, depth + 1)
    elif isinstance(obj, list):
        for value in obj:
            if isinstance(value, (dict, list)):
                complete_media_streams(value, depth + 1)


# Jellyfin 12's SessionInfoDto members the desktop's fake does not send.
JELLYFIN_12_SESSION = {
    "HasCustomDeviceName": False, "SupportedCommands": [], "PlayState": {"CanSeek": False, "IsPaused": False,
    "IsMuted": False, "RepeatMode": "RepeatNone", "PlaybackOrder": "Default"}, "AdditionalUsers": [],
    "NowPlayingQueue": [], "NowPlayingQueueFullItems": [],
}

# Every member of Jellyfin 12's UserPolicy with the server's defaults for an ordinary user (the fake's own values win).
JELLYFIN_12_POLICY = {
    "IsAdministrator": False, "IsHidden": False, "EnableCollectionManagement": False, "EnableSubtitleManagement": False,
    "EnableLyricManagement": False, "IsDisabled": False, "BlockedTags": [], "AllowedTags": [],
    "EnableUserPreferenceAccess": True, "AccessSchedules": [], "BlockUnratedItems": [],
    "EnableRemoteControlOfOtherUsers": False, "EnableSharedDeviceControl": True, "EnableRemoteAccess": True,
    "EnableLiveTvManagement": False, "EnableLiveTvAccess": True, "EnableMediaPlayback": True,
    "EnableAudioPlaybackTranscoding": True, "EnableVideoPlaybackTranscoding": True, "EnablePlaybackRemuxing": True,
    "ForceRemoteSourceTranscoding": False, "EnableContentDeletion": False, "EnableContentDeletionFromFolders": [],
    "EnableContentDownloading": True, "EnableSyncTranscoding": True, "EnableMediaConversion": True,
    "EnabledDevices": [], "EnableAllDevices": True, "EnabledChannels": [], "EnableAllChannels": True,
    "EnabledFolders": [], "EnableAllFolders": True, "InvalidLoginAttemptCount": 0, "LoginAttemptsBeforeLockout": -1,
    "MaxActiveSessions": 0, "EnablePublicSharing": True, "BlockedMediaFolders": [], "BlockedChannels": [],
    "RemoteClientBitrateLimit": 0,
    "AuthenticationProviderId": "Jellyfin.Server.Implementations.Users.DefaultAuthenticationProvider",
    "PasswordResetProviderId": "Jellyfin.Server.Implementations.Users.DefaultPasswordResetProvider",
    "SyncPlayAccess": "CreateAndJoinGroups",
}


def main(argv=None):
    profiles = load_profiles()
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--profile", required=True, choices=sorted(profiles))
    ap.add_argument("--desktop-fakeserver", default=os.environ.get(ENV_DESKTOP) or DEFAULT_DESKTOP)
    ap.add_argument("--refuse-transcodes", action="store_true",
                    help="refuse every PlaybackInfo below a file's bitrate with NoCompatibleStream")
    ap.add_argument("--bitrate-test-kbps", type=int, default=0, help="GET /Playback/BitrateTest at this many kbit/s")
    a, rest = ap.parse_known_args(argv)
    desktop = load_desktop(a.desktop_fakeserver)
    profile = profiles[a.profile]
    desktop.Server = tv_server_class(desktop, profile, a.refuse_transcodes, a.bitrate_test_kbps)
    print("tvserver: profile %s (Jellyfin %s, %s auth)%s%s" % (
        a.profile, profile["version"], profile["auth"], ", refusing transcodes" if a.refuse_transcodes else "",
        ", link test at %d kbit/s" % a.bitrate_test_kbps if a.bitrate_test_kbps else ""), flush=True)
    return desktop.main(rest + ["--auth", profile["auth"]])


if __name__ == "__main__":
    sys.exit(main())
