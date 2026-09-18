package me.weishu.kernelsu.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 设备信息卡片里 KPM / SUSFS 两行的摘要规则：
 * 内核没有对应能力时返回空串（卡片隐藏该行），有能力时给出可核对的信息。
 */
class KpmSusfsSummaryTest {
    @Test
    fun hidesKpmRowWhenKernelReportsNoSupport() {
        assertEquals("", buildKpmSummary(KpmCaps(), null))
        assertEquals("", buildKpmSummary(KpmCaps(error = "ksud unavailable"), null))
    }

    @Test
    fun reportsKpmCapabilitiesWithLoadedCount() {
        val caps = KpmCaps(
            backend = "native_gki",
            managementAvailable = true,
            supported = true,
            kernelSupported = true,
            loaderReady = true,
            abiVersion = 1,
            capabilities = 0x3,
            maxLoaded = 8,
        )
        assertEquals("已启用 · 已加载 2 个 · 上限 8 · ABI v1", buildKpmSummary(caps, 2))
    }

    @Test
    fun keepsKpmRowWhenListProbeFailsButKernelSupportsIt() {
        val caps = KpmCaps(
            managementAvailable = true,
            supported = true,
            kernelSupported = true,
            loaderReady = true,
            maxLoaded = 4,
        )
        assertEquals(
            "已启用 · 上限 4",
            buildKpmSummary(caps, null),
        )
    }

    @Test
    fun marksKernelSupportedButDisabledPolicy() {
        val caps = KpmCaps(kernelSupported = true, loaderReady = true, policyEnabled = false)
        assertEquals("已就绪 · 已加载 0 个 · 策略已关闭", buildKpmSummary(caps, 0))
    }

    @Test
    fun countsOnlyLiveKpmModules() {
        val list = """[{"id":"a","loaded":true},{"id":"b","loaded":false},{"id":"c","loaded":null},{},{"id":"d","loaded":true}]"""
        assertEquals(2, countLiveKpmModules(list) ?: -1)
        assertEquals(0, countLiveKpmModules("[]") ?: -1)
        assertNull(countLiveKpmModules("not json"))
    }

    @Test
    fun hidesSusfsRowWithoutToolOrMounts() {
        assertEquals("", buildSusfsSummary("", "", featureCount = 0, mountCount = 0, hiddenPathCount = 0))
    }

    @Test
    fun reportsSusfsVersionFeaturesMountsAndHiddenPaths() {
        assertEquals(
            "v2.0.0 · 特性 33 项 · 挂载 3 项 · 隐藏路径 8 条",
            buildSusfsSummary("/data/adb/ksu/bin/ksu_susfs", "v2.0.0", 33, 3, 8),
        )
    }

    @Test
    fun reportsMountsEvenWhenToolIsMissing() {
        assertEquals(
            "已检测到挂载 · 挂载 2 项 · 未找到 ksu_susfs",
            buildSusfsSummary("", "", featureCount = 0, mountCount = 2, hiddenPathCount = 0),
        )
    }
}
