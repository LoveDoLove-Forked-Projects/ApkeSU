use anyhow::{Context, Result, bail, ensure};
use serde_json::{Value, json};
use std::collections::{BTreeMap, BTreeSet};
use std::fs::{self, DirBuilder, OpenOptions};
use std::io::{Read, Write};
use std::os::unix::fs::{DirBuilderExt, MetadataExt, OpenOptionsExt, symlink};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use crate::ksucalls;

const ROOT: &str = "/data/adb/ksu/susfs";
const GENERATIONS: &str = "/data/adb/ksu/susfs/generations";
const CURRENT: &str = "/data/adb/ksu/susfs/current";
const STATUS: &str = "/data/adb/ksu/susfs/status.conf";
const ISSUES: &str = "/data/adb/ksu/susfs/status.entries";
const SERVICE: &str = "/data/adb/service.d/98-apkesu-susfs-paths.sh";
const MAX_ENTRIES: usize = 256;
const MAX_PATH_BYTES: usize = 255;
const MAX_ARGUMENT_BYTES: usize = 4096;
const APPLY_TIMEOUT: Duration = Duration::from_secs(25);
const TOOL_CANDIDATES: [&str; 3] = [
    "/data/adb/ksu/bin/ksu_susfs",
    "/data/adb/ap/bin/ksu_susfs",
    "/system/bin/ksu_susfs",
];

const FALLBACK_SERVICE: &str = r#"#!/system/bin/sh
BASE=/data/adb/ksu/susfs
CURRENT=$BASE/current
STATUS=$BASE/status.conf
ISSUES=$BASE/status.entries
MODE=${1:-boot}
config_dir=$(readlink -f "$CURRENT" 2>/dev/null || true)
case "$config_dir" in "$BASE"/generations/*) ;; *) config_dir=$BASE ;; esac
settings=$config_dir/settings.conf
read_setting() { sed -n "s/^$1=//p" "$settings" 2>/dev/null | sed -n '1p'; }
tool=
for candidate in /data/adb/ksu/bin/ksu_susfs /data/adb/ap/bin/ksu_susfs /system/bin/ksu_susfs; do
  [ -f "$candidate" ] && [ -x "$candidate" ] && [ ! -L "$candidate" ] || continue
  tool=$candidate
  break
done
generation=$(read_setting generation)
requires_reboot=$(read_setting requires_reboot)
[ "$requires_reboot" = 1 ] || requires_reboot=0
configured=0
applied=0
failed=0
: > "$ISSUES.pending.$$"
run_tool() {
  category=$1
  target=$2
  shift 2
  configured=$((configured + 1))
  if "$tool" "$@" >/dev/null 2>&1; then
    applied=$((applied + 1))
  else
    failed=$((failed + 1))
    printf 'failed\t%s\t%s\tcommand_failed\n' "$category" "$target" >> "$ISSUES.pending.$$"
  fi
}
apply_paths() {
  file=$1
  command=$2
  category=$3
  [ -f "$file" ] || return 0
  while IFS= read -r value; do
    [ -n "$value" ] || continue
    run_tool "$category" "$value" "$command" "$value"
  done < "$file"
}
write_status() {
  state=$1
  tmp=$STATUS.pending.$$
  {
    printf 'generation=%s\n' "$generation"
    printf 'state=%s\n' "$state"
    printf 'configured_count=%s\n' "$configured"
    printf 'applied_count=%s\n' "$applied"
    printf 'skipped_count=0\n'
    printf 'failed_count=%s\n' "$failed"
    printf 'requires_reboot=%s\n' "$requires_reboot"
    printf 'finished_at=%s\n' "$(date +%s 2>/dev/null || echo 0)"
    printf 'tool=%s\n' "$tool"
  } > "$tmp"
  chmod 0600 "$tmp" "$ISSUES.pending.$$" 2>/dev/null || true
  mv -f "$tmp" "$STATUS"
  mv -f "$ISSUES.pending.$$" "$ISSUES"
}
if [ -z "$tool" ]; then
  failed=1
  printf 'failed\truntime\tksu_susfs\ttool_unavailable\n' > "$ISSUES.pending.$$"
  write_status failed
  exit 0
fi
enabled=$(read_setting enabled)
[ -n "$enabled" ] || enabled=1
logging=$(read_setting logging)
avc=$(read_setting avc_log_spoofing)
hide_mounts=$(read_setting hide_sus_mnts_for_non_su_procs)
[ "$enabled" = 1 ] || logging=0
[ "$enabled" = 1 ] || avc=0
[ "$enabled" = 1 ] || hide_mounts=0
run_tool logging "$logging" enable_log "${logging:-0}"
run_tool avc "$avc" enable_avc_log_spoofing "${avc:-0}"
run_tool mount_visibility "$hide_mounts" hide_sus_mnts_for_non_su_procs "${hide_mounts:-0}"
if [ "$enabled" != 1 ]; then
  write_status disabled
  exit 0
fi
apply_paths "$config_dir/paths.txt" add_sus_path path
apply_paths "$config_dir/path_loop.txt" add_sus_path_loop loop
apply_paths "$config_dir/sus_maps.txt" add_sus_map map
if [ -f "$config_dir/open_redirect.txt" ]; then
  while IFS='|' read -r original redirected uid_scheme; do
    [ -n "$original" ] || continue
    run_tool redirect "$original -> $redirected" add_open_redirect "$original" "$redirected" "${uid_scheme:-3}"
  done < "$config_dir/open_redirect.txt"
fi
if [ -f "$config_dir/sus_kstat_statically.txt" ]; then
  while IFS='|' read -r a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13; do
    [ -n "$a1" ] || continue
    run_tool kstat "$a1" add_sus_kstat_statically "$a1" "$a2" "$a3" "$a4" "$a5" "$a6" "$a7" "$a8" "$a9" "$a10" "$a11" "$a12" "$a13"
  done < "$config_dir/sus_kstat_statically.txt"
fi
release=$(read_setting uname_release)
version=$(read_setting uname_version)
if [ -n "$release" ] || [ -n "$version" ]; then
  run_tool uname "$release | $version" set_uname "${release:-default}" "${version:-default}"
fi
cmdline=$(read_setting cmdline_or_bootconfig)
if [ -n "$cmdline" ] && [ -f "$cmdline" ]; then
  run_tool cmdline "$cmdline" set_cmdline_or_bootconfig "$cmdline"
fi
if [ "$failed" -gt 0 ]; then
  [ "$applied" -gt 0 ] && write_status partial || write_status failed
else
  [ "$requires_reboot" = 1 ] && write_status reboot_pending || write_status applied
fi
exit 0
"#;

#[derive(Clone, Debug, Default)]
#[allow(clippy::struct_excessive_bools)]
struct SusfsConfig {
    paths: Vec<String>,
    loop_paths: Vec<String>,
    maps: Vec<String>,
    redirects: Vec<(String, String, u8)>,
    kstats: Vec<Vec<String>>,
    enabled: bool,
    logging: bool,
    avc_log_spoofing: bool,
    hide_sus_mounts: bool,
    uname_release: String,
    uname_version: String,
    cmdline_or_bootconfig: String,
}

fn ensure_gki_mode() -> Result<()> {
    ensure!(
        !ksucalls::is_lkm_mode() && !ksucalls::is_late_load(),
        "SUSFS management is only available in GKI mode"
    );
    Ok(())
}

fn trusted_tool() -> Option<PathBuf> {
    TOOL_CANDIDATES.iter().find_map(|candidate| {
        let path = Path::new(candidate);
        let metadata = fs::symlink_metadata(path).ok()?;
        (metadata.is_file()
            && !metadata.file_type().is_symlink()
            && metadata.uid() == 0
            && metadata.mode() & 0o022 == 0
            && metadata.mode() & 0o111 != 0)
            .then(|| path.to_path_buf())
    })
}

fn run_output(program: &Path, arguments: &[&str], timeout: Duration) -> Result<String> {
    let mut child = Command::new(program)
        .args(arguments)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .with_context(|| format!("execute {}", program.display()))?;
    let deadline = Instant::now() + timeout;
    loop {
        match child.try_wait()? {
            Some(status) => {
                let mut stdout = String::new();
                let mut stderr = String::new();
                if let Some(mut pipe) = child.stdout.take() {
                    pipe.read_to_string(&mut stdout)?;
                }
                if let Some(mut pipe) = child.stderr.take() {
                    pipe.read_to_string(&mut stderr)?;
                }
                ensure!(
                    status.success(),
                    "{} failed: {}",
                    program.display(),
                    stderr.trim()
                );
                return Ok(stdout);
            }
            None if Instant::now() >= deadline => {
                let _ = child.kill();
                let _ = child.wait();
                bail!("{} timed out", program.display());
            }
            None => std::thread::sleep(Duration::from_millis(40)),
        }
    }
}

fn parse_key_values(path: &Path) -> BTreeMap<String, String> {
    fs::read_to_string(path)
        .unwrap_or_default()
        .lines()
        .filter_map(|line| line.split_once('='))
        .map(|(key, value)| (key.trim().to_string(), value.trim().to_string()))
        .collect()
}

fn active_config_dir() -> PathBuf {
    let generations = Path::new(GENERATIONS);
    fs::canonicalize(CURRENT)
        .ok()
        .filter(|path| path.starts_with(generations) && path.is_dir())
        .unwrap_or_else(|| PathBuf::from(ROOT))
}

fn read_lines(path: &Path) -> Vec<String> {
    fs::read_to_string(path)
        .unwrap_or_default()
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty())
        .map(ToOwned::to_owned)
        .collect()
}

fn parse_bool(value: Option<&String>, default: bool) -> bool {
    value.map_or(default, |value| {
        matches!(value.as_str(), "1" | "2" | "true")
    })
}

fn read_config() -> SusfsConfig {
    let dir = active_config_dir();
    let settings = parse_key_values(&dir.join("settings.conf"));
    let redirects = read_lines(&dir.join("open_redirect.txt"))
        .into_iter()
        .filter_map(|line| {
            let mut parts = line.split('|');
            let original = parts.next()?.to_string();
            let redirected = parts.next()?.to_string();
            let uid = parts.next().unwrap_or("3").parse::<u8>().ok()?;
            (uid <= 4).then_some((original, redirected, uid))
        })
        .collect();
    let kstats = read_lines(&dir.join("sus_kstat_statically.txt"))
        .into_iter()
        .map(|line| line.split('|').map(ToOwned::to_owned).collect::<Vec<_>>())
        .filter(|arguments| arguments.len() == 13)
        .collect();
    SusfsConfig {
        paths: read_lines(&dir.join("paths.txt")),
        loop_paths: read_lines(&dir.join("path_loop.txt")),
        maps: read_lines(&dir.join("sus_maps.txt")),
        redirects,
        kstats,
        enabled: parse_bool(settings.get("enabled"), true),
        logging: parse_bool(settings.get("logging"), false),
        avc_log_spoofing: parse_bool(settings.get("avc_log_spoofing"), false),
        hide_sus_mounts: parse_bool(settings.get("hide_sus_mnts_for_non_su_procs"), false),
        uname_release: settings.get("uname_release").cloned().unwrap_or_default(),
        uname_version: settings.get("uname_version").cloned().unwrap_or_default(),
        cmdline_or_bootconfig: settings
            .get("cmdline_or_bootconfig")
            .cloned()
            .unwrap_or_default(),
    }
}

fn feature_names(raw: &str) -> Vec<String> {
    let mut features = raw
        .split_whitespace()
        .filter(|value| value.starts_with("CONFIG_KSU_SUSFS_"))
        .map(ToOwned::to_owned)
        .collect::<Vec<_>>();
    features.sort();
    features.dedup();
    features
}

fn runtime_json() -> Value {
    let status = parse_key_values(Path::new(STATUS));
    let issues = read_lines(Path::new(ISSUES))
        .into_iter()
        .filter_map(|line| {
            let parts = line.split('\t').collect::<Vec<_>>();
            (parts.len() == 4).then(|| {
                json!({
                    "state": parts[0],
                    "category": parts[1],
                    "target": parts[2],
                    "reason": parts[3],
                })
            })
        })
        .collect::<Vec<_>>();
    json!({
        "generation": status.get("generation").cloned().unwrap_or_default(),
        "state": status.get("state").cloned().unwrap_or_else(|| "unknown".to_string()),
        "configuredCount": number(&status, "configured_count"),
        "appliedCount": number(&status, "applied_count"),
        "skippedCount": number(&status, "skipped_count"),
        "failedCount": number(&status, "failed_count"),
        "requiresReboot": status.get("requires_reboot").is_some_and(|value| value == "1"),
        "issues": issues,
    })
}

fn number(values: &BTreeMap<String, String>, key: &str) -> u64 {
    values
        .get(key)
        .and_then(|value| value.parse().ok())
        .unwrap_or(0)
}

fn config_json(config: &SusfsConfig) -> Value {
    json!({
        "paths": config.paths,
        "loopPaths": config.loop_paths,
        "susMaps": config.maps,
        "openRedirects": config.redirects.iter().map(|(original, redirected, uid)| json!({
            "original": original,
            "redirected": redirected,
            "uidScheme": uid.to_string(),
        })).collect::<Vec<_>>(),
        "kstatEntries": config.kstats,
        "enabled": config.enabled,
        "logging": config.logging,
        "avcLogSpoofing": config.avc_log_spoofing,
        "hideSusMounts": config.hide_sus_mounts,
        "unameRelease": config.uname_release,
        "unameVersion": config.uname_version,
        "cmdlineOrBootconfig": config.cmdline_or_bootconfig,
    })
}

pub fn status() -> Result<Value> {
    ensure_gki_mode()?;
    let config = read_config();
    let tool = trusted_tool();
    let version = tool
        .as_deref()
        .and_then(|path| run_output(path, &["show", "version"], Duration::from_secs(4)).ok())
        .unwrap_or_default()
        .lines()
        .next()
        .unwrap_or_default()
        .trim()
        .to_string();
    let features = tool
        .as_deref()
        .and_then(|path| {
            run_output(path, &["show", "enabled_features"], Duration::from_secs(4)).ok()
        })
        .map_or_else(Vec::new, |value| feature_names(&value));
    let feature_probe_available = !features.is_empty();
    let supports =
        |name: &str| !feature_probe_available || features.iter().any(|item| item == name);
    let error = if tool.is_none() {
        "tool_unavailable"
    } else if !supports("CONFIG_KSU_SUSFS_SUS_PATH") {
        "path_feature_unavailable"
    } else {
        ""
    };
    Ok(json!({
        "ok": true,
        "available": error.is_empty(),
        "error": error,
        "toolPath": tool.as_ref().map_or_else(String::new, |path| path.display().to_string()),
        "version": version,
        "features": features,
        "capabilities": {
            "path": supports("CONFIG_KSU_SUSFS_SUS_PATH"),
            "mount": supports("CONFIG_KSU_SUSFS_SUS_MOUNT"),
            "kstat": supports("CONFIG_KSU_SUSFS_SUS_KSTAT"),
            "openRedirect": supports("CONFIG_KSU_SUSFS_OPEN_REDIRECT"),
            "susMap": supports("CONFIG_KSU_SUSFS_SUS_MAP"),
            "uname": supports("CONFIG_KSU_SUSFS_SPOOF_UNAME"),
            "cmdline": supports("CONFIG_KSU_SUSFS_SPOOF_CMDLINE_OR_BOOTCONFIG"),
            "logging": supports("CONFIG_KSU_SUSFS_ENABLE_LOG"),
        },
        "config": config_json(&config),
        "runtime": runtime_json(),
    }))
}

fn bool_field(value: &Value, name: &str, default: bool) -> bool {
    value.get(name).and_then(Value::as_bool).unwrap_or(default)
}

fn string_field(value: &Value, name: &str) -> Result<String> {
    let result = value
        .get(name)
        .and_then(Value::as_str)
        .unwrap_or_default()
        .trim()
        .to_string();
    ensure!(
        result.len() <= MAX_ARGUMENT_BYTES && !result.chars().any(char::is_control),
        "invalid {name}"
    );
    Ok(result)
}

fn normalized_path(raw: &str, map_path: bool) -> Option<String> {
    let trimmed = raw.trim();
    if !trimmed.starts_with('/') || trimmed == "/" || trimmed.chars().any(char::is_control) {
        return None;
    }
    let mut segments = Vec::new();
    for segment in trimmed.split('/') {
        match segment {
            "" | "." => {}
            ".." => return None,
            value => segments.push(value),
        }
    }
    let normalized = format!("/{}", segments.join("/"));
    if normalized.len() > MAX_PATH_BYTES {
        return None;
    }
    let blocked = if map_path {
        ["/data/adb", "/data/adb/ksu", "/data/adb/ap"]
            .iter()
            .any(|path| normalized == *path)
    } else {
        normalized == "/data/adb"
            || ["/data/adb/modules", "/data/adb/ksu", "/data/adb/ap"]
                .iter()
                .any(|path| normalized == *path || normalized.starts_with(&format!("{path}/")))
    };
    (!blocked).then_some(normalized)
}

fn path_array(value: &Value, name: &str, map_path: bool) -> Result<Vec<String>> {
    let items = value
        .get(name)
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    ensure!(items.len() <= MAX_ENTRIES, "too many {name} entries");
    let mut values = Vec::with_capacity(items.len());
    let mut seen = BTreeSet::new();
    for item in items {
        let raw = item.as_str().context("path entry must be a string")?;
        let path = normalized_path(raw, map_path).context("invalid SUSFS path")?;
        ensure!(seen.insert(path.clone()), "duplicate SUSFS path: {path}");
        values.push(path);
    }
    Ok(values)
}

fn parse_config(value: &Value) -> Result<SusfsConfig> {
    let paths = path_array(value, "paths", false)?;
    let loop_paths = path_array(value, "loopPaths", false)?;
    let maps = path_array(value, "susMaps", true)?;
    let redirect_values = value
        .get("openRedirects")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    ensure!(
        redirect_values.len() <= MAX_ENTRIES,
        "too many open redirects"
    );
    let mut redirects = Vec::with_capacity(redirect_values.len());
    for item in redirect_values {
        let original = normalized_path(
            item.get("original")
                .and_then(Value::as_str)
                .unwrap_or_default(),
            false,
        )
        .context("invalid redirect source")?;
        let redirected = normalized_path(
            item.get("redirected")
                .and_then(Value::as_str)
                .unwrap_or_default(),
            false,
        )
        .context("invalid redirect target")?;
        let uid = item
            .get("uidScheme")
            .and_then(|value| {
                value
                    .as_str()
                    .and_then(|text| text.parse::<u8>().ok())
                    .or_else(|| value.as_u64().and_then(|number| u8::try_from(number).ok()))
            })
            .unwrap_or(3);
        ensure!(uid <= 4, "redirect UID scheme must be between 0 and 4");
        redirects.push((original, redirected, uid));
    }
    let kstat_values = value
        .get("kstatEntries")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    ensure!(kstat_values.len() <= MAX_ENTRIES, "too many kstat entries");
    let mut kstats = Vec::with_capacity(kstat_values.len());
    for entry in kstat_values {
        let arguments = entry.as_array().context("kstat entry must be an array")?;
        ensure!(arguments.len() == 13, "kstat entry requires 13 arguments");
        let mut normalized = Vec::with_capacity(13);
        for argument in arguments {
            let value = argument
                .as_str()
                .context("kstat argument must be a string")?
                .trim();
            ensure!(
                !value.is_empty()
                    && value.len() <= MAX_ARGUMENT_BYTES
                    && !value.contains('|')
                    && !value.chars().any(char::is_control),
                "invalid kstat argument"
            );
            normalized.push(value.to_string());
        }
        kstats.push(normalized);
    }
    Ok(SusfsConfig {
        paths,
        loop_paths,
        maps,
        redirects,
        kstats,
        enabled: bool_field(value, "enabled", true),
        logging: bool_field(value, "logging", false),
        avc_log_spoofing: bool_field(value, "avcLogSpoofing", false),
        hide_sus_mounts: bool_field(value, "hideSusMounts", false),
        uname_release: string_field(value, "unameRelease")?,
        uname_version: string_field(value, "unameVersion")?,
        cmdline_or_bootconfig: string_field(value, "cmdlineOrBootconfig")?,
    })
}

fn removals_require_reboot(previous: &SusfsConfig, next: &SusfsConfig) -> bool {
    (previous.enabled && !next.enabled)
        || previous.paths.iter().any(|item| !next.paths.contains(item))
        || previous
            .loop_paths
            .iter()
            .any(|item| !next.loop_paths.contains(item))
        || previous.maps.iter().any(|item| !next.maps.contains(item))
        || previous
            .redirects
            .iter()
            .any(|item| !next.redirects.contains(item))
        || previous
            .kstats
            .iter()
            .any(|item| !next.kstats.contains(item))
}

fn write_file(path: &Path, content: &str, mode: u32) -> Result<()> {
    let mut file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(mode)
        .open(path)
        .with_context(|| format!("create {}", path.display()))?;
    file.write_all(content.as_bytes())?;
    file.sync_all()?;
    Ok(())
}

fn line_file(values: &[String]) -> String {
    if values.is_empty() {
        String::new()
    } else {
        format!("{}\n", values.join("\n"))
    }
}

fn persist_config(config: &SusfsConfig, requires_reboot: bool) -> Result<String> {
    DirBuilder::new()
        .recursive(true)
        .mode(0o700)
        .create(GENERATIONS)?;
    let nonce = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis();
    let generation = format!("web-{nonce}-{}", std::process::id());
    let directory = Path::new(GENERATIONS).join(&generation);
    DirBuilder::new().mode(0o700).create(&directory)?;
    let result = (|| -> Result<()> {
        write_file(
            &directory.join("paths.txt"),
            &line_file(&config.paths),
            0o600,
        )?;
        write_file(
            &directory.join("path_loop.txt"),
            &line_file(&config.loop_paths),
            0o600,
        )?;
        write_file(
            &directory.join("sus_maps.txt"),
            &line_file(&config.maps),
            0o600,
        )?;
        let redirects = config
            .redirects
            .iter()
            .map(|(source, target, uid)| format!("{source}|{target}|{uid}"))
            .collect::<Vec<_>>();
        write_file(
            &directory.join("open_redirect.txt"),
            &line_file(&redirects),
            0o600,
        )?;
        let kstats = config
            .kstats
            .iter()
            .map(|arguments| arguments.join("|"))
            .collect::<Vec<_>>();
        write_file(
            &directory.join("sus_kstat_statically.txt"),
            &line_file(&kstats),
            0o600,
        )?;
        let settings = format!(
            "enabled={}\nlogging={}\navc_log_spoofing={}\nhide_sus_mnts_for_non_su_procs={}\nuname_release={}\nuname_version={}\ncmdline_or_bootconfig={}\ngeneration={}\nrequires_reboot={}\n",
            u8::from(config.enabled),
            u8::from(config.logging),
            u8::from(config.avc_log_spoofing),
            u8::from(config.hide_sus_mounts),
            config.uname_release,
            config.uname_version,
            config.cmdline_or_bootconfig,
            generation,
            u8::from(requires_reboot),
        );
        write_file(&directory.join("settings.conf"), &settings, 0o600)?;
        let temporary = Path::new(ROOT).join(format!(".current-{}", std::process::id()));
        if let Err(error) = fs::remove_file(&temporary)
            && error.kind() != std::io::ErrorKind::NotFound
        {
            return Err(error.into());
        }
        symlink(&directory, &temporary)?;
        fs::rename(&temporary, CURRENT)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_dir_all(&directory);
    }
    result?;
    Ok(generation)
}

fn ensure_service() -> Result<()> {
    let path = Path::new(SERVICE);
    if let Ok(metadata) = fs::symlink_metadata(path) {
        ensure!(
            metadata.is_file() && !metadata.file_type().is_symlink(),
            "invalid SUSFS service script"
        );
        return Ok(());
    }
    fs::create_dir_all("/data/adb/service.d")?;
    let temporary = Path::new("/data/adb/service.d")
        .join(format!(".98-apkesu-susfs-paths.sh.{}", std::process::id()));
    if let Err(error) = fs::remove_file(&temporary)
        && error.kind() != std::io::ErrorKind::NotFound
    {
        return Err(error.into());
    }
    write_file(&temporary, FALLBACK_SERVICE, 0o755)?;
    fs::rename(temporary, path)?;
    Ok(())
}

pub fn apply(payload: &Value) -> Result<Value> {
    ensure_gki_mode()?;
    let value = payload.get("config").unwrap_or(payload);
    ensure!(value.is_object(), "SUSFS config must be an object");
    let previous = read_config();
    let config = parse_config(value)?;
    let requires_reboot = removals_require_reboot(&previous, &config);
    let generation = persist_config(&config, requires_reboot)?;
    ensure_service()?;
    let apply_error = run_output(
        Path::new("/system/bin/sh"),
        &[SERVICE, "--immediate"],
        APPLY_TIMEOUT,
    )
    .err()
    .map(|error| format!("{error:#}"));
    let mut result = status()?;
    let object = result.as_object_mut().expect("SUSFS status is an object");
    object.insert("generation".to_string(), Value::String(generation));
    object.insert("requiresReboot".to_string(), Value::Bool(requires_reboot));
    object.insert(
        "applyError".to_string(),
        apply_error.map_or(Value::Null, Value::String),
    );
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::{normalized_path, parse_config};
    use serde_json::json;

    #[test]
    fn normalizes_and_blocks_management_paths() {
        assert_eq!(
            normalized_path("/data/local/./tmp/example", false).as_deref(),
            Some("/data/local/tmp/example")
        );
        assert!(normalized_path("/data/adb/ksu/bin", false).is_none());
        assert!(normalized_path("/data/local/../adb", false).is_none());
    }

    #[test]
    fn rejects_invalid_redirect_uid_scheme() {
        let error = parse_config(&json!({
            "paths": ["/data/local/tmp/example"],
            "openRedirects": [{
                "original": "/data/local/tmp/source",
                "redirected": "/data/local/tmp/target",
                "uidScheme": "9",
            }],
        }))
        .expect_err("invalid uid scheme must fail");
        assert!(error.to_string().contains("UID scheme"));
    }
}
