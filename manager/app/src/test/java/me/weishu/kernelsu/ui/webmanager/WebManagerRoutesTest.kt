package me.weishu.kernelsu.ui.webmanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebManagerRoutesTest {
    private val token = "0123456789abcdef0123456789abcdef"

    @Test
    fun tokenPathSplitsTokenFromRoute() {
        val parsed = WebManagerRoutes.parseTokenPath("/w/$token/api/status")
        assertEquals(token, parsed?.token)
        assertEquals("/api/status", parsed?.routePath)

        val webUi = WebManagerRoutes.parseTokenPath("/w/$token/webui/example.module/index.html")
        assertEquals("/webui/example.module/index.html", webUi?.routePath)
    }

    @Test
    fun tokenPathRejectsMalformedPrefixes() {
        assertNull(WebManagerRoutes.parseTokenPath("/api/status"))
        assertNull(WebManagerRoutes.parseTokenPath("/w/$token"))
        assertNull(WebManagerRoutes.parseTokenPath("/w//api/status"))
        assertNull(WebManagerRoutes.parseTokenPath("/w/short/api/status"))
        assertNull(WebManagerRoutes.parseTokenPath("/w/not-hexadecimal-value/api/status"))
        assertNull(WebManagerRoutes.parseTokenPath("/w/$token/"))
    }

    @Test
    fun tokenPrefixIsEmptyWithoutToken() {
        assertEquals("", WebManagerRoutes.tokenPrefix(null))
        assertEquals("", WebManagerRoutes.tokenPrefix(""))
        assertEquals("/w/$token", WebManagerRoutes.tokenPrefix(token))
    }

    @Test
    fun routePathRemovesTokenScopeBeforeClassification() {
        assertEquals(
            "/api/assets/wallpaper/lkm",
            WebManagerRoutes.routePath("/w/$token/api/assets/wallpaper/lkm"),
        )
        assertEquals("/api/status", WebManagerRoutes.routePath("/api/status"))
    }

    @Test
    fun rootRelativeWebUiAssetUsesAuthenticatedReferrer() {
        assertEquals(
            "/webui/example.module/assets/index.js",
            WebManagerRoutes.resolveRootRelativeWebUiAsset(
                requestPath = "/assets/index.js",
                referrer = "http://127.0.0.1:10240/w/$token/webui/example.module/",
                port = 10240,
                activeToken = token,
            ),
        )
        assertEquals(
            "/w/$token/webui/example.module/",
            WebManagerRoutes.webUiBaseUrl("/w/$token", "example.module"),
        )
    }

    @Test
    fun rootRelativeWebUiAssetRejectsForeignOrManagerRoutes() {
        val referrer = "http://127.0.0.1:10240/w/$token/webui/example.module/"
        assertNull(WebManagerRoutes.resolveRootRelativeWebUiAsset("/api/status", referrer, 10240, token))
        assertNull(WebManagerRoutes.resolveRootRelativeWebUiAsset("/assets/app.js", referrer, 10241, token))
        assertNull(
            WebManagerRoutes.resolveRootRelativeWebUiAsset(
                "/assets/app.js",
                "http://example.com:10240/w/$token/webui/example.module/",
                10240,
                token,
            ),
        )
    }

    @Test
    fun webUiAssetMapsDirectoryRequestsToIndexHtml() {
        val root = WebManagerRoutes.resolveWebUiAsset("/webui/example.module/")
        assertTrue(root is WebManagerRoutes.AssetResolution.Asset)
        assertEquals("example.module", (root as WebManagerRoutes.AssetResolution.Asset).moduleId)
        assertEquals("index.html", root.relativePath)

        val bare = WebManagerRoutes.resolveWebUiAsset("/webui/example.module")
        assertEquals("index.html", (bare as WebManagerRoutes.AssetResolution.Asset).relativePath)

        val nested = WebManagerRoutes.resolveWebUiAsset("/webui/example.module/assets/app.js")
        assertEquals("assets/app.js", (nested as WebManagerRoutes.AssetResolution.Asset).relativePath)

        val dot = WebManagerRoutes.resolveWebUiAsset("/webui/example.module/.")
        assertEquals("index.html", (dot as WebManagerRoutes.AssetResolution.Asset).relativePath)
    }

    @Test
    fun webUiAssetRejectsTraversalAndBadModuleIds() {
        val traversal = WebManagerRoutes.resolveWebUiAsset("/webui/example.module/../other/secret.txt")
        assertTrue(traversal is WebManagerRoutes.AssetResolution.Failure)
        assertEquals(400, (traversal as WebManagerRoutes.AssetResolution.Failure).status)

        // The server only ever sees URI-decoded paths, so a real backslash or a
        // "." segment must both be rejected.
        val backslash = WebManagerRoutes.resolveWebUiAsset("/webui/example.module/..\\secret")
        assertEquals(400, (backslash as WebManagerRoutes.AssetResolution.Failure).status)

        val dotSegment = WebManagerRoutes.resolveWebUiAsset("/webui/example.module/./secret")
        assertEquals(400, (dotSegment as WebManagerRoutes.AssetResolution.Failure).status)

        val badId = WebManagerRoutes.resolveWebUiAsset("/webui/../secret/index.html")
        assertEquals(404, (badId as WebManagerRoutes.AssetResolution.Failure).status)

        val other = WebManagerRoutes.resolveWebUiAsset("/api/status")
        assertEquals(404, (other as WebManagerRoutes.AssetResolution.Failure).status)
    }

    @Test
    fun webUiAssetRejectsOversizedPaths() {
        val long = "/webui/example.module/" + "a".repeat(WebManagerRoutes.MAX_WEB_PATH_LENGTH)
        val result = WebManagerRoutes.resolveWebUiAsset(long)
        assertEquals(414, (result as WebManagerRoutes.AssetResolution.Failure).status)
    }

    @Test
    fun moduleApiPathDecodesModuleId() {
        val parsed = WebManagerRoutes.parseModuleApiPath("/api/modules/example%2Emodule/action")
        assertEquals("example.module", parsed?.moduleId)
        assertEquals("action", parsed?.action)

        assertNull(WebManagerRoutes.parseModuleApiPath("/api/modules/module%2Fname/action"))
        assertNull(WebManagerRoutes.parseModuleApiPath("/api/modules/example.module"))
        assertNull(WebManagerRoutes.parseModuleApiPath("/api/superuser/2000/grant"))
    }

    @Test
    fun jobApiPathAcceptsSnapshotsAndCancel() {
        val snapshot = WebManagerRoutes.parseJobApiPath("/api/jobs/job-abc123")
        assertEquals("job-abc123", snapshot?.jobId)
        assertNull(snapshot?.action)

        val cancel = WebManagerRoutes.parseJobApiPath("/api/jobs/job-abc123/cancel")
        assertEquals("cancel", cancel?.action)

        assertNull(WebManagerRoutes.parseJobApiPath("/api/jobs/job-abc123/stop"))
        assertNull(WebManagerRoutes.parseJobApiPath("/api/jobs/short"))
        assertNull(WebManagerRoutes.parseJobApiPath("/api/jobs/job-abc123/extra/cancel"))
    }

    @Test
    fun iconPathValidatesPackageName() {
        val prefixes = listOf("/api/webui/icon/", "/api/icon/")
        assertEquals("com.example.app", WebManagerRoutes.parseIconPath("/api/icon/com.example.app", prefixes))
        assertEquals(
            "com.example.app",
            WebManagerRoutes.parseIconPath("/api/webui/icon/com.example.app", prefixes),
        )
        assertNull(WebManagerRoutes.parseIconPath("/api/icon/", prefixes))
        assertNull(WebManagerRoutes.parseIconPath("/api/icon/../secrets", prefixes))
        assertNull(WebManagerRoutes.parseIconPath("/api/other/com.example.app", prefixes))
    }

    @Test
    fun moduleIconPathMustStayInsideModuleDirectory() {
        val dir = "/data/adb/modules/example.module"
        assertTrue(WebManagerRoutes.isModuleIconPath(dir, "$dir/webuiIcon.png"))
        assertTrue(WebManagerRoutes.isModuleIconPath(dir, "$dir/assets/icon.webp"))
        assertFalse(WebManagerRoutes.isModuleIconPath(dir, "/data/adb/modules/other/icon.png"))
        assertFalse(WebManagerRoutes.isModuleIconPath(dir, "$dir/../other/icon.png"))
        assertFalse(WebManagerRoutes.isModuleIconPath(dir, "$dir/"))
        assertFalse(WebManagerRoutes.isModuleIconPath(dir, ""))
    }

    @Test
    fun bridgeUrlKeepsTokenPrefixAndEncodesModuleId() {
        val url = WebManagerRoutes.bridgeUrl("/w/$token", "example.module")
        assertEquals("/w/$token/__apkesu_webui_bridge.js?module=example.module", url)
        assertEquals(
            "/__apkesu_webui_bridge.js?module=example%20module",
            WebManagerRoutes.bridgeUrl("", "example module"),
        )
    }

    @Test
    fun bridgePathRecognisesInjectedFileName() {
        assertTrue(WebManagerRoutes.isBridgePath("__apkesu_webui_bridge.js"))
        assertTrue(WebManagerRoutes.isBridgePath(WebManagerRoutes.BRIDGE_PATH))
        assertFalse(WebManagerRoutes.isBridgePath("assets/bridge.js"))
        assertFalse(WebManagerRoutes.isBridgePath(""))
    }

    @Test
    fun entryDocumentDetectionDrivesHtmlErrorPages() {
        assertTrue(WebManagerRoutes.isWebUiEntryPath("index.html"))
        assertTrue(WebManagerRoutes.isWebUiEntryPath("INDEX.HTML"))
        assertTrue(WebManagerRoutes.isWebUiEntryPath("index.htm"))
        assertFalse(WebManagerRoutes.isWebUiEntryPath("assets/app.js"))
        assertFalse(WebManagerRoutes.isWebUiEntryPath("index.css"))
        assertFalse(WebManagerRoutes.isWebUiEntryPath(""))

        // 空路径解析成入口文档，其它资源保持原样
        val entry = WebManagerRoutes.resolveWebUiAsset("/webui/example.module/")
        assertEquals("index.html", (entry as WebManagerRoutes.AssetResolution.Asset).relativePath)
        val asset = WebManagerRoutes.resolveWebUiAsset("/webui/example.module/assets/app.js")
        assertEquals("assets/app.js", (asset as WebManagerRoutes.AssetResolution.Asset).relativePath)
    }

    @Test
    fun assetPathParsesKnownKinds() {
        val wallpaper = WebManagerRoutes.parseAssetPath("/api/assets/wallpaper/lkm")
        assertEquals("wallpaper", wallpaper?.kind)
        assertEquals("lkm", wallpaper?.name)

        val navIcon = WebManagerRoutes.parseAssetPath("/api/assets/navicon/settings")
        assertEquals("navicon", navIcon?.kind)
        assertEquals("settings", navIcon?.name)
    }

    @Test
    fun assetPathRejectsUnknownOrMalformed() {
        assertNull(WebManagerRoutes.parseAssetPath("/api/status"))
        assertNull(WebManagerRoutes.parseAssetPath("/api/assets/"))
        assertNull(WebManagerRoutes.parseAssetPath("/api/assets/wallpaper"))
        assertNull(WebManagerRoutes.parseAssetPath("/api/assets/wallpaper/lkm/inner.png"))
        assertNull(WebManagerRoutes.parseAssetPath("/api/assets/wallpaper/unknown"))
        assertNull(WebManagerRoutes.parseAssetPath("/api/assets/other/lkm"))
        assertNull(WebManagerRoutes.parseAssetPath("/api/assets/wallpaper/../secret"))
        assertNull(WebManagerRoutes.parseAssetPath("/api/assets//lkm"))
    }
}
