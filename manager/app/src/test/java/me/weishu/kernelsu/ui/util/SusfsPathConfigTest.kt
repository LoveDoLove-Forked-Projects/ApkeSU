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
        assertEquals("/data/local/example", normalizeSusfsPath("/data//local/./tmp/../example"))
        assertNull(normalizeSusfsPath("/data/../../system"))
        assertEquals("/data/local/é", normalizeSusfsPath("/data/local/é"))
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
        assertEquals("/" + "a".repeat(254), normalizeSusfsPath("/" + "a".repeat(254)))
        assertNull(normalizeSusfsPath("/" + "a".repeat(255)))
    }

    @Test
    fun bootServiceWaitsForSusfsAndRetriesFailedRestores() {
        val service = susfsPathServiceScript()

        assertTrue(service.contains("find_tool()"))
        assertTrue(service.contains("show enabled_features"))
        assertTrue(service.contains("PROBED=0"))
        assertTrue(service.contains("PROBE_SUPPORTED=0"))
        assertTrue(service.contains("FEATURE_PROBE_OK=0"))
        assertTrue(service.contains("while [ \"\$attempt\" -lt \"\$MAX_ATTEMPTS\" ]; do"))
        assertTrue(service.contains("while [ \"\$storage_attempt\" -lt \"\$STORAGE_ATTEMPTS\" ]"))
        assertTrue(service.contains("if [ \"\$MODE\" = \"--immediate\" ]; then"))
        assertTrue(service.contains("set_sdcard_root_path /sdcard"))
        assertTrue(service.contains("set_android_data_root_path /sdcard/Android/data"))
        assertTrue(service.contains("/storage/emulated/*"))
        assertTrue(service.contains("apply_path_file \"\$CONFIG\" add_sus_path path 1"))
        assertTrue(service.contains("run_tool \"\$category\" \"\$target_path\" \"\$command\""))
        assertTrue(service.contains("write_status"))
        assertTrue(service.contains("generation_is_current()"))
        assertTrue(service.contains("generation_is_current || return 125"))
        assertTrue(service.contains("discarded stale status for generation"))
        assertTrue(service.contains("if [ \"\$enabled\" != \"1\" ]; then"))
        assertTrue(service.contains("probe_tool || true"))
        assertTrue(service.contains("apply_settings"))
        assertTrue(service.contains("enable_log \"\$logging_value\""))
        assertTrue(service.contains("enable_avc_log_spoofing \"\$avc_value\""))
        assertTrue(service.contains("hide_sus_mnts_for_non_su_procs \"\$hide_value\""))
        assertTrue(service.contains("[ \"\$FEATURE_PROBE_OK\" -eq 0 ] && supports_version 20000"))
        assertTrue(service.contains("[ \"\$FEATURE_PROBE_OK\" -eq 0 ] && supports_version 10500"))
        assertTrue(service.contains("[ \"\$FEATURE_PROBE_OK\" -eq 0 ] && supports_version 10504"))
        assertTrue(service.contains("[ \"\$FEATURE_PROBE_OK\" -eq 0 ] && supports_version 10507"))
        assertTrue(service.contains("[ \"\$MODE\" = \"--immediate\" ] || REQUIRES_REBOOT=0"))
        assertTrue(service.contains("[ \"\$SKIPPED_COUNT\" -gt 0 ]"))
        assertFalse(service.contains("hide_sus_mnts_for_all_procs"))
        assertTrue(service.contains("command -v ksu_susfs"))
        assertTrue(service.contains("0\$mode & 022"))
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
            setOf("CONFIG_KSU_SUSFS_SUS_PATH", "CONFIG_KSU_SUSFS_SUS_MOUNT"),
            parseSusfsFeatureNames(
                "[enabled] CONFIG_KSU_SUSFS_SUS_PATH=y\nCONFIG_KSU_SUSFS_SUS_MOUNT: y",
            ),
        )
    }

    @Test
    fun reportsR28CapabilitiesWithoutTreatingToolPresenceAsFullSupport() {
        val capabilities = buildSusfsCapabilities(
            toolAvailable = true,
            versionText = "v1.5.2-R28",
            featureText = "CONFIG_KSU_SUSFS_SUS_PATH CONFIG_KSU_SUSFS_SUS_MOUNT CONFIG_KSU_SUSFS_SPOOF_UNAME",
            featureProbeSucceeded = true,
        )

        assertTrue(capabilities.featureProbeAvailable)
        assertTrue(capabilities.supportsAddSusPath)
        assertTrue(capabilities.supportsTryUmount)
        assertTrue(capabilities.supportsPathLoop)
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
    fun successfulEmptyFeatureProbeFallsBackToVersionCapabilities() {
        val capabilities = buildSusfsCapabilities(
            toolAvailable = true,
            versionText = "v2.1.0",
            featureText = "",
            featureProbeSucceeded = true,
        )

        assertFalse(capabilities.featureProbeAvailable)
        assertTrue(capabilities.supportsAddSusPath)
        assertTrue(capabilities.supportsPathLoop)
        assertTrue(capabilities.supportsTryUmount)
        assertTrue(capabilities.supportsKstat)
    }

    @Test
    fun readsLegacyModuleRuntimePolicyWhenManagerSettingsAreMissing() {
        val state = parseSusfsConfigOutput(
            listOf(
                "__TOOL__=/data/adb/ksu/bin/ksu_susfs",
                "__LEGACY__susfs_log=1",
                "__LEGACY__avc_log_spoofing='true'",
                "__LEGACY__hide_sus_mnts_for_all_or_non_su_procs=2",
            ),
            buildSusfsCapabilities(true, "v2.1.0", "", false),
        )

        assertTrue(state.logging)
        assertTrue(state.avcLogSpoofing)
        assertTrue(state.hideSusMntsForNonSuProcs)
    }

    @Test
    fun managerRuntimePolicyOverridesLegacyModulePolicy() {
        val state = parseSusfsConfigOutput(
            listOf(
                "__TOOL__=/data/adb/ksu/bin/ksu_susfs",
                "__SETTING__logging=0",
                "__SETTING__avc_log_spoofing=0",
                "__SETTING__hide_sus_mnts_for_non_su_procs=0",
                "__LEGACY__susfs_log=1",
                "__LEGACY__avc_log_spoofing=1",
                "__LEGACY__hide_sus_mnts_for_all_or_non_su_procs=1",
            ),
            buildSusfsCapabilities(true, "v2.1.0", "", false),
        )

        assertFalse(state.logging)
        assertFalse(state.avcLogSpoofing)
        assertFalse(state.hideSusMntsForNonSuProcs)
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
        assertTrue(config.openRedirects.isEmpty())
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

    @Test
    fun rejectsUnsupportedBackupVersionAndInvalidUidScheme() {
        assertEquals(
            "unsupported_backup_version:3",
            parseSusfsBackupJson("{\"version\":3}").error,
        )
        val result = parseSusfsBackupJson(
            """
            {"version":2,"open_redirect":[
              {"original":"/data/a","redirected":"/data/b","uid_scheme":"9"},
              {"original":"/data/c","redirected":"/data/d"}
            ]}
            """.trimIndent(),
        )
        val config = requireNotNull(result.config)
        assertEquals(1, config.openRedirects.size)
        assertEquals("3", config.openRedirects.single().uidScheme)
        assertTrue(result.warnings.any { it == "skipped_open_redirect_uid" })
    }

    @Test
    fun importSkipsIncompleteKstatAndSizeLimitIncludesKstatPayload() {
        val imported = parseSusfsBackupJson(
            """{"version":2,"sus_kstat":[{"args":["/data/a","1"]}]}""",
        )
        assertTrue(requireNotNull(imported.config).kstatEntries.isEmpty())
        assertTrue(imported.warnings.contains("skipped_sus_kstat"))

        val largeValue = "a".repeat(4096)
        val oversized = SusfsPathConfigState(
            kstatEntries = List(6) {
                SusfsKstatEntry(listOf("/data/item-$it") + List(12) { largeValue })
            },
        )
        assertEquals("config_too_large", validateSusfsConfig(oversized))
    }

    @Test
    fun mergesWithoutDuplicatingEntriesAndTracksRebootOnlyForRemoval() {
        val current = SusfsPathConfigState(
            paths = listOf("/data/a"),
            enabled = true,
        )
        val imported = SusfsPathConfigState(
            paths = listOf("/data/a", "/data/b"),
            enabled = false,
        )
        val merged = mergeSusfsConfig(current, imported)
        assertEquals(listOf("/data/a", "/data/b"), merged.paths)
        assertFalse(merged.enabled)
        assertFalse(susfsRequiresReboot(current, imported.copy(enabled = true)))
        assertTrue(susfsRequiresReboot(current, imported))
        assertTrue(susfsRequiresReboot(imported.copy(enabled = true), current))
    }

    @Test
    fun parsesStructuredRuntimeStatusAndIssues() {
        val state = parseSusfsConfigOutput(
            listOf(
                "__TOOL__=/system/bin/ksu_susfs",
                "__SETTING__enabled=1",
                "__SETTING__generation=g1",
                "__STATUS__generation=g1",
                "__STATUS__state=partial",
                "__STATUS__configured_count=4",
                "__STATUS__applied_count=2",
                "__STATUS__failed_count=1",
                "__STATUS__requires_reboot=1",
                "__ISSUE__=failed\tpath\t/data/a\texit_1",
            ),
            buildSusfsCapabilities(true, "v2.3.0", "CONFIG_KSU_SUSFS_SUS_PATH", true),
        )
        assertEquals("partial", state.runtimeStatus.state)
        assertEquals(4, state.runtimeStatus.configuredCount)
        assertEquals(1, state.runtimeStatus.failedCount)
        assertTrue(state.runtimeStatus.requiresReboot)
        assertEquals("/data/a", state.runtimeStatus.issues.single().target)
    }

    @Test
    fun currentConfigMigratesLegacyRedirectSchemeAndRejectsPathModeDuplicates() {
        val state = parseSusfsConfigOutput(
            listOf(
                "__TOOL__=/system/bin/ksu_susfs",
                "__SETTING__enabled=1",
                "__REDIRECT__=/data/source|/data/target",
                "__KSTAT__=/data/incomplete|1",
            ),
            buildSusfsCapabilities(
                true,
                "v2.3.0",
                "CONFIG_KSU_SUSFS_SUS_PATH CONFIG_KSU_SUSFS_OPEN_REDIRECT",
                true,
            ),
        )
        assertEquals("3", state.openRedirects.single().uidScheme)
        assertTrue(state.kstatEntries.isEmpty())
        assertEquals(
            "duplicate_path_mode",
            validateSusfsConfig(
                SusfsPathConfigState(paths = listOf("/data/a"), loopPaths = listOf("/data/a")),
            ),
        )
    }

    @Test
    fun ignoresRuntimeStatusFromAnOlderGeneration() {
        val state = parseSusfsConfigOutput(
            listOf(
                "__TOOL__=/system/bin/ksu_susfs",
                "__SETTING__enabled=1",
                "__SETTING__generation=new-generation",
                "__SETTING__requires_reboot=1",
                "__STATUS__generation=old-generation",
                "__STATUS__state=applied",
                "__STATUS__applied_count=7",
                "__STATUS__requires_reboot=0",
                "__ISSUE__=failed\tpath\t/data/stale\texit_1",
            ),
            buildSusfsCapabilities(true, "v2.3.0", "CONFIG_KSU_SUSFS_SUS_PATH", true),
        )

        assertEquals("new-generation", state.runtimeStatus.generation)
        assertEquals("pending", state.runtimeStatus.state)
        assertEquals(0, state.runtimeStatus.appliedCount)
        assertTrue(state.runtimeStatus.requiresReboot)
        assertTrue(state.runtimeStatus.issues.isEmpty())
    }

    @Test
    fun treatsDisabledAndRebootPendingAsSuccessfulSavedStates() {
        assertTrue(isSusfsApplyStateSuccessful("applied"))
        assertTrue(isSusfsApplyStateSuccessful("reboot_pending"))
        assertTrue(isSusfsApplyStateSuccessful("disabled"))
        assertFalse(isSusfsApplyStateSuccessful("partial"))
        assertFalse(isSusfsApplyStateSuccessful("failed"))
    }
}
