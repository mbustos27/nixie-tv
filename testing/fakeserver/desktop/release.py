"""Nixie 1.5 (the updater stream): the fake GitHub release the app's update check asks in a harness run (NIXIE_UPDATE_API),
as strict as GitHub where the app depends on it (the 1.4.0 bug hunt: "each fake models the happy answer"):

- GET /__harness/release/latest: GitHub's latest-release answer for the version set (FAKESERVER_RELEASE at start, or
  POST /__harness/release {"version"}; 0.0.1, up to date, by default).
  * No User-Agent: 403 with GitHub's own words (GitHub refuses API requests without one).
  * BH-150-m8: an X-GitHub-Api-Version GitHub doesn't support: 400 with GitHub's own words (live, 2026-11-20); none: its
    default, 2022-11-28. A 200 says which it used in x-github-api-version-selected, as GitHub does.
  * Every answer carries GitHub's rate-limit headers (x-ratelimit-limit 60, -remaining, -used, -reset as epoch seconds,
    -resource core); the 61st request within the hour gets 403 with remaining 0, as GitHub's primary limit does.
  * {"mode"}: "ok"; "ratelimited" (403, remaining 0, reset in 15 minutes); "retry-after" (429 with Retry-After: 90);
    "refuse" (403 with requests left: refused for another reason); "error" (502); "hang" (no answer for 30 s).
  * {"repository": "owner/name"} other than mbustos27/fluentfin-mpv: the repository was renamed; GitHub answers the old
    address with 301 to /repositories/<id>/..., here /__harness/release/repositories/4242/latest, whose answer names the
    new repository in its pages, its API address and its assets.
- GET /__harness/release/download/v<version>/<asset>: the installer (deterministic bytes, {"size"}, 24 MB by default) and
  its .sha256, as the app fetches them in a harness run (the app maps GitHub's asset addresses to these). The installer
  honours Range (206, or 416 past its end), is sent at {"kbps"} (8192 by default; FAKESERVER_RELEASE_KBPS and
  FAKESERVER_RELEASE_SIZE set both at start), and with {"cut_at": n} the next download
  stops after n bytes (a dropped connection, to resume). {"mismatch": true}: the .sha256 names other bytes.
  {"digest": "ok" | "none" | "wrong"}: the digest the API reports for the installer asset (GitHub's "sha256:...").
- POST /__harness/release answers the whole configuration; GET /__harness/release/state too, with the count of API requests.
"""

import hashlib
import json
import os
import random
import re
import threading
import time

DEFAULT_REPOSITORY = "mbustos27/fluentfin-mpv"
RENAMED_ID = 4242
NO_AGENT = {"message": "Request forbidden by administrative rules. Please make sure your request has a User-Agent header "
                       "(https://docs.github.com/en/rest/overview/resources-in-the-rest-api#user-agent-required). "
                       "Check https://developer.github.com for other possible causes."}
MODES = ("ok", "ratelimited", "retry-after", "refuse", "error", "hang")
#: BH-150-m8: the REST API versions GitHub supports (its own 400 listed them, 2026-11-20), and the one it uses without the header
API_VERSIONS = ("2026-03-10", "2022-11-28")
DEFAULT_API_VERSION = "2022-11-28"


def unsupported_version(version):
    """GitHub's 400 body for an X-GitHub-Api-Version it doesn't support (its words, 2026-11-20)."""
    return {"message": "Bad Request",
            "errors": "The version you specified in the \"X-GitHub-API-Version\" request header, \"%s\", is not a supported "
                      "version. The following versions are currently supported: \"2026-03-10\" (most recent) and "
                      "\"2022-11-28\"." % version,
            "documentation_url": "https://docs.github.com/rest", "status": "400"}


def release_answer(version, repository=DEFAULT_REPOSITORY, size=None, digest=None, notes=None):
    """GitHub's latest-release answer for `repository` at `version`: its page, API address and assets on github.com (the
    app only opens or downloads them on a click or, for an installed copy, in the background)."""
    base = "https://github.com/%s/releases" % repository
    msi = "Nixie-%s-win-x64.msi" % version
    installer = {"name": msi, "browser_download_url": "%s/download/v%s/%s" % (base, version, msi)}
    if size is not None:
        installer["size"] = size
    if digest:
        installer["digest"] = digest
    return {"url": "https://api.github.com/repos/%s/releases/1" % repository, "tag_name": "v" + version, "name": "Nixie " + version,
            "draft": False, "prerelease": False, "html_url": "%s/tag/v%s" % (base, version),
            "body": notes if notes is not None else "Harness release " + version,
            "assets": [installer, {"name": msi + ".sha256", "size": 90,
                                   "browser_download_url": "%s/download/v%s/%s.sha256" % (base, version, msi)}]}


def _env_int(name, default):
    try:
        value = int(os.environ.get(name) or default)
        return value if value > 0 else default
    except ValueError:
        return default


class FakeRelease:
    def __init__(self, version="0.0.1"):
        self.lock = threading.Lock()
        self.version = version
        self.mode = "ok"
        self.repository = DEFAULT_REPOSITORY
        # FAKESERVER_RELEASE_SIZE (bytes) and FAKESERVER_RELEASE_KBPS set the installer's size and speed at start
        self.size = _env_int("FAKESERVER_RELEASE_SIZE", 24 * 1024 * 1024)
        self.kbps = _env_int("FAKESERVER_RELEASE_KBPS", 8192)
        self.cut_at = None
        self.mismatch = False
        self.digest = "ok"
        self.limit = 60
        self.notes = None
        self.asked = []              # times of API requests (the hour's window)
        self.api_requests = 0
        self.api_version_asked = None   # BH-150-m8: the X-GitHub-Api-Version of the last API request it took
        self.downloads = 0
        self._bytes = {}

    def configure(self, body):
        with self.lock:
            version = str(body.get("version") or "")
            if re.fullmatch(r"\d+\.\d+\.\d+", version):
                self.version = version
            if body.get("mode") in MODES:
                self.mode = body["mode"]
            repository = body.get("repository")
            if isinstance(repository, str) and re.fullmatch(r"[A-Za-z0-9-]+/[A-Za-z0-9._-]+", repository):
                self.repository = repository
            for key in ("size", "kbps", "limit"):
                if isinstance(body.get(key), int) and body[key] > 0:
                    setattr(self, key, body[key])
            if "cut_at" in body:
                self.cut_at = body["cut_at"] if isinstance(body["cut_at"], int) and body["cut_at"] > 0 else None
            if "mismatch" in body:
                self.mismatch = bool(body["mismatch"])
            if body.get("digest") in ("ok", "none", "wrong"):
                self.digest = body["digest"]
            if "notes" in body:
                self.notes = body["notes"] if isinstance(body["notes"], str) else None
            if body.get("reset_limit"):
                self.asked = []
            return self.state()

    def state(self):
        return {"version": self.version, "mode": self.mode, "repository": self.repository, "size": self.size, "kbps": self.kbps,
                "cut_at": self.cut_at, "mismatch": self.mismatch, "digest": self.digest, "limit": self.limit,
                "api_requests": self.api_requests, "api_version_asked": self.api_version_asked, "downloads": self.downloads}

    def installer(self):
        """The installer's bytes for this version and size: the same every time (a seeded generator)."""
        key = (self.version, self.size)
        if key not in self._bytes:
            self._bytes = {key: random.Random("nixie-%s-%d" % key).randbytes(self.size)}
        return self._bytes[key]

    def sha256(self):
        return hashlib.sha256(self.installer()).hexdigest()

    # ------------------------------------------------------------------ the API
    def _limit_headers(self, now, remaining_override=None):
        with self.lock:
            self.asked = [t for t in self.asked if now - t < 3600]
            used = len(self.asked)
            reset = int((self.asked[0] if self.asked else now) + 3600)
        remaining = max(0, self.limit - used) if remaining_override is None else remaining_override
        return {"x-ratelimit-limit": str(self.limit), "x-ratelimit-remaining": str(remaining), "x-ratelimit-used": str(used),
                "x-ratelimit-reset": str(reset), "x-ratelimit-resource": "core"}

    def api(self, handler, path):
        """GET /__harness/release/latest or /__harness/release/repositories/<id>/latest."""
        now = time.time()
        if not (handler.headers.get("User-Agent") or "").strip():
            handler.route = "release:no-agent"
            return handler._json(403, NO_AGENT)
        asked_version = handler.headers.get("X-GitHub-Api-Version")
        if asked_version is not None and asked_version.strip() not in API_VERSIONS:
            handler.route = "release:api-version"
            return handler._json(400, unsupported_version(asked_version.strip()))
        self.api_version_asked = asked_version
        renamed = self.repository != DEFAULT_REPOSITORY
        if path == "/__harness/release/latest" and renamed:
            # GitHub's answer for a renamed repository's old address: a permanent redirect to its id
            handler.route = "release:moved"
            return handler._send(301, b"", "application/json", {"Location": "/__harness/release/repositories/%d/latest" % RENAMED_ID})
        with self.lock:
            self.api_requests += 1
            over = len([t for t in self.asked if now - t < 3600]) >= self.limit
            self.asked.append(now)
        if self.mode == "hang":
            time.sleep(30)
            return handler._json(504, {"message": "hung"})
        if over or self.mode == "ratelimited":
            handler.route = "release:rate-limited"
            headers = self._limit_headers(now, remaining_override=0)
            if self.mode == "ratelimited":
                headers["x-ratelimit-reset"] = str(int(now + 15 * 60))
            return handler._send(403, json.dumps({"message": "API rate limit exceeded for 127.0.0.1."}).encode(), "application/json; charset=utf-8", headers)
        headers = self._limit_headers(now)
        if self.mode == "retry-after":
            headers["Retry-After"] = "90"
            handler.route = "release:retry-after"
            return handler._send(429, json.dumps({"message": "You have exceeded a secondary rate limit."}).encode(), "application/json; charset=utf-8", headers)
        if self.mode == "refuse":
            handler.route = "release:refused"
            return handler._send(403, json.dumps({"message": "Forbidden"}).encode(), "application/json; charset=utf-8", headers)
        if self.mode == "error":
            handler.route = "release:error"
            return handler._send(502, json.dumps({"message": "Server Error"}).encode(), "application/json; charset=utf-8", headers)
        digest = {"ok": "sha256:" + self.sha256(), "wrong": "sha256:" + "0" * 64, "none": None}[self.digest]
        answer = release_answer(self.version, self.repository, self.size, digest, self.notes)
        headers["x-github-api-version-selected"] = (asked_version or DEFAULT_API_VERSION).strip()
        handler.route = "release:latest"
        return handler._send(200, json.dumps(answer).encode("utf-8"), "application/json; charset=utf-8", headers)

    # ------------------------------------------------------------------ the assets
    def download(self, handler, path):
        """GET /__harness/release/download/v<version>/<asset>."""
        m = re.fullmatch(r"/__harness/release/download/v(\d+\.\d+\.\d+)/(Nixie-\d+\.\d+\.\d+-win-x64\.msi)(\.sha256)?", path)
        if not m or m.group(1) != self.version or m.group(2) != "Nixie-%s-win-x64.msi" % self.version:
            handler.route = "release:no-asset"
            return handler._send(404, b"Not Found", "text/plain")
        name = m.group(2)
        if m.group(3):
            handler.route = "release:sha256"
            hexdigest = hashlib.sha256(b"other bytes").hexdigest() if self.mismatch else self.sha256()
            return handler._send(200, ("%s *%s\n" % (hexdigest, name)).encode(), "text/plain")
        data = self.installer()
        size = len(data)
        start = 0
        rng = handler.headers.get("Range")
        if rng:
            rm = re.fullmatch(r"bytes=(\d+)-", rng.strip())
            if rm:
                start = int(rm.group(1))
                if start >= size:
                    handler.route = "release:416"
                    return handler._send(416, b"", None, {"Content-Range": "bytes */%d" % size})
        with self.lock:
            self.downloads += 1
            cut, self.cut_at = self.cut_at, None
        handler.route = "release:installer" + (" from %d" % start if start else "")
        handler.extra = {"range_from": start, "cut_at": cut}
        handler.status = 206 if start else 200
        handler.send_response(handler.status)
        handler.send_header("Content-Type", "application/octet-stream")
        handler.send_header("Content-Length", str(size - start))
        if start:
            handler.send_header("Content-Range", "bytes %d-%d/%d" % (start, size - 1, size))
        handler.send_header("Accept-Ranges", "bytes")
        handler.end_headers()
        if handler.method == "HEAD":
            return
        chunk = 64 * 1024
        per_second = max(1, self.kbps) * 1024
        at = start
        began = time.perf_counter()
        while at < size:
            if cut is not None and at - start >= cut:
                handler.close_connection = True
                return
            n = min(chunk, size - at)
            handler.wfile.write(data[at:at + n])
            at += n
            handler.sent += n
            ahead = (at - start) / per_second - (time.perf_counter() - began)
            if ahead > 0:
                time.sleep(ahead)

    def handle(self, handler, method, path):
        """The release's routes; False for a path that is not one of them."""
        low = path.lower()
        if low in ("/__harness/release/latest", "/__harness/release/repositories/%d/latest" % RENAMED_ID) and method in ("GET", "HEAD"):
            self.api(handler, low)
            return True
        if low.startswith("/__harness/release/download/") and method in ("GET", "HEAD"):
            self.download(handler, path)
            return True
        if low == "/__harness/release" and method == "POST":
            handler._json(200, self.configure(handler._body()))
            return True
        if low == "/__harness/release/state" and method == "GET":
            handler._json(200, self.state())
            return True
        return False
