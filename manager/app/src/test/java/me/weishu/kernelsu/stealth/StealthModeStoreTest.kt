package me.weishu.kernelsu.stealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StealthModeStoreTest {
    @Test
    fun normalizesDigitsAndCompleteDialCodes() {
        assertEquals(DEFAULT_STEALTH_MODE_CODE, StealthModeStore.normalizeCode("4211"))
        assertEquals("*#*#987654#*#*", StealthModeStore.normalizeCode(" *#*#987654#*#* "))
        assertEquals("4211", StealthModeStore.secretCodeHost(DEFAULT_STEALTH_MODE_CODE))
    }

    @Test
    fun rejectsCodesThatDialerCannotRouteSafely() {
        assertNull(StealthModeStore.normalizeCode("12"))
        assertNull(StealthModeStore.normalizeCode("*#*#42a1#*#*"))
        assertNull(StealthModeStore.normalizeCode("*#*#4211#*#*;rm"))
    }

    @Test
    fun secretCodeMustMatchTheConfiguredHostExactly() {
        assertTrue(StealthModeStore.matchesSecretCode("4211", DEFAULT_STEALTH_MODE_CODE))
        assertFalse(StealthModeStore.matchesSecretCode("42110", DEFAULT_STEALTH_MODE_CODE))
        assertFalse(StealthModeStore.matchesSecretCode(null, DEFAULT_STEALTH_MODE_CODE))
    }

    @Test
    fun requestedCodeMustNormalizeAndMatchExactly() {
        assertTrue(StealthModeStore.matchesRequestedCode("4211", DEFAULT_STEALTH_MODE_CODE))
        assertTrue(
            StealthModeStore.matchesRequestedCode(
                " *#*#4211#*#* ",
                DEFAULT_STEALTH_MODE_CODE,
            )
        )
        assertFalse(StealthModeStore.matchesRequestedCode("42110", DEFAULT_STEALTH_MODE_CODE))
        assertFalse(StealthModeStore.matchesRequestedCode("12", DEFAULT_STEALTH_MODE_CODE))
        assertFalse(StealthModeStore.matchesRequestedCode(null, DEFAULT_STEALTH_MODE_CODE))
    }
}
