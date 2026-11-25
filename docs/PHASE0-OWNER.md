# Phase 0: the owner's steps

About 30 minutes, in this order. Agents did everything else (the project, the debug build with the Watch Next test,
the release checker, the test server). These steps need you because they involve the server you use, your TV and the
release key, which no agent creates, sees or holds. With step 1 already done, it's about 25 minutes.

| # | Step | Time | Needs |
|---|---|---|---|
| 1 | Nothing: the server's version is already known | 0 min | |
| 2 | Create the release key, record its fingerprint, keep the password | 5 min | this PC |
| 3 | Back the key up on two different physical drives | 5 min | two drives |
| 4 | Turn on wireless debugging on the TV and pair this PC | 5 min | the TV and its remote |
| 5 | Install the debug build, run the Watch Next test, save the device facts | 8 min | steps 4 |
| 6 | Sign and install the hello APK, and look for the banner | 5 min | steps 2 and 4 |

Fill in the results table at the end (or tell the coordinator) when you are done.

All commands are for PowerShell on this PC, from the repository folder:

```powershell
cd <the repository's folder>
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$bt  = "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.1.0"
```

## 1. The server's version: already known

The version of the server you use was already on record, so there's nothing to do and no dashboard to open (most
people who use a Jellyfin server don't run it).

It decides nothing today: Nixie pins the Jellyfin SDK line that serves both 10.11 and 12.x. It tells us when the
move to the 12-only SDK line is safe. No tool signs in to the server to read it.

## 2. Create the release key

The release key signs every Nixie for Android TV release, for good. Android installs an update only when it is
signed with the same key, so **losing the key or its password means every installed copy must be uninstalled
(losing its settings and sign-in) to move to a new one.** That is why steps 2 and 3 matter.

1. Make a folder for it outside the repository (not the Desktop, not a synced folder such as OneDrive), and name the
   key file in it. The commands below use `$ks`:

   ```powershell
   $keys = '<the folder>'
   New-Item -ItemType Directory -Force $keys | Out-Null
   $ks = "$keys\nixie-tv-release.jks"
   ```

2. Create the key. keytool asks for a password twice: use a long passphrase (five or more random words) that you
   create in your password manager first, in an entry named "Nixie for Android TV release key".

   ```powershell
   & "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -v `
       -keystore $ks -storetype PKCS12 `
       -alias nixie-tv -keyalg RSA -keysize 4096 -validity 10000 `
       -dname "CN=Nixie for Android TV"
   ```

   - The certificate is public: anyone with the APK can read it, so it carries only the app's name, never yours or
     an address.
   - `-validity 10000` is about 27 years (Google Play asks for at least 25).
   - PKCS12 uses the one password for the store and the key.

3. **The password** lives in your password manager only. Not in a file, not in the repository, not in an
   environment variable that stays set, never typed into a chat or given to an agent. Signing (step 6) asks for it
   each time. If you want a second copy, write it on paper and keep it apart from the backup drives.

4. Record the certificate's fingerprint, so the release checker can tell your key from any other. It is public
   information, safe to commit:

   ```powershell
   & "$env:JAVA_HOME\bin\keytool.exe" -list -v -keystore $ks -alias nixie-tv
   ```

   It asks for the password, then prints the certificate. Copy the value after `SHA256:` (32 pairs of hex digits
   with colons) into a new file `release\signing-cert.sha256` in the repository, as its only line. The coordinator
   commits it.

## 3. Back the key up on two different physical drives

Two copies, each on a **different physical drive** from the original and from each other (two folders on one disk
are one copy). A backup drive is a good first one; any other internal or external drive the second. The `.jks` file
is encrypted with the key's password, so a copy on its own is not a way in.

```powershell
# $ks as in step 2; for each backup drive (change X: to the drive letter):
New-Item -ItemType Directory -Force "X:\Keys\nixie-tv" | Out-Null
Copy-Item $ks "X:\Keys\nixie-tv\"
# verify: all hashes must be the same
Get-FileHash $ks, "X:\Keys\nixie-tv\nixie-tv-release.jks"
```

## 4. Turn on wireless debugging and pair

On the TV, with the remote:

1. **Settings > System > About**, then select **Android TV OS build** seven times, until it says you are a
   developer.
2. **Settings > System > Developer options > Wireless debugging**: turn it on. (A Google TV Streamer on Ethernet
   may block it: switch the Streamer to Wi-Fi for this.)
3. In Wireless debugging, choose **Pair device with pairing code**. The TV shows an address with a port and a
   six-digit code.

On the PC:

```powershell
& $adb pair <address>:<pairing port>   # the address and port from the pairing screen; it asks for the code
& $adb connect <address>:<port>        # the address and port on the Wireless debugging screen (not the pairing one)
& $adb devices                         # the TV is listed as "device"
```

Pairing is once; wireless debugging turns itself off when the TV restarts, so turn it on again (and `adb connect`)
next time.

## 5. The debug build: the Watch Next test and the device facts

The debug build installs as **Nixie debug**, beside the real app, and plays no sound.

```powershell
$tv = "<address>:<port>"             # your TV, as in step 4
& $adb -s $tv install -r app\build\outputs\apk\nixie\debug\app-nixie-debug.apk
```

On the TV:

1. Open **Your apps** (or Apps) and look at the **Nixie debug** banner: five glowing nixie tubes spelling NIXIE.
   Open it.
2. Press **Down** to **Add to Continue Watching** and press OK. It says "Added as program N".
3. Press **Home**. On the For you tab, look in the **Continue watching** row for "A Quiet Harbor (Nixie test)"
   with a sunset poster. Give it a minute, and look again after leaving and coming back to Home.
4. If it is there, select it: Nixie debug opens and says "Opened from a link: item a1b2...". Note what you saw.
5. Back in Nixie debug, press **Remove it**, so the test leaves nothing behind.
6. Move to **Save device facts** (Right from the Add button) and press OK. It says how many decoders it found.
   Then on the PC:

   ```powershell
   & $adb -s $tv pull /sdcard/Android/data/io.github.mbustos27.nixie.tv.debug/files/device-facts.json testing\devices\my-tv.json
   ```

   This file holds the facts the plan leaves to the real box (the ABI list, memory class, which decoders exist
   with their limits, AV1 or not, HDR types, which audio formats pass through). It names the model but nothing
   personal. The coordinator renames it after your box and commits it.

What the Watch Next result decides:

- **The movie showed up:** Nixie 1.0 publishes Continue Watching to the Google TV home screen.
- **It did not:** the feature is dropped, and Google's partner-only Engage program is not attempted.

## 6. The signed hello APK

This proves the whole release chain: sign, sideload, banner on the home screen. It needs the key from step 2.
An agent builds the unsigned APK (`.\gradlew.bat :app:assembleNixieRelease`); you sign it:

```powershell
$ks = '<the folder>\nixie-tv-release.jks'            # the key from step 2
& "$bt\apksigner.bat" sign `
    --ks $ks --ks-key-alias nixie-tv `
    --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false `
    --out Nixie-TV-0.1.0.apk `
    app\build\outputs\apk\nixie\release\app-nixie-release-unsigned.apk
```

apksigner asks "Keystore password for signer #1": paste it from your password manager. Then check and install:

```powershell
cd tools\release-check; cargo build --release; cd ..\..      # once, if nixie-release is not built yet
tools\release-check\target\release\nixie-release.exe sha256 Nixie-TV-0.1.0.apk
tools\release-check\target\release\nixie-release.exe check  Nixie-TV-0.1.0.apk   # every line PASS
& $adb -s $tv install -r Nixie-TV-0.1.0.apk
```

On the TV, **Nixie** (without "debug") is now in Your apps with the banner. That is phase 0's exit.

The signed APK and its `.sha256` are not committed (the repository ignores `*.apk`). 0.1.0 is a test build, not a
release, so it is not recorded in `release\released.txt`; the first public release is 1.0.0.

## When you are done

```powershell
& $adb disconnect $tv
```

Wireless debugging can stay on for later phases, or be turned off in Developer options. To remove the debug build:
`& $adb -s $tv uninstall io.github.mbustos27.nixie.tv.debug`.

## Results

| Question | Answer |
|---|---|
| Jellyfin server version | On record (12.x) |
| Release key created, fingerprint in `release\signing-cert.sha256` | Not yet: waits until a release needs it (steps 2, 3 and 6) |
| Backup 1: drive | Not yet (with the key) |
| Backup 2: drive | Not yet (with the key) |
| TV model (Chromecast with Google TV 4K / HD, Google TV Streamer, other) | A Google TV box with Android 14 and the Google TV launcher, paired over wireless debugging |
| Nixie debug banner shown in Your apps | **Yes** |
| "A Quiet Harbor (Nixie test)" shown in Continue watching | **No.** The app inserted it, but the launcher logged `Watch next verification data is empty`: Google TV filters unverified apps' entries. Removed again afterwards. Per the plan, Engage isn't attempted, and Google TV's own home screen gets no Continue watching row from Nixie; Continue watching inside Nixie is unaffected, and other home screens that show Android's Watch Next row got it later (tv-watchnext) |
| Choosing it opened Nixie debug | Not reachable (the entry never showed) |
| Device facts file pulled | **Yes**, in `testing\devices\`. What it taught the app: a 32-bit-only ARM userspace (so the release keeps `armeabi-v7a` beside `arm64-v8a`), about 2 GB of RAM (so no live blur on such boxes), 4K with HDR, hardware decoders for the common video codecs, and passthrough without DTS (so DTS is decoded in the app) |
| Signed Nixie 0.1.0: `check` all PASS | Not yet (step 6, with the key) |
| Nixie banner shown on the TV | Not yet (step 6); the debug build's banner showed |
