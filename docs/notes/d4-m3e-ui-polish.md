# D4 Material 3 Expressive UI 全面升级笔记（U1–U4，2026-11-24）

> **性质**：调研与实证笔记（非产品权威；产品决策唯一权威仍以 [`docs/product/HANDBOOK.md`](../product/HANDBOOK.md) 为准）。
> **分支与范围**：阶段分支 `d4/m3e-ui-polish`（基于 `dev` 的 M1–M9 合并基线 `d1dc9a3`；已以合并提交 `60b6016` 合回 `dev` 并删除），按 **U1 → U2 → U3 → U4** 四个切片完成 PixFold Android 原生 Compose UI 的 Material 3 Expressive（`material3 1.5.0-rc01`）全面打磨。
> **真机核对状态（重要）**：**真机核对待补（顺延至另一台机器与 M1–M9 一并真机核对）** —— 本轮 U1–U4 全部通过纯 JVM `@GraphicsMode(NATIVE)` 布局/无障碍守卫、Roborazzi 25 张黄金图视觉回归与定向语义变异验证，**尚未在真机上实测**，顺延至另一台机器与 M1–M9（`d4/opt-and-non-adb-tests`）一并真机联合核对（详见 §5）。

---

## 1. 经字节码核实的 `material3 1.5.0-rc01` API 边界

全部 API 与 `@OptIn` 要求均通过 `javap` 直接核验本机 Gradle 缓存中的 `material3-android-1.5.0-rc01.aar/classes.jar`，并在 `:app:lintDebug`（`warningsAsErrors = true`）下保持零未使用 `@OptIn` 与零编译警告：

| 类别 | 实际使用的 API | `@OptIn` 要求 | 关键实测/字节码陷阱与应对 |
| --- | --- | --- | --- |
| 主题与动效 | `MaterialExpressiveTheme`、`MotionScheme.expressive()` | 无需 `@OptIn` | `Theme.kt` 新增 `LocalIsPureBlack` 与 `pixFoldSurfaceBorder()` / `pixFoldCardContainerColor()` / `pixFoldSegmentedColors()`，统一补齐纯黑模式 `1.dp outlineVariant` 描边 |
| 表达型形状徽章 | `MaterialShapes`（`Cookie9Sided`、`Cookie7Sided`、`Clover4Leaf`、`Arch`、`SoftBurst`）+ `RoundedPolygon.toShape()` | 需 `@OptIn(ExperimentalMaterial3ExpressiveApi::class)` | 封装于 `PixFoldShapeBadge` 与局部私有 `@Composable`，避免大范围扩散实验注解；零硬编码 `RoundedCornerShape(...)` |
| 表达型加载指示器 | `LoadingIndicator`、`ContainedLoadingIndicator` | 需 `@OptIn(ExperimentalMaterial3ExpressiveApi::class)` | 用于 `WorkspaceBar` 扫描态与 `CbzUpdateProgressDialog` 未知条目数不确定态 |
| 波浪进度指示器 | `LinearWavyProgressIndicator`、`CircularWavyProgressIndicator` | 无需 `@OptIn` | 参数接收 `stroke: Stroke` / `trackStroke: Stroke`（非 `Dp`）；确定态自带 `ProgressBarRangeInfo` 语义，`TaskProgressBarTest` / `PackProgressDialogTest` 零放宽通过 |
| 分段列表项 | `SegmentedListItem`、`ListItemDefaults.segmentedShapes(index, count)`、`ListItemDefaults.SegmentedGap` | 无需 `@OptIn` | ① 默认 `containerColor` 为 `surface` 会与画布融色 ⇒ 封装 `PixFoldSegmentedItem` 强制抬升至 `surfaceContainerLow` 并在纯黑下加 `1.dp` 描边；② 内置 `mergeDescendants = true`，内部带独立 `testTag` 的子节点须显式 `.semantics(mergeDescendants = true) {}` 保持独立语义边界 |
| 形变按钮与图标按钮 | `Button` / `FilledTonalButton` / `OutlinedButton` / `TextButton`（`shapes = ButtonDefaults.shapes()`）；`IconButton` / `FilledTonalIconButton` / `FilledTonalIconToggleButton`（`shapes = IconButtonDefaults.shapes()` / `toggleableShapes()`） | 无需 `@OptIn` | 危险动作继续走 `dangerActionButtonColors()` / `dangerOutlineButtonColors()` + `dangerOutlineBorder()`，深浅与纯黑下维持暗红明度 `< 0.25` 与视觉重量三级区分 |
| 连接式切换按钮组 | `FilledTonalToggleButton`、`FilledTonalToggleButtonDefaults`、`ButtonGroupDefaults.ConnectedSpaceBetween`、`connectedLeading/Middle/TrailingButtonShapes()` | 无需 `@OptIn` | 固定 2–3 项（主题模式 / 网格列表切换 / 全部-仅问题筛选）使用 `Row(spacedBy(ConnectedSpaceBetween))` 而非溢出折叠 `ButtonGroup`，在 `361dp × 1.3×` 字号下零裁切、高度 `>= 48.dp`，纯黑未选中态补 `1.dp` 描边 |
| 浮动工具栏 | `HorizontalFloatingToolbar`、`FloatingToolbarDefaults.vibrantFloatingToolbarColors()` | 无需 `@OptIn` | 用于 `ImagePreview` 底部翻页/页码/重命名控制条；内置语义合并，内部 `TAG_PREVIEW_PAGE_LABEL` 显式加 `.semantics(mergeDescendants = true) {}` 保住既有文本断言，所有图标按钮 `48.dp` 触控目标 |
| 强调排版与扩展圆角 | `MaterialTheme.typography.*Emphasized`、`MaterialTheme.shapes.largeIncreased`（20dp）/`extraLarge`（28dp） | 无需 `@OptIn` | 全仓零硬编码 `RoundedCornerShape(...)`；注：`material3 1.5.0-rc01` 默认 `Typography()` 构造中 `*Emphasized` 默认继承常规字重（`TypeScaleTokens.LabelLargeEmphasizedWeight` 为 `Bold` 须自定义 `Typography` 才激活），组件层统一绑定语义角色以便后续主题排版演进零改动生效 |

---

## 2. 切片落地总览（U1–U4）

| 切片 | 提交 | 核心改动范围 | 新增/更新专项守卫与资源 |
| --- | --- | --- | --- |
| **U1 — M3E 基础原语与纯黑规范** | `9b6b00a` | `Theme.kt`（`LocalIsPureBlack` + 纯黑描边/分段配色辅助）、新建 `ExpressivePrimitives.kt`（`PixFoldShapeBadge`/`PixFoldSegmentedItem`/`PixFoldStatusPill`/`PixFoldSectionCard`/`PixFoldEmptyState`/`PixFoldTopBar`）、`PixFoldDialog.kt`（纯黑 `1.dp` 描边、`heroBadge` 槽、`headlineSmallEmphasized`、形变按钮）、`PageActionBar.kt`（`surfaceContainer` 色调底 + 维持 56–64dp 高度） | 新增 `ExpressiveFoundationTest`；刷新底栏与撤销弹窗 4 张黄金图 |
| **U2 — 首页、工作区与设置/许可页** | `2d010ce` | `HomeScreen.kt`（`Cookie9Sided` / `Clover4Leaf` 徽章 + `headlineMediumEmphasized` + tonal 设置按钮）、`WorkspaceBar.kt`（从属紧凑胶囊 + 扫描态 `LoadingIndicator`）、`WorkspaceListDialog.kt`（分段工作区项 + `FilledTonalIconToggleButton` 图钉）、`SettingsScreen.kt`（`PixFoldTopBar` + 4 分组分段卡片 + 连接式主题切换 + `Cookie9Sided` 强调色色块预览 + 保留色彩标准弹窗裁决）、`LicensesScreen.kt` | 新增 5 枚 Material Symbols Rounded 24dp 矢量图（`ic_photo_library`、`ic_auto_stories`、`ic_folder_open`、`ic_chevron_right`、`ic_check`）；新增 `M3eHomeWorkspaceSettingsTest`；刷新 6 张 + 新增 3 张黄金图 |
| **U3 — 工作流 A / B / C 进度、步进与卡片** | `1709e28` | `TaskProgressBar`（`LinearWavyProgressIndicator`）、`PackProgressDialog`（双层同心 `CircularWavyProgressIndicator` 136dp/96dp）、`CbzUpdateDialogs`（确定态 `CircularWavyProgressIndicator` 88dp / 不确定态 `ContainedLoadingIndicator` 88dp）、`OrganizeSteps`/`LibrarySteps`（色调页签 + `PixFoldEmptyState`）、`ResetAllButton`/`DirMetaButton`/`MetaActionButton`/`ScrollToTopButton`（形变 tonal 图标按钮）、`SortAndPreviewPage`（连接式网格/列表切换 + 色调规则摘要）、`DraggableThumbCard`/`ThumbRow`、`SortRuleEditor`/`NamingPage`/`SchemeEditor`/`ProposalTable`、`LibraryPage`/`MetadataPage`/`PackPreviewPage`（分段卷项、色调卡片、`surfaceContainerHighest` XML 块；保持 `PlanRow` LazyColumn 虚拟化） | 更新 `SortPagePolishTest`（1 处旧 `SegmentedButton` 源码守卫改对齐 `FilledTonalToggleButton`）；新增 `M3eWorkflowsProgressControlsTest`；新增 5 张黄金图 |
| **U4 — 计划报告、撤销/确认弹窗、芯片与预览浮条** | `ce5d3d3` | `IssueFilterRow.kt`（连接式 2 按钮 `FilledTonalToggleButton` 组 + 纯黑未选中描边）、`PlanComponents.kt`（`PlanHeader` 色调摘要卡+状态胶囊、`PlanRow` `PixFoldSegmentedItem`、`ConfirmDialog` 危险步居中 `SoftBurst` 徽章+形变按钮）、`RenamePlanPage.kt`（分段计划行 + 色调危险开关卡）、`ExecutionReportPage.kt`（色调报告摘要卡+三态计数胶囊、分段报告行、`UndoScopeDialog` 居中 56dp `SoftBurst` 警示徽章+干净标题+三档形变按钮）、`CbzUpdateDialogs.kt`（`CbzUpdateConfirmDialog` `Cookie9Sided` 徽章+形变主按钮）、`SingleSelectChipRow.kt`（仅选中芯片用 `labelLargeEmphasized`，零芯片硬规则回退）、`ImagePreview.kt`（底部 M3E 活力 `HorizontalFloatingToolbar` + 顶部文件名胶囊与 48dp tonal 关闭按钮）、删除因关闭图标化而不再使用的 `R.string.common_close`（中英同步） | 新增 `ic_chevron_left.xml`；新增 `M3ePlanReportPreviewTest`（8 条用例）；刷新 4 张 + 新增 3 张黄金图（总计 **25 张**） |

---

## 3. 跨切片红线与不变量核验

- **`:domain` 零改动、零 Android 依赖**：`AdapterBoundaryTest` 全绿，`:domain` 目录 `git diff dev..HEAD` 为空。
- **零新增第三方 UI / 图标依赖**：6 枚新增 24dp 矢量图均为 Apache 2.0 Material Symbols Rounded 源码级 VectorDrawable（`#FF000000` 可着色），无未使用资源。
- **零硬编码 `RoundedCornerShape(...)` 与零 emoji**：`HygieneAndShapeTokensTest`、`ExpressiveFoundationTest`、`M3eHomeWorkspaceSettingsTest`、`M3eWorkflowsProgressControlsTest`、`M3ePlanReportPreviewTest`、`EmojiFreeSourceTest` 全绿。
- **芯片硬规则零回退**：`ChipLayoutRulesTest`、`ChipRowWrappingTest`、`NativeChipLayoutTest`、`ChipFlowFastPathTest`、`ChipLabelTruncationTest` 零放宽全绿。
- **既有视觉/像素/热路径守卫零放宽**：`HomeCardStyleTest`、`BottomBarStyleTest`、`UndoScopeDialogStyleTest`、`PackProgressDialogTest`、`TaskProgressBarTest`、`ImagePreviewTest`、`HotPathRecompositionTest`、`PackRecompositionAndVirtualizationTest`、`NativeCoreScreensLayoutA11yTest` 零放宽全绿。

---

## 4. Roborazzi 视觉回归黄金图矩阵（共 25 张）

统一采用真机 PJX110 几何与中文限定符（`zh-rCN-w361dp-h794dp-560dpi`、`@GraphicsMode(NATIVE)`、`changeThreshold = 0.01f`），全部存放于 `android/app/src/test/snapshots/`：

| 分类 | 黄金图文件（`src/test/snapshots/*.png`） | 覆盖重点 | 引入/更新切片 |
| --- | --- | --- | --- |
| 首页与纯黑卡片（4 张） | `home_light.png`、`home_dark.png`、`home_pure_black.png`、`home_pure_black_card.png` | `Cookie9Sided`/`Clover4Leaf` 双工作流大卡、从属工作区胶囊、纯黑 `1.dp` 描边局部放大校验 | M7 → U2 刷新 |
| 芯片瀑布流与二级浮层（4 张） | `chips_single_select_collapsed.png`、`chips_single_select_expanded.png`、`chips_two_level_popup_screen.png`、`chips_two_level_popup_window.png` | 96–156dp 定宽、4 行折行、3 行收口原地展开、非焦点系列顶点浮层 | M7（U4 零回归验证） |
| 设置与工作区列表（5 张） | `settings_color_spec_2021.png`、`settings_color_spec_2025.png`、`settings_light.png`、`settings_pure_black.png`、`workspace_list_dialog_dark.png` | M3 2021 vs M3E 2025 色板、连接式主题切换、强调色色块、分段设置组与工作区弹窗 | M7 → U2 刷新/新增 |
| 底栏与撤销范围弹窗（4 张） | `bottom_bar_plan_light.png`、`bottom_bar_plan_dark.png`、`dialog_undo_scope_dark.png`、`dialog_undo_scope_pure_black.png` | 色调底栏暗红撤销按钮、居中 56dp `SoftBurst` 警示徽章、三档视觉重量按钮、纯黑弹窗描边 | M7 → U1/U4 刷新 |
| 工作流 A/B/C 页面与波浪进度（5 张） | `sort_page_dark.png`、`library_page_dark.png`、`metadata_page_light.png`、`pack_preview_dark.png`、`pack_progress_dialog_dark.png` | 连接式网格/列表切换、分段卷与页码行、色调表单卡片、XML 预览容器、双层同心波浪进度环 | U3 新增 |
| 计划、执行报告与大图预览（3 张） | `rename_plan_dark.png`、`execution_report_dark.png`、`image_preview_dark.png` | 英雄摘要卡 + 三态计数胶囊、连接式筛选条、分段计划/报告行、活力 `HorizontalFloatingToolbar` | U4 新增 |

---

## 5. 待真机联合核对清单（M1–M9 + U1–U4 · 真机核对待补）

> **状态：真机核对待补（顺延至另一台机器与 M1–M9 一并真机核对）**。以下 11 项合并了 `docs/notes/d4-optimization-and-non-adb-testing.md` §3.2（M1–M9）与本轮 `d4/m3e-ui-polish`（U1–U4）中依赖真实硬件、ColorOS OEM 字体/SAF/SystemUI 或 60/120Hz 肉眼观感的核验项，顺延至另一台机器待用户授权后在真机（PJX110 / Android 16）一次性联合核对并留证：

### A. 性能、SAF 闭环与 R8 运行期（承自 M1–M9）
- [ ] **屏幕旋转 / 分屏配置变更状态保活**：验证 `PixFoldSessionViewModel` 跨配置变更保留手工排序、改名覆盖、元数据草稿与缩略图缓存，不重置工作流进度。
- [ ] **书架页 `LibraryRoute` 进页懒探测流畅度与大目录无卡顿**：验证 `LibraryVolumeLoader` + `SafVolumeProbeSession` 在大目录/多卷书库下按需异步探测无主线程掉帧或 ANR。
- [ ] **真实 ColorOS SAF 下大体积 CBZ 打包流式写盘与工作流 C 就地重写**：验证 `CbzWriter`（≤ 8 MiB 有界单趟缓冲）、`CbzMetaRewriter`（`STORED` 透传）与 `SafMetaUpdater` 在真实 `ExternalStorageProvider` 下的大包写入、中断取消与产物完整性。
- [ ] **Release R8 APK 安装与冷启冒烟（3.59 MB 级）**：真机安装 Release 包并遍历核心路径，确认 Nav3 `@Serializable NavKey`、`AboutLibraries`、`PixFoldSessionViewModel` 与 `material3 1.5.0-rc01` 混淆压缩后运行期无崩溃。
- [ ] **设置页单选弹窗 TalkBack 无障碍聚焦**：验证 M6 修复后的 `RadioRow`/`ChoiceRow` 及 U2 分段设置项在 TalkBack 下单次聚焦播报选中态、无重复可点击节点。

### B. Material 3 Expressive 视觉、动效与全面屏交互（U1–U4）
- [ ] **Android 12+ 壁纸动态取色（`AccentChoice.System`）与 OLED 纯黑实感**：在真机 OLED 屏核对浅色 / 深色 / 纯黑三种模式下首页大卡、设置分段组、工作区列表弹窗、计划/报告卡与撤销弹窗的 `1.dp outlineVariant` 描边清晰度与对比度。
- [ ] **M3E 弹簧动效与形变交互手感（60/120Hz）**：实测 `MotionScheme.expressive()` 下首页大卡、设置页连接式主题切换、排序页网格/列表切换、计划/报告筛选条及底栏/弹窗形变按钮按压与切换流畅度。
- [ ] **波浪进度与容装加载器真机动效**：实测真实大批量改名（`LinearWavyProgressIndicator`）、真实多卷打包双层同心波浪环（`CircularWavyProgressIndicator` 外环页级 + 内环卷级无重叠且中心读数清晰）、工作流 C 更新进度（确定态波浪环 / 不确定态 `ContainedLoadingIndicator`）与工作区扫描 `LoadingIndicator`。
- [ ] **ColorOS OEM 字体下 `361dp × 1.3×` 窄屏与连接式切换组**：在系统较大字体下复核主题模式 3 连按钮、排序网格/列表 2 连按钮与「全部 / 仅问题（N）」2 连按钮中英文均不换行裁切、不溢出视口。
- [ ] **`ImagePreview` 顶部胶囊与底部 `HorizontalFloatingToolbar` 全面屏适配**：核对挖孔状态栏与底部手势条 insets 下顶部文件名胶囊、48dp tonal 关闭按钮与底部活力浮动工具栏（上一页 / 页码 / 重命名 / 下一页）无遮挡、无边缘手势误触，且双击 2× 缩放与双指平移零掉帧、清晰无糊。
- [ ] **`UndoScopeDialog` / `ConfirmDialog` `SoftBurst` 徽章与背景高斯模糊**：实测触发覆盖撤销警告与删除源目录二次确认时，居中 56dp `SoftBurst` 警示徽章排版、三档按钮视觉重量区分与底层 12dp 高斯模糊观感。

---

## 6. 后续优化方向：APK 体积压缩与实时 UI 调试方案

> **跨机续接说明（2026-11-24）**：以下记录换机后即可落地的两项工程提效方向——APK 体积精简（立即可压 90%）与实时可视化调 UI 方案，方便在另一台机器与 §5 真机核对一并推进。

### 6.1 APK 体积压缩方案（2026-11-24 实测拆包）

| 产物 | 总大小 | 内部拆包分布 | 说明 |
| --- | --- | --- | --- |
| `app-debug.apk` | **36.59 MB**（`38,369,705 B`） | `dex` 35.75 MB（97.7%）、`resources.arsc` 0.69 MB、`lib` 0.04 MB、`res` 0.03 MB | 未混淆且 `minSdk >= 28` 默认不压缩 dex |
| M5 R8 `app-release-unsigned.apk` | **3.59 MB**（`3,763,684 B`） | `dex` 3.09 MB（↓ 91.4%）、`resources.arsc` 0.36 MB（↓ 48%） | 已开启 R8 压缩与资源裁剪，缺签名暂不可直接安装 |

- **方案 A（立即可压 90%，36.59 MB → ~3.7 MB）**：给 `release`（或新增临时 `staging` buildType）配置本地签名（临时 debug keystore 或正式 keystore），产出约 3.7 MB 可直接安装的 R8 精简包，并同步完成 §5A 中的 R8 混淆真机冒烟。
- **方案 B（进一步精简至 ~3.2–3.4 MB，可选）**：通过 `androidResources { localeFilters += listOf("en", "zh") }` 裁掉 AndroidX / M3 非中英文语言表；通过 `packaging.resources.excludes` 剔除 `DebugProbesKt.bin` 及冗余 `META-INF` 元数据（原生 `lib/` 仅 0.04 MB，无需拆 ABI）。

### 6.2 实时调 UI 可视化方案（4 种对比与推荐组合）

1. **Android Compose Preview 侧栏面板**（`android-cli-plugin` 的 `compose-preview` sidecar，底层走 `android preview render`，可开 `--daemon` 与 `--component-tree` 悬停高亮组件树与像素坐标）：在 `android/app/src/debug/` 补核心页面 `@Preview`（首页、设置页、排序/书架/打包预览、双波浪进度框、全屏预览，零影响正式包），免真机、免模拟器即可并排查看浅色 / 深色 / 纯黑 / 1.3× 字号。
2. **Android Device Hub 侧栏面板**（`android-cli-plugin` 的 `device-hub` sidecar）：管理 AVD 模拟器及直接 Mirror 投屏 AVD 或 USB / `adb connect` 真机，用于实测 M3E 弹簧形变、波浪进度、拖拽与缩放手感（16 GB 机器开 WSL 时内存偏紧，优先投屏真机或大内存机器跑 AVD）。
3. **现有 Roborazzi 25 张全屏黄金图**（零改造）：运行 `./gate.sh --record` 刷新 `android/app/src/test/snapshots/`，适合点击、弹层后的真实交互态像素对比与回归门禁。
4. **Android Studio Split Preview（Interactive Mode）+ Live Edit**：与方案 1 共用 `debug` 源集 `@Preview` 代码，手调间距与色值可获秒级反馈。

- **跨机推荐组合**：先在 `android/app/src/debug/` 补齐一组核心页面 `@Preview` → 静态调排版、配色、纯黑描边走 **Compose Preview 面板**（或 AS Split Preview）；动效、波浪进度与触控手感走 **Device Hub 投屏真机 / 模拟器**。换机端 `android` CLI 与面板前置配置见 [`docs/notes/environment.md`](environment.md) §12.8。
- **已落地免插件方案（2026-11-24，分支 `d4/live-ui-preview`）**：为兼容不具备 `android-cli-plugin` 的终端 agent（如 DSH）及 Windows PowerShell/WSL 双宿主，已落地双轨方案：
  - **模式 A（免手机静态画廊）**：[`scripts/live_preview.py`](../../scripts/live_preview.py) 基于 Roborazzi NATIVE 无污染重定向至 `android/app/build/live-preview/`，提供本地 SSE 浏览器实时画廊与像素对比（规格见 [`docs/superpowers/specs/2026-11-24-live-ui-preview-design.md`](../superpowers/specs/2026-11-24-live-ui-preview-design.md)）。
  - **模式 B（真机 `scrcpy` + 15 场景直达）**：[`scripts/live_device.py`](../../scripts/live_device.py) + `src/debug`/`src/release` 同名多态入口支持 `android install --use-delta-install` 增量推包与一秒直达 15 场景（零侵入 Release、不建 `debug/AndroidManifest.xml`，规格见 [`docs/superpowers/specs/2026-11-25-live-device-showcase-design.md`](../superpowers/specs/2026-11-25-live-device-showcase-design.md)）。统一技能入口见 [`pixfold-live-ui`](../../.agents/skills/pixfold-live-ui/SKILL.md)。
