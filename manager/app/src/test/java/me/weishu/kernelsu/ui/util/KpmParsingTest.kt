package me.weishu.kernelsu.ui.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KpmParsingTest {
    @Test
    fun preservesUnknownRuntimeStateFromKsud() {
        val entries = parseKpmEntries(
            """[{"id":"hello","enabled":true,"loaded":null,"runtimeKnown":false}]""",
        )

        assertFalse(entries.single().loaded)
        assertFalse(entries.single().runtimeKnown)
    }

    @Test
    fun acceptsKnownLoadedRuntimeState() {
        val entries = parseKpmEntries(
            """[{"id":"hello","enabled":true,"loaded":true,"runtimeKnown":true}]""",
        )

        assertTrue(entries.single().loaded)
        assertTrue(entries.single().runtimeKnown)
    }
}
