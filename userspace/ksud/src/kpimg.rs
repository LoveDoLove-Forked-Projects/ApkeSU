use android_bootimg::parser::BootImage;
use android_bootimg::patcher::BootImagePatchOption;
use anyhow::{Context, Result, ensure};
use std::fs::{self, File};
use std::io::{self, Cursor, Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::thread;
use std::time::{Duration, Instant};

const MAGIC: &[u8; 8] = b"KP1158\0\0";
const MAX_BOOT: usize = 512 * 1024 * 1024;
const MAX_KERNEL: usize = 128 * 1024 * 1024 - 8192;
const MAX_TOOL: usize = 16 * 1024 * 1024;
const MAX_LOG: usize = 256 * 1024;
const PRESET_HEADER: usize = 64;
const PRESET_SIZE: usize = 1416;
const HEADER_BACKUP: usize = PRESET_HEADER + 104 + 56;

#[derive(clap::Args, Debug)]
pub struct BootPatchKpimgArgs {
    /// Android boot.img containing a built-in CONFIG_KSU=y CONFIG_KPM=y kernel
    #[arg(short, long)]
    pub boot: PathBuf,
    /// Output image file; never flashed automatically
    #[arg(short, long)]
    pub output: PathBuf,
    /// Override the bundled KPM runtime
    #[arg(long)]
    pub kpimg: Option<PathBuf>,
    /// Override the bundled static Android AArch64 patcher
    #[arg(long)]
    pub kptools: Option<PathBuf>,
    /// Replace an existing output file (never an input)
    #[arg(long)]
    pub force: bool,
    /// Maximum patcher execution time in seconds
    #[arg(long, default_value_t = 120, value_parser = clap::value_parser!(u64).range(1..=600))]
    pub timeout_seconds: u64,
}

#[derive(clap::Args, Debug)]
pub struct BootInfoKpimgArgs {
    #[arg(short, long)]
    pub boot: PathBuf,
}

fn read_regular(path: &Path, limit: usize) -> Result<Vec<u8>> {
    let metadata =
        fs::symlink_metadata(path).with_context(|| format!("cannot inspect {}", path.display()))?;
    ensure!(
        metadata.is_file() && !metadata.file_type().is_symlink(),
        "expected a regular file: {}",
        path.display()
    );
    ensure!(
        metadata.len() > 0 && metadata.len() <= limit as u64,
        "invalid file size: {}",
        path.display()
    );
    let mut bytes = Vec::new();
    File::open(path)?
        .take(limit as u64 + 1)
        .read_to_end(&mut bytes)?;
    ensure!(
        bytes.len() <= limit,
        "file exceeds size limit: {}",
        path.display()
    );
    Ok(bytes)
}

fn u64_at(bytes: &[u8], offset: usize) -> Result<u64> {
    let data = bytes
        .get(offset..offset + 8)
        .context("truncated binary field")?;
    Ok(u64::from_le_bytes(data.try_into()?))
}

fn validate_arm64(image: &[u8]) -> Result<()> {
    ensure!(
        image.len() >= 64 && image.len() <= MAX_KERNEL,
        "invalid ARM64 kernel size"
    );
    ensure!(
        image.get(56..60) == Some(b"ARM\x64"),
        "kernel is not an ARM64 Image"
    );
    ensure!(
        u64_at(image, 24)? & 1 == 0,
        "big-endian kernels are unsupported"
    );
    let entry = if image.starts_with(b"MZ") { 4 } else { 0 };
    let instruction = u32::from_le_bytes(image[entry..entry + 4].try_into()?);
    ensure!(
        instruction & 0xfc00_0000 == 0x1400_0000,
        "unrecognized ARM64 entry instruction"
    );
    Ok(())
}

fn validate_kpimg(bytes: &[u8]) -> Result<()> {
    ensure!(
        bytes.len() > 4096 && bytes.len() <= MAX_TOOL && bytes.starts_with(MAGIC),
        "invalid kpimg header or size"
    );
    ensure!(
        u64_at(bytes, 16)? & 2 != 0,
        "kpimg was not built for Android"
    );
    Ok(())
}

fn validate_kptools(bytes: &[u8]) -> Result<()> {
    ensure!(
        bytes.len() >= 64 && bytes.len() <= MAX_TOOL,
        "invalid kptools size"
    );
    ensure!(
        &bytes[..7] == b"\x7fELF\x02\x01\x01",
        "kptools must be little-endian ELF64"
    );
    let kind = u16::from_le_bytes(bytes[16..18].try_into()?);
    ensure!(
        matches!(kind, 2 | 3) && &bytes[18..20] == b"\xb7\0",
        "kptools must be an AArch64 executable"
    );
    let phoff = usize::try_from(u64_at(bytes, 32)?)?;
    let entry_size = usize::from(u16::from_le_bytes(bytes[54..56].try_into()?));
    let count = usize::from(u16::from_le_bytes(bytes[56..58].try_into()?));
    ensure!(
        count > 0 && entry_size >= 56,
        "invalid kptools program headers"
    );
    let end = count
        .checked_mul(entry_size)
        .and_then(|size| phoff.checked_add(size))
        .context("program header overflow")?;
    ensure!(
        phoff >= 64 && end <= bytes.len(),
        "truncated kptools program headers"
    );
    let mut executable = false;
    for index in 0..count {
        let pos = phoff + index * entry_size;
        let kind = u32::from_le_bytes(bytes[pos..pos + 4].try_into()?);
        ensure!(kind != 3, "kptools must be static (PT_INTERP is forbidden)");
        if kind == 1 {
            let offset = u64_at(bytes, pos + 8)?;
            let size = u64_at(bytes, pos + 32)?;
            ensure!(
                offset
                    .checked_add(size)
                    .is_some_and(|end| end <= bytes.len() as u64),
                "invalid kptools load segment"
            );
            executable |= bytes[pos + 4] & 1 != 0 && size > 0;
        }
    }
    ensure!(executable, "kptools has no executable load segment");
    Ok(())
}

struct LimitedBuffer(Vec<u8>);

impl Write for LimitedBuffer {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        if bytes.len() > MAX_KERNEL.saturating_sub(self.0.len()) {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "decompressed kernel exceeds limit",
            ));
        }
        self.0.extend_from_slice(bytes);
        Ok(bytes.len())
    }
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

fn parse_boot(bytes: &[u8]) -> Result<BootImage<'_>> {
    ensure!(
        bytes.len() >= 4096 && bytes.starts_with(b"ANDROID!"),
        "expected Android boot.img; vendor_boot and raw images are unsupported"
    );
    let version = u32::from_le_bytes(bytes[40..44].try_into()?);
    ensure!(version <= 4, "unsupported Android boot header version");
    if version <= 2 {
        let page_size = u32::from_le_bytes(bytes[36..40].try_into()?);
        ensure!(
            (2048..=65536).contains(&page_size) && page_size.is_power_of_two(),
            "invalid boot page size"
        );
    }
    let boot = BootImage::parse(bytes).context("cannot parse boot image")?;
    ensure!(
        boot.get_blocks().get_kernel().is_some(),
        "image has no kernel: init_boot cannot carry Native GKI KPM"
    );
    Ok(boot)
}

fn unpack_kernel(boot: &BootImage<'_>) -> Result<Vec<u8>> {
    let mut output = LimitedBuffer(Vec::new());
    boot.get_blocks()
        .get_kernel()
        .context("missing kernel")?
        .dump(&mut output, false)
        .context("cannot decompress kernel")?;
    validate_arm64(&output.0)?;
    Ok(output.0)
}

fn marker(image: &[u8]) -> Result<Option<usize>> {
    let mut result = None;
    for (offset, window) in image.windows(MAGIC.len()).enumerate() {
        if window != MAGIC {
            continue;
        }
        // A string in kernel rodata is not itself proof of an injected runtime.
        if offset % 4096 != 0 || offset + PRESET_SIZE > image.len() {
            continue;
        }
        let original = usize::try_from(u64_at(image, offset + PRESET_HEADER + 8)?)?;
        let size = usize::try_from(u64_at(image, offset + PRESET_HEADER + 16)?)?;
        if original < 64 || original.checked_add(4095).map(|n| n & !4095) != Some(offset) {
            continue;
        }
        ensure!(
            size > 4096
                && offset
                    .checked_add(size)
                    .is_some_and(|end| end <= image.len()),
            "truncated injected kpimg"
        );
        ensure!(
            u64_at(image, offset + PRESET_HEADER + 40)? == offset as u64,
            "invalid kpimg setup offset"
        );
        ensure!(result.is_none(), "multiple kpimg presets found");
        result = Some(offset);
    }
    Ok(result)
}

fn validate_patched(original: &[u8], patched: &[u8], runtime: &[u8]) -> Result<usize> {
    validate_arm64(patched)?;
    let offset = marker(patched)?.context("patcher output has no valid kpimg preset")?;
    ensure!(
        u64_at(patched, offset + PRESET_HEADER + 8)? == original.len() as u64,
        "original kernel length mismatch"
    );
    ensure!(
        u64_at(patched, offset + PRESET_HEADER + 16)? == ((runtime.len() + 15) & !15) as u64,
        "injected runtime length mismatch"
    );
    ensure!(
        patched.get(offset..offset + 24) == runtime.get(..24),
        "injected runtime header mismatch"
    );
    ensure!(
        patched.get(offset + 4096..offset + runtime.len()) == runtime.get(4096..),
        "injected runtime code mismatch"
    );
    ensure!(
        patched.get(offset + HEADER_BACKUP..offset + HEADER_BACKUP + 8) == original.get(..8),
        "original kernel entry backup mismatch"
    );
    let entry = if original.starts_with(b"MZ") { 4 } else { 0 };
    let instruction = u32::from_le_bytes(patched[entry..entry + 4].try_into()?);
    let displacement = ((instruction as i32) << 6 >> 6) * 4;
    ensure!(
        entry as i64 + i64::from(displacement) == (offset + 4096) as i64,
        "kernel entry does not branch into kpimg"
    );
    Ok(offset)
}

fn validate_output(args: &BootPatchKpimgArgs) -> Result<()> {
    if let Ok(metadata) = fs::symlink_metadata(&args.output) {
        ensure!(
            metadata.is_file() && !metadata.file_type().is_symlink(),
            "output must be a regular file"
        );
        let output = fs::canonicalize(&args.output)?;
        for source in std::iter::once(&args.boot)
            .chain(args.kpimg.iter())
            .chain(args.kptools.iter())
        {
            ensure!(
                fs::canonicalize(source)? != output,
                "refusing to overwrite an input file"
            );
        }
        ensure!(
            args.force,
            "output already exists; use --force to replace it"
        );
    }
    Ok(())
}

fn asset_bytes(name: &str, override_path: Option<&Path>) -> Result<Vec<u8>> {
    if let Some(path) = override_path {
        return read_regular(path, MAX_TOOL);
    }
    #[cfg(target_os = "android")]
    let key = name.to_owned();
    #[cfg(not(target_os = "android"))]
    let key = format!("aarch64/{name}");
    Ok(crate::assets::get_asset_data(&key)
        .with_context(|| format!("missing {name}; run scripts/build_kernelpatch first"))?
        .into_owned())
}

fn drain(mut source: impl Read) -> io::Result<Vec<u8>> {
    let mut output = Vec::new();
    let mut chunk = [0; 8192];
    loop {
        let read = source.read(&mut chunk)?;
        if read == 0 {
            return Ok(output);
        }
        let keep = read.min(MAX_LOG.saturating_sub(output.len()));
        output.extend_from_slice(&chunk[..keep]);
    }
}

fn run_patcher(
    tool: &Path,
    input: &Path,
    runtime: &Path,
    output: &Path,
    timeout: Duration,
) -> Result<()> {
    let mut child = Command::new(tool)
        .arg("-p")
        .arg("-i")
        .arg(input)
        .arg("-k")
        .arg(runtime)
        .arg("-o")
        .arg(output)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .context("cannot execute static AArch64 kptools (requires an Android AArch64 host)")?;
    let stdout = child.stdout.take().context("missing patcher stdout")?;
    let stderr = child.stderr.take().context("missing patcher stderr")?;
    let out_thread = thread::spawn(move || drain(stdout));
    let err_thread = thread::spawn(move || drain(stderr));
    let start = Instant::now();
    let status = loop {
        match child.try_wait() {
            Ok(Some(status)) => break Ok(status),
            Ok(None) if start.elapsed() < timeout => thread::sleep(Duration::from_millis(25)),
            Ok(None) => {
                let _ = child.kill();
                let _ = child.wait();
                break Err(anyhow::anyhow!(
                    "kptools timed out after {} seconds",
                    timeout.as_secs()
                ));
            }
            Err(error) => {
                let _ = child.kill();
                let _ = child.wait();
                break Err(error.into());
            }
        }
    };
    let stdout = out_thread
        .join()
        .map_err(|_| anyhow::anyhow!("patcher stdout reader failed"))??;
    let stderr = err_thread
        .join()
        .map_err(|_| anyhow::anyhow!("patcher stderr reader failed"))??;
    print!("{}", String::from_utf8_lossy(&stdout));
    eprint!("{}", String::from_utf8_lossy(&stderr));
    ensure!(
        status?.success(),
        "kptools failed; output was not published"
    );
    Ok(())
}

pub fn patch_boot(args: &BootPatchKpimgArgs) -> Result<()> {
    patch_with(args, run_patcher)
}

fn patch_with(
    args: &BootPatchKpimgArgs,
    run: impl FnOnce(&Path, &Path, &Path, &Path, Duration) -> Result<()>,
) -> Result<()> {
    validate_output(args)?;
    println!("- Reading GKI boot image");
    let source = read_regular(&args.boot, MAX_BOOT)?;
    let boot = parse_boot(&source)?;
    let kernel = unpack_kernel(&boot)?;
    ensure!(
        marker(&kernel)?.is_none(),
        "kernel already contains kpimg; use the original unpatched GKI boot image"
    );
    let runtime = asset_bytes("kpimg", args.kpimg.as_deref())?;
    let tool = asset_bytes("kptools", args.kptools.as_deref())?;
    validate_kpimg(&runtime)?;
    validate_kptools(&tool)?;
    let parent = args
        .output
        .parent()
        .filter(|p| !p.as_os_str().is_empty())
        .unwrap_or_else(|| Path::new("."));
    ensure!(parent.is_dir(), "output directory does not exist");
    let parent = fs::canonicalize(parent)?;
    let work = tempfile::Builder::new()
        .prefix(".apkesu-kpimg-")
        .tempdir_in(&parent)?;
    let tool_path = work.path().join("kptools");
    let runtime_path = work.path().join("kpimg");
    let input_path = work.path().join("Image");
    let patched_path = work.path().join("Image.patched");
    fs::write(&tool_path, tool)?;
    fs::write(&runtime_path, &runtime)?;
    fs::write(&input_path, &kernel)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(work.path(), fs::Permissions::from_mode(0o700))?;
        fs::set_permissions(&tool_path, fs::Permissions::from_mode(0o700))?;
    }
    println!("- Checking built-in KPM ABI and injecting kpimg");
    run(
        &tool_path,
        &input_path,
        &runtime_path,
        &patched_path,
        Duration::from_secs(args.timeout_seconds),
    )?;
    let patched = read_regular(&patched_path, MAX_KERNEL)?;
    let offset = validate_patched(&kernel, &patched, &runtime)?;
    let expected = sha256::digest(&patched);
    let mut image_patcher = BootImagePatchOption::new(&boot);
    image_patcher.replace_kernel(Box::new(Cursor::new(patched)), false);
    let mut temporary = tempfile::NamedTempFile::new_in(&parent)?;
    image_patcher
        .patch(temporary.as_file_mut())
        .context("cannot repack boot image")?;
    temporary.as_file_mut().flush()?;
    temporary.as_file().sync_all()?;
    let repacked = read_regular(temporary.path(), MAX_BOOT)?;
    let verified = parse_boot(&repacked)?;
    ensure!(
        sha256::digest(unpack_kernel(&verified)?) == expected,
        "repacked kernel verification failed"
    );
    ensure!(
        boot.get_blocks()
            .get_ramdisk()
            .map(android_bootimg::parser::RamdiskImage::get_data)
            == verified
                .get_blocks()
                .get_ramdisk()
                .map(android_bootimg::parser::RamdiskImage::get_data),
        "ramdisk changed unexpectedly"
    );
    validate_output(args)?;
    if args.force {
        temporary.persist(&args.output).map_err(|e| e.error)?;
    } else {
        temporary
            .persist_noclobber(&args.output)
            .map_err(|e| e.error)?;
    }
    println!("- kpimg offset: 0x{offset:x}");
    println!("- Output: {}", args.output.display());
    println!("- SHA256: {}", sha256::digest(&repacked));
    println!(
        "- Image structure verified; AVB signatures were not regenerated. No partition was flashed."
    );
    Ok(())
}

pub fn print_info(args: &BootInfoKpimgArgs) -> Result<()> {
    let bytes = read_regular(&args.boot, MAX_BOOT)?;
    let boot = parse_boot(&bytes)?;
    let kernel = unpack_kernel(&boot)?;
    println!("kernel_arch=arm64\nkernel_size={}", kernel.len());
    if let Some(offset) = marker(&kernel)? {
        println!("kpimg_present=true\nkpimg_offset=0x{offset:x}");
    } else {
        println!("kpimg_present=false");
    }
    println!("runtime_boot_verified=false");
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn raw_kernel() -> Vec<u8> {
        let mut image = vec![0; 8192];
        image[..4].copy_from_slice(&0x1400_0010u32.to_le_bytes());
        image[16..24].copy_from_slice(&8192u64.to_le_bytes());
        image[56..60].copy_from_slice(b"ARM\x64");
        image
    }

    fn boot_image(kernel: &[u8]) -> Vec<u8> {
        let mut image = vec![0; 4096];
        image[..8].copy_from_slice(b"ANDROID!");
        image[8..12].copy_from_slice(&(kernel.len() as u32).to_le_bytes());
        image[12..16].copy_from_slice(&4u32.to_le_bytes());
        image[20..24].copy_from_slice(&1580u32.to_le_bytes());
        image[40..44].copy_from_slice(&3u32.to_le_bytes());
        image.extend_from_slice(kernel);
        image.resize((image.len() + 4095) & !4095, 0);
        image.extend_from_slice(b"cpio");
        image.resize((image.len() + 4095) & !4095, 0);
        image
    }

    fn runtime() -> Vec<u8> {
        let mut image = vec![0; 8192];
        image[..8].copy_from_slice(MAGIC);
        image[16..24].copy_from_slice(&2u64.to_le_bytes());
        image[4096..].fill(0xa5);
        image
    }

    fn tool() -> Vec<u8> {
        let mut image = vec![0; 128];
        image[..7].copy_from_slice(b"\x7fELF\x02\x01\x01");
        image[16..18].copy_from_slice(&2u16.to_le_bytes());
        image[18..20].copy_from_slice(&183u16.to_le_bytes());
        image[32..40].copy_from_slice(&64u64.to_le_bytes());
        image[54..56].copy_from_slice(&56u16.to_le_bytes());
        image[56..58].copy_from_slice(&1u16.to_le_bytes());
        image[64..68].copy_from_slice(&1u32.to_le_bytes());
        image[68] = 5;
        image[96..104].copy_from_slice(&128u64.to_le_bytes());
        image
    }

    fn injected(kernel: &[u8], runtime: &[u8]) -> Vec<u8> {
        let offset = (kernel.len() + 4095) & !4095;
        let mut image = kernel.to_vec();
        image.resize(offset, 0);
        image.extend_from_slice(runtime);
        image[offset + 72..offset + 80].copy_from_slice(&(kernel.len() as u64).to_le_bytes());
        image[offset + 80..offset + 88].copy_from_slice(&(runtime.len() as u64).to_le_bytes());
        image[offset + 104..offset + 112].copy_from_slice(&(offset as u64).to_le_bytes());
        image[offset + HEADER_BACKUP..offset + HEADER_BACKUP + 8].copy_from_slice(&kernel[..8]);
        image[..4].copy_from_slice(&(0x1400_0000 | ((offset as u32 + 4096) / 4)).to_le_bytes());
        image
    }

    fn fixture(dir: &Path) -> BootPatchKpimgArgs {
        let args = BootPatchKpimgArgs {
            boot: dir.join("boot.img"),
            output: dir.join("patched.img"),
            kpimg: Some(dir.join("kpimg")),
            kptools: Some(dir.join("kptools")),
            force: false,
            timeout_seconds: 1,
        };
        fs::write(&args.boot, boot_image(&raw_kernel())).unwrap();
        fs::write(args.kpimg.as_ref().unwrap(), runtime()).unwrap();
        fs::write(args.kptools.as_ref().unwrap(), tool()).unwrap();
        args
    }

    #[test]
    fn rejects_missing_directory_and_empty_inputs() {
        let dir = tempfile::tempdir().unwrap();
        assert!(read_regular(&dir.path().join("missing"), MAX_BOOT).is_err());
        assert!(read_regular(dir.path(), MAX_BOOT).is_err());
        let empty = dir.path().join("empty");
        fs::write(&empty, []).unwrap();
        assert!(read_regular(&empty, MAX_BOOT).is_err());
    }

    #[test]
    fn refuses_inputs_as_output_and_existing_output_without_force() {
        let dir = tempfile::tempdir().unwrap();
        let mut args = fixture(dir.path());
        fs::write(&args.output, b"keep").unwrap();
        assert!(validate_output(&args).is_err());
        args.force = true;
        assert!(validate_output(&args).is_ok());
        args.output.clone_from(&args.boot);
        assert!(validate_output(&args).is_err());
        args.output = args.kpimg.clone().unwrap();
        assert!(validate_output(&args).is_err());
    }

    #[test]
    fn rejects_init_boot_vendor_boot_and_non_arm64() {
        assert!(parse_boot(&boot_image(&[])).is_err());
        let mut vendor = boot_image(&raw_kernel());
        vendor[..8].copy_from_slice(b"VNDRBOOT");
        assert!(parse_boot(&vendor).is_err());
        let data = boot_image(&vec![0; 8192]);
        assert!(unpack_kernel(&parse_boot(&data).unwrap()).is_err());
        for size in [0, 7, 63, 4095] {
            assert!(parse_boot(&vec![0; size]).is_err());
        }
    }

    #[test]
    fn validates_tools_and_rejects_dynamic_or_wrong_architecture() {
        assert!(validate_kpimg(&runtime()).is_ok());
        assert!(validate_kpimg(&vec![0; 8192]).is_err());
        assert!(validate_kptools(&tool()).is_ok());
        let mut image = tool();
        image[64..68].copy_from_slice(&3u32.to_le_bytes());
        assert!(validate_kptools(&image).is_err());
        image = tool();
        image[18] = 62;
        assert!(validate_kptools(&image).is_err());
        image = tool();
        image[32..40].copy_from_slice(&u64::MAX.to_le_bytes());
        assert!(validate_kptools(&image).is_err());
    }

    #[test]
    fn rejects_wrong_entry_truncated_runtime_and_changed_code() {
        let kernel = raw_kernel();
        let runtime = runtime();
        let good = injected(&kernel, &runtime);
        assert!(validate_patched(&kernel, &good, &runtime).is_ok());
        let mut bad = good.clone();
        bad[..4].copy_from_slice(&kernel[..4]);
        assert!(validate_patched(&kernel, &bad, &runtime).is_err());
        bad = good.clone();
        bad.pop();
        assert!(validate_patched(&kernel, &bad, &runtime).is_err());
        bad = good;
        bad[8192 + 4096] ^= 1;
        assert!(validate_patched(&kernel, &bad, &runtime).is_err());
    }

    #[test]
    fn aligned_magic_without_matching_original_size_is_not_a_patch() {
        let mut kernel = raw_kernel();
        kernel[4096..4104].copy_from_slice(MAGIC);
        assert_eq!(marker(&kernel).unwrap(), None);
        kernel.insert(0, 0);
        assert_eq!(marker(&kernel).unwrap(), None);
    }

    #[test]
    fn patch_failure_keeps_existing_output_and_source() {
        let dir = tempfile::tempdir().unwrap();
        let mut args = fixture(dir.path());
        args.force = true;
        fs::write(&args.output, b"keep").unwrap();
        let source = fs::read(&args.boot).unwrap();
        assert!(
            patch_with(&args, |_, _, _, output, _| {
                fs::write(output, b"incomplete")?;
                anyhow::bail!("Native GKI KPM ABI missing")
            })
            .is_err()
        );
        assert_eq!(fs::read(&args.output).unwrap(), b"keep");
        assert_eq!(fs::read(&args.boot).unwrap(), source);
    }

    #[test]
    fn repacks_and_revalidates_synthetic_boot_without_changing_ramdisk() {
        let dir = tempfile::tempdir().unwrap();
        let args = fixture(dir.path());
        patch_with(&args, |_, input, runtime, output, _| {
            fs::write(output, injected(&fs::read(input)?, &fs::read(runtime)?))?;
            Ok(())
        })
        .unwrap();
        let data = fs::read(&args.output).unwrap();
        let boot = parse_boot(&data).unwrap();
        assert_eq!(boot.get_blocks().get_ramdisk().unwrap().get_data(), b"cpio");
        assert_eq!(marker(&unpack_kernel(&boot).unwrap()).unwrap(), Some(8192));
    }

    #[test]
    fn log_drain_remains_bounded_and_consumes_full_stream() {
        let bytes = vec![b'x'; MAX_LOG * 2];
        let mut source = Cursor::new(bytes);
        assert_eq!(drain(&mut source).unwrap().len(), MAX_LOG);
        assert_eq!(source.position(), (MAX_LOG * 2) as u64);
    }
}
