# 工作流 C：就地更新已有 CBZ 的元数据 —— 设计规格

> **状态**：**已按用户授权自行裁决**（用户 `m07523`：「今天依旧做到真机就可以WINDOWS关机，明天再授权核对，在这之前怎么实现你来决定，写到任务里避免你又忘记关机？」）⇒ 三个方案问题已于 `m06900` 获批准，本规格的落地细节由我裁决并逐条写明理由。
> **日期**：2026-10-26
> **产物类型**：设计规格（brainstorming 的 Architectural 路径产物，spec 之后才进 `writing-plans`）
> **产品权威**：`HANGOFF.md`（本文件只是实现级规格，与 HANGOFF 冲突时以 HANGOFF 为准）
> **关联**：`docs/superpowers/specs/2026-10-25-workspace-list-design.md`（结构模板）、`docs/phases/d4-mvp.md`（D4 台账）

## 1. 背景与范围

### 1.1 背景

打包链（D3 / D4b）已经跑通：扫目录 → 定元数据 → 写 CBZ。但**已存在的 CBZ 是只读的死物** —— 用户改不了里面的 `ComicInfo.xml`，除非重新打一遍包。重新打包有两个问题：

1. **会重排页**。打包按 `pageOrder` 重命名页条目为 `0001.jpg` 起（`ComicInfoXml.kt:76 renumberPages`）。用户只想改作者名，却顺带把页名重排了。
2. **要求源目录还在**。CBZ 可能是别处拷来的、源目录早删了，此时根本没有「再打一次」的可能。

工作流 C 补的就是这一格：**给已有 CBZ 就地更新 `ComicInfo.xml`，页条目一个字节不动**。

### 1.2 本阶段范围

| 项 | 内容 |
| --- | --- |
| 入口 | **两处**（用户 `m06900` 裁决 Q1 = 丙）：库页卷行 + 元数据页底栏 |
| 作用对象 | **单卷**（Q2 = 甲）。不做批量 |
| 改什么 | 只改 `ComicInfo.xml` 这一个条目的**内容**；页条目、条目名、条目顺序一律原样保留 |
| 不改什么 | 页重编号、页增删、CBZ 改名、输出目录变更 |
| 失败回退 | **三段式落盘 + 备份**（Q3 = 甲），见 §7 |

### 1.3 明确不在本阶段范围

| 不做 | 理由 |
| --- | --- |
| 批量更新（多卷一次） | 每卷是一次整包重写（4.7 GiB 包就要读写 4.7 GiB）。批量会让「等多久」不可预期，且部分成功的回滚语义复杂。用户已裁 Q2 = 甲 |
| 撤销 | 原包被替换后旧内容只剩 `.bak`，而 Q3 甲要求成功后删 `.bak` ⇒ **天然不可撤销**。改用**执行前确认框**承担告知责任（§9.3） |
| 只改一个条目（真·就地） | ZIP 格式上做不到。见 §2.1 |
| 改页内容 / 加页删页 | 那是工作流 B 的事 |
| 改 `cbzFileName` / 输出目录 | 那是改名 + 移动，与元数据无关 |
| 读取 DEFLATED 条目的原文做校验 | 会引入「更新元数据顺带校验了所有图」的隐性大开销 |

## 2. 已核实的硬约束（读源码确认，非推测）

### 2.1 CBZ 是 ZIP：无法只改一个条目

ZIP 的中央目录为每个条目记录 **偏移量 / 压缩后大小 / CRC-32**。改一个条目的字节长度，后面所有条目的偏移量都要重算，中央目录整体重写 —— 而 SAF 给的是 `InputStream`/`OutputStream`，**没有随机写**能力（`android/app/src/main/kotlin/com/cyanix/pixfold/data/saf/SafPackPlatform.kt` 里全部是 `openInputStream`/`openOutputStream`）。

三条候选路：

| 路 | 结论 |
| --- | --- |
| 尾部追加一个同名 `ComicInfo.xml` | ❌ ZIP 对重名条目的行为**未定义**，不同解压器取第一个还是最后一个不一致。会破坏「`ComicInfo.xml` 恒为第 0 条」契约（§2.2） |
| 原地覆写 + 重算 CRC | ❌ 要求新 XML 不比旧的长（否则撑破），且要求 SAF 随机写 |
| **流式整包重写** | ✅ 采取。峰值内存 = 64 KiB 缓冲（与 `CbzWriter` 同一策略） |

代价必须明说：**4.73 GiB 的包只改一行元数据，也要读写 4.73 GiB**。这不是实现缺陷，是 ZIP + SAF 的物理下限。

### 2.2 `ComicInfo.xml` 恒为第 0 条（兼容性契约）

`android/domain/src/test/kotlin/com/cyanix/pixfold/domain/pack/CbzReaderCompatTest.kt:103` 断言：

```kotlin
assertEquals(expected, names, "中央目录的条目顺序必须等于 ComicInfo + 页序")
```

`CbzWriter` 的 KDoc 也写明理由：**阅读器常只读第一条取元数据**。因此重写时**不能保留旧 `ComicInfo.xml` 的物理位置**（第三方包可能把它放在第 5 条），必须：丢弃旧条目 → 新 XML 写在最前 → 其余条目按**旧包原顺序**跟进。

### 2.3 产出必须全 STORED

`CbzReaderCompatTest.kt:150` 断言 `all entries are stored rather than deflated`。`CbzWriter` 用 `zip.setLevel(0)` 保证。

这条对工作流 C 有**直接**后果：STORED 条目在写的时候必须提前知道 `size` 与 `crc`（否则 `ZipException: STORED entry missing size, compressed size, or crc-32`）。见 §2.4。

### 2.4 读取侧：STORED 能白拿 size/crc，DEFLATED 不能

`ZipInputStream.getNextEntry()` 给的 `ZipEntry`：

| 输入条目类型 | `entry.size` | `entry.crc` | 结论 |
| --- | --- | --- | --- |
| STORED | 真实值 | 真实值 | **直接复用**，边读边写，零额外成本 |
| DEFLATED | `-1` | `-1` | **必须先读完才知道** ⇒ 需要落盘或读两遍 |

自家 `CbzWriter` 产出的包全是 STORED ⇒ **典型路径单遍、零 spill**。第三方包才付代价。

### 2.5 领域层已有 ZIP 写入能力，读取能力在平台层

- `android/domain/src/main/kotlin/com/cyanix/pixfold/domain/pack/CbzWriter.kt`：`java.util.zip.ZipOutputStream`，全 STORED，`zip.finish()` 而非 `close()`（输出流所有权归调用方）。
- `android/domain/src/main/kotlin` 里**没有** `ZipInputStream` / `ZipFile` 的任何使用者。
- 全工程唯一 ZIP 读取点在平台层：`android/app/src/main/kotlin/com/cyanix/pixfold/data/saf/SafPackPlatform.kt` 的 `SafExistingComicInfoProbe`（`ZipInputStream`，最多扫 16 个条目）。

⇒ **本规格要在领域层新增第一个 ZIP 读取器**。它不碰 Android API，可纯 JVM 单测，因此放在 `domain/pack/` 是对的。

### 2.6 SAF 下建临时文件的语法

`SafCbzOutputSink.createDocument` 的存在证明这条路可行：`DocumentsContract.createDocument(resolver, parentUri, mime, displayName)` 在目标目录下建文件。临时包**必须与目标包同目录** —— `DocumentsContract.renameDocument` 只支持同目录改名（跨目录要 `moveDocument`，不同 provider 支持度差异大）。

### 2.7 `renameDocument` 可能返回 null

`android/app/src/main/kotlin/com/cyanix/pixfold/data/saf/SafRenamePlatform.kt:58` 的 KDoc 已记录：契约允许 provider 返回 null，且 `ExternalStorageProvider` 改名后 document uri **会变**（id 由相对路径派生）。工作流 C 不保存 uri（改完即弃），但必须处理 null。

### 2.8 空间无法判定 ≠ 空间充足

`android/app/src/main/kotlin/com/cyanix/pixfold/data/saf/SafPackPlatform.kt:367 FilesystemSpaceSource.availableBytesOf` 只对 `com.android.externalstorage.documents` 且 docId 以 `primary:` 开头的树给出确定值，其余返 `null`。项目既有立场（AGENTS.md D2 决策 ⑥）：**`null` 表示「无法判定」而非 0，不阻断但要告知**。

### 2.9 生产代码零 Snackbar

`android/app/src/main/kotlin/com/cyanix/pixfold/ui/library/LibrarySteps.kt` 的 `InterruptedNoticeRow` KDoc 论证过为什么不用 Snackbar（4 秒自动消失，而它讲的是磁盘上可能有不完整文件）。工作流 C 的结果反馈沿用同一立场：**用弹框，不用自动消失的东西**（§9.4）。

### 2.10 `PackProgressDialog` 可直接复用

`android/app/src/main/kotlin/com/cyanix/pixfold/ui/components/PackProgressDialog.kt:91`：

```kotlin
fun PackProgressDialog(
    progress: TaskProgress, pageDone: Int?, pageTotal: Int?,
    onCancel: () -> Unit, modifier: Modifier = Modifier,
    cancelling: Boolean = false, volumeLabel: String? = null,
)
```

外环画「当前单元内的细粒度进度」、内环画「整体进度」，两次取消确认由组件内部持有。工作流 C 只有一个包 ⇒ `TaskProgress(total = 1)`、外环 = 已转写条目 / 总条目、内环 = 0 或 1。**零新组件**。

### 2.11 UI 组件新增参数必须有默认值

`LibraryPage` / `MetadataPage` / `VolumeRow` 的新参数一律给默认值（
`onUpdateMeta: ((ComicVolume) -> Unit)? = null`）。

**为什么**：`LibraryPage` 的 KDoc 已写明「默认值让既有测试一行都不用改」是既定做法（`LibraryPage.kt:95` 的 `onNext` / `workspaceState` / `onPickWorkspace` / `onOpenWorkspaceList` / `workspaceWarnings` 五个参数全部带默认值）。工作流 C 的新入口是**可选动作**，不给默认值会让 12 个测试文件因为「多一个必填参数」而红 —— 那不是改动的语义。

### 2.12 可复用的既有字符串键（已核实，别新增重复项）

| 需要 | 复用 | 出处 |
| --- | --- | --- |
| 「取消」 | `R.string.common_cancel` | `PlanComponents.kt:191` / `ExecutionReportPage.kt:322` / `TaskProgressBar.kt:139` 等 |
| 「确定 / 好的」 | `R.string.domain_general_confirm_ok` | `PlanComponents.kt:190` |
| 进度弹窗的取消与二次确认 | `R.string.pack_progress_cancel` / `pack_progress_cancel_confirm_ok` | `PackProgressDialog.kt:243` / `:118` |

**`android.R.string.ok` 在本工程零使用**（grep 实测）⇒ 不要引入平台默认文案，那会让中英文与全站不一致。

## 3. 用户裁决清单（逐条带原文）

| # | 裁决 | 原话 |
| --- | --- | --- |
| Q1 | 入口 = **丙**（库页卷行 + 元数据页底栏两处） | `User said (m06900)`：「按你的想法来吧，我觉得可以」 |
| Q2 | 范围 = **甲**（单卷） | 同上 |
| Q3 | 失败回退 = **甲**（临时文件 → 原包改名 `.bak` → 临时改名原名 → 删 `.bak`；`renameDocument` 不支持时退化乙：删原包 → 临时改名原名） | 同上 |
| — | 今日只做到真机就绪，真机核对需**明天重新授权** | `User said (m07523)`：「今天依旧做到真机就可以WINDOWS关机，明天再授权核对，在这之前怎么实现你来决定，写到任务里避免你又忘记关机？」 |

### 3.1 本规格新做的裁决（用户授权「怎么实现你来决定」）

| # | 裁决 | 理由 | 错了的代价 |
| --- | --- | --- | --- |
| D1 | **DEFLATED 输入落 spill 到 `cacheDir`，不读两遍** | 自家包全 STORED ⇒ 典型路径零成本；第三方包落 spill = 4.7 GiB 写 + 读，读两遍 = 9.4 GiB 读。前者可预估（预检时告知），后者对用户完全不可预期 | spill 空间不足时整体失败（原包未动），代价是「更新不了」，不是数据损失 |
| D2 | **不提供撤销** | Q3 甲要求成功后删 `.bak` ⇒ 无可回滚的载体。造假撤销比没有更坏 | 用户改错了只能重新编辑再更新一次。**由确认框承担告知责任**（§9.3 必须写「无法撤销」） |
| D3 | **页条目名原样保留，不重编号** | 工作流 C 的承诺是「只改元数据」。重编号会让「改个作者名」偷偷重排页 | 用户可能保留了有意义的页名（`cover.jpg`），重编号会毁掉它 |
| D4 | **结果反馈用弹框，不进 `ExecutionReport`** | `ExecutionReport` 是为「多项执行 + 撤销档位」设计的。单卷单动作套上去要造假 entries | 多一点 UI 代码；换来的是不污染报告链 |
| D5 | **完成前不写任何字节到目标包** | 临时包写完并验证长度 > 0 才动原包。中途取消 / 失败一律删临时包 | — |
| D6 | **入口只在 `volume.outputExists == true` 时出现** | `outputExists` 是扫描期事实（`markOutputExists` 填）。为 false 时「更新已有 CBZ」这句话本身不成立 | 用户少一个入口；但错误的入口会让用户点了才失败 |

## 4. 数据模型

### 4.1 领域层新类型（`android/domain/src/main/kotlin/com/cyanix/pixfold/domain/pack/CbzMetaUpdate.kt`）

```kotlin
/** 一次「就地更新元数据」的计划。全部字段都是纯数据/回调，可跨进程传递。 */
data class CbzMetaUpdatePlan(
    /** 要改的那一卷。体积、页数等仅供进度显示。 */
    val volumeId: String,
    /** 新的 ComicInfo.xml 全文（唯一产出点是 [ComicInfo.build]）。 */
    val comicInfoXml: String,
    /** 目标包在**它自己所在目录**里的显示名（= volume.cbzFileName）。 */
    val targetDisplayName: String,
    /** 包内条目总数（含 ComicInfo.xml），仅用于进度条分母。扫描期未知，进执行期才回填。 */
    val entryCount: Int,
) {
    init {
        require(comicInfoXml.isNotEmpty()) { "comicInfoXml 不能为空（内容为空的 ComicInfo 是缺陷，不是清空）" }
        require(targetDisplayName.isNotEmpty()) { "targetDisplayName 不能为空" }
    }
}
```

**为什么没有 `pages` 字段**：工作流 C 不碰页 —— 页的内容与名字都从旧包里读。带上 `pages` 会诱使实现者去调 `renumberPages`（违反 D3）。

**为什么 `entryCount` 可写但初值常有 0**：建计划时只能从 `SafExistingComicInfoProbe` 知道「有没有 ComicInfo」，不知道条目总数（数完要把整包读一遍）。所以它是 `var`？—— 不，用 `copy(entryCount = n)` 在扫描后回填，保持 data class 不可变。初值 0 表示未知，进度条此时显示不确定态（与 `PackProgressDialog` 的 `pageTotal == null` 同构）。

### 4.2 结果类型

```kotlin
sealed interface CbzMetaUpdateOutcome {
    /** 成功：目标包已含新 XML。 */
    data class Updated(val entryCount: Int, val xmlBytes: Int) : CbzMetaUpdateOutcome
    /** 原包未动（临时包写失败 / 空间不足 / 取消）。 */
    data class Failed(val reason: UpdateFailure, val detail: String?) : CbzMetaUpdateOutcome
}

enum class UpdateFailure {
    /** 目标包找不到（用户刚在别的应用里删了）。 */
    TargetMissing,
    /** 目录授权失效。 */
    PermissionLost,
    /** 可用空间不足以写临时包。 */
    NoSpace,
    /** 包内没有可识别的条目 / 是坏包。 */
    NotAZip,
    /** 写临时包途中读原包失败。 */
    ReadFailed,
    /** 临时包写完了，但替换原包失败（此时原包状态未知，见 §7.4）。 */
    ReplaceFailed,
    /** 用户取消。 */
    Cancelled,
}
```

**为什么 `Failed` 要分 `reason`**：六种失败的用户动作完全不同（重选目录 / 腾空间 / 换个包 / 直接放弃）。合成一句「更新失败」等于让用户猜。

### 4.3 平台层端口

```kotlin
/** 就地更新一个 CBZ 的元数据。所有实现都是阻塞 IO，调用方负责放到 Dispatchers.IO。 */
interface CbzMetaUpdater {
    /** 目标包是否存在（建计划前问一次）。 */
    fun targetExists(volume: ComicVolume): Boolean

    /**
     * 预检：会不会因为空间不足而失败。
     * 返回 null = 无法判定（与 `FilesystemSpaceSource.availableBytesOf` 同语义）。
     */
    fun preflight(plan: CbzMetaUpdatePlan, volume: ComicVolume): UpdatePreflight

    /**
     * 执行。可在**条目边界**取消（见 §7.6），取消时临时包被删除、原包一个字节未动，
     * 结果为 [CbzMetaUpdateOutcome.Failed] + [UpdateFailure.Cancelled]。
     * 进度按已处理条目数回调。
     */
    fun update(
        plan: CbzMetaUpdatePlan,
        volume: ComicVolume,
        shouldStop: () -> Boolean,
        onProgress: (done: Int, total: Int) -> Unit,
    ): CbzMetaUpdateOutcome
}

sealed interface UpdatePreflight {
    /** 可以做，还会顺带告知预计的读写量。 */
    data class Ready(val estimatedReadBytes: Long?, val estimatedWriteBytes: Long?) : UpdatePreflight
    /** 空间不够。 */
    data class NoSpace(val requiredBytes: Long, val availableBytes: Long) : UpdatePreflight
    /** 无法判定空间，但允许继续。 */
    object Unknown : UpdatePreflight
}
```

**为什么接 `shouldStop: () -> Boolean` 而不是 `CancellationToken`**：领域层不该依赖 `domain/plan` 的取消令牌（那是打包链的类型），一个无参布尔回调足够，测试里可直接给 `{ true }`。

**为什么只允许在条目边界停**：转写期间原包**一个字节都没动**，全部写进临时包 ⇒ 任何时刻停下，只要删临时包就回到起点。但停在**条目中间**会让临时包处于半写状态，ZipOutputStream 的中央目录不完整，清理时还要处理未 finish 的内部状态。放在条目边界，取消点就是「上一张图刚复制完」，清理是纯粹的 `deleteDocument`。**这个语义必须写进 KDoc**，否则后人会以为可以中途硬停（打包链的 `CancellationToken.shouldStop` 确实只在项开始前检查，见 AGENTS.md D2 决策 ⑦）。

### 4.4 领域层纯函数（新增）

```kotlin
/**
 * 把旧条目的原始信息交给重写器决策。
 *
 * 单独抽出来是为了可测：判断「这条要不要保留 / 是页还是元数据」的规则
 * **不应该藏在 IO 代码里** —— 一旦藏进去，只能靠构造真实 ZIP 才能断。
 */
data class SourceEntry(
    val name: String,
    /** STORED 直写时非 null；DEFLATED 为 null。 */
    val size: Long?,
    val crc: Long?,
)

/** 该条目是不是元数据条目（大小写不敏感，只看 basename —— 第三方包可能带目录前缀）。 */
fun isComicInfoEntry(name: String): Boolean =
    name.substringAfterLast('/').equals(CbzWriter.COMIC_INFO_ENTRY, ignoreCase = true)

/** 目标包重写后的条目名序列（ComicInfo 在最前，其余保持旧顺序且**不含**任何旧 ComicInfo）。 */
fun rewrittenEntryOrder(oldNames: List<String>): List<String> =
    listOf(CbzWriter.COMIC_INFO_ENTRY) + oldNames.filterNot { isComicInfoEntry(it) }
```

`rewrittenEntryOrder` 是纯函数 ⇒ 用「第三方包把 ComicInfo 放在第 5 条」当输入就能断出「新顺序是 ComicInfo + 前 4 条 + 第 6 条起」，不必构造真 ZIP。

## 5. 接口变更

| 文件 | 变更 |
| --- | --- |
| `android/domain/src/main/kotlin/com/cyanix/pixfold/domain/pack/CbzMetaUpdate.kt` | **新建**。§4.1 / §4.2 / §4.3 / §4.4 的全部类型与纯函数 |
| `android/domain/src/main/kotlin/com/cyanix/pixfold/domain/pack/CbzMetaRewriter.kt` | **新建**。流式转写核心（§6） |
| `android/domain/src/main/kotlin/com/cyanix/pixfold/domain/text/TextKey.kt` | 追加 8 个键（§5.1） |
| `android/app/src/main/kotlin/com/cyanix/pixfold/data/saf/SafMetaUpdater.kt` | **新建**。`CbzMetaUpdater` 的 SAF 实现（§7） |
| `android/app/src/main/res/values/strings_library.xml` + `values-zh/strings_library.xml` | 各追加 10 条文案（§5.2；取消与「确定」复用既有键） |
| `android/app/src/main/res/drawable/ic_edit_note.xml` | **新建** VectorDrawable（项目不引入 material-icons，见 AGENTS.md） |

### 5.1 `TextKey` 追加（8 个）

```kotlin
// ---------------- 工作流 C：就地更新已有 CBZ 的元数据 ----------------
/** 「更新已有 CBZ 的元数据」——按钮与弹窗标题共用。 */
UpdateMetaTitle,
/** 「这个操作会重写整个压缩包（%1$s），且**无法撤销**。」args = [包大小文案] */
UpdateMetaSizeWarning,
/** 「压缩包内共 %1$d 个条目，其中元数据 1 个、页 %2$d 个。」args = [总数, 页数] */
UpdateMetaEntrySummary,
/** 六条失败原因。 */
UpdateFailTargetMissing, UpdateFailPermissionLost, UpdateFailNoSpace,
UpdateFailNotAZip, UpdateFailReadFailed, UpdateFailReplaceFailed,
/** 「已更新 %1$s 的元数据（%2$d 个条目原样保留）。」args = [包名, 页数] */
UpdateDoneSummary,
```

**为什么失败原因进 `TextKey` 而不是直接用字符串资源**：`UpdateFailure` 是领域层的枚举（§4.2），领域层不能引用 `R.string`。既有做法是枚举带 `textKey`（见 `LangChoice` / `MetaField` / `ExecKind`），这里沿用。

### 5.2 字符串资源（10 条新增，`values` 与 `values-zh` 必须同步）

| key | en | zh |
| --- | --- | --- |
| `update_meta_action` | `Update metadata` | `更新元数据` |
| `update_meta_action_cd` | `Update metadata of the existing archive` | `更新已有压缩包的元数据` |
| `update_meta_title` | `Update metadata` | `更新元数据` |
| `update_meta_body_size` | `This rewrites the whole archive (%1$s). This cannot be undone.` | `这会重写整个压缩包（%1$s），且无法撤销。` |
| `update_meta_body_entries` | `%1$d entries will be copied as-is; only ComicInfo.xml changes.` | `%1$d 个条目将原样复制，只有 ComicInfo.xml 会改。` |
| `update_meta_unknown_size` | `unknown size` | `大小未知` |
| `update_meta_confirm` | `Update` | `更新` |
| `update_meta_progress_title` | `Updating metadata` | `正在更新元数据` |
| `update_meta_done_title` | `Updated` | `已更新` |
| `update_meta_done_body` | `%1$s now carries the new ComicInfo.xml. %2$d entries were kept unchanged.` | `%1$s 已写入新的 ComicInfo.xml，%2$d 个条目原样保留。` |
| `update_meta_fail_title` | `Could not update` | `更新失败` |

**为什么 `update_meta_body_size` 必须带「无法撤销」**：D2 裁决删掉了撤销能力，这是工作流 C **唯一**不可逆的地方。不写在主文案里（藏在展开的详情里）等于没告知。

## 6. 领域层核心：`CbzMetaRewriter`

### 6.1 签名

```kotlin
object CbzMetaRewriter {
    /**
     * 把 [input] 这个 ZIP 流原样转写到 [out]，只把 ComicInfo.xml 换成 [comicInfoXml]。
     *
     * - 其余条目：内容逐字节复制，条目名与**相对顺序**不变；
     * - 新 ComicInfo.xml 恒为第一条；
     * - 全部条目 STORED（[CbzReaderCompatTest] 的兼容契约）；
     * - 不关闭 [input] / [out]（所有权归调用方，与 [CbzWriter] 同一立场）；
     * - [spillDir] 用于 DEFLATED 条目的中转（见 §6.3）；null 表示不允许 spill
     *   ⇒ 遇到 DEFLATED 抛 [IllegalStateException]。
     *
     * @return 重写后的条目总数（含 ComicInfo.xml）
     */
    fun rewrite(
        input: InputStream,
        out: OutputStream,
        comicInfoXml: String,
        timeMillis: Long,
        spillDir: File?,
        onEntryWritten: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int
}
```

**为什么传 `spillDir: File?` 而不是让实现自己找 `cacheDir`**：领域层不能引用 `Context`。平台层给路径，领域层只负责用。

**为什么返回条目数**：进度条分母在执行前不知道（§4.1），执行完就知道真实值 —— 用它回填到结果里，用户看到的「%d 个条目」与磁盘实际一致。

### 6.2 转写顺序（单遍）

```
1. 写新 ComicInfo.xml（第一条，STORED，size/crc 现算 —— 与 CbzWriter.stored 同款）
2. while (ZipInputStream.getNextEntry()) {
     a. 若 isComicInfoEntry(entry.name) → 丢弃（continue，不写）
     b. 否则 → 复制到输出：
          - STORED 且 size/crc 可知 → putNextEntry 后直接 write，一遍
          - 否则 → 落 spill（§6.3）
   }
3. zip.finish()   // 不是 close
```

**为什么第一遍就写 XML 而不是最后补**：ZipOutputStream 是顺序追加的，没有「回头改第 0 条」。而 §2.2 要求 ComicInfo 在最前 ⇒ 必须**先写**。这带来的后果：无论旧包把 ComicInfo 放在哪，新包都是第 0 条 —— 正是我们要的。

### 6.3 DEFLATED 条目：spill 到临时文件

ZipOutputStream 对 STORED 条目必须在 `putNextEntry` 前设好 `size`/`crc`。DEFLATED 输入给不出这两个值 ⇒ 两条路：

| 方案 | 成本 | 结论 |
| --- | --- | --- |
| 读两遍输入流 | 需要 seek —— SAF 的 InputStream **不支持** | ❌ 不可行 |
| 缓冲到内存 | 一张 20 MB 的图 = 20 MB 堆；100 张就是 OOM | ❌ D2b 踩过（`OutOfMemoryError`） |
| **落 spill 文件** | 逐字节写盘再读回，峰值内存 64 KiB | ✅ D1 裁决 |

`spillDir` 里每个 DEFLATED 条目用一个临时文件，写完立刻读回复制、随即删除（**不累积**）。因此 spill 的峰值占用 = 最大单个 DEFLATED 条目，不是整包。

**为什么可以只 spill 一个**：ZipInputStream 是顺序流，处理完一个条目就能删掉它的 spill 文件。不需要同时保留多个。

### 6.4 逐条目的 `timeMillis`

与 `CbzWriter` 一致：调用方给显式时间戳，保证可复现。**旧条目的 mtime 不保留** —— `ZipEntry.getTime()` 在 ZipInputStream 下通常可读，但保留它会让「同样的输入 + 同样的 XML」产出不同字节（旧包 mtime 各异），破坏可复现性。这条要在 KDoc 写明，否则后人会当缺陷修。

### 6.5 STORED 与 DEFLATED 混合时的 `setLevel`

`CbzWriter` 用 `zip.setLevel(0)` 全局设 STORED。`ZipOutputStream` 的语义是：`setLevel(0)` 后**每个**条目都 STORED，且此时 `putNextEntry` 必须带 size/crc。

⇒ 转写器对 DEFLATED 输入也必须先算齐 size/crc 再 `putNextEntry` —— 这正是 §6.3 的 spill 要解决的。**不存在「DEFLATED 输入 → 输出也 DEFLATED」这条路**，因为 §2.3 要求全 STORED。

## 7. 平台层：`SafMetaUpdater`

文件：`android/app/src/main/kotlin/com/cyanix/pixfold/data/saf/SafMetaUpdater.kt`

```kotlin
/**
 * `CbzMetaUpdater` 的 SAF 实现。
 *
 * 所有方法都是阻塞 IO，调用方负责放到 `Dispatchers.IO`（与 `AndroidSafSource` 同一约定）。
 */
class SafMetaUpdater(
    context: Context,
    private val treeUri: Uri,
    private val space: FilesystemSpaceSource,
    private val dirOfVolume: (ComicVolume) -> String,
    private val now: () -> Long = System::currentTimeMillis,
) : CbzMetaUpdater
```

`dirOfVolume` 与 `SafExistingComicInfoProbe` 同款：`ComicVolume` 只有相对目录（`outputDir`），把它解析成 document URI 需要逐段下钻，这段逻辑属于平台层。

### 7.1 定位目标包

三步：`parentDocumentUri(dirOfVolume(volume))` → 在 parent 下按 `volume.cbzFileName` 精确查子项 → 拿到 document URI。

这段逻辑与 `SafCbzOutputSink` 里的 `parentDocumentUri` / `findChild` / `queryChildDocId` 完全相同（`SafPackPlatform.kt:126` / `:148` / `:158`）。**裁决 D8：抽成同文件内的 `internal` 顶层函数，两处共用**，不做复制。

理由：这三段是纯 SAF 查询逻辑，复制一份的代价不是「多 70 行」而是「provider 兼容性修复要改两处，而且第二处不会有人记得」。守卫：`SafCbzOutputSink` 的既有断言必须全绿（行为不变，只是 body 换成一行委托）。若发现某条断言依赖私有可见性而红，退回复制并在两处加交叉引用注释 —— **不允许改断言来迁就**。

### 7.2 建临时包

```kotlin
val tmpName = "${'$'}{volume.cbzFileName}.updating"
val tmpUri = DocumentsContract.createDocument(resolver, parentUri, MIME_CBZ, tmpName)
    ?: return Failed(ReplaceFailed, "无法在目标目录创建临时文件")
```

**为什么临时包放同一个目录**：`DocumentsContract.renameDocument` 只在**同一 parent** 内改名。跨目录要 copy+delete，那会翻倍 IO 且中断后留两份。

**为什么名字带 `.updating` 后缀**：明确表达「正在进行中」；万一进程被杀留下残骸，用户看到名字能判断出该删。它仍以 `.cbz` 开头 ⇒ 文件名排序时紧贴目标包旁边，用户容易找到。

### 7.3 三段式替换（Q3 甲）

| 步 | 动作 | 失败后的原包状态 | 处理 |
| --- | --- | --- | --- |
| 1 | 原包 → `原名.cbz.bak` | **完好**（只是名字变了） | 删临时包；报 `ReplaceFailed`。无副作用 |
| 2 | 临时包 → 原名 | 名字仍在 `.bak` 上，内容完好 | 把 `.bak` 改回原名（回滚）；删临时包；报 `ReplaceFailed` |
| 3 | 删 `.bak` | **已是新包** | **算成功**，但 detail 带上「残留原名.cbz.bak」 |

**步骤 3 失败为什么算成功**：此时用户的包已经是带新元数据的完整包，能正常打开能正常读。因为删不掉一个旧备份就报「更新失败」，会让用户以为要重来一遍 —— 那才是真的糟。残留的 `.bak` 由 UI 在完成弹框里单独提一句（§8.5）。

**回滚（步骤 2 之后）也必须验证**：把 `.bak` 改回原名同样可能失败。此时状态是「包还在、只是叫 `原名.cbz.bak`」⇒ 报 `ReplaceFailed` 且 detail 必须**点名那个 .bak 文件名**，否则用户会以为包丢了。这是 §7.4 里唯一需要人工介入的路径。

### 7.4 替换失败时的「未知态」清单

替换阶段是三段非原子操作，每一步都可能留下中间态。全部列出来，不许有「这条应该不会发生」：

| 已做 | 未做 | 磁盘上是什么 | 结果 |
| --- | --- | --- | --- |
| — | — | 原包 + 临时包 | 删临时包 → `ReplaceFailed` |
| 1 成功 | 2、3 | 原包叫 `.bak` + 临时包 | 回滚（`.bak` → 原名）+ 删临时包 → `ReplaceFailed` |
| 1、2 成功 | 3 | 新包 += 原名 + `.bak` | `Updated`（detail 提示残留） |
| 1 成功、2 失败 | 3 | 原包叫 `.bak` + 临时包 | 回滚；回滚也失败 ⇒ `ReplaceFailed` + detail 点名 `.bak` |
| 写临时包途中失败 | 替换 | 原包 + 半个临时包 | 删临时包 → `ReadFailed` / `NoSpace` / `NotAZip` |

**清理一律走 `try/finally` + 「尽力删」**：清理本身失败只 `Log.w`，不改变已定的结果类型（与 `CbzOutputSink.discardFor` 同一立场：契约是幂等、不抛）。

### 7.5 空间预检

```kotlin
override fun preflight(plan: CbzMetaUpdatePlan, volume: ComicVolume): UpdatePreflight {
    val target = findTarget(volume) ?: return UpdatePreflight.Unknown
    val inputBytes = sizeOf(target)                  // query COLUMN_SIZE，取不到 → null
    val available = space.availableBytesOf(treeUri)  // 可能是 null
    if (inputBytes == null || available == null) return UpdatePreflight.Unknown
    val required = inputBytes + SAFETY_MARGIN_BYTES  // 输出 ≈ 输入（全 STORED 转写，只换一个 XML 条目）
    return if (available < required) UpdatePreflight.NoSpace(required, available)
           else UpdatePreflight.Ready(inputBytes, required)
}
```

`SAFETY_MARGIN_BYTES = 32L * 1024 * 1024`，与打包链的 `SpaceThreshold.Percentage(10)` + 32 MiB 余量同源（HANGOFF §11 第 12 条）。

**为什么 `estimatedWriteBytes` 用「输入 + 余量」而不是精确值**：精确值要数条目、读每条 size 才能算，那是「读一遍整包」—— 预检本身不能有整包成本。输入大小是上界（新 XML 通常只有几百字节），误差方向是**高估**，安全侧。

**为什么 `Unknown` 不阻断**：与 `PackUiPreflight` 一致 —— 空间不知道 ≠ 空间不足。真写不下时写临时包会抛异常，落到 `NoSpace` / `ReadFailed`，一样有明确报错。

### 7.6 取消边界

`shouldStop()` 只在两个位置被检查：

1. **每个条目开始前**（`CbzMetaRewriter.rewrite` 的循环里）；
2. **替换阶段开始前**（进入 §7.3 步骤 1 之前）—— 一旦开始替换就不再看，因为三步之间停下会制造 §7.4 的中间态，而中间态的恢复逻辑必须走完才有意义。

取消后：删临时包 → `Failed(Cancelled, null)`。**原包一个字节未动。** 这条必须写进 KDoc，因为它是「取消按钮可以放心按」的唯一依据。

### 7.7 时间戳

`timeMillis = now()`，一次取值传给整次转写（所有条目同一时间戳）。与 `CbzWriter` 的「调用方显式给定」一致；不逐条取 `System.currentTimeMillis()`，否则同一份输入两次运行的字节不同。

### 7.8 输入 / 输出流的所有权

```kotlin
resolver.openInputStream(targetUri)?.use { raw ->
    resolver.openOutputStream(tmpUri, "w")?.use { out ->
        CbzMetaRewriter.rewrite(
            input = BufferedInputStream(raw),
            out = out,
            comicInfoXml = plan.comicInfoXml,
            timeMillis = timeMillis,
            spillDir = cacheDir,
            onEntryWritten = onProgress,
        )
    }
}
```

`rewrite` 不关流（§6.1），这里用 `use` 保证两个都由平台层关。**输出流必须先关再改名** —— 不关的话部分 provider 的改名会失败（缓冲数据还在用户态）。

`cacheDir` 由构造参数给 `context.cacheDir`。**spill 文件必须放 cacheDir 而不是目标目录** —— 放目标目录会在用户的漫画文件夹里拉一堆临时文件，而且和 `.updating` / `.bak` 的命名空间混在一起。

## 8. UI 设计与接线

### 8.1 入口一：库页卷行

`LibraryPage.kt:292` 的 `VolumeRow` 现在是整行 `clickable(onSelect)` + `semantics` 合并。**在行内右侧加一个图标按钮**：

```kotlin
Row(
    modifier = Modifier.fillMaxWidth().clickable(onSelect)
        .padding(horizontal = 8.dp, vertical = 6.dp)
        .testTag(volumeCardTag(volume.id))
        .semantics { contentDescription = volumeCd },
    verticalAlignment = Alignment.CenterVertically,
) {
    Column(Modifier.weight(1f)) { /* 现有三行文字 */ }
    if (onUpdateMeta != null && volume.outputExists) {
        IconButton(
            onClick = onUpdateMeta,
            modifier = Modifier.testTag(volumeUpdateTag(volume.id))
                .semantics { contentDescription = updateCd },
        ) { Icon(painterResource(R.drawable.ic_edit_note), contentDescription = null) }
    }
}
```

三条要点：

1. **只在 `volume.outputExists == true` 时出现**（D6）。没有 CBZ 的卷连「更新元数据」的对象都没有 —— 那不是失败提示，是入口不该存在。
2. **必须自带 `.clickable`（这里用 `IconButton`）**。整行已有 `clickable`，子按钮不自己消费点击就会被行吞掉。既有先例：`VolumeChipRow` 里的 `PinToggle`。
3. **不新增 `onUpdateMeta` 到 `VolumeRow` 的调用点例外**：新参数必须有默认值（`LibraryPage` 的既定做法，见 §2.11），否则既有的 12 个测试文件全红。

### 8.2 入口二：元数据页底栏

`MetadataPage.kt:385-413` 的底栏用 `PageActionBar(leading = 返回, trailing = 下一步)`，两个槽位都被占。三条走法：

| 方案 | 结论 |
| --- | --- |
| 塞进 `leading`（返回左边） | ❌ `leading` 的语义是「退回类」动作，第三个退回类动作会让人以为它能退两步 |
| 塞进 `trailing`（下一步左边） | ⚠️ 可行，但两个按钮挤在右侧，而「更新元数据」是**另一种终局**（不是推进流程） |
| **用 `extraContent` 槽位** | ✅ 该槽位的定义就是「按钮行下方的补充说明」，本来就是第三件东西的落点；现在唯一使用者是报告页 |

⇒ **用 `extraContent`**，渲染成一个占满整行的 `OutlinedButton`：

```kotlin
extraContent = {
    if (updateEnabled) {
        OutlinedButton(
            onClick = onUpdateMeta,
            modifier = Modifier.fillMaxWidth().testTag(TAG_METADATA_UPDATE),
        ) { Text(stringResource(R.string.update_meta_action)) }
    }
}
```

**为什么是 `OutlinedButton` 而不是 `Button`**：`Button` 是 `trailing` 里「下一步」的样式，两者同形会让用户以为它也是「推进到下一步」。`OutlinedButton` 在 M3 里表达「次要但完整」的动作。

**`updateEnabled` 的判据**：`state.activeVolumeOrNull?.outputExists == true`。与入口一同一条（D6）。

### 8.3 确认框（不可逆的唯一告知点）

```kotlin
PixFoldAlertDialog(
    onDismissRequest = onCancel,
    title = { Text(stringResource(R.string.update_meta_title)) },
    text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.update_meta_body_size, sizeText))
            Text(stringResource(R.string.update_meta_entry_hint, entryText))
        }
    },
    confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.update_meta_confirm)) } },
    dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) } },
    modifier = Modifier.testTag(TAG_UPDATE_CONFIRM_DIALOG),
)
```

`sizeText` = 包的体积（`COLUMN_SIZE`），取不到时用 `R.string.update_meta_unknown_size`（「大小未知」）。**「大小未知」不许省略这一行** —— 用户最想知道的就是「我要读写多大的东西」。

第二行文案随条目数是否已知分叉：已知 ⇒ `update_meta_body_entries`（「%1$d 个条目将原样复制」）；未知 ⇒ 不显示该行（不编数字）。

### 8.4 进度弹窗

**直接复用 `PackProgressDialog`**（§2.10）。它的签名 `PackProgressDialog(progress, pageDone, pageTotal, onCancel, cancelling, volumeLabel)` 逐字段对得上：

| `PackProgressDialog` 参数 | 工作流 C 的值 |
| --- | --- |
| `progress: TaskProgress` | `TaskProgress(total = plan.entryCount.takeIf { it > 0 } ?: 1, done = done)` |
| `pageDone` / `pageTotal` | 已处理条目 / 总条目（`null` ⇒ 不确定态转圈） |
| `onCancel` | 置 `cancelRequested = true`（真正的停止由 `shouldStop` 在条目边界生效） |
| `cancelling` | `cancelRequested`（显示「正在取消…」） |
| `volumeLabel` | `volume.cbzFileName` |

**取消按钮能按，但不会立刻停** —— 这是 §7.6 的语义。KDoc 要写清楚：`cancelling = true` 期间按钮禁用，文案照旧，用户看到的是「点了，在等这一张复制完」。

### 8.5 结果弹框

```kotlin
PixFoldAlertDialog(
    onDismissRequest = onDone,
    title = { Text(stringResource(if (ok) R.string.update_meta_done_title else R.string.update_meta_fail_title)) },
    text = { Text(bodyText) },
    confirmButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.domain_general_confirm_ok)) } },
    modifier = Modifier.testTag(TAG_UPDATE_RESULT_DIALOG),
)
```

`bodyText` 三种：

1. 成功：`update_meta_done_body`（包名 + 保留条目数）。
2. 成功但有残留 `.bak`：上面那句 + 换行 + 「已保留旧备份：原名.cbz.bak（可手动删除）」。**不加新字符串键**，直接拼 `detail`（平台层已给出文件名）。
3. 失败：按 `UpdateFailure` 映射到六条 `TextKey` 之一；`detail` 非空时换行附上。

**为什么用弹框而不是 `ExecutionReportPage`**：D4 裁决。报告页是「多项 + 撤销档位」的载体，单卷更新没有多项也没有撤销，套进去会多出一个空的撤销区。

### 8.6 状态与接线（`LibrarySteps.kt`）

新增状态（全部 `remember(rootPath)`，与既有 `state`/`report` 同域）：

```kotlin
var updateTarget by remember(rootPath) { mutableStateOf<ComicVolume?>(null) }   // 非 null ⇒ 确认框
var updating by remember(rootPath) { mutableStateOf(false) }                     // 执行中 ⇒ 进度弹窗
var updateDone by remember(rootPath) { mutableStateOf(0) }
var updateTotal by remember(rootPath) { mutableStateOf(0) }
var cancelRequested by remember(rootPath) { mutableStateOf(false) }
var cancelToken by remember(rootPath) { mutableStateOf<CancellationToken?>(null) }
var updateResult by remember(rootPath) { mutableStateOf<CbzMetaUpdateOutcome?>(null) }  // 非 null ⇒ 结果弹框
```

**`cancelRequested` 必须每次执行前复位**（与打包链 `cancelling` 同一个坑：上次取消污染下一次）。

`onUpdateMeta` 三处装配（`LibraryShelfRoute` → `LibraryPage`、`LibraryMetadataRoute` → `MetadataPage`）都写成 `{ updateTarget = it }`。真正的执行在 `LibrarySteps` 顶层：

```kotlin
val updater = /* 注入，见 §8.7 */
LaunchedEffect(updateTarget, updating) {
    val volume = updateTarget ?: return@LaunchedEffect
    if (!updating) return@LaunchedEffect   // 只有确认后才进这里
    ...
}
```

⇒ 实际用 `scope.launch` + `Dispatchers.IO`，与 `performPack()` 同构：

```kotlin
fun startUpdate(volume: ComicVolume) {
    cancelRequested = false
    updating = true
    updateDone = 0
    updateTotal = 0
    val token = CancellationToken()
    cancelToken = token
    scope.launch {
        val outcome = withContext(Dispatchers.IO) {
            updater?.update(planOf(volume), volume, { token.isCancelled }) { done, total ->
                updateDone = done; updateTotal = total
            } ?: CbzMetaUpdateOutcome.Failed(UpdateFailure.ReadFailed, "更新器未注入")
        }
        updating = false
        cancelToken = null
        updateResult = outcome
        // 成功后刷新 outputExists / 让既有 ComicInfo 重新读一遍（§8.8）
    }
}
```

**`shouldStop` 传 `{ token.isCancelled }`**：`CancellationToken` 的公开面是 `isCancelled` 还是 `shouldStop(...)` 需在实现时按实际 API 对齐（`CancellationToken.shouldStop(beforeStartingItem)` 带参数，用于打包链；工作流 C 需要一个无参版本 —— 若没有，就把 `{ token.shouldStop(0) }` 包一层，**不要修改 `CancellationToken` 本身**）。

### 8.7 注入缝（`PixFoldApp.kt`）

`CbzMetaUpdater` 在 `PixFoldApp` 里构造（那里已有 `workspaceHandle` 与 `SafMetaUpdater` 需要的所有依赖），经 `LibrarySteps` 透传到两个入口：

```kotlin
val metaUpdater = remember(workspaceHandle) {
    workspaceHandle?.let {
        SafMetaUpdater(
            context = context,
            treeUri = it.treeUri.toUri(),
            space = FilesystemSpaceSource(context),
            dirOfVolume = { vol -> outputDirRelOf(vol.outputDir, it.displayName) },
        )
    }
}
```

- **`remember(workspaceHandle)` 必须有 key**（热切换工作区的白屏事故就是无参 `remember`，见 AGENTS.md D2 缺陷 ①）。
- **`null` 表示「没有工作区」**，此时两个入口都不渲染（与 `outputExists` 判据天然一致：没有工作区就没有卷）。
- `LibrarySteps` 加参数 `metaUpdater: CbzMetaUpdater? = null`，默认 `null` ⇒ 既有 24 处导航测试零改动。

### 8.8 成功后的刷新

更新成功后磁盘上的包变了但 `ComicVolume` 没变 ⇒ 两件事必须重做：

1. **`outputExists` 不变**（文件还在），不用重扫工作区。
2. **`ComicInfo` 当前值要重读**：`applyExistingComicInfo` 只在 `PixFoldApp` 顶层扫工作区时跑一次（`PixFoldApp.kt:399`）。工作流 C 改了 XML 但内存里的 `title`/`series` 还是旧的 ⇒ **用户下次看到的是自己刚改之前的值**。

修法（最小）：更新成功后用**新 XML 本身**回填，不重读磁盘：

```kotlin
val updated = withComicInfoValues(volume, plan.comicInfoXml)
libraryState = selectVolume(replaceVolume(...updated...))
```

`withComicInfoValues` = 新增领域纯函数：`parseComicInfo(xml)` → 按 `applyExistingComicInfo` 同款规则（只改当前值、建议值不动）产出一份。**为什么不重读磁盘**：刚写的就是它，读一遍多一次整包打开 + 一次 `ZipInputStream` 扫描，换来的是「可能读到一半被别的应用改了」的不确定性。

## 9. 测试计划（TDD，先红后绿）

### 9.1 领域层（`android/domain/src/test/kotlin/.../pack/`）

`CbzMetaRewriterTest`（新文件，约 12 条）：

| # | 断言 | 断什么 |
| --- | --- | --- |
| T1 | 单条目 STORED 包：输出只有 ComicInfo + 那一条，内容逐字节相同 | 基本转写 |
| T2 | 旧包 ComicInfo 在第 3 条 ⇒ 输出的 ComicInfo 在第 0 条，其余顺序不变 | §2.2 契约 |
| T3 | 旧包有**两个** ComicInfo（大小写不同）⇒ 输出只有一个 | 去重 |
| T4 | 旧包无 ComicInfo ⇒ 输出有且仅有一个，且在最前 | 加法方向 |
| T5 | 输出所有条目 `method == STORED`（用 `ZipFile` 读，不用 `ZipInputStream`） | §2.3 |
| T6 | 输出每条 `size`/`crc` 与实际字节一致 | STORED 的正确性 |
| T7 | DEFLATED 输入条目 ⇒ 输出仍 STORED 且内容逐字节相同 | spill 路径 |
| T8 | `spillDir = null` + DEFLATED 输入 ⇒ 抛 `IllegalStateException` | 契约边界 |
| T9 | `shouldStop = { true }` ⇒ 抛/返回取消，且**输出流没有被写入任何条目之前就停** | §7.6 条目边界 |
| T10 | 空包（0 条目）⇒ 输出只有一个 ComicInfo | 边界 |
| T11 | 输出流不被 `rewrite` 关闭（传一个 `close` 会抛的 `OutputStream` 包装） | §6.1 |
| T12 | 输入流不被 `rewrite` 关闭 | 同上 |

`CbzMetaUpdatePlanTest`（约 5 条）：`rewrittenEntryOrder` 三种输入（ComicInfo 在首位 / 中段 / 不存在）；`isComicInfoEntry` 大小写 + 目录前缀；plan 两条 `require`。

`withComicInfoValuesTest`（约 4 条）：只改当前值不动建议值；缺失字段留空；`cbzFileName` 不动；与 `applyExistingComicInfo` 对同一 XML 的结果一致（同一份规则不许两套实现）。

### 9.2 平台层（Robolectric，`android/app/src/test/.../data/saf/`）

`SafMetaUpdaterTest`（约 8 条，用 `DocumentsProvider` 的 Robolectric 影子或 `FakeCbzMetaUpdater`）：

⚠️ **Robolectric 没有真实 `DocumentsProvider`** ⇒ §7.3 / §7.4 的三段式替换**无法离线断**（与 `ContentObserver.notifyForDescendants` 同一类别）。因此：

- 平台层只断**纯逻辑**部分：`preflight` 的三种返回（Ready / NoSpace / Unknown，用假 `FilesystemSpaceSource` + 假 size）。
- 三段式替换 + 回滚 + 残留 `.bak` 全部**只真机验**，并在 `progress.md` 登记为「只能真机断」。
- `CbzMetaUpdater` 的假实现（`FakeCbzMetaUpdater`）供 UI 层断言用：能按脚本返回 `Updated` / 六种 `Failed` / 触发 `onProgress` 序列。

### 9.3 UI 层（`android/app/src/test/.../ui/library/`）

`CbzUpdateEntryTest`（约 6 条）：

| # | 断言 |
| --- | --- |
| U1 | `outputExists == false` 的卷**不渲染**更新按钮（两个入口都不渲染） |
| U2 | `outputExists == true` 的卷渲染按钮，点它回调收到的 `volume.id` 正确 |
| U3 | 点卷行本身仍走 `onSelect`（新增按钮没吞掉整行的点击） |
| U4 | 元数据页底栏的更新按钮在 `activeVolumeOrNull == null` 时不渲染 |
| U5 | `metaUpdater == null`（未注入）时两个入口都不渲染 |
| U6 | 确认框 → 确认 → 进度弹窗 → 完成弹框 这条链（用 `FakeCbzMetaUpdater` 立即返回） |

`CbzUpdateFailureTest`（约 4 条）：六种 `UpdateFailure` 各映射到不同文案（断言六条的 `TextKey` 互不相同且都非空）；`detail` 非空时出现在结果弹框里。

### 9.4 变异清单（Task 9）

| 变异 | 期望 |
| --- | --- |
| M1：删掉「丢弃旧 ComicInfo」分支 ⇒ 输出出现两个 ComicInfo | KILLED by T3 |
| M2：把新 XML 写到最后而不是最前 | KILLED by T2 |
| M3：`setLevel(0)` 改成默认（DEFLATED 输出） | KILLED by T5 |
| M4：`shouldStop` 不检查 ⇒ 取消无效 | KILLED by T9 |
| M5：入口判据从 `outputExists` 改成恒 true | KILLED by U1 |
| M6：`rewrite` 里 `out.close()` | KILLED by T11 |
| M7：成功后不回填 `ComicInfo` 当前值 | KILLED by `withComicInfoValuesTest` + U6 的后续断言 |
| M8：`preflight` 把 `available == null` 当 0（拒绝） | KILLED by `SafMetaUpdaterTest` 的 Unknown 条 |

### 9.5 只能真机断的清单（登记进 `progress.md`）

1. 三段式替换（`原包 → .bak` / `临时 → 原名` / `删 .bak`）与三个中间态。
2. 回滚路径（步骤 2 失败后把 `.bak` 改回原名）。
3. 输出流关闭后 `renameDocument` 真的能成功（部分 provider 的要求）。
4. 4.73 GiB 级别的真实包：内存不涨（spill 生效）、进度条推进、取消在条目边界真的能停。
5. 授权失效（`PermissionLost`）在弹窗里的表现。

## 10. 验收标准

1. 库页有包（`outputExists`）的卷行右侧出现更新按钮；元数据页底栏出现更新按钮。
2. 点按钮弹确认框，正文含包体积与「无法撤销」。
3. 确认后出现进度弹窗，条目数推进；取消在条目边界生效且原包未被改动（真机验）。
4. 成功后弹完成框，包名与保留条目数正确；再次打开该包，`ComicInfo.xml` 是新内容且是第一条。
5. 元数据页的当前值立刻反映新写入的值（不需要重扫工作区）。
6. 六种失败各自的文案可读、可区分。
7. 产出包通过 `CbzReaderCompatTest` 的全部 9 条契约（复用既有测试，喂一个「工作流 C 产出」的包进去）。
8. 四道闸门绿：`app` / `domain` 测试数只增不减、`lint` 零告警、`assembleDebug` 成功。

## 11. 已知边界（写在这里，不在实现时才发现）

1. **失败路径的真机取证需要用户授权**，本轮（2026-10-26）只做到「真机就绪」，核对留到用户下次授权。
2. **一次只更新一卷**（Q2 甲裁决）。批量更新不做 —— 那需要进度条的多卷语义与部分成功的报告，与 D4 的范围不符。
3. **不提供撤销**（D2 裁决）。用户在确认框里被告知。
4. **不真的「就地」改**：整个包会重写。4.73 GiB 的包只改一行元数据也要读写 4.73 GiB。
5. **页条目名不重编号**（D3 裁决）。第三方包的页名保持原样 —— 重命名会破坏用户的阅读顺序假设（有些包的页名是 `001.jpg` 到 `120.jpg`，重排会变）。
6. **DEFLATED 输入走 spill**（D1 裁决），峰值额外占用 = 最大单个 DEFLATED 条目。自家产物全 STORED ⇒ 典型场景零 spill。
7. **读到一半被别的应用改了**：没有锁，无防护。`ZipInputStream` 读到的可能是一个混合状态 —— 这在 SAF 下无法避免（没有 `flock`）。
8. **残留 `.bak` 与 `.updating` 不会被自动清理**：跨会话的残留无法判断是不是用户的备份。UI 只在本次成功且有残留时提一句。
9. **ComicInfo 之外的元数据条目原样保留**（比如某些包带 `info.txt`），不识别不修改。

