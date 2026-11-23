package com.cyanix.pixfold.data.task

import com.cyanix.pixfold.domain.task.TaskProgress
import org.junit.After
import org.junit.Test
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `PackTaskBus` 与 `PackTaskService` 跨线程并发安全性单测（P0 #2）。
 *
 * 钉住两条真实竞态防线：
 * 1. `publishPage` / `publish`（Dispatchers.IO）与 `requestCancel`（Main）并发交错时，
 *    绝不能把已经置位的 `cancelling = true` 覆盖回 `false`；
 * 2. `PackTaskService.snapshot` 必须声明为 `@Volatile`，保证 IO 回调写入对 Main `onTimeout()` 可见。
 */
class PackTaskBusConcurrencyTest {

    @After
    fun tearDown() {
        PackTaskBus.resetForTest()
    }

    private fun sampleRequest() = PackTaskRequest(
        treeUri = "content://test/tree/root",
        rootDisplayName = "root",
        plans = emptyList(),
        workspaceName = "root",
    )

    @Test
    fun `concurrent publishPage and requestCancel never loses cancelling flag`() {
        val pool = Executors.newFixedThreadPool(2)
        val lostCancelCount = AtomicInteger(0)

        try {
            repeat(250) {
                PackTaskBus.resetForTest()
                assertTrue(PackTaskBus.requestStart(sampleRequest()))

                val publisherStarted = CountDownLatch(1)
                val cancelIssued = AtomicBoolean(false)

                val pageWriter = pool.submit {
                    publisherStarted.countDown()
                    for (page in 1..600) {
                        PackTaskBus.publishPage(page, 600)
                        if (cancelIssued.get()) {
                            val current = PackTaskBus.state.value as? PackTaskState.Running
                            if (current != null && !current.cancelling) {
                                lostCancelCount.incrementAndGet()
                            }
                        }
                    }
                }
                val canceller = pool.submit {
                    publisherStarted.await(5, TimeUnit.SECONDS)
                    Thread.yield()
                    PackTaskBus.requestCancel()
                    cancelIssued.set(true)
                }

                pageWriter.get(5, TimeUnit.SECONDS)
                canceller.get(5, TimeUnit.SECONDS)

                val finalState = PackTaskBus.state.value as? PackTaskState.Running
                assertTrue(finalState != null, "终态仍应处于 Running（尚未 settle）")
                if (!finalState.cancelling) {
                    lostCancelCount.incrementAndGet()
                }
            }
        } finally {
            pool.shutdownNow()
        }

        assertTrue(
            lostCancelCount.get() == 0,
            "并发 publishPage 将 cancelling=true 覆盖回 false 共 ${lostCancelCount.get()} 次",
        )
    }

    @Test
    fun `publish after requestCancel preserves cancelling true`() {
        assertTrue(PackTaskBus.requestStart(sampleRequest()))
        PackTaskBus.requestCancel()

        // 模拟 IO 线程在取消之前刚读到 token.isCancelled == false，随后才调 publish
        PackTaskBus.publish(
            progress = TaskProgress(total = 4, done = 1),
            cancelling = false,
        )

        val current = PackTaskBus.state.value as PackTaskState.Running
        assertTrue(current.cancelling, "已取消后迟到的 publish 不得把 cancelling 重置为 false")
    }

    @Test
    fun `publishPage preserves cancelling when already cancelling`() {
        assertTrue(PackTaskBus.requestStart(sampleRequest()))
        PackTaskBus.requestCancel()
        PackTaskBus.publishPage(pageDone = 42, pageTotal = 100)

        val current = PackTaskBus.state.value as PackTaskState.Running
        assertTrue(current.cancelling, "publishPage 不得覆盖已置位的 cancelling")
        assertTrue(current.pageDone == 42 && current.pageTotal == 100)
    }

    @Test
    fun `PackTaskService snapshot field is volatile`() {
        val field = PackTaskService::class.java.getDeclaredField("snapshot")
        assertTrue(
            Modifier.isVolatile(field.modifiers),
            "PackTaskService.snapshot 在 IO 线程写入、主线程 onTimeout() 读取，必须标注 @Volatile",
        )
        assertFalse(Modifier.isFinal(field.modifiers))
    }
}
