# D2 · 平台能力验证

> **来源**：`HANGOFF.md` 原有 §9 内容，原行号 L3150–L3283（2026-10-23 迁出）。
> **内容**：D2a SAF 闭环 spike 与 D2b 五项平台能力。
> **性质**：实现期逐轮留痕，只查不常读。**产品决策仍以 `HANGOFF.md` 为唯一权威**，本文件是其详版。
> **索引**：见 [`docs/INDEX.md`](../INDEX.md)。

<!-- 原 HANGOFF.md L3150–L3283 -->

## D2：平台能力验证
**目标**:优先 Android SAF。重点验证最容易推翻技术选型的能力,不先做完整业务。
> ~~再验证 Windows 和 Linux 文件访问~~ —— **2026-09-19 移除**:GUI 只做 Android(§2/§7),§10 明确"桌面端无验收项"。
> 领域模型不得依赖 Windows 专有路径与打包机制的约束(§7 末)继续有效,但它属领域层横向约束,不是本阶段的验证项。
顺序注(2026-09-07):SAF spike 可提前至 D1 完整原型之前执行,判定与顺序见 §8.2。
**拆分为 D2a / D2b(2026-09-10 按事实对齐)**——原来把"SAF 可行性 spike"与"完整平台能力"混作一个 D2,而 spike 已于 2026-09-08 提前完成,导致路线图与实际进度不符:
- **D2a · SAF 闭环 spike** ✅ **已完成(2026-09-08,提前执行)**:选目录 → 持久授权 → 递归遍历 → 读字节 → 创建/改名/删除,真机 5/5 验收(§8.3);产物原在 `C:\Personal\pixfold-spike`(**该目录已于 2026-09-13 删除,完整 Kotlin 实现现归档在本地分支 `archive-flutter-verify`**,见 §12.7)。**本阶段不再重复,验收记录归档。**
  - **✅ 2026-10-18 起在 `android/` 里重建平台层(只读闭环)** —— 注意这不是「重复 spike」:spike 验证的是**可行性**(5/5 通过并已归档),而这里是把同样的 `DocumentsContract` 调用序列**落成正式代码**。本轮范围经用户裁决收窄为**只读**:选目录 → 遍历出真实 `SourceItem` → 缩略图/预览读字节;**写入(CBZ 落盘)留 D4b**。落地要点:
    - **领域端口**(`domain/source/`,零 Android 依赖 —— `AdapterBoundaryTest` 强制):`SourceDoc` 把 provider 查询行压成纯 Kotlin 值对象(目录 MIME 常量**写死字符串**而非 import `DocumentsContract`)、`WorkspaceSource` 接口(`loadCollections` 返回 `LoadResult.Success/Denied/Failed`,`readBytes` 返回 `ByteArray?`)、`collectionsFrom` 是**分组规则的唯一权威**(工作区根下每个一级子目录 = 一个集合,根下散图归根集合 `id = ""`;集合内按 `naturalCompare` 排;`seed`/`id` 从 `documentUri` **稳定派生**而非下标 —— 否则重排后缩略图颜色会变)。
    - **线程契约由端口声明**:`WorkspaceSource` 的方法是**普通阻塞函数**而非 `suspend`(domain 不引协程依赖),调用方负责 `withContext(Dispatchers.IO)`。异常契约 = 授权失效**不抛** `SecurityException` 穿透 UI,返回 `Denied`。
    - **平台层**(`app/data/saf/`):递归用显式 `parentDocId` 调 `buildChildDocumentsUriUsingTree`(**不从 URI 反解** —— 反解出的 id 在部分国产 ROM 上带 provider 私有后缀);`MAX_DEPTH = 4`;查询**不传 sortOrder**(排序在领域层做,provider 排一遍再排一遍语义不同会互相打脸);`takePersistableUriPermission` 先存 URI 再调用、`SecurityException` 只记日志(抛异常 ≠ URI 不可用,真正判据是「能不能 query 出东西」)。
    - **UI 落点**(2026-10-18 实施判断,遵循 §4):**不改 `WorkflowAStep` 枚举、不加第 4 个 Tab** —— 那会牵动 `workflowAStepIndex()`/`goToWorkflowAStep()`/`popWorkflowStep()` 与约 30 条导航测试,回归风险远大于收益。改为在页签行**上方**加常驻 `WorkspaceBar`(「当前工作区: {名} [更换]」,未选时给「选择文件夹」),并在 `collections` 为空时于页签**下方**给空态。两者都在工作流内部、不破坏 3 步线性流程,与 §4「从属层」语义一致。首页的从属层行按 §4 原文(「将来」)**本轮不做**。
    - **`None` 不是错误**:D1 全 mock,用户从没选过目录是正常初态 ⇒ `collectionsOr(mock)` 回退演示数据,既有 491 条测试零改动;但 `Ready` 且列表**为空**时**不**回退 mock(用户选了空目录就该看到「没有图片」,悄悄塞回演示数据会让人以为扫到了东西)。
    - **闸门**:`app 515`(+24)/ `domain 504`(+21)/ lint 零告警 / APK 33,758,891 B;变异 **M1–M4 全 KILLED**(M1 `Ready` 空列表回退 mock、M2 mock 数据发缩略图请求、M3 工作区条默认常显、M4 删掉空态)。**真机核对顺延 2026-10-19**(用户 2026-10-18 深夜指令:先做完全部离线部分)。
  - **✅ 真机核对已完成(2026-10-19)**,并按真机暴露出的三个真实问题追加了两轮修正。核对结论:
    - **真实缩略图**:网格里是真实漫画页面(截图 `android/artifacts/real.png`,Native Heap 24MB / PSS 115MB),不是 `ProceduralThumb` 占位。
    - **持久授权跨进程**:`am force-stop` 后重启,首页进工作流 A 自动恢复 `当前工作区:test` 并扫出真实集合 ⇒ `takePersistableUriPermission` + `SafTreeStore` 链路成立。
    - **设备**:PJX110 / Android 16 / 361dp×794dp / zh-CN;测试工作区 `/storage/emulated/0/test`(用户授权,允许测试删除)。
  - **真机暴露问题 1:热切换工作区后内容区整片空白**(冷启正常)。根因 = `WorkflowASteps.kt` / `SortAndPreviewPage.kt` 各自的本地 `state` 用**无参 `remember`** 播种:上层 `PixFoldApp.kt` 是 `remember(collections)`,换工作区时重建并传下新 state,但下层组合实例没被销毁 ⇒ 仍持旧工作区的 orders(只有旧集合 id)⇒ 渲染处 `order = state.orders[it.id] ?: return` **静默 return,整页白屏**。连带发现「重置全部」也救不回来(它的 `collections` 来自旧 state),而切回旧工作区又能显示(旧 state 里恰好有那些 id)—— 这解释了真机上「时好时坏」的假象。
    - 修法三条:①三处播种点(`WorkflowASteps.kt` 的 state/activeId/namingState、`SortAndPreviewPage.kt` 的 state、`NamingPage.kt` 的 state)全部加 `collections`/`collection` 作 key;②去掉静默 `?: return`,改渲染显式空态 `a_sort_empty` + `TAG_SORT_EMPTY`(照抄步骤 2 已有的 `a_step_empty` 写法 —— **任何状态错配都必须可见**,白屏是最坏的失败模式);③`NamingPage` 同理(刷新后 `overrides` 的键是旧 id,不重播种会带着指向不存在项的覆盖算建议)。
    - **真机回归**:临时工作区 `/storage/emulated/0/test2`(SeriesA 下 BookOne 3 张 / BookTwo 2 张)热切换后 dump **LEN 33836** 完整渲染;修复前同场景 **LEN 11325**(空白)。切回 `test` 后 **LEN 51696** 完整。正反双向都验证。
  - **真机暴露问题 2:集合分级错误 —— 一个 series 下两本书没分开**。旧规则「工作区根下每个一级子目录 = 一个集合」在真实素材上把同一系列下的多本书**并成一个集合**(用户实测:步骤 1 里两本书的页混在一起,无法分别排序命名)。**规则已改判(用户 2026-10-19 裁决)为「每个直接含图片的目录 = 一个集合」**,集合的**父目录即系列**。
    - `ImageCollection` 末尾追加两个**带默认值**的字段:`seriesKey`(系列分组键 = 集合目录的相对父路径,空串 = 无系列)、`seriesName`(父目录名)。带默认值 ⇒ 26 处既有 4 位置参数构造零改动。
    - **为什么不做嵌套结构**:集合是唯一被排序/命名/打包消费的单位,系列只是芯片行的分组视图;做成「系列 → 集合」外层容器会让 `WorkflowAState.orders` 这类按集合 id 索引的状态全部改索引口径,收益与代价不成比例。
    - **集合 id 用集合目录相对工作区根的路径**(不是目录名)—— 否则不同系列下的同名书目录会撞 id。副作用是同一系列的集合 id 前缀相同 ⇒ 自然序排列天然相邻。
    - 领域层新增 `domain/source/SeriesGroups.kt`:`groupCollections()` 把集合按 `seriesKey` 分组、**不重新排序**(集合顺序的唯一权威仍是 `collectionsFrom`);分组 key 带 `series:`/`single:` 前缀,避免「系列名恰好等于某单本集合 id」时撞键。
    - **真机核对**:1 级系列芯片 `…で (56)`;2 级两本 `…で (24)` / `…で――2 [DL版] (32)`;点第二本 ⇒ `共 32 张`、首图 `035.jpg` ⇒ 两本真正分开。
    - **✅ 芯片行统一(2026-10-19 用户裁决:只统一「集合芯片」与「卷芯片」)**。两处原本各写一份 `FlowRow + FilterChip + 溢出 sheet`,语义完全相同(从 N 个具名条目选 1 个)。抽出**两层**共同底层:`ui/components/SingleSelectChipRow<T>`(单行)与 `ui/components/TwoLevelChipRows<T>`(两级 = 1 级行 + 可选 2 级行)。`VolumeChipRow` 缩成薄壳(对外签名/tag/文案全不变),集合芯片(`ui/workflowa/CollectionChipRows.kt`)与卷芯片(`ui/components/VolumeChipRows.kt`)都只剩「把领域分组映射成 `TwoLevelGroup` + 提供文案/tag」的胶水。层级 = 1 级是系列或单本、2 级是选中系列后其下列出的各单本。
      - **`TwoLevelChipRows` 必须自带 `Column`**(2026-10-19 实测缺陷):首版直接把两个 `SingleSelectChipRow` 作为兄弟节点返回,生产调用点恰好都在 `Column` 作用域里所以没暴露;**但组件在非 `Column` 容器里会把两行芯片重叠在同一位置** —— 实测 1 级 `A` 与 2 级 `A/第01卷` 的 bounds 完全一致(`Rect.fromLTRB(67.0, 42.0, 185.0, 154.0)`),点击命中最上层,表现为「点系列芯片却选了书」。修法是函数体整体包 `Column(modifier.fillMaxWidth())`。**排查手法**:`performClick` 没点中时打印每个节点的 `boundsInRoot`,坐标完全重合即布局重叠 —— 靠读代码推理不出来。
      - **1 级芯片 5 个上限与「全部 N 项」sheet 保留**:这是 2026-10-18 真实规模回归换来的(40 卷实测芯片块 2296px 吃满 835.3dp 视口,连续 11 次全屏 swipe 下方内容仍不可达)。**不设 `FlowRow(maxLines=2)`** —— 试过变异存活,承重的是条数上限而不是行数上限。
      - **两级行新增两条真机/结构判据**:①选中系列时 1 级行**收缩到一行**(`maxLines = 1`)—— 换行会把 2 级行挤下去;②限行后看不见的条目不能再靠换行够到,而一行容量**离线测不出**(Robolectric 不做真实文本测量,芯片宽度算成 ~0dp)⇒ `overflowing` 判据改成「超过 5 个 **或** 限行且多于 1 个」,宁可多给入口。
      - **为什么其余 9 处 `FilterChip` 不统一**:布尔开关(`cleanEmptyDirs`)、枚举参数编辑(冲突策略/源处置策略/批量字段)、带方向三角的排序字段多选 —— 语义是「编辑一个参数」而不是「从 N 个条目选 1 个」,抽同一个底层只会造出一个参数比实现还长的组件。
      - **结构守卫同步**:`ChipRowWrappingTest` 的两张表(禁 `horizontalScroll` 的文件表扩到 9 个、`FlowRow(` 计数表)按新的文件归属重写,第 3 条守两级结构的用例改名 `two_level_chips_stay_two_level()` 并**改盯 `TwoLevelChipRows.kt`** —— 两级结构只在那一处实现,盯着只剩参数透传的 `CollectionChipRows.kt` 等于断言一个空壳。
    - **✅ 步骤 2 命名页加集合芯片行(2026-10-19 用户提出)**:用户原话「整理图片的步骤2也加上芯片选取，单向继承步骤1选择项」。**裁决 = 与步骤 1 共用同一个 `activeId`**(用户从三选一里选了「共用同一个选中项」)—— 措辞是「单向继承」,但实际选定的是双向共享:在步骤 2 切了集合,回步骤 1 也是新选的那个。实现上 `activeId` 的唯一持有者仍是 `WorkflowASteps`,步骤 2 只上报;`NamingPage` 新增两个**带默认值**的参数(`collections = emptyList()` / `onSelectCollection = {}`),38 个既有用例零改动。**为什么重要**:步骤 1 的排序和步骤 2 的改名必须对同一本书,否则用户会在步骤 2 改到一本自己没在步骤 1 排过的书。真机三跳验证:步骤 1 选第二本 ⇒ 步骤 2 标题 `…――2 [DL版]（32 张）` ⇒ 步骤 2 里切回第一本 ⇒ 回步骤 1 显示 `共 24 张`(共享而非快照复制)。测试 `NamingCollectionChipTest`(4 用例)必须走真的 `WorkflowASteps` 而非裸组合 `NamingPage` —— 共用 `activeId` 是**跨步骤连接性**,裸组合时根本没有「步骤 1 的选中项」这回事。
    - **✅ 工作流 B 元数据页卷芯片改两级(2026-10-19 用户提出)**:用户原话「打包漫画的元数据步骤也改下支持刚才的双层芯片选取」。领域层新增 `domain/comic/VolumeGroups.kt`(`groupVolumes()`,13 tests),与 `groupCollections` 严格同构 —— **复用同一套 `SERIES_PREFIX` / `SINGLE_PREFIX` 常量**,两个页面的分组键处于同一键空间。1 级芯片按 `ComicVolume.series` 分组(显示 `进击的巨人（3）`)、2 级列出该系列各卷;单本卷沿用 `volumeChipTag`(既有 20 余处测试零改动)。**两行不能共用 tag**(`onNodeWithTag` 遇多节点直接失败)⇒ 1 级行用新的 `TAG_VOLUME_SERIES_ROW` / `volumeGroupTag()`,2 级行保留 `TAG_VOLUME_SELECTOR` / `volumeChipTag()`。`MetadataPageTest` 的 9 处跨系列点击改用 `switchToVolume()` 辅助(先点 1 级系列芯片再点 2 级卷 —— 这正是用户的真实路径)。**步骤 3 打包预览页刻意不跟着改**:那里的芯片带勾选状态,两级再叠加勾选时 1 级系列芯片无法表达「这个系列里几卷被排除」,而用户实际是逐卷核对。
      - **规模夹具必须构造「一个系列几十卷」**(`MetadataVolumeChipStickyTest`):首版 `manyVolumes()` 轮换取 5 个 mock 卷 ⇒ 得到 2 个混合系列,1 级行收缩后成员分散到两条分支,断言误判。真实规模缺陷的形态是**同一系列几十卷**,那才会让 2 级行溢出 ⇒ 夹具改成恒定取 `library.volumes.first()`。
      - **1 级行收缩后的溢出入口也要断**(新增用例 `series row cramped to one line keeps an all entry`):`maxLines = 1` 之后看不见的系列芯片必须有「全部 N 项」入口,否则某些系列彻底不可达。

  - **真机暴露问题 3:页数不刷新**(用户删了重复文件,页数仍显示 90)。这不是 bug 而是**架构缺口** —— app 只在冷启动/选目录时扫一次。见 D2b 新增的「工作区实时刷新」条目。
  - **闸门(本轮收尾)**:`app 582` / `domain 699` / lint 零告警 / APK 34,563,062 B。变异 **M1–M11**,其中 **M8 SURVIVED 且刻意保留**:ContentObserver 的 `notifyForDescendants = true` 无法用 Robolectric 单测钉住(无真实 `DocumentsProvider`),属「只能真机断」的类别 —— 按项目纪律不设假保障,在此记明。

  - **闸门(芯片两级化收尾,2026-10-19)**:`app 587` / `domain 712` / lint 零告警 / APK 35,228,506 B。变异 **M11–M18**:M12/M14/M15 KILLED;**M13 SURVIVED 是冗余守卫**(`NamingPage` 外层的 `collections.size > 1` 与 `CollectionChipRows` 内部判据重复)⇒ 按项目纪律**删掉**外层那层,不留杀不死的判据;**M16 SURVIVED 查出断言太弱**(「切走再切回记住上次那本」原来只断 `assertIsDisplayed()`,而切回来后该系列两本都在 2 级行里、都可见)⇒ 改断**选中态**(`assertIsSelected` / `assertIsNotSelected`)后 KILLED。
- **D2b · 完整平台能力** 🔄 **领域层部分完成**(2026-10-12 夜间离线推进):
  - Android:千级缩略图**生成策略**(`ContentResolver.loadThumbnail` vs `BitmapFactory` + `inSampleSize` + 缓存/并发上限)、**后台任务与进度**(切后台恢复、进度上报)、**授权失效恢复流程**(SecurityException → 引导重选目录);
    - **✅ 第 1 项「千级缩略图生成策略」领域层 + 平台策略已落地(2026-10-19 凌晨)**。动机数字:实测样本库 **851 张**、单张 3.4MB JPEG;512×512 ARGB_8888 = **1 MiB/张** ⇒ 851 张全缓存约 **892 MiB**,而真机 PJX110 堆约 **256MB**。
      - 领域层 `domain/thumb/ThumbBudget.kt`(21 tests):请求边长**向上对齐 2 的幂**(`inSampleSize` 语义就是 2 的幂,请求非档位值 provider 会自己取整、预览页按它算缩放比就错)、单张成本 = 边长²×4、缓存预算取**堆的 12.5%**、并发 = `cpuCores + 1` 夹在 2..8。堆大小与核数是**传进去的参数**(领域层零 Android 依赖)。降级策略:**先降边长,再降条目数** —— 条目数是「手感」下限,少于几屏会疯狂重载。256MB 堆 + 512px 显示 ⇒ **32 张 / 32MiB**;堆只有 96MB 且显示 1024px 时自动降到 512px 并置 `degraded=true`。
      - 缓存层 `ui/workspace/ThumbnailCache.kt`(11 tests):**按字节淘汰的 LRU**(不是按条目数),条目数只做兜底。
      - 装饰器 `ui/workspace/CachingThumbnailSource.kt`(10 tests):缓存 + 并发闸门 + **在途请求合并**。为什么要合并:`gate` 许可数是 `cpuCores + 1`(通常 8),同一张图的多个请求**完全可能一起穿过双检**,于是真读了 N 次 —— 这是首轮测试**当场抓出的真缺陷**(写测试时预期只会读一次,实测读了 4 次),改成 `uri → CompletableDeferred` 的合并表后通过。
      - **平台实现 `AndroidThumbnailSource` 随之退回成「只管读」**(只调 `loadThumbnail`、失败/超时/OOM 一律返回 null),缓存与并发全部上移到装饰器。理由是可测性:LRU 顺序、字节账目、在途并发这些「写错了不崩、真机只表现为卡或偶发 OOM」的部分必须能离线断言。
      - 真实规模用例:851 张逐张走完,缓存必须停在预算内(`cachedCount <= 4`、`cachedBytes == 4 × 256KiB`),而不是 851 × 1MiB。
      - **真机验证仍待**(这是 D2b 退出条件的一部分)。
    - **✅ 第 3 项「授权失效恢复流程」领域状态机 + 自动重试已落地(2026-10-19 凌晨)**。D2a 只做了最小形态(`SecurityException` → `Denied` → 工作区条给重选入口),本轮补上**决策逻辑**:
      - **关键平台事实**:Android 上「用户真的撤销了授权」与「进程重启后 `takePersistableUriPermission` 尚未就绪」的表现**完全一样**,都是 `SecurityException`,平台层无从分辨。因此**不能一见 `Denied` 就弹「授权已失效」** —— 冷启动是这个失效的最高频场景,一次重试往往就好;不重试的话用户每次开机都要看一次假警报,点一下重选又莫名其妙好了。
      - **自动重试策略**(`ui/workspace/WorkspaceState.kt`):`LoadResult.Denied` 归为 `FailureCause.Transient`、`Failed` 归为 `Io`,`AUTO_RETRY_LIMIT = 1`;`Revoked`(确定性撤销)一次都不重试 —— 重试一百次也不会成功,只会让用户多等。重试仍失败才落 `Denied(autoRetried = 1)`,界面据此换文案「重试后仍无法访问 %1$s」而非「授权已失效」,如实告知「尽力了」而不是假装一次成功。
      - **领域状态机**(`domain/source/WorkspaceRecovery.kt`,17 tests):`Healthy` / `Retrying(cause, attempt)` / `NeedsReselect(cause, keptName)` / `Cleared` 四态,`recoverWorkspace(current, event, keptName)` 纯函数转移、`MAX_AUTO_RETRIES = 3`(领域层的通用上限,比平台策略宽松 —— 它描述的是「任何时候都不该无限转圈」这条不变量)。**三条硬规则**:①`Transient` 有限自动重试后**降级**为 `NeedsReselect`(无限重试 = 永远转圈的界面,比直接报错更糟);②`NeedsReselect` **必须保留原目录名**(丢了名字只剩「授权失效」,用户无从判断该重选哪个);③`recoveryActionsFor()` 保证**任何失效状态至少有一条出路**,且 `NeedsReselect` **同时**给重试与重选(只给重选的话,其实只是抖动的用户被迫重走系统选择器)。
      - **为什么单独一个状态机而不是复用 `LoadResult`**:`LoadResult` 描述**一次扫描**的结果;工作区状态要跨越多轮「重试 → 失败 → 重选 → 成功」,生命周期不同。硬塞一起会让「扫描失败但工作区仍健康(重试即可)」这种中间态无处安放。
      - `WorkspaceController` 的 IO 调度器改为**可注入**(`ioDispatcher: CoroutineDispatcher = Dispatchers.IO`),否则 `runTest` 的虚拟时间管不到真实线程池,测试只能靠 sleep 等 —— 那是最脆的一类测试。新增 `WorkspaceControllerTest`(8 tests):冷启动无授权不扫 / 有授权自动恢复 / 失效自动重试一次 / 重试仍失效时才 `Denied(autoRetried=1)` / 成功路径不多试 / 选目录先记授权再扫 / forget 连持久授权一起抹 / 失败信息保留。
      - **闸门**:`app 554`(+18)/ `domain 542`(+17)/ lint 零告警 / APK 34,421,019 B;变异 **M1–M8 全 KILLED**。⚠️ 首轮 **M4/M5/M6 存活**暴露断言不足 —— M4(去掉自动重试)、M5(不记重试次数)当时**根本没有测试覆盖 Controller**,补 8 条 Controller 测试后杀掉;M6(改文案分档)暴露原断言只查「目录名在不在、按钮在不在」,两版文案都满足 ⇒ 改成**直接断言那版文案存在 + 另一版不存在**,复测 KILLED。**教训**:「状态存在」类断言对「文案/数值分档」没有杀伤力,必须断到**具体取值**。
    - **✅ 第 2 项「后台任务与进度」领域层已落地(2026-10-19 凌晨)**。领域层 `domain/task/TaskProgress.kt`(26 tests):
      - **失败项也推进进度** —— 「进度」回答的是「还有多少没处理完」,不是「成功了多少」。若失败项不推进,一批里失败 30% 时进度条会**卡在 70% 永远不动**,用户以为任务死了,而它其实早就跑完了。成功数与失败数另走报告展示,两条线不能混。
      - **空任务进度直接视为完成**(0/0 返回 `0f` 会让空目录看起来像「还没开始」;除零保护是本类型的职责,不能推给每个调用点)。
      - **上报节流六条判据,顺序不可调换**:①首次必报 ②**完成必报**(被节流掉 = 进度条永远停在 99%)③**失败数变化必报**(否则用户不知道有东西失败了)④**总数/已处理数倒退必报**(重扫/换工作区会把进度重置,节流不能吃掉它)⑤百分比跨 5 个点 ⑥距上次超过 250ms(长时间无进展时给「我还活着」的信号)。千级任务每张图都上报 = 上千次状态更新、Compose 侧上千次重组。
      - **中断令牌不是简单布尔**:会被界面线程 `cancel()` 与后台线程 `shouldStop()` 同时碰,普通 `Boolean` 无 happens-before 保证 ⇒ 后台循环可能永远读不到取消,表现为「点了取消没反应」⇒ 用 `@Volatile` + 幂等 `cancel()`。**只在一项开始前检查,不在项内检查** —— 半途硬停会在目标目录留下半个文件,比多花一秒钟糟得多。
      - **结果判定:先判完成,再判取消。** 用户点取消的瞬间任务恰好跑完是真实会发生的,这时报「已取消」是骗人的(实际什么都没少做)。空任务即使被取消也算完成(无事可做,报「已取消」会让人以为出了岔子)。
      - **删掉一条冗余判据**:`isComplete` 原写 `total == 0 || processed >= total`,变异验证实测删掉 `total == 0 ||` 后**无任何测试变红 = 等价变异**(`processed` 已保证非负,故 `processed >= 0` 恒成立)⇒ 按项目既有纪律(D3 `undoRecordOf` 先例)删掉,不留杀不死的判据。
      - **平台层接线与真机验证仍待**(前台服务/WorkManager、切后台恢复走 `SavedStateHandle`、进度推送)。
      - **✅ 平台层接线已落地(2026-10-19 晚)**。`ui/workflowb/PackProgressBar.kt`(10 tests):计数走 `TaskProgress.processed` 而非 `done`(否则会出现「条形走了 60%、文字写已成功 2/5」—— 两者不同源)、`LinearProgressIndicator(progress = { … })` 必须传 lambda(传常量条形不动)、取消按钮在进度条行内且**完成后刻意不隐藏**(隐藏会改变行高造成布局跳动,且「点取消的瞬间刚好跑完」是真实会发生的)。**切换后台恢复(前台服务/WorkManager、`SavedStateHandle`)仍未做** —— 当前实现是「组合作用域内跑协程」,切后台若被系统回收会中断。这是第 2 项唯一未完成的子项,已在退出条件里单列。
    - **✅ 第 4 项「存储空间预检」平台层接线已落地(2026-10-19 晚)**:`data/saf/SafPackPlatform.kt` 的 `FilesystemSpaceSource.availableBytesOf(treeUri): Long?` —— 只对 `authority == "com.android.externalstorage.documents"` 且 docId 以 `primary:` 开头的树给答案(走 `StatFs`),其余返回 **null = 「不知道」而非 0**。`PackExecutor.kt` 的 `executePackPlans(availableBytes: Long?)`:null 时**跳过空间阻断直接执行**(`preflight = null` 交给上层显示「无法判定」),0 时判 `Rejected`。刻意否定两种做法:拿假的大数(`Long.MAX_VALUE`)会把「不知道」伪装成够用;直接拒绝则真有空间时无法打包。**阈值已定档**:`SpaceThreshold.Percentage(marginPercent = 10)` + `safetyMargin = 32 MiB`(CBZ 输出 ≈ 原图总和,ZIP 再压收益 < 2%;32 MiB 兜 ZIP 缓冲与 provider 元数据更新),**HANGOFF §11 第 12 条由此关闭**。
    - **✅ 第 5 项「大归档写入」平台层接线已落地(2026-10-19 晚)**:`data/saf/SafPackPlatform.kt` 的 `SafCbzOutputSink`(`createDocument`+`openOutputStream`,`discardFor` 走 `DocumentsContract.deleteDocument` 清半成品)+ `SafPageBytes`(`openInputStream().readBytes()`)+ `SafPackExecutor`(全在 `Dispatchers.IO`)。三条承重细节:①**`createDocument` 的 parent 必须是 document URI**,传 tree URI 在国产 ROM 上抛 `IllegalArgumentException: Invalid URI` ⇒ 用 `getTreeDocumentId` 再逐级 `buildChildDocumentsUriUsingTree` 下钻;②**覆盖必须原地重写**(`openOutputStream(existing, "wt")`),不能用 `createDocument` —— 后者遇同名会**自动改名**成 `xxx (1).cbz`,与 `OutputConflictPolicy.Overwrite` 语义冲突;③`openFor` 返回 null 是**正常业务分支**(记 `Failed`,且**不调用** `discardFor`),只有「流已打开但写入失败」才清理。
    - **✅ 真实端到端已验证(2026-10-19 晚,真机 PJX110 / Android 16)**:工作流 B 从 mock 切到真实工作区扫描(`ComicVolumesFrom.kt` 转换层 + `WorkflowBReconcile.kt` 增量对账),`/storage/emulated/0/test` 同系列两本分别产出 **38,085,495 B** 与 **79,764,587 B** 两个独立 CBZ,报告「共 2 项：成功 2 · 跳过 0 · 失败 0」。
    - **⚠️ 真实端到端当场暴露的缺陷(已修)**:旧实现下同系列两本(目录名都解析不出卷号)默认名都取系列名 ⇒ **两卷写到同一路径,后写静默覆盖前写**;报告显示「成功 2」而磁盘只剩 **1 个** 79,764,572 B 的文件 —— 用户会以为两本都打好了。两处修:①`titleSug` 建议源由系列名改为**卷目录名**(用户 m05761 指出「默认名不是应该从单本的文件(夹)名解析吗」,原理由「避免重复卷号」站不住 —— 真实素材里系列名恰恰等于第一本的名字);②`PackPreflight` 新增 `PackBlocker.OutputNameCollision` **执行前阻断**(判据是 `outputPath` 完整路径而非文件名,不同目录同名合法;跳过的卷不参与)。
    - **✅ 名字清洗(2026-10-19 晚,用户 m06168「[DL版]没过滤掉？顺便检查下过滤规则」)**:此前真实扫描**完全没有清洗**,目录名整串当标题,CBZ 文件名里留着 `[DL版]` 这类发行标注。用户 m06244 裁决「照搬旧脚本规则」⇒ `domain/naming/NameCleaning.kt`(24 tests),规则与 `scripts/batch_pack_cbz.py` 逐字对齐:①**只剥末尾的 `[]` 标注**(`TRAILING_TAG_RE` 的 `$` 锚承重 —— 中间的 `[]` 是标题的一部分,`真正的[漫画]标题` 不能毁);②开头的展会代码 `(C108)` 剥掉;③括号原作 `（原作：X）` 剥掉;④`writerRaw` 保留原始形态(带方括号)、`cleanWriterName` 才清理 —— 这层差异是「一键清理作者」按钮存在的理由。**一处刻意比脚本更严的语义**:`[Cior (Ken-1)]` 整体被方括号包裹时**保留内部括号**(同人本 `[社团 (画师)]` 里括号内是署名的一部分),而 `[作者]谏山创(原作:某人)` 这种「标记前缀」形态才全剥其后括号 —— 由 `DerivedNamesTest` 抓出。
    - **✅ 目录名到展示名的统一抽象(2026-10-19 晚,用户追问「其他地方使用的默认值自动获取呢，统一抽象出来使用？」)**:清洗原先只做在**消费方**(`comicVolumesFrom`),导致工作流 A 的集合芯片、步骤 2 标题仍是裸目录名 —— 同一个名字在界面上有两个形态。改为 `derivedNamesOf(dirName): DerivedNames`(`raw` / `title` / `cbzBase` / `writerRaw` / `writerCleaned`)作为**唯一入口**,`ImageCollection.derived`(lazy,不参与 data class 相等)承载结果。**四种形态各有用途,不得合并**:`raw` 给芯片与步骤 2 标题(用户要在文件管理器里按名字找目录)、`title` 给元数据 `<Title>`(作者另写 `<Writer>`,重复即污染)、`cbzBase` 给落盘文件名(**保留** `[作者]` 前缀,文件管理器里认得出是谁)。**用户 2026-10-19 裁决:芯片显示原始目录名**。
    - **⚠️ 统一抽象过程中又抓到一处不一致(真机)**:步骤 2 标题已清洗,而同一页的「新名称」预览仍是 `[Cior (Ken-1)] …_001.jpg`。根因是 `effectiveScheme(state, collection)` 在「跟随集合」分支直接用 `collection.rootDirName`;修法是取 `derivedNamesOf(...).title`,并把 5 处分散的命名结构播种收敛成 `domain/naming/NamingSeed.kt` 的 `namingSchemeSeedOf(collections)`(单一入口才能被单测钉住 —— 分散构造时「哪一处忘了改」只有真机看得出来)。
    - **真机复验(2026-10-19 晚)**:芯片仍显示原始目录名(含 `[DL版]`)✓;步骤 2 标题与「新名称」均为 `彼女がボクの知らないところで――2` ✓;工作流 B 两卷建议 Title 已剥 `[DL版]` ✓;磁盘两个 CBZ 名为 `[Cior (Ken-1)] 彼女がボクの知らないところで.cbz` / `…――2.cbz`(保留作者前缀、无发行标注)✓。
  - **✅ 第 2 项「后台任务与进度」已完整(2026-10-19 深夜)**:打包执行从**组合作用域**移进 `dataSync` 前台服务,Activity 销毁不再中断任务;进程被杀后冷启用 `PackTaskStore` 的快照恢复提示。要点:
    - **执行位置**:`PackTaskService` 在 `Dispatchers.Main.immediate` 上起协程,真正的写盘仍在 `SafPackExecutor` 的 `Dispatchers.IO` 里。`onStartCommand` **无条件先** `startForegroundIfNeeded(...)` —— Android 要求 `startForegroundService` 后 5 秒内调,否则 `ForegroundServiceDidNotStartInTimeException`。
    - **不自动续跑**:`recoverPackTask(snapshot, serviceAlive)` 只回答「上次有没有跑完」,`serviceAlive` **必填无默认值**(`Running` 有两种含义:真在跑 vs 进程已死)。中断时刻的状态不可知 —— 可能死在写完一卷后,也可能死在 `openOutputStream` 刚建文件时,自动重跑会写出半成品或重复文件。
    - **快照编码不用 JSON**:`domain` 模块只有 stdlib(无 kotlinx-serialization),故 `PackTaskSnapshotCodec` 用「`键:值` 每行一条 + base64url 值」。`decode` **一律不抛**(冷启动路径,历史/损坏/半截数据都不能让应用崩)。
    - **进度通知抽象**:`OngoingProgressNotification`(进度,强制静音)+ `TaskCompletionNotification`(完成,跟随系统声音)两个对象,**都不把 `Builder` 交给调用方** —— 升格的 9 条硬条件里有 4 条是「不得怎样」(`customContentView`/`groupSummary`/`setColorized(true)`/`IMPORTANCE_MIN`),编译器一条都拦不住,加上去后单测全绿而真机胶囊静默消失。
    - **Live Update 升格的真正门槛不是 API 版本**:一度误判为「Android 36.0 恒为 false、要等 36.1」,真机反证 —— ColorOS V16.0.0 / `sdk_full=36.0` 上,打开系统「流体云 · 显示实时活动」总开关(**默认关闭**)后即生效。故 `setRequestPromotedOngoing(true)` 必须**无条件**写,不能用 `canPostPromotedNotifications()` 当门禁(它是能力查询,而升格是一个「位」,用户随时可能开开关)。
    - **渠道分两条**:进度渠道 `pack_task_live` 强制静音(每几百毫秒更新一次,响铃会炸);完成渠道 `task_done` **刻意不调** `setSound`/`enableVibration` ⇒ 跟随系统(用户 2026-10-19 裁决「声音和震动就跟随系统吧不做按钮了」)。渠道的声音设置作用于**整条渠道**,无法单条覆盖,故必须分开。
    - **完成通知覆盖两个流程**:打包(`FINAL_NOTIFICATION_ID = 4102`)与整理(`ORGANIZE_NOTIFICATION_ID = 4110`)各一条,id 不同 —— 后完成的不挤掉先完成的。**整理图片这条路径没有任何前台服务会建渠道**,故 `TaskCompletionNotification.build` 自己调 `ensureChannel`;否则 `notify()` 到不存在的渠道在 Android 8+ 上被系统**静默丢弃**。
    - **`onTimeout` 双重重载**都要覆写(`onTimeout(startId)` + `onTimeout(startId, fgsType)`),`dataSync` 有 6 小时上限。用户裁决(m08887)「6小时肯定是足够的」⇒ 只作防御性代码,**不做真机验证**。
    - **真机读数**:整理完成通知 `id=4110` / 标题「图片整理完成」/ 正文「Stress · 成功 120 · 跳过 0 · 失败 0」;打包完成通知 `id=4102` / 标题「打包完成」/ 正文「Stress · 成功 5 · 失败 0」;两条都落在 `task_done` 渠道(`mSound=content://settings/system/notification_sound`、`mImportance=3`、`mUnimportant=false`)。升格胶囊实测抓到状态栏实体(`ic_pack_progress` + `60%`),`dumpsys notification` 里 flags 含 `PROMOTED_ONGOING`。
  - **⚠️ D2b 退出条件逐项取证状态(2026-10-19 深夜,订正此前一次过度声称)**:此前一轮曾把本行写成「✅ 五项全部达成」,那是**只看代码落地、没核真机证据**的过度声称。逐项核查后订正如下 ——
    > **✅ 阶段结论(2026-10-19,用户裁决)**:D2 **阶段性完成**,剩余两项「①千级缩略图实测」与「⑤1GB 级归档 + Zip64 实测」**接受「代码写完但未验证」的状态**,记录于此,**转入走查阶段再补**。依据:①真机工作区只有 56 张图片,没有千级素材;⑤CBZ 是 STORED 不压缩,1GB 输入需等量输出空间,而设备只剩 3.24–4.6 GB,跑一次即吃光且不可逆。
    | 项 | 代码 | 真机取证 | 诚实状态 |
    |---|---|---|---|
    | 1 千级缩略图 | ✅ 领域层 + 平台策略 | ✅ **862 张实测通过(2026-10-24)** | 见下「① 千级缩略图取证」 |
    | 2 后台任务与进度 | ✅ | ✅ **完整** | 切后台续跑 + 胶囊 + 完成通知全部实测 |
    | 3 授权失效恢复 | ✅ | ✅ **失败路径已补** | 见下「③ 失败路径取证」 |
    | 4 存储空间预检 | ✅ | ✅ **失败路径已补** | 见下「④ 失败路径取证」 |
    | 5 大归档写入 | ✅ 流式已修 | ⚠️ **未达 1GB 级** | 见下「⑤ 取证与 OOM 缺陷」 |
  - **① 千级缩略图取证(2026-10-24 补)**:用户往 `/sdcard/test/pixiv` 复制了 **862 张**真实素材(单张最大 9.9 MB),设备 PJX110 / Android 16 / 1264×2780px / 560dpi = **361dp×794dp**。实测读数:
    - **冷启动**(`am force-stop` 后 `monkey` 启动 → 首页 → 点「整理图片」开始计时):**首次出画面 @3.2s、画面稳定 @6.7s**。方法 = 每 180ms 截一屏看 PNG 字节数,>900KB 即缩略图已铺满。
    - **内存**( `dumpsys meminfo com.cyanix.pixfold`):刚进列表 TOTAL PSS **136,526 KB**(Java 16,444 / Native 40,504)→ 快速滑动中峰值 **271,329 KB**(Java 43,356)→ 滚到底回落 **231,449 KB**(Java 37,228 / Native 115,308 / Graphics 5,508 / TOTAL RSS 383,576)。**峰值后回落 = 按字节 LRU 真的在淘汰**,不是只涨不跌;全程 pid 未变、`logcat -b crash` 长度 **0**。
    - **流畅度**(`dumpsys gfxinfo` reset 后缓慢滑 6 次):Total frames **850**、Janky frames **1 (0.12%)**、50th **8ms** / 90th **14ms** / 95th **15ms** / 99th **16ms**、Missed Vsync **0**、Slow UI thread **1**、Slow issue draw commands **0**。(legacy 口径 Janky 34 / 4.00% 一并记下。)
    - 滚到列表末端 **862 张全部加载完成**,末行可见、无空白格、无占位破图。
    - ⇒ **D2b 第 1 项关闭**。2026-10-19 那条「接受代码写完但未验证」的裁决自此作废(素材不足这个前提已经不存在)。
  - **③ 失败路径取证(2026-10-19 补)**:`cmd package uninstall -k` 会把应用持久授权**连同包一起清掉而保留 `shared_prefs`** —— 这构造出了「prefs 里记着 tree URI,但授权已不存在」的真实场景(`dumpsys activity permissions | grep -c pixfold` = **0**,prefs 仍含 `content://…/tree/primary%3Atest%2FStress`)。重装后冷启进「整理图片」,界面逐字显示 **「重试后仍无法访问 Stress,请重新选择该文件夹。」** 并给出「选择文件夹」入口 ⇒ **`Denied(autoRetried=1)` + 重选引导这条完整链路成立**。显示的是 `workspace_denied_retried` 而非 `workspace_denied`,恰好证明自动重试真的跑过一次(这正是「不因冷启未就绪就误报失效」的实现口径)。
    - ⚠️ **顺带暴露一个待办**:授权失效时工作流 A/B 仍显示 **mock 数据**(`旅行照片 (30)` 等 4 个集合、工作流 B「4 卷待确认」)。这是 `collectionsOr(mock)` 的既有设计(只有 `Ready` 用真实结果),但用户看到「授权已失效」旁边还有一堆可操作内容,会照常点「下一步」走到最后才失败。**未修**。
  - **④ 失败路径取证(2026-10-19 补)**:造 8GB 逻辑大小(稀疏文件,`du` 实占 7KB)的单页卷 ⇒ 计划页显示 **「可用空间不足：还差 4.3 GB（当前可用 4.5 GB）。」**,且**磁盘零字节写入**(目录里仍只有源文件,没有任何 CBZ、没有半成品)。数字关系也合理:8G 页 + ComicInfo ≈ 8.6G 预估 − 4.5G 可用 = 差 4.3G。⇒ 第 4 项「明确拒绝 + 不留下半成品」成立。
  - **⑤ 取证与 OOM 缺陷(2026-10-19 补,★本轮最重要发现)**:用 4 卷 × 4 张 100MB 页真机执行,**进程被 `OutOfMemoryError` 杀掉**:
    ```
    java.lang.OutOfMemoryError: Failed to allocate a 104857616 byte allocation
      with 25165824 free bytes and 49MB until OOM, target footprint 241935104, growth limit 268435456
      at java.io.ByteArrayOutputStream.toByteArray
      at kotlin.io.ByteStreamsKt.readBytes(IOStreams.kt:152)
      at com.cyanix.pixfold.data.saf.SafPageBytes.bytesOf(SafPackPlatform.kt:299)
      at …pack.runPackPlans(PackRunner.kt:149) → writeCbz(CbzWritePlan.kt:135)
    ```
    ⇒ **`readBytes()` 把整页读进一个 `ByteArray`,页有多大就申请多大**。更糟的是崩溃发生在**输出流已打开之后** ⇒ 目录里留下 **0 字节 CBZ**(`Vol 01 第01卷.cbz` = 0 B),正好命中验收原文里的「不留下半成品」。
    - **修法(用户裁决「改成流式两遍读」)**:`PageByteSource` / `PageBytes` 增 `openFor(itemId): InputStream?`(带默认实现 `bytesOf(...)?.inputStream()`,故既有内存实现与测试零改动);`CbzWriter.writeStreaming` 对每页读两遍 —— 第一遍流式算 `size` 与 `CRC32`(边读过丢),第二遍边读边写出,**峰值内存 = 一个 64 KiB 缓冲区**,与页大小无关。`STORED` 要求预知 size/CRC 这条硬约束不变。`PackRunner` 的探测也改成**只开流不读内容、开完立即关**(原先探测调 `bytesOf` 同样会整块读,且会泄漏文件描述符)。
    - **修复后真机复验**:同场景进程**存活无崩溃**;改用 4 张 8MB 真实文件的 `realbig` 工作区 ⇒ **「共 1 项：成功 1 · 跳过 0 · 失败 0」**,产物 `VolA.cbz` **33,555,158 B**。中断恢复提示也验过:崩溃后冷启显示 **「上次打包未完成（0 / 4）。可能没写完的输出文件：最多 4 个。」**
    - **仍未做的**:§10 要求的 **1GB 级**归档尚未实跑(设备只剩 4.6–3.24 GB,而 STORED 不压缩 ⇒ 1GB 输入需要等量输出,做一次就会把空间吃光);Zip64 实测、FAT32 目标上限、目标阅读器能否打开 2GB 以上包,均仍为空白。

    - **✅ 第 4 项「工作区实时刷新」(2026-10-19 用户提出并落地)**。用户原话:「应用使用过程中，我可能看到图片有问题或者缺失，会用文件管理器删除或添加或重命名图片，工作区应该实时刷新」。这原本不在五项退出条件里,但它使前四项在真实使用中**全部失效**(扫一次就再也不看磁盘,页数永远是旧的)。
      - **触发方式(用户三选一裁决:回前台重扫 + ContentObserver)**:①`ON_RESUME` 时重扫(冷启第一帧的 `restore()` 已经要扫,首次 `ON_RESUME` 必须**跳过**,否则冷启连扫两遍);②`ContentObserver` 监听树 URI(`notifyForDescendants = true` —— 变更发生在**深层子文档**,只监听树根会漏);注册失败(`SecurityException`)只记日志并返回 `null`,退回回前台重扫 —— 实时监听是**增强能力**,不能因为它坏了就连基本刷新都做不了。
      - **不退回 `Loading`**:刷新期间**保留旧列表**,用 `isRefreshing` 单独标记。否则千级缩略图列表会肉眼可见地闪一下。
      - **失败不清屏**:后台刷新失败(`Failed`)时**保留旧内容**(不是换成错误页)。理由:用户没做任何操作,列表突然变成错误页比「没刷新成功」更糟。只有授权失效(`Denied`)才走自动重试 → `Denied` 状态。
      - **变更风暴合并**:刷新进行中再次触发只置 `refreshQueued`,扫完补一轮,**不并行**。文件管理器批量删 100 张会发几十次通知,并行扫会互相覆盖。
      - **观察者不重挂**:`watchIfReady` 原来无条件 `closeWatcher()` + 重挂,修成 `if (watcher != null && watchedHandle == handle) return` —— 注销与重注册的窗口内到达的通知会丢,而那正是用户刚改完文件的高发时刻(这是**首版实现里真实存在的缺陷**,靠测试「刷新时不重复挂监听」钉住)。
      - **增量对账**(`domain/workflowa/WorkflowAReconcile.kt`,用户裁决 = 增量对账而非整组重置):`reconcileWorkflowAState` / `reconcilePageOrder` / `reconcileNamingState` 三个纯函数。语义 = **仍存在的图保留人工排序与改名,删掉的丢弃,新增的按规则插入**(插到「规则序里它前面最近的保留项」之后;同锚点多张新图时接在上一个之后,否则会被逐个前插反转)。热路径返回**同一实例**(id 集合没变且元数据没变),调用方可据此跳过重组。
      - **对账必须发生在组合期,不能放 `LaunchedEffect`**:子组件自己也有 `remember(collections)` 播种,先以旧状态组合、事后再更新**不会重新播种** ⇒ 界面看不到对账结果。这条**实测钉住**:`RefreshKeepsManualOrderTest` 去掉对账那一行即变红(M11)。
      - **修掉一个真实缺陷(重命名场景)**:`reconcilePageOrder` 原来在 `oldIds == newIds` 时**原样返回旧实例** ⇒ 而 `ExternalStorageProvider` **重命名后 `documentUri` 不变** ⇒ 界面永远显示改名前的旧文件名 —— 正好命中用户原话里的「重命名图片」。改为「id 集合相同 → 顺序与人工标记原样保留,但每项换成刷新后的元数据」。
      - **为什么监听 provider 而不是文件系统**:SAF 的 `DocumentFile` 没有跨目录批量监听能力,`FileObserver` 在 `content://` 上无从下手;`ContentObserver` 是唯一不额外申请权限、不常驻轮询的途径。**代价(已知且未解决)**:文件管理器若绕开 `DocumentsProvider` 直接改文件系统(adb `cp`/`rm` 就是这样),provider 不会 `notifyChange` ⇒ observer 收不到。这类变更只能靠回前台重扫兜住。
      - **真机核对**:删一张图后**停在应用内不刷新**(符合上述代价),`KEYCODE_HOME` → 回前台 4s 后 **56/32 → 55/31** ⇒ 回前台重扫生效;还原后回 56。
      - **手动刷新按钮:刻意不做**(用户 2026-10-19 追问「之前不是选的SAF自动刷新吗，怎么还要写手动按钮」后确认)。自动链路已够用,加按钮是冗余;若真实文件管理器场景下 observer 确实收不到,再补。
  - **存储空间预检与不足退路(2026-09-10 新增)**:手机空间不足是既定会发生的场景(用户确认"少数情况因此转电脑")→ 须验证:执行前按预估输出大小做空间预检、不足时明确拒绝并给出"转到电脑处理"的引导、且不留下半成品;
    - **✅ 领域层已落地(2026-10-12)**:`domain/storage/SpaceCheck.kt` —— `SpaceCheckResult`(Sufficient/Insufficient/Marginal 三态)、`SpaceThreshold`(Percentage/Absolute 两种阈值策略)、`checkSpace()` 纯领域判定 + `estimateCbzSize`/`estimateTotalSize` 预估。22 项单测全绿,3 点变异验证全捕获(不足判定 / 预估加法 / Marginal 判定)。阈值参数化,具体值待真机验证后定(§11 第 12 条)。
  - **大归档写入(2026-09-10 新增)**:真实样本存在 1GB~4GB 的 CBZ,而 D2a 的 `createAndWrite` 仅验证了小文件,**4GB 级写入在本项目是空白**。须真机验证:Kotlin/JVM 侧 zip 实现(`java.util.zip.ZipOutputStream`、Apache Commons Compress)对 Zip64 与"未知长度流式 STORED 条目"的实际支持、写入中断后的半成品识别与清理、FAT32 目标(单文件上限 4GiB−1)、以及**目标阅读器能否打开 Zip64 包并在 2GB 以上仍可用**;
    - **✅ 领域层已落地(2026-10-12)**:`domain/pack/Zip64Assessment.kt` —— `Zip32Limits` 常量(MAX_ENTRIES=65535, MAX_ENTRY_SIZE/MAX_CENTRAL_DIR_OFFSET=4GB−1)、`Zip64Requirement`(NotNeeded/Needed(reason))、`assessZip64Need()` 三阈值判定(按优先级报最先触发原因)、`isCompleteArchive()` 中断半成品识别(空文件或无 EOCD = 不完整)。22 项单测全绿,3 点变异验证全捕获(条目数 / 条目大小 / 完整性判定)。
  - **退出条件**:以下**五项**在目标机型可复现验证,且失败路径有明确的用户可理解提示 ——
    1. 千级缩略图**生成策略**;**2.** **后台任务与进度**(切后台恢复、进度上报);**3.** **授权失效恢复流程**(SecurityException → 引导重选目录);
    **4.** **存储空间预检与不足退路**;**5.** **大归档写入**(Zip64 / 中断恢复 / 目标文件系统上限)。
    > 第 4、5 两项**是正式退出条件,不是加固项**:§10《平台》把它们列为验收标准(「Android 真机完成 **1GB 级**归档的写入验证…更大归档的验收口径为"空间预检 + 明确拒绝 + 引导到电脑"」,并注明依据 §7/§9 D2b)。
    > 原文此处列 5 条却写"上述三项"属**列举不全**的笔误(2026-09-19 校正为"五项"),**不是**"只有三项算数"。
  - ~~Windows / Linux:文件访问与批量预览流程~~ —— **2026-09-19 移除**:与 §7「桌面不再有 GUI 目标,也不再为其投入开发」及 §10「**桌面端无验收项**」直接矛盾,系换栈(2026-09-10)前的遗留条目。领域模型**仍不得依赖 Windows 专有路径与打包机制**(该约束见 §7,继续有效),但这属于领域层的横向约束,不是 D2b 的验证项。

