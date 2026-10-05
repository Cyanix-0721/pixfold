package com.pixfold.d1.ui.text

import com.pixfold.d1.R
import com.pixfold.d1.domain.text.Msg
import com.pixfold.d1.domain.text.TextKey
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [WARN_ICONS] 这张表的**完整性**守护(2026-10-05 用户要求 emoji 换矢量图)。
 *
 * **为什么这张表需要单独的测试**:去掉 `⚠️` 前缀之后,"这条文案是警告"的语义
 * 就**只**存在于这张表里。表里少一条,对应的警告行会静默退化成普通文案 ——
 * 既没有 emoji 也没有图标,而 [EmojiFreeSourceTest] 照样全绿(它只查"有没有 emoji",
 * 不查"该有的图标在不在"),[WarningIconRenderTest] 也照样全绿(它只查图本身画得对不对)。
 * 这三者各守一段,**缺一就会出现"三绿而功能已坏"**。
 *
 * 本测试同时钉住三个方向:
 *  1. 11 个曾带前缀的键 -> 三角形图标(**少一个就红**);
 *  2. 非警示键 -> `null`(负向对照:没有它,一个"对谁都返回图标"的表也能全绿);
 *  3. `TextKey` 一侧经 [TextKey.resId] 折进同一张表的那条路径真的通。
 */
class WarnIconMappingTest {

    /**
     * 曾带 `⚠️` 前缀、改判为矢量图标的全部键(权威名单,来自 RED 取证时
     * `artifacts/emoji_scan.py` 报出的 27 行 / 13 键)。
     *
     * 其中 11 个进本表;另 2 个是 `⛔` 的退化键(`plan_skip_reason_prefix`、
     * `a_plan_skip_reason`),去前缀后只剩裸 `%1$s`,已**整键删除**,
     * 其图标由调用点直接给 [R.drawable.ic_forbidden](见 [WarnLine] 的 KDoc)。
     */
    private val warningKeys = listOf(
        R.string.domain_danger_dialog_title,
        R.string.domain_summary_clean_empty_dirs_warning,
        R.string.domain_summary_overwrite_cbz,
        R.string.domain_summary_delete_source_dir_warning,
        R.string.domain_rename_summary_clean_empty_dirs,
        R.string.plan_danger_action,
        R.string.report_undo_warning_title,
        R.plurals.a_naming_conflicts,
        R.string.pack_page_issues,
        R.string.pack_delete_warning,
        R.string.pack_excluded_warning,
    )

    @Test
    fun `the table has exactly the eleven warning keys`() {
        assertEquals(
            warningKeys.size,
            WARN_ICONS.size,
            "警示键的数量变了。增删都请连同本名单一起改 —— 少一条意味着某行警告" +
                "静默失去图标,而 EmojiFreeSourceTest 与图标渲染测试都不会红。",
        )
    }

    @Test
    fun `every previously prefixed warning key maps to the triangle icon`() {
        for (key in warningKeys) {
            assertEquals(
                R.drawable.ic_warning_triangle,
                warnIcon(key),
                "键 0x${Integer.toHexString(key)} 应映射到三角形警示图标;" +
                    "若为 null,说明表里少了这一条,该警告行会退化成无图标的普通文案",
            )
        }
    }

    @Test
    fun `non warning keys deliberately have no icon`() {
        // 负向对照:这些键今天都以普通 Text 渲染(其中若干行还保留 "· " 前缀,
        // PlanUiTest / PlanWiringTest / NamingPageTest 正是按 "· " 断言的),
        // 一旦被误加进表里,行首的圆点会被图标顶掉,那 4 处断言随即失败。
        val notWarnings = listOf(
            R.string.domain_summary_source_dir_prefix,
            R.string.domain_summary_source_dir_keep,
            R.string.domain_general_confirm_title,
            R.string.common_cancel,
            R.string.pack_page_issue_separator,
        )
        for (key in notWarnings) {
            assertNull(
                warnIcon(key),
                "键 0x${Integer.toHexString(key)} 不是警示文案,不该有图标",
            )
        }
    }

    @Test
    fun `TextKey carried warnings reach the same table through resId`() {
        // 5 个带前缀的键是 TextKey,经 textOf() 渲染;它们必须能走到同一张表,
        // 否则"领域层文案"与"资源键"会各留一份名单并逐渐漂移。
        val textKeyWarnings = listOf(
            TextKey.DangerDialogTitle,
            TextKey.SummaryCleanEmptyDirsWarning,
            TextKey.SummaryOverwriteCbz,
            TextKey.SummaryDeleteSourceDirWarning,
            TextKey.RenameSummaryCleanEmptyDirs,
        )
        for (key in textKeyWarnings) {
            assertEquals(
                R.drawable.ic_warning_triangle,
                warnIcon(Msg(key)),
                "$key 应经 TextKey.resId() 折进警示表并命中三角形图标",
            )
        }
    }

    @Test
    fun `ordinary domain messages carry no icon`() {
        // 负向对照(领域层一侧):打包计划行 `· 创建 CBZ …` 必须保持无图标。
        assertNull(warnIcon(Msg(TextKey.PackCreateCbz)), "普通计划行不该有图标")
        assertNull(warnIcon(Msg(TextKey.PackPackPages)), "普通计划行不该有图标")
    }

    @Test
    fun `both warning icons are distinct drawables so shape differs from colour alone`() {
        // ⚠️ 与 ⛔ 的区别必须体现在**形状**上(实心警告 vs 空心禁止),不能只靠颜色 ——
        // 色盲用户同样要能分辨。这条断言挡住"两个图标被改成同一张图"。
        assertTrue(
            R.drawable.ic_warning_triangle != R.drawable.ic_forbidden,
            "三角形与禁止图标必须是两个不同的 drawable",
        )
        assertNotNull(warnIcon(R.string.plan_danger_action))
    }
}
