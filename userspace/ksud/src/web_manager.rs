use anyhow::{Context, Result, bail, ensure};
use log::{error, info, warn};
use rust_embed::RustEmbed;
use serde_json::{Value, json};
use std::collections::{HashMap, HashSet};
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpListener, TcpStream};
use std::os::fd::AsRawFd;
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::Path;
use std::process::Command;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use crate::{defs, init_event, ksucalls, module, utils};

const DEFAULT_PORT: u16 = 10_240;
const CONFIG_SCHEMA_VERSION: u32 = 1;
const MAX_HEADER_BYTES: usize = 16 * 1024;
const MAX_BODY_BYTES: usize = 256 * 1024;
const MAX_CLIENTS: usize = 8;
const IO_TIMEOUT: Duration = Duration::from_secs(8);
const START_TIMEOUT: Duration = Duration::from_secs(3);
const COOKIE_NAME: &str = "apkesu_web_token";
const TOKEN_PATH_PREFIX: &str = "/w/";
const PACKAGES_LIST_PATH: &str = "/data/system/packages.list";

static SHUTDOWN: AtomicBool = AtomicBool::new(false);
static WRITE_LOCK: Mutex<()> = Mutex::new(());

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
        matches!(method.as_str(), "GET" | "POST"),
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
        "HTTP/1.1 {} {}\r\nContent-Type: {}\r\nContent-Length: {}\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'\r\n{}\r\n",
        response.status,
        reason,
        response.content_type,
        response.body.len(),
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
            "{COOKIE_NAME}={}; Path=/; HttpOnly; SameSite=Strict",
            context.config.token
        ));
    }
    response
}

fn route_authenticated(request: &Request, path: &str, context: &ServerContext) -> Response {
    match (request.method.as_str(), path) {
        ("GET", "/" | "/index.html") => embedded_asset("index.html"),
        ("GET", "/app.js") => embedded_asset("app.js"),
        ("GET", "/style.css") => embedded_asset("style.css"),
        ("GET", "/api/status") => status_response(context),
        ("GET", "/api/modules") => modules_response(),
        ("GET", "/api/superuser") => superuser_response(),
        ("GET", "/api/reboot") => reboot_status_response(),
        ("GET", "/api/settings") => settings_response(context),
        ("POST", "/api/reboot") => reboot_action_response(&request.body),
        ("POST", "/api/settings/auto-start") => auto_start_response(&request.body),
        ("POST", "/api/admin/stop") => stop_server_response(),
        _ if request.method == "POST" && path.starts_with("/api/modules/") => {
            module_action_response(path)
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
        _ => "application/octet-stream",
    };
    Response::text(200, content_type, asset.data.into_owned())
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
    Response::json(
        200,
        json!({
            "ok": true,
            "persistent": true,
            "server": {
                "running": true,
                "port": context.config.port,
                "enabled": context.config.enabled,
                "pid": std::process::id(),
            },
            "kernel": {
                "available": info.version > 0,
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
            }
        }),
    )
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
        if package_name.len() <= 255
            && package_name
                .bytes()
                .all(|value| value.is_ascii_alphanumeric() || matches!(value, b'.' | b'_'))
        {
            packages.push((package_name.to_string(), uid));
        }
    }
    packages.sort_by(|left, right| left.0.cmp(&right.0));
    Ok(packages)
}

fn superuser_response() -> Response {
    let allowlist = match ksucalls::get_allow_list() {
        Ok(value) => value.into_iter().collect::<HashSet<_>>(),
        Err(error) => return Response::error(500, "allowlist_failed", error.to_string()),
    };
    match read_packages() {
        Ok(packages) => {
            let records = packages
                .into_iter()
                .filter(|(_, uid)| *uid >= 10_000)
                .map(|(package_name, uid)| {
                    json!({
                        "packageName": package_name,
                        "uid": uid,
                        "granted": allowlist.contains(&uid),
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
        Ok(()) => Response::json(
            200,
            json!({ "ok": true, "packageName": package_name, "uid": uid, "granted": allow }),
        ),
        Err(error) => Response::error(409, "profile_update_failed", error.to_string()),
    }
}

fn settings_response(context: &ServerContext) -> Response {
    Response::json(
        200,
        json!({
            "ok": true,
            "persistent": true,
            "enabled": context.config.enabled,
            "port": context.config.port,
            "bindAddress": "127.0.0.1",
            "authentication": "token-cookie",
            "configPath": defs::WEB_MANAGER_CONFIG_PATH,
        }),
    )
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
    use super::{constant_time_eq, percent_decode, strip_token_path};

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
}
