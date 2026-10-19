# Android 16(API 36)Live Updates / ProgressStyle API 备忘

> 调研日期:2026-10-19。用途:D2b「切后台恢复」的前台服务 + 进度通知接线。
> 取证方式:developer.android.com 官方页 + AOSP `frameworks/base` 源码 + 本地 SDK `android.jar` / `api-versions.xml` 的 `javap` / XML 实测。每条结论后附 URL 或本地路径。
> **本项目现状(工作树实测)**:`compileSdk = 37`、**`minSdk = 36`**、`targetSdk = 36`(`android/app/build.gradle.kts:14,23,24`)、`androidx.core:core-ktx:1.19.0`(`android/gradle/libs.versions.toml:15,43`)。
> ⚠️ `minSdk = 36` 是**未提交的工作树改动**(`git diff` 显示由 34 改为 36,同批还显式声明了 `implementation(libs.core.ktx)`)。这条对本文第 6 节影响很大 —— **minSdk 36 意味着所有设备都具备 `ProgressStyle` 原生支持,降级路径只为「理论上」存在**。若该改动回退到 34,第 6 节的多版本结论立刻生效。

---

## 0. 先读:四个和你提问对不上的地方

在分节之前先集中列出**经实测证伪的 API 名称**,后面各节不再重复:

| 你提到的 | 实测结论 | 依据 |
| --- | --- | --- |
| `setStatusText` | **不存在**。16.0 / QPR2 / main / AndroidX 四份 `Notification*.java` 里 grep 计数全为 0 | [AOSP Notification.java (android16-release)](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/app/Notification.java)、[AndroidX NotificationCompat.java](https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/core/core/src/main/java/androidx/core/app/NotificationCompat.java) |
| `setCenteredText` | **不存在**。同上,四份文件 grep 全 0 | 同上 |
| `@ExperimentalNotificationApi` | **不存在这个注解**。framework 侧用的是 `@FlaggedApi(Flags.FLAG_API_RICH_ONGOING)`(系统内部注解,应用无法也无需 opt-in);AndroidX 侧**没有任何实验注解**(`NotificationCompat.java` 全文 `grep -c Experimental` = 0)⇒ **应用侧零 opt-in** | [Notification.ProgressStyle 参考页](https://developer.android.com/reference/android/app/Notification.ProgressStyle)(页面上无任何 experimental 提示)、AOSP 源码 |
| `setRequestPromotedOngoing` 属 API 36 | **错,是 API 36.1**(Android 16 QPR2)。API 36 的 `android.jar` 里**根本没有这个方法** | 本地 `platforms/android-36/data/api-versions.xml` 中该方法 MISSING;`android-37.0/data/api-versions.xml` 记 `since="36.1"` |

另外两条容易踩的语义偏差:

- **`CATEGORY_PROGRESS` 不是升格条件**。`NotificationManagerService.java` 里 `grep -c CATEGORY_PROGRESS` = **0** ⇒ 系统升格判定完全不看 category。它是 API 21 就有的老常量("progress of a long-running background operation"),设了无害,但不是必需项。
- **渠道要求是「不得是 IMPORTANCE_MIN」,而不是「必须是某个特定级别」**。所以现有的 `IMPORTANCE_LOW` 是合法的。

---

## 1. `Notification.ProgressStyle` 完整 API

### 结论

- **API 级别**:`Notification.ProgressStyle`(含 `Point`/`Segment`)全部 **API 36**。AndroidX 对应物 `androidx.core.app.NotificationCompat.ProgressStyle` **1.17.0**(2025-08-13 稳定)。
- **构造**:无参 `ProgressStyle()`。**没有**接受 List 的构造重载。
- **方法总数 19 个**(`javap` 实测,android-36 与 android-37.0 完全一致):

```
<init>()                              addProgressPoint(Point)        addProgressSegment(Segment)
getProgress()                         getProgressMax()               getProgressPoints()
getProgressSegments()                 getProgressStartIcon()         getProgressEndIcon()
getProgressTrackerIcon()              isProgressIndeterminate()      isStyledByProgress()
setProgress(int)                      setProgressPoints(List)        setProgressSegments(List)
setProgressIndeterminate(boolean)     setStyledByProgress(boolean)   setProgressStartIcon(Icon)
setProgressEndIcon(Icon)              setProgressTrackerIcon(Icon)
```

- **`Segment` 构造**:`public Segment(int length)`,参数注解 `@IntRange(from = 1)`(值 ≥ 1)**没有单位** —— 只是段与段之间、以及与 `setProgress` 的相对比例。
- **`Point` 构造**:`public Point(int position)`,`@IntRange(from = 1)`,`position` 相对 `getProgressMax()`。
- **`semanticStyle` 分两层,版本不同**(实测):
  - framework 层:`Segment.getSemanticStyle()` / `setSemanticStyle(int)` **API 37** 才新增(`javap` android-36 无、android-37.0 有);API 36 的 `Segment` 只有 `mLength` / `mId` / `mColor`。
  - AndroidX 层:`NotificationCompat.ProgressStyle.Segment.setSemanticStyle(int)` / `getSemanticStyle()` **Added in 1.19.1**(官方参考页逐条给出的版本号),常量 `SEMANTIC_STYLE_{UNSPECIFIED,INFO,SAFE,CAUTION,DANGER}`。
  - ⇒ 本项目 `core-ktx 1.19.0` **够不到** 1.19.1 的 `setSemanticStyle`,但 framework 侧 `compileSdk = 37` 够得到 API 37 的版本。二者择一。**别在无版本保护的代码里用。**(本项目 `minSdk = 36` 时 framework 侧 API 37 的 `setSemanticStyle` 仍需 `Build.VERSION.SDK_INT >= 37` 判断,因为 `minSdk 36 < 37`。)
- **不需要任何 opt-in 注解**。

### 各方法作用与签名(官方原文摘录)

| 方法 | 签名 | 官方原文要点 |
| --- | --- | --- |
| `setProgress` | `setProgress(@IntRange(from=0) int progress)` | "Specifies the progress (in the same units as `Segment.getLength()`) of the tracker along the length of the bar. **The max progress value is the sum of all Segment lengths.** The default value is 0." |
| `getProgressMax` | `getProgressMax()` | "Gets the sum of the lengths of all Segments in the style, which defines the maximum progress. **Defaults to 100 when segments are omitted.**" |
| `setProgressSegments` | `setProgressSegments(@NonNull List<Segment>)` | "Sets or replaces the segments of the progress bar. Segments allow for creating progress bars with multiple colors or sections... For example, Traffic conditions along a navigation journey." |
| `getProgressSegments` | `getProgressSegments()` | "**If no segments are provided, the progress bar will be rendered with a single segment with length 100 and default color.**" |
| `setProgressPoints` | `setProgressPoints(@NonNull List<Point>)` | "Replaces all the progress points." |
| `setStyledByProgress` | `setStyledByProgress(boolean)` | "Indicates whether the segments and points will be styled differently based on whether they are behind or ahead of the current progress. When `true`, segments appearing ahead of the current progress will be given a **slightly different appearance**... When `false`, all segments will be given the **filled appearance**, and it will be the app's responsibility to use `setProgressTrackerIcon` or segment colors to make the current progress clear. **the default value is `true`.**" |
| `setProgressIndeterminate` | `setProgressIndeterminate(boolean)` | "Used to indicate an initialization state without a known progress amount. When specified, the following fields are ignored: `setProgress(int)`, `setProgressSegments(List)`, `setProgressPoints(List)`, `setProgressTrackerIcon(Icon)`, `setStyledByProgress(boolean)`." + "If the app provides **exactly one** Segment, that segment's color will be used to style the indeterminate bar." |
| `setProgressTrackerIcon` | `setProgressTrackerIcon(@Nullable Icon)` | "An optional icon that can appear as an **overlay on the bar at the point of current progress**. Aspect ratio may be anywhere from **2:1 to 1:2**; content outside that aspect ratio range will be **cropped**. This icon **will be mirrored in RTL**." |
| `setProgressStartIcon` / `setProgressEndIcon` | `(@Nullable Icon)` | 可选的方形图标,裁成中心正方形,"will **NOT** be mirrored in RTL layouts"。 |

> ⚠️ 类文档明确:`Notification.Builder.setProgress(...)` 设的 extras 会在 build 时**被本 style 覆盖**,二者不并用。

### 官方原文 + URL

- <https://developer.android.com/reference/android/app/Notification.ProgressStyle>
- <https://developer.android.com/reference/android/app/Notification.ProgressStyle.Segment>(`public Segment (int length)`,`Added in API level 36`,参数 `Value is 1 or greater`)
- <https://developer.android.com/reference/androidx/core/app/NotificationCompat.ProgressStyle>(19 个方法全部 `Added in: 1.17.0`)
- <https://developer.android.com/jetpack/androidx/releases/core>(1.17.0-alpha01,June 18, 2025:"Added `NotificationCompat.ProgressStyle` and `NotificationCompat.Builder.setRequestPromotedOngoing()`");1.17.0 稳定版 **August 13, 2025**

compat 版与框架版的唯一签名差异:图标用 **`IconCompat`** 而非 `Icon`。

### 可照抄代码

```kotlin
import android.app.Notification
import android.graphics.Color
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat

// 无 opt-in 注解。compat 版内部自己按 SDK 降级(见第 6 节)。
private fun buildProgressStyle(percent: Int): NotificationCompat.ProgressStyle =
    NotificationCompat.ProgressStyle()
        .setStyledByProgress(false)                              // 全部显示为"已填充"外观
        .setProgress(percent.coerceIn(0, 100))                   // 单位与 Segment.length 同
        .setProgressSegments(
            listOf(
                NotificationCompat.ProgressStyle.Segment(100)     // 单段,长度 100 ⇒ progressMax = 100
                    .setColor(Color.parseColor("#4CAF50")),
            )
        )

// 想用框架版(仅 API 36+ 分支,需自行做 Build.VERSION 判断):
// import android.app.Notification.ProgressStyle
// val style = Notification.ProgressStyle()
//     .setProgress(42)
//     .setProgressSegments(listOf(Notification.ProgressStyle.Segment(100)))
```

多段 + 追踪图标的完整形态(官方示例改写,Java 风格语义一致):

```kotlin
val style = NotificationCompat.ProgressStyle()
    .setStyledByProgress(false)
    .setProgress(456)
    .setProgressTrackerIcon(IconCompat.createWithResource(context, R.drawable.ic_pack_progress))
    .setProgressSegments(
        listOf(
            NotificationCompat.ProgressStyle.Segment(41).setColor(Color.BLACK),
            NotificationCompat.ProgressStyle.Segment(559).setColor(Color.BLUE),
        )
    )
    .setProgressPoints(
        listOf(
            NotificationCompat.ProgressStyle.Point(60).setColor(Color.RED),
            NotificationCompat.ProgressStyle.Point(560).setColor(Color.GREEN),
        )
    )
```

---

## 2. 升格为芯片(chip)的条件

### 结论

官方 **9 条硬条件**(逐字,<https://developer.android.com/develop/ui/views/notifications/live-update>,Last updated 2026-10-16 UTC):

1. "Must be Standard Style, `BigTextStyle`, `CallStyle`, `ProgressStyle`, or `MetricStyle`."
2. "Must request the following **non-runtime permission** in the android manifest `android.permission.POST_PROMOTED_NOTIFICATIONS`."
3. "Must request promotion using `EXTRA_REQUEST_PROMOTED_ONGOING` or `NotificationCompat.Builder#setRequestPromotedOngoing`."
4. "Must be `ongoing` (set `FLAG_ONGOING_EVENT`)."
5. "Must have a `contentTitle` set."
6. "Must **NOT** have any `customContentView` set (no `RemoteViews`)."
7. "Must **NOT** be the summary of a group using `setGroupSummary`."
8. "Must **NOT** `setColorized` to `TRUE`."
9. "The notification channel must **NOT** have `IMPORTANCE_MIN`."

**芯片内容来源的优先级**(`setShortCriticalText` 的 javadoc,<https://developer.android.com/reference/android/app/Notification.Builder>):

> "Short critical text will always be the **highest precedence** content for a chip. Setting this to `""` will ensure the chip has no content; `null` (the default) will fall back to the other options. A `MetricStyle`'s critical metric is the next option... A time (from `Notification.when`) is the final source of content for the chip."

**芯片尺寸与文本显示规则**(Live Updates 页 Status Chips 节):

> "The status chip always includes an icon, and optionally includes text. **The chip has a maximum width of 96dp.** The text will show only if the entire text can fit in the chip."
> - "If less than 7 characters, show the whole text."
> - "If less than half of the text will display, show the icon only."
> - "If more than half of the text will display, show as much text as possible."

`setShortCriticalText` 签名与建议长度:`public Notification.Builder setShortCriticalText(String shortCriticalText)`,**Added in API level 36**,"Suggested max length is **7 characters**, and there is no guarantee how much or how little of this text will be shown."

**`when` 驱动芯片倒计时**(Live Updates 页 When time 节):`when` 在未来 ≥ 2 分钟 ⇒ 芯片显示倒计时(官方例子:当前 10:05、`when` = 10:10 ⇒ chip 显示 **5min**);`when` 在过去 ⇒ 不显示文本;`setUsesChronometer(true)` + `setChronometerCountDown(...)` ⇒ 显示计时器("as long as it is positive");`setShowWhen(false)` ⇒ 不显示。

### 时间限制 —— **未查到,且我刻意确认过**

**官方文档没有任何「最长显示多久 / 超时降级」的文字**。我另外做了两轮源码取证,同样没有:

- `core/java/android/app/notification.aconfig`(16.0 与 QPR2 两分支):`grep -E "duration|timeout|chip"` 只命中 flag 的 description 文本,**没有任何时长类 flag**。
- SystemUI `statusbar/notification/promoted/` 整个包:`PackageDemotionInteractor.kt` 只有 `onPackageDemoted(packageName, uid)`,`PromotedNotificationsInteractor.kt` 里 `grep duration|timeout|_MS|hour|minute|expire|DISMISS` **零命中**。唯一带时间的是 `AODPromotedNotificationViewModel` 的 `RECENTLY_ALERTED_THRESHOLD` / `audiblyAlertedIconVisibleUntil`,那是 **AOD 图标**的时长,与 chip 无关。

⇒ **结论:官方未规定显示时长上限;系统不做超时降级。** 降级的实际触发者是**用户**(下拉后手动 dismiss / 在设置里关掉该应用的 promotion)。真正有硬时长的是 `dataSync` 前台服务本身(见第 4 节 6 小时上限)。

### 用户/系统开关 —— 需要,且默认开启

- `NotificationManager.canPostPromotedNotifications()`(**API 36**):"Returns whether the calling app's properly formatted notifications can appear in a promoted format... Apps can request this permission by sending the user to the activity that matches the system intent action `Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS`."
- 实操常量:`Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS` = `"android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS"`,**API 36**,需要 `EXTRA_APP_PACKAGE`;官方提示 "In some cases, a matching Activity may not exist, so ensure you safeguard against this."
  > ⚠️ **官方文档自相矛盾**:Live Updates 页写的是 `Settings.ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS`,但 **`Settings` 参考页里不存在这个常量**(`api-versions.xml` 里也查不到)。**以 `ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS` 为准。**
- `FLAG_PROMOTED_ONGOING`(API 36):"Bit to be bitwise-ORed into the `flags` field that should be set by the system if this notification is a promoted ongoing notification... **Applications cannot set this flag directly**, but the posting app and `NotificationListenerService` can read it."
- `PreferencesHelper.java` 第 189 行:`private static final boolean DEFAULT_CAN_HAVE_PROMOTED_NOTIFS = true;` ⇒ **升格默认开启**,用户只能关。

### 系统内部的真实判定逻辑(AOSP,QPR2 `NotificationManagerService.isPromotable`)

```java
if (!notification.hasPromotableCharacteristics()) return false;
if (channel.getImportance() <= IMPORTANCE_MIN) return false;
if (android.service.notification.Flags.notificationClassification()
        && NotificationChannel.SYSTEM_RESERVED_IDS.contains(channel.getId())) return false;
return true;
```

`hasPromotableCharacteristics()` 在两个分支里**语义相反**,这是最危险的一处:

```java
// android16-qpr2-release(API 36.1)
public boolean hasPromotableCharacteristics() {
    if (Flags.uiRichOngoing()) {
        return isRequestPromotedOngoing() && isOngoingEvent() && hasTitle()
            && hasPromotableStyle() && !isGroupSummary() && !containsCustomViews()
            && !isColorizedRequested();          // 不得 colorized —— 与文档一致
    } else {
        // Original promotable specs:
        if (!isOngoingEvent() || isGroupSummary() || containsCustomViews() || !hasTitle()) return false;
        if (isOngoingCallStyle()) return true;
        return isColorizedRequested() && hasPromotableStyle();  // 必须 colorized —— 与文档相反
    }
}
```

- **必须 `setColorized(true)`** 这条只存在于 `Flags.uiRichOngoing()` **关闭**的老分支里(即 16.0 出厂 Android 16 的实际行为)。
- `uiRichOngoing()` **开启**时走新分支:必须 `setRequestPromotedOngoing(true)`、**不得** `setColorized(true)` —— 这才是官方 Live Updates 页描述的那套。
- 该 flag 在 `notification.aconfig` 里是 `namespace: "systemui"` 的 aconfig flag,**由系统 OTA 决定,应用无法查询也无法控制**。
- 推论:**同时满足不了两边**。若设备跑 16.0 + `uiRichOngoing` 关闭,则新文档的「不得 colorized」反而是升格失败的原因;若开启,则老做法(colorized)失败。**在 API 36 上不要期待芯片稳定出现**,把芯片当「可能出现的增强」而非功能依赖。
- 附带发现(实测 `main` 分支 `Notification.java`,617,179 B):该分支上 **`setRequestPromotedOngoing` / `isRequestPromotedOngoing` grep 计数均为 0**、`MetricStyle` 也不存在,而 `hasPromotableCharacteristics()` 退回成纯 colorized 规则(`return isColorizedRequested() && isOngoingEvent() && hasTitle() && !isGroupSummary() && !containsCustomViews() && hasPromotableStyle();`)。⇒ **QPR2 的「显式 opt-in + 禁 colorized」方案目前只存在于发布分支,尚未进入 main**,进一步说明这是正在演进的特性,不要按它写死逻辑。

### 可照抄代码

```kotlin
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat

// 注意:setRequestPromotedOngoing / setShortCriticalText 都在 NotificationCompat.Builder 上,
// AndroidX core 1.17.0+ 提供,内部按 SDK 写 extras,不会在低版本崩。
private fun buildLiveUpdateNotification(
    context: Context,
    openAppIntent: PendingIntent,
    percent: Int,
): Notification {
    val style = NotificationCompat.ProgressStyle()
        .setStyledByProgress(false)
        .setProgress(percent.coerceIn(0, 100))
        .setProgressSegments(listOf(NotificationCompat.ProgressStyle.Segment(100)))

    return NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_pack_progress)
        .setContentTitle(context.getString(R.string.pack_progress_title))
        .setContentText(context.getString(R.string.pack_progress_text, percent))
        .setContentIntent(openAppIntent)
        // —— 升格相关 ——
        .setOngoing(true)                     // 条件 4
        .setRequestPromotedOngoing(true)      // 条件 3(compat 写 EXTRA_REQUEST_PROMOTED_ONGOING)
        .setShortCriticalText("$percent%")    // 芯片文本,建议 ≤7 字符
        .setWhen(System.currentTimeMillis() + 5 * 60_000L)  // 可选:未来时间 ⇒ 芯片倒计时
        .setShowWhen(true)
        // —— 禁止项(条件 6/7/8):不要设 ----
        // .setCustomContentView(...)   ← 禁止
        // .setGroupSummary(true)       ← 禁止
        // .setColorized(true)          ← 禁止
        .setSilent(true)
        .setOnlyAlertOnce(true)
        .setStyle(style)
        .build()
}
```

---

## 3. 通知渠道(NotificationChannel)要求

### 结论

- **唯一硬要求:importance 不得为 `IMPORTANCE_MIN`**(= 1)。官方原文第 9 条:"The notification channel must **NOT** have `IMPORTANCE_MIN`." AOSP `isPromotable` 里对应的判据是 `channel.getImportance() <= IMPORTANCE_MIN` 直接 return false。
- 常数值(`javap -constants` 实测):`IMPORTANCE_MIN = 1`、`IMPORTANCE_LOW = 2`、`IMPORTANCE_DEFAULT = 3`、`IMPORTANCE_HIGH = 4`、`IMPORTANCE_MAX = 5` ⇒ **`IMPORTANCE_LOW` 合法**。
- **没有**「Live Update 专用渠道」的要求,也**没有**额外 channel 配置项。
- **`CATEGORY_PROGRESS` 不是必需的**(见第 0 节)。设了无害。
- 另:QPR2 的 `isPromotable` 还有一条 —— 若 `notificationClassification` flag 开启,则**系统保留 ID 的渠道**不可升格(`NotificationChannel.SYSTEM_RESERVED_IDS`)。自建渠道 ID 不会撞上。

### 官方原文 + URL

- <https://developer.android.com/develop/ui/views/notifications/live-update>(9 条要求,第 9 条即渠道要求)
- <https://developer.android.com/reference/android/app/NotificationManager>(`IMPORTANCE_MIN` 等常量)
- <https://developer.android.com/develop/ui/compose/notifications/channels> —— **渠道创建后不可改 importance**,原文:"**Once you submit the channel to the `NotificationManager`, you can't change the importance level.**" 以及 "After you create a notification channel, you can't change the notification channel's visual and auditory behaviors programmatically." ⇒ 若现有渠道的 importance 需要调整,**唯一办法是换一个新渠道 ID**。

### 可照抄代码

```kotlin
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

private const val CHANNEL_ID = "pack_progress"

private fun createChannel(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    val channel = NotificationChannel(
        CHANNEL_ID,
        context.getString(R.string.pack_channel_name),
        // IMPORTANCE_LOW 合法(只要不是 IMPORTANCE_MIN)。
        // IMPORTANCE_LOW ⇒ 无声音、不弹横幅,适合持续进度的常驻通知。
        NotificationManager.IMPORTANCE_LOW,
    ).apply {
        description = context.getString(R.string.pack_channel_desc)
        setSound(null, null)
        enableVibration(false)
        setShowBadge(false)
    }
    manager.createNotificationChannel(channel)
    // 渠道一旦创建,importance 与视觉/听觉行为都不可再改(官方原文见上);
    // 需要改动时唯一办法是换一个新的渠道 ID。
}
```

---

## 4. 权限

### 结论

| 权限 | 级别 | 必需? | 说明 |
| --- | --- | --- | --- |
| `POST_NOTIFICATIONS` | **API 33,dangerous(运行时)** | **是**(targetSdk ≥ 33 时必须) | 应用发通知的基础权限;被拒则通知栏里什么都看不到(见下) |
| `POST_PROMOTED_NOTIFICATIONS` | **API 36.1** 起,`normal\|appop` | **是**(要升格就必须) | 非运行时权限,清单声明即可;但 `appop` 意味着用户可在设置里关。官方原文:"This is required **in addition to (not instead of)** `POST_NOTIFICATIONS`." |
| `FOREGROUND_SERVICE` | API 28 | 是(用前台服务就必须) | |
| `FOREGROUND_SERVICE_DATA_SYNC` | API 34 | 是(选 `dataSync` 类型就必须) | 与 `FOREGROUND_SERVICE` **两个都要** |

- `POST_NOTIFICATIONS` 被拒的后果(官方原文):"On Android 13 (API level 33) or higher, if the user denies the notification permission, they still see notices related to foreground services in the **Task Manager** but **don't see them in the notification drawer**." ⇒ 前台服务不会因此崩,但通知(以及芯片)完全不可见。
- **FGS 类型选 `dataSync` 是对的**:官方 service-types 表里 `dataSync` 行的 runtime prerequisites = "**None**",描述含 "Local file processing"、"Import or export operations" —— 与 PixFold 的 CBZ 打包完全吻合。替代的 `mediaProcessing` 同样有 6 小时上限,没有优势。
- **`dataSync` 有硬时长上限(Android 15 起)**:官方原文 —— "The system permits an app's `dataSync` services to run for a total of **6 hours in a 24-hour period**, after which the system calls `Service.onTimeout(int, int)`... At this time, the service has a few seconds to call `Service.stopSelf()`. When `Service.onTimeout()` is called, the service is **no longer considered a foreground service**. If the service does not call `Service.stopSelf()`, the system throws an internal exception."(日志原文 `Fatal Exception: android.app.RemoteServiceException: "A foreground service of type dataSync did not stop within its timeout: [component name]"`)。用尽后**再启动**会抛 `ForegroundServiceStartNotAllowedException`,消息 "Time limit already exhausted for foreground service type dataSync",除非用户把应用带回前台(重置计时)。
- **后台启动限制**:官方豁免条款逐字含 "The user performs an action on a UI element related to your app. For example, they might interact with a bubble, notification, widget, or activity." ⇒ **用户点按钮时启动前台服务是安全的**。反例:"If an app tries to start a foreground service while the app runs in the background, and the foreground service doesn't satisfy one of the exceptional cases, the system throws a `ForegroundServiceStartNotAllowedException`."

### manifest 写法

```xml
<!-- 权限 -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<!-- 36.1 起才存在;低版本声明未知权限只是被忽略,不会报错 -->
<uses-permission android:name="android.permission.POST_PROMOTED_NOTIFICATIONS" />

<application ...>
    <!-- 多类型用 | 连接,如 android:foregroundServiceType="dataSync|mediaProcessing" -->
    <service
        android:name=".data.task.PackTaskService"
        android:exported="false"
        android:foregroundServiceType="dataSync" />
</application>
```

- 官方原文:"You must declare the service type in your app manifest, and also request the appropriate foreground service permission for that type (in addition to requesting the `FOREGROUND_SERVICE` permission)."
- 未声明类型的后果:"If you try to create a foreground service and its type isn't declared in the manifest, the system throws a `MissingForegroundServiceTypeException` upon calling `startForeground()`."

### 运行时请求 `POST_NOTIFICATIONS`

```kotlin
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.*

@Composable
fun rememberNotificationPermission(): Pair<Boolean, () -> Unit> {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }
    return granted to {
        if (Build.VERSION.SDK_INT >= 33) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
```

> 注意:本项目 `minSdk = 36` ⇒ `Build.VERSION.SDK_INT < 33` 与 `< 36` 都恒 false,版本判断可省。上面保留是为可读性(以及 minSdk 可能回退的情形)。

---

## 5. 前台服务 + 通知的接线

### 结论

- **首次进入前台**:`ServiceCompat.startForeground(service, id, notification, foregroundServiceType)`。注意 `foregroundServiceType` 必须是 manifest 里声明类型的**子集**,否则抛 `IllegalArgumentException`;传 `ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST` = 用 manifest 里全部。
- **后续更新**:`NotificationManager.notify(sameId, notification)` 即可,**不必**再调 `startForeground`。官方原文:"To update a notification after you issue it, call `NotificationManagerCompat.notify()` again, passing it the **same ID** you used before."
- **结束时干净退出**:`stopForeground(STOP_FOREGROUND_REMOVE)`(等价 `ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)`)→ `stopSelf()`。`STOP_FOREGROUND_REMOVE` 的 javadoc:"if supplied, the notification previously supplied to `startForeground` will be cancelled and removed from display."
- **`ServiceCompat.startForeground` 的版本语义**:官方原文 "`startForeground` with the third parameter `foregroundServiceType` was added in `Q`. Before SDK Version `Q`, this method call should call `startForeground` without the `foregroundServiceType` parameter." compat 内部已处理,调用方不必判断。SDK ≥ UPSIDE_DOWN_CAKE 起**不允许** `FOREGROUND_SERVICE_TYPE_NONE`。
- **一个容易忽略的坑**:`setForegroundServiceBehavior(...)` 的 javadoc(AOSP):"**By default, the system can choose to defer visibility of the notification for a short time after the service is started.** Pass `FOREGROUND_SERVICE_IMMEDIATE` ... in order to guarantee that visibility is never deferred." ⇒ 想让 Live Update 芯片尽快出现,应显式 `setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)`。

### `startForeground` 会抛的四类异常(AOSP `Service.java` javadoc)

| 异常 | 触发条件 |
| --- | --- |
| `ForegroundServiceStartNotAllowedException` | API S+ 后台启动限制 |
| `InvalidForegroundServiceTypeException` | API 34+ 且 manifest 类型为 `NONE` |
| `MissingForegroundServiceTypeException` | API 34+ 且未设类型 |
| `SecurityException` | API 34+ 且缺少对应类型权限 |

### `ServiceCompat` 签名 + URL

- `public static void startForeground(@NonNull Service service, int id, @NonNull Notification notification, int foregroundServiceType)`,**Added in 1.12.0** — <https://developer.android.com/reference/androidx/core/app/ServiceCompat>
- `public static void stopForeground(@NonNull Service service, int flags)`,**Added in 1.1.0**,原文 "Remove the passed service from foreground state, allowing it to be killed if more memory is needed."
- `STOP_FOREGROUND_REMOVE = 1`,**Added in 1.1.0**
- <https://developer.android.com/develop/ui/compose/notifications/create-notification>(通知更新语义)

### 可照抄代码(Service 骨架)

```kotlin
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class PackTaskService : Service() {

    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // 进入前台:类型必须是 manifest 的子集
                startForegroundCompat(buildProgressNotification(this, percent = 0))
                // …启动协程做实际工作
            }
            ACTION_STOP -> {
                teardown()     // 见下
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(notification: Notification) {
        if (foregroundStarted) return
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            // 本项目 minSdk = 36 ⇒ 恒走 dataSync 分支(也恒 ≥ 34,不会命中 Q 以下路径)。
            // ServiceCompat 内部已处理 Q(29) 以下的降级,不必自己判断。
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        foregroundStarted = true
    }

    /** 进度更新:首次走 startForeground,之后只 notify 同 ID。 */
    private fun updateProgress(percent: Int) {
        val notification = buildProgressNotification(this, percent)
        if (!foregroundStarted) {
            startForegroundCompat(notification)
        } else {
            notifications.notify(NOTIFICATION_ID, notification)   // 同 ID 覆盖
        }
    }

    /** 干净收尾:先摘 FGS 通知,再结束服务。 */
    private fun teardown() {
        if (foregroundStarted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        stopSelf()
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.cyanix.pixfold.action.PACK_START"
        const val ACTION_STOP = "com.cyanix.pixfold.action.PACK_STOP"
    }
}
```

**终态通知的两种做法**(二选一,别混):

```kotlin
// A) 保留一条可清理的终态通知(用户点一下才消失)
private fun notifyFinal(summary: String) {
    val final = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_pack_done)
        .setContentTitle(getString(R.string.pack_done_title))
        .setContentText(summary)
        .setAutoCancel(true)
        .setOngoing(false)               // ← 必须摘掉 ongoing,否则 autoCancel 不生效
        .build()
    notifications.notify(FINAL_NOTIFICATION_ID, final)   // 注意:用不同 ID
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)  // 只摘掉 FGS 那条
}

// B) 直接什么都不留
private fun stopSilently() {
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    stopSelf()
}
```

### `onTimeout` —— 两个重载,API 级别不同

`javap` 实测 `android.app.Service` 有两个重载;`api-versions.xml` 的 `since` 值:

| 重载 | since | 用途 |
| --- | --- | --- |
| `onTimeout(int startId)` | **API 34** | Android 14 引入的短服务超时(与 FGS 类型无关) |
| `onTimeout(int startId, int fgsType)` | **API 35** | Android 15 的 FGS 类型超时(`dataSync` 6h/24h 走这条),`fgsType` 告诉你哪个类型超时 |

```kotlin
import android.os.Build
import androidx.annotation.RequiresApi

// API 35+:dataSync 累计 6h/24h 后触发,带 fgsType
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)   // 35
override fun onTimeout(startId: Int, fgsType: Int) {
    // 必须在几秒内停;否则系统抛 RemoteServiceException
    PackTaskBus.onTimeoutAborted()      // 通知 UI:被系统中断,不是用户取消
    teardown()
}
```

> ⚠️ 本项目 `minSdk = 36`:两个重载都高于 minSdk,`onTimeout(int)`(34)与 `onTimeout(int, int)`(35)都可直接覆写,无需 `@RequiresApi`。若将来 minSdk 回退到 34,`onTimeout(int, int)` 是 **API 35**,覆写它需 `@RequiresApi(35)`(否则 lint `NewApi`);`onTimeout(int)` 是 API 34,可直接覆写。两者都存在时,**系统按超时来源分别调用**,建议都覆写。方法在低版本不会被调用,覆写本身不会导致旧设备崩溃。

---

## 6. 降级兼容(API 34 / 35)

> **先看这条**:本项目 `minSdk` 已经是 **36**(`android/app/build.gradle.kts:23`,工作树未提交改动)。⇒ 所有可能安装的设备的 `Build.VERSION.SDK_INT` 都 ≥ 36,`ProgressStyle` **永远走原生分支**。本节讲的降级路径对本项目**当前不生效**,保留是为了:(a) minSdk 若回退到 34;(b) 说明为何手写 `Build.VERSION` 分支是多余的。

### 结论

**官方推荐路径 = 用 AndroidX `NotificationCompat.ProgressStyle`,让 compat 层自己降级。** 不需要你手写分支。

`NotificationCompat.ProgressStyle.apply()` 源码(androidx-main 第 6914 行)的三档行为逐字:

```java
if (Build.VERSION.SDK_INT >= 36) {
    final Notification.ProgressStyle progressStyle = new Notification.ProgressStyle();
    Api36Impl.setStyledByProgress / setProgress / setProgressIndeterminate;
    Api36Impl.setProgressStartIcon / setProgressEndIcon / setProgressTrackerIcon;
    if (Build.VERSION.SDK_INT >= 37) { Api37Impl.setProgressPoints / setProgressSegments }
    else                             { Api36Impl.setProgressPoints / setProgressSegments }
    builder1.setStyle(progressStyle);
} else {
    final int progressMax = getProgressMax();
    builder1.setProgress(progressMax, Math.min(mProgress, progressMax), mIndeterminate);
}
```

⇒ **SDK < 36 时自动退化成普通 `setProgress(max, progress, indeterminate)`**,而不是「display 什么也不显示」。

类 javadoc 也写明:"NOTE: ProgressStyle Notifications are supported on Android 36 and above. **If the SDK version is below 36, the ProgressStyle will fall back to the default notification style.**"

配套的 extras 写入策略(`addCompatExtras`):
- `SDK_INT <= 36` ⇒ 写 compat 版 `EXTRA_PROGRESS_SEGMENTS` / `EXTRA_PROGRESS_POINTS`。注释原文:"ProgressStyle segments and points are supported by the platform on SDK 36, but **new properties were added to them on SDK 37**, so we need to include their compat versions on, 36 too."
- `SDK_INT < 36` ⇒ 才写 `EXTRA_PROGRESS` / `EXTRA_PROGRESS_INDETERMINATE` / `EXTRA_PROGRESS_MAX` / `EXTRA_STYLED_BY_PROGRESS` / tracker / start / end icon。

**所以:现有 `PackTaskService.buildProgressNotification()` 里那段 `if (Build.VERSION.SDK_INT >= ANDROID_16) … else …` 手写分支是多余的**,可以直接删掉 —— compat 已经做了同样的事,而且做得更全(它会保留 segments/points 的语义)。手写分支的唯一副作用是:一旦你哪天想加 segments,那段 else 分支会让 34/35 用户丢信息。**而在 `minSdk = 36` 下,那段 else 分支是死代码,永远不可达。**

### 官方原文 + URL

- <https://developer.android.com/reference/androidx/core/app/NotificationCompat.ProgressStyle>
- <https://developer.android.com/jetpack/androidx/releases/core>(1.17.0,August 13, 2025)

### 可照抄代码(无需分支)

```kotlin
import androidx.core.app.NotificationCompat

/**
 * 一套代码兼容 API 34–37+(若 minSdk 回退到 34 时的形态)。
 * compat 在 <36 时自动降级为 setProgress(max, progress, indeterminate)。
 * 本项目 minSdk = 36 ⇒ 恒走原生分支,下面的注释只是说明兼容层的能力。
 */
private fun buildProgressNotification(context: Context, percent: Int): Notification {
    val p = percent.coerceIn(0, 100)
    val style = NotificationCompat.ProgressStyle()
        .setStyledByProgress(false)
        .setProgress(p)
        .setProgressSegments(listOf(NotificationCompat.ProgressStyle.Segment(100)))

    return NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_pack_progress)
        .setContentTitle(context.getString(R.string.pack_progress_title))
        .setContentText(context.getString(R.string.pack_progress_text, p))
        .setOngoing(true)
        .setRequestPromotedOngoing(true)   // <36 自动降级为普通 ongoing 通知
        .setShortCriticalText("$p%")
        .setSilent(true)
        .setOnlyAlertOnce(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)  // 1.7.0+;API 31 以下无效
        .setStyle(style)
        .build()
}
```

> 若确实需要手写降级(例如不想引 compat),官方给出的最低保证就是 `setProgress(max, progress, false)` + `setOngoing(true)`,配合 `setContentTitle` / `setContentText` 表达状态。**但 `ProgressStyle` 提供的 segments/points/trackerIcon 在 <36 上没有任何等价物**,信息会降级。

---

## 7. 陷阱与限制

共 18 条,按「最容易踩 → 最不容易踩」排列,每条附依据。

1. **`setRequestPromotedOngoing` 不在 API 36 里,是 36.1。**
   本地 `platforms/android-36/android.jar` 的 `Notification$Builder` 只有 `setShortCriticalText`,`javap` 实测无 `setRequestPromotedOngoing`;`android-36/data/api-versions.xml` 里该方法 **MISSING**,`android-37.0` 记 `since="36.1"`。JDiff 页 <https://developer.android.com/sdk/api_diff/36.1/changes> 也把 `setRequestPromotedOngoing`、`EXTRA_REQUEST_PROMOTED_ONGOING`、`isRequestPromotedOngoing`、`hasSummarizedContent` 四项列为 36→36.1 新增。
   ⇒ **用 AndroidX compat 版即可绕开**(它在 1.17.0 就提供,内部写 extras);直接调 framework 版则必须 `compileSdk ≥ 37` 或给 36 加 `minorApiLevel = 1`。本项目 `compileSdk = 37` 已满足。

2. **API 36 上「必须 colorized」和「不得 colorized」同时为真,取决于系统 aconfig flag。**
   `hasPromotableCharacteristics()` 在 QPR2 里分两分支:`Flags.uiRichOngoing()` 为 true ⇒ `!isColorizedRequested()` 且要求 `isRequestPromotedOngoing()`;为 false ⇒ 老规则 `isColorizedRequested() && hasPromotableStyle()`。而 16.0 分支(出厂 Android 16)只有老规则。flag 是 `namespace: "systemui"` 的 aconfig flag,应用无从查询。
   ⇒ **把芯片当增强,别当功能依赖**;`setColorized(true)` 按官方 Live Updates 页永远不要设。

3. **文档里的 `Settings.ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS` 不存在。**
   Live Updates 页写这个名字,但 `Settings` 参考页与 `api-versions.xml` 里都查不到;真实常量是 `Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS`(`"android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS"`,API 36),且需要 `EXTRA_APP_PACKAGE`。加上 "In some cases, a matching Activity may not exist" ⇒ **必须 try/catch `ActivityNotFoundException`**。

4. **`POST_NOTIFICATIONS` 与 `POST_PROMOTED_NOTIFICATIONS` 是两回事,后者不能替代前者。**
   官方原文:"This is required **in addition to (not instead of)** `POST_NOTIFICATIONS`." 前者 dangerous 运行时权限(API 33),后者 `normal|appop`(API 36.1)。用户拒了前者,前台服务仍在 Task Manager 里可见但**通知栏完全不可见**,芯片自然也没有。

5. **`dataSync` 有 6 小时 / 24 小时的硬上限,超时是崩溃不是静默降级。**
   Android 15 起:"per run for a total of 6 hours in a 24-hour period... If the service does not call `Service.stopSelf()`, the system throws an internal exception",日志形如 `android.app.RemoteServiceException: "A foreground service of type dataSync did not stop within its timeout: ..."`。用尽后再启动抛 `ForegroundServiceStartNotAllowedException`("Time limit already exhausted for foreground service type dataSync"),除非用户把应用拉回前台。
   ⇒ **必须覆写 `onTimeout(startId, fgsType)` 并立即 `stopSelf()`**;PixFold 的打包任务通常远短于此,但**批量 + 反复重试**可能累积。

6. **`POST_PROMOTED_NOTIFICATIONS` 是 `appop`,默认未必 ALLOWED。**
   `AppOpsManager.java`(QPR2 第 3404–3408 行)构造该 op 时**未调用 `.setDefaultMode(...)`**(对比同文件其他 op 会显式写 `.setDefaultMode(MODE_ALLOWED)`)⇒ 默认模式由 appop 框架决定,不能假设一定放行。而 `PreferencesHelper` 的 `DEFAULT_CAN_HAVE_PROMOTED_NOTIFS = true` 是**另一个**层面(用户偏好)的默认值。
   ⇒ 运行时用 `NotificationManager.canPostPromotedNotifications()`(API 36)判断,**不要假设清单声明了就一定升格**。

7. **前台服务通知默认可能被系统延迟显示。**
   `setForegroundServiceBehavior` javadoc:"By default, the system can **choose to defer visibility** of the notification for a short time after the service is started... Pass `FOREGROUND_SERVICE_IMMEDIATE` in order to guarantee that visibility is never deferred." ⇒ 芯片「何时出现」不可控,除非显式 `FOREGROUND_SERVICE_IMMEDIATE`。

8. **`ProgressStyle` 与 `Builder.setProgress` 不能并用。**
   类文档:"The extras set by `Notification.Builder#setProgress` will be **overridden** by the values set on this style object when the notification is built. Therefore, that method is not used with this style." ⇒ 现有代码里若同时设了二者,后者静默失效(不报错,只是不生效)。

9. **`setProgressIndeterminate(true)` 会让一堆 setter 静默失效。**
   官方列出的被忽略项:`setProgress`、`setProgressSegments`、`setProgressPoints`、`setProgressTrackerIcon`、`setStyledByProgress`。且 "If the app provides **exactly one** Segment, that segment's color will be used to style the indeterminate bar" —— 想控制不确定态颜色,就必须**恰好只给一个** Segment。

10. **`progressMax` 由 segments 长度之和决定,不是固定 100。**
    "The max progress value is the sum of all Segment lengths";"Defaults to 100 when segments are omitted"。一旦改成 `Segment(41) + Segment(559)`,progress 的上限就变成 600 —— 继续传 `percent`(0..100)会让进度条永远停在 14%。**这是最容易写出「进度条不动」bug 的地方。**

11. **芯片文本有 96dp 硬上限 + 三档截断规则,长文本会被吞。**
    "The chip has a maximum width of 96dp... If less than half of the text will display, show the icon only." 而 `setShortCriticalText` 建议 ≤ 7 字符。中文虽能塞更多信息,但仍受 96dp 约束。另外 `setShortCriticalText("")`(空串)会**强制芯片无内容**,`null` 才会回落到 when/chronometer。

12. **别重发被用户 dismiss 的 Live Update。**
    官方 Dismissal 节:"Don't repost Live Updates that the user dismissed. Use `setDeleteIntent` to detect dismissed updates." 反复重发会让用户直接关掉整个应用的 promotion 权限。

13. **`Segment` / `Point` 的 `semanticStyle` 分 framework(API 37)与 AndroidX(1.19.1)两条线,本项目当前都够不到完整组合。**
    `javap` 实测:android-37.0 的 `Segment` / `Point` 有 `getSemanticStyle()` / `setSemanticStyle(int)`,16.0 源码里没有 ⇒ framework 侧 **API 37**。AndroidX 官方参考页逐条标注 `setSemanticStyle` / `getSemanticStyle` **Added in 1.19.1**,而项目 `coreKtx = "1.19.0"`(`android/gradle/libs.versions.toml:15`)—— **差一个补丁版**。
    同页另一条:`NotificationCompat.java` 里 `grep -c MetricStyle` = 0 ⇒ **compat 完全没有 MetricStyle**;framework 侧 `MetricStyle` 是 **API 37.0**(`api-versions.xml` `since="37.0"`,android-36 jar 里不存在),别在 targetSdk 36 的代码里指望它。

14. **`NotificationCompat.ProgressStyle` 在 `SDK_INT <= 36` 时会额外写 compat extras。**
    `addCompatExtras` 注释:"ProgressStyle segments and points are supported by the platform on SDK 36, but **new properties were added to them on SDK 37**, so we need to include their compat versions on, 36 too." ⇒ 36 上应用设的 `semanticStyle` 之类新属性会走 compat extras 通道,由系统侧读取;这也是为何 compat 降级路径比手写更准确。

15. **`setForegroundServiceBehavior` 在 compat 里是 1.7.0,不是 1.5.0;且 `S`(API 31)以下无效。**
    官方参考页:`FOREGROUND_SERVICE_IMMEDIATE` **Added in 1.7.0**;framework 侧 `Notification.FOREGROUND_SERVICE_IMMEDIATE = 1` 是 **API 31**(`api-versions.xml` `since="31"`)。`NotificationCompat.Builder.setForegroundServiceBehavior(int)` 的 AOSP javadoc 原文:"This method has no effect when running on versions prior to `S`." 本项目 minSdk 36 已满足。

16. **服务收尾漏一步 = 通知栏留僵尸通知或服务不销毁。**
    `stopForeground(STOP_FOREGROUND_REMOVE)` 只摘通知,**不会**结束服务(`Service.stopSelf()` 才结束);反之 `stopSelf()` 若不先 `stopForeground(..., REMOVE)`,通知可能残留到服务销毁。两个都要调,顺序建议先 `stopForeground` 再 `stopSelf`。

17. **`onTimeout` 有两个重载,版本不同,别只覆写一个。**
    `javap` + `api-versions.xml` 实测:`onTimeout(int startId)` **since="34"**(Android 14 短服务超时);`onTimeout(int startId, int fgsType)` **since="35"**(Android 15 的 FGS 类型超时,`dataSync` 6h/24h 走这条)。只覆写前者,在 35+ 上收到的是后者,超时处理会落空。

18. **`setOngoing(false)` 是 `autoCancel` 生效的前提。**
    终态通知若要「点一下消失」,必须 `setOngoing(false)` + `setAutoCancel(true)`;还在 `ongoing` 状态的通知点不掉。同时注意升格条件 4 要求 `ongoing` —— **终态通知本来就不该再升格**,这是设计上的一致而非冲突。

---

## 附:一句话接线清单

对本项目(`PackTaskService.kt`,376 行)的最小改动集:

1. `AndroidManifest.xml`(**已实测**:第 19–21 行已声明 `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` / `POST_NOTIFICATIONS`;第 73–76 行 `<service ... android:foregroundServiceType="dataSync" />` 已正确):**只需新增** `<uses-permission android:name="android.permission.POST_PROMOTED_NOTIFICATIONS" />`。
2. `createChannel()`:`IMPORTANCE_LOW` **保持不动**(第 242 行,合法 —— 只要不是 `IMPORTANCE_MIN` = 1)。
3. `buildProgressNotification()`:删掉 `if (Build.VERSION.SDK_INT >= ANDROID_16)` 手写分支(第 279 行;compat 已做,且 `minSdk = 36` 下 else 分支是死代码);加 `.setRequestPromotedOngoing(true)` + `.setShortCriticalText(...)` + `.setForegroundServiceBehavior(FOREGROUND_SERVICE_IMMEDIATE)`。
4. 收尾:补 `ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)`(**已实测全文件无 `stopForeground` 调用**)再 `stopSelf()`。
5. 新增 `onTimeout`:两个重载都覆写 —— `onTimeout(startId: Int)`(API 34)与 `onTimeout(startId: Int, fgsType: Int)`(API 35,`dataSync` 6h/24h 走这条);收到后立即 `stopSelf()` 并告知 UI「被系统中断」。
6. 通知权限:入口处检查 `POST_NOTIFICATIONS`;升格状态用 `NotificationManager.canPostPromotedNotifications()`(API 36,`minSdk = 36` 下无需版本判断)判断,**不做硬依赖** —— 芯片出不出现最终由系统 aconfig flag + 用户设置决定。
