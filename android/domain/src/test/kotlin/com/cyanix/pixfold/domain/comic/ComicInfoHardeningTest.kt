package com.cyanix.pixfold.domain.comic

import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 安全加固**不得**以「解析整体失败」为代价。
 *
 * ## 为什么需要这个测试（真机缺陷的直接原因）
 *
 * `parseComicInfo` 在 `runCatching` 里配置 `DocumentBuilderFactory`：禁用 DTD、
 * 禁用外部实体、禁用 XInclude。这些是 **Xerces 专有 feature**，Android 的实现是
 * Harmony `DocumentBuilderFactoryImpl`，**对不认识的 feature 会抛
 * `ParserConfigurationException`**（Xerces 自己也允许抛）。
 *
 * 一旦抛，`runCatching` 把它吞成 `null` ⇒ 整个解析失败 ⇒ 真机上「有 ComicInfo.xml
 * 的包」被当成「没有 ComicInfo.xml」⇒ 元数据页当前值被清空 ⇒ 用户点更新时把空值写回
 * 文件（2026-10-27 真机实测：11,451,392 B 的包更新后变成 11,451,329 B，XML 从 295 B
 * 缩成 232 B 全空标签）。
 *
 * ## 离线为什么能测
 *
 * Robolectric 用的是 **JDK Xerces**（探针实测 `FACTORY=com.sun.org.apache.xerces...`），
 * 所以真机那条路径离线跑不出来。这里改成**注入一个所有 `setFeature` 都抛异常**的
 * 工厂 —— 等价于「运行时一个 feature 都不支持」，比真机更极端。加固实现必须在这种
 * 环境下**仍然解析成功**，靠的是「逐个 try，失败忽略」+「DOCTYPE 在文本层拒掉」。
 */
class ComicInfoHardeningTest {

    @Test
    fun `parsing survives a factory that rejects every feature`() {
        val data = assertNotNull(
            parseComicInfo(
                "<ComicInfo><Title>标题</Title><Number>3</Number></ComicInfo>",
                hostileFactory(),
            ),
            "加固失败不得让整份 XML 解析不出来",
        )
        assertEquals("标题", data.title)
        assertEquals(3, data.number)
    }

    @Test
    fun `a doctype is still rejected when the parser has no feature support`() {
        // feature 一个都设不上时，XXE 防线只剩文本层这一道。它必须独立成立。
        assertNull(
            parseComicInfo(
                """
                <?xml version="1.0"?>
                <!DOCTYPE ComicInfo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <ComicInfo><Title>&xxe;</Title></ComicInfo>
                """.trimIndent(),
                hostileFactory(),
            ),
            "没有 feature 支持时也必须拒掉 DOCTYPE",
        )
    }

    @Test
    fun `a doctype is rejected regardless of letter case and spacing`() {
        // DOCTYPE 出现在根元素之后也不许放过 —— 判据是「全文有没有」，不是「开头有没有」。
        assertNull(
            parseComicInfo(
                "<ComicInfo><Title>x</Title></ComicInfo>\n<!doctype comicinfo>",
                hostileFactory(),
            ),
        )
        assertNull(parseComicInfo("<!DocType  ComicInfo><ComicInfo><Title>x</Title></ComicInfo>", hostileFactory()))
    }

    @Test
    fun `a broken document still yields null with a hostile factory`() {
        assertNull(parseComicInfo("<ComicInfo><Title>未闭合", hostileFactory()))
        assertNull(parseComicInfo("", hostileFactory()))
    }

    // ---------- 夹具 ----------

    /**
     * 所有 `setFeature` / `setAttribute` 都抛异常的工厂 —— 模拟「运行时一个加固项都不支持」。
     *
     * 其余方法一律委托给真实工厂，这样 `newDocumentBuilder()` 产出的仍是能解析的 builder，
     * 测的只是「加固配置失败时会不会连解析一起废掉」。
     */
    private fun hostileFactory(): DocumentBuilderFactory {
        val delegate = DocumentBuilderFactory.newInstance()
        return object : DocumentBuilderFactory() {
            override fun newDocumentBuilder(): DocumentBuilder = delegate.newDocumentBuilder()

            override fun setFeature(name: String, value: Boolean) {
                throw ParserConfigurationException("不支持的 feature：$name")
            }

            override fun getFeature(name: String): Boolean =
                throw ParserConfigurationException("不支持的 feature：$name")

            override fun setAttribute(name: String, value: Any?) {
                throw IllegalArgumentException("不支持的 attribute：$name")
            }

            override fun getAttribute(name: String): Any? =
                throw IllegalArgumentException("不支持的 attribute：$name")

            override fun setValidating(value: Boolean) = delegate.setValidating(value)
            override fun isValidating(): Boolean = delegate.isValidating
            override fun setIgnoringElementContentWhitespace(value: Boolean) =
                delegate.setIgnoringElementContentWhitespace(value)
            override fun isIgnoringElementContentWhitespace(): Boolean =
                delegate.isIgnoringElementContentWhitespace
            override fun setCoalescing(value: Boolean) = delegate.setCoalescing(value)
            override fun isCoalescing(): Boolean = delegate.isCoalescing
            override fun setIgnoringComments(value: Boolean) = delegate.setIgnoringComments(value)
            override fun isIgnoringComments(): Boolean = delegate.isIgnoringComments
            override fun setExpandEntityReferences(value: Boolean) =
                delegate.setExpandEntityReferences(value)
            override fun isExpandEntityReferences(): Boolean = delegate.isExpandEntityReferences
            override fun setNamespaceAware(value: Boolean) = delegate.setNamespaceAware(value)
            override fun isNamespaceAware(): Boolean = delegate.isNamespaceAware
            override fun setXIncludeAware(value: Boolean) = delegate.setXIncludeAware(value)
            override fun isXIncludeAware(): Boolean = delegate.isXIncludeAware
        }
    }
}
