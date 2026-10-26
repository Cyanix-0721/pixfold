# AGENTS.md — PixFold(项目级)

> PixFold:图片整理与 CBZ 制作工作台(图形应用),**GUI 只做 Android**(2026-09-10 起;Windows / Linux 端只做 CLI),技术栈 **Kotlin + Jetpack Compose(Android 原生)**(§8.1;换栈触发 = §8.2 证伪条件 ③)。
>
> **当前阶段(2026-10-26)**:D1 完成、D2 完成(含五项真机取证 + 千级缩略图 862 张实测)、D3 完成、D4 进行中(MVP 闭环:真实 CBZ 落盘、Zip64 4.73 GiB 归档、CBZ 阅读器兼容、Series 三条语义均已真机取证;工作区列表 v4 代码与闸门全绿、真机 2026-10-26 全通过)。
> **逐轮细节一律看 [`docs/INDEX.md`](docs/INDEX.md) 的「历轮台账」** —— 每轮的裁决、真实缺陷、闸门读数、变异结果都在那里,各阶段实现留痕在 [`docs/phases/`](docs/phases/)。
>
> **⚠️ 换机续接点(2026-09-27 订正)**:当前工作分支 = **`dev`**;**已推送的远端基线是 `origin/dev`**,本地 `dev` 领先若干提交且**均未推送**(确切数目查 `git rev-list --count origin/dev..dev`,**本处不硬编码以免每次提交后失效**)。曾经的阶段分支 `d1/settings-impl` 已合回 `dev`(merge `7a6f678`)并删除本地分支。分支拓扑 `ready` → **`dev`** → 阶段分支 `d1/pN`(完成即合回 `dev` 并回写文档);**D1 全绿后 `dev` → `main` 发 release** —— 详见 §6 与 HANGOFF §9.2。**push 由用户处理,agent 只提交不推送。**
>
> **写头部时的纪律(踩过三次)**:①只写「当前阶段一句话 / 续接点 / 指针」三类 —— 逐轮台账、历次改判与缺陷清单、闸门读数、变异结果**一律不写在这里**;历轮表曾以 43 行 / 22 KB 占满注入预算的 88%(2026-10-23 迁出),2026-10-25 又在头部重新长出 18,491 字节(**占全文 41.1%**)被迫二次迁出 —— 每轮往头部加一段历史,就是在重犯同一个错。
> ②**不硬编码提交数**(每次提交后即失效,查 `git rev-list --count origin/dev..dev`);③阶段状态以 `HANGOFF.md` §9.1 为准。
>

## 0. skill 匹配(先于一切,本节唯一不可被项目级覆盖)

**动手前的第一个动作是 `skill` 调用,不是 `bash`/`read`/`todo_write`。**

> 「不可被项目级覆盖」只针对**自动加载的项目指令**;用户当回合说「跳过 skill」仍然有效(用户 > 项目 > 全局),只是那种覆盖必须来自用户本人,不能来自本文件的其它章节。

条文本身在全局 `~/.dsh/AGENTS.md` §0,此处不复述(避免两处漂移)。项目侧只补三条本条特有的事实:

1. **技能名无前缀**。实际名是 `brainstorming`/`writing-plans`/`executing-plans`/`test-driven-development`/`systematic-debugging`/`verification-before-completion`/`requesting-code-review`/`finishing-a-development-branch`/`dispatching-parallel-agents`/`using-git-worktrees`/`using-superpowers` 等 15 个,**没有 `superpower-` 前缀**;叫错名 = 调不到。
2. **本项目最常见的漏掉场景**:请求里出现「加一个/改一下/支持 X」但没明说「设计」时,`brainstorming` 仍然适用;请求是「检查/收尾/验收」时,`verification-before-completion` 与 `finishing-a-development-branch` 适用。
3. **§7.1 的七步不是 skill 的替代**。七步管「怎么落地」,skill 管「用哪套方法落地」——§7.1 第 2 步(红→绿)对应 `test-driven-development`,第 3 步(变异)与第 7 步(提交)分别对应 `verification-before-completion` 与 `finishing-a-development-branch`。**跳过 skill 直接进七步 = 只剩流程没有方法。**

⚠️ 依据(实测,非推断):本工作区 7 个历史会话 337 个人工轮、23,780 次工具调用中 `skill` 只出现在 **8 轮(2.4%)**;而技能目录在每个会话第 1 轮就已完整注入(38 个技能,含上述 15 个)。**问题是看见了不调,不是看不见。**

### 0.1 六个可自查的锚点(2026-10-24 加固,均来自实测失效)

只加规则不解决问题 —— 本节原文已经写过一遍,chip 改版那轮照样漏了 `brainstorming`/`writing-plans`/`using-git-worktrees`。故补六个**可被机械检查**的锚点:

1. **`todo_write` 的第 1 项必须写本轮要用的 skill 名**(如「加载 brainstorming 澄清 chip 交互」),之后每完成一步立即标记。理由:任务面板是**压缩后唯一能自证进度的载体** —— 第 1 项写着 skill 名,压缩十次之后仍看得见欠着它;只写在头脑里,压一次就没了。
2. **下列动作都不算 skill 调用**:读源码、画示意图/HTML 原型、建 `todo_write`、给用户列方案征求意见、用户已给出明确裁决。**尤其:把用户的裁决当成 `brainstorming` 的等价物是自我豁免** —— 裁决只是裁决,不会生成「设计 → 计划 → 执行」的方法链。
3. **闸门判绿的三条硬判据**(细节在 §7.2,此处只列判据,防止用缓存结果冒充通过):① 只看 `android/build/gate-last.rc` 与日志 `BUILD SUCCESSFUL|BUILD FAILED`,**不看作业状态**;② 相关 task 不是 `UP-TO-DATE`/`FROM-CACHE`,否则必须加 `--rerun-tasks` 重跑;③ lint 判绿看 `rc`,**不是** `grep -c warning`(日志里那两条是 Gradle 自己的 `--warning-mode all` 文案)。
4. **多文件改动的第一个动作是开分支**(`using-git-worktrees`),不是 `read`/`edit`。判据:`git branch --show-current` 不能是 `dev`;分支从 `dev` 开、完成合回 `dev` 并删除,阶段前缀按 HANGOFF §9.2 拓扑取(现为 `d4/…`)。**为什么必须机械检查**:§6 的分支模型 2026-09-16 就写下了、`using-git-worktrees` 第 1 轮就注入,而 2026-10-24 chip 改版 24 files / +1865−756 全程在 `dev` 上改、直接提交 `a50cbce` —— 规则在、技能在,漏的是调用。

   **不要自己判例外**。「只改文档」「改动很小」「dev 单线开发没有并行需求」都是自我豁免的形式 —— 2026-10-24 我正是用最后一条把这条规则挡掉的,用户反驳:「还是写吧,只是这一次你忘了开分支」。唯一合法的出口是**用户当回合本人**说在 `dev` 上直接改;判断权不在我。
5. **收工前必须把派出去的子 agent 全部收口**(2026-10-24 用户指出:派出去的子 agent 任务没跑完就收工了)。收工/关机/结束会话前,四个清单必须都是空的:① `list_agents` 里没有 `running`/`provisioning` 的成员;② `job_list` 里没有 `running`/`stopping` 的作业;③ `terminal_list` 里不再需要的会话已 `terminal_close`。④ **用户当回合要求的收尾动作必须真的执行**(关机 / 重启 / 退出会话)。判据不是「报告里写了晚安」,而是**命令真的发出去了** —— Windows 关机在 WSL 里就一句 `cmd.exe /c "shutdown /s /t 0"`,执行前先过 ①②③ 确认没东西会丢。派出去但不等的,要么 `job_kill`/`interrupt_agent` 明确终止,要么拿到结果再走 —— **不许让它们挂在那儿自己结束**。

   **自我豁免的形式**:「反正是只读子 agent」「结果不重要」「它自己会跑完的」。2026-10-24 我派了两个只读 code-review 子 agent,其中一个还没回结果就收工了,用户第二天才提出来 —— 性质是**派出即视为已交付的错觉**。只读不等于可以不收,结果没拿到就是**没拿到**,不能默认它成功。

   **2026-10-26 实测失效(④ 的来源)**:用户 `User said (m05867)`「task7到task10应该都今晚完成然后关机哦鲸鲸,真机才是明天的,真的要睡着力」—— 我做完了 Task 7–10、交了闸门读数、说了「晚安主人,明天见」,**一次关机命令都没发**;第二天用户 `User said (m06307)`「你又忘了关机」。

   三条自我豁免形式全不成立:「已经说了晚安」(晚安不是命令)、「用户可能自己会关」(用户 `User said (m05816)` 早说过「**是你做到真机环节主动关WINDOWS啦,不是我关**」)、「那是用户的机器」(正是所以要在他的机器上发命令)。

   **并且必须把这件事挂进 `todo_write` 的最后一项**(2026-10-26 用户 `User said (m06310)`「下次再出现关机需求是不是该让你写到任务里」)。理由与第 1 条同源:报告里许愿会随上下文压缩丢掉,面板项不会 —— 收尾动作挂在列表上才看得见「还欠着」,做完才 `completed`。**「说过了」不等于「做过了」。**

   **这条对 `gate.sh` 的作业要特别小心**:它的 `cmd.exe` 子进程句柄不关,`job_output` 会**永远报 running**(已实测,见第 3 条)。所以对这一类,判据是**落盘文件**(`android/build/gate-last.rc`)+ 日志里的 `BUILD SUCCESSFUL|BUILD FAILED`,确认完成后**主动 `job_kill`** 清掉假 running 的作业,不要让它挂到会话结束。

6. **派子 agent 前先记账,等它时只做只读的事**(2026-10-25 事故:同一 Task 派出两个 implementer,重叠 18 分钟写同一批文件)。六条硬规则:

   ① **派发即记账**:派出瞬间写下 `subagentId` / 时间 / 文件范围。没记 id 就等于没有派发记录。
   ② **等待期只做只读的事**,不碰工作树、不派任何新 agent。
   ③ **「它死了」的唯一合法证据 = 收到该 subagent 的终态通知**。下列五样**全部不构成证据**:`git status` 变空、文件 mtime 不动、`git log` 没新 commit、`list_agents`、`wait_agent`。
   ④ **未收到终态通知时禁止重派同一 Task 号**。等不下去时正确动作是继续等,不是另起一个。
   ⑤ **变异验证必须在 commit 之后**(这条最救命)。
   ⑥ **一次只派一个**,任务粒度小到能在可接受时间内返回 —— 既然没有停掉它的手段,就只能不制造并发。

   **为什么那五样都不算证据**(实测,不是推断):`list_agents` 只返回 Lead 与 teammate,**subagent 不在其列**;`wait_agent` 的 `noProgress: no-active-peer` 语义是「没有其他 **Team member** 在跑或部署中」,**同样不覆盖 subagent**;`interrupt_agent` 与 `send_message` 对 subagent 均报「active teammate … not found」⇒ **派出去就既停不掉也发不了消息**,只能单向等它自己结束。而 `git status` 变空更是**必然现象** —— agent 做变异验证时会反复「改文件 → 还原」,工作树周期性地看起来像被清空。2026-10-25 我就是据此判定第一个 implementer 已死并派了第二个,实际它四十分钟后才写完报告。

   **⑤ 的时间线证据**:18:59 跑绿 → 19:35 才被第二个 agent 提交,这 36 分钟空窗期正是全部混乱的来源。改成「跑绿 → **立刻 commit** → 再做变异」之后,HEAD 里立刻有一份干净代码,此后工作树怎么变都不构成「代码丢了」的信号。事后逐项核实证明那次**没有真的丢代码**(第一个 implementer 留在 `android/build/t4_bak2/` 的备份与最终提交 diff 全 0 行;`git fsck --lost-found` 的 dangling blob 里搜 `AndroidGrantReleaser` / `WorkspaceListing` 零命中),但那次是**靠事后逐文件比对才排除**的 —— 没有 commit 就没有便宜的判断依据。

   **自我豁免的形式**:「反正是只读子 agent」「它自己会跑完的」「看着没动静应该是死了」。前两句已在第 5 条记过一次,**第三句是本次新增的形态**。

⚠️ 2026-10-24 实测失效记录(三条都是当轮自查出来的,不是推断):① chip 改版全程未调 `brainstorming`,也没产出 `writing-plans`(事后补 `docs/superpowers/plans/2026-10-24-chip-waterfall-v4.md`);② 首次用 `./gate.sh` 只跑 2s(全 up-to-date)就报了「通过」,改 `--rerun-tasks` 才拿到真结果;③ 变异 `MF` 因 build cache 报假 SURVIVED,差点当成结论。

## 1. 文档层级(谁在哪记录什么)

| 文件          | 职责                                                                                                                                                                                                                |
| ------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `HANGOFF.md`  | **产品决策唯一权威**:定位、已确认约束、用户工作流 A/B/C、统一领域模型、交互原则、平台策略、技术栈决策(§8)、设计路线(§9)、验收标准(§10)、待确认问题、开发环境实况(§12)、参考文件;文件内标题即目录,细节以对应章节为准 |
| `README.md`   | 项目概览与门面,内容须与 HANGOFF 一致;含开发环境部署节(⚠️ 暂定,只含项目相关步骤)                                                                                                                                     |
| `.mise.toml`  | 项目工具版本声明(**纯配置无注释**,说明见 HANGOFF §12.2);变更后需 `mise install`                                                                                                                                     |
| `scripts/`    | 两个 Python 脚本:现有行为参考、回归样例、命令行备用;**不是最终规范**——GUI 需补全交互并避免静默危险操作                                                                                                              |
| `docs/INDEX.md` | **文档地图**(非产品权威):四层结构 = 产品权威 / 阶段详版 `docs/phases/` / 调研笔记 `docs/notes/` / 规格与计划 `docs/superpowers/`;另含当前状态速览、旧编号 `§9.1「…」` 映射表、维护规则。**新增文档须在此登记一行** |
| `docs/phases/` | **各阶段实现留痕**(`d1-interaction-prototype` / `branch-topology` / `settings-and-theme` / `d2-platform` / `d3-domain-core` / `d4-mvp`)。2026-10-23 自 `HANGOFF.md` §9 原文逐字迁出,**产品决策仍以 HANGOFF 为唯一权威**,此处只是其详版,性质为「只查不常读」 |
| `docs/notes/` | 调研与实证笔记(非产品权威):`d1-archive-domain-semantics.md`(归档原型领域语义+16 项测试意图)、`scripts-behavior-matrix.md`(脚本行为矩阵+13 条危险默认值)、`d1-toolchain-evidence.md`(新栈构建链实证)、`d1-ui-friction-inventory.md`(**操作流程与 UI 摩擦点清单**,用户 2026-09-19 提出"先调基础 UI"后的改动依据;每条标注来源=真机实测/代码核实/走查观察)。每条结论附来源/命令 |
| `docs/superpowers/` | D1 流程产物:`specs/`(设计规格)、`plans/`(分阶段实施计划)。**产品决策仍以 HANGOFF 为唯一权威**,此处只承载实现级规格与计划。现有规格:`2026-09-16-d1-interaction-prototype-design.md`(D1 交互原型)、`2026-09-20-d1-settings-theme-design.md`(**设置与主题体系 · 阶段 1**)。⚠️ **`plans/2026-10-04-d3-p1-packing-core.md` 仍是活跃台账**(同时是实施计划与夜间台账,每轮开工读 §0、收工更新 §3),不是历史文档 |
| `android/` | **Android 工程**(2026-10-18 由 `pixfold-d1/` 改名;包名 `com.cyanix.pixfold`)。**在阶段分支/dev 上**,不进 `ready`/`main`;`domain/` 纯 Kotlin JVM + `app/` Compose。拓扑见 HANGOFF §9.2 |
| 归档分支      | `archive-flutter-verify`(**已推远程**;本地无同名分支):已删除的 `pixfold-d1` / `pixfold-saf-spike` 整树快照;新栈可参考其中 Kotlin SAF 实现。详见 HANGOFF §12.7                                                       |

**变更路由(维护规则)**:领域术语、决策、约束变更 → **先回写 `HANGOFF.md` 对应章节(唯一权威)**,不在别处另起炉灶;凡改变“当前阶段/状态”,随后同步本文件头部快照与 `README.md` 状态/路线,保持三处一致、不得只改一处。环境/工具类变动回写 `HANGOFF.md` §12。原 `s_handoff.md` 已于 2026-09-07 并入 §12 后删除,勿再新建同类文件。

**反方向同样成立:历史不进头部。** 每轮收工写文档时,「这一轮做了什么」写进 `docs/INDEX.md` 的历轮台账或 `docs/phases/`,**不写进本文件头部**;头部只允许「当前阶段一句话 / 换机续接点 / 指针」三类。2026-10-23 与 2026-10-25 两次迁出都是同一个病 — 平时一句「顺手记一下」最省事,攒够一年就是 22 KB。

## 2. 不可违背的产品约束

1. **扫描、决策、执行分离**:扫描/预览阶段不改文件;执行前必须给出完整计划供用户确认。
2. **建议 ≠ 事实**:自动识别的排序、名称、卷号、语言必须可见、可改。
3. **预览是主工作区,不是日志**。
4. **批次统一 + 逐项例外**:批量设置后允许单项覆盖。
5. **破坏性动作可解释**:默认保留源文件;删除必须独立确认并列出影响范围。
6. **Android SAF 优先验证**:目录授权、遍历、读写、缩略图、后台任务。
7. **UI 符合最新 Material Design**(§6.5):用 material3 语义角色(颜色/排版/形状),不硬编码;
   支持深色模式;Android 12+ 动态取色;触控 ≥48dp;纯图标按钮给 `contentDescription`。
   **当前基准 = material3 1.4.0(stable)**;M3E 的公开 API 只在 1.5.0-alpha28,不在本阶段范围
   (P7 走查后评估,详见 §6.5 与 `docs/notes/d1-toolchain-evidence.md` §7)。

## 3. 领域词汇

设计/实现讨论一律用 `HANGOFF.md` §5 统一领域模型中的词。发现术语冲突或缺口时:**先与用户确认,再回写 HANGOFF**,不要自行造词或悄悄换说法。`HANGOFF.md` 是领域主场;引入独立 CONTEXT.md / ADR(如经 domain-modeling skill)须用户同意。

## 4. 当前阶段:该做 / 不该做

**该做**(路线与执行顺序以 HANGOFF §8.2 / §8.3 / §9 为准;已完成项细节与 D2 spike 的 Kotlin channel 参考实现均以 HANGOFF §8.3 为准,需要时再读,不在此重复):

- 已锁定 **Kotlin + Jetpack Compose(Android 原生)**(§8.1,2026-09-10 重审,替换 Flutter + Dart);**GUI 只做 Android**(§2/§7);换栈触发 = §8.2 证伪条件 ③(**已于 2026-09-10 触发**:桌面端信息呈现未达 D1 预期);正式工程创建的触发 = D1 退出条件达成(§9 决策门);**D1 原型需在新栈重建**,但验收清单 20 项与走查结论(实现无关的交互契约)继续有效;
- **验证工程实况(2026-09-13;归档位置 2026-09-16 复检)**:`pixfold-d1` / `pixfold-saf-spike` 两个工程**目录已删除,`ready` 分支从未包含过它们**;内容在分支 `archive-flutter-verify`(**已推远程**;本地无同名分支,取用走 `origin/archive-flutter-verify`)。**要参考 SAF 的 Kotlin 实现或旧原型领域层语义时,去归档分支取(`git show 9efef5b:<路径>`),不要按文档里的 `C:\Personal\...` 旧路径找文件**;新栈重建时不迁移其代码;
- D1 交互原型(当前主线):范围 = 缩略图/拖拽排序、命名结构编辑、CBZ 元数据编辑、执行计划预览,可不连真实文件系统;**完成判据 = HANGOFF §9《D1 验收清单》20 项在 Android 真机全部勾满(§9 门槛表已把该清单定为 D1 退出条件);判据未满足前不得宣称 D1 完成**;清单已于 2026-09-10 由双列收敛为 **Android 单列**(条目未改);
- 下一阶段 D2b(完整平台能力,见 §9):缩略图生成策略 / 后台任务与进度 / 授权失效恢复;D2a(SAF 闭环)已于 2026-09-08 提前验收,不再重复;
- 流程约定(**修复自证三层**,§9 D1):凡交互或数据改动,须依次过"领域单测 → UI 层断言 → 真机实证(埋点 + 模拟输入 + 像素/快照对比)",不得只凭"测试通过"交付;**每轮任务的固定步骤与真机核对规约见 §7(含授权、取证、收尾与已知坑)**;
- 环境与工具维护:版本声明经 `.mise.toml`(项目);全局工具声明不属本仓库管辖(见 §12.2);**环境已于 2026-09-16 复检**(§12 顶部横幅):`platforms;android-36` / cmdline-tools 23.0.0 均已就位,**Kotlin/Gradle 侧已在 `temurin-17` 下完整实证**(探针工程:assembleDebug + 纯 JVM 单测 + Robolectric UI 测试 + lint 全绿,证据 `docs/notes/d1-toolchain-evidence.md`;原"待 D1 确认"项已关闭),曾遮蔽 SDK 的 system32 残留 adb 已清除(§12.5);`.mise.toml` 现仅 `java = "temurin-17"`(已去掉 `flutter` 声明)。

**不该做**:

- **不另起新的"正式工程"(2026-09-21 重述)**:D1 退出条件已达成,且用户确认
  **`android/` 的 UI 即 D4 MVP 的基座**(见 HANGOFF §9 D1「复用关系」)——
  MVP 能力**就地演进**,不再新建工程、也不推倒重写。发版仍按 §9.2 拓扑(`dev` → `main`);
- 不把 `scripts/` 现有行为当作最终规范照搬;
- 不用 `mise use -g` 改全局工具声明(绕过 chezmoi 造成漂移);
- 不改 D1 验收清单的判据以"迁就现状"(清单是 D1 的唯一出口,条目变更须用户确认)。

## 5. 脚本使用

- Python ≥3.10,PEP 723 内联依赖(metadata 在脚本头部 `# ///` 块);用 `uv run scripts/<名>.py …` 运行。
- 脚本含交互输入与真实文件操作:**运行前先向用户说明影响范围**;能 dry-run 就先 dry-run。

## 6. 其他约定

- 语言:与用户用中文交流;仓库文档双语(README)或中文(HANGOFF),新增/修改匹配所在文件的语言风格。
- Git:本仓库独立于 chezmoi dotfiles 仓库;忽略项现状为 **`.agents/`**（用户 2026-09-19 移除 `.kilo/`、`.workbuddy*/` —— 两者已不再使用）。
- **分支模型(2026-09-16 起)**:`ready`(设计文档线,原型代码永不进)→ **`dev`(开发主线)** → 阶段分支 `d1/pN`(D4 起为 `d4/…`)。阶段分支从 `dev` 开、**完成即合回 `dev` 并回写文档**、随即删除;D1 全绿后 `dev` → `main` 发 release,此后开发继续在 `dev`。**push 由用户自行处理**,agent 只提交不推送。详见 HANGOFF §9.2。**开分支是硬前置,判据与出口见 §0.1 第 4 条。**
- 验收:任何改动对照 `HANGOFF.md` §10(功能 / 数据正确性 / 平台)自检。

## 7. 每轮任务的固定步骤与真机核对规约

> 本节把已反复踩过的坑固化为步骤。**用户可当回合覆盖本节**;未覆盖时按此执行,不得跳步。

### 7.1 固定步骤(顺序不可调换)

> **进入下面七步之前,先过 §0** —— skill 匹配是第一步的前置,不是第零个可选步骤。条文与六个可自查锚点都在 §0,本节不重复。第 1 步动手前先过 §0.1 第 4 条(开分支);收工前过第 5、6 条(子 agent 收口、派发纪律、以及用户当回合要求的收尾动作 —— 关机命令必须真执行且挂进任务面板最后一项)。

1. **对齐依据**:读 `HANGOFF.md` 对应条目(产品决策唯一权威)+ `docs/notes/d1-ui-friction-inventory.md` + 用户当回合指令;**有歧义先请用户裁决**,把裁决项写下来再动手。
2. **红 → 绿**:先写会失败的断言,再改码;跑四道闸门(见 7.2)。
3. **变异验证**:证明断言有杀伤力(见 7.4)。**派给子 agent 时,它的 commit 必须早于变异**(§0.1 第 6 条 ⑤)—— 本会话自己做的变异不受此限,因为「变异不足 → 补断言 → 重跑」本来就要在提交前完成。
4. **真机核对**:必须**先获用户授权**(见 7.3)。
5. **回写文档**:`HANGOFF.md` → 同步本文件头部快照 + `README.md`(变更路由三处一致)。
6. **展示改动集 + 主动提醒提交**:列出文件与行数,然后**每次都主动问一句**:「这些改动现在提交,还是继续下一个需求一起提交?」—— 判断权在用户,不在我。**不许自己替他判「改动小/是中间态/后面还要动同一片代码,先攒着吧」**,那是把「等待裁决」偷换成「我已经决定攒着」。依据 `User said (m03873)`:「每次完成任务提醒我提交,我来决定是直接继续新需求写完一起提交还是先提交再继续」。注意这条**不豁免**下方「不得在真机核对前宣布完成」:提醒提交的前提是四道闸门绿 + 该真机的部分已真机核对,前置没做完时该说的是「还差什么」,不是「要不要提交」。
7. **提交**(只提交不推送)→ **推送前**跑密钥扫描(见 §7.2 末;**必须在提交之后** —— 该扫描读的是 git 对象,提交前跑会漏掉正要提交的内容)→ **合回 `dev`** → 再补一次"提交号 + 合并结果"的文档同步提交。

⚠️ **任务面板纪律(与上列 7 步并行贯穿全程):开工建全量清单,每步完成立即标记,不得攒批** —— 条文与理由见全局 `~/.dsh/AGENTS.md` §0(含 `todo_write` 的挂载来源、`executing-plans` 依据、以及「面板是压缩后唯一能自证进度的载体」的原因),**此处不复述以免两处漂移**。**留一条本项目实测先例**:第 18 条一轮 7 项**开工时只写了一次、全程未再更新**,直到用户指出(`User said (m16667)`:「你又忘了推进任务进度」)才一次性全勾掉;性质是**执行过失而非规约缺口**。

⚠️ **同一过失反复发生，累计五次(内容相同无需重读)**：② `User said (m21345)`「你又忘了实时跟进人物面板」；③ `User said (m22486)`「你都到变异测试了任务面板却仍然没有实时跟进」；④ `User said (m22601)`「**你已经好几次没有实时更新任务面板了，全局AGENTS.md的约束还不够吗**」；⑤ `User said (m23631)`「菜单宽度这个没有更加优雅的写法吗，另外你好像又又又又又忘了更新任务面板进度」(「又」×5 ⇒ 用户已用字数计数)。⇒ 五次均为**执行过失**。现行硬规则：**任何一步完成后的第一个工具调用必须是 `todo_write`**，然后才允许启动下一步命令（可检查：面板更新时间戳必须早于下一个 bash/read）。

⚠️ **不得在真机核对前宣布完成、不得先催提交**:用户已明确纠正 —— `User said (m13805)`「你经常嘴上说完成要提交却没有给我真机校验先，而且任务进度会卡着」。**正确顺序是「真机核对 → 回写文档 → 展示改动集 → 提交」**。

⚠️ **但反过来也不行:前置绿了必须主动问提交,不许闷着。** 上面那条禁的是「拿提交当完成宣告、跳过真机」,**不是「别提提交」**。2026-10-25 用户明确要求每轮完成主动提醒(`User said (m03873)`),因为「现在提交还是攒着一起提交」是**他的调度决策** —— 我不问,他就得自己盯回来催。两条合起来的判据:**前置没绿 ⇒ 报「还差什么」;前置全绿 ⇒ 报改动集 + 问「现在提交还是攒着」。**

### 7.2 四道闸门(Gradle 必须在 Windows 侧跑)

```bash
cd android && ./gate.sh
```

`gate.sh`(2026-10-21 新建)内部就是一次 gradle 调用:

```
run-gradle.bat :app:lintDebug :app:testDebugUnitTest :domain:test :app:assembleDebug
```

**为什么必须经 `gate.sh`**:退出码。以前写 `cmd.exe /c "run-gradle.bat …" | tail -20`,
管道的 RC 来自 `tail` 而非 gradle ⇒ **构建失败也报成功**(实测:故意漏一个 `}`,
gradle `BUILD FAILED` 但 RC=0)。`gate.sh` 把输出重定向进 `build/gate-last.log`、
直接取 gradle 退出码、失败时打出行号。**不要在它外面再套 `| tail`** ——
`tail` 等 EOF 而 `run-gradle.bat` 的句柄不关,会导致命令早已结束、作业一直报 running。

⚠️ **build cache 开启后 JUnit XML 的 mtime 不再能证明「本次跑过」**(命中缓存不重写 XML)。
判闸门是否真跑,看 `build/gate-last.log`:出现 `BUILD SUCCESSFUL` 且相关 task 行不是
`UP-TO-DATE`/`FROM-CACHE`。

- **绝不在 WSL 里跑 Gradle**;cmd.exe 下**禁用 `--tests`**(会静默假绿 0 测试)。
- **测试数只认 JUnit XML**:`android/app/build/test-results/testDebugUnitTest/TEST-*.xml`、`android/domain/build/test-results/test/TEST-*.xml`(用 Python 解析;本机无 `bc`)。
- `app` 的 `@Test` 总数比 XML 多 1 是**正常的** —— `NegativeControlTest` 是故意失败的负向对照,默认排除,仅 `-PincludeNegativeControl` 时运行。
- **lint 必须零告警**:`warningsAsErrors = true` + `abortOnError = true`,一条 warning 即硬失败。新增资源/权限/API 后尤其容易多出 `ObsoleteSdkInt`、`UnusedQuantity`、`ExtraTranslation`。

**提交后、推送前另跑一道密钥扫描**(WSL 侧;**不是 Gradle target,故不并入上面的命令**;放在 §7.1 第 7 步):

```
betterleaks git . --redact --revision="origin/dev..dev" --include=merges --include=commit-messages
```

- ⚠️ **参数随 betterleaks 2.0.0-rc.3 变了**:该版本改走 go-git,`--log-opts` **已被移除**,
  限定范围改用 **`--revision`**(`A..B`);彻底口径(含合并提交)改用 **`--include=merges`**。
  用旧参数会直接报 unknown flag,不要照抄旧笔记。
- **必须在提交之后跑**:该命令读的是 **git 对象**,工作树里未提交的改动**不在扫描范围内** —— 提交前跑会恰好漏掉正要提交的内容。要扫工作树须用 `betterleaks filesystem <path>`。
- 只扫**未推送**部分(已推送的无法追回,扫了也没用;`origin/dev..dev` 随推送自动收敛)。
- **判绿口径见全局 `~/.dsh/AGENTS.md` §4**:真扫描必有 `INF scanned ~NNNN bytes`(百万级)且退出码 0;见到 `scanned ~0 bytes` + `incomplete scan` 一律当**失败**,不许当绿。
- `--redact` **不可省**(否则命中值明文落盘);回报只给 `rule_id`/路径/行号,不复述 `match.value`。
- 为什么**不改名「五道闸门」**:`四道闸门`是项目高频固定术语(`HANGOFF.md` 16 处、`AGENTS.md` 6 处、`README.md` 8 处、`docs/notes/` 25 处),且 HANGOFF 那 16 处全是**历轮历史记录**(「P4 收口跑四道闸门」),改名等于伪造历史;四道闸门 = 一次 Gradle 调用的四个 target,密钥扫描是独立命令、语义上属 §7.1 第 7 步「提交」。

### 7.3 真机核对规约(三层自证的第三层)

**授权**:动手机前必须先通知用户并取得明确授权;**每轮单独授权,不继承上一轮**。用户未授权时可先把前两层做完并汇报,不要自行连设备。

**设备与环境**(固定):无线调试 `adb connect 192.168.43.1:4444`;PJX110 / Android 16 / `1264×2780px` / `560dpi` = **361dp × 794dp**;系统 locale `zh-CN`。
- 唤醒常亮:`input keyevent KEYCODE_WAKEUP` → `input keyevent 82`(解锁)→ `svc power stayon true`。
- 安装:`adb install -r android/app/build/outputs/apk/debug/app-debug.apk`。
- 启动:`adb shell monkey -p com.cyanix.pixfold -c android.intent.category.LAUNCHER 1`(⚠️ `input keyevent 4` 可能直接退出应用,不是回首页)。

**取证**:`android/dumpui.bat NAME` → dump 到 `/data/local/tmp/NAME.xml` → 落入 `android/artifacts/`(已 gitignored)。**读节点 `bounds` 坐标是唯一能证伪"控件放不下 / 落在视口外"的场合**;`uiautomator dump` 的坐标会过期,**每次 tap 前重新 dump**。

**已知坑(必须遵守)**:
- **边缘 x 不可用于应用内滑动**:`input swipe 1200 …` 的 x=1200 落在手势导航边缘区 → 被系统当成返回手势、**直接退出应用**;应用内滑动用 **x ≤ 1000**。
- **中文 IME 下 `adb shell input text` 抛异常**:`NullPointerException: Attempt to get length of null array`(`InputShellCommand.sendText:410`)⇒ **只能输入 ASCII**;且键盘弹出会把页面整体上移,**点按钮前必须先收键盘**(ESC = `input keyevent 111`),否则坐标落在 IME 上。
- `input swipe` 触发不了长按 → 用 `input draganddrop`。
- `cmd.exe /c "adb.bat shell ls … | grep …"` 的管道**会被 cmd.exe 吞掉** → 管道放到 WSL 侧执行。
- **调试产物绝不写 `/sdcard` / `/storage/emulated`**(`adb.bat` 已有守卫);只走 `/data/local/tmp`。
- 汇报要给**读数**(坐标/尺寸/数值),不能只说"已通过"。

**收尾(每次必做)**:清理 `/data/local/tmp` 内的 `.xml`/`.png`(计数须为 0)、`svc power stayon false` 复位、`am force-stop` 退出应用。

### 7.4 纪律:哪类结论必须真机,哪类可以离线

**Robolectric 不做真实文本测量与真实布局**:芯片宽度一律算成 ~0(实测 trip 34.9dp / scan 34.6 / misc 34.3 / cn 34.3)。因此:

| 结论类型 | 能否离线断 | 说明 |
| --- | --- | --- |
| 领域逻辑(排序/命名/元数据) | ✅ 领域单测 | 第一层 |
| 结构/接线(节点存在、点击推进、按钮左右槽位) | ✅ UI 断言 | `getBoundsInRoot()` 能反映 `graphicsLayer` 缩放 |
| **"放得下"、"是否挤出视口"** | ❌ **只能真机** | 删掉 `horizontalScroll` 后 312 项全绿 = 纯假保障 |
| **视觉规格(底色、高度、危险红)** | ⚠️ 像素断言 + 真机 | 需 `@GraphicsMode(NATIVE)` + `captureToImage()`;LEGACY 后端画不出真实颜色 |
| **动画观感/是否生硬** | ❌ **只能真机(用户判定)** | 用户观感判定才算定稿 |

⚠️ **变异能存活就说明断言没有杀伤力 —— 删掉它,并写明为什么刻意不设这条断言**;留着只会给假保障。
⚠️ **变异纪律**:先用 assert 确认旧串真的消失、再解读结果(否则"存活"是假象);`/tmp` 不跨 bash 调用保留,故**备份+变异+还原必须在同一次调用内完成**;还原**一律用 `cp` 备份,绝不用 `git checkout --`**(会连未提交改动一起丢掉)。
