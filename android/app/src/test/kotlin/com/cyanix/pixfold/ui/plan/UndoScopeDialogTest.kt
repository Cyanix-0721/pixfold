package com.cyanix.pixfold.ui.plan

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.cyanix.pixfold.R
import com.cyanix.pixfold.TestText
import com.cyanix.pixfold.domain.mock.MockDataB
import com.cyanix.pixfold.domain.plan.ExecutionReport
import com.cyanix.pixfold.domain.plan.OutputConflictPolicy
import com.cyanix.pixfold.domain.plan.UndoScope
import com.cyanix.pixfold.domain.plan.buildPackagePlans
import com.cyanix.pixfold.domain.plan.executePackagePlans
import com.cyanix.pixfold.domain.plan.performUndo
import com.cyanix.pixfold.domain.plan.undoRecordOf
import com.cyanix.pixfold.domain.text.Msg
import com.cyanix.pixfold.ui.text.resolveText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **撤销范围警告**的三选一(用户 2026-10-04,m04079 第③项)。
 *
 * 用户逐字:「添加警告，仅选择当前操作生成的撤销还是全部删除还是取消，记得按钮颜色
 * 要区分开，把取消放到最后边」。这句话里有**四条可证伪的要求**,本文件逐条钉住:
 *
 *  1. **有警告**:覆盖了原本已存在的文件时,点撤销**不直接执行**,先弹框;
 *  2. **三选一**:仅撤销本次生成的 / 全部删除(含被覆盖的) / 取消 —— 恰好三条路;
 *  3. **颜色要区分开**:两个执行档是**破坏性动作**(深红填充),取消是纯文字按钮
 *     (无底色)⇒ 由同目录 `UndoScopeDialogStyleTest` 用**像素**证明(语义树看不出);
 *  4. **取消放到最后边**:按钮的**纵向次序**是 安全 → 危险 → 取消,这里用
 *     `getBoundsInRoot().top` 断言 —— 源码里的书写顺序不算数,布局出来的位置才算。
 *
 * ## 为什么"选中档位"必须被断言
 *
 * 最容易犯的错是:界面让用户确认了"全部删除",而回调照旧按安全档回滚 ——
 * 警告弹了、选择做了、**结果没变**,比不弹更糟(用户以为自己授权了删除)。
 * 所以这里两次点击都断言**回调真的收到了那一档**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2000dp")
class UndoScopeDialogTest {

    @get:Rule
    val rule = createComposeRule()

    private val library = MockDataB.library
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** `op-102` 的输出**原本就存在** ⇒ Overwrite 下会真的覆盖掉用户的东西。 */
    private fun overwritingReport(): ExecutionReport {
        val op102 = library.volumes.first { it.id == "op-102" }
        return executePackagePlans(
            buildPackagePlans(listOf(op102), conflictPolicy = OutputConflictPolicy.Overwrite),
        )
    }

    /** `aot-1` 的输出**原本不存在** ⇒ 只新建,不覆盖。 */
    private fun creatingReport(): ExecutionReport {
        val aot1 = library.volumes.first { it.id == "aot-1" }
        return executePackagePlans(buildPackagePlans(listOf(aot1)))
    }

    /**
     * 报告页宿主:把回调收到的**档位**记进状态,便于断言。
     *
     * 与生产接线同形(`WorkflowASteps.kt` / `WorkflowBSteps.kt`),否则这里测的
     * 就是另一个世界的行为。
     */
    private fun host(report: ExecutionReport, received: MutableList<UndoScope>) {
        rule.setContent {
            var message by remember { mutableStateOf<Msg?>(null) }
            ExecutionReportPage(
                report = report,
                undoMessage = message,
                onUndoScope = { scope ->
                    received += scope
                    message = performUndo(undoRecordOf(report, scope)).message
                },
                onDone = {},
            )
        }
    }

    // ================= 1. 有警告 =================

    @Test
    fun `an overwriting run warns before undoing instead of undoing right away`() {
        val report = overwritingReport()
        assertTrue(report.needsUndoWarning, "前置:op-102 那条必须被算成覆盖项")
        val received = mutableListOf<UndoScope>()
        host(report, received)

        rule.onNodeWithTag(TAG_UNDO_SCOPE_DIALOG).assertDoesNotExist()
        rule.onNodeWithTag(TAG_REPORT_UNDO).performClick()

        // 弹了框、且**什么都没回滚** —— 这正是"先警告"的含义。
        rule.onNodeWithTag(TAG_UNDO_SCOPE_DIALOG).assertIsDisplayed()
        assertTrue(received.isEmpty(), "警告阶段不得擅自执行撤销,实际收到 $received")
    }

    @Test
    fun `the warning says how many files were replaced`() {
        val report = overwritingReport()
        host(report, mutableListOf())

        rule.onNodeWithTag(TAG_REPORT_UNDO).performClick()
        rule.onNodeWithText(context.getString(R.string.report_undo_warning_title)).assertIsDisplayed()
        val body = TestText.plural(
            R.plurals.report_undo_warning_body,
            report.overwrittenCount,
            report.overwrittenCount,
        )
        rule.onNodeWithText(body).assertIsDisplayed()

        // ⚠️ 光有上面的等价断言抓不住"正文把数字吞了":期望值取自**同一份资源**,
        // 资源里少了 `%1$d`,期望值和实际值会一起变(轮 14 的盲区)。
        // 故这里独立地钉住「正文里必须出现那个数」—— 不依赖资源怎么写。
        assertTrue(
            body.contains(report.overwrittenCount.toString()),
            "警告正文必须写出被替换的文件数(${report.overwrittenCount}),实际:\"$body\"",
        )
    }

    /**
     * 覆盖**不止一个**时也要报出数量 —— 这条专钉 `<plurals>` 的 `other` 变格。
     *
     * ## 为什么必须单独有一条
     *
     * W19 的 M28 变异(把英文 `other` 变格里的 `%1$d` 删掉)**存活了** ——
     * 因为上面那条用例只有 1 个覆盖项,Android 取的是 `one` 变格,
     * `other` 变格根本没被读到。也就是说:"英文复数形态把数字吞了"这件事故障时无人发现。
     *
     * mock 库里只有 `op-102` 一卷 `outputExists = true`,故这里复制一卷凑出 2 个覆盖项
     * (这是**数据**,不是界面文案,复制 id 即可)。
     */
    @Test
    fun `the warning body still contains the count when more than one file was replaced`() {
        val op102 = library.volumes.first { it.id == "op-102" }
        val second = op102.copy(id = "op-102-b", cbzFileName = "海贼王 第103卷.cbz")
        val report = executePackagePlans(
            buildPackagePlans(listOf(op102, second), conflictPolicy = OutputConflictPolicy.Overwrite),
        )
        assertEquals(2, report.overwrittenCount, "前置:两个覆盖项,且要跨过复数边界")

        host(report, mutableListOf())
        rule.onNodeWithTag(TAG_REPORT_UNDO).performClick()

        val body = TestText.plural(
            R.plurals.report_undo_warning_body,
            report.overwrittenCount,
            report.overwrittenCount,
        )
        println("UndoScopeDialog probe: multi body=\"$body\"")
        rule.onNodeWithText(body).assertIsDisplayed()
        assertTrue(
            body.contains(report.overwrittenCount.toString()),
            "多个文件被替换时正文同样要报出数量,实际:\"$body\"",
        )
    }

    /**
     * 中文侧正文也必须报出数量。
     *
     * ⚠️ 其余用例跑在 Robolectric 默认 locale(`en_US` ⇒ 默认槽位 = 英文),
     * **中文资源根本不会被加载** ⇒ 中文正文写成什么样都没人管。
     * 这是 W19 的 M31 变异(删掉中文正文里的 `%1$d`)存活的原因,故这里显式切到中文 locale。
     */
    @Test
    @Config(sdk = [34], qualifiers = "zh-rCN-w400dp-h2000dp")
    fun `the chinese warning body also contains the count`() {
        val report = overwritingReport()
        host(report, mutableListOf())
        rule.onNodeWithTag(TAG_REPORT_UNDO).performClick()

        val body = TestText.plural(
            R.plurals.report_undo_warning_body,
            report.overwrittenCount,
            report.overwrittenCount,
        )
        println("UndoScopeDialog probe: zh body=\"$body\"")
        rule.onNodeWithText(body).assertIsDisplayed()
        assertTrue(
            body.contains(report.overwrittenCount.toString()),
            "中文正文同样要报出被替换的文件数,实际:\"$body\"",
        )
    }

    /**
     * 没有覆盖项时**不弹** —— 两档结果逐字相同,弹框只是多一次无谓点击。
     *
     * 反向守卫:若哪天实现改成"一律先弹",这条会红。
     */
    @Test
    fun `a run that only created new files does not warn at all`() {
        val report = creatingReport()
        assertTrue(!report.needsUndoWarning, "前置:aot-1 不该被算成覆盖项")
        val received = mutableListOf<UndoScope>()
        host(report, received)

        rule.onNodeWithTag(TAG_REPORT_UNDO).performClick()

        rule.onNodeWithTag(TAG_UNDO_SCOPE_DIALOG).assertDoesNotExist()
        assertEquals(listOf(UndoScope.ThisRun), received, "无覆盖项时应直接按安全档执行")
    }

    // ================= 2. 三选一 =================

    @Test
    fun `the scope dialog offers exactly three choices`() {
        host(overwritingReport(), mutableListOf())
        rule.onNodeWithTag(TAG_REPORT_UNDO).performClick()

        rule.onNodeWithTag(TAG_UNDO_SCOPE_THIS_RUN).assertIsDisplayed()
        rule.onNodeWithTag(TAG_UNDO_SCOPE_EVERYTHING).assertIsDisplayed()
        rule.onNodeWithTag(TAG_UNDO_SCOPE_CANCEL).assertIsDisplayed()

        // 文案来自**界面用的同一条解析路径**,不手写字面量(裁决 test_strategy=B)。
        rule.onNodeWithTag(TAG_UNDO_SCOPE_THIS_RUN)
            .assertTextEquals(TestText.of(R.string.report_undo_this_run))
        rule.onNodeWithTag(TAG_UNDO_SCOPE_EVERYTHING)
            .assertTextEquals(TestText.of(R.string.report_undo_everything))
        rule.onNodeWithTag(TAG_UNDO_SCOPE_CANCEL)
            .assertTextEquals(TestText.of(R.string.common_cancel))
    }

    /**
     * 用户明说「把取消放到最后边」⇒ 断言**布局出来的纵向次序**。
     *
     * 只断言三个 tag 都存在是抓不住这条要求的:`Column` 里换个书写顺序,
     * 语义断言照样全绿,而用户看到的是"取消跑到中间去了"。
     */
    @Test
    fun `cancel sits below both scopes in the laid out dialog`() {
        host(overwritingReport(), mutableListOf())
        rule.onNodeWithTag(TAG_REPORT_UNDO).performClick()

        val safeTop = rule.onNodeWithTag(TAG_UNDO_SCOPE_THIS_RUN).getBoundsInRoot().top.value
        val dangerTop = rule.onNodeWithTag(TAG_UNDO_SCOPE_EVERYTHING).getBoundsInRoot().top.value
        val cancelTop = rule.onNodeWithTag(TAG_UNDO_SCOPE_CANCEL).getBoundsInRoot().top.value
        println("UndoScopeDialog probe: tops safe=$safeTop danger=$dangerTop cancel=$cancelTop")

        assertTrue(safeTop < dangerTop, "安全档应在危险档上面;实际 $safeTop vs $dangerTop")
        assertTrue(dangerTop < cancelTop, "「取消放到最后边」—— 取消应在最下面;实际 $dangerTop vs $cancelTop")
    }

    /**
     * 三条路的文案必须**两两不同** —— 这是"能选"的最低条件。
     *
     * ## 为什么这条不能用 `assertTextEquals(TestText.of(...))` 代替
     *
     * 上面那些等价断言取的是**同一份资源**:若两个档位的资源内容被写成同一句话,
     * 期望值和实际值会**一起**变错,所有等价断言照样全绿(轮 14 实测过的盲区:
     * 英文资源被砍成一个占位符,整套测试没有一项失败)。
     *
     * 而"两句话必须不一样"是**不看具体措辞**的性质:它与语言、与译法都无关,
     * 只钉住"用户面对的是两个不同的选项"这个功能前提。措辞写得好不好仍需人读,
     * 但"两个按钮写着同一句话"是机器能抓、且必须抓的。
     */
    @Test
    fun `the three choices are worded differently from each other`() {
        host(overwritingReport(), mutableListOf())
        rule.onNodeWithTag(TAG_REPORT_UNDO).performClick()

        val labels = listOf(
            R.string.report_undo_this_run,
            R.string.report_undo_everything,
            R.string.common_cancel,
        ).map { TestText.of(it) }
        println("UndoScopeDialog probe: labels=$labels")

        assertEquals(labels.size, labels.toSet().size, "三个选项的文案必须两两不同,实际 $labels")
        labels.forEach { assertTrue(it.isNotBlank(), "选项文案不能为空") }

        // 标题与正文也不能撞:否则「警告」退化成一句没有信息量的话。
        val title = TestText.of(R.string.report_undo_warning_title)
        assertTrue(title.isNotBlank(), "警告标题不能为空")
        assertTrue(title !in labels, "警告标题不应与任一选项文案相同")
    }

    /**
     * **界面渲染出来的**三句文案也必须两两不同 —— 直接对语义树取值,不经任何推断。
     *
     * ## 为什么等价断言(本文其他用例)漏掉了「两个档位写成同一句」
     *
     * `assertTextEquals(TestText.of(R.string.report_undo_this_run))` 取的是
     * **同一份资源**:把两个档位的资源内容改成同一句话,期望值与实际值会一起变,
     * 断言照样绿(W19 的 M29/M30 就是这么存活的)。
     *
     * 这里改从**界面上真实存在的那两个节点**取文本,不看资源 id —— 于是
     * "两个按钮显示同一句话"变成可证伪的性质。这是轮 14 那个盲区的直接补丁。
     */
    @Test
    fun `the two scope buttons do not render the same sentence`() {
        host(overwritingReport(), mutableListOf())
        rule.onNodeWithTag(TAG_REPORT_UNDO).performClick()

        val safe = rule.onNodeWithTag(TAG_UNDO_SCOPE_THIS_RUN).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }
        val danger = rule.onNodeWithTag(TAG_UNDO_SCOPE_EVERYTHING).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }
        val cancel = rule.onNodeWithTag(TAG_UNDO_SCOPE_CANCEL).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }
        println("UndoScopeDialog probe: rendered safe=\"$safe\" danger=\"$danger\" cancel=\"$cancel\"")

        assertTrue(safe.isNotBlank() && danger.isNotBlank() && cancel.isNotBlank(), "三个选项都必须有文案")
        assertTrue(
            safe != danger,
            "两个档位必须是**不同**的选项,实际都渲染成\"$safe\"",
        )
        assertTrue(safe != cancel && danger != cancel, "取消不应与任一档位文案相同")
    }

    /**
     * 中文侧的三句文案也必须两两不同。
     *
     * ⚠️ 与上一条同理:默认 locale 是 `en_US` ⇒ **中文资源根本不会被加载**。
     * 把中文的「全部删除」写成「仅撤销本次生成的」,在英文 locale 下毫无痕迹 ——
     * 而真机上中文用户会看到两个一模一样的按钮。故中文侧必须单独断言一次。
     */
    @Test
    @Config(sdk = [34], qualifiers = "zh-rCN-w400dp-h2000dp")
    fun `the chinese choices are worded differently from each other`() {
        host(overwritingReport(), mutableListOf())
        rule.onNodeWithTag(TAG_REPORT_UNDO).performClick()

        val safe = rule.onNodeWithTag(TAG_UNDO_SCOPE_THIS_RUN).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }
        val danger = rule.onNodeWithTag(TAG_UNDO_SCOPE_EVERYTHING).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }
        val cancel = rule.onNodeWithTag(TAG_UNDO_SCOPE_CANCEL).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }
        val title = TestText.of(R.string.report_undo_warning_title)
        println("UndoScopeDialog probe: zh safe=\"$safe\" danger=\"$danger\" cancel=\"$cancel\" title=\"$title\"")

        assertEquals(listOf(safe, danger, cancel).size, setOf(safe, danger, cancel).size,
            "中文三个选项必须两两不同,实际 \"$safe\" / \"$danger\" / \"$cancel\"")
        assertTrue(title !in listOf(safe, danger, cancel), "中文警告标题不应与任一选项相同")
    }

    // ================= 3. 两个执行档各自生效 =================

    @Test
    fun `choosing the safe scope reports the safe scope back`() {
        val report = overwritingReport()
        val received = mutableListOf<UndoScope>()
        host(report, received)

        rule.onNodeWithTag(TAG_REPORT_UNDO).performClick()
        rule.onNodeWithTag(TAG_UNDO_SCOPE_THIS_RUN).performClick()

        assertEquals(listOf(UndoScope.ThisRun), received)
        rule.onNodeWithTag(TAG_UNDO_SCOPE_DIALOG).assertDoesNotExist()
        // 安全档下**没有**可回滚项(本次只覆盖、没新建)⇒ 记录为 null,如实报
        // 「没有可撤销的操作」。这正是安全档的意义:宁可什么都不删。
        assertNull(undoRecordOf(report, UndoScope.ThisRun), "安全档不该为覆盖项推撤销操作")
        rule.onNodeWithTag(TAG_REPORT_UNDO_MESSAGE)
            .assertTextEquals(resolveText(context, performUndo(null).message))
    }

    @Test
    fun `choosing the dangerous scope reports the dangerous scope back`() {
        val report = overwritingReport()
        val received = mutableListOf<UndoScope>()
        host(report, received)

        rule.onNodeWithTag(TAG_REPORT_UNDO).performClick()
        rule.onNodeWithTag(TAG_UNDO_SCOPE_EVERYTHING).performClick()

        assertEquals(listOf(UndoScope.Everything), received)
        rule.onNodeWithTag(TAG_UNDO_SCOPE_DIALOG).assertDoesNotExist()
        val record = undoRecordOf(report, UndoScope.Everything)
        assertEquals(1, record!!.opCount, "危险档应为那条覆盖项推一条撤销操作")
        rule.onNodeWithTag(TAG_REPORT_UNDO_MESSAGE)
            .assertTextEquals(resolveText(context, performUndo(record).message))
    }

    /**
     * 混合报告(既有新建、又有覆盖)是**真实**形态:一整库打包时总会有几本撞名。
     *
     * 这条钉住两档的**差**:安全档只回滚本次新建的那些,危险档**额外**把覆盖项也算上。
     * 只测"全是覆盖"的报告是抓不住这个差的 —— 那样安全档为空、危险档 1 项,
     * 两档的差别恰好被"新建项"这个维度掩盖掉了。
     */
    @Test
    fun `the two scopes differ exactly by the overwritten entries`() {
        val aot1 = library.volumes.first { it.id == "aot-1" }
        val op102 = library.volumes.first { it.id == "op-102" }
        val report = executePackagePlans(
            buildPackagePlans(listOf(aot1, op102), conflictPolicy = OutputConflictPolicy.Overwrite),
        )
        assertEquals(1, report.overwrittenCount, "前置:恰好一条覆盖项")
        assertEquals(2, report.succeededCount, "前置:两条都执行成功")

        val safe = undoRecordOf(report, UndoScope.ThisRun)!!
        val dangerous = undoRecordOf(report, UndoScope.Everything)!!

        assertEquals(1, safe.opCount, "安全档只回滚本次新建的那一条")
        assertEquals(0, safe.overwrittenOpCount, "安全档里不该有覆盖项")
        assertEquals(2, dangerous.opCount, "危险档两条都回滚")
        assertEquals(1, dangerous.overwrittenOpCount, "危险档里恰好多出那一条覆盖项")
    }

    // ================= 4. 取消 =================

    /**
     * 取消必须**什么都不做**:不回滚、不留消息、也能再次点开。
     *
     * 「能再次点开」是独立的性质:若实现用 `remember` 存了"问过了"的标志而忘了复位,
     * 用户取消一次之后就再也撤销不了 —— 那是个静默的功能坏死。
     */
    @Test
    fun `cancelling closes the dialog and undoes nothing`() {
        val report = overwritingReport()
        val received = mutableListOf<UndoScope>()
        host(report, received)

        rule.onNodeWithTag(TAG_REPORT_UNDO).performClick()
        rule.onNodeWithTag(TAG_UNDO_SCOPE_CANCEL).performClick()

        rule.onNodeWithTag(TAG_UNDO_SCOPE_DIALOG).assertDoesNotExist()
        assertTrue(received.isEmpty(), "取消不得触发任何回滚,实际 $received")
        rule.onNodeWithTag(TAG_REPORT_UNDO_MESSAGE).assertDoesNotExist()

        // 取消之后还能重新点开(状态确实复位了)。
        rule.onNodeWithTag(TAG_REPORT_UNDO).performClick()
        rule.onNodeWithTag(TAG_UNDO_SCOPE_DIALOG).assertIsDisplayed()
        assertNull(received.firstOrNull(), "重开后仍不得擅自执行")
    }
}
