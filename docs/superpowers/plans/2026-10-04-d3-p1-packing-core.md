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
| `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/comic/ComicMetadataTest.kt` | 改：W13 补预览一致性/空串清理用例（+38） | W13 |
| `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/sort/PageOrderTest.kt` | 改：W13 补固定项无重复/未知 id 用例（+30） | W13 |
| `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/plan/DangerActionTest.kt` | 改：W13 补覆盖清单排除已跳过卷用例（+24） | W13 |
| `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/plan/ConfirmFlowTest.kt` | 改：W13 补未开始态用例（+10） | W13 |
| `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/sort/NaturalOrderTest.kt` | 改：W14 补中文数字非法串/单边数字/空记号用例（+24） | W14 |
| `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/naming/NamingWarningsTest.kt` | 改：W14 补警告参数取值与冲突谓词用例（+73） | W14 |
| `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/workflowa/WorkflowAStateTest.kt` | 改：W14 补固定项 × 规则变更与 `pinnedCountTotal` 用例（+40） | W14 |
| `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/settings/PixFoldSettingsTest.kt` | 改：W14 补 `predictiveBackAnimation` 默认值断言（+1） | W14 |
| `pixfold-d1/artifacts/mutate_w8.py` + `artifacts/w8bak/` | 变异脚本与备份（`artifacts/` 已 gitignore，**不入库**） | W8 |
| `pixfold-d1/artifacts/mutate_w11.py` + `artifacts/w11bak/` | 变异脚本与备份（同样**不入库**） | W11 |
| `pixfold-d1/artifacts/mutate_w12.py` + `artifacts/w12bak/` | 变异脚本与备份（同样**不入库**） | W12 |
| `pixfold-d1/artifacts/mutate_w13.py` + `artifacts/w13bak/`（复核用 `mutate_w13b.py` + `artifacts/w13bbak/`） | 变异脚本与备份（同样**不入库**） | W13 |
| `pixfold-d1/artifacts/mutate_w14.py` + `artifacts/w14bak/`（复核用 `mutate_w14b.py`） | 变异脚本与备份（同样**不入库**） | W14 |
| `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/model/SourceItemTest.kt` | **新**：`fileName` / `relPath` / `sizeLabel` 三处显示口径与两个单位阈值 | W15 |
| `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/model/SortTest.kt` | **新**：`SortRule` 两条 `require` 与 `MAX_LEVELS` 上界 | W15 |
| `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/model/MetadataTest.kt` | **新**：`Suggestion.hasValue` / `pendingIssues` / `pageCount` / 卷覆盖标志 | W15 |
| `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/text/MsgTest.kt` | **新**：`Msg` 的参数列表契约（位置、类型、相等性） | W15 |
| `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/settings/SettingsStoreTest.kt` | **新**：`InMemorySettingsStore` 的存取、初值与实例隔离 | W15 |
| `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/workflowb/WorkflowBStateTest.kt` | **新**：`WorkflowBState` 的全部 12 个公开入口（此前**零领域断言**） | W15 |
| `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/naming/NamingWarningsTest.kt` + `…/naming/RootDirFollowTest.kt` | 改：W15 各补 1 条字面量阈值 / 跟随标志用例（+1 / +1） | W15 |
| `pixfold-d1/artifacts/mutate_w15.py` + `artifacts/w15bak/`（复核用 `mutate_w15b.py` + `artifacts/w15bbak/`） | 变异脚本与备份（同样**不入库**） | W15 |
| `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/text/TextKeyMappingTest.kt` | **新**：12 张「枚举 → `TextKey`」映射表 + `DangerAction` 文案三元组**逐项钉死**（此前领域测试对这批键的引用计数全为 0） | W16 |
| `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/plan/OperationPlanTest.kt` | 改：W16 补「跳过的改名操作报原因而非状态词」用例（+1） | W16 |
| `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/plan/RenameLibraryPlanTest.kt` | 改：W16 补 `collectionNames` 取值与 `counts` 三项用例（+2） | W16 |
| `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/pack/CbzWriterTest.kt` | 改：W16 补 `CbzEntry.toString` 契约用例（+1） | W16 |
| `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/naming/NamingEvalTest.kt` | 改：W16 补 `clearOverride` 只清一个 id 的用例（+1） | W16 |
| `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/comic/ComicMetadataTest.kt` | 改：W16 补 `includedVolumes` 只留勾选卷的用例（+1） | W16 |
| `pixfold-d1/artifacts/mutate_w16.py` + `artifacts/w16bak/`（复核用 `mutate_w16b.py`） | 变异脚本与备份（同样**不入库**） | W16 |
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
| W13 | 余量加固（§3 余量政策）：**第二次广扫荡**——换 5 个文件（`ComicInfoXml` / `ConfirmFlow` / `DangerAction` / `PageOrder` / `MetaEdit`）投 39 组变异，覆盖 W7/W8/W11/W12 都没碰过的页序校验、确认流边界、危险清单、固定项落位、元数据批量写入 | 存活变异必须补到全捕获，或按 §0.4 **给出可复核的等价性证明**并写文档说明 | ✅ 完成（轮 9：39 组 → **27 捕获 / 12 存活 / 0 锚点缺失**；12 个存活者判定为 **8 真缺口 + 4 已证等价**，8 个真缺口补断言后**全部转为捕获**且每条的捕获者是**它自己的新用例**；4 个等价者重跑**仍存活**，与证明一致；+7 用例 ⇒ domain **357** 项；生产代码零改动） |
| W14 | 余量加固（§3 余量政策）：**第三次广扫荡**——再换 8 个文件（`NaturalOrder` / `SortItems` / `NamingEval` / `Warnings` / `RenamePlan` / `NamingSummary` / `WorkflowAState` / `PixFoldSettings`）投 **58 组**变异，覆盖前三次都没碰过的自然序解析、排序字段、命名求值、警告参数、改名计划谓词、命名摘要、批次规则与设置默认值 | 存活变异必须补到全捕获，或按 §0.4 **给出可复核的等价性证明**并写文档说明 | ✅ 完成（轮 10：58 组 → **43 捕获 / 15 存活 / 0 锚点缺失 / 0 编译失败**；15 个存活者判定为 **13 真缺口 + 2 已证等价**，13 个真缺口补断言后**全部转为捕获**且每条捕获者是**它自己的新用例**；2 个等价者（N4/R2）重跑**仍存活**，与证明一致；+10 用例 ⇒ domain **367** 项；生产代码零改动） |
| W15 | 余量加固（§3 余量政策）：**第四次广扫荡**——目标面换成此前六次（W7/W8/W11/W12/W13/W14）**从未投过变异**的整个 `model/` 包 + 从零覆盖的 `workflowb/`，共 9 个文件投 **64 组**变异，覆盖 `hasWarningOnly`/`effectiveScheme` 谓词、`hasValue` 三态、`needsConfirmation`、`isOverridden`、`SortRule` 两条守卫、`sizeLabel` 两个阈值、`relPath`/`fileName`、`WorkflowBState` 全部 12 个入口、内存设置存储与 `Msg` 参数契约 | 存活变异必须补到全捕获，或按 §0.4 **给出可复核的等价性证明**并写文档说明 | ✅ 完成（轮 11：64 组 → **32 捕获 / 32 存活 / 0 锚点缺失 / 0 编译失败**；32 个存活者判定为 **31 真缺口 + 1 已证等价**（N6），31 个真缺口补断言后**全部转为捕获**且每条捕获者是**它自己的新用例**；1 个等价者重跑**仍存活**，与证明一致；+39 用例 ⇒ domain **406** 项；生产代码零改动） |
| W16 | 余量加固（§3 余量政策）：**第五次广扫荡（收尾轮）**——按 AST 精算出的历史覆盖表，挑历史变异组数最少的文件与**零测试引用**的表达面，共 23 个文件投 **66 组**变异，重点覆盖 12 张「枚举 → `TextKey`」映射表、`DangerAction` 文案三元组、`comicVolumeOf`/`setIncluded`/`includedVolumes`/`updateComponent`/`clearOverride`/`containsIllegalChar`/`compareNaturalTokens` 等生产有调用但测试零引用的函数 | 存活变异必须补到全捕获，或按 §0.4 **给出可复核的等价性证明**并写文档说明 | ✅ 完成（轮 12：66 组 → **42 捕获 / 24 存活 / 0 锚点缺失 / 0 编译失败**；24 个存活者判定为 **23 真缺口 + 1 已证等价**（C5），23 个真缺口补断言后**全部转为捕获**；1 个等价者重跑**仍存活**，与证明一致；+19 用例 ⇒ domain **425** 项；生产代码零改动） |
| W17 | **R1 修复（用户裁决「细说 R1」后授权）**：让撤销记录能恢复原文件名 —— `ExecEntry` 增 `originalPath`（+ 第三条不变量）、`executeRenameOp` 成功分支从 `op.proposal.image.fileName` 填、`undoOpsOf` 的 Rename 分支改取它作 `to` | 红→绿；`from` = 新名（起点）、`to` = 原名（终点）**不同源**；变异验证 0 逃逸 | ✅ 完成（轮 13：**6 组变异全捕获 / 0 存活 / 0 锚点缺失 / 0 编译失败**；两条"如实记录缺陷"用例翻成正向断言 ⇒ +3 用例，domain **428** 项；生产代码 +26 行） |
| W18 | **R2 补齐（用户裁决「改吧」后授权新增 `TextKey`）**：改名侧逐项句子契约 —— 新增 `TextKey.RenamePlanRename` + 双语 `domain_rename_plan_rename` + `PixFoldText.resId()` 映射；`RenamePlannedOp.describe()` 未跳过项改给「原名 → 新名」，跳过项无原因时回落 `OpStatusSkip`（原为 `OpStatusRename`，等于说"会重命名"却跳过） | 红→绿；**首次改 `app/**`**（资源 + 映射）；变异含**资源内容**变异（占位符数量/方向/重复）且必须全捕获 | ✅ 完成（轮 14：**10 组变异全捕获 / 0 存活 / 0 锚点缺失 / 0 编译失败**；两遍结论一致；domain **429**、app **402**；生产代码 +5 文件 含 2 个双语资源） |
| W19 | **R3 修复（用户 `m04079` 第③项授权）**：覆盖的撤销给出**三选一警告** —— 领域层新增 `UndoScope{ThisRun,Everything}` + `UndoOp.DeleteOverwritten`（与 `DeleteCreated` 类型可分）+ `ExecutionReport.overwrittenCount/needsUndoWarning` + `VolumePackPlan.overwritesExisting`（计划层唯一判据，报告计数与撤销推导共用）；UI 底栏撤销按钮在**有覆盖项时先弹框**（三个按钮各带色：两个执行档实心红、取消无底色 `TextButton`、**取消排在最后**），`onUndo` ⇒ `onUndoScope: (UndoScope) -> Unit`（**无默认值**，逼每个调用点表态） | 红→绿；**安全档绝不删用户原有文件**；变异含**资源内容**变异（占位符/重复文案/中文侧）且必须全捕获 | ✅ 完成（轮 15：**31 组变异两遍一致全捕获 / 0 存活 / 0 锚点缺失 / 0 编译失败**；第一遍曾 3 存活 + 2 编译失败，全部归因为**我的测试/变异设计错**并补强后转捕获 —— 其中 3 处是真测试缺口；domain **431**、app **420**；生产 5 文件 含 2 个双语资源） |
| W20 | **R4 修复（用户 `m04079` 第④项「想一想怎么修」）**：让页码自检真的能报出不一致 —— `validatePageConsistency` 此前拿 `renumberPages(volume).size` 跟 `volume.pageOrder.order.size` 比长度，而 `renumberPages` 的返回长度**就是** `order.size`（等于列表与自身比，恒等）⇒ `pageIssues` 恒空、`consistent` 恒真 ⇒ `PackPreviewPage.kt:204` 的告警分支**不可达**。改为比「卷内登记的页 `volume.pages`」与「页序里排的页 `pageOrder.order`」：长度不同报 `PageIssueCountMismatch`，长度相同但成员不同报**新增键** `PageIssueMissingFromOrder`（+ 双语资源 + `resId()` 映射）—— 判据是**集合/成员**而非位置，故人工拖拽（只改 `order` 不改 `images`）不会误报。补零交叉校验**保留**：它在 `renumberPages` 与校验侧各写一次，是同一规则的两份**独立拷贝**，只改坏一处即被捕获（非自证） | 红→绿；两类检查各自的两个来源必须**不同源**；置换不变性必须有断言；变异含**资源内容**变异（占位符数量/方向/中英抄错）且必须全捕获 | ✅ 完成（轮 16：**21 组变异两遍一致全捕获 / 0 存活 / 0 锚点缺失 / 0 编译失败**；第一遍 7 存活 + 0 编译失败，全部归因为**我的测试设计错**并补强后转捕获 —— 其中 5 处是真测试缺口（同源期望值盲区）、2 处是我自己的等价变异设计错；**同时发现并修正一条既有测试**：`page preview follows the page order, not the raw page list` 原先断言「分叉卷也一致」，那是骑在坏检查上的；现在改为断言必须报出分叉。domain **434**、app **425**；生产 3 文件 含 2 个双语资源） |
| W21 | **i18n 自动化（用户 `m04079` 第⑤项）**：补「键 → 译文」的结构契约测试，并复查既有 i18n 是否要调整 —— 新增 `app/src/test/…/ui/text/TranslationCoverageTest.kt`（5 条契约，**不经 `resId()`**，改按命名约定 `TextKey.FooBar → domain_foo_bar` 独立反查资源，同进程内中英对拍：①逐键覆盖 ②占位符下标+类型+顺序逐位对等 ③渲染逐字相等 ④复数档由数量参数选中 + **各档占位符同形** ⑤跨语言可辨 + 键间不撞句，两个白名单逐字写死）；另有只读审计脚本（`artifacts/i18n_audit.py` + `i18n_table.tsv` + `i18n_render.tsv`，107 键 × 两语言） | 红→绿；**必须证明新测试有杀伤力** —— 专门复活 W20 曾存活的 4 类变异（占位符删/对调/中英抄错），且必须全捕获 | ✅ 完成（轮 17：**16 组变异两遍** —— 第一遍 **15 捕获 / 1 存活**（M14 复数 `one` 档占位符被删：期望值与实际值**同源**，是 W20 记录的同源期望值盲区**第三次命中**），补一条「复数各档占位符同形」契约后第二遍 **16 捕获 / 0 存活 / 0 锚点缺失 / 0 编译失败**，3 个文件还原**逐字节一致**；app **425 → 430**（+5，新建 1 个测试文件）；**生产代码零改动**） |
| W22 | **第⑥项：警告图标去 emoji，改用矢量图标（用户 `m00673`）**：把 `⚠️ `/`⛔ ` 两类**警示前缀**从文案里彻底移除（13 个键：5 个 `TextKey` + 6 个纯资源键 + 1 个 `<plurals>` + **2 个去前缀后只剩裸 `%1$s` 的退化键，直接删除**），改由**矢量图标**承担视觉警示 —— 新建 2 个 `vector` drawable（`ic_warning_triangle` 实心警告 / `ic_forbidden` 空心禁止，**靠形状不靠颜色**区分）+ `app/src/main/kotlin/com/pixfold/d1/ui/text/WarnIcon.kt`（`WARN_ICONS: Map<Int, Int>` **按资源 id 索引** —— 13 键横跨 `TextKey`/纯资源键/plurals 三种命名空间，只有资源 id 全覆盖；`TextKey` 那半经 `resId()` 折入同一张表，避免两处漂移；另有 `@Composable WarnLine`）+ 调用点 7 处接线（`PlanComponents` / `ExecutionReportPage` / `NamingPage` / `RenamePlanPage` / `PackPreviewPage` / `ProposalTable`）；**`· `/`→ `/`← ` 符号前缀按仓库既有裁决不动** | 红→绿；**必须证明守卫有杀伤力** —— ①新建 `EmojiFreeSourceTest`（扫 `res/` 29 个 XML + `app/src/main/kotlin` 43 个文件）在改前必须真红、改后必须绿；②图标**长得对**不能只断言"节点存在"（像素级渲染断言）；③变异含**图标几何退化**（子路径缺失 ⇒ 退化成实心块）且必须全捕获 | ✅ 完成（轮 18：**21 组变异两遍 / 0 逃逸** —— 第一遍 8 捕获 / 2 存活，存活者 M6/M7 = 删 `fillType="evenOdd"`，**已证等价**（`grid_base.txt` 与 `grid_mut.txt` 整幅 24×24 网格 `cmp` **逐字节 IDENTICAL**：三角与感叹号绕向相反 ⇒ `nonZero`/`evenOdd` 同值），第二遍改测 KDoc 写明的**真正失败模式**（子路径缺失 ⇒ 退化成实心块）4 捕获 / 0 存活；9 个备份文件与工作树**逐文件 sha256 SAME**；domain **434**（不变）、app **430 → 456**（+23 新用例 +3 既有）；**真机 10 个警示面 + i18n 中英对照全部通过**；**新发现 R2 键在生产路径不可达 ⇒ §4 新增 R5**） |
| W23 | **R4 真机取证 + R5 裁决落账（用户 `m02554`「这2个应该怎么办」）**：R5 —— 用户在三个选项里选 ①**保持现状**（键 / 双语资源 / `resId()` 映射 / 相关测试**一律不动**，台账记为「无生产出口，刻意」）；R4 —— 打包页那条 `page_issues` 告警在真机上**从未出现过**（`grep -l '页码与页序不一致' artifacts/*.xml` 零命中），为取证**临时注入分叉卷探针**（只改 mock 数据 `domain/…/mock/MockLibraryBData.kt`：让 `aot-1` 的 `pages` 比 `pageOrder.order` 少 1 项）→ 构建 → 装机 → **真机截到告警**（中/英各一次）→ 换回干净包做**反向对照**（告警 0 命中、页码行精确上移 56 px = 图标行高度被回收）→ **完整还原源码**（sha256 与备份一致）→ 重建 → 重装 → 设备回基线 | ①探针**只动 mock 数据**，不得碰生产判据；②取证后源码必须**逐字节还原**（sha256 比对 + `R4_PROBE` 残留计数 0）；③反向对照必须证明**告警消失且布局回流**，而非仅"没看到"；④R5 是产品决策，只按用户选择记录，**不接通也不删除** | ✅ 完成（轮 19：中文告警逐字 `页码与页序不一致：页码项数与页序长度不一致（15 ≠ 16）`、英文 `Page numbers do not match the page order: Page item count does not match the page order (15 ≠ 16)`，两次都带 16dp 矢量图标节点；干净包反证 **0 命中** + `text="1"` 行 `[42,2199]` → `[42,2143]` 上移 **56 px**；`R4_PROBE` 残留 **0** 处、`MockLibraryBData.kt` sha256 `0e558ce028918759…` = 干净值；重建 APK **33,437,589 字节 / sha256 `1a2164ea74a29857…`** 与基线**逐字节相同**；四闸门 domain 32/434、app 50/456、lint 0/0/4、`RC=0`；**生产可达性不变** —— 仍无 UI 入口能把 `pages` 与 `pageOrder` 弄分叉 ⇒ 本轮验的是**渲染链**，不是"生产可见性"；**R5 ✅ 已裁决、§4 已无待裁决项**） |
| W24 | **`HANGOFF.md` §9.1 订正 + 追记（用户 `m03250`「文档都回写了吗」）**：唯一权威文档的 D3 小节仍是轮 5（W9，2026-10-04）写的，此后 **34 个提交一行未进** ⇒ 就地加注订正 **6 处失真**（标题「未合并、未推送」/「共 8 个提交」「只在 `domain/**`」/「真机核对…判定不需要真机」/「`app/**` 一行未改」/ lint 诚实例外整段 /「是否并回由用户决定」）+ **追加追记小节**（R1–R5 五项裁决、i18n 契约、警示图标矢量化、真机 2 轮、变异 401 组 / 0 逃逸、合回 `dev`、未做/不宣称）。**不改写历史数字，只就地加注** | `git diff --numstat` = `131 0` 且无删除行；两 hunk 均在 §9.1 内；`AGENTS.md` / `README.md` unchanged；阶段快照三处逐字未动 | ✅ 完成（`768bec7`；闸门 `62/62 executed`、domain 434 / app 456 / 0F 0E / lint 0-0-4） |

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

- 提交：`3c34c75` test(d3): 补两处未覆盖表达面（W12 广扫荡 23 组 → 2 存活 → 全捕获）
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

#### 轮 9（2026-10-04 18:2x）— W13 余量加固：第二次广扫荡（换文件、换表达面）

- 提交：`52dd0a7` test(d3): 补五处未覆盖表达面（W13 广扫荡 39 组 → 12 存活 → 8 真缺口全捕获）
- 改动：4 files / **+102 / −0**（**纯断言增量，生产代码零改动**，`git diff --name-only` 只列测试文件）
  - `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/comic/ComicMetadataTest.kt`（+38）
  - `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/sort/PageOrderTest.kt`（+30）
  - `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/plan/DangerActionTest.kt`（+24）
  - `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/plan/ConfirmFlowTest.kt`（+10）
  - 工具：`pixfold-d1/artifacts/mutate_w13.py` + `artifacts/w13bak/`，复核用 `mutate_w13b.py` + `artifacts/w13bbak/`
    （`.gitignore:9` 已忽略，**不入库**）
- 闸门：`BUILD SUCCESSFUL in 4s` / exit 0 / `62 actionable tasks: 4 executed, 58 up-to-date`；
  JUnit XML `domain` **25 files / 357 tests / 0F 0E 0S**（350 → 357，**+7**）、
  `app` **42 files / 399 tests / 0F 0E**（**未变，正确**：本分支一行未改 `app/**`）；
  lint **0 errors, 0 warnings, 4 hints**（4 条 hint 全为 `AutoboxingStateCreation`，不计入 `warningsAsErrors`）。

##### 本轮方法：第二次广扫荡，且**强制两段式**（先扫、再判定）

W12 只用一次扫描就收敛；本轮把范围扩到 5 个文件、39 组变异，并**把"扫描"与"判定"拆成两步**：
第一遍 `mutate_w13.py` 只负责找出存活者（不做解释）；第二遍 `mutate_w13b.py` 只重跑那 12 个存活者，
按**已写死的预期分类**核对（真缺口必须转为捕获、等价者必须仍存活）。这样"变异体存活"不会被
顺手解释掉，等价性必须**先给出证明**才允许登记。

变异体分布（39 组）：

| 前缀 | 文件 | 组数 | 覆盖的表达面 |
| --- | --- | --- | --- |
| R | `comic/ComicInfoXml.kt` | 5 | 页序校验（`validatePageConsistency`）、`ComicPreview.consistent`、补零位数 |
| C | `plan/ConfirmFlow.kt` | 10 | 确认流状态机：未开始态、推进、取消、`nextDanger`、进度标签 |
| D | `plan/DangerAction.kt` | 7 | 危险动作集合推导、固定顺序、危险清单、总览句 |
| O | `sort/PageOrder.kt` | 7 | 固定项落位、人工标记、`togglePin`、越界夹取 |
| M | `comic/MetaEdit.kt` | 10 | `setMetaValue` 类型分支、`batchApply` 计数/force/标记、`replaceVolume` |

##### 第一遍扫描结果（`python3 pixfold-d1/artifacts/mutate_w13.py`）

**39 组 → 27 捕获 / 12 存活 / 0 锚点缺失**，每组读数均为 `350 tests completed`；
逐文件 sha256 还原核对全 OK（`ComicInfoXml.kt: 91cc6737f176` / `MetaEdit.kt: 4c77d8adeef4` /
`ConfirmFlow.kt: f022de3bb920` / `DangerAction.kt: 6255615a1959` / `PageOrder.kt: 6eb3c14d4af3`）。

捕获者（举要，证明 27 个捕获都是**有名字的用例**在抓）：`C8 取消退一步` →
`cancelling at any step returns to the very beginning`；`D1 末条恒"只写入输出文件"` →
`general summary never claims it only writes files when source deletion is on`；
`D6 危险步骤顺序改 Set 迭代序` → `danger sequence is deterministic regardless of set iteration order`；
`M3 忽略 force` → `L14 force overwrites the per-item exception`；
`M7 按整对象相等定位` → 7 个用例失败；`O4 撞位改向右` →
`pinned collision falls back to the nearest free slot on the left`。

12 个存活者：`R1 R2 R3 R5`（页序校验）/ `C1 C2`（未开始态）/ `D5`（覆盖清单）/ `O1 O5 O6`（固定项）/
`M6 M10`（元数据）。

##### 存活者判定：8 真缺口 + 4 已证等价

**4 个等价者先给证明**（都从源码推出，不是"看不出差别"就登记）：

- **R2**（`pages.size != order.size` → `pages.size > order.size`）：`validatePageConsistency` 的 `pages`
  就是 `renumberPages(volume)`（`ComicInfoXml.kt:111`），而 `renumberPages` 返回 `order.mapIndexed{…}`
  （`:66-70`）⇒ `pages.size ≡ volume.pageOrder.order.size` **恒成立** ⇒ 两个写法**都恒假**。
- **R5**（`!p.fileName.startsWith("$expected.")` → `p.fileName.isEmpty()`）：校验侧
  `:116 expected = (i+1).padStart(if (pages.size >= 100) 4 else 3, '0')` 与重编号侧
  `:67 padding = if (order.size >= 100) 4 else 3` / `:69 (i+1).padStart(padding,'0') + "." + ext.lowercase()`
  **同源同 index** ⇒ `fileName` 恒以 `"$expected."` 开头 ⇒ 原式恒假；变异体 `fileName.isEmpty()`
  也恒假（`fileName` 至少是 `"001."`）⇒ 等价。
- **O5**（`if (idx < 0) continue` → `break`）：`idx < 0` 意味着 `slots[0..min(recorded, size-1)]` 全被占，
  即需 ≥ `min(recorded,size-1)+1` 个已落位固定项；已落位者的 `recorded` **互不相同且都小于**当前
  `recorded`（`:67` 按 `sortedBy { it.key }` 升序处理）⇒ 若 `min(recorded,size-1) == recorded`
  则需 `recorded+1` 个不同下标 `< recorded`，只有 `recorded` 个，**矛盾**；故必为
  `min(recorded,size-1) == size-1`，即**全部 slot 已满**，其后每一项同样失败 ⇒ 结果恒同。
- **M6**（`volumes.none { it.id == volume.id }` → `volumes.isEmpty()`）：`.none{}` 为真时按 id
  `map` 逐项都不匹配 ⇒ 返回列表与原列表**逐元素相同**；空列表时两者都返回空 ⇒ 等价。

**8 个真缺口与新增断言**（每个新用例都是该变异的**唯一捕获者**，见下节重跑）：

| 变异 | 缺口本质 | 新增用例（文件） |
| --- | --- | --- |
| R1 `consistent` 恒真 | `pageIssues` 非空时没人断言 `consistent` 为假 | `a preview with a recorded page issue is not consistent()`（ComicMetadataTest） |
| R3 校验侧 `>=100`→`>100` | 100 页卷的一致性从没被断言过 | `a hundred-page volume still reports a consistent preview()`（ComicMetadataTest） |
| C1 `isLastStep` 丢 `isActive` | 未开始态（`sequence == null`）从没被断言 | `an untouched flow reports no steps and is not on its last step()`（ConfirmFlowTest） |
| C2 `totalSteps ?: 1` | 同上（同一条用例覆盖两个变异） | 同上 |
| D5 覆盖清单不排除已跳过卷 | 从没构造过 `skipped = true && outputExists = true` 的计划 | `overwrite list names only the files that would really be overwritten()`（DangerActionTest） |
| O1 `rest` 不剔除固定项（会重复） | 结果**从没被断言为无重复置换** | `applyRule fills the free slots without duplicating a pinned item()`（PageOrderTest） |
| O6 `togglePin` 对不存在项也记标记 | 只测过已存在的项 | `togglePin on an unknown id is a silent no-op()`（PageOrderTest） |
| M10 作者清理空串也当建议 | 只覆盖了 `null` 建议，没覆盖空串 | `writer cleaning ignores an empty cleaned suggestion()`（ComicMetadataTest） |

##### 踩坑记录（下一轮直接复用）

O1 的第一版断言写在既有用例 `pinned item keeps position across applyRule while manual marks clear`
（`PageOrderTest.kt`）内部 —— 该用例固定的是**规则序首位**项，固定项在规则序里排首位时
`rest` 剔不剔除都不产生重复，**断言位置抓不到变异**。已撤回，改建专用用例：把规则序首位项
先拖到末格再固定（`togglePin(moveItemTo(s0, firstId, 4), firstId)`），此时固定项与规则序冲突，
变异体必然把同一项塞进两个 slot。**教训：断言必须落在变异体的差异真的会经过的那个量上，
"补在已有用例里"不等于"补到了"。**

##### 重跑确认（8 个真缺口全转捕获；4 个等价者仍存活）

按 §0.4 补断言后**重跑那 12 个变异**（`python3 pixfold-d1/artifacts/mutate_w13b.py`，锚点唯一性检查通过，
逐文件 sha256 还原一致，同上一组哈希）：

```
[捕获] R1 ComicPreview.consistent 恒真
     ComicMetadataTest > a preview with a recorded page issue is not consistent() FAILED
[捕获] R3 校验侧补零位数 >=100 -> >100
     ComicMetadataTest > a hundred-page volume still reports a consistent preview() FAILED
[捕获] C1 isLastStep 丢掉 isActive 前提
     ConfirmFlowTest > an untouched flow reports no steps and is not on its last step() FAILED
[捕获] C2 totalSteps 未开始时报 1
     ConfirmFlowTest > an untouched flow reports no steps and is not on its last step() FAILED
[捕获] D5 覆盖清单不再排除已跳过的卷
     DangerActionTest > overwrite list names only the files that would really be overwritten() FAILED
[捕获] O1 applyRule 不再把固定项从规则序里剔除
     PageOrderTest > applyRule fills the free slots without duplicating a pinned item() FAILED
[捕获] O6 togglePin 对不存在的项也记标记
     PageOrderTest > togglePin on an unknown id is a silent no-op() FAILED
[捕获] M10 作者清理把空串也当成可清理建议
     ComicMetadataTest > writer cleaning ignores an empty cleaned suggestion() FAILED
[存活·符合预期] R2 / R5 / O5 / M6（已证等价）
```

⇒ **真缺口 8/8 转为捕获**（每次 `357 tests completed, 1 failed`，失败者正是新增的那一条），
**等价 4/4 仍存活**，与上面的证明一致。

- 结论 / 阻塞 / 待裁决：本轮**未新增待裁决项**（8 个缺口都属"断言不够"，4 个等价者已给出可复核证明，
  都不是产品语义分歧）；R1/R2/R3 仍未决，均不阻塞。
  本轮**未改生产代码**、**未新增 TextKey**、**未改 `app/**`**、**未改构建脚本**、**未新增依赖**。

---

#### 轮 10（2026-10-04 19:1x）— W14 余量加固：第三次广扫荡（换 8 个文件，58 组）

- 提交：`3185b15` test(d3): 补十处未覆盖表达面（W14 广扫荡 58 组 → 15 存活 → 13 真缺口全捕获）
- 改动：4 files / **+138 / −0**（**纯断言增量，生产代码零改动**，`git diff --name-only` 只列测试文件）
  - `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/naming/NamingWarningsTest.kt`（+73，5 用例）
  - `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/workflowa/WorkflowAStateTest.kt`（+40，2 用例）
  - `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/sort/NaturalOrderTest.kt`（+24，3 用例）
  - `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/settings/PixFoldSettingsTest.kt`（+1，并入既有用例）
  - 工具：`pixfold-d1/artifacts/mutate_w14.py` + `artifacts/w14bak/`，复核用 `mutate_w14b.py`
    （`.gitignore:9` 已忽略，**不入库**）
- 闸门：`BUILD SUCCESSFUL in 4s` / exit 0 / `62 actionable tasks: 4 executed, 58 up-to-date`；
  JUnit XML `domain` **25 files / 367 tests / 0F 0E 0S**（357 → 367，**+10**）、
  `app` **42 files / 399 tests / 0F 0E**（**未变，正确**：本分支一行未改 `app/**`）；
  lint **0 errors, 0 warnings, 4 hints**（4 条 hint 全为 `AutoboxingStateCreation`，不计入 `warningsAsErrors`）。

##### 本轮方法：第三次广扫荡，**继续沿用两段式**，并新增"编译失败"档

轮 9 已证明两段式（先扫、再按写死的预期核对）能挡住"把存活顺手解释掉"。本轮沿用，
并把范围扩到 **8 个文件、58 组**，全部是 **W7/W8/W11/W12/W13 五次都没变异过**的表达面。
`mutate_w14.py` 还多出一档判定：锚点唯一命中但**编译不过**的变异记为 `编译失败`，
与"捕获/存活"分开统计（本轮该档为 0，说明 58 个变异体都至少能编译）。

变异体分布（58 组）：

| 前缀 | 文件 | 组数 | 覆盖的表达面 |
| --- | --- | --- | --- |
| N | `sort/NaturalOrder.kt` | 14 | 中文数字解析（`十`/`十X`/`X十Y`/非法串）、记号切分、数值 vs 码点分支、补零比较 |
| S | `sort/SortItems.kt` | 7 | 六个排序字段的分支、方向、null 处理、比较器稳定性 |
| E | `naming/NamingEval.kt` | 9 | 组件拼接、分隔符、`applyCase`、扩展名策略、`ImageIndex` 起点、清洗开关 |
| D | `naming/Warnings.kt` | 8 | 五类警告的产生条件与**参数取值**（重名计数、大小写计数、非法字符列表、超长阈值） |
| R | `naming/RenamePlan.kt` | 8 | 冲突谓词（哪些警告导致跳过）、`reason` 取用、覆盖名增删、组件增删移、根目录名 |
| F | `naming/NamingSummary.kt` | 3 | 摘要句的启用组件数 / 样例 / 人工计数 |
| A | `workflowa/WorkflowAState.kt` | 6 | 批次规则 vs 独立集合、`toggleCustomRule` 保序、三个派生计数 |
| P | `settings/PixFoldSettings.kt` | 3 | 默认值、`monetEnabled` 与 `accent` 的一致性、`withAccent`/`withMonet` |

##### 第一遍扫描结果（`python3 pixfold-d1/artifacts/mutate_w14.py`）

**58 组 → 43 捕获 / 15 存活 / 0 锚点缺失 / 0 编译失败**；每组读数均为 `357 tests completed`；
逐文件 sha256 还原核对全 OK（`NaturalOrder.kt: f63a1aeae6f7` / `SortItems.kt: 58d2feb684fc` /
`NamingEval.kt: 8af93c336b65` / `Warnings.kt: b753a2abbcca` / `RenamePlan.kt: 78b2d559c197` /
`NamingSummary.kt: 52a90a54b2eb` / `WorkflowAState.kt: b7d12267d209` / `PixFoldSettings.kt: 4a8189676c1f`）。

15 个存活者：`N2 N3 N4 N5 N12 N14`（自然序）/ `D4 D6 D8`（警告参数）/ `R1 R2 R3`（改名计划谓词）/
`A2 A6`（批次规则与计数）/ `P3`（设置默认值）。

> ⚠️ **本轮踩到的锚点唯一性坑（已修，值得记下）**：`N9` 的锚点
> `if (x.length != y.length) return x.length.compareTo(y.length)` 在 `sort/NaturalOrder.kt` 里**出现两次**
> （`:97` 数值分支、`:105` 文本分支），`count == 2` ⇒ 会记成 `ANCHOR-MISS` 而**静默少测一组**。
> 修法：锚点前缀上一条注释行 `// 2) 数值相等 -> 原始位数少者在前`，使锚点唯一。
> 并新增**发射前干跑**：`exec(src.split("def sha(")[0])` 后逐组 `count(old)`，确认
> **58 组锚点全部唯一、问题 0** 才启动扫描。§0.4 的"锚点必须唯一"从此有了自动化前置检查。

##### 存活者判定：13 真缺口 + 2 已证等价

**2 个等价者先给证明**（都从源码推出，不是"看不出差别"就登记）：

- **N4**（`CHINESE_DIGITS[head[0]] ?: return null` → `?: 0`）：`head = s.substring(0, ten)`，其中
  `ten = s.indexOf('十')`（`NaturalOrder.kt:23`）是**第一个** `十` 的下标 ⇒ `head` 里不可能含 `十`；
  而 `head` 非空时它只能来自 `NUMERAL_RUN` 的 `[一二三四五六七八九十]+` 分支（`:9`），
  去掉 `十` 后只剩 `一`~`九` ⇒ `CHINESE_DIGITS[head[0]]` **永不为 null** ⇒ 该回退分支**不可达**，
  两个写法在可达状态上恒等。**注意：个位侧（N5）不成立** —— `tail` 可以含 `十`
  （`"十十"` ⇒ `tail = "十"` ⇒ `CHINESE_DIGITS['十']` 为 null，回退**可达**），故 N5 判为真缺口。
- **R2**（冲突谓词多认一个 `TooLong`）：`RenamePlan.kt:31` 的谓词只在 `if (p.hasConflict)` 之内求值，
  而 `hasConflict = warnings.any { it.type == Duplicate || it.type == IllegalChar }`
  （`model/Naming.kt:119-122`）⇒ 进入该分支时**至少已有一条** Duplicate/IllegalChar；
  又因警告按 `Duplicate → IllegalChar → CaseCollision → TooLong → Overridden` **固定顺序**追加
  （`Warnings.kt:40/48/61/71/79`）⇒ `TooLong` 永远排在已有冲突之后 ⇒ `first { … }` 的结果不变。

**13 个真缺口**（每条都补了"只可能由它自己捕获"的断言）：

| 变异 | 缺口是什么 | 新用例（捕获者） |
| --- | --- | --- |
| N2 | `十` 缺省分支 `if (s.length == 1)` 去掉后 `CHINESE_DIGITS[s[0]]` 对 `"十十"` 越界/错值 | `malformed chinese numeral runs are left as plain text` |
| N3 | `十` 后面接两位的非法串没有被断言拒收 | 同上 |
| N5 | 个位查表失败的回退（`"十十"`）从未被断言 | 同上 |
| N12 | 单边数字段必须走**码点**比较，无断言 | `one side digits still compares by code unit` |
| N14 | 记号序列**只允许非空段**（末尾空记号会改变 `ta.size.compareTo`） | `tokenizer does not append an empty token when the name ends with digits` |
| D4 | `IllegalChar` **只在 `sanitize=false`** 时产生 —— 用"覆盖名"当探针 | `sanitize flag alone decides the illegal char warning even for an override` |
| D6 | `WarnCaseCollision.args[0]` 是**其他**项数（组大小 − 1），喂给 Android **plurals** ⇒ 用户可见 | `case collision reports the number of other items sharing the lowercase name` |
| D8 | 非法字符列表**必须去重**（两个制表符只显示一条「控制字符」） | `illegal char parts list control characters once and each reserved char once` |
| R1 | 冲突谓词**必须同时认** `Duplicate` 与 `IllegalChar`（漏掉后者会让 `first{}` 抛异常） | `an illegal char conflict alone still yields a readable reason` |
| R3 | `reason` 取**第一条**冲突警告（同时有重名与非法字符时必须是重名） | `reason prefers the duplicate warning when both conflicts are present` |
| A2 | 独立集合改规则必须基于**当前状态**重排（固定项不得因"从原始图重算"而丢位） | `applying a rule to a custom collection keeps its pinned item` |
| A6 | `pinnedCountTotal` 必须数**固定**项而非人工调整项（三个派生量里唯一无断言的） | `pinnedCountTotal counts pins and is not the manual count` |
| P3 | `predictiveBackAnimation` 是 `PixFoldSettings` 里**唯一**无领域断言的字段 | 并入 `defaults follow system theme and dynamic color` |

##### 第二遍复核（`python3 pixfold-d1/artifacts/mutate_w14b.py`，只重跑 15 个存活者）

```
[捕获] (真缺口) N2 / N3 / N5 / N12 / N14 / D4 / D6 / D8 / R1 / R3 / A2 / A6 / P3   ← 13/13
[★存活★] (已证等价) N4 / R2                                                        ← 2/2
```

⇒ **真缺口 13/13 转为捕获**（每次 `367 tests completed, 1 failed`，失败者正是新增的那一条），
**等价 2/2 仍存活**，与上面的证明一致。5 个被变异文件的 sha256 还原核对全 OK。

- 结论 / 阻塞 / 待裁决：本轮**未新增待裁决项**（13 个缺口都属"断言不够"，2 个等价者已给出可复核证明，
  都不是产品语义分歧）；R1/R2/R3/R4 仍未决，均不阻塞。
  本轮**未改生产代码**、**未新增 TextKey**、**未改 `app/**`**、**未改构建脚本**、**未新增依赖**。

---

#### 轮 11（2026-10-04 19:5x）— W15 余量加固：第四次广扫荡（换 9 个文件，64 组）

- 提交：`8b3e988` test(d3): 补三十九处未覆盖表达面断言（W15 广扫荡 64 组 → 32 存活 → 31 真缺口全捕获）
- 改动：8 files / **+445 / −0**（**纯断言增量，生产代码零改动**，`git diff --name-only` 只列测试文件）
  - 新建 `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/workflowb/WorkflowBStateTest.kt`（113 行 / 10 用例）
  - 新建 `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/model/MetadataTest.kt`（95 行 / 8 用例）
  - 新建 `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/model/SourceItemTest.kt`（71 行 / 7 用例）
  - 新建 `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/model/SortTest.kt`（68 行 / 5 用例）
  - 新建 `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/settings/SettingsStoreTest.kt`（42 行 / 4 用例）
  - 新建 `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/text/MsgTest.kt`（36 行 / 3 用例）
  - `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/naming/RootDirFollowTest.kt`（+13，1 用例）
  - `pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/naming/NamingWarningsTest.kt`（+7，1 用例）
  - 工具：`pixfold-d1/artifacts/mutate_w15.py` + `artifacts/w15bak/`，复核用 `mutate_w15b.py` + `artifacts/w15bbak/`
    （`.gitignore:9` 已忽略，**不入库**）
- 闸门：`BUILD SUCCESSFUL in 4s` / exit 0 / `62 actionable tasks: 4 executed, 58 up-to-date`；
  JUnit XML `domain` **31 files / 406 tests / 0F 0E 0S**（367 → 406，**+39**）、
  `app` **42 files / 399 tests / 0F 0E**（**未变，正确**：本分支一行未改 `app/**`）；
  lint **0 errors, 0 warnings, 4 hints**（4 条 hint 全为 `AutoboxingStateCreation`，不计入 `warningsAsErrors`）；
  `assembleDebug` 产出 `app-debug.apk`（33,405,421 字节）。

##### 本轮方法：第四次广扫荡，**换到"整个 `model/` 包 + 从零覆盖的 `workflowb/`"**

前三次广扫荡（W12/W13/W14）走的都是 `plan/`、`pack/`、`comic/`、`naming/`、`sort/`、`workflowa/`、`settings/`，
而 **`model/` 包的 4 个文件与 `workflowb/` 从未投过任何变异**。本轮先把"零覆盖事实"实测出来，再据此定面：

| 被查符号 | 领域测试内引用数 | 结论 |
| --- | --- | --- |
| `selectVolume` / `setVolumeIncluded` / `updateVolume` / `batchApplyToLibrary` | 0 / 0 / 0 / 0 | `WorkflowBState` 12 个入口**全靠 app 层在用** |
| `activeVolume` / `includedCount` / `pendingCount` / `seriesGroups` / `volumeCount` | 0 / 0 / 0 / 0 / 0 | 同上 |
| `relPath` / `seriesKey` / `InMemorySettingsStore` / `msg(` | 0 / 0 / 0 / 0 | 前两个只有 app 消费者；`msg` 两个助手**全仓生产代码零调用点** |
| `MAX_LEVELS` / `ILLEGAL_NAME_CHARS` | 0 / 0 | 常量本身无断言 |
| `hasValue` / `needsConfirmation` | 1 / 1 | 都只有**单侧**断言（`needsConfirmation` 只断过 false 侧） |
| `ComicVolume.isOverridden` | 0（**app 侧也 0**） | 全仓无消费者 |

变异体分布（64 组）：

| 前缀 | 文件 | 组数 | 覆盖的表达面 |
| --- | --- | --- | --- |
| N | `model/Naming.kt` | 14 | `hasWarningOnly` 白名单、`finalName`/`isOverridden` 回退、`effectiveScheme` 分支、跟随标志、阈值与默认常量 |
| M | `model/Metadata.kt` | 12 | `hasValue` 三态、`seriesKey`、`pageCount`、`pendingIssues` 两条、`needsConfirmation`、卷覆盖标志、分组顺序与容器 |
| S | `model/Sort.kt` | 5 | `MAX_LEVELS` 数值、两条 `require` 的边界、默认规则的两级与方向 |
| I | `model/SourceItem.kt` | 8 | `sizeLabel` 的两个单位阈值与两条换算、`fileName`、`relPath` 三段 |
| B | `workflowb/WorkflowBState.kt` | 12 | 12 个公开入口各自一处 |
| T | `settings/SettingsStore.kt` | 3 | `load`/`save`/初值 |
| G | `text/Msg.kt` | 2 | `msg(vararg)` 的参数保序与保留 |
| K | `mock/MockData.kt` + `mock/MockLibraryBData.kt` | 8 | 样例库的计数、边界名、扩展名大小写、非法字符样例、卷号空值、既有产出 |

##### 第一遍扫描结果（`python3 pixfold-d1/artifacts/mutate_w15.py`）

**64 组 → 32 捕获 / 32 存活 / 0 锚点缺失 / 0 编译失败**；每组读数均为 `367 tests completed`；
逐文件 sha256 还原核对全 OK（`Naming.kt: d1132f8dbc77` / `Metadata.kt: 23922990fde7` /
`Sort.kt: aa9474ae7787` / `SourceItem.kt: 6443b27dc551` / `WorkflowBState.kt: e1f97730fdf9` /
`SettingsStore.kt: 74d029e73c76` / `Msg.kt: 47b259ce90a4` / `MockData.kt: 0a3fa8cf8e98` /
`MockLibraryBData.kt: 0e558ce02891`）。

**8 个 mock 变异（K1–K8）全部被捕获**，且捕获强度很高（`K7` 把 `op-102` 的 `outputExists` 由真改假 ⇒ **29 个用例失败**，
`K6` 把 `aot-0` 的卷号由 null 改 0 ⇒ 5 个，`K2`/`K8` 各 2 个）—— 说明样例库本身已被大量断言当作**输入契约**钉住。

32 个存活者：`N6 N7 N9`（Naming）/ `M1 M2 M8 M9`（Metadata）/ `S1 S3`（Sort）/
`I1 I2 I3 I5 I6 I7`（SourceItem）/ `B1…B12`（WorkflowBState）/ `T1 T2 T3`（SettingsStore）/ `G1 G2`（Msg）。

> ⚠️ **本轮踩到的锚点唯一性坑（已修，值得记下）**：`K8` 的锚点原本写成
> `'dirName = "第02卷", count = 18,'` 一带的 Kotlin 片段里含 `E:\\漫画库`，
> 而 Python 字面量里要表达 Kotlin 的 `E:\\漫画库` 需要**四个**反斜杠，写两个 ⇒ 锚点匹配 **0 次**，
> 干跑立刻报出来。改用同一行内**不含反斜杠的唯一片段**（`count = 18`）后，
> **64 组锚点全部唯一、问题 0** 才启动扫描。
> **教训：永远不要把含反斜杠的 Kotlin 片段当 Python 锚点**，改用同行的无反斜杠唯一片段。

##### 存活者判定：31 真缺口 + 1 已证等价

**唯一的等价者先给证明**（从源码推，不是"看不出差别"就登记）：

- **N6**（`effectiveScheme` 的 `if/else` 改写成 elvis）：
  `model/Naming.kt:169-174` 的 `rootDirName` 字段类型是**非空** `String`
  （`data class NamingScheme(rootDirName: String, …)`，`:61`）⇒ `state.rootDirNameOverride != null` 成立时
  `state.rootDirNameOverride` 的智能转型结果是 `String`（非 `String?`），
  两个分支各自 `copy(rootDirName = …)` 的实参类型与取值完全相同 ⇒
  改写前后在**所有可达状态**上恒等。**注意 N5 用的是同一段锚点但语义不同**（永远跟随集合），
  故 N5 判为真缺口、N6 判为等价。

**31 个真缺口**（每条都补了"只可能由它自己捕获"的断言）：

| 变异 | 缺口是什么 | 新用例（捕获者） |
| --- | --- | --- |
| N7 | 跟随标志的判据必须与 `effectiveScheme` 的 `!= null` **一致**；空串是合法构造值（公开参数），`!isNullOrEmpty()` 会让 UI 显示"跟随"而实际生效的是那个空串例外 | `the follow flag stays in sync with the state it reports on`（`RootDirFollowTest`） |
| N9 | 超长阈值是规格 §7 的 **180**；旧用例**用常量本身算样本长度**，常量改成 181 它照样过 | `the too long threshold is the spec value 180`（`NamingWarningsTest`） |
| M1 | `hasValue`：**空串不算有值**（`Suggestion("")` 与 `null` 同义） | `a blank string suggestion counts as no value` |
| M2 | `hasValue`：**非字符串只要非 null 就算有值**（`Suggestion(0)` 是有效卷号，0 不能被当成"没有"） | `a non string suggestion only needs to be non null` |
| M8 | `needsConfirmation`：**单项待确认也算**（`size > 1` 会让只缺语言的卷悄悄变"已确认"） | `a single pending issue is enough to need confirmation` |
| M9 | 卷的 `isOverridden` 必须跟**手工编辑集合**走（全仓无消费者，属"契约断言"） | `the volume override flag follows the manual edit set` |
| S1 | 层级上限就是 **4**（`MAX_LEVELS` 常量值本身此前零断言） | `a rule accepts up to four levels` |
| S3 | 规则的**第一条** `require`：至少一级（去掉后 `SortRule(emptyList())` 不再抛） | `a rule needs at least one level` |
| I1 | `sizeLabel` 的 MB 阈值是 **`>=`**（整 1 MB 必须显示 `1.0 MB`） | `size label switches at exactly one megabyte` |
| I2 | `sizeLabel` 的 KB 阈值是 **`>=`**（整 1 KB 必须显示 `1 KB` 而不是 `1024 B`） | `size label switches at exactly one kilobyte` |
| I3 | KB 换算是 **÷1024**（用 3000/10000 分辨：÷1000 会得 3/10） | `kilobyte label divides by one thousand and twenty four` |
| I5 | `fileName` = 基名 + `.` + 扩展名 | `file name is base name plus extension` |
| I6 | `relPath` 必须带目录前缀 | `relative path includes the directory when there is one` |
| I7 | 根目录下的 `relPath` **没有前导斜杠** | `relative path is just the file name at the root` |
| B1 | 失效 id 回退到**第一卷**（不是最后一卷） | `a stale active id falls back to the first volume not the last` |
| B2 | `activeVolume` 必须**按 id 查** | `active volume is looked up by id` |
| B3 | `includedCount` 数**已勾选**的卷 | `included count and total count are different things` |
| B4 | `volumeCount` 是**总数**，不是已勾选数 | 同上 |
| B5 | `pendingCount` 数**待确认**的卷，与勾选无关 | `pending count counts unconfirmed volumes not included ones` |
| B6 | `seriesGroups` 真的分组（不是空表） | `series groups keep first appearance order` |
| B7 | 初始选中**第一**卷 | `initial state selects the first volume as the active one` |
| B8 | `selectVolume` 库内校验：未知 id **原样返回** | `select volume switches inside the library and ignores unknown ids` |
| B9 | `setVolumeIncluded` 真的写入传入值 | `included count and total count are different things` |
| B10 | 未知 id 的 `setVolumeIncluded` **什么都不改** | `set volume included on an unknown id changes nothing` |
| B11 | `updateVolume` 是**原位替换**而不是追加 | `update volume replaces in place instead of appending` |
| B12 | `batchApplyToLibrary` 必须把**新库写回状态** | `batch apply writes the new library back and reports real counts` |
| T1 | `load` 返回**已存值** | `the initial value is used instead of the default` |
| T2 | `save` 真的保存 | `save is visible to the next load` |
| T3 | 构造时的初值**被采纳** | `the initial value is used instead of the default` |
| G1 | `msg(vararg)` **保留**全部参数 | `vararg arguments keep their order and types` |
| G2 | 参数**按位置保序**（资源文件的 `%1$s`/`%2$d` 依赖它） | 同上 |

##### 第二遍复核（`python3 pixfold-d1/artifacts/mutate_w15b.py`，只重跑 32 个存活者）

复核脚本先做**前置核对**（当前生产文件必须与 `artifacts/w15bak/` **逐字节一致**，否则中止，避免在污染基线上继续读），
并把每组的分类 `真缺口` / `已证等价` 写死进脚本，末尾按分类分别核对预期：

```
[捕获]  (真缺口)   N7 / N9 / M1 / M2 / M8 / M9 / S1 / S3 / I1 / I2 / I3 / I5 / I6 / I7 /
                   B1…B12 / T1 / T2 / T3 / G1 / G2                              ← 31/31
[★存活★] (已证等价) N6                                                          ← 1/1
```

⇒ **真缺口 31/31 转为捕获**（每次 `406 tests completed, 1 failed` 或 2 failed，失败者正是新增的那一条），
**等价 1/1 仍存活**，与上面的证明一致。7 个被变异文件的 sha256 还原核对全 OK。

- 结论 / 阻塞 / 待裁决：本轮**未新增待裁决项**（31 个缺口全属"断言不够"，唯一等价者已给出可复核证明，
  不是产品语义分歧）；R1/R2/R3/R4 仍未决，均不阻塞。
  本轮**未改生产代码**、**未新增 TextKey**、**未改 `app/**`**、**未改构建脚本**、**未新增依赖**。

---

#### 轮 12（2026-10-04 20:35）— W16 第五次广扫荡（收尾轮）：12 张映射表 + 零引用表达面

- 提交：`b0f0e88` test(d3): 补二十四处未覆盖表达面断言（W16 广扫荡 66 组 → 24 存活 → 23 真缺口全捕获）
- 改动：6 files (+318/−0)（5 个既有测试文件 +318 中的 99 行，1 个新建 `TextKeyMappingTest.kt` 219 行；**全是测试文件**）
- 闸门：app 399 tests / domain 425 tests / 0F 0E / lint 0 errors 0 warnings（旧产物）/ assembleDebug OK
- 变异：66 组，42 捕获，24 存活 → 23 真缺口全捕获 + 1 已证等价，0 逃逸
- 结论 / 阻塞 / 待裁决：本轮**未新增待裁决项**；R1/R2/R3/R4 仍未决，均不阻塞。
  本轮**未改生产代码**、**未新增 TextKey**、**未改 `app/**`**、**未改构建脚本**、**未新增依赖**。

##### 本轮方法：先用 AST 精算历史覆盖面，再挑「投得最少」的面

前七次扫荡（W7/W8/W11/W12/W13/W14/W15）累计约 **250 组**变异，但**从未有一张「每文件投了多少组」的账**。
本轮开跑前先用 `ast.parse` **静态**解析 `artifacts/mutate_*.py`（⚠️ **绝不用 `exec` 加载**：早期脚本
（`mutate_round3.py` 等）**顶层就有副作用代码**，加载即真的开跑并会污染禁改的 `app/**`——本轮已因此
发生一次事故，见下），把每组的第二个元素求值成真实路径后按前缀切出相对路径，得到准确基线：

- 历史共 **266 组 / 28 个生产文件**。
- **从未投过变异的生产文件只有 2 个**：`model/ImageCollection.kt`（9 行，纯 data class 声明）
  与 `text/TextKey.kt`（194 行，纯枚举常量）——**没有行为可投**，这是"行为面已 100% 覆盖"的
  结构性原因，不是漏做。
- ⇒ W16 的真正新面 = ① 历史组数最少的文件里**剩下的**面；② 生产有调用但**测试零引用**的函数。

零引用面（`grep -rl` 逐键/逐函数在 31 个领域测试文件里复核，引用计数全为 0）：

| 面 | 为什么零引用 | 指错的后果 |
| --- | --- | --- |
| 12 张「枚举 → `TextKey`」映射表（50 个键） | 领域层只持有键、**不持有译文**（`model/Sort.kt` KDoc），译文在 app 侧 `PixFoldText.kt` 的 `when` 里补 | **静默串味**：选「大小」显示「文件名」的文字、选「日语」写成「未设置」——不崩溃 |
| `DangerAction` 的 label/body/button 三元组 | 既有 `DangerActionTest` 只断言「三个动作的键**两两不同**」 | 整个动作的三份文案**错位到另一个动作**，无人察觉 |
| `comicVolumeOf` / `setIncluded` | 无测试消费者 | 语言/勾选语义被改而测试全绿 |
| `includedVolumes` | 只有 app 侧 `PackPreviewPage.kt:153` 用 | 计数口径反转 |
| `updateComponent` / `clearOverride` | 只经 `toggleComponent` / `setOverride` 间接用 | 改组件无效 / 覆盖项清不掉 |
| `containsIllegalChar` / `compareNaturalTokens` | 只经 `sanitizeName` / `naturalCompare` 间接用 | 控制字符漏网 / 记号数兜底失效 |

##### 事故与修复：`exec` 历史脚本导致 `app/**` 被污染

干跑锚点唯一性时，我用 `exec(src.split("def sha(")[0])` 去"只加载定义"，但早期脚本**顶层就有副作用**，
于是 `mutate_r3c.py` 真的跑了起来（60s 后被转入后台），并已把
`pixfold-d1/app/src/main/kotlin/com/pixfold/d1/ui/workflowa/SortAndPreviewPage.kt:761-767` 的
`textAlign = TextAlign.Center` 改成 `TextAlign.Start`（它的 `M7`）。

- 修复：`job_kill` 掉后台作业 → `cp` 从 `artifacts/SortAndPreviewPage.kt.bak_c` 还原
  （先 `diff -q` 确认原文件与备份**逐字节相同**）→ `git diff --quiet HEAD` 通过（**WORKTREE CLEAN**）
  → `git diff --name-only dev...d3/p1 -- pixfold-d1/app/` = 0 条。
- **教训**：**绝不用 `exec` 加载历史变异脚本**，只用 `ast.parse` 静态分析。
  变异脚本的"只加载不执行"必须靠 AST，不靠 `split`。

##### 第一遍扫描结果（`python3 pixfold-d1/artifacts/mutate_w16.py`）

```
共 66 组（A 20 / B 8 / C 38）… 锚点缺失 0 组 ⇒ 全部唯一，可以开跑
捕获 42 / 存活 24 / 锚点缺失 0 / 编译失败 0
还原核对：全部逐字节一致
```

24 个存活者：A 段 **17** 个（全是映射表指到别的项的键）、B 段 **2** 个（`includedVolumes` 取反、
`clearOverride` 变空操作）、C 段 **5** 个（`RenamePlannedOp.describe` 丢 reason、
改名成功项 `createdPath` 改用 `item.label`、`collectionNames` 记 `rootDirName`、
`LibraryRenamePlan.counts` 两数对调、`CbzEntry.toString` 丢字节数）。

##### 存活者判定：23 真缺口 + 1 已证等价

| 存活者 | 判定 | 依据 |
| --- | --- | --- |
| A1–A17（17 个映射表错键） | **真缺口** | 键被指到别的项 ⇒ 译文静默串味；领域测试对这批键零引用，故无人捕获 |
| B4 `includedVolumes` 取反 | **真缺口** | 生产有调用（`PackPreviewPage.kt:153`），测试零引用 |
| B7 `clearOverride` 变空操作 | **真缺口** | app 侧 `NamingPage.kt:234 onResetOverride` 是唯一调用点，测试零引用 |
| C4 `describe` 丢 `op.reason` | **真缺口** | 跳过项的跳过理由会退化成状态词 |
| C5 `createdPath = item.label` | **已证等价** | `RenamePlannedOp.label` 的定义就是 `op.finalName`（`OperationPlan.kt:76`），与 `op.finalName` **恒等** ⇒ §0.4 意义上的等价变异体，复核时必须**仍存活** |
| C10 `collectionNames` 记 `rootDirName` | **真缺口** | 既有测试只断言 `collectionNames.size`，键数对而值错无人管 |
| C11 `LibraryRenamePlan.counts` 两数对调 | **真缺口** | `.counts` 的读者只在 `OperationPlanTest`（打包侧），改名侧无断言 |
| C15 `CbzEntry.toString` 丢字节数 | **真缺口** | 公开契约但**无观测面**（`app/**` 零消费者），故只能由单测钉死 |

##### 补断言（红→绿）

- **新建 `text/TextKeyMappingTest.kt`（13 用例）**：覆盖 A 段 17 个变异所属的 12 张映射表
  + `DangerAction` 三元组。写法一律
  `assertEquals(listOf(键…), Enum.entries.map { it.textKey })`（`ExecKind` 用 `.titleKey`），
  即**逐项钉死**而非"键两两不同"——后者正是既有 `DangerActionTest` 的写法，挡不住整体错位。
- 5 个既有文件各追加 1–2 条：
  `OperationPlanTest` +1（跳过的改名操作报原因）、`RenameLibraryPlanTest` +2（`collectionNames` 取值
  + `counts` 三项；`collectionNames` 那条**额外做了差分断言**——把集合 `copy(rootDirName = "改过的根名")`
  后重算，因为本组 mock 里集合名与根目录名恰好相同，否则差分恒真、拦不住"记 rootDirName"的写法）、
  `CbzWriterTest` +1、`NamingEvalTest` +1、`ComicMetadataTest` +1。

domain 闸门：**32 files / 425 tests / 0F 0E 0S**（406 → 425，**+19**）。

##### 第二遍复核（`python3 pixfold-d1/artifacts/mutate_w16b.py`，只重跑 24 个存活者）

复核脚本沿用轮 11 的两段式：先做**前置核对**（每个生产文件 sha256 必须等于 `artifacts/w16bak/` 里的备份，
否则中止），把每组的分类 `真缺口`/`已证等价` 写死进脚本，末尾按分类分别核对预期。

```
总数 24 / 捕获 23 / 存活 1 / 锚点缺失 0 / 编译失败 0
还原核对：全部逐字节一致
真缺口全部转捕获：是
等价者仍存活(与证明一致)：是
```

⇒ **真缺口 23/23 转为捕获**（日志里可见失败者正是新增用例，如
`TextKeyMappingTest > exec kind maps each option to its own title key() FAILED`、
`OperationPlanTest > describe of a skipped rename op reports its reason instead of the status word() FAILED`）；
**等价 1/1（C5）仍存活**，与证明一致。

- 结论 / 阻塞 / 待裁决：本轮**未新增待裁决项**（23 个缺口全属"断言不够"，唯一等价者已给出可复核证明）；
  R1/R2/R3/R4 仍未决，均不阻塞。
  本轮**未改生产代码**（`git diff --quiet HEAD -- pixfold-d1/domain/src/main` 通过）、**未新增 TextKey**、
  **未改 `app/**`**（`git diff --name-only dev...d3/p1 -- pixfold-d1/app/` = 0 条）、**未改构建脚本**、**未新增依赖**。
- ⚠️ lint 读数是**旧产物**（`app/build/reports/lint-results-debug.txt` mtime 17:44）：
  本次 `:app:lintDebug` 执行了，但 `lintReportDebug` / `lintAnalyzeDebug` 均为 `UP-TO-DATE`
  ⇒ 报告未重写。可判定正确（本分支 `app/**` 一行未改），但**不得当作新鲜读数引用**。
- ⚠️ **收尾轮声明**：本轮是 12 轮上限内的**最后一轮**（goal `maxGoalRounds = 12`）。
  §3 余量轮政策到此为止；后续若继续加固需用户**重新授权并追加轮次**。

---

#### 轮 13（2026-10-04 21:48）— W17 **R1 修复**：撤销记录能恢复原文件名

- 提交：`cc4c30c` fix(d3): 撤销记录恢复原文件名（R1：from 取新名、to 取原名，不再空操作）
- 改动：5 files (+106/−35)（生产 2 个 `domain/plan/{Execution,OperationPlan}.kt` +26 行；测试 3 个
  `plan/{UndoAlignmentTest,ExecutionReportTest,PackingPipelineTest}.kt`）
- 闸门：app 399 tests / domain **428** tests / 0F 0E / lint 0 errors 0 warnings（旧产物）/ assembleDebug OK
- 变异：6 组，**6 捕获 / 0 存活 / 0 锚点缺失 / 0 编译失败**（两遍一致）
- 结论 / 阻塞 / 待裁决：**R1 关闭**（§4 表格状态已改）。R2/R3/R4 仍未决，均不阻塞。

##### 用户裁决与本轮授权

用户 `m04079` 裁决五项，其中第 ① 项「细说 R1」已在 `m04152` 交代清楚；`m04081` 进一步确认
R1 按"扩 `ExecEntry` 加原名字段"这条修法落地（即 §4 表格 R1 行里列的第 ① 种语义）。
本轮即执行该裁决。**未新增 `TextKey`、未改 `app/**`、未改构建脚本、未新增依赖。**

##### 缺陷逐字回顾（修之前）

`UndoOp.RestoreRenamed(from, to)` 的语义是「把改名后的文件改回去」⇒ `from` 应是新名、`to` 应是**原文件名**。
但成功项的 `label` 与 `createdPath` 都是 `op.finalName`（`OperationPlan.kt:72` 的
`override val label: String get() = op.finalName` 与成功分支的 `createdPath = op.finalName`），
于是推导出的记录是 `RestoreRenamed("改后.jpg", "改后.jpg")` —— **空操作**，真要按它回滚是回不去的。
根因：`ExecEntry` 里**没有任何字段**能装原文件名。

为什么 D1 一直没暴露：`performUndo` 恒返回 `applied = false`（原型不触碰真实文件系统），
UI 只显示「已记录 N 项」，**从不消费 `from`/`to`**；真机核对（`HANGOFF.md:540` 第 17 项）看到的
也是同一句话。⇒ 这是一个**只在撤销记录内部、对用户暂时不可见**的语义缺陷。

##### 修复（三处生产改动）

1. `domain/plan/Execution.kt`：`ExecEntry` 增末位字段 `val originalPath: String? = null`，
   KDoc 写明「本项动手之前，这个位置上原本存在的东西；`null` = 原位置本来就是空的（纯新建）」，
   即它与 `createdPath` 一起才构成一次完整的「从哪来 → 到哪去」。
2. 同文件 init 增**第三条 `require`**：`require(outcome == ExecOutcome.Succeeded || originalPath == null)`。
   理由与既有两条同源（跳过项与失败项什么也没动，带上原名就是无中生有）。消息仍是**中文开发者诊断**，
   不建键（与既有两条判据一致）。
3. 同文件 `undoOpsOf` 的 Rename 分支：
   `UndoOp.RestoreRenamed(from = path, to = e.originalPath ?: path)`。
   **`from` 仍取 `createdPath`**（= 新名 = 回滚起点），`to` 首次改取原名。
4. `domain/plan/OperationPlan.kt` 的 `executeRenameOp` 成功分支补
   `originalPath = op.proposal.image.fileName` —— 原文件名只存在于**计划**里，
   报告若不带上它，"从报告推导撤销"（`undoRecordOf(report)`）这条入口在类型上就永远取不到。

##### 为什么 `originalPath` 落在 `ExecEntry`，以及为什么**回落**而不是 `requireNotNull`

本台账开头（`UndoAlignmentTest` 文件头）声明的不变量是：**两个撤销入口必须产出同一份记录** ——
`undoRecordOf(report)`（只有报告）与 `undoRecordFrom(plan, report)`（报告 + 计划）。
若只让后者从计划里现取原名，两个入口就会给出不同的 `to`，"不可能漂移"就成了空话。
把原名放进报告自己，两条路径才真正等价 —— 这也是**选第 ① 种修法**的实质理由。

`to = e.originalPath ?: path` 的**回落**是刻意的：报告构造不保证每条成功项都带原名 ——
`UndoAlignmentTest > a report kind that disagrees with the plan kind is visible in the undo record`
这条用例**刻意**用 Package 报告配 Rename 计划（正是为了钉住"kind 从哪来"这一层）。
回落成空操作最坏是"没回滚"；改成 `requireNotNull` 会让整个撤销入口抛异常；
而删错文件不可逆。两害相权取保守的那个 —— 这一点已由变异 **M6** 反证：
`requireNotNull` 版本被上述用例捕获。

##### 红→绿（两段式，红是**真运行期断言失败**）

第一次尝试时红是编译错（`Unresolved reference 'originalPath'`），**不算数**。
于是拆成两步：先只加字段 + 填字段、`undoOpsOf` 原样不动 ⇒ 再跑：

```
UndoAlignmentTest > only the succeeded entry of a mixed rename batch is undoable() FAILED
UndoAlignmentTest > a successful rename undo restores the original file name() FAILED
425 tests completed, 2 failed
BUILD FAILED in 6s
```

⇒ 这才是真红（2 条断言因 `to` 仍是新名而失败）。随后改 `undoOpsOf` ⇒ `BUILD SUCCESSFUL in 7s`。
另外本轮**新增 3 条断言**（`ExecutionReportTest`：跳过项/失败项不得携带 `originalPath`、
成功项可省略 —— 反向不能堵死；并把既有的 `rename undo maps to restoring the original name`
从"只断言 `from`"补成**同时钉死 `to`**）。

##### 两条"如实记录缺陷"的用例翻成正向断言

| 原用例名 | 新用例名 | 变化 |
| --- | --- | --- |
| `a successful rename undo currently collapses from and to onto the new name` | `a successful rename undo restores the original file name` | `assertEquals(op.from, op.to, …)` ⇒ `assertEquals("改后.jpg", op.from)` + `assertEquals(original, op.to)` |
| `the undo record cannot recover the original name from the report alone` | `the report carries the original name so undo no longer needs the plan` | 保留 `e.label != original`（标签仍是新名），新增 `assertEquals(original, e.originalPath)` |

第三条 `only the succeeded entry of a mixed rename batch is undoable` 也补了 `to` 断言。
⇒ 上一轮 §6.5 里那句「`from`/`to` 对调的变异**存活**」的**等价变异**预测在本轮兑现：
该变异（M2）现在**被捕获**。

##### 变异表（6 组，全捕获；`python3 pixfold-d1/artifacts/mutate_w17.py`）

| # | 变异 | 结果 | 捕获者 |
| --- | --- | --- | --- |
| M1 | 改名撤销的 `to` 退回 `createdPath`（缺陷 R1 复活） | 捕获 | `UndoAlignmentTest > only the succeeded entry of a mixed rename batch is undoable()` |
| M2 | 改名撤销的 `from`/`to` 对调 | 捕获 | `ExecutionReportTest > rename undo maps to restoring the original name()` |
| M3 | 成功项的原文件名改用 `finalName` 填 | 捕获 | 同上 |
| M4 | 成功项不填原文件名（回落成 `createdPath`） | 捕获 | 同上 |
| M5 | `originalPath` 不变量弱化（只挡 `Skipped`、放过 `Failed`） | 捕获 | `ExecutionReportTest > a failed entry cannot carry an original path()` |
| M6 | 回落改成 `requireNotNull`（拿不到原名就抛异常） | 捕获 | `UndoAlignmentTest > a report kind that disagrees with the plan kind is visible in the undo record()` |

```
总数 6 / 捕获 6 / 存活 0 / 锚点缺失 0 / 编译失败 0
还原核对：全部逐字节一致（Execution.kt 2b35dd2b36c1 / OperationPlan.kt 5e18dbd48e00）
```

**两遍一致**：第一遍的 M1 由 `ExecutionReportTest` 捕获、M4 的"捕获者"栏被 Gradle 的
`> Task :domain:test FAILED` 行抢占（归因取行取错了）；修正归因逻辑（排除 `> Task` 行）后
第二遍重跑，结论相同、捕获者更精确。第二遍前先做**前置核对**：
两个生产文件的 sha256 与 `artifacts/w17bak/` 备份**逐字节一致**才开跑。

##### 结论与越界核对

- **R1 关闭**。§4 表格 R1 行状态改为「✅ 已修复（轮 13，W17）」。
- **未新增待裁决项**；R2/R3/R4 仍未决，均不阻塞。
- 本轮**改动了生产代码**（`domain/plan/{Execution,OperationPlan}.kt`，+26 行）—— 这是本分支
  自 W10 终验以来**第一次**改生产代码，因为 R1 是用户明确裁决要修的**真缺陷**（不是余量加固）。
- **未新增 `TextKey`**（新 `require` 的消息是开发者诊断，中文不建键）；
  **未改 `app/**`**（`git diff --name-only dev...d3/p1 -- pixfold-d1/app/` = 0 条）；
  **未改任何 `res/values*/**.xml`**；**未改构建脚本**；**未新增依赖**。
- ⚠️ lint 读数仍是**旧产物**（`app/build/reports/lint-results-debug.txt` mtime 17:44）：
  本次 `:app:lintDebug` 执行了，但 `lintAnalyzeDebug` / `lintReportDebug` 为 `UP-TO-DATE`。
  可判定正确（`app/**` 仍未改），但**不得当作新鲜读数引用**。
- ⚠️ `ExecutionReportTest > rename undo maps to restoring the original name` 此前**只断言 `from`**，
  本轮补上 `to` —— 这正是 HANGOFF 所记"测试数量不等于测试强度"的一个实例。

#### 轮 14（2026-10-04 22:2x）— W18 **R2 补齐**：改名侧逐项句子契约（**首次改 `app/**`**）

- 提交：`759ef68` fix(d3): 改名计划行给出「原名 → 新名」（R2：新增 TextKey + 双语资源）
- 改动：7 files (+183/−14)（生产 5 个：`domain/text/TextKey.kt` +21、`domain/plan/OperationPlan.kt` ±22、
  `app/…/ui/text/PixFoldText.kt` +3、`app/src/main/res/values/domain_strings.xml` +4、
  `app/src/main/res/values-zh/domain_strings.xml` +4；测试 2 个：`plan/OperationPlanTest.kt`、
  `app/…/ui/text/PixFoldTextTest.kt` +111）
- 闸门：app **402** tests / domain **429** tests / 0F 0E 0S / lint **0 errors, 0 warnings, 4 hints（新鲜读数）** / assembleDebug OK
- 变异：10 组，**10 捕获 / 0 存活 / 0 锚点缺失 / 0 编译失败**（两遍一致）
- 结论 / 阻塞 / 待裁决：**R2 关闭**（§4 表格状态已改）。R3/R4 仍未决，均不阻塞。

##### 用户裁决与本轮授权

用户 `m04081` 就 R2 回「**改吧**」⇒ 授权**新增 `TextKey`**；`m04079` 第 ⑤ 项另要求"这属于 i18n 内容，
顺带实现了，然后看下前面相关 i18n 的要不要调整"。R2 因此**首次改动 `app/**`**
（此前 13 轮里 `git diff --name-only dev...d3/p1 -- pixfold-d1/app/` 恒为 0 条）——
改的是**资源与映射**，不是 UI 布局：`RenamePlanPage.kt` 一行未动（理由见下）。

##### 缺陷逐字回顾（修之前）

`HANGOFF.md:2690-2693`：「**R2(非缺陷)**:`RenamePlannedOp.describe()` 在无原因时只能给一句状态词
(`Msg(TextKey.OpStatusRename)`)—— 改名侧没有逐项句子契约(计划页渲染的是"原名 → 新名"两列)。
补齐需**新增 `TextKey`**,而本轮明令禁止新增键(会牵动 `PixFoldText.kt` 的穷尽 `when` 与双语资源,
把 lint 风险引入无人值守窗口)。」

逐条核对后**发现两处，不只一处**：
1. **未跳过项没有句子**（原记录的那一处）：`describe()` 只回 `Msg(TextKey.OpStatusRename)`——「将重命名」，
   说不出"从什么改成什么"。
2. **跳过项无原因时回落方向是错的**（原记录没提）：原实现把"无原因"与"未跳过"**混在同一分支**，
   于是跳过项若无 `reason` 就会回落到 `OpStatusRename` —— 对一个**将被跳过**的项说「将重命名」，
   比"没话说"更误导。构造上 `buildRenamePlan` 必给跳过项配 `reason`（`RenamePlan.kt:32-41`
   取 `warnings.first { Duplicate || IllegalChar }.message`），故这条今天是**不可达**的，
   但它是同一个 `when` 的另一半，本轮一并摆正。

##### 三处生产改动（逐字）

1. `pixfold-d1/domain/src/main/kotlin/com/pixfold/d1/domain/text/TextKey.kt`：
   - 文件头前缀清单插 `RenamePlan*`；
   - 在 `PackSkipOutputExists,` 与「执行报告文本」段之间新开一段
     `// ---------------- 改名计划行(RenamePlannedOp.describe) ----------------` + **`RenamePlanRename,`**（单键）。
     其 KDoc 写明：两个参数都是**文件名字符串**、无数量变格（故**不**登记 `pluralQuantityIndex()`）；
     打包侧有 `PackCreateCbz` 说清"这一项要做什么"，改名侧原先只有一句状态词；
     ⚠️ 第 1 个参数是**改之前**的名字、第 2 个是**改之后**的 —— 顺序反了句子仍通顺但意思全错。
2. `domain/src/main/kotlin/com/pixfold/d1/domain/plan/OperationPlan.kt`：
   `RenamePlannedOp.describe()` 改为**两分支**：
   `if (op.isSkipped) listOf(op.reason ?: Msg(TextKey.OpStatusSkip)) else listOf(Msg(TextKey.RenamePlanRename, listOf(op.proposal.image.fileName, op.finalName)))`；
   文件头那句「本文件**不新增任何 [TextKey]**」删除，改为注明轮 14 的例外。
3. 双语资源 + 映射：`app/src/main/res/values/domain_strings.xml` 增
   `<string name="domain_rename_plan_rename">Rename %1$s → %2$s</string>`；
   `app/src/main/res/values-zh/domain_strings.xml` 增 `重命名 %1$s → %2$s`；
   `app/src/main/kotlin/com/pixfold/d1/ui/text/PixFoldText.kt` 的穷尽 `resId()` 增
   `TextKey.RenamePlanRename -> R.string.domain_rename_plan_rename`。

##### 为什么落在 `RenamePlannedOp` 而**不**动 `RenamePlanPage.kt`

`grep -rn "\.describe()" --include=*.kt app/src` ⇒ 只有两处消费者，且**都是打包侧**：
`app/…/ui/plan/PlanComponents.kt:139`（`PlanRow` 收的是 `VolumePackPlan`）与
`app/test/…/ui/plan/PlanUiTest.kt:81`。改名计划页渲染的是**自己的两列布局**
（`RenamePlanPage.kt:235 RenamePlanRow` → `:273 text = "→ ${op.finalName}"`），**不经过 `describe()`**。
因此 `OperationPlan.describe()` 的改名分支在 app 侧**零消费者** —— 本轮补的是**领域层契约**
（"计划必须可解释"，约束 5）与"键 → 译文"的可解析性，UI 布局保持原样。这是刻意的：
R2 的授权是"补齐契约 + i18n"，不是重做计划页。

##### 红→绿（两段式，含真实红）

- **红**（先只改测试、生产代码未动）：`:domain:test --rerun-tasks` ⇒
  `OperationPlanTest > describe of a rename plan spells out the original and the new name() FAILED`
  与 `… > a skipped rename op without a reason falls back to the skip status word() FAILED`，
  `429 tests completed, 2 failed`，`BUILD FAILED in 6s` ⇒ **运行期断言红，不是编译错**。
- **绿**：改完 `describe()` 后同命令 ⇒ `BUILD SUCCESSFUL in 5s`。

##### 两条用例翻正向对照表

| 用例 | 轮 14 之前 | 轮 14 之后 |
| --- | --- | --- |
| `OperationPlanTest > describe of a rename plan …` | 原名 `… falls back to the rename status key`，断言 `listOf(Msg(TextKey.OpStatusRename))`（**钉的是缺陷**） | 改名 `… spells out the original and the new name`，断言 `listOf(Msg(TextKey.RenamePlanRename, listOf(原名, 新名)))`（**钉方向**） |
| `OperationPlanTest > a skipped rename op without a reason …` | 不存在 | 新增：手工造 `reason = null` 的 Skipped 项，断言回落 `OpStatusSkip`（**钉回落方向**） |
| `PixFoldTextTest > rename plan line …` | 不存在 | 新增 3 条：`… mentions both names with the original first`（手工拼 `Msg`）、`… produced by the domain resolves with both real file names`（完整链路）、`the chinese rename plan line keeps both names with the original first`（`qualifiers = "zh-rCN"`） |

##### ★ 本轮最重要的发现：**"同一条解析路径"的期望值看不见资源写错**

本仓既有做法（`test_strategy = B`）是：UI 措辞**不写死**，期望值一律经 `TestText`/`resolveText`
从**同一份资源**算出 ⇒ 与语言无关。这在"键 → 资源"这一步上很稳，但有一个**结构性盲区**：
如果资源**内容本身**写错，期望值与实际值会**一起错**，断言照样全绿。

实证（W18 第一遍）：把英文资源改成只有一个占位符 `Rename %1$s`（第二个文件名被静默吞掉），
上面那两条比对型断言**全部照常通过**。故本轮补了一条**不依赖措辞、只看句子可观测形状**的判据：
句子必须**同时提到两个名字，且原名排在新名之前**（`indexOf` 双非负 + 顺序）。
这一条同时抓住三类资源内容错误：少占位符（名字没出现）、占位符写反（顺序失败）、
两侧同一占位符（缺一个名字）。

同理，Robolectric 默认 locale 是 `en_US` ⇒ 本文件其余用例跑的全是**英文**那一份，
`values-zh/` 的内容**一行都不会被读到**；故另加一条 `qualifiers = "zh-rCN"` 的同款形状断言。
没有它，中文资源被写成方向反的句子时**测试全绿而中文用户看到反的**。

##### 10 组变异表（两遍一致）

| # | 变异 | 判定 | 捕获者 |
| --- | --- | --- | --- |
| M1 | `describe` 未跳过分支退回一句状态词（**缺陷原样复活**） | 捕获 | `OperationPlanTest > describe of a rename plan spells out the original and the new name()` |
| M2 | 两个参数对调（新名在前） | 捕获 | 同上 |
| M3 | 跳过回落方向反了（回落到「将重命名」） | 捕获 | `OperationPlanTest > a skipped rename op without a reason falls back to the skip status word()` |
| M4 | 丢掉跳过分支（一律走正向句子） | 捕获 | 同上（2 failed） |
| M5 | 分支判据用错（把未跳过当跳过） | 捕获 | 同上（3 failed） |
| M6 | `resId()` 把 `RenamePlanRename` 映射到旧状态词资源 | 捕获 | `PixFoldTextTest > the rename plan line produced by the domain resolves with both real file names` |
| M7 | `resId()` 映射到跳过状态资源 | 捕获 | 同上 |
| M8 | **英文资源**少写一个占位符（第二个文件名被吞） | 捕获 | 同上（**这条逼出了上面的形状判据**） |
| M9 | **中文资源**调换两个占位符（方向反） | 捕获 | `PixFoldTextTest > the chinese rename plan line keeps both names with the original first` |
| M10 | **中文资源**两侧写成同一个占位符 | 捕获 | 同上 |

```
总数 10 / 捕获 10 / 存活 0 / 锚点缺失 0 / 编译失败 0
还原核对: 全部逐字节一致(OperationPlan.kt 2c5cec10989e / TextKey.kt 9368222f75cb /
          PixFoldText.kt 84d829c5369f / values/domain_strings.xml ad7a49621f20 /
          values-zh/domain_strings.xml c3e73ebb9a48)
```

##### ⚠️ 本轮踩到的工具缺陷：备份按 basename ⇒ 双语资源互相覆盖

`values/domain_strings.xml` 与 `values-zh/domain_strings.xml` **同名**。W18 第一遍的备份写
`BAK / p.name`，于是后写的（中文）覆盖了前一份（英文）；M8 变异后"还原"把**中文内容写进了英文文件**，
该文件在剩余组里一直是坏的 ⇒ **M8 判成"存活"、M9/M10 判成"编译失败"，三个结论全部作废**。
破案证据：第一遍汇总把两个同名文件打印成同一个哈希 `c3e73ebb9a48`。
修法：备份保留**相对路径**（`BAK / p.relative_to(ROOT)`），重跑后两个哈希不同、10 组全捕获、两遍一致。
教训已写进脚本 KDoc —— `values*/` 下有 **6 组同名资源文件**，任何备份/还原都要保留相对路径。
英文文件已从 `HEAD` 版本恢复并重新施加 R2 改动（`git diff --stat` 复核 = `2 files changed, 8 insertions(+)`，
`grep -c "重命名"` 在英文文件上 = **0**）。

##### 结论与越界核对

- **R2 关闭**。§4 表格 R2 行状态改为「✅ 已修复（轮 14 / W18）」。
- **未新增待裁决项**；R3/R4 仍未决，均不阻塞。
- 本轮**按用户授权**新增了 1 个 `TextKey` 并**首次改动 `app/**`**（2 个资源 + 1 个映射 + 1 个测试），
  但 `RenamePlanPage.kt` 等 UI 布局文件**一行未动**；**未改构建脚本**；**未新增依赖**。
  `HANGOFF.md` **本轮一字未改**（与轮 13 同款处置）：`HANGOFF.md:2690-2693` 记的是**轮 9 当时**
  "明令禁止新增键"这件事，属历史记录；**R2 的当前状态以本台账 §4 表格为准**（已改 ✅）。
- 闸门读数（本轮四道闸门后**单独重跑**了 `:app:lintDebug --rerun-tasks` 与
  `:app:assembleDebug --rerun-tasks` 以免沿用缓存）：`:app:lintDebug` ⇒
  `32 actionable tasks: 32 executed`，报告 mtime **22:21:53** ⇒ **新鲜读数**，
  `0 errors, 0 warnings, 4 hints`（4 条仍全为 `AutoboxingStateCreation` hint）。
  `:app:assembleDebug --rerun-tasks` ⇒ `41 actionable tasks: 41 executed`，
  APK 33,402,024 字节（mtime 22:22:15；比上一版**小** 3,877 字节 —— 属正常波动，
  两个新字符串同时写进了 `resources.arsc`，已用 `zipfile` 逐字节回读证实
  `Rename %1$s → %2$s` 与 `重命名 %1$s → %2$s` 都在 APK 内）。
- 测试计数口径：`domain` 源码 `@Test` = **429** = XML **429**；`app` 源码 `@Test` = **403**，
  XML = **402**，差 1 仍是 `NegativeControlTest`（被 `unitTests.all { exclude(...) }` 排除）。

---

#### 轮 15（2026-10-04 23:3x）— W19 **R3 修复**：覆盖的撤销给出「三选一」警告（**首次改 UI 布局**）

- 提交：`b83098d` fix(d3): 覆盖已有文件后撤销先警告并让用户选范围（R3）
- 改动：**16 files**（生产 7 个：`domain/plan/Execution.kt` +112/−9、`domain/plan/PackagePlan.kt` +17、
  `domain/plan/OperationPlan.kt` +9、`domain/plan/DangerAction.kt` ±1、
  `app/…/ui/plan/ExecutionReportPage.kt` +134/−2、`app/…/ui/workflowa/WorkflowASteps.kt` ±6、
  `app/…/ui/workflowb/WorkflowBSteps.kt` ±6，以及 2 个双语资源
  `app/src/main/res/values/strings_plan.xml` +13、`values-zh/strings_plan.xml` +12；
  测试 7 个：新建 `app/…/ui/plan/UndoScopeDialogTest.kt`（14 用例）、
  新建 `app/…/ui/plan/UndoScopeDialogStyleTest.kt`（2 用例）、
  `app/…/ui/plan/PlanWiringTest.kt` +46（17 → 19）、`app/…/ui/plan/PlanUiTest.kt` ±9/−4、
  `app/…/ui/plan/IssueFilterTest.kt` ±2（机械改签名）、
  `domain/plan/UndoAlignmentTest.kt` +71/−29（17 → 18）、
  `domain/plan/DangerActionTest.kt` +45/−9（16 → 17））
- 闸门：app **420** tests / domain **431** tests / 0F 0E 0S / lint **0 errors, 0 warnings, 4 hints** / assembleDebug OK
- 变异：31 组，**两遍一致 31 捕获 / 0 存活 / 0 锚点缺失 / 0 编译失败**
- 结论 / 阻塞 / 待裁决：**R3 关闭**（§4 表格状态已改）。R4 仍未决，不阻塞。

##### 用户裁决与本轮授权

用户 `m04079` 第 ③ 项原文：「**添加警告，仅选择当前操作生成的撤销还是全部删除还是取消，
记得按钮颜色要区分开，把取消放到最后边**」。四条要求逐条对应到实现：

| 用户要求 | 实现 |
| --- | --- |
| 添加警告 | `ExecutionReport.needsUndoWarning = overwrittenCount > 0`；底栏撤销按钮**不再直接撤销**，改为先开 `UndoScopeDialog` |
| 三个选择 | `UndoScope.ThisRun`（仅撤销本次生成的）/ `UndoScope.Everything`（全部删除，含被覆盖的）/ 取消 |
| 按钮颜色要区分开 | 两个执行档 `Button(colors = dangerActionButtonColors())`（浅色实心 `error` / 深色 `errorContainer`）；取消是 `TextButton`，**无底色**。像素级判据见下 |
| 取消放到最后边 | `Column` 里顺序 = 标题 → 正文 → 安全档 → 危险档 → **取消**；`cancel sits below both scopes in the laid out dialog` 用 `getBoundsInRoot().top` 钉死 |

##### 缺陷逐字回顾（修之前）

`HANGOFF.md:2686-2689`（轮 7 / W11 登记）：「**R3(缺陷)**:`OutputConflictPolicy.Overwrite` +
输出已存在时,报告报 `Succeeded`、`createdPath` = **被替换的那个旧文件**路径,撤销据此推导
`DeleteCreated(该路径)` ⇒ 真执行会**删掉用户原有的 CBZ**,且被覆盖的内容无法恢复。」

根因不是"少写了一个 if"，而是**同一个问题在两个地方各判了一次、判据不同源**：
危险动作机制（`DangerAction.overwritesExisting`）认识这个场景，**撤销推导却从不查策略**。
所以修法是**把判据收成一处**（`VolumePackPlan.overwritesExisting`），让报告、危险动作、撤销推导共用。

##### 修复（生产改动）

1. `domain/plan/PackagePlan.kt`：`VolumePackPlan` 增
   `val overwritesExisting: Boolean get() = !skipped && conflictPolicy == OutputConflictPolicy.Overwrite && volume.outputExists`
   —— **全仓唯一判据**。`DangerAction.kt` 的 `OverwriteExisting` 分支随即改用它（原为同义的重复表达式）。
2. `domain/plan/OperationPlan.kt`：打包成功分支改为
   `createdPath = plan.outputPath, originalPath = if (plan.overwritesExisting) plan.outputPath else null`
   —— 覆盖项**留下痕迹**，新建项**不留**。改名分支的 `originalPath` 含义不同（R1 用来存原名），故
   `Execution.kt` 判覆盖时**必须按 kind 分叉**。
3. `domain/plan/Execution.kt`：
   - `private fun ExecEntry.isOverwrite(kind: ExecKind) = kind == ExecKind.Package && originalPath != null`
     —— **kind 分叉是强制的**：改名侧 R1 用 `originalPath` 装"要改回去的原名"，若不看 kind，
     每一次改名都会被算成"覆盖了别人的文件"，一撤销就删掉用户的原图。
   - 新增 `enum class UndoScope { ThisRun, Everything }`，KDoc 写明 `ThisRun` 是安全档
     （**永远不会删被覆盖的文件**）、`Everything` 是危险档。
   - `sealed interface UndoOp` 增 `data class DeleteOverwritten(val path: String) : UndoOp`
     —— 与被覆盖项**类型可分**（不是给 `DeleteCreated` 加个布尔标志：类型不同才能在 `when` 里穷尽）。
   - `UndoRecord` 增 `val overwrittenOpCount: Int get() = ops.count { it is UndoOp.DeleteOverwritten }`。
   - `ExecutionReport` 增 `overwrittenCount = entries.count { it.isOverwrite(kind) }` 与
     `needsUndoWarning = overwrittenCount > 0`。
   - `internal fun undoOpsOf(report, kind, scope: UndoScope = UndoScope.ThisRun)`；
     `fun undoRecordOf(report, scope: UndoScope = UndoScope.ThisRun)`
     —— **默认必须是保守的那一档**：忘了传参数只是"少删一点"（还能再点一次），
     默认成危险档则会**不可逆地多删**。
4. `app/…/ui/plan/ExecutionReportPage.kt`：
   - 形参 `onUndo: () -> Unit` ⇒ **`onUndoScope: (UndoScope) -> Unit`，且故意不给默认值**。
     KDoc 小节「## 为什么 [onUndoScope] 必须带 [UndoScope] 参数」写明：若仍收 `() -> Unit`，
     界面能让用户确认"全部删除"而调用方照旧按安全档回滚 —— **警告弹了、选择也做了、结果什么都没变，
     比不弹更糟**。没有默认值，每个调用点都得表态。
   - 撤销按钮：`if (report.needsUndoWarning) scopeDialogOpen = true else onUndoScope(UndoScope.ThisRun)`
     —— **没有覆盖项时一次都不打扰用户**（这是"仅在确有危险时才警告"的边界）。
   - `UndoScopeDialog(overwrittenCount, onChoose, onCancel)`：标题（`headlineSmall` + `colorScheme.error`）
     → 正文（`pluralStringResource(R.plurals.report_undo_warning_body, count, count)`）
     → 安全档 `Button` → 危险档 `Button` → 取消 `TextButton`，全部 `fillMaxWidth`。
     KDoc 解释**为什么不用 `AlertDialog`**：M3 的 `AlertDialog` 只有 `confirmButton`/`dismissButton`
     两个槽位，摆不下三个同级按钮；而槽位位置随平台排版变化，会直接破坏"取消在最后边"。
     故用 `Dialog` + `Surface`（`androidx.compose.ui.window.Dialog` 全仓此前零使用，随 compose-ui 发布，**不是新依赖**）。
   - ⚠️ 四条文案全部在 `Dialog` **lambda 外面**解析（`stringResource` 不能在非 `@Composable` 的
     `semantics {}` 里调用，而 `Dialog` 的 content lambda 里解析也没问题 —— 放到外面是为了让
     "文案"与"结构"分离，测试可从语义树独立取值）。
5. 两个生产调用点（`WorkflowASteps.kt:318`、`WorkflowBSteps.kt:201`）：
   `onUndoScope = { scope -> undoMessage = performUndo(undoRecordOf(currentReport, scope)).message }`
   —— **档位真的传下去了**，不是接住就扔。

##### 红→绿（**第一版红是假的**）

⚠️ 首版断言引用了 `report.overwrittenCount` / `needsUndoWarning` / `UndoScope` —— 这些**还不存在**，
于是得到的是**编译错误，不是 RED**。正确顺序：先只写"用既有 API 能表达的断言"拿到真 RED，
再补新 API 的断言。真 RED（`artifacts/red_r3.log`, RC=1）是两条真断言失败：

```
覆盖项必须在报告里留下「这个位置原本有东西」的痕迹 —— 否则撤销无从区分「本次新建」与「替换掉别人的」
  ==> expected: <E:\漫画库\_output/海贼王 第102卷.cbz> but was: <null>
Rename 的执行项不携带「被覆盖」痕迹  ==> expected: <null> but was: <第01卷_p001.jpg>
```

XML `tests=18 failures=2 errors=0`。第二条暴露了 **kind 分叉的必要性**：R1 让改名成功项也带
`originalPath`（装原名），所以"有没有 `originalPath`"**不能**当作"是否覆盖"的判据。

⚠️ 我第一版的**改名侧断言本身写错了**（断言 `originalPath` 为 null，与 R1 的契约冲突）——
改成 `overwrittenCount == 0` + `!needsUndoWarning` + 两档推导结果相等，才是在陈述真契约。

##### 变异验证：31 组两遍（`artifacts/w19_run_pass1.log` / `pass2.log`）

新增静态锚点校验器 `artifacts/check_anchors.py`（`ast.parse` 静态求值，**绝不 `exec`** —— 轮 12 踩过
"exec 历史脚本真的写了一版变异进生产文件"）。W19 覆盖面：计划层唯一判据（M1–M4）、报告覆盖痕迹
（M5–M6）、`isOverwrite` kind 分叉（M7–M9）、计数与警告开关（M10–M11）、安全档跳过与两档判据
（M12–M14）、`overwrittenOpCount`（M15–M16）、`undoRecordOf` 默认值（M17–M18）、危险动作复用（M19）、
弹框触发（M20–M22）、两个 `onChoose` 接线（M23–M24、M27）、取消接线（M25）、关框（M26）、
双语文案与占位符（M28–M31）。

**第一遍：26 捕获 / 3 存活（M3、M28、M31）/ 2 编译失败（M29、M30）。**
五处异常**逐条归因，全部是"我的变异/测试设计错"，不是生产缺陷**：

| 异常 | 归因 | 处置 |
| --- | --- | --- |
| M3 存活（`overwritesExisting` 丢 `!skipped`） | **等价变异**：`buildPackagePlans` 里 `skipped = v.outputExists && conflictPolicy == Skip`，而 `overwritesExisting` 第二合取项是 `policy == Overwrite`；策略不可能同时是两者 ⇒ **`!skipped` 被蕴含**。穷举 `(outputExists, policy)` 六种组合，两个谓词完全相同 | 不判等价了事：**直接构造**出生产路径造不出的"Overwrite + 跳过"计划（`skippedOverwritePlan`），新增 `a skipped volume is never counted as overwriting` ⇒ 该合取项从此**可证伪** |
| M28 存活（英文 `other` 变格丢 `%1$d`） | 原用例只有 **1** 个覆盖项 ⇒ Android 取 `one` 变格，**`other` 变格根本没被读到** | 新增 2 个覆盖项的用例跨过复数边界 ⇒ 捕获 |
| M31 存活（中文正文丢 `%1$d`） | 其余用例跑在 Robolectric 默认 locale（`en_US`）⇒ **中文资源根本不会被加载** | 新增 `qualifiers = "zh-rCN-w400dp-h2000dp"` 用例 ⇒ 捕获 |
| M29/M30 编译失败 | **变异本身非法**：我把 `<string name="report_undo_everything">…</string>` 整条换成了 `<string name="report_undo_this_run">…</string>`，于是同一个 key 出现两次、原 key 消失（实测 `report_undo_this_run` occurrences=2、`report_undo_everything`=0）⇒ AAPT 失败 | 改为**只换内容、保留原 key** ⇒ 两遍均捕获 |

⚠️ 这条 M29/M30 是**方法论的坑**：变异"编译失败"看起来像"测试通过了"，实际是**变异体没被真正构建**，
什么都没证明。**变异必须保证文件仍可编译**，否则该组结果无效。

**第二遍（补强后，测试冻结）：31/31 全捕获，与第一遍的差异恰好是上面五组**：

```
CHANGED M3: ★存活★ -> 捕获      CHANGED M28: ★存活★ -> 捕获
CHANGED M29: 编译失败 -> 捕获    CHANGED M30: 编译失败 -> 捕获
CHANGED M31: ★存活★ -> 捕获      其余 26 组两遍均为"捕获"
```

##### 测试补强（3 处真缺口）

- `domain/plan/DangerActionTest.kt`：抽 `skippedOverwritePlan(live)` 助手 + 新增
  `a skipped volume is never counted as overwriting`（前置断言 `skipped == true` **且**
  `conflictPolicy == Overwrite` **且** `volume.outputExists`，再断言 `!overwritesExisting`）。
  这条用例的价值不在"多一条"，而在于它**让一个此前在真实数据上不可证伪的合取项变成可证伪**。
- `app/…/ui/plan/UndoScopeDialogTest.kt` +4：`the two scope buttons do not render the same sentence`
  与 `the chinese choices are worded differently from each other`（从**语义树**取
  `config.getOrNull(SemanticsProperties.Text)` 拼接后的文本**两两比较** —— 不看资源 id，
  故"两个按钮渲染同一句"可证伪）、`the warning body still contains the count when more than one file was replaced`、
  `the chinese warning body also contains the count`。
- ⚠️ **这是同类盲区第二次出现**（轮 14 已记：「用 `TestText`/`resolveText` 从**同一份资源**算期望，
  内容写错也会通过」）。本轮把该教训推广成一条可复用判据：**凡是"键 → 译文"，判据必须落在
  "渲染出来的句子有什么可观测形状"**（含不含数字、两两是否相同、顺序），而不是"等于同一个函数算出的值"。

##### 结论与越界核对

- **R3 关闭**。§4 表格 R3 行状态改为「✅ 已修复（轮 15 / W19）」。
- **未新增待裁决项**；R4 仍未决，不阻塞。
- 本轮**按用户授权改动 `app/**`**：新增 UI 组件（`UndoScopeDialog`）、改 UI 布局（撤销按钮 +
  页面尾部）、新增 4 条双语字符串 + 1 个 `<plurals>`。**未改构建脚本**（`build.gradle.kts` /
  `libs.versions.toml` / `.mise.toml` 逐条 `git diff --quiet` 确认未动）；**未新增依赖**
  （`androidx.compose.ui.window.Dialog` 随 compose-ui 发布）；**未改 `HANGOFF.md`**
  （`HANGOFF.md:2686-2689` 记的是**轮 7 当时**的登记，属历史记录；R3 当前状态以本台账 §4 为准）。
- 测试计数口径：`domain` 源码 `@Test` = **431** = XML **431**；`app` 源码 `@Test` = **421**，
  XML = **420**，差 1 仍是 `NegativeControlTest`（被 `unitTests.all { exclude(...) }` 排除）。
  `app` 从 402 → 420（+18：`UndoScopeDialogTest` 14 + `UndoScopeDialogStyleTest` 2 + `PlanWiringTest` 2）。
- **像素级判据**（`UndoScopeDialogStyleTest`，`@GraphicsMode(NATIVE)` 真取位图）：
  浅色 `safe=#FFB3261E danger=#FFB3261E cancel=#FFE9E7EF surface=#FFE9E7EF`；
  深色 `safe=#FF8C1D18 danger=#FF8C1D18 cancel=#FF29292F surface=#FF29292F`
  ⇒ 两个执行档实心错误色，**取消像素与对话框表面色逐字节相同**（即"无底色"），
  且 `channelDistance(cancel, safe) >= 40` 保证两者可分辨。
  ⚠️ 对话框是**独立窗口**，`captureToImage` 必须对对话框节点自身截图，再把子节点 root 坐标
  翻译成位图坐标；直接对页面截图量不到对话框像素。
#### 轮 16（2026-10-05 00:2x）— W20 **R4 修复**：页码自检从「与自身比」改为「页表 ↔ 页序」

- **用户授权原文**（`m04079` 第④项）：「想一想怎么修」。
- **修的是哪个缺陷**：§4 R4 行登记的 `validatePageConsistency(volume)`。
  它的输入是 `renumberPages(volume)` 的**输出**（`ComicInfoXml.kt` 里 `previewOf` 同时调两者），
  而 `renumberPages` 的返回长度**就是** `volume.pageOrder.order.size`：
  ```kotlin
  // 修前（:110-113）
  val pages = renumberPages(volume)
  if (pages.size != volume.pageOrder.order.size) { ... }   // 列表与自身比长度 ⇒ 恒假
  ```
  ⇒ `pageIssues` **恒空**、`consistent` **恒真**、`PackPreviewPage.kt:204 if (!preview.consistent)`
  的告警分支**不可达** —— 用户在真机上永远看不到那一句。
- **两种登记过的修法与取舍**：§4 R4 行给了两条路 —— ①改看「磁盘上真实存在的页」；
  ②校验 `volume.pages` 与 `pageOrder.order` 是否一致。**取第②条**：第①条要碰文件系统，
  与原型「不碰真实文件系统」的既定约束冲突（`performUndo` 恒返回 `applied = false` 即该约束的体现）。
  这是**在被授权的两个选项之间做选择**，不是新立产品决策。
- **⚠️ 对 §4 R4 行「恒真」措辞的一处纠正（重要，防后人误读）**：
  该行说「②校验侧补零位数与重编号侧恒等、③`startsWith("$expected.")` 恒真」——
  **③ 并不恒真**。`expected` 的补零宽度用 `pages.size`、`renumberPages` 用 `order.size`，
  是**同一规则的两份独立拷贝**（两侧各写了一次 `if (size >= 100) 4 else 3`）。
  只改坏其中一处即会报错 —— W13 记录的变异「页码补零不随页数自适应(恒 3 位)」
  正是被这一条抓住的。故本轮**保留**该检查，并在 KDoc 里写明它为何不是自证。
  **真正恒假的只有数量检查**（列表与自身比长度），本轮修的就是它。
- **修法（生产 3 文件）**：
  ```kotlin
  // 修后（:126-136）
  val listed = volume.pages
  val order = volume.pageOrder.order
  if (listed.size != order.size) {
      issues.add(Msg(TextKey.PageIssueCountMismatch, listOf(listed.size, order.size)))
  } else {
      val orderIds = order.map { it.id }.toSet()
      val missing = listed.count { it.id !in orderIds }
      if (missing > 0) {
          issues.add(Msg(TextKey.PageIssueMissingFromOrder, listOf(missing, listed.size)))
      }
  }
  ```
  - **置换不变性是硬约束**：人工拖拽**合法地**改 `order` 而不改 `pages`
    （`sort/PageOrder.kt` 的 `moveItemTo`/`applyRule` 只改 `order` 与 manual/pinned 集合，
    **从不改 `images`**）。故判据必须是**集合/成员**比较，绝不能逐位置比对 ——
    否则用户每拖一次就看到一条假警告。这一点有专门用例钉死。
  - **新增 `TextKey.PageIssueMissingFromOrder`** + 双语资源 `domain_page_issue_missing_from_order`
    （en `%1$d of %2$d pages are not in the page order` / zh `有 %1$d 页（共 %2$d 页）不在页序里`）
    + `PixFoldText.resId()` 映射（穷尽 `when`，不加即编译失败）。
    长度相同但成员不同是数量检查覆盖不到的一类，故值得单独一句文案。
  - 两类检查的**两个来源必须不同源**是本轮的核心不变量，已写进 KDoc。
- **⚠️ 本轮同时改了一条既有测试（必须记下，不是顺手改的）**：
  `ComicMetadataTest` 的 `page preview follows the page order, not the raw page list`（`:185`）
  原先构造「卷自称 18 页、页序只排了 3 页」的分叉卷，却断言 `preview.consistent` ——
  那条断言**是骑在坏检查上的**（因为坏检查恒真，它才绿）。修好检查后它立刻变红。
  它的**核心断言**（预览只画页序里的 3 页）仍然有效且保留；
  `assertTrue(preview.consistent)` 改为断言**必须报出分叉**。
  诚实说明：这不是「测试挡路就改测试」，而是那条断言本身在断言一个**错误的行为**。
- **红→绿**（两层都取了真 RED，见 `artifacts/red_r4.log`）：
  - 领域 RED：`433 tests completed, 1 failed` /
    `ComicMetadataTest > a volume whose page list and page order disagree is reported as inconsistent() FAILED` /
    `org.opentest4j.AssertionFailedError: 页表与页序长度不同时必须判为不一致`；
    XML `ComicMetadataTest tests=46 failures=1 errors=0`。
  - UI RED：`422 tests completed, 1 failed` /
    `PackPreviewPageTest > a volume whose page list and page order disagree is flagged on the preview page` /
    `AssertionError: ... contains '⚠️ Page numbers do not match the page order: Page item count does not match the page order (15 ≠ 16)' ... is not displayed!`；
    XML `PackPreviewPageTest tests=21 failures=1 errors=0`。
  - GREEN：领域 `434 → 0 failed`；app `425 → 0 failed`（`artifacts/green_r4.log`）。
- **W20 变异扫荡（21 组，两遍，`artifacts/mutate_w20.py` / `w20_run_pass*.log`）**：
  第一遍 **14 捕获 / 7 存活 / 0 锚点缺失 / 0 编译失败**；补强后第二遍
  **21 捕获 / 0 存活 / 0 锚点缺失 / 0 编译失败**，还原核对 5 个文件**逐字节一致**。
  覆盖：数量检查的输入来源/条件/参数顺序、成员检查的存在性/判据/参数顺序/键选择、
  补零交叉校验的两处分叉、新键的三个资源面（占位符数量、占位符方向、中英抄错）、
  映射串到两个邻居。
  - **7 个存活者的归因（全部是「我的测试/变异设计错」，不是生产缺陷）**：

    | 存活者 | 归因 | 处置 |
    |---|---|---|
    | M11 编号检查恒真 / M12 丢掉 `.` 分隔符 | **我的变异是等价变异**：`renumberPages` 产出的名字恒为 `NNN.ext`，故 `startsWith("")` 与 `startsWith("$expected")` 在该数据集上与原式同判 | 换成两个**非等价**变异（无条件触发 / 序号从 0 起），均被捕获 |
    | M15–M18 英文与中文资源占位符删掉/对调 | **真测试缺口**：期望值用 `TestText.of(同一个资源)` 算出 ⇒ 资源改坏时两边同时变，断言照样绿（与轮 14 记录的同源期望值盲区同一个根因） | 新增 2 条**形状断言**用例（见下） |
    | M21 中文文案抄成英文 | **真测试缺口**：Robolectric 默认 locale 是 `en_US`，中文槽位**不会被任何用例加载** | 新增用例带 `@Config(qualifiers = "zh-rCN")` |

  - **补强的 2 条新用例**（`app/src/test/kotlin/com/pixfold/d1/ui/text/PixFoldTextTest.kt`）：
    `the missing from order wording keeps both numbers in argument order` 与
    `the chinese missing from order wording is chinese and keeps both numbers in order`。
    判据刻意**不比对整句**，只断言 ①两个数目都真的出现在句子里（占位符没被删）
    ②它们**按参数顺序**出现（没被对调）③中文槽位真的是中文（含 CJK 码点）。
    用 `listOf(3, 47)` 而不是 `listOf(1, 18)`：两个数目互不为子串，`indexOf` 才有判别力。
    数字是**参数**（数据）而非界面措辞，故写死数字不违反「文案不写死」原则。
  - 这是轮 14 记录的同源期望值盲区的**第二次命中**，也是 i18n 那一项（`m04079` 第⑤项）
    要系统解决的问题的第一手证据。
- **四道闸门**（`artifacts/gates_r4.log`，`--rerun-tasks` 全量，`RC=0`、`BUILD SUCCESSFUL in 44s`、
  `62 actionable tasks: 62 executed`）：
  - domain **32 files / 434 tests / 0F 0E 0S**（源码 `@Test` = 434，与 XML 一致）；
  - app **44 files / 425 tests / 0F 0E 0S**（源码 `@Test` = 426，差 1 恒为 `NegativeControlTest`）；
  - lint **0 errors, 0 warnings, 4 hints**（4 条 hint 全是既有的 `AutoboxingStateCreation`，
    位点在 `DragReorderList.kt`，与本轮无关；`warningsAsErrors` 仍为零告警）；
  - APK `pixfold-d1/app/build/outputs/apk/debug/app-debug.apk` **33,420,916 bytes**、
    mtime `2026-10-05 00:22`，`find app/src/main domain/src/main -newer <apk>` 为空 ⇒ 比所有源文件新；
    `resources.arsc` 里新串**三种形态逐一复核为 YES**（键名、英文、中文），
    另加 `domain_page_issue_count_mismatch` 中英各一条。
- **边界核对**：`pixfold-d1/domain/build.gradle.kts`、`pixfold-d1/app/build.gradle.kts`、
  `pixfold-d1/build.gradle.kts`、`pixfold-d1/settings.gradle.kts`、
  `pixfold-d1/gradle/libs.versions.toml`、`.mise.toml`、`AGENTS.md`、`README.md`、`HANGOFF.md`
  逐条 `git diff --quiet HEAD -- <f>` ⇒ **全部 unchanged**。
  未新增依赖、未改构建脚本、未改 `HANGOFF.md`、未连真机、未 push。
  本轮改动面 **9 files, +223 / −8**（生产 3 文件：`ComicInfoXml.kt` / `TextKey.kt` /
  `PixFoldText.kt` + 2 个双语资源；其余为测试与本轮新增的测试助手 `TestText.raw`）。
- **R4 关闭**。§4 表格 R4 行状态改为「✅ 已修复（轮 16 / W20）」。
- **一处诚实的范围声明**：本轮修的是「页表 ↔ 页序分叉**能被报出来**」。
  但生产路径下 `ComicVolume` 的构造点唯一（`MetaEdit.kt:81 comicVolumeOf`，`:101 pageOrder = pageOrderOf(pages)`
  与 `pages = pages` 同源），且 `model/Metadata.kt:92` 的**类型级默认值**就是 `pageOrderOf(pages)`，
  故**生产路径下这两者目前永不分叉** —— 新检查在当前代码里是**防御性的**，
  它防的是未来新增的 `copy(pages = …)` 路径与测试构造。这一点写在这里，避免后人误以为
  「修好之后用户马上就会看到新告警」。真正不可达的是**修复前**的告警分支；修复后它可达，
  只是当前生产数据触发不到。这与 §4 R4 行「非本轮引入，是既有设计」的记录一致。

---

#### 轮 17（2026-10-05 01:2x）— W21 **i18n 自动化**：键 → 译文的结构契约 + 既有 i18n 复查

- 提交：`339d9fa` test(d3): i18n 键→译文结构契约（W21：另立独立一侧，16 组变异两遍全捕获）
- **用户授权原文**（`m04079` 第⑤项）：「这属于 i18n 内容，顺带实现了，然后看下前面相关 i18n 的要不要调整」。
  （授权本就包含两件事：**实现**键→译文自动化 + **复查**既有 i18n 是否需要调整。）
- **为什么必须另起一组测试（既有两组都看不见）** —— 这不是"再加几条断言"，是补一个**盲区**：
  1. `PixFoldTextTest` 的期望值一律经 `TestText.of(同一个资源)` 从**被测的那份资源**里取 ⇒
     资源文案改坏时实际值与期望值**一起变**，断言恒真。这正是 W20 里 M15–M18（中英占位符
     被删/对调）、M21（中文文案抄成英文）**全部存活**的根因，也是轮 14 记录的同一个根因。
  2. `resId()` 是**穷尽 `when` + `private`**，只保证「每个键都有个资源」，**不保证指向正确的那一个**。
     把两条相邻映射互换（如 `PageIssueMissingFromOrder` 指到 `PageIssueCountMismatch`），
     编译、lint、既有单测**全都照旧通过** —— 因为没有任何一处**独立地**知道「这个键该是哪条资源」。
  3. 故本组测试**完全不碰 `resId()`/`pluralQuantityIndex()`**，改从**命名约定**这一侧独立反查：
     `TextKey.FooBar` → `domain_foo_bar`（107/107 成立，0 例外；枚举名**不含数字** ⇒ snake 往返无歧义）。
     一侧按名字取模板、另一侧按键走映射渲出来，再逐字对拍 —— 两侧各自成立，才第一次让
     「映射指错 / 复数档取错 / 翻译漏了」可观测。
- **新增文件**：`pixfold-d1/app/src/test/kotlin/com/pixfold/d1/ui/text/TranslationCoverageTest.kt`
  （**313 行 / 5 条契约**，`@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])`）：

  | # | 用例 | 断言什么 |
  |---|---|---|
  | 1 | `every_key_resolves_to_the_resource_named_after_it_in_both_languages` | 每个键在**中英两侧**都存在按约定命名的 `string`/`plurals`；两侧 kind 相同；占位符 `List<Slot(下标,类型)>` **逐位相同**且下标从 1 起按出现顺序连续；再 `resolveText` 渲染并与**按名字取到的模板**逐字相等 |
  | 2 | `every_plural_key_selects_its_variant_with_the_quantity_argument` | 11 个复数键必须**由数量参数选中档位**（先断言 `one != other` 否则无判别力，再把**所有**数量参数一起取同一值，验证不取决于数量下标排第几个）；并用 `assertEquals(PLURAL_KEYS, covered)` 钉死复数键集合 |
  | 3 | `every_plural_variant_keeps_the_same_placeholder_shape` | **各档占位符同形**（英 `one` / 英 `other` / 中 `other` 三处签名一致）—— 由本轮 M14 存活逼出来，见下 |
  | 4 | `only_the_documented_keys_read_the_same_in_both_languages` | 中英渲染相同的键**必须**恰为白名单 `SAME_IN_BOTH_LANGUAGES`（5 个 `MetaField*` + `WarnIllegalCharSeparator`） |
  | 5 | `only_the_documented_keys_share_the_same_english_wording` | 英文撞句的键组**必须**恰为白名单 `SHARED_ENGLISH_WORDING`（`CasePolicy*`/`ExtPolicy*` 三对） |

- **实现要点（都是踩出来的）**：
  - **同进程切语言**：`english.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) })`
    ⇒ 中英可在**同一条断言内**对拍，不必为中文单开 `@Config(qualifiers=…)` 配置类。
  - **渲染逐字相等比"首尾前缀/后缀"强**：差异若落在句子中间（`ExecSummary` 的 `item:`/`items:`），
    只看首尾是看不见的。`format` 写死 `Locale.ROOT` 免得受运行环境默认 locale 影响。
  - ⚠️ **`data class` 不能省**：`Slot`/`Template` 起初写成普通 `class`，`assertEquals` 退化成
    **引用比较**，任何两串都判不等（`artifacts/i18n_contract_run2.log`：
    `Expected <[…Slot@1cfb99c5…]>, actual <[…Slot@1a7ab85e…]>`）。改 `data class` 即绿。
  - ⚠️ **Kotlin 模板变量后紧跟 CJK 会被吞进标识符**（CJK 是合法标识符字符）：
    `"$key:在$language资源里…"` 里 `$language资源里找不到为` 被当成**一个变量名** ⇒
    `Unresolved reference 'language资源里找不到为'`（`artifacts/i18n_contract_run1.log`，首次编译失败）。
    修法：写成 `${language}`。
  - 两个白名单**逐字写死**是刻意的：它们是「这里允许相同」的**人工裁决记录**，
    不是从资源里算出来的；新增一项都必须写明理由，否则就是漏翻或抄串了。
- **红→绿**：新测试是**纯新增文件**，先编译失败（上面的 CJK 坑，是真 RED 的一种形态），
  再 `Slot` 非 `data class` 失败，修好后 **5 用例 GREEN / 0 failures / 0 errors**
  （`TEST-com.pixfold.d1.ui.text.TranslationCoverageTest.xml tests=5`，`artifacts/i18n_contract_run3.log`）。
- **W21 变异扫荡（16 组，两遍，`artifacts/mutate_w21.py` / `w21_run_pass*.log`）** ——
  **目的不是"再扫一遍"，而是证明新测试有杀伤力**：专门复活 W20 曾存活的变异型。
  第一遍 **15 捕获 / 1 存活 / 0 锚点缺失 / 0 编译失败**；第二遍 **16 捕获 / 0 存活**；
  3 个文件还原**逐字节一致**（`PixFoldText.kt e4aa68ce3ea7` / `values/domain_strings.xml 63d03d98feb8` /
  `values-zh/domain_strings.xml 921b3a4e4a74`）。覆盖五面：

  | 面 | 组 | 结果 |
  |---|---|---|
  | `resId()` **映射指错**（此前从未有测试能看见） | M1–M3 | 全捕获（M1/M3 由新测试，M2 由既有 `PixFoldTextTest`） |
  | 占位符删/对调/类型改（**W20 M15–M18 同型，当时全存活**） | M4–M8 | **全捕获** —— 新测试的直接价值 |
  | 中英槽位互串 / 中文抄英文（**W20 M21 同型，当时存活**） | M9–M10 | 全捕获 |
  | 复数：两档同文 / 数量下标改错 / 漏配复数键 | M11–M13 | 全捕获 |
  | 复数各档占位符 / 键间撞句 | M14–M16 | M14 第一遍存活 → 补契约后捕获；M15/M16 捕获 |

  - **M14 的归因（第三遍命中同一个根因，如实记下）**：变异「英文 `warn_too_long` 的 `one` 档删掉
    第 2 个占位符」第一遍**存活**。原因是**期望值与实际值同源**：对数量为 1 的情形，
    契约 ④ 的期望值取自 `one` 档模板、实际值也由 `one` 档渲染，两边同时变 ⇒ 断言恒真。
    这与 W20 的 M15–M18、轮 14 记录的盲区是**同一个根因的第三次命中**。
    **修法**：加契约 ③ `every_plural_variant_keeps_the_same_placeholder_shape`，
    拿**另一档**当独立的一侧 —— `other` 档的占位符签名与 `one` 档无关。
    定点复核确认 M14 现在**恰好**由这条新用例捕获，随后才跑第二遍全量。
  - ⚠️ **本契约只管形状，不管词形**：`1 item` vs `1 items` 哪个才对需要词典，机械判定不了；
    词形正确性靠契约 ② 的 `one != other` + 人工评审。**边界如实记在此处，不夸大覆盖率。**
- **既有 i18n 复查（授权原文的后半句「看下前面相关 i18n 的要不要调整」）**：
  结论 = **既有 i18n 无需调整**，依据是两条正交的普查 + 一次人工裁决核对：
  - **资源面**（只读审计脚本 `artifacts/i18n_audit.py`，六类可疑 `UNMAPPED` / `SNAKE-MISMATCH` /
    `MISSING-EN` / `MISSING-ZH` / `DUP-RES` / `SAME-TEXT`）⇒ **全部为 0**。
    数据面：`TextKey` **107** 条 ↔ `resId()` 映射 **107** 条 ↔ 按约定命名的资源 **107/107** 命中；
    中英占位符「下标+类型」集合 **107/107 相同**、相对顺序 **107/107 相同**；
    非位置占位符 **0**；全仓无 `values-en/`（英文就在默认槽位）。
  - **生产层**（CJK 字面量普查 + Compose 语义槽裸字面量普查，13 处）⇒ **无用户可见文案漏翻**：
    命中全是 ①KDoc 注释、②`mock/` 的**模拟数据**（`"旅行照片"` 等 —— 是数据不是文案）、
    ③开发者可见的 `require` 消息（`Sort.kt`、`CbzWritePlan.kt`、`CbzWriter.kt`）、
    ④`MetaEdit.kt:46` 的磁盘文件名模板、⑤**符号前缀**（`· `/`→ `/`← `/`⛔ `）与数据插值。
  - **一次人工裁决核对**：`ui/plan/PlanComponents.kt:229` 的危险弹框正文写死 `"⛔ $body"`，
    而同文件 `:150` 的同类前缀走资源键（`plan_skip_reason_prefix`）。仓库**三处注释**已裁决
    「符号前缀不属于文案」（`ui/workflowa/ProposalTable.kt:173`、
    `values-zh/strings_plan.xml:23`、`values-zh/strings_workflowa.xml:143`）⇒
    **不构成漏翻**，只是同一约定有两种写法（一处成键、一处写死）。**如实记为观察项，不改**。
  - 另一处同类观察：`ui/HomeScreen.kt:67` 与 `ui/settings/LicensesScreen.kt:198` 直接写死
    `"PixFold"` 而未引用 `R.string.app_name`（`values/strings.xml:3`，`translatable="false"`，
    代码里**零引用**；`AndroidManifest.xml:28` 用了）。值与资源**逐字相同**，不是漏翻。
- **四道闸门**（`artifacts/gates_i18n.log`，`--rerun-tasks` 全量，`RC=0`、`BUILD SUCCESSFUL in 42s`、
  `62 actionable tasks: 62 executed`；跑前 `rm -rf` 了两个 `test-results` 目录以保证不是陈旧 XML）：
  - domain **32 files / 434 tests / 0F 0E 0S**（源码 `@Test` = 434，与 XML 一致）；
  - app **45 files / 430 tests / 0F 0E 0S**（源码 `@Test` = 431，差 1 恒为 `NegativeControlTest`）；
  - lint **0 errors, 0 warnings, 4 hints**（4 条 hint 全是既有的 `AutoboxingStateCreation`，
    位点 `DragReorderGrid.kt:175-176` 与 `DragReorderList.kt:94-95`，与本轮无关）；
  - APK `app/build/outputs/apk/debug/app-debug.apk` **33,420,916 bytes**、mtime `2026-10-05 01:21`，
    `find app/src/main domain/src/main -newer <apk>` 为空 ⇒ 比所有源文件新。
- **边界核对**：`domain/build.gradle.kts`、`app/build.gradle.kts`、`build.gradle.kts`、
  `settings.gradle.kts`、`gradle/libs.versions.toml`、`.mise.toml`、`AGENTS.md`、`README.md`、
  `HANGOFF.md` 逐条 `git diff --quiet HEAD -- <f>` ⇒ **全部 unchanged**。
  `git diff --stat HEAD -- domain/src/main app/src/main` ⇒ **空**（生产代码零改动）。
  本轮改动面 = **1 个新测试文件**（`TranslationCoverageTest.kt`，313 行），未新增依赖、
  未改构建脚本、未改 `HANGOFF.md`、未连真机、未 push。
- ⚠️ **临时探针已删除**：`app/src/test/kotlin/com/pixfold/d1/_ProbeLocale.kt`（locale 机制验证 +
  107 键 × 两语言渲染全量对拍用的探针）已 `rm -f`，确认不存在。它**从未入库**。
- **i18n 一项关闭**。§4 表格新增 i18n 行并标记 ✅（见下）。

#### 轮 18（2026-10-05 11:2x）— W22 **第⑥项：警告图标去 emoji，改用矢量图标**（用户 `m00673`）

- 提交：`fb4a656` feat(d3): 警告图标改用矢量图标，文案里彻底去掉 emoji 前缀（第⑥项）
- **用户原话**（`m00673`，逐字）：「警告图标你直接用的emoji不好看，没有更好看的矢量图吗」。
  这是继 ①`m04079` 解释 R1、②`m04081` 新增 `TextKey`、③`m04079` 三选一撤销警告、
  ④`m04079` 修不可达校验分支、⑤`m04079` i18n 之后的**第六项用户指令**。
- **勘察结论（先查后改）**：
  - 全仓 emoji 分两种载体 —— **资源串里的前缀**（`⚠️ ` 11 处、`⛔ ` 2 处，跨 8 个 `values*` 文件）
    与 **Kotlin 字面量**（`app/…/ui/plan/PlanComponents.kt:229` 的 `text = "⛔ $body"`）。
  - 涉及 **13 个键**，但只有 **5 个是 `TextKey`**（`DangerDialogTitle`/`SummaryCleanEmptyDirsWarning`/
    `SummaryOverwriteCbz`/`SummaryDeleteSourceDirWarning`/`RenameSummaryCleanEmptyDirs`），
    另有 6 个**纯资源键** + 1 个 `<plurals>`（`a_naming_conflicts`）+ 2 个**退化键**（见下）。
    ⇒ `TranslationCoverageTest`（枚举 `TextKey.entries`）**只能覆盖那 5 个** ——
    这是它管不到第⑥项的原因，**不是它的缺陷**。
  - **仓库既有裁决**：`· `/`→ `/`← ` 这类**符号前缀不属于文案**（三处注释已明写）⇒
    本轮**只处理 `⚠️`/`⛔` 两类警示前缀**，`· ` 一律不动（4 处既有断言逐字保持：
    `ui/plan/PlanUiTest.kt:81`/`:135`、`ui/plan/PlanWiringTest.kt:207`、`ui/workflowa/NamingPageTest.kt:586`）。
- **两个「退化键」的处置（本轮最容易做错的一步）**：`plan_skip_reason_prefix`（英/中）与
  `a_plan_skip_reason`（英/中）去掉 `⛔ ` 后**只剩 `%1$s`**，即**不再承载任何文案**。
  故处置是**删除键本身**，而不是留一个空壳 —— 保留会让「键存在」这个信号变成噪声，
  且 `TranslationCoverageTest` 第③条（渲染逐字相等）对它们已无意义。
  **踩坑记录**：第一版资源脚本只删了英文侧两个键，**中文侧两个键被漏掉** ⇒
  新写的 `EmojiFreeSourceTest` 跨文件扫描**当场报出这个不对称**
  （`values-zh/strings_plan.xml:24` / `values-zh/strings_workflowa.xml:144`），补第二个锚点脚本修正。
  **这是新增守卫的第一个真实战果：人工脚本漏改，守卫抓住。**
- **实现（三层，全部零新依赖、零构建脚本改动）**：
  1. **资源层**：`artifacts/edit_w22_res.py` **25 处替换 / 8 个文件**，逐处 `count(old) == 1`
     否则 `ANCHOR-MISS` 整体不写盘；备份 `artifacts/w22bak/`（保留相对路径）。
     补 `artifacts/edit_w22_res2.py` **4 处**（2 个中文退化键 + 2 处已失实注释），备份 `w22bak2/`。
  2. **图标层**：新建 2 个 `vector` drawable（`ic_warning_triangle` / `ic_forbidden`，
     Material Symbols 几何，Apache-2.0 已在 `ui/settings/LicensesScreen.kt:49` 声明）。
     **为何两个而不是一个**：`⚠️`（警告）与 `⛔`（禁止）语义不同，区分**靠形状不靠颜色**
     （实心警告三角 vs 空心禁止环）—— 与 `PinToggle` 的 filled/outlined 同判，色盲用户也能分辨。
     断言取值纪律：只描述「正确的图标长什么样」，且只取 alpha 稳定为 0/255 的点
     （`(12,12)=240`、`(17,17)=80` 是反锯齿边缘，不可用于断言）。
  3. **组件层**：新建 `app/src/main/kotlin/com/pixfold/d1/ui/text/WarnIcon.kt`（**106 行**）——
     `const val TAG_WARN_ICON` + `val WARN_ICONS: Map<Int, Int>`（**键是资源 id**）
     + `warnIcon(resId: Int): Int?` / `warnIcon(msg: Msg): Int?`
     + `@Composable fun WarnLine(icon, text, style, color, modifier, iconSize, textModifier)`。
     为让 `warnIcon(msg)` 复用映射，`app/…/ui/text/PixFoldText.kt:159` 的 `TextKey.resId()`
     由 `private` 放宽为 `internal`（**不是**新开第二张表 —— 两张表必然漂移）。
     `WarnLine` 用 `contentDescription = null`（图标语义正文里已用文字说清，避免读屏念两遍，
     与 `SchemeEditor`/`PinToggle` 同判）；**需要按文本断言的 testTag 必须走 `textModifier`**，
     挂 `Row` 上会让 `assertTextEquals` 找不到文本节点。
- **11 个映射条目**：5 个 `TextKey`（经 `resId()` 折入）+ `plan_danger_action` +
  `report_undo_warning_title` + `<plurals name="a_naming_conflicts">` + `pack_page_issues` +
  `pack_delete_warning` + `pack_excluded_warning` —— **全部 → `ic_warning_triangle`**；
  `ic_forbidden` 只用于两处 `⛔` 语义（`PlanComponents.kt` 的危险弹框正文与跳过行原因）。
  `ic_exclamation_circle` **已被 `SchemeEditor.kt:413` 占用**（语义 = 「润色信息」按钮）⇒ 不复用。
- **调用点接线 7 处**：`ui/plan/PlanComponents.kt`（危险动作行 / 跳过行 / `ConfirmStep.General`
  摘要行 / 危险弹框正文 / 弹框标题）、`ui/plan/ExecutionReportPage.kt`（撤销范围对话框标题，
  `iconSize = 24.dp` 与 `headlineSmall` 齐平）、`ui/workflowa/NamingPage.kt`（冲突计数）、
  `ui/workflowa/RenamePlanPage.kt`（跳过原因）、`ui/workflowb/PackPreviewPage.kt`（页码告警 /
  删除源目录告警 / 未勾选告警）、`ui/workflowa/ProposalTable.kt`（注释）。
  ⚠️ **`describe()` 行保持裸 `Text` 是有意的**：它渲染的 5 个键
  （`PackCreateCbz`/`PackWriteComicInfo`/`PackPackPages`/`PackDeleteSourceDir`/`PackSkipOutputExists`）
  **不在 `WARN_ICONS` 里**（它们不是警示文案）⇒ 不该长图标。
  ⚠️ `TAG_CONFIRM_DANGER_TITLE` 必须留在 `Text` 上而非移到 `Row`（`ui/plan/PlanUiTest.kt:140`
  做的是 `assertTextEquals`）—— 全仓唯一有文本断言的警示 tag。
- **测试（新建 5 个文件 / 23 条用例；改 2 个既有文件 +3 条）**：
  - `app/src/test/kotlin/com/pixfold/d1/EmojiFreeSourceTest.kt`（**332 行 / 6 用例**）——
    **这是守卫，不是图标测试**：扫 `res/` 下全部 XML（29 文件）与 `app/src/main/kotlin`（43 文件），
    断言「警示 emoji 一个都不剩」+「退化键被删除而非留空壳」。
    **①资源与②Kotlin 必须是两条用例**（合一起时资源断言先失败会**短路**掉 Kotlin 扫描 ⇒ 假绿）；
    另有 4 条**自检**用例防「扫描空转」（阈值 `res.files ≥ 20` / `res.stringTags ≥ 300` /
    `kt.files ≥ 30`；检测器必须认得它替换掉的两种 emoji，且**不得**误报 `→`/`·`/`%1$s`；
    注释遮蔽必须**保留换行** —— 首版把换行也吃掉，报出的行号整体偏移）。
  - `ui/text/WarningIconRenderTest.kt`（**3 用例**）/ `ForbiddenIconRenderTest.kt`（**4 用例**）——
    **像素级断言**（`SIZE = 96`、`SCALE = 4`，采样每格中心）：不只断言「节点存在」，
    而是断言**图标长得对**（三角内实心、感叹号真挖空、禁止环有缝）。
    KDoc 记下教训：「语义层断言『节点存在』不等于『图标看得见』，更不等于『图标长得对』」。
  - `ui/text/WarnIconMappingTest.kt`（**6 用例**）—— 11 个键逐一有映射、无多余键、
    `TextKey` 半边与资源 id 半边一致。
  - `ui/text/WarnLineTest.kt`（**4 用例**）—— 组件契约（图标画出、`contentDescription = null`、
    `textModifier` 落在 `Text` 上而非 `Row` 上）。
  - 既有：`ui/plan/PlanUiTest.kt` **10 → 11**、`ui/workflowb/PackPreviewPageTest.kt` **21 → 23**。
  - 临时探针 `_ProbeIconShape.kt` **已删除**（转正为上面两个渲染测试）。
- **变异清扫 21 组 / 两遍 / 0 逃逸**（`artifacts/mutate_w22.py` M1–M10 + `mutate_w22b.py` M11–M14）：
  - 第一遍 **8 捕获 / 2 存活**（M6/M7 = 删 drawable 的 `fillType="evenOdd"`）。
    **判定为「已证等价的变异设计错」，不是测试缺口**，证据可复核：
    `artifacts/grid_base.txt` 与 `grid_mut.txt` 是整幅 24×24 像素网格，`cmp` **逐字节 IDENTICAL**
    —— 因为三角与感叹号**绕向相反**，`nonZero` 与 `evenOdd` 在该形状上等价。
  - 第二遍换测 **KDoc 里写明的真正失败模式**（子路径缺失 ⇒ 图标退化成实心块）：
    M11 删感叹号子路径 / M12 删禁止环子路径 / M13 映射指向不存在的 drawable /
    M14 映射 11 条全改指一个图标 ⇒ **4 捕获 / 0 存活**。
  - **累计 21 组两遍 / 0 逃逸**。9 个备份文件与工作树**逐文件 sha256 SAME** ⇒ 无变异残留。
- **四道闸门（`--rerun-tasks` 全量重建，`artifacts/gates_w22_final3.log`）**：
  **`RC=0`、`BUILD SUCCESSFUL in 55s`、`62 actionable tasks: 62 executed`**（跑前删了两个
  `test-results` 目录以保证不是陈旧 XML）；domain **32 files / 434 tests / 0F 0E 0S**、
  app **50 files / 456 tests / 0F 0E 0S**、lint **0 errors, 0 warnings, 4 hints**（4 条为既有
  `AutoboxingStateCreation`）；APK **33,437,589 字节**、mtime 2026-10-05 11:53:09。
  计数口径：app 源码 `@Test`（**已排除 `NegativeControlTest`**）**456** = XML **456**；
  逐项对账 `431(HEAD 含 NC) − 1(NC) + 23(5 个新文件) + 1(PlanUiTest) + 2(PackPreviewPageTest) = 456`，
  **无残差**。类名对拍：源码 52 类 / XML 50 类，「有源码无 XML」恰为
  `NegativeControlTest` + `TestText`（助手），**无孤儿测试类**。
  ⚠️ 口径修正：轮 16 记的「app 源码 = XML + 1」在本轮**不再成立** —— 本轮已先把
  `NegativeControlTest` 从源码计数里排除，两边**直接相等**。
- **真机核对（第⑥项是 UI 面改动，按 §7.4 必须过）—— 10 个警示面全部通过**：
  设备 PJX110 / Android 16 / 1264×2780 px = 361dp×794dp / 系统语言 zh-CN，
  新 APK 已重装（`adb install -r` ⇒ `Success`，`lastUpdateTime=2026-10-05 11:25:12`）。
  逐面取证（dump 与像素读数见下）：
  ①A 线通用确认框（`artifacts/cfA1.xml`）：无 `⚠️`，图标 `[156,1484][212,1540]` = **56×56 px = 16dp**；
  ②A 线危险确认框（`artifacts/cfA2.xml`）：标题与正文两处均无 emoji，图标列 `x=156`，
  **对照空白 `[96,1176][152,1232]` 为纯 `(0,0,0)`** ⇒ 图标确在图标列、非背景噪声；
  ③B 线 `pack_delete_warning` + `plan_danger_action`（`artifacts/b2del.xml`）：两行各有图标
  （`[42,1260]` / `[42,1407]`），其下空白对照区无图标；
  ④B 线普通确认 `domain_summary_delete_source_dir_warning`（`artifacts/bcf1.xml`）：图标 `[206,1484][262,1540]`；
  ⑤B 线覆盖路径 `domain_summary_overwrite_cbz`（`artifacts/bcf3.xml`）：图标 `[156,1453][212,1509]`，
  且路径参数正确渲染；
  ⑥报告页（`artifacts/brep.xml`）：「共 5 项：成功 5 · 跳过 0 · 失败 0」；
  ⑦撤销范围对话框标题（`artifacts/bundo2.xml`）：图标 **96×96 px = 24dp**（标题是 `headlineSmall`
  故放大），对话框 `[72,802][1192,2118]` **未溢出**，三按钮顺序未变（取消仍在最后）；
  ⑧A 线改名计划页危险动作行：无 `⚠️`；⑨B 线打包页源目录处置：无 `⚠️`；
  ⑩英文模式复核（见下）。
  像素读数（`artifacts/pngprobe.py --top`）：图标区典型值 `(242,184,181)`（错误色淡化后）
  或 `(187,166,167)`，**与「无图标」对照空白区（纯 `(0,0,0)` / `(248,224,225)`）可分**。
- **i18n 中英对照真机复查（一并完成）**：设置 → 语言 → `English`，走通 A 线全流程
  （`artifacts/en_a1.xml` / `en_a3.xml` / `en_a4.xml` / `en_a4d.xml` / `en_cf1.xml` / `en_cf2.xml`）：
  `Step 1 Sort and preview` / `Dangerous action: Clean up empty folders (needs a separate
  confirmation before running)` / `Confirm dangerous action` / `“Clean up empty folders” is checked; ...`
  —— **全部无 emoji 且图标照常绘制**（`artifacts/w22_en_cf2.png`）。
  语言已复原为「跟随系统」（`artifacts/langrestored.xml` 全中文）。
  ⚠️ 注意：mock 数据（`旅行照片`、`进击的巨人`）本身是中文，**那是数据不是 UI 文案**。
  ⇒ 6 个 `TranslationCoverageTest` 覆盖不到的**纯资源键**在英文下也渲染正确。
- **本轮新发现（⇒ §4 新登记 R5）**：**R2 的键在生产路径上不可达**。
  `OperationPlan.describe()`（`domain/…/plan/OperationPlan.kt:137`）是
  `RenamePlannedOp.describe()`（`:87`，产 `Msg(TextKey.RenamePlanRename, ...)`）的**唯一调用者**，
  而 `OperationPlan` 在 `app/src/main` **零引用**；app 侧唯一 `describe()` 调用点
  `app/…/ui/plan/PlanComponents.kt:144` 的 `PlanRow` 形参类型是 **`VolumePackPlan`**
  （`PlanComponents.kt:107`，唯一调用点 `app/…/ui/workflowb/PackPreviewPage.kt:351`）⇒
  走 `domain/…/plan/PackagePlan.kt:82 fun VolumePackPlan.describe()`，**产不出 `RenamePlanRename`**。
  `app/…/ui/workflowa/RenamePlanPage.kt` 完全不消费 `describe()`。
  ⇒ **R2「已修复」但用户在真机上永远看不到**（真机实测证实）；真机 dump 里出现的
  `→ 旅行照片_001.jpg` 是 2026-09-27 的**旧镜像**，早于 R2 提交（10-04 22:24），不能当作 R2 已测的证据。
  **修法二选一属产品决策，记 §4 R5 不自行改。**
- **守卫边界如实记**：`EmojiFreeSourceTest` **只扫 `app/src/main/kotlin`，不含 `domain/`**
  （emoji 只在 app 侧，有意为之）⇒ 该守卫全绿**不等于**全仓注释没腐化。
  本轮据此单独做了一轮**注释腐化修正**：`artifacts/edit_w22_comments.py` 修掉 5 处仍写着 `⚠️` 的注释
  （`domain/…/comic/ComicInfoXml.kt:120`、`domain/…/text/TextKey.kt:33`、
  `domain/…/plan/DangerAction.kt:52`、`domain/src/test/…/plan/DangerActionTest.kt:142`、
  `domain/src/test/…/comic/ComicMetadataTest.kt:417`），备份 `artifacts/w22cbak/`，
  `git diff --stat` = **5 files / +5 / −5**（纯注释，不改行为）。
  改完单独跑了一次四闸门：**`RC=0`、`BUILD SUCCESSFUL in 55s`、`62 executed`，读数与上同**；
  并确认**不需要重建 APK**（`:app:compileDebugKotlin` `UP-TO-DATE`，`:app:assembleDebug`
  复跑 `41 actionable tasks: 41 up-to-date`）。
- **第 6 处注释腐化（补记）**：全树复扫（`grep -rn '⚠️\|⛔' app/src domain/src`，约 110 处命中）
  逐处确认**全部落在注释/KDoc/资源头注释内、无一处是真实 UI 文案**；其中
  `app/…/ui/plan/ExecutionReportPage.kt:265` 的 KDoc 仍在断言
  「**警告**:标题带 ⚠️、正文说清"覆盖掉的内容已经找不回来了"」—— 已被本轮改动证伪
  （标题的警示改由 `ic_warning_triangle` 矢量图标承担，资源里已无 ⚠️ 前缀）。
  前 5 处由 `artifacts/edit_w22_comments.py` 按**字面前缀**匹配批量修掉，第 6 处写的是
  **关于前缀的断言**、形态不同故漏网；本处直接改措辞
  `标题带 ⚠️` ⇒ `标题带警示图标`。提交 = **`02503be`** docs(d3)（纯注释，1 file / +1 / −1）。
- **补记后重跑四闸门**（`--rerun-tasks` 全量，`artifacts/gates_w22_final4.log`）：
  **`RC=0`、`BUILD SUCCESSFUL in 39s`、`62 actionable tasks: 62 executed`**；
  domain **32 files / 434 tests / 0F 0E 0S**、app **50 files / 456 tests / 0F 0E 0S**、
  lint **0 errors, 0 warnings, 4 hints**（仍是既有 `AutoboxingStateCreation`）、
  APK **33,437,589 字节** —— 与改动前**逐项一致** ⇒ 纯注释改动零行为影响，由读数证实。
- **未做 / 不宣称**：① `· `/`→ `/`← ` 符号前缀**按既有裁决不动**；
  ② 两个退化键的**删除**是「去前缀后无文案」的直接后果，不是产品决策；
  ③ 真机上**未验证** R2 的逐项句子（不可达，见 R5）与 R4 的页码告警
  （mock 数据自身一致，需专门造分叉卷才能触发）；
  ④ 未改构建脚本、未新增依赖、未改 `HANGOFF.md`/`AGENTS.md`/`README.md`
  （三处阶段快照仍不同步，属产品决策）。

#### 轮 19（2026-10-05 17:4x）— W23 **R4 真机取证 + R5 裁决落账**（用户 `m02554`）

- 提交：`b154b52` docs(d3): 台账回写轮 19（W23：R4 真机取证 + R5 裁决）

- **用户原话**（`m02554`，逐字）：第一行「1」，随后「这2个应该怎么办」。承接 `m02502`「这是什么功能」
  （问的是 §4 R5 的 `TextKey.RenamePlanRename`）。「这2个」指 §6.5 未做清单第 1 项里**如实标注的两处真机未验证项**：
  R2 的逐项句子、R4 的页码自检告警。两处都不是"顺手能补"的 —— 一处是**键不可达**，一处是**mock 数据自身一致**。
- **本轮定位**：**取证 + 裁决落账轮，零源码改动**（收工时 `git status --porcelain` 为空）。故**本轮无变异**
  （变异是给"新写的代码"用的；本轮既没写生产代码也没写测试，投变异只能得到"捕获者不存在"的假结论）。
- **R5 落账：用户选 ① 保持现状**。三个选项的原文见 §4 R5 行；本轮**只改状态列**，
  键 / 双语 `domain_rename_plan_rename` / `PixFoldText.resId()` 映射 / `domain` 与 `app` 的相关测试
  **一律不动**；`RenamePlanPage` **不接通** `describe()`，`PlanRow` 也**不泛化**到 `OperationPlan`。
  ⚠️ **这条不是"修好了"而是"决定不修"**：`RenamePlanRename` 在生产路径上**依然不可达**，
  §4 R5 左侧的事实链**一字未变**，台账措辞按选项①原文记作**「无生产出口，刻意」**。
- **R4 取证：为什么必须造探针**。`grep -l '页码与页序不一致' artifacts/*.xml` 在轮 18 落账时**零命中**，
  但"没看到"**不等于**"看不到" —— 要么是渲染链坏了，要么是数据永远一致。按 §4 R4 已记录的事实链
  （`ComicVolume` 的两个构造点都让 `pages` 与 `pageOrder = pageOrderOf(pages)` 同源；
  B 线既无重排入口、`MetaField` 7 个值也不含 pages）判断是**后者**，但这需要一次**可观测的证伪尝试**才能定论。
- **探针（临时，只动 mock 数据）**：`domain/src/main/kotlin/com/pixfold/d1/domain/mock/MockLibraryBData.kt`
  加一个 `private const val R4_PROBE = true`，并把 `aot-1` 的 `volume(...)` 调用接成
  `.let { if (R4_PROBE) it.copy(pages = it.pages.dropLast(1)) else it }` ⇒
  `1 file changed, 4 insertions(+), 1 deletion(-)`（5408 → 5592 字符）。
  **刻意只改 mock 数据**：生产判据、`validatePageConsistency`、`ComicInfoXml.kt` 一行未动 ——
  探针要复现的是"数据分叉"，不是"改坏检查"。
  注入前先 `cp -p` 备份到 `artifacts/w24bak/MockLibraryBData.kt`（sha256 双方 `0e558ce02891…`）。
- **探针构建与装机**：`:app:assembleDebug` ⇒ `artifacts/r4_probe_build.log`，`BUILD SUCCESSFUL in 22s`、
  `41 actionable tasks: 6 executed, 35 up-to-date`，APK **33,488,270 字节**、mtime 2026-10-05 17:07:28；
  `adb.bat install -r` ⇒ `Performing Streamed Install` / `Success`。
- **真机取证（中文）** `artifacts/r4b.xml` + `artifacts/w24_r4_warning.png`：
  告警文本逐字 `页码与页序不一致：页码项数与页序长度不一致（15 ≠ 16）`，
  `TextView` bounds `[112,2136][1165,2192]`；同一子树的**图标节点** `View [42,2136][98,2192]` = 56×56 px = **16dp**
  ⇒ **图标与文案同排**（`WarnLine` 的 `Row`）。子原因逐字来自 `domain_page_issue_count_mismatch`（15 ≠ 16 = 列表比页序少 1）。
- **真机取证（英文，切系统语言复核 i18n）** `artifacts/r4en1.xml` + `artifacts/w24_r4_en.png`：
  告警文本逐字 `Page numbers do not match the page order: Page item count does not match the page order (15 ≠ 16)`，
  `TextView` bounds `[112,2206][1222,2318]`；图标节点 `View [42,2234][98,2290]`（换行后仍与首行对齐）。
  取证后语言已复原（`artifacts/r4restore.xml` 全中文界面）。
- **反向对照（决定性的一步）**：换回干净包（源码已还原后重建）再进同一个「打包」页 ⇒ `artifacts/r4c_pack.xml`：
  `grep -c '页码与页序不一致'` = **0**、`grep -c 'Page numbers do not match'` = **0**，
  且页码行 `text="1"` 的 bounds 从探针包的 `[42,2199]` 变成 `[42,2143]` ⇒ **精确上移 56 px**，
  正好等于图标行 `View [42,2136][98,2192]` 的高度 ⇒ **告警行确实消失、布局回流**。
  这一步排除了"探针没生效"和"告警本来就显示"两种误判：同一台设备、同一条路径，只差一个数据分叉。
- **还原与验证**：`cp` 自备份还原 ⇒ `MockLibraryBData.kt` sha256 = `0e558ce028918759d04f0abe0d9201bb49f4e19547b539a08c203b92735cc09a`
  （= 注入前干净值）；`grep -rn 'R4_PROBE' domain/src app/src | wc -l` = **0**。
- **重建与重装**：四闸门 `--rerun-tasks` 全量（先删两个 `test-results`）⇒ `artifacts/gates_w25_restore.log`，
  `RC=0`、`BUILD SUCCESSFUL in 1m 12s`、`62 actionable tasks: 62 executed`、domain 32/434、app 50/456、lint 0/0/4；
  APK **33,437,589 字节**、mtime 2026-10-05 17:19:26、sha256 `1a2164ea74a298575e3937fa06b38bd6f4f37c2fb2d3adeabec8bbf7151983d1`
  ⇒ 与干净基线 `artifacts/w24bak/app-debug.clean.apk` **逐字节相同**；装机 `Success`，
  设备侧 `base.apk` = 33,437,589 字节 ⇒ **设备回到与基线一致的安装**。
- **拆除**：`svc power stayon false` ⇒ `stay_on_while_plugged_in = 0`（用户原值）、
  `screen_off_timeout = 86400000`（用户原值）、`am force-stop com.pixfold.d1`。
- **一次 APK 字节差被查清并关闭**：中途一次重建得 33,487,738 字节（比基线 **+50,149**），当时怀疑回归。
  逐条目比对后：155 个 zip 条目名集合**完全相同**，每条目 `(file_size, compress_size, CRC)` **逐条全等**，
  `sum(compress)` / `sum(uncompress)` / 中央目录长度估算也相同 ⇒ 差异纯在**容器层**
  （条目**顺序**不同：首个顺序差异 @21，clean = `classes8.dex` / new = `classes9.dex`；签名块偏移不同），
  **内容等价、非回归**。权威复跑（`--rerun-tasks` 全量）后与基线**逐字节相同** ⇒ 疑点关闭。
- **本轮验的是什么，不夸大**：验的是**渲染链**（domain 判据 → `Msg` → `WarnLine` → 真机像素），
  **不是**"R4 告警在生产中可见" —— 生产可达性的结论**没有变**：`ComicVolume` 的两个构造点仍同源、
  B 线仍无入口能把 `pages` 与 `pageOrder` 弄分叉，所以取证**必须**靠 mock 探针。
  这一条与 §4 R4 状态列里已有的"防御性"诚实声明一致，互为佐证。
- **未做 / 不宣称**：① **未改**任何生产代码、**未改**构建脚本、**未新增**依赖；
  ② 边界文件（`app/build.gradle.kts`、`domain/build.gradle.kts`、`build.gradle.kts`、`settings.gradle.kts`、
  `gradle/libs.versions.toml`、`.mise.toml`、`AGENTS.md`、`README.md`、`HANGOFF.md`）逐条 `git diff --quiet HEAD`
  全部 unchanged；③ 未推送、未并入 `dev`；④ 未改三处阶段快照（`HANGOFF.md` §9 顶 + `AGENTS.md` 头 + `README.md`）。
- **结论 / 阻塞 / 待裁决**：无新增待裁决项；**§4 R5 已裁决关闭 ⇒ §4 已无待裁决项**。
  真机上仅剩的未验证项 R2 已由用户裁决为**刻意保留**（设计如此，非待办）。

---

#### 轮 20（2026-10-05 18:2x）— W24 **`HANGOFF.md` §9.1「D3 打包核心轮」订正 + 追记**（用户 `m03250`）

- 提交：`768bec7` docs(d3): 订正 HANGOFF §9.1「D3 打包核心轮」6 处失真，并追记 R1–R5/i18n/图标/合回 dev

- **用户原话**（`m03250`，逐字）：「先合回dev吧，**文档都回写了吗**」。后半问是触发点：
  台账（本文件）已逐轮回写，但**唯一权威文档 `HANGOFF.md` 一行都没回写** ——
  它的 §9.1「D3 打包核心轮」小节仍停在轮 5（W9，2026-10-04）写的状态，此后 34 个提交未进。
  这正是 `AGENTS.md:49` 变更路由要求的"先回写 `HANGOFF.md` 对应章节(唯一权威)"那一环。
- **本轮定位**：**文档订正轮，零源码改动**（收工时 `git status --porcelain` 为空）。故本轮无变异清扫。
- **决策：不改写历史数字，只"就地加注 + 追加追记"**。沿用轮 5（`:493-498`）已定的三条纪律：
  ① 已写下的历史读数一律保留原样、就地加 `> ⚠️` 注 —— 改写会让"当时的读数"消失，那正是最该留痕的东西；
  ② 沿用既有 `#### ✅ <主题>（日期）` 标题层级，**不新造层级**；
  ③ 边界（"**不是 D3 阶段完成**"）写在同一个小节里，读者才不会误读。
  > 该纪律正是轮 5 处理同类问题的产物（`:480-489` 那三处口径笔误也是"原始记录保留、就地加注"）。
- **订正块（6 处失真）**，逐条列明且一律保留原文：
  ① 标题「未合并、未推送」⇒ 现为**已 ff 合回 `dev`、仍未推送**；
  ② 「交付面」段「共 **8 个**提交」「改动**只在 `domain/**`**」⇒ **42 个**提交、
     净 `69 files / +8912 / −150`，且 `app/src/main` 也有 **20 files / +440 / −51**；
  ③ 「未做 / 不宣称」的「**真机核对** …… 判定**不需要**真机」⇒ **W22/W23 各做了 1 轮真机**；
  ④ 同段的「**`app/**` 一行未改**，故**没有任何 UI 层变化**」⇒ 已不成立（同 ②）；
  ⑤ 「⚠️ lint 读数的一处诚实例外」整段 ⇒ 该疑点**已关闭**（报告文件现为新鲜读数）；
  ⑥ 末尾「未推送、未合并 `dev` …… 是否并回由用户决定」⇒ 用户已决定并回（同 ①）。
  同时显式声明**仍然有效**的部分：领域层交付面（`OperationPlan` / `CbzWriter` / `CbzWritePlan`）、
  6 条关键设计决定、以及 R1/R2 的**登记本身** —— 均未被后续推翻。
- **追记小节**（`#### ✅ D3 打包核心轮·追记(2026-10-05:…)`，插在本小节末尾、`### D2` 之前）：
  性质声明、交付面与读数（42 提交 / 69 files +8912 −150 / domain 247→434 / app 399→456）、
  **R1–R5 五项裁决表**、i18n 契约、警示图标矢量化、真机 2 轮、变异累计 **401 组 / 0 逃逸**、
  合回 `dev`（`--ff-only`、无 merge commit、分支保留）、未做/不宣称。
- **写盘方式**：`artifacts/ledger_w26.py`（两遍制：默认干跑，`--apply` 才写盘；备份
  `artifacts/w26bak/HANGOFF.md`）。两条编辑**均为 grow 型**（锚点串保留在结果里），
  每条带 `count == 1` 锚点断言，以及新增块首行 `count == 0` 的**防二次写入** precheck。
- **干跑 → 写盘**：`BEFORE` = **3295 行 / 180,484 字符 / sha256 `343720ff102b…`**；
  首次干跑 `+7743` 字符，**复核所引数字时发现三处将要写错的事实**并修正、重跑得 `+7994`；
  `--apply` 后 = **3426 行 / 319,671 字节 / 188,478 字符**、sha256 **`c8fd0a404221…`**、
  CRLF 0 / bare CR 0 / 以换行结尾；三个关键串（D3 原标题 / 订正块首行 / 追记块标题）各 `count == 1`。
- **纯新增已验证（本轮最关键的闸门）**：`git diff --numstat -- HANGOFF.md` = **`131 0`**；
  `git diff -U0 | grep '^-'` 去掉 `---` 后**零行** ⇒ **未删除任何一行**；
  两个 hunk = `@@ -2612,0 +2613,19 @@`（订正块）与 `@@ -2711,0 +2731,112 @@`（追记块）。
- **阶段快照三处仍未动**（本轮最要紧的边界）：`HANGOFF.md:451` 门槛表
  `| D3 领域核心与适配层 | 🔄 部分已由 D1 提前交付(2026-09-19 校正)`、
  `HANGOFF.md:2881` 现状对照表 `| **打包** | ⬜ **未交付** |`、§9 顶部 `> 进度(` 行 ——
  **三者逐字未改**；`AGENTS.md`（注意：**在仓库根**，`pixfold-d1/AGENTS.md` **不存在**）与
  `README.md` 的 `git diff --quiet HEAD` 均 **unchanged**。
  追记里**如实标注**"这几处仍是旧值、本轮不自改"（`:2835` 与 `:2881` 附近）。
- **写盘前的三次事实复核**（避免把错误事实写进唯一权威文档 —— 干跑时逐条回源码/台账核对）：
  1. 「7 处调用点」⇒ 实测 `WarnLine(` 在 `app/src/main/kotlin` 是 **11 处 / 5 个文件**
     （`ui/plan/PlanComponents.kt` 6、`ui/workflowb/PackPreviewPage.kt` 3、
     `ui/plan/ExecutionReportPage.kt` 1、`ui/workflowa/NamingPage.kt` 1、
     `ui/workflowa/RenamePlanPage.kt` 1）；**`ProposalTable.kt` 未接 `WarnLine`**
     （`fb4a656` 对它 `+2/−1` **只改注释**，其 `:160-190` 仍用文字圆点 `"· …"`）⇒ 已改写。
  2. 「共 **4 个**已证等价变异」⇒ 台账逐轮登记的等价者**远多于 4**
     （W13 4 个、W14 2 个 N4/R2、W15 1 个 N6、W16 1 个 C5、W7 1 个 P5、
     W4 遗留 M5、W22 2 个图标 `fillType` 一对）⇒ 改为**不另立合计数**、逐轮列举，
     并保留 W13 只记"组"数不重算捕获数的口径。
  3. 「W17/W18 是 R1 修复轮」⇒ `:201` **W17 = R1 修复**、`:202` **W18 = R2 补齐**轮 ⇒ 已改写。
- **闸门**：文档轮，**未改任何 Kotlin**；仍按 §0.3 跑一次四道闸门确认未受影响
  （`--rerun-tasks` 全量 + 先删两个 `test-results`，读数见 §6.2）—— 与前一轮**完全一致**。
- **结论 / 阻塞 / 待裁决**：无新增待裁决项。**唯一权威文档至此与台账一致**；
  剩下的三处阶段快照同步仍是**产品决策**，本轮**未做**（见 §6.5 第 3 项）。

- **追记后的收尾（同轮补记，2026-10-05 18:3x–18:4x）**：追记落盘后自查发现**两处"写下即过期"的失真** ——
  追记里写的是「合回 `dev` 那一刻」的读数（`HANGOFF.md:2753` 交付面表「分支归宿」格
  `dev == d3/p1`、`HANGOFF.md:2826-2827` 正文 `[ahead 42]`），而**同一轮随后又落了 docs 提交**
  （都在 `dev` 上、`d3/p1` 未动）⇒ 两个断言当场失效。
  - **修法**：新建 `artifacts/fix_w24_hangoff_stale.py`（两遍制），**沿用轮 5 纪律不改写历史数字**，
    只就地追加 `> ⚠️` 注（`HANGOFF.md:2755` 与 `:2836`）说明"那是合并时点的快照"。
    `git diff --numstat` = **`11 0`**（纯追加）。
  - **⚠️ 订正注自己又过期了一次（本轮最该记住的教训）**：第一版注里写死
    「本轮又落 **3 个** docs 提交 ⇒ `dev` = `d3/p1` **+3**、`[ahead 45]`」——
    但**写下它的那个提交本身又让 `dev` 前进一格** ⇒ 实测立刻变成 `+4` / `[ahead 46]`。
    **任何写死提交数的注都会自我过期**。故新建 `artifacts/fix_w24_hangoff_stale2.py`
    把两处改为**时间无关表述**：只保留"合并时点读数（`[ahead 42]`）"这一不变的历史事实，
    把"此后领先多少"改为指向 `git status -sb` / `git rev-list --count d3/p1..dev` 的**实时输出**。
    脚本内加了反向断言（正文不得再出现 `**+3**` / `[ahead 45]`）。
  - 该订正**把 `5f5b233` 改写成 `3ff135e`**（`git commit --amend`）：改前已核实
    ① 全仓无任何文件引用 `5f5b233`、② 它是 `dev` 的 tip 且**未推送** ⇒ amend 不破坏任何记录；
    改写后 `git show --numstat` = **`12 0`**（仍为纯追加，零删除行）。
- **结论 / 阻塞 / 待裁决（补记）**：无新增待裁决项；订正只动 `HANGOFF.md`，**未改任何 Kotlin**。

- **终笔（同轮第三笔，2026-10-05 18:4x–18:5x）**：核对提交时间轴时发现 §6.2 补记① 存在**时点误标** ——
  它把 `artifacts/gates_w24b_fix.log`（mtime **18:29:19**）称作「订正 `HANGOFF.md` 两处时间性失真
  **之后**的复跑」，但首次订正提交 `3ff135e` = **18:40:25** ⇒ 那次复跑实际在订正**之前**。
  - **修法**：新建 `artifacts/ledger_w24_supp2.py`（两遍制；干跑 `chars 167883 → 168754`、
    两锚点各 `count == 1`、两 precheck 各 `0`）。① 把误标行就地改为
    「⚠️ **时点更正**：这一次复跑发生在首次订正**之前**、非「订正之后」」；
    ② 追加「轮 20 补记②」记下**真正在两次订正之后**的那次复跑；脚本含反向断言
    （正文不得再出现原误标串）。
  - `git diff --numstat` = **`13 1`**，**唯一删除行就是那条旧误标行**（已在提交前逐字核对）。
  - **提交 `1767739`**（`docs(d3): 更正台账 §6.2 补记里对闸门复跑时点的误标，并补记订正后的权威复跑`）。
  - **终态闸门复跑**（`artifacts/gates_w24d_final.log`，mtime **18:49:42**）：`RC=0` /
    `BUILD SUCCESSFUL in 41s` / **`62 actionable tasks: 62 executed`**；domain 32 files **434** /
    app 50 files **456** / lint **`0 errors, 0 warnings, 4 hints`**；APK **33,437,589 字节**
    （mtime 18:49:11）、sha256 **`1a2164ea74a298575e3937fa06b38bd6f4f37c2fb2d3adeabec8bbf7151983d1`**
    —— 与干净基线 `artifacts/w24bak/app-debug.clean.apk` **逐字节相同**；新鲜度 0。
    ⇒ **五次读数完全一致**，文档轮对构建与测试**零影响**。
- **结论 / 阻塞 / 待裁决（终笔）**：无新增待裁决项；`d3/p1` 停在 `09fc863` 未动，
  `dev` 已在其上，**仍未推送**（push 由用户处理）。三处阶段快照同步仍是**产品决策**，本轮**未做**。

---

## 4. 待裁决 / 阻塞（越界项写这里，不自行决定）

> 出现下列任一情况，**停止该项**并在此登记，然后继续**下一项**工作：
> 需要新产品决策、需要新增 `TextKey`/资源、需要改 `app/**`、需要真机结论、
> 需要新增依赖、需要产品阈值（如 HANGOFF §11 第 12 条的空间不足阈值）。

| # | 轮次 | 事项 | 为什么不能自行决定 | 状态 |
| --- | --- | --- | --- | --- |
| R1 | 3（W6） | `UndoOp.RestoreRenamed(from, to)` 在成功项上恒 `from == to`（空操作）—— `executeRenameOp` 成功分支的 `label` 与 `createdPath` 都取 `op.finalName`，报告里**无处**携带原文件名 | 修法有两种且语义不同：①扩 `ExecEntry` 加原名字段；②让 `undoRecordFrom(plan, report)` 从计划取 `op.proposal.image.fileName`（只有它手里有计划）。选哪种、以及 `from`/`to` 的最终语义，都属撤销记录的产品决策；且本轮契约是"既有可观测行为逐字不变" | ✅ **已修复**（轮 13 / W17：用户 `m04081` 采纳第 ① 种修法 —— `ExecEntry` 增 `originalPath`、`executeRenameOp` 从计划填、`undoOpsOf` 改取它作 `to`；6 组变异全捕获；详见 §3.1 轮 13） |
| R2 | 3（W7） | `RenamePlannedOp.describe()` 在无原因时只能给 `Msg(TextKey.OpStatusRename)` 一句状态词 | 改名侧**没有**逐项句子契约（`RenamePlanPage` 渲染的是"原名 → 新名"两列 + 状态标签）；要补齐就得**新增 TextKey**，而本轮明令禁止 | ✅ **已修复**（轮 14 / W18：用户 `m04081`「改吧」授权新增 `TextKey.RenamePlanRename` —— 未跳过项给出「原名 → 新名」逐项句子，双语资源 + `PixFoldText` 映射同步补齐；顺带修掉跳过项无原因时回落到 `OpStatusRename` 的错误方向；10 组变异全捕获，含 4 组资源内容变异；详见 §3.1 轮 14） |
| R3 | 7（W11） | `OutputConflictPolicy.Overwrite` + 输出已存在时，报告报 `Succeeded`、`createdPath` = **被替换的那个旧文件**路径，撤销据此推导 `DeleteCreated(该路径)` ⇒ 真执行会**删掉用户原有的 CBZ**，且被覆盖的内容无法恢复 | 「覆盖的撤销」是什么，属产品决策，三种语义完全不同：①当作新建 ⇒ 删掉（现状，会把用户旧文件也删了）②覆盖前先备份 ⇒ 撤销时恢复备份（要给 `ExecEntry` 加"被覆盖路径"字段）③覆盖项标为不可撤销（要把 `OverwriteExisting` 传进 `undoOpsOf`）。**危险动作机制已认识此场景**（`DangerAction.kt` 判 `OverwriteExisting`），只有撤销推导从不查策略。按 §7.1「有歧义先请用户裁决」，故只记录 | ✅ **已修复**（轮 15 / W19：用户 `m04079` 第③项裁决「添加警告，仅选择当前操作生成的撤销还是全部删除还是取消，记得按钮颜色要区分开，把取消放到最后边」—— 取的是**①的安全版**：默认档 `UndoScope.ThisRun` **永不删被覆盖的文件**，只有用户在弹框里显式选了 `Everything` 才会推导出 `DeleteOverwritten`；`VolumePackPlan.overwritesExisting` 收成全仓唯一判据，报告计数/危险动作/撤销推导共用同源；31 组变异两遍全捕获；详见 §3.1 轮 15） |
| R4 | 9（W13） | `validatePageConsistency(volume)` 的输入就是 `renumberPages(volume)` 的**输出**（`ComicInfoXml.kt:111` 调 `:93-97 previewOf`），两者同源 ⇒ ①`pages.size != order.size` 恒假、②校验侧补零位数与重编号侧恒等、③`startsWith("$expected.")` 恒真 ⇒ **`pageIssues` 恒空、`consistent` 恒真**，UI 的告警分支（`app/…/workflowb/PackPreviewPage.kt:204 if (!preview.consistent)`）**不可达** | 要让这两个分支可达，必须改 `validatePageConsistency` 的**输入语义**（例如改成校验"磁盘上真实存在的页"而非"按页序重编号的页"，或校验 `volume.pages` 与 `pageOrder.order` 是否一致）。这改变了"一致性"这个词在 D3 里的含义，且会牵动 UI 告警的触发条件 ⇒ **属产品决策**。本轮只做等价性证明与记录，**不改输入来源** | ✅ **已修复**（轮 16 / W20：用户 `m04079` 第④项「想一想怎么修」授权 —— 取的是登记里的**第②种修法**：校验 `volume.pages` 与 `pageOrder.order` 是否一致。第①种（改看「磁盘上真实存在的页」）要碰文件系统，与原型「不碰真实文件系统」的既定约束冲突（`performUndo` 恒 `applied=false` 即该约束的体现），故未取。修后：数量检查改比「卷内登记的页」与「页序里排的页」，长度不同报 `PageIssueCountMismatch`，长度相同但成员不同报**新增键** `PageIssueMissingFromOrder`（+ 双语资源 + `resId()` 映射）；判据是**集合/成员**而非位置 ⇒ 人工拖拽只改 `order` 不改 `images`，不会误报（有专门用例钉死置换不变性）。补零交叉校验**保留**——本轮同时**纠正**了本行原措辞里的一处不准确：③`startsWith("$expected.")`**并不恒真**，`expected` 的补零宽度用 `pages.size`、`renumberPages` 用 `order.size`，是同一规则的**两份独立拷贝**，只改坏一处即被捕获（W13 的变异「页码补零不随页数自适应」正是被它抓住的）；**真正恒假的只有数量检查**。21 组变异两遍全捕获；同时修正一条骑在坏检查上的既有断言（详见 §3.1 轮 16）。⚠️ 诚实范围声明：生产路径下 `ComicVolume` 构造点唯一且类型级默认值就是 `pageOrderOf(pages)`，故这两者**目前永不分叉**，新检查是**防御性**的（防未来新增的 `copy(pages=…)` 路径），它解决的是「修复前告警分支不可达」这一点。 |
| i18n | 15（W19） | 见 `m04079` 第⑤项：「键 → 译文」这条链**没有任何独立的一侧** —— `PixFoldTextTest` 的期望值经 `TestText.of(同一个资源)` 取得（**同源**，资源改坏时两边一起变）；`resId()` 是 `private` 穷尽 `when`，只保证「有资源」不保证「指对」。两处叠加的直接后果：W20 的 M15–M18（中英占位符删/对调）、M21（中文抄成英文）**全部存活** | 要补自动化就得**另立一侧**（按命名约定反查资源 + 同进程切语言对拍），而这需要新增测试文件、并决定「哪些键允许中英相同 / 允许撞句」——后者是**人工裁决**（写成白名单即等于给译文立规），不是能从代码里算出来的 | ✅ **已完成**（轮 17 / W21：用户 `m04079` 第⑤项「这属于 i18n 内容，顺带实现了」授权 —— 新增 `app/…/ui/text/TranslationCoverageTest.kt`（5 条契约，不经 `resId()`，按 `TextKey.FooBar → domain_foo_bar` 独立反查，同进程内中英对拍）；16 组变异**两遍**（第一遍 15 捕获 / 1 存活 M14 = 同源期望值盲区**第三次命中**，补「复数各档占位符同形」契约后第二遍 16 捕获 / 0 存活），3 文件还原逐字节一致；app +5 ⇒ **430**；**生产代码零改动**。既有 i18n 复查结论 = **无需调整**（资源面六类可疑项全 0；生产层无漏翻文案；唯一不一致是符号前缀「一处成键、一处写死」，仓库三处注释已裁决符号前缀不属于文案，如实记为观察项）；详见 §3.1 轮 17） |
| R5 | 18（W22） | **R2 的键在生产路径上不可达**（第⑥项真机核实时发现）：R2（轮 14）新增的 `TextKey.RenamePlanRename` 由 `RenamePlannedOp.describe()`（`domain/…/plan/OperationPlan.kt:87`）产出，而它的**唯一调用者** `OperationPlan.describe()`（`:137`）在 `app/src/main` **零引用**；app 侧唯一 `describe()` 调用点 `app/…/ui/plan/PlanComponents.kt:144` 的 `PlanRow` 形参类型是 **`VolumePackPlan`**（`PlanComponents.kt:107`，唯一调用点 `app/…/ui/workflowb/PackPreviewPage.kt:351`）⇒ 走 `PackagePlan.kt:82 fun VolumePackPlan.describe()`，**产不出 `RenamePlanRename`**。`app/…/ui/workflowa/RenamePlanPage.kt` 又完全不消费 `describe()`（`:274 text = "→ ${op.finalName}"` 是代码里的符号前缀，状态词走 `R.string.a_plan_status_rename`/`a_plan_status_skip`）⇒ **键与映射齐备、domain+app 测试全绿，但用户在真机上永远看不到**（真机实测证实：改名计划页只有 `→ 旅行照片_001.jpg`，无「重命名 原名 → 新名」逐项句子） | 修法二选一且语义不同：①**接通** —— 让 `RenamePlanPage` 改走 `describe()`（或把 `PlanRow` 泛化到 `OperationPlan`），改动 UI 布局与交互；②**删除** —— 承认该键无用途，删键 + 双语资源 + `resId()` 映射 + 相关测试。选哪种、以及「改名计划页要不要逐项句子」本身，都是**产品决策**（UI 呈现形态），按 §7.1「有歧义先请用户裁决」只记录不改 | ✅ **已裁决：保持现状**（轮 19，用户 `m02554` 选定第 ① 种）—— 台账措辞按选项①原文记作**「无生产出口，刻意」**：键、双语资源、`resId()` 映射、domain/app 测试**一律不动**，`RenamePlanPage` **不接通** `describe()`，`PlanRow` 也**不泛化**到 `OperationPlan`。⚠️ 这不是"修好了"而是"决定不修"：`RenamePlanRename` 在生产路径上**依然不可达**（本行左侧的事实链一字未变） |

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

> **轮 10 收工时的实测读数**（供早间直接比对，省一次跑闸门；闸门仍以你自己重跑为准）：
> `git rev-list --count dev..d3/p1` = **19**（轮 10 测试提交 `3185b15` + 本回填提交）；`git status --porcelain` 空；
> domain `25 files / 367 tests / 0F 0E 0S`（源码 `@Test` 亦为 **367**，无排除项）、
> app `42 files / 399 tests / 0F 0E`（源码 `@Test` **400**，差的 1 个是 `NegativeControlTest`，
> 被 `app/build.gradle.kts` 的 `unitTests.all { exclude("**/NegativeControlTest.class") }` 排除）；
> lint `0 errors, 0 warnings, 4 hints`（4 条均为 `AutoboxingStateCreation`，不计入 `warningsAsErrors`）；
> `git diff --name-only dev...d3/p1 -- pixfold-d1/app/` = **0 条**。

> **轮 11 收工时的实测读数**（同上，供早间直接比对）：
> `git rev-list --count dev..d3/p1` = 见命令输出（在轮 10 的 19 之上再 +2：W15 测试提交与本次回填提交）；
> `git status --porcelain` 空、`git stash list` 空；
> domain `31 files / 406 tests / 0F 0E 0S`（源码 `@Test` 亦为 **406**，**无排除项、两者一致**）、
> app `42 files / 399 tests / 0F 0E`（**未变**：本分支一行未改 `app/**`）；
> lint `0 errors, 0 warnings, 4 hints`（同上，全为 hint 级）；`assembleDebug` 产出
> `app/build/outputs/apk/debug/app-debug.apk`（33,405,421 字节）；
> `git diff --name-only dev...d3/p1 -- pixfold-d1/app/` = **0 条**；
> 本轮新增的 6 个测试文件全部在 `domain/src/test/kotlin/com/pixfold/d1/domain/` 下的
> `model/`（3 个）、`text/`（1 个）、`settings/`（1 个）、`workflowb/`（1 个）—— 其中 `model/`、`text/`、`workflowb/`
> 是本轮**新开的测试目录**。

> **轮 12 收工时的实测读数**（同上，供早间直接比对）：
> `git rev-list --count dev..d3/p1` = 见命令输出（在轮 11 的 21 之上再 +2：W16 测试提交与本次回填提交）；
> `git status --porcelain` 空、`git stash list` 空；
> domain `32 files / 425 tests / 0F 0E 0S`（源码 `@Test` 亦为 **425**，**无排除项、两者一致**）、
> app `42 files / 399 tests / 0F 0E`（**未变**：本分支一行未改 `app/**`）；
> lint `0 errors, 0 warnings, 4 hints`（⚠️ **旧产物**，mtime 17:44 —— 本次 `:app:lintDebug` 执行了，
> 但 `lintReportDebug`/`lintAnalyzeDebug` 为 `UP-TO-DATE`，报告未重写）；`assembleDebug` 产出
> `app/build/outputs/apk/debug/app-debug.apk`（33,405,421 字节）；
> `git diff --name-only dev...d3/p1 -- pixfold-d1/app/` = **0 条**；
> 本轮新增的 1 个测试文件在 `text/` 目录下（`TextKeyMappingTest.kt`，13 用例），
> 其余 5 个既有文件各追加 1–2 条用例 ⇒ domain 406 → **425（+19）**。
> **本轮是 12 轮上限内的最后一轮**，之后不再有自动续跑。

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
  > 轮 9（W13）+7 ⇒ **357**（见 §3.1 轮 9）。
  > 轮 10（W14）+10 ⇒ **367**（见 §3.1 轮 10）。
  > 轮 11（W15）+39 ⇒ **406**（见 §3.1 轮 11；6 个新建文件共 37 条 + 2 个既有文件各 +1）。
  > 轮 12（W16）+19 ⇒ **425**（见 §3.1 轮 12；1 个新建 `text/TextKeyMappingTest.kt` 共 13 条
  > + 5 个既有文件各追加 1–2 条：`ComicMetadataTest` 44 / `NamingEvalTest` 34 / `CbzWriterTest` 21 /
  > `OperationPlanTest` 25 / `RenameLibraryPlanTest` 13）。
  > 轮 13（W17 / R1）+3 ⇒ **428**（见 §3.1 轮 13；`ExecutionReportTest` 新增 3 条不变量用例，
  > 并把 `rename undo maps to restoring the original name` 从"只断言 `from`"补成同时钉死 `to`）。
  > 轮 14（W18 / R2）domain +1 ⇒ **429**、app **+2** ⇒ **402**（见 §3.1 轮 14；domain 侧
  > 一条用例翻正向 + 新增一条回落方向用例；app 侧 `PixFoldTextTest` 新增 3 条，其中
  > 两条是"句子可观测形状"判据 —— 一条钉英文槽位、一条钉中文槽位）。
  > ⚠️ 这也是本分支**首次**让 `app` 测试数从 399 变动：此前 13 轮 `app` 恒 399。
  > `app` 始终 **399** 未变（本分支一行未改 `app/**`）。
  > 轮 15（W19 / R3 修复）domain +2 ⇒ **431**、app **+18** ⇒ **420**（见 §3.1 轮 15；
  > domain 侧 `DangerActionTest` 17（+1，含"直接构造生产造不出的 Overwrite+跳过 计划"）
  > 与 `UndoAlignmentTest` 18（+1）；app 侧新建 `UndoScopeDialogTest` **14** +
  > `UndoScopeDialogStyleTest` **2** + `PlanWiringTest` 17 → **19**）。
  > ⚠️ 这是本分支**首次让 `app` 测试数出现两位数增长**，也是**首次改 UI 布局**
  > （`ExecutionReportPage.kt` 新增 `UndoScopeDialog` 组件 + 底栏按钮分支）。
  > 计数口径：`domain` 源码 `@Test` **431** = XML **431**；`app` 源码 `@Test` **421**、
  > XML **420**，差 1 恒为 `NegativeControlTest`（`unitTests.all { exclude(...) }` 排除）。
- 产物：`app/build/outputs/apk/debug/app-debug.apk`（33,385,168 字节，mtime 17:43）⇒ `assembleDebug` 真实产出。
  > 轮 15 读数（`--rerun-tasks` 全量重建）：**33,420,392 字节**，mtime **2026-10-04 23:33:31**；
  > `find app/src/main domain/src/main -newer <apk>` 为空 ⇒ APK 比所有源文件新（读数新鲜）；
  > `resources.arsc` 内含全部 6 条新串（英/中各 3 条含 `<plurals>` 的 `one`/`other` 变格）
  > 与 `%1$d file` 占位符。
> 轮 16 读数（`--rerun-tasks` 全量重建）：domain **32 files / 434 tests / 0F 0E 0S**、
> app **44 files / 425 tests / 0F 0E 0S**、lint **0 errors, 0 warnings, 4 hints**；
> `RC=0`、`BUILD SUCCESSFUL in 44s`、`62 actionable tasks: 62 executed`；先 `rm -rf` 了两个
> `test-results` 目录以保证不是陈旧 XML。
> 计数口径：`domain` 源码 `@Test` **434** = XML **434**；`app` 源码 `@Test` **426**、
> XML **425**，差 1 恒为 `NegativeControlTest`。
> 轮 16 增量：domain **431 → 434**（+3：分叉卷报数量不符、成员不同报缺失、以及既有
> `page preview follows the page order, not the raw page list` 的断言翻正 —— 详见 §3.1 轮 16）；
> app **420 → 425**（+5：`PackPreviewPageTest` +2、`PixFoldTextTest` +3）。
> 产物：**33,420,916 字节**，mtime **2026-10-05 00:22**；
> `find app/src/main domain/src/main -newer <apk>` 为空 ⇒ APK 比所有源文件新；
> `resources.arsc` 复核新串**三种形态**（键名 / 英文 / 中文）逐一 **YES**，
> 另加 `domain_page_issue_count_mismatch` 中英各一条。


> 轮 18 读数（`--rerun-tasks` 全量重建，`artifacts/gates_w22_final3.log`）：domain **32 files / 434 tests / 0F 0E 0S**、
> app **50 files / 456 tests / 0F 0E 0S**；lint **0 errors, 0 warnings, 4 hints**（4 条为既有
> `AutoboxingStateCreation`，见 `app/build/reports/lint-results-debug.txt` 末行）。
> `RC=0`、`BUILD SUCCESSFUL in 55s`、`62 actionable tasks: 62 executed`；先 `rm -rf` 了两个
> `test-results` 目录以保证不是陈旧 XML。
> 计数口径（**本轮已修正**）：app 源码 `@Test` **456**（**先排除 `NegativeControlTest`**）= XML **456**、
> 文件数 50 = 50 ⇒ 轮 16 记的「源码 = XML + 1」**不再适用**；逐项对账
> `431(HEAD 含 NC) − 1(NC) + 23(5 个新文件) + 1(PlanUiTest) + 2(PackPreviewPageTest) = 456`，**无残差**。
> 类名对拍：源码 52 类 / XML 50 类，「有源码无 XML」恰为 `NegativeControlTest` + `TestText`（助手）。
> 轮 18 增量：domain **434 → 434**（+0，第⑥项不碰领域判据）；app **430 → 456**（+26：
> `EmojiFreeSourceTest` +6、`WarningIconRenderTest` +3、`ForbiddenIconRenderTest` +4、
> `WarnIconMappingTest` +6、`WarnLineTest` +4、`PlanUiTest` +1、`PackPreviewPageTest` +2）。
> 产物：**33,437,589 字节**，mtime **2026-10-05 11:53:09**。
> 真机：PJX110 / Android 16 / 1264×2780 px = 361dp×794dp；新 APK 已装（`lastUpdateTime=2026-10-05 11:25:12`），
> **10 个警示面 + i18n 中英对照全部通过**（逐面取证见 §3.1 轮 18）。
> 轮 19 读数（真机取证轮：探针构建 → 取证 → 还原 → `--rerun-tasks` 全量重建，`artifacts/gates_w25_restore.log`）：
> domain **32 files / 434 tests / 0F 0E 0S**、app **50 files / 456 tests / 0F 0E 0S**（与轮 18 **逐项一致** ——
> 本轮**零源码改动**，探针只动 mock 数据且已逐字节还原）；lint `0 errors, 0 warnings, 4 hints`
> （4 hints 全是既有 `AutoboxingStateCreation`，见 `app/build/reports/lint-results-debug.txt`）；
> `RC=0`、`BUILD SUCCESSFUL in 1m 12s`、`62 actionable tasks: 62 executed`
> （先删 `app/build/test-results/testDebugUnitTest` 与 `domain/build/test-results/test` 保证读数权威）。
> 产物：**33,437,589 字节**、mtime **2026-10-05 17:19:26**、
> sha256 **`1a2164ea74a298575e3937fa06b38bd6f4f37c2fb2d3adeabec8bbf7151983d1`**
> —— 与干净基线 `artifacts/w24bak/app-debug.clean.apk` **逐字节相同**；新鲜度：所有源文件都不比该日志新
> （`find app/src/main app/src/test domain/src/main domain/src/test -type f -newer artifacts/gates_w25_restore.log` = **0**）。
> 真机：PJX110 / Android 16；本轮新增 **R4 页码自检告警的真机取证**（中文 + 英文各一次 + 干净包反向对照），
> 设备已回到与基线一致的安装，逐面取证见 §3.1 轮 19。
> 轮 20 读数（**文档订正轮**：只改 `HANGOFF.md`，未改任何 Kotlin；`--rerun-tasks` 全量、
> 先删两个 `test-results`；日志 `artifacts/gates_w24_hangoff.log`，mtime 2026-10-05 18:2x）：
> `RC=0` / `BUILD SUCCESSFUL in 42s` / **`62 actionable tasks: 62 executed`**（无 `up-to-date`）；
> `domain:test` = **32 files / 434 tests / 0 failures / 0 errors / 0 skipped**；
> `app:testDebugUnitTest` = **50 files / 456 tests / 0F / 0E / 0S**；
> `:app:lintDebug` = **`0 errors, 0 warnings, 4 hints`**（4 条均为既有 `AutoboxingStateCreation`）；
> `:app:assembleDebug` ⇒ APK **33,437,589 字节**、mtime **2026-10-05 18:21** ——
> 与干净基线 `artifacts/w24bak/app-debug.clean.apk` **逐字节相同**；
> 新鲜度：`find app/src/main app/src/test domain/src/main domain/src/test -type f -newer
> artifacts/gates_w24_hangoff.log` = **0**。
> ⇒ 文档订正对构建与测试**零影响**；读数与轮 19 完全一致（domain 434 / app 456 / lint 0-0-4）。
> 轮 20 补记（⚠️ **时点更正**：这一次复跑发生在首次订正**之前**、非「订正之后」—— 见下方第二次复跑）：
> `--rerun-tasks` 全量、先删两个 `test-results`；日志 `artifacts/gates_w24b_fix.log`，mtime 2026-10-05 18:3x）：
> `RC=0` / `BUILD SUCCESSFUL in 36s` / **`62 actionable tasks: 62 executed`**（无 `up-to-date`）；
> `domain:test` = **32 files / 434 tests / 0F / 0E / 0S**；
> `app:testDebugUnitTest` = **50 files / 456 tests / 0F / 0E / 0S**；
> `:app:lintDebug` = **`0 errors, 0 warnings, 4 hints`**；
> `:app:assembleDebug` ⇒ APK **33,437,589 字节**、mtime **2026-10-05 18:28:51** ——
> 与干净基线 `artifacts/w24bak/app-debug.clean.apk` **逐字节相同**；
> 新鲜度：`find app/src domain/src -type f -newer artifacts/gates_w24b_fix.log` = **0**。
> ⇒ 与轮 19/20 读数**三次完全一致**（domain 434 / app 456 / lint 0-0-4）—— 文档改动对构建与测试**零影响**。
> 轮 20 补记②（**两次 `HANGOFF.md` 订正都落盘之后**的复跑：只改 `HANGOFF.md`、未改任何 Kotlin；
> `--rerun-tasks` 全量、先删两个 `test-results`；日志 `artifacts/gates_w24c_final.log`，mtime 2026-10-05 18:44:59）：
> `RC=0` / `BUILD SUCCESSFUL in 43s` / **`62 actionable tasks: 62 executed`**（无 `up-to-date`）；
> `domain:test` = **32 files / 434 tests / 0F / 0E / 0S**；
> `app:testDebugUnitTest` = **50 files / 456 tests / 0F / 0E / 0S**；
> `:app:lintDebug` = **`0 errors, 0 warnings, 4 hints`**；
> `:app:assembleDebug` ⇒ APK **33,437,589 字节**、mtime **2026-10-05 18:44:28**、
> sha256 **`1a2164ea74a298575e3937fa06b38bd6f4f37c2fb2d3adeabec8bbf7151983d1`** ——
> 与干净基线 `artifacts/w24bak/app-debug.clean.apk` **逐字节相同**；
> 新鲜度：`find app/src domain/src -type f -newer artifacts/gates_w24c_final.log` = **0**。
> ⇒ 四次读数（轮 19 / 轮 20 / 补记① / **补记②**）**完全一致**（domain 434 / app 456 / lint 0-0-4）——
> 文档改动对构建与测试**零影响**；补记① 的时点误标在此更正。
> 轮 20 补记② 落地之后（**本段是终笔前最后一次闸门复跑**）的读数
> （`artifacts/gates_w24d_final.log`，mtime **2026-10-05 18:49:42**、`--rerun-tasks` 全量、先删两个 `test-results`）：
> `RC=0` / `BUILD SUCCESSFUL in 41s` / **`62 actionable tasks: 62 executed`**（无 `up-to-date`）；
> `domain:test` = **32 files / 434 tests / 0F / 0E / 0S**；
> `app:testDebugUnitTest` = **50 files / 456 tests / 0F / 0E / 0S**；
> `:app:lintDebug` = **`0 errors, 0 warnings, 4 hints`**；
> `:app:assembleDebug` ⇒ APK **33,437,589 字节**、mtime **2026-10-05 18:49:11**、
> sha256 **`1a2164ea74a298575e3937fa06b38bd6f4f37c2fb2d3adeabec8bbf7151983d1`** ——
> 与干净基线 `artifacts/w24bak/app-debug.clean.apk` **逐字节相同**；
> 新鲜度：`find app/src domain/src -type f -newer artifacts/gates_w24d_final.log` = **0**。
> ⇒ **五次读数**（轮 19 / 轮 20 / 补记① / 补记② / **本次**）**完全一致**
> （domain 434 / app 456 / lint 0-0-4）—— 本轮的文档改动对构建与测试**零影响**。


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
> 余量轮的增量见各轮小节：轮 7（W11）**+3 files / +93 / −0**；轮 8（W12）**+2 files / +46 / −0**；
> 轮 9（W13）**+4 files / +102 / −0**；轮 10（W14）**+4 files / +138 / −0**（也全是测试文件）；
> 轮 11（W15）**+8 files / +445 / −0**（6 个新建测试文件 + 2 个既有测试文件各 +1 用例，同样全是测试文件）。
> 轮 12（W16）**+6 files / +318 / −0**（1 个新建 `text/TextKeyMappingTest.kt` 219 行 + 5 个既有测试文件
> 共 +99 行，**同样全是测试文件**；生产代码零改动，`git diff --quiet HEAD -- pixfold-d1/domain/src/main` 通过）。
> 轮 13（W17 / R1 修复）**+5 files / +106 / −35** —— 这是本分支自 W10 终验以来**第一次改生产代码**
> （`domain/plan/Execution.kt` +23 / `domain/plan/OperationPlan.kt` +3），其余 3 个是测试文件
> （`plan/UndoAlignmentTest.kt` 重写两条用例、`plan/ExecutionReportTest.kt` +3 用例并补 `to` 断言、
> `plan/PackingPipelineTest.kt` 仅改注释）。R1 是用户裁决要修的**真缺陷**，不是余量加固。
> 轮 14（W18 / R2 补齐）**+7 files / +183 / −14** —— 这是本分支**首次改动 `pixfold-d1/app/**`**
> （用户 `m04081`「改吧」授权新增 `TextKey`）：生产 5 个文件里 2 个是双语资源
> （`values/domain_strings.xml` 与 `values-zh/domain_strings.xml` 各 +4）、1 个是映射
> （`ui/text/PixFoldText.kt` +3）、2 个在 domain（`text/TextKey.kt` +21 新增 1 键、
> `plan/OperationPlan.kt` ±22 改 `describe()` 两分支）；测试 2 个（`plan/OperationPlanTest.kt`
> 一条翻正向 + 一条新增、`app/…/ui/text/PixFoldTextTest.kt` +111 三条新用例）。
> ⚠️ `app/**` 的"0 条"结论**从本轮起不再成立**，是**授权范围内**的改动（资源 + 映射 + 测试），
> UI 布局文件（如 `ui/workflowa/RenamePlanPage.kt`）仍**一行未动**。
> 当前实时读数以 `git diff --stat dev...d3/p1` 为准；改动面 = `domain/**` + `app/src/main/res/values*/`
> （2 个文件）+ `app/…/ui/text/PixFoldText.kt` + `app/src/test/**`（1 个文件）+ `HANGOFF.md` + 本台账。
> 轮 15（W19 / R3 修复）**+16 files / +483 / −58** —— 生产 7 个文件
> （`domain/plan/Execution.kt` +112/−9、`domain/plan/PackagePlan.kt` +17、`domain/plan/OperationPlan.kt` +9、
> `domain/plan/DangerAction.kt` ±1、`app/…/ui/plan/ExecutionReportPage.kt` +134/−2 —— **首次改 UI 布局**、
> `app/…/ui/workflowa/WorkflowASteps.kt` ±6、`app/…/ui/workflowb/WorkflowBSteps.kt` ±6，
> 以及 2 个双语资源 `values/strings_plan.xml` +13 / `values-zh/strings_plan.xml` +12）；
> 测试 7 个（2 个**新建** `app/…/ui/plan/UndoScopeDialogTest.kt` 14 用例与
> `UndoScopeDialogStyleTest.kt` 2 用例，另 5 个既有文件共 +187/−42）。
> ⚠️ 与轮 14 不同：轮 14 只动"资源 + 映射"，**本轮真的动了 UI 布局与交互**（撤销按钮改为
> "有覆盖项先弹框"、新增 `UndoScopeDialog` 组件、新增 4 条双语字符串 + 1 个 `<plurals>`），
> 但**未改构建脚本、未新增依赖、未改 `HANGOFF.md`**。
> ⚠️ `app/**` 的"0 条"结论**从轮 14 起不再成立**，本轮进一步扩大；两条都属**用户授权范围**
> （`m04081` 授权 R2、`m04079` 第③项授权 R3）。
> 轮 16（W20 / R4 修复）**+9 files / +223 / −8** —— 生产 5 个文件
> （`domain/…/comic/ComicInfoXml.kt` +30/−6 重写 `validatePageConsistency` 与 KDoc、
> `domain/…/text/TextKey.kt` +1 新键、`app/…/ui/text/PixFoldText.kt` +1 映射、
> `app/src/main/res/values/domain_strings.xml` +1、`app/src/main/res/values-zh/domain_strings.xml` +1）；
> 测试 4 个（`app/src/test/…/TestText.kt` +11 新增 `raw()` 助手 —— 见下、
> `app/src/test/…/ui/text/PixFoldTextTest.kt` +65 三条新用例、
> `app/src/test/…/ui/workflowb/PackPreviewPageTest.kt` +37 两条新用例 + 助手、
> `domain/src/test/…/comic/ComicMetadataTest.kt` +76/−2 两条新用例 + 一条既有断言翻正）。
> ⚠️ **`TestText.raw()` 的存在理由**：`TestText.of(id)` 走 `getString(id)`，会把 `%1$s`
> 当格式串**当场格式化**并抛 `MissingFormatArgumentException`；要拿"模板本身"必须用
> `context.resources.getText(id).toString()`（只解码不格式化）。这是本轮踩到并修掉的坑。
> ⚠️ 与轮 15 不同：本轮**未改 UI 布局**，只改领域判据 + 新增一条文案 + 映射与资源。
> 轮 17（W21 / i18n 自动化）**+1 file / +313 / −0** —— 改动面**只有一个新建测试文件**
> （`app/src/test/kotlin/com/pixfold/d1/ui/text/TranslationCoverageTest.kt`，313 行 5 用例）。
> `git diff --stat HEAD -- domain/src/main app/src/main` ⇒ **空** ⇒ **生产代码零改动**，
> 这是本分支自轮 13 以来**改动面最小的一轮**；既未新增依赖、未改构建脚本，
> 也未改 `HANGOFF.md`（本轮的授权面是「补自动化 + 复查既有 i18n」，不涉及阶段快照）。
> 只读审计产物（`artifacts/i18n_audit.py` / `i18n_table.tsv` / `i18n_render.tsv`）与
> 变异脚本 `artifacts/mutate_w21.py` 均在 `pixfold-d1/.gitignore:9` 忽略的 `artifacts/` 下，**未入库**。

> 轮 18（W22 / 第⑥项 图标）**共 30 个文件：22 个已跟踪改动（+176 / −56）+ 8 个新建**。
> 其中**生产侧 21 个**：`ui/text/WarnIcon.kt`（**新建** 106 行）、
> `res/drawable/ic_warning_triangle.xml`（**新建** 25 行）、`res/drawable/ic_forbidden.xml`（**新建** 24 行）、
> `ui/plan/PlanComponents.kt`、`ui/plan/ExecutionReportPage.kt`、`ui/text/PixFoldText.kt`（`resId()` 放宽为 `internal`）、
> `ui/workflowa/NamingPage.kt`、`ui/workflowa/ProposalTable.kt`（注释）、`ui/workflowa/RenamePlanPage.kt`、
> `ui/workflowb/PackPreviewPage.kt`、8 个双语资源（`values/` 与 `values-zh/` 各 4 个：
> `domain_strings.xml`、`strings_plan.xml`、`strings_workflowa.xml`、`strings_workflowb.xml`）、
> 以及 3 个 domain 文件的**纯注释**修正（`comic/ComicInfoXml.kt`、`text/TextKey.kt`、`plan/DangerAction.kt`）。
> 测试侧 **9 个**：新建 `ui/text/…/EmojiFreeSourceTest.kt`（332 行）+
> `ui/text/{WarningIconRenderTest,ForbiddenIconRenderTest,WarnIconMappingTest,WarnLineTest}.kt`，
> 既有 `ui/plan/PlanUiTest.kt` +1 用例、`ui/workflowb/PackPreviewPageTest.kt` +2 用例，
> 以及 2 个 domain 测试文件的**纯注释**修正（`plan/DangerActionTest.kt`、`comic/ComicMetadataTest.kt`）。
> ⇒ **21 + 9 = 30**。
> `git diff --stat HEAD -- pixfold-d1/domain/src/main pixfold-d1/app/src/main` = **18 files / +106 / −53**
> （已跟踪部分的生产改动面）。
> ⚠️ **资源层的 13 键去前缀不是"改文案"而是"删前缀"**：13 个键的**可见文案一字未改**
> （如 `⚠️ 已有同名 CBZ 将被覆盖：%1$s` → `已有同名 CBZ 将被覆盖：%1$s`），
> 另有 **2 个退化键被整体删除**（去前缀后只剩裸 `%1$s`，`plan_skip_reason_prefix` / `a_plan_skip_reason`，
> 英中各一条 ⇒ 共 4 个元素）。**占位符数量与下标一位未动** ⇒ `TranslationCoverageTest`
> 的契约①②③④⑤全部照常成立（该测试只枚举 `TextKey.entries`，13 键里只有 5 个在枚举范围内，
> 且这 5 个的前缀删除对"渲染逐字相等"是**同步**的 ⇒ 不触发白名单漂移）。
> ⚠️ **边界文件逐条 `git diff --quiet HEAD` 全部 unchanged**：`app/build.gradle.kts`、
> `domain/build.gradle.kts`、`build.gradle.kts`、`settings.gradle.kts`、`gradle/libs.versions.toml`、
> `.mise.toml`、`AGENTS.md`、`README.md`、`HANGOFF.md` ⇒ **未改构建脚本、未新增依赖、未动阶段快照**。
> ⚠️ 变异脚本与备份（`artifacts/mutate_w22.py`、`mutate_w22b.py`、`w22bak/`、`w22bak2/`、`w22cbak/`、
> `w22mutbak/`、`w22mutbak2/`、`grid_base.txt`、`grid_mut.txt`、`pngprobe.py`、`adbx.sh` 等）
> 均在 `pixfold-d1/.gitignore:9` 忽略的 `artifacts/` 下，**未入库**。
> 轮 19 改动面：**源码零改动**（本轮收工时 `git status --porcelain` 为空）。探针是一次**临时注入并完整还原**：
> `domain/src/main/kotlin/com/pixfold/d1/domain/mock/MockLibraryBData.kt` 注入后 `1 file changed, 4 insertions(+), 1 deletion(-)`，
> 还原后 sha256 = `0e558ce028918759d04f0abe0d9201bb49f4e19547b539a08c203b92735cc09a`
> （= 注入前备份 `artifacts/w24bak/MockLibraryBData.kt`，双方一致）；
> `grep -rn 'R4_PROBE' domain/src app/src | wc -l` = **0**。
> ⚠️ **一次 APK 字节差被查清并关闭**：中途一次重建得 **33,487,738 字节**（比基线 **+50,149**），当时按回归疑点查。
> 逐条目比对结果：155 个 zip 条目名集合**完全相同**（无增无减）、每条目 `(file_size, compress_size, CRC)`
> **逐条全等**、`sum(compress)`/`sum(uncompress)` 与中央目录长度估算也相同 ⇒ 差异纯在**容器层**
> （条目**顺序**不同，首个顺序差异 @21：clean = `classes8.dex` / new = `classes9.dex`；签名块偏移不同）
> ⇒ **内容等价、非回归**；权威复跑（`--rerun-tasks` 全量）后与基线**逐字节相同** ⇒ 该疑点关闭。
> ⚠️ 本轮**未改**任何生产代码、**未改**构建脚本、**未新增**依赖；边界文件（`app/build.gradle.kts`、
> `domain/build.gradle.kts`、`build.gradle.kts`、`settings.gradle.kts`、`gradle/libs.versions.toml`、`.mise.toml`、
> `AGENTS.md`、`README.md`、`HANGOFF.md`）逐条 `git diff --quiet HEAD` **全部 unchanged**。
> ⚠️ 真机取证的产物（`artifacts/r4a.xml`、`r4b.xml`、`r4en1.xml`、`r4c_pack.xml`、`r4c_home.xml`、`r4restore.xml`、
> `r4_probe_build.log`、`w24_r4_warning.png`、`w24_r4_en.png`、`w24bak/`）均在
> `pixfold-d1/.gitignore:9` 忽略的 `artifacts/` 下，**未入库**。

> 轮 20 改动面：**仅 `HANGOFF.md`**（纯追加 **+131 / −0**；两个 hunk 都在 §9.1 D3 小节内 ——
> 标题后 **19 行**、小节末尾 **112 行**）⇒ **未改任何 Kotlin**、**未改构建脚本**、**未新增依赖**。
> ⚠️ 与前几轮不同：**本轮 `HANGOFF.md` 不再是"逐条 unchanged"的边界文件**（这是有意的，
> 且正是 `AGENTS.md:49` 变更路由要求的那一步 —— 回写唯一权威文档）；但
> **阶段快照三处（§9 顶 `> 进度(` 行、门槛表 `| D3 |` 行、`| **打包** | ⬜ **未交付** |` 行）
> 与 `AGENTS.md` / `README.md` 仍逐条 `git diff --quiet HEAD` unchanged**。
> 文件级：3295 → **3426 行**、306,426 → **319,671 字节**、
> sha256 `343720ff102b…` → **`c8fd0a404221…`**（CRLF 0 / bare CR 0 维持不变）。
> 轮 20 补记（**订正两处时间性失真之后**）：`HANGOFF.md` 再由 `3426` → **`3437` 行**、
> 319,671 → **320,594 字节**、sha256 `c8fd0a404221…` → **`07e640a3df3b…`**；
> 随后把两条注改为**时间无关表述**（见 §3.1 轮 20 补记）⇒ **`3438` 行**、**320,687 字节**、
> sha256 **`30ed30d25117…`**（CRLF 0 / bare CR 0 维持不变）。
> 两次改动均为**纯追加**（`git diff --numstat` = `11 0`；amend 后 `git show --numstat` = `12 0`），
> **零删除行**；**除 `HANGOFF.md` 外本轮未动任何文件**，`AGENTS.md` / `README.md`
> 与阶段快照三处**仍逐条 unchanged**。


### 6.4 提交拓扑（`git log --oneline dev..d3/p1`）

> ⚠️ **该命令自 2026-10-05 合回 `dev` 起已失效**：`git merge --ff-only d3/p1` 之后 `dev` 又前进了
> 若干 docs 提交，故 `dev..d3/p1` **输出 0 行**（`d3/p1` 反被 `dev` 包含）。
> 本表因此**不再能由该命令重放**，而是**手工维护的权威提交清单**；
> 要看"合并后又落了什么"，用 `git log --oneline d3/p1..dev`。

> **不写死条数**（终验当时为 9；W10 提交与回填提交各 +1）—— 以命令输出为准。
> ⚠️ 轮 14 起 `pixfold-d1/app/**` 有改动，故 §6.3 里"`app/**` 始终 0 条"的旧结论**已作废**。
> 链条如下（最新在上）。**轮 17（W21）起本表已逐条补齐哈希** —— 轮 7/8/10/13 的占位行与
> 缺哈希行均已回填（`257e0e1` / `288309b` / `3185b15` / `b190bb6` 等）。
> ⚠️ **订正（轮 20 终笔）**：此处原写「历史占位**已清零**」—— 实测当时表中仍有
> `（轮 19 回填 = 本次提交）` 与 `（轮 20 回填 = 本次提交）` 两行待填（自指的哈希只能由
> 后续一笔补上，故一直悬着），本轮一并填实（`09fc863` / `4577aed`）；本表仍按
> 「先留占位、下一笔回填」的惯例维护：

| 提交 | 类型 | 内容 |
| `1767739` | docs | 轮 20 终笔：更正 §6.2 补记① 对闸门复跑**时点**的误标（`gates_w24b_fix.log` mtime 18:29:19 早于首次订正提交 `3ff135e` 18:40:25）并补记两次订正之后的权威复跑 `gates_w24d_final.log`（**1 file / +13 / −1**，唯一删除行即旧误标行） |
| `ad4cca0` | docs | 轮 20 终笔台账回写：§3.1「终笔」段 + §6.2 终态闸门第五次读数 + §6.4 `1767739` 行（1 file / +33 / −0） |
| `3ff135e` | docs | 轮 20 补记：订正 `HANGOFF.md` 追记里两处"写下即过期"的分支读数（`dev == d3/p1` / `[ahead 42]`）；⚠️ 第一版注写死了提交数又自我过期 ⇒ 改为时间无关表述（**纯追加 +12 / −0**；amend 自 `5f5b233`，改前已核实全仓无引用且未推送） |
| `63a33b9` | docs | 轮 20 补记：追记后收尾 —— 时间性失真订正（`HANGOFF.md:2755`/`:2836`）+ 闸门复跑读数 + §6.4 命令失效注（1 file / +49 / −0） |
| `c5f6610` | docs | 轮 20 / W24 台账回写：§3 新增 W24 行、§3.1 新增轮 20 小节（触发/决策/订正 6 处/追记/写盘两遍制/纯新增验证/阶段快照未动/三次事实复核）、§6.2 追加闸门读数、§6.3 追加改动面、§6.4 新增 `768bec7` 行、§6.5 第 3 项补注（1 file / +88 / −0） |
| `4577aed` | docs | 台账回填轮 20（W24：`HANGOFF.md` §9.1 订正 + 追记）提交号 |
| `768bec7` | docs | 轮 20 / W24：`HANGOFF.md` §9.1「D3 打包核心轮」订正 6 处失真 + 追记 R1–R5/i18n/图标/合回 `dev`（**纯追加 +131 / −0**、两个 hunk；阶段快照三处与 `AGENTS.md`/`README.md` 未动） |
| `b154b52` | docs | 轮 19 / W23 台账回写：§3 新增 W23 行、§3.1 新增轮 19 小节（R4 真机取证逐面读数 + R5 裁决）、§4 R5 状态列改 ✅、§6.2 追加轮 19 读数、§6.3 追加改动面与 APK 字节差定性、§6.5 收尾（1 file / +106 / −5） |
| `09fc863` | docs | 台账回填轮 19（W23：R4 真机取证 + R5 裁决）提交号与读数 |
| `02503be` | docs | 轮 18 补记：修正报告页 KDoc 里已证伪的「标题带 ⚠️」措辞（第 6 处注释腐化；纯注释 1 file / +1 / −1） |
| `（轮 18 回填）` | docs | 台账回填轮 18（W22 / 第⑥项 图标）提交号与读数（`6c02b26`） |
| `e491acd` | docs | 轮 18 / W22 台账回写：§3 W22 行、§3.1 轮 18 小节、§4 R5 登记、§6.2 读数、§6.3 增量、§6.5 收尾（1 file / +208 / −2） |
| `fb4a656` | feat | W22 / 第⑥项：警告 emoji ⇒ 矢量图标（+2 drawable、`WarnIcon.kt` 映射表与 `WarnLine`、13 键去前缀 + 删 2 退化键、7 处调用点接线、21 组变异两遍、真机 10 面 + i18n 对照；app 430 → 456） |
| `（轮 17 回填）` | docs | 台账回填轮 17（W21 / i18n）提交号与读数（`7b56499`） |
| `339d9fa` | test | W21 / i18n：键 → 译文结构契约 `TranslationCoverageTest.kt`（5 契约 / 313 行；16 组变异两遍全捕获；app 425 → 430；生产零改动） |
| `（轮 16 回填）` | docs | 台账回填轮 16（W20 / R4）提交号与读数（`ee7fb12`） |
| `df02823` | fix | W20 / R4：页码自检从「与自身比」改为「页表 ↔ 页序」（+1 `TextKey`、双语资源、映射；判据为集合/成员以保置换不变；domain 434 / app 425） |
| `（轮 15 回填）` | docs | 台账回填轮 15（W19 / R3）提交号与读数（`fc1084f`） |
| `b83098d` | fix | W19 / R3：覆盖的撤销先警告并让用户选范围（+2 `UndoScope` 档位、`DeleteOverwritten`、双语资源与弹框；`onUndo` ⇒ `onUndoScope`；domain 431 / app 420） |
| `（轮 14 回填）` | docs | 台账回填轮 14（W18 / R2）提交号与读数（`dc2e28e`） |
| `759ef68` | fix | W18 / R2：改名计划行给出「原名 → 新名」（+1 `TextKey`、双语资源、映射；domain 429 / app 402） |
| `（轮 13 回填）` | docs | 台账回填轮 13（W17 / R1）提交号与读数（`b190bb6`） |
| `cc4c30c` | fix | W17 / R1：撤销记录恢复原文件名（+3 用例 ⇒ domain 428） |
| `47f42ff` | docs | 台账回填轮 12（W16）提交号与读数（收尾轮） |
| `b0f0e88` | test | W16：五次广扫荡 66 组 → 24 存活 → 23 真缺口全捕获（+19 用例 ⇒ domain 425） |
| `ffd9433` | docs | 台账回填轮 11（W15）提交号与读数 |
| `8b3e988` | test | W15：四次广扫荡 64 组 → 32 存活 → 31 真缺口全捕获（+39 用例 ⇒ domain 406） |
| `884c343` | docs | 台账回填轮 10：记录 `3185b15` |
| `3185b15` | test | W14：三次广扫荡 58 组 → 15 存活 → 13 真缺口全捕获（+10 用例 ⇒ domain 367） |
| `a1768c7` | docs | 台账回填轮 9：记录 `52dd0a7` + 登记 R4 |
| `52dd0a7` | test | W13：二轮广扫荡 39 组 → 12 存活 → 8 真缺口全捕获（+7 用例 ⇒ domain 357） |
| `（轮 8 回填）` | docs | 台账回填轮 8：记录 `3c34c75`（回填提交 `257e0e1`） |
| `3c34c75` | test | W12：广扫荡 23 组 → 2 存活 → 补断言（+2 用例 ⇒ domain 350） |
| `（轮 7 回填）` | docs | 台账回填轮 7：记录 `2fe36ce` + R3 登记（回填提交 `288309b`） |
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

- **完成**：W0–**W21** 全部 ✅（§3 状态表）；D3 退出条件 1 所要求的**打包领域核心**
  **轮 13（W17）另加**：R1 真缺陷修复（撤销 `from == to` 空操作 ⇒ `ExecEntry.originalPath`），6 组变异全捕获，
  +3 用例（425 → **428**）。
  **轮 14（W18）另加**：R2 补齐（新增 `TextKey.RenamePlanRename` + 双语资源 + `resId()` 映射，
  `RenamePlannedOp.describe()` 未跳过项给出「原名 → 新名」、跳过项无原因回落 `OpStatusSkip`），
  10 组变异全捕获（含 4 组**资源内容**变异），domain +1 ⇒ **429**、app +2 ⇒ **402**。
  ⚠️ 本轮是本分支**首次改动 `pixfold-d1/app/**`**（用户 `m04081`「改吧」授权），
  但只动资源 / 映射 / 测试，UI 布局文件一行未动。
  （`PackagePlan`/`OperationPlan` 语义、`ExecutionReport`、`UndoRecord`）已落地并有 **425** 项单测覆盖。
  **余量轮 7（W11）另加**：条目名撞名守卫（真漏洞，已修）+ 2 用例；
  **余量轮 8（W12）另加**：广扫荡 23 组变异 → 2 存活 → 补断言后全捕获 ⇒ 又 +2 用例（348 → **350**）；
  **余量轮 9（W13）另加**：二轮广扫荡 39 组变异（换 5 个文件）→ 12 存活 → 判定出 8 真缺口（补断言后全捕获）
  + 4 已证等价 ⇒ 又 +7 用例（350 → **357**）；
  **余量轮 10（W14）另加**：三轮广扫荡 **58 组**变异（再换 **8** 个文件：自然序解析、排序字段、命名求值、
  警告参数、改名计划谓词、命名摘要、批次规则、设置默认值）→ 15 存活 → 判定出 **13 真缺口**（补断言后全捕获）
  + **2 已证等价**（N4/R2）⇒ 又 +10 用例（357 → **367**）。
  **余量轮 11（W15）另加**：四次广扫荡 **64 组**变异（换到前六次**从未投过**的整个 `model/` 包
  + 从零覆盖的 `workflowb/`，共 9 个文件：命名谓词与常量、元数据建议/待确认/覆盖、排序规则守卫、
  显示口径与单位阈值、工作流 B 全部 12 个入口、内存设置存储、消息参数契约、样例库自身）→ 32 存活 →
  判定出 **31 真缺口**（补断言后全捕获，且每条捕获者是它自己的新用例）+ **1 已证等价**（N6）
  ⇒ 又 +39 用例（367 → **406**）。
  七次扫荡累计：W7 23 组 / W8 16 组 / W11 7 组 / W12 23 组 / W13 39 组 / W14 58 组 / **W15 64 组**。
  **余量轮 12（W16，收尾轮）另加**：五次广扫荡 **66 组**变异。开跑前先用 `ast.parse` 精算了历史覆盖面
  （**266 组 / 28 个生产文件**；从未投过变异的只有 `model/ImageCollection.kt` 与 `text/TextKey.kt` 两个
  **纯声明**文件 ⇒ 行为面已结构性覆盖满），据此挑面：**12 张「枚举 → `TextKey`」映射表**（50 个键在
  31 个领域测试文件里引用计数**全为 0**，指错的后果是**静默串味**而非崩溃）+ `DangerAction` 文案三元组
  + 生产有调用但测试零引用的 `comicVolumeOf`/`setIncluded`/`includedVolumes`/`updateComponent`/
  `clearOverride`/`containsIllegalChar`/`compareNaturalTokens` 等 → 42 捕获 / 24 存活 → 判定出
  **23 真缺口**（补断言后全捕获）+ **1 已证等价**（C5：`RenamePlannedOp.label` 的定义就是 `op.finalName`）
  ⇒ 又 +19 用例（406 → **425**）。
  八次扫荡累计：W7 23 组 / W8 16 组 / W11 7 组 / W12 23 组 / W13 39 组 / W14 58 组 / W15 64 组 / **W16 66 组**。
  **W16 为 12 轮上限内的最后一轮**（goal `maxGoalRounds = 12`），后续加固需用户重新授权并追加轮次。
  **轮 15（W19）另加**：R3 真缺陷修复（覆盖后撤销会删掉用户原有 CBZ）——领域层新增
  `UndoScope{ThisRun,Everything}`、`UndoOp.DeleteOverwritten`、`ExecutionReport.overwrittenCount/needsUndoWarning`，
  并把 `VolumePackPlan.overwritesExisting` 收成**全仓唯一判据**（报告计数 / 危险动作 / 撤销推导同源）；
  UI 层撤销按钮在**有覆盖项时先弹三选一警告**（两个执行档实心错误色、取消无底色且排在最后），
  `onUndo` ⇒ `onUndoScope: (UndoScope) -> Unit`（**无默认值**，逼每个调用点表态）。
  31 组变异**两遍一致全捕获**（第一遍 3 存活 + 2 编译失败全部归因为测试/变异设计错，补强后转捕获），
  domain +2 ⇒ **431**、app +18 ⇒ **420**。
  ⚠️ 本轮是本分支**首次改动 UI 布局与交互**（此前轮 14 只动资源与映射）——用户 `m04079` 第③项授权范围内。
   **轮 16（W20）另加**：R4 真缺陷修复（页码自检拿"自己的输出"跟"页序长度"比 ⇒ 恒等 ⇒
   `pageIssues` 恒空、`consistent` 恒真、UI 告警分支不可达）—— `validatePageConsistency` 改为比
   「卷内登记的页 `volume.pages`」与「页序里排的页 `pageOrder.order`」：长度不同报
   `PageIssueCountMismatch`，长度相同但成员不同报**新增键** `PageIssueMissingFromOrder`
   （+ 双语资源 + `resId()` 映射）；判据是**集合/成员**而非位置 ⇒ 人工拖拽（只改 `order` 不改
   `images`）不会误报，有专门用例钉死置换不变性。补零交叉校验**保留**——它在本轮同时**纠正**了
   §4 R4 行原措辞的一处不准确：`startsWith("$expected.")` 并**不**恒真，`expected` 的补零宽度用
   `pages.size`、`renumberPages` 用 `order.size`，是同一规则的**两份独立拷贝**。
   21 组变异**两遍一致全捕获**（第一遍 7 存活全部归因为测试/变异设计错，其中 5 处是真测试缺口
   —— 同源期望值盲区 + 中文槽位在默认 locale 下不被加载；补了 2 条形状断言用例后转捕获）。
   domain +3 ⇒ **434**、app +5 ⇒ **425**。
   ⚠️ 本轮**未改 UI 布局**，只改领域判据 + 新增一条文案 + 映射与资源。
   ⚠️ **诚实范围声明**：生产路径下 `ComicVolume` 构造点唯一（`MetaEdit.kt:81`）且
   `model/Metadata.kt:92` 的类型级默认值就是 `pageOrderOf(pages)`，故这两者**目前永不分叉** ——
   新检查是**防御性**的，它解决的是"修复前告警分支不可达"，而不是"用户马上会看到新告警"。
  十次扫荡累计：W7 23 / W8 16 / W11 7 / W12 23 / W13 39 / W14 58 / W15 64 / W16 66 / W19 31 / **W20 21 组**（W20 = R4 修复面，两遍一致全捕获）。
  **轮 17（W21）另加**：i18n 自动化（用户 `m04079` 第⑤项）—— 新增「键 → 译文」结构契约测试
  `app/…/ui/text/TranslationCoverageTest.kt`（**5 条契约 / 313 行**），关键是**另立独立的一侧**：
  既有 `PixFoldTextTest` 的期望值一律经 `TestText.of(同一个资源)` 取得（资源改坏时两边一起变 ⇒ 断言恒真），
  而 `resId()` 是 private 穷尽 `when`，只保证「有资源」不保证「指对」；新测试**完全不碰 `resId()`**，
  改按命名约定 `TextKey.FooBar → domain_foo_bar`（107/107 成立、0 例外）反查资源，
  并在**同进程内**切出中文 `Context` 与英文逐字对拍 —— 于是「映射指错 / 复数档取错 / 翻译漏了 / 抄成另一种语言」
  第一次全部可观测。16 组变异**两遍**（第一遍 15 捕获 / 1 存活，第二遍 16 捕获 / 0 存活 / 0 锚点缺失 /
  0 编译失败；3 个文件还原**逐字节一致**）。app +5 ⇒ **430**；**生产代码零改动**。
  ⚠️ **第一遍的唯一存活者 M14 值得单独记下**：变异「英文 `warn_too_long` 的 `one` 档删掉第 2 个占位符」存活，
  根因是**期望值与实际值同源**（数量为 1 时期望取自 `one` 档、实际也由 `one` 档渲染 ⇒ 两边同时变）
  —— 这与 W20 的 M15–M18、轮 14 记录的盲区是**同一个根因的第三次命中**。补的契约是
  「**各档占位符同形**」（拿**另一档** `other` 当独立的一侧，英 `one`/英 `other`/中 `other` 三处签名一致），
  定点复核确认 M14 现在**恰好**由这条新用例捕获后才跑第二遍全量。
  ⚠️ **契约边界如实记**：该契约只管**形状**不管**词形**（`1 item` vs `1 items` 哪个对需要词典，机械判定不了），
  词形正确性靠「各档文本必须不同」这一条 + 人工评审 ⇒ **不夸大成「译文已自动校验」**。
  ⚠️ **既有 i18n 复查结论 = 无需调整**，依据两条正交普查：①资源面只读审计脚本六类可疑项
  （`UNMAPPED`/`SNAKE-MISMATCH`/`MISSING-EN`/`MISSING-ZH`/`DUP-RES`/`SAME-TEXT`）**全部为 0**；
  ②生产层 CJK 字面量普查 + Compose 语义槽裸字面量普查（13 处）⇒ 命中全是 KDoc 注释、`mock/` 模拟数据、
  开发者可见的 `require` 消息、磁盘文件名模板、**符号前缀**（`· `/`→ `/`← `/`⛔ `）与数据插值，
  **无用户可见文案漏翻**。两处外观不一致如实记为**观察项、不改**：
  `ui/plan/PlanComponents.kt:229` 危险弹框正文写死 `“⛔ $body”` 而同文件 `:150` 同类前缀走资源键
  （仓库三处注释已裁决「符号前缀不属于文案」⇒ 不构成漏翻）；`ui/HomeScreen.kt:67` 与
  `ui/settings/LicensesScreen.kt:198` 写死 `“PixFold”` 而未引用 `R.string.app_name`（值与资源逐字相同）。
  十一次扫荡累计：W7 23 / W8 16 / W11 7 / W12 23 / W13 39 / W14 58 / W15 64 / W16 66 / W19 31 / W20 21 / **W21 16 组**。
  十二次扫荡累计：W7 23 / W8 16 / W11 7 / W12 23 / W13 39 / W14 58 / W15 64 / W16 66 / W19 31 / W20 21 / W21 16 / **W22 21 组**（W22 = 第⑥项 图标面，两遍一致：8 捕获 + 2 已证等价 ⇒ 4 捕获）。
- **未做 / 不宣称**（与 HANGOFF 小节一致，勿据此认为 D3 已完成）：
  1. ⚠️ **真机核对** —— 领域核心工作面（W0–W21）按 §7.4 表第 1 行判定不需要；
     **轮 18（W22 / 第⑥项）是 UI 面改动，已在真机上核对完毕**（10 个警示面 + i18n 中英对照，
     逐面取证见 §3.1 轮 18）；**轮 19（W23）补上了 R4 页码自检告警的真机取证**（临时注入分叉卷探针 ⇒
     中/英各截到一次告警，再用干净包反向对照证明告警消失 + 布局回流 56 px，逐面取证见 §3.1 轮 19）；
     真机上**仍未验证**的只剩 **R2 的逐项句子** —— 它已由用户 `m02554` **裁决为刻意保留**（键不可达，
     见 §4 R5），属**设计如此**而非待办；
  2. ❌ **D3 三项退出条件的正式宣布**；
  3. ❌ **门槛表/阶段快照的同步改动**（需 `HANGOFF.md` §9 顶 + `AGENTS.md` 头 + `README.md` 三处
     一致修改，属产品决策）；本分支对 `AGENTS.md`/`README.md` **零改动**已核对；
     ⚠️ **轮 20 补充**：`HANGOFF.md` §9.1 的 D3 小节**已回写**（就地订正 + 追记，纯追加 `+131 / −0`，
     `768bec7`）—— 但那是**小节内的内容补记**，**不是阶段状态改动**：三处阶段快照
     （§9 顶 `> 进度(` 行 / 门槛表 `| D3 |` 行 / `| **打包** | ⬜ **未交付** |` 行）与
     `AGENTS.md` / `README.md` **仍然逐条未动**。
     ⚠️ **轮 20 再补充**：追记落盘后又发现其中两处分支读数**写下即过期**
     （`dev == d3/p1` / `[ahead 42]`），已就地加注订正（`HANGOFF.md:2755` 与 `:2836`，
     纯追加 `11 0`）；订正注本身第一版写死了提交数、**又立刻自我过期**，
     已改为**时间无关表述**（`3ff135e`）。**三处阶段快照逐字未动的结论不变。**
  4. ❌ **退出条件 2 的"适配层零 Android 依赖"可执行检查**（如 Gradle 依赖断言）—— 未授权，未做；
  5. ❌ D2b 五项、§11 第 12 条空间阈值。
- **待用户裁决**（§4）：**R1** 撤销 `from == to` 空操作（既有缺陷，非本轮引入，修法二选一）；
  **R2** 改名计划描述缺逐项句子（补齐需新增 `TextKey`，本轮禁止）；
  **R3**（轮 7 新登记）`Overwrite` 策略下撤销会去删**用户原有的文件**（修法三选一，属产品决策）；
  **R4**（轮 9 新登记）页序自检的两个分支**永不可达**（校验输入与重编号输出同源）⇒
  `consistent` 恒真、`PackPreviewPage.kt:204` 的告警分支不可达；修法要改"一致性"的输入语义，属产品决策。
  **其中 R1 已于轮 13（W17）关闭、R2 已于轮 14（W18）关闭、R3 已于轮 15（W19）关闭**；
   **R4 已于轮 16（W20）关闭**（用户 `m04079` 第④项「想一想怎么修」授权后，取登记里的第②种修法）；**四项全部关闭，§4 已无待裁决项**。
   **i18n（§4 新增行）已于轮 17（W21）关闭**（用户 `m04079` 第⑤项「这属于 i18n 内容，顺带实现了」授权后落地）
   ⇒ **§4 五项（R1/R2/R3/R4/i18n）全部关闭**。
   **R5 为轮 18（W22）新登记，已于轮 19（W23）由用户 `m02554` 裁决**（第⑥项真机核实时发现）：
   R2 的键**在生产路径上不可达** ⇒ 用户选定第 ① 种**保持现状** ⇒ 台账记为**「无生产出口，刻意」**
   （键 / 双语资源 / `resId()` 映射 / 相关测试一律不动）⇒ **§4 已无待裁决项**。
   ⚠️ 这是"决定不修"而非"修好了"：生产可达性**不变**。
  **轮 15（W19）未新增待裁决项** —— 31 组变异两遍全捕获，无存活者（第一遍的 3 个存活者与
  2 个编译失败已逐条归因为测试/变异设计错，并以补强断言消除）；R3 是用户 `m04079` 第③项
  裁决后按"默认安全档 + 显式弹框选择 + 判据收成一处"这条最小授权面落地。
  **轮 13（W17）未新增待裁决项** —— 6 组变异全捕获，无存活者；R1 是用户裁决后按第 ① 种语义落地。
  **轮 14（W18）未新增待裁决项** —— 10 组变异全捕获，无存活者；R2 是用户「改吧」后按
  "新增单个 `TextKey` + 双语资源 + 映射"这条最小授权面落地。
  > ⚠️ **轮 14 的一条覆盖事实，单独记下**（与轮 12 那条互补）：轮 12 已确认"键 → 哪一句译文"
  > 无自动化检查；轮 14 进一步实证了这个盲区的**具体形态**——资源**内容**写错时，
  > "期望值经同一份资源算出"的既有做法会让期望值与实际值**一起错**，断言照样全绿。
  > 本轮用"句子可观测形状"判据（两个名字都出现、原名在前）在**英文**与**中文**两个槽位各补一条，
  > 才把 3 组资源内容变异从"存活/假编译失败"转为捕获。**该盲区对全部 50+ 个键依然存在**，
  > 只是现在有了一个可复制的判据形态。
  **轮 8（W12）未新增待裁决项** —— Q1/Q6 两个存活变异都属"断言区分度不够"，
  语义本身没有分歧（四元组顺序、前导点归属在代码里都是确定的），故直接补断言，无需用户裁决。
  **轮 9（W13）新增 R4 一项待裁决** —— 12 个存活者里 8 个是"断言不够"（直接补断言即可），
  另 4 个（R2/R5/O5/M6）已给出**可从源码复核的等价性证明**（不是"看不出差别"就登记）。
  **轮 10（W14）未新增待裁决项** —— 15 个存活者里 **13 个是"断言不够"**（直接补断言即可），
  另 2 个（N4/R2）已给出**可从源码复核的等价性证明**；本轮**没有**发现新的产品语义分歧。
  **轮 11（W15）未新增待裁决项** —— 32 个存活者里 **31 个是"断言不够"**（直接补断言即可），
  另 1 个（N6）已给出**可从源码复核的等价性证明**（`rootDirName` 是非空 `String` ⇒ `if/else` 与 elvis 逐分支同值）；
  本轮**没有**发现新的产品语义分歧。
  **轮 12（W16，收尾轮）未新增待裁决项** —— 24 个存活者里 **23 个是"断言不够"**（直接补断言即可），
  另 1 个（C5）已给出**可从源码复核的等价性证明**（`OperationPlan.kt:76` 的 `label` 定义就是 `finalName`）；
  本轮**没有**发现新的产品语义分歧。R1–R4 四项至此仍未决，**均不阻塞**（本分支不再自动续跑，
  是否裁决由用户决定）。
  > ⚠️ **轮 12 的一条覆盖事实，单独记下**：12 张「枚举 → `TextKey`」映射表**共 50 个键**在
  > 31 个领域测试文件里的引用计数**全为 0**，`DangerAction` 的 36 个 label/body/button 键同理。
  > 这些键的**正确性完全依赖 app 侧 `ui/text/PixFoldText.kt` 的 `when` 分支**，而领域测试对它们
  > 一个字都没断言过（W16 之前）。本轮新建的 `text/TextKeyMappingTest.kt` 只钉死"**枚举项 → 哪一个键**"
  > 这一层；**"键 → 哪一句译文"仍无任何自动化检查**（那需要 app 侧资源测试，且译文属产品文案），
  > 故这批映射若被改错，领域测试能拦住的只有"指到别的枚举项的键"，拦不住"键本身写错字"。
  > 这是**已知边界**，不构成缺陷，但值得知道。
  > ⚠️ **轮 11 的一条覆盖事实，单独记下**：`text/Msg.kt` 的两个 `msg(...)` 助手
  > **在全仓生产代码里零调用点**（`grep -rn "msg(" pixfold-d1/*/src/main` 无命中），
  > 只有 app 层测试与领域测试在用 ⇒ 目前它只是一份"参数保序 / 保留类型"的**契约**，
  > 由一个尚未接线的新增路径使用。本轮为它补了 3 条断言（`text/MsgTest.kt`），
  > 但**若后续接入方不用 `msg(...)` 而是直接写 `Msg(key, listOf(...))`，该契约照样成立** ——
  > 这不构成缺陷，只是说明"零调用点"本身值得知道（未来若决定删除该助手，需同步删测试）。
  > ⚠️ **轮 9 的一个诚实说明**：R2/R5 的等价性**根因是同一处设计**——
  > `validatePageConsistency` 的输入 `pages` 就是 `renumberPages(volume)` 的输出，两者同源，
  > 因此"页数不符"与"序号不符"这两个分支在当前实现下**永远不可达**（`pageIssues` 恒空、
  > `consistent` 恒真）。这意味着 `ComicPreview.consistent` / `pageIssues` 目前**只是恒真恒空**，
  > UI 侧 `PackPreviewPage.kt:204 if (!preview.consistent)` 的告警分支同理不可达。
  > **这不是本轮引入的缺陷，也不在本轮授权面内**（要让它们可达就得改 `validatePageConsistency`
  > 的输入来源，属产品/接口决策），故只记录、不改。
  > 可在后续轮次作为**候选裁决项**提出（是否让预览基于"磁盘上真实存在的页"而非"重编号结果"）。
- **诚实性说明**：②③两项本可"顺手写上"，但那属于**替他做产品决策**，按 §7.1 第 1 步**只记录不改**。

