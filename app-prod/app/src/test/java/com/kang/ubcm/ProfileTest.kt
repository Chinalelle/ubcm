package com.kang.ubcm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 我的页身份字段整理单测。 */
class ProfileTest {

    @Test
    fun `班级名转人话`() {
        assertEquals("2026 级 1 班（岐黄班）", Profile.classLabel("岐黄班202601"))
        assertEquals("2026 级 12 班（中医学）", Profile.classLabel("中医学202612"))
    }

    @Test
    fun `解析不出来就原样返回`() {
        assertEquals("实验班", Profile.classLabel("实验班"))
        assertNull(Profile.classLabel(""))
        assertNull(Profile.classLabel(null))
        assertNull(Profile.classLabel("null"))
    }

    @Test
    fun `缺失字段补破折号`() {
        assertEquals("—", Profile.orEmpty(null))
        assertEquals("—", Profile.orEmpty(""))
        assertEquals("—", Profile.orEmpty("null"))
        assertEquals("同学", Profile.orEmpty("同学"))
    }

    @Test
    fun `凭证文案`() {
        assertEquals("5/6 有效", Profile.credentialText(5, 6))
        assertEquals("0/6 有效", Profile.credentialText(0, 6))
        assertEquals("—", Profile.credentialText(0, 0))
    }
}
