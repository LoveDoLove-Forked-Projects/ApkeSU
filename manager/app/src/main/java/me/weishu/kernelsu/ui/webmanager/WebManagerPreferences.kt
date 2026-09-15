package me.weishu.kernelsu.ui.webmanager

import android.content.Context

internal const val WEB_MANAGER_PORT = 10240
internal const val WEB_MANAGER_AUTO_START_KEY = "web_manager_auto_start"

internal object WebManagerPreferences {
    fun isAutoStartEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getBoolean(WEB_MANAGER_AUTO_START_KEY, false)

    fun setAutoStartEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean(WEB_MANAGER_AUTO_START_KEY, enabled)
            .apply()
    }
}
