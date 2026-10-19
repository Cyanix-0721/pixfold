package com.cyanix.pixfold.data.task

import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.cyanix.pixfold.domain.task.PackTaskPhase
import com.cyanix.pixfold.domain.task.PackTaskSnapshot
import com.cyanix.pixfold.domain.task.TaskProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * `PackTaskService` 的通知行为。
 *
 * ## 为什么必须测这一层
 *
 * 2026-10-19 真机暴露的缺陷（完成通知被前台服务连带清除）**在纯 JVM 单测里
 * 根本看不见**：两条通知都用 4101 时，`PackTaskBus` 照样 `settle(Done(...))`，
 * 所有状态断言全绿，唯独用户永远看不到「打包完成」。
 *
 * 承重的那条事实是「**结束通知必须与前台服务使用不同的 id**」——
 * 它只在「服务停止时系统 cancel 前台通知」这一系统行为下才有意义。
 * Robolectric 的 `ShadowNotificationManager` 恰好能观察到这个 id 层面的差异，
 * 所以在这里钉住：**两个 id 必须不同**，且结束时进度通知必须被撤掉。
 *
 * 这是变异 M1（把 `FINAL_NOTIFICATION_ID` 改回 `NOTIFICATION_ID`）唯一能杀掉的地方。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PackTaskServiceTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    private fun startedService(): PackTaskService =
        Robolectric.buildService(PackTaskService::class.java).create().get()

    @Test
    fun `channel is created with low importance`() {
        startedService()
        val channel = manager.getNotificationChannel(PackTaskService.CHANNEL_ID)
        assertNotNull("前台服务必须建好通知渠道，否则 notify 静默失败", channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    /**
     * 结束通知的 id 必须**与前台服务不同**。
     *
     * 若相同，`stopSelf()` 时系统 cancel 掉前台通知会把结束通知一起带走
     * ——真机 logcat 实证：`onNotificationRemoved ... reason:REASON_APP_CANCEL`。
     */
    @Test
    fun `final notification uses a different id than the foreground one`() {
        assertNotEquals(
            "结束通知与前台服务共用 id 会在服务停止时被连带清除，用户看不到「打包完成」",
            PackTaskService.NOTIFICATION_ID,
            PackTaskService.FINAL_NOTIFICATION_ID,
        )
    }

    /**
     * 断言**实际调用点**用了哪个 id —— 只断常量不同是不够的。
     *
     * 变异实测教训（2026-10-19）：首版只 `assertNotEquals(NOTIFICATION_ID,
     * FINAL_NOTIFICATION_ID)`，把 `notifyFinal` 里的 id 改回 `NOTIFICATION_ID`
     * 后测试**仍然全绿**（常量本身没变）。那是「守错了地方」的假保障。
     *
     * 正确写法：真的调一次 `notifyFinal`，看系统里那条通知挂在哪个 id 下。
     */
    @Test
    fun `notifyFinal actually posts under the final id`() {
        val service = startedService()
        service.notifyFinal(android.R.string.ok, "done")

        val posted = shadowOf(manager).allNotifications
        assertNotNull(
            "结束通知必须真的发出（否则用户看不到结果）",
            shadowOf(manager).getNotification(PackTaskService.FINAL_NOTIFICATION_ID),
        )
        assertNull(
            "结束通知绝不能用前台服务的 id —— 停服会被连带清除",
            shadowOf(manager).getNotification(PackTaskService.NOTIFICATION_ID),
        )
        assertTrue("应当只发一条结束通知，实际 ${posted.size} 条", posted.isNotEmpty())
    }

    /** 发结束通知时要把还挂着的前台进度通知撤掉，否则屏幕同时留两条、进度条误导成还在跑。 */
    @Test
    fun `notifyFinal cancels the foreground progress notification`() {
        val service = startedService()
        manager.notify(
            PackTaskService.NOTIFICATION_ID,
            androidx.core.app.NotificationCompat.Builder(context, PackTaskService.CHANNEL_ID)
                .setSmallIcon(androidx.core.R.drawable.ic_call_answer)
                .setContentTitle("progress")
                .build(),
        )
        assertNotNull(shadowOf(manager).getNotification(PackTaskService.NOTIFICATION_ID))

        service.notifyFinal(android.R.string.ok, "done")

        assertNull(
            "结束后前台进度通知必须撤掉",
            shadowOf(manager).getNotification(PackTaskService.NOTIFICATION_ID),
        )
        assertNotNull(shadowOf(manager).getNotification(PackTaskService.FINAL_NOTIFICATION_ID))
    }

    /** 通知 id 常量必须是稳定的正数：0 与负数在部分 ROM 上有特殊语义。 */
    @Test
    fun `notification ids are positive and stable`() {
        assertTrue(PackTaskService.NOTIFICATION_ID > 0)
        assertTrue(PackTaskService.FINAL_NOTIFICATION_ID > 0)
        assertEquals(4101, PackTaskService.NOTIFICATION_ID)
        assertEquals(4102, PackTaskService.FINAL_NOTIFICATION_ID)
    }

    /**
     * 快照落盘与恢复的闭环：Service 写过的快照，冷启动时读得回来。
     *
     * 这条断的是「切后台恢复」这条链路真正的数据基础 —— 若快照没落盘，
     * 无论 `recoverPackTask` 判得多准，界面都拿不到任何东西。
     */
    @Test
    fun `snapshot written by the store survives a cold read`() {
        val store = PackTaskStore(context)
        val snapshot = PackTaskSnapshot(
            taskId = "pack-42",
            phase = PackTaskPhase.Running,
            startedAtMillis = 1_000L,
            updatedAtMillis = 2_000L,
            progress = TaskProgress(total = 3, done = 1),
            currentVolume = "系列/第01卷",
            outputTargets = listOf("系列/第01卷.cbz", "系列/第02卷.cbz"),
        )
        store.save(snapshot)

        // 用**另一个** store 实例读，模拟进程重启（不能靠内存里的任何东西）。
        val reloaded = PackTaskStore(context).load()
        assertNotNull("快照必须能从磁盘读回，否则切后台恢复无从谈起", reloaded)
        assertEquals("pack-42", reloaded!!.taskId)
        assertEquals(PackTaskPhase.Running, reloaded.phase)
        assertEquals(3, reloaded.progress.total)
        assertEquals(listOf("系列/第01卷.cbz", "系列/第02卷.cbz"), reloaded.outputTargets)
    }

    /** 清掉之后必须真的读不到 —— 否则下一次冷启动会对着旧快照报假中断。 */
    @Test
    fun `cleared snapshot reads back as null`() {
        val store = PackTaskStore(context)
        store.save(
            PackTaskSnapshot(
                taskId = "pack-43",
                phase = PackTaskPhase.Finished,
                startedAtMillis = 1L,
                updatedAtMillis = 2L,
                progress = TaskProgress(total = 1, done = 1),
            ),
        )
        store.clear()
        assertNull(PackTaskStore(context).load())
    }
}
