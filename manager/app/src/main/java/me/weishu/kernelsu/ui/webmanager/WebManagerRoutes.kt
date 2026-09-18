package me.weishu.kernelsu.ui.webmanager

/**
 * Pure path/identifier parsing shared by [WebManagerServer].
 *
 * Every function here is intentionally free of Android dependencies so the
 * routing rules that guard token-scoped resources and module assets can be
 * covered by JVM unit tests.
 */
internal object WebManagerRoutes {
    const val TOKEN_PATH_PREFIX = "/w/"
    const val WEBUI_PATH_PREFIX = "/webui/"
    const val ASSET_PATH_PREFIX = "/api/assets/"
    const val BRIDGE_PATH = "/__apkesu_webui_bridge.js"
    const val BRIDGE_FILE_NAME = "__apkesu_webui_bridge.js"
    const val MAX_WEB_PATH_LENGTH = 2_048

    /** `/api/assets/<kind>/<name>`（自定义壁纸与导航栏图标）。 */
    data class AssetPath(val kind: String, val name: String)

    private val tokenPattern = Regex("[0-9a-fA-F]{16,128}")

    /** `/w/<token>/api/status` → token + `/api/status`. */
    data class TokenPath(val token: String, val routePath: String)

    sealed interface AssetResolution {
        data class Asset(val moduleId: String, val relativePath: String) : AssetResolution

        data class Failure(val status: Int, val message: String) : AssetResolution
    }

    data class ModuleApiPath(val moduleId: String, val action: String)

    data class JobApiPath(val jobId: String, val action: String?)

    /** Builds `/w/<token>`; the empty token yields an empty prefix. */
    fun tokenPrefix(token: String?): String = token?.takeIf { it.isNotEmpty() }
        ?.let { TOKEN_PATH_PREFIX + it }
        .orEmpty()

    /**
     * Absolute URL of the injected WebUI bridge script. It always carries the
     * token prefix so the script loads even when the browser drops the cookie.
     */
    fun bridgeUrl(tokenPrefix: String, moduleId: String): String =
        tokenPrefix + BRIDGE_PATH + "?module=" +
            java.net.URLEncoder.encode(moduleId, "UTF-8").replace("+", "%20")

    /**
     * Splits a token-scoped request path. Returns null when the path is not
     * token scoped, so callers keep the regular cookie/header/query auth flow.
     */
    fun parseTokenPath(rawPath: String): TokenPath? {
        if (!rawPath.startsWith(TOKEN_PATH_PREFIX)) return null
        val rest = rawPath.substring(TOKEN_PATH_PREFIX.length)
        val separator = rest.indexOf('/')
        if (separator <= 0) return null
        val token = rest.substring(0, separator)
        if (!tokenPattern.matches(token)) return null
        val routePath = rest.substring(separator)
        if (routePath.length < 2) return null
        return TokenPath(token, routePath)
    }

    /**
     * Resolves a WebUI asset request into the module id and the path relative to
     * that module's `webroot`. Rejects traversal, separators and oversized paths.
     */
    fun resolveWebUiAsset(rawPath: String): AssetResolution {
        if (!rawPath.startsWith(WEBUI_PATH_PREFIX)) {
            return AssetResolution.Failure(404, "module WebUI is unavailable")
        }
        if (rawPath.length > MAX_WEB_PATH_LENGTH) {
            return AssetResolution.Failure(414, "WebUI path is too long")
        }
        val suffix = rawPath.removePrefix(WEBUI_PATH_PREFIX)
        val segments = suffix.split('/')
        val moduleId = segments.firstOrNull().orEmpty()
        if (!WebManagerSecurity.isValidModuleId(moduleId)) {
            return AssetResolution.Failure(404, "module WebUI is unavailable")
        }
        val relativePath = segments.drop(1).joinToString("/")
        if (relativePath.isEmpty() || relativePath == "." || relativePath == "./") {
            return AssetResolution.Asset(moduleId, "index.html")
        }
        if (relativePath.length > MAX_WEB_PATH_LENGTH ||
            relativePath.contains('\u0000') ||
            relativePath.contains('\\') ||
            relativePath.split('/').any { it.isEmpty() || it == "." || it == ".." }
        ) {
            return AssetResolution.Failure(400, "invalid WebUI path")
        }
        return AssetResolution.Asset(moduleId, relativePath)
    }

    /** `/api/modules/<id>/{enable,disable,uninstall,undo-uninstall,action,icon}`. */
    fun parseModuleApiPath(path: String): ModuleApiPath? {
        val parts = path.split('/').filter(String::isNotEmpty)
        if (parts.size != 4 || parts[0] != "api" || parts[1] != "modules") return null
        val moduleId = WebManagerSecurity.decodeModuleId(parts[2]) ?: return null
        return ModuleApiPath(moduleId, parts[3])
    }

    /** `/api/jobs/<id>` or `/api/jobs/<id>/cancel`. */
    fun parseJobApiPath(path: String): JobApiPath? {
        val parts = path.split('/').filter(String::isNotEmpty)
        if (parts.size < 3 || parts.size > 4) return null
        if (parts[0] != "api" || parts[1] != "jobs") return null
        val jobId = parts[2].takeIf { id -> id.length in 8..64 && id.all { it.isLetterOrDigit() || it == '-' } }
            ?: return null
        val action = parts.getOrNull(3)
        if (action != null && action != "cancel") return null
        return JobApiPath(jobId, action)
    }

    /** `/api/icon/<package>` and `/api/webui/icon/<package>`. */
    fun parseIconPath(path: String, prefixes: List<String>): String? {
        val prefix = prefixes.firstOrNull { path.startsWith(it) } ?: return null
        val packageName = path.removePrefix(prefix)
        if (packageName.isEmpty() || packageName.length > 256) return null
        if (!packageName.all { it.isLetterOrDigit() || it == '.' || it == '_' }) return null
        return packageName
    }

    /**
     * Guards a ksud-provided module icon path. ksud already resolves the value
     * from `module.prop` and rejects traversal, but the browser manager reads it
     * through a root shell, so the path is validated again against the module
     * directory before it is opened.
     */
    fun isModuleIconPath(moduleDir: String, iconPath: String): Boolean {
        if (iconPath.isBlank() || iconPath.length > MAX_WEB_PATH_LENGTH) return false
        if (iconPath.contains('\u0000')) return false
        val normalizedDir = moduleDir.trimEnd('/') + "/"
        if (!iconPath.startsWith(normalizedDir)) return false
        return iconPath.removePrefix(normalizedDir)
            .split('/')
            .none { it.isEmpty() || it == "." || it == ".." }
    }

    /** True when the decoded path points at the injected WebUI bridge script. */
    fun isBridgePath(relativePath: String): Boolean =
        relativePath == BRIDGE_FILE_NAME || relativePath == "$BRIDGE_PATH"

    /** `/api/assets/<kind>/<name>`。 */
    fun parseAssetPath(path: String): AssetPath? {
        if (!path.startsWith(ASSET_PATH_PREFIX)) return null
        val parts = path.removePrefix(ASSET_PATH_PREFIX).split('/')
        if (parts.size != 2) return null
        val kind = parts[0].trim()
        val name = parts[1].trim()
        // 只有目录里登记过的资源名才算命中，未知名字直接当作没有这条路由。
        if (!WebManagerAssets.isValidAsset(kind, name)) return null
        return AssetPath(kind, name)
    }

    /** 页面入口文档：决定失败时是给 HTML 说明页还是给 JSON。 */
    fun isWebUiEntryPath(relativePath: String): Boolean =
        relativePath.equals("index.html", ignoreCase = true) ||
            relativePath.equals("index.htm", ignoreCase = true)
}
