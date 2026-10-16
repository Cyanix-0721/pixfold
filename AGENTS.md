# AGENTS.md — PixFold(项目级)

> PixFold:图片整理与 CBZ 制作工作台(图形应用),**GUI 只做 Android**(2026-09-10 起;Windows / Linux 端只做 CLI),技术栈 **Kotlin + Jetpack Compose(Android 原生)**(§8.1;换栈触发 = §8.2 证伪条件 ③)。
>
> **当前阶段(2026-10-16)**:设计期。**D1 交互原型已交付** —— P1–P7 全部交付,**验收清单 20/20 通过**,D1 退出条件(§9 决策门)已达成;D2a SAF spike 5/5 通过。**D1 收尾轮已完成(2026-10-14/15)**:用户走查提出的 5 条 UI 调整(首页两张大卡「整理图片」/「打包漫画」+ 副标题 + 描边 / 纯黑模式下卡片边界不可见 / 设置页假信息「多语言将在后续版本补全」/ Tab 行右侧「重置全部」纯图标按钮 + 长按说明 + 二次确认 + 回步骤 1(A/B 同构)/ 回主页触发重置,工作流内部切步骤不重置)+ 1 个真实缺陷(**连点底栏左下角 ⇒ 嵌套栈清空且回不来**,加 `popWorkflowStep()` 护栏,4 处接线)+ **拖拽语义改判**(「松手落地」→「**实时让位**」,**推翻 HANGOFF §11 第 9 条**;界面实时变、数据仍只在松手时提交一次)+ 图标库评估(**结论:不引入依赖**,继续 `artifacts/svg2vd.py`)。四道闸门全绿(`app 476` / `domain 483` / `lint` 零告警 / APK 34,109,166 B),变异 M1–M17;**真机核对已通过(用户亲测)**。**D1 收尾轮 · 第二轮已完成(2026-10-15/16)**:补上首轮遗留的**拖拽让位过渡动效**(`ui/components/DragReorderAnimation.kt` 的 `DragReorderPlacementSpec` = `spring(DampingRatioNoBouncy, StiffnessHigh, visibilityThreshold = IntOffset.VisibilityThreshold)`,经 `Modifier.animateItem(fadeInSpec = null, placementSpec = …, fadeOutSpec = null)` **只在拖动期间**挂载);真机复测用户反馈「动画有点慢,而且拖拽到边缘的时候会抽搐」⇒ ① `StiffnessMedium`(1500) → **`StiffnessHigh`(10000)**(连续重启下稳态追踪滞后 196.4px ≈2.73 格 → **54.7px ≈0.76 格**);② **抽搐根因 = 命中几何被动画污染**(`slotBounds` 原由各 item 的 `onGloballyPositioned` 累积,而 `animateItem` 的位移施加在 item 自身 `graphicsLayer` 上 ⇒ 读到**飞行途中**的矩形 ⇒ 反复换位 ⇒ 动画反复重启 ⇒ 自我维持抖动)⇒ 抽出纯函数 `layoutSlotBounds(...)` 每次从 `gridState.layoutInfo` **现算逻辑几何**,与动画解耦。四道闸门全绿(`app 482` / `domain 483` / `lint` 零告警 / APK 34,109,166 B);**真机核对已通过(用户逐字「效果可以」)**。**教训**:凡「读界面几何做命中判定」处都要问「这个读数会不会被动画污染」。**新增产品决策**:「**工作区选取与入口分层**」(HANGOFF §4)——首页不加第三个入口,工作区选取长在工作流内部步骤 0,外部分享走 Activity 入口不经首页,首页将来只加**从属层**显示。**阶段复盘**落 `docs/notes/d1-review.md`。**D3 领域核心与适配层已交付**(2026-10-05;三项退出条件逐条取证见 HANGOFF §9 D3 节):打包核心(CBZ 生成/写入计划、执行报告、撤销记录)落地并单测通过;适配层边界有**可执行检查**(`domain/src/test/.../boundary/AdapterBoundaryTest.kt`,四层断言 + 自检,**经真实注入变异验证四层皆可红**、0 逃逸,并因此查出一处假绿并修掉);`domain/` 单测基线连续(全分支零测试文件删除,现 **483 项**全绿)。**`pixfold-d1/` 已由「原型」升级为 D4 MVP 的代码基座**(用户确认 D1 的 UI 即 MVP 基座)—— 能力**就地演进,不再新建工程、不推倒重写**;设置页/主题体系/全量 i18n 等属 **D4 提前启动**。D4 = **D4a(UI 层真实功能,进行中)/ D4b(真实文件闭环,依赖 D2b + D3)**;**D3 已不再是 D4b 的阻塞项**,剩余的 D4b 前置只有 **D2b**;门槛表按依赖并行推进。**真机验证层级**:阶段分支共 **5 轮**真机核对(轮 23 覆盖度审计:`WARN_ICONS` 中文覆盖 8/11 → **11/11**;轮 25 O1 撤销范围对话框两档视觉分级**逐位复核** —— 浅色安全档透明底 + `#B3261E` 描边 vs 危险档 `#B3261E` 填充、深色安全档透明底 + `#F2B8B5` 描边 vs 危险档 `#8C1D18` 填充);**打包核心仍未端到端**(原型不连真实文件系统,属 **D4b**)。**D2b 领域层部分完成(2026-10-12)**:存储空间预检(`SpaceCheck.kt`,22 tests)+ Zip64 需求判定与中断半成品识别(`Zip64Assessment.kt`,22 tests)已落地,平台层接线与真机验证仍待。下一步 = **D2b 剩余三项(缩略图生成策略 / 后台任务与进度 / 授权失效恢复)+ 已完成两项的平台层接线**。
>
> **⚠️ 换机续接点(2026-09-27 订正)**:当前工作分支 = **`dev`**;**已推送的远端基线是 `origin/dev`**,本地 `dev` 领先若干提交且**均未推送**(确切数目查 `git rev-list --count origin/dev..dev`,**本处不硬编码以免每次提交后失效**)。曾经的阶段分支 `d1/settings-impl` 已合回 `dev`(merge `7a6f678`)并删除本地分支。分支拓扑 `ready` → **`dev`** → 阶段分支 `d1/pN`(完成即合回 `dev` 并回写文档);**D1 全绿后 `dev` → `main` 发 release** —— 详见 §6 与 HANGOFF §9.1。**push 由用户处理,agent 只提交不推送。**
>
> **本文件头部只做「当前阶段 + 历轮索引」,不再逐轮写详版记录**(原因:详版曾使本文件达 66.8 KB,**超出 65536 字节工作区指令预算 ⇒ 每次加载都被系统截断**,§7.3 已知坑列表尾与 §7.4 表格因此不可见)。**每轮的唯一权威记录在各处文档里**,索引见下。
>
> **历轮记录索引**(一行一轮;日期 = 实际提交日):
>
> | 日期 | 轮次 | 提交 | 详情位置 |
> | --- | --- | --- | --- |
> | 2026-09-21 | 补 `2021≠2025` 断言 + 工作流内部预测性返回 | — | HANGOFF §9.1「后续三步」 |
> | 2026-09-22 | 导航迁移 Navigation Compose + 分层返回转场(**决策与 F1–F13 源码级依据**) | `33c0507`、`1559289` | `docs/notes/d1-navigation-compose-migration.md` |
> | 2026-09-24 | **启动图标 MD 主题化 + minSdk 24→30**;并核实 **16 KB 页大小已达标且零配置**(`zipalign -c -P 16` 为准,不可按 ZIP 结构自算) | `2ba10f8` | HANGOFF §9.1「启动图标」+ `docs/notes/d1-toolchain-evidence.md` §9.7/§10 |
> | 2026-09-24 | **P0 命名页头部折叠**(固定区 692dp→154dp、首屏建议行 0→5 条;含 LazyList 前插锚定陷阱) | `55685a1` | `docs/notes/d1-ui-friction-inventory.md` §1 |
> | 2026-09-25 | **P1 底栏化**(5 个列表内动作全移到底栏)+ **底栏左右分槽 + 步骤1 头部伸缩** + **底栏视觉规格**(降高/返回类底色/撤销危险红) | `1c95db9` | `docs/notes/d1-ui-friction-inventory.md` §2 / §2b / §2d |
> | 2026-09-25 | **minSdk 30 → 34**(获 `LocaleManager` + HDR gainmap) | `f3cfb08` | HANGOFF §12.6 |
> | 2026-09-25 | **应用内语言不同步修复**(空表 = 重置系统语言 ⇒ 判据必须是「变化」而非「值」;含 `AppLanguageTest` 3 守卫 + 3 轮变异) | `669f1ed` | HANGOFF §9.1「设置与主题体系」第 4/5 条 |
> | 2026-09-25 | **全量 i18n**(领域层文案改「文案键 + 参数」;`values/` 装英文、中文入 `values-zh/`) | `2221ef1`、`0368be5`、`53149ba` | HANGOFF §9.1「全量 i18n」 |
> | 2026-09-25 | **§11 第 13/15 条**(中文数字自然排序 + 批量不再是逐项例外)+ 芯片行溢出(7 处同源) | `27f3a13` | HANGOFF §9.1「§11 第 13/15 条」+ 摩擦清单 §12 |
> | 2026-09-25 | **§11 第 9/10/14 条**(拖拽跟手浮层 + 命名摘要披露手工调整) | `c712685` | HANGOFF §9.1「§11 第 9/10/14 条」 |
> | 2026-09-26 | **§11 第 18 条**(拖拽跨视口边缘滚动) | `c712685` | HANGOFF §9.1「§11 第 18 条」+ 摩擦清单 §13/§14 |
> | 2026-09-26 | **预览取错图 + 预览往返丢失所选集合 + 预览页改名入口**(三处真实缺陷) | `4953f7a` | HANGOFF §9.1「预览取错图…」+ 摩擦清单 §15 |
> | 2026-09-26 | **「仅问题/全部」筛选推广到计划页/报告页**(抽共享 `IssueFilterRow`；筛完保原始行号)+ **死代码 `ThumbCard.kt` 清理**(常量迁入 `DraggableThumbCard.kt`) | `e35ab9c` | HANGOFF §9.1「筛选推广」+ 摩擦清单 §3 |
> | 2026-09-27 | **五项交互修订**(排序页编辑器并入滚动容器(含前插锚定陷阱)/ 图钉统一 32dp(**订正**「32dp 低于 48dp 硬指标」的错误结论——触控框实为 48dp)/ 去 `SegmentedButton` 对勾 / 集合芯片改半角括号 / 新增列数控件(自适应默认)) | `e93d759` | HANGOFF §9.1「五项交互修订」+ 摩擦清单 §16 |
> | 2026-09-27 | **列数控件第二轮**(模式行内纯图标下拉 2–5 / 去掉「自适应」项 / 新增竖屏与横屏两个默认列数设置(默认「跟随自适应」) / 调窄 `ADAPTIVE_COLUMN_MIN_WIDTH` 110→80dp 使 361dp = **4 列**;⚠️ **当时结论「不设 `selected` 语义⇒高亮只能用 `leadingIcon`」已被同日第三轮推翻**,见下行) | `f6dfaa9` | HANGOFF §9.1「列数控件第二轮」+ 摩擦清单 §16b |
> | 2026-09-27 | **列数控件第三/四/五轮**(同日连走三轮:③ 下拉「当前档位」由 `leadingIcon` 对勾改为**整行背景填充** + 显式 `selected` 语义,根因 = 对勾让两侧宽度不等(现四行等宽、数字全 `x=529`),官方色角色依据 = `MenuTokens.ListItemSelectedContainerColor`,删 `ic_check.xml`;④ 菜单行宽收窄 **48dp**,宽度**只给在容器一处** `DropdownMenu(modifier = …width(COLUMN_MENU_WIDTH))`(依据 = 条目侧 `sizeIn(112.dp)` 与内层 `width(IntrinsicSize.Max)` 均 `enforceIncoming = true`,上游单点设宽即全局收窄);⑤ 数字**居中**,M3 text 槽位是无对齐参数的 `Box(weight(1f))` ⇒ 只能 `Text` 铺满槽位 + `TextAlign.Center`。**同日并把 Gradle wrapper 9.7.1 → 9.8.0 一并落地**(lint 由 1 error 转全绿)) | `3ca85b0` | HANGOFF §9.1「列数控件第三轮」+ 摩擦清单 §16c(含 §16c.9/§16c.10) |
> | 2026-10-05 | **D3 打包核心与适配层**(工作面 W1–W24,分支 `d3/p1` 已完成并合回 `dev`;含 6 项待裁决 R1–R5 + i18n 的落地、警示 emoji 全面矢量化、**适配层边界可执行检查**`AdapterBoundaryTest`) | 见 `docs/superpowers/plans/2026-10-04-d3-p1-packing-core.md` §3.1 | HANGOFF §9 D3 节 |
> | 2026-10-12 | **夜间离线推进(7 项全完成)**:A1 四道闸门复跑(939 tests 全绿)/ A2 变异审计(四层+11 组已验证)/ A3 lint hints 修复(`mutableStateOf`→`mutableFloatStateOf`/`mutableIntStateOf`,4 处)+ 依赖版本检查 / A4 文档一致性扫描(三处快照一致,§11 9/18 已裁决) / B1 D2b 存储空间预检领域层(`SpaceCheck.kt`,22 tests,3 变异) / B2 D2b Zip64 需求判定与中断半成品识别领域层(`Zip64Assessment.kt`,22 tests,3 变异) / B3 AboutLibraries 开源许可页(已实现验证,无需新代码)。分支 `d2b/p1` 合回 `dev` 并删除 | `1670ee7`/`00b47ea`/`58bdac4`/`27450ba` | HANGOFF §9 D2b 节 |
> | 2026-10-15 | **D1 收尾轮(5 条 UI 调整 + 1 缺陷 + 拖拽语义改判 + 图标库评估)**:① 首页两张大卡「整理图片」/「打包漫画」+ 副标题 + `outlineVariant` 描边(纯黑模式下卡片与背景同色、完全不可见是②的真机缺陷);③ 删设置页假信息「多语言将在后续版本补全」;④ Tab 行右侧「重置全部」纯图标按钮(`ResetAllButton.kt` + Material Symbols 官方 SVG 经 `artifacts/svg2vd.py` 机器变换)+ 长按 tooltip + 二次确认 + 回步骤 1(A/B 同构);⑤ 回主页触发重置、工作流内部切步骤不重置(`composable<HomeRoute>` 内 `LaunchedEffect`);缺陷 = 连点底栏左下角 ⇒ 嵌套栈清空且回不来(加 `nav/StepNavigation.kt` 的 `popWorkflowStep()` 护栏,4 处接线);**拖拽语义改判**「松手落地」→「实时让位」(推翻 §11 第 9 条;界面实时变、数据只在松手时提交一次,`resolveReflowTarget` 纯判据);⑦ 图标库评估结论 = **不引入依赖**;新增产品决策「**工作区选取与入口分层**」。四道闸门绿(app 476 / domain 483 / lint 零告警 / APK 34,109,166 B),变异 M1–M17(M8 存活查出 `remember` 无 key 的真缺陷);真机核对通过(用户亲测)。**遗留**:拖拽让位缺过渡动效 | `fc663c2`/`ac39f06`/`37f7729`/`1dea313` | HANGOFF §4「工作区选取与入口分层」+ §9「D1 收尾轮」+ §11 第 9 条/§12.5 结论 2 改判;`docs/notes/d1-review.md` |
> | 2026-10-16 | **D1 收尾轮 · 第二轮(拖拽让位动效 + 边缘抽搐修复)**:⑥b 补过渡动效(`DragReorderAnimation.kt` 的 `DragReorderPlacementSpec` + `Modifier.animateItem` **只在拖动期间**挂载);真机复测反馈「动画有点慢,而且拖拽到边缘的时候会抽搐」⇒ ⑥b-1 `StiffnessMedium`→**`StiffnessHigh`**(滞后 196.4px≈2.73 格 → 54.7px≈0.76 格);⑥b-2 **抽搐根因 = 命中几何被动画污染**(`slotBounds` 由 `onGloballyPositioned` 累积 ⇒ 读到飞行中矩形 ⇒ 反复换位 ⇒ 自我维持抖动)⇒ 抽 `layoutSlotBounds(...)` 从 `gridState.layoutInfo` 现算逻辑几何。四道闸门绿(app 482 / domain 483 / lint 零告警 / APK 34,109,166 B),变异 M1–M6;真机核对通过(用户逐字「效果可以」) | `7028c25`/`55a8648` | HANGOFF §9「D1 收尾轮 · 第二轮」+ §11 第 9 条/§12.5 |
>
> ⚠️ **写头部时的两条纪律**(踩过):①**详版会超预算被截断** —— 只留「阶段/续接点/索引」,细节一律落到上表指向的文档;②**不硬编码提交数**(每次提交后即失效)。
>
> 本文件优先级低于用户当回合指令,高于全局 AGENTS.md;技术栈/流程/环境类全局约定见全局文件。

## 1. 文档层级(谁在哪记录什么)

| 文件          | 职责                                                                                                                                                                                                                |
| ------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `HANGOFF.md`  | **产品决策唯一权威**:定位、已确认约束、用户工作流 A/B/C、统一领域模型、交互原则、平台策略、技术栈决策(§8)、设计路线(§9)、验收标准(§10)、待确认问题、开发环境实况(§12)、参考文件;文件内标题即目录,细节以对应章节为准 |
| `README.md`   | 项目概览与门面,内容须与 HANGOFF 一致;含开发环境部署节(⚠️ 暂定,只含项目相关步骤)                                                                                                                                     |
| `.mise.toml`  | 项目工具版本声明(**纯配置无注释**,说明见 HANGOFF §12.2);变更后需 `mise install`                                                                                                                                     |
| `scripts/`    | 两个 Python 脚本:现有行为参考、回归样例、命令行备用;**不是最终规范**——GUI 需补全交互并避免静默危险操作                                                                                                              |
| `docs/notes/` | 调研与实证笔记(非产品权威):`d1-archive-domain-semantics.md`(归档原型领域语义+16 项测试意图)、`scripts-behavior-matrix.md`(脚本行为矩阵+13 条危险默认值)、`d1-toolchain-evidence.md`(新栈构建链实证)、`d1-ui-friction-inventory.md`(**操作流程与 UI 摩擦点清单**,用户 2026-09-19 提出"先调基础 UI"后的改动依据;每条标注来源=真机实测/代码核实/走查观察)。每条结论附来源/命令 |
| `docs/superpowers/` | D1 流程产物:`specs/`(设计规格)、`plans/`(分阶段实施计划)。**产品决策仍以 HANGOFF 为唯一权威**,此处只承载实现级规格与计划。现有规格:`2026-09-16-d1-interaction-prototype-design.md`(D1 交互原型)、`2026-09-20-d1-settings-theme-design.md`(**设置与主题体系 · 阶段 1**) |
| `pixfold-d1/` | D1 原型工程(**在阶段分支/dev 上**,不进 `ready`/`main`);`domain/` 纯 Kotlin JVM + `app/` Compose。拓扑见 HANGOFF §9.1 |
| 归档分支      | `archive-flutter-verify`(**已推远程**;本地无同名分支):已删除的 `pixfold-d1` / `pixfold-saf-spike` 整树快照;新栈可参考其中 Kotlin SAF 实现。详见 HANGOFF §12.7                                                       |

**变更路由(维护规则)**:领域术语、决策、约束变更 → **先回写 `HANGOFF.md` 对应章节(唯一权威)**,不在别处另起炉灶;凡改变“当前阶段/状态”,随后同步本文件头部快照与 `README.md` 状态/路线,保持三处一致、不得只改一处。环境/工具类变动回写 `HANGOFF.md` §12。原 `s_handoff.md` 已于 2026-09-07 并入 §12 后删除,勿再新建同类文件。

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
  **`pixfold-d1/` 的 UI 即 D4 MVP 的基座**(见 HANGOFF §9 D1「复用关系」)——
  MVP 能力**就地演进**,不再新建工程、也不推倒重写。发版仍按 §9.1 拓扑(`dev` → `main`);
- 不把 `scripts/` 现有行为当作最终规范照搬;
- 不用 `mise use -g` 改全局工具声明(绕过 chezmoi 造成漂移);
- 不改 D1 验收清单的判据以"迁就现状"(清单是 D1 的唯一出口,条目变更须用户确认)。

## 5. 脚本使用

- Python ≥3.10,PEP 723 内联依赖(metadata 在脚本头部 `# ///` 块);用 `uv run scripts/<名>.py …` 运行。
- 脚本含交互输入与真实文件操作:**运行前先向用户说明影响范围**;能 dry-run 就先 dry-run。

## 6. 其他约定

- 语言:与用户用中文交流;仓库文档双语(README)或中文(HANGOFF),新增/修改匹配所在文件的语言风格。
- Git:本仓库独立于 chezmoi dotfiles 仓库;忽略项现状为 **`.agents/`**（用户 2026-09-19 移除 `.kilo/`、`.workbuddy*/` —— 两者已不再使用）。
- **分支模型(2026-09-16 起)**:`ready`(设计文档线,原型代码永不进)→ **`dev`(开发主线)** → 阶段分支 `d1/pN`。阶段分支从 `dev` 开、**完成即合回 `dev` 并回写文档**、随即删除;D1 全绿后 `dev` → `main` 发 release,此后开发继续在 `dev`。**push 由用户自行处理**,agent 只提交不推送。详见 HANGOFF §9.1。
- 验收:任何改动对照 `HANGOFF.md` §10(功能 / 数据正确性 / 平台)自检。

## 7. 每轮任务的固定步骤与真机核对规约

> 本节把已反复踩过的坑固化为步骤。**用户可当回合覆盖本节**;未覆盖时按此执行,不得跳步。

### 7.1 固定步骤(顺序不可调换)

1. **对齐依据**:读 `HANGOFF.md` 对应条目(产品决策唯一权威)+ `docs/notes/d1-ui-friction-inventory.md` + 用户当回合指令;**有歧义先请用户裁决**,把裁决项写下来再动手。
2. **红 → 绿**:先写会失败的断言,再改码;跑四道闸门(见 7.2)。
3. **变异验证**:证明断言有杀伤力(见 7.4)。
4. **真机核对**:必须**先获用户授权**(见 7.3)。
5. **回写文档**:`HANGOFF.md` → 同步本文件头部快照 + `README.md`(变更路由三处一致)。
6. **展示改动集等批准**:列出文件与行数,**等用户点头**。
7. **提交**(只提交不推送)→ **合回 `dev`** → 再补一次"提交号 + 合并结果"的文档同步提交。

⚠️ **任务面板纪律(与上列 7 步并行贯穿全程):开工建全量清单,每步完成立即标记,不得攒批** —— 条文与理由见全局 `~/.dsh/AGENTS.md` §0(含 `todo_write` 的挂载来源、`executing-plans` 依据、以及「面板是压缩后唯一能自证进度的载体」的原因),**此处不复述以免两处漂移**。**留一条本项目实测先例**:第 18 条一轮 7 项**开工时只写了一次、全程未再更新**,直到用户指出(`User said (m16667)`:「你又忘了推进任务进度」)才一次性全勾掉;性质是**执行过失而非规约缺口**。

⚠️ **同一过失反复发生，累计五次(内容相同无需重读)**：② `User said (m21345)`「你又忘了实时跟进人物面板」；③ `User said (m22486)`「你都到变异测试了任务面板却仍然没有实时跟进」；④ `User said (m22601)`「**你已经好几次没有实时更新任务面板了，全局AGENTS.md的约束还不够吗**」；⑤ `User said (m23631)`「菜单宽度这个没有更加优雅的写法吗，另外你好像又又又又又忘了更新任务面板进度」(「又」×5 ⇒ 用户已用字数计数)。⇒ 五次均为**执行过失**。现行硬规则：**任何一步完成后的第一个工具调用必须是 `todo_write`**，然后才允许启动下一步命令（可检查：面板更新时间戳必须早于下一个 bash/read）。

⚠️ **不得在真机核对前宣布完成、不得先催提交**:用户已明确纠正 —— `User said (m13805)`「你经常嘴上说完成要提交却没有给我真机校验先，而且任务进度会卡着」。**正确顺序是「真机核对 → 回写文档 → 展示改动集 → 提交」**。

### 7.2 四道闸门(Gradle 必须在 Windows 侧跑)

```
cmd.exe /c "D:\Programing\Personal\pixfold\pixfold-d1\run-gradle.bat :app:lintDebug :app:testDebugUnitTest :domain:test :app:assembleDebug"
```

- **绝不在 WSL 里跑 Gradle**;cmd.exe 下**禁用 `--tests`**(会静默假绿 0 测试)。
- **测试数只认 JUnit XML**:`pixfold-d1/app/build/test-results/testDebugUnitTest/TEST-*.xml`、`pixfold-d1/domain/build/test-results/test/TEST-*.xml`(用 Python 解析;本机无 `bc`)。
- `app` 的 `@Test` 总数比 XML 多 1 是**正常的** —— `NegativeControlTest` 是故意失败的负向对照,默认排除,仅 `-PincludeNegativeControl` 时运行。
- **lint 必须零告警**:`warningsAsErrors = true` + `abortOnError = true`,一条 warning 即硬失败。新增资源/权限/API 后尤其容易多出 `ObsoleteSdkInt`、`UnusedQuantity`、`ExtraTranslation`。

### 7.3 真机核对规约(三层自证的第三层)

**授权**:动手机前必须先通知用户并取得明确授权;**每轮单独授权,不继承上一轮**。用户未授权时可先把前两层做完并汇报,不要自行连设备。

**设备与环境**(固定):无线调试 `adb connect 192.168.43.1:4444`;PJX110 / Android 16 / `1264×2780px` / `560dpi` = **361dp × 794dp**;系统 locale `zh-CN`。
- 唤醒常亮:`input keyevent KEYCODE_WAKEUP` → `input keyevent 82`(解锁)→ `svc power stayon true`。
- 安装:`adb install -r pixfold-d1/app/build/outputs/apk/debug/app-debug.apk`。
- 启动:`adb shell monkey -p com.pixfold.d1 -c android.intent.category.LAUNCHER 1`(⚠️ `input keyevent 4` 可能直接退出应用,不是回首页)。

**取证**:`pixfold-d1/dumpui.bat NAME` → dump 到 `/data/local/tmp/NAME.xml` → 落入 `pixfold-d1/artifacts/`(已 gitignored)。**读节点 `bounds` 坐标是唯一能证伪"控件放不下 / 落在视口外"的场合**;`uiautomator dump` 的坐标会过期,**每次 tap 前重新 dump**。

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
