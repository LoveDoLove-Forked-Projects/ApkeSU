package me.weishu.kernelsu.ui.webmanager

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.system.Os
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.io.SuFile
import kotlinx.coroutines.runBlocking
import me.weishu.kernelsu.BuildConfig
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.data.model.AppInfo
import me.weishu.kernelsu.data.repository.SettingsRepository
import me.weishu.kernelsu.data.repository.SettingsRepositoryImpl
import me.weishu.kernelsu.data.repository.SuperUserRepositoryImpl
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.util.KpmCaps
import me.weishu.kernelsu.ui.util.KpmCommandResult
import me.weishu.kernelsu.ui.util.KpmEntry
import me.weishu.kernelsu.ui.util.KpmExcludedApp
import me.weishu.kernelsu.ui.util.AppLanguageManager
import me.weishu.kernelsu.ui.util.LauncherIconOption
import me.weishu.kernelsu.ui.util.applyLauncherIcon
import me.weishu.kernelsu.ui.util.collectRootDiagnosticInfo
import me.weishu.kernelsu.ui.util.clearDynamicManager
import me.weishu.kernelsu.ui.util.deleteHiddenPathConfig
import me.weishu.kernelsu.ui.util.execKsud
import me.weishu.kernelsu.ui.util.getCpuSpoofStatus
import me.weishu.kernelsu.ui.util.getDynamicManagerStatus
import me.weishu.kernelsu.ui.util.getHiddenPathConfig
import me.weishu.kernelsu.ui.util.getHiddenPathLogs
import me.weishu.kernelsu.ui.util.isCpuSpoofModelValid
import me.weishu.kernelsu.ui.util.restoreDefaultCpuSpoof
import me.weishu.kernelsu.ui.util.reboot
import me.weishu.kernelsu.ui.util.saveCpuSpoofTarget
import me.weishu.kernelsu.ui.util.setDynamicManagerCertificate
import me.weishu.kernelsu.ui.util.setCpuSpoofEnabled
import me.weishu.kernelsu.ui.util.setHiddenPathAutoLoad
import me.weishu.kernelsu.ui.util.unloadHiddenPathKernelPaths
import me.weishu.kernelsu.ui.util.controlKpm
import me.weishu.kernelsu.ui.util.createRootShell
import me.weishu.kernelsu.ui.util.ensureManagerRegistered
import me.weishu.kernelsu.ui.util.getKpmCaps
import me.weishu.kernelsu.ui.util.getKpmExcludedApps
import me.weishu.kernelsu.ui.util.getKpmList
import me.weishu.kernelsu.ui.util.getNativeWebManagerUrl
import me.weishu.kernelsu.ui.util.importKpm
import me.weishu.kernelsu.ui.util.isManagerHiddenModuleId
import me.weishu.kernelsu.ui.util.listModulesWithTimeout
import me.weishu.kernelsu.ui.util.loadKpm
import me.weishu.kernelsu.ui.util.parseKpmEntries
import me.weishu.kernelsu.ui.util.probeKpmSummary
import me.weishu.kernelsu.ui.util.probeSusfsSummary
import me.weishu.kernelsu.ui.util.removeKpm
import me.weishu.kernelsu.ui.util.resolveDeviceName
import me.weishu.kernelsu.ui.util.rootAvailable
import me.weishu.kernelsu.ui.util.setKpmAppExcluded
import me.weishu.kernelsu.ui.util.setKpmEnabled
import me.weishu.kernelsu.ui.util.setKpmPolicy
import me.weishu.kernelsu.ui.util.startNativeWebManager
import me.weishu.kernelsu.ui.util.toggleModule
import me.weishu.kernelsu.ui.util.unloadKpm
import me.weishu.kernelsu.ui.util.undoUninstallModule
import me.weishu.kernelsu.ui.util.uninstallModule
import me.weishu.kernelsu.stealth.DEFAULT_STEALTH_MODE_CODE
import me.weishu.kernelsu.stealth.StealthModeStore
import me.weishu.kernelsu.ui.webui.SuFilePathHandler
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import android.content.ComponentName

/**
 * Loopback HTTP console for the manager.
 *
 * Every response is gated by a random per-session token that may be presented as
 * a query parameter, an `Authorization: Bearer` header, the session cookie, or a
 * `/w/<token>/...` path prefix. The path form exists so module WebUI pages keep
 * working when a browser refuses to store the session cookie: relative module
 * assets inherit the authenticated prefix instead of relying on cookies.
 */
internal object WebManagerServer {
    private const val TAG = "ApkeSU-WebManager"
    private const val API_VERSION = 3
    private const val MAX_HEADERS_BYTES = 16 * 1024
    private const val MAX_BODY_BYTES = 64 * 1024
    private const val MAX_HTML_BYTES = 512 * 1024
    /** 二进制上传端点（KPM 导入、自定义壁纸/图标）：4 MiB 文件 + 余量，其余端点仍限 64 KiB。 */
    private const val MAX_UPLOAD_BYTES = 8 * 1024 * 1024
    private const val DEFAULT_KPM_MAX_IMAGE_BYTES = 4L * 1024 * 1024
    private const val KPM_QUERY_DEADLINE_MILLIS = 20_000L
    private const val KPM_ACTION_DEADLINE_MILLIS = 35_000L
    private const val MAX_ICON_BYTES = 512 * 1024
    private const val MAX_EXEC_COMMAND_LENGTH = 8 * 1024
    private const val MAX_EXEC_CWD_LENGTH = 512
    private const val MAX_EXEC_ENV_COUNT = 64
    private const val MAX_EXEC_ENV_VALUE_LENGTH = 2 * 1024
    private const val MAX_EXEC_ARGS = 128
    private const val MAX_EXEC_ARG_LENGTH = 2 * 1024
    private const val MAX_EXEC_OUTPUT_CHARS = 512 * 1024
    private const val EXEC_TIMEOUT_MILLIS = 30_000L
    private const val APP_SNAPSHOT_TTL_MILLIS = 30_000L
    private const val MODULE_SNAPSHOT_TTL_MILLIS = 1_500L
    private const val MODULES_ROOT = "/data/adb/modules"
    private const val REQUEST_TIMEOUT_MILLIS = 5_000
    private const val ICON_SIZE_PX = 96

    /**
     * Hard upper bounds for the two root backed queries. The browser cannot wait
     * longer than its own timeout, and a stuck root shell must never block the
     * request thread forever, so both queries run on the [queryExecutor] and are
     * observed through [runWithDeadline].
     */
    private const val MODULE_QUERY_DEADLINE_MILLIS = 12_000L

    /**
     * 应用列表查询：本地（PackageManager + 允许列表，无 root/binder）与 Root 服务
     * **并行**跑，Root 只在 [ROOT_APP_WAIT_MILLIS] 内等，超时就直接用本地的结果，
     * 这样页面永远不会因为 Root 服务卡住而拿不到应用。
     */
    private const val ROOT_APP_WAIT_MILLIS = 4_000L
    private const val LOCAL_APP_WAIT_MILLIS = 8_000L
    private const val DEVICE_QUERY_DEADLINE_MILLIS = 25_000L
    private const val DEVICE_SNAPSHOT_TTL_MILLIS = 60_000L
    private const val ASSET_DIR = "web-manager-assets"
    /** 功能开关：状态探测 4s，写入 6s；内核不支持时状态为 unknown。 */
    private const val FEATURE_QUERY_DEADLINE_MILLIS = 4_000L
    private const val FEATURE_ACTION_DEADLINE_MILLIS = 6_000L
    private const val FEATURE_STATUS_UNKNOWN = "unknown"
    private const val FEATURE_GROUP_ROOT = "root"
    private const val FEATURE_GROUP_MOUNT = "mount"
    /** 工具箱：状态探测 6s，写入 15s（软重启等要等 ksud 返回）。 */
    private const val TOOLS_QUERY_DEADLINE_MILLIS = 6_000L
    private const val TOOLS_ACTION_DEADLINE_MILLIS = 15_000L
    private const val REBOOT_ROOT_CHECK_DEADLINE_MILLIS = 4_000L
    /** Let the HTTP 202 response leave the socket before Android tears down userspace. */
    private const val REBOOT_DISPATCH_DELAY_MILLIS = 750L
    /** 网页管理器界面语言固定为中文，语言设置只作用于原生管理器。 */
    private const val WEB_MANAGER_PAGE_LANGUAGE = "zh-CN"
    /** 外观图片允许浏览器私有缓存；URL 里带 updatedAt 做缓存失效。 */
    private const val ASSET_CACHE_SECONDS = 600
    private val iconPrefixes = listOf("/api/webui/icon/", "/api/icon/")
    private val packageNamePattern = Regex("[A-Za-z0-9._]+")
    private val KPM_ACTIONS = setOf("enable", "disable", "load", "unload", "remove", "control")
    private val EMPTY_BODY = ByteArray(0)

    /** 唯一接受二进制请求体的端点（KPM 导入）。 */
    private const val KPM_IMPORT_PATH = "/api/kpm/import"
    private val envKeyPattern = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
    private val bridgePlaceholderPattern =
        Regex("__MODULE_ID__|__MODULE_INFO_JSON__|__PACKAGE_DATA__|__TOKEN_PREFIX__")

    private val lock = Any()
    private val moduleSnapshotLock = Any()
    private val appSnapshotLock = Any()
    private val deviceSnapshotLock = Any()
    private val actionShellsLock = Any()
    private val diagnostics = WebManagerDiagnostics()
    private val superUserRepository = SuperUserRepositoryImpl()
    private val settingsRepository: SettingsRepository by lazy { SettingsRepositoryImpl() }
    private val jobs = WebManagerJobs()
    private val rebootPending = AtomicBoolean(false)
    private val activeActionShells = HashMap<String, Shell>()
    private val queryExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "ApkeSU-WebManager-Query").apply { isDaemon = true }
    }
    private val random = SecureRandom()
    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private var token: String? = null
    private var startedAtElapsedRealtime: Long = 0L
    private var cachedModuleSnapshot: ModuleSnapshot? = null
    private var cachedAppSnapshot: SuperUserSnapshot? = null
    private var cachedDeviceSnapshot: DeviceSnapshot? = null
    private var warmUpThread: Thread? = null

    /** 应用列表查询专用线程池：Root 查询卡住时也不会占住其它查询的线程。 */
    private val appQueryExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "ApkeSU-WebManager-Apps").apply { isDaemon = true }
    }

    /** 服务实际绑定的端口：绑定 0 端口由系统分配，避免与其它程序抢固定端口。 */
    @Volatile
    private var boundPort: Int = 0

    /** 当前服务端口；未启动时返回 0。 */
    fun port(): Int = boundPort

    fun start(): String = synchronized(lock) {
        val existing = serverSocket
        if (existing != null && !existing.isClosed) {
            return@synchronized url(token ?: error("web manager token is missing"))
        }

        // 默认绑定 0 → 由内核分配随机空闲端口（仅回环）；设置里指定固定端口时优先用它，
        // 端口被占用或不可用则回落随机，保证服务仍能起来（诊断里会记一笔）。
        val loopback = InetAddress.getByName("127.0.0.1")
        val requestedPort = WebManagerPreferences.fixedPort(ksuApp)
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(loopback, requestedPort))
            }
        } catch (error: IOException) {
            if (requestedPort == 0) throw error
            diagnostics.warn(
                "service",
                "fixed port $requestedPort unavailable (${error.message}), falling back to a random port",
            )
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(loopback, 0))
            }
        }
        val nextToken = ByteArray(32).also(random::nextBytes).toHex()
        val nextExecutor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "ApkeSU-WebManager-Client").apply { isDaemon = true }
        }
        serverSocket = socket
        executor = nextExecutor
        token = nextToken
        boundPort = socket.localPort
        startedAtElapsedRealtime = SystemClock.elapsedRealtime()
        Thread({ acceptLoop(socket, nextExecutor) }, "ApkeSU-WebManager-Accept").apply {
            isDaemon = true
            start()
        }
        startWarmUp()
        Log.i(TAG, "web manager listening on 127.0.0.1:$boundPort")
        url(nextToken)
    }

    fun stop() = synchronized(lock) {
        serverSocket?.close()
        serverSocket = null
        executor?.shutdownNow()
        executor = null
        token = null
        boundPort = 0
        startedAtElapsedRealtime = 0L
        warmUpThread?.interrupt()
        warmUpThread = null
        invalidateModuleSnapshot()
        synchronized(appSnapshotLock) { cachedAppSnapshot = null }
        synchronized(deviceSnapshotLock) { cachedDeviceSnapshot = null }
        synchronized(actionShellsLock) {
            val now = SystemClock.elapsedRealtime()
            activeActionShells.forEach { (jobId, shell) ->
                jobs.cancel(jobId, now)
                runCatching { shell.close() }
            }
            activeActionShells.clear()
        }
        Log.i(TAG, "web manager stopped")
    }

    fun isRunning(): Boolean = synchronized(lock) {
        serverSocket?.let { !it.isClosed } == true
    }

    /**
     * The app list needs a root service round trip that is slow on a cold start.
     * Fill both caches once in the background so the first browser request can
     * answer from memory instead of tripping the browser timeout.
     */
    private fun startWarmUp() {
        if (warmUpThread?.isAlive == true) return
        warmUpThread = Thread({
            runCatching { readModuleList(forceRefresh = true) }
            runCatching { readSuperUserSnapshot(forceRefresh = true) }
        }, "ApkeSU-WebManager-WarmUp").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private fun acceptLoop(socket: ServerSocket, pool: ExecutorService) {
        try {
            while (!socket.isClosed) {
                val client = socket.accept()
                pool.execute { handle(client) }
            }
        } catch (error: Throwable) {
            if (!socket.isClosed) Log.e(TAG, "accept loop failed", error)
        }
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = REQUEST_TIMEOUT_MILLIS
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            val requestLine = readLine(input, MAX_HEADERS_BYTES) ?: return
            val requestParts = requestLine.split(' ', limit = 3)
            if (requestParts.size != 3) {
                respond(output, 400, jsonError("invalid request"))
                return
            }
            val headers = linkedMapOf<String, String>()
            var headerBytes = requestLine.toByteArray(StandardCharsets.ISO_8859_1).size
            while (true) {
                val line = readLine(input, MAX_HEADERS_BYTES) ?: run {
                    respond(output, 400, jsonError("incomplete headers"))
                    return
                }
                headerBytes += line.length
                if (headerBytes > MAX_HEADERS_BYTES) {
                    respond(output, 431, jsonError("headers too large"))
                    return
                }
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator <= 0) {
                    respond(output, 400, jsonError("invalid header"))
                    return
                }
                headers[line.substring(0, separator).trim().lowercase()] =
                    line.substring(separator + 1).trim()
            }

            val target = runCatching { URI(requestParts[1]) }.getOrNull()
            if (target == null || target.isAbsolute) {
                respond(output, 400, jsonError("invalid request target"))
                return
            }
            val rawPath = target.path.orEmpty()
            val tokenPath = WebManagerRoutes.parseTokenPath(rawPath)
            val requestRoutePath = WebManagerRoutes.routePath(rawPath)
            // Only the KPM import endpoint accepts a binary payload, so it gets a
            // larger cap than the JSON endpoints.
            val bodyLimit = if (requestRoutePath == KPM_IMPORT_PATH ||
                requestRoutePath.startsWith(WebManagerRoutes.ASSET_PATH_PREFIX)
            ) {
                MAX_UPLOAD_BYTES
            } else {
                MAX_BODY_BYTES
            }
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            if (contentLength < 0 || contentLength > bodyLimit) {
                respond(output, 413, jsonError("request body too large"))
                return
            }
            var requestBody = ""
            var requestBodyBytes = EMPTY_BODY
            if (contentLength > 0) {
                val body = ByteArray(contentLength)
                var offset = 0
                while (offset < body.size) {
                    val read = input.read(body, offset, body.size - offset)
                    if (read <= 0) {
                        respond(output, 400, jsonError("incomplete request body"))
                        return
                    }
                    offset += read
                }
                requestBodyBytes = body
                requestBody = String(body, StandardCharsets.UTF_8)
            }

            val currentToken = synchronized(lock) { token }
            val routePath = requestRoutePath
            val parameters = query(target.rawQuery)
            val headerToken = WebManagerSecurity.bearerToken(headers["authorization"])
            val cookieToken = WebManagerSecurity.cookieToken(headers["cookie"])
            val queryAuthenticated =
                WebManagerSecurity.constantTimeEquals(currentToken.orEmpty(), parameters["token"])
            val pathAuthenticated = tokenPath != null &&
                WebManagerSecurity.constantTimeEquals(currentToken.orEmpty(), tokenPath.token)
            if (currentToken == null ||
                !(queryAuthenticated ||
                    pathAuthenticated ||
                    WebManagerSecurity.constantTimeEquals(currentToken, headerToken) ||
                    WebManagerSecurity.constantTimeEquals(currentToken, cookieToken))
            ) {
                // A page navigation deserves an explanation; API clients keep
                // getting JSON so they can handle the code.
                val wantsHtml = headers["accept"]?.contains("text/html", ignoreCase = true) == true
                respond(
                    output,
                    if (wantsHtml) {
                        HttpResponse(401, WEB_MANAGER_TOKEN_PAGE, "text/html; charset=utf-8")
                    } else {
                        HttpResponse(401, jsonError("invalid or missing access token"))
                    },
                )
                return
            }

            val method = requestParts[0].uppercase(Locale.ROOT)
            if (method in setOf("POST", "PUT", "PATCH", "DELETE") &&
                !WebManagerSecurity.isAllowedOrigin(headers["origin"], boundPort)
            ) {
                respond(output, 403, jsonError("cross-origin request rejected"))
                return
            }

            val tokenPrefix = WebManagerRoutes.tokenPrefix(currentToken)
            val wantsHtml = headers["accept"]?.contains("text/html", ignoreCase = true) == true
            val response = runCatching {
                val primary = route(
                    method,
                    routePath,
                    parameters,
                    requestBody,
                    tokenPrefix,
                    requestBodyBytes,
                    wantsHtml,
                )
                val fallbackPath = if (method == "GET" && primary.status in setOf(404, 405)) {
                    WebManagerRoutes.resolveRootRelativeWebUiAsset(
                        requestPath = routePath,
                        referrer = headers["referer"],
                        port = boundPort,
                        activeToken = currentToken,
                    )
                } else {
                    null
                }
                if (fallbackPath != null) {
                    diagnostics.info("webui", "mapped root asset $routePath -> $fallbackPath")
                    webUiAssetResponse(fallbackPath, tokenPrefix, wantsHtml)
                } else {
                    primary
                }
            }.getOrElse { error ->
                Log.e(TAG, "request handler failed", error)
                diagnostics.error("route", "$method $routePath failed", error)
                errorResponse(
                    status = 500,
                    code = "request_handler_failed",
                    message = "服务器处理请求失败",
                    detail = error,
                )
            }
            if (response.status >= 400 && response.status != 401) {
                diagnostics.warn("route", "$method $routePath -> ${response.status}")
            }
            respond(
                output,
                if (queryAuthenticated || pathAuthenticated) response.copy(setCookie = true) else response,
            )
        }
    }

    private fun route(
        method: String,
        path: String,
        parameters: Map<String, String>,
        body: String,
        tokenPrefix: String,
        bodyBytes: ByteArray = EMPTY_BODY,
        wantsHtml: Boolean = false,
    ): HttpResponse {
        val moduleApi = WebManagerRoutes.parseModuleApiPath(path)
        val assetPath = WebManagerRoutes.parseAssetPath(path)
        val jobApi = WebManagerRoutes.parseJobApiPath(path)
        val iconPackage = WebManagerRoutes.parseIconPath(path, iconPrefixes)
        return when {
            method == "GET" && path == "/" ->
                HttpResponse(200, managerPage(), "text/html; charset=utf-8")
            method == "GET" && path == WebManagerRoutes.BRIDGE_PATH ->
                webUiBridgeResponse(parameters["module"], tokenPrefix)
            method == "GET" && iconPackage != null -> iconResponse(iconPackage)
            method == "GET" && path.startsWith(WebManagerRoutes.WEBUI_PATH_PREFIX) ->
                webUiAssetResponse(path, tokenPrefix, wantsHtml)
            method == "GET" && path == "/api/status" -> HttpResponse(200, statusJson())
            method == "GET" && path == "/api/modules" -> modulesResponse(
                forceRefresh = parameters["refresh"] == "1",
            )
            method == "GET" && path == "/api/superuser" -> superUsersResponse(
                forceRefresh = parameters["refresh"] == "1",
            )
            method == "GET" && path == "/api/superuser/summary" -> superUserSummaryResponse(
                forceRefresh = parameters["refresh"] == "1",
            )
            method == "GET" && path == "/api/settings" -> HttpResponse(200, settingsJson())
            method == "GET" && path == "/api/settings/manager" ->
                HttpResponse(200, managerAppSettingsJson().toString())
            method == "GET" && path == "/api/stealth" -> HttpResponse(200, stealthStatusJson().toString())
            method == "GET" && path == "/api/dynamic-manager" -> dynamicManagerStatusResponse()
            method == "GET" && assetPath != null -> assetResponse(assetPath.kind, assetPath.name)
            method == "POST" && assetPath != null ->
                uploadAssetResponse(assetPath.kind, assetPath.name, parameters, bodyBytes)
            method == "DELETE" && assetPath != null -> clearAssetResponse(assetPath.kind, assetPath.name)
            method == "POST" && path == "/api/settings/theme" -> handleThemeAction(body)
            method == "POST" && path == "/api/settings/port" -> handlePortAction(body)
            method == "POST" && path == "/api/settings/launcher" -> handleLauncherAction(body)
            method == "GET" && path == "/api/features" -> HttpResponse(200, featuresJson())
            method == "GET" && path == "/api/tools" -> HttpResponse(200, toolsJson())
            method == "GET" && path == "/api/tools/reboot" -> HttpResponse(200, rebootStatusJson())
            method == "POST" && path == "/api/tools/builtin-mount" -> handleBuiltinMountAction(body)
            method == "POST" && path == "/api/tools/kpatch" -> handleKPatchAction(body)
            method == "POST" && path == "/api/tools/pathmask" -> handlePathmaskAction(body)
            method == "POST" && path == "/api/tools/cpu-spoof" -> handleCpuSpoofAction(body)
            method == "POST" && path == "/api/tools/reboot" -> handleRebootAction(body)
            method == "POST" && path == "/api/tools/native-susfs" -> nativeSusfsManagerResponse()
            method == "POST" && path == "/api/tools/soft-reboot" ->
                scheduleReboot(WebManagerRebootMode.SOFT)
            method == "POST" && path == "/api/settings/language" -> handleLanguageAction(body)
            method == "POST" && path == "/api/settings/manager" -> handleManagerAppSettingsAction(body)
            method == "POST" && path == "/api/features" -> handleFeatureAction(body)
            method == "POST" && path == "/api/settings/asset-meta" -> handleAssetMetaAction(body)
            method == "GET" && path == "/api/device" -> HttpResponse(
                200,
                deviceJson(forceRefresh = parameters["refresh"] == "1"),
            )
            method == "GET" && path == "/api/kpm" -> kpmResponse()
            method == "POST" && path == "/api/kpm/policy" -> handleKpmPolicy(body)
            method == "POST" && path == "/api/kpm/action" -> handleKpmAction(body)
            method == "POST" && path == "/api/kpm/exclude" -> handleKpmExclude(body)
            method == "POST" && path == KPM_IMPORT_PATH -> kpmImportResponse(parameters, bodyBytes)
            method == "GET" && path == "/api/diagnostics" -> HttpResponse(200, diagnosticsJson())
            method == "GET" && path == "/api/webui/module-info" ->
                webUiModuleInfoResponse(parameters["module"])
            method == "GET" && path == "/api/webui/packages" -> webUiPackagesResponse(
                moduleId = parameters["module"],
                forceRefresh = parameters["refresh"] == "1",
            )
            method == "GET" && jobApi != null && jobApi.action == null ->
                jobSnapshotResponse(jobApi.jobId, parameters)
            method == "GET" && moduleApi?.action == "icon" -> moduleIconResponse(moduleApi.moduleId)
            method == "POST" && path == "/api/webui/exec" ->
                webUiExecResponse(parameters["module"], body)
            method == "POST" && path == "/api/webui/packages-info" -> webUiPackagesInfoResponse(
                moduleId = parameters["module"],
                body = body,
            )
            method == "POST" && path == "/api/settings/auto-start" -> handleAutoStartAction(body)
            method == "POST" && path == "/api/stealth" -> handleStealthAction(body)
            method == "POST" && path == "/api/stealth/disable" -> handleStealthDisable(body)
            method == "POST" && path == "/api/dynamic-manager" -> handleDynamicManagerAction(body)
            method == "POST" && path == "/api/settings/cache" -> invalidateCacheResponse()
            method == "POST" && jobApi?.action == "cancel" -> cancelJobResponse(jobApi.jobId)
            method == "POST" && moduleApi?.action == "action" -> startModuleAction(moduleApi.moduleId)
            method == "POST" && moduleApi != null -> handleModuleAction(moduleApi)
            method == "POST" && path.startsWith("/api/superuser/") -> handleSuperUserAction(path)
            method == "POST" -> HttpResponse(404, jsonError("endpoint not found", "endpoint_not_found"))
            else -> HttpResponse(405, jsonError("method not allowed", "method_not_allowed"))
        }
    }

    // ----------------------------------------------------------------- modules

    private data class ModuleSnapshot(val modules: List<JSONObject>, val cachedAt: Long)

    private fun readModuleList(forceRefresh: Boolean = false): Result<List<JSONObject>> {
        val now = SystemClock.elapsedRealtime()
        synchronized(moduleSnapshotLock) {
            val cached = cachedModuleSnapshot
            if (!forceRefresh && cached != null && now - cached.cachedAt < MODULE_SNAPSHOT_TTL_MILLIS) {
                return Result.success(cached.modules)
            }

            val started = SystemClock.elapsedRealtime()
            val queried = runWithDeadline(MODULE_QUERY_DEADLINE_MILLIS, "modules") { queryModuleList() }
            if (queried != null) {
                cachedModuleSnapshot = ModuleSnapshot(queried, now)
                diagnostics.info(
                    "modules",
                    "${queried.size} modules in ${SystemClock.elapsedRealtime() - started}ms",
                )
                return Result.success(queried)
            }
            if (cached != null) {
                diagnostics.warn("modules", "query failed, serving stale snapshot")
                return Result.success(cached.modules)
            }
            return Result.failure(IllegalStateException("module list query failed"))
        }
    }

    private fun queryModuleList(): List<JSONObject> {
        check(rootAvailable()) { "root shell unavailable" }
        val rawJson = runBlocking { listModulesWithTimeout() }
        val array = JSONArray(rawJson)
        return (0 until array.length()).mapNotNull { index -> array.optJSONObject(index) }
            .filter { module ->
                val id = module.optString("id").trim()
                id.isNotBlank() && !isManagerHiddenModuleId(id)
            }
    }

    private fun moduleJson(module: JSONObject): JSONObject {
        val id = module.optString("id").trim()
        val moduleDir = "$MODULES_ROOT/$id"
        val icon = module.optString("webuiIcon").trim()
            .takeIf { WebManagerRoutes.isModuleIconPath(moduleDir, it) }
        val actionIcon = module.optString("actionIcon").trim()
            .takeIf { WebManagerRoutes.isModuleIconPath(moduleDir, it) }
        return JSONObject()
            .put("id", id)
            .put("name", module.optString("name").ifBlank { id })
            .put("version", module.optString("version").ifBlank { "未知版本" })
            .put("author", module.optString("author").ifBlank { "" })
            .put("description", module.optString("description").ifBlank { "" })
            .put("enabled", module.optBoolean("enabled", true))
            .put("remove", module.optBoolean("remove", false))
            .put("update", module.optBoolean("update", false))
            .put("metamodule", module.optInt("metamodule", 0) != 0 ||
                module.optBoolean("metamodule", false))
            // Normal modules expose the WebUI flag as "web"; the KPatch-Next
            // status adapter uses "webui". Accept both so a valid WebUI is never
            // hidden from the browser manager.
            .put("webui", module.optBoolean("webui", false) || module.optBoolean("web", false))
            .put("action", module.optBoolean("action", false))
            .put("hasIcon", icon != null)
            .put("hasActionIcon", actionIcon != null)
            .put("actionJobId", jobs.runningJobId(id))
    }

    private fun modulesResponse(forceRefresh: Boolean): HttpResponse {
        val started = SystemClock.elapsedRealtime()
        val source = readModuleList(forceRefresh).getOrElse {
            return errorResponse(
                status = 503,
                code = "module_query_failed",
                message = "无法读取模块列表，请确认 Root 服务可用",
                detail = it,
            )
        }
        val result = JSONArray()
        source.forEach { module -> result.put(moduleJson(module)) }
        return HttpResponse(
            status = 200,
            body = JSONObject()
                .put("modules", result)
                .put("count", result.length())
                .put("queryMillis", SystemClock.elapsedRealtime() - started)
                .toString(),
        )
    }

    private fun findModule(moduleId: String): JSONObject? = readModuleList().getOrNull()
        ?.firstOrNull { it.optString("id").trim() == moduleId }

    private fun handleModuleAction(action: WebManagerRoutes.ModuleApiPath): HttpResponse {
        if (isManagerHiddenModuleId(action.moduleId)) {
            return HttpResponse(400, jsonError("module cannot be managed", "module_cannot_be_managed"))
        }
        val success = when (action.action) {
            "enable" -> toggleModule(action.moduleId, true)
            "disable" -> toggleModule(action.moduleId, false)
            "uninstall" -> uninstallModule(action.moduleId)
            "undo-uninstall" -> undoUninstallModule(action.moduleId)
            else -> return HttpResponse(404, jsonError("unknown module action", "endpoint_not_found"))
        }
        invalidateModuleSnapshot()
        return if (success) {
            HttpResponse(
                200,
                JSONObject()
                    .put("ok", true)
                    .put("action", action.action)
                    .put("moduleId", action.moduleId)
                    .toString(),
            )
        } else {
            errorResponse(500, "module_operation_failed", "模块操作失败，请检查 Root 服务")
        }
    }

    private fun startModuleAction(moduleId: String): HttpResponse {
        if (isManagerHiddenModuleId(moduleId)) {
            return HttpResponse(400, jsonError("module cannot be managed", "module_cannot_be_managed"))
        }
        val module = findModule(moduleId) ?: run {
            diagnostics.warn("action", "module not found for action: $moduleId")
            return errorResponse(404, "module_not_found", "模块不存在或尚未加载")
        }
        if (module.optBoolean("remove", false)) {
            return errorResponse(409, "module_unavailable", "模块已标记卸载，无法执行脚本")
        }
        if (!module.optBoolean("enabled", true)) {
            return errorResponse(409, "module_unavailable", "模块未启用，无法执行脚本")
        }
        if (!module.optBoolean("action", false) && !hasActionScript(moduleId)) {
            diagnostics.warn("action", "no action.sh for $moduleId")
            return errorResponse(404, "action_script_missing", "该模块没有 action.sh")
        }
        val jobId = jobs.start(moduleId, SystemClock.elapsedRealtime())
            ?: return errorResponse(409, "action_already_running", "该模块的脚本正在执行中")
        diagnostics.info("action", "started action for $moduleId (job=$jobId)")
        runActionJob(jobId, moduleId)
        return HttpResponse(
            202,
            JSONObject()
                .put("jobId", jobId)
                .put("moduleId", moduleId)
                .put("running", true)
                .toString(),
        )
    }

    private fun hasActionScript(moduleId: String): Boolean {
        val shell = rootShellForRead() ?: return false
        return try {
            SuFile("$MODULES_ROOT/$moduleId/action.sh").apply { setShell(shell) }.isFile
        } catch (error: Throwable) {
            diagnostics.error("action", "action.sh probe failed for $moduleId", error)
            false
        } finally {
            runCatching { shell.close() }
        }
    }

    /**
     * Runs `ksud module action <id>` on a dedicated thread and streams the output
     * into the job store so the browser can poll it. Cancelling closes the shell,
     * which kills the running script.
     */
    private fun runActionJob(jobId: String, moduleId: String) {
        Thread({
            var shell: Shell? = null
            try {
                shell = rootShellForExec()
                if (shell == null) {
                    jobs.append(jobId, "无法获取 root shell，无法执行模块脚本\n")
                    jobs.finish(jobId, 126, cancelled = false, nowMillis = SystemClock.elapsedRealtime())
                    return@Thread
                }
                synchronized(actionShellsLock) { activeActionShells[jobId] = shell }
                if (!jobs.isRunning(jobId)) return@Thread

                val stdout = appendCallback(jobId)
                val stderr = appendCallback(jobId)
                val result = shell.newJob()
                    .add("${shellQuote(ksuDaemonPath())} module action ${shellQuote(moduleId)}")
                    .to(stdout, stderr)
                    .exec()
                if (result.code != 0) {
                    diagnostics.error(
                        "action",
                        "$moduleId exited with ${result.code}: ${result.err.joinToString(" ").take(120)}",
                    )
                } else {
                    diagnostics.info("action", "$moduleId action finished")
                }
                jobs.finish(
                    jobId,
                    result.code,
                    cancelled = false,
                    nowMillis = SystemClock.elapsedRealtime(),
                )
            } catch (error: Throwable) {
                Log.e(TAG, "module action failed: $moduleId", error)
                diagnostics.error("action", "module action failed: $moduleId", error)
                jobs.append(jobId, (error.message ?: "action failed") + "\n")
                jobs.finish(jobId, 126, cancelled = false, nowMillis = SystemClock.elapsedRealtime())
            } finally {
                synchronized(actionShellsLock) { activeActionShells.remove(jobId) }
                runCatching { shell?.close() }
                jobs.prune(SystemClock.elapsedRealtime())
            }
        }, "ApkeSU-WebManager-Action").apply {
            isDaemon = true
            start()
        }
    }

    private fun appendCallback(jobId: String): CallbackList<String?> =
        object : CallbackList<String?>() {
            override fun onAddElement(s: String?) {
                jobs.append(jobId, (s ?: "") + "\n")
            }
        }

    private fun jobSnapshotResponse(jobId: String, parameters: Map<String, String>): HttpResponse {
        val snapshot = jobs.snapshot(jobId)
            ?: return errorResponse(404, "job_not_found", "执行任务不存在或已过期")
        val totalLength = jobs.producedLength(jobId)
        val offset = parameters["offset"]?.toIntOrNull()?.coerceIn(0, totalLength) ?: 0
        val delta = jobs.snapshot(jobId, offset) ?: snapshot
        return HttpResponse(
            200,
            JSONObject()
                .put("id", snapshot.id)
                .put("moduleId", snapshot.moduleId)
                .put("state", snapshot.state.name.lowercase(Locale.ROOT))
                .put("running", snapshot.running)
                .put("exitCode", snapshot.exitCode ?: JSONObject.NULL)
                .put("output", delta.output)
                .put("offset", offset + delta.output.length)
                .put("totalLength", totalLength)
                .put("truncated", snapshot.truncated)
                .put("startedAtMillis", snapshot.startedAtMillis)
                .put("finishedAtMillis", snapshot.finishedAtMillis ?: JSONObject.NULL)
                .toString(),
        )
    }

    private fun cancelJobResponse(jobId: String): HttpResponse {
        val cancelled = jobs.cancel(jobId, SystemClock.elapsedRealtime())
        synchronized(actionShellsLock) { activeActionShells[jobId] }?.let { shell ->
            runCatching { shell.close() }
        }
        return if (cancelled) {
            HttpResponse(200, JSONObject().put("ok", true).put("cancelled", true).toString())
        } else {
            errorResponse(409, "job_not_running", "该任务已结束")
        }
    }

    private fun moduleIconResponse(moduleId: String): HttpResponse {
        if (isManagerHiddenModuleId(moduleId)) {
            return HttpResponse(404, jsonError("module icon unavailable"))
        }
        val module = findModule(moduleId) ?: return HttpResponse(404, jsonError("module icon unavailable"))
        val iconPath = module.optString("webuiIcon").trim()
            .takeIf { WebManagerRoutes.isModuleIconPath("$MODULES_ROOT/$moduleId", it) }
            ?: return HttpResponse(404, jsonError("module icon unavailable"))
        val shell = rootShellForRead() ?: return HttpResponse(404, jsonError("module icon unavailable"))
        return try {
            val file = SuFile(iconPath).apply { setShell(shell) }
            if (!file.isFile || file.length() > MAX_ICON_BYTES) {
                shell.close()
                return HttpResponse(404, jsonError("module icon unavailable"))
            }
            HttpResponse(
                status = 200,
                contentType = SuFilePathHandler.guessMimeType(iconPath),
                stream = StreamBody(SuFilePathHandler.openFile(File(iconPath), shell), file.length(), shell),
            )
        } catch (error: Throwable) {
            runCatching { shell.close() }
            Log.e(TAG, "failed to read module icon: $moduleId", error)
            HttpResponse(404, jsonError("module icon unavailable"))
        }
    }

    // ------------------------------------------------------------- superuser

    private data class SuperUserEntry(
        val uid: Int,
        val packageName: String,
        val label: String,
        val profileKeys: List<String>,
        val allowSu: Boolean,
        val customProfile: Boolean,
        val appCount: Int,
        val sharedUid: Boolean,
        val isSystem: Boolean,
    ) {
        val manageable: Boolean get() = uid >= 2000 || uid == 1000

        fun toJson(): JSONObject = JSONObject()
            .put("uid", uid)
            .put("userId", uid / 100000)
            .put("label", label)
            .put("packageName", packageName)
            .put("allowSu", allowSu)
            .put("customProfile", customProfile)
            .put("appCount", appCount)
            .put("sharedUid", sharedUid)
            .put("manageable", manageable)
            .put("isSystem", isSystem)
    }

    private data class SuperUserSnapshot(
        val entries: List<SuperUserEntry>,
        val source: String,
        val totalApps: Int,
        val cachedAt: Long,
    )

    private data class LocalPackage(
        val packageName: String,
        val label: String,
        val uid: Int,
        val isSystem: Boolean,
    )

    /**
     * 应用列表：**并行**跑两条路径后取结果。
     *
     * 1. `root`：`SuperUserRepository.getAppList()`（Root 服务 / binder，可能长时间卡住）
     * 2. `local`：PackageManager + 内核允许列表（无 root、无 binder，通常几百毫秒）
     *
     * Root 路径只在 [ROOT_APP_WAIT_MILLIS] 内等待；超时就先给本地快照（页面立刻可用），
     * 同时让仍在后台跑的 Root 查询在完成时把缓存升级成 root 来源，下次刷新即用 Root 数据。
     */
    private fun readSuperUserSnapshot(forceRefresh: Boolean): Result<SuperUserSnapshot> {
        val now = SystemClock.elapsedRealtime()
        synchronized(appSnapshotLock) {
            val cached = cachedAppSnapshot
            if (!forceRefresh && cached != null && now - cached.cachedAt < APP_SNAPSHOT_TTL_MILLIS) {
                return Result.success(cached)
            }

            val started = SystemClock.elapsedRealtime()
            val rootFuture = appQueryExecutor.submit(Callable { queryRootSnapshot(now) })
            val localFuture = appQueryExecutor.submit(Callable { queryLocalSnapshot(now) })

            val localSnapshot = awaitSnapshot(localFuture, LOCAL_APP_WAIT_MILLIS)
            val rootSnapshot = awaitSnapshot(
                rootFuture,
                if (localSnapshot == null) ROOT_APP_WAIT_MILLIS + LOCAL_APP_WAIT_MILLIS else ROOT_APP_WAIT_MILLIS,
            )
            if (localSnapshot != null || rootSnapshot != null) {
                val selected = when {
                    localSnapshot != null && rootSnapshot != null -> mergeSuperUserSnapshots(localSnapshot, rootSnapshot)
                    localSnapshot != null -> localSnapshot
                    else -> rootSnapshot!!
                }
                cachedAppSnapshot = selected
                diagnostics.info(
                    "apps",
                    "${selected.source} snapshot: ${selected.entries.size} uids in " +
                        "${SystemClock.elapsedRealtime() - started}ms",
                )
                if (localSnapshot != null && rootSnapshot == null) {
                    upgradeAppSnapshotWhenRootCompletes(rootFuture, localSnapshot)
                }
                return Result.success(selected)
            }

            diagnostics.error("apps", "no packages available from root or PackageManager")
            return Result.failure(
                IllegalStateException("root query and PackageManager returned no packages"),
            )
        }
    }

    private fun mergeSuperUserSnapshots(
        local: SuperUserSnapshot,
        root: SuperUserSnapshot,
    ): SuperUserSnapshot {
        val localByUid = local.entries.associateBy { it.uid }
        val rootByUid = root.entries.associateBy { it.uid }
        val entries = (localByUid.keys + rootByUid.keys).map { uid ->
            val localEntry = localByUid[uid]
            val rootEntry = rootByUid[uid]
            val primary = rootEntry ?: localEntry!!
            val variants = listOfNotNull(localEntry, rootEntry)
            SuperUserEntry(
                uid = uid,
                packageName = primary.packageName,
                label = primary.label,
                profileKeys = variants.flatMap { it.profileKeys }.distinct(),
                allowSu = variants.any { it.allowSu },
                customProfile = variants.any { it.customProfile },
                appCount = maxOf(localEntry?.appCount ?: 0, rootEntry?.appCount ?: 0),
                sharedUid = variants.any { it.sharedUid },
                isSystem = rootEntry?.isSystem ?: localEntry?.isSystem ?: false,
            )
        }.sortedBy { it.label.lowercase(Locale.ROOT) }
        return SuperUserSnapshot(
            entries = entries,
            source = "root+local",
            totalApps = maxOf(local.totalApps, root.totalApps),
            cachedAt = maxOf(local.cachedAt, root.cachedAt),
        )
    }

    private fun upgradeAppSnapshotWhenRootCompletes(
        rootFuture: java.util.concurrent.Future<SuperUserSnapshot?>,
        localSnapshot: SuperUserSnapshot,
    ) {
        Thread({
            val rootSnapshot = runCatching {
                rootFuture.get(LOCAL_APP_WAIT_MILLIS + ROOT_APP_WAIT_MILLIS, TimeUnit.MILLISECONDS)
            }.getOrNull() ?: return@Thread
            val merged = mergeSuperUserSnapshots(localSnapshot, rootSnapshot)
            synchronized(appSnapshotLock) {
                val current = cachedAppSnapshot
                if (current == null || current.cachedAt <= merged.cachedAt) {
                    cachedAppSnapshot = merged
                    diagnostics.info("apps", "root snapshot arrived late, cache merged")
                }
            }
        }, "ApkeSU-WebManager-AppsUpgrade").apply {
            isDaemon = true
            start()
        }
    }

    private fun <T> awaitSnapshot(future: java.util.concurrent.Future<T>, timeoutMillis: Long): T? = try {
        future.get(timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (error: Throwable) {
        if (error is InterruptedException) Thread.currentThread().interrupt()
        null
    }

    private fun queryRootSnapshot(now: Long): SuperUserSnapshot? = runCatching {
        val result = runBlocking { superUserRepository.getAppList() }.getOrNull() ?: return null
        val apps = result.first.filter { app -> !app.special && app.packageName != ksuApp.packageName }
        if (apps.isEmpty()) return null
        SuperUserSnapshot(
            entries = groupApps(apps),
            source = "root",
            totalApps = apps.size,
            cachedAt = now,
        )
    }.getOrNull()

    /** 完全不依赖 root 的快照：安装包 + 内核允许列表，卡住的 Root 服务也影响不到它。 */
    private fun queryLocalSnapshot(now: Long): SuperUserSnapshot? = runCatching {
        val localPackages = readLocalPackages()
        if (localPackages.isEmpty()) return null
        val allowedUids = readAllowedUids()
        SuperUserSnapshot(
            entries = groupLocalPackages(localPackages, allowedUids),
            source = "local",
            totalApps = localPackages.size,
            cachedAt = now,
        )
    }.getOrNull()

    private fun groupApps(apps: List<AppInfo>): List<SuperUserEntry> = collapseByUid(
        apps.mapNotNull { app ->
            val applicationInfo = app.packageInfo.applicationInfo ?: return@mapNotNull null
            val packageName = app.packageInfo.packageName.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            SuperUserEntry(
                uid = app.uid,
                packageName = packageName,
                label = app.label.ifBlank { packageName },
                profileKeys = listOf(app.profileKey),
                allowSu = app.allowSu,
                customProfile = app.hasCustomProfile,
                appCount = 1,
                sharedUid = false,
                isSystem = (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            )
        },
    )

    private fun groupLocalPackages(
        packages: List<LocalPackage>,
        allowedUids: Set<Int>,
    ): List<SuperUserEntry> = collapseByUid(
        packages.map { record ->
            SuperUserEntry(
                uid = record.uid,
                packageName = record.packageName,
                label = record.label,
                profileKeys = listOf(record.packageName),
                allowSu = record.uid in allowedUids,
                customProfile = false,
                appCount = 1,
                sharedUid = false,
                isSystem = record.isSystem,
            )
        },
    )

    private fun collapseByUid(entries: List<SuperUserEntry>): List<SuperUserEntry> =
        entries.groupBy { it.uid }
            .map { (uid, grouped) ->
                val primary = grouped.sortedWith(
                    compareByDescending<SuperUserEntry> { it.allowSu }
                        .thenBy { it.label.lowercase(Locale.ROOT) },
                ).first()
                SuperUserEntry(
                    uid = uid,
                    packageName = primary.packageName,
                    label = primary.label,
                    profileKeys = grouped.flatMap { it.profileKeys }.distinct(),
                    allowSu = grouped.any { it.allowSu },
                    customProfile = grouped.any { it.customProfile },
                    appCount = grouped.size,
                    sharedUid = grouped.size > 1,
                    isSystem = primary.isSystem,
                )
            }
            .sortedBy { it.label.lowercase(Locale.ROOT) }

    private fun readLocalPackages(): List<LocalPackage> {
        val packageManager = ksuApp.packageManager
        val packages = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledPackages(0)
            }
        }.getOrElse {
            Log.w(TAG, "local package query failed", it)
            return emptyList()
        }
        return packages.mapNotNull { packageInfo ->
            val applicationInfo = packageInfo.applicationInfo ?: return@mapNotNull null
            if ((applicationInfo.flags and ApplicationInfo.FLAG_HAS_CODE) == 0) return@mapNotNull null
            val packageName = packageInfo.packageName.takeIf(String::isNotBlank) ?: return@mapNotNull null
            if (packageName == ksuApp.packageName) return@mapNotNull null
            LocalPackage(
                packageName = packageName,
                label = runCatching { applicationInfo.loadLabel(packageManager).toString() }
                    .getOrDefault(packageName),
                uid = applicationInfo.uid,
                isSystem = (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            )
        }
    }

    private fun readAllowedUids(): Set<Int> = runCatching {
        Natives.getAllowList().toSet()
    }.getOrElse {
        Log.w(TAG, "failed to read allow list", it)
        emptySet()
    }

    private fun superUsersResponse(forceRefresh: Boolean): HttpResponse {
        val started = SystemClock.elapsedRealtime()
        val snapshot = readSuperUserSnapshot(forceRefresh).getOrElse { error ->
            Log.e(TAG, "superuser query failed", error)
            return errorResponse(
                status = 503,
                code = "superuser_query_failed",
                message = "无法读取应用列表，请确认 Root 服务可用",
                detail = error,
            )
        }
        val result = JSONArray()
        var authorizedCount = 0
        var sharedUidCount = 0
        snapshot.entries.forEach { entry ->
            if (entry.allowSu) authorizedCount++
            if (entry.sharedUid) sharedUidCount++
            result.put(entry.toJson())
        }
        return HttpResponse(
            status = 200,
            body = JSONObject()
                .put("apps", result)
                .put("totalApps", snapshot.totalApps)
                .put("uidCount", result.length())
                .put("authorizedCount", authorizedCount)
                .put("sharedUidCount", sharedUidCount)
                .put("source", snapshot.source)
                .put("stale", false)
                .put("queryMillis", SystemClock.elapsedRealtime() - started)
                .toString(),
        )
    }

    /**
     * Counters only, for the home page card: the browser does not need the full
     * app list to show how many UIDs are authorized, and this keeps the home
     * request small.
     */
    private fun superUserSummaryResponse(forceRefresh: Boolean): HttpResponse {
        val started = SystemClock.elapsedRealtime()
        val snapshot = readSuperUserSnapshot(forceRefresh).getOrElse { error ->
            return errorResponse(
                status = 503,
                code = "superuser_query_failed",
                message = "无法读取应用列表，请确认 Root 服务可用",
                detail = error,
            )
        }
        return HttpResponse(
            status = 200,
            body = JSONObject()
                .put("totalApps", snapshot.totalApps)
                .put("uidCount", snapshot.entries.size)
                .put("authorizedCount", snapshot.entries.count { it.allowSu })
                .put("sharedUidCount", snapshot.entries.count { it.sharedUid })
                .put("source", snapshot.source)
                .put("queryMillis", SystemClock.elapsedRealtime() - started)
                .toString(),
        )
    }

    private fun handleSuperUserAction(path: String): HttpResponse {
        val parts = path.split('/').filter(String::isNotEmpty)
        if (parts.size != 4 || parts[0] != "api" || parts[1] != "superuser") {
            return errorResponse(404, "endpoint_not_found", "endpoint not found")
        }
        val uid = parts[2].toIntOrNull()
            ?: return errorResponse(400, "invalid_uid", "invalid uid")
        if (uid < 2000 && uid != 1000) {
            return errorResponse(403, "uid_not_manageable", "this uid cannot be granted root")
        }
        val allowSu = when (parts[3]) {
            "grant" -> true
            "revoke" -> false
            else -> return errorResponse(404, "unknown_action", "unknown superuser action")
        }

        val snapshot = readSuperUserSnapshot(forceRefresh = true).getOrElse { error ->
            return errorResponse(
                status = 503,
                code = "superuser_query_failed",
                message = "无法读取应用列表，请确认 Root 服务可用",
                detail = error,
            )
        }
        val entry = snapshot.entries.firstOrNull { it.uid == uid }
            ?: return errorResponse(404, "uid_not_found", "uid not found")

        var managerRegistered = false
        var updatedCount = 0
        entry.profileKeys.forEach { profileKey ->
            val current = runCatching { Natives.getAppProfile(profileKey, uid) }.getOrNull()
                ?: Natives.Profile(profileKey, uid)
            val profile = current.copy(currentUid = uid, allowSu = allowSu)
            val updated = if (runCatching { Natives.setAppProfile(profile) }.getOrDefault(false)) {
                true
            } else {
                if (!managerRegistered) managerRegistered = ensureManagerRegistered()
                managerRegistered && runCatching { Natives.setAppProfile(profile) }.getOrDefault(false)
            }
            if (updated) updatedCount++
        }
        synchronized(appSnapshotLock) { cachedAppSnapshot = null }
        val verified = updatedCount == entry.profileKeys.size && runCatching {
            Natives.getAllowList().contains(uid) == allowSu
        }.getOrDefault(false)
        return if (verified) {
            startWarmUp()
            HttpResponse(
                200,
                JSONObject()
                    .put("ok", true)
                    .put("allowSu", allowSu)
                    .put("updatedCount", updatedCount)
                    .toString(),
            )
        } else if (updatedCount != entry.profileKeys.size) {
            errorResponse(
                status = 500,
                code = "profile_update_failed",
                message = "超级用户配置更新失败",
                detail = "updated $updatedCount of ${entry.profileKeys.size}",
            )
        } else {
            errorResponse(
                status = 409,
                code = "profile_verification_failed",
                message = "内核未保存新的 Root 授权状态",
            )
        }
    }

    // ------------------------------------------------------------------ status

    private fun statusJson(): String {
        val started = SystemClock.elapsedRealtime()
        val moduleResult = readModuleList()
        val modules = moduleResult.getOrNull().orEmpty()
        var enabledModuleCount = 0
        var pendingModuleCount = 0
        var webUiModuleCount = 0
        modules.forEach { module ->
            if (module.optBoolean("remove", false)) {
                pendingModuleCount++
            } else if (module.optBoolean("enabled", true)) {
                enabledModuleCount++
            }
            if (module.optBoolean("webui", false) || module.optBoolean("web", false)) {
                webUiModuleCount++
            }
        }
        val response = JSONObject()
            .put("apiVersion", API_VERSION)
            .put("port", boundPort)
            .put("loopback", true)
            .put("serverRunning", isRunning())
            .put("uptimeSeconds", uptimeSeconds())
            .put("root", runCatching { rootAvailable() }.getOrDefault(false))
            .put("moduleQueryOk", moduleResult.isSuccess)
            .put("queryMillis", SystemClock.elapsedRealtime() - started)
            .put("webUiModuleCount", webUiModuleCount)
            .put("hasRunningAction", jobs.hasRunningJob())
        if (moduleResult.isSuccess) {
            val visibleModuleCount = modules.size
            response
                .put("moduleCount", visibleModuleCount)
                .put("moduleEnabledCount", enabledModuleCount)
                .put("moduleDisabledCount", visibleModuleCount - enabledModuleCount - pendingModuleCount)
                .put("modulePendingCount", pendingModuleCount)
        } else {
            response
                .put("moduleErrorCode", "module_query_failed")
                .put("moduleError", moduleResult.exceptionOrNull()?.message ?: "module list failed")
        }
        synchronized(appSnapshotLock) { cachedAppSnapshot }?.let { snapshot ->
            response
                .put("appCount", snapshot.totalApps)
                .put("uidCount", snapshot.entries.size)
                .put("authorizedCount", snapshot.entries.count { it.allowSu })
                .put("appSource", snapshot.source)
        }
        return response.toString()
    }

    // --------------------------------------------------------------------- kpm

    /**
     * 与原生「KPM 管理」页同一套原理：能力与列表都来自 `ksud kpm …`，
     * 策略、启用、加载、控制、删除、排除名单逐一对应，导入同样先落到缓存文件再交给 ksud。
     */
    private class KpmSnapshot(
        val caps: KpmCaps,
        val entries: List<KpmEntry>,
        val excluded: List<KpmExcludedApp>,
        val error: String,
    )

    private suspend fun readKpmSnapshot(): KpmSnapshot {
        val caps = getKpmCaps()
        if (caps.error.isNotBlank()) {
            return KpmSnapshot(caps, emptyList(), emptyList(), caps.error)
        }
        // 越狱/晚加载模式与不可用的后端都不会有可管理的条目
        if (caps.lateLoad || !caps.managementAvailable) {
            return KpmSnapshot(caps, emptyList(), emptyList(), "")
        }
        val listResult = getKpmList()
        if (!listResult.success) {
            return KpmSnapshot(
                caps,
                emptyList(),
                emptyList(),
                listResult.error.ifBlank { "无法读取 KPM 列表" },
            )
        }
        val entries = runCatching { parseKpmEntries(listResult.output) }.getOrElse { error ->
            return KpmSnapshot(caps, emptyList(), emptyList(), "KPM 列表解析失败：${error.message.orEmpty()}")
        }
        val excluded = if (caps.backend == "kpatch-next") {
            runCatching { getKpmExcludedApps() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        return KpmSnapshot(caps, entries, excluded, "")
    }

    private fun kpmCapsJson(caps: KpmCaps): JSONObject = JSONObject()
        .put("backend", caps.backend)
        .put("managementAvailable", caps.managementAvailable)
        .put("supported", caps.supported)
        .put("kernelSupported", caps.kernelSupported)
        .put("loaderReady", caps.loaderReady)
        .put("policyEnabled", caps.policyEnabled)
        .put("lateLoad", caps.lateLoad)
        .put("abiVersion", caps.abiVersion)
        .put("capabilities", caps.capabilities)
        .put("maxImageSize", caps.maxImageSize)
        .put("maxLoaded", caps.maxLoaded)
        .put("disabledReason", caps.disabledReason)
        .put("error", caps.error)

    private fun kpmEntryJson(entry: KpmEntry): JSONObject = JSONObject()
        .put("id", entry.id)
        .put("name", entry.name)
        .put("version", entry.version)
        .put("license", entry.license)
        .put("author", entry.author)
        .put("description", entry.description)
        .put("args", entry.args)
        .put("enabled", entry.enabled)
        .put("loaded", entry.loaded)
        .put("runtimeKnown", entry.runtimeKnown)
        .put("quarantined", entry.quarantined)
        .put("quarantineReason", entry.quarantineReason)
        .put("sourceName", entry.sourceName)
        .put("importedAt", entry.importedAt)
        .put("error", entry.error)

    private fun kpmResponse(): HttpResponse {
        val started = SystemClock.elapsedRealtime()
        val snapshot = runWithDeadline(KPM_QUERY_DEADLINE_MILLIS, "kpm") {
            runBlocking { readKpmSnapshot() }
        } ?: return errorResponse(504, "kpm_query_timeout", "读取 KPM 状态超时，请稍后重试")
        diagnostics.info(
            "kpm",
            "backend=${snapshot.caps.backend} entries=${snapshot.entries.size}" +
                " policy=${snapshot.caps.policyEnabled} error=${snapshot.error.ifBlank { "-" }}",
        )
        val entries = JSONArray()
        snapshot.entries.forEach { entries.put(kpmEntryJson(it)) }
        val excluded = JSONArray()
        snapshot.excluded.forEach {
            excluded.put(JSONObject().put("package", it.packageName).put("uid", it.uid))
        }
        return HttpResponse(
            200,
            JSONObject()
                .put("caps", kpmCapsJson(snapshot.caps))
                .put("entries", entries)
                .put("excluded", excluded)
                .put("error", snapshot.error)
                .put("apiVersion", API_VERSION)
                .put("queryMillis", SystemClock.elapsedRealtime() - started)
                .toString(),
        )
    }

    /** KPM 命令的统一回包：成功与否都带上输出与错误文本，前端直接展示。 */
    private fun kpmResultResponse(result: KpmCommandResult): HttpResponse = HttpResponse(
        status = 200,
        body = JSONObject()
            .put("success", result.success)
            .put("output", result.output)
            .put("error", result.error)
            .toString(),
    )

    private fun handleKpmPolicy(body: String): HttpResponse {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return errorResponse(400, "invalid_body", "请求体不是合法 JSON")
        val enabled = json.optBoolean("enabled", false)
        val result = runWithDeadline(KPM_ACTION_DEADLINE_MILLIS, "kpm-policy") {
            runBlocking { setKpmPolicy(enabled) }
        } ?: return errorResponse(504, "kpm_action_timeout", "KPM 开关操作超时")
        diagnostics.info("kpm", "policy ${if (enabled) "enable" else "disable"} -> ${result.success}")
        return kpmResultResponse(result)
    }

    private fun handleKpmAction(body: String): HttpResponse {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return errorResponse(400, "invalid_body", "请求体不是合法 JSON")
        val action = json.optString("action").trim().lowercase()
        val id = json.optString("id").trim()
        val args = json.optString("args")
        if (action !in KPM_ACTIONS) {
            return errorResponse(400, "unknown_kpm_action", "未知的 KPM 操作")
        }
        if (id.isBlank() || !WebManagerSecurity.isValidModuleId(id)) {
            return errorResponse(400, "invalid_kpm_id", "KPM ID 不合法")
        }
        val result = runWithDeadline(KPM_ACTION_DEADLINE_MILLIS, "kpm-$action") {
            runBlocking {
                when (action) {
                    "enable" -> setKpmEnabled(id, true)
                    "disable" -> setKpmEnabled(id, false)
                    "load" -> loadKpm(id)
                    "unload" -> unloadKpm(id)
                    "remove" -> removeKpm(id)
                    else -> controlKpm(id, args)
                }
            }
        } ?: return errorResponse(504, "kpm_action_timeout", "KPM 操作超时：$action")
        diagnostics.info("kpm", "$action $id -> ${result.success}")
        return kpmResultResponse(result)
    }

    private fun handleKpmExclude(body: String): HttpResponse {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return errorResponse(400, "invalid_body", "请求体不是合法 JSON")
        val packageName = json.optString("package").trim()
        val uid = json.optInt("uid", -1)
        val enabled = json.optBoolean("enabled", false)
        if (!packageNamePattern.matches(packageName)) {
            return errorResponse(400, "invalid_package", "包名不合法")
        }
        if (uid <= 0) {
            return errorResponse(400, "invalid_uid", "UID 不合法")
        }
        val result = runWithDeadline(KPM_ACTION_DEADLINE_MILLIS, "kpm-exclude") {
            runBlocking { setKpmAppExcluded(packageName, uid, enabled) }
        } ?: return errorResponse(504, "kpm_action_timeout", "排除应用超时")
        diagnostics.info("kpm", "exclude $packageName/$uid=$enabled -> ${result.success}")
        return kpmResultResponse(result)
    }

    /**
     * 浏览器把待导入的 KPM 原始字节 POST 过来，服务端先落缓存文件再交给 `ksud kpm import`，
     * 与原生页「复制到 cache 再导入」的做法一致；导入结束后立即删除临时文件。
     */
    private fun kpmImportResponse(parameters: Map<String, String>, bodyBytes: ByteArray): HttpResponse {
        if (bodyBytes.isEmpty()) {
            return errorResponse(400, "empty_upload", "没有收到文件内容")
        }
        val caps = runWithDeadline(KPM_QUERY_DEADLINE_MILLIS, "kpm-import-caps") {
            runBlocking { getKpmCaps() }
        } ?: return errorResponse(504, "kpm_query_timeout", "读取 KPM 状态超时")
        if (caps.error.isNotBlank()) {
            return errorResponse(409, "kpm_unavailable", caps.error)
        }
        if (!caps.managementAvailable || caps.lateLoad) {
            return errorResponse(409, "kpm_unavailable", "当前内核没有可用的 KPM 后端")
        }
        if (!caps.policyEnabled) {
            return errorResponse(409, "kpm_policy_disabled", "KPM 加载开关已关闭，请先打开再导入")
        }
        val limit = caps.maxImageSize.takeIf { it > 0 } ?: DEFAULT_KPM_MAX_IMAGE_BYTES
        if (bodyBytes.size.toLong() > limit) {
            return errorResponse(413, "kpm_file_too_large", "文件 ${bodyBytes.size} 字节，超过 KPM 上限 $limit 字节")
        }
        val fileName = WebManagerSecurity.sanitizeUploadName(parameters["name"])
            ?: "apkesu-kpm-${System.currentTimeMillis()}.kpm"
        val directory = File(ksuApp.cacheDir, "kpm-upload")
        if (!directory.exists() && !directory.mkdirs()) {
            return errorResponse(500, "cache_unavailable", "无法创建导入缓存目录")
        }
        val target = File(directory, fileName)
        val result = try {
            target.writeBytes(bodyBytes)
            runWithDeadline(KPM_ACTION_DEADLINE_MILLIS, "kpm-import") {
                runBlocking {
                    importKpm(
                        source = target,
                        args = parameters["args"].orEmpty(),
                        force = parameters["force"] == "1",
                        enable = parameters["enable"] == "1",
                    )
                }
            }
        } catch (error: Throwable) {
            diagnostics.error("kpm", "import failed", error)
            return errorResponse(500, "kpm_import_failed", "导入失败：${error.message.orEmpty()}")
        } finally {
            target.delete()
        }
        if (result == null) {
            return errorResponse(504, "kpm_action_timeout", "KPM 导入超时")
        }
        diagnostics.info("kpm", "import $fileName (${bodyBytes.size} bytes) -> ${result.success}")
        return kpmResultResponse(result)
    }

    // ------------------------------------------------------------ device info

    private data class DeviceSnapshot(val json: JSONObject, val cachedAt: Long)

    /**
     * Kernel (LKM/GKI) and device summary for the home page. `collectRootDiagnosticInfo`
     * spawns a root shell for the KMI/slot probes, so the result is cached and the
     * whole collection runs under the query deadline.
     */
    private fun deviceJson(forceRefresh: Boolean = false): String {
        val now = SystemClock.elapsedRealtime()
        val staleSnapshot = synchronized(deviceSnapshotLock) {
            val cached = cachedDeviceSnapshot
            if (!forceRefresh) {
                cached
                    ?.takeIf { now - it.cachedAt < DEVICE_SNAPSHOT_TTL_MILLIS }
                    ?.let { return it.json.toString() }
            }
            cached
        }

        val started = SystemClock.elapsedRealtime()
        val collected = runWithDeadline(DEVICE_QUERY_DEADLINE_MILLIS, "device") { loadDeviceJson() }
        val usingFallback = collected == null
        val base = collected
            ?: staleSnapshot?.json?.let { JSONObject(it.toString()) }
            ?: JSONObject()
                .put("kernel", JSONObject().put("mode", "unknown"))
                .put("device", JSONObject())
        val enriched = JSONObject(base.toString())
            .put("apiVersion", API_VERSION)
            .put("port", boundPort)
            .put("queryMillis", SystemClock.elapsedRealtime() - started)
            .put("stale", usingFallback && staleSnapshot != null)
            .put("errorCode", if (usingFallback) "device_query_unavailable" else JSONObject.NULL)
        if (collected != null) {
            synchronized(deviceSnapshotLock) {
                cachedDeviceSnapshot = DeviceSnapshot(enriched, SystemClock.elapsedRealtime())
            }
        }
        return enriched.toString()
    }

    private fun loadDeviceJson(): JSONObject {
        val info = runCatching { runBlocking { collectRootDiagnosticInfo() } }.getOrElse { error ->
            diagnostics.warn("device", "root diagnostics failed: ${error.message}")
            null
        }
        val kernel = JSONObject()
            .put("mode", info?.workMode ?: "unknown")
            .put("driverVersion", info?.driverVersion ?: 0)
            .put("kernelUapi", info?.kernelUapi ?: 0)
            .put("managerUapi", info?.managerUapi ?: 0)
            .put("kernelModuleLoaded", info?.kernelModuleLoaded ?: false)
            .put("ksuRootShell", info?.ksuRootShell ?: false)
            .put("kmi", info?.currentKmi.orEmpty())
            .put("slot", info?.currentSlot.orEmpty())
            .put("release", runCatching { Os.uname().release }.getOrDefault("unknown"))
        val device = JSONObject()
            .put("model", runCatching { resolveDeviceName() }.getOrDefault(Build.MODEL))
            .put("manufacturer", Build.MANUFACTURER)
            .put("androidRelease", Build.VERSION.RELEASE)
            .put("sdkInt", Build.VERSION.SDK_INT)
            .put("abi", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
            .put("managerVersionName", BuildConfig.VERSION_NAME)
            .put("managerVersionCode", BuildConfig.VERSION_CODE)
        // 工具链摘要：没有 KPM / SUSFS 的内核返回空串，前端据此隐藏对应行
        val kpm = runCatching { runBlocking { probeKpmSummary() } }.getOrElse { error ->
            diagnostics.warn("device", "kpm probe failed: ${error.message}")
            ""
        }
        val susfs = runCatching { runBlocking { probeSusfsSummary() } }.getOrElse { error ->
            diagnostics.warn("device", "susfs probe failed: ${error.message}")
            ""
        }
        diagnostics.info(
            "device",
            "mode=${info?.workMode ?: "unknown"} uapi=${info?.kernelUapi ?: 0}/${info?.managerUapi ?: 0}" +
                " kpm=${kpm.ifBlank { "-" }} susfs=${susfs.ifBlank { "-" }}",
        )
        return JSONObject()
            .put("kernel", kernel)
            .put("device", device)
            .put("kpm", kpm)
            .put("susfs", susfs)
    }

    /**
     * WebUI 打不开时的回包：浏览器导航（Accept: text/html）给可读说明页，
     * 资源请求保持 JSON，方便前端与模块脚本自行处理。
     */
    private fun webUiUnavailableResponse(
        moduleId: String,
        reason: String,
        status: Int,
        wantsHtml: Boolean,
    ): HttpResponse {
        if (!wantsHtml) return HttpResponse(status, jsonError(reason))
        val page = WEB_MANAGER_WEBUI_ERROR_PAGE
            .replace("__MODULE_ID__", htmlEscape(moduleId.ifBlank { "未知模块" }))
            .replace("__REASON__", htmlEscape(reason))
        return HttpResponse(status, page, "text/html; charset=utf-8")
    }

    private fun webUiAssetFailureReason(moduleId: String, relativePath: String, entryRequest: Boolean): String {
        val webRoot = "$MODULES_ROOT/$moduleId/webroot"
        val shell = rootShellForRead()
        if (shell == null) {
            return "root shell 不可用，无法读取 $webRoot/$relativePath；请在管理器里确认 Root 授权后重试。"
        }
        runCatching { shell.close() }
        return if (entryRequest) {
            "在 $webRoot 里找不到 $relativePath（模块未启用、缺少 webroot/ 目录或入口文件名不是 index.html）。"
        } else {
            "找不到模块资源：$webRoot/$relativePath"
        }
    }

    private fun htmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    // -------------------------------------------------- 功能开关（Root 与安全）

    /**
     * 与原生「设置 → Root 与权限 / 挂载与隐藏」同一套开关：状态来自
     * [SettingsRepository]（内核特性探测），写入同样走仓库，成功后回写持久化。
     */
    private class FeatureToggle(
        val key: String,
        val label: String,
        val summary: String,
        val status: suspend () -> String,
        val enabled: suspend () -> Boolean,
        val apply: (Boolean) -> Boolean,
        val rebootHint: String? = null,
        /** 设置页分类：root（Root 与权限）/ mount（挂载与隐藏）。 */
        val group: String = FEATURE_GROUP_ROOT,
    )

    private fun featureToggles(repo: SettingsRepository): List<FeatureToggle> = listOf(
        FeatureToggle(
            key = "suCompat",
            label = "su 兼容模式",
            summary = "允许应用通过 /system/bin/su 提权（sucompat）",
            status = { repo.getSuCompatStatus() },
            enabled = { repo.isSuEnabled() },
            apply = { repo.setSuEnabled(it) },
        ),
        FeatureToggle(
            key = "kernelUmount",
            label = "内核 umount 模块",
            summary = "默认对已授权应用卸载模块挂载（kernel umount）",
            status = { repo.getKernelUmountStatus() },
            enabled = { repo.isKernelUmountEnabled() },
            apply = { repo.setKernelUmountEnabled(it) },
            group = FEATURE_GROUP_MOUNT,
        ),
        FeatureToggle(
            key = "webviewUmount",
            label = "WebView 隔离 umount",
            summary = "对 WebView zygote 进程隐藏模块挂载",
            status = { repo.getWebViewZygoteUmountStatus() },
            enabled = { repo.isWebViewZygoteUmountEnabled() },
            apply = { repo.setWebViewZygoteUmountEnabled(it) },
            group = FEATURE_GROUP_MOUNT,
        ),
        FeatureToggle(
            key = "selinuxHide",
            label = "SELinux 隐藏",
            summary = "对指定进程隐藏 SELinux 策略改动痕迹",
            status = { repo.getSelinuxHideStatus() },
            enabled = { repo.isSelinuxHideEnabled() },
            apply = { repo.setSelinuxHideEnabled(it) > 0 },
        ),
        FeatureToggle(
            key = "sulog",
            label = "su 日志",
            summary = "记录 su 请求与授权结果，便于排查",
            status = { repo.getSulogStatus() },
            enabled = { repo.getSulogPersistValue() == 1L },
            apply = { repo.setSulogEnabled(it) },
        ),
        FeatureToggle(
            key = "adbRoot",
            label = "ADB root",
            summary = "允许 adb 以 root 身份运行（需重启生效）",
            status = { repo.getAdbRootStatus() },
            enabled = { repo.getAdbRootPersistValue() == 1L },
            apply = { repo.setAdbRootEnabled(it) },
            rebootHint = "重启生效",
        ),
        FeatureToggle(
            key = "avcSpoof",
            label = "AVC 伪装",
            summary = "屏蔽与模块相关的 AVC 拒绝日志",
            status = { repo.getAvcSpoofStatus() },
            enabled = { repo.isAvcSpoofEnabled() },
            apply = { repo.setAvcSpoofEnabled(it) },
        ),
        FeatureToggle(
            key = "defaultUmount",
            label = "默认卸载模块",
            summary = "新授权应用默认不挂载模块（可被单独覆盖）",
            status = { "supported" },
            enabled = { repo.isDefaultUmountModules() },
            apply = { repo.setDefaultUmountModules(it) },
            group = FEATURE_GROUP_MOUNT,
        ),
    )

    private fun featuresJson(): String {
        val repo = settingsRepository
        val array = JSONArray()
        for (toggle in featureToggles(repo)) {
            val status = runWithDeadline(FEATURE_QUERY_DEADLINE_MILLIS, "feature-${toggle.key}") {
                runBlocking { toggle.status() }
            } ?: FEATURE_STATUS_UNKNOWN.also {
                diagnostics.warn("features", "${toggle.key} status unavailable")
            }
            val enabled = runWithDeadline(FEATURE_QUERY_DEADLINE_MILLIS, "feature-state-${toggle.key}") {
                runBlocking { toggle.enabled() }
            } ?: false
            array.put(
                JSONObject()
                    .put("key", toggle.key)
                    .put("label", toggle.label)
                    .put("summary", toggle.summary)
                    .put("status", status)
                    .put("supported", status.equals("supported", ignoreCase = true))
                    .put("enabled", enabled)
                    .put("rebootHint", toggle.rebootHint ?: JSONObject.NULL)
                    .put("group", toggle.group)
            )
        }
        return JSONObject().put("features", array).toString()
    }

    private fun handleFeatureAction(body: String?): HttpResponse {
        val payload = runCatching { JSONObject(body.orEmpty()) }.getOrNull()
            ?: return errorResponse(400, "bad_request", "请求内容不是合法 JSON")
        val key = payload.optString("key", "")
        val toggle = featureToggles(settingsRepository).firstOrNull { it.key == key }
            ?: return errorResponse(404, "unknown_feature", "没有这个功能开关")
        val enabled = payload.optBoolean("enabled", false)
        val applied = runWithDeadline(FEATURE_ACTION_DEADLINE_MILLIS, "feature-set-${toggle.key}") {
            runBlocking { toggle.apply(enabled) }
        } ?: return errorResponse(
            500,
            "feature_failed",
            "${toggle.label} 写入失败或超时：内核可能不支持该特性",
        )
        if (!applied) {
            diagnostics.warn("features", "${toggle.key} -> $enabled rejected by repository")
            return errorResponse(
                500,
                "feature_apply_rejected",
                "${toggle.label} 未能写入：内核可能不支持该特性",
            )
        }
        runCatching { settingsRepository.execKsudFeatureSave() }
            .onFailure { error -> diagnostics.warn("features", "feature save failed: ${error.message}") }
        diagnostics.info("features", "${toggle.key} -> $enabled (applied=$applied)")
        return HttpResponse(
            200,
            JSONObject()
                .put("ok", true)
                .put("key", toggle.key)
                .put("enabled", enabled)
                .put("applied", applied)
                .put("rebootHint", toggle.rebootHint ?: JSONObject.NULL)
                .toString(),
        )
    }

    // -------------------------------------------------- 管理器自身（端口 / 桌面图标）

    /**
     * 管理器桌面图标状态：隐藏后桌面没有图标，管理器只能从网页控制台进入，
     * 但 APK 仍在、服务照常运行 —— 相当于「没有可见的管理器，网页管理器继续可用」。
     */
    private fun launcherJson(): JSONObject {
        val packageManager = ksuApp.packageManager
        val option = LauncherIconOption.fromValue(settingsRepository.launcherIcon)
        val anyEnabled = LauncherIconOption.entries.any { alias ->
            runCatching {
                packageManager.getComponentEnabledSetting(
                    ComponentName(ksuApp.packageName, alias.aliasClassName),
                ) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }.getOrDefault(false)
        }
        val hidden = WebManagerPreferences.isLauncherIconHidden(ksuApp) && !anyEnabled
        return JSONObject()
            .put("hidden", hidden)
            .put("option", option.value)
            .put("label", runCatching { ksuApp.getString(option.labelRes) }.getOrDefault(option.value))
    }

    private fun handleLauncherAction(body: String?): HttpResponse {
        val payload = runCatching { JSONObject(body.orEmpty()) }.getOrNull()
            ?: return errorResponse(400, "bad_request", "请求内容不是合法 JSON")
        val hidden = payload.optBoolean("hidden", false)
        val packageManager = ksuApp.packageManager
        val applied = runCatching {
            if (hidden) {
                LauncherIconOption.entries.forEach { alias ->
                    packageManager.setComponentEnabledSetting(
                        ComponentName(ksuApp.packageName, alias.aliasClassName),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP,
                    )
                }
                WebManagerPreferences.setLauncherIconHidden(ksuApp, true)
            } else {
                applyLauncherIcon(ksuApp, LauncherIconOption.fromValue(settingsRepository.launcherIcon))
                WebManagerPreferences.setLauncherIconHidden(ksuApp, false)
            }
            true
        }.onFailure { error ->
            diagnostics.error("launcher", "set hidden=$hidden failed: ${error.message}")
        }.getOrDefault(false)
        diagnostics.info("launcher", "hidden=$hidden applied=$applied")
        return if (applied) {
            HttpResponse(
                200,
                JSONObject().put("ok", true).put("hidden", hidden).put("launcher", launcherJson()).toString(),
            )
        } else {
            errorResponse(500, "launcher_failed", "桌面图标切换失败（可能被系统限制）")
        }
    }

    private fun handlePortAction(body: String?): HttpResponse {
        val payload = runCatching { JSONObject(body.orEmpty()) }.getOrNull()
            ?: return errorResponse(400, "bad_request", "请求内容不是合法 JSON")
        val mode = payload.optString("mode", "random")
        val requested = payload.optInt("port", 0)
        if (mode != "random" && mode != "fixed") {
            return errorResponse(400, "invalid_port_mode", "端口模式只能是 random 或 fixed")
        }
        if (mode == "fixed" && requested !in 1024..65535) {
            return errorResponse(400, "invalid_port", "固定端口请填 1024-65535（普通应用无法绑定更低端口）")
        }
        WebManagerPreferences.setFixedPort(ksuApp, if (mode == "fixed") requested else 0)
        val effective = WebManagerPreferences.fixedPort(ksuApp)
        diagnostics.info("service", "port mode=$mode fixed=$effective (takes effect on next start)")
        return HttpResponse(
            200,
            JSONObject()
                .put("ok", true)
                .put("mode", if (effective > 0) "fixed" else "random")
                .put("fixedPort", effective)
                .put("currentPort", boundPort)
                .toString(),
        )
    }

    // -------------------------------------------------- 工具箱（原生同款工具）

    /**
     * 与原生「设置 → 挂载与隐藏 / 工具箱」同一套工具：
     * 内置挂载、KPatch-Next、隐藏路径（pathmask）、CPU 伪装、语言、软重启。
     * 状态全部来自原生仓库/`ksud`，失败写诊断而不是编数据。
     */
    private fun toolsJson(): String {
        val root = JSONObject()

        val mount = runWithDeadline(TOOLS_QUERY_DEADLINE_MILLIS, "tool-builtin-mount") {
            runBlocking { settingsRepository.getBuiltinMountStatus() }
        }
        root.put(
            "builtinMount",
            if (mount == null) {
                diagnostics.warn("tools", "builtin mount status unavailable")
                JSONObject().put("available", false)
            } else {
                JSONObject()
                    .put("available", true)
                    .put("installed", mount.installed)
                    .put("enabled", mount.enabled)
                    .put("version", mount.version)
                    .put("versionCode", mount.versionCode)
                    .put("moduleName", mount.moduleName)
                    .put("mode", mount.defaultMode)
                    .put("variant", mount.variant)
                    .put("webUi", mount.webUi)
                    .put("conflict", mount.conflict ?: JSONObject.NULL)
            },
        )

        val kpatch = runWithDeadline(TOOLS_QUERY_DEADLINE_MILLIS, "tool-kpatch") {
            runBlocking { settingsRepository.getKPatchNextStatus() }
        }
        root.put(
            "kpatchNext",
            if (kpatch == null) {
                diagnostics.warn("tools", "kpatch-next status unavailable")
                JSONObject().put("available", false)
            } else {
                JSONObject()
                    .put("available", true)
                    .put("installed", kpatch.installed)
                    .put("enabled", kpatch.enabled)
                    .put("version", kpatch.version)
                    .put("versionCode", kpatch.versionCode)
                    .put("moduleName", kpatch.moduleName)
                    .put("pendingUpdate", kpatch.pendingUpdate)
                    .put("pendingRemove", kpatch.pendingRemove)
                    .put("unresolved", kpatch.unresolved)
                    .put("webUi", kpatch.webUi)
                    .put("builtinAvailable", kpatch.builtinAvailable)
                    .put("conflict", kpatch.conflict ?: JSONObject.NULL)
                    .put("error", kpatch.error)
            },
        )

        val cpu = runWithDeadline(TOOLS_QUERY_DEADLINE_MILLIS, "tool-cpu-spoof") {
            runBlocking { getCpuSpoofStatus() }
        }
        root.put(
            "cpuSpoof",
            if (cpu == null) {
                diagnostics.warn("tools", "cpu spoof status unavailable")
                JSONObject().put("available", false)
            } else {
                JSONObject()
                    .put("available", true)
                    .put("supported", cpu.supported)
                    .put("configured", cpu.configured)
                    .put("enabled", cpu.enabled)
                    .put("applied", cpu.applied)
                    .put("current", cpu.current)
                    .put("target", cpu.target)
                    .put("original", cpu.original)
                    .put("manufacturer", cpu.manufacturer)
                    .put("platform", cpu.platform)
                    .put("error", cpu.error)
            },
        )

        val pathmask = runWithDeadline(TOOLS_QUERY_DEADLINE_MILLIS, "tool-pathmask") {
            runBlocking { getHiddenPathConfig() }
        }
        root.put(
            "pathmask",
            if (pathmask == null) {
                diagnostics.warn("tools", "pathmask status unavailable")
                JSONObject().put("available", false)
            } else {
                JSONObject()
                    .put("available", true)
                    .put("phase", pathmask.phase)
                    .put("loaded", pathmask.loaded)
                    .put("autoLoadEnabled", pathmask.autoLoadEnabled)
                    .put("autoLoadDelaySeconds", pathmask.autoLoadDelaySeconds)
                    .put("savedCount", pathmask.savedCount)
                    .put("activeCount", pathmask.activeCount)
                    .put("resolvedCount", pathmask.resolvedCount)
                    .put("unresolvedTargetCount", pathmask.unresolvedTargetCount)
                    .put("currentKmi", pathmask.currentKmi)
                    .put("requiresReload", pathmask.requiresReload)
                    .put("requiresReboot", pathmask.requiresReboot)
                    .put("hideDirents", pathmask.hideDirents)
                    .put("hideIsolated", pathmask.hideIsolated)
                    .put("useAppScope", pathmask.useAppScope)
                    .put("targetPaths", JSONArray(pathmask.targetPaths))
                    .put("missingTargetPaths", JSONArray(pathmask.missingTargetPaths))
                    .put("lastErrorCode", pathmask.lastErrorCode)
                    .put("lastErrorMessage", pathmask.lastErrorMessage)
            },
        )

        val current = runCatching { AppLanguageManager.getSelectedLanguage(ksuApp) }.getOrNull()
        val languages = JSONArray()
        for (language in AppLanguageManager.supportedLanguages) {
            languages.put(
                JSONObject()
                    .put("tag", language.languageTag)
                    .put("label", runCatching { ksuApp.getString(language.displayNameRes) }.getOrDefault(language.languageTag)),
            )
        }
        root.put(
            "language",
            JSONObject()
                .put("current", current?.languageTag ?: "zh-CN")
                .put("supported", languages)
                .put("webPageLanguage", WEB_MANAGER_PAGE_LANGUAGE),
        )

        return root.toString()
    }

    private fun handleBuiltinMountAction(body: String?): HttpResponse {
        val payload = runCatching { JSONObject(body.orEmpty()) }.getOrNull()
            ?: return errorResponse(400, "bad_request", "请求内容不是合法 JSON")
        val action = payload.optString("action", "")
        val result = runWithDeadline(TOOLS_ACTION_DEADLINE_MILLIS, "tool-builtin-mount-set") {
            runBlocking {
                when (action) {
                    "enabled" -> settingsRepository.setBuiltinMountEnabled(payload.optBoolean("value", false))
                    "mode" -> settingsRepository.setBuiltinMountDefaultMode(payload.optString("value", ""))
                    "variant" -> settingsRepository.setBuiltinMountVariant(payload.optString("value", ""))
                    else -> false
                }
            }
        }
        if (action != "enabled" && action != "mode" && action != "variant") {
            return errorResponse(400, "unknown_action", "未知的内置挂载操作")
        }
        val ok = result == true
        diagnostics.info("tools", "builtin mount $action -> $ok")
        return if (ok) {
            HttpResponse(200, JSONObject().put("ok", true).put("action", action).toString())
        } else {
            errorResponse(500, "tool_failed", "内置挂载设置失败或超时")
        }
    }

    private fun nativeSusfsManagerResponse(): HttpResponse {
        if (Natives.isLkmMode || Natives.isLateLoadMode) {
            return errorResponse(409, "gki_required", "SUSFS 管理仅支持 GKI 模式")
        }
        val url = runWithDeadline(10_000L, "native-susfs-manager") {
            getNativeWebManagerUrl() ?: run {
                startNativeWebManager()
                getNativeWebManagerUrl()
            }
        }
        return if (url != null) {
            HttpResponse(200, JSONObject().put("ok", true).put("url", url).toString())
        } else {
            errorResponse(503, "native_web_manager_unavailable", "无法启动 ksud 网页管理器")
        }
    }

    private fun handleKPatchAction(body: String?): HttpResponse {
        val payload = runCatching { JSONObject(body.orEmpty()) }.getOrNull()
            ?: return errorResponse(400, "bad_request", "请求内容不是合法 JSON")
        val enabled = payload.optBoolean("enabled", false)
        val ok = runWithDeadline(TOOLS_ACTION_DEADLINE_MILLIS, "tool-kpatch-set") {
            runBlocking { settingsRepository.setKPatchNextEnabled(enabled) }
        } == true
        diagnostics.info("tools", "kpatch-next enabled=$enabled -> $ok")
        return if (ok) {
            HttpResponse(200, JSONObject().put("ok", true).put("enabled", enabled).toString())
        } else {
            errorResponse(500, "tool_failed", "KPatch-Next 开关写入失败或超时")
        }
    }

    private fun handlePathmaskAction(body: String?): HttpResponse {
        val payload = runCatching { JSONObject(body.orEmpty()) }.getOrNull()
            ?: return errorResponse(400, "bad_request", "请求内容不是合法 JSON")
        val action = payload.optString("action", "")
        when (action) {
            "logs" -> {
                val logs = runWithDeadline(TOOLS_QUERY_DEADLINE_MILLIS, "tool-pathmask-logs") {
                    runBlocking { getHiddenPathLogs() }
                }.orEmpty()
                return HttpResponse(200, JSONObject().put("ok", true).put("logs", logs).toString())
            }

            "autoLoad", "apply", "unload", "delete" -> Unit
            else -> return errorResponse(400, "unknown_action", "未知的隐藏路径操作")
        }
        val result = runWithDeadline(TOOLS_ACTION_DEADLINE_MILLIS, "tool-pathmask-$action") {
            runBlocking {
                when (action) {
                    "autoLoad" -> setHiddenPathAutoLoad(
                        payload.optBoolean("enabled", false),
                        payload.optInt("delaySeconds", 0),
                    )
                    "apply" -> setHiddenPathAutoLoad(
                        runCatching { getHiddenPathConfig() }.getOrDefault(defaultHiddenPathConfig()).autoLoadEnabled,
                        null,
                    )
                    "unload" -> unloadHiddenPathKernelPaths()
                    else -> deleteHiddenPathConfig()
                }
            }
        }
        if (result == null) {
            diagnostics.warn("tools", "pathmask $action timed out")
            return errorResponse(500, "tool_timeout", "隐藏路径操作超时")
        }
        diagnostics.info("tools", "pathmask $action success=${result.success} error=${result.errorCode}")
        return JSONObject()
            .put("ok", result.success)
            .put("errorCode", result.errorCode)
            .put("errorMessage", result.errorMessage)
            .let { HttpResponse(if (result.success) 200 else 500, it.toString()) }
    }

    private fun defaultHiddenPathConfig() = me.weishu.kernelsu.ui.util.HiddenPathConfigState()

    private fun handleCpuSpoofAction(body: String?): HttpResponse {
        val payload = runCatching { JSONObject(body.orEmpty()) }.getOrNull()
            ?: return errorResponse(400, "bad_request", "请求内容不是合法 JSON")
        val action = payload.optString("action", "")
        val result = runWithDeadline(TOOLS_ACTION_DEADLINE_MILLIS, "tool-cpu-$action") {
            runBlocking {
                when (action) {
                    "enable", "disable" -> setCpuSpoofEnabled(action == "enable")
                    "target" -> saveCpuSpoofTarget(payload.optString("model", ""))
                    "reset" -> restoreDefaultCpuSpoof()
                    else -> null
                }
            }
        } ?: return errorResponse(500, "tool_timeout", "CPU 伪装操作超时或未执行")
        if (action != "enable" && action != "disable" && action != "target" && action != "reset") {
            return errorResponse(400, "unknown_action", "未知的 CPU 伪装操作")
        }
        diagnostics.info("tools", "cpu spoof $action success=${result.success} error=${result.error}")
        return JSONObject()
            .put("ok", result.success)
            .put("errorCode", result.error)
            .let { HttpResponse(if (result.success) 200 else 500, it.toString()) }
    }

    @Suppress("DEPRECATION")
    private fun userspaceRebootSupported(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val powerManager = ksuApp.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isRebootingUserspaceSupported == true
    }

    private fun rebootStatusJson(): String {
        val rootReady = runWithDeadline(REBOOT_ROOT_CHECK_DEADLINE_MILLIS, "tool-reboot-status") {
            rootAvailable()
        } == true
        return JSONObject()
            .put("available", rootReady)
            .put("pending", rebootPending.get())
            .put("userspaceSupported", userspaceRebootSupported())
            .put("lateLoad", runCatching { Natives.isLateLoadMode }.getOrDefault(false))
            .toString()
    }

    private fun handleRebootAction(body: String?): HttpResponse {
        val payload = runCatching { JSONObject(body.orEmpty()) }.getOrNull()
            ?: return errorResponse(400, "bad_request", "请求内容不是合法 JSON")
        val mode = WebManagerRebootMode.parse(payload.optString("mode", ""))
            ?: return errorResponse(400, "invalid_reboot_mode", "不支持的重启模式")
        if (mode == WebManagerRebootMode.USERSPACE && !userspaceRebootSupported()) {
            return errorResponse(409, "reboot_mode_unsupported", "当前设备不支持用户空间重启")
        }
        return scheduleReboot(mode)
    }

    /**
     * Rebooting synchronously can close the socket before the browser receives
     * a response. Validate root first, return 202, then dispatch on the worker
     * pool after a short delay. The actual command is the same path used by the
     * native Manager's reboot menu.
     */
    private fun scheduleReboot(mode: WebManagerRebootMode): HttpResponse {
        val rootReady = runWithDeadline(REBOOT_ROOT_CHECK_DEADLINE_MILLIS, "tool-reboot-root") {
            rootAvailable()
        }
        if (rootReady == null) {
            return errorResponse(504, "reboot_root_timeout", "检查 root 环境超时，未发送重启命令")
        }
        if (!rootReady) {
            return errorResponse(503, "reboot_root_unavailable", "root shell 不可用，未发送重启命令")
        }
        if (!rebootPending.compareAndSet(false, true)) {
            return errorResponse(409, "reboot_already_pending", "已有重启请求正在执行")
        }

        val scheduled = runCatching {
            queryExecutor.execute {
                try {
                    Thread.sleep(REBOOT_DISPATCH_DELAY_MILLIS)
                    diagnostics.info("tools", "dispatch reboot mode=${mode.wireValue}")
                    if (mode.usesKsudSoftReboot) {
                        val dispatched = execKsud("soft-reboot", true, true)
                        if (!dispatched) {
                            diagnostics.warn("tools", "ksud rejected soft reboot dispatch")
                        }
                    } else {
                        reboot(mode.nativeReason)
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    diagnostics.warn("tools", "reboot ${mode.wireValue} interrupted before dispatch")
                } catch (error: Throwable) {
                    diagnostics.error("tools", "reboot ${mode.wireValue} failed", error)
                } finally {
                    rebootPending.set(false)
                }
            }
            true
        }.getOrElse { error ->
            rebootPending.set(false)
            diagnostics.error("tools", "unable to schedule reboot ${mode.wireValue}", error)
            false
        }
        if (!scheduled) {
            return errorResponse(500, "reboot_schedule_failed", "无法安排重启任务")
        }

        diagnostics.info("tools", "reboot ${mode.wireValue} accepted")
        return HttpResponse(
            202,
            JSONObject()
                .put("ok", true)
                .put("accepted", true)
                .put("mode", mode.wireValue)
                .put("dispatchAfterMillis", REBOOT_DISPATCH_DELAY_MILLIS)
                .toString(),
        )
    }

    private fun handleLanguageAction(body: String?): HttpResponse {
        val payload = runCatching { JSONObject(body.orEmpty()) }.getOrNull()
            ?: return errorResponse(400, "bad_request", "请求内容不是合法 JSON")
        val tag = payload.optString("tag", "").trim()
        val result = ManagerAppSettingsStore.applyWebUpdate(JSONObject().put("language", tag))
        val ok = result.isSuccess
        diagnostics.info("language", "set $tag -> $ok")
        return if (ok) {
            HttpResponse(200, JSONObject().put("ok", true).put("tag", tag).toString())
        } else {
            val error = result.exceptionOrNull()
            errorResponse(
                if (error is IllegalArgumentException) 400 else 500,
                if (error is IllegalArgumentException) "unsupported_language" else "manager_settings_write_failed",
                error?.message ?: "不支持的语言或写入失败",
            )
        }
    }

    private fun managerAppSettingsJson(): JSONObject = JSONObject()
        .put("ok", true)
        .put("manager", ManagerAppSettingsStore.snapshot().toJson())

    private fun handleManagerAppSettingsAction(body: String?): HttpResponse {
        val payload = runCatching { JSONObject(body.orEmpty()) }.getOrNull()
            ?: return errorResponse(400, "invalid_json", "请求内容不是合法 JSON")
        return ManagerAppSettingsStore.applyWebUpdate(payload).fold(
            onSuccess = { settings ->
                HttpResponse(
                    200,
                    JSONObject().put("ok", true).put("manager", settings.toJson()).toString(),
                )
            },
            onFailure = { error ->
                errorResponse(
                    if (error is IllegalArgumentException) 400 else 500,
                    if (error is IllegalArgumentException) {
                        "invalid_manager_setting"
                    } else {
                        "manager_settings_write_failed"
                    },
                    error.message ?: "软件管理器设置写入失败",
                )
            },
        )
    }

    private fun dynamicManagerStatusResponse(): HttpResponse {
        val state = runWithDeadline(TOOLS_QUERY_DEADLINE_MILLIS, "dynamic-manager-status") {
            runBlocking { getDynamicManagerStatus().getOrThrow() }
        } ?: return errorResponse(504, "dynamic_manager_timeout", "读取动态管理器状态超时")
        return HttpResponse(200, dynamicManagerJson(state).toString())
    }

    private fun dynamicManagerJson(state: me.weishu.kernelsu.ui.util.DynamicManagerCliState): JSONObject {
        val managers = JSONObject()
        state.managerSignatureIndexes.forEach { (appId, signatureIndex) ->
            managers.put(appId.toString(), signatureIndex)
        }
        return JSONObject()
            .put("ok", true)
            .put(
                "status",
                JSONObject()
                    .put("schemaVersion", 2)
                    .put("supported", state.supported)
                    .put("configured", state.configured)
                    .put("active", state.active)
                    .put("certificateSize", state.certificateSize)
                    .put("certificateSha256", state.certificateSha256)
                    .put("managers", managers)
                    .put("error", state.error.ifBlank { JSONObject.NULL }),
            )
    }

    private fun handleDynamicManagerAction(body: String?): HttpResponse {
        val payload = runCatching { JSONObject(body.orEmpty()) }.getOrNull()
            ?: return errorResponse(400, "invalid_json", "请求内容不是合法 JSON")
        val action = payload.optString("action")
        val result = runWithDeadline(TOOLS_ACTION_DEADLINE_MILLIS, "dynamic-manager-$action") {
            runBlocking {
                when (action) {
                    "clear" -> clearDynamicManager()
                    "set" -> runCatching {
                        val size = payload.optInt("certificateSize", -1)
                        val hash = payload.optString("certificateSha256", "")
                        setDynamicManagerCertificate(size, hash).getOrThrow()
                    }
                    else -> Result.failure(IllegalArgumentException("不支持这个动态管理器操作"))
                }
            }
        } ?: return errorResponse(504, "dynamic_manager_timeout", "动态管理器操作超时")
        return result.fold(
            onSuccess = { dynamicManagerStatusResponse() },
            onFailure = { error ->
                errorResponse(
                    if (error is IllegalArgumentException) 400 else 409,
                    "dynamic_manager_update_failed",
                    error.message ?: "动态管理器更新失败",
                )
            },
        )
    }

    // -------------------------------------------------- 自定义外观（壁纸与图标）

    /**
     * 与原生管理器同款的自定义外观：主页四张卡片各有壁纸、底部导航栏五个图标可换。
     * 图片存在管理器私有目录（filesDir/web-manager-assets），参数存 SharedPreferences，
     * 因此换浏览器/清缓存都不会丢，权限只受网页管理器本身的令牌保护。
     */
    private fun assetDirectory(): File = File(ksuApp.filesDir, ASSET_DIR)

    private fun assetFile(kind: String, name: String): File =
        File(assetDirectory(), WebManagerAssets.assetFileName(kind, name))

    private fun assetResponse(kind: String, name: String): HttpResponse {
        if (!WebManagerAssets.isValidAsset(kind, name)) {
            return errorResponse(404, "unknown_asset", "未知的外观资源")
        }
        val file = assetFile(kind, name)
        if (!file.isFile) return errorResponse(404, "asset_missing", "尚未设置该图片")
        val length = file.length()
        if (length <= 0L || length > WebManagerAssets.MAX_ASSET_BYTES) {
            return errorResponse(500, "asset_unusable", "图片文件不可用，请重新选择")
        }
        val mime = runCatching { WebManagerAssets.detectImageMime(file.readBytes()) }.getOrNull()
            ?: return errorResponse(500, "asset_unusable", "图片格式无法识别，请重新选择")
        return HttpResponse(
            status = 200,
            body = null,
            contentType = mime,
            stream = StreamBody(file.inputStream(), length),
            cacheSeconds = ASSET_CACHE_SECONDS,
        )
    }

    private fun uploadAssetResponse(
        kind: String,
        name: String,
        parameters: Map<String, String>,
        bodyBytes: ByteArray,
    ): HttpResponse {
        if (!WebManagerAssets.isValidAsset(kind, name)) {
            return errorResponse(404, "unknown_asset", "未知的外观资源")
        }
        if (bodyBytes.isEmpty()) return errorResponse(400, "empty_upload", "没有收到图片内容")
        if (bodyBytes.size > WebManagerAssets.MAX_ASSET_BYTES) {
            return errorResponse(413, "asset_too_large", "图片超过 ${WebManagerAssets.MAX_ASSET_BYTES} 字节")
        }
        if (WebManagerAssets.detectImageMime(bodyBytes) == null) {
            return errorResponse(415, "unsupported_image", "仅支持 PNG、JPEG、WebP 或 GIF 图片")
        }
        val directory = assetDirectory()
        if (!directory.exists() && !directory.mkdirs()) {
            return errorResponse(500, "asset_dir_unavailable", "无法创建图片目录")
        }
        val upload = runCatching { assetFile(kind, name).writeBytes(bodyBytes) }
            .getOrElse { error ->
                diagnostics.error("assets", "write failed for $kind/$name", error)
                return errorResponse(500, "asset_write_failed", "保存图片失败：${error.message.orEmpty()}")
            }
        val meta = assetMetaFromParameters(kind, parameters)
        WebManagerPreferences.setAssetMeta(ksuApp, kind, name, meta)
        diagnostics.info("assets", "stored $kind/$name (${bodyBytes.size} bytes)")
        return HttpResponse(
            status = 200,
            body = JSONObject()
                .put("ok", true)
                .put("kind", kind)
                .put("name", name)
                .put("bytes", bodyBytes.size)
                .put("meta", meta)
                .toString(),
        )
    }

    private fun clearAssetResponse(kind: String, name: String): HttpResponse {
        if (!WebManagerAssets.isValidAsset(kind, name)) {
            return errorResponse(404, "unknown_asset", "未知的外观资源")
        }
        assetFile(kind, name).delete()
        WebManagerPreferences.clearAssetMeta(ksuApp, kind, name)
        diagnostics.info("assets", "cleared $kind/$name")
        return HttpResponse(200, JSONObject().put("ok", true).put("cleared", true).toString())
    }

    private fun handleAssetMetaAction(body: String): HttpResponse {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return errorResponse(400, "invalid_body", "请求体不是合法 JSON")
        val kind = json.optString("kind").trim()
        val name = json.optString("name").trim()
        if (!WebManagerAssets.isValidAsset(kind, name)) {
            return errorResponse(404, "unknown_asset", "未知的外观资源")
        }
        if (!assetFile(kind, name).isFile) {
            return errorResponse(409, "asset_missing", "请先选择图片再调整")
        }
        val meta = assetMetaFromParameters(kind, mapOf())
            .also { normalized ->
                // 用请求里的 JSON 覆盖默认值后再收敛一次
                val merged = JSONObject(normalized.toString())
                json.optJSONObject("meta")?.keys()?.forEach { key ->
                    merged.put(key, json.optJSONObject("meta")?.opt(key))
                }
                val finalMeta = WebManagerAssets.normalizeMeta(kind, merged, System.currentTimeMillis())
                WebManagerPreferences.setAssetMeta(ksuApp, kind, name, finalMeta)
            }
        return HttpResponse(
            status = 200,
            body = JSONObject()
                .put("ok", true)
                .put("kind", kind)
                .put("name", name)
                .put("meta", WebManagerPreferences.assetMeta(ksuApp, kind, name) ?: meta)
                .toString(),
        )
    }

    private fun handleThemeAction(body: String): HttpResponse {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return errorResponse(400, "invalid_body", "请求体不是合法 JSON")
        val theme = json.optString("theme").trim().lowercase()
        if (theme !in WebManagerPreferences.THEME_VALUES) {
            return errorResponse(400, "invalid_theme", "主题只能是 auto/light/dark")
        }
        WebManagerPreferences.setTheme(ksuApp, theme)
        return HttpResponse(200, JSONObject().put("ok", true).put("theme", theme).toString())
    }

    /** 上传时把 query 里的外观参数收敛成规范 meta；缺省值即为默认外观。 */
    private fun assetMetaFromParameters(kind: String, parameters: Map<String, String>): JSONObject {
        val raw = JSONObject()
        parameters["fit"]?.let { raw.put("fit", it) }
        parameters["scale"]?.toDoubleOrNull()?.let { raw.put("scale", it) }
        parameters["offsetX"]?.toDoubleOrNull()?.let { raw.put("offsetX", it) }
        parameters["offsetY"]?.toDoubleOrNull()?.let { raw.put("offsetY", it) }
        parameters["dim"]?.toDoubleOrNull()?.let { raw.put("dim", it) }
        parameters["blur"]?.toDoubleOrNull()?.let { raw.put("blur", it) }
        return WebManagerAssets.normalizeMeta(kind, raw, System.currentTimeMillis())
    }

    private fun assetsJson(): JSONObject {
        val wallpapers = JSONObject()
        WebManagerAssets.WALLPAPER_TARGETS.keys.forEach { target ->
            val meta = WebManagerPreferences.assetMeta(ksuApp, WebManagerAssets.KIND_WALLPAPER, target)
            if (meta != null && assetFile(WebManagerAssets.KIND_WALLPAPER, target).isFile) {
                wallpapers.put(target, meta)
            }
        }
        val navIcons = JSONObject()
        WebManagerAssets.NAV_ICON_SLOTS.keys.forEach { slot ->
            val meta = WebManagerPreferences.assetMeta(ksuApp, WebManagerAssets.KIND_NAV_ICON, slot)
            if (meta != null && assetFile(WebManagerAssets.KIND_NAV_ICON, slot).isFile) {
                navIcons.put(slot, meta)
            }
        }
        return JSONObject()
            .put("wallpapers", wallpapers)
            .put("navIcons", navIcons)
            .put("moduleWallpapers", moduleWallpapersJson())
    }

    /**
     * 模块卡片壁纸：目录里按 `modulewall-<模块 id>.img` 存，返回 {模块 id: 参数}。
     * 与原生模块页一致，按模块 id 各存一张。
     */
    private fun moduleWallpapersJson(): JSONObject {
        val result = JSONObject()
        val dir = File(ksuApp.filesDir, ASSET_DIR)
        val files = dir.listFiles() ?: return result
        for (file in files) {
            val fileName = file.name
            if (!fileName.startsWith("${WebManagerAssets.KIND_MODULE_WALL}-") || !fileName.endsWith(".img")) continue
            val moduleId = fileName.removePrefix("${WebManagerAssets.KIND_MODULE_WALL}-").removeSuffix(".img")
            if (!WebManagerAssets.isValidModuleWallName(moduleId)) continue
            val meta = WebManagerPreferences.assetMeta(ksuApp, WebManagerAssets.KIND_MODULE_WALL, moduleId)
                ?: continue
            result.put(moduleId, meta)
        }
        return result
    }

    /** 自定义外观说明（给设置页渲染用）：可用的目标/槽位与展示名。 */
    private fun assetCatalogJson(): JSONObject {
        val targets = JSONObject()
        WebManagerAssets.WALLPAPER_TARGETS.forEach { (key, label) -> targets.put(key, label) }
        val slots = JSONObject()
        WebManagerAssets.NAV_ICON_SLOTS.forEach { (key, label) -> slots.put(key, label) }
        return JSONObject()
            .put("wallpaperTargets", targets)
            .put("navIconSlots", slots)
            .put("moduleWallpaperKind", WebManagerAssets.KIND_MODULE_WALL)
            .put("maxBytes", WebManagerAssets.MAX_ASSET_BYTES)
    }

    private fun settingsJson(): String {
        val now = SystemClock.elapsedRealtime()
        val started = synchronized(lock) { startedAtElapsedRealtime }
        return JSONObject()
            .put("autoStart", WebManagerPreferences.isAutoStartEnabled(ksuApp))
            .put("theme", WebManagerPreferences.theme(ksuApp))
            .put("port", boundPort)
            .put("portMode", if (WebManagerPreferences.fixedPort(ksuApp) > 0) "fixed" else "random")
            .put("fixedPort", WebManagerPreferences.fixedPort(ksuApp))
            .put("launcher", launcherJson())
            .put("loopback", true)
            .put("running", isRunning())
            .put("uptimeSeconds", if (started > 0L) ((now - started) / 1_000L).coerceAtLeast(0L) else 0L)
            .put("apiVersion", API_VERSION)
            .put("assets", assetsJson())
            .put("assetCatalog", assetCatalogJson())
            .put("stealth", stealthStatusJson())
            .put("manager", ManagerAppSettingsStore.snapshot().toJson())
            .toString()
    }

    private fun stealthStatusJson(): JSONObject {
        val rootState = StealthModeStore.readRootState().getOrNull()
        return JSONObject()
            .put("enabled", rootState?.enabled ?: StealthModeStore.isEnabled())
            .put("codeBackedUp", rootState?.codeBackedUp ?: false)
            .put("code", rootState?.code ?: StealthModeStore.code())
    }

    private fun handleStealthAction(body: String): HttpResponse {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return errorResponse(400, "invalid_body", "请求体不是合法 JSON")
        if (!json.has("enabled")) {
            return errorResponse(400, "enabled_required", "缺少 enabled 布尔值")
        }
        val enabled = json.optBoolean("enabled")
        if (!enabled) {
            return handleStealthDisable(body)
        }
        val requestedCode = json.optString("code", StealthModeStore.code())
        val normalizedCode = StealthModeStore.normalizeCode(requestedCode)
            ?: return errorResponse(
                400,
                "invalid_stealth_code",
                "隐身密令必须是 3-16 位数字或 *#*#数字#*#* 格式",
            )
        return StealthModeStore.setEnabledBlocking(
            enabled = enabled,
            requestedCode = normalizedCode,
        ).fold(
            onSuccess = {
                HttpResponse(
                    200,
                    JSONObject()
                        .put("ok", true)
                        .put("enabled", enabled)
                        .put("codeBackedUp", true)
                        .put("code", normalizedCode)
                        .toString(),
                )
            },
            onFailure = { error ->
                errorResponse(
                    status = 500,
                    code = "stealth_update_failed",
                    message = error.message ?: "隐身模式更新失败",
                )
            },
        )
    }

    private fun handleStealthDisable(body: String): HttpResponse {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return errorResponse(400, "invalid_body", "请求体不是合法 JSON")
        val requestedCode = json.optString("code").takeIf { json.has("code") }
            ?: return errorResponse(400, "stealth_code_required", "关闭隐身模式必须输入密令")
        val rootState = StealthModeStore.readRootState().getOrElse { error ->
            return errorResponse(
                status = 500,
                code = "stealth_state_unavailable",
                message = "无法读取隐身模式状态",
                detail = error,
            )
        }
        val configuredCode = rootState.code ?: DEFAULT_STEALTH_MODE_CODE
        if (!StealthModeStore.matchesRequestedCode(requestedCode, configuredCode)) {
            return errorResponse(403, "stealth_code_mismatch", "隐身密令不正确")
        }
        return StealthModeStore.setEnabledBlocking(
            enabled = false,
            requestedCode = configuredCode,
        ).fold(
            onSuccess = {
                HttpResponse(
                    200,
                    JSONObject()
                        .put("ok", true)
                        .put("enabled", false)
                        .put("codeBackedUp", true)
                        .toString(),
                )
            },
            onFailure = { error ->
                errorResponse(
                    status = 500,
                    code = "stealth_disable_failed",
                    message = "隐身模式关闭失败",
                    detail = error,
                )
            },
        )
    }

    private fun handleAutoStartAction(body: String): HttpResponse {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return HttpResponse(400, jsonError("invalid settings body"))
        if (!json.has("enabled")) return HttpResponse(400, jsonError("missing enabled"))
        val enabled = json.optBoolean("enabled")
        WebManagerPreferences.setAutoStartEnabled(ksuApp, enabled)
        return HttpResponse(200, JSONObject().put("ok", true).put("autoStart", enabled).toString())
    }

    /** Drops the module and app caches so the next request re-reads ksud/root state. */
    private fun invalidateCacheResponse(): HttpResponse {
        invalidateModuleSnapshot()
        synchronized(appSnapshotLock) { cachedAppSnapshot = null }
        synchronized(deviceSnapshotLock) { cachedDeviceSnapshot = null }
        startWarmUp()
        return HttpResponse(200, JSONObject().put("ok", true).toString())
    }

    private fun uptimeSeconds(): Long {
        val started = synchronized(lock) { startedAtElapsedRealtime }
        return if (started > 0L) {
            ((SystemClock.elapsedRealtime() - started) / 1_000L).coerceAtLeast(0L)
        } else {
            0L
        }
    }

    // ------------------------------------------------------------ module webui

    private fun webUiModuleInfoResponse(moduleId: String?): HttpResponse {
        if (moduleId == null) return HttpResponse(400, jsonError("missing module id"))
        val info = activeWebModuleInfo(moduleId)
            ?: return HttpResponse(404, jsonError("module WebUI is unavailable"))
        return HttpResponse(200, info.toString())
    }

    private fun webUiPackagesResponse(moduleId: String?, forceRefresh: Boolean): HttpResponse {
        if (!hasActiveWebUiModule(moduleId)) {
            return HttpResponse(404, jsonError("module WebUI is unavailable"))
        }
        val records = webPackageRecords(forceRefresh).getOrElse { error ->
            return errorResponse(
                status = 503,
                code = "package_query_failed",
                message = "无法读取应用列表，请确认 Root 服务可用",
                detail = error,
            )
        }
        val packages = JSONArray()
        records.forEach { packages.put(it.packageName) }
        return HttpResponse(200, JSONObject().put("packages", packages).toString())
    }

    private fun webUiPackagesInfoResponse(moduleId: String?, body: String): HttpResponse {
        if (!hasActiveWebUiModule(moduleId)) {
            return HttpResponse(404, jsonError("module WebUI is unavailable"))
        }
        val request = runCatching { JSONObject(body) }.getOrNull()
            ?: return HttpResponse(400, jsonError("invalid packages body"))
        val names = request.optJSONArray("packages")
            ?: return HttpResponse(400, jsonError("missing packages"))
        if (names.length() > MAX_EXEC_ARGS) {
            return HttpResponse(413, jsonError("too many packages"))
        }
        val requested = ArrayList<String>(names.length())
        for (index in 0 until names.length()) {
            val name = names.optString(index)
            if (name.isEmpty() || name.length > 256 || !packageNamePattern.matches(name)) {
                return HttpResponse(400, jsonError("invalid package name"))
            }
            requested.add(name)
        }
        val records = webPackageRecords(request.optBoolean("refresh", false))
            .getOrElse { error ->
                return errorResponse(
                    status = 503,
                    code = "package_query_failed",
                    message = "无法读取应用列表，请确认 Root 服务可用",
                    detail = error,
                )
            }
            .associateBy { it.packageName }
        val result = JSONArray()
        requested.forEach { name ->
            val record = records[name]
            result.put(
                record?.toJson() ?: JSONObject()
                    .put("packageName", name)
                    .put("error", "Package not found or inaccessible"),
            )
        }
        return HttpResponse(200, result.toString())
    }

    private fun iconResponse(packageName: String): HttpResponse {
        return runCatching {
            val drawable = ksuApp.packageManager.getApplicationIcon(packageName)
            val bitmap = Bitmap.createBitmap(ICON_SIZE_PX, ICON_SIZE_PX, Bitmap.Config.ARGB_8888)
            val bytes = ByteArrayOutputStream()
            try {
                drawable.setBounds(0, 0, bitmap.width, bitmap.height)
                drawable.draw(Canvas(bitmap))
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)) {
                    "failed to encode package icon"
                }
            } finally {
                bitmap.recycle()
            }
            HttpResponse(
                status = 200,
                contentType = "image/png",
                stream = StreamBody(bytes.toByteArray().inputStream(), bytes.size().toLong()),
            )
        }.getOrElse {
            HttpResponse(404, jsonError("package icon unavailable"))
        }
    }

    private fun webUiBridgeResponse(moduleId: String?, tokenPrefix: String): HttpResponse {
        if (moduleId == null) return HttpResponse(400, jsonError("missing module id"))
        val moduleInfo = activeWebModuleInfo(moduleId)
            ?: return HttpResponse(404, jsonError("module WebUI is unavailable"))
        val packageData = JSONArray()
        webPackageRecords(forceRefresh = false)
            .onFailure { error ->
                diagnostics.warn(
                    "webui",
                    "package list unavailable for $moduleId bridge: ${error.message}",
                )
            }
            .getOrDefault(emptyList())
            .forEach { packageData.put(it.toJson()) }
        val script = bridgePlaceholderPattern.replace(WEBUI_BRIDGE_SCRIPT_TEMPLATE) { match ->
            when (match.value) {
                "__MODULE_ID__" -> JSONObject.quote(moduleId)
                "__MODULE_INFO_JSON__" -> JSONObject.quote(moduleInfo.toString())
                "__TOKEN_PREFIX__" -> JSONObject.quote(tokenPrefix)
                else -> packageData.toString()
            }
        }
        return HttpResponse(200, script, "application/javascript; charset=utf-8")
    }

    private fun webUiAssetResponse(path: String, tokenPrefix: String, wantsHtml: Boolean): HttpResponse {
        val resolution = WebManagerRoutes.resolveWebUiAsset(path)
        if (resolution is WebManagerRoutes.AssetResolution.Failure) {
            return webUiUnavailableResponse(
                moduleId = "",
                reason = resolution.message,
                status = resolution.status,
                wantsHtml = wantsHtml,
            )
        }
        val asset = resolution as WebManagerRoutes.AssetResolution.Asset
        if (isManagerHiddenModuleId(asset.moduleId)) {
            return webUiUnavailableResponse(
                moduleId = asset.moduleId,
                reason = "该模块的 WebUI 不被网页管理器提供服务（隐藏模块 ID）",
                status = 404,
                wantsHtml = wantsHtml,
            )
        }
        // Relative script tags inside the module resolve to this namespace, so
        // the bridge is served here as well as from its absolute path.
        if (WebManagerRoutes.isBridgePath(asset.relativePath)) {
            return webUiBridgeResponse(asset.moduleId, tokenPrefix)
        }

        val opened = openModuleAsset(asset.moduleId, asset.relativePath)
            ?: run {
                val indexRequest = WebManagerRoutes.isWebUiEntryPath(asset.relativePath)
                diagnostics.warn(
                    "webui",
                    if (indexRequest) "entry document missing: ${asset.moduleId}/${asset.relativePath}"
                    else "asset missing: ${asset.moduleId}/${asset.relativePath}",
                )
                return webUiUnavailableResponse(
                    moduleId = asset.moduleId,
                    reason = webUiAssetFailureReason(asset.moduleId, asset.relativePath, indexRequest),
                    status = 404,
                    wantsHtml = wantsHtml && indexRequest,
                )
            }
        val isHtml = asset.relativePath.substringAfterLast('.', "").lowercase(Locale.ROOT)
            .let { it == "html" || it == "htm" }
        if (!isHtml) {
            return HttpResponse(
                status = 200,
                contentType = SuFilePathHandler.guessMimeType(asset.relativePath),
                stream = opened,
                referrerPolicy = "same-origin",
            )
        }

        return runCatching {
            val html = readLimited(opened.input, MAX_HTML_BYTES)
                ?: run {
                    opened.close()
                    return@runCatching HttpResponse(413, jsonError("WebUI document is too large"))
                }
            opened.close()
            HttpResponse(
                status = 200,
                body = injectWebUiBridge(
                    String(html, StandardCharsets.UTF_8),
                    asset.moduleId,
                    tokenPrefix,
                ),
                contentType = "text/html; charset=utf-8",
                referrerPolicy = "same-origin",
            )
        }.getOrElse {
            opened.close()
            Log.e(TAG, "failed to read WebUI document: ${asset.moduleId}/${asset.relativePath}", it)
            HttpResponse(500, jsonError("failed to read WebUI resource"))
        }
    }

    private fun activeWebModuleInfo(id: String): JSONObject? {
        val module = activeWebModuleRecord(id) ?: return null
        if (!moduleHasWebRoot(id)) return null
        return module
            .put("moduleDir", "$MODULES_ROOT/$id")
            .put("webroot", true)
            .put("web", true)
    }

    private fun activeWebModuleRecord(id: String): JSONObject? {
        if (!WebManagerSecurity.isValidModuleId(id) || isManagerHiddenModuleId(id)) {
            diagnostics.warn("webui", "invalid or hidden module id: $id")
            return null
        }
        val module = findModule(id) ?: run {
            diagnostics.warn("webui", "module not in ksud list: $id")
            return null
        }
        if (!module.optBoolean("enabled", true) || module.optBoolean("remove", false)) {
            diagnostics.warn("webui", "module not active (enabled/remove): $id")
            return null
        }
        return JSONObject(module.toString())
    }

    private fun hasActiveWebUiModule(id: String?): Boolean = id != null && activeWebModuleInfo(id) != null

    private fun moduleHasWebRoot(id: String): Boolean {
        val shell = rootShellForRead() ?: return false
        return try {
            val present = SuFile("$MODULES_ROOT/$id/webroot").apply { setShell(shell) }.isDirectory
            if (!present) {
                diagnostics.warn("webui", "$MODULES_ROOT/$id/webroot is not a directory")
            }
            present
        } catch (error: Throwable) {
            diagnostics.error("webui", "webroot probe failed for $id", error)
            false
        } finally {
            runCatching { shell.close() }
        }
    }

    private fun openModuleAsset(moduleId: String, relativePath: String): StreamBody? {
        if (activeWebModuleRecord(moduleId) == null) return null
        val shell = rootShellForRead() ?: return null
        return try {
            val webRoot = File(MODULES_ROOT, "$moduleId/webroot")
            val target = SuFilePathHandler.getCanonicalFileIfChild(webRoot, relativePath)
            if (target == null) {
                shell.close()
                diagnostics.warn("webui", "asset escaped webroot: $moduleId/$relativePath")
                return null
            }
            val suFile = SuFile(target.absolutePath).apply { setShell(shell) }
            if (!suFile.isFile) {
                shell.close()
                diagnostics.warn("webui", "asset missing: $moduleId/$relativePath")
                return null
            }
            val input = SuFilePathHandler.openFile(target, shell)
            StreamBody(input, suFile.length(), shell)
        } catch (error: Throwable) {
            runCatching { shell.close() }
            Log.e(TAG, "failed to open WebUI resource: $moduleId/$relativePath", error)
            diagnostics.error("webui", "failed to open $moduleId/$relativePath", error)
            null
        }
    }

    /**
     * Injects the bridge script. The URL carries the session token in the path,
     * so the script - and every relative module asset - authenticates even when
     * the browser drops the session cookie.
     */
    private fun injectWebUiBridge(html: String, moduleId: String, tokenPrefix: String): String {
        val url = WebManagerRoutes.bridgeUrl(tokenPrefix, moduleId)
        val baseUrl = WebManagerRoutes.webUiBaseUrl(tokenPrefix, moduleId)
        val bootstrap = "<base href=\"$baseUrl\"><script src=\"$url\"></script>"
        val lower = html.lowercase(Locale.ROOT)
        val headStart = lower.indexOf("<head")
        val headOpenEnd = if (headStart >= 0) html.indexOf('>', headStart) else -1
        return if (headOpenEnd >= 0) {
            html.substring(0, headOpenEnd + 1) + bootstrap + html.substring(headOpenEnd + 1)
        } else {
            bootstrap + html
        }
    }

    // -------------------------------------------------------------------- exec

    private fun buildExecRequest(body: String): String {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: throw IllegalArgumentException("invalid command body")
        val command = json.optString("command")
        if (command.isEmpty() || command.length > MAX_EXEC_COMMAND_LENGTH || command.contains('\u0000')) {
            throw IllegalArgumentException("invalid command")
        }
        val argsJson = json.optJSONArray("args")
        val commandPart = if (argsJson == null) {
            command
        } else {
            if (argsJson.length() > MAX_EXEC_ARGS) throw IllegalArgumentException("too many command arguments")
            buildString {
                append(shellQuote(command))
                for (index in 0 until argsJson.length()) {
                    val arg = argsJson.optString(index)
                    if (arg.length > MAX_EXEC_ARG_LENGTH || arg.contains('\u0000')) {
                        throw IllegalArgumentException("invalid command argument")
                    }
                    append(' ').append(shellQuote(arg))
                }
            }
        }
        val optionsValue = json.opt("options")
        val options = when {
            optionsValue is JSONObject -> optionsValue
            optionsValue is String -> runCatching { JSONObject(optionsValue) }.getOrElse {
                throw IllegalArgumentException("invalid command options")
            }
            else -> JSONObject()
        }
        val prefix = StringBuilder()
        val cwd = options.optString("cwd").takeIf { it.isNotBlank() }
        if (cwd != null) {
            if (cwd.length > MAX_EXEC_CWD_LENGTH || cwd.contains('\u0000')) {
                throw IllegalArgumentException("invalid working directory")
            }
            prefix.append("cd ").append(shellQuote(cwd)).append(" && ")
        }
        val env = options.optJSONObject("env")
        if (env != null) {
            if (env.length() > MAX_EXEC_ENV_COUNT) throw IllegalArgumentException("too many environment variables")
            env.keys().asSequence().toList().sorted().forEach { key ->
                val value = env.optString(key)
                if (!envKeyPattern.matches(key) ||
                    value.length > MAX_EXEC_ENV_VALUE_LENGTH ||
                    value.contains('\u0000')
                ) {
                    throw IllegalArgumentException("invalid environment variable")
                }
                prefix.append("export ").append(key).append('=').append(shellQuote(value)).append("; ")
            }
        }
        val commandLine = prefix.append(commandPart).toString()
        if (commandLine.length > MAX_EXEC_COMMAND_LENGTH) {
            throw IllegalArgumentException("command is too long")
        }
        return commandLine
    }

    private fun webUiExecResponse(moduleId: String?, body: String): HttpResponse {
        if (!hasActiveWebUiModule(moduleId)) {
            return HttpResponse(404, jsonError("module WebUI is unavailable"))
        }
        val command = runCatching { buildExecRequest(body) }.getOrElse {
            return HttpResponse(400, jsonError(it.message ?: "invalid command"))
        }
        val result = executeRootCommand(command)
        return HttpResponse(
            status = 200,
            body = JSONObject()
                .put("errno", result.code)
                .put("stdout", result.stdout)
                .put("stderr", result.stderr)
                .toString(),
        )
    }

    private fun executeRootCommand(command: String): ExecResult {
        return runCatching {
            me.weishu.kernelsu.ui.util.withNewRootShell(true) {
                if (!isRoot) return@withNewRootShell ExecResult(126, "", "root shell unavailable")
                val stdout = LimitedOutput(MAX_EXEC_OUTPUT_CHARS)
                val stderr = LimitedOutput(MAX_EXEC_OUTPUT_CHARS)
                val task = newJob().add(command).to(stdout, stderr).enqueue()
                val result = try {
                    task.get(EXEC_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    close()
                    return@withNewRootShell ExecResult(124, stdout.joinToString("\n"), "command timed out")
                }
                ExecResult(result.code, stdout.joinToString("\n"), stderr.joinToString("\n"))
            }
        }.getOrElse { error ->
            Log.e(TAG, "WebUI command failed", error)
            ExecResult(126, "", error.message ?: "root command failed")
        }
    }

    // ------------------------------------------------------------- app records

    private data class WebPackageRecord(
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
        val appLabel: String,
        val isSystem: Boolean,
        val uid: Int,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("packageName", packageName)
            .put("versionName", versionName)
            .put("versionCode", versionCode)
            .put("appLabel", appLabel)
            .put("isSystem", isSystem)
            .put("uid", uid)
    }

    /**
     * Package list for module WebUI bridges. The warm super user snapshot (root
     * backed, multi user aware) is preferred, but a cold cache never blocks a
     * module page: PackageManager answers instead.
     */
    private fun webPackageRecords(forceRefresh: Boolean): Result<List<WebPackageRecord>> {
        val records = LinkedHashMap<String, WebPackageRecord>()
        val snapshot = if (forceRefresh) {
            readSuperUserSnapshot(forceRefresh = true).getOrNull()
        } else {
            synchronized(appSnapshotLock) { cachedAppSnapshot }
        }
        snapshot?.entries.orEmpty().forEach { entry ->
            records.putIfAbsent(
                entry.packageName,
                WebPackageRecord(
                    packageName = entry.packageName,
                    versionName = "",
                    versionCode = 0L,
                    appLabel = entry.label,
                    isSystem = entry.isSystem,
                    uid = entry.uid,
                ),
            )
        }

        val packageManager = ksuApp.packageManager
        val localResult = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledPackages(0)
            }
        }
        localResult.getOrNull().orEmpty().mapNotNull { packageInfo ->
            val applicationInfo = packageInfo.applicationInfo ?: return@mapNotNull null
            WebPackageRecord(
                packageName = packageInfo.packageName,
                versionName = packageInfo.versionName.orEmpty(),
                versionCode = PackageInfoCompat.getLongVersionCode(packageInfo),
                appLabel = runCatching { applicationInfo.loadLabel(packageManager).toString() }
                    .getOrDefault(packageInfo.packageName),
                isSystem = (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                uid = applicationInfo.uid,
            )
        }.forEach { record -> records.put(record.packageName, record) }

        if (records.isEmpty()) {
            return Result.failure(
                localResult.exceptionOrNull() ?: IllegalStateException("package query returned no packages"),
            )
        }
        return Result.success(records.values.sortedBy { it.packageName })
    }

    // -------------------------------------------------------------------- misc

    private fun invalidateModuleSnapshot() = synchronized(moduleSnapshotLock) {
        cachedModuleSnapshot = null
    }

    // ------------------------------------------------------------- diagnostics

    /**
     * Opens a root shell for module file work. The plain (non global mount) shell
     * is the one the module list already uses successfully, so file reads prefer
     * it and only fall back to the global mount shell; script execution prefers
     * the global namespace like the native manager but still degrades to the
     * plain shell instead of failing outright.
     */
    private fun openRootShell(globalMntFirst: Boolean): Shell? {
        val order = if (globalMntFirst) booleanArrayOf(true, false) else booleanArrayOf(false, true)
        for (global in order) {
            val shell = runCatching { createRootShell(global) }.getOrElse { error ->
                diagnostics.warn("shell", "createRootShell(global=$global) failed: ${error.message}")
                null
            } ?: continue
            if (shell.isRoot) {
                diagnostics.info("shell", "root shell ready (global=$global)")
                return shell
            }
            diagnostics.warn("shell", "shell(global=$global) is not root, trying next mode")
            runCatching { shell.close() }
        }
        diagnostics.error("shell", "no root shell available (globalMntFirst=$globalMntFirst)")
        return null
    }

    /** File reads: plain root shell first, global mount only as a fallback. */
    private fun rootShellForRead(): Shell? = openRootShell(globalMntFirst = false)

    /** Script execution: global mount first (native parity), plain shell as fallback. */
    private fun rootShellForExec(): Shell? = openRootShell(globalMntFirst = true)

    /**
     * Runs [block] on the query pool with a hard deadline, so a stuck root shell
     * can never pin a client thread (and with it the browser) forever.
     */
    private fun <T> runWithDeadline(timeoutMillis: Long, tag: String, block: () -> T): T? {
        val future = queryExecutor.submit(Callable { block() })
        return try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            diagnostics.error(tag, "timed out after ${timeoutMillis}ms")
            null
        } catch (error: Throwable) {
            diagnostics.error(tag, error.message ?: "query failed", error)
            null
        }
    }

    private fun probeRootShell(globalMnt: Boolean): Boolean = runCatching {
        createRootShell(globalMnt).use { shell -> shell.isRoot }
    }.getOrDefault(false)

    private fun diagnosticsJson(): String {
        val started = SystemClock.elapsedRealtime()
        val ksud = File(ksuDaemonPath())
        val moduleSnapshot = synchronized(moduleSnapshotLock) { cachedModuleSnapshot }
        val appSnapshot = synchronized(appSnapshotLock) { cachedAppSnapshot }
        val body = runWithDeadline(25_000L, "diagnostics") {
            JSONObject()
                .put("apiVersion", API_VERSION)
                .put("port", boundPort)
                .put("loopback", true)
                .put("serverRunning", isRunning())
                .put("uptimeSeconds", uptimeSeconds())
                .put("androidSdk", Build.VERSION.SDK_INT)
                .put("managerPackage", ksuApp.packageName)
                .put("nativeLibraryDir", ksuApp.applicationInfo.nativeLibraryDir)
                .put("ksudPath", ksud.absolutePath)
                .put("ksudExists", ksud.isFile)
                .put("ksudSize", if (ksud.isFile) ksud.length() else 0L)
                .put("shellPlainRoot", probeRootShell(false))
                .put("shellGlobalRoot", probeRootShell(true))
                .put("rootAvailable", runCatching { rootAvailable() }.getOrDefault(false))
                .put(
                    "moduleSnapshot",
                    moduleSnapshot?.let { snapshot ->
                        JSONObject()
                            .put("count", snapshot.modules.size)
                            .put("ageMillis", SystemClock.elapsedRealtime() - snapshot.cachedAt)
                    } ?: JSONObject.NULL,
                )
                .put(
                    "appSnapshot",
                    appSnapshot?.let { snapshot ->
                        JSONObject()
                            .put("source", snapshot.source)
                            .put("entries", snapshot.entries.size)
                            .put("totalApps", snapshot.totalApps)
                            .put("ageMillis", SystemClock.elapsedRealtime() - snapshot.cachedAt)
                    } ?: JSONObject.NULL,
                )
                .put("hasRunningAction", jobs.hasRunningJob())
                .put("queryMillis", SystemClock.elapsedRealtime() - started)
        }
        val log = JSONArray()
        diagnostics.snapshot(limit = 25).forEach { entry ->
            log.put(
                JSONObject()
                    .put("level", entry.level.name.lowercase(Locale.ROOT))
                    .put("tag", entry.tag)
                    .put("message", entry.message),
            )
        }
        return (body ?: JSONObject().put("errorCode", "diagnostics_failed").put("error", "诊断超时"))
            .put("log", log)
            .toString()
    }

    private fun managerPage(): String = WEB_MANAGER_PAGE

    private fun ksuDaemonPath(): String =
        ksuApp.applicationInfo.nativeLibraryDir + File.separator + "libksud.so"

    private fun readLimited(input: InputStream, maxBytes: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            if (output.size() + count > maxBytes) return null
            output.write(buffer, 0, count)
        }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

    private fun respond(
        output: BufferedOutputStream,
        status: Int,
        body: String,
        contentType: String = "application/json; charset=utf-8",
    ) {
        respond(
            output,
            HttpResponse(status = status, body = body, contentType = contentType),
        )
    }

    private fun respond(output: BufferedOutputStream, response: HttpResponse) {
        val bodyBytes = response.body?.toByteArray(StandardCharsets.UTF_8)
        val stream = response.stream
        val reason = when (response.status) {
            200 -> "OK"
            202 -> "Accepted"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            408 -> "Request Timeout"
            409 -> "Conflict"
            413 -> "Payload Too Large"
            414 -> "URI Too Long"
            415 -> "Unsupported Media Type"
            431 -> "Request Header Fields Too Large"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            504 -> "Gateway Timeout"
            else -> "Error"
        }
        val contentLength = bodyBytes?.size?.toLong() ?: stream?.length
        val cookie = if (response.setCookie) {
            val currentToken = synchronized(lock) { token }.orEmpty()
            "Set-Cookie: ${WebManagerSecurity.TOKEN_COOKIE_NAME}=$currentToken; Path=/; HttpOnly; SameSite=Strict\r\n"
        } else {
            ""
        }
        val lengthHeader = contentLength?.takeIf { it >= 0 }?.let { "Content-Length: $it\r\n" }.orEmpty()
        val cacheHeader = if (response.cacheSeconds > 0) {
            "Cache-Control: private, max-age=${response.cacheSeconds}\r\n"
        } else {
            "Cache-Control: no-store\r\n"
        }
        val header = "HTTP/1.1 ${response.status} $reason\r\n" +
            "Content-Type: ${response.contentType}\r\n" +
            lengthHeader +
            cookie +
            cacheHeader +
            "X-Content-Type-Options: nosniff\r\n" +
            "X-Frame-Options: SAMEORIGIN\r\n" +
            "Referrer-Policy: ${response.referrerPolicy}\r\n" +
            "Connection: close\r\n\r\n"
        try {
            output.write(header.toByteArray(StandardCharsets.ISO_8859_1))
            if (bodyBytes != null) {
                output.write(bodyBytes)
            } else if (stream != null) {
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = stream.input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
            output.flush()
        } finally {
            stream?.close()
        }
    }

    private fun readLine(input: BufferedInputStream, maxBytes: Int): String? {
        val bytes = ByteArrayOutputStream()
        var previous = -1
        while (bytes.size() <= maxBytes) {
            val current = input.read()
            if (current < 0) {
                return if (bytes.size() == 0) null
                else bytes.toString(StandardCharsets.ISO_8859_1.name())
            }
            if (previous == '\r'.code && current == '\n'.code) {
                val data = bytes.toByteArray()
                return String(data, 0, (data.size - 1).coerceAtLeast(0), StandardCharsets.ISO_8859_1)
            }
            bytes.write(current)
            previous = current
        }
        return null
    }

    private fun query(rawQuery: String?): Map<String, String> = rawQuery.orEmpty()
        .split('&')
        .asSequence()
        .filter(String::isNotEmpty)
        .mapNotNull { item ->
            val parts = item.split('=', limit = 2)
            val key = runCatching { URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name()) }
                .getOrNull() ?: return@mapNotNull null
            val value = runCatching {
                URLDecoder.decode(parts.getOrElse(1) { "" }, StandardCharsets.UTF_8.name())
            }.getOrNull() ?: ""
            key to value
        }
        .toMap()

    private fun errorResponse(
        status: Int,
        code: String,
        message: String,
        detail: Throwable? = null,
    ): HttpResponse {
        val body = JSONObject()
            .put("errorCode", code)
            .put("error", message)
        detail?.message?.takeIf(String::isNotBlank)?.let { body.put("detail", it) }
        return HttpResponse(status, body.toString())
    }

    private fun errorResponse(
        status: Int,
        code: String,
        message: String,
        detail: String,
    ): HttpResponse {
        val body = JSONObject()
            .put("errorCode", code)
            .put("error", message)
            .put("detail", detail)
        return HttpResponse(status, body.toString())
    }

    private fun jsonError(message: String, code: String = "request_failed"): String = JSONObject()
        .put("errorCode", code)
        .put("error", message)
        .toString()

    private fun url(accessToken: String): String = "http://127.0.0.1:$boundPort/?token=$accessToken"

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private data class HttpResponse(
        val status: Int,
        val body: String? = null,
        val contentType: String = "application/json; charset=utf-8",
        val stream: StreamBody? = null,
        val setCookie: Boolean = false,
        /** > 0 时改用 private max-age 缓存（仅用于自定义外观图片）。 */
        val cacheSeconds: Int = 0,
        val referrerPolicy: String = "no-referrer",
    )

    private data class StreamBody(
        val input: InputStream,
        val length: Long,
        val shell: Shell? = null,
    ) {
        fun close() {
            runCatching { input.close() }
            runCatching { shell?.close() }
        }
    }

    private data class ExecResult(
        val code: Int,
        val stdout: String,
        val stderr: String,
    )

    private class LimitedOutput(private val limit: Int) : ArrayList<String>() {
        private var sizeInChars = 0

        override fun add(element: String): Boolean {
            if (sizeInChars >= limit) return true
            val remaining = limit - sizeInChars
            val value = element.take(remaining)
            sizeInChars += value.length
            return super.add(value)
        }
    }
}
