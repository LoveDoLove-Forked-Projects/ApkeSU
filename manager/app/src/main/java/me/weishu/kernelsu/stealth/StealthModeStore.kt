package me.weishu.kernelsu.stealth

import android.content.Context
import android.os.Process
import androidx.core.content.edit
import com.topjohnwu.superuser.ShellUtils
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.util.withNewRootShell
import kotlin.concurrent.thread

const val STEALTH_MODE_ENABLED_KEY = "stealth_mode_enabled"
const val STEALTH_MODE_CODE_KEY = "stealth_mode_code"
const val DEFAULT_STEALTH_MODE_CODE = "*#*#4211#*#*"
const val STEALTH_MODE_DISABLED_ACTION = "me.weishu.kernelsu.action.STEALTH_MODE_DISABLED"
const val STEALTH_MODE_CHANGED_ACTION = "me.weishu.kernelsu.action.STEALTH_MODE_CHANGED"

private const val SETTINGS_PREFS = "settings"
private const val STEALTH_CODE_PATH = "/data/adb/ksu/hansu_code"
private const val STEALTH_MODE_PATH = "/data/adb/ksu/hansu_mode"
private val SECRET_CODE_PATTERN = Regex("^\\*#\\*#([0-9]{3,16})#\\*#\\*$")
private val SECRET_CODE_HOST_PATTERN = Regex("^[0-9]{3,16}$")

data class StealthModeRootState(
    val enabled: Boolean,
    val codeBackedUp: Boolean,
    val code: String?,
)

object StealthModeStore {
    fun isEnabled(context: Context = ksuApp): Boolean = preferences(context)
        .getBoolean(STEALTH_MODE_ENABLED_KEY, false)

    fun code(context: Context = ksuApp): String = normalizeCode(
        preferences(context).getString(STEALTH_MODE_CODE_KEY, DEFAULT_STEALTH_MODE_CODE).orEmpty()
    ) ?: DEFAULT_STEALTH_MODE_CODE

    fun normalizeCode(value: String): String? {
        val trimmed = value.trim()
        if (SECRET_CODE_HOST_PATTERN.matches(trimmed)) {
            return "*#*#$trimmed#*#*"
        }
        return SECRET_CODE_PATTERN.matchEntire(trimmed)?.let { match ->
            "*#*#${match.groupValues[1]}#*#*"
        }
    }

    fun secretCodeHost(value: String): String? = normalizeCode(value)
        ?.let(SECRET_CODE_PATTERN::matchEntire)
        ?.groupValues
        ?.get(1)

    fun matchesSecretCode(host: String?, configuredCode: String): Boolean {
        return host != null && host == secretCodeHost(configuredCode)
    }

    fun matchesRequestedCode(requestedCode: String?, configuredCode: String): Boolean {
        val requested = requestedCode?.let(::normalizeCode) ?: return false
        val configured = normalizeCode(configuredCode) ?: return false
        return requested == configured
    }

    fun setEnabledBlocking(
        enabled: Boolean,
        requestedCode: String = code(),
        context: Context = ksuApp,
    ): Result<Unit> = runCatching {
        val normalizedCode = normalizeCode(requestedCode) ?: error("invalid secret code")
        val temporaryCodePath = "$STEALTH_CODE_PATH.tmp.${Process.myPid()}"
        val command = buildString {
            append("umask 077; mkdir -p /data/adb/ksu && ")
            append("printf '%s\\n' '")
            append(normalizedCode)
            append("' > ")
            append(temporaryCodePath)
            append(" && chmod 600 ")
            append(temporaryCodePath)
            append(" && mv -f ")
            append(temporaryCodePath)
            append(" ")
            append(STEALTH_CODE_PATH)
            if (enabled) {
                append(" && : > ")
                append(STEALTH_MODE_PATH)
                append(" && chmod 600 ")
                append(STEALTH_MODE_PATH)
            } else {
                append(" && rm -f ")
                append(STEALTH_MODE_PATH)
            }
        }
        val result = withNewRootShell {
            check(isRoot) { "root shell unavailable" }
            ShellUtils.fastCmdResult(this, command)
        }
        check(result) { "root command failed" }
        updateLocalState(context, enabled, normalizedCode)
    }

    fun readRootState(): Result<StealthModeRootState> = runCatching {
        val output = withNewRootShell {
            check(isRoot) { "root shell unavailable" }
            ShellUtils.fastCmd(
                this,
                "if [ -f $STEALTH_MODE_PATH ]; then echo enabled; else echo disabled; fi; " +
                    "if [ -f $STEALTH_CODE_PATH ]; then sed -n '1p' $STEALTH_CODE_PATH; fi",
            )
        }.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val mode = output.firstOrNull()
        check(mode == "enabled" || mode == "disabled") { "invalid root stealth state" }
        val enabled = mode == "enabled"
        val rootCode = output.getOrNull(1)?.let(::normalizeCode)
        StealthModeRootState(
            enabled = enabled,
            codeBackedUp = rootCode != null,
            code = rootCode,
        )
    }

    fun updateLocalState(
        context: Context,
        enabled: Boolean,
        normalizedCode: String = code(context),
    ) {
        preferences(context).edit(commit = true) {
            putBoolean(STEALTH_MODE_ENABLED_KEY, enabled)
            putString(STEALTH_MODE_CODE_KEY, normalizeCode(normalizedCode) ?: DEFAULT_STEALTH_MODE_CODE)
        }
    }

    fun reconcileFromRootAsync(context: Context = ksuApp) {
        val appContext = context.applicationContext
        thread(name = "stealth-mode-reconcile", isDaemon = true) {
            reconcileFromRoot(appContext)
        }
    }

    fun reconcileFromRoot(context: Context = ksuApp): Result<StealthModeRootState> {
        val appContext = context.applicationContext
        return readRootState().onSuccess { rootState ->
            updateLocalState(
                appContext,
                rootState.enabled,
                rootState.code ?: code(appContext),
            )
        }
    }

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
}
