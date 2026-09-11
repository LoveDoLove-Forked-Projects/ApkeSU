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
        assertTrue(service.contains("add_sus_path \"\$target_path\" >/dev/null 2>&1 || failed=1"))
        assertTrue(service.contains("sleep 1"))
        assertFalse(service.contains("\\$("))
        assertFalse(service.contains("\\\""))
    }

    @Test
    fun bootServiceIsValidPosixShell() {
        val shell = File("/bin/sh")
        assumeTrue(shell.canExecute())
        val script = Files.createTempFile("apkesu-susfs-paths-", ".sh")
        try {
            Files.write(script, susfsPathServiceScript().toByteArray())
            val process = ProcessBuilder(shell.path, "-n", script.toString()).start()
            val error = process.errorStream.bufferedReader().use { it.readText() }
            assertEquals(error, 0, process.waitFor())
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
            featureText = "CONFIG_KSU_SUSFS_SUS_PATH CONFIG_KSU_SUSFS_TRY_UMOUNT",
            featureProbeSucceeded = true,
        )

        assertTrue(capabilities.featureProbeAvailable)
        assertTrue(capabilities.supportsAddSusPath)
        assertTrue(capabilities.supportsTryUmount)
        assertFalse(capabilities.supportsPathLoop)
        assertFalse(capabilities.supportsKstat)
        assertFalse(capabilities.supportsOpenRedirect)
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
}
