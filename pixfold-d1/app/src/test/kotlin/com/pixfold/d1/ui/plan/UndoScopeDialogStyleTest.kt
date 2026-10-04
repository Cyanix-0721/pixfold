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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.pixfold.d1.domain.mock.MockDataB
import com.pixfold.d1.domain.plan.OutputConflictPolicy
import com.pixfold.d1.domain.plan.buildPackagePlans
import com.pixfold.d1.domain.plan.executePackagePlans
import com.pixfold.d1.ui.theme.PixFoldTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * **撤销范围对话框的按钮配色**守护(用户 2026-10-04,m04079 第③项)。
 *
 * 用户逐字:「…记得**按钮颜色要区分开**，把取消放到最后边」。次序由
 * [UndoScopeDialogTest] 用布局坐标钉住;这条要求**只能看像素**:
 *
 *  - `Button`(填充)与 `TextButton`(纯文字)在**语义树里完全一样** ——
 *    都是 `Role.Button` + 同一段文字,`assertIsDisplayed()` 一条都抓不住;
 *  - 两个执行档必须是**深红填充**(与底栏撤销按钮同一套 `dangerActionButtonColors()`),
 *    取消必须是**无底色的纯文字按钮** ⇒ 用户一眼能分出"哪个会删东西、哪个只是关掉"。
 *
 * ## 判据为什么是「两两可分」而不是「等于某个色号」
 *
 * 写死色号会把测试焊死在当前配色上(换主题就红,而不是发现缺陷)。真正要守的**性质**是:
 *
 *  1. 两个执行档都**偏红且是暗色**(淡粉 `#F2B8B5` 也"偏红",但不像危险动作);
 *  2. 取消的底色**等于对话框表面色**(即容器透明),与两个执行档**明显不同**。
 *
 * ## ⚠️ 必须在真实主题下取样
 *
 * 与 `BottomBarStyleTest` 同一个坑:裸组合页面会采到 material3 **基线浅色**
 * (`error` `#B3261E`),而设备是深色;`dangerActionButtonColors()` 恰恰**按模式取不同档位**
 * (浅色取 `error`、深色取 `errorContainer`)。故浅 / 深各一条用例,
 * 全部经 [setContentLight] / [setContentDark] 建立真实主题。
 *
 * 取像素:`@GraphicsMode(NATIVE)` + `captureToImage().asAndroidBitmap()`
 * (LEGACY 后端画不出真实颜色)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2000dp-560dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UndoScopeDialogStyleTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val library = MockDataB.library

    // ---- 像素取样脚手架(与 BottomBarStyleTest 同款) ----

    private fun px(value: Dp): Int = with(rule.density) { value.toPx() }.toInt()

    private fun centerY(bounds: DpRect): Dp = (bounds.top + bounds.bottom) / 2

    private fun bounds(tag: String): DpRect = rule.onNodeWithTag(tag).getBoundsInRoot()

    /** 两个像素的通道差之和;用于判断「底色可见 / 两个控件颜色不同」。 */
    private fun channelDistance(a: Int, b: Int): Int =
        abs(Color.red(a) - Color.red(b)) +
            abs(Color.green(a) - Color.green(b)) +
            abs(Color.blue(a) - Color.blue(b))

    private fun relativeLuminance(color: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(Color.red(color)) +
            0.7152 * channel(Color.green(color)) +
            0.0722 * channel(Color.blue(color))
    }

    private fun hex(color: Int): String = String.format("#%08X", color)

    /**
     * 界面上**真的弹出了这个对话框**吗 —— 像素坐标是相对对话框的。
     *
     * ⚠️ 对话框在**独立窗口**里,不能像底栏那样对 `onRoot()` 取图再换算坐标,
     * 故这里对对话框节点自身取图,并用「节点在 root 里的位置 − 对话框在 root 里的位置」
     * 把某个子节点的位置翻译成该位图内的坐标。
     */
    private fun dialogBitmap(): Bitmap =
        rule.onNodeWithTag(TAG_UNDO_SCOPE_DIALOG).captureToImage().asAndroidBitmap()

    /**
     * 在对话框位图里取某个子节点**容器区域**的一个像素。
     *
     * 取点规则与 `BottomBarStyleTest.sampleContainer` 一致:垂直居中、距左边缘 4dp。
     * M3 按钮是胶囊形,垂直中线上填充一直延伸到左边缘,所以这点取到的是**容器色**
     * 而不是背后表面色;同时又避开了居中文字的字形。
     */
    private fun sampleInDialog(bitmap: Bitmap, tag: String): Int {
        val dlg = bounds(TAG_UNDO_SCOPE_DIALOG)
        val node = bounds(tag)
        val x = px(node.left - dlg.left) + px(4.dp)
        val y = px(centerY(node) - dlg.top)
        return bitmap.getPixel(x, y)
    }

    /**
     * 对话框的**表面色**参照点:两个执行档之间的空隙。
     *
     * 这是"容器透明"的判据来源 —— 取消按钮是 `TextButton`,它那一点的像素**就等于**
     * 这个表面色(而不是"另一个颜色")。
     */
    private fun sampleDialogSurface(bitmap: Bitmap): Int {
        val dlg = bounds(TAG_UNDO_SCOPE_DIALOG)
        val gapY = (bounds(TAG_UNDO_SCOPE_EVERYTHING).bottom + bounds(TAG_UNDO_SCOPE_CANCEL).top) / 2
        return bitmap.getPixel(px(4.dp), px(gapY - dlg.top))
    }

    private fun setContentLight(content: @Composable () -> Unit) =
        rule.setContent { PixFoldTheme(darkTheme = false, dynamicColor = false, content = content) }

    private fun setContentDark(content: @Composable () -> Unit) =
        rule.setContent { PixFoldTheme(darkTheme = true, dynamicColor = false, content = content) }

    /** `op-102` 的输出原本就存在 ⇒ Overwrite 下会覆盖,于是点撤销会先弹范围框。 */
    private fun openDialog(dark: Boolean) {
        val op102 = library.volumes.first { it.id == "op-102" }
        val report = executePackagePlans(
            buildPackagePlans(listOf(op102), conflictPolicy = OutputConflictPolicy.Overwrite),
        )
        val content: @Composable () -> Unit = {
            ExecutionReportPage(
                report = report,
                undoMessage = null,
                onUndoScope = {},
                onDone = {},
            )
        }
        if (dark) setContentDark(content) else setContentLight(content)
        rule.onNodeWithTag(TAG_REPORT_UNDO).performClick()
        rule.onNodeWithTag(TAG_UNDO_SCOPE_DIALOG).assertExists()
    }

    /** 「危险动作」的底色判据:偏红 **且** 是暗色(两条都要,理由见 KDoc)。 */
    private fun assertDarkRedContainer(color: Int, what: String) {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val lum = relativeLuminance(color)
        assertTrue(
            r > g + 40 && r > b + 40,
            "$what 应有偏红的填充底色;实测 r=$r g=$g b=$b (${hex(color)})",
        )
        assertTrue(
            lum < 0.25,
            "$what 应是**暗**红(危险动作的底),不能是淡粉;实测亮度=$lum (${hex(color)})",
        )
    }

    /**
     * 两个执行档都是深红填充、取消是无底色纯文字 ⇒ 三者**颜色互相可分**。
     *
     * 一次跑完三条性质,浅 / 深只是主题不同:
     *  ① 两个执行档各是暗红;
     *  ② 取消那一点**等于**对话框表面色(容器透明,不是"另一种颜色");
     *  ③ 执行档与取消的通道差足够大(用户"一眼能区分"的可证伪化)。
     */
    private fun assertScopesAndCancelAreDistinguishable() {
        val bmp = dialogBitmap()
        val safe = sampleInDialog(bmp, TAG_UNDO_SCOPE_THIS_RUN)
        val danger = sampleInDialog(bmp, TAG_UNDO_SCOPE_EVERYTHING)
        val cancel = sampleInDialog(bmp, TAG_UNDO_SCOPE_CANCEL)
        val surface = sampleDialogSurface(bmp)
        println(
            "UndoScopeDialogStyle probe: safe=${hex(safe)} danger=${hex(danger)} " +
                "cancel=${hex(cancel)} surface=${hex(surface)}",
        )

        assertDarkRedContainer(safe, "「仅撤销本次生成的」按钮")
        assertDarkRedContainer(danger, "「全部删除(含被覆盖的)」按钮")

        assertTrue(
            channelDistance(cancel, surface) < 12,
            "取消应是**无底色**的纯文字按钮(取样点应等于对话框表面色);" +
                "实测取消=${hex(cancel)} 表面=${hex(surface)}",
        )
        assertTrue(
            channelDistance(cancel, safe) >= 40,
            "取消与执行档的颜色必须明显不同(用户:按钮颜色要区分开);" +
                "实测取消=${hex(cancel)} 执行档=${hex(safe)}",
        )
    }

    @Test
    fun `scope buttons are dark red while cancel is unfilled in light theme`() {
        openDialog(dark = false)
        assertScopesAndCancelAreDistinguishable()
    }

    @Test
    fun `scope buttons are dark red while cancel is unfilled in dark theme`() {
        openDialog(dark = true)
        assertScopesAndCancelAreDistinguishable()
    }
}
