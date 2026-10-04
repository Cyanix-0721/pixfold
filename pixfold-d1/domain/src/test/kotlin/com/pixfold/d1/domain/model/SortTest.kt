package com.pixfold.d1.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 排序规则的构造约束(规格 §5.1:1–4 级)。
 *
 * `SortRule` 的两条 `require` 此前**没有任何 `assertFailsWith` 断言**
 * (领域测试里的 `assertFailsWith` 只出现在 `pack/`、`plan/` 几个文件),
 * `MAX_LEVELS` 的引用计数是 0 —— 于是"把上限放宽到 5 级"与
 * "把非空判定改成恒真"两个变异体都存活了。这里把边界两侧都钉住。
 */
class SortTest {

    private fun key(field: SortField = SortField.NaturalName) = SortKey(field, true)

    @Test
    fun `a rule needs at least one level`() {
        // 空规则会让排序退化成"没有依据",UI 的下拉也不允许清空最后一级
        assertFailsWith<IllegalArgumentException>("空排序规则必须被拒绝") {
            SortRule(emptyList())
        }
        assertEquals(1, SortRule(listOf(key())).keys.size)
    }

    @Test
    fun `a rule accepts up to four levels`() {
        // 恰好 4 级是合法的(边界内侧);MAX_LEVELS 必须是规格写的 4
        assertEquals(4, SortRule.MAX_LEVELS, "级数上限是规格 §5.1 的 4")
        assertEquals(4, SortRule(List(4) { key() }).keys.size, "4 级必须被接受")
    }

    @Test
    fun `a rule rejects a fifth level`() {
        // 边界外侧:5 级必须被拒绝(上限被悄悄放宽 1 时这条会失败)
        assertFailsWith<IllegalArgumentException>("5 级超出上限,必须被拒绝") {
            SortRule(List(5) { key() })
        }
    }

    @Test
    fun `each level keeps its own field and direction`() {
        val rule = SortRule(
            listOf(
                SortKey(SortField.DirName, true),
                SortKey(SortField.NaturalName, false),
            ),
        )
        assertEquals(SortField.DirName, rule.keys[0].field)
        assertEquals(true, rule.keys[0].ascending)
        assertEquals(SortField.NaturalName, rule.keys[1].field)
        assertEquals(false, rule.keys[1].ascending, "每级各有各的方向,不得互相串")
    }

    @Test
    fun `the default rule is dir ascending then natural name ascending`() {
        // 默认规则的级序与方向是"打开工作区就能看到的顺序",属于可见行为
        assertEquals(
            listOf(
                SortKey(SortField.DirName, true),
                SortKey(SortField.NaturalName, true),
            ),
            defaultRule().keys,
        )
    }
}
