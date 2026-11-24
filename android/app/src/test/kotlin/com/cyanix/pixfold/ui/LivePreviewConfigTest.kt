package com.cyanix.pixfold.ui

import org.junit.AssumptionViolatedException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class LivePreviewConfigTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun default_snapshot_path_falls_back_to_baseline_directory() {
        assertEquals(
            "src/test/snapshots/home_dark.png",
            LivePreviewConfig.snapshotPath("home_dark", null),
        )
        assertEquals(
            "src/test/snapshots/home_dark.png",
            LivePreviewConfig.snapshotPath("home_dark", "   "),
        )
    }

    @Test
    fun custom_output_dir_trims_trailing_separators() {
        assertEquals(
            "build/live-preview/home_dark.png",
            LivePreviewConfig.snapshotPath("home_dark", "build/live-preview"),
        )
        assertEquals(
            "build/live-preview/home_dark.png",
            LivePreviewConfig.snapshotPath("home_dark", "build/live-preview/"),
        )
        assertEquals(
            "build/live-preview/home_dark.png",
            LivePreviewConfig.snapshotPath("home_dark", " build/live-preview\\ "),
        )
    }

    @Test
    fun without_output_override_all_methods_run_even_if_allowlist_non_empty() {
        assertTrue(
            LivePreviewConfig.shouldRunMethod(
                methodName = "anyTest",
                outDirOverride = null,
                allowedMethods = setOf("onlyOne"),
            ),
        )
        assertTrue(
            LivePreviewConfig.shouldRunMethod(
                methodName = "anyTest",
                outDirOverride = "  ",
                allowedMethods = setOf("onlyOne"),
            ),
        )
    }

    @Test
    fun with_output_override_empty_allowlist_runs_all_and_non_empty_filters_exact_method() {
        assertTrue(
            LivePreviewConfig.shouldRunMethod(
                methodName = "homeScreen_light",
                outDirOverride = "build/live-preview",
                allowedMethods = emptySet(),
            ),
        )
        assertTrue(
            LivePreviewConfig.shouldRunMethod(
                methodName = "homeScreen_light",
                outDirOverride = "build/live-preview",
                allowedMethods = setOf("homeScreen_light", "homeScreen_dark"),
            ),
        )
        assertFalse(
            LivePreviewConfig.shouldRunMethod(
                methodName = "sortPage_dark",
                outDirOverride = "build/live-preview",
                allowedMethods = setOf("homeScreen_light", "homeScreen_dark"),
            ),
        )
    }

    @Test
    fun read_method_filter_ignores_comments_and_blank_lines_and_returns_empty_when_missing() {
        assertEquals(emptySet(), LivePreviewConfig.readMethodFilter(null))

        val dir = tempFolder.newFolder("live-preview")
        assertEquals(emptySet(), LivePreviewConfig.readMethodFilter(dir.absolutePath))

        File(dir, LivePreviewConfig.METHODS_FILE_NAME).writeText(
            """
            # comment line

              homeScreen_light  
            homeScreen_dark
            homeScreen_light
            """.trimIndent(),
        )
        assertEquals(
            setOf("homeScreen_light", "homeScreen_dark"),
            LivePreviewConfig.readMethodFilter(dir.absolutePath),
        )
    }

    @Test
    fun preview_filter_rule_skips_unselected_method_before_running_statement() {
        val rule = PreviewFilterRule(
            outDirProvider = { "build/live-preview" },
            methodsProvider = { setOf("homeScreen_light") },
        )

        val skippedRan = AtomicBoolean(false)
        val skippedStatement = rule.apply(
            object : Statement() {
                override fun evaluate() {
                    skippedRan.set(true)
                }
            },
            Description.createTestDescription("VisualRegressionSnapshotTest", "sortPage_dark"),
        )
        try {
            skippedStatement.evaluate()
            fail("Expected AssumptionViolatedException for unselected method")
        } catch (_: AssumptionViolatedException) {
            // Expected skip
        }
        assertFalse(skippedRan.get(), "Skipped test statement body must not execute")

        val allowedRan = AtomicBoolean(false)
        val allowedStatement = rule.apply(
            object : Statement() {
                override fun evaluate() {
                    allowedRan.set(true)
                }
            },
            Description.createTestDescription("VisualRegressionSnapshotTest", "homeScreen_light"),
        )
        allowedStatement.evaluate()
        assertTrue(allowedRan.get(), "Allowed test statement body must execute")
    }
}
