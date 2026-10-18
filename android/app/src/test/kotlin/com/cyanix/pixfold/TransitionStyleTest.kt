package com.cyanix.pixfold

import com.cyanix.pixfold.TestText

import android.graphics.Bitmap
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.cyanix.pixfold.domain.settings.InMemorySettingsStore
import com.cyanix.pixfold.domain.settings.PixFoldSettings
import com.cyanix.pixfold.ui.TAG_SETTINGS_BUTTON
import com.cyanix.pixfold.ui.settings.TAG_SETTINGS_PAGE
import com.cyanix.pixfold.ui.workflowa.TAG_STEP_BACK
import com.cyanix.pixfold.ui.workflowa.TAG_STEP_NAMING
import com.cyanix.pixfold.ui.workflowa.TAG_STEP_TABS
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * **返回转场形态**的守护:两级风格必须真的不一样(2026-09-22 建立)。
 *
 * ## 守护的规格(用户逐字给出)
 *
 * - 工作流内部(带步骤的容器):「拖动越多当前页越透明然后显示上级页面」→ 只淡出,**不缩放**;
 * - 大页面切换(根级):「当前页面虚化缩小」→ 淡出 **+** 缩小到 `PREDICTIVE_MIN_SCALE`。
 *
 * 两档由 `NavTransitionStyle.Step` / `.Page` 区分,配方在
 * `android/app/src/main/kotlin/com/cyanix/pixfold/nav/PixFoldNav.kt`。
 *
 * ## 为什么必须**同时**看几何和像素
 *
 * `boundsInRoot` 只看得到缩放/位移,**看不到 alpha**;而步骤级恰恰只剩 alpha 这一维。
 * 反过来只看像素又分不清「淡出」和「淡出+缩小」。两档的判别式因此是:
 *
 * | | 宽度(几何) | 画面(像素) |
 * |---|---|---|
 * | 步骤级 | 不变 | 必须变化(上级页露出来) |
 * | 根级 | 必须变小 | 必须变化 |
 * | 开关关闭 | 不变 | **逐字节不变** |
 *
 * ## 第三个判别式:手势拉满时必须已经"长成"目标页
 *
 * 只有上面两条时**根级仍有漏洞**:把 `scaleOut(0.90) + fadeOut()` 削弱成
 * `scaleOut(0.90)`(丢掉虚化),宽度照样变小、像素照样变化,两条断言**全过**。
 * 故补一条:手势拉到 `progress = 1f` 时,画面必须已经等于**真的退回去之后**的画面
 * (彼时 alpha 恰好为 0,上层页彻底消失,屏幕上只剩目标页)。缩放单独存在时,
 * 缩小中的设置页仍然盖在首页上,这一条必然失败。步骤级同样用这一条兜底。
 *
 * ## 比对范围:只比内容区,不比整屏
 *
 * 步骤页签行(`TAG_STEP_TABS`)在**嵌套 NavHost 子树之外**,不受转场管辖;松手返回时
 * 它的选中项会**合法地**从「命名」切回「排序」。因此所有与收敛性有关的像素比对都走
 * [contentBitmap](裁掉页签带),否则会把页签翻转误判为"过渡没收敛" —— 这正是
 * SDK 35/36 下那 8000 余个虚假残差的真因(详见该函数的 KDoc)。
 *
 * ## 不能断言什么
 *
 * 真实返回手势的**系统绘制**本身(系统那道边缘拖拽层)。那部分由真机走查覆盖,
 * 与本仓库其他预测性返回测试的边界说明一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TransitionStyleTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun event(progress: Float) = BackEventCompat(0f, 0f, progress, BackEventCompat.EDGE_LEFT)

    /** 手势进行中(不松手),推进到 [progress]。 */
    private fun gestureTo(progress: Float) {
        rule.activityRule.scenario.onActivity { activity ->
            val dispatcher = activity.onBackPressedDispatcher
            dispatcher.dispatchOnBackStarted(event(0f))
            dispatcher.dispatchOnBackProgressed(event(progress))
        }
        rule.waitForIdle()
    }

    /** 松手完成手势,落到目标页。 */
    private fun release() {
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun rootBitmap(): Bitmap = rule.onRoot().captureToImage().asAndroidBitmap()

    /**
     * 裁掉**步骤页签行**之后再取内容区。
     *
     * **为什么必须裁**(2026-09-22 定位出的测试缺陷):步骤页签行
     * (`PrimaryTabRow`,`TAG_STEP_TABS`)位于**嵌套 NavHost 子树之外** ——
     * 它不在转场的管辖范围内。松手真正返回时,它的选中项**合法地**从「命名」切回「排序」,
     * 于是「整屏比对」会把这段页签翻转也算进"过渡残差"里。
     *
     * 差异定位探针实测:SDK 35/36 下那 8000 余个差异像素 **100% 落在
     * `box=(15,0)-(265,59)`** 即页签的 `Rect.fromLTRB(0.0, 0.0, 400.0, 60.0)` 带内;
     * 排除该带后内容区差 **0**。故凡与转场收敛性有关的比对,都必须只比内容区。
     */
    private fun contentBitmap(): Bitmap {
        val full = rootBitmap()
        val tabsBottom = rule.onNodeWithTag(TAG_STEP_TABS)
            .fetchSemanticsNode().boundsInRoot.bottom.toInt()
        return Bitmap.createBitmap(full, 0, tabsBottom, full.width, full.height - tabsBottom)
    }

    private fun width(tag: String): Float =
        rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.width

    /** 两张同尺寸位图之间**逐像素**不同的个数。 */
    private fun changedPixels(a: Bitmap, b: Bitmap): Int {
        require(a.width == b.width && a.height == b.height) {
            "位图尺寸不一致: ${a.width}x${a.height} vs ${b.width}x${b.height}"
        }
        val pa = IntArray(a.width * a.height)
        val pb = IntArray(b.width * b.height)
        a.getPixels(pa, 0, a.width, 0, 0, a.width, a.height)
        b.getPixels(pb, 0, b.width, 0, 0, b.width, b.height)
        var n = 0
        for (i in pa.indices) if (pa[i] != pb[i]) n++
        return n
    }

    /** 打开工作流 A 的步骤 2(命名页)。 */
    private fun openNamingStep() {
        rule.onNodeWithText(TestText.of(R.string.home_workflow_a)).performClick()
        rule.onNodeWithTag(TAG_STEP_NAMING).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_STEP_BACK).assertExists()
    }

    private fun openSettingsPage() {
        rule.onNodeWithTag(TAG_SETTINGS_BUTTON).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_SETTINGS_PAGE).assertExists()
    }

    /** 判定「已彻底淡出」的容差:满进度时画面应等于目标页,只留抗锯齿级别的差异。 */
    private val settledTolerance = 5_000

    // --- 步骤级:只淡出,不缩放 ---

    @Test
    fun `the step level back gesture fades without scaling`() {
        rule.setContent {
            PixFoldApp(store = InMemorySettingsStore(PixFoldSettings(predictiveBackAnimation = true)))
        }
        openNamingStep()

        // 用 contentBitmap 而非 rootBitmap:页签行不在转场管辖内,松手时它会合法翻转选中项。
        val before = contentBitmap()
        val widthBefore = width(TAG_STEP_BACK)

        gestureTo(1f)

        val during = contentBitmap()
        val widthDuring = width(TAG_STEP_BACK)
        val changed = changedPixels(before, during)

        release()
        val after = contentBitmap()
        val settled = changedPixels(after, during)
        println(
            "StepStyle probe: widthBefore=$widthBefore widthDuring=$widthDuring " +
                "changedPixels=$changed settledDiff=$settled",
        )

        // 几何:步骤级**不得**缩放 —— 上级页要原样露出来,不能被缩小的卡片盖住。
        assertTrue(
            abs(widthDuring - widthBefore) < 1f,
            "步骤级转场不应缩放:widthBefore=$widthBefore widthDuring=$widthDuring",
        )
        // 像素:必须真的随进度淡出露出上级页(命名页与网格页差别很大)。
        assertTrue(
            changed > settledTolerance,
            "步骤级转场应随进度淡出露出上级页,实际仅 $changed 个像素变化",
        )
        // 拉满时必须已经长成目标页 —— 这条同时排除「只缩放不淡出」与「毫无变换」。
        assertTrue(
            settled < settledTolerance,
            "手势拉满时应已完全退到目标页(网格),实际与落地画面差 $settled 个像素",
        )
    }

    // --- 根级:淡出 + 缩小 ---

    @Test
    fun `the page level back gesture both fades and scales`() {
        rule.setContent {
            PixFoldApp(store = InMemorySettingsStore(PixFoldSettings(predictiveBackAnimation = true)))
        }
        openSettingsPage()

        val before = rootBitmap()
        val widthBefore = width(TAG_SETTINGS_PAGE)

        gestureTo(1f)

        val during = rootBitmap()
        val widthDuring = width(TAG_SETTINGS_PAGE)
        val changed = changedPixels(before, during)

        release()
        val after = rootBitmap()
        val settled = changedPixels(after, during)
        println(
            "PageStyle probe: widthBefore=$widthBefore widthDuring=$widthDuring " +
                "changedPixels=$changed settledDiff=$settled",
        )

        // 几何:根级**必须**缩小 —— 这正是「大页面切换」区别于步骤级的那一维。
        assertTrue(
            widthDuring < widthBefore - 1f,
            "根级转场应缩小:widthBefore=$widthBefore widthDuring=$widthDuring",
        )
        // 像素:虚化确实发生(`scaleOut` 只含 scale,「虚化」靠 `fadeOut` 补,必须单独验)。
        assertTrue(
            changed > settledTolerance,
            "根级转场应随进度淡出,实际仅 $changed 个像素变化",
        )
        // **关键一条**:满进度时必须已经只剩首页 —— 把 `fadeOut()` 从根级配方里拿掉,
        // 宽度断言与上一条都仍然通过,只有这一条会失败。
        assertTrue(
            settled < settledTolerance,
            "手势拉满时应已完全退到首页(上层页 alpha 归零),实际与落地画面差 $settled 个像素",
        )
    }

    // --- 开关关闭:两级都纹丝不动 ---

    @Test
    fun `with the switch off neither level transforms`() {
        rule.setContent {
            PixFoldApp(store = InMemorySettingsStore(PixFoldSettings(predictiveBackAnimation = false)))
        }
        openSettingsPage()

        val before = rootBitmap()
        val widthBefore = width(TAG_SETTINGS_PAGE)

        gestureTo(1f)

        val during = rootBitmap()
        val widthDuring = width(TAG_SETTINGS_PAGE)
        val changed = changedPixels(before, during)
        println(
            "SwitchOff root probe: widthBefore=$widthBefore widthDuring=$widthDuring changedPixels=$changed",
        )

        assertTrue(
            abs(widthDuring - widthBefore) < 1f && changed == 0,
            "开关关闭时根级不应有任何变换:" +
                "widthBefore=$widthBefore widthDuring=$widthDuring changedPixels=$changed",
        )
    }
}
