package com.cyanix.pixfold.domain.naming

import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.domain.model.SourceItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 命名结构初值的「根目录名」组件该填什么 —— 这是 2026-10-19 真机抓到的一处不一致。
 *
 * ## 现象
 *
 * 步骤 2 的标题已经清洗成 `命名结构 · 彼女がボクの知らないところで（24 张）`,
 * 但下面的「新名称」预览仍是 `[Cior (Ken-1)] 彼女がボクの知らないところで_001.jpg` ——
 * **同一页上同一个名字有两个形态**。
 *
 * ## 根因
 *
 * `NamingScheme.rootDirName` 是 `RootDir` 组件的取值,而它播种自
 * `ImageCollection.rootDirName`(= 原始目录名)。`RootDir` 组件产出的正是**新文件名**,
 * 按用户裁决「落盘文件名用清洗后的名字」,它就该拿 `derived.title`。
 *
 * ## 为什么单独抽一个函数
 *
 * 这个初值在 5 处被重复构造(`PixFoldApp` 两次 / `WorkflowASteps` 两次 / `NamingPage` 一次)。
 * 分散构造意味着「哪一处忘了改」只有真机才看得出来 —— 抽成单一入口才能被单测钉住。
 */
class NamingSeedTest {

    private fun collection(id: String, name: String) = ImageCollection(
        id = id,
        name = name,
        rootDirName = name,
        images = listOf(
            SourceItem(
                id = "$id-1",
                collectionId = id,
                dir = "",
                baseName = "001",
                ext = "jpg",
                sizeBytes = 1024,
                modifiedEpochMillis = 0,
                createdEpochMillis = null,
                seed = 1,
            ),
        ),
    )

    /** 真实素材:根目录名组件必须拿到清洗后的标题,不带作者前缀、不带末尾标注。 */
    @Test
    fun `根目录名组件用清洗后的标题而不是原始目录名`() {
        val coll = collection("real", "[Cior (Ken-1)] 彼女がボクの知らないところで――2 [DL版]")

        assertEquals("彼女がボクの知らないところで――2", namingSchemeSeedOf(listOf(coll)).rootDirName)
    }

    /** 反向:原始目录名不得出现在初值里(否则上面那条对「没清洗」也成立)。 */
    @Test
    fun `原始目录名不出现在命名结构初值里`() {
        val coll = collection("real", "[Cior (Ken-1)] 作品名 [DL版]")

        val seeded = namingSchemeSeedOf(listOf(coll)).rootDirName

        assertTrue("[DL版]" !in seeded, "末尾发行标注必须被剥掉,实际:$seeded")
        assertTrue("(Ken-1)" !in seeded, "作者括号必须被剥掉,实际:$seeded")
    }

    /** 名字本来就干净时不应被改动(mock 集合全属此类,既有测试靠这条不变)。 */
    @Test
    fun `干净的名字原样作为初值`() {
        assertEquals("trip", namingSchemeSeedOf(listOf(collection("trip", "trip"))).rootDirName)
        assertEquals("进击的巨人 第02卷", namingSchemeSeedOf(listOf(collection("aot", "进击的巨人 第02卷"))).rootDirName)
    }

    /** 空列表给空串,不抛(mock 兜底与「工作区里没有集合」都会走到)。 */
    @Test
    fun `空集合列表给空根目录名`() {
        assertEquals("", namingSchemeSeedOf(emptyList()).rootDirName)
    }

    /** 只取第一个集合 —— 与既有行为一致(切集合不改批次级命名结构,规格 §6.3)。 */
    @Test
    fun `取第一个集合作为根目录名来源`() {
        val first = collection("a", "[作者A] 第一本")
        val second = collection("b", "[作者B] 第二本")

        assertEquals("第一本", namingSchemeSeedOf(listOf(first, second)).rootDirName)
    }

    /** 其余字段必须是默认值(不能顺手带上别的集合的东西)。 */
    @Test
    fun `除根目录名外其余字段保持默认`() {
        val seeded = namingSchemeSeedOf(listOf(collection("x", "作品")))
        val defaults = com.cyanix.pixfold.domain.model.NamingScheme(rootDirName = "")

        assertEquals(defaults.components, seeded.components)
        assertEquals(defaults.indexStart, seeded.indexStart)
        assertEquals(defaults.indexPadding, seeded.indexPadding)
        assertEquals(defaults.extPolicy, seeded.extPolicy)
        assertEquals(defaults.sanitize, seeded.sanitize)
        assertEquals(defaults.replacement, seeded.replacement)
    }
}
