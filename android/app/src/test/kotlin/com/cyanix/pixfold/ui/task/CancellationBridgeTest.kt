package com.cyanix.pixfold.ui.task

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cyanix.pixfold.R
import com.cyanix.pixfold.TestText
import com.cyanix.pixfold.domain.mock.MockDataB
import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.domain.model.NameProposal
import com.cyanix.pixfold.domain.model.SourceItem
import com.cyanix.pixfold.domain.naming.OpStatus
import com.cyanix.pixfold.domain.naming.RenameOp
import com.cyanix.pixfold.domain.pack.CbzMetaUpdateOutcome
import com.cyanix.pixfold.domain.pack.CbzMetaUpdatePlan
import com.cyanix.pixfold.domain.pack.CbzMetaUpdater
import com.cyanix.pixfold.domain.pack.UpdateFailure
import com.cyanix.pixfold.domain.pack.UpdatePreflight
import com.cyanix.pixfold.domain.plan.RenameActions
import com.cyanix.pixfold.domain.plan.executeRenamePlanReal
import com.cyanix.pixfold.domain.source.SERIES_PREFIX
import com.cyanix.pixfold.domain.task.CancellationToken
import com.cyanix.pixfold.ui.library.LibrarySteps
import com.cyanix.pixfold.ui.library.TAG_B_STEP_METADATA
import com.cyanix.pixfold.ui.library.TAG_METADATA_UPDATE
import com.cyanix.pixfold.ui.library.TAG_UPDATE_CONFIRM_DIALOG
import com.cyanix.pixfold.ui.library.volumeChipTag
import com.cyanix.pixfold.ui.library.volumeGroupTag
import com.cyanix.pixfold.ui.organize.OrganizeSteps
import com.cyanix.pixfold.ui.organize.TAG_A_PLAN_CONFIRM
import com.cyanix.pixfold.ui.organize.TAG_STEP_PLAN
import com.cyanix.pixfold.ui.plan.TAG_CONFIRM_OK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P1 #7 协程取消 → [CancellationToken] 桥接测试。
 *
 * 覆盖：
 * 1. `withCancellationToken` 在阻塞 IO 期间被协程取消时立刻触发 `token.cancel()`；
 * 2. 正常完成时不误触 `token.cancel()`；
 * 3. `LibrarySteps`（工作流 C）与 `OrganizeSteps`（批量改名）在组合作用域离场取消时，
 *    底层阻塞循环立刻收到 `shouldStop() == true` / `token.isCancelled == true`，不会孤儿写盘。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h2000dp")
class CancellationBridgeTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `cancelling coroutine running blocking IO loop cancels CancellationToken immediately`() = runBlocking {
        val token = CancellationToken()
        val enteredIo = CountDownLatch(1)
        val sawCancel = CountDownLatch(1)
        val iterations = AtomicInteger(0)

        val job = launch(Dispatchers.Default) {
            withCancellationToken(token) {
                withContext(Dispatchers.IO) {
                    enteredIo.countDown()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                    while (!token.isCancelled && System.nanoTime() < deadline) {
                        iterations.incrementAndGet()
                        Thread.sleep(5)
                    }
                    if (token.isCancelled) sawCancel.countDown()
                }
            }
        }

        assertTrue(enteredIo.await(2, TimeUnit.SECONDS), "应进入 IO 阻塞循环")
        job.cancelAndJoin()

        assertTrue(token.isCancelled, "协程取消必须联动触发 CancellationToken.cancel()")
        assertTrue(sawCancel.await(1, TimeUnit.SECONDS), "阻塞 IO 循环应在边界看到 token.isCancelled == true")
    }

    @Test
    fun `normal completion of withCancellationToken does not cancel token`() = runBlocking {
        val token = CancellationToken()
        val result = withCancellationToken(token) {
            withContext(Dispatchers.IO) { "ok" }
        }
        assertEquals("ok", result)
        assertFalse(token.isCancelled, "正常完成不应误取消令牌")
    }

    @Test
    fun `cancelling coroutine stops executeRenamePlanReal on next item boundary`() = runBlocking {
        val token = CancellationToken()
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val renameCalls = AtomicInteger(0)

        val ops = (1..5).map { i ->
            val item = SourceItem(
                id = "id-$i",
                collectionId = "c1",
                dir = "",
                baseName = "old_$i",
                ext = "jpg",
                sizeBytes = 100L,
                modifiedEpochMillis = 0L,
                createdEpochMillis = null,
                seed = i,
                uri = "content://doc/$i",
            )
            RenameOp(
                proposal = NameProposal(image = item, proposedName = "new_$i.jpg"),
                status = OpStatus.Ok,
                reason = null,
            )
        }
        val actions = RenameActions { uri, _ ->
            val n = renameCalls.incrementAndGet()
            if (n == 1) {
                firstStarted.countDown()
                releaseFirst.await(2, TimeUnit.SECONDS)
            }
            "$uri#renamed"
        }

        val job = launch(Dispatchers.Default) {
            withCancellationToken(token) {
                withContext(Dispatchers.IO) {
                    executeRenamePlanReal(ops, actions, token = token)
                }
            }
        }

        assertTrue(firstStarted.await(2, TimeUnit.SECONDS), "第 1 项改名应已开始")
        job.cancel()
        // 放行正在进行的第 1 项，验证后续 4 项不再执行
        releaseFirst.countDown()
        job.join()

        assertTrue(token.isCancelled, "协程取消应立即取消改名令牌")
        assertEquals(1, renameCalls.get(), "协程取消后不得成为孤儿继续改名剩余 4 个文件")
    }

    @Test
    fun `disposing LibrarySteps during Workflow C update cancels updater shouldStop`() {
        val library = MockDataB.library
        val withArchive: ComicVolume = library.volumes.first { it.outputExists }
        val enteredUpdate = CountDownLatch(1)
        val observedStop = CountDownLatch(1)

        val blockingUpdater = object : CbzMetaUpdater {
            override fun targetExists(volume: ComicVolume): Boolean = true

            override fun preflight(plan: CbzMetaUpdatePlan, volume: ComicVolume): UpdatePreflight =
                UpdatePreflight.Ready(estimatedReadBytes = 1024L, estimatedWriteBytes = 1024L)

            override fun update(
                plan: CbzMetaUpdatePlan,
                volume: ComicVolume,
                shouldStop: () -> Boolean,
                onProgress: (done: Int, total: Int) -> Unit,
            ): CbzMetaUpdateOutcome {
                enteredUpdate.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (!shouldStop() && System.nanoTime() < deadline) {
                    Thread.sleep(5)
                }
                if (shouldStop()) {
                    observedStop.countDown()
                    return CbzMetaUpdateOutcome.Failed(UpdateFailure.Cancelled)
                }
                return CbzMetaUpdateOutcome.Updated(entryCount = 1, xmlBytes = 32)
            }
        }

        var mounted by mutableStateOf(true)
        rule.setContent {
            if (mounted) {
                LibrarySteps(
                    rootPath = library.rootPath,
                    volumes = library.volumes,
                    metaUpdater = blockingUpdater,
                )
            }
        }
        rule.onNodeWithTag(TAG_B_STEP_METADATA).performClick()
        rule.waitForIdle()
        val chip = volumeChipTag(withArchive.id)
        if (rule.onNodeWithTag(chip).let { runCatching { it.fetchSemanticsNode() }.isFailure }) {
            rule.onNodeWithTag(volumeGroupTag(SERIES_PREFIX + withArchive.series)).performClick()
            rule.waitForIdle()
        }
        rule.onNodeWithTag(chip).performClick()
        rule.waitForIdle()

        rule.onNodeWithTag(TAG_METADATA_UPDATE).performClick()
        rule.waitForIdle()
        rule.waitUntil(timeoutMillis = 3_000) {
            runCatching { rule.onNodeWithTag(TAG_UPDATE_CONFIRM_DIALOG).fetchSemanticsNode() }.isSuccess
        }
        rule.onNodeWithText(TestText.of(R.string.update_meta_confirm)).performClick()

        assertTrue(enteredUpdate.await(3, TimeUnit.SECONDS), "工作流 C 应已进入后台更新")

        // 卸载 LibrarySteps（模拟返回/配置变更销毁作用域）
        mounted = false
        rule.waitForIdle()

        assertTrue(
            observedStop.await(2, TimeUnit.SECONDS),
            "LibrarySteps 离场取消协程作用域后，工作流 C 的 shouldStop() 必须立即变为 true，防止孤儿写盘",
        )
    }

    @Test
    fun `disposing OrganizeSteps during batch rename cancels remaining SAF renames`() {
        val items = (1..5).map { i ->
            SourceItem(
                id = "item-$i",
                collectionId = "col",
                dir = "",
                baseName = "img_$i",
                ext = "jpg",
                sizeBytes = 512L,
                modifiedEpochMillis = 0L,
                createdEpochMillis = null,
                seed = i,
                uri = "content://doc/img_$i",
            )
        }
        val collections = listOf(
            ImageCollection(id = "col", name = "col", rootDirName = "col", images = items),
        )
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val calls = AtomicInteger(0)

        val blockingActions = RenameActions { uri, _ ->
            val n = calls.incrementAndGet()
            if (n == 1) {
                firstStarted.countDown()
                releaseFirst.await(3, TimeUnit.SECONDS)
            }
            "$uri#renamed"
        }

        var mounted by mutableStateOf(true)
        rule.setContent {
            if (mounted) {
                OrganizeSteps(
                    collections = collections,
                    renameActions = blockingActions,
                )
            }
        }
        rule.onNodeWithTag(TAG_STEP_PLAN).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_A_PLAN_CONFIRM).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_CONFIRM_OK).performClick()

        assertTrue(firstStarted.await(3, TimeUnit.SECONDS), "批量改名应已开始第 1 项")

        // 卸载 OrganizeSteps（取消 UI 作用域）后再放行第 1 项
        mounted = false
        rule.waitForIdle()
        releaseFirst.countDown()
        // 给 IO 线程足够时间跑完可能泄漏的循环（若未取消会立刻把剩下 4 项都跑完）
        Thread.sleep(150)

        assertEquals(
            1,
            calls.get(),
            "OrganizeSteps 离场取消协程后，批量改名必须在第 1 项边界停止，不能继续孤儿改写剩余文件",
        )
    }
}
