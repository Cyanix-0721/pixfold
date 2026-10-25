# D4 · MVP 闭环

> **来源**：`HANGOFF.md` 原有 §9 内容，原行号 L3329–L3900（2026-10-23 迁出）。
> **内容**：D4a/D4b MVP 闭环：真实文件闭环、预检、通知、元数据、Series 等。
> **性质**：实现期逐轮留痕，只查不常读。**产品决策仍以 `HANGOFF.md` 为唯一权威**，本文件是其详版。
> **索引**：见 [`docs/INDEX.md`](../INDEX.md)。

<!-- 原 HANGOFF.md L3329–L3900 -->

## D4：MVP 闭环

> **▶ 计划页预检已落地(2026-10-20 凌晨)** —— 预检的**时机**从「点执行才算」提前到
> 「进计划页就算」。
>
> **补的缺口不是「少一个功能」,是「算了却不给看」**:`PackPreflight` 早在 D4b 开局就落地
> (18 tests),`SpaceMarginal` / `EstimateIsLowerBound` / `Zip64Required` 三项**算得出来**,
> 但 `grep -rn "Zip64Required\|Zip64" --include=*.kt android/app/src/main/kotlin/` 唯一命中
> 是 `ui/library/PackExecutorPort.kt:80` 的一行注释 ⇒ **UI 层零渲染**。用户站在计划页时,
> 屏幕上只有页码预览,「这一卷需要 Zip64」「空间只剩一点点」一条都看不到,直到按下执行
> 才可能被拒绝(或更糟:顺利开写然后中途失败)。
>
> **同时修掉一个让 Zip64 判定形同虚设的硬缺陷**:`assessZip64Need` 的第三阈值
> `centralDirOffset` 在生产调用点 `domain/pack/PackPreflight.kt:149` **从未被传过**,
> 一直吃默认 `0L` ⇒「整卷超过 4 GiB」这条**永远不会触发**。真机 4.7 G/1600 页的单本
> 在本轮之前是**一条提示都没有**的。
>
> **三条设计裁决**:
> 1. **端口形状 `PackUiPreflight.Ready(result, availableBytes: Long?)` / `Unavailable(message)`** ——
>    领域层的 `PackPreflightResult` 要求 `availableBytes: Long`,表达不了「问不到」;
>    而界面必须区分「问不到空间」与「空间充足」:前者不该显示「够用」(那是承诺),
>    也不该显示「不足」(那是假的)。故 `null` 一路透传到 UI,由 UI 如实说「无法判断」。
> 2. **预检与执行不是两套判据** —— `PackExecutorPort.preflight` 的 KDoc 明确要求实现方
>    **复用 `execute()` 内部的同一组阈值常量**。两处各肉一份数值,改了一处忘了另一处,
>    离线测试全绿而真机上「准备页说能打、执行时被拒绝」。
> 3. **`execute()` 仍保留内部预检** —— 计划可能在「计划页」与「按下执行」之间被改动,
>    执行方不能信任调用方的「我已预检过」。
>
> **渲染分级**:阻断项 `colorScheme.error`,告知项 `onSurfaceVariant`。同一堆信息用同一种
> 颜色,用户分不清「点下去会怎样」。顺序固定:先全部 blockers,再全部 warnings,
> 最后(可选)「空间无法判定」。Zip64 的三个原因各有独立文案 —— 用户看不懂
> `CentralDirOffsetTooLarge`。
>
> **顺带修掉的真实缺陷**:`PixFoldApp` 里的 `packExecutor` **没有 `remember`**,每次重组
> 都新建一个 `SafPackExecutor`;若它进了 `LaunchedEffect` 的 key,「换了对象」就被当成
> 「条件变了」⇒ 预检被反复取消重启,每次重启都先把结论置 null。修法是把「对象是否稳定」
> 这个要求**从调用方收回到本层**(`rememberUpdatedState`),而不是给调用方再加一条纪律。
>
> **真机核对**(PJX110 / Android 16 / zh-CN):工作区选中主人放的 4.7 G / 1600 页单本,
> 计划页底栏上方逐字显示 `这一卷需要 ZIP64：整个包达到 4 GB 以上。仍会正常写出。`
> (`bounds="[42,2433][1222,2545]"`),探针读数 `est=5078821943 avail=33051258880
> vols=[e=1600 size=5078821943 zip64=Needed]`。
>
> **四道闸门**:app **675** / domain **888** / lint 零告警 / APK **34,224,026 B**;
> 变异 **M1–M7 全 KILLED**。⚠️ **M5 与 M7 首轮都存活**,两个都是**断言没有判别力**:
> M5(平台层预检写死 10% 阈值)当时没有任何测试能观察到阈值差异 ⇒ 把 `spaceSource`/
> `threshold`/`safetyMargin` 开成构造参数后杀掉;M7(执行器对象身份进 `LaunchedEffect`
> 的 key)首版测试让 `preflight` 立刻返回,两种实现最后都能显示结论 ⇒ 改成让 `preflight`
> **永不返回**后杀掉。**教训:断「结果对了」抓不到「路径错了」;要让被观察的差异在
> 观察窗口内持续存在。**

> **▶ 大归档实测 + 通知跳转 + 双层圆环进度弹窗(2026-10-20 白天)** —— 三件性质不同的事:
>
> **① 第 5 项「大归档写入」真机实测通过(4.73 GiB)**:此前只验过「预检能判出需要 Zip64」与
> JDK 层 70000 条目探针,**从未真写出过 4 GB 以上的包**。本轮用主人放进 `test` 的
> `[青水庵 (にやすけ 青水庵)] 子産み島/…2&3～今、お腹あいてるよ～`(4.7 GB / 1599 页)实测:
> 输出 CBZ = **5,078,971,778 B**(4.73 GiB),耗时约 3 分钟。结构取证(`dd` 取头 1 MB + 尾 718 KB):
> 普通 EOCD 的 `cd_offset = 0xFFFFFFFF`(Zip64 占位符)、entries=1600;**Zip64 EOCD Record 存在**
> (偏移 5,078,971,680,`record_size=44`)**`version_needed=45`**、`cd_offset=5,078,882,575`;
> Locator 在 5,078,971,736。**Kuro Reader Pro 打开成功**:`1 / 1599` → SeekBar 拖到末页 `1599 / 1599`。
> ⇒ JDK `ZipOutputStream` 在 `cd_offset > 4 GiB` 时自动写 Zip64 记录,阅读器正确跟随;
> 生产路径就是 `ZipOutputStream` + `setLevel(0)` + `entry.method = STORED`。
> **预检刻意不把 Zip64 当阻断项**(否则 4.7 G 的包会被彻底挡住)—— 这条也被这次实测证实是对的。
>
> **② 通知跳转缺陷(用户报)**:点实时活动/完成通知落到**首页**而非打包页。三层根因:
> `openAppIntent()` 只 `Intent(this, MainActivity::class.java)` 不带路由;`MainActivity` 是不读 intent 的空壳;
> `LibrarySteps` 没有「直接落到步骤 3」的能力。修法四层:`MainActivity` 读 `EXTRA_OPEN_LIBRARY_PACK`
> (**`onCreate` 与 `onNewIntent` 两个入口都要读** —— `FLAG_ACTIVITY_CLEAR_TOP` 复用已有 Activity 时
> 走后者,比前者更常见)、`PixFoldApp(openPackOnStart)` + `onPackRouteHandled` 回清、
> `LibrarySteps(startAtPackStep)` 用 `goToLibraryStep` 而非直接 navigate(后者会让返回栈变成
> `[Shelf, Pack]`,少掉元数据这一步)。
> **真机第二轮撞出的真缺陷**:首版 `var startAtPack by remember { mutableStateOf(openPackOnStart) }`
> **无 key** ⇒ `onNewIntent` 改了上层标志但下层 `remember` 不重播种 ⇒ **第二次点通知静默失效**。
> 变异 M3 首轮 SURVIVED 也是同一原因(`PlanWiringTest` 式直构组件绕过了 `PixFoldApp` 那一层)⇒
> 补端到端测试后杀掉。**教训:凡「上层请求 → 下层一次性动作」的接线,都必须有「动作已消费」的回清,
> 否则只有第一次生效。**
>
> **③ 打包进度改双层圆环弹窗 + 背景高斯模糊(用户裁决)**:模态对话框(必须等结束或点取消)、
> 只模糊弹窗后的应用内容、取消要二次确认。为此做了两件地基工作:
> - **页级进度透传**:内环要「当前卷的页进度」,而 `onProgress` 原本只在**卷间**回调 ⇒
>   `CbzWriter.writeStreaming` → `CbzWritePlan.writeCbz` → `PackRunner` → `PackExecutor` 逐层加
>   页级回调(domain 910 tests)。
> - **全站弹窗统一抽象** `ui/components/PixFoldDialog.kt`:此前工程里有 40+ 处裸
>   `AlertDialog`/`ModalBottomSheet`,要加「背景模糊」就得每处改一遍。
>   抽象里 `DialogBlurController` **用计数而不是布尔值**(嵌套弹窗:确认框里再弹错误框,用户关掉内层时
>   布尔值会把模糊撤掉而外层还开着)、`release` 钳到 0(否则负计数导致**背景永久模糊**)。
>   **真缺陷(两条既有测试抓出)**:首版 `DialogBlurHost` 写成
>   `if (!hasOpenDialog) { content() } else { Box { Box { content() } } }` ⇒ `hasOpenDialog` 一变,
>   `content()` 的调用位置从 slot 1 跳到 slot 3,Compose 视为不同子树而**整棵销毁重建** ⇒
>   弹窗的 `showDialog` 状态随 `remember` 一起丢失 ⇒ **弹窗一打开就自己消失**。
>   改为「Box 恒定存在,只让修饰符变」。**教训:宿主型组件里,`content()` 的调用层级必须恒定。**
> - 变异 M1–M4 全 KILLED;M5(模态语义改恒 true)**SURVIVED** ⇒ 按纪律删掉那条断言并写明理由
>   (Robolectric 没有「点 Dialog 外部」「Dialog 上的返回键」两条输入路径,`dismissOnClickOutside`
>   由真实窗口系统消费,写「构造了弹窗就算过」的断言只会是假保障 ⇒ **这条语义只能真机验证**)。
>
> **④ 弹窗共同父类 `PixFoldDialogShell`(用户裁决「9 也补上吧,写的更加抽象写让调用的几个都有共同的父类?」
> + 「我说的包括8」)**:上一版 `PixFoldDialog.kt` 里四个入口(`PixFoldDialog` / `PixFoldInfoDialog` /
> `PixFoldConfirmDialog` / `PixFoldAlertDialog`)**各自写了一遍** `Dialog { Surface { Column { … } } }` ——
> 外观一变就要改四处,而「四处能保持一致」纯靠人工。改为**四个入口全部委托同一个 Shell**:
> **Compose 没有 UI 继承,「共同父类」的唯一形态就是「委托到同一个 Composable」**。
> | 归 Shell 管(全工程只写一次) | 归调用方管 |
> | --- | --- |
> | `Dialog` 窗口 + `DialogProperties` | 标题长什么样 |
> | `Surface` 圆角/底色/高度 | 正文内容 |
> | 24dp 内边距与三段间距 | 按钮怎么排 |
> | 正文可滚动 + 背景模糊注册 | — |
>
> 两条形状决策:**`buttons` 收 `ColumnScope` 任意槽而不是 confirm/dismiss 两槽** —— M3 的
> `AlertDialog` 只给两个槽,摆不下「三颗全宽竖排」(撤销范围对话框:安全档/危险档/取消,取消必须最后);
> **`contentScrollable: Boolean = true` 开关** —— 正文自己已是 `LazyColumn` 时不能再包
> `verticalScroll`,实测 `IllegalStateException: Vertically scrollable component was measured with an
> infinity maximum height constraints`(设置页的单选弹窗正是这种),**这不是可选项而是崩溃点**。
> **模糊注册收进 Shell 的唯一出入口** —— 以前每层各自调一次,新增一个弹窗就多一个「忘了调」的机会。
>
> **⑤ 第 9 处 `ModalBottomSheet` 也补上模糊 `PixFoldBottomSheet`**:它与 Dialog **不是**同一种窗口
> (`ModalBottomSheet` 是同 window 内从底部升起的浮层,硬塞进 Dialog shell 会变成「从中间弹出、
> 盖满屏幕」),故并列一个 shell 而非复用;但两者**共用同一个 `DialogBlurController` 计数** ——
> 弹层上再弹对话框时关掉内层不会提前解糊。`sheetState` 交给调用方:关闭要走「先播收起动画、
> 动画结束再回调」两段,藏进函数内部就得自己起协程,「谁负责把浮层从组合里移除」立刻变含糊。
>
> **⑥ 8 条可执行检查(`PixFoldDialogShellTest`)+ 变异 M1–M6**:其中 **M6 首轮 SURVIVED 是关键教训** ——
> 「Shell 把 `contentScrollable` 传给 `Modifier.verticalScroll`」这条,直接测 `PixFoldDialogShell` 时
> 参数怎么错都还是同一个 Shell 在渲染 ⇒ 无判别力;改为**从四个真实便捷入口各自调用一次**,
> 断言外层 scroll 容器的**个数**(可滚 = 1、不可滚 = 0)后才 KILLED。
> **教训:测抽象层要经由真实调用方,否则抽象层的错在测试里也被自己吃掉了。**
>
> 四道闸门:app **701** / domain **910** / lint 零告警 / APK **34,339,570 B**。
>
> 四道闸门:app **693** / domain **910** / lint 零告警 / APK **34,323,186 B**。

> **▶ Series 三条语义(2026-10-21 凌晨)** —— 用户 2026-10-20 裁决的三条,全部落在领域层,
> 真机核对待补(设备凌晨自动锁 PIN,解锁后补验):
>
> **① 无系列层时 Series 跟随 Title**(原来是无建议)。**② 仅当卷目录作者名 `[xxx]` 与
> 工作区文件夹名一致时,Series 才取工作区文件夹名。** 两者合成 `ComicVolumesFrom.kt` 的
> `seriesSuggestionOf(collection, rootDisplayName)` 判定树(替换原 :88-92 的 if/else):
>
> 1. 有系列层 ⇒ `collection.seriesDerived.title`(原行为,来源「目录层级 L2(系列层)」);
> 2. 无系列,但卷目录 `derived.writerRaw` 与 `derivedNamesOf(rootDisplayName).writerRaw`
>    **都非空且相等** ⇒ 工作区名派生 title(新来源「工作区文件夹名(卷目录 [作者] 前缀与其一致)」);
> 3. 其余 ⇒ 跟随 Title(新来源「无系列层,跟随 Title」)。
>
> **判据刻意用原始方括号前缀比对而非清洗值** —— 清洗会把 `[社团 (画师)]` 的内部括号剥掉,
> 整体包裹形态会被误判成「与工作区名一致」。
>
> **③ series 下出现嵌套目录 ⇒ 书架页发警告。** `SourceGroups.kt` 新增
> `nestedDirWarnings(collections): List<Msg>` —— 判据 = 集合的 `seriesKey` 含 `/`
> (系列与单本之间夹了中间层);按 seriesKey `distinct` + `naturalCompare` 排序(与集合排序
> 同一口径,纯字典序会把 `卷2` 排在 `卷10` 前);每条 `Msg(TextKey.NestedSeriesDir, [系列名,
> 嵌套层])`,嵌套层是**完整中间路径**(可能是多级,原样带出)。UI 落点 = 书架页
> (`LibraryPage`)摘要与列表之间常驻 error 色警告条,参数 `workspaceWarnings` 默认空 ⇒
> 既有测试零改动。
>
> **⚠ 中英占位符顺序事故(本轮第三次踩)**:`TranslationCoverageTest` 三条契约连环抓
> (「中英占位符逐位相同」「下标从 1 起按出现顺序连续」)。终态 = 英文
> `Nested folder %1$s under series %2$s`、中文 `系列 %1$s 下出现嵌套目录 %2$s`、
> args = `[系列名, 嵌套层]`,三处(模板/args/测试断言)必须同步。
>
> **排序用例的判别力手法**:夹具用 甲(U+7532)/乙(U+4E59) —— 码点序「乙」在「甲」前,
> 若实现退化成纯 `sorted()` 结果碰巧相同则无判别力。
>
> 四道闸门:app **704** / domain **920** / lint 零告警 / APK **35,024,674 B**。

> **▶ 元数据默认值三改判 + chip 六项规格(2026-10-21)** —— 用户在这一轮连着提出七条,
> 按性质分两类:三条是「默认值该从哪来」,六条是「芯片行的形态」。
>
> **一、writer 默认值取清理后形态。** 用户原话:「如果没有 ComicInfo 让你获取 writer,
> 就默认获取去除 [] 的」。`ComicVolumesFrom.kt` 的 `writerSug` 从 `writerRaw` 改为
> `writerCleaned`(来源常量改名 `WRITER_SOURCE_DIR` → `WRITER_SOURCE_CLEANED = "目录名开头的
> [作者] 前缀(已去掉方括号)"`)。`writerCleanedSug` 仍与之同值(来源不同)⇒ **「一键清理」
> 在本路径上变幂等**,按钮保留 —— ComicInfo.xml 里的作者仍可能带方括号。
>
> **二、建议值优先级 ComicInfo > 目录层级。** `applyExistingComicInfo` 原本只改**当前值**,
> 用户裁决「3 优先看 comicinfo 的,其次目录层级」⇒ 扩为当前值与建议值都以文件为准
> (`COMIC_INFO_SOURCE = "已有 ComicInfo.xml(优先级高于目录层级)"`)。文件里没写的字段
> 建议值仍取自目录。
>
> **三、单本 Series 跟随 Title。** 用户原话:「单本 series 建议值跟随 ComicInfo 后一个选取
> Title,体现在恢复建议后和 Title 一致」⇒ `seriesSug` 三分支里 `seriesDir.isEmpty()` 时
> 取 `titleSug.value`(而非重新从目录算),保证「恢复建议」按下去 Series 与 Title 同值。
>
> **四、修本轮引入的回归:单本被判成系列。** 条一让 `series` 当前值恒非空,而
> `ComicVolume.seriesKey` 原定义是 `get() = series`(当前值)⇒ `VolumeGroups.isSeries` 恒真
> ⇒ 5 个单本显示成「5 个系列」。修法 = 新增 **`seriesDir`**(扫描层的系列目录名),
> `seriesKey` 改指它。判据从「用户可见的当前值」回到「扫描时的目录结构」。
>
> **五、chip 六项规格**(用户原话:「chip 跟随内容长度变化,不过要有长度限制,比如最长 10
> 个字(过长则显示 title 首尾);然后内容文字要居中;第一层和第二层在 UI 上区分开,比如加个
> 遮罩;第一层 chip 最多显示 2 行(指 chip),多的加个屏框的下箭头长按钮替代现在的文字按钮;
> chip 内部可换行(指 chip 内容,和前面的最多 2 行不冲突);chip 瀑布流显示」):
>
> - **截断保首尾**:`truncateChipLabel(label, maxChars = CHIP_LABEL_MAX_CHARS = 10)` ——
>   纯尾部省略会让同系列两本(差别在卷号或 `[DL版]`)截成一模一样,`maxChars = 10` ⇒
>   首 5 + `…` + 尾 4。
> - **截断只作用于名称,后缀完整保留**:`chipSuffix` 独立通道。首版把「(未勾选)」拼进
>   label 再截断,`PackPreviewPageTest` 立刻红 —— 名字越长越容易把标记砍掉,而长名字正是
>   最需要看标记的场合。
> - **文字居中**:`Text(textAlign = TextAlign.Center)`。**不是芯片在行内居中** —— 用户两次
>   澄清「居中是 chip 内部文字居中,而不是 chip 本身居中」。
> - **行内左对齐**:`Arrangement.spacedBy(6.dp)`。瀑布流的本义是从左上起排、各行左端对齐。
> - **两级遮罩**:1 级 0.45 / 2 级 0.18 的 `surfaceVariant`,`padding(horizontal = 8.dp)`
>   让它成为「底托」而不是横贯屏幕的分隔条。用户两次反订:「你这个遮罩都顶到边缘了很难看」
>   「只有第一层又第二层又没有也很难看」。
> - **1 级恒 2 行 + 全宽下箭头长按钮**:`TOP_CHIP_MAX_LINES = 2` 恒定(用户否掉旧的「选中
>   系列后收缩到 1 行」)。溢出入口从文字按钮改成 `fillMaxWidth` + `ic_arrow_drop_down`。
>
> **六、一键清理与恢复建议改纯图标 + 长按提示。** 文字按钮 4 个汉字约 72dp,6 个按钮叠加
> 输入框后行宽不够(真机「输入框被挤得很窄」的直接原因);改图标后每个 48dp。图标经
> `android/tools/svg2vd.py` 从 Material Symbols 官方 SVG 机器变换,不手抄路径。
>
> **七、`AppNavigationTest` 缺 `qualifiers`(真缺陷)。** 该文件是全仓唯一没写
> `@Config(qualifiers = "w400dp-h1000dp")` 的大屏测试 —— Robolectric 默认屏 320×470dp,
> 比真机 361×794 矮得多,芯片行多占的高度把 `TAG_GRID` 顶出视口 ⇒ 2 条测试红。补上
> `qualifiers` 后 12/12 绿。**这不是测试迁就实现**:默认屏本来就不代表目标机型。
>
> 四道闸门:app **731** / domain **925** / lint 零告警 / APK **34,357,115 B**。
> 变异 M1–M12 全 KILLED(M7「去掉下箭头」首轮 SURVIVED ⇒ 补结构性断言后杀掉;
> M10 首轮替换串未匹配属无效变异,重跑后 KILLED)。
> 变异 M1–M7 全 KILLED(M1 删有系列分支 2 红 / M2 删工作区名分支 4 红 / M3 嵌套阈值
> 1→2 5 红 / M4 排序退字典序 2 红 / M5 前缀比对改清洗值 2 红 / M6 警告条只渲染第一条
> 1 红 / M7 删去重 3 红)。

> **▶ 进度读数合并 + 元数据「按目录结构获取」(2026-10-22 凌晨)** —— 两条都是走查里
> 用户看出「已经实现了但还是不对」的场合。
>
> **一、进度中心读数是两层合并值,不是卷级百分比。** 用户原话:「内圈的百分比根据双层
> 共同计算取值吧,不如外圈没转完进度一直 0」。卷级进度只在**整卷写完**时 +1 ⇒ 一卷 1599
> 页时,前 1598 页中心读数一直是 0%,然后直接跳到 25%,看起来像卡死;而外环明明在
> 缓慢推进 —— 两处读数互相矛盾。修法 = `TaskProgress.percentWithPage(pageDone, pageTotal)`
> 把「已完成整卷数 + 当前卷页比例」一起算:`((base + pageFraction) / total * 100f)`。
> 页级数据缺失(或 `pageTotal <= 0`)时退化成纯卷级百分比。**同一函数上没有「满了提前
> return 100」的分支** —— 变异实测那是等价判据(`base / t = 1f` 再叠加任何页比例都被末尾
> `coerceIn` 夹住),按纪律删掉死判据并把理由写进 KDoc。
>
> **二、「一键清理」按钮被删,因为它和默认获取逻辑已经一致。** 用户原话:「一键清理作者的
> 按钮多余了？因为现在清理和默认的自动获取逻辑一致」。writer 的默认建议值这一轮已改成
> 清理后形态(去方括号)⇒ 在真实扫描路径上 `applyWriterCleaning` 恒等变换。它只在
> ComicInfo.xml 里的作者带方括号时还有差别 —— 而那种情况用户直接改输入框就行。
>
> **三、新增「按目录结构获取」,挂在页签行「重置全部」旁边,作用于当前卷全部字段。**
> 用户先说的是「右上角重置旁边」,我第一版只做到 Writer 行里(只管作者)⇒ 用户反订
> 「我说的按目录结构获取是右上角重置旁边,不是只给作者用」。
>
> - 它与「恢复建议」是**两件事**,必须并存:恢复建议取的是**当前建议值**(可能已被
>   ComicInfo.xml 覆盖),「按目录结构获取」**只从目录名重新派生**、完全绕开文件。
> - 为此 `ComicVolume` 新增两个目录快照 `dirTitleSug` / `dirSeriesSug`(与既有的
>   `dirWriterSug` 并列),并在 `applyExistingComicInfo` 里**刻意不动**这三个字段 ——
>   一旦它们被文件值顶掉,用户在「ComicInfo 写错了」这个**最有用的场合**点这个按钮
>   会发现毫无变化,只会以为按钮坏了。变异 M2 首轮 SURVIVED 证明这条契约此前零覆盖,
>   补断言后 KILLED。
> - 改动**进 `overridden`**,与「恢复建议」同构:它们都是「用户显式选了这一项的值」。
> - 确认弹窗不可省(`PixFoldConfirmDialog`):一次点下去会覆盖 7 个字段,包括用户手改
>   过的那些 —— 这是「破坏性动作可解释」约束的落点。
>
> **四、修出一处真缺陷:`MetadataPage` 的本地状态用无参 `remember`。** 页签行的按钮改的是
> 上层的 `LibraryState`,而该页 `var state by remember { mutableStateOf(libraryState) }`
> 只在首次组合时播种 ⇒ **输入框看起来毫无反应**。这与 D2a 那轮「热切换工作区后白屏」
> 是同一个成因(上层给新状态、下层 remember 不重新播种)。改为 `remember(libraryState)`,
> 不会丢本地编辑 —— 每次编辑都经 `update` 同步回上层。
>
> **五、分类:`(1)` 后缀不是缺陷。** 默认 `conflictPolicy = OutputConflictPolicy.Rename`,
> `outputExists && Rename` 时输出名变 `单本A (1).cbz`。我一度当成「同名覆盖失效」,
> 是自己看错了报告路径与磁盘文件名的对应关系。
>
> 四道闸门:app **736** / domain **933** / lint 零告警 / APK **35,043,142 B**;
> 变异 M1(`applyDirMetaSuggestions` 改用被覆盖的建议值)4 红、M2(dir 快照被 ComicInfo
> 覆盖)1 红,均 KILLED。
> **真机核对待补(2026-10-22 白天)** —— 用户当晚裁决「做到真机就可以关机了,真机是明天
> 继续的」。
>
> **真机核对已完成(2026-10-22 上午)**:「按目录结构获取」按钮位置/七字段重填报/确认弹窗
> 全部通过(「已覆盖 7 个字段」);进度中心读数为两层合并值(卷 `0 / 1` 时显示 **79%**、
> 页 `1275 / 1599`);4.73 GiB 单本产出 **5,078,971,824 B**。⚠️ 连采 6 次读到 0% 是
> **采样假象**(`uiautomator dump` 抓不到弹窗),改用 `screencap` 才拿到真值。
>
> **▶ 元数据空标签统一 + 可选字段缺失警告 + chip 字符上限 (2026-10-22 上午)** ——
> 一次走查三件事,前两件彼此牵连。
>
> **一、五个字段统一写自闭合空标签,`<Number>` 不再是例外。** 用户先裁决「所有标签为空时
> 都应该不写入,目前只有卷号实现了」,我照做后又改回:「还是空值写自闭合标签吧,几个标签
> 全部都改」。终态 = `field(tag, value)` 空值一律 `  <Tag />`,字段序固定、每份 XML 五个
> 位置齐全。**判据从「某字段特例」回到「统一」**:读者(人或工具)不必先记住哪个字段可能
> 消失。D1 起 `<Number>` 省略的理由(「`<Number />` 会被读成第 0 卷」)是猜测性的,
> 若日后证实某阅读器确有该行为,须在文件头写明具体机型/版本再单独处理。
> 配套改判 4 条旧用例(断言「空值自闭合」的那几条口径不变,只是把 `<Number />` 也算进来),
> 并保留「字段为空仍给待确认提示」这条独立判据。
>
> **二、可选字段缺失警告从 1 项扩到 4 项。** 用户原话:「其他几个可选项缺失的警告补上」。
> `ComicVolume.pendingIssues` 新增 `PendingTitle` / `PendingSeries` / `PendingWriter`
> (与 `PendingVolume` 并列),文案「标题缺失」/「系列缺失」/「作者缺失」。判据用 `isBlank()` 而非 `isEmpty()` —— 只填空格
> 在界面上看着像填了、写进 XML 却是空白(变异 M4 专打这条)。**语言仍不在此列**:
> `Unset` 是合法终态(2026-10-20 裁决),而这三项空值通常意味着目录名没解析出来。
> ⚠️ **标题这一项我漏过一轮**:首次只加了系列 / 作者,理由是「标题几乎总能从目录名得到」
> —— 这条**站不住**,因为 `titleSug` 给的只是**建议值**,Title 是一块可编辑输入框,用户可以
> 把它清空,清空后 XML 里就是 `<Title />`。判据必须是「当前值是否为空」,不能拿「通常能
> 解析出来」当豁免。2026-10-22 中午补 `PendingTitle`,顺序改为
> **Title → Series → Volume → Writer**(与 XML 标签序一致);真机清空标题后读到
> **「待确认：标题缺失 / 卷号缺失」**。
> 三处 UI(`LibraryPage` / `MetadataPage` / `PackPreviewPage`)已在消费 `pendingIssues`,
> 零改动自动生效。
>
> **三、chip 字符上限 10 → 24。** 用户原话:「显示太短了,首尾几个字都要显示,太长中间可以
> 省略,且chip内容可以换行显示」。原值 10 的前提是「芯片单行、宽度跟随内容」—— 那时首 5 +
> … + 尾 4,`[青水庵 (にやすけ 青水庵)] 子産み島2&3～今、お腹あいてるよ～` 在真机上被压成
> `子産み島2…～(2)`,同系列两本照样分不清。现在芯片内文字允许折到 3 行 ⇒ **高度方向已有界,
> 宽度上限就该放宽到「够看出一本书是谁」而不是「够排进一行」**。
> 真机验收:超长系列名显示为 `超长系列名称开头标识符A…末尾标识符XYZ（2）`,首尾标识符
> 都在、中间省略、chip 内折成 2 行 —— 三条同时成立。
>
> 四道闸门:app **736** / domain **937** / lint 零告警 / APK **35,044,494 B**;
> 变异 M1(删 PendingSeries)2 红、M2(删 PendingWriter)2 红、M4(`isBlank` 退化成
> `isEmpty`)1 红、补标题后 M1(删标题判据)2 红 / M2(判据挪末尾破顺序)1 红,均 KILLED。
> 真机另验:「无前缀无系列单本」卷显示 **「待确认：卷号缺失 / 作者缺失」**。

> **▶ 字段级红色警告:缺失要标在出错的那一格上 (2026-10-22 深夜)** ——
> 需求我先理解错了一轮。用户说「你忘了卷号外的标签也补上缺失警告」,我补的是**页头汇总**
> 那一行(`pendingIssues` → 「待确认：标题缺失 / 卷号缺失」);用户随即纠正:
> 「我说的不是顶部的待确认,而是**卷号那样空值的红色警告**」。
>
> **差别不是装饰。** 卷号字段一直有 `isError` + `supportingText` —— 红边挂在**出错的那一格**
> 上,旁边直接写「卷号缺失」。汇总行要求用户把视线从输入框挪到页头、再回头对照哪一项,
> 中间还要自己完成「页头上第三项是 Title」这种映射。字段有几块,红标记就在几块上,
> 不用对照。
>
> **一、判据只定义一次。** `ComicVolume.pendingIssueOf(field: MetaField): Msg?` 是
> 「字段 ↔ 待确认项」的唯一映射;`pendingIssues` 改成
> `PENDING_FIELD_ORDER.mapNotNull { pendingIssueOf(it) }`。两处都从这里取 ——
> 若各写一份「什么算缺失」,迟早出现「页头说标题缺失、标题框却不标红」这类
> **用户看得见的分歧**(它不会让测试红,只会让界面自相矛盾)。
> `PENDING_FIELD_ORDER` = Title → Series → Volume → Writer;语言/CBZ 名/输出目录
> 刻意返回 `null`(语言「不写入」是合法终态,另两项各有默认值链路)。
>
> **二、`TextFieldRow` 补 `isError` + `supportingText`,与 `VolumeNumberRow` 同构。**
> 一句话改动:`val pending = volume.pendingIssueOf(field)` → `isError = pending != null`
> 且 `supportingText = pendingText?.let { { Text(it) } }`。
>
> **三、断言口径换了一次,这条要记住。** 首版断言 `SemanticsProperties.Error` 等于
> 「标题缺失」,实测得 **`Invalid input`** —— M3 的 `isError` 恒定写那一句,与具体缺什么
> 无关(它只是"有错"的机器标记)。而 supportingText 因 `mergeDescendants` **并进了同一个
> 字段节点**(探针实测 `Text : [Title, Title missing]`)。终态断言 = 读该字段节点的
> `SemanticsProperties.Text` 列表,要求其中含「标题缺失」。
> **教训:断言前先用探针把真实语义树打出来,别按 API 名字猜它填什么。**
>
> **闸门与变异**:`app 741` / `domain 940` / `lint` 零告警 / APK **35,625,226 B**。
> M1(`TextFieldRow` 不设 `isError`)3 红、M2(`pendingIssueOf` 的 Title 分支恒 `null`)
> 3 红、M3(页头汇总顺序改动 = 不走同一判据)2 红,均 KILLED。
>
> **真机**(PJX110 / Android 16 / zh-CN):清空 Title 后该输入框红边、其下红字
> **「标题缺失」**,页头同步「待确认：标题缺失 / 卷号缺失」,Series 无红。

> **▶ 元数据页四项改判(2026-10-20 凌晨)** —— 用户在一次走查里连着提出四条,彼此有关联:
> 前两条都是「元数据不该凭空造值」,后两条是「已有值不该被无视」与「不该逼用户确认」。
>
> **① 卷号为空 ⇒ 省略 `<Number>` 标签(不是写空标签)。** 原实现走统一的
> `field(tag, value)`,空值一律输出自闭合 `  <Number />`。但卷号与其它字段不同:
> 空卷号不是「用户把它填成了空」,而是「这一卷没有卷号」。ComicInfo 规范里
> `<Number>` 是可选的,省略即无卷号;而 `<Number />` 会被部分阅读器读成
> 「第 0 卷」或空字符串卷号。**同时保留「卷号缺失」的待确认提示** —— 用户原话
> 「空的时候卷号缺失的提示仍然保留别删」:省略标签是**输出**层面的正确性,
> 提示是**输入**层面的提醒,两者不冲突。其余字段仍写自闭合空标签(它们空着就是
> 「这个字段没有内容」,不需要特殊处理)。
>
> **② `LanguageISO` 从六档缩到三档:`Unset` / `Zh` / `Ja`。** 用户原话「LanguageISO
> 多出了其他 ISO,这个没需求,未知没必要,因为和不写入重复了,未设置在应用里就不需要了吧,
> 只要有 zh/ja/不写入标签就够了」。逐条对应:`Other`(自由输入 ISO 639-1)= 没有需求;
> `Unknown` 与 `Skip` **与 `Unset` 表达同一件事**(都是「不写入标签」)⇒ 三个词让用户
> 先猜它们的区别;`Unset` 的文案从「未设置(需确认)」改为「不写入标签」。
> **连带推翻 D1 验收第 12 项**:`Unset` 从「待确认」变成**合法终态**,
> `ComicVolume.pendingIssues` 因此**只剩「卷号缺失」一项**。理由:「不写入」是一个
> 用户可以明确做出的选择,不是待办;把合法选择挂在「待确认」上会让每一本书永远带一个
> 清不掉的黄点。
>
> **③ 已有 `ComicInfo.xml` 作为「当前值」,但「建议值」仍取文件夹。** 用户原话
> 「元数据页的内容如果 ComicInfo.xml 没写就默认是空的,但是恢复建议是通过文件夹结构和
> 过滤规则获取的,这点和无 ComicInfo 的一致」+「Series 默认取 series 文件夹名,如果
> ComicInfo 已有值就直接取,但是恢复建议还是取 series 文件夹名称」。⇒ **两条来源必须
> 分开**:`title`/`series`/`writer`/`volume`/`language` 的**当前值**优先取文件里的值
> (没写就是空),而 `titleSug`/`seriesSug`/... 一律仍由文件夹结构与清洗规则推导。
> 这样「恢复建议值」按钮的行为与「没有 ComicInfo」时**完全一致**,用户不必理解两种模式。
> 落地:`domain/comic/ComicInfoReader.kt` 的 `parseComicInfo`(纯解析,零 Android 依赖)
> + `applyExistingComicInfo`(把解析结果并进卷) + `ExistingComicInfoProbe` 端口,
> 平台实现 `SafExistingComicInfoProbe` 读同名 CBZ 里的 `ComicInfo.xml`。
> **对账仍按「值 ≠ 建议」判人工改过** —— 从文件读来的值恰好等于建议值时,它本来也
> 不需要保护。
>
> **④ 元数据输入框从单行改多行软换行(修「拖不动光标」)。** 用户原话「元数据当内容
> 超过输入框的时候拖拽光标无法移动光标过去,输入法的光标移动可以」。根因不是 Compose 的
> bug:`singleLine = true` 下文本超出宽度后是**水平滚动**的,而长按拖动选择/移动光标
> 在这条路径上与父级的水平滚动手势竞争 ⇒ 拖到视口外就没有落点。改成多行软换行后
> 文本换行显示、不再横向滚动,拖动目标始终在视口内。**代价**:每行高度可变(长标题占两行),
> 用 `minLines` 保持空框高度与原先一致,避免整页跳动。
>
> **四道闸门**:app **677** / domain **907** / lint 零告警 / APK **34,236,654 B**。

> **▶ D4 分两段(2026-09-21 用户确认后细化)**:
> - **D4a UI 层真实功能**(进行中):设置页 / 主题体系 / i18n / 开源许可页 ——
>   **不依赖文件系统**,故可先于 D2b/D3 推进;
> - **D4b 真实文件闭环**(未开始):扫描 → 排序 → 元数据 → **真实产出 CBZ** ——
>   **硬依赖 D2b(平台能力)+ D3(适配层)**,是真正的 MVP 出口。
>
> **▶ 提前启动(2026-09-21 用户确认)**:自 2026-09-20 起的
> **设置页 / 主题体系**(以及其后的"基础 UI 调整")属 **D4 MVP 的提前启动**,
> **不计入 D1 的六项交付**。这解释了"D1 清单已 20/20,却仍在往里加功能"的现象 ——
> 加的是 **D4 范畴**的能力,只是提前做了。
>
> **打包(真实产出 CBZ)仍留 D4**(用户 2026-09-21 确认),
> 即 MVP 端到端闭环**尚未开始**;当前 D4 提前启动的部分是 **UI/交互层**,
> 真实文件系统、SAF 读写、真实打包产出**都还没有**。
>
> **依赖关系提醒**:D4 完整闭环依赖 D2b(平台能力)与 D3(领域核心)的落地;
> 现在提前做的是**不依赖真实文件系统的那部分 UI**(与 D1 的做法一脉相承)。

> **✅ 2026-10-19 凌晨:D4b 的执行编排内核已落地(领域层,零 Android 依赖)**
> ——`domain/pack/PackRunner.kt`,26 项单测。这不是「D4b 完成」,而是把 D4b 里**唯一还不依赖真机**的那一块钉死。
>
> **补的是什么缺口**:D3 已交付 `buildCbzWritePlan` + `writeCbz`(两块拼图),D2a 交付了「从 SAF 读字节」的端口,
> 但**没有东西把它们按卷串起来**。散着调用会出三件事:①`writeCbz` 缺页时「一个字节都不写」,
> 可那时目标文件**已经被创建**了 ⇒ 磁盘上留下 0 字节 CBZ(阅读器打开不报错、只显示空白);
> ②中断只能在卷间生效,一半卷写完才取消会让报告与实际不符;③没有编排就没有 `TaskProgress` 的来源。
>
> **四条硬规则**:
> 1. **先探测、后开流**:缺页时**连输出流都不打开** —— 这是「不在用户目录留 0 字节 CBZ」的唯一保证。
>    (探测比写入多做一次,但比留一个假文件便宜得多。)
> 2. **写盘失败必须清理半成品**:`CbzOutputSink.discardFor(plan)` 是端口的一部分,
>    不是可选优化 —— 清理者必须尽力而为、**不抛异常**(它是在失败路径上被调用的,再炸一次只会盖掉原始错误)。
> 3. **中断只在卷间检查**:已经开始写的那一卷**必须写完** —— 半途硬停会留下半个 ZIP,比多花一秒钟糟得多。
>    `PackRunReport.interruptedAt` 记录「第几卷开始前停下」,`isComplete` 因此为 `false`;
>    而**有失败项时 `isComplete` 仍为 `true`** —— 失败是「处理过了但没成功」,不是「还没处理」,
>    这条区分决定了界面该说「已完成,其中 N 卷失败」还是「已取消,还剩 M 卷」。
> 4. **`timeMillis` 必须由调用方显式给定且为正**:取「现在」会让同一份输入产出不同字节,测试与增量比对都做不了。
>
> **顺带修正了一个真缺陷**:`TaskProgress` 原先把「跳过」并进 `failed`,
> 于是「跳过了 1 卷」在界面上会被读成「1 卷失败了」—— 纯粹误导。现拆出独立的 `skipped` 字段,
> **进度**仍把它算作「已处理」(否则进度条会卡在 0%),但**报告**把它与失败分开计数。
>
> **归责边界**:归这里 = 按卷顺序执行、探测/开流/清理的调用决策、中断检查点、进度与报告;
> 不归这里 = 字节从哪来(`PageByteSource`)、流怎么开(`CbzOutputSink`)—— 两者都是 `fun interface`,
> 平台层用 SAF 实现、测试用内存实现。**平台层实现(SAF 建文件 / 删半成品 / 真实落盘)与真机验证仍待。**
>
> **闸门**:`domain 590`(+22)/ app 554 / lint 零告警 / APK 34,421,078 B;变异 **M1–M6 全 KILLED**
> (M1 缺页也开流、M2 写盘失败不清理、M3 中断检查挪到卷尾、M4 skipped 不推进进度、
> M5 失败不推进进度、M6 `isComplete` 恒 true)。
> ⚠️ 首轮 2 条测试红暴露的是**夹具 bug 而非实现 bug**:夹具让所有卷共用同一批 `itemId`,
> 于是「让 A 卷缺页」把 B 卷也一起判缺 —— 断言写对了、夹具错了。**教训:夹具的标识必须带作用域前缀。**
> **✅ 2026-10-19 凌晨:D4b 的执行前预检已落地(领域层,零 Android 依赖)**
> ——`domain/pack/PackPreflight.kt`,18 项单测。`SpaceCheck`(22 tests)与 `Zip64Assessment`(22 tests)
> 早在 D2b 就落地并各自单测通过,但**从未被任何生产代码调用过** —— 它们只是两个孤立的工具函数。
> 而本节把「1GB 级归档写入……空间预检 + 明确拒绝 + 引导到电脑」列为验收标准,
> 意味着这两个函数必须在**真正开始写之前**被组合起来使用。
>
> **组合它们不是简单相加,有三个只在「放在一起」时才出现的问题**:
> 1. **预估的可靠性取决于尺寸数据在不在**。SAF provider 的 `COLUMN_SIZE` 可以为 `null`(平台层按 0 处理),
>    此时 `estimateCbzSize` 算出来的是**下界**而不是估计值。拿一个下界去判「空间充足」是拿用户的数据冒险 ⇒
>    凡有卷尺寸未知,必给 `PackWarning.EstimateIsLowerBound(volumeCount)`。
> 2. **空计划不该报空间不足/临界**。`checkSpace(available, 0, …, safetyMargin > 0)` 会返回 `Marginal` ——
>    一「没有东西要打包」的任务被告知「空间临界」是荒唐的 ⇒ 计划全为空/全被跳过时**干脆不判空间**。
> 3. **Zip64 不是拒绝的理由,是告知的理由**。Zip64 是标准扩展,`CbzWriter` 已知条目大小
>    (不需要未知长度流式 STORED),能正常写出。真正要拒绝的只有**空间不足**。
>    把 Zip64 当阻断条件会让用户在一份大归档上完全无法打包 ⇒ Zip64 只出 `PackWarning`。
>
> **结论形状**:`PackPreflightResult(volumes, blockers, warnings, estimatedBytes, availableBytes)`,
> `canProceed` **只看 `blockers.isEmpty()`** —— 提示项永不影响能否继续。阻断项目前只有
> `PackBlocker.SpaceInsufficient`(带 `shortfallBytes`,让界面能说清「还差 1.2 GB」而不是笼统「空间不足」);
> 提示项有 `SpaceMarginal` / `EstimateIsLowerBound` / `Zip64Required(逐卷,带原因)`。
> **一次给出全部问题**而非只报第一个 —— 否则用户会陷入「改一个 → 再试 → 又冒一个」的循环。
>
> **跳过卷不参与判定**:不产出字节、不打开流,拿它的 Zip64 或空间拦住用户毫无意义。
>
> **归责边界**:归这里 = 把计划折成「总预估 + 每卷条目统计」、组合两个既有判定、分出阻断/提示;
> 不归这里 = 可用空间数字从哪来(平台层 `StatFs`/SAF)、页尺寸从哪来(扫描层已填 `sizeBytes`)、
> 以及**要不要继续**(那是 UI 的事,这里只给结论)。**阈值策略刻意不设默认值** ——
> 具体数字待真机验证后定(§11 第 12 条,已于 2026-10-19 定为 `Percentage(10)` + `safetyMargin = 32 MiB`,
> 落点是 `SafPackExecutor` 内部常量而非领域层默认参数 —— 领域函数仍保持「必须显式给出」的形状,
> 这样换一个存空间更小的目标(如 FAT32 外置卡)时可以给不同值而不用在领域层开洞)。
>
> **闸门**:`domain 608`(+18)/ app 554 / lint 零告警 / APK 34,421,078 B;变异 **M1–M7 全 KILLED**
> (M1 空计划也走空间判定、M2 跳过卷也参与、M3 Zip64 变成阻断条件、M4 尺寸未知不警告、
> M5 条目数漏算 ComicInfo、M6 空间临界也变成阻断、M7 预估漏算元数据开销)。
> ⚠️ 变异脚本自身也踩了两个坑:①首轮「变异生效」判定用「旧串消失」—— 但 M3 的新串**包含**旧串作前缀,
> 于是判定误报「没生效」并把 M3 残留在源码里(第二次跑测试才看到);正确判据是**文件内容确实变了**。
> ②首轮 3 条测试红又是**夹具 bug**:mock 卷只有几张页,`take(pages)` 造 7 万页时被静默截断,
> 于是「需要 Zip64」的用例永远不触发 —— 断言对了、夹具错了。**教训:夹具必须能造出断言所需的规模。**
> **✅ 2026-10-19 凌晨(续):D4b 的「预检 → 执行」闸门已落地(领域层,零 Android 依赖)**
>
> `domain/pack/PackGate.kt`(8 tests)。补的缺口:上一段的 `preflightPackPlans` 虽然单测通过,但它与真正的写盘 `runPackPlans` 之间**没有任何代码联系** —— 「预检拒绝就别写」此前只是注释里的承诺。后果不是理论风险:`runPackPlans` 一旦被调用就逐卷 `openFor` + 写字节,预检结论被无视时,用户会在**已经知道空间不足**的情况下看着程序写到一半失败,并在目标目录留下若干半成品。
>
> 故新增 `runPackPlansChecked(...) : PackCheckedOutcome`,两种结果:
> - `Rejected(preflight)` —— 有阻断项,**一个输出流都不打开、一个页面字节都不读**(`runPackPlans` 根本不会被调用);
> - `Ran(preflight, report)` —— 预检放行并已执行,预检明细与执行报告一起带回。
>
> 四条设计要点:
> 1. **断言的是「一次都没打开」而不是「打开了但没写」** —— 打开本身就已经在用户目录里创建了文件,那正是 `writeOne` 里「先探测后开流」要避免的事。
> 2. **接口形状防「忘了预检」**:参数 = `runPackPlans` 全部参数 + 可用空间/阈值/安全余量。想真正写盘就必须提供可用空间与阈值,提供它们就意味着预检已经发生了。
> 3. **拒绝时原样带回 `preflight`** —— `SpaceInsufficient.shortfallBytes` 让界面能说清「还差 1.2 GB」。
> 4. 预检只阻断 `blockers`;**`Marginal` 与 `Zip64Required` 放行**(有独立用例)。空计划不被空间临界拦住;放行后中断令牌仍然生效。
>
> ⚠️ **首轮 2 条测试红又是夹具 bug**:`base.copy(pages = items)` **不会联动 `pageOrder`** ⇒ 预检按旧页数算,于是「Zip64 用例」既不够大、「临界用例」也不临界。修法 = 显式 `pageOrder = pageOrderOf(items)`。**教训:`ComicVolume.copy` 的 `pages` 与 `pageOrder` 是两个独立字段,改一个必须同时改另一个。**
>
> 闸门:domain **616**(+8)/ app 554 / lint 零告警 / APK 34,421,078 B。变异 **M1–M3 全 KILLED**(M1 删掉拒绝分支 / M2 预检传空计划 / M3 拒绝时也跑执行)。

> **✅ 2026-10-19 凌晨(再续):D4b 的端到端集成测试已落地(领域层)**
>
> `domain/src/test/.../pack/PackEndToEndTest.kt`(18 tests)。这一层与 `PackRunnerTest` / `PackPreflightTest` / `PackGateTest` 的分工:**那三个用的都是手造的、形状被裁剪过的对象**(2 页的卷、1000 字节的页),断言点单一、好定位;而真实链路的风险恰恰在**数据形状**上 —— `MockDataB` 的卷是 12–20 页、每页 800KB–3.8MB(预检会算出 MB 级估算)、有一卷 `outputExists = true`(默认 Rename 策略会改名,这个名字最终决定 ZIP 内容归属)、`cbzFileName` 是「标题 + 第NN卷」拼接且含中文与空格。
>
> 断言口径:不断「ZIP 里有什么内容」(那由 `CbzWriter`/`CbzWritePlan` 的测试负责),只断**链路产物** —— 卷数对得上 / 每卷写出非空字节 / ZIP 结构合法(局部文件头 `PK\x03\x04` + 中央目录结束记录 `PK\x05\x06`)/ 进度单调推进到 100% / 报告计数与计划一致 / 重命名与跳过两策略下输出名与写出集合都对。
>
> 三条「真实数据才暴露」的发现:
> 1. **输出名唯一性**必须单独断言 —— 五卷里两卷都在「海贼王」系列、标题取自系列层,若 `outputDisplay` 拼错会静默互相覆盖。
> 2. **跳过策略下被跳过的那卷不该产生输出** —— 只有拿真实库里那卷 `outputExists = true` 才验得到,手造夹具很容易漏掉这个组合。
> 3. **中断与缺页的交互**:中断后剩余卷不再写出、已写出的保留;缺页的卷**不打开流**(否则留 0 字节 CBZ),这两条在端到端里用真实页 id 跑,比手造夹具更接近真机路径。
>
> ⚠️ **踩坑:`fun interface` 的匿名对象实现不可用**。把 `object : CbzOutputSink { override fun openFor(...): OutputStream? }` 写成匿名对象时,编译器持续报 `ABSTRACT_MEMBER_NOT_IMPLEMENTED`(即使方法签名逐字正确、`--rerun-tasks` 也照报),改用 **SAM 转换** `CbzOutputSink { plan -> ... }` 后立即通过。**教训:只有一个抽象方法的 `fun interface`,匿名对象语法在某些 Kotlin 版本上会踩这个坑,优先用 SAM lambda。**
>
> 闸门:domain **634**(+18)/ app 554 / lint 零告警 / APK 34,421,078 B。变异 **M1–M8 全 KILLED**(M1/M8 缺页先开流 / M2 跳过卷也开流 / M3 中断检查恒 false / M4 写盘失败不清理 / M5 `isComplete` 恒真 / M6 闸门拒绝时也执行 / M7 闸门把 Marginal 当阻断)。⚠️ 变异首轮 M2/M3/M4 **ANCHOR-MISS**,因为我凭记忆写了锚点 —— 按项目纪律锚点必须是**代码里真实存在的串**,读原文后重跑全部 KILLED。

> **✅ 2026-10-19 凌晨(再再续):D4b 的运行报告已能喂给现有报告页(领域层,零 Android 依赖)**
>
> `domain/pack/PackReportBridge.kt`(18 tests)把**真实写盘**的 `PackRunReport` 单向转成界面已在渲染的 `ExecutionReport`。
> 两套报告此前各说各话:`ExecutionReport` 是 D3 的**确定性模拟**(KDoc 原文「D1 原型不触碰真实文件系统」),
> `PackRunReport` 是 D4b 的**真实结果**。改 UI 让它同时认两套(报告页 / 撤销入口 / 文案解析全分叉)成本远大于做一次转换。
>
> **转换的三条硬规则**:
> 1. **只有 `Written` 算 `Succeeded` 且带 `createdPath`** —— `ExecEntry` 的 init 强制 `Succeeded ⟺ createdPath != null`;其余一律 null,于是**自动不进撤销记录**(`undoOpsOf` 只收带 path 的项)。
> 2. **`originalPath` 取 `VolumePackPlan.overwritesExisting`** 这**唯一**实现,不在此重写 `conflictPolicy == Overwrite && outputExists` —— 那正是缺陷 R3 最贵的部分(界面警告了覆盖、撤销却按新建删)。填对之后 `overwrittenCount` / `needsUndoWarning` / 撤销安全档三者全部自动正确。
> 3. **中断后未处理的卷补成 `Skipped` + 「尚未开始」** —— `PackRunReport.entries` 只含已处理卷,直接转换会让报告比计划少几行,用户拿报告核对计划会以为「计划里那几卷凭空消失了」。故转换要传完整 `plans`(计划内容不在 `PackRunReport` 里)。补「尚未开始」而不是「跳过」:跳过是**计划里就定了**的(输出已存在 + 跳过策略),这里是**用户取消**导致没轮到,两种原因不能共用一句话。
>
> **新增两条文案键**:`PackFailMissingPages`(plurals,%1$d 读不出的页数;英文 one/other 两档 —— lint 的 `PluralsCandidate` 直接拦下了 `%d` 后跟 `pages` 的写法)、`PackFailWrite`(%1$s 平台详情原样带出:`ENOSPC`/`EPERM`/provider 抛错 这些原因用户与开发者都猜不到,它是唯一线索)、`PackFailNotStarted`。
>
> **归责边界**:归这里 = 机器结果翻译成报告词汇;不归这里 = 要不要执行(`PackGate`)、真的去写(`PackRunner`)、撤销怎么做(`undoRecordOf`)。本文件零副作用。
>
> 四道闸门绿(app **554** / domain **652**,+18 / lint 零告警 / APK **34,422,326 B**),变异 **M1–M5 全 KILLED**(M1 全部算成功 / M2 覆盖项不填 originalPath / M3 中断后不补行 / M4 缺页原因漏页数 / M5 失败详情不带给用户)。
> ⚠️ **首轮 3 红又是夹具 bug**:夹具一律用 `Rename` 策略,而 `overwritesExisting` 要求 `conflictPolicy == Overwrite` ⇒ 覆盖用例本来就不算覆盖。**教训:读判据实现再写夹具,别按字段名猜。**
> ⚠️ **新增复数键必须同步 `TranslationCoverageTest.PLURAL_KEYS`** —— 那是「复数键集合」的显式契约,漏改直接红。
>


> **✅ 2026-10-19 深夜:D4b 的真实改名与真实撤销已在真机跑通(ANR 修复轮)**
>
> 这一轮才把「执行」从模拟变成真的动文件,并当场撞出一个只在真机出现的缺陷。
>
> **真实改名/撤销的落地形状**(领域层零 Android 依赖):
> - `domain/plan/RenameExecution.kt`: `RenameActions.rename(uri, newName): String?` —— **返回新 uri 而不是 `Boolean`**。`ExternalStorageProvider` 改名后 document id 由路径派生 ⇒ uri 变了,**撤销必须拿新 uri**,只回 `true/false` 会让撤销改错人。返回 `null` = 失败(正常业务分支,平台实现不得抛)。
> - `executeRenamePlanReal` 的四个分支顺序**逐条对齐**模拟版 `executeRenameOp`:跳过(不碰平台)→ 目标名为空(`RenameFailEmptyName`)→ **缺 uri(`RenameFailNoUri`)** → 平台调用。**缺 uri 记失败而不是跳过**:跳过是「计划里就定了」,缺 uri 是「执行环境缺信息」,两者对用户的含义完全不同。
> - `UndoOp` 三个子类各加 `val uri: String? = null`(接口级),`undoOpsOf` 从 `ExecEntry.uri` 透传;撤销时缺 uri 记失败而不是静默跳过。`performUndoReal` 逐项 `runCatching`(**单点失败不中断**:已删掉的文件不会因为后面失败而回来),全成报 `UndoApplied`,否则 `UndoAppliedPartly`。
> - 平台层 `data/saf/SafRenamePlatform.kt`:`renameDocument` 抽成构造参数**只为可测** —— Robolectric 的 shadow 只能回放固定结果,「provider 返回 null」这条契约无法从外部触发。
>
> **真机撞出的 ANR(本轮最重要的发现)**:首轮 120 项一起改名,磁盘改到第 86 个时应用消失。`adb logcat -b crash` **空**、主日志无 FATAL ⇒ 不是崩溃。`dumpsys dropbox --print data_app_anr` 拿到主线程栈顶逐字 `android.provider.DocumentsContract.renameDocument+292` → `android.content.ContentResolver.call+612` → `SafRenameActions._init_$lambda$0`。
> **根因**:`executeRenamePlanReal` 在**主线程**逐项调 `renameDocument`,每项一次同步 Binder 往返,120 项串行 > 5 秒 ⇒ 系统判 ANR。
> **为什么 655 个测试全绿也没发现**:Robolectric 里没有真实 provider 往返,一秒能跑完几千项。**这是「本地测试覆盖不到、只能真机撞」的一类缺陷**:凡「N 次同步 IPC 串行」都必须假定 N 会大到超时。
>
> **修法**(三件,缺一不可):
> 1. 领域层 `executeRenamePlanReal` 加 `token: CancellationToken` + `onProgress: (TaskProgress) -> Unit`,`buildList { for ((index, op) in ops.withIndex()) }` 逐项**先检查中断再执行**;中断后把剩余项补成 `Skipped` + `PackFailNotStarted`(「尚未开始(用户已取消)」,与计划里的跳过语义区分)。**计数与 `entry.outcome` 严格同源**,每项上报一次(一项就是一次 IPC,「动一格」必须对应一次真实变化,不做时间节流)。
> 2. UI 把执行与撤销都挪到 `withContext(Dispatchers.IO)`,执行期用 `TaskProgressBar` 顶替底栏的「确认执行」。
> 3. 进度条通用化:`ui/library/PackProgressBar.kt` → **`ui/components/TaskProgressBar.kt`**(`TAG_PACK_PROGRESS`/`TAG_PACK_CANCEL` 一并改名为 `TAG_TASK_PROGRESS`/`TAG_TASK_CANCEL`,**字符串值也换** —— 两个页面同时挂同名 tag 时 `onNodeWithTag` 会撞多节点直接失败)。
>
> **撤销期单独加 `undoing` 状态**:撤销发生在报告页(`executing` 已复位),两者互斥但不是同一个状态。期间按钮禁用 + 文案换「撤销中…」—— 否则界面无变化,用户会连点,每次派发一整轮撤销。
>
> **真机复验读数**(PJX110 / Android 16 / ColorOS / `/storage/emulated/0/test/Stress` 5 卷 × 24 页 = 120 张):
> - 执行:进程存活(PID 11490)、报告 **「共 120 项:成功 120 · 跳过 0 · 失败 0」**、磁盘 **120/120 真改名、原名残留 0、文件数仍 120**。
> - 撤销:磁盘 **120/120 全部回原名**、界面 **「已撤销 120 项」**。
> - 进度条真机抓不到:24 项约 1–2 秒跑完,比一次 `dumpui` 还快 ⇒ 进度条接线由离线测试钉住(`executing replaces the confirm button with a progress bar`),真机只验证「不 ANR」。
>
> **中途一次假警报**(记下来免得下次误判):第二次改名报告「120 全失败」,原因是**磁盘文件已被上一轮改过名**,目标名与现状同名 ⇒ provider 拒绝。不是代码缺陷。**测试前必须先恢复素材到已知状态**。
>
> 四道闸门:app **657** / domain **877** / lint 零告警 / APK **34,204,030 B**。变异 **M1–M6 全 KILLED**(M1 执行回主线程、M2 底栏不换进度条、M3 去掉中断检查、M4 尚未开始原因置空、M5 成功不计入进度、M6 跳过不计入进度)。M5 首轮写成重复 `when` 分支导致编译失败 —— **编译失败不算 KILLED,必须重做成语义合法但行为错误的变异**。


> **▶ 工作区选取搬到首页 (2026-10-24)** —— 用户原话「把工作区选取改到首页？写功能里感觉意义不大,要么是首页点进去,要么是外部直接分享文件夹或者大量图片进去（这功能D5再做）」。
>
> **一、落地的不是新设计,而是 HANGOFF §4 里 2026-10-15 就写成目标形态的那一版** —— 当时原文是「标题行 + 设置齿轮 → 两张工作流大卡(不变) → 其下从属层一行『当前工作区: … [更换]』」,执行时机标为「属 D2b/D4b,本轮(D1 收尾)不碰首页」。本轮把它从「将来」落到现在,**入口仍然不是第三张大卡**(Workspace 是范围、工作流是任务,摆成三张等权卡会误导用户以为「选工作区」也是一件能做完的事)。
>
> **二、三条裁决(用户原话「乙甲乙」)** —— ①工作流内那两处 `WorkspaceBar` 怎么办:**乙** = 只在正忙/出问题时显示;②选完工作区停首页还是自动跳:**甲** = 停首页;③未选工作区时两张大卡禁用吗:**乙** = 禁用,首页变成「先选文件夹」引导页。
>
> **三、裁决①不能简单「全删」的根因**:`WorkspaceState.collectionsOr` 在非 `Ready` 状态**一律回退 `MockData`** ⇒ 授权失效后界面上照样有一整列**看着正常**的图片,用户会以为在编辑真实文件。所以 `Denied`/`Failed` 必须保留重选入口;`None`(首页已有入口)/`Ready`(无事可做)才可以不画。落成扩展 `val WorkspaceState.showsWorkspaceBar`,而不是在两处调用点各写一个 `when` —— **只改一处不会有任何测试报错**,这正是要抽出扩展的理由。
>
> **四、裁决③不能复用现成判据**:`WorkspaceState.canOrganize` 在 `None`/`Denied`/`Failed` 都返 `true`(D1 的 mock 演示语义:没选目录也能拿演示数据玩)。而首页入口要回答的是「点进去操作的是不是**你自己的**文件」—— 两个问题不同,必须另立 `HomeScreen.canStartWorkflow`:`null→true`(宿主没接线时本组件无权假定)、`None/Loading/Denied/Failed→false`、`Ready→collections.isNotEmpty()`。
>
> **五、禁用态的写法有讲究**:必须走 `Modifier.clickable(enabled = enabled, onClick = …)`,**不能换成空 lambda** —— 只有 `enabled` 参数会往语义树写 `Disabled`,读屏才知道这个大卡用不了,测试的 `assertIsNotEnabled` 也依赖它。视觉上描边 `outlineVariant.copy(alpha = 0.4f)`、标题 `onSurface.copy(alpha = 0.38f)`。
>
> **六、测试侧必须开注入缝**:`PixFoldApp` 原来直接 `remember { AndroidSafSource(context) }`,没有替换点。Robolectric 没有真实 `DocumentsProvider` ⇒ `lastWorkspace()` 恒 null ⇒ state 恒 `None` ⇒ 两张大卡恒禁用 ⇒ **24 处导航测试会一起红,那不是行为回归**。故加 `PixFoldApp(workspaceSource: WorkspaceSource? = null)`(与既有 `store`/`openPackOnStart` 同一套可测性先例),测试侧补 `ReadyWorkspaceSource`。另有一条真实的连锁改判:`AppNavigationTest` 的 `volumeCardTag("aot-1")` 改成 `"trip"` —— 注入就绪工作区后卷 id 直接来自 `collections`(`ComicVolumesFrom.kt` 的 `id = collection.id`),而 `aot-1` 只属于「无工作区 ⇒ 回退 `MockDataB`」那条路径。
>
> **七、变异 M5 首轮 SURVIVED 是本轮最有价值的一条**:`LibraryPage` 的 `showsWorkspaceBar` 条件退回 `!= null`,全部测试照绿 —— 说明工作流 B 侧**一条守卫都没有**,只守了 A 侧。补 `LibraryPageTest` 两条(常态不挂条 / 撤权后必须挂条)后转 KILLED。**两处必须同时改的地方,只改一处不会有任何测试报错**,与第三条同一个病因。
>
> 四道闸门(`--rerun-tasks` 强制重跑):app **764** / domain **940** / lint 零告警 / APK **34,426,454 B**;变异 M1–M6 全 KILLED。分支 `d4/home-workspace`。**真机核对待做**。
>

优先交付（**GUI 仅 Android**，2026-09-10 变更）：

- Android：单目录/单系列，图片预览与人工排序，逐卷元数据，CBZ 生成；
- 桌面端不再有交付项；现有 Python 脚本维持可运行，不新增功能。

