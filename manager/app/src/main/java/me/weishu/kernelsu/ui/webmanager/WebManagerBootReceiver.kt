package me.weishu.kernelsu.ui.webmanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class WebManagerBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!WebManagerPreferences.isAutoStartEnabled(context)) return
        runCatching { WebManagerService.start(context) }
            .onFailure { Log.e(TAG, "failed to start web manager after boot", it) }
    }

    private companion object {
        const val TAG = "ApkeSU-WebManager"
    }
}
