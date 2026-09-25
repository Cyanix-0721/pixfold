package com.pixfold.d1.ui.plan

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import com.pixfold.d1.domain.mock.MockData
import com.pixfold.d1.domain.mock.MockDataB
import com.pixfold.d1.ui.components.TAG_PAGE_ACTION_BAR
import com.pixfold.d1.ui.theme.PixFoldTheme
import com.pixfold.d1.ui.workflowa.TAG_A_PLAN_CONFIRM
import com.pixfold.d1.ui.workflowa.TAG_STEP_BACK
import com.pixfold.d1.ui.workflowa.TAG_STEP_NAMING
import com.pixfold.d1.ui.workflowa.TAG_TO_PLAN
import com.pixfold.d1.ui.workflowa.WorkflowASteps
import com.pixfold.d1.ui.workflowb.TAG_B_STEP_BACK
import com.pixfold.d1.ui.workflowb.TAG_LIBRARY_NEXT
import com.pixfold.d1.ui.workflowb.TAG_METADATA_NEXT
import com.pixfold.d1.ui.workflowb.WorkflowBSteps
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * **底栏的视觉规格**守护(用户 2026-09-25 真机走查后追加)。
 *
 * 用户逐字:「底栏高度可以稍微低一点,而且有的按钮还是纯文字描述而不是带背景的按钮,
 * 步骤3的撤销按钮背景改红色。」这句话里有三条**可证伪的视觉要求**,而原有的
 * `PlanWiringTest` 那 4 条位置守卫只钉「左 / 右」,一条都抓不住:
 *
 *  1. **高度**:底栏比之前低 —— 所以既要钉「不超过阈值」,也要钉「下不去触控下限」;
 *  2. **返回类按钮要有填充底色** —— `TextButton` 与 `FilledTonalButton` 的**语义树完全一样**
 *     (`Role.Button` + 同一段文字),`assertIsDisplayed()` 看不出区别,只有**取像素**能分辨;
 *  3. **撤销按钮是红色** —— 同理,`OutlinedButton` 与红底 `Button` 在语义层无从区分;
 *     且「红」还必须是**暗红**:深色基线的 `error` 是 `#F2B8B5` 淡粉,它也"偏红",
 *     用户看到的就是「不像危险动作」(2026-09-26 用户收紧措辞后补上明度判据)。
 *
 * ## 为什么必须看像素
 *
 * 这一层守护的正是本项目第 4 层验收「UI 判断是否符合需要的操作逻辑」的**视觉子集**:
 * 前 3 层(领域单测 / UI 断言 / 真机实证)在控件类型换错时会全绿 ——
 * 因为换错控件类型**不改变任何语义**,只改变画出来的像素。
 *
 * ⚠️ 而**本文件第一版自己就假绿了**:裸组合页面、没包 `PixFoldTheme`,于是采到的是
 * material3 **基线浅色**(`error` `#B3261E`),而设备是**深色**(`#F2B8B5`),
 * 真实缺陷因此被放过。所以现在所有取样都在 [setContentLight] / [setContentDark]
 * 建立的真实主题下进行(见那两个函数的 KDoc)。
 *
 * 取像素的做法与 `TransitionStyleTest` 一致:`@GraphicsMode(NATIVE)` +
 * `captureToImage().asAndroidBitmap()`(Robolectric 的 LEGACY 图形后端画不出真实颜色)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w361dp-h794dp-560dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BottomBarStyleTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    // ---- 像素取样脚手架 ----

    private fun px(value: Dp): Int = with(rule.density) { value.toPx() }.toInt()

    /** `DpRect` 没有 `center`(与 `Rect` 不同),自己算垂直中线。 */
    private fun centerY(bounds: DpRect): Dp = (bounds.top + bounds.bottom) / 2

    private fun rootBitmap(): Bitmap = rule.onRoot().captureToImage().asAndroidBitmap()

    /**
     * 在某个节点的**容器区域**里取一个像素。
     *
     * 取点规则:垂直居中、水平距左边缘 4dp。M3 按钮是**胶囊形**(`CornerFull`),
     * 在垂直中线上填充一直延伸到左边缘,所以这个点是「容器色」而不是「背景色」;
     * 同时又避开了居中文字的字形。
     */
    private fun sampleContainer(
        bitmap: Bitmap,
        bounds: DpRect,
    ): Int = bitmap.getPixel(px(bounds.left) + 4, px(centerY(bounds)))

    /** 两个像素的通道差之和;用于判断「底色可见」。 */
    private fun channelDistance(a: Int, b: Int): Int =
        abs(Color.red(a) - Color.red(b)) +
            abs(Color.green(a) - Color.green(b)) +
            abs(Color.blue(a) - Color.blue(b))

    private fun bounds(tag: String): DpRect = rule.onNodeWithTag(tag).getBoundsInRoot()

    /**
     * 组合内容时**必须包 `PixFoldTheme`**。
     *
     * 这是本文件第一版的**假绿根因**:当时四条守卫全是裸组合 `rule.setContent { WorkflowASteps(…) }`,
     * 拿到的是 material3 的**基线浅色** `colorScheme`(即 `lightColorScheme()`,error `#B3261E`),
     * 而设备上是**深色**(error `#F2B8B5`)。两条分支的像素完全不同,守卫却全绿 ——
     * 于是「深色下撤销按钮是淡粉」这个真实缺陷被放过去了。
     *
     * 因此这里显式给浅 / 深两套主题各建一个入口,并让每个像素断言都在**应用真实主题**下取值。
     * 用 `dynamicColor = false` 是为了确定性:动态取色依赖系统壁纸,Robolectric 下不可控。
     */
    private fun setContentLight(content: @Composable () -> Unit) =
        rule.setContent { PixFoldTheme(darkTheme = false, dynamicColor = false, content = content) }

    private fun setContentDark(content: @Composable () -> Unit) =
        rule.setContent { PixFoldTheme(darkTheme = true, dynamicColor = false, content = content) }

    /**
     * sRGB 相对亮度(与 `androidx.compose.ui.graphics.luminance()` 同一公式)。
     *
     * 这里手算而不用那个扩展,是因为本文件已 `import android.graphics.Color`,
     * 与 `androidx.compose.ui.graphics.Color` 同名冲突。
     */
    private fun relativeLuminance(color: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(Color.red(color)) +
            0.7152 * channel(Color.green(color)) +
            0.0722 * channel(Color.blue(color))
    }

    // ================= 1. 高度 =================

    /**
     * 底栏必须**比改造前矮**,但不能矮到丢掉触控尺寸。
     *
     * 改造前(用户走查时的形态):外层 `Column` 叠了 `spacedBy(6.dp)`,内层
     * `padding(vertical = 8.dp)` + `spacedBy(6.dp)` ⇒ 报告页(带 `extraContent`)
     * 实际高度 71dp+(单按钮页 71dp)。改造后:去掉外层 `spacedBy`、垂直内边距 6dp、
     * 行内间距 4dp ⇒ 单按钮页 = 1(分隔线) + 6 + 48(按钮触控下限) + 6 = **61dp**。
     *
     * 下限 56dp 的作用是防止「为了矮而矮」:48dp 是约束 7 的触控下限,
     * 再往下压就只能缩按钮本身,那是**用它换高度**、不是本需求要的。
     */
    @Test
    fun `bottom bar height dropped without going under the touch target floor`() {
        val lib = MockDataB.library
        setContentLight { WorkflowBSteps(rootPath = lib.rootPath, volumes = lib.volumes) }
        // 步骤 2 是最"满"的形态:左右两个按钮同时在
        rule.onNodeWithTag(TAG_LIBRARY_NEXT).performClick()

        val bar = bounds(TAG_PAGE_ACTION_BAR)
        val height = (bar.bottom - bar.top).value
        println("BottomBarStyle probe: bar height = $height dp")

        assertTrue(height <= 64f, "底栏应比改造前(71dp)矮,实际 $height dp")
        assertTrue(height >= 56f, "底栏不应压到触控下限以下,实际 $height dp")
    }

    // ================= 2. 返回类按钮要有底色 =================

    /**
     * 工作流 B 步骤 2:左「← 返回上一步」与右「下一步 打包计划」**都必须是带底色的按钮**。
     *
     * 判据是「按钮容器区域的颜色 ≠ 同一根底栏上的空白底色」。`TextButton` 的容器是透明的,
     * 取样点会**正好等于**页面底色,差值 0 —— 这正是用户说的「纯文字描述」。
     */
    @Test
    fun `both bottom bar buttons on workflow B step 2 are filled`() {
        val lib = MockDataB.library
        setContentLight { WorkflowBSteps(rootPath = lib.rootPath, volumes = lib.volumes) }
        rule.onNodeWithTag(TAG_LIBRARY_NEXT).performClick()

        val bmp = rootBitmap()
        val back = bounds(TAG_B_STEP_BACK)
        val next = bounds(TAG_METADATA_NEXT)
        val bar = bounds(TAG_PAGE_ACTION_BAR)

        // 两个按钮之间的空白处 = 底栏(也就是页面)的底色
        val gapX = (px(back.right) + px(next.left)) / 2
        val background = bmp.getPixel(gapX, px(centerY(back)))

        val backColor = sampleContainer(bmp, back)
        val nextColor = sampleContainer(bmp, next)
        println(
            "BottomBarStyle probe: background=${hex(background)} back=${hex(backColor)} next=${hex(nextColor)}",
        )

        assertTrue(
            channelDistance(backColor, background) >= 12,
            "返回按钮应有填充底色(不能是纯文字按钮);实测按钮=${hex(backColor)} 底色=${hex(background)}",
        )
        assertTrue(
            channelDistance(nextColor, background) >= 12,
            "下一步按钮应有填充底色;实测按钮=${hex(nextColor)} 底色=${hex(background)}",
        )
    }

    /** 工作流 A 步骤 2 同理:左「← 返回排序」也必须有底色。 */
    @Test
    fun `bottom bar back button on workflow A step 2 is filled`() {
        setContentLight { WorkflowASteps(collections = MockData.workspaceA.collections) }
        rule.onNodeWithTag(TAG_STEP_NAMING).performClick()

        val bmp = rootBitmap()
        val back = bounds(TAG_STEP_BACK)
        val next = bounds(TAG_TO_PLAN)

        val gapX = (px(back.right) + px(next.left)) / 2
        val background = bmp.getPixel(gapX, px(centerY(back)))
        val backColor = sampleContainer(bmp, back)
        println("BottomBarStyle probe: A2 background=${hex(background)} back=${hex(backColor)}")

        assertTrue(
            channelDistance(backColor, background) >= 12,
            "返回按钮应有填充底色;实测按钮=${hex(backColor)} 底色=${hex(background)}",
        )
    }

    // ================= 3. 撤销按钮是红色 =================

    /** 走到报告页(工作流 A 步骤 3 的终局)。 */
    private fun navigateToReport() {
        rule.onNodeWithTag(TAG_STEP_NAMING).performClick()
        rule.onNodeWithTag(TAG_TO_PLAN).performClick()
        rule.onNodeWithTag(TAG_A_PLAN_CONFIRM).performClick()
        rule.onNodeWithTag(TAG_CONFIRM_OK).performClick()
        rule.onNodeWithTag(TAG_REPORT_PAGE).assertExists()
    }

    /**
     * 撤销按钮的底色必须是**饱和且暗的红**,也就是「看起来就是个危险动作」。
     *
     * ## 判据为什么是两条而不是一条
     *
     * 光断「偏红」(`r > g+40 && r > b+40`)**抓不住本缺陷**:material3 深色基线的
     * `error` = `#F2B8B5` 是 `(242,184,181)`,它 `242 > 184+40` 且 `242 > 181+40`,
     * 「偏红」判定**照样通过** —— 可它是**淡粉**,用户看到的就是「不像危险动作」。
     *
     * 所以再加一条**明度上限**:危险动作的**底**必须是暗色,淡粉(相对亮度 ≈0.56)
     * 是给「错误文字」提亮用的,不能当底。实测两套配色都远低于阈值:
     * 浅色 `error` `#B3261E` 亮度 ≈0.11、深色 `errorContainer` `#8C1D18` 亮度 ≈0.07。
     *
     * ## 为什么浅 / 深各一条
     *
     * error 族是**按模式反转**的:浅色下要取 `error`、深色下要取 `errorContainer`
     * (见 `dangerActionButtonColors()` 的 KDoc)。只测其中一个模式,
     * 另一个模式回归时不会响 —— 而**本缺陷恰恰只在深色下出现**。
     */
    private fun assertUndoIsDarkRed() {
        val bmp = rootBitmap()
        val undo = bounds(TAG_REPORT_UNDO)
        val color = sampleContainer(bmp, undo)
        val lum = relativeLuminance(color)
        println("BottomBarStyle probe: undo=${hex(color)} luminance=$lum")

        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        assertTrue(
            r > g + 40 && r > b + 40,
            "撤销按钮应偏红;实测 r=$r g=$g b=$b (${hex(color)})",
        )
        assertTrue(
            lum < 0.25,
            "撤销按钮应是**暗**红(危险动作的底),不能是淡粉;实测亮度=$lum (${hex(color)})",
        )
    }

    @Test
    fun `report undo button is painted dark red in light theme`() {
        setContentLight { WorkflowASteps(collections = MockData.workspaceA.collections) }
        navigateToReport()
        assertUndoIsDarkRed()
    }

    @Test
    fun `report undo button is painted dark red in dark theme`() {
        setContentDark { WorkflowASteps(collections = MockData.workspaceA.collections) }
        navigateToReport()
        assertUndoIsDarkRed()
    }

    private fun hex(color: Int): String = String.format("#%08X", color)
}
