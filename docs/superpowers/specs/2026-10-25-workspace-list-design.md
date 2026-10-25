# 工作区列表（最近 8 条 + 图钉 + 拖拽） —— 设计规格

> **状态**：**待用户审阅**（brainstorming 的 Architectural 路径硬门：spec 经审阅后才能进入 writing-plans）。
> **日期**：2026-10-25
> **产物类型**：实现级设计规格（`docs/superpowers/specs/`）
> **产品权威**：`HANGOFF.md`。本文件只承载实现级设计与决策依据，不另立产品决策。
> **关联**：设计稿源 `android/artifacts/ws-mock/ws4.html` / 成品 `ws4-final.png`（v4 定稿，用户已逐格确认）

## 1. 背景与范围

### 1.1 背景

2026-10-24 的「工作区选取搬到首页」（commit `a50cbce` 之后的 `d4/home-workspace` 分支）把**唯一的**选目录入口搬到了首页工作区条上。但那条只记住**一个**目录：

- `android/app/src/main/kotlin/com/cyanix/pixfold/data/saf/SafTreeStore.kt` 只有 `load()` / `save(handle)` / `clear()`，prefs 里只有 `tree_uri` 与 `tree_display_name` 两个 key；
- 用户换一次目录，上一个就被覆盖，再也找不回来；
- SAF 的持久授权其实**一直在系统里累积**（`contentResolver.persistedUriPermissions` 是一个列表），只是 PixFold 从来没有去读它，导致系统里堆着一堆授权而应用只认得最后一个。

用户 2026-10-25 提出要做「最近使用的文件夹列表」（m04085、m04125）。

### 1.2 本阶段范围

| 交付 | 说明 |
| --- | --- |
| 工作区列表 | 最多 **8** 条，按最近打开倒序 |
| 图钉 | 钉住的排到**本区最前**；图钉之间仍可互相拖拽互换 |
| 拖拽排序 | 长按整行任意位置拖动，实时让位，松手提交一次 |
| 两分区 | 「已持久授权」/「只记住了路径」，每次打开列表时**实时**判定 |
| ✕ 移除 | 只从列表删记录，不动磁盘；可勾「同时撤销持久授权」 |
| 首页工作区条 | 从「当前目录名 + 更换」改为「点整条打开列表」 |
| 删除假开关 | 设置页「文件访问方式（SAF / 全部文件访问）」整组删除 |

### 1.3 明确不在本阶段范围

| 项 | 原因 | 去向 |
| --- | --- | --- |
| 相册模式（`PickMultipleVisualMedia`） | 用户裁决 m04085「那就不做相册模式了」 | 不做 |
| 指定第三方文件管理器（如 MiXplorer） | 用户裁决 m04125「不做指定文件管理器」。技术理由：`intent.setPackage("com.mixplorer")` 基本 `ActivityNotFoundException`；只要它实现 `DocumentsProvider`，就**自动**出现在系统选择器侧栏，无需我们做任何事 | 不做 |
| 外部分享文件夹 / 批量图片进来自动成为工作区 | 用户裁决 m03117「这功能 D5 再做」 | D5 |
| 存储权限（`READ_MEDIA_IMAGES` / `MANAGE_EXTERNAL_STORAGE`） | SAF 的 tree URI 授权已覆盖读、写回、重启有效，且 Play 无风险 | manifest 一行不加 |
| 列表项显示缩略图 / 封面 | 设计稿 v4 里没有，且要额外遍历目录 | 不做 |
| 列表项显示图片张数 | 要遍历才知道，会让打开列表变成 N 次全盘扫描 | 不做（设计稿 v4 同款取舍） |

## 2. 已核实的硬约束（读源码确认，非推测）

### 2.1 现有存储只支持单条

`android/app/src/main/kotlin/com/cyanix/pixfold/data/saf/SafTreeStore.kt`（45 行全文）：prefs 名 `pixfold_workspace`，key 只有 `tree_uri` / `tree_display_name`；`load(): WorkspaceHandle?`（getString 为 null 或空串返 null）、`save(handle)`、`clear()`。

### 2.2 `WorkspaceSource` 接口当前只有单工作区语义

`android/domain/src/main/kotlin/com/cyanix/pixfold/domain/source/WorkspaceSource.kt`：

```kotlin
fun lastWorkspace(): WorkspaceHandle?
fun rememberWorkspace(handle: WorkspaceHandle)
fun forgetWorkspace()
```

实现方只有两处真实：`android/app/src/main/kotlin/com/cyanix/pixfold/data/saf/AndroidSafSource.kt:49/:60/:76`；假源三处：`android/app/src/test/kotlin/com/cyanix/pixfold/ReadyWorkspaceSource.kt:43-47`、`android/app/src/test/kotlin/com/cyanix/pixfold/ui/workspace/WorkspaceControllerTest.kt:114-116`（`FailingSource` 包装）与 `:155-157`（`RecordingSource`）。

### 2.3 domain 层拿不到 `ContentResolver`

`android/domain/src/test/kotlin/com/cyanix/pixfold/domain/boundary/AdapterBoundaryTest.kt` 是**每次闸门都跑的持续守卫**：`domain/src/main` 的非注释代码里出现 `import android.*` / `import androidx.*` / `import com.cyanix.pixfold.(ui|data|nav).*` 即失败。

⇒ 「这一条还在不在持久授权里」**不能**在 domain 判定，必须由平台层实现、经接口注入。

### 2.4 拖拽组件已经存在且是长按启动

`android/app/src/main/kotlin/com/cyanix/pixfold/ui/components/DragReorderList.kt:141` 已用 `detectDragGesturesAfterLongPress`，无独立抓手；语义是「整行可拖、长按启动、目标行由父容器用 `boundsInRoot` 判定、**实时让位**、数据只在松手时提交一次」（:52-54）。本功能沿用同一套语义。

### 2.5 弹窗组件已有唯一出入口

`android/app/src/main/kotlin/com/cyanix/pixfold/ui/components/PixFoldDialog.kt:223` `PixFoldDialogShell(onDismissRequest, buttons, modifier, maxWidth = DialogMaxWidth, dismissOnOutside = true, tag = TAG_PIXFOLD_DIALOG, contentScrollable = true, title, content)`；`DialogMaxWidth = 320.dp`（:282）、`DialogPadding = 24.dp`（:294）。正文可滚是 `weight(1f, fill = false).verticalScroll(...)`，**没有**高度上限 —— 8 条列表会给它撑到视口外，本规格要补上限。

### 2.6 依赖里没有拖拽库、没有 WorkManager

`android/app/build.gradle.kts:105-137` 与 `android/gradle/libs.versions.toml` 均无第三方拖拽库；持久化只能用 `SharedPreferences` + `kotlinx-serialization-core`（已在依赖里，:119）。

## 3. 用户裁决清单（逐条带原文）

| # | 裁决 | 原话 / ref |
| --- | --- | --- |
| 1 | 上限 **8** 条 | User said (m04085)：「8行可以」 |
| 2 | 加**图钉**按钮（带置顶效果）+ **拖拽排序** | 同上：「加图钉按钮（带置顶效果）和拖拽排序吧」 |
| 3 | 设计稿要体现「SAF 与持久授权分开」 | 同上：「你这次的图没有体现出之前说的SAF和持久授权分开」 |
| 4 | **不做**相册模式 | 同上：「那就不做相册模式了」 |
| 5 | **不做**指定第三方文件管理器 | User said (m04125)：「不做指定文件管理器」 |
| 6 | 设置里的假「文件访问方式」**不做** | 同上：「不做」 |
| 7 | 拖拽不要独立抓手，长按整行拖 | 同上：「拖拽排序的按钮改下有点难看」 |
| 8 | 图钉统一成命名页那款 | 同上：「命名功能用的图钉按钮没这个图钉好看」 |
| 9 | 钉住 = **本区**最前；图钉之间仍可互换 | 同上：「钉住的排到本区最前，多个图钉之间仍可互相拖拽」 |
| 10 | 两个分区**保留**（删的是设置里那个全局偏好） | 同上：「但是你的图里又说删除了2个分区的区分啊鱼鱼」 |

## 4. 数据模型

### 4.1 新增（domain）

```kotlin
// android/domain/src/main/kotlin/com/cyanix/pixfold/domain/source/WorkspaceEntry.kt
data class WorkspaceEntry(
    val handle: WorkspaceHandle,
    val pinned: Boolean = false,
)
```

```kotlin
// android/domain/src/main/kotlin/com/cyanix/pixfold/domain/source/RememberResult.kt
/** [WorkspaceSource.rememberWorkspace] 的结果。 */
sealed interface RememberResult {
    /** 新增成功。 */
    data object Added : RememberResult

    /** 已存在同一 treeUri，已更新名字并移到最前。 */
    data object MovedToFront : RememberResult

    /**
     * 已满 8 条且全部钉住，无处可挤。
     * UI 用 [com.cyanix.pixfold.R.string.workspace_list_full_pinned] 提示「先取消一个图钉」。
     */
    data object RejectedAllPinned : RememberResult
}
```

```kotlin
// android/domain/src/main/kotlin/com/cyanix/pixfold/domain/source/WorkspaceListing.kt
/**
 * 列表项在 UI 层的完整事实：存储事实（handle / pinned）+ 运行时事实（granted）。
 *
 * **为什么 granted 不存进 WorkspaceEntry**：持久授权是系统状态，用户在文件管理器里
 * 「撤销授权」时我们收不到通知；把它持久化等于存一个随时会过期的判断。
 */
data class WorkspaceListing(
    val entry: WorkspaceEntry,
    val granted: Boolean,
)
```

### 4.2 存储格式

`SafTreeStore` 从「两个标量 key」改成「一个 JSON 字符串 key」：

- key `workspaces_v2`，值 = `kotlinx.serialization` 序列化的 `List<StoredEntry>`，`StoredEntry(treeUri, displayName, pinned)`。
- **迁移**：`loadAll()` 发现 `workspaces_v2` 缺失/空且旧 key `tree_uri` 有值 ⇒ 用旧值构造一条 `pinned = false` 的条目写入 v2，然后删掉旧 key。旧 key 在写成功后才删（写失败下次还能再迁一次）。
- **反序列化失败**（JSON 损坏 / 字段变更）⇒ 当作空列表，不抛。理由同 `SafTreeStore` 现有 KDoc：「这段代码在冷启动路径上」。

### 4.3 顺序语义（一条规则，不拆两处）

列表的**存储顺序**就是**显示顺序**。`pinned` 只是渲染时把钉住的排到本区最前，**不改变存储位置**。

这一条是「图钉之间仍可互相拖拽互换」（裁决 9）能成立的唯一方式：若拖拽时只交换「同钉住状态之间」的相对位置，就必须维护两个独立序列，而这正是漂移的来源。

⇒ 渲染时对每个分区内部做**稳定排序**：`sortedByDescending { it.entry.pinned }`（Kotlin 的 `sortedBy`/`sortedByDescending` 是**稳定**的，等键项保持原相对顺序）。

### 4.4 拖拽下标的换算（最容易出错的一处，写死在这）

拖拽组件提交的是**渲染顺序**里的 `(from, to)`，而存储是**一条**全局序列。换算分三步，一步都不能省：

```
1) 拒绝跨分区：render[from].granted != render[to].granted ⇒ 什么都不做
2) 在存储序列里按 treeUri 找真下标：
     si = store.indexOfFirst { it.treeUri == render[from].treeUri }
     sj = store.indexOfFirst { it.treeUri == render[to].treeUri }
3) 先删后插（顺序反了会错一位）：
     val item = store.removeAt(si)
     store.add(sj.coerceIn(0, store.size), item)
```

**为什么「先删后插」的 `sj` 不用减一**：`si < sj` 时移除后原 `sj` 的元素前移到了 `sj - 1`，而「插到它前面」要求的新下标正好是 `sj - 1 + 1 = sj`；`si > sj` 时 `sj` 不受影响。两种情况同一个表达式，故**不加特判** —— 加了反而会在其中一支上错位。

**为什么按 treeUri 而不是下标**：渲染顺序经过 pinned 稳定排序，与存储下标没有任何线性对应关系。

## 5. `WorkspaceSource` 接口变更

### 5.1 方案选择

| 方案 | 做法 | 优点 | 缺点 |
| --- | --- | --- | --- |
| **甲（推荐）** | 删掉三个单工作区方法，换成五个列表方法 | 只有一套语义，编译器强制所有实现方改 | 三个假源要改（共 3 文件 / 约 12 行） |
| 乙 | 保留旧三方法，新增多工作区方法并给默认实现 | 假源零改动 | 接口同时活两套语义，`lastWorkspace()` 与 `workspaces().first()` 迟早给出不同答案 |

**选甲**。理由：调用点只有 `WorkspaceController` 与三个假源，改造面 3 文件；而乙那种「两套语义并存」正是本项目反复吃亏的形态（`WorkspaceState.canOrganize` 在 None/Denied/Failed 都返 true，与首页真正要问的问题相反，2026-10-24 只能另写一个 `canStartWorkflow`）。

### 5.2 新接口

```kotlin
interface WorkspaceSource {
    /** 全部已记住的工作区，顺序即显示顺序。 */
    fun workspaces(): List<WorkspaceEntry>

    /**
     * 记住一个工作区并把它移到最前。
     *
     * 已存在（同一 treeUri）时：更新 displayName、移到最前、**保留 pinned**。
     * 加第 9 条时从尾部往前找第一条未钉住的剔除；**8 条全钉住时返回 [RememberResult.RejectedAllPinned]**，
     * 列表一个字都不改。返回值不是可选项 —— 静默失败会让用户以为「添加成功但列表没变」。
     */
    fun rememberWorkspace(handle: WorkspaceHandle): RememberResult

    /** 只从列表移除这一条；不动磁盘、不动持久授权。 */
    fun forgetWorkspace(treeUri: String)

    /** 切换图钉。treeUri 不存在时无事发生。 */
    fun setPinned(treeUri: String, pinned: Boolean)

    /** 拖拽提交：把 from 这一条移到下标 to。越界时钳到合法范围。 */
    fun moveWorkspace(from: Int, to: Int)

    /**
     * 当前仍持有系统持久授权的全部 treeUri。**平台层才能回答**（见 §2.3）。
     *
     * 这是唯一真相源：**没有默认实现**，每个实现方必须回答。
     * 单条版 [isPersistentGrant] 由它在接口层派生，实现方**不要覆盖**。
     */
    fun persistentGrants(): Set<String>

    /**
     * 这一条是否还持有系统持久授权。默认按 [persistentGrants] 派生。
     *
     * ⚠️ 实现方**不要覆盖本方法** —— 两个方法各自实现会出现「批量说 true、
     * 单条说 false」而没有任何测试能自然发现（假源会各自实现得自洽）。
     */
    fun isPersistentGrant(treeUri: String): Boolean = treeUri in persistentGrants()

    // —— 以下与原接口相同 ——
    fun loadCollections(handle: WorkspaceHandle): LoadResult
    fun readBytes(item: SourceItem): ByteArray?
    fun watchChanges(handle: WorkspaceHandle, onChange: () -> Unit): WorkspaceChangeWatcher? = null
}
```

`lastWorkspace()` 被**删除**；它的唯一调用点 `WorkspaceController.restore():104` 改为 `source.workspaces().firstOrNull()?.handle`。

### 5.3 8 条上限落在哪一层

**落在 `AndroidSafSource.rememberWorkspace` 与 `SafTreeStore`**，不是 UI。理由：`SafTreeStore` 是唯一写入口，把上限写在显示层意味着「读得到 9 条、看不清第 9 条」这类无法自证的状态。

剔除顺序：新加第 9 条时，从**尾部**往前找第一条 `pinned == false` 的条目剔除。**8 条全钉住时拒绝新增**，返回 `RememberResult.RejectedAllPinned`（UI 提示「先取消一个图钉再添加」，见 §7.3）。

### 5.4 `AndroidSafSource.persistentGrants` 实现

```kotlin
// 一次跨进程查询拿到全集，调用方逐条 in 判定。
// 写成「逐条 any {}」会让 8 条列表产生 8 次 ContentResolver→ActivityManager 的 Binder 往返，
// 而 openList() 在 Compose 点击路径上是同步执行的（见计划 Task 4 复盘 Important-2）。
override fun persistentGrants(): Set<String> = try {
    resolver.persistedUriPermissions
        .filter { it.isReadPermission }
        .map { it.uri.toString() }
        .toSet()
} catch (e: Exception) { emptySet() }
```

## 6. `WorkspaceController` 变更

新增四个公开成员（其余不动）：

```kotlin
/** 列表快照（含 granted 运行时判定）。每次 openList()/增删改后重算。 */
var listings: List<WorkspaceListing> by mutableStateOf(emptyList())
    private set

fun openList()                       // 重读 workspaces() + 一次 persistentGrants() 逐条 in 判定
fun open(treeUri: String)            // 点一条：load(handle)，成功即 rememberWorkspace（移到最前）
fun remove(treeUri: String, alsoRelease: Boolean)
fun togglePin(treeUri: String)
fun move(from: Int, to: Int)         // 拖拽提交
```

三个必须落在 KDoc 里的理由：

1. **`openList()` 每次都重读 `persistentGrants()`**：持久授权可能在应用外被撤销（用户在系统设置里删了），缓存它等于显示过期的 ✓。（每次调用**只查一次**批量接口，不逐条查。）
2. **`open(treeUri)` 成功后才移到最前**：失败就移到最前，会让一条打不开的记录反复霸占第一位。

   实现口径（只有一处，不散在调用点）：**在 `load(handle)` 的成功分支里调 `source.rememberWorkspace(handle)`**。这条路径同时覆盖三种入口 —— `restore`（冷启动）、`pick`（用户新选）、`open`（从列表点一条）。三者语义一致：**真的读到了，才承认它是最新的**。
   - 已在最前时 `rememberWorkspace` 是幂等的（位置不变），故 `restore` 不会平白改变顺序；
   - 失败（`Denied` / `Failed`）不进这个分支，顺序保持。

   ⚠️ 注意 `load` 的自动重试分支（`WorkspaceController.kt:187-193`）：重试成功也要走 `rememberWorkspace`，否则「冷启动抖一下才读到」的那次不会更新顺序。
3. **`remove(alsoRelease = true)` 才调 `releasePersistableUriPermission`**：默认不撤销 —— 「从列表删掉」与「收回系统授权」是两件事，混成一件会让用户手滑就丢掉授权（下次还得重新走系统选择器）。

## 7. UI 设计

### 7.1 首页工作区条

`android/app/src/main/kotlin/com/cyanix/pixfold/ui/HomeScreen.kt:136-138` 现在是 `if (workspaceState != null) { WorkspaceBar(state, onPick) }`。

改为：`WorkspaceBar` 增加 `onOpenList: () -> Unit`，整条可点（`Modifier.clickable`），右侧按钮文案从「更换」改为「工作区」。

- **为什么整条可点**：列表里的操作（切换 / 图钉 / 拖拽 / 移除）远多于「换一个」，而「更换」这个词只描述了其中一个。
- 状态映射全部保留（
`
WorkspaceBar
`
 现有 5 状态文案不变），只有**动作语义**变：
  - 
`
Ready
`
（工作区正常）：整条 / 右侧按钮都打开**列表**；
  - 其余状态（
`
None
`
 / 
`
Loading
`
 / 
`
Denied
`
 / 
`
Failed
`
）：整条 / 右侧按钮都走 
`
onPick
`
 = **系统选择器**。

> **修正（2026-10-26，Task 7 复审 I-3）**：本节初稿写「任何状态下点它都打开列表」，实现后核对
> **只在首页成立**。工作流页（
`
OrganizeSteps
`
 / 
`
LibraryPage
`
）只在该状态满足
> 
`
WorkspaceState.showsWorkspaceBar
`
 时渲染条，而那是 
`
Loading
`
 / 
`
Denied
`
 / 
`
Failed
`
 三态
> （2026-10-24 用户裁决「乙：工作流内的条只在异常/扫描中显示」）；
`
Ready
`
 时工作流页根本不画条。
> 于是 
`
rowAction = if (state is Ready) onOpenList else onPick
`
 在工作流页**恒走 onPick** ——
> 这在语义上恰好是对的：授权失效时用户要的是重新选目录，不是看一个全是失效项的列表。
> 三处 
`
onOpenWorkspaceList
`
 透传（含 
`
LibrarySteps
`
）保留为预防性接线，不算死代码。
- `Loading` 状态下`actionLabel` 原本为空（`if (actionLabel.isNotEmpty())` 时才画按钮）；改版后 `Loading` 也画「工作区」按钮 —— 因为列表里可以切到别的目录，不让点等于把唯一的出口锁死。**文案文字与按钮都保留，只是按钮从「更换」变「工作区」、整条都可点。**

### 7.2 弹窗结构

复用 `PixFoldDialogShell`（`maxWidth = DialogMaxWidth = 320.dp`、`tag = TAG_WORKSPACE_DIALOG`、`contentScrollable = false` —— 列表自身滚动，见 §2.5 那条嵌套滚动会崩的注释）。

**高度上限**：正文 `Column` 加 `heightIn(max = WORKSPACE_LIST_MAX_HEIGHT)`，取 `300.dp`（≈视口 38%）。8 条 × 56dp 行高 + 两个分区标题 ≈ 500dp ≫ 上限，所以列表内部必须滚动 —— 这与 `PixFoldDialogShell` 的 `contentScrollable = true`（整体竖滚）互斥，故这里传 `false` 并让列表自己滚。

分区渲染（每个分区内部独立排序 + 独立拖拽）：

```
┌─────────────────────────────┐
│ 工作区                       │
│                             │
│ 已持久授权            ＋     │
│  ⠿ 📁 青水庵 子産み島   📌 ✕│   ← pinned
│  ⠿ 📁 test              ✕  │
│  ⠿ 📁 chipcheck         ✕  │
│                             │
│ 只记住了路径          ＋     │
│  ⠿ 📁 旧目录            ✕  │
│                             │
│         [关闭]  [添加文件夹…] │
└─────────────────────────────┘
```

**注意：没有拖拽抓手**（裁决 7）。上图 ⠿ 只是示意「可拖」，实际不画任何图标。

「✕」**常驻**，与设计稿 v4 一致。它与拖拽长按不冲突：`✕` 是独立的 `IconButton`，会消费自己区域内的按下事件，而长按判定挂在行容器上 —— 两者的事件区域不重叠。

「同时撤销持久授权」**不做成每行的复选框**，改成本弹窗底部一个**全局开关**（`Switch` + 文案「移除时同时撤销系统授权」，默认关）：

- 每行一个勾选框会把「✕」的语义从「从列表删掉」变成「删掉并可能动系统状态」，用户每次都要重新判断；
- 全局开关只在真的要点 ✕ 时才需要被看见，默认关 = 默认只删记录（安全侧）。
- 打开这个开关后，点 ✕ **仍不弹二次确认** —— 因为开关本身就是一次显式确认，再问一遍等于把同一个决定问两次。开关状态**不持久化**（每次都从关开始）。

**图钉**：复用 `android/app/src/main/kotlin/com/cyanix/pixfold/ui/components/PinToggle.kt`（`fun PinToggle(isPinned, itemName, onClick, modifier, size: Dp = 32.dp, tag = TAG_PIN_TOGGLE)`，已钉 = 实心 primary 底，未钉 = surface 底；图标是 Material Symbols 官方 push_pin 几何的 VectorDrawable `ic_push_pin_filled.xml` / `ic_push_pin_outlined.xml`）—— 裁决 8 要求的「命名页那款」。

### 7.3 交互细则

| 交互 | 行为 |
| --- | --- |
| 点一行 | 切换过去（`open`）并关闭弹窗 |
| 长按一行拖动 | 实时让位，松手提交一次；**只能在同一分区内换位**（跨分区禁止：分区是运行时事实，把一条「只记住路径」拖到「已持久授权」区等于伪造授权） |
| 拖动时 | 列表不滚（`userScrollEnabled = false`），边缘自动滚 |
| 点图钉 | 立刻切换（无确认）；该行跳到本区最前 |
| 点 ✕ | 直接移除，**无二次确认** —— 因为「只删记录不动磁盘」，可逆（重新选一次就回来） |
| 添加文件夹… | 走现有 `OpenDocumentTree` launcher；返回后若已存在则移到最前，若已有 8 条且全钉住则提示「先取消一个图钉」 |
| 分区为空 | 该分区整块不渲染（不显示空标题） |

### 7.4 触控与可访问性（AGENTS.md §2.7 硬约束）

- 行高 ≥ 48dp；`✕` 与 📌 各自的点击目标 ≥ 48dp（`PinToggle` 现有 `size = 32.dp` 是**视觉**尺寸，其外层已有 `minimumInteractiveComponentSize`，需在实现时确认；chip v4 的教训是 Robolectric 量到 36dp，故**加显式 `heightIn(min = 48.dp)`**）。
- 每个可点元素给 `contentDescription`（读屏不能只念出目录名）。

## 8. 删除设置里的假开关

### 8.1 删除范围

| 文件 | 处 |
| --- | --- |
| `android/domain/src/main/kotlin/com/cyanix/pixfold/domain/settings/PixFoldSettings.kt` | `:86` `enum class StorageAccess`、`:142` `val storageAccess` 字段 |
| `android/app/src/main/kotlin/com/cyanix/pixfold/data/SharedPrefsSettingsStore.kt` | `:12` import、`:43` `readEnum(KEY_STORAGE, ...)` |
| `android/app/src/main/kotlin/com/cyanix/pixfold/ui/settings/SettingsScreen.kt` | `:53` import、`:79` `TAG_SETTINGS_STORAGE`、`:257-273` 整组 UI、`:760-762` `storageLabel()` |
| `android/app/src/main/res/values/settings_strings.xml` 与 `values-zh/settings_strings.xml` | `settings_storage` / `settings_storage_saf_desc` / `settings_storage_all_desc` / `settings_storage_note` / `storage_saf` / `storage_all`（各 6 条，两份必须同步删） |

### 8.2 测试连带改判

| 文件 | 处 | 改法 |
| --- | --- | --- |
| `android/app/src/test/kotlin/com/cyanix/pixfold/ui/settings/SettingsScreenTest.kt` | `:24` import、`:205-209` `storage access selection reports it upward` 整条删除、`:367` 夹具 | 删该用例与夹具字段 |
| `android/app/src/test/kotlin/com/cyanix/pixfold/data/SharedPrefsSettingsStoreTest.kt` | `:70` 夹具 | 删字段 |
| `android/domain/src/test/kotlin/com/cyanix/pixfold/domain/settings/PixFoldSettingsTest.kt` | `:35` 默认值断言、`:85`、`:97` `copy(storageAccess = ...)` 往返 | 删三处 |

### 8.3 为什么这是「删」而不是「留空壳」

`values-zh/settings_strings.xml:29` 的 `settings_storage_note` 原文是「将在接入真实文件系统后生效」—— 而 D2a 已经把真实文件系统接上了（`AndroidSafSource` 早就在跑），这个开关**从来没有**被任何代码读过（`grep 'storageAccess'` 只有设置页自己读写）。留着它等于在设置页放一个「看起来能改，改了什么都不发生」的开关，比没有更糟。

## 9. 测试计划（TDD，先红后绿）

| # | 用例 | 层 | 断什么 |
| --- | --- | --- | --- |
| T1 | 存 8 条后加第 9 条，最久未用的被挤掉 | 领域单测（`SafTreeStore` 用 Robolectric） | 条数恒 ≤ 8 且第 1 条是新的 |
| T2 | 8 条全钉住时新增被拒 | 同上 | 返回 `RememberResult.RejectedAllPinned`，列表逐字不变 |
| T3 | 已存在的 treeUri 再选一次：移到最前、pinned 保留 | 同上 | 位置与 pinned |
| T4 | 旧 key 迁移：只有 `tree_uri` 时读出 1 条 | 同上 | 迁移后旧 key 被删 |
| T5 | JSON 损坏时不抛、返回空列表 | 同上 | 不抛异常 |
| T6 | 钉住的排本区最前，图钉之间保持存储相对顺序 | 领域纯函数 | 稳定排序 |
| T7 | 拖拽只提交一次，顺序按存储下标换算 | 领域纯函数 | `moveWorkspace` 结果 |
| T8 | 分区判定：`isPersistentGrant` false 的落「只记住了路径」 | `WorkspaceController` | `listings` 的 granted 字段 |
| T9 | `open` 成功后才移到最前；失败不移动 | `WorkspaceController` | 失败后顺序不变 |
| T10 | `remove(alsoRelease = false)` 不撤销授权 | `WorkspaceController` + 假源 | 假源记录到 0 次 release |
| T11 | 首页工作区条整条可点并触发 `onOpenList` | UI | 点击计数 |
| T12 | 弹窗两个分区按 granted 划分 | UI | 各分区的行 |
| T13 | 空分区不渲染标题 | UI | 标题 doesNotExist |
| T14 | 连点 ✕ 移除后列表少一条 | UI | 行数 |
| T15 | 点图钉后该行到本区最前 | UI | 行顺序（`getBoundsInRoot` 比较 top） |
| T16 | 按钮与图钉点击目标 ≥ 48dp | UI | `bounds.height >= 48f` |
| T17 | 删掉假开关后设置页不再有该项 | UI | `TAG_SETTINGS_STORAGE` doesNotExist |

**T15 的坑**：`getBoundsInRoot()` 在 Robolectric 下能反映布局（AGENTS.md §7.4 已确认「结构/接线」可离线断），但**拖拽本身不可离线断**（手势 + 实时让位是 Robolectric 断不了的类别）⇒ 拖拽只断「提交后顺序对了」，不断「拖动过程中的让位动画」。

## 10. 验收标准

1. 四道闸门 `cd android && ./gate.sh` → `build/gate-last.rc` 为 0，app / domain / lint / assembleDebug 全绿。
2. 变异验证：至少 5 条 —— 去掉 8 上限、去掉 pinned 稳定排序、`open` 失败也移到最前、`remove` 默认撤销授权、去掉空分区跳过。全部 KILLED。
3. 真机核对（**需用户当轮单独授权**）：① 8 条列表 + 两分区渲染 ② 点图钉后置顶 ③ 长按拖拽换位 ④ ✕ 移除一条后仍能切回 ⑤ 撤销系统授权后该条落到下区 ⑥ 首页条整条可点。
4. 回写文档：`HANGOFF.md` §4（工作区入口分层）、`AGENTS.md` 头部（一句话）、`README.md`、`docs/INDEX.md` 历轮台账、`docs/phases/d4-mvp.md`。

## 11. 已知边界（写在这里，不在实现时才发现）

1. **跨分区拖拽被禁止**（§7.3）。实现上 = 拖拽命中只在本分区内换算下标；跨分区时钳回原下标。
2. **列表不显示图片张数**（§1.3）。用户可能想知道「这个目录里有多少张」，但那需要遍历，会让「打开列表」变成 N 次全盘扫描。将来若要做，应改为「点开该条时才扫」。
3. **持久授权被外部撤销时，应用收不到通知**。列表要等下一次 `openList()` 才显示 ✕ 变 ⚠。这是平台限制，不是本设计的缺陷 —— 但**必须**在文案上体现（下区标题是「只记住了路径」而不是「已失效」）。
4. **上限 8 条与 SAF 系统授权的数量无关**。列表是 PixFold 自己的记录，系统里可能挂着 20 条授权而我们只记 8 条；被挤掉的条目的系统授权**不会**自动撤销（用户裁决未要求，且自动撤销是破坏性动作）。
