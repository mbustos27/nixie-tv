//! The release checks. Each takes what was read from the APK and the repository and says PASS, WARN or FAIL with
//! the reason in plain words. Nothing here runs a tool or touches the disk, so every rule is unit-tested.

use std::collections::{BTreeMap, BTreeSet};

use crate::apk::{Entry, min_load_alignment};
use crate::parse::{Badging, Released, Signing, Version};

/// The permanent application id (plan decision 5). Debug builds carry ".debug" and must never be released.
pub const APP_ID: &str = "io.github.mbustos27.nixie.tv";
/// The release APK is universal for real boxes: the 2020 Chromecast has a 32-bit userspace.
pub const RELEASE_ABIS: [&str; 2] = ["arm64-v8a", "armeabi-v7a"];
/// Play's TV ceiling for minSdk and the target floor the plan names.
pub const MAX_MIN_SDK: u32 = 31;
pub const MIN_TARGET_SDK: u32 = 34;
const PAGE_16K: u64 = 16_384;
const INSTALLER_PERMISSION: &str = "android.permission.REQUEST_INSTALL_PACKAGES";
/// Glacier's core:ffmpeg module (Media3's FFmpeg audio decoder): DTS and TrueHD to PCM. A common 4K Google TV box has
/// no DTS passthrough and no DTS decoder, so a release without it leaves DTS tracks to the server's transcoder.
pub const FFMPEG_LIB: &str = "libffmpegJNI.so";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Level {
    Pass,
    Warn,
    Fail,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Finding {
    pub check: &'static str,
    pub level: Level,
    pub detail: String,
}

fn pass(check: &'static str, detail: impl Into<String>) -> Finding {
    Finding { check, level: Level::Pass, detail: detail.into() }
}
fn warn(check: &'static str, detail: impl Into<String>) -> Finding {
    Finding { check, level: Level::Warn, detail: detail.into() }
}
fn fail(check: &'static str, detail: impl Into<String>) -> Finding {
    Finding { check, level: Level::Fail, detail: detail.into() }
}

/// Everything one run looked at.
pub struct Inputs<'a> {
    pub file_name: &'a str,
    pub apk: &'a [u8],
    pub apk_sha256: &'a str,
    pub entries: &'a [Entry],
    pub badging: &'a Badging,
    pub signing: &'a Signing,
    /// The release certificate's SHA-256 from release/signing-cert.sha256, or why it could not be read.
    pub expected_cert: Result<String, String>,
    /// release/released.txt (empty when there is no release yet), or why it could not be read.
    pub ledger: Result<Vec<Released>, String>,
    /// The text of `<apk>.sha256`, None when the file is missing.
    pub sha256_text: Option<String>,
    pub max_bytes: u64,
}

/// The github flavor carries the installer permission; the play flavor must not.
pub fn flavor(b: &Badging) -> &'static str {
    if b.permissions.iter().any(|p| p == INSTALLER_PERMISSION) { "github" } else { "play" }
}

pub fn run(i: &Inputs) -> Vec<Finding> {
    vec![
        file_name(i.file_name, i.badging),
        signature(i.signing, &i.expected_cert),
        manifest(i.badging),
        version(i.badging),
        rising(i.badging, &i.ledger),
        abis(i.entries),
        ffmpeg(i.entries),
        alignment(i.entries, flavor(i.badging)),
        pages_16k(i.entries, i.apk, flavor(i.badging)),
        size(i.apk.len() as u64, i.max_bytes),
        sha256(i.file_name, i.apk_sha256, i.sha256_text.as_deref()),
    ]
}

pub fn file_name(name: &str, b: &Badging) -> Finding {
    let want = format!("Nixie-TV-{}.apk", b.version_name);
    if name == want { pass("file name", want) } else { fail("file name", format!("{name} should be {want}")) }
}

pub fn signature(s: &Signing, expected: &Result<String, String>) -> Finding {
    const C: &str = "signature";
    if !s.verifies {
        let why = s.errors.first().map(String::as_str).unwrap_or("apksigner says it does not verify");
        return fail(C, format!("does not verify (unsigned?): {why}"));
    }
    if !(s.v2 && s.v3) {
        return fail(C, format!("needs v2 and v3 signatures (v2 {}, v3 {})", s.v2, s.v3));
    }
    if s.signers != 1 {
        return fail(C, format!("{} signers; a release has exactly one", s.signers));
    }
    if s.cert_dn.as_deref().is_some_and(|dn| dn.contains("Android Debug")) {
        return fail(C, "signed with the Android debug key, not the release key");
    }
    let Some(actual) = s.cert_sha256.as_deref() else {
        return fail(C, "apksigner printed no certificate fingerprint");
    };
    match expected {
        Err(why) => fail(C, format!("release/signing-cert.sha256: {why} (record the release key's fingerprint first)")),
        Ok(want) if want != actual => {
            fail(C, format!("signed by {}..., not the release key {}...", &actual[..12], &want[..12]))
        }
        Ok(_) => pass(C, format!("v2 + v3, one signer, the release key {}...", &actual[..12])),
    }
}

pub fn manifest(b: &Badging) -> Finding {
    let mut problems = Vec::new();
    if b.package != APP_ID {
        problems.push(format!("package is {} (should be {APP_ID})", b.package));
    }
    if !b.leanback_launchable {
        problems.push("no LEANBACK_LAUNCHER activity".to_string());
    }
    if b.banner.is_none() {
        problems.push("no android:banner".to_string());
    }
    if !b.leanback_required {
        problems.push("android.software.leanback is not required".to_string());
    }
    if !b.touchscreen_not_required {
        problems.push("touchscreen is not declared required=false".to_string());
    }
    if b.debuggable {
        problems.push("debuggable".to_string());
    }
    if b.min_sdk > MAX_MIN_SDK {
        problems.push(format!("minSdk {} is above {MAX_MIN_SDK}", b.min_sdk));
    }
    if b.target_sdk < MIN_TARGET_SDK {
        problems.push(format!("targetSdk {} is below {MIN_TARGET_SDK}", b.target_sdk));
    }
    if problems.is_empty() {
        pass(
            "manifest",
            format!(
                "{APP_ID}, {} flavor, launcher + banner, touchscreen not required, sdk {}-{}",
                flavor(b),
                b.min_sdk,
                b.target_sdk
            ),
        )
    } else {
        fail("manifest", problems.join("; "))
    }
}

pub fn version(b: &Badging) -> Finding {
    match Version::parse(&b.version_name) {
        None => fail("version", format!("versionName '{}' is not major.minor.patch[-beta.N]", b.version_name)),
        Some(v) if v.code() != b.version_code => fail(
            "version",
            format!(
                "versionCode {} should be {} for {v} (major*1000000 + minor*10000 + patch*100 + beta, 99 when stable)",
                b.version_code,
                v.code()
            ),
        ),
        Some(v) => pass("version", format!("{v}, versionCode {}", b.version_code)),
    }
}

pub fn rising(b: &Badging, ledger: &Result<Vec<Released>, String>) -> Finding {
    const C: &str = "versionCode rises";
    let list = match ledger {
        Err(why) => return fail(C, format!("release/released.txt: {why}")),
        Ok(list) => list,
    };
    if let Some(same) = list.iter().find(|r| r.version.to_string() == b.version_name) {
        return fail(C, format!("{} was already released (versionCode {})", same.version, same.code));
    }
    match list.iter().max_by_key(|r| r.code) {
        None => pass(C, "no earlier release recorded"),
        Some(last) if b.version_code <= last.code => fail(
            C,
            format!(
                "versionCode {} is not above the last release, {} ({}); Android refuses a downgrade",
                b.version_code, last.version, last.code
            ),
        ),
        Some(last) => pass(C, format!("{} > {} ({})", b.version_code, last.code, last.version)),
    }
}

pub fn abis(entries: &[Entry]) -> Finding {
    const C: &str = "ABIs";
    let mut by_abi: BTreeMap<&str, BTreeSet<&str>> = BTreeMap::new();
    for e in entries {
        if let Some((abi, file)) = e.native_lib() {
            by_abi.entry(abi).or_default().insert(file);
        }
    }
    if by_abi.is_empty() {
        return pass(C, "no native code");
    }
    let have: BTreeSet<&str> = by_abi.keys().copied().collect();
    let want: BTreeSet<&str> = RELEASE_ABIS.into_iter().collect();
    let mut problems = Vec::new();
    for missing in want.difference(&have) {
        problems.push(format!("{missing} is missing"));
    }
    for extra in have.difference(&want) {
        problems.push(format!("{extra} should not be in a release"));
    }
    let empty = BTreeSet::new();
    let (a, b) = (by_abi.get("arm64-v8a").unwrap_or(&empty), by_abi.get("armeabi-v7a").unwrap_or(&empty));
    if !a.is_empty() && !b.is_empty() {
        for lib in a.symmetric_difference(b) {
            let (in_abi, not_in) =
                if a.contains(lib) { ("arm64-v8a", "armeabi-v7a") } else { ("armeabi-v7a", "arm64-v8a") };
            problems.push(format!("{lib} is in {in_abi} but not {not_in}"));
        }
    }
    if problems.is_empty() {
        pass(C, format!("armeabi-v7a + arm64-v8a, {} libraries each", a.len()))
    } else {
        fail(C, problems.join("; "))
    }
}

pub fn ffmpeg(entries: &[Entry]) -> Finding {
    const C: &str = "FFmpeg decoder";
    let have: BTreeSet<&str> =
        entries.iter().filter_map(|e| e.native_lib()).filter(|(_, f)| *f == FFMPEG_LIB).map(|(abi, _)| abi).collect();
    let missing: Vec<&str> = RELEASE_ABIS.into_iter().filter(|a| !have.contains(a)).collect();
    if missing.is_empty() {
        pass(C, format!("{FFMPEG_LIB} in armeabi-v7a and arm64-v8a"))
    } else {
        fail(C, format!("{FFMPEG_LIB} is missing for {} (core/ffmpeg/README.md builds it)", missing.join(", ")))
    }
}

/// Stored entries must sit where the platform can map them: native libraries on a page boundary (16 KB for the
/// newest devices, which Play enforces), resources.arsc stored and 4-byte aligned (installs fail otherwise from
/// targetSdk 30).
pub fn alignment(entries: &[Entry], flavor: &str) -> Finding {
    const C: &str = "zip alignment";
    let mut fails = Vec::new();
    let mut soft = Vec::new();
    for e in entries {
        if e.native_lib().is_some() {
            if !e.is_stored() {
                soft.push(format!("{} is compressed (legacy packaging)", e.name));
            } else if e.data_offset % 4096 != 0 {
                fails.push(format!("{} is not page aligned", e.name));
            } else if e.data_offset % PAGE_16K != 0 {
                soft.push(format!("{} is 4 KB but not 16 KB aligned", e.name));
            }
        } else if e.name == "resources.arsc" {
            if !e.is_stored() {
                fails.push("resources.arsc is compressed".to_string());
            } else if e.data_offset % 4 != 0 {
                fails.push("resources.arsc is not 4-byte aligned".to_string());
            }
        }
    }
    if !fails.is_empty() {
        fails.extend(soft);
        return fail(C, fails.join("; "));
    }
    if !soft.is_empty() {
        return soft_finding(C, flavor, soft.join("; "));
    }
    pass(C, "native libraries stored on 16 KB boundaries, resources.arsc stored and aligned")
}

/// 64-bit libraries must have 16 KB aligned LOAD segments to run on 16 KB page devices (Play requires it when
/// targeting API 35+). A FAIL for the play flavor, a WARN for github (no TV box uses 16 KB pages yet).
pub fn pages_16k(entries: &[Entry], apk: &[u8], flavor: &str) -> Finding {
    const C: &str = "16 KB pages";
    let mut problems = Vec::new();
    let mut checked = 0;
    for e in entries {
        let Some((abi, file)) = e.native_lib() else { continue };
        if abi != "arm64-v8a" && abi != "x86_64" {
            continue;
        }
        if !e.is_stored() {
            problems.push(format!("{abi}/{file} is compressed, not checked"));
            continue;
        }
        let start = e.data_offset as usize;
        let Some(bytes) = apk.get(start..start + e.size as usize) else {
            problems.push(format!("{abi}/{file} runs past the end"));
            continue;
        };
        checked += 1;
        match min_load_alignment(bytes) {
            Ok(a) if a >= PAGE_16K => {}
            Ok(a) => problems.push(format!("{abi}/{file} LOAD aligned to {a}")),
            Err(why) => problems.push(format!("{abi}/{file}: {why}")),
        }
    }
    if problems.is_empty() {
        pass(
            C,
            if checked == 0 {
                "no 64-bit native code".to_string()
            } else {
                format!("{checked} 64-bit libraries, all 16 KB aligned")
            },
        )
    } else {
        soft_finding(C, flavor, problems.join("; "))
    }
}

fn soft_finding(check: &'static str, flavor: &str, detail: String) -> Finding {
    if flavor == "play" { fail(check, detail) } else { warn(check, format!("{detail} (Play would refuse this)")) }
}

pub fn size(bytes: u64, max: u64) -> Finding {
    let mb = |b: u64| b as f64 / (1024.0 * 1024.0);
    if bytes <= max {
        pass("size", format!("{:.1} MB (limit {:.0} MB)", mb(bytes), mb(max)))
    } else {
        fail("size", format!("{:.1} MB is over the {:.0} MB limit", mb(bytes), mb(max)))
    }
}

pub fn sha256(file_name: &str, actual: &str, text: Option<&str>) -> Finding {
    const C: &str = ".sha256";
    let Some(text) = text else {
        return fail(C, format!("{file_name}.sha256 is missing (nixie-release sha256 <apk> writes it)"));
    };
    match crate::parse::sha256_file(text) {
        Err(why) => fail(C, format!("{file_name}.sha256: {why}")),
        Ok((_, name)) if name != file_name => fail(C, format!("it names {name}, not {file_name}")),
        Ok((hash, _)) if hash != actual => fail(C, "its hash does not match the APK"),
        Ok(_) => pass(C, format!("matches ({}...)", &actual[..12])),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::apk::entries;
    use crate::apk::testzip::{Spec, build, elf32, elf64};
    use crate::parse::Version;

    fn release_badging() -> Badging {
        Badging {
            package: APP_ID.into(),
            version_code: 1_000_099,
            version_name: "1.0.0".into(),
            min_sdk: 28,
            target_sdk: 36,
            label: "Nixie".into(),
            banner: Some("res/7y.xml".into()),
            leanback_launchable: true,
            leanback_required: true,
            touchscreen_not_required: true,
            debuggable: false,
            native_code: vec!["arm64-v8a".into(), "armeabi-v7a".into()],
            permissions: vec![INSTALLER_PERMISSION.into(), "android.permission.INTERNET".into()],
        }
    }

    fn signed(cert: &str) -> Signing {
        Signing {
            verifies: true,
            v2: true,
            v3: true,
            signers: 1,
            cert_sha256: Some(cert.into()),
            cert_dn: Some("CN=Nixie for Android TV".into()),
            errors: vec![],
        }
    }

    fn released(v: &str) -> Released {
        let version = Version::parse(v).unwrap();
        Released { version, code: version.code(), sha256: "0".repeat(64) }
    }

    #[test]
    fn signature_needs_the_release_key() {
        let key = "a".repeat(64);
        assert_eq!(signature(&signed(&key), &Ok(key.clone())).level, Level::Pass);
        let other = signature(&signed(&"b".repeat(64)), &Ok(key.clone()));
        assert_eq!(other.level, Level::Fail);
        assert!(other.detail.contains("not the release key"));
        let mut debug = signed(&key);
        debug.cert_dn = Some("C=US, O=Android, CN=Android Debug".into());
        assert!(signature(&debug, &Ok(key.clone())).detail.contains("debug key"));
        let mut v2_only = signed(&key);
        v2_only.v3 = false;
        assert_eq!(signature(&v2_only, &Ok(key.clone())).level, Level::Fail);
        assert_eq!(signature(&Signing::default(), &Ok(key.clone())).level, Level::Fail);
        let no_record = signature(&signed(&key), &Err("missing".into()));
        assert_eq!(no_record.level, Level::Fail);
        let mut two = signed(&key);
        two.signers = 2;
        assert_eq!(signature(&two, &Ok(key)).level, Level::Fail);
    }

    #[test]
    fn manifest_names_each_problem() {
        assert_eq!(manifest(&release_badging()).level, Level::Pass);
        let mut b = release_badging();
        b.package = format!("{APP_ID}.debug");
        b.banner = None;
        b.debuggable = true;
        b.touchscreen_not_required = false;
        b.min_sdk = 32;
        let f = manifest(&b);
        assert_eq!(f.level, Level::Fail);
        for word in [".debug", "banner", "debuggable", "touchscreen", "minSdk 32"] {
            assert!(f.detail.contains(word), "{word} in {}", f.detail);
        }
    }

    #[test]
    fn version_code_follows_the_name() {
        assert_eq!(version(&release_badging()).level, Level::Pass);
        let mut b = release_badging();
        b.version_code = 1_000_100;
        assert_eq!(version(&b).level, Level::Fail);
        b.version_name = "1.0.0-debug".into();
        assert_eq!(version(&b).level, Level::Fail);
    }

    #[test]
    fn version_code_must_rise() {
        let b = release_badging(); // 1.0.0 = 1000099
        assert_eq!(rising(&b, &Ok(vec![])).level, Level::Pass);
        assert_eq!(rising(&b, &Ok(vec![released("0.9.9")])).level, Level::Pass);
        assert_eq!(rising(&b, &Ok(vec![released("1.0.0")])).level, Level::Fail, "same version again");
        assert_eq!(
            rising(&b, &Ok(vec![released("0.9.0"), released("1.0.1")])).level,
            Level::Fail,
            "a later one exists"
        );
        assert_eq!(rising(&b, &Err("line 2: bad".into())).level, Level::Fail);
    }

    fn entry(name: &str, stored: bool, offset: u64) -> Entry {
        Entry {
            name: name.into(),
            method: if stored { 0 } else { 8 },
            compressed_size: 1,
            size: 1,
            data_offset: offset,
        }
    }

    #[test]
    fn release_abis_are_exactly_the_two_arm_ones() {
        let libs = |abis: &[&str], file: &str| {
            abis.iter().map(|a| entry(&format!("lib/{a}/{file}"), true, 0)).collect::<Vec<_>>()
        };
        assert_eq!(abis(&[entry("classes.dex", false, 0)]).level, Level::Pass);
        assert_eq!(abis(&libs(&["arm64-v8a", "armeabi-v7a"], "libass.so")).level, Level::Pass);
        let debug = abis(&libs(&["arm64-v8a", "armeabi-v7a", "x86", "x86_64"], "libass.so"));
        assert_eq!(debug.level, Level::Fail);
        assert!(debug.detail.contains("x86 should not"));
        let arm64_only = abis(&libs(&["arm64-v8a"], "libass.so"));
        assert!(arm64_only.detail.contains("armeabi-v7a is missing"));
        let mut uneven = libs(&["arm64-v8a", "armeabi-v7a"], "libass.so");
        uneven.push(entry("lib/arm64-v8a/libffmpegJNI.so", true, 0));
        assert!(abis(&uneven).detail.contains("libffmpegJNI.so is in arm64-v8a but not armeabi-v7a"));
    }

    #[test]
    fn release_carries_the_ffmpeg_decoder_for_both_arm_abis() {
        let lib = |abi: &str| entry(&format!("lib/{abi}/{FFMPEG_LIB}"), true, 0);
        assert_eq!(ffmpeg(&[lib("arm64-v8a"), lib("armeabi-v7a")]).level, Level::Pass);
        let none = ffmpeg(&[entry("classes.dex", false, 0)]);
        assert_eq!(none.level, Level::Fail);
        assert!(none.detail.contains("arm64-v8a, armeabi-v7a"), "{}", none.detail);
        let v8_only = ffmpeg(&[lib("arm64-v8a"), entry("lib/armeabi-v7a/libother.so", true, 0)]);
        assert!(v8_only.detail.contains("missing for armeabi-v7a"), "{}", v8_only.detail);
    }

    #[test]
    fn alignment_rules() {
        let good = [entry("lib/arm64-v8a/a.so", true, 3 * 16384), entry("resources.arsc", true, 400)];
        assert_eq!(alignment(&good, "play").level, Level::Pass);
        let only_4k = [entry("lib/arm64-v8a/a.so", true, 4096)];
        assert_eq!(alignment(&only_4k, "github").level, Level::Warn);
        assert_eq!(alignment(&only_4k, "play").level, Level::Fail);
        assert_eq!(alignment(&[entry("lib/arm64-v8a/a.so", true, 100)], "github").level, Level::Fail);
        assert_eq!(alignment(&[entry("lib/arm64-v8a/a.so", false, 100)], "github").level, Level::Warn);
        assert_eq!(alignment(&[entry("resources.arsc", false, 0)], "github").level, Level::Fail);
        assert_eq!(alignment(&[entry("resources.arsc", true, 2)], "github").level, Level::Fail);
    }

    #[test]
    fn sixteen_kb_pages_read_from_the_real_bytes() {
        let ok = elf64(16384);
        let small = elf64(4096);
        let v7 = elf32(4096); // 32-bit libraries are not held to 16 KB
        let zip = build(&[
            Spec { name: "lib/arm64-v8a/libok.so", data: &ok, stored: true, align: 16384 },
            Spec { name: "lib/armeabi-v7a/libok.so", data: &v7, stored: true, align: 16384 },
        ]);
        let list = entries(&zip).unwrap();
        assert_eq!(pages_16k(&list, &zip, "play").level, Level::Pass);

        let zip = build(&[Spec { name: "lib/arm64-v8a/libold.so", data: &small, stored: true, align: 16384 }]);
        let list = entries(&zip).unwrap();
        let f = pages_16k(&list, &zip, "play");
        assert_eq!(f.level, Level::Fail);
        assert!(f.detail.contains("libold.so LOAD aligned to 4096"));
        assert_eq!(pages_16k(&list, &zip, "github").level, Level::Warn);
    }

    #[test]
    fn size_and_name() {
        assert_eq!(size(10, 10).level, Level::Pass);
        assert_eq!(size(11, 10).level, Level::Fail);
        let b = release_badging();
        assert_eq!(file_name("Nixie-TV-1.0.0.apk", &b).level, Level::Pass);
        assert_eq!(file_name("app-github-release.apk", &b).level, Level::Fail);
    }

    #[test]
    fn sha256_file_must_match() {
        let h = "c".repeat(64);
        let name = "Nixie-TV-1.0.0.apk";
        assert_eq!(sha256(name, &h, Some(&format!("{h}  {name}\n"))).level, Level::Pass);
        assert_eq!(sha256(name, &h, None).level, Level::Fail);
        assert_eq!(sha256(name, &h, Some(&format!("{}  {name}\n", "d".repeat(64)))).level, Level::Fail);
        assert_eq!(sha256(name, &h, Some(&format!("{h}  other.apk\n"))).level, Level::Fail);
    }

    #[test]
    fn flavor_follows_the_installer_permission() {
        let mut b = release_badging();
        assert_eq!(flavor(&b), "github");
        b.permissions.retain(|p| p != INSTALLER_PERMISSION);
        assert_eq!(flavor(&b), "play");
    }
}
