use anyhow::{Context, Result, anyhow, bail};
use chrono::Local;
use const_format::concatcp;
use serde_json::{Value, json};
#[cfg(target_os = "android")]
use std::os::fd::AsRawFd;
use std::{
    collections::{BTreeMap, BTreeSet},
    fs::{self, OpenOptions},
    io::{self, Read, Write},
    path::{Component, Path, PathBuf},
    process::Command,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use crate::{boot_patch, defs, module, utils};

const RESCUE_DIR: &str = concatcp!(defs::WORKING_DIR, "rescue/");
const CONFIG_PATH: &str = concatcp!(RESCUE_DIR, "config.json");
const MANIFEST_PATH: &str = concatcp!(RESCUE_DIR, "manifest.json");
const ENABLED_PATH: &str = concatcp!(RESCUE_DIR, "enabled");
const LOG_PATH: &str = concatcp!(RESCUE_DIR, "rescue.log");
const BOOT_COUNT_PATH: &str = concatcp!(RESCUE_DIR, "boot_count");
const BOOT_OK_PATH: &str = concatcp!(RESCUE_DIR, "boot_ok");
const PENDING_BOOT_PATH: &str = concatcp!(RESCUE_DIR, "pending_boot");
// Written after an image restore and removed only after the next boot reaches
// boot-completed.  The legacy lock below is read only for migration.
const RESTORE_PENDING_BOOT_PATH: &str = concatcp!(RESCUE_DIR, "restore_pending_boot.json");
const RESTORE_LOCK_PATH: &str = concatcp!(RESCUE_DIR, "restore_done.lock");
const RESTORE_TRANSACTION_PATH: &str = concatcp!(RESCUE_DIR, "restore_transaction.json");
const AUTO_RESTORE_ATTEMPTS_PATH: &str = concatcp!(RESCUE_DIR, "auto_restore_attempts");
const FAILURE_BASELINE_PATH: &str = concatcp!(RESCUE_DIR, "failure_baseline.json");
const VERIFIED_PATH: &str = concatcp!(RESCUE_DIR, "verified.json");
const ENVIRONMENT_CHECK_PATH: &str = concatcp!(RESCUE_DIR, "environment_check.json");
const CONFIG_CHANGED_PATH: &str = concatcp!(RESCUE_DIR, "config_changed");
const HASH_CACHE_PATH: &str = concatcp!(RESCUE_DIR, "sha256_cache.json");
const RESCUE_DISABLED_MODULES_PATH: &str = concatcp!(RESCUE_DIR, "disabled_modules.json");
const RECOVERY_CHECK_GUARD_PATH: &str = "/dev/ksu_rescue_recovery_checked";
const SKIP_MODULES_ONCE_PATH: &str = concatcp!(RESCUE_DIR, "skip_modules_once");
const CACHE_SKIP_MODULES_ONCE_PATH: &str = "/cache/ksu_rescue_skip_modules_once";
const SKIP_MODULES_THIS_BOOT_PATH: &str = "/dev/ksu_rescue_skip_modules_this_boot";
const LEGACY_FIX_DONE_LOCK_PATH: &str = "/cache/mochen_fix_done.lock";
const LEGACY_LOOP_FLAG_PATH: &str = "/cache/mochen_boot_loop_flag";
const LEGACY_PANIC_FLAG_PATH: &str = "/cache/mochen_kernel_panic";
const LEGACY_TMP_MODULE_DISABLE_PATH: &str = "/cache/tmp_modules_disable";
const MAX_AUTO_RESTORE_ATTEMPTS: u32 = 3;
const PENDING_BOOT_FAILURE_TRIGGER_COUNT: u32 = 2;
const MODULE_RESCUE_FAILURE_TRIGGER_COUNT: u32 = 2;
const LOG_MAX_BYTES: u64 = 1024 * 1024;
const LOG_ROTATION_COUNT: usize = 3;
const COPY_BUFFER_BYTES: usize = 1024 * 1024;
const COPY_IDLE_TIMEOUT_SECONDS: u64 = 45;
const COPY_TOTAL_TIMEOUT_SECONDS: u64 = 10 * 60;
const FAILURE_ARTIFACT_SCAN_BYTES: u64 = 4 * 1024 * 1024;
const FAILURE_ARTIFACT_MAX_FILES: usize = 32;
const ERROR_PREFIX: &str = "APKESU_ERROR";

#[derive(Clone, Debug)]
struct RestoreTransactionEntry {
    name: String,
    label: String,
    image_path: String,
    device_path: String,
    expected_sha256: String,
    expected_size: u64,
    status: String,
}

#[derive(Clone, Debug)]
struct RestoreTransaction {
    id: String,
    reason: String,
    automatic: bool,
    description: String,
    activate_slot: Option<String>,
    phase: String,
    error_code: String,
    error_message: String,
    started_at: String,
    updated_at: String,
    entries: Vec<RestoreTransactionEntry>,
}

#[derive(Clone, Debug, Default)]
struct RestorePendingBoot {
    transaction_id: String,
    armed_boot_id: String,
    validation_boot_id: String,
    armed_at: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum RestorePendingBootState {
    None,
    Current,
    Previous,
}

fn coded_error(code: &str, message: impl AsRef<str>) -> anyhow::Error {
    anyhow!("{ERROR_PREFIX}:{code}:{}", message.as_ref())
}

pub fn structured_error(error: anyhow::Error) -> anyhow::Error {
    if format!("{error:#}").contains(ERROR_PREFIX) {
        return error;
    }
    let message = format!("{error:#}");
    let lower = message.to_ascii_lowercase();
    let code = if lower.contains("sha256") || lower.contains("checksum") {
        "rescue.checksum_mismatch"
    } else if lower.contains("partition") && lower.contains("missing") {
        "rescue.partition_missing"
    } else if lower.contains("manifest") || lower.contains("backup") {
        "rescue.backup_invalid"
    } else if lower.contains("fingerprint") || lower.contains("identity") {
        "rescue.device_mismatch"
    } else if lower.contains("restore") {
        "rescue.restore_failed"
    } else if lower.contains("config") {
        "rescue.config_invalid"
    } else {
        "rescue.operation_failed"
    };
    coded_error(code, message)
}

fn error_code(error: &anyhow::Error) -> String {
    let rendered = format!("{error:#}");
    rendered
        .split_once(&format!("{ERROR_PREFIX}:"))
        .and_then(|(_, suffix)| suffix.split_once(':').map(|(code, _)| code))
        .unwrap_or("rescue.operation_failed")
        .to_owned()
}

#[derive(Clone, Debug, Default)]
struct RescueConfig {
    include_dtbo: bool,
    include_vbmeta: bool,
    backup_other_slot: bool,
    dangerous_auto_restore: DangerousAutoRestore,
    custom_partitions: BTreeMap<String, String>,
}

#[derive(Clone, Copy, Debug, Default)]
enum DangerousAutoRestore {
    #[default]
    Skip,
    Allow,
}

impl DangerousAutoRestore {
    const fn is_allowed(self) -> bool {
        matches!(self, Self::Allow)
    }
}

#[derive(Clone)]
struct PartitionSpec {
    name: String,
    label: String,
    image_path: String,
    required: bool,
    custom_path: Option<String>,
    ota: bool,
    restore: bool,
}

struct RestorePlan {
    description: String,
    specs: Vec<PartitionSpec>,
    activate_slot: Option<String>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum BootRescueAction {
    None,
    RestoreBackups,
    DisableModules,
}

#[allow(clippy::struct_excessive_bools)]
#[derive(Clone, Copy, Debug, Default)]
struct BootRescueSignals {
    pending_boot: bool,
    restore_pending_boot: bool,
    restore_transaction_pending: bool,
    previous_boot_ok: bool,
    boot_count: u32,
    failure_hint: bool,
}

const fn post_fs_data_rescue_action(signals: BootRescueSignals) -> BootRescueAction {
    let repeated_boot_failure = signals.boot_count >= PENDING_BOOT_FAILURE_TRIGGER_COUNT;
    let restore_requested = signals.restore_transaction_pending
        || (signals.restore_pending_boot && (signals.failure_hint || repeated_boot_failure))
        || (signals.pending_boot
            && (signals.failure_hint || (repeated_boot_failure && !signals.previous_boot_ok)));
    if restore_requested {
        BootRescueAction::RestoreBackups
    } else if !signals.pending_boot
        && !signals.restore_pending_boot
        && !signals.restore_transaction_pending
        && !signals.previous_boot_ok
        && (signals.failure_hint || signals.boot_count >= MODULE_RESCUE_FAILURE_TRIGGER_COUNT)
    {
        // An unverified image must never be overwritten solely because a normal
        // boot was interrupted. Recover modules first; image rollback stays tied
        // to an explicit post-flash verification marker.
        BootRescueAction::DisableModules
    } else {
        BootRescueAction::None
    }
}

const fn recovery_boot_rescue_action(signals: BootRescueSignals) -> BootRescueAction {
    let restore_requested = signals.restore_transaction_pending
        || ((signals.pending_boot || signals.restore_pending_boot)
            && (signals.failure_hint || signals.boot_count >= PENDING_BOOT_FAILURE_TRIGGER_COUNT));
    if restore_requested {
        BootRescueAction::RestoreBackups
    } else if signals.failure_hint {
        BootRescueAction::DisableModules
    } else {
        BootRescueAction::None
    }
}

pub fn print_status() {
    let config_result = read_config();
    let status_error = config_result
        .as_ref()
        .err()
        .map(ToString::to_string)
        .unwrap_or_default();
    let config = config_result.unwrap_or_default();
    let specs = partition_specs(&config);
    let manifest = read_manifest().unwrap_or_else(|_| json!({}));
    let validation = validate_backups_quick(&config);
    let ready = validation.is_ok();
    let ready_reason = validation
        .err()
        .map(|err| err.to_string())
        .unwrap_or_default();
    let transaction_result = read_restore_transaction();
    let transaction_error = transaction_result
        .as_ref()
        .err()
        .map(|error| format!("{error:#}"))
        .unwrap_or_default();
    let transaction = transaction_result.ok().flatten();
    let restore_interrupted = !transaction_error.is_empty()
        || transaction
            .as_ref()
            .is_some_and(|transaction| !matches!(transaction.phase.as_str(), "completed" | "idle"));
    let restore_pending = read_restore_pending_boot();
    let restore_boot_state = restore_pending.as_ref().map_or("none", |marker| {
        match restore_pending_boot_state_from_marker(
            marker,
            &current_boot_id().unwrap_or_default(),
            read_boot_count(),
        ) {
            RestorePendingBootState::None => "none",
            RestorePendingBootState::Current => "validating",
            RestorePendingBootState::Previous => "interrupted",
        }
    });
    // A completed transaction is not a completed restore until the first
    // post-restore boot has passed validation. Keep that distinction visible
    // while the rollback marker is still armed.
    let last_restore_done = restore_pending.is_none()
        && (Path::new(RESTORE_LOCK_PATH).exists()
            || transaction
                .as_ref()
                .is_some_and(|transaction| transaction.phase == "completed"));
    let verification_current = verification_marker_is_current(&specs, &manifest);
    let config_changed = Path::new(CONFIG_CHANGED_PATH).exists();
    let phase = rescue_phase(
        status_error.is_empty(),
        is_enabled(),
        ready,
        verification_current,
        config_changed,
        restore_interrupted,
    );
    let status = json!({
        "statusOk": status_error.is_empty(),
        "statusErrorCode": if status_error.is_empty() { "" } else { "rescue.config_invalid" },
        "statusError": status_error,
        "phase": phase,
        "enabled": is_enabled(),
        "config": config_json(&config),
        "images": specs.iter().map(|spec| image_status(spec, &manifest, false)).collect::<Vec<_>>(),
        "bootCount": read_boot_count(),
        "autoRestoreAttempts": read_auto_restore_attempts(),
        "pendingBoot": Path::new(PENDING_BOOT_PATH).exists() || restore_pending.is_some(),
        "pendingImageBoot": Path::new(PENDING_BOOT_PATH).exists(),
        "restorePendingBoot": restore_pending.is_some(),
        "restoreBootState": restore_boot_state,
        "currentSlot": current_slot(),
        "bootMode": boot_mode(),
        "device": device_summary(),
        "lastRestoreDone": last_restore_done,
        "skipModulesOnce": skip_modules_once_exists(),
        "skipModulesThisBoot": should_skip_modules_this_boot(),
        "manifest": manifest,
        "ready": ready,
        "readyReason": ready_reason,
        "verified": verification_current,
        "environmentChecked": environment_check_is_current(&config),
        "configChangedProtectionDisabled": config_changed,
        "restoreInterrupted": restore_interrupted,
        "restoreTransactionError": transaction_error,
        "restoreTransaction": transaction.as_ref().map(restore_transaction_json),
        "rescueDisabledModules": read_rescue_disabled_modules(),
        "log": tail_file(LOG_PATH, 80).unwrap_or_default(),
    });
    println!("{status}");
}

pub fn print_test_report() {
    let config_result = read_config();
    let config_error = config_result
        .as_ref()
        .err()
        .map(ToString::to_string)
        .unwrap_or_default();
    let config = config_result.unwrap_or_default();
    let specs = partition_specs(&config);
    let environment = validate_environment(&specs);
    let backup_validation = validate_backups_quick(&config);
    let ok = config_error.is_empty() && environment.is_ok();
    let reason = if config_error.is_empty() {
        environment
            .err()
            .map(|err| err.to_string())
            .unwrap_or_default()
    } else {
        config_error.clone()
    };
    let backup_ready = config_error.is_empty() && backup_validation.is_ok();
    let backup_reason = if config_error.is_empty() {
        backup_validation
            .err()
            .map(|err| err.to_string())
            .unwrap_or_default()
    } else {
        config_error
    };
    if ok && let Err(error) = write_environment_check(&config) {
        append_log(format!("failed to persist environment check: {error:#}"));
    }
    let manifest = read_manifest().unwrap_or_else(|_| json!({}));
    let report = json!({
        "ok": ok,
        "errorCode": if ok { "" } else { "rescue.environment_invalid" },
        "reason": reason,
        "backupReady": backup_ready,
        "backupReason": backup_reason,
        "currentSlot": current_slot(),
        "device": device_summary(),
        "images": specs.iter().map(|spec| image_status(spec, &manifest, false)).collect::<Vec<_>>(),
        "manifest": manifest,
        "checks": {
            "bootPartitionFound": find_partition(&specs[0]).is_ok_and(|partition| partition.is_some()),
            "bootBackupReady": Path::new(&specs[0].image_path).is_file(),
            "configWritable": utils::ensure_dir_exists(RESCUE_DIR).is_ok(),
        }
    });
    println!("{report}");
}

pub fn print_verify_report() {
    let result = (|| -> Result<Value> {
        let config = read_config()?;
        let specs = partition_specs(&config);
        validate_backups_full(&config, true)?;
        let manifest = read_manifest()?;
        write_verification_marker(&specs, &manifest)?;
        remove_file_if_exists(Path::new(CONFIG_CHANGED_PATH))?;
        Ok(json!({
            "ok": true,
            "errorCode": "",
            "reason": "",
            "verifiedAt": Local::now().to_rfc3339(),
            "images": specs.iter().map(|spec| image_status(spec, &manifest, true)).collect::<Vec<_>>(),
        }))
    })();

    match result {
        Ok(report) => println!("{report}"),
        Err(error) => {
            let error = structured_error(error);
            println!(
                "{}",
                json!({
                    "ok": false,
                    "errorCode": error_code(&error),
                    "reason": format!("{error:#}"),
                })
            );
        }
    }
}

pub fn refresh_and_enable() -> Result<()> {
    let config = read_config()?;
    let specs = partition_specs(&config);
    validate_environment(&specs).map_err(structured_error)?;
    write_environment_check(&config)?;
    backup(true).map_err(structured_error)?;
    validate_backups_full(&config, true).map_err(structured_error)?;
    let manifest = read_manifest()?;
    write_verification_marker(&specs, &manifest)?;
    enable().map_err(structured_error)
}

pub fn print_diagnostics() {
    println!("=== ApkeSU rescue diagnostic ===");
    println!("generatedAt={}", Local::now().to_rfc3339());
    println!("=== status ===");
    print_status();
    println!("=== logs ===");
    print_logs();
}

pub fn import_config_text(content: &str) -> Result<()> {
    let config = parse_config(content)?;
    let changed = read_config().map_or(true, |current| {
        config_json(&current) != config_json(&config)
    });
    if changed {
        ensure_no_active_restore_transaction()?;
        pause_protection_for_backup_change(
            "partition configuration changed; recheck, backup, verify, and enable again",
        )?;
        remove_file_if_exists(Path::new(ENVIRONMENT_CHECK_PATH))?;
    }
    write_config(&config)?;
    if changed {
        atomic_write(Path::new(CONFIG_CHANGED_PATH), b"1")?;
    }
    append_log("rescue config updated by manager");
    Ok(())
}

pub fn import_image(partition: &str, source: &Path, force: bool) -> Result<()> {
    let result = import_image_inner(partition, source, force);
    if let Err(err) = &result {
        append_log(format!("image import failed: {err:#}"));
    }
    result
}

fn import_image_inner(partition: &str, source: &Path, force: bool) -> Result<()> {
    let name = normalize_partition_name(partition)?;
    let config = read_config()?;
    let specs = partition_specs(&config);
    let spec = specs
        .iter()
        .find(|item| item.label == name)
        .cloned()
        .unwrap_or_else(|| current_slot_spec(&name, name == "boot", &config));

    let source = ensure_safe_import_source(source)?;
    let Some(device) = find_partition(&spec)? else {
        bail!("{} partition is missing", spec.name);
    };
    validate_import_source_against_partition(&source, &spec, &device)?;

    if Path::new(&spec.image_path).exists() && !force {
        bail!(
            "{} backup already exists; pass --force to overwrite it",
            spec.name
        );
    }

    ensure_no_active_restore_transaction()?;
    utils::ensure_dir_exists(RESCUE_DIR)?;
    let protected_files = vec![spec.image_path.clone(), MANIFEST_PATH.to_string()];
    preserve_files(&protected_files)?;
    if let Err(error) = pause_protection_for_backup_change(
        "a rescue image is being replaced; run full verification before enabling again",
    ) {
        cleanup_preserved_files(&protected_files);
        return Err(error);
    }
    let import_result = (|| -> Result<()> {
        atomic_copy(&source, Path::new(&spec.image_path)).with_context(|| {
            format!(
                "failed to import {} backup from {}",
                spec.name,
                source.display()
            )
        })?;
        validate_image_size_against_partition(&spec, &device)?;
        write_manifest(&specs)
    })();
    if let Err(err) = import_result {
        if let Err(restore_err) = restore_preserved_files(&protected_files) {
            bail!("import failed: {err:#}; previous backup restore failed: {restore_err:#}");
        }
        return Err(err).context("previous rescue backup was restored");
    }
    cleanup_preserved_files(&protected_files);
    append_log(format!(
        "imported {} backup from {}, sha256={}",
        spec.name,
        source.display(),
        sha256_of(&spec.image_path)
    ));
    Ok(())
}

pub fn backup(force: bool) -> Result<()> {
    utils::ensure_dir_exists(RESCUE_DIR)?;
    let config = read_config()?;
    let specs = partition_specs(&config);
    let backup_exists = Path::new(MANIFEST_PATH).exists()
        || specs
            .iter()
            .any(|spec| Path::new(&spec.image_path).exists());
    if backup_exists && !force {
        bail!("backup already exists; pass --force to overwrite it");
    }
    ensure_no_active_restore_transaction()?;
    append_log("backup requested by manager");
    let protected_files = rescue_file_paths(&specs);
    preserve_files(&protected_files)?;
    if let Err(error) = pause_protection_for_backup_change(
        "rescue backups are being refreshed; run full verification before enabling again",
    ) {
        cleanup_preserved_files(&protected_files);
        return Err(error);
    }
    let backup_result = (|| -> Result<()> {
        for spec in &specs {
            let Some(device) = find_partition(spec)? else {
                if spec.required {
                    bail!("{} partition is missing", spec.name);
                }
                // Do not leave an old optional image looking usable when the
                // partition disappeared. The previous file is already
                // protected by preserve_files and can still be restored if
                // a later backup step fails.
                remove_file_if_exists(Path::new(&spec.image_path))?;
                append_log(format!("skip backup: {} partition not found", spec.name));
                continue;
            };
            backup_partition(spec, &device)?;
        }
        write_manifest(&specs)
    })();

    if let Err(err) = backup_result {
        if let Err(restore_err) = restore_preserved_files(&protected_files) {
            bail!("backup failed: {err:#}; previous backup restore failed: {restore_err:#}");
        }
        append_log(format!(
            "backup failed; restored previous rescue backups: {err:#}"
        ));
        return Err(err);
    }

    cleanup_preserved_files(&protected_files);
    remove_file_if_exists(Path::new(CONFIG_CHANGED_PATH))?;
    Ok(())
}

pub fn enable() -> Result<()> {
    // Enabling must never erase evidence for an image change that has not
    // survived a boot yet. An explicit disable remains the escape hatch.
    ensure_no_active_restore_transaction()?;
    let config = read_config()?;
    let specs = partition_specs(&config);
    validate_backups_quick(&config)?;
    let manifest = read_manifest()?;
    if !verification_marker_is_current(&specs, &manifest) {
        return Err(coded_error(
            "rescue.full_verification_required",
            "run a full backup verification before enabling rescue protection",
        ));
    }
    utils::ensure_dir_exists(RESCUE_DIR)?;
    clear_runtime_markers()?;
    write_failure_baseline()?;
    atomic_write(Path::new(ENABLED_PATH), b"1").context("failed to enable rescue protection")?;
    let initialize_result = (|| -> Result<()> {
        atomic_write(Path::new(BOOT_OK_PATH), b"1")
            .context("failed to mark current boot as healthy")?;
        atomic_write(Path::new(BOOT_COUNT_PATH), b"0")
            .context("failed to reset rescue boot counter")?;
        atomic_write(Path::new(AUTO_RESTORE_ATTEMPTS_PATH), b"0")
            .context("failed to reset rescue restore attempts")
    })();
    if let Err(err) = initialize_result {
        let _ = remove_file_if_exists(Path::new(ENABLED_PATH));
        return Err(err).context("rescue protection was not enabled");
    }
    append_log("rescue protection enabled");
    Ok(())
}

pub fn disable() -> Result<()> {
    utils::ensure_dir_exists(RESCUE_DIR)?;
    remove_file_if_exists(Path::new(ENABLED_PATH))
        .context("failed to disable rescue protection")?;
    remove_file_if_exists(Path::new(BOOT_COUNT_PATH))?;
    remove_file_if_exists(Path::new(AUTO_RESTORE_ATTEMPTS_PATH))?;
    remove_file_if_exists(Path::new(BOOT_OK_PATH))?;
    remove_file_if_exists(Path::new(FAILURE_BASELINE_PATH))?;
    clear_runtime_markers()?;
    append_log("rescue protection disabled");
    Ok(())
}

fn ensure_no_active_restore_transaction() -> Result<()> {
    if let Some(transaction) = read_restore_transaction_for_restore()?
        && !matches!(transaction.phase.as_str(), "completed" | "idle")
    {
        return Err(coded_error(
            "rescue.restore_in_progress",
            format!(
                "restore transaction {} is still in phase {}; finish or recover it before changing rescue backups",
                transaction.id, transaction.phase
            ),
        ));
    }
    if read_restore_pending_boot().is_some() {
        return Err(coded_error(
            "rescue.restore_validation_pending",
            "a restored image is still awaiting boot validation; complete or recover that boot before changing rescue backups",
        ));
    }
    if Path::new(PENDING_BOOT_PATH).exists() {
        return Err(coded_error(
            "rescue.image_validation_pending",
            "an image change is still awaiting boot validation; complete or recover that boot before changing rescue backups",
        ));
    }
    Ok(())
}

fn pause_protection_for_backup_change(reason: &str) -> Result<()> {
    utils::ensure_dir_exists(RESCUE_DIR)?;
    if read_restore_pending_boot().is_some() || Path::new(PENDING_BOOT_PATH).exists() {
        return Err(coded_error(
            "rescue.validation_pending",
            "cannot clear rescue runtime markers while an image change is awaiting boot validation",
        ));
    }
    let was_enabled =
        invalidate_protection_markers_at(Path::new(ENABLED_PATH), Path::new(VERIFIED_PATH))?;
    for path in [
        BOOT_COUNT_PATH,
        AUTO_RESTORE_ATTEMPTS_PATH,
        BOOT_OK_PATH,
        FAILURE_BASELINE_PATH,
    ] {
        remove_file_if_exists(Path::new(path))?;
    }
    clear_runtime_markers()?;
    if was_enabled {
        append_log(format!("rescue protection paused because {reason}"));
    } else {
        append_log(format!("rescue verification invalidated because {reason}"));
    }
    Ok(())
}

fn invalidate_protection_markers_at(enabled: &Path, verified: &Path) -> Result<bool> {
    let was_enabled = enabled.exists();
    remove_file_if_exists(enabled)?;
    remove_file_if_exists(verified)?;
    Ok(was_enabled)
}

pub fn restore_now() -> Result<()> {
    restore_keep_data_now()
}

pub fn restore_keep_data_now() -> Result<()> {
    append_log("manual data-preserving rollback requested by manager");
    restore_backups("manual data-preserving rollback", false)
}

fn ensure_restore_validation_can_start() -> Result<()> {
    if read_restore_pending_boot().is_some()
        && restore_pending_boot_state(read_boot_count()) != RestorePendingBootState::Previous
    {
        return Err(coded_error(
            "rescue.restore_validation_pending",
            "a previous restore is still awaiting boot validation",
        ));
    }
    Ok(())
}

fn write_restore_pending_boot(transaction: &RestoreTransaction) -> Result<()> {
    let marker = RestorePendingBoot {
        transaction_id: transaction.id.clone(),
        armed_boot_id: current_boot_id().unwrap_or_default(),
        validation_boot_id: String::new(),
        armed_at: Local::now().to_rfc3339(),
    };
    write_restore_pending_boot_marker(&marker)
}

// Prefer the new field, but only accept the legacy field when it agrees with
// it.  A malformed or conflicting marker must be treated as unconsumed so a
// boot-completed callback cannot erase the only recovery evidence.
fn marker_armed_boot_id(marker: &Value) -> Option<String> {
    match (marker.get("armedBootId"), marker.get("bootId")) {
        (Some(new), Some(legacy)) => {
            let new = new.as_str()?;
            let legacy = legacy.as_str()?;
            (new == legacy).then(|| new.to_owned())
        }
        (Some(value), None) | (None, Some(value)) => value.as_str().map(ToOwned::to_owned),
        (None, None) => None,
    }
}

fn read_restore_pending_boot() -> Option<RestorePendingBoot> {
    if Path::new(RESTORE_PENDING_BOOT_PATH).exists() {
        let Some(value) = read_json_file(RESTORE_PENDING_BOOT_PATH) else {
            append_log("restore boot validation marker is invalid; using counter fallback");
            return Some(RestorePendingBoot::default());
        };
        return Some(RestorePendingBoot {
            transaction_id: value
                .get("transactionId")
                .and_then(Value::as_str)
                .unwrap_or_default()
                .to_owned(),
            // `bootId` was the field used by the first version of this marker;
            // keep accepting it when no conflicting new field is present.
            armed_boot_id: marker_armed_boot_id(&value).unwrap_or_default(),
            validation_boot_id: value
                .get("validationBootId")
                .and_then(Value::as_str)
                .unwrap_or_default()
                .to_owned(),
            armed_at: value
                .get("armedAt")
                .and_then(Value::as_str)
                .unwrap_or_default()
                .to_owned(),
        });
    }

    // Older ApkeSU builds used a plain lock file.  Treat the first boot seen
    // after migration as the validation boot, rather than as the boot that
    // armed the restore.  Otherwise a failed restore would get one extra
    // reboot before it could be recovered.
    if !Path::new(RESTORE_LOCK_PATH).exists() {
        return None;
    }
    let marker = RestorePendingBoot {
        transaction_id: "legacy-restore".to_owned(),
        armed_boot_id: String::new(),
        validation_boot_id: current_boot_id().unwrap_or_default(),
        armed_at: Local::now().to_rfc3339(),
    };
    if let Err(error) = write_restore_pending_boot_marker(&marker) {
        append_log(format!(
            "failed to migrate legacy restore lock; using counter fallback: {error:#}"
        ));
    } else if let Err(error) = remove_file_if_exists(Path::new(RESTORE_LOCK_PATH)) {
        append_log(format!(
            "failed to remove migrated legacy restore lock: {error:#}"
        ));
    }
    Some(marker)
}

fn write_restore_pending_boot_marker(marker: &RestorePendingBoot) -> Result<()> {
    let value = json!({
        "schemaVersion": 2,
        "transactionId": marker.transaction_id,
        "armedBootId": marker.armed_boot_id,
        // Keep the old key for one release so an older Manager can still
        // display the marker without changing its meaning.
        "bootId": marker.armed_boot_id,
        "validationBootId": marker.validation_boot_id,
        "armedAt": marker.armed_at,
    });
    atomic_write(
        Path::new(RESTORE_PENDING_BOOT_PATH),
        value.to_string().as_bytes(),
    )
    .context("failed to persist restore boot validation marker")
}

fn restore_pending_boot_state(boot_count: u32) -> RestorePendingBootState {
    let Some(marker) = read_restore_pending_boot() else {
        return RestorePendingBootState::None;
    };
    let current_boot_id = current_boot_id().unwrap_or_default();

    if !marker.validation_boot_id.is_empty() {
        if !current_boot_id.is_empty() && marker.validation_boot_id == current_boot_id {
            return RestorePendingBootState::Current;
        }
        if current_boot_id.is_empty() && boot_count < PENDING_BOOT_FAILURE_TRIGGER_COUNT {
            return RestorePendingBootState::Current;
        }
        return RestorePendingBootState::Previous;
    }

    if !marker.armed_boot_id.is_empty() && !current_boot_id.is_empty() {
        if marker.armed_boot_id == current_boot_id {
            // The restore command can be invoked more than once before the
            // reboot request is accepted. Do not start another restore in the
            // boot that created the marker.
            return RestorePendingBootState::Current;
        }

        let mut observed = marker;
        observed.validation_boot_id = current_boot_id;
        if let Err(error) = write_restore_pending_boot_marker(&observed) {
            append_log(format!(
                "failed to persist restore validation boot ID; using counter fallback: {error:#}"
            ));
            if boot_count >= PENDING_BOOT_FAILURE_TRIGGER_COUNT {
                return RestorePendingBootState::Previous;
            }
        }
        return RestorePendingBootState::Current;
    }

    if boot_count >= PENDING_BOOT_FAILURE_TRIGGER_COUNT {
        RestorePendingBootState::Previous
    } else {
        // A missing boot ID is unusual. Give one boot a chance to reach the
        // completion callback, then use the persistent counter as fallback.
        RestorePendingBootState::Current
    }
}

fn restore_pending_boot_state_from_marker(
    marker: &RestorePendingBoot,
    current_boot_id: &str,
    boot_count: u32,
) -> RestorePendingBootState {
    if !marker.validation_boot_id.is_empty() {
        if marker.validation_boot_id == current_boot_id
            || (current_boot_id.is_empty() && boot_count < PENDING_BOOT_FAILURE_TRIGGER_COUNT)
        {
            RestorePendingBootState::Current
        } else {
            RestorePendingBootState::Previous
        }
    } else if !marker.armed_boot_id.is_empty() && marker.armed_boot_id == current_boot_id {
        RestorePendingBootState::Current
    } else if marker.armed_boot_id.is_empty() || current_boot_id.is_empty() {
        if boot_count >= PENDING_BOOT_FAILURE_TRIGGER_COUNT {
            RestorePendingBootState::Previous
        } else {
            RestorePendingBootState::Current
        }
    } else {
        // The marker may have been created on an earlier boot while the
        // validation boot ID could not be persisted. Use the durable counter
        // as a bounded fallback instead of keeping modules skipped forever.
        if boot_count >= PENDING_BOOT_FAILURE_TRIGGER_COUNT {
            RestorePendingBootState::Previous
        } else {
            RestorePendingBootState::Current
        }
    }
}

fn pending_boot_armed_in_current_boot() -> bool {
    if !Path::new(PENDING_BOOT_PATH).exists() {
        return false;
    }
    let Some(marker) = read_json_file(PENDING_BOOT_PATH) else {
        // An unreadable pending marker is safer to preserve than to erase.
        return true;
    };
    let current_boot_id = current_boot_id().ok();
    marker_armed_in_boot(&marker, current_boot_id.as_deref(), false)
}

fn marker_armed_in_current_boot_id(marker: &Value, current_boot_id: Option<&str>) -> bool {
    let Some(current_boot_id) = current_boot_id.filter(|id| !id.is_empty()) else {
        return false;
    };
    marker_armed_boot_id(marker)
        .is_some_and(|armed_boot_id| !armed_boot_id.is_empty() && armed_boot_id == current_boot_id)
}

fn pending_boot_marker_is_current_boot() -> bool {
    let Some(marker) = read_json_file(PENDING_BOOT_PATH) else {
        return false;
    };
    marker_armed_in_current_boot_id(&marker, current_boot_id().ok().as_deref())
}

fn restore_boot_armed_in_current_boot() -> bool {
    if !Path::new(RESTORE_PENDING_BOOT_PATH).exists() {
        return false;
    }
    let Some(marker) = read_json_file(RESTORE_PENDING_BOOT_PATH) else {
        return true;
    };
    restore_marker_blocks_boot_commit(&marker, current_boot_id().ok().as_deref())
}

fn restore_marker_blocks_boot_commit(marker: &Value, current_boot_id: Option<&str>) -> bool {
    let Some(current_boot_id) = current_boot_id else {
        return true;
    };
    let Some(validation_boot_id) = marker.get("validationBootId").and_then(Value::as_str) else {
        return true;
    };
    if validation_boot_id.is_empty() || validation_boot_id != current_boot_id {
        return true;
    }

    // If both fields are present, marker_armed_boot_id rejects conflicts. An
    // armed ID equal to the validation boot is also inconsistent, so retain
    // the marker conservatively.
    let has_armed_boot_field =
        marker.get("armedBootId").is_some() || marker.get("bootId").is_some();
    let Some(armed_boot_id) = marker_armed_boot_id(marker) else {
        // The one supported marker without an armed boot ID is the migration
        // form generated from the legacy restore lock. Missing, malformed, or
        // conflicting IDs in every other marker must remain fail-closed.
        return marker.get("transactionId").and_then(Value::as_str) != Some("legacy-restore")
            || has_armed_boot_field;
    };
    if armed_boot_id.is_empty() {
        // The only intentionally empty armed ID is the one-time migration of
        // the old restore lock. Any other missing ID is unverified evidence.
        return marker.get("transactionId").and_then(Value::as_str) != Some("legacy-restore");
    }
    armed_boot_id == current_boot_id
}

fn marker_armed_in_boot(
    marker: &Value,
    current_boot_id: Option<&str>,
    require_unvalidated: bool,
) -> bool {
    let Some(armed_boot_id) = marker_armed_boot_id(marker) else {
        return true;
    };
    if armed_boot_id.is_empty() {
        return true;
    }
    let Some(current_boot_id) = current_boot_id else {
        return true;
    };
    if armed_boot_id != current_boot_id {
        return false;
    }
    if !require_unvalidated {
        return true;
    }
    marker
        .get("validationBootId")
        .is_none_or(|value| value.as_str().is_some_and(str::is_empty))
}

fn restore_transaction_is_pending() -> bool {
    if !Path::new(RESTORE_TRANSACTION_PATH).exists() {
        return false;
    }
    match read_restore_transaction() {
        Ok(Some(transaction)) if matches!(transaction.phase.as_str(), "completed" | "idle") => {
            false
        }
        Ok(Some(transaction)) => {
            if let Err(error) = validate_restore_transaction_shape(&transaction) {
                match quarantine_invalid_restore_transaction(Path::new(RESTORE_TRANSACTION_PATH)) {
                    Ok(path) => append_log(format!(
                        "quarantined structurally invalid restore transaction: {} ({error:#})",
                        path.display()
                    )),
                    Err(quarantine_error) => append_log(format!(
                        "failed to quarantine structurally invalid restore transaction: {quarantine_error:#}"
                    )),
                }
                // Keep the rescue path active. The next restore attempt will
                // build a fresh transaction from the verified backup set.
            }
            true
        }
        Ok(None) => false,
        Err(error) => {
            match quarantine_invalid_restore_transaction(Path::new(RESTORE_TRANSACTION_PATH)) {
                Ok(path) => append_log(format!(
                    "quarantined invalid restore transaction after parse failure: {}",
                    path.display()
                )),
                Err(quarantine_error) => append_log(format!(
                    "failed to quarantine invalid restore transaction: {quarantine_error:#}"
                )),
            }
            append_log(format!(
                "restore transaction cannot be parsed; recovery will be attempted from a fresh transaction: {error:#}"
            ));
            true
        }
    }
}

fn read_restore_transaction_for_restore() -> Result<Option<RestoreTransaction>> {
    match read_restore_transaction() {
        Ok(Some(transaction)) if !matches!(transaction.phase.as_str(), "completed" | "idle") => {
            if let Err(error) = validate_restore_transaction_shape(&transaction) {
                let path =
                    quarantine_invalid_restore_transaction(Path::new(RESTORE_TRANSACTION_PATH))
                        .with_context(|| {
                            format!(
                                "invalid restore transaction could not be quarantined: {error:#}"
                            )
                        })?;
                append_log(format!(
                    "quarantined structurally invalid restore transaction before restore: {}",
                    path.display()
                ));
                Ok(None)
            } else {
                Ok(Some(transaction))
            }
        }
        Ok(transaction) => Ok(transaction),
        Err(error) => {
            let path = quarantine_invalid_restore_transaction(Path::new(RESTORE_TRANSACTION_PATH))
                .with_context(|| {
                    format!("invalid restore transaction could not be quarantined: {error:#}")
                })?;
            append_log(format!(
                "quarantined invalid restore transaction before creating a new one: {}",
                path.display()
            ));
            Ok(None)
        }
    }
}

fn quarantine_invalid_restore_transaction(path: &Path) -> Result<PathBuf> {
    if !path.exists() {
        bail!("restore transaction disappeared before quarantine")
    }
    let file_name = path
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or("restore_transaction.json");
    let nonce = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let destination = path.with_file_name(format!(
        "{file_name}.invalid-{nonce}-{}",
        std::process::id()
    ));
    fs::rename(path, &destination).with_context(|| {
        format!(
            "failed to quarantine invalid restore transaction {}",
            path.display()
        )
    })?;
    if let Some(parent) = path.parent() {
        sync_parent_directory(parent);
    }
    Ok(destination)
}

pub fn mark_next_boot_pending(reason: &str) -> Result<()> {
    if !is_enabled() {
        return Ok(());
    }

    // A restore validation boot owns the image state until it reaches
    // boot-completed. An external writer must not replace that marker and
    // make the restore transaction unrecoverable.
    if restore_transaction_is_pending() || read_restore_pending_boot().is_some() {
        return Err(coded_error(
            "rescue.restore_validation_pending",
            "an image restore is still awaiting boot validation",
        ));
    }

    // Protection may have been left enabled by an older build or by manual
    // file edits while its verified backup set is no longer usable. Do not
    // claim to arm verification in that state: a later failure would have no
    // trusted image to restore.
    let config = read_config()?;
    let specs = partition_specs(&config);
    let manifest = read_manifest()?;
    if !verification_marker_is_current(&specs, &manifest) {
        return Err(coded_error(
            "rescue.full_verification_required",
            "rescue protection is enabled but its backup set is not fully verified",
        ));
    }

    utils::ensure_dir_exists(RESCUE_DIR).context("failed to prepare pending boot marker")?;
    if let Err(error) = write_failure_baseline() {
        // The failure baseline is diagnostic evidence.  A failed baseline must
        // not discard the core pending marker after an image was flashed.
        append_log(format!(
            "failed to refresh failure baseline before pending boot: {error:#}"
        ));
    }
    let armed_boot_id = current_boot_id().unwrap_or_default();
    let pending = json!({
        "schemaVersion": 2,
        "reason": reason,
        "armedAt": Local::now().to_rfc3339(),
        "armedBootId": armed_boot_id,
        // Keep the legacy key for one release so older boot hooks can still
        // recognize a pending image change.
        "bootId": armed_boot_id,
    });
    atomic_write(Path::new(PENDING_BOOT_PATH), pending.to_string().as_bytes())
        .context("failed to write pending boot marker")?;
    remove_file_if_exists(Path::new(BOOT_OK_PATH))?;
    if let Err(error) = write_boot_count(0) {
        append_log(format!(
            "failed to reset boot counter for pending boot: {error:#}"
        ));
    }
    if let Err(error) = write_auto_restore_attempts(0) {
        append_log(format!(
            "failed to reset auto restore attempts for pending boot: {error:#}"
        ));
    }
    append_log(format!("next boot marked pending: {reason}"));
    Ok(())
}

pub fn print_logs() {
    let logs = tail_file(LOG_PATH, 240).unwrap_or_default();
    if logs.is_empty() {
        println!("No rescue logs yet.");
    } else {
        println!("{logs}");
    }
}

pub fn clear_logs() -> Result<()> {
    utils::ensure_dir_exists(RESCUE_DIR)?;
    atomic_write(Path::new(LOG_PATH), b"").context("failed to clear rescue log")?;
    for index in 1..=LOG_ROTATION_COUNT {
        remove_file_if_exists(Path::new(&format!("{LOG_PATH}.{index}")))?;
    }
    append_log("rescue log cleared");
    Ok(())
}

pub fn take_skip_modules_once() -> bool {
    if Path::new(SKIP_MODULES_THIS_BOOT_PATH).exists() {
        consume_skip_module_markers();
        return true;
    }
    let skip_once = skip_modules_once_exists();
    let legacy_skip_once = Path::new(LEGACY_TMP_MODULE_DISABLE_PATH).exists();
    if !skip_once && !legacy_skip_once {
        return false;
    }

    // Persist the per-boot guard before consuming the durable request. If the
    // device is out of space or /dev is temporarily unavailable, retaining the
    // durable marker is safer than silently losing the rescue request.
    if let Err(err) = atomic_write(Path::new(SKIP_MODULES_THIS_BOOT_PATH), b"1") {
        append_log(format!("failed to mark module skip guard: {err:#}"));
        return true;
    }
    consume_skip_module_markers();
    append_log(format!(
        "rescue requested temporary module skip for this boot: skip_once={skip_once}, legacy_skip_once={legacy_skip_once}"
    ));
    true
}

pub fn should_skip_modules_this_boot() -> bool {
    Path::new(SKIP_MODULES_THIS_BOOT_PATH).exists()
        || skip_modules_once_exists()
        || Path::new(LEGACY_TMP_MODULE_DISABLE_PATH).exists()
}

fn consume_skip_module_markers() {
    for path in [
        SKIP_MODULES_ONCE_PATH,
        CACHE_SKIP_MODULES_ONCE_PATH,
        LEGACY_TMP_MODULE_DISABLE_PATH,
    ] {
        if let Err(error) = remove_file_if_exists(Path::new(path)) {
            append_log(format!(
                "failed to consume module skip marker {path}: {error:#}"
            ));
        }
    }
}

pub fn check_on_post_fs_data() {
    if !is_enabled() {
        return;
    }

    if is_recovery_boot() {
        check_on_recovery_boot();
        return;
    }

    let previous_boot_ok = Path::new(BOOT_OK_PATH).exists();
    let pending_boot = Path::new(PENDING_BOOT_PATH).exists();
    if pending_boot && pending_boot_marker_is_current_boot() {
        // post-fs-data can be invoked again during a soft reboot or by a
        // vendor init replay. The marker was created in this same boot, so
        // counting this invocation as another failed boot could roll back a
        // healthy image before the device has actually rebooted.
        append_log("pending image marker belongs to the current boot; defer rescue decision");
        return;
    }
    let next_count = if previous_boot_ok {
        1
    } else {
        read_boot_count().saturating_add(1)
    };
    if let Err(error) = write_boot_count(next_count) {
        append_log(format!("failed to update boot counter: {error:#}"));
        return;
    }
    if let Err(error) = remove_file_if_exists(Path::new(BOOT_OK_PATH)) {
        append_log(format!("failed to consume healthy boot marker: {error:#}"));
        return;
    }

    let restore_state = restore_pending_boot_state(next_count);
    if restore_state == RestorePendingBootState::Current {
        append_log(
            "restored image boot is still being validated; defer healthy marker until boot-completed",
        );
        mark_skip_modules_once();
        return;
    }
    let restore_pending_boot = restore_state == RestorePendingBootState::Previous;
    let restore_transaction_pending = restore_transaction_is_pending();

    append_log(format!(
        "post-fs-data rescue check: previous_boot_ok={previous_boot_ok}, pending_boot={pending_boot}, boot_count={next_count}"
    ));

    // On the first patched boot, pstore still describes the boot that performed the
    // flash. Only trust failure evidence after another attempted boot.
    let failure_hint = next_count > 1 && has_boot_failure_hint();
    match post_fs_data_rescue_action(BootRescueSignals {
        pending_boot,
        restore_pending_boot,
        restore_transaction_pending,
        previous_boot_ok,
        boot_count: next_count,
        failure_hint,
    }) {
        BootRescueAction::RestoreBackups => {
            append_log(format!(
                "auto image rollback triggered on post-fs-data: failure_hint={failure_hint}, pending_boot={pending_boot}, boot_count={next_count}"
            ));
            if let Err(err) = auto_restore_backups() {
                let details = format!("{err:#}");
                append_log(format!("auto image rollback failed: {details}"));
                rescue_modules_for_failed_boot(format!(
                    "automatic image rollback failed; modules were isolated: {details}"
                ));
            }
        }
        BootRescueAction::DisableModules => {
            rescue_modules_for_failed_boot(format!(
                "unverified boot failure: failure_hint={failure_hint}, boot_count={next_count}"
            ));
        }
        BootRescueAction::None => {
            if !pending_boot {
                append_log(
                    "no pending flashed boot; image rollback remains armed only for verified flashes",
                );
            }
        }
    }
}

pub fn check_on_recovery_boot() {
    if !is_enabled() || !is_recovery_boot() {
        return;
    }

    if Path::new(RECOVERY_CHECK_GUARD_PATH).exists() {
        append_log("recovery rescue check already ran in this boot");
        return;
    }
    if let Err(err) = atomic_write(Path::new(RECOVERY_CHECK_GUARD_PATH), b"1") {
        append_log(format!("failed to write recovery check guard: {err:#}"));
    }

    let previous_boot_ok = Path::new(BOOT_OK_PATH).exists();
    let pending_boot = Path::new(PENDING_BOOT_PATH).exists();
    let next_count = if previous_boot_ok {
        1
    } else {
        read_boot_count().saturating_add(1)
    };
    if let Err(error) = write_boot_count(next_count) {
        append_log(format!("failed to update recovery boot counter: {error:#}"));
        return;
    }
    if let Err(error) = remove_file_if_exists(Path::new(BOOT_OK_PATH)) {
        append_log(format!("failed to consume healthy boot marker: {error:#}"));
        return;
    }

    let restore_state = restore_pending_boot_state(next_count);
    if restore_state == RestorePendingBootState::Current {
        append_log(
            "restored image recovery boot is still being validated; defer healthy marker until boot-completed",
        );
        mark_skip_modules_once();
        return;
    }
    let restore_pending_boot = restore_state == RestorePendingBootState::Previous;
    let restore_transaction_pending = restore_transaction_is_pending();

    let failure_hint = has_boot_failure_hint();
    append_log(format!(
        "recovery rescue check: boot_mode={}, previous_boot_ok={previous_boot_ok}, pending_boot={pending_boot}, boot_count={next_count}, failure_hint={failure_hint}",
        boot_mode()
    ));

    match recovery_boot_rescue_action(BootRescueSignals {
        pending_boot,
        restore_pending_boot,
        restore_transaction_pending,
        previous_boot_ok,
        boot_count: next_count,
        failure_hint,
    }) {
        BootRescueAction::RestoreBackups => {
            if let Err(err) = auto_restore_backups() {
                let details = format!("{err:#}");
                append_log(format!("auto image rollback failed in recovery: {details}"));
                rescue_modules_for_failed_boot(format!(
                    "automatic recovery rollback failed; modules were isolated: {details}"
                ));
            }
        }
        BootRescueAction::DisableModules => {
            rescue_modules_for_failed_boot(
                "recovery boot failure evidence without a pending image flash",
            );
        }
        BootRescueAction::None => {}
    }
}

pub fn mark_boot_completed() {
    if !is_enabled() {
        return;
    }

    if let Err(err) = utils::ensure_dir_exists(RESCUE_DIR) {
        append_log(format!(
            "failed to create rescue dir on boot completed: {err:#}"
        ));
        return;
    }

    // A restore or external image flash can be initiated before the reboot
    // request is accepted. Do not turn that still-running boot into a healthy
    // validation boot and erase the only recovery marker.
    if restore_transaction_is_pending()
        || pending_boot_armed_in_current_boot()
        || restore_boot_armed_in_current_boot()
    {
        append_log(
            "boot-completed arrived while an image change still needs validation; keep rescue markers",
        );
        return;
    }

    if let Err(error) = write_boot_count(0) {
        append_log(format!(
            "failed to reset boot counter at boot completion: {error:#}"
        ));
        return;
    }
    if let Err(error) = write_auto_restore_attempts(0) {
        append_log(format!(
            "failed to reset auto restore attempts at boot completion: {error:#}"
        ));
        return;
    }
    if let Err(error) = write_failure_baseline() {
        append_log(format!(
            "failed to refresh boot failure baseline: {error:#}"
        ));
    }
    // Remove the restore-attempt marker before committing boot_ok. A boot that
    // reaches this callback has passed the validation point; keeping the
    // marker would make the next boot look like an interrupted restore. Do not
    // commit boot_ok if cleanup failed: the next boot must remain rescueable.
    if let Err(error) = clear_runtime_markers() {
        append_log(format!(
            "failed to clear rescue runtime markers at boot completion: {error:#}"
        ));
        return;
    }
    if let Err(error) = atomic_write(Path::new(BOOT_OK_PATH), b"1") {
        append_log(format!("failed to commit healthy boot marker: {error:#}"));
        return;
    }
    cleanup_legacy_rescue_flags();
    append_log("boot completed; rescue counter reset");
}

fn partition_specs(config: &RescueConfig) -> Vec<PartitionSpec> {
    base_partition_names(config)
        .into_iter()
        .map(|(name, required)| current_slot_spec(name, required, config))
        .chain(other_slot_specs(config))
        .collect()
}

fn base_partition_names(config: &RescueConfig) -> Vec<(&'static str, bool)> {
    let mut names = vec![("boot", true), ("vendor_boot", false), ("init_boot", false)];
    if config.include_dtbo {
        names.push(("dtbo", false));
    }
    if config.include_vbmeta {
        names.push(("vbmeta", false));
    }
    names
}

fn current_slot_spec(name: &str, required: bool, config: &RescueConfig) -> PartitionSpec {
    let custom_path = config.custom_partitions.get(name).cloned();
    PartitionSpec {
        name: name.to_string(),
        label: name.to_string(),
        image_path: format!("{RESCUE_DIR}{name}.img"),
        required,
        custom_path,
        ota: false,
        restore: true,
    }
}

fn other_slot_specs(config: &RescueConfig) -> Vec<PartitionSpec> {
    if !config.backup_other_slot || current_slot().is_empty() {
        return Vec::new();
    }

    base_partition_names(config)
        .into_iter()
        .filter(|(name, _)| !config.custom_partitions.contains_key(*name))
        .map(|(name, _required)| PartitionSpec {
            name: name.to_string(),
            label: format!("{name}_other"),
            image_path: format!("{RESCUE_DIR}{name}_other.img"),
            required: false,
            custom_path: None,
            ota: true,
            restore: false,
        })
        .collect()
}

fn validate_environment(specs: &[PartitionSpec]) -> Result<()> {
    utils::ensure_dir_exists(RESCUE_DIR)?;
    for spec in specs {
        if find_partition(spec)?.is_none() && spec.required {
            bail!("{} partition is missing", spec.name);
        }
    }
    Ok(())
}

fn validate_backups_quick(config: &RescueConfig) -> Result<()> {
    validate_backups_with_mode(config, false, false)
}

fn validate_backups_full(config: &RescueConfig, refresh_hashes: bool) -> Result<()> {
    validate_backups_with_mode(config, true, refresh_hashes)
}

fn validate_backups_with_mode(
    config: &RescueConfig,
    verify_sha256: bool,
    refresh_hashes: bool,
) -> Result<()> {
    let plans = restore_plans(config)?;
    let mut last_error = None;
    for plan in plans {
        match validate_restore_backups(&plan.specs, config, false, verify_sha256, refresh_hashes) {
            Ok(()) => return Ok(()),
            Err(err) => last_error = Some(err),
        }
    }
    if let Some(err) = last_error {
        return Err(err).context("no usable rescue backup set");
    }
    bail!("no usable rescue backup set");
}

fn validate_restore_backups(
    specs: &[PartitionSpec],
    config: &RescueConfig,
    automatic: bool,
    verify_sha256: bool,
    refresh_hashes: bool,
) -> Result<()> {
    let manifest = validate_manifest_context_for_restore()?;
    let mut available_count = 0usize;
    for spec in specs {
        if !should_restore_spec(spec, config, automatic) {
            continue;
        }

        let Some(device) = find_partition(spec)? else {
            if spec.required {
                bail!("{} partition is missing", spec.name);
            }
            append_log(format!(
                "skip restore validation: {} partition not found",
                spec.name
            ));
            continue;
        };

        let image = Path::new(&spec.image_path);
        if !image.is_file() {
            if spec.required {
                bail!("{} backup is missing", spec.name);
            }
            append_log(format!(
                "skip restore validation: {} backup not found",
                spec.name
            ));
            continue;
        }

        validate_image_against_partition(spec, &device, &manifest, verify_sha256, refresh_hashes)?;
        available_count += 1;
    }

    if available_count == 0 {
        bail!("no rescue backup is available to restore");
    }

    Ok(())
}

fn should_restore_spec(spec: &PartitionSpec, config: &RescueConfig, automatic: bool) -> bool {
    if !spec.restore {
        return false;
    }
    if automatic
        && is_dangerous_partition(&spec.name)
        && !config.dangerous_auto_restore.is_allowed()
    {
        append_log(format!(
            "skip auto restore: {} is a dangerous partition",
            spec.name
        ));
        return false;
    }
    true
}

fn validate_image_against_partition(
    spec: &PartitionSpec,
    device: &str,
    manifest: &Value,
    verify_sha256: bool,
    refresh_hash: bool,
) -> Result<()> {
    let image_size = fs::metadata(&spec.image_path)
        .with_context(|| format!("failed to read {} backup metadata", spec.name))?
        .len();
    if image_size == 0 {
        bail!("{} backup is empty", spec.name);
    }

    let device_size = partition_size(device);
    if device_size > 0 && image_size != device_size {
        bail!(
            "{} backup size mismatch: image={}, partition={}",
            spec.name,
            image_size,
            device_size
        );
    }

    if verify_sha256 {
        let expected = manifest_sha256_from(manifest, &spec.label)
            .filter(|digest| is_sha256_digest(digest))
            .ok_or_else(|| {
                coded_error(
                    "rescue.manifest_digest_missing",
                    format!("{} has no SHA256 in the rescue manifest", spec.label),
                )
            })?;
        let actual = cached_sha256(&spec.image_path, refresh_hash);
        if actual.is_empty() {
            return Err(coded_error(
                "rescue.checksum_unavailable",
                format!("failed to calculate {} backup SHA256", spec.name),
            ));
        }
        if !expected.eq_ignore_ascii_case(&actual) {
            return Err(coded_error(
                "rescue.checksum_mismatch",
                format!("{} backup SHA256 mismatch", spec.name),
            ));
        }
    }

    Ok(())
}

fn validate_image_size_against_partition(spec: &PartitionSpec, device: &str) -> Result<()> {
    let image_size = fs::metadata(&spec.image_path)
        .with_context(|| format!("failed to read {} backup metadata", spec.name))?
        .len();
    if image_size == 0 {
        bail!("{} backup is empty", spec.name);
    }

    let device_size = partition_size(device);
    if device_size > 0 && image_size != device_size {
        bail!(
            "{} backup size mismatch: image={}, partition={}",
            spec.name,
            image_size,
            device_size
        );
    }

    Ok(())
}

fn restore_plans(config: &RescueConfig) -> Result<Vec<RestorePlan>> {
    let manifest = validate_manifest_context_for_restore()?;
    let saved_slot = manifest_slot(&manifest);
    let now_slot = current_slot();
    if saved_slot.is_empty() || now_slot.is_empty() || saved_slot == now_slot {
        return Ok(vec![RestorePlan {
            description: "restore backups for current slot".to_string(),
            specs: partition_specs(config),
            activate_slot: None,
        }]);
    }

    let mut plans = Vec::new();
    if config.backup_other_slot {
        let current_slot_specs = current_slot_specs_from_other_backups(config);
        if current_slot_specs.iter().any(|spec| spec.required) {
            plans.push(RestorePlan {
                description: format!(
                    "slot changed from {saved_slot} to {now_slot}; use other-slot backups for current slot"
                ),
                specs: current_slot_specs,
                activate_slot: None,
            });
        }
    }

    plans.push(RestorePlan {
        description: format!(
            "slot changed from {saved_slot} to {now_slot}; restore backup slot and switch active slot back to {saved_slot}"
        ),
        specs: saved_slot_specs(config),
        activate_slot: Some(saved_slot),
    });
    Ok(plans)
}

fn current_slot_specs_from_other_backups(config: &RescueConfig) -> Vec<PartitionSpec> {
    base_partition_names(config)
        .into_iter()
        .filter(|(name, _)| !config.custom_partitions.contains_key(*name))
        .map(|(name, required)| PartitionSpec {
            name: name.to_string(),
            label: format!("{name}_other"),
            image_path: format!("{RESCUE_DIR}{name}_other.img"),
            required,
            custom_path: None,
            ota: false,
            restore: true,
        })
        .collect()
}

fn saved_slot_specs(config: &RescueConfig) -> Vec<PartitionSpec> {
    base_partition_names(config)
        .into_iter()
        .map(|(name, required)| {
            let custom_path = config.custom_partitions.get(name).cloned();
            PartitionSpec {
                name: name.to_string(),
                label: name.to_string(),
                image_path: format!("{RESCUE_DIR}{name}.img"),
                required,
                ota: custom_path.is_none(),
                custom_path,
                restore: true,
            }
        })
        .collect()
}

fn auto_restore_backups() -> Result<()> {
    let attempts = read_auto_restore_attempts();
    if attempts >= MAX_AUTO_RESTORE_ATTEMPTS {
        // Stop retrying before a persistent marker can cause an unbounded
        // restore loop. Keep the diagnostic counter and transaction record,
        // but clear only boot-scoped markers first so a later manual recovery
        // starts from a known state.
        if let Err(error) = clear_runtime_markers() {
            append_log(format!(
                "failed to clear runtime markers after restore limit: {error:#}"
            ));
        }
        if let Err(error) = remove_file_if_exists(Path::new(ENABLED_PATH)) {
            append_log(format!(
                "failed to disable rescue protection after restore limit: {error:#}"
            ));
        }
        append_log("auto restore attempt limit reached; rescue protection disabled");
        bail!("auto restore attempt limit reached");
    }
    write_auto_restore_attempts(attempts.saturating_add(1))?;
    restore_backups("auto data-preserving rollback after failed boot", true)
}

fn validate_restore_transaction_shape(transaction: &RestoreTransaction) -> Result<()> {
    if transaction.entries.is_empty() {
        return Err(coded_error(
            "rescue.restore_transaction_invalid",
            "unfinished restore transaction has no entries",
        ));
    }
    if !matches!(
        transaction.phase.as_str(),
        "prepared" | "writing" | "failed" | "awaiting_boot_validation"
    ) {
        return Err(coded_error(
            "rescue.restore_transaction_invalid",
            format!(
                "unfinished restore transaction has unsupported phase {}",
                transaction.phase
            ),
        ));
    }
    if let Some(slot) = transaction.activate_slot.as_deref()
        && let Err(error) = bootctl_slot_number(slot)
    {
        return Err(coded_error(
            "rescue.restore_transaction_invalid",
            format!("invalid restore transaction active slot {slot}: {error:#}"),
        ));
    }

    let mut labels = BTreeSet::new();
    for entry in &transaction.entries {
        if !is_known_partition(&entry.name) {
            return Err(coded_error(
                "rescue.restore_transaction_invalid",
                format!("unknown partition in restore transaction: {}", entry.name),
            ));
        }
        let expected_label = entry.name.clone();
        let other_label = format!("{}_other", entry.name);
        if entry.label != expected_label && entry.label != other_label {
            return Err(coded_error(
                "rescue.restore_transaction_invalid",
                format!("invalid restore transaction label: {}", entry.label),
            ));
        }
        if !labels.insert(entry.label.clone()) {
            return Err(coded_error(
                "rescue.restore_transaction_invalid",
                format!("duplicate restore transaction entry: {}", entry.label),
            ));
        }

        let expected_image = format!("{RESCUE_DIR}{}.img", entry.label);
        if entry.image_path != expected_image {
            return Err(coded_error(
                "rescue.restore_transaction_invalid",
                format!(
                    "restore transaction image path is outside rescue storage: {}",
                    entry.image_path
                ),
            ));
        }
        if entry.expected_size == 0 || !is_sha256_digest(&entry.expected_sha256) {
            return Err(coded_error(
                "rescue.restore_transaction_invalid",
                format!("invalid expected image metadata for {}", entry.label),
            ));
        }
        if !matches!(
            entry.status.as_str(),
            "pending" | "writing" | "failed" | "verified"
        ) {
            return Err(coded_error(
                "rescue.restore_transaction_invalid",
                format!("invalid restore transaction status for {}", entry.label),
            ));
        }

        let device = sanitize_partition_path(&entry.device_path).ok_or_else(|| {
            coded_error(
                "rescue.restore_transaction_invalid",
                format!("unsafe restore target path: {}", entry.device_path),
            )
        })?;
        if device != entry.device_path {
            return Err(coded_error(
                "rescue.restore_transaction_invalid",
                format!(
                    "restore target path is not normalized: {}",
                    entry.device_path
                ),
            ));
        }
    }
    Ok(())
}

fn validate_restore_transaction_for_resume(transaction: &RestoreTransaction) -> Result<()> {
    validate_restore_transaction_shape(transaction)?;

    for entry in &transaction.entries {
        validate_restore_target(entry)?;

        let image_size = fs::metadata(&entry.image_path)
            .with_context(|| format!("failed to read {} backup metadata", entry.label))?
            .len();
        if image_size != entry.expected_size {
            return Err(coded_error(
                "rescue.backup_size_changed",
                format!("{} backup size changed while resuming", entry.label),
            ));
        }
        if cached_sha256(&entry.image_path, true) != entry.expected_sha256 {
            return Err(coded_error(
                "rescue.backup_changed",
                format!("{} backup SHA256 changed while resuming", entry.label),
            ));
        }
        let device_size = partition_size(&entry.device_path);
        if device_size > 0 && device_size != entry.expected_size {
            return Err(coded_error(
                "rescue.partition_size_mismatch",
                format!(
                    "{} restore target size changed: image={}, partition={}",
                    entry.label, entry.expected_size, device_size
                ),
            ));
        }
    }
    Ok(())
}

fn validate_restore_target(entry: &RestoreTransactionEntry) -> Result<()> {
    if !restore_target_matches_entry(entry) {
        return Err(coded_error(
            "rescue.restore_transaction_invalid",
            format!(
                "restore target does not match {} partition semantics: {}",
                entry.name, entry.device_path
            ),
        ));
    }

    let device = sanitize_partition_path(&entry.device_path).ok_or_else(|| {
        coded_error(
            "rescue.restore_transaction_invalid",
            format!("unsafe restore target path: {}", entry.device_path),
        )
    })?;
    validate_partition_device_path(&entry.name, Path::new(&device))
        .map_err(|error| coded_error("rescue.restore_transaction_invalid", error.to_string()))?;
    Ok(())
}

fn is_sha256_digest(value: &str) -> bool {
    value.len() == 64 && value.bytes().all(|byte| byte.is_ascii_hexdigit())
}

fn restore_backups(reason: &str, automatic: bool) -> Result<()> {
    ensure_restore_validation_can_start()?;
    let config = read_config()?;
    let existing = read_restore_transaction_for_restore()?;
    let mut transaction = if let Some(existing) =
        existing.filter(|transaction| !matches!(transaction.phase.as_str(), "completed" | "idle"))
    {
        // Resume the persisted transaction before consulting the manifest. The
        // transaction already contains the exact verified backup and target
        // paths needed to finish a partial write.
        validate_restore_transaction_for_resume(&existing)?;
        if let Some(slot) = existing.activate_slot.as_deref() {
            validate_active_slot_switch(slot)?;
        }
        append_log(format!(
            "resuming restore transaction {} in phase {}",
            existing.id, existing.phase
        ));
        existing
    } else {
        let plans = restore_plans(&config)?;
        let mut selected_plan = None;
        let mut last_error = None;
        for plan in plans {
            match validate_restore_backups(&plan.specs, &config, automatic, true, false) {
                Ok(()) => {
                    selected_plan = Some(plan);
                    break;
                }
                Err(err) => {
                    append_log(format!("skip restore plan '{}': {err:#}", plan.description));
                    last_error = Some(err);
                }
            }
        }
        let Some(plan) = selected_plan else {
            if let Some(err) = last_error {
                return Err(err).context("no usable rescue restore plan");
            }
            bail!("no usable rescue restore plan");
        };

        if let Some(slot) = plan.activate_slot.as_deref() {
            validate_active_slot_switch(slot)?;
        }
        prepare_restore_transaction(reason, automatic, &plan, &config)?
    };

    append_log(format!("restore started: {reason}"));
    append_log(format!("restore plan: {}", transaction.description));
    append_log("restore mode: keep /data untouched; only configured boot images are restored");
    write_restore_transaction(&transaction)?;
    if let Err(error) = execute_restore_transaction(&mut transaction) {
        "failed".clone_into(&mut transaction.phase);
        transaction.error_code = error_code(&structured_error(anyhow!(format!("{error:#}"))));
        transaction.error_message = format!("{error:#}");
        transaction.updated_at = Local::now().to_rfc3339();
        if let Err(write_error) = write_restore_transaction(&transaction) {
            append_log(format!(
                "restore failed and transaction update also failed: {write_error:#}"
            ));
        }
        return Err(coded_error(
            "rescue.restore_interrupted",
            format!(
                "restore transaction {} stopped after a partial write: {error:#}",
                transaction.id
            ),
        ));
    }
    // Keep the transaction resumable until the validation marker and terminal
    // phase are both durable. If either write fails, the next boot will retry
    // from this transaction instead of assuming the restore was complete.
    write_restore_pending_boot(&transaction)?;
    "completed".clone_into(&mut transaction.phase);
    transaction.updated_at = Local::now().to_rfc3339();
    write_restore_transaction(&transaction)?;

    mark_skip_modules_once();
    mark_legacy_tmp_module_disable();
    disable_all_modules_for_rescue();
    mark_legacy_fix_done_lock();
    cleanup_legacy_rescue_flags();
    remove_file_if_exists(Path::new(BOOT_OK_PATH))?;
    write_boot_count(0)?;
    remove_file_if_exists(Path::new(PENDING_BOOT_PATH))?;
    append_log("restore finished; rebooting");
    let _ = Command::new("sync").status();
    request_reboot().context("restore completed but reboot request failed")?;
    Ok(())
}

fn prepare_restore_transaction(
    reason: &str,
    automatic: bool,
    plan: &RestorePlan,
    config: &RescueConfig,
) -> Result<RestoreTransaction> {
    let manifest = read_manifest()?;
    let mut entries = Vec::new();
    for spec in &plan.specs {
        if !should_restore_spec(spec, config, automatic) || !Path::new(&spec.image_path).is_file() {
            continue;
        }
        let Some(device_path) = find_partition(spec)? else {
            if spec.required {
                return Err(coded_error(
                    "rescue.partition_missing",
                    format!(
                        "{} partition disappeared after restore preflight",
                        spec.name
                    ),
                ));
            }
            continue;
        };
        let expected_sha256 = manifest_sha256_from(&manifest, &spec.label).unwrap_or_default();
        if expected_sha256.is_empty() {
            return Err(coded_error(
                "rescue.manifest_digest_missing",
                format!("{} has no SHA256 in the rescue manifest", spec.label),
            ));
        }
        entries.push(RestoreTransactionEntry {
            name: spec.name.clone(),
            label: spec.label.clone(),
            image_path: spec.image_path.clone(),
            device_path,
            expected_sha256,
            expected_size: fs::metadata(&spec.image_path)?.len(),
            status: "pending".to_owned(),
        });
    }
    if entries.is_empty() {
        return Err(coded_error(
            "rescue.no_restore_entries",
            "no rescue backup was selected for restore",
        ));
    }

    if let Some(existing) = read_restore_transaction_for_restore()?
        && let Some(resumable) =
            select_resumable_restore_transaction(existing, &entries, plan.activate_slot.as_deref())?
    {
        append_log(format!(
            "resuming interrupted restore transaction {} in phase {}",
            resumable.id, resumable.phase
        ));
        return Ok(resumable);
    }

    let now = Local::now();
    Ok(RestoreTransaction {
        id: format!("{}-{}", now.format("%Y%m%d%H%M%S"), std::process::id()),
        reason: reason.to_owned(),
        automatic,
        description: plan.description.clone(),
        activate_slot: plan.activate_slot.clone(),
        phase: "prepared".to_owned(),
        error_code: String::new(),
        error_message: String::new(),
        started_at: now.to_rfc3339(),
        updated_at: now.to_rfc3339(),
        entries,
    })
}

fn execute_restore_transaction(transaction: &mut RestoreTransaction) -> Result<()> {
    "writing".clone_into(&mut transaction.phase);
    transaction.error_code.clear();
    transaction.error_message.clear();
    transaction.updated_at = Local::now().to_rfc3339();
    write_restore_transaction(transaction)?;

    for index in 0..transaction.entries.len() {
        let entry = transaction.entries[index].clone();
        if entry.status == "verified"
            && cached_sha256(&entry.device_path, true) == entry.expected_sha256
        {
            append_log(format!(
                "restore transaction {}: {} remains verified; skip rewrite",
                transaction.id, entry.label
            ));
            continue;
        }

        "writing".clone_into(&mut transaction.entries[index].status);
        transaction.updated_at = Local::now().to_rfc3339();
        write_restore_transaction(transaction)?;
        append_log(format!(
            "restore transaction {}: writing {}",
            transaction.id, entry.label
        ));

        if let Err(error) = restore_transaction_entry(&entry) {
            "failed".clone_into(&mut transaction.entries[index].status);
            transaction.updated_at = Local::now().to_rfc3339();
            let _ = write_restore_transaction(transaction);
            return Err(error);
        }
        "verified".clone_into(&mut transaction.entries[index].status);
        transaction.updated_at = Local::now().to_rfc3339();
        write_restore_transaction(transaction)?;
    }

    if let Some(slot) = transaction.activate_slot.as_deref() {
        set_active_slot(slot)?;
    }
    // The partition writes and optional slot switch are complete, but the
    // restored image has not yet survived a full Android boot. Keep this
    // phase non-terminal so a process or device failure before the validation
    // marker is committed can be resumed automatically.
    "awaiting_boot_validation".clone_into(&mut transaction.phase);
    transaction.updated_at = Local::now().to_rfc3339();
    write_restore_transaction(transaction)
}

fn restore_transaction_entry(entry: &RestoreTransactionEntry) -> Result<()> {
    // Re-check immediately before opening the block device. The persisted
    // transaction may have crossed a process restart, and a by-name symlink
    // can change after the initial preflight.
    validate_restore_target(entry)?;
    let metadata = fs::metadata(&entry.image_path)
        .with_context(|| format!("failed to read {} backup metadata", entry.label))?;
    if metadata.len() != entry.expected_size {
        return Err(coded_error(
            "rescue.backup_size_changed",
            format!("{} backup size changed after preflight", entry.label),
        ));
    }
    let actual_backup_sha256 = cached_sha256(&entry.image_path, true);
    if actual_backup_sha256 != entry.expected_sha256 {
        return Err(coded_error(
            "rescue.backup_changed",
            format!("{} backup SHA256 changed after preflight", entry.label),
        ));
    }
    run_dd(&entry.image_path, &entry.device_path)
        .with_context(|| format!("failed to restore {}", entry.name))?;
    let actual_partition_sha256 = cached_sha256(&entry.device_path, true);
    if actual_partition_sha256 != entry.expected_sha256 {
        return Err(coded_error(
            "rescue.restore_verification_failed",
            format!(
                "{} restore verification failed: SHA256 mismatch",
                entry.label
            ),
        ));
    }
    append_log(format!("restore {} verified", entry.label));
    Ok(())
}

fn restore_transactions_match(
    existing: &RestoreTransaction,
    entries: &[RestoreTransactionEntry],
    activate_slot: Option<&str>,
) -> bool {
    existing.activate_slot.as_deref() == activate_slot
        && restore_transaction_entries_match(existing, entries)
        && existing
            .entries
            .iter()
            .zip(entries)
            .all(|(left, right)| left.device_path == right.device_path)
}

fn restore_transaction_entries_match(
    existing: &RestoreTransaction,
    entries: &[RestoreTransactionEntry],
) -> bool {
    existing.entries.len() == entries.len()
        && existing.entries.iter().zip(entries).all(|(left, right)| {
            left.name == right.name
                && left.label == right.label
                && left.image_path == right.image_path
                && left.expected_sha256 == right.expected_sha256
                && left.expected_size == right.expected_size
        })
}

fn partition_target_key(path: &str) -> Option<String> {
    let file_name = Path::new(path).file_name()?.to_str()?;
    let key = file_name
        .strip_suffix("_a")
        .or_else(|| file_name.strip_suffix("_b"))
        .unwrap_or(file_name);
    (!key.is_empty()).then(|| key.to_owned())
}

fn restore_target_matches_entry(entry: &RestoreTransactionEntry) -> bool {
    let configured_custom_path = read_config()
        .ok()
        .and_then(|config| config.custom_partitions.get(&entry.name).cloned());
    if let Some(custom_path) = configured_custom_path {
        let Some(entry_path) = Path::new(&entry.device_path).canonicalize().ok() else {
            return false;
        };
        let Some(config_path) = Path::new(&custom_path).canonicalize().ok() else {
            return false;
        };
        return entry_path == config_path;
    }

    let path = Path::new(&entry.device_path);
    let has_by_name_component = path
        .components()
        .any(|component| component.as_os_str() == "by-name");
    let direct_block_child = path.parent() == Some(Path::new("/dev/block"));
    if has_by_name_component {
        return partition_target_key(&entry.device_path).as_deref() == Some(entry.name.as_str());
    }
    if direct_block_child
        && partition_target_key(&entry.device_path).as_deref() == Some(entry.name.as_str())
    {
        return true;
    }

    // Raw platform-specific block paths do not contain the partition label in
    // their basename. Without an explicit custom mapping they are ambiguous
    // and must not be used for a resumed write.
    false
}

fn restore_transaction_targets_match(
    existing: &RestoreTransaction,
    entries: &[RestoreTransactionEntry],
) -> bool {
    existing.entries.len() == entries.len()
        && existing.entries.iter().zip(entries).all(|(left, right)| {
            left.device_path.starts_with("/dev/block/")
                && right.device_path.starts_with("/dev/block/")
                && partition_target_key(&left.device_path)
                    == partition_target_key(&right.device_path)
        })
}

fn select_resumable_restore_transaction(
    existing: RestoreTransaction,
    entries: &[RestoreTransactionEntry],
    activate_slot: Option<&str>,
) -> Result<Option<RestoreTransaction>> {
    if matches!(existing.phase.as_str(), "completed" | "idle") {
        return Ok(None);
    }
    if restore_transactions_match(&existing, entries, activate_slot) {
        return Ok(Some(existing));
    }
    // A/B devices can switch the active slot between two rescue checks. The
    // recalculated plan then contains the same partition targets with a
    // different *_a/*_b path and/or activation state. Reuse the persisted
    // transaction in that case; its original device paths are retained so a
    // resumed write cannot silently move to a different partition.
    if restore_transaction_entries_match(&existing, entries)
        && restore_transaction_targets_match(&existing, entries)
    {
        return Ok(Some(existing));
    }
    Err(coded_error(
        "rescue.restore_transaction_conflict",
        format!(
            "unfinished restore transaction {} does not match the current restore plan",
            existing.id
        ),
    ))
}

fn restore_transaction_json(transaction: &RestoreTransaction) -> Value {
    json!({
        "schemaVersion": 2,
        "id": transaction.id,
        "reason": transaction.reason,
        "automatic": transaction.automatic,
        "description": transaction.description,
        "activateSlot": transaction.activate_slot,
        "phase": transaction.phase,
        "errorCode": transaction.error_code,
        "errorMessage": transaction.error_message,
        "startedAt": transaction.started_at,
        "updatedAt": transaction.updated_at,
        "entries": transaction.entries.iter().map(|entry| json!({
            "name": entry.name,
            "label": entry.label,
            "imagePath": entry.image_path,
            "devicePath": entry.device_path,
            "expectedSha256": entry.expected_sha256,
            "expectedSize": entry.expected_size,
            "status": entry.status,
        })).collect::<Vec<_>>(),
    })
}

fn write_restore_transaction(transaction: &RestoreTransaction) -> Result<()> {
    atomic_write(
        Path::new(RESTORE_TRANSACTION_PATH),
        restore_transaction_json(transaction).to_string().as_bytes(),
    )
    .context("failed to persist rescue restore transaction")
}

fn read_restore_transaction() -> Result<Option<RestoreTransaction>> {
    read_restore_transaction_from(Path::new(RESTORE_TRANSACTION_PATH))
}

fn read_restore_transaction_from(path: &Path) -> Result<Option<RestoreTransaction>> {
    if !path.exists() {
        return Ok(None);
    }
    let content = fs::read_to_string(path).with_context(|| {
        format!(
            "failed to read rescue restore transaction {}",
            path.display()
        )
    })?;
    parse_restore_transaction(&content).map(Some)
}

fn parse_restore_transaction(content: &str) -> Result<RestoreTransaction> {
    let value: Value =
        serde_json::from_str(content).context("invalid rescue restore transaction JSON")?;
    if let Some(version) = value.get("schemaVersion") {
        let version = version
            .as_u64()
            .context("restore transaction schemaVersion is not an integer")?;
        if !matches!(version, 1 | 2) {
            bail!("unsupported rescue restore transaction schemaVersion: {version}");
        }
    }
    let required_string = |key: &str| -> Result<String> {
        value
            .get(key)
            .and_then(Value::as_str)
            .filter(|text| !text.is_empty())
            .map(ToOwned::to_owned)
            .with_context(|| format!("restore transaction is missing {key}"))
    };
    let entries = value
        .get("entries")
        .and_then(Value::as_array)
        .context("restore transaction is missing entries")?
        .iter()
        .map(|entry| {
            let string = |key: &str| -> Result<String> {
                entry
                    .get(key)
                    .and_then(Value::as_str)
                    .filter(|text| !text.is_empty())
                    .map(ToOwned::to_owned)
                    .with_context(|| format!("restore transaction entry is missing {key}"))
            };
            Ok(RestoreTransactionEntry {
                name: string("name")?,
                label: string("label")?,
                image_path: string("imagePath")?,
                device_path: string("devicePath")?,
                expected_sha256: string("expectedSha256")?,
                expected_size: entry
                    .get("expectedSize")
                    .and_then(Value::as_u64)
                    .context("restore transaction entry is missing expectedSize")?,
                status: string("status")?,
            })
        })
        .collect::<Result<Vec<_>>>()?;
    Ok(RestoreTransaction {
        id: required_string("id")?,
        reason: required_string("reason")?,
        automatic: value
            .get("automatic")
            .and_then(Value::as_bool)
            .unwrap_or(false),
        description: required_string("description")?,
        activate_slot: value
            .get("activateSlot")
            .and_then(Value::as_str)
            .filter(|slot| !slot.is_empty())
            .map(ToOwned::to_owned),
        phase: required_string("phase")?,
        error_code: value
            .get("errorCode")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_owned(),
        error_message: value
            .get("errorMessage")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_owned(),
        started_at: required_string("startedAt")?,
        updated_at: required_string("updatedAt")?,
        entries,
    })
}

fn backup_partition(spec: &PartitionSpec, device: &str) -> Result<()> {
    append_log(format!(
        "backup {}: {} -> {}",
        spec.name, device, spec.image_path
    ));
    let backup_result = (|| -> Result<()> {
        run_dd(device, &spec.image_path)
            .with_context(|| format!("failed to backup {}", spec.name))?;
        validate_image_size_against_partition(spec, device)
    })();
    backup_result?;
    append_log(format!(
        "backup {} ok: size={}, sha256={}",
        spec.name,
        fs::metadata(&spec.image_path)?.len(),
        sha256_of(&spec.image_path)
    ));
    Ok(())
}

fn run_dd(input: &str, output: &str) -> Result<()> {
    let mut input_file = OpenOptions::new()
        .read(true)
        .open(input)
        .with_context(|| format!("open input {input}"))?;

    let output_is_block = output.starts_with("/dev/block/");
    if output_is_block {
        let mut output_file = OpenOptions::new()
            .write(true)
            .open(output)
            .with_context(|| format!("open output {output}"))?;
        set_block_writable(&output_file, output)?;
        copy_with_timeout(&mut input_file, &mut output_file)
            .with_context(|| format!("copy {input} to {output}"))?;
        output_file
            .sync_all()
            .with_context(|| format!("sync output {output}"))?;
    } else {
        let output_path = Path::new(output);
        let parent = output_path
            .parent()
            .with_context(|| format!("{output} has no parent directory"))?;
        utils::ensure_dir_exists(parent)?;
        let mut temporary = tempfile::NamedTempFile::new_in(parent)
            .with_context(|| format!("create temporary backup in {}", parent.display()))?;
        copy_with_timeout(&mut input_file, &mut temporary)
            .with_context(|| format!("copy {input} to temporary backup for {output}"))?;
        temporary
            .as_file()
            .sync_all()
            .with_context(|| format!("sync temporary backup for {output}"))?;
        temporary
            .persist(output_path)
            .map_err(|error| error.error)
            .with_context(|| format!("atomically replace {output}"))?;
        sync_parent_directory(parent);
    }
    let _ = Command::new("sync").status();
    Ok(())
}

fn copy_with_timeout<R: Read, W: Write>(input: &mut R, output: &mut W) -> Result<u64> {
    let started = Instant::now();
    copy_with_clock(
        input,
        output,
        Duration::from_secs(COPY_IDLE_TIMEOUT_SECONDS),
        Duration::from_secs(COPY_TOTAL_TIMEOUT_SECONDS),
        || started.elapsed(),
    )
}

fn copy_with_clock<R, W, F>(
    input: &mut R,
    output: &mut W,
    idle_timeout: Duration,
    total_timeout: Duration,
    mut elapsed: F,
) -> Result<u64>
where
    R: Read,
    W: Write,
    F: FnMut() -> Duration,
{
    let mut buffer = vec![0_u8; COPY_BUFFER_BYTES];
    let mut copied = 0_u64;
    let mut last_progress = Duration::ZERO;
    loop {
        ensure_copy_deadline(elapsed(), last_progress, idle_timeout, total_timeout)?;
        let count = input.read(&mut buffer).context("read copy source")?;
        if count == 0 {
            return Ok(copied);
        }
        ensure_copy_deadline(elapsed(), last_progress, idle_timeout, total_timeout)?;
        output
            .write_all(&buffer[..count])
            .context("write copy destination")?;
        let now = elapsed();
        ensure_copy_deadline(now, last_progress, idle_timeout, total_timeout)?;
        copied = copied.saturating_add(count as u64);
        last_progress = now;
    }
}

fn ensure_copy_deadline(
    elapsed: Duration,
    last_progress: Duration,
    idle_timeout: Duration,
    total_timeout: Duration,
) -> Result<()> {
    if elapsed > total_timeout {
        return Err(coded_error(
            "rescue.io_timeout",
            format!(
                "rescue image copy exceeded the {} second total timeout",
                total_timeout.as_secs()
            ),
        ));
    }
    if elapsed.saturating_sub(last_progress) > idle_timeout {
        return Err(coded_error(
            "rescue.io_timeout",
            format!(
                "rescue image copy made no progress for more than {} seconds",
                idle_timeout.as_secs()
            ),
        ));
    }
    Ok(())
}

#[cfg(target_os = "android")]
fn set_block_writable(file: &fs::File, path: &str) -> Result<()> {
    if !path.starts_with("/dev/block/") {
        return Ok(());
    }
    unsafe {
        const BLKROSET: i32 = libc::_IO(0x12, 93);
        let mut val: libc::c_int = 0;
        if libc::ioctl(file.as_raw_fd(), BLKROSET, &raw mut val) != 0 {
            bail!("Failed to set rw for {path}: {}", *libc::__errno());
        }
    }
    Ok(())
}

#[cfg(not(target_os = "android"))]
fn set_block_writable(_file: &fs::File, _path: &str) -> Result<()> {
    Ok(())
}

fn request_reboot() -> Result<()> {
    let commands: [(&str, &[&str]); 4] = [
        ("/system/bin/reboot", &[]),
        ("reboot", &[]),
        ("svc", &["power", "reboot"]),
        ("setprop", &["sys.powerctl", "reboot"]),
    ];

    for (program, args) in commands {
        match Command::new(program).args(args).status() {
            Ok(status) if status.success() => {
                append_log(format!("requested reboot via {program}"));
                return Ok(());
            }
            Ok(status) => {
                append_log(format!("reboot command {program} exited with {status}"));
            }
            Err(err) => {
                append_log(format!("reboot command {program} failed: {err:#}"));
            }
        }
    }
    bail!("all reboot commands failed")
}

fn set_active_slot(slot: &str) -> Result<()> {
    let slot_number = bootctl_slot_number(slot)?;
    let mut errors = Vec::new();
    prepare_bootctl_for_slot_switch();
    for program in bootctl_commands() {
        match Command::new(program)
            .arg("set-active-boot-slot")
            .arg(slot_number)
            .status()
        {
            Ok(status) if status.success() => {
                append_log(format!("set active slot to {slot} via {program}"));
                return Ok(());
            }
            Ok(status) => {
                errors.push(format!("{program} exited with {status}"));
            }
            Err(err) => {
                errors.push(format!("{program} failed: {err:#}"));
            }
        }
    }
    bail!("failed to set active slot to {slot}: {}", errors.join("; "))
}

fn validate_active_slot_switch(slot: &str) -> Result<()> {
    let _ = bootctl_slot_number(slot)?;
    let mut errors = Vec::new();
    prepare_bootctl_for_slot_switch();
    for program in bootctl_commands() {
        match Command::new(program).arg("hal-info").status() {
            Ok(status) if status.success() => {
                append_log(format!(
                    "validated active slot switch support via {program}"
                ));
                return Ok(());
            }
            Ok(status) => {
                errors.push(format!("{program} hal-info exited with {status}"));
            }
            Err(err) => {
                errors.push(format!("{program} hal-info failed: {err:#}"));
            }
        }
    }
    bail!(
        "cannot switch active slot to {slot}; bootctl is unavailable: {}",
        errors.join("; ")
    )
}

fn prepare_bootctl_for_slot_switch() {
    if let Err(err) = crate::assets::ensure_binaries(true) {
        append_log(format!(
            "failed to extract bootctl before slot switch: {err:#}"
        ));
    }
}

const fn bootctl_commands() -> [&'static str; 3] {
    [
        crate::assets::BOOTCTL_PATH,
        "/system/bin/bootctl",
        "bootctl",
    ]
}

fn bootctl_slot_number(slot: &str) -> Result<&'static str> {
    match slot.trim() {
        "_a" | "a" | "0" => Ok("0"),
        "_b" | "b" | "1" => Ok("1"),
        value => bail!("unsupported slot suffix for rescue restore: {value}"),
    }
}

fn mark_skip_modules_once() {
    if let Err(err) = utils::ensure_dir_exists(RESCUE_DIR) {
        append_log(format!(
            "failed to create rescue dir for module skip: {err:#}"
        ));
        return;
    }

    let primary = atomic_write(Path::new(SKIP_MODULES_ONCE_PATH), b"1");
    let cache_compat = atomic_write(Path::new(CACHE_SKIP_MODULES_ONCE_PATH), b"1");
    if primary.is_ok() || cache_compat.is_ok() {
        append_log("temporary module skip will be applied on next boot");
    } else {
        if let Err(err) = primary {
            append_log(format!("failed to mark temporary module skip: {err:#}"));
        }
        if let Err(err) = cache_compat {
            append_log(format!(
                "failed to mark cache temporary module skip: {err:#}"
            ));
        }
    }
}

fn skip_modules_once_exists() -> bool {
    Path::new(SKIP_MODULES_ONCE_PATH).exists() || Path::new(CACHE_SKIP_MODULES_ONCE_PATH).exists()
}

fn clear_runtime_markers() -> Result<()> {
    for path in [
        PENDING_BOOT_PATH,
        RESTORE_PENDING_BOOT_PATH,
        RESTORE_LOCK_PATH,
        SKIP_MODULES_ONCE_PATH,
        CACHE_SKIP_MODULES_ONCE_PATH,
        SKIP_MODULES_THIS_BOOT_PATH,
        LEGACY_TMP_MODULE_DISABLE_PATH,
    ] {
        remove_file_if_exists(Path::new(path))?;
    }
    Ok(())
}

fn mark_legacy_tmp_module_disable() {
    if let Err(err) = atomic_write(Path::new(LEGACY_TMP_MODULE_DISABLE_PATH), b"1") {
        append_log(format!(
            "failed to mark legacy temporary module disable: {err:#}"
        ));
    } else {
        append_log("legacy temporary module disable marker will be applied on next boot");
    }
}

fn rescue_modules_for_failed_boot(reason: impl AsRef<str>) {
    append_log(format!("module rescue triggered: {}", reason.as_ref()));
    mark_skip_modules_once();
    disable_all_modules_for_rescue();
}

fn disable_all_modules_for_rescue() {
    let active_modules = active_module_ids();
    let disabled_modules = merge_module_ids(rescue_disabled_module_ids(), &active_modules);
    if let Err(error) = write_rescue_disabled_module_ids(&disabled_modules) {
        append_log(format!(
            "failed to record modules disabled by rescue: {error:#}"
        ));
    }
    match module::disable_all_modules() {
        Ok(()) => append_log(format!(
            "{} active modules were persistently disabled for rescue; re-enable them individually after confirming a stable boot",
            active_modules.len()
        )),
        Err(err) => append_log(format!("failed to disable all modules for rescue: {err:#}")),
    }
}

fn merge_module_ids(mut saved: Vec<String>, active: &[String]) -> Vec<String> {
    saved.extend(active.iter().cloned());
    saved.sort();
    saved.dedup();
    saved
}

fn active_module_ids() -> Vec<String> {
    let mut ids = Vec::new();
    if let Err(error) = module::foreach_module(module::ModuleType::Active, |path| {
        if let Some(id) = path.file_name().and_then(|name| name.to_str()) {
            ids.push(id.to_owned());
        }
        Ok(())
    }) {
        append_log(format!("failed to enumerate active modules: {error:#}"));
    }
    ids.sort();
    ids.dedup();
    ids
}

fn write_rescue_disabled_module_ids(ids: &[String]) -> Result<()> {
    let value = json!({
        "updatedAt": Local::now().to_rfc3339(),
        "moduleIds": ids,
    });
    atomic_write(
        Path::new(RESCUE_DISABLED_MODULES_PATH),
        value.to_string().as_bytes(),
    )
    .context("failed to persist rescue-disabled modules")
}

fn rescue_disabled_module_ids() -> Vec<String> {
    read_json_file(RESCUE_DISABLED_MODULES_PATH)
        .and_then(|value| value.get("moduleIds").and_then(Value::as_array).cloned())
        .unwrap_or_default()
        .iter()
        .filter_map(Value::as_str)
        .filter(|id| module::validate_module_id(id).is_ok())
        .map(ToOwned::to_owned)
        .collect()
}

fn read_rescue_disabled_modules() -> Vec<Value> {
    rescue_disabled_module_ids()
        .into_iter()
        .map(|id| {
            let path = Path::new(defs::MODULE_DIR).join(&id);
            let properties = module::read_module_prop(&path).unwrap_or_default();
            json!({
                "id": id,
                "name": properties.get("name").cloned().unwrap_or_default(),
                "version": properties.get("version").cloned().unwrap_or_default(),
                "installed": path.is_dir(),
                "disabled": path.join(defs::DISABLE_FILE_NAME).exists(),
            })
        })
        .collect()
}

pub fn enable_rescue_module(id: &str) -> Result<()> {
    let mut ids = rescue_disabled_module_ids();
    if !ids.iter().any(|saved| saved == id) {
        return Err(coded_error(
            "rescue.module_not_recorded",
            format!("module {id} was not disabled by rescue protection"),
        ));
    }
    module::enable_module(id).map_err(structured_error)?;
    ids.retain(|saved| saved != id);
    write_rescue_disabled_module_ids(&ids)?;
    append_log(format!("module {id} re-enabled after rescue"));
    Ok(())
}

fn mark_legacy_fix_done_lock() {
    if let Err(err) = atomic_write(Path::new(LEGACY_FIX_DONE_LOCK_PATH), b"1") {
        append_log(format!("failed to mark legacy rescue lock: {err:#}"));
    }
}

fn cleanup_legacy_rescue_flags() {
    for path in [LEGACY_LOOP_FLAG_PATH, LEGACY_PANIC_FLAG_PATH] {
        let _ = fs::remove_file(path);
    }
}

fn find_partition(spec: &PartitionSpec) -> Result<Option<String>> {
    if let Some(path) = spec.custom_path.as_deref() {
        if Path::new(path).exists() {
            let resolved = validate_partition_device_path(&spec.name, Path::new(path))?;
            return Ok(Some(resolved.display().to_string()));
        }
        bail!(
            "custom partition path for {} does not exist: {path}",
            spec.name
        );
    }
    let Some(path) = boot_patch::find_partition_path(&spec.name, spec.ota) else {
        return Ok(None);
    };
    // Automatic candidates are also untrusted input: a vendor can expose a
    // stale regular file or a symlink outside the block-device tree. Validate
    // the resolved target before any backup or restore operation uses it.
    validate_partition_device_path(&spec.name, &path)?;
    Ok(Some(path.display().to_string()))
}

fn validate_partition_device_path(name: &str, path: &Path) -> Result<PathBuf> {
    let resolved = path
        .canonicalize()
        .with_context(|| format!("failed to resolve {name} partition path {}", path.display()))?;
    if !resolved.starts_with("/dev/block") {
        bail!(
            "{} partition path resolves outside /dev/block: {}",
            name,
            resolved.display()
        );
    }
    #[cfg(target_os = "android")]
    {
        use std::os::unix::fs::FileTypeExt;
        if !fs::metadata(&resolved)?.file_type().is_block_device() {
            bail!(
                "{} partition path is not a block device: {}",
                name,
                resolved.display()
            );
        }
    }
    Ok(resolved)
}

fn image_status(spec: &PartitionSpec, manifest: &Value, deep: bool) -> serde_json::Value {
    let device = find_partition(spec).ok().flatten().unwrap_or_default();
    let size = fs::metadata(&spec.image_path).map_or(0, |metadata| metadata.len());
    let exists = Path::new(&spec.image_path).is_file() && size > 0;
    let partition_size = partition_size(&device);
    let manifest_sha256 = manifest_sha256_from(manifest, &spec.label).unwrap_or_default();
    let actual_sha256 = if deep && exists {
        cached_sha256(&spec.image_path, false)
    } else {
        String::new()
    };
    let sha256 = if actual_sha256.is_empty() {
        manifest_sha256.clone()
    } else {
        actual_sha256.clone()
    };
    let size_ok = if deep {
        exists && (partition_size == 0 || size == partition_size)
    } else {
        partition_size == 0 || size == 0 || size == partition_size
    };
    let sha256_ok = if deep {
        deep_image_verification_ok(
            exists,
            size,
            partition_size,
            &manifest_sha256,
            &actual_sha256,
        )
    } else {
        // A shallow status read must not claim that a digest was freshly
        // verified. The UI uses the explicit verification state for that.
        true
    };
    let verification_state = if !exists {
        "missing"
    } else if !size_ok || (deep && !sha256_ok) {
        "failed"
    } else if deep {
        "verified"
    } else if is_sha256_digest(&manifest_sha256) {
        "cached"
    } else {
        "unknown"
    };
    json!({
        "name": spec.name,
        "label": spec.label,
        "partition": device,
        "image": spec.image_path,
        "required": spec.required,
        "custom": spec.custom_path.is_some(),
        "otherSlot": spec.ota,
        "restore": spec.restore,
        "dangerous": is_dangerous_partition(&spec.name),
        "exists": exists,
        "size": size,
        "partitionSize": partition_size,
        "sha256": sha256,
        "sha256Ok": sha256_ok,
        "verificationState": verification_state,
        "sizeOk": size_ok,
    })
}

fn deep_image_verification_ok(
    exists: bool,
    size: u64,
    partition_size: u64,
    manifest_sha256: &str,
    actual_sha256: &str,
) -> bool {
    exists
        && size > 0
        && (partition_size == 0 || size == partition_size)
        && is_sha256_digest(manifest_sha256)
        && is_sha256_digest(actual_sha256)
        && manifest_sha256.eq_ignore_ascii_case(actual_sha256)
}

fn write_manifest(specs: &[PartitionSpec]) -> Result<()> {
    let images = specs
        .iter()
        .map(|spec| -> Result<Value> {
            let device = find_partition(spec).ok().flatten().unwrap_or_default();
            let size = fs::metadata(&spec.image_path).map_or(0, |metadata| metadata.len());
            let partition_size = partition_size(&device);
            let sha256 = if size > 0 {
                cached_sha256(&spec.image_path, true)
            } else {
                String::new()
            };
            if size > 0 && sha256.is_empty() {
                return Err(coded_error(
                    "rescue.checksum_unavailable",
                    format!("failed to calculate {} backup SHA256", spec.label),
                ));
            }
            let exists = size > 0;
            let size_ok = partition_size == 0 || !exists || size == partition_size;
            let sha256_ok = !exists || is_sha256_digest(&sha256);
            let verification_state = if !exists {
                "missing"
            } else if !size_ok || !sha256_ok {
                "failed"
            } else {
                "cached"
            };
            Ok(json!({
                "name": spec.name,
                "label": spec.label,
                "partition": device,
                "image": spec.image_path,
                "required": spec.required,
                "custom": spec.custom_path.is_some(),
                "otherSlot": spec.ota,
                "restore": spec.restore,
                "dangerous": is_dangerous_partition(&spec.name),
                "exists": exists,
                "size": size,
                "partitionSize": partition_size,
                "sha256": sha256,
                "sha256Ok": sha256_ok,
                "verificationState": verification_state,
                "sizeOk": size_ok,
            }))
        })
        .collect::<Result<Vec<_>>>()?;
    let manifest = json!({
        "createdAt": Local::now().format("%Y-%m-%d %H:%M:%S").to_string(),
        "slot": current_slot(),
        "device": device_summary(),
        "config": config_json(&read_config()?),
        "images": images,
    });
    atomic_write(Path::new(MANIFEST_PATH), manifest.to_string().as_bytes())
        .context("failed to write rescue manifest")
}

fn read_manifest() -> Result<Value> {
    if !Path::new(MANIFEST_PATH).exists() {
        return Ok(json!({}));
    }
    let content = fs::read_to_string(MANIFEST_PATH).context("failed to read rescue manifest")?;
    serde_json::from_str(&content).context("invalid rescue manifest")
}

fn validate_manifest_context_for_restore() -> Result<Value> {
    let manifest = read_manifest()?;
    if manifest.as_object().is_none_or(serde_json::Map::is_empty) {
        bail!("rescue manifest is missing; please backup first");
    }

    let saved_fingerprint = manifest
        .get("device")
        .and_then(|device| device.get("fingerprint"))
        .and_then(Value::as_str)
        .unwrap_or_default();
    let now_fingerprint = utils::getprop("ro.build.fingerprint").unwrap_or_default();
    if !saved_fingerprint.is_empty() && saved_fingerprint != now_fingerprint {
        if !is_recovery_boot() {
            bail!("device fingerprint mismatch; please create a fresh backup");
        }

        let saved_device = manifest
            .get("device")
            .and_then(|device| device.get("device"))
            .and_then(Value::as_str)
            .unwrap_or_default();
        let now_device = utils::getprop("ro.product.device").unwrap_or_default();
        if saved_device.is_empty() || now_device.is_empty() || saved_device != now_device {
            bail!("device identity mismatch in recovery; refusing rescue restore");
        }
        append_log("recovery fingerprint differs; device codename matched backup manifest");
    }

    let saved_config = manifest.get("config").cloned().unwrap_or_else(|| json!({}));
    let saved_config = parse_config(&saved_config.to_string())
        .map(|config| config_json(&config))
        .unwrap_or(saved_config);
    let current_config = config_json(&read_config().unwrap_or_default());
    if saved_config != current_config {
        bail!("rescue config changed after backup; please create a fresh backup");
    }

    Ok(manifest)
}

fn manifest_slot(manifest: &Value) -> String {
    manifest
        .get("slot")
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_string()
}

fn manifest_sha256_from(manifest: &Value, name: &str) -> Option<String> {
    let images = manifest.get("images")?.as_array()?;
    images.iter().find_map(|image| {
        let image_name = image.get("label").or_else(|| image.get("name"))?.as_str()?;
        if image_name == name {
            image.get("sha256")?.as_str().map(ToOwned::to_owned)
        } else {
            None
        }
    })
}

fn cached_sha256(path: &str, refresh: bool) -> String {
    if !refresh {
        let cached = cached_sha256_readonly(path);
        if !cached.is_empty() {
            return cached;
        }
    }

    let Some(signature) = file_signature(Path::new(path)) else {
        return String::new();
    };
    let mut cache = read_json_file(HASH_CACHE_PATH).unwrap_or_else(|| json!({}));
    if !refresh
        && let Some(entry) = cache.get(path)
        && entry.get("size").and_then(Value::as_u64) == Some(signature.0)
        && entry.get("modifiedNanos").and_then(Value::as_u64) == Some(signature.1)
        && let Some(digest) = entry.get("sha256").and_then(Value::as_str)
        && !digest.is_empty()
    {
        return digest.to_owned();
    }

    let digest = sha256_of(path);
    if digest.is_empty() {
        return digest;
    }
    if !cache.is_object() {
        cache = json!({});
    }
    if let Some(entries) = cache.as_object_mut() {
        entries.insert(
            path.to_owned(),
            json!({
                "size": signature.0,
                "modifiedNanos": signature.1,
                "sha256": digest,
            }),
        );
        if let Err(error) = atomic_write(Path::new(HASH_CACHE_PATH), cache.to_string().as_bytes()) {
            append_log(format!("failed to persist SHA256 cache: {error:#}"));
        }
    }
    digest
}

// Status queries and the enable gate must not turn into an unbounded read of
// a boot image just because the cache was deleted or invalidated. A missing
// entry is deliberately reported as unknown; the explicit `rescue verify`
// command is the operation that is allowed to recalculate it.
fn cached_sha256_readonly(path: &str) -> String {
    let Some(signature) = file_signature(Path::new(path)) else {
        return String::new();
    };
    let Some(entry) = read_json_file(HASH_CACHE_PATH).and_then(|cache| cache.get(path).cloned())
    else {
        return String::new();
    };
    if entry.get("size").and_then(Value::as_u64) != Some(signature.0)
        || entry.get("modifiedNanos").and_then(Value::as_u64) != Some(signature.1)
    {
        return String::new();
    }
    entry
        .get("sha256")
        .and_then(Value::as_str)
        .filter(|digest| is_sha256_digest(digest))
        .unwrap_or_default()
        .to_owned()
}

fn file_signature(path: &Path) -> Option<(u64, u64)> {
    let metadata = fs::metadata(path).ok()?;
    let modified = metadata.modified().ok()?.duration_since(UNIX_EPOCH).ok()?;
    let modified_nanos = modified
        .as_secs()
        .saturating_mul(1_000_000_000)
        .saturating_add(u64::from(modified.subsec_nanos()));
    Some((metadata.len(), modified_nanos))
}

fn verified_file_json(path: &Path) -> Result<Value> {
    let path_text = path.display().to_string();
    let (size, modified_nanos) = file_signature(path).ok_or_else(|| {
        coded_error(
            "rescue.backup_invalid",
            format!("failed to read backup metadata for {path_text}"),
        )
    })?;
    if size == 0 {
        return Err(coded_error(
            "rescue.backup_invalid",
            format!("backup file is empty: {path_text}"),
        ));
    }
    let digest = cached_sha256(&path_text, true);
    if digest.is_empty() {
        return Err(coded_error(
            "rescue.checksum_unavailable",
            format!("failed to calculate backup SHA256 for {path_text}"),
        ));
    }
    Ok(json!({
        "path": path_text,
        "size": size,
        "modifiedNanos": modified_nanos,
        "sha256": digest,
    }))
}

fn read_json_file(path: &str) -> Option<Value> {
    let content = fs::read_to_string(path).ok()?;
    serde_json::from_str(&content).ok()
}

fn write_verification_marker(specs: &[PartitionSpec], manifest: &Value) -> Result<()> {
    let files = specs
        .iter()
        .filter(|spec| Path::new(&spec.image_path).is_file())
        .map(|spec| verified_file_json(Path::new(&spec.image_path)))
        .collect::<Result<Vec<_>>>()?;
    if files.is_empty() {
        return Err(coded_error(
            "rescue.backup_invalid",
            "no rescue backup was available for the verification marker",
        ));
    }
    let manifest_sha256 = cached_sha256(MANIFEST_PATH, true);
    if manifest_sha256.is_empty() {
        return Err(coded_error(
            "rescue.manifest_digest_missing",
            "failed to calculate rescue manifest SHA256",
        ));
    }
    let marker = json!({
        "schemaVersion": 2,
        "verifiedAt": Local::now().to_rfc3339(),
        "manifestSha256": manifest_sha256,
        "manifestCreatedAt": manifest.get("createdAt").and_then(Value::as_str).unwrap_or_default(),
        "files": files,
    });
    atomic_write(Path::new(VERIFIED_PATH), marker.to_string().as_bytes())
        .context("failed to persist rescue verification marker")
}

fn parse_verification_marker_files(marker: &Value) -> Result<BTreeMap<String, (u64, String)>> {
    let files = marker
        .get("files")
        .and_then(Value::as_array)
        .context("verification marker is missing files")?;
    if files.is_empty() {
        bail!("verification marker contains no files");
    }

    let mut parsed = BTreeMap::new();
    for (index, entry) in files.iter().enumerate() {
        let path = entry
            .get("path")
            .and_then(Value::as_str)
            .filter(|path| !path.is_empty())
            .with_context(|| format!("verification marker file {index} has no path"))?;
        let size = entry
            .get("size")
            .and_then(Value::as_u64)
            .filter(|size| *size > 0)
            .with_context(|| format!("verification marker file {index} has an invalid size"))?;
        let sha256 = entry
            .get("sha256")
            .and_then(Value::as_str)
            .filter(|digest| is_sha256_digest(digest))
            .with_context(|| format!("verification marker file {index} has an invalid SHA256"))?;
        if parsed
            .insert(path.to_owned(), (size, sha256.to_owned()))
            .is_some()
        {
            bail!("verification marker contains duplicate path: {path}");
        }
    }
    Ok(parsed)
}

fn verification_marker_is_current(specs: &[PartitionSpec], manifest: &Value) -> bool {
    let Some(marker) = read_json_file(VERIFIED_PATH) else {
        return false;
    };
    if marker.get("schemaVersion").and_then(Value::as_u64) != Some(2) {
        return false;
    }
    let manifest_sha256 = marker
        .get("manifestSha256")
        .and_then(Value::as_str)
        .unwrap_or_default();
    let cached_manifest_sha256 = cached_sha256_readonly(MANIFEST_PATH);
    if !is_sha256_digest(manifest_sha256)
        || !manifest_sha256.eq_ignore_ascii_case(&cached_manifest_sha256)
    {
        return false;
    }
    let Ok(expected) = parse_verification_marker_files(&marker) else {
        return false;
    };
    let current_files = specs
        .iter()
        .filter(|spec| Path::new(&spec.image_path).is_file())
        .collect::<Vec<_>>();
    let current_paths = current_files
        .iter()
        .map(|spec| spec.image_path.clone())
        .collect::<BTreeSet<_>>();
    let expected_paths = expected.keys().cloned().collect::<BTreeSet<_>>();
    if current_files.len() != current_paths.len() || current_paths != expected_paths {
        return false;
    }

    let manifest_created_at = manifest
        .get("createdAt")
        .and_then(Value::as_str)
        .filter(|value| !value.is_empty());
    let marker_created_at = marker
        .get("manifestCreatedAt")
        .and_then(Value::as_str)
        .filter(|value| !value.is_empty());
    if manifest_created_at != marker_created_at {
        return false;
    }

    current_files.iter().all(|spec| {
        let Some((size, _)) = file_signature(Path::new(&spec.image_path)) else {
            return false;
        };
        let Some((expected_size, expected_sha256)) = expected.get(&spec.image_path) else {
            return false;
        };
        size > 0
            && size == *expected_size
            && cached_sha256_readonly(&spec.image_path).eq_ignore_ascii_case(expected_sha256)
    })
}

fn write_environment_check(config: &RescueConfig) -> Result<()> {
    let marker = json!({
        "checkedAt": Local::now().to_rfc3339(),
        "config": config_json(config),
        "device": device_summary(),
    });
    atomic_write(
        Path::new(ENVIRONMENT_CHECK_PATH),
        marker.to_string().as_bytes(),
    )
    .context("failed to persist rescue environment check")
}

fn environment_check_is_current(config: &RescueConfig) -> bool {
    let Some(marker) = read_json_file(ENVIRONMENT_CHECK_PATH) else {
        return false;
    };
    marker.get("config") == Some(&config_json(config))
        && marker
            .get("device")
            .and_then(|device| device.get("device"))
            .and_then(Value::as_str)
            == device_summary().get("device").and_then(Value::as_str)
}

#[allow(clippy::fn_params_excessive_bools)]
const fn rescue_phase(
    status_ok: bool,
    enabled: bool,
    backup_ready: bool,
    verified: bool,
    config_changed: bool,
    restore_interrupted: bool,
) -> &'static str {
    if !status_ok {
        "unavailable"
    } else if restore_interrupted {
        "restore_error"
    } else if config_changed {
        "config_changed"
    } else if !backup_ready {
        "needs_backup"
    } else if !verified {
        "needs_verification"
    } else if enabled {
        "protected"
    } else {
        "ready_to_enable"
    }
}

fn read_config() -> Result<RescueConfig> {
    if !Path::new(CONFIG_PATH).exists() {
        return Ok(RescueConfig::default());
    }
    let content = fs::read_to_string(CONFIG_PATH).context("failed to read rescue config")?;
    parse_config(&content)
}

fn write_config(config: &RescueConfig) -> Result<()> {
    utils::ensure_dir_exists(RESCUE_DIR)?;
    atomic_write(
        Path::new(CONFIG_PATH),
        config_json(config).to_string().as_bytes(),
    )
    .context("failed to write rescue config")
}

fn parse_config(content: &str) -> Result<RescueConfig> {
    let value: Value = serde_json::from_str(content).context("invalid rescue config JSON")?;
    let mut custom_partitions = BTreeMap::new();
    if let Some(object) = value.get("customPartitions").and_then(Value::as_object) {
        for (name, path) in object {
            if !is_known_partition(name) {
                bail!("unsupported custom rescue partition: {name}");
            }
            let path = path
                .as_str()
                .and_then(sanitize_partition_path)
                .with_context(|| format!("invalid custom partition path for {name}"))?;
            custom_partitions.insert(name.clone(), path);
        }
    }
    Ok(RescueConfig {
        include_dtbo: value
            .get("includeDtbo")
            .and_then(Value::as_bool)
            .unwrap_or(false),
        include_vbmeta: value
            .get("includeVbmeta")
            .and_then(Value::as_bool)
            .unwrap_or(false),
        backup_other_slot: value
            .get("backupOtherSlot")
            .and_then(Value::as_bool)
            .unwrap_or(false),
        dangerous_auto_restore: if value
            .get("allowDangerousAutoRestore")
            .and_then(Value::as_bool)
            .unwrap_or(false)
        {
            DangerousAutoRestore::Allow
        } else {
            DangerousAutoRestore::Skip
        },
        custom_partitions,
    })
}

fn config_json(config: &RescueConfig) -> Value {
    json!({
        "includeDtbo": config.include_dtbo,
        "includeVbmeta": config.include_vbmeta,
        "backupOtherSlot": config.backup_other_slot,
        "allowDangerousAutoRestore": config.dangerous_auto_restore.is_allowed(),
        "customPartitions": config.custom_partitions,
    })
}

fn sanitize_partition_path(path: &str) -> Option<String> {
    let path = path.trim();
    let candidate = Path::new(path);
    if candidate.is_absolute()
        && candidate.starts_with("/dev/block")
        && candidate
            .components()
            .all(|component| matches!(component, Component::RootDir | Component::Normal(_)))
        && !path.contains('\0')
        && !path.contains('"')
        && !path.contains('\'')
    {
        Some(path.to_string())
    } else {
        None
    }
}

fn is_known_partition(name: &str) -> bool {
    matches!(
        name,
        "boot" | "vendor_boot" | "init_boot" | "dtbo" | "vbmeta"
    )
}

fn is_dangerous_partition(name: &str) -> bool {
    matches!(name, "dtbo" | "vbmeta")
}

fn normalize_partition_name(name: &str) -> Result<String> {
    let name = name.trim();
    match name {
        "boot" | "dtbo" | "vbmeta" => Ok(name.to_string()),
        "init_boot" | "initboot" | "intboot" => Ok("init_boot".to_string()),
        "vendor_boot" | "vendorboot" | "verboot" => Ok("vendor_boot".to_string()),
        _ => bail!("unsupported rescue partition: {name}"),
    }
}

fn ensure_safe_import_source(source: &Path) -> Result<PathBuf> {
    let path = source
        .canonicalize()
        .with_context(|| format!("failed to resolve {}", source.display()))?;
    if !path.is_file() {
        bail!("source image is not a regular file: {}", source.display());
    }
    let size = fs::metadata(&path)?.len();
    if size == 0 {
        bail!("source image is empty");
    }
    Ok(path)
}

fn preserve_file(path: &str) -> Result<()> {
    let source = Path::new(path);
    let backup = backup_path(source);
    if !source.exists() {
        remove_file_if_exists(&backup)?;
        return Ok(());
    }
    atomic_copy(source, &backup)
        .with_context(|| format!("failed to preserve previous backup {}", source.display()))?;
    Ok(())
}

fn restore_preserved_file(path: &str) -> Result<()> {
    let source = Path::new(path);
    let backup = backup_path(source);
    if backup.exists() {
        atomic_copy(&backup, source)
            .with_context(|| format!("failed to restore previous backup {}", source.display()))?;
    } else {
        remove_file_if_exists(source)?;
    }
    Ok(())
}

fn rescue_file_paths(specs: &[PartitionSpec]) -> Vec<String> {
    let mut paths = Vec::with_capacity(specs.len() + 1);
    paths.push(MANIFEST_PATH.to_string());
    paths.extend(specs.iter().map(|spec| spec.image_path.clone()));
    paths.sort();
    paths.dedup();
    paths
}

fn preserve_files(paths: &[String]) -> Result<()> {
    let mut preserved = Vec::with_capacity(paths.len());
    for path in paths {
        if let Err(err) = preserve_file(path) {
            cleanup_preserved_files(&preserved);
            return Err(err);
        }
        preserved.push(path.clone());
    }
    Ok(())
}

fn restore_preserved_files(paths: &[String]) -> Result<()> {
    let mut errors = Vec::new();
    for path in paths {
        if let Err(err) = restore_preserved_file(path) {
            errors.push(format!("{path}: {err:#}"));
        }
    }
    if errors.is_empty() {
        cleanup_preserved_files(paths);
        Ok(())
    } else {
        bail!("{}", errors.join("; "))
    }
}

fn cleanup_preserved_files(paths: &[String]) {
    for path in paths {
        let _ = fs::remove_file(backup_path(Path::new(path)));
    }
}

fn backup_path(path: &Path) -> PathBuf {
    let file_name = path
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or("backup");
    path.with_file_name(format!("{file_name}.bak"))
}

fn validate_import_source_against_partition(
    source: &Path,
    spec: &PartitionSpec,
    device: &str,
) -> Result<()> {
    let image_size = fs::metadata(source)
        .with_context(|| format!("failed to read import metadata for {}", spec.name))?
        .len();
    let device_size = partition_size(device);
    if device_size == 0 {
        bail!("failed to determine {} partition size", spec.name);
    }
    if image_size != device_size {
        bail!(
            "{} import size mismatch: image={}, partition={}",
            spec.name,
            image_size,
            device_size
        );
    }
    Ok(())
}

fn atomic_copy(source: &Path, destination: &Path) -> Result<u64> {
    let parent = destination
        .parent()
        .with_context(|| format!("{} has no parent", destination.display()))?;
    utils::ensure_dir_exists(parent)?;
    let mut input =
        fs::File::open(source).with_context(|| format!("failed to open {}", source.display()))?;
    let mut temporary = tempfile::NamedTempFile::new_in(parent)
        .with_context(|| format!("failed to create temporary file in {}", parent.display()))?;
    let copied = io::copy(&mut input, &mut temporary)
        .with_context(|| format!("failed to copy {}", source.display()))?;
    temporary.as_file().sync_all().with_context(|| {
        format!(
            "failed to sync temporary copy for {}",
            destination.display()
        )
    })?;
    temporary
        .persist(destination)
        .map_err(|err| err.error)
        .with_context(|| format!("failed to atomically replace {}", destination.display()))?;
    sync_parent_directory(parent);
    Ok(copied)
}

fn atomic_write(path: &Path, content: &[u8]) -> Result<()> {
    let parent = path
        .parent()
        .with_context(|| format!("{} has no parent", path.display()))?;
    utils::ensure_dir_exists(parent)?;
    let mut temporary = tempfile::NamedTempFile::new_in(parent)
        .with_context(|| format!("failed to create temporary file in {}", parent.display()))?;
    temporary
        .write_all(content)
        .with_context(|| format!("failed to write temporary file for {}", path.display()))?;
    temporary
        .as_file()
        .sync_all()
        .with_context(|| format!("failed to sync temporary file for {}", path.display()))?;
    temporary
        .persist(path)
        .map_err(|err| err.error)
        .with_context(|| format!("failed to atomically replace {}", path.display()))?;
    sync_parent_directory(parent);
    Ok(())
}

fn remove_file_if_exists(path: &Path) -> Result<()> {
    match fs::remove_file(path) {
        Ok(()) => Ok(()),
        Err(err) if err.kind() == io::ErrorKind::NotFound => Ok(()),
        Err(err) => Err(err).with_context(|| format!("failed to remove {}", path.display())),
    }
}

fn sync_parent_directory(parent: &Path) {
    if let Ok(directory) = fs::File::open(parent) {
        let _ = directory.sync_all();
    }
}

fn current_slot() -> String {
    boot_patch::get_slot_suffix(false).unwrap_or_default()
}

fn current_boot_id() -> Result<String> {
    if let Ok(value) = fs::read_to_string("/proc/sys/kernel/random/boot_id") {
        let value = value.trim().to_owned();
        if !value.is_empty() {
            return Ok(value);
        }
    }

    // Some vendor kernels restrict boot_id even for a root daemon. Linux's
    // boot-time field is a stable per-boot token and keeps restore validation
    // bounded instead of leaving a restore marker pending forever.
    if let Ok(stat) = fs::read_to_string("/proc/stat")
        && let Some(token) = boot_time_token(&stat)
    {
        return Ok(token);
    }

    bail!("failed to read current boot identity")
}

fn boot_time_token(proc_stat: &str) -> Option<String> {
    proc_stat.lines().find_map(|line| {
        let mut fields = line.split_whitespace();
        if fields.next() != Some("btime") {
            return None;
        }
        let value = fields.next()?.parse::<u64>().ok()?;
        (value > 0).then(|| format!("proc-stat-btime:{value}"))
    })
}

fn boot_mode() -> String {
    boot_mode_values().into_iter().next().unwrap_or_default()
}

fn boot_mode_values() -> Vec<String> {
    [
        "ro.bootmode",
        "ro.boot.bootmode",
        "vendor.boot.bootmode",
        "ro.boot.mode",
        "ro.boot.recovery",
    ]
    .into_iter()
    .filter_map(utils::getprop)
    .map(|value| value.trim().to_ascii_lowercase())
    .filter(|value| !value.is_empty() && value != "unknown")
    .collect()
}

fn is_recovery_boot() -> bool {
    boot_mode_values()
        .iter()
        .any(|mode| is_recovery_mode_value(mode))
}

fn is_recovery_mode_value(mode: &str) -> bool {
    matches!(
        mode.trim().to_ascii_lowercase().as_str(),
        "1" | "true" | "yes" | "recovery" | "rec"
    )
}

fn device_summary() -> Value {
    json!({
        "brand": utils::getprop("ro.product.brand").unwrap_or_default(),
        "model": utils::getprop("ro.product.model").unwrap_or_default(),
        "device": utils::getprop("ro.product.device").unwrap_or_default(),
        "fingerprint": utils::getprop("ro.build.fingerprint").unwrap_or_default(),
        "kernel": std::fs::read_to_string("/proc/sys/kernel/osrelease").unwrap_or_default().trim(),
    })
}

fn is_enabled() -> bool {
    Path::new(ENABLED_PATH).exists()
}

fn read_boot_count() -> u32 {
    fs::read_to_string(BOOT_COUNT_PATH)
        .ok()
        .and_then(|value| value.trim().parse().ok())
        .unwrap_or(0)
}

fn read_auto_restore_attempts() -> u32 {
    fs::read_to_string(AUTO_RESTORE_ATTEMPTS_PATH)
        .ok()
        .and_then(|value| value.trim().parse().ok())
        .unwrap_or(0)
}

fn write_auto_restore_attempts(value: u32) -> Result<()> {
    atomic_write(
        Path::new(AUTO_RESTORE_ATTEMPTS_PATH),
        value.to_string().as_bytes(),
    )
    .context("failed to write auto restore attempts")
}

fn write_boot_count(value: u32) -> Result<()> {
    atomic_write(Path::new(BOOT_COUNT_PATH), value.to_string().as_bytes())
        .context("failed to write boot counter")
}

fn has_boot_failure_hint() -> bool {
    has_legacy_failure_hint() || has_boot_reason_failure_hint() || has_fresh_failure_artifact_hint()
}

fn has_legacy_failure_hint() -> bool {
    let loop_content = fs::read_to_string(LEGACY_LOOP_FLAG_PATH).ok();
    let panic_content = fs::read_to_string(LEGACY_PANIC_FLAG_PATH).ok();
    let loop_failure = loop_content
        .as_deref()
        .is_some_and(has_legacy_loop_failure_text);
    let panic_failure = panic_content.as_deref().is_some_and(has_failure_text);

    if loop_failure {
        append_log(format!(
            "legacy rescue failure hint found in {LEGACY_LOOP_FLAG_PATH}"
        ));
    } else if Path::new(LEGACY_LOOP_FLAG_PATH).exists() {
        // The old module creates this file during every normal boot. Its
        // existence alone is not evidence of a failed boot.
        append_log(format!(
            "ignored non-actionable legacy loop marker: {LEGACY_LOOP_FLAG_PATH}"
        ));
    }
    if panic_failure {
        append_log(format!(
            "legacy rescue failure hint found in {LEGACY_PANIC_FLAG_PATH}"
        ));
    }
    loop_failure || panic_failure
}

fn has_legacy_loop_failure_text(text: &str) -> bool {
    let text = text.to_ascii_lowercase();
    [
        "boot loop",
        "bootloop",
        "reboot loop",
        "infinite reboot",
        "kernel panic",
        "watchdog",
    ]
    .iter()
    .any(|token| text.contains(token))
        || has_failure_text(&text)
}

fn has_boot_reason_failure_hint() -> bool {
    let text = boot_reason_text();
    let found = has_boot_reason_failure_text(&text);
    if found {
        append_log(format!("boot failure hint found in boot reason: {text}"));
    }
    found
}

fn boot_reason_text() -> String {
    [
        "sys.boot.reason",
        "ro.boot.bootreason",
        "ro.boot.boot_reason",
        "ro.boot.hardware.reboot_reason",
    ]
    .into_iter()
    .filter_map(utils::getprop)
    .map(|value| value.trim().to_ascii_lowercase())
    .collect::<Vec<_>>()
    .join("\n")
}

fn has_fresh_failure_artifact_hint() -> bool {
    let current = failure_artifacts();
    let Some(baseline_value) = read_json_file(FAILURE_BASELINE_PATH) else {
        append_log(
            "failure evidence baseline is missing; record current artifacts without triggering rollback",
        );
        if let Err(error) = write_failure_baseline() {
            append_log(format!(
                "failed to initialize failure evidence baseline: {error:#}"
            ));
        }
        return false;
    };
    let baseline = baseline_value
        .get("artifacts")
        .and_then(Value::as_object)
        .cloned()
        .unwrap_or_default();

    if let Some(path) = fresh_failure_artifact_path(&baseline, &current) {
        append_log(format!("fresh boot failure evidence found in {path}"));
        return true;
    }
    false
}

fn fresh_failure_artifact_path(
    baseline: &serde_json::Map<String, Value>,
    current: &BTreeMap<String, Value>,
) -> Option<String> {
    for (path, artifact) in current {
        let digest = artifact
            .get("sha256")
            .and_then(Value::as_str)
            .unwrap_or_default();
        let has_failure = artifact
            .get("hasFailure")
            .and_then(Value::as_bool)
            .unwrap_or(false);
        let baseline_digest = baseline
            .get(path.as_str())
            .and_then(Value::as_str)
            .unwrap_or_default();
        if has_failure && !digest.is_empty() && digest != baseline_digest {
            return Some(path.clone());
        }
    }
    None
}

fn write_failure_baseline() -> Result<()> {
    let artifacts = failure_artifacts()
        .into_iter()
        .map(|(path, value)| {
            (
                path,
                Value::String(
                    value
                        .get("sha256")
                        .and_then(Value::as_str)
                        .unwrap_or_default()
                        .to_owned(),
                ),
            )
        })
        .collect::<serde_json::Map<_, _>>();
    let baseline = json!({
        "createdAt": Local::now().to_rfc3339(),
        "bootId": current_boot_id().unwrap_or_default(),
        "artifacts": artifacts,
    });
    atomic_write(
        Path::new(FAILURE_BASELINE_PATH),
        baseline.to_string().as_bytes(),
    )
    .context("failed to persist failure evidence baseline")
}

fn failure_artifacts() -> BTreeMap<String, Value> {
    let mut paths = vec![PathBuf::from("/proc/last_kmsg")];
    if let Ok(entries) = fs::read_dir("/sys/fs/pstore") {
        let mut pstore_paths = entries
            .flatten()
            .filter_map(|entry| {
                let path = entry.path();
                let name = path.file_name()?.to_str()?;
                (name.contains("ramoops") || name.contains("console") || name.contains("dmesg"))
                    .then_some(path)
            })
            .collect::<Vec<_>>();
        pstore_paths.sort();
        pstore_paths.truncate(FAILURE_ARTIFACT_MAX_FILES);
        paths.extend(pstore_paths);
    }

    paths
        .into_iter()
        .filter_map(|path| {
            failure_artifact_from_path(&path, FAILURE_ARTIFACT_SCAN_BYTES)
                .map(|artifact| (path.display().to_string(), artifact))
        })
        .collect()
}

fn failure_artifact_from_path(path: &Path, max_scan_bytes: u64) -> Option<Value> {
    let digest = sha256::try_digest(path).ok()?;
    let mut sample = Vec::new();
    fs::File::open(path)
        .ok()?
        .take(max_scan_bytes.saturating_add(1))
        .read_to_end(&mut sample)
        .ok()?;
    let truncated = sample.len() as u64 > max_scan_bytes;
    sample.truncate(max_scan_bytes.min(usize::MAX as u64) as usize);
    let text = String::from_utf8_lossy(&sample);
    Some(json!({
        "sha256": digest,
        "hasFailure": has_failure_text(&text),
        "scannedBytes": sample.len(),
        "scanTruncated": truncated,
    }))
}

fn has_failure_text(text: &str) -> bool {
    text.lines().any(|line| {
        let line = line.to_ascii_lowercase();
        let has_failure_word = line.contains("fail")
            || line.contains("error")
            || line.contains("invalid")
            || line.contains("mismatch")
            || line.contains("corrupt");
        line.contains("kernel panic")
            || line.contains("panic - not syncing")
            || line.contains("watchdog bite")
            || line.contains("watchdog bark")
            || line.contains("watchdog timeout")
            || line.contains("watchdog reset")
            || line.contains("ramdump")
            || line.contains("boot verification")
            || line.contains("dtb load fail")
            || line.contains("verification failed")
            || line.contains("invalid magic")
            || line.contains("bad magic")
            || has_failure_word
                && [
                    "dm-verity",
                    "dtb",
                    "dtbo",
                    "avb",
                    "vbmeta",
                    "init_boot",
                    "vendor_boot",
                    "gki",
                    "kmi",
                ]
                .iter()
                .any(|token| line.contains(token))
    })
}

fn has_boot_reason_failure_text(text: &str) -> bool {
    let text = text.to_ascii_lowercase();
    [
        "kernel_panic",
        "kernel panic",
        "watchdog",
        "ramdump",
        "dm-verity",
        "boot verification",
        "hard_reset",
    ]
    .iter()
    .any(|token| text.contains(token))
}

fn sha256_of(path: &str) -> String {
    sha256::try_digest(Path::new(path)).unwrap_or_default()
}

fn partition_size(path: &str) -> u64 {
    if path.is_empty() {
        return 0;
    }

    // Android vendor ramdisks do not expose one consistent PATH. Try the
    // standalone utility and the toolbox fallbacks before treating the size
    // as unknown.
    for (program, args) in [
        ("blockdev", vec!["--getsize64", path]),
        ("/system/bin/blockdev", vec!["--getsize64", path]),
        ("/system/bin/toybox", vec!["blockdev", "--getsize64", path]),
        ("/system/bin/toolbox", vec!["blockdev", "--getsize64", path]),
    ] {
        if let Ok(output) = Command::new(program).args(&args).output()
            && output.status.success()
            && let Ok(text) = String::from_utf8(output.stdout)
            && let Ok(size) = text.trim().parse::<u64>()
            && size > 0
        {
            return size;
        }
    }

    // `/sys/class/block/*/size` is expressed in 512-byte sectors and remains
    // available on devices where the user-space blockdev binary is hidden.
    let resolved = Path::new(path)
        .canonicalize()
        .unwrap_or_else(|_| PathBuf::from(path));
    if let Some(name) = resolved.file_name().and_then(|value| value.to_str())
        && let Ok(sectors) =
            fs::read_to_string(Path::new("/sys/class/block").join(name).join("size"))
                .ok()
                .and_then(|value| value.trim().parse::<u64>().ok())
                .ok_or(())
        && sectors > 0
    {
        return sectors.saturating_mul(512);
    }

    fs::metadata(path).map_or(0, |metadata| metadata.len())
}

fn append_log(message: impl AsRef<str>) {
    if let Err(err) = append_log_inner(message.as_ref()) {
        log::warn!("rescue: failed to append log: {err}");
    }
}

fn append_log_inner(message: &str) -> Result<()> {
    utils::ensure_dir_exists(RESCUE_DIR)?;
    rotate_log_if_needed()?;
    let mut file = OpenOptions::new()
        .create(true)
        .append(true)
        .open(LOG_PATH)
        .with_context(|| format!("failed to open {LOG_PATH}"))?;
    let timestamp = Local::now().format("%Y-%m-%d %H:%M:%S");
    writeln!(file, "[{timestamp}] {message}").context("failed to write rescue log")
}

fn rotate_log_if_needed() -> Result<()> {
    let Ok(metadata) = fs::metadata(LOG_PATH) else {
        return Ok(());
    };
    if metadata.len() < LOG_MAX_BYTES {
        return Ok(());
    }

    remove_file_if_exists(Path::new(&format!("{LOG_PATH}.{LOG_ROTATION_COUNT}")))?;
    for index in (1..LOG_ROTATION_COUNT).rev() {
        let source = format!("{LOG_PATH}.{index}");
        let destination = format!("{LOG_PATH}.{}", index + 1);
        if Path::new(&source).exists() {
            fs::rename(&source, &destination).with_context(|| {
                format!("failed to rotate rescue log {source} to {destination}")
            })?;
        }
    }
    fs::rename(LOG_PATH, format!("{LOG_PATH}.1")).context("failed to rotate rescue log")
}

fn tail_file(path: &str, max_lines: usize) -> Result<String> {
    if !Path::new(path).exists() {
        return Ok(String::new());
    }

    let content = fs::read_to_string(path).with_context(|| format!("failed to read {path}"))?;
    let lines = content.lines().collect::<Vec<_>>();
    let start = lines.len().saturating_sub(max_lines);
    Ok(lines[start..].join("\n"))
}

#[cfg(test)]
mod tests {
    use super::{
        BootRescueAction, BootRescueSignals, COPY_TOTAL_TIMEOUT_SECONDS, PartitionSpec,
        RestorePendingBoot, RestorePendingBootState, RestoreTransaction, RestoreTransactionEntry,
        backup_path, boot_time_token, copy_with_clock, deep_image_verification_ok, error_code,
        failure_artifact_from_path, fresh_failure_artifact_path, has_legacy_loop_failure_text,
        invalidate_protection_markers_at, is_recovery_mode_value, marker_armed_boot_id,
        marker_armed_in_boot, marker_armed_in_current_boot_id, merge_module_ids,
        normalize_partition_name, parse_config, parse_restore_transaction,
        parse_verification_marker_files, partition_target_key, post_fs_data_rescue_action,
        preserve_file, read_restore_transaction_from, recovery_boot_rescue_action,
        restore_marker_blocks_boot_commit, restore_pending_boot_state_from_marker,
        restore_preserved_file, restore_target_matches_entry, restore_transaction_json,
        restore_transaction_targets_match, restore_transactions_match,
        select_resumable_restore_transaction, validate_import_source_against_partition,
    };
    use serde_json::{Map, Value, json};
    use std::{collections::BTreeMap, fs, io::Cursor, time::Duration};

    #[test]
    fn normalizes_supported_partition_aliases() {
        assert_eq!(normalize_partition_name("initboot").unwrap(), "init_boot");
        assert_eq!(normalize_partition_name("verboot").unwrap(), "vendor_boot");
        assert!(normalize_partition_name("system").is_err());
    }

    #[test]
    fn rejects_invalid_custom_partition_paths() {
        let invalid_path = r#"{"customPartitions":{"boot":"/data/local/tmp/boot.img"}}"#;
        let unknown_partition = r#"{"customPartitions":{"system":"/dev/block/system"}}"#;
        assert!(parse_config(invalid_path).is_err());
        assert!(parse_config(unknown_partition).is_err());
    }

    #[test]
    fn validates_import_size_before_replacing_backup() {
        let directory = tempfile::tempdir().unwrap();
        let source = directory.path().join("source.img");
        let partition = directory.path().join("boot");
        fs::write(&source, b"short").unwrap();
        fs::write(&partition, b"partition").unwrap();
        let spec = PartitionSpec {
            name: "boot".to_string(),
            label: "boot".to_string(),
            image_path: directory.path().join("boot.img").display().to_string(),
            required: true,
            custom_path: None,
            ota: false,
            restore: true,
        };
        assert!(
            validate_import_source_against_partition(
                &source,
                &spec,
                &partition.display().to_string(),
            )
            .is_err()
        );
    }

    #[test]
    fn restores_preserved_file_and_does_not_reuse_stale_backup() {
        let directory = tempfile::tempdir().unwrap();
        let target = directory.path().join("boot.img");
        let target_text = target.display().to_string();
        fs::write(&target, b"known-good").unwrap();
        preserve_file(&target_text).unwrap();
        fs::write(&target, b"new-image").unwrap();
        restore_preserved_file(&target_text).unwrap();
        assert_eq!(fs::read(&target).unwrap(), b"known-good");

        fs::remove_file(&target).unwrap();
        assert!(backup_path(&target).exists());
        preserve_file(&target_text).unwrap();
        assert!(!backup_path(&target).exists());
        fs::write(&target, b"partial-import").unwrap();
        restore_preserved_file(&target_text).unwrap();
        assert!(!target.exists());
    }

    #[test]
    fn keeps_image_rollback_scoped_to_pending_flashes() {
        assert_eq!(
            post_fs_data_rescue_action(BootRescueSignals {
                pending_boot: true,
                boot_count: 2,
                ..Default::default()
            }),
            BootRescueAction::RestoreBackups,
        );
        assert_eq!(
            post_fs_data_rescue_action(BootRescueSignals {
                boot_count: 2,
                ..Default::default()
            }),
            BootRescueAction::DisableModules,
        );
        assert_eq!(
            post_fs_data_rescue_action(BootRescueSignals {
                previous_boot_ok: true,
                boot_count: 1,
                ..Default::default()
            }),
            BootRescueAction::None,
        );
        assert_eq!(
            post_fs_data_rescue_action(BootRescueSignals {
                restore_transaction_pending: true,
                previous_boot_ok: true,
                boot_count: 1,
                ..Default::default()
            }),
            BootRescueAction::RestoreBackups,
        );
    }

    #[test]
    fn recovery_requires_failure_evidence_or_repeated_pending_boots() {
        assert_eq!(
            recovery_boot_rescue_action(BootRescueSignals {
                pending_boot: true,
                boot_count: 1,
                ..Default::default()
            }),
            BootRescueAction::None,
        );
        assert_eq!(
            recovery_boot_rescue_action(BootRescueSignals {
                pending_boot: true,
                failure_hint: true,
                boot_count: 1,
                ..Default::default()
            }),
            BootRescueAction::RestoreBackups,
        );
        assert_eq!(
            recovery_boot_rescue_action(BootRescueSignals {
                pending_boot: true,
                boot_count: 2,
                ..Default::default()
            }),
            BootRescueAction::RestoreBackups,
        );
        assert_eq!(
            recovery_boot_rescue_action(BootRescueSignals {
                failure_hint: true,
                boot_count: 1,
                ..Default::default()
            }),
            BootRescueAction::DisableModules,
        );
        assert_eq!(
            recovery_boot_rescue_action(BootRescueSignals {
                restore_transaction_pending: true,
                boot_count: 1,
                ..Default::default()
            }),
            BootRescueAction::RestoreBackups,
        );
    }

    #[test]
    fn boot_completed_does_not_consume_current_boot_image_marker() {
        let pending = json!({"bootId": "boot-current"});
        assert!(marker_armed_in_boot(&pending, Some("boot-current"), false));
        assert!(!marker_armed_in_boot(
            &json!({"bootId": "boot-previous"}),
            Some("boot-current"),
            false
        ));

        let restore = json!({
            "bootId": "boot-current",
            "validationBootId": ""
        });
        assert!(marker_armed_in_boot(&restore, Some("boot-current"), true));
        assert!(!marker_armed_in_boot(
            &json!({
                "bootId": "boot-current",
                "validationBootId": "boot-current"
            }),
            Some("boot-current"),
            true
        ));
    }

    #[test]
    fn armed_boot_id_migrates_and_conflicts_fail_closed() {
        assert_eq!(
            marker_armed_boot_id(&json!({"bootId": "legacy"})),
            Some("legacy".to_owned())
        );
        assert_eq!(
            marker_armed_boot_id(&json!({
                "armedBootId": "current",
                "bootId": "current"
            })),
            Some("current".to_owned())
        );
        assert!(
            marker_armed_boot_id(&json!({
                "armedBootId": "current",
                "bootId": "old"
            }))
            .is_none()
        );
        assert!(marker_armed_in_boot(
            &json!({
                "armedBootId": "current",
                "bootId": "old"
            }),
            Some("current"),
            false
        ));
    }

    #[test]
    fn duplicate_post_fs_data_marker_is_only_current_when_ids_match() {
        let marker = json!({
            "armedBootId": "boot-current",
            "bootId": "boot-current",
        });
        assert!(marker_armed_in_current_boot_id(
            &marker,
            Some("boot-current")
        ));
        assert!(!marker_armed_in_current_boot_id(&marker, Some("boot-next")));
        assert!(!marker_armed_in_current_boot_id(&marker, None));
        assert!(!marker_armed_in_current_boot_id(
            &json!({"armedBootId": ""}),
            Some("boot-current")
        ));
    }

    #[test]
    fn restore_marker_requires_current_validation_id_to_commit_health() {
        assert!(!restore_marker_blocks_boot_commit(
            &json!({
                "armedBootId": "before",
                "bootId": "before",
                "validationBootId": "current"
            }),
            Some("current")
        ));
        assert!(restore_marker_blocks_boot_commit(
            &json!({
                "armedBootId": "before",
                "bootId": "before",
                "validationBootId": "old"
            }),
            Some("current")
        ));
        assert!(restore_marker_blocks_boot_commit(
            &json!({"validationBootId": "current"}),
            None
        ));
        assert!(restore_marker_blocks_boot_commit(
            &json!({"validationBootId": "current"}),
            Some("current")
        ));
        assert!(!restore_marker_blocks_boot_commit(
            &json!({
                "transactionId": "legacy-restore",
                "validationBootId": "current"
            }),
            Some("current")
        ));
        assert!(restore_marker_blocks_boot_commit(
            &json!({
                "armedBootId": "current",
                "bootId": "current",
                "validationBootId": "current"
            }),
            Some("current")
        ));
    }

    #[test]
    fn deep_image_verification_requires_size_and_two_valid_matching_digests() {
        let digest = "a".repeat(64);
        assert!(deep_image_verification_ok(
            true, 4096, 4096, &digest, &digest
        ));
        assert!(!deep_image_verification_ok(true, 4096, 4096, "", &digest));
        assert!(!deep_image_verification_ok(
            true,
            4096,
            4096,
            &digest,
            &"b".repeat(64)
        ));
        assert!(!deep_image_verification_ok(
            true, 4096, 2048, &digest, &digest
        ));
        assert!(!deep_image_verification_ok(
            false, 4096, 4096, &digest, &digest
        ));
    }

    #[test]
    fn verification_marker_rejects_missing_invalid_and_duplicate_files() {
        let digest = "a".repeat(64);
        let valid = json!({
            "files": [{
                "path": "/data/adb/ksu/rescue/boot.img",
                "size": 4096,
                "sha256": digest
            }]
        });
        let parsed = parse_verification_marker_files(&valid).unwrap();
        assert_eq!(parsed.len(), 1);

        let duplicate = json!({
            "files": [
                {"path": "/data/adb/ksu/rescue/boot.img", "size": 4096, "sha256": "a".repeat(64)},
                {"path": "/data/adb/ksu/rescue/boot.img", "size": 4096, "sha256": "a".repeat(64)}
            ]
        });
        assert!(parse_verification_marker_files(&duplicate).is_err());

        let invalid_digest = json!({
            "files": [{"path": "/data/adb/ksu/rescue/boot.img", "size": 4096, "sha256": "short"}]
        });
        assert!(parse_verification_marker_files(&invalid_digest).is_err());

        let zero_size = json!({
            "files": [{"path": "/data/adb/ksu/rescue/boot.img", "size": 0, "sha256": "a".repeat(64)}]
        });
        assert!(parse_verification_marker_files(&zero_size).is_err());
    }

    #[test]
    fn recovery_detection_accepts_only_explicit_current_modes() {
        for mode in ["1", "true", "yes", "recovery", "REC"] {
            assert!(
                is_recovery_mode_value(mode),
                "expected recovery mode: {mode}"
            );
        }
        for mode in [
            "normal",
            "reboot,recovery",
            "recovery-requested",
            "kernel_panic_recovery",
        ] {
            assert!(
                !is_recovery_mode_value(mode),
                "ambiguous value must not trigger recovery restore: {mode}"
            );
        }
    }

    #[test]
    fn boot_time_token_is_a_stable_fallback_for_missing_boot_id() {
        assert_eq!(
            boot_time_token("cpu 1\nbtime 1720000000\nprocesses 42\n"),
            Some("proc-stat-btime:1720000000".to_owned())
        );
        assert_eq!(boot_time_token("btime 0\n"), None);
        assert_eq!(boot_time_token("btime not-a-number\n"), None);
        assert_eq!(boot_time_token("cpu 1\n"), None);
    }

    #[test]
    fn legacy_loop_marker_requires_failure_content() {
        assert!(!has_legacy_loop_failure_text(""));
        assert!(!has_legacy_loop_failure_text("created during normal boot"));
        assert!(has_legacy_loop_failure_text("boot loop detected"));
        assert!(has_legacy_loop_failure_text("kernel panic - not syncing"));
    }

    #[test]
    fn restore_marker_allows_one_validation_boot_then_detects_failure() {
        let marker = RestorePendingBoot {
            transaction_id: "restore-1".to_owned(),
            armed_boot_id: "boot-before".to_owned(),
            validation_boot_id: String::new(),
            armed_at: "now".to_owned(),
        };
        assert_eq!(
            restore_pending_boot_state_from_marker(&marker, "boot-after", 1),
            RestorePendingBootState::Current,
        );

        let validating = RestorePendingBoot {
            validation_boot_id: "boot-after".to_owned(),
            ..marker.clone()
        };
        assert_eq!(
            restore_pending_boot_state_from_marker(&validating, "boot-after", 1),
            RestorePendingBootState::Current,
        );
        assert_eq!(
            restore_pending_boot_state_from_marker(&validating, "boot-failed", 2),
            RestorePendingBootState::Previous,
        );
        assert_eq!(
            restore_pending_boot_state_from_marker(&marker, "boot-failed", 2),
            RestorePendingBootState::Previous,
            "counter fallback must detect failure when validation boot ID was not persisted",
        );
    }

    #[test]
    fn restore_transaction_serialization_uses_current_schema() {
        let transaction = RestoreTransaction {
            id: "schema-test".to_owned(),
            reason: "unit test".to_owned(),
            automatic: false,
            description: "restore current slot".to_owned(),
            activate_slot: None,
            phase: "writing".to_owned(),
            error_code: String::new(),
            error_message: String::new(),
            started_at: "start".to_owned(),
            updated_at: "update".to_owned(),
            entries: vec![],
        };
        assert_eq!(restore_transaction_json(&transaction)["schemaVersion"], 2);
        assert!(
            parse_restore_transaction(&restore_transaction_json(&transaction).to_string()).is_ok()
        );
        assert!(
            parse_restore_transaction(
                &restore_transaction_json(&transaction)
                    .as_object()
                    .map(|object| {
                        let mut legacy = object.clone();
                        legacy.insert("schemaVersion".to_owned(), json!(1));
                        Value::Object(legacy)
                    })
                    .unwrap()
                    .to_string()
            )
            .is_ok()
        );
        assert!(
            parse_restore_transaction(
                &restore_transaction_json(&transaction)
                    .as_object()
                    .map(|object| {
                        let mut future = object.clone();
                        future.insert("schemaVersion".to_owned(), json!(99));
                        Value::Object(future)
                    })
                    .unwrap()
                    .to_string()
            )
            .is_err()
        );
    }

    #[test]
    fn restore_targets_must_match_partition_names() {
        let entry = |name: &str, path: &str| RestoreTransactionEntry {
            name: name.to_owned(),
            label: name.to_owned(),
            image_path: format!("/data/adb/ksu/rescue/{name}.img"),
            device_path: path.to_owned(),
            expected_sha256: "a".repeat(64),
            expected_size: 1,
            status: "pending".to_owned(),
        };

        assert!(restore_target_matches_entry(&entry(
            "boot",
            "/dev/block/by-name/boot_a"
        )));
        assert!(restore_target_matches_entry(&entry(
            "init_boot",
            "/dev/block/platform/soc/by-name/init_boot_b"
        )));
        assert!(!restore_target_matches_entry(&entry(
            "boot",
            "/dev/block/by-name/vendor_boot_a"
        )));
        assert!(!restore_target_matches_entry(&entry(
            "boot",
            "/dev/block/by-name/boot_a/extra"
        )));
    }

    #[test]
    fn repeated_rescue_module_failures_keep_previous_module_records() {
        let merged = merge_module_ids(
            vec!["old-module".to_owned(), "shared".to_owned()],
            &["new-module".to_owned(), "shared".to_owned()],
        );
        assert_eq!(
            merged,
            vec![
                "new-module".to_owned(),
                "old-module".to_owned(),
                "shared".to_owned()
            ]
        );
    }

    #[test]
    fn invalidating_verification_pauses_enabled_protection() {
        let directory = tempfile::tempdir().unwrap();
        let enabled = directory.path().join("enabled");
        let verified = directory.path().join("verified.json");
        fs::write(&enabled, b"1").unwrap();
        fs::write(&verified, b"verified").unwrap();

        assert!(invalidate_protection_markers_at(&enabled, &verified).unwrap());
        assert!(!enabled.exists());
        assert!(!verified.exists());

        fs::write(&verified, b"verified-again").unwrap();
        assert!(!invalidate_protection_markers_at(&enabled, &verified).unwrap());
        assert!(!verified.exists());
    }

    #[test]
    fn failure_artifact_sampling_is_bounded_but_digest_is_complete() {
        let directory = tempfile::tempdir().unwrap();
        let artifact = directory.path().join("console-ramoops");
        let content = b"kernel panic\n0123456789abcdefghijklmnopqrstuvwxyz";
        fs::write(&artifact, content).unwrap();

        let report = failure_artifact_from_path(&artifact, 16).unwrap();
        assert_eq!(report["sha256"], sha256::digest(content));
        assert_eq!(report["hasFailure"], true);
        assert_eq!(report["scannedBytes"], 16);
        assert_eq!(report["scanTruncated"], true);
    }

    #[test]
    fn stale_pstore_evidence_does_not_trigger_again() {
        let mut baseline = Map::new();
        baseline.insert("/sys/fs/pstore/console-ramoops".to_owned(), json!("old"));
        let current = BTreeMap::from([(
            "/sys/fs/pstore/console-ramoops".to_owned(),
            json!({"sha256":"old","hasFailure":true}),
        )]);

        assert!(fresh_failure_artifact_path(&baseline, &current).is_none());
    }

    #[test]
    fn fresh_pstore_evidence_is_detected_against_baseline() {
        let mut baseline = Map::new();
        baseline.insert("/sys/fs/pstore/console-ramoops".to_owned(), json!("old"));
        let current = BTreeMap::from([(
            "/sys/fs/pstore/console-ramoops".to_owned(),
            json!({"sha256":"new","hasFailure":true}),
        )]);

        assert_eq!(
            fresh_failure_artifact_path(&baseline, &current),
            Some("/sys/fs/pstore/console-ramoops".to_owned()),
        );
    }

    #[test]
    fn restore_transaction_round_trip_preserves_verified_progress() {
        let entries = vec![RestoreTransactionEntry {
            name: "boot".to_owned(),
            label: "boot".to_owned(),
            image_path: "/data/adb/ksu/rescue/boot.img".to_owned(),
            device_path: "/dev/block/by-name/boot_a".to_owned(),
            expected_sha256: "abc".to_owned(),
            expected_size: 4096,
            status: "verified".to_owned(),
        }];
        let transaction = RestoreTransaction {
            id: "test".to_owned(),
            reason: "unit test".to_owned(),
            automatic: false,
            description: "restore current slot".to_owned(),
            activate_slot: None,
            phase: "writing".to_owned(),
            error_code: String::new(),
            error_message: String::new(),
            started_at: "start".to_owned(),
            updated_at: "update".to_owned(),
            entries: entries.clone(),
        };

        let value = restore_transaction_json(&transaction);
        assert_eq!(value["phase"], Value::String("writing".to_owned()));
        assert_eq!(value["entries"][0]["status"], "verified");
        assert!(restore_transactions_match(&transaction, &entries, None));
    }

    #[test]
    fn interrupted_restore_resumes_verified_progress_after_process_restart() {
        let entries = vec![
            RestoreTransactionEntry {
                name: "boot".to_owned(),
                label: "boot".to_owned(),
                image_path: "/data/adb/ksu/rescue/boot.img".to_owned(),
                device_path: "/dev/block/by-name/boot_a".to_owned(),
                expected_sha256: "boot-digest".to_owned(),
                expected_size: 4096,
                status: "verified".to_owned(),
            },
            RestoreTransactionEntry {
                name: "init_boot".to_owned(),
                label: "init_boot".to_owned(),
                image_path: "/data/adb/ksu/rescue/init_boot.img".to_owned(),
                device_path: "/dev/block/by-name/init_boot_a".to_owned(),
                expected_sha256: "init-boot-digest".to_owned(),
                expected_size: 2048,
                status: "failed".to_owned(),
            },
        ];
        let transaction = RestoreTransaction {
            id: "interrupted".to_owned(),
            reason: "unit test".to_owned(),
            automatic: false,
            description: "restore current slot".to_owned(),
            activate_slot: None,
            phase: "failed".to_owned(),
            error_code: "rescue.io_timeout".to_owned(),
            error_message: "copy timed out".to_owned(),
            started_at: "start".to_owned(),
            updated_at: "update".to_owned(),
            entries: entries.clone(),
        };
        let temp = tempfile::tempdir().expect("create temp directory");
        let state_path = temp.path().join("restore_transaction.json");
        fs::write(
            &state_path,
            restore_transaction_json(&transaction).to_string(),
        )
        .expect("persist transaction");

        let restarted = read_restore_transaction_from(&state_path)
            .expect("read persisted transaction")
            .expect("transaction must exist");
        let resumed = select_resumable_restore_transaction(restarted, &entries, None)
            .expect("matching interrupted transaction should resume")
            .expect("transaction should be resumable");

        assert_eq!(resumed.id, "interrupted");
        assert_eq!(resumed.entries[0].status, "verified");
        assert_eq!(resumed.entries[1].status, "failed");
    }

    #[test]
    fn conflicting_restore_transaction_is_rejected() {
        let entries = vec![RestoreTransactionEntry {
            name: "boot".to_owned(),
            label: "boot".to_owned(),
            image_path: "/data/adb/ksu/rescue/boot.img".to_owned(),
            device_path: "/dev/block/by-name/boot_a".to_owned(),
            expected_sha256: "new-digest".to_owned(),
            expected_size: 4096,
            status: "pending".to_owned(),
        }];
        let existing = RestoreTransaction {
            id: "old-transaction".to_owned(),
            reason: "unit test".to_owned(),
            automatic: false,
            description: "restore current slot".to_owned(),
            activate_slot: None,
            phase: "writing".to_owned(),
            error_code: String::new(),
            error_message: String::new(),
            started_at: "start".to_owned(),
            updated_at: "update".to_owned(),
            entries: vec![RestoreTransactionEntry {
                expected_sha256: "old-digest".to_owned(),
                ..entries[0].clone()
            }],
        };

        let error = select_resumable_restore_transaction(existing, &entries, None)
            .expect_err("mismatched unfinished transaction must be rejected");
        assert_eq!(error_code(&error), "rescue.restore_transaction_conflict");
    }

    #[test]
    fn interrupted_ab_restore_can_resume_after_slot_activation_changes() {
        let existing = RestoreTransaction {
            id: "ab-restore".to_owned(),
            reason: "unit test".to_owned(),
            automatic: true,
            description: "restore saved slot".to_owned(),
            activate_slot: Some("_a".to_owned()),
            phase: "awaiting_boot_validation".to_owned(),
            error_code: String::new(),
            error_message: String::new(),
            started_at: "start".to_owned(),
            updated_at: "update".to_owned(),
            entries: vec![RestoreTransactionEntry {
                name: "boot".to_owned(),
                label: "boot".to_owned(),
                image_path: "/data/adb/ksu/rescue/boot.img".to_owned(),
                device_path: "/dev/block/by-name/boot_b".to_owned(),
                expected_sha256: "digest".to_owned(),
                expected_size: 4096,
                status: "verified".to_owned(),
            }],
        };
        let recalculated = vec![RestoreTransactionEntry {
            device_path: "/dev/block/by-name/boot_a".to_owned(),
            ..existing.entries[0].clone()
        }];

        assert!(!restore_transactions_match(&existing, &recalculated, None));
        assert!(restore_transaction_targets_match(&existing, &recalculated));
        let resumed = select_resumable_restore_transaction(existing, &recalculated, None)
            .expect("same A/B partition plan should be resumable")
            .expect("transaction should resume");
        assert_eq!(resumed.id, "ab-restore");
        assert_eq!(
            partition_target_key(&resumed.entries[0].device_path).as_deref(),
            Some("boot")
        );
    }

    #[test]
    fn slow_storage_copy_returns_structured_timeout() {
        let mut input = Cursor::new(vec![1_u8; 32]);
        let mut output = Vec::new();
        let mut ticks = [Duration::ZERO, Duration::from_secs(46)].into_iter();

        let error = copy_with_clock(
            &mut input,
            &mut output,
            Duration::from_secs(45),
            Duration::from_secs(COPY_TOTAL_TIMEOUT_SECONDS),
            || ticks.next().unwrap_or(Duration::from_secs(46)),
        )
        .expect_err("stalled copy must time out");

        assert_eq!(error_code(&error), "rescue.io_timeout");
        assert!(output.is_empty());
    }
}
