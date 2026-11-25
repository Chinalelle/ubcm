package com.kang.ubcm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 版本比较是纯逻辑，放 JVM 单测（网络/JSON 部分依赖 Android 的 org.json，不在这里测）。 */
class UpdateCheckerTest {

    @Test
    fun `版本号按数字段比较`() {
        assertEquals(1, UpdateChecker.compareVersions("1.0.2", "1.0.1"))
        assertEquals(-1, UpdateChecker.compareVersions("1.0.1", "1.0.2"))
        assertEquals(0, UpdateChecker.compareVersions("1.0.1", "1.0.1"))
    }

    @Test
    fun `多位数不会按字符串比较`() {
        // 字符串比较会认为 1.0.9 > 1.0.10
        assertEquals(1, UpdateChecker.compareVersions("1.0.10", "1.0.9"))
        assertEquals(-1, UpdateChecker.compareVersions("1.0.9", "1.0.10"))
    }

    @Test
    fun `忽略 v 前缀和后缀`() {
        assertEquals(0, UpdateChecker.compareVersions("v1.2.3", "1.2.3"))
        assertEquals(0, UpdateChecker.compareVersions("1.2.3-debug", "1.2.3"))
        assertEquals(1, UpdateChecker.compareVersions("v2.0", "1.9.9"))
    }

    @Test
    fun `缺失的段按 0 处理`() {
        assertEquals(0, UpdateChecker.compareVersions("1.0", "1.0.0"))
        assertEquals(1, UpdateChecker.compareVersions("1.0.1", "1.0"))
    }

    @Test
    fun `hasUpdate 只在远端更高时为真`() {
        assertTrue(UpdateChecker.hasUpdate("1.0.1", "1.0.0"))
        assertFalse(UpdateChecker.hasUpdate("1.0.0", "1.0.0"))
        assertFalse(UpdateChecker.hasUpdate("0.9.9", "1.0.0"))
    }
}
