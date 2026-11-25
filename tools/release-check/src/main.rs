//! nixie-release: the checks a Nixie for Android TV release passes before it is uploaded.
//!
//!   nixie-release check  <apk> [--repo DIR] [--max-mb N] [--build-tools DIR]
//!   nixie-release sha256 <apk>
//!   nixie-release record <apk> [--repo DIR] [--max-mb N] [--build-tools DIR]
//!
//! `check` runs every check and exits 0 only when none fails (warnings allowed). `sha256` writes `<apk>.sha256`
//! in sha256sum form. `record` runs the checks and, when they pass, adds the version to release/released.txt, so
//! the next release must have a higher versionCode. It never signs anything and never touches a key: signing is
//! the owner's step (docs/PHASE0-OWNER.md).
//!
//! The SDK's aapt2 and apksigner are found under --build-tools, or the newest build-tools of the SDK at
//! ANDROID_HOME, ANDROID_SDK_ROOT or %LOCALAPPDATA%\Android\Sdk. apksigner needs Java (JAVA_HOME or PATH).

mod apk;
mod checks;
mod parse;

use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::{Command, ExitCode};

use sha2::{Digest, Sha256};

use checks::{Finding, Level};

const USAGE: &str = "usage:
  nixie-release check  <apk> [--repo DIR] [--max-mb N] [--build-tools DIR]
  nixie-release sha256 <apk>
  nixie-release record <apk> [--repo DIR] [--max-mb N] [--build-tools DIR]";

/// The official TV client ships 28 MB; Wholphin's per-ABI builds 40-44 MB. 64 MB leaves room without hiding a
/// mistake such as an mpv build slipping in.
const DEFAULT_MAX_MB: u64 = 64;

struct Args {
    command: String,
    apk: PathBuf,
    repo: PathBuf,
    max_mb: u64,
    build_tools: Option<PathBuf>,
}

fn parse_args(mut it: impl Iterator<Item = String>) -> Result<Args, String> {
    let command = it.next().ok_or(USAGE)?;
    if !matches!(command.as_str(), "check" | "sha256" | "record") {
        return Err(format!("unknown command '{command}'\n{USAGE}"));
    }
    let mut apk = None;
    let mut repo = PathBuf::from(".");
    let mut max_mb = DEFAULT_MAX_MB;
    let mut build_tools = None;
    while let Some(a) = it.next() {
        let mut value = |flag: &str| it.next().ok_or(format!("{flag} needs a value"));
        match a.as_str() {
            "--repo" => repo = PathBuf::from(value("--repo")?),
            "--max-mb" => max_mb = value("--max-mb")?.parse().map_err(|_| "--max-mb needs a whole number")?,
            "--build-tools" => build_tools = Some(PathBuf::from(value("--build-tools")?)),
            s if s.starts_with("--") => return Err(format!("unknown option {s}\n{USAGE}")),
            s if apk.is_none() => apk = Some(PathBuf::from(s)),
            s => return Err(format!("unexpected argument {s}\n{USAGE}")),
        }
    }
    Ok(Args { command, apk: apk.ok_or(USAGE)?, repo, max_mb, build_tools })
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn sha256_hex(data: &[u8]) -> String {
    hex(&Sha256::digest(data))
}

/// The newest build-tools folder that has aapt2.
fn find_build_tools(given: Option<&Path>) -> Result<PathBuf, String> {
    if let Some(dir) = given {
        return Ok(dir.to_path_buf());
    }
    let sdk = ["ANDROID_HOME", "ANDROID_SDK_ROOT"]
        .iter()
        .filter_map(std::env::var_os)
        .map(PathBuf::from)
        .chain(std::env::var_os("LOCALAPPDATA").map(|l| PathBuf::from(l).join("Android").join("Sdk")))
        .find(|p| p.join("build-tools").is_dir())
        .ok_or("no Android SDK found (set ANDROID_HOME or pass --build-tools)")?;
    let mut versions: Vec<(Vec<u32>, PathBuf)> = fs::read_dir(sdk.join("build-tools"))
        .map_err(|e| format!("cannot list build-tools: {e}"))?
        .filter_map(Result::ok)
        .map(|d| d.path())
        .filter(|p| p.join(exe("aapt2")).is_file())
        .map(|p| {
            let key = p
                .file_name()
                .and_then(|n| n.to_str())
                .unwrap_or("")
                .split('.')
                .map(|s| s.parse().unwrap_or(0))
                .collect();
            (key, p)
        })
        .collect();
    versions.sort();
    versions.pop().map(|(_, p)| p).ok_or_else(|| "no build-tools with aapt2 in the SDK".into())
}

fn exe(name: &str) -> String {
    if cfg!(windows) { format!("{name}.exe") } else { name.to_string() }
}

fn run_tool(program: &Path, args: &[&std::ffi::OsStr]) -> Result<String, String> {
    let out =
        Command::new(program).args(args).output().map_err(|e| format!("cannot run {}: {e}", program.display()))?;
    let mut text = String::from_utf8_lossy(&out.stdout).into_owned();
    text.push_str(&String::from_utf8_lossy(&out.stderr));
    Ok(text)
}

fn read_optional(path: &Path) -> Result<Option<String>, String> {
    match fs::read_to_string(path) {
        Ok(t) => Ok(Some(t)),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(e) => Err(format!("{}: {e}", path.display())),
    }
}

fn sha256_path(apk: &Path) -> PathBuf {
    let mut s = apk.as_os_str().to_owned();
    s.push(".sha256");
    PathBuf::from(s)
}

fn file_name(apk: &Path) -> String {
    apk.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default()
}

fn write_sha256(apk: &Path) -> Result<(), String> {
    let data = fs::read(apk).map_err(|e| format!("{}: {e}", apk.display()))?;
    let line = format!("{}  {}\n", sha256_hex(&data), file_name(apk));
    let out = sha256_path(apk);
    fs::write(&out, &line).map_err(|e| format!("{}: {e}", out.display()))?;
    print!("wrote {}: {line}", out.display());
    Ok(())
}

struct Report {
    findings: Vec<Finding>,
    version_name: String,
    version_code: u32,
    apk_sha256: String,
}

fn check(a: &Args) -> Result<Report, String> {
    let data = fs::read(&a.apk).map_err(|e| format!("{}: {e}", a.apk.display()))?;
    let entries = apk::entries(&data).map_err(|e| format!("{}: {e}", a.apk.display()))?;
    let tools = find_build_tools(a.build_tools.as_deref())?;
    let apk_arg = a.apk.as_os_str();

    let badging_text = run_tool(&tools.join(exe("aapt2")), &["dump".as_ref(), "badging".as_ref(), apk_arg])?;
    let badging = parse::badging(&badging_text).map_err(|e| format!("aapt2: {e}"))?;
    let apksigner = tools.join(if cfg!(windows) { "apksigner.bat" } else { "apksigner" });
    // With the APK's own minSdk (28) apksigner checks only v3 and reports v2 as false even when it is there; from
    // 24 it verifies both signatures the release carries (v1 is not needed from 24).
    let signing_text = run_tool(
        &apksigner,
        &[
            "verify".as_ref(),
            "--min-sdk-version".as_ref(),
            "24".as_ref(),
            "--verbose".as_ref(),
            "--print-certs".as_ref(),
            apk_arg,
        ],
    )?;
    let signing = parse::signing(&signing_text);

    let release_dir = a.repo.join("release");
    let expected_cert = match read_optional(&release_dir.join("signing-cert.sha256"))? {
        None => Err("missing".to_string()),
        Some(t) => parse::cert_file(&t),
    };
    let ledger = match read_optional(&release_dir.join("released.txt"))? {
        None => Ok(Vec::new()),
        Some(t) => parse::ledger(&t),
    };
    let apk_sha256 = sha256_hex(&data);
    let name = file_name(&a.apk);
    let sha256_text = read_optional(&sha256_path(&a.apk))?;
    let findings = checks::run(&checks::Inputs {
        file_name: &name,
        apk: &data,
        apk_sha256: &apk_sha256,
        entries: &entries,
        badging: &badging,
        signing: &signing,
        expected_cert,
        ledger,
        sha256_text,
        max_bytes: a.max_mb * 1024 * 1024,
    });
    Ok(Report { findings, version_name: badging.version_name, version_code: badging.version_code, apk_sha256 })
}

fn print_report(apk: &Path, r: &Report) -> bool {
    println!("{}", apk.display());
    for f in &r.findings {
        let tag = match f.level {
            Level::Pass => "PASS",
            Level::Warn => "WARN",
            Level::Fail => "FAIL",
        };
        println!("  {tag}  {:<18} {}", f.check, f.detail);
    }
    let fails = r.findings.iter().filter(|f| f.level == Level::Fail).count();
    let warns = r.findings.iter().filter(|f| f.level == Level::Warn).count();
    if fails == 0 {
        println!("ready to upload ({warns} warning(s))");
    } else {
        println!("NOT ready: {fails} check(s) failed, {warns} warning(s)");
    }
    fails == 0
}

fn record(a: &Args, r: &Report) -> Result<(), String> {
    let path = a.repo.join("release").join("released.txt");
    let existing = read_optional(&path)?;
    let mut f =
        fs::OpenOptions::new().create(true).append(true).open(&path).map_err(|e| format!("{}: {e}", path.display()))?;
    let mut text = String::new();
    if existing.is_none() {
        text.push_str(
            "# Released versions: <version> <versionCode> <sha256 of the APK>. Written by nixie-release record.\n",
        );
    } else if existing.is_some_and(|t| !t.is_empty() && !t.ends_with('\n')) {
        text.push('\n');
    }
    text.push_str(&format!("{} {} {}\n", r.version_name, r.version_code, r.apk_sha256));
    f.write_all(text.as_bytes()).map_err(|e| format!("{}: {e}", path.display()))?;
    println!("recorded {} ({}) in {}", r.version_name, r.version_code, path.display());
    Ok(())
}

fn main() -> ExitCode {
    let args = match parse_args(std::env::args().skip(1)) {
        Ok(a) => a,
        Err(e) => {
            eprintln!("{e}");
            return ExitCode::from(2);
        }
    };
    let result = match args.command.as_str() {
        "sha256" => write_sha256(&args.apk).map(|_| true),
        _ => check(&args).and_then(|r| {
            let ok = print_report(&args.apk, &r);
            if ok && args.command == "record" {
                record(&args, &r)?;
            } else if !ok && args.command == "record" {
                println!("not recorded");
            }
            Ok(ok)
        }),
    };
    match result {
        Ok(true) => ExitCode::SUCCESS,
        Ok(false) => ExitCode::from(1),
        Err(e) => {
            eprintln!("nixie-release: {e}");
            ExitCode::from(2)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn args(list: &[&str]) -> Result<Args, String> {
        parse_args(list.iter().map(|s| s.to_string()))
    }

    #[test]
    fn parses_commands_and_options() {
        let a = args(&["check", "x.apk", "--max-mb", "50", "--repo", "r"]).unwrap();
        assert_eq!((a.command.as_str(), a.max_mb), ("check", 50));
        assert_eq!(a.apk, PathBuf::from("x.apk"));
        assert_eq!(a.repo, PathBuf::from("r"));
        assert!(args(&[]).is_err());
        assert!(args(&["sign", "x.apk"]).is_err(), "there is no sign command");
        assert!(args(&["check"]).is_err());
        assert!(args(&["check", "a.apk", "b.apk"]).is_err());
        assert!(args(&["check", "a.apk", "--max-mb", "lots"]).is_err());
        assert!(args(&["check", "a.apk", "--keystore", "k"]).is_err());
    }

    #[test]
    fn sha256_matches_a_known_vector() {
        assert_eq!(sha256_hex(b"abc"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
