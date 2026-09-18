package me.weishu.kernelsu.ui.webmanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebManagerRebootTest {
    @Test
    fun parsesEveryExposedModeToItsNativeReason() {
        val expected = mapOf(
            "system" to "",
            "userspace" to "userspace",
            "soft" to "soft_reboot",
            "recovery" to "recovery",
            "bootloader" to "bootloader",
            "download" to "download",
            "edl" to "edl",
        )

        assertEquals(expected.keys, WebManagerRebootMode.entries.map { it.wireValue }.toSet())
        expected.forEach { (wireValue, nativeReason) ->
            assertEquals(nativeReason, WebManagerRebootMode.parse(wireValue)?.nativeReason)
        }
    }

    @Test
    fun rejectsAnythingOutsideTheFixedWhitelist() {
        assertNull(WebManagerRebootMode.parse(null))
        assertNull(WebManagerRebootMode.parse(""))
        assertNull(WebManagerRebootMode.parse("SYSTEM"))
        assertNull(WebManagerRebootMode.parse("recovery; id"))
        assertNull(WebManagerRebootMode.parse("shutdown"))
    }

    @Test
    fun softModeIsTheOnlyKsudSoftReboot() {
        assertTrue(WebManagerRebootMode.SOFT.usesKsudSoftReboot)
        WebManagerRebootMode.entries
            .filterNot { it == WebManagerRebootMode.SOFT }
            .forEach { assertFalse(it.usesKsudSoftReboot) }
    }

    @Test
    fun webPageUsesTheUnifiedRebootMenu() {
        assertTrue(WEB_MANAGER_PAGE.contains("id=\"rebootMenu\""))
        assertTrue(WEB_MANAGER_PAGE.contains("/api/tools/reboot"))
        assertTrue(WEB_MANAGER_PAGE.contains("data-reboot-mode"))
        assertFalse(WEB_MANAGER_PAGE.contains("id=\"softRebootBtn\""))
    }
}
