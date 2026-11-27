"""Tests of the TV server profiles (tvserver.py over the desktop fake server) and of the device files in
testing/devices. Standard library only, no mpv, nothing written into the desktop repository:

    python -B -m unittest discover -s testing/fakeserver -p "test_*.py"
"""

import glob
import inspect
import json
import os
import shutil
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.dont_write_bytecode = True

import tvserver  # noqa: E402

# Always the pinned copy (desktop/SOURCE.md): a change in the desktop repository must not change these tests.
DESKTOP_DIR = tvserver.DEFAULT_DESKTOP
desktop = tvserver.load_desktop(DESKTOP_DIR)
CREDS = desktop.load_credentials(os.path.join(DESKTOP_DIR, "credentials.json"))
PROFILES = tvserver.load_profiles()
DEVICES = os.path.join(os.path.dirname(HERE), "devices")


def make_media(folder, seconds=600):
    """A synthetic media folder in the shape of the desktop's media.py build (no mpv needed)."""
    os.makedirs(os.path.join(folder, "trickplay"), exist_ok=True)
    video = os.path.join(folder, "test.mkv")
    with open(video, "wb") as f:
        f.write(bytes(range(256)) * 64)
    with open(os.path.join(folder, "trickplay", "0.jpg"), "wb") as f:
        f.write(b"\xff\xd8fake\xff\xd9")
    srt = os.path.join(folder, "test.fr.srt")
    with open(srt, "w", encoding="utf-8") as f:
        f.write("1\n00:00:01,000 --> 00:00:02,000\n[Francais]\n")
    return {"seconds": seconds, "path": video, "size": os.path.getsize(video), "duration_ms": seconds * 1000,
            "video": {"codec": "h264", "width": 1280, "height": 720, "fps": 24, "bitrate": 1500000},
            "audio": [{"codec": "aac", "language": "eng", "title": "English", "channels": 2, "bitrate": 128000},
                      {"codec": "ac3", "language": "jpn", "title": "Japanese", "channels": 2, "bitrate": 192000}],
            "subtitles": [{"codec": "subrip", "language": "eng", "title": "English"}],
            "external_subtitle": {"codec": "srt", "language": "fre", "title": "French", "path": srt},
            "trickplay": {"width": 320, "height": 180, "tile": 10, "interval_ms": 10000, "thumbnails": 60,
                          "sheets": 1, "dir": os.path.join(folder, "trickplay"), "ok": True}}


def mb_header(device_id, token=None):
    h = 'MediaBrowser Client="Nixie TV test", Device="emulator", DeviceId="%s", Version="0.1.0"' % device_id
    return h + (', Token="%s"' % token if token else "")


class ProfileServer:
    """One TV server with a profile, on a free loopback port, in this process."""

    def __init__(self, name, **options):
        self.tmp = tempfile.mkdtemp(prefix="nixie-tvserver-")
        cls = tvserver.tv_server_class(desktop, PROFILES[name], **options)
        self.srv = cls(os.path.join(self.tmp, "out"), make_media(os.path.join(self.tmp, "media")), CREDS)
        threading.Thread(target=self.srv.serve, daemon=True).start()

    def close(self):
        self.srv.httpd.shutdown()
        self.srv.httpd.server_close()
        self.srv.log_file.close()
        self.srv.profile_log.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def call(self, path, method="GET", body=None, headers=None):
        """(status, parsed JSON or None)"""
        req = urllib.request.Request(self.srv.url + path, method=method, headers=headers or {},
                                     data=None if body is None else json.dumps(body).encode())
        if body is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                data = r.read()
                return r.status, json.loads(data) if data else None
        except urllib.error.HTTPError as e:
            e.close()
            return e.code, None

    def password_login(self, device_id):
        status, body = self.call("/Users/AuthenticateByName", "POST",
                                 {"Username": CREDS["username"], "Pw": CREDS["password"]},
                                 {"Authorization": mb_header(device_id)})
        assert status == 200, status
        return body["AccessToken"]

    def quick_connect_login(self, device_id):
        status, start = self.call("/QuickConnect/Initiate", "POST", headers={"Authorization": mb_header(device_id)})
        assert status == 200, status
        status, _ = self.call("/__harness/quickconnect/approve", "POST", {"code": start["Code"]})
        assert status == 200, status
        status, body = self.call("/Users/AuthenticateWithQuickConnect", "POST", {"Secret": start["Secret"]},
                                 {"Authorization": mb_header("another-id-on-the-second-call")})
        assert status == 200, status
        return body["AccessToken"]

    def signed_in(self, token, device_id="any"):
        return self.call("/System/Info", headers={"Authorization": mb_header(device_id, token)})[0] == 200


class ProfileTests(unittest.TestCase):
    def test_each_profile_answers_its_version(self):
        for name, profile in PROFILES.items():
            with self.subTest(name):
                s = ProfileServer(name)
                try:
                    status, info = s.call("/System/Info/Public")
                    self.assertEqual(status, 200)
                    self.assertEqual(info["Version"], profile["version"])
                    token = s.password_login("dev-v")
                    self.assertEqual(s.call("/System/Info", headers={"Authorization": mb_header("dev-v", token)})[1]["Version"],
                                     profile["version"])
                finally:
                    s.close()

    def test_jellyfin_12_refuses_get_initiate_and_legacy_auth(self):
        s = ProfileServer("jellyfin-12")
        try:
            self.assertEqual(s.call("/QuickConnect/Initiate", "GET", headers={"Authorization": mb_header("d")})[0], 405)
            self.assertEqual(s.call("/QuickConnect/Initiate", "POST", headers={"Authorization": mb_header("d")})[0], 200)
            token = s.password_login("dev-l")
            self.assertEqual(s.call("/System/Info", headers={"X-Emby-Token": token})[0], 401)
            self.assertEqual(s.call("/System/Info?api_key=" + token)[0], 401)
            self.assertTrue(s.signed_in(token))
        finally:
            s.close()

    def test_jellyfin_10_11_still_takes_the_old_forms(self):
        s = ProfileServer("jellyfin-10.11")
        try:
            self.assertEqual(s.call("/QuickConnect/Initiate", "GET", headers={"Authorization": mb_header("d")})[0], 200)
            token = s.password_login("dev-o")
            self.assertEqual(s.call("/System/Info", headers={"X-Emby-Token": token})[0], 200)
        finally:
            s.close()

    def test_one_token_per_device_id(self):
        s = ProfileServer("jellyfin-12")
        try:
            first = s.password_login("shared-id")
            other = s.password_login("other-id")
            second = s.password_login("shared-id")
            self.assertFalse(s.signed_in(first), "a second sign-in with the same DeviceId revokes the first token")
            self.assertTrue(s.signed_in(second))
            self.assertTrue(s.signed_in(other), "another DeviceId keeps its token")
        finally:
            s.close()

    def test_quick_connect_binds_the_token_to_the_initiate_device_id(self):
        s = ProfileServer("jellyfin-12")
        try:
            qc = s.quick_connect_login("attempt-1")
            self.assertTrue(s.signed_in(qc))
            s.password_login("another-id-on-the-second-call")     # the header DeviceId of the authenticate call
            self.assertTrue(s.signed_in(qc), "the token belongs to the Initiate DeviceId, not the later header's")
            s.password_login("attempt-1")
            self.assertFalse(s.signed_in(qc), "a sign-in with the Initiate DeviceId revokes the Quick Connect token")
        finally:
            s.close()

    def test_sign_in_answers_carry_what_sdk_1_9_4_requires(self):
        # Glacier's SDK refuses a UserDto or SessionInfoDto that misses a required member (found on the emulator).
        s = ProfileServer("jellyfin-12")
        try:
            status, body = s.call("/Users/AuthenticateByName", "POST",
                                  {"Username": CREDS["username"], "Pw": CREDS["password"]},
                                  {"Authorization": mb_header("policy-check")})
            self.assertEqual(200, status)
            policy = body["User"]["Policy"]
            for key in ("ForceRemoteSourceTranscoding", "EnableAllDevices", "EnableAllChannels", "SyncPlayAccess"):
                self.assertIn(key, policy)
            self.assertTrue(policy["EnableAllFolders"], "the fake's own values win")
            session = body["SessionInfo"]
            for key in ("LastPlaybackCheckIn", "HasCustomDeviceName", "SupportedCommands", "PlayState"):
                self.assertIn(key, session)
            status, me = s.call("/Users/Me", headers={"Authorization": mb_header("policy-check", body["AccessToken"])})
            self.assertEqual(200, status)
            self.assertIn("EnableAllDevices", me["Policy"])
        finally:
            s.close()

    def test_media_streams_carry_what_sdk_1_9_4_requires(self):
        answer = {"Items": [{"MediaSources": [{"MediaStreams": [{"Type": "Audio"}, {"Type": "Video", "IsOriginal": True}]}],
                             "MediaStreams": [{"Type": "Subtitle"}]}]}
        tvserver.complete_media_streams(answer)
        item = answer["Items"][0]
        self.assertEqual(False, item["MediaStreams"][0]["IsHearingImpaired"])
        self.assertEqual(False, item["MediaSources"][0]["MediaStreams"][0]["IsOriginal"])
        self.assertEqual(True, item["MediaSources"][0]["MediaStreams"][1]["IsOriginal"], "the fake's own values win")

    def test_device_profiles_sent_with_playback_info_are_recorded(self):
        s = ProfileServer("jellyfin-12")
        try:
            token = s.password_login("dev-p")
            movie = s.call("/__harness/fixture")[1]["movie"]["id"]
            with open(os.path.join(DEVICES, "emulator-google-tv-api34-x86.device-profile.json"), encoding="utf-8") as f:
                golden = {k: v for k, v in json.load(f).items() if not k.startswith("_")}
            status, info = s.call("/Items/%s/PlaybackInfo" % movie, "POST", {"DeviceProfile": golden},
                                  {"Authorization": mb_header("dev-p", token)})
            self.assertEqual(status, 200)
            self.assertTrue(info["PlaySessionId"])
            with open(os.path.join(s.srv.out, "device-profiles.jsonl"), encoding="utf-8") as f:
                lines = [json.loads(line) for line in f]
            self.assertEqual(len(lines), 1)
            self.assertEqual(lines[0]["profile"], golden)
            self.assertEqual(lines[0]["item"], movie.replace("-", ""))
        finally:
            s.close()

    def test_search_suggestions_answer_as_jellyfin_12_does(self):
        # The search page asks GET /Items/Suggestions before anything is typed; the desktop's fake has no such route
        # (a 404, "Search failed" on the emulator).
        s = ProfileServer("jellyfin-12")
        try:
            self.assertEqual(s.call("/Items/Suggestions?type=Movie,Series&limit=8")[0], 401)
            token = s.password_login("dev-s")
            h = {"Authorization": mb_header("dev-s", token)}
            uid = s.call("/Users/Me", headers=h)[1]["Id"]
            status, body = s.call("/Items/Suggestions?userId=%s&type=Movie,Series&limit=8" % uid, headers=h)
            self.assertEqual(status, 200)
            self.assertEqual(len(body["Items"]), 8)
            self.assertEqual({"Movie", "Series"}, {i["Type"] for i in body["Items"]})
            status, body = s.call("/Items/Suggestions?userId=%s&type=Movie&limit=3" % uid, headers=h)
            self.assertEqual({"Movie"}, {i["Type"] for i in body["Items"]})
            self.assertEqual(3, len(body["Items"]))
        finally:
            s.close()


    def test_the_showcase_movie_has_a_local_trailer_only_when_asked(self):
        # TV-S3: the trailer player checked on the emulator without YouTube (NIXIE_TV_LOCAL_TRAILER=1).
        s = ProfileServer("jellyfin-12")
        try:
            token = s.password_login("dev-t")
            h = {"Authorization": mb_header("dev-t", token)}
            uid = s.call("/Users/Me", headers=h)[1]["Id"]
            movie = tvserver.showcase_movie(s.srv.lib)
            path = "/Items/%s/LocalTrailers?userId=%s" % (movie, uid)
            self.assertEqual([], s.call(path, headers=h)[1])
            self.assertNotEqual(1, s.call("/Items/%s?userId=%s" % (movie, uid), headers=h)[1].get("LocalTrailerCount"))
            os.environ[tvserver.ENV_LOCAL_TRAILER] = "1"
            try:
                trailers = s.call(path, headers=h)[1]
                self.assertEqual(["Teaser"], [t["Name"] for t in trailers])
                self.assertEqual(1, s.call("/Items/%s?userId=%s" % (movie, uid), headers=h)[1]["LocalTrailerCount"])
                other = next(i for i, item in s.srv.lib.items.items() if item.get("kind") == "movie" and i != movie)
                self.assertEqual([], s.call("/Items/%s/LocalTrailers?userId=%s" % (other, uid), headers=h)[1])
            finally:
                del os.environ[tvserver.ENV_LOCAL_TRAILER]
        finally:
            s.close()


class RefusingServerTests(unittest.TestCase):
    """Nixie TV 1.0.1: the gate's server where the test user may not transcode (--refuse-transcodes), its slow link
    test (--bitrate-test-kbps) and GET /__harness/tv."""

    def playback_info(self, s, movie, headers, limit):
        return s.call("/Items/%s/PlaybackInfo" % movie, "POST", {"MaxStreamingBitrate": limit}, headers)

    def test_a_smaller_ask_is_refused_and_an_unlimited_one_plays_the_file(self):
        s = ProfileServer("jellyfin-12", refuse_transcodes=True)
        try:
            h = {"Authorization": mb_header("dev-r", s.password_login("dev-r"))}
            movie = s.call("/__harness/fixture")[1]["movie"]["id"]
            status, info = self.playback_info(s, movie, h, 120_000_000)
            self.assertEqual(200, status)
            self.assertNotIn("ErrorCode", info)
            self.assertTrue(info["MediaSources"][0]["SupportsDirectPlay"])
            status, info = self.playback_info(s, movie, h, 400_000)
            self.assertEqual(200, status)
            self.assertEqual("NoCompatibleStream", info["ErrorCode"])
            source = info["MediaSources"][0]
            self.assertFalse(source["SupportsDirectPlay"] or source["SupportsDirectStream"] or source["SupportsTranscoding"])
            self.assertNotIn("TranscodingUrl", source)
            self.assertEqual(1, s.call("/__harness/summary")[1]["by_route"]["playbackinfo-refused"])
        finally:
            s.close()

    def test_without_the_option_a_smaller_ask_is_answered(self):
        s = ProfileServer("jellyfin-12")
        try:
            h = {"Authorization": mb_header("dev-n", s.password_login("dev-n"))}
            movie = s.call("/__harness/fixture")[1]["movie"]["id"]
            status, info = self.playback_info(s, movie, h, 400_000)
            self.assertEqual(200, status)
            self.assertNotIn("ErrorCode", info)
        finally:
            s.close()

    def test_the_link_test_runs_at_the_rate_asked(self):
        s = ProfileServer("jellyfin-12", bitrate_test_kbps=800)
        try:
            started = time.monotonic()
            with urllib.request.urlopen(s.srv.url + "/Playback/BitrateTest?Size=65536", timeout=10) as r:
                data = r.read()
            took = time.monotonic() - started
            self.assertEqual(65536, len(data))
            # 65,536 bytes at 800 kbit/s take 0.66 s.
            self.assertGreater(took, 0.5)
            self.assertLess(took, 3.0)
            # The server logs the request after its last byte: closing before that would end its log first.
            time.sleep(0.3)
        finally:
            s.close()

    def test_harness_tv_names_the_server_the_user_and_the_movie(self):
        s = ProfileServer("jellyfin-12")
        try:
            token = s.password_login("dev-i")
            ids = s.call("/__harness/tv")[1]
            self.assertEqual(s.call("/System/Info/Public")[1]["Id"], ids["server_id"])
            uid = s.call("/Users/Me", headers={"Authorization": mb_header("dev-i", token)})[1]["Id"]
            self.assertEqual(uid.replace("-", ""), ids["user_id"].replace("-", ""))
            self.assertEqual(tvserver.showcase_movie(s.srv.lib), ids["movie_id"])
        finally:
            s.close()


class PinTests(unittest.TestCase):
    def test_the_desktop_server_is_the_pinned_copy(self):
        # Bug hunt M5: a change in the desktop repository's live checkout broke every sign-in.
        self.assertEqual(os.path.normcase(os.path.abspath(tvserver.DEFAULT_DESKTOP)),
                         os.path.normcase(os.path.join(HERE, "desktop")))
        with open(os.path.join(HERE, "desktop", "SOURCE.md"), encoding="utf-8") as f:
            self.assertRegex(f.read(), r"at commit \*\*[0-9a-f]{7,}\*\*")

    def test_overrides_keep_the_desktop_methods_signatures(self):
        # A method the TV server defines with a name the desktop's also has must take the same arguments: when a newer
        # desktop fake adds or changes one (fa95aa0's _device_id(self)), this fails here, not in the app's suite.
        cls = tvserver.tv_server_class(desktop, PROFILES["jellyfin-12"])
        pairs = [(cls, desktop.Server)]
        handler = cls._handler(object.__new__(cls))
        pairs.append((handler, handler.__mro__[1]))
        for tv, base in pairs:
            for name, member in vars(tv).items():
                if not callable(member) or name.startswith("__") or not hasattr(base, name):
                    continue
                with self.subTest("%s.%s" % (base.__name__, name)):
                    self.assertEqual(inspect.signature(member), inspect.signature(getattr(base, name)))


class DeviceFileTests(unittest.TestCase):
    def answers(self):
        files = [p for p in glob.glob(os.path.join(DEVICES, "*.json")) if not p.endswith(".device-profile.json")]
        self.assertTrue(files)
        for path in files:
            with open(path, encoding="utf-8") as f:
                yield os.path.basename(path), json.load(f)

    def test_answer_files_share_one_schema(self):
        keys = {"schema", "source", "device", "abis", "abis64", "memory", "display", "audioDirect", "decoders"}
        for name, data in self.answers():
            with self.subTest(name):
                self.assertEqual(data["schema"], "nixie-device-answers/1")
                self.assertLessEqual(keys, set(data))
                self.assertIn(data["source"], ("measured", "expected"))
                if data["source"] == "expected":
                    self.assertIs(data["verified"], False)
                    self.assertTrue(name.endswith(".expected.json"))

    def test_measured_emulator_answers(self):
        with open(os.path.join(DEVICES, "emulator-google-tv-api34-x86.json"), encoding="utf-8") as f:
            emu = json.load(f)
        self.assertEqual(emu["source"], "measured")
        self.assertIn("x86", emu["abis"])
        mimes = {d["mime"] for d in emu["decoders"]}
        self.assertLessEqual({"video/avc", "video/hevc", "video/x-vnd.on2.vp9", "video/av01"}, mimes)
        self.assertNotIn("audio/ac3", mimes, "no Dolby decoder on the emulator: AC-3 needs the FFmpeg decoder")

    def test_device_profile_follows_the_plan_rules(self):
        with open(os.path.join(DEVICES, "emulator-google-tv-api34-x86.device-profile.json"), encoding="utf-8") as f:
            dp = json.load(f)
        subs = {(p["Format"], p["Method"]) for p in dp["SubtitleProfiles"]}
        for fmt in ("srt", "subrip", "ttml", "vtt", "ass", "ssa"):
            self.assertIn((fmt, "Embed"), subs)
            self.assertIn((fmt, "External"), subs)
        for fmt in ("pgssub", "dvdsub", "dvbsub"):
            self.assertIn((fmt, "Embed"), subs)
            self.assertIn((fmt, "Encode"), subs)
        hls = [t for t in dp["TranscodingProfiles"] if t["Protocol"] == "hls"]
        self.assertEqual([t["Container"] for t in hls], ["mp4", "ts"], "fMP4 first, TS as the fallback")
        # the emulator's display reports no HDR, so every video codec is held to SDR
        for cp in dp["CodecProfiles"]:
            ranges = [c["Value"] for c in cp["Conditions"] if c["Property"] == "VideoRangeType"]
            self.assertEqual(ranges, ["SDR"], cp["Codec"])


if __name__ == "__main__":
    unittest.main()
