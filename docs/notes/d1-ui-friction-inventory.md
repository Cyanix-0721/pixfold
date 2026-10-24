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

> ⚠️ **上面这句只对浅色成立，2026-09-25 已被用户驳回并修正** —— 真机是**深色**，
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

## 2d. ✅ P1 续·撤销按钮深色不像危险动作 —— 2026-09-25 已修（提交 `1c95db9`，同提交携带中间态 `Theme.kt`）

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

## 3. ✅ P1：A 侧已有的筛选模式未推广到计划页/报告页 —— 2026-09-26 已修，真机核对通过

**来源：代码核实**

**当时实况（2026-09-25 记）**：建议表**已有**「仅问题 / 全部」筛选
（`ProposalTable.kt`：`TAG_FILTER_ALL` / `TAG_FILTER_CONFLICTS`）；
计划页（`RenamePlanPage.kt`）与报告页（`ExecutionReportPage.kt`）**都没有**。
**问题**：56 行里通常只有少数是「跳过 / 失败」，却要滚完全部 56 行；
语义上「只显示需要我关注的」比「全量平铺」更贴合「预览是主工作区」。
**注意**：报告页的「跳过 / 失败」筛选是**有语义价值的**（成功项通常无需逐条查看），
但需确认用户是否要「看全量以求安心」。

### 3.1 用户裁决（两项，2026-09-26）

- **筛选条位置 = 固定常驻**（不随列表滚走）。理由：与报告页头部一致，且解决
  「想改筛选还得滚回顶部」；代价是常驻约 48dp 竖向空间（P0 曾因头部过高被投诉）。
  未选「随头部滚动（改动最小）」。—— 落实结果见 §3.5 的常驻实测。
- **改动范围 = 只改 A 侧计划页**。理由：台账写的是「计划页/报告页」，报告页本身
  A/B 共用故自动覆盖；B 侧的计划列表嵌在 `PackPreviewPage.kt` 里（且它自己有 §4 待处理）。
  ⇒ 本轮**不动** `PackPreviewPage.kt` 的内联计划。

### 3.2 修法：抽出一个共享的筛选条，而不是抄三遍

原实现把筛选条写死在 `ProposalTable.kt` 的 `ProposalSummary` 里。三处各写一遍必然漂移
（`§12` 的芯片行溢出就是「7 处同源」的教训），故新建
`pixfold-d1/app/src/main/kotlin/com/pixfold/d1/ui/components/IssueFilterRow.kt`：

```kotlin
@Composable fun IssueFilterRow(issueCount: Int, issuesOnly: Boolean,
    onIssuesOnlyChange: (Boolean) -> Unit, modifier: Modifier = Modifier,
    allTag: String, issuesTag: String)
```

无状态（筛选状态由各页持有，遵循项目「本地状态权威 + 每次变更上报」的约定）。
`ProposalSummary` 改为复用该组件 —— `TAG_FILTER_ALL` / `TAG_FILTER_CONFLICTS` /
`TAG_PROPOSAL_SUMMARY` 与 `a_table_summary` 复数键的文本契约**一字未动**
（`NamingPageTest.kt:641` 与多处 `assertTextEquals` 钉着）。

组件只负责画，**「N 是什么」与「筛完怎么保住行号」由调用方决定**：

| 调用点 | N 的口径 | 就绪谓词 |
| --- | --- | --- |
| 命名页 `ProposalSummary` | `conflictCount + warningOnlyCount` | （既有逻辑，未动） |
| 计划页 `RenamePlanPage.kt` | `plan.skipped` | `op.isSkipped` |
| 报告页 `ExecutionReportPage.kt` | `report.skippedCount + report.failedCount` | `entry.outcome != ExecOutcome.Succeeded` |

**行号必须保原始下标**：计划页与报告页都用 `withIndex()` 存 `(原始 index, 元素)`，
再 `items(shown, key = { it.index }) { RenamePlanRow(it.value, it.index, ...) }`。
理由不是审美 —— `planRowTag(index)` 是「计划第几行」的契约，报告行序又与计划行序靠
同一序号核对（`Execution.kt` KDoc 明示 entries 不重排）。改用可见序号会出现
「报告第 5 行对到计划第 3 行」。见 §3.4 的 P2/R2 变异。

其余细节：

- 筛选条置于各页 `Column` 内、`LazyColumn` **之外**（落实裁决 1）。
  **整页仍然只有一个滚动容器** —— 这是硬约束，内层 `LazyColumn` 被挤到 0 高的坑真机踩过。
- **筛空后的空态是必需的**：`if (issuesOnly && shown.isEmpty())` 才会渲染
  `common_filter_none`（「没有需要注意的项」）。条件写成裸 `shown.isEmpty()` 会把
  「计划本身为空」也当成筛选结果，故加了 `issuesOnly &&`。
- **报告页默认必须是「全部」**：报告是「刚才到底跑了什么」的凭证，默认收窄会让用户
  以为成功项根本没执行。`PlanUiTest.kt:175` 也遍历 `ExecOutcome.entries` 断言三类
  都在场 —— 这条约束是硬的，不是口味。
- 文案：新增 `common_filter_all` / `_all_cd` / `common_filter_issues`（`%1$d`）/
  `_issues_cd` / `common_filter_none` 五键（en + zh，落 `strings_common.xml`）；
  **删除** `strings_workflowa.xml` 里旧的 `a_table_filter_all` / `_all_cd` /
  `a_table_filter_issues` / `_issues_cd` 四键 —— 留着必被 lint `UnusedResources`
  在 `warningsAsErrors` 下判死。

### 3.3 顺带清掉一处死代码（用户授权）

`ui/components/ThumbCard.kt`（54 行）**全文零调用点**：它是 `cdd4932` 引入的静态网格卡片，
被 `fe64adf` 自研拖拽排序时的 `DraggableThumbCard.kt` 取代，此后一直是死代码。
但 `ThumbCard.kt:20` 的 `const val TAG_GRID_CARD = "grid-card"` **仍活着** ——
被 `DraggableThumbCard.kt:67` 与 **6 个测试文件**共用。

⇒ 处置：把常量搬到 `DraggableThumbCard.kt` 顶层（同包，6 个测试文件**零 import 变更**；
其中 `ScrollToTopTest.kt` 本就在 `package com.pixfold.d1.ui.components` 内），再删整个文件。
证据：清理后 `grep -rn "ThumbCard" app/src/main/ | grep -v DraggableThumbCard` 无输出；
解包 APK 全部 22 个 dex 做字节子串计数 ⇒ `DraggableThumbCard` 43 次、`ThumbCard`
**也恰好 43 次**（相等即证明后者的每次出现都是前者名字的子串，独立类已不存在）。
md 里另有 3 处 `ThumbCard` 引用（`HANGOFF.md:2785`、`docs/notes/d1-archive-domain-semantics.md`
的 Flutter 代际测试表、`docs/superpowers/plans/2026-09-16-d1-p2-grid-list-preview.md`），
**均为历史记录，不改** —— 改它们等于篡改当时实况。

### 3.4 自证

- **测试**：新增 `pixfold-d1/app/src/test/kotlin/com/pixfold/d1/ui/plan/IssueFilterTest.kt`
  （6 条）：计划页筛选 + 行号保持 + 筛空空态 + 筛选后底栏仍可达；报告页同样四件事；
  以及命名页 `ProposalSummary` 复用共享组件后 `TAG_FILTER_CONFLICTS` 的 N 与回调仍对。
  造跳过项的方式 = 两次 `setOverride` 造**同最终名**的两个跳过项，下标刻意取 **4 与 8**
  —— 若实现误用可见序号，筛选后会变成 0 与 1，而 0/1 恰是「跳过项在开头」时的正确答案，
  **只有错开位置才能区分两种实现**。
- **四道闸门**：`app` **358**（+6，上一轮 352）/ `domain` **247** 全绿、
  lint **0 errors, 0 warnings**（4 hints 是既有的 `AutoboxingStateCreation`）、
  `assembleDebug` 成功 APK **33,316,124 B**。
  ⚠️ 清理 `ThumbCard.kt` 后 APK 与清理前**逐字节相同** —— 死代码确实没进包。
- **变异 11 条全部捕获、0 逃逸、0 跳过**（脚本 `pixfold-d1/app/build/mut_issue_filter.py`，不跟踪）：
  C1「仅问题」点了不生效 / C2「全部」点了反进筛选 / P1 计划页谓词失效 /
  **P2 计划页把可见序号当行号** / P3 计划页 N 改成总行数 / P4 计划页筛空不给说明 /
  R1 报告页谓词失效 / **R2 报告页把可见序号当行号** / R3 报告页 N 漏掉失败项 /
  R4 报告页筛空不给说明 / S1 命名页 N 漏掉「仅警告」项。
  逐条都命中预期用例，两条「序号」变异报的都是 `could not find any node … (TestTag = 'plan-row-4')`
  这类**位置**失败，正是它们存在的意义。

**已提交** `e35ab9c`（15 files，+642/−106，直接提交到 `dev`、无独立分支，**未推送**）。

### 3.5 真机核对（2026-09-26 通过，用户授权，PJX110 / Android 16 / 361dp×794dp）

设备侧先造出「2 个跳过项」：命名页把第 1、2 项都覆盖成同名 `dup.jpg`。
⚠️ 操作教训：`input tap` 进输入框后光标位置不定，`keyevent 123`(MOVE_END) + 连按 `67`(DEL)
**不可靠**（实测光标在行首，DEL 是 no-op），`input text` 又是**追加**而非替换。
正解 = **`input keycombination -t 120 113 29`（Ctrl+A）再 `input text`**，一次覆盖成功。

逐条读数（`uiautomator dump`）：

| # | 核对项 | 实测 |
| --- | --- | --- |
| ① | 计划页筛选条是否常驻在列表外 | `全部` `[42,392][254,504]` / `仅问题（2）` `[275,392][663,504]`，紧贴步骤页签下方、列表之上 ✅ |
| ② | 默认是「全部」还是被收窄 | 默认 72 行全渲染（行 0/1 为 `将跳过`，行 2 起 `将重命名`）✅ |
| ③ | 点「仅问题」是否只剩问题行 | 只剩 2 行（均为 `dup.jpg` + `⛔ 重名：有 2 项同名「dup.jpg」，执行时将跳过`），行 2 起全部消失 ✅ |
| ④ | 筛选后汇总/开关/底栏是否被挤掉 | `共 72 卷 · 将执行 70 卷 · 跳过 2 卷`(y567)、`清理空目录开关`(y760)、`确认执行`(y2605)、`返回步骤2`(y2605) **全在原位** ✅ |
| ⑤ | **常驻的决定性证据** | 列表 `swipe` 滚到 `扫描件/ch02/封:面?.png`、`杂图/img3.jpg` 后，筛选条 bounds **与滚动前逐像素相同**；`PlanHeader` 与清理空目录**已滚出视口**，筛选条未滚走 ✅ |
| ⑥ | 点「全部」能否恢复 | 行 1~11 的 `将重命名` 全部回来，`将跳过` 仍在行 0/1 ✅ |
| ⑦ | 无问题项时点「仅问题（0）」 | 列表清空 + `没有需要注意的项` `[56,1030][454,1100]`；`确认执行`(y2605) 与 `清理空目录开关`(y760) **未移** ✅ |
| ⑧ | 报告页默认态 | `共 72 项：成功 70 · 跳过 2 · 失败 0`；筛选条 `全部` `[42,606][254,718]` / `仅问题（2）` `[275,606][663,718]`；先列 2 条 `跳过`，随后 `成功` 逐条 ✅（**成功项默认可见**） |
| ⑨ | 报告页点「仅问题」 | 只剩 2 条 `跳过` 行，`撤销本次执行` / `完成` 底栏不变 ✅ |
| ⑩ | 报告页点「全部」恢复 | 视口内回到 `跳过`×2 + `成功`×11 ✅ |
| ⑪ | **`TAG_GRID_CARD` 搬家后网格是否还能用**（防回归） | 步骤 1 网格卡片照常渲染（`IMG_20260701_001.jpg` 等 + `固定位置` 图钉）；点第 1 张卡片 → 预览打开 `图片 IMG_20260701_001.jpg`、`第 1 / 30 页`、`将重命名为 dup.jpg` ✅ |

调试产物已清理（设备 `/data/local/tmp` 的 .xml/.png 已删并复核、`/sdcard` 无残留），
`svc power stayon` 已复位，应用已 force-stop。
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

`MetadataPage.kt` **754 行**（B 侧最大页面；2026-09-26 复核，旧记 637 已过期），一页内含：

- 卷切换 chips + 当前卷摘要
- 7 个字段编辑器（Title / Series / Writer / Number / LanguageISO / CBZ 文件名 / 输出目录）
- 每字段的「建议」说明 + 「恢复建议」按钮
- 「一键清理作者」等批量辅助
- 批量设置（字段选择 + 批量值 + 应用 / 强制覆盖）

**真机实测（2026-10-18，已实测，结论：不构成「过长」，但暴露一个真实摩擦点）**

设备 PJX110 / Android 16 / 1264×2780 px / 560 dpi ⇒ **361dp × 794dp**（2.75 px/dp），
locale `zh-CN`，5 卷数据（aot-1 / aot-2 / aot-0 / op-101 / op-102）。取证 dump 与截图见
`pixfold-d1/artifacts/`（`t_meta_top.xml` 首屏、`t_sc_2.xml` 底部、`meta_top.png` /
`meta_bot.png` / `meta_ime.png`）。

| 读数 | 值 | 换算 |
| --- | --- | --- |
| 可视区（Tab 行底 `y=308` → 底栏顶 `y=2605`） | **2297 px** | **835.3 dp** |
| 页面内容总高 | **3729 px** | **1356.0 dp** |
| 内容 / 视口 | **1.62×** | — |
| 首屏外隐藏部分 | 1432 px | 520.7 dp（视口的 62%） |
| 滚到底所需全屏 swipe | **约 2 次** | 滚动成本 0.62 个视口 |

逐块高度：Number 块 106.9 dp / LanguageISO 块 132.4 dp / CBZ 文件名块 106.9 dp /
输出目录块 166.9 dp；输入框本体 81.5 dp。辅助说明文字（「建议：…」「清理建议：…」
「语言默认未设置…」）共 **9 行 / 518 px = 188.4 dp = 页面高度的 14%**，最长一条
「清理建议：谏山创（清理规则：去作者前缀 / 括号原作 / 尾部标签）」占 2 行 40.7 dp。

⇒ **判据**：1.62 个视口、2 次 swipe 即可触底，**不满足「过长」**，故
「分组 / 分区导航」**不必做**（若做只会增加一次点击而省下一次滑动，净负）。
辅助说明仅占 14%，也不是主要成本来源。

**但实测暴露一个此前未记录的摩擦点（P2，新）**：**卷切换 chips 会随滚动滚出视口**。
一次全屏 swipe 后 5 个芯片全部不可见（dump 内 `content-desc` 以「卷 」开头的节点数为 0），
**必须回顶才能换卷**；而换卷在 B 侧是高频动作（逐卷核对元数据）。且切卷后滚动位置
会回到顶部（`LazyColumn` 内容整体重建，实测 Title 回到 `y=1026` 原位）——
即「换卷 → 必然回顶 → 又要重新滚回原字段」构成一个来回。
**候选方向**：卷选择器 `stickyHeader` 化，或把当前卷切换收进底栏/Tab 行。
注：`MetadataPage.kt` 现无 `stickyHeader`、无滚动条（grep 确认）。

### 7b. P2 修复（2026-10-18，同日）：卷芯片行常驻 + 规模上限 + 「全部 N 卷」sheet

**第一步（P2 本身）**：把 `VolumeSelector` **移出 `LazyColumn`**、常驻于页面外层 `Column`
（与步骤 3 `PackPreviewPage.kt:112-140` 同构 —— 该页的卷选择器本来就在 `LazyColumn` 之外）。
选它而非 `stickyHeader` 的理由：① 仓库已有同构先例；② 常驻高度相同（芯片区 294 px =
106.9 dp，占 835.3 dp 视口的 13%）；③ `stickyHeader` 在 foundation 1.12.1 的
**3 参重载被标 `Deprecated`**，须显式传 `contentType` 才不踩弃用。

真机复测（2026-10-18）：一次全屏 swipe 后 5 个芯片 `content-desc`（`卷 aot-1`…`卷 op-102`）
全部仍在、`bounds` 未变；滚到底后点芯片切卷，路径与 CBZ 文件名随之更新且
**滚动位置保留**（`LanguageISO` 仍在 `y=882`）。代价：`LazyColumn` 视口从 2297 px 缩到
**1961 px = 713.1 dp（缩 122.2 dp / 14.6%）**。

**第二步（规模回归，本次改动引入、已修）**：用户要求补测「几十卷」形态。用
`(1..35).map { volume(id = "scale-$i", …) }` 注入到 40 卷后实测：
**38 个芯片排 13 行、芯片块 `y 350..2646` = 2296 px = 834.9 dp**，几乎吃满 835.3 dp 的
列表视口，`Title` 之后**全部字段永久不可达**（连续 11 次全屏 swipe 后仍 NONE）。
**A/B 对照定性**：同一份 40 卷数据把 `VolumeSelector` 放回 `LazyColumn` 内（旧布局）后，
6 次 swipe 即 `Number` / `LanguageISO` / `CBZ 文件名` / `输出目录` / `批量设置` 全部可见
⇒ **回归由「移出 LazyColumn 且行数无上限」共同引入，不是既有缺陷**。
（注：修复前 5 卷形态下旧布局也要回顶换卷，这正是 P2 本身。）

> ⚠️ **本节（含下面 A′ 修法与「资料」整段）是 2026-10-18 的实测与当时的修法记录，
> 已被 2026-10-23 的瀑布流改版取代**：`VISIBLE_VOLUME_CHIPS`（常驻 5 个）、
`visibleVolumeChips()`、`TOP_CHIP_MAX_LINES`、`ModalBottomSheet` 列全量 —— **全部已删**。
> 现行实现 = 宽度区间 96–156dp、芯片内文字 4 行、字符上限 30、
> **按芯片行数收口（`CHIP_COLLAPSE_LINES = 3`）+ 同尺寸虚线芯片原地展开**、
> 二级以 series 为顶点的 `Popup` 浮层。**读本节请当历史，不要当现行规格。**

**修法（A′，用户 2026-10-18 裁决）**：**承重的是芯片个数上限，不是行数上限**。
卷数 > `VISIBLE_VOLUME_CHIPS`（= 5，361 dp 下 5 个芯片恰好 2 行的实测值）时：
① 常驻行**只留前 4 卷 + 当前卷**（`visibleVolumeChips()` 纯函数，当前卷必在行内，
否则用户看不出在改哪一卷）；② 下方给「**全部 N 卷**」`TextButton`（`heightIn(min = 48.dp)`）；
③ 点开 `ModalBottomSheet` 列全量、选中即切卷并关闭。卷数 ≤ 5 时**原样全列、不出入口**。

**`FlowRow(maxLines = 2)` 试过并删掉**（变异 M4 存活）：常驻芯片已被限到 5 个，
`maxLines = 2` 永不触发 ⇒ 不承重；且在更窄屏幕上 5 个芯片可能排到第 3 行，
`maxLines = 2` 反而会裁掉第 5 个（可能正是当前卷）且无提示。

**真机复测（2026-10-18，40 卷注入）**：常驻芯片 = **5 个**（aot-1 / aot-2 / aot-0 /
op-101 / op-102）+ 「全部 40 卷」入口 `[14,686][337,854]`；3 次全屏 swipe 后
`CBZ 文件名 y=1246` / `输出目录 y=1540` / `批量设置 y=1866` / `批量值 y=2216` /
`应用 y=2209` / `尊重逐项例外 y=2419` **全部可见**（修复前 11 次仍 NONE）；
sheet 内滚到底可见 `scale-27`…`scale-35`，点 `scale-35` 后 sheet 关闭、常驻行变为
`aot-1 / aot-2 / aot-0 / op-101 / scale-35`（当前卷回到行内），页面标题区显示
`规模测试 35`、`建议：35（规模测试注入）`、`规模测试 35 第35卷.cbz` ⇒ 切卷真实生效。
取证：`artifacts/t5_40_top.xml` / `t5_40_bottom.xml` / `t5_40_sheet.xml` /
`t5_40_sheet_bot.xml` / `t5_40_after.xml`。

**第三步（步骤 3 同源，用户 2026-10-18 逐字「一起做了吧」）：抽共享组件**

步骤 3 `PackPreviewPage.kt` 的卷芯片（`TAG_PACK_VOLUME_SELECTOR`）同样在 `LazyColumn`
之外、同样无个数上限 ⇒ 卷数很多时会把该页计划列表挤下去，与修前的步骤 2 同源。
两页原本**各写一份**「`FlowRow` 遍历全部卷」（09-25 那轮同源缺陷也是两处各修一遍）⇒
按 `ui/components/IssueFilterRow.kt` 的既定做法（其 KDoc 原话「同一种交互在三处各写一遍,
必然漂移」）抽成共享组件 `ui/components/VolumeChipRow.kt`，**全站唯一实现**：

- 参数注入各页差异：`selectorTag` / `chipTag` / `allEntryTag` / `sheetTag` / `sheetListTag` /
  `sheetChipTag`（tag 命名空间不冲突）、`chipDescription`（无障碍文案，`semantics {}` 的
  lambda 不是 `@Composable`，故由调用方在外层解析好再传）、`chipLabel`（步骤 3 要标「（未勾选）」）。
- `VISIBLE_VOLUME_CHIPS = 5` 与 `visibleVolumeChips()` 随之迁入组件；`plurals meta_volume_all`
  迁到 `strings_common.xml` / `values-zh/strings_common.xml` 的 `volume_all`（跨页共用）。
- **源码结构守卫同步改**：`ChipRowWrappingTest` 的 `FlowRow` 计数表
  （`MetadataPage` 2→1、`PackPreviewPage` 3→2、`VolumeChipRow` +1），并把新文件加进
  `horizontalScroll` 扫描列表 —— 否则「把共享组件改回横向滚动」这条路会绕过守卫。

**真机复测（2026-10-18，步骤 3）**：
- **5 卷（不降级）**：5 个芯片全列、**正好 2 行**、**无**「全部」入口；先前担心的
  「（未勾选）后缀让芯片变宽 ⇒ 排到第 3 行」**实测不成立** —— 取消勾选 `op-102` 后其标签
  变「海贼王（未勾选）」、宽度增到 `[42,714][550,826]`，仍只占第 3 行，其余 4 个位置不动；
  滚到底后 5 个芯片 `bounds` 一个未变。
- **40 卷（规模注入）**：常驻 5 个芯片（y 350..644，与 5 卷形态**同高**）+「全部 40 卷」
  入口 `[56,735][295,805]`；4 次全屏 swipe 后芯片与入口位置未变，页码预览区已滚到
  `第12 卷`、底栏「确认打包」可见；sheet 内 12 次 swipe 到底见 `scale-27`…`scale-35`，
  点 `scale-35` 后 sheet 关闭、常驻行变 `aot-1 / aot-2 / aot-0 / op-101 / scale-35`，
  内容换成 `规模测试 35` / `E:\漫画库\规模测试\第35 卷` / `← 第35 卷_p001.jpg`。
- **步骤 2 回归核对（40 卷）**：5 芯片 + 「全部 40 卷」入口、`Title` / `Series` / `Writer` /
  `Number` / `LanguageISO` 首屏可见 ⇒ 步骤 3 的改动未回退步骤 2。
- 取证：`artifacts/t6_pack.xml` / `t6_pack_excl.xml` / `t6_pack_excl_bot.xml` /
  `t6s_pack.xml` / `t6s_pack_bot.xml` / `t6s_sheet.xml` / `t6s_sheet_bot.xml` / `t6s_after.xml` /
  `t6s_meta40.xml`。

**变异验证（M1–M6c，全部 KILLED）**：M1 常驻行不限个数（**首轮存活** ⇒ 补了「`scale-6` /
`scale-40` 不得存在」的直接断言才杀掉 —— Robolectric 把芯片宽度算成 ~0，40 个也能塞进 2 行，
故「字段可达」那类断言对该变异无杀伤力）；M2 去掉「全部 N 卷」入口（4 红）；
M3 当前卷不保留在常驻行（3 红）；M4 sheet 只列前 5 卷（2 红）；M5 5 卷也出入口（2 红）；
M6a 卷芯片行写成全限定 `FlowRow(`（结构守卫 1 红）；M6b 外层套 `Row + horizontalScroll`（1 红）；
M6c 改用普通 `Row`（不换行也不滚动，1 红）。

**键盘遮挡（附带核实，结论：无缺陷）**：tap 输出目录框后 IME 弹出，视口从 2297 px
缩到 **1250 px（454.5 dp，缩掉 46%）**，内容/视口比升到 2.98×，但**聚焦字段本身仍完整
可见**（输出目录框 `y=1264..1488`，底栏顶 1527）⇒ `adjustResize` +
`WindowInsets.safeDrawing` 表现正确。

**规模口径（用户 2026-09-26 给定，逐字）**：「一般都是**几十到两百张图片**，多的话**大几百张**，
极端情况**两三千张**也有，建议如何处理」。注意 `MetadataPage` 只渲染 `state.activeVolume`
（`MetadataPage.kt:124`），故**本页的规模变量是「卷数」而非「图片总数」** ——
卷数一般是「一套漫画的册数」（几十量级），而每卷的图片数是几百量级，后者分摊在
`LazyColumn` 的懒组合里、不进本页常驻区。

**领域成本实测（探针 `domain/src/test/.../perf/RenamePlanPerfProbe.kt`，2026-09-26）**：
用户口径下工作流 A 计划页每次重组的领域耗时（每张按 50MB 造数据）：

| 场景 | n=200 | n=800 | n=3000 |
| --- | --- | --- | --- |
| `buildProposals`（单集合，40 目录） | 0.67ms | 1.55ms | 4.54ms |
| `buildLibraryRenamePlan`（1 集合，计划页真实路径） | 0.36ms | 0.81ms | 3.83ms |

最坏「n=3000 全同目录 + 大量重名（走 `attachWarnings` 重分支）」2.66ms；
真实分片形态「20 集合 ×150 张 = 3000」**2.95ms**。
⇒ **两三千张的领域成本是个位数毫秒**，无需为此做分页或虚拟化；
真正的规模风险在**平台层**（缩略图生成、SAF 遍历）而非领域层，属 D2b 范围。

⚠️ 探针**只打印不断言**，与既有 `SortPerfProbe.kt` 同类（该文件已跟踪）。
**它计入 `domain` 的 `@Test` 总数**（每次 +1），故「domain 247」这个读数依赖本文件在版本控制中。

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
| **P1** | 3 | 计划页/报告页缺筛选 | 一致性与可用性 | **✅ 2026-09-26 已修**（真机 11 项核对通过） |
| **P2** | 4 | B 侧策略与计划的因果距离 | 可用性 |
| **P2** | 5 | 危险动作触发路径长 | 第 2 项的派生 |
| **P2** | 10 | 深色下卡片是 material3 基线紫灰，与种子派生背景**色相错配** | **违反约束 7（配色未走语义派生）** |
| **P1** | 12 | 芯片行横向滚动把靠后的可操作控件**挤出视口**（7 处同源） | **违反约束 3/7 + §0 布局约定** | **✅ 2026-09-25 已修** |
| **P1** | 13 | 拖拽**无跟手浮层** + 命名页对手工调整**沉默**（§11 第 9/10 条） | 反馈缺失 / 操作逻辑不一致 | **✅ 2026-09-25 已修** |
| **P1** | 14 | 拖拽**无法跨视口** —— 30 张图时从第 1 张拖到第 30 张**物理上不可能** | **功能性缺陷（目标不可达）** | **✅ 2026-09-25 已修**（真机核对 2026-09-26 通过） |
| **P1** | 15 | 预览**取错图**（声明序 vs 页序）+ 预览往返**丢失所选集合** + 预览页缺改名入口 | **功能性缺陷（显示的不是点的那张）** | **✅ 2026-09-26 已修**（真机 5 项核对通过） |
| **P3** | 6/7 | 容器重复；MetadataPage **754 行** | 结构性；**规模结论已给**（领域成本个位数 ms，见 §7，无需分页）。**§7 已于 2026-10-18 真机实测：1.62 个视口 / 2 次 swipe 触底 ⇒「过长」不成立、分区导航不必做** |
| **P2** | 7b | **卷切换 chips 随滚动滚出视口**，换卷必须回顶（§7 实测派生） | 可用性（高频动作被埋） | **✅ 2026-10-18 已修**（`VolumeSelector` 移出 `LazyColumn` 常驻；同轮补修 40 卷规模回归 ⇒ 常驻限 5 个芯片 + 「全部 N 卷」sheet；**步骤 3 同源缺陷一并修** ⇒ 抽共享组件 `ui/components/VolumeChipRow.kt`，两页共用；真机 5 卷 / 40 卷双形态、步骤 2 + 3 双页面核对通过） |
| **P1** | 16 | 五项交互修订（排序页滚动 / 图钉尺寸 / 去对勾 / 芯片文案 / 列数控件），**其中 ⑤ 列数控件同日连走第二、三、四、五轮修订** | 一致性 / 可用性 | **✅ 2026-09-27 已修**（真机核对通过；第二轮见 §16b；第三轮「高亮改为整行背景填充」、第四轮「菜单收窄 48dp」、第五轮「数字居中」见 §16c） |

**建议顺序**：~~**P0 先做**~~（**✅ 2026-09-24 已完成**），
~~再 P1（固定终局动作）~~（**✅ 2026-09-25 已完成**）；~~长列表筛选~~（**✅ 2026-09-26 已完成**，见 §3），
P2/P3 视情况。第 2 项修复**真机核对已通过**（2026-09-25，与 §2b 同轮，用户授权后执行）；
离线全绿：282 + 229 + lint 零告警 + 6 组变异全捕获 —— 底栏是纯布局位移、不涉数据路径。
**第 15 节（2026-09-26）**离线全绿：`app 352` / `domain 247` + lint 零告警 + **16 组变异全捕获**，
真机 5 项逐条读数见 §15.8。
**第 16 节（2026-09-27）**离线全绿：`app 395` / `domain 247` + lint 零告警 + **16 组变异全捕获**，
真机 9 项逐条读数见 §16.7；**同节 ⑤ 列数控件于同日连走第二轮修订**（纯图标下拉 + 竖/横屏默认列数设置，见 §16b）**与第三轮修订**（下拉当前档由对勾改为整行背景填充，见 §16c）。
**第 16c 节（2026-09-27）**离线：`app 399` / `domain 247` + lint **`0 errors, 0 warnings, 4 hints`** + **15 组变异全捕获**（M0–M6 高亮轮、M1–M4 宽度轮、M5–M8 居中轮；其中高亮轮 M1 与居中轮 M8 分别证实并堵上了两个真实断言空洞），
真机 7 项逐条读数见 §16c.6；⚠️ 本轮 `:app:lintDebug` 的 1 条 error 属**工具链环境漂移**（Gradle 9.8.0 上游发布，报告对本轮改动 0 命中），
经用户裁决**升级到 Gradle 9.8.0**，已于**同日并入本轮落地**（lint 由 1 error 转全绿），详见 §16c.7。

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
> **第 3 节（P1 筛选推广）已于 2026-09-26 完成**：离线 `app 358` / `domain 247` + lint 零告警
> + **11 组变异全捕获**，真机 11 项逐条读数见 §3.5；
> 同轮经用户授权清掉死代码 `ui/components/ThumbCard.kt`（常量 `TAG_GRID_CARD` 已迁入
> `DraggableThumbCard.kt`，清理前后 APK **逐字节相同**）。
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

## 12. ✅ P1：芯片行横向滚动把靠后的可操作控件挤出视口（7 处同源）—— 2026-09-25 已修

**来源：第 13/15 条真机核对途中用 `dumpui.bat` 量坐标时发现（2026-09-25）**

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

**✅ 用户裁决（2026-09-25）：改成 `FlowRow` 自动换行**（未采纳"保留横向滚动只加提示"）。

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

## 13. ✅ 拖拽无跟手浮层 + 命名页对手工调整沉默（§11 第 9/10 条）—— 2026-09-25 已修

**来源：为 `HANGOFF.md` §11 第 9/10/14 条做准备时的代码核实（2026-09-25）**，
两条都是**用户裁决后实施**（裁决逐字：「维持松手落地，另补「跟手浮层」」/「摘要行加一行被动披露（推荐）」）。

### 13.1 第 9 条：拖拽期间没有任何东西跟着手指

**现象**：拖动卡片时**源卡只 `.alpha(0.35f)` 留在原地**，反馈全靠目标格高亮 ——
用户手里"没有东西"。`grep 'translation|graphicsLayer|offset|ghost|overlay'` 在
`ui/components/DragReorderGrid.kt` + `DraggableThumbCard.kt` 的命中只有 `pointerInRoot` 与 `onDrag: (Offset) -> Unit`。

**实现**：新建 `app/src/main/kotlin/com/pixfold/d1/ui/components/DragGhost.kt`
—— 以指针为中心（`translationX = pointerInRoot.x - containerInRoot.x - size.width / 2f`）、
`scaleX/Y = 1.05f`、`alpha = 0.92f`、`elevation = 8.dp`、
`containerColor = surfaceContainerHigh`；`DragGhostStyle { Card, Row }` 两分支对应网格/列表。
接入 `DragReorderGrid.kt`（+19/−1）与 `DragReorderList.kt`（+20/−1），各新增
`containerInRoot` 状态 + `Box(modifier.onGloballyPositioned { containerInRoot = it.boundsInRoot().topLeft })`。
**浮层只画不参与命中判定**（`clearAndSetSemantics { }`），故与"松手落地"不冲突。

⚠️ **`testTag` 必须挂在 `graphicsLayer` 节点的子节点上** —— 实测规律（`DragGhostBoundsTest` 钉死）：

| 变换挂在哪 | `onGloballyPositioned { boundsInRoot() }` | 语义树 `boundsInRoot` |
| --- | --- | --- |
| **本节点**：`padding`/`offset`/`graphicsLayer` | ✅ 反映 | ❌ **不反映** |
| **祖先节点**：`graphicsLayer`（平移与缩放） | ✅ 反映 | ✅ **反映** |

挂错位置 ⇒ 读到的 bounds 是**布局前**位置 ⇒ "浮层是否跟手"的断言**恒真、变异杀不死**。
（反向验证：`TransitionStyleTest` 能观察到根级 `scaleOut` 的缩放，正因为那个图层挂在页面容器的**祖先**上。）

### 13.2 第 10 条：第 2 步对人工调整完全沉默

**现象**：第 1 步**已有**同口径提示（`SortAndPreviewPage.kt` 的「已人工调整 N 处」+ 重置按钮），
第 2 步 `grep 'manual'` 在 `NamingPage.kt` / `WorkflowASteps.kt` **零命中** ——
而命名序号**跟随页序**，上一步拖过几张会改变这里的建议名，翻页后就被藏起来。

**实现**：`domain/.../naming/NamingSummary.kt` 的 `namingSummaryLine(scheme, sample, manualCount = 0)`
改返回 `List<Msg>`，`manualCount > 0` 时追加 `TextKey.NamingSummaryManual` 段；
`NamingPage.kt:165` 传入 `order.manualCount`（`PageOrderState.manualCount` = `manualIds.size`）。
摘要变为 `旅行照片_001.jpg · 2 个组件启用 · 含 3 处手工调整`；**归零时与改动前逐字一致**。

### 13.3 🚨 顺带刨出的真实缺陷：披露段的前导空格被 AAPT2 trim

拼接后读作 `…2 个组件启用· 3 处手工调整` —— **缺空格**。
这是本仓库在 `domain_list_separator` 上踩过的**同一个坑**。修法 = 存活写法 `\u0020`
（英文两档 + 中文 `other` 档都改）。

⚠️ **最关键教训**：我最初按"语言无关"纪律用 `TestText` 从**同一份资源**取期望值写断言 —
— 期望值与实际值**一起被 trim** ⇒ **断言恒真 ⇒ 四道闸门全绿**。
缺陷只在**变异验证打印实际报文**时才暴露（`Expected < · 1 manual adjustment>, actual <· 1 manual adjustment>`）。
⇒ 已在 `app/src/test/kotlin/com/pixfold/d1/ResourceFallbackTest.kt`（全仓唯一刻意写死中英字面量处）
加 2 条逐字断言：`manual_adjustment_segment_keeps_its_leading_space`（`zh-rCN`）与
`manual_adjustment_segment_in_the_english_default_keeps_its_leading_space`（不写 qualifiers）。
⚠️ 第二条**第一版写成 `qualifiers = "ja"` 而失败**：兜底取的是**文案**（默认槽位英文，对），
但复数**变体**按 locale 的 CLDR 规则选 —— 日语只有 `other`，故 quantity=1 仍取 `other`。
**默认 locale 是 en_US（⇒ `one` 形），故不写 qualifiers。**

### 13.4 验证

**四道闸门**：`app 326`（324 → +2 空格守卫）/ `domain 246` 全绿、lint `No issues found.`、
APK **34,210,577 B** ⚠️ **陈旧值** —— 该字节数取自未重打包的旧文件（Gradle 当时报
`:app:packageDebug UP-TO-DATE`），真值 **33,246,968 B**（见 §14.4）。

**变异验证 9 组全捕获、逃逸 0**（脚本 `app/build/mut_ghost_summary.py`，不跟踪）：
G1 位移置零 / **G2 tag 挪回图层节点**（反向守卫 tag 位置修正）/ G3 不渲染浮层 /
G4 不屏蔽语义（文件名重复）/ S1 接线断开 / S2 永不产出段 / S3 数量恒 0 /
**S4/S5 前导空格退回裸空格**（这两条正是 13.3 那两条字面量断言的杀伤力证明）。

**真机核对已通过（2026-09-25，PJX110 / Android 16 / 361dp×794dp，用户授权后执行）**：
- 第 9 条 —— **用户亲测**，逐字「**我自己试了，网格和列表效果都可以**」。
- 第 10 条 —— agent 读数：拖过 4 张后进第 2 步，摘要栏实测
  `旅行照片_001.jpg · 2 个组件启用 · 含 4 处手工调整`（节点 `[42,525][1124,595]`）；
  点「重置人工调整」（回到 `0 处`）后再进第 2 步，摘要变回 `旅行照片_001.jpg · 2 个组件启用`
  —— **披露段消失**，两个方向都对。

设备已复位（`/data/local/tmp` 内 `.png`/`.xml` 计数 = 0、`svc power stayon false`、`am force-stop`）。

---

## 14. ✅ 拖拽无法跨视口（§11 第 18 条）—— 2026-09-25 已修，2026-09-26 真机核对通过

**来源：为第 9 条排查拖拽实现时发现的代码缺陷（2026-09-25）**（登记为 `HANGOFF.md` §11 第 18 条）。
用户原话（逐字）：「拖拽不会带动网格/列表滚动，极端点可能会有首页被自动排序到末尾的情况所以需要拖拽调整」
—— ⇒ 这不是观感打磨，而是**排序回退手段不可用**：自动排序把首页挤到末尾后，用户没法定点拖回来。

### 14.1 ⚠️ 初判根因是错的（本节最重要的订正）

初记写的根因是「`DraggableThumbCard` / `ThumbRow` 的 `onDrag` 里 `change.consume()` 阻断了
LazyGrid/LazyColumn 接收手势」。**探针实测这个判断不成立** ——
`change.consume()` 只阻止 Lazy **自己**滚动，并不杀死手势。

**实测真因**：拖拽手势挂在**卡片**上（`pointerInput(item.id)`），而卡片是 Lazy 的**子项**。
一旦滚得够远，被拖的那张卡离开视口，`LazyGrid` / `LazyColumn` 把它**销毁**，
挂在它身上的 `pointerInput` 随之被取消，**`onDragEnd` 永不触发** ⇒ 跨视口拖拽物理上不可能。

> 探针读法：按触边事件数读活性（不松手，看浮层还在不在）——
> 4 次触边仍活、16 次触边后浮层消失 = 手势已死。
> 另有一项旁证：`repeat(10)` 触边时落点上报仍在，`repeat(30)` 时变成 `moved=[]`。

第二条根因（初记正确）：全组件**没有任何边缘自动滚动**
—— `grep 'scrollBy|animateScroll|translation|dragAmount'` 在 `ui/components/` 与
`SortAndPreviewPage.kt` 的命中只有回顶按钮的 `animateScrollToItem(0)`。

### 14.2 修法（两步缺一不可）

1. **把手势从卡片上移到不滚动的容器 `Box`**（`DragReorderGrid.kt` / `DragReorderList.kt`），
   卡片只负责显示；命中判定改由容器拿 `slotBounds` 反查（新增 `findContainedSlotAt`）。
   ⇒ 被拖的卡即使滚出视口被销毁，**手势仍然活在容器上**。
2. **拖动期间把滚动权交给程序**：Lazy 加 `userScrollEnabled = draggingIndex == null`，
   滚动改由 `EdgeAutoScroll.kt` 的 `edgeAutoScrollStep(...)` + `scrollBy`
   **按每个 `onDrag` 事件驱动**。

> **为什么必须"交出滚动权"**（探针实测）：不交时 Lazy 在 Main 阶段先接到事件、
> 立刻转成 `cancel`（手势长度 2）；交出后手势完整走完（`events=12`，收尾是 `end`）。
> 期间两种滚动机制互斥，故不会叠加成双倍速度。

`EdgeAutoScroll.kt`（新建，84 行）参数：边缘带宽 `EDGE_SCROLL_ZONE_DP = 72f`、
基础步长 `EDGE_SCROLL_BASE_STEP_DP = 14f`、连续触边加速上限 `EDGE_SCROLL_MAX_MULTIPLIER = 4`。
近上缘返回负值、近下缘返回正值，`步长 = 14dp × density × 深度 × (1 + burst).coerceIn(1, 4)`。

### 14.3 为什么是事件驱动而不是帧循环/定时器

帧循环在本仓库**离线不可驱动，且会挂死测试套**（实测）：
无条件 `while(true){ withFrameNanos{} }` ⇒ `setContent`/`waitForIdle` 永不返回
（`java.lang.InterruptedException at Builders.kt:94`）；`autoAdvance = true` ⇒
`OutOfMemoryError: Java heap space`；`advanceTimeByFrame()` 不恢复续体，
`advanceTimeBy` 也不驱动 `kotlinx.coroutines.delay`。

**代价（有意取舍）**：指针在边缘**完全静止**时不继续滚动。
真机手指会抖，叠加触边加速后长距离仍然可达。
归档 Flutter 原型同样用长按起拖「避免与滚动冲突」，同向。

### 14.4 自证

- `EdgeAutoScrollTest` **14 条**：8 条纯函数 + 6 条端到端
  （网格下缘 / 网格回滚到顶 / 列表下缘 / **滚动之后起拖应落在手指下的卡上** /
  **源卡滚出视口后浮层仍活** / **长按空白处不得起拖**）。
- 变异 **11 组全捕获、逃逸 0**（脚本 `pixfold-d1/app/build/mut_edge_scroll.py`，不跟踪）：
  G-A 自动滚动恒为 0 / G-B 边缘方向反 / G-C 算出步长但不接线 / G-D 网格侧不交出滚动权 /
  G-E 起拖兜底到最近格 / G-F 去掉加速 / G-G 去掉加速上限 / G-H 不清理过期格坐标 /
  G-I 列表侧不交出滚动权 / G-J 列表侧不接线 / G-K 浮层尺寸改为实时取。
- **首轮 G-H 与 G-K 是真逃逸** —— 既有测试**全部在"滚动之前"起拖**，
  故「滚动后起拖的身份」与「源卡滚出后的浮层尺寸」两条性质**无断言覆盖**；
  补上面对应的 2 条端到端测试后归零。
  另一条 G-E 首轮逃逸是**判据选错**：只断言"松手后的上报"会被
  「目标格 == 起始格 ⇒ 不提交」掩盖 ⇒ 改断言**浮层是否出现**才有杀伤力。
- **四道闸门**：`app 340` / `domain 246` 全绿、lint `No issues found.`、APK **33,246,968 B**。

### 14.5 真机核对（2026-09-26 通过）

用户亲测，逐字「**效果可以**」。

> ⚠️ 本轮核对期间发现**用户正在手机上操作** —— agent 注入的一次上滑返回了**列表态且换了集合**，
> 一次普通上滑做不到。⇒ **立即停止全部 adb 输入注入**，只做清理，避免与用户手势打架。

✅ **离线守卫的边界**：Robolectric 无真实布局，「滚动可达性」本身仍属"放得下"类结论
（见 §0 布局约定与 §12 同款教训）；**但本轮缺陷的因果机制是手势被取消、浮层消失**，
这一层是可离线的（`ghost survives after the source card scrolls out of view` 正是它的守卫）。

---

## 15. ✅ 预览取错图 / 预览往返丢失所选集合 / 预览页改名入口 —— 2026-09-26 已修，真机 5 项核对通过

**来源**：用户提问（逐字）「是否没必要保留列表视图了，然后网格视图的图钉按钮改成展开一个小的选项框，图钉和重命名？」
⇒ 排查「图钉能否藏进菜单」时**连带刨出三处真实缺陷**，并定下改名的落点。

> 该提问本身的结论（已答复，未改动代码）：**列表视图应保留**（验收第 1、4 项要求双视图与列表拖拽；
> 且列表是**唯一**显示 `relPath` + 体积的视图 —— 网格卡片只显示文件名，故 `ch01/page_01.png` 与
> `ch02/page_01.png` 在网格里**字节级相同**，`ViewMode.kt:3` 的 KDoc 早写明这一分工）；
> **图钉藏进菜单 = 净负收益**（图钉是二态控件，「入口可见 + 二态形状」正是走查反馈④ 的修法；
> 长按已被拖拽占用、短按进预览，没有空闲手势；361dp 屏上三列卡片仅约 110dp 宽）。
> 用户随后裁决（逐字）「**那就不做菜单，点开图片预览的是否加个重命名按钮吧**」⇒ 溢出菜单构想作废。

### 15.1 缺陷 1：预览取图用「集合声明序」，而网格点击传的是「当前页序」

`SortAndPreviewPage.kt:312 onClick = { index -> onOpenPreview(index) }`，这个 `index` 是
`items = order.order` 里的**页序下标**；而 `PixFoldApp.kt` 的预览目的地却用
`collections.firstOrNull { it.id == activeCollectionId }?.images`（**声明序**）取图。
两套顺序**只在恰好相同时才对**，于是「点第 1 张，出来的是另一张」。

**反证材料就在 mock 里**（无需真机即可复现）：
`MockDataTest.kt:65-80` 断言 `cn` 集合**页序首项 = `第一话`**，而
`MockData.kt:73-82 private fun chapters()` 的**声明序首项 = `第十二话`** ⇒ 逐项不同。

> ⚠️ **为什么此前一直没被发现**：既有 `AppNavigationTest.kt` 全用 `trip` 集合，
> 它的声明序**恰好等于**默认规则（`DirName` asc + `NaturalName` asc）的结果 ⇒ **巧合通过**。

**修法**：预览改从提升后的 `WorkflowAState` 取当前页序 ——
`val pageOrder = workflowAState.orders[activeCollection.id]?.order ?: activeCollection.images`。

### 15.2 缺陷 2：`onActiveCollectionChange` 声明了却从未被调用

`WorkflowASteps.kt:110` 接收 `onActiveCollectionChange`，但接线处只写成
`{ activeId = it }` —— 只更新组件**本地**状态并**不转发** ⇒
应用根里的 `activeCollectionId` 永远停在首个集合。

**修法**：改成双写 `{ activeId = it; onActiveCollectionChange(it) }`。

### 15.3 缺陷 3：预览往返后步骤容器被重建，所选集合退回首个

**由新增的端到端测试首次跑红才暴露**（`No node found that matches TestTag =
'proposal-name-cn-3' in scrollable container`，容器只报 30 行 = `trip` 集合）。
根因：预览是**根导航的兄弟目的地**，打开预览会让 `WorkflowARoute` **离开组合**，
其内部 `var activeId by remember { ... }` 随之销毁；关闭预览回来是**全新组合实例**，
没有种子就退回 `collections.first()`。表现「排序页切到章节 → 点开一张图 → 关闭后变回旅行照片」。

**修法**：`WorkflowASteps` 新增 `initialActiveCollectionId: String? = null`（带默认值，
否则 4 个测试文件 16 处调用点全断），应用根传 `initialActiveCollectionId = activeCollectionId`。

**同时把 `WorkflowAState` / `NamingState` 提升到应用根**（否则改名只在页内可见、
换页即丢）。范式沿用仓库既有的「**本地状态权威 + 初值取自上层 + 每次变更上报**」
（`SortAndPreviewPage.kt:135`），**刻意不用** `externalState ?: local` ——
那样一旦上层传值，就地编辑就永不显示（`NamingPage.kt:108-116` 记有同款教训）。

### 15.4 改名入口（用户裁决方向）

落在预览顶栏，`PreviewRename(val overrides, val finalNameOf, val onRename)` **打包成一个非空值**，
使「三个参数缺一个」不可表示；`ImagePreview(rename = null)` 是「本页不提供改名」的**唯一**表达。
`ImagePreview` 的新参数**必须带默认值**（`ImagePreviewTest.kt` 有 9 处 `setContent`）。

- 初值 = **当前最终名**（`finalNameOf`，覆盖优先于建议名）⇒ 用户是「改」而不是「重打一遍」。
- 空串 = 撤销覆盖，语义**由领域层 `setOverride` 拥有**（`if (name.isNullOrEmpty()) overrides - id`），
  UI 不重新实现（不 trim、不兜底）。
- 新增 6 个字符串键（中英同步）：`preview_rename` / `preview_rename_title` /
  `preview_rename_field` / `preview_rename_hint` / `preview_rename_save` / `preview_renamed`。

### 15.5 ⚠️ 本轮最重要的订正：一条**空洞测试**与一个**错误的技术判断**

我最初给预览的三个 `TextButton` 加了 `Modifier.heightIn(min = 48.dp)`，
注释写「M3 `TextButton` 默认最小高度只有 **40dp**,不达标」，并据此写了
`top bar buttons keep a 48dp minimum touch target`。

**变异实测推翻了它**：T1/T2/T3 三条「删掉 `heightIn`」变异**全部逃逸**（失败 0 条）。
探针实测（`TmpSizeProbe`，用完即删）：

| 组合 | 实测高度 |
| --- | --- |
| 裸 `TextButton` | **52px** |
| `TextButton` + `heightIn(min = 48.dp)` | **52px** |
| `TextButton` + `Modifier.size(height = 20.dp)` | **20px** |
| 裸 `Row` + `clickable` | **35px** |
| `Row` + `heightIn(min = 48.dp)` + `clickable` | **48px** |

⇒ **M3 的 `minimumInteractiveComponentSize` 早已把 `TextButton` 抬到 48dp+**，
40dp 只是**视觉**高度；`heightIn` 加不加布局尺寸完全一样 ⇒ **冗余修饰符，已删**。
（全仓无 `LocalMinimumInteractiveComponentSize` 覆盖，故该抬升确实生效。）

**教训**：`heightIn` 挂在哪很关键 —— 它挂在**裸 `Row` + `clickable`** 上是**承载行为**的
（35px → 48px），仓库现有 2 处（`SortAndPreviewPage.kt:400`、`SchemeEditor.kt:152`）
**必须保留**；挂在 M3 按钮上是**装饰**。

**订正后的守卫**：变异改成「**显式把按钮压到 20dp**」—— 这类改动才真会打破指标
（M3 的最小交互尺寸不再生效）。T1/T2 现在各自捕获（报错逐字
`preview-rename 触控高度应 ≥ 48dp,实际 20px(下限 48px)`），守卫**不再是空洞测试**。

### 15.6 同时刨出的旁证：`PinToggle` 的尺寸问题 —— ⚠️ **本节的结论当时是错的，已于 §16 订正**

同一探针实测 `PinToggle(size = 32.dp)` → **32px**、`size = 40.dp` → **40px**，当时据此写：
「`PinToggle` 用 `Modifier.size(size)` **显式**定尺寸 ⇒ **不享有** M3 的最小交互尺寸抬升，
所以 32/40dp **就是真实触摸目标**，确实低于 48dp 硬指标。」

**⚠️ 2026-09-27 订正：上述推断是错的。** 探针读到的是 `fetchSemanticsNode().size`，
它是 `Modifier.size(size)` 定下的**视觉**尺寸；而 M3 的 `minimumInteractiveComponentSize`
并不挂在尺寸修饰符上，是 `Modifier.clickable` 内部的 `getExtendedTouchPadding`
（`foundation/Clickable.kt:895-903`）在**测量阶段**把触控区撑到 48dp 的 ——
它不进语义节点的 `size` 字段，故探针看不见它。**真机 dump 直接证伪**：
九个图钉节点的 `bounds` 全部是 **168×168px = 48.0dp**（`PinToggle` 视觉方块另有 112px = 32dp）。
⇒ 32/40dp 是**视觉**尺寸，触摸目标一直是 **48dp**，**约束 7 从未被违反**。

`PinButtonTest.kt:113-119` 那条用例的问题仍在（用例名写 48dp 而断言 `>= 40`，
且自己传 `size = 40.dp` ⇒ 自说自话），已随 §16 重写。

### 15.7 自证

- **测试**：`ImagePreviewTest.kt` +8 条（含改名入口在有/无接线下的两态、初值、确认回传、
  取消丢弃、反馈行的三条状态、48dp 守卫）；`AppNavigationTest.kt` 新增 2 条端到端 ——
  ①预览改名 → 关闭 → 命名页看到新名（顺带钉住缺陷 1，页序首张 = `cn-3`）；
  ②命名页改名 → **经预览往返（销毁重建步骤容器）** → 名字仍在。
- **四道闸门**：`app 352` / `domain 247` 全绿、lint `0 errors, 0 warnings`、
  `assembleDebug` 成功 APK **33,299,288 B**。
- **变异 16 条全部捕获、0 逃逸、0 跳过**（脚本 `pixfold-d1/app/build/mut_preview_order_rename.py`，不跟踪）：
  P1 预览退回声明序 / P2 改名丢弃写回 / F1 集合回调只写本地 / **F2 命名步骤不上报** /
  R1–R7（不写回 / 写错张 / 初值用建议名 / 反馈行永不出现 / 反馈行恒显示 / 取消变提交 / 未接线也显示入口）/
  A1–A2（往返后不恢复集合）/ T1–T2（显式压到 20dp）/ D1 空串当新名字。
- ⚠️ **F2 首轮逃逸是真逃逸**：`NamingPage` 内部本就持有本地 `state`，漏上报在**单页内不可见**，
  必须「离开再回来」才暴露 ⇒ 据此补了上面那条往返测试，二轮归零。

### 15.8 真机核对（2026-09-26 通过，用户授权，PJX110 / Android 16 / 361dp×794dp）

逐条读数（`uiautomator dump`）：

| # | 核对项 | 实测 |
| --- | --- | --- |
| ① | 点第 1 张卡片，预览是不是那一张 | 卡片 `第一话.jpg` → 预览 `图片 第一话.jpg`、`第 1 / 16 页` ✅（修复前会显示声明序首张 `第十二话`） |
| ② | 排序页切集合 → 开预览 → 关闭后集合是否退回 | 仍 `共 16 张` + `第一话/第二话/第三话` ✅（修复前退回「旅行照片（30）」） |
| ③ | 预览里改名的反馈行 | `将重命名为 preview_edit.jpg` ✅ |
| ④ | 改名传播到命名页 | `新名称（已覆盖）` = `preview_edit.jpg`，摘要栏同步，`恢复建议值 cn-3` ✅ |
| ⑤ | 命名页改名 → 退回排序 → 开预览 → 再回命名页 | 两个改名（`preview_edit.jpg` / `naming_edit.jpg`）**都还在**、`警告 2` 保持 ✅ |

调试产物已清理（设备 `/data/local/tmp/t*.xml` 与本地 `artifacts/t*.xml` 均已删），
`svc power stayon` 已复位，应用已 force-stop。

---

## 16. ✅ P1：五项交互修订（排序页滚动 / 图钉尺寸 / 去对勾 / 芯片文案 / 列数控件）—— 2026-09-27 已修，真机核对通过

**来源：用户真机走查后的五条追加要求**（同一轮提出，逐字）：

| # | 用户原话（逐字） | 落点 |
| --- | --- | --- |
| ① | 「排序规则展开时无法下滑」 | A 步骤 1 的排序规则编辑器 |
| ② | 「PinToggle48太大了，32差不多了」 | `PinToggle` 图钉 |
| ③ | 「网格/列表切换按钮选中有高亮所以不需要"✔"」 | `ModeToggle` |
| ④ | 「集合按钮内的字没有居中，集合名（数量）后面还有一块空格的感觉」 | 集合芯片文案 |
| ⑤ | 「网格视图加个列数按钮（默认选自适应）」 | 新增列数控件 |

### 16.1 ① 展开后无法下滑：编辑器占满滚动容器（**初判根因与真相都在此**）

`RuleSummaryBar` 折叠时，`SortRuleEditor` 原先是 `Column` 里 `weight(1f)` 网格**之外**的固定子项。
折叠态编辑器只有折叠摘要行，网格拿到全部剩余高度；**展开态编辑器实测约 2900px，
而屏高只有 2780px** ⇒ 编辑器吃光固定区、`weight(1f)` 网格被压到近 0 高度，
`LazyGrid` 的滚动区间随之消失 ⇒ **页面看起来"无法下滑"**。

**类 KDoc 里原先的估算被真机证伪**（原文逐字「编辑器高度有界（≈140dp → 最多 ≈220dp）」）——
四级规则时编辑器远不止 220dp。该 KDoc 已标注"已被真机证伪"。

**用户裁决**（逐字）：**A「编辑器并进列表的滚动区（推荐）」**。
拒绝 B（给编辑器设高度上限）与 C（整页合成一个滚动区）。

**修法**：把编辑器变成网格/列表**同一个 `LazyColumn`/`LazyGrid` 的 header item**
（`DragReorderGrid` / `DragReorderList` 新增 `header: (@Composable () -> Unit)? = null` 参数，
`header != null` 时在 `itemsIndexed` 之前插 `item(key = DRAG_HEADER_ITEM_KEY, span = { GridItemSpan(maxLineSpan) })`）。
`RuleSummaryBar` **保持常驻在滚动容器之外**，仍作折叠开关。
**代价（已在裁决描述里写明并接受）**：编辑器会随列表滚出视口 —— 与 P0 命名页同款行为。

**⚠️ 前插锚定陷阱（本轮的技术核心）**：往视口**上方**插入内容时，
`LazyList` 默认按**像素偏移**保持滚动位置 ⇒ 画面毫无反应（用户看到的是"点了没反应"）。
LazyList 实为按 **key** 解析 `updateScrollPositionIfTheFirstItemWasMoved`，
故占位项必须带**稳定 key**。折叠时**不移除** `scrollSignal`（否则画面会被硬拽回顶部，
比"没反应"更糟）；**只在展开时** `scrollSignal++` 回顶。
> ⚠️ header 绝不能并进 `items`/`itemsIndexed` —— 会让 `onClick(index)` 与 `slotBounds[index]` 整体错位一格。

### 16.2 ② 图钉 32dp：**本条订正了 §15.6 的错误结论**

`ThumbRow.kt` 原先给 `PinToggle` 多传了 `size = 40.dp`，**覆盖了组件默认值 32dp** ⇒
网格与列表两个调用点（`ThumbRow.kt`、`DraggableThumbCard.kt`）都不一致。
已删该实参，两处统一走组件默认 **`PinToggle(size: Dp = 32.dp)`**。

**§15.6 当时写「32/40dp 就是真实触摸目标，确实低于 48dp 硬指标」—— 这是错的**（已在该节标注订正）：

- 探针读的 `fetchSemanticsNode().size` 是 `Modifier.size(size)` 定下的**视觉**尺寸；
- M3 的 `minimumInteractiveComponentSize`（默认 48dp）**不是**挂在尺寸修饰符上，
  而是 `Modifier.clickable` 内部的 `getExtendedTouchPadding`
  （`foundation/Clickable.kt:895-903`）在**测量阶段**把触控区撑到 48dp，它不进语义节点的 `size` 字段；
- **真机 dump 直接证伪**：九个图钉节点的 `bounds` 全部 **168×168px = 48.0dp**（视觉方块另有 112px = 32dp）。

⇒ **视觉 32dp 与触控 48dp 共存，约束 7 从未被违反**。用户「32 差不多了」的裁决**不违反 a11y 硬指标**，
**勿改回 40dp**（那条实参是纯粹的不一致，不是尺寸需求）。

### 16.3 ③ 去对勾：`SegmentedButton` 的 26dp 图标槽位

`SegmentedButton.kt:221` 的 `icon` 参数默认值是 `{ SegmentedButtonDefaults.Icon(selected) }`。
即便不画图标，`SegmentedButtonContentMeasurePolicy`（`:413-459`）仍**硬留
`IconSize 18dp + IconSpacing 8dp = 26dp` 的槽位**，并在 `iconWidth == 0` 时
用 `offsetX = -13dp` 把标签往左挪回半个槽位 ⇒ **标签始终被右推 13dp**。

**修法**：两处 `SegmentedButton` 显式传 `icon = {}` ⇒ 槽位宽度归零、`offsetX = -13dp` 生效 ⇒ 标签居中，
**总宽不变**（该 measure policy 只挪标签，不改容器宽）。只暴露 `contentPadding`，真正移除槽位须弃用 `SegmentedButton`。

**⚠️ 本项在离线不可观测**：`Icons.Filled.Check` 的 `contentDescription = null` ⇒ 语义树无痕；
且 `ModeToggleProbeTest` 实测 `onAllNodesWithText("Grid")` 拿到的节点 rectangle **与按钮完全相同**
（Robolectric 不做真实文字测量）⇒ 标签居中与否也无法离线观测。
**故守卫写成源码结构断言**（`functionBody()` 抽出函数体后断言 `contains("icon = {}")`
且 `!contains("SegmentedButtonDefaults.Icon(")`，且必须先 `decommented()` 剥注释）。

**真机取得了决定性像素证据**（同一节点同坐标对照，按钮区 `x42-266`，中心 = 154）：

| | `text='网格'` 子节点 | 中心 | 与按钮中心差 |
| --- | --- | --- | --- |
| 修补前（09-26 筛选轮 `artifacts/a_step1.xml`） | x=150-250 | **200** | **+46px ≈ 13.1dp** |
| 本轮（`artifacts/step1_reexp.xml`） | x=105-205 | **155** | **+1px** |

⇒ 46px ≈ 13.1dp 的右推**正是那个 26dp 槽位的一半**，与源码推导逐像素吻合。
按钮总宽仍 224px = 64dp（与改前相同）⇒ 去对勾未造成布局位移。

### 16.4 ④ 集合芯片"文字不居中"：**根因是全角括号，不是内衬**

真机像素实测（`artifacts/vfy1.xml` + 自写 PNG 解码器）：

| | 容器 x 范围 | 宽 | 文字 x 范围 | 墨迹中心 | 容器中心 | 差 |
| --- | --- | --- | --- | --- | --- | --- |
| 修补前 `旅游照片（30）` | 42-508 | **466px** | — | 偏左 | — | — |
| 本轮 `旅行照片 (30)` | 42-456 | **414px** | 98-400 | 247 | 249 | **−2px ≈ 0.6dp** |

**code-level 根因**：`FilterChip` → 私有 `SelectableChip` → `AnimatingChipContent`（`Chip.kt:2020`），
`ChipLayoutMeasurePolicy`（`:2281`）算 `width = leadingIconWidth + labelPlaceable.width + trailingIconWidth`，
`widthOrZero` 对 null 图标返回 0 ⇒ **不为缺席的图标留位**（与 `SegmentedButton` 不同）；
真实内衬是**对称**的 16dp/侧（`FilterChipPadding` 8dp `:2961` + `HorizontalElementsPadding` 8dp `:2955`）。
真机逐点位核对：文字节点左右各 **56px = 16dp** 内衬 ⇒ **内衬完全对称，不是 padding 的锅**。
真正原因是**全角 `（）` 的字形 advance box 是 1em**，墨迹却在字形中心 ⇒
括号内侧留出半个字宽的空档，右侧看起来"多了一块空格"。

**修法 = 纯文案改动**（没有任何公开 API 能改这个内衬）：`a_collection_chip` 由
`%1$s（%2$d）` 改为 **`%1$s (%2$d)`**；同类问题一并修 `a_reset_manual` 为
**`重置人工调整 (%1$d 处)`**。收益实测：芯片**收窄 52px ≈ 14.9dp**（正是两个全角字形省下的宽度）。

**⚠️ 断言写法有坑**（两条都踩过）：①**必须只看那一行** —— 全文件断言会被 zh 资源别处
**正当**的全角括号满足（如 `a_sort_levels` =「排序规则（%1$d/%2$d 级）」）；
②**必须先剥 XML 注释** —— 注释里就写着「全角括号」字样，不剥会被自己的说明满足。

### 16.5 ⑤ 列数控件（用户裁决 A：数字按钮组 自适应/2/3/4）

**用户裁决**（逐字）**A「数字按钮组：自适应 2 3 4（推荐）」**。

**放置位置经真机几何二次裁决**：原措辞是"贴切换行"，但真机实测模式行
（`网格 视图` x42-266 + `列表 视图` x263-487 = 448px）右侧被"本组独立"开关
（x851-1226）占住，**只剩 x487→851 = 364px ≈ 104dp 空隙，而四个按钮需要约 196–232dp**
⇒ 用户重新裁决**A「单独一行，紧贴切换行下方（推荐）」**。实测一行高约 168px(48dp 触控)/140px 视觉，
**仅网格模式渲染**、左对齐。拒绝 B（置于排序摘要栏下方）与 C（塞进切换行）。

`DragReorderGrid` 的 `columns` 参数类型由 `Int` 改为 **`Int? = 3`**（保持 9 个既有测试调用点的默认行为不变）：
`columns == null` ⇒ `GridCells.Adaptive(ADAPTIVE_COLUMN_MIN_WIDTH = 110.dp)`，否则 `GridCells.Fixed(columns)`。

**状态放页面本地**（`var columns by remember { mutableStateOf<Int?>(null) }`），**刻意不进 `WorkflowAState`** ——
它是**视图偏好**而非领域事实，与紧邻的 `mode` 同类。**默认 null（自适应）**。

**⚠️ 一处观感变化必须披露**：`Adaptive(110.dp)` 在 **320dp 宽的测试默认视口下落到 2 列**
（原 `Fixed(3)` 是 3 列）⇒ `SortAndPreviewPageTest` 的"中文数字排序"用例随之从"6 张排 2 行"变成"6 张排 3 行"
而超出视口，被 `LazyGrid` 回收成 4 张。**这不是排序缺陷**，该用例已改为 `renderedCardNames()`
逐屏 `ScrollToIndex` 收集（每屏按 `boundsInRoot` 的 `(top, left)` 排序、`LinkedHashSet` 保序去重）。
**361dp 真机上自适应仍是 3 列**（345dp 可用 ÷ 110 = 3.13），与改前**逐像素相同**。

### 16.6 自证

- **四道闸门**：`app 373`（+21）/ `domain 247` 全绿、lint **0 errors / 0 warnings**（4 条既有
  `AutoboxingStateCreation` Hint 不计）、`assembleDebug` 成功 APK **33,334,244 B**。
- **变异 23 组全部捕获、0 逃逸、0 跳过**（脚本 `app/build/mut_polish.py`，不跟踪）——
  ①6 组（编辑器退回容器外 / 网格不接线 / 列表不接线 / 展开不回顶 / 折叠也回顶 / header 丢 span / 列表 header 空实现）、
  ③3 组（两处恢复对勾 + 反向）、④3 组（zh 两处退全角 + en 与 zh 不一致）、
  ⑤8 组（默认列数 3 / `Adaptive` 退 `Fixed` / 110 改 130 与 80 / 列表模式也显示 / 档位去掉 4 /
  onClick 空实现 / selected 恒 true）、②2 组（`ThumbRow` 退回 40dp / 默认值改 48dp）。
- **⚠️ 两条变异脚本教训**：
  ①**编译失败不是"捕获"** —— M9 首轮 `NO-XML`，根因是我按 `selected = mode == option` 臆造了
  `SegmentedButtonDefaults.Icon(selected)`，而该 lambda 里根本没有 `selected` 这个名字
  （只有 `index`/`option`/`isGrid`），逐字报 `Unresolved reference 'selected'.`。
  改写成 `Icon(mode == option)` 后才真被断言抓住。
  ②**还原必须整文件快照，不能"把新串换回旧串"** —— 多个变异的 `new` 串在文件**别处**也会出现
  （如 `scrollSignal++` 有 3 处），按 `replace(new, old, 1)` 会改错那一处，
  上一轮的 v26 变异就是这样把备份**污染成假阴性**。脚本另加**干跑锚点校验**
  （逐个变异断言 `count(old) == 1`，不跑 Gradle），输出 `anchors ok | mutants=23`。

### 16.7 真机核对（2026-09-27 通过，用户授权，PJX110 / Android 16 / 361dp×794dp）

| # | 核对项 | 实测读数 |
| --- | --- | --- |
| ① | 展开后可一路滑到底 | `swipe` 后可见 `IMG_20260701_007…015`、`content-desc="第1级` 节点数 **0**（编辑器已滚出视口）、`回到顶部` 出现 ✅（修补前物理不可能） |
| ① | 折叠不把用户拽回顶 | 在深处折叠 ⇒ 摘要行可见而其下仍是 `007/008/009`，滚动位置**原样保留** ✅ |
| ① | 重新展开回顶 | 再点 ⇒ 回到展开态顶部（`添加排序级`/`排序规则（2/4 级）`/第1级芯片可见） ✅ |
| ② | 图钉触控框 | 九个节点 `bounds` 全 **168×168px = 48dp** ✅（视觉 32dp 由离线单测断言） |
| ③ | 去对勾 | 标签中心由 200 → **155**（按钮中心 154）；按钮总宽不变 ✅；对勾本体属观感项（`cd = null`），像素复核 `网格` delta=**+0**、`列表` delta=**−1** |
| ④ | 芯片墨迹居中 | `旅行照片 (30)` 容器中心 249、墨迹中心 247 ⇒ **delta −2px**；容器由 466px 收窄到 **414px** ✅ |
| ⑤ | 列数控件位置/交互 | 紧贴模式行下方、单独一行、左对齐；点 `2 列` 首行图钉 **2** 个、点 `4 列` **4** 个、点回 `自适应` **3** 个 ✅；列表模式该行**不出现** ✅ |
| ⑤ | 默认自适应在 361dp | 三列卡片 `x42-417`/`x445-820`/`x848-1222`，与修补前 `a_step1.xml` **逐像素相同** ✅ |

**⚠️ 上表 ⑤ 的两行已被 §16b 取代**：列数控件当日就走了第二轮修订（从「单独一行 4 个数字按钮 + 自适应」改为「模式行内纯图标 + 下拉 2–5」，默认自适应由 **3 列变 4 列**）。下文两条观测限制仍成立。

**⚠️ 两条真机观测限制**：
①**`selected="true"` 在 uiautomator dump 里读不到** —— Compose 的 `SemanticsProperties.Selected`
未被映射（整个 dump 里唯一的 `selected="true"` 是步骤页签）。
⇒「默认选中自适应」**只能靠离线单测**（`column_control_defaults_to_adaptive` 里的 `isSelected(TAG_COLUMN_ADAPTIVE)`）守护，
真机只能目视高亮色。
②③ 的「对勾消失」本身与 ④ 的「墨迹居中」属观感项，dump 只能给坐标，曾建议用户目视确认。

**操作教训**：`input tap` 后**必须重新 dump 确认状态真的变了**再解读 ——
本轮首次点折叠开关未生效，接连两份 dump（`step1_exp.xml`/`step1_exp2.xml`）都是未生效的中间态，
差点被当成"展开后没变化"的证据。

调试产物已清理（设备 `/data/local/tmp` 的 `.xml`/`.png` 计数归 **0**），
`svc power stayon false` 已复位（`mStayOn=false`），应用已 force-stop，`adb disconnect` 已断开。

---

## 16b. ✅ P1 续：列数控件第二轮（模式行内图标下拉 + 竖屏/横屏默认列数设置）—— 2026-09-27 已修，真机核对通过

**这是 §16 ⑤ 的第二轮修订。** 上一轮交付的形态（4 个数字按钮＋「自适应」按钮**单独占一行**、左对齐）
被用户当场否决：一是「自适应被拆成 2 行导致高度和 2/3/4 不一样很难看」，
二是模式行右侧本就有空位却空着。所以 §16.7 表里 ⑤ 的两行读数
（「紧贴模式行下方、**单独一行**」、「默认自适应三列」）**已被本节取代**，只作历史记录。

### 16b.1 用户原话（逐字，两条）

| # | 用户原话（逐字） | 落点 |
| --- | --- | --- |
| ⑤-1 | 「自适应被拆成2行导致高度和2/3/4不一样很难看，不能改成图标（不要直接文字显示，长按图标显示出这是列数的说明）点击下拉列表选择，去除自适应选项。在设置里加上列数的默认列数设置，这里的默认列数的默认选项是根据机器分辨率自适应的，顺带一提我觉得我的手机4列作为默认比较好看」 | 控件形态 + 默认值来源 + 新增设置项 |
| ⑤-2 | 「然后因为只有一个图标所以可以放在网格/列表的切换按钮右边而不用单独占用一行」 | 位置 |

### 16b.2 九条裁决（用户，勿重推）

| # | 主题 | 裁决 |
| --- | --- | --- |
| 1 | `icon_form` | 纯图标；当前值**只在下拉列表**里显示；长按图标出提示说明「这是列数」 |
| 2 | `col_options` | 工具栏下拉 = **2 / 3 / 4 / 5**，**无**「自适应」项 |
| 3 | `settings_relation` | 设置 = 默认值；工具栏 = **本页临时覆盖，离开页面即失效** |
| 4 | `adaptive_target` | 调窄 `ADAPTIVE_COLUMN_MIN_WIDTH`，使 361dp 上自适应 = **4 列** |
| 5 | `commit_timing` | 先提交上一轮，再开新轮（已做 `e93d759`） |
| 6 | `toolbar_bridge` | 设置里无「自适应」项，**算出的自适应值就是默认项**；排序页点图标展开下拉并高亮该项；设置里竖屏/横屏分**两项**，默认都是自适应值；排序页按屏幕方向**实时**取用 |
| 7 | `resolve_out_of_range` | 选项 A：超范围的当前值**临时并入**下拉列表，保证当前值永远可见可选 |
| 8 | `col_option_range` | 竖屏 1–5 / 横屏 2–9（**括号写明「显示 2–5 / 2–9」**）⇒ 实现取**下界 2**，上界随方向 |
| 9 | `adapt_recovery` | 新增「跟随自适应」选项（显示算出的数）；**部分推翻第 6 条** ⇒ 模型改 `Int?`（`null` = 跟随自适应），设置页须有显式「跟随自适应」项 |

⚠️ **裁决 8 的取舍**：裁决原文写「竖屏 1–5」，但括号里又写「显示 2–5」。
按**更具体的显示范围**执行（`MIN_COLUMNS = 2`）；1 列仍可经自适应算出后被并入下拉（裁决 7）。

### 16b.3 修法：一份共享算式 + 三层优先级

新建 `pixfold-d1/app/src/main/kotlin/com/pixfold/d1/ui/components/ColumnOptions.kt` **作列数算式的唯一事实来源**，
避免设置页与排序页各算一遍发生漂移（§12「7 处同源」的教训）：

- `const val PORTRAIT_MAX_COLUMNS = 5` / `const val LANDSCAPE_MAX_COLUMNS = 9` / `const val MIN_COLUMNS = 2`
- `fun columnOptions(maxColumns: Int, current: Int): List<Int>` —— 基础列表 `(MIN_COLUMNS..maxColumns)`；
  当前值不在基础列表内时**并入并重排**（裁决 7）。
- `@Composable fun isPortraitOrientation(): Boolean`（`UNDEFINED` 当竖屏）、`orientationWidth(...)`（竖屏取 `min(w,h)`、横屏取 `max(w,h)`）

星级优先级就在排序页一行：
`val columns = columnOverride ?: settingColumns ?: adaptiveColumns`
—— **工具栏临时覆盖 > 设置默认值 > 自适应**。
`columnOverride` 用 `remember`（**刻意不用 `rememberSaveable`**），因为裁决 3 的要求就是「离开页面即失效」。

设置侧：`PixFoldSettings` 新增 `portraitDefaultColumns: Int? = null` / `landscapeDefaultColumns: Int? = null`
（**`domain/` 是纯 JVM 模块，编译期不知屏宽，故默认必须是 `null`**）；
`SharedPrefsSettingsStore` 新增 `readNullableColumns`/`putNullableColumns` —— **`null` 直接 `remove(key)`，不写哨兵值**
（写 `0` 会在旧版本里读成合法列数）。

### 16b.4 ☈ 本轮最重要的技术事实：下拉里的「选中态」在语义树里不存在

`DropdownMenuItem` 在 material3 1.4.0 里**不设 `selected`/`selectable`/`toggleable` 语义**
（源码 `Menu.kt` 与 `AndroidMenu.android.kt` 全文 grep 零命中），它的 modifier 链只有
`clickable(...).fillMaxWidth().size(...).padding(contentPadding)`。⇒

1. 高亮当前档**只能靠 `leadingIcon`**（故本轮新增 `res/drawable/ic_check.xml`）。
2. 离线断言**读不到 `isSelected`**，只能查**对勾图标自己的 `testTag`**，且必须
   **`useUnmergedTree = true`** —— 菜单项的 `clickable` 会把子节点合并进去。
3. 对勾**只在菜单展开期间存在于语义树** ⇒ 本轮首次跑闸门时四个用例因在**菜单未展开**时调
   `hasChecked()` 而**恒为 false**。修法 = 新增断言 helper `assertAnnouncedColumns(n, message)`，
   改读**按钮自己的 `contentDescription`**（`a_cols_cd` = 「N 列」）—— 它在未展开时也可读。
   同时保留一处 `openColumnMenu()` + `hasChecked` 用来钉住「对勾真的转移了」。
   反证：那两个先调 `openColumnMenu()` 的用例一直是绿的。

### 16b.5 自证

- **静态侧**：`pixfold-d1/app/src/test/kotlin/com/pixfold/d1/ui/components/ColumnOptionsTest.kt`（纯 JVM，8 例）直接钉住 `4`@361dp、`4`@400dp、`3`@320dp、`9`@794dp、
  `336dp` 临界点（`16 + 80*4`）与 `335dp`、不低于 1 列的保底、超范围当前值合并。
- **持久化侧**：`pixfold-d1/app/src/test/kotlin/com/pixfold/d1/data/SharedPrefsSettingsStoreTest.kt`（**本仓第一个直测该 Store 的用例**，6 例）：
  往返、两方向各自独立的 key、「跟随自适应」以**缺失 key** 表示、缺失 key 读作跟随自适应、
  手工写入非法值（0/-1/-9）回退跟随自适应、保存「跟随自适应」会清掉旧固定值。
- **四道闸门**：`app 395`（373 → **+22**）/ `domain 247` 全绿、lint **0 errors / 0 warnings**、APK **33,369,264 B**
  （跑前已清 `app/build/test-results/testDebugUnitTest`、`domain/build/test-results/test`、`app/build/outputs/apk/debug/app-debug.apk`）。
- **变异 16 组全部捕获、0 逃逸、0 跳过**（脚本 `pixfold-d1/app/build/mutate_round.py`，不跟踪）：

| 编号 | 变异 | 红了 |
| --- | --- | --- |
| M1 | `ADAPTIVE_COLUMN_MIN_WIDTH` 80 → 110dp | 7 |
| M2 | `MIN_COLUMNS = 2` → `1` | 5 |
| M3 | `columnOptions` 当前值合并分支→ `return base` | 4 |
| M4 | 竖/横默认列数两字段取反 | 2 |
| M5 | `columns` 三层优先级去掉 `settingColumns` | 4 |
| M6 | `leadingIcon` 判定→ `if (false)` | 3 |
| M7 | `putNullableColumns` 的 `remove` 分支取反 | 2 |
| M8 | 长按提示文案改成 `a_cols_cd` | 1 |
| M9 | `PORTRAIT_MAX_COLUMNS` 5 → 9 | 4 |
| M10 | `readNullableColumns` 的 `takeIf { it > 0 }` → `!= 0` | 1 |
| M11 | 设置页自适应文案参数改 `1` | 1 |
| M12 | 设置页两行都用竖屏宽算 | **首轮逃逸 ❌** |
| M13 | 列数按钮在列表模式也显示 | 1 |
| M14 | 选完不关菜单（去 `expanded = false`） | 2 |
| M15 | 按钮去 `contentDescription` | 5 |
| M16 | `orientationWidthDp` 竖/横取反 | 8 |

- **⚠️ 一条先存活后补杀（M12）**：原设置页用例只钉了**竖屏行**的数字，
  「两行都按竖屏宽度算」完全溜过闸门。补写 `each orientation shows its own adaptive number`
  （同时断言竖屏 `4` 与横屏 `24`，取 `w400dp-h2000dp`）后杀死 ⇒ **一条真实的断言空洞**。
  ⚠️ 补写时还踩了一坑：我原以为横屏的数字只在展开后出现，而**两行都常驻显示自己算出的数**。
- **⚠️ M8 有杀伤力** ⇒ 长按提示的 Robolectric 断言稳定，**不**按 §7.4 撤掉 `long_press_shows_the_column_count_label`。

### 16b.6 真机核对（2026-09-27 通过，用户授权，PJX110 / Android 16 / 361dp×794dp）

| # | 核对项 | 实测读数 |
| --- | --- | --- |
| a | 纯图标 | 按钮 `[487,924][655,1092]`、`content-desc='4 列'`、**`text=''`** ⇒ 无任何可见文字 ✅ |
| a | 位置（紧贴模式切换右侧、同一行） | 模式切换 `[42,938][266,1078]` + `[263,938][487,1078]`，列数按钮 `x` 从 **487** 开始 ⇒ 紧贴其右 ✅ |
| a | 自适应目标 | 361dp 上自适应 = **4 列**（播报 `4 列`）✅ |
| b | 下拉列表 | 四项 `2 列`/`3 列`/`4 列`/`5 列`，`text` = 2/3/4/5 ⇒ 恰为 **2/3/4/5**、**无「自适应」项**、竖屏上限 **5** ✅ |
| b | 当前档高亮 | 2/3/5 的数字文字 `x=529`，**`4 列` 的 `x=655`** —— 被左侧对勾挤开 **126px = 36dp**（对勾是 `DropdownMenuItem` 主动让位，`selected` 语义不存在）✅ |
| c | 选 2 列：卡片变宽 + 播报同步 | 按钮 `content-desc='2 列'`；卡片 `[42,1306][618,1980]` 宽 **576px = 164.6dp**（4 列时 274px = 78.3dp）✅ |
| d | 长按提示「列数」 | 提示框 `y=826..909`（高 84px）、`x=499..641`，**正压在按钮 `y=924..1092` 上方**（与 `TooltipAnchorPosition.Above` 一致）；框内暗像素包围盒 `x=529..641`、`y=848..901`，可辨认出「列数」✅ |
| e | 工具栏覆盖**不**持久 | 2 列 → `keyevent 4` 回首页 → 重进 A ⇒ 播报 **`4 列`**（回到自适应）✅ |
| f | 设置两行各显示本方向自适应值 | `竖屏默认列数` = **`跟随自适应（4）`**；`横屏默认列数` = **`跟随自适应（9）`**；说明文字「作用于网格视图；排序页的列数按钮可在本次进入时临时覆盖。」✅ |
| g | 设置播种 + 横屏取横屏值 | 竖屏会话选 `2 列` ⇒ 设置行变 `2 列`、横屏仍 `跟随自适应（9）`；重进 A ⇒ **`2 列`**；转横屏（ROTATION_90）⇒ **`9 列`**（忽略竖屏的 2）；横屏下拉 **2–9**、对勾在 **9**（`9 列` 的数字 `x=795` 而 3–8 列全为 `x=669`）✅ |
| h | 转回竖屏 + 清空设置 | 转回 ROTATION_0 ⇒ 播报 **`2 列`**（取竖屏设置值）；选 `跟随自适应（4）` ⇒ 两行都回到自适应；重进 A ⇒ **`4 列`** ✅ |
| i/j | 收尾清理 | `/data/local/tmp` 的 `.xml`/`.png` 计数 **25 → 0**；`accelerometer_rotation=1`、`user_rotation=0`、`mStayOn=false`、应用已 force-stop ✅ |

⚠️ **本轮新增的操作教训（值得固定下来）**：长按提示 **1.5s 后自动消失**，
而 `pixfold-d1/dumpui.bat` 的 `uiautomator dump` 往返耗时远超 1.5s ⇒ **两次都只拍到空白**；
`input swipe` 也触发不了长按。正解 = **把动作与取证串进设备侧的同一个 `adb shell`**：

```
adb shell "input draganddrop 571 1008 571 1008 700; screencap -p /data/local/tmp/tip.png"
```

再与基线截图做**逐像素比对**（本机 `python3` 无 PIL，用 `zlib` + PNG 逐行反滤波手写解码器）。
—— 同样的道理适用于任何「有纯自动隐藏计时」的瞬态 UI。

调试产物已清理，`svc power stayon false` 已复位，应用已 force-stop。

**已提交** `f6dfaa9`(24 files,+1440/−168,**直接提交到 `dev`、无独立分支、未推送**)。

---

## 16c. ✅ P1 续：列数控件第三轮（下拉「当前档位」**整行背景填充** + 行宽收窄 **48dp** + 数字**居中**）—— 2026-09-27 已修，真机核对通过（7 项 + 后续两轮各 1 项）

### 16c.1 起因与用户裁决

- `User said (m22963)`（逐字）：「列数下拉要的效果是高亮而不是打勾」。
- **根因**（第二轮真机读数可证）：`DropdownMenuItem` 的 `leadingIcon` 传 `null` 时不占位、非 `null` 时左侧让出图标槽 ⇒ **两态宽度不同**。第二轮实测：当前档位数字被对勾挤到 `x=655`，其余档位在 `x=529`（偏移 126px = 36dp）。宽度不一致正是「不像高亮」的观感来源 —— **对勾只是高亮的一种实现方式，不是高亮本身**。
- **形态裁决**：`ask_user_question` 给四选项（仅文字色 / **背景填充色** / 边框 / 左侧色条），用户选 (m23013)（逐字选项标签）**「2背景填充色」** ⇒ **整行背景填充**。

### 16c.2 material3 1.4.0 源码级依据（一手，解包自 `pixfold-d1/app/build/m3src/`）

| 事实 | 源码位置 / 原文 |
| --- | --- |
| 调用方 `modifier` **在链首** ⇒ `.background()` 铺满整行（含内部 12dp 横向内边距） | `commonMain/androidx/compose/material3/Menu.kt:419` `DropdownMenuItemContent(...)`：`modifier.clickable(...).fillMaxWidth().sizeIn(minWidth = DropdownMenuItemDefaultMinWidth, maxWidth = DropdownMenuItemDefaultMaxWidth, minHeight = MenuListItemContainerHeight).padding(contentPadding)` |
| `MenuItemColors` **没有 selected 变体**（只有 textColor / leadingIconColor / trailingIconColor 及各自 disabled 版） | `Menu.kt:274-290` ⇒ 选中底色**不会**由 `colors` 给出，只能在 `modifier` 上自己填 |
| `Menu.kt` / `AndroidMenu.android.kt` 内 `selected` / `selectable` / `toggleable` 语义 **grep 零命中** | ⇒ 测试读不到 `isSelected`，`selected` 语义必须自己显式设置 |
| 尺寸 token | `Menu.kt:524-528`：`MenuListItemContainerHeight = 48.dp`、`DropdownMenuItemHorizontalPadding = 12.dp`、`DropdownMenuItemDefaultMinWidth = 112.dp`、`DropdownMenuItemDefaultMaxWidth = 280.dp`；`Menu.kt:526 DropdownMenuVerticalPadding = 8.dp` |
| 外层 `Surface` 已 `clip` 圆角 + 内层 `Column` 有 8dp 纵向内边距 ⇒ 首尾条目够不着圆角 | `Surface.kt:475` `clip(shape)`；⇒ **`.background()` 只填矩形即可，刻意不写 `clip`** |
| 官方选中项配色 | `tokens/MenuTokens.kt`：**`ListItemSelectedContainerColor = SecondaryContainer`**、`ListItemSelectedLabelTextColor = OnSecondaryContainer`、`ListItemSelectedLeadingTrailingIconColor = OnSecondaryContainer`；容器 `ContainerColor = SurfaceContainer`、`ContainerShape = CornerExtraSmall` |
| 菜单渲染在 `Popup` 里 | `androidMain/androidx/compose/material3/AndroidMenu.android.kt:69-86` |

**配色角色回源**：`pixfold-d1/app/src/main/kotlin/com/pixfold/d1/ui/theme/ColorSchemes.kt:97 secondaryContainer = role(secondaryContainer)`；`ColorSchemes.kt:150-200 fun ColorScheme.withPureBlack()` 只把 `background/surface/surfaceBright/surfaceDim/surfaceContainerLowest/Low/Container/High/Highest` 压成 `Color.Black`、`surfaceVariant = Color(0xFF1A1A1A)`，**不动 `secondaryContainer`** ⇒ 纯黑（OLED）模式下选中填充仍可见。

### 16c.3 实现（唯一生产文件 `pixfold-d1/app/src/main/kotlin/com/pixfold/d1/ui/workflowa/SortAndPreviewPage.kt`）

```kotlin
options.forEachIndexed { index, option ->
    val isCurrent = option == columns
    val highlight = if (isCurrent) {
        Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
    } else {
        Modifier
    }
    DropdownMenuItem(
        text = { Text(text = option.toString()) },
        onClick = { expanded = false; onSelect(option) },
        modifier = Modifier
            .testTag(columnTag(option))
            .then(highlight)
            .semantics { selected = isCurrent; contentDescription = optionCds[index] },
    )
}
```

- **删** `fun columnCheckTag(n: Int) = "column-check-$n"` 及其 13 行 KDoc；**整块删** `leadingIcon`。
- **删** `pixfold-d1/app/src/main/res/drawable/ic_check.xml`（删前全仓仅 `SortAndPreviewPage.kt:679` 一处引用）—— 不清会被 lint `UnusedResources`（生效 + `warningsAsErrors = true`）硬失败。
- 非选中档用 `Modifier`（而非 `Color.Unspecified`）⇒ 不白画一层；随之 `androidx.compose.ui.graphics.Color` 成为**未用 import**，已删（同上 lint 硬失败）。
- `SemanticsProperties.Selected` 是**全仓首次使用**（grep 零先例）；仓内 `selected = ...` **参数**用法本已遍布（`IssueFilterRow.kt:55/63`、`SortAndPreviewPage.kt:281/583`、`SettingsScreen.kt:546` 等），但那是组件参数，不是语义属性。

### 16c.4 断言（`pixfold-d1/app/src/test/kotlin/com/pixfold/d1/ui/workflowa/SortPagePolishTest.kt`）

- `hasChecked(n)`（读 `columnCheckTag` + `useUnmergedTree = true`）⇒ **`isHighlighted(n)`**：`rule.onAllNodesWithTag(columnTag(n), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.config ?: return false`，再判 `config.contains(SemanticsProperties.Selected) && config[SemanticsProperties.Selected]`（用 `firstOrNull` 使**否定断言**也能取到 `false`）。
- 两处测试改名：`..._checks_the_current_one` ⇒ `..._highlights_the_current_one`；`..._moves_the_check` ⇒ `..._moves_the_highlight`。
- **新增源码结构守卫** `current_column_is_highlighted_by_a_background_fill_not_a_checkmark()` —— **7 条**断言：`.background(`、`secondaryContainer`、`option == columns`、`if (isCurrent)`、`.then(highlight)`、`!leadingIcon`、`!ic_check`。
  - **为什么要断言到「接线」**：`selected` 语义与背景填充**分属两条 `.` 链** ⇒ 只删 `.then(highlight)` 时 `isHighlighted()` 仍全绿而用户看得见的底色已消失。**这条空洞在随后的变异 M1 中被实测证实**（见 16c.5）。
  - **为什么又是结构断言而非像素断言**：底色是**绘制**产物，而条目渲染在 `Popup` 里，Robolectric 下 `onRoot().captureToImage()` 能否截到 Popup 内容**未经证实**，拿它当断言前提是埋雷。**底色本身已由真机像素读数钉住**（见 16c.6 ③）。

### 16c.5 变异验证（M0–M6 全杀，还原逐字节一致）

| 变异 | 内容 | 结果 |
| --- | --- | --- |
| M0 | 基线不改 | 全绿 ✓ 42 files / 396 tests / 0 failed |
| M1 | 删 `\n                        .then(highlight)` | **杀死 ✓ 1 条失败**（若不设 `.then(highlight)` 断言则**存活** ⇒ 证实 16c.4 的空洞） |
| M2 | `val isCurrent = option == columns` ⇒ `= true` | 杀死 ✓ 3 条失败 |
| M3 | `val highlight = if (isCurrent) {` ⇒ `if (false) {` | 杀死 ✓ 1 条失败 |
| M4 | `secondaryContainer` ⇒ `primaryContainer` | 杀死 ✓ 1 条失败 |
| M5 | `selected = isCurrent` ⇒ `selected = false` | 杀死 ✓ 3 条失败 |
| M6 | 加回 `leadingIcon = { Icon(painterResource(R.drawable.ic_view_column), contentDescription = null) },` | 杀死 ✓ 1 条失败 |

脚本 `pixfold-d1/artifacts/mutate_round3.py`（gitignored）；**还原一律用 `cp` 备份 + 内存副本，绝不用 `git checkout --`**；还原校验打印「还原校验：OK（逐字节相同）」。

### 16c.6 真机核对（2026-09-27 通过，用户单独授权，PJX110 / Android 16 / 1264×2780px / 560dpi = 361dp×794dp，locale zh-CN）

| # | 核对项 | 实测读数 |
| --- | --- | --- |
| ① | 首页入口 | `PixFold` `[56,217][379,343]`；设置 `[1040,196][1208,364]`；工作流 A `[56,406][1208,630]` ✅ |
| ② | 361dp 自适应 = 4 列 | 列数按钮 `content-desc='4 列'`、`text=''` `[487,924][655,1092]`；网格四列 `001` `[42,1306][316,1678]`、`002` `[344,…]`、`003` `[646,…]`、`004` `[948,…]` ⇒ 卡片宽 **274px = 78.3dp** ✅ |
| ③ | **两态等宽（本轮核心）** | 四个菜单行**左边缘全 `x=487`、右边缘全 `x=879`（宽 392px）**，数字**全在 `x=529..558`**：`2 列` `[487,1120][879,1288]`、`3 列` `[487,1288][879,1456]`、`4 列` `[487,1456][879,1624]`、`5 列` `[487,1624][879,1792]` ⇒ 对比第二轮（当前档 `x=655` vs 其余 `x=529`），**36dp 宽度差已消除** ✅ |
| ③ | **整行背景填充 + 仅当前档** | `r3_menu.png`（270,938 B）采样：第 2/3/5 行（y=1204/1372/1708）在 x=500/620/800/870 **全部 `(0,0,0)`**；**第 4 行（y=1540）四处全 `(80,52,55)`**；菜单外参考 `(141,58,57)` ✅ |
| ③ | **填充随选择转移** | 选 2 列后 `r3_menu2.png`（222,906 B）：`2列` y=1204 = `(80,52,55)`，`3/4/5列` = `(0,0,0)` ✅ |
| ④ | 选中后卡片真的变宽 | tap 后按钮 `content-desc='2 列'`；卡片 `001` `[42,1306][618,1980]` 宽 **576px = 164.6dp**（4 列时 274px）✅ |
| ⑤ | 长按提示未被本轮破坏 | 亮气泡 `(255,248,247)` 位于 **x=499..641、y=826..909**，与第二轮读数**逐字相同** ✅ |
| ⑥ | 工具栏临时覆盖**不**持久 | 选 2 列 → `keyevent 111` + `4` 回首页 → 重进 A ⇒ 按钮回到 **`4 列`**、卡片 `001` 恢复 `[42,1306][316,1678]`（274px）✅ |
| ⑦ | 设置页两行 | `竖屏默认列数` = **`跟随自适应（4）`** `[845,821][1222,891]`；`横屏默认列数` = **`跟随自适应（9）`** `[845,989][1222,1059]`；说明行「作用于网格视图；排序页的列数按钮可在本次进入时临时覆盖。」✅ |

⚠️ **本轮新增操作教训**：`input draganddrop 571 1008 571 1008`（起止同点）在本机被判定为**点击**而非长按 ⇒ 会重新打开下拉菜单。正解 = 用**显式运动事件**，并把动作与取证串进设备侧**同一条** `adb shell`：

```
adb shell "input keyevent 111; sleep 1; input tap 300 2600; sleep 1; \
           input motionevent DOWN 571 1008; sleep 2; \
           screencap -p /data/local/tmp/tip.png; uiautomator dump /data/local/tmp/tip.xml; \
           input motionevent UP 571 1008"
```

（第二轮记的「`input draganddrop` 可以触发长按」**在本轮不成立** —— 起止点相同的写法退化为点击。改用 `motionevent DOWN/UP` 后一次成功。）另：**`uiautomator dump` 不映射 tooltip**（提示不是可访问节点）⇒ 提示只能靠**像素**取证。

收尾：`/data/local/tmp` 的 `.xml`/`.png` 计数 **归零**（`ls` 只剩既有非调试产物）；`accelerometer_rotation=1`、`user_rotation=0`、`svc power stayon false`、`am force-stop com.pixfold.d1` 均已复位。

### 16c.7 离线闸门与 lint 红项归因

- `:app:testDebugUnitTest` 42 files / **396 tests** / 0 failed / 0 skipped（较第二轮 395 **+1**，即新增的结构守卫）。
  ⚠️ 此 396 是**第三轮高亮修订**当时的读数；同日另两轮修订（§16c.9 宽度、§16c.10 居中）各再 +1，**最终为 42 files / 399 tests / 0 failed / 0 skipped**。
- `:domain:test` 20 files / **247 tests** / 0/0/0。
- `:app:assembleDebug` ⇒ `app/build/outputs/apk/debug/app-debug.apk` = **33,368,725 B**。
- ✅ `:app:lintDebug` **最终 = `0 errors, 0 warnings, 4 hints`**。首跑曾有 **1 error / 0 warnings / 4 hints**，唯一 error 逐字：
  `D:\Programing\Personal\pixfold\pixfold-d1\gradle\wrapper\gradle-wrapper.properties:3: Error: A newer version of Gradle than 9.7.1 is available: 9.8.0 [AndroidGradlePluginVersion]`
  **归因 = 环境漂移**（Gradle 9.8.0 上游新发布，上一轮跑闸门时该检查无对象可报）：对 `grep -c -E 'SortAndPreviewPage|SortPagePolishTest|ic_check'` 数 lint 报告 ⇒ **0 命中**，本轮改动一个都没出现在报告里。4 个 hints 全是既有 `AutoboxingStateCreation`（`ui/components/DragReorderGrid.kt:175`、`:176`，`ui/components/DragReorderList.kt:94`、`:95`）。
  **用户裁决 `lint_drift_policy`（逐字选项标签）= 「A：升级 Gradle wrapper 到 9.8.0」** ⇒ **2026-09-27 同日并入本轮落地**（改 `gradle-wrapper.properties:3` 第 3 行 `distributionUrl` → 重跑四道闸门 ⇒ error 消失、lint `0 errors, 0 warnings, 4 hints`、APK **33,368,725 B** 逐字节同尺寸，与升级前一致）；`app/build.gradle.kts:52-73` 的豁免清单**未动**（那段注释明写三项检查是**刻意重新启用**的）。

### 16c.8 与第二轮的关系

第二轮的九条裁决与实现（纯图标 / 档位 2–5 / 去掉「自适应」项 / 竖横屏两个默认列数设置 / `ADAPTIVE_COLUMN_MIN_WIDTH` 110→80dp 使 361dp = 4 列 / 超范围值并入 / 临时覆盖）**全部保持不变**；本轮**只换高亮的呈现形态**：`leadingIcon` 对勾 ⇒ 整行 `secondaryContainer` 背景填充。第二轮小节见 §16b，其 16b.6 表中「当前档高亮」那一行的读数（`4 列` 的 `x=655`）**已被本轮 ③ 取代**。

### 16c.9 第四轮：下拉菜单过宽 ⇒ 收窄 48dp（单点、不逐条目重复）

- `User said (m23557)`（逐字）：「列数下拉列表只显示个数字，选项却特别宽，不好看」。
- 同回合 `User said (m23631)`（逐字）：「菜单宽度这个没有更加优雅的写法吗，另外你好像又又又又又忘了更新任务面板进度」
  ⇒ (a) 要求**更优雅**的写法（否掉逐条目 `Modifier.width()`）；(b) **第五次**任务面板批评。
- **形态裁决**：`ask_user_question` id `menu_width_fix`，用户选（逐字选项标签）**「改成 48dp（与列数按钮同宽）」**。
- **源码依据**（一手，解包自 `pixfold-d1/app/build/m3src/`、`fdsrc2/`）：

| 事实 | 源码位置 / 原文 |
| --- | --- |
| 条目侧宽度被 `sizeIn` 兜到 **112dp**，但其默认 `enforceIncoming = true` ⇒ 会被上游固定宽**夹住** | `Menu.kt:419` `…fillMaxWidth().sizeIn(minWidth = DropdownMenuItemDefaultMinWidth = 112.dp, maxWidth = DropdownMenuItemDefaultMaxWidth = 280.dp, minHeight = MenuListItemContainerHeight)`；`Size.kt:226-238 sizeIn` 的 `SizeElement(enforceIncoming = true)` |
| 内层 `width(IntrinsicSize.Max)` 同样 `enforceIncoming = true` ⇒ 服从传入的 48dp 上限 | `Menu.kt:359-415` `Column(modifier = modifier.padding(vertical = DropdownMenuVerticalPadding).width(IntrinsicSize.Max).verticalScroll(scrollState))`；`Intrinsic.kt:51 Modifier.width(intrinsicSize) = … IntrinsicWidthElement(width = …, enforceIncoming = true, …)` |
| 调用方 `modifier` 在**链首**；外层 `Surface` **不接收**调用方 modifier ⇒ 宽度只作用在内层 `Column` 上，`Surface` 随之包裹收缩 | `Menu.kt:359-415`；`Intrinsic.kt:268-300 IntrinsicSizeModifier.measure` 的 `constraints.constrain(contentConstraints)` |
| 公开 `DropdownMenu` 确实把 `modifier` 透传到该 `Column` | commonMain `Menu.kt:122-136 expect fun DropdownMenu(… modifier = Modifier …)`；`AndroidMenu.android.kt:42-87 actual fun DropdownMenu` 内 `Popup { DropdownMenuContent(…, modifier = modifier, content = content) }` |
| `Modifier.width(Dp)` 自身也是 `enforceIncoming = true`（会被 Popup 的屏幕宽约束夹住，不影响 48dp） | `Size.kt:61-73 Modifier.width(width: Dp) = SizeElement(minWidth = width, maxWidth = width, enforceIncoming = true)` |

- **实现**（`pixfold-d1/app/src/main/kotlin/com/pixfold/d1/ui/workflowa/SortAndPreviewPage.kt`）：
  新增 `private val COLUMN_MENU_WIDTH = 48.dp`（带 30 余行 KDoc 逐条引上表源码），
  `DropdownMenu(modifier = Modifier.testTag(TAG_COLUMN_MENU).width(COLUMN_MENU_WIDTH))` ——
  **宽度只在这一处给，条目侧一字未改**。这正是用户 m23631 要的「更优雅的写法」：
  库的 112dp 兜底是 `private`、无法从外部调低，但因为它 `enforceIncoming = true`，**上游单点设宽即等价于全局收窄**。
- **断言**（`SortPagePolishTest.kt`）：
  - `column_menu_is_as_narrow_as_the_button()` —— 用 `rule.density` 算 `48.dp.roundToPx()`，
    断言 `TAG_COLUMN_MENU` 节点与**每一档条目**（`columnTag(2..5)`）的真实布局宽度都 `== expected`
    （守住「条目侧 112dp 兜底确实被夹掉」）。
  - `column_menu_width_is_set_once_on_the_container()` —— **位置断言**：`widthAt in (menuCallAt + 1) until firstItemAt`
    （`menuCallAt = body.indexOf("DropdownMenu(")`、`firstItemAt = body.indexOf("DropdownMenuItem(")`），
    即宽度必须写在**菜单容器的 modifier** 里、**不得挪到条目链上**（后者正是用户否掉的写法）。
- **变异**：M1（删容器固定宽）、M2（宽度挪到逐条目）、M3（具名常量→魔法数字 `48.dp`）**全部杀死**；
  M4（`48`→`64dp`）由 `column_menu_is_as_narrow_as_the_button` 杀死，
  而位置断言**刻意不管数值**（已在测试注释写明分工）。
- **真机核对**：四行**全部 `[487,…][655,…]`、w=168px = 48.0dp**（上一轮 392px = 112.0dp）；
  逐行 `2 列` `[487,1120][655,1288]`、`3 列` `[487,1288][655,1456]`、`4 列` `[487,1456][655,1624]`、`5 列` `[487,1624][655,1792]`。
  高亮仍整行填充且随选择转移（选 2 列后 `x=500`/`x=630` 均 `(80,52,55)`，3/4/5 行全 `(0,0,0)`）；
  选 2 列后卡片外层 `[42,1306][618,1980]` = **576px = 164.6dp**（4 列时 274px）。
  ⚠️ 量卡片宽**必须取外层节点**：内层拖拽柄（cd 为 `…固定位置`）只有 168px，曾因此得出矛盾结论。

### 16c.10 第五轮：数字未居中 ⇒ `fillMaxWidth()` + `TextAlign.Center`

- `User said (m23942)`（逐字，全文）：「数字没有居中显示」。
- **根因**（M3 一手源码）：`DropdownMenuItemContent` 把文字槽位放在一个**没有对齐参数**的
  `Box(Modifier.weight(1f))` 里（`Menu.kt:461-479`）—— `Box` 默认把子项贴**起始边**放，
  于是数字被行内 12dp `contentPadding` 顶到左边（`Menu.kt:525 DropdownMenuItemHorizontalPadding = 12.dp`）。
  真机实测：四个数字 text 节点**全在 `x=529..558`**，行左缘 `487` ⇒ 距左 **42px = 12dp**，确实是左对齐。
  槽位自身没有对齐参数可传 ⇒ **只能在 `text` lambda 内让 `Text` 铺满槽位再 `TextAlign.Center`**。
- **实现**：
  ```kotlin
  text = {
      Text(
          text = option.toString(),
          modifier = Modifier.fillMaxWidth(),
          textAlign = TextAlign.Center,
      )
  },
  ```
  新增 import `androidx.compose.ui.text.style.TextAlign`。
- **断言**（`column_menu_numbers_are_horizontally_centered()`，**两层分工**）：
  1. **几何层** —— 对每档取条目节点与「`hasText(n.toString()) and hasAnyAncestor(hasTestTag(columnTag(n)))`」的
     数字节点（`useUnmergedTree = true`），断言两者中线偏差 `abs(itemCX - numberCX) <= 1f`。
  2. **结构层** —— 源码位置断言 `fillAt in (slotAt + 1) until alignAt`
     （`slotAt = body.indexOf("text = {")`、`fillAt = body.indexOf("Modifier.fillMaxWidth()")`、`alignAt = body.indexOf("textAlign = TextAlign.Center")`）。
  - **为什么必须两层**：`TextAlign` 只影响**字形**位置、**不改语义节点 bounds**，且 Robolectric 不做真实文本测量
    ⇒ 几何层**永远看不见** `TextAlign` 是否生效（它只能证明 `fillMaxWidth()` 生效）；结构层只能证明源码写法。
    两条合起来才等价于「数字居中」。**真机像素读数**才是终审（见下）。
- **变异**：M5（删 `textAlign = TextAlign.Center`）、M6（删 `Modifier.fillMaxWidth()`）、
  M7（`TextAlign.Center` → `TextAlign.Start`）**全部杀死** —— 但**三组都杀在结构层**，
  故几何层尚缺独立杀伤证据（有做结构层影子的嫌疑）。**M8 专门堵这个洞**：
  把 `Text` 包进 `Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart)`、`Text` 退回 wrap-content
  ⇒ 两个 token 仍在原位（结构断言**必绿**）而数字确实不居中。结果 **M8 杀在几何断言**，
  失败消息逐字 `2 列那一档的数字没有铺满 M3 的 text 槽位:行中线=24.0, 数字节点中线=13.0 ⇒ 数字会贴左内边距(12dp),看着不居中`
  ⇒ **几何层独立有效，不是结构层的影子**。
- **真机核对（像素级，节点框 ≠ 字形）**：四个数字的**墨迹**纵向中线 vs 行中线偏差
  **≤ 0.5px = 0.14dp**（行 2/3/4 为 0.14dp、行 5 为 0.00dp），墨迹 x=559..582（宽 **24px**）
  远在 48dp 行内 ⇒ **居中且未被 48dp 行宽截断**；换到高亮行（第 2 行）复测同样成立。
  ⚠️ 方法教训：`uiautomator` 给的 text 节点 bounds 是**槽位框**（铺满后 84px 宽），
  不能用来判断字形是否居中 ⇒ 必须**从 PNG 采样墨迹**（`uv run --with pillow`，逐列扫描与两种背景色差 > 90 的像素）。

**已提交** `3ca85b0`（8 files，+692/−86，**直接提交到 `dev`、无独立分支、未推送**）。

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
| 第 15 节「页序 vs 声明序」 | 读源码：`SortAndPreviewPage.kt:312` 传的 `index` 取自 `order.order`，对照预览目的地的取图来源；反证用 `MockDataTest` 已断言的页序首项 |
| 第 15 节「触摸目标是否真达标」 | 临时 Robolectric 探针 `TmpSizeProbe`：读 `fetchSemanticsNode().size.height`，同一文件内并列放「裸 / 加 `heightIn` / 显式 `size`」三种写法对比（用完即删） |
| 第 15 节「漏上报是否可见」 | 靠**变异**而非阅读：把上报改成只写本地，看测试是否变红 —— 单页内不变红即说明该性质无断言覆盖 |
| 第 3 节「筛选是否真按谓词筛」 | 真机 `uiautomator dump` 数 `将跳过` / `将重命名` / `成功` / `跳过` 行；并造出「2 个跳过项」以让 `仅问题（2）` 的 N 可核对 |
| 第 3 节「筛选条是否真常驻」 | 同一页 `swipe` 滚动前后各 dump 一次，比对筛选条 `bounds` 是否逐像素相同；同时确认 `PlanHeader` 已滚出视口（对照组） |
| 第 16c.9 节「菜单真的只有 48dp」 | 真机 `uiautomator dump` 读 `TAG_COLUMN_MENU` 与四个条目节点的 `bounds`（应全为 168px = 48.0dp）；对照上一轮的 392px = 112.0dp |
| 第 16c.10 节「数字真的居中」 | ⚠️ **不能**读 text 节点 bounds（那是铺满后的槽位框，恒等于行中线）；必须从 `screencap` 的 PNG 逐列扫描「与行内两种背景色差 > 90」的像素求墨迹范围，再与行中线比对 |
| 第 3 节「删掉的类是否真没进包」 | 解包 APK 全部 `classes*.dex` 做字节子串计数：`DraggableThumbCard` 与 `ThumbCard` 计数**相等**即证明后者仅为前者名字的子串 （`grep -c` 对二进制/多 dex 不可靠，返回 0） |


**⚠️ 一条取证教训**：本清单初稿曾据 Robolectric 探针（`w400dp-h1000dp`）
称"头部约 862dp、仅剩约 130dp"，而**真机是 794dp 视口** —— 探针视口比真机更宽松，
数字不可直接当结论。**结论一律以真机 dump 为准**（此项已改为实测值）。
