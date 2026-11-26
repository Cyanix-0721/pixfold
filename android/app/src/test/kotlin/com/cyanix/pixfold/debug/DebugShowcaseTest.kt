package com.cyanix.pixfold.debug

import android.content.Intent
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cyanix.pixfold.domain.settings.AccentChoice
import com.cyanix.pixfold.domain.settings.ColorSpecVersion
import com.cyanix.pixfold.domain.settings.ColorStyle
import com.cyanix.pixfold.domain.settings.PixFoldSettings
import com.cyanix.pixfold.domain.settings.ThemeMode
import com.cyanix.pixfold.ui.PixFoldUiTestDefaults
import com.cyanix.pixfold.ui.assertAccessibleTargets
import com.cyanix.pixfold.ui.assertAllNodesFitViewport
import com.cyanix.pixfold.ui.settings.TAG_SETTINGS_PAGE
import com.cyanix.pixfold.ui.theme.AccentPresets
import com.cyanix.pixfold.ui.theme.ColorSchemes
import com.cyanix.pixfold.ui.workspace.TAG_WORKSPACE_ACTION
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = PixFoldUiTestDefaults.QUALIFIER_ZH_PHONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DebugShowcaseTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun parse_all_15_debug_scenes_succeeds() {
        val expectedIds = listOf(
            "catalog",
            "home",
            "settings",
            "workspace_dialog",
            "chips",
            "chips_two_level",
            "sort",
            "library",
            "metadata",
            "pack_preview",
            "pack_progress",
            "rename_plan",
            "execution_report",
            "undo_dialog",
            "image_preview",
        )
        assertEquals(expectedIds.size, DebugScene.entries.size)
        expectedIds.forEach { id ->
            val parsed = parseDebugShowcaseParams(sceneRaw = id)
            assertNotNull(parsed, "Scene $id should parse cleanly")
            assertEquals(id, parsed.scene.id)
        }
        assertEquals(DebugScene.HOME, parseDebugShowcaseParams(sceneRaw = "HOME")?.scene)
    }

    @Test
    fun parse_theme_color_spec_accent_and_font_scale_clamp() {
        assertNull(parseDebugShowcaseParams(sceneRaw = null))
        assertNull(parseDebugShowcaseParams(sceneRaw = "   "))
        assertNull(parseDebugShowcaseParams(sceneRaw = "unknown_scene"))

        val pureBlackReq = requireNotNull(
            parseDebugShowcaseParams(
                sceneRaw = "settings",
                themeRaw = "pure_black",
                colorSpecRaw = "2021",
                accentRaw = "system",
                fontScaleRaw = 1.3f,
            ),
        )
        assertEquals(ThemeMode.Dark, pureBlackReq.settings.themeMode)
        assertTrue(pureBlackReq.settings.pureBlack)
        assertEquals(ColorSpecVersion.V2021, pureBlackReq.settings.colorSpec)
        assertEquals(AccentChoice.System, pureBlackReq.settings.accent)
        assertEquals(1.3f, pureBlackReq.fontScale)

        val lightClampLow = requireNotNull(
            parseDebugShowcaseParams(
                sceneRaw = "home",
                themeRaw = "light",
                colorSpecRaw = "bad",
                accentRaw = "bad",
                fontScaleRaw = 0.2f,
            ),
        )
        assertEquals(ThemeMode.Light, lightClampLow.settings.themeMode)
        assertFalse(lightClampLow.settings.pureBlack)
        assertEquals(ColorSpecVersion.V2025, lightClampLow.settings.colorSpec)
        assertEquals(AccentChoice.Default, lightClampLow.settings.accent)
        assertEquals(0.85f, lightClampLow.fontScale)

        val clampHigh = requireNotNull(
            parseDebugShowcaseParams(sceneRaw = "home", fontScaleRaw = 4.5f),
        )
        assertEquals(2.0f, clampHigh.fontScale)
    }

    @Test
    fun intent_parsing_exit_flag_and_extra_cleanup() {
        val intent = Intent().apply {
            putExtra(EXTRA_DEBUG_SCENE, "pack_progress")
            putExtra(EXTRA_DEBUG_THEME, "pure_black")
            putExtra(EXTRA_DEBUG_COLOR_SPEC, "2021")
            putExtra(EXTRA_DEBUG_ACCENT, "blue")
            putExtra(EXTRA_DEBUG_FONT_SCALE, "1.3")
            putExtra(EXTRA_DEBUG_EXIT, true)
        }
        val req = requireNotNull(parseDebugShowcaseRequest(intent))
        assertEquals(DebugScene.PACK_PROGRESS, req.scene)
        assertTrue(req.settings.pureBlack)
        assertEquals(ColorSpecVersion.V2021, req.settings.colorSpec)
        assertEquals(AccentChoice.Blue, req.settings.accent)
        assertEquals(1.3f, req.fontScale)
        assertTrue(isExitShowcaseIntent(intent))

        clearDebugShowcaseExtras(intent)
        assertNull(parseDebugShowcaseRequest(intent))
        assertFalse(isExitShowcaseIntent(intent))

        val floatIntent = Intent().apply {
            putExtra(EXTRA_DEBUG_SCENE, "home")
            putExtra(EXTRA_DEBUG_FONT_SCALE, 1.5f)
        }
        assertEquals(1.5f, parseDebugShowcaseRequest(floatIntent)?.fontScale)
    }

    @Test
    fun catalog_renders_all_sub_scenes_and_navigates_with_back_handling() {
        var exited = false
        rule.setContent {
            DebugShowcaseScreen(
                request = DebugShowcaseRequest(
                    scene = DebugScene.CATALOG,
                    settings = PixFoldSettings(themeMode = ThemeMode.Dark, accent = AccentChoice.Default),
                    fontScale = 1.0f,
                ),
                onExitShowcase = { exited = true },
            )
        }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_DEBUG_SHOWCASE_CATALOG).assertIsDisplayed()
        assertAllNodesFitViewport(rule)
        assertAccessibleTargets(rule)

        rule.onNodeWithTag("debug-showcase-item-home").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_DEBUG_SHOWCASE_CATALOG).assertDoesNotExist()
        assertFalse(exited)

        rule.runOnUiThread {
            rule.activity.onBackPressedDispatcher.onBackPressed()
        }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_DEBUG_SHOWCASE_CATALOG).assertIsDisplayed()
        assertFalse(exited)

        rule.runOnUiThread {
            rule.activity.onBackPressedDispatcher.onBackPressed()
        }
        rule.waitForIdle()
        assertTrue(exited)
    }

    @Test
    fun all_14_child_scenes_compose_without_crash() {
        var activeRequest by mutableStateOf(
            DebugShowcaseRequest(
                scene = DebugScene.HOME,
                settings = PixFoldSettings(themeMode = ThemeMode.Dark, accent = AccentChoice.Default),
                fontScale = 1.0f,
            ),
        )
        rule.setContent {
            DebugShowcaseScreen(
                request = activeRequest,
                onExitShowcase = {},
            )
        }
        val childScenes = DebugScene.entries.filter { it != DebugScene.CATALOG }
        assertEquals(14, childScenes.size)
        childScenes.forEach { scene ->
            rule.runOnUiThread {
                activeRequest = activeRequest.copy(scene = scene)
            }
            rule.waitForIdle()
            assertTrue(
                rule.onAllNodes(isRoot()).fetchSemanticsNodes().isNotEmpty(),
                "Scene $scene should render at least one semantics root",
            )
        }
    }

    @Test
    fun compose_previews_render_core_screens_within_viewport() {
        val previewFile = listOf(
            File("src/debug/kotlin/com/cyanix/pixfold/debug/PixFoldComposePreviews.kt"),
            File("app/src/debug/kotlin/com/cyanix/pixfold/debug/PixFoldComposePreviews.kt"),
        ).firstOrNull { it.exists() }
        assertNotNull(previewFile, "PixFoldComposePreviews.kt 应位于 debug 源集下")
        assertTrue(previewFile.readText().contains("@Preview"))
        val manifestFile = listOf(
            File("src/debug/AndroidManifest.xml"),
            File("app/src/debug/AndroidManifest.xml"),
        ).firstOrNull { it.exists() }
        assertNull(manifestFile, "不得新增 src/debug/AndroidManifest.xml")

        var which by mutableIntStateOf(0)
        rule.setContent {
            when (which) {
                0 -> HomeDarkPreview()
                1 -> SettingsDarkPreview()
                else -> PackProgressDialogPreview()
            }
        }

        rule.waitForIdle()
        rule.onNodeWithText("整理图片").assertIsDisplayed()
        assertAllNodesFitViewport(rule)

        rule.runOnUiThread { which = 1 }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_SETTINGS_PAGE).assertIsDisplayed()
        assertAllNodesFitViewport(rule)

        rule.runOnUiThread { which = 2 }
        rule.waitForIdle()
        rule.onNodeWithText("正在打包").assertIsDisplayed()
        assertAllNodesFitViewport(rule)
    }

    @Test
    fun parse_defaults_color_style_to_tonal_spot_and_parses_all_styles() {
        val defaultReq = requireNotNull(parseDebugShowcaseParams(sceneRaw = "home"))
        assertEquals(ColorStyle.TonalSpot, defaultReq.settings.colorStyle)

        val expectedInputs = mapOf(
            ColorStyle.TonalSpot to listOf("tonal_spot", "TonalSpot"),
            ColorStyle.Vibrant to listOf("vibrant", "Vibrant"),
            ColorStyle.Expressive to listOf("expressive", "Expressive"),
            ColorStyle.Neutral to listOf("neutral", "Neutral"),
            ColorStyle.Monochrome to listOf("monochrome", "Monochrome"),
            ColorStyle.Fidelity to listOf("fidelity", "Fidelity"),
            ColorStyle.Content to listOf("content", "Content"),
            ColorStyle.Rainbow to listOf("rainbow", "Rainbow"),
            ColorStyle.FruitSalad to listOf("fruit_salad", "FruitSalad"),
        )
        assertEquals(ColorStyle.entries.size, expectedInputs.size)
        expectedInputs.forEach { (style, raws) ->
            raws.forEach { raw ->
                val req = requireNotNull(
                    parseDebugShowcaseParams(sceneRaw = "home", colorStyleRaw = raw),
                )
                assertEquals(style, req.settings.colorStyle, "raw=$raw 应解析为 $style")
            }
        }

        val fallbackReq = requireNotNull(
            parseDebugShowcaseParams(sceneRaw = "home", colorStyleRaw = "invalid_style"),
        )
        assertEquals(ColorStyle.TonalSpot, fallbackReq.settings.colorStyle)

        val intent = Intent().apply {
            putExtra(EXTRA_DEBUG_SCENE, "settings")
            putExtra(EXTRA_DEBUG_COLOR_STYLE, "fruit_salad")
        }
        assertEquals(ColorStyle.FruitSalad, parseDebugShowcaseRequest(intent)?.settings?.colorStyle)
        clearDebugShowcaseExtras(intent)
        assertNull(parseDebugShowcaseRequest(intent))
        intent.putExtra(EXTRA_DEBUG_SCENE, "home")
        assertEquals(ColorStyle.TonalSpot, parseDebugShowcaseRequest(intent)?.settings?.colorStyle)
    }

    @Test
    fun catalog_color_style_selector_switches_style_and_gates_color_spec() {
        val seedArgb = AccentPresets.DefaultSeed.toArgb()
        val tonalSecondaryArgb = ColorSchemes.fromSeed(
            seedArgb = seedArgb,
            dark = true,
            style = ColorStyle.TonalSpot,
            spec = ColorSpecVersion.V2025,
        ).secondary.toArgb()
        val expressiveSecondaryArgb = ColorSchemes.fromSeed(
            seedArgb = seedArgb,
            dark = true,
            style = ColorStyle.Expressive,
            spec = ColorSpecVersion.V2025,
        ).secondary.toArgb()
        val paletteGap = rgbManhattanDistance(tonalSecondaryArgb, expressiveSecondaryArgb)
        assertTrue(
            paletteGap > 30,
            "TonalSpot 与 Expressive 的 secondary 必须存在足够色差（当前 gap=$paletteGap）",
        )

        val request = requireNotNull(parseDebugShowcaseParams(sceneRaw = "catalog"))
        rule.setContent {
            DebugShowcaseScreen(
                request = request,
                onExitShowcase = {},
            )
        }
        rule.waitForIdle()
        assertAllNodesFitViewport(rule)
        assertAccessibleTargets(rule)

        ColorStyle.entries.forEach { style ->
            rule.onNodeWithTag(debugShowcaseColorStyleTag(style)).assertExists()
        }
        rule.onNodeWithTag(debugShowcaseColorStyleTag(ColorStyle.TonalSpot)).assertIsOn()
        rule.onNodeWithTag(debugShowcaseColorStyleTag(ColorStyle.Vibrant)).assertIsOff()
        rule.onNodeWithTag(TAG_DEBUG_SHOWCASE_SPEC_2025).assertIsEnabled()
        rule.onNodeWithTag(TAG_DEBUG_SHOWCASE_SPEC_2021).assertIsEnabled()

        rule.onNodeWithTag(debugShowcaseColorStyleTag(ColorStyle.Monochrome)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(debugShowcaseColorStyleTag(ColorStyle.Monochrome)).assertIsOn()
        rule.onNodeWithTag(debugShowcaseColorStyleTag(ColorStyle.TonalSpot)).assertIsOff()
        rule.onNodeWithTag(TAG_DEBUG_SHOWCASE_SPEC_2025).assertIsNotEnabled()
        rule.onNodeWithTag(TAG_DEBUG_SHOWCASE_SPEC_2021).assertIsNotEnabled()

        rule.onNodeWithTag(debugShowcaseColorStyleTag(ColorStyle.Expressive)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(debugShowcaseColorStyleTag(ColorStyle.Expressive)).assertIsOn()
        rule.onNodeWithTag(TAG_DEBUG_SHOWCASE_SPEC_2025).assertIsEnabled()
        rule.onNodeWithTag(TAG_DEBUG_SHOWCASE_SPEC_2021).assertIsEnabled()

        val expressiveBtnBitmap = rule
            .onNodeWithTag(debugShowcaseColorStyleTag(ColorStyle.Expressive))
            .captureToImage()
            .asAndroidBitmap()
        var expressiveHits = 0
        var tonalHits = 0
        for (y in 0 until expressiveBtnBitmap.height) {
            for (x in 0 until expressiveBtnBitmap.width) {
                val pixel = expressiveBtnBitmap.getPixel(x, y)
                if (AndroidColor.alpha(pixel) < 250) continue
                if (withinChannelTolerance(pixel, expressiveSecondaryArgb, tolerance = 3)) expressiveHits++
                if (withinChannelTolerance(pixel, tonalSecondaryArgb, tolerance = 3)) tonalHits++
            }
        }
        assertTrue(
            expressiveHits > 300 && tonalHits == 0,
            "切换 Expressive 后选中按钮应真实使用 Expressive 配色（expressiveHits=$expressiveHits, tonalHits=$tonalHits）",
        )
    }

    @Test
    fun home_dark_preview_renders_tonal_spot_secondary_container() {
        val seedArgb = AccentPresets.DefaultSeed.toArgb()
        val tonalArgb = ColorSchemes.fromSeed(
            seedArgb = seedArgb,
            dark = true,
            style = ColorStyle.TonalSpot,
            spec = ColorSpecVersion.V2025,
        ).secondaryContainer.toArgb()
        val expressiveArgb = ColorSchemes.fromSeed(
            seedArgb = seedArgb,
            dark = true,
            style = ColorStyle.Expressive,
            spec = ColorSpecVersion.V2025,
        ).secondaryContainer.toArgb()
        val paletteGap = rgbManhattanDistance(tonalArgb, expressiveArgb)
        assertTrue(
            paletteGap > 30,
            "TonalSpot 与 Expressive 的 secondaryContainer 必须存在足够色差（当前 gap=$paletteGap）",
        )

        rule.setContent {
            HomeDarkPreview()
        }
        rule.waitForIdle()

        val bitmap = rule.onNodeWithTag(TAG_WORKSPACE_ACTION).captureToImage().asAndroidBitmap()
        var tonalHits = 0
        var expressiveHits = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (AndroidColor.alpha(pixel) < 250) continue
                if (withinChannelTolerance(pixel, tonalArgb, tolerance = 3)) tonalHits++
                if (withinChannelTolerance(pixel, expressiveArgb, tolerance = 3)) expressiveHits++
            }
        }
        assertTrue(
            tonalHits > 500 && expressiveHits == 0,
            "HomeDarkPreview 应使用 ColorStyle.TonalSpot 配色（tonalHits=$tonalHits, expressiveHits=$expressiveHits）",
        )
    }

    private fun rgbManhattanDistance(a: Int, b: Int): Int =
        abs(AndroidColor.red(a) - AndroidColor.red(b)) +
            abs(AndroidColor.green(a) - AndroidColor.green(b)) +
            abs(AndroidColor.blue(a) - AndroidColor.blue(b))

    private fun withinChannelTolerance(a: Int, b: Int, tolerance: Int): Boolean =
        abs(AndroidColor.red(a) - AndroidColor.red(b)) <= tolerance &&
            abs(AndroidColor.green(a) - AndroidColor.green(b)) <= tolerance &&
            abs(AndroidColor.blue(a) - AndroidColor.blue(b)) <= tolerance
}
