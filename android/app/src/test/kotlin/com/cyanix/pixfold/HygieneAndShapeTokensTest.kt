package com.cyanix.pixfold

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.cyanix.pixfold.domain.comic.BatchApplyResult
import com.cyanix.pixfold.domain.comic.ComicInfoData
import com.cyanix.pixfold.domain.comic.ComicPreview
import com.cyanix.pixfold.domain.comic.PackSummary
import com.cyanix.pixfold.domain.comic.RenumberedPage
import com.cyanix.pixfold.domain.comic.VolumeGroup
import com.cyanix.pixfold.domain.comic.groupVolumes
import com.cyanix.pixfold.domain.comic.previewOf
import com.cyanix.pixfold.domain.mock.MockDataB
import com.cyanix.pixfold.domain.model.LangChoice
import com.cyanix.pixfold.domain.settings.AccentChoice
import com.cyanix.pixfold.domain.settings.PixFoldSettings
import com.cyanix.pixfold.domain.settings.ThemeMode
import com.cyanix.pixfold.ui.components.PixFoldConfirmDialog
import com.cyanix.pixfold.ui.components.SingleSelectChipRow
import com.cyanix.pixfold.ui.components.TAG_PIXFOLD_DIALOG
import com.cyanix.pixfold.ui.theme.PixFoldTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P2 #9 工程卫生、M3E 形状语义与稳定性配置收紧守卫（基于 `junit4.v2`）：
 *
 * 1. `PixFoldTheme`（M3E `MaterialExpressiveTheme`）默认 `MaterialTheme.shapes` 保持
 *    `small = 8.dp` / `medium = 12.dp` / `extraLarge = 28.dp`，替换硬编码圆角后视觉一致；
 * 2. `SingleSelectChipRow.kt`、`TwoLevelChipRows.kt`、`PixFoldDialog.kt` 均使用
 *    `MaterialTheme.shapes`，无硬编码 `RoundedCornerShape(...)`；
 * 3. `compose_stability.conf` 明确枚举 `domain.comic` 不可变值类，不包含 `domain.comic.**`
 *    通配符，也不标任何 `fun interface` 端口或可变类型；
 * 4. 显式枚举的 `domain.comic` 值类在 Compose 中仍可按结构相等跳过重组；
 * 5. 已删死代码 `AndroidThumbnailSource.kt` 与 `src/debug/nav3spike/` 不回归；
 * 6. 组件在自定义 `MaterialTheme.shapes` 下渲染正常。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w361dp-h794dp-560dpi")
class HygieneAndShapeTokensTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `PixFoldTheme default M3E shapes match 8dp 12dp 28dp visual contract`() {
        var shapes: Shapes? = null
        rule.setContent {
            PixFoldTheme(
                settings = PixFoldSettings(themeMode = ThemeMode.Light, accent = AccentChoice.Default),
            ) {
                shapes = MaterialTheme.shapes
            }
        }
        rule.waitForIdle()
        val resolved = requireNotNull(shapes)
        assertEquals(RoundedCornerShape(8.dp), resolved.small, "M3E shapes.small 应为 8.dp")
        assertEquals(RoundedCornerShape(12.dp), resolved.medium, "M3E shapes.medium 应为 12.dp")
        assertEquals(RoundedCornerShape(28.dp), resolved.extraLarge, "M3E shapes.extraLarge 应为 28.dp")
    }

    @Test
    fun `chip rows and dialog use MaterialTheme shapes without hardcoded RoundedCornerShape`() {
        val targets = listOf(
            "src/main/kotlin/com/cyanix/pixfold/ui/components/SingleSelectChipRow.kt" to
                listOf("MaterialTheme.shapes.medium", "MaterialTheme.shapes.small"),
            "src/main/kotlin/com/cyanix/pixfold/ui/components/TwoLevelChipRows.kt" to
                listOf("MaterialTheme.shapes.medium"),
            "src/main/kotlin/com/cyanix/pixfold/ui/components/PixFoldDialog.kt" to
                listOf("MaterialTheme.shapes.extraLarge"),
        )

        targets.forEach { (relativePath, expectedTokens) ->
            val file = appFile(relativePath)
            assertTrue(file.exists(), "源码文件不存在: ${file.path}")
            val codeLines = nonCommentLines(file.readText())
            val joined = codeLines.joinToString("\n")

            assertFalse(
                joined.contains("RoundedCornerShape("),
                "$relativePath 不应硬编码 RoundedCornerShape(...)，应使用 MaterialTheme.shapes",
            )
            assertFalse(
                joined.contains("import androidx.compose.foundation.shape.RoundedCornerShape"),
                "$relativePath 不应保留未使用的 RoundedCornerShape import",
            )
            expectedTokens.forEach { token ->
                assertTrue(
                    joined.contains(token),
                    "$relativePath 应使用语义形状 token: $token",
                )
            }
        }
    }

    @Test
    fun `compose_stability conf explicitly lists domain comic value classes and excludes ports and mutable types`() {
        val conf = appFile("compose_stability.conf")
        assertTrue(conf.exists(), "缺少稳定性配置文件: ${conf.path}")

        val entries = conf.readLines()
            .map { it.substringBefore("//").trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        assertFalse(
            "com.cyanix.pixfold.domain.comic.**" in entries,
            "compose_stability.conf 不得使用 domain.comic.** 宽通配符（会误匹配 3 个 fun interface 端口）",
        )

        val requiredComicTypes = listOf(
            "com.cyanix.pixfold.domain.comic.VolumeGroup",
            "com.cyanix.pixfold.domain.comic.BatchApplyResult",
            "com.cyanix.pixfold.domain.comic.PackSummary",
            "com.cyanix.pixfold.domain.comic.RenumberedPage",
            "com.cyanix.pixfold.domain.comic.ComicPreview",
            "com.cyanix.pixfold.domain.comic.ComicInfoData",
        )
        requiredComicTypes.forEach { fqcn ->
            assertTrue(fqcn in entries, "compose_stability.conf 应显式列出不可变值类: $fqcn")
        }

        val forbiddenTypes = listOf(
            "com.cyanix.pixfold.domain.comic.OutputExistsProbe",
            "com.cyanix.pixfold.domain.comic.ExistingComicInfoProbe",
            "com.cyanix.pixfold.domain.comic.PageBytes",
            "com.cyanix.pixfold.domain.task.CancellationToken",
            "com.cyanix.pixfold.domain.settings.SettingsStore",
            "com.cyanix.pixfold.domain.settings.InMemorySettingsStore",
            "com.cyanix.pixfold.domain.pack.CbzEntry",
            "com.cyanix.pixfold.domain.pack.CbzMetaUpdater",
            "com.cyanix.pixfold.domain.pack.NotAZipException",
            "com.cyanix.pixfold.domain.plan.RenameActions",
            "com.cyanix.pixfold.domain.plan.UndoActions",
            "com.cyanix.pixfold.domain.source.WorkspaceSource",
            "com.cyanix.pixfold.domain.source.AndroidGrantReleaser",
        )
        forbiddenTypes.forEach { fqcn ->
            assertFalse(
                matchesAnyStabilityRule(fqcn, entries),
                "compose_stability.conf 不得匹配端口/可变/异常类型: $fqcn",
            )
        }
    }

    @Test
    fun `domain comic explicit immutable value classes skip recomposition on equal distinct instances`() {
        var tick by mutableIntStateOf(0)
        val compositions = AtomicInteger(0)
        val volumes = MockDataB.library.volumes
        val baseGroup = groupVolumes(volumes).first()
        val basePreview = previewOf(volumes.first())
        val basePage = basePreview.pages.first()
        val baseInfo = ComicInfoData(
            title = "Title",
            series = "Series",
            number = 1,
            writer = "Author",
            language = LangChoice.Zh,
        )
        val baseSummary = PackSummary(volumeCount = 2, pageCount = 20, emptyVolumeCount = 0)
        val baseBatch = BatchApplyResult(volumes = volumes, appliedCount = 1, skippedCount = 0)

        rule.setContent {
            val currentTick = tick
            Text("tick-$currentTick")
            DomainComicStabilityProbe(
                group = baseGroup.copy(),
                preview = basePreview.copy(),
                page = basePage.copy(),
                info = baseInfo.copy(),
                summary = baseSummary.copy(),
                batch = baseBatch.copy(),
                onComposed = { compositions.incrementAndGet() },
            )
        }
        rule.waitForIdle()
        assertEquals(1, compositions.get())

        tick = 1
        rule.waitForIdle()
        tick = 2
        rule.waitForIdle()

        assertEquals(
            1,
            compositions.get(),
            "domain.comic 显式声明的不可变值类应按结构相等跳过重组，实际重组 ${compositions.get()} 次",
        )
    }

    @Test
    fun `dead code AndroidThumbnailSource and debug nav3spike remain removed`() {
        assertFalse(
            appFile("src/main/kotlin/com/cyanix/pixfold/data/saf/AndroidThumbnailSource.kt").exists(),
            "死代码 AndroidThumbnailSource.kt 应保持删除",
        )
        assertFalse(
            appFile("src/debug/kotlin/com/cyanix/pixfold/nav3spike/Nav3SpikeActivity.kt").exists(),
            "残留 spike Nav3SpikeActivity.kt 应保持删除",
        )
        assertFalse(
            appFile("src/debug/AndroidManifest.xml").exists(),
            "残留 debug AndroidManifest.xml 应保持删除",
        )
    }

    @Test
    fun `SingleSelectChipRow and PixFoldConfirmDialog render cleanly with MaterialTheme shapes`() {
        val items = (1..12).map { "item-$it" }
        rule.setContent {
            PixFoldTheme(
                settings = PixFoldSettings(themeMode = ThemeMode.Light, accent = AccentChoice.Default),
            ) {
                SingleSelectChipRow(
                    items = items,
                    activeId = items.first(),
                    idOf = { it },
                    onSelect = {},
                    selectorTag = "hygiene-chip-row",
                    chipTag = { "hygiene-chip-$it" },
                    allEntryTag = "hygiene-chip-all",
                    chipDescription = { it },
                    chipLabel = { it },
                )
                PixFoldConfirmDialog(
                    onDismissRequest = {},
                    title = "Confirm",
                    body = "Body",
                    confirmText = "OK",
                    onConfirm = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("hygiene-chip-row").assertIsDisplayed()
        rule.onNodeWithTag("hygiene-chip-all").assertIsDisplayed()
        rule.onNodeWithTag(TAG_PIXFOLD_DIALOG).assertIsDisplayed()
    }

    private fun appFile(relative: String): File {
        val direct = File(relative)
        if (direct.exists()) return direct
        val fromRepo = File("android/app/$relative")
        return if (fromRepo.exists()) fromRepo else direct
    }

    private fun nonCommentLines(source: String): List<String> {
        var inBlockComment = false
        val out = mutableListOf<String>()
        for (raw in source.lines()) {
            var line = raw
            if (inBlockComment) {
                val endIdx = line.indexOf("*/")
                if (endIdx < 0) continue
                line = line.substring(endIdx + 2)
                inBlockComment = false
            }
            while (true) {
                val startIdx = line.indexOf("/*")
                if (startIdx < 0) break
                val endIdx = line.indexOf("*/", startIdx + 2)
                if (endIdx < 0) {
                    line = line.substring(0, startIdx)
                    inBlockComment = true
                    break
                }
                line = line.substring(0, startIdx) + line.substring(endIdx + 2)
            }
            val code = line.substringBefore("//").trim()
            if (code.isNotEmpty()) out += code
        }
        return out
    }

    private fun matchesAnyStabilityRule(fqcn: String, rules: Set<String>): Boolean =
        rules.any { rule ->
            when {
                rule == fqcn -> true
                rule.endsWith(".**") -> {
                    val prefix = rule.removeSuffix("**")
                    fqcn.startsWith(prefix)
                }
                rule.endsWith(".*") -> {
                    val prefix = rule.removeSuffix("*")
                    fqcn.startsWith(prefix) && !fqcn.removePrefix(prefix).contains('.')
                }
                else -> false
            }
        }
}

@Composable
private fun DomainComicStabilityProbe(
    group: VolumeGroup,
    preview: ComicPreview,
    page: RenumberedPage,
    info: ComicInfoData,
    summary: PackSummary,
    batch: BatchApplyResult,
    onComposed: () -> Unit,
) {
    SideEffect { onComposed() }
    Text("${group.key}-${preview.consistent}-${page.fileName}-${info.title}-${summary.volumeCount}-${batch.appliedCount}")
}
