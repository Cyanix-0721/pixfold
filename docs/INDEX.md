# PixFold 文档索引

> **本文件是文档地图，不是产品权威。** 产品决策的唯一权威仍是 [`HANGOFF.md`](../HANGOFF.md)。
> 当本文件与 `HANGOFF.md` 冲突时，以 `HANGOFF.md` 为准，并回来修正本文件。

## 为什么有这个文件

`HANGOFF.md` 曾经是 467 KB / 4,516 行，其中 §9「设计阶段路线」占 77.5%（362 KB）。
那 362 KB 里绝大部分是**实现期的逐轮留痕**——写完之后基本只在两类场合被读：
① 查「当时为什么这么改」；② 查「这一轮到底改了哪些文件」。

它和「当前该做什么」混在同一节里，导致每次打开 `HANGOFF.md` 都要先滑过几百 KB 的历史。

2026-10-23 的处理方式：**`HANGOFF.md` 只留状态与索引，留痕按阶段迁到 `docs/phases/`。**
留痕一字未改、未删、未摘要——原文逐字搬运，每段带 `<!-- 原 HANGOFF.md L{a}–L{b} -->` 标注来源。
所以任何一条旧记录都还能找到，只是不再挡在常读路径上。

## 文档地图

### 第一层：产品权威（常读）

| 文件 | 职责 | 规模 |
| --- | --- | --- |
| [`HANGOFF.md`](../HANGOFF.md) | **产品决策唯一权威**：定位、约束、工作流、领域模型、交互原则、平台策略、技术栈、验收标准、待确认问题、环境实况 | 约 130 KB |
| [`README.md`](../README.md) | 项目门面：概览、构建与开发环境部署 | 约 130 KB |
| [`AGENTS.md`](../AGENTS.md) | 项目级 agent 规约：文档层级、不可违背约束、每轮固定步骤、闸门与真机核对规约 | 约 56 KB |

### 第二层：阶段详版（只查不常读）

`HANGOFF.md` §9 的实现留痕按阶段拆到这里。**性质 = 实现期逐轮留痕**，
含每一轮的根因分析、修法、闸门读数、变异结果与真机取证。

| 文件 | 覆盖范围 | 来源（原 §9 行号） |
| --- | --- | --- |
| [`phases/d1-interaction-prototype.md`](phases/d1-interaction-prototype.md) | D1 交互原型全部轮次：验收清单、§11 各条、五项交互修订、列数控件两轮、收尾三轮 | L522–549、L550–629、L1633–1685、L2035–2654、L3015–3149 |
| [`phases/branch-topology.md`](phases/branch-topology.md) | 分支拓扑与七阶段切片原则（原 `### D1.1`） | L630–1312 |
| [`phases/settings-and-theme.md`](phases/settings-and-theme.md) | 设置页 / 主题体系 / 启动图标 / i18n | L1313–1372、L1373–1632、L1686–2034 |
| [`phases/d2-platform.md`](phases/d2-platform.md) | D2a SAF 闭环 spike、D2b 五项平台能力与真机取证 | L3150–3283 |
| [`phases/d3-domain-core.md`](phases/d3-domain-core.md) | D3 打包核心轮、追记、适配层边界检查 | L2655–3014、L3284–3328 |
| [`phases/d4-mvp.md`](phases/d4-mvp.md) | D4a/D4b：真实文件闭环、预检、通知、元数据、Series、进度读数 | L3329–3900 |

### 第三层：调研与实证笔记

非产品权威，记录「当时怎么验证的」。每条结论都附来源与命令。

| 文件 | 内容 |
| --- | --- |
| [`notes/d1-ui-friction-inventory.md`](notes/d1-ui-friction-inventory.md) | **操作流程与 UI 摩擦点清单**（149 KB，最大的一份）；每条标注来源 = 真机实测 / 代码核实 / 走查观察 |
| [`notes/d1-archive-domain-semantics.md`](notes/d1-archive-domain-semantics.md) | 自归档分支提取的归档原型领域语义 + 16 项测试意图逐条还原 |
| [`notes/d1-toolchain-evidence.md`](notes/d1-toolchain-evidence.md) | 新栈构建链实证（探针工程：assembleDebug + JVM 单测 + Robolectric + lint 全绿） |
| [`notes/d1-review.md`](notes/d1-review.md) | D1 全弧回顾：规模读数、真实缺陷台账、自证方法论、已知局限与未决项 |
| [`notes/d1-navigation-compose-migration.md`](notes/d1-navigation-compose-migration.md) | 导航迁移 Navigation Compose 的决策与 F1–F13 源码级依据 |
| [`notes/d2b-android16-live-updates-api.md`](notes/d2b-android16-live-updates-api.md) | Android 16 Live Update 通知升格的 API 调研 |
| [`notes/scripts-behavior-matrix.md`](notes/scripts-behavior-matrix.md) | 两个 Python 脚本的行为矩阵 + 13 条危险默认值 |

### 第四层：规格与计划（superpowers 产物）

**六份全部保留，无一份失效。** 状态如下：

| 文件 | 状态 | 现在还读它吗 |
| --- | --- | --- |
| [`superpowers/plans/2026-10-04-d3-p1-packing-core.md`](superpowers/plans/2026-10-04-d3-p1-packing-core.md) | **活跃台账** | **是**。它同时是实施计划与夜间台账，§0「行车规则」与 §3「轮次台账」每轮开工/收工必读 |
| [`superpowers/plans/2026-10-24-chip-waterfall-v4.md`](superpowers/plans/2026-10-24-chip-waterfall-v4.md) | 事后追记（retro-plan） | 改芯片行时读。写清了 96–156 / 4 行 / 3 行收口 / 浮层的全部推导依据，以及两条被探针否决的原生路子（`FlowRowOverflow` 已废弃且 `shownItemCount` 组合阶段不可用、老重载 `overflowIndicator` 不存在）。**不要按它重新实现** |
| [`superpowers/specs/2026-09-20-d1-settings-theme-design.md`](superpowers/specs/2026-09-20-d1-settings-theme-design.md) | 已完成规格 | 查设计意图时读。文件内已标「已实施完成并真机核对通过」 |
| [`superpowers/specs/2026-09-16-d1-interaction-prototype-design.md`](superpowers/specs/2026-09-16-d1-interaction-prototype-design.md) | 已完成规格 | 查 D1 交互设计依据时读 |
| [`superpowers/plans/2026-09-16-d1-p1-scaffold-domain.md`](superpowers/plans/2026-09-16-d1-p1-scaffold-domain.md) | 历史计划 | 仅溯源时读。复选框 46 项全未勾选是历史事实（执行时走的是 `todo_write` 面板），不代表任务未完成 |
| [`superpowers/plans/2026-09-16-d1-p2-grid-list-preview.md`](superpowers/plans/2026-09-16-d1-p2-grid-list-preview.md) | 历史计划 | 同上，34 项未勾选 |

## 历轮台账（2026-09-21 起）

本表原先在 `AGENTS.md` 开头，每次加载项目指令都要占满 22 KB，是 `AGENTS.md` 逼近
65,536 字节注入上限的主因（曾占到 88%）。2026-10-23 迁到这里：**内容逐字未改**，
`AGENTS.md` 开头只留一个指针。

⚠️ **2026-10-25 二次迁出**：迁完之后头部又长回 18,491 字节（占当时全文 41.1%），
内容仍是同一批事的第二份副本 —— 所以本表不是「一次性归档」，而是**每轮都在往里加**。
头部的合法内容只有三类：当前阶段一句话 / 换机续接点 / 指向本表的指针。

历轮表是「一行一轮」的索引，**详情位置列里的 `§9.1「…」` 保持原样**，按上面
「旧编号映射」表解析。日期 = 实际提交日。

| 日期 | 轮次 | 提交 | 详情位置 |
| --- | --- | --- | --- |
| 2026-09-21 | 补 `2021≠2025` 断言 + 工作流内部预测性返回 | — | HANGOFF §9.1「后续三步」 |
| 2026-09-22 | 导航迁移 Navigation Compose + 分层返回转场(**决策与 F1–F13 源码级依据**) | `33c0507`、`1559289` | `docs/notes/d1-navigation-compose-migration.md` |
| 2026-09-24 | **启动图标 MD 主题化 + minSdk 24→30**;并核实 **16 KB 页大小已达标且零配置**(`zipalign -c -P 16` 为准,不可按 ZIP 结构自算) | `2ba10f8` | HANGOFF §9.1「启动图标」+ `docs/notes/d1-toolchain-evidence.md` §9.7/§10 |
| 2026-09-24 | **P0 命名页头部折叠**(固定区 692dp→154dp、首屏建议行 0→5 条;含 LazyList 前插锚定陷阱) | `55685a1` | `docs/notes/d1-ui-friction-inventory.md` §1 |
| 2026-09-25 | **P1 底栏化**(5 个列表内动作全移到底栏)+ **底栏左右分槽 + 步骤1 头部伸缩** + **底栏视觉规格**(降高/返回类底色/撤销危险红) | `1c95db9` | `docs/notes/d1-ui-friction-inventory.md` §2 / §2b / §2d |
| 2026-09-25 | **minSdk 30 → 34**(获 `LocaleManager` + HDR gainmap) | `f3cfb08` | HANGOFF §12.6 |
| 2026-09-25 | **应用内语言不同步修复**(空表 = 重置系统语言 ⇒ 判据必须是「变化」而非「值」;含 `AppLanguageTest` 3 守卫 + 3 轮变异) | `669f1ed` | HANGOFF §9.1「设置与主题体系」第 4/5 条 |
| 2026-09-25 | **全量 i18n**(领域层文案改「文案键 + 参数」;`values/` 装英文、中文入 `values-zh/`) | `2221ef1`、`0368be5`、`53149ba` | HANGOFF §9.1「全量 i18n」 |
| 2026-09-25 | **§11 第 13/15 条**(中文数字自然排序 + 批量不再是逐项例外)+ 芯片行溢出(7 处同源) | `27f3a13` | HANGOFF §9.1「§11 第 13/15 条」+ 摩擦清单 §12 |
| 2026-09-25 | **§11 第 9/10/14 条**(拖拽跟手浮层 + 命名摘要披露手工调整) | `c712685` | HANGOFF §9.1「§11 第 9/10/14 条」 |
| 2026-09-26 | **§11 第 18 条**(拖拽跨视口边缘滚动) | `c712685` | HANGOFF §9.1「§11 第 18 条」+ 摩擦清单 §13/§14 |
| 2026-09-26 | **预览取错图 + 预览往返丢失所选集合 + 预览页改名入口**(三处真实缺陷) | `4953f7a` | HANGOFF §9.1「预览取错图…」+ 摩擦清单 §15 |
| 2026-09-26 | **「仅问题/全部」筛选推广到计划页/报告页**(抽共享 `IssueFilterRow`；筛完保原始行号)+ **死代码 `ThumbCard.kt` 清理**(常量迁入 `DraggableThumbCard.kt`) | `e35ab9c` | HANGOFF §9.1「筛选推广」+ 摩擦清单 §3 |
| 2026-09-27 | **五项交互修订**(排序页编辑器并入滚动容器(含前插锚定陷阱)/ 图钉统一 32dp(**订正**「32dp 低于 48dp 硬指标」的错误结论——触控框实为 48dp)/ 去 `SegmentedButton` 对勾 / 集合芯片改半角括号 / 新增列数控件(自适应默认)) | `e93d759` | HANGOFF §9.1「五项交互修订」+ 摩擦清单 §16 |
| 2026-09-27 | **列数控件第二轮**(模式行内纯图标下拉 2–5 / 去掉「自适应」项 / 新增竖屏与横屏两个默认列数设置(默认「跟随自适应」) / 调窄 `ADAPTIVE_COLUMN_MIN_WIDTH` 110→80dp 使 361dp = **4 列**;⚠️ **当时结论「不设 `selected` 语义⇒高亮只能用 `leadingIcon`」已被同日第三轮推翻**,见下行) | `f6dfaa9` | HANGOFF §9.1「列数控件第二轮」+ 摩擦清单 §16b |
| 2026-09-27 | **列数控件第三/四/五轮**(同日连走三轮:③ 下拉「当前档位」由 `leadingIcon` 对勾改为**整行背景填充** + 显式 `selected` 语义,根因 = 对勾让两侧宽度不等(现四行等宽、数字全 `x=529`),官方色角色依据 = `MenuTokens.ListItemSelectedContainerColor`,删 `ic_check.xml`;④ 菜单行宽收窄 **48dp**,宽度**只给在容器一处** `DropdownMenu(modifier = …width(COLUMN_MENU_WIDTH))`(依据 = 条目侧 `sizeIn(112.dp)` 与内层 `width(IntrinsicSize.Max)` 均 `enforceIncoming = true`,上游单点设宽即全局收窄);⑤ 数字**居中**,M3 text 槽位是无对齐参数的 `Box(weight(1f))` ⇒ 只能 `Text` 铺满槽位 + `TextAlign.Center`。**同日并把 Gradle wrapper 9.7.1 → 9.8.0 一并落地**(lint 由 1 error 转全绿)) | `3ca85b0` | HANGOFF §9.1「列数控件第三轮」+ 摩擦清单 §16c(含 §16c.9/§16c.10) |
| 2026-10-05 | **D3 打包核心与适配层**(工作面 W1–W24,分支 `d3/p1` 已完成并合回 `dev`;含 6 项待裁决 R1–R5 + i18n 的落地、警示 emoji 全面矢量化、**适配层边界可执行检查**`AdapterBoundaryTest`) | 见 `docs/superpowers/plans/2026-10-04-d3-p1-packing-core.md` §3.1 | HANGOFF §9 D3 节 |
| 2026-10-12 | **夜间离线推进(7 项全完成)**:A1 四道闸门复跑(939 tests 全绿)/ A2 变异审计(四层+11 组已验证)/ A3 lint hints 修复(`mutableStateOf`→`mutableFloatStateOf`/`mutableIntStateOf`,4 处)+ 依赖版本检查 / A4 文档一致性扫描(三处快照一致,§11 9/18 已裁决) / B1 D2b 存储空间预检领域层(`SpaceCheck.kt`,22 tests,3 变异) / B2 D2b Zip64 需求判定与中断半成品识别领域层(`Zip64Assessment.kt`,22 tests,3 变异) / B3 AboutLibraries 开源许可页(已实现验证,无需新代码)。分支 `d2b/p1` 合回 `dev` 并删除 | `1670ee7`/`00b47ea`/`58bdac4`/`27450ba` | HANGOFF §9 D2b 节 |
| 2026-10-15 | **D1 收尾轮(5 条 UI 调整 + 1 缺陷 + 拖拽语义改判 + 图标库评估)**:① 首页两张大卡「整理图片」/「打包漫画」+ 副标题 + `outlineVariant` 描边(纯黑模式下卡片与背景同色、完全不可见是②的真机缺陷);③ 删设置页假信息「多语言将在后续版本补全」;④ Tab 行右侧「重置全部」纯图标按钮(`ResetAllButton.kt` + Material Symbols 官方 SVG 经 `artifacts/svg2vd.py` 机器变换)+ 长按 tooltip + 二次确认 + 回步骤 1(A/B 同构);⑤ 回主页触发重置、工作流内部切步骤不重置(`composable<HomeRoute>` 内 `LaunchedEffect`);缺陷 = 连点底栏左下角 ⇒ 嵌套栈清空且回不来(加 `nav/StepNavigation.kt` 的 `popWorkflowStep()` 护栏,4 处接线);**拖拽语义改判**「松手落地」→「实时让位」(推翻 §11 第 9 条;界面实时变、数据只在松手时提交一次,`resolveReflowTarget` 纯判据);⑦ 图标库评估结论 = **不引入依赖**;新增产品决策「**工作区选取与入口分层**」。四道闸门绿(app 476 / domain 483 / lint 零告警 / APK 34,109,166 B),变异 M1–M17(M8 存活查出 `remember` 无 key 的真缺陷);真机核对通过(用户亲测)。**遗留**:拖拽让位缺过渡动效 | `fc663c2`/`ac39f06`/`37f7729`/`1dea313` | HANGOFF §4「工作区选取与入口分层」+ §9「D1 收尾轮」+ §11 第 9 条/§12.5 结论 2 改判;`docs/notes/d1-review.md` |
| 2026-10-16 | **D1 收尾轮 · 第二轮(拖拽让位动效 + 边缘抽搐修复)**:⑥b 补过渡动效(`DragReorderAnimation.kt` 的 `DragReorderPlacementSpec` + `Modifier.animateItem` **只在拖动期间**挂载);真机复测反馈「动画有点慢,而且拖拽到边缘的时候会抽搐」⇒ ⑥b-1 `StiffnessMedium`→**`StiffnessHigh`**(滞后 196.4px≈2.73 格 → 54.7px≈0.76 格);⑥b-2 **抽搐根因 = 命中几何被动画污染**(`slotBounds` 由 `onGloballyPositioned` 累积 ⇒ 读到飞行中矩形 ⇒ 反复换位 ⇒ 自我维持抖动)⇒ 抽 `layoutSlotBounds(...)` 从 `gridState.layoutInfo` 现算逻辑几何。四道闸门绿(app 482 / domain 483 / lint 零告警 / APK 34,109,166 B),变异 M1–M6;真机核对通过(用户逐字「效果可以」) | `7028c25`/`55a8648` | HANGOFF §9「D1 收尾轮 · 第二轮」+ §11 第 9 条/§12.5 |
| 2026-10-18 | **D1 收尾轮 · 第三轮(元数据页卷芯片行常驻 + 规模上限 + 「全部 N 卷」sheet)**:补测唯一「待实测」项(元数据页单页长度)⇒「过长」不成立(1.62 视口 / 2 次 swipe),但派生 P2 = 卷芯片随滚动滚出视口、换卷必须回顶;`VolumeSelector` **移出 `LazyColumn` 常驻**(与步骤 3 同构);随即发现 40 卷规模回归(13 行芯片 834.9dp 吃满视口 ⇒ 字段永久不可达)⇒ 常驻限 5 个芯片(前 4 卷 + 当前卷,`visibleVolumeChips()`)+「全部 N 卷」`TextButton` + `ModalBottomSheet`;`FlowRow(maxLines=2)` 变异存活**已删**。代价:列表视口缩 14.6%。**同源缺陷(步骤 3 `PackPreviewPage` 卷芯片)经用户裁决「一起做了吧」一并修** ⇒ 抽共享组件 `ui/components/VolumeChipRow.kt`(全站唯一实现,参数注入各页 tag/文案差异),`plurals meta_volume_all` 迁至 `strings_common.xml` 的 `volume_all`;`ChipRowWrappingTest` 的 `FlowRow` 计数表与 `horizontalScroll` 扫描列表同步扩到新文件。变异 M1–M6c 全 KILLED(M1 首轮存活 ⇒ 补直接断言后杀掉)。四道闸门绿(app **491** / domain 483 / lint 零告警 / APK **34,178,446 B**),真机 5 卷 + 40 卷 × 步骤 2/3 双页面核对通过 | `f5f7457`/`21426c8` | HANGOFF §9「D1 收尾轮 · 第三轮」+ `docs/notes/d1-ui-friction-inventory.md` §7b |
| 2026-10-18 | **工程改名(进 D2 前)**：目录 `pixfold-d1/` → **`android/`**、包名 `com.pixfold.d1` → **`com.cyanix.pixfold`**、`rootProject.name` → `pixfold-android`（`namespace`/`applicationId` 同步改）。动机 = `pixfold-d1` 是 D1 阶段代号，工程早已是 D4 MVP 基座并进入 D2。规模：Kotlin 源码 1115 处 / 172 文件、Gradle 3 处、`run-gradle.bat` 路径、源码扫描型测试的硬编码路径 2 处 + **边界测试正则 1 处**。⚠️ **两处易漏**：① `AdapterBoundaryTest` 的 `FORBIDDEN_REFERENCE` 写的是**转义形式** `com\.pixfold\.d1`，普通 `sed 's/com\.pixfold\.d1/…/'` 匹配不到 ⇒ 漏改会让边界检查去扫一个已不存在的包名、退化成恒真式（假绿）；② 目录改名后 `.gradle`/`build` 里的**增量编译缓存含旧绝对路径**，首次构建报 `The given file '…pixfold-d1\domain\build\classes…' is located outside the root directory`，须删 `build`/`.gradle` 重跑。四道闸门绿（app **491** / domain **483** / lint 零告警 / APK **33,555,875 B**），变异 M1（把边界正则退回旧包名）**KILLED**。文档口径：当前状态引用改新名，**历轮台账保留 `pixfold-d1` 原文**（改历史=伪造历史） | — | HANGOFF 头部「工程改名」注 + §9 D1 复用关系 |
| 2026-10-18 | **D2a 平台层在 `android/` 里重建(只读闭环)**:领域端口 `domain/source/`(`SourceDoc` 纯值对象 / `WorkspaceSource` 接口 / `collectionsFrom` 分组规则唯一权威,21 tests)+ 平台层 `app/data/saf/`(`AndroidSafSource` 递归 `buildChildDocumentsUriUsingTree` + `MAX_DEPTH=4` + 不传 sortOrder / `SafTreeStore` / `AndroidThumbnailSource`)+ `ui/workspace/`(`WorkspaceState` 映射 / `WorkspaceController` / `WorkspaceBar`)+ 5 处缩略图改 `ItemThumb`。**落点判断**:**不加第 4 个 Tab**(会牵动 `workflowAStepIndex()`/`goToWorkflowAStep()`/`popWorkflowStep()` 与约 30 条导航测试)⇒ 页签上方常驻 `WorkspaceBar` + 页签下方空态,与 §4「从属层」一致。**`None` 不是错误**:D1 全 mock ⇒ `collectionsOr(mock)` 回退演示数据、既有测试零改动;但 `Ready` 且空列表**不**回退。⚠️ lint 首轮 6 错(`workspace_none` 未使用 + 5 处 `UseKtx` 的 `Uri.parse` ⇒ 全改 `toUri()`;顺带删掉包 `Uri.parse` 的**死 try/catch** —— 它永不抛异常,真判据是 `getTreeDocumentId` 抛 `IllegalArgumentException`)。四道闸门绿(app **515** / domain **504** / lint 零告警 / APK **33,758,891 B**),变异 **M1–M4 全 KILLED**。**真机核对顺延 2026-10-19**(用户深夜指令)。⚠️ 变异踩坑:读 JUnit XML 前必须确认本次 gradle 真的跑过(否则读到上一轮结果 = 假阳性) | — | HANGOFF §9 D2a 节「在 android/ 里重建」 |
| 2026-10-19 | **D2b ①千级缩略图**:全缓存约 892 MiB vs 真机堆 256MB ⇒ `ThumbBudget`(边长向上对齐 2 的幂/预算=堆 12.5%/并发 `cpuCores+1`)+`ThumbnailCache`(按字节 LRU)+`CachingThumbnailSource`(在途请求合并 —— 并发闸门放行数 >1 时同图被读 4 次,是测试抓出的真缺陷);平台实现退回「只管读」,缓存与并发上移到可离线断言处。app 536 / domain 525,变异 M1–M10 全 KILLED | — | HANGOFF §9 D2b「第 1 项」 |
| 2026-10-19 | **D2b ③授权失效恢复决策层**:「用户真撤销」与「进程重启后授权未就绪」都是 `SecurityException`,平台无从分辨 ⇒ 不能一见 `Denied` 就报失效;`WorkspaceRecovery` 四态状态机(有限重试后降级/保留原目录名/任何失效态至少一条出路)+`AUTO_RETRY_LIMIT=1`;`WorkspaceController` 的 IO 调度器改可注入。app 554 / domain 542,变异 M1–M8 全 KILLED(M4/M5/M6 首轮存活 = Controller 零覆盖) | — | HANGOFF §9 D2b「第 3 项」 |
| 2026-10-19 | **D2b ②后台任务与进度领域层**:`TaskProgress` 五条语义 —— 失败项也推进进度(否则失败 30% 时卡在 70% 不动)/空任务即完成/节流六条判据(完成必报、失败数变化必报、倒退必报)/中断令牌 `@Volatile` 且只在项间检查/结果先判完成再判取消。app 554 / domain 568,变异 M1–M10(M8 等价 ⇒ 删冗余判据) | — | HANGOFF §9 D2b「第 2 项」 |
| 2026-10-19 | **D4b 执行前预检**:把此前从未被生产代码调用的 `SpaceCheck` 与 `Zip64Assessment` 组合;三处只在组合时出现的问题 —— SAF 的 `COLUMN_SIZE` 可为 null ⇒ 估算是**下界**(必给 `EstimateIsLowerBound`)/空计划不判空间/**Zip64 是告知不是拒绝**(当阻断会让大归档完全打不了)。阈值刻意不设默认值。domain 608,变异 M1–M7 全 KILLED | — | HANGOFF §9「D4b 执行前预检」 |
| 2026-10-19 | **D4b 执行编排内核**:`PackRunner`+`CbzOutputSink` 把 `buildCbzWritePlan`/`PageByteSource` 按卷串起来;四条硬规则 —— **先探测后开流**(缺页时连流都不开,否则留 0 字节 CBZ)/写盘失败必 `discardFor` 清理/中断只在卷间/`timeMillis` 必显式给。`isComplete` 在有失败项时仍为 true。顺带把「跳过」从 `failed` 拆成独立 `skipped`。domain 590,变异 M1–M6 全 KILLED | — | HANGOFF §9「D4b 执行编排内核」 |
| 2026-10-19 | **D4b 预检→执行闸门**:`PackGate.runPackPlansChecked` 让「预检拒绝就别写」从注释承诺变成代码 —— `Rejected` 时**一个输出流都不打开、一页字节都不读**;接口形状防「忘了预检」(参数 = `runPackPlans` 全部 + 空间参数)。domain 616,变异 M1–M3 全 KILLED | — | HANGOFF §9「D4b 预检→执行闸门」 |
| 2026-10-19 | **D4b 端到端集成测试**:`PackEndToEndTest` 用真实 mock 库(5 卷含 1 卷输出冲突)跑通「扫描→计划→预检→执行→ZIP 字节」;只断链路产物不断 ZIP 内容。三条真实数据才暴露的发现 = 输出名唯一性/跳过卷不该产生输出/中断与缺页的交互。domain 634,变异 M1–M8 全 KILLED | — | HANGOFF §9「D4b 端到端集成测试」 |
| 2026-10-19 | **D4b 运行报告桥接**:`packRunAsExecutionReport` 把真实 `PackRunReport` 单向转成 UI 已在渲染的 `ExecutionReport`(UI 零改动)。三条硬规则 —— 只有 `Written` 算成功且带 `createdPath`/`originalPath` 复用 `overwritesExisting` 唯一判据/中断后未处理卷补成「尚未开始」而非「跳过」。app 554 / domain 652,变异 M1–M5 全 KILLED | — | HANGOFF §9「D4b 运行报告桥接」 |
| 2026-10-19 | **D2a 真机核对 + 三处真缺陷**:①热切换工作区整片空白(无参 `remember` 播种 ⇒ 旧状态 + `?: return` 静默白屏)②集合分级错误(改判「每个直接含图片的目录 = 一个集合」)③重命名后文件名不更新(`documentUri` 不变 ⇒ 同 id 时原样返回旧实例)。并落地工作区实时刷新(回前台重扫 + `ContentObserver` + 组合期增量对账)。app 582 / domain 699,变异 M1–M11(M8 刻意保留) | — | HANGOFF §9 D2a/D2b 节 |
| 2026-10-19 | **D2b 平台层接线 + 真实产出 CBZ**:`SafPackPlatform`(覆盖走 `openOutputStream(existing,"wt")` 否则被改名成 `xxx (1).cbz`)/`SafPackExecutor`/`ComicVolumesFrom`/`PackExecutor`;真机产出 38,085,495 B 与 79,764,587 B 两个 CBZ。**当场暴露静默数据丢失**:同系列两本默认名都取系列名 ⇒ 后写覆盖前写 ⇒ 修 `titleSug` 取卷目录名 + `PackPreflight.OutputNameCollision` 阻断。app 614 / domain 776 | — | HANGOFF §9 D2b/D4b 节 |
| 2026-10-19 | **名字清洗 + 目录名统一抽象**:真机 CBZ 名带 `[DL版]` ⇒ 查出真实扫描路径此前完全没清洗。`NameCleaning`(`TRAILING_TAG_RE` 的 `$` 锚承重/`cleanCbzBaseName` 保留 `[作者]`)+`derivedNamesOf` 成唯一入口(raw 给芯片/title 给 `<Title>`/cbzBase 给落盘名,四种形态不得合并)+`namingSchemeSeedOf` 收敛 5 处分散播种。app 616 / domain 817,变异 M1–M16 全 KILLED | — | HANGOFF §9 D2b 节 |
| 2026-10-20 | **弹窗共同父类 + 第 9 处模糊 + 页级进度**:用户裁决「写的更加抽象写让调用的几个都有共同的父类?」「我说的包括8」⇒ 四个便捷入口全部委托 `PixFoldDialogShell`(Compose 无 UI 继承,「共同父类」唯一形态 = 委托同一 Composable);`buttons` 收 `ColumnScope` 任意槽(M3 `AlertDialog` 两槽摆不下三颗全宽竖排);`contentScrollable` 开关是**崩溃点**而非可选项(正文已是 `LazyColumn` 再包 `verticalScroll` 必崩);第 9 处 `ModalBottomSheet` 并列 `PixFoldBottomSheet` 但**共用同一模糊计数**。页级进度逐层透传 `CbzWriter→PackRunner→PackExecutor`(内环用)。app **701** / domain **910**,变异 M1–M6 全 KILLED(**M6 首轮存活 = 测抽象层时抽象层把错自己吃掉了 ⇒ 改经真实调用方断 scroll 容器个数**) | — | HANGOFF §9「双层圆环进度弹窗」 |
| 2026-10-21 | **Series 三条语义**:①无系列层时 Series 跟随 Title;②仅当卷目录 `[作者]` 前缀与工作区文件夹名一致时取工作区名(`seriesSuggestionOf` 判定树,**原始方括号前缀比对**而非清洗值);③series 下嵌套目录 ⇒ 书架页 error 色警告条(`nestedDirWarnings` = seriesKey 含 `/`,naturalCompare 排序)。占位符顺序三处同步(TranslationCoverageTest 连环抓)。app **704** / domain **920**,变异 M1–M7 全 KILLED;**真机核对待补**(凌晨设备自动锁 PIN) | — | HANGOFF §9「Series 三条语义」 |
| 2026-10-21 | **构建提速 + 闸门退出码修复**:①四道闸门旧调用形式 `cmd.exe /c "run-gradle.bat …" | tail -20` 的 RC 来自 `tail` ⇒ **构建失败也报成功**(实测漏一个 `}`,gradle `BUILD FAILED` 而 RC=0)⇒ 新建 `android/gate.sh`(输出重定向 `build/gate-last.log` + 直接取 gradle 退出码 + 失败打行号);⚠️ 外面再套 `\| tail` 会造成假挂起(`tail` 等 EOF)②`gradle.properties` 四项性能开关全开:`-Xmx4g`(内存峰值采样 4046→2471 MB,增量 1.6 GB ⇒ 原定 6 GB 虚高)/ `parallel` / `caching` / `configuration-cache`(lint 连跑 stored 4s→reused 2s);**build cache 让 JUnit XML mtime 失去证明力** ⇒ 判闸门看 `build/gate-last.log` 的 task 行。app 706 / domain 920 / lint 零告警 / APK 35,024,674 B | — | HANGOFF §12.4「每日必用命令」+ §12.4b「构建性能配置」 |
| 2026-10-21 | **元数据默认值三改判 + chip 六项规格**:①writer 默认值取清理后形态(去 `[]`)②建议值优先级 ComicInfo > 目录层级(`applyExistingComicInfo` 扩为连建议值一起覆盖)③单本 Series 跟随 Title(恢复建议后两者同值)④修本轮引入的回归:新增 `seriesDir` 作 `seriesKey` 唯一判据(原先取当前值 ⇒ 5 个单本显示成 5 个系列)⑤chip:截断保首尾(10 字)/后缀独立通道/文字居中/行内左对齐/两级遮罩留白/1 级恒 2 行 + 全宽下箭头长按钮⑥一键清理与恢复建议改纯图标 + 长按提示⑦`AppNavigationTest` 补 `qualifiers`(全仓唯一漏写,Robolectric 默认屏 320×470dp 装不下)。app **731** / domain **925** / lint 零告警 / APK 34,357,115 B;变异 M1–M12 全 KILLED | — | HANGOFF §9「元数据默认值三改判 + chip 六项规格」 |
| 2026-10-22 | **进度读数合并 + 元数据「按目录结构获取」**:①进度弹窗中心读数改为「已完成整卷 + 当前卷页比例」合并值(`TaskProgress.percentWithPage`),否则 1599 页的卷前 1598 页一直显示 0% ②删「一键清理」(writer 默认值已是清理形态 ⇒ 恒等变换)③新增「按目录结构获取」挂页签行「重置全部」旁、作用于当前卷全部字段,`ComicVolume` 补 `dirTitleSug`/`dirSeriesSug` 快照且 `applyExistingComicInfo` 刻意不动三个 dir 字段(否则 ComicInfo 写错时用户没有退路)④修真缺陷:`MetadataPage` 用无参 `remember` ⇒ 上层改状态后输入框毫无反应(与 D2a 白屏同因)。app **736** / domain **933** / lint 零告警 / APK **35,043,142 B**;变异 M1–M2 全 KILLED;**真机核对待补(白天)** | — | HANGOFF §9「进度读数合并 + 按目录结构获取」 |
| 2026-10-22 | **空标签统一 + 缺失警告补齐 + chip 上限放宽**:①五个 ComicInfo 字段**统一写自闭合空标签**,`<Number>` 不再例外(用户先要求「全都不写入」、随即改回「全部写自闭合」;D1 那条「`<Number />` 会被读成第 0 卷」是猜测性理由)②`pendingIssues` 从 1 项扩到 **4 项**:新增 `PendingTitle`/`PendingSeries`/`PendingWriter`(判据 `isBlank()`,只填空格算缺失),三处 UI 已在消费故零改动。⚠️ **标题这轮漏过一次** —— 首版以「标题几乎总能从目录名得到」为由不列,但 `titleSug` 只是**建议值**、Title 是可编辑输入框,用户清空后 XML 就是 `<Title />`;判据必须是「当前值是否为空」③chip 字符上限 10→24(首 12 + … + 尾 11),前提是芯片内文字已可折行 ⇒ 宽度上限改为「够看出一本书是谁」。app **736** / domain **937** / lint 零告警 / APK **35,044,494 B**;变异 M1/M2/M4 全 KILLED,补标题后 M1/M2 亦 KILLED;真机验超长名 `超长系列名称开头标识符A…末尾标识符XYZ（2）` 首尾保留+折行、`待确认：卷号缺失 / 作者缺失`、清空标题后 `待确认：标题缺失 / 卷号缺失` | — | HANGOFF §9「元数据空标签统一 + 可选字段缺失警告 + chip 字符上限」 |
| 2026-10-22 | **字段级红色警告**:`ComicVolume.pendingIssueOf(field)` 成「字段 ↔ 待确认项」唯一映射(页头汇总改为 `PENDING_FIELD_ORDER.mapNotNull`),`TextFieldRow` 补 `isError` + `supportingText`(Title/Series/Writer 各自标红)。⚠️ 需求先理解错一轮 —— 我补的是页头汇总,用户要的是「卷号那样空值的红色警告」。断言口径:不能用 `SemanticsProperties.Error`(M3 恒填 `Invalid input`),supportingText 因 `mergeDescendants` 并进同一节点 ⇒ 读该节点 `Text` 列表。app **741** / domain **940** / lint 零告警 / APK **35,625,226 B**;M1–M3 全 KILLED;真机见红边 + 「标题缺失」 | — | HANGOFF §9「字段级红色警告」 |
| 2026-10-23/24 | **chip 瀑布流改版（v4）—— 从「条数上限 + 上滑弹窗」改为「行数收口 + 原地展开 + series 顶点浮层」**:用户裁决（m00047 / m00110）①一行 2–3 个 ②横向定宽 ③纵向折 2–3 行→**4 行** ④过长首尾省略 ⑤「全部 N 项」**不再弹窗**、延续瀑布流；并取消「一级行最多 2 行」、二级改系列顶点浮层。落地 —— `ChipRowConstants.kt`(新建,`CHIP_MIN_WIDTH=96.dp`/`CHIP_MAX_WIDTH=156.dp`/`CHIP_TEXT_MAX_LINES=4`/`CHIP_LABEL_MAX_CHARS=30`/`CHIP_COLLAPSE_LINES=3`) + `SingleSelectChipRow.kt` 新增 `ChipFlow`(**`SubcomposeLayout` 手写两趟分行**:`ChipSlot.Probe` 用 `clearAndSetSemantics` 只量宽、`ChipSlot.Item` 才真组合;`Probe`/`Item` 必须不同 key,否则子组合被复用、清语义漏到真实副本) + `CollapseChip`(虚线同尺寸原地展开/`ic_arrow_drop_up`) + `SingleSelectChip` 补 `widthIn`(**核心改动:此前无任何宽度上限,长名撑满整行→瀑布流退化成一列**);二级改 `Popup` 浮层(自定义 `PopupPositionProvider` 钳窗口、`CHILD_POPUP_MAX_HEIGHT=318.dp` 内滚、三条收起触发 = 再点同系列/点另一系列/点系列外单本);删 `PixFoldBottomSheet` 与 10 个 sheet tag 常量、`VISIBLE_SINGLE_SELECT_CHIPS`/`TOP_CHIP_MAX_LINES`/`visibleSingleSelectItems`/`visibleVolumeChips` 全部退役。**原生 `FlowRowOverflow` 已用探针否决**:foundation-layout 1.12.1 里它 `deprecated`(`FlowLayout overflow is no longer maintained`)且 `shownItemCount` 在组合阶段读取抛 `IllegalStateException`;老重载参数名 `overflowIndicator` 该版本不存在(编译期 `No parameter with name`)。**两个真缺陷当场修掉**:①`items[1]` 越界崩溃(items 由 2 项缩 1 项时残留 subcomposition 带旧 index 重进)⇒ `getOrNull(index) ?: return@ChipFlow` ②被收起芯片仍留在语义树(读屏会念出看不见的条目)⇒ 两趟 subcompose。四道闸门绿(app **748** / domain **940** / lint 零告警 / APK **35,087,282 B**),变异全 KILLED(去 48dp 触摸目标 / 锚点退回单变量 / 展开态去掉限高 / 收口计数改成已显示数 / focusable 退回 true / 删 BackHandler)。**真机核对完成(density 3.5 / 1264×2780)**,并当场改判两处 —— ①**行内间距 横 6/纵 4 → 横 4/纵 10 dp**(用户:「chip内容有换行的情况下,左右间隔大了些,上下又几乎没有间隔」;行高由最高那块决定,单行块下方先空一截再叠 4dp ⇒ 行粘死) ②**又一个真缺陷:`Popup(focusable = true)` 让收起触发②「点另一个 series」要点两次** —— focusable 窗口持焦点,窗口外触摸被消费成「点外部⇒dismiss」且不透传给下层窗口;改 `focusable = false` + `dismissOnClickOutside = false` + `dismissOnBackPress = false`,返回键由 `BackHandler(enabled = true)` 兜住(activity-compose 1.13.0 既有依赖)。真机读数:芯片宽 156.0/133.7/119.4/96.3dp 全在 [96,156];横向 4dp、行间留白 ≈10dp;15 个集合在第 3 行末尾出「全部 15 项」虚线芯片、点开 6 行全铺开、超 318dp 自带内滚且「收起 ▲」可达;24 卷系列浮层在芯片区下方、1 级芯片无一被遮挡、内滚正常、三条收起触发 + 返回键全过;30 字截断实测**未溢成 5 行**。**教训(第四次)**:断言写 `src.contains("focusable = false")` 时被该测试自己的 KDoc 命中 ⇒ 恒真,变异存活后才暴露,须过滤注释行再断 | — | HANGOFF §9「chip 瀑布流改版」 |
| 2026-10-24 | **两个遗留缺口全部关闭（真机取证）**：① **D2b 千级缩略图** —— 用户复制 862 张真实素材到 `/sdcard/test/pixiv`（单张最大 9.9 MB），真机 PJX110 / Android 16 / 1264×2780px / 560dpi 实测：冷启 **3.2s 出画面 / 6.7s 稳定**；TOTAL PSS **136,526 KB → 峰值 271,329 KB → 回落 231,449 KB**（按字节 LRU 真在淘汰，pid 未变、`logcat -b crash` 长度 0）；gfxinfo Total frames **850** / Janky **1 (0.12%)** / 50th 8ms / 90th 14ms / 95th 15ms / 99th 16ms / Missed Vsync 0；862 张滚到底全部加载、无空白格、无占位破图 ⇒ **D2b 五项真机 5/5，2026-10-19「接受代码写完但未验证」的裁决作废**。② **Series 三条语义** —— 造三组素材实测：`sem1/普通单本 第01卷` ⇒ Series 建议「普通单本 第01卷（无系列层，跟随 Title）」；`[测试作者] 测试系列/` 下 `[测试作者] 测试系列 第01卷` ⇒「测试系列（工作区文件夹名（卷目录 [作者] 前缀与其一致））」，同目录反例 `[别人] 别的书 第03卷` ⇒「别的书 第03卷（无系列层，跟随 Title）」；`nestcheck/甲系列/中间层/卷01` ⇒ 列表页出 error 色警告条「系列 甲系列 下出现嵌套目录 中间层 —— 系列内应只放单本文件夹或压缩包」（实测色 `#EDB4B2` vs 正常文字 `#D7C1C2`）⇒ **三条全部与 `seriesSuggestionOf` / `nestedDirWarnings` 的判定树一致，无缺陷** | 真机读数见 `docs/phases/d2-platform.md`「① 千级缩略图取证」 | HANGOFF §9 D2b / §9.7 D4 |
| 2026-10-24 | **工作区选取搬到首页**（分支 `d4/home-workspace`）：用户原话「把工作区选取改到首页？写功能里感觉意义不大，要么是首页点进去，要么是外部直接分享文件夹或者大量图片进去（这功能D5再做）」⇒ 落地的是 `HANGOFF.md` §4 里 2026-10-15 就写成目标形态、当时标「将来」的那一版：**标题+齿轮 → 两张工作流大卡 → 其下从属层一行「当前工作区: … [更换]」**，入口**仍不是第三张大卡**。三条裁决（用户「乙甲乙」）：① 工作流内两处 `WorkspaceBar` 改 `workspaceState.showsWorkspaceBar` 收窄（Loading/Denied/Failed 才画；`None`/`Ready` 不画）—— 不能全删，因为 `WorkspaceState.collectionsOr` 在非 `Ready` 一律回退 `MockData`，授权失效后界面上照样有一列看着正常的图片；② 选完**停首页**不自动跳；③ 未选工作区时**两张大卡禁用** + 引导文案。**准入判据另立 `HomeScreen.canStartWorkflow`** —— 不复用 `WorkspaceState.canOrganize`（后者在 None/Denied/Failed 都返 true，是 D1 的 mock 演示语义）：`null→true` / `None/Loading/Denied/Failed→false` / `Ready→collections.isNotEmpty()`。禁用走 `Modifier.clickable(enabled = enabled)` 而非空 lambda（只有 `enabled` 往语义树写 `Disabled`，读屏与 `assertIsNotEnabled` 都依赖它）。测试侧新增注入缝 `PixFoldApp(workspaceSource: WorkspaceSource? = null)` + `ReadyWorkspaceSource`（Robolectric 无 `DocumentsProvider` ⇒ `lastWorkspace()` 恒 null ⇒ 大卡恒禁用 ⇒ 24 处导航测试需放行）；`AppNavigationTest` 的 `aot-1` 改 `trip`（注入就绪工作区后卷 id 来自 `collections` 而非 `MockDataB`）。新增 `HomeWorkspaceTest` 10 条 + `LibraryPageTest` 2 条 + `OrganizeWorkspaceTest` 6 条改判。四道闸门 **app 764 / domain 940 / lint 零告警 / APK 34,426,454 B**（`--rerun-tasks`）；变异 M1–M6 全 KILLED（M5 首轮 SURVIVED ⇒ 补 `LibraryPageTest` 守卫后转 KILLED）；**真机核对待做**。 |
| 2026-10-26 | **工作区列表 v4 落地**（分支 `d4/home-workspace`，18 个提交）：用户裁决「加个硬上限」= **8**、钉住的排到本区最前且图钉之间仍可互换、不做相册模式、不做指定第三方文件管理器（「它已经在那儿了」—— 实现 DocumentsProvider 的就会自动出现在系统选择器侧栏）、删掉设置页那个从未被代码读过的「文件访问方式」开关。落地 ① 两分区（已持久授权 / 只记住了路径）按 `persistedUriPermissions` 每次打开实时判、分区不可跨越拖拽 ② 图钉 = 本区置顶 ③ 长按整行拖动（无独立抓手）④ 上限 8，满则挤掉最久未打开、全钉住则拒绝并提示 ⑤ ✕ 只删记录，Switch 可选同时撤销持久授权 ⑥ 只用 SAF、manifest 一行存储权限不加。规格 `docs/superpowers/specs/2026-10-25-workspace-list-design.md`，计划 `docs/superpowers/plans/2026-10-25-workspace-list.md`，按 subagent-driven-development 分 T1–T11。三个「不崩不报错」的真缺陷：① `applyRefresh()` 自动重试成功分支漏 remember ⇒ 冷启动恢复旧工作区 ② `openList()` 逐条判授权 = 8 次 Binder ⇒ 改成批量 `persistentGrants(): Set<String>` ③ `move()` 用例只断 source 不断 controller.listings ⇒ 删 `refreshListings()` 全绿。另修 Task 5 的 48dp 陷阱（Loading 态整行退化成 16.0dp）。变异 M1–M10b 全 KILLED；**M10 首轮存活**（让位位移内联在 graphicsLayer，上下对调 841 条全绿）⇒ 抽 `rowShiftFor` / `rowDragOffsetFor` 纯函数 + 7 条几何单测。并行派两个做变异的 agent 造成工作树互污（已固化 AGENTS.md §0.1 第 6 条）。闸门 app **848** / domain **950** / lint 0 / APK 34,543,005 B。**真机（Task 11）待做**。 |

## 当前状态

阶段路线、退出条件与各阶段状态以 [`HANGOFF.md` §9](../HANGOFF.md) 为准，本节只是速览。
最后同步：2026-10-24。

| 阶段 | 状态 | 说明 |
| --- | --- | --- |
| **D0** 需求与样例固化 | ✅ | — |
| **D1** 交互原型 | ✅ 2026-09-19 | 验收清单 20/20；**UI 就地演进为 D4 基座** |
| **D1.1** 分支拓扑 | ✅ 2026-09-16 | `ready` → `dev` → 阶段分支 → `main` |
| **设置与主题体系** | ✅ | D4 的提前启动，不计入 D1 六项 |
| **D2a** SAF 闭环 | ✅ 2026-09-08 | 真机 5/5 |
| **D2b** 平台能力五项 | ✅ | 真机 **5/5**；①千级缩略图 862 张实测通过（2026-10-24） |
| **D3** 领域核心与适配层 | ✅ 2026-10-05 | 边界有可执行检查，`domain/` 零 Android 依赖 |
| **D4a** MVP 界面闭环 | 🔄 | — |
| **D4b** MVP 真实文件闭环 | 🔄 | 已跑通真实产出 CBZ（含 4.73 GiB + Zip64）、改名撤销、预检、通知、元数据 |
| **D5** 增强功能 | ⬜ | 未开始 |

## 旧编号映射：`§9.1「…」` 该去哪

`§9.1` 在本仓库被引用了 84 次，但 **`HANGOFF.md` 里从来没有过一个叫「§9.1」的编号节**——
所有 `###` 标题都是按名字起的（`### D1：交互原型`、`### D1.1 分支拓扑…`）。
`§9.1` 是项目里约定俗成的**泛指**，实际指向至少六种不同的东西。

2026-10-23 起，`§9` 内部有了真实编号，映射关系如下：

| 旧写法 | 实际指什么 | 现在去哪 |
| --- | --- | --- |
| `§9.1「<某个轮次名>」` | 某一轮的具体留痕 | 先看 `HANGOFF.md` §9.1 的**历轮索引表**，再点进对应 `docs/phases/` 文件 |
| `§9.1 拓扑` / `§9.1 分支模型` / `§9.1 发版` / `§9.1 切片原则` | 分支拓扑与阶段切分 | **`HANGOFF.md` §9.2**，详版在 [`phases/branch-topology.md`](phases/branch-topology.md) |
| `§9.1` 指阶段表 / 门槛表 | 阶段状态 | `HANGOFF.md` §9.1 的阶段主文档表 |
| `§9 D1` / `§9 D2b` / `§9 D3` 等（无 `.1`） | 某一阶段的整节 | 该阶段现在的编号节（§9.4 D1 / §9.5 D2 / §9.6 D3 / §9.7 D4） |
| `§9.7`（**注意：这是另一份文件**） | `docs/notes/d1-toolchain-evidence.md` 的 §9.7 | 与本文件的 §9.7 无关，去那份笔记里找 |

> **没有做的事（有意为之）**：既有的 84 处引用**一处都没有被改写**，除了 5 处明确指向「分支拓扑」的
> `§9.1` 改成了 `§9.2`。原因是这 84 处的语义分成至少六类，任何批量替换都会改坏一部分——
> 2026-10-23 曾试过一次正则批量替换，产出了方括号与圆括号错配、丢失右引号 `」` 的畸形 markdown，
> 已全部回滚。**旧引用保持原样 + 提供本映射表**，比冒险改 84 处更安全。

## 维护规则

1. **产品决策的变更先回 `HANGOFF.md`**，再同步 `README.md` 与本文件的状态表（三处一致）。
2. **新增一轮工作**：写成所属阶段的 `####` 子节，落在 `docs/phases/` 对应文件里，
   并在 `HANGOFF.md` §9.1 的历轮索引表加一行。**不得新开 `###` 编号节** ——
   早期正是「每轮新开一个 `###`」导致 `###` 标题吞掉了大量无关轮次，编号与内容彻底脱钩。
3. **新增一份调研笔记**：放 `docs/notes/`，并在本文件第三层表格加一行（写清内容，不只是文件名）。
4. **不要删除历史文档**。过时的用状态列标注（如「历史计划」），而不是删掉——
   删掉的是「当时为什么这么决定」的唯一证据。
