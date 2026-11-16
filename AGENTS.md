# AGENTS.md — PixFold(项目级)

> PixFold:图片整理与 CBZ 制作工作台(图形应用),**GUI 只做 Android**(Windows / Linux 端只做 CLI),技术栈 **Kotlin + Jetpack Compose(Android 原生)**。产品定位 / 约束 / 领域模型 / 路线 / 验收标准的唯一权威是 `docs/product/HANDBOOK.md`;当前阶段以 HANDBOOK §9.1 为准。
>
> **当前阶段:D4a 进行中**(用户裁决「UI 还有很多细节要打磨」,不设退出日期;发版门槛 = MVP 彻底实现之后)。逐轮裁决、真实缺陷、闸门读数与变异结果看 `docs/INDEX.md` 的「历轮台账」,各阶段实现留痕在 `docs/phases/`。
>
> **会话 / 换机交接请用 `/handoff` 生成临时文档,不在本文件维护。本文件头部只放「当前阶段一句话 + 指针」;历史进度、缺陷清单、闸门读数一律不进头部。**

## 0. skill 匹配(先于一切,本节唯一不可被项目级覆盖)

**动手前的第一个动作是 `skill` 调用,不是 `bash`/`read`/`todo_write`。**

> 「不可被项目级覆盖」只针对**自动加载的项目指令**;用户当回合说「跳过 skill」仍然有效(用户 > 项目 > 全局),只是那种覆盖必须来自用户本人,不能来自本文件的其它章节。

条文本身在全局 `~/.dsh/AGENTS.md` §0,此处不复述(避免两处漂移)。项目侧只补三条本条特有的事实:

1. **技能名无前缀**。实际名是 `brainstorming`/`writing-plans`/`executing-plans`/`test-driven-development`/`systematic-debugging`/`verification-before-completion`/`requesting-code-review`/`finishing-a-development-branch`/`dispatching-parallel-agents`/`using-git-worktrees`/`using-superpowers` 等 15 个,**没有 `superpower-` 前缀**;叫错名 = 调不到。
2. **本项目最常见的漏掉场景**:请求里出现「加一个/改一下/支持 X」但没明说「设计」时,`brainstorming` 仍然适用;请求是「检查/收尾/验收」时,`verification-before-completion` 与 `finishing-a-development-branch` 适用。
3. **§7.1 的七步不是 skill 的替代**。七步管「怎么落地」,skill 管「用哪套方法落地」——§7.1 第 2 步(红→绿)对应 `test-driven-development`,第 3 步(变异)与第 7 步(提交)分别对应 `verification-before-completion` 与 `finishing-a-development-branch`。**跳过 skill 直接进七步 = 只剩流程没有方法。**

⚠️ 依据(实测,非推断):本工作区 7 个历史会话 337 个人工轮、23,780 次工具调用中 `skill` 只出现在 **8 轮(2.4%)**;而技能目录在每个会话第 1 轮就已完整注入(38 个技能,含上述 15 个)。**问题是看见了不调,不是看不见。**

### 0.1 六个可自查的锚点（2026-10-24 加固）

只加规则不解决问题——上一版写过的规则，chip 改版那轮照样漏掉了 `brainstorming`/`writing-plans`/`using-git-worktrees`。故补六个**可被机械检查**的锚点。历年事故与实测失效的原始记录（含时间线、用户原话）见 [`docs/notes/pixfold-agent-纪律台账.md`](docs/notes/pixfold-agent-纪律台账.md)，此处只留规则。

1. **`todo_write` 的第 1 项必须写本轮要用的 skill 名**（如「加载 brainstorming 澄清 chip 交互」），之后每完成一步立即标记。理由：任务面板是**压缩后唯一能自证进度的载体**——第 1 项写着 skill 名，压缩十次之后仍看得见欠着它；只写在头脑里，压一次就没了。
2. **下列动作都不算 skill 调用**：读源码、画示意图/HTML 原型、建 `todo_write`、给用户列方案征求意见、用户已给出明确裁决。**把用户的裁决当成 `brainstorming` 的等价物是自我豁免**——裁决只是裁决，不会生成「设计 → 计划 → 执行」的方法链。
3. **闸门判绿的三条硬判据**（细节见 skill `pixfold-build-gates`，此处只列判据，防止用缓存结果冒充通过）：① 只看 `android/build/gate-last.rc` 与日志 `BUILD SUCCESSFUL|BUILD FAILED`，**不看作业状态**；② 相关 task 不是 `UP-TO-DATE`/`FROM-CACHE`，否则必须加 `--rerun-tasks` 重跑；③ lint 判绿看 `rc`，**不是** `grep -c warning`。
4. **多文件改动的第一个动作是开分支**（`using-git-worktrees`），不是 `read`/`edit`。判据：`git branch --show-current` 不能是 `dev`；分支从 `dev` 开、完成合回 `dev` 并删除，阶段前缀按 HANDBOOK §9.2 拓扑取（现为 `d4/…`）。**不要自己判例外**——「只改文档」「改动很小」「dev 单线开发没有并行需求」都是自我豁免的形式；唯一合法出口是**用户当回合本人**说在 `dev` 上直接改，判断权不在我。
5. **收工前必须把派出去的子 agent 全部收口**。收工/关机/结束会话前，四个清单必须都是空的：① `list_agents` 无 `running`/`provisioning`；② `job_list` 无 `running`/`stopping`；③ `terminal_list` 不再需要的会话已 `terminal_close`；④ **用户当回合要求的收尾动作必须真的执行**（关机/重启/退出），判据是**命令真的发出去了**，不是报告里写了晚安。派出去但不等的，要么 `job_kill`/`interrupt_agent` 明确终止，要么拿到结果再走，**不许让它们挂在那儿自己结束**。这条收尾动作**必须挂进 `todo_write` 最后一项**——报告里许愿会随压缩丢掉，面板项不会；**「说过了」不等于「做过了」**。对 `gate.sh` 的作业要特别小心：它的 `cmd.exe` 子进程句柄不关，`job_output` 会**永远报 running**，判据用落盘 `gate-last.rc` + 日志 `BUILD SUCCESSFUL|BUILD FAILED`，确认完成后**主动 `job_kill`**。
6. **派子 agent 前先记账，等它时只做只读的事**。六条硬规则：① **派发即记账**（派出瞬间写下 `subagentId`/时间/文件范围，没记 id 等于没派发）；② **等待期只做只读的事**，不碰工作树、不派新 agent；③ **「它死了」的唯一合法证据 = 收到该 subagent 的终态通知**，`git status` 变空 / 文件 mtime 不动 / `git log` 无新 commit / `list_agents` / `wait_agent` 五样**全不构成证据**；④ **未收到终态通知时禁止重派同一 Task 号**，等不下去就继续等；⑤ **变异验证必须在 commit 之后**（最救命）；⑥ **一次只派一个**，粒度小到能在可接受时间内返回。

## 1. 文档层级（谁在哪记录什么）

产品决策的**唯一权威是 [`docs/product/HANDBOOK.md`](docs/product/HANDBOOK.md)**；完整文档地图与「哪个文件记什么」的清单在 [`docs/INDEX.md`](docs/INDEX.md)，新增文档须在 INDEX 登记一行。速记：

- **`docs/product/HANDBOOK.md`** —— 产品决策唯一权威（定位/约束/用户工作流/领域模型/技术栈 §8/路线 §9/验收 §10）；文件内标题即目录。
- **`README.md`** —— 项目门面，须与 HANDBOOK 一致；含开发环境部署节（暂定）。
- **`docs/INDEX.md`** —— 文档地图（非产品权威）：四层 = 产品权威 / 阶段详版 `docs/phases/` / 笔记 `docs/notes/` / 规格与计划 `docs/superpowers/`。
- **`docs/phases/`** —— 各阶段实现留痕（`d1-…`→`d4-mvp`），是 HANDBOOK §9 的详版，「只查不常读」。
- **`docs/notes/`** —— 调研与实证笔记（如 `d1-ui-friction-inventory.md`、`d1-toolchain-evidence.md`、`pixfold-agent-纪律台账.md`），每条结论附来源/命令。
- **`docs/superpowers/`** —— D1 流程产物 `specs/`（设计规格）、`plans/`（实施计划）。
- **`.mise.toml`** —— 工具版本声明（纯配置无注释，说明见 docs/notes/environment.md §12.2）；变更后 `mise install`。
- **`scripts/`** —— 两个 Python 脚本，行为参考/回归样例，**不是最终规范**。
- **`android/`** —— Android 工程（包名 `com.cyanix.pixfold`），`domain/` 纯 Kotlin JVM + `app/` Compose，在 dev/阶段分支上。
- **归档分支 `archive-flutter-verify`** —— 已删除旧原型整树快照，查 SAF Kotlin 实现用 `git show <sha>:<路径>`（见 docs/notes/environment.md §12.7）。

**变更路由（维护规则）**：领域术语/决策/约束变更 → **先回写 `docs/product/HANDBOOK.md` 对应章节（唯一权威）**，不在别处另起炉灶；凡改变「当前阶段/状态」，随后同步本文件头部快照与 `README.md` 状态/路线，保持三处一致、不得只改一处。环境/工具类变动回写 `docs/notes/environment.md` §12。原 `s_handoff.md` 已于 2026-09-07 并入 §12 后删除，勿再新建同类文件。

**反方向同样成立：历史不进头部。** 每轮收工写文档时，「这一轮做了什么」写进 `docs/INDEX.md` 的历轮台账或 `docs/phases/`，**不写进本文件头部**；头部只允许「当前阶段一句话 + 指针」两类，会话 / 换机交接用 `/handoff` 生成临时文档。

## 2. 不可违背的产品约束

1. **扫描、决策、执行分离**:扫描/预览阶段不改文件;执行前必须给出完整计划供用户确认。
2. **建议 ≠ 事实**:自动识别的排序、名称、卷号、语言必须可见、可改。
3. **预览是主工作区,不是日志**。
4. **批次统一 + 逐项例外**:批量设置后允许单项覆盖。
5. **破坏性动作可解释**:默认保留源文件;删除必须独立确认并列出影响范围。
6. **Android SAF 优先验证**:目录授权、遍历、读写、缩略图、后台任务。
7. **UI 符合最新 Material Design**(§6.5):用 material3 语义角色(颜色/排版/形状),不硬编码;
   支持深色模式;Android 12+ 动态取色;触控 ≥48dp;纯图标按钮给 `contentDescription`。
   **当前基准 = material3 1.5.0-rc01**(2026-11-08 用户裁决,单独指定版本覆盖 BOM,启用 M3E
   `MaterialExpressiveTheme` + `MotionScheme.expressive()`;运行期 minSdk 24、构建期要求
   compileSdk 37/AGP 9.1.0 均已满足;详见 §6.5)。**非 stable,版本须锁死**。

## 3. 领域词汇

设计/实现讨论一律用 `docs/product/HANDBOOK.md` §5 统一领域模型中的词。发现术语冲突或缺口时:**先与用户确认,再回写 HANDBOOK**,不要自行造词或悄悄换说法。`docs/product/HANDBOOK.md` 是领域主场;引入独立 CONTEXT.md / ADR(如经 domain-modeling skill)须用户同意。

## 4. 当前阶段：该做 / 不该做

**当前阶段：D4a 进行中**（用户裁决「UI 还有很多细节要打磨」，不设退出日期）；D1/D2/D3/D4b 与工作流 C 已完成并真机取证；发版门槛 = MVP 彻底实现之后。**权威状态以 `docs/product/HANDBOOK.md` §9.1 为准，路线与执行顺序以 §8.2/§8.3/§9 为准**，本处不复述细节，需要时再读。

**该做：**

- 技术栈已锁定 **Kotlin + Jetpack Compose（Android 原生）**；**GUI 只做 Android**（§2/§7）。`android/` 的 UI 即 D4 MVP 基座，MVP 能力**就地演进**。
- 交互或数据改动须过「**修复自证三层**」：领域单测 → UI 层断言 → 真机实证（埋点 + 模拟输入 + 像素/快照对比），不得只凭「测试通过」交付。每轮固定步骤与真机规约见 §7。
- 版本声明经 `.mise.toml`（项目）；全局工具声明不属本仓库管辖（见 docs/notes/environment.md §12.2）。

**不该做：**

- **不另起新的「正式工程」**（2026-09-21 重述）：`android/` 即 D4 基座，MVP **就地演进**，不新建工程、不推倒重写。发版仍按 §9.2 拓扑（`dev` → `main`）。
- 不把 `scripts/` 现有行为当作最终规范照搬。
- 不用 `mise use -g` 改全局工具声明（绕过 chezmoi 造成漂移）。
- 不改 D1 验收清单的判据以「迁就现状」（清单是 D1 的唯一出口，条目变更须用户确认）。

## 5. 脚本使用

- Python ≥3.10,PEP 723 内联依赖(metadata 在脚本头部 `# ///` 块);用 `uv run scripts/<名>.py …` 运行。
- 脚本含交互输入与真实文件操作:**运行前先向用户说明影响范围**;能 dry-run 就先 dry-run。

## 6. 其他约定

- 语言:与用户用中文交流;仓库文档双语(README)或中文(HANDBOOK),新增/修改匹配所在文件的语言风格。
- Git:本仓库独立于 chezmoi dotfiles 仓库;忽略项现状为 **`.agents/`**（用户 2026-09-19 移除 `.kilo/`、`.workbuddy*/` —— 两者已不再使用）。
- **分支模型(2026-09-16 起)**:`ready`(设计文档线,原型代码永不进)→ **`dev`(开发主线)** → 阶段分支 `d1/pN`(D4 起为 `d4/…`)。阶段分支从 `dev` 开、**完成即合回 `dev` 并回写文档**、随即删除;D1 全绿后 `dev` → `main` 发 release,此后开发继续在 `dev`。**push 由用户自行处理**,agent 只提交不推送。详见 HANDBOOK §9.2。**开分支是硬前置,判据与出口见 §0.1 第 4 条。**
- 验收:任何改动对照 `docs/product/HANDBOOK.md` §10(功能 / 数据正确性 / 平台)自检。

## 7. 每轮任务的固定步骤与真机核对规约

> 本节把已反复踩过的坑固化为步骤。**用户可当回合覆盖本节**;未覆盖时按此执行,不得跳步。

### 7.1 固定步骤(顺序不可调换)

> **进入下面七步之前,先过 §0** —— skill 匹配是第一步的前置,不是第零个可选步骤。条文与六个可自查锚点都在 §0,本节不重复。第 1 步动手前先过 §0.1 第 4 条(开分支);收工前过第 5、6 条(子 agent 收口、派发纪律、以及用户当回合要求的收尾动作 —— 关机命令必须真执行且挂进任务面板最后一项)。

1. **对齐依据**:读 `docs/product/HANDBOOK.md` 对应条目(产品决策唯一权威)+ `docs/notes/d1-ui-friction-inventory.md` + 用户当回合指令;**有歧义先请用户裁决**,把裁决项写下来再动手。
2. **红 → 绿**:先写会失败的断言,再改码;跑四道闸门(口径见 skill `pixfold-build-gates`)。
3. **变异验证**:证明断言有杀伤力(变异纪律见 skill `pixfold-device-testing` 末)。**派给子 agent 时,它的 commit 必须早于变异**(§0.1 第 6 条 ⑤)—— 本会话自己做的变异不受此限,因为「变异不足 → 补断言 → 重跑」本来就要在提交前完成。
4. **真机核对**:必须**先获用户授权**(见 skill `pixfold-device-testing`)。
5. **回写文档**:`docs/product/HANDBOOK.md` → 同步本文件头部快照 + `README.md`(变更路由三处一致)。
6. **展示改动集 + 主动提醒提交**:列出文件与行数,然后**每次都主动问一句**:「这些改动现在提交,还是继续下一个需求一起提交?」—— 判断权在用户,不在我。**不许自己替他判「改动小/是中间态/后面还要动同一片代码,先攒着吧」**,那是把「等待裁决」偷换成「我已经决定攒着」。依据 `User said (m03873)`:「每次完成任务提醒我提交,我来决定是直接继续新需求写完一起提交还是先提交再继续」。注意这条**不豁免**下方「不得在真机核对前宣布完成」:提醒提交的前提是四道闸门绿 + 该真机的部分已真机核对,前置没做完时该说的是「还差什么」,不是「要不要提交」。
7. **提交**(只提交不推送)→ **推送前**跑密钥扫描(见 skill `pixfold-build-gates`;**必须在提交之后** —— 该扫描读的是 git 对象,提交前跑会漏掉正要提交的内容)→ **合回 `dev`** → 再补一次"提交号 + 合并结果"的文档同步提交。

⚠️ **任务面板纪律(与上列 7 步并行贯穿全程):开工建全量清单,每步完成立即标记,不得攒批** —— 条文与理由见全局 `~/.dsh/AGENTS.md` §0(含 `todo_write` 的挂载来源、`executing-plans` 依据、以及「面板是压缩后唯一能自证进度的载体」的原因),**此处不复述以免两处漂移**。**留一条本项目实测先例**:第 18 条一轮 7 项**开工时只写了一次、全程未再更新**,直到用户指出(`User said (m16667)`:「你又忘了推进任务进度」)才一次性全勾掉;性质是**执行过失而非规约缺口**。

⚠️ **同一过失反复发生，累计五次(内容相同无需重读)**：② `User said (m21345)`「你又忘了实时跟进人物面板」；③ `User said (m22486)`「你都到变异测试了任务面板却仍然没有实时跟进」；④ `User said (m22601)`「**你已经好几次没有实时更新任务面板了，全局AGENTS.md的约束还不够吗**」；⑤ `User said (m23631)`「菜单宽度这个没有更加优雅的写法吗，另外你好像又又又又又忘了更新任务面板进度」(「又」×5 ⇒ 用户已用字数计数)。⇒ 五次均为**执行过失**。现行硬规则：**任何一步完成后的第一个工具调用必须是 `todo_write`**，然后才允许启动下一步命令（可检查：面板更新时间戳必须早于下一个 bash/read）。

⚠️ **不得在真机核对前宣布完成、不得先催提交**:用户已明确纠正 —— `User said (m13805)`「你经常嘴上说完成要提交却没有给我真机校验先，而且任务进度会卡着」。**正确顺序是「真机核对 → 回写文档 → 展示改动集 → 提交」**。

⚠️ **但反过来也不行:前置绿了必须主动问提交,不许闷着。** 上面那条禁的是「拿提交当完成宣告、跳过真机」,**不是「别提提交」**。2026-10-25 用户明确要求每轮完成主动提醒(`User said (m03873)`),因为「现在提交还是攒着一起提交」是**他的调度决策** —— 我不问,他就得自己盯回来催。两条合起来的判据:**前置没绿 ⇒ 报「还差什么」;前置全绿 ⇒ 报改动集 + 问「现在提交还是攒着」。**

### 7.2 闸门与真机规约 → 见项目 skill

四道闸门的跑法与判绿口径、真机核对与 adb 已知坑,已各自下沉为项目 skill(`.dsh/skills/`,rank 100,随项目走)。**动手前用 `skill` 工具加载,不在本文件展开:**

- 跑 gradle 闸门 / 判构建·lint·测试是否真通过 / 提交后推送前密钥扫描 → **`pixfold-build-gates`**
- 连真机核对 UI(放不放得下、视觉规格、动画观感) / 判某类结论能否离线断 → **`pixfold-device-testing`**(动设备前先取得当轮授权)

> 术语保留:`四道闸门` = 一次 Gradle 调用的四个 target,密钥扫描是独立命令、语义属 §7.1 第 7 步「提交」,不要改名。
