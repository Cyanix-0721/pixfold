package com.pixfold.d1.domain.naming

import com.pixfold.d1.domain.model.NameComponent
import com.pixfold.d1.domain.model.NameComponentKind
import com.pixfold.d1.domain.model.NamingScheme
import com.pixfold.d1.domain.text.Msg
import com.pixfold.d1.domain.text.TextKey
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 折叠摘要行的文案([namingSummaryLine])。
 *
 * 守两件事:①样本存在时摘要里能看到**真实名字**;②组件计数反映**启用**数而非总数
 * —— 否则用户关掉一个组件后摘要还在报旧数字,反而比不显示更误导。
 *
 * **断言 [Msg] 结构而非成品文案**:领域层只给键与参数,成品在 UI 层解析。
 * 这里因此换成"用了哪个键、参数是否传对",换语言不会让本文件变红。
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
            Msg(TextKey.NamingSummaryWithHead, listOf("旅行照片_001.jpg", 2)),
            namingSummaryLine(scheme, "旅行照片_001.jpg"),
        )
    }

    @Test
    fun `disabled components are not counted`() {
        // OrigName 是关掉的 -> 计 2 而不是 3
        assertEquals(Msg(TextKey.NamingSummaryWithHead, listOf("x.jpg", 2)), namingSummaryLine(scheme, "x.jpg"))
    }

    @Test
    fun `null sample falls back to the component count only`() {
        // 集合为空(没有可命名的图片)时没有样本可展示 —— 必须走**不带 head 的键**,
        // 否则成品里会拼出 "null · 2 个组件启用"
        assertEquals(Msg(TextKey.NamingSummaryEnabled, listOf(2)), namingSummaryLine(scheme, null))
    }

    @Test
    fun `blank sample is treated as absent`() {
        assertEquals(Msg(TextKey.NamingSummaryEnabled, listOf(2)), namingSummaryLine(scheme, "   "))
    }

    @Test
    fun `all components disabled reports zero`() {
        val off = scheme.copy(components = scheme.components.map { it.copy(enabled = false) })
        assertEquals(Msg(TextKey.NamingSummaryEnabled, listOf(0)), namingSummaryLine(off, null))
    }
}
