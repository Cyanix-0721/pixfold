package com.cyanix.pixfold.data.task

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat

/**
 * 「持续进行中的任务进度」通知的**唯一**构建入口。
 *
 * ## 为什么需要这个抽象
 *
 * Android 16 起，一个通知要被系统升格成实时活动（Live Update / 状态栏胶囊 /
 * OPPO 的流体云），必须同时满足 9 条硬条件。其中最阴险的是**「不得怎样」**那几条：
 *
 * - 不得设 `customContentView`；
 * - 不得是 `setGroupSummary(true)`；
 * - 不得 `setColorized(true)`；
 * - 渠道 importance 不得是 `IMPORTANCE_MIN`。
 *
 * 这些错误**编译器一条都拦不住**。把 `setColorized(true)` 加上去，构建照样全绿，
 * 单测照样全绿，唯独真机上胶囊**静默消失**——没有异常、没有日志、没有失败通知。
 * 这正是最难排查的那类回归：功能「看起来还在」，只是那个特性不再生效。
 *
 * 所以这里的取舍是：**不把 `Builder` 交给调用方**。调用方只能给纯数据
 * （标题 / 文案 / 进度 / 短状态），拿回一个已经配好的 `Notification`。
 * 需求变化时只能在本文件里改，9 条条件于是只有一处需要维护、一处需要复审。
 *
 * 顺带解决的第二个问题：将来「扫描工作区」「批量重命名」若也要进度通知，
 * 直接调本函数即可，不会因为照抄漏掉某一条而静默失去升格能力。
 *
 * ## 离线可断言的判据（实测，非推测）
 *
 * 探针实测（Robolectric / SDK 36）：
 * - `setRequestPromotedOngoing(true)` **不会**设置 `Notification.FLAG_PROMOTED_ONGOING`
 *   （该位是 system_server 收到请求后自己补的，应用侧代码永远看不到它）；
 * - 它做的是往 extras 里写 `android.requestPromotedOngoing = true`；
 * - `hasPromotableCharacteristics()` 在 Robolectric 下返回 `false`。
 *
 * 于是**离线唯一可信的判据是那个 extras 键**，见 `OngoingProgressNotificationTest`。
 * 真机验证只能看 `dumpsys notification` 的 flags 是否出现 `PROMOTED_ONGOING`。
 */
internal object OngoingProgressNotification {

    /**
     * 状态芯片（胶囊）的短文本上限。
     *
     * 官方约束：胶囊区最大宽 96dp。文本不足 7 字符时**整段显示**，超过一半宽度
     * 则尽量多显示，再多就退化成只剩图标。故短文本一律控制在 7 个字符以内
     * （`"100%"` 是 4 个字符，跨语言无歧义）。
     */
    private const val SHORT_TEXT_MAX = 7

    /**
     * 构建一条进度通知。
     *
     * @param fraction 0f..1f 的完成比例，内部裁剪并换算成百分比。
     * @param text 正文，放「2 / 5」这类读数。**不设上限**——正文在展开卡片里显示。
     * @param shortText 胶囊短文本，例如 `"60%"`。超过 [SHORT_TEXT_MAX] 会被截断：
     *   与其让系统按比例截出一个「60…」，不如我们自己截得可读。
     * @param contentIntent 点通知回到应用。`null` 表示不设（通知不可点开）。
     */
    fun build(
        context: Context,
        channelId: String,
        smallIcon: Int,
        title: String,
        text: String,
        fraction: Float,
        shortText: String? = null,
        contentIntent: PendingIntent? = null,
    ): Notification {
        val percent = (fraction.coerceIn(0f, 1f) * 100).toInt()
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(text)
            // 升格条件 4：必须 ongoing。用户划不掉，直到任务自己收尾。
            .setOngoing(true)
            // 静音是**通知层**的默认。渠道层另有 setSound(null)，两层都设属刻意冗余：
            // 用户可能在系统设置里把渠道改回有声，那时通知层的 SILENT 仍能压住
            // 逐卷刷新的进度通知（一次任务会 notify 多次，响铃会变成连续噪音）。
            .setSilent(true)
            // 进度刷新很频繁。没有这一条，每次 notify 都会重新弹出提醒。
            .setOnlyAlertOnce(true)
            // 升格条件 3：必须显式请求升格。
            //
            // **无条件写 true，不要用 `canPostPromotedNotifications()` 当门禁。**
            // 2026-10-19 真机实测：sdk_full = 36.0（非 36.1）、ColorOS V16.0.0，
            // 用户打开系统「流体云 - 实时活动」总开关后升格即生效，logcat 里
            // system_server 侧出现 `PROMOTED_ONGOING`。该开关默认关闭且随时可开，
            // 所以「用户此刻没开」不是不请求的理由——下次 notify 就该自动生效。
            .setRequestPromotedOngoing(true)

        if (contentIntent != null) {
            builder.setContentIntent(contentIntent)
        }

        if (!shortText.isNullOrEmpty()) {
            // 升格后的胶囊文本。只在 API 36+ 有意义，minSdk = 36 ⇒ 无兼容分支。
            // 用 runCatching 兜住：这是纯装饰位，构造失败绝不该让整个任务崩掉。
            runCatching {
                builder.setShortCriticalText(shortText.take(SHORT_TEXT_MAX))
            }
        }

        // 升格条件 1：style 必须是 Standard / BigText / Call / **ProgressStyle** / Metric。
        //
        // 用 **androidx 的** `NotificationCompat.ProgressStyle`，不是框架的
        // `Notification.ProgressStyle`：`NotificationCompat.Builder.setStyle()`
        // 只接受 `NotificationCompat.Style`，传框架那个直接编译失败。
        //
        // `setStyledByProgress(false)` 是刻意的：true 时系统会把**整个通知的语义**
        // 绑到进度上（通知标题等由进度决定样式），而我们要保留自己的标题与正文。
        //
        // Segment 只有 length/id/color/semanticStyle 四个属性，**没有 label** ——
        // 文案一律走 setContentText。单段 100 表示「整条进度条就是这一个任务」。
        // 注意 progressMax 是各 Segment 长度之和；漏设 segment 时默认 100，
        // 一旦把 segment 长度写成 50 而进度还按 0..100 给，进度条就会显示成
        // 「跑了一半就满」—— 这是最容易写出「进度条不动」的地方。
        return builder.setStyle(
            NotificationCompat.ProgressStyle()
                .setProgress(percent)
                .setStyledByProgress(false)
                .setProgressSegments(
                    listOf(NotificationCompat.ProgressStyle.Segment(100)),
                ),
        ).build()
    }
}
