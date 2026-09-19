use crate::sulog;
use anyhow::{Context, Result, bail};
use const_format::concatcp;
use std::collections::HashMap;
use std::fs::File;
use std::io::{Read, Write};
use std::path::Path;

use crate::defs;

const FEATURE_CONFIG_PATH: &str = concatcp!(defs::WORKING_DIR, ".feature_config");
#[allow(clippy::unreadable_literal)]
const FEATURE_MAGIC: u32 = 0x7f4b5355;
const FEATURE_VERSION: u32 = 1;
const AVC_SPOOF_LEGACY_ID: u32 = 10003;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u32)]
pub enum FeatureId {
    SuCompat = 0,
    KernelUmount = 1,
    Sulog = 2,
    AdbRoot = 3,
    SelinuxHide = 4,
    AvcSpoof = 5,
    WebviewZygoteUmount = 7,
    SeccompHookStatus = 8,
    SeccompHookLastError = 9,
    SeccompHookCallCount = 10,
    SeccompHookReleaseCount = 11,
    SeccompHookFailureCount = 12,
}

impl FeatureId {
    pub const fn from_u32(id: u32) -> Option<Self> {
        match id {
            0 => Some(Self::SuCompat),
            1 => Some(Self::KernelUmount),
            2 => Some(Self::Sulog),
            3 => Some(Self::AdbRoot),
            4 => Some(Self::SelinuxHide),
            5 | AVC_SPOOF_LEGACY_ID => Some(Self::AvcSpoof),
            7 => Some(Self::WebviewZygoteUmount),
            8 => Some(Self::SeccompHookStatus),
            9 => Some(Self::SeccompHookLastError),
            10 => Some(Self::SeccompHookCallCount),
            11 => Some(Self::SeccompHookReleaseCount),
            12 => Some(Self::SeccompHookFailureCount),
            _ => None,
        }
    }

    pub const fn name(self) -> &'static str {
        match self {
            Self::SuCompat => "su_compat",
            Self::KernelUmount => "kernel_umount",
            Self::Sulog => "sulog",
            Self::AdbRoot => "adb_root",
            Self::SelinuxHide => "selinux_hide",
            Self::AvcSpoof => "avc_spoof",
            Self::WebviewZygoteUmount => "webview_zygote_umount",
            Self::SeccompHookStatus => "seccomp_hook_status",
            Self::SeccompHookLastError => "seccomp_hook_last_error",
            Self::SeccompHookCallCount => "seccomp_hook_call_count",
            Self::SeccompHookReleaseCount => "seccomp_hook_release_count",
            Self::SeccompHookFailureCount => "seccomp_hook_failure_count",
        }
    }

    pub const fn canonical_id(self) -> u32 {
        self as u32
    }

    pub const fn legacy_id(self) -> Option<u32> {
        match self {
            Self::AvcSpoof => Some(AVC_SPOOF_LEGACY_ID),
            _ => None,
        }
    }

    pub const fn is_read_only(self) -> bool {
        matches!(
            self,
            Self::SeccompHookStatus
                | Self::SeccompHookLastError
                | Self::SeccompHookCallCount
                | Self::SeccompHookReleaseCount
                | Self::SeccompHookFailureCount
        )
    }

    pub const fn description(self) -> &'static str {
        match self {
            Self::SuCompat => {
                "SU Compatibility Mode - allows authorized apps to gain root via traditional 'su' command"
            }
            Self::KernelUmount => {
                "Kernel Umount - controls whether kernel automatically unmounts modules when not needed"
            }
            Self::Sulog => {
                "SU Log - streams kernel sulog events to userspace and persists them to disk"
            }
            Self::AdbRoot => "ADB Root - Enable adbd root",
            Self::SelinuxHide => {
                "SELinux Hide - sanitize /sys/fs/selinux access results for app UIDs"
            }
            Self::AvcSpoof => "AVC Spoof - hide KernelSU SELinux domains in AVC audit logs",
            Self::WebviewZygoteUmount => {
                "WebView Zygote Umount - unmount modules from WebView zygote and its isolated children"
            }
            Self::SeccompHookStatus => "GKI Seccomp hook capability and runtime status flags",
            Self::SeccompHookLastError => "GKI Seccomp hook most recent errno",
            Self::SeccompHookCallCount => "GKI Seccomp hook invocation count",
            Self::SeccompHookReleaseCount => "GKI Seccomp filter release count",
            Self::SeccompHookFailureCount => "GKI Seccomp hook failure count",
        }
    }
}

fn parse_feature_id(name: &str) -> Result<FeatureId> {
    match name {
        "su_compat" | "0" => Ok(FeatureId::SuCompat),
        "kernel_umount" | "1" => Ok(FeatureId::KernelUmount),
        "sulog" | "2" => Ok(FeatureId::Sulog),
        "adb_root" | "3" => Ok(FeatureId::AdbRoot),
        "selinux_hide" | "4" => Ok(FeatureId::SelinuxHide),
        "avc_spoof" | "5" | "10003" => Ok(FeatureId::AvcSpoof),
        "webview_zygote_umount" | "7" => Ok(FeatureId::WebviewZygoteUmount),
        "seccomp_hook_status" | "8" => Ok(FeatureId::SeccompHookStatus),
        "seccomp_hook_last_error" | "9" => Ok(FeatureId::SeccompHookLastError),
        "seccomp_hook_call_count" | "10" => Ok(FeatureId::SeccompHookCallCount),
        "seccomp_hook_release_count" | "11" => Ok(FeatureId::SeccompHookReleaseCount),
        "seccomp_hook_failure_count" | "12" => Ok(FeatureId::SeccompHookFailureCount),
        _ => bail!("Unknown feature: {name}"),
    }
}

fn get_kernel_feature(feature_id: FeatureId) -> Result<(u64, bool)> {
    let id = feature_id.canonical_id();
    match crate::ksucalls::get_feature(id) {
        Ok(result) if result.1 => Ok(result),
        Ok(result) => feature_id.legacy_id().map_or_else(
            || Ok(result),
            |legacy_id| Ok(crate::ksucalls::get_feature(legacy_id).unwrap_or(result)),
        ),
        Err(err) => feature_id.legacy_id().map_or_else(
            || Err(err).with_context(|| format!("Failed to get feature {}", feature_id.name())),
            |legacy_id| Ok(crate::ksucalls::get_feature(legacy_id).unwrap_or((0, false))),
        ),
    }
}

fn set_kernel_feature(feature_id: FeatureId, value: u64) -> Result<()> {
    if feature_id.is_read_only() {
        bail!("Feature {} is read-only", feature_id.name());
    }
    let id = feature_id.canonical_id();
    match crate::ksucalls::set_feature(id, value) {
        Ok(()) => {}
        Err(err) => {
            if let Some(legacy_id) = feature_id.legacy_id() {
                crate::ksucalls::set_feature(legacy_id, value).with_context(|| {
                    format!(
                        "Failed to set feature {} to {value} (id={id}: {err}, legacy_id={legacy_id})",
                        feature_id.name()
                    )
                })?;
            } else {
                return Err(err).with_context(|| {
                    format!("Failed to set feature {} to {value}", feature_id.name())
                });
            }
        }
    }

    if feature_id == FeatureId::Sulog
        && value != 0
        && let Err(err) = sulog::ensure_sulogd_running()
    {
        log::warn!("failed to ensure sulogd is running after feature init: {err:#}");
    }

    Ok(())
}

pub fn load_binary_config() -> Result<HashMap<u32, u64>> {
    let path = Path::new(FEATURE_CONFIG_PATH);
    if !path.exists() {
        log::info!("Feature config not found, using defaults");
        return Ok(HashMap::new());
    }

    let mut file = File::open(path).with_context(|| "Failed to open feature config")?;

    let mut magic_buf = [0u8; 4];
    file.read_exact(&mut magic_buf)
        .with_context(|| "Failed to read magic")?;
    let magic = u32::from_le_bytes(magic_buf);

    if magic != FEATURE_MAGIC {
        bail!("Invalid feature config magic: expected 0x{FEATURE_MAGIC:08x}, got 0x{magic:08x}");
    }

    let mut version_buf = [0u8; 4];
    file.read_exact(&mut version_buf)
        .with_context(|| "Failed to read version")?;
    let version = u32::from_le_bytes(version_buf);

    if version != FEATURE_VERSION {
        log::warn!(
            "Feature config version mismatch: expected {FEATURE_VERSION}, got {version
            }",
        );
    }

    let mut count_buf = [0u8; 4];
    file.read_exact(&mut count_buf)
        .with_context(|| "Failed to read count")?;
    let count = u32::from_le_bytes(count_buf);

    let mut features = HashMap::new();
    for _ in 0..count {
        let mut id_buf = [0u8; 4];
        let mut value_buf = [0u8; 8];

        file.read_exact(&mut id_buf)
            .with_context(|| "Failed to read feature id")?;
        file.read_exact(&mut value_buf)
            .with_context(|| "Failed to read feature value")?;

        let id = u32::from_le_bytes(id_buf);
        let value = u64::from_le_bytes(value_buf);

        let canonical_id = FeatureId::from_u32(id).map_or(id, FeatureId::canonical_id);
        features.insert(canonical_id, value);
    }

    log::info!("Loaded {} features from config", features.len());
    Ok(features)
}

pub fn save_binary_config(features: &HashMap<u32, u64>) -> Result<()> {
    crate::utils::ensure_dir_exists(Path::new(defs::WORKING_DIR))?;

    let path = Path::new(FEATURE_CONFIG_PATH);
    let mut file = File::create(path).with_context(|| "Failed to create feature config")?;

    file.write_all(&FEATURE_MAGIC.to_le_bytes())
        .with_context(|| "Failed to write magic")?;

    file.write_all(&FEATURE_VERSION.to_le_bytes())
        .with_context(|| "Failed to write version")?;

    let count = features.len() as u32;
    file.write_all(&count.to_le_bytes())
        .with_context(|| "Failed to write count")?;

    for (&id, &value) in features {
        file.write_all(&id.to_le_bytes())
            .with_context(|| format!("Failed to write feature id {id}"))?;
        file.write_all(&value.to_le_bytes())
            .with_context(|| format!("Failed to write feature value for id {id}"))?;
    }

    file.sync_all()
        .with_context(|| "Failed to sync feature config")?;

    log::info!("Saved {} features to config", features.len());
    Ok(())
}

pub fn apply_config(features: &HashMap<u32, u64>) {
    log::info!("Applying feature configuration to kernel...");

    let mut applied = 0;
    for (&id, &value) in features {
        match FeatureId::from_u32(id) {
            Some(feature_id) => match set_kernel_feature(feature_id, value) {
                Ok(()) => {
                    log::info!("Set feature {} to {value}", feature_id.name());
                    applied += 1;
                }
                Err(e) => {
                    log::warn!("Failed to set feature {}: {e}", feature_id.name());
                }
            },
            None => match crate::ksucalls::set_feature(id, value) {
                Ok(()) => {
                    log::info!("Set feature {id} to {value}");
                    applied += 1;
                }
                Err(e) => {
                    log::warn!("Failed to set feature {id}: {e}");
                }
            },
        }
    }

    log::info!("Applied {applied} features successfully");
}

pub fn get_feature(id: &str) -> Result<()> {
    let feature_id = parse_feature_id(id)?;
    let (value, supported) = get_kernel_feature(feature_id)?;

    if !supported {
        println!("Feature '{id}' is not supported by kernel");
        return Ok(());
    }

    println!("Feature: {} ({})", feature_id.name(), feature_id as u32);
    println!("Description: {}", feature_id.description());
    println!("Value: {value}");
    if feature_id.is_read_only() {
        println!("Status: read-only");
    } else {
        println!(
            "Status: {}",
            if value != 0 { "enabled" } else { "disabled" }
        );
    }

    Ok(())
}

pub fn get_feature_config(id: &str) -> Result<()> {
    let feature_id = parse_feature_id(id)?;

    let features = load_binary_config()?;
    let id_u32 = feature_id as u32;

    println!("Feature: {} ({})", feature_id.name(), id_u32);
    println!("Description: {}", feature_id.description());

    if let Some(value) = features.get(&id_u32) {
        println!("Value: {value}");
        println!(
            "Status: {}",
            if *value != 0 { "enabled" } else { "disabled" }
        );
    } else {
        println!("Not set in config");
    }

    Ok(())
}

pub fn set_feature(id: &str, value: u64) -> Result<()> {
    let feature_id = parse_feature_id(id)?;
    if feature_id.is_read_only() {
        bail!("Feature '{}' is read-only", feature_id.name());
    }

    // Check if this feature is managed by any module
    if let Ok(managed_features_map) = crate::module::get_managed_features() {
        // Find which modules manage this feature
        let managing_modules: Vec<&String> = managed_features_map
            .iter()
            .filter(|(_, features)| features.iter().any(|f| f == feature_id.name()))
            .map(|(module_id, _)| module_id)
            .collect();

        if !managing_modules.is_empty() {
            // Feature is managed, check if caller is an authorized module
            let caller_module = std::env::var("KSU_MODULE").unwrap_or_default();

            if caller_module.is_empty() || !managing_modules.contains(&&caller_module) {
                bail!(
                    "Feature '{}' is managed by module(s): {}. Direct modification is not allowed.",
                    feature_id.name(),
                    managing_modules
                        .iter()
                        .map(|s| s.as_str())
                        .collect::<Vec<_>>()
                        .join(", ")
                );
            }

            log::info!(
                "Module '{caller_module}' is setting managed feature '{}'",
                feature_id.name()
            );
        }
    }

    set_kernel_feature(feature_id, value)?;

    println!(
        "Feature '{}' set to {value} ({})",
        feature_id.name(),
        if value != 0 { "enabled" } else { "disabled" }
    );

    Ok(())
}

/// Query a feature for local management surfaces without parsing CLI output.
/// The final flag reports whether an active module owns the feature.
pub fn feature_state(id: &str) -> Result<(u64, bool, bool)> {
    let feature_id = parse_feature_id(id)?;
    let (value, supported) = get_kernel_feature(feature_id)?;
    let managed = managed_feature_ids().contains(&feature_id.canonical_id());
    Ok((value, supported, managed))
}

/// Apply a writable feature and persist the complete supported feature snapshot
/// so the same state is restored on the next boot.
pub fn set_feature_persisted(id: &str, value: u64) -> Result<()> {
    set_feature(id, value)?;
    save_config()
}

pub fn list_features() {
    println!("Available Features:");
    println!("{}", "=".repeat(80));

    // Get managed features from modules
    let managed_features_map = crate::module::get_managed_features().unwrap_or_default();

    // Build a reverse map: feature_name -> Vec<module_id>
    let mut feature_to_modules: HashMap<String, Vec<String>> = HashMap::new();
    for (module_id, feature_list) in &managed_features_map {
        for feature_name in feature_list {
            feature_to_modules
                .entry(feature_name.clone())
                .or_default()
                .push(module_id.clone());
        }
    }

    let all_features = [
        FeatureId::SuCompat,
        FeatureId::KernelUmount,
        FeatureId::Sulog,
        FeatureId::AdbRoot,
        FeatureId::SelinuxHide,
        FeatureId::AvcSpoof,
        FeatureId::WebviewZygoteUmount,
        FeatureId::SeccompHookStatus,
        FeatureId::SeccompHookLastError,
        FeatureId::SeccompHookCallCount,
        FeatureId::SeccompHookReleaseCount,
        FeatureId::SeccompHookFailureCount,
    ];

    for feature_id in &all_features {
        let id = feature_id.canonical_id();
        let (value, supported) = get_kernel_feature(*feature_id).unwrap_or((0, false));

        let status = if !supported {
            "NOT_SUPPORTED".to_string()
        } else if feature_id.is_read_only() {
            format!("READ_ONLY ({value})")
        } else if value != 0 {
            format!("ENABLED ({value})")
        } else {
            "DISABLED".to_string()
        };

        let managed_by = feature_to_modules.get(feature_id.name());
        let managed_mark = if managed_by.is_some() {
            " [MODULE_MANAGED]"
        } else {
            ""
        };

        println!(
            "[{}] {} (ID={}){}",
            status,
            feature_id.name(),
            id,
            managed_mark
        );
        println!("    {}", feature_id.description());

        if let Some(modules) = managed_by {
            println!(
                "    ⚠️  Managed by module(s): {} (forced to 0 on initialization)",
                modules.join(", ")
            );
        }

        println!();
    }
}

pub fn load_config_and_apply() -> Result<()> {
    let features = load_binary_config()?;

    if features.is_empty() {
        println!("No features found in config file");
        return Ok(());
    }

    apply_config(&features);
    println!("Feature configuration loaded and applied");
    Ok(())
}

pub fn save_config() -> Result<()> {
    let mut features = HashMap::new();

    let all_features = [
        FeatureId::SuCompat,
        FeatureId::KernelUmount,
        FeatureId::Sulog,
        FeatureId::AdbRoot,
        FeatureId::SelinuxHide,
        FeatureId::AvcSpoof,
        FeatureId::WebviewZygoteUmount,
    ];

    for feature_id in &all_features {
        let id = feature_id.canonical_id();
        if let Ok((value, supported)) = get_kernel_feature(*feature_id)
            && supported
        {
            features.insert(id, value);
            log::info!("Saved feature {} = {value}", feature_id.name());
        }
    }

    save_binary_config(&features)?;
    println!(
        "Current feature states saved to config file ({} features)",
        features.len()
    );
    Ok(())
}

pub fn check_feature(id: &str) -> Result<()> {
    let feature_id = parse_feature_id(id)?;

    // Check if this feature is managed by any module
    let managed_features_map = crate::module::get_managed_features().unwrap_or_default();
    let is_managed = managed_features_map
        .values()
        .any(|features| features.iter().any(|f| f == feature_id.name()));

    if is_managed {
        println!("managed");
        return Ok(());
    }

    // Check if the feature is supported by kernel
    let (_value, supported) = get_kernel_feature(feature_id)?;

    if supported {
        println!("supported");
    } else {
        println!("unsupported");
    }

    Ok(())
}

/// 收集被活动模块接管的特性 id（这些特性由模块控制，ksud 不再应用配置）。
fn managed_feature_ids() -> std::collections::HashSet<u32> {
    let mut managed = std::collections::HashSet::new();
    match crate::module::get_managed_features() {
        Ok(managed_features_map) => {
            if !managed_features_map.is_empty() {
                log::info!(
                    "Found {} modules managing features",
                    managed_features_map.len()
                );
            }
            for (module_id, feature_list) in &managed_features_map {
                for feature_name in feature_list {
                    match parse_feature_id(feature_name) {
                        Ok(feature_id) => {
                            managed.insert(feature_id as u32);
                            log::info!(
                                "  - feature '{feature_name}' is managed by module '{module_id}'"
                            );
                        }
                        Err(_) => {
                            log::warn!(
                                "  - Unknown managed feature '{feature_name}' from module '{module_id}', ignoring"
                            );
                        }
                    }
                }
            }
        }
        Err(e) => log::warn!("Failed to get managed features from modules: {e}"),
    }
    managed
}

fn should_reapply_feature(feature_id: FeatureId, configured: u64, current: u64) -> bool {
    current != configured || (feature_id == FeatureId::SelinuxHide && configured != 0)
}

/// Retry configured features that did not become active during post-fs-data.
///
/// SELinux hide is retried whenever it is configured on. Its legacy get ABI
/// reports the requested state, so a failed early hook can otherwise look
/// active and prevent a later retry.
pub fn reapply_configured_features() -> Result<()> {
    let features = load_binary_config()?;
    if features.is_empty() {
        return Ok(());
    }

    let managed = managed_feature_ids();
    let mut pending: HashMap<u32, u64> = HashMap::new();
    for (&id, &value) in &features {
        if managed.contains(&id) {
            continue;
        }
        let Some(feature_id) = FeatureId::from_u32(id) else {
            continue;
        };
        match get_kernel_feature(feature_id) {
            Ok((current, true)) if should_reapply_feature(feature_id, value, current) => {
                pending.insert(id, value);
            }
            Ok(_) => {}
            Err(e) => log::warn!("feature {} state unknown: {e}", feature_id.name()),
        }
    }

    if pending.is_empty() {
        log::info!("feature re-apply: all configured features already match");
        return Ok(());
    }

    log::info!("feature re-apply: retrying {} feature(s)", pending.len());
    for (&id, &value) in &pending {
        if let Some(feature_id) = FeatureId::from_u32(id) {
            match set_kernel_feature(feature_id, value) {
                Ok(()) => log::info!("feature {} re-applied ({value})", feature_id.name()),
                Err(e) => log::warn!(
                    "feature {} still pending after retry (want {value}): {e:#}",
                    feature_id.name()
                ),
            }
        }
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::{FeatureId, should_reapply_feature};

    #[test]
    fn selinux_hide_is_retried_when_requested_state_masks_hook_failure() {
        assert!(should_reapply_feature(FeatureId::SelinuxHide, 1, 1));
        assert!(!should_reapply_feature(FeatureId::SelinuxHide, 0, 0));
    }

    #[test]
    fn other_features_are_only_retried_on_state_mismatch() {
        assert!(!should_reapply_feature(FeatureId::KernelUmount, 1, 1));
        assert!(should_reapply_feature(FeatureId::KernelUmount, 1, 0));
    }

    #[test]
    fn seccomp_hook_diagnostics_are_read_only() {
        assert!(FeatureId::SeccompHookStatus.is_read_only());
        assert!(FeatureId::SeccompHookLastError.is_read_only());
        assert!(FeatureId::SeccompHookCallCount.is_read_only());
        assert!(FeatureId::SeccompHookReleaseCount.is_read_only());
        assert!(FeatureId::SeccompHookFailureCount.is_read_only());
        assert!(!FeatureId::KernelUmount.is_read_only());
    }
}

pub fn init_features() -> Result<()> {
    log::info!("Initializing features from config...");

    let mut features = load_binary_config()?;

    // 被模块接管的特性交给模块控制，这里从配置里摘掉
    for managed_id in managed_feature_ids() {
        if features.remove(&managed_id).is_some() {
            log::info!("Skipping module-managed feature {managed_id}");
        }
    }

    if features.is_empty() {
        log::info!("No features to apply, skipping initialization");
        return Ok(());
    }

    apply_config(&features);

    // Save the configuration (excluding managed features)
    save_binary_config(&features)?;
    log::info!("Saved feature configuration to file");

    Ok(())
}
