use anyhow::{Context, Result, bail, ensure};
use serde_json::json;
use std::os::unix::fs::PermissionsExt;
use std::{
    fs,
    path::{Path, PathBuf},
};

use crate::{defs, ksucalls, module, utils};

pub const KPATCH_NEXT_MODULE_ID: &str = "KPatch-Next";

const BUILTIN_ZIP: &[u8] = include_bytes!("../builtin/kpatch-next-module.zip");
const MODULE_NAME_FALLBACK: &str = "KPatch-Next";
const MODULE_VERSION_FALLBACK: &str = "v0.0.1";
const MODULE_VERSION_CODE_FALLBACK: &str = "1";
const KPATCH_NEXT_DATA_DIR: &str = "/data/adb/kp-next";

pub fn print_status() {
    if !ksucalls::is_lkm_mode() || ksucalls::is_late_load() {
        println!(
            "{}",
            json!({
                "moduleId": KPATCH_NEXT_MODULE_ID,
                "moduleName": MODULE_NAME_FALLBACK,
                "installed": false,
                "enabled": false,
                "webui": false,
                "backend": "unsupported",
                "reason": if ksucalls::is_late_load() {
                    "KPatch-Next is disabled in late-load mode"
                } else {
                    "KPatch-Next is only available in LKM mode"
                },
            })
        );
        return;
    }
    let module_dir = module_dir();
    let update_dir = update_dir();
    let module_prop = module::read_module_prop(&module_dir).unwrap_or_default();
    let module_path = module_dir.display().to_string();
    let name = module_prop
        .get("name")
        .map_or(MODULE_NAME_FALLBACK, String::as_str);
    let version = module_prop
        .get("version")
        .map_or(MODULE_VERSION_FALLBACK, String::as_str);
    let version_code = module_prop
        .get("versionCode")
        .map_or(MODULE_VERSION_CODE_FALLBACK, String::as_str);
    let installed = module_dir.join("module.prop").exists();
    // A disabled pending copy must not make the Manager render the switch as
    // enabled. `pendingUpdate` describes an update that will actually be
    // activated on the next boot.
    let pending_update = has_enabled_pending_update(&update_dir);
    let pending_remove = module_dir.join(defs::REMOVE_FILE_NAME).exists()
        || update_dir.join(defs::REMOVE_FILE_NAME).exists();
    let enabled = is_module_enabled(&module_dir)
        && !update_dir.join(defs::DISABLE_FILE_NAME).exists()
        && !pending_remove;
    let webui = module_dir
        .join(defs::MODULE_WEB_DIR)
        .join("index.html")
        .is_file();
    let unresolved = module_dir.join("unresolved").exists();
    let data_dir = Path::new(KPATCH_NEXT_DATA_DIR).exists();

    println!(
        "{}",
        json!({
            "moduleId": KPATCH_NEXT_MODULE_ID,
            "moduleName": name,
            "modulePath": module_path,
            "version": version,
            "versionCode": version_code,
            "installed": installed,
            "enabled": enabled,
            "pendingUpdate": pending_update,
            "pendingRemove": pending_remove,
            "webui": webui,
            "unresolved": unresolved,
            "dataDir": data_dir,
            "builtinAvailable": !BUILTIN_ZIP.is_empty(),
            "conflict": serde_json::Value::Null,
        })
    );
}

pub fn enable() -> Result<()> {
    ensure_kpatch_mode()?;
    let cleanup_synchronized_kpms_on_failure = !is_enabled();
    crate::kpm::migrate_to_kpatch_next()?;
    let module_dir = module_dir();
    let had_module_prop = module_dir.join("module.prop").exists();

    if let Err(error) = prepare_module_dir_for_installer(&module_dir) {
        return cleanup_after_install_failure(error, cleanup_synchronized_kpms_on_failure);
    }
    if let Err(error) = ensure_data_dirs() {
        return cleanup_after_install_failure(error, cleanup_synchronized_kpms_on_failure);
    }

    let zip_path = match write_builtin_zip() {
        Ok(path) => path,
        Err(error) => {
            return cleanup_after_install_failure(error, cleanup_synchronized_kpms_on_failure);
        }
    };
    let Some(zip_path) = zip_path.to_str() else {
        return cleanup_after_install_failure(
            anyhow::anyhow!("builtin KPatch Next zip path is not valid UTF-8"),
            cleanup_synchronized_kpms_on_failure,
        );
    };

    if let Err(e) = module::install_module(zip_path) {
        cleanup_partial_module_dir(&module_dir, had_module_prop);
        return cleanup_after_install_failure(e, cleanup_synchronized_kpms_on_failure);
    }

    remove_marker_if_exists(&module_dir.join(defs::DISABLE_FILE_NAME))?;
    remove_marker_if_exists(&update_dir().join(defs::DISABLE_FILE_NAME))?;
    remove_marker_if_exists(&module_dir.join(defs::REMOVE_FILE_NAME))?;
    remove_marker_if_exists(&module_dir.join("unresolved"))?;
    ensure_policy_aware_service(&module_dir)?;
    Ok(())
}

fn cleanup_after_install_failure(
    error: anyhow::Error,
    cleanup_synchronized_kpms: bool,
) -> Result<()> {
    if !cleanup_synchronized_kpms {
        return Err(error);
    }
    match crate::kpm::cleanup_kpatch_after_install_failure() {
        Ok(()) => Err(error),
        Err(cleanup_error) => Err(error.context(format!(
            "KPatch-Next installation failed and KPM synchronization cleanup also failed: {cleanup_error:#}"
        ))),
    }
}

pub fn is_enabled() -> bool {
    if !ksucalls::is_lkm_mode() || ksucalls::is_late_load() {
        return false;
    }
    let module_dir = module_dir();
    let update_dir = update_dir();
    is_module_enabled(&module_dir)
        && !update_dir.join(defs::DISABLE_FILE_NAME).exists()
        && !update_dir.join(defs::REMOVE_FILE_NAME).exists()
}

pub fn disable() -> Result<()> {
    ensure_kpatch_mode()?;
    let module_dir = module_dir();
    let update_dir = update_dir();
    let active_module_may_own_runtime = module_dir.join("module.prop").is_file();
    // Persist the disable marker before touching the live runtime. This is the
    // fail-closed boundary: even if KPatch is unhealthy or a live KPM cannot be
    // unloaded, the module service will not run on the next boot.
    let (active_touched, update_touched) = persist_disabled_state(&module_dir, &update_dir)?;
    let touched = active_touched || update_touched;

    if active_module_may_own_runtime && let Err(error) = crate::kpm::stop_kpatch_runtime() {
        log::warn!(
            "KPatch runtime could not be stopped while disabling the module; persistent disable will take effect after reboot: {error:#}"
        );
    }

    if touched && let Err(e) = module::regenerate_preinit_rc() {
        log::warn!("regenerate preinit rc failed: {e}");
    }
    Ok(())
}

fn persist_disabled_state(module_dir: &Path, update_dir: &Path) -> Result<(bool, bool)> {
    for dir in [module_dir, update_dir] {
        if dir.is_symlink() {
            bail!(
                "{} is a symlink, refusing to update its state",
                dir.display()
            );
        }
    }
    let active_touched = ensure_disable_marker_if_dir_exists(module_dir)?;
    let update_touched = ensure_disable_marker_if_dir_exists(update_dir)?;
    Ok((active_touched, update_touched))
}

fn is_module_enabled(dir: &Path) -> bool {
    dir.join("module.prop").is_file()
        && !dir.join(defs::DISABLE_FILE_NAME).exists()
        && !dir.join(defs::REMOVE_FILE_NAME).exists()
}

fn has_enabled_pending_update(dir: &Path) -> bool {
    dir.join("module.prop").is_file()
        && !dir.join(defs::DISABLE_FILE_NAME).exists()
        && !dir.join(defs::REMOVE_FILE_NAME).exists()
}

fn ensure_disable_marker_if_dir_exists(dir: &Path) -> Result<bool> {
    if !dir.exists() {
        return Ok(false);
    }
    remove_marker_if_exists(&dir.join(defs::REMOVE_FILE_NAME))?;
    utils::ensure_file_exists(dir.join(defs::DISABLE_FILE_NAME))?;
    Ok(true)
}

fn ensure_kpatch_mode() -> Result<()> {
    ensure!(
        ksucalls::is_lkm_mode() && !ksucalls::is_late_load(),
        "KPatch-Next is only available in LKM mode and is disabled in late-load mode"
    );
    Ok(())
}

fn module_dir() -> PathBuf {
    Path::new(defs::MODULE_DIR).join(KPATCH_NEXT_MODULE_ID)
}

fn ensure_policy_aware_service(module_dir: &Path) -> Result<()> {
    let service = module_dir.join("service.sh");
    let content = r#"#!/system/bin/sh

MODDIR=${0%/*}
KPNDIR="/data/adb/kp-next"
PATH="$MODDIR/bin:$PATH"
CONFIG="$KPNDIR/package_config"
REHOOK="$(cat "$KPNDIR/rehook" 2>/dev/null)"
KSU_KPM_POLICY="/data/adb/ksu/kpm/.policy.json"
KSU_KPM_PENDING="/data/adb/ksu/kpm/.kpatch_boot_pending"
KSU_KPM_EXCLUDES="/data/adb/ksu/kpm/.package_config"
KSUD="/data/adb/ksu/bin/ksud"

# KPatch-Next is an LKM backend only.  Keep this guard before every KPatch
# command so a stale installed module cannot start on a GKI/Native boot.
if [ ! -x "$KSUD" ]; then
    touch "$MODDIR/unresolved"
    exit 0
fi
KSU_DEBUG_INFO="$("$KSUD" debug info 2>/dev/null)"
KSU_LKM_MODE="$(printf '%s\n' "$KSU_DEBUG_INFO" | sed -n 's/^lkm: //p')"
KSU_LATE_LOAD="$(printf '%s\n' "$KSU_DEBUG_INFO" | sed -n 's/^late_load: //p')"
if [ "$KSU_LKM_MODE" != "true" ] || [ "$KSU_LATE_LOAD" != "false" ]; then
    touch "$MODDIR/unresolved"
    exit 0
fi

mkdir -p "$KPNDIR/kpm" "$(dirname "$KSU_KPM_PENDING")"
chmod 700 "$KPNDIR" "$KPNDIR/kpm" "$(dirname "$KSU_KPM_PENDING")" 2>/dev/null

remove_pending_id() {
    [ -f "$KSU_KPM_PENDING" ] || return 0
    pending_tmp="$KSU_KPM_PENDING.tmp.$$"
    while IFS= read -r pending_id; do
        [ "$pending_id" = "$1" ] || printf '%s\n' "$pending_id"
    done < "$KSU_KPM_PENDING" > "$pending_tmp"
    if [ -s "$pending_tmp" ]; then
        mv -f "$pending_tmp" "$KSU_KPM_PENDING"
    else
        rm -f "$pending_tmp" "$KSU_KPM_PENDING"
    fi
}

if [ -z "$(kpatch hello)" ]; then
    touch "$MODDIR/unresolved"
    exit 0
fi
rm -f "$MODDIR/unresolved"

LOAD_KPM=1
if [ -f "$KSU_KPM_POLICY" ] && ! grep -q '"enabled"[[:space:]]*:[[:space:]]*true' "$KSU_KPM_POLICY"; then
    LOAD_KPM=0
fi

if [ "$LOAD_KPM" = "1" ]; then
    for kpm in "$KPNDIR"/kpm/*.kpm; do
        [ -s "$kpm" ] || continue
        id="$(basename "$kpm" .kpm)"
        echo "$id" >> "$KSU_KPM_PENDING"
        args_file="$KPNDIR/kpm/$id.args"
        if [ -s "$args_file" ]; then
            args="$(cat "$args_file" 2>/dev/null)"
            load_ok=false
            kpatch kpm load "$kpm" "$args" && load_ok=true
        else
            load_ok=false
            kpatch kpm load "$kpm" && load_ok=true
        fi
        if [ "$load_ok" != "true" ]; then
            remove_pending_id "$id"
        fi
    done
fi

if [ -n "$REHOOK" ]; then
    if [ "$REHOOK" = "enable" ] || [ "$REHOOK" = "disable" ]; then
        kpatch rehook "$REHOOK"
    else
        rm -f "$KPNDIR/rehook"
    fi
fi

until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 1
done
rm -f "$KSU_KPM_PENDING"

if [ -f "$CONFIG" ]; then
    tail -n +2 "$CONFIG" | while IFS=, read -r pkg exclude allow uid; do
        if [ "$exclude" = "1" ]; then
            resolved_uid=$(awk -v package="$pkg" '$1 == package { print $2; exit }' /data/system/packages.list)
            [ -z "$resolved_uid" ] && resolved_uid="$uid"
            [ -n "$resolved_uid" ] && kpatch exclude_set "$resolved_uid" 1
        fi
    done
fi

if [ -f "$KSU_KPM_EXCLUDES" ]; then
    while IFS=, read -r pkg exclude allow uid; do
        [ "$exclude" = "1" ] || continue
        resolved_uid=$(awk -v package="$pkg" '$1 == package { print $2; exit }' /data/system/packages.list)
        [ -z "$resolved_uid" ] && resolved_uid="$uid"
        [ -n "$resolved_uid" ] && kpatch exclude_set "$resolved_uid" 1
    done < "$KSU_KPM_EXCLUDES"
fi
"#;
    fs::write(&service, content)?;
    fs::set_permissions(&service, fs::Permissions::from_mode(0o755))?;
    Ok(())
}

fn update_dir() -> PathBuf {
    Path::new(defs::MODULE_UPDATE_DIR).join(KPATCH_NEXT_MODULE_ID)
}

fn prepare_module_dir_for_installer(module_dir: &Path) -> Result<()> {
    if module_dir.is_symlink() {
        bail!(
            "{} is a symlink, refusing to update it",
            module_dir.display()
        );
    }

    utils::ensure_dir_exists(module_dir)?;
    utils::ensure_dir_exists(module_dir.join("bin"))?;
    utils::ensure_dir_exists(module_dir.join("patch"))?;
    utils::ensure_dir_exists(module_dir.join(defs::MODULE_WEB_DIR))?;
    Ok(())
}

fn ensure_data_dirs() -> Result<()> {
    utils::ensure_dir_exists(KPATCH_NEXT_DATA_DIR)?;
    utils::ensure_dir_exists(Path::new(KPATCH_NEXT_DATA_DIR).join("kpm"))?;
    Ok(())
}

fn write_builtin_zip() -> Result<PathBuf> {
    let dir = Path::new(defs::WORKING_DIR).join("builtin");
    utils::ensure_dir_exists(&dir)?;
    let zip_path = dir.join("KPatch-Next-Module.zip");
    fs::write(&zip_path, BUILTIN_ZIP)
        .with_context(|| format!("failed to write {}", zip_path.display()))?;
    Ok(zip_path)
}

fn remove_marker_if_exists(path: &Path) -> Result<()> {
    if path.exists() {
        fs::remove_file(path).with_context(|| format!("failed to remove {}", path.display()))?;
    }
    Ok(())
}

fn cleanup_partial_module_dir(module_dir: &Path, had_module_prop: bool) {
    if had_module_prop || module_dir.join("module.prop").exists() {
        return;
    }
    if let Err(e) = fs::remove_dir_all(module_dir) {
        log::warn!("failed to clean partial KPatch Next module dir: {e}");
    }
}

#[cfg(test)]
mod tests {
    use super::{has_enabled_pending_update, is_module_enabled, persist_disabled_state};
    use std::fs;
    use tempfile::tempdir;

    #[test]
    fn disabled_module_does_not_claim_an_enabled_runtime() {
        let root = tempdir().unwrap();
        assert!(!is_module_enabled(root.path()));

        fs::write(root.path().join("module.prop"), "id=KPatch-Next\n").unwrap();
        assert!(is_module_enabled(root.path()));

        fs::write(root.path().join("disable"), "").unwrap();
        assert!(!is_module_enabled(root.path()));
    }

    #[test]
    fn disabling_active_and_pending_modules_is_idempotent() {
        let root = tempdir().unwrap();
        let active = root.path().join("active");
        let update = root.path().join("update");
        fs::create_dir_all(&active).unwrap();
        fs::create_dir_all(&update).unwrap();
        fs::write(active.join("module.prop"), "id=KPatch-Next\n").unwrap();
        fs::write(update.join("module.prop"), "id=KPatch-Next\n").unwrap();
        fs::write(active.join("remove"), "").unwrap();
        fs::write(update.join("remove"), "").unwrap();

        assert_eq!(
            persist_disabled_state(&active, &update).unwrap(),
            (true, true)
        );
        assert!(active.join("disable").is_file());
        assert!(update.join("disable").is_file());
        assert!(!active.join("remove").exists());
        assert!(!update.join("remove").exists());
        assert!(!is_module_enabled(&active));

        assert_eq!(
            persist_disabled_state(&active, &update).unwrap(),
            (true, true)
        );
    }

    #[test]
    fn disabled_pending_update_is_not_reported_as_enabled() {
        let root = tempdir().unwrap();
        let update = root.path().join("update");
        fs::create_dir_all(&update).unwrap();
        fs::write(update.join("module.prop"), "id=KPatch-Next\n").unwrap();
        assert!(has_enabled_pending_update(&update));

        fs::write(update.join("disable"), "").unwrap();
        assert!(!has_enabled_pending_update(&update));

        fs::remove_file(update.join("disable")).unwrap();
        fs::write(update.join("remove"), "").unwrap();
        assert!(!has_enabled_pending_update(&update));
    }
}
