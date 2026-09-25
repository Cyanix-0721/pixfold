# D1 操作流程与 UI 摩擦点清单

> **性质**：调研笔记（非产品权威）。产品决策仍以 `HANGOFF.md` 为唯一权威。
>
> **⚠️ 本文件是「操作逻辑符合性走查」的落盘处（HANGOFF §9 D1 第 4 层，2026-09-21 用户追加）**：
> 前三层自证（领域单测 / UI 断言 / 真机实证）验证的是「**实现是否正确**」；
> 本台账记录的是「**设计是否符合操作逻辑**」—— 两者都绿，UI 仍可能不好用。
> **用途**：用户 2026-09-19 提出「先把基础 UI 调一下，再开始看功能」——
> 本文件是这轮 UI 调整的**问题台账与改动依据**。
> **来源标注**：每条都注明是**真机实测**、**代码核实**还是**走查观察**，
> 不用推测充数。凡推测必标「待实测」。

## 0. 背景与判定基线

**设备实况**（2026-09-19 实测）：

| 项 | 值 |
| --- | --- |
| 机型 | PJX110 / Android 16 |
| 分辨率 | 1264 × 2780 px |
| 密度 | 560 dpi = **3.5×** |
| 逻辑尺寸 | **361dp × 794dp** |

**基线约束**（`HANGOFF.md` §2，不可违背）：

- 约束 3：**预览是主工作区，不是日志**
- 约束 7：UI 符合最新 Material Design（语义色/排版/形状、深色模式、≥48dp、`contentDescription`）
- 布局约定（历次踩坑固化）：一页**一个**页面级 `LazyColumn`；不在无界 `verticalScroll` 里嵌 `LazyColumn`；361dp 下不给可操作控件加横向滚动

---

## 1. ✅ P0：命名页头部吞掉 95% 首屏（违反约束 3）—— 2026-09-24 已修

**来源：真机实测（2026-09-19，dump 坐标）+ 探针复核（2026-09-24）**

未滚动时的真机 dump（32 个文本节点）：

| 项 | 实测值 |
| --- | --- |
| 步骤 Tab 行 | y = 175 … 315px（属步骤容器，不在编辑器内） |
| 命名页编辑器**首个**元素 | y = 399px（「← 返回排序」） |
| 命名页编辑器**末端** | y = **2632px** |
| 编辑器占用高度 | 399→2632 = 2233px ≈ **638dp** |
| 含 Tab 行的头部总高 | 175→2632 = 2457px ≈ **702dp** |
| 底部固定「下一步」栏 | y = 2647 … 2717px |
| 可视高度 | 2780px = **794dp** |
| **头部占可视比例** | **约 88%**（2457 / 2780px） |
| **编辑器末端到固定底栏的间隙** | **15px ≈ 4dp** |
| **首屏可见的建议行数** | **0 条**（dump 中检索 `IMG_` / `新名称`，命中 0） |

> 关键读法：编辑器内容**一直顶到固定底栏上方 15px** ——
> 也就是说 `LazyColumn` 的整个可视区被头部占满，
> 用户**必须下滑**才能看到第一条建议名。

> **这不是"偏紧"，而是"预览完全不在首屏"** —— 用户进入命名页看到的
> 全是配置项，一张图片的建议名都看不到,必须下滑才知道这个页面是干什么的。
> 直接违反约束 3「预览是主工作区」。

**已造成的后果（两次被迫绕过它）**：

1. P6 加「替换字符」入口时**加成行内新行 → 14 项既有测试失败**
   （建议行完全不组合）；最终改放进 ⓘ 对话框换取零高度 —— 治了症状，没治病。
2. 「清洗示例行」（§11 第 16 项方案 A）同样只能放对话框内。

**⚠️ 一条取证教训（本清单初稿写错、已改正）**：
我最初据 Robolectric 探针（`w400dp-h1000dp`，即 1000dp 视口）
记"头部约 862dp、仅剩约 130dp"—— 但**真机是 794dp 视口**，
探针视口比真机**更宽松**，两者都不可直接当结论。
本节数字已全部改为**真机 dump 实测值**。

**代码位置（修复后）**：`app/src/main/kotlin/com/pixfold/d1/ui/workflowa/NamingPage.kt`
—— 固定区（标题 + 摘要栏 + 条件冲突红字 + 汇总）**不滚动**；
展开态编辑器仍是 `LazyColumn` 的 `item(key = "editor")`，**在滚动容器内**（可达但可滚走）。

**结构成分**（编辑器内含，折叠后全部隐藏）：

- 标题「命名结构 · 集合名（N 张）」
- 根目录名输入 + 跟随集合标签/按钮
- 扩展名策略下拉 + 自动清洗开关 + ⓘ
- 起始序号 + 补零位数
- 组件列表（默认 3 个，每个含：启用/类型/删除/上移/下移/分隔符/大小写/自定义文本）
- 添加组件

**用户裁决（2026-09-24）**：选 **`A1+`折叠摘要栏 + 摘要行吸顶**。

- 否决 A1（仅折叠）：汇总行会随列表滚走，用户改了结构就看不到冲突数。
- 否决 A2（移入 sheet）：42 条既有断言需重写，改动最大；且与「逐一比对建议名」的用法冲突
  —— 用户需要一边看结构一边看结果。
- 否决 A3（仅压间距）：**探针实测否决**。3 个默认组件卡片各 150dp 共 450dp = 头部 65%，
  压间距最多省约 200dp（692→约 490dp），仍占 62% 视口，**首屏依旧 0 条建议行**。
  ⇒ 空间黑洞是**逐组件 150dp 的 Card**，不是标题/根目录/策略行，压间距治不了根。

**实现结果（Robolectric 探针，`w400dp-h1000dp`，密度 1.0，root 400 × 1000 dp）**：

| 项 | 折叠态（默认） | 展开态 |
| --- | --- | --- |
| 标题 | top = 6.0，h = 36.0 | 同左 |
| 摘要栏 | top = 48.0，h = 49.0 | 同左 |
| 汇总行 | top = 107.0，h = 36.0 | 同左 |
| 列表 | top = 154.0，h = 846.0 | 同左 |
| 编辑器 | **不在组合树里**（count = 0） | top = 158.0，h = 804.0 |
| 建议行 | top = 160.0，h = 113.0 | top = 973.0（需滚） |
| **固定区合计** | **154dp** | 154dp |

> 折叠态首屏可见建议行从 **0 条 → 5 行以上**（真机 794dp 视口）。
> 探针数字与真机 dump 的差异已在上面「取证教训」中记明：探针视口比真机宽松，
> 但**两者的相对关系（固定区 154dp ≪ 视口）成立**，首屏可见性由 UI 断言守卫。

**✅ 真机核对已完成（2026-09-24，用户授权后执行）**

设备 PJX110 / Android 16，`1264 × 2780px`、密度 560dpi ⇒ 视口 **361dp × 794dp**。
`uiautomator dump` 实测（像素）：

| 项 | 折叠态（默认） | 展开态 |
| --- | --- | --- |
| 标题「命名结构 · 旅行照片（30 张）」 | y = 539 … 623 | 同左（未动） |
| 摘要栏「旅行照片_001.jpg · 2 个组件启用」 | y = 693 … 763 | 同左（未动） |
| 汇总行「共 30 项 · 冲突 0 · 警告 0」 | y = 886 … 942 | 同左（未动） |
| 固定区（标题顶 → 列表顶） | 471 → 约 965px ≈ **141dp** | 同左 |
| 编辑器「命名结构（2 个组件启用）」 | **不在 dump 中**（未参与布局） | y = 1079 起，可见 |
| **首屏可见建议行** | **5 条**（`001` y=1037 … `005` y=2325） | 0 条（需滚，符合预期） |
| 底部「下一步：执行计划」栏 | y = 2647 … 2717（未动） | 同左 |

**逐条核对结论**：

1. **首屏建议行 0 条 → 5 条** ✅（离线预测「5 行以上」，真机实测正好 5 条）。
2. **固定区 141dp** ✅（探针预测 154dp；差异来自真机 361dp 宽度下文本换行 ——
   两者的相对关系「固定区 ≪ 视口」一致，见上方取证教训）。
3. **摘要栏吸顶** ✅：在展开态下滑动到策略行后，标题 / 摘要栏 / 汇总行**坐标一字未变**
   （539 / 693 / 886），证明它们确实在滚动容器之外。
4. **展开可达** ✅：点摘要栏后编辑器出现在 y=1079 的视口内；继续滑动可依次到达
   组件卡片、根目录名、跟随集合、扩展名策略、起始序号/补零位数、建议行。
5. **LazyList 前插锚定陷阱已修** ✅（真机复现 + 验证）：折叠态下滑到 `005`~`010`，
   此时点「展开」—— 列表**自动回顶**、编辑器立即可见；修掉前此处看不到任何变化。
6. **既有关联交互未被破坏** ✅：展开态下 ⓘ「自动清洗说明」对话框可正常打开，
   内容完整（说明正文 / 替换为输入框 / 恢复默认 / 示例「封:面?.jpg → 封_面_.jpg」/ 知道了）。
7. 设备已复位：`svc power stayon false`、应用已 `force-stop`、
   `/data/local/tmp/p0-*.xml` 与本地 `artifacts/p0-*` 调试产物全部清理。

> 本次真机核对为**用户授权后执行**；核对中未发现新的 UI 摩擦点。

**⚠️ 修复过程中发现并修掉的真实缺陷（LazyList 前插锚定）**：
编辑器是**前插**到 `LazyColumn` 首项的，而 LazyList 会锚定「当前第一个可见项」不让它跳动
⇒ 编辑器被插到视口**上方**，用户点「展开」看不到任何变化（实测:14 项既有测试全部失败）。
修法是 `rememberLazyListState()` + `LaunchedEffect(editorExpanded) { if (editorExpanded) listState.scrollToItem(0) }`，
并由新断言 `expanding the editor really shows it on screen` 钉死。

---

## 2. ✅ P1：长列表的终局动作要滚很久才够到 —— 2026-09-25 已修（提交 `1c95db9`）

**来源：真机走查观察 + 代码核实**

| # | 现象（修复前） | 证据 |
| --- | --- | --- |
| B4 | A 侧计划页 **56 行平铺**（`items(plan.ops.size)`） | `RenamePlanPage.kt:115` |
| B5 | 「确认执行」在**列表内部**（末尾），真机需连滑 **8–12 次** | `RenamePlanPage.kt:126-131`，走查实测滑动次数 |
| B6 | 报告页同样平铺 56 行，「撤销本次执行」也在末尾 | `ExecutionReportPage.kt:101` |
| B7 | B 侧「确认打包」同样在列表内 | `PackPreviewPage.kt:332` |

**⚠️ 已存在的不一致（代码核实）**：

- `NamingPage` 的「下一步：执行计划」在 **`WorkflowASteps` 底部固定栏**（列表之外，不随滚动）
- 但 `RenamePlanPage` 的「确认执行」在 **`LazyColumn` 内部**（要滚到底）

> 同样是"推进到下一步"的按钮，**一个固定、一个要滚**。这属于无意的行为分裂。

### 修复方案（用户裁决：全部固定，5 个动作全移到底栏）

新增共享组件 `pixfold-d1/app/src/main/kotlin/com/pixfold/d1/ui/components/PageActionBar.kt`
（`TAG_PAGE_ACTION_BAR`）= `HorizontalDivider()` + 内边距 `Column`；调用方放在**页面级
`Column` 之内、`LazyColumn` 之外**，列表保留 `weight(1f)`。按钮由调用方给（各页动作数量与
危险级别不同，报告页是两个）。

| 页面 | 动作 | 修复前位置 | 修复后 |
| --- | --- | --- | --- |
| `RenamePlanPage` | 确认执行 | `item(key="execute")` 列表末尾 | `PageActionBar`（列表外） |
| `ExecutionReportPage` | 撤销本次执行 / 完成 | `item(key="actions")` 列表末尾 | `PageActionBar`（列表外），完成按钮补 `TAG_REPORT_DONE` |
| `LibraryPage` | 下一步 编辑元数据 | `item(key="next")`，`FilterChip` | `PageActionBar`，改 `Button` |
| `MetadataPage` | 下一步 打包计划 | `item(key="next")`，`FilterChip` | `PageActionBar`，改 `Button` |
| `PackPreviewPage` | 确认打包 | `item(key="plan")` 内部末尾 | `PageActionBar`（列表外，`showPlanSection` 门控） |

`TAG_LIBRARY_NEXT` / `TAG_METADATA_NEXT` 同步从函数体之后挪到文件头（与其他 tag 同处）。

**为什么 Tab 不能替代（原注释的说法是错的，已改写）**：三处旧注释写「Tab 也能切，但长表
滚到底后 Tab 不在视口内」——**与事实不符**。`PrimaryTabRow` 位于嵌套 `NavHost` **之外**，
自身不在任何 `LazyColumn` / `verticalScroll` 里，**结构上不可能滚出视口**。既然 Tab 永远可见，
它就不是「够不到终局动作」的解法，而保留底栏的理由是语义：

1. 这些是**不可逆操作**（确认执行真会改文件名），Tab 行里没有对应项，切目的地≠执行；
2. 「下一步」是**向导式顺序推进**，Tab 是**平级视图索引**（可跳步，绕过步骤 2 的复核）；
3. A 侧 `TAG_NEXT_STEP`（排序页）与 `TAG_TO_PLAN`（命名页）早已固定在底栏 —— 统一才消歧。

三处错误注释已改写：`WorkflowASteps.kt:209`、`WorkflowASteps.kt:229`、`WorkflowBSteps.kt:148`。

**顺带修掉的真实缺陷**：`RenamePlanPage` 的 `contentInsets: WindowInsets = WindowInsets.safeDrawing`
参数**声明了却从未使用**（根 `Column` 缺 `.windowInsetsPadding(contentInsets)`），
B 侧三页与 `ExecutionReportPage` 都有；已补上。

### 验证

- **回归守卫**：`pixfold-d1/app/src/test/kotlin/com/pixfold/d1/ui/plan/PlanWiringTest.kt`
  删掉 4 处 `performScrollToNode`（`:65`/`:74`/`:124`/`:138`）——那正是本缺陷的显式编码；
  并把 2 处 `assertExists()` 提升为 `assertIsDisplayed()`。
- **真机视口守卫**（新增 2 条，各自 `@Config(qualifiers = "w361dp-h794dp-560dpi")`）：
  A 侧断言底栏在真机视口下可见（含视口自检 `rootHeightDp in 780f..810f`，防 qualifiers
  写错时悄悄换宽松视口假绿）；B 侧三步连续断言 `TAG_LIBRARY_NEXT` → `TAG_METADATA_NEXT`
  → `TAG_PLAN_CONFIRM`。类级 `@Config` 是 `h2000dp` 超高视口，两种实现都能过 ⇒ 必须单列。
- **变异自证（6 组，全部捕获）**：删 A 侧底栏（5 失败）/ 删 B 侧库底栏（1）/ 删 B 侧元数据
  底栏（1）/ 报告动作移回列表内（3）/ A 侧确认移回列表内（5）/ B 侧确认移回列表内（6）；
  另 2 组（底栏零宽、`showPlanSection && false`）分别捕获 4 / 6 条。
- **四门**：`:app:testDebugUnitTest` **282**（280 → +2 守卫）、`:domain:test` **229**、
  `lintDebug` `No issues found.`、`assembleDebug` BUILD SUCCESSFUL。

**未做**：长列表加筛选仍待办（见 §3，P1）。

---

## 2b. ✅ P1 续：底栏左右分槽 + A 侧步骤 1 头部可伸缩 —— 2026-09-25 已修（提交 `1c95db9`）

**来源：用户真机走查后的追加要求**

User said（逐字）：「步骤1上面部分也做个伸缩吧，让网格/列表的空间可以大点显示.并且下一步、完成之类的按钮都放到底栏的右侧，返回、上一步的按钮改到左侧」

拆成三条需求：

| # | 要求 | 原状 | 修复后 |
| --- | --- | --- | --- |
| C1 | A 侧步骤 1 头部可伸缩，把空间让给网格/列表 | `SortRuleEditor` 常驻，**撑满固定区** | 折叠成一行摘要栏，默认折叠 |
| C2 | 推进/终局动作（下一步 / 完成 / 确认执行 / 确认打包）在底栏**右侧** | 混杂：A 步骤1 的「下一步」在**模式行**里；报告页两按钮**紧邻居中**（`spacedBy(8.dp)`，非左右分槽） | 一律 `trailing` |
| C3 | 返回类动作（返回上一步 / ← 返回排序 / ← 返回命名结构）在底栏**左侧** | A 侧在**页面顶部**、B 侧在 **Tab 行下方**（都在内容之上） | 一律 `leading` |

**C1 折叠范围（用户裁决：只折叠排序规则编辑器）**：集合切换行、「共 N 张」行、
网格-列表切换行**全部常驻**——它们各自承担「当前在哪一组 / 有多少张 / 用哪种视图」的
事实陈述，折叠掉会让用户失去定位。只把 `SortRuleEditor` 收起来。

**为什么默认折叠**（与 P0 一致，用户已批准该模式）：进入步骤 1 的首要任务是看排序结果，
规则编辑是低频动作；折叠后网格首屏多出约 140dp。代价是 3 个测试文件须先展开才能点规则芯片
（已改，见下）。

**折叠实现位置**：状态 hoist 在 `SortAndPreviewPage`（`var ruleExpanded by
rememberSaveable { mutableStateOf(false) }`），**`SortRuleEditor` 自身不折叠**。
理由：`SortRuleDirectionTest.kt` 以 `SortRuleEditor` 为 root 直接组合并做 13 次点击，
把折叠塞进组件本体就得改整个文件；hoist 到页面层则**零改动**。

**`scopeLabel` 只在折叠态渲染**：展开后 `SortRuleEditor` 标题行已显示同一文案，
两处都渲染会让 `onNodeWithText(batchLabel(...))` 精确匹配命中 2 个节点
（`BatchRuleUiTest.kt:87/:92/:131` 会失败）。

**摘要栏内容**（约束 2「建议 ≠ 事实」：折叠的是**编辑控件**，不是**状态可见性**）：
沿用 `SchemeEditor.kt` 的 `SchemeSummaryBar` 结构（`heightIn(min = 48.dp).clickable`，
箭头用官方 `ic_arrow_drop_up/down`），摘要文本形如
「排序规则：目录名 ↑ · 文件名(自然) ↑」，另带当前批次的 `batchLabel`。

**C2/C3 的实现**：`PageActionBar` 由单列改为**双槽**（`leading` / `trailing` /
`extraContent`），中间夹 **`Spacer(Modifier.weight(1f))`**——刻意**不用**
`Arrangement.SpaceBetween`：单侧按钮时 `SpaceBetween` 会把唯一按钮推到左边，
而 `Spacer` 让它仍贴自己那侧。按钮**不得 `fillMaxWidth()`**（否则同排另一侧被挤成 0 宽），
B 侧三处原有的 `fillMaxWidth()` 已去掉。

各页分配：

| 页面 | 左（leading） | 右（trailing） |
| --- | --- | --- |
| A 步骤1 `SortAndPreviewPage` | —（无返回） | 「下一步」`TAG_NEXT_STEP`（**从模式行移出**） |
| A 步骤2 `NamingPage` | 「← 返回排序」`TAG_STEP_BACK` | 「下一步：执行计划」`TAG_TO_PLAN` |
| A 步骤3 `RenamePlanPage` | 「← 返回命名结构」`TAG_STEP_BACK` | 「确认执行」`TAG_A_PLAN_CONFIRM` |
| 报告页 `ExecutionReportPage` | 「撤销本次执行」`TAG_REPORT_UNDO` | 「完成」`TAG_REPORT_DONE` |
| B 步骤1 `LibraryPage` | —（无返回） | 「下一步 编辑元数据」`TAG_LIBRARY_NEXT` |
| B 步骤2 `MetadataPage` | 「← 返回上一步」`TAG_B_STEP_BACK` | 「下一步 打包计划」`TAG_METADATA_NEXT` |
| B 步骤3 `PackPreviewPage` | 「← 返回上一步」`TAG_B_STEP_BACK` | 「确认打包」`TAG_PLAN_CONFIRM` |

**接线方式**：底栏由**各页自己渲染**（P1 已定的分层；约 10 个测试调用点直接组合页面并断言 tag，
改成容器级渲染会全断）。容器通过**可空回调** `onBack` / `onNext` 传入，页面以
`if (onBack != null) …` 守卫。`grep -rn "onNext" app/src/test` **零命中** ⇒
所有页面测试不传回调 ⇒ 新底栏在单测里不组合、**零布局影响**。

`WorkflowASteps` 因此删掉三处页面上下方的常驻 `Column`（「← 返回排序」「下一步：执行计划」
「← 返回命名结构」）；`WorkflowBSteps` 删掉 Tab 行与内容之间那行常驻 `TextButton`
（「← 返回上一步」）。A 侧 `TAG_STEP_BACK`/`TAG_TO_PLAN`、B 侧 `TAG_B_STEP_BACK`
**常量声明保留在原文件**（现由页面层使用）。

**⚠️ 已知的重复 tag 窗口（评估为可接受）**：转场期间父子 destination 同时组合，
`TAG_B_STEP_BACK` 会短暂出现 2 个节点。该 tag **测试零引用**；A 侧
`BackNavigationTest` 查 `TAG_STEP_BACK` 用 `onAllNodesWithTag` / 空断言而非单数
`onNodeWithTag`，且全部在 `waitForIdle()` 之后。B 侧该 tag 亦无任何测试引用。

**验证**：

- **四门**：`:app:testDebugUnitTest` **287**（286 → +1 折叠用例；286 = 282 + 4 位置守卫）、
  `:domain:test` **229**、`lintDebug` `No issues found.`、
  `assembleDebug` 成功（APK `app-debug.apk` = **33,549,719 B**）。
  APK 内 dex 已确认含 `rule-summary-bar` / `rule-summary-toggle`。
- **新增位置守卫（4 条，`@Config(qualifiers = "w361dp-h794dp-560dpi")`）**：
  本次需求本质是**位置**（「放到底栏右侧」「改到左侧」），而原先只有「按钮可见」类断言——
  **抓不住位置放反**。故用 `getBoundsInRoot()` 取 `DpRect` 比较 `.left`/`.right`/`.top`：
  ① B 步骤2 返回在左、下一步在右（且 `back.top == next.top` 同一行不换行）；
  ② A 步骤2 返回在左、下一步在右；③ 报告页撤销在左、完成在右；
  ④ A 步骤1 的「下一步」在右半屏 **且点击后真能推进**（`TAG_TO_PLAN` 出现）。
  断言 `back.right <= next.left` + `back.left < rootWidth / 2` + `next.left > rootWidth / 2`。
- **新增折叠用例 1 条**（`BatchRuleUiTest`）：默认折叠（`TAG_RULE_SUMMARY_BAR` /
  `TAG_RULE_SUMMARY_TOGGLE` 显示、`TAG_SORT_RULE_EDITOR.assertDoesNotExist()`、
  摘要文本可见）→ 展开后编辑器出现 → 再点收起后消失。
- **测试侧连带**：`BatchRuleUiTest.kt` / `RuleAutoApplyPageTest.kt` 各加
  `expandRuleEditor()` helper（点 `TAG_RULE_SUMMARY_TOGGLE` + `waitForIdle`），
  在点规则芯片/「添加」之前调用。`SortRuleDirectionTest.kt` **零改动**（见上：状态 hoist 在页面层）。
- **变异自证（8 组，全部捕获）**：编辑器永不展开（9 失败）/ 默认展开（12）/ 摘要丢掉规则文本（1）/
  A 步骤1 「下一步」塞进 leading（1）/ B 步骤2 两槽互换（1）/ 报告页两槽互换（1）/
  网格回 `fillMaxSize()`（1）/ A 步骤2 `onNext = null` 接线断（10）。

**教训**：
- `assertDoesNotExist()` 是 `SemanticsNodeInteraction` 的**成员方法，不是顶层扩展函数**，
  不能 `import androidx.compose.ui.test.assertDoesNotExist`（编译失败）——直接调用即可。
- 变异脚本若把两个槽整体互换，必须**整段替换**（`leading = {...}` ↔ `trailing = {...}`）；
  只改关键字会得到「重复具名参数」的**编译错误**（表现为 `tests=0` 的 NO-XML），
  那不是有效变异证据，须改用整段替换后重跑。

**真机核对（2026-09-25，PJX110 / Android 16 / 1264×2780px = 361dp×794dp）—— 已通过**：

| 位置 | 左（返回类） | 右（推进类） | 同排 |
| --- | --- | --- | --- |
| A 步骤1 | — | 「下一步」**x989-1138** y2633-2703 | 底栏 |
| A 步骤2 | 「← 返回排序」x84-344 | 「下一步：执行计划」x742-1138 | ✅ 同 y2633-2703 |
| A 步骤3 | 「← 返回命名结构」x84-443 | 「确认执行」x939-1138 | ✅ 同排 |
| 报告页 | 「撤销本次执行」x126-423 | 「完成」x1038-1138 | ✅ 同排 |
| B 步骤1 | — | 「下一步 编辑元数据」x730-1138 | 底栏 |
| B 步骤2 | 「← 返回上一步」x84-394 | 「下一步 打包计划」x779-1138 | ✅ 同排 |
| B 步骤3 | 「← 返回上一步」x84-394 | 「确认打包」x939-1138 | ✅ 同排 |

**改造前 → 改造后（同一设备同一视口）**：

| 项 | 改造前 | 改造后 |
| --- | --- | --- |
| A 步骤1「下一步」 | x1031-1180 **y791-861**（模式行内，与「网格/列表」「本组独立」同排） | x989-1138 **y2633-2703**（底栏右侧） |
| A 步骤2 返回 | 页面**上方**单独一行 | 底栏**左侧**、与「下一步」同排 |
| B 步骤2/3 返回 | x56-366 **y399-469**（Tab 行下方、**内容之上**） | 底栏**左侧** x84-394、与推进同排 |
| 报告页撤销/完成 | 紧邻居中（`spacedBy(8.dp)`，非左右分槽） | 左 x126-423 / 右 x1038-1138 |

**A 步骤1 折叠收益（实测）**：折叠态摘要栏 y931-1057、首张图片 **y1520**、网格可用高约 1113px；
展开态摘要栏 y959-1029、编辑器 2 级可见（「第1级」y1334 / 「第2级」y1516）、首张图片 **y2080**、
网格可用高约 553px ⇒ **折叠多出 560px ≈ 155dp（+38%）**。摘要栏点击展开/收起均可复现，
展开态「排序规则（2/4 级）」标题行与折叠态摘要的 `scopeLabel` **不重复渲染**（符合设计）。

设备已复位（`stayon false`、`force-stop`、`/data/local/tmp` 调试产物已清理），未发现新的 UI 摩擦点。

---

## 2c. ✅ P1 续：底栏视觉规格（降高 + 底色 + 撤销红）—— 2026-09-25 已修（提交 `1c95db9`）

**来源：用户真机走查后的追加要求**

User said（逐字）：「底栏高度可以稍微低一点，而且有的按钮还是纯文字描述而不是带背景的按钮，步骤3的撤销按钮背景改红色」

拆成三条需求，**每一条都是可证伪的视觉事实**（而 §2b 的 4 条位置守卫一条也抓不住）：

| # | 要求 | 原状 | 修复后 |
| --- | --- | --- | --- |
| V1 | 底栏比之前**矮** | 外层 `Column` 叠 `spacedBy(6.dp)` + 内层 `padding(vertical = 8.dp)` + `spacedBy(6.dp)` ⇒ 单按钮页 71dp | 去掉外层 `spacedBy`、垂直内边距 **6dp**、行内间距 **4dp** ⇒ 单按钮页 **61dp**（实测 61.14dp） |
| V2 | 返回类按钮要**有填充底色**（不再是「纯文字描述」） | 4 处用 `TextButton`（容器透明，只画文字） | 4 处改 **`FilledTonalButton`**（`#E8DEF8` 底） |
| V3 | 报告页「撤销本次执行」**红底** | `OutlinedButton`（只有一圈描边，与「完成」相比几乎看不出是危险动作） | `Button` + `colors = ButtonDefaults.buttonColors(containerColor = colorScheme.error, contentColor = colorScheme.onError)`（实测 `#B3261E`） |

**V1 的高度账**（material3 1.4.0 源码级核实）：`ButtonDefaults.MinHeight = ButtonSmallTokens.ContainerHeight = 40.0.dp`、
`ContentPadding` 上下各 8dp，但 `Button` 外层 `Surface` 带 `minimumInteractiveComponentSize()` ⇒
**实际渲染高 48dp**；`FilledTonalButton` / `OutlinedButton` / `ElevatedButton` **复用同一套** MinHeight 与 ContentPadding
⇒ 换控件类型**不改变尺寸，只改变是否画填充底色**。所以 V2/V3 与 V1 互不冲突。
降高后的下限是 **48dp 触控尺寸（约束 7）**，内边距不可再往 0 收 —— 那是**拿触控尺寸换高度**，不是本需求要的。

**V3 为什么用 `colorScheme.error` 而不是写死红色**：约束 7 要求用语义角色而非硬编码颜色；
撤销是**破坏性 / 回退类**动作，按 Material 语义着 `error` 底色。禁用态交给 `buttonColors` 的默认
`disabled*` 角色（灰化），**不用 `errorContainer`** —— 那在浅色主题下是粉底，危险语义被削弱。

> ⚠️ **上面这句只对浅色成立，2026-09-26 已被用户驳回并修正** —— 真机是**深色**，
> 深色下 `error` 是**淡粉** `#F2B8B5`，正是用户说的"不像危险动作"。
> 修正方案见 **§2d**（浅色走 `error`、深色走 `errorContainer`，两档各取所长）。

**V2 的 4 处**：`NamingPage.kt`（cd「返回步骤1」）、`RenamePlanPage.kt`（cd「返回步骤2」）、
`MetadataPage.kt`（**仅底栏那一处**，页面内另 5 处 `TextButton` 是别的操作、**不能连带换掉**）、
`PackPreviewPage.kt`。前三个文件的 `import androidx.compose.material3.TextButton` **直接换成**
`FilledTonalButton`；`MetadataPage.kt` **新增** import 并**保留** `TextButton`。

**新增像素级守卫 4 条（`BottomBarStyleTest.kt`）**：本轮三条需求**全都是「语义树分辨不出」的**——
`TextButton` 与 `FilledTonalButton` 的语义树完全一样（`Role.Button` + 同一段文字），
`assertIsDisplayed()` 看不出区别；`OutlinedButton` 与红底 `Button` 在语义层同样无从区分；
`getBoundsInRoot()` 只比 `.left`/`.right`，也**抓不住**「没底色」「不是红」「底栏偏高」。
⇒ 必须**取像素**。做法与 `TransitionStyleTest` 一致：`@GraphicsMode(NATIVE)` +
`captureToImage().asAndroidBitmap()`（Robolectric 的 LEGACY 图形后端画不出真实颜色）。

| 守卫 | 判据 | 实测读数 |
| --- | --- | --- |
| 底栏高度既降又不破触控下限 | `56f <= height <= 64f` | **61.14dp** |
| B 步骤2 两侧按钮都有底色 | 按钮容器色 vs 同排空白处底色的通道差 ≥ 12 | 底 `#FEF7FF` / 返回 `#E8DEF8` / 下一步 `#6750A4` |
| A 步骤2 返回按钮有底色 | 同上 | 底 `#FEF7FF` / 返回 `#E8DEF8` |
| 撤销按钮是红的 | `r > g+40 && r > b+40` | `#B3261E` |

取样点规则：垂直居中、水平距左边缘 4dp —— M3 按钮是**胶囊形**（`CornerFull`），
垂直中线上填充一直延伸到左边缘，所以该点是「容器色」而非背景色，同时又避开居中文字的字形。
**断「偏红」而不写死 `#RRGGBB`**：约束 7 要求用语义角色，写死色值会在用户切换强调色 /
换深色主题时误报。

**为什么这层守护必要**：它正是第 4 层验收「UI 判断是否符合需要的操作逻辑」的**视觉子集**——
前 3 层（领域单测 / UI 断言 / 真机实证）在控件类型换错时会**全绿**，
因为换错控件类型**不改变任何语义，只改变画出来的像素**。

**验证**：

- **四门**：`:app:testDebugUnitTest` **291**（287 → +4 条像素守卫）、`:domain:test` **229**、
  `lintDebug` `No issues found.`、`assembleDebug` 成功（APK `app-debug.apk` = **33,549,783 B**，
  较上轮 33,549,719 B 仅 +64 B —— 换控件类型与调内边距几乎不增体积）。
- **变异自证（5 组，全部捕获，且各自命中预期的那一条守卫）**：

  | 变异 | 命中守卫 | 结果 |
  | --- | --- | --- |
  | 底栏高度退回改造前（外层 `spacedBy` 6dp + 垂直内边距 8dp） | `height` | CAUGHT（1 失败） |
  | B 步骤2 返回退回无底色 `TextButton` | `workflow B step 2` | CAUGHT（1 失败） |
  | A 步骤2 返回退回无底色 `TextButton` | `workflow A step 2` | CAUGHT（1 失败） |
  | 撤销按钮退回默认主色底（删 `colors = …error…`） | `undo` | CAUGHT（1 失败） |
  | 撤销按钮底色由 `error` 改成 `primary` | `undo` | CAUGHT（1 失败） |

  每条变异都**只命中它对应的那一条**守卫，说明 4 条守卫各司其职、没有互相掩护。
  变异脚本 `app/build/mut_p1c.py`（不跟踪）；脚本内对每组变异**先 assert 变异真的落盘**
  再解读结果，并在 `finally` 里逐条还原（A 侧那次变异需**同时改 import 与调用点**，
  故变异描述支持「多文件多编辑」的原子组合）。

**教训**：
- `DpRect` **没有 `center`**（与 `androidx.compose.ui.geometry.Rect` 不同）——
  写 `bounds.center.y` 会编译失败（`Unresolved reference 'center'`），须自己算 `(top + bottom) / 2`。
- 像素取样若取在**文字字形**上，会把「有没有底色」和「文字反色差异」混在一起；
  取左边缘内 4dp 才能稳定读到容器色。

---

## 2d. ✅ P1 续·撤销按钮深色不像危险动作 —— 2026-09-26 已修（提交 `1c95db9`，同提交携带中间态 `Theme.kt`）

**来源：用户真机走查后收紧措辞**

User said（逐字）：「撤销本次执行的按钮背景改成红色表示这是个危险性质的按钮」

§2c 的 V3 把底色改成了 `colorScheme.error`，浅色下确实读到 `#B3261E` 深红、守卫也绿。
但**真机是深色**（`theme_mode=System` + 设备当前深色），用户看到的是**淡粉**，于是措辞从
"背景改红色" 收紧为 "表示这是个危险性质的按钮" —— 即：**看起来不像危险**。

**根因（源码 + 真机像素双向确认）**

`ColorSchemes.kt:145-152` 把 error 族**刻意钉死为 material3 默认**（正是为了保住全仓 18 处
把 `error`/`onError` 当**文字色**用的地方），而 material3 的 error 族**按模式反转**：

| 角色 | 浅色 | 深色 |
| --- | --- | --- |
| `error` | `#B3261E` 深红 ✅ | `#F2B8B5` **淡粉** ❌ |
| `onError` | `#FFFFFF` 白 | `#601410` 暗红 |
| `errorContainer` | `#F9DEDC` **粉底** ❌ | `#8C1D18` 深红 ✅ |
| `onErrorContainer` | `#8C1D18` 深红 | `#F9DEDC` 米白 |

（从 `material3@1.4.0` sources jar 的 `ColorDarkTokens.kt:23-30` + `PaletteTokens.kt:26-35` 逐行核实。）

⇒ **没有哪一个角色能同时在深浅两色下都"像危险"**：`error` 在深色是淡粉，
`errorContainer` 在浅色是粉底。真机像素采样 `(247,182,187)` ≈ `#F7B6BB` 印证了淡粉。
且**不能改 `error` 角色本身** —— 那是给文字色用的（深底上要提亮才读得清），
18 处诉求与"底色"冲突（`grep -rn "colorScheme\.error\|colorScheme\.onError"` 全为文字色，
当容器底色用的全仓只有报告页撤销按钮一处）。

**修复：新增 `Theme.kt` 的 `dangerActionButtonColors()`，按底色明度在两档间切换**

```kotlin
@Composable
fun dangerActionButtonColors(): ButtonColors {
    val scheme = MaterialTheme.colorScheme
    return if (scheme.background.luminance() < 0.5f) {
        ButtonDefaults.buttonColors(
            containerColor = scheme.errorContainer,
            contentColor = scheme.onErrorContainer,
        )
    } else {
        ButtonDefaults.buttonColors(
            containerColor = scheme.error,
            contentColor = scheme.onError,
        )
    }
}
```

- **浅色走 `error`**（`#B3261E` 深红）、**深色走 `errorContainer`**（`#8C1D18` 深红）——
  两档各取所长，且**零新增色值**，全部落在 M3 令牌内。
- 深浅判定用 `background.luminance() < 0.5f`：`ColorScheme` **没有** `isLight()` 之类辅助，
  主题算出的 `dark` 局部变量也传不到叶子页面；判底色明度是 M3 自己的做法
  （`BasicEdgeToEdgeDialog` 即如此）。对纯黑模式同样成立。
- **禁用态不在此处理**：`buttonColors` 只传 container/content 时，`disabled*` 自动继承
  `defaultButtonColors` 的灰化角色（已核 `Button.kt:584-596`）。
- `ExecutionReportPage.kt:120` 改为 `colors = dangerActionButtonColors()`。

**守卫必须同时改（原 4 条全部假绿）**

原 4 条守卫全部**裸组合页面、没包 `PixFoldTheme`** ⇒ 采到 material3 **基线浅色**
（`error` `#B3261E`），而设备是**深色**（`#F2B8B5`），缺陷因此被放过。
修正：新增 `setContentLight` / `setContentDark` 两个入口（`PixFoldTheme(darkTheme = …, dynamicColor = false)`，
`dynamicColor = false` 保证确定性 —— 动态取色依赖系统壁纸、Robolectric 下不可控），
全部取样改在真实主题下进行。

**原「偏红」断言抓不住本缺陷**：深色基线 `error = #F2B8B5 = (242,184,181)`，
`242 > 184+40` 且 `242 > 181+40`，`r > g+40 && r > b+40` **照样通过** —— 可它是淡粉。
⇒ 新增**明度判据** `lum < 0.25`，并把原单条测试**拆成浅色 / 深色两条**：

| 守卫 | 判据 | 实测读数 |
| --- | --- | --- |
| 浅色下撤销按钮是暗红 | `r > g+40 && r > b+40` 且 `lum < 0.25` | `#B3261E`，lum **0.1106** |
| 深色下撤销按钮是暗红 | 同上 | `#8C1D18`，lum **0.0652** |

**验证**：

- **四门**：`:app:testDebugUnitTest` **292**（291 → +1 条深色守卫）、`:domain:test` **229**、
  `lintDebug` `No issues found.`、`assembleDebug` 成功（APK **33,046,763 B**；较 minSdk 30
  时的 33,549,783 B 减少 503,020 B，属 minSdk 34 的连带效果，非本轮视觉改动所致）。
- **变异自证（2 组，各命中自己那一条）**：

  | 变异 | 命中守卫 | 结果 |
  | --- | --- | --- |
  | `dangerActionButtonColors()` 永远用 `error`（即修复前行为） | 深色用例 | CAUGHT —— `实测亮度=0.5649442091904655 (#FFF2B8B5)` |
  | 浅色分支改用 `errorContainer` | 浅色用例 | CAUGHT —— `r=249 g=222 b=220 (#FFF9DEDC)` |

  第一条精确复现了用户抱怨的那个颜色，且**只被新增的深色用例杀死**（旧「偏红」断言会放行）。

**教训（务必记住）**：
- ⚠️ **`git checkout -- <file>` 会连未提交的改动一起丢掉**：用它"还原变异"时把新写的
  `dangerActionButtonColors` **和** 早先 minSdk 34 的清理一起抹掉，事后靠 `edit` 逐条重放才恢复。
  **变异还原必须用 `cp` 备份文件，不要用 `git checkout --`**（备份放工作区 build 目录，
  沙箱 `/tmp` 不跨 bash 调用保留）。
- 本轮再次印证 §2c 的结论：**语义树分辨不出"像不像危险"**——
  `#F2B8B5`（淡粉）与 `#8C1D18`（深红）在语义层完全一样，只有**取像素 + 判明度**能分辨。

---

## 3. P1：A 侧已有的筛选模式未推广到计划页/报告页

**来源：代码核实**

- 建议表**已有**「仅问题 / 全部」筛选（`ProposalTable.kt`：`TAG_FILTER_ALL` / `TAG_FILTER_CONFLICTS`）
- 计划页（`RenamePlanPage.kt`）与报告页（`ExecutionReportPage.kt`）**都没有**

**问题**：56 行里通常只有少数是「跳过 / 失败」，却要滚完全部 56 行。
语义上「只显示需要我关注的」比「全量平铺」更贴合"预览是主工作区"。

**注意**：报告页的「跳过 / 失败」筛选是**有语义价值的**
（成功项通常无需逐条查看），但需确认用户是否要"看全量以求安心"。

---

## 4. P2：B 侧第 3 步「策略」与「计划结果」的因果距离

**来源：代码核实（`PackPreviewPage.kt:243-332`）+ 真机走查**

`plan` item 内部顺序为：

```
执行计划（标题）
  → 策略选择（输出冲突策略 / 源目录策略 / 清理空目录）
  → PlanHeader（共 N 卷 · 将执行 X · 跳过 Y）
  → 逐卷 PlanRow
  → 「确认打包」
```

**顺序本身是对的**（先定策略，再看结果），但**策略改动后计划列表在其下方很远**，
真机上要滚动才能看到「改了策略会怎样」。走查时我确实来回滚动确认因果。

注：`PlanHeader` 已在策略下方紧邻，缓解了一部分 —— 但 5 卷时可行，卷数多时仍吃力。

**候选方向**：策略区吸顶；或策略变更时自动滚到 `PlanHeader`。

---

## 5. P2：危险动作确认路径长

**来源：真机走查**

- 「清理空目录」「删除源目录」需**两次确认**（通用 + 独立危险确认）——
  交互本身**清晰且真机验证过**（文案与规格 §9.2 逐字一致），**不需要改**
- 但触发这些动作**必须先滚到页面底部**

**说明**：这是 P1/P2 的**派生问题** —— 终局动作固定后自然缓解。

---

## 6. P3：跨工作流的模式一致性

**来源：代码核实**

| 项 | 状态 |
| --- | --- |
| 返回层级（3→2→1→首页） | ✅ **已统一**（两侧 `BackHandler` 同构） |
| 步骤命名（步骤1/2/3） | ✅ 已统一 |
| 步骤容器结构 | ⚠️ 高度同构但**各写一份**：`WorkflowASteps` 265 行 / `WorkflowBSteps` 206 行 |
| 「下一步」按钮位置 | ⚠️ **不一致**（见第 2 节） |

**说明**：两侧步骤内容差异确实大（排序·命名·计划 vs 漫画库·元数据·计划），
容器重复**当前不构成问题**（YAGNI）；但**行为不一致**（按钮位置）应消除。

---

## 7. P3：MetadataPage 单页承载过多

**来源：代码核实**

`MetadataPage.kt` **637 行**（B 侧最大页面），一页内含：

- 卷切换 chips + 当前卷摘要
- 7 个字段编辑器（Title / Series / Writer / Number / LanguageISO / CBZ 文件名 / 输出目录）
- 每字段的「建议」说明 + 「恢复建议」按钮
- 「一键清理作者」等批量辅助
- 批量设置（字段选择 + 批量值 + 应用 / 强制覆盖）

**待实测**：真机上单页是否过长、是否需要分组或分区导航。

---

## 8. 附带发现：「原文件名」组件的默认关闭

**来源：真机实测（P7 走查）**

默认命名方案是「根目录名 + 图片序号」，**`OrigName` 组件默认未启用**。

- 建议表**逐行显示原路径**（`day1/IMG_20260701_001.jpg`）✅ 对照清晰
- 但**原文件名不进入输出**，默认方案下用户若想保留原名需手动启用该组件

**这不是缺陷**（设计如此），已作为设计事实记录。
相关裁决见 `HANGOFF.md` §11 第 16 项（已实施「方案 A：实时示例行」）。

---

## 9. 汇总与建议顺序

| 优先级 | 项 | 一句话 | 性质 |
| --- | --- | --- | --- |
| **P0** | 1 | 命名页头部占 88%，预览不在首屏 | **违反约束 3** | **✅ 2026-09-24 已修** |
| **P1** | 2 | 终局动作在做列表内，要滚 8–12 次；且与「下一步」按钮位置不一致 | 行为分裂 | **✅ 2026-09-25 已修**（连同 2b/2c/2d 一并提交 `1c95db9`） |
| **P1** | 3 | 计划页/报告页缺筛选 | 一致性与可用性 |
| **P2** | 4 | B 侧策略与计划的因果距离 | 可用性 |
| **P2** | 5 | 危险动作触发路径长 | 第 2 项的派生 |
| **P2** | 10 | 深色下卡片是 material3 基线紫灰，与种子派生背景**色相错配** | **违反约束 7（配色未走语义派生）** |
| **P1** | 12 | 芯片行横向滚动把靠后的可操作控件**挤出视口**（7 处同源） | **违反约束 3/7 + §0 布局约定** | **✅ 2026-09-27 已修** |
| **P3** | 6/7 | 容器重复；MetadataPage 637 行 | 结构性 |

**建议顺序**：~~**P0 先做**~~（**✅ 2026-09-24 已完成**），
~~再 P1（固定终局动作）~~（**✅ 2026-09-25 已完成**）；~~长列表筛选~~ 仍待办（§3），
P2/P3 视情况。第 2 项修复**真机核对已通过**（2026-09-25，与 §2b 同轮，用户授权后执行）；
离线全绿：282 + 229 + lint 零告警 + 6 组变异全捕获 —— 底栏是纯布局位移、不涉数据路径。

> **流程约定**：以上均为**设计变更**（动页面结构），需先出设计方案交用户确认，
> 再进入实现；实现后按「修复自证三层」（领域单测 → UI 断言 → 真机实证）交付。
> 第 10 节属**行为变更（改变现有观感）**，须先经用户裁决
> —— **2026-09-21 用户已裁决「修」，离线实现与断言已完成，真机核对亦已于同日完成**
> （见本节末尾「✅ 真机核对已完成」）。
> 连带新增的第 11 节（色彩标准对 5 个风格无效 → 置灰）同属用户裁决后实施，真机核对同日完成。
> ⚠️ 本句曾写「真机核对待授权进行（尚未完成）」，与第 10 / 11 节末尾的已完成记录矛盾，
> 属文档漂移，已于 2026-09-24 随 P0 收口一并订正。
> 第 1 节（P0）则已完成全部离线自证 + 探针实测 + **真机核对（2026-09-24，用户授权后执行）**，
> 首屏建议行 0 → 5 条、固定区 141dp、摘要栏吸顶与展开回顶均在真机复现。
> **第 2 节（P1 底栏化）已于 2026-09-25 完成离线自证**（282 + 229 全绿、lint 零告警、
> 6 组变异全捕获），**真机核对已通过**（2026-09-25，与第 2b 节同轮，用户授权后执行）。
> **第 2b / 2c 节（底栏分槽 + 步骤 1 头部伸缩 → 底栏视觉规格）同样已完成离线自证与真机核对**：
> 2b 的七个位置读数见本节 §2b「真机核对」表；2c 的三条视觉规格（底栏 71→61dp、
> 返回类按钮带填充底色、撤销按钮红底）与其 4 条像素级守卫读数见 §2c。
>
> **2026-09-21 追加第 4 层**：操作逻辑符合性走查（改动是否与其他操作/状态互相矛盾）。
> 第 2 节修复后，原「下一步固定、确认要滚」的行为分裂已消除，三处「Tab 会滚出视口」
> 的错误注释也已订正 —— 这两点正是第 4 层要查的内容。

---

## 10. P2：配色只映射 24 个角色，扩展角色不随种子色变化（深色下卡片色相错配）—— ✅ 2026-09-21 已修

**来源：代码核实 + 探针实测（2026-09-21）**

**机制**：`ColorSchemes.toColorScheme()` 用 `base.copy(...)` 只覆盖约 **24** 个角色；
`surfaceBright` / `surfaceDim` / `surfaceContainerLowest` / `surfaceContainerLow` /
`surfaceContainer` / `surfaceContainerHigh` / `surfaceContainerHighest` /
`primaryFixed` / `primaryFixedDim` / `secondaryFixed` / `tertiaryFixed` /
`onPrimaryFixed` / `onPrimaryFixedVariant` **不在其中** —— 它们是 `lightColorScheme()` /
`darkColorScheme()` 的**基线常量**，因此**不随强调色 / 色彩风格 / 色彩标准变化**。

**实测证据**（种子 `#3F51B5` 靛蓝 vs `#2E7D32` 绿；探针逐项比较 38 个角色）：
两组种子下这些角色取值**逐字节相同**，故"不受种子影响"成立：

| 角色 | 浅色 | 深色 |
| --- | --- | --- |
| `surfaceContainer` | `#F3EDF7` | `#211F26` |
| `surfaceContainerHigh` | `#ECE6F0` | `#2B2930` |
| `surfaceContainerHighest` | `#E6E0E9` | `#36343B` |
| `surfaceBright` | `#FEF7FF` | `#3B383E` |
| `primaryFixed` | `#EADDFF` | `#EADDFF`（深色下仍是淡紫） |

对照组（**确实**随种子变化）：`primary` / `primaryContainer` / `secondary` / `tertiary` /
`background` / `surface` / `onSurface` / `surfaceVariant` / `outline` / `inverseSurface` 等 19 个。
深色实测：`surface = #121318`（靛蓝中性）而 `surfaceContainer = #211F26`（基线紫灰）。

**后果**：

1. 非靛蓝强调色下，`surface`（种子派生）与 `surfaceContainer*`（基线紫灰）**色相不一致**
   —— "背景对了、卡片还是 M3 基线紫"。
2. 深色下最明显。**这正是「纯黑背景」开关能救场的原因** —— `withPureBlack()` 把这些
   容器角色一并压到 `#000000`，错配被掩盖；不开纯黑时就是"深底 + 基线紫灰卡"。
   反过来说：用户偏好"莫奈 + 纯黑"很可能部分是**被这个错配逼出来的**。
3. `*Fixed*` 系列在深色下保持浅紫；若将来用到固定色角色（M3 的 fixed 色板本就用于
   跨深浅保持一致的强调块），会明显偏色。
4. **error 族不受种子影响是刻意的**（产品约束 5「破坏性动作必须醒目」，见
   `ColorSchemes.kt` 注释与单测）—— **不属本条问题，勿一并"修掉"**。

**候选修法**：

| 方向 | 说明 | 代价 |
| --- | --- | --- |
| A1 补全映射 | 按规范为扩展角色算对应调色板与 tone（`DynamicScheme` 已提供 `surfaceContainer*` / `surfaceBright` / `*Fixed*`），纳入 `copy(...)` | 改动中等；**会改变现有观感**，须过四层自证 + 真机核对 |
| A2 仅记录 | 不动，等用户明说卡片颜色不对再修 | 零风险；错配留存 |

**✅ 2026-09-21 用户裁决：修（A1）。已实现。**

`ColorSchemes.toColorScheme()` 补入 **19 个扩展角色**（7 个 surface 档 + 12 个 `*Fixed*`），
取值一律来自算法输出的 `DynamicScheme`，不再落回 `base`。
连带改动：`withPureBlack()` 补上 `surfaceBright = Color.Black` / `surfaceDim = Color.Black`
—— 二者补全映射后会随种子色变化，若不在纯黑时一并压掉，深色纯黑下又会冒出非黑浅色表面。
**error 族保持"刻意不受种子影响"未动**（见上文后果 4）。

**修复后实测**（同一探针，靛蓝 `#3F51B5` vs 绿 `#2E7D32`，**已不再相同**）：

| 角色 | 靛蓝 | 绿 |
| --- | --- | --- |
| 浅色 `surfaceContainer` | `#EFEDF4` | `#ECEFE6` |
| 深色 `surfaceContainer` | `#1F1F25` | `#1D211B` |
| 浅色 / 深色 `primaryFixed` | `#DEE0FF` | `#BCF0B4`（仍跨深浅一致，但**已随种子**） |

**两套规范的差异面同时变大**：受影响的 4 个风格从 **21–24/48** 升到 **36–43/48**
（19 个角色不再恒定）。**数字变大不是断言松了，是缺陷少了** ——
也反证了此前"2021/2025 变化微小"部分源于漏映射。

**回归护栏**（`ColorSchemesTest`，判据是"**是否还等于 material3 基线色**"）：
未映射的角色在**所有**种子 / 风格 / 标准 / 深浅组合下恰等于 `lightColorScheme()` /
`darkColorScheme()` 的对应值，映射后必须不同。
**第一版判据用的是"6 个种子下取值是否唯一" —— 被 `surfaceContainerLowest` 浅色判失败**，
因为它恒为 `#FFFFFFFF`；但那是算法**正确**行为（该角色取中性色板 tone 100）。
**教训：「与种子无关」≠「没接上」。**

**变异验证**（4 组，全部被捕获）：删除 19 个映射 → 1 项失败；
`enabled = false` 恒置灰 → 2 项失败；`enabled = true` 从不置灰 → 1 项失败；
把 `Monochrome` 错列进敏感名单 → 1 项失败。

**✅ 真机核对已完成（2026-09-21，用户授权后执行）**

条件：深色 + 强调色「绿」+ 动态取色关 + 纯黑关（**修复前必然暴露错配**的组合），
取原始帧缓冲逐点采样：

| 取样点 | 修复前（预期） | 实测 |
| --- | --- | --- |
| 背景 | 种子派生（绿调） | `#0F0F0A`（绿调） |
| 卡片（工作流 A / B） | `#211F26`（基线紫灰，B>R） | **`#28281D`（绿调，R=G>B）** |

**结论**：卡片已随种子色派生、与背景**同色相** —— "背景对了、卡片还是 M3 基线紫"
的错配在真机上**已消失**。核对后设备已恢复原设置状态。

---

## 11. P2：「色彩标准」对 9 个色彩风格中的 5 个是无操作开关 —— ✅ 2026-09-21 已修

**来源：接线断言取证时顺带实测（2026-09-21）**

**机制**：设置页把「色彩标准（2021 / 2025）」做成**全局开关**，但如 HANGOFF §9.1
第 1 步所证，`ColorSpec2025 : ColorSpec2021()` 只重写 `NEUTRAL` / `TONAL_SPOT` /
`EXPRESSIVE` / `VIBRANT` 四个变体，其余分支一律 `else -> super`。
因此选中 Monochrome / Fidelity / Content / Rainbow / FruitSalad 时，
切换 2021↔2025 **不改变任何颜色、界面无任何反馈**，而页面文案未作说明。

**后果**：用户会以为"这个开关坏了"。这比"没有这个开关"更糟 —— 它损害设置页的可信度。

**✅ 用户裁决（2026-09-21）：置灰**（未采纳"加说明文字"）。

实现要点：

- `ColorSchemes.COLOR_SPEC_SENSITIVE_STYLES`（顶层 `Set<ColorStyle>`）+ 
  `ColorStyle.isAffectedByColorSpec()` 作为**单一事实源**，
  **UI 置灰与测试断言共用同一个判定**，不会各写一份而漂移。
- 设置页「色彩标准」行传 `enabled = settings.colorStyle.isAffectedByColorSpec()`；
  `ChoiceRow` 新增 `enabled: Boolean = true`：禁用时 `.clickable(enabled = false)`、
  文字按 M3 禁用态 `alpha = 0.38f`、且**不可打开对话框**（`if (open && enabled)`）。
- **置灰而非隐藏**：值仍可见，用户能看出"我设过 2025，只是当前风格下不生效"，
  不会以为设置丢了。**选择保留、不重置** —— 切回受影响的风格即自动生效，无需重设。

**⚠️ 已知取舍**：用户只选了置灰、未选文案，故"**为什么**灰了"没有文字解释。
若日后仍觉费解，补一句说明即可（不改变行为）。

**UI 断言**（`SettingsScreenTest`）：对不受影响的风格断言 `assertIsNotEnabled()`
且点击后 `TAG_CHOICE_DIALOG` **不存在**；对受影响的风格断言 `assertIsEnabled()`
且点击后对话框存在。**变异验证**：`enabled = false` 与 `enabled = true` 两个方向
分别被 2 项 / 1 项断言抓住。

**✅ 真机核对（2026-09-21，用户授权后执行）**：选「单色」后，「色彩标准」行的语义容器
实测 `clickable="true" enabled="false"`（`bounds=[42,1687][1222,1855]`），
**点击无任何对话框弹出**；切回「标准」后同一容器变 `enabled="true"`，再点即弹出对话框
（含 `Material 3 (2021)` / `Material 3 Expressive (2025)`）。值在置灰态下**仍可见**。

---

## 12. ✅ P1：芯片行横向滚动把靠后的可操作控件挤出视口（7 处同源）—— 2026-09-27 已修

**来源：第 13/15 条真机核对途中用 `dumpui.bat` 量坐标时发现（2026-09-27）**

**现象**：工作流 A 步骤 1 的集合芯片行，361dp 下第 4 个集合**完全落在视口外**
（`旅行照片（30）[98,413][452,483]` / `扫描件（14）[592,413][897,483]` /
`杂图（12）[1037,413][1264,483]` —— 最后一个的右边界 = **1264 = 屏宽**）。
向左滑该行后才露出 `章节（16）[911,413][1166,483]`。

**⚠️ 这不是新增 mock 集合引入的**：3 个集合时 `杂图` 的右边界**已恰好顶到屏宽**，
缺陷本来就存在（改为 2 行卡片后勉强塞下），第 4 个集合只是让它显形。
同类于 HANGOFF §9 第 1 条（组件卡片横向滚动 → 靠后按钮落在视口外），
也违反本文件 §0 已固化的布局约定「**361dp 下不给可操作控件加横向滚动**」。

**排查后发现同源缺陷共 7 处**（逐一 dump 实测，修复前 / 修复后可见数）：

| 位置 | 修复前 | 修复后 |
| --- | --- | --- |
| A 步骤 1 集合芯片 | **3/4**（`杂图` 顶到屏宽） | 4/4（换第 2 行） |
| A 步骤 1 排序字段芯片（展开态） | **3/6**（`目录名` 已到 x1182，`扩展名`/`修改时间`/`大小` 全在外） | 6/6（分 2 行） |
| B 卷切换行（元数据页） | **3/5**（`进击的巨人 外传[860,371][1219,441]`，`海贼王` 两卷全在外） | 5/5（换第 2 行） |
| B 批量字段芯片 | 3/3（贴边） | 3/3 |
| B 打包页卷芯片 | **3/5** | 5/5 |
| B 打包页冲突 / 源策略芯片 | 贴边 | 正常 |

**✅ 用户裁决（2026-09-27）：改成 `FlowRow` 自动换行**（未采纳"保留横向滚动只加提示"）。

**实现**：7 处 `Row + horizontalScroll(rememberScrollState())` → `FlowRow`，
分布在 4 个文件 —— `ui/workflowa/SortAndPreviewPage.kt`（集合行，1 处）、
`ui/workflowa/SortRuleEditor.kt:127`（排序级别，1 处）、
`ui/workflowb/MetadataPage.kt`（卷切换 `:324` + 批量字段 `:683`，2 处）、
`ui/workflowb/PackPreviewPage.kt`（卷切换 `:113` + 两个策略行 `:285`/`:306`，3 处）。
⚠️ `FlowRow` **没有 `verticalAlignment` 参数**（`Row` 有），须改用 `itemVerticalAlignment`。
**API 事实**：Compose foundation 1.12.1 下 `FlowRow` **无需** `@OptIn(ExperimentalLayoutApi::class)`。

**⚠️ 离线守卫只能守"结构"，守不了"放得下"**：本缺陷属布局溢出，
而 **Robolectric 不做真实文本测量** —— 芯片标签宽度全部算成 ~0
（实测 `trip 34.9dp / scan 34.6 / misc 34.3 / cn 34.3`），于是"宽度够不够"恒为"够"。
曾写过按 `getBoundsInRoot()` 比屏宽的断言，把 `horizontalScroll` 删掉后**测试仍全绿**
（变异存活）⇒ 无杀伤力，已删除；撑到 20 个中文字符的第 5 个集合也照旧全绿。
**真机 `dumpui.bat` 量真实坐标才是唯一能证伪的场合。**
故离线这层退而守**结构性事实**（新建 `app/src/test/kotlin/com/pixfold/d1/ChipRowWrappingTest.kt`，2 条）：
①这 4 个文件不得再出现 `horizontalScroll`（去注释后扫描）；
②各文件 `FlowRow(` 数量须等于期望表 `{SortAndPreview:1, SortRuleEditor:1, MetadataPage:2, PackPreviewPage:3}`。
**变异验证**：改回 `Row + horizontalScroll` ⇒ **两条都失败**；
改成**普通 `Row`（无 scroll）** ⇒ **只有第 ② 条失败** ⇒ 两条可判别、互不遮蔽。
（首版普通 `Row` 变异把 `verticalArrangement` 留在原地 ⇒ 编译失败、`tests=0`，**不是证据** ——
再次确认：变异后必须确认 JUnit XML 存在且 `tests>0`。）

**⚠️ 教训**：**变异能存活就说明断言没杀伤力 —— 留着它只会给假保障。**

**四道闸门**：`app 313`（311 → +2 守卫）/ `domain 242` 全绿、lint `No issues found.`、
APK **33,691,473 B**（未变，纯布局改动）；真机 7 处逐一复核通过。

---

## 附：本文件的取证方式（可复现）

| 结论 | 取法 |
| --- | --- |
| 设备/密度/逻辑尺寸 | `adb shell wm size`、`adb shell wm density` |
| 头部高度 2632px | 真机 `uiautomator dump`，读节点 `bounds` |
| 首屏建议行数 0 | 同上 dump 中检索 `IMG_` / `新名称` |
| 按钮固定 vs 列表内 | 读源码：按钮是否在 `LazyColumn` 之外 |
| 页面行数 | `wc -l` |
| 筛选器有无 | 检索 `TAG_FILTER_*` 的实际使用点 |
| 第 10 节"角色不受种子影响" | 临时 JVM 探针：两组种子各生成 `ColorScheme`，用 **`toArgb()`** 逐角色比较；取值相同的即未从种子派生 |
| 第 10 节色彩标准覆盖面 | 同一探针比较 `SPEC_2021` / `SPEC_2025` 的 48 个角色，计数变值角色 |


**⚠️ 一条取证教训**：本清单初稿曾据 Robolectric 探针（`w400dp-h1000dp`）
称"头部约 862dp、仅剩约 130dp"，而**真机是 794dp 视口** —— 探针视口比真机更宽松，
数字不可直接当结论。**结论一律以真机 dump 为准**（此项已改为实测值）。
