package me.weishu.kernelsu.ui.screen.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsFeatureAvailabilityTest {
    @Test
    fun lateLoadModeDisablesPathConfigAndKpatchNext() {
        val state = SettingsUiState(
            isLkmMode = false,
            isLateLoadMode = true,
            runtimeModeResolved = true,
            isKPatchNextEnabled = true,
            isKPatchNextWebUiAvailable = true,
        )

        assertEquals(PathConfigBackend.Disabled, state.pathConfigBackend)
        assertFalse(state.canOpenPathConfig)
        assertFalse(state.canToggleKPatchNext)
        assertFalse(state.canOpenKPatchNextWebUi)
    }

    @Test
    fun lkmModeUsesPathmaskConfig() {
        val state = SettingsUiState(isLkmMode = true, runtimeModeResolved = true)

        assertEquals(PathConfigBackend.PathmaskLkm, state.pathConfigBackend)
        assertTrue(state.canOpenPathConfig)
        assertFalse(state.isGkiMode)
    }

    @Test
    fun lateLoadModeWinsWhenBothModeFlagsAreSet() {
        val state = SettingsUiState(
            isLkmMode = true,
            isLateLoadMode = true,
            runtimeModeResolved = true,
            isKPatchNextEnabled = true,
        )

        assertEquals(PathConfigBackend.Disabled, state.pathConfigBackend)
        assertFalse(state.canOpenPathConfig)
        assertFalse(state.canToggleKPatchNext)
    }

    @Test
    fun builtInGkiModeUsesSusfsConfig() {
        val state = SettingsUiState(
            isLkmMode = false,
            isLateLoadMode = false,
            runtimeModeResolved = true,
        )

        assertEquals(PathConfigBackend.SusfsGki, state.pathConfigBackend)
        assertTrue(state.canOpenPathConfig)
        assertTrue(state.isGkiMode)
    }

    @Test
    fun builtInGkiModeDisablesKpatchNextControls() {
        val state = SettingsUiState(
            isLkmMode = false,
            isLateLoadMode = false,
            runtimeModeResolved = true,
            isKPatchNextEnabled = true,
            isKPatchNextWebUiAvailable = true,
        )

        assertFalse(state.canToggleKPatchNext)
        assertFalse(state.canOpenKPatchNextWebUi)
    }

    @Test
    fun nativeGkiCapabilityShowsItsOwnKpmEntry() {
        val state = SettingsUiState(
            isLkmMode = false,
            isLateLoadMode = false,
            runtimeModeResolved = true,
            kpmBackend = "native-gki",
            isKpmManagementAvailable = true,
            isKpmCapabilityResolved = true,
        )

        assertTrue(state.isKpmSettingsEntryVisible)
    }

    @Test
    fun staleKpatchStateCannotExposeKpmEntryInGkiMode() {
        val state = SettingsUiState(
            isLkmMode = false,
            isLateLoadMode = false,
            runtimeModeResolved = true,
            isKPatchNextEnabled = true,
            kpmBackend = "kpatch-next",
            isKpmManagementAvailable = true,
            isKpmCapabilityResolved = true,
        )

        assertFalse(state.isKpmSettingsEntryVisible)
    }

    @Test
    fun unresolvedKpmCapabilityDoesNotExposeSettingsEntry() {
        val state = SettingsUiState(
            isLkmMode = false,
            isLateLoadMode = false,
            runtimeModeResolved = true,
            kpmBackend = "native-gki",
            isKpmManagementAvailable = true,
        )

        assertFalse(state.isKpmSettingsEntryVisible)
    }

    @Test
    fun unresolvedModeKeepsRuntimeSpecificEntriesDisabled() {
        val state = SettingsUiState()

        assertEquals(PathConfigBackend.Unknown, state.pathConfigBackend)
        assertFalse(state.canOpenPathConfig)
        assertFalse(state.canToggleKPatchNext)
    }

    @Test
    fun kpatchNextOperationLocksTheSwitch() {
        val state = SettingsUiState(
            runtimeModeResolved = true,
            isKPatchNextOperationRunning = true,
        )

        assertFalse(state.canToggleKPatchNext)
    }

    @Test
    fun kpatchNextSwitchRepresentsEffectiveLifecycle() {
        assertTrue(
            SettingsUiState(
                isKPatchNextInstalled = true,
                isKPatchNextEnabled = true,
            ).isKPatchNextSwitchChecked
        )
        assertTrue(
            SettingsUiState(
                isKPatchNextPendingUpdate = true,
            ).isKPatchNextSwitchChecked
        )
        assertFalse(
            SettingsUiState(
                isKPatchNextInstalled = true,
                isKPatchNextPendingRemove = true,
            ).isKPatchNextSwitchChecked
        )
        assertFalse(
            SettingsUiState(
                isKPatchNextInstalled = true,
                isKPatchNextEnabled = false,
            ).isKPatchNextSwitchChecked
        )
    }
}
