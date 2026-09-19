package me.weishu.kernelsu.ui.viewmodel

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.system.Os
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.BuildConfig
import me.weishu.kernelsu.KernelVersion
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.repository.SettingsRepository
import me.weishu.kernelsu.data.repository.SettingsRepositoryImpl
import me.weishu.kernelsu.data.repository.CUSTOM_HOME_TITLE_KEY
import me.weishu.kernelsu.data.repository.MIUIX_CLASSIC_HOME_LAYOUT_KEY
import me.weishu.kernelsu.data.repository.SHOW_GKI_WARNING_KEY
import me.weishu.kernelsu.data.repository.SHOW_HOME_LEARN_CARD_KEY
import me.weishu.kernelsu.data.repository.SHOW_HOME_SUPPORT_CARD_KEY
import me.weishu.kernelsu.data.repository.SHOW_VERSION_MISMATCH_WARNING_KEY
import me.weishu.kernelsu.getKernelVersion
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.screen.home.HomeUiState
import me.weishu.kernelsu.ui.screen.home.GkiSeccompHookReport
import me.weishu.kernelsu.ui.screen.home.KernelHookType
import me.weishu.kernelsu.ui.screen.home.RootRuntimeState
import me.weishu.kernelsu.ui.screen.home.SeccompCapabilityChecks
import me.weishu.kernelsu.ui.screen.home.SeccompSelfCheckResult
import me.weishu.kernelsu.ui.screen.home.SeccompStatusResolution
import me.weishu.kernelsu.ui.screen.home.SystemInfo
import me.weishu.kernelsu.ui.screen.home.getManagerVersion
import me.weishu.kernelsu.ui.screen.home.hasBlockingRootVersionMismatch
import me.weishu.kernelsu.ui.screen.home.resolveSeccompDisplayStatus
import me.weishu.kernelsu.ui.util.apkeSuKernelModuleLoaded
import me.weishu.kernelsu.ui.util.apkeSuRootAvailable
import me.weishu.kernelsu.ui.util.collectRootDiagnosticInfo
import me.weishu.kernelsu.ui.util.ensureManagerRegistered
import me.weishu.kernelsu.ui.util.getModuleCount
import me.weishu.kernelsu.ui.util.getInstalledKsudStatus
import me.weishu.kernelsu.ui.util.getSELinuxStatusRaw
import me.weishu.kernelsu.ui.util.getSuperuserCount
import me.weishu.kernelsu.ui.util.isHiddenPathLkmMode
import me.weishu.kernelsu.ui.util.KsuCli
import me.weishu.kernelsu.ui.util.ksuRootAvailable
import me.weishu.kernelsu.ui.util.probeKpmSummary
import me.weishu.kernelsu.ui.util.probeSusfsSummary
import me.weishu.kernelsu.ui.util.resolveDeviceName
import me.weishu.kernelsu.ui.util.runSeccompSelfChecks
import me.weishu.kernelsu.ui.util.rootAvailable
import me.weishu.kernelsu.ui.screen.home.resolveSeccompStatus
import java.util.concurrent.atomic.AtomicLong
import java.text.DateFormat
import java.util.Date
import org.json.JSONObject

private const val SECCOMP_FAILURE_REASON_KEY = "last_failure_reason"
private const val SECCOMP_FAILURE_TIME_KEY = "last_failure_time"

class HomeViewModel(
    private val repo: SettingsRepository = SettingsRepositoryImpl()
) : ViewModel() {

    private val prefs = ksuApp.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val seccompPrefs = ksuApp.getSharedPreferences("seccomp_guard", Context.MODE_PRIVATE)
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SHOW_VERSION_MISMATCH_WARNING_KEY ||
            key == SHOW_GKI_WARNING_KEY ||
            key == SHOW_HOME_SUPPORT_CARD_KEY ||
            key == SHOW_HOME_LEARN_CARD_KEY ||
            key == MIUIX_CLASSIC_HOME_LAYOUT_KEY ||
            key == CUSTOM_HOME_TITLE_KEY
        ) {
            _uiState.update {
                it.copy(
                    showVersionMismatchWarningSetting = repo.showVersionMismatchWarning,
                    showGkiWarningSetting = repo.showGkiWarning,
                    showHomeSupportCard = repo.showHomeSupportCard,
                    showHomeLearnCard = repo.showHomeLearnCard,
                    miuixClassicHomeLayoutEnabled = repo.miuixClassicHomeLayoutEnabled,
                    customHomeTitle = repo.customHomeTitle,
                )
            }
        }
    }

    private val _uiState = MutableStateFlow(fallbackState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private var refreshJob: Job? = null
    private val refreshGeneration = AtomicLong(0L)
    private val _diagnosticReport = MutableStateFlow<String?>(null)
    val diagnosticReport: StateFlow<String?> = _diagnosticReport.asStateFlow()
    private val _diagnosticRunning = MutableStateFlow(false)
    val diagnosticRunning: StateFlow<Boolean> = _diagnosticRunning.asStateFlow()

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    override fun onCleared() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        super.onCleared()
    }

    fun refresh() {
        val generation = refreshGeneration.incrementAndGet()
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val baseState = withContext(Dispatchers.IO) {
                runCatching { Natives.refreshInfo() }
                buildStateSafely()
            }
            if (generation != refreshGeneration.get()) return@launch
            _uiState.update { baseState }
            // KPM / SUSFS 需要 root 探测，放在状态之后异步补齐，避免拖慢主页首屏
            val systemInfo = withContext(Dispatchers.IO) {
                baseState.systemInfo.copy(
                    kpm = runCatching { probeKpmSummary() }.getOrDefault(""),
                    susfs = runCatching { probeSusfsSummary() }.getOrDefault(""),
                )
            }
            if (generation != refreshGeneration.get()) return@launch
            _uiState.update { current ->
                if (current.systemInfo == baseState.systemInfo) {
                    current.copy(systemInfo = systemInfo)
                } else {
                    current
                }
            }
        }
    }

    fun runRootDiagnostics() {
        if (_diagnosticRunning.value) return
        _diagnosticRunning.value = true
        _diagnosticReport.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = collectRootDiagnosticInfo()
                _diagnosticReport.value = buildDiagnosticReport(info)
                refresh()
            } catch (error: CancellationException) {
                throw error
            } catch (throwable: Exception) {
                Log.e(TAG, "root diagnostics failed", throwable)
                _diagnosticReport.value = ksuApp.getString(
                    R.string.root_diagnostic_failed,
                    throwable.message.orEmpty(),
                )
            } finally {
                _diagnosticRunning.value = false
            }
        }
    }

    fun dismissDiagnosticReport() {
        _diagnosticReport.value = null
    }

    private suspend fun buildStateSafely(): HomeUiState {
        return try {
            buildState()
        } catch (throwable: Throwable) {
            Log.e(TAG, "build home state failed", throwable)
            fallbackState()
        }
    }

    private suspend fun buildState(): HomeUiState {
        val kernelVersion = getKernelVersion()
        var isManager = runCatching { Natives.isManager }.getOrDefault(false)
        var ksuVersion = runCatching { Natives.version.takeIf { it > 0 } }.getOrNull()
        var ksuDaemonRoot = runCatching { ksuRootAvailable() }.getOrDefault(false)
        val kernelModuleLoaded = runCatching { apkeSuKernelModuleLoaded() }.getOrDefault(false)
        val driverConnected = ksuVersion != null ||
            ksuDaemonRoot ||
            kernelModuleLoaded ||
            runCatching { apkeSuRootAvailable() }.getOrDefault(false)
        val fallbackRoot = runCatching { rootAvailable() }.getOrDefault(false)
        if (!isManager && driverConnected && fallbackRoot) {
            isManager = runCatching { ensureManagerRegistered() }.getOrDefault(false)
            if (isManager) {
                runCatching { Natives.refreshInfo() }
                ksuVersion = runCatching { Natives.version.takeIf { it > 0 } }.getOrNull()
                ksuDaemonRoot = runCatching { ksuRootAvailable() }.getOrDefault(false)
            }
        }
        val kernelUAPIVersion = ksuVersion?.let { runCatching { Natives.kernelUAPIVersion }.getOrNull() }
        val managerUAPIVersion = runCatching { Natives.managerUAPIVersion }.getOrDefault(0)
        val lateLoadMode = runCatching { Natives.isLateLoadMode }.getOrDefault(false)
        val lkmRuntime = runCatching { Natives.isLkmMode }.getOrDefault(false)
        val lkmMode = ksuVersion?.let {
            if (kernelVersion.isGKI()) lkmRuntime else null
        }
        val builtInGkiMode = driverConnected && kernelVersion.isGKI() && !lkmRuntime && !lateLoadMode
        val hiddenPathLkmMode = lkmMode == true &&
            ksuDaemonRoot &&
            runCatching { isHiddenPathLkmMode() }.getOrDefault(false)
        val managerVersion = getManagerVersion(ksuApp)
        val requiresNewKernel = isManager && runCatching { Natives.requireNewKernel() }.getOrDefault(false)
        val uapiMismatch = isManager && runCatching { Natives.checkUAPIMismatch() }.getOrDefault(false)
        val installedKsud = if (isManager && ksuDaemonRoot) {
            runCatching { getInstalledKsudStatus() }.getOrNull()
        } else {
            null
        }
        val ksudVersionMismatch = installedKsud?.let {
            !it.present || it.versionCode != BuildConfig.VERSION_CODE
        } == true
        val rootRuntimeState = RootRuntimeState.resolve(
            driverConnected = driverConnected,
            managerRegistered = isManager,
            daemonRootAvailable = ksuDaemonRoot,
            blockingVersionMismatch = hasBlockingRootVersionMismatch(
                managerVersionCode = managerVersion.versionCode,
                driverVersion = ksuVersion,
                requiresNewKernel = requiresNewKernel,
                uapiMismatch = uapiMismatch,
            ),
        )
        val kernelHookStatus = if (driverConnected) {
            runCatching { Natives.kernelHookStatus }.getOrDefault(Natives.HOOK_STATUS_UNSUPPORTED)
        } else {
            Natives.HOOK_STATUS_UNSUPPORTED
        }
        val hasTracepoint = if (kernelHookStatus == Natives.HOOK_STATUS_UNSUPPORTED) {
            null
        } else {
            kernelHookStatus and Natives.HOOK_STATUS_TRACEPOINT != 0L
        }
        val rawSeccompStatus = runCatching {
            Os.prctl(21 /* PR_GET_SECCOMP */, 0, 0, 0, 0)
        }.getOrDefault(-1)
        val seccompHookReport = readGkiSeccompHookReport(driverConnected)
        val seccompResolution = resolveSeccompRuntimeStatus(
            rawStatus = rawSeccompStatus,
            hookReport = seccompHookReport,
            kernelVersion = kernelVersion,
            driverConnected = driverConnected,
            lkmRuntime = lkmRuntime,
            lateLoadMode = lateLoadMode,
            kernelUapi = kernelUAPIVersion,
            managerUapi = managerUAPIVersion,
            ksudReady = ksuDaemonRoot && installedKsud?.present == true &&
                installedKsud.versionCode == BuildConfig.VERSION_CODE,
        )
        return HomeUiState(
            kernelVersion = kernelVersion,
            ksuVersion = ksuVersion,
            isKernelActive = driverConnected,
            lkmMode = lkmMode,
            hiddenPathLkmMode = hiddenPathLkmMode,
            isManager = isManager,
            isManagerPrBuild = BuildConfig.IS_PR_BUILD,
            isKernelPrBuild = runCatching { Natives.isPrBuild }.getOrDefault(false),
            requiresNewKernel = requiresNewKernel,
            uapiMismatch = uapiMismatch,
            kernelUAPIVersion = kernelUAPIVersion,
            managerUAPIVersion = managerUAPIVersion,
            isRootAvailable = ksuDaemonRoot,
            rootRuntimeState = rootRuntimeState,
            daemonVersionMismatch = ksudVersionMismatch,
            kernelHookTypes = KernelHookType.resolve(
                hasActiveDriver = driverConnected,
                hasTracepoint = hasTracepoint,
            ),
            isSafeMode = runCatching { Natives.isSafeMode }.getOrDefault(false),
            isLateLoadMode = lateLoadMode,
            currentManagerVersionCode = managerVersion.versionCode,
            showVersionMismatchWarningSetting = repo.showVersionMismatchWarning,
            showGkiWarningSetting = repo.showGkiWarning,
            showHomeSupportCard = repo.showHomeSupportCard,
            showHomeLearnCard = repo.showHomeLearnCard,
            miuixClassicHomeLayoutEnabled = repo.miuixClassicHomeLayoutEnabled,
            customHomeTitle = repo.customHomeTitle,
            superuserCount = runCatching { getSuperuserCount() }.getOrDefault(0),
            moduleCount = runCatching { getModuleCount() }.getOrDefault(0),
            systemInfo = SystemInfo(
                kernelVersion = runCatching { Os.uname().release }.getOrDefault("unknown"),
                managerVersion = "${managerVersion.versionName} (${managerVersion.versionCode})",
                deviceModel = runCatching { resolveDeviceName() }.getOrDefault(Build.MODEL),
                fingerprint = Build.FINGERPRINT,
                selinuxStatus = runCatching { getSELinuxStatusRaw() }.getOrDefault("unknown"),
                seccompStatus = resolveSeccompDisplayStatus(
                    builtInGkiMode = builtInGkiMode,
                    guardedStatus = seccompResolution.status,
                ),
                seccompFailureReason = seccompResolution.failureReason,
                seccompProcessStatus = rawSeccompStatus,
                seccompHookStatus = seccompHookReport.status,
                seccompHookLastError = seccompHookReport.lastError,
                seccompHookCallCount = seccompHookReport.callCount,
                seccompHookReleaseCount = seccompHookReport.releaseCount,
                seccompHookFailureCount = seccompHookReport.failureCount,
            ),
        )
    }

    private fun readGkiSeccompHookReport(driverConnected: Boolean): GkiSeccompHookReport {
        if (!driverConnected) {
            return GkiSeccompHookReport(
                status = Natives.SECCOMP_HOOK_STATUS_UNSUPPORTED,
                lastError = Natives.SECCOMP_HOOK_ERROR_UNSUPPORTED,
                callCount = Natives.SECCOMP_HOOK_COUNT_UNSUPPORTED,
                releaseCount = Natives.SECCOMP_HOOK_COUNT_UNSUPPORTED,
                failureCount = Natives.SECCOMP_HOOK_COUNT_UNSUPPORTED,
            )
        }
        return GkiSeccompHookReport(
            status = runCatching { Natives.gkiSeccompHookStatus }
                .getOrDefault(Natives.SECCOMP_HOOK_STATUS_UNSUPPORTED),
            lastError = runCatching { Natives.gkiSeccompHookLastError }
                .getOrDefault(Natives.SECCOMP_HOOK_ERROR_UNSUPPORTED),
            callCount = runCatching { Natives.gkiSeccompHookCallCount }
                .getOrDefault(Natives.SECCOMP_HOOK_COUNT_UNSUPPORTED),
            releaseCount = runCatching { Natives.gkiSeccompHookReleaseCount }
                .getOrDefault(Natives.SECCOMP_HOOK_COUNT_UNSUPPORTED),
            failureCount = runCatching { Natives.gkiSeccompHookFailureCount }
                .getOrDefault(Natives.SECCOMP_HOOK_COUNT_UNSUPPORTED),
        )
    }

    private suspend fun resolveSeccompRuntimeStatus(
        rawStatus: Int,
        hookReport: GkiSeccompHookReport,
        kernelVersion: KernelVersion,
        driverConnected: Boolean,
        lkmRuntime: Boolean,
        lateLoadMode: Boolean,
        kernelUapi: Int?,
        managerUapi: Int,
        ksudReady: Boolean,
    ): SeccompStatusResolution {
        val capabilities = SeccompCapabilityChecks(
            gki = driverConnected && kernelVersion.isGKI() && !lkmRuntime && !lateLoadMode,
            ko = driverConnected && lkmRuntime && !lateLoadMode,
            gkiHookReady = hookReport.usable,
            gkiHookFailureReason = hookReport.failureReason,
            uapi = kernelUapi != null && kernelUapi > 0 &&
                managerUapi > 0 && kernelUapi == managerUapi,
            ksud = ksudReady,
        )
        val selfCheck = if (capabilities.ready) {
            runSeccompSelfChecks().let {
                SeccompSelfCheckResult(
                    ksud = it.ksud,
                    rootShell = it.rootShell,
                    moduleQuery = it.moduleQuery,
                    failureReason = it.failureReason,
                )
            }
        } else {
            null
        }
        val resolution = resolveSeccompStatus(capabilities, selfCheck)
        if (resolution.status != 2) {
            persistSeccompFailure(resolution.failureReason)
            val shouldRollback = rawStatus == 2 || selfCheck?.allPassed == false
            if (shouldRollback) {
                // The guard is deliberately best effort on older kernels. The
                // status remains disabled even when the optional ioctl is absent.
                runCatching { Natives.disableCurrentSeccomp() }
                    .onSuccess { disabled ->
                        if (!disabled) Log.w(TAG, "kernel rejected Seccomp rollback")
                    }
                    .onFailure { Log.w(TAG, "failed to release Seccomp filter", it) }
                restoreNormalRuntimeState()
            }
        } else if (resolution.status == 2) {
            seccompPrefs.edit()
                .remove(SECCOMP_FAILURE_REASON_KEY)
                .remove(SECCOMP_FAILURE_TIME_KEY)
                .apply()
        }
        val persistedReason = seccompPrefs.getString(SECCOMP_FAILURE_REASON_KEY, "").orEmpty()
        return resolution.copy(failureReason = resolution.failureReason.ifBlank { persistedReason })
    }

    private fun persistSeccompFailure(reason: String) {
        seccompPrefs.edit()
            .putString(SECCOMP_FAILURE_REASON_KEY, reason.ifBlank { "seccomp_self_check_failed" })
            .putLong(SECCOMP_FAILURE_TIME_KEY, System.currentTimeMillis())
            .apply()
    }

    private fun restoreNormalRuntimeState() {
        // Self-checks are read-only; reset only the transient shell/driver
        // caches so the next refresh observes the restored normal state.
        KsuCli.reset()
        runCatching { Natives.refreshInfo() }
    }

    private fun fallbackState(): HomeUiState {
        val managerVersion = runCatching { getManagerVersion(ksuApp) }.getOrNull()
        val managerUAPIVersion = runCatching { Natives.managerUAPIVersion }.getOrDefault(0)
        val ksuVersion = runCatching { Natives.version.takeIf { it > 0 } }.getOrNull()
        val isKernelActive = ksuVersion != null || apkeSuKernelModuleLoaded()
        val kernelVersion = runCatching { getKernelVersion() }.getOrElse { KernelVersion(0, 0, 0) }
        val builtInGkiMode = isKernelActive && kernelVersion.isGKI() &&
            !runCatching { Natives.isLkmMode }.getOrDefault(false) &&
            !runCatching { Natives.isLateLoadMode }.getOrDefault(false)
        val isManager = isKernelActive && runCatching { Natives.isManager }.getOrDefault(false)
        val ksuDaemonRoot = isKernelActive && runCatching { ksuRootAvailable() }.getOrDefault(false)
        val rootRuntimeState = RootRuntimeState.resolve(
            driverConnected = isKernelActive,
            managerRegistered = isManager,
            daemonRootAvailable = ksuDaemonRoot,
            blockingVersionMismatch = false,
        )
        return HomeUiState(
            kernelVersion = kernelVersion,
            ksuVersion = ksuVersion,
            isKernelActive = isKernelActive,
            managerUAPIVersion = managerUAPIVersion,
            kernelUAPIVersion = null,
            lkmMode = null,
            isManager = isManager,
            isManagerPrBuild = BuildConfig.IS_PR_BUILD,
            isKernelPrBuild = false,
            requiresNewKernel = false,
            uapiMismatch = false,
            isRootAvailable = ksuDaemonRoot,
            rootRuntimeState = rootRuntimeState,
            kernelHookTypes = KernelHookType.resolve(hasActiveDriver = isKernelActive),
            isSafeMode = false,
            isLateLoadMode = false,
            currentManagerVersionCode = managerVersion?.versionCode ?: BuildConfig.VERSION_CODE.toLong(),
            showVersionMismatchWarningSetting = runCatching {
                repo.showVersionMismatchWarning
            }.getOrDefault(true),
            showGkiWarningSetting = runCatching { repo.showGkiWarning }.getOrDefault(true),
            showHomeSupportCard = runCatching { repo.showHomeSupportCard }.getOrDefault(true),
            showHomeLearnCard = runCatching { repo.showHomeLearnCard }.getOrDefault(true),
            miuixClassicHomeLayoutEnabled = runCatching {
                repo.miuixClassicHomeLayoutEnabled
            }.getOrDefault(false),
            customHomeTitle = runCatching { repo.customHomeTitle }.getOrDefault(""),
            superuserCount = 0,
            moduleCount = 0,
            systemInfo = SystemInfo(
                kernelVersion = runCatching { Os.uname().release }.getOrDefault("unknown"),
                managerVersion = "${managerVersion?.versionName ?: BuildConfig.VERSION_NAME} (${managerVersion?.versionCode ?: BuildConfig.VERSION_CODE})",
                deviceModel = Build.MODEL,
                fingerprint = Build.FINGERPRINT,
                selinuxStatus = "unknown",
                seccompStatus = resolveSeccompDisplayStatus(
                    builtInGkiMode = builtInGkiMode,
                    guardedStatus = -1,
                ),
            ),
        )
    }

    private fun buildDiagnosticReport(info: me.weishu.kernelsu.ui.util.RootDiagnosticInfo): String {
        val packagedCode = parseVersionCode(info.packagedKsudVersion)
        val installedCode = parseVersionCode(info.installedKsudVersion)
        val packagedVersionMatches = packagedCode == BuildConfig.VERSION_CODE
        val installedVersionMatches = installedCode == BuildConfig.VERSION_CODE
        val daemonVersionMatches = packagedVersionMatches && installedVersionMatches
        val driverConnected = info.driverVersion > 0 || info.kernelModuleLoaded || info.ksuRootShell
        val uapiMismatch = info.kernelUapi > 0 && info.kernelUapi != info.managerUapi
        val requiresNewKernel =
            (info.driverVersion in 1 until Natives.minimalSupportedKernel) || uapiMismatch
        val state = RootRuntimeState.resolve(
            driverConnected = driverConnected,
            managerRegistered = info.managerRegistered,
            daemonRootAvailable = info.ksuRootShell,
            blockingVersionMismatch = hasBlockingRootVersionMismatch(
                managerVersionCode = BuildConfig.VERSION_CODE.toLong(),
                driverVersion = info.driverVersion.takeIf { it > 0 },
                requiresNewKernel = requiresNewKernel,
                uapiMismatch = uapiMismatch,
            ),
        )
        val timestamp = DateFormat.getDateTimeInstance().format(Date())
        val mode = when (info.workMode) {
            "lkm" -> "LKM"
            "gki" -> "GKI"
            "late_load" -> ksuApp.getString(R.string.root_diagnostic_mode_jailbreak)
            else -> ksuApp.getString(R.string.root_diagnostic_unknown)
        }

        return buildString {
            appendLine(ksuApp.getString(R.string.root_diagnostic_title))
            appendLine(ksuApp.getString(R.string.root_diagnostic_time, timestamp))
            appendLine(ksuApp.getString(R.string.root_diagnostic_conclusion, ksuApp.getString(state.labelRes)))
            appendLine()
            appendLine(ksuApp.getString(R.string.root_diagnostic_driver_connection, healthLabel(driverConnected)))
            appendLine(ksuApp.getString(R.string.root_diagnostic_driver_version, info.driverVersion.takeIf { it > 0 } ?: "-"))
            appendLine(
                ksuApp.getString(
                    R.string.root_diagnostic_driver_manager_match,
                    healthLabel(info.driverVersion == BuildConfig.VERSION_CODE),
                )
            )
            appendLine(ksuApp.getString(R.string.root_diagnostic_kernel_module, presentLabel(info.kernelModuleLoaded)))
            appendLine(ksuApp.getString(R.string.root_diagnostic_root_shell, healthLabel(info.ksuRootShell)))
            appendLine(ksuApp.getString(R.string.root_diagnostic_fallback_shell, presentLabel(info.fallbackRootShell)))
            appendLine(ksuApp.getString(R.string.root_diagnostic_manager_registered, healthLabel(info.managerRegistered)))
            appendLine(ksuApp.getString(R.string.root_diagnostic_manager, info.managerPackage, info.managerUid))
            appendLine(ksuApp.getString(R.string.root_diagnostic_uapi, info.managerUapi, info.kernelUapi))
            appendLine()
            val unavailable = ksuApp.getString(R.string.root_diagnostic_unavailable)
            appendLine(ksuApp.getString(R.string.root_diagnostic_packaged_ksud, info.packagedKsudVersion.ifBlank { unavailable }))
            appendLine(ksuApp.getString(R.string.root_diagnostic_installed_ksud, info.installedKsudVersion.ifBlank { unavailable }))
            appendLine(ksuApp.getString(R.string.root_diagnostic_packaged_match, healthLabel(packagedVersionMatches)))
            appendLine(ksuApp.getString(R.string.root_diagnostic_installed_match, healthLabel(installedVersionMatches)))
            appendLine(ksuApp.getString(R.string.root_diagnostic_daemon_match, healthLabel(daemonVersionMatches)))
            appendLine(ksuApp.getString(R.string.root_diagnostic_kmi, info.currentKmi.ifBlank { "-" }))
            appendLine(
                ksuApp.getString(
                    R.string.root_diagnostic_current_slot,
                    info.currentSlot.ifBlank { ksuApp.getString(R.string.root_diagnostic_no_slot) },
                )
            )
            appendLine(ksuApp.getString(R.string.root_diagnostic_work_mode, mode))
            appendLine(ksuApp.getString(R.string.root_diagnostic_hidden_path_lkm, presentLabel(info.hiddenPathLkm)))
            if (state == RootRuntimeState.ManagerUnregistered) {
                appendLine()
                if (info.driverVersion > 0 && info.driverVersion != BuildConfig.VERSION_CODE) {
                    appendLine(
                        ksuApp.getString(
                            R.string.root_diagnostic_fix_version_mismatch,
                            info.driverVersion,
                            BuildConfig.VERSION_CODE,
                        )
                    )
                    appendLine(ksuApp.getString(R.string.root_diagnostic_fix_repatch))
                } else {
                    appendLine(ksuApp.getString(R.string.root_diagnostic_fix_identity))
                }
            }
        }.trimEnd()
    }

    private fun parseVersionCode(raw: String): Int? {
        return runCatching { JSONObject(raw).optString("versionCode").toIntOrNull() }.getOrNull()
    }

    private fun healthLabel(value: Boolean): String = ksuApp.getString(
        if (value) R.string.root_diagnostic_normal else R.string.root_diagnostic_abnormal
    )

    private fun presentLabel(value: Boolean): String = ksuApp.getString(
        if (value) R.string.root_diagnostic_present else R.string.root_diagnostic_absent
    )

    private companion object {
        const val TAG = "HomeViewModel"
    }
}
