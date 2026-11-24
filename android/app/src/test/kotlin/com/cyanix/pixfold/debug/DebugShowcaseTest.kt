package com.cyanix.pixfold.debug

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.cyanix.pixfold.domain.settings.AccentChoice
import com.cyanix.pixfold.domain.settings.ColorSpecVersion
import com.cyanix.pixfold.domain.settings.PixFoldSettings
import com.cyanix.pixfold.domain.settings.ThemeMode
import com.cyanix.pixfold.ui.PixFoldUiTestDefaults
import com.cyanix.pixfold.ui.assertAccessibleTargets
import com.cyanix.pixfold.ui.assertAllNodesFitViewport
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
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
}
