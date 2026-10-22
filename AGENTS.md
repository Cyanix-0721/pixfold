# AGENTS.md — PixFold(项目级)

> PixFold:图片整理与 CBZ 制作工作台(图形应用),**GUI 只做 Android**(2026-09-10 起;Windows / Linux 端只做 CLI),技术栈 **Kotlin + Jetpack Compose(Android 原生)**(§8.1;换栈触发 = §8.2 证伪条件 ③)。
>
> **当前阶段(2026-10-19)**:设计期(设计文档线)与实现并行推进。**D1 交付物全部完成**(交互原型 P1–P7 交付、验收清单 20/20、收尾三轮含拖拽让位动效、D1 退出条件 §9 决策门已达成)。**D2 阶段性完成**:**D2a** = SAF 平台层在 `android/` 里重建(只读闭环:领域端口 + 递归遍历 + 持久授权 + `loadThumbnail` + 工作区条/空态),**真机核对通过**;**D2b 五项** = ①千级缩略图策略 ②后台任务与进度 ③授权失效恢复 ④存储空间预检 ⑤大归档写入,**领域层与平台层全部接线完成**;真机取证 **3/5 完整**(②③④已含失败路径),**①只测到 56 张、⑤只测到 33MB 级**(1GB 级与 Zip64 仍未验)。**D3** = 打包核心与适配层(含可执行边界检查 `AdapterBoundaryTest`)。**D4b** = 真实文件闭环:**已端到端跑通** —— `/storage/emulated/0/test` 真实产出两个独立 CBZ(`38,085,495 B` / `79,764,587 B`)。**D2b 代码层已全部完成(2026-10-19 深夜)**:切后台恢复走 `dataSync` 前台服务 + 快照恢复,进度通知支持 Android 16 Live Update 升格(真机拿到 `PROMOTED_ONGOING` 与状态栏胶囊),完成通知分两条渠道(进度静音 / 完成跟随系统)。**同日补做失败路径真机取证并查出真缺陷**:③授权失效(撤销持久授权后逐字显示「重试后仍无法访问…」+ 重选入口)、④空间不足(「还差 4.3 GB」且零字节写入)均通过;**⑤ 查出 OOM 级缺陷** —— 单页整块 `readBytes()` 在 256MB 堆上撞 `OutOfMemoryError`,进程被杀且留下 0 字节 CBZ;已按用户裁决改成**流式两遍读**(峰值内存 = 64KiB 缓冲区),复验产出 33,555,158 B 完整 CBZ。**仍未做**:千级缩略图实测、1GB 级归档 + Zip64 —— 用户 2026-10-19 裁决**接受「代码写完但未验证」,记录后转走查阶段再补**(依据:真机只有 56 张素材;CBZ 是 STORED 不压缩,1GB 输入需等量输出而设备只剩 3.24–4.6 GB)。**D4b 真实改名/撤销已跑通(2026-10-19 深夜)**:`domain/plan/RenameExecution.kt`(`RenameActions.rename` 返**新 uri** 而非 Boolean,撤销才改得对人)+ `data/saf/SafRenamePlatform.kt`;真机 120 张全部改名成功后全部撤销回原名。**首轮撞出 ANR**:`executeRenamePlanReal` 在主线程逐项调 `DocumentsContract.renameDocument`(每项一次同步 Binder 往返),120 项 > 5 秒被系统杀;`logcat -b crash` **空**(不是崩溃),靠 `dumpsys dropbox --print data_app_anr` 才拿到主线程栈顶。修法三件:领域层加 `CancellationToken` + `onProgress`(中断后剩余项补 `PackFailNotStarted`)、UI 执行与撤销都挪 `Dispatchers.IO`、进度条通用化为 `ui/components/TaskProgressBar.kt`。**修正后的续接点**:D4b 剩余 = CBZ 可被阅读器打开、1GB 级归档(已裁决留走查补)。
>
> **D2 期间的关键改判与新增决策**(细节见 HANGOFF §9 D2a/D2b 节与 §5/§11):
> ① **集合分级改判** —— 旧「一级子目录 = 一个集合」在真实素材上把同系列多本并成一集,改为「**每个直接含图片的目录 = 一个集合**」,父目录即系列(`ImageCollection` 尾部追加带默认值 `seriesKey`/`seriesName`,26 处既有构造零改动);**不做嵌套结构** —— 集合是唯一被排序/命名/打包消费的单位,系列只是芯片行分组视图;集合 id 用**相对工作区根的路径**(用目录名会在不同系列下撞 id)。
> ② **工作区实时刷新** —— 回 `ON_RESUME` 重扫 + `ContentObserver`(`notifyForDescendants = true`)+ **增量对账**(保留人工排序/改名,丢弃已删,新增按规则插入;对账**必须在组合期**做,放 `LaunchedEffect` 会因下层不重播种而看不到结果)。刷新期保留旧列表不退回 `Loading`、失败不清屏、变更风暴合并成补一轮;**手动刷新按钮刻意不做**。**已知代价**:绕开 `DocumentsProvider` 直接改文件系统(adb `cp`/`rm`)不触发 `notifyChange`,只能靠回前台重扫兜住。
> ③ **芯片行统一**(用户裁决只统一「集合芯片」与「卷芯片」,其余 9 处 `FilterChip` 不动) —— 抽出 `SingleSelectChipRow<T>` 底层,集合芯片与工作流 B 卷芯片走**两级**(1 级 = 系列或单本、2 级 = 选中系列后其下列出各成员;1 级在选中系列时收缩到一行);第二层底层 `TwoLevelChipRows<T>`,首版未自带 `Column` ⇒ 非 `Column` 容器下两行 bounds 完全重合、点击命中最上层(已修)。其余 `FilterChip` 是「编辑一个参数」而非「从 N 条选 1 条」,语义不同。
> ④ **工作流 B 复用工作区**(用户裁决,与 A 同一套)—— 一个集合 = 一本;三层分工 `comicVolumesFrom`(纯函数给建议值)/ `SafOutputExistsProbe`(问 provider 同名 CBZ 是否存在,决定覆盖警告)/ `SafPackExecutor`(读字节写 ZIP)。`outputExists` **必须问一次**:为 true 不告知 ⇒ 静默覆盖;为 false 而实际存在 ⇒ 撤销把「覆盖」记成「新建」、**删掉用户原来的文件**(缺陷 R3)。
> ⑤ **输出名撞车必须执行前阻断** —— 真机当场暴露的**静默数据丢失**:同系列两本默认名都取系列名 ⇒ 两卷写同一路径、后写覆盖前写,报告说「成功 2」而磁盘只剩 1 个文件。修法 = `titleSug` 改取**卷目录名**(用户指出「默认名不是应该从单本的文件(夹)名解析吗」)+ `PackPreflight` 新增 `PackBlocker.OutputNameCollision`(真机验证:两本都改 `same.cbz` 后界面明确提示且**一个字节都没写**)。
> ⑥ **空间未知 ≠ 空间充足** —— `availableBytes: Long? = null` 表示「无法判定」而非 0;拿假大数伪装「够用」和直接拒绝都错。**§11 第 12 条空间阈值由此关闭**:`SpaceThreshold.Percentage(10)` + 安全余量 `32 MiB`。
> ⑦ **零字节 CBZ 的防线 = 先探测后开流** —— 缺页时连输出流都不打开;写盘失败必 `discardFor` 清理半成品;中断只在**卷间**检查(半途硬停留半个 ZIP)。
> ⑧ **`PackRunReport` 单向转 `ExecutionReport`** —— 真实写盘报告喂给 D3 已有报告页,UI 零改动;只有 `Written` 算成功且带 `createdPath`(自动不进撤销记录),`originalPath` 复用 `overwritesExisting` 这唯一判据。
>
> **D2 期间真机暴露的真实缺陷**(均已修,详见 HANGOFF §9):① **热切换工作区整片空白** —— 本地 `state` 用**无参 `remember`** 播种,上层换工作区传下新 state 但下层组合未销毁 ⇒ 仍持旧 orders ⇒ 渲染处 `?: return` **静默白屏**,连「重置全部」也救不回;修法 = 播种点加 `collections`/`collection` 作 key + 去掉静默 `return` 改显式空态。**任何状态错配都必须可见,白屏是最坏的失败模式。** ② **重命名后文件名不更新** —— `reconcilePageOrder` 原在 id 集合相同时原样返回旧实例,而 `ExternalStorageProvider` **重命名不换 `documentUri`** ⇒ 永远显示旧名;改为「顺序与人工标记原样保留,但每项换成刷新后的元数据」。③ **集合分级错误**(见上 ①)。
>
> **D2 收尾四道闸门(第二轮结束时)**:`app 616` / `domain 817` / `lint` 零告警 / APK **34,852,830 B**;变异 M1–M16 全 KILLED(M5 首轮存活 = 排序断言没有判别力 ⇒ 改成逆序后杀掉;M15/M16 为本轮新增的 `effectiveScheme` 与播种入口变异)。**遗留**:① `ContentObserver` 的 `notifyForDescendants` 变异 **M8 刻意保留**(Robolectric 无真实 `DocumentsProvider`,属「只能真机断」类别,不设假保障);② `WorkspaceState.Failed` 不带 handle ⇒ 首次加载失败后 `refresh()` 是 no-op(已知,未修)。
>
> **下一步 = Series 三条语义(用户 2026-10-20 裁决,详见下方「Series 待做」)。** **本轮(2026-10-20 凌晨)**:①**计划页预检** —— 预检从「点执行才算」提前到「进计划页就算」(`PackExecutorPort.preflight` + `PackUiPreflight{Ready/Unavailable}` + `PackPreviewPage` 预检面板,阻断=error 色/告知=onSurfaceVariant),并修掉 `assessZip64Need` 从不接收 `centralDirOffset` 导致「整卷超过 4 GiB」永不触发;真机 4.7G/1600 页单本验证面板文案出现;顺带修 `PixFoldApp` 的 `packExecutor` 缺 `remember` ⇒ 预检无限重启(改 `rememberUpdatedState`)。②**CBZ 阅读器兼容已取证** —— Kuro Reader Pro 1.6.7 打开真机产出的 38MB/24 页包,首屏正常、末页 `24 / 24`;并补上此前零覆盖的检查(`CbzReaderCompatTest` 9 项全走 `ZipFile`,此前全工程回读一律 `ZipInputStream`)。③**已有 ComicInfo.xml 作为当前值** —— `ComicInfoReader.kt` 的 `parseComicInfo`/`applyExistingComicInfo` + `SafExistingComicInfoProbe`;当前值取文件、**建议值仍取文件夹**(用户裁决),「恢复建议值」因此与无 ComicInfo 时一致;对账仍按「值≠建议」判人工改过。④**卷号为空省略 `<Number>` 标签**(保留卷号缺失提示)、**LanguageISO 缩为 Unset/Zh/Ja**(Unset 即终态,不再进 `pendingIssues`)、**元数据输入框改多行软换行**(修光标拖不到视口外)。四道闸门 **app 677 / domain 907 / lint 零告警 / APK 34,236,654 B**。**执行后可撤销已落地** —— 见上方续接点段的 ANR 修复轮记录。**名字清洗与「目录名 → 展示名」统一抽象**(2026-10-19 晚):`derivedNamesOf` 是全工程唯一入口,芯片显示原始目录名、`<Title>` 用清洗后的 `title`、CBZ 名用保留 `[作者]` 前缀的 `cbzBase`。
>
> **本轮(2026-10-20 白天)**:①**4.73 GiB 归档真机实测通过** —— D2b 第 5 项最后一块。用 `test/[青水庵 (にやすけ 青水庵)] 子産み島/…2&3`(4.7 GB / 1599 页)打出 **5,078,971,778 B** CBZ;Zip64 取证:普通 EOCD `cd_offset=0xFFFFFFFF`、Zip64 EOCD Record `version_needed=45`、`cd_offset=5,078,882,575`;Kuro Reader 打开 `1/1599`→`1599/1599`。②**通知跳转缺陷已修** —— `MainActivity` 读 `EXTRA_OPEN_LIBRARY_PACK`(`onCreate` + `onNewIntent` 两个入口)、`PixFoldApp(openPackOnStart)`+`onPackRouteHandled` 回清、`LibrarySteps(startAtPackStep)` 走 `goToLibraryStep`。**真机第二轮撞出真缺陷**:`var startAtPack by remember { mutableStateOf(...) }` **无 key** ⇒ 第二次点通知静默失效 ⇒ 加回清后修复。③**打包进度改双层圆环弹窗 + 背景高斯模糊**(用户裁决:模态/只模糊背景/取消二次确认):领域层补**页级进度透传**(`CbzWriter`→`PackRunner`→`PackExecutor`,内环用);新建全站弹窗抽象 `ui/components/PixFoldDialog.kt` —— `DialogBlurController` **用计数不用布尔**(嵌套弹窗)且 `release` 钳到 0(否则背景永久模糊)。**真缺陷**:首版 `DialogBlurHost` 按 `hasOpenDialog` 切换 `content()` 调用层级 ⇒ Compose 视为不同子树而重建 ⇒ **弹窗一打开就自己消失**(两条既有测试抓出)⇒ 改为「Box 恒定,只让修饰符变」。变异 M1–M4 KILLED;M5(模态语义)存活 ⇒ 删断言并写明「只能真机验」(Robolectric 无「点 Dialog 外部/返回键」路径)。四道闸门 **app 693 / domain 910 / lint 零告警 / APK 34,323,186 B**。
> **Series 三条语义已实现(2026-10-21 凌晨,详见 HANGOFF §9「Series 三条语义」)**:①无系列层时 Series 跟随 Title;②仅当卷目录 `[作者]` 前缀与工作区文件夹名一致时取工作区名(`seriesSuggestionOf` 判定树);③series 下嵌套目录 ⇒ 书架页警告条(`nestedDirWarnings`)。app 704 / domain 920,变异 M1–M7 全 KILLED;**真机核对待补**(设备凌晨自动锁 PIN)。
> **⚠️ 换机续接点(2026-09-27 订正)**:当前工作分支 = **`dev`**;**已推送的远端基线是 `origin/dev`**,本地 `dev` 领先若干提交且**均未推送**(确切数目查 `git rev-list --count origin/dev..dev`,**本处不硬编码以免每次提交后失效**)。曾经的阶段分支 `d1/settings-impl` 已合回 `dev`(merge `7a6f678`)并删除本地分支。分支拓扑 `ready` → **`dev`** → 阶段分支 `d1/pN`(完成即合回 `dev` 并回写文档);**D1 全绿后 `dev` → `main` 发 release** —— 详见 §6 与 HANGOFF §9.2。**push 由用户处理,agent 只提交不推送。**
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
> | 2026-10-18 | **D1 收尾轮 · 第三轮(元数据页卷芯片行常驻 + 规模上限 + 「全部 N 卷」sheet)**:补测唯一「待实测」项(元数据页单页长度)⇒「过长」不成立(1.62 视口 / 2 次 swipe),但派生 P2 = 卷芯片随滚动滚出视口、换卷必须回顶;`VolumeSelector` **移出 `LazyColumn` 常驻**(与步骤 3 同构);随即发现 40 卷规模回归(13 行芯片 834.9dp 吃满视口 ⇒ 字段永久不可达)⇒ 常驻限 5 个芯片(前 4 卷 + 当前卷,`visibleVolumeChips()`)+「全部 N 卷」`TextButton` + `ModalBottomSheet`;`FlowRow(maxLines=2)` 变异存活**已删**。代价:列表视口缩 14.6%。**同源缺陷(步骤 3 `PackPreviewPage` 卷芯片)经用户裁决「一起做了吧」一并修** ⇒ 抽共享组件 `ui/components/VolumeChipRow.kt`(全站唯一实现,参数注入各页 tag/文案差异),`plurals meta_volume_all` 迁至 `strings_common.xml` 的 `volume_all`;`ChipRowWrappingTest` 的 `FlowRow` 计数表与 `horizontalScroll` 扫描列表同步扩到新文件。变异 M1–M6c 全 KILLED(M1 首轮存活 ⇒ 补直接断言后杀掉)。四道闸门绿(app **491** / domain 483 / lint 零告警 / APK **34,178,446 B**),真机 5 卷 + 40 卷 × 步骤 2/3 双页面核对通过 | `f5f7457`/`21426c8` | HANGOFF §9「D1 收尾轮 · 第三轮」+ `docs/notes/d1-ui-friction-inventory.md` §7b |
> | 2026-10-18 | **工程改名(进 D2 前)**：目录 `pixfold-d1/` → **`android/`**、包名 `com.pixfold.d1` → **`com.cyanix.pixfold`**、`rootProject.name` → `pixfold-android`（`namespace`/`applicationId` 同步改）。动机 = `pixfold-d1` 是 D1 阶段代号，工程早已是 D4 MVP 基座并进入 D2。规模：Kotlin 源码 1115 处 / 172 文件、Gradle 3 处、`run-gradle.bat` 路径、源码扫描型测试的硬编码路径 2 处 + **边界测试正则 1 处**。⚠️ **两处易漏**：① `AdapterBoundaryTest` 的 `FORBIDDEN_REFERENCE` 写的是**转义形式** `com\.pixfold\.d1`，普通 `sed 's/com\.pixfold\.d1/…/'` 匹配不到 ⇒ 漏改会让边界检查去扫一个已不存在的包名、退化成恒真式（假绿）；② 目录改名后 `.gradle`/`build` 里的**增量编译缓存含旧绝对路径**，首次构建报 `The given file '…pixfold-d1\domain\build\classes…' is located outside the root directory`，须删 `build`/`.gradle` 重跑。四道闸门绿（app **491** / domain **483** / lint 零告警 / APK **33,555,875 B**），变异 M1（把边界正则退回旧包名）**KILLED**。文档口径：当前状态引用改新名，**历轮台账保留 `pixfold-d1` 原文**（改历史=伪造历史） | — | HANGOFF 头部「工程改名」注 + §9 D1 复用关系 |
> | 2026-10-18 | **D2a 平台层在 `android/` 里重建(只读闭环)**:领域端口 `domain/source/`(`SourceDoc` 纯值对象 / `WorkspaceSource` 接口 / `collectionsFrom` 分组规则唯一权威,21 tests)+ 平台层 `app/data/saf/`(`AndroidSafSource` 递归 `buildChildDocumentsUriUsingTree` + `MAX_DEPTH=4` + 不传 sortOrder / `SafTreeStore` / `AndroidThumbnailSource`)+ `ui/workspace/`(`WorkspaceState` 映射 / `WorkspaceController` / `WorkspaceBar`)+ 5 处缩略图改 `ItemThumb`。**落点判断**:**不加第 4 个 Tab**(会牵动 `workflowAStepIndex()`/`goToWorkflowAStep()`/`popWorkflowStep()` 与约 30 条导航测试)⇒ 页签上方常驻 `WorkspaceBar` + 页签下方空态,与 §4「从属层」一致。**`None` 不是错误**:D1 全 mock ⇒ `collectionsOr(mock)` 回退演示数据、既有测试零改动;但 `Ready` 且空列表**不**回退。⚠️ lint 首轮 6 错(`workspace_none` 未使用 + 5 处 `UseKtx` 的 `Uri.parse` ⇒ 全改 `toUri()`;顺带删掉包 `Uri.parse` 的**死 try/catch** —— 它永不抛异常,真判据是 `getTreeDocumentId` 抛 `IllegalArgumentException`)。四道闸门绿(app **515** / domain **504** / lint 零告警 / APK **33,758,891 B**),变异 **M1–M4 全 KILLED**。**真机核对顺延 2026-10-19**(用户深夜指令)。⚠️ 变异踩坑:读 JUnit XML 前必须确认本次 gradle 真的跑过(否则读到上一轮结果 = 假阳性) | — | HANGOFF §9 D2a 节「在 android/ 里重建」 |
> | 2026-10-19 | **D2b ①千级缩略图**:全缓存约 892 MiB vs 真机堆 256MB ⇒ `ThumbBudget`(边长向上对齐 2 的幂/预算=堆 12.5%/并发 `cpuCores+1`)+`ThumbnailCache`(按字节 LRU)+`CachingThumbnailSource`(在途请求合并 —— 并发闸门放行数 >1 时同图被读 4 次,是测试抓出的真缺陷);平台实现退回「只管读」,缓存与并发上移到可离线断言处。app 536 / domain 525,变异 M1–M10 全 KILLED | — | HANGOFF §9 D2b「第 1 项」 |
> | 2026-10-19 | **D2b ③授权失效恢复决策层**:「用户真撤销」与「进程重启后授权未就绪」都是 `SecurityException`,平台无从分辨 ⇒ 不能一见 `Denied` 就报失效;`WorkspaceRecovery` 四态状态机(有限重试后降级/保留原目录名/任何失效态至少一条出路)+`AUTO_RETRY_LIMIT=1`;`WorkspaceController` 的 IO 调度器改可注入。app 554 / domain 542,变异 M1–M8 全 KILLED(M4/M5/M6 首轮存活 = Controller 零覆盖) | — | HANGOFF §9 D2b「第 3 项」 |
> | 2026-10-19 | **D2b ②后台任务与进度领域层**:`TaskProgress` 五条语义 —— 失败项也推进进度(否则失败 30% 时卡在 70% 不动)/空任务即完成/节流六条判据(完成必报、失败数变化必报、倒退必报)/中断令牌 `@Volatile` 且只在项间检查/结果先判完成再判取消。app 554 / domain 568,变异 M1–M10(M8 等价 ⇒ 删冗余判据) | — | HANGOFF §9 D2b「第 2 项」 |
> | 2026-10-19 | **D4b 执行前预检**:把此前从未被生产代码调用的 `SpaceCheck` 与 `Zip64Assessment` 组合;三处只在组合时出现的问题 —— SAF 的 `COLUMN_SIZE` 可为 null ⇒ 估算是**下界**(必给 `EstimateIsLowerBound`)/空计划不判空间/**Zip64 是告知不是拒绝**(当阻断会让大归档完全打不了)。阈值刻意不设默认值。domain 608,变异 M1–M7 全 KILLED | — | HANGOFF §9「D4b 执行前预检」 |
> | 2026-10-19 | **D4b 执行编排内核**:`PackRunner`+`CbzOutputSink` 把 `buildCbzWritePlan`/`PageByteSource` 按卷串起来;四条硬规则 —— **先探测后开流**(缺页时连流都不开,否则留 0 字节 CBZ)/写盘失败必 `discardFor` 清理/中断只在卷间/`timeMillis` 必显式给。`isComplete` 在有失败项时仍为 true。顺带把「跳过」从 `failed` 拆成独立 `skipped`。domain 590,变异 M1–M6 全 KILLED | — | HANGOFF §9「D4b 执行编排内核」 |
> | 2026-10-19 | **D4b 预检→执行闸门**:`PackGate.runPackPlansChecked` 让「预检拒绝就别写」从注释承诺变成代码 —— `Rejected` 时**一个输出流都不打开、一页字节都不读**;接口形状防「忘了预检」(参数 = `runPackPlans` 全部 + 空间参数)。domain 616,变异 M1–M3 全 KILLED | — | HANGOFF §9「D4b 预检→执行闸门」 |
> | 2026-10-19 | **D4b 端到端集成测试**:`PackEndToEndTest` 用真实 mock 库(5 卷含 1 卷输出冲突)跑通「扫描→计划→预检→执行→ZIP 字节」;只断链路产物不断 ZIP 内容。三条真实数据才暴露的发现 = 输出名唯一性/跳过卷不该产生输出/中断与缺页的交互。domain 634,变异 M1–M8 全 KILLED | — | HANGOFF §9「D4b 端到端集成测试」 |
> | 2026-10-19 | **D4b 运行报告桥接**:`packRunAsExecutionReport` 把真实 `PackRunReport` 单向转成 UI 已在渲染的 `ExecutionReport`(UI 零改动)。三条硬规则 —— 只有 `Written` 算成功且带 `createdPath`/`originalPath` 复用 `overwritesExisting` 唯一判据/中断后未处理卷补成「尚未开始」而非「跳过」。app 554 / domain 652,变异 M1–M5 全 KILLED | — | HANGOFF §9「D4b 运行报告桥接」 |
> | 2026-10-19 | **D2a 真机核对 + 三处真缺陷**:①热切换工作区整片空白(无参 `remember` 播种 ⇒ 旧状态 + `?: return` 静默白屏)②集合分级错误(改判「每个直接含图片的目录 = 一个集合」)③重命名后文件名不更新(`documentUri` 不变 ⇒ 同 id 时原样返回旧实例)。并落地工作区实时刷新(回前台重扫 + `ContentObserver` + 组合期增量对账)。app 582 / domain 699,变异 M1–M11(M8 刻意保留) | — | HANGOFF §9 D2a/D2b 节 |
> | 2026-10-19 | **D2b 平台层接线 + 真实产出 CBZ**:`SafPackPlatform`(覆盖走 `openOutputStream(existing,"wt")` 否则被改名成 `xxx (1).cbz`)/`SafPackExecutor`/`ComicVolumesFrom`/`PackExecutor`;真机产出 38,085,495 B 与 79,764,587 B 两个 CBZ。**当场暴露静默数据丢失**:同系列两本默认名都取系列名 ⇒ 后写覆盖前写 ⇒ 修 `titleSug` 取卷目录名 + `PackPreflight.OutputNameCollision` 阻断。app 614 / domain 776 | — | HANGOFF §9 D2b/D4b 节 |
> | 2026-10-19 | **名字清洗 + 目录名统一抽象**:真机 CBZ 名带 `[DL版]` ⇒ 查出真实扫描路径此前完全没清洗。`NameCleaning`(`TRAILING_TAG_RE` 的 `$` 锚承重/`cleanCbzBaseName` 保留 `[作者]`)+`derivedNamesOf` 成唯一入口(raw 给芯片/title 给 `<Title>`/cbzBase 给落盘名,四种形态不得合并)+`namingSchemeSeedOf` 收敛 5 处分散播种。app 616 / domain 817,变异 M1–M16 全 KILLED | — | HANGOFF §9 D2b 节 |
> | 2026-10-20 | **弹窗共同父类 + 第 9 处模糊 + 页级进度**:用户裁决「写的更加抽象写让调用的几个都有共同的父类?」「我说的包括8」⇒ 四个便捷入口全部委托 `PixFoldDialogShell`(Compose 无 UI 继承,「共同父类」唯一形态 = 委托同一 Composable);`buttons` 收 `ColumnScope` 任意槽(M3 `AlertDialog` 两槽摆不下三颗全宽竖排);`contentScrollable` 开关是**崩溃点**而非可选项(正文已是 `LazyColumn` 再包 `verticalScroll` 必崩);第 9 处 `ModalBottomSheet` 并列 `PixFoldBottomSheet` 但**共用同一模糊计数**。页级进度逐层透传 `CbzWriter→PackRunner→PackExecutor`(内环用)。app **701** / domain **910**,变异 M1–M6 全 KILLED(**M6 首轮存活 = 测抽象层时抽象层把错自己吃掉了 ⇒ 改经真实调用方断 scroll 容器个数**) | — | HANGOFF §9「双层圆环进度弹窗」 |
> | 2026-10-21 | **Series 三条语义**:①无系列层时 Series 跟随 Title;②仅当卷目录 `[作者]` 前缀与工作区文件夹名一致时取工作区名(`seriesSuggestionOf` 判定树,**原始方括号前缀比对**而非清洗值);③series 下嵌套目录 ⇒ 书架页 error 色警告条(`nestedDirWarnings` = seriesKey 含 `/`,naturalCompare 排序)。占位符顺序三处同步(TranslationCoverageTest 连环抓)。app **704** / domain **920**,变异 M1–M7 全 KILLED;**真机核对待补**(凌晨设备自动锁 PIN) | — | HANGOFF §9「Series 三条语义」 |
> | 2026-10-21 | **构建提速 + 闸门退出码修复**:①四道闸门旧调用形式 `cmd.exe /c "run-gradle.bat …" | tail -20` 的 RC 来自 `tail` ⇒ **构建失败也报成功**(实测漏一个 `}`,gradle `BUILD FAILED` 而 RC=0)⇒ 新建 `android/gate.sh`(输出重定向 `build/gate-last.log` + 直接取 gradle 退出码 + 失败打行号);⚠️ 外面再套 `\| tail` 会造成假挂起(`tail` 等 EOF)②`gradle.properties` 四项性能开关全开:`-Xmx4g`(内存峰值采样 4046→2471 MB,增量 1.6 GB ⇒ 原定 6 GB 虚高)/ `parallel` / `caching` / `configuration-cache`(lint 连跑 stored 4s→reused 2s);**build cache 让 JUnit XML mtime 失去证明力** ⇒ 判闸门看 `build/gate-last.log` 的 task 行。app 706 / domain 920 / lint 零告警 / APK 35,024,674 B | — | HANGOFF §12.4「每日必用命令」+ §12.4b「构建性能配置」 |
> | 2026-10-21 | **元数据默认值三改判 + chip 六项规格**:①writer 默认值取清理后形态(去 `[]`)②建议值优先级 ComicInfo > 目录层级(`applyExistingComicInfo` 扩为连建议值一起覆盖)③单本 Series 跟随 Title(恢复建议后两者同值)④修本轮引入的回归:新增 `seriesDir` 作 `seriesKey` 唯一判据(原先取当前值 ⇒ 5 个单本显示成 5 个系列)⑤chip:截断保首尾(10 字)/后缀独立通道/文字居中/行内左对齐/两级遮罩留白/1 级恒 2 行 + 全宽下箭头长按钮⑥一键清理与恢复建议改纯图标 + 长按提示⑦`AppNavigationTest` 补 `qualifiers`(全仓唯一漏写,Robolectric 默认屏 320×470dp 装不下)。app **731** / domain **925** / lint 零告警 / APK 34,357,115 B;变异 M1–M12 全 KILLED | — | HANGOFF §9「元数据默认值三改判 + chip 六项规格」 |
> | 2026-10-22 | **进度读数合并 + 元数据「按目录结构获取」**:①进度弹窗中心读数改为「已完成整卷 + 当前卷页比例」合并值(`TaskProgress.percentWithPage`),否则 1599 页的卷前 1598 页一直显示 0% ②删「一键清理」(writer 默认值已是清理形态 ⇒ 恒等变换)③新增「按目录结构获取」挂页签行「重置全部」旁、作用于当前卷全部字段,`ComicVolume` 补 `dirTitleSug`/`dirSeriesSug` 快照且 `applyExistingComicInfo` 刻意不动三个 dir 字段(否则 ComicInfo 写错时用户没有退路)④修真缺陷:`MetadataPage` 用无参 `remember` ⇒ 上层改状态后输入框毫无反应(与 D2a 白屏同因)。app **736** / domain **933** / lint 零告警 / APK **35,043,142 B**;变异 M1–M2 全 KILLED;**真机核对待补(白天)** | — | HANGOFF §9「进度读数合并 + 按目录结构获取」 |
> | 2026-10-22 | **空标签统一 + 缺失警告补齐 + chip 上限放宽**:①五个 ComicInfo 字段**统一写自闭合空标签**,`<Number>` 不再例外(用户先要求「全都不写入」、随即改回「全部写自闭合」;D1 那条「`<Number />` 会被读成第 0 卷」是猜测性理由)②`pendingIssues` 从 1 项扩到 **4 项**:新增 `PendingTitle`/`PendingSeries`/`PendingWriter`(判据 `isBlank()`,只填空格算缺失),三处 UI 已在消费故零改动。⚠️ **标题这轮漏过一次** —— 首版以「标题几乎总能从目录名得到」为由不列,但 `titleSug` 只是**建议值**、Title 是可编辑输入框,用户清空后 XML 就是 `<Title />`;判据必须是「当前值是否为空」③chip 字符上限 10→24(首 12 + … + 尾 11),前提是芯片内文字已可折行 ⇒ 宽度上限改为「够看出一本书是谁」。app **736** / domain **937** / lint 零告警 / APK **35,044,494 B**;变异 M1/M2/M4 全 KILLED,补标题后 M1/M2 亦 KILLED;真机验超长名 `超长系列名称开头标识符A…末尾标识符XYZ（2）` 首尾保留+折行、`待确认：卷号缺失 / 作者缺失`、清空标题后 `待确认：标题缺失 / 卷号缺失` | — | HANGOFF §9「元数据空标签统一 + 可选字段缺失警告 + chip 字符上限」 |
> | 2026-10-22 | **字段级红色警告**:`ComicVolume.pendingIssueOf(field)` 成「字段 ↔ 待确认项」唯一映射(页头汇总改为 `PENDING_FIELD_ORDER.mapNotNull`),`TextFieldRow` 补 `isError` + `supportingText`(Title/Series/Writer 各自标红)。⚠️ 需求先理解错一轮 —— 我补的是页头汇总,用户要的是「卷号那样空值的红色警告」。断言口径:不能用 `SemanticsProperties.Error`(M3 恒填 `Invalid input`),supportingText 因 `mergeDescendants` 并进同一节点 ⇒ 读该节点 `Text` 列表。app **741** / domain **940** / lint 零告警 / APK **35,625,226 B**;M1–M3 全 KILLED;真机见红边 + 「标题缺失」 | — | HANGOFF §9「字段级红色警告」 |
>
> ⚠️ **写头部时的两条纪律**(踩过):①**详版会超预算被截断** —— 只留「阶段/续接点/索引」,细节一律落到上表指向的文档;②**不硬编码提交数**(每次提交后即失效)。
>
> 本文件优先级低于用户当回合指令,高于全局 AGENTS.md 的**项目事实类**约定(技术栈/环境/工具链/构建命令)。**但全局 `~/.dsh/AGENTS.md` §0 的「任何用户任务先按 Superpowers 协议匹配 skill」是下限,项目级不得覆盖** —— 见 §7.0。

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
- **分支模型(2026-09-16 起)**:`ready`(设计文档线,原型代码永不进)→ **`dev`(开发主线)** → 阶段分支 `d1/pN`。阶段分支从 `dev` 开、**完成即合回 `dev` 并回写文档**、随即删除;D1 全绿后 `dev` → `main` 发 release,此后开发继续在 `dev`。**push 由用户自行处理**,agent 只提交不推送。详见 HANGOFF §9.2。
- 验收:任何改动对照 `HANGOFF.md` §10(功能 / 数据正确性 / 平台)自检。

## 7. 每轮任务的固定步骤与真机核对规约

> 本节把已反复踩过的坑固化为步骤。**用户可当回合覆盖本节**;未覆盖时按此执行,不得跳步。

### 7.0 skill 匹配(本节唯一不可被项目级覆盖的部分)

**动手前的第一个动作是 `skill` 调用,不是 `bash`/`read`/`todo_write`。**

> 「不可被项目级覆盖」只针对**自动加载的项目指令**;用户当回合说「跳过 skill」仍然有效(用户 > 项目 > 全局),只是那种覆盖必须来自用户本人,不能来自本文件的其它章节。

条文本身在全局 `~/.dsh/AGENTS.md` §0,此处不复述(避免两处漂移)。项目侧只补三条本条特有的事实:

1. **技能名无前缀**。实际名是 `brainstorming`/`writing-plans`/`executing-plans`/`test-driven-development`/`systematic-debugging`/`verification-before-completion`/`requesting-code-review`/`finishing-a-development-branch`/`dispatching-parallel-agents`/`using-git-worktrees`/`using-superpowers` 等 15 个,**没有 `superpower-` 前缀**;叫错名 = 调不到。
2. **本项目最常见的漏掉场景**:请求里出现「加一个/改一下/支持 X」但没明说「设计」时,`brainstorming` 仍然适用;请求是「检查/收尾/验收」时,`verification-before-completion` 与 `finishing-a-development-branch` 适用。
3. **§7.1 的七步不是 skill 的替代**。七步管「怎么落地」,skill 管「用哪套方法落地」——§7.1 第 2 步(红→绿)对应 `test-driven-development`,第 3 步(变异)与第 7 步(提交)分别对应 `verification-before-completion` 与 `finishing-a-development-branch`。**跳过 skill 直接进七步 = 只剩流程没有方法。**

⚠️ 依据(实测,非推断):本工作区 7 个历史会话 337 个人工轮、23,780 次工具调用中 `skill` 只出现在 **8 轮(2.4%)**;而技能目录在每个会话第 1 轮就已完整注入(38 个技能,含上述 15 个)。**问题是看见了不调,不是看不见。**

### 7.1 固定步骤(顺序不可调换)

> **进入下面七步之前,先过 §7.0** —— skill 匹配是第一步的前置,不是第零个可选步骤。

1. **对齐依据**:读 `HANGOFF.md` 对应条目(产品决策唯一权威)+ `docs/notes/d1-ui-friction-inventory.md` + 用户当回合指令;**有歧义先请用户裁决**,把裁决项写下来再动手。
2. **红 → 绿**:先写会失败的断言,再改码;跑四道闸门(见 7.2)。
3. **变异验证**:证明断言有杀伤力(见 7.4)。
4. **真机核对**:必须**先获用户授权**(见 7.3)。
5. **回写文档**:`HANGOFF.md` → 同步本文件头部快照 + `README.md`(变更路由三处一致)。
6. **展示改动集等批准**:列出文件与行数,**等用户点头**。
7. **提交**(只提交不推送)→ **推送前**跑密钥扫描(见 §7.2 末;**必须在提交之后** —— 该扫描读的是 git 对象,提交前跑会漏掉正要提交的内容)→ **合回 `dev`** → 再补一次"提交号 + 合并结果"的文档同步提交。

⚠️ **任务面板纪律(与上列 7 步并行贯穿全程):开工建全量清单,每步完成立即标记,不得攒批** —— 条文与理由见全局 `~/.dsh/AGENTS.md` §0(含 `todo_write` 的挂载来源、`executing-plans` 依据、以及「面板是压缩后唯一能自证进度的载体」的原因),**此处不复述以免两处漂移**。**留一条本项目实测先例**:第 18 条一轮 7 项**开工时只写了一次、全程未再更新**,直到用户指出(`User said (m16667)`:「你又忘了推进任务进度」)才一次性全勾掉;性质是**执行过失而非规约缺口**。

⚠️ **同一过失反复发生，累计五次(内容相同无需重读)**：② `User said (m21345)`「你又忘了实时跟进人物面板」；③ `User said (m22486)`「你都到变异测试了任务面板却仍然没有实时跟进」；④ `User said (m22601)`「**你已经好几次没有实时更新任务面板了，全局AGENTS.md的约束还不够吗**」；⑤ `User said (m23631)`「菜单宽度这个没有更加优雅的写法吗，另外你好像又又又又又忘了更新任务面板进度」(「又」×5 ⇒ 用户已用字数计数)。⇒ 五次均为**执行过失**。现行硬规则：**任何一步完成后的第一个工具调用必须是 `todo_write`**，然后才允许启动下一步命令（可检查：面板更新时间戳必须早于下一个 bash/read）。

⚠️ **不得在真机核对前宣布完成、不得先催提交**:用户已明确纠正 —— `User said (m13805)`「你经常嘴上说完成要提交却没有给我真机校验先，而且任务进度会卡着」。**正确顺序是「真机核对 → 回写文档 → 展示改动集 → 提交」**。

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
