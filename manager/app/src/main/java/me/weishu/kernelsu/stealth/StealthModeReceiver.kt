package me.weishu.kernelsu.stealth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.concurrent.thread

class SecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in SECRET_CODE_ACTIONS) return
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        thread(name = "stealth-secret-code", isDaemon = true) {
            try {
                val rootState = StealthModeStore.readRootState().getOrNull()
                val enabled = rootState?.enabled ?: StealthModeStore.isEnabled(appContext)
                val configuredCode = rootState?.code
                    ?: StealthModeStore.code(appContext)
                if (enabled && StealthModeStore.matchesSecretCode(intent.data?.host, configuredCode)) {
                    StealthModeStore.setEnabledBlocking(
                        enabled = false,
                        requestedCode = configuredCode,
                        context = appContext,
                    ).onFailure { error ->
                        Log.e(TAG, "disable stealth mode from secret code failed", error)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        private const val TAG = "SecretCodeReceiver"
        private val SECRET_CODE_ACTIONS = setOf(
            "android.provider.Telephony.SECRET_CODE",
            "android.telephony.action.SECRET_CODE",
        )
    }
}

class StealthModeSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != STEALTH_MODE_DISABLED_ACTION &&
            intent.action != STEALTH_MODE_CHANGED_ACTION
        ) return
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        thread(name = "stealth-mode-sync", isDaemon = true) {
            try {
                StealthModeStore.readRootState().onSuccess { rootState ->
                    StealthModeStore.updateLocalState(
                        appContext,
                        rootState.enabled,
                        rootState.code ?: StealthModeStore.code(appContext),
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
