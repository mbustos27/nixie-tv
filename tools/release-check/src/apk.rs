//! Reading an APK without unpacking it: the ZIP central directory (names, compression, where each entry's data
//! starts) and the ELF program headers of stored native libraries. Only what the release checks need; no inflate.

/// One file inside the APK.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Entry {
    pub name: String,
    /// ZIP compression method: 0 = stored, 8 = deflated.
    pub method: u16,
    pub compressed_size: u64,
    pub size: u64,
    /// Absolute offset of the entry's data (after its local header).
    pub data_offset: u64,
}

impl Entry {
    pub fn is_stored(&self) -> bool {
        self.method == 0
    }

    /// `lib/<abi>/<file>.so` gives `(abi, file)`.
    pub fn native_lib(&self) -> Option<(&str, &str)> {
        let rest = self.name.strip_prefix("lib/")?;
        let (abi, file) = rest.split_once('/')?;
        (file.ends_with(".so") && !file.contains('/') && !abi.is_empty()).then_some((abi, file))
    }
}

fn u16_at(b: &[u8], at: usize) -> Option<u16> {
    Some(u16::from_le_bytes(b.get(at..at + 2)?.try_into().ok()?))
}

fn u32_at(b: &[u8], at: usize) -> Option<u32> {
    Some(u32::from_le_bytes(b.get(at..at + 4)?.try_into().ok()?))
}

fn u64_at(b: &[u8], at: usize) -> Option<u64> {
    Some(u64::from_le_bytes(b.get(at..at + 8)?.try_into().ok()?))
}

const EOCD_SIG: u32 = 0x0605_4b50;
const CDH_SIG: u32 = 0x0201_4b50;
const LFH_SIG: u32 = 0x0403_4b50;

/// Lists every entry of a ZIP (APK) held in memory. Errors name what is wrong; ZIP64 is refused (an APK this
/// size is a mistake).
pub fn entries(zip: &[u8]) -> Result<Vec<Entry>, String> {
    // The end-of-central-directory record is the last 22 bytes plus a comment of at most 65535 bytes.
    let search_from = zip.len().saturating_sub(22 + 0xFFFF);
    let eocd = (search_from..=zip.len().saturating_sub(22))
        .rev()
        .find(|&i| u32_at(zip, i) == Some(EOCD_SIG))
        .ok_or("not a ZIP file (no end of central directory)")?;
    let count = u16_at(zip, eocd + 10).ok_or("truncated end record")? as usize;
    let cd_size = u32_at(zip, eocd + 12).ok_or("truncated end record")?;
    let cd_offset = u32_at(zip, eocd + 16).ok_or("truncated end record")?;
    if cd_size == u32::MAX || cd_offset == u32::MAX || count == 0xFFFF {
        return Err("ZIP64 archives are not expected for an APK".into());
    }
    let mut at = cd_offset as usize;
    let mut out = Vec::with_capacity(count);
    for i in 0..count {
        if u32_at(zip, at) != Some(CDH_SIG) {
            return Err(format!("central directory entry {i} is damaged"));
        }
        let method = u16_at(zip, at + 10).ok_or("truncated entry")?;
        let compressed_size = u32_at(zip, at + 20).ok_or("truncated entry")? as u64;
        let size = u32_at(zip, at + 24).ok_or("truncated entry")? as u64;
        let name_len = u16_at(zip, at + 28).ok_or("truncated entry")? as usize;
        let extra_len = u16_at(zip, at + 30).ok_or("truncated entry")? as usize;
        let comment_len = u16_at(zip, at + 32).ok_or("truncated entry")? as usize;
        let local = u32_at(zip, at + 42).ok_or("truncated entry")? as usize;
        let name_bytes = zip.get(at + 46..at + 46 + name_len).ok_or("truncated entry name")?;
        let name =
            String::from_utf8(name_bytes.to_vec()).map_err(|_| format!("entry {i} has a name that is not UTF-8"))?;
        if u32_at(zip, local) != Some(LFH_SIG) {
            return Err(format!("{name}: local header is missing"));
        }
        let lname = u16_at(zip, local + 26).ok_or("truncated local header")? as usize;
        let lextra = u16_at(zip, local + 28).ok_or("truncated local header")? as usize;
        let data_offset = (local + 30 + lname + lextra) as u64;
        if data_offset + compressed_size > zip.len() as u64 {
            return Err(format!("{name}: data runs past the end of the file"));
        }
        out.push(Entry { name, method, compressed_size, size, data_offset });
        at += 46 + name_len + extra_len + comment_len;
    }
    Ok(out)
}

/// The smallest alignment of the ELF file's PT_LOAD segments, or an error for something that is not an ELF file.
/// A 16 KB page device can load a library only when every PT_LOAD is aligned to at least 16384.
pub fn min_load_alignment(elf: &[u8]) -> Result<u64, String> {
    if elf.get(0..4) != Some(b"\x7fELF".as_slice()) {
        return Err("not an ELF file".into());
    }
    if elf.get(5) != Some(&1) {
        return Err("not a little-endian ELF file".into());
    }
    let is64 = match elf.get(4) {
        Some(1) => false,
        Some(2) => true,
        _ => return Err("unknown ELF class".into()),
    };
    let (phoff, phentsize, phnum) = if is64 {
        (u64_at(elf, 0x20), u16_at(elf, 0x36), u16_at(elf, 0x38))
    } else {
        (u32_at(elf, 0x1C).map(u64::from), u16_at(elf, 0x2A), u16_at(elf, 0x2C))
    };
    let (phoff, phentsize, phnum) = match (phoff, phentsize, phnum) {
        (Some(a), Some(b), Some(c)) => (a as usize, b as usize, c as usize),
        _ => return Err("truncated ELF header".into()),
    };
    let mut min: Option<u64> = None;
    for i in 0..phnum {
        let ph = phoff + i * phentsize;
        let p_type = u32_at(elf, ph).ok_or("truncated program header")?;
        if p_type != 1 {
            continue; // PT_LOAD only
        }
        let align = if is64 { u64_at(elf, ph + 0x30) } else { u32_at(elf, ph + 0x1C).map(u64::from) }
            .ok_or("truncated program header")?;
        min = Some(min.map_or(align, |m| m.min(align)));
    }
    min.ok_or_else(|| "no loadable segment".into())
}

#[cfg(test)]
pub mod testzip {
    //! A tiny ZIP writer for tests: stored or "deflated" (just flagged; the checker never inflates) entries, with
    //! padding in the local extra field to put data at a chosen alignment.

    pub struct Spec<'a> {
        pub name: &'a str,
        pub data: &'a [u8],
        pub stored: bool,
        /// Align the data to this many bytes (0 = no padding).
        pub align: usize,
    }

    pub fn build(specs: &[Spec]) -> Vec<u8> {
        let mut out = Vec::new();
        let mut central = Vec::new();
        for s in specs {
            let local = out.len();
            let base = local + 30 + s.name.len();
            let pad = if s.align > 1 { (s.align - base % s.align) % s.align } else { 0 };
            let method: u16 = if s.stored { 0 } else { 8 };
            out.extend_from_slice(&0x0403_4b50u32.to_le_bytes());
            out.extend_from_slice(&[20, 0, 0, 0]);
            out.extend_from_slice(&method.to_le_bytes());
            out.extend_from_slice(&[0; 8]); // time, date, crc (unchecked here)
            out.extend_from_slice(&(s.data.len() as u32).to_le_bytes());
            out.extend_from_slice(&(s.data.len() as u32).to_le_bytes());
            out.extend_from_slice(&(s.name.len() as u16).to_le_bytes());
            out.extend_from_slice(&(pad as u16).to_le_bytes());
            out.extend_from_slice(s.name.as_bytes());
            out.extend(std::iter::repeat_n(0u8, pad));
            out.extend_from_slice(s.data);

            central.extend_from_slice(&0x0201_4b50u32.to_le_bytes());
            central.extend_from_slice(&[20, 0, 20, 0, 0, 0]);
            central.extend_from_slice(&method.to_le_bytes());
            central.extend_from_slice(&[0; 8]);
            central.extend_from_slice(&(s.data.len() as u32).to_le_bytes());
            central.extend_from_slice(&(s.data.len() as u32).to_le_bytes());
            central.extend_from_slice(&(s.name.len() as u16).to_le_bytes());
            central.extend_from_slice(&[0; 2 + 2 + 2 + 2 + 4]); // extra, comment, disk, internal, external attrs
            central.extend_from_slice(&(local as u32).to_le_bytes());
            central.extend_from_slice(s.name.as_bytes());
        }
        let cd_offset = out.len();
        out.extend_from_slice(&central);
        out.extend_from_slice(&0x0605_4b50u32.to_le_bytes());
        out.extend_from_slice(&[0; 4]);
        out.extend_from_slice(&(specs.len() as u16).to_le_bytes());
        out.extend_from_slice(&(specs.len() as u16).to_le_bytes());
        out.extend_from_slice(&(central.len() as u32).to_le_bytes());
        out.extend_from_slice(&(cd_offset as u32).to_le_bytes());
        out.extend_from_slice(&[0; 2]);
        out
    }

    /// A minimal 64-bit ELF with one PT_LOAD of the given alignment.
    pub fn elf64(align: u64) -> Vec<u8> {
        let mut e = vec![0u8; 64 + 56];
        e[0..4].copy_from_slice(b"\x7fELF");
        e[4] = 2;
        e[5] = 1;
        e[0x20..0x28].copy_from_slice(&64u64.to_le_bytes());
        e[0x36..0x38].copy_from_slice(&56u16.to_le_bytes());
        e[0x38..0x3A].copy_from_slice(&1u16.to_le_bytes());
        e[64..68].copy_from_slice(&1u32.to_le_bytes());
        e[64 + 0x30..64 + 0x38].copy_from_slice(&align.to_le_bytes());
        e
    }

    /// A minimal 32-bit ELF with one PT_LOAD of the given alignment.
    pub fn elf32(align: u32) -> Vec<u8> {
        let mut e = vec![0u8; 52 + 32];
        e[0..4].copy_from_slice(b"\x7fELF");
        e[4] = 1;
        e[5] = 1;
        e[0x1C..0x20].copy_from_slice(&52u32.to_le_bytes());
        e[0x2A..0x2C].copy_from_slice(&32u16.to_le_bytes());
        e[0x2C..0x2E].copy_from_slice(&1u16.to_le_bytes());
        e[52..56].copy_from_slice(&1u32.to_le_bytes());
        e[52 + 0x1C..52 + 0x20].copy_from_slice(&align.to_le_bytes());
        e
    }
}

#[cfg(test)]
mod tests {
    use super::testzip::{Spec, build, elf32, elf64};
    use super::*;

    #[test]
    fn lists_entries_with_their_data_offsets() {
        let zip = build(&[
            Spec { name: "AndroidManifest.xml", data: b"<m/>", stored: false, align: 0 },
            Spec { name: "lib/arm64-v8a/liba.so", data: b"0123456789", stored: true, align: 16384 },
        ]);
        let list = entries(&zip).unwrap();
        assert_eq!(list.len(), 2);
        assert_eq!(list[0].name, "AndroidManifest.xml");
        assert!(!list[0].is_stored());
        let lib = &list[1];
        assert!(lib.is_stored());
        assert_eq!(lib.data_offset % 16384, 0);
        assert_eq!(&zip[lib.data_offset as usize..lib.data_offset as usize + 10], b"0123456789");
        assert_eq!(lib.native_lib(), Some(("arm64-v8a", "liba.so")));
    }

    #[test]
    fn native_lib_needs_the_lib_abi_file_shape() {
        let e = |n: &str| Entry { name: n.into(), method: 0, compressed_size: 0, size: 0, data_offset: 0 };
        assert_eq!(e("lib/x86/libz.so").native_lib(), Some(("x86", "libz.so")));
        assert_eq!(e("lib/x86/sub/libz.so").native_lib(), None);
        assert_eq!(e("lib/libz.so").native_lib(), None);
        assert_eq!(e("assets/lib/x86/libz.so").native_lib(), None);
        assert_eq!(e("lib/x86/readme.txt").native_lib(), None);
    }

    #[test]
    fn refuses_what_is_not_a_zip() {
        assert!(entries(b"hello").is_err());
        assert!(entries(&[]).is_err());
        let mut zip = build(&[Spec { name: "a", data: b"x", stored: true, align: 0 }]);
        let n = zip.len();
        zip.truncate(n - 30); // cut into the central directory
        assert!(entries(&zip).is_err());
    }

    #[test]
    fn reads_elf_load_alignment() {
        assert_eq!(min_load_alignment(&elf64(16384)).unwrap(), 16384);
        assert_eq!(min_load_alignment(&elf64(4096)).unwrap(), 4096);
        assert_eq!(min_load_alignment(&elf32(4096)).unwrap(), 4096);
        assert!(min_load_alignment(b"not an elf at all").is_err());
    }
}
