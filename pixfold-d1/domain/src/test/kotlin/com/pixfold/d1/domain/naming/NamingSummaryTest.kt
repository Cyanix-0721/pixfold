package com.pixfold.d1.domain.naming

import com.pixfold.d1.domain.model.NameComponent
import com.pixfold.d1.domain.model.NameComponentKind
import com.pixfold.d1.domain.model.NamingScheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 折叠摘要行的文案([namingSummaryLine])。
 *
 * 守两件事:①样本存在时摘要里能看到**真实名字**;②组件计数反映**启用**数而非总数
 * —— 否则用户关掉一个组件后摘要还在报旧数字,反而比不显示更误导。
 */
class NamingSummaryTest {

    private val scheme = NamingScheme(
        rootDirName = "旅行照片",
        components = listOf(
            NameComponent(NameComponentKind.RootDir),
            NameComponent(NameComponentKind.ImageIndex),
            NameComponent(NameComponentKind.OrigName, enabled = false),
        ),
    )

    @Test
    fun `summary shows the sample name and the enabled component count`() {
        assertEquals(
            "旅行照片_001.jpg · 2 个组件启用",
            namingSummaryLine(scheme, "旅行照片_001.jpg"),
        )
    }

    @Test
    fun `disabled components are not counted`() {
        // OrigName 是关掉的 -> 计 2 而不是 3
        assertEquals("x.jpg · 2 个组件启用", namingSummaryLine(scheme, "x.jpg"))
    }

    @Test
    fun `null sample falls back to the component count only`() {
        // 集合为空(没有可命名的图片)时没有样本可展示,不能拼出 "null · ..."
        assertEquals("2 个组件启用", namingSummaryLine(scheme, null))
    }

    @Test
    fun `blank sample is treated as absent`() {
        assertEquals("2 个组件启用", namingSummaryLine(scheme, "   "))
    }

    @Test
    fun `all components disabled reports zero`() {
        val off = scheme.copy(components = scheme.components.map { it.copy(enabled = false) })
        assertEquals("0 个组件启用", namingSummaryLine(off, null))
    }
}
