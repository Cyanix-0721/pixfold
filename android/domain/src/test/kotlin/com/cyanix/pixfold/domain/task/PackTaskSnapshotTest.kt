package com.cyanix.pixfold.domain.task

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `PackTaskSnapshot` 与 `recoverPackTask` 的语义（D2b「切后台恢复」）。
 *
 * 这组测试的核心不是「字段存得住」，而是**恢复判定的分档**：
 * 哪些状态该打扰用户、哪些不该。分错档的代价不对称 ——
 * 该提示时没提示 ⇒ 用户自己发现目录里有个打不开的文件；
 * 不该提示时提示了 ⇒ 用户每次开机都看到一个假警报，久而久之学会忽略所有提示。
 */
class PackTaskSnapshotTest {

    private fun running(
        taskId: String = "t1",
        progress: TaskProgress = TaskProgress(total = 3, done = 1),
        targets: List<String> = listOf("a.cbz", "b.cbz"),
    ) = PackTaskSnapshot(
        taskId = taskId,
        phase = PackTaskPhase.Running,
        startedAtMillis = 1_000L,
        updatedAtMillis = 1_500L,
        progress = progress,
        currentVolume = "第01卷",
        outputTargets = targets,
    )

    // ---- 快照自身的语义 ----

    @Test
    fun `进行中与执行器故障才可能留半成品`() {
        val base = running()
        assertTrue(base.mayLeavePartialFiles)

        assertTrue(
            base.finished(PackTaskPhase.Aborted, nowMillis = 2_000L, failureDetail = "boom")
                .mayLeavePartialFiles,
        )
    }

    @Test
    fun `已完成 已取消 被拒绝都不算留半成品`() {
        val base = running()

        assertFalse(base.finished(PackTaskPhase.Finished, nowMillis = 2_000L).mayLeavePartialFiles)
        assertFalse(base.finished(PackTaskPhase.Cancelled, nowMillis = 2_000L).mayLeavePartialFiles)
        assertFalse(base.finished(PackTaskPhase.Rejected, nowMillis = 2_000L).mayLeavePartialFiles)
    }

    @Test
    fun `收尾不允许传进行中`() {
        assertFailsWith<IllegalArgumentException> {
            running().finished(PackTaskPhase.Running, nowMillis = 2_000L)
        }
    }

    @Test
    fun `收尾会清掉正在处理的卷`() {
        val done = running().finished(PackTaskPhase.Finished, nowMillis = 2_000L)

        assertEquals(null, done.currentVolume)
        assertEquals(PackTaskPhase.Finished, done.phase)
        assertEquals(2_000L, done.updatedAtMillis)
    }

    @Test
    fun `推进进度保留任务身份与目标列表`() {
        val next = running().withProgress(
            next = TaskProgress(total = 3, done = 2),
            currentVolume = "第02卷",
            nowMillis = 2_500L,
        )

        assertEquals("t1", next.taskId)
        assertEquals(listOf("a.cbz", "b.cbz"), next.outputTargets)
        assertEquals("第02卷", next.currentVolume)
        assertEquals(2, next.processedVolumes)
        assertEquals(1, next.remainingVolumes)
    }

    @Test
    fun `推进进度可以显式清掉当前卷`() {
        val next = running().withProgress(
            next = TaskProgress(total = 3, done = 2),
            currentVolume = null,
            nowMillis = 2_500L,
        )

        assertEquals(null, next.currentVolume)
    }

    // ---- 恢复判定：五种状态的六种组合 ----

    @Test
    fun `没有快照就是正常启动`() {
        assertEquals(PackTaskRecovery.None, recoverPackTask(null, serviceAlive = false))
        assertEquals(PackTaskRecovery.None, recoverPackTask(null, serviceAlive = true))
    }

    @Test
    fun `服务还活着时界面重连不应打扰用户`() {
        val recovery = recoverPackTask(running(), serviceAlive = true)

        assertTrue(recovery is PackTaskRecovery.StillRunning)
        assertEquals("t1", (recovery as PackTaskRecovery.StillRunning).snapshot.taskId)
    }

    @Test
    fun `服务死了但快照停在跑着就是中断`() {
        val recovery = recoverPackTask(running(), serviceAlive = false)

        assertTrue(recovery is PackTaskRecovery.Interrupted)
        assertEquals(
            listOf("a.cbz", "b.cbz"),
            (recovery as PackTaskRecovery.Interrupted).outputsToVerify,
        )
    }

    @Test
    fun `执行器故障即使服务已停也算中断`() {
        val aborted = running().finished(
            PackTaskPhase.Aborted,
            nowMillis = 2_000L,
            failureDetail = "授权已失效",
        )

        // 服务活着也照样要提示:故障发生在执行中途,写没写完不确定。
        assertTrue(recoverPackTask(aborted, serviceAlive = true) is PackTaskRecovery.Interrupted)
        assertTrue(recoverPackTask(aborted, serviceAlive = false) is PackTaskRecovery.Interrupted)
    }

    @Test
    fun `跑完 取消 被拒都是已了结`() {
        val base = running()

        for (phase in listOf(PackTaskPhase.Finished, PackTaskPhase.Cancelled, PackTaskPhase.Rejected)) {
            val settled = base.finished(phase, nowMillis = 2_000L)
            assertTrue(
                recoverPackTask(settled, serviceAlive = false) is PackTaskRecovery.Settled,
                "phase=$phase 应归为已了结",
            )
        }
    }

    @Test
    fun `预检拒绝即使服务死了也不提示`() {
        // 关键分档:Rejected 意味着一个输出流都没打开,磁盘上什么都没写。
        // 若它被归成 Interrupted,用户会被告知「上次可能没写完」而实际什么都没发生。
        val rejected = running(targets = emptyList())
            .finished(PackTaskPhase.Rejected, nowMillis = 2_000L)

        val recovery = recoverPackTask(rejected, serviceAlive = false)

        assertTrue(recovery is PackTaskRecovery.Settled)
    }

    @Test
    fun `中断提示要带回快照本身`() {
        val snapshot = running(progress = TaskProgress(total = 5, done = 2, failed = 1))
        val recovery = recoverPackTask(snapshot, serviceAlive = false) as PackTaskRecovery.Interrupted

        // 界面要能说清「上次处理到 3/5」,所以进度必须跟着回来。
        assertEquals(3, recovery.snapshot.processedVolumes)
        assertEquals(2, recovery.snapshot.remainingVolumes)
        assertEquals("第01卷", recovery.snapshot.currentVolume)
    }

    @Test
    fun `失败项计入已处理所以中断读数不会卡住`() {
        // 与 TaskProgress 的既有语义一致:失败也是"处理过了"。
        // 若恢复提示按 done 而不是 processed 读,会显示"处理到 2/5"而实际已经碰过 3 卷。
        val snapshot = running(progress = TaskProgress(total = 5, done = 2, failed = 1, skipped = 1))

        assertEquals(4, snapshot.processedVolumes)
        assertEquals(1, snapshot.remainingVolumes)
    }
}
