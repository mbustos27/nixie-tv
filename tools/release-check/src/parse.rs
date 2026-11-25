//! Parsers for what the SDK tools print (`aapt2 dump badging`, `apksigner verify --print-certs`) and for the
//! release files kept in the repository (the `.sha256` file, the certificate fingerprint, the release ledger).

/// major.minor.patch with an optional `-beta.N`, the same rule as the app's build (Glacier's rule, kept so the
/// in-app updater compares versions the same way): no leading zeros, minor and patch below 100, beta 1 to 98.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub struct Version {
    pub major: u32,
    pub minor: u32,
    pub patch: u32,
    pub beta: Option<u32>,
}

impl Version {
    pub fn parse(text: &str) -> Option<Version> {
        let num = |s: &str, max_digits: usize| -> Option<u32> {
            let ok = !s.is_empty()
                && s.len() <= max_digits
                && s.bytes().all(|b| b.is_ascii_digit())
                && !(s.len() > 1 && s.starts_with('0'));
            if ok { s.parse().ok() } else { None }
        };
        let (core, beta) = match text.split_once('-') {
            None => (text, None),
            Some((core, suffix)) => {
                let n = num(suffix.strip_prefix("beta.")?, 2)?;
                (1..=98).contains(&n).then_some(())?;
                (core, Some(n))
            }
        };
        let mut parts = core.split('.');
        let (a, b, c) = (parts.next()?, parts.next()?, parts.next()?);
        if parts.next().is_some() {
            return None;
        }
        let major = num(a, 4)?;
        // Play's ceiling for versionCode is 2,100,000,000.
        (major <= 2_099).then_some(())?;
        Some(Version { major, minor: num(b, 2)?, patch: num(c, 2)?, beta })
    }

    /// versionCode = major * 1,000,000 + minor * 10,000 + patch * 100 + (the beta number, or 99 for a stable
    /// release), so a stable release outranks its own betas.
    pub fn code(&self) -> u32 {
        self.major * 1_000_000 + self.minor * 10_000 + self.patch * 100 + self.beta.unwrap_or(99)
    }
}

impl std::fmt::Display for Version {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}.{}.{}", self.major, self.minor, self.patch)?;
        match self.beta {
            Some(n) => write!(f, "-beta.{n}"),
            None => Ok(()),
        }
    }
}

/// What `aapt2 dump badging` says about the APK's manifest.
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct Badging {
    pub package: String,
    pub version_code: u32,
    pub version_name: String,
    pub min_sdk: u32,
    pub target_sdk: u32,
    pub label: String,
    pub banner: Option<String>,
    pub leanback_launchable: bool,
    pub leanback_required: bool,
    pub touchscreen_not_required: bool,
    pub debuggable: bool,
    pub native_code: Vec<String>,
    pub permissions: Vec<String>,
}

/// The value of `key='...'` on a badging line.
fn attr(line: &str, key: &str) -> Option<String> {
    let start = line.find(&format!("{key}='"))? + key.len() + 2;
    let end = line[start..].find('\'')? + start;
    Some(line[start..end].to_string())
}

fn sdk_value(line: &str) -> Option<u32> {
    line.split_once(":'")?.1.trim_end_matches('\'').parse().ok()
}

pub fn badging(text: &str) -> Result<Badging, String> {
    let mut b = Badging::default();
    let mut saw_package = false;
    for raw in text.lines() {
        let line = raw.trim();
        if let Some(rest) = line.strip_prefix("package:") {
            saw_package = true;
            b.package = attr(rest, "name").unwrap_or_default();
            b.version_code = attr(rest, "versionCode").and_then(|v| v.parse().ok()).ok_or("versionCode is missing")?;
            b.version_name = attr(rest, "versionName").unwrap_or_default();
        } else if line.starts_with("minSdkVersion:") || line.starts_with("sdkVersion:") {
            b.min_sdk = sdk_value(line).ok_or("minSdkVersion is unreadable")?;
        } else if line.starts_with("targetSdkVersion:") {
            b.target_sdk = sdk_value(line).ok_or("targetSdkVersion is unreadable")?;
        } else if let Some(rest) = line.strip_prefix("application:") {
            b.label = attr(rest, "label").unwrap_or_default();
            b.banner = attr(rest, "banner").filter(|s| !s.is_empty());
        } else if line.starts_with("leanback-launchable-activity:") {
            b.leanback_launchable = true;
        } else if let Some(rest) = line.strip_prefix("uses-feature-not-required:") {
            if attr(rest, "name").as_deref() == Some("android.hardware.touchscreen") {
                b.touchscreen_not_required = true;
            }
        } else if let Some(rest) = line.strip_prefix("uses-feature:") {
            if attr(rest, "name").as_deref() == Some("android.software.leanback") {
                b.leanback_required = true;
            }
        } else if line == "application-debuggable" {
            b.debuggable = true;
        } else if let Some(rest) = line.strip_prefix("native-code:") {
            b.native_code = rest.split('\'').map(str::trim).filter(|s| !s.is_empty()).map(String::from).collect();
        } else if let Some(name) = line.strip_prefix("uses-permission:").and_then(|rest| attr(rest, "name")) {
            b.permissions.push(name);
        }
    }
    if !saw_package {
        return Err("aapt2 printed no package line".into());
    }
    Ok(b)
}

/// What `apksigner verify --verbose --print-certs` reports.
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct Signing {
    pub verifies: bool,
    pub v2: bool,
    pub v3: bool,
    pub signers: u32,
    /// Lower-case hex SHA-256 of signer #1's certificate.
    pub cert_sha256: Option<String>,
    pub cert_dn: Option<String>,
    pub errors: Vec<String>,
}

pub fn signing(text: &str) -> Signing {
    let mut s = Signing::default();
    for raw in text.lines() {
        let line = raw.trim();
        let flag = |prefix: &str| line.starts_with(prefix) && line.ends_with(": true");
        if line == "Verifies" {
            s.verifies = true;
        } else if flag("Verified using v2 scheme") {
            s.v2 = true;
        } else if flag("Verified using v3 scheme") {
            s.v3 = true;
        } else if let Some(n) = line.strip_prefix("Number of signers:") {
            s.signers = n.trim().parse().unwrap_or(0);
        } else if let Some(d) = line.strip_prefix("Signer #1 certificate SHA-256 digest:") {
            s.cert_sha256 = normalise_hex(d);
        } else if let Some(d) = line.strip_prefix("Signer #1 certificate DN:") {
            s.cert_dn = Some(d.trim().to_string());
        } else if line.starts_with("ERROR") {
            s.errors.push(line.to_string());
        }
    }
    s
}

/// 64 hex digits, colons and spaces allowed (keytool prints AA:BB:...), as lower-case hex; None otherwise.
pub fn normalise_hex(text: &str) -> Option<String> {
    let hex: String = text.trim().chars().filter(|c| *c != ':' && *c != ' ').collect::<String>().to_ascii_lowercase();
    (hex.len() == 64 && hex.bytes().all(|b| b.is_ascii_hexdigit())).then_some(hex)
}

/// The expected release certificate: the first line of `release/signing-cert.sha256` that is not a comment.
pub fn cert_file(text: &str) -> Result<String, String> {
    let line = text
        .lines()
        .map(str::trim)
        .find(|l| !l.is_empty() && !l.starts_with('#'))
        .ok_or("it has no fingerprint line")?;
    normalise_hex(line).ok_or_else(|| "its fingerprint is not 64 hex digits".into())
}

/// A `sha256sum`-style line: `<64 hex>  <file name>` (or ` *<name>` for binary mode).
pub fn sha256_file(text: &str) -> Result<(String, String), String> {
    let line = text.lines().next().ok_or("it is empty")?.trim_end();
    let (hash, rest) = line.split_once(' ').ok_or("expected '<hash>  <file name>'")?;
    let name = rest.trim_start_matches(' ').trim_start_matches('*');
    let hash = normalise_hex(hash).ok_or("the hash is not 64 hex digits")?;
    if name.is_empty() {
        return Err("the file name is missing".into());
    }
    if text.lines().filter(|l| !l.trim().is_empty()).count() != 1 {
        return Err("it should hold exactly one line".into());
    }
    Ok((hash, name.to_string()))
}

/// One released version in `release/released.txt`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Released {
    pub version: Version,
    pub code: u32,
    pub sha256: String,
}

/// The ledger: `<version> <versionCode> <sha256>` per line, `#` comments. Every line must be well formed and
/// consistent (code = the version's code), so a typo cannot let a lower versionCode through.
pub fn ledger(text: &str) -> Result<Vec<Released>, String> {
    let mut out = Vec::new();
    for (n, raw) in text.lines().enumerate() {
        let line = raw.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let parts: Vec<&str> = line.split_whitespace().collect();
        let bad = |why: &str| format!("line {}: {why}", n + 1);
        if parts.len() != 3 {
            return Err(bad("expected '<version> <versionCode> <sha256>'"));
        }
        let version = Version::parse(parts[0]).ok_or_else(|| bad("the version is not major.minor.patch[-beta.N]"))?;
        let code: u32 = parts[1].parse().map_err(|_| bad("the versionCode is not a number"))?;
        if code != version.code() {
            return Err(bad(&format!("versionCode {code} does not match {version} ({})", version.code())));
        }
        let sha256 = normalise_hex(parts[2]).ok_or_else(|| bad("the sha256 is not 64 hex digits"))?;
        out.push(Released { version, code, sha256 });
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    const RELEASE_BADGING: &str = "package: name='io.github.mbustos27.nixie.tv' versionCode='1000099' versionName='1.0.0' platformBuildVersionName='17' platformBuildVersionCode='37' compileSdkVersion='37' compileSdkVersionCodename='17'
minSdkVersion:'28'
targetSdkVersion:'36'
uses-permission: name='android.permission.REQUEST_INSTALL_PACKAGES'
uses-permission: name='android.permission.INTERNET'
application-label:'Nixie'
application: label='Nixie' icon='res/E4.xml' banner='res/7y.xml'
leanback-launchable-activity: name='io.github.mbustos27.nixie.tv.MainActivity'  label='' icon='' banner=''
feature-group: label=''
  uses-feature-not-required: name='android.hardware.microphone'
  uses-feature-not-required: name='android.hardware.touchscreen'
  uses-feature: name='android.software.leanback'
  uses-feature: name='android.hardware.screen.landscape'
supports-screens: 'small' 'normal' 'large' 'xlarge'
native-code: 'arm64-v8a' 'armeabi-v7a'
";

    #[test]
    fn versions_follow_the_app_rule() {
        assert_eq!(Version::parse("1.2.3").unwrap().code(), 1_020_399);
        assert_eq!(Version::parse("0.1.0").unwrap().code(), 10_099);
        assert_eq!(Version::parse("0.10.1").unwrap().code(), 100_199, "Glacier's own rule for 0.10.1");
        let beta = Version::parse("1.4.0-beta.2").unwrap();
        assert_eq!((beta.code(), beta.to_string().as_str()), (1_040_002, "1.4.0-beta.2"));
        assert!(Version::parse("1.4.0").unwrap().code() > beta.code(), "a stable release outranks its betas");
        assert_eq!(Version::parse("2099.99.99").unwrap().code(), 2_099_999_999);
        for bad in [
            "",
            "1.2",
            "1.2.3.4",
            "v1.2.3",
            "1.100.0",
            "1.0.100",
            "01.2.3",
            "1.02.3",
            "1.2.3-debug",
            "1.-2.3",
            "1.0.0-beta.0",
            "1.0.0-beta.99",
            "1.0.0-beta.01",
            "1.0.0-beta.",
            "1.0.0-rc.1",
            "1.0.0-beta.1-x",
            "2100.0.0",
        ] {
            assert!(Version::parse(bad).is_none(), "{bad}");
        }
    }

    #[test]
    fn reads_badging() {
        let b = badging(RELEASE_BADGING).unwrap();
        assert_eq!(b.package, "io.github.mbustos27.nixie.tv");
        assert_eq!((b.version_code, b.version_name.as_str()), (1_000_099, "1.0.0"));
        assert_eq!((b.min_sdk, b.target_sdk), (28, 36));
        assert_eq!(b.banner.as_deref(), Some("res/7y.xml"));
        assert!(b.leanback_launchable && b.leanback_required && b.touchscreen_not_required && !b.debuggable);
        assert_eq!(b.native_code, vec!["arm64-v8a", "armeabi-v7a"]);
        assert!(b.permissions.iter().any(|p| p == "android.permission.REQUEST_INSTALL_PACKAGES"));
    }

    #[test]
    fn badging_sees_what_is_missing() {
        let text = RELEASE_BADGING
            .replace(" banner='res/7y.xml'", "")
            .replace("  uses-feature-not-required: name='android.hardware.touchscreen'\n", "")
            .replace("leanback-launchable-activity", "launchable-activity")
            + "application-debuggable\n";
        let b = badging(&text).unwrap();
        assert!(b.banner.is_none() && !b.touchscreen_not_required && !b.leanback_launchable && b.debuggable);
        assert!(badging("nothing useful").is_err());
    }

    #[test]
    fn reads_apksigner() {
        let text = "Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Verified using v3.1 scheme (APK Signature Scheme v3.1): false
Number of signers: 1
Signer #1 certificate DN: CN=Nixie for Android TV
Signer #1 certificate SHA-256 digest: AB12000000000000000000000000000000000000000000000000000000000000
";
        let s = signing(text);
        assert!(s.verifies && s.v2 && s.v3);
        assert_eq!(s.signers, 1);
        assert_eq!(s.cert_sha256.as_deref(), Some("ab12000000000000000000000000000000000000000000000000000000000000"));
        let unsigned = signing("DOES NOT VERIFY\nERROR: Missing META-INF/MANIFEST.MF\n");
        assert!(!unsigned.verifies && !unsigned.v2 && unsigned.errors.len() == 1);
    }

    #[test]
    fn reads_fingerprints_in_keytool_or_plain_form() {
        let plain = "ab".repeat(32);
        let keytool = vec!["AB"; 32].join(":");
        assert_eq!(cert_file(&format!("# release key\n{keytool}\n")).unwrap(), plain);
        assert_eq!(cert_file(&plain).unwrap(), plain);
        assert!(cert_file("# only a comment\n").is_err());
        assert!(cert_file("abcd").is_err());
    }

    #[test]
    fn reads_sha256_files() {
        let h = "0f".repeat(32);
        assert_eq!(
            sha256_file(&format!("{h}  Nixie-TV-1.0.0.apk\n")).unwrap(),
            (h.clone(), "Nixie-TV-1.0.0.apk".into())
        );
        assert_eq!(sha256_file(&format!("{h} *Nixie.apk")).unwrap().1, "Nixie.apk");
        assert!(sha256_file("").is_err());
        assert!(sha256_file(&h).is_err());
        assert!(sha256_file(&format!("{h}  a.apk\n{h}  b.apk\n")).is_err());
        assert!(sha256_file("abc  a.apk").is_err());
    }

    #[test]
    fn ledger_lines_must_be_consistent() {
        let h = "1".repeat(64);
        let l =
            ledger(&format!("# released\n1.0.0-beta.1 1000001 {h}\n1.0.0 1000099 {h}\n1.0.1 1000199 {h}\n")).unwrap();
        assert_eq!(l.len(), 3);
        assert_eq!(l[2].code, 1_000_199);
        assert_eq!(l[0].version.to_string(), "1.0.0-beta.1");
        assert!(ledger(&format!("1.0.0 10000 {h}")).is_err(), "code that does not match the version");
        assert!(ledger("1.0.0 1000099").is_err());
        assert!(ledger(&format!("1.0 1000099 {h}")).is_err());
        assert!(ledger("").unwrap().is_empty());
    }
}
