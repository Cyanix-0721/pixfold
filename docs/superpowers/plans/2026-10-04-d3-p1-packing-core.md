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
| `pixfold-d1/artifacts/mutate_w8.py` + `artifacts/w8bak/` | 变异脚本与备份（`artifacts/` 已 gitignore，**不入库**） | W8 |
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
| W7 | 变异清扫（W1–W6 合计 ≥ 20 组） | 0 逃逸；存活变异按 §0.4 处理并记录 | ✅ 完成：**合计 30 组，28 捕获 / 2 等价**（等价项理由均写进台账：M5 编码无关、P5 见 R1）。其中 9 组经「补断言」后由存活转为捕获 |
| W8 | 边界与不变式加固（空卷 / 单页 / 重名跳过 / `isBlank` 输出目录 / 冲突策略矩阵） | 新增断言 + 变异验证 | ✅ 完成（`PackingPipelineTest` 17 用例，轮 4；**16 组变异全捕获**，其中 4 组只可能由本轮新增用例捕获 ⇒ 补的是真缺口） |
| W9 | 文档回写：`HANGOFF.md` §9.1 追加「D3 打包核心轮」+ 本文件写「早间复核清单」 | **只追加**，不改阶段快照 | ⬜ |
| W10 | 终验：干净工作树重跑四道闸门 + 计数比对 + `git log --oneline dev..d3/p1` 汇总 | 本文件写最终读数 | ⬜ |

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
- 变异：**20 组，19 捕获，1 等价**
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
- 改动：`domain/src/test/kotlin/com/pixfold/d1/domain/plan/UndoAlignmentTest.kt`（新，16 用例）、
  `OperationPlanTest.kt`（+6 用例：runnable 真实读数、label 真实杀伤力、skipReason 契约、
  失败行回落原名、describe 三条路径）、`OperationPlan.kt`（**仅把 `executePackageOp` /
  `executeRenameOp` 里的 `v.id` / `op.id` / `v.cbzFileName` / `op.finalName` 换成
  `item.id` / `item.label`** —— 行为逐字等价，但让 `PlannedOp` 的接口字段真正承重）
- RED 证据：新增用例首次编译失败三处 `Unresolved reference 'NameProposal'`
  （`OperationPlanTest.kt:247/261/295`）+ `Unresolved reference 'aot2'` / `assertNull`
- 闸门：`BUILD SUCCESSFUL in 36s` / `62 actionable tasks: 11 executed, 51 up-to-date` /
  **app 42 files · 399 tests（未变，正确）/ domain 24 files · 328 tests / 0F 0E / lint 0 告警**
- 变异：**W7 共 30 组，28 捕获，2 等价**
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
  - **补断言前后的对比即"断言有杀伤力"的证据**：P14/P16/P16b/P17/P18/P20/P20b/P20c/P11
    共 9 组在补断言前**存活**，补断言后全部被捕获。
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

## 4. 待裁决 / 阻塞（越界项写这里，不自行决定）

> 出现下列任一情况，**停止该项**并在此登记，然后继续**下一项**工作：
> 需要新产品决策、需要新增 `TextKey`/资源、需要改 `app/**`、需要真机结论、
> 需要新增依赖、需要产品阈值（如 HANGOFF §11 第 12 条的空间不足阈值）。

| # | 轮次 | 事项 | 为什么不能自行决定 | 状态 |
| --- | --- | --- | --- | --- |
| R1 | 3（W6） | `UndoOp.RestoreRenamed(from, to)` 在成功项上恒 `from == to`（空操作）—— `executeRenameOp` 成功分支的 `label` 与 `createdPath` 都取 `op.finalName`，报告里**无处**携带原文件名 | 修法有两种且语义不同：①扩 `ExecEntry` 加原名字段；②让 `undoRecordFrom(plan, report)` 从计划取 `op.proposal.image.fileName`（只有它手里有计划）。选哪种、以及 `from`/`to` 的最终语义，都属撤销记录的产品决策；且本轮契约是"既有可观测行为逐字不变" | ⏸ 待用户裁决（不阻塞 W8–W10） |
| R2 | 3（W7） | `RenamePlannedOp.describe()` 在无原因时只能给 `Msg(TextKey.OpStatusRename)` 一句状态词 | 改名侧**没有**逐项句子契约（`RenamePlanPage` 渲染的是"原名 → 新名"两列 + 状态标签）；要补齐就得**新增 TextKey**，而本轮明令禁止 | ⏸ 待用户裁决（当前行为已够用，非缺陷） |

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

（W9/W10 收工时填写）
