package me.weishu.kernelsu.ui.webmanager

import org.json.JSONObject

/**
 * 网页管理器的自定义外观资源：主页卡片壁纸与底部导航栏图标。
 *
 * 与原生管理器同一套思路（`HomeMetricCardWallpaperTarget` / `CustomNavigationIconSlot`），
 * 但网页端把图片存在管理器私有目录里、参数存进 SharedPreferences，任何浏览器打开都能用。
 * 这里只做纯逻辑（名称校验、参数收敛、图片类型识别），方便 JVM 单测覆盖。
 */
internal object WebManagerAssets {
    const val KIND_WALLPAPER = "wallpaper"
    const val KIND_NAV_ICON = "navicon"

    /** 模块卡片壁纸：与原生模块页一致，按模块 id 各存一张。 */
    const val KIND_MODULE_WALL = "modulewall"

    /** 单张图片上限（与 KPM 上传共用同一个请求体上限）。 */
    const val MAX_ASSET_BYTES = 8 * 1024 * 1024

    /** 壁纸目标：主卡片（LKM/GKI）、超级用户卡片、模块卡片、设备信息卡片。 */
    val WALLPAPER_TARGETS: Map<String, String> = linkedMapOf(
        "lkm" to "主卡片（LKM/GKI）",
        "superuser" to "超级用户卡片",
        "module" to "模块卡片",
        "device" to "设备信息卡片",
    )

    /** 导航栏图标槽位：与网页管理器底部标签一一对应。 */
    val NAV_ICON_SLOTS: Map<String, String> = linkedMapOf(
        "home" to "主页",
        "superuser" to "授权",
        "module" to "模块",
        "kpm" to "KPM",
        "settings" to "设置",
    )

    private const val DEFAULT_WALLPAPER_FIT = "cover"
    private val wallpaperFits = setOf("cover", "contain", "stretch")

    /** 模块 id 允许的字符集：模块目录名本身，禁止路径分隔与隐藏文件。 */
    private val moduleIdPattern = Regex("[A-Za-z0-9._-]{1,64}")

    fun isValidKind(kind: String): Boolean =
        kind == KIND_WALLPAPER || kind == KIND_NAV_ICON || kind == KIND_MODULE_WALL

    fun namesForKind(kind: String): Set<String> = when (kind) {
        KIND_WALLPAPER -> WALLPAPER_TARGETS.keys
        KIND_NAV_ICON -> NAV_ICON_SLOTS.keys
        else -> emptySet()
    }

    /** 模块卡片壁纸的名字是动态的模块 id，按字符集校验而不是白名单。 */
    fun isValidModuleWallName(name: String): Boolean =
        moduleIdPattern.matches(name) && name != "." && name != ".." && !name.startsWith(".")

    fun isValidAsset(kind: String, name: String): Boolean = when (kind) {
        KIND_MODULE_WALL -> isValidModuleWallName(name)
        else -> name in namesForKind(kind)
    }

    /** 落盘文件名固定由 kind+name 生成，绝不使用用户输入拼接路径。 */
    fun assetFileName(kind: String, name: String): String = "$kind-$name.img"

    fun metaKey(kind: String, name: String): String = "web_manager_asset_${kind}_$name"

    /**
     * 收敛前端传来的外观参数；非法值回落到默认值，避免坏数据把界面搞坏。
     * 壁纸：适应方式 / 缩放 / 水平与垂直偏移 / 变暗 / 模糊；图标：缩放 / 垂直偏移。
     */
    fun normalizeMeta(kind: String, raw: JSONObject?, updatedAt: Long): JSONObject {
        val meta = JSONObject()
        meta.put("kind", kind)
        meta.put("updatedAt", updatedAt)
        if (kind == KIND_NAV_ICON) {
            meta.put("scale", clampDouble(raw?.optDouble("scale") ?: 1.0, 0.5, 1.8))
            meta.put("offsetY", clampDouble(raw?.optDouble("offsetY") ?: 0.0, -8.0, 8.0))
        } else {
            val fit = raw?.optString("fit")?.trim()?.lowercase().orEmpty()
            meta.put("fit", if (fit in wallpaperFits) fit else DEFAULT_WALLPAPER_FIT)
            meta.put("scale", clampDouble(raw?.optDouble("scale") ?: 1.0, 0.5, 3.0))
            meta.put("offsetX", clampDouble(raw?.optDouble("offsetX") ?: 0.0, -50.0, 50.0))
            meta.put("offsetY", clampDouble(raw?.optDouble("offsetY") ?: 0.0, -50.0, 50.0))
            meta.put("dim", clampDouble(raw?.optDouble("dim") ?: 0.35, 0.0, 0.85))
            meta.put("blur", clampDouble(raw?.optDouble("blur") ?: 0.0, 0.0, 12.0))
        }
        return meta
    }

    fun parseMeta(kind: String, stored: String?, updatedAt: Long): JSONObject? {
        val raw = stored?.takeIf { it.isNotBlank() }
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return null
        return normalizeMeta(kind, raw, raw.optLong("updatedAt", updatedAt))
    }

    /** 由文件头识别允许的图片类型，未知内容必须在落盘前拒绝。 */
    fun detectImageMime(bytes: ByteArray): String? {
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        ) {
            return "image/jpeg"
        }
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) {
            return "image/png"
        }
        if (bytes.size >= 12 &&
            bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
            bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
            bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
        ) {
            return "image/webp"
        }
        if (bytes.size >= 6 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte()) {
            return "image/gif"
        }
        return null
    }

    private fun clampDouble(value: Double, min: Double, max: Double): Double {
        if (value.isNaN()) return min
        return value.coerceIn(min, max)
    }
}
