package com.cyanix.pixfold.ui.library

import com.cyanix.pixfold.domain.comic.ComicInfoData
import com.cyanix.pixfold.domain.comic.applyExistingComicInfo
import com.cyanix.pixfold.domain.comic.comicVolumeOf
import com.cyanix.pixfold.domain.comic.markOutputExists
import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.model.LangChoice
import com.cyanix.pixfold.domain.model.MetaField
import com.cyanix.pixfold.domain.model.Suggestion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [LibraryVolumeLoader] 纯 JVM 协程状态机单测：
 * - `syncBase` 同步无阻塞更新基础卷，不触发探测；
 * - `requestProbe` 在注入的 `ioDispatcher` 上异步执行并落入 `outputExists` + `existingComicInfo`；
 * - 集合变化 / 切换工作区后的过期（或乱序）探测结果不得覆盖新卷；
 * - 用户已有编辑在探测结果回流时通过 `reconcileLibrary` 保留。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryVolumeLoaderTest {

    private fun TestScope.unconfinedScope(): CoroutineScope =
        CoroutineScope(UnconfinedTestDispatcher(testScheduler))

    private fun baseVolume(
        id: String,
        title: String = "目录-$id",
        outputExists: Boolean = false,
    ): ComicVolume = comicVolumeOf(
        id = id,
        dirPath = id,
        pages = emptyList(),
        titleSug = Suggestion(title, "目录"),
        seriesSug = Suggestion("系列", "目录"),
        writerSug = Suggestion("目录作者", "目录"),
        volumeSug = Suggestion<Int?>(1, "目录"),
        langSug = Suggestion<String>(null, ""),
        outputDirSug = "系列",
        outputExists = outputExists,
        cbzBaseSug = Suggestion(id, "目录"),
        seriesDir = "系列",
    )

    @Test
    fun `syncBase updates base volumes without invoking any probe`() = runTest {
        val loader = LibraryVolumeLoader(
            scope = unconfinedScope(),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val v1 = listOf(baseVolume("v1"))
        loader.syncBase(workspaceKey = "ws-a", rootPath = "漫画", baseVolumes = v1)

        assertEquals(v1, loader.volumes)
        assertFalse(loader.isProbing)
    }

    @Test
    fun `requestProbe runs on injected IO dispatcher and applies outputExists and comicInfo`() = runTest {
        val ioExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "loader-test-io") }
        val ioDispatcher: CoroutineDispatcher = ioExecutor.asCoroutineDispatcher()
        val callerThread = Thread.currentThread().name
        var observedThread: String? = null
        val done = CompletableDeferred<Unit>()

        try {
            val loader = LibraryVolumeLoader(
                scope = unconfinedScope(),
                ioDispatcher = ioDispatcher,
            )
            val base = listOf(baseVolume("v1"))
            loader.syncBase("ws-a", "漫画", base)

            loader.requestProbe(
                workspaceKey = "ws-a",
                rootPath = "漫画",
                baseVolumes = base,
            ) { vols ->
                observedThread = Thread.currentThread().name
                val marked = markOutputExists(vols) { true }
                val applied = applyExistingComicInfo(marked) {
                    ComicInfoData(
                        title = "XML标题",
                        series = "XML系列",
                        number = 9,
                        writer = "XML作者",
                        language = LangChoice.Zh,
                    )
                }
                done.complete(Unit)
                applied
            }

            done.await()
            // 等待协程把结果写回主状态
            val deadline = System.currentTimeMillis() + 3_000
            while (loader.isProbing && System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
            }

            assertTrue(
                observedThread?.startsWith("loader-test-io") == true,
                "探测必须在注入的 IO 线程执行，实际线程=$observedThread",
            )
            assertNotEquals(callerThread, observedThread)
            val result = loader.volumes.single()
            assertTrue(result.outputExists)
            assertEquals("XML标题", result.title)
            assertEquals("XML系列", result.series)
            assertEquals(9, result.volume)
            assertEquals("XML作者", result.writer)
            assertEquals(LangChoice.Zh, result.language)
        } finally {
            ioExecutor.shutdownNow()
        }
    }

    @Test
    fun `stale probe after collections change does not clobber newer volumes`() = runTest {
        val standard = StandardTestDispatcher(testScheduler)
        val loader = LibraryVolumeLoader(
            scope = CoroutineScope(standard),
            ioDispatcher = standard,
        )
        val baseV1 = listOf(baseVolume("v1", title = "旧目录"))
        val baseV2 = listOf(baseVolume("v2", title = "新目录"))

        val gate1 = CompletableDeferred<Unit>()
        loader.syncBase("ws-a", "漫画", baseV1)
        loader.requestProbe("ws-a", "漫画", baseV1) { vols ->
            // 模拟阻塞/不可立即中断的慢 IO，完成后返回过期结果
            gate1.await()
            vols.map { it.copy(title = "过期探测结果", outputExists = true) }
        }
        // 让探针 1 启动并挂在 gate1
        testScheduler.runCurrent()

        // 在探针 1 尚未完成前，工作区集合变化为 v2
        loader.syncBase("ws-a", "漫画", baseV2)
        loader.requestProbe("ws-a", "漫画", baseV2) { vols ->
            vols.map { it.copy(title = "新探测结果", outputExists = false) }
        }
        advanceUntilIdle()
        assertEquals(listOf("v2"), loader.volumes.map { it.id })
        assertEquals("新探测结果", loader.volumes.single().title)

        // 此时让过期的探针 1 才完成 —— 绝不能覆盖新卷
        gate1.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("v2"), loader.volumes.map { it.id })
        assertEquals("新探测结果", loader.volumes.single().title)
        assertFalse(loader.volumes.single().outputExists)
        assertFalse(loader.isProbing)
    }

    @Test
    fun `stale probe after workspace switch does not clobber new workspace volumes`() = runTest {
        val standard = StandardTestDispatcher(testScheduler)
        val loader = LibraryVolumeLoader(
            scope = CoroutineScope(standard),
            ioDispatcher = standard,
        )
        val wsAVolumes = listOf(baseVolume("a1", title = "工作区A卷"))
        val wsBVolumes = listOf(baseVolume("b1", title = "工作区B卷"))
        val gateA = CompletableDeferred<Unit>()

        loader.syncBase("ws-a", "A", wsAVolumes)
        loader.requestProbe("ws-a", "A", wsAVolumes) { vols ->
            gateA.await()
            vols.map { it.copy(title = "A的过期探测", outputExists = true) }
        }
        testScheduler.runCurrent()

        // 切到工作区 B
        loader.syncBase("ws-b", "B", wsBVolumes)
        assertEquals(wsBVolumes, loader.volumes)

        // 旧工作区 A 的探针稍后返回，不得污染工作区 B
        gateA.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("b1"), loader.volumes.map { it.id })
        assertEquals("工作区B卷", loader.volumes.single().title)
        assertFalse(loader.volumes.single().outputExists)
        assertFalse(loader.isProbing)
    }

    @Test
    fun `out-of-order completion on non-cancellable blocking thread does not clobber newer probe`() = runTest {
        val pool = Executors.newFixedThreadPool(2)
        val ioDispatcher = pool.asCoroutineDispatcher()
        val started1 = CompletableDeferred<Unit>()
        val release1 = java.util.concurrent.CountDownLatch(1)
        val finished1 = java.util.concurrent.CountDownLatch(1)
        val finished2 = CompletableDeferred<Unit>()

        try {
            val loader = LibraryVolumeLoader(
                scope = unconfinedScope(),
                ioDispatcher = ioDispatcher,
            )
            val base1 = listOf(baseVolume("v1", title = "一"))
            val base2 = listOf(baseVolume("v1", title = "二"))

            loader.syncBase("ws-a", "漫画", base1)
            loader.requestProbe("ws-a", "漫画", base1) { vols ->
                started1.complete(Unit)
                // 真实阻塞 I/O 不响应协程取消，直到 release1 放行
                release1.await()
                finished1.countDown()
                vols.map { it.copy(title = "乱序过期结果", outputExists = true) }
            }
            started1.await()

            // 新集合到达并立刻完成探针 2
            loader.syncBase("ws-a", "漫画", base2)
            loader.requestProbe("ws-a", "漫画", base2) { vols ->
                val out = vols.map { it.copy(title = "最新结果", outputExists = false) }
                finished2.complete(Unit)
                out
            }
            finished2.await()
            val deadline = System.currentTimeMillis() + 3_000
            while (loader.isProbing && System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
            }
            assertEquals("最新结果", loader.volumes.single().title)

            // 放行阻塞中的探针 1，确认其返回不会覆写探针 2 的结果
            release1.countDown()
            assertTrue(finished1.await(3, java.util.concurrent.TimeUnit.SECONDS))
            Thread.sleep(50)

            assertEquals("最新结果", loader.volumes.single().title)
            assertFalse(loader.volumes.single().outputExists)
        } finally {
            release1.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `probed result preserves prior user edits via reconcileLibrary`() = runTest {
        val loader = LibraryVolumeLoader(
            scope = unconfinedScope(),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val base = listOf(baseVolume("v1", title = "目录默认"))
        loader.syncBase("ws-a", "漫画", base)

        // 模拟用户在探测完成前已手工改过标题
        loader.applyUserEdit(
            listOf(
                base.single().copy(
                    title = "用户手改标题",
                    overridden = setOf(MetaField.Title),
                ),
            ),
        )

        loader.requestProbe("ws-a", "漫画", base) { vols ->
            applyExistingComicInfo(
                volumes = markOutputExists(vols) { true },
                probe = {
                    ComicInfoData(
                        title = "文件里旧标题",
                        series = "文件系列",
                        writer = "文件作者",
                        number = 5,
                        language = LangChoice.Zh,
                    )
                },
            )
        }

        val merged = loader.volumes.single()
        assertEquals("用户手改标题", merged.title)
        assertTrue(merged.outputExists)
        assertEquals("文件作者", merged.writer)
        assertEquals(LangChoice.Zh, merged.language)
    }

    @Test
    fun `same snapshot is not re-probed twice while active, but re-probes after cancelProbe`() = runTest {
        var calls = 0
        val loader = LibraryVolumeLoader(
            scope = unconfinedScope(),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val base = listOf(baseVolume("v1"))
        loader.syncBase("ws-a", "漫画", base)

        loader.requestProbe("ws-a", "漫画", base) { vols ->
            calls++
            vols
        }
        loader.requestProbe("ws-a", "漫画", base) { vols ->
            calls++
            vols
        }
        assertEquals(1, calls, "同一快照已完成探测，重组再次触发不得重复查询")

        loader.cancelProbe()
        loader.requestProbe("ws-a", "漫画", base) { vols ->
            calls++
            vols
        }
        assertEquals(2, calls, "离开 LibraryRoute 清理后重新进入应重新探测最新磁盘状态")
    }
}
