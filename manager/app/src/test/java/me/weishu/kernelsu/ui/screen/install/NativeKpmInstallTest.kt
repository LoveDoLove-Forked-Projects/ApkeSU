package me.weishu.kernelsu.ui.screen.install

import me.weishu.kernelsu.ui.util.BootPatchMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeKpmInstallTest {
    @Test
    fun nativeKpmNeverUsesDirectOrRamdiskInstallation() {
        val mode = BootPatchMode.NativeKpm
        assertFalse(mode.supportsInstallMethod(null))
        assertFalse(mode.supportsInstallMethod(InstallMethod.DirectInstall))
        assertFalse(mode.supportsInstallMethod(InstallMethod.DirectInstallToInactiveSlot))
        assertFalse(mode.supportsInstallMethod(InstallMethod.AnyKernel()))
        assertTrue(mode.supportsInstallMethod(InstallMethod.SelectFile(summary = "boot.img")))
        assertTrue(mode.supportsInstallMethod(InstallMethod.DownloadFile(partition = "boot", summary = "")))
        assertFalse(mode.supportsInstallMethod(InstallMethod.DownloadFile(partition = "init_boot", summary = "")))
        assertFalse(mode.supportsInstallMethod(InstallMethod.DownloadFile(partition = "vendor_boot", summary = "")))
    }
}
