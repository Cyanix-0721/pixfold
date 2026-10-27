package com.cyanix.pixfold.ui.library

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cyanix.pixfold.FakeCbzMetaUpdater
import com.cyanix.pixfold.R
import com.cyanix.pixfold.TestText
import com.cyanix.pixfold.domain.comic.ComicInfo
import com.cyanix.pixfold.domain.mock.MockDataB
import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.pack.CbzMetaUpdateOutcome
import com.cyanix.pixfold.domain.pack.UpdateFailure
import com.cyanix.pixfold.domain.source.SERIES_PREFIX
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * 工作流 C 的完整接线：点入口 → 确认 → 执行 → 结果。
 *
 * ## 为什么断言“写进计划的那份 XML”
 *
 * 用户改完元数据点「更新」，进 CBZ 的必须是**界面上那一份**。领域层
 * ComicInfo.build 是唯一产出点，断言计划里的 XML 等于它，等于钉住
 * 「用户看到什么就写什么」—— 任何一处漏字段都会在这里红。
 *
 * ## 为什么取消要单独断言
 *
 * 取消的语义与失败不同：用户自己按的取消**不该**弹「更新失败」的框
 * （spec §8.5）。实现时很容易顺手写成「任何非 Updated 都给结果框」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h2000dp")
class CbzUpdateFlowTest {

    @get:Rule
    val rule = createComposeRule()

    private val library = MockDataB.library
    private val withArchive: ComicVolume = library.volumes.first { it.outputExists }

    private fun show(updater: FakeCbzMetaUpdater) {
        rule.setContent {
            LibrarySteps(
                rootPath = library.rootPath,
                volumes = library.volumes,
                metaUpdater = updater,
            )
        }
    }

    private fun goToMetadata() {
        rule.onNodeWithTag(TAG_B_STEP_METADATA).performClick()
        rule.waitForIdle()
    }

    /**
     * 切到带归档的那一卷 —— **走用户真实路径**（先点 1 级系列芯片，再点 2 级卷芯片）。
     *
     * 两步是必须的：两级芯片里，别的系列的卷只有选中该系列后才出现在 2 级行。
     */
    private fun selectArchiveVolume() {
        val chip = volumeChipTag(withArchive.id)
        if (rule.onAllNodesWithTag(chip).fetchSemanticsNodes().isEmpty()) {
            rule.onNodeWithTag(volumeGroupTag(SERIES_PREFIX + withArchive.series)).performClick()
            rule.waitForIdle()
        }
        rule.onNodeWithTag(chip).performClick()
        rule.waitForIdle()
    }

    private fun confirmInDialog() {
        rule.onNodeWithTag(TAG_UPDATE_CONFIRM_DIALOG).assertIsDisplayed()
        rule.onNodeWithText(TestText.of(R.string.update_meta_confirm)).performClick()
        awaitUpdateSettled()
    }

    /**
     * 等更新彻底收尾。
     *
     * **为什么不能只 `waitForIdle()`**：执行块走 `withContext(Dispatchers.IO)`，
     * Robolectric 下那是真实线程池，不归 Compose 的测试调度器管 ⇒ `waitForIdle` 会立刻返回，
     * 断言就跑在「结果还没回来」的那一帧上（第一次正是这么红的，报的却是窗没显示）。
     *
     * 判据 = **进度框消失**（= 执行块已经跑完并写回状态）。取消路径没有结果框，
     * 那里同样只靠这一条成立 —— 用它当唯一收尾信号，两种结局共用一条等待。
     */
    private fun awaitUpdateSettled() {
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithTag(TAG_UPDATE_PROGRESS_DIALOG).fetchSemanticsNodes().isEmpty() &&
                rule.onAllNodesWithTag(TAG_UPDATE_CONFIRM_DIALOG).fetchSemanticsNodes().isEmpty()
        }
        rule.waitForIdle()
    }

    /**
     * 等结果框出现。
     *
     * **为什么不能只靠 [awaitUpdateSettled]**：执行协程里 `updating = false` 与
     * `updateResult = outcome` 是两次独立的状态写入，Compose 可能在两者之间提交一帧 ——
     * 那一帧上前两个框都已消失、结果框还没出来。测试线程正好落在那儿就会红（实际发生过：
     * 同一份代码单跑绿、整套跑红）。等「结果框出现」才是终态判据。
     */
    private fun awaitResultDialog() {
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithTag(TAG_UPDATE_RESULT_DIALOG).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
    }

    private fun resultBody(): String =
        rule.onNodeWithTag(TAG_UPDATE_RESULT_BODY)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString("") { it.text }

    @Test
    fun `no update entry anywhere when the updater is not injected`() {
        rule.setContent {
            LibrarySteps(rootPath = library.rootPath, volumes = library.volumes)
        }
        // 高视口下所有卷都已组合，故「不存在」是真的不存在，不是没滚到。
        rule.onNodeWithTag(volumeUpdateTag(withArchive.id)).assertDoesNotExist()
        goToMetadata()
        rule.onNodeWithTag(TAG_METADATA_UPDATE).assertDoesNotExist()
    }

    @Test
    fun `confirm leads to the result dialog and writes exactly one plan`() {
        val fake = FakeCbzMetaUpdater()
        show(fake)
        goToMetadata()
        selectArchiveVolume()

        rule.onNodeWithTag(TAG_METADATA_UPDATE).performClick()
        rule.waitForIdle()
        confirmInDialog()
        awaitResultDialog()

        rule.onNodeWithTag(TAG_UPDATE_RESULT_DIALOG).assertIsDisplayed()
        assertEquals(1, fake.updateCalls, "点一次确认只该执行一次")
        val plan = fake.lastPlan!!
        assertEquals(ComicInfo.build(withArchive), plan.comicInfoXml, "写进包的必须是界面上那一份")
        assertEquals(withArchive.cbzFileName, plan.targetDisplayName)
    }

    @Test
    fun `the result dialog closes with a word that matches the outcome`() {
        // 结果框是「陈述结局」，不是「发起动作」。原先复用了 GeneralConfirmOk
        // （en "Run" / zh "执行"），成功框上显示「执行」让人以为还要再点一次才生效。
        val fake = FakeCbzMetaUpdater()
        show(fake)
        goToMetadata()
        selectArchiveVolume()
        rule.onNodeWithTag(TAG_METADATA_UPDATE).performClick()
        rule.waitForIdle()
        confirmInDialog()
        awaitResultDialog()
        rule.onNodeWithTag(TAG_UPDATE_RESULT_CONFIRM)
            .assertTextEquals(TestText.of(R.string.domain_general_done))
    }

    @Test
    fun `a failed result dialog closes with Close rather than Done`() {
        // 失败时「完成」是错的 —— 用户没有完成任何事。
        val fake = FakeCbzMetaUpdater(
            outcome = CbzMetaUpdateOutcome.Failed(UpdateFailure.NoSpace),
        )
        show(fake)
        goToMetadata()
        selectArchiveVolume()
        rule.onNodeWithTag(TAG_METADATA_UPDATE).performClick()
        rule.waitForIdle()
        confirmInDialog()
        awaitResultDialog()
        rule.onNodeWithTag(TAG_UPDATE_RESULT_CONFIRM)
            .assertTextEquals(TestText.of(R.string.domain_general_close))
    }

    @Test
    fun `a failed update shows the specific reason rather than a generic one`() {
        val fake = FakeCbzMetaUpdater(
            outcome = CbzMetaUpdateOutcome.Failed(UpdateFailure.NoSpace),
        )
        show(fake)
        goToMetadata()
        selectArchiveVolume()
        rule.onNodeWithTag(TAG_METADATA_UPDATE).performClick()
        rule.waitForIdle()
        confirmInDialog()
        awaitResultDialog()
        rule.onNodeWithTag(TAG_UPDATE_RESULT_DIALOG).assertIsDisplayed()
        assertEquals(TestText.of(R.string.domain_update_fail_no_space), resultBody())
    }

    @Test
    fun `cancelling closes the dialog and reports nothing`() {
        val fake = FakeCbzMetaUpdater(
            outcome = CbzMetaUpdateOutcome.Failed(UpdateFailure.Cancelled),
        )
        show(fake)
        goToMetadata()
        selectArchiveVolume()
        rule.onNodeWithTag(TAG_METADATA_UPDATE).performClick()
        rule.waitForIdle()
        confirmInDialog()
        // 取消是用户按的，不该被说成一次故障。
        rule.onNodeWithTag(TAG_UPDATE_RESULT_DIALOG).assertDoesNotExist()
        rule.onNodeWithTag(TAG_UPDATE_PROGRESS_DIALOG).assertDoesNotExist()
    }

    @Test
    fun `a missing archive is reported before the confirm dialog`() {
        val fake = FakeCbzMetaUpdater(exists = false)
        show(fake)
        goToMetadata()
        selectArchiveVolume()
        rule.onNodeWithTag(TAG_METADATA_UPDATE).performClick()
        rule.waitForIdle()
        // 包都不在了，还让人确认一次再报错是骗人的 —— 直接给结果。
        rule.onNodeWithTag(TAG_UPDATE_CONFIRM_DIALOG).assertDoesNotExist()
        rule.onNodeWithTag(TAG_UPDATE_RESULT_DIALOG).assertIsDisplayed()
        assertEquals(0, fake.updateCalls)
    }
}
