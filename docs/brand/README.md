# Nixie for TV's brand files

- `banner-dark.svg`: the README banner at dusk, for GitHub's dark mode (1280 × 320).
- `banner-light.svg`: the same banner at noon, for the light mode.
- `social-preview.svg`: the repository's link preview (1280 × 640), dusk.
- `social-preview.png`: that preview rendered, for GitHub's Settings › General › Social preview (it takes PNG, JPG
  or GIF, not SVG).

## Where they come from

They are [Nixie for Windows' brand files](https://github.com/mbustos27/nixie/tree/main/docs/brand), by the same
Nixie contributors, made into Nixie for TV's with a "TV" badge and a TV set. Like those, they are drawn by hand from
the Windows app's own shapes and colours, with no stock art. The files named below are in the Windows repository.

- **The dusk scene** follows [`DuskScene.cs`][dusk]: the sky's colours, the striped sun with its three rings, the
  curved four-point sparkles, and the hills and saguaro from [`AmbientBackdropView.xaml`][hills]. The sky is one
  smooth gradient rather than the app's flat bands, because at link-card size bands read as banding; the social
  preview has no scanlines for the same reason. Nixie for TV's art adds an old TV set with two antennas on the hills
  beside the saguaro.
- **The wordmark** is "NIXIE" in [Monoton](https://fonts.google.com/specimen/Monoton) (by Vernon Adams, SIL Open Font
  License 1.1; the Windows repository ships the font in [`ui/FluentFin/Assets/Fonts`][fonts]), turned into outlines
  and letter-spaced 0.06 em as [`AppWordmark.cs`][wordmark] sets it. Lit like the sign-in card's: the tube `#FFC4A6`
  with a hot-pink glow `#F96985` and a wide nixie-orange halo `#FF7A50` at dusk, crimson `#BC1E47` with one faint
  crimson glow at noon. This repository holds only the outlines of N, I, X and E, not the font.
- **The "TV" badge** beside the E is real text, bold and letter-spaced, in a rounded frame, lit the same way as
  NIXIE.
- **The glass card** uses the panel colours in [`Glass.xaml`][glass]: the plum tint `#34181D` (noon: `#FFF3F1`), a
  lit top edge, and the text colours `#F8EFF0` / `#D3BFC1` (noon: `#2D0F16` / `#633A41`).
- **The text** is real text in Segoe UI Variable, with system fonts after it on other systems. The banners say "A
  Jellyfin app for Android TV / and Google TV, built for the remote"; the social preview "A Jellyfin app for Android
  TV / and Google TV", which still reads when a chat app shows the card about 400 px wide. Check a new version at that
  size before uploading it.

The social preview differs from the Windows one where the badge needs room: NIXIE is at scale 1.6 instead of 1.96,
with the badge scaled to match; the card is 776 × 396 instead of 782 × 352, for two lines of text; and the saguaro
stands further right, with the TV set (1.5 times the banners' size) between it and the card.

[dusk]: https://github.com/mbustos27/nixie/blob/main/ui/FluentFin.Core/Theming/DuskScene.cs
[hills]: https://github.com/mbustos27/nixie/blob/main/ui/FluentFin/Controls/AmbientBackdropView.xaml
[fonts]: https://github.com/mbustos27/nixie/tree/main/ui/FluentFin/Assets/Fonts
[wordmark]: https://github.com/mbustos27/nixie/blob/main/ui/FluentFin/Controls/AppWordmark.cs
[glass]: https://github.com/mbustos27/nixie/blob/main/ui/FluentFin/Styles/Glass.xaml

## The palette

The same as the Windows app's:

- **Sky, top to horizon:** dusk `#500723` `#BC1E47` `#E6385F` `#EC6A79` `#F96985` `#F7AA9C`; noon `#FFF4EE`
  `#F9C9BF` `#F5A8A5` `#F7AA9C` `#F38C7D` `#EC6A79`.
- **Sun, top to bottom:** dusk `#F5A8A5` to `#F96985`; noon `#FFF4EE` to `#EC6A79`.
- **Hills, far to near:** dusk `#C73153` `#BC1E47` `#500723`; noon `#F0849A` `#BC1E47` `#500723`.
- **Saguaro and TV set:** `#500723`.
- **Accent:** dusk `#F96985`; noon `#BC1E47`.

## Credits and licences

The art is Nixie's own work, 2026 Nixie contributors, under the GPL-3.0-or-later like the rest of Nixie's files. The
NIXIE letters are Monoton's outlines, copyright 2011 Vernon Adams, under the SIL Open Font License 1.1
([LICENSES/OFL-1.1.txt](../../LICENSES/OFL-1.1.txt)). [REUSE.toml](../../REUSE.toml) records both for these files.

## Changing them

The SVGs are plain text: edit the tagline in its `<text>` elements, and keep the dark and light banners in step. The
NIXIE letters are copied from the Windows banners; to set other letters in Monoton, export them from the font (WPF's
`GlyphTypeface.GetGlyphOutline` gives the path data).

To render `social-preview.png` again, use Edge's headless mode (nothing to download: `msedge.exe` is in
`C:\Program Files (x86)\Microsoft\EdgeCore\<version>` when the Edge browser itself is not installed), with a scratch
folder outside the repository:

```powershell
& <path to msedge.exe> --headless --disable-gpu --no-first-run --hide-scrollbars --force-device-scale-factor=1 `
  --user-data-dir=<scratch>\edge-profile --window-size=1280,640 --screenshot=<scratch>\social-preview.png `
  file:///<repository>/docs/brand/social-preview.svg
```

To see it at chat-card size, put `<img src="social-preview.png" width="400">` in a page in the same scratch folder
and screenshot that the same way with `--window-size=400,200`. Then save the PNG over `docs/brand/social-preview.png`
(keep it under 1 MB; it is about 300 KB) and upload it again on GitHub: **Settings › General › Social preview › Edit ›
Upload an image**.
