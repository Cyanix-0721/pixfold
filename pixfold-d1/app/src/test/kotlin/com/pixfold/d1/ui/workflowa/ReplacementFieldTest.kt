package com.pixfold.d1.ui.workflowa

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasTestTag
import com.pixfold.d1.domain.mock.MockData
import com.pixfold.d1.domain.model.ImageCollection
import com.pixfold.d1.domain.model.NameComponent
import com.pixfold.d1.domain.model.NameComponentKind
import com.pixfold.d1.domain.model.NamingScheme
import com.pixfold.d1.domain.model.NamingState
import com.pixfold.d1.domain.naming.sanitizeName
import com.pixfold.d1.domain.sort.pageOrderOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 验收第 18 项:「必须人工输入的字段都有明确入口」—— **替换字符**这一项的 UI 层断言。
 *
 * 背景:该字段此前只存在于领域层(`NamingScheme.replacement`),UI **无任何入口**,
 * 属"有字段无入口"的不一致(离线盘点验收第 18 项时发现,用户 2026-09-19 定夺补上)。
 *
 * 本测试守三件事:
 *  1. 入口**存在**且可改 —— 且改完**建议名真的跟着变**(否则就是"假控件");
 *  2. 只在**开启自动清洗**时出现 —— 关闭时它完全不起作用,留个改了没反应的框是误导;
 *  3. 留空的语义被**说清** —— 留空 = 删除非法字符(不是"没填")。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2000dp")
class ReplacementFieldTest {

    @get:Rule
    val rule = createComposeRule()

    private val collection: ImageCollection = MockData.workspaceA.collections.first()

    /** 含「原文件名」组件的方案 —— 让非法字符真的进入输出。 */
    private val illegalComponents = listOf(
        NameComponent(NameComponentKind.RootDir),
        NameComponent(NameComponentKind.OrigName),
    )
    /**
     * 挂载命名页。
     *
     * **默认启用「原文件名」组件**(`OrigName`)—— 关键前提:
     * 默认方案是 `根目录名 + 序号`,原始文件名**根本不进入输出**,
     * 此时替换字符改什么都看不出来。只有让输出包含原始文件名,
     * `replacement` 才真正参与求值(第一版测试漏了这点,断言失败后修正)。
     */
    private fun setPage(
        scheme: NamingScheme = NamingScheme(
            rootDirName = "旅行照片",
            components = listOf(
                NameComponent(NameComponentKind.RootDir),
                NameComponent(NameComponentKind.OrigName),
            ),
        ),
    ) {
        // 用**含非法字符**的文件名观察"替换字符"是否真的生效
        // (fileName 是 baseName + ext 的派生值,故改 baseName)
        val c = collection.copy(
            images = listOf(collection.images.first().copy(baseName = "封:面?")),
        )
        rule.setContent {
            NamingPage(
                collection = c,
                order = pageOrderOf(c.images),
                initialState = NamingState(scheme = scheme),
                contentInsets = WindowInsets(0, 0, 0, 0),
            )
        }
    }

    /** 唯一那张图的建议名(读 EditableText,避免与 M3 的 label 文本混淆)。 */
    private fun proposedName(): String =
        editableTextOf(proposalFieldTag(collection.images.first().id))

    /**
     * 打开「自动清洗」说明对话框 —— 替换字符输入框就在其中。
     *
     * **为什么不放在编辑器行内**:命名页整页只有一个 LazyColumn,头部(编辑器)在真机上
     * 已高达 ~862dp,视口仅剩约 130dp 给建议表;再插一行会把**主工作区**挤出屏幕
     * (实测:加行后建议行完全不组合,14 项既有测试失败)。
     * 放进对话框零高度成本,且与"清洗规则"的说明同处一室。
     */
    private fun openSanitizeDialog() {
        rule.onNodeWithTag(TAG_PROPOSAL_LIST)
            .performScrollToNode(hasTestTag(TAG_SCHEME_SANITIZE_INFO))
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_SCHEME_SANITIZE_INFO).performClick()
        rule.waitForIdle()
    }

    @Test
    fun `replacement field is available when sanitize is on`() {
        setPage(NamingScheme(rootDirName = "旅行照片", sanitize = true, components = illegalComponents))
        openSanitizeDialog()
        rule.onNodeWithTag(TAG_SCHEME_REPLACEMENT).assertExists()
    }

    @Test
    fun `replacement field is hidden when sanitize is off`() {
        // 清洗关闭时 replacement 完全不起作用(领域层 `if (scheme.sanitize)`)。
        // 留一个改了没反应的输入框 = 假控件,故隐藏。
        setPage(NamingScheme(rootDirName = "旅行照片", sanitize = false, components = illegalComponents))
        openSanitizeDialog()
        rule.onNodeWithTag(TAG_SCHEME_REPLACEMENT).assertDoesNotExist()
    }

    @Test
    fun `changing replacement really changes the suggested name`() {
        // 核心断言:不只是"框能改",而是**改完建议名真的变** ——
        // 否则就是 P3 那类"领域做完、界面没接上"的死控件。
        setPage(NamingScheme(rootDirName = "旅行照片", sanitize = true, replacement = "_", components = illegalComponents))
        openSanitizeDialog()
        // 先把非法字符替换为 "-",再比对建议名
        rule.onNodeWithTag(TAG_SCHEME_REPLACEMENT).performTextClearance()
        rule.onNodeWithTag(TAG_SCHEME_REPLACEMENT).performTextInput("-")
        rule.waitForIdle()

        // 断言要**精确**:不能写"不含 _" —— `_` 同时是组件间**分隔符**,
        // 与替换字符无关(第一版这么写导致误判,已修正)。
        // 原始文件名 `封:面?.jpg` 里的 `:` 与 `?` 都应变成替换字符。
        val name = proposedName()
        assertEquals(
            "旅行照片_封-面-.jpg",
            name,
            "非法字符 : 与 ? 都应被替换为「-」；分隔符 _ 保持不变",
        )
    }

    @Test
    fun `empty replacement deletes the illegal character and says so`() {
        setPage(NamingScheme(rootDirName = "旅行照片", sanitize = true, replacement = "_", components = illegalComponents))
        openSanitizeDialog()
        rule.onNodeWithTag(TAG_SCHEME_REPLACEMENT).performTextClearance()
        rule.waitForIdle()

        // 留空 = 删除非法字符(领域层 append("")),必须给出说明而非静默
        rule.onNodeWithText("留空 = 直接删除非法字符").assertExists()
    }

    @Test
    fun `reset button restores the default replacement and is disabled at default`() {
        setPage(NamingScheme(rootDirName = "旅行照片", sanitize = true, replacement = "_", components = illegalComponents))
        openSanitizeDialog()
        // 已是默认 -> 按钮禁用(避免"点了没反应"的假按钮)
        rule.onNodeWithTag(TAG_SCHEME_REPLACEMENT_RESET).assertIsNotEnabled()

        rule.onNodeWithTag(TAG_SCHEME_REPLACEMENT).performTextClearance()
        rule.onNodeWithTag(TAG_SCHEME_REPLACEMENT).performTextInput("#")
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_SCHEME_REPLACEMENT_RESET).assertIsEnabled()

        rule.onNodeWithTag(TAG_SCHEME_REPLACEMENT_RESET).performClick()
        rule.waitForIdle()
        assertEquals(
            "_",
            editableTextOf(TAG_SCHEME_REPLACEMENT),
            "点「默认」后应回到领域默认值（与领域常量同源）",
        )
    }

    @Test
    fun `replacement accepts only a single character`() {
        // 替换字符多于一个会让"一个非法字符"变成多个字符,破坏逐字符替换的可预期性。
        setPage(NamingScheme(rootDirName = "旅行照片", sanitize = true, components = illegalComponents))
        openSanitizeDialog()
        rule.onNodeWithTag(TAG_SCHEME_REPLACEMENT).performTextClearance()
        rule.onNodeWithTag(TAG_SCHEME_REPLACEMENT).performTextInput("abc")
        rule.waitForIdle()
        assertEquals("a", editableTextOf(TAG_SCHEME_REPLACEMENT), "应只保留首个字符")
    }

    private fun editableTextOf(tag: String): String {
        val node = rule.onNodeWithTag(tag).fetchSemanticsNode()
        val key = SemanticsProperties.EditableText
        return if (node.config.contains(key)) node.config[key].text else ""
    }

    // ---- 实时示例(方案 A,用户 2026-09-19 裁决) ----

    @Test
    fun `dialog shows a concrete cleaning example`() {
        setPage(NamingScheme(
            rootDirName = "旅行照片",
            sanitize = true,
            replacement = "_",
            components = illegalComponents,
        ))
        openSanitizeDialog()
        rule.onNodeWithText("示例：封:面?.jpg → 封_面_.jpg").assertExists()
    }

    @Test
    fun `example follows the replacement character`() {
        // 示例必须是**实时**的:换了替换字符,示例立刻跟着变。
        setPage(NamingScheme(
            rootDirName = "旅行照片",
            sanitize = true,
            replacement = "_",
            components = illegalComponents,
        ))
        openSanitizeDialog()
        rule.onNodeWithTag(TAG_SCHEME_REPLACEMENT).performTextClearance()
        rule.onNodeWithTag(TAG_SCHEME_REPLACEMENT).performTextInput("#")
        rule.waitForIdle()
        rule.onNodeWithText("示例：封:面?.jpg → 封#面#.jpg").assertExists()
    }

    @Test
    fun `example is computed by the real sanitize function`() {
        // 防"文档说谎":示例若被写成硬编码字符串,清洗规则一改就会与真实行为不符,
        // 而那种偏差极难被发现。这里直接拿**领域函数**的结果与界面文案对照,
        // 钉死"示例 = 真实清洗结果"。
        setPage(NamingScheme(
            rootDirName = "旅行照片",
            sanitize = true,
            replacement = "@",
            components = illegalComponents,
        ))
        openSanitizeDialog()
        val expected = sanitizeName("封:面?.jpg", "@")
        rule.onNodeWithText("示例：封:面?.jpg → $expected").assertExists()
        assertEquals("封@面@.jpg", expected, "前置:领域函数应把 : 与 ? 都替换为 @")
    }
}
