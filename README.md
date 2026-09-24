# PixFold

图片整理与 CBZ 制作工作台。

> 当前状态：**设计阶段——D1 交互原型主线**（D2 Android SAF spike 已 5/5 验收；2026-09-10 技术栈重审换栈为 Kotlin + Jetpack Compose；**2026-09-16 D1 在新栈启动重建，2026-09-17 P1–P3 已交付、2026-09-18 P4 已交付、2026-09-19 P1–P7 全部交付、验收 20/20、D1 退出条件已达成**；**2026-09-21 用户定调:`pixfold-d1/` 的 UI 即 **D4 MVP 基座**(不再另起正式工程);**D4 拆为 D4a(UI 层真实功能,进行中)/ D4b(真实文件闭环,依赖 D2b+D3)**,打包留 D4b;阶段改为"按依赖并行推进"；分支拓扑与七阶段切分见 HANGOFF §9.1；**验收进度 20/20**（全部 20 项；P3–P7 均已收口），逐项状态见 HANGOFF §9；**设置与主题体系（D4 提前启动）已交付并真机核对**（`d1/settings-impl`，未合回 `dev`），验证 **224+278** 全绿、`lint` 零告警；**2026-09-24 修掉 P0 命名页头部问题**（命名页头部占约 88% 可视高度→折叠摘要栏 + 摘要行吸顶，固定区 692dp→154dp、首屏建议行 0→5 行以上；`app 280 + domain 229` 全绿，3 组变异全捕获；**真机核对已完成** 2026-09-24：固定区 **141dp**、首屏建议行 **0→5 条**，摘要栏吸顶与「展开自动回顶」均在真机复现），详见 `docs/notes/d1-ui-friction-inventory.md` §1；**2026-09-25 修掉 P1 长列表终局动作问题**（用户裁决「5 个列表内动作全移到底栏」：`RenamePlanPage`「确认执行」/ `ExecutionReportPage`「撤销·完成」/ `LibraryPage`「下一步 编辑元数据」/ `MetadataPage`「下一步 打包计划」/ `PackPreviewPage`「确认打包」；新增共享组件 `PageActionBar`，调用方放在页面级 `Column` 内、`LazyColumn` 之外，列表保留 `weight(1f)`；后两者 `FilterChip`→`Button`）；**顺带修掉真实缺陷**：`RenamePlanPage` 的 `contentInsets` 参数原先声明却从未使用；三处「Tab 会滚出视口」的错误注释已改写为事实（Tab 行在嵌套 `NavHost` 之外、不在任何滚动容器里，结构上不可能滚出视口；保留底栏的真实理由是这些是不可逆操作、且「下一步」是向导式顺序推进而非平级视图索引）；`app 282 + domain 229` 全绿（+2 条真机视口守卫）、`lint` 零告警、`assembleDebug` 成功、**6 组变异全捕获**；`PlanWiringTest.kt` 删掉 4 处 `performScrollToNode`（那正是本缺陷的显式编码）；**真机核对已通过**（2026-09-25，与下表 P1 续同轮），详见 `docs/notes/d1-ui-friction-inventory.md` §2 与 HANGOFF §9.1「底栏化轮」；**2026-09-25 P1 续：底栏左右分槽 + 步骤 1 头部伸缩**（用户真机走查后追加「步骤1上面部分也做个伸缩吧，让网格/列表的空间可以大点显示.并且下一步、完成之类的按钮都放到底栏的右侧，返回、上一步的按钮改到左侧」）：A 步骤 1 的 `SortRuleEditor` 折叠成一行摘要栏（**默认折叠**，折叠状态 hoist 在 `SortAndPreviewPage`，故 `SortRuleEditor` 自身不折叠 ⇒ `SortRuleDirectionTest.kt` 零改动；集合切换行/「共 N 张」行/网格-列表切换行**全部常驻**；`scopeLabel` **只在折叠态渲染**，否则 `onNodeWithText(batchLabel(...))` 精确匹配会命中 2 个节点）；`PageActionBar` 由单列改为**双槽** `leading`/`trailing`/`extraContent`，中间夹 **`Spacer(weight(1f))`**（刻意不用 `Arrangement.SpaceBetween`——单侧按钮时它会把唯一按钮推到左边），按钮**不得 `fillMaxWidth()`**⇒ A 步骤1 右「下一步」（从**模式行**移出）、A 步骤2 左「← 返回排序」/右「下一步：执行计划」、A 步骤3 左「← 返回命名结构」/右「确认执行」、报告页 左「撤销」/右「完成」、B 步骤1 右「下一步 编辑元数据」、B 步骤2/3 左「← 返回上一步」；两个步骤容器删掉内容上下方的常驻返回/推进 `Column`（A 侧 3 处、B 侧 1 处），改为给页面传**可空回调** `onBack`/`onNext`；**`app 287` + `domain 229` 全绿、`lint` 零告警、`assembleDebug` 成功，8 组变异全部捕获**；**新增 4 条位置守卫**——本次需求本质是**位置**，原先只有「按钮可见」类断言抓不住位置放反，故用 `getBoundsInRoot()` 比较 `.left`/`.right`（B 步骤2 左右分槽且同一行 / A 步骤2 左右分槽 / 报告页左右分槽 / A 步骤1「下一步」在右半屏**且点击真能推进**）；**真机核对已通过**（2026-09-25，PJX110 / Android 16 / 361dp×794dp）：七个位置逐一读数 —— A 步骤1「下一步」x989-1138 y2633-2703（改造前在模式行内 y791-861）；A 步骤2 左「← 返回排序」x84-344 / 右「下一步：执行计划」x742-1138 同排；A 步骤3 左「← 返回命名结构」/ 右「确认执行」同排；报告页 左「撤销」x126-423 / 右「完成」x1038-1138（改造前紧邻居中）；B 步骤1 右「下一步 编辑元数据」x730-1138；B 步骤2/3 左「← 返回上一步」x84-394，**且改造前位于 Tab 行下方 y399-469（内容之上）的返回入口已消失**；**A 步骤1 折叠收益实测：折叠态首图 y1520、网格可用高约 1113px；展开态首图 y2080、约 553px ⇒ 折叠多出 560px ≈ 155dp（+38%）**，详见 `docs/notes/d1-ui-friction-inventory.md` §2b 与 HANGOFF §9.1「底栏分槽 + 步骤1 头部伸缩轮」；**2026-09-25 P1 续·底栏视觉规格（降高 + 底色 + 撤销红）**（用户真机走查后追加「底栏高度可以稍微低一点，而且有的按钮还是纯文字描述而不是带背景的按钮，步骤3的撤销按钮背景改红色」）：三条需求 —— ①底栏降高：外层 `Column` 去掉 `spacedBy(6.dp)`、垂直内边距 8→**6dp**、行内间距 6→**4dp** ⇒ 单按钮页 **71dp→61dp**（实测 61.14dp）；**material3 1.4.0 源码级事实**：`ButtonDefaults.MinHeight = ButtonSmallTokens.ContainerHeight = 40.0.dp`、ContentPadding 上下各 8dp，但 `Button` 外层 `Surface` 带 `minimumInteractiveComponentSize()` ⇒ **实际渲染高 48dp**；`FilledTonalButton`/`OutlinedButton`/`ElevatedButton` **复用同一套**尺寸 ⇒ **换控件类型不改变尺寸，只改变是否画填充底色**（故 ②③ 与 ① 不冲突）；下限 = **48dp 触控尺寸（约束 7）**，内边距不可再往 0 收。② 返回类 4 处 `TextButton`→**`FilledTonalButton`**（`NamingPage` cd「返回步骤1」/ `RenamePlanPage` cd「返回步骤2」/ `MetadataPage` **仅底栏那处**（页面内另 5 处 `TextButton` 是别的操作，不能连带换）/ `PackPreviewPage`）。③ 报告页「撤销本次执行」`OutlinedButton`→`Button` + `colors = ButtonDefaults.buttonColors(containerColor = colorScheme.error, contentColor = colorScheme.onError)`（实测 `#B3261E`；**用语义角色而非写死红色**，禁用态交给 `buttonColors` 默认 `disabled*` 灰化）。⚠️ **2026-09-26 上面「不用 `errorContainer`」被用户驳回并已修正**：该判断**只对浅色成立**，而真机是**深色**——深色下 `error` 是**淡粉** `#F2B8B5`，正是用户收紧措辞（「表示这是个危险性质的按钮」）所指的「不像危险动作」。修正：新增 `Theme.kt` 的 `dangerActionButtonColors()`，按 `background.luminance() < 0.5f` 在两档间切换——**浅色走 `error`**（`#B3261E`）、**深色走 `errorContainer`**（`#8C1D18`），两档各取所长、零新增色值；`ExecutionReportPage.kt:120` 改用它。**原 4 条像素守卫全部假绿**（裸组合页面、没包 `PixFoldTheme` ⇒ 采到 material3 基线浅色，而设备是深色）——已改为经 `setContentLight`/`setContentDark` 在真实主题下取样；且原「偏红」断言抓不住本缺陷（深色基线 `#F2B8B5` 的 `r>g+40 && r>b+40` 照样通过），故**新增明度判据 `lum < 0.25`** 并把测试拆成浅/深两条。`app 292`（291 → +1 条深色守卫）/ `domain 229` 全绿、lint 零告警；**2 组变异各自命中自己那条**（永远用 `error` → 深色用例，报错逐字 `实测亮度=0.5649442091904655 (#FFF2B8B5)`，**精确复现用户抱怨的颜色且旧断言会放行**；浅色分支改 `errorContainer` → 浅色用例 `#FFF9DEDC`）。**教训**⚠️：`git checkout -- <file>` 会连未提交的改动一起丢掉，变异还原必须用 `cp` 备份文件。详见 `docs/notes/d1-ui-friction-inventory.md` §2d 与 HANGOFF §9.1「撤销按钮深色不像危险动作」轮）。**新增 4 条像素级守卫 `BottomBarStyleTest.kt`**：本轮三条需求**全都是「语义树分辨不出」的**——`TextButton` 与 `FilledTonalButton` 语义树完全一样（`Role.Button` + 同一段文字）、`OutlinedButton` 与红底 `Button` 同样无从区分、`getBoundsInRoot()` 只比 `.left`/`.right` 抓不住「没底色」「不是红」「底栏偏高」⇒ 必须**取像素**（`@GraphicsMode(NATIVE)` + `captureToImage().asAndroidBitmap()`，LEGACY 后端画不出真实颜色）；判据与实测：高度 `56f<=h<=64f`→61.14dp、有底色（按钮容器色 vs 同排空白处底色通道差 ≥12）→底 `#FEF7FF`/返回 `#E8DEF8`/下一步 `#6750A4`、撤销偏红 `r>g+40 && r>b+40`→`#B3261E`；取样点 = 垂直居中、水平距左边缘 4dp（胶囊形填充延伸到左边缘，且避开居中文字字形）；**断「偏红」而不写死 `#RRGGBB`**（写死会在切换强调色/换深色主题时误报）。这层正是第 4 层验收「UI 判断是否符合需要的操作逻辑」的**视觉子集**——前 3 层在控件类型换错时会**全绿**，因为换错类型**不改变任何语义、只改变像素**。`app 291`（287→+4 条像素守卫）+ `domain 229` 全绿、`lint` 零告警、`assembleDebug` 成功（APK **33,549,783 B**，较上轮仅 +64 B）；**5 组变异全部捕获且各自只命中预期的那一条**（高度退回→`height` / B 步骤2 返回退回 `TextButton`→`workflow B step 2` / A 步骤2 同→`workflow A step 2` / 撤销退回默认主色底→`undo` / 撤销 `error`→`primary`→`undo`），变异脚本 `app/build/mut_p1c.py`（不跟踪）；**教训**：`DpRect` **没有 `center`**（与 `androidx.compose.ui.geometry.Rect` 不同，须自算 `(top+bottom)/2`，写 `bounds.center.y` 编译失败）；像素取样取在文字字形上会把「有没有底色」与「文字反色差异」混在一起。**✅ 真机核对已通过**（2026-09-26，PJX110 / Android 16 / 361dp×794dp，用户此前授权本轮）：底栏触控框 48dp、可视填充 40dp、总高 ≈61dp（与 Robolectric 探针 61.14dp 一致），最后非黑像素距屏底 36px 落在手势区内不被遮挡；返回类按钮确认有 tonal 底色、推进类为 primary 填充；B 步骤2 最宽配对 [42,520]+[695,1222] 未溢出 1264 宽屏；详见 `docs/notes/d1-ui-friction-inventory.md` §2c 与 HANGOFF §9.1「底栏视觉规格轮」。**2026-09-21 补齐**2026-09-21 补齐「色彩标准 2021≠2025」接线断言（3 组变异全捕获）**，并**修掉**该轮实测出的两个问题：①「色彩标准」对 9 个色彩风格中的 5 个无操作 → 设置页对该 5 个风格**置灰该行**；②配色只映射约 24 个角色致深色下卡片为 material3 基线紫灰 → **补全 19 个扩展角色映射**（深色下卡片色相错配已消除，**2026-09-21 真机核对通过**），详见 HANGOFF §9.1 与 `docs/notes/d1-ui-friction-inventory.md` 第 10、11 节）；**2026-09-21 修掉「工作流内部预测性返回无效」**（步骤容器原用 `BackHandler`，优先级高于根级 `PredictiveBackHandler` → 工作流内无手势跟随动画）：根级容器提升为公共组件并透传开关，新增 9 条断言、5 组变异全捕获；**2026-09-21 真机量化核对通过**：工作流 A 步骤 2 慢速边缘滑动中，工作流页面绕屏幕中心随进度缩放（顶端分隔线 y 346→418、跨度 1263→1178，由 x/y 两轴独立反推的进度一致），**关掉开关后同一滑动内容逐值不变**，松手仍完成 2→1 层级后退）；**2026-09-22 完成导航迁移到 Navigation Compose**（用户裁决「不再手写两层渲染，改为把导航整体迁移到 Navigation Compose」，落地 HANGOFF §9.1 第 3 步「目标页显示在背后」）：根导航由手写 `when (screen)` 改为 **type-safe `@Serializable` route 的 `NavHost`**，两个步骤容器改为**嵌套 NavHost**（栈深 1 时自动禁用内层返回处理器），预测性转场在 NavHost 级配置（`predictivePopEnterTransition = None` 让目标页静止在背后 + `predictivePopExitTransition = scaleOut(0.90f)`，开关关闭时退为 `None`），旧 `PredictiveBackContainer` **整体删除**，新增 `DestinationSurface` 不透明底座；**app 273 + domain 224 全绿、`lint` 零告警**，**真机核对已通过**；**2026-09-22 追加:返回转场改为「分层」**（用户看真机录像后判定「动画有点长且生硬」，逐字给出两条规格 —— 工作流内部「侧面拖动越多当前页越透明然后显示上级页面」、大页面切换「当前页面虚化缩小」；进入动画仍瞬切）：新增 `NavTransitionStyle { Step, Page }` + `PixFoldNavHost` 的 `style` 必填参数，**步骤级 = `fadeOut()`（不缩放）/ 根级 = `scaleOut(0.90f) + fadeOut()`**（⚠️ `scaleOut` 产物只含 scale 不含 alpha，漏 `fadeOut` 即成「只缩小不虚化」；「虚化」以 alpha 近似，该 compose 版本无模糊原语）；形态断言迁入新建 `TransitionStyleTest.kt`，`PredictiveBackTest` **7 → 4** 条，**6 组变异全被杀死**；**2026-09-22 真机核对通过**（用户逐字「实测现在这样很不错了，后续还要改就得到走查阶段了」⇒ 形态定稿，后续调整推迟到走查阶段），完整记录见 `docs/notes/d1-navigation-compose-migration.md` §7.8）；**2026-09-22 启动图标 MD 主题化 + minSdk 提到 30**：启动图标由单层 `drawable` vector 改为**三层自适应图标**（`mipmap-anydpi/ic_launcher.xml` = `background` + `foreground` + **`monochrome`** 单色层，随系统 Material You 主题着色；字形沿用占位方块、正式图标后绘），API 24/25 的 48dp 回退档**整体删除**；`minSdk` 由 24 提到 **30**（用户裁决「至少 30 以下完全没必要」；**Java 工具链仍为 17**），`mipmap-anydpi-v26/` 随之并入 `mipmap-anydpi/`；**连带现象（非缺陷）**：minSdk≥28 时 AGP **有意不压缩 dex**（AGP `DexPackaging` DSL 逐字「If null, dex files will be uncompressed when minSdk >= 28.」，为让 ART 直接 mmap 加载、省安装期解压），debug APK 由 13 MB 变为 33 MB，逐档实测 26→13,040,830 B / 28·29·30→33.2 MB，用户裁决**保持默认**（自用侧载不经分发，且只影响 APK、AAB 不受影响），详见 `docs/notes/d1-toolchain-evidence.md` §9.7；新增 `LauncherIconTest`（5 用例，含「单色层与前景字形同步」源码结构断言）；**app 278 + domain 224 全绿、`lint` 零告警**；**2026-09-24 核实 16 KB 页大小已达标且零配置**（`zipalign -c -P 16` → `Verification successful`，4 条 `.so` 偏移 `% 16384` 全为 0、ELF 段 `Align` 全为 `0x4000`、APK 内均 STORED 未压缩；AGP 9.4.1 ≥ 8.5.1 自动对齐，无需 `packaging` 配置，详见 `docs/notes/d1-toolchain-evidence.md` §10）。**2026-09-25 P1 底栏化 + 底栏分槽/步骤1 头部伸缩**已提交（`1c95db9`，19 files +1592/−201，含底栏降高 71→61dp、返回类 4 处改 `FilledTonalButton`、撤销按钮危险红的深浅双档 `dangerActionButtonColors()`；新增像素级守卫 `BottomBarStyleTest.kt`，真机读数 61.14dp 与离线探针一致）；**2026-09-26 minSdk 30 → 34**（`f3cfb08`，5 files +56/−21）：起因是应用内语言依赖 `LocaleManager`/`LocaleConfig`（API 33+），而 **33 与 34 的代码清理成本完全相同**（都删同一批 3 处 `ObsoleteSdkInt` 守卫），故取 34 额外获得 **HDR gainmap**（`android/graphics/Gainmap`、`Bitmap.getGainmap()/hasGainmap()/setGainmap()`）供 D3/D4 图片管线用；连带删除 `AndroidManifest.xml` 的 `xmlns:tools`/`tools:targetApi`、`PixFoldApp.kt` 与 `Theme.kt` 的 `Build.VERSION` 守卫（`settings.monetEnabled && SDK_INT >= S` → `if (settings.monetEnabled)`）；`compileSdk 37`/`targetSdk 36`/Java 17 **不动**；**零测试改动**（既有 `@Config(sdk=[34])` 与之一致；`d1-toolchain-evidence.md` §9.5 早先「29 个测试文件须改 `[36]`」是 minSdk=36 的代价，已纠正），`app 292 + domain 229` 全绿、`lint` 零告警、APK **33,046,763 B**，详见 `docs/notes/d1-toolchain-evidence.md` §9.8。⚠️ 两提交在 `Theme.kt` 上刻意造**中间态**（保留 Build 守卫 + 保留危险色函数）使各自独立过 lint（方案 C）。
> 目标平台：**Android（唯一 GUI 平台）**；Windows / Linux 不再是 GUI 目标平台
> 技术栈：**Kotlin + Jetpack Compose（Android 原生，2026-09-10 锁定，替换 Flutter）**；决策依据与对照方案见 HANGOFF §8

## 项目目标

PixFold 不是简单地把两个命令行脚本包进一个窗口。它要把图片排序、命名结构、漫画分组、CBZ 元数据和输出策略等需要人工判断的过程，做成一个：

- 可以预览；
- 可以逐项编辑；
- 可以批量设置后单项覆盖；
- 可以在执行前检查完整计划；
- 可以保留源文件并支持回退；
- 能在 Android 上工作的图形化应用（Windows / Linux 仅作为手机空间不足时的退路，不提供 GUI）。

自动化只负责提出建议，最终结果由用户确认。

## 当前已有脚本

`scripts/` 中保留两个 Python 脚本，作为现有行为参考、回归样例和命令行备用工具：

- `batch_rename_images.py`：递归扫描、图片排序、命名、移动/复制和冲突处理；
- `batch_pack_cbz.py`：漫画目录识别、元数据推导、语言/卷号处理、ComicInfo.xml 生成、CBZ 打包和已有 CBZ 更新。

脚本不会直接被当成最终产品规范。GUI 需要把其中的交互补全，并避免危险的静默操作。

## 设计重点

1. **扫描、决策、执行分离**：扫描阶段不改文件，用户确认后才执行计划。
2. **建议不等于事实**：自动识别的排序、名称、卷号和语言都必须可见、可改。
3. **图片顺序可视化**：支持缩略图预览、自然排序、拖拽调整和逐项固定位置。
4. **命名结构可编排**：前缀、目录片段、序号、原名和自定义文本都可以组合与调整。
5. **CBZ 元数据逐卷确认**：title、series、writer、volume、language 和输出名都支持逐项编辑。
6. **计划优先**：执行前展示重命名、复制、移动、打包、覆盖和删除的完整计划。
7. **安全默认值**：默认保留源文件，删除必须独立确认并清楚列出影响范围。
8. **Android 优先验证**：优先验证 SAF 目录授权、扫描、预览、创建和重命名能力。
9. **UI 符合最新 Material Design**：颜色/排版/形状走 material3 语义角色（不硬编码），支持深色模式与 Android 12+ 动态取色（HANGOFF §6.5）。当前基准为 material3 1.4.0（stable）；M3E 的公开 API 目前只在 alpha 版，留待走查后评估。

## 设计阶段路线

```text
D0  固化需求与真实样例
 ↓
D1  完成交互原型，不连接真实文件系统          ← 当前：新栈重建中（7 阶段，见 HANGOFF §9.1）
 ↓
D2a SAF 闭环 spike（已提前完成 5/5）
 ↓
D2b 完整平台能力：缩略图策略 / 后台任务 / 授权失效恢复 / 大归档写入
 ↓
D3  拆分可测试的领域核心与平台适配层
 ↓
D4  交付 Android 基础闭环（桌面端已移出范围）
 ↓
D5  增加模板、撤销、更新 CBZ、多系列批处理等增强功能
```

> 进度(2026-09-08)：D0 已完成；D2 的 Android SAF spike 按 HANGOFF §8.2 提前执行并 **5/5 验收**，证伪条件 ①② 排除；当前主线为 D1 交互原型（不连真实文件系统）。执行顺序与判定以 HANGOFF §8.2 / §8.3 / §9 为准。
> 进度(2026-09-09，**Flutter 原型时期，已随换栈作废**)：D1 交互原型已启动于 `C:\Personal\pixfold-d1`（仓库外，确定性 mock 数据）。该轮的修复细节随换栈移除，其中属于设计意图的部分（预览自适应+缩放拖动、拖拽视觉要有包络）已入 HANGOFF §9 D1 验收清单 / §12.5。
> 进度(2026-09-10)：**拖拽定案自研**（三方包 drop 不落地 → 弃用，原型**零三方依赖**）；**流程优化**：§9 新增《D1 验收清单》（20 项逐条打勾）+ 阶段门槛表（每阶段可判定退出条件）+ **决策门**（清单全绿才创建正式工程）+ D2 拆分为 D2a/D2b + “修复自证三层”约定。

> 进度(2026-09-10 晚):**技术栈重审换栈**。D1 走查发现桌面端“元素过大、信息密度低、一屏看不了多少”，未达 D1 预期 → 触发 HANGOFF §8.2 证伪条件 ③，据此把技术栈由 Flutter + Dart 换为 **Kotlin + Jetpack Compose（Android 原生）**，并明确 **GUI 只做 Android**（Windows / Linux 移出目标平台）。判定依据、继承与作废清单见 HANGOFF §8.1 / §8.2；原型需在新栈重建，D1 验收清单 20 项**判据不变**。

> 进度(2026-09-13)：**换栈收口——验证工程已删除并归档**。`pixfold-d1`（Flutter 原型）与 `pixfold-saf-spike`（SAF spike）两个工程目录已从磁盘删除（`ready` 分支从未包含过它们），删除前整树归档在分支 `archive-flutter-verify`（tip `9efef5b`；**2026-09-16 复检：该分支已推远程 `origin`**，本地无同名分支，取用走 `origin/archive-flutter-verify`）。因此文档中出现的 `C:\Personal\pixfold-d1` / `C:\Personal\pixfold-spike` 路径**均已失效**，需按“归档分支中的历史快照”理解。归档中仍有参考价值的是 **`pixfold-saf-spike` 的 Kotlin SAF 实现**（新栈平台层可直接参考）与旧原型领域层的测试语义（代码本身作废、不迁移）。**当前无任何原型代码，D1 需从零重建**；详见 HANGOFF §12.7。

> 进度(2026-09-16)：**环境复检——修正文档漂移，环境比原记录更完整**。逐项实测后确认：`platforms;android-36` 与 cmdline-tools 23.0.0 **均已就位**（原标 ⬜ 待装 / 偏旧）；Kotlin/Gradle 侧**已有一次成功构建实证**（2026-09-12，Gradle 9.6.0 / AGP 9.4.0 / Kotlin 2.2.10）；**adb / fastboot 曾被 `C:\Windows\System32` 的第三方残留（adb 33.0.0）遮蔽**，已清除并验收通过；归档分支 `archive-flutter-verify` **已推远程**（原先"仅本地"的风险已消除）。详见 HANGOFF §12 顶部横幅与 §12.2 / §12.5 / §12.7。

> 进度(2026-09-16，**D1 启动重建**)：**分支模型与阶段切分固化**——`ready`（设计文档线）→ **`dev`（开发主线）** → 阶段分支 `d1/p1…p7`（从 `dev` 开、完成即合回 `dev` 并回写文档），D1 全绿后 `dev` → `main` 发 release，此后开发继续在 `dev`；原型代码改为**在仓库内**开发（不再放仓库外目录，避免 09-13 的整树丢失），详见 HANGOFF §9.1。**构建链已在 `temurin-17` 下完整实证**（探针工程：`assembleDebug` + 纯 Kotlin JVM 单测 + Robolectric Compose UI 测试 + `lint` 全绿），原"temurin-17 下的完整构建待 D1 确认"一项**已关闭**；同批实测确认两条换栈坑（AGP 9 内置 Kotlin 不得再加 `kotlin-android`、Compose 插件须锁 2.2.10；Kotlin `Regex.split` 丢弃捕获组会导致自然排序静默失效）。证据见 `docs/notes/d1-toolchain-evidence.md`。

> 进度(2026-09-17，**P1–P3 交付 / 真机接入**)：**真机已接入**（用户手机固定无线调试地址 `192.168.43.1:4444`，PJX110 / Android 16），依赖真机的验收项不再挂起。**P1**（AGP 9.4 / Gradle 9.6 / Compose BOM 2026.02.01 + material3 1.4.0 脚手架、领域地基、确定性 mock 数据）与 **P2**（缩略图网格/列表双视图、大图预览含自适应/双击缩放/平移/翻页）**完成**；**P3 完成**——网格与列表双视图拖拽（松手落地）、图钉入口、人工调整标记与一键重置、多级排序规则编辑器（含批次默认 / 本组独立）已交付并真机实证。**P4 完成**——命名结构编辑器（组件启用 / 顺序 / 分隔符 / 大小写 / 补零 / 扩展名策略 / 自动清洗）、命名建议表（原路径→新名）、逐项覆盖（改结构不丢失）、冲突与警告（重名 / 非法字符 / 大小写冲突 / 超长，含筛选）已交付并真机实证；根目录名默认**跟随集合**，可改为手工指定。**P5 完成**——工作流 B 的三步流程（漫画库分组与勾选 → 逐卷元数据（建议值带来源 / 语言默认「未设置」须人工确认 / 批量设置不覆盖逐项例外）→ ComicInfo.xml 与页码列表预览）已完成**三层自证（领域单测 + UI 断言 + 真机核对）**，第 11–14 项转 ✅。**D1 验收清单 20/20 全部通过**，D1 退出条件（§9 门槛表）已达成。自证实况：领域 219 项 + UI 238 项测试全绿，`lint` 零告警；真机证据见 §9.1「P5/P6/P7 收口记录」。**真机走查方法提醒**：`adb shell input swipe` 触发不了长按手势，拖拽须用 `adb shell input draganddrop`（详见 HANGOFF §12.5 的"假阴性陷阱"）。

## 开发环境部署(⚠️ 暂定)

> 本节已按 **Kotlin + Jetpack Compose**（HANGOFF §8.1）重写，并同步 **2026-09-16 复检**的环境实况（SDK 根由 Android Studio 管理）。完整清单、版本锚点与踩坑见 [HANGOFF.md](HANGOFF.md) §12。

- **JDK**：mise 管理，`.mise.toml` 声明 `java = "temurin-17"`（当前 17.0.20+101）；`JAVA_HOME` 已 setx。另：Studio 自带 **JBR 25.0.3** 是完整 JDK，Gradle 在其下亦实测可启动；AGP 9.x 要求 **JDK 最低 17**，两者都满足。**Gradle 不读 `.mise.toml`**，命令行构建靠 `JAVA_HOME`，故建议保留 mise 声明（理由见 §12.2 末注）。
- **Android Studio**：scoop 安装（2026.1.4.7，自带 JBR，无需外部 Java 即可启动）。
- **Android SDK**：根目录 `C:\Users\Administrator\AppData\Local\Android\Sdk`（Studio 的默认位置，由 Studio 的 SDK Manager 管理）。现有组件：platform-tools **37.0.1**、build-tools 35.0.1 / 36.0.0、platforms **android-36 + android-37.0**、sources android-36 + android-37.0、emulator、system-images;android-36、licenses、cmdline-tools **23.0.0**。
- **环境变量（Studio 不会设，必须手工设）**：`ANDROID_HOME` 指向上述 SDK 根；用户 PATH 加入 `<sdk>\platform-tools`（adb / fastboot）与 `<sdk>\cmdline-tools\latest\bin`（sdkmanager）。注意 `ANDROID_SDK_HOME` 是历史变量（老工具的 `.android` 位置），**不是** SDK 路径。
- **adb**：一律来自 SDK 的 platform-tools；不装独立 adb 包。⚠️ **第三方驱动/模拟器可能把 adb 塞进 `C:\Windows\System32`（靠 PATH 顺序遮蔽 SDK 那份）**——验证时用 `Get-Command adb -All` 确认只有一份，详见 HANGOFF §12.5。
- **Gradle / Kotlin**：✅ **已有构建实证** —— 2026-09-12 一次 `BUILD SUCCESSFUL`（Studio 模板工程，Gradle 9.6.0 / AGP 9.4.0 / Kotlin 2.2.10，跑在 JBR 25 上）；**2026-09-16 已在 `temurin-17` 下完整复验通过**（探针工程：`assembleDebug` + 纯 JVM 单测 + Robolectric UI 测试 + `lint` 全绿，证据 `docs/notes/d1-toolchain-evidence.md`）。**AGP 9 起 Kotlin 内置，无需单独安装 Kotlin 工具链**，且勿手工加 `kotlin-android` 插件（与新 DSL 冲突，见 §12.5）；**Compose 编译器插件仍需单独应用，版本须锁 AGP 内置的 KGP 版本（2.2.10）**。
- **验收**：`sdkmanager --list` 组件齐全 + `adb devices` 能看到手机（当前无设备在线，配对记录仍在，重开无线调试即可）。

```powershell
# ═══ PixFold 开发环境重建命令(项目相关步骤;基础工具链 scoop/mise/pwsh 视为已就绪)═══

# 1) 按项目 .mise.toml 安装版本声明(java temurin-17;幂等,只补缺失)
mise install

# 2) Android Studio(SDK 由它的 SDK Manager 安装到默认位置)
scoop install android-studio
#   首次启动完成 Setup Wizard,勾选 Android SDK Platform-Tools 等组件

# 3) 环境变量(Studio 不会设;新终端才生效)
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
[Environment]::SetEnvironmentVariable("ANDROID_HOME", $sdk, "User")
#   用户 PATH 追加: <sdk>\platform-tools 与 <sdk>\cmdline-tools\latest\bin

# 4) 平台组件(新终端;目标 compileSdk 36)
sdkmanager "platforms;android-36" "build-tools;36.0.0"

# 5) 验收:组件齐全 + 设备在线 + adb 唯一
sdkmanager --list
adb devices
Get-Command adb -All      # 应只解析到 <sdk>\platform-tools\adb.exe(见 HANGOFF §12.5)
```

> 安装 `sources;*` 这类含上万个小文件的包前，先把 **SDK 目录**加入杀软信任区，否则可能报 `AccessDeniedException`（见 HANGOFF §12.5）。

## 文档

- [`HANGOFF.md`](HANGOFF.md)：产品定位、工作流、领域模型、平台策略、设计路线（含 §9.1 分支拓扑与阶段切分）、验收标准与开发环境实况(§12)；
- [`scripts/`](scripts/)：现有 Python 行为参考和回归样例；
- [`docs/notes/`](docs/notes/)：调研与实证笔记（归档原型领域语义、脚本行为矩阵、构建链实证）；
- [`docs/superpowers/`](docs/superpowers/)：D1 设计规格（`specs/`）与分阶段实施计划（`plans/`）。

## License

[MIT](LICENSE) © Cyanix-0721
