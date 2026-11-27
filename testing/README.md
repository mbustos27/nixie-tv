# Test servers and device answers

Automated tests never sign in to a real server. They run against a fake Jellyfin server on 127.0.0.1, with the
answers real boxes give recorded as files.

## `fakeserver/`: the TV app's test server

`tvserver.py` runs the Windows app's fake Jellyfin server with a server profile from `profiles.json` laid over it.
The fake is nixie-tv's own pinned copy in `fakeserver/desktop/` (`SOURCE.md` names the fluentfin-mpv commit it came
from; never edited here), so a change in the desktop repository cannot break the TV's tests or scripts (it did on
2026-11-21, bug hunt M5); a test checks that every method `tvserver.py` overrides keeps the desktop's signature. The profiles:

| Profile | Version | Token rules | GET `/QuickConnect/Initiate` | Use |
|---|---|---|---|---|
| `jellyfin-12` | 12.4.0 | strict: only `Authorization: MediaBrowser Token=...` (and `ApiKey`) | 405 | the default |
| `jellyfin-10.11` | 10.11.11 | legacy: `X-Emby-*` and `api_key` too | works | below the 12.0 minimum |
| `jellyfin-10.10` | 10.10.7 | legacy | works | below the 12.0 minimum |

The app supports Jellyfin 12.0 and newer (`MinimumServerVersion` in `core/jellyfin`'s `ServerInfo.kt`, Glacier's rule
with Jellyfin SDK 1.9.4): an older server shows as not supported and connects only after **Connect anyway**. The two
legacy profiles come from the plan's SDK 1.8.12, which served 10.11 too; only this folder's own tests use them, and no
app test or gate check runs against an older server.

All three keep **one token per DeviceId**: a sign-in revokes the earlier token issued to the same DeviceId, and a
Quick Connect token belongs to the DeviceId sent with Initiate. This is why Nixie mints a per-attempt DeviceId.
Every `DeviceProfile` posted with PlaybackInfo is written to `<out>/device-profiles.jsonl`.
Over the desktop's routes it adds what Jellyfin 12 answers and the desktop's fake lacks: `GET /Items/Suggestions`
(the search page's suggestions before anything is typed). `serve_synthetic.py <dir>` serves the `jellyfin-12` profile on
synthetic media (no mpv) and writes `<dir>/ready.json`; the app's `SearchServerTest` runs Nixie's search through the SDK
against it (signing in with Quick Connect and the harness's approval, never the credentials file).
`NIXIE_TV_LOCAL_TRAILER=1` gives the showcase movie a local trailer file (its own test video, "Teaser"), so the trailer
player can be checked on the emulator without YouTube; off by default.
`--refuse-transcodes` makes it a server where the test user may not transcode: a PlaybackInfo below a file's bitrate
gets that file with nothing allowed and `ErrorCode: NoCompatibleStream`, as a shared server answers; `--bitrate-test-kbps
N` slows its link test (`/Playback/BitrateTest`) to N kbit/s and nothing else. The basics gate's `RefusedTranscodeGate`
runs on both (1.0.1); `GET /__harness/tv` names the server's, the test user's and the showcase movie's ids for it.

```powershell
# the pinned desktop fake; --desktop-fakeserver DIR (or NIXIE_DESKTOP_FAKESERVER) tries another copy by hand
python -B testing\fakeserver\tvserver.py --profile jellyfin-12 --out <dir> --media <dir> --ready-file <file>
python -B -m unittest discover -s testing\fakeserver -p "test_*.py"
```

`--media` is a folder built by the desktop's `media.py` (silent test video, two audio and three subtitle tracks);
the tests use a synthetic one. Always run Python with `-B`, so nothing is written into the desktop repository.
The emulator reaches the server at `10.0.2.2:<port>`; a real TV through `adb reverse tcp:<port> tcp:<port>`, so the
server never listens on the network.

## `devices/`: what each box answers

One file per device in the `nixie-device-answers/1` shape: ABIs, memory class and the low-RAM flag, display modes
and HDR types, which compressed audio formats the HDMI output takes directly, and every decoder `MediaCodecList`
reports with its profiles, levels and sizes. A debug build writes the same file on any box
(**Save device facts**, then `adb pull /sdcard/Android/data/io.github.mbustos27.nixie.tv.debug/files/device-facts.json`).

| File | Source |
|---|---|
| `emulator-google-tv-api34-x86.json` | measured on the Nixie_TV_API34 emulator, 2026-11-21 |
| `google-tv-4k-box.json` | measured on a 4K Google TV box (Amlogic, 32-bit userspace, about 2 GB), 2026-11-21 |
| `chromecast-google-tv-4k.expected.json` | the plan's expectations, unverified: replace with a measured file from that box |
| `chromecast-google-tv-hd.expected.json` | the same |
| `google-tv-streamer.expected.json` | the same |

What the emulator measured, for the tests to come:

- ABIs `x86`, `armeabi-v7a`, `armeabi` (no 64-bit): debug builds must keep x86. The Jellyfin FFmpeg decoder and
  `ass-media` ship x86 libraries, so libass runs on the emulator.
- Hardware decoders (goldfish): H.264 up to 4096 x 4096 (level 5.2), HEVC **Main only** (no Main 10) up to level
  5.2, VP9 to 3840 x 2160. AV1 is software only, up to 2048 x 2048. No HDR on the display.
- No AC-3, E-AC-3, DTS or TrueHD decoder and no passthrough: those tracks play only through the FFmpeg decoder.

### `emulator-google-tv-api34-x86.device-profile.json`

The DeviceProfile Nixie should send on the emulator: the target for phase 2's profile builder, written by hand from
the measured answers and the plan's rules. The decisions in it, for phase 2 to confirm or change with the code:

- Direct play first: MP4, MKV, WebM, MOV and TS with H.264, HEVC, VP8, VP9 and AV1, and every audio codec the
  platform or the FFmpeg decoder handles (AC-3, E-AC-3, DTS and TrueHD decoded to PCM, which assumes Jellyfin's
  FFmpeg build includes those decoders: check in phase 2).
- Limits per codec from the decoders: HEVC Main only and level 156 (5.2), H.264 8-bit to level 52, AV1 to
  2048 x 2048, and every video codec SDR only, because the display reports no HDR type.
- Transcoding: HLS with fMP4 segments first (it carries E-AC-3, FLAC and Opus), TS as the fallback; 6 channels.
- Subtitles, as the plan sets them with libass on: SRT, SubRip, TTML and WebVTT Embed and External; ASS and SSA
  Embed and External (Encode when libass is off); PGS, DVD and DVB Embed with Encode as the fallback.
- 120 Mbit/s until the user's bitrate setting exists.

## `licences/`: REUSE.toml

`test_reuse_toml.py` resolves REUSE.toml as REUSE does (the last matching table with `precedence = "override"`
decides) and checks the fork's copyright lines: Glacier's and Nixie's on files that began as Glacier's (the rebrand's
strings, the theme modules' stand-ins, any file that says it was copied from Glacier), Nixie's alone on files Nixie
wrote. It stands in for `reuse lint` where the reuse tool is not installed:
`python -B -m unittest discover -s testing/licences -p "test_*.py"`.
