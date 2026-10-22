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

**五份全部保留，无一份失效。** 状态如下：

| 文件 | 状态 | 现在还读它吗 |
| --- | --- | --- |
| [`superpowers/plans/2026-10-04-d3-p1-packing-core.md`](superpowers/plans/2026-10-04-d3-p1-packing-core.md) | **活跃台账** | **是**。它同时是实施计划与夜间台账，§0「行车规则」与 §3「轮次台账」每轮开工/收工必读 |
| [`superpowers/specs/2026-09-20-d1-settings-theme-design.md`](superpowers/specs/2026-09-20-d1-settings-theme-design.md) | 已完成规格 | 查设计意图时读。文件内已标「已实施完成并真机核对通过」 |
| [`superpowers/specs/2026-09-16-d1-interaction-prototype-design.md`](superpowers/specs/2026-09-16-d1-interaction-prototype-design.md) | 已完成规格 | 查 D1 交互设计依据时读 |
| [`superpowers/plans/2026-09-16-d1-p1-scaffold-domain.md`](superpowers/plans/2026-09-16-d1-p1-scaffold-domain.md) | 历史计划 | 仅溯源时读。复选框 46 项全未勾选是历史事实（执行时走的是 `todo_write` 面板），不代表任务未完成 |
| [`superpowers/plans/2026-09-16-d1-p2-grid-list-preview.md`](superpowers/plans/2026-09-16-d1-p2-grid-list-preview.md) | 历史计划 | 同上，34 项未勾选 |

## 当前状态

阶段路线、退出条件与各阶段状态以 [`HANGOFF.md` §9](../HANGOFF.md) 为准，本节只是速览。
最后同步：2026-10-23。

| 阶段 | 状态 | 说明 |
| --- | --- | --- |
| **D0** 需求与样例固化 | ✅ | — |
| **D1** 交互原型 | ✅ 2026-09-19 | 验收清单 20/20；**UI 就地演进为 D4 基座** |
| **D1.1** 分支拓扑 | ✅ 2026-09-16 | `ready` → `dev` → 阶段分支 → `main` |
| **设置与主题体系** | ✅ | D4 的提前启动，不计入 D1 六项 |
| **D2a** SAF 闭环 | ✅ 2026-09-08 | 真机 5/5 |
| **D2b** 平台能力五项 | ✅ | 真机 4/5；①千级缩略图实测仍缺（设备素材只有 56 张） |
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
