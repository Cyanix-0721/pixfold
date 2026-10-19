package com.cyanix.pixfold.domain.naming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [derivedNamesOf] 的用例 —— 统一抽象的**契约**。
 *
 * 这些断言的目的是钉住「四种形态各自是什么」,防止将来有人把 title 与 cbzBase
 * 合并成同一个字段(那会让「作者前缀出现在 <Title> 里」或「CBZ 名丢掉作者」二者之一复现)。
 */
class DerivedNamesTest {

    /** 真实素材:带作者前缀 + 括号作者本名 + 末尾发行标注。 */
    @Test
    fun 真实素材的四种形态互相不同() {
        val n = derivedNamesOf("[Cior (Ken-1)] 彼女がボクの知らないところで――2 [DL版]")

        // raw 原样,芯片与步骤 2 标题用它。
        assertEquals("[Cior (Ken-1)] 彼女がボクの知らないところで――2 [DL版]", n.raw)
        // title:作者前缀、括号、末尾标注全剥掉。
        assertEquals("彼女がボクの知らないところで――2", n.title)
        // cbzBase:保留作者前缀,其余同 title。
        assertEquals("[Cior (Ken-1)] 彼女がボクの知らないところで――2", n.cbzBase)
        // 作者原始形态带方括号(写进 writerSug),清理后不带。
        assertEquals("[Cior (Ken-1)]", n.writerRaw)
        assertEquals("Cior (Ken-1)", n.writerCleaned)
    }

    /** 系列目录名同样是「作者 + 标题」形态 —— 系列建议值必须清洗。 */
    @Test
    fun 系列目录名清洗后不含作者前缀() {
        val series = derivedNamesOf("[Cior (Ken-1)] 彼女がボクの知らないところで")

        assertEquals("彼女がボクの知らないところで", series.title)
        assertEquals("[Cior (Ken-1)] 彼女がボクの知らないところで", series.cbzBase)
    }

    /** 没有作者前缀时四种形态只在末尾标注上分叉。 */
    @Test
    fun 无作者前缀时仅在末尾标注上分叉() {
        val n = derivedNamesOf("彼女がボクの知らないところで――2 [DL版]")

        assertEquals("彼女がボクの知らないところで――2", n.title)
        assertEquals("彼女がボクの知らないところで――2", n.cbzBase)
        assertEquals("", n.writerRaw)
        assertEquals("", n.writerCleaned)
    }

    /** 标题中间的 `[]` 是标题的一部分,不能剥 —— 与旧脚本 `strip_original_work` 对齐。 */
    @Test
    fun 标题中间的方括号保留下来() {
        val n = derivedNamesOf("真正的[漫画]标题 [DL]")

        assertEquals("真正的[漫画]标题", n.title)
    }

    /** 开头的展会代码 `(C108)` 剥掉,但作者前缀保留在 cbzBase 里。 */
    @Test
    fun 开头展会代码剥掉而作者前缀保留() {
        val n = derivedNamesOf("(C108) [作者A] 作品A 副标题（原作：X）[DL]")

        assertEquals("作品A 副标题", n.title)
        assertEquals("[作者A] 作品A 副标题", n.cbzBase)
        assertEquals("[作者A]", n.writerRaw)
        assertEquals("作者A", n.writerCleaned)
    }

    /**
     * 剥完什么都不剩时**回退到原目录名**,而不是给空标题。
     *
     * 展示空标题比展示原目录名更糟:用户拿着空标题去文件管理器里找不到任何东西。
     */
    @Test
    fun 剥完为空时回退到原目录名() {
        val n = derivedNamesOf("[DL版]")

        assertEquals("[DL版]", n.raw)
        // title 无法从 `[DL版]` 得到任何内容(它整体是末尾标注)⇒ 回退。
        assertEquals("[DL版]", n.title)
        assertEquals("[DL版]", n.cbzBase)
    }

    /** 空串输入返回全空,不抛异常(工作区根集合的兜底名可能是空串)。 */
    @Test
    fun 空串输入返回全空() {
        val n = derivedNamesOf("")

        assertEquals("", n.raw)
        assertEquals("", n.title)
        assertEquals("", n.cbzBase)
        assertEquals("", n.writerRaw)
        assertEquals("", n.writerCleaned)
    }

    /** [DerivedNames.isCleaned] 供「来源说明」判断写「目录名」还是「目录名清洗」。 */
    @Test
    fun isCleaned_反映清洗是否真的改变了什么() {
        assertTrue(derivedNamesOf("作品B[DL] [中文翻译]").isCleaned)
        assertFalse(derivedNamesOf("干净的作品名").isCleaned)
    }

    /** 普通目录名(无任何标记)四种形态应完全相同或全空。 */
    @Test
    fun 普通目录名四种形态一致() {
        val n = derivedNamesOf("进击的巨人 第02卷")

        assertEquals("进击的巨人 第02卷", n.title)
        assertEquals("进击的巨人 第02卷", n.cbzBase)
        assertEquals("", n.writerRaw)
    }
}
