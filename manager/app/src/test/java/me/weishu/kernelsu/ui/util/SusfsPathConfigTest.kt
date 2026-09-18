package me.weishu.kernelsu.ui.util

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class SusfsPathConfigTest {
    @Test
    fun normalizesAbsolutePathsWithoutChangingTheirContents() {
        assertEquals("/data/local/tmp/example", normalizeSusfsPath("  /data/local/tmp/example/  "))
        assertEquals("/storage/emulated/0/Android/data/example", normalizeSusfsPath("/storage/emulated/0/Android/data/example"))
        assertEquals("/data/adb/custom", normalizeSusfsPath("/data/adb/custom"))
    }

    @Test
    fun rejectsRootRelativeAndControlCharacterPaths() {
        assertNull(normalizeSusfsPath("/"))
        assertNull(normalizeSusfsPath("data/local/tmp"))
        assertNull(normalizeSusfsPath("/data/local/tmp\nnext"))
        assertNull(normalizeSusfsPath("/data/adb/modules"))
        assertNull(normalizeSusfsPath("/data/adb/ksu/bin"))
        assertNull(normalizeSusfsPath("/data/adb/ap"))
        assertNull(normalizeSusfsPath(""))
    }

    @Test
    fun bootServiceWaitsForSusfsAndRetriesFailedRestores() {
        val service = susfsPathServiceScript()

        assertTrue(service.contains("find_tool()"))
        assertTrue(service.contains("show enabled_features"))
        assertTrue(service.contains("PROBED=0"))
        assertTrue(service.contains("PROBE_SUPPORTED=0"))
        assertTrue(service.contains("FEATURE_PROBE_OK=0"))
        assertTrue(service.contains("while [ \"\$attempt\" -lt 30 ]; do"))
        assertTrue(service.contains("while [ \"\$storage_attempt\" -lt 100 ]"))
        assertTrue(service.contains("set_sdcard_root_path /sdcard"))
        assertTrue(service.contains("set_android_data_root_path /sdcard/Android/data"))
        assertTrue(service.contains("/storage/emulated/*"))
        assertTrue(service.contains("apply_path_file \"\$CONFIG\" add_sus_path || failed=1"))
        assertTrue(service.contains("\"\$TOOL\" \"\$command\" \"\$target_path\" >/dev/null 2>&1"))
        assertTrue(service.contains("sleep 1"))
        assertFalse(service.contains("\\$("))
        assertFalse(service.contains("\\\""))
    }

    @Test
    fun bootServiceIsValidPosixShell() {
        val shell = File("/bin/sh")
        val script = Files.createTempFile("apkesu-susfs-paths-", ".sh")
        try {
            Files.write(script, susfsPathServiceScript().toByteArray())
            val process = when {
                shell.canExecute() -> ProcessBuilder(shell.path, "-n", script.toString()).start()
                System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true) -> {
                    val windowsPath = script.toAbsolutePath().toString()
                    val wslPath = "/mnt/${windowsPath[0].lowercaseChar()}/" +
                        windowsPath.substring(3).replace('\\', '/')
                    runCatching { ProcessBuilder("wsl", "-e", "sh", "-n", wslPath).start() }.getOrNull()
                }
                else -> null
            }
            assumeTrue(process != null)
            val checkedProcess = requireNotNull(process)
            val error = checkedProcess.errorStream.bufferedReader().use { it.readText() }
            assertEquals(error, 0, checkedProcess.waitFor())
        } finally {
            Files.deleteIfExists(script)
        }
    }

    @Test
    fun parsesR28VersionAndFeatureNames() {
        assertEquals(SusfsVersion(1, 5, 2), parseSusfsVersion("v1.5.2-R28"))
        assertEquals(
            setOf("CONFIG_KSU_SUSFS_SUS_PATH", "CONFIG_KSU_SUSFS_TRY_UMOUNT"),
            parseSusfsFeatureNames("CONFIG_KSU_SUSFS_SUS_PATH\nCONFIG_KSU_SUSFS_TRY_UMOUNT"),
        )
    }

    @Test
    fun reportsR28CapabilitiesWithoutTreatingToolPresenceAsFullSupport() {
        val capabilities = buildSusfsCapabilities(
            toolAvailable = true,
            versionText = "v1.5.2-R28",
            featureText = "CONFIG_KSU_SUSFS_SUS_PATH CONFIG_KSU_SUSFS_TRY_UMOUNT CONFIG_KSU_SUSFS_SPOOF_UNAME",
            featureProbeSucceeded = true,
        )

        assertTrue(capabilities.featureProbeAvailable)
        assertTrue(capabilities.supportsAddSusPath)
        assertTrue(capabilities.supportsTryUmount)
        assertFalse(capabilities.supportsPathLoop)
        assertFalse(capabilities.supportsKstat)
        assertFalse(capabilities.supportsOpenRedirect)
        assertTrue(capabilities.supportsUnameSpoof)
        assertFalse(capabilities.supportsCmdlineSpoof)
    }

    @Test
    fun legacyToolKeepsPathCompatibilityWhenFeatureProbeIsUnavailable() {
        val capabilities = buildSusfsCapabilities(
            toolAvailable = true,
            versionText = "",
            featureText = "",
            featureProbeSucceeded = false,
        )

        assertFalse(capabilities.featureProbeAvailable)
        assertTrue(capabilities.supportsAddSusPath)
    }

    @Test
    fun explicitFeatureProbeCanRejectMissingPathSupport() {
        val capabilities = buildSusfsCapabilities(
            toolAvailable = true,
            versionText = "v2.1.0",
            featureText = "CONFIG_KSU_SUSFS_TRY_UMOUNT",
            featureProbeSucceeded = true,
        )

        assertFalse(capabilities.supportsAddSusPath)
    }

    @Test
    fun successfulEmptyFeatureProbeDoesNotFallBackToLegacySupport() {
        val capabilities = buildSusfsCapabilities(
            toolAvailable = true,
            versionText = "v2.1.0",
            featureText = "",
            featureProbeSucceeded = true,
        )

        assertTrue(capabilities.featureProbeAvailable)
        assertFalse(capabilities.supportsAddSusPath)
        assertFalse(capabilities.supportsPathLoop)
        assertFalse(capabilities.supportsTryUmount)
        assertFalse(capabilities.supportsKstat)
    }

    @Test
    fun preparesLegacyExternalStorageRootsOnlyForAffectedVersionsAndPaths() {
        val externalPaths = listOf(
            "/sdcard/Android/data/example",
            "/storage/emulated/0/Android/data/example",
            "/storage/self/primary/Android/data/example",
        )

        externalPaths.forEach { path ->
            assertTrue(shouldPrepareSusfsExternalStorageRoots("v1.5.8", listOf(path)))
            assertTrue(shouldPrepareSusfsExternalStorageRoots("v2.0.0", listOf(path)))
            assertFalse(shouldPrepareSusfsExternalStorageRoots("v1.5.7", listOf(path)))
            assertFalse(shouldPrepareSusfsExternalStorageRoots("v2.1.0", listOf(path)))
        }
        assertFalse(
            shouldPrepareSusfsExternalStorageRoots(
                "v1.5.8",
                listOf("/data/local/tmp/example"),
            ),
        )
        assertFalse(shouldPrepareSusfsExternalStorageRoots("unknown", externalPaths))
    }

    @Test
    fun bootServiceLimitsLegacyExternalRootSetupToSupportedVersionRange() {
        val service = susfsPathServiceScript()

        assertTrue(service.contains("[ \"\$version_code\" -ge 10508 ]"))
        assertTrue(service.contains("[ \"\$version_code\" -lt 20100 ]"))
    }

    @Test
    fun importsBackupWithUnameAndSkipsManagementPaths() {
        val result = parseSusfsBackupJson(
            """
            {
              "version": 2,
              "enabled": true,
              "avc_log_spoofing": true,
              "hide_sus_mnts_for_non_su_procs": true,
              "uname": {"release": "6.12-test", "version": "#1 SMP PREEMPT"},
              "sus_path": [
                {"path": "/data/adb/modules", "is_loop": true},
                {"path": "/system/bin/su", "is_loop": true}
              ],
              "sus_map": ["/data/adb/modules/example"]
            }
            """.trimIndent(),
        )

        val config = requireNotNull(result.config)
        assertEquals("6.12-test", config.unameRelease)
        assertEquals("#1 SMP PREEMPT", config.unameVersion)
        assertEquals(listOf("/system/bin/su"), config.loopPaths)
        assertEquals(listOf("/data/adb/modules/example"), config.susMaps)
        assertTrue(result.warnings.any { it.contains("/data/adb/modules") })
    }

    @Test
    fun backupRoundTripPreservesUnameAndRuntimePolicy() {
        val original = SusfsPathConfigState(
            paths = listOf("/data/local/tmp/example"),
            loopPaths = listOf("/system/bin/su"),
            susMaps = listOf("/data/local/tmp/library"),
            enabled = true,
            logging = false,
            avcLogSpoofing = true,
            hideSusMntsForNonSuProcs = true,
            unameRelease = "6.12-test",
            unameVersion = "#1 SMP PREEMPT",
        )

        val restored = requireNotNull(parseSusfsBackupJson(buildSusfsBackupJson(original)).config)
        assertEquals(original.paths, restored.paths)
        assertEquals(original.loopPaths, restored.loopPaths)
        assertEquals(original.susMaps, restored.susMaps)
        assertEquals(original.unameRelease, restored.unameRelease)
        assertEquals(original.unameVersion, restored.unameVersion)
        assertEquals(original.avcLogSpoofing, restored.avcLogSpoofing)
        assertEquals(original.hideSusMntsForNonSuProcs, restored.hideSusMntsForNonSuProcs)
    }

    @Test
    fun serviceReplaysUnameSpoofing() {
        val service = susfsPathServiceScript()

        assertTrue(service.contains("read_setting uname_release"))
        assertTrue(service.contains("read_setting uname_version"))
        assertTrue(service.contains("set_uname \"\${release:-default}\" \"\${build:-default}\""))
    }
}
