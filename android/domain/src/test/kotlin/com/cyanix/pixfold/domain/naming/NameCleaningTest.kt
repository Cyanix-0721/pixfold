package com.cyanix.pixfold.domain.naming

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 名字清洗规则的回归测试。
 *
 * **权威来源 = `scripts/batch_pack_cbz.py`**(旧脚本的现有行为参考),本测试把它的
 * docstring 样例**逐条搬过来**,一条不漏 —— 这些样例是脚本作者用真实素材攒出来的,
 * 比我自己想的边界用例更值钱。
 *
 * 为什么值得单独一个文件:清洗规则错一个字符,用户看到的就是落盘的 CBZ 文件名
 * 和 `ComicInfo.xml` 里的 `<Title>`/`<Series>` 全错,而这类错误**不会报错**,
 * 只会安静地把 `[DL版]` 留在文件名里。
 */
class NameCleaningTest {

    // ---- stripOriginalWork ----
    // 对应旧脚本 `strip_original_work` 的 docstring 全量样例。

    @Test
    fun `剥掉全角括号里的原作`() {
        assertEquals("作品A", stripOriginalWork("作品A（原作：X）"))
    }

    @Test
    fun `剥掉半角括号里的原作`() {
        assertEquals("作品A", stripOriginalWork("作品A (原作X)"))
    }

    @Test
    fun `原作在中间时两侧文字都保留`() {
        assertEquals("作品A 1", stripOriginalWork("作品A（原作：X） 1"))
    }

    @Test
    fun `剥掉末尾的方括号标注`() {
        assertEquals("作品A", stripOriginalWork("作品A [DL]"))
    }

    @Test
    fun `剥掉末尾连续的多个方括号标注`() {
        assertEquals("作品A", stripOriginalWork("作品A[DL] [中文翻译]"))
    }

    @Test
    fun `标题中间的方括号必须保留`() {
        // 承重:中间的 [] 是标题的一部分(如「真正的[漫画]标题」),剥掉会毁掉标题。
        assertEquals("真正的[漫画]标题", stripOriginalWork("真正的[漫画]标题 [DL]"))
    }

    @Test
    fun `非末尾的方括号标注必须保留`() {
        // 承重:旧脚本 docstring 明确写了这条 —— "作品A [DL] 副标题" 里 [DL] 在中间,保留。
        assertEquals("作品A [DL] 副标题", stripOriginalWork("作品A [DL] 副标题"))
    }

    @Test
    fun `连续空格规整成一个且去掉首尾空白`() {
        assertEquals("作品A 副标题", stripOriginalWork("  作品A    副标题  "))
    }

    @Test
    fun `没有可剥内容时原样返回`() {
        assertEquals("作品A", stripOriginalWork("作品A"))
    }

    // ---- parseCollectionName ----
    // 对应旧脚本 `parse_name` 的 docstring 样例。

    @Test
    fun `剥掉开头的展会代码并提取作者`() {
        // docstring 原文:"(C108) [作者A] 作品A 副标题（原作：X）" -> ("作者A", "作品A 副标题")
        val parsed = parseCollectionName("(C108) [作者A] 作品A 副标题（原作：X）")
        assertEquals("[作者A]", parsed.writerRaw)
        assertEquals("作品A 副标题", parsed.title)
    }

    @Test
    fun `没有方括号时作者为空`() {
        val parsed = parseCollectionName("作品B（原作：Y）")
        assertEquals("", parsed.writerRaw)
        assertEquals("作品B", parsed.title)
    }

    @Test
    fun `作者方括号原样保留供一键清理`() {
        // 承重:writerRaw 是**原始形态**(带方括号),WriterCleaned 才是清理后的。
        // 如果这里就剥掉方括号,UI 上「一键清理作者」按钮会变成无意义的空操作。
        val parsed = parseCollectionName("[作者]谏山创(原作:某人)(电子版)")
        assertEquals("[作者]", parsed.writerRaw)
        assertEquals("谏山创", parsed.title)
    }

    @Test
    fun `作者方括号内部的括号内容不参与剥原作的判定`() {
        // 真实素材:"[Cior (Ken-1)] 彼女がボクの知らないところで"
        // (Ken-1) 在方括号内部,属于作者名的一部分,不能被当成原作剥掉。
        val parsed = parseCollectionName("[Cior (Ken-1)] 彼女がボクの知らないところで")
        assertEquals("[Cior (Ken-1)]", parsed.writerRaw)
        assertEquals("彼女がボクの知らないところで", parsed.title)
    }

    @Test
    fun `真实素材第二本的末尾标注被剥掉`() {
        val parsed = parseCollectionName("彼女がボクの知らないところで――2 [DL版]")
        assertEquals("", parsed.writerRaw)
        assertEquals("彼女がボクの知らないところで――2", parsed.title)
    }

    @Test
    fun `只有方括号没有后文时标题为空`() {
        val parsed = parseCollectionName("[作者]")
        assertEquals("[作者]", parsed.writerRaw)
        assertEquals("", parsed.title)
    }

    // ---- cleanCbzBaseName ----
    // 对应旧脚本 `clean_cbz_name` 的 docstring 样例。

    @Test
    fun `CBZ 基名保留作者前缀并剥掉其余`() {
        assertEquals("[作者] 标题", cleanCbzBaseName("(C108) [作者] 标题（原作：X）[DL]"))
    }

    @Test
    fun `CBZ 基名保留标题中间的方括号`() {
        assertEquals("[作者] 真正的[漫画]标题", cleanCbzBaseName("[作者] 真正的[漫画]标题 [中文翻译]"))
    }

    @Test
    fun `CBZ 基名在本来就干净时不变`() {
        assertEquals("[作者] 标题", cleanCbzBaseName("[作者] 标题"))
    }

    @Test
    fun `CBZ 基名剥掉真实的 DL版 标注`() {
        // 用户 m06168 直接指出的问题:"[DL版]没过滤掉?"
        assertEquals("彼女がボクの知らないところで――2", cleanCbzBaseName("彼女がボクの知らないところで――2 [DL版]"))
    }

    @Test
    fun `CBZ 基名对真实素材第一本保留作者前缀`() {
        assertEquals(
            "[Cior (Ken-1)] 彼女がボクの知らないところで",
            cleanCbzBaseName("[Cior (Ken-1)] 彼女がボクの知らないところで"),
        )
    }

    // ---- cleanWriterName ----

    @Test
    fun `清理作者 剥掉包裹的方括号与原作括号`() {
        assertEquals("谏山创", cleanWriterName("[作者]谏山创(原作:某人)(电子版)"))
    }

    /**
     * 整体被方括号包裹时,**内部括号是署名的一部分**([社团 (画师)] 是主体例),
     * 剥掉只剩社团名、作者反而丢了。与旧脚本 `parse_name` 的 `name[1:end]` 一致。
     */
    @Test
    fun `清理作者 整体被方括号包裹时保留内部括号`() {
        assertEquals("Cior (Ken-1)", cleanWriterName("[Cior (Ken-1)]"))
    }

    /** 对照:方括号只是标记前缀时,其后的括号是「原作/电子版」附注,要全剥。 */
    @Test
    fun `清理作者 标记前缀之后的括号是附注要剥掉`() {
        assertEquals("谏山创", cleanWriterName("[作者]谏山创(原作:某人)"))
    }

    @Test
    fun `清理作者 本来就干净时原样返回`() {
        assertEquals("尾田荣一郎", cleanWriterName("尾田荣一郎"))
    }

    @Test
    fun `清理作者 空串返回空串`() {
        assertEquals("", cleanWriterName(""))
    }

    @Test
    fun `清理作者 整体被方括号包裹时括号内就是作者名`() {
        // 承重:这一分支处理的是「writerRaw 本身就是整段 [xxx]」的真实扫描形态。
        // 不能因为内部文字恰好是「作者」二字就返回空串 —— 那样一键清理会把作者清空。
        assertEquals("作者", cleanWriterName("[作者]"))
    }
}
