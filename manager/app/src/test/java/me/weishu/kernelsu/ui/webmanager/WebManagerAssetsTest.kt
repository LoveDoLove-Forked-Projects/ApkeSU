package me.weishu.kernelsu.ui.webmanager

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebManagerAssetsTest {
    @Test
    fun wallpaperTargetsMatchHomeCards() {
        assertEquals(listOf("lkm", "superuser", "module", "device"), WebManagerAssets.WALLPAPER_TARGETS.keys.toList())
    }

    @Test
    fun navIconSlotsMatchBottomBar() {
        assertEquals(
            listOf("home", "superuser", "module", "kpm", "settings"),
            WebManagerAssets.NAV_ICON_SLOTS.keys.toList(),
        )
    }

    @Test
    fun acceptsKnownAssetsOnly() {
        assertTrue(WebManagerAssets.isValidAsset("wallpaper", "lkm"))
        assertTrue(WebManagerAssets.isValidAsset("navicon", "settings"))
        assertFalse(WebManagerAssets.isValidAsset("wallpaper", "settings"))
        assertFalse(WebManagerAssets.isValidAsset("navicon", "lkm"))
        assertFalse(WebManagerAssets.isValidAsset("wallpaper", "../etc/passwd"))
        assertFalse(WebManagerAssets.isValidAsset("other", "lkm"))
        assertFalse(WebManagerAssets.isValidAsset("wallpaper", ""))
    }

    @Test
    fun assetFileNameNeverBuildsPath() {
        assertEquals("wallpaper-lkm.img", WebManagerAssets.assetFileName("wallpaper", "lkm"))
        assertEquals("navicon-home.img", WebManagerAssets.assetFileName("navicon", "home"))
        assertFalse(WebManagerAssets.assetFileName("wallpaper", "lkm").contains("/"))
    }

    @Test
    fun wallpaperMetaUsesDefaultsWhenEmpty() {
        val meta = WebManagerAssets.normalizeMeta(WebManagerAssets.KIND_WALLPAPER, null, 1_700_000_000_000L)
        assertEquals("cover", meta.getString("fit"))
        assertEquals(1.0, meta.getDouble("scale"), 0.0001)
        assertEquals(0.0, meta.getDouble("offsetX"), 0.0001)
        assertEquals(0.0, meta.getDouble("offsetY"), 0.0001)
        assertEquals(0.35, meta.getDouble("dim"), 0.0001)
        assertEquals(0.0, meta.getDouble("blur"), 0.0001)
        assertEquals("wallpaper", meta.getString("kind"))
        assertEquals(1_700_000_000_000L, meta.getLong("updatedAt"))
    }

    @Test
    fun wallpaperMetaClampsOutOfRangeValues() {
        val raw = JSONObject()
            .put("fit", "SOMETHING")
            .put("scale", 99.0)
            .put("offsetX", -400.0)
            .put("offsetY", 400.0)
            .put("dim", 5.0)
            .put("blur", -3.0)
        val meta = WebManagerAssets.normalizeMeta(WebManagerAssets.KIND_WALLPAPER, raw, 1L)
        assertEquals("cover", meta.getString("fit"))
        assertEquals(3.0, meta.getDouble("scale"), 0.0001)
        assertEquals(-50.0, meta.getDouble("offsetX"), 0.0001)
        assertEquals(50.0, meta.getDouble("offsetY"), 0.0001)
        assertEquals(0.85, meta.getDouble("dim"), 0.0001)
        assertEquals(0.0, meta.getDouble("blur"), 0.0001)
    }

    @Test
    fun wallpaperFitKeepsAllowedValues() {
        for (fit in listOf("cover", "contain", "stretch")) {
            val meta = WebManagerAssets.normalizeMeta(
                WebManagerAssets.KIND_WALLPAPER,
                JSONObject().put("fit", fit.uppercase()),
                1L,
            )
            assertEquals(fit, meta.getString("fit"))
        }
    }

    @Test
    fun navIconMetaClampsTighterThanWallpaper() {
        val raw = JSONObject().put("scale", 4.0).put("offsetX", -40.0).put("offsetY", 40.0)
        val meta = WebManagerAssets.normalizeMeta(WebManagerAssets.KIND_NAV_ICON, raw, 2L)
        assertEquals(3.0, meta.getDouble("scale"), 0.0001)
        assertEquals(-12.0, meta.getDouble("offsetX"), 0.0001)
        assertEquals(12.0, meta.getDouble("offsetY"), 0.0001)
        assertFalse(meta.has("fit"))
        assertFalse(meta.has("dim"))
    }

    @Test
    fun parseMetaIgnoresBrokenStoredJson() {
        assertNull(WebManagerAssets.parseMeta(WebManagerAssets.KIND_WALLPAPER, null, 1L))
        assertNull(WebManagerAssets.parseMeta(WebManagerAssets.KIND_WALLPAPER, "   ", 1L))
        assertNull(WebManagerAssets.parseMeta(WebManagerAssets.KIND_WALLPAPER, "{not json", 1L))
    }

    @Test
    fun parseMetaKeepsStoredUpdatedAt() {
        val stored = JSONObject()
            .put("fit", "contain")
            .put("scale", 1.4)
            .put("updatedAt", 42L)
            .toString()
        val meta = WebManagerAssets.parseMeta(WebManagerAssets.KIND_WALLPAPER, stored, 999L)
        assertEquals(42L, meta!!.getLong("updatedAt"))
        assertEquals("contain", meta.getString("fit"))
        assertEquals(1.4, meta.getDouble("scale"), 0.0001)
    }

    @Test
    fun metaKeyIsNamespacedPerAsset() {
        assertEquals("web_manager_asset_wallpaper_lkm", WebManagerAssets.metaKey("wallpaper", "lkm"))
        assertEquals("web_manager_asset_navicon_kpm", WebManagerAssets.metaKey("navicon", "kpm"))
    }

    @Test
    fun moduleWallNamesAcceptModuleIds() {
        assertTrue(WebManagerAssets.isValidAsset("modulewall", "hybrid_mount"))
        assertTrue(WebManagerAssets.isValidAsset("modulewall", "KPatch-Next"))
        assertTrue(WebManagerAssets.isValidAsset("modulewall", "kr-susfs"))
        assertTrue(WebManagerAssets.isValidAsset("modulewall", "a.b_c-1"))
    }

    @Test
    fun moduleWallNamesRejectPathsAndHiddenEntries() {
        assertFalse(WebManagerAssets.isValidAsset("modulewall", ""))
        assertFalse(WebManagerAssets.isValidAsset("modulewall", "."))
        assertFalse(WebManagerAssets.isValidAsset("modulewall", ".."))
        assertFalse(WebManagerAssets.isValidAsset("modulewall", ".hidden"))
        assertFalse(WebManagerAssets.isValidAsset("modulewall", "../etc/passwd"))
        assertFalse(WebManagerAssets.isValidAsset("modulewall", "a/b"))
        assertFalse(WebManagerAssets.isValidAsset("modulewall", "a b"))
        assertFalse(WebManagerAssets.isValidAsset("modulewall", "a".repeat(65)))
        // 模块壁纸不能占用主页卡片/导航图标的保留名字
        assertFalse(WebManagerAssets.isValidAsset("wallpaper", "hybrid_mount"))
        assertFalse(WebManagerAssets.isValidAsset("navicon", "hybrid_mount"))
    }

    @Test
    fun moduleWallUsesWallpaperParameters() {
        val meta = WebManagerAssets.normalizeMeta(
            WebManagerAssets.KIND_MODULE_WALL,
            JSONObject().put("fit", "contain").put("scale", 1.3).put("dim", 0.6).put("blur", 2.0),
            3L,
        )
        assertEquals("contain", meta.getString("fit"))
        assertEquals(1.3, meta.getDouble("scale"), 0.0001)
        assertEquals(0.6, meta.getDouble("dim"), 0.0001)
        assertEquals(2.0, meta.getDouble("blur"), 0.0001)
        assertEquals("modulewall", meta.getString("kind"))
        assertFalse(meta.has("offsetY") && meta.getDouble("offsetY") > 50.0)
    }

    @Test
    fun moduleWallFileNameStaysFlat() {
        val name = WebManagerAssets.assetFileName(WebManagerAssets.KIND_MODULE_WALL, "hybrid_mount")
        assertEquals("modulewall-hybrid_mount.img", name)
        assertFalse(name.contains("/"))
        assertEquals("web_manager_asset_modulewall_hybrid_mount",
            WebManagerAssets.metaKey(WebManagerAssets.KIND_MODULE_WALL, "hybrid_mount"))
    }

    @Test
    fun detectsImageTypesFromMagicBytes() {
        assertEquals("image/png", WebManagerAssets.detectImageMime(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertEquals("image/jpeg", WebManagerAssets.detectImageMime(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals("image/gif", WebManagerAssets.detectImageMime("GIF89a".toByteArray()))
        val webp = "RIFF".toByteArray() + byteArrayOf(0x20, 0x00, 0x00, 0x00) + "WEBP".toByteArray()
        assertEquals("image/webp", WebManagerAssets.detectImageMime(webp))
    }

    @Test
    fun unknownBytesAreRejected() {
        assertNull(WebManagerAssets.detectImageMime(byteArrayOf(1, 2, 3)))
        assertNull(WebManagerAssets.detectImageMime(ByteArray(0)))
    }
}
