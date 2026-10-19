package com.cyanix.pixfold.data.task

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `OngoingProgressNotification` 的守卫。
 *
 * ## 这些断言在守什么
 *
 * 通知**升格成实时活动**（状态栏胶囊 / 流体云）是 9 条硬条件的合取。少一条就静默失效：
 * 没有异常、没有日志、任务照常跑完，只是那个特性不再出现。真机上唯一的判据是
 * `dumpsys notification` 的 flags 里有没有 `PROMOTED_ONGOING`，那是 system_server
 * 收到请求后自己补的位，**应用侧拿不到**。
 *
 * 所以离线能守的是**请求已发出**（extras 里的 `android.requestPromotedOngoing`），
 * 以及**没有踩到那几条「不得怎样」**。前者是「有没有请求」，后者是「请求还有没有效」——
 * 两个都要断，因为条件 6/7/8 全是**加一行就破**的类型。
 *
 * 实测依据（探针，SDK 36 / Robolectric）：`setRequestPromotedOngoing(true)` 不设置
 * `FLAG_PROMOTED_ONGOING`，只写 extras 键；`hasPromotableCharacteristics()` 返回 false。
 * 因此**不要**用 flags 位或 `hasPromotableCharacteristics()` 做断言 —— 它们在离线
 * 环境里恒为「未升格」，写成断言只会得到一个永远红/永远绿的假信号。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OngoingProgressNotificationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun build(
        fraction: Float = 0.6f,
        shortText: String? = "60%",
        text: String = "2 / 5",
    ): Notification = OngoingProgressNotification.build(
        context = context,
        channelId = "test-channel",
        smallIcon = android.R.drawable.ic_menu_add,
        title = "打包中",
        text = text,
        fraction = fraction,
        shortText = shortText,
    )

    /** 升格硬条件 3：必须显式请求。缺了它就只是普通通知，胶囊永不出现。 */
    @Test
    fun requestsPromotion() {
        val extras = build().extras
        assertNotNull(extras)
        assertEquals(
            true,
            extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING, false),
        )
    }

    /** 升格硬条件 4：必须 ongoing。用户能划掉的通知不会被升格。 */
    @Test
    fun isOngoing() {
        assertTrue(build().flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    /** 升格硬条件 5：必须有 contentTitle。 */
    @Test
    fun hasContentTitle() {
        assertEquals("打包中", build().extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
    }

    /**
     * 升格硬条件 8：**不得** `setColorized(true)`。
     *
     * 这条最容易被「顺手优化」破坏——框架 `Notification.ProgressStyle` 的官方示例里
     * 就写了 `.setColorized(true)`，照着抄会让升格静默失效，且编译/单测全绿。
     *
     * ⚠️ 判据必须是**布尔**取值，不能用 `getInt(EXTRA_COLORIZED, 0) == 0`。
     * 探针实测（SDK 36 / Robolectric）：`setColorized(true)` 往 extras 里写的是
     * **Boolean** 类型的 `android.colorized` 键；缺键时 `getInt(..., 0)` 返回 0，
     * 有键时因为类型不符同样返回 0 ⇒ 那个写法两种情况下都通过，是假绿。
     * 这里补 `containsKey`：只要键出现就算违规，不依赖取值类型。
     */
    @Test
    fun isNotColorized() {
        val extras = build().extras
        assertFalse(
            "不得出现 android.colorized —— 一旦出现升格就会静默失效",
            extras.containsKey(Notification.EXTRA_COLORIZED),
        )
        assertFalse(extras.getBoolean(Notification.EXTRA_COLORIZED, false))
    }

    /**
     * 升格硬条件 6：**不得**设自定义 RemoteViews。
     *
     * 一旦设了 `customContentView`，系统就无法按 Live Update 的样式重排，
     * 请求会被直接忽略。
     */
    @Test
    fun hasNoCustomContentView() {
        val notification = build()
        assertNull(notification.contentView)
        assertNull(notification.bigContentView)
    }

    /** 升格硬条件 7：**不得**是组摘要。 */
    @Test
    fun isNotGroupSummary() {
        assertFalse(build().flags and Notification.FLAG_GROUP_SUMMARY != 0)
    }

    /**
     * 进度值必须落到 extras 上，且 progressMax 是 100（单段 Segment(100)）。
     *
     * 守的是「进度条不动」那类 bug：`progressMax` 由各 Segment 长度**求和**得出，
     * 段长度与进度值不同源就会出现「跑到一半就满」或「到尾也不满」。
     */
    @Test
    fun carriesProgress() {
        val extras = build(fraction = 0.6f).extras
        assertEquals(60, extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(100, extras.getInt(Notification.EXTRA_PROGRESS_MAX))
    }

    /** 越界输入必须被裁剪，不能把 `progress > max` 传给系统。 */
    @Test
    fun clampsOutOfRangeFraction() {
        assertEquals(100, build(fraction = 3f).extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(0, build(fraction = -2f).extras.getInt(Notification.EXTRA_PROGRESS))
    }

    /**
     * 胶囊短文本上限 7 字符。
     *
     * 官方约束：芯片区最大宽 96dp，不足 7 字符整段显示，超了就按比例截断甚至退化
     * 成只剩图标。所以超长时由我们截断——留着让系统截，出来的可能是「打包中第…」，
     * 不如自己截成可控的前缀。
     */
    @Test
    fun truncatesShortTextToSevenChars() {
        // 夹具必须**真的超过** 7 个字符，否则这条测试断的是「没截断也算对」。
        // 「打包中第1234卷」= 9 字符 ⇒ 应截成前 7 个「打包中第123」。
        val long = "打包中第1234卷"
        assertEquals(9, long.length)
        val shortText = build(shortText = long).shortCriticalText
        assertEquals("打包中第123", shortText)
    }

    /** 不传短文本时不应凭空造一个。 */
    @Test
    fun omitsShortTextWhenNull() {
        assertNull(build(shortText = null).shortCriticalText)
    }

    /**
     * 静音 + 只响一次。
     *
     * 一次任务会 notify 很多次。没有 `setOnlyAlertOnce`，每次刷新都是一次提醒；
     * 没有 `setSilent`，用户会在打包过程中被持续打扰。
     *
     * ⚠️ 判据的选择经过实测（探针，SDK 36 / Robolectric），因为几乎所有
     * 「看起来合理」的断言在这里都是**假绿**：
     *
     * | 候选判据 | `setSilent(true)` | 不调 `setSilent` | 有判别力？ |
     * | --- | --- | --- | --- |
     * | `notification.sound == null` | null | null | ❌ |
     * | `notification.vibrate == null` | null | null | ❌ |
     * | `defaults and DEFAULT_SOUND == 0` | 0 | 0 | ❌ |
     * | `notification.group` | `"silent"` | `null` | ✅ |
     * | `notification.groupAlertBehavior` | 1（SUMMARY） | 0（ALL） | ✅ |
     *
     * 根因（反查 `core-1.19.0.aar` 的 `NotificationCompatBuilder` 字节码）：
     * `setSilent` 做的是「清掉本来就没设的声音/震动」+ 挂一个名为 `"silent"`
     * 的通知组并指定组内提示行为。前一半是**幂等空操作**，默认值本来就是空，
     * 所以只能断后一半。`Notification` 里也没有 `FLAG_SILENT` 这个常量（它是
     * framework 的内部位，公开常量表里不存在）。
     */
    @Test
    fun isSilentAndAlertsOnlyOnce() {
        val notification = build()
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(
            "setSilent 会把通知挂进名为 silent 的组，这是它唯一可观测的痕迹",
            "silent",
            notification.group,
        )
        assertEquals(
            "组内提示行为必须是 SUMMARY —— 否则组内每条进度刷新仍会各自提醒",
            androidx.core.app.NotificationCompat.GROUP_ALERT_SUMMARY,
            notification.groupAlertBehavior,
        )
    }

    /**
     * 与 `PackTaskService` 的接线：服务发出去的那条通知确实带了升格请求。
     *
     * 上面每条断的是抽象本身；这条断的是「服务真的用了它」。两者必须都有——
     * 只断抽象的话，把服务改回自己拼 `Builder` 仍然会全绿。
     */
    @Test
    fun serviceUsesTheAbstraction() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val service = org.robolectric.Robolectric
            .buildService(PackTaskService::class.java)
            .create()
            .get()

        service.updateNotificationForTest(0.6f, "2 / 5")

        val posted = org.robolectric.Shadows.shadowOf(manager)
            .getNotification(PackTaskService.NOTIFICATION_ID)
        assertNotNull("服务没有发出进度通知", posted)
        assertEquals(
            true,
            posted!!.extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING, false),
        )
    }
}
