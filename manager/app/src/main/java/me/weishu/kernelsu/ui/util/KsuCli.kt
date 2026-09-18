package me.weishu.kernelsu.ui.util

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Parcelable
import android.os.Process
import android.os.SystemClock
import android.provider.OpenableColumns
import android.system.Os
import android.util.Base64
import android.util.Log
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.parcelize.Parcelize
import me.weishu.kernelsu.BuildConfig
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.core.tasks.BootKernelVersion
import me.weishu.kernelsu.core.tasks.ExtractImage
import me.weishu.kernelsu.core.tasks.ProbeResult
import me.weishu.kernelsu.core.utils.DataSourceChannel
import me.weishu.kernelsu.ksuApp
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * @author weishu
 * @date 2023/1/1.
 */
private const val TAG = "KsuCli"
private const val SHELL_JOB_TIMEOUT_MILLIS = 10_000L
private const val STATUS_TIMEOUT_MILLIS = 30_000L
private const val DIAGNOSTIC_TIMEOUT_MILLIS = 60_000L
private const val LONG_IO_TIMEOUT_MILLIS = 300_000L
private const val ERROR_PREFIX = "APKESU_ERROR:"
private const val ANDROID_16_API = 36
private const val BUSYBOX = "/data/adb/ksu/bin/busybox"
const val CPU_SPOOF_PROPERTY_VALUE_LIMIT = 91
private val managerRegistrationLock = Any()
private const val MANAGER_REGISTRATION_RETRY_MILLIS = 30_000L
private const val FIRST_APPLICATION_APPID = 10_000
private const val LAST_APPLICATION_APPID = 19_999
private const val DYNAMIC_MANAGER_STATUS_SCHEMA_VERSION = 2
private const val DYNAMIC_MANAGER_MIN_CERTIFICATE_SIZE = 0x100
private const val DYNAMIC_MANAGER_MAX_CERTIFICATE_SIZE = 0x1000
private val DYNAMIC_MANAGER_CERTIFICATE_SHA256 = Regex("[0-9a-f]{64}")
private var lastManagerRegistrationFailureKey: String? = null
private var lastManagerRegistrationFailureAt = 0L
const val HYBRID_MOUNT_MODULE_ID = "hybrid_mount"
const val KPATCH_NEXT_MODULE_ID = "KPatch-Next"
const val BUILTIN_MOUNT_MODE_OVERLAY = "overlay"
const val BUILTIN_MOUNT_MODE_MAGIC = "magic"
const val BUILTIN_MOUNT_VARIANT_LITE = "lite"
const val BUILTIN_MOUNT_VARIANT_FULL = "full"
const val HIDDEN_PATH_CONFIG_FILE_NAME = "apkesu_hidden_path_config.json"
const val HIDDEN_PATH_CONFIG_MIME_TYPE = "application/json"
const val HIDDEN_PATH_MAX_AUTO_LOAD_DELAY_SECONDS = 300
private const val SUSFS_PATH_CONFIG_DIR = "/data/adb/ksu/susfs"
private const val SUSFS_PATH_CONFIG_FILE = "$SUSFS_PATH_CONFIG_DIR/paths.txt"
private const val SUSFS_PATH_LOOP_CONFIG_FILE = "$SUSFS_PATH_CONFIG_DIR/path_loop.txt"
private const val SUSFS_MAP_CONFIG_FILE = "$SUSFS_PATH_CONFIG_DIR/sus_maps.txt"
private const val SUSFS_OPEN_REDIRECT_CONFIG_FILE = "$SUSFS_PATH_CONFIG_DIR/open_redirect.txt"
private const val SUSFS_KSTAT_CONFIG_FILE = "$SUSFS_PATH_CONFIG_DIR/sus_kstat_statically.txt"
private const val SUSFS_SETTINGS_CONFIG_FILE = "$SUSFS_PATH_CONFIG_DIR/settings.conf"
private const val SUSFS_CURRENT_CONFIG_DIR = "$SUSFS_PATH_CONFIG_DIR/current"
private const val SUSFS_GENERATIONS_DIR = "$SUSFS_PATH_CONFIG_DIR/generations"
private const val SUSFS_RUNTIME_STATUS_FILE = "$SUSFS_PATH_CONFIG_DIR/status.conf"
private const val SUSFS_RUNTIME_ISSUES_FILE = "$SUSFS_PATH_CONFIG_DIR/status.entries"
private const val SUSFS_PATH_SERVICE_FILE = "/data/adb/service.d/98-apkesu-susfs-paths.sh"
private const val SUSFS_PATH_FEATURE = "CONFIG_KSU_SUSFS_SUS_PATH"
private const val SUSFS_MOUNT_FEATURE = "CONFIG_KSU_SUSFS_SUS_MOUNT"
private const val SUSFS_KSTAT_FEATURE = "CONFIG_KSU_SUSFS_SUS_KSTAT"
private const val SUSFS_LOG_FEATURE = "CONFIG_KSU_SUSFS_ENABLE_LOG"
private const val SUSFS_OPEN_REDIRECT_FEATURE = "CONFIG_KSU_SUSFS_OPEN_REDIRECT"
private const val SUSFS_MAP_FEATURE = "CONFIG_KSU_SUSFS_SUS_MAP"
private const val SUSFS_UNAME_FEATURE = "CONFIG_KSU_SUSFS_SPOOF_UNAME"
private const val SUSFS_CMDLINE_FEATURE = "CONFIG_KSU_SUSFS_SPOOF_CMDLINE_OR_BOOTCONFIG"
private const val SUSFS_CONFIG_MAX_BYTES = 256 * 1024
private const val SUSFS_MAX_PATH_BYTES = 255
private const val SUSFS_PATH_APPLY_TIMEOUT_MILLIS = 20_000L
private const val GRAPHICS_RENDERER_DIR = "/data/adb/apkesu/graphics_renderer"
private const val GRAPHICS_RENDERER_MODE_FILE = "$GRAPHICS_RENDERER_DIR/mode"
private const val GRAPHICS_RENDERER_BACKUP_MARKER = "$GRAPHICS_RENDERER_DIR/backup_complete"
private const val GRAPHICS_RENDERER_ORIGINAL_RENDERER = "$GRAPHICS_RENDERER_DIR/original_renderer"
private const val GRAPHICS_RENDERER_ORIGINAL_DISABLE = "$GRAPHICS_RENDERER_DIR/original_disable_vulkan"
private const val GRAPHICS_RENDERER_RESTART_MARKER = "$GRAPHICS_RENDERER_DIR/restart_required"
private const val GRAPHICS_RENDERER_SERVICE = "/data/adb/service.d/99-apkesu-graphics-renderer.sh"
private const val GRAPHICS_RENDERER_SERVICE_ASSET = "graphics_renderer_service.sh"
private const val GRAPHICS_RENDERER_VERIFICATION_ATTEMPTS = 8
private const val GRAPHICS_RENDERER_VERIFICATION_DELAY_MILLIS = 250L

internal fun isManagerHiddenModuleId(moduleId: String): Boolean {
    return moduleId.equals(KPATCH_NEXT_MODULE_ID, ignoreCase = true)
}

private fun getKsuDaemonPath(): String {
    return ksuApp.applicationInfo.nativeLibraryDir + File.separator + "libksud.so"
}

data class FlashResult(
    val code: Int,
    val err: String,
    val showReboot: Boolean,
) {
    constructor(result: Shell.Result, showReboot: Boolean) : this(result.code, result.err.joinToString("\n"), showReboot)
    constructor(result: Shell.Result) : this(result, result.isSuccess)
}

data class DynamicManagerCliState(
    val supported: Boolean = false,
    val configured: Boolean = false,
    val active: Boolean = false,
    val certificateSize: Int = 0,
    val certificateSha256: String = "",
    val managerSignatureIndexes: Map<Int, Int> = emptyMap(),
    val error: String = "",
)

data class BuiltinMountStatus(
    val moduleId: String = HYBRID_MOUNT_MODULE_ID,
    val moduleName: String = "Hybrid Mount Lite",
    val modulePath: String = "/data/adb/ksu/builtin/hybrid_mount",
    val version: String = "",
    val versionCode: String = "",
    val installed: Boolean = false,
    val enabled: Boolean = false,
    val conflict: String? = null,
    val defaultMode: String = BUILTIN_MOUNT_MODE_OVERLAY,
    val variant: String = BUILTIN_MOUNT_VARIANT_LITE,
    val webUi: Boolean = false,
    val sourceUrl: String = "",
    val archiveSha256: String = "",
    val lkmCount: Int = 0,
    val supportedKmis: List<String> = emptyList(),
    val currentKmi: String = "",
    val compatibility: String = "unknown",
    val lkmPurpose: String = "",
    val apkeSuRootDriver: Boolean = false,
)

data class KPatchNextStatus(
    val moduleId: String = KPATCH_NEXT_MODULE_ID,
    val moduleName: String = "KPatch-Next",
    val modulePath: String = "/data/adb/modules/KPatch-Next",
    val version: String = "",
    val versionCode: String = "",
    val installed: Boolean = false,
    val enabled: Boolean = false,
    val pendingUpdate: Boolean = false,
    val pendingRemove: Boolean = false,
    val webUi: Boolean = false,
    val unresolved: Boolean = false,
    val dataDir: Boolean = false,
    val builtinAvailable: Boolean = false,
    val conflict: String? = null,
    val error: String = "",
)

data class KpmCaps(
    val backend: String = "none",
    val managementAvailable: Boolean = false,
    val supported: Boolean = false,
    val kernelSupported: Boolean = false,
    val loaderReady: Boolean = false,
    val probeError: Int = 0,
    val policyEnabled: Boolean = true,
    val lateLoad: Boolean = false,
    val abiVersion: Int = 0,
    val capabilities: Int = 0,
    val maxImageSize: Long = 0,
    val maxLoaded: Int = 0,
    val disabledReason: String = "",
    val error: String = "",
)

data class KpmEntry(
    val id: String,
    val name: String = "",
    val version: String = "",
    val license: String = "",
    val author: String = "",
    val description: String = "",
    val args: String = "",
    val enabled: Boolean = false,
    val loaded: Boolean = false,
    val runtimeKnown: Boolean = true,
    val quarantined: Boolean = false,
    val quarantineReason: String = "",
    val sourceName: String = "",
    val importedAt: String = "",
    val error: String = "",
)

data class KpmCommandResult(
    val success: Boolean,
    val output: String = "",
    val error: String = "",
)

data class KpmExcludedApp(
    val packageName: String,
    val uid: Int,
)

data class EpkesuHideStatus(
    val enabled: Boolean = false,
    val configured: Boolean = false,
    val applied: Boolean = false,
)

data class CpuSpoofStatus(
    val supported: Boolean = false,
    val configured: Boolean = false,
    val enabled: Boolean = false,
    val applied: Boolean = false,
    val current: String = "",
    val target: String = "",
    val original: String = "",
    val manufacturer: String = "",
    val platform: String = "",
    val error: String = "",
)

data class CpuSpoofCommandResult(
    val success: Boolean,
    val error: String = "",
)

internal fun mergeCpuSpoofStatus(
    previous: CpuSpoofStatus,
    refreshed: CpuSpoofStatus,
): CpuSpoofStatus {
    val hasPayload = refreshed.supported ||
        refreshed.configured ||
        refreshed.enabled ||
        refreshed.applied ||
        refreshed.current.isNotBlank() ||
        refreshed.target.isNotBlank() ||
        refreshed.original.isNotBlank() ||
        refreshed.manufacturer.isNotBlank() ||
        refreshed.platform.isNotBlank()
    return if (refreshed.error.isNotBlank() && !hasPayload) {
        previous.copy(error = refreshed.error)
    } else {
        refreshed
    }
}

data class HiddenPathConfigState(
    val targetPaths: List<String> = emptyList(),
    val appPackages: List<String> = emptyList(),
    val useAppScope: Boolean = true,
    val hideDirents: Boolean = true,
    val hideIsolated: Boolean = true,
    val autoLoadEnabled: Boolean = true,
    val autoLoadDelaySeconds: Int = 0,
    val autoLoadRemainingSeconds: Int = 0,
    val loaded: Boolean = false,
    val currentKmi: String = "",
    val phase: String = "unconfigured",
    val savedCount: Int = 0,
    val availableCount: Int = 0,
    val activeCount: Int = 0,
    val resolvedCount: Int = 0,
    val activeTargetPaths: String = "",
    val missingTargetPaths: List<String> = emptyList(),
    val unresolvedTargetCount: Int = 0,
    val unresolvedTargetPaths: List<String> = emptyList(),
    val requiresReload: Boolean = false,
    val requiresReboot: Boolean = false,
    val hasPendingCandidate: Boolean = false,
    val lastErrorCode: String = "",
    val lastErrorMessage: String = "",
    val lastLog: String = "",
    val resolvedAppUids: List<String> = emptyList(),
    val unresolvedAppPackages: List<String> = emptyList(),
) {
    val pendingTargetCount: Int
        get() = missingTargetPaths.size
            .coerceAtLeast((savedCount - availableCount).coerceAtLeast(0))

    val notEffectiveTargetCount: Int
        get() = (missingTargetPaths.size + unresolvedTargetCount)
            .coerceAtLeast((savedCount - activeCount).coerceAtLeast(0))

    val isPartial: Boolean
        get() = loaded && (phase == "partial" || unresolvedTargetCount > 0)
}

data class HiddenPathConfigReadResult(
    val config: HiddenPathConfigState? = null,
    val error: String = "",
    val errorCode: String = "",
)

internal fun HiddenPathConfigState.editableEquals(other: HiddenPathConfigState): Boolean {
    return targetPaths == other.targetPaths &&
        appPackages == other.appPackages &&
        useAppScope == other.useAppScope &&
        hideDirents == other.hideDirents &&
        hideIsolated == other.hideIsolated &&
        autoLoadEnabled == other.autoLoadEnabled &&
        autoLoadDelaySeconds == other.autoLoadDelaySeconds
}

data class ToolCommandResult(
    val success: Boolean = false,
    val errorCode: String = "",
    val errorMessage: String = "",
    val timedOut: Boolean = false,
)

data class HiddenPathVisibilityResult(
    val uid: Int = -1,
    val path: String = "",
    val status: String = "probe_failed",
    val visible: Boolean = false,
    val rootExists: Boolean = false,
    val moduleLoaded: Boolean = false,
    val resolvedCount: String = "",
    val error: String = "",
)

data class SusfsCapabilities(
    val version: String = "",
    val features: Set<String> = emptySet(),
    val featureProbeAvailable: Boolean = false,
    val supportsAddSusPath: Boolean = false,
    val supportsPathLoop: Boolean = false,
    val supportsTryUmount: Boolean = false,
    val supportsKstat: Boolean = false,
    val supportsOpenRedirect: Boolean = false,
    val supportsSusMap: Boolean = false,
    val supportsUnameSpoof: Boolean = false,
    val supportsCmdlineSpoof: Boolean = false,
    val supportsLogging: Boolean = false,
    val supportsAvcLogSpoofing: Boolean = false,
    val supportsHideSusMounts: Boolean = false,
)

data class SusfsOpenRedirectEntry(
    val originalPath: String,
    val redirectedPath: String,
    val uidScheme: String = "",
)

data class SusfsKstatEntry(
    val arguments: List<String>,
)

data class SusfsApplyIssue(
    val category: String,
    val target: String,
    val code: String,
)

data class SusfsRuntimeStatus(
    val generation: String = "",
    val state: String = "unknown",
    val configuredCount: Int = 0,
    val appliedCount: Int = 0,
    val skippedCount: Int = 0,
    val failedCount: Int = 0,
    val requiresReboot: Boolean = false,
    val startedAt: String = "",
    val finishedAt: String = "",
    val issues: List<SusfsApplyIssue> = emptyList(),
)

data class SusfsPathConfigState(
    val available: Boolean = false,
    val toolPath: String = "",
    val paths: List<String> = emptyList(),
    val loopPaths: List<String> = emptyList(),
    val susMaps: List<String> = emptyList(),
    val openRedirects: List<SusfsOpenRedirectEntry> = emptyList(),
    val kstatEntries: List<SusfsKstatEntry> = emptyList(),
    val enabled: Boolean = true,
    val logging: Boolean = false,
    val avcLogSpoofing: Boolean = false,
    val hideSusMntsForNonSuProcs: Boolean = false,
    val unameRelease: String = "",
    val unameVersion: String = "",
    val cmdlineOrBootconfig: String = "",
    val capabilities: SusfsCapabilities = SusfsCapabilities(),
    val runtimeStatus: SusfsRuntimeStatus = SusfsRuntimeStatus(),
    val error: String = "",
)

data class SusfsPathApplyResult(
    val success: Boolean = false,
    val saved: Boolean = false,
    val appliedCount: Int = 0,
    val skippedCount: Int = 0,
    val failedCount: Int = 0,
    val requiresReboot: Boolean = false,
    val generation: String = "",
    val issues: List<SusfsApplyIssue> = emptyList(),
    val error: String = "",
)

data class SusfsBackupImportResult(
    val config: SusfsPathConfigState? = null,
    val warnings: List<String> = emptyList(),
    val error: String = "",
)

data class RootDiagnosticInfo(
    val driverVersion: Int = 0,
    val kernelModuleLoaded: Boolean = false,
    val ksuRootShell: Boolean = false,
    val fallbackRootShell: Boolean = false,
    val managerRegistered: Boolean = false,
    val managerPackage: String = BuildConfig.APPLICATION_ID,
    val managerUid: Int = -1,
    val kernelUapi: Int = 0,
    val managerUapi: Int = 0,
    val packagedKsudVersion: String = "",
    val installedKsudVersion: String = "",
    val currentKmi: String = "",
    val currentSlot: String = "",
    val workMode: String = "unknown",
    val hiddenPathLkm: Boolean = false,
)

data class InstalledKsudStatus(
    val present: Boolean = false,
    val versionCode: Int? = null,
)

data class RescueImageState(
    val name: String = "",
    val label: String = "",
    val partition: String = "",
    val image: String = "",
    val required: Boolean = false,
    val custom: Boolean = false,
    val exists: Boolean = false,
    val size: Long = 0,
    val partitionSize: Long = 0,
    val sha256: String = "",
    val sha256Ok: Boolean = true,
    val sizeOk: Boolean = true,
    val otherSlot: Boolean = false,
    val restore: Boolean = true,
    val dangerous: Boolean = false,
    val verificationState: String = "unknown",
)

data class RescueDisabledModule(
    val id: String = "",
    val name: String = "",
    val version: String = "",
    val installed: Boolean = false,
    val disabled: Boolean = false,
)

data class RescueRestoreEntry(
    val name: String = "",
    val label: String = "",
    val imagePath: String = "",
    val devicePath: String = "",
    val expectedSha256: String = "",
    val expectedSize: Long = 0,
    val status: String = "",
)

data class RescueRestoreTransaction(
    val id: String = "",
    val reason: String = "",
    val automatic: Boolean = false,
    val description: String = "",
    val activateSlot: String = "",
    val phase: String = "",
    val errorCode: String = "",
    val errorMessage: String = "",
    val startedAt: String = "",
    val updatedAt: String = "",
    val entries: List<RescueRestoreEntry> = emptyList(),
)

data class RescueConfigState(
    val includeDtbo: Boolean = false,
    val includeVbmeta: Boolean = false,
    val backupOtherSlot: Boolean = false,
    val allowDangerousAutoRestore: Boolean = false,
    val customPartitions: Map<String, String> = emptyMap(),
)

data class RescueStatus(
    val available: Boolean = false,
    val phase: String = "unavailable",
    val statusErrorCode: String = "",
    val statusError: String = "",
    val enabled: Boolean = false,
    val config: RescueConfigState = RescueConfigState(),
    val images: List<RescueImageState> = emptyList(),
    val bootCount: Int = 0,
    val autoRestoreAttempts: Int = 0,
    val pendingBoot: Boolean = false,
    val pendingImageBoot: Boolean = false,
    val restorePendingBoot: Boolean = false,
    val restoreBootState: String = "none",
    val currentSlot: String = "",
    val bootMode: String = "",
    val device: String = "",
    val deviceFingerprint: String = "",
    val manifestCreatedAt: String = "",
    val manifestSlot: String = "",
    val manifestDevice: String = "",
    val manifestFingerprint: String = "",
    val manifestTotalSize: Long = 0,
    val lastRestoreDone: Boolean = false,
    val skipModulesOnce: Boolean = false,
    val skipModulesThisBoot: Boolean = false,
    val ready: Boolean = false,
    val readyReason: String = "",
    val verified: Boolean = false,
    val environmentChecked: Boolean = false,
    val configChangedProtectionDisabled: Boolean = false,
    val restoreInterrupted: Boolean = false,
    val restoreTransactionError: String = "",
    val restoreTransaction: RescueRestoreTransaction? = null,
    val rescueDisabledModules: List<RescueDisabledModule> = emptyList(),
    val log: String = "",
) {
    val requiredReady: Boolean
        get() = ready
}

data class RescueTestReport(
    val ok: Boolean = false,
    val errorCode: String = "",
    val reason: String = "",
    val text: String = "",
    val backupReady: Boolean = false,
    val backupReason: String = "",
)

fun RescueConfigState.toConfigJson(): String {
    val custom = JSONObject()
    customPartitions.forEach { (key, value) ->
        if (value.isNotBlank()) {
            custom.put(key, value)
        }
    }
    return JSONObject()
        .put("includeDtbo", includeDtbo)
        .put("includeVbmeta", includeVbmeta)
        .put("backupOtherSlot", backupOtherSlot)
        .put("allowDangerousAutoRestore", allowDangerousAutoRestore)
        .put("customPartitions", custom)
        .toString()
}

fun HiddenPathConfigState.toConfigJson(): String {
    require(autoLoadDelaySeconds in 0..HIDDEN_PATH_MAX_AUTO_LOAD_DELAY_SECONDS) {
        "Pathmask auto-load delay is out of range"
    }
    return JSONObject()
        .put("schemaVersion", 3)
        .put("targetPaths", JSONArray(targetPaths.cleanConfigList()))
        .put("appPackages", JSONArray(appPackages.cleanConfigList()))
        .put("useAppScope", useAppScope)
        .put("hideDirents", hideDirents)
        .put("hideIsolated", hideIsolated)
        .put("autoLoadEnabled", autoLoadEnabled)
        .put("autoLoadDelaySeconds", autoLoadDelaySeconds)
        .toString(2)
}

fun parseHiddenPathConfigJson(content: String, current: HiddenPathConfigState = HiddenPathConfigState()): HiddenPathConfigState {
    val obj = JSONObject(content)
    return current.copy(
        targetPaths = obj.optJSONArray("targetPaths").toStringList().cleanConfigList(),
        appPackages = obj.optJSONArray("appPackages").toStringList().cleanConfigList(),
        useAppScope = obj.optBoolean("useAppScope", current.useAppScope),
        hideDirents = obj.optBoolean("hideDirents", current.hideDirents),
        hideIsolated = obj.optBoolean("hideIsolated", current.hideIsolated),
        autoLoadEnabled = obj.optBoolean("autoLoadEnabled", current.autoLoadEnabled),
        // Older exports had no delay field and therefore always loaded immediately.
        autoLoadDelaySeconds = obj.readAutoLoadDelaySeconds(0),
    )
}

private fun JSONObject.readAutoLoadDelaySeconds(defaultValue: Int): Int {
    if (!has("autoLoadDelaySeconds")) return defaultValue
    val numeric = (opt("autoLoadDelaySeconds") as? Number)?.toDouble()
    require(numeric != null && numeric.isFinite() && numeric % 1.0 == 0.0) {
        "Pathmask auto-load delay must be an integer"
    }
    val seconds = numeric.toInt()
    require(seconds in 0..HIDDEN_PATH_MAX_AUTO_LOAD_DELAY_SECONDS) {
        "Pathmask auto-load delay must be between 0 and $HIDDEN_PATH_MAX_AUTO_LOAD_DELAY_SECONDS seconds"
    }
    return seconds
}

object KsuCli {
    private val shellLock = Any()
    private var shell: Shell? = null
    private var globalMntShell: Shell? = null

    val SHELL: Shell
        get() = getCachedShell(false)

    val GLOBAL_MNT_SHELL: Shell
        get() = getCachedShell(true)

    private fun getCachedShell(globalMnt: Boolean): Shell = synchronized(shellLock) {
        val current = if (globalMnt) globalMntShell else shell
        if (current != null && current.isUsableRoot()) {
            return@synchronized current
        }

        current?.closeQuietly()
        val newShell = createRootShell(globalMnt)
        if (globalMnt) {
            globalMntShell = newShell
        } else {
            shell = newShell
        }
        newShell
    }

    fun reset(globalMnt: Boolean = false) = synchronized(shellLock) {
        val current = if (globalMnt) globalMntShell else shell
        current?.closeQuietly()
        if (globalMnt) {
            globalMntShell = null
        } else {
            shell = null
        }
    }
}

private fun Shell.isUsableRoot(): Boolean = runCatching { isRoot }.getOrDefault(false)

private fun Shell.closeQuietly() {
    runCatching { close() }
}

fun getRootShell(globalMnt: Boolean = false): Shell {
    return if (globalMnt) KsuCli.GLOBAL_MNT_SHELL else {
        KsuCli.SHELL
    }
}

inline fun <T> withNewRootShell(
    globalMnt: Boolean = false,
    block: Shell.() -> T
): T {
    return createRootShell(globalMnt).use(block)
}

fun Uri.getFileName(context: Context): String? {
    return runCatching {
        context.contentResolver.query(
            this,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) {
                return@use null
            }
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        }
    }.getOrNull()
}

fun createRootShell(globalMnt: Boolean = false): Shell {
    Shell.enableVerboseLogging = BuildConfig.DEBUG
    val builder = Shell.Builder.create()
    fun buildRootShellOrThrow(label: String, vararg commands: String): Shell {
        val shell = builder.build(*commands)
        if (shell.isUsableRoot()) {
            return shell
        }
        shell.closeQuietly()
        error("$label shell is not root")
    }

    val tryKsuShell = {
        if (globalMnt) {
            buildRootShellOrThrow("ksu", getKsuDaemonPath(), "debug", "su", "-g")
        } else {
            buildRootShellOrThrow("ksu", getKsuDaemonPath(), "debug", "su")
        }
    }
    val trySuShell = {
        if (globalMnt) {
            buildRootShellOrThrow("su", "su", "-mm")
        } else {
            buildRootShellOrThrow("su", "su")
        }
    }

    return try {
        tryKsuShell()
    } catch (ksuError: Throwable) {
        Log.w(TAG, "ksu failed: ", ksuError)
        try {
            trySuShell()
        } catch (suError: Throwable) {
            Log.e(TAG, "su failed: ", suError)
            builder.build("sh")
        }
    }
}

fun execKsud(
    args: String,
    newShell: Boolean = false,
    globalMnt: Boolean = false,
): Boolean {
    if (shouldSkipUnsafeKsudCommand()) {
        Log.w(TAG, "skip ksud command without safe root shell: $args")
        return false
    }

    return if (newShell) {
        withNewRootShell(globalMnt = globalMnt) {
            ShellUtils.fastCmdResult(this, "${shellQuote(getKsuDaemonPath())} $args")
        }
    } else {
        ShellUtils.fastCmdResult(
            getRootShell(globalMnt),
            "${shellQuote(getKsuDaemonPath())} $args",
        )
    }
}

/** Starts the persistent native web manager when the bundled ksud supports it. */
fun startNativeWebManager(): Boolean = execKsud("web-manager start", newShell = true)

/** Enables or disables the persistent native web manager. */
fun setNativeWebManagerEnabled(enabled: Boolean): Boolean =
    execKsud("web-manager ${if (enabled) "enable" else "disable"}", newShell = true)

/** Returns the authenticated native URL, or null when this ksud is too old/unavailable. */
fun getNativeWebManagerUrl(): String? = runCatching {
    if (shouldSkipUnsafeKsudCommand()) return@runCatching null
    val output = ShellUtils.fastCmd(
        getRootShell(),
        "${shellQuote(getKsuDaemonPath())} web-manager url",
    ).trim()
    output.takeIf { it.startsWith("http://127.0.0.1:") && it.contains("/w/") }
}.getOrNull()

suspend fun getBuiltinMountStatus(): BuiltinMountStatus = withContext(Dispatchers.IO) {
    if (shouldSkipUnsafeKsudCommand()) {
        return@withContext BuiltinMountStatus()
    }

    runCatching {
        val stdout = ArrayList<String>()
        val stderr = ArrayList<String>()
        val result = withTimeoutOrNull(SHELL_JOB_TIMEOUT_MILLIS) {
            getRootShell().newJob()
                .add("${getKsuDaemonPath()} builtin-mount status")
                .to(stdout, stderr)
                .exec()
        }

        if (result == null) {
            Log.w(TAG, "builtin-mount status timed out")
            KsuCli.reset()
            return@runCatching BuiltinMountStatus()
        }

        if (!result.isSuccess) {
            Log.w(TAG, "builtin-mount status failed: ${stderr.joinToString("\n")}")
            return@runCatching BuiltinMountStatus()
        }

        val obj = JSONObject(stdout.joinToString("\n"))
        val mode = obj.optString("defaultMode", BUILTIN_MOUNT_MODE_OVERLAY)
            .takeIf { it == BUILTIN_MOUNT_MODE_OVERLAY || it == BUILTIN_MOUNT_MODE_MAGIC }
            ?: BUILTIN_MOUNT_MODE_OVERLAY
        val variant = obj.optString("variant", BUILTIN_MOUNT_VARIANT_LITE)
            .takeIf { it == BUILTIN_MOUNT_VARIANT_LITE || it == BUILTIN_MOUNT_VARIANT_FULL }
            ?: BUILTIN_MOUNT_VARIANT_LITE
        BuiltinMountStatus(
            moduleId = obj.optString("moduleId", HYBRID_MOUNT_MODULE_ID),
            moduleName = obj.optString("moduleName", "Hybrid Mount Lite"),
            modulePath = obj.optString("modulePath", "/data/adb/ksu/builtin/hybrid_mount"),
            version = obj.optString("version", ""),
            versionCode = obj.optString("versionCode", ""),
            installed = obj.optBoolean("installed", false),
            enabled = obj.optBoolean("enabled", false),
            conflict = obj.optString("conflict").takeIf { it.isNotBlank() && it != "null" },
            defaultMode = mode,
            variant = variant,
            webUi = obj.optBoolean("webui", false),
            sourceUrl = obj.optString("sourceUrl", ""),
            archiveSha256 = obj.optString("archiveSha256", ""),
            lkmCount = obj.optInt("lkmCount", 0),
            supportedKmis = obj.optJSONArray("supportedKmis").toStringList(),
            currentKmi = obj.optString("currentKmi", ""),
            compatibility = obj.optString("compatibility", "unknown"),
            lkmPurpose = obj.optString("lkmPurpose", ""),
            apkeSuRootDriver = obj.optBoolean("apkesuRootDriver", false),
        )
    }.getOrElse {
        Log.w(TAG, "builtin-mount status unavailable", it)
        KsuCli.reset()
        BuiltinMountStatus()
    }
}

fun setBuiltinMountEnabled(enabled: Boolean): Boolean {
    val command = if (enabled) "enable" else "disable"
    return execKsud("builtin-mount $command", true)
}

fun setBuiltinMountDefaultMode(mode: String): Boolean {
    val normalized = if (mode == BUILTIN_MOUNT_MODE_MAGIC) {
        BUILTIN_MOUNT_MODE_MAGIC
    } else {
        BUILTIN_MOUNT_MODE_OVERLAY
    }
    return execKsud("builtin-mount set-default-mode $normalized", true)
}

fun setBuiltinMountVariant(variant: String): Boolean {
    val normalized = if (variant == BUILTIN_MOUNT_VARIANT_FULL) {
        BUILTIN_MOUNT_VARIANT_FULL
    } else {
        BUILTIN_MOUNT_VARIANT_LITE
    }
    return execKsud("builtin-mount set-variant $normalized", true)
}

suspend fun getKPatchNextStatus(): KPatchNextStatus = withContext(Dispatchers.IO) {
    if (shouldSkipUnsafeKsudCommand()) {
        return@withContext KPatchNextStatus(error = "Root shell is unavailable")
    }

    runCatching {
        val stdout = ArrayList<String>()
        val stderr = ArrayList<String>()
        val result = withTimeoutOrNull(SHELL_JOB_TIMEOUT_MILLIS) {
            getRootShell().newJob()
                .add("${getKsuDaemonPath()} kpatch-next status")
                .to(stdout, stderr)
                .exec()
        }

        if (result == null) {
            Log.w(TAG, "kpatch-next status timed out")
            KsuCli.reset()
            return@runCatching KPatchNextStatus(error = "KPatch-Next status timed out")
        }

        if (!result.isSuccess) {
            val error = stderr.joinToString("\n").trim().ifBlank {
                "KPatch-Next status command failed"
            }
            Log.w(TAG, "kpatch-next status failed: $error")
            return@runCatching KPatchNextStatus(error = error)
        }

        val obj = JSONObject(stdout.joinToString("\n"))
        KPatchNextStatus(
            moduleId = obj.optString("moduleId", KPATCH_NEXT_MODULE_ID),
            moduleName = obj.optString("moduleName", "KPatch-Next"),
            modulePath = obj.optString("modulePath", "/data/adb/modules/KPatch-Next"),
            version = obj.optString("version", ""),
            versionCode = obj.optString("versionCode", ""),
            installed = obj.optBoolean("installed", false),
            enabled = obj.optBoolean("enabled", false),
            pendingUpdate = obj.optBoolean("pendingUpdate", false),
            pendingRemove = obj.optBoolean("pendingRemove", false),
            webUi = obj.optBoolean("webui", false),
            unresolved = obj.optBoolean("unresolved", false),
            dataDir = obj.optBoolean("dataDir", false),
            builtinAvailable = obj.optBoolean("builtinAvailable", false),
            conflict = obj.optString("conflict").takeIf { it.isNotBlank() && it != "null" },
        )
    }.getOrElse {
        Log.w(TAG, "kpatch-next status unavailable", it)
        KsuCli.reset()
        KPatchNextStatus(
            error = it.message.orEmpty().ifBlank { "KPatch-Next status is unavailable" },
        )
    }
}

fun setKPatchNextEnabled(enabled: Boolean): Boolean {
    val command = if (enabled) "enable" else "disable"
    return execKsud("kpatch-next $command", true)
}

private data class KsudCommandOutput(
    val success: Boolean,
    val code: Int,
    val stdout: String,
    val stderr: String,
)

private suspend fun runKsudCommandWithOutput(
    args: String,
    timeoutMillis: Long = SHELL_JOB_TIMEOUT_MILLIS,
): KsudCommandOutput = withContext(Dispatchers.IO) {
    if (shouldSkipUnsafeKsudCommand()) {
        return@withContext KsudCommandOutput(
            success = false,
            code = -1,
            stdout = "",
            stderr = "Root shell is unavailable",
        )
    }

    val stdout = ArrayList<String>()
    val stderr = ArrayList<String>()
    val result = withTimeoutOrNull(timeoutMillis) {
        getRootShell().newJob()
            .add("${shellQuote(getKsuDaemonPath())} $args")
            .to(stdout, stderr)
            .exec()
    }
    if (result == null) {
        KsuCli.reset()
        return@withContext KsudCommandOutput(
            success = false,
            code = -2,
            stdout = stdout.joinToString("\n"),
            stderr = "Command timed out after ${timeoutMillis}ms",
        )
    }
    KsudCommandOutput(
        success = result.isSuccess,
        code = result.code,
        stdout = stdout.joinToString("\n"),
        stderr = stderr.joinToString("\n"),
    )
}

internal fun parseDynamicManagerStatusJson(content: String): DynamicManagerCliState {
    val json = JSONObject(content)
    require(json.optInt("schemaVersion", -1) == DYNAMIC_MANAGER_STATUS_SCHEMA_VERSION) {
        "Unsupported Dynamic Manager status schema"
    }
    val supported = json.optBoolean("supported", false)
    val configured = json.optBoolean("configured", false)
    val active = json.optBoolean("active", false)
    val certificateSize = json.optInt("certificateSize", 0)
    val certificateSha256 = json.optString("certificateSha256", "")
    val managerSignatureIndexes = buildMap {
        val managers = json.optJSONArray("managers")
        if (managers != null) {
            for (index in 0 until managers.length()) {
                val manager = managers.optJSONObject(index)
                    ?: throw IllegalArgumentException("Dynamic Manager registry entry is invalid")
                val appId = manager.optInt("appId", -1)
                val signatureIndex = manager.optInt("signatureIndex", -1)
                require(appId in FIRST_APPLICATION_APPID..LAST_APPLICATION_APPID) {
                    "Dynamic Manager registry App ID is invalid"
                }
                require(signatureIndex in 0..255) {
                    "Dynamic Manager signature index is invalid"
                }
                require(putIfAbsent(appId, signatureIndex) == null) {
                    "Dynamic Manager registry contains a duplicate App ID"
                }
            }
        }
    }
    require(!active || configured) { "Active Dynamic Manager is not configured" }
    require(!active || managerSignatureIndexes.containsValue(255)) {
        "Active Dynamic Manager is missing from the manager registry"
    }
    if (configured) {
        require(certificateSize in DYNAMIC_MANAGER_MIN_CERTIFICATE_SIZE..DYNAMIC_MANAGER_MAX_CERTIFICATE_SIZE) {
            "Dynamic Manager certificate size is invalid"
        }
        require(DYNAMIC_MANAGER_CERTIFICATE_SHA256.matches(certificateSha256)) {
            "Dynamic Manager certificate SHA-256 is invalid"
        }
    } else {
        require(certificateSize == 0 && certificateSha256.isEmpty()) {
            "Unconfigured Dynamic Manager contains certificate data"
        }
    }
    return DynamicManagerCliState(
        supported = supported,
        configured = configured,
        active = active,
        certificateSize = certificateSize,
        certificateSha256 = certificateSha256,
        managerSignatureIndexes = managerSignatureIndexes,
        error = json.optString("error", "").takeUnless { it == "null" }.orEmpty(),
    )
}

suspend fun getDynamicManagerStatus(): Result<DynamicManagerCliState> {
    val result = runKsudCommandWithOutput("kernel dynamic-manager status")
    if (!result.success) {
        return Result.failure(
            IllegalStateException(
                result.stderr.trim().ifBlank { "ksud exited with code ${result.code}" },
            ),
        )
    }
    return runCatching { parseDynamicManagerStatusJson(result.stdout) }
}

suspend fun setDynamicManagerApk(
    apkPath: String,
): Result<Unit> {
    val result = runKsudCommandWithOutput(
        "kernel dynamic-manager set-apk ${shellQuote(apkPath)}",
        timeoutMillis = SHELL_JOB_TIMEOUT_MILLIS * 2,
    )
    return if (result.success) {
        Result.success(Unit)
    } else {
        Result.failure(
            IllegalStateException(
                result.stderr.trim().ifBlank { "ksud exited with code ${result.code}" },
            ),
        )
    }
}

suspend fun setDynamicManagerCertificate(certificateSize: Int, certificateSha256: String): Result<Unit> {
    require(certificateSize in DYNAMIC_MANAGER_MIN_CERTIFICATE_SIZE..DYNAMIC_MANAGER_MAX_CERTIFICATE_SIZE)
    require(DYNAMIC_MANAGER_CERTIFICATE_SHA256.matches(certificateSha256))
    val result = runKsudCommandWithOutput(
        "kernel dynamic-manager set $certificateSize ${shellQuote(certificateSha256)}",
        timeoutMillis = SHELL_JOB_TIMEOUT_MILLIS * 2,
    )
    return if (result.success) {
        Result.success(Unit)
    } else {
        Result.failure(
            IllegalStateException(
                result.stderr.trim().ifBlank { "ksud exited with code ${result.code}" },
            ),
        )
    }
}

suspend fun clearDynamicManager(): Result<Unit> {
    val result = runKsudCommandWithOutput("kernel dynamic-manager clear")
    return if (result.success) {
        Result.success(Unit)
    } else {
        Result.failure(
            IllegalStateException(
                result.stderr.trim().ifBlank { "ksud exited with code ${result.code}" },
            ),
        )
    }
}

private fun KsudCommandOutput.toKpmResult(): KpmCommandResult {
    return KpmCommandResult(
        success = success,
        output = stdout.trim(),
        error = if (success) "" else stderr.trim().ifBlank { "ksud exited with code $code" },
    )
}

suspend fun getKpmCaps(): KpmCaps {
    val result = runKsudCommandWithOutput("kpm caps").toKpmResult()
    if (!result.success) {
        return KpmCaps(error = result.error)
    }
    return runCatching {
        val obj = JSONObject(result.output)
        val capabilities = obj.optInt("capabilities", 0)
        KpmCaps(
            backend = obj.optString("backend", "none").lowercase(),
            managementAvailable = obj.optBoolean(
                "managementAvailable",
                obj.optBoolean("kernelSupported", capabilities != 0),
            ),
            supported = obj.optBoolean("supported", capabilities != 0) && capabilities != 0,
            kernelSupported = obj.optBoolean("kernelSupported", capabilities != 0),
            loaderReady = obj.optBoolean(
                "loaderReady",
                obj.optBoolean("kernelSupported", capabilities != 0),
            ),
            probeError = obj.optInt("probeError", 0),
            policyEnabled = obj.optBoolean("policyEnabled", true),
            lateLoad = obj.optBoolean("lateLoad", false),
            abiVersion = obj.optInt("abiVersion", 0),
            capabilities = capabilities,
            maxImageSize = obj.optLong("maxImageSize", 0L),
            maxLoaded = obj.optInt("maxLoaded", 0),
            disabledReason = obj.optString("disabledReason", ""),
        )
    }.getOrElse { error ->
        KpmCaps(error = "Invalid KPM capability response: ${error.message.orEmpty()}")
    }
}

/** KPM 是否在内核中可用：任一后端能力/加载器就绪即视为可用。 */
internal fun KpmCaps.isAvailable(): Boolean = kernelSupported ||
    supported ||
    loaderReady ||
    managementAvailable ||
    capabilities != 0

/**
 * `ksud kpm list` 输出里 `"loaded": true` 的条目数；输出不可解析时返回 null。
 * KPM 运行时状态未知时 ksud 会把 loaded 写成 null，此时不应计入。
 */
internal fun countLiveKpmModules(listJson: String): Int? = runCatching {
    val array = JSONArray(listJson)
    (0 until array.length()).count { index ->
        array.optJSONObject(index)?.optBoolean("loaded", false) == true
    }
}.getOrNull()

/** 设备信息卡片用的 KPM 一行摘要；没有 KPM 支持时返回空串，调用方隐藏该行。 */
internal fun buildKpmSummary(caps: KpmCaps, loadedCount: Int?): String {
    if (!caps.isAvailable() && loadedCount == null) return ""
    val parts = ArrayList<String>(4)
    parts += when {
        caps.supported -> "已启用"
        caps.loaderReady -> "已就绪"
        else -> "内核支持"
    }
    loadedCount?.let { parts += "已加载 $it 个" }
    if (caps.maxLoaded > 0) parts += "上限 ${caps.maxLoaded}"
    if (caps.abiVersion > 0) parts += "ABI v${caps.abiVersion}"
    if (!caps.policyEnabled) parts += "策略已关闭"
    if (caps.lateLoad) parts += "晚加载模式"
    return parts.joinToString(" · ")
}

/**
 * 设备信息卡片用的 KPM 摘要；内核不支持或 ksud 不可用时返回空串。
 * 摘要必须能自证：加载数量来自 `ksud kpm list`，读不到就只报能力。
 */
suspend fun probeKpmSummary(): String {
    val caps = runCatching { getKpmCaps() }.getOrNull() ?: return ""
    if (!caps.isAvailable()) return ""
    val loadedCount = runCatching { getKpmList() }
        .getOrNull()
        ?.takeIf { it.success }
        ?.output
        ?.let(::countLiveKpmModules)
    return buildKpmSummary(caps, loadedCount)
}

suspend fun setKpmPolicy(enabled: Boolean): KpmCommandResult {
    val action = if (enabled) "enable" else "disable"
    return runKsudCommandWithOutput("kpm policy $action", timeoutMillis = 30_000L)
        .toKpmResult()
}

suspend fun getKpmList(): KpmCommandResult {
    return runKsudCommandWithOutput("kpm list", timeoutMillis = SHELL_JOB_TIMEOUT_MILLIS)
        .toKpmResult()
}

suspend fun importKpm(
    source: File,
    args: String,
    force: Boolean,
    enable: Boolean,
): KpmCommandResult {
    val options = buildString {
        append("kpm import ")
        append(shellQuote(source.absolutePath))
        append(" --trusted --args ")
        append(shellQuote(args))
        if (force) append(" --force")
        if (enable) append(" --enable")
    }
    return runKsudCommandWithOutput(options, timeoutMillis = 30_000L).toKpmResult()
}

suspend fun setKpmEnabled(id: String, enabled: Boolean): KpmCommandResult {
    val action = if (enabled) "enable" else "disable"
    return runKsudCommandWithOutput("kpm $action ${shellQuote(id)}", timeoutMillis = 30_000L)
        .toKpmResult()
}

suspend fun loadKpm(id: String): KpmCommandResult {
    return runKsudCommandWithOutput("kpm load ${shellQuote(id)}", timeoutMillis = 30_000L)
        .toKpmResult()
}

suspend fun unloadKpm(id: String): KpmCommandResult {
    return runKsudCommandWithOutput("kpm unload ${shellQuote(id)}", timeoutMillis = 30_000L)
        .toKpmResult()
}

suspend fun removeKpm(id: String): KpmCommandResult {
    return runKsudCommandWithOutput("kpm remove ${shellQuote(id)}", timeoutMillis = 30_000L)
        .toKpmResult()
}

suspend fun controlKpm(id: String, args: String): KpmCommandResult {
    return runKsudCommandWithOutput(
        "kpm control ${shellQuote(id)} --args ${shellQuote(args)}",
        timeoutMillis = 30_000L,
    ).toKpmResult()
}

suspend fun getKpmExcludedApps(): List<KpmExcludedApp> {
    val result = runKsudCommandWithOutput("kpm exclude-list").toKpmResult()
    if (!result.success) error(result.error)
    val array = JSONArray(result.output)
    return buildList {
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val packageName = obj.optString("package").trim()
            val uid = obj.optInt("uid", -1)
            if (packageName.isNotBlank() && uid > 0) {
                add(KpmExcludedApp(packageName, uid))
            }
        }
    }
}

suspend fun setKpmAppExcluded(
    packageName: String,
    uid: Int,
    excluded: Boolean,
): KpmCommandResult {
    return runKsudCommandWithOutput(
        "kpm exclude ${shellQuote(packageName)} $uid --enabled $excluded",
        timeoutMillis = 30_000L,
    ).toKpmResult()
}

fun parseKpmEntries(content: String): List<KpmEntry> {
    val array = JSONArray(content)
    return buildList {
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val id = obj.optString("id").trim()
            if (id.isBlank()) continue
            add(
                KpmEntry(
                    id = id,
                    name = obj.optString("name", id),
                    version = obj.optString("version", ""),
                    license = obj.optString("license", ""),
                    author = obj.optString("author", ""),
                    description = obj.optString("description", ""),
                    args = obj.optString("args", ""),
                    enabled = obj.optBoolean("enabled", false),
                    loaded = obj.optBoolean("loaded", false),
                    runtimeKnown = obj.optBoolean(
                        "runtimeKnown",
                        obj.has("loaded") && !obj.isNull("loaded"),
                    ),
                    quarantined = obj.optBoolean("quarantined", false),
                    quarantineReason = obj.optString("quarantineReason", ""),
                    sourceName = obj.optString("sourceName", ""),
                    importedAt = obj.optString("importedAt", ""),
                    error = obj.optString("error", ""),
                ),
            )
        }
    }
}

suspend fun getEpkesuHideStatus(): EpkesuHideStatus = withContext(Dispatchers.IO) {
    if (shouldSkipUnsafeKsudCommand()) {
        return@withContext EpkesuHideStatus()
    }

    val stdout = ArrayList<String>()
    val stderr = ArrayList<String>()
    val result = withTimeoutOrNull(SHELL_JOB_TIMEOUT_MILLIS) {
        getRootShell().newJob()
            .add("${getKsuDaemonPath()} epkesu-hide status")
            .to(stdout, stderr)
            .exec()
    }

    if (result == null) {
        Log.w(TAG, "epkesu-hide status timed out")
        KsuCli.reset()
        return@withContext EpkesuHideStatus()
    }

    if (!result.isSuccess) {
        Log.w(TAG, "epkesu-hide status failed: ${stderr.joinToString("\n")}")
        return@withContext EpkesuHideStatus()
    }

    runCatching {
        val obj = JSONObject(stdout.joinToString("\n"))
        EpkesuHideStatus(
            enabled = obj.optBoolean("enabled", false),
            configured = obj.optBoolean("configured", obj.optBoolean("enabled", false)),
            applied = obj.optBoolean("applied", obj.optBoolean("enabled", false)),
        )
    }.getOrElse {
        Log.w(TAG, "parse epkesu-hide status failed: ${stdout.joinToString("\n")}", it)
        EpkesuHideStatus()
    }
}

fun setEpkesuHideEnabled(enabled: Boolean): Boolean {
    val command = if (enabled) "enable" else "disable"
    return execKsud("epkesu-hide $command", true)
}

fun isCpuSpoofModelValid(model: String): Boolean {
    if (model.any { it.isISOControl() }) return false
    val value = model.trim()
    return value.isNotEmpty() &&
        !value.startsWith('-') &&
        value.toByteArray(Charsets.UTF_8).size <= CPU_SPOOF_PROPERTY_VALUE_LIMIT
}

suspend fun getCpuSpoofStatus(): CpuSpoofStatus = withContext(Dispatchers.IO) {
    if (shouldSkipUnsafeKsudCommand()) {
        return@withContext CpuSpoofStatus(error = "root_unavailable")
    }

    val stdout = ArrayList<String>()
    val stderr = ArrayList<String>()
    val result = withTimeoutOrNull(SHELL_JOB_TIMEOUT_MILLIS) {
        getRootShell().newJob()
            .add("${shellQuote(getKsuDaemonPath())} cpu-spoof status")
            .to(stdout, stderr)
            .exec()
    }

    if (result == null) {
        Log.w(TAG, "cpu-spoof status timed out")
        KsuCli.reset()
        return@withContext CpuSpoofStatus(error = "timeout")
    }

    if (!result.isSuccess) {
        val error = stderr.joinToString("\n").trim().ifBlank { "status_failed" }
        Log.w(TAG, "cpu-spoof status failed: $error")
        return@withContext CpuSpoofStatus(error = error)
    }

    runCatching {
        val obj = JSONObject(stdout.joinToString("\n"))
        CpuSpoofStatus(
            supported = obj.optBoolean("supported", false),
            configured = obj.optBoolean("configured", false),
            enabled = obj.optBoolean("enabled", false),
            applied = obj.optBoolean("applied", false),
            current = obj.optString("current", ""),
            target = obj.optString("target", ""),
            original = obj.optString("original", ""),
            manufacturer = obj.optString("manufacturer", ""),
            platform = obj.optString("platform", ""),
            error = obj.optString("error", ""),
        )
    }.getOrElse {
        Log.w(TAG, "parse cpu-spoof status failed: ${stdout.joinToString("\n")}", it)
        CpuSpoofStatus(error = "parse_failed")
    }
}

suspend fun saveCpuSpoofTarget(model: String): CpuSpoofCommandResult {
    if (!isCpuSpoofModelValid(model)) {
        return CpuSpoofCommandResult(false, "invalid_cpu_model")
    }
    return runCpuSpoofCommand("configure --model ${shellQuote(model.trim())}")
}

suspend fun setCpuSpoofEnabled(enabled: Boolean): CpuSpoofCommandResult {
    return runCpuSpoofCommand(if (enabled) "enable" else "disable")
}

suspend fun restoreDefaultCpuSpoof(): CpuSpoofCommandResult {
    return runCpuSpoofCommand("restore-default")
}

suspend fun getGraphicsRendererStatus(): GraphicsRendererStatus = withContext(Dispatchers.IO) {
    if (!rootAvailable()) {
        return@withContext GraphicsRendererStatus(error = "root_unavailable")
    }

    val stdout = ArrayList<String>()
    val stderr = ArrayList<String>()
    val command = """
        renderer="${'$'}(getprop debug.hwui.renderer 2>/dev/null)"
        disable_vulkan="${'$'}(getprop debug.hwui.disable_vulkan 2>/dev/null)"
        egl_driver="${'$'}(getprop ro.hardware.egl 2>/dev/null)"
        hardware_vulkan="${'$'}(getprop ro.hardware.vulkan 2>/dev/null)"
        vulkan_feature="${'$'}(pm list features 2>/dev/null | grep 'android.hardware.vulkan.level' | head -n 1)"
        vulkan_driver=""
        for candidate in /vendor/lib64/hw/vulkan.*.so /vendor/lib/hw/vulkan.*.so /system/vendor/lib64/hw/vulkan.*.so /system/vendor/lib/hw/vulkan.*.so; do
          if [ -e "${'$'}candidate" ]; then vulkan_driver="${'$'}candidate"; break; fi
        done
        configured_mode="${'$'}(cat $GRAPHICS_RENDERER_MODE_FILE 2>/dev/null)"
        original_renderer="${'$'}(cat $GRAPHICS_RENDERER_ORIGINAL_RENDERER 2>/dev/null)"
        original_disable_vulkan="${'$'}(cat $GRAPHICS_RENDERER_ORIGINAL_DISABLE 2>/dev/null)"
        [ -f $GRAPHICS_RENDERER_BACKUP_MARKER ] && backup_available=1 || backup_available=0
        [ -x $GRAPHICS_RENDERER_SERVICE ] && persistent=1 || persistent=0
        [ -f $GRAPHICS_RENDERER_RESTART_MARKER ] && restart_required=1 || restart_required=0
        printf 'renderer=%s\n' "${'$'}renderer"
        printf 'disable_vulkan=%s\n' "${'$'}disable_vulkan"
        printf 'egl_driver=%s\n' "${'$'}egl_driver"
        printf 'hardware_vulkan=%s\n' "${'$'}hardware_vulkan"
        printf 'vulkan_feature=%s\n' "${'$'}vulkan_feature"
        printf 'vulkan_driver=%s\n' "${'$'}vulkan_driver"
        printf 'configured_mode=%s\n' "${'$'}configured_mode"
        printf 'original_renderer=%s\n' "${'$'}original_renderer"
        printf 'original_disable_vulkan=%s\n' "${'$'}original_disable_vulkan"
        printf 'backup_available=%s\n' "${'$'}backup_available"
        printf 'persistent=%s\n' "${'$'}persistent"
        printf 'restart_required=%s\n' "${'$'}restart_required"
    """.trimIndent()
    val result = withTimeoutOrNull(SHELL_JOB_TIMEOUT_MILLIS) {
        getRootShell().newJob().add(command).to(stdout, stderr).exec()
    }
    if (result == null) {
        KsuCli.reset()
        return@withContext GraphicsRendererStatus(error = "timeout")
    }
    if (!result.isSuccess) {
        return@withContext GraphicsRendererStatus(
            rootAvailable = true,
            error = stderr.joinToString("\n").trim().ifBlank { "status_failed" },
        )
    }
    parseGraphicsRendererStatus(stdout)
}

suspend fun setGraphicsRendererMode(
    mode: GraphicsRendererMode,
    persistent: Boolean,
): GraphicsRendererCommandResult = withContext(Dispatchers.IO) {
    if (mode == GraphicsRendererMode.Custom) {
        return@withContext GraphicsRendererCommandResult(false, error = "invalid_mode")
    }
    val before = getGraphicsRendererStatus()
    if (!before.rootAvailable) {
        return@withContext GraphicsRendererCommandResult(false, before, before.error.ifBlank { "root_unavailable" })
    }
    if (mode == GraphicsRendererMode.Vulkan && !before.vulkanSupported) {
        return@withContext GraphicsRendererCommandResult(false, before, "vulkan_unsupported")
    }
    if (mode == GraphicsRendererMode.SystemDefault) {
        return@withContext restoreGraphicsRendererDefault(before)
    }

    val serviceBase64 = runCatching {
        ksuApp.assets.open(GRAPHICS_RENDERER_SERVICE_ASSET).use { input ->
            Base64.encodeToString(input.readBytes(), Base64.NO_WRAP)
        }
    }.getOrElse {
        return@withContext GraphicsRendererCommandResult(false, before, "service_asset_missing")
    }

    if (!before.backupAvailable) {
        val backup = runGraphicsRendererCommands(
            listOf(
                "mkdir -p $GRAPHICS_RENDERER_DIR && chmod 0700 $GRAPHICS_RENDERER_DIR",
                atomicWriteCommand(GRAPHICS_RENDERER_ORIGINAL_RENDERER, before.rendererProperty),
                atomicWriteCommand(GRAPHICS_RENDERER_ORIGINAL_DISABLE, before.disableVulkanProperty),
                ": > $GRAPHICS_RENDERER_BACKUP_MARKER && chmod 0600 $GRAPHICS_RENDERER_BACKUP_MARKER",
            )
        )
        if (!backup.first) {
            return@withContext GraphicsRendererCommandResult(false, before, backup.second.ifBlank { "backup_failed" })
        }
    }

    val applied = applyGraphicsRendererRuntime(mode)
    if (!applied.first) {
        rollbackGraphicsRendererRuntime(before)
        return@withContext GraphicsRendererCommandResult(
            false,
            getGraphicsRendererStatus(),
            applied.second.ifBlank { "apply_failed" },
        )
    }
    val runtimeStatus = awaitGraphicsRendererStatus { it.matchesRuntimeMode(mode) }
    if (!runtimeStatus.matchesRuntimeMode(mode)) {
        logGraphicsRendererVerificationFailure("runtime", mode, runtimeStatus)
        rollbackGraphicsRendererRuntime(before)
        return@withContext GraphicsRendererCommandResult(
            false,
            getGraphicsRendererStatus(),
            "runtime_verification_failed",
        )
    }

    val committed = writeGraphicsRendererConfiguration(mode, persistent, serviceBase64, restartRequired = true)
    if (!committed.first) {
        rollbackGraphicsRendererRuntime(before)
        restorePreviousGraphicsRendererConfiguration(before, serviceBase64)
        return@withContext GraphicsRendererCommandResult(
            false,
            getGraphicsRendererStatus(),
            committed.second.ifBlank { "config_write_failed" },
        )
    }

    val finalStatus = awaitGraphicsRendererStatus { status ->
        status.configuredMode == mode &&
            status.persistent == persistent &&
            status.matchesRuntimeMode(mode)
    }
    val verified = finalStatus.configuredMode == mode &&
        finalStatus.persistent == persistent &&
        finalStatus.matchesRuntimeMode(mode)
    if (!verified) {
        logGraphicsRendererVerificationFailure("final", mode, finalStatus)
        rollbackGraphicsRendererRuntime(before)
        restorePreviousGraphicsRendererConfiguration(before, serviceBase64)
        return@withContext GraphicsRendererCommandResult(
            false,
            getGraphicsRendererStatus(),
            "final_verification_failed",
        )
    }
    GraphicsRendererCommandResult(true, finalStatus)
}

private suspend fun restoreGraphicsRendererDefault(
    before: GraphicsRendererStatus,
): GraphicsRendererCommandResult {
    if (!before.configured && !before.backupAvailable) {
        val cleanup = runGraphicsRendererCommands(
            listOf(
                "rm -f $GRAPHICS_RENDERER_SERVICE $GRAPHICS_RENDERER_SERVICE.tmp",
                "rm -f $GRAPHICS_RENDERER_MODE_FILE $GRAPHICS_RENDERER_ORIGINAL_RENDERER " +
                    "$GRAPHICS_RENDERER_ORIGINAL_DISABLE $GRAPHICS_RENDERER_BACKUP_MARKER " +
                    "$GRAPHICS_RENDERER_RESTART_MARKER",
                "rmdir $GRAPHICS_RENDERER_DIR 2>/dev/null || true",
            )
        )
        val status = getGraphicsRendererStatus()
        return GraphicsRendererCommandResult(
            success = cleanup.first && !status.configured && !status.persistent,
            status = status,
            error = when {
                !cleanup.first -> cleanup.second.ifBlank { "cleanup_failed" }
                status.configured || status.persistent -> "cleanup_verification_failed"
                else -> ""
            },
        )
    }
    if (!before.backupAvailable) {
        return GraphicsRendererCommandResult(false, before, "backup_missing")
    }
    val restored = runGraphicsRendererCommands(
        graphicsRendererPropertyCommands(
            before.originalRendererProperty,
            before.originalDisableVulkanProperty,
        )
    )
    if (!restored.first) {
        rollbackGraphicsRendererRuntime(before)
        return GraphicsRendererCommandResult(
            false,
            getGraphicsRendererStatus(),
            restored.second.ifBlank { "restore_failed" },
        )
    }
    val runtimeStatus = awaitGraphicsRendererStatus { status ->
        status.rendererProperty == before.originalRendererProperty &&
            status.disableVulkanProperty == before.originalDisableVulkanProperty
    }
    if (
        runtimeStatus.rendererProperty != before.originalRendererProperty ||
        runtimeStatus.disableVulkanProperty != before.originalDisableVulkanProperty
    ) {
        rollbackGraphicsRendererRuntime(before)
        return GraphicsRendererCommandResult(
            false,
            getGraphicsRendererStatus(),
            "restore_verification_failed",
        )
    }
    val cleanup = runGraphicsRendererCommands(
        listOf(
            "rm -f $GRAPHICS_RENDERER_SERVICE",
            "rm -f $GRAPHICS_RENDERER_MODE_FILE $GRAPHICS_RENDERER_ORIGINAL_RENDERER " +
                "$GRAPHICS_RENDERER_ORIGINAL_DISABLE $GRAPHICS_RENDERER_BACKUP_MARKER " +
                "$GRAPHICS_RENDERER_RESTART_MARKER",
            "rmdir $GRAPHICS_RENDERER_DIR 2>/dev/null || true",
        )
    )
    if (!cleanup.first) {
        return GraphicsRendererCommandResult(false, runtimeStatus, cleanup.second.ifBlank { "cleanup_failed" })
    }
    val finalStatus = getGraphicsRendererStatus()
    return GraphicsRendererCommandResult(
        success = !finalStatus.configured && !finalStatus.persistent,
        status = finalStatus,
        error = if (!finalStatus.configured && !finalStatus.persistent) "" else "cleanup_verification_failed",
    )
}

private suspend fun applyGraphicsRendererRuntime(mode: GraphicsRendererMode): Pair<Boolean, String> =
    runGraphicsRendererCommands(
        when (mode) {
            GraphicsRendererMode.Vulkan -> graphicsRendererPropertyCommands("skiavk", "false")
            GraphicsRendererMode.OpenGl -> graphicsRendererPropertyCommands("skiagl", "true")
            else -> emptyList()
        }
    )

private suspend fun rollbackGraphicsRendererRuntime(status: GraphicsRendererStatus) {
    runGraphicsRendererCommands(
        graphicsRendererPropertyCommands(status.rendererProperty, status.disableVulkanProperty)
    )
}

private fun graphicsRendererPropertyCommands(renderer: String, disableVulkan: String): List<String> = listOf(
    resetGraphicsPropertyCommand("debug.hwui.renderer", renderer),
    resetGraphicsPropertyCommand("debug.hwui.disable_vulkan", disableVulkan),
)

private fun resetGraphicsPropertyCommand(name: String, value: String): String {
    val executable = shellQuote(getKsuDaemonPath())
    return if (value.isEmpty()) {
        "$executable resetprop --delete ${shellQuote(name)}"
    } else {
        "$executable resetprop ${shellQuote(name)} ${shellQuote(value)}"
    }
}

private suspend fun awaitGraphicsRendererStatus(
    predicate: (GraphicsRendererStatus) -> Boolean,
): GraphicsRendererStatus {
    var status = getGraphicsRendererStatus()
    repeat(GRAPHICS_RENDERER_VERIFICATION_ATTEMPTS - 1) {
        if (predicate(status)) return status
        if (!status.rootAvailable && status.error == "root_unavailable") return status
        delay(GRAPHICS_RENDERER_VERIFICATION_DELAY_MILLIS)
        status = getGraphicsRendererStatus()
    }
    return status
}

private fun logGraphicsRendererVerificationFailure(
    stage: String,
    expectedMode: GraphicsRendererMode,
    status: GraphicsRendererStatus,
) {
    Log.w(
        TAG,
        "graphics renderer $stage verification failed: expected=${expectedMode.value}, " +
            "current=${status.currentMode.value}, renderer=${status.rendererProperty}, " +
            "disableVulkan=${status.disableVulkanProperty}, configured=${status.configuredMode?.value}, " +
            "persistent=${status.persistent}, error=${status.error}",
    )
}

private suspend fun writeGraphicsRendererConfiguration(
    mode: GraphicsRendererMode,
    persistent: Boolean,
    serviceBase64: String,
    restartRequired: Boolean,
): Pair<Boolean, String> {
    val commands = mutableListOf(
        "mkdir -p $GRAPHICS_RENDERER_DIR /data/adb/service.d && chmod 0700 $GRAPHICS_RENDERER_DIR",
        atomicWriteCommand(GRAPHICS_RENDERER_MODE_FILE, mode.value),
    )
    if (persistent) {
        commands += "printf '%s' ${shellQuote(serviceBase64)} | $BUSYBOX base64 -d > " +
            "$GRAPHICS_RENDERER_SERVICE.tmp && chmod 0700 $GRAPHICS_RENDERER_SERVICE.tmp && " +
            "chown 0:0 $GRAPHICS_RENDERER_SERVICE.tmp && mv -f $GRAPHICS_RENDERER_SERVICE.tmp $GRAPHICS_RENDERER_SERVICE"
    } else {
        commands += "rm -f $GRAPHICS_RENDERER_SERVICE $GRAPHICS_RENDERER_SERVICE.tmp"
    }
    commands += if (restartRequired) {
        ": > $GRAPHICS_RENDERER_RESTART_MARKER && chmod 0600 $GRAPHICS_RENDERER_RESTART_MARKER"
    } else {
        "rm -f $GRAPHICS_RENDERER_RESTART_MARKER"
    }
    return runGraphicsRendererCommands(commands)
}

private suspend fun restorePreviousGraphicsRendererConfiguration(
    status: GraphicsRendererStatus,
    serviceBase64: String,
) {
    val mode = status.configuredMode
    if (mode == null) {
        runGraphicsRendererCommands(
            listOf(
                "rm -f $GRAPHICS_RENDERER_MODE_FILE $GRAPHICS_RENDERER_SERVICE $GRAPHICS_RENDERER_RESTART_MARKER"
            )
        )
    } else {
        writeGraphicsRendererConfiguration(mode, status.persistent, serviceBase64, status.restartRequired)
    }
}

private fun atomicWriteCommand(path: String, value: String): String =
    "printf '%s' ${shellQuote(value)} > $path.tmp && chmod 0600 $path.tmp && mv -f $path.tmp $path"

private suspend fun runGraphicsRendererCommands(commands: List<String>): Pair<Boolean, String> {
    if (commands.isEmpty()) return true to ""
    val stdout = ArrayList<String>()
    val stderr = ArrayList<String>()
    val script = buildString {
        appendLine("set -e")
        commands.forEach(::appendLine)
    }
    val result = runCatching {
        withTimeoutOrNull(SHELL_JOB_TIMEOUT_MILLIS) {
            getRootShell().newJob().add(script).to(stdout, stderr).exec()
        }
    }.getOrElse { error ->
        return false to error.message.orEmpty().ifBlank { "shell_failed" }
    }
    if (result == null) {
        KsuCli.reset()
        return false to "timeout"
    }
    val detail = stderr.joinToString("\n").trim().ifBlank { stdout.joinToString("\n").trim() }
    return result.isSuccess to detail
}

private suspend fun runCpuSpoofCommand(command: String): CpuSpoofCommandResult = withContext(Dispatchers.IO) {
    if (shouldSkipUnsafeKsudCommand()) {
        return@withContext CpuSpoofCommandResult(false, "root_unavailable")
    }

    val stdout = ArrayList<String>()
    val stderr = ArrayList<String>()
    val result = withTimeoutOrNull(SHELL_JOB_TIMEOUT_MILLIS) {
        getRootShell().newJob()
            .add("${shellQuote(getKsuDaemonPath())} cpu-spoof $command")
            .to(stdout, stderr)
            .exec()
    }
    if (result == null) {
        Log.w(TAG, "cpu-spoof command timed out: $command")
        KsuCli.reset()
        return@withContext CpuSpoofCommandResult(false, "timeout")
    }

    if (!result.isSuccess) {
        val error = stderr.joinToString("\n").trim().ifBlank {
            stdout.joinToString("\n").trim().ifBlank { "command_failed" }
        }
        Log.w(TAG, "cpu-spoof command failed: $command, $error")
        return@withContext CpuSpoofCommandResult(false, error)
    }

    CpuSpoofCommandResult(true)
}

private suspend fun runStructuredKsudCommand(
    area: String,
    command: String,
    timeoutMillis: Long = STATUS_TIMEOUT_MILLIS,
): ToolCommandResult = withContext(Dispatchers.IO) {
    if (shouldSkipUnsafeKsudCommand()) {
        return@withContext ToolCommandResult(
            errorCode = "$area.root_unavailable",
            errorMessage = "root shell unavailable",
        )
    }
    runCatching {
        val stdout = ArrayList<String>()
        val stderr = ArrayList<String>()
        val result = withTimeoutOrNull(timeoutMillis) {
            getRootShell().newJob()
                .add("${shellQuote(getKsuDaemonPath())} $command")
                .to(stdout, stderr)
                .exec()
        }
        if (result == null) {
            KsuCli.reset()
            return@runCatching ToolCommandResult(
                errorCode = "$area.timeout",
                errorMessage = "$area command timed out",
                timedOut = true,
            )
        }
        if (result.isSuccess) {
            ToolCommandResult(success = true)
        } else {
            val raw = stderr.joinToString("\n").trim().ifBlank {
                stdout.joinToString("\n").trim().ifBlank { "$area command failed" }
            }
            val structured = parseStructuredKsudError(raw, "$area.command_failed")
            Log.w(TAG, "$area command failed: $command, $raw")
            structured
        }
    }.getOrElse { error ->
        KsuCli.reset()
        ToolCommandResult(
            errorCode = "$area.unavailable",
            errorMessage = error.message.orEmpty().ifBlank { "$area command unavailable" },
        )
    }
}

private suspend fun getKsudTextOutput(
    command: String,
    timeoutMillis: Long = STATUS_TIMEOUT_MILLIS,
): String = withContext(Dispatchers.IO) {
    if (shouldSkipUnsafeKsudCommand()) {
        return@withContext ""
    }
    runCatching {
        val stdout = ArrayList<String>()
        val stderr = ArrayList<String>()
        val result = withTimeoutOrNull(timeoutMillis) {
            getRootShell().newJob()
                .add("${shellQuote(getKsuDaemonPath())} $command")
                .to(stdout, stderr)
                .exec()
        }
        if (result == null) {
            KsuCli.reset()
            return@runCatching ""
        }
        if (result.isSuccess) {
            stdout.joinToString("\n")
        } else {
            stderr.joinToString("\n").ifBlank { stdout.joinToString("\n") }
        }
    }.getOrElse { error ->
        Log.w(TAG, "ksud text command unavailable: $command", error)
        KsuCli.reset()
        ""
    }
}

internal fun parseStructuredKsudError(raw: String, fallbackCode: String): ToolCommandResult {
    val payload = raw.substringAfter(ERROR_PREFIX, "")
    if (payload.isBlank()) {
        return ToolCommandResult(errorCode = fallbackCode, errorMessage = raw.trim())
    }
    val code = payload.substringBefore(':').trim().ifBlank { fallbackCode }
    val message = payload.substringAfter(':', "").trim().ifBlank { raw.trim() }
    return ToolCommandResult(errorCode = code, errorMessage = message)
}

suspend fun readHiddenPathConfig(): HiddenPathConfigReadResult = withContext(Dispatchers.IO) {
    if (shouldSkipUnsafeKsudCommand()) {
        return@withContext HiddenPathConfigReadResult(error = "root shell unavailable")
    }

    runCatching {
        val stdout = ArrayList<String>()
        val stderr = ArrayList<String>()
        val result = withTimeoutOrNull(STATUS_TIMEOUT_MILLIS) {
            getRootShell().newJob()
                .add("${getKsuDaemonPath()} pathmask status")
                .to(stdout, stderr)
                .exec()
        }

        if (result == null) {
            Log.w(TAG, "pathmask status timed out")
            KsuCli.reset()
            return@runCatching HiddenPathConfigReadResult(error = "pathmask status timed out")
        }

        if (!result.isSuccess) {
            val error = stderr.joinToString("\n").trim().ifBlank { "pathmask status failed" }
            Log.w(TAG, "pathmask status failed: $error")
            return@runCatching HiddenPathConfigReadResult(error = error)
        }

        val parsed = parseHiddenPathStatusJson(stdout.joinToString("\n"))
        if (parsed.errorCode == "pathmask.status_parse_failed") {
            Log.w(TAG, "pathmask status returned malformed output; resetting root shell")
            KsuCli.reset()
        }
        parsed
    }.getOrElse {
        Log.w(TAG, "pathmask status unavailable", it)
        KsuCli.reset()
        HiddenPathConfigReadResult(
            error = it.message ?: "pathmask status unavailable",
            errorCode = "pathmask.status_unavailable",
        )
    }
}

internal fun parseHiddenPathStatusJson(raw: String): HiddenPathConfigReadResult {
    val obj = runCatching { parseJsonObjectPayload(raw) }.getOrElse { error ->
        return HiddenPathConfigReadResult(
            error = error.message.orEmpty().ifBlank { "invalid pathmask status JSON" },
            errorCode = "pathmask.status_parse_failed",
        )
    }
    val statusError = obj.optString("error", "").trim()
    if (statusError.isNotBlank()) {
        return HiddenPathConfigReadResult(
            error = statusError,
            errorCode = obj.optString("errorCode", "pathmask.status_failed"),
        )
    }

    return HiddenPathConfigReadResult(config = HiddenPathConfigState(
        targetPaths = obj.optJSONArray("targetPaths").toStringList(),
        appPackages = obj.optJSONArray("appPackages").toStringList(),
        useAppScope = obj.optBoolean("useAppScope", true),
        hideDirents = obj.optBoolean("hideDirents", true),
        hideIsolated = obj.optBoolean("hideIsolated", true),
        autoLoadEnabled = obj.optBoolean("autoLoadEnabled", true),
        autoLoadDelaySeconds = obj.readNonNegativeInt("autoLoadDelaySeconds")
            .coerceIn(0, HIDDEN_PATH_MAX_AUTO_LOAD_DELAY_SECONDS),
        autoLoadRemainingSeconds = obj.readNonNegativeInt("autoLoadRemainingSeconds"),
        loaded = obj.optBoolean("loaded", false),
        currentKmi = obj.optString("currentKmi", ""),
        phase = obj.optString("phase", "unconfigured"),
        savedCount = obj.readNonNegativeInt("savedCount"),
        availableCount = obj.readNonNegativeInt("availableCount"),
        activeCount = obj.readNonNegativeInt("activeCount"),
        resolvedCount = obj.readNonNegativeInt("resolvedCount"),
        activeTargetPaths = obj.readStringOrArray("activeTargetPaths"),
        missingTargetPaths = obj.optJSONArray("missingTargetPaths").toStringList(),
        unresolvedTargetCount = obj.readNonNegativeInt("unresolvedTargetCount"),
        unresolvedTargetPaths = obj.optJSONArray("unresolvedTargetPaths").toStringList(),
        requiresReload = obj.optBoolean("requiresReload", false),
        requiresReboot = obj.optBoolean("requiresReboot", false),
        hasPendingCandidate = obj.optBoolean("hasPendingCandidate", false),
        lastErrorCode = obj.optString("lastErrorCode", ""),
        lastErrorMessage = obj.optString("lastErrorMessage", ""),
        lastLog = obj.optString("lastLog", ""),
        resolvedAppUids = obj.optJSONArray("resolvedAppUids").toStringList(),
        unresolvedAppPackages = obj.optJSONArray("unresolvedAppPackages").toStringList(),
    ))
}

private fun parseJsonObjectPayload(raw: String): JSONObject {
    val trimmed = raw.trim()
    runCatching { JSONObject(trimmed) }.getOrNull()?.let { return it }

    var objectStart = -1
    var depth = 0
    var inString = false
    var escaped = false
    var lastObject: JSONObject? = null
    raw.forEachIndexed { index, character ->
        if (inString) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> inString = false
            }
            return@forEachIndexed
        }

        when (character) {
            '"' -> if (depth > 0) inString = true
            '{' -> {
                if (depth == 0) objectStart = index
                depth += 1
            }
            '}' -> if (depth > 0) {
                depth -= 1
                if (depth == 0 && objectStart >= 0) {
                    runCatching { JSONObject(raw.substring(objectStart, index + 1)) }
                        .getOrNull()
                        ?.let { lastObject = it }
                    objectStart = -1
                }
            }
        }
    }
    lastObject?.let { return it }
    error("invalid pathmask status JSON")
}

private fun JSONObject.readNonNegativeInt(name: String): Int {
    val value = opt(name)
    val parsed = if (value is Number) {
        value.toLong()
    } else if (value is String) {
        value.trim().toLongOrNull()
    } else {
        null
    } ?: 0L
    return parsed.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}

private fun JSONObject.readStringOrArray(name: String): String {
    val value = opt(name)
    return if (value is JSONArray) {
        value.toStringList().joinToString(",")
    } else if (value is String) {
        value.trim()
    } else {
        ""
    }
}

suspend fun getHiddenPathConfig(): HiddenPathConfigState =
    readHiddenPathConfig().config ?: HiddenPathConfigState()

suspend fun saveAndApplyHiddenPathConfig(config: HiddenPathConfigState): ToolCommandResult {
    return runStructuredKsudCommand(
        area = "pathmask",
        command = "pathmask apply-json ${shellQuote(config.toConfigJson())}",
        timeoutMillis = LONG_IO_TIMEOUT_MILLIS,
    )
}

suspend fun setHiddenPathAutoLoad(
    enabled: Boolean,
    delaySeconds: Int? = null,
): ToolCommandResult {
    val safeDelay = delaySeconds?.coerceIn(0, HIDDEN_PATH_MAX_AUTO_LOAD_DELAY_SECONDS)
    val delayArgument = safeDelay?.let { " --delay-seconds $it" }.orEmpty()
    return runStructuredKsudCommand(
        area = "pathmask",
        command = "pathmask set-auto-load $enabled$delayArgument",
    )
}

suspend fun unloadHiddenPathKernelPaths(): ToolCommandResult = runStructuredKsudCommand(
    area = "pathmask",
    command = "pathmask unload",
)

suspend fun deleteHiddenPathConfig(): ToolCommandResult = runStructuredKsudCommand(
    area = "pathmask",
    command = "pathmask delete-config",
)

suspend fun getHiddenPathDiagnostics(): String = getKsudTextOutput(
    command = "pathmask diagnostics",
    timeoutMillis = DIAGNOSTIC_TIMEOUT_MILLIS,
)

private fun normalizeSusfsAbsolutePath(raw: String): String? {
    val trimmed = raw.trim()
    if (
        trimmed.isEmpty() ||
        !trimmed.startsWith('/') ||
        trimmed == "/" ||
        trimmed.any(Char::isISOControl)
    ) {
        return null
    }
    val segments = ArrayDeque<String>()
    trimmed.split('/').forEach { segment ->
        when (segment) {
            "", "." -> Unit
            ".." -> if (segments.isEmpty()) return null else segments.removeLast()
            else -> segments.addLast(segment)
        }
    }
    if (segments.isEmpty()) return null
    val normalized = "/" + segments.joinToString("/")
    if (normalized.toByteArray(Charsets.UTF_8).size > SUSFS_MAX_PATH_BYTES) return null
    return normalized
}

fun normalizeSusfsPath(raw: String): String? {
    val normalized = normalizeSusfsAbsolutePath(raw) ?: return null
    val blockedManagementPaths = listOf(
        "/data/adb/modules",
        "/data/adb/ksu",
        "/data/adb/ap",
    )
    if (
        normalized == "/data/adb" ||
        blockedManagementPaths.any { normalized == it || normalized.startsWith("$it/") }
    ) {
        return null
    }
    return normalized
}

internal data class SusfsVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
)

private val susfsVersionPattern = Regex("(?:^|[^0-9])v?(\\d+)\\.(\\d+)\\.(\\d+)")
private val susfsFeaturePattern = Regex("CONFIG_KSU_SUSFS_[A-Z0-9_]+")

internal fun parseSusfsVersion(raw: String): SusfsVersion? {
    val match = susfsVersionPattern.find(raw.trim()) ?: return null
    return SusfsVersion(
        major = match.groupValues[1].toIntOrNull() ?: return null,
        minor = match.groupValues[2].toIntOrNull() ?: return null,
        patch = match.groupValues[3].toIntOrNull() ?: return null,
    )
}

internal fun parseSusfsFeatureNames(raw: String): Set<String> = susfsFeaturePattern
    .findAll(raw)
    .map(MatchResult::value)
    .toSet()

private fun parseSusfsPolicyBoolean(raw: String): Boolean {
    val value = raw.trim().trim('\'', '"').substringBefore('#').trim()
    return value == "1" || value == "2" || value.equals("true", ignoreCase = true)
}

private fun JSONObject.firstString(vararg names: String): String = names
    .asSequence()
    .map { optString(it, "").trim() }
    .firstOrNull { it.isNotEmpty() }
    .orEmpty()

private fun normalizeSusfsArgument(raw: String): String? {
    val value = raw.trim()
    return value.takeIf {
        it.isNotEmpty() &&
            it.length <= 4096 &&
            !it.any(Char::isISOControl) &&
            !it.contains('|')
    }
}

internal fun normalizeSusfsMapPath(raw: String): String? {
    val normalized = normalizeSusfsAbsolutePath(raw) ?: return null
    // sus_map hides references in process maps. Keep individual module entries
    // importable, but never accept the management roots themselves.
    if (normalized == "/data/adb" || normalized == "/data/adb/ksu" || normalized == "/data/adb/ap") {
        return null
    }
    return normalized
}

private fun parseSusfsKstatEntry(json: JSONObject): SusfsKstatEntry? {
    val arguments = buildList {
        json.optJSONArray("args")?.let { array ->
            for (index in 0 until array.length()) {
                normalizeSusfsArgument(array.optString(index, ""))?.let(::add)
            }
        }
        if (isEmpty()) {
            // SUSFS exports static kstat entries as named stat fields. Keep the
            // documented order so imported v1/v2 backups remain executable.
            listOf(
                "path", "ino", "dev", "nlink", "size", "atime", "atime_nsec",
                "mtime", "mtime_nsec", "ctime", "ctime_nsec", "blocks", "blksize",
            ).forEach { key ->
                if (json.has(key)) normalizeSusfsArgument(json.optString(key, ""))?.let(::add)
            }
        }
    }
    return arguments.takeIf { it.size == 13 }?.let(::SusfsKstatEntry)
}

/** Parses the SUSFS module's version-2 backup without touching the device. */
internal fun parseSusfsBackupJson(raw: String): SusfsBackupImportResult = runCatching {
    val root = JSONObject(raw)
    val version = root.optInt("version", 1)
    val warnings = mutableListOf<String>()
    if (version !in 1..2) {
        return@runCatching SusfsBackupImportResult(error = "unsupported_backup_version:$version")
    }

    val loopPaths = buildList {
        val array = root.optJSONArray("sus_path")
        if (array != null) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                if (!item.optBoolean("is_loop", false)) continue
                val rawPath = item.firstString("path", "target")
                val path = normalizeSusfsPath(rawPath)
                if (path == null) {
                    if (rawPath.isNotBlank()) warnings += "skipped_path:$rawPath"
                } else if (path !in this) {
                    add(path)
                }
            }
        }
    }
    val actualNormalPaths = buildList {
        val array = root.optJSONArray("sus_path")
        if (array != null) {
            for (index in 0 until array.length()) {
                val item = array.opt(index)
                val path = when (item) {
                    is JSONObject -> item.firstString("path", "target")
                    else -> item?.toString().orEmpty()
                }
                if (item !is JSONObject || !item.optBoolean("is_loop", false)) {
                    val normalized = normalizeSusfsPath(path)
                    if (normalized == null) {
                        if (path.isNotBlank()) warnings += "skipped_path:$path"
                    } else if (normalized !in this) {
                        add(normalized)
                    }
                }
            }
        }
    }

    val maps = buildList {
        val array = root.optJSONArray("sus_map")
        if (array != null) {
            for (index in 0 until array.length()) {
                val path = normalizeSusfsMapPath(array.optString(index, ""))
                if (path == null) {
                    if (array.optString(index, "").isNotBlank()) warnings += "skipped_map"
                } else if (path !in this) add(path)
            }
        }
    }
    val redirects = buildList {
        val array = root.optJSONArray("open_redirect")
        if (array != null) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val original = normalizeSusfsPath(item.firstString("original", "original_path", "path"))
                val redirected = normalizeSusfsPath(item.firstString("redirected", "redirected_path", "target"))
                if (original == null || redirected == null) {
                    warnings += "skipped_open_redirect"
                    continue
                }
                val uidScheme = item.firstString("uid_scheme", "uidScheme").ifBlank { "3" }
                if (uidScheme.toIntOrNull() !in 0..4) {
                    warnings += "skipped_open_redirect_uid"
                    continue
                }
                add(SusfsOpenRedirectEntry(original, redirected, uidScheme))
            }
        }
    }.distinct()
    val kstats = buildList {
        val array = root.optJSONArray("sus_kstat")
        if (array != null) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                parseSusfsKstatEntry(item)?.let(::add) ?: run { warnings += "skipped_sus_kstat" }
            }
        }
    }
    val uname = root.optJSONObject("uname")
    val cmdline = root.optString("cmdline_or_bootconfig", "").trim()
    val hideAllLegacy = root.optBoolean("hide_sus_mnts_for_all_procs", false)
    if (hideAllLegacy) warnings += "migrated_hide_all_to_non_su"

    SusfsBackupImportResult(
        config = SusfsPathConfigState(
            paths = actualNormalPaths,
            loopPaths = loopPaths,
            susMaps = maps,
            openRedirects = redirects,
            kstatEntries = kstats,
            enabled = root.optBoolean("enabled", true),
            logging = root.optBoolean("logging", false),
            avcLogSpoofing = root.optBoolean("avc_log_spoofing", false),
            hideSusMntsForNonSuProcs = root.optBoolean("hide_sus_mnts_for_non_su_procs", false) || hideAllLegacy,
            unameRelease = uname?.firstString("release", "kernel_release").orEmpty(),
            unameVersion = uname?.firstString("version", "build").orEmpty(),
            cmdlineOrBootconfig = cmdline,
        ),
        warnings = warnings.distinct(),
    )
}.getOrElse { error ->
    SusfsBackupImportResult(error = error.message.orEmpty().ifBlank { "invalid_backup" })
}

internal fun parseSusfsConfigOutput(
    lines: List<String>,
    capabilities: SusfsCapabilities,
): SusfsPathConfigState {
    fun value(prefix: String): String = lines.firstOrNull { it.startsWith(prefix) }
        ?.substringAfter('=').orEmpty().trim()
    fun setting(name: String): String = value("__SETTING__$name=")
    fun legacySetting(name: String): String = value("__LEGACY__$name=")
    fun status(name: String): String = value("__STATUS__$name=")
    fun values(prefix: String): List<String> = lines.asSequence()
        .filter { it.startsWith(prefix) }
        .map { it.substringAfter('=') }
        .mapNotNull(::normalizeSusfsPath)
        .distinct()
        .toList()
    val redirects = lines.asSequence()
        .filter { it.startsWith("__REDIRECT__=") }
        .map { it.substringAfter('=') }
        .map { it.split('|', limit = 3) }
        .mapNotNull { parts ->
            if (parts.size < 2) return@mapNotNull null
            val original = normalizeSusfsPath(parts[0]) ?: return@mapNotNull null
            val redirected = normalizeSusfsPath(parts[1]) ?: return@mapNotNull null
            val uidScheme = parts.getOrElse(2) { "" }.ifBlank { "3" }
            if (uidScheme.toIntOrNull() !in 0..4) return@mapNotNull null
            SusfsOpenRedirectEntry(original, redirected, uidScheme)
        }
        .distinct()
        .toList()
    val kstats = lines.asSequence()
        .filter { it.startsWith("__KSTAT__=") }
        .map { it.substringAfter('=') }
        .map { it.split('|').mapNotNull(::normalizeSusfsArgument) }
        .filter { it.size == 13 }
        .map(::SusfsKstatEntry)
        .toList()
    val issues = lines.asSequence()
        .filter { it.startsWith("__ISSUE__=") }
        .map { it.substringAfter('=') }
        .map { it.split('\t', limit = 4) }
        .mapNotNull { parts ->
            if (parts.size != 4) null else SusfsApplyIssue(parts[1], parts[2], parts[3])
        }
        .toList()
    val configuredGeneration = setting("generation")
    val statusGeneration = status("generation")
    val statusMatchesConfig = statusGeneration.isNotBlank() &&
        (configuredGeneration.isBlank() || statusGeneration == configuredGeneration)
    return SusfsPathConfigState(
        available = value("__TOOL__=").isNotBlank() && capabilities.supportsAddSusPath,
        toolPath = value("__TOOL__="),
        paths = values("__PATH__="),
        loopPaths = values("__LOOP__="),
        susMaps = lines.asSequence()
            .filter { it.startsWith("__MAP__=") }
            .map { it.substringAfter('=') }
            .mapNotNull(::normalizeSusfsMapPath)
            .distinct()
            .toList(),
        openRedirects = redirects,
        kstatEntries = kstats,
        enabled = setting("enabled") != "0",
        logging = setting("logging").ifBlank { legacySetting("susfs_log") }
            .let(::parseSusfsPolicyBoolean),
        avcLogSpoofing = setting("avc_log_spoofing").ifBlank { legacySetting("avc_log_spoofing") }
            .let(::parseSusfsPolicyBoolean),
        hideSusMntsForNonSuProcs = setting("hide_sus_mnts_for_non_su_procs")
            .ifBlank { legacySetting("hide_sus_mnts_for_all_or_non_su_procs") }
            .let(::parseSusfsPolicyBoolean),
        unameRelease = setting("uname_release"),
        unameVersion = setting("uname_version"),
        cmdlineOrBootconfig = setting("cmdline_or_bootconfig"),
        capabilities = capabilities,
        runtimeStatus = SusfsRuntimeStatus(
            generation = configuredGeneration.ifBlank { statusGeneration },
            state = if (statusMatchesConfig) status("state").ifBlank { "unknown" } else "pending",
            configuredCount = if (statusMatchesConfig) status("configured_count").toIntOrNull() ?: 0 else 0,
            appliedCount = if (statusMatchesConfig) status("applied_count").toIntOrNull() ?: 0 else 0,
            skippedCount = if (statusMatchesConfig) status("skipped_count").toIntOrNull() ?: 0 else 0,
            failedCount = if (statusMatchesConfig) status("failed_count").toIntOrNull() ?: 0 else 0,
            requiresReboot = if (statusMatchesConfig) {
                status("requires_reboot") == "1"
            } else {
                setting("requires_reboot") == "1"
            },
            startedAt = if (statusMatchesConfig) status("started_at") else "",
            finishedAt = if (statusMatchesConfig) status("finished_at") else "",
            issues = if (statusMatchesConfig) issues else emptyList(),
        ),
        error = when {
            value("__TOOL__=").isBlank() -> "tool_unavailable"
            !capabilities.supportsAddSusPath -> "path_feature_unavailable"
            else -> ""
        },
    )
}

internal fun isSusfsApplyStateSuccessful(state: String): Boolean =
    state in setOf("applied", "reboot_pending", "disabled")

internal fun buildSusfsBackupJson(config: SusfsPathConfigState): String {
    val root = JSONObject()
        .put("version", 2)
        .put("enabled", config.enabled)
        .put("cmdline_or_bootconfig", config.cmdlineOrBootconfig)
        .put("avc_log_spoofing", config.avcLogSpoofing)
        .put("logging", config.logging)
        .put("hide_sus_mnts_for_non_su_procs", config.hideSusMntsForNonSuProcs)
    root.put(
        "uname",
        JSONObject().put("version", config.unameVersion).put("release", config.unameRelease),
    )
    root.put("sus_path", JSONArray().apply {
        config.paths.forEach { put(JSONObject().put("path", it).put("is_loop", false)) }
        config.loopPaths.forEach { put(JSONObject().put("path", it).put("is_loop", true)) }
    })
    root.put("sus_kstat", JSONArray().apply {
        config.kstatEntries.forEach { put(JSONObject().put("args", JSONArray(it.arguments))) }
    })
    root.put("open_redirect", JSONArray().apply {
        config.openRedirects.forEach {
            put(
                JSONObject()
                    .put("original", it.originalPath)
                    .put("redirected", it.redirectedPath)
                    .put("uid_scheme", it.uidScheme),
            )
        }
    })
    root.put("sus_map", JSONArray(config.susMaps))
    return root.toString(2)
}

internal fun mergeSusfsConfig(
    current: SusfsPathConfigState,
    imported: SusfsPathConfigState,
): SusfsPathConfigState = current.copy(
    paths = (current.paths + imported.paths).distinct(),
    loopPaths = (current.loopPaths + imported.loopPaths).distinct(),
    susMaps = (current.susMaps + imported.susMaps).distinct(),
    openRedirects = (current.openRedirects + imported.openRedirects).distinct(),
    kstatEntries = (current.kstatEntries + imported.kstatEntries).distinct(),
    enabled = imported.enabled,
    logging = imported.logging,
    avcLogSpoofing = imported.avcLogSpoofing,
    hideSusMntsForNonSuProcs = imported.hideSusMntsForNonSuProcs,
    unameRelease = imported.unameRelease.ifBlank { current.unameRelease },
    unameVersion = imported.unameVersion.ifBlank { current.unameVersion },
    cmdlineOrBootconfig = imported.cmdlineOrBootconfig.ifBlank { current.cmdlineOrBootconfig },
)

internal fun susfsRequiresReboot(
    previous: SusfsPathConfigState,
    next: SusfsPathConfigState,
): Boolean {
    if (previous.enabled && !next.enabled) return true
    if (previous.paths.any { it !in next.paths }) return true
    if (previous.loopPaths.any { it !in next.loopPaths }) return true
    if (previous.susMaps.any { it !in next.susMaps }) return true
    if (previous.openRedirects.any { it !in next.openRedirects }) return true
    if (previous.kstatEntries.any { it !in next.kstatEntries }) return true
    if (previous.cmdlineOrBootconfig.isNotBlank() && next.cmdlineOrBootconfig.isBlank()) return true
    return false
}

private fun SusfsVersion.atLeast(major: Int, minor: Int, patch: Int): Boolean =
    compareValuesBy(this, SusfsVersion(major, minor, patch), SusfsVersion::major, SusfsVersion::minor, SusfsVersion::patch) >= 0

private fun SusfsVersion.before(major: Int, minor: Int, patch: Int): Boolean =
    !atLeast(major, minor, patch)

internal fun shouldPrepareSusfsExternalStorageRoots(
    versionText: String,
    paths: Collection<String>,
): Boolean {
    val version = parseSusfsVersion(versionText) ?: return false
    val needsLegacyRoots = version.atLeast(1, 5, 8) && version.before(2, 1, 0)
    if (!needsLegacyRoots) return false
    return paths.any { path ->
        path == "/sdcard" ||
            path.startsWith("/sdcard/") ||
            path == "/storage/emulated" ||
            path.startsWith("/storage/emulated/") ||
            path == "/storage/self/primary" ||
            path.startsWith("/storage/self/primary/")
    }
}

internal fun buildSusfsCapabilities(
    toolAvailable: Boolean,
    versionText: String,
    featureText: String,
    featureProbeSucceeded: Boolean,
): SusfsCapabilities {
    val version = parseSusfsVersion(versionText)
    val features = parseSusfsFeatureNames(featureText)
    val effectiveFeatureProbe = featureProbeSucceeded && features.isNotEmpty()
    fun has(feature: String): Boolean = feature in features
    return SusfsCapabilities(
        version = versionText.trim(),
        features = features,
        featureProbeAvailable = effectiveFeatureProbe,
        supportsAddSusPath = toolAvailable && (!effectiveFeatureProbe || has(SUSFS_PATH_FEATURE)),
        // add_sus_path_loop is part of the SUS_PATH capability in SUSFS v2.x.
        supportsPathLoop = has(SUSFS_PATH_FEATURE) ||
            (!effectiveFeatureProbe && version?.atLeast(1, 5, 9) == true),
        supportsTryUmount = has(SUSFS_MOUNT_FEATURE) ||
            (!effectiveFeatureProbe && version?.atLeast(1, 5, 3) == true),
        supportsKstat = has(SUSFS_KSTAT_FEATURE) ||
            (!effectiveFeatureProbe && version?.atLeast(2, 0, 0) == true),
        supportsOpenRedirect = has(SUSFS_OPEN_REDIRECT_FEATURE),
        supportsSusMap = has(SUSFS_MAP_FEATURE),
        supportsUnameSpoof = has(SUSFS_UNAME_FEATURE) ||
            (!effectiveFeatureProbe && version?.atLeast(1, 5, 0) == true),
        supportsCmdlineSpoof = has(SUSFS_CMDLINE_FEATURE) ||
            (!effectiveFeatureProbe && version?.atLeast(1, 5, 4) == true),
        supportsLogging = has(SUSFS_LOG_FEATURE) ||
            (!effectiveFeatureProbe && version?.atLeast(1, 5, 0) == true),
        // These commands are runtime ABI operations and are version gated.
        supportsAvcLogSpoofing = version?.atLeast(1, 5, 3) == true,
        supportsHideSusMounts = has(SUSFS_MOUNT_FEATURE) ||
            (!effectiveFeatureProbe && version?.atLeast(1, 5, 7) == true),
    )
}

/** 设备信息卡片用的 SUSFS 一行摘要；既没有工具也没有挂载痕迹时返回空串。 */
internal fun buildSusfsSummary(
    toolPath: String,
    version: String,
    featureCount: Int,
    mountCount: Int,
    hiddenPathCount: Int,
): String {
    if (version.isBlank() && mountCount <= 0) return ""
    val parts = ArrayList<String>(5)
    parts += version.ifBlank { "已检测到挂载" }
    if (featureCount > 0) parts += "特性 $featureCount 项"
    // mountCount 统计 /proc/mounts 里出现 susfs 的挂载条目（SUSFS 的 sus mount 会以此形式出现）
    if (mountCount > 0) parts += "挂载 $mountCount 项"
    if (hiddenPathCount > 0) parts += "隐藏路径 $hiddenPathCount 条"
    if (toolPath.isBlank()) parts += "未找到 ksu_susfs"
    return parts.joinToString(" · ")
}

/**
 * 探测 SUSFS：先找 ksu_susfs 工具读版本与已启用特性，再统计挂载痕迹与已配置的隐藏路径。
 * 没有 SUSFS 的内核返回空串，调用方隐藏该行。
 */
suspend fun probeSusfsSummary(): String = withContext(Dispatchers.IO) {
    if (shouldSkipUnsafeKsudCommand()) return@withContext ""
    val stdout = ArrayList<String>()
    val stderr = ArrayList<String>()
    val command = buildString {
        appendTrustedSusfsToolDiscovery()
        appendLine("printf '__TOOL__=%s\\n' \"${'$'}tool\"")
        appendLine("config_dir=/data/adb/ksu/susfs")
        appendLine("resolved=\$(readlink -f /data/adb/ksu/susfs/current 2>/dev/null || true)")
        appendLine("case \"${'$'}resolved\" in /data/adb/ksu/susfs/generations/*) config_dir=\"${'$'}resolved\" ;; esac")
        appendLine("version=''")
        appendLine("features=0")
        appendLine("if [ -n \"${'$'}tool\" ]; then")
        appendLine("  version=\"\$(\"${'$'}tool\" show version 2>/dev/null | sed -n '1p')\"")
        appendLine("  feature_output=\"\$(\"${'$'}tool\" show enabled_features 2>/dev/null)\"")
        appendLine("  if [ -n \"${'$'}feature_output\" ]; then features=\$(printf '%s\\n' \"${'$'}feature_output\" | grep -c .); fi")
        appendLine("fi")
        appendLine("printf '__VERSION__=%s\\n' \"${'$'}version\"")
        appendLine("printf '__FEATURES__=%s\\n' \"${'$'}features\"")
        appendLine("printf '__MOUNTS__=%s\\n' \"\$(grep -ci susfs /proc/mounts 2>/dev/null)\"")
        appendLine("hidden=0")
        appendLine("if [ -f \"${'$'}config_dir/paths.txt\" ]; then")
        appendLine("  hidden=\$(grep -c . \"${'$'}config_dir/paths.txt\" 2>/dev/null)")
        appendLine("fi")
        appendLine("printf '__HIDDEN__=%s\\n' \"${'$'}hidden\"")
    }
    val result = runCatching {
        withTimeoutOrNull(SHELL_JOB_TIMEOUT_MILLIS) {
            getRootShell().newJob().add(command).to(stdout, stderr).exec()
        }
    }.getOrElse { error ->
        KsuCli.reset()
        return@withContext ""
    } ?: run {
        KsuCli.reset()
        return@withContext ""
    }
    if (!result.isSuccess) return@withContext ""
    fun field(name: String): String = stdout.firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')
        ?.trim()
        .orEmpty()
    buildSusfsSummary(
        toolPath = field("__TOOL__"),
        version = field("__VERSION__"),
        featureCount = field("__FEATURES__").toIntOrNull() ?: 0,
        mountCount = field("__MOUNTS__").toIntOrNull() ?: 0,
        hiddenPathCount = field("__HIDDEN__").toIntOrNull() ?: 0,
    )
}

suspend fun getSusfsPathConfig(): SusfsPathConfigState = withContext(Dispatchers.IO) {
    if (shouldSkipUnsafeKsudCommand()) {
        return@withContext SusfsPathConfigState(error = "root_unavailable")
    }
    if (Natives.isLateLoadMode || Natives.isLkmMode) {
        return@withContext SusfsPathConfigState(error = "gki_mode_required")
    }

    val stdout = ArrayList<String>()
    val stderr = ArrayList<String>()
    val command = buildString {
        appendTrustedSusfsToolDiscovery()
        appendLine("printf '__TOOL__=%s\\n' \"${'$'}tool\"")
        appendLine("config_dir=/data/adb/ksu/susfs")
        appendLine("resolved=\$(readlink -f /data/adb/ksu/susfs/current 2>/dev/null || true)")
        appendLine("case \"${'$'}resolved\" in /data/adb/ksu/susfs/generations/*) config_dir=\"${'$'}resolved\" ;; esac")
        appendLine("version=''")
        appendLine("features=''")
        appendLine("feature_output=''")
        appendLine("feature_probe=0")
        appendLine("if [ -n \"${'$'}tool\" ]; then")
        appendLine("  version=\"${'$'}(\"${'$'}tool\" show version 2>/dev/null | sed -n '1p')\"")
        appendLine("  if feature_output=\"${'$'}(\"${'$'}tool\" show enabled_features 2>/dev/null)\" && printf '%s' \"${'$'}feature_output\" | grep -q 'CONFIG_KSU_SUSFS_'; then")
        appendLine("    feature_probe=1")
        appendLine("    features=\"${'$'}(printf '%s' \"${'$'}feature_output\" | tr '\\n' ' ')\"")
        appendLine("  else")
        appendLine("    features=''")
        appendLine("  fi")
        appendLine("fi")
        appendLine("printf '__VERSION__=%s\\n' \"${'$'}version\"")
        appendLine("printf '__FEATURE_PROBE__=%s\\n' \"${'$'}feature_probe\"")
        appendLine("printf '__FEATURES__=%s\\n' \"${'$'}features\"")
        appendLine("for setting in enabled logging avc_log_spoofing hide_sus_mnts_for_non_su_procs uname_release uname_version cmdline_or_bootconfig generation requires_reboot; do")
        appendLine("  setting_value=")
        appendLine("  if [ -f \"${'$'}config_dir/settings.conf\" ]; then setting_value=\$(sed -n \"s/^${'$'}setting=//p\" \"${'$'}config_dir/settings.conf\" | sed -n '1p'); fi")
        appendLine("  printf '__SETTING__%s=%s\\n' \"${'$'}setting\" \"${'$'}setting_value\"")
        appendLine("done")
        appendLine("legacy_config=/data/adb/susfs4ksu/config.sh")
        appendLine("for setting in susfs_log avc_log_spoofing hide_sus_mnts_for_all_or_non_su_procs; do")
        appendLine("  legacy_value=")
        appendLine("  if [ -f \"${'$'}legacy_config\" ]; then legacy_value=\$(sed -n \"s/^${'$'}setting=//p\" \"${'$'}legacy_config\" | sed -n '1p'); fi")
        appendLine("  printf '__LEGACY__%s=%s\\n' \"${'$'}setting\" \"${'$'}legacy_value\"")
        appendLine("done")
        appendLine("if [ -f \"${'$'}config_dir/paths.txt\" ]; then")
        appendLine("  while IFS= read -r target_path; do")
        appendLine("    [ -n \"${'$'}target_path\" ] && printf '__PATH__=%s\\n' \"${'$'}target_path\"")
        appendLine("  done < \"${'$'}config_dir/paths.txt\"")
        appendLine("fi")
        appendLine("if [ -f \"${'$'}config_dir/path_loop.txt\" ]; then while IFS= read -r target_path; do [ -n \"${'$'}target_path\" ] && printf '__LOOP__=%s\\n' \"${'$'}target_path\"; done < \"${'$'}config_dir/path_loop.txt\"; fi")
        appendLine("if [ -f \"${'$'}config_dir/sus_maps.txt\" ]; then while IFS= read -r target_path; do [ -n \"${'$'}target_path\" ] && printf '__MAP__=%s\\n' \"${'$'}target_path\"; done < \"${'$'}config_dir/sus_maps.txt\"; fi")
        appendLine("if [ -f \"${'$'}config_dir/open_redirect.txt\" ]; then while IFS= read -r redirect; do [ -n \"${'$'}redirect\" ] && printf '__REDIRECT__=%s\\n' \"${'$'}redirect\"; done < \"${'$'}config_dir/open_redirect.txt\"; fi")
        appendLine("if [ -f \"${'$'}config_dir/sus_kstat_statically.txt\" ]; then while IFS= read -r kstat; do [ -n \"${'$'}kstat\" ] && printf '__KSTAT__=%s\\n' \"${'$'}kstat\"; done < \"${'$'}config_dir/sus_kstat_statically.txt\"; fi")
        appendLine("if [ -f /data/adb/ksu/susfs/status.conf ]; then while IFS='=' read -r key value; do printf '__STATUS__%s=%s\\n' \"${'$'}key\" \"${'$'}value\"; done < /data/adb/ksu/susfs/status.conf; fi")
        appendLine("if [ -f /data/adb/ksu/susfs/status.entries ]; then while IFS= read -r issue; do printf '__ISSUE__=%s\\n' \"${'$'}issue\"; done < /data/adb/ksu/susfs/status.entries; fi")
    }
    val result = runCatching {
        withTimeoutOrNull(SHELL_JOB_TIMEOUT_MILLIS) {
            getRootShell().newJob().add(command).to(stdout, stderr).exec()
        }
    }.getOrElse { error ->
        KsuCli.reset()
        return@withContext SusfsPathConfigState(error = error.message.orEmpty().ifBlank { "shell_failed" })
    }
    if (result == null) {
        KsuCli.reset()
        return@withContext SusfsPathConfigState(error = "timeout")
    }
    if (!result.isSuccess) {
        return@withContext SusfsPathConfigState(
            error = stderr.joinToString("\n").trim().ifBlank { "probe_failed" },
        )
    }

    val toolPath = stdout.firstOrNull { it.startsWith("__TOOL__=") }
        ?.substringAfter('=')
        ?.trim()
        .orEmpty()
    val paths = stdout.asSequence()
        .filter { it.startsWith("__PATH__=") }
        .map { it.substringAfter('=') }
        .mapNotNull(::normalizeSusfsPath)
        .distinct()
        .toList()
    val toolVersion = stdout.firstOrNull { it.startsWith("__VERSION__=") }
        ?.substringAfter('=')
        ?.trim()
        .orEmpty()
    val featureText = stdout.firstOrNull { it.startsWith("__FEATURES__=") }
        ?.substringAfter('=')
        ?.trim()
        .orEmpty()
    val featureProbeSucceeded = stdout.firstOrNull { it.startsWith("__FEATURE_PROBE__=") }
        ?.substringAfter('=')
        ?.trim() == "1"
    val capabilities = buildSusfsCapabilities(
        toolAvailable = toolPath.isNotBlank(),
        versionText = toolVersion,
        featureText = featureText,
        featureProbeSucceeded = featureProbeSucceeded,
    )
    parseSusfsConfigOutput(stdout, capabilities).copy(
        available = capabilities.supportsAddSusPath,
        toolPath = toolPath,
        paths = paths,
    )
}

suspend fun getSusfsDiagnostics(): String = withContext(Dispatchers.IO) {
    if (shouldSkipUnsafeKsudCommand()) return@withContext "error=root_unavailable\n"
    if (Natives.isLateLoadMode || Natives.isLkmMode) return@withContext "error=gki_mode_required\n"

    val stdout = ArrayList<String>()
    val stderr = ArrayList<String>()
    val command = buildString {
        appendTrustedSusfsToolDiscovery()
        appendLine("config_dir=/data/adb/ksu/susfs")
        appendLine("resolved=\$(readlink -f /data/adb/ksu/susfs/current 2>/dev/null || true)")
        appendLine("case \"${'$'}resolved\" in /data/adb/ksu/susfs/generations/*) config_dir=\"${'$'}resolved\" ;; esac")
        appendLine("printf '[probe]\\n'")
        appendLine("printf 'timestamp=%s\\n' \"\$(date -u '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null)\"")
        appendLine("printf 'tool=%s\\n' \"${'$'}tool\"")
        appendLine("printf 'config_dir=%s\\n' \"${'$'}config_dir\"")
        appendLine("if [ -n \"${'$'}tool\" ]; then")
        appendLine("  printf 'version=%s\\n' \"\$(\"${'$'}tool\" show version 2>/dev/null | sed -n '1p')\"")
        appendLine("  printf 'variant=%s\\n' \"\$(\"${'$'}tool\" show variant 2>/dev/null | sed -n '1p')\"")
        appendLine("  printf '[features]\\n'")
        appendLine("  \"${'$'}tool\" show enabled_features 2>&1 || true")
        appendLine("fi")
        appendLine("for file in settings.conf paths.txt path_loop.txt sus_maps.txt open_redirect.txt sus_kstat_statically.txt; do")
        appendLine("  printf '[config:%s]\\n' \"${'$'}file\"")
        appendLine("  if [ -f \"${'$'}config_dir/${'$'}file\" ]; then sed -n '1,512p' \"${'$'}config_dir/${'$'}file\"; else printf '<missing>\\n'; fi")
        appendLine("done")
        appendLine("for file in status.conf status.entries; do")
        appendLine("  printf '[runtime:%s]\\n' \"${'$'}file\"")
        appendLine("  if [ -f /data/adb/ksu/susfs/${'$'}file ]; then sed -n '1,512p' /data/adb/ksu/susfs/${'$'}file; else printf '<missing>\\n'; fi")
        appendLine("done")
        appendLine("printf '[runtime:runtime.log]\\n'")
        appendLine("if [ -f /data/adb/ksu/susfs/logs/runtime.log ]; then $BUSYBOX tail -c 131072 /data/adb/ksu/susfs/logs/runtime.log; else printf '<missing>\\n'; fi")
    }
    val result = runCatching {
        withTimeoutOrNull(SHELL_JOB_TIMEOUT_MILLIS) {
            getRootShell().newJob().add(command).to(stdout, stderr).exec()
        }
    }.getOrElse { error ->
        KsuCli.reset()
        return@withContext "error=${error.message.orEmpty().ifBlank { "shell_failed" }}\n"
    }
    if (result == null) {
        KsuCli.reset()
        return@withContext "error=timeout\n"
    }
    val managerHeader = buildString {
        appendLine("[manager]")
        appendLine("version=${BuildConfig.VERSION_NAME}")
        appendLine("version_code=${BuildConfig.VERSION_CODE}")
    }
    if (!result.isSuccess) {
        return@withContext managerHeader + "error=" +
            stderr.joinToString("\n").trim().ifBlank { "diagnostic_failed" } + "\n"
    }
    managerHeader + stdout.joinToString("\n", postfix = "\n")
}

private fun susfsLineListText(values: List<String>): String =
    values.joinToString(separator = "\n", postfix = if (values.isEmpty()) "" else "\n")

private fun isValidSusfsSettingValue(value: String): Boolean =
    value.length <= 4096 && !value.any(Char::isISOControl) && !value.contains('=')

private fun StringBuilder.appendTrustedSusfsToolDiscovery() {
    appendLine("tool=''")
    appendLine("for candidate in /data/adb/ksu/bin/ksu_susfs /data/adb/ap/bin/ksu_susfs /system/bin/ksu_susfs; do")
    appendLine("  [ -f \"${'$'}candidate\" ] && [ -x \"${'$'}candidate\" ] && [ ! -L \"${'$'}candidate\" ] || continue")
    appendLine("  owner=\$(stat -c %u \"${'$'}candidate\" 2>/dev/null) || continue")
    appendLine("  mode=\$(stat -c %a \"${'$'}candidate\" 2>/dev/null) || continue")
    appendLine("  [ \"${'$'}owner\" = 0 ] || continue")
    appendLine("  case \"${'$'}mode\" in [0-7][0-7][0-7]|[0-7][0-7][0-7][0-7]) ;; *) continue ;; esac")
    appendLine("  [ ${'$'}((0${'$'}mode & 022)) -eq 0 ] || continue")
    appendLine("  tool=\"${'$'}candidate\"")
    appendLine("  break")
    appendLine("done")
}

internal fun validateSusfsConfig(config: SusfsPathConfigState): String {
    val allPathCount = config.paths.size + config.loopPaths.size + config.susMaps.size
    if (allPathCount > 256 || config.openRedirects.size > 64 || config.kstatEntries.size > 128) {
        return "too_many_entries"
    }
    if (config.paths.mapNotNull(::normalizeSusfsPath).distinct().size != config.paths.size) return "invalid_path"
    if (config.loopPaths.mapNotNull(::normalizeSusfsPath).distinct().size != config.loopPaths.size) return "invalid_loop_path"
    if (config.paths.any(config.loopPaths::contains)) return "duplicate_path_mode"
    if (config.susMaps.mapNotNull(::normalizeSusfsMapPath).distinct().size != config.susMaps.size) return "invalid_sus_map"
    if (config.openRedirects.any { entry ->
            normalizeSusfsPath(entry.originalPath) == null ||
                normalizeSusfsPath(entry.redirectedPath) == null ||
                entry.uidScheme.toIntOrNull() !in 0..4
        }
    ) return "invalid_open_redirect"
    if (config.kstatEntries.any { entry ->
            entry.arguments.size != 13 || entry.arguments.any { normalizeSusfsArgument(it) == null }
        }
    ) return "invalid_sus_kstat"
    if (
        !isValidSusfsSettingValue(config.unameRelease) ||
        !isValidSusfsSettingValue(config.unameVersion) ||
        !isValidSusfsSettingValue(config.cmdlineOrBootconfig)
    ) return "invalid_setting"
    val totalBytes = config.paths.sumOf { it.toByteArray(Charsets.UTF_8).size } +
        config.loopPaths.sumOf { it.toByteArray(Charsets.UTF_8).size } +
        config.susMaps.sumOf { it.toByteArray(Charsets.UTF_8).size } +
        config.openRedirects.sumOf {
            it.originalPath.toByteArray(Charsets.UTF_8).size + it.redirectedPath.toByteArray(Charsets.UTF_8).size + 4
        } +
        config.kstatEntries.sumOf { entry ->
            entry.arguments.sumOf { it.toByteArray(Charsets.UTF_8).size + 1 }
        } +
        config.unameRelease.toByteArray(Charsets.UTF_8).size +
        config.unameVersion.toByteArray(Charsets.UTF_8).size +
        config.cmdlineOrBootconfig.toByteArray(Charsets.UTF_8).size
    if (totalBytes > SUSFS_CONFIG_MAX_BYTES) return "config_too_large"
    return ""
}

private fun editableSusfsConfigEquals(
    first: SusfsPathConfigState,
    second: SusfsPathConfigState,
): Boolean = first.enabled == second.enabled &&
    first.paths == second.paths &&
    first.loopPaths == second.loopPaths &&
    first.susMaps == second.susMaps &&
    first.openRedirects == second.openRedirects &&
    first.kstatEntries == second.kstatEntries &&
    first.logging == second.logging &&
    first.avcLogSpoofing == second.avcLogSpoofing &&
    first.hideSusMntsForNonSuProcs == second.hideSusMntsForNonSuProcs &&
    first.unameRelease == second.unameRelease &&
    first.unameVersion == second.unameVersion &&
    first.cmdlineOrBootconfig == second.cmdlineOrBootconfig

suspend fun saveAndApplySusfsPathConfig(paths: List<String>): SusfsPathApplyResult {
    val current = getSusfsPathConfig()
    if (!current.available) return SusfsPathApplyResult(error = current.error.ifBlank { "tool_unavailable" })
    return saveAndApplySusfsConfig(current.copy(paths = paths))
}

suspend fun saveAndApplySusfsConfig(config: SusfsPathConfigState): SusfsPathApplyResult = withContext(Dispatchers.IO) {
    if (shouldSkipUnsafeKsudCommand()) {
        return@withContext SusfsPathApplyResult(error = "root_unavailable")
    }
    if (Natives.isLateLoadMode || Natives.isLkmMode) {
        return@withContext SusfsPathApplyResult(error = "gki_mode_required")
    }
    validateSusfsConfig(config).takeIf(String::isNotEmpty)?.let { error ->
        return@withContext SusfsPathApplyResult(error = error)
    }
    val previous = getSusfsPathConfig()
    if (!previous.available || !previous.capabilities.supportsAddSusPath) {
        return@withContext SusfsPathApplyResult(error = previous.error.ifBlank { "tool_unavailable" })
    }
    if (config.loopPaths.isNotEmpty() && !previous.capabilities.supportsPathLoop) {
        return@withContext SusfsPathApplyResult(error = "path_loop_unavailable")
    }
    if (config.susMaps.isNotEmpty() && !previous.capabilities.supportsSusMap) {
        return@withContext SusfsPathApplyResult(error = "sus_map_unavailable")
    }
    if (config.openRedirects.isNotEmpty() && !previous.capabilities.supportsOpenRedirect) {
        return@withContext SusfsPathApplyResult(error = "open_redirect_unavailable")
    }
    if (config.kstatEntries.isNotEmpty() && !previous.capabilities.supportsKstat) {
        return@withContext SusfsPathApplyResult(error = "sus_kstat_unavailable")
    }
    if (config.cmdlineOrBootconfig.isNotBlank() && !previous.capabilities.supportsCmdlineSpoof) {
        return@withContext SusfsPathApplyResult(error = "cmdline_unavailable")
    }
    if (
        (config.unameRelease.isNotBlank() || config.unameVersion.isNotBlank()) &&
        !previous.capabilities.supportsUnameSpoof
    ) {
        return@withContext SusfsPathApplyResult(error = "uname_unavailable")
    }
    if (config.avcLogSpoofing && !previous.capabilities.supportsAvcLogSpoofing) {
        return@withContext SusfsPathApplyResult(error = "avc_spoofing_unavailable")
    }
    if (config.hideSusMntsForNonSuProcs && !previous.capabilities.supportsHideSusMounts) {
        return@withContext SusfsPathApplyResult(error = "hide_mounts_unavailable")
    }
    val normalized = config.copy(
        paths = config.paths.mapNotNull(::normalizeSusfsPath).distinct(),
        loopPaths = config.loopPaths.mapNotNull(::normalizeSusfsPath).distinct(),
        susMaps = config.susMaps.mapNotNull(::normalizeSusfsMapPath).distinct(),
        openRedirects = config.openRedirects.map {
            it.copy(
                originalPath = requireNotNull(normalizeSusfsPath(it.originalPath)),
                redirectedPath = requireNotNull(normalizeSusfsPath(it.redirectedPath)),
                uidScheme = it.uidScheme.toIntOrNull()?.toString() ?: "3",
            )
        }.distinct(),
        kstatEntries = config.kstatEntries.distinct(),
        unameRelease = config.unameRelease.trim(),
        unameVersion = config.unameVersion.trim(),
        cmdlineOrBootconfig = config.cmdlineOrBootconfig.trim(),
    )
    val generation = UUID.randomUUID().toString()
    val requiresReboot = susfsRequiresReboot(previous, normalized)
    val settingsText = buildString {
        appendLine("enabled=${if (normalized.enabled) 1 else 0}")
        appendLine("logging=${if (normalized.logging) 1 else 0}")
        appendLine("avc_log_spoofing=${if (normalized.avcLogSpoofing) 1 else 0}")
        appendLine("hide_sus_mnts_for_non_su_procs=${if (normalized.hideSusMntsForNonSuProcs) 1 else 0}")
        appendLine("uname_release=${normalized.unameRelease}")
        appendLine("uname_version=${normalized.unameVersion}")
        appendLine("cmdline_or_bootconfig=${normalized.cmdlineOrBootconfig}")
        appendLine("generation=$generation")
        appendLine("requires_reboot=${if (requiresReboot) 1 else 0}")
    }
    val fileValues = linkedMapOf(
        "paths.txt" to susfsLineListText(normalized.paths),
        "path_loop.txt" to susfsLineListText(normalized.loopPaths),
        "sus_maps.txt" to susfsLineListText(normalized.susMaps),
        "open_redirect.txt" to susfsLineListText(
            normalized.openRedirects.map { "${it.originalPath}|${it.redirectedPath}|${it.uidScheme}" },
        ),
        "sus_kstat_statically.txt" to susfsLineListText(normalized.kstatEntries.map { it.arguments.joinToString("|") }),
        "settings.conf" to settingsText,
    )
    val serviceScript = susfsPathServiceScript()
    val serviceBase64 = Base64.encodeToString(serviceScript.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    val stdout = ArrayList<String>()
    val stderr = ArrayList<String>()
    val pendingService = "$SUSFS_PATH_SERVICE_FILE.pending"
    val pendingGeneration = "$SUSFS_GENERATIONS_DIR/.pending-$generation"
    val finalGeneration = "$SUSFS_GENERATIONS_DIR/$generation"
    val pendingCurrent = "$SUSFS_PATH_CONFIG_DIR/current.pending"
    val command = buildString {
        appendLine("set -e")
        appendLine("mkdir -p ${shellQuote(SUSFS_PATH_CONFIG_DIR)} ${shellQuote(SUSFS_GENERATIONS_DIR)} /data/adb/service.d")
        appendLine("rm -rf ${shellQuote(pendingGeneration)}")
        appendLine("mkdir -m 0700 ${shellQuote(pendingGeneration)}")
        appendLine("trap 'rm -rf ${shellQuote(pendingGeneration)}; rm -f ${shellQuote(pendingCurrent)} ${shellQuote(pendingService)}' EXIT")
        fileValues.forEach { (name, value) ->
            appendLine(atomicWriteCommand("$pendingGeneration/$name", value))
        }
        appendLine(
            "printf '%s' ${shellQuote(serviceBase64)} | $BUSYBOX base64 -d > " +
                shellQuote(pendingService)
        )
        appendLine("chmod 0700 ${shellQuote(pendingService)}")
        appendLine("chown 0:0 ${shellQuote(pendingService)}")
        appendLine("mv -f ${shellQuote(pendingService)} ${shellQuote(SUSFS_PATH_SERVICE_FILE)}")
        appendLine("chown -R 0:0 ${shellQuote(pendingGeneration)}")
        appendLine("mv -f ${shellQuote(pendingGeneration)} ${shellQuote(finalGeneration)}")
        appendLine("rm -f ${shellQuote(pendingCurrent)}")
        appendLine("ln -s ${shellQuote("generations/$generation")} ${shellQuote(pendingCurrent)}")
        appendLine("if [ -e ${shellQuote(SUSFS_CURRENT_CONFIG_DIR)} ] && [ ! -L ${shellQuote(SUSFS_CURRENT_CONFIG_DIR)} ]; then")
        appendLine("  mv ${shellQuote(SUSFS_CURRENT_CONFIG_DIR)} ${shellQuote("$SUSFS_GENERATIONS_DIR/legacy-current-$generation")}")
        appendLine("fi")
        // BusyBox mv -T replaces the symlink itself instead of treating the
        // existing directory target as the destination directory.
        appendLine("$BUSYBOX mv -fT ${shellQuote(pendingCurrent)} ${shellQuote(SUSFS_CURRENT_CONFIG_DIR)}")
        appendLine("ls -1dt ${shellQuote(SUSFS_GENERATIONS_DIR)}/* 2>/dev/null | tail -n +4 | while IFS= read -r old; do rm -rf \"${'$'}old\"; done")
        appendLine("set +e")
        appendLine("/system/bin/sh ${shellQuote(SUSFS_PATH_SERVICE_FILE)} --immediate")
        appendLine("apply_rc=${'$'}?")
        appendLine("set -e")
        appendLine("printf '__APPLY_RC__=%s\\n' \"${'$'}apply_rc\"")
    }
    suspend fun resultFromCurrentState(fallbackError: String): SusfsPathApplyResult {
        val refreshed = getSusfsPathConfig()
        val status = refreshed.runtimeStatus
        val stateMatches = status.generation == generation
        val applied = stateMatches && isSusfsApplyStateSuccessful(status.state)
        return SusfsPathApplyResult(
            success = applied,
            saved = stateMatches,
            appliedCount = if (stateMatches) status.appliedCount else 0,
            skippedCount = if (stateMatches) status.skippedCount else 0,
            failedCount = if (stateMatches) status.failedCount else 0,
            requiresReboot = requiresReboot || (stateMatches && status.requiresReboot),
            generation = generation,
            issues = if (stateMatches) status.issues else emptyList(),
            error = when {
                applied -> ""
                stateMatches && status.state != "pending" -> "apply_${status.state}"
                else -> fallbackError
            },
        )
    }

    var executionError = ""
    val result = runCatching {
        withTimeoutOrNull(SUSFS_PATH_APPLY_TIMEOUT_MILLIS) {
            getRootShell().newJob().add(command).to(stdout, stderr).exec()
        }
    }.getOrElse { error ->
        executionError = error.message.orEmpty().ifBlank { "shell_failed" }
        null
    }
    if (result == null) {
        KsuCli.reset()
        return@withContext resultFromCurrentState(executionError.ifBlank { "timeout" })
    }
    if (!result.isSuccess) {
        return@withContext resultFromCurrentState(
            stderr.joinToString("\n").trim().ifBlank {
                stdout.joinToString("\n").trim().ifBlank { "apply_failed" }
            },
        )
    }
    resultFromCurrentState("apply_status_unavailable")
}

internal fun susfsPathServiceScript(): String = """#!/system/bin/sh
CONFIG=$SUSFS_PATH_CONFIG_FILE
LOOP_CONFIG=$SUSFS_PATH_LOOP_CONFIG_FILE
MAP_CONFIG=$SUSFS_MAP_CONFIG_FILE
REDIRECT_CONFIG=$SUSFS_OPEN_REDIRECT_CONFIG_FILE
KSTAT_CONFIG=$SUSFS_KSTAT_CONFIG_FILE
SETTINGS=$SUSFS_SETTINGS_CONFIG_FILE
CURRENT=$SUSFS_CURRENT_CONFIG_DIR
STATUS=$SUSFS_RUNTIME_STATUS_FILE
ISSUES=$SUSFS_RUNTIME_ISSUES_FILE
STARTED_AT=${'$'}(date +%s 2>/dev/null || echo 0)
CONFIG_DIR=$SUSFS_PATH_CONFIG_DIR
LOG_DIR=$SUSFS_PATH_CONFIG_DIR/logs
LOG_FILE=${'$'}LOG_DIR/runtime.log
TOOL=
TOOL_VERSION=
SUSFS_FEATURES=
FEATURE_PROBE_OK=0
PROBED=0
PROBE_SUPPORTED=0
EXTERNAL_ROOTS_PREPARED=0
MODE=${'$'}{1:-boot}
MAX_ATTEMPTS=30
STORAGE_ATTEMPTS=100
if [ "${'$'}MODE" = "--immediate" ]; then
    MAX_ATTEMPTS=1
    STORAGE_ATTEMPTS=2
fi

mkdir -p "${'$'}LOG_DIR" 2>/dev/null || true
if [ -f "${'$'}LOG_FILE" ] && [ ${'$'}(wc -c < "${'$'}LOG_FILE" 2>/dev/null) -gt 131072 ]; then
    rm -f "${'$'}LOG_FILE.2" 2>/dev/null || true
    mv -f "${'$'}LOG_FILE.1" "${'$'}LOG_FILE.2" 2>/dev/null || true
    mv -f "${'$'}LOG_FILE" "${'$'}LOG_FILE.1" 2>/dev/null || true
fi
log() {
    printf '%s %s\n' "${'$'}(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null)" "${'$'}*" >> "${'$'}LOG_FILE" 2>/dev/null || true
}
read_setting() {
    [ -f "${'$'}SETTINGS" ] || return 0
    sed -n "s/^${'$'}1=//p" "${'$'}SETTINGS" 2>/dev/null | sed -n '1p'
}
resolve_config() {
    resolved=${'$'}(readlink -f "${'$'}CURRENT" 2>/dev/null || true)
    case "${'$'}resolved" in
        "$SUSFS_PATH_CONFIG_DIR"/generations/*) CONFIG_DIR="${'$'}resolved" ;;
        *) CONFIG_DIR="$SUSFS_PATH_CONFIG_DIR" ;;
    esac
    CONFIG="${'$'}CONFIG_DIR/paths.txt"
    LOOP_CONFIG="${'$'}CONFIG_DIR/path_loop.txt"
    MAP_CONFIG="${'$'}CONFIG_DIR/sus_maps.txt"
    REDIRECT_CONFIG="${'$'}CONFIG_DIR/open_redirect.txt"
    KSTAT_CONFIG="${'$'}CONFIG_DIR/sus_kstat_statically.txt"
    SETTINGS="${'$'}CONFIG_DIR/settings.conf"
}
record_issue() {
    printf '%s\t%s\t%s\t%s\n' "${'$'}1" "${'$'}2" "${'$'}3" "${'$'}4" >> "${'$'}ISSUES_PENDING"
}
generation_is_current() {
    [ -n "${'$'}RUN_GENERATION" ] || return 0
    active_resolved=${'$'}(readlink -f "${'$'}CURRENT" 2>/dev/null || true)
    case "${'$'}active_resolved" in
        "$SUSFS_PATH_CONFIG_DIR"/generations/*) active_settings="${'$'}active_resolved/settings.conf" ;;
        *) active_settings="$SUSFS_PATH_CONFIG_DIR/settings.conf" ;;
    esac
    active_generation=${'$'}(sed -n 's/^generation=//p' "${'$'}active_settings" 2>/dev/null | sed -n '1p')
    [ "${'$'}active_generation" = "${'$'}RUN_GENERATION" ]
}
write_status() {
    if ! generation_is_current; then
        rm -f "${'$'}ISSUES_PENDING" 2>/dev/null || true
        log "discarded stale status for generation ${'$'}RUN_GENERATION"
        return 0
    fi
    status_tmp="${'$'}STATUS.pending.${'$'}${'$'}"
    {
        printf 'generation=%s\n' "${'$'}RUN_GENERATION"
        printf 'state=%s\n' "${'$'}1"
        printf 'configured_count=%s\n' "${'$'}CONFIGURED_COUNT"
        printf 'applied_count=%s\n' "${'$'}APPLIED_COUNT"
        printf 'skipped_count=%s\n' "${'$'}SKIPPED_COUNT"
        printf 'failed_count=%s\n' "${'$'}FAILED_COUNT"
        printf 'requires_reboot=%s\n' "${'$'}REQUIRES_REBOOT"
        printf 'started_at=%s\n' "${'$'}STARTED_AT"
        printf 'finished_at=%s\n' "${'$'}2"
        printf 'tool=%s\n' "${'$'}TOOL"
        printf 'version=%s\n' "${'$'}TOOL_VERSION"
    } > "${'$'}status_tmp"
    chmod 0600 "${'$'}status_tmp" "${'$'}ISSUES_PENDING" 2>/dev/null || true
    mv -f "${'$'}status_tmp" "${'$'}STATUS"
    mv -f "${'$'}ISSUES_PENDING" "${'$'}ISSUES"
}
run_tool() {
    category="${'$'}1"
    target="${'$'}2"
    shift 2
    output_file="$SUSFS_PATH_CONFIG_DIR/.last-command-output"
    generation_is_current || return 125
    CONFIGURED_COUNT=${'$'}((CONFIGURED_COUNT + 1))
    "${'$'}TOOL" "${'$'}@" > "${'$'}output_file" 2>&1
    code=${'$'}?
    if [ "${'$'}code" -eq 0 ]; then
        APPLIED_COUNT=${'$'}((APPLIED_COUNT + 1))
        return 0
    fi
    FAILED_COUNT=${'$'}((FAILED_COUNT + 1))
    record_issue failed "${'$'}category" "${'$'}target" "exit_${'$'}code"
    log "${'$'}category failed: ${'$'}target"
    return 1
}
skip_entry() {
    CONFIGURED_COUNT=${'$'}((CONFIGURED_COUNT + 1))
    SKIPPED_COUNT=${'$'}((SKIPPED_COUNT + 1))
    record_issue skipped "${'$'}1" "${'$'}2" "${'$'}3"
}
feature_enabled() {
    [ "${'$'}FEATURE_PROBE_OK" -eq 1 ] && printf '%s\n' "${'$'}SUSFS_FEATURES" | grep -qF "${'$'}1"
}
version_code() {
    version_triplet=${'$'}(printf '%s\n' "${'$'}TOOL_VERSION" | sed -n 's/^[^0-9]*\([0-9][0-9]*\)\.\([0-9][0-9]*\)\.\([0-9][0-9]*\).*/\1 \2 \3/p')
    [ -n "${'$'}version_triplet" ] || return 1
    set -- ${'$'}version_triplet
    printf '%s\n' ${'$'}(( ${'$'}1 * 10000 + ${'$'}2 * 100 + ${'$'}3 ))
}
supports_version() {
    current=${'$'}(version_code 2>/dev/null) || return 1
    [ "${'$'}current" -ge "${'$'}1" ]
}
is_trusted_tool() {
    candidate="${'$'}1"
    [ -f "${'$'}candidate" ] && [ -x "${'$'}candidate" ] && [ ! -L "${'$'}candidate" ] || return 1
    owner=${'$'}(stat -c %u "${'$'}candidate" 2>/dev/null) || return 1
    mode=${'$'}(stat -c %a "${'$'}candidate" 2>/dev/null) || return 1
    [ "${'$'}owner" = 0 ] || return 1
    case "${'$'}mode" in [0-7][0-7][0-7]|[0-7][0-7][0-7][0-7]) ;; *) return 1 ;; esac
    [ ${'$'}((0${'$'}mode & 022)) -eq 0 ]
}
find_tool() {
    for candidate in /data/adb/ksu/bin/ksu_susfs /data/adb/ap/bin/ksu_susfs /system/bin/ksu_susfs; do
        if is_trusted_tool "${'$'}candidate"; then
            TOOL="${'$'}candidate"
            break
        fi
    done
    [ -x "${'$'}TOOL" ]
}
probe_tool() {
    PROBED=1
    PROBE_SUPPORTED=0
    TOOL_VERSION=${'$'}("${'$'}TOOL" show version 2>/dev/null | sed -n '1p')
    if SUSFS_FEATURES=${'$'}("${'$'}TOOL" show enabled_features 2>/dev/null) &&
        printf '%s\n' "${'$'}SUSFS_FEATURES" | grep -q 'CONFIG_KSU_SUSFS_'; then
        FEATURE_PROBE_OK=1
    else
        FEATURE_PROBE_OK=0
        SUSFS_FEATURES=
    fi
    if [ "${'$'}FEATURE_PROBE_OK" -eq 1 ] && ! feature_enabled "$SUSFS_PATH_FEATURE"; then
        log "path feature is not reported"
        return 1
    fi
    PROBE_SUPPORTED=1
    return 0
}
needs_external_storage_roots() {
    for file in "${'$'}CONFIG" "${'$'}LOOP_CONFIG"; do
        [ -f "${'$'}file" ] || continue
        while IFS= read -r target_path; do
            case "${'$'}target_path" in
                /sdcard|/sdcard/*|/storage/emulated|/storage/emulated/*|/storage/self/primary|/storage/self/primary/*) return 0 ;;
            esac
        done < "${'$'}file"
    done
    return 1
}
prepare_external_storage_roots() {
    [ "${'$'}EXTERNAL_ROOTS_PREPARED" -eq 1 ] && return 0
    EXTERNAL_ROOTS_PREPARED=1
    needs_external_storage_roots || return 0
    version_code=${'$'}(version_code 2>/dev/null || echo 0)
    [ "${'$'}version_code" -ge 10508 ] && [ "${'$'}version_code" -lt 20100 ] || return 0
    storage_attempt=0
    while [ "${'$'}storage_attempt" -lt "${'$'}STORAGE_ATTEMPTS" ] && [ ! -d /sdcard/Android/data ]; do
        storage_attempt=${'$'}((storage_attempt + 1))
        sleep 1
    done
    "${'$'}TOOL" set_sdcard_root_path /sdcard >/dev/null 2>&1 || log "sdcard root unavailable"
    "${'$'}TOOL" set_android_data_root_path /sdcard/Android/data >/dev/null 2>&1 || log "android data root unavailable"
}
apply_path_file() {
    file="${'$'}1"
    command="${'$'}2"
    category="${'$'}3"
    supported="${'$'}4"
    [ -f "${'$'}file" ] || return 0
    failed=0
    while IFS= read -r target_path; do
        case "${'$'}target_path" in
            ""|\#*) continue ;;
            /*)
                if [ "${'$'}supported" = "1" ]; then
                    run_tool "${'$'}category" "${'$'}target_path" "${'$'}command" "${'$'}target_path" || failed=1
                else
                    skip_entry "${'$'}category" "${'$'}target_path" unsupported
                fi
                ;;
            *)
                failed=1
                CONFIGURED_COUNT=${'$'}((CONFIGURED_COUNT + 1))
                FAILED_COUNT=${'$'}((FAILED_COUNT + 1))
                record_issue failed "${'$'}category" "${'$'}target_path" invalid_path
                ;;
        esac
    done < "${'$'}file"
    return "${'$'}failed"
}
apply_maps() {
    [ -f "${'$'}MAP_CONFIG" ] || return 0
    supported=0
    feature_enabled "$SUSFS_MAP_FEATURE" && supported=1
    while IFS= read -r target_path; do
        case "${'$'}target_path" in
            ""|\#*) continue ;;
            /*)
                if [ "${'$'}supported" -eq 1 ]; then
                    run_tool map "${'$'}target_path" add_sus_map "${'$'}target_path" || true
                else
                    skip_entry map "${'$'}target_path" unsupported
                fi
                ;;
        esac
    done < "${'$'}MAP_CONFIG"
}
apply_redirects() {
    [ -f "${'$'}REDIRECT_CONFIG" ] || return 0
    supported=0
    feature_enabled "$SUSFS_OPEN_REDIRECT_FEATURE" && supported=1
    while IFS='|' read -r original redirected uid_scheme; do
        [ -n "${'$'}original" ] || continue
        if [ "${'$'}supported" -eq 1 ]; then
            run_tool redirect "${'$'}original -> ${'$'}redirected" add_open_redirect "${'$'}original" "${'$'}redirected" "${'$'}uid_scheme" || true
        else
            skip_entry redirect "${'$'}original -> ${'$'}redirected" unsupported
        fi
    done < "${'$'}REDIRECT_CONFIG"
}
apply_kstats() {
    [ -f "${'$'}KSTAT_CONFIG" ] || return 0
    supported=0
    if feature_enabled "$SUSFS_KSTAT_FEATURE" || { [ "${'$'}FEATURE_PROBE_OK" -eq 0 ] && supports_version 20000; }; then
        supported=1
    fi
    while IFS='|' read -r a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13; do
        [ -n "${'$'}a1" ] || continue
        if [ "${'$'}supported" -eq 1 ]; then
            run_tool kstat "${'$'}a1" add_sus_kstat_statically "${'$'}a1" "${'$'}a2" "${'$'}a3" "${'$'}a4" "${'$'}a5" "${'$'}a6" "${'$'}a7" "${'$'}a8" "${'$'}a9" "${'$'}a10" "${'$'}a11" "${'$'}a12" "${'$'}a13" || true
        else
            skip_entry kstat "${'$'}a1" unsupported
        fi
    done < "${'$'}KSTAT_CONFIG"
}
apply_settings() {
    enabled=${'$'}(read_setting enabled)
    [ -n "${'$'}enabled" ] || enabled=1
    [ "${'$'}enabled" = "1" ] || return 0
    logging=${'$'}(read_setting logging)
    logging_value=0
    [ "${'$'}logging" = "1" ] && logging_value=1
    if feature_enabled "$SUSFS_LOG_FEATURE" || { [ "${'$'}FEATURE_PROBE_OK" -eq 0 ] && supports_version 10500; }; then
        run_tool logging "${'$'}logging_value" enable_log "${'$'}logging_value" || true
    elif [ "${'$'}logging_value" -eq 1 ]; then
        skip_entry logging "${'$'}logging_value" unsupported
    fi
    version_value=${'$'}(version_code 2>/dev/null || echo 0)
    if [ "${'$'}version_value" -ge 10503 ]; then
        avc=${'$'}(read_setting avc_log_spoofing)
        avc_value=0
        [ "${'$'}avc" = "1" ] && avc_value=1
        run_tool avc "${'$'}avc_value" enable_avc_log_spoofing "${'$'}avc_value" || true
    fi
    release=${'$'}(read_setting uname_release)
    build=${'$'}(read_setting uname_version)
    if [ -n "${'$'}release" ] || [ -n "${'$'}build" ]; then
        if feature_enabled "$SUSFS_UNAME_FEATURE" || { [ "${'$'}FEATURE_PROBE_OK" -eq 0 ] && supports_version 10500; }; then
            run_tool uname "${'$'}release | ${'$'}build" set_uname "${'$'}{release:-default}" "${'$'}{build:-default}" || true
        else
            skip_entry uname "${'$'}release | ${'$'}build" unsupported
        fi
    fi
    cmdline=${'$'}(read_setting cmdline_or_bootconfig)
    if [ -n "${'$'}cmdline" ] && [ -f "${'$'}cmdline" ]; then
        if feature_enabled "$SUSFS_CMDLINE_FEATURE" || { [ "${'$'}FEATURE_PROBE_OK" -eq 0 ] && supports_version 10504; }; then
            run_tool cmdline "${'$'}cmdline" set_cmdline_or_bootconfig "${'$'}cmdline" || true
        else
            skip_entry cmdline "${'$'}cmdline" unsupported
        fi
    elif [ -n "${'$'}cmdline" ]; then
        CONFIGURED_COUNT=${'$'}((CONFIGURED_COUNT + 1))
        FAILED_COUNT=${'$'}((FAILED_COUNT + 1))
        record_issue failed cmdline "${'$'}cmdline" not_found
    fi
    hide_non=${'$'}(read_setting hide_sus_mnts_for_non_su_procs)
    if feature_enabled "$SUSFS_MOUNT_FEATURE" || { [ "${'$'}FEATURE_PROBE_OK" -eq 0 ] && supports_version 10507; }; then
        hide_value=0
        [ "${'$'}hide_non" = "1" ] && hide_value=1
        run_tool mount_visibility "${'$'}hide_value" hide_sus_mnts_for_non_su_procs "${'$'}hide_value" || true
    fi
}

resolve_config
RUN_GENERATION=${'$'}(read_setting generation)
ISSUES_PENDING="${'$'}ISSUES.pending.${'$'}{RUN_GENERATION:-legacy}.${'$'}${'$'}"
: > "${'$'}ISSUES_PENDING"
REQUIRES_REBOOT=${'$'}(read_setting requires_reboot)
[ "${'$'}REQUIRES_REBOOT" = "1" ] || REQUIRES_REBOOT=0
[ "${'$'}MODE" = "--immediate" ] || REQUIRES_REBOOT=0
CONFIGURED_COUNT=0
APPLIED_COUNT=0
SKIPPED_COUNT=0
FAILED_COUNT=0
attempt=0
while [ "${'$'}attempt" -lt "${'$'}MAX_ATTEMPTS" ]; do
    [ -f "${'$'}SETTINGS" ] || [ -f "${'$'}CONFIG" ] || [ -f "${'$'}LOOP_CONFIG" ] || exit 0
    enabled=${'$'}(read_setting enabled)
    [ -z "${'$'}enabled" ] && enabled=1
    if [ "${'$'}enabled" != "1" ]; then
        write_status disabled "${'$'}(date +%s 2>/dev/null || echo 0)"
        exit 0
    fi
    if [ -z "${'$'}TOOL" ]; then find_tool || true; fi
    if [ -n "${'$'}TOOL" ] && probe_tool; then
        generation_is_current || exit 0
        prepare_external_storage_roots
        failed=0
        apply_path_file "${'$'}CONFIG" add_sus_path path 1 || failed=1
        if feature_enabled "$SUSFS_PATH_FEATURE" || supports_version 10509; then
            apply_path_file "${'$'}LOOP_CONFIG" add_sus_path_loop loop 1 || failed=1
        else
            apply_path_file "${'$'}LOOP_CONFIG" add_sus_path_loop loop 0 || failed=1
        fi
        apply_maps
        apply_kstats
        apply_redirects
        apply_settings
        if [ "${'$'}FAILED_COUNT" -gt 0 ] || [ "${'$'}SKIPPED_COUNT" -gt 0 ] || [ "${'$'}failed" -ne 0 ]; then
            if [ "${'$'}APPLIED_COUNT" -gt 0 ]; then final_state=partial; else final_state=failed; fi
            write_status "${'$'}final_state" "${'$'}(date +%s 2>/dev/null || echo 0)"
            exit 0
        fi
        write_status "${'$'}([ "${'$'}REQUIRES_REBOOT" -eq 1 ] && echo reboot_pending || echo applied)" "${'$'}(date +%s 2>/dev/null || echo 0)"
        log "SUSFS configuration applied"
        exit 0
    else
        log "SUSFS runtime unavailable"
    fi
    attempt=${'$'}((attempt + 1))
    sleep 1
done
FAILED_COUNT=${'$'}((FAILED_COUNT + 1))
record_issue failed runtime "${'$'}TOOL" unavailable
write_status failed "${'$'}(date +%s 2>/dev/null || echo 0)"
log "SUSFS configuration timed out after ${'$'}MAX_ATTEMPTS attempt(s)"
exit 0
"""

suspend fun getHiddenPathLogs(): String = withContext(Dispatchers.IO) {
    if (shouldSkipUnsafeKsudCommand()) {
        return@withContext ""
    }

    runCatching {
        val stdout = ArrayList<String>()
        val stderr = ArrayList<String>()
        val result = withTimeoutOrNull(SHELL_JOB_TIMEOUT_MILLIS) {
            getRootShell().newJob()
                .add("${getKsuDaemonPath()} pathmask logs")
                .to(stdout, stderr)
                .exec()
        }

        if (result == null) {
            KsuCli.reset()
            return@runCatching ""
        }
        if (result.isSuccess) stdout.joinToString("\n") else stderr.joinToString("\n")
    }.getOrElse {
        Log.w(TAG, "pathmask logs unavailable", it)
        KsuCli.reset()
        ""
    }
}

suspend fun clearHiddenPathLogs(): ToolCommandResult = runStructuredKsudCommand(
    area = "pathmask",
    command = "pathmask clear-logs",
)

suspend fun testHiddenPathVisibility(uid: Int, path: String): HiddenPathVisibilityResult =
    withContext(Dispatchers.IO) {
        if (uid < 0 || path.isBlank()) {
            return@withContext HiddenPathVisibilityResult(error = "invalid UID or path")
        }
        if (shouldSkipUnsafeKsudCommand()) {
            return@withContext HiddenPathVisibilityResult(error = "root shell unavailable")
        }

        runCatching {
            val stdout = ArrayList<String>()
            val stderr = ArrayList<String>()
            val command = "${shellQuote(getKsuDaemonPath())} pathmask test-visibility " +
                "--uid $uid --path ${shellQuote(path)}"
            val result = withTimeoutOrNull(SHELL_JOB_TIMEOUT_MILLIS) {
                getRootShell().newJob()
                    .add(command)
                    .to(stdout, stderr)
                    .exec()
            }
            if (result == null) {
                KsuCli.reset()
                return@runCatching HiddenPathVisibilityResult(
                    uid = uid,
                    path = path,
                    error = "visibility probe timed out",
                )
            }
            if (!result.isSuccess) {
                return@runCatching HiddenPathVisibilityResult(
                    uid = uid,
                    path = path,
                    error = stderr.joinToString("\n").ifBlank { "visibility probe failed" },
                )
            }

            val obj = JSONObject(stdout.joinToString("\n"))
            HiddenPathVisibilityResult(
                uid = obj.optInt("uid", uid),
                path = obj.optString("path", path),
                status = obj.optString("status", "probe_failed"),
                visible = obj.optBoolean("visible", false),
                rootExists = obj.optBoolean("rootExists", false),
                moduleLoaded = obj.optBoolean("moduleLoaded", false),
                resolvedCount = obj.optString("resolvedCount", ""),
                error = obj.optString("error", ""),
            )
        }.getOrElse {
            Log.w(TAG, "pathmask visibility probe unavailable", it)
            KsuCli.reset()
            HiddenPathVisibilityResult(uid = uid, path = path, error = it.message.orEmpty())
        }
    }

suspend fun getRescueStatus(): RescueStatus = withContext(Dispatchers.IO) {
    if (shouldSkipUnsafeKsudCommand()) {
        return@withContext RescueStatus(
            statusErrorCode = "rescue.root_unavailable",
            statusError = "ksud command unavailable",
        )
    }

    runCatching {
        val stdout = ArrayList<String>()
        val stderr = ArrayList<String>()
        val result = withTimeoutOrNull(STATUS_TIMEOUT_MILLIS) {
            getRootShell().newJob()
                .add("${getKsuDaemonPath()} rescue status")
                .to(stdout, stderr)
                .exec()
        }

        if (result == null) {
            Log.w(TAG, "rescue status timed out")
            KsuCli.reset()
            return@runCatching RescueStatus(
                statusErrorCode = "rescue.timeout",
                statusError = "rescue status timed out",
            )
        }

        if (!result.isSuccess) {
            val error = stderr.joinToString("\n").ifBlank { "rescue status command failed" }
            Log.w(TAG, "rescue status failed: $error")
            val structured = parseStructuredKsudError(error, "rescue.status_failed")
            return@runCatching RescueStatus(
                statusErrorCode = structured.errorCode,
                statusError = structured.errorMessage,
            )
        }

        val obj = JSONObject(stdout.joinToString("\n"))
        val manifest = obj.optJSONObject("manifest")
        val device = obj.optJSONObject("device")
        val manifestDevice = manifest?.optJSONObject("device")
        val images = obj.optJSONArray("images").toRescueImageList()
        RescueStatus(
            available = obj.optBoolean("statusOk", true),
            phase = obj.optString("phase", "unavailable"),
            statusErrorCode = obj.optString("statusErrorCode", ""),
            statusError = obj.optString("statusError", ""),
            enabled = obj.optBoolean("enabled", false),
            config = obj.optJSONObject("config").toRescueConfigState(),
            images = images,
            bootCount = obj.optInt("bootCount", 0),
            autoRestoreAttempts = obj.optInt("autoRestoreAttempts", 0),
            pendingBoot = obj.optBoolean("pendingBoot", false),
            pendingImageBoot = obj.optBoolean("pendingImageBoot", false),
            restorePendingBoot = obj.optBoolean("restorePendingBoot", false),
            restoreBootState = obj.optString("restoreBootState", "none"),
            currentSlot = obj.optString("currentSlot", ""),
            bootMode = obj.optString("bootMode", ""),
            device = listOf(
                device?.optString("brand", "").orEmpty(),
                device?.optString("model", "").orEmpty(),
            ).filter(String::isNotBlank).joinToString(" "),
            deviceFingerprint = device?.optString("fingerprint", "").orEmpty(),
            manifestCreatedAt = manifest?.optString("createdAt", "").orEmpty(),
            manifestSlot = manifest?.optString("slot", "").orEmpty(),
            manifestDevice = listOf(
                manifestDevice?.optString("brand", "").orEmpty(),
                manifestDevice?.optString("model", "").orEmpty(),
                manifestDevice?.optString("device", "").orEmpty(),
            ).filter(String::isNotBlank).distinct().joinToString(" "),
            manifestFingerprint = manifestDevice?.optString("fingerprint", "").orEmpty(),
            manifestTotalSize = images.filter(RescueImageState::exists).sumOf(RescueImageState::size),
            lastRestoreDone = obj.optBoolean("lastRestoreDone", false),
            skipModulesOnce = obj.optBoolean("skipModulesOnce", false),
            skipModulesThisBoot = obj.optBoolean("skipModulesThisBoot", false),
            ready = obj.optBoolean("ready", false),
            readyReason = obj.optString("readyReason", ""),
            verified = obj.optBoolean("verified", false),
            environmentChecked = obj.optBoolean("environmentChecked", false),
            configChangedProtectionDisabled = obj.optBoolean("configChangedProtectionDisabled", false),
            restoreInterrupted = obj.optBoolean("restoreInterrupted", false),
            restoreTransactionError = obj.optString("restoreTransactionError", ""),
            restoreTransaction = obj.optJSONObject("restoreTransaction").toRescueRestoreTransaction(),
            rescueDisabledModules = obj.optJSONArray("rescueDisabledModules").toRescueDisabledModules(),
            log = obj.optString("log", ""),
        )
    }.getOrElse {
        Log.w(TAG, "rescue status unavailable", it)
        KsuCli.reset()
        RescueStatus(
            statusErrorCode = "rescue.status_unavailable",
            statusError = it.message.orEmpty().ifBlank { "rescue status unavailable" },
        )
    }
}

suspend fun runRescueCommand(
    command: String,
    timeoutMultiplier: Long = 6,
): ToolCommandResult = runStructuredKsudCommand(
    area = "rescue",
    command = "rescue $command",
    timeoutMillis = SHELL_JOB_TIMEOUT_MILLIS * timeoutMultiplier.coerceAtLeast(1),
)

suspend fun saveRescueConfig(config: RescueConfigState): ToolCommandResult = runStructuredKsudCommand(
    area = "rescue",
    command = "rescue import-config-json ${shellQuote(config.toConfigJson())}",
    timeoutMillis = STATUS_TIMEOUT_MILLIS,
)

suspend fun importRescueImage(partition: String, sourcePath: String, force: Boolean): Boolean = withContext(Dispatchers.IO) {
    if (shouldSkipUnsafeKsudCommand()) {
        return@withContext false
    }

    runCatching {
        val stderr = ArrayList<String>()
        val forceArg = if (force) " --force" else ""
        val result = withTimeoutOrNull(SHELL_JOB_TIMEOUT_MILLIS * 30) {
            getRootShell().newJob()
                .add(
                    "${getKsuDaemonPath()} rescue import-image " +
                        "${shellQuote(partition)} ${shellQuote(sourcePath)}$forceArg"
                )
                .to(null, stderr)
                .exec()
        }
        if (result == null) {
            Log.w(TAG, "rescue image import timed out")
            KsuCli.reset()
            return@runCatching false
        }
        if (!result.isSuccess) {
            Log.w(TAG, "rescue image import failed: ${stderr.joinToString("\n")}")
        }
        result.isSuccess
    }.getOrElse {
        Log.w(TAG, "rescue image import unavailable", it)
        KsuCli.reset()
        false
    }
}

suspend fun testRescueEnvironment(): RescueTestReport = withContext(Dispatchers.IO) {
    if (shouldSkipUnsafeKsudCommand()) {
        return@withContext RescueTestReport(reason = "ksud command unavailable")
    }

    runCatching {
        val stdout = ArrayList<String>()
        val stderr = ArrayList<String>()
        val result = withTimeoutOrNull(STATUS_TIMEOUT_MILLIS) {
            getRootShell().newJob()
                .add("${shellQuote(getKsuDaemonPath())} rescue test")
                .to(stdout, stderr)
                .exec()
        }
        if (result == null) {
            KsuCli.reset()
            return@runCatching RescueTestReport(
                errorCode = "rescue.timeout",
                reason = "rescue environment check timed out",
            )
        }
        val raw = stdout.joinToString("\n")
        val obj = JSONObject(raw)
        RescueTestReport(
            ok = result.isSuccess && obj.optBoolean("ok", false),
            errorCode = obj.optString("errorCode", ""),
            reason = obj.optString("reason", stderr.joinToString("\n")),
            text = raw,
            backupReady = obj.optBoolean("backupReady", false),
            backupReason = obj.optString("backupReason", ""),
        )
    }.getOrElse {
        Log.w(TAG, "rescue test unavailable", it)
        KsuCli.reset()
        RescueTestReport(reason = it.message.orEmpty())
    }
}

suspend fun verifyRescueBackups(): RescueTestReport = withContext(Dispatchers.IO) {
    if (shouldSkipUnsafeKsudCommand()) {
        return@withContext RescueTestReport(
            errorCode = "rescue.root_unavailable",
            reason = "ksud command unavailable",
        )
    }
    runCatching {
        val stdout = ArrayList<String>()
        val stderr = ArrayList<String>()
        val result = withTimeoutOrNull(LONG_IO_TIMEOUT_MILLIS) {
            getRootShell().newJob()
                .add("${shellQuote(getKsuDaemonPath())} rescue verify")
                .to(stdout, stderr)
                .exec()
        }
        if (result == null) {
            KsuCli.reset()
            return@runCatching RescueTestReport(
                errorCode = "rescue.timeout",
                reason = "rescue verification timed out",
            )
        }
        val raw = stdout.joinToString("\n")
        val obj = runCatching { JSONObject(raw) }.getOrNull()
        RescueTestReport(
            ok = result.isSuccess && obj?.optBoolean("ok", false) == true,
            errorCode = obj?.optString("errorCode", "").orEmpty(),
            reason = obj?.optString("reason", stderr.joinToString("\n")).orEmpty(),
            text = raw.ifBlank { stderr.joinToString("\n") },
        )
    }.getOrElse { error ->
        Log.w(TAG, "rescue verification unavailable", error)
        KsuCli.reset()
        RescueTestReport(
            errorCode = "rescue.unavailable",
            reason = error.message.orEmpty(),
        )
    }
}

suspend fun getRescueLogs(): String = getKsudTextOutput(
    command = "rescue logs",
    timeoutMillis = DIAGNOSTIC_TIMEOUT_MILLIS,
)

suspend fun getRescueDiagnostics(): String = getKsudTextOutput(
    command = "rescue diagnostics",
    timeoutMillis = DIAGNOSTIC_TIMEOUT_MILLIS,
)

suspend fun enableRescueModule(id: String): ToolCommandResult = runStructuredKsudCommand(
    area = "rescue",
    command = "rescue enable-module ${shellQuote(id)}",
    timeoutMillis = STATUS_TIMEOUT_MILLIS,
)

fun isHiddenPathLkmMode(): Boolean {
    return runCatching {
        withNewRootShell(globalMnt = true) {
            newJob()
                .add("[ -f /pathmask.ko ] || grep -q '^pathmask ' /proc/modules")
                .exec()
                .isSuccess
        }
    }.getOrDefault(false)
}

suspend fun getFeatureStatus(feature: String): String = withContext(Dispatchers.IO) {
    if (shouldSkipUnsafeKsudCommand()) {
        return@withContext ""
    }

    val shell = getRootShell()
    val out = shell.newJob()
        .add("${getKsuDaemonPath()} feature check $feature").to(ArrayList<String>(), null).exec().out
    out.firstOrNull()?.trim().orEmpty()
}

suspend fun getFeaturePersistValue(feature: String): Long? = withContext(Dispatchers.IO) {
    if (shouldSkipUnsafeKsudCommand()) {
        return@withContext null
    }

    val shell = getRootShell()
    val out = shell.newJob()
        .add("${getKsuDaemonPath()} feature get --config $feature").to(ArrayList<String>(), null).exec().out
    val valueLine = out.firstOrNull { it.trim().startsWith("Value:") } ?: return@withContext null
    valueLine.substringAfter("Value:").trim().toLongOrNull()
}

fun install(): Boolean {
    val start = SystemClock.elapsedRealtime()
    val libadbroot = File(ksuApp.applicationInfo.nativeLibraryDir, "libadbroot.so").absolutePath
    val dataPath = ksuApp.applicationInfo.deviceProtectedDataDir
    val result = execKsud(
        "install --libadbroot ${shellQuote(libadbroot)} --data-path ${shellQuote(dataPath)}",
        true,
    )
    Log.w(TAG, "install result: $result, cost: ${SystemClock.elapsedRealtime() - start}ms")
    return result
}

fun listModules(): String {
    if (shouldSkipUnsafeKsudCommand()) {
        return "[]"
    }

    val shell = getRootShell()

    val result = shell.newJob()
        .add("${getKsuDaemonPath()} module list").to(ArrayList(), ArrayList()).exec()
    if (!result.isSuccess) {
        KsuCli.reset()
        Log.w(TAG, "module list failed: ${result.err.joinToString("\n")}")
        return "[]"
    }
    return result.out.joinToString("\n").ifBlank { "[]" }
}

suspend fun listModulesWithTimeout(timeoutMillis: Long = SHELL_JOB_TIMEOUT_MILLIS): String {
    if (shouldSkipUnsafeKsudCommand()) {
        return "[]"
    }

    val stdout = ArrayList<String>()
    val result = withTimeoutOrNull(timeoutMillis) {
        suspendCancellableCoroutine { cont ->
            val shell = getRootShell()
            shell.newJob()
                .add("${getKsuDaemonPath()} module list")
                .to(stdout, null)
                .submit(Shell.EXECUTOR) { result ->
                    if (cont.isActive) {
                        cont.resume(result)
                    }
                }
        }
    }

    if (result == null) {
        Log.w(TAG, "module list timed out after ${timeoutMillis}ms")
        KsuCli.reset()
        error("module list timed out after ${timeoutMillis}ms")
    }

    if (!result.isSuccess) {
        KsuCli.reset()
        error("module list failed: ${result.err.joinToString("\n")}")
    }

    return result.out.joinToString("\n").ifBlank { "[]" }
}

fun getModuleCount(): Int {
    val result = listModules()
    runCatching {
        val array = JSONArray(result)
        return (0 until array.length()).count { index ->
            val id = array.optJSONObject(index)?.optString("id").orEmpty()
            id.isNotBlank() && !isManagerHiddenModuleId(id)
        }
    }.getOrElse { return 0 }
}

fun getSuperuserCount(): Int {
    return Natives.getSuperuserCount()
}

fun toggleModule(id: String, enable: Boolean): Boolean {
    val cmd = if (enable) {
        "module enable ${shellQuote(id)}"
    } else {
        "module disable ${shellQuote(id)}"
    }
    val result = execKsud(cmd, true)
    Log.i(TAG, "$cmd result: $result")
    return result
}

fun undoUninstallModule(id: String): Boolean {
    val cmd = "module undo-uninstall ${shellQuote(id)}"
    val result = execKsud(cmd, true)
    Log.i(TAG, "undo uninstall module $id result: $result")
    return result
}

fun uninstallModule(id: String): Boolean {
    val cmd = "module uninstall ${shellQuote(id)}"
    val result = execKsud(cmd, true)
    Log.i(TAG, "uninstall module $id result: $result")
    return result
}

private fun flashWithIO(
    cmd: String,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): Shell.Result {

    val stdoutCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStdout(s ?: "")
        }
    }

    val stderrCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStderr(s ?: "")
        }
    }

    return withNewRootShell {
        newJob().add(cmd).to(stdoutCallback, stderrCallback).exec()
    }
}

private fun processUiPrintLine(s: String?): Pair<Int, String?> {
    if (s == null) {
        return Pair(1, null)
    }

    val check1 = s.startsWith("ui_print")
    val trimmed = s.trim()
    val check2 = trimmed.startsWith("ui_print")
    if (!check1 && check2) return Pair(1, null)

    return if (check1) {
        Pair(1, trimmed.drop(8).dropWhile { it.isWhitespace() })
    } else {
        Pair(2, trimmed)
    }
}

private fun flashWithIoAk3(
    cmd: String,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): Shell.Result {

    val stdoutCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            val (type, text) = processUiPrintLine(s)
            if (type == 1) {
                text?.let(onStdout)
            } else {
                text?.let(onStderr)
            }
        }
    }

    val stderrCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStderr(s ?: "")
        }
    }

    return withNewRootShell {
        newJob().add(cmd).to(stdoutCallback, stderrCallback).exec()
    }
}

private fun copyUriToCache(uri: Uri, fileName: String): File {
    val requestedName = File(fileName).name
    val baseName = requestedName.substringBeforeLast('.', requestedName)
        .take(32)
        .padEnd(3, '_')
    val extension = requestedName.substringAfterLast('.', "")
        .takeIf { it.isNotEmpty() }
        ?.let { ".$it" }
        .orEmpty()
    val file = File.createTempFile("${baseName}_", extension, ksuApp.cacheDir)
    return try {
        val input = ksuApp.contentResolver.openInputStream(uri)
            ?: error("Unable to open selected file: $uri")
        input.use { source ->
            file.outputStream().use { output ->
                source.copyTo(output)
            }
        }
        require(file.length() > 0) { "Selected file is empty: $uri" }
        file
    } catch (error: Exception) {
        file.delete()
        throw error
    }
}

fun flashModule(
    uri: Uri,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): FlashResult {
    if (!install()) {
        val error = "Failed to install the ApkeSU daemon"
        onStderr(error)
        return FlashResult(1, error, false)
    }

    val file = copyUriToCache(uri, "module.zip")
    try {
        val cmd = "module install ${shellQuote(file.absolutePath)}"
        val result = flashWithIO("${shellQuote(getKsuDaemonPath())} $cmd", onStdout, onStderr)
        Log.i("KernelSU", "install module $uri result: $result")

        return FlashResult(result)
    } finally {
        file.delete()
    }
}

fun runModuleAction(
    moduleId: String, onStdout: (String) -> Unit, onStderr: (String) -> Unit
): Shell.Result {
    val stdoutCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStdout(s ?: "")
        }
    }

    val stderrCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStderr(s ?: "")
        }
    }

    val result = withNewRootShell(true) {
        newJob().add("${shellQuote(getKsuDaemonPath())} module action ${shellQuote(moduleId)}")
            .to(stdoutCallback, stderrCallback).exec()
    }

    Log.i("KernelSU", "Module runAction result: $result")

    return result
}

fun restoreBoot(
    onStdout: (String) -> Unit, onStderr: (String) -> Unit
): FlashResult {
    val result = flashWithIO("${shellQuote(getKsuDaemonPath())} boot-restore -f", onStdout, onStderr)
    return FlashResult(result)
}

fun uninstallPermanently(
    onStdout: (String) -> Unit, onStderr: (String) -> Unit
): FlashResult {
    val result = flashWithIO(
        "${shellQuote(getKsuDaemonPath())} uninstall --package-name ${shellQuote(BuildConfig.APPLICATION_ID)}",
        onStdout,
        onStderr,
    )
    return FlashResult(result)
}

@Parcelize
sealed class LkmSelection : Parcelable {
    @Parcelize
    data class LkmUri(val uri: Uri) : LkmSelection()

    @Parcelize
    data class KmiString(val value: String) : LkmSelection()

    @Parcelize
    data class PathMaskKmiString(val value: String) : LkmSelection()

    @Parcelize
    data object KmiNone : LkmSelection()
}

private fun writeLkmFile(lkm: LkmSelection): File? {
    if (lkm !is LkmSelection.LkmUri) return null
    return copyUriToCache(lkm.uri, "kernelsu-tmp-lkm.ko")
}

private fun bootPatchFlags(
    allowShell: Boolean,
    enableAdb: Boolean,
    forceBackup: Boolean,
): String = buildString {
    if (allowShell) append(" --allow-shell")
    if (enableAdb) append(" --enable-adbd")
    if (forceBackup) append(" --backup")
}

enum class BootPatchMode {
    Normal,
    HiddenPath,
    NativeKpm,
}

internal fun BootPatchMode.cliArguments(): String = when (this) {
    BootPatchMode.Normal -> ""
    BootPatchMode.HiddenPath -> " --pathmask-lkm"
    BootPatchMode.NativeKpm -> error("Native GKI KPM requires boot-patch-kpimg")
}

internal fun nativeKpmPatchCommand(bootPath: String, outputPath: String): String =
    "boot-patch-kpimg --boot ${shellQuote(bootPath)} --output ${shellQuote(outputPath)} --force"

private suspend fun patchNativeKpmFile(
    input: File,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit,
): FlashResult = withContext(Dispatchers.IO) {
    val output = preparePatchedImageOutput(
        ksuApp.cacheDir,
        "apkesu_gki_kpm_${System.currentTimeMillis()}.img",
    )
    try {
        val command = nativeKpmPatchCommand(input.absolutePath, output.absolutePath)
        val result = flashWithIO("${shellQuote(getKsuDaemonPath())} $command", onStdout, onStderr)
        if (!result.isSuccess) return@withContext FlashResult(result, false)
        if (validatePatchedImageOutput(output) != null) restorePatchedImageAccess(output)
        val error = validatePatchedImageOutput(output)
        if (error != null) return@withContext FlashResult(1, error, false)
        val destination = saveFileToDownloads(ksuApp, output.name, output)
        onStdout("- Native GKI KPM image saved to $destination")
        FlashResult(result, false)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        val message = error.localizedMessage ?: "Native GKI KPM patch failed"
        onStderr(message)
        FlashResult(1, message, false)
    } finally {
        output.delete()
    }
}

suspend fun installBoot(
    bootUri: Uri?,
    lkm: LkmSelection,
    patchMode: BootPatchMode,
    ota: Boolean,
    partition: String?,
    allowShell: Boolean,
    enableAdb: Boolean,
    forceBackup: Boolean,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit,
): FlashResult {
    var bootFile: File? = null
    var lkmFile: File? = null
    var patchedOutput: File? = null

    return try {
        bootFile = bootUri?.let { uri -> copyUriToCache(uri, "boot.img") }
        if (patchMode == BootPatchMode.NativeKpm) {
            val input = bootFile
                ?: return FlashResult(1, "Native GKI KPM requires a selected boot.img", false)
            if (ota || partition != null && partition != "boot") {
                return FlashResult(1, "Native GKI KPM only supports boot.img output without flashing", false)
            }
            return patchNativeKpmFile(input, onStdout, onStderr)
        }
        var cmd = "boot-patch"

        cmd += bootFile?.let { " -b ${shellQuote(it.absolutePath)}" } ?: " -f"

        if (allowShell) {
            cmd += " --allow-shell"
        }
        if (enableAdb) {
            cmd += " --enable-adbd"
        }
        if (ota) {
            cmd += " -u"
        }
        if (forceBackup) {
            cmd += " --backup"
        }

        val effectivePatchMode = if (lkm is LkmSelection.PathMaskKmiString) {
            BootPatchMode.HiddenPath
        } else {
            patchMode
        }
        cmd += effectivePatchMode.cliArguments()

        when (lkm) {
            is LkmSelection.LkmUri -> {
                val selectedLkmFile = copyUriToCache(lkm.uri, "kernelsu-tmp-lkm.ko")
                lkmFile = selectedLkmFile
                cmd += " -m ${shellQuote(selectedLkmFile.absolutePath)}"
            }

            is LkmSelection.KmiString -> {
                cmd += " --kmi ${shellQuote(lkm.value)}"
            }

            is LkmSelection.PathMaskKmiString -> {
                cmd += " --kmi ${shellQuote(lkm.value)}"
            }

            LkmSelection.KmiNone -> Unit
        }

        if (bootFile != null) {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val outputName = "apkesu_patched_$timestamp.img"
            val outputFile = preparePatchedImageOutput(ksuApp.cacheDir, outputName)
            patchedOutput = outputFile
            cmd += " -o ${shellQuote(ksuApp.cacheDir.absolutePath)}"
            cmd += " --out-name ${shellQuote(outputName)}"
        } else {
            partition?.let { part ->
                cmd += " --partition ${shellQuote(part)}"
            }
        }

        if (bootFile == null) {
            // Direct install writes the patched boot immediately. Refresh the
            // persistent daemon first so the next boot keeps the APK-bundled
            // ksud/version instead of an older /data/adb/ksud copy.
            if (!install()) {
                val error = "Failed to install the ApkeSU daemon before direct install"
                onStderr(error)
                return FlashResult(1, error, false)
            }
        }

        val result = flashWithIO("${shellQuote(getKsuDaemonPath())} $cmd", onStdout, onStderr)
        Log.i("KernelSU", "install boot result: ${result.isSuccess}")

        if (!result.isSuccess) {
            return FlashResult(result, false)
        }

        patchedOutput?.let { output ->
            var outputError = validatePatchedImageOutput(output)
            if (outputError != null && output.isFile && output.length() > 0L) {
                Log.w(TAG, "$outputError; attempting to restore app access")
                restorePatchedImageAccess(output)
                outputError = validatePatchedImageOutput(output)
            }
            if (outputError != null) {
                val error = "Patched image output is unavailable: $outputError"
                onStderr(error)
                return FlashResult(1, error, false)
            }
            val savedPath = runCatching {
                saveFileToDownloads(
                    context = ksuApp,
                    displayName = output.name,
                    source = output,
                )
            }.getOrElse { throwable ->
                val error = "Failed to save patched image: ${throwable.localizedMessage ?: throwable.javaClass.simpleName}"
                onStderr(error)
                return FlashResult(1, error, false)
            }
            onStdout("- Patched image saved to $savedPath")
        }

        if (bootFile != null && rootAvailable() && !install()) {
            onStderr("Warning: patched successfully, but failed to refresh the ApkeSU daemon")
        }

        FlashResult(result, bootUri == null)
    } finally {
        bootFile?.delete()
        lkmFile?.delete()
        patchedOutput?.delete()
    }
}

/** Downloads a factory/OTA archive, extracts [partition], then patches it locally. */
suspend fun downloadBoot(
    url: String,
    partition: String,
    lkm: LkmSelection,
    patchMode: BootPatchMode,
    allowShell: Boolean,
    enableAdb: Boolean,
    forceBackup: Boolean,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit,
): FlashResult = withContext(Dispatchers.IO) {
    val bootFile = File(ksuApp.cacheDir, "download-boot.img")
    var lkmFile: File? = null
    var patchedOutput: File? = null
    try {
        if (patchMode == BootPatchMode.NativeKpm && partition != "boot") {
            return@withContext FlashResult(1, "Native GKI KPM requires the boot partition", false)
        }
        onStdout("- Downloading and extracting $partition")
        val channel = DataSourceChannel(newDownloadClient(), url)
        val magic = try {
            readMagic(channel)
        } finally {
            channel.position(0)
        }
        val image = ExtractImage(bootFile, onStdout)
        val probeChannel = DataSourceChannel(newDownloadClient(), url)
        val probedKmi = try {
            if (magic == "CrAU") {
                ExtractImage.probePayload(
                    probeChannel,
                    withKmi = patchMode != BootPatchMode.NativeKpm && lkm is LkmSelection.KmiNone,
                    onProgress = onStdout,
                ).kmi
            } else {
                ExtractImage.probe(
                    probeChannel,
                    withKmi = patchMode != BootPatchMode.NativeKpm && lkm is LkmSelection.KmiNone,
                    onProgress = onStdout,
                ).kmi
            }
        } finally {
            probeChannel.close()
        }
        try {
            if (magic == "CrAU") image.consumePayload(channel, partition) else image.consume(channel, partition)
        } finally {
            channel.close()
        }

        if (patchMode == BootPatchMode.NativeKpm) {
            return@withContext patchNativeKpmFile(bootFile, onStdout, onStderr)
        }
        val autoKmi = if (lkm is LkmSelection.KmiNone) {
            (probedKmi ?: BootKernelVersion.parseKmiFromBoot(bootFile))?.also {
                onStdout("- Auto detected KMI: $it")
            }
        } else {
            null
        }
        if (autoKmi == null && lkm is LkmSelection.KmiNone) {
            return@withContext FlashResult(-1, "Failed to determine KMI from the package", false)
        }

        val effectivePatchMode = if (lkm is LkmSelection.PathMaskKmiString) {
            BootPatchMode.HiddenPath
        } else {
            patchMode
        }
        var cmd = "${shellQuote(getKsuDaemonPath())} boot-patch -b ${shellQuote(bootFile.absolutePath)}"
        if (allowShell) cmd += " --allow-shell"
        if (enableAdb) cmd += " --enable-adbd"
        if (forceBackup) cmd += " --backup"
        cmd += effectivePatchMode.cliArguments()

        when (lkm) {
            is LkmSelection.LkmUri -> {
                val selectedLkmFile = copyUriToCache(lkm.uri, "kernelsu-tmp-lkm.ko")
                lkmFile = selectedLkmFile
                cmd += " -m ${shellQuote(selectedLkmFile.absolutePath)}"
            }
            is LkmSelection.KmiString -> cmd += " --kmi ${shellQuote(lkm.value)}"
            is LkmSelection.PathMaskKmiString -> cmd += " --kmi ${shellQuote(lkm.value)}"
            LkmSelection.KmiNone -> Unit
        }
        if (autoKmi != null) cmd += " --kmi ${shellQuote(autoKmi)}"
        cmd += " --partition ${shellQuote(partition)}"

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val outputName = "apkesu_patched_$timestamp.img"
        patchedOutput = preparePatchedImageOutput(ksuApp.cacheDir, outputName)
        cmd += " -o ${shellQuote(ksuApp.cacheDir.absolutePath)} --out-name ${shellQuote(outputName)}"

        val result = flashWithIO(cmd, onStdout, onStderr)
        if (!result.isSuccess) return@withContext FlashResult(result, false)

        val output = requireNotNull(patchedOutput)
        var outputError = validatePatchedImageOutput(output)
        if (outputError != null && output.isFile && output.length() > 0L) {
            restorePatchedImageAccess(output)
            outputError = validatePatchedImageOutput(output)
        }
        if (outputError != null) {
            val error = "Patched image output is unavailable: $outputError"
            onStderr(error)
            return@withContext FlashResult(1, error, false)
        }
        val savedPath = runCatching {
            saveFileToDownloads(ksuApp, output.name, output)
        }.getOrElse { throwable ->
            val error = "Failed to save patched image: ${throwable.localizedMessage ?: throwable.javaClass.simpleName}"
            onStderr(error)
            return@withContext FlashResult(1, error, false)
        }
        onStdout("- Patched image saved to $savedPath")
        FlashResult(result, false)
    } catch (error: Exception) {
        onStderr(error.localizedMessage ?: error.javaClass.simpleName)
        FlashResult(-1, error.localizedMessage ?: "Download failed", false)
    } finally {
        bootFile.delete()
        lkmFile?.delete()
        patchedOutput?.delete()
    }
}

suspend fun probeRemoteBootPartitions(url: String): ProbeResult = withContext(Dispatchers.IO) {
    DataSourceChannel(newDownloadClient(), url).use { channel ->
        val magic = readMagic(channel)
        if (magic == "CrAU") {
            ExtractImage.probePayload(channel, withKmi = false)
        } else {
            ExtractImage.probe(channel, withKmi = false)
        }
    }
}

private fun newDownloadClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

private fun readMagic(channel: DataSourceChannel): String {
    val buffer = ByteBuffer.allocate(4)
    channel.read(buffer)
    channel.position(0)
    return String(buffer.array(), StandardCharsets.ISO_8859_1)
}

internal fun preparePatchedImageOutput(cacheDir: File, outputName: String): File {
    check(cacheDir.isDirectory || cacheDir.mkdirs()) {
        "Unable to prepare patched image directory"
    }
    val output = File(cacheDir, outputName)
    if (output.exists()) {
        check(output.delete()) { "Unable to replace stale patched image output" }
    }
    output.outputStream().use { }
    return output
}

internal fun validatePatchedImageOutput(output: File): String? {
    if (!output.isFile) return "file is missing"
    if (output.length() <= 0L) return "file is empty"
    return runCatching {
        output.inputStream().use { it.read() }
    }.exceptionOrNull()?.let { throwable ->
        "file is not readable (${throwable.localizedMessage ?: throwable.javaClass.simpleName})"
    }
}

private fun restorePatchedImageAccess(output: File): Boolean {
    val uid = Process.myUid()
    val result = withNewRootShell {
        newJob()
            .add(
                "chown $uid:$uid ${shellQuote(output.absolutePath)} && " +
                    "chmod 0600 ${shellQuote(output.absolutePath)}"
            )
            .exec()
    }
    if (!result.isSuccess) {
        Log.w(TAG, "Failed to restore patched image access: ${result.err.joinToString("; ")}")
    }
    return result.isSuccess
}

fun reboot(reason: String = "") {
    if (reason == "soft_reboot") {
        execKsud("soft-reboot", true, true)
        return
    }
    val shell = getRootShell()
    if (reason == "recovery") {
        // KEYCODE_POWER = 26, hide incorrect "Factory data reset" message
        ShellUtils.fastCmd(shell, "/system/bin/input keyevent 26")
    }
    val quotedReason = shellQuote(reason)
    ShellUtils.fastCmd(
        shell,
        "/system/bin/svc power reboot $quotedReason || /system/bin/reboot $quotedReason"
    )
}

fun flashAnyKernelZip(
    uri: Uri,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): FlashResult {
    if (!install()) {
        val error = "Failed to install the ApkeSU daemon before AnyKernel flash"
        onStderr(error)
        return FlashResult(1, error, false)
    }

    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
    val tmpFile = copyUriToCache(uri, "anykernel_${timestamp}.zip")

    // AnyKernel is an external writer and can reboot or fail halfway through
    // its own script. Arm rescue verification before handing it the archive.
    // The daemon returns success when protection is disabled, so this remains
    // compatible with installations that do not use rescue protection.
    val rescueArmed = runCatching {
        execKsud(
            args = "rescue mark-pending ${shellQuote("AnyKernel install")}",
            newShell = true,
            globalMnt = true,
        )
    }.getOrElse {
        Log.w(TAG, "failed to arm rescue marker before AnyKernel flash", it)
        false
    }
    if (!rescueArmed) {
        tmpFile.delete()
        val error = "Rescue verification marker could not be armed; AnyKernel flash aborted"
        onStderr(error)
        return FlashResult(1, error, false)
    }
    onStdout("Rescue protection: next boot was marked for verification before AnyKernel flash")

    val destZip = tmpFile.absolutePath
    val destZipName = tmpFile.name
    val destDirFile = File(ksuApp.cacheDir, "anykernel3_${timestamp}")
    val destDir = destDirFile.absolutePath

    val cmd = """
        mkdir -p '$destDir' && \
        $BUSYBOX unzip -p -o '$destZip' "META-INF/com/google/android/update-binary" > '$destDir/update-binary' 2>/dev/null && \
        $BUSYBOX test -s '$destDir/update-binary' && \
        $BUSYBOX cp '$destZip' '$destDir/$destZipName' && \
        $BUSYBOX chmod 755 '$destDir/update-binary' && \
        $BUSYBOX chown root:root '$destDir/update-binary' && \
        (cd '$destDir' && \
            if [ -f './update-binary' ] && $BUSYBOX grep -q "AnyKernel3" './update-binary'; then \
                AKHOME='$destDir/tmp' $BUSYBOX ash '$destDir/update-binary' 3 1 '$destDir/$destZipName'; \
            else \
                echo 'No installer script found' >&2; exit 1; \
            fi)
    """.trimIndent().replace(Regex("\\s+\\\\\\s*"), " ")

    return try {
        val result = flashWithIoAk3(cmd, onStdout, onStderr)
        FlashResult(result, result.isSuccess)
    } finally {
        runCatching {
            createRootShell(true).use { shell ->
                shell.newJob()
                    .add("rm -rf ${shellQuote(destDir)} ${shellQuote(destZip)}")
                    .exec()
            }
        }
        tmpFile.delete()
    }
}

fun rootAvailable(): Boolean {
    return runCatching {
        val available = getRootShell().isRoot
        if (!available) {
            KsuCli.reset()
        }
        available
    }.getOrDefault(false)
}

fun ksuRootAvailable(): Boolean {
    return runCatching {
        val shell = Shell.Builder.create().build(getKsuDaemonPath(), "debug", "su")
        try {
            shell.isRoot
        } finally {
            shell.closeQuietly()
        }
    }.getOrDefault(false)
}

fun apkeSuKernelModuleLoaded(): Boolean {
    return runCatching {
        File("/sys/module/kernelsu").isDirectory ||
            File("/sys/module/apkesu").isDirectory
    }.getOrDefault(false)
}

fun apkeSuRootAvailable(): Boolean {
    if (runCatching { Natives.version > 0 }.getOrDefault(false) ||
        apkeSuKernelModuleLoaded()
    ) {
        return true
    }
    if (ksuRootAvailable()) {
        return true
    }

    return runCatching {
        withNewRootShell {
            if (!isRoot) {
                return@withNewRootShell false
            }

            val versionOutput = ArrayList<String>()
            newJob()
                .add("su -v 2>/dev/null || /system/bin/su -v 2>/dev/null || true")
                .to(versionOutput, null)
                .exec()
            val suVersion = versionOutput.joinToString("\n")
            if (suVersion.contains("KernelSU", ignoreCase = true) ||
                suVersion.contains("ApkeSU", ignoreCase = true)
            ) {
                return@withNewRootShell true
            }

            val marker = ShellUtils.fastCmd(
                this,
                "if [ -d /sys/module/kernelsu ] || [ -d /sys/module/apkesu ] || " +
                    "grep -Eq '^(kernelsu|apkesu) ' /proc/modules 2>/dev/null; " +
                    "then echo 1; else echo 0; fi"
            ).trim()
            marker == "1"
        }
    }.getOrDefault(false)
}

suspend fun collectRootDiagnosticInfo(): RootDiagnosticInfo = withContext(Dispatchers.IO) {
    runCatching { Natives.refreshInfo() }
    val driverVersion = runCatching { Natives.version }.getOrDefault(0)
    val managerRegistered = runCatching { Natives.isManager }.getOrDefault(false)
    val kernelUapi = runCatching { Natives.kernelUAPIVersion }.getOrDefault(0)
    val managerUapi = runCatching { Natives.managerUAPIVersion }.getOrDefault(0)
    val lkmMode = runCatching { Natives.isLkmMode }.getOrDefault(false)
    val lateLoadMode = runCatching { Natives.isLateLoadMode }.getOrDefault(false)
    val ksuRootShell = ksuRootAvailable()
    val kernelModuleLoaded = apkeSuKernelModuleLoaded()
    val ksud = shellQuote(getKsuDaemonPath())

    var fallbackRootShell = false
    var packagedKsudVersion = ""
    var installedKsudVersion = ""
    var currentKmi = ""
    var currentSlot = ""
    runCatching {
        withNewRootShell {
            fallbackRootShell = isRoot
            packagedKsudVersion = ShellUtils.fastCmd(
                this,
                "$ksud debug userspace-version 2>/dev/null",
            ).trim()
            installedKsudVersion = ShellUtils.fastCmd(
                this,
                "if [ -x /data/adb/ksud ]; then " +
                    "/data/adb/ksud debug userspace-version 2>/dev/null; else echo missing; fi",
            ).trim()
            currentKmi = ShellUtils.fastCmd(
                this,
                "$ksud boot-info current-kmi 2>/dev/null",
            ).trim()
            currentSlot = ShellUtils.fastCmd(
                this,
                "getprop ro.boot.slot_suffix 2>/dev/null",
            ).trim().ifBlank {
                ShellUtils.fastCmd(this, "getprop ro.boot.slot 2>/dev/null").trim()
            }
        }
    }.onFailure {
        Log.w(TAG, "collect root shell diagnostics failed", it)
    }

    val workMode = when {
        lateLoadMode -> "late_load"
        lkmMode -> "lkm"
        driverVersion > 0 || kernelModuleLoaded || ksuRootShell -> "gki"
        else -> "unknown"
    }
    RootDiagnosticInfo(
        driverVersion = driverVersion,
        kernelModuleLoaded = kernelModuleLoaded,
        ksuRootShell = ksuRootShell,
        fallbackRootShell = fallbackRootShell,
        managerRegistered = managerRegistered,
        managerUid = Os.getuid(),
        kernelUapi = kernelUapi,
        managerUapi = managerUapi,
        packagedKsudVersion = packagedKsudVersion,
        installedKsudVersion = installedKsudVersion,
        currentKmi = currentKmi,
        currentSlot = currentSlot,
        workMode = workMode,
        hiddenPathLkm = if (fallbackRootShell && lkmMode) {
            isHiddenPathLkmMode()
        } else {
            false
        },
    )
}

fun getInstalledKsudStatus(): InstalledKsudStatus {
    return runCatching {
        withNewRootShell {
            if (!isRoot) return@withNewRootShell InstalledKsudStatus()
            val stdout = ArrayList<String>()
            newJob()
                .add(
                    "if [ -x /data/adb/ksud ]; then " +
                        "echo present; /data/adb/ksud debug userspace-version 2>/dev/null; " +
                        "else echo missing; fi"
                )
                .to(stdout, null)
                .exec()
            val present = stdout.firstOrNull()?.trim() == "present"
            val versionCode = stdout.firstOrNull { it.trim().startsWith("{") }
                ?.let { JSONObject(it).optString("versionCode").toIntOrNull() }
            InstalledKsudStatus(present = present, versionCode = versionCode)
        }
    }.getOrElse {
        Log.w(TAG, "installed ksud status unavailable", it)
        InstalledKsudStatus()
    }
}

fun ensureManagerRegistered(): Boolean {
    return synchronized(managerRegistrationLock) {
        if (runCatching { Natives.refreshInfo(); Natives.isManager }.getOrDefault(false)) {
            lastManagerRegistrationFailureKey = null
            lastManagerRegistrationFailureAt = 0L
            return@synchronized true
        }
        if (!apkeSuRootAvailable()) {
            return@synchronized false
        }

        val managerUid = Os.getuid()
        val managerAppId = managerUid.mod(100_000)
        if (managerAppId !in FIRST_APPLICATION_APPID..LAST_APPLICATION_APPID) {
            Log.e(TAG, "refusing manager registration for non-application uid $managerUid")
            return@synchronized false
        }
        val driverVersion = runCatching { Natives.version }.getOrDefault(0)
        val failureKey = "$driverVersion:$managerUid"
        val now = SystemClock.elapsedRealtime()
        if (failureKey == lastManagerRegistrationFailureKey &&
            now - lastManagerRegistrationFailureAt < MANAGER_REGISTRATION_RETRY_MILLIS
        ) {
            Log.w(TAG, "skip repeated manager registration for driver $driverVersion")
            return@synchronized false
        }
        val result = runCatching {
            val ksud = shellQuote(getKsuDaemonPath())
            val packageName = shellQuote(BuildConfig.APPLICATION_ID)
            val command = "$ksud register-manager --package-name $packageName --manager-uid $managerUid"
            withNewRootShell {
                newJob().add(command).exec()
            }
        }.onFailure {
            Log.w(TAG, "register manager appid failed", it)
        }.getOrNull()

        if (result?.isSuccess != true) {
            lastManagerRegistrationFailureKey = failureKey
            lastManagerRegistrationFailureAt = SystemClock.elapsedRealtime()
            Log.w(TAG, "register manager appid failed: ${result?.err?.joinToString("\n")}")
            return@synchronized false
        }

        KsuCli.reset()
        val registered = runCatching {
            Natives.refreshInfo()
            Natives.isManager
        }.getOrDefault(false)
        if (registered) {
            lastManagerRegistrationFailureKey = null
            lastManagerRegistrationFailureAt = 0L
        } else {
            lastManagerRegistrationFailureKey = failureKey
            lastManagerRegistrationFailureAt = SystemClock.elapsedRealtime()
            Log.w(TAG, "manager registration command succeeded but kernel identity did not refresh")
        }
        registered
    }
}

private fun shouldSkipUnsafeKsudCommand(): Boolean {
    return Build.VERSION.SDK_INT >= ANDROID_16_API && !rootAvailable()
}

private val fallbackSupportedKmis = listOf(
    "android12-5.10",
    "android13-5.10",
    "android13-5.15",
    "android14-5.15",
    "android14-6.1",
    "android15-6.6",
    "android16-6.12",
    "android17-6.18",
)

private val kmiNameRegex = Regex("""^android\d+-\d+(?:\.\d+)?$""")

internal enum class BootImageKmiSource {
    Image,
    CurrentDevice,
}

internal data class BootImageKmiDetection(
    val kmi: String,
    val source: BootImageKmiSource,
)

internal suspend fun detectBootImageKmi(
    uri: Uri,
    fallbackKmi: String? = null,
): BootImageKmiDetection = withContext(Dispatchers.IO) {
    var bootFile: File? = null
    try {
        val selectedBootFile = copyUriToCache(uri, "boot-kmi.img")
        bootFile = selectedBootFile
        val imageHasNoKernel = runCatching {
            BootKernelVersion.isKernellessBootImage(selectedBootFile)
        }.getOrDefault(false)

        // Parse the selected image locally first. This path understands compressed
        // kernel payloads and also works before the daemon has been installed.
        runCatching { BootKernelVersion.parseKmiFromBoot(selectedBootFile) }
            .getOrNull()
            ?.takeIf { it.matches(kmiNameRegex) }
            ?.let { return@withContext BootImageKmiDetection(it, BootImageKmiSource.Image) }

        // Keep ksud as a compatibility fallback for boot formats not covered by
        // the lightweight manager parser.
        val stdout = ArrayList<String>()
        val stderr = ArrayList<String>()
        val command = "${shellQuote(getKsuDaemonPath())} boot-info image-kmi " +
            "--boot ${shellQuote(selectedBootFile.absolutePath)}"
        val result = withNewRootShell {
            newJob().add(command).to(stdout, stderr).exec()
        }
        if (result.isSuccess) {
            stdout.asSequence()
                .map(String::trim)
                .firstOrNull { it.matches(kmiNameRegex) }
                ?.let { return@withContext BootImageKmiDetection(it, BootImageKmiSource.Image) }
        }

        // init_boot images intentionally contain no kernel. For those images the
        // current device KMI is the correct target, while a normal unreadable boot
        // image must still fail and require manual selection.
        if (imageHasNoKernel) {
            val currentKmi = (fallbackKmi ?: getCurrentKmi())
                .takeIf { it.matches(kmiNameRegex) }
            if (currentKmi != null) {
                return@withContext BootImageKmiDetection(
                    currentKmi,
                    BootImageKmiSource.CurrentDevice,
                )
            }
        }

        val detail = stderr.joinToString("\n").trim()
        error(detail.ifBlank { "The selected image does not contain a supported KMI marker" })
    } finally {
        bootFile?.delete()
    }
}

suspend fun getCurrentKmi(): String = withContext(Dispatchers.IO) {
    runCatching {
        val shell = getRootShell()
        val cmd = "boot-info current-kmi"
        ShellUtils.fastCmd(shell, "${shellQuote(getKsuDaemonPath())} $cmd").trim()
    }.getOrElse {
        Log.w(TAG, "current KMI detection failed", it)
        ""
    }
}

suspend fun getSupportedKmis(): List<String> = withContext(Dispatchers.IO) {
    runCatching {
        val shell = getRootShell()
        val cmd = "boot-info supported-kmis"
        val result = shell.newJob()
            .add("${shellQuote(getKsuDaemonPath())} $cmd")
            .to(ArrayList(), null)
            .exec()
        check(result.isSuccess) { result.err.joinToString("\n").ifBlank { "ksud exited with ${result.code}" } }
        result.out.map { it.trim() }
            .filter { it.matches(kmiNameRegex) }
            .distinct()
            .ifEmpty { fallbackSupportedKmis }
    }.getOrElse {
        Log.w(TAG, "supported KMI detection failed; using packaged fallback list", it)
        fallbackSupportedKmis
    }
}

suspend fun isAbDevice(): Boolean = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val cmd = "boot-info is-ab-device"
    ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} $cmd").trim().toBoolean()
}

suspend fun getDefaultPartition(ota: Boolean = false): String = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    if (shell.isRoot) {
        val cmd = if (ota) "boot-info default-partition --ota" else "boot-info default-partition"
        ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} $cmd").trim()
    } else {
        if (!Os.uname().release.contains("android12-")) "init_boot" else "boot"
    }
}

suspend fun getSlotSuffix(ota: Boolean): String = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val cmd = if (ota) {
        "boot-info slot-suffix --ota"
    } else {
        "boot-info slot-suffix"
    }
    ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} $cmd").trim()
}

suspend fun getAvailablePartitions(ota: Boolean = false): List<String> = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val cmd = if (ota) "boot-info available-partitions --ota" else "boot-info available-partitions"
    val out = shell.newJob().add("${getKsuDaemonPath()} $cmd").to(ArrayList(), null).exec().out
    out.filter { it.isNotBlank() }.map { it.trim() }
}

fun hasMagisk(): Boolean {
    val shell = getRootShell(true)
    val result = shell.newJob().add("which magisk").exec()
    Log.i(TAG, "has magisk: ${result.isSuccess}")
    return result.isSuccess
}

fun isSepolicyValid(rules: String?): Boolean {
    if (rules == null) {
        return true
    }
    val shell = getRootShell()
    val result = shell.newJob()
        .add("${shellQuote(getKsuDaemonPath())} sepolicy check ${shellQuote(rules)}")
        .to(ArrayList(), null)
            .exec()
    return result.isSuccess
}

fun getSepolicy(pkg: String): String {
    val shell = getRootShell()
    val result = shell.newJob()
        .add("${shellQuote(getKsuDaemonPath())} profile get-sepolicy ${shellQuote(pkg)}")
        .to(ArrayList(), null)
            .exec()
    Log.i(TAG, "code: ${result.code}, out: ${result.out}, err: ${result.err}")
    return result.out.joinToString("\n")
}

fun setSepolicy(pkg: String, rules: String): Boolean {
    val shell = getRootShell()
    val result = shell.newJob()
        .add("${shellQuote(getKsuDaemonPath())} profile set-sepolicy ${shellQuote(pkg)} ${shellQuote(rules)}")
        .to(ArrayList(), null).exec()
    Log.i(TAG, "set sepolicy result: ${result.code}")
    return result.isSuccess
}

fun listAppProfileTemplates(): List<String> {
    val shell = getRootShell()
    return shell.newJob().add("${shellQuote(getKsuDaemonPath())} profile list-templates").to(ArrayList(), null)
        .exec().out
}

fun getAppProfileTemplate(id: String): String {
    val shell = getRootShell()
    return shell.newJob().add("${shellQuote(getKsuDaemonPath())} profile get-template ${shellQuote(id)}")
        .to(ArrayList(), null).exec().out.joinToString("\n")
}

fun setAppProfileTemplate(id: String, template: String): Boolean {
    val shell = getRootShell()
    val cmd = "${shellQuote(getKsuDaemonPath())} profile set-template ${shellQuote(id)} ${shellQuote(template)}"
    return shell.newJob().add(cmd)
        .to(ArrayList(), null).exec().isSuccess
}

fun deleteAppProfileTemplate(id: String): Boolean {
    val shell = getRootShell()
    return shell.newJob().add("${shellQuote(getKsuDaemonPath())} profile delete-template ${shellQuote(id)}")
        .to(ArrayList(), null).exec().isSuccess
}

private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) {
        return emptyList()
    }
    return buildList {
        for (index in 0 until length()) {
            optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
        }
    }
}

private fun JSONObject?.toRescueImageState(): RescueImageState {
    if (this == null) {
        return RescueImageState()
    }
    return RescueImageState(
        name = optString("name", ""),
        label = optString("label", ""),
        partition = optString("partition", ""),
        image = optString("image", ""),
        required = optBoolean("required", false),
        custom = optBoolean("custom", false),
        exists = optBoolean("exists", false),
        size = optLong("size", 0),
        partitionSize = optLong("partitionSize", 0),
        sha256 = optString("sha256", ""),
        sha256Ok = optBoolean("sha256Ok", true),
        sizeOk = optBoolean("sizeOk", true),
        otherSlot = optBoolean("otherSlot", false),
        restore = optBoolean("restore", true),
        dangerous = optBoolean("dangerous", false),
        verificationState = optString("verificationState", "unknown"),
    )
}

private fun JSONObject?.toRescueRestoreTransaction(): RescueRestoreTransaction? {
    if (this == null) return null
    val entriesJson = optJSONArray("entries")
    val entries = buildList {
        if (entriesJson != null) {
            for (index in 0 until entriesJson.length()) {
                val entry = entriesJson.optJSONObject(index) ?: continue
                add(
                    RescueRestoreEntry(
                        name = entry.optString("name", ""),
                        label = entry.optString("label", ""),
                        imagePath = entry.optString("imagePath", ""),
                        devicePath = entry.optString("devicePath", ""),
                        expectedSha256 = entry.optString("expectedSha256", ""),
                        expectedSize = entry.optLong("expectedSize", 0),
                        status = entry.optString("status", ""),
                    )
                )
            }
        }
    }
    return RescueRestoreTransaction(
        id = optString("id", ""),
        reason = optString("reason", ""),
        automatic = optBoolean("automatic", false),
        description = optString("description", ""),
        activateSlot = optString("activateSlot", ""),
        phase = optString("phase", ""),
        errorCode = optString("errorCode", ""),
        errorMessage = optString("errorMessage", ""),
        startedAt = optString("startedAt", ""),
        updatedAt = optString("updatedAt", ""),
        entries = entries,
    )
}

private fun JSONArray?.toRescueDisabledModules(): List<RescueDisabledModule> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val module = optJSONObject(index) ?: continue
            add(
                RescueDisabledModule(
                    id = module.optString("id", ""),
                    name = module.optString("name", ""),
                    version = module.optString("version", ""),
                    installed = module.optBoolean("installed", false),
                    disabled = module.optBoolean("disabled", false),
                )
            )
        }
    }
}

private fun JSONObject?.toRescueConfigState(): RescueConfigState {
    if (this == null) {
        return RescueConfigState()
    }
    val custom = optJSONObject("customPartitions")
    val keys = custom?.keys()
    val map = mutableMapOf<String, String>()
    if (keys != null) {
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = custom.optString(key, "")
        }
    }
    return RescueConfigState(
        includeDtbo = optBoolean("includeDtbo", false),
        includeVbmeta = optBoolean("includeVbmeta", false),
        backupOtherSlot = optBoolean("backupOtherSlot", false),
        allowDangerousAutoRestore = optBoolean("allowDangerousAutoRestore", false),
        customPartitions = map,
    )
}

private fun JSONArray?.toRescueImageList(): List<RescueImageState> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            add(optJSONObject(index).toRescueImageState())
        }
    }
}

private fun List<String>.cleanConfigList(): List<String> {
    return map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
}

fun forceStopApp(packageName: String, userId: Int? = null) {
    val shell = getRootShell()
    val userArg = userId?.let { " --user $it" } ?: ""
    val result = shell.newJob().add("am force-stop$userArg ${shellQuote(packageName)}").exec()
    Log.i(TAG, "force stop $packageName result: $result")
}

fun launchApp(packageName: String, userId: Int? = null) {
    val shell = getRootShell()
    val userArg = userId?.let { " --user $it" } ?: ""
    val result =
        shell.newJob()
            .add(
                "component=\$(cmd package resolve-activity --brief$userArg ${shellQuote(packageName)} | " +
                    "tail -n 1); [ -n \"\$component\" ] && " +
                    "cmd activity start-activity$userArg -n \"\$component\""
            )
            .exec()
    Log.i(TAG, "launch $packageName result: $result")
}

fun restartApp(packageName: String, userId: Int? = null) {
    forceStopApp(packageName, userId)
    launchApp(packageName, userId)
}
