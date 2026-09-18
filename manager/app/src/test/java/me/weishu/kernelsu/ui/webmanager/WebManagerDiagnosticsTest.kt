package me.weishu.kernelsu.ui.webmanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebManagerDiagnosticsTest {
    @Test
    fun keepsOnlyTheNewestEntries() {
        val diagnostics = WebManagerDiagnostics(maxEntries = 3)
        repeat(5) { index -> diagnostics.info("tag", "message-$index") }
        val snapshot = diagnostics.snapshot()
        assertEquals(3, snapshot.size)
        assertEquals("message-2", snapshot.first().message)
        assertEquals("message-4", snapshot.last().message)
    }

    @Test
    fun snapshotLimitReturnsTheTail() {
        val diagnostics = WebManagerDiagnostics(maxEntries = 10)
        repeat(6) { index -> diagnostics.info("tag", "m$index") }
        val tail = diagnostics.snapshot(limit = 2)
        assertEquals(listOf("m4", "m5"), tail.map { it.message })
    }

    @Test
    fun throwableMessagesIncludeTypeAndReason() {
        val diagnostics = WebManagerDiagnostics()
        diagnostics.error("shell", "root shell failed", IllegalStateException("boom"))
        val entry = diagnostics.snapshot().single()
        assertEquals(WebManagerDiagnostics.Level.ERROR, entry.level)
        assertTrue(entry.message.contains("IllegalStateException"))
        assertTrue(entry.message.contains("boom"))
    }

    @Test
    fun longAndMultilineMessagesAreFlattened() {
        val diagnostics = WebManagerDiagnostics()
        diagnostics.warn("tag", "line one\nline two" + "x".repeat(400))
        val entry = diagnostics.snapshot().single()
        assertTrue(entry.message.length <= 240)
        assertTrue(!entry.message.contains('\n'))
    }

    @Test
    fun clearEmptiesTheBuffer() {
        val diagnostics = WebManagerDiagnostics()
        diagnostics.info("tag", "one")
        diagnostics.clear()
        assertTrue(diagnostics.snapshot().isEmpty())
    }
}
