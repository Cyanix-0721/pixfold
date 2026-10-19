package com.cyanix.pixfold.data.task

import android.app.Notification
import android.app.NotificationManager
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 完成通知（[TaskCompletionNotification]）的四条硬约束。
 *
 * ## 为什么这一层必须单独测
 *
 * 用户裁决（m09108）是「声音和震动跟随系统」，落到代码上只有**一个**实现方式：
 * 完成通知走一条**不静音**的渠道。任何一处写成 `setSound(null, null)` 或
 * `enableVibration(false)`，都会在编译、lint、真机上都「看起来正常」——
 * 只是永远不响，且用户没有任何应用内开关能救回来（渠道设置只能由用户在系统
 * 设置里改）。
 *
 * ## 判据怎么取的（探针实测，2026-10-19）
 *
 * Robolectric / SDK 36 下建四条对照渠道后读数：
 *
 * | 建法 | `getNotificationChannel().sound` |
 * |---|---|
 * | 什么都不设 | `content://settings/system/notification_sound` |
 * | `setSound(null, null)` | `null` |
 *
 * 于是「跟不跟随系统」的**离线可断判据就是 `channel.sound != null`**。
 * `shouldVibrate()` 在 Robolectric 下恒为 `false`（不设也是 false），
 * **不可用** —— 拿它写断言会得到一个恒真的假保障。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TaskCompletionNotificationTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    private fun ensureChannel(): android.app.NotificationChannel {
        TaskCompletionNotification.ensureChannel(
            context = context,
            name = "done",
            description = "desc",
        )
        return manager.getNotificationChannel(TaskCompletionNotification.CHANNEL_ID)
            ?: error("渠道必须被创建出来，否则 notify 会被系统静默丢弃")
    }

    /**
     * **最重要的一条**：完成渠道必须跟系统，不能静音。
     *
     * 变异 M1（在 `ensureChannel` 里加 `setSound(null, null)`）唯一能杀掉的地方。
     * 加进去之后编译绿、lint 绿、真机通知照常出现 —— 只是**永远不响**。
     */
    @Test
    fun `completion channel follows the system sound instead of silencing`() {
        val channel = ensureChannel()

        assertNotNull(
            "完成通知渠道绝不能显式静音 —— 用户裁决「声音和震动跟随系统」，" +
                "而渠道一旦静音，应用内没有任何开关能改回来",
            channel.sound,
        )
        assertEquals(
            "不设置声音时应落到系统默认通知音",
            android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,
            channel.sound,
        )
    }

    /**
     * importance 必须让通知能出声。
     *
     * `IMPORTANCE_LOW` / `MIN` / `NONE` 在系统侧都会被静音（LOW 还额外被归入
     * `mUnimportant`，连带失去实时活动升格能力）。这里写「不得低于 DEFAULT」
     * 而不是「等于 DEFAULT」：将来因为 UX 理由提到 HIGH 不该让这条红。
     */
    @Test
    fun `completion channel is important enough to make a sound`() {
        val channel = ensureChannel()
        assertTrue(
            "渠道 importance 必须 >= DEFAULT（当前 ${channel.importance}），否则系统会静音",
            channel.importance >= NotificationManager.IMPORTANCE_DEFAULT,
        )
    }

    /**
     * 完成通知**必须可划掉**且不 `ongoing`。
     *
     * 前台进度那条是 ONGOING（任务正在跑，有存在理由）；结束了还挂着就是在骗
     * 用户「它还在运行」。真机缺陷史上有过相反的例子（完成通知被前台服务连带
     * 清掉），这条守的是另一个极端。
     */
    @Test
    fun `completion notification is dismissible and not ongoing`() {
        val notification = TaskCompletionNotification.build(
            context = context,
            smallIcon = android.R.drawable.ic_menu_save,
            title = "done",
            body = "body",
        )

        val flags = notification.flags
        assertFalse(
            "完成通知不得是 ONGOING —— 那会让用户以为任务还在跑",
            flags and Notification.FLAG_ONGOING_EVENT != 0,
        )
        assertTrue(
            "完成通知必须可自动划掉",
            flags and Notification.FLAG_AUTO_CANCEL != 0,
        )
    }

    /**
     * 完成通知**不得**请求实时活动升格。
     *
     * 升格意味着常驻状态栏胶囊 + 无提示音 + 不可折叠（官方文档：「Expanded by
     * default / Uncollapsible」）。如果结束通知也带这个位，用户会看到一条永远
     * 挂在胶囊区、没有声音的「打包完成」—— 既没提示作用又占位置。
     *
     * 判据取 extras 键（与 `OngoingProgressNotificationTest` 同一个理由：
     * `FLAG_PROMOTED_ONGOING` 由 system_server 补，应用侧永远看不到）。
     */
    @Test
    fun `completion notification does not request live update promotion`() {
        val notification = TaskCompletionNotification.build(
            context = context,
            smallIcon = android.R.drawable.ic_menu_save,
            title = "done",
            body = "body",
        )

        val extras: Bundle? = notification.extras
        assertFalse(
            "结束通知不该请求升格 —— 那会让它变成常驻胶囊且无声音",
            extras?.containsKey(EXTRA_REQUEST_PROMOTED_ONGOING) == true,
        )
    }

    /**
     * 两条流程的完成通知 id 必须不同，且都与前台服务的 id 不同。
     *
     * 判据是**真的发两条**再看系统里存了几条 —— 只断常量不够（历史上踩过：
     * 变异改的是调用点，只断常量的断言照样绿）。
     */
    @Test
    fun `organize and pack completions do not overwrite each other`() {
        TaskCompletionNotification.post(
            context = context,
            id = TaskCompletionNotification.ORGANIZE_NOTIFICATION_ID,
            smallIcon = android.R.drawable.ic_menu_save,
            title = "organize",
            body = "1 / 0 / 0",
        )
        TaskCompletionNotification.post(
            context = context,
            id = PackTaskService.FINAL_NOTIFICATION_ID,
            smallIcon = android.R.drawable.ic_menu_save,
            title = "pack",
            body = "1 / 0 / 0",
        )

        assertNotNull(
            "整理完成通知必须留下",
            shadowOf(manager).getNotification(TaskCompletionNotification.ORGANIZE_NOTIFICATION_ID),
        )
        assertNotNull(
            "打包完成通知必须留下 —— 两个流程各自一条，后完成的不该挤掉先完成的",
            shadowOf(manager).getNotification(PackTaskService.FINAL_NOTIFICATION_ID),
        )
        assertNotEquals(
            "两条完成通知的 id 必须不同",
            TaskCompletionNotification.ORGANIZE_NOTIFICATION_ID,
            PackTaskService.FINAL_NOTIFICATION_ID,
        )
    }

    /**
     * `build` 必须**自己**确保渠道存在。
     *
     * 「整理图片」是纯 UI 流程，从来没跑过前台服务 —— 如果渠道只在
     * `PackTaskService.createChannel()` 里建，那么整理完成时 `notify()` 会
     * 被系统**静默丢弃**（Android 8+ 对不存在的渠道不抛异常、不报错）。
     */
    @Test
    fun `build ensures the channel even when no service ever ran`() {
        assertNull(
            "前置条件：测试开始时渠道不该存在",
            manager.getNotificationChannel(TaskCompletionNotification.CHANNEL_ID),
        )

        TaskCompletionNotification.build(
            context = context,
            smallIcon = android.R.drawable.ic_menu_save,
            title = "done",
            body = "body",
        )

        assertNotNull(
            "build 必须自己建渠道 —— 整理图片这条路径没有任何前台服务会替它建",
            manager.getNotificationChannel(TaskCompletionNotification.CHANNEL_ID),
        )
    }

    private companion object {
        /** 与 AndroidX `NotificationCompat.Builder.setRequestPromotedOngoing` 写入的键一致。 */
        const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    }
}
