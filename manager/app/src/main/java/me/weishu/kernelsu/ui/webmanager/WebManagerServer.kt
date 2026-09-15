package me.weishu.kernelsu.ui.webmanager

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.io.SuFile
import kotlinx.coroutines.runBlocking
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.data.model.AppInfo
import me.weishu.kernelsu.data.repository.SuperUserRepositoryImpl
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.util.createRootShell
import me.weishu.kernelsu.ui.util.ensureManagerRegistered
import me.weishu.kernelsu.ui.util.isManagerHiddenModuleId
import me.weishu.kernelsu.ui.util.listModulesWithTimeout
import me.weishu.kernelsu.ui.util.rootAvailable
import me.weishu.kernelsu.ui.util.toggleModule
import me.weishu.kernelsu.ui.util.uninstallModule
import me.weishu.kernelsu.ui.webui.SuFilePathHandler
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.Locale

internal object WebManagerServer {
    private const val TAG = "ApkeSU-WebManager"
    private const val API_VERSION = 2
    private const val MAX_HEADERS_BYTES = 16 * 1024
    private const val MAX_BODY_BYTES = 64 * 1024
    private const val MAX_MODULE_ID_LENGTH = 128
    private const val MAX_WEB_PATH_LENGTH = 2_048
    private const val MAX_HTML_BYTES = 512 * 1024
    private const val MAX_EXEC_COMMAND_LENGTH = 8 * 1024
    private const val MAX_EXEC_CWD_LENGTH = 512
    private const val MAX_EXEC_ENV_COUNT = 64
    private const val MAX_EXEC_ENV_VALUE_LENGTH = 2 * 1024
    private const val MAX_EXEC_ARGS = 128
    private const val MAX_EXEC_ARG_LENGTH = 2 * 1024
    private const val MAX_EXEC_OUTPUT_CHARS = 512 * 1024
    private const val EXEC_TIMEOUT_MILLIS = 30_000L
    private const val APP_SNAPSHOT_TTL_MILLIS = 2_000L
    private const val MODULE_SNAPSHOT_TTL_MILLIS = 750L
    private const val MODULES_ROOT = "/data/adb/modules"
    private const val WEBUI_BRIDGE_PATH = "/__apkesu_webui_bridge.js"
    private const val REQUEST_TIMEOUT_MILLIS = 5_000
    private val packageNamePattern = Regex("[A-Za-z0-9._]+")
    private val envKeyPattern = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
    private val bridgePlaceholderPattern = Regex("__MODULE_ID__|__MODULE_INFO_JSON__|__PACKAGE_DATA__")

    private val lock = Any()
    private val moduleSnapshotLock = Any()
    private val appSnapshotLock = Any()
    private val superUserRepository = SuperUserRepositoryImpl()
    private val random = SecureRandom()
    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private var token: String? = null
    private var startedAtElapsedRealtime: Long = 0L
    private var cachedModuleSnapshot: ModuleSnapshot? = null
    private var cachedAppSnapshot: AppSnapshot? = null

    fun start(): String = synchronized(lock) {
        val existing = serverSocket
        if (existing != null && !existing.isClosed) {
            return@synchronized url(token ?: error("web manager token is missing"))
        }

        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), WEB_MANAGER_PORT))
        }
        val nextToken = ByteArray(32).also(random::nextBytes).toHex()
        val nextExecutor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "ApkeSU-WebManager-Client").apply { isDaemon = true }
        }
        serverSocket = socket
        executor = nextExecutor
        token = nextToken
        startedAtElapsedRealtime = SystemClock.elapsedRealtime()
        Thread({ acceptLoop(socket, nextExecutor) }, "ApkeSU-WebManager-Accept").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "web manager listening on 127.0.0.1:$WEB_MANAGER_PORT")
        url(nextToken)
    }

    fun stop() = synchronized(lock) {
        serverSocket?.close()
        serverSocket = null
        executor?.shutdownNow()
        executor = null
        token = null
        startedAtElapsedRealtime = 0L
        invalidateModuleSnapshot()
        invalidateAppSnapshot()
        Log.i(TAG, "web manager stopped")
    }

    fun isRunning(): Boolean = synchronized(lock) {
        serverSocket?.let { !it.isClosed } == true
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

            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
                respond(output, 413, jsonError("request body too large"))
                return
            }
            var requestBody = ""
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
                requestBody = String(body, StandardCharsets.UTF_8)
            }

            val target = runCatching { URI(requestParts[1]) }.getOrNull()
            if (target == null || target.isAbsolute) {
                respond(output, 400, jsonError("invalid request target"))
                return
            }
            val currentToken = synchronized(lock) { token }
            val queryToken = query(target.rawQuery)["token"]
            val headerToken = WebManagerSecurity.bearerToken(headers["authorization"])
            val cookieToken = WebManagerSecurity.cookieToken(headers["cookie"])
            val queryAuthenticated = WebManagerSecurity.constantTimeEquals(currentToken.orEmpty(), queryToken)
            if (currentToken == null ||
                !(queryAuthenticated ||
                    WebManagerSecurity.constantTimeEquals(currentToken, headerToken) ||
                    WebManagerSecurity.constantTimeEquals(currentToken, cookieToken))
            ) {
                respond(output, 401, jsonError("invalid or missing access token"))
                return
            }

            val method = requestParts[0].uppercase(Locale.ROOT)
            if (method in setOf("POST", "PUT", "PATCH", "DELETE") &&
                !WebManagerSecurity.isAllowedOrigin(headers["origin"], WEB_MANAGER_PORT)
            ) {
                respond(output, 403, jsonError("cross-origin request rejected"))
                return
            }
            val response = runCatching {
                when {
                    method == "GET" && target.path == "/" -> HttpResponse(
                        200,
                        managerPage(),
                        "text/html; charset=utf-8",
                    )
                    method == "GET" && target.path == WEBUI_BRIDGE_PATH -> webUiBridgeResponse(target)
                    method == "GET" && target.path.startsWith("/webui/") -> webUiAssetResponse(target.path)
                    method == "GET" && target.path == "/api/status" -> HttpResponse(200, statusJson())
                    method == "GET" && target.path == "/api/modules" -> modulesResponse(
                        forceRefresh = query(target.rawQuery)["refresh"] == "1",
                    )
                    method == "GET" && target.path == "/api/superuser" -> superusersResponse(
                        forceRefresh = query(target.rawQuery)["refresh"] == "1",
                    )
                    method == "GET" && target.path == "/api/settings" -> HttpResponse(200, settingsJson())
                    method == "GET" && target.path == "/api/webui/module-info" -> webUiModuleInfoResponse(target)
                    method == "GET" && target.path == "/api/webui/packages" -> webUiPackagesResponse(target)
                    method == "GET" && target.path.startsWith("/api/webui/icon/") -> webUiIconResponse(target)
                    method == "POST" && target.path == "/api/webui/exec" -> webUiExecResponse(target, requestBody)
                    method == "POST" && target.path == "/api/webui/packages-info" -> webUiPackagesInfoResponse(target, requestBody)
                    method == "POST" && target.path.startsWith("/api/superuser/") -> handleSuperuserAction(target.path)
                    method == "POST" && target.path == "/api/settings/auto-start" -> handleAutoStartAction(requestBody)
                    method == "POST" -> handleModuleAction(target.path)
                    else -> HttpResponse(405, jsonError("method not allowed", "method_not_allowed"))
                }
            }.getOrElse { error ->
                Log.e(TAG, "request handler failed", error)
                errorResponse(
                    status = 500,
                    code = "request_handler_failed",
                    message = "服务器处理请求失败",
                    detail = error,
                )
            }
            respond(output, if (queryAuthenticated) response.copy(setCookie = true) else response)
        }
    }

    private fun handleModuleAction(path: String): HttpResponse {
        val parts = path.split('/').filter(String::isNotEmpty)
        if (parts.size != 4 || parts[0] != "api" || parts[1] != "modules") {
            return HttpResponse(404, jsonError("endpoint not found"))
        }
        val id = WebManagerSecurity.decodeModuleId(parts[2])
            ?: return HttpResponse(400, jsonError("invalid module id", "invalid_module_id"))
        if (isManagerHiddenModuleId(id)) {
            return HttpResponse(400, jsonError("module cannot be managed", "module_cannot_be_managed"))
        }
        val success = when (parts[3]) {
            "enable" -> toggleModule(id, true)
            "disable" -> toggleModule(id, false)
            "uninstall" -> uninstallModule(id)
            else -> return HttpResponse(404, jsonError("unknown module action"))
        }
        invalidateModuleSnapshot()
        return if (success) {
            HttpResponse(200, JSONObject().put("ok", true).toString())
        } else {
            HttpResponse(500, jsonError("module operation failed"))
        }
    }

    private fun readModuleList(forceRefresh: Boolean = false): Result<JSONArray> {
        val now = SystemClock.elapsedRealtime()
        synchronized(moduleSnapshotLock) {
            val cached = cachedModuleSnapshot
            if (!forceRefresh && cached != null && now - cached.cachedAt < MODULE_SNAPSHOT_TTL_MILLIS) {
                return Result.success(JSONArray(cached.rawJson))
            }

            val result = runCatching {
                check(rootAvailable()) { "root shell unavailable" }
                val rawJson = runBlocking { listModulesWithTimeout() }
                JSONArray(rawJson)
                ModuleSnapshot(rawJson, now)
            }
            result.onSuccess { cachedModuleSnapshot = it }
            return result.map { snapshot -> JSONArray(snapshot.rawJson) }
        }
    }

    private fun statusJson(): String {
        val moduleResult = readModuleList()
        val modules = moduleResult.getOrNull() ?: JSONArray()
        var visibleModuleCount = 0
        var enabledModuleCount = 0
        var pendingModuleCount = 0
        repeat(modules.length()) { index ->
            val module = modules.optJSONObject(index) ?: return@repeat
            val id = module.optString("id").trim()
            if (id.isBlank() || isManagerHiddenModuleId(id)) return@repeat
            visibleModuleCount++
            if (module.optBoolean("remove", false)) {
                pendingModuleCount++
            } else if (module.optBoolean("enabled", true)) {
                enabledModuleCount++
            }
        }
        val response = JSONObject()
            .put("apiVersion", API_VERSION)
            .put("port", WEB_MANAGER_PORT)
            .put("loopback", true)
            .put("serverRunning", isRunning())
            .put("uptimeSeconds", uptimeSeconds())
            .put("root", runCatching { rootAvailable() }.getOrDefault(false))
            .put("moduleQueryOk", moduleResult.isSuccess)
        if (moduleResult.isSuccess) {
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
        return response.toString()
    }

    private fun modulesResponse(forceRefresh: Boolean): HttpResponse {
        val source = readModuleList(forceRefresh).getOrElse {
            return errorResponse(
                status = 503,
                code = "module_query_failed",
                message = "无法读取模块列表，请确认 Root 服务可用",
                detail = it,
            )
        }
        val result = JSONArray()
        repeat(source.length()) { index ->
            val module = source.optJSONObject(index) ?: return@repeat
            val id = module.optString("id").trim()
            if (id.isBlank() || isManagerHiddenModuleId(id)) return@repeat
            result.put(
                JSONObject()
                    .put("id", id)
                    .put("name", module.optString("name", id))
                    .put("version", module.optString("version", "Unknown"))
                    .put("enabled", module.optBoolean("enabled", true))
                    .put("remove", module.optBoolean("remove", false))
                    // Normal modules expose this flag as "web"; the
                    // KPatch-Next status adapter uses "webui". Accept both
                    // so the browser manager does not hide a valid WebUI.
                    .put("webui", module.optBoolean("webui", false) || module.optBoolean("web", false)),
            )
        }
        return HttpResponse(
            status = 200,
            body = JSONObject()
                .put("modules", result)
                .put("count", result.length())
                .toString(),
        )
    }

    private fun superusersResponse(forceRefresh: Boolean): HttpResponse {
        val apps = getAppSnapshot(forceRefresh).getOrElse { error ->
            Log.e(TAG, "superuser query failed", error)
            return errorResponse(
                status = 503,
                code = "superuser_query_failed",
                message = "无法读取超级用户列表，请确认 Root 服务可用",
                detail = error,
            )
        }.apps
            .filter { app -> !app.special && app.packageName != ksuApp.packageName }

        val result = JSONArray()
        var authorizedCount = 0
        var sharedUidCount = 0
        apps.groupBy { it.uid }
            .toList()
            .sortedBy { (_, grouped) -> grouped.minOfOrNull { it.label.lowercase() }.orEmpty() }
            .forEach { (uid, grouped) ->
                val primary = grouped
                    .sortedWith(
                        compareByDescending<AppInfo> { it.allowSu }
                            .thenBy { it.label.lowercase() },
                    )
                    .first()
                val allowSu = grouped.any { it.allowSu }
                if (allowSu) authorizedCount++
                if (grouped.size > 1) sharedUidCount++
                result.put(
                    JSONObject()
                        .put("uid", uid)
                        .put("userId", uid / 100000)
                        .put("label", primary.label)
                        .put("packageName", primary.packageName)
                        .put("allowSu", allowSu)
                        .put("customProfile", grouped.any { it.hasCustomProfile })
                        .put("appCount", grouped.size)
                        .put("sharedUid", grouped.size > 1)
                        .put("manageable", uid >= 2000 || uid == 1000),
                )
            }
        return HttpResponse(
            status = 200,
            body = JSONObject()
                .put("apps", result)
                .put("totalApps", apps.size)
                .put("uidCount", result.length())
                .put("authorizedCount", authorizedCount)
                .put("sharedUidCount", sharedUidCount)
                .put("stale", false)
                .toString(),
        )
    }

    private fun handleSuperuserAction(path: String): HttpResponse {
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

        return synchronized(appSnapshotLock) {
            val apps = getAppSnapshot(forceRefresh = true).getOrElse { error ->
                return@synchronized errorResponse(
                    status = 503,
                    code = "superuser_query_failed",
                    message = "无法读取超级用户列表，请确认 Root 服务可用",
                    detail = error,
                )
            }.apps
                .filter { app ->
                    !app.special &&
                        app.packageName != ksuApp.packageName &&
                        app.uid == uid
                }
                .distinctBy { it.profileKey }
            if (apps.isEmpty()) {
                return@synchronized errorResponse(404, "uid_not_found", "uid not found")
            }

            var managerRegistered = false
            var updatedCount = 0
            apps.forEach { app ->
                val current = app.profile ?: Natives.Profile(app.profileKey, app.uid)
                val profile = current.copy(
                    currentUid = app.uid,
                    allowSu = allowSu,
                )
                val updated = if (Natives.setAppProfile(profile)) {
                    true
                } else {
                    if (!managerRegistered) managerRegistered = ensureManagerRegistered()
                    managerRegistered && Natives.setAppProfile(profile)
                }
                if (updated) updatedCount++
            }
            invalidateAppSnapshot()
            if (updatedCount == apps.size) {
                HttpResponse(
                    200,
                    JSONObject()
                        .put("ok", true)
                        .put("allowSu", allowSu)
                        .put("updatedCount", updatedCount)
                        .toString(),
                )
            } else {
                errorResponse(
                    status = 500,
                    code = "profile_update_failed",
                    message = "超级用户配置更新失败",
                    detail = "updated $updatedCount of ${apps.size}",
                )
            }
        }
    }

    private fun settingsJson(): String {
        val now = SystemClock.elapsedRealtime()
        val started = synchronized(lock) { startedAtElapsedRealtime }
        return JSONObject()
            .put("autoStart", WebManagerPreferences.isAutoStartEnabled(ksuApp))
            .put("port", WEB_MANAGER_PORT)
            .put("loopback", true)
            .put("running", isRunning())
            .put("uptimeSeconds", if (started > 0L) ((now - started) / 1_000L).coerceAtLeast(0L) else 0L)
            .put("apiVersion", API_VERSION)
            .toString()
    }

    private fun handleAutoStartAction(body: String): HttpResponse {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return HttpResponse(400, jsonError("invalid settings body"))
        if (!json.has("enabled")) return HttpResponse(400, jsonError("missing enabled"))
        val enabled = json.optBoolean("enabled")
        WebManagerPreferences.setAutoStartEnabled(ksuApp, enabled)
        return HttpResponse(200, JSONObject().put("ok", true).put("autoStart", enabled).toString())
    }

    private fun webUiModuleInfoResponse(target: URI): HttpResponse {
        val id = query(target.rawQuery)["module"]
            ?: return HttpResponse(400, jsonError("missing module id"))
        val info = activeWebModuleInfo(id)
            ?: return HttpResponse(404, jsonError("module WebUI is unavailable"))
        return HttpResponse(200, info.toString())
    }

    private fun webUiPackagesResponse(target: URI): HttpResponse {
        if (!hasActiveWebUiModule(query(target.rawQuery)["module"])) {
            return HttpResponse(404, jsonError("module WebUI is unavailable"))
        }
        val records = webPackageRecords(forceRefresh = query(target.rawQuery)["refresh"] == "1")
            .getOrElse { error ->
                return errorResponse(
                    status = 503,
                    code = "package_query_failed",
                    message = "无法读取应用列表，请确认 Root 服务可用",
                    detail = error,
                )
            }
        val packages = JSONArray()
        records.forEach { packages.put(it.packageName) }
        return HttpResponse(
            200,
            JSONObject().put("packages", packages).toString(),
        )
    }

    private fun webUiPackagesInfoResponse(target: URI, body: String): HttpResponse {
        if (!hasActiveWebUiModule(query(target.rawQuery)["module"])) {
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
            if (name.isBlank() || name.length > 256 || !packageNamePattern.matches(name)) {
                return HttpResponse(400, jsonError("invalid package name"))
            }
            requested.add(name)
        }
        val records = webPackageRecords(forceRefresh = request.optBoolean("refresh", false))
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
            result.put(record?.toJson() ?: JSONObject()
                .put("packageName", name)
                .put("error", "Package not found or inaccessible"))
        }
        return HttpResponse(200, result.toString())
    }

    private fun webUiIconResponse(target: URI): HttpResponse {
        val packageName = target.path.removePrefix("/api/webui/icon/")
        if (packageName.isBlank() || packageName.length > 256 || !packageNamePattern.matches(packageName)) {
            return HttpResponse(400, jsonError("invalid package name"))
        }
        return runCatching {
            val drawable = ksuApp.packageManager.getApplicationIcon(packageName)
            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
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

    private fun webUiBridgeResponse(target: URI): HttpResponse {
        val moduleId = query(target.rawQuery)["module"]
            ?: return HttpResponse(400, jsonError("missing module id"))
        val moduleInfo = activeWebModuleInfo(moduleId)
            ?: return HttpResponse(404, jsonError("module WebUI is unavailable"))
        val packageData = JSONArray()
        webPackageRecords().getOrElse { error ->
            return errorResponse(
                status = 503,
                code = "package_query_failed",
                message = "无法读取应用列表，请确认 Root 服务可用",
                detail = error,
            )
        }.forEach { packageData.put(it.toJson()) }
        val script = bridgePlaceholderPattern.replace(WEBUI_BRIDGE_SCRIPT_TEMPLATE) { match ->
            when (match.value) {
                "__MODULE_ID__" -> JSONObject.quote(moduleId)
                "__MODULE_INFO_JSON__" -> JSONObject.quote(moduleInfo.toString())
                else -> packageData.toString()
            }
        }
        return HttpResponse(200, script, "application/javascript; charset=utf-8")
    }

    private fun webUiAssetResponse(path: String): HttpResponse {
        if (path.length > MAX_WEB_PATH_LENGTH) {
            return HttpResponse(414, jsonError("WebUI path is too long"))
        }
        val suffix = path.removePrefix("/webui/")
        val segments = suffix.split('/')
        val moduleId = segments.firstOrNull().orEmpty()
        if (!WebManagerSecurity.isValidModuleId(moduleId) || isManagerHiddenModuleId(moduleId)) {
            return HttpResponse(404, jsonError("module WebUI is unavailable"))
        }
        val relativePath = segments.drop(1).joinToString("/").ifBlank { "index.html" }
        if (relativePath.length > MAX_WEB_PATH_LENGTH ||
            relativePath.contains('\u0000') ||
            relativePath.contains('\\') ||
            relativePath.split('/').any { it.isEmpty() || it == "." || it == ".." }
        ) {
            return HttpResponse(400, jsonError("invalid WebUI path"))
        }

        val asset = openModuleAsset(moduleId, relativePath)
            ?: return HttpResponse(404, jsonError("WebUI resource not found"))
        if (!relativePath.substringAfterLast('.', "").lowercase(Locale.ROOT).let { it == "html" || it == "htm" }) {
            return HttpResponse(
                status = 200,
                contentType = SuFilePathHandler.guessMimeType(relativePath),
                stream = asset,
            )
        }

        return runCatching {
            val html = readLimited(asset.input, MAX_HTML_BYTES)
                ?: run {
                    asset.close()
                    return@runCatching HttpResponse(413, jsonError("WebUI document is too large"))
                }
            asset.close()
            HttpResponse(
                status = 200,
                body = injectWebUiBridge(String(html, StandardCharsets.UTF_8), moduleId),
                contentType = "text/html; charset=utf-8",
            )
        }.getOrElse {
            asset.close()
            Log.e(TAG, "failed to read WebUI document: $moduleId/$relativePath", it)
            HttpResponse(500, jsonError("failed to read WebUI resource"))
        }
    }

    private fun activeWebModuleInfo(id: String): JSONObject? {
        if (!WebManagerSecurity.isValidModuleId(id) || isManagerHiddenModuleId(id)) return null
        val module = readModuleList().getOrNull()
            ?.let { modules ->
                (0 until modules.length())
                    .mapNotNull { modules.optJSONObject(it) }
                    .firstOrNull { it.optString("id").trim() == id }
            }
            ?: return null
        if (!module.optBoolean("enabled", true) || module.optBoolean("remove", false)) return null
        if (!moduleHasWebRoot(id)) return null
        return JSONObject(module.toString())
            .put("moduleDir", "$MODULES_ROOT/$id")
            .put("webroot", true)
            .put("web", true)
    }

    private fun hasActiveWebUiModule(id: String?): Boolean = id != null && activeWebModuleInfo(id) != null

    private fun moduleHasWebRoot(id: String): Boolean {
        return runCatching {
            createRootShell(true).use { shell ->
                if (!shell.isRoot) return@use false
                SuFile("$MODULES_ROOT/$id/webroot").apply { setShell(shell) }.isDirectory
            }
        }.getOrDefault(false)
    }

    private fun openModuleAsset(moduleId: String, relativePath: String): StreamBody? {
        if (activeWebModuleInfo(moduleId) == null) return null
        val shell = runCatching { createRootShell(true) }.getOrNull() ?: return null
        return try {
            if (!shell.isRoot) {
                shell.close()
                return null
            }
            val webRoot = File(MODULES_ROOT, "$moduleId/webroot")
            val target = SuFilePathHandler.getCanonicalFileIfChild(webRoot, relativePath)
            if (target == null) {
                shell.close()
                return null
            }
            val suFile = SuFile(target.absolutePath).apply { setShell(shell) }
            if (!suFile.isFile) {
                shell.close()
                return null
            }
            val input = SuFilePathHandler.openFile(target, shell)
            StreamBody(input, suFile.length(), shell)
        } catch (error: Throwable) {
            shell.close()
            Log.e(TAG, "failed to open WebUI resource: $moduleId/$relativePath", error)
            null
        }
    }

    private fun injectWebUiBridge(html: String, moduleId: String): String {
        val script = "<script src=\"$WEBUI_BRIDGE_PATH?module=$moduleId\"></script>"
        val lower = html.lowercase(Locale.ROOT)
        val headEnd = lower.indexOf("</head>")
        return if (headEnd >= 0) {
            html.substring(0, headEnd) + script + html.substring(headEnd)
        } else {
            script + html
        }
    }

    private fun managerPage(): String = WEB_MANAGER_PAGE.replace("\\n", "\n")

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

    private fun webPackageRecords(forceRefresh: Boolean = false): Result<List<WebPackageRecord>> {
        val records = LinkedHashMap<String, WebPackageRecord>()
        val rootResult = getAppSnapshot(forceRefresh)

        // Package visibility can hide most applications from the Manager
        // process. The root-backed repository already has the multi-user
        // fallback used by the native Super User page, so use it first and
        // retain PackageManager as a best-effort supplement.
        rootResult.getOrNull()?.apps.orEmpty()
            .filter { app -> !app.special && app.packageName != ksuApp.packageName }
            .mapNotNull(::webPackageRecord)
            .forEach { record -> records.putIfAbsent(record.packageName, record) }

        val packageManager = ksuApp.packageManager
        val localResult = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
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
        }.forEach { record -> records.putIfAbsent(record.packageName, record) }

        if (rootResult.isFailure && localResult.isFailure) {
            return Result.failure(
                IllegalStateException(
                    "root and local package queries failed; " +
                        "root=${rootResult.exceptionOrNull()?.message ?: "unknown"}, " +
                        "local=${localResult.exceptionOrNull()?.message ?: "unknown"}",
                    rootResult.exceptionOrNull() ?: localResult.exceptionOrNull(),
                ),
            )
        }
        return Result.success(records.values.sortedBy { it.packageName })
    }

    private fun webPackageRecord(app: AppInfo): WebPackageRecord? {
        val packageInfo = app.packageInfo
        val applicationInfo = packageInfo.applicationInfo ?: return null
        val packageName = packageInfo.packageName.takeIf(String::isNotBlank) ?: return null
        return WebPackageRecord(
            packageName = packageName,
            versionName = packageInfo.versionName.orEmpty(),
            versionCode = PackageInfoCompat.getLongVersionCode(packageInfo),
            appLabel = app.label.ifBlank { packageName },
            isSystem = (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            uid = applicationInfo.uid,
        )
    }

    private fun getAppSnapshot(forceRefresh: Boolean): Result<AppSnapshot> {
        val now = SystemClock.elapsedRealtime()
        synchronized(appSnapshotLock) {
            val cached = cachedAppSnapshot
            if (!forceRefresh && cached != null && now - cached.cachedAt < APP_SNAPSHOT_TTL_MILLIS) {
                return Result.success(cached)
            }

            val repositoryResult = runCatching {
                runBlocking { superUserRepository.getAppList() }
            }
            val result = repositoryResult.getOrNull()
            val snapshot = result?.getOrNull()?.let { (apps, userIds) ->
                AppSnapshot(
                    apps = apps,
                    userIds = userIds,
                    cachedAt = now,
                )
            }
            if (snapshot != null) {
                cachedAppSnapshot = snapshot
                return Result.success(snapshot)
            }
            return Result.failure(
                repositoryResult.exceptionOrNull()
                    ?: result?.exceptionOrNull()
                    ?: IllegalStateException("superuser repository returned no data"),
            )
        }
    }

    private fun invalidateAppSnapshot() = synchronized(appSnapshotLock) {
        cachedAppSnapshot = null
    }

    private fun invalidateModuleSnapshot() = synchronized(moduleSnapshotLock) {
        cachedModuleSnapshot = null
    }

    private fun uptimeSeconds(): Long {
        val started = synchronized(lock) { startedAtElapsedRealtime }
        return if (started > 0L) {
            ((SystemClock.elapsedRealtime() - started) / 1_000L).coerceAtLeast(0L)
        } else {
            0L
        }
    }

    private fun buildExecRequest(body: String): String {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: throw IllegalArgumentException("invalid command body")
        val command = json.optString("command")
        if (command.isBlank() || command.length > MAX_EXEC_COMMAND_LENGTH || command.contains('\u0000')) {
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
        val options = when (val value = json.opt("options")) {
            is JSONObject -> value
            is String -> runCatching { JSONObject(value) }.getOrElse {
                throw IllegalArgumentException("invalid command options")
            }
            null -> JSONObject()
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
            val keys = env.keys().asSequence().toList().sorted()
            keys.forEach { key ->
                val value = env.optString(key)
                if (!envKeyPattern.matches(key) || value.length > MAX_EXEC_ENV_VALUE_LENGTH || value.contains('\u0000')) {
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

    private fun webUiExecResponse(target: URI, body: String): HttpResponse {
        if (!hasActiveWebUiModule(query(target.rawQuery)["module"])) {
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

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

    private fun respond(
        output: BufferedOutputStream,
        status: Int,
        body: String,
        contentType: String = "application/json; charset=utf-8",
    ) {
        respond(
            output,
            HttpResponse(
                status = status,
                body = body,
                contentType = contentType,
            ),
        )
    }

    private fun respond(output: BufferedOutputStream, response: HttpResponse) {
        val bodyBytes = response.body?.toByteArray(StandardCharsets.UTF_8)
        val stream = response.stream
        val reason = when (response.status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            408 -> "Request Timeout"
            413 -> "Payload Too Large"
            414 -> "URI Too Long"
            431 -> "Request Header Fields Too Large"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
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
        val header = "HTTP/1.1 ${response.status} $reason\r\n" +
            "Content-Type: ${response.contentType}\r\n" +
            lengthHeader +
            cookie +
            "Cache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
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
            if (current < 0) return if (bytes.size() == 0) null else bytes.toString(StandardCharsets.ISO_8859_1.name())
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
            val key = runCatching { URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name()) }.getOrNull() ?: return@mapNotNull null
            val value = runCatching { URLDecoder.decode(parts.getOrElse(1) { "" }, StandardCharsets.UTF_8.name()) }.getOrNull() ?: ""
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

    private fun url(accessToken: String): String = "http://127.0.0.1:$WEB_MANAGER_PORT/?token=$accessToken"

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private data class HttpResponse(
        val status: Int,
        val body: String? = null,
        val contentType: String = "application/json; charset=utf-8",
        val stream: StreamBody? = null,
        val setCookie: Boolean = false,
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

    private data class AppSnapshot(
        val apps: List<AppInfo>,
        val userIds: List<Int>,
        val cachedAt: Long,
    )

    private data class ModuleSnapshot(
        val rawJson: String,
        val cachedAt: Long,
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
