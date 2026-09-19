package me.weishu.kernelsu.ui.webmanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import com.topjohnwu.superuser.ShellUtils
import me.weishu.kernelsu.data.repository.SettingsRepositoryImpl
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.util.AppLanguageManager
import me.weishu.kernelsu.ui.util.withNewRootShell
import org.json.JSONObject
import java.util.concurrent.Executors

const val MANAGER_SETTINGS_CHANGED_ACTION = "me.weishu.kernelsu.action.MANAGER_SETTINGS_CHANGED"

internal const val MANAGER_APP_SETTINGS_PATH = "/data/adb/ksu/web_manager_app_settings.json"
internal const val MANAGER_APP_SETTINGS_SCHEMA_VERSION = 1
internal const val MAX_CUSTOM_HOME_TITLE_LENGTH = 40

internal data class ManagerAppSettings(
    val language: String,
    val checkModuleUpdate: Boolean,
    val showVersionMismatchWarning: Boolean,
    val showGkiWarning: Boolean,
    val showHomeSupportCard: Boolean,
    val showHomeLearnCard: Boolean,
    val customHomeTitle: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schemaVersion", MANAGER_APP_SETTINGS_SCHEMA_VERSION)
        .put("language", language)
        .put("checkModuleUpdate", checkModuleUpdate)
        .put("showVersionMismatchWarning", showVersionMismatchWarning)
        .put("showGkiWarning", showGkiWarning)
        .put("showHomeSupportCard", showHomeSupportCard)
        .put("showHomeLearnCard", showHomeLearnCard)
        .put("customHomeTitle", customHomeTitle)

    companion object {
        private val supportedLanguageTags = AppLanguageManager.supportedLanguages
            .mapTo(linkedSetOf()) { it.languageTag }
        private val allowedKeys = setOf(
            "schemaVersion",
            "language",
            "checkModuleUpdate",
            "showVersionMismatchWarning",
            "showGkiWarning",
            "showHomeSupportCard",
            "showHomeLearnCard",
            "customHomeTitle",
        )

        fun fromJson(json: JSONObject): ManagerAppSettings {
            require(json.optInt("schemaVersion", -1) == MANAGER_APP_SETTINGS_SCHEMA_VERSION) {
                "unsupported manager settings schema"
            }
            val unknownKeys = json.keys().asSequence().filterNot(allowedKeys::contains).toList()
            require(unknownKeys.isEmpty()) { "unknown manager settings: ${unknownKeys.joinToString()}" }
            val language = json.requireString("language")
            require(language in supportedLanguageTags) { "unsupported manager language" }
            val customHomeTitle = json.requireString("customHomeTitle").trim()
            require(customHomeTitle.length <= MAX_CUSTOM_HOME_TITLE_LENGTH) {
                "custom home title is too long"
            }
            return ManagerAppSettings(
                language = language,
                checkModuleUpdate = json.requireBoolean("checkModuleUpdate"),
                showVersionMismatchWarning = json.requireBoolean("showVersionMismatchWarning"),
                showGkiWarning = json.requireBoolean("showGkiWarning"),
                showHomeSupportCard = json.requireBoolean("showHomeSupportCard"),
                showHomeLearnCard = json.requireBoolean("showHomeLearnCard"),
                customHomeTitle = customHomeTitle,
            )
        }

        fun updated(current: ManagerAppSettings, payload: JSONObject): ManagerAppSettings {
            val supportedKeys = allowedKeys - "schemaVersion"
            val unknownKeys = payload.keys().asSequence().filterNot(supportedKeys::contains).toList()
            require(unknownKeys.isEmpty()) { "unknown manager settings: ${unknownKeys.joinToString()}" }
            require(payload.length() > 0) { "manager settings update is empty" }
            val language = payload.optionalString("language") ?: current.language
            require(language in supportedLanguageTags) { "unsupported manager language" }
            val customHomeTitle = (payload.optionalString("customHomeTitle") ?: current.customHomeTitle).trim()
            require(customHomeTitle.length <= MAX_CUSTOM_HOME_TITLE_LENGTH) {
                "custom home title is too long"
            }
            return current.copy(
                language = language,
                checkModuleUpdate = payload.optionalBoolean("checkModuleUpdate")
                    ?: current.checkModuleUpdate,
                showVersionMismatchWarning = payload.optionalBoolean("showVersionMismatchWarning")
                    ?: current.showVersionMismatchWarning,
                showGkiWarning = payload.optionalBoolean("showGkiWarning") ?: current.showGkiWarning,
                showHomeSupportCard = payload.optionalBoolean("showHomeSupportCard")
                    ?: current.showHomeSupportCard,
                showHomeLearnCard = payload.optionalBoolean("showHomeLearnCard")
                    ?: current.showHomeLearnCard,
                customHomeTitle = customHomeTitle,
            )
        }

        private fun JSONObject.requireBoolean(key: String): Boolean {
            require(has(key) && get(key) is Boolean) { "$key must be a boolean" }
            return getBoolean(key)
        }

        private fun JSONObject.optionalBoolean(key: String): Boolean? {
            if (!has(key)) return null
            require(get(key) is Boolean) { "$key must be a boolean" }
            return getBoolean(key)
        }

        private fun JSONObject.requireString(key: String): String {
            require(has(key) && get(key) is String) { "$key must be a string" }
            return getString(key)
        }

        private fun JSONObject.optionalString(key: String): String? {
            if (!has(key)) return null
            require(get(key) is String) { "$key must be a string" }
            return getString(key)
        }
    }
}

object ManagerAppSettingsStore {
    private const val TAG = "ManagerAppSettings"
    private const val MISSING_MARKER = "__APK_ESU_MANAGER_SETTINGS_MISSING__"
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "manager-settings-sync").apply { isDaemon = true }
    }

    internal fun snapshot(context: Context = ksuApp): ManagerAppSettings {
        val repository = SettingsRepositoryImpl()
        return ManagerAppSettings(
            language = AppLanguageManager.getSelectedLanguage(context).languageTag,
            checkModuleUpdate = repository.checkModuleUpdate,
            showVersionMismatchWarning = repository.showVersionMismatchWarning,
            showGkiWarning = repository.showGkiWarning,
            showHomeSupportCard = repository.showHomeSupportCard,
            showHomeLearnCard = repository.showHomeLearnCard,
            customHomeTitle = repository.customHomeTitle,
        )
    }

    fun reconcileFromRootAsync(context: Context = ksuApp) {
        val appContext = context.applicationContext
        writer.execute {
            readRootState().onSuccess { rootState ->
                if (rootState == null) {
                    writeRootState(snapshot(appContext)).onFailure { error ->
                        Log.w(TAG, "create manager settings backup failed", error)
                    }
                } else {
                    applyLocalState(appContext, rootState).onFailure { error ->
                        Log.w(TAG, "apply manager settings from root failed", error)
                    }
                }
            }.onFailure { error ->
                Log.w(TAG, "read manager settings backup failed", error)
            }
        }
    }

    fun exportToRootAsync(context: Context = ksuApp) {
        val appContext = context.applicationContext
        writer.execute {
            writeRootState(snapshot(appContext)).onFailure { error ->
                Log.w(TAG, "export manager settings failed", error)
            }
        }
    }

    internal fun applyWebUpdate(
        payload: JSONObject,
        context: Context = ksuApp,
    ): Result<ManagerAppSettings> = runCatching {
        val previous = snapshot(context)
        val updated = ManagerAppSettings.updated(previous, payload)
        applyLocalState(context, updated).getOrThrow()
        writeRootState(updated).onFailure {
            applyLocalState(context, previous)
        }.getOrThrow()
        updated
    }

    internal fun applyRootState(
        settings: ManagerAppSettings,
        context: Context = ksuApp,
    ): Result<Unit> = applyLocalState(context, settings)

    internal fun readRootState(): Result<ManagerAppSettings?> = runCatching {
        val output = withNewRootShell {
            check(isRoot) { "root shell unavailable" }
            ShellUtils.fastCmd(
                this,
                "if [ -f $MANAGER_APP_SETTINGS_PATH ]; then cat $MANAGER_APP_SETTINGS_PATH; " +
                    "else printf '%s\\n' '$MISSING_MARKER'; fi",
            )
        }.trim()
        if (output == MISSING_MARKER) null else ManagerAppSettings.fromJson(JSONObject(output))
    }

    internal fun writeRootState(settings: ManagerAppSettings): Result<Unit> = runCatching {
        val temporaryPath = "$MANAGER_APP_SETTINGS_PATH.tmp.${Process.myPid()}"
        val content = shellQuote(settings.toJson().toString())
        val result = withNewRootShell {
            check(isRoot) { "root shell unavailable" }
            ShellUtils.fastCmdResult(
                this,
                "umask 077; mkdir -p /data/adb/ksu && " +
                    "printf '%s\\n' $content > $temporaryPath && chmod 600 $temporaryPath && " +
                    "mv -f $temporaryPath $MANAGER_APP_SETTINGS_PATH",
            )
        }
        check(result) { "root command failed" }
    }

    private fun applyLocalState(context: Context, settings: ManagerAppSettings): Result<Unit> = runCatching {
        val repository = SettingsRepositoryImpl()
        repository.checkModuleUpdate = settings.checkModuleUpdate
        repository.showVersionMismatchWarning = settings.showVersionMismatchWarning
        repository.showGkiWarning = settings.showGkiWarning
        repository.showHomeSupportCard = settings.showHomeSupportCard
        repository.showHomeLearnCard = settings.showHomeLearnCard
        repository.customHomeTitle = settings.customHomeTitle
        val currentLanguage = AppLanguageManager.getSelectedLanguage(context).languageTag
        if (currentLanguage != settings.language) {
            check(AppLanguageManager.setSelectedLanguage(context, settings.language)) {
                "unable to apply manager language"
            }
        }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
}

class ManagerAppSettingsSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MANAGER_SETTINGS_CHANGED_ACTION) return
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        Thread({
            try {
                ManagerAppSettingsStore.readRootState().onSuccess { rootState ->
                    if (rootState != null) {
                        ManagerAppSettingsStore.applyRootState(rootState, appContext).onFailure { error ->
                            Log.e(TAG, "apply web manager settings failed", error)
                        }
                    }
                }.onFailure { error ->
                    Log.e(TAG, "read web manager settings failed", error)
                }
            } finally {
                pendingResult.finish()
            }
        }, "manager-settings-receiver").apply { isDaemon = true }.start()
    }

    private companion object {
        private const val TAG = "ManagerSettingsReceiver"
    }
}
