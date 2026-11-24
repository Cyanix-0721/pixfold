package com.cyanix.pixfold.ui.library

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cyanix.pixfold.PixFoldApp
import com.cyanix.pixfold.R
import com.cyanix.pixfold.ReadyWorkspaceSource
import com.cyanix.pixfold.TestText
import com.cyanix.pixfold.domain.comic.ComicInfoData
import com.cyanix.pixfold.domain.comic.ExistingComicInfoProbe
import com.cyanix.pixfold.domain.comic.OutputExistsProbe
import com.cyanix.pixfold.domain.model.LangChoice
import com.cyanix.pixfold.ui.organize.TAG_SORT_PAGE
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `PixFoldApp` 工作流 B 卷探测懒加载与异步离主线程的端到端 Compose/Robolectric 验证（P0 #1）。
 *
 * 钉住两条核心契约：
 * 1. 停留在首页或进入「整理图片」时，绝不触发 `outputExistsProbe` / `existingComicInfoProbe`；
 * 2. 只有进入「打包漫画」（`LibraryRoute`）后才在注入的 IO 调度器（非主线程）上运行探测，
 *    并把 `outputExists` 冲突提示与 `ComicInfo.xml` 元数据稳定渲染到界面。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w361dp-h794dp-560dpi")
class LibraryVolumeProbingUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val organizeTitle get() = TestText.of(R.string.home_workflow_a)
    private val libraryTitle get() = TestText.of(R.string.home_workflow_b)
    private val conflictLabel get() = TestText.of(R.string.lib_output_conflict)

    @Test
    fun `probes are NOT invoked while staying on Home or OrganizeRoute`() {
        val existsCalls = AtomicInteger(0)
        val comicInfoCalls = AtomicInteger(0)
        val ioExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "ui-probe-io") }

        try {
            rule.setContent {
                PixFoldApp(
                    workspaceSource = ReadyWorkspaceSource(),
                    outputExistsProbe = OutputExistsProbe {
                        existsCalls.incrementAndGet()
                        true
                    },
                    existingComicInfoProbe = ExistingComicInfoProbe {
                        comicInfoCalls.incrementAndGet()
                        ComicInfoData(title = "不应在首页被读取")
                    },
                    probeDispatcher = ioExecutor.asCoroutineDispatcher(),
                )
            }
            rule.waitForIdle()

            assertEquals(
                0,
                existsCalls.get(),
                "停留在首页时不得执行 outputExistsProbe",
            )
            assertEquals(
                0,
                comicInfoCalls.get(),
                "停留在首页时不得执行 existingComicInfoProbe",
            )

            // 进入工作流 A（整理图片）同样不得触发工作流 B 的 CBZ/ComicInfo 探测
            rule.onNodeWithText(organizeTitle).performClick()
            rule.waitForIdle()
            rule.onNodeWithTag(TAG_SORT_PAGE).assertIsDisplayed()

            assertEquals(
                0,
                existsCalls.get(),
                "进入整理图片（OrganizeRoute）时不得执行 outputExistsProbe",
            )
            assertEquals(
                0,
                comicInfoCalls.get(),
                "进入整理图片（OrganizeRoute）时不得执行 existingComicInfoProbe",
            )
        } finally {
            ioExecutor.shutdownNow()
        }
    }

    @Test
    fun `entering LibraryRoute runs probes off-main on injected IO dispatcher and updates UI`() {
        val existsCalls = AtomicInteger(0)
        val comicInfoCalls = AtomicInteger(0)
        val probeThreads = Collections.synchronizedList(mutableListOf<String>())
        val ranOnMain = Collections.synchronizedList(mutableListOf<Boolean>())
        val ioExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "ui-probe-io") }
        val uniqueTitle = "探针读取的独特标题"

        try {
            rule.setContent {
                PixFoldApp(
                    workspaceSource = ReadyWorkspaceSource(),
                    outputExistsProbe = OutputExistsProbe {
                        existsCalls.incrementAndGet()
                        probeThreads += Thread.currentThread().name
                        ranOnMain += Looper.getMainLooper().isCurrentThread
                        true
                    },
                    existingComicInfoProbe = ExistingComicInfoProbe {
                        comicInfoCalls.incrementAndGet()
                        probeThreads += Thread.currentThread().name
                        ranOnMain += Looper.getMainLooper().isCurrentThread
                        ComicInfoData(
                            title = uniqueTitle,
                            series = "探针系列",
                            number = 7,
                            writer = "探针作者",
                            language = LangChoice.Zh,
                        )
                    },
                    probeDispatcher = ioExecutor.asCoroutineDispatcher(),
                )
            }
            rule.waitForIdle()

            // 进入前仍为 0
            assertEquals(0, existsCalls.get())
            assertEquals(0, comicInfoCalls.get())

            // 进入打包漫画（LibraryRoute）
            rule.onNodeWithText(libraryTitle).performClick()
            rule.waitUntil(timeoutMillis = 5_000) {
                rule.onAllNodesWithText(uniqueTitle, substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }

            assertTrue(existsCalls.get() > 0, "进入 LibraryRoute 后应执行 outputExistsProbe")
            assertTrue(comicInfoCalls.get() > 0, "进入 LibraryRoute 后应执行 existingComicInfoProbe")
            assertTrue(
                probeThreads.isNotEmpty() && probeThreads.all { it.startsWith("ui-probe-io") },
                "所有探测都必须在注入的 IO 调度器线程运行，实际线程=$probeThreads",
            )
            assertFalse(
                ranOnMain.any { it },
                "探测绝不能在 Android 主线程执行",
            )

            // 验证探测结果（existingComicInfo 标题 + outputExists 冲突提示）都已渲染到 UI
            assertTrue(
                rule.onAllNodesWithText(uniqueTitle, substring = true)
                    .fetchSemanticsNodes().isNotEmpty(),
                "ComicInfo.xml 读出的标题应展示在库页",
            )
            assertTrue(
                rule.onAllNodesWithText(conflictLabel, substring = true)
                    .fetchSemanticsNodes().isNotEmpty(),
                "outputExists = true 的同名冲突提示应展示在库页",
            )
        } finally {
            ioExecutor.shutdownNow()
        }
    }
}
