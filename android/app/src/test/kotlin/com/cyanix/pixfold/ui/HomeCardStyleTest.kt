package com.cyanix.pixfold.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.DpRect
import com.cyanix.pixfold.R
import com.cyanix.pixfold.TestText
import com.cyanix.pixfold.domain.settings.AccentChoice
import com.cyanix.pixfold.domain.settings.PixFoldSettings
import com.cyanix.pixfold.domain.settings.ThemeMode
import com.cyanix.pixfold.ui.theme.PixFoldTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * 首页大卡片的形态守护(用户 2026-10-14 走查后裁决)。
 *
 * ## 用户逐字
 *
 * 1. 「主要功能就是工作流AB，所以主页应该有**2个大卡片**显示，并且改下文案，**直接叫工作流不好听**」
 * 2. 标题裁决:「**整理图片**」/「**打包漫画**」—— 去掉「工作流 A/B」字样;
 * 3. 副标题裁决:「**要，每个卡一行副标题**」;
 * 4. 卡片可见性裁决:「**卡片加描边**（跟随纯黑开关说明）」。
 *
 * ## 第 4 条是**真机实测缺陷**,不是审美偏好
 *
 * 2026-10-14 真机(`pure_black=true`,设备日常配置)逐行扫描卡 A 上下边缘带
 * `y=406..415` / `y=620..635`、`x=40..1220` —— **全部 `(0,0,0)`**;
 * 卡 A 高频色统计 `(0,0,0)` 244,706px vs 文字色 `(248,224,225)` 8,764px
 * ⇒ 卡片**没有任何可见边界**,与背景融为一体。
 *
 * 根因(一手):`HomeScreen` 用 M3 `Card` 默认配色,其填充角色是 `surfaceContainerLow`;
 * 而 `ColorSchemes.withPureBlack()` 把 `background` / `surface` / `surfaceContainer*` /
 * `surfaceBright` / `surfaceDim` **全部压成 `Color.Black`** ⇒ 卡与背景同色。
 * 设置页自己写着「OLED 更省电;**层级改由边框表达**」,首页却没照做。
 *
 * ## 为什么必须取像素
 *
 * 「有没有描边」在**语义树里完全不存在** —— `Card` 加不加 `border` 参数,
 * `assertIsDisplayed()` / `assertExists()` 一律全绿。这与 `BottomBarStyleTest`
 * 记录的同一个坑同源(控件类型/装饰换错不改变语义,只改变画出来的像素)。
 *
 * ## ⚠️ 必须在**纯黑**主题下取样
 *
 * 非纯黑模式下 M3 的 `surfaceContainerLow` 与 `background` **本来就不同色**,
 * 描边缺失也照样"可见" —— 在默认主题下跑本文件,描边断言会**假绿**。
 * 故取样一律走 [setContentPureBlackDark]。
 *
 * ## 文案为什么写死英文字面量
 *
 * 标题与副标题是**用户当回合裁决的文案**,属于「定稿内容」而非「界面文案」。
 * 与 `ResourceFallbackTest` 同一个理由:若经 `TestText.of(R.string.…)` 取,
 * 断言就退化成"资源里有什么就断言什么" —— **改错了文案测试照样绿**。
 * Robolectric 默认 locale 为 `en_US`(解析默认槽位 `values/`),故字面量取英文。
 *
 * ## 卡片节点怎么定位
 *
 * 卡片的 `clickable` 带 `mergeDescendants = true`,故**合并后的语义节点就是卡片本身**,
 * 其 `Text` 含标题与副标题两条。`onNodeWithText(标题)` 因此命中**卡片节点**,
 * `getBoundsInRoot()` 拿到的是**整卡**而非文字行 —— 高度与边缘取样都依赖这一点。
 * (标题经 `TestText.of` 取,与资源同源,故本文件不因改文案而失效。)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w361dp-h794dp-560dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeCardStyleTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** 用户裁决的标题(逐字,英文为默认槽位文案)。 */
    private val titleA = "Organize images"
    private val titleB = "Pack comics"

    /** 用户裁决的副标题(逐字)。 */
    private val subtitleA = "Sort, rename and preview by your own rules"
    private val subtitleB = "Fill in metadata and pack volumes into CBZ"

    // ---- 脚手架 ----

    private fun px(value: Float): Int = (value * rule.density.density).toInt()

    private fun rootBitmap(): Bitmap = rule.onRoot().captureToImage().asAndroidBitmap()

    /** 卡片**整卡**的 bounds(不是文字行)—— 见类注释「卡片节点怎么定位」。 */
    private fun cardBounds(text: String): DpRect = rule.onNodeWithText(text).getBoundsInRoot()

    private fun channelDistance(a: Int, b: Int): Int =
        abs(Color.red(a) - Color.red(b)) +
            abs(Color.green(a) - Color.green(b)) +
            abs(Color.blue(a) - Color.blue(b))

    private fun hex(color: Int): String =
        String.format("#%02X%02X%02X", Color.red(color), Color.green(color), Color.blue(color))

    /**
     * 组合内容时**必须**用纯黑 + 深色主题,且**必须**套上应用真实的背景 `Surface`。
     *
     * `accent = Default`(而非 `System`)是为了确定性:动态取色依赖系统壁纸,
     * Robolectric 下不可控。
     *
     * ## ⚠️ `Surface(background)` 这一层是本文件的**假绿陷阱**
     *
     * 第一版只写了 `PixFoldTheme { HomeScreen(…) }`。实测探针读出
     * `background=#FAFAFA` —— 那是 **Robolectric 的窗口默认底色**,不是应用的
     * `colorScheme.background`。于是"卡片边缘 vs 背景"比较的其实是"描边 vs 白底",
     * 通道差 531,**即使纯黑下卡片完全不可见也照样全绿**。
     *
     * `PixFoldApp.kt` 里真实的层级是 `PixFoldTheme { Surface(color = background) { … } }`
     * (该 `Surface` 的注释写明:深色模式下不能透出窗口白底)。故本文件必须复刻这一层,
     * 否则守的就不是真机上的那个画面。
     */
    private fun setContentPureBlackDark(content: @Composable () -> Unit) = rule.setContent {
        PixFoldTheme(
            settings = PixFoldSettings(
                themeMode = ThemeMode.Dark,
                accent = AccentChoice.Default,
                pureBlack = true,
            ),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
                content = content,
            )
        }
    }

    private fun home() = setContentPureBlackDark {
        HomeScreen(onOpenWorkflowA = {}, onOpenWorkflowB = {})
    }

    // ================= 1. 文案:不再出现「工作流 A/B」 =================

    /**
     * 标题必须换成用户裁决的两个词,且**不得**再出现「工作流」字样。
     *
     * 这条断言的是**可见文案本身**,不是资源 id —— 用户的原话是「直接叫工作流不好听」,
     * 所以真正要守的是「屏幕上不再出现那个词」。
     */
    @Test
    fun `home card titles drop the workflow wording`() {
        home()

        rule.onNodeWithText(titleA).assertIsDisplayed()
        rule.onNodeWithText(titleB).assertIsDisplayed()

        // 「Workflow」字样必须从两张卡上彻底消失(旧文案是 "Workflow A · …" / "Workflow B · …")
        val stale = rule.onAllNodesWithText("Workflow", substring = true).fetchSemanticsNodes()
        assertTrue(
            stale.isEmpty(),
            "首页卡片不应再出现「Workflow」字样;实测仍有 ${stale.size} 个节点命中",
        )
    }

    // ================= 2. 副标题:每卡一行 =================

    /**
     * 两张卡**各有一行副标题**(用户裁决「要，每个卡一行副标题」)。
     *
     * 副标题必须与标题**同卡**:靠「副标题节点与标题节点在同一张卡内」来钉,
     * 否则两行文字挂在别处也能过。
     */
    @Test
    fun `both home cards carry a one line subtitle`() {
        home()

        rule.onNodeWithText(subtitleA).assertIsDisplayed()
        rule.onNodeWithText(subtitleB).assertIsDisplayed()

        // 同卡判据:标题卡的高度必须**容得下**两行(标题 + 副标题)。
        // 若副标题被放到卡外,卡高会退回单行量级(改造前 64dp),这条即失败。
        val h = cardBounds(titleA).let { (it.bottom - it.top).value }
        assertTrue(
            h >= 80f,
            "卡片高度 $h dp 装不下「标题 + 副标题」两行 —— 副标题可能没在卡内",
        )
    }

    // ================= 3. 纯黑下卡片边界可见(真机缺陷回归) =================

    /**
     * 纯黑模式下,卡片的**上边缘必须与卡外背景明显可分**。
     *
     * 取样点:卡 A 上边缘(描边)vs 两卡之间空隙的中点(背景)。
     * 缺陷态下两者都是 `#000000`,通道差为 0 ⇒ 断言失败。
     */
    @Test
    fun `home cards stay visible on the pure black background`() {
        home()

        val cardA = cardBounds(titleA)
        val cardB = cardBounds(titleB)
        val bitmap = rootBitmap()

        // 上边缘:水平取卡中点(避开圆角),纵向 +1px 落在描边上
        val edge = bitmap.getPixel(px((cardA.left + cardA.right).value / 2f), px(cardA.top.value) + 1)
        // 背景:两卡之间的空隙(Column 的 spacedBy(12.dp) 区),水平取屏宽中点
        val gapY = px((cardA.bottom + cardB.top).value / 2f)
        val background = bitmap.getPixel(px(180f), gapY)

        val distance = channelDistance(edge, background)
        println(
            "HomeCardStyle probe: edge=${hex(edge)} background=${hex(background)} distance=$distance",
        )

        assertTrue(
            distance >= 24,
            "纯黑模式下卡片上边缘(${hex(edge)})必须与背景(${hex(background)})可分 —— " +
                "通道差仅 $distance;为 0 即「卡片与背景同色、完全看不见边界」(2026-10-14 真机缺陷)",
        )
    }

    // ================= 4. 是「大卡片」 =================

    /**
     * 卡片要够高,才称得上用户要的「**大**卡片」。
     *
     * 改造前实测高度 **64dp**(真机 `home1.xml`:卡 A `[56,406][1208,630]` = 224px ÷ 3.5)。
     * 下限取 **80dp**:低于它就说明副标题没占住一行、或内边距被压回去了。
     * 上限 200dp 防「为了大而大」把首屏撑爆(首页还要留出设置入口)。
     */
    @Test
    fun `home cards are large enough to read as big cards`() {
        home()

        val height = cardBounds(titleA).let { (it.bottom - it.top).value }
        println("HomeCardStyle probe: card height = $height dp")

        assertTrue(height >= 80f, "卡片应是大卡片(≥80dp),实测 $height dp —— 改造前为 64dp")
        assertTrue(height <= 200f, "卡片不应大到挤掉首屏其它内容,实测 $height dp")
    }
}
