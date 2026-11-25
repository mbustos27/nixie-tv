# Scripts

Helper scripts for building and releasing. Each script finds the repository root from its own location, so you can run
it from any working directory.

Nixie for TV is released on GitHub only. Glacier's scripts for its store listings (Google Play, Amazon), its website
and its promo film are not part of this repository; they live in
[Glacier's repository](https://github.com/Glacier-Jellyfin/glacier-androidtv).

## ffmpeg

| Script | What it does | Run by |
|---|---|---|
| `build-ffmpeg.sh` | Builds FFmpeg's audio decoders for Android and installs their headers and static libraries where `core/ffmpeg` expects them. | CI, through the FFmpeg action (`.github/actions/ffmpeg`); by hand on Linux, macOS or WSL |
| `check-ffmpeg-apk.sh` | Fails unless the APK contains the FFmpeg decoder for every ABI. | `ci.yml` |

## release

| Script | What it does | Run by |
|---|---|---|
| `release-notes.sh` | Prints the changelog section of one version, with the German and Spanish sections below it when they exist. | By hand |

## media

| Script | What it does | Run by |
|---|---|---|
| `make-playback-samples.py` | Makes the sample files for the playback check and for the Dolby Vision and IEC 61937 tests. | By hand (needs ffmpeg and dovi_tool) |
