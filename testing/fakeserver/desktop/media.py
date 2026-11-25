"""The fake server's test video, made with mpv's encoder, and its trickplay tiles.

mpv's encoder writes one video and one audio stream per file, so each stream is encoded on its own (a test pattern with
a running counter; two audio tracks of digital silence, since no test run may make a sound) and a small Matroska muxer
here combines them with two subtitle tracks (S_TEXT/UTF8) into one .mkv: video, audio "English" (AAC), audio
"Japanese" (AC-3), subtitles "English" and "Spanish". A third subtitle track, French, is the server's external .srt.
Trickplay: mpv tiles one frame every 10 s into 10x10 sheets of 320x180 JPEGs, Jellyfin's layout.

    python media.py --mpv <mpv.exe> --out <dir> [--seconds 600]

Standard library only. mpv runs in encode mode (no window) with --no-config. Writes only under --out.
"""

import argparse
import glob
import json
import os
import struct
import subprocess
import sys

# Matroska element ids
EBML, SEGMENT, SEEKHEAD, SEEK, SEEKID, SEEKPOSITION = 0x1A45DFA3, 0x18538067, 0x114D9B74, 0x4DBB, 0x53AB, 0x53AC
INFO, TIMESCALE, DURATION, MUXINGAPP, WRITINGAPP, TITLE = 0x1549A966, 0x2AD7B1, 0x4489, 0x4D80, 0x5741, 0x7BA9
TRACKS, TRACKENTRY, TRACKNUMBER, TRACKUID, TRACKTYPE, CODECID = 0x1654AE6B, 0xAE, 0xD7, 0x73C5, 0x83, 0x86
LANGUAGE, LANGUAGE_BCP47, NAME, FLAGDEFAULT, FLAGFORCED, FLAGLACING = 0x22B59C, 0x22B59D, 0x536E, 0x88, 0x55AA, 0x9C
CLUSTER, TIMESTAMP, SIMPLEBLOCK, BLOCKGROUP, BLOCK, BLOCKDURATION, REFERENCEBLOCK = 0x1F43B675, 0xE7, 0xA3, 0xA0, 0xA1, 0x9B, 0xFB
CUES, CUEPOINT, CUETIME, CUETRACKPOSITIONS, CUETRACK, CUECLUSTERPOSITION = 0x1C53BB6B, 0xBB, 0xB3, 0xB7, 0xF7, 0xF1

VIDEO_W, VIDEO_H, FPS = 1280, 720, 24
TRICK_W, TRICK_H, TRICK_TILES, TRICK_INTERVAL_S = 320, 180, 10, 10


def _read_vint(buf, pos, keep_marker):
    first = buf[pos]
    length, mask = 1, 0x80
    while length <= 8 and not first & mask:
        length += 1
        mask >>= 1
    if length > 8:
        raise ValueError("bad EBML vint at %d" % pos)
    value = first if keep_marker else first & (mask - 1)
    for i in range(1, length):
        value = (value << 8) | buf[pos + i]
    unknown = not keep_marker and value == (1 << (7 * length)) - 1
    return value, pos + length, unknown


def elements(buf, start, end, with_header=False):
    """(id, payload start, payload end) of each element between start and end (with_header: the element's own start
    first)."""
    pos = start
    while pos < end:
        eid, p, _ = _read_vint(buf, pos, True)
        size, p, unknown = _read_vint(buf, p, False)
        stop = end if unknown else min(end, p + size)
        yield (pos, eid, p, stop) if with_header else (eid, p, stop)
        pos = stop


def _uint_of(buf, start, end):
    return int.from_bytes(buf[start:end], "big") if end > start else 0


def _id_bytes(eid):
    return eid.to_bytes((eid.bit_length() + 7) // 8, "big")


def _size_bytes(n, width=None):
    if width:
        return (n | (1 << (7 * width))).to_bytes(width, "big")
    for length in range(1, 9):
        if n < (1 << (7 * length)) - 1:
            return (n | (1 << (7 * length))).to_bytes(length, "big")
    raise ValueError("element too large")


def el(eid, payload):
    return _id_bytes(eid) + _size_bytes(len(payload)) + payload


def el_uint(eid, value, width=None):
    width = width or max(1, (value.bit_length() + 7) // 8)
    return el(eid, value.to_bytes(width, "big"))


def el_str(eid, text):
    return el(eid, text.encode("utf-8"))


def read_mkv(path):
    """Tracks (number -> raw children of its TrackEntry, minus number/uid/language/name/flags) and blocks
    (time in ms, track number, keyframe, frame payload after the block header) of an mpv/lavf Matroska file."""
    with open(path, "rb") as f:
        buf = f.read()
    tracks, blocks, scale = {}, [], 1000000
    for eid, s, e in elements(buf, 0, len(buf)):
        if eid != SEGMENT:
            continue
        for sid, ss, se in elements(buf, s, e):
            if sid == INFO:
                for iid, is_, ie in elements(buf, ss, se):
                    if iid == TIMESCALE:
                        scale = _uint_of(buf, is_, ie)
            elif sid == TRACKS:
                for tid, ts, te in elements(buf, ss, se):
                    if tid != TRACKENTRY:
                        continue
                    number, keep = None, []
                    for header, cid, cs, ce in elements(buf, ts, te, with_header=True):
                        if cid == TRACKNUMBER:
                            number = _uint_of(buf, cs, ce)
                        elif cid not in (TRACKUID, LANGUAGE, LANGUAGE_BCP47, NAME, FLAGDEFAULT, FLAGFORCED):
                            keep.append(buf[header:ce])
                    tracks[number] = keep
            elif sid == CLUSTER:
                cluster_time = 0
                for cid, cs, ce in elements(buf, ss, se):
                    if cid == TIMESTAMP:
                        cluster_time = _uint_of(buf, cs, ce)
                    elif cid == SIMPLEBLOCK:
                        blocks.append(_block(buf, cs, ce, cluster_time, None, simple=True))
                    elif cid == BLOCKGROUP:
                        inner, duration, reference = None, None, False
                        for gid, gs, ge in elements(buf, cs, ce):
                            if gid == BLOCK:
                                inner = (gs, ge)
                            elif gid == BLOCKDURATION:
                                duration = _uint_of(buf, gs, ge)
                            elif gid == REFERENCEBLOCK:
                                reference = True
                        if inner:
                            b = _block(buf, inner[0], inner[1], cluster_time, duration, simple=False)
                            b["key"] = not reference
                            blocks.append(b)
    if scale != 1000000:
        for b in blocks:
            b["time"] = b["time"] * scale // 1000000
    return tracks, blocks


def _block(buf, start, end, cluster_time, duration, simple):
    track, p, _ = _read_vint(buf, start, False)
    rel = struct.unpack(">h", buf[p:p + 2])[0]
    flags = buf[p + 2]
    return {"time": cluster_time + rel, "track": track, "key": bool(flags & 0x80) if simple else True,
            "lacing": flags & 0x06, "data": buf[p + 3:end], "duration": duration}


def mux(out_path, inputs, subtitles, title):
    """inputs: [(path, source track number, {"language", "name", "default"})]; subtitles: [({"language", "name",
    "default", "forced"}, [(start ms, end ms, text)])]. Track order in the output: inputs, then subtitles."""
    entries, blocks = [], []
    for number, (path, source_track, meta) in enumerate(inputs, 1):
        tracks, src_blocks = read_mkv(path)
        if source_track not in tracks:
            raise ValueError("%s has no track %d (has %s)" % (path, source_track, sorted(tracks)))
        entries.append(el(TRACKENTRY, el_uint(TRACKNUMBER, number) + el_uint(TRACKUID, 0x1000 + number)
                          + b"".join(tracks[source_track]) + el_str(LANGUAGE, meta["language"])
                          + el_str(NAME, meta["name"]) + el_uint(FLAGDEFAULT, 1 if meta.get("default") else 0)))
        for b in src_blocks:
            if b["track"] == source_track:
                blocks.append(dict(b, track=number, kind="av", order=number))
    for k, (meta, cues) in enumerate(subtitles):
        number = len(inputs) + 1 + k
        entries.append(el(TRACKENTRY, el_uint(TRACKNUMBER, number) + el_uint(TRACKUID, 0x1000 + number)
                          + el_uint(TRACKTYPE, 0x11) + el_str(CODECID, "S_TEXT/UTF8") + el_uint(FLAGLACING, 0)
                          + el_str(LANGUAGE, meta["language"]) + el_str(NAME, meta["name"])
                          + el_uint(FLAGDEFAULT, 1 if meta.get("default") else 0)
                          + el_uint(FLAGFORCED, 1 if meta.get("forced") else 0)))
        for start, stop, text in cues:
            blocks.append({"time": start, "track": number, "key": True, "lacing": 0, "data": text.encode("utf-8"),
                           "duration": stop - start, "kind": "sub", "order": number})
    blocks.sort(key=lambda b: (b["time"], b["order"]))
    end_time = max(b["time"] + (b["duration"] or 0) for b in blocks)

    # clusters: a new one at each video keyframe at least 1 s into the current one, and before 30 s
    clusters, current, cluster_start = [], [], None
    for b in blocks:
        new = cluster_start is None or b["time"] - cluster_start > 30000 or (
            b["order"] == 1 and b["key"] and b["time"] - cluster_start >= 1000)
        if new:
            if current:
                clusters.append((cluster_start, current))
            current, cluster_start = [], b["time"]
        current.append(b)
    if current:
        clusters.append((cluster_start, current))

    info = el(INFO, el_uint(TIMESCALE, 1000000) + el(DURATION, struct.pack(">d", float(end_time)))
              + el_str(MUXINGAPP, "fluentfin-mpv fakeserver") + el_str(WRITINGAPP, "fluentfin-mpv fakeserver")
              + el_str(TITLE, title))
    tracks_el = el(TRACKS, b"".join(entries))
    cluster_bytes, cue_points = [], []

    def seekhead(info_pos, tracks_pos, cues_pos):
        def seek(eid, pos):
            return el(SEEK, el(SEEKID, _id_bytes(eid)) + el_uint(SEEKPOSITION, pos, width=8))
        return el(SEEKHEAD, seek(INFO, info_pos) + seek(TRACKS, tracks_pos) + seek(CUES, cues_pos))

    head_len = len(seekhead(0, 0, 0))
    pos = head_len + len(info) + len(tracks_el)
    for start, members in clusters:
        body = el_uint(TIMESTAMP, start)
        for b in members:
            header = _size_bytes(b["track"]) + struct.pack(">h", b["time"] - start)
            if b["kind"] == "sub":
                body += el(BLOCKGROUP, el(BLOCK, header + b"\x00" + b["data"]) + el_uint(BLOCKDURATION, b["duration"]))
            else:
                flags = (0x80 if b["key"] else 0) | b["lacing"]
                body += el(SIMPLEBLOCK, header + bytes([flags]) + b["data"])
        cluster = el(CLUSTER, body)
        if members[0]["order"] == 1 and members[0]["key"]:
            cue_points.append(el(CUEPOINT, el_uint(CUETIME, start) + el(CUETRACKPOSITIONS, el_uint(CUETRACK, 1)
                                                                          + el_uint(CUECLUSTERPOSITION, pos, width=8))))
        cluster_bytes.append(cluster)
        pos += len(cluster)
    cues = el(CUES, b"".join(cue_points))
    head = seekhead(head_len, head_len + len(info), pos)
    segment_body = head + info + tracks_el + b"".join(cluster_bytes) + cues
    ebml_header = el(EBML, el_uint(0x4286, 1) + el_uint(0x42F7, 1) + el_uint(0x42F2, 4) + el_uint(0x42F3, 8)
                     + el_str(0x4282, "matroska") + el_uint(0x4287, 4) + el_uint(0x4285, 2))
    with open(out_path, "wb") as f:
        f.write(ebml_header + el(SEGMENT, segment_body))
    return end_time


def subtitle_cues(prefix, seconds, every=4.0, show=3.0):
    """A cue every `every` seconds: "<prefix> 12 - 00:44" (so a screenshot tells which track and time it is)."""
    cues, t, n = [], 1.0, 1
    while t + show < seconds:
        start = int(t * 1000)
        cues.append((start, start + int(show * 1000), "%s %d - %02d:%02d" % (prefix, n, int(t) // 60, int(t) % 60)))
        t += every
        n += 1
    return cues


def srt(cues):
    def ts(ms):
        return "%02d:%02d:%02d,%03d" % (ms // 3600000, ms // 60000 % 60, ms // 1000 % 60, ms % 1000)
    return "".join("%d\n%s --> %s\n%s\n\n" % (i, ts(a), ts(b), text) for i, (a, b, text) in enumerate(cues, 1))


def _run(cmd, log):
    with open(log, "ab") as f:
        f.write(("\n$ " + subprocess.list2cmdline(cmd) + "\n").encode("utf-8"))
        f.flush()
        rc = subprocess.call(cmd, stdout=f, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                             creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    return rc


def build(mpv, out, seconds=600):
    """Makes <out>/test.mkv, <out>/test.fr.srt, <out>/trickplay/<n>.jpg and <out>/media.json (what the server
    reports about them). Returns media.json's content. Reuses a complete earlier build with the same length."""
    os.makedirs(out, exist_ok=True)
    meta_path = os.path.join(out, "media.json")
    if os.path.exists(meta_path):
        with open(meta_path, encoding="utf-8") as f:
            meta = json.load(f)
        if meta.get("seconds") == seconds and meta.get("silent") and os.path.exists(meta["path"]):
            return meta
    log = os.path.join(out, "media-build.log")
    work = os.path.join(out, "parts")
    os.makedirs(work, exist_ok=True)
    base = [mpv, "--no-config", "--msg-level=all=warn", "--force-window=no"]

    video = os.path.join(work, "video.mkv")
    codec = None
    for ovc, opts, name in (("h264_mf", "b=1500000", "h264"), ("mpeg4", "b=1500000", "mpeg4")):
        if os.path.exists(video):
            os.remove(video)
        rc = _run(base + ["av://lavfi:testsrc=size=%dx%d:rate=%d:duration=%d" % (VIDEO_W, VIDEO_H, FPS, seconds),
                          "--o=" + video, "--of=matroska", "--ovc=" + ovc, "--ovcopts=" + opts, "--oac=aac"], log)
        if rc == 0 and os.path.exists(video) and os.path.getsize(video) > 10000:
            codec = name
            break
    if not codec:
        raise RuntimeError("mpv could not encode the test video (see %s)" % log)
    audio = []
    for name, oac, rate in (("aac", "aac", "128000"), ("ac3", "ac3", "192000")):
        path = os.path.join(work, "audio-%s.mka" % name)
        # digital silence: no test run makes a sound (the harness's mpv also runs with ao=null)
        rc = _run(base + ["av://lavfi:anullsrc=channel_layout=stereo:sample_rate=48000:duration=%d" % seconds,
                          "--o=" + path, "--of=matroska", "--oac=" + oac, "--oacopts=b=" + rate], log)
        if rc != 0 or not os.path.exists(path):
            raise RuntimeError("mpv could not encode the %s track (see %s)" % (name, log))
        audio.append(path)

    final = os.path.join(out, "test.mkv")
    duration_ms = mux(final, [(video, 1, {"language": "und", "name": "Test pattern", "default": True}),
                              (audio[0], 1, {"language": "eng", "name": "English", "default": True}),
                              (audio[1], 1, {"language": "jpn", "name": "Japanese"})],
                      [({"language": "eng", "name": "English"}, subtitle_cues("[English]", seconds)),
                       ({"language": "spa", "name": "Spanish"}, subtitle_cues("[Espanol]", seconds, every=5.0))],
                      "Nixie harness test video")
    with open(os.path.join(out, "test.fr.srt"), "w", encoding="utf-8", newline="\n") as f:
        f.write(srt(subtitle_cues("[Francais]", seconds, every=6.0)))

    trick = os.path.join(out, "trickplay")
    os.makedirs(trick, exist_ok=True)
    for old in glob.glob(os.path.join(trick, "*.jpg")):
        os.remove(old)
    thumbs = (seconds + TRICK_INTERVAL_S - 1) // TRICK_INTERVAL_S
    rc = _run(base + [final, "--no-audio", "--sid=no",
                      "--vf=lavfi=[fps=1/%d,scale=%d:%d,tile=%dx%d]" % (TRICK_INTERVAL_S, TRICK_W, TRICK_H,
                                                                        TRICK_TILES, TRICK_TILES),
                      "--o=" + os.path.join(trick, "sheet-%d.jpg"), "--of=image2", "--ovc=mjpeg"], log)
    sheets = sorted(glob.glob(os.path.join(trick, "sheet-*.jpg")), key=lambda p: int(p.rsplit("-", 1)[1][:-4]))
    for i, path in enumerate(sheets):
        os.replace(path, os.path.join(trick, "%d.jpg" % i))
    meta = {"seconds": seconds, "silent": True, "path": final, "size": os.path.getsize(final), "duration_ms": duration_ms,
            "video": {"codec": codec, "width": VIDEO_W, "height": VIDEO_H, "fps": FPS, "bitrate": 1500000},
            "audio": [{"codec": "aac", "language": "eng", "title": "English", "channels": 2, "bitrate": 128000},
                      {"codec": "ac3", "language": "jpn", "title": "Japanese", "channels": 2, "bitrate": 192000}],
            "subtitles": [{"codec": "subrip", "language": "eng", "title": "English"},
                          {"codec": "subrip", "language": "spa", "title": "Spanish"}],
            "external_subtitle": {"codec": "srt", "language": "fre", "title": "French",
                                  "path": os.path.join(out, "test.fr.srt")},
            "trickplay": {"width": TRICK_W, "height": TRICK_H, "tile": TRICK_TILES, "interval_ms": TRICK_INTERVAL_S * 1000,
                          "thumbnails": thumbs if sheets else 0, "sheets": len(sheets), "dir": trick,
                          "ok": rc == 0 and bool(sheets)}}
    with open(meta_path, "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=2)
    return meta


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--mpv", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--seconds", type=int, default=600)
    a = ap.parse_args()
    print(json.dumps(build(a.mpv, os.path.abspath(a.out), a.seconds), indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
