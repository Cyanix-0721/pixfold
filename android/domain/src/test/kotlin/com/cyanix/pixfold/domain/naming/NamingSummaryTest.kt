package com.cyanix.pixfold.domain.naming

import com.cyanix.pixfold.domain.model.NameComponent
import com.cyanix.pixfold.domain.model.NameComponentKind
import com.cyanix.pixfold.domain.model.NamingScheme
import com.cyanix.pixfold.domain.text.Msg
import com.cyanix.pixfold.domain.text.TextKey
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 折叠摘要行的文案([namingSummaryLine])。
 *
 * 守三件事:①样本存在时摘要里能看到**真实名字**;②组件计数反映**启用**数而非总数
 * —— 否则用户关掉一个组件后摘要还在报旧数字,反而比不显示更误导;
 * ③人工拖拽过之后追加**手工调整段**(HANGOFF §11 第 10 条)。
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
            listOf(Msg(TextKey.NamingSummaryWithHead, listOf("旅行照片_001.jpg", 2))),
            namingSummaryLine(scheme, "旅行照片_001.jpg"),
        )
    }

    @Test
    fun `disabled components are not counted`() {
        // OrigName 是关掉的 -> 计 2 而不是 3
        assertEquals(
            listOf(Msg(TextKey.NamingSummaryWithHead, listOf("x.jpg", 2))),
            namingSummaryLine(scheme, "x.jpg"),
        )
    }

    @Test
    fun `null sample falls back to the component count only`() {
        // 集合为空(没有可命名的图片)时没有样本可展示 —— 必须走**不带 head 的键**,
        // 否则成品里会拼出 "null · 2 个组件启用"
        assertEquals(listOf(Msg(TextKey.NamingSummaryEnabled, listOf(2))), namingSummaryLine(scheme, null))
    }

    @Test
    fun `blank sample is treated as absent`() {
        assertEquals(listOf(Msg(TextKey.NamingSummaryEnabled, listOf(2))), namingSummaryLine(scheme, "   "))
    }

    @Test
    fun `all components disabled reports zero`() {
        val off = scheme.copy(components = scheme.components.map { it.copy(enabled = false) })
        assertEquals(listOf(Msg(TextKey.NamingSummaryEnabled, listOf(0))), namingSummaryLine(off, null))
    }

    // ---------------- 手工调整披露(HANGOFF §11 第 10 条) ----------------

    @Test
    fun `manual count appends a trailing manual segment`() {
        // 第 1 步拖过 3 张 -> 第 2 步摘要必须说出来。段是**追加**的,
        // 故前一段与无调整时逐字相同(用户不会因为多了这件事而丢失原有信息)。
        assertEquals(
            listOf(
                Msg(TextKey.NamingSummaryWithHead, listOf("x.jpg", 2)),
                Msg(TextKey.NamingSummaryManual, listOf(3)),
            ),
            namingSummaryLine(scheme, "x.jpg", manualCount = 3),
        )
    }

    @Test
    fun `manual segment appears even without a sample`() {
        // 空集合不会有人工调整,但这条钉住"两个维度互不依赖":
        // 披露段不因为缺少样本而消失。
        assertEquals(
            listOf(
                Msg(TextKey.NamingSummaryEnabled, listOf(2)),
                Msg(TextKey.NamingSummaryManual, listOf(1)),
            ),
            namingSummaryLine(scheme, null, manualCount = 1),
        )
    }

    @Test
    fun `zero manual count adds no segment`() {
        // 没拖过时输出**只有一个段**,与加这个功能之前逐字一致 —— 这是本次改动
        // 对既有界面的唯一承诺:没有人工调整就完全看不出区别。
        assertEquals(1, namingSummaryLine(scheme, "x.jpg", manualCount = 0).size)
        assertEquals(
            listOf(Msg(TextKey.NamingSummaryWithHead, listOf("x.jpg", 2))),
            namingSummaryLine(scheme, "x.jpg", manualCount = 0),
        )
    }

    @Test
    fun `manual count defaults to zero so existing callers are unaffected`() {
        assertEquals(
            namingSummaryLine(scheme, "x.jpg", manualCount = 0),
            namingSummaryLine(scheme, "x.jpg"),
        )
    }
}
