package com.cyanix.pixfold.ui.components

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 芯片标签的长度上限(2026-10-21 用户裁决)。
 *
 * 用户原话:「chip跟随内容长度变化,不过要有长度限制,比如最长10个字(过长则显示title首尾)」。
 *
 * ## 为什么截断要发生在渲染前,而不是靠 `maxLines = 1` + `TextOverflow.Ellipsis`
 *
 * 省略号截尾只会砍掉尾部,同系列两本 `…彼女がボクの知らないところで第01巻` 与 `…第02巻`
 * 截完长得一模一样 —— 恰恰把「区分它们」的信息丢了。首尾保留则两本的尾部(卷号)都在,
 * 一眼能分辨。
 *
 * ## 为什么单位是字符数而不是 dp
 *
 * 判据是**字符数**,不是 dp:10 个中文与 10 个拉丁字母的物理宽度不同,但那是排版后果,
 * 不是这条规则本身。dp 口径还测不出来 —— Robolectric 把文本宽度全算成 ~0
 * (本目录 `ChipRowWrappingTest` 顶部有实测读数)。
 *
 * 上限 2026-10-22 由 10 改到 24(用户:「显示太短了,首尾几个字都要显示」),
 * 前提也跟着变了:芯片内文字此时已允许折行,宽度上限不再需要「够排进一行」。
 */
class ChipLabelTruncationTest {

    @Test
    fun a_short_label_is_left_untouched() {
        assertEquals("第01卷", truncateChipLabel("第01卷"))
    }

    @Test
    fun a_label_at_the_limit_is_left_untouched() {
        val exactlyAtLimit = "一二三四五六七八九十一二三四五六七八九十一二三四"
        assertEquals(CHIP_LABEL_MAX_CHARS, exactlyAtLimit.length, "夹具本身必须正好在限上")
        assertEquals(exactlyAtLimit, truncateChipLabel(exactlyAtLimit))
    }

    @Test
    fun a_long_label_keeps_its_head_and_tail() {
        // 夹具必须**超过 24 字上限**,否则截断根本不该发生(2026-10-22 上限放宽后
        // 原来那条 18 字的夹具落进了「不截断」区间,断 contains("…") 必然失败)。
        val long = "[青水庵 (にやすけ 青水庵)] 彼女がボクの知らないところで第01巻"
        val cut = truncateChipLabel(long)

        assertTrue(cut.length <= CHIP_LABEL_MAX_CHARS, "截断后不能超过上限,实际: $cut")
        assertTrue(cut.startsWith(long.take(1)), "首部必须保留,实际: $cut")
        assertTrue(cut.endsWith(long.takeLast(1)), "尾部必须保留(卷号在那里),实际: $cut")
        assertTrue(cut.contains("…"), "首尾之间必须有省略标记,实际: $cut")
    }

    @Test
    fun truncating_never_returns_more_than_the_limit() {
        val veryLong = "あ".repeat(200)
        assertEquals(CHIP_LABEL_MAX_CHARS, truncateChipLabel(veryLong).length)
    }

    @Test
    fun the_limit_is_twenty_four_characters() {
        assertEquals(24, CHIP_LABEL_MAX_CHARS, "上限 2026-10-22 由用户改判:10 字显示太短,首尾都要看得见")
    }

    @Test
    fun a_tiny_limit_falls_back_to_plain_truncation() {
        // 放不下「首 + … + 尾」三件套时,不能拼出比 maxChars 更长的串。
        assertEquals(2, truncateChipLabel("abcdefgh", maxChars = 2).length)
    }
}
