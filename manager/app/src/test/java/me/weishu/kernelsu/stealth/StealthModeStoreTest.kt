package me.weishu.kernelsu.stealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StealthModeStoreTest {
    @Test
    fun acceptsArbitraryNonEmptyCodesWithoutChangingThem() {
        assertEquals("4211", StealthModeStore.normalizeCode("4211"))
        assertEquals("*#*#987654#*#*", StealthModeStore.normalizeCode(" *#*#987654#*#* "))
        assertEquals("letters, symbols !@'中文", StealthModeStore.normalizeCode("letters, symbols !@'中文"))
        assertEquals("1", StealthModeStore.normalizeCode("1"))
        assertEquals("4211", StealthModeStore.secretCodeHost(DEFAULT_STEALTH_MODE_CODE))
    }

    @Test
    fun onlyRejectsEmptyOrMultiLineStorageValues() {
        assertNull(StealthModeStore.normalizeCode(""))
        assertNull(StealthModeStore.normalizeCode("   "))
        assertNull(StealthModeStore.normalizeCode("line one\nline two"))
        assertNull(StealthModeStore.normalizeCode("nul\u0000value"))
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
        assertTrue(StealthModeStore.matchesRequestedCode("custom !@'中文", "custom !@'中文"))
        assertFalse(StealthModeStore.matchesRequestedCode(null, DEFAULT_STEALTH_MODE_CODE))
    }

    @Test
    fun arbitraryCodesAreSafelyQuotedForTheRootShell() {
        assertEquals("'plain text !@中文'", StealthModeStore.shellQuote("plain text !@中文"))
        assertEquals("'a'\"'\"'b'", StealthModeStore.shellQuote("a'b"))
        assertEquals("'\$(id); rm -f /data/local/tmp/x'", StealthModeStore.shellQuote("\$(id); rm -f /data/local/tmp/x"))
    }
}
