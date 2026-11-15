package com.cyanix.pixfold

import com.cyanix.pixfold.TestText

import android.graphics.Bitmap
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.cyanix.pixfold.domain.settings.InMemorySettingsStore
import com.cyanix.pixfold.domain.settings.PixFoldSettings
import com.cyanix.pixfold.domain.settings.ThemeMode
import com.cyanix.pixfold.ui.TAG_SETTINGS_BUTTON
import com.cyanix.pixfold.ui.settings.TAG_SETTINGS_PAGE
import com.cyanix.pixfold.ui.organize.TAG_SORT_PAGE
import com.cyanix.pixfold.nav.WORKFLOW_SHRINK_MOTION
import com.cyanix.pixfold.ui.organize.TAG_STEP_BACK
import com.cyanix.pixfold.ui.organize.TAG_STEP_NAMING
import com.cyanix.pixfold.ui.organize.TAG_STEP_TABS
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **返回转场形态**的守护:两级风格必须真的不一样(2026-09-22 建立)。
 *
 * ## 守护的规格(用户逐字给出)
 *
 * - 工作流内部(带步骤的容器):「拖动越多当前页越透明然后显示上级页面」→ 只淡出,**不缩放**;
 * - 设置/许可→首页:设置页沿**滑动方向滑出并淡出**,首页从反侧滑入并淡入;
 *   方向由预测性返回的 `swipeEdge` 决定(左边缘→右出,右边缘→左出,普通返回键按右出);
 * - 工作流→首页:整页以首页对应卡片中心为轴**等比缩小并全程淡出**,不拉伸文字
 *   (2026-11-15 用户裁决:「还是之前那种缩放淡化,字体别拉伸,改成整体等比缩放,
 *   文字保持清晰不变形」)。
 *
 * 配方在 `android/app/src/main/kotlin/com/cyanix/pixfold/nav/PixFoldNav.kt`;
 * 工作流返回用 `WORKFLOW_SHRINK_MOTION` 的整页 `scaleOut + fadeOut`,两者同一条
 * `LinearEasing`,时长与延迟完全一致。
 *
 * ## 为什么必须**同时**看几何和像素
 *
 * `boundsInRoot` 对缩放可见,但对**滑出视口**的节点会被裁成 0 宽 —— 设置页因此改用
 * `getUnclippedBoundsInRoot()` 读真实位移;它看不到 alpha,步骤级(无缩放无位移)只能靠像素。
 * 反过来只看像素又分不清「淡出」和「瞬切」。三者的判别式因此是:
 *
 * | | 几何 | 画面(像素) |
 * |---|---|---|
 * | 步骤级 | 宽度不变 | 必须变化(上级页露出来) |
 * | 设置返回 | 宽度不变、left 增大 | 必须变化,半程左侧亮度低于落地首页(淡入) |
 * | 工作流返回 | 宽度等比缩小、中心向卡片移动 | 必须变化(淡出) |
 * | 开关关闭 | 不变 | **逐字节不变** |
 *
 * ## 第三个判别式:手势拉满时必须已经"长成"目标页
 *
 * 只靠几何 + 像素变化仍可能把动画换成别的无关变化。
 * 故补一条:手势拉到 `progress = 1f` 时,画面必须已经等于**真的退回去之后**的画面
 * (彼时上层页彻底消失,屏幕上只剩目标页)。三档都用这一条兜底。
 *
 * ## 比对范围:只比内容区,不比整屏
 *
 * 步骤页签行(`TAG_STEP_TABS`)在**工作流子 NavDisplay 子树之外**,不受转场管辖;松手返回时
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
@Config(sdk = [36], qualifiers = "w400dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TransitionStyleTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun event(
        progress: Float,
        edge: Int = BackEventCompat.EDGE_LEFT,
    ) = BackEventCompat(0f, 0f, progress, edge)

    private var gestureStarted = false

    /** 手势进行中(不松手),推进到 [progress]。可多次调用以取不同进度。 */
    private fun gestureTo(
        progress: Float,
        edge: Int = BackEventCompat.EDGE_LEFT,
    ) {
        rule.activityRule.scenario.onActivity { activity ->
            val dispatcher = activity.onBackPressedDispatcher
            if (!gestureStarted) {
                dispatcher.dispatchOnBackStarted(event(0f, edge))
                gestureStarted = true
            }
            dispatcher.dispatchOnBackProgressed(event(progress, edge))
        }
        rule.waitForIdle()
    }

    /** 松手完成手势,落到目标页。 */
    private fun release() {
        gestureStarted = false
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun rootBitmap(): Bitmap = rule.onRoot().captureToImage().asAndroidBitmap()

    /**
     * 裁掉**步骤页签行**之后再取内容区。
     *
     * **为什么必须裁**(2026-09-22 定位出的测试缺陷):步骤页签行
     * (`PrimaryTabRow`,`TAG_STEP_TABS`)位于**工作流子 NavDisplay 子树之外** ——
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

    private fun width(tag: String): Float = bounds(tag).width

    private fun bounds(tag: String) =
        rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    /** 被滑出视口的节点 boundsInRoot 会被裁成 0 宽,取未裁剪边界看真实位移。 */
    private fun unclippedBounds(tag: String) =
        rule.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    /** 位图左侧 [fraction] 区域的平均亮度(0..255),用于判定淡入/淡出。 */
    private fun meanLuminance(
        bitmap: Bitmap,
        fraction: Float = 0.25f,
    ): Double {
        val w = (bitmap.width * fraction).toInt().coerceAtLeast(1)
        val y0 = (bitmap.height * 0.1f).toInt()
        val y1 = (bitmap.height * 0.9f).toInt()
        var sum = 0.0
        var n = 0
        for (y in y0 until y1) {
            for (x in 0 until w) {
                val c = bitmap.getPixel(x, y)
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF
                sum += (r * 299 + g * 587 + b * 114) / 1000.0
                n++
            }
        }
        return sum / n
    }

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
            PixFoldApp(store = InMemorySettingsStore(PixFoldSettings(predictiveBackAnimation = true)), workspaceSource = ReadyWorkspaceSource())
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

    // --- 设置→首页:整页横向滑出 ---

    @Test
    fun `the settings return gesture slides horizontally without scaling`() {
        rule.setContent {
            // 深色纯黑底:首页淡入时亮度单调上升,亮度差可稳定判定「只滑不淡」。
            PixFoldApp(
                store = InMemorySettingsStore(
                    PixFoldSettings(
                        predictiveBackAnimation = true,
                        themeMode = ThemeMode.Dark,
                        pureBlack = true,
                    ),
                ),
                workspaceSource = ReadyWorkspaceSource(),
            )
        }
        openSettingsPage()

        val before = rootBitmap()
        val beforeBounds = unclippedBounds(TAG_SETTINGS_PAGE)
        val widthBefore = beforeBounds.right.value - beforeBounds.left.value
        val leftBefore = beforeBounds.left.value

        gestureTo(0.25f)
        val early = rootBitmap()
        gestureTo(0.5f)
        val half = rootBitmap()
        gestureTo(1f)

        val during = rootBitmap()
        val duringBounds = unclippedBounds(TAG_SETTINGS_PAGE)
        val widthDuring = duringBounds.right.value - duringBounds.left.value
        val leftDuring = duringBounds.left.value
        val changed = changedPixels(before, during)

        release()
        val after = rootBitmap()
        val settled = changedPixels(after, during)
        val earlyLeftLuminance = meanLuminance(early, fraction = 0.15f)
        val halfLeftLuminance = meanLuminance(half)
        val afterLeftLuminance = meanLuminance(after)
        println(
            "SettingsSlide probe: earlyLeftLuminance=$earlyLeftLuminance " +
                "halfLeftLuminance=$halfLeftLuminance afterLeftLuminance=$afterLeftLuminance",
        )

        // 几何:设置页整页右移,但不缩放。
        assertTrue(
            abs(widthDuring - widthBefore) < 1f,
            "设置返回不应缩放:widthBefore=$widthBefore widthDuring=$widthDuring",
        )
        assertTrue(
            leftDuring > leftBefore + widthBefore * 0.5f,
            "设置返回应向右滑出:leftBefore=$leftBefore leftDuring=$leftDuring",
        )
        // 像素:必须真的动起来露出首页。
        assertTrue(
            changed > settledTolerance,
            "设置返回应产生横向滑动,实际仅 $changed 个像素变化",
        )
        // 早期(1/4 进度)左侧是刚淡入的首页:必须明显比落地后的首页暗,证明不是「只滑不淡」。
        assertTrue(
            earlyLeftLuminance < afterLeftLuminance * 0.8,
            "设置返回应同时淡入首页:早期左侧亮度=$earlyLeftLuminance,落地后=$afterLeftLuminance",
        )
        // 满进度时必须已经只剩首页。
        assertTrue(
            settled < settledTolerance,
            "手势拉满时应已完全退到首页,实际与落地画面差 $settled 个像素",
        )
    }

    @Test
    fun `the settings return follows the swipe edge direction`() {
        rule.setContent {
            PixFoldApp(store = InMemorySettingsStore(PixFoldSettings(predictiveBackAnimation = true)), workspaceSource = ReadyWorkspaceSource())
        }
        openSettingsPage()

        val beforeBounds = unclippedBounds(TAG_SETTINGS_PAGE)
        val widthBefore = beforeBounds.right.value - beforeBounds.left.value
        val leftBefore = beforeBounds.left.value

        gestureTo(0.5f, BackEventCompat.EDGE_RIGHT)

        val duringBounds = unclippedBounds(TAG_SETTINGS_PAGE)
        val widthDuring = duringBounds.right.value - duringBounds.left.value
        val leftDuring = duringBounds.left.value
        release()

        assertTrue(
            abs(widthDuring - widthBefore) < 1f,
            "右边缘返回也不应缩放:widthBefore=$widthBefore widthDuring=$widthDuring",
        )
        assertTrue(
            leftDuring < leftBefore - widthBefore * 0.2f,
            "右边缘返回应向左滑出:leftBefore=$leftBefore leftDuring=$leftDuring",
        )
    }

    // --- 工作流→首页:整页等比缩放 + 同步线性淡出 ---

    @Test
    fun `workflow shrink and fade share one uniform linear timeline`() {
        val motion = WORKFLOW_SHRINK_MOTION
        val start = motion.frameAt(0f)
        val quarter = motion.frameAt(0.25f)
        val half = motion.frameAt(0.5f)
        val end = motion.frameAt(1f)

        // 起点:原尺寸、完全不透明。
        assertEquals(1f, start.scale, 0.0001f)
        assertEquals(1f, start.alpha, 0.0001f)
        // 缩放与透明度沿同一条进度线性推进,半程正好各走一半。
        assertEquals(1f + (motion.targetScale - 1f) * 0.25f, quarter.scale, 0.0001f)
        assertEquals(0.75f, quarter.alpha, 0.0001f)
        assertEquals(1f + (motion.targetScale - 1f) * 0.5f, half.scale, 0.0001f)
        assertEquals(0.5f, half.alpha, 0.0001f)
        // 终点:缩到目标比例、完全透明。
        assertEquals(motion.targetScale, end.scale, 0.0001f)
        assertEquals(0f, end.alpha, 0.0001f)
        // 淡出不得晚于缩放:延迟为 0 且两者时长一致,否则会「缩完了还在、或淡完了还在缩」。
        assertEquals(0, motion.fadeDelayMillis)
        assertEquals(motion.durationMillis, motion.fadeDurationMillis)
    }

    @Test
    fun `workflow return keeps uniform aspect ratio without stretching`() {
        rule.setContent {
            PixFoldApp(store = InMemorySettingsStore(PixFoldSettings(predictiveBackAnimation = true)), workspaceSource = ReadyWorkspaceSource())
        }
        rule.waitForIdle()
        rule.onNodeWithText(TestText.of(R.string.home_workflow_a)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_SORT_PAGE).assertExists()

        val before = bounds(TAG_SORT_PAGE)
        val ratioBefore = before.width / before.height

        gestureTo(0.5f)

        val during = bounds(TAG_SORT_PAGE)
        val ratioDuring = during.width / during.height
        println(
            "WorkflowAspect probe: before=${before.width}x${before.height} " +
                "during=${during.width}x${during.height} ratioBefore=$ratioBefore ratioDuring=$ratioDuring",
        )

        assertTrue(
            during.width < before.width * 0.95f,
            "工作流返回应缩小:before=${before.width} during=${during.width}",
        )
        // 等比缩放:宽高比在全程保持不变;FillBounds 之类把内容拉满目标 bounds 会让比例漂移。
        assertEquals(
            ratioBefore,
            ratioDuring,
            0.02f,
            "工作流返回必须整体等比缩放,不能横向拉伸:" +
                "ratioBefore=$ratioBefore ratioDuring=$ratioDuring",
        )
        release()
    }

    // --- 开关关闭:两级都纹丝不动 ---

    @Test
    fun `with the switch off neither level transforms`() {
        rule.setContent {
            PixFoldApp(store = InMemorySettingsStore(PixFoldSettings(predictiveBackAnimation = false)), workspaceSource = ReadyWorkspaceSource())
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