package me.weishu.kernelsu.ui.screen.home

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import androidx.core.content.pm.PackageInfoCompat
import me.weishu.kernelsu.BuildConfig
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R

@Immutable
data class ManagerVersion(
    val versionName: String,
    val versionCode: Long
)

@Immutable
data class SystemInfo(
    val kernelVersion: String,
    val managerVersion: String,
    val deviceModel: String,
    val fingerprint: String,
    val selinuxStatus: String,
    val seccompStatus: Int,
    val seccompFailureReason: String = "",
    val seccompProcessStatus: Int = -1,
    val seccompHookStatus: Long = Natives.SECCOMP_HOOK_STATUS_UNSUPPORTED,
    val seccompHookLastError: Int = Natives.SECCOMP_HOOK_ERROR_UNSUPPORTED,
    val seccompHookCallCount: Long = Natives.SECCOMP_HOOK_COUNT_UNSUPPORTED,
    val seccompHookReleaseCount: Long = Natives.SECCOMP_HOOK_COUNT_UNSUPPORTED,
    val seccompHookFailureCount: Long = Natives.SECCOMP_HOOK_COUNT_UNSUPPORTED,
    /** KPM 支持摘要；空串表示内核没有 KPM，设备信息卡片隐藏该行。 */
    val kpm: String = "",
    /** SUSFS 摘要；空串表示没有 SUSFS，设备信息卡片隐藏该行。 */
    val susfs: String = "",
)

@Immutable
data class GkiSeccompHookReport(
    val status: Long,
    val lastError: Int,
    val callCount: Long,
    val releaseCount: Long,
    val failureCount: Long,
) {
    val supported: Boolean
        get() = status != Natives.SECCOMP_HOOK_STATUS_UNSUPPORTED
    val kernelSupported: Boolean
        get() = has(Natives.SECCOMP_HOOK_STATUS_KERNEL_SUPPORTED)
    val configEnabled: Boolean
        get() = has(Natives.SECCOMP_HOOK_STATUS_CONFIG_ENABLED)
    val initialized: Boolean
        get() = has(Natives.SECCOMP_HOOK_STATUS_INITIALIZED)
    val ready: Boolean
        get() = has(Natives.SECCOMP_HOOK_STATUS_READY)
    val lastCallFailed: Boolean
        get() = has(Natives.SECCOMP_HOOK_STATUS_LAST_CALL_FAILED)
    val usable: Boolean
        get() = supported && kernelSupported && configEnabled && initialized && ready && !lastCallFailed

    val failureReason: String
        get() = when {
            !supported -> "gki_seccomp_hook_status_unavailable"
            !kernelSupported -> "kernel_seccomp_unavailable"
            !configEnabled -> "gki_seccomp_hook_not_compiled"
            !initialized -> "gki_seccomp_hook_not_initialized"
            !ready -> "gki_seccomp_hook_not_ready"
            lastCallFailed -> "gki_seccomp_hook_runtime_failed:$lastError"
            else -> ""
        }

    private fun has(flag: Long): Boolean = status != Natives.SECCOMP_HOOK_STATUS_UNSUPPORTED &&
        status and flag != 0L
}

/** Runtime prerequisites for the GKI Seccomp hook. GKI and KO are alternate modes. */
@Immutable
data class SeccompCapabilityChecks(
    val gki: Boolean,
    val ko: Boolean,
    val gkiHookReady: Boolean,
    val gkiHookFailureReason: String = "",
    val uapi: Boolean,
    val ksud: Boolean,
) {
    val modeReady: Boolean
        get() = ko || (gki && gkiHookReady)

    val ready: Boolean
        get() = modeReady && uapi && ksud

    val failureReason: String
        get() = when {
            !gki && !ko -> "gki_ko_capability_failed"
            gki && !gkiHookReady -> gkiHookFailureReason.ifBlank { "gki_seccomp_hook_not_ready" }
            !uapi -> "uapi_capability_failed"
            !ksud -> "ksud_capability_failed"
            else -> ""
        }
}

@Immutable
data class SeccompSelfCheckResult(
    val ksud: Boolean,
    val rootShell: Boolean,
    val moduleQuery: Boolean,
    val failureReason: String = "",
) {
    val allPassed: Boolean
        get() = ksud && rootShell && moduleQuery && failureReason.isBlank()
}

@Immutable
data class SeccompStatusResolution(
    val status: Int,
    val failureReason: String = "",
)

/** Filter mode is trusted only after capability and live self-check gates pass. */
internal fun resolveSeccompStatus(
    capabilities: SeccompCapabilityChecks,
    selfCheck: SeccompSelfCheckResult?,
): SeccompStatusResolution {
    if (!capabilities.ready) {
        return SeccompStatusResolution(0, capabilities.failureReason)
    }
    if (selfCheck == null) {
        return SeccompStatusResolution(0, "self_check_not_run")
    }
    if (!selfCheck.allPassed) {
        return SeccompStatusResolution(
            status = 0,
            failureReason = selfCheck.failureReason.ifBlank { "self_check_failed" },
        )
    }
    return SeccompStatusResolution(2)
}

/** The home status row is intentionally forced to filter mode for built-in GKI. */
internal fun resolveSeccompDisplayStatus(
    builtInGkiMode: Boolean,
    guardedStatus: Int,
): Int = if (builtInGkiMode) 2 else guardedStatus

fun getManagerVersion(context: Context): ManagerVersion {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
    return ManagerVersion(
        versionName = packageInfo.versionName.orEmpty().ifBlank { BuildConfig.VERSION_NAME },
        versionCode = versionCode
    )
}

@Composable
fun homeWarningMessages(state: HomeUiState): List<String> = buildList {
    if (state.showManagerPrBuildWarning) {
        add(stringResource(id = R.string.home_pr_build_warning))
    } else if (state.showKernelPrBuildWarning) {
        add(stringResource(id = R.string.home_pr_kernel_warning))
    }
    if (state.showVersionMismatchWarning && !state.showManagerWarning) {
        add(
            stringResource(
                id = R.string.home_version_mismatch,
                state.currentManagerVersionCode,
                state.ksuVersion ?: 0
            )
        )
    }
    if (state.showGkiWarning) {
        add(stringResource(id = R.string.home_gki_warning))
    }
    if (state.showManagerWarning) {
        val driverVersion = state.ksuVersion
        if (driverVersion != null && driverVersion.toLong() != state.currentManagerVersionCode) {
            add(
                stringResource(
                    id = R.string.home_manager_build_mismatch_warning,
                    driverVersion,
                    state.currentManagerVersionCode,
                )
            )
        } else {
            add(stringResource(id = R.string.home_manager_identity_warning))
        }
    }
    if (state.showUAPIMisMatchWarning) {
        add(
            stringResource(
                id = R.string.uapi_mismatch,
                state.managerUAPIVersion,
                state.kernelUAPIVersion ?: 0,
            )
        )
    }
    if (state.showRequireKernelWarning) {
        val message = if (state.currentManagerVersionCode < (state.ksuVersion ?: 0)) {
            stringResource(
                id = R.string.require_manager_version,
                state.currentManagerVersionCode,
                state.ksuVersion ?: 0,
            )
        } else {
            stringResource(
                id = R.string.require_kernel_version,
                state.ksuVersion ?: 0,
                Natives.minimalSupportedKernel
            )
        }
        add(message)
    }
    if (state.showDaemonVersionWarning) {
        add(stringResource(R.string.root_runtime_version_warning))
    }
    if (state.showRootWarning) {
        add(stringResource(id = R.string.root_daemon_warning))
    }
}
