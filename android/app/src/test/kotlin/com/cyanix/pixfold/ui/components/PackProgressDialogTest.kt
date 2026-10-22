package com.cyanix.pixfold.ui.components

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.cyanix.pixfold.domain.task.TaskProgress
import com.cyanix.pixfold.ui.theme.PixFoldTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [PackProgressDialog] 的双层圆环**各自画哪一级进度**。
 *
 * ## 为什么要单独测这件事
 *
 * 用户裁决（`User said (m13400)`）原话是「内层打包数量进度，外层当前打包漫画图片
 * 打包进度」—— 即 **内环 = 卷数（粗）、外环 = 页数（细）**。首版实现写反了（外环
 * 卷、内环页），而它在真机上看起来完全正常：两环都在转、都随任务推进、界面不会报错
 * ⇒ **肉眼核对只能发现「环在动」，发现不了「动的是哪一级」**。
 *
 * Robolectric 下 `CircularProgressIndicator` 的进度会落进语义树的
 * `ProgressBarRangeInfo`，那是这里唯一有判别力的读数（尺寸/颜色都随主题，且
 * 两环的差别只有 32dp）。
 *
 * ## 夹具刻意让两个值不同
 *
 * 卷进度 `1/4 = 0.25`、页进度 `50/100 = 0.5`。若实现把两级对调，两条断言必然互换
 * ⇒ 这是本条测试唯一有杀伤力的做法（两个值相同则互换后仍然全绿）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PackProgressDialogTest {

    @get:Rule
    val rule = createComposeRule()

    private fun render(
        progress: TaskProgress = TaskProgress(total = 4, done = 1, failed = 0, skipped = 0),
        pageDone: Int? = 50,
        pageTotal: Int? = 100,
        volumeLabel: String? = null,
    ) {
        rule.setContent {
            PixFoldTheme {
                PackProgressDialog(
                    progress = progress,
                    pageDone = pageDone,
                    pageTotal = pageTotal,
                    volumeLabel = volumeLabel,
                    onCancel = {},
                )
            }
        }
    }

    @Test
    fun `outer ring shows the page progress of the current volume`() {
        render()
        rule.onNodeWithTag(TAG_PACK_PROGRESS_OUTER)
            .assertRangeInfoEquals(ProgressBarRangeInfo(current = 0.5f, range = 0f..1f, steps = 0))
    }

    @Test
    fun `inner ring shows the volume progress of the whole batch`() {
        render()
        rule.onNodeWithTag(TAG_PACK_PROGRESS_INNER)
            .assertRangeInfoEquals(ProgressBarRangeInfo(current = 0.25f, range = 0f..1f, steps = 0))
    }

    @Test
    fun `outer ring is the bigger one`() {
        render()
        val outer = rule.onNodeWithTag(TAG_PACK_PROGRESS_OUTER).fetchSemanticsNode().boundsInRoot
        val inner = rule.onNodeWithTag(TAG_PACK_PROGRESS_INNER).fetchSemanticsNode().boundsInRoot
        // 外层半径必须大于内层，否则「内层/外层」这两个词在界面上就无法区分。
        assertTrue(
            outer.width > inner.width,
            "外层圆环(${outer.width})必须比内层(${inner.width})大",
        )
    }

    @Test
    fun `inner ring shows no progress when the page count is unknown`() {
        // 刚切到这一卷、或当前卷被跳过/缺页时页数未知。此时外环不能画假百分比。
        render(pageDone = null, pageTotal = null)
        rule.onNodeWithTag(TAG_PACK_PROGRESS_OUTER)
            .assertRangeInfoEquals(ProgressBarRangeInfo(current = 0f, range = 0f..1f, steps = 0))
        // 内环仍然可用 —— 卷进度与页数是否已知无关。
        rule.onNodeWithTag(TAG_PACK_PROGRESS_INNER)
            .assertRangeInfoEquals(ProgressBarRangeInfo(current = 0.25f, range = 0f..1f, steps = 0))
    }

    // ---- 布局：圆环与卷名必须水平居中 -----------------------------------
    //
    // 用户 `User said (m15456)`：「圆圈和名称没有居中显示」。首版 `PixFoldDialogShell`
    // 的正文 `Column` 没有水平对齐参数（默认 `Start`），而本弹窗内层 `Column` 又
    // 是 wrap-content 宽度 ⇒ 内层的 `CenterHorizontally` 只在自身宽度内生效，
    // 整体仍然贴左。
    //
    // 判别手法：**拿圆环矩形中心与对话框矩形中心比**。Robolectric 不做真实文本
    // 测量，但 `boundsInRoot` 反映真实布局 —— 贴左时两者相差上百 dp，不是几像素
    // 的测量误差，这条断言有杀伤力。

    @Test
    fun `rings are centered horizontally inside the dialog`() {
        render()
        val dialog = rule.onNodeWithTag(TAG_PACK_PROGRESS_DIALOG).fetchSemanticsNode().boundsInRoot
        val outer = rule.onNodeWithTag(TAG_PACK_PROGRESS_OUTER).fetchSemanticsNode().boundsInRoot
        val dialogCenter = (dialog.left + dialog.right) / 2f
        val ringCenter = (outer.left + outer.right) / 2f
        assertEquals(
            dialogCenter,
            ringCenter,
            absoluteTolerance = 1f,
            message = "圆环中心($ringCenter)必须与对话框中心($dialogCenter)重合",
        )
    }

    @Test
    fun `volume label is centered horizontally inside the dialog`() {
        render(volumeLabel = "[Cior (Ken-1)] 彼女がボクの知らないところで")
        val dialog = rule.onNodeWithTag(TAG_PACK_PROGRESS_DIALOG).fetchSemanticsNode().boundsInRoot
        val label = rule.onNodeWithTag(TAG_PACK_PROGRESS_VOLUME).fetchSemanticsNode().boundsInRoot
        val dialogCenter = (dialog.left + dialog.right) / 2f
        val labelCenter = (label.left + label.right) / 2f
        assertEquals(
            dialogCenter,
            labelCenter,
            absoluteTolerance = 1f,
            message = "卷名中心($labelCenter)必须与对话框中心($dialogCenter)重合",
        )
    }

    // ---- 卷名要带作者 ---------------------------------------------------
    //
    // 用户 `User said (m15457)`：「当前打包的名称要完整显示作者和标题」。
    // 只显示标题时，同系列多本（`…` 与 `…――2 [DL版]`）在弹窗里看起来几乎一样，
    // 用户分不清卡在哪一本。

    @Test
    fun `volume label shows the whole name it is given`() {
        val full = "[Cior (Ken-1)] 彼女がボクの知らないところで"
        render(volumeLabel = full)
        rule.onNodeWithTag(TAG_PACK_PROGRESS_VOLUME)
            .assertTextEquals(full)
    }

    // ---- 中心读数是两层合并值 -----------------------------------------
    //
    // 用户 `User said (m17028)`：「内圈的百分比根据双层共同计算取值吧，不如外圈
    // 没转完进度一直 0」。夹具是 4 卷的一批、当前卷 50/100 页：
    //  · 只按卷算 ⇒ 25%（`progress.percent`，已 done=1）
    //  · 两层合并 ⇒ (1 + 0.5)/4 = 37%
    // 两个值不同 ⇒ 实现退回单层必然红。

    @Test
    fun `centre readout mixes the volume and page progress`() {
        render()
        rule.onNodeWithTag(TAG_PACK_PROGRESS_PERCENT)
            .assertTextEquals("37%")
    }

    @Test
    fun `centre readout falls back to volume progress without page data`() {
        render(pageDone = null, pageTotal = null)
        rule.onNodeWithTag(TAG_PACK_PROGRESS_PERCENT)
            .assertTextEquals("25%")
    }
}
