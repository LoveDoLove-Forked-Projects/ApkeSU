package me.weishu.kernelsu.ui.webmanager

import android.content.Context
import org.json.JSONObject

internal const val WEB_MANAGER_AUTO_START_KEY = "web_manager_auto_start"
internal const val WEB_MANAGER_THEME_KEY = "web_manager_theme"

internal const val WEB_MANAGER_FIXED_PORT_KEY = "web_manager_fixed_port"
internal const val WEB_MANAGER_LAUNCHER_HIDDEN_KEY = "web_manager_launcher_hidden"

internal object WebManagerPreferences {
    const val THEME_AUTO = "auto"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"
    val THEME_VALUES = setOf(THEME_AUTO, THEME_LIGHT, THEME_DARK)

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun isAutoStartEnabled(context: Context): Boolean =
        prefs(context).getBoolean(WEB_MANAGER_AUTO_START_KEY, false)

    fun setAutoStartEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(WEB_MANAGER_AUTO_START_KEY, enabled).apply()
    }

    /** 固定端口（0 = 随机回环端口）；普通应用无法绑定 1024 以下端口。 */
    fun fixedPort(context: Context): Int =
        prefs(context).getInt(WEB_MANAGER_FIXED_PORT_KEY, 0).takeIf { it in 1024..65535 } ?: 0

    fun setFixedPort(context: Context, port: Int) {
        prefs(context).edit().putInt(WEB_MANAGER_FIXED_PORT_KEY, if (port in 1024..65535) port else 0).apply()
    }

    /** 桌面图标是否被隐藏（隐藏后管理器只能通过网页控制台进入）。 */
    fun isLauncherIconHidden(context: Context): Boolean =
        prefs(context).getBoolean(WEB_MANAGER_LAUNCHER_HIDDEN_KEY, false)

    fun setLauncherIconHidden(context: Context, hidden: Boolean) {
        prefs(context).edit().putBoolean(WEB_MANAGER_LAUNCHER_HIDDEN_KEY, hidden).apply()
    }

    fun theme(context: Context): String =
        prefs(context).getString(WEB_MANAGER_THEME_KEY, THEME_AUTO)
            ?.takeIf { it in THEME_VALUES }
            ?: THEME_AUTO

    fun setTheme(context: Context, value: String) {
        val normalized = value.takeIf { it in THEME_VALUES } ?: THEME_AUTO
        prefs(context).edit().putString(WEB_MANAGER_THEME_KEY, normalized).apply()
    }

    /** 外观资源的参数（图片本体在 filesDir/web-manager-assets 下）。 */
    fun assetMeta(context: Context, kind: String, name: String): JSONObject? {
        val stored = prefs(context).getString(WebManagerAssets.metaKey(kind, name), null)
        return WebManagerAssets.parseMeta(kind, stored, 0L)
    }

    fun setAssetMeta(context: Context, kind: String, name: String, meta: JSONObject) {
        prefs(context).edit()
            .putString(WebManagerAssets.metaKey(kind, name), meta.toString())
            .apply()
    }

    fun clearAssetMeta(context: Context, kind: String, name: String) {
        prefs(context).edit().remove(WebManagerAssets.metaKey(kind, name)).apply()
    }
}
