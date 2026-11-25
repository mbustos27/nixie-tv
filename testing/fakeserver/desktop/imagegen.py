"""Placeholder artwork for the fake Jellyfin server: PNGs of real sizes with the item's name drawn in, and blurhashes.

Standard library only (zlib + struct). Each image is a two-colour gradient picked from the item id, a darker band and
the title in a 5x7 bitmap font, so a screenshot shows which item a card is. Rendering works on whole rows (bytes
repetition, a cache of shaded rows), so a 1000x1500 poster takes a few tens of milliseconds.
"""

import functools
import hashlib
import math
import os
import struct
import zlib

# 5x7 glyphs, one string per row, '#' = ink. Lower case is drawn as upper case.
_FONT = {
    "A": [".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"],
    "B": ["####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."],
    "C": [".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."],
    "D": ["####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."],
    "E": ["#####", "#....", "#....", "####.", "#....", "#....", "#####"],
    "F": ["#####", "#....", "#....", "####.", "#....", "#....", "#...."],
    "G": [".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".####"],
    "H": ["#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"],
    "I": [".###.", "..#..", "..#..", "..#..", "..#..", "..#..", ".###."],
    "J": ["..###", "...#.", "...#.", "...#.", "...#.", "#..#.", ".##.."],
    "K": ["#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"],
    "L": ["#....", "#....", "#....", "#....", "#....", "#....", "#####"],
    "M": ["#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"],
    "N": ["#...#", "#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#"],
    "O": [".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."],
    "P": ["####.", "#...#", "#...#", "####.", "#....", "#....", "#...."],
    "Q": [".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#"],
    "R": ["####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"],
    "S": [".####", "#....", "#....", ".###.", "....#", "....#", "####."],
    "T": ["#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."],
    "U": ["#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."],
    "V": ["#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#.."],
    "W": ["#...#", "#...#", "#...#", "#.#.#", "#.#.#", "#.#.#", ".#.#."],
    "X": ["#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#"],
    "Y": ["#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."],
    "Z": ["#####", "....#", "...#.", "..#..", ".#...", "#....", "#####"],
    "0": [".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."],
    "1": ["..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."],
    "2": [".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"],
    "3": ["####.", "....#", "....#", ".###.", "....#", "....#", "####."],
    "4": ["...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."],
    "5": ["#####", "#....", "####.", "....#", "....#", "#...#", ".###."],
    "6": [".###.", "#....", "#....", "####.", "#...#", "#...#", ".###."],
    "7": ["#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."],
    "8": [".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."],
    "9": [".###.", "#...#", "#...#", ".####", "....#", "....#", ".###."],
    " ": [".....", ".....", ".....", ".....", ".....", ".....", "....."],
    ".": [".....", ".....", ".....", ".....", ".....", ".##..", ".##.."],
    ",": [".....", ".....", ".....", ".....", ".##..", "..#..", ".#..."],
    ":": [".....", ".##..", ".##..", ".....", ".##..", ".##..", "....."],
    "-": [".....", ".....", ".....", "#####", ".....", ".....", "....."],
    "'": ["..#..", "..#..", ".#...", ".....", ".....", ".....", "....."],
    "&": [".##..", "#..#.", "#.#..", ".#...", "#.#.#", "#..#.", ".##.#"],
    "!": ["..#..", "..#..", "..#..", "..#..", "..#..", ".....", "..#.."],
    "?": [".###.", "#...#", "....#", "...#.", "..#..", ".....", "..#.."],
    "(": ["...#.", "..#..", ".#...", ".#...", ".#...", "..#..", "...#."],
    ")": [".#...", "..#..", "...#.", "...#.", "...#.", "..#..", ".#..."],
    "/": ["....#", "...#.", "...#.", "..#..", ".#...", ".#...", "#...."],
}
_FALLBACK = _FONT["?"]
# the columns that have ink, per glyph row, as (start, length) runs
_RUNS = {}
for _ch, _rows in _FONT.items():
    _RUNS[_ch] = []
    for _row in _rows:
        runs, x = [], 0
        while x < 5:
            if _row[x] == "#":
                start = x
                while x < 5 and _row[x] == "#":
                    x += 1
                runs.append((start, x - start))
            else:
                x += 1
        _RUNS[_ch].append(runs)

_B83 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~"


# Nixie 1.4.0 (the contrast floor): every image one flat colour, the worst case any artwork can be under a label drawn on
# it: FAKESERVER_ART=white or black (or POST /__harness/art {"art": ...} while running); unset or "": the items' own art.
# Logos keep their title (title art, not a backdrop).
FLAT_ART = {"white": (255, 255, 255), "black": (0, 0, 0)}
ART = (os.environ.get("FAKESERVER_ART") or "").strip().lower()
if ART not in FLAT_ART:
    ART = ""


def set_art(art):
    """The art from now on ("white", "black", or "" for the items' own); a ValueError for anything else."""
    global ART
    art = (art or "").strip().lower()
    if art and art not in FLAT_ART:
        raise ValueError("art: white, black or empty")
    ART = art
    return ART


def art_key():
    """What the image tags carry so that a client asks again when the art changes: "" for the items' own art."""
    return ":" + ART if ART else ""


def palette(key):
    """Two colours (top, bottom) and an ink colour, the same for every image of one item."""
    if ART:
        return FLAT_ART[ART], FLAT_ART[ART], FLAT_ART[ART]
    h = hashlib.md5(key.encode("utf-8")).digest()
    hue = h[0] / 255.0
    top = _hsv(hue, 0.55 + h[1] / 255.0 * 0.3, 0.55 + h[2] / 255.0 * 0.3)
    bottom = _hsv((hue + 0.08 + h[3] / 255.0 * 0.15) % 1.0, 0.7, 0.18 + h[4] / 255.0 * 0.15)
    return top, bottom, (245, 245, 240)


def _hsv(h, s, v):
    i = int(h * 6) % 6
    f = h * 6 - int(h * 6)
    p, q, t = v * (1 - s), v * (1 - f * s), v * (1 - (1 - f) * s)
    r, g, b = [(v, t, p), (q, v, p), (p, v, t), (p, q, v), (t, p, v), (v, p, q)][i]
    return int(r * 255), int(g * 255), int(b * 255)


def _mix(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def _wrap(text, max_chars):
    words, lines, line = text.upper().split(), [], ""
    for word in words:
        word = word[:max_chars]
        if not line:
            line = word
        elif len(line) + 1 + len(word) <= max_chars:
            line += " " + word
        else:
            lines.append(line)
            line = word
    if line:
        lines.append(line)
    return lines or [""]


def render(key, title, subtitle, width, height, alpha=False):
    """PNG bytes, width x height: the item's gradient, a band in the lower part, the title (and a smaller subtitle).
    alpha: RGBA with a transparent background (logos)."""
    width, height = max(1, int(width)), max(1, int(height))
    if ART and not alpha:
        return _png(width, height, (b"\x00" + bytes(FLAT_ART[ART]) * width) * height, 3)
    top, bottom, ink = palette(key)
    if alpha:
        ink = (245, 245, 240)   # a logo keeps its light title whatever the art (the flat art is the pictures')
    channels = 4 if alpha else 3
    # text layout: the scale makes ~14 characters fill the width, at most 4 lines, and fits the height
    scale = max(1, min(width // (6 * 14), height // (8 * 9)))
    max_chars = max(1, (width - 2 * scale * 3) // (6 * scale))
    lines = _wrap(title, max_chars)[:4]
    sub_scale = max(1, scale // 2)
    sub_lines = _wrap(subtitle, max(1, (width - 2 * sub_scale * 3) // (6 * sub_scale)))[:2] if subtitle else []
    text_h = len(lines) * 9 * scale + (len(sub_lines) * 9 * sub_scale + scale * 4 if sub_lines else 0)
    band_top = max(0, height - text_h - 6 * scale) if not alpha else (height - text_h) // 2
    text_top = band_top + 3 * scale if not alpha else band_top

    shades = 48
    cache = {}

    def bg_row(y):
        if alpha:
            return bytearray(width * 4)
        t = y / max(1, height - 1)
        level = int(t * (shades - 1))
        dark = y >= band_top
        k = (level, dark)
        row = cache.get(k)
        if row is None:
            c = _mix(top, bottom, level / (shades - 1))
            if dark:
                c = tuple(int(v * 0.45) for v in c)
            row = bytes(c) * width
            cache[k] = row
        return bytearray(row)

    ink_px = bytes(ink) + (b"\xff" if alpha else b"")
    glyph_rows = {}   # y -> list of (x, run length in pixels)
    y = text_top
    for size, texts in ((scale, lines), (sub_scale, sub_lines)):
        if size == sub_scale and texts:
            y += scale * 2
        for text in texts:
            x0 = (width - len(text) * 6 * size + size) // 2
            for gy in range(7):
                runs = []
                for ci, ch in enumerate(text):
                    for start, length in _RUNS.get(ch, _RUNS["?"])[gy]:
                        runs.append((x0 + (ci * 6 + start) * size, length * size))
                for dy in range(size):
                    glyph_rows.setdefault(y + gy * size + dy, []).extend(runs)
            y += 9 * size

    raw = bytearray()
    for yy in range(height):
        row = bg_row(yy)
        for x, n in glyph_rows.get(yy, ()):
            x1, x2 = max(0, x), min(width, x + n)
            if x2 > x1:
                row[x1 * channels:x2 * channels] = ink_px * (x2 - x1)
        raw.append(0)
        raw += row
    return _png(width, height, bytes(raw), channels)


def _png(width, height, raw, channels):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    colour_type = 6 if channels == 4 else 2
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, colour_type, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 1)) + chunk(b"IEND", b""))


def png_size(data):
    """(width, height) of a PNG, for tests."""
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    return struct.unpack(">II", data[16:24])


def blurhash(key, x_components=1, y_components=3):
    """A real blurhash of the item's gradient (what a client shows while the image loads); the gradient is vertical,
    so 1x3 components hold it."""
    top, bottom, _ = palette(key)
    w, h = (1 if x_components == 1 else 8), 12   # one column holds a vertical gradient exactly

    def lin(v):
        v /= 255.0
        return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4

    pixels = [[[lin(c) for c in _mix(top, bottom, y / (h - 1))] for _ in range(w)] for y in range(h)]
    factors = []
    for j in range(y_components):
        for i in range(x_components):
            norm = 1.0 if i == 0 and j == 0 else 2.0
            r = g = b = 0.0
            for y in range(h):
                for x in range(w):
                    basis = norm * math.cos(math.pi * i * x / w) * math.cos(math.pi * j * y / h)
                    p = pixels[y][x]
                    r += basis * p[0]
                    g += basis * p[1]
                    b += basis * p[2]
            scale = 1.0 / (w * h)
            factors.append((r * scale, g * scale, b * scale))

    def srgb(v):
        v = max(0.0, min(1.0, v))
        return int(round((v * 12.92 if v <= 0.0031308 else 1.055 * v ** (1 / 2.4) - 0.055) * 255))

    def b83(value, length):
        return "".join(_B83[(value // 83 ** (length - 1 - k)) % 83] for k in range(length))

    def sign_pow(v, e):
        return math.copysign(abs(v) ** e, v)

    dc, ac = factors[0], factors[1:]
    out = b83((x_components - 1) + (y_components - 1) * 9, 1)
    if ac:
        actual_max = max(abs(c) for f in ac for c in f)
        quantised = max(0, min(82, int(actual_max * 166 - 0.5)))
        max_value = (quantised + 1) / 166
        out += b83(quantised, 1)
    else:
        max_value = 1
        out += b83(0, 1)
    out += b83((srgb(dc[0]) << 16) + (srgb(dc[1]) << 8) + srgb(dc[2]), 4)
    for f in ac:
        q = [max(0, min(18, int(math.floor(sign_pow(c / max_value, 0.5) * 9 + 9.5)))) for c in f]
        out += b83(q[0] * 19 * 19 + q[1] * 19 + q[2], 2)
    return out


def blurhash_cached(key):
    return _blurhash_cached(key, ART)


@functools.lru_cache(maxsize=65536)
def _blurhash_cached(key, art):
    return blurhash(key)
