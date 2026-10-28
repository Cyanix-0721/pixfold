package com.cyanix.pixfold.ui.library

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.cyanix.pixfold.R
import com.cyanix.pixfold.TestText
import com.cyanix.pixfold.domain.pack.CbzMetaUpdateOutcome
import com.cyanix.pixfold.domain.pack.UpdateFailure
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 验收标准 6：**六种失败各自的文案可读、可区分**（spec §10 第 6 条）。
 *
 * ## 「可区分」为什么能离线断，而「可读」不能
 *
 * 「可区分」是一条**集合性质**：六条文案两两不相等，且每个 [UpdateFailure] 都有自己的去处。
 * 它跟字体、换行、视口宽度全无关 —— 资源里两串字符相不相等，在任何环境下都有确定答案。
 * 所以这一半必须在离线钉死，不能只靠真机肉眼看。
 *
 * 「可读」是另一半：字会不会被截断、换行后还能不能看懂、技术异常有没有漏到第一行 ——
 * 那些只有真机能断（§7.4 表），本文件**不伪造**这类断言。
 *
 * ## 为什么直接渲染结果框，而不是走整个 LibrarySteps 流程
 *
 * 走完整流程每验一种失败都要：进元数据页 → 选卷 → 点更新 → 确认 → 等协程。
 * 六种就是六遍，其中五遍的差别只在 Fake 的返回值上 —— 测到的是流程重复，
 * 不是文案本身。[CbzUpdateFlowTest] 已经有一条走完整流程的 NoSpace 用例守着接线，
 * 这里专注在「六条文案各自长什么样」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h2000dp")
class CbzUpdateFailureTextTest {

    @get:Rule
    val rule = createComposeRule()

    /**
     * 六种**需要给用户文案**的失败。
     *
     * [UpdateFailure.Cancelled] 不在其中：取消是用户自己按的，弹「更新失败：已取消」
     * 等于把用户的决定说成一次故障（spec §8.5，由 [cancelling gives no message at all] 守）。
     */
    private val reasons = listOf(
        UpdateFailure.TargetMissing,
        UpdateFailure.PermissionLost,
        UpdateFailure.NoSpace,
        UpdateFailure.NotAZip,
        UpdateFailure.ReadFailed,
        UpdateFailure.ReplaceFailed,
    )

    /** 每条失败对应的资源 id —— 与 PixFoldText 的映射逐条对齐，改映射这里会红。 */
    private fun expectedStringId(reason: UpdateFailure): Int = when (reason) {
        UpdateFailure.TargetMissing -> R.string.domain_update_fail_target_missing
        UpdateFailure.PermissionLost -> R.string.domain_update_fail_permission_lost
        UpdateFailure.NoSpace -> R.string.domain_update_fail_no_space
        UpdateFailure.NotAZip -> R.string.domain_update_fail_not_a_zip
        UpdateFailure.ReadFailed -> R.string.domain_update_fail_read_failed
        UpdateFailure.ReplaceFailed -> R.string.domain_update_fail_replace_failed
        UpdateFailure.Cancelled -> error("取消不给文案，不该走到这里")
    }

    /**
     * 结果框内容由 state 驱动 —— **不能每个用例都 setContent 一次**：
     * Compose 测试的规则只允许 setContent 调一次，第二次会抛异常。
     * 逐条验证六种失败时靠改 state 换内容，同一个组合树复用。
     */
    private val shown = mutableStateOf<CbzMetaUpdateOutcome.Failed?>(
        CbzMetaUpdateOutcome.Failed(UpdateFailure.TargetMissing),
    )

    @Before
    fun setUpContent() {
        rule.setContent {
            shown.value?.let {
                CbzUpdateResultDialog(
                    outcome = it,
                    volumeName = "第01卷.cbz",
                    onDone = {},
                )
            }
        }
        rule.waitForIdle()
    }

    private fun showFailure(reason: UpdateFailure, detail: String? = null) {
        shown.value = CbzMetaUpdateOutcome.Failed(reason, detail)
        rule.waitForIdle()
    }

    private fun bodyText(): String =
        rule.onNodeWithTag(TAG_UPDATE_RESULT_BODY)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString("") { it.text }

    @Test
    fun `every failure reason renders its own message`() {
        reasons.forEach { reason ->
            showFailure(reason)
            assertEquals(
                TestText.of(expectedStringId(reason)),
                bodyText(),
                reason.name + " 渲染出的文案必须就是它映射到的那一条",
            )
        }
    }

    /**
     * 「可区分」的硬判据：六条文案**两两不同**。
     *
     * 如果两个枚举映射到同一个 TextKey（或两条资源文案写重了），上面那条逐条断言
     * 照样全绿 —— 因为每条都「等于它自己映射的那条」。用户看到的却是同一句话配上
     * 两个完全不同的下一步（「腾空间」与「换个包」）。
     */
    @Test
    fun `the six messages are pairwise distinct`() {
        val messages = reasons.associateWith { TestText.of(expectedStringId(it)) }
        assertEquals(reasons.size, messages.size)
        assertEquals(
            reasons.size,
            messages.values.toSet().size,
            "六条失败文案必须两两不同，重复的是：" +
                messages.entries.groupBy { it.value }.filter { it.value.size > 1 }.keys,
        )
        messages.forEach { (reason, text) ->
            assertTrue(text.isNotBlank(), reason.name + " 的文案不能是空白")
        }
    }

    /**
     * 文案里不许有未替换的格式占位符。
     *
     * 六条失败文案都不带参数，但万一将来有人给某条加了占位符却漏传参，
     * 界面上就会直接显示占位符本身 —— 用户看不懂，而 TestText.of 传了参也照样「相等」。
     */
    @Test
    fun `no message leaks a format placeholder`() {
        reasons.forEach { reason ->
            val text = TestText.of(expectedStringId(reason))
            assertTrue(
                !text.contains("%"),
                reason.name + " 的文案里有未替换的占位符：" + text,
            )
        }
    }

    /**
     * 技术补充（detail）追加在文案之后，且**不改写**文案本身。
     *
     * 真机上最容易出现的形态是「文案对、但下面拖着一串英文异常」。那种情况用户
     * 至少还能看懂第一句，所以顺序不能反 —— 先给人话，再给技术补充。
     */
    @Test
    fun `technical detail follows the readable message rather than replacing it`() {
        showFailure(UpdateFailure.ReplaceFailed, detail = "无法把原包改名为 x.cbz.updating.bak")
        val body = bodyText()
        val base = TestText.of(R.string.domain_update_fail_replace_failed)
        assertTrue(body.startsWith(base), "第一行必须是给用户看的那句，实际是：" + body)
        assertTrue(body.contains("x.cbz.updating.bak"), "技术补充必须还在，实际是：" + body)
    }

    /** 取消：不弹结果框（文案为 null），所以这条路径上不存在「可读性」问题。 */
    @Test
    fun `cancelling gives no message at all`() {
        assertNull(
            updateFailureKey(UpdateFailure.Cancelled),
            "取消不该被说成一次故障",
        )
    }

    /** 反过来钉住：其余六种**必须**有文案，否则结果框会是个空框。 */
    @Test
    fun `every non-cancelled reason has a message key`() {
        reasons.forEach { reason ->
            assertNotNull(updateFailureKey(reason), reason.name + " 没有文案键，结果框会是空的")
        }
    }

    /**
     * 六个文案键两两不同 —— 在**键**这一层再守一次。
     *
     * 上面 [the six messages are pairwise distinct] 守的是资源文案；这一条守的是
     * updateFailureKey 的 when 分支。有人手滑把两个分支写成同一个 TextKey 时，
     * 资源层可能还没重，这一条会先红。
     */
    @Test
    fun `the six reasons map to six distinct text keys`() {
        val keys = reasons.map { updateFailureKey(it) }
        assertEquals(reasons.size, keys.toSet().size, "有两个失败原因共用了同一个文案键：" + keys)
    }
}
