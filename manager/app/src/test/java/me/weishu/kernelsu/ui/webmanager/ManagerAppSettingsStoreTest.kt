package me.weishu.kernelsu.ui.webmanager

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagerAppSettingsStoreTest {
    private fun validJson(): JSONObject = JSONObject()
        .put("schemaVersion", MANAGER_APP_SETTINGS_SCHEMA_VERSION)
        .put("language", "zh-CN")
        .put("checkModuleUpdate", true)
        .put("showVersionMismatchWarning", true)
        .put("showGkiWarning", true)
        .put("showHomeSupportCard", true)
        .put("showHomeLearnCard", true)
        .put("customHomeTitle", "")

    @Test
    fun parsesCanonicalManagerSettings() {
        val settings = ManagerAppSettings.fromJson(validJson())

        assertEquals("zh-CN", settings.language)
        assertTrue(settings.checkModuleUpdate)
        assertEquals("", settings.customHomeTitle)
    }

    @Test
    fun partialUpdateOnlyChangesWhitelistedValues() {
        val current = ManagerAppSettings.fromJson(validJson())
        val updated = ManagerAppSettings.updated(
            current,
            JSONObject()
                .put("showGkiWarning", false)
                .put("customHomeTitle", "  ApkeSU 测试机  "),
        )

        assertFalse(updated.showGkiWarning)
        assertEquals("ApkeSU 测试机", updated.customHomeTitle)
        assertTrue(updated.checkModuleUpdate)
    }

    @Test
    fun rejectsUnknownWrongTypeAndUnsupportedLanguage() {
        val current = ManagerAppSettings.fromJson(validJson())

        assertThrows(IllegalArgumentException::class.java) {
            ManagerAppSettings.updated(current, JSONObject().put("unknown", true))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManagerAppSettings.updated(current, JSONObject().put("showGkiWarning", "false"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManagerAppSettings.updated(current, JSONObject().put("language", "de"))
        }
    }
}
