# Nixie for TV on Amazon Fire TV: the release plan (sideload only, tested by emulation)

**The owner's decisions (2026-11-21):**
- "also plan amazon fire tv release";
- "lets just emulate it i dont want to buy one";
- "we'll do this sideload only on firetvb" (Fire TV).

So Fire TV gets **the same GitHub APK as Google TV, with its updater, installed by sideloading**. There is no Amazon
Appstore build, listing, developer account or review, and no Fire TV device: testing is by emulation, with what an
emulator can't prove listed below. This rests on two research passes of Amazon's own developer docs and dated news, on
2026-11-21. It comes **after TV 1.0** on Google TV.

## The short version
- **One codebase, one repository** (`mbustos27/nixie-tv`, created at TV 1.0). If F0 needs no Fire-only change in the
  build, it's **one APK** for both.
- **Fire OS only: Fire OS 7, 8, and the TVs on 14 and 16.** Amazon's **Vega OS** sticks run no Android apps and allow no
  sideloading, so Nixie can't run on them. Vega apps are React Native or web only: a different app, not planned.
- **Installing it:** Fire TV's Developer options, then "Install unknown apps" for the Downloader app, then the GitHub
  release's APK URL. After that, Nixie's own updater keeps it current, as on Google TV.
- **Testing:** Android TV emulators at Fire OS 7's and 8's Android levels, with a debug switch that makes Nixie behave as
  on Fire OS. There's no Fire OS emulator (below).
- **The owner's part:** nothing to buy and no account. Optionally, someone with a Fire OS stick can try the APK and say
  what they see.

## What Nixie already has
- `minSdk = 28` (Fire OS 7 is API 28, Fire OS 8 is API 29–30) and `targetSdk = 37`.
- `armeabi-v7a` in the ABI filters. Every Fire TV runs a 32-bit userspace, so the 32-bit FFmpeg libraries ship
  ([AFTVnews, 2024-09-11](https://www.aftvnews.com/?p=43549)).
- `android.software.leanback` and `android.hardware.touchscreen` in the manifest (touchscreen must be
  `required="false"`; check it at F0).
- No Google services: no Play Billing, no Play Core updates, no Cast receiver, no Firebase messaging. Nixie's updater
  and sign-in need none of them.
- The FFmpeg audio decoder. Fire TV passes through only Dolby (DD, DD+, Atmos, detected with `EXTRA_ENCODINGS`). DTS,
  DTS-HD and TrueHD passthrough is confirmed only on the Cube 3rd gen, so decoding stays
  ([Amazon, 2025-03-20](https://developer.amazon.com/docs/fire-tv/dolby-integration-guidelines.html);
  [AFTVnews, 2022-12-13](https://www.aftvnews.com/fire-tv-cube-gains-improved-upscaling-as-well-as-dts-dts-hd-and-dolby-true-hd-audio-passthrough-in-latest-software-update/)).

## Devices (from [Amazon's Fire OS overview, 2026-10-16](https://developer.amazon.com/docs/fire-tv/fire-os-overview.html))
| OS | Devices | Nixie |
|---|---|---|
| Fire OS 7 (API 28) | Stick 3rd gen, Stick Lite, Stick HD 2024, 4K Max 1st gen, Cube 2nd and 3rd gen | Yes |
| Fire OS 8 (API 29–30) | Stick 4K 2nd gen (sold as the 4K Plus), 4K Max 2nd gen | Yes |
| Fire OS 14 and 16 | Mostly Fire TV televisions | Yes. Fire OS 16 needs 16 KB-aligned native libraries ([Amazon, 2026-06-30](https://developer.amazon.com/docs/fire-tv/fire-os-16.html)) |
| **Vega OS** | Stick 4K Select (2025), Stick HD (2026), Stick 4K 3rd gen (2026) | **No**: no APKs, no sideloading ([AFTVnews, 2026-10-05](https://www.aftvnews.com/these-are-the-fire-tvs-that-dont-support-sideloading-or-downloader-due-to-vega-os-replacing-fire-os/)) |

Amazon says future sticks will be Vega. Fire TV support is for the Fire OS devices people already own, and the README
says so plainly.

## How it's tested without a device
- **No Fire OS emulator exists.** Amazon says Fire OS compatibility testing "requires an actual device"
  ([Amazon, 2025-10-09](https://developer.amazon.com/docs/fire-tv/fire-os-7.html)). Its emulator guidance "cannot be
  used to simulate the Fire TV" ([Amazon, 2025-10-09](https://developer.amazon.com/docs/fire-tablets/ft-testing-without-an-amazon-device.html)).
  The old Kindle emulator page is gone. Vega's virtual device runs only Vega packages.
- **The stand-in is Amazon's own advice for Fire OS 8:** a stock Android TV image at API 30
  ([Amazon, 2023-09-20](https://developer.amazon.com/apps-and-games/blogs/2023/09/fireos8-on-new-fire-devices)).
  - Two AVDs: Android TV **API 28** (Fire OS 7) and **API 30** (Fire OS 8), both without Google APIs.
  - Google's images at those levels are **x86 only** (ARM images start at API 31, and there is no 32-bit ARM image)
    ([Google's image list, read 2026-11-21](https://dl.google.com/android/repository/sys-img/android-tv/sys-img2-3.xml)),
    so the emulator runs the x86 build of the FFmpeg libraries, not the 32-bit ARM ones Fire TV uses.
- **A debug-build switch, "Behave as Fire OS":** it makes Nixie take its Fire OS paths (Watch Next hidden, Fire's
  wording) on any AVD. Faking the system feature `amazon.hardware.fire_tv` on an emulator needs a writable system image
  and is unreliable ([Chromium's emulator notes](https://chromium.googlesource.com/chromium/src/+show/HEAD/docs/android_emulator.md)).
  The real detection uses that feature
  ([Amazon, 2026-05-20](https://developer.amazon.com/docs/device-specs/identify-fire-tv-devices.html)), with a test
  that feeds it both ways.
- **Not used:** Amazon's free cloud Fire TVs ("Live Device Interaction – Virtual Devices", 60-minute sessions, Fire OS
  5–8, APK upload and an adb shell). They need an Amazon developer-console sign-in, and the owner chose no account. They
  stay available later if that changes; even they don't fully support audio
  ([Amazon FAQ, 2026-06-30](https://developer.amazon.com/ja/docs/app-testing/appstore-quality-central-faq.html)).

## What emulation can't prove, and how each risk is handled
| Not provable without a device | How it's handled |
|---|---|
| **HDMI audio passthrough** (Dolby to an AVR) and DTS or TrueHD decode on the box | Nixie decodes DTS and TrueHD with FFmpeg everywhere, the same code as on the 4K Google TV test box (which can't pass DTS through either), and asks Android whether passthrough is supported (`EXTRA_ENCODINGS`) instead of assuming it. **Known gap in the README:** "Dolby passthrough on Fire TV hasn't been tested on a device." |
| **HDR and Dolby Vision output** | Media3 picks the output from the display's reported capabilities; the same code is checked on the 4K Google TV test box. Known gap, as above. |
| **Refresh-rate switching** | The standard `Display.Mode` / `preferredDisplayModeId` API, which Fire OS honours from Fire OS 6 ([Amazon, 2020-10-29](https://developer.amazon.com/docs/fire-tv/4k-apis-for-hdmi-mode-switch.html)), tested on the 4K Google TV test box and the emulator's mode list. Off by default if it misbehaves: one setting. Known gap. |
| **Real-box speed and memory** (32-bit ARM FFmpeg, a 1–2 GB stick) | The 4K Google TV test box is the nearest real hardware (also 32-bit ARM userspace, 2 GB); its frame and memory reads stand in, and the emulator gets a 1 GB AVD for memory pressure. Known gap. |
| **Amazon's launcher** (Fire's home, its recents, Home and Back) | Watch Next is hidden on Fire OS, since Fire's own row needs Amazon's catalog integration and a partnership ([Amazon, 2026-07-31](https://www.developer.amazon.com/docs/fire-tv/get-started-with-firetv-integration-sdk.html)). Home and Back follow Android's rules, tested on the AVDs. Known gap: how Fire's home shows the banner. |
| **The sideload and updater flow on Fire OS** (Downloader, "Install unknown apps" for Nixie itself) | The updater uses Android's standard package installer and asks for the install permission the standard way, the same code as on Google TV. It's tested on the API 28 and 30 AVDs, including a denied permission. The README walks through Fire's own screens, from Amazon's and AFTVnews' published steps (Developer options: About, seven times; a Fire OS bug hid Downloader there until a fix from 2026-09-16, [AFTVnews, 2026-08-26](https://www.aftvnews.com/?p=46742)). Known gap. |
| **Remote buttons and the screensaver** | Fire's remote sends standard key codes (media keys included); keep-screen-on during playback is the same as on Google TV. Tested on the AVDs. Known gap. |

The release notes and the README list these as **"Fire TV: built for, not yet tried on a device"** until someone with
a Fire OS stick reports back. A user report of a problem on Fire TV is triaged like any other.

## Phases
### F0: compatibility (about half a day, after TV 1.0; agents)
1. **Fire OS detection** (`amazon.hardware.fire_tv`) and the debug switch "Behave as Fire OS":
   - Watch Next hidden on Fire OS, and its setting's line says why;
   - a test for both values.
2. **Manifest:** touchscreen `required="false"`, leanback, the banner.
3. **Native libraries 16 KB-aligned** (Fire OS 16, and Android 15 or later on Google TV too). The x86 FFmpeg build for
   the emulators.
4. **Refresh rate matching** through `preferredDisplayModeId`, if Glacier's player doesn't already, behind a setting.
   Tunnelling stays off by default: the Cube 2nd gen tunnels only through secure decoders
   ([Amazon, 2026-06-09](https://developer.amazon.com/docs/fire-tv/4k-tunnel-mode-playback.html)).
5. **Amazon's test criteria as tests,** because they're good rules even without a review
   ([Amazon, 2026-11-16](https://developer.amazon.com/docs/app-testing/test-criteria.html)):
   - no screensaver during playback;
   - Home saves state;
   - exit in under 2 seconds;
   - media keys work;
   - Back never loops.
6. **Two AVDs, API 28 and API 30** (Android TV, no Google APIs, x86, 1 GB and 2 GB):
   - the session checklist of `TV-SESSION.md` against the fake server, in "Behave as Fire OS" mode;
   - the updater installing a newer test build, and refusing when the install permission is denied.

### F1: the sideload release (with TV 1.0.x)
- The same APK on the GitHub release, plus a README section "On Fire TV":
  - the Fire OS devices it's for, and the Vega ones it can't run on;
  - Developer options, Downloader, and the release's download URL;
  - the known gaps above.
- The updater works as on Google TV.
- If F0 needed a Fire-only build after all, the Fire APK is a second asset with its own name. Same application id,
  same signing key, so it updates either way.

## What the owner does
Nothing to buy and no account. When F1 ships, a short note says which devices it's for, and that it hasn't been tried
on a device yet.

## Risks
- **Amazon may move every stick to Vega.** Fire TV support then means existing devices only. That's acceptable for a
  free app; no Vega port is planned.
- **A Fire-only bug that only a device shows** (from the table above): handled when a user reports it, since no
  device is on hand.
