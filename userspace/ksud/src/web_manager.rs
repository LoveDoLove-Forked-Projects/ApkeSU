use anyhow::{Context, Result, bail, ensure};
use log::{error, info, warn};
use rust_embed::RustEmbed;
use serde_json::{Value, json};
use std::borrow::Cow;
use std::collections::{HashMap, HashSet};
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpListener, TcpStream};
use std::os::fd::AsRawFd;
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use crate::{
    builtin_mount, defs, dynamic_manager, feature, init_event, kpatch_next, kpm, ksucalls, module,
    pathmask, utils, web_manager_susfs,
};

const DEFAULT_PORT: u16 = 10_240;
const REST_API_VERSION: u32 = 1;
const REST_API_PREFIX: &str = "/api/v1";
const CONFIG_SCHEMA_VERSION: u32 = 1;
const MAX_HEADER_BYTES: usize = 16 * 1024;
const MAX_BODY_BYTES: usize = 8 * 1024 * 1024;
const MAX_ASSET_BYTES: usize = MAX_BODY_BYTES;
const MAX_KPM_BYTES: usize = 4 * 1024 * 1024;
const MAX_CLIENTS: usize = 8;
const IO_TIMEOUT: Duration = Duration::from_secs(8);
const START_TIMEOUT: Duration = Duration::from_secs(3);
/// 主页系统信息探测里外部命令（ksud 自身、ksu_susfs）的硬超时。
const COMMAND_TIMEOUT: Duration = Duration::from_secs(8);
const SYSTEM_CACHE_TTL: Duration = Duration::from_mins(1);
const COOKIE_NAME: &str = "apkesu_web_token";
const TOKEN_PATH_PREFIX: &str = "/w/";
const PACKAGES_LIST_PATH: &str = "/data/system/packages.list";
const APP_METADATA_PATH: &str = "/data/adb/ksu/web_manager_apps/apps.json";
const APP_ICON_DIR: &str = "/data/adb/ksu/web_manager_apps/icons";
const MAX_APP_METADATA_BYTES: u64 = 4 * 1024 * 1024;
const MAX_APP_ICON_BYTES: u64 = 1024 * 1024;
const STEALTH_CODE_PATH: &str = "/data/adb/ksu/hansu_code";
const STEALTH_MODE_PATH: &str = "/data/adb/ksu/hansu_mode";
const DEFAULT_STEALTH_CODE: &str = "*#*#4211#*#*";
const STEALTH_MODE_DISABLED_ACTION: &str = "me.weishu.kernelsu.action.STEALTH_MODE_DISABLED";
const STEALTH_MODE_CHANGED_ACTION: &str = "me.weishu.kernelsu.action.STEALTH_MODE_CHANGED";
const MANAGER_SETTINGS_CHANGED_ACTION: &str = "me.weishu.kernelsu.action.MANAGER_SETTINGS_CHANGED";
const MANAGER_SETTINGS_SCHEMA_VERSION: u32 = 1;
const MAX_CUSTOM_HOME_TITLE_CHARS: usize = 40;
const SUPPORTED_MANAGER_LANGUAGES: [&str; 7] = ["zh-CN", "en", "fr", "ru", "ja", "ko", "es"];
const WALLPAPER_TARGETS: [(&str, &str); 4] = [
    ("lkm", "主卡片（LKM/GKI）"),
    ("superuser", "超级用户卡片"),
    ("module", "模块卡片"),
    ("device", "设备信息卡片"),
];
const NAV_ICON_SLOTS: [(&str, &str); 5] = [
    ("home", "主页"),
    ("superuser", "授权"),
    ("module", "模块"),
    ("kpm", "KPM / 工具"),
    ("settings", "设置"),
];
const FEATURE_TOGGLES: [(&str, &str, &str, &str, Option<&str>); 7] = [
    (
        "suCompat",
        "su_compat",
        "su 兼容模式",
        "允许已授权应用通过 /system/bin/su 获取 Root",
        None,
    ),
    (
        "kernelUmount",
        "kernel_umount",
        "内核 umount 模块",
        "对需要隐藏环境的进程卸载模块挂载",
        None,
    ),
    (
        "webviewUmount",
        "webview_zygote_umount",
        "WebView 隔离 umount",
        "对 WebView zygote 与隔离进程隐藏模块挂载",
        None,
    ),
    (
        "selinuxHide",
        "selinux_hide",
        "SELinux 隐藏",
        "隐藏应用可见的 SELinux 策略改动痕迹",
        Some("部分内核需要重启生效"),
    ),
    (
        "sulog",
        "sulog",
        "su 日志",
        "记录 su 请求与授权结果，便于排查",
        None,
    ),
    (
        "adbRoot",
        "adb_root",
        "ADB Root",
        "允许 adbd 以 Root 身份运行",
        Some("切换后会重启 adbd"),
    ),
    (
        "avcSpoof",
        "avc_spoof",
        "AVC 伪装",
        "隐藏 KernelSU SELinux 域相关的 AVC 日志",
        None,
    ),
];

static SHUTDOWN: AtomicBool = AtomicBool::new(false);
static WRITE_LOCK: Mutex<()> = Mutex::new(());
/// 主页系统信息探测（SELinux/Seccomp/KPM/SUSFS）的短缓存，避免每次刷新都跑外部命令。
static SYSTEM_CACHE: Mutex<Option<(Instant, Value)>> = Mutex::new(None);

#[derive(RustEmbed)]
#[folder = "web_manager/"]
struct WebAssets;

#[derive(Clone, Debug)]
struct WebManagerConfig {
    schema_version: u32,
    enabled: bool,
    port: u16,
    token: String,
}

impl Default for WebManagerConfig {
    fn default() -> Self {
        Self {
            schema_version: CONFIG_SCHEMA_VERSION,
            enabled: false,
            port: DEFAULT_PORT,
            token: String::new(),
        }
    }
}

#[derive(Clone, Debug)]
struct WebManagerState {
    pid: u32,
    port: u16,
    started_at: u64,
}

#[derive(Debug)]
struct Request {
    method: String,
    target: String,
    headers: HashMap<String, String>,
    body: Vec<u8>,
}

#[derive(Debug)]
struct Response {
    status: u16,
    content_type: &'static str,
    body: Vec<u8>,
    set_cookie: Option<String>,
}

impl Response {
    #[allow(clippy::needless_pass_by_value)]
    fn json(status: u16, value: Value) -> Self {
        Self {
            status,
            content_type: "application/json; charset=utf-8",
            body: serde_json::to_vec(&value)
                .unwrap_or_else(|_| b"{\"error\":\"serialization failed\"}".to_vec()),
            set_cookie: None,
        }
    }

    fn text(status: u16, content_type: &'static str, body: impl Into<Vec<u8>>) -> Self {
        Self {
            status,
            content_type,
            body: body.into(),
            set_cookie: None,
        }
    }

    fn error(status: u16, code: &str, message: impl Into<String>) -> Self {
        Self::json(
            status,
            json!({
                "ok": false,
                "error": {
                    "code": code,
                    "message": message.into(),
                }
            }),
        )
    }
}

struct LockGuard {
    file: File,
}

impl Drop for LockGuard {
    fn drop(&mut self) {
        unsafe {
            libc::flock(self.file.as_raw_fd(), libc::LOCK_UN);
        }
    }
}

struct StateGuard;

impl Drop for StateGuard {
    fn drop(&mut self) {
        let _ = fs::remove_file(defs::WEB_MANAGER_STATE_PATH);
        let _ = fs::remove_file(defs::WEB_MANAGER_STOP_PATH);
    }
}

struct ClientGuard {
    active: Arc<AtomicUsize>,
}

impl Drop for ClientGuard {
    fn drop(&mut self) {
        self.active.fetch_sub(1, Ordering::AcqRel);
    }
}

#[derive(Clone)]
struct ServerContext {
    config: WebManagerConfig,
}

extern "C" fn shutdown_signal_handler(_signal: libc::c_int) {
    SHUTDOWN.store(true, Ordering::Release);
}

fn unix_timestamp() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

fn ensure_storage() -> Result<()> {
    let path = Path::new(defs::WEB_MANAGER_DIR);
    fs::create_dir_all(path).with_context(|| format!("create {}", path.display()))?;
    fs::set_permissions(path, fs::Permissions::from_mode(0o700))
        .with_context(|| format!("chmod {}", path.display()))?;
    Ok(())
}

fn atomic_write(path: &Path, bytes: &[u8], mode: u32) -> Result<()> {
    let file_name = path
        .file_name()
        .and_then(|name| name.to_str())
        .context("invalid web manager state path")?;
    let temporary = path.with_file_name(format!(".{file_name}.tmp.{}", std::process::id()));
    let mut file = OpenOptions::new()
        .create(true)
        .truncate(true)
        .write(true)
        .mode(mode)
        .open(&temporary)
        .with_context(|| format!("open {}", temporary.display()))?;
    file.write_all(bytes)
        .with_context(|| format!("write {}", temporary.display()))?;
    file.sync_all()
        .with_context(|| format!("sync {}", temporary.display()))?;
    fs::rename(&temporary, path).with_context(|| format!("replace {}", path.display()))?;
    fs::set_permissions(path, fs::Permissions::from_mode(mode))
        .with_context(|| format!("chmod {}", path.display()))?;
    Ok(())
}

fn generate_token() -> Result<String> {
    let mut random = [0_u8; 32];
    File::open("/dev/urandom")
        .context("open /dev/urandom")?
        .read_exact(&mut random)
        .context("read /dev/urandom")?;
    let mut token = String::with_capacity(random.len() * 2);
    for byte in random {
        use std::fmt::Write as _;
        write!(token, "{byte:02x}").context("encode authentication token")?;
    }
    Ok(token)
}

fn normalize_config(mut config: WebManagerConfig) -> Result<WebManagerConfig> {
    config.schema_version = CONFIG_SCHEMA_VERSION;
    if config.port < 1024 {
        config.port = DEFAULT_PORT;
    }
    if config.token.len() != 64 || !config.token.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        config.token = generate_token()?;
    }
    config.token.make_ascii_lowercase();
    Ok(config)
}

fn read_config() -> Result<WebManagerConfig> {
    let path = Path::new(defs::WEB_MANAGER_CONFIG_PATH);
    if !path.is_file() {
        return normalize_config(WebManagerConfig::default());
    }
    let bytes = fs::read(path).with_context(|| format!("read {}", path.display()))?;
    let value: Value = serde_json::from_slice(&bytes).context("parse web manager config")?;
    let object = value
        .as_object()
        .context("web manager config is not an object")?;
    let config = WebManagerConfig {
        schema_version: object
            .get("schemaVersion")
            .and_then(Value::as_u64)
            .unwrap_or_else(|| u64::from(CONFIG_SCHEMA_VERSION)) as u32,
        enabled: object
            .get("enabled")
            .and_then(Value::as_bool)
            .unwrap_or(false),
        port: object
            .get("port")
            .and_then(Value::as_u64)
            .unwrap_or_else(|| u64::from(DEFAULT_PORT)) as u16,
        token: object
            .get("token")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_string(),
    };
    normalize_config(config)
}

fn write_config(config: &WebManagerConfig) -> Result<()> {
    ensure_storage()?;
    let bytes = serde_json::to_vec_pretty(&json!({
        "schemaVersion": config.schema_version,
        "enabled": config.enabled,
        "port": config.port,
        "token": config.token,
    }))
    .context("serialize web manager config")?;
    atomic_write(Path::new(defs::WEB_MANAGER_CONFIG_PATH), &bytes, 0o600)
}

fn read_state() -> Option<WebManagerState> {
    let bytes = fs::read(defs::WEB_MANAGER_STATE_PATH).ok()?;
    let value: Value = serde_json::from_slice(&bytes).ok()?;
    let object = value.as_object()?;
    Some(WebManagerState {
        pid: object.get("pid")?.as_u64()? as u32,
        port: object.get("port")?.as_u64()? as u16,
        started_at: object.get("startedAt")?.as_u64()?,
    })
}

fn write_state(state: &WebManagerState) -> Result<()> {
    let bytes = serde_json::to_vec_pretty(&json!({
        "pid": state.pid,
        "port": state.port,
        "startedAt": state.started_at,
    }))
    .context("serialize web manager state")?;
    atomic_write(Path::new(defs::WEB_MANAGER_STATE_PATH), &bytes, 0o600)
}

fn acquire_server_lock() -> Result<LockGuard> {
    ensure_storage()?;
    let file = OpenOptions::new()
        .create(true)
        .read(true)
        .write(true)
        .truncate(false)
        .mode(0o600)
        .open(defs::WEB_MANAGER_LOCK_PATH)
        .context("open web manager lock")?;
    let result = unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) };
    if result != 0 {
        bail!("web manager is already running");
    }
    Ok(LockGuard { file })
}

const fn server_addr(port: u16) -> SocketAddr {
    SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), port)
}

fn state_is_running(state: &WebManagerState) -> bool {
    TcpStream::connect_timeout(&server_addr(state.port), Duration::from_millis(250)).is_ok()
}

fn public_status(config: &WebManagerConfig) -> Value {
    let state = read_state().filter(state_is_running);
    let running = state.is_some();
    let port = state.as_ref().map_or(config.port, |value| value.port);
    json!({
        "supported": true,
        "enabled": config.enabled,
        "running": running,
        "port": port,
        "pid": state.as_ref().map(|value| value.pid),
        "url": running.then(|| authenticated_url(config, port)),
        "persistent": true,
        "error": Value::Null,
    })
}

fn authenticated_url(config: &WebManagerConfig, port: u16) -> String {
    format!(
        "http://127.0.0.1:{port}{TOKEN_PATH_PREFIX}{}/",
        config.token
    )
}

fn status_running(status: &Value) -> bool {
    status
        .get("running")
        .and_then(Value::as_bool)
        .unwrap_or(false)
}

fn print_json(value: &Value) -> Result<()> {
    println!("{}", serde_json::to_string_pretty(value)?);
    Ok(())
}

pub fn enable(port: Option<u16>) -> Result<()> {
    let mut config = read_config()?;
    if let Some(port) = port {
        ensure!(port >= 1024, "port must be between 1024 and 65535");
        if config.port != port && status_running(&public_status(&config)) {
            stop()?;
        }
        config.port = port;
    }
    config.enabled = true;
    write_config(&config)?;
    start_background(&config)?;
    print_status()
}

pub fn disable() -> Result<()> {
    let mut config = read_config()?;
    config.enabled = false;
    write_config(&config)?;
    stop()?;
    print_status()
}

pub fn start() -> Result<()> {
    let config = read_config()?;
    write_config(&config)?;
    start_background(&config)?;
    print_status()
}

pub fn start_if_enabled() {
    let result = read_config().and_then(|config| {
        if config.enabled {
            start_background(&config)
        } else {
            Ok(())
        }
    });
    if let Err(error) = result {
        warn!("failed to start persistent web manager: {error:#}");
    }
}

pub fn stop() -> Result<()> {
    ensure_storage()?;
    let Some(state) = read_state() else {
        let _ = fs::remove_file(defs::WEB_MANAGER_STOP_PATH);
        return Ok(());
    };
    atomic_write(Path::new(defs::WEB_MANAGER_STOP_PATH), b"stop\n", 0o600)?;
    let _ = TcpStream::connect_timeout(&server_addr(state.port), Duration::from_millis(250));
    let deadline = Instant::now() + START_TIMEOUT;
    while Instant::now() < deadline {
        if !state_is_running(&state) {
            let _ = fs::remove_file(defs::WEB_MANAGER_STATE_PATH);
            let _ = fs::remove_file(defs::WEB_MANAGER_STOP_PATH);
            return Ok(());
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    bail!("web manager did not stop before the timeout")
}

pub fn print_status() -> Result<()> {
    let config = read_config()?;
    print_json(&public_status(&config))
}

pub fn print_url() -> Result<()> {
    let config = read_config()?;
    let status = public_status(&config);
    ensure!(status_running(&status), "web manager is not running");
    let port = status
        .get("port")
        .and_then(Value::as_u64)
        .context("web manager status has no port")? as u16;
    println!("{}", authenticated_url(&config, port));
    Ok(())
}

pub fn rotate_token() -> Result<()> {
    let mut config = read_config()?;
    let was_running = status_running(&public_status(&config));
    if was_running {
        stop()?;
    }
    config.token = generate_token()?;
    write_config(&config)?;
    if was_running {
        start_background(&config)?;
    }
    print_url().or_else(|_| print_status())
}

fn start_background(config: &WebManagerConfig) -> Result<()> {
    if status_running(&public_status(config)) {
        return Ok(());
    }
    let _ = fs::remove_file(defs::WEB_MANAGER_STOP_PATH);
    if utils::create_daemon(true)? {
        let result = run_server(config.clone());
        if let Err(error) = result {
            error!("persistent web manager exited: {error:#}");
        }
        unsafe { libc::_exit(0) }
    } else {
        let deadline = Instant::now() + START_TIMEOUT;
        while Instant::now() < deadline {
            if status_running(&public_status(config)) {
                return Ok(());
            }
            std::thread::sleep(Duration::from_millis(50));
        }
        bail!("web manager did not start before the timeout")
    }
}

pub fn serve() -> Result<()> {
    let config = read_config()?;
    run_server(config)
}

fn run_server(config: WebManagerConfig) -> Result<()> {
    let _lock = acquire_server_lock()?;
    utils::switch_mnt_ns(1).context("switch to the global mount namespace")?;
    let listener = TcpListener::bind(server_addr(config.port))
        .with_context(|| format!("bind web manager to 127.0.0.1:{}", config.port))?;
    listener
        .set_nonblocking(true)
        .context("set web manager listener nonblocking")?;
    SHUTDOWN.store(false, Ordering::Release);
    unsafe {
        libc::signal(
            libc::SIGTERM,
            shutdown_signal_handler as *const () as libc::sighandler_t,
        );
        libc::signal(
            libc::SIGINT,
            shutdown_signal_handler as *const () as libc::sighandler_t,
        );
    }
    let state = WebManagerState {
        pid: std::process::id(),
        port: config.port,
        started_at: unix_timestamp(),
    };
    write_state(&state)?;
    let _state_guard = StateGuard;
    let context = Arc::new(ServerContext { config });
    let active_clients = Arc::new(AtomicUsize::new(0));
    info!(
        "persistent web manager listening on 127.0.0.1:{}",
        state.port
    );

    while !SHUTDOWN.load(Ordering::Acquire) && !Path::new(defs::WEB_MANAGER_STOP_PATH).exists() {
        match listener.accept() {
            Ok((stream, peer)) => {
                if !peer.ip().is_loopback() {
                    continue;
                }
                let previous = active_clients.fetch_add(1, Ordering::AcqRel);
                if previous >= MAX_CLIENTS {
                    active_clients.fetch_sub(1, Ordering::AcqRel);
                    let mut stream = stream;
                    let _ = write_response(
                        &mut stream,
                        Response::error(503, "busy", "网页管理器正忙，请稍后重试"),
                    );
                    continue;
                }
                let active = Arc::clone(&active_clients);
                let server_context = Arc::clone(&context);
                let spawn_result = std::thread::Builder::new()
                    .name("ksud-web-client".to_string())
                    .spawn(move || {
                        let _guard = ClientGuard { active };
                        if let Err(error) = handle_client(stream, &server_context) {
                            warn!("web manager client failed: {error:#}");
                        }
                    });
                if let Err(error) = spawn_result {
                    active_clients.fetch_sub(1, Ordering::AcqRel);
                    warn!("failed to spawn web manager client: {error}");
                }
            }
            Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                std::thread::sleep(Duration::from_millis(50));
            }
            Err(error) => return Err(error).context("accept web manager client"),
        }
    }
    info!("persistent web manager stopped");
    Ok(())
}

fn read_request(stream: &mut TcpStream) -> Result<Request> {
    stream.set_read_timeout(Some(IO_TIMEOUT))?;
    stream.set_write_timeout(Some(IO_TIMEOUT))?;
    let mut buffer = Vec::with_capacity(4096);
    let mut chunk = [0_u8; 2048];
    let header_end = loop {
        let count = stream.read(&mut chunk).context("read HTTP request")?;
        ensure!(count != 0, "connection closed before request headers");
        buffer.extend_from_slice(&chunk[..count]);
        ensure!(
            buffer.len() <= MAX_HEADER_BYTES,
            "HTTP request headers are too large"
        );
        if let Some(position) = find_subslice(&buffer, b"\r\n\r\n") {
            break position + 4;
        }
    };
    let header =
        std::str::from_utf8(&buffer[..header_end]).context("HTTP headers are not UTF-8")?;
    let mut lines = header.split("\r\n");
    let request_line = lines.next().context("missing HTTP request line")?;
    let mut request_parts = request_line.split_whitespace();
    let method = request_parts
        .next()
        .context("missing HTTP method")?
        .to_string();
    let target = request_parts
        .next()
        .context("missing HTTP target")?
        .to_string();
    let version = request_parts.next().context("missing HTTP version")?;
    ensure!(
        version == "HTTP/1.1" || version == "HTTP/1.0",
        "unsupported HTTP version"
    );
    ensure!(request_parts.next().is_none(), "invalid HTTP request line");
    ensure!(
        matches!(method.as_str(), "GET" | "POST" | "DELETE"),
        "unsupported HTTP method"
    );
    ensure!(
        target.starts_with('/') && target.len() <= 4096,
        "invalid HTTP target"
    );

    let mut headers = HashMap::new();
    for line in lines.filter(|line| !line.is_empty()) {
        let (name, value) = line.split_once(':').context("invalid HTTP header")?;
        headers.insert(name.trim().to_ascii_lowercase(), value.trim().to_string());
    }
    if headers
        .get("transfer-encoding")
        .is_some_and(|value| !value.eq_ignore_ascii_case("identity"))
    {
        bail!("chunked requests are not supported");
    }
    let content_length = headers.get("content-length").map_or(Ok(0), |value| {
        value.parse::<usize>().context("invalid Content-Length")
    })?;
    ensure!(
        content_length <= MAX_BODY_BYTES,
        "HTTP request body is too large"
    );
    while buffer.len() - header_end < content_length {
        let count = stream.read(&mut chunk).context("read HTTP request body")?;
        ensure!(count != 0, "connection closed before request body");
        buffer.extend_from_slice(&chunk[..count]);
    }
    let body = buffer[header_end..header_end + content_length].to_vec();
    Ok(Request {
        method,
        target,
        headers,
        body,
    })
}

fn find_subslice(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    haystack
        .windows(needle.len())
        .position(|window| window == needle)
}

fn handle_client(mut stream: TcpStream, context: &ServerContext) -> Result<()> {
    let response = match read_request(&mut stream) {
        Ok(request) => route_request(&request, context),
        Err(error) => Response::error(400, "bad_request", error.to_string()),
    };
    write_response(&mut stream, response)
}

fn write_response(stream: &mut TcpStream, response: Response) -> Result<()> {
    let reason = match response.status {
        200 => "OK",
        202 => "Accepted",
        400 => "Bad Request",
        401 => "Unauthorized",
        403 => "Forbidden",
        404 => "Not Found",
        405 => "Method Not Allowed",
        409 => "Conflict",
        413 => "Payload Too Large",
        415 => "Unsupported Media Type",
        429 => "Too Many Requests",
        500 => "Internal Server Error",
        503 => "Service Unavailable",
        _ => "Error",
    };
    let cookie = response
        .set_cookie
        .map(|value| format!("Set-Cookie: {value}\r\n"))
        .unwrap_or_default();
    let header = format!(
        "HTTP/1.1 {} {}\r\nContent-Type: {}\r\nContent-Length: {}\r\nConnection: close\r\nCache-Control: no-store\r\nX-Ksud-Api-Version: {}\r\nService-Worker-Allowed: /\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; connect-src 'self'; worker-src 'self'; manifest-src 'self'; frame-ancestors 'none'\r\n{}\r\n",
        response.status,
        reason,
        response.content_type,
        response.body.len(),
        REST_API_VERSION,
        cookie,
    );
    stream.write_all(header.as_bytes())?;
    stream.write_all(&response.body)?;
    stream.flush()?;
    Ok(())
}

fn route_request(request: &Request, context: &ServerContext) -> Response {
    if !origin_is_allowed(request, context.config.port) {
        return Response::error(403, "invalid_origin", "请求来源不是当前本机网页管理器");
    }
    let raw_path = request.target.split('?').next().unwrap_or("/");
    let (path, path_token) = strip_token_path(raw_path);
    let supplied_token = path_token
        .as_deref()
        .or_else(|| bearer_token(request))
        .or_else(|| cookie_token(request));
    if !supplied_token.is_some_and(|token| constant_time_eq(token, &context.config.token)) {
        return unauthorized_response();
    }
    let mut response = route_authenticated(request, &path, context);
    if path_token.is_some() {
        response.set_cookie = Some(format!(
            "{COOKIE_NAME}={}; Path=/; Max-Age=31536000; HttpOnly; SameSite=Strict",
            context.config.token
        ));
    }
    response
}

fn route_authenticated(request: &Request, path: &str, context: &ServerContext) -> Response {
    let normalized_path = normalize_api_path(path);
    let path = normalized_path.as_ref();
    match (request.method.as_str(), path) {
        ("GET", "/" | "/index.html") => embedded_asset("index.html"),
        ("GET", "/app.js") => embedded_asset("app.js"),
        ("GET", "/style.css") => embedded_asset("style.css"),
        ("GET", "/manifest.webmanifest") => embedded_asset("manifest.webmanifest"),
        ("GET", "/sw.js") => embedded_asset("sw.js"),
        ("GET", "/pwa-icon-192.png") => embedded_asset("pwa-icon-192.png"),
        ("GET", "/pwa-icon-512.png") => embedded_asset("pwa-icon-512.png"),
        ("GET", "/pwa-icon.svg") => embedded_asset("pwa-icon.svg"),
        ("GET", "/api/meta") => api_meta_response(context),
        ("GET", "/api/status") => status_response(context),
        ("GET", "/api/modules") => modules_response(),
        ("GET", "/api/superuser") => superuser_response(),
        ("GET", "/api/reboot") => reboot_status_response(),
        ("GET", "/api/settings") => settings_response(context),
        ("GET", "/api/settings/manager") => manager_settings_response(),
        ("GET", "/api/stealth") => stealth_status_response(),
        ("GET", "/api/dynamic-manager") => dynamic_manager_status_response(),
        ("GET", "/api/features") => features_response(),
        ("GET", "/api/assets") => assets_response(),
        ("GET", "/api/kpm") => kpm_response(),
        ("GET", "/api/pathmask") => pathmask_response(),
        ("GET", "/api/builtin-mount") => builtin_mount_response(),
        ("GET", "/api/kpatch-next") => kpatch_next_response(),
        ("GET", "/api/susfs") => susfs_response(),
        ("POST", "/api/reboot") => reboot_action_response(&request.body),
        ("POST", "/api/settings/auto-start") => auto_start_response(&request.body),
        ("POST", "/api/settings/manager") => manager_settings_action_response(&request.body),
        ("POST", "/api/settings/asset-meta") => asset_meta_response(&request.body),
        ("POST", "/api/features") => feature_action_response(&request.body),
        ("POST", "/api/stealth") => stealth_action_response(&request.body),
        ("POST", "/api/stealth/disable") => stealth_disable_response(&request.body),
        ("POST", "/api/dynamic-manager") => dynamic_manager_action_response(&request.body),
        ("POST", "/api/kpm/policy") => kpm_policy_response(&request.body),
        ("POST", "/api/kpm/import") => kpm_import_response(request),
        ("POST", "/api/pathmask") => pathmask_action_response(&request.body),
        ("POST", "/api/builtin-mount") => builtin_mount_action_response(&request.body),
        ("POST", "/api/kpatch-next") => kpatch_next_action_response(&request.body),
        ("POST", "/api/susfs") => susfs_action_response(&request.body),
        ("POST", "/api/admin/stop") => stop_server_response(),
        _ if request.method == "GET" && path.starts_with("/api/assets/") => {
            asset_file_response(path)
        }
        _ if request.method == "GET" && path.starts_with("/api/apps/icon/") => {
            app_icon_response(path)
        }
        _ if request.method == "POST" && path.starts_with("/api/assets/") => {
            asset_upload_response(path, &request.body)
        }
        _ if request.method == "DELETE" && path.starts_with("/api/assets/") => {
            asset_clear_response(path)
        }
        _ if request.method == "POST" && path.starts_with("/api/modules/") => {
            module_action_response(path)
        }
        _ if request.method == "POST" && path.starts_with("/api/kpm/") => {
            kpm_action_response(path, &request.body)
        }
        _ if request.method == "POST" && path.starts_with("/api/superuser/") => {
            superuser_action_response(path, &request.body)
        }
        _ if request.method == "GET" && path.starts_with("/webui/") => module_webui_response(path),
        _ => Response::error(404, "not_found", "没有这个接口"),
    }
}

fn embedded_asset(name: &str) -> Response {
    let Some(asset) = WebAssets::get(name) else {
        return Response::error(404, "asset_not_found", "网页资源不存在");
    };
    let content_type = match Path::new(name).extension().and_then(|value| value.to_str()) {
        Some("html") => "text/html; charset=utf-8",
        Some("js") => "application/javascript; charset=utf-8",
        Some("css") => "text/css; charset=utf-8",
        Some("webmanifest") => "application/manifest+json; charset=utf-8",
        Some("png") => "image/png",
        Some("svg") => "image/svg+xml",
        _ => "application/octet-stream",
    };
    Response::text(200, content_type, asset.data.into_owned())
}

fn normalize_api_path(path: &str) -> Cow<'_, str> {
    match path.strip_prefix(REST_API_PREFIX) {
        Some("") => Cow::Borrowed("/api/meta"),
        Some(suffix) if suffix.starts_with('/') => Cow::Owned(format!("/api{suffix}")),
        _ => Cow::Borrowed(path),
    }
}

fn api_meta_response(context: &ServerContext) -> Response {
    Response::json(
        200,
        json!({
            "ok": true,
            "service": "ksud-web-manager",
            "apiVersion": REST_API_VERSION,
            "apiBase": REST_API_PREFIX,
            "persistentBackend": true,
            "server": {
                "port": context.config.port,
                "autoStart": persisted_auto_start(context),
            },
            "capabilities": [
                "status",
                "superuser",
                "modules",
                "features",
                "assets",
                "stealth",
                "reboot",
                "pwa",
                "kpm",
                "susfs",
                "pathmask",
                "builtinMount",
                "kpatchNext",
            ],
        }),
    )
}

fn asset_catalog_json() -> Value {
    let wallpaper_targets = WALLPAPER_TARGETS
        .into_iter()
        .map(|(name, label)| (name.to_string(), Value::String(label.to_string())))
        .collect::<serde_json::Map<_, _>>();
    let nav_icon_slots = NAV_ICON_SLOTS
        .into_iter()
        .map(|(name, label)| (name.to_string(), Value::String(label.to_string())))
        .collect::<serde_json::Map<_, _>>();
    json!({
        "wallpaperTargets": wallpaper_targets,
        "navIconSlots": nav_icon_slots,
        "moduleWallpaperKind": "modulewall",
        "maxBytes": MAX_ASSET_BYTES,
    })
}

fn is_valid_asset(kind: &str, name: &str) -> bool {
    match kind {
        "wallpaper" => WALLPAPER_TARGETS.iter().any(|(target, _)| *target == name),
        "navicon" => NAV_ICON_SLOTS.iter().any(|(slot, _)| *slot == name),
        "modulewall" => module::validate_module_id(name).is_ok(),
        _ => false,
    }
}

fn parse_asset_path(path: &str) -> Option<(&str, &str)> {
    let suffix = path.strip_prefix("/api/assets/")?;
    let (kind, name) = suffix.split_once('/')?;
    if kind.is_empty() || name.is_empty() || name.contains('/') || !is_valid_asset(kind, name) {
        return None;
    }
    Some((kind, name))
}

fn asset_path(kind: &str, name: &str) -> Option<PathBuf> {
    is_valid_asset(kind, name)
        .then(|| Path::new(defs::WEB_MANAGER_ASSET_DIR).join(format!("{kind}-{name}.img")))
}

fn ensure_asset_storage() -> Result<()> {
    ensure_storage()?;
    let path = Path::new(defs::WEB_MANAGER_ASSET_DIR);
    fs::create_dir_all(path).with_context(|| format!("create {}", path.display()))?;
    fs::set_permissions(path, fs::Permissions::from_mode(0o700))
        .with_context(|| format!("chmod {}", path.display()))?;
    Ok(())
}

fn detect_image_mime(bytes: &[u8]) -> Option<&'static str> {
    if bytes.starts_with(&[0xff, 0xd8, 0xff]) {
        return Some("image/jpeg");
    }
    if bytes.starts_with(&[0x89, b'P', b'N', b'G', 0x0d, 0x0a, 0x1a, 0x0a]) {
        return Some("image/png");
    }
    if bytes.len() >= 12 && &bytes[..4] == b"RIFF" && &bytes[8..12] == b"WEBP" {
        return Some("image/webp");
    }
    if bytes.starts_with(b"GIF87a") || bytes.starts_with(b"GIF89a") {
        return Some("image/gif");
    }
    None
}

fn default_asset_meta(kind: &str, updated_at: u64) -> Value {
    if kind == "navicon" {
        json!({
            "kind": kind,
            "updatedAt": updated_at,
            "scale": 1.0,
            "offsetX": 0.0,
            "offsetY": 0.0,
        })
    } else {
        json!({
            "kind": kind,
            "updatedAt": updated_at,
            "fit": "cover",
            "scale": 1.0,
            "offsetX": 0.0,
            "offsetY": 0.0,
            "dim": 0.35,
            "blur": 0.0,
        })
    }
}

fn finite_number(value: Option<&Value>, fallback: f64) -> f64 {
    value
        .and_then(Value::as_f64)
        .filter(|number| number.is_finite())
        .unwrap_or(fallback)
}

fn normalize_asset_meta(kind: &str, raw: Option<&Value>, updated_at: u64) -> Value {
    let mut meta = default_asset_meta(kind, updated_at);
    let object = meta
        .as_object_mut()
        .expect("default asset metadata is an object");
    if kind == "navicon" {
        object.insert(
            "scale".to_string(),
            json!(finite_number(raw.and_then(|value| value.get("scale")), 1.0).clamp(0.5, 3.0)),
        );
        object.insert(
            "offsetX".to_string(),
            json!(
                finite_number(raw.and_then(|value| value.get("offsetX")), 0.0).clamp(-12.0, 12.0)
            ),
        );
        object.insert(
            "offsetY".to_string(),
            json!(
                finite_number(raw.and_then(|value| value.get("offsetY")), 0.0).clamp(-12.0, 12.0)
            ),
        );
    } else {
        let fit = raw
            .and_then(|value| value.get("fit"))
            .and_then(Value::as_str)
            .filter(|value| matches!(*value, "cover" | "contain" | "stretch"))
            .unwrap_or("cover");
        object.insert("fit".to_string(), json!(fit));
        for (key, fallback, minimum, maximum) in [
            ("scale", 1.0, 0.5, 3.0),
            ("offsetX", 0.0, -50.0, 50.0),
            ("offsetY", 0.0, -50.0, 50.0),
            ("dim", 0.35, 0.0, 0.85),
            ("blur", 0.0, 0.0, 12.0),
        ] {
            object.insert(
                key.to_string(),
                json!(
                    finite_number(raw.and_then(|value| value.get(key)), fallback)
                        .clamp(minimum, maximum)
                ),
            );
        }
    }
    meta
}

fn read_asset_meta_store() -> Value {
    fs::read(defs::WEB_MANAGER_ASSET_META_PATH)
        .ok()
        .and_then(|bytes| serde_json::from_slice::<Value>(&bytes).ok())
        .filter(Value::is_object)
        .unwrap_or_else(|| json!({}))
}

fn write_asset_meta_store(store: &Value) -> Result<()> {
    ensure_asset_storage()?;
    let bytes = serde_json::to_vec_pretty(store).context("serialize web manager asset metadata")?;
    atomic_write(Path::new(defs::WEB_MANAGER_ASSET_META_PATH), &bytes, 0o600)
}

fn stored_asset_meta<'a>(store: &'a Value, kind: &str, name: &str) -> Option<&'a Value> {
    store.get(kind)?.get(name)
}

fn set_stored_asset_meta(store: &mut Value, kind: &str, name: &str, meta: Value) {
    let root = store
        .as_object_mut()
        .expect("asset metadata store is an object");
    let group = root.entry(kind.to_string()).or_insert_with(|| json!({}));
    if !group.is_object() {
        *group = json!({});
    }
    group
        .as_object_mut()
        .expect("asset metadata group is an object")
        .insert(name.to_string(), meta);
}

fn remove_stored_asset_meta(store: &mut Value, kind: &str, name: &str) {
    if let Some(group) = store.get_mut(kind).and_then(Value::as_object_mut) {
        group.remove(name);
    }
}

fn assets_json() -> Value {
    let store = read_asset_meta_store();
    let collect = |kind: &str, names: &[(&str, &str)]| {
        names
            .iter()
            .filter_map(|(name, _)| {
                let path = asset_path(kind, name)?;
                if !path.is_file() {
                    return None;
                }
                let modified = path
                    .metadata()
                    .ok()
                    .and_then(|metadata| metadata.modified().ok())
                    .and_then(|time| time.duration_since(UNIX_EPOCH).ok())
                    .map_or_else(
                        || unix_timestamp() * 1_000,
                        |duration| duration.as_millis() as u64,
                    );
                let raw = stored_asset_meta(&store, kind, name);
                Some((name.to_string(), normalize_asset_meta(kind, raw, modified)))
            })
            .collect::<serde_json::Map<_, _>>()
    };
    let module_wallpapers = fs::read_dir(defs::WEB_MANAGER_ASSET_DIR)
        .ok()
        .into_iter()
        .flatten()
        .filter_map(Result::ok)
        .filter_map(|entry| {
            let name = entry.file_name().to_string_lossy().into_owned();
            let module_id = name.strip_prefix("modulewall-")?.strip_suffix(".img")?;
            if !is_valid_asset("modulewall", module_id) || !entry.path().is_file() {
                return None;
            }
            let modified = entry
                .metadata()
                .ok()
                .and_then(|metadata| metadata.modified().ok())
                .and_then(|time| time.duration_since(UNIX_EPOCH).ok())
                .map_or_else(
                    || unix_timestamp() * 1_000,
                    |duration| duration.as_millis() as u64,
                );
            let raw = stored_asset_meta(&store, "modulewall", module_id);
            Some((
                module_id.to_string(),
                normalize_asset_meta("modulewall", raw, modified),
            ))
        })
        .collect::<serde_json::Map<_, _>>();
    json!({
        "wallpapers": collect("wallpaper", &WALLPAPER_TARGETS),
        "navIcons": collect("navicon", &NAV_ICON_SLOTS),
        "moduleWallpapers": module_wallpapers,
    })
}

fn assets_response() -> Response {
    Response::json(
        200,
        json!({
            "ok": true,
            "assets": assets_json(),
            "assetCatalog": asset_catalog_json(),
        }),
    )
}

fn asset_file_response(path: &str) -> Response {
    let Some((kind, name)) = parse_asset_path(path) else {
        return Response::error(404, "unknown_asset", "未知的外观资源");
    };
    let Some(file_path) = asset_path(kind, name) else {
        return Response::error(404, "unknown_asset", "未知的外观资源");
    };
    let bytes = match fs::read(&file_path) {
        Ok(bytes) if !bytes.is_empty() && bytes.len() <= MAX_ASSET_BYTES => bytes,
        Ok(_) => return Response::error(500, "asset_unusable", "图片文件不可用，请重新选择"),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            return Response::error(404, "asset_missing", "尚未设置该图片");
        }
        Err(error) => return Response::error(500, "asset_read_failed", error.to_string()),
    };
    let Some(content_type) = detect_image_mime(&bytes) else {
        return Response::error(500, "asset_unusable", "图片格式无法识别，请重新选择");
    };
    Response::text(200, content_type, bytes)
}

fn asset_upload_response(path: &str, body: &[u8]) -> Response {
    let Some((kind, name)) = parse_asset_path(path) else {
        return Response::error(404, "unknown_asset", "未知的外观资源");
    };
    if body.is_empty() {
        return Response::error(400, "empty_upload", "没有收到图片内容");
    }
    if body.len() > MAX_ASSET_BYTES {
        return Response::error(413, "asset_too_large", "图片不能超过 8 MiB");
    }
    if detect_image_mime(body).is_none() {
        return Response::error(
            415,
            "unsupported_image",
            "仅支持 PNG、JPEG、WebP 或 GIF 图片",
        );
    }
    let _guard = WRITE_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let result = (|| -> Result<Value> {
        ensure_asset_storage()?;
        let file_path = asset_path(kind, name).context("invalid asset target")?;
        atomic_write(&file_path, body, 0o600)?;
        let mut store = read_asset_meta_store();
        let updated_at = unix_timestamp() * 1_000;
        let meta = normalize_asset_meta(kind, None, updated_at);
        set_stored_asset_meta(&mut store, kind, name, meta.clone());
        write_asset_meta_store(&store)?;
        Ok(meta)
    })();
    match result {
        Ok(meta) => Response::json(
            200,
            json!({ "ok": true, "kind": kind, "name": name, "bytes": body.len(), "meta": meta }),
        ),
        Err(error) => Response::error(500, "asset_write_failed", error.to_string()),
    }
}

fn asset_clear_response(path: &str) -> Response {
    let Some((kind, name)) = parse_asset_path(path) else {
        return Response::error(404, "unknown_asset", "未知的外观资源");
    };
    let _guard = WRITE_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let result = (|| -> Result<()> {
        if let Some(file_path) = asset_path(kind, name)
            && let Err(error) = fs::remove_file(file_path)
            && error.kind() != std::io::ErrorKind::NotFound
        {
            return Err(error).context("remove web manager asset");
        }
        let mut store = read_asset_meta_store();
        remove_stored_asset_meta(&mut store, kind, name);
        write_asset_meta_store(&store)
    })();
    match result {
        Ok(()) => Response::json(200, json!({ "ok": true, "cleared": true })),
        Err(error) => Response::error(500, "asset_clear_failed", error.to_string()),
    }
}

fn asset_meta_response(body: &[u8]) -> Response {
    let payload = match parse_json_body(body) {
        Ok(value) => value,
        Err(error) => return Response::error(400, "invalid_json", error.to_string()),
    };
    let kind = payload
        .get("kind")
        .and_then(Value::as_str)
        .unwrap_or_default();
    let name = payload
        .get("name")
        .and_then(Value::as_str)
        .unwrap_or_default();
    if !is_valid_asset(kind, name) {
        return Response::error(404, "unknown_asset", "未知的外观资源");
    }
    let Some(file_path) = asset_path(kind, name) else {
        return Response::error(404, "unknown_asset", "未知的外观资源");
    };
    if !file_path.is_file() {
        return Response::error(409, "asset_missing", "请先选择图片再调整");
    }
    let meta = normalize_asset_meta(kind, payload.get("meta"), unix_timestamp() * 1_000);
    let _guard = WRITE_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let result = {
        let mut store = read_asset_meta_store();
        set_stored_asset_meta(&mut store, kind, name, meta.clone());
        write_asset_meta_store(&store)
    };
    match result {
        Ok(()) => Response::json(
            200,
            json!({ "ok": true, "kind": kind, "name": name, "meta": meta }),
        ),
        Err(error) => Response::error(500, "asset_meta_failed", error.to_string()),
    }
}

fn unauthorized_response() -> Response {
    Response::text(
        401,
        "text/html; charset=utf-8",
        "<!doctype html><meta charset=utf-8><title>ApkeSU</title><style>body{font-family:sans-serif;padding:32px;background:#101412;color:#eef4ef}main{max-width:520px;margin:auto}code{color:#7bd99b}</style><main><h1>需要认证</h1><p>请从 ApkeSU 或 <code>ksud web-manager url</code> 打开此页面。</p></main>",
    )
}

fn origin_is_allowed(request: &Request, port: u16) -> bool {
    let Some(origin) = request.headers.get("origin") else {
        return true;
    };
    origin == &format!("http://127.0.0.1:{port}") || origin == &format!("http://localhost:{port}")
}

fn strip_token_path(path: &str) -> (String, Option<String>) {
    let Some(rest) = path.strip_prefix(TOKEN_PATH_PREFIX) else {
        return (path.to_string(), None);
    };
    let (token, suffix) = rest.split_once('/').unwrap_or((rest, ""));
    if token.len() != 64 || !token.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return (path.to_string(), None);
    }
    let route = if suffix.is_empty() {
        "/".to_string()
    } else {
        format!("/{suffix}")
    };
    (route, Some(token.to_ascii_lowercase()))
}

fn bearer_token(request: &Request) -> Option<&str> {
    request
        .headers
        .get("authorization")?
        .strip_prefix("Bearer ")
}

fn cookie_token(request: &Request) -> Option<&str> {
    request.headers.get("cookie")?.split(';').find_map(|part| {
        let (name, value) = part.trim().split_once('=')?;
        (name == COOKIE_NAME).then_some(value)
    })
}

fn constant_time_eq(left: &str, right: &str) -> bool {
    if left.len() != right.len() {
        return false;
    }
    left.bytes()
        .zip(right.bytes())
        .fold(0_u8, |difference, (a, b)| difference | (a ^ b))
        == 0
}

fn status_response(context: &ServerContext) -> Response {
    let info = ksucalls::get_info();
    let mode = if ksucalls::is_late_load() {
        "late-load"
    } else if ksucalls::is_lkm_mode() {
        "lkm"
    } else {
        "gki"
    };
    let kernel_release = rustix::system::uname()
        .release()
        .to_string_lossy()
        .into_owned();
    let kernel_active = info.version > 0;
    Response::json(
        200,
        json!({
            "ok": true,
            "persistent": true,
            "server": {
                "running": true,
                "port": context.config.port,
                "enabled": persisted_auto_start(context),
                "pid": std::process::id(),
            },
            "kernel": {
                "available": kernel_active,
                "version": info.version,
                "uapiVersion": info.uapi_version,
                "mode": mode,
                "release": kernel_release,
                "nativeKpm": ksucalls::is_native_kpm(),
            },
            "userspace": {
                "versionCode": defs::VERSION_CODE.trim(),
                "versionName": defs::VERSION_NAME.trim(),
            },
            "device": {
                "brand": utils::getprop("ro.product.brand").unwrap_or_default(),
                "model": utils::getprop("ro.product.model").unwrap_or_default(),
                "device": utils::getprop("ro.product.device").unwrap_or_default(),
                "android": utils::getprop("ro.build.version.release").unwrap_or_default(),
                "fingerprint": utils::getprop("ro.build.fingerprint").unwrap_or_default(),
            },
            // 主页设备信息卡片用的探测结果（与原生 InfoCard 同源）
            "system": system_block(kernel_active),
        }),
    )
}

/// 主页设备信息卡片的额外字段；探测较慢（会执行 ksud 自身与 ksu_susfs），60 秒缓存。
fn system_block(kernel_active: bool) -> Value {
    {
        let cache = SYSTEM_CACHE
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if let Some((stamp, value)) = cache.as_ref()
            && stamp.elapsed() < SYSTEM_CACHE_TTL
        {
            return value.clone();
        }
    }
    let value = json!({
        "selinux": selinux_status(),
        "seccomp": seccomp_state(),
        "hookType": if kernel_active { "tracepoint" } else { "" },
        "kpm": kpm_summary(),
        "susfs": susfs_summary(),
    });
    let mut cache = SYSTEM_CACHE
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    *cache = Some((Instant::now(), value.clone()));
    value
}

fn selinux_status() -> &'static str {
    let Ok(value) = fs::read_to_string("/sys/fs/selinux/enforce") else {
        return if Path::new("/sys/fs/selinux").exists() {
            "Unknown"
        } else {
            "Disabled"
        };
    };
    selinux_from_enforce(value.trim())
}

fn selinux_from_enforce(value: &str) -> &'static str {
    match value {
        "1" => "Enforcing",
        "0" => "Permissive",
        _ => "Unknown",
    }
}

fn seccomp_state() -> Value {
    match feature::feature_state("seccomp_hook_status") {
        Ok((value, supported, _)) => json!({
            "supported": supported,
            "enabled": supported && value != 0,
        }),
        Err(_) => json!({ "supported": false, "enabled": false }),
    }
}

/// 运行当前 ksud 二进制并解析其 JSON 输出（与 App 端 `ksud kpm …` 同一条路径）。
fn ksud_json(arguments: &[&str]) -> Option<Value> {
    ksud_json_result(arguments).ok()
}

fn ksud_json_result(arguments: &[&str]) -> Result<Value> {
    let program = std::env::current_exe().context("locate current ksud executable")?;
    let output = run_command_output(&program, arguments, COMMAND_TIMEOUT)?;
    serde_json::from_str(output.trim()).context("parse ksud JSON response")
}

fn run_command_output(program: &Path, arguments: &[&str], timeout: Duration) -> Result<String> {
    let mut child = Command::new(program)
        .args(arguments)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .with_context(|| format!("execute {}", program.display()))?;
    let stdout = child.stdout.take().context("capture command stdout")?;
    let stderr = child.stderr.take().context("capture command stderr")?;
    let stdout_reader = std::thread::spawn(move || {
        let mut output = String::new();
        let mut reader = stdout;
        reader.read_to_string(&mut output).map(|_| output)
    });
    let stderr_reader = std::thread::spawn(move || {
        let mut output = String::new();
        let mut reader = stderr;
        reader.read_to_string(&mut output).map(|_| output)
    });
    let deadline = Instant::now() + timeout;
    let status = loop {
        match child.try_wait() {
            Ok(Some(status)) => break status,
            Ok(None) => {
                if Instant::now() >= deadline {
                    let _ = child.kill();
                    let _ = child.wait();
                    let _ = stdout_reader.join();
                    let _ = stderr_reader.join();
                    bail!("{} timed out", program.display());
                }
                std::thread::sleep(Duration::from_millis(40));
            }
            Err(error) => {
                let _ = child.kill();
                let _ = child.wait();
                let _ = stdout_reader.join();
                let _ = stderr_reader.join();
                return Err(error).context("wait for command");
            }
        }
    };
    let output = stdout_reader
        .join()
        .map_err(|_| anyhow::anyhow!("stdout reader panicked"))??;
    let error_output = stderr_reader
        .join()
        .map_err(|_| anyhow::anyhow!("stderr reader panicked"))??;
    ensure!(
        status.success(),
        "{} failed: {}",
        program.display(),
        error_output.trim()
    );
    Ok(output)
}

fn kpm_summary() -> String {
    let caps = ksud_json(&["kpm", "caps"]);
    let loaded = ksud_json(&["kpm", "list"]).and_then(|value| {
        value.as_array().map(|entries| {
            entries
                .iter()
                .filter(|entry| entry.get("loaded").and_then(Value::as_bool) == Some(true))
                .count()
        })
    });
    build_kpm_summary(caps.as_ref(), loaded)
}

fn kpm_flag(caps: &Value, key: &str) -> bool {
    caps.get(key).and_then(Value::as_bool).unwrap_or(false)
}

fn kpm_number(caps: &Value, key: &str) -> i64 {
    caps.get(key).and_then(Value::as_i64).unwrap_or(0)
}

fn build_kpm_summary(caps: Option<&Value>, loaded: Option<usize>) -> String {
    let Some(caps) = caps.filter(|value| value.is_object()) else {
        return String::new();
    };
    let supported = kpm_flag(caps, "supported");
    let loader_ready = kpm_flag(caps, "loaderReady") || supported;
    let kernel_supported = kpm_flag(caps, "kernelSupported") || supported;
    if !(supported || loader_ready || kernel_supported || loaded.is_some()) {
        return String::new();
    }
    let mut parts = Vec::with_capacity(5);
    parts.push(
        if supported {
            "已启用"
        } else if loader_ready {
            "已就绪"
        } else {
            "内核支持"
        }
        .to_string(),
    );
    if let Some(count) = loaded {
        parts.push(format!("已加载 {count} 个"));
    }
    let max_loaded = kpm_number(caps, "maxLoaded");
    if max_loaded > 0 {
        parts.push(format!("上限 {max_loaded}"));
    }
    let abi_version = kpm_number(caps, "abiVersion");
    if abi_version > 0 {
        parts.push(format!("ABI v{abi_version}"));
    }
    if caps.get("policyEnabled").and_then(Value::as_bool) == Some(false) {
        parts.push("策略已关闭".to_string());
    }
    if kpm_flag(caps, "lateLoad") {
        parts.push("晚加载模式".to_string());
    }
    parts.join(" · ")
}

const SUSFS_PROBE_SCRIPT: &str = r#"tool=''
for candidate in /data/adb/ksu/bin/ksu_susfs /data/adb/ap/bin/ksu_susfs /system/bin/ksu_susfs; do
  [ -f "$candidate" ] && [ -x "$candidate" ] || continue
  tool="$candidate"
  break
done
if [ -z "$tool" ]; then tool=$(command -v ksu_susfs 2>/dev/null); fi
printf '__TOOL__=%s\n' "$tool"
version=''
features=0
if [ -n "$tool" ]; then
  version=$("$tool" show version 2>/dev/null | sed -n '1p')
  feature_output=$("$tool" show enabled_features 2>/dev/null)
  if [ -n "$feature_output" ]; then features=$(printf '%s\n' "$feature_output" | grep -c .); fi
fi
printf '__VERSION__=%s\n' "$version"
printf '__FEATURES__=%s\n' "$features"
printf '__MOUNTS__=%s\n' "$(grep -ci susfs /proc/mounts 2>/dev/null)"
config_dir=/data/adb/ksu/susfs
resolved=$(readlink -f /data/adb/ksu/susfs/current 2>/dev/null || true)
case "$resolved" in /data/adb/ksu/susfs/generations/*) config_dir="$resolved" ;; esac
hidden=0
if [ -f "$config_dir/paths.txt" ]; then hidden=$(grep -c . "$config_dir/paths.txt" 2>/dev/null || true); fi
printf '__HIDDEN__=%s\n' "$hidden"
"#;

fn susfs_summary() -> String {
    let Ok(output) = run_command_output(
        Path::new("/system/bin/sh"),
        &["-c", SUSFS_PROBE_SCRIPT],
        COMMAND_TIMEOUT,
    ) else {
        return String::new();
    };
    let field = |name: &str| {
        output
            .lines()
            .find_map(|line| line.strip_prefix(name))
            .map(str::trim)
            .unwrap_or_default()
            .to_string()
    };
    build_susfs_summary(
        &field("__TOOL__="),
        &field("__VERSION__="),
        field("__FEATURES__=").parse().unwrap_or(0),
        field("__MOUNTS__=").parse().unwrap_or(0),
        field("__HIDDEN__=").parse().unwrap_or(0),
    )
}

fn build_susfs_summary(
    tool_path: &str,
    version: &str,
    feature_count: u64,
    mount_count: u64,
    hidden_path_count: u64,
) -> String {
    if version.is_empty() && mount_count == 0 {
        return String::new();
    }
    let mut parts = Vec::with_capacity(5);
    parts.push(if version.is_empty() {
        "已检测到挂载".to_string()
    } else {
        version.to_string()
    });
    if feature_count > 0 {
        parts.push(format!("特性 {feature_count} 项"));
    }
    if mount_count > 0 {
        parts.push(format!("挂载 {mount_count} 项"));
    }
    if hidden_path_count > 0 {
        parts.push(format!("隐藏路径 {hidden_path_count} 条"));
    }
    if tool_path.is_empty() {
        parts.push("未找到 ksu_susfs".to_string());
    }
    parts.join(" · ")
}

fn modules_response() -> Response {
    let mut modules = module::get_modules();
    modules.sort_by(|left, right| {
        left.get("name")
            .or_else(|| left.get("id"))
            .cmp(&right.get("name").or_else(|| right.get("id")))
    });
    Response::json(200, json!({ "ok": true, "modules": modules }))
}

fn parse_action_path<'a>(path: &'a str, prefix: &str) -> Option<(&'a str, &'a str)> {
    let suffix = path.strip_prefix(prefix)?;
    let (identifier, action) = suffix.split_once('/')?;
    if identifier.is_empty() || action.is_empty() || action.contains('/') {
        return None;
    }
    Some((identifier, action))
}

fn percent_decode(value: &str) -> Option<String> {
    let mut output = Vec::with_capacity(value.len());
    let bytes = value.as_bytes();
    let mut index = 0;
    while index < bytes.len() {
        if bytes[index] == b'%' {
            let high = *bytes.get(index + 1)?;
            let low = *bytes.get(index + 2)?;
            output.push((hex_value(high)? << 4) | hex_value(low)?);
            index += 3;
        } else {
            output.push(bytes[index]);
            index += 1;
        }
    }
    String::from_utf8(output).ok()
}

const fn hex_value(value: u8) -> Option<u8> {
    match value {
        b'0'..=b'9' => Some(value - b'0'),
        b'a'..=b'f' => Some(value - b'a' + 10),
        b'A'..=b'F' => Some(value - b'A' + 10),
        _ => None,
    }
}

fn module_action_response(path: &str) -> Response {
    let Some((encoded_id, action)) = parse_action_path(path, "/api/modules/") else {
        return Response::error(404, "invalid_module_action", "模块操作地址无效");
    };
    let Some(module_id) = percent_decode(encoded_id) else {
        return Response::error(400, "invalid_module_id", "模块 ID 编码无效");
    };
    if let Err(error) = module::validate_module_id(&module_id) {
        return Response::error(400, "invalid_module_id", error.to_string());
    }
    let _guard = WRITE_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let result = match action {
        "enable" => module::enable_module(&module_id),
        "disable" => module::disable_module(&module_id),
        "uninstall" => module::uninstall_module(&module_id),
        "undo-uninstall" => module::undo_uninstall_module(&module_id),
        "action" => module::run_action(&module_id),
        _ => return Response::error(404, "unknown_module_action", "不支持这个模块操作"),
    };
    match result {
        Ok(()) => Response::json(
            200,
            json!({ "ok": true, "moduleId": module_id, "action": action }),
        ),
        Err(error) => Response::error(409, "module_action_failed", error.to_string()),
    }
}

fn clear_system_cache() {
    let mut cache = SYSTEM_CACHE
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    *cache = None;
}

fn ensure_lkm_management(feature_name: &str) -> Result<()> {
    ensure!(ksucalls::is_lkm_mode(), "{feature_name} 仅支持 LKM 模式");
    ensure!(
        !ksucalls::is_late_load(),
        "{feature_name} 不支持 late-load 模式"
    );
    Ok(())
}

fn ensure_persistent_management(feature_name: &str) -> Result<()> {
    ensure!(
        !ksucalls::is_late_load(),
        "{feature_name} 不支持 late-load 模式"
    );
    Ok(())
}

fn kpm_response() -> Response {
    let caps = match ksud_json_result(&["kpm", "caps"]) {
        Ok(value) => value,
        Err(error) => return Response::error(409, "kpm_unavailable", format!("{error:#}")),
    };
    let (modules, list_error) = match ksud_json_result(&["kpm", "list"]) {
        Ok(Value::Array(values)) => (values, Value::Null),
        Ok(_) => (Vec::new(), Value::String("KPM 列表格式无效".to_string())),
        Err(error) => (Vec::new(), Value::String(format!("{error:#}"))),
    };
    Response::json(
        200,
        json!({
            "ok": true,
            "caps": caps,
            "modules": modules,
            "listError": list_error,
        }),
    )
}

fn kpm_policy_response(body: &[u8]) -> Response {
    let payload = match parse_json_body(body) {
        Ok(value) => value,
        Err(error) => return Response::error(400, "invalid_json", error.to_string()),
    };
    let Some(enabled) = payload.get("enabled").and_then(Value::as_bool) else {
        return Response::error(400, "invalid_kpm_policy", "缺少 enabled 布尔值");
    };
    let _guard = WRITE_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    match kpm::set_policy(enabled) {
        Ok(()) => {
            clear_system_cache();
            Response::json(200, json!({ "ok": true, "enabled": enabled }))
        }
        Err(error) => Response::error(409, "kpm_policy_failed", format!("{error:#}")),
    }
}

fn request_header_bool(request: &Request, name: &str) -> bool {
    request.headers.get(name).is_some_and(|value| {
        matches!(
            value.trim().to_ascii_lowercase().as_str(),
            "1" | "true" | "yes" | "on"
        )
    })
}

fn kpm_import_response(request: &Request) -> Response {
    if request.body.is_empty() {
        return Response::error(400, "empty_kpm", "请选择要导入的 KPM 文件");
    }
    if request.body.len() > MAX_KPM_BYTES {
        return Response::error(413, "kpm_too_large", "KPM 文件不能超过 4 MiB");
    }
    let args = request
        .headers
        .get("x-apkesu-kpm-args")
        .map(String::as_str)
        .unwrap_or_default();
    if args.len() > 4096 || args.chars().any(char::is_control) {
        return Response::error(400, "invalid_kpm_args", "KPM 参数无效或过长");
    }
    let force = request_header_bool(request, "x-apkesu-kpm-force");
    let enable = request_header_bool(request, "x-apkesu-kpm-enable");
    let path = Path::new(defs::WEB_MANAGER_DIR).join(format!(
        ".kpm-upload-{}-{}.bin",
        std::process::id(),
        unix_timestamp()
    ));
    let _guard = WRITE_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let result = (|| -> Result<()> {
        ensure_storage()?;
        atomic_write(&path, &request.body, 0o600)?;
        kpm::import(&path, args, true, force, enable)
    })();
    let cleanup = fs::remove_file(&path);
    if let Err(error) = cleanup
        && error.kind() != std::io::ErrorKind::NotFound
    {
        warn!("failed to remove temporary KPM upload: {error}");
    }
    match result {
        Ok(()) => {
            clear_system_cache();
            Response::json(200, json!({ "ok": true, "enabled": enable }))
        }
        Err(error) => Response::error(409, "kpm_import_failed", format!("{error:#}")),
    }
}

fn kpm_action_response(path: &str, body: &[u8]) -> Response {
    let Some((encoded_id, action)) = parse_action_path(path, "/api/kpm/") else {
        return Response::error(404, "invalid_kpm_action", "KPM 操作地址无效");
    };
    let Some(module_id) = percent_decode(encoded_id) else {
        return Response::error(400, "invalid_kpm_id", "KPM ID 编码无效");
    };
    let control_args = if action == "control" {
        let payload = match parse_json_body(body) {
            Ok(value) => value,
            Err(error) => return Response::error(400, "invalid_json", error.to_string()),
        };
        let value = payload
            .get("args")
            .and_then(Value::as_str)
            .unwrap_or_default();
        if value.len() > 4096 || value.chars().any(char::is_control) {
            return Response::error(400, "invalid_kpm_args", "KPM 控制参数无效或过长");
        }
        value.to_string()
    } else {
        String::new()
    };
    let _guard = WRITE_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let result = match action {
        "enable" => kpm::enable_module(&module_id),
        "disable" => kpm::disable_module(&module_id),
        "load" => kpm::load_module(&module_id),
        "unload" => kpm::unload_module(&module_id),
        "remove" => kpm::remove_module(&module_id),
        "control" => kpm::control_module(&module_id, &control_args),
        _ => return Response::error(404, "unknown_kpm_action", "不支持这个 KPM 操作"),
    };
    match result {
        Ok(()) => {
            clear_system_cache();
            Response::json(
                200,
                json!({ "ok": true, "moduleId": module_id, "action": action }),
            )
        }
        Err(error) => Response::error(409, "kpm_action_failed", format!("{error:#}")),
    }
}

fn pathmask_response() -> Response {
    if let Err(error) = ensure_lkm_management("PathMask") {
        return Response::error(409, "pathmask_unavailable", error.to_string());
    }
    match ksud_json_result(&["pathmask", "status"]) {
        Ok(status) => Response::json(200, json!({ "ok": true, "status": status })),
        Err(error) => Response::error(409, "pathmask_status_failed", format!("{error:#}")),
    }
}

fn pathmask_action_response(body: &[u8]) -> Response {
    if let Err(error) = ensure_lkm_management("PathMask") {
        return Response::error(409, "pathmask_unavailable", error.to_string());
    }
    let payload = match parse_json_body(body) {
        Ok(value) => value,
        Err(error) => return Response::error(400, "invalid_json", error.to_string()),
    };
    let action = payload
        .get("action")
        .and_then(Value::as_str)
        .unwrap_or("apply");
    let _guard = WRITE_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let result = match action {
        "apply" => serde_json::to_string(payload.get("config").unwrap_or(&payload))
            .context("serialize PathMask config")
            .and_then(|value| pathmask::apply_config_text(&value)),
        "unload" => pathmask::unload(),
        "delete" => pathmask::delete_config(),
        _ => return Response::error(400, "unknown_pathmask_action", "不支持这个 PathMask 操作"),
    };
    match result {
        Ok(()) => Response::json(200, json!({ "ok": true, "action": action })),
        Err(error) => Response::error(409, "pathmask_action_failed", format!("{error:#}")),
    }
}

fn builtin_mount_response() -> Response {
    if let Err(error) = ensure_persistent_management("内置挂载") {
        return Response::error(409, "builtin_mount_unavailable", error.to_string());
    }
    match ksud_json_result(&["builtin-mount", "status"]) {
        Ok(status) => Response::json(200, json!({ "ok": true, "status": status })),
        Err(error) => Response::error(409, "builtin_mount_status_failed", format!("{error:#}")),
    }
}

fn builtin_mount_action_response(body: &[u8]) -> Response {
    if let Err(error) = ensure_persistent_management("内置挂载") {
        return Response::error(409, "builtin_mount_unavailable", error.to_string());
    }
    let payload = match parse_json_body(body) {
        Ok(value) => value,
        Err(error) => return Response::error(400, "invalid_json", error.to_string()),
    };
    let action = payload
        .get("action")
        .and_then(Value::as_str)
        .unwrap_or_default();
    let value = payload
        .get("value")
        .and_then(Value::as_str)
        .unwrap_or_default();
    let _guard = WRITE_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let result = match action {
        "enable" => builtin_mount::enable(),
        "disable" => builtin_mount::disable(),
        "mode" => builtin_mount::MountMode::parse(value).and_then(builtin_mount::set_default_mode),
        "variant" => {
            builtin_mount::BuiltinMountVariant::parse(value).and_then(builtin_mount::set_variant)
        }
        _ => {
            return Response::error(
                400,
                "unknown_builtin_mount_action",
                "不支持这个内置挂载操作",
            );
        }
    };
    match result {
        Ok(()) => Response::json(200, json!({ "ok": true, "action": action })),
        Err(error) => Response::error(409, "builtin_mount_action_failed", format!("{error:#}")),
    }
}

fn kpatch_next_response() -> Response {
    if let Err(error) = ensure_lkm_management("KPatch-Next") {
        return Response::error(409, "kpatch_next_unavailable", error.to_string());
    }
    match ksud_json_result(&["kpatch-next", "status"]) {
        Ok(status) => Response::json(200, json!({ "ok": true, "status": status })),
        Err(error) => Response::error(409, "kpatch_next_status_failed", format!("{error:#}")),
    }
}

fn kpatch_next_action_response(body: &[u8]) -> Response {
    if let Err(error) = ensure_lkm_management("KPatch-Next") {
        return Response::error(409, "kpatch_next_unavailable", error.to_string());
    }
    let payload = match parse_json_body(body) {
        Ok(value) => value,
        Err(error) => return Response::error(400, "invalid_json", error.to_string()),
    };
    let action = payload
        .get("action")
        .and_then(Value::as_str)
        .unwrap_or_default();
    let _guard = WRITE_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let result = match action {
        "enable" => kpatch_next::enable(),
        "disable" => kpatch_next::disable(),
        _ => {
            return Response::error(
                400,
                "unknown_kpatch_next_action",
                "不支持这个 KPatch-Next 操作",
            );
        }
    };
    match result {
        Ok(()) => Response::json(200, json!({ "ok": true, "action": action })),
        Err(error) => Response::error(409, "kpatch_next_action_failed", format!("{error:#}")),
    }
}

fn susfs_response() -> Response {
    match web_manager_susfs::status() {
        Ok(status) => Response::json(200, status),
        Err(error) => Response::error(409, "susfs_unavailable", format!("{error:#}")),
    }
}

fn susfs_action_response(body: &[u8]) -> Response {
    let payload = match parse_json_body(body) {
        Ok(value) => value,
        Err(error) => return Response::error(400, "invalid_json", error.to_string()),
    };
    let _guard = WRITE_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    match web_manager_susfs::apply(&payload) {
        Ok(status) => {
            clear_system_cache();
            Response::json(200, status)
        }
        Err(error) => Response::error(409, "susfs_apply_failed", format!("{error:#}")),
    }
}

fn is_valid_package_name(package_name: &str) -> bool {
    !package_name.is_empty()
        && package_name.len() <= 255
        && package_name
            .bytes()
            .all(|value| value.is_ascii_alphanumeric() || matches!(value, b'.' | b'_'))
}

fn read_packages() -> Result<Vec<(String, u32)>> {
    let content = fs::read_to_string(PACKAGES_LIST_PATH).context("read Android package list")?;
    let mut packages = Vec::new();
    for line in content.lines() {
        let mut fields = line.split_whitespace();
        let Some(package_name) = fields.next() else {
            continue;
        };
        let Some(uid) = fields.next().and_then(|value| value.parse::<u32>().ok()) else {
            continue;
        };
        if is_valid_package_name(package_name) {
            packages.push((package_name.to_string(), uid));
        }
    }
    packages.sort_by(|left, right| left.0.cmp(&right.0));
    Ok(packages)
}

fn cached_app_labels() -> HashMap<String, String> {
    let Ok(metadata) = fs::metadata(APP_METADATA_PATH) else {
        return HashMap::new();
    };
    if !metadata.is_file() || metadata.len() > MAX_APP_METADATA_BYTES {
        return HashMap::new();
    }
    let Ok(content) = fs::read_to_string(APP_METADATA_PATH) else {
        return HashMap::new();
    };
    let Ok(payload) = serde_json::from_str::<Value>(&content) else {
        return HashMap::new();
    };
    payload
        .get("apps")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(|record| {
            let package_name = record.get("packageName")?.as_str()?;
            let label = record.get("label")?.as_str()?.trim();
            if !is_valid_package_name(package_name)
                || label.is_empty()
                || label.len() > 512
                || label.chars().any(char::is_control)
            {
                return None;
            }
            Some((package_name.to_string(), label.to_string()))
        })
        .collect()
}

fn app_icon_path(package_name: &str) -> PathBuf {
    Path::new(APP_ICON_DIR).join(format!("{package_name}.png"))
}

fn parse_app_icon_path(path: &str) -> Option<String> {
    let encoded = path.strip_prefix("/api/apps/icon/")?.strip_suffix(".png")?;
    let package_name = percent_decode(encoded)?;
    is_valid_package_name(&package_name).then_some(package_name)
}

fn app_icon_response(path: &str) -> Response {
    let Some(package_name) = parse_app_icon_path(path) else {
        return Response::error(404, "app_icon_not_found", "应用图标不存在");
    };
    let icon = app_icon_path(&package_name);
    let Ok(metadata) = fs::symlink_metadata(&icon) else {
        return Response::error(404, "app_icon_not_found", "应用图标不存在");
    };
    if !metadata.file_type().is_file() || metadata.len() > MAX_APP_ICON_BYTES {
        return Response::error(404, "app_icon_not_found", "应用图标不存在");
    }
    match fs::read(icon) {
        Ok(bytes) => Response::text(200, "image/png", bytes),
        Err(error) => Response::error(500, "app_icon_read_failed", error.to_string()),
    }
}

fn superuser_response() -> Response {
    let allowlist = match ksucalls::get_allow_list() {
        Ok(value) => value.into_iter().collect::<HashSet<_>>(),
        Err(error) => return Response::error(500, "allowlist_failed", error.to_string()),
    };
    match read_packages() {
        Ok(packages) => {
            let labels = cached_app_labels();
            let records = packages
                .into_iter()
                .filter(|(_, uid)| *uid >= 10_000)
                .map(|(package_name, uid)| {
                    let label = labels
                        .get(&package_name)
                        .cloned()
                        .unwrap_or_else(|| package_name.clone());
                    let icon_available = app_icon_path(&package_name).is_file();
                    json!({
                        "packageName": package_name,
                        "label": label,
                        "uid": uid,
                        "granted": allowlist.contains(&uid),
                        "iconAvailable": icon_available,
                    })
                })
                .collect::<Vec<_>>();
            Response::json(
                200,
                json!({
                    "ok": true,
                    "grantedCount": allowlist.len(),
                    "apps": records,
                }),
            )
        }
        Err(error) => Response::error(500, "package_list_failed", error.to_string()),
    }
}

fn parse_json_body(body: &[u8]) -> Result<Value> {
    ensure!(!body.is_empty(), "request body is empty");
    serde_json::from_slice(body).context("parse request JSON")
}

fn superuser_action_response(path: &str, body: &[u8]) -> Response {
    let Some((uid_text, action)) = parse_action_path(path, "/api/superuser/") else {
        return Response::error(404, "invalid_superuser_action", "超级用户操作地址无效");
    };
    let Ok(uid) = uid_text.parse::<u32>() else {
        return Response::error(400, "invalid_uid", "UID 无效");
    };
    if uid < 10_000 {
        return Response::error(400, "invalid_uid", "不能修改系统 UID");
    }
    let allow = match action {
        "grant" => true,
        "revoke" => false,
        _ => return Response::error(404, "unknown_superuser_action", "不支持这个授权操作"),
    };
    let payload = match parse_json_body(body) {
        Ok(value) => value,
        Err(error) => return Response::error(400, "invalid_json", error.to_string()),
    };
    let Some(package_name) = payload.get("packageName").and_then(Value::as_str) else {
        return Response::error(400, "package_required", "缺少应用包名");
    };
    let packages = match read_packages() {
        Ok(value) => value,
        Err(error) => return Response::error(500, "package_list_failed", error.to_string()),
    };
    if !packages
        .iter()
        .any(|(package, package_uid)| package == package_name && *package_uid == uid)
    {
        return Response::error(409, "package_uid_mismatch", "应用包名与 UID 不匹配");
    }
    let _guard = WRITE_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    match ksucalls::set_root_access(package_name, uid, allow) {
        Ok(()) => match ksucalls::get_allow_list() {
            Ok(allowlist) if allowlist.contains(&uid) == allow => Response::json(
                200,
                json!({ "ok": true, "packageName": package_name, "uid": uid, "granted": allow }),
            ),
            Ok(_) => Response::error(
                409,
                "profile_verification_failed",
                "内核未保存新的 Root 授权状态",
            ),
            Err(error) => Response::error(
                409,
                "profile_verification_failed",
                format!("Root 授权已写入但无法校验：{error}"),
            ),
        },
        Err(error) => Response::error(409, "profile_update_failed", error.to_string()),
    }
}

fn settings_response(context: &ServerContext) -> Response {
    let (manager, manager_settings_error) = match read_manager_app_settings() {
        Ok(settings) => (settings, None),
        Err(error) => (default_manager_app_settings(), Some(error.to_string())),
    };
    Response::json(
        200,
        json!({
            "ok": true,
            "persistent": true,
            "enabled": persisted_auto_start(context),
            "port": context.config.port,
            "bindAddress": "127.0.0.1",
            "authentication": "token-cookie",
            "configPath": defs::WEB_MANAGER_CONFIG_PATH,
            "assets": assets_json(),
            "assetCatalog": asset_catalog_json(),
            "stealth": stealth_status_json(),
            "manager": manager,
            "managerSettingsError": manager_settings_error,
        }),
    )
}

fn default_manager_app_settings() -> Value {
    json!({
        "schemaVersion": MANAGER_SETTINGS_SCHEMA_VERSION,
        "language": "zh-CN",
        "checkModuleUpdate": true,
        "showVersionMismatchWarning": true,
        "showGkiWarning": true,
        "showHomeSupportCard": true,
        "showHomeLearnCard": true,
        "customHomeTitle": "",
    })
}

fn normalize_manager_app_settings(value: &Value) -> Result<Value> {
    let object = value
        .as_object()
        .context("manager settings must be an object")?;
    let allowed: HashSet<&str> = [
        "schemaVersion",
        "language",
        "checkModuleUpdate",
        "showVersionMismatchWarning",
        "showGkiWarning",
        "showHomeSupportCard",
        "showHomeLearnCard",
        "customHomeTitle",
    ]
    .into_iter()
    .collect();
    ensure!(
        object.keys().all(|key| allowed.contains(key.as_str())),
        "manager settings contain an unknown field"
    );
    ensure!(
        object.get("schemaVersion").and_then(Value::as_u64)
            == Some(u64::from(MANAGER_SETTINGS_SCHEMA_VERSION)),
        "unsupported manager settings schema"
    );
    let language = object
        .get("language")
        .and_then(Value::as_str)
        .context("manager language is missing")?;
    ensure!(
        SUPPORTED_MANAGER_LANGUAGES.contains(&language),
        "unsupported manager language"
    );
    let boolean = |key: &str| {
        object
            .get(key)
            .and_then(Value::as_bool)
            .with_context(|| format!("{key} must be a boolean"))
    };
    let custom_home_title = object
        .get("customHomeTitle")
        .and_then(Value::as_str)
        .context("customHomeTitle must be a string")?
        .trim();
    ensure!(
        custom_home_title.chars().count() <= MAX_CUSTOM_HOME_TITLE_CHARS,
        "customHomeTitle is too long"
    );
    Ok(json!({
        "schemaVersion": MANAGER_SETTINGS_SCHEMA_VERSION,
        "language": language,
        "checkModuleUpdate": boolean("checkModuleUpdate")?,
        "showVersionMismatchWarning": boolean("showVersionMismatchWarning")?,
        "showGkiWarning": boolean("showGkiWarning")?,
        "showHomeSupportCard": boolean("showHomeSupportCard")?,
        "showHomeLearnCard": boolean("showHomeLearnCard")?,
        "customHomeTitle": custom_home_title,
    }))
}

fn read_manager_app_settings() -> Result<Value> {
    let path = Path::new(defs::WEB_MANAGER_APP_SETTINGS_PATH);
    if !path.is_file() {
        return Ok(default_manager_app_settings());
    }
    let bytes = fs::read(path).with_context(|| format!("read {}", path.display()))?;
    let value: Value = serde_json::from_slice(&bytes).context("parse manager settings")?;
    normalize_manager_app_settings(&value)
}

fn write_manager_app_settings(settings: &Value) -> Result<()> {
    ensure_storage()?;
    let normalized = normalize_manager_app_settings(settings)?;
    let mut bytes = serde_json::to_vec_pretty(&normalized)?;
    bytes.push(b'\n');
    atomic_write(
        Path::new(defs::WEB_MANAGER_APP_SETTINGS_PATH),
        &bytes,
        0o600,
    )
}

fn manager_settings_response() -> Response {
    match read_manager_app_settings() {
        Ok(manager) => Response::json(200, json!({ "ok": true, "manager": manager })),
        Err(error) => Response::error(500, "manager_settings_read_failed", error.to_string()),
    }
}

fn manager_settings_action_response(body: &[u8]) -> Response {
    let payload = match parse_json_body(body) {
        Ok(value) => value,
        Err(error) => return Response::error(400, "invalid_json", error.to_string()),
    };
    let Some(update) = payload.as_object() else {
        return Response::error(400, "invalid_manager_settings", "软件管理器设置必须是对象");
    };
    if update.is_empty() {
        return Response::error(400, "empty_manager_settings", "没有要更新的软件管理器设置");
    }
    let allowed: HashSet<&str> = [
        "language",
        "checkModuleUpdate",
        "showVersionMismatchWarning",
        "showGkiWarning",
        "showHomeSupportCard",
        "showHomeLearnCard",
        "customHomeTitle",
    ]
    .into_iter()
    .collect();
    if !update.keys().all(|key| allowed.contains(key.as_str())) {
        return Response::error(400, "unknown_manager_setting", "包含不支持的软件管理器设置");
    }
    let mut current = match read_manager_app_settings() {
        Ok(value) => value,
        Err(error) => {
            return Response::error(500, "manager_settings_read_failed", error.to_string());
        }
    };
    let Some(current_object) = current.as_object_mut() else {
        return Response::error(500, "manager_settings_invalid", "软件管理器设置损坏");
    };
    for (key, value) in update {
        current_object.insert(key.clone(), value.clone());
    }
    let normalized = match normalize_manager_app_settings(&current) {
        Ok(value) => value,
        Err(error) => {
            return Response::error(400, "invalid_manager_setting", error.to_string());
        }
    };
    let _guard = WRITE_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    if let Err(error) = write_manager_app_settings(&normalized) {
        return Response::error(500, "manager_settings_write_failed", error.to_string());
    }
    broadcast_manager_settings_changed();
    Response::json(200, json!({ "ok": true, "manager": normalized }))
}

fn persisted_auto_start(context: &ServerContext) -> bool {
    read_config().map_or(context.config.enabled, |config| config.enabled)
}

fn features_response() -> Response {
    let features = FEATURE_TOGGLES
        .iter()
        .map(|(key, feature_name, label, summary, reboot_hint)| {
            let state = feature::feature_state(feature_name);
            let (value, supported, managed, error) = match state {
                Ok((value, supported, managed)) => (value, supported, managed, None),
                Err(error) => (0, false, false, Some(error.to_string())),
            };
            let status = if managed {
                "managed"
            } else if supported {
                "supported"
            } else {
                "unsupported"
            };
            json!({
                "key": key,
                "label": label,
                "summary": summary,
                "status": status,
                "supported": supported && !managed,
                "managed": managed,
                "enabled": value != 0,
                "value": value,
                "rebootHint": reboot_hint,
                "error": error,
            })
        })
        .collect::<Vec<_>>();
    let diagnostic = |name: &str| {
        feature::feature_state(name)
            .ok()
            .and_then(|(value, supported, _)| supported.then_some(value))
    };
    Response::json(
        200,
        json!({
            "ok": true,
            "features": features,
            "seccomp": {
                "status": diagnostic("seccomp_hook_status"),
                "lastError": diagnostic("seccomp_hook_last_error"),
                "callCount": diagnostic("seccomp_hook_call_count"),
                "releaseCount": diagnostic("seccomp_hook_release_count"),
                "failureCount": diagnostic("seccomp_hook_failure_count"),
            },
        }),
    )
}

fn feature_action_response(body: &[u8]) -> Response {
    let payload = match parse_json_body(body) {
        Ok(value) => value,
        Err(error) => return Response::error(400, "invalid_json", error.to_string()),
    };
    let key = payload
        .get("key")
        .and_then(Value::as_str)
        .unwrap_or_default();
    let Some((_, feature_name, label, _, reboot_hint)) = FEATURE_TOGGLES
        .iter()
        .find(|(candidate, _, _, _, _)| *candidate == key)
    else {
        return Response::error(404, "unknown_feature", "没有这个功能开关");
    };
    let Some(enabled) = payload.get("enabled").and_then(Value::as_bool) else {
        return Response::error(400, "enabled_required", "缺少 enabled 布尔值");
    };
    let state = match feature::feature_state(feature_name) {
        Ok(state) => state,
        Err(error) => return Response::error(500, "feature_query_failed", error.to_string()),
    };
    if state.2 {
        return Response::error(409, "feature_managed", format!("{label} 当前由模块管理"));
    }
    if !state.1 {
        return Response::error(409, "feature_unsupported", format!("内核不支持{label}"));
    }
    let _guard = WRITE_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    if let Err(error) = feature::set_feature_persisted(feature_name, u64::from(enabled)) {
        return Response::error(500, "feature_apply_failed", error.to_string());
    }
    if *feature_name == "adb_root"
        && let Err(error) = Command::new("/system/bin/setprop")
            .args(["ctl.restart", "adbd"])
            .status()
    {
        warn!("restart adbd after web feature update failed: {error}");
    }
    Response::json(
        200,
        json!({
            "ok": true,
            "key": key,
            "enabled": enabled,
            "rebootHint": reboot_hint,
        }),
    )
}

fn dynamic_manager_status_response() -> Response {
    Response::json(
        200,
        json!({ "ok": true, "status": dynamic_manager::status() }),
    )
}

fn dynamic_manager_action_response(body: &[u8]) -> Response {
    let payload = match parse_json_body(body) {
        Ok(value) => value,
        Err(error) => return Response::error(400, "invalid_json", error.to_string()),
    };
    let action = payload
        .get("action")
        .and_then(Value::as_str)
        .unwrap_or_default();
    let _guard = WRITE_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let result = match action {
        "clear" => dynamic_manager::clear(),
        "set" => {
            let Some(size) = payload
                .get("certificateSize")
                .and_then(Value::as_u64)
                .and_then(|value| u32::try_from(value).ok())
            else {
                return Response::error(400, "invalid_certificate_size", "证书大小必须是 256-4096");
            };
            let Some(hash) = payload.get("certificateSha256").and_then(Value::as_str) else {
                return Response::error(400, "invalid_certificate_hash", "缺少证书 SHA-256");
            };
            match dynamic_manager::parse_hash(hash) {
                Ok(hash) => dynamic_manager::set(size, hash),
                Err(error) => return Response::error(400, "invalid_certificate_hash", error),
            }
        }
        _ => return Response::error(400, "unknown_dynamic_manager_action", "不支持这个操作"),
    };
    if let Err(error) = result {
        return Response::error(409, "dynamic_manager_update_failed", error.to_string());
    }
    Response::json(
        200,
        json!({ "ok": true, "status": dynamic_manager::status() }),
    )
}

fn stealth_status_json() -> Value {
    let stored_code = fs::read_to_string(STEALTH_CODE_PATH)
        .ok()
        .and_then(|content| content.lines().next().and_then(normalize_stealth_code));
    json!({
        "enabled": Path::new(STEALTH_MODE_PATH).is_file(),
        "codeBackedUp": stored_code.is_some(),
        "code": stored_code.unwrap_or_else(|| DEFAULT_STEALTH_CODE.to_string()),
    })
}

fn stealth_status_response() -> Response {
    let status = stealth_status_json();
    Response::json(
        200,
        json!({
            "ok": true,
            "enabled": status["enabled"],
            "codeBackedUp": status["codeBackedUp"],
            "code": status["code"],
        }),
    )
}

fn normalize_stealth_code(value: &str) -> Option<String> {
    let trimmed = value.trim();
    let digits = trimmed
        .strip_prefix("*#*#")
        .and_then(|value| value.strip_suffix("#*#*"))
        .unwrap_or(trimmed);
    (digits.len() >= 3 && digits.len() <= 16 && digits.bytes().all(|byte| byte.is_ascii_digit()))
        .then(|| format!("*#*#{digits}#*#*"))
}

fn stored_stealth_code() -> String {
    fs::read_to_string(STEALTH_CODE_PATH)
        .ok()
        .and_then(|content| content.lines().next().and_then(normalize_stealth_code))
        .unwrap_or_else(|| DEFAULT_STEALTH_CODE.to_string())
}

fn stealth_code_matches(configured: &str, requested: &str) -> bool {
    let Some(configured) = normalize_stealth_code(configured) else {
        return false;
    };
    let Some(requested) = normalize_stealth_code(requested) else {
        return false;
    };
    constant_time_eq(&configured, &requested)
}

fn broadcast_stealth_state(enabled: bool) {
    let action = if enabled {
        STEALTH_MODE_CHANGED_ACTION
    } else {
        STEALTH_MODE_DISABLED_ACTION
    };
    for package_name in ["io.github.fixz.apkesu", "io.github.fixz.apkesu.dev"] {
        let receiver = format!("{package_name}/me.weishu.kernelsu.stealth.StealthModeSyncReceiver");
        match Command::new("/system/bin/am")
            .args([
                "broadcast",
                "--include-stopped-packages",
                "--receiver-foreground",
                "-a",
                action,
                "-n",
                receiver.as_str(),
            ])
            .status()
        {
            Ok(status) if status.success() => {}
            Ok(status) => {
                warn!("stealth mode sync broadcast to {package_name} exited with {status}");
            }
            Err(error) => warn!("stealth mode sync broadcast to {package_name} failed: {error}"),
        }
    }
}

fn broadcast_manager_settings_changed() {
    for package_name in ["io.github.fixz.apkesu", "io.github.fixz.apkesu.dev"] {
        let receiver = format!(
            "{package_name}/me.weishu.kernelsu.ui.webmanager.ManagerAppSettingsSyncReceiver"
        );
        match Command::new("/system/bin/am")
            .args([
                "broadcast",
                "--include-stopped-packages",
                "--receiver-foreground",
                "-a",
                MANAGER_SETTINGS_CHANGED_ACTION,
                "-n",
                receiver.as_str(),
            ])
            .status()
        {
            Ok(status) if status.success() => {}
            Ok(status) => {
                warn!("manager settings sync broadcast to {package_name} exited with {status}");
            }
            Err(error) => {
                warn!("manager settings sync broadcast to {package_name} failed: {error}");
            }
        }
    }
}

fn persist_stealth_state(enabled: bool, code: &str) -> Result<()> {
    ensure_storage()?;
    atomic_write(
        Path::new(STEALTH_CODE_PATH),
        format!("{code}\n").as_bytes(),
        0o600,
    )?;
    if enabled {
        atomic_write(Path::new(STEALTH_MODE_PATH), b"", 0o600)?;
    } else if let Err(error) = fs::remove_file(STEALTH_MODE_PATH)
        && error.kind() != std::io::ErrorKind::NotFound
    {
        return Err(error).context("disable stealth mode");
    }
    Ok(())
}

fn stealth_action_response(body: &[u8]) -> Response {
    let payload = match parse_json_body(body) {
        Ok(value) => value,
        Err(error) => return Response::error(400, "invalid_json", error.to_string()),
    };
    let Some(enabled) = payload.get("enabled").and_then(Value::as_bool) else {
        return Response::error(400, "enabled_required", "缺少 enabled 布尔值");
    };
    if !enabled {
        let Some(requested_code) = payload.get("code").and_then(Value::as_str) else {
            return Response::error(400, "stealth_code_required", "关闭隐身模式必须输入密令");
        };
        return disable_stealth_with_code(requested_code);
    }
    let code_value = payload
        .get("code")
        .and_then(Value::as_str)
        .map_or_else(stored_stealth_code, str::to_owned);
    let Some(code) = normalize_stealth_code(&code_value) else {
        return Response::error(
            400,
            "invalid_stealth_code",
            "隐身密令必须是 3-16 位数字或 *#*#数字#*#* 格式",
        );
    };
    let _guard = WRITE_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    if let Err(error) = persist_stealth_state(enabled, &code) {
        return Response::error(500, "stealth_update_failed", error.to_string());
    }
    broadcast_stealth_state(enabled);
    Response::json(
        200,
        json!({
            "ok": true,
            "enabled": enabled,
            "codeBackedUp": true,
            "code": code,
        }),
    )
}

fn disable_stealth_with_code(requested_code: &str) -> Response {
    let code = stored_stealth_code();
    if !stealth_code_matches(&code, requested_code) {
        return Response::error(403, "stealth_code_mismatch", "隐身密令不正确");
    }
    let _guard = WRITE_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    if let Err(error) = persist_stealth_state(false, &code) {
        return Response::error(500, "stealth_disable_failed", error.to_string());
    }
    broadcast_stealth_state(false);
    Response::json(
        200,
        json!({
            "ok": true,
            "enabled": false,
            "codeBackedUp": true,
            "code": code,
        }),
    )
}

fn stealth_disable_response(body: &[u8]) -> Response {
    let payload = match parse_json_body(body) {
        Ok(value) => value,
        Err(error) => return Response::error(400, "invalid_json", error.to_string()),
    };
    let Some(requested_code) = payload.get("code").and_then(Value::as_str) else {
        return Response::error(400, "stealth_code_required", "关闭隐身模式必须输入密令");
    };
    disable_stealth_with_code(requested_code)
}

fn auto_start_response(body: &[u8]) -> Response {
    let payload = match parse_json_body(body) {
        Ok(value) => value,
        Err(error) => return Response::error(400, "invalid_json", error.to_string()),
    };
    let Some(enabled) = payload.get("enabled").and_then(Value::as_bool) else {
        return Response::error(400, "enabled_required", "缺少 enabled 布尔值");
    };
    let _guard = WRITE_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let result = read_config().and_then(|mut config| {
        config.enabled = enabled;
        write_config(&config)
    });
    match result {
        Ok(()) => Response::json(200, json!({ "ok": true, "enabled": enabled })),
        Err(error) => Response::error(500, "config_update_failed", error.to_string()),
    }
}

fn stop_server_response() -> Response {
    if let Err(error) = ensure_storage()
        .and_then(|()| atomic_write(Path::new(defs::WEB_MANAGER_STOP_PATH), b"stop\n", 0o600))
    {
        return Response::error(500, "stop_failed", error.to_string());
    }
    Response::json(202, json!({ "ok": true, "message": "网页管理器正在停止" }))
}

fn reboot_status_response() -> Response {
    let userspace_supported =
        utils::getprop("ro.init.userspace_reboot.is_supported").is_some_and(|value| value == "1");
    Response::json(
        200,
        json!({
            "ok": true,
            "modes": [
                { "id": "system", "label": "重启系统", "supported": true },
                { "id": "userspace", "label": "重启用户空间", "supported": userspace_supported },
                { "id": "soft", "label": "软重启", "supported": true },
                { "id": "recovery", "label": "恢复模式", "supported": true },
                { "id": "bootloader", "label": "Bootloader", "supported": true },
                { "id": "download", "label": "下载模式", "supported": true },
                { "id": "edl", "label": "EDL", "supported": true }
            ]
        }),
    )
}

fn reboot_action_response(body: &[u8]) -> Response {
    let payload = match parse_json_body(body) {
        Ok(value) => value,
        Err(error) => return Response::error(400, "invalid_json", error.to_string()),
    };
    let Some(mode) = payload.get("mode").and_then(Value::as_str) else {
        return Response::error(400, "mode_required", "缺少重启模式");
    };
    if !matches!(
        mode,
        "system" | "userspace" | "soft" | "recovery" | "bootloader" | "download" | "edl"
    ) {
        return Response::error(400, "invalid_reboot_mode", "不支持这个重启模式");
    }
    let mode = mode.to_string();
    let requested_mode = mode.clone();
    let _ = std::thread::Builder::new()
        .name("ksud-web-reboot".to_string())
        .spawn(move || {
            std::thread::sleep(Duration::from_millis(700));
            if let Err(error) = dispatch_reboot(&requested_mode) {
                error!("web manager reboot '{requested_mode}' failed: {error:#}");
            }
        });
    Response::json(202, json!({ "ok": true, "mode": mode }))
}

fn dispatch_reboot(mode: &str) -> Result<()> {
    match mode {
        "soft" => init_event::soft_reboot(),
        "system" => run_reboot_command("/system/bin/reboot", &[]),
        "userspace" => run_reboot_command("setprop", &["sys.powerctl", "reboot,userspace"]),
        "recovery" => run_reboot_command("/system/bin/reboot", &["recovery"]),
        "bootloader" => run_reboot_command("/system/bin/reboot", &["bootloader"]),
        "download" => run_reboot_command("/system/bin/reboot", &["download"]),
        "edl" => run_reboot_command("/system/bin/reboot", &["edl"]),
        _ => bail!("unsupported reboot mode"),
    }
}

fn run_reboot_command(program: &str, arguments: &[&str]) -> Result<()> {
    let status = Command::new(program)
        .args(arguments)
        .status()
        .with_context(|| format!("run {program}"))?;
    ensure!(status.success(), "{program} exited with {status}");
    Ok(())
}

fn module_webui_response(path: &str) -> Response {
    let suffix = path.trim_start_matches("/webui/");
    let (encoded_id, relative) = suffix.split_once('/').unwrap_or((suffix, "index.html"));
    let Some(module_id) = percent_decode(encoded_id) else {
        return Response::error(400, "invalid_module_id", "模块 ID 编码无效");
    };
    if module::validate_module_id(&module_id).is_err() {
        return Response::error(404, "webui_not_found", "模块 WebUI 不存在");
    }
    let relative = if relative.is_empty() {
        "index.html"
    } else {
        relative
    };
    if relative.len() > 2048
        || relative.contains('\0')
        || relative.contains('\\')
        || relative
            .split('/')
            .any(|part| part.is_empty() || matches!(part, "." | ".."))
    {
        return Response::error(400, "invalid_webui_path", "WebUI 路径无效");
    }
    let root = Path::new(defs::MODULE_DIR)
        .join(&module_id)
        .join(defs::MODULE_WEB_DIR);
    let candidate = root.join(relative);
    let Ok(canonical_root) = root.canonicalize() else {
        return Response::error(404, "webui_not_found", "模块 WebUI 不存在");
    };
    let canonical_candidate = match candidate.canonicalize() {
        Ok(value) if value.starts_with(&canonical_root) && value.is_file() => value,
        _ => return Response::error(404, "webui_asset_not_found", "WebUI 资源不存在"),
    };
    match fs::read(&canonical_candidate) {
        Ok(bytes) => Response::text(200, mime_type(&canonical_candidate), bytes),
        Err(error) => Response::error(500, "webui_read_failed", error.to_string()),
    }
}

fn mime_type(path: &Path) -> &'static str {
    match path.extension().and_then(|value| value.to_str()) {
        Some("html" | "htm") => "text/html; charset=utf-8",
        Some("css") => "text/css; charset=utf-8",
        Some("js" | "mjs") => "application/javascript; charset=utf-8",
        Some("json") => "application/json; charset=utf-8",
        Some("png") => "image/png",
        Some("jpg" | "jpeg") => "image/jpeg",
        Some("gif") => "image/gif",
        Some("svg") => "image/svg+xml",
        Some("webp") => "image/webp",
        Some("woff") => "font/woff",
        Some("woff2") => "font/woff2",
        _ => "application/octet-stream",
    }
}

#[cfg(test)]
mod tests {
    use super::{
        build_kpm_summary, build_susfs_summary, constant_time_eq, default_manager_app_settings,
        detect_image_mime, is_valid_asset, normalize_api_path, normalize_asset_meta,
        normalize_manager_app_settings, normalize_stealth_code, parse_app_icon_path,
        parse_asset_path, percent_decode, selinux_from_enforce, stealth_code_matches,
        strip_token_path,
    };
    use serde_json::json;

    #[test]
    fn token_path_is_scoped() {
        let token = "a".repeat(64);
        let (path, parsed) = strip_token_path(&format!("/w/{token}/api/status"));
        assert_eq!(path, "/api/status");
        assert_eq!(parsed.as_deref(), Some(token.as_str()));
    }

    #[test]
    fn malformed_token_path_is_not_trusted() {
        let (path, parsed) = strip_token_path("/w/not-a-token/api/status");
        assert_eq!(path, "/w/not-a-token/api/status");
        assert!(parsed.is_none());
    }

    #[test]
    fn identifiers_are_percent_decoded_without_path_aliases() {
        assert_eq!(
            percent_decode("module%2Ename").as_deref(),
            Some("module.name")
        );
        assert!(percent_decode("broken%2").is_none());
    }

    #[test]
    fn token_comparison_requires_same_content_and_length() {
        assert!(constant_time_eq("abc", "abc"));
        assert!(!constant_time_eq("abc", "abd"));
        assert!(!constant_time_eq("abc", "ab"));
    }

    #[test]
    fn versioned_api_routes_keep_v1_stable_and_legacy_compatible() {
        assert_eq!(normalize_api_path("/api/v1"), "/api/meta");
        assert_eq!(normalize_api_path("/api/v1/status"), "/api/status");
        assert_eq!(
            normalize_api_path("/api/v1/assets/wallpaper/lkm"),
            "/api/assets/wallpaper/lkm"
        );
        assert_eq!(normalize_api_path("/api/status"), "/api/status");
        assert_eq!(normalize_api_path("/api/v10/status"), "/api/v10/status");
    }

    #[test]
    fn asset_targets_are_strictly_whitelisted() {
        assert!(is_valid_asset("wallpaper", "lkm"));
        assert!(is_valid_asset("navicon", "settings"));
        assert!(is_valid_asset("modulewall", "hybrid_mount"));
        assert_eq!(
            parse_asset_path("/api/assets/wallpaper/device"),
            Some(("wallpaper", "device"))
        );
        assert_eq!(
            parse_asset_path("/api/assets/modulewall/hybrid_mount"),
            Some(("modulewall", "hybrid_mount"))
        );
        assert!(!is_valid_asset("wallpaper", "../config.json"));
        assert!(!is_valid_asset("modulewall", "../module"));
        assert!(parse_asset_path("/api/assets/navicon/home/extra").is_none());
        assert!(parse_asset_path("/api/assets/unknown/home").is_none());
    }

    #[test]
    fn asset_metadata_is_normalized_to_safe_ranges() {
        let wallpaper = normalize_asset_meta(
            "wallpaper",
            Some(&json!({
                "fit": "invalid",
                "scale": 9,
                "offsetX": -99,
                "offsetY": 80,
                "dim": 1,
                "blur": 99,
            })),
            42,
        );
        assert_eq!(wallpaper["fit"], "cover");
        assert_eq!(wallpaper["scale"], 3.0);
        assert_eq!(wallpaper["offsetX"], -50.0);
        assert_eq!(wallpaper["offsetY"], 50.0);
        assert_eq!(wallpaper["dim"], 0.85);
        assert_eq!(wallpaper["blur"], 12.0);

        let icon = normalize_asset_meta(
            "navicon",
            Some(&json!({ "scale": 9, "offsetX": -99, "offsetY": 20 })),
            43,
        );
        assert_eq!(icon["scale"], 3.0);
        assert_eq!(icon["offsetX"], -12.0);
        assert_eq!(icon["offsetY"], 12.0);

        let module_wallpaper = normalize_asset_meta(
            "modulewall",
            Some(&json!({ "fit": "contain", "scale": 1.3, "offsetX": 4, "dim": 0.6 })),
            44,
        );
        assert_eq!(module_wallpaper["fit"], "contain");
        assert_eq!(module_wallpaper["scale"], 1.3);
        assert_eq!(module_wallpaper["offsetX"], 4.0);
        assert_eq!(module_wallpaper["dim"], 0.6);
    }

    #[test]
    fn app_icon_paths_only_accept_package_names() {
        assert_eq!(
            parse_app_icon_path("/api/apps/icon/com.example.app.png").as_deref(),
            Some("com.example.app")
        );
        assert_eq!(
            parse_app_icon_path("/api/apps/icon/com%2Eexample%2Eapp.png").as_deref(),
            Some("com.example.app")
        );
        assert!(parse_app_icon_path("/api/apps/icon/..%2Fsecret.png").is_none());
        assert!(parse_app_icon_path("/api/apps/icon/com.example.app.jpg").is_none());
    }

    #[test]
    fn common_image_headers_are_recognized() {
        assert_eq!(
            detect_image_mime(&[0x89, b'P', b'N', b'G', 0x0d, 0x0a, 0x1a, 0x0a]),
            Some("image/png")
        );
        assert_eq!(detect_image_mime(b"RIFF0000WEBP"), Some("image/webp"));
        assert_eq!(detect_image_mime(b"not an image"), None);
    }

    #[test]
    fn stealth_codes_accept_digits_or_full_secret_code_format() {
        assert_eq!(
            normalize_stealth_code("4211").as_deref(),
            Some("*#*#4211#*#*")
        );
        assert_eq!(
            normalize_stealth_code("*#*#123456#*#*").as_deref(),
            Some("*#*#123456#*#*")
        );
        assert!(normalize_stealth_code("12").is_none());
        assert!(normalize_stealth_code("*#*#12ab#*#*").is_none());
    }

    #[test]
    fn stealth_disable_code_must_match_after_normalization() {
        assert!(stealth_code_matches("*#*#4211#*#*", "4211"));
        assert!(stealth_code_matches("4211", " *#*#4211#*#* "));
        assert!(!stealth_code_matches("*#*#4211#*#*", "42110"));
        assert!(!stealth_code_matches("*#*#4211#*#*", "12"));
    }

    #[test]
    fn manager_settings_are_strict_and_canonical() {
        let mut settings = default_manager_app_settings();
        settings["showGkiWarning"] = json!(false);
        settings["customHomeTitle"] = json!(" ApkeSU ");
        let normalized = normalize_manager_app_settings(&settings).unwrap();
        assert_eq!(normalized["showGkiWarning"], json!(false));
        assert_eq!(normalized["customHomeTitle"], json!("ApkeSU"));

        settings["language"] = json!("de");
        assert!(normalize_manager_app_settings(&settings).is_err());
        settings["language"] = json!("zh-CN");
        settings["unknown"] = json!(true);
        assert!(normalize_manager_app_settings(&settings).is_err());
    }

    #[test]
    fn selinux_enforce_values_map_to_native_labels() {
        assert_eq!(selinux_from_enforce("1"), "Enforcing");
        assert_eq!(selinux_from_enforce("0"), "Permissive");
        assert_eq!(selinux_from_enforce("2"), "Unknown");
        assert_eq!(selinux_from_enforce(""), "Unknown");
    }

    #[test]
    fn kpm_summary_matches_native_device_card_format() {
        let caps = json!({
            "supported": true,
            "loaderReady": true,
            "kernelSupported": true,
            "abiVersion": 1,
            "maxLoaded": 8,
            "policyEnabled": true,
            "lateLoad": false,
        });
        assert_eq!(
            build_kpm_summary(Some(&caps), Some(2)),
            "已启用 · 已加载 2 个 · 上限 8 · ABI v1"
        );

        let closed_policy = json!({
            "supported": false,
            "loaderReady": true,
            "kernelSupported": true,
            "abiVersion": 0,
            "maxLoaded": 0,
            "policyEnabled": false,
            "lateLoad": true,
        });
        assert_eq!(
            build_kpm_summary(Some(&closed_policy), None),
            "已就绪 · 策略已关闭 · 晚加载模式"
        );

        let unsupported = json!({
            "supported": false,
            "loaderReady": false,
            "kernelSupported": false,
            "policyEnabled": true,
        });
        assert_eq!(build_kpm_summary(Some(&unsupported), None), "");
        assert_eq!(build_kpm_summary(None, None), "");
        // 能力读不到但确实加载了条目时仍要显示
        assert_eq!(build_kpm_summary(None, Some(1)), "");
        assert_eq!(
            build_kpm_summary(Some(&unsupported), Some(1)),
            "内核支持 · 已加载 1 个"
        );
    }

    #[test]
    fn susfs_summary_matches_native_device_card_format() {
        assert_eq!(
            build_susfs_summary(
                "/data/adb/ksu/bin/ksu_susfs",
                "susfs version: v2.0.0",
                33,
                3,
                8
            ),
            "susfs version: v2.0.0 · 特性 33 项 · 挂载 3 项 · 隐藏路径 8 条"
        );
        assert_eq!(
            build_susfs_summary("", "", 0, 4, 0),
            "已检测到挂载 · 挂载 4 项 · 未找到 ksu_susfs"
        );
        // 没有版本也没有挂载痕迹：隐藏整行
        assert_eq!(build_susfs_summary("", "", 0, 0, 6), "");
        assert_eq!(
            build_susfs_summary("/system/bin/ksu_susfs", "v2.0.0", 0, 0, 0),
            "v2.0.0"
        );
    }
}
