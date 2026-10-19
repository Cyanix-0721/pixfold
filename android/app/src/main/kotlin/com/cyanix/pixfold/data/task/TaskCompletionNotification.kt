package com.cyanix.pixfold.data.task

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import com.cyanix.pixfold.R

/**
 * 「任务已结束」通知的**唯一**构建入口。
 *
 * ## 与 [OngoingProgressNotification] 的分工
 *
 * | | 进行中 | 已结束 |
 * |---|---|---|
 * | 渠道 | [CHANNEL_ID] `pack_task_live`（强制静音） | [CHANNEL_ID] `task_done`（跟随系统） |
 * | `ongoing` | `true`（用户划不掉） | `false`（可划掉） |
 * | 实时活动升格 | 请求 | **不请求**（结束通知不该常驻状态栏胶囊） |
 * | `silent` | `true` | **不设**（让系统按用户设置决定响不响） |
 *
 * 两份渠道分开是**必须**的，不是洁癖：进度通知每几百毫秒更新一次，如果它响铃，
 * 一次打包会响几十声；而完成通知**就是该响的那一声**。同一个渠道无法同时满足
 * 两者 —— 渠道的声音设置是整条渠道的，不是单条通知的。
 *
 * ## 为什么完成渠道不写 `setSound` / `enableVibration`
 *
 * 用户 2026-10-19 裁决：「声音和震动就跟随系统吧不做按钮了」。渠道**不显式设**
 * 声音即跟随系统默认铃声与震动强度；一旦写了 `setSound(null, null)` 就等于
 * 强制静音，用户在应用里再没有任何开关可以打开（那正是上一版渠道的状态）。
 *
 * 注意：`IMPORTANCE_DEFAULT` 是「有声音」的前提，`IMPORTANCE_LOW` 及以下系统
 * 会静音。这里刻意用 DEFAULT 而非 HIGH —— HIGH 会以悬浮横幅弹出，对「打包完成」
 * 这种结果通知属于打扰。
 *
 * ## 为什么不让调用方拿 `Builder`
 *
 * 同 [OngoingProgressNotification] 的理由：这里有三条「不得怎样」的隐式约束
 * （不得静音、不得 `ongoing`、不得请求升格），任何一条被加上去都不会编译失败，
 * 只会在真机上表现为「完成没声音」或「结束还挂在状态栏」。封装成单一入口后，
 * 这些约束只有一处需要复审。
 */
internal object TaskCompletionNotification {

    /**
     * 完成通知的渠道 id。
     *
     * 与进度渠道（`pack_task_live`）分开的第二个原因：渠道 importance 只能降
     * 不能升，进度渠道为了升格被定成 DEFAULT 且**静音**；若把完成通知也发到
     * 那条渠道，等于让它也静音 —— 用户将永远听不到完成提示，且没有任何
     * 应用内手段可以恢复（只能去系统设置里翻渠道）。
     */
    const val CHANNEL_ID = "task_done"

    /**
     * 整理图片的完成通知 id。
     *
     * 与打包的 [PackTaskService.FINAL_NOTIFICATION_ID]（4102）**必须不同**：
     * 两个流程可能先后完成，各自的通知应是两条独立记录，后完成的不能把先完成的
     * 挤掉 —— 否则用户看到「打包完成」时已经不知道「整理完成」发生过。
     *
     * 也刻意避开前台的 4101：那条是 ONGOING，语义完全不同。
     */
    const val ORGANIZE_NOTIFICATION_ID = 4110

    /**
     * 创建（或校正）完成通知渠道。
     *
     * 幂等：重复调用只会更新描述文字，不会重置用户改过的设置
     * （`createNotificationChannel` 对已存在渠道只更新 name/description）。
     *
     * @return 渠道对象本身，供测试断言「没有显式静音」。
     */
    fun ensureChannel(context: Context, name: String, description: String): NotificationChannel {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            name,
            // DEFAULT 而非 HIGH：完成通知不需要悬浮横幅。也**不能**是 LOW/MIN，
            // 那会被系统静音 —— 而「跟随系统」的前提是渠道本身允许出声。
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            this.description = description
            // 刻意**不**调 setSound / enableVibration / setVibrationPattern：
            // 不设置即跟随系统默认（用户裁决 2026-10-19）。
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
        return manager.getNotificationChannel(CHANNEL_ID) ?: channel
    }

    /**
     * 构建一条「任务已完成」通知。
     *
     * @param title 标题，例如「图片整理完成」。
     * @param body 正文，放「成功 N 项 · 失败 M 项」这类读数。
     * @param contentIntent 点通知回到应用；`null` 表示不可点。
     */
    fun build(
        context: Context,
        smallIcon: Int,
        title: String,
        body: String,
        contentIntent: PendingIntent? = null,
    ): Notification {
        // 进构建前先确保渠道存在：`notify()` 到不存在的渠道在 Android 8+ 上
        // 会被系统**静默丢弃**（不抛异常、不报错、通知就是不出现在通知栏）。
        // 调用方可能只发了完成通知而没跑过任何前台服务（整理图片就是这种情况），
        // 那时没有任何别的路径会建这个渠道 —— 放在这里才不会漏。
        ensureChannel(
            context = context,
            name = context.getString(R.string.task_done_channel),
            description = context.getString(R.string.task_done_channel_desc),
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(body)
            // BigTextStyle：正文可能被系统折成一行显示不全（尤其「工作区 + 计数」
            // 那种长串）。展开后完整可见。
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            // 结束通知**必须**可划掉且不 ongoing。前台进度那条是 ONGOING，
            // 它有它存在的理由（任务正在跑）；结束了还挂着就是在骗用户。
            .setOngoing(false)
            // 这里**不**调 setSilent：静音是进度通知的需求，不是完成通知的。
            // 调了就等于把用户裁决的「跟随系统」重新变成强制静音。
            .setCategory(NotificationCompat.CATEGORY_STATUS)
        contentIntent?.let { builder.setContentIntent(it) }
        return builder.build()
    }

    /**
     * 直接发出完成通知（含建渠道）。
     *
     * 两个流程都走这一个入口，避免「打包发了、整理没发」这类不对称。
     */
    fun post(
        context: Context,
        id: Int,
        smallIcon: Int,
        title: String,
        body: String,
        contentIntent: PendingIntent? = null,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(
            id,
            build(
                context = context,
                smallIcon = smallIcon,
                title = title,
                body = body,
                contentIntent = contentIntent,
            ),
        )
    }

}
