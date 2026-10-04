# D3 阶段 P1：打包核心（`OperationPlan` 统一操作词汇 + CBZ 字节生成）实施计划 · 夜间无人值守台账

> **面向 Agent 执行者**：本文件同时是**实施计划**与**夜间台账**。每轮开工先读本节 §0「行车规则」，
> 收工前更新 §3「轮次台账」并提交。上下文被压缩后，**本文件是唯一能自证进度的载体**。

**目标：** 补上 HANGOFF §9 D3 退出条件 1 的实测缺口 —— 全仓 `OperationPlan` 目前**零命中**
（只在 `HANGOFF.md:196` 与 `HANGOFF.md:2655` 出现），CBZ 打包在门槛表上仍标「⬜ 未交付」。
本轮把「计划词汇统一 + CBZ 字节生成」落成**可在正式工程承接的领域核心**，并单测通过。

**架构：** 只在 `pixfold-d1/domain/` 内演进（纯 Kotlin/JVM，零新增依赖）。ZIP 用 JDK 自带
`java.util.zip`；平台层将来只实现 `PageByteSource`（读字节）并负责落盘，**顺序 / 命名 / XML 位置 /
ZIP 结构全部归领域层**。既有公开契约（`VolumePackPlan`/`ExecutionReport`/`UndoRecord` 及其函数）
签名与语义**冻结**。

**技术栈：** 同 D1（Kotlin 2.4.20 / AGP 9.4.1 / JDK 17 / Gradle wrapper 9.8.0 / Robolectric 4.17）。
`domain` 模块零生产依赖（`domain/build.gradle.kts` 只有 `kotlin("test")`）。

**规格：** `HANGOFF.md` §5（统一领域模型 + 5 条关键原则）、§9 D3 节（重定义后的三条退出条件与其
归属澄清）、`docs/notes/d1-archive-domain-semantics.md`（§6.4/§6.5/§6.6 归档语义契约）、
`docs/notes/scripts-behavior-matrix.md`（§6.2/§6.3 参考脚本的 ZIP 与 XML 事实）。

**前置：** D1 的 P1–P7 已交付并合入 `dev`；`dev` tip = `9a20002`。本分支 `d3/p1` 从该提交开出。

---

## 0. 行车规则（每轮开工必读，压缩后尤其）

### 0.1 授权边界（用户 2026-10-04 批准）

| 项 | 值 |
| --- | --- |
| 分支 | `d3/p1`（从 `dev` 开出）。**只提交到本分支** |
| 推送 | ❌ **绝不推送**（push 是用户的活） |
| 合并回 `dev` | ❌ **绝不合并**。早上用户复核 diff 后自行处理 |
| 默认分支 | ❌ 不动 `main` / `ready`；不新建第二个分支 |
| 轮次上限 | **12**（`create_goal` 时设定；耗尽由 driver 记 `round-limit` 并停） |
| 工作面 | **只有** D3 打包核心：`OperationPlan` 统一操作词汇 + CBZ 字节生成 |

### 0.2 硬禁令（越界即停，写进 §4「待裁决」后继续下一项）

1. **不改 `pixfold-d1/app/**`** —— 一行都不改。
2. **不改任何 `res/values*/**.xml`**，**不新增 `TextKey`**。理由：新文案键会连带
   `PixFoldText.kt` 的穷尽 `when` + 中英双语资源 + lint `ExtraTranslation`/`UnusedQuantity`，
   把 lint 风险引进无人值守时段；且本轮无任何新增用户可见文案。
   确需新键 ⇒ **该轮停止**，写 §4「待裁决」。
3. **不新增依赖**；不动 `domain/build.gradle.kts` / `libs.versions.toml` / `.mise.toml` /
   `settings.gradle.kts` / `gradle.properties`。
4. **不连真机**：不跑 `adb`、不 `dumpui`、不装 APK。夜间工作面按 §7.4 表格第 1 行
   「领域逻辑 ✅ 领域单测」判定，真机项一律留白天。
5. **不做产品决策**：任何歧义（阈值、文案、交互取舍）不自行裁决。
6. **不改用户环境**：不碰 Codex / WSL / chezmoi / 电源设置 / 系统配置；不装任何东西。
7. **不启用** Auto review、Agent Teams、`teams`、`workflow`（本轮只有一个写者，并行只会引入写冲突）。
8. **不宣称**「D3 已完成」；**不改三处阶段快照**（`HANGOFF.md` §9.1 门槛表 / 项目
   `AGENTS.md` 头部 / `README.md` 状态行）。阶段未变，三处一致规则不触发 —— 本轮只在
   `HANGOFF.md` §9.1 **追加**小节。

### 0.3 四道闸门（Gradle 只能在 Windows 侧；绝不在 WSL 里跑 Gradle）

```
cmd.exe /c "D:\Programing\Personal\pixfold\pixfold-d1\run-gradle.bat :app:lintDebug :app:testDebugUnitTest :domain:test :app:assembleDebug"
```

- `timeoutMs` 给 900000（15 分钟）；超时转后台 job 用 `job_output` 收。
- **禁用 `--tests`**（cmd.exe 下会静默假绿 0 测试）。
- 测试数**只认 JUnit XML**，用系统 `python3` 解析（不是 `uv`，见 §0.5）：
  - `pixfold-d1/app/build/test-results/testDebugUnitTest/TEST-*.xml`
  - `pixfold-d1/domain/build/test-results/test/TEST-*.xml`
- ⚠️ **读 XML 前必须先重跑闸门** —— 遗留 XML 会把已修好的用例报成红（本项目已踩过：
  `SortPagePolishTest.column_menu_numbers_are_horizontally_centered` 在 2026-09-29 的 XML 里
  一直是红的，而代码早已修好）。
- 基线（2026-10-04 15:48 实测，`BUILD SUCCESSFUL in 55s`）：`app` 42 files / **399 tests**
  / 0F 0E；`domain` 20 files / **247 tests** / 0F 0E。`app` 的 `@Test` 数比 XML 多 1 属正常
  （`NegativeControlTest` 是故意失败的负向对照，默认排除）。
- lint 必须 **0 errors / 0 warnings**（`warningsAsErrors = true` + `abortOnError = true`）。
- `assembleDebug` 必须成功。

### 0.4 变异纪律

- **备份 + 变异 + 还原必须在同一次 bash 调用内完成**（`/tmp` 不跨调用保留）。
- 还原**一律 `cp` 备份**，**绝不用 `git checkout -- <file>`**（会连未提交改动一起丢）。
- 先确认旧串真的消失再解读结果，否则「存活」是假象。
- **变异能存活 ⇒ 断言没有杀伤力 ⇒ 删掉它，并写明为什么刻意不设这条断言。**
- **编译失败不是「捕获」**：脚本要先做锚点干跑校验。
- 还原用**整文件快照**，不要「把新串换回旧串」（新串可能在文件别处也出现）。

### 0.5 环境事实（避免重复踩坑）

- **`uv` 在 workspace-write 沙箱下不可用**（默认缓存 `~/.cache/uv` 被判只读，实测
  `error: Could not acquire lock / Read-only file system`）。夜间一律用系统 `python3`
  （3.13.5）。若确需 `uv run --with`，必须带 `UV_CACHE_DIR=$PWD/.uvcache`。
- 沙箱 = `workspace-write`，审批 = `ask` 且**无回答者 ⇒ fail closed**。所以夜间所有命令
  必须**零审批**：只写工作区内（`D:\Programing\Personal\pixfold\...` 映射到工作区）与 `/tmp`。
- Gradle 缓存不在 `~/.gradle`（该目录不存在），走 Windows 用户目录 —— 不碰。
- 不得写 `/sdcard` / `/storage/emulated`（真机相关，本轮也用不上）。
- `git push` 在这台机上走 `~/bin/win-ssh` 链路 —— 本轮**不需要**。

### 0.6 每轮收工动作（顺序不可换）

1. `git status --porcelain` 确认只有本轮预期文件；
2. `git add -A && git commit`（中文提交信息，沿用仓库习惯：`feat(d3): …` / `test(d3): …` / `docs(d3): …`）；
3. 若该轮有提交号需要回填，**再补一次** `docs(d3): 回填 … 的提交号`（仓库既有习惯）；
4. 更新本文件 §3「轮次台账」并提交；
5. 若该轮出现越界项，写进 §4「待裁决」。

---

## 1. 全局约束（沿用 D1 P2《全局约束》，本阶段相关要点）

1. **验收判据不可调整**：本轮覆盖 HANGOFF §9 D3 退出条件 1；不通过即记待修项。
2. **零三方依赖**：ZIP 用 JDK `java.util.zip`，测试用 `kotlin("test")` + JDK 标准库。
3. **领域层只产出「键 + 参数」**：`domain/` **不持有文案**。用户可见文本一律
   `Msg(TextKey, args)`；`domain/src/main/kotlin/com/pixfold/d1/domain/text/TextKey.kt`
   的枚举**只装键、不装译文**；解析只发生在 app 层 `ui/text/PixFoldText.kt`。
   ⚠️ `require { "…" }` 的断言消息**不是用户文案**，保持中文裸串（既有惯例）。
4. **计划优先于直接操作**（§5 关键原则 4）：任何改文件动作先产出可检查的计划。
5. **结果可追溯**（§5 关键原则 5）：记录原始标识、新标识、操作时间、策略、错误信息。
6. **每完成一个任务即提交**（只 commit，不 push）。
7. **`domain` 模块的测试注解用 `kotlin.test.Test`**（`app` 模块才用 `org.junit.Test`）。
8. **不动既有公开契约**：`VolumePackPlan.describe()` 的 5 条消息顺序
   （`docs/notes/d1-archive-domain-semantics.md:610-617`）、`ExecutionReport` 的
   `createdPath 非空 ⟺ Succeeded` 不变式、`UndoRecord` 的 `opCount` 语义 —— 全部冻结。
   重构只能让既有测试**零改动**地继续通过。

---

## 2. 文件结构

| 文件 | 职责 | 状态 |
| --- | --- | --- |
| `pixfold-d1/domain/src/main/kotlin/com/pixfold/d1/domain/plan/OperationPlan.kt` | **新**：`sealed interface PlannedOp` + `OperationPlan` 统一操作词汇 | W1 |
| `pixfold-d1/domain/src/main/kotlin/com/pixfold/d1/domain/pack/CbzWriter.kt` | **新**：ZIP 字节写入（STORED、`ComicInfo.xml` 结构上第 0 条） | W4 |
| `pixfold-d1/domain/src/main/kotlin/com/pixfold/d1/domain/pack/CbzWritePlan.kt` | **新**：`CbzWritePlan` + `PageByteSource` + `buildCbzWritePlan` + `writeCbz` | W5 |
| `pixfold-d1/domain/src/main/kotlin/com/pixfold/d1/domain/plan/Execution.kt` | 改：`execute(OperationPlan)` 驱动，旧函数保留为薄委托 | W2/W3 |
| `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/plan/OperationPlanTest.kt` | **新** | W1 |
| `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/pack/CbzWriterTest.kt` | **新** | W4 |
| `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/pack/CbzWritePlanTest.kt` | **新** | W5 |
| `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/plan/UndoAlignmentTest.kt` | **新** | W6 |
| `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/plan/PackingPipelineTest.kt` | **新**：跨层不变式（计划页数 ↔ 归档条目数、成功 ↔ 可写、计划行 ↔ 报告行、撤销 ↔ 已创建） | W8 |
| `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/plan/ExecutionReportTest.kt` | 改：W12 补四元组区分度用例（+26） | W12 |
| `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/plan/PackagePlanTest.kt` | 改：W12 补前导点边界用例（+20） | W12 |
| `pixfold-d1/artifacts/mutate_w8.py` + `artifacts/w8bak/` | 变异脚本与备份（`artifacts/` 已 gitignore，**不入库**） | W8 |
| `pixfold-d1/artifacts/mutate_w11.py` + `artifacts/w11bak/` | 变异脚本与备份（同样**不入库**） | W11 |
| `pixfold-d1/artifacts/mutate_w12.py` + `artifacts/w12bak/` | 变异脚本与备份（同样**不入库**） | W12 |
| `HANGOFF.md` | 改：§9.1 追加「D3 打包核心轮」小节（**只追加**） | W9 |
| `docs/superpowers/plans/2026-10-04-d3-p1-packing-core.md` | 本文件（台账） | 全程 |

**不动的文件**：`pixfold-d1/app/**`、任何 `res/values*/**.xml`、`domain/build.gradle.kts`、
`libs.versions.toml`、`.mise.toml`、`settings.gradle.kts`、`README.md`、`AGENTS.md`。

---

## 3. 轮次台账

> **每轮收工前更新本表**：状态、提交号、闸门读数（app/domain 测试数）、变异组数、阻塞与待裁决。
> 这张表是压缩后唯一能自证进度的载体 —— 不要攒批。

| # | 工作项 | 验收（可判定） | 状态 |
| --- | --- | --- | --- |
| W0 | 准备：开分支 `d3/p1` + 写本台账 + `create_goal(12)` | 分支存在、台账已提交、goal 已武装 | ✅ 完成（`8d1aa5d`；goal `goal-6d888f82-18f9-4bca-b0de-5181f7bba3ae`，armed / max 12） |
| W1 | `OperationPlan` 词汇落地：`sealed interface PlannedOp`（`PackagePlannedOp`/`RenamePlannedOp`）+ `data class OperationPlan(kind, ops, dangerActions)` 带 `total/skipped/runnable/counts/describe()`；`operationPlanOf(plans)` / `operationPlanOfRenames(ops)` | 红→绿单测；`describe()` **复用既有 `Pack*` 键，不新增 TextKey** | ✅ 完成（17 用例，`72232c1`） |
| W2 | `executePackagePlans` 改由 `OperationPlan` 驱动（`executePlan(plan): ExecutionReport`），旧函数保留为薄委托 | 既有 `PackagePlanTest`/`ExecutionReportTest` **零改动**全绿 | ✅ 完成（`72232c1`；`executePackagePlans` 现为一行委托） |
| W3 | `executeRenamePlan` 同上（`operationPlanOfRenames`） | 既有 `RenamePlan`/`ExecutionReport` 相关测试零改动全绿 | ✅ 完成（`72232c1`；撤销推导收成唯一实现 `undoOpsOf`） |
| W4 | `pack/CbzWriter.kt`：`write(comicInfoXml, pages, out, timeMillis)` —— **STORED**（依据 `scripts-behavior-matrix.md:267` 参考脚本用 `ZIP_STORED`）、`ComicInfo.xml` **结构上强制第 0 条**、条目名 UTF-8 | 单测用 `ZipInputStream` 回读：条目顺序、`getMethod() == STORED`、名称集合、内容逐字节相等 | ✅ 完成（20 用例，`55408ba`） |
| W5 | `pack/CbzWritePlan.kt`：`buildCbzWritePlan(volume)` 复用 `ComicInfo.build` + `renumberPages`；`interface PageByteSource { fun bytesOf(itemId: String): ByteArray? }` + `writeCbz(plan, source, out, timeMillis)` 返回 `CbzWriteResult(writtenEntries, missing)` | 单测：页序含人工调整、≥100 页跨 3→4 位补零、缺图 → `missing` 而非抛异常 | ✅ 完成（20 用例，`55408ba`） |
| W6 | `UndoRecord` / `ExecutionReport` 与新词汇对齐的全路径覆盖 | 单测：Package→`DeleteCreated`、Rename→`RestoreRenamed`、Skipped/Failed 不产生撤销项、`canUndo=false → null` | ✅ 完成（`UndoAlignmentTest` 16 用例，轮 3） |
| W7 | 变异清扫（W1–W6 合计 ≥ 20 组） | 0 逃逸；存活变异按 §0.4 处理并记录 | ✅ 完成：**23 个不同变异体 / 30 次运行，逃逸 0**；W7 自身等价变异仅 **P5 一个**（见 R1）。**曾存活 9 个（M5/M6/N5/P5/P14/P16/P17/P18/P20），其中等价 2、真正经「补断言」由存活转为捕获 7 个**（口径详见轮 3 小节加的 ⚠️ 校正） |
| W8 | 边界与不变式加固（空卷 / 单页 / 重名跳过 / `isBlank` 输出目录 / 冲突策略矩阵） | 新增断言 + 变异验证 | ✅ 完成（`PackingPipelineTest` 17 用例，轮 4；**16 组变异全捕获**，其中 4 组只可能由本轮新增用例捕获 ⇒ 补的是真缺口） |
| W9 | 文档回写：`HANGOFF.md` §9.1 追加「D3 打包核心轮」+ 本文件写「早间复核清单」 | **只追加**，不改阶段快照 | ✅ 完成（轮 5：`HANGOFF.md:2611` 起追加；台账 §5 早间复核清单同步补写） |
| W10 | 终验：干净工作树重跑四道闸门 + 计数比对 + `git log --oneline dev..d3/p1` 汇总 | 本文件写最终读数 | ✅ 完成（轮 6：**带 `clean` 的全量重建**，64/64 executed，exit 0；读数见 §6） |
| W11 | 余量加固（§3 余量政策）：条目名与元数据条目撞名守卫 + 新缺陷登记 | 先实测确认漏洞存在，再红→绿；新缺陷只登记不自行修复 | ✅ 完成（轮 7：`CbzWritePlan` 拒 `ComicInfo.xml` 撞名 + 2 用例；**7 组变异全捕获**；**登记 R3**） |
| W12 | 余量加固（§3 余量政策）：**广扫荡找缺口**——在 W7/W8/W11 未变异过的表达面上投 23 组变异，针对存活者补断言 | 存活变异必须补到全捕获，或按 §0.4 归为等价并写文档说明 | ✅ 完成（轮 8：23 组 → **21 捕获 / 2 存活 / 0 锚点缺失**，2 个存活者补断言后**各自成为该变异的唯一捕获者**；+2 用例 ⇒ domain **350** 项；生产代码零改动） |

**余量处理**：W1–W10 全部完成且仍有轮次 ⇒ **继续加固核心**（更多边界 / 更多变异 / 更多不变式），
**不越界**到未授权工作面（适配层检查、D2b、真机、UI）。

### 3.1 每轮记录（追加，不覆盖）

<!-- 格式：
#### 轮 N（YYYY-MM-DD HH:MM）— W<k> <一句话>
- 提交：`<sha>` <subject>
- 改动：<files> (+a/−b)
- 闸门：app <n> tests / domain <n> tests / 0F 0E / lint 0w / assembleDebug OK
- 变异：<m> 组，<k> 捕获，0 逃逸
- 结论 / 阻塞 / 待裁决：…
-->

#### 轮 0（2026-10-04 16:47）— 准备
- 分支：`d3/p1` 从 `dev`（`9a20002`）开出
- 台账：本文件建立
- goal：待 `create_goal(max_goal_rounds = 12)`（需用户当回合的直接消息授权 —— 见 §5 睡前准备）
- 阻塞：**`create_goal` 的权威检查要求「当前回合内有直接人类消息」**。开工这一回合只有
  `exit_plan_mode` 的批准事件，不构成直接人类消息。若 `create_goal` 被拒 ⇒ 本轮**不自行改成
  默认 256 轮**，按 §5 请用户发一条放行消息。
- 待裁决：无
- **结果**：`create_goal` **成功**（`exit_plan_mode` 的批准构成直接人类消息）——
  `goal.id = goal-6d888f82-18f9-4bca-b0de-5181f7bba3ae`、`revision = 1`、`phase = active`、
  `activation = armed`、`maxGoalRounds = 12`。上一条阻塞**已解除**。

#### 轮 1（2026-10-04 17:5x）— W1+W2+W3 统一操作词汇
- 提交：`72232c1` feat(d3): 统一操作词汇 OperationPlan（打包/改名共用一个执行入口，旧入口改薄委托）
- 改动：`plan/Execution.kt`（改，−34/+? 行：旧两个入口变一行委托、撤销推导收成唯一实现
  `undoOpsOf`）、`plan/OperationPlan.kt`（新，255 行）、`plan/OperationPlanTest.kt`（新，17 用例）
- RED 证据：`e: …/OperationPlanTest.kt:41 Unresolved reference 'operationPlanOf'`、
  `:209 Unresolved reference 'executePlan'`
- 闸门：`BUILD SUCCESSFUL in 48s` / `62 actionable tasks: 17 executed, 45 up-to-date` /
  app 42 files · 399 tests / domain 21 files · 264 tests / 0F 0E / lint 0 告警 /
  assembleDebug OK
- 变异：留到 W7 汇总（W1–W3 的断言在 W7 与 W4/W5 一起清扫）
- 设计决定（**后续勿推翻**）：
  1. `PlannedOp` 只有 `PackagePlannedOp` / `RenamePlannedOp` 两个实现 ——
     「清理空目录」是**计划级字段** `OperationPlan.dangerActions`，不造 `PlannedOp`。
     理由：给它造一条 op 会让计划项数凭空 +1，报告与计划行数对不上。
  2. `OperationPlan.init` 用 `require(ops.all { it.kind == kind })` 强制同质 ——
     撤销语义按 `ExecKind` 分（Package→`DeleteCreated`、Rename→`RestoreRenamed`），
     混装会用错的回滚方式。诊断消息保持中文字面量（不是用户文案）。
  3. 执行分支**复制**而非"桥接"：`executePackageOp` 内部直接读 `item.plan`，
     保证既有可观测行为逐字不变；旧入口只做一行委托。
  4. `RenamePlannedOp.describe()` 只能给「跳过原因 或 `Msg(TextKey.OpStatusRename)`」——
     改名侧没有逐项句子契约，且**不新增 TextKey**（§0.2 禁令 2）。
- 结论 / 阻塞 / 待裁决：无

#### 轮 2（2026-10-04 18:3x）— W4+W5 CBZ 字节生成与写入计划
- 提交：`55408ba` feat(d3): CBZ 字节生成与写入计划（CbzWriter + CbzWritePlan + PageByteSource）
- 改动：`pack/CbzWriter.kt`（新，128 行）、`pack/CbzWritePlan.kt`（新，139 行）、
  `pack/CbzWriterTest.kt`（新，20 用例）、`pack/CbzWritePlanTest.kt`（新，20 用例）
- RED 证据：`e: …/CbzWriterTest.kt:33 Unresolved reference 'CbzEntry'`、`:41 Unresolved reference 'CbzWriter'`；
  第二轮 `e: …/CbzWritePlanTest.kt:62 Unresolved reference 'PageByteSource'`、`:66 Unresolved reference 'CbzWritePlan'`、
  `:85 Unresolved reference 'buildCbzWritePlan'`（共 31 处未解析引用）
- 闸门：`BUILD SUCCESSFUL` / exit 0 / app 42 files · 399 tests（未变，正确）/ 
  domain 23 files · 305 tests / 0F 0E / lint 0 告警 / assembleDebug OK
- 变异：**23 个不同变异体 / 25 次运行，逃逸 0**（其中 1 个等价变异 M5 ⇒ 22 个被断言捕获）
  > ⚠️ **口径校正（轮 4 复核时发现）**：本节标题原先写"20 组，19 捕获，1 等价"，
  > 与下方的逐条枚举对不上 —— 逐条数出来是 6+7+10+2 = **25 次运行**，其中 M6 与 N5
  > 各重跑一次用于验证补断言是否生效，故**不同变异体 = 23 个**。
  > 差额来自"轮次"与"变异体"两个口径混用（第一轮 + 第二轮合计并不等于 20）。
  > HANGOFF 的 D3 小节已按下面这套逐条枚举为准重述，此处保留原始记录并加注。
  - W4 第一轮 6 组：M1 排序 ✓、M2 改 DEFLATED ✓、M3 忽略 timeMillis ✓、
    M4 去掉 require ✓、**M5 `toByteArray()` 平台默认编码 —— 存活**、
    **M6 `close()` 替 `finish()` —— 存活**
  - 因 M5/M6 存活而**补断言**：新增 `the caller's stream is left open`（用带 `closed` 标记的
    `TrackingStream`，因 `ByteArrayOutputStream.close()` 是空操作测不出）、
    `a finished stream is still usable by the caller`、`an empty entry name is rejected`、
    `a directory shaped entry name is rejected`、`entries compare by byte content rather than identity`
  - 补断言后第二轮 7 组：M5b 显式 `ISO_8859_1` ✓（⇒ M5 是**等价变异**：Gradle 测试 JVM 的
    `file.encoding` 就是 UTF-8，故 `toByteArray()` 与 `toByteArray(Charsets.UTF_8)` 在本机
    不可区分；断言本身有效，已用 M5b 证实）、M6 `close()` ✓、M7 去空名 require ✓、
    M8 去目录名 require ✓、M9 去自定义 equals ✓、M10 `contentEquals`→引用相等 ✓、
    M11 先写页后写 XML ✓
  - W5 第一轮 10 组：N1 写部分（非全有或全无）✓、N2 missing 按卷序而非计划序 ✓、
    N3 writtenEntries 漏 ComicInfo ✓、N4 去重名 require ✓、**N5 去空 XML require —— 存活**、
    N6 去 timeMillis require ✓、N7 用 `volume.pages` 而非 `pageOrder` ✓、
    N8 忽略 `renumberPages` 补零 ✓、N9 `ok` 恒真 ✓、N10 缺图时伪造 writtenEntries ✓
  - 因 N5 存活而**补断言** `an empty comic info xml is rejected`（并顺手把原
    `a plan with no pages still carries a usable comic info xml` 从 `assertNull` 反写法
    改成 `assertTrue(isNotEmpty())`，读起来才是正向断言）
  - 补断言后第二轮 2 组：N5 ✓（被新用例杀死）、N11 `ok` 恒假 ✓
- 设计决定（**后续勿推翻**）：
  1. **全有或全无**：任何一页取不到字节 ⇒ 一个字节都不写。理由：参考脚本
     `batch_pack_cbz.py` 遇到读不到的文件会抛 `IOError` 终止，**绝不**产出少页的 CBZ；
     若"能读到几页就写几页"，调用方会拿到一个**看起来完全正常**的 CBZ（能打开、
     ComicInfo 也在，只是页数少了）。
  2. **先全部探测再决定写不写**，探测顺序 = 计划顺序 —— 既让 `missing` 顺序可预期，
     也避免"边写边取"留下半成品。
  3. `timeMillis` 校验在 `writeCbz` 里**前置**（`CbzWriter.write` 里也有同一断言）——
     否则 `timeMillis = 0` + 缺图会静默返回「缺图」而不指出时间戳有问题。
  4. **重名检测放在 `CbzWritePlan`，不放 `CbzWriter`** —— 后者是字节原语，职责是把给定条目
     照原样写出来；重名与否是**计划的正确性**问题，该在有名字来源的那层判。
  5. `CbzWriteResult.writtenEntries` 是 `List<String>`（含 `ComicInfo.xml`）而非计数 ——
     报告必须能与归档实际内容逐条核对。
- 结论 / 阻塞 / 待裁决：无

#### 轮 3（2026-10-04 18:5x）— W6+W7 撤销全路径对齐 + 变异清扫
- 提交：`e82cdb9` test(d3): 撤销/报告全路径对齐 + 30 组变异清扫（28 捕获 / 2 等价）
  > 注：**提交标题里的数字按上表口径读作「30 次运行」**，不可与"不同变异体"混用
  > （不同变异体 = 23；且"2 等价"里的 M5 是 W4 遗留项，见本节 ⚠️ 校正）。
  > 提交标题是历史记录，**不改写已有的 commit message**，此处加注即可。
- 改动：`domain/src/test/kotlin/com/pixfold/d1/domain/plan/UndoAlignmentTest.kt`（新，16 用例）、
  `OperationPlanTest.kt`（+6 用例：runnable 真实读数、label 真实杀伤力、skipReason 契约、
  失败行回落原名、describe 三条路径）、`OperationPlan.kt`（**仅把 `executePackageOp` /
  `executeRenameOp` 里的 `v.id` / `op.id` / `v.cbzFileName` / `op.finalName` 换成
  `item.id` / `item.label`** —— 行为逐字等价，但让 `PlannedOp` 的接口字段真正承重）
- RED 证据：新增用例首次编译失败三处 `Unresolved reference 'NameProposal'`
  （`OperationPlanTest.kt:247/261/295`）+ `Unresolved reference 'aot2'` / `assertNull`
- 闸门：`BUILD SUCCESSFUL in 36s` / `62 actionable tasks: 11 executed, 51 up-to-date` /
  **app 42 files · 399 tests（未变，正确）/ domain 24 files · 328 tests / 0F 0E / lint 0 告警**
- 变异：**W7 共 23 个不同变异体 / 30 次运行，逃逸 0**（其中 1 个等价变异 P5 ⇒ 22 个被断言捕获）
  > ⚠️ **口径校正（轮 4 复核时发现）**：本节标题原先写"共 30 组，28 捕获，2 等价"，
  > 把三个口径混在了一起：①"30"是**运行次数**（20+10），而 P5 与 P11 各跑两次
  > （P5 第一批存活→第二批复测仍存活；P11 第一批锚点未命中→第二批修锚点后捕获），
  > 故**不同变异体 = 23 个**（P1–P20 + P16b + P20b + P20c）。②"2 等价"里的第二个其实是
  > 表内那一行 **M5，属 W4 遗留项的复验**，不是 W7 的变异体。③因此 W7 的等价变异只有 **P5 一个**。
  > HANGOFF 的 D3 小节已按下面这套逐条枚举为准重述，此处保留原始记录并加注。
  - 第一批 20 组（`/tmp/mutate_w7.py`）：13 捕获 / 6 存活 / 1 锚点缺失
    （P11 因换行缩进不匹配未命中，已在第二批修正锚点后捕获）
  - 第二批 10 组（`/tmp/mutate_w7b.py`）：9 捕获 / 1 存活
  - 逐条：
    | 编号 | 变异 | 结果 |
    | --- | --- | --- |
    | P1 | 打包/改名撤销语义对调 | 捕获（7 条用例） |
    | P2 | `undoOpsOf` 改读 `report.kind` | 捕获 |
    | P3 | 去掉 `canUndo` 守卫 | 捕获 |
    | P4 | 忽略 `createdPath`，所有项都进撤销 | 捕获（4 条） |
    | **P5** | **`RestoreRenamed` 的 `from`/`to` 对调** | **等价（见下 R1）** |
    | P6 | 去掉「跳过项不得带 createdPath」require | 捕获（3 条） |
    | P7 | 去掉「成功项必须带 createdPath」require | 捕获（2 条） |
    | P8 | `undoRecordFrom` 改读 `report.kind` | 捕获 |
    | P9 | `executePlan` 反转 op 顺序 | 捕获 |
    | P10 | `pageCount == 0` 判在 `skipped` 之前 | 捕获（8 条） |
    | P11 | 去掉同质 `require` | 捕获（第二批修锚点后） |
    | P12 | `operationPlanOf` 丢危险动作 | 捕获（2 条） |
    | P13 | `operationPlanOf` 硬编码 `Rename` | 捕获（32 条） |
    | P14 | `runnable = total + skipped` | 捕获（补断言后） |
    | P15 | `skipped` 数成"未跳过" | 捕获（2 条） |
    | P16 | `PackagePlannedOp.label` 改成 `outputDisplay` | 捕获（补断言后） |
    | P16b | `label` 改成卷 id | 捕获（同一条断言） |
    | P17 | `RenamePlannedOp.skipReason` 恒非空 | 捕获（补断言后） |
    | P18 | 失败行标签用 `finalName`（空串） | 捕获（补断言后） |
    | P19 | `undoRecordFrom` 永不返回 null | 捕获 |
    | P20 | `PackagePlannedOp.describe()` 返回空 | 捕获（补断言后） |
    | P20b | `RenamePlannedOp.describe()` 返回空 | 捕获（补断言后） |
    | P20c | `OperationPlan.describe()` 返回空 | 捕获（补断言后） |
    | M5 | `toByteArray()` 用平台默认编码（W4 遗留） | 等价（JVM `file.encoding=UTF-8`） |
  - **补断言前后的对比即"断言有杀伤力"的证据**：**曾经存活**的变异体共 9 个
    （M5、M6、N5、P5、P14、P16、P17、P18、P20），其中 M5 与 P5 是等价变异（原理上杀不掉），
    **其余 7 个（M6/N5/P14/P16/P17/P18/P20）全部靠补断言转为捕获**（M6 属 W4、N5 属 W5）。
    > ⚠️ **口径校正（轮 4 复核时发现）**：本行原先写"P14/P16/P16b/P17/P18/P20/P20b/P20c/P11
    > 共 9 组在补断言前存活"，把两类东西混了进来 —— **P16b/P20b/P20c 是在第二批首次引入、
    > 当场就被捕获**（并非"先存活后捕获"），**P11 是脚本锚点未命中**（换行缩进不匹配，
    > 属工具缺陷而非存活的变异体）。真正的"存活→补断言→捕获"是 7 个，不是 9 个。
- **R1（本轮新发现的既有缺陷，已登记待裁决，未改实现）**：
  `UndoOp.RestoreRenamed(from, to)` 在成功项上**恒有 `from == to`** ——
  `executeRenameOp` 的成功分支 `label = op.finalName` 且 `createdPath = op.finalName`，
  于是 `undoOpsOf` 推出 `RestoreRenamed("改后.jpg", "改后.jpg")`，是个**空操作**。
  - **不是本轮引入**：`git show 72232c1^:…/plan/Execution.kt` 显示重构前就是同一取值
    （`label = op.finalName` + `createdPath = op.finalName`），本轮的"零行为变化"要求
    恰好把它**原样保留**下来。
  - **为什么一直没暴露**：`performUndo` 恒返回 `applied = false`（D1 不触碰真实文件系统），
    UI 只显示「已记录 N 项」，从不消费 `from`/`to`；真机核对（HANGOFF:540 第 17 项）
    看到的也是同一句话。
  - **为什么不自作主张修**：正确取值要产品决策 —— 撤销记录该携带"原文件名"
    （需扩 `ExecEntry` 字段，或让 `undoRecordFrom` 从计划取
    `op.proposal.image.fileName`），还是 `from`/`to` 本就该是别的含义。按 §7.1 第 1 步
    「有歧义先请用户裁决」，且夜间无人值守，故**只记录、不动行为**。
  - **变异层面的后果**：P5（`from`/`to` 对调）因此是**等价变异**，不是断言没杀伤力。
    已把原先那条"钉死方向"的用例改写为
    `a successful rename undo currently collapses from and to onto the new name`
    + `the undo record cannot recover the original name from the report alone`，
    注释里写明这是**既有缺陷**而非期望行为；缺陷修复后该变异会被正向断言杀死。
- 设计决定（**后续勿推翻**）：
  1. `executePackageOp`/`executeRenameOp` 改从 `item` 取 `id`/`label` —— 让接口字段承重。
     这是**唯一**让 P16/P16b 这类变异有意义的前提：字段没人读，改错了也没人发现。
  2. `executeRenameOp` 的失败分支**刻意不用** `item.label`：最终名是空白，拿它当标签会
     在报告里显示空行，用户看不出是哪张图失败 —— 故回落到 `op.proposal.image.fileName`。
     该取舍已写成源码注释（守护 P18）。
  3. 新测试的破坏性读数必须**写死具体数字**（如 `runnable == 4`）而非复算
     （`total - skipped`）—— 后者与实现同构，等于没有断言。P14 证实了这一点。
- 结论 / 阻塞 / 待裁决：**R1 登记待裁决**（不阻塞后续 W8–W10，因为不改行为）

#### 轮 4（2026-10-04 17:2x）— W8 边界与跨层不变式加固

- 提交：`a22a12a`（`test(d3): 跨层不变式加固（W8：计划/报告/字节三层对齐 + 16 组变异全捕获）`）
- 目标：补上**层与层之间**的等式。到 W7 为止每层各自被钉死了（`PackagePlanTest` 管"计划说什么"、
  `ExecutionReportTest` 管"报告说什么"、`CbzWriterTest` 管"字节写成什么"），但**没有任何一条用例把三层连起来** ——
  "计划宣称 16 页 + 报告说成功 + 归档里只有 15 张"这种坏成品可以全绿通过。
- 新增 `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/plan/PackingPipelineTest.kt`（17 用例，首跑即绿）。
  该文件头部注释写明了「为什么单层测试不够」与「与既有用例的分工」，避免后续被当成重复用例删掉。
- 覆盖的四类不变式：
  1. **页数同源**：`PackPackPages` 行的实参 == 归档里除 `ComicInfo.xml` 外的条目数；
     `VolumePackPlan.pageCount` == `CbzWritePlan.pageCount`（对**全部五卷**）；
     人工删掉一页后宣称页数与条目数同步 −1（反向守卫，防止"两个页数都恒等于 `pages.size`"而假性相等）。
  2. **成功项真的写得出来**：报告里每个 `Succeeded` 的项，其卷都 `writeCbz` 成功且 `createdPath == plan.outputPath`；
     失败项必须 `createdPath == null` 且其写入计划确实为空页。
  3. **计划行 ↔ 报告行对齐**（3×2 全策略矩阵）：行数、id 序列、label 序列逐行相等；
     三类计数之和 == `plan.total`；`plan.skipped == report.skippedCount`。
  4. **撤销只指向已创建**：`DeleteCreated` 的路径集合 **恰好** == 报告 `createdPath` 集合；
     `RestoreRenamed` 只断言 `from`（`to` 的方向正是 R1 的对象，钉死它会在 R1 修复时假性变红）。
- **补上的真实覆盖缺口（变异证明见下）**：`ExecutionReportTest` 只用 `""` 测空输出目录，
  而 `isBlank()` 与 `isEmpty()` 在 `""` 上**同解** ⇒ 改成 `isEmpty()` 不会被任何既有用例发现。
  新增用例遍历 `""` / `" "` / `"   "` / `"\t"` / `"\n"` 五种纯空白。
  同理钉住**反向边界**：纯空白作者 `"   "` 在 XML 与计划行两侧都必须按"有值"处理
  （`isNullOrEmpty` 而非 `isBlank` 的刻意选择），空串则两侧都给占位（`<Writer />` + `PackValueEmpty`）。
- RED 证据：无（本项是**补断言**，实现未改 ⇒ 首跑即绿属预期）。
  故**不依赖"先红后绿"来证明断言有效**，改由 16 组变异全捕获来证明（见下）。
- 四道闸门：`BUILD SUCCESSFUL in 3s`，`62 actionable tasks: 4 executed, 58 up-to-date`，exit 0；
  XML `app: 42 files / 399 tests / 0F 0E`（**未变，正确** —— 本分支不碰 `app/**`）、
  `domain: 25 files / 345 tests / 0F 0E`（328 → 345，+17）。
- lint 读数复核（**重要**）：`:app:lintDebug` 的 analyze/report 任务长期 `UP-TO-DATE`，
  报告文件仍是 2026-09-29 的旧产物（`0 errors, 0 warnings, 4 hints`）。
  已用 `git diff --name-only dev...d3/p1 -- pixfold-d1/app/` 确认**本分支从未改动 `app/**`** ⇒
  UP-TO-DATE 是正确行为而非假绿；4 个 hint 均为 `AutoboxingStateCreation`（Jetpack Compose），
  hint 不计入 `warningsAsErrors`。故"lint 0 告警"结论成立，但**后续轮次不要把它当成"新鲜读数"引用**。
- 变异清扫 **16 组，16 捕获 / 0 存活 / 0 锚点缺失**（脚本 `pixfold-d1/artifacts/mutate_w8.py`，
  备份落 `pixfold-d1/artifacts/w8bak/`，两处均已 gitignore）：

  | # | 变异 | 作用文件 | 结果 | 捕获者 |
  | --- | --- | --- | --- | --- |
  | M1 | `v.outputDir.isBlank()` → `isEmpty()` | `plan/OperationPlan.kt` | 捕获 | **本文件新增**的 `a whitespace only output dir fails exactly like an empty one` |
  | M2 | `field` 的 `isNullOrEmpty()` → `isNullOrBlank()` | `comic/ComicInfoXml.kt` | 捕获 | **本文件新增**的 `a whitespace only writer is written verbatim on both sides` |
  | M3 | 计划行 `v.writer.isEmpty()` → `isBlank()` | `plan/PackagePlan.kt` | 捕获 | 同上 |
  | M4 | `v.pageCount == 0` → `v.pages.isEmpty()` | `plan/OperationPlan.kt` | 捕获 | 4 条（既有 `ExecutionReportTest` ×2 + 本文件） |
  | M5 | 写入计划改读 `volume.pages` 而非页序 | `pack/CbzWritePlan.kt` | 捕获 | 4 条（含本文件 `a volume whose page order was edited…`） |
  | M6 | 计划 `pageCount = v.pages.size` | `plan/PackagePlan.kt` | 捕获 | **仅**本文件 `a volume whose page order was edited still announces the edited count` |
  | M7 | `CbzWriter` 只写第一页 | `pack/CbzWriter.kt` | 捕获 | 7 条 |
  | M8 | 报告行逆序 | `plan/OperationPlan.kt` | 捕获 | 2 条（含本文件全矩阵） |
  | M9 | 报告丢弃失败行 | `plan/OperationPlan.kt` | 捕获 | 10 条 |
  | M10 | `createdPath` 用 `cbzFileName` 而非冲突改名后的 `outputPath` | `plan/OperationPlan.kt` | 捕获 | 4 条（含本文件 `every operation reported as succeeded…`） |
  | M11 | `skipped` 数成未跳过 | `plan/OperationPlan.kt` | 捕获 | 5 条 |
  | M12 | `runnable = total` | `plan/OperationPlan.kt` | 捕获 | 1 条 |
  | M13 | `canUndo` 恒真 | `plan/Execution.kt` | 捕获 | 5 条（含本文件 2 条） |
  | M14 | 打包撤销删 `label` 而非 `createdPath` | `plan/Execution.kt` | 捕获 | 3 条（含本文件） |
  | M15 | 改名撤销 `from` 改常量 | `plan/Execution.kt` | 捕获 | 4 条（含本文件） |
  | M16 | 丢掉 `included` 过滤 | `plan/PackagePlan.kt` | 捕获 | 3 条（含本文件） |

  **M1/M2/M3/M6 的捕获者只可能是本文件新增的用例** —— 这直接证明 W8 补的是真缺口，而不是重复断言。
- 设计决定（**后续勿推翻**）：
  1. 跨层断言只连"三层之间"，不重测单层细节 —— 单层细节已有归属文件，重复写会让后续改动时
     "改一处红三处"，掩盖真正失效的那条。
  2. `rename undo` 只钉 `from`，**刻意不钉 `to`** —— `to` 的方向是 R1 的待裁决对象；
     钉死会在 R1 按任一方案修复时把一条正确改动误报为回归。
  3. 变异脚本与备份移到 `pixfold-d1/artifacts/`（已 gitignore）而不是 `/tmp`：
     本次实测发现**写入工具的 `/tmp` 与 bash 的 `/tmp` 不是同一个命名空间**（`write` 落盘的文件
     bash 侧看不到），落工作区内的忽略目录才是可靠做法。
- 结论 / 阻塞 / 待裁决：无新增待裁决项（R1/R2 仍未决，不阻塞）。
  源码**零改动**（只新增测试文件），故本轮不存在行为变化风险。

---

#### 轮 5（2026-10-04 17:3x）— W9 文档回写（`HANGOFF.md` 只追加）

- 提交：`ea78b3c` docs(d3): 回写 HANGOFF §9.1「D3 打包核心轮」+ 校正变异计数口径（W9）
- 改动：`HANGOFF.md`（**仅追加一个小节**，插在 `### D1.1` 末尾 `**已提交** `3ca85b0`…` 之后、
  `### D2：平台能力验证`（`:2688` 附近）之前 —— 即 `HANGOFF.md:2611` 起的
  `#### ✅ D3 打包核心轮:统一操作词汇 + CBZ 字节生成(2026-10-04 分支 `d3/p1`,**未合并、未推送**)`）
  + 本台账（W9 状态、本轮记录、§6 自检结果、§3 口径校正）。
- **只追加的证据**：`git diff --numstat` 应为纯新增（`+N −0`），且 `git diff` 中**不得出现**
  `> 进度(` 行、门槛表 `| D3 |` 行、`## 9` 顶部任何行的改动。**阶段快照三处同步（`HANGOFF.md` §9 顶
  + `AGENTS.md` 头 + `README.md`）本轮一律不做** —— 那是"宣布阶段状态"的产品决策，超出授权。
- 小节内容（7 块）：性质声明（**只是 D3 退出条件 1 的领域层交付,不是 D3 阶段完成**）、动因
  （2026-09-19 重定义后打包是四类核心唯一未交付项）、交付面表（5 行）、**6 条关键设计决定**、
  验证读数（四道闸门 + domain 247→345 / app 399 未变）、`domain/` 零 Android 依赖说明
  （只用 `java.util.zip`/`java.io`，未改 `build.gradle.kts`，但**退出条件 2 的可执行检查仍未落地**）、
  **R1/R2 两处待裁决**、**未做/不宣称**清单（❌真机 ❌三项退出条件正式宣布 ❌门槛表/阶段快照三处同步
  ❌适配层可执行检查 ❌D2b 五项 ❌§11 第 12 条空间阈值 ❌`app/**` 一行未改）、lint 诚实例外、台账指引。
- **本轮顺带纠正了两处台账口径笔误（重要，属"诚实的验证读数"要求）**：
  1. 轮 2 小节标题「20 组」与自己的逐条枚举（6+7+10+2 = **25 次运行 / 23 个不同变异体**）对不上。
  2. 轮 3 小节标题「30 组，28 捕获，2 等价」把三个口径混用：30 是**运行次数**（20+10，其中 P5/P11 各跑两次），
     不同变异体 = **23**（P1–P20 + P16b + P20b + P20c）；且「2 等价」里的第二个是表内那行 **M5（W4 遗留复验）**，
     故 W7 自身等价变异只有 **P5 一个**。
  3. 轮 3 小节「共 9 组在补断言前存活」把**新引入即被捕获**的 P16b/P20b/P20c 与**锚点未命中的 P11**
     （工具缺陷，非存活变异体）混了进来 ⇒ 真相：**曾存活 9 个**（M5/M6/N5/P5/P14/P16/P17/P18/P20），
     其中等价 2（M5/P5），**真正"存活→补断言→捕获" = 7 个**（M6/N5/P14/P16/P17/P18/P20）。
  - 三处均已就地加 ⚠️ 注（**原始记录保留、不改写历史；提交标题里的历史数字也不改写**），
    并同步进 `HANGOFF.md` 的小节，口径统一为「**逐条枚举为准**」。
  - 累计口径（以逐条枚举为准）：**71 次运行 / 62 个不同变异体，逃逸 0**；其中 **2 个等价变异**
    ⇒ **60 个被断言捕获**。
- 闸门：文档轮，**未改任何 Kotlin**；仍按 §0.3 跑一次四道闸门确认未受影响（读数见 §6）。
- 设计决定（**后续勿推翻**）：
  1. **不改写历史数字**。已提交的 commit message 与既往轮次记录一律保留原样、就地加注 ——
     改写会让"当时的读数"消失，那正是最需要留痕的东西。
  2. `HANGOFF.md` 采用 `#### ✅ <主题>（日期）` 的既有格式，**不新造标题层级**。
  3. 小节里显式写「**不是 D3 阶段完成**」与「**未做/不宣称**」清单：文档回写最容易变成
     无声的阶段宣称，必须把边界写在同一个小节里，读者才不会误读。
- 结论 / 阻塞 / 待裁决：无新增待裁决项（R1/R2 仍未决，不阻塞）。

---

#### 轮 6（2026-10-04 17:4x）— W10 终验（**带 `clean` 的全量重建**）

- 提交：`6682986` docs(d3): W10 终验读数（带 clean 的全量重建 64/64 + 越界逐条核对）
- 改动：**仅本台账**（§3 W10 状态、本轮记录、`## 6. 自检结果` 全部填写）。
  **未改任何 Kotlin、未改 `HANGOFF.md`** ⇒ 本轮不可能影响闸门读数。
- 做法：前置条件 = 工作树干净（`git status --porcelain` 空、`git stash list` 空），
  然后跑 **`clean` + 四道闸门**，而不是直接跑四道闸门。
  > **为什么加 `clean`**：前几轮的闸门读数大量命中 `up-to-date`，尤其 `:app:lintDebug`
  > 的报告文件长期停在 **2026-09-29 的旧产物**（§3.1 轮 5 已记录该"诚实例外"）。
  > 加 `clean` 后 **64/64 任务全部真实执行**（`64 actionable tasks: 64 executed`），
  > 由此得到的才是**全新鲜读数**，也顺便**关闭了 lint 陈旧读数这个悬置疑点**。
- 读数（详见 §6.2）：
  - `BUILD SUCCESSFUL in 56s` / exit 0 / **64 actionable tasks: 64 executed**（无 up-to-date 命中）；
  - lint **0 errors, 0 warnings, 4 hints**（XML 逐条复核：4 条**全为 Hint** 级
    `AutoboxingStateCreation`，hint 不计入 `warningsAsErrors`）；**报告 mtime 2026-10-04 17:44 ⇒ 新鲜**；
  - JUnit XML：`domain` **25 files / 345 tests / 0F 0E**、`app` **42 files / 399 tests / 0F 0E**；
  - 源码 `@Test` 计数：`domain` **345**（与 XML 一致）、`app` **400**（比 XML 多 **1** ⇒
    正是被 `exclude("**/NegativeControlTest.class")` 默认排除的 `NegativeControlTest`，
    **399 是正确的，不是丢测**）；
  - 产物 `app/build/outputs/apk/debug/app-debug.apk` = 33,385,168 字节（mtime 17:43）。
- 越界核对（§6.3）：`git diff --stat dev...d3/p1` = **11 files / +2678 / −74**，
  11 条路径**逐条在白名单内**；`git diff --name-only dev...d3/p1 -- pixfold-d1/app/` = **0 条**；
  `domain/build.gradle.kts`/`libs.versions.toml`/`app/build.gradle.kts`/`.mise.toml`/`AGENTS.md`/`README.md`
  逐条 `git diff --quiet` 确认未改；`git ls-tree` 确认**无残留变异备份**入库。
- 设计决定（**后续勿推翻**）：
  1. **终验必须带 `clean`**。只跑四道闸门很容易得到一串 `up-to-date`，那只证明"缓存还在"，
     不证明"当前代码真的能过"；`clean` 后 `64/64 executed` 才有资格当终验读数。
  2. **计数一律解析 JUnit XML**，不看 Gradle 控制台的汇总行；并**用源码 `@Test` 计数交叉核对**，
     把已知的 1 项排除（`NegativeControlTest`）显式写进台账 —— 否则下次读者会把 399↔400 当成丢测。
  3. **不写死提交数**（用 `git rev-list --count dev..d3/p1`）：每次回填提交都会 +1，
     写死必然立刻过期（§6.1 已如此标注）。
- 结论 / 阻塞 / 待裁决：无新增待裁决项（R1/R2 仍未决，不阻塞并回）。

---

#### 轮 7（2026-10-04 17:5x）— W11 余量加固：条目名撞名守卫 + R3 登记

- 提交：`2fe36ce` fix(d3): 条目名与元数据条目撞名守卫（W11 余量加固 + 登记 R3）
- 改动：3 files / +93 / −0（**纯增量，零删除**）
  - `pixfold-d1/domain/src/main/kotlin/com/pixfold/d1/domain/pack/CbzWritePlan.kt`（+8）
  - `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/pack/CbzWritePlanTest.kt`（+38）
  - `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/plan/UndoAlignmentTest.kt`（+47）
  - 工具：`pixfold-d1/artifacts/mutate_w11.py` + `artifacts/w11bak/`（`.gitignore:9` 已忽略，**不入库**）
- 闸门：`BUILD SUCCESSFUL in 32s` / exit 0 / `62 actionable tasks: 14 executed, 48 up-to-date`；
  lint **0 errors, 0 warnings, 4 hints**；JUnit XML `domain` **25 files / 348 tests / 0F 0E**
  （345 → 348，**+3**）、`app` **42 files / 399 tests / 0F 0E**（**未变，正确**）；
  源码 `@Test` 计数交叉核对 `domain` **348**（与 XML 一致）、`app` **400**（−1 `NegativeControlTest` = 399）。
- 变异：**7 组，7 捕获，0 逃逸，0 锚点缺失**（N1–N7，脚本 `artifacts/mutate_w11.py`，
  跑完逐文件 sha256 核对还原一致）。

##### 本轮修的真漏洞：页名 == `ComicInfo.xml`

`CbzWritePlan` 的 `init` 里**原本只有**「页与页重名」检查。但 `CbzWriter` **无条件**
把 `ComicInfo.xml` 写在第 0 条 ⇒ 只要某页的条目名也叫 `ComicInfo.xml`，就会产出
**两个同名条目**：ZIP 结构依然合法，阅读器取到哪一个不确定（可能把图片当元数据，
也可能反过来）。这正是那条重名检查要防的**同一类静默错误**，只是调用方
**不必自己把这个名字写进 `pages`** 就会撞上，所以那条检查看不见它。

**先实测再动手**（不靠推理下结论）：临时探针 `ZzProbeTest` 实跑输出的
`PROBE-B collision accepted=true` 证实漏洞存在，随后**立即 `rm -v` 删除探针**
（`git status --porcelain` 已确认干净）。RED 证据（`--rerun-tasks`，exit=1）：

```
CbzWritePlanTest > a page named like the comic info entry is rejected() FAILED
    org.opentest4j.AssertionFailedError at CbzWritePlanTest.kt:312
346 tests completed, 1 failed
BUILD FAILED in 5s
```

注意是 `AssertionFailedError` 而**不是**预期中的异常类型不符 ⇒ **根本没有异常被抛出**，
撞名确实被静默接受。修复（`CbzWritePlan.kt`，紧随既有的重名 `require` 之后）：

```kotlin
val collision = pages.filter { it.fileName == CbzWriter.COMIC_INFO_ENTRY }.map { it.fileName }
require(collision.isEmpty()) {
    "页的条目名与元数据条目 ${CbzWriter.COMIC_INFO_ENTRY} 撞名（会产出两个同名条目）：$collision"
}
```

两条新用例：`a page named like the comic info entry is rejected`（撞名必须被拒，
且报错点明撞的是元数据条目名）+ `only the exact comic info name collides`
（**守卫的精度**：`comicinfo.xml` / `ComicInfo.png` / `001-ComicInfo.xml` 都**不该**被拒 ——
ZIP 条目名区分大小写，不能顺手扩大打击面，否则用户真有一张叫 `ComicInfo.png` 的图就打不了包）。
第二条不是凑数：变异 N2/N3/N4 的唯一捕获者就是它（见下表）。

##### 本轮登记的新缺陷：R3（**只记录，不自行修复**）

用探针 `PROBE-A` 实测确认：`OutputConflictPolicy.Overwrite` + 输出**已存在**时 ——

```
PROBE-A outputExists=true
PROBE-A report=[ExecEntry(id=op-102, label=海贼王 第102卷.cbz, outcome=Succeeded, ..., createdPath=E:\漫画库\_output/海贼王 第102卷.cbz)]
PROBE-A undo=[DeleteCreated(path=E:\漫画库\_output\海贼王 第102卷.cbz)]
```

报告报 `Succeeded`，`createdPath` 取的却是**本该被覆盖的那个旧文件**的路径，撤销据此
推导出 `DeleteCreated(该路径)` —— 可那个文件**不是本次创建的**，是被**替换**的。
真要执行撤销，会**删掉用户原有的 CBZ**，而被覆盖掉的内容已经找不回来。

值得注意：危险动作机制**已经认识**这个场景（`DangerAction.kt` 用
`conflictPolicy == Overwrite && volume.outputExists` 判定 `OverwriteExisting`，界面上会专门
警告一次），**但撤销推导走的是另一条路径，从不查策略**。属产品决策（「覆盖的撤销」
到底是 ①当作新建 ⇒ 删掉 ②覆盖前备份 ⇒ 撤销时恢复 ③覆盖项干脆不可撤销），
按 §7.1 第 1 步「有歧义先请用户裁决」⇒ **只记录、不动行为**，登记为 **R3**。

诚实记录用例 `an overwritten file is recorded as created and so undo would delete it`
（`UndoAlignmentTest.kt`）钉住当前行为并写明"修复后此断言应改变"，
另含**反向守卫**：同一卷换成 `Skip` 策略就没有撤销记录 ⇒ 说明问题只出在 `Overwrite`
这一个分支上，不是"凡是已存在的输出都记错"。

##### 变异表（7 组，全捕获）

| # | 变异 | 位置 | 结果 | 捕获者 |
| --- | --- | --- | --- | --- |
| N1 | 撞名守卫整个失效（`collision = emptyList()`） | `pack/CbzWritePlan.kt` | 捕获 | `a page named like the comic info entry is rejected` |
| N2 | 判定放宽成 `contains` | 同上 | 捕获（**唯一**） | `only the exact comic info name collides` |
| N3 | 判定放宽成 `endsWith` | 同上 | 捕获（**唯一**） | 同上 |
| N4 | 判定改成 `ignoreCase = true` | 同上 | 捕获（**唯一**） | 同上 |
| N5 | `require` 变成 `if (false) require(...)`（守卫写了但不生效） | 同上 | 捕获 | `a page named like the comic info entry is rejected` |
| N6 | 成功项的 `createdPath` 改用 `outputDisplay` | `plan/OperationPlan.kt` | 捕获（5 用例） | 含本轮新用例 `an overwritten file is recorded as created...` |
| N7 | 打包撤销恒返回 `null` | `plan/Execution.kt` | 捕获（11 用例） | — |

**N2/N3/N4 的唯一捕获者都是本轮新增的精度用例** ⇒ 补的是真缺口；若只写"撞名被拒"
那一条，把判定放宽成 `contains` 也不会有任何用例变红。

- 结论 / 阻塞 / 待裁决：**新增 R3 待裁决**（见 §4）；R1/R2 仍未决，均不阻塞。
  本轮**未新增 TextKey**、**未改 `app/**`**、**未改构建脚本**、**未新增依赖**。

---

#### 轮 8（2026-10-04 18:0x）— W12 余量加固：广扫荡找缺口（**不再靠猜**）

- 提交：`（见下方回填行）` test(d3): 补两处未覆盖表达面（W12 广扫荡 23 组 → 2 存活 → 全捕获）
- 改动：2 files / **+46 / −0**（**纯断言增量，生产代码零改动**）
  - `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/plan/ExecutionReportTest.kt`（+26）
  - `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/plan/PackagePlanTest.kt`（+20）
  - 工具：`pixfold-d1/artifacts/mutate_w12.py` + `artifacts/w12bak/`（`.gitignore:9` 已忽略，**不入库**）
- 闸门：`BUILD SUCCESSFUL in 3s` / exit 0 / `62 actionable tasks: 4 executed, 58 up-to-date`；
  lint **0 errors, 0 warnings, 4 hints**；JUnit XML `domain` **25 files / 350 tests / 0F 0E**
  （348 → 350，**+2**）、`app` **42 files / 399 tests / 0F 0E**（**未变，正确**）；
  源码 `@Test` 计数交叉核对 `domain` **350**（与 XML 一致）、`app` **400**（−1 `NegativeControlTest` = 399）。

##### 本轮方法：把"猜缺口"换成"广扫荡"

W7/W8/W11 都是**先怀疑某处有问题、再去验证**。本轮换成反向做法：在 W7/W8/W11
**尚未变异过的表达面**上一次性投 **23 组**变异（Q1–Q23），让测试自己告诉我哪里有洞 ——
脚本先做锚点唯一性检查（命中 ≠1 次直接记 `ANCHOR-MISS`，不当成"存活"），
每组跑完立即 `cp` 还原，收尾逐文件 sha256 核对。

覆盖面：报告汇总四元组（5 组）· `outputDisplayOf` 边界（3）· XML 字段与转义（4）·
重编号（4）· 归档写入（3）· 改名侧汇总与危险动作（4）。

**结果：21 捕获 / 2 存活 / 0 锚点缺失。** 两个存活者都是**真缺口**（非等价变异）：

| # | 变异 | 位置 | 首轮 | 补断言后 |
| --- | --- | --- | --- | --- |
| Q1 | `ExecSummary` 的**成功/跳过两个实参对调** | `plan/Execution.kt` | ★存活★ | 捕获 |
| Q6 | `outputDisplayOf` 的 `dot <= 0` 放宽成 `dot < 0` | `plan/PackagePlan.kt` | ★存活★ | 捕获 |

其余 21 组（Q2/Q3/Q4/Q5/Q7–Q23）首轮即被既有断言捕获，**未新增用例**。

##### 存活者 Q1：为什么四元组写对了却钉不住

既有的 `all three outcomes can appear in one report` 断言的是
`Msg(TextKey.ExecSummary, listOf(3, 1, 1, 1))` —— 看似已经钉死。但用例构造的是
"1 卷正常 / 1 卷冲突跳过 / 1 卷无页失败"，**成功数与跳过数都等于 1**，
于是把这两个实参对调，四元组**一个字节都不变**。这不是断言无效，
是**用例数据的区分度不够**：四个数里只要有两个相等，对应位置的交换就不可见。

新增 `summary keeps the four counts in their own positions`：构造
**6 卷 → 成功 3 / 跳过 2 / 失败 1**（四个数两两不等），断言 `listOf(6, 3, 2, 1)`。
补充说明：原有的 `summary counts add up to the number of entries` 用的是
**自洽性**（三项之和 == 总数），对调后依然自洽 ⇒ 它也杀不死 Q1，这是必然的，不是缺陷。

##### 存活者 Q6：`dot <= 0` 的另一半没人管

`outputDisplayOf` 的 `if (dot <= 0)` 一次性管着两种"没有扩展名"的形态：
**完全没有点**（`lastIndexOf` 返回 −1）与**点在最前面**（返回 0）。
既有用例 `rename policy appends the suffix even when there is no extension` 只钉了前者
（输入 `"无扩展名"`），后者**一条都没有** ⇒ 判定放宽成 `dot < 0` 时，
`".cbz"` 会走进"有扩展名"分支，产出 `".cbz"` → **`" (1).cbz"`**（以空格开头、
在 Windows 上是**非法/易被资源管理器吞掉**的文件名）。

前导点形态**可达**，不是造出来的边界：`defaultCbzName` 在标题建议为空串时给出
`"$t.cbz"` ⇒ `"".cbz"` = `".cbz"`；用户在 B 侧元数据页也能把 CBZ 名改成任意文本
（`MetaField.CbzName` → `MetaValue.Text`）。新增用例 `rename policy does not strip a leading dot`
先用 `defaultCbzName("fallback-id", Suggestion("", …), Suggestion(null, …))` 断言
产物确实是 `".cbz"`（**证明这个输入不是臆造的**），再断言
`outputDisplayOf(".cbz", Rename, outputExists = true) == ".cbz (1)"`。

##### 重跑确认（两个存活者都转为捕获）

按 §0.4 补断言后**重跑原变异**（同一脚本能力，锚点唯一性检查通过，逐文件 sha256 还原一致
`Execution.kt: ebcfcee593e3` / `PackagePlan.kt: 0f7dc9bd1f43`）：

```
[捕获] Q1 ExecSummary 成功/跳过两个数对调
     ExecutionReportTest > summary keeps the four counts in their own positions() FAILED
[捕获] Q6 dot<=0 -> dot<0
     PackagePlanTest > rename policy does not strip a leading dot() FAILED
```

⇒ 两个新用例各自是**该变异的唯一捕获者**，补的是真缺口而非重复断言。

- 结论 / 阻塞 / 待裁决：本轮**未新增待裁决项**（Q1/Q6 都属"断言不够"而非产品语义分歧，
  无需用户裁决）；R1/R2/R3 仍未决，均不阻塞。
  本轮**未改生产代码**、**未新增 TextKey**、**未改 `app/**`**、**未改构建脚本**、**未新增依赖**。

---

## 4. 待裁决 / 阻塞（越界项写这里，不自行决定）

> 出现下列任一情况，**停止该项**并在此登记，然后继续**下一项**工作：
> 需要新产品决策、需要新增 `TextKey`/资源、需要改 `app/**`、需要真机结论、
> 需要新增依赖、需要产品阈值（如 HANGOFF §11 第 12 条的空间不足阈值）。

| # | 轮次 | 事项 | 为什么不能自行决定 | 状态 |
| --- | --- | --- | --- | --- |
| R1 | 3（W6） | `UndoOp.RestoreRenamed(from, to)` 在成功项上恒 `from == to`（空操作）—— `executeRenameOp` 成功分支的 `label` 与 `createdPath` 都取 `op.finalName`，报告里**无处**携带原文件名 | 修法有两种且语义不同：①扩 `ExecEntry` 加原名字段；②让 `undoRecordFrom(plan, report)` 从计划取 `op.proposal.image.fileName`（只有它手里有计划）。选哪种、以及 `from`/`to` 的最终语义，都属撤销记录的产品决策；且本轮契约是"既有可观测行为逐字不变" | ⏸ 待用户裁决（不阻塞 W8–W10） |
| R2 | 3（W7） | `RenamePlannedOp.describe()` 在无原因时只能给 `Msg(TextKey.OpStatusRename)` 一句状态词 | 改名侧**没有**逐项句子契约（`RenamePlanPage` 渲染的是"原名 → 新名"两列 + 状态标签）；要补齐就得**新增 TextKey**，而本轮明令禁止 | ⏸ 待用户裁决（当前行为已够用，非缺陷） |
| R3 | 7（W11） | `OutputConflictPolicy.Overwrite` + 输出已存在时，报告报 `Succeeded`、`createdPath` = **被替换的那个旧文件**路径，撤销据此推导 `DeleteCreated(该路径)` ⇒ 真执行会**删掉用户原有的 CBZ**，且被覆盖的内容无法恢复 | 「覆盖的撤销」是什么，属产品决策，三种语义完全不同：①当作新建 ⇒ 删掉（现状，会把用户旧文件也删了）②覆盖前先备份 ⇒ 撤销时恢复备份（要给 `ExecEntry` 加"被覆盖路径"字段）③覆盖项标为不可撤销（要把 `OverwriteExisting` 传进 `undoOpsOf`）。**危险动作机制已认识此场景**（`DangerAction.kt` 判 `OverwriteExisting`），只有撤销推导从不查策略。按 §7.1「有歧义先请用户裁决」，故只记录 | ⏸ 待用户裁决（不阻塞余量轮） |

**已知的、明确不碰的开放项**（不要在本轮"顺手"处理）：
- HANGOFF §11 第 12 条：Android 端存储不足的**阈值与提示形态**，明写「待 D2b 真机验证后定」。
- D3 退出条件 2（`domain/` 零 Android 依赖的**可执行检查**）：用户本轮**未选中**，属未授权工作面。
- 摩擦清单 §4（B 侧 `PackPreviewPage.kt:243-332` 策略选择与计划行的因果距离）、
  §7（`MetadataPage.kt` 754 行）—— 都属 UI 层，本轮禁区。

---

## 5. 睡前准备与早间复核（给用户）

### 5.1 睡前（约 2 分钟）

1. 确认工作树干净、`dev` 在 `9a20002`（或你已知的 tip）。
2. **发一条放行消息**（例如「按计划开工」）—— 必需的，因为 `create_goal` 要求当前回合有
   直接人类消息；我会在同一回合开 `d3/p1`、写台账、`create_goal(..., 12)`。
3. 可选：`powercfg /change standby-timeout-dc 0`（实测交流下 `STANDBYIDLE = 0x00000000`
   即永不睡眠，且本机无电池）。
4. **别重启 WSL / 别关掉承载 DSH 的进程** —— resume/fork 后 goal 会被解除武装且**不会自愈**，
   夜间就此结束（这是硬上限，不存在自动恢复）。
5. 睡前不要再改仓库里的文件（每轮以「工作树干净」为前提）。

### 5.2 早间复核清单

1. `git log --oneline dev..d3/p1` —— 与 §3.1 每轮记录交叉核对。
2. `git diff dev...d3/p1 --stat` —— 确认改动面**只在 `domain/**` + `HANGOFF.md` + 本文件**。
   **若出现 `app/**` 或 `res/values*/**.xml` ⇒ 越界，需逐条查。**
3. `git status --porcelain` / `git stash list` —— 确认无遗留未提交。
4. 亲自跑一次四道闸门（§0.3）。
5. 读 §3.1 与 §4，决定：并回 `dev`、还是打回某几轮。
6. **未做**：真机核对（本轮工作面是领域层，按 §7.4 表第 1 行判定，不需要真机）；
   D3 退出条件的「正式宣布达成」（那是白天的判断）。

---

## 6. 自检结果

### 6.1 终验环境

- 分支：`d3/p1`（`git rev-parse --abbrev-ref HEAD`）。
- 提交数：**以 `git rev-list --count dev..d3/p1` 的实时输出为准，此处不写死数字** ——
  终验当时读到 **9**；此后 W10 自身的提交（`6682986`）与本次回填提交各 +1，现已更多。
  这正是"不写死"的理由：每一次文档提交都会让写死的数字立刻过期。
- 工作树：`git status --porcelain` 空、`git stash list` 空（终验**在干净工作树上跑**）。
- 时间：`2026-10-04 17:43–17:45 (+08:00)`。

### 6.2 四道闸门（**带 `clean` 的全量重建**）

```
cmd.exe /c "D:\Programing\Personal\pixfold\pixfold-d1\run-gradle.bat clean \
  :app:lintDebug :app:testDebugUnitTest :domain:test :app:assembleDebug"
```

- 读数：**`BUILD SUCCESSFUL in 56s`**，**`64 actionable tasks: 64 executed`**，**exit 0**。
  > ⚠️ 这次刻意加了 `clean`：此前几轮的闸门多为 `up-to-date` 命中，
  > 且 `:app:lintDebug` 的报告文件长期停留在 **2026-09-29 的旧产物**
  > （详见 §3.1 轮 5 与 HANGOFF 的"lint 诚实例外"）。`clean` 后 **64/64 全部真实执行**，
  > 读数不再是缓存命中 ⇒ 这是整套验证里**唯一一次"全新鲜"的读数**。
- lint：`0 errors, 0 warnings, 4 hints`（XML 逐条复核：4 条 **全为 `Hint`** 级
  `AutoboxingStateCreation`，位置 `app/src/main/kotlin/…/ui/components/DragReorderGrid.kt:175,176`
  与 `…/DragReorderList.kt:94,95`）。hint 不计入 `warningsAsErrors`；
  报告 mtime **2026-10-04 17:44**（新鲜）。
- 测试计数（JUnit XML 解析，非 Gradle 控制台）：

  | 模块 | 文件数 | 用例数 | 失败 | 错误 | 基线对照 |
  | --- | --- | --- | --- | --- | --- |
  | `domain` | 25 | **345** | 0 | 0 | 起点 20 files / 247 tests ⇒ **+98** |
  | `app` | 42 | **399** | 0 | 0 | 起点 42 / 399 ⇒ **未变（正确）** |

  - `app` 源码 `@Test` 计数 = **400**，比 XML 多 **1** —— 多出的正是 `NegativeControlTest`，
    被 `app/build.gradle.kts` 的 `unitTests.all { exclude("**/NegativeControlTest.class") }`
    默认排除。**因此 399 是正确的，不是丢测。**
  - `domain` 源码 `@Test` 计数 = **345**，与 XML **完全一致**（无排除项）。
  > ⚠️ 上表是 **W10 终验那一刻**的读数，为历史记录，**不回改**。此后余量轮继续加断言：
  > 轮 7（W11）+3 ⇒ **348**，轮 8（W12）+2 ⇒ **350**（见 §3.1 轮 7/轮 8 与 §6.5）。
  > `app` 始终 **399** 未变（本分支一行未改 `app/**`）。
- 产物：`app/build/outputs/apk/debug/app-debug.apk`（33,385,168 字节，mtime 17:43）⇒ `assembleDebug` 真实产出。

### 6.3 改动面与越界核对

- `git diff --stat dev...d3/p1` = **11 files changed, 2678 insertions(+), 74 deletions(-)**。
- 路径白名单逐条核对（**11 条全在允许面内**）：`HANGOFF.md`、本台账、
  `domain/…/plan/OperationPlan.kt`、`domain/…/pack/CbzWriter.kt`、`domain/…/pack/CbzWritePlan.kt`、
  `domain/…/plan/Execution.kt`，以及 5 个 domain 测试文件。
- **越界检查全部通过**：
  - `git diff --name-only dev...d3/p1 -- pixfold-d1/app/` ⇒ **0 条**（本分支一行未改 `app/**`）；
  - `domain/build.gradle.kts`、`libs.versions.toml`、`app/build.gradle.kts`、`.mise.toml`、
    `AGENTS.md`、`README.md` ⇒ 逐条 `git diff --quiet` 确认**均未改动**。
- **无残留变异备份**：`git ls-tree -r --name-only d3/p1 | grep -Ei '\.(bak|orig|rej)$|w7bak|w8bak|~$'`
  ⇒ 无命中；`pixfold-d1/artifacts/` 被 `pixfold-d1/.gitignore:9` 忽略（`git check-ignore -v` 已证实），
  变异脚本与备份**未入库**。

> ⚠️ 上面这段是 **W10 终验那一刻**的读数（**11 files / 2678 insertions**），为历史记录，**不回改**。
> 余量轮的增量见各轮小节：轮 7（W11）**+3 files / +93 / −0**；轮 8（W12）**+2 files / +46 / −0**。
> 当前实时读数以 `git diff --stat dev...d3/p1` 为准（结论不变：**改动面只有 `domain/**` + `HANGOFF.md` + 本台账**，
> `pixfold-d1/app/**` 始终 **0 条**）。

### 6.4 提交拓扑（`git log --oneline dev..d3/p1`）

> **不写死条数**（终验当时为 9；W10 提交与回填提交各 +1）—— 以命令输出为准。
> 截至 W9/W10 落地的链条如下（最新在上）；**轮 7（W11）与轮 8（W12）的提交与回填见各自小节**：

| 提交 | 类型 | 内容 |
| --- | --- | --- |
| `（轮 8 回填）` | docs | 台账回填轮 8 提交号 |
| `（轮 8）` | test | W12：广扫荡 23 组 → 2 存活 → 补断言（+2 用例 ⇒ domain 350） |
| `（轮 7 回填）` | docs | 台账回填轮 7 提交号 + R3 登记 |
| `2fe36ce` | fix | W11：条目名撞名守卫 + R3 诚实记录 |
| `901baf1` | docs | 台账回填 W9/W10 提交号 |
| `6682986` | docs | W10：终验读数（带 `clean` 的全量重建 64/64 + 越界逐条核对） |
| `ea78b3c` | docs | W9：`HANGOFF.md` §9.1 追加「D3 打包核心轮」+ 变异计数口径校正 |
| `4359761` | docs | 台账回填轮 4 |
| `a22a12a` | test | W8 跨层不变式（17 用例 / 16 变异全捕获） |
| `9e1fd41` | docs | 台账回填轮 3 |
| `e82cdb9` | test | W6+W7 撤销全路径对齐 + 变异清扫 |
| `15cc10e` | docs | 台账回填 W1–W5 |
| `55408ba` | feat | W4+W5 CBZ 字节生成与写入计划 |
| `72232c1` | feat | W1–W3 统一操作词汇 `OperationPlan` |
| `8d1aa5d` | docs | 夜间计划与台账 |

**未推送、未合并 `dev`**（`git log origin/dev..d3/p1` 与本地一致；是否并回由用户决定）。

### 6.5 结论与明确的"未做"

- **完成**：W0–W12 全部 ✅（§3 状态表）；D3 退出条件 1 所要求的**打包领域核心**
  （`PackagePlan`/`OperationPlan` 语义、`ExecutionReport`、`UndoRecord`）已落地并有 **350** 项单测覆盖。
  **余量轮 7（W11）另加**：条目名撞名守卫（真漏洞，已修）+ 2 用例；
  **余量轮 8（W12）另加**：广扫荡 23 组变异 → 2 存活 → 补断言后全捕获 ⇒ 又 +2 用例（348 → **350**）。
- **未做 / 不宣称**（与 HANGOFF 小节一致，勿据此认为 D3 已完成）：
  1. ❌ **真机核对** —— 工作面 100% 领域层，按 §7.4 表第 1 行判定不需要；
  2. ❌ **D3 三项退出条件的正式宣布**；
  3. ❌ **门槛表/阶段快照的同步改动**（需 `HANGOFF.md` §9 顶 + `AGENTS.md` 头 + `README.md` 三处
     一致修改，属产品决策）；本分支对 `AGENTS.md`/`README.md` **零改动**已核对；
  4. ❌ **退出条件 2 的"适配层零 Android 依赖"可执行检查**（如 Gradle 依赖断言）—— 未授权，未做；
  5. ❌ D2b 五项、§11 第 12 条空间阈值。
- **待用户裁决**（§4）：**R1** 撤销 `from == to` 空操作（既有缺陷，非本轮引入，修法二选一）；
  **R2** 改名计划描述缺逐项句子（补齐需新增 `TextKey`，本轮禁止）；
  **R3**（轮 7 新登记）`Overwrite` 策略下撤销会去删**用户原有的文件**（修法三选一，属产品决策）。
  **三项均不阻塞余量轮。**
  **轮 8（W12）未新增待裁决项** —— Q1/Q6 两个存活变异都属"断言区分度不够"，
  语义本身没有分歧（四元组顺序、前导点归属在代码里都是确定的），故直接补断言，无需用户裁决。
- **诚实性说明**：②③两项本可"顺手写上"，但那属于**替他做产品决策**，按 §7.1 第 1 步**只记录不改**。

