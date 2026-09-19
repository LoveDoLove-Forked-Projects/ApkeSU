package me.weishu.kernelsu.ui.screen.home

import me.weishu.kernelsu.KernelVersion
import me.weishu.kernelsu.Natives
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeUiStateTest {
    @Test
    fun rootRuntimeStateKeepsEachFailureDistinct() {
        assertEquals(
            RootRuntimeState.DriverDisconnected,
            RootRuntimeState.resolve(
                driverConnected = false,
                managerRegistered = false,
                daemonRootAvailable = false,
                blockingVersionMismatch = false,
            ),
        )
        assertEquals(
            RootRuntimeState.ManagerUnregistered,
            RootRuntimeState.resolve(
                driverConnected = true,
                managerRegistered = false,
                daemonRootAvailable = true,
                blockingVersionMismatch = false,
            ),
        )
        assertEquals(
            RootRuntimeState.DaemonError,
            RootRuntimeState.resolve(
                driverConnected = true,
                managerRegistered = true,
                daemonRootAvailable = false,
                blockingVersionMismatch = false,
            ),
        )
        assertEquals(
            RootRuntimeState.VersionMismatch,
            RootRuntimeState.resolve(
                driverConnected = true,
                managerRegistered = true,
                daemonRootAvailable = true,
                blockingVersionMismatch = true,
            ),
        )
        assertEquals(
            RootRuntimeState.Running,
            RootRuntimeState.resolve(
                driverConnected = true,
                managerRegistered = true,
                daemonRootAvailable = true,
                blockingVersionMismatch = false,
            ),
        )
    }

    @Test
    fun incompatibleKernelOrOlderManagerBlocksRunningState() {
        assertTrue(
            hasBlockingRootVersionMismatch(
                managerVersionCode = 32699,
                driverVersion = 32700,
                requiresNewKernel = false,
                uapiMismatch = false,
            )
        )
        assertTrue(
            hasBlockingRootVersionMismatch(
                managerVersionCode = 32700,
                driverVersion = 32700,
                requiresNewKernel = true,
                uapiMismatch = false,
            )
        )
        assertTrue(
            hasBlockingRootVersionMismatch(
                managerVersionCode = 32700,
                driverVersion = 32700,
                requiresNewKernel = false,
                uapiMismatch = true,
            )
        )
        assertFalse(
            hasBlockingRootVersionMismatch(
                managerVersionCode = 32700,
                driverVersion = 32699,
                requiresNewKernel = false,
                uapiMismatch = false,
            )
        )
    }

    @Test
    fun uapiMismatchDoesNotAlsoClaimKernelVersionIsTooLow() {
        val mismatch = homeState(
            requiresNewKernel = true,
            uapiMismatch = true,
        )

        assertTrue(mismatch.showUAPIMisMatchWarning)
        assertFalse(mismatch.showRequireKernelWarning)

        val oldKernel = homeState(
            requiresNewKernel = true,
            uapiMismatch = false,
        )

        assertFalse(oldKernel.showUAPIMisMatchWarning)
        assertTrue(oldKernel.showRequireKernelWarning)
    }

    @Test
    fun seccompFilterRequiresCapabilitiesAndAllSelfChecks() {
        val ready = SeccompCapabilityChecks(
            gki = true,
            ko = false,
            gkiHookReady = true,
            uapi = true,
            ksud = true,
        )

        assertEquals(
            SeccompStatusResolution(0, "gki_ko_capability_failed"),
            resolveSeccompStatus(
                capabilities = ready.copy(gki = false),
                selfCheck = null,
            ),
        )
        assertEquals(
            SeccompStatusResolution(0, "module query self-check failed"),
            resolveSeccompStatus(
                capabilities = ready,
                selfCheck = SeccompSelfCheckResult(
                    ksud = true,
                    rootShell = true,
                    moduleQuery = false,
                    failureReason = "module query self-check failed",
                ),
            ),
        )
        assertEquals(
            SeccompStatusResolution(2),
            resolveSeccompStatus(
                capabilities = ready,
                selfCheck = SeccompSelfCheckResult(
                    ksud = true,
                    rootShell = true,
                    moduleQuery = true,
                ),
            ),
        )
    }

    @Test
    fun builtInGkiSeccompDisplayIsForcedToFilterMode() {
        assertEquals(2, resolveSeccompDisplayStatus(builtInGkiMode = true, guardedStatus = 0))
        assertEquals(2, resolveSeccompDisplayStatus(builtInGkiMode = true, guardedStatus = -1))
        assertEquals(0, resolveSeccompDisplayStatus(builtInGkiMode = false, guardedStatus = 0))
        assertEquals(-1, resolveSeccompDisplayStatus(builtInGkiMode = false, guardedStatus = -1))
    }

    @Test
    fun gkiSeccompHookReportSeparatesCapabilityFromCurrentProcessMode() {
        val readyStatus = Natives.SECCOMP_HOOK_STATUS_KERNEL_SUPPORTED or
            Natives.SECCOMP_HOOK_STATUS_CONFIG_ENABLED or
            Natives.SECCOMP_HOOK_STATUS_INITIALIZED or
            Natives.SECCOMP_HOOK_STATUS_READY
        val ready = GkiSeccompHookReport(
            status = readyStatus,
            lastError = 0,
            callCount = 3,
            releaseCount = 1,
            failureCount = 0,
        )

        assertTrue(ready.usable)
        assertEquals("", ready.failureReason)

        val failed = ready.copy(
            status = readyStatus or Natives.SECCOMP_HOOK_STATUS_LAST_CALL_FAILED,
            lastError = -12,
            failureCount = 1,
        )
        assertFalse(failed.usable)
        assertEquals("gki_seccomp_hook_runtime_failed:-12", failed.failureReason)

        val unsupported = ready.copy(status = Natives.SECCOMP_HOOK_STATUS_UNSUPPORTED)
        assertFalse(unsupported.usable)
        assertEquals("gki_seccomp_hook_status_unavailable", unsupported.failureReason)
    }

    @Test
    fun stealthModeStateDoesNotExposeRootOrKernelSuDetails() {
        val real = homeState(requiresNewKernel = false, uapiMismatch = false).copy(
            rootRuntimeState = RootRuntimeState.Running,
            superuserCount = 7,
            moduleCount = 4,
            kernelHookTypes = listOf(KernelHookType.Tracepoint),
            systemInfo = homeState(false, false).systemInfo.copy(
                seccompStatus = 2,
                seccompFailureReason = "private failure",
                kpm = "native-gki",
                susfs = "enabled",
            ),
        )

        val hidden = real.asStealthModeState()

        assertNull(hidden.ksuVersion)
        assertFalse(hidden.isKernelActive)
        assertFalse(hidden.isManager)
        assertFalse(hidden.isRootAvailable)
        assertEquals(RootRuntimeState.DriverDisconnected, hidden.rootRuntimeState)
        assertEquals(0, hidden.superuserCount)
        assertEquals(0, hidden.moduleCount)
        assertTrue(hidden.kernelHookTypes.isEmpty())
        assertEquals(0, hidden.systemInfo.seccompStatus)
        assertEquals("", hidden.systemInfo.seccompFailureReason)
        assertEquals("", hidden.systemInfo.kpm)
        assertEquals("", hidden.systemInfo.susfs)
    }

    private fun homeState(
        requiresNewKernel: Boolean,
        uapiMismatch: Boolean,
    ) = HomeUiState(
        kernelVersion = KernelVersion(6, 1, 0),
        ksuVersion = 32720,
        managerUAPIVersion = 2,
        kernelUAPIVersion = if (uapiMismatch) 1 else 2,
        lkmMode = true,
        isManager = true,
        isManagerPrBuild = false,
        isKernelPrBuild = false,
        requiresNewKernel = requiresNewKernel,
        uapiMismatch = uapiMismatch,
        isRootAvailable = true,
        isSafeMode = false,
        isLateLoadMode = false,
        currentManagerVersionCode = 32720,
        showVersionMismatchWarningSetting = true,
        superuserCount = 0,
        moduleCount = 0,
        systemInfo = SystemInfo(
            kernelVersion = "6.1.0",
            managerVersion = "2.7.1 (32720)",
            deviceModel = "test",
            fingerprint = "test",
            selinuxStatus = "Enforcing",
            seccompStatus = 2,
        ),
    )
}
