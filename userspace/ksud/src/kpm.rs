use anyhow::{Context, Result, bail, ensure};
use goblin::elf::{Elf, header, section_header};
use log::warn;
use serde_json::{Map, Value, json};
use sha256::digest;
use std::collections::{HashMap, HashSet};
use std::fmt::Write as _;
use std::fs::{self, DirBuilder, File, OpenOptions};
use std::io::{ErrorKind, Read, Write};
use std::os::fd::AsRawFd;
use std::os::unix::fs::{DirBuilderExt, OpenOptionsExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::process::{Command, Output};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use crate::kpm_backend::{
    BootLoadAction, KpmBackend, PendingBootAction, PendingMarkerAction, PolicyChangeAction,
};
use crate::{defs, kpatch_next, kpm_backend, ksu_uapi, ksucalls};

const MANIFEST_NAME: &str = "manifest.json";
const IMAGE_NAME: &str = "module.kpm";
const MANIFEST_VERSION: u64 = 1;
const MAX_IMAGE_SIZE: usize = 4 * 1024 * 1024;
const MAX_NAME_LEN: usize = 31;
const MAX_VERSION_LEN: usize = 31;
const MAX_LICENSE_LEN: usize = 31;
const MAX_AUTHOR_LEN: usize = 31;
const MAX_DESCRIPTION_LEN: usize = 511;
const MAX_ARGS_LEN: usize = 1023;
const MAX_SECTIONS: usize = 256;
const MAX_SECTION_MEMORY_SIZE: u64 = 8 * 1024 * 1024;
const MAX_INFO_SECTION_SIZE: u64 = 2048;
// Keep the boot marker capacity aligned with both Native GKI and KPatch-Next.
// The marker must be able to describe every module the loader can accept.
const MAX_PENDING_MODULES: usize = 64;
const PENDING_SCHEMA_VERSION: u64 = 1;
const MAX_KPATCH_PENDING_BYTES: usize = MAX_PENDING_MODULES * (MAX_NAME_LEN + 1);
const ELF64_EHDR_SIZE: u64 = 64;
const ELF64_SHDR_SIZE: u64 = 64;
const ELF64_ALIGNMENT: u64 = 8;
const ELF64_SYM_SIZE: u64 = 24;
const ELF64_RELA_SIZE: u64 = 24;
const KPATCH_BINARY: &str = "/data/adb/modules/KPatch-Next/bin/kpatch";
const KPATCH_KPM_DIR: &str = "/data/adb/kp-next/kpm";
const KPATCH_KPM_ARGS_SUFFIX: &str = ".args";
const KPATCH_DATA_DIR: &str = "/data/adb/kp-next";
const KPATCH_EXCLUDE_CONFIG_PATH: &str = "/data/adb/kp-next/package_config";
const KPATCH_BOOT_PENDING_PATH: &str = "/data/adb/ksu/kpm/.kpatch_boot_pending";
const KPATCH_ABI_VERSION: u32 = 1;
const KPATCH_MAX_LOADED: u32 = 64;
const ANDROID_USER_RANGE: u32 = 100_000;
const EXCLUDE_CONFIG_NAME: &str = ".package_config";
const BOOT_OPERATION_LOCK_TIMEOUT: Duration = Duration::from_secs(30);
const BOOT_OPERATION_LOCK_RETRY: Duration = Duration::from_millis(50);

fn backend() -> KpmBackend {
    kpm_backend::select(
        ksucalls::is_lkm_mode(),
        ksucalls::is_late_load(),
        ksucalls::is_native_kpm(),
    )
}

const fn backend_name(value: KpmBackend) -> &'static str {
    match value {
        KpmBackend::NativeGki => "native-gki",
        KpmBackend::KpatchNext => "kpatch-next",
        KpmBackend::Unsupported => "none",
    }
}

fn ensure_supported_backend() -> Result<KpmBackend> {
    let selected = backend();
    ensure!(
        selected != KpmBackend::Unsupported,
        "KPM is unavailable in this kernel mode"
    );
    Ok(selected)
}

const REQUIRED_NATIVE_CAPABILITIES: u32 = ksu_uapi::KSU_KPM_CAP_ABI
    | ksu_uapi::KSU_KPM_CAP_LOAD
    | ksu_uapi::KSU_KPM_CAP_UNLOAD
    | ksu_uapi::KSU_KPM_CAP_LIST
    | ksu_uapi::KSU_KPM_CAP_CONTROL
    | ksu_uapi::KSU_KPM_CAP_INFO
    | ksu_uapi::KSU_KPM_CAP_VERSION;

const fn native_caps_are_ready(caps: &ksucalls::NativeKpmCaps) -> bool {
    caps.abi_version >= 1
        && caps.loader_ready
        && caps.probe_error == 0
        && caps.capabilities & REQUIRED_NATIVE_CAPABILITIES == REQUIRED_NATIVE_CAPABILITIES
        && caps.max_image_size > 0
        && caps.max_loaded > 0
        && caps.max_name_len > 0
        && caps.max_args_len > 0
        && !caps.late_load
}

fn native_loader_ready() -> bool {
    ksucalls::get_native_kpm_caps().is_ok_and(|caps| native_caps_are_ready(&caps))
}

fn ensure_native_loader_ready() -> Result<()> {
    ensure!(
        native_loader_ready(),
        "Native GKI KPM loader is not attached; reboot with a KPM-enabled boot image"
    );
    Ok(())
}

fn kernel_page_size() -> u64 {
    let page_size = unsafe { libc::sysconf(libc::_SC_PAGESIZE) };
    if page_size > 0 {
        page_size as u64
    } else {
        4096
    }
}

#[derive(Clone, Debug)]
struct KpmMetadata {
    name: String,
    version: String,
    license: String,
    author: String,
    description: String,
}

#[derive(Clone, Debug)]
struct KpmManifest {
    metadata: KpmMetadata,
    sha256: String,
    args: String,
    enabled: bool,
    quarantined: bool,
    quarantine_reason: String,
    imported_at: String,
    source_name: String,
}

#[derive(Clone, Debug)]
struct KpmRuntimeInfo {
    name: String,
    version: String,
}

struct OperationLock(File);

impl Drop for OperationLock {
    fn drop(&mut self) {
        unsafe {
            libc::flock(self.0.as_raw_fd(), libc::LOCK_UN);
        }
    }
}

fn kpm_root() -> &'static Path {
    Path::new(defs::KPM_DIR)
}

fn ensure_private_dir(path: &Path) -> Result<()> {
    match fs::symlink_metadata(path) {
        Ok(metadata) => {
            ensure!(
                !metadata.file_type().is_symlink() && metadata.is_dir(),
                "refusing to use a symlink or non-directory: {}",
                path.display()
            );
            if metadata.permissions().mode() & 0o777 != 0o700 {
                fs::set_permissions(path, fs::Permissions::from_mode(0o700))?;
            }
        }
        Err(error) if error.kind() == ErrorKind::NotFound => {
            DirBuilder::new().recursive(true).mode(0o700).create(path)?;
        }
        Err(error) => return Err(error.into()),
    }
    Ok(())
}

fn ensure_kpm_root() -> Result<()> {
    ensure_private_dir(kpm_root())
}

fn open_operation_lock() -> Result<File> {
    ensure_kpm_root()?;
    let file = OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .mode(0o600)
        .custom_flags(libc::O_CLOEXEC | libc::O_NOFOLLOW)
        .open(defs::KPM_OPERATION_LOCK_PATH)
        .context("failed to open KPM operation lock")?;
    ensure!(
        file.metadata()?.is_file(),
        "KPM operation lock is not a file"
    );
    Ok(file)
}

fn lock_operation_file(file: File, nonblocking: bool) -> Result<OperationLock> {
    let flags = if nonblocking {
        libc::LOCK_EX | libc::LOCK_NB
    } else {
        libc::LOCK_EX
    };
    loop {
        let result = unsafe { libc::flock(file.as_raw_fd(), flags) };
        if result == 0 {
            return Ok(OperationLock(file));
        }
        let error = std::io::Error::last_os_error();
        if error.kind() == ErrorKind::Interrupted {
            continue;
        }
        bail!("failed to acquire KPM operation lock: {error}");
    }
}

fn acquire_operation_lock() -> Result<OperationLock> {
    lock_operation_file(open_operation_lock()?, true)
}

fn acquire_boot_operation_lock() -> Result<OperationLock> {
    // Boot lifecycle events are one-shot, so retry lock contention rather than
    // dropping an event. Keep the wait bounded in case an ioctl never returns.
    let file = open_operation_lock()?;
    let deadline = Instant::now() + BOOT_OPERATION_LOCK_TIMEOUT;
    loop {
        let result = unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) };
        if result == 0 {
            return Ok(OperationLock(file));
        }
        let error = std::io::Error::last_os_error();
        if error.kind() == ErrorKind::Interrupted {
            continue;
        }
        if error.kind() != ErrorKind::WouldBlock {
            bail!("failed to acquire KPM boot operation lock: {error}");
        }
        if Instant::now() >= deadline {
            bail!(
                "timed out after {} seconds waiting for the KPM boot operation lock",
                BOOT_OPERATION_LOCK_TIMEOUT.as_secs()
            );
        }
        thread::sleep(BOOT_OPERATION_LOCK_RETRY);
    }
}

#[derive(Clone, Debug)]
struct ExcludedPackage {
    package: String,
    uid: u32,
}

fn exclude_config_path() -> PathBuf {
    kpm_root().join(EXCLUDE_CONFIG_NAME)
}

fn kpatch_exclude_config_path() -> &'static Path {
    Path::new(KPATCH_EXCLUDE_CONFIG_PATH)
}

fn validate_excluded_package(package: &str) -> Result<()> {
    ensure!(
        !package.is_empty() && package.len() <= 255,
        "invalid package name"
    );
    ensure!(
        package
            .bytes()
            .all(|byte| byte != b',' && !byte.is_ascii_control()),
        "invalid package name"
    );
    Ok(())
}

fn read_excluded_packages_from(path: &Path) -> Result<Vec<ExcludedPackage>> {
    let metadata = match fs::symlink_metadata(path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == ErrorKind::NotFound => return Ok(Vec::new()),
        Err(error) => return Err(error.into()),
    };
    ensure!(
        metadata.is_file() && !metadata.file_type().is_symlink(),
        "invalid KPM exclusion file: {}",
        path.display()
    );
    let mut content = String::new();
    File::open(path)
        .with_context(|| format!("failed to open {}", path.display()))?
        .read_to_string(&mut content)
        .with_context(|| format!("failed to read {}", path.display()))?;
    let mut packages = Vec::new();
    for line in content
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty())
    {
        let fields = line.split(',').collect::<Vec<_>>();
        let (package, uid_text) = if fields.len() >= 4 {
            (fields[0], fields[3])
        } else if fields.len() == 2 {
            (fields[0], fields[1])
        } else {
            continue;
        };
        let Ok(uid) = uid_text.parse::<u32>() else {
            continue;
        };
        let uid = uid % ANDROID_USER_RANGE;
        if validate_excluded_package(package).is_ok()
            && !packages
                .iter()
                .any(|item: &ExcludedPackage| item.package == package)
        {
            packages.push(ExcludedPackage {
                package: package.to_string(),
                uid,
            });
        }
    }
    Ok(packages)
}

fn read_excluded_packages() -> Result<Vec<ExcludedPackage>> {
    if backend() == KpmBackend::KpatchNext {
        let kpatch_path = kpatch_exclude_config_path();
        if kpatch_path.is_file() {
            return read_excluded_packages_from(kpatch_path);
        }
    }
    read_excluded_packages_from(&exclude_config_path())
}

fn should_sync_kpatch_excludes() -> bool {
    backend() == KpmBackend::KpatchNext
        && (Path::new(KPATCH_BINARY).is_file() || Path::new(KPATCH_DATA_DIR).is_dir())
}

fn ensure_kpatch_data_dir() -> Result<()> {
    ensure_private_dir(Path::new(KPATCH_DATA_DIR))
}

fn write_excluded_packages(packages: &[ExcludedPackage]) -> Result<()> {
    let mut manager_content = String::new();
    let mut kpatch_content = String::from("pkg,exclude,allow,uid\n");
    for item in packages {
        let _ = writeln!(manager_content, "{},1,0,{}", item.package, item.uid);
        let _ = writeln!(kpatch_content, "{},1,0,{}", item.package, item.uid);
    }
    write_atomic(&exclude_config_path(), manager_content.as_bytes())?;
    if should_sync_kpatch_excludes() {
        ensure_kpatch_data_dir()?;
        write_atomic(kpatch_exclude_config_path(), kpatch_content.as_bytes())?;
    }
    Ok(())
}

fn set_exclude_runtime(uid: u32, enabled: bool) -> Result<()> {
    ensure!(
        backend() == KpmBackend::KpatchNext,
        "KPM application exclusions are only supported by KPatch-Next"
    );
    if !kpatch_runtime_ready() {
        return Ok(());
    }
    let uid_text = uid.to_string();
    let state = if enabled { "1" } else { "0" };
    kpatch_command(&["exclude_set", &uid_text, state])?;
    Ok(())
}

pub fn set_excluded_package(package: &str, uid: u32, enabled: bool) -> Result<()> {
    ensure!(
        backend() == KpmBackend::KpatchNext,
        "KPM application exclusions are only supported by KPatch-Next"
    );
    let _lock = acquire_operation_lock()?;
    ensure_kpm_root()?;
    validate_excluded_package(package)?;
    let app_id = uid % ANDROID_USER_RANGE;
    ensure!(app_id > 0, "invalid application UID");
    set_exclude_runtime(app_id, enabled)?;
    let mut packages = read_excluded_packages()?;
    packages.retain(|item| item.package != package);
    if enabled {
        packages.push(ExcludedPackage {
            package: package.to_string(),
            uid: app_id,
        });
    }
    write_excluded_packages(&packages)?;
    println!(
        "KPM exclusion {} for {} ({app_id})",
        if enabled { "enabled" } else { "disabled" },
        package
    );
    Ok(())
}

pub fn print_exclude_list() -> Result<()> {
    ensure!(
        backend() == KpmBackend::KpatchNext,
        "KPM application exclusions are only supported by KPatch-Next"
    );
    ensure_kpm_root()?;
    let packages = read_excluded_packages()?;
    println!(
        "{}",
        json!(
            packages
                .iter()
                .map(|item| json!({
                    "package": item.package,
                    "uid": item.uid,
                    "active": true,
                }))
                .collect::<Vec<_>>()
        )
    );
    Ok(())
}

fn ensure_kpatch_kpm_dir() -> Result<()> {
    ensure_private_dir(Path::new(KPATCH_KPM_DIR))
}

fn kpatch_command(args: &[&str]) -> Result<Output> {
    let binary = Path::new(KPATCH_BINARY);
    let metadata = fs::symlink_metadata(binary).context("KPatch Next runtime is not installed")?;
    ensure!(
        metadata.is_file() && !metadata.file_type().is_symlink(),
        "KPatch runtime binary is invalid"
    );
    let old_path = std::env::var("PATH").unwrap_or_default();
    let output = Command::new(binary)
        .args(args)
        .env(
            "PATH",
            format!("/data/adb/modules/KPatch-Next/bin:{old_path}"),
        )
        .output()
        .with_context(|| format!("execute kpatch {}", args.join(" ")))?;
    if output.status.success() {
        return Ok(output);
    }
    let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
    let stdout = String::from_utf8_lossy(&output.stdout).trim().to_string();
    let detail = if stderr.is_empty() { stdout } else { stderr };
    bail!(
        "kpatch {} failed with {}{}",
        args.join(" "),
        output.status,
        if detail.is_empty() {
            String::new()
        } else {
            format!(": {detail}")
        }
    )
}

fn kpatch_runtime_ready() -> bool {
    kpatch_command(&["hello"])
        .is_ok_and(|output| !String::from_utf8_lossy(&output.stdout).trim().is_empty())
}

fn ensure_kpatch_runtime_ready() -> Result<()> {
    ensure!(
        kpatch_runtime_ready(),
        "KPatch Next runtime is not active; refusing to assume that no KPM is loaded"
    );
    Ok(())
}

fn kpatch_live_names() -> Result<Vec<String>> {
    ensure_kpatch_runtime_ready()?;
    let output = kpatch_command(&["kpm", "list"])?;
    Ok(String::from_utf8_lossy(&output.stdout)
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty())
        .map(ToOwned::to_owned)
        .collect())
}

fn kpatch_image_path(id: &str) -> PathBuf {
    Path::new(KPATCH_KPM_DIR).join(format!("{id}.kpm"))
}

fn kpatch_args_path(id: &str) -> PathBuf {
    Path::new(KPATCH_KPM_DIR).join(format!("{id}{KPATCH_KPM_ARGS_SUFFIX}"))
}

fn sync_kpatch_image(id: &str, manifest: &KpmManifest) -> Result<PathBuf> {
    ensure_kpatch_kpm_dir()?;
    let bytes = hash_and_validate_image(id, manifest)?;
    let path = kpatch_image_path(id);
    write_atomic(&path, &bytes)?;
    // KPatch-Next accepts the module arguments as the second load argument.
    // Keep them in a private sidecar so service.sh can restore the same
    // runtime configuration after a reboot without parsing JSON in shell.
    write_atomic(&kpatch_args_path(id), manifest.args.as_bytes())?;
    Ok(path)
}

fn unsync_kpatch_image(id: &str) -> Result<()> {
    let path = kpatch_image_path(id);
    remove_marker(&path, "KPatch KPM image")?;
    let args_path = kpatch_args_path(id);
    remove_marker(&args_path, "KPatch KPM arguments")?;
    Ok(())
}

fn kpatch_load(id: &str, manifest: &KpmManifest) -> Result<KpmRuntimeInfo> {
    ensure!(
        kpatch_runtime_ready(),
        "KPatch Next is installed but the boot kernel is not patched or still needs a reboot"
    );
    let path = sync_kpatch_image(id, manifest)?;
    let path = path
        .to_str()
        .context("KPatch KPM path is not valid UTF-8")?;
    let args = manifest.args.as_str();
    if args.is_empty() {
        kpatch_command(&["kpm", "load", path])?;
    } else {
        kpatch_command(&["kpm", "load", path, args])?;
    }
    Ok(KpmRuntimeInfo {
        name: manifest.metadata.name.clone(),
        version: manifest.metadata.version.clone(),
    })
}

fn unload_runtime(id: &str) -> Result<()> {
    match backend() {
        KpmBackend::NativeGki => {
            ensure_native_loader_ready()?;
            ksucalls::native_kpm_unload(id)
                .with_context(|| format!("unload Native GKI KPM '{id}'"))?;
        }
        KpmBackend::KpatchNext => {
            ensure_kpatch_runtime_ready()?;
            kpatch_command(&["kpm", "unload", id])?;
        }
        KpmBackend::Unsupported => bail!("KPM runtime is unavailable"),
    }
    Ok(())
}

/// Stop KPatch-owned KPMs before its module is disabled or removed. Without
/// this handoff, the Manager would immediately switch back to the legacy
/// backend while the old KPatch instances were still resident in the kernel.
pub fn stop_kpatch_runtime() -> Result<()> {
    ensure!(
        backend() == KpmBackend::KpatchNext,
        "KPatch-Next runtime is only available in LKM mode"
    );
    let _lock = acquire_operation_lock()?;
    ensure_kpatch_runtime_ready()?;
    for id in kpatch_live_names()? {
        kpatch_command(&["kpm", "unload", &id])
            .with_context(|| format!("failed to unload KPatch KPM '{id}'"))?;
    }
    remove_marker(
        Path::new(KPATCH_BOOT_PENDING_PATH),
        "KPatch Next KPM pending marker",
    )?;
    Ok(())
}

// The policy is deliberately kept next to the imported images so it survives
// Manager updates while remaining outside the module enumeration.
fn read_policy() -> Result<bool> {
    ensure_kpm_root()?;
    let path = Path::new(defs::KPM_POLICY_PATH);
    let metadata = match fs::symlink_metadata(path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == ErrorKind::NotFound => return Ok(true),
        Err(error) => return Err(error.into()),
    };
    ensure!(
        metadata.is_file() && !metadata.file_type().is_symlink(),
        "invalid KPM policy file"
    );
    let value: Value = serde_json::from_slice(&read_regular_file(path, "KPM policy")?)
        .context("invalid KPM policy JSON")?;
    let map = value.as_object().context("KPM policy is not an object")?;
    ensure!(
        map.get("schemaVersion").and_then(Value::as_u64) == Some(1),
        "unsupported KPM policy version"
    );
    map.get("enabled")
        .and_then(Value::as_bool)
        .context("KPM policy enabled state is missing")
}

fn validate_id(id: &str) -> Result<()> {
    ensure!(
        !id.is_empty() && id.len() <= MAX_NAME_LEN,
        "invalid KPM name"
    );
    ensure!(
        id.bytes().enumerate().all(|(index, byte)| {
            byte.is_ascii_alphanumeric() || (index > 0 && matches!(byte, b'.' | b'_' | b'-'))
        }),
        "KPM name contains unsupported characters"
    );
    ensure!(
        id.as_bytes()[0].is_ascii_alphanumeric(),
        "KPM name must start with an ASCII letter or digit"
    );
    Ok(())
}

fn parse_runtime_names(output: &str) -> Result<Vec<String>> {
    let mut names = Vec::new();
    let mut seen = HashSet::new();
    for line in output.lines() {
        let name = line.trim();
        if name.is_empty() {
            continue;
        }
        validate_id(name).with_context(|| format!("invalid runtime KPM name '{name}'"))?;
        ensure!(
            seen.insert(name.to_string()),
            "duplicate runtime KPM name '{name}'"
        );
        names.push(name.to_string());
    }
    Ok(names)
}

fn validate_metadata_field(value: &str, max_len: usize, field: &str) -> Result<()> {
    ensure!(!value.is_empty(), "KPM {field} is empty");
    ensure!(value.len() <= max_len, "KPM {field} is too long");
    ensure!(!value.as_bytes().contains(&0), "KPM {field} contains NUL");
    Ok(())
}

fn validate_args(args: &str) -> Result<()> {
    ensure!(args.len() <= MAX_ARGS_LEN, "KPM arguments are too long");
    ensure!(!args.as_bytes().contains(&0), "KPM arguments contain NUL");
    Ok(())
}

fn section_name<'a>(elf: &'a Elf<'a>, index: usize) -> Option<&'a str> {
    let section = elf.section_headers.get(index)?;
    elf.shdr_strtab.get_at(section.sh_name)
}

fn section_bytes<'a>(
    bytes: &'a [u8],
    section: &goblin::elf::section_header::SectionHeader,
) -> Result<&'a [u8]> {
    let offset = usize::try_from(section.sh_offset).context("KPM section offset overflow")?;
    let size = usize::try_from(section.sh_size).context("KPM section size overflow")?;
    let end = offset
        .checked_add(size)
        .context("KPM section range overflow")?;
    bytes
        .get(offset..end)
        .context("KPM section is outside the file")
}

fn parse_metadata(bytes: &[u8], elf: &Elf<'_>) -> Result<KpmMetadata> {
    let section = elf
        .section_headers
        .iter()
        .enumerate()
        .find(|(index, _)| section_name(elf, *index) == Some(".kpm.info"))
        .map(|(_, section)| section)
        .context(".kpm.info section is missing")?;
    ensure!(
        section.sh_flags & u64::from(section_header::SHF_ALLOC) != 0,
        ".kpm.info is not allocatable"
    );
    let data = section_bytes(bytes, section)?;
    ensure!(
        data.last() == Some(&0),
        "KPM metadata section is not NUL terminated"
    );
    let mut fields = HashMap::new();
    for raw in data.split(|byte| *byte == 0) {
        if raw.is_empty() {
            continue;
        }
        let separator = raw.iter().position(|byte| *byte == b'=');
        let separator = separator.context("KPM metadata entry has no '='")?;
        ensure!(
            separator > 0 && separator + 1 < raw.len(),
            "KPM metadata value is empty"
        );
        let key =
            std::str::from_utf8(&raw[..separator]).context("KPM metadata key is invalid UTF-8")?;
        let value = std::str::from_utf8(&raw[separator + 1..])
            .context("KPM metadata value is invalid UTF-8")?;
        ensure!(
            fields.insert(key.to_string(), value.to_string()).is_none(),
            "duplicate KPM metadata key"
        );
    }

    let metadata = KpmMetadata {
        name: fields
            .remove("name")
            .context("KPM name metadata is missing")?,
        version: fields
            .remove("version")
            .context("KPM version metadata is missing")?,
        license: fields
            .remove("license")
            .unwrap_or_else(|| "unknown".to_string()),
        author: fields
            .remove("author")
            .unwrap_or_else(|| "unknown".to_string()),
        description: fields.remove("description").unwrap_or_default(),
    };
    validate_id(&metadata.name)?;
    validate_metadata_field(&metadata.name, MAX_NAME_LEN, "name")?;
    validate_metadata_field(&metadata.version, MAX_VERSION_LEN, "version")?;
    validate_metadata_field(&metadata.license, MAX_LICENSE_LEN, "license")?;
    validate_metadata_field(&metadata.author, MAX_AUTHOR_LEN, "author")?;
    ensure!(
        metadata.description.len() <= MAX_DESCRIPTION_LEN,
        "KPM description is too long"
    );
    Ok(metadata)
}

fn parse_kpm(bytes: &[u8]) -> Result<KpmMetadata> {
    ensure!(
        !bytes.is_empty() && bytes.len() <= MAX_IMAGE_SIZE,
        "KPM file size is unsupported"
    );
    let elf = Elf::parse(bytes).context("invalid KPM ELF")?;
    ensure!(
        elf.header.e_type == header::ET_REL,
        "KPM must be an ET_REL ELF"
    );
    ensure!(
        elf.header.e_machine == header::EM_AARCH64,
        "KPM must target AArch64"
    );
    ensure!(
        elf.is_64 && elf.little_endian,
        "KPM must be a little-endian 64-bit ELF"
    );
    ensure!(
        elf.header.e_phoff == 0 && elf.header.e_phnum == 0 && elf.header.e_phentsize == 0,
        "KPM must not contain program headers"
    );
    ensure!(
        u64::from(elf.header.e_ehsize) == ELF64_EHDR_SIZE
            && u64::from(elf.header.e_shentsize) == ELF64_SHDR_SIZE
            && elf.header.e_shoff % ELF64_ALIGNMENT == 0,
        "KPM ELF section table is malformed"
    );
    ensure!(
        !elf.section_headers.is_empty() && elf.section_headers.len() <= MAX_SECTIONS,
        "KPM section table is unsupported"
    );
    ensure!(
        elf.header.e_shstrndx != section_header::SHN_UNDEF as u16
            && (elf.header.e_shstrndx as usize) < elf.section_headers.len(),
        "KPM section-name table index is invalid"
    );
    let section_string_table = &elf.section_headers[elf.header.e_shstrndx as usize];
    ensure!(
        section_string_table.sh_type == section_header::SHT_STRTAB,
        "KPM section-name table is invalid"
    );
    let section_string_data = section_bytes(bytes, section_string_table)?;
    ensure!(
        !section_string_data.is_empty() && section_string_data[0] == 0,
        "KPM section-name table is invalid"
    );

    let mut special_sections = HashSet::new();
    let mut symbol_section = None;
    let page_size = kernel_page_size();
    for (index, section) in elf.section_headers.iter().enumerate() {
        let name = section_name(&elf, index)
            .with_context(|| format!("section {index} has an invalid name"))?;
        if section.sh_type != section_header::SHT_NOBITS {
            let _ = section_bytes(bytes, section)
                .with_context(|| format!("section {name} is outside the KPM file"))?;
        }
        ensure!(
            section.sh_size <= MAX_SECTION_MEMORY_SIZE,
            "section {name} is too large"
        );
        ensure!(
            (section.sh_addralign == 0 || section.sh_addralign.is_power_of_two())
                && section.sh_addralign <= page_size,
            "section {name} has invalid alignment"
        );
        ensure!(
            section.sh_flags & u64::from(section_header::SHF_COMPRESSED) == 0,
            "compressed KPM sections are unsupported"
        );
        ensure!(
            section.sh_flags & u64::from(section_header::SHF_TLS) == 0,
            "TLS KPM sections are unsupported"
        );
        ensure!(
            section.sh_flags
                & (u64::from(section_header::SHF_ALLOC)
                    | u64::from(section_header::SHF_WRITE)
                    | u64::from(section_header::SHF_EXECINSTR))
                != (u64::from(section_header::SHF_ALLOC)
                    | u64::from(section_header::SHF_WRITE)
                    | u64::from(section_header::SHF_EXECINSTR)),
            "writable executable KPM sections are unsupported"
        );
        if matches!(
            name,
            ".kpm.info" | ".kpm.init" | ".kpm.exit" | ".kpm.ctl0" | ".kpm.ctl1" | ".kpm.event"
        ) {
            ensure!(
                special_sections.insert(name.to_string()),
                "duplicate KPM special section: {name}"
            );
        }
        if name == ".kpm.info" {
            ensure!(
                section.sh_type == section_header::SHT_PROGBITS
                    && section.sh_flags & u64::from(section_header::SHF_ALLOC) != 0
                    && section.sh_size > 0
                    && section.sh_size <= MAX_INFO_SECTION_SIZE,
                ".kpm.info has an invalid layout"
            );
        }
        if matches!(
            name,
            ".kpm.init" | ".kpm.exit" | ".kpm.ctl0" | ".kpm.ctl1" | ".kpm.event"
        ) {
            ensure!(
                section.sh_type == section_header::SHT_PROGBITS
                    && section.sh_flags & u64::from(section_header::SHF_ALLOC) != 0
                    && section.sh_size == std::mem::size_of::<u64>() as u64
                    && section.sh_addralign >= std::mem::size_of::<u64>() as u64,
                "KPM callback section has an invalid layout"
            );
        }
        if section.sh_type == section_header::SHT_REL {
            bail!("AArch64 REL sections are unsupported");
        }
        if section.sh_type == section_header::SHT_SYMTAB {
            ensure!(
                symbol_section.is_none()
                    && (section.sh_link as usize) < elf.section_headers.len()
                    && elf.section_headers[section.sh_link as usize].sh_type
                        == section_header::SHT_STRTAB
                    && section.sh_entsize == ELF64_SYM_SIZE
                    && section.sh_size >= ELF64_SYM_SIZE
                    && section.sh_size % ELF64_SYM_SIZE == 0
                    && section.sh_info > 0
                    && u64::from(section.sh_info) <= section.sh_size / ELF64_SYM_SIZE
                    && section.sh_offset % ELF64_ALIGNMENT == 0,
                "KPM symbol table is invalid"
            );
            symbol_section = Some(index);
        }
        if section.sh_type == section_header::SHT_RELA {
            ensure!(
                section.sh_entsize == ELF64_RELA_SIZE
                    && section.sh_size % ELF64_RELA_SIZE == 0
                    && (section.sh_info as usize) < elf.section_headers.len()
                    && (section.sh_link as usize) < elf.section_headers.len()
                    && section.sh_offset % ELF64_ALIGNMENT == 0,
                "KPM relocation section is invalid"
            );
        }
    }
    ensure!(symbol_section.is_some(), "KPM symbol table is missing");
    let symbol_section = symbol_section.expect("symbol section was checked above");
    let symbol_strings = &elf.section_headers[elf.section_headers[symbol_section].sh_link as usize];
    let symbol_string_data = section_bytes(bytes, symbol_strings)?;
    ensure!(
        !symbol_string_data.is_empty() && symbol_string_data[0] == 0,
        "KPM symbol string table is invalid"
    );
    ensure!(
        [".kpm.info", ".kpm.init", ".kpm.exit"]
            .iter()
            .all(|required| special_sections.contains(*required)),
        "required KPM callback sections are missing"
    );
    parse_metadata(bytes, &elf)
}

fn manifest_path(id: &str) -> PathBuf {
    kpm_root().join(id).join(MANIFEST_NAME)
}

fn image_path(id: &str) -> PathBuf {
    kpm_root().join(id).join(IMAGE_NAME)
}

fn validated_image_path(id: &str) -> Result<PathBuf> {
    validate_id(id)?;
    let path = image_path(id);
    let metadata = fs::symlink_metadata(&path)
        .with_context(|| format!("KPM image does not exist: {}", path.display()))?;
    ensure!(
        metadata.is_file() && !metadata.file_type().is_symlink(),
        "KPM image must be a regular file"
    );
    Ok(path)
}

fn value_string(map: &Map<String, Value>, key: &str, default: &str) -> String {
    map.get(key)
        .and_then(Value::as_str)
        .unwrap_or(default)
        .to_string()
}

fn read_regular_file(path: &Path, description: &str) -> Result<Vec<u8>> {
    let metadata = fs::symlink_metadata(path)
        .with_context(|| format!("{description} does not exist: {}", path.display()))?;
    ensure!(
        metadata.is_file() && !metadata.file_type().is_symlink(),
        "{description} must be a regular file: {}",
        path.display()
    );
    fs::read(path).with_context(|| format!("failed to read {description}: {}", path.display()))
}

fn read_manifest(id: &str) -> Result<KpmManifest> {
    validate_id(id)?;
    let directory = kpm_root().join(id);
    let metadata = fs::symlink_metadata(&directory)
        .with_context(|| format!("KPM directory does not exist: {id}"))?;
    ensure!(
        !metadata.file_type().is_symlink() && metadata.is_dir(),
        "invalid KPM directory"
    );
    let raw = read_regular_file(&manifest_path(id), "KPM manifest")?;
    let value: Value = serde_json::from_slice(&raw).context("invalid KPM manifest JSON")?;
    let map = value.as_object().context("KPM manifest is not an object")?;
    ensure!(
        map.get("schemaVersion").and_then(Value::as_u64) == Some(MANIFEST_VERSION),
        "unsupported KPM manifest version"
    );
    let metadata_value = map
        .get("metadata")
        .and_then(Value::as_object)
        .context("KPM manifest metadata is missing")?;
    let metadata = KpmMetadata {
        name: value_string(metadata_value, "name", ""),
        version: value_string(metadata_value, "version", ""),
        license: value_string(metadata_value, "license", "unknown"),
        author: value_string(metadata_value, "author", "unknown"),
        description: value_string(metadata_value, "description", ""),
    };
    validate_id(&metadata.name)?;
    ensure!(
        metadata.name == id,
        "KPM manifest name does not match directory"
    );
    validate_metadata_field(&metadata.version, MAX_VERSION_LEN, "version")?;
    validate_metadata_field(&metadata.license, MAX_LICENSE_LEN, "license")?;
    validate_metadata_field(&metadata.author, MAX_AUTHOR_LEN, "author")?;
    ensure!(
        metadata.description.len() <= MAX_DESCRIPTION_LEN,
        "KPM description is too long"
    );
    let args = value_string(map, "args", "");
    validate_args(&args)?;
    let sha256 = value_string(map, "sha256", "");
    ensure!(
        sha256.len() == 64 && sha256.bytes().all(|byte| byte.is_ascii_hexdigit()),
        "invalid KPM SHA-256"
    );
    Ok(KpmManifest {
        metadata,
        sha256,
        args,
        enabled: map.get("enabled").and_then(Value::as_bool).unwrap_or(false),
        quarantined: map
            .get("quarantined")
            .and_then(Value::as_bool)
            .unwrap_or(false),
        quarantine_reason: value_string(map, "quarantineReason", ""),
        imported_at: value_string(map, "importedAt", ""),
        source_name: value_string(map, "sourceName", ""),
    })
}

fn manifest_value(manifest: &KpmManifest) -> Value {
    json!({
        "schemaVersion": MANIFEST_VERSION,
        "metadata": {
            "name": manifest.metadata.name,
            "version": manifest.metadata.version,
            "license": manifest.metadata.license,
            "author": manifest.metadata.author,
            "description": manifest.metadata.description,
        },
        "sha256": manifest.sha256,
        "args": manifest.args,
        "enabled": manifest.enabled,
        "quarantined": manifest.quarantined,
        "quarantineReason": manifest.quarantine_reason,
        "importedAt": manifest.imported_at,
        "sourceName": manifest.source_name,
    })
}

fn write_atomic(path: &Path, bytes: &[u8]) -> Result<()> {
    let parent = path.parent().context("atomic file has no parent")?;
    let parent_metadata = fs::symlink_metadata(parent)
        .with_context(|| format!("atomic file parent does not exist: {}", parent.display()))?;
    ensure!(
        parent_metadata.is_dir() && !parent_metadata.file_type().is_symlink(),
        "atomic file parent is not a regular directory: {}",
        parent.display()
    );
    if let Ok(metadata) = fs::symlink_metadata(path) {
        ensure!(
            !metadata.file_type().is_symlink() && metadata.is_file(),
            "refusing to replace a non-regular atomic target: {}",
            path.display()
        );
    }
    let temp_path = parent.join(format!(
        ".{}.tmp-{}-{}",
        path.file_name()
            .and_then(|name| name.to_str())
            .unwrap_or("kpm"),
        std::process::id(),
        monotonic_nonce()
    ));
    let result = (|| -> Result<()> {
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&temp_path)?;
        file.write_all(bytes)?;
        file.sync_all()?;
        fs::rename(&temp_path, path)?;
        match File::open(parent) {
            Ok(directory) => {
                if let Err(error) = directory.sync_all() {
                    warn!(
                        "failed to sync atomic file parent {}: {error}",
                        parent.display()
                    );
                }
            }
            Err(error) => {
                warn!(
                    "failed to open atomic file parent {}: {error}",
                    parent.display()
                );
            }
        }
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temp_path);
    }
    result
}

fn write_policy(enabled: bool) -> Result<()> {
    write_atomic(
        Path::new(defs::KPM_POLICY_PATH),
        &serde_json::to_vec_pretty(&json!({
            "schemaVersion": 1,
            "enabled": enabled,
        }))?,
    )
}

fn write_manifest(id: &str, manifest: &KpmManifest) -> Result<()> {
    let bytes = serde_json::to_vec_pretty(&manifest_value(manifest))?;
    write_atomic(&manifest_path(id), &bytes)
}

fn monotonic_nonce() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |duration| duration.as_nanos())
}

fn current_boot_id() -> Result<String> {
    let value = fs::read_to_string("/proc/sys/kernel/random/boot_id")
        .context("failed to read Android boot id")?;
    let value = value.trim();
    ensure!(!value.is_empty(), "Android boot id is empty");
    Ok(value.to_string())
}

fn write_pending(ids: &[String], boot_id: &str) -> Result<()> {
    ensure!(
        !ids.is_empty() && ids.len() <= MAX_PENDING_MODULES,
        "invalid pending KPM batch"
    );
    ensure!(!boot_id.is_empty(), "pending KPM boot id is empty");
    for id in ids {
        validate_id(id)?;
    }
    ensure!(
        ids.iter().collect::<HashSet<_>>().len() == ids.len(),
        "pending KPM batch contains duplicate ids"
    );
    let pending = json!({
        "schemaVersion": PENDING_SCHEMA_VERSION,
        "ids": ids,
        "bootId": boot_id,
    });
    write_atomic(
        Path::new(defs::KPM_BOOT_PENDING_PATH),
        &serde_json::to_vec(&pending)?,
    )
}

fn remove_marker(path: &Path, description: &str) -> Result<()> {
    let metadata = match fs::symlink_metadata(path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(error.into()),
    };
    ensure!(
        metadata.is_file() || metadata.file_type().is_symlink(),
        "{description} is not a removable file: {}",
        path.display()
    );
    fs::remove_file(path)
        .with_context(|| format!("failed to remove {description}: {}", path.display()))?;
    Ok(())
}

fn clear_pending() -> Result<()> {
    remove_marker(
        Path::new(defs::KPM_BOOT_PENDING_PATH),
        "Native GKI KPM pending marker",
    )
}

fn clear_all_pending_markers() -> Result<()> {
    let native_result = clear_pending();
    let kpatch_result = remove_marker(
        Path::new(KPATCH_BOOT_PENDING_PATH),
        "KPatch Next KPM pending marker",
    );
    native_result?;
    kpatch_result
}

fn hash_and_validate_image(id: &str, manifest: &KpmManifest) -> Result<Vec<u8>> {
    let path = validated_image_path(id)?;
    let bytes = fs::read(path).context("failed to read KPM image")?;
    ensure!(bytes.len() <= MAX_IMAGE_SIZE, "KPM image is too large");
    ensure!(
        digest(&bytes) == manifest.sha256,
        "KPM image SHA-256 does not match manifest"
    );
    let metadata = parse_kpm(&bytes)?;
    ensure!(
        metadata.name == manifest.metadata.name,
        "KPM name changed after import"
    );
    ensure!(
        metadata.version == manifest.metadata.version,
        "KPM version changed after import"
    );
    Ok(bytes)
}

fn live_names() -> Result<Vec<String>> {
    match backend() {
        KpmBackend::NativeGki => {
            let caps = ksucalls::get_native_kpm_caps()
                .context("failed to query Native GKI KPM loader state")?;
            if !caps.loader_ready || caps.probe_error != 0 {
                // The kpimg bridge installs all ABI hooks transactionally. If
                // its version hook is not ready, none of the Manager-owned
                // Native KPMs can be resident through this backend.
                return Ok(Vec::new());
            }
            let output = ksucalls::native_kpm_list().context("failed to list Native GKI KPMs")?;
            parse_runtime_names(&output)
        }
        KpmBackend::KpatchNext => kpatch_live_names(),
        KpmBackend::Unsupported => Ok(Vec::new()),
    }
}

fn is_live(id: &str) -> Result<bool> {
    Ok(live_names()?.iter().any(|name| name == id))
}

fn ensure_kpm_management_available() -> Result<()> {
    ensure!(
        !ksucalls::is_late_load(),
        "KPM is disabled in jailbreak (late-load) mode"
    );
    match ensure_supported_backend()? {
        KpmBackend::NativeGki => {}
        KpmBackend::KpatchNext => ensure!(
            kpatch_next::is_enabled(),
            "KPM requires the KPatch-Next module to be enabled"
        ),
        KpmBackend::Unsupported => unreachable!(),
    }
    Ok(())
}

fn ensure_kpm_available() -> Result<()> {
    ensure_kpm_management_available()?;
    ensure!(
        read_policy()?,
        "KPM loading is disabled by user policy; enable it first"
    );
    Ok(())
}

fn load_manifest_now(
    id: &str,
    manifest: &KpmManifest,
    keep_pending_until_boot_completed: bool,
    pending_ids: Option<&[String]>,
) -> Result<KpmRuntimeInfo> {
    ensure_kpm_available()?;
    ensure!(manifest.enabled, "KPM is disabled");
    ensure!(
        !manifest.quarantined,
        "KPM is quarantined; enable it to retry"
    );
    let _ = hash_and_validate_image(id, manifest)?;
    if is_live(id)? {
        // A ksud process may restart during the same boot after the loader has
        // already accepted this KPM. Do not submit it twice, and keep any
        // existing pending marker until boot completion handles it.
        return Ok(KpmRuntimeInfo {
            name: manifest.metadata.name.clone(),
            version: manifest.metadata.version.clone(),
        });
    }
    let boot_id = current_boot_id()?;
    let single_pending_id;
    let pending_ids = if let Some(ids) = pending_ids {
        ids
    } else {
        single_pending_id = vec![id.to_string()];
        &single_pending_id
    };
    write_pending(pending_ids, &boot_id)?;
    let load_result = match backend() {
        KpmBackend::NativeGki => (|| -> Result<KpmRuntimeInfo> {
            ensure_native_loader_ready()?;
            let path = validated_image_path(id)?;
            let path = path
                .to_str()
                .context("Native KPM path is not valid UTF-8")?;
            ksucalls::native_kpm_load(path, &manifest.args)
                .with_context(|| format!("load Native GKI KPM '{id}'"))?;
            Ok(KpmRuntimeInfo {
                name: manifest.metadata.name.clone(),
                version: manifest.metadata.version.clone(),
            })
        })(),
        KpmBackend::KpatchNext => kpatch_load(id, manifest),
        KpmBackend::Unsupported => bail!("KPM backend is unavailable"),
    };
    match load_result {
        Ok(info) => {
            if kpm_backend::pending_marker_action(keep_pending_until_boot_completed)
                == PendingMarkerAction::ClearAfterOperation
                && let Err(clear_error) = clear_pending()
            {
                let rollback = unload_runtime(id);
                return match rollback {
                    Ok(()) => Err(clear_error.context(
                        "KPM loaded but its boot marker could not be cleared; runtime was rolled back",
                    )),
                    Err(rollback_error) => Err(anyhow::anyhow!(
                        "KPM loaded but marker cleanup failed: {clear_error:#}; runtime rollback also failed: {rollback_error:#}"
                    )),
                };
            }
            Ok(info)
        }
        Err(error) => {
            if kpm_backend::pending_marker_action(keep_pending_until_boot_completed)
                == PendingMarkerAction::ClearAfterOperation
                && let Err(clear_error) = clear_pending()
            {
                bail!(
                    "failed to load KPM {id} through {}: {error:#}; pending marker cleanup also failed: {clear_error:#}",
                    backend_name(backend())
                );
            }
            bail!(
                "failed to load KPM {id} through {}: {error:#}",
                backend_name(backend())
            )
        }
    }
}

fn quarantine(id: &str, reason: &str) -> Result<()> {
    let mut manifest = read_manifest(id)?;
    manifest.enabled = false;
    manifest.quarantined = true;
    manifest.quarantine_reason = reason.to_string();
    write_manifest(id, &manifest)
}

fn recover_kpatch_previous_boot() -> Result<()> {
    let path = Path::new(KPATCH_BOOT_PENDING_PATH);
    let metadata = match fs::symlink_metadata(path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(error.into()),
    };
    if metadata.file_type().is_symlink() {
        warn!("KPatch Next pending marker is a symlink; removing the marker");
        return remove_marker(path, "KPatch Next pending marker");
    }
    ensure!(
        metadata.is_file(),
        "KPatch Next pending marker is not a regular file"
    );
    let content =
        fs::read_to_string(path).context("failed to read KPatch Next boot pending marker")?;
    if content.len() > MAX_KPATCH_PENDING_BYTES {
        warn!("KPatch Next pending marker is too large; clearing it");
        return remove_marker(path, "KPatch Next pending marker");
    }
    let mut ids = Vec::new();
    for raw_id in content.lines().map(str::trim).filter(|id| !id.is_empty()) {
        if let Err(error) = validate_id(raw_id) {
            warn!("ignoring invalid KPatch KPM pending id '{raw_id}': {error:#}");
            continue;
        }
        if !ids.iter().any(|id| id == raw_id) {
            ids.push(raw_id.to_string());
        }
        if ids.len() >= MAX_PENDING_MODULES {
            break;
        }
    }
    if ids.is_empty() {
        warn!("KPatch Next boot pending marker is empty; clearing it");
        return remove_marker(path, "KPatch Next pending marker");
    }
    warn!(
        "previous boot stopped while {} KPatch KPM(s) were active; quarantining them",
        ids.len()
    );
    for id in ids {
        if let Err(error) = quarantine(
            &id,
            "previous boot did not complete after KPatch Next KPM load",
        ) {
            warn!("failed to quarantine KPatch KPM '{id}': {error:#}");
        }
        if let Err(error) = unsync_kpatch_image(&id) {
            warn!("failed to remove KPatch KPM '{id}' after recovery: {error:#}");
        }
    }
    remove_marker(path, "KPatch Next pending marker")
}

fn recover_native_previous_boot() -> Result<()> {
    let path = Path::new(defs::KPM_BOOT_PENDING_PATH);
    let metadata = match fs::symlink_metadata(path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(error.into()),
    };
    if metadata.file_type().is_symlink() {
        warn!("Native GKI pending marker is a symlink; removing the marker");
        return remove_marker(path, "Native GKI pending marker");
    }
    ensure!(
        metadata.is_file(),
        "Native GKI pending marker is not a regular file"
    );
    let value: Value = match serde_json::from_slice(
        &fs::read(path).context("failed to read Native GKI KPM boot marker")?,
    ) {
        Ok(value) => value,
        Err(error) => {
            warn!("invalid Native GKI KPM boot marker; clearing it: {error}");
            remove_marker(path, "Native GKI pending marker")?;
            return Ok(());
        }
    };
    let schema = value.get("schemaVersion").and_then(Value::as_u64);
    let boot_id = value.get("bootId").and_then(Value::as_str);
    let ids = value.get("ids").and_then(Value::as_array);
    let (Some(PENDING_SCHEMA_VERSION), Some(boot_id), Some(ids)) = (schema, boot_id, ids) else {
        warn!("Native GKI KPM boot marker is missing required fields; clearing it");
        remove_marker(path, "Native GKI pending marker")?;
        return Ok(());
    };
    if ids.is_empty() || ids.len() > MAX_PENDING_MODULES || boot_id.is_empty() {
        warn!("Native GKI KPM boot marker has invalid bounds; clearing it");
        remove_marker(path, "Native GKI pending marker")?;
        return Ok(());
    }
    let current_boot = match current_boot_id() {
        Ok(current_boot) => current_boot,
        Err(error) => {
            warn!("cannot read current boot id; preserving Native GKI pending marker: {error:#}");
            return Ok(());
        }
    };
    match kpm_backend::pending_boot_action(true, current_boot == boot_id) {
        PendingBootAction::KeepCurrentBoot => {
            warn!(
                "Native GKI pending marker belongs to the current boot; keeping it for boot completion"
            );
            return Ok(());
        }
        PendingBootAction::QuarantinePreviousBoot => {}
        PendingBootAction::ClearInvalid => unreachable!(),
    }

    let mut normalized_ids = Vec::new();
    for value in ids {
        let Some(id) = value.as_str() else {
            warn!("ignoring non-string Native GKI KPM pending id");
            continue;
        };
        if let Err(error) = validate_id(id) {
            warn!("ignoring invalid Native GKI KPM pending id '{id}': {error:#}");
            continue;
        }
        if !normalized_ids.iter().any(|item| item == id) {
            normalized_ids.push(id.to_string());
        }
    }
    for id in normalized_ids {
        if let Err(error) = quarantine(
            &id,
            "previous boot did not complete after Native GKI KPM load",
        ) {
            warn!("failed to quarantine Native GKI KPM '{id}': {error:#}");
        }
    }
    remove_marker(path, "Native GKI pending marker")
}

/// Synchronize enabled KPM images to the KPatch-Next service directory before
/// enabling the backend. The canonical manifests remain under ApkeSU so the
/// Manager keeps one source of truth.
fn migrate_to_kpatch_next_inner() -> Result<()> {
    ensure!(
        backend() == KpmBackend::KpatchNext,
        "KPatch-Next synchronization is only available in LKM mode"
    );
    ensure!(
        !ksucalls::is_late_load(),
        "KPatch Next is disabled in jailbreak (late-load) mode"
    );
    ensure_kpm_root()?;
    ensure_kpatch_kpm_dir()?;
    for entry in fs::read_dir(kpm_root())? {
        let entry = entry?;
        let id = entry.file_name().to_string_lossy().into_owned();
        if id.starts_with('.') || entry.file_type()?.is_symlink() {
            continue;
        }
        match read_manifest(&id) {
            Ok(manifest) if manifest.enabled && !manifest.quarantined => {
                if let Err(error) = sync_kpatch_image(&id, &manifest) {
                    warn!("failed to synchronize KPM '{id}' with KPatch Next: {error:#}");
                    if let Err(quarantine_error) =
                        quarantine(&id, &format!("KPatch synchronization failed: {error:#}"))
                    {
                        warn!("failed to quarantine KPM '{id}': {quarantine_error:#}");
                    }
                    if let Err(cleanup_error) = unsync_kpatch_image(&id) {
                        warn!("failed to remove stale KPatch KPM '{id}': {cleanup_error:#}");
                    }
                }
            }
            Ok(_) => unsync_kpatch_image(&id)?,
            Err(error) => {
                warn!("ignoring invalid KPM '{id}' during KPatch migration: {error:#}");
                unsync_kpatch_image(&id)?;
            }
        }
    }

    clear_pending()?;
    Ok(())
}

fn unsync_all_kpatch_images() -> Result<()> {
    ensure_kpm_root()?;
    let mut failures = Vec::new();
    for entry in fs::read_dir(kpm_root())? {
        let entry = entry?;
        let id = entry.file_name().to_string_lossy().into_owned();
        if id.starts_with('.') || entry.file_type()?.is_symlink() {
            continue;
        }
        if let Err(error) = unsync_kpatch_image(&id) {
            warn!("failed to remove synchronized KPatch KPM '{id}': {error:#}");
            failures.push(format!("{id}: {error:#}"));
        }
    }
    ensure!(
        failures.is_empty(),
        "failed to remove one or more synchronized KPatch KPM images: {}",
        failures.join("; ")
    );
    Ok(())
}

pub fn migrate_to_kpatch_next() -> Result<()> {
    let _lock = acquire_operation_lock()?;
    migrate_to_kpatch_next_inner()
}

/// Remove synchronized KPM files when KPatch-Next installation fails before
/// its service can own them.
pub fn cleanup_kpatch_after_install_failure() -> Result<()> {
    if backend() != KpmBackend::KpatchNext {
        return Ok(());
    }
    let _lock = acquire_operation_lock()?;
    let unsync_result = unsync_all_kpatch_images();
    let marker_result = clear_pending();
    unsync_result?;
    marker_result
}

fn recover_boot_state_inner() {
    if let Err(error) = ensure_kpm_root() {
        warn!("KPM boot recovery could not prepare storage: {error:#}");
        return;
    }
    let selected = backend();
    match selected {
        KpmBackend::NativeGki => {
            if let Err(error) = recover_native_previous_boot() {
                warn!("Native GKI KPM boot recovery failed: {error:#}");
            }
            if let Err(error) = remove_marker(
                Path::new(KPATCH_BOOT_PENDING_PATH),
                "stale KPatch Next pending marker",
            ) {
                warn!("failed to clear stale KPatch Next marker: {error:#}");
            }
        }
        KpmBackend::KpatchNext => {
            if let Err(error) = recover_kpatch_previous_boot() {
                warn!("KPatch Next KPM boot recovery failed: {error:#}");
            }
            if let Err(error) = clear_pending() {
                warn!("failed to clear stale Native GKI marker: {error:#}");
            }
        }
        KpmBackend::Unsupported => {
            if let Err(error) = clear_pending() {
                warn!("failed to clear stale Native GKI marker: {error:#}");
            }
            if let Err(error) = remove_marker(
                Path::new(KPATCH_BOOT_PENDING_PATH),
                "stale KPatch Next pending marker",
            ) {
                warn!("failed to clear stale KPatch Next marker: {error:#}");
            }
        }
    }
}

pub fn recover_boot_state() {
    let _lock = match acquire_boot_operation_lock() {
        Ok(lock) => lock,
        Err(error) => {
            warn!("KPM boot recovery could not acquire its operation lock: {error:#}");
            return;
        }
    };
    recover_boot_state_inner();
}

pub fn load_enabled_at_boot() {
    let _lock = match acquire_boot_operation_lock() {
        Ok(lock) => lock,
        Err(error) => {
            warn!("KPM boot loading could not acquire its operation lock: {error:#}");
            return;
        }
    };
    recover_boot_state_inner();
    let policy_enabled = match read_policy() {
        Ok(enabled) => enabled,
        Err(error) => {
            warn!("KPM boot loading skipped because the policy is invalid: {error:#}");
            return;
        }
    };
    let selected = backend();
    let native_caps = if selected == KpmBackend::NativeGki {
        match ksucalls::get_native_kpm_caps() {
            Ok(caps) => Some(caps),
            Err(error) => {
                warn!("Native GKI KPM capability query failed at boot: {error:#}");
                None
            }
        }
    } else {
        None
    };
    let native_ready = native_caps.as_ref().is_some_and(native_caps_are_ready);
    match kpm_backend::boot_load_action(selected, policy_enabled, native_ready) {
        BootLoadAction::DisabledByPolicy => {
            warn!("KPM boot loading skipped because the global policy is disabled");
            return;
        }
        BootLoadAction::DelegateToKpatchNext | BootLoadAction::Unsupported => {
            return;
        }
        BootLoadAction::WaitForNativeLoader => {
            warn!("Native GKI KPM loader is unavailable; enabled KPMs remain unloaded");
            return;
        }
        BootLoadAction::LoadNative => {}
    }
    let max_loaded = native_caps.as_ref().map_or(0, |caps| {
        kpm_backend::native_boot_batch_limit(caps.max_loaded, MAX_PENDING_MODULES)
    });
    if max_loaded == 0 {
        warn!("Native GKI KPM loader reported an invalid zero module limit");
        return;
    }
    let entries = match fs::read_dir(kpm_root()) {
        Ok(entries) => entries,
        Err(error) => {
            warn!("failed to enumerate Native GKI KPMs at boot: {error:#}");
            return;
        }
    };
    let mut candidates = Vec::new();
    for entry in entries.flatten() {
        let id = entry.file_name().to_string_lossy().into_owned();
        let file_type = match entry.file_type() {
            Ok(file_type) => file_type,
            Err(error) => {
                warn!("failed to inspect KPM entry '{id}' at boot: {error:#}");
                continue;
            }
        };
        if id.starts_with('.') || file_type.is_symlink() || !file_type.is_dir() {
            continue;
        }
        let manifest = match read_manifest(&id) {
            Ok(manifest) => manifest,
            Err(error) => {
                warn!("ignoring invalid Native GKI KPM '{id}' at boot: {error:#}");
                continue;
            }
        };
        if manifest.enabled && !manifest.quarantined {
            candidates.push((id, manifest));
        }
    }
    candidates.sort_by(|left, right| left.0.cmp(&right.0));

    let current_live = match ksucalls::native_kpm_list() {
        Ok(names) => match parse_runtime_names(&names) {
            Ok(names) => names.into_iter().collect::<HashSet<_>>(),
            Err(error) => {
                warn!("Native GKI KPM boot loading returned an invalid module list: {error:#}");
                return;
            }
        },
        Err(error) => {
            warn!("Native GKI KPM boot loading could not list current modules: {error:#}");
            return;
        }
    };
    let current_loaded = match ksucalls::native_kpm_num() {
        Ok(count) => count as u32,
        Err(error) => {
            warn!("Native GKI KPM boot loading could not read the current module count: {error:#}");
            return;
        }
    };
    let advertised_max = native_caps.as_ref().map_or(0, |caps| caps.max_loaded);
    if advertised_max == 0 || current_loaded > advertised_max {
        warn!(
            "Native GKI KPM loader returned an invalid count {current_loaded} for a maximum of {advertised_max}"
        );
        return;
    }
    if current_live.len() != current_loaded as usize {
        warn!(
            "Native GKI KPM loader returned {} names but a count of {current_loaded}; refusing an inconsistent boot batch",
            current_live.len()
        );
        return;
    }

    let (already_live, mut pending): (Vec<_>, Vec<_>) = candidates
        .into_iter()
        .partition(|(id, _)| current_live.contains(id));
    let new_load_limit = native_caps.as_ref().map_or(0, |caps| {
        kpm_backend::native_boot_new_load_limit(
            caps.max_loaded,
            current_loaded,
            MAX_PENDING_MODULES,
            already_live.len(),
        )
    });
    let overflow = if pending.len() > new_load_limit {
        pending.split_off(new_load_limit)
    } else {
        Vec::new()
    };
    let mut candidates = already_live;
    candidates.extend(pending);
    candidates.sort_by(|left, right| left.0.cmp(&right.0));
    for (id, _) in overflow {
        let reason = format!(
            "Native GKI boot load limit exceeded (loader={max_loaded}, active={current_loaded}, safety={MAX_PENDING_MODULES})"
        );
        warn!("quarantining Native GKI KPM '{id}': {reason}");
        if let Err(error) = quarantine(&id, &reason) {
            warn!("failed to quarantine overflow Native GKI KPM '{id}': {error:#}");
        }
    }
    if candidates.is_empty() {
        return;
    }

    let pending_ids = candidates
        .iter()
        .map(|(id, _)| id.clone())
        .collect::<Vec<_>>();
    let boot_id = match current_boot_id() {
        Ok(boot_id) => boot_id,
        Err(error) => {
            warn!("Native GKI KPM boot loading cannot create a recovery marker: {error:#}");
            return;
        }
    };
    if let Err(error) = write_pending(&pending_ids, &boot_id) {
        warn!(
            "Native GKI KPM boot loading skipped because its recovery marker could not be written: {error:#}"
        );
        return;
    }

    for (id, manifest) in candidates {
        if let Err(error) = load_manifest_now(&id, &manifest, true, Some(&pending_ids)) {
            warn!("failed to load Native GKI KPM '{id}' at boot: {error:#}");
            if let Err(quarantine_error) =
                quarantine(&id, &format!("Native GKI boot load failed: {error:#}"))
            {
                warn!("failed to quarantine Native GKI KPM '{id}': {quarantine_error:#}");
            }
        }
    }
}

pub fn mark_boot_completed() {
    let _lock = match acquire_boot_operation_lock() {
        Ok(lock) => lock,
        Err(error) => {
            warn!("KPM boot-completed cleanup could not acquire its operation lock: {error:#}");
            return;
        }
    };
    match backend() {
        KpmBackend::NativeGki => {
            if let Err(error) = clear_pending() {
                warn!("failed to clear Native GKI pending marker: {error:#}");
            }
            if let Err(error) = remove_marker(
                Path::new(KPATCH_BOOT_PENDING_PATH),
                "stale KPatch Next pending marker",
            ) {
                warn!("failed to clear stale KPatch Next marker: {error:#}");
            }
        }
        KpmBackend::KpatchNext => {
            if let Err(error) = remove_marker(
                Path::new(KPATCH_BOOT_PENDING_PATH),
                "KPatch Next pending marker",
            ) {
                warn!("failed to clear KPatch Next pending marker: {error:#}");
            }
            if let Err(error) = clear_pending() {
                warn!("failed to clear stale Native GKI marker: {error:#}");
            }
        }
        KpmBackend::Unsupported => {
            if let Err(error) = clear_pending() {
                warn!("failed to clear stale Native GKI marker: {error:#}");
            }
            if let Err(error) = remove_marker(
                Path::new(KPATCH_BOOT_PENDING_PATH),
                "stale KPatch Next pending marker",
            ) {
                warn!("failed to clear stale KPatch Next marker: {error:#}");
            }
        }
    }
}

pub fn print_caps() {
    let late_load = ksucalls::is_late_load();
    let policy = read_policy();
    let policy_enabled = policy.as_ref().is_ok_and(|enabled| *enabled);
    match backend() {
        KpmBackend::NativeGki => {
            let caps = ksucalls::get_native_kpm_caps();
            let (abi_version, capabilities, max_image_size, max_loaded, loader_ready, probe_error) =
                caps.as_ref()
                    .map_or((0, 0, 0, 0, false, -libc::EOPNOTSUPP), |caps| {
                        (
                            caps.abi_version,
                            caps.capabilities,
                            caps.max_image_size,
                            caps.max_loaded,
                            caps.loader_ready,
                            caps.probe_error,
                        )
                    });
            let loaded_count = if loader_ready {
                ksucalls::native_kpm_num()
                    .ok()
                    .and_then(|count| usize::try_from(count).ok())
            } else {
                None
            };
            let disabled_reason = if late_load {
                "jailbreak (late-load) mode"
            } else if policy.is_err() {
                "invalid KPM policy"
            } else if !policy_enabled {
                "disabled by user policy"
            } else if caps.is_err() || !loader_ready {
                "Native GKI KPM loader is not attached"
            } else {
                ""
            };
            println!(
                "{}",
                json!({
                    "backend": backend_name(KpmBackend::NativeGki),
                    "managementAvailable": !late_load && caps.is_ok(),
                    "abiVersion": abi_version,
                    "capabilities": capabilities,
                    "maxImageSize": max_image_size,
                    "maxLoaded": max_loaded,
                    "loadedCount": loaded_count,
                    "kernelSupported": loader_ready,
                    "loaderReady": loader_ready,
                    "probeError": probe_error,
                    "policyEnabled": policy_enabled,
                    "lateLoad": late_load,
                    "supported": loader_ready && policy_enabled && !late_load,
                    "disabledReason": disabled_reason,
                    "policyError": policy.as_ref().err().map(|error| format!("{error:#}")),
                })
            );
        }
        KpmBackend::KpatchNext => {
            let module_enabled = kpatch_next::is_enabled();
            let kernel_supported = kpatch_runtime_ready();
            let loaded_count = if kernel_supported {
                kpatch_live_names().ok().map(|names| names.len())
            } else {
                None
            };
            let disabled_reason = if late_load {
                "jailbreak (late-load) mode"
            } else if policy.is_err() {
                "invalid KPM policy"
            } else if !policy_enabled {
                "disabled by user policy"
            } else if !module_enabled {
                "KPatch-Next is not enabled"
            } else if !kernel_supported {
                "KPatch-Next runtime is not active; reboot may be required"
            } else {
                ""
            };
            println!(
                "{}",
                json!({
                    "backend": backend_name(KpmBackend::KpatchNext),
                    "managementAvailable": module_enabled && !late_load,
                    "abiVersion": KPATCH_ABI_VERSION,
                    "capabilities": i32::from(kernel_supported),
                    "maxImageSize": MAX_IMAGE_SIZE,
                    "maxLoaded": KPATCH_MAX_LOADED,
                    "loadedCount": loaded_count,
                    "kernelSupported": kernel_supported,
                    "loaderReady": kernel_supported,
                    "policyEnabled": policy_enabled,
                    "lateLoad": late_load,
                    "supported": module_enabled && kernel_supported && policy_enabled && !late_load,
                    "disabledReason": disabled_reason,
                    "policyError": policy.as_ref().err().map(|error| format!("{error:#}")),
                })
            );
        }
        KpmBackend::Unsupported => println!(
            "{}",
            json!({
                "backend": backend_name(KpmBackend::Unsupported),
                "managementAvailable": false,
                "capabilities": 0,
                "loadedCount": null,
                "kernelSupported": false,
                "loaderReady": false,
                "policyEnabled": policy_enabled,
                "lateLoad": late_load,
                "supported": false,
                "disabledReason": "KPM is unavailable in this kernel mode",
            })
        ),
    }
}

pub fn print_policy() {
    print_caps();
}

fn disable_policy_inner(selected: KpmBackend, current: bool) -> Result<()> {
    if current {
        // Persist fail-closed state before touching a live runtime. A crash or
        // failed unload must not make ksud restore modules on the next boot.
        write_policy(false)?;
    }

    let mut failures = Vec::new();
    if let Err(error) = clear_all_pending_markers() {
        failures.push(format!("pending marker cleanup failed: {error:#}"));
    }

    let runtime = live_names();
    let action = kpm_backend::policy_change_action(current, false, runtime.is_ok());
    match &runtime {
        Ok(ids) => {
            for id in ids {
                if let Err(error) = unload_runtime(id) {
                    failures.push(format!("failed to unload '{id}': {error:#}"));
                }
            }
        }
        Err(error) => warn!("KPM runtime state is unavailable while disabling policy: {error:#}"),
    }

    // KPatch-Next owns its boot loading. Remove synchronized copies even if
    // policy was already disabled, otherwise stale KPMs can return on reboot.
    if selected == KpmBackend::KpatchNext
        && let Err(error) = unsync_all_kpatch_images()
    {
        failures.push(format!("KPatch boot image cleanup failed: {error:#}"));
    }

    ensure!(
        failures.is_empty(),
        "KPM policy is disabled persistently, but cleanup is incomplete: {}",
        failures.join("; ")
    );
    match action {
        PolicyChangeAction::DisableUnknownRuntime => println!(
            "KPM policy disabled persistently; runtime state is unavailable, so reboot to guarantee live modules are unloaded"
        ),
        PolicyChangeAction::DisableKnownRuntime => println!(
            "KPM policy {}",
            if current {
                "disabled"
            } else {
                "is already disabled; runtime state reconciled"
            }
        ),
        PolicyChangeAction::NoChange | PolicyChangeAction::Enable => unreachable!(),
    }
    Ok(())
}

fn set_policy_inner(enabled: bool) -> Result<()> {
    let selected = ensure_supported_backend()?;
    ensure_kpm_root()?;
    let current = read_policy()?;
    if !enabled {
        return disable_policy_inner(selected, current);
    }

    ensure_kpm_management_available()?;
    if kpm_backend::policy_change_action(current, true, true) == PolicyChangeAction::NoChange {
        println!("KPM policy is already enabled");
        return Ok(());
    }

    if selected == KpmBackend::KpatchNext
        && let Err(error) = migrate_to_kpatch_next_inner()
    {
        let cleanup = unsync_all_kpatch_images();
        return match cleanup {
            Ok(()) => Err(error.context("failed to prepare KPatch KPM images")),
            Err(cleanup_error) => Err(anyhow::anyhow!(
                "failed to prepare KPatch KPM images: {error:#}; rollback cleanup also failed: {cleanup_error:#}"
            )),
        };
    }
    if let Err(error) = write_policy(true) {
        if selected == KpmBackend::KpatchNext
            && let Err(cleanup_error) = unsync_all_kpatch_images()
        {
            return Err(anyhow::anyhow!(
                "failed to enable KPM policy: {error:#}; KPatch image rollback also failed: {cleanup_error:#}"
            ));
        }
        return Err(error);
    }

    let runtime_ready = match selected {
        KpmBackend::NativeGki => native_loader_ready(),
        KpmBackend::KpatchNext => kpatch_runtime_ready(),
        KpmBackend::Unsupported => false,
    };
    if runtime_ready {
        let mut ids = fs::read_dir(kpm_root())?
            .flatten()
            .filter_map(|entry| {
                let id = entry.file_name().to_str()?.to_owned();
                let file_type = entry.file_type().ok()?;
                (!id.starts_with('.') && file_type.is_dir() && !file_type.is_symlink())
                    .then_some(id)
            })
            .collect::<Vec<_>>();
        ids.sort();
        let mut failures = Vec::new();
        for id in ids {
            let manifest = match read_manifest(&id) {
                Ok(manifest) => manifest,
                Err(error) => {
                    let reason = format!("invalid manifest: {error:#}");
                    warn!("cannot reconcile KPM '{id}' while enabling policy: {reason}");
                    failures.push(format!("{id}: {reason}"));
                    continue;
                }
            };
            if !manifest.enabled || manifest.quarantined {
                continue;
            }
            if let Err(error) = load_manifest_now(&id, &manifest, false, None) {
                let mut reason = format!("load failed: {error:#}");
                warn!("failed to load KPM '{id}' while enabling policy: {error:#}");
                if let Err(quarantine_error) =
                    quarantine(&id, &format!("policy enable load failed: {error:#}"))
                {
                    let _ = write!(reason, "; quarantine failed: {quarantine_error:#}");
                }
                if selected == KpmBackend::KpatchNext
                    && let Err(cleanup_error) = unsync_kpatch_image(&id)
                {
                    let _ = write!(
                        reason,
                        "; KPatch boot image cleanup failed: {cleanup_error:#}"
                    );
                }
                failures.push(format!("{id}: {reason}"));
            }
        }
        ensure!(
            failures.is_empty(),
            "KPM policy is enabled, but one or more modules could not be reconciled: {}",
            failures.join("; ")
        );
    }
    println!("KPM policy enabled");
    Ok(())
}

pub fn set_policy(enabled: bool) -> Result<()> {
    let _lock = acquire_operation_lock()?;
    set_policy_inner(enabled)
}

fn manifest_json(id: &str, manifest: &KpmManifest, live: Option<bool>) -> Value {
    json!({
        "id": id,
        "name": manifest.metadata.name,
        "version": manifest.metadata.version,
        "license": manifest.metadata.license,
        "author": manifest.metadata.author,
        "description": manifest.metadata.description,
        "sha256": manifest.sha256,
        "args": manifest.args,
        "enabled": manifest.enabled,
        "quarantined": manifest.quarantined,
        "quarantineReason": manifest.quarantine_reason,
        "sourceName": manifest.source_name,
        "importedAt": manifest.imported_at,
        "loaded": live,
        "runtimeKnown": live.is_some(),
    })
}

pub fn print_list() -> Result<()> {
    ensure_supported_backend()?;
    ensure_kpm_root()?;
    let live = match live_names() {
        Ok(names) => Some(names),
        Err(error) => {
            warn!("KPM runtime state is unavailable for list: {error:#}");
            None
        }
    };
    let mut values = Vec::new();
    for entry in fs::read_dir(kpm_root())? {
        let entry = entry?;
        let id = entry.file_name().to_string_lossy().into_owned();
        if id.starts_with('.') || entry.file_type()?.is_symlink() {
            continue;
        }
        match read_manifest(&id) {
            Ok(manifest) => values.push(manifest_json(
                &id,
                &manifest,
                live.as_ref()
                    .map(|names| names.iter().any(|name| name == &id)),
            )),
            Err(error) => values.push(json!({"id": id, "error": format!("{error:#}")})),
        }
    }
    println!("{}", Value::Array(values));
    Ok(())
}

pub fn print_info(id: &str) -> Result<()> {
    let selected = ensure_supported_backend()?;
    validate_id(id)?;
    let manifest = read_manifest(id)?;
    let loaded = live_names()
        .ok()
        .map(|names| names.iter().any(|name| name == id));
    let runtime_info = if selected == KpmBackend::NativeGki && loaded == Some(true) {
        ksucalls::native_kpm_info(id).ok()
    } else {
        None
    };
    let image_size =
        u32::try_from(fs::metadata(validated_image_path(id)?)?.len()).unwrap_or(u32::MAX);
    println!(
        "{}",
        json!({
            "backend": backend_name(selected),
            "name": manifest.metadata.name,
            "version": manifest.metadata.version,
            "license": manifest.metadata.license,
            "author": manifest.metadata.author,
            "description": manifest.metadata.description,
            "state": match loaded {
                Some(true) => 2,
                Some(false) => 0,
                None => -1,
            },
            "runtimeKnown": loaded.is_some(),
            "imageSize": image_size,
            "textSize": 0,
            "roSize": 0,
            "loadedAtNs": 0,
            "runtimeInfo": runtime_info,
        })
    );
    Ok(())
}

fn import_inner(path: &Path, args: &str, trusted: bool, force: bool, enable: bool) -> Result<()> {
    let selected = ensure_supported_backend()?;
    ensure_kpm_available()?;
    ensure!(trusted, "KPM import requires --trusted acknowledgement");
    validate_args(args)?;
    ensure_kpm_root()?;
    let source_metadata = fs::symlink_metadata(path).context("KPM source does not exist")?;
    ensure!(
        source_metadata.is_file() && !source_metadata.file_type().is_symlink(),
        "KPM source must be a regular file"
    );
    let bytes = fs::read(path).context("failed to read KPM source")?;
    let metadata = parse_kpm(&bytes)?;
    let id = metadata.name.clone();
    let target = kpm_root().join(&id);
    let target_metadata = fs::symlink_metadata(&target).ok();
    ensure!(
        target_metadata
            .as_ref()
            .is_none_or(|metadata| !metadata.file_type().is_symlink()),
        "refusing to replace a symlinked KPM directory"
    );
    if target_metadata.is_some() && !force {
        bail!("KPM '{id}' already exists; pass --force to replace it");
    }
    if let Some(metadata) = target_metadata.as_ref() {
        ensure!(metadata.is_dir(), "existing KPM path is not a directory");
    }
    if force && is_live(&id)? {
        unload_runtime(&id).context("failed to unload existing KPM before replacement")?;
    }
    if force && selected == KpmBackend::KpatchNext {
        unsync_kpatch_image(&id)?;
    }
    let operation_id = monotonic_nonce();
    let temp = kpm_root().join(format!(
        ".import-{id}-{}-{operation_id}",
        std::process::id()
    ));
    DirBuilder::new().mode(0o700).create(&temp)?;
    let result = (|| -> Result<()> {
        let image = temp.join(IMAGE_NAME);
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&image)?;
        file.write_all(&bytes)?;
        file.sync_all()?;
        let manifest = KpmManifest {
            metadata,
            sha256: digest(&bytes),
            args: args.to_string(),
            enabled: false,
            quarantined: false,
            quarantine_reason: String::new(),
            imported_at: chrono::Local::now().to_rfc3339(),
            source_name: path
                .file_name()
                .and_then(|name| name.to_str())
                .unwrap_or("module.kpm")
                .to_string(),
        };
        let manifest_bytes = serde_json::to_vec_pretty(&manifest_value(&manifest))?;
        let manifest_path = temp.join(MANIFEST_NAME);
        let mut manifest_file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&manifest_path)?;
        manifest_file.write_all(&manifest_bytes)?;
        manifest_file.sync_all()?;
        if target_metadata.is_some() {
            let backup = kpm_root().join(format!(
                ".replace-{id}-{}-{operation_id}",
                std::process::id()
            ));
            fs::rename(&target, &backup)?;
            if let Err(error) = fs::rename(&temp, &target) {
                fs::rename(&backup, &target).with_context(|| {
                    format!("failed to restore existing KPM after replacement error: {error}")
                })?;
                return Err(error.into());
            }
            if let Err(error) = fs::remove_dir_all(&backup) {
                warn!(
                    "failed to remove KPM replacement backup {}: {error}",
                    backup.display()
                );
            }
        } else {
            fs::rename(&temp, &target)?;
        }
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_dir_all(&temp);
    }
    result?;
    if enable {
        enable_module_inner(&id)?;
    }
    println!("Imported KPM '{id}'");
    Ok(())
}

pub fn import(path: &Path, args: &str, trusted: bool, force: bool, enable: bool) -> Result<()> {
    let _lock = acquire_operation_lock()?;
    import_inner(path, args, trusted, force, enable)
}

fn enable_module_inner(id: &str) -> Result<()> {
    let selected = ensure_supported_backend()?;
    ensure_kpm_available()?;
    ensure_kpm_root()?;
    let previous = read_manifest(id)?;
    let mut manifest = previous.clone();
    manifest.enabled = true;
    manifest.quarantined = false;
    manifest.quarantine_reason.clear();
    write_manifest(id, &manifest)?;
    if selected == KpmBackend::KpatchNext
        && let Err(error) = sync_kpatch_image(id, &manifest)
    {
        if let Err(rollback_error) = write_manifest(id, &previous) {
            bail!(
                "failed to synchronize KPM '{id}' with KPatch Next: {error:#}; manifest rollback failed: {rollback_error:#}"
            );
        }
        return Err(error);
    }
    let runtime_ready = match selected {
        KpmBackend::NativeGki => native_loader_ready(),
        KpmBackend::KpatchNext => kpatch_runtime_ready(),
        KpmBackend::Unsupported => false,
    };
    if runtime_ready && let Err(error) = load_manifest_now(id, &manifest, false, None) {
        let mut failure = format!("failed to enable KPM '{id}': {error:#}");
        if let Err(quarantine_error) = quarantine(id, &format!("enable failed: {error:#}")) {
            let _ = write!(failure, "; quarantine failed: {quarantine_error:#}");
        }
        if selected == KpmBackend::KpatchNext
            && let Err(cleanup_error) = unsync_kpatch_image(id)
        {
            let _ = write!(
                failure,
                "; KPatch boot image cleanup failed: {cleanup_error:#}"
            );
        }
        bail!(failure);
    }
    println!(
        "Enabled {} KPM '{id}'{}",
        backend_name(selected),
        if runtime_ready {
            ""
        } else {
            "; it will load after the selected KPM loader is active"
        }
    );
    Ok(())
}

pub fn enable_module(id: &str) -> Result<()> {
    let _lock = acquire_operation_lock()?;
    enable_module_inner(id)
}

pub fn disable_module(id: &str) -> Result<()> {
    let _lock = acquire_operation_lock()?;
    let selected = ensure_supported_backend()?;
    let mut manifest = read_manifest(id)?;
    let previous_manifest = manifest.clone();
    let runtime_state = match live_names() {
        Ok(names) => Some(names.iter().any(|name| name == id)),
        Err(error) => {
            warn!("cannot query KPM '{id}' runtime state while disabling it: {error:#}");
            None
        }
    };
    if runtime_state == Some(true) {
        unload_runtime(id).context("failed to unload KPM")?;
    }
    let restore_kpatch_state = selected == KpmBackend::KpatchNext && kpatch_image_path(id).exists();
    if selected == KpmBackend::KpatchNext {
        unsync_kpatch_image(id)?;
    }
    manifest.enabled = false;
    if let Err(error) = write_manifest(id, &manifest) {
        if restore_kpatch_state
            && let Err(rollback_error) = sync_kpatch_image(id, &previous_manifest)
        {
            bail!(
                "failed to disable KPM '{id}': {error:#}; KPatch state rollback failed: {rollback_error:#}"
            );
        }
        return Err(error);
    }
    if runtime_state.is_none() {
        println!(
            "Disabled KPM '{id}' persistently; runtime state is unavailable, so reboot to guarantee it is unloaded"
        );
    } else {
        println!("Disabled KPM '{id}'");
    }
    Ok(())
}

pub fn remove_module(id: &str) -> Result<()> {
    let _lock = acquire_operation_lock()?;
    let selected = ensure_supported_backend()?;
    validate_id(id)?;
    if is_live(id).with_context(|| {
        format!(
            "cannot safely remove KPM '{id}' while runtime state is unavailable; disable it and reboot first"
        )
    })? {
        unload_runtime(id).context("failed to unload KPM before removal")?;
    }
    if selected == KpmBackend::KpatchNext {
        unsync_kpatch_image(id)?;
    }
    let path = kpm_root().join(id);
    let metadata = fs::symlink_metadata(&path).context("KPM does not exist")?;
    ensure!(
        !metadata.file_type().is_symlink() && metadata.is_dir(),
        "refusing to remove invalid KPM path"
    );
    fs::remove_dir_all(&path).context("failed to remove KPM")?;
    println!("Removed KPM '{id}'");
    Ok(())
}

pub fn load_module(id: &str) -> Result<()> {
    let _lock = acquire_operation_lock()?;
    validate_id(id)?;
    ensure!(!is_live(id)?, "KPM is already loaded");
    let manifest = read_manifest(id)?;
    let info = load_manifest_now(id, &manifest, false, None)?;
    println!(
        "{}",
        json!({"loaded": true, "name": info.name, "version": info.version})
    );
    Ok(())
}

pub fn unload_module(id: &str) -> Result<()> {
    let _lock = acquire_operation_lock()?;
    ensure_supported_backend()?;
    validate_id(id)?;
    unload_runtime(id)?;
    println!("Unloaded KPM '{id}'");
    Ok(())
}

pub fn control_module(id: &str, args: &str) -> Result<()> {
    let _lock = acquire_operation_lock()?;
    let selected = ensure_supported_backend()?;
    ensure_kpm_available()?;
    validate_id(id)?;
    validate_args(args)?;
    match selected {
        KpmBackend::NativeGki => {
            ensure_native_loader_ready()?;
            let result = ksucalls::native_kpm_control(id, args)
                .with_context(|| format!("control Native GKI KPM '{id}'"))?;
            println!("{{\"result\":{result}}}");
        }
        KpmBackend::KpatchNext => {
            ensure!(kpatch_runtime_ready(), "KPatch Next runtime is not active");
            let output = kpatch_command(&["kpm", "ctl0", id, args])?;
            println!("{}", String::from_utf8_lossy(&output.stdout).trim());
        }
        KpmBackend::Unsupported => unreachable!(),
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::parse_runtime_names;

    #[test]
    fn runtime_names_are_trimmed_without_empty_lines() {
        assert_eq!(
            parse_runtime_names("alpha\n\nbeta\n").unwrap(),
            vec!["alpha", "beta"]
        );
    }

    #[test]
    fn runtime_names_reject_duplicates() {
        assert!(parse_runtime_names("alpha\nalpha").is_err());
    }

    #[test]
    fn runtime_names_reject_invalid_loader_output() {
        assert!(parse_runtime_names("../escape").is_err());
        assert!(parse_runtime_names("bad name").is_err());
    }
}
