# chip 瀑布流 v4 实施计划（宽区间 / 4 行文字 / 3 行收口 / series 浮层）

> **面向 Agent 执行者**：必需子技能：使用 `subagent-driven-development`（推荐）或 `executing-plans` 按任务逐项执行本计划。步骤使用复选框（`- [ ]`）语法跟踪。
>
> ⚠️ **本文件是事后追记（retro-plan）**：代码已于 2026-10-23/24 全部落地并通过四道闸门与真机核对，之后才补写。正文的步骤顺序 = 实际执行顺序，状态一律标为已勾选。补写目的有二：**① 把决策链固化下来**（为什么是 96–156、为什么 3 行、为什么 Popup 而不是同层 Box），否则后来者只能从 KDoc 与历轮台账里反向拼；**② 让同类改动有可复用的做法**（手写分行、探针否决、变异口径）。**不要按它重新实现一遍。**

**目标：** 把芯片行从「条数上限 5 + 一级行限 2 行 + 长名撑满整宽退化成一列 + 溢出开底部弹窗」改为「固定宽度区间 96–156dp 的瀑布流 + 芯片内文字可折 4 行 + 按 3 行收口 + 溢出原地展开 + 二级以 series 为顶点浮层」。

**架构：** 收口不能用 `FlowRow` —— 手写收口芯片放进 `FlowRow(maxLines = 3)` 会被裁掉（「溢出部分的唯一入口」自己先消失）。Compose 1.12.1 原生的 `FlowRowOverflow` 经探针实测**已废弃且组合阶段不可用**，故改为 `SubcomposeLayout` 自写分行：**先探测宽度算折行、再只对可见项重新组合**（两趟分离，避免被收起的芯片残留在语义树里）。二级从「兄弟行」改为 `Popup` 浮层，因为本组件必须自带 `Column`、高度只有芯片行那么高，同层 overlay 会被父级 bounds 裁掉。

**技术栈：** Kotlin 2.4.20 / AGP 9.4.1 / Compose BOM 2026.09.00（foundation-layout 1.12.1）/ material3 1.4.0 / Robolectric 4.17。**零新增三方依赖**（`PathEffect.dashPathEffect` 与 `Popup` 都来自 Compose 本体）。

**规格：** `HANGOFF.md` §9.7 D4 段 + 用户裁决原文 m00047 / m00110 / m00185 / m01885（逐条见各任务「为什么」）。

**前置：** D4b 已跑通；芯片行上一版为 `VISIBLE_SINGLE_SELECT_CHIPS = 5` + `TOP_CHIP_MAX_LINES = 2` + `AllItemsSheet`（`PixFoldBottomSheet`）。

## 全局约束

沿用项目 `AGENTS.md` §2 不可违背的产品约束与 §7 每轮固定步骤，本阶段相关要点：

1. **四道闸门只能在 Windows 侧跑**：`cd android && ./gate.sh`（内部 `run-gradle.bat :app:lintDebug :app:testDebugUnitTest :domain:test :app:assembleDebug`）。**不要在它外面套 `| tail`** —— `cmd.exe` 句柄不关会让作业永远报 running。
2. **判闸门只看落盘文件**：`android/build/gate-last.rc` + 日志里的 `BUILD SUCCESSFUL|BUILD FAILED`，**不看 job 状态**。
3. **build cache 会让 task 报 `UP-TO-DATE`/`FROM-CACHE`**，JUnit XML 的 mtime 不刷新就无法证明「本次真跑过」。要强制真跑必须加 `--rerun-tasks`（本阶段踩过：`./gate.sh` 只跑 2s 就报成功，不构成证据）。
4. **lint 判绿口径 = `rc=0`**（`warningsAsErrors = true` + `abortOnError = true`），**不是** grep `warning` 计数 —— 日志里那两条是 Gradle 自己的 `--warning-mode all` 文案。
5. **变异纪律**：变异脚本必须**逐条后台单跑 + 单条超时**，且跑前断言锚点唯一、`finally` 里 `cp` 还原（**绝不用 `git checkout --`**，会连未提交改动一起丢）。一次跑多个变异在 120s 超时被中断会把变异留在工作树里（本阶段踩过：C1 段被删没还原）。
6. **变异能存活就说明断言没有杀伤力 —— 删掉它并写明为什么刻意不设这条断言**。
7. **修复自证三层**：领域单测 → UI 层断言 → 真机实证（用户当轮单独授权，不继承上一轮）。
8. **M3 合规**（`AGENTS.md` §2.7）：触控 ≥48dp；纯图标按钮给 `contentDescription`；颜色取 `MaterialTheme` 语义角色。
9. **移植性约束**：Robolectric **不做真实文本测量**（芯片宽一律算成 ~0）。但 `Modifier.widthIn(min = 96.dp)` 是**确定性**约束、离线也生效 —— 这条是「一行 3 块、3 行 9 块、第 10 块必然溢出」能离线断言的唯一依据。

## 文件结构

| 文件 | 动作 | 职责 |
|---|---|---|
| `android/app/src/main/kotlin/com/cyanix/pixfold/ui/components/ChipRowConstants.kt` | **新建** | 全部芯片尺寸/行数常量 + `truncateChipLabel`。从 `SingleSelectChipRow.kt` 抽出，因为 `TwoLevelChipRows.kt` 也要用 `CHIP_*`，留在原文件会形成环 |
| `android/app/src/main/kotlin/com/cyanix/pixfold/ui/components/SingleSelectChipRow.kt` | 重写 | 单级芯片行 + `ChipFlow`（手写分行）+ `CollapseChip`（虚线收口）+ `SingleSelectChip`（宽度区间） |
| `android/app/src/main/kotlin/com/cyanix/pixfold/ui/components/TwoLevelChipRows.kt` | 重写 | 两级：一级行 + series 顶点 `Popup` 浮层 + 三条收起触发 |
| `android/app/src/main/kotlin/com/cyanix/pixfold/ui/components/VolumeChipRow.kt` | 改 | 删 `VISIBLE_VOLUME_CHIPS` / `visibleVolumeChips`（随条数上限退役） |
| `android/app/src/main/kotlin/com/cyanix/pixfold/ui/components/PixFoldDialog.kt` | 改 | **删** `PixFoldBottomSheet` 整段（全站唯一使用者已改原地展开） |
| `android/app/src/main/kotlin/com/cyanix/pixfold/ui/organize/CollectionChipRows.kt` | 改 | 删 6 个 sheet tag 常量；`ChipRowTags` 去掉 3 个 sheet 字段 |
| `android/app/src/main/kotlin/com/cyanix/pixfold/ui/library/MetadataPage.kt` / `PackPreviewPage.kt` | 改 | 删各自 6 个 sheet tag 常量；`TAG_*_ALL` 注释改为「原地展开」 |
| `android/app/src/main/res/values/strings_common.xml` + `values-zh/` | 改 | 新增 `chip_collapse`（Show less / 收起）。**两份必须同步**，否则 lint `MissingTranslation` 硬失败 |
| 6 个测试文件 | 改判 | 见任务 10 |

**为什么必须新开 `ChipRowConstants.kt` 而不是放原处**：`TwoLevelChipRows.kt` 的浮层要用 `CHIP_EXPANDED_MAX_HEIGHT` 与 `CHIP_MAX_WIDTH`；若常量留在 `SingleSelectChipRow.kt`，两级文件之间就变成双向依赖。而 `truncateChipLabel` 与常量是同一套规格，拆开会让「改上限要改两个文件」。

## 任务 1：抽出常量与截断函数（`ChipRowConstants.kt`）

**为什么先做这一步**：后面每个任务都要引用这些常量，且它们的值全部有推导依据 —— 先把依据写成 KDoc，避免后来者「顺手调一下试试」。

- [x] 建 `ChipRowConstants.kt`，写 `CHIP_MIN_WIDTH: Dp = 96.dp` / `CHIP_MAX_WIDTH: Dp = 156.dp`
- [x] KDoc 写清 321dp 轨道推导：361 − 8×2 − 12×2 = **321dp**；`156×2 + 4 = 316 ≤ 321`（两个长名可行）；`156×3 + 8 = 476 > 321`（三个长名不可行）；`96×3 + 8 = 296`（三个短名可行）；`96×4 + 12 = 396 > 321`（四个短名不可行）⇒ **区间 [96,156] 保证一行只能 2 或 3 个**
- [x] `CHIP_LABEL_MAX_CHARS = 30`，KDoc 记：156dp 内容宽 = 156 − padding 28 − 边框 2 = 126dp ⇒ 13.5sp 全角字 **9 字/行**，4 行 = 36 字；实测 **33 全角字 + `(512)` 仍 4 行、34 字跳 5 行** ⇒ 取 30 留余量。首 15 + … + 尾 14
- [x] `CHIP_TEXT_MAX_LINES = 4`（原 3，用户 m00110 裁决）
- [x] `CHIP_COLLAPSE_LINES = 3`，KDoc 记：3 行 ≈ 280dp = 35% 视口；不收口 40 卷 ≈ 20 行 ≈ **1860dp**，是 2026-10-18 那个 834.9dp 缺陷的两倍多
- [x] `CHIP_EXPANDED_MAX_HEIGHT: Dp = 318.dp`（≈40% 视口，与浮层上限取同值）
- [x] 移入 `truncateChipLabel(label, maxChars = CHIP_LABEL_MAX_CHARS)`：`maxChars <= 0` → `""`；`length <= maxChars` → 原样；`maxChars < 3` → `label.take(maxChars)`；否则 `tail = (maxChars - 1) / 2`、`head = maxChars - 1 - tail` → `label.take(head) + "…" + label.takeLast(tail)`
- [x] **删除** `VISIBLE_SINGLE_SELECT_CHIPS` / `TOP_CHIP_MAX_LINES` / `visibleSingleSelectItems`
- [x] 验证：`:app:compileDebugKotlin` 通过

**为什么删而不保留兼容**：条数上限（5 个）在瀑布流里会**因名字长短时松时紧** —— 同样 5 块可能是 2 行也可能是 5 行，视觉上那条线跟着名字长度跳动。行数才是视觉上真实存在的那条线。两者并存会让「到底按哪个收口」永远有歧义。

## 任务 2：`ChipFlow` —— 手写分行（探针先否决两条原生路子）

**为什么这是本计划最关键的一步**：它是整个改版唯一没有现成 API 的地方，且否决过程本身是决策依据，必须留痕。

- [x] **探针 A（原生 `FlowRowOverflow`）**：写临时 `FlowOverflowProbeTest`，用 `FlowRow(maxLines = 3, overflow = FlowRowOverflow.expandOrCollapseIndicator(...))`
  - [x] 跑出失败，异常原文：`java.lang.IllegalStateException: Accessing shownItemCount before it is set. Are you calling this in the Composition phase, rather than in the draw phase?`（栈顶 `FlowLayoutOverflow.kt:642` → `FlowRowOverflowScopeImpl.getShownItemCount`）⇒ `shownItemCount` **组合阶段不可访问**
  - [x] 同时拿到两条**编译期弃用警告**：`'fun FlowRow(... overflow: FlowRowOverflow ...)' is deprecated` / `'class FlowRowOverflow : FlowLayoutOverflow' is deprecated. FlowLayout overflow is no longer maintained.`
  - [x] **结论：放弃原生路子**（既已废弃、关键读数又只能在绘制阶段拿）
- [x] **探针 B（老重载 `overflowIndicator`）**：编译期报 `No parameter with name 'overflowIndicator' found` ⇒ 该参数在当前 foundation-layout 1.12.1 里不存在。**原生彻底封死**
- [x] 删两个探针文件
- [x] 写 `private sealed interface ChipSlot { Probe(index) / Item(index) / Indicator(expanded) }`。**三个 key 必须互不相同** —— 同 key 会让 subcomposition 复用，「清语义」的包装会漏到真实那一份上，芯片连 testTag 都没了
- [x] `internal fun ChipFlow(count, horizontalGap, verticalGap, collapseLines, expanded, modifier, indicatorTag, indicatorLabel, onToggle, item)`，`SubcomposeLayout` 内三步：
  - [x] 内部 `fun rowsOf(widths: List<Int>): List<List<Int>>` 贪心折行
  - [x] **第一趟（探测）**：`List(count) { index -> subcompose(ChipSlot.Probe(index)) { Box(Modifier.clearAndSetSemantics {}) { item(index) } }.first().measure(probeConstraints) }` → 算 `naturalRows`
  - [x] **第二趟（显示）**：`List(shownCount) { index -> subcompose(ChipSlot.Item(index)) { item(index) }.first().measure(probeConstraints) }`
  - [x] 收口判定：`overLines = collapseLines < Int.MAX_VALUE && naturalRows.size > collapseLines`；`collapsing = overLines && !expanded`
  - [x] 收起时**倒着试**：`val reserve = CHIP_MAX_WIDTH.roundToPx(); val capacity = naturalRows.take(collapseLines).sumOf { it.size }; shownCount = (capacity downTo 0).firstOrNull { candidate -> rowsOf(probePlaceables.take(candidate).map { it.width } + reserve).size <= collapseLines } ?: 0`
  - [x] `showIndicator = if (collapsing) true else expanded && overLines`
  - [x] 末行 `layout(rowWidth, totalHeight)`，逐行 `placeRelative`
- [x] `probeConstraints = Constraints(maxWidth = rowWidth)`（**不是** `Constraints()`）：无约束 measure 在父级轨道 < 156dp 时会量出比轨道更宽的块，而 place 不重测 ⇒ 静默横向溢出
- [x] 越界守卫：`val item = items.getOrNull(index) ?: return@ChipFlow`
- [x] 验证：`:app:compileDebugKotlin` RC=0

**两个真缺陷（都是这一步自己撞出来的，写进 KDoc 而不是悄悄修掉）**：

- **越界崩溃**：原写法 `subcompose(ChipSlot.Item(index))` + lambda 里直接 `items[index]`。items 从 2 项变 1 项时，上一轮残留的子组合会带着旧 index 重进 ⇒ `IndexOutOfBoundsException`（栈顶 `SingleSelectChipRow.kt:198` 经 `Recomposer.performRecompose`）。**这是真崩溃，不是测试写错。**
- **隐藏芯片仍进语义树**：先前的写法一次性 subcompose 全部 count 项、只用前 shownCount 个放置。未放置的节点**仍在语义树里** ⇒ 收起态 `onNodeWithTag("chip-item-12")` 能找到，读屏会念出看不见的条目。**这是真实无障碍缺陷。** 修法 = 两趟分离 + probe 副本套 `clearAndSetSemantics`。

**为什么 reserve 按 `CHIP_MAX_WIDTH` 算而不按收口芯片实测宽**：收口芯片宽度取决于文字（「全部 12 项」vs「收起」），按实测会在展开/收起之间反复震荡；按上限算是**保守且稳定**的 —— 代价只是可能少显示 1 块、行末空 40–60dp，不会把第 3 行撑成第 4 行。

## 任务 3：`CollapseChip` —— 虚线收口芯片

**为什么手写而不复用 `FilterChip`**：`FilterChip` 的选中态语义与「展开/收起」是两回事，且虚线外观要自绘。

- [x] `widthIn(min = CHIP_MIN_WIDTH, max = CHIP_MAX_WIDTH)` —— **尺寸必须与普通芯片同区间**，否则它会被当成另一种东西
- [x] **双层 Box**：外层 `Modifier.minimumInteractiveComponentSize().heightIn(min = 48.dp).clip(...).clickable(onClick).testTag(tag).semantics(mergeDescendants = true) { contentDescription = ...; role = Role.Button }`（承担 48dp 触摸目标 / 点击 / 语义）；内层 `widthIn(...).heightIn(min = 32.dp).clip(...).drawBehind { 虚线 }.padding(...)`（只画框与字，不被 48dp 撑高）。两者恒等宽
- [x] 虚线：`drawRoundRect(color = outline, cornerRadius = CornerRadius(8.dp.toPx()), style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))))`
- [x] 内容 `Row(Text(maxLines = 1) + Icon(if (expanded) ic_arrow_drop_up else ic_arrow_drop_down))`（两个 drawable 都已存在，不必新建）
- [x] 验证：`ChipLayoutRulesTest.the collapse chip has a 48dp touch target` 断 `entry.bottom - entry.top >= 48f`

**实测修正**：只调 `minimumInteractiveComponentSize()` 时 Robolectric 量到触摸区**只有 36dp** —— 该扩展受 `LocalMinimumInteractiveComponentEnforcement` 控制、会失效。故必须再显式加 `.heightIn(min = 48.dp)`。**这是「只信 API 文档不信实测」的教训。**

**为什么 `mergeDescendants = true`**：`.clickable` 默认不合并子孙 ⇒ 带 `contentDescription` 的 Box 与内部 Text 是两个可聚焦节点，「全部 12 项」被读屏念两遍，且 Box 不报按钮角色。

## 任务 4：`SingleSelectChip` 加宽度区间

- [x] `FilterChip(..., modifier = Modifier.widthIn(min = CHIP_MIN_WIDTH, max = CHIP_MAX_WIDTH).testTag(tag).semantics { contentDescription = ... })`
- [x] label 保持 `Text(text = truncateChipLabel(chipLabel(item)) + chipSuffix(item), textAlign = TextAlign.Center, softWrap = true, maxLines = textMaxLines, overflow = TextOverflow.Ellipsis)`
- [x] 验证：`ChipLayoutRulesTest.the collapse chip is the same size as a normal chip` 断 `chipWidth in 96f..156f`

**这一步就是「瀑布流退化成一列」的直接修复点**：改动前该 `FilterChip` **没有任何 max-width**，长名会撑满整行（实测 8 个条目排成 7 行、多数行只有 1 个，宽度 289–321dp）。

**刻意不设的断言**：曾写 `assertEquals(chipWidth, entryWidth)`，但那是 Robolectric「文本宽 ≈ 0」的巧合，真机上两者不等 ⇒ 已删，只保留区间断言。

## 任务 5：展开态限高 + 内滚（评审 C1）

**为什么必须有**：芯片行常驻在滚动容器**之外**（2026-10-18 裁决），它自己不滚。收起态有 `CHIP_COLLAPSE_LINES` 兜着，一旦展开 40 卷 ≈ 14 行 ≈ 1200dp 而视口仅 794dp，下方 `weight(1f)` 被挤成 0 ⇒ 用户**既看不到内容，也够不到收起入口**。这是 2026-10-18 那个 834.9dp 缺陷的同型形态。

- [x] `val scrollState = rememberScrollState()` + `val scope = rememberCoroutineScope()`
- [x] `ChipFlow` 的 modifier：先 `.fillMaxWidth()`，再 `.then(if (expanded) Modifier.heightIn(max = CHIP_EXPANDED_MAX_HEIGHT).verticalScroll(scrollState) else Modifier)`，再 `.padding(horizontal = 12.dp, vertical = 4.dp).testTag(selectorTag)`
- [x] **收起态刻意不套 `heightIn`**：本身有行数封顶，套了会在极端字体缩放下平白截断
- [x] `onToggle = { expanded = !expanded; if (!expanded) scope.launch { scrollState.scrollTo(0) } }`（`scrollTo` 是 suspend，第一次编译报 `ILLEGAL_SUSPEND_FUNCTION_CALL` 才发现）
- [x] 验证：`ChipLayoutRulesTest.the collapse chip stays reachable after expanding`（展开后同一枚仍 `assertIsDisplayed`，再点一次回到收起态）

## 任务 6：`TwoLevelChipRows` 二级改 series 顶点浮层

**为什么不能是兄弟行**：用户 m00110 裁决「上面 series 为顶点，展开叠加一个遮罩显示」。

- [x] 状态：`var openSeriesKey: String? by remember(groupKeys) { mutableStateOf(null) }`（**按 series key，不用布尔** —— 布尔无法表达「换到另一个 series」）、`var anchors: Map<String, Rect>`、`var container: Rect`
- [x] **`groupKeys = groups.map { it.key }` 作 remember key**（不是 `groups` 实例）：工作区重扫、ComicInfo 回填、乃至上游 `remember(collections) { map { … } }` 命中的重建都会清掉状态，表现为「展开的浮层自己关了」「上次看的那一本被忘掉」
- [x] 一级行 onSelect 三分支实现**三条收起触发**：
  - `if (!group.isSeries) { openSeriesKey = null; onSelect(first) }` —— 点 series 外单本 ⇒ 收起
  - `else if (openSeriesKey == key) { openSeriesKey = null }` —— 再点同一 series ⇒ **不调 `onSelect`**（否则顺手换书，用户没要求）
  - `else { openSeriesKey = key; if (group.members.none { idOf(it) == activeItemId }) onSelect(remembered ?: first) }`
- [x] **anchors 用 `Map<String, Rect>` 而不是单个 `Rect`**：`onChipBounds` 对每个 series 都会回调，`SubcomposeLayout` 按 index 顺序 place ⇒ 单变量会被**最后一个** series 芯片永远覆盖，点第 1 个 series 浮层却长在最后一个下面。存 Map 后渲染取 `anchors[openSeriesKey]`
- [x] `onChipBounds` 加 `bounds.width > 0f` 守卫（宽度 0 = 探测副本未真正放置，不能当锚点）
- [x] 浮层内二级行传 `collapseLines = Int.MAX_VALUE`（不收口）+ `surface = false`（卡片自带底色）
- [x] `ChildChipsPopup(anchor, topY, containerLeft, containerWidth, onDismiss, content)`：自定义 `PopupPositionProvider` + `Popup` + `Surface(RoundedCornerShape(12.dp), surfaceVariant.copy(alpha = 0.98f), shadowElevation = 6.dp)`
- [x] **浮层顶边贴芯片区下沿**（`topY = container.bottom`）而不是贴锚点下沿，理由见下
- [x] 容器几何对齐底托：`containerLeft = container.left + 8.dp`、`containerWidth = container.width - 16.dp`（1 级底托左右各 8dp 外边距）
- [x] 高度上限放**整个 Column** `heightIn(max = CHILD_POPUP_MAX_HEIGHT + ARROW_HEIGHT)`（含 6dp 箭头），内层滚动 Box `.weight(1f, fill = false)`
- [x] 箭头水平位置钳制：`arrowCenterPx = (...) .coerceAtLeast(arrowPad).coerceAtMost(maxOf(arrowPad, widthPx - arrowPad))` —— 原写法 `coerceIn(min, maxOf(0, w - min))` 在 `widthPx < 48dp` 时给出空区间 ⇒ `IllegalArgumentException: Cannot coerce value to an empty range`
- [x] RTL 镜像：`val rawX = if (layoutDirection == LayoutDirection.Rtl) windowSize.width - popupContentSize.width - leftPx else leftPx`
- [x] 早退判据改 `val totalMembers = groups.sumOf { it.members.size }; if (totalMembers <= 1) return`（原 `groups.size == 1 && members.size == 1` 把「1 个系列含 1 本」与「含 N 本」混为一谈）
- [x] 验证：`ChipLayoutRulesTest.the popup anchors to the tapped series, not the last one`（源码含 `anchors = anchors + (id to bounds)`、含 `anchors[openSeriesKey]`、不含 `var anchor by remember`）

**为什么浮层顶边贴芯片区下沿而不是贴锚点下沿**（这条与 v3 图纸阶段一致，中间改错又改回）：
若顶边贴锚点下沿，锚点在第 1 行时会盖住第 2、3 行的 series 芯片；而 `Popup(focusable = true)` 把盖住区域的点击消费成「点外部 ⇒ 关闭」⇒ 用户裁决的**收起触发②「点另一个 series」物理上要点两次**。顶边贴芯片区下沿后 1 级芯片一枚都不被遮挡，「以哪枚为顶点」由**箭头水平位置**表达。

**为什么选 `Popup` 而不是同层 `Box` overlay**：本组件必须自带 `Column`，高度只有芯片行那么高，同层 overlay 会被父级 bounds 裁掉；芯片行本身常驻在滚动容器之外（2026-10-18 裁决），所以不需要跟随页面滚动。

## 任务 7：`Popup(focusable = false)`（真机逮到的缺陷）

- [x] `PopupProperties(focusable = false, dismissOnClickOutside = false, dismissOnBackPress = false)`
- [x] Popup content 开头加 `BackHandler(enabled = true) { onDismiss() }` + `import androidx.activity.compose.BackHandler`（activity-compose 1.13.0 是既有依赖）
- [x] 验证：`ChipLayoutRulesTest.the child level lives in a popup, not a sibling row` 断源码含 `focusable = false,` 与 `BackHandler(enabled = true)`（**先过滤 `//` 与 `*` 开头的注释行**，否则断言会被自己的 KDoc 命中而恒真）

**机制**：`focusable` 的 Popup 是 `FLAG_NOT_TOUCH_MODAL` 但**持焦点**的独立窗口，落在窗口外的触摸被消费成 `onDismissRequest` 且**不透传**给下层窗口 ⇒ 第一次只关浮层、第二次才开新的。改 `false` 后窗口外触摸直落主窗口。

**代价（明确记下，不是遗漏）**：失去「点外部关闭」。用户裁决 m00110 里本来没有这条，返回键由 `BackHandler` 兜住。

## 任务 8：间距改判（横 4 / 纵 10）

**用户原话**：「chip内容有换行的情况下，左右间隔大了些，上下又几乎没有间隔」。

- [x] `horizontalGap = 6.dp → 4.dp`、`verticalGap = 4.dp → 10.dp`
- [x] KDoc 记真因：行高由该行最高那块决定，单行芯片下方先空出一截，再叠加仅 4dp 行距 ⇒ 行与行在像素上连成一片；而芯片已顶到 156dp 上限，横向 6dp 反而显宽
- [x] `ChipRowConstants.kt` 的 321dp 推导同步（6→4 不改变四个结论，最近边界是「两个长名」316 对 321，仍有 5dp 余量）
- [x] 验证：`ChipRowWrappingTest` 断 `horizontalGap = 4.dp`，并加「纵 > 横」不等式守卫（用 Regex 从源码读两个数比较，**不钉死数字**）

## 任务 9：删死代码（`PixFoldBottomSheet` 全家）

**用户原话**：「记得原本的上滑弹窗可以删了，毕竟改完这里就没有地方用到了」。

- [x] `PixFoldDialog.kt`：整段删 `fun PixFoldBottomSheet(...)` + 连带 import（`ExperimentalMaterial3Api` / `SheetState` / `ModalBottomSheet` / `BottomSheetDefaults`）。**保留** `MaterialTheme` / `ColumnScope` / `Box` / `testTag`
- [x] `SingleSelectChipRow.kt` / `VolumeChipRow.kt`：删 `sheetTag` / `sheetListTag` / `sheetChipTag` 三个参数
- [x] `ChipRowTags` 删 3 个 sheet 字段
- [x] `CollectionChipRows.kt` 删 6 个 tag；`MetadataPage.kt` / `PackPreviewPage.kt` 各删 6 个
- [x] 验证：`grep` 全部 15 个 sheet tag 在 `android/app/src/main` 与 `src/test` 下**零命中**
- [x] `PixFoldDialogShellTest`：删 `a bottom sheet registers the same blur as a dialog`；`a dialog inside a sheet counts two` → `a dialog inside a dialog counts two`（改成 `PixFoldDialog` 嵌套，断 `assertEquals(2, counter.value)`）—— **弹层组件删了，但「计数而非布尔」语义还得守**

## 任务 10：受牵连测试改判

**这一阶段的原则**：每条被改判的断言，注释里必须写「为什么改判 + 用户裁决 ref」，否则半年后没人敢动。

- [x] `ChipLayoutRulesTest`：`manyItems` 8 → **12**（一行 3 块 × 3 行 = 9 块，8 块永远不触发收口）；`maxLines` 参数 → `collapseLines`；`the all entry opens the sheet` → `the collapse chip expands the remaining chips in place`（点 TAG_ALL 后 `chip-item-12` displayed、`TAG_SHEET` doesNotExist）；`the top level row caps at two rows` 整条作废 → `the row collapses at three chip lines`；新增 `the collapse entry counts every item, not just the shown ones`（`onNodeWithText("all 12")`）
- [x] `ChipLabelTruncationTest`：`the_limit_is_twenty_four_characters` → `the_limit_is_thirty_characters`；夹具改 `"一二三四五六七八九十".repeat(3)`
- [x] `ChipRowWrappingTest`：FlowRow 期望表 `SingleSelectChipRow` 1 → **0**，新增 `ChipFlow(` + `SubcomposeLayout(` 守卫；`two_level_chips_stay_two_level` 改为断「无 `TOP_CHIP_MAX_LINES`」「含 `openSeriesKey?.let`」「含 `if (!group.isSeries)`」；`all_items_sheet_pads_chips_horizontally` 整条替换为 `the_all_entry_no_longer_opens_a_sheet`
- [x] `CollectionChipRowsTest`：5 条依赖 `TAG_COLLECTION_BOOK_ROW` 常驻的用例改为「先点开 series 再断」；新增三条收起触发用例
- [x] `MetadataVolumeChipStickyTest`：新增 `pickVolumeInPopup(seriesName, volumeId)` 辅助（点 series 开浮层 → `volumeChipTag(id).performScrollTo().performClick()`）；删 `visibleVolumeChips` 整组
- [x] `PackVolumeChipScaleTest`：单级 `VolumeChipRow`（5 卷平铺不收口）⇒ `scale-6` **可见**是预期行为，断言方向反转
- [x] 验证：`:app:testDebugUnitTest` 从 8 条红 → 全绿

**踩坑（写进测试 KDoc）**：`src.contains("TOP_CHIP_MAX_LINES")` 会命中源码里的**解释性注释**（「曾经 TOP_CHIP_MAX_LINES 把后者钉在 2 行」）⇒ 断言前必须先过滤 `//` 与 `*` 开头的行。同类问题在 `focusable = false` 那条也出现过（断言被自己的 KDoc 命中而恒真）。

## 任务 11：变异验证（M1–M8 / MA–MG）

- [x] `M1` 去掉 `widthIn(min, max)` ⇒ 4 条红（KILLED）
- [x] `M2` `CollapseChip` 丢宽度约束 ⇒ `the collapse chip is the same size as a normal chip` 红（KILLED）
- [x] `M3` 探测副本去掉 `clearAndSetSemantics` ⇒ 多条红（KILLED）
- [x] `M4` 收起判定去掉 `naturalRows.size > collapseLines` ⇒ 3 条红（KILLED）
- [x] `M6` 再点同系列从「不收浮层」改「换书」⇒ 2 条红（KILLED）
- [x] `M8` `CHIP_COLLAPSE_LINES` 3→4 ⇒ 5 条红（KILLED）
- [x] `MA` 去 48dp ⇒ `the collapse chip has a 48dp touch target`（KILLED）
- [x] `MB` `anchors = mapOf(id to bounds)` ⇒ 8 条红（KILLED）
- [x] `MC` 展开态去掉限高与滚动 ⇒ `PackVolumeChipScaleTest` 展开用例（KILLED）
- [x] `MD` `allEntryText(total)` → `allEntryText(it)` ⇒ `the collapse entry counts every item`（KILLED）
- [x] `MF` `focusable = false,` → `true,` ⇒ **首轮假 SURVIVED**（build cache + 断言被 KDoc 命中恒真），加 `--rerun-tasks` 并改断 `code` 后才 KILLED
- [x] `MG` 删 `BackHandler` ⇒ KILLED

**「MF 假 SURVIVED」的教训**：看到 SURVIVED 先别当结论 —— **先查这个 task 是不是 `FROM-CACHE`**（2 秒跑完不可能真跑）。

## 任务 12：四道闸门 + 真机核对

- [x] `cd android && ./gate.sh` —— **首次只跑 2s、全是 up-to-date，不构成证据**
- [x] 改 `cmd.exe /c "run-gradle.bat :app:testDebugUnitTest :app:lintDebug :domain:test :app:assembleDebug --rerun-tasks"` 强制重跑
- [x] 结果：`BUILD SUCCESSFUL in 1m 12s` / `rc=0` / app **748** fail 0 / domain **940** fail 0 / APK **35,087,282 B**
- [x] 真机（用户授权）：PJX110 / Android 16 / 1264×2780px / 560dpi = 361dp×794dp / density 3.5 / zh-CN
  - 芯片宽实测 **156.0 / 133.7 / 119.4 / 96.3 dp** 全在 [96,156]
  - 横向 4dp（x=20 → x=180）、行基线 y=154/232/290、行高 60/48/48、行间留白 ≈10dp
  - 15 集合：第 3 行末尾出「全部 15 项」虚线芯片（w=109.7 h=48 x=120.3），点开 6 行 15 块全铺开、无弹层；超 318dp 自带内滚、「收起 ▲」可达
  - 24 卷系列浮层：落在芯片区下方、1 级芯片一枚都没被遮挡、内滚正常；**三条收起触发 + 返回键全部一步生效**
  - 30 字截断**未溢成 5 行**（3 个长名均 3 行、末字带省略号）

## 自检结果

| 项 | 结果 |
|---|---|
| `:app:testDebugUnitTest` | 748 tests / fail 0 |
| `:domain:test` | 940 tests / fail 0 |
| `:app:lintDebug` | 零告警（`rc=0`，非 grep 计数） |
| `:app:assembleDebug` | APK 35,087,282 B |
| 变异 M1–M8 / MA–MG | 全 KILLED（MF 首轮假 SURVIVED 已查明为 build cache） |
| 真机 | 30 / 96 / 156 三个数全部对上；四条收起/展开触发全部一步生效 |
| 改动面 | 22 改 1 增；`1507 insertions(+), 755 deletions(-)` |

**明确的未做 / 已知边界**：

1. **`CHIP_LABEL_MAX_CHARS = 30` 的推导依赖「FilterChip 左右内边距 28dp」这一假设**。真机 3 个长名均 3 行未溢出，但样本只 3 个；极端字体缩放（系统大字体）下若溢成 5 行，`maxLines = 4` + `Ellipsis` 会兜住不破版，代价是尾部字被吞 —— 那时应降到 27–28。
2. **浮层定位不消费 `WindowInsets`**：`PopupPositionProvider` 用的是 `boundsInWindow()` + `windowSize` 手算，带 insets 的设备上两者不保证同原点。当前设备实测无偏差，未做进一步处理。
3. **`childAllEntryText` 是保留但恒不显示的参数**（浮层内 `collapseLines = Int.MAX_VALUE` 不收口），保留是为了两级调用形态对称。
4. **本轮 superpowers 流程缺口（如实记）**：`brainstorming` 未调用、`writing-plans` 未在当时产出（本文件即补）、`using-git-worktrees` 未使用。详见会话台账。
