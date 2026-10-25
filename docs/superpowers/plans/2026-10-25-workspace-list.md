# 工作区列表（最近 8 条 + 图钉 + 拖拽）实施计划

> **面向 Agent 执行者**：必需子技能：使用 `subagent-driven-development`（推荐）或 `executing-plans` 按任务逐项执行本计划。步骤使用复选框（`- [ ]`）语法跟踪。

**目标：** 把 PixFold 的单工作区记忆（只认最后一个目录）改成**最多 8 条的工作区列表**，带图钉置顶、长按拖拽排序、按「已持久授权 / 只记住了路径」两分区渲染，并把设置页那个从未被代码读过的假开关整组删除。

**架构：** 三层不变。领域层新增 `WorkspaceEntry` / `RememberResult` / `WorkspaceListing` 三个数据类并把 `WorkspaceSource` 的三个单工作区方法换成五个列表方法（**不留双语义**）；平台层 `SafTreeStore` 从两个标量 key 改为一个 JSON key + 旧 key 迁移，`AndroidSafSource` 实现 `isPersistentGrant`；界面层 `WorkspaceController` 新增 `listings` 状态与五个列表操作，`WorkspaceBar` 从「目录名 + 更换」改为整条可点打开列表，新增 `WorkspaceListDialog` 复用 `PixFoldDialogShell`。

**技术栈：** Kotlin 2.4.20 / AGP 9.4.1 / Compose BOM 2026.09.00 / material3 / Robolectric 4.17。持久化用 `SharedPreferences` + `kotlinx-serialization-json` 1.11.0（**本计划唯一新增依赖**，与已在用的 `kotlinx-serialization-core` 同版本，gradle 缓存里已有 jar）。拖拽沿用手写 `detectDragGesturesAfterLongPress`，**不引入任何拖拽库**。

**规格：** `docs/superpowers/specs/2026-10-25-workspace-list-design.md`（§4 数据模型、§5 接口变更、§6 控制器、§7 UI、§8 删假开关、§9 测试计划 T1–T17）

**前置：** 分支 `d4/home-workspace` 上的首页工作区条改动已完成（四道闸门绿、真机四项核完、变异 M1–M6 全 KILLED），工作树未提交。

## 全局约束

1. **上限 8 落存储层，不落 UI**（spec §5.3）。`SafTreeStore` 是唯一写入口；在显示层截断会造出「读得到 9 条、看不清第 9 条」这种无法自证的状态。
2. **顺序语义只有一条**（spec §4.3）：存储顺序 = 显示顺序；`pinned` 只在渲染时做**稳定**排序，**不改变存储位置**。图钉之间能互相拖拽互换全靠这一条。
3. **拖拽下标换算必须「先删后插」且 `sj` 不特判**（spec §4.4）。这是全功能最容易错的一处，错的表现是「拖一格只动半格」。
4. **`remove` 默认不撤销系统授权**（spec §6 第 3 条）。「从列表删掉」与「收回授权」是两件事。
5. **持久授权判定由平台层回答**，domain 层不得 `import android.*`（`AdapterBoundaryTest` 每次闸门都跑）。接口形状是**批量**的 `fun persistentGrants(): Set<String>`（唯一真相源、无默认实现），单条版 `isPersistentGrant(treeUri) = treeUri in persistentGrants()` 是接口默认实现、实现方不得覆盖 —— 理由见 Task 4 复盘（Important-2）。
6. **触控 ≥48dp**（AGENTS.md §2.7）。chip v4 的教训：`minimumInteractiveComponentSize()` 在 Robolectric 下量到 36dp，必须**加显式 `heightIn(min = 48.dp)`**。
7. **`app` 模块测试用 `org.junit.Test`**（不是 `kotlin.test.Test`）；`domain` 模块用 `kotlin.test.Test`。
8. **新增/删除字符串资源必须 values 与 values-zh 同步**，否则 lint `MissingTranslation` / `ExtraTranslation` 在 `warningsAsErrors = true` 下硬失败。
9. **闸门判绿只看 `android/build/gate-last.rc` 与日志里的 `BUILD SUCCESSFUL/FAILED`**，不看作业状态（`cmd.exe` 句柄不关会让作业永远报 running）；task 报 `UP-TO-DATE`/`FROM-CACHE` 时必须加 `--rerun-tasks` 重跑。
10. **每完成一个任务即 `git add` + `git commit`**（只提交，push 由用户处理）；每个任务完成后**第一个工具调用必须是 `todo_write`**。

## 文件结构

| 文件 | 职责 |
| --- | --- |
| `android/domain/.../domain/source/WorkspaceEntry.kt` | **新建**：`WorkspaceEntry(handle, pinned)` |
| `android/domain/.../domain/source/RememberResult.kt` | **新建**：`Added` / `MovedToFront` / `RejectedAllPinned` |
| `android/domain/.../domain/source/WorkspaceListing.kt` | **新建**：`WorkspaceListing(entry, granted)` |
| `android/domain/.../domain/source/WorkspaceSource.kt` | **改**：删三个单工作区方法，加五个列表方法 + `persistentGrants()`（单条版是派生默认实现） |
| `android/domain/.../domain/source/WorkspaceOrdering.kt` | **新建**：稳定排序 + 拖拽下标换算的纯函数 |
| `android/app/.../data/saf/SafTreeStore.kt` | **重写**：JSON 列表存储 + 旧 key 迁移 + 8 条上限 |
| `android/app/.../data/saf/AndroidSafSource.kt` | **改**：实现五个列表方法 + `persistentGrants()`（一次跨进程查询拿全集） |
| `android/app/.../ui/workspace/WorkspaceController.kt` | **改**：`listings` 状态 + `openList`/`open`/`remove`/`togglePin`/`move` |
| `android/app/.../ui/workspace/WorkspaceBar.kt` | **改**：整条可点 + 按钮文案改「工作区」+ 新增 `onOpenList` |
| `android/app/.../ui/workspace/WorkspaceListDialog.kt` | **新建**：两分区列表 + 图钉 + 拖拽 + 上限提示 + 撤销开关 |
| `android/app/.../PixFoldApp.kt` | **改**：弹窗开关状态、`openList` 接线、「添加文件夹…」走现有 launcher |
| `android/app/.../ui/settings/SettingsScreen.kt` | **改**：删假开关整组 |
| `android/domain/.../domain/settings/PixFoldSettings.kt` | **改**：删 `StorageAccess` 枚举与字段 |
| `android/app/.../data/SharedPrefsSettingsStore.kt` | **改**：删 `storageAccess` 读写 |
| `android/app/src/main/res/values{,-zh}/settings_strings.xml` | **改**：删 6 条假开关文案（两份同步） |
| `android/app/src/main/res/values{,-zh}/strings_common.xml` | **改**：新增列表弹窗文案（两份同步） |

**任务顺序**：1 领域数据类与排序纯函数 → 2 存储层 → 3 **接口变更 + 平台层 + 全部假源（一次原子改动）** → 4 控制器 → 5 工作区条 → 6 列表弹窗 → 7 接线 → 8 删假开关 → 9 变异 → 10 闸门 → 11 真机。

任务 3 之所以把「接口 + 平台层 + 假源」合成一个任务：`WorkspaceSource` 是整个工程里**被四处实现**的接口（1 个真实 + 3 个测试假源），只改接口不改编译不过、只改一半编译也不过 —— 拆成三个任务会造出两个必然红的中间态，反而让「红了是不是我上一步写错了」变成常态。任务 8（删假开关）与 1–7 无依赖，可与任意任务并行。

## 关键接口（跨任务契约，实现时逐字对齐）

```kotlin
// domain/source/WorkspaceOrdering.kt —— 任务 1 产出，任务 4/7 消费

/** 一个分区内部按 pinned 稳定排序：钉住的排最前，等键项保持原相对顺序。 */
fun orderedForDisplay(entries: List<WorkspaceListing>): List<WorkspaceListing>

/**
 * 拖拽提交：把 fromUri 这条移到 toUri 的位置，返回新的存储序列。
 * 返回 null 表示这次拖拽应被丢弃（同一位置 / URI 不在序列里 / 跨分区）。
 *
 * 入参用 treeUri 而不是下标 —— 渲染顺序经过 pinned 排序，与存储下标无线性对应。
 */
fun movedOrder(
    store: List<WorkspaceEntry>,
    fromUri: String,
    toUri: String,
): List<WorkspaceEntry>?
```

```kotlin
// data/saf/SafTreeStore.kt —— 任务 2 产出，任务 3 消费
class SafTreeStore(context: Context) {
    fun loadAll(): List<WorkspaceEntry>
    fun remember(handle: WorkspaceHandle): RememberResult
    fun forget(treeUri: String)
    fun setPinned(treeUri: String, pinned: Boolean)
    fun move(from: Int, to: Int)
}
```

```kotlin
// ui/workspace/WorkspaceListDialog.kt —— 任务 7 产出，任务 8 消费
const val TAG_WORKSPACE_DIALOG = "workspace-list-dialog"
const val TAG_WORKSPACE_ADD = "workspace-list-add"
const val TAG_WORKSPACE_RELEASE_SWITCH = "workspace-list-release"
fun workspaceRowTag(treeUri: String): String = "workspace-row-$treeUri"
fun workspaceRemoveTag(treeUri: String): String = "workspace-remove-$treeUri"

@Composable
fun WorkspaceListDialog(
    listings: List<WorkspaceListing>,
    currentTreeUri: String?,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onRemove: (treeUri: String, alsoRelease: Boolean) -> Unit,
    onTogglePin: (String) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onAdd: () -> Unit,
    rejectHint: String? = null,
)
```

## Review Focus（规格暗示但正文没给测试的 5 类，每个任务里补）

1. **空列表 / 单条列表**：`moveTargetOf` 在 `size <= 1` 时不得崩，`orderedForDisplay` 空列表返回空。
2. **同一 treeUri 重复添加**：`remember` 必须走 `MovedToFront` 分支，`pinned` 保留、条数不变 —— 若实现成「无条件 add」会静默长出重复行。
3. **JSON 损坏 / 字段缺失**：`loadAll` 不抛，返回空列表；`pinned` 字段缺失时按 `false` 处理（旧数据没有这个字段）。
4. **`remove` 掉的正好是当前打开的那条**：状态必须落到 `None`（不是继续显示一个已不在列表里的目录），且不得撤销授权（`alsoRelease = false` 时）。
5. **第 9 条被挤掉时正好是钉住的**：剔除循环必须跳过 `pinned`，全钉住时返回 `RejectedAllPinned` 且列表一个字不变。
## Task 1: 领域数据类与排序纯函数

**Files**
- Create `android/domain/src/main/kotlin/com/cyanix/pixfold/domain/source/WorkspaceEntry.kt`
- Create `android/domain/src/main/kotlin/com/cyanix/pixfold/domain/source/RememberResult.kt`
- Create `android/domain/src/main/kotlin/com/cyanix/pixfold/domain/source/WorkspaceListing.kt`
- Create `android/domain/src/main/kotlin/com/cyanix/pixfold/domain/source/WorkspaceOrdering.kt`
- Test `android/domain/src/test/kotlin/com/cyanix/pixfold/domain/source/WorkspaceOrderingTest.kt`

**Interfaces**
- Consumes: `com.cyanix.pixfold.domain.source.WorkspaceHandle`（已存在，`data class WorkspaceHandle(val treeUri: String, val displayName: String)`）
- Produces: `WorkspaceEntry` / `RememberResult` / `WorkspaceListing` / `orderedForDisplay()` / `movedOrder()` —— 任务 2/3/4/6 全部依赖这些名字，**逐字对齐，不得改名**。

**为什么先做这一层**：排序与下标换算是这个功能里唯一「错了不崩、只是看起来有点毛病」的部分（拖一格只动半格、钉住的图钉之间换不过去）。把它们抽成纯函数，就能在 domain 层用最便宜的方式钉死，不必借 Robolectric 的 UI 栈。

- [ ] **Step 1: 写失败的测试**

创建 `android/domain/src/test/kotlin/com/cyanix/pixfold/domain/source/WorkspaceOrderingTest.kt`。注意 domain 模块用 `kotlin.test.Test`，不是 `org.junit.Test`。

```kotlin
package com.cyanix.pixfold.domain.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkspaceOrderingTest {

    private fun entry(name: String, pinned: Boolean = false) =
        WorkspaceEntry(WorkspaceHandle(treeUri = "content://tree/$name", displayName = name), pinned)

    private fun listing(name: String, pinned: Boolean = false, granted: Boolean = true) =
        WorkspaceListing(entry(name, pinned), granted)

    // ---- Review Focus 1：空列表与单条列表 ----

    @Test
    fun `空列表排序后仍是空`() {
        assertEquals(0, orderedForDisplay(emptyList()).size)
    }

    @Test
    fun `单条列表的拖拽被丢弃`() {
        val store = listOf(entry("a"))
        assertNull(movedOrder(store, "content://tree/a", "content://tree/a"))
    }

    @Test
    fun `URI 不在序列里时拖拽被丢弃`() {
        val store = listOf(entry("a"), entry("b"))
        assertNull(movedOrder(store, "content://tree/不存在", "content://tree/b"))
        assertNull(movedOrder(store, "content://tree/a", "content://tree/不存在"))
    }

    // ---- Review Focus 2：钉住的排最前，图钉之间保持存储相对顺序 ----

    @Test
    fun `钉住的排本区最前`() {
        val input = listOf(listing("a"), listing("b"), listing("c", pinned = true))
        assertEquals(listOf("c", "a", "b"), orderedForDisplay(input).map { it.entry.handle.displayName })
    }

    @Test
    fun `多个图钉之间保持存储相对顺序`() {
        // c 与 d 都钉住；存储里 c 在前，排序后仍须 c 在前 —— 这条就是「图钉之间可互换」的地基。
        val input = listOf(listing("a"), listing("c", pinned = true), listing("b"), listing("d", pinned = true))
        assertEquals(listOf("c", "d", "a", "b"), orderedForDisplay(input).map { it.entry.handle.displayName })
    }

    @Test
    fun `全部钉住时顺序不变`() {
        val input = listOf(listing("a", pinned = true), listing("b", pinned = true))
        assertEquals(listOf("a", "b"), orderedForDisplay(input).map { it.entry.handle.displayName })
    }

    // ---- 拖拽下标换算（spec §4.4 的先删后插） ----

    @Test
    fun `向前拖：被移的条目落到目标条目原来的位置上`() {
        val store = listOf(entry("a"), entry("b"), entry("c"), entry("d"))
        // 把 a 拖到 c 的位置 ⇒ b c a d
        val out = movedOrder(store, "content://tree/a", "content://tree/c")!!
        assertEquals(listOf("b", "c", "a", "d"), out.map { it.handle.displayName })
    }

    @Test
    fun `向后拖：被移的条目落到目标条目原来的位置上`() {
        val store = listOf(entry("a"), entry("b"), entry("c"), entry("d"))
        // 把 d 拖到 b 的位置 ⇒ a d b c
        val out = movedOrder(store, "content://tree/d", "content://tree/b")!!
        assertEquals(listOf("a", "d", "b", "c"), out.map { it.handle.displayName })
    }

    @Test
    fun `相邻互换`() {
        val store = listOf(entry("a"), entry("b"))
        assertEquals(listOf("b", "a"), movedOrder(store, "content://tree/a", "content://tree/b")!!.map { it.handle.displayName })
        assertEquals(listOf("b", "a"), movedOrder(store, "content://tree/b", "content://tree/a")!!.map { it.handle.displayName })
    }

    // ---- Review Focus 5：pinned 不参与存储位置，只影响渲染 ----

    @Test
    fun `拖拽结果里 pinned 原样保留`() {
        val store = listOf(entry("a", pinned = true), entry("b"))
        val out = movedOrder(store, "content://tree/a", "content://tree/b")!!
        assertEquals(listOf(false, true), out.map { it.pinned })
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd android && cmd.exe /c "run-gradle.bat :domain:test" > build/t1.log 2>&1; echo $? > build/t1.rc; cat build/t1.rc
```

期望：`build/t1.rc` 为 1，日志里是 `e: unresolved reference: WorkspaceEntry`（或同类编译错误）。**不许跳过这一步** —— 没看见红就不知道测试有没有真的被编译进去。

- [ ] **Step 3: 实现**

`android/domain/src/main/kotlin/com/cyanix/pixfold/domain/source/WorkspaceEntry.kt`：

```kotlin
package com.cyanix.pixfold.domain.source

/**
 * 工作区列表里的一条**存储事实**。
 *
 * [pinned] 只影响渲染顺序（见 [orderedForDisplay]），**不改变存储位置** ——
 * 拖拽时若为钉住与非钉住维护两条序列，两者迟早漂移。
 */
data class WorkspaceEntry(
    val handle: WorkspaceHandle,
    val pinned: Boolean = false,
)
```

`android/domain/src/main/kotlin/com/cyanix/pixfold/domain/source/RememberResult.kt`：

```kotlin
package com.cyanix.pixfold.domain.source

/** [WorkspaceSource.rememberWorkspace] 的结果。 */
sealed interface RememberResult {
    /** 新增成功。 */
    data object Added : RememberResult

    /** 已存在同一 treeUri：已更新名字并移到最前，`pinned` 原样保留。 */
    data object MovedToFront : RememberResult

    /**
     * 已满上限且全部钉住，无处可挤。列表一个字都没改。
     *
     * 返回值不是可选项 —— 静默失败会让用户以为「添加成功但列表没变」。
     */
    data object RejectedAllPinned : RememberResult
}
```

`android/domain/src/main/kotlin/com/cyanix/pixfold/domain/source/WorkspaceListing.kt`：

```kotlin
package com.cyanix.pixfold.domain.source

/**
 * 列表项在 UI 层的完整事实：存储事实（[entry]）+ 运行时事实（[granted]）。
 *
 * **为什么 [granted] 不存进 [WorkspaceEntry]**：持久授权是系统状态，用户在系统设置或
 * 别的应用里撤销它时我们收不到通知；把它持久化等于存一个随时会过期的判断。
 */
data class WorkspaceListing(
    val entry: WorkspaceEntry,
    val granted: Boolean,
)
```

`android/domain/src/main/kotlin/com/cyanix/pixfold/domain/source/WorkspaceOrdering.kt`：

```kotlin
package com.cyanix.pixfold.domain.source

/**
 * 一个分区内部按 [WorkspaceEntry.pinned] **稳定**排序：钉住的排最前，等键项保持原相对顺序。
 *
 * 用 `sortedByDescending` 而非自己写 partition+concat：Kotlin 的排序是稳定的，
 * 手写版本一旦写成 `sortedBy { !it.pinned }` 之外的任何变体都容易丢掉稳定性，
 * 而稳定性正是「多个图钉之间仍可互相拖拽互换」的唯一依据。
 */
fun orderedForDisplay(entries: List<WorkspaceListing>): List<WorkspaceListing> =
    entries.sortedByDescending { it.entry.pinned }

/**
 * 拖拽提交：把 [fromUri] 这条移到 [toUri] 当前所在的位置。
 *
 * 返回 `null` 表示这次拖拽应被丢弃（同一位置 / URI 不在序列里）。
 * **跨分区的拒绝由调用方做** —— 纯函数看不见 `granted`，把分区判断塞进来会让这里
 * 同时依赖存储与运行时两类事实。
 *
 * ## 为什么入参是 treeUri 而不是下标
 * 渲染顺序经过 pinned 稳定排序，与存储下标没有任何线性对应关系。
 *
 * ## 为什么「先删后插」且目标下标不减一
 * 设 `si < sj`：移除后原 `sj` 的元素前移到 `sj - 1`，而「插到它前面」要求的新下标
 * 正好是 `sj - 1 + 1 = sj`；`si > sj` 时 `sj` 不受影响。两支同一个表达式，
 * 加特判反而会在其中一支上错位。
 */
fun movedOrder(
    store: List<WorkspaceEntry>,
    fromUri: String,
    toUri: String,
): List<WorkspaceEntry>? {
    if (fromUri == toUri) return null
    val si = store.indexOfFirst { it.handle.treeUri == fromUri }
    val sj = store.indexOfFirst { it.handle.treeUri == toUri }
    if (si < 0 || sj < 0) return null

    val out = store.toMutableList()
    val item = out.removeAt(si)
    out.add(sj.coerceIn(0, out.size), item)
    return out
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd android && cmd.exe /c "run-gradle.bat :domain:test" > build/t1.log 2>&1; echo $? > build/t1.rc; cat build/t1.rc
```

期望 `0`。测试数 = 原 940 + 10 = **950**（用 `android/domain/build/test-results/test/TEST-*.xml` 里的 `tests=` 之和核对，别看日志里的行数）。

- [ ] **Step 5: 提交**

```bash
git add android/domain/src/main/kotlin/com/cyanix/pixfold/domain/source/WorkspaceEntry.kt \
        android/domain/src/main/kotlin/com/cyanix/pixfold/domain/source/RememberResult.kt \
        android/domain/src/main/kotlin/com/cyanix/pixfold/domain/source/WorkspaceListing.kt \
        android/domain/src/main/kotlin/com/cyanix/pixfold/domain/source/WorkspaceOrdering.kt \
        android/domain/src/test/kotlin/com/cyanix/pixfold/domain/source/WorkspaceOrderingTest.kt
git commit -q -m "feat(workspace): 列表数据模型与排序纯函数"
```
## Task 2: 存储层（JSON 列表 + 上限 8 + 旧 key 迁移）

**Files**
- Modify `android/gradle/libs.versions.toml`（新增 `kotlinx-serialization-json`）
- Modify `android/app/build.gradle.kts`（`implementation(libs.kotlinx.serialization.json)`）
- Rewrite `android/app/src/main/kotlin/com/cyanix/pixfold/data/saf/SafTreeStore.kt`
- Test `android/app/src/test/kotlin/com/cyanix/pixfold/data/saf/SafTreeStoreTest.kt`

**Interfaces**
- Consumes: 任务 1 的 `WorkspaceEntry` / `RememberResult` / `WorkspaceHandle`
- Produces:

```kotlin
class SafTreeStore(context: Context) {
    fun loadAll(): List<WorkspaceEntry>
    fun remember(handle: WorkspaceHandle): RememberResult
    fun forget(treeUri: String)
    fun setPinned(treeUri: String, pinned: Boolean)
    fun move(from: Int, to: Int)
}

const val WORKSPACE_LIST_LIMIT: Int = 8
```

**为什么上限落在这一层**（spec §5.3）：`SafTreeStore` 是**唯一写入口**。把上限写在显示层会产生「读得到 9 条、看不清第 9 条」这种无法自证的状态 —— 存储里到底有几条，没人说得清。

- [ ] **Step 1: 加依赖**

`android/gradle/libs.versions.toml` 的 `[versions]` 段加 `kotlinxSerializationJson = "1.11.0"`（与既有的 `kotlinxSerializationCore` 同版本），`[libraries]` 段加：

```toml
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinxSerializationJson" }
```

`android/app/build.gradle.kts` 的 dependencies 里，紧跟既有的 `implementation(libs.kotlinx.serialization.core)` 之后加一行 `implementation(libs.kotlinx.serialization.json)`。

**为什么不用手写 JSON**：`org.json` 在 JDK 单测里是空壳（Robolectric 下可用、纯 JVM 下抛 `Stub!`），而本项目的 Json 只在这一处，用官方库比自造转义更安全。gradle 缓存里 `kotlinx-serialization-json-jvm/1.11.0` 已存在，可离线。

- [ ] **Step 2: 写失败的测试**

创建 `android/app/src/test/kotlin/com/cyanix/pixfold/data/saf/SafTreeStoreTest.kt`。`app` 模块用 `org.junit.Test` + Robolectric：

```kotlin
package com.cyanix.pixfold.data.saf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cyanix.pixfold.domain.source.RememberResult
import com.cyanix.pixfold.domain.source.WorkspaceHandle
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafTreeStoreTest {

    private lateinit var context: Context
    private lateinit var store: SafTreeStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = SafTreeStore(context)
    }

    private fun handle(name: String) =
        WorkspaceHandle(treeUri = "content://tree/$name", displayName = name)

    private fun seed(count: Int): List<WorkspaceHandle> =
        (1..count).map { handle("w$it").also { store.remember(it) } }

    // ---- T1：上限 8，第 9 条挤掉最久未用的 ----

    @Test
    fun `存满 8 条后第 9 条挤掉最后一条`() {
        seed(8)
        val result = store.remember(handle("new"))

        assertEquals(RememberResult.Added, result)
        val names = store.loadAll().map { it.handle.displayName }
        assertEquals(WORKSPACE_LIST_LIMIT, names.size)
        assertEquals("new", names.first(), "新加的必须排第一");
        assertFalse(names.contains("w8"), "最久未用的 w8 必须被挤掉")
        assertTrue(names.contains("w7"))
    }

    // ---- T2：全钉住时拒绝新增，列表逐字不变 ----

    @Test
    fun `8 条全钉住时新增被拒`() {
        val seeded = seed(8)
        seeded.forEach { store.setPinned(it.treeUri, true) }
        val before = store.loadAll()

        val result = store.remember(handle("new"))

        assertEquals(RememberResult.RejectedAllPinned, result)
        assertEquals(before, store.loadAll(), "拒绝时列表一个字都不能改")
    }

    @Test
    fun `只钉住最后一条时第 9 条仍能加进来`() {
        val seeded = seed(8)
        store.setPinned(seeded.last().treeUri, true)

        assertEquals(RememberResult.Added, store.remember(handle("new")))
        val names = store.loadAll().map { it.handle.displayName }
        assertEquals(WORKSPACE_LIST_LIMIT, names.size)
        assertTrue(names.contains("w8"), "钉住的那条不能被挤掉")
        assertFalse(names.contains("w7"))
    }

    // ---- T3：重复添加走 MovedToFront，保留 pinned ----

    @Test
    fun `重复添加会移到最前并保留图钉`() {
        seed(3)
        store.setPinned("content://tree/w1", true)

        val result = store.remember(handle("w1"))

        assertEquals(RememberResult.MovedToFront, result)
        val all = store.loadAll()
        assertEquals(3, all.size, "重复添加不得长出第 4 条")
        assertEquals("w1", all.first().handle.displayName)
        assertTrue(all.first().pinned, "重复添加不得丢掉图钉")
    }

    // ---- T4：旧 key 迁移 ----

    @Test
    fun `只有旧 key 时迁移出一条`() {
        context.getSharedPreferences("pixfold_workspace", Context.MODE_PRIVATE).edit()
            .putString("tree_uri", "content://tree/legacy")
            .putString("tree_display_name", "旧目录")
            .apply()

        val all = store.loadAll()

        assertEquals(1, all.size)
        assertEquals("content://tree/legacy", all.single().handle.treeUri)
        assertEquals("旧目录", all.single().handle.displayName)
        assertFalse(all.single().pinned)
    }

    @Test
    fun `迁移后旧 key 被删掉`() {
        val prefs = context.getSharedPreferences("pixfold_workspace", Context.MODE_PRIVATE)
        prefs.edit().putString("tree_uri", "content://tree/legacy").apply()

        store.loadAll()

        assertFalse(prefs.contains("tree_uri"), "迁移成功就必须删旧 key，否则下次又迁一遍")
    }

    // ---- T5：JSON 损坏不抛 ----

    @Test
    fun `JSON 损坏时返回空列表且不抛`() {
        context.getSharedPreferences("pixfold_workspace", Context.MODE_PRIVATE).edit()
            .putString("workspaces_v2", "{ 这不是 JSON")
            .apply()

        assertEquals(0, store.loadAll().size)
    }

    // ---- 图钉与顺序 ----

    @Test
    fun `切换图钉只改一条`() {
        seed(3)
        store.setPinned("content://tree/w2", true)

        val all = store.loadAll()
        assertEquals(listOf(false, true, false), all.map { it.pinned })
    }

    @Test
    fun `对不存在的 URI 设图钉无事发生`() {
        seed(2)
        val before = store.loadAll()
        store.setPinned("content://tree/不存在", true)
        assertEquals(before, store.loadAll())
    }

    @Test
    fun `拖拽提交后顺序变了`() {
        seed(3)
        store.move(0, 2)

        assertEquals(listOf("w2", "w3", "w1"), store.loadAll().map { it.handle.displayName })
    }

    @Test
    fun `越界下标被钳到合法范围`() {
        seed(3)
        store.move(0, 99)

        assertEquals(listOf("w2", "w3", "w1"), store.loadAll().map { it.handle.displayName })
    }

    @Test
    fun `移除一条不影响其他`() {
        seed(3)
        store.forget("content://tree/w2")

        assertEquals(listOf("w3", "w1"), store.loadAll().map { it.handle.displayName })
    }
}
```

**注意**：`setUp()` 里没有清理 prefs —— Robolectric 每个测试方法拿的是**新的** Application，prefs 天然隔离。若发现跨用例污染（出现过再改），才加 `prefs.edit().clear().commit()`。

- [ ] **Step 3: 跑测试确认失败**

```bash
cd android && cmd.exe /c "run-gradle.bat :app:testDebugUnitTest --rerun-tasks" > build/t2.log 2>&1; echo $? > build/t2.rc; cat build/t2.rc
```

期望 `1`。日志里应是 `e: unresolved reference: loadAll` / `remember` 之类 —— 现有 `SafTreeStore` 只有 `load()` / `save()` / `clear()`。

- [ ] **Step 4: 实现**

重写 `android/app/src/main/kotlin/com/cyanix/pixfold/data/saf/SafTreeStore.kt`：

```kotlin
package com.cyanix.pixfold.data.saf

import android.content.Context
import androidx.core.content.edit
import com.cyanix.pixfold.domain.source.RememberResult
import com.cyanix.pixfold.domain.source.WorkspaceEntry
import com.cyanix.pixfold.domain.source.WorkspaceHandle
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 列表上限。落在这里而不是 UI —— 见 spec §5.3。 */
const val WORKSPACE_LIST_LIMIT: Int = 8

/**
 * 工作区列表的持久化（SharedPreferences + JSON）。

 * ## 为什么从「两个标量 key」改成「一个 JSON key」
 * 旧格式只能表达一个目录；列表要有顺序、图钉、条数上限，标量 key 表达不了。
 * 换成一个 `workspaces_v2` 存整个数组，写入是单次 `apply()` —— 不会出现「名字写进去了、图钉没写」的半截状态。

 * ## 为什么反序列化失败当作空列表而不抛
 * 这段代码在**冷启动路径**上：抛异常等于用户一打开应用就崩，且无路可退。
 * 而损坏的数据本来也没有可挽救的信息，空列表让用户重新选一次即可。
 */
class SafTreeStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 全部条目，顺序即显示顺序（图钉只影响渲染，不影响这里）。 */
    fun loadAll(): List<WorkspaceEntry> {
        val raw = prefs.getString(KEY_LIST, null)
        if (raw.isNullOrEmpty()) return migratedFromLegacy()
        val decoded = runCatching {
            Json.decodeFromString<List<StoredEntry>>(raw)
        }.getOrNull() ?: return emptyList()
        return decoded.map { it.toEntry() }
    }

    /**
     * 记住一个工作区并把它移到最前。
     *
     * 已存在（同一 treeUri）⇒ 更新显示名 + 移到最前 + **保留 pinned**，返回 [RememberResult.MovedToFront]。
     * 已满 [WORKSPACE_LIST_LIMIT] 且全部钉住 ⇒ 一个字都不改，返回 [RememberResult.RejectedAllPinned]。
     * 否则从**尾部**往前找第一条未钉住的剔除。
     */
    fun remember(handle: WorkspaceHandle): RememberResult {
        val current = loadAll().toMutableList();
        val existing = current.indexOfFirst { it.handle.treeUri == handle.treeUri }
        if (existing >= 0) {
            val pinned = current[existing].pinned
            current.removeAt(existing)
            current.add(0, WorkspaceEntry(handle, pinned))
            persist(current)
            return RememberResult.MovedToFront
        }

        if (current.size >= WORKSPACE_LIST_LIMIT) {
            val victim = current.indexOfLast { !it.pinned }
            if (victim < 0) return RememberResult.RejectedAllPinned
            current.removeAt(victim)
        }

        current.add(0, WorkspaceEntry(handle, pinned = false))
        persist(current)
        return RememberResult.Added
    }

    /** 只从列表移除这一条；不动磁盘、不动系统授权。 */
    fun forget(treeUri: String) {
        val current = loadAll()
        val kept = current.filterNot { it.handle.treeUri == treeUri }
        if (kept.size != current.size) persist(kept)
    }

    /** 切换图钉。treeUri 不存在时无事发生。 */
    fun setPinned(treeUri: String, pinned: Boolean) {
        val current = loadAll()
        if (current.none { it.handle.treeUri == treeUri }) return
        persist(current.map { if (it.handle.treeUri == treeUri) it.copy(pinned = pinned) else it })
    }

    /** 拖拽提交：把 from 移到 to。越界时钳到合法范围。 */
    fun move(from: Int, to: Int) {
        val current = loadAll().toMutableList()
        if (from !in current.indices) return
        val item = current.removeAt(from)
        current.add(to.coerceIn(0, current.size), item)
        persist(current)
    }

    private fun persist(entries: List<WorkspaceEntry>) {
        val json = Json.encodeToString(entries.map { StoredEntry.of(it) })
        prefs.edit { putString(KEY_LIST, json) }
    }

    /**
     * 旧格式只有 `tree_uri` / `tree_display_name` 两个 key。迁移成一条 `pinned = false`。
     *
     * **顺序**：先写新 key 成功，再删旧 key —— 反过来的话写失败就永久丢了那条工作区。
     */
    private fun migratedFromLegacy(): List<WorkspaceEntry> {
        val legacyUri = prefs.getString(KEY_LEGACY_URI, null)
        if (legacyUri.isNullOrEmpty()) return emptyList()
        val entry = WorkspaceEntry(
            WorkspaceHandle(
                treeUri = legacyUri,
                displayName = prefs.getString(KEY_LEGACY_NAME, null).orEmpty(),
            ),
            pinned = false,
        )
        persist(listOf(entry))
        prefs.edit { remove(KEY_LEGACY_URI); remove(KEY_LEGACY_NAME) }
        return listOf(entry)
    }

    private companion object {
        const val PREFS_NAME = "pixfold_workspace"
        const val KEY_LIST = "workspaces_v2"
        const val KEY_LEGACY_URI = "tree_uri"
        const val KEY_LEGACY_NAME = "tree_display_name"
    }
}

/**
 * 落盘形态。与 [WorkspaceEntry] 分开是因为前者是**存储格式**（字段名一旦发布就不能改）
 * 而后者是**领域类型**（可以随便改）。
 *
 * `pinned` 给默认值 `false`：旧版本写的数据没有这个字段，缺省即未钉住。
 */
@Serializable
private data class StoredEntry(
    val treeUri: String,
    val displayName: String,
    val pinned: Boolean = false,
) {
    fun toEntry() = WorkspaceEntry(WorkspaceHandle(treeUri, displayName), pinned)

    companion object {
        fun of(entry: WorkspaceEntry) = StoredEntry(
            treeUri = entry.handle.treeUri,
            displayName = entry.handle.displayName,
            pinned = entry.pinned,
        )
    }
}
```

**注意两处标点**：上面 `remember` 与 `toEntry` 里出现了 `;` —— Kotlin 里多余的分号合法但 lint 可能报 `RedundantSemicolon`（`warningsAsErrors = true` 下是硬失败）。**实现时去掉它们**。

- [ ] **Step 5: 跑测试确认通过**

```bash
cd android && cmd.exe /c "run-gradle.bat :app:testDebugUnitTest --rerun-tasks" > build/t2.log 2>&1; echo $? > build/t2.rc; cat build/t2.rc
```

期望 `0`。

- [ ] **Step 6: 提交**

```bash
git add android/gradle/libs.versions.toml android/app/build.gradle.kts \
        android/app/src/main/kotlin/com/cyanix/pixfold/data/saf/SafTreeStore.kt \
        android/app/src/test/kotlin/com/cyanix/pixfold/data/saf/SafTreeStoreTest.kt
git commit -q -m "feat(workspace): 工作区列表存储层(JSON+上限8+旧key迁移)"
```
## Task 3: 接口变更 + 平台层 + 全部假源（一次原子改动）

**Files**
- Modify `android/domain/src/main/kotlin/com/cyanix/pixfold/domain/source/WorkspaceSource.kt`
- Modify `android/app/src/main/kotlin/com/cyanix/pixfold/data/saf/AndroidSafSource.kt`
- Modify `android/app/src/main/kotlin/com/cyanix/pixfold/ui/workspace/WorkspaceController.kt`（**仅三处调用的最小适配**，新功能留给任务 4）
- Modify `android/app/src/test/kotlin/com/cyanix/pixfold/ReadyWorkspaceSource.kt`
- Modify `android/app/src/test/kotlin/com/cyanix/pixfold/ui/workspace/WorkspaceControllerTest.kt`（三个内部假源 + 一条用例改判）
- Test `android/app/src/test/kotlin/com/cyanix/pixfold/data/saf/AndroidSafSourceGrantTest.kt`

**Interfaces**
- Consumes: 任务 1 的 `WorkspaceEntry` / `RememberResult`；任务 2 的 `SafTreeStore` 五方法
- Produces: 新 `WorkspaceSource`（任务 4/5/6/7 全部依赖它）

**为什么这三样必须在一个任务里**：`WorkspaceSource` 是全工程被**四处实现**的接口。只改接口 ⇒ 平台层与三个假源一起编译不过；只改一半 ⇒ 编译同样不过。拆开就必然造出「红了，但不知道是上一步写错了还是这一步本该红」的中间态，那是 TDD 最贵的噪声。

**本任务只让编译与既有测试重新变绿，不新增列表功能**。新增的 `listings` / `openList` / `open` / `remove` / `togglePin` / `move` 全在任务 4。

- [ ] **Step 1: 写失败的测试**

新建 `android/app/src/test/kotlin/com/cyanix/pixfold/data/saf/AndroidSafSourceGrantTest.kt`：

```kotlin
package com.cyanix.pixfold.data.saf

import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertFalse

/**
 * `isPersistentGrant` 在**没有授权**时必须返回 false。
 *
 * 这是下分区「只记住了路径」的唯一判据：若它恒返 true，用户会看到一条挂着 ✓
 * 却一点就报「授权已失效」的记录，且永远不会落到下区。
 *
 * Robolectric 下 `persistedUriPermissions` 恒为空，故这里只能断 false 这一侧；
 * true 那一侧需要真实 DocumentsProvider，属真机核对项（spec §10 第 3 条 ⑤）。
 */
@RunWith(RobolectricTestRunner::class)
class AndroidSafSourceGrantTest {

    @Test
    fun `没有系统授权时返回 false`() {
        val source = AndroidSafSource(ApplicationProvider.getApplicationContext())
        assertFalse(source.isPersistentGrant("content://tree/never-granted"))
    }
}
```

同时把 `WorkspaceControllerTest.kt` 里那条用例改判 —— 现有断言依赖已被删除的 `lastWorkspace()`：

```kotlin
    @Test
    fun `忘记工作区会清空状态并把它从列表里删掉`() = runTest {
        val source = ScriptedSource(LoadResult.Success(collections()), last = handle)
        val controller = WorkspaceController(source, testScope(), ioDispatcher = testDispatcher())
        controller.restore()
        assertIs<WorkspaceState.Ready>(controller.state)

        controller.forget()

        assertEquals(WorkspaceState.None, controller.state)
        assertTrue(source.workspaces().isEmpty(), "列表里也必须没了，否则下次启动又回来")
    }
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd android && cmd.exe /c "run-gradle.bat :app:testDebugUnitTest --rerun-tasks" > build/t3.log 2>&1; echo $? > build/t3.rc; cat build/t3.rc
```

期望 `1`。会出现两类错误：`e: unresolved reference: isPersistentGrant` / `e: unresolved reference: workspaces`，以及 `WorkspaceSource` 的既有实现方「类未实现抽象成员」的报错。

- [ ] **Step 3: 改接口**

`android/domain/src/main/kotlin/com/cyanix/pixfold/domain/source/WorkspaceSource.kt` —— 删掉 `lastWorkspace()` / `rememberWorkspace(handle)` / `forgetWorkspace()` 三个成员，换成：

```kotlin
    /** 全部已记住的工作区，顺序即显示顺序（图钉只影响渲染，不影响这里）。 */
    fun workspaces(): List<WorkspaceEntry>

    /**
     * 记住一个工作区并把它移到最前。
     *
     * 已存在（同一 treeUri）⇒ 更新显示名 + 移到最前 + **保留 pinned**。
     * 已满上限且全部钉住 ⇒ 列表一个字都不改，返回 [RememberResult.RejectedAllPinned]。
     */
    fun rememberWorkspace(handle: WorkspaceHandle): RememberResult

    /** 只从列表移除这一条；不动磁盘、不动系统授权。 */
    fun forgetWorkspace(treeUri: String)

    /** 切换图钉。treeUri 不存在时无事发生。 */
    fun setPinned(treeUri: String, pinned: Boolean)

    /** 拖拽提交：把 from 这一条移到下标 to。越界时钳到合法范围。 */
    fun moveWorkspace(from: Int, to: Int)

    /**
     * 这一条是否还持有系统持久授权。
     *
     * **只有平台层能回答** —— domain 层不得 `import android.*`（`AdapterBoundaryTest` 每次闸门都跑）。
     * 默认 `true`：假源与不支持查询的实现按「都在」处理，避免每个测试替身都要实现一遍。
     */
    fun isPersistentGrant(treeUri: String): Boolean = true
```

接口 KDoc 顶部补一段（这是本任务唯一需要「为什么」的地方）：

> `lastWorkspace()` 已被删除，调用点改 `workspaces().firstOrNull()?.handle`。**不留双语义**：本项目已经吃过一次「两套语义并存」的亏 —— `WorkspaceState.canOrganize` 在 `None`/`Denied`/`Failed` 上都返 `true`，与首页真正要问的问题相反，2026-10-24 只能另写一个 `canStartWorkflow` 绕开。

- [ ] **Step 4: 改平台层**

`AndroidSafSource.kt` —— 删 `lastWorkspace()` / 旧的 `rememberWorkspace` / `forgetWorkspace`，加五个新方法 + `isPersistentGrant`：

```kotlin
    override fun workspaces(): List<WorkspaceEntry> = treeStore.loadAll()

    override fun rememberWorkspace(handle: WorkspaceHandle): RememberResult {
        val result = treeStore.remember(handle)
        if (result == RememberResult.RejectedAllPinned) return result
        // 仍然「先存 URI 再提升授权」—— 顺序与改造前一致：即使 take 抛异常，
        // URI 也已经记住了，下次还能再从列表里点它。
        try {
            resolver.takePersistableUriPermission(
                handle.treeUri.toUri(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "takePersistableUriPermission 失败 ${handle.treeUri}", e)
        } catch (e: Exception) {
            Log.w(TAG, "takePersistableUriPermission 异常 ${handle.treeUri}", e)
        }
        return result
    }

    override fun forgetWorkspace(treeUri: String) = treeStore.forget(treeUri)

    override fun setPinned(treeUri: String, pinned: Boolean) = treeStore.setPinned(treeUri, pinned)

    override fun moveWorkspace(from: Int, to: Int) = treeStore.move(from, to)

    override fun isPersistentGrant(treeUri: String): Boolean = try {
        resolver.persistedUriPermissions.any { it.uri.toString() == treeUri && it.isReadPermission }
    } catch (e: Exception) {
        // 查询本身失败时按「没有授权」处理：让用户重选一次，比显示一个假的 ✓ 好。
        false
    }
```

补 import：`com.cyanix.pixfold.domain.source.RememberResult`、`com.cyanix.pixfold.domain.source.WorkspaceEntry`。

**另一件事**：`releasePersistableUriPermission` 现在还没有调用点（`remove(alsoRelease = true)` 是任务 4 才加）。本任务先在同文件加一个包内可见的方法，供任务 4 用：

```kotlin
    /** 撤销这一条的系统持久授权。**只删列表不做这件事** —— 两者是不同强度的动作。 */
    fun releasePersistentGrant(treeUri: String) {
        try {
            resolver.releasePersistableUriPermission(
                treeUri.toUri(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: Exception) {
            Log.w(TAG, "releasePersistableUriPermission 失败 $treeUri", e)
        }
    }
```

- [ ] **Step 5: 改控制器最小适配（三处）**

`WorkspaceController.kt`：

```kotlin
// restore()：原 `source.lastWorkspace() ?: return`
val first = source.workspaces().firstOrNull()?.handle ?: return
load(first)

// pick()：rememberWorkspace 现在有返回值；本任务先忽略（任务 4 处理被拒提示）
source.rememberWorkspace(handle)

// forget()：原 `source.forgetWorkspace()`
currentHandle()?.let { source.forgetWorkspace(it.treeUri) }
```

`currentHandle()` 已存在于该文件（`state.handleOrNull()`）。**注意 `forget()` 的现有实现先 `cancelJob()` + `closeWatcher()` 再清 source** —— 顺序不变。

- [ ] **Step 6: 改四个假源**

**6a. `android/app/src/test/kotlin/com/cyanix/pixfold/ReadyWorkspaceSource.kt`**（任务 5 之前先让编译过）：把 `lastWorkspace()` / `rememberWorkspace` / `forgetWorkspace` 三个覆写换成：

```kotlin
private val entries = mutableListOf(WorkspaceEntry(handle, pinned = false))

override fun workspaces(): List<WorkspaceEntry> = entries.toList()
override fun rememberWorkspace(handle: WorkspaceHandle): RememberResult = RememberResult.Added
override fun forgetWorkspace(treeUri: String) = Unit
override fun setPinned(treeUri: String, pinned: Boolean) = Unit
override fun moveWorkspace(from: Int, to: Int) = Unit
```

**6b–6d. `WorkspaceControllerTest.kt` 内三个假源**。逐个改：

- `ScriptedSource`：删 `var lastWorkspace: WorkspaceHandle?` / `var remembered` / `override fun lastWorkspace()` / `override fun forgetWorkspace()`，改成

```kotlin
    val entries = mutableListOf<WorkspaceEntry>().apply { last?.let { add(WorkspaceEntry(it)) } }
    var remembered: WorkspaceHandle? = null

    override fun workspaces(): List<WorkspaceEntry> = entries.toList()
    override fun rememberWorkspace(handle: WorkspaceHandle): RememberResult {
        remembered = handle
        return RememberResult.Added
    }
    override fun forgetWorkspace(treeUri: String) {
        entries.removeAll { it.handle.treeUri == treeUri }
    }
    override fun setPinned(treeUri: String, pinned: Boolean) = Unit
    override fun moveWorkspace(from: Int, to: Int) = Unit
```

  注意构造参数 `private val last: WorkspaceHandle? = null` **必须保留原名** —— 有 5 处调用点在用它（`ScriptedSource(LoadResult.Success(collections()), last = handle)`）。

- `WatchableSource` 与 `HandleKeyedSource`：同样把三个旧覆写换成上面五个（`rememberWorkspace` 直接 `return RememberResult.Added` 即可，这两个假源不关心 remember）。

- 用例 `失败结果携带原始信息供调试` 不受影响。检查 `grep -n 'lastWorkspace\|remembered\|\.forget()' android/app/src/test -r`，把所有引用旧 API 的地方清干净。

- [ ] **Step 7: 跑测试确认通过**

```bash
cd android && cmd.exe /c "run-gradle.bat :app:testDebugUnitTest :domain:test --rerun-tasks" > build/t3.log 2>&1; echo $? > build/t3.rc; cat build/t3.rc
```

期望 `0`。app 测试数 = 任务 2 结束后那一次 + 1（新加 `AndroidSafSourceGrantTest`）。

- [ ] **Step 8: 提交**

```bash
git add -A android/domain/src/main android/app/src/main android/app/src/test
git commit -q -m "refactor(workspace): WorkspaceSource 换列表接口并落地平台层"
```
## Task 4: 控制器（listings + 五个列表操作）

**Files**
- Modify `android/app/src/main/kotlin/com/cyanix/pixfold/ui/workspace/WorkspaceController.kt`
- Test `android/app/src/test/kotlin/com/cyanix/pixfold/ui/workspace/WorkspaceControllerListTest.kt`

**Interfaces**
- Consumes: 任务 1 的 `orderedForDisplay` / `movedOrder` / `WorkspaceListing`；任务 3 的新 `WorkspaceSource`
- Produces（任务 6/7 依赖这些名字）：

```kotlin
var listings: List<WorkspaceListing>   // by mutableStateOf(emptyList()), private set
var listRejected: Boolean             // by mutableStateOf(false), private set —— 上一次 remember 被拒
fun openList()
fun open(treeUri: String)
fun remove(treeUri: String, alsoRelease: Boolean)
fun togglePin(treeUri: String)
fun move(fromUri: String, toUri: String)   // 注意：入参是 treeUri，不是下标
```

> ⚠️ `move` 的签名在 spec §6 里写的是 `move(from: Int, to: Int)`。**实现按上表的 treeUri 版本** —— 拖拽组件提交的是渲染顺序里的位置，经过 pinned 稳定排序后与存储下标没有线性对应，用下标必须在调用点先换算，而那个换算正是 spec §4.4 交给 `movedOrder` 做的事。纯函数已经把下标运算收在内部。

**为什么这一层需要测试**：`WorkspaceController` 是平台层与界面层之间唯一有分支逻辑的地方。它的缺陷形态极隐蔽 —— 列表打开时显示过期的 ✓、打不开的记录霸占第一位、移除当前打开的那条之后界面还停在一个已经不存在的目录上。都不崩、不报错，只是显得「这应用有点毛病」。

- [ ] **Step 1: 写失败的测试**

新建 `android/app/src/test/kotlin/com/cyanix/pixfold/ui/workspace/WorkspaceControllerListTest.kt`。复用一个能记住顺序、能判定授权的假源：

```kotlin
package com.cyanix.pixfold.ui.workspace

import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.domain.source.LoadResult
import com.cyanix.pixfold.domain.source.RememberResult
import com.cyanix.pixfold.domain.source.WorkspaceChangeWatcher
import com.cyanix.pixfold.domain.source.WorkspaceEntry
import com.cyanix.pixfold.domain.source.WorkspaceHandle
import com.cyanix.pixfold.domain.source.WorkspaceSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceControllerListTest {

    private fun TestScope.testScope() = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
    private fun TestScope.testDispatcher() = UnconfinedTestDispatcher(testScheduler)

    private fun handle(name: String) = WorkspaceHandle("content://tree/$name", name)

    /** 能记住顺序、能按 URI 判授权的假源。 */
    private class ListSource(
        var entries: MutableList<WorkspaceEntry> = mutableListOf(),
        var granted: Set<String> = emptySet(),
        var result: LoadResult = LoadResult.Success(emptyList()),
    ) : WorkspaceSource {
        var rememberCalls = 0
        var released = mutableListOf<String>()
        var pinnedAtRelease = mutableListOf<String>()

        override fun workspaces(): List<WorkspaceEntry> = entries.toList()
        override fun rememberWorkspace(handle: WorkspaceHandle): RememberResult {
            rememberCalls++
            entries.removeAll { it.handle.treeUri == handle.treeUri }
            entries.add(0, WorkspaceEntry(handle, false))
            return RememberResult.MovedToFront
        }
        override fun forgetWorkspace(treeUri: String) { entries.removeAll { it.handle.treeUri == treeUri } }
        override fun setPinned(treeUri: String, pinned: Boolean) {
            val i = entries.indexOfFirst { it.handle.treeUri == treeUri }
            if (i >= 0) entries[i] = entries[i].copy(pinned = pinned)
        }
        override fun moveWorkspace(from: Int, to: Int) {
            val item = entries.removeAt(from)
            entries.add(to.coerceIn(0, entries.size), item)
        }
        override fun isPersistentGrant(treeUri: String): Boolean = treeUri in granted
        override fun loadCollections(handle: WorkspaceHandle): LoadResult = result
        override fun readBytes(item: com.cyanix.pixfold.domain.model.SourceItem): ByteArray? = null
        override fun watchChanges(handle: WorkspaceHandle, onChange: () -> Unit): WorkspaceChangeWatcher? = null
    }

    private fun controller(source: WorkspaceSource, scope: CoroutineScope) =
        WorkspaceController(source, scope, ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)

    // ---- T8：分区判定 ----

    @Test
    fun `listings 按 granted 分区并做图钉稳定排序`() = runTest {
        val a = WorkspaceEntry(handle("a"))
        val b = WorkspaceEntry(handle("b"), pinned = true)
        val c = WorkspaceEntry(handle("c"))
        val source = ListSource(mutableListOf(a, b, c), granted = setOf(a.handle.treeUri))
        val controller = controller(source, testScope())

        controller.openList()

        assertEquals(listOf(true, false, false), controller.listings.map { it.granted })
        // 上区内：b 钉住所以在前；下区内 a 在 c 前（存储顺序）        assertEquals(listOf("b", "a", "c"), controller.listings.map { it.entry.handle.displayName })
    }

    @Test
    fun `openList 每次都重读授权，不缓存`() = runTest {
        val a = WorkspaceEntry(handle("a"))
        val source = ListSource(mutableListOf(a), granted = setOf(a.handle.treeUri))
        val controller = controller(source, testScope())
        controller.openList()
        assertTrue(controller.listings.single().granted)

        source.granted = emptySet()  // 模拟用户去系统设置里撤销了
        controller.openList()

        assertFalse(controller.listings.single().granted, "缓存授权会显示一个过期的 ✓")
    }

    // ---- T9：open 成功才移到最前 ----

    @Test
    fun `open 成功后把它移到最前`() = runTest {
        val b = WorkspaceEntry(handle("b"))
        val source = ListSource(mutableListOf(WorkspaceEntry(handle("a")), b), result = LoadResult.Success(collections()))
        val controller = controller(source, testScope())

        controller.open(b.handle.treeUri)

        assertEquals(b.handle.treeUri, source.entries.first().handle.treeUri)
        assertEquals(1, source.rememberCalls)
        assertIs<WorkspaceState.Ready>(controller.state)
    }

    @Test
    fun `open 失败时不改变顺序`() = runTest {
        val b = WorkspaceEntry(handle("b"))
        val source = ListSource(mutableListOf(WorkspaceEntry(handle("a")), b), result = LoadResult.Failed("磁盘满了"))
        val controller = controller(source, testScope())

        controller.open(b.handle.treeUri)

        assertEquals("content://tree/a", source.entries.first().handle.treeUri, "打不开的记录不该霸占第一位")
        assertEquals(0, source.rememberCalls)
    }

    // ---- Review Focus 4：移除的正好是当前打开那条 ----

    @Test
    fun `移除当前打开的那条会把状态落回 None`() = runTest {
        val a = WorkspaceEntry(handle("a"))
        val source = ListSource(mutableListOf(a), result = LoadResult.Success(collections()))
        val controller = controller(source, testScope())
        controller.open(a.handle.treeUri)
        assertIs<WorkspaceState.Ready>(controller.state)

        controller.remove(a.handle.treeUri, alsoRelease = false)

        assertEquals(WorkspaceState.None, controller.state, "不能还停在一个已不在列表里的目录上")
        assertTrue(controller.listings.isEmpty())
    }

    @Test
    fun `移除别的条目不影响当前工作区`() = runTest {
        val a = WorkspaceEntry(handle("a"))
        val source = ListSource(
            mutableListOf(a, WorkspaceEntry(handle("b"))),
            result = LoadResult.Success(collections()),
        )
        val controller = controller(source, testScope())
        controller.open(a.handle.treeUri)

        controller.remove("content://tree/b", alsoRelease = false)

        assertIs<WorkspaceState.Ready>(controller.state)
        assertEquals(listOf("a"), controller.listings.map { it.entry.handle.displayName })
    }

    // ---- T10：release 只在 alsoRelease=true 时发生 ----

    @Test
    fun `默认移除不撤销系统授权`() = runTest {
        val a = WorkspaceEntry(handle("a"))
        val source = ListSource(mutableListOf(a), granted = setOf(a.handle.treeUri))
        val controller = controller(source, testScope())

        controller.remove(a.handle.treeUri, alsoRelease = false)

        assertEquals(0, source.released.size, "只删记录不该动系统授权")
    }

    @Test
    fun `勾了同时撤销才真的撤销`() = runTest {
        val a = WorkspaceEntry(handle("a"))
        val source = ListSource(mutableListOf(a), granted = setOf(a.handle.treeUri))
        val controller = controller(source, testScope())

        controller.remove(a.handle.treeUri, alsoRelease = true)

        assertEquals(listOf(a.handle.treeUri), source.released)
    }

    // ---- T15 的领域侧：togglePin 与 move ----

    @Test
    fun `togglePin 把该条移到本区最前`() = runTest {
        val a = WorkspaceEntry(handle("a"))
        val b = WorkspaceEntry(handle("b"))
        val source = ListSource(mutableListOf(a, b), granted = setOf(a.handle.treeUri, b.handle.treeUri))
        val controller = controller(source, testScope())

        controller.togglePin(b.handle.treeUri)

        assertEquals("b", controller.listings.first().entry.handle.displayName)
        assertTrue(controller.listings.first().entry.pinned)
    }

    @Test
    fun `move 接受 treeUri 并换算成存储顺序`() = runTest {
        val a = WorkspaceEntry(handle("a"))
        val b = WorkspaceEntry(handle("b"))
        val c = WorkspaceEntry(handle("c"))
        val source = ListSource(mutableListOf(a, b, c), granted = setOf(a.handle.treeUri, b.handle.treeUri, c.handle.treeUri))
        val controller = controller(source, testScope())

        controller.move("content://tree/a", "content://tree/c")

        assertEquals(listOf("b", "c", "a"), source.entries.map { it.handle.displayName })
    }

    @Test
    fun `跨分区拖拽被丢弃`() = runTest {
        val a = WorkspaceEntry(handle("a"))
        val b = WorkspaceEntry(handle("b"))
        // a 有授权、b 没有 ⇒ 处在两个分区里
        val source = ListSource(mutableListOf(a, b), granted = setOf(a.handle.treeUri))
        val controller = controller(source, testScope())
        controller.openList()

        controller.move(a.handle.treeUri, b.handle.treeUri)

        assertEquals(listOf("a", "b"), source.entries.map { it.handle.displayName }, "跨分区不得换位，分区是运行时事实")
    }

    private fun collections(): List<ImageCollection> = emptyList()
}
```
- [ ] **Step 2: 跑测试确认失败**

```bash
cd android && cmd.exe /c "run-gradle.bat :app:testDebugUnitTest --rerun-tasks" > build/t4.log 2>&1; echo $? > build/t4.rc; cat build/t4.rc
```

期望 `1`，`e: unresolved reference: listings / openList / togglePin`。

- [ ] **Step 3: 实现**

在 `WorkspaceController` 里加这些成员（放在现有 `state` / `isRefreshing` 之后、`restore()` 之前）：

```kotlin
    /**
     * 列表快照。**含 granted 运行时判定**，每次 [openList] 与任何增删改后重算。
     *
     * 不缓存 granted：持久授权可能在应用外被撤销（用户在系统设置里删了），
     * 我们收不到任何通知。缓存它等于显示一个过期的 ✓。
     */
    var listings: List<WorkspaceListing> by mutableStateOf(emptyList())
        private set

    /** 上一次 [rememberWorkspace] 被「8 条全钉住」拒绝了。UI 据此提示「先取消一个图钉」。 */
    var listRejected: Boolean by mutableStateOf(false)
        private set

    /** 重读列表 + 逐条判定授权。每次打开列表都调 —— 见 [listings] 的 KDoc。 */
    fun openList() {
        refreshListings()
    }

    /**
     * 从列表里选一条切过去。
     *
     * **只在读到之后才移到最前**：失败就移到最前会让一条打不开的记录反复霸占第一位。
     * 移到最前的动作落在 [load] 的成功分支里，这里只负责发起。
     */
    fun open(treeUri: String) {
        val entry = source.workspaces().firstOrNull { it.handle.treeUri == treeUri } ?: return
        load(entry.handle)
    }

    /**
     * 从列表移除一条。
     *
     * [alsoRelease] 为 `true` 才撤销系统持久授权 —— 默认 `false`。
     * 「从列表删掉」与「收回系统授权」是两件事：混成一件会让用户手滑就丢掉授权，
     * 下次还得重新走系统选择器。
     */
    fun remove(treeUri: String, alsoRelease: Boolean) {
        val wasCurrent = currentHandle()?.treeUri == treeUri
        if (wasCurrent) {
            cancelJob()
            closeWatcher()
            state = WorkspaceState.None
        }
        source.forgetWorkspace(treeUri)
        if (alsoRelease) (source as? AndroidGrantReleaser)?.releasePersistentGrant(treeUri)
        refreshListings()
    }

    /** 切换图钉。渲染顺序立刻变（钉住的排本区最前），存储位置不动。 */
    fun togglePin(treeUri: String) {
        val entry = source.workspaces().firstOrNull { it.handle.treeUri == treeUri } ?: return
        source.setPinned(treeUri, !entry.pinned)
        refreshListings()
    }

    /**
     * 拖拽提交。入参是 **treeUri**，不是下标 —— 渲染顺序经过 pinned 稳定排序，
     * 与存储下标没有线性对应关系（换算见 [movedOrder]）。
     *
     * **跨分区的拖拽被丢弃**：分区是运行时事实，把一条「只记住了路径」拖进
     * 「已持久授权」区等于伪造授权。
     */
    fun move(fromUri: String, toUri: String) {
        val store = source.workspaces()
        val fromGranted = source.isPersistentGrant(fromUri)
        val toGranted = source.isPersistentGrant(toUri)
        if (fromGranted != toGranted) return
        val moved = movedOrder(store, fromUri, toUri) ?: return
        val from = store.indexOfFirst { it.handle.treeUri == fromUri }
        val to = moved.indexOfFirst { it.handle.treeUri == toUri }
        if (from < 0) return
        source.moveWorkspace(from, to)
        refreshListings()
    }

    private fun refreshListings() {
        listings = orderedForDisplay(
            source.workspaces().map { WorkspaceListing(it, source.isPersistentGrant(it.handle.treeUri)) },
        )
    }
```

**`move` 里 `to` 的算法不能简写。** 不要写成 `store.indexOfFirst { it.handle.treeUri == toUri }` —— 那是**移动前**的位置；先删后插之后目标条目自己也移位了。必须先在 `movedOrder` 的结果里找。

补 import：`WorkspaceEntry` / `WorkspaceListing` / `orderedForDisplay` / `movedOrder` / `AndroidGrantReleaser`（均在 `com.cyanix.pixfold.domain.source`）。

**`AndroidGrantReleaser`** —— 让 domain 保持无 `android.*` 依赖、又让控制器能撤销授权的最小接口。新建 `android/domain/src/main/kotlin/com/cyanix/pixfold/domain/source/AndroidGrantReleaser.kt`：

```kotlin
package com.cyanix.pixfold.domain.source

/**
 * 能撤销系统持久授权的能力面。
 *
 * 独立于 [WorkspaceSource] 而不是塞进去：假源（尤其只读的测试替身）不该被迫实现一个
 * 「撤销系统授权」的方法。默认不实现 = 不支持撤销，与「不支持查询授权」的处理方式一致。
 */
interface AndroidGrantReleaser {
    fun releasePersistentGrant(treeUri: String)
}
```

`AndroidSafSource` 改为 `class AndroidSafSource(context: Context) : WorkspaceSource, AndroidGrantReleaser`（`releasePersistentGrant` 在任务 3 已写好）。

**在 `load()` 的成功分支里加移到最前**（spec §6 第 2 条，一个位置覆盖 restore / pick / open 三个入口）：

```kotlin
// load() 的 launch 块里，读到 Success 之后、赋 state 之前：
        if (result is LoadResult.Success) source.rememberWorkspace(handle)
        state = result.toWorkspaceState(handle)
```

`applyRefresh()` 的成功分支同样加（`refresh()` 走另一条路径）。**自动重试成功的那一支共用同一段代码 ⇒ 自动覆盖。**

- [ ] **Step 4: 跑测试确认通过**

```bash
cd android && cmd.exe /c "run-gradle.bat :app:testDebugUnitTest --rerun-tasks" > build/t4.log 2>&1; echo $? > build/t4.rc; cat build/t4.rc
```

期望 `0`。若 `move 接受 treeUri 并换算成存储顺序` 红了，先检查 `to` 的算法有没有被简化成移动前下标。

- [ ] **Step 5: 提交**

```bash
git add android/domain/src/main/kotlin/com/cyanix/pixfold/domain/source \
        android/app/src/main/kotlin/com/cyanix/pixfold/ui/workspace/WorkspaceController.kt \
        android/app/src/main/kotlin/com/cyanix/pixfold/data/saf/AndroidSafSource.kt \
        android/app/src/test/kotlin/com/cyanix/pixfold/ui/workspace/WorkspaceControllerListTest.kt
git commit -q -m "feat(workspace): 控制器支持列表(open/remove/togglePin/move)"
```
---

## Task 5: 工作区条（整条可点，按钮改「工作区」）

**Files**
- Modify `android/app/src/main/kotlin/com/cyanix/pixfold/ui/workspace/WorkspaceBar.kt`
- Modify `android/app/src/test/kotlin/com/cyanix/pixfold/ui/workspace/WorkspaceBarTest.kt`
- Modify `android/app/src/test/kotlin/com/cyanix/pixfold/ui/workspace/OrganizeWorkspaceTest.kt`（若有断言旧按钮文案）
- Modify `android/app/src/main/res/values/strings_common.xml` 与 `values-zh/strings_common.xml`

**Interfaces**
- Consumes: 任务 4 的 `openList()`（由任务 7 接到回调上）
- Produces（任务 7 依赖）：

```kotlin
fun WorkspaceBar(
    state: WorkspaceState,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenList: () -> Unit = {},
)
```

**为什么整条可点**：`Ready` 时右侧原来只有一个「更换」按钮，点了直接弹系统选择器 —— 那是把「换一个」当成唯一动作。有列表之后，最常见的心智动作是「看看我都有哪些文件夹」，那需要一个比 48dp 小按钮大得多的命中区。整条可点，右侧文字按钮保留（它更显眼，是给「我就是要换一个」的用户）。

**文案为什么从「更换」改「工作区」**：`Ready` 态下这个按钮现在打开的是列表而非选择器，继续叫「更换」会让人以为按下去就跳系统选择器。`onPick` 仍留在签名里 —— `None`/`Denied`/`Failed` 三态下它还是「选择文件夹」的唯一入口。

- [ ] **Step 1: 写失败的测试**

`WorkspaceBarTest.kt` 里现有辅助是 `setContent(state, onPick)`、rule 名是 `rule`（`createAndroidComposeRule<ComponentActivity>()`）。追加三条：

```kotlin
    private fun setContentWithList(
        state: WorkspaceState,
        onPick: () -> Unit = {},
        onOpenList: () -> Unit = {},
    ) = rule.setContent {
        PixFoldTheme(darkTheme = false, dynamicColor = false) {
            WorkspaceBar(state = state, onPick = onPick, onOpenList = onOpenList)
        }
    }

    @Test
    fun `就绪态点击整条会打开列表`() {
        var opened = 0
        setContentWithList(WorkspaceState.Ready(handle, emptyList()), onOpenList = { opened++ })

        rule.onNodeWithTag(TAG_WORKSPACE_BAR).performClick()

        assertEquals(1, opened, "整条都该能打开列表，不能只有右侧小按钮")
    }

    @Test
    fun `就绪态右侧按钮打开列表而不是系统选择器`() {
        var opened = 0
        var picked = 0
        setContentWithList(
            WorkspaceState.Ready(handle, emptyList()),
            onPick = { picked++ },
            onOpenList = { opened++ },
        )

        rule.onNodeWithTag(TAG_WORKSPACE_ACTION).performClick()

        assertEquals(1, opened)
        assertEquals(0, picked, "就绪态的按钮是「工作区」，不是「更换」")
    }

    @Test
    fun `未选工作区时点击整条仍直接走系统选择器`() {
        var opened = 0
        var picked = 0
        setContentWithList(WorkspaceState.None, onPick = { picked++ }, onOpenList = { opened++ })

        rule.onNodeWithTag(TAG_WORKSPACE_BAR).performClick()

        assertEquals(1, picked, "没有列表可开时，整条点击必须仍能选文件夹")
        assertEquals(0, opened)
    }
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd android && cmd.exe /c "run-gradle.bat :app:testDebugUnitTest --rerun-tasks" > build/t5.log 2>&1; echo $? > build/t5.rc; cat build/t5.rc
```

期望 `1`，`e: no value passed for parameter 'onOpenList'`。

- [ ] **Step 3: 实现**

`WorkspaceBar.kt` 改三处。① 签名加参数 + 决定点击行为：

```kotlin
    // 只有 Ready 有列表可开。其余态列表为空或全是失效项，点进去只会让人更困惑；
    // 直接给系统选择器 —— 那是它们唯一的出路。
    val rowAction = if (state is WorkspaceState.Ready) onOpenList else onPick
```

② `Row` 加整条点击 + 语义合并：

```kotlin
        Row(
            modifier
                .fillMaxWidth()
                .testTag(TAG_WORKSPACE_BAR)
                .clickable(onClick = rowAction)
                .semantics(mergeDescendants = true) {}
                .padding(start = 16.dp, end = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
```

> `mergeDescendants = true` 必须加。不合并时读屏把「当前工作区：测试」与右侧按钮念成两个独立可聚焦节点，而整条又是第三个可点区域 —— 用户听到三样东西，其中两样做同一件事。

③ `TextButton` 的 `onClick` 从 `onPick` 改 `rowAction`，`Ready` 分支的动作文案换新键：

```kotlin
            is WorkspaceState.Ready -> R.string.workspace_button to R.string.workspace_button_cd
```

新增两条文案（两份资源必须同步）：

```xml
<string name="workspace_button">Folders</string>
<string name="workspace_button_cd">Manage workspace folders</string>
```

```xml
<string name="workspace_button">工作区</string>
<string name="workspace_button_cd">管理工作区文件夹</string>
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd android && cmd.exe /c "run-gradle.bat :app:testDebugUnitTest --rerun-tasks" > build/t5.log 2>&1; echo $? > build/t5.rc; cat build/t5.rc
```

期望 `0`。若 `OrganizeWorkspaceTest` 有断旧文案「更换」的用例红了，改用 `TestText.of(R.string.workspace_button)`。

- [ ] **Step 5: 提交**

```bash
git add android/app/src/main/kotlin/com/cyanix/pixfold/ui/workspace/WorkspaceBar.kt \
        android/app/src/main/res \
        android/app/src/test/kotlin/com/cyanix/pixfold/ui/workspace
git commit -q -m "feat(workspace): 工作区条整条可点，按钮改「工作区」"
```
---

## Task 6: 列表弹窗

**Files**
- Create `android/app/src/main/kotlin/com/cyanix/pixfold/ui/workspace/WorkspaceDragList.kt`
- Create `android/app/src/main/kotlin/com/cyanix/pixfold/ui/workspace/WorkspaceListDialog.kt`
- Test `android/app/src/test/kotlin/com/cyanix/pixfold/ui/workspace/WorkspaceListDialogTest.kt`
- Modify `android/app/src/main/res/values/strings_common.xml` 与 `values-zh/strings_common.xml`（+9 条）

**Interfaces**
- Consumes: `WorkspaceListing`（任务 1）、`pinTag` 与 `PinToggle`（`ui/components/PinToggle.kt`）、`PixFoldDialogShell`（`ui/components/PixFoldDialog.kt`）
- Produces（任务 7 依赖）：

```kotlin
const val TAG_WORKSPACE_DIALOG = "workspace-list-dialog"
const val TAG_WORKSPACE_ADD = "workspace-list-add"
const val TAG_WORKSPACE_RELEASE_SWITCH = "workspace-list-release"
const val WORKSPACE_ROW_HEIGHT: Dp = 56.dp
fun workspaceRowTag(treeUri: String): String = "workspace-row-$treeUri"
fun workspaceRemoveTag(treeUri: String): String = "workspace-remove-$treeUri"

@Composable
fun WorkspaceListDialog(
    listings: List<WorkspaceListing>,
    currentTreeUri: String?,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onRemove: (treeUri: String, alsoRelease: Boolean) -> Unit,
    onTogglePin: (String) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onAdd: () -> Unit,
    rejectHint: String? = null,
)
```

**两个必须交代的设计决定**：
1. **不用 `LazyColumn`**。`PixFoldDialogShell` 正文默认 `contentScrollable = true`，内部已是 `weight(1f, fill = false).verticalScroll(...)`；再嵌 `LazyColumn` 会 `IllegalStateException: Vertically scrollable component was measured with an infinity maximum height constraints`（该 KDoc 已记）。上限只有 8 条，`Column` + `verticalScroll` 足够。
2. **拖拽要自己写**。既有 `DragReorderList` 的签名写死 `List<SourceItem>`（`ui/components/DragReorderList.kt:62`），工作区条目是 `WorkspaceListing`，套不进去。新写一个只管「行高固定、整行长按拖动」的极简版本。

- [ ] **Step 1: 写失败的测试**

新建 `android/app/src/test/kotlin/com/cyanix/pixfold/ui/workspace/WorkspaceListDialogTest.kt`：

```kotlin
package com.cyanix.pixfold.ui.workspace

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cyanix.pixfold.R
import com.cyanix.pixfold.TestText
import com.cyanix.pixfold.domain.source.WorkspaceEntry
import com.cyanix.pixfold.domain.source.WorkspaceHandle
import com.cyanix.pixfold.domain.source.WorkspaceListing
import com.cyanix.pixfold.ui.components.pinTag
import com.cyanix.pixfold.ui.theme.PixFoldTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w361dp-h794dp-560dpi")
class WorkspaceListDialogTest {

    @get:Rule val rule = createComposeRule()

    private fun listing(name: String, granted: Boolean, pinned: Boolean = false) =
        WorkspaceListing(WorkspaceEntry(WorkspaceHandle("content://tree/$name", name), pinned), granted)

    private class Captured {
        var opened: String? = null
        var removed: Pair<String, Boolean>? = null
        var pinned: String? = null
        var added = 0
    }

    private fun show(
        listings: List<WorkspaceListing>,
        current: String? = null,
        rejectHint: String? = null,
    ): Captured {
        val c = Captured()
        rule.setContent {
            PixFoldTheme(darkTheme = false, dynamicColor = false) {
                WorkspaceListDialog(
                    listings = listings,
                    currentTreeUri = current,
                    onDismiss = {},
                    onOpen = { c.opened = it },
                    onRemove = { treeUri, alsoRelease -> c.removed = treeUri to alsoRelease },
                    onTogglePin = { c.pinned = it },
                    onMove = { _, _ -> },
                    onAdd = { c.added++ },
                    rejectHint = rejectHint,
                )
            }
        }
        return c
    }

    @Test
    fun `两个分区都有内容时都画标题`() {
        show(listOf(listing("a", granted = true), listing("b", granted = false)))
        rule.onNodeWithText(TestText.of(R.string.workspace_section_granted)).assertIsDisplayed()
        rule.onNodeWithText(TestText.of(R.string.workspace_section_stale)).assertIsDisplayed()
    }

    @Test
    fun `分区为空时不画该分区标题`() {
        show(listOf(listing("a", granted = true)))
        rule.onNodeWithText(TestText.of(R.string.workspace_section_granted)).assertIsDisplayed()
        rule.onNodeWithText(TestText.of(R.string.workspace_section_stale)).assertDoesNotExist()
    }

    @Test
    fun `点一行带着 treeUri 回调 open`() {
        val c = show(listOf(listing("a", granted = true)))
        rule.onNodeWithTag(workspaceRowTag("content://tree/a")).performClick()
        assertEquals("content://tree/a", c.opened)
    }

    @Test
    fun `当前打开的那条标出使用中`() {
        show(listOf(listing("a", granted = true)), current = "content://tree/a")
        rule.onNodeWithText(TestText.of(R.string.workspace_in_use)).assertIsDisplayed()
    }

    @Test
    fun `撤销开关默认关，移除时回传 false`() {
        val c = show(listOf(listing("a", granted = true)))
        rule.onNodeWithTag(TAG_WORKSPACE_RELEASE_SWITCH).assertIsOff()
        rule.onNodeWithTag(workspaceRemoveTag("content://tree/a")).performClick()
        assertEquals("content://tree/a" to false, c.removed)
    }

    @Test
    fun `打开撤销开关后移除会回传 true`() {
        val c = show(listOf(listing("a", granted = true)))
        rule.onNodeWithTag(TAG_WORKSPACE_RELEASE_SWITCH).performClick()
        rule.onNodeWithTag(TAG_WORKSPACE_RELEASE_SWITCH).assertIsOn()
        rule.onNodeWithTag(workspaceRemoveTag("content://tree/a")).performClick()
        assertEquals("content://tree/a" to true, c.removed)
    }

    @Test
    fun `点图钉回调 togglePin`() {
        val c = show(listOf(listing("a", granted = true), listing("b", granted = true)))
        rule.onNodeWithTag(pinTag("content://tree/b")).performClick()
        assertEquals("content://tree/b", c.pinned)
    }

    @Test
    fun `添加按钮回调 onAdd`() {
        val c = show(listOf(listing("a", granted = true)))
        rule.onNodeWithTag(TAG_WORKSPACE_ADD).performClick()
        assertEquals(1, c.added)
    }

    @Test
    fun `被拒提示出现时显示`() {
        show(listOf(listing("a", granted = true)), rejectHint = "ALL_PINNED")
        rule.onNodeWithText("ALL_PINNED").assertIsDisplayed()
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd android && cmd.exe /c "run-gradle.bat :app:testDebugUnitTest --rerun-tasks" > build/t6.log 2>&1; echo $? > build/t6.rc; cat build/t6.rc
```

期望 `1`，`e: unresolved reference: WorkspaceListDialog`。

- [ ] **Step 3: 实现拖拽容器**

新建 `WorkspaceDragList.kt`：

```kotlin
@Composable
fun <T> WorkspaceDragList(
    items: List<T>,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    row: @Composable (T) -> Unit,
) {
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowHeightPx = with(LocalDensity.current) { WORKSPACE_ROW_HEIGHT.toPx() }
    // 目标下标 = 起点 + 位移折算的行数，钳在合法范围
    val targetIndex = if (dragFrom < 0) -1
        else (dragFrom + (dragOffset / rowHeightPx).roundToInt()).coerceIn(0, items.lastIndex)

    Column(modifier) {
        items.forEachIndexed { index, item ->
            val dragging = index == dragFrom
            // 让位：被拖行越过谁，谁就往反方向挪一格
            val shift = when {
                targetIndex < 0 || dragging -> 0f
                index in (dragFrom + 1)..targetIndex -> -rowHeightPx
                index in targetIndex..(dragFrom - 1) -> rowHeightPx
                else -> 0f
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(WORKSPACE_ROW_HEIGHT)
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { translationY = shift + if (dragging) dragOffset else 0f }
                    .pointerInput(items) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragFrom = index; dragOffset = 0f },
                            onDrag = { _, delta -> dragOffset += delta.y },
                            onDragEnd = {
                                val to = targetIndex
                                if (dragFrom >= 0 && to >= 0 && to != dragFrom) onMove(dragFrom, to)
                                dragFrom = -1; dragOffset = 0f
                            },
                            onDragCancel = { dragFrom = -1; dragOffset = 0f },
                        )
                    },
            ) { row(item) }
        }
    }
}
```

`const val WORKSPACE_ROW_HEIGHT: Dp = 56.dp`（56 ≥ 48dp，见 AGENTS.md §2.7）。

- [ ] **Step 4: 实现弹窗**

新建 `WorkspaceListDialog.kt`：

```kotlin
@Composable
fun WorkspaceListDialog(
    listings: List<WorkspaceListing>,
    currentTreeUri: String?,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onRemove: (treeUri: String, alsoRelease: Boolean) -> Unit,
    onTogglePin: (String) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onAdd: () -> Unit,
    rejectHint: String? = null,
) {
    // 开关不持久化：它表达「这一次移除顺便撤销授权」的临时意图。
    // 持久化会让下次手滑删掉一条就永久失去授权，而那是不可逆的。
    var releaseGrant by remember { mutableStateOf(false) }
    val granted = listings.filter { it.granted }
    val stale = listings.filterNot { it.granted }

    PixFoldDialogShell(
        onDismissRequest = onDismiss,
        tag = TAG_WORKSPACE_DIALOG,
        contentScrollable = false,  // 正文自己滚，见 KDoc 的 infinity 约束
        title = { Text(stringResource(R.string.workspace_list_title)) },
    ) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            if (rejectHint != null) {
                Text(
                    rejectHint,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (granted.isNotEmpty()) {
                SectionLabel(R.string.workspace_section_granted)
                WorkspaceDragList(granted, onMove = { f, t -> onMove(f, t) }) { entry ->
                    WorkspaceRow(entry, currentTreeUri, releaseGrant, onOpen, onTogglePin, onRemove)
                }
            }
            if (stale.isNotEmpty()) {
                SectionLabel(R.string.workspace_section_stale)
                Text(
                    stringResource(R.string.workspace_section_stale_hint),
                    style = MaterialTheme.typography.labelSmall,
                )
                // 分区内下标 → 整表下标：下分区要加 granted.size 偏移，
                // 少了这一步拖拽会改错条目，而且不会崩，只是「拖了没反应」。
                WorkspaceDragList(stale, onMove = { f, t -> onMove(granted.size + f, granted.size + t) }) { entry ->
                    WorkspaceRow(entry, currentTreeUri, releaseGrant, onOpen, onTogglePin, onRemove)
                }
            }
            TextButton(onClick = onAdd, modifier = Modifier.testTag(TAG_WORKSPACE_ADD)) {
                Text(stringResource(R.string.workspace_list_add))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.workspace_release_switch), Modifier.weight(1f))
                Switch(
                    checked = releaseGrant,
                    onCheckedChange = { releaseGrant = it },
                    modifier = Modifier.testTag(TAG_WORKSPACE_RELEASE_SWITCH),
                )
            }
        }
    }
}
```

行内容 `WorkspaceRow`：

```kotlin
Row(
    Modifier
        .fillMaxWidth()
        .testTag(workspaceRowTag(entry.handle.treeUri))
        .clickable { onOpen(entry.handle.treeUri) }
        .padding(horizontal = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Column(Modifier.weight(1f)) {
        Text(entry.handle.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (entry.handle.treeUri == currentTreeUri) {
            Text(
                stringResource(R.string.workspace_in_use),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
    // PinToggle 视觉 32dp（用户 2026-09-26 明确要求），命中外层撑到 48dp。
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        PinToggle(
            isPinned = entry.pinned,
            itemName = entry.handle.displayName,
            onClick = { onTogglePin(entry.handle.treeUri) },
            tag = pinTag(entry.handle.treeUri),
        )
    }
    IconButton(
        onClick = { onRemove(entry.handle.treeUri, releaseGrant) },
        modifier = Modifier.testTag(workspaceRemoveTag(entry.handle.treeUri)).size(48.dp),
    ) {
        Icon(
            Icons.Default.Close,
            contentDescription = stringResource(R.string.workspace_remove_cd, entry.handle.displayName),
        )
    }
}
```

> 不要改 `PinToggle` 的默认 `size = 32.dp` —— 它被 6 处复用（`ThumbRow` / `DraggableThumbCard` / `DragReorderGrid` / `DragReorderList` / `SortAndPreviewPage`×2），改默认值会静默改变那些界面的密度。

**新增 9 条文案**（`values` 与 `values-zh` 必须同步）：

| key | en | zh |
| --- | --- | --- |
| `workspace_list_title` | `Workspace folders` | `工作区文件夹` |
| `workspace_section_granted` | `Persistent access` | `已持久授权` |
| `workspace_section_stale` | `Path remembered only` | `只记住了路径` |
| `workspace_section_stale_hint` | `Tap to re-grant access.` | `点按可重新授权。` |
| `workspace_list_add` | `Add folder…` | `添加文件夹…` |
| `workspace_in_use` | `In use` | `使用中` |
| `workspace_remove_cd` | `Remove %1$s from the list` | `从列表移除 %1$s` |
| `workspace_release_switch` | `Also revoke system access` | `同时撤销系统授权` |
| `workspace_reject_all_pinned` | `All folders are pinned. Unpin one to add another.` | `全部文件夹都已钉住，先取消一个图钉再加。` |

- [ ] **Step 5: 跑测试确认通过**

```bash
cd android && cmd.exe /c "run-gradle.bat :app:testDebugUnitTest --rerun-tasks" > build/t6.log 2>&1; echo $? > build/t6.rc; cat build/t6.rc
```

期望 `0`。Robolectric 默认 locale 是 en_US ⇒ 断言必须走 `TestText.of(R.string.…)`（读 `values/` 英文串），不要手写中文。

- [ ] **Step 6: 提交**

```bash
git add android/app/src/main/kotlin/com/cyanix/pixfold/ui/workspace \
        android/app/src/main/res \
        android/app/src/test/kotlin/com/cyanix/pixfold/ui/workspace
git commit -q -m "feat(workspace): 工作区列表弹窗(两分区/图钉/拖拽/撤销开关)"
```
---

## Task 7: 接线（PixFoldApp + 三处调用点）

**Files**
- Modify `android/app/src/main/kotlin/com/cyanix/pixfold/PixFoldApp.kt`
- Modify `android/app/src/main/kotlin/com/cyanix/pixfold/ui/HomeScreen.kt`
- Modify `android/app/src/main/kotlin/com/cyanix/pixfold/ui/organize/OrganizeSteps.kt`
- Modify `android/app/src/main/kotlin/com/cyanix/pixfold/ui/library/LibraryPage.kt`
- Test `android/app/src/test/kotlin/com/cyanix/pixfold/ui/HomeWorkspaceTest.kt`

**Interfaces**
- Consumes: 任务 5 的 `onOpenList`、任务 6 的 `WorkspaceListDialog` 与四个 tag、任务 4 的五个控制器方法
- Produces: 无（终端任务）

**为什么弹窗挂在 `PixFoldApp` 顶层而不是某个页面里**：列表是**全局**入口 —— 首页、工作流 A、工作流 B 三处都能打开它；而 `open` 成功之后 `state` 变 `Ready`，工作区条在工作流页会消失（§3 裁决 1）。挂在页面里就会出现「页面已经变了，弹窗还浮在上面」的错位。顶层只有一份状态。

- [ ] **Step 1: 写失败的测试**

在 `HomeWorkspaceTest.kt` 追加两条（该类的 rule 名是 `rule`，`createComposeRule()`）：

```kotlin
    @Test
    fun `就绪态点工作区条打开列表弹窗`() {
        rule.setContent { PixFoldApp(workspaceSource = ReadyWorkspaceSource()) }

        rule.onNodeWithTag(TAG_WORKSPACE_BAR).performClick()

        rule.onNodeWithTag(TAG_WORKSPACE_DIALOG).assertIsDisplayed()
    }

    @Test
    fun `弹窗里点添加文件夹不崩且弹窗还在`() {
        // Robolectric 下 ActivityResultLauncher 不会真的弹系统界面，这里只断
        // 「点了不崩、弹窗还在」——launcher 真行为属真机核对项（任务 11 第 4 条）。
        rule.setContent { PixFoldApp(workspaceSource = ReadyWorkspaceSource()) }
        rule.onNodeWithTag(TAG_WORKSPACE_BAR).performClick()

        rule.onNodeWithTag(TAG_WORKSPACE_ADD).performClick()

        rule.onNodeWithTag(TAG_WORKSPACE_DIALOG).assertIsDisplayed()
    }
```

补 import：`com.cyanix.pixfold.PixFoldApp`、`com.cyanix.pixfold.ReadyWorkspaceSource`、`com.cyanix.pixfold.ui.workspace.TAG_WORKSPACE_ADD`、`com.cyanix.pixfold.ui.workspace.TAG_WORKSPACE_DIALOG`。

> 第二条刻意只断到「不崩」。**不要**为了看起来更严而断「系统选择器被拉起」—— Robolectric 下那是假保障（本项目已有过「删掉 horizontalScroll 后 312 项全绿」的先例）。

- [ ] **Step 2: 跑测试确认失败**

```bash
cd android && cmd.exe /c "run-gradle.bat :app:testDebugUnitTest --rerun-tasks" > build/t7.log 2>&1; echo $? > build/t7.rc; cat build/t7.rc
```

期望 `1`，`e: unresolved reference: TAG_WORKSPACE_DIALOG`。

- [ ] **Step 3: 给三个页面加透传参数**

`HomeScreen.kt` / `OrganizeSteps.kt` / `LibraryPage.kt` 各自签名尾部加 `onOpenWorkspaceList: () -> Unit = {}`，并把它传给内部那个 `WorkspaceBar(... onOpenList = onOpenWorkspaceList)`。默认值保证既有测试零改动。

- [ ] **Step 4: 在 `PixFoldApp` 里接线**

① 弹窗开关 + 打开动作（放在 `workspaceController` 之后）：

```kotlin
    // 列表弹窗挂在顶层：三个页面都能打开它，而 open 之后工作区条会在工作流里消失
    // （§3 裁决 1），挂在页面里会出现「页面没了但弹窗还开着」的错位。
    var showWorkspaceList by remember { mutableStateOf(false) }

    // 打开前重读一次授权 —— isPersistentGrant 是运行时事实，可能在应用外被撤销。
    val openWorkspaceList: () -> Unit = {
        workspaceController.openList()
        showWorkspaceList = true
    }
```

② `OpenDocumentTree` launcher 的回调：

```kotlin
    val pickWorkspaceFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            workspaceController.pick(uri.toString())
            // 弹窗不关：用户可能是从「添加文件夹…」进来的，加完还要看到列表。
            if (showWorkspaceList) workspaceController.openList()
        }
    }
```

③ 三处调用点（`HomeScreen` / `OrganizeSteps` / `LibraryPage`）各补 `onOpenWorkspaceList = openWorkspaceList`。

④ 顶层渲染弹窗（放进 `DialogBlurHost` 那一层，与其它弹窗同级）：

```kotlin
    if (showWorkspaceList) {
        WorkspaceListDialog(
            listings = workspaceController.listings,
            currentTreeUri = workspaceHandle?.treeUri,
            onDismiss = { showWorkspaceList = false },
            onOpen = { treeUri ->
                // 先关弹窗再 open：open 成功会推进 state，工作区条会随之消失。
                showWorkspaceList = false
                workspaceController.open(treeUri)
            },
            onRemove = { treeUri, alsoRelease -> workspaceController.remove(treeUri, alsoRelease) },
            onTogglePin = { treeUri -> workspaceController.togglePin(treeUri) },
            onMove = { from, to ->
                // 弹窗给的是整表下标；控制器收 treeUri（任务 4 的签名），在这里换算。
                val all = workspaceController.listings
                if (from in all.indices && to in all.indices) {
                    workspaceController.move(
                        all[from].entry.handle.treeUri,
                        all[to].entry.handle.treeUri,
                    )
                }
            },
            onAdd = { pickWorkspaceFolder.launch(null) },
            rejectHint = if (workspaceController.listRejected) {
                stringResource(R.string.workspace_reject_all_pinned)
            } else {
                null
            },
        )
    }
```

- [ ] **Step 5: 跑测试确认通过**

```bash
cd android && cmd.exe /c "run-gradle.bat :app:testDebugUnitTest --rerun-tasks" > build/t7.log 2>&1; echo $? > build/t7.rc; cat build/t7.rc
```

期望 `0`。若 `AppNavigationTest` / `BackNavigationTest` 那批注入 `ReadyWorkspaceSource()` 的用例红了，先检查 `showWorkspaceList` 有没有被误初始化成 `true`（弹窗会盖住首页 tag）。

- [ ] **Step 6: 提交**

```bash
git add android/app/src/main/kotlin/com/cyanix/pixfold \
        android/app/src/test/kotlin/com/cyanix/pixfold/ui/HomeWorkspaceTest.kt
git commit -q -m "feat(workspace): 首页与工作流接入工作区列表弹窗"
```
---

## Task 8: 删掉设置里那个从没生效过的「文件访问方式」

> **可与 Task 1–7 并行**（无共享文件、无接口依赖）。若用 subagent-driven，这是唯一能安全拆出去的任务。

**Files**
- Modify `android/domain/src/main/kotlin/com/cyanix/pixfold/domain/settings/PixFoldSettings.kt`
- Modify `android/domain/src/test/kotlin/com/cyanix/pixfold/domain/settings/PixFoldSettingsTest.kt`
- Modify `android/app/src/main/kotlin/com/cyanix/pixfold/data/SharedPrefsSettingsStore.kt`
- Modify `android/app/src/test/kotlin/com/cyanix/pixfold/data/SharedPrefsSettingsStoreTest.kt`
- Modify `android/app/src/main/kotlin/com/cyanix/pixfold/ui/settings/SettingsScreen.kt`
- Modify `android/app/src/test/kotlin/com/cyanix/pixfold/ui/settings/SettingsScreenTest.kt`
- Modify `android/app/src/main/res/values/settings_strings.xml` 与 `values-zh/settings_strings.xml`（删 6 条）

**Interfaces**
- Produces: `PixFoldSettings` 少一个字段、`StorageAccess` 类型消失。**没有任何下游依赖它** —— 这是它能安全删掉的原因。

**为什么删**：它给出两个选项（`Saf` / `AllFiles`），存进 `PixFoldSettings.storageAccess`，然后**没有任何代码读它去改变行为**。字符串资源 `settings_storage_note` 自己写着「将在接入真实文件系统后生效」—— 而真实文件系统早就接上了（D2a 起 SAF 全链路可用）。用户 2026-10-25 裁决：「那就不做相册模式了；目前的方案是SAF吧」。留着它就是在设置里放一个不会生效的开关，用户切到「全部文件访问」会发现一切照旧。

> 注意：删的是**设置里的全局偏好**，不是「两个分区」（已持久授权 / 只记住路径）。后者是每次打开弹窗查 `persistedUriPermissions` 的运行时事实，与这个开关无关。

- [ ] **Step 1: 写失败的测试**

`SettingsScreenTest.kt` 三处改判：

① `all four sections and key rows are present`（:101-124）里从 `listOf(...)` 删掉 `TAG_SETTINGS_STORAGE,` 一行，并在该测试末尾补一条反向断言：

```kotlin
        // 假开关已删（2026-10-25 用户裁决）：它存的偏好从没有代码读取。
        rule.onNodeWithTag("settings-storage").assertDoesNotExist()
```

② 删掉整个 `storage access selection reports it upward`（:204-210）；同时删掉 `import com.cyanix.pixfold.domain.settings.StorageAccess`（:24）—— 否则编译报 unresolved。

③ `reset confirmation requires a second tap` 的夹具（:355-368）删掉 `storageAccess = StorageAccess.AllFiles,` 一行。

`SharedPrefsSettingsStoreTest.kt` 的 `custom`（:60-72）删掉 `storageAccess = StorageAccess.AllFiles,` 与文件里的 import。

`PixFoldSettingsTest.kt` 三处改判：删 `assertEquals(StorageAccess.Saf, s.storageAccess, "文件访问默认 SAF")`（:35，连同 import）、`copy` 夹具里的 `storageAccess = StorageAccess.AllFiles,`（:85）、`assertEquals(StorageAccess.AllFiles, changed.storageAccess)`（:97）。

> `save then load round trips every field` 断言的是**整个对象相等**（不是逐字段），所以 `custom` 少一个字段它会自动跟着变严 —— 不需要额外改断言。

- [ ] **Step 2: 跑测试确认失败**

```bash
cd android && cmd.exe /c "run-gradle.bat :app:testDebugUnitTest :domain:test --rerun-tasks" > build/t8.log 2>&1; echo $? > build/t8.rc; cat build/t8.rc
```

期望 `1`。此时是**测试先红**：`settings-storage` 还画得出来，`assertDoesNotExist` 失败（也可能先撞 `e: unresolved reference: StorageAccess`，说明测试删得比生产快 —— 两种都算红）。

- [ ] **Step 3: 删生产代码**

① `PixFoldSettings.kt`：删 `enum class StorageAccess { Saf, AllFiles }` 及其 KDoc（:80-92）、删字段 `val storageAccess: StorageAccess = StorageAccess.Saf,`（:142）。

② `SharedPrefsSettingsStore.kt`：删 import（:12）、`storageAccess = readEnum(KEY_STORAGE, …)`（:43）、`putString(KEY_STORAGE, …)`（:56）、`const val KEY_STORAGE = "storage_access"`（:100）。

> **旧 prefs 里残留的 `storage_access` 键不用清**：没有任何代码再读它，清它反而要写迁移代码（且 SharedPreferences 没有「删一个未知键」的安全 API，只能按名字删）。留一条死键的代价是几十字节。

③ `SettingsScreen.kt`：删 import（:53）、`const val TAG_SETTINGS_STORAGE = "settings-storage"`（:79）、`ChoiceRow(...)` + 两个 `Note(...)` 整段（:258-273）、`private fun storageLabel(...)`（:760-762）。

> 删完 `SectionHeader(stringResource(R.string.settings_section_behavior))` 下面直接接 `DefaultColumnsRow`。**`settings_section_behavior` 这个标题必须保留** —— 网格默认列数、预测性返回都在这一节里。

④ 两份资源各删 6 条：

```xml
settings_storage / settings_storage_saf_desc / settings_storage_all_desc
settings_storage_note / storage_saf / storage_all
```

> 六条都只有 `SettingsScreen.kt` 一个引用点（已 grep 核实），删生产代码后它们会变成 `UnusedResources` 告警 ⇒ 在 `warningsAsErrors = true` 下是**硬失败**。所以删代码和删资源必须同一次改完。

- [ ] **Step 4: 跑测试确认通过**

```bash
cd android && cmd.exe /c "run-gradle.bat :app:testDebugUnitTest :domain:test :app:lintDebug --rerun-tasks" > build/t8.log 2>&1; echo $? > build/t8.rc; cat build/t8.rc
```

期望 `0`。**必须带 `:app:lintDebug`** —— 这个任务最容易出「资源无引用」类 lint 失败，而它只在 lint 里暴露。

- [ ] **Step 5: 提交**

```bash
git add android/app/src/main android/domain/src/main android/app/src/test android/domain/src/test
git commit -q -m "refactor(settings): 删掉从未生效的「文件访问方式」开关"
```
---

## Task 9: 变异验证

**Files**
- Temporary（跑完即删）`android/.mut.py`

**目的**：证明 Task 1–7 的断言**有杀伤力**。规则来自 AGENTS.md §7.4 与 chip v4 的实战教训：
- 变异**必须逐条后台单跑**，不能一次跑多条 —— 之前一次性跑四个变异，120s 超时被中断，最后那个变异留在工作树里没还原。
- 还原**一律 `cp` 备份**，绝不用 `git checkout --`（会连未提交改动一起丢掉）。
- 跑前断言锚点**唯一**（`src.count(old) != 1` 就退出）。之前锚点缩进多写 8 个空格，导致 `ANCHOR_COUNT 0`，白白跑一轮。
- **必须加 `--rerun-tasks`**。build cache 会让 `:app:testDebugUnitTest` 报 `UP-TO-DATE` 或 `FROM-CACHE`，JUnit XML 的 mtime 不刷新 ⇒ 变异根本没跑，看起来「存活」。
- 脚本判完成看**落盘文件**（`build/t.rc`）与日志里的 `BUILD SUCCESSFUL|BUILD FAILED`，**不看 bash 作业状态** —— `cmd.exe` 句柄不关会让作业永远报 running。

- [ ] **Step 1: 写变异脚本**

`android/.mut.py`，参数化单条执行（`python3 .mut.py M1`）：

```python
import json, shutil, subprocess, sys, pathlib, re

MUT = {
    'M1': ('app/src/main/kotlin/com/cyanix/pixfold/data/saf/SafTreeStore.kt',
           'private const val LIMIT = 8',
           'private const val LIMIT = 100',
           '去掉 8 上限会活下来 ⇒ 上限断言没杀伤力'),
    'M2': ('domain/src/main/kotlin/com/cyanix/pixfold/domain/source/WorkspaceOrdering.kt',
           'entries.sortedByDescending { it.entry.pinned }',
           'entries',
           '去掉 pinned 稳定排序 ⇒ 图钉置顶断言必须红'),
    'M3': ('app/src/main/kotlin/com/cyanix/pixfold/ui/workspace/WorkspaceController.kt',
           'if (result is LoadResult.Success) {',
           'if (true) {',
           'open 失败也移最前 ⇒ 顺序断言必须红'),
    'M4': ('app/src/main/kotlin/com/cyanix/pixfold/ui/workspace/WorkspaceController.kt',
           'if (alsoRelease) {',
           'if (true) {',
           'remove 默认就撤销授权 ⇒ 默认不撤销断言必须红'),
    'M5': ('app/src/main/kotlin/com/cyanix/pixfold/ui/workspace/WorkspaceListDialog.kt',
           'if (granted.isNotEmpty()) {',
           'if (true) {',
           '空分区也画标题 ⇒ 空分区断言必须红'),
    'M6': ('app/src/main/kotlin/com/cyanix/pixfold/domain/source/WorkspaceOrdering.kt',
           'if (si < 0 || sj < 0) return null',
           'if (si < 0 || sj < 0) return store',
           'URI 不在序列时返回原表还是 null ⇒ 若无人红说明边界没测'),
    'M7': ('app/src/main/kotlin/com/cyanix/pixfold/ui/workspace/WorkspaceListDialog.kt',
           'onMove(granted.size + f, granted.size + t)',
           'onMove(f, t)',
           '去掉下分区下标偏移 ⇒ 拖拽会改错条目，若无人红说明拖拽没测'),
}

name = sys.argv[1]
rel, old, new, why = MUT[name]
path = pathlib.Path(rel)
orig = path.read_text()
if orig.count(old) != 1:
    print('ANCHOR_COUNT', orig.count(old)); sys.exit(2)
bak = pathlib.Path('.mutbak') / path.name
bak.parent.mkdir(exist_ok=True)
shutil.copy2(path, bak)
for f in pathlib.Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    f.unlink()
try:
    path.write_text(orig.replace(old, new))
    rc = subprocess.run(['cmd.exe', '/c',
        'run-gradle.bat :app:testDebugUnitTest --rerun-tasks'],
        capture_output=True, text=True).returncode
    failed = []
    for f in pathlib.Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
        body = f.read_text()
        for m in re.finditer(r'<testcase name="([^"]+)" classname="([^"]+)"', body):
            tail = body[m.start():m.start()+4000]
            if '<failure' in tail:
                failed.append(m.group(2).split('.')[-1] + ' > ' + m.group(1))
    print(json.dumps({'mutant': name, 'rc': rc, 'why': why,
                      'verdict': 'KILLED' if failed else 'SURVIVED',
                      'failed': failed}, ensure_ascii=False, indent=1))
finally:
    shutil.copy2(bak, path)
    assert path.read_text() == orig, 'RESTORE MISMATCH'
    print('RESTORED ok')
```

> 锚点必须与源码**逐字一致**（含缩进）。写脚本前先 `grep -n '<锚点片段>' <文件>` 确认唯一且缩进对得上。

- [ ] **Step 2: 逐条跑（每条单独一次 bash 调用，不要串在一个作业里）**

```bash
cd android && python3 .mut.py M1
```

其余 M2…M7 同理，**一条一个作业**。读完一条的结果再跑下一条。

期望：**七条全部 KILLED**。

| 变异 | 预期的红 |
| --- | --- |
| M1 | `SafTreeStoreTest` 的第 9 条添加 / `WorkspaceControllerListTest` 的列表上限用例 |
| M2 | `WorkspaceOrderingTest` 的「钉住排最前」+ `WorkspaceControllerListTest` 的 togglePin |
| M3 | `WorkspaceControllerListTest` 的「open 失败不动顺序」 |
| M4 | `WorkspaceControllerListTest` 的「默认不撤销授权」 |
| M5 | `WorkspaceListDialogTest` 的「分区为空时不画该分区标题」 |
| M6 | `WorkspaceOrderingTest` 的「URI 不在序列」 |
| M7 | 若 `WorkspaceListDialogTest` 无对应用例 ⇒ **SURVIVED 是真实结果**，此时**补测试**再重跑（见 Step 3） |

- [ ] **Step 3: 处理存活的变异**

**存活不等于脚本写错了。** chip v4 的教训：M5（`showsWorkspaceBar` 条件退回 `!= null`）首轮 SURVIVED，因为工作流 B 侧根本没有测试守那条 —— 补齐 `LibraryPageTest` 两条后转 KILLED。那正说明「两处调用点必须同时改」这条约束此前无人守。

所以：存活 ⇒ **先补一条能杀掉它的断言**，再重跑确认 KILLED。若确认该行为**无法离线断言**（Robolectric 限制），则删掉变异、在计划里写明「只能真机验」，**不留假保障**。

- [ ] **Step 4: 清理**

```bash
cd /mnt/d/Programing/Personal/pixfold && rm -rf android/.mut.py android/.mutbak && git status --porcelain
```

期望 `git status` 里**没有**变异残留（改动集应与 Step 1 之前完全一致）。

---

## Task 10: 四道闸门

- [ ] **Step 1: 强制重跑**

```bash
cd android && cmd.exe /c "run-gradle.bat :app:testDebugUnitTest :app:lintDebug :domain:test :app:assembleDebug --rerun-tasks" > build/gate-last.log 2>&1; echo $? > build/gate-last.rc; cat build/gate-last.rc
```

- [ ] **Step 2: 按硬判据核绿**

三条**全部**满足才算绿：
1. `android/build/gate-last.rc` 是 `0`。
2. `build/gate-last.log` 里有 `BUILD SUCCESSFUL`，且 `:app:testDebugUnitTest` / `:app:lintDebug` / `:domain:test` / `:app:assembleDebug` 四个 task 行**不是** `UP-TO-DATE` / `FROM-CACHE`（带了 `--rerun-tasks` 就必然真跑）。
3. 测试数只认 JUnit XML（`app/build/test-results/testDebugUnitTest/TEST-*.xml`、`domain/build/test-results/test/TEST-*.xml`），用 python 解析。**不要 `grep -c warning`** —— 之前把 Gradle 自己的 `--warning-mode all` 文案数成了两条 lint 告警。

基线：本轮开工前 app **764** / domain **940**。本轮预计增加：
| 任务 | app | domain |
| --- | --- | --- |
| T1 排序纯函数 | — | +10 |
| T2 存储层 | +12 | — |
| T3 平台授权 | +3 | — |
| T4 控制器 | +12 | — |
| T5 工作区条 | +3 | — |
| T6 弹窗 | +9 | — |
| T7 接线 | +2 | — |
| T8 删假开关 | −1（删了一条） +1 反向断言 | −1 |
| **合计** | **+40**（约 **804**） | **+9**（约 **949**） |

> 数字是**估算**，以实际 XML 为准；末尾附上实际读数即可，不必凑到表里的数。

- [ ] **Step 3: 记录 APK 尺寸**

```bash
cd /mnt/d/Programing/Personal/pixfold && stat -c %s android/app/build/outputs/apk/debug/app-debug.apk
```
---

## Task 11: 真机核对

**前置：必须先拿到用户当轮授权**（AGENTS.md §7.3「每轮单独授权，不继承上一轮」）。未授权时做到 Task 10 为止并汇报。

**设备**：PJX110 / Android 16 / 1264×2780px / 560dpi = **361dp×794dp** / density 3.5 / locale zh-CN。

**工具口径（全部实测过，别踩第二遍）**：
- `adb.bat connect 192.168.43.1:4444`；`dumpui.bat` 必须 `cmd.exe /c "dumpui.bat NAME"`，WSL 直跑报 `syntax error`。
- **uiautomator dump 的 bounds 单位是 px，直接用，不要乘 density。**
- `dumpui.bat` 偶发产出 2034 字节空骨架 ⇒ 改用 `adb.bat shell uiautomator dump /data/local/tmp/x.xml` + `adb.bat shell cat /data/local/tmp/x.xml`。
- 含中文/方括号的设备侧脚本**不能经 cmd.exe 直传**（会吃字符）⇒ `adb.bat push` 到 `/data/local/tmp` 再 `adb.bat shell sh`。
- 截图必须 `adb.bat shell screencap -p /data/local/tmp/x.png` + `adb.bat pull`；`adb exec-out screencap -p > 本地路径` 在 cmd.exe 下找不到路径。
- 统计行数在 **WSL 侧**对 stdout 切行；`adb.bat shell 'ls … | wc -l'` 经 cmd.exe 会炸（`unexpected argument '-''`）。
- 应用内滑动 x ≤ 1000（x=1200 落在手势导航边缘区，会被当成返回手势直接退出应用）。
**收尾必做**：清 `/data/local/tmp` 内 `.xml`/`.png`（计数须为 0）、`svc power stayon false`、`am force-stop com.cyanix.pixfold`。

- [ ] **Step 1: 装包 + 冷启动**

```bash
cd /mnt/d/Programing/Personal/pixfold/android && cmd.exe /c "adb.bat install -r app/build/outputs/apk/debug/app-debug.apk" && cmd.exe /c "adb.bat shell monkey -p com.cyanix.pixfold -c android.intent.category.LAUNCHER 1"
```

- [ ] **Step 2: 六项核对**

| # | 项 | 判据 |
| --- | --- | --- |
| 1 | 工作区条整条可点 | 点条左侧空白处（不是右侧按钮）⇒ 弹窗出现 |
| 2 | 两分区 | 至少造 1 条「只记住路径」：`adb shell mv` 掉一个已授权目录（或在该目录上撤销授权）⇒ 它落「只记住了路径」分区，另一条在「已持久授权」 |
| 3 | 钉住置顶 + 图钉之间可拖 | 钉第 2 条 ⇒ 跳到本区第 1 位；再钉第 3 条 ⇒ 两条钉住的能互相换位 |
| 4 | 拖拽排序 | 长按整行任意位置拖动 ⇒ 松手后顺序稳定，重开弹窗顺序不变 |
| 5 | 上限 8 | 连加到第 9 条 ⇒ 挤掉最久未打开的非钉住项；把 8 条全钉住再添加 ⇒ 出现「全部文件夹都已钉住」，且列表一条都没变 |
| 6 | ✕ 与撤销开关 | 默认关 ⇒ 移除后系统授权仍在（重加同一目录不弹授权框）；打开后移除 ⇒ 再添加同一目录会重新弹授权框 |

**每项要给读数**（坐标 / 尺寸 / 条数），不能只写「已通过」。

- [ ] **Step 3: 清收**

```bash
cd /mnt/d/Programing/Personal/pixfold/android && cmd.exe /c "adb.bat shell rm -f /data/local/tmp/*.xml /data/local/tmp/*.png" && cmd.exe /c "adb.bat shell svc power stayon false" && cmd.exe /c "adb.bat shell am force-stop com.cyanix.pixfold"
```

核对 `adb.bat shell ls /data/local/tmp | wc -l` 的计数（在 WSL 侧切行），`.xml`/`.png` 须为 0。

---

## Self-Review

> 下面是我写完计划后逐条自查的记录，**执行者不需要重做**，但若发现计划与实现冲突，以 spec 为准并向用户报告。

**① spec 覆盖**

| spec 章节 | 落到哪个任务 |
| --- | --- |
| §4 数据模型（WorkspaceEntry / Listing / RememberResult） | Task 1 |
| §4.3 稳定排序语义 | Task 1（`orderedForDisplay`）+ Task 9 M2 守 |
| §4.4 拖拽下标换算 | Task 1（`movedOrder` 先删后插）+ Task 4（`to` 在结果里找） |
| §5 五个接口方法 + `isPersistentGrant` | Task 3 |
| §6 控制器 listings / openList / open / remove / togglePin / move | Task 4 |
| §7 首页条整条可点 + 弹窗两分区 | Task 5 + Task 6 |
| §7 上限 8 / 图钉 / 拖拽 / 撤销开关 | Task 2（上限）+ Task 6（UI） |
| §8 删假开关 | Task 8 |
| §9 T1–T17 测试计划 | 打散到 Task 1–7 的 Step 1 |
| §10 验收 5 条变异 | Task 9（扩到 7 条） |
| §10 验收 6 项真机 | Task 11 |
| §11 已知边界 4 条 | 已逐条落进对应任务的 KDoc 与 Task 11 的「真机不可断言」说明 |

**② 步骤扫描**：没有「处理边界情况」这类不决定任何事的行。每个 Step 都指向一个可检查结果（测试红/绿、文件存在、rc 值、设备读数）。

**③ 类型一致性**：
- `RememberResult` 三值 `Added` / `MovedToFront` / `RejectedAllPinned`：Task 1 定义，Task 2 返回，Task 4 判 `RejectedAllPinned` 置 `listRejected`。三处字面一致。
- `orderedForDisplay(List<WorkspaceListing>)`：Task 1 产出，Task 4 `refreshListings()` 消费。
- `movedOrder(List<WorkspaceEntry>, String, String): List<WorkspaceEntry>?`：Task 1 产出（**吃 `WorkspaceEntry` 不是 `WorkspaceListing`** —— 它看不见 `granted`，跨分区拒绝由调用方做），Task 4 `move` 消费，且传入的是 `listings.map { it.entry }`。
- `WorkspaceBar` 的 `onOpenList`：Task 5 加，Task 7 传。三处调用点（HomeScreen / OrganizeSteps / LibraryPage）参数名统一为 `onOpenWorkspaceList`。
- `WorkspaceListDialog` 的 `onMove: (from: Int, to: Int)`（整表下标）：Task 6 产出，Task 7 在顶层换算成 treeUri 再调控制器 —— **控制器那层是 treeUri 版本**，这是有意的类型差，因为渲染顺序经 pinned 排序后与存储下标无线性对应。

**④ Review Focus 五条各有测试**：
| 风险 | 守它的测试 |
| --- | --- |
| 空列表 / 单条列表 | `WorkspaceOrderingTest` 空列表与单条；`WorkspaceListDialogTest` 分区为空不画标题 |
| 同 treeUri 重复添加 | `SafTreeStoreTest` T3（返 `MovedToFront`，不产生重复条目） |
| JSON 损坏 | `SafTreeStoreTest` T5（返空列表，不抛） |
| remove 掉当前打开那条 | `WorkspaceControllerListTest` 的「移除当前条落 None」 |
| 第 9 条被挤掉时正好钉住 | `SafTreeStoreTest` T1（找 `indexOfLast { !pinned }`）+ T2（全钉住返 `RejectedAllPinned` 且一个字不改） |

**⑤ 篇幅比例**：计划约 2 条测试用例 : 1 条实现行数，未出现「把实现代码抄一遍」的转写。唯一的例外是 Task 6 的 `WorkspaceDragList` 与 `WorkspaceListDialog` —— 那两个组件是本轮唯一没有既有参照物的新代码（既有 `DragReorderList` 类型不匹配），且拖拽的让位计算容易写错，故给了完整实现。
