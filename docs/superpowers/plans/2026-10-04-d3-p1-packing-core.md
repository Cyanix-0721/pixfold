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
| W0 | 准备：开分支 `d3/p1` + 写本台账 + `create_goal(12)` | 分支存在、台账已提交、goal 已武装 | ✅ 进行中 |
| W1 | `OperationPlan` 词汇落地：`sealed interface PlannedOp`（`Rename`/`Package`/`DeleteSourceDir`/`CleanEmptyDir`）+ `data class OperationPlan(ops)` 带 `total/skipped/runnable/counts/describe()`；`operationPlanOf(plans)` / `operationPlanOfRenames(ops)` | 红→绿单测；`describe()` **复用既有 `Pack*` 键，不新增 TextKey** | ⬜ |
| W2 | `executePackagePlans` 改由 `OperationPlan` 驱动（`execute(plan): ExecutionReport`），旧函数保留为薄委托 | 既有 `PackagePlanTest`/`ExecutionReportTest`/`PlanUiTest` **零改动**全绿 | ⬜ |
| W3 | `executeRenamePlan` 同上（`operationPlanOfRenames`） | 既有 `RenamePlan`/`ExecutionReport` 相关测试零改动全绿 | ⬜ |
| W4 | `pack/CbzWriter.kt`：`write(comicInfoXml, pages, out, timeMillis)` —— **STORED**（依据 `scripts-behavior-matrix.md:267` 参考脚本用 `ZIP_STORED`）、`ComicInfo.xml` **结构上强制第 0 条**、条目名 UTF-8 | 单测用 `ZipInputStream` 回读：条目顺序、`getMethod() == STORED`、名称集合、内容逐字节相等 | ⬜ |
| W5 | `pack/CbzWritePlan.kt`：`buildCbzWritePlan(volume)` 复用 `ComicInfo.build` + `renumberPages`；`interface PageByteSource { fun bytesOf(itemId: String): ByteArray? }` + `writeCbz(plan, source, out, timeMillis)` 返回 `CbzWriteResult(written, missing)` | 单测：页序含人工调整、≥100 页跨 3→4 位补零、缺图 → `missing` 而非抛异常 | ⬜ |
| W6 | `UndoRecord` / `ExecutionReport` 与新词汇对齐的全路径覆盖 | 单测：Package→`DeleteCreated`、Rename→`RestoreRenamed`、Skipped/Failed 不产生撤销项、`canUndo=false → null` | ⬜ |
| W7 | 变异清扫（≥ 20 组）覆盖 W1–W6 新增断言 | 0 逃逸；存活变异按 §0.4 处理并记录 | ⬜ |
| W8 | 边界与不变式加固（空卷 / 单页 / 重名跳过 / `isBlank` 输出目录 / 冲突策略矩阵） | 新增断言 + 变异验证 | ⬜ |
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

---

## 4. 待裁决 / 阻塞（越界项写这里，不自行决定）

> 出现下列任一情况，**停止该项**并在此登记，然后继续**下一项**工作：
> 需要新产品决策、需要新增 `TextKey`/资源、需要改 `app/**`、需要真机结论、
> 需要新增依赖、需要产品阈值（如 HANGOFF §11 第 12 条的空间不足阈值）。

| # | 轮次 | 事项 | 为什么不能自行决定 | 状态 |
| --- | --- | --- | --- | --- |
| — | — | （暂无） | — | — |

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
