# D4 项目优化审查与无 ADB 测试方案（2026-11-23）

> **性质**：调研与实证笔记（非产品权威；产品决策唯一权威仍以 [`docs/product/HANDBOOK.md`](../product/HANDBOOK.md) 为准）。
> **目的**：在 D4a 深化打磨阶段，基于源码与构建实证完成两项系统盘点：① 架构、性能与工程质量优化审查（P0–P2）；② 无 ADB 纯 JVM 测试架构与分阶段落地路线，并给出后续阶段分支 `d4/opt-and-non-adb-tests` 的按序实施清单（M1–M9）。
> **约束前提**：全部方案严格遵循 `HANDBOOK.md` §2/§6.5/§8——`android/` 就地演进、GUI 仅做 Android、仅用 SAF、保持 `:domain` 纯 JVM 零 Android 依赖（`AdapterBoundaryTest` 不破）、锁死 `material3 = 1.5.0-rc01`（M3E）、`:app:lintDebug` 零告警。

---

## 0. 调研基线

| 维度 | 实测基线（2026-11-23） | 来源 |
| --- | --- | --- |
| 分支与提交基准 | `dev`（基线 `ad48f1d`，阶段 D4a 进行中） | `git rev-parse --short HEAD` |
| 构建工具链 | Gradle `9.8.0` / AGP `9.4.1`（`android.newDsl=true`）/ Kotlin `2.4.20` / JDK `temurin-21`（`jvmTarget = 17`） | `android/gradle/wrapper/gradle-wrapper.properties`、`android/gradle/libs.versions.toml`、`.mise.toml` |
| Android SDK 与 UI 栈 | `compileSdk = 37` / `minSdk = 36` / `targetSdk = 36`；Compose BOM `2026.09.00` + `material3 = 1.5.0-rc01`（显式覆盖 BOM 启用 M3E）；Navigation 3 `1.2.1`；Coil `3.6.3`；Robolectric `4.17` | `android/app/build.gradle.kts`、`android/gradle/libs.versions.toml` |
| 宿主与内存预算 | Windows 宿主（8c/16t，16 GB RAM；WSL 分配 10 GB 后 Windows 侧可用构建余量约 **6 GB**）；`org.gradle.jvmargs=-Xmx4g`、`parallel=true`、`caching=true`、`configuration-cache=true`（`problems=warn`） | `android/gradle.properties:6-20` |
| 测试规模基线 | `:domain`：**65** 测试类 / **986** `@Test`（JUnit 5 Platform，XML 986 pass，合计 **5.3s**，含真实 `ZipFile` 兼容测试 `CbzReaderCompatTest.kt` 9 条）；`:app`：**101** 文件（97 测试类 + 4 辅助）/ **910** `@Test`（JUnit 4，XML **909** pass + 1 排除 `NegativeControlTest`）；其中 **77** 个 `RobolectricTestRunner`、**59** 个 Compose 规则、仅 **10** 个 `@GraphicsMode(NATIVE)`、**7** 个 `captureToImage()` 像素探针、**0** 个 `fontScale`、**0** 个 Compose 深色限定符截图、**0** 个 `@Preview`、`androidTest` 目录不存在 | `android/domain/src/test`、`android/app/src/test`、JUnit XML |
| 四道闸门耗时机理（冷 vs 暖） | **暖启动基线约 `1m 57s`**（`BUILD SUCCESSFUL in 1m57s`，`gate-last.rc=0`，app 909 / domain 986 / lint 0 告警）；`android/build/gate-last.log:195` 记录的 `18m 8s` 与 `AppLanguageTest.xml` 的 `720.1s`（其 3 条用例实际之和仅 `7.6s`，其余 96 类合计约 `48.8s`）属**冷启动一次性下载摊销**（Gradle 9.8.0 分发包 `gate-last.log:1-3` + Robolectric 4.17 SDK 36 与 151.8 MB `nativeruntime-dist-compat-1.0.19.jar` 在按字母序首个测试类触发下载），不可误判为常态运行耗时 | `.agents/skills/pixfold-build-gates/SKILL.md:15`、`android/build/gate-last.log`、`TEST-com.cyanix.pixfold.*.xml` |

---

## 一、项目优化审查

按优先级分为 P0（主线程阻塞 / 并发竞态 / 高频重组，立即修复）、P1（I/O 与内存瓶颈 / 状态托管 / 渲染热路径）、P2（构建卫生 / 死代码 / M3E Token 收口）。

### P0 — 严重（高影响，优先修复）

#### P0 #1：组合阶段主线程同步执行 SAF Binder IPC 与 ZIP/XML I/O
- **定位与证据**：
  - `android/app/src/main/kotlin/com/cyanix/pixfold/PixFoldApp.kt:505-533`：在 `remember(...)` 中于主线程同步计算 `scannedVolumes`，对每一卷依次调用 `SafOutputExistsProbe.exists()`（`data/saf/SafPackPlatform.kt:155-185`）和 `SafExistingComicInfoProbe.read()`（`data/saf/SafPackPlatform.kt:329-382`）。
  - `data/saf/SafPackPlatform.kt:384-417`：`SafExistingComicInfoProbe.parentDocumentUri` 与同文件 `safParentDocumentUri`（`SafPackPlatform.kt:446-464`）存在重复实现。
- **问题根因**：
  1. 每次工作区加载或刷新都会立即触发，即使用户仅停留在 `HomeRoute` 或使用 `OrganizeRoute`。
  2. 缺少目录列举缓存：同一系列目录下的 N 卷会触发 **2N** 次完整 SAF 目录树遍历 + **2N** 次 `resolver.query(childrenUri)` + 同步 `openInputStream` → `ZipInputStream` → XML DOM 解析，全部压在 UI 主线程；20–50+ 卷书库存在明确 ANR 风险。
- **改进方案**（影响：高 / 工作量：中）：
  - 将卷探测迁至 `Dispatchers.IO` 异步管线（纳入 `WorkspaceController` 或独立 `LibraryVolumeLoader`，进入 `LibraryRoute` 时按需触发或后台刷新）。
  - 按父目录批量缓存一次 `childrenUri` 列举结果，单趟合并「输出是否存在」与「已有 CBZ URI 查找」。
  - 删除 `SafExistingComicInfoProbe` 中重复的 `parentDocumentUri`/`queryDirId`，统一复用 `safParentDocumentUri`。

#### P0 #2：缩略图缓存、任务总线与前台服务的线程安全与取消缺陷
- **定位与证据**：
  - `ui/workspace/ThumbnailCache.kt:34-40`：使用未同步的访问序 `LinkedHashMap(16, 0.75f, true)`（注意其 `.get()` 也会修改内部双向链表指针）；`ui/workspace/CachingThumbnailSource.kt:71-120` 仅对 `inFlight` 加锁（注释已指出 `withPermit` 后可能跨线程恢复），却未保护 `cache.get/put/clear`。
  - `ui/workspace/CachingThumbnailSource.kt:100-104`：无条件吞掉 `request.await()` 抛出的 `CancellationException` 并返回 `null`，滚动列表条目 `LaunchedEffect` 取消时会破坏结构化并发。
  - `data/pack/PackTaskBus.kt:119-129`：`publishPage()`（由 `PackTaskService.kt:198` 在 `Dispatchers.IO` 调用）与 `requestCancel()`（由 `LibrarySteps.kt:887` 在主线程调用）均使用非原子 `mutableState.value = running.copy(...)`，并发页进度写入可将 `cancelling = true` 覆盖回 `false`。
  - `data/pack/PackTaskService.kt:120, 188, 485`：`snapshot` 实际在 `onProgress` 的 `Dispatchers.IO` 回调中写入（`SafPackExecutor.kt:121, 152`），与第 115 行 KDoc（「只在主线程读写，故不加锁」）矛盾；同时 `onTimeout()` 在主线程读取该字段且无 `@Volatile` 或同步保护。
- **改进方案**（影响：高 / 工作量：低）：
  - 为 `ThumbnailCache` 加同步锁（或在 `CachingThumbnailSource` 统一持锁访问 `cache`）。
  - 捕获 `CancellationException` 后检查 `if (!currentCoroutineContext().isActive) throw e`，保留协作取消传播。
  - `PackTaskBus` 状态更新一律改为 `MutableStateFlow.update { ... }` 原子 CAS。
  - `PackTaskService.snapshot` 标 `@Volatile`（或统一派发到 `Dispatchers.Main.immediate` 更新）。

#### P0 #3：`PackPreviewPage`、`LibrarySteps`、`NamingPage`、`PixFoldTheme` 未缓存重计算与失效虚拟化
- **定位与证据**：
  - `ui/library/PackPreviewPage.kt:200`：`previewOf(volume)` 未加 `remember`，每次重组重算；第 306 行 `items(preview.pages.indices.toList())` **缺少 `key` 与 `contentType`**，且每次重组分配装箱 `List<Int>`（大卷高达 1,599 项）；第 345–436 行未缓存 `buildPackagePlans(...)`，并在单个 `item(key = "plan")` 内用 `for` 循环渲染全部 `PlanRow`，使 `LazyColumn` 虚拟化彻底失效。
  - `ui/library/LibrarySteps.kt:374`：在 1,001 行顶层 Composable 中直接 `PackTaskBus.state.collectAsState()`（未用 `android/app/build.gradle.kts:127` 已声明的 `lifecycle-runtime-compose` 的 `collectAsStateWithLifecycle()`），而 `PackTaskService.kt:198` 无节流地按页发射进度——打包 1,599 页时每一页都会触发整棵 `LibrarySteps` + `PackPreviewPage`（含 `previewOf` 与 `indices.toList()`）重组，且应用退到后台后仍持续重组。
  - `ui/theme/Theme.kt:54-76`：`PixFoldTheme` 每次根重组均未缓存地重新调用 `dynamicDarkColorScheme`/`dynamicLightColorScheme` 及完整 Material Color Utilities `ColorSchemes.fromSeed(...)`。
  - `ui/organize/NamingPage.kt:141-148`：`buildProposals`、`buildRenamePlan`、计数与筛选 `shown` 均未 `remember`（而 `PixFoldApp.kt:830` 与 `RenamePlanPage.kt:104` 已缓存）。
- **改进方案**（影响：高 / 工作量：低）：
  - `LibrarySteps.kt:374` 改为 `collectAsStateWithLifecycle()`，并将 `PackProgressDialog` 状态收集下沉到独立小包装组件（或传状态 lambda），把逐页刷新隔离在弹窗内。
  - `PackPreviewPage` 中对 `previewOf(volume)` 与 `buildPackagePlans(...)` 加 `remember`；第 306 行改为 `itemsIndexed(preview.pages, key = { _, p -> p.item.id }, contentType = { _, _ -> "page" })`；将 `PlanRow` 改为 `LazyColumn` 的 `itemsIndexed(plans, key = ..., contentType = ...)` 恢复虚拟化。
  - `PixFoldTheme` 对 `ColorScheme` 按 `(dark, settings.monetEnabled, settings.accent, settings.colorStyle, settings.colorSpec, settings.pureBlack, context)` 做 `remember`。
  - `NamingPage` 中缓存 `proposals` / `plan` / `shown`。

#### P0 #4：跨模块 `:domain` → `:app` 的 Compose 编译器稳定性盲区
- **定位与证据**：
  - `android/domain/build.gradle.kts` 为纯 `kotlin-jvm` 模块，未应用 Compose 编译器插件；`android/app/build.gradle.kts` 未配置 `composeCompiler { stabilityConfigurationFiles = ... }`；全仓仅 2 处 `@Immutable`/`@Stable`（`PreviewRename.kt:51`、`PixFoldDialog.kt:58`）。
  - 结果：所有 `:domain` 不可变数据类（`SourceItem`、`ComicVolume`、`LibraryState`、`OrganizeState`、`NamingState`、`PixFoldSettings`、`TaskProgress`）在 Compose 眼中均为 **unstable**。在 Strong Skipping Mode 下，不稳定参数退化为引用相等 `===` 检查，一旦派生列表或对象重建（如 `domain/library/LibraryState.kt:75` 的 `seriesGroups` getter 在 `LibraryPage.kt:177, 227` 每次重组重算两次 `groupVolumes(volumes)`），跳过优化即告失效。
- **改进方案**（影响：高 / 工作量：低）：
  - 在 `android/app/build.gradle.kts` 增加 `composeCompiler { stabilityConfigurationFiles.add(...) }`，声明 `:domain` 中不可变值对象包/类型（**不给 `:domain` 加任何 Android 依赖**，100% 满足 `AdapterBoundaryTest`）。
  - 缓存 `LibraryState.seriesGroups`（或在 `LibraryPage` 中 `remember(state.volumes)`）。

---

### P1 — 高价值（性能、可靠性与可维护性）

#### P1 #5：CBZ 流式 I/O 与内存分配瓶颈（`CbzWriter` 与 `CbzMetaRewriter`）
- **定位与证据**：
  - `domain/pack/CbzWriter.kt:118-169`：`ZipOutputStream(out)` 直接包装未缓冲的 SAF `OutputStream`；在 `pages.forEachIndexed` 循环体内每次分配 **2 × 64 KiB `ByteArray`**（第 133、154 行，1,599 页产生约 204 MB GC 分配抖动）；且每页打开两次 SAF `InputStream`（1,599 页 = 3,198 次 Binder IPC + 2× 磁盘读取）。
  - `domain/pack/CbzMetaRewriter.kt:108, 153-208`：未缓冲 `ZipOutputStream(out)`；每条目分配 64 KiB `ByteArray`；使用默认初始容量 32 B 的 `ByteArrayOutputStream()` 反复倍增拷贝至 8 MiB/条目（2.2 GB CBZ 上造成 10+ GB 瞬时拷贝）；即使原条目已是 `STORED` 且本地头已给出有效 `size` 与 `crc`（所有 PixFold 生成的 CBZ），仍全量缓冲并重算 CRC。
- **改进方案**（影响：高 / 工作量：中）：
  - 用 `BufferedOutputStream(out, 64 * 1024)` 包装输出（返回前 `flush` 且不关闭调用方 `out`），将单个 64 KiB `ByteArray` 提到循环外复用。
  - `CbzWriter.writeStreaming`：对 ≤ 8 MiB 的页面采用**严格有界、可复用的单趟缓冲**算完 size/CRC 后直写，仅 > 8 MiB 时回退两趟读取，将 SAF I/O 减半同时守住 256 MB 堆 OOM 护栏（`SafPackPlatform.kt:207-211`）。
  - `CbzMetaRewriter.copyEntry`：**必须显式守卫 `source.method == ZipEntry.STORED && source.size >= 0 && source.crc >= 0`**（不可仅凭 `size >= 0 && crc >= 0`），满足时直接 `zin -> zip` 流式透传（JDK `ZipOutputStream.closeEntry()` 仍会校验精确 size 与 CRC）；对 `DEFLATED` 条目预设 `ByteArrayOutputStream` 容量，超 8 MiB 维持现有 spill 文件路径。

#### P1 #6：`WorkspaceController` 主线程残留 Binder IPC 与工作流 C 冗余 SAF 遍历
- **定位与证据**：
  - `ui/workspace/WorkspaceController.kt:270, 274`（`pick`）：`nameResolver(treeUri)`（`AndroidSafSource.displayNameOf` → `resolver.query`）与 `rememberWorkspace`（`takePersistableUriPermission` + SharedPreferences）在主线程同步执行。
  - `WorkspaceController.kt:255-261`（`move`）：同步调用 `isPersistentGrant(fromUri)`、`isPersistentGrant(toUri)` 与 `refreshListings()`，在主线程连续触发 **3 次** `persistedUriPermissions` Binder IPC。
  - `WorkspaceController.kt:332, 341, 428, 449`：`rememberIfLoaded` 在 `withContext(ioDispatcher)` 之外于主线程调用 `takePersistableUriPermission`。
  - 工作流 C（`ui/library/LibrarySteps.kt:529-540` + `data/saf/SafMetaUpdater.kt:104-117`）：`targetExists` + `preflight` + `update` + `update` 内部二次 `preflight`，一次更新触发 **4 次**冗余 SAF 目录树遍历。
- **改进方案**（影响：中高 / 工作量：低）：
  - 将 `pick` 中的 `displayNameOf` 与 `rememberIfLoaded` 移入 `ioDispatcher`。
  - `move()` 复用单次 `persistentGrants()` 快照。
  - `SafMetaUpdater.preflight()` 直接返回 `TargetMissing` 或返回已解析的目标 URI，供 `update()` 复用，将 SAF 遍历从 **4 次降至 2 次**。

#### P1 #7：孤儿阻塞 IO 协程与配置变更状态丢失
- **定位与证据**：
  - `ui/library/LibrarySteps.kt:569-582`（2.2 GB CBZ 元数据重写）与 `ui/organize/OrganizeSteps.kt:523-534, 585-605`（批量 SAF 重命名/撤销）运行在 `rememberCoroutineScope()` 中，而取消仅轮询 `token.isCancelled`、未感知协程 `isActive`。旋转屏幕或按返回取消作用域时，`token.isCancelled` 仍为 `false`，后台阻塞 IO 线程成为孤儿，继续修改磁盘文件却无 UI 承接结果。
  - `:app` 全模块尚无 `ViewModel` / `SavedStateHandle`：`PixFoldApp.kt`（1,046 行）用 `remember { ... }` 持有 `workspaceController`、`organizeState`、`namingState`、`libraryVolumes`、`thumbnailSource` 与 `coilImageLoader`，旋转/分屏/动态取色变化会清空未保存的手工排序、改名覆盖、元数据编辑与整套缩略图 LRU 缓存。
  - `MetadataPage.kt:188`、`OrganizeSteps.kt:246`、`SortAndPreviewPage.kt:236` 采用 `var state by remember(parentState)` 镜像父状态，每次编辑都会重新分配 `MutableState` 而非纯状态提升。
- **改进方案**（影响：高 / 工作量：中）：
  - 立即将取消判据接通协程活性：`shouldStop = { token.isCancelled || !currentCoroutineContext().isActive }`（多 GB 级工作流 C 重写可进一步对齐 `PackTaskService` 走前台服务或 `ViewModel` 作用域）。
  - 渐进引入轻量 `AppViewModel`（`lifecycle-viewmodel` 已随 `activity-compose`/`lifecycle-runtime-compose` 传递引入）跨配置变更持有 `WorkspaceController`、`ImageLoader`/`ThumbnailSource` 与工作流会话状态，并逐步拆分 `PixFoldApp.kt`（1,046 行）与 `LibrarySteps.kt`（1,001 行）。

#### P1 #8：缩略图命中闪烁、大图预览内存/重组与 Compose 热路径优化
- **定位与证据**：
  - `ui/components/ItemThumb.kt:70-79`：即使内存缓存 100% 命中，`bitmap` 初始值仍恒为 `null`，导致每张图先闪一帧 `ProceduralThumb` Canvas 再由 `LaunchedEffect` 触发重组切到 `Image`。
  - `ui/preview/ImagePreview.kt:147-148, 163-168`：使用急切版 `graphicsLayer(scaleX = state.scale, ...)`，双指缩放/拖拽每帧重组整页；翻页时无缓存地解码 2× 屏幕尺寸位图（高达 2528×4096 ≈ 41 MB）；第 123 行 `remember { mutableStateOf(previewStateOf(...)) }` 缺少 `items.size` key。
  - `ui/components/DragReorderGrid.kt:201, 259, 399, 402`：组合阶段读取 `pointerInRoot`，且 120Hz 拖拽每帧执行 O(N) `firstOrNull { it.id == ghostId }`。
  - `ui/components/SingleSelectChipRow.kt:359-381`：即使 `expanded == true` 或 `collapseLines == Int.MAX_VALUE`（每个系列浮层 `Popup` 均如此），`ChipFlow` 仍执行完整的 `ChipSlot.Probe` + `ChipSlot.Item` 两趟子组合（2× 开销）；第 142 行 `items.map(idOf)` 每次重组均重新分配列表。
  - `nav/PixFoldNav.kt:115, 136-147`：`latestBuilder` 在每次重组时重新执行 `builder` 并新建 `PixFoldNavGraphBuilder` + `mutableMapOf`。
  - `PixFoldApp.kt:195-205`：`coilImageLoader` 缺少 `DisposableEffect { onDispose { coilImageLoader.shutdown() } }`，`thumbnailSource = remember { ... }` 漏写 `coilImageLoader` key。
- **改进方案**（影响：中高 / 工作量：低中）：
  - 在 `ThumbnailSource` 新增同步 `fun peek(uri: String): ImageBitmap? = null`，`ItemThumb` 初始化直接取缓存命中帧消除 1 帧闪烁。
  - `ImagePreview` 与 `DragGhost` 改为 lambda 版 `graphicsLayer { ... }`（传入 `() -> Offset`，并在起拖时缓存 `ghostItem`）。
  - `ChipFlow` 在 `expanded || collapseLines == Int.MAX_VALUE` 时短路跳过 `Probe` 趟。
  - 为 `previewImageSource` 增加 2–3 张小容量 LRU 缓存；将 `coilImageLoader` 生命周期绑定至 `AppViewModel` 或 `DisposableEffect`。

---

### P2 — 工程卫生、M3E 细节与构建配置

#### P2 #9：死代码与临时脚手架清理、M3E 形状 Token、编译警告与构建/发布配置
- **定位与证据**：
  1. **死代码与残留 spike**：
     - `android/app/src/main/kotlin/com/cyanix/pixfold/data/saf/AndroidThumbnailSource.kt`（81 行）：迁移 Coil 3 后生产调用方为 **0**（`docs/INDEX.md` 2026-11-13 已注记在 Nav3 后删除）。
     - `android/app/src/debug/kotlin/com/cyanix/pixfold/nav3spike/Nav3SpikeActivity.kt` 及 `android/app/src/debug/AndroidManifest.xml`（含 `android:exported="true"`）。
  2. **硬编码圆角与 M3E 采纳**：
     - 违背 `HANDBOOK.md` §6.5（「用 `MaterialTheme.shapes` 语义层级而非写死圆角」）：`SingleSelectChipRow.kt:167, 468, 482`（`RoundedCornerShape(12.dp)`/`8.dp`）、`TwoLevelChipRows.kt:441`（`12.dp`）、`PixFoldDialog.kt:288`（`28.dp` 即 `MaterialTheme.shapes.extraLarge`）。
     - M3E `1.5.0-rc01`（`Theme.kt:78-82`）当前仅启用 `MotionScheme.expressive()`，尚未引入 `LoadingIndicator`/`ButtonGroup`/`SplitButton` 等表达型组件（可在 D4a 打磨阶段按需采用）。
  3. **过期注释与编译器警告**：
     - `android/app/build.gradle.kts:16-21` 在 `minSdk = 36` 上方仍留 `30 → 34` 注释；`PixFoldApp.kt:326, 1004`、`Theme.kt:26`、`AndroidManifest.xml:51, 58` 同样残留 `minSdk 34` 过期注释。
     - `PixFoldApp.kt:163` 存在条件 `remember`（`store ?: remember { ... }`，违背同文件 L483 自注的组合位点约束）；L489/523/883 产出 Kotlin `Unnecessary safe call` 编译警告；L736 `organizeDoneTitle` 未使用。
  4. **构建配置与发布准备**：
     - `android/build/gate-last.log:16-22` 报配置期急切解析 `debugCompileClasspath` / `debugRuntimeClasspath`（「build performance and scalability issue」，疑似 `aboutLibraries` 插件配置触发）及 Gradle 10 弃用提示。
     - `android/app/build.gradle.kts` 尚未配置 `buildTypes { release { isMinifyEnabled = true; isShrinkResources = true } }`（当前 debug APK 37.3 MB，须在 MVP 发版前补齐）。
     - `scripts/`（`batch_pack_cbz.py` 1,356 行、`batch_rename_images.py` 641 行）按 `HANDBOOK.md` §2/§7 保持冻结不碰。
- **改进方案**（影响：中 / 工作量：低）：
  - 删除 `AndroidThumbnailSource.kt` 与 `app/src/debug/nav3spike/`。
  - 将硬编码 `RoundedCornerShape` 统一替换为 `MaterialTheme.shapes.small/medium/extraLarge`。
  - 清理过期 `minSdk 34` 注释，修复 `PixFoldApp.kt` 3 处编译警告、未使用 `val` 与 L163 条件 `remember`。
  - 运行一次 `--info` 定位并消除配置期急切 classpath 解析；**维持 `maxParallelForks = 1`**（见下方复核约束 #2）；MVP 发版前配置 release R8 压缩与资源裁剪。

---

### 交叉复核补充的 6 条修正与约束（Verifier 核验）

1. **跨报告死代码冲突裁决**：`AndroidThumbnailSource.kt` 生产调用方为 0，应直接删除；离线缩略图测试只覆盖 `CoilThumbnailSource`。
2. **Windows 6 GB 内存红线 — 维持 `maxParallelForks = 1`**：Gradle daemon 已占 `-Xmx4g`，扩展 `@GraphicsMode(NATIVE)` 与 Roborazzi 后每个 fork 都会独立加载 `robolectric-nativeruntime.dll` 与 Skia 原生堆；2 个并行 fork 极易突破 Windows 侧约 6 GB 可用余量（`android/gradle.properties:6-7`）。在取得真实内存遥测数据前，**维持 `maxParallelForks = 1`**。
3. **冷下载与暖运行耗时机理区分**：`18m 8s` 与 `AppLanguageTest.xml` 的 `720.1s` 均含首次下载摊销，优化前后基准对比一律使用暖启动基线（**~1m 57s**）与单类测试用时。
4. **Roborazzi 的 Gradle 9 / AGP 9 配置缓存约束**：`roborazzi { outputDir.set(...) }` 必须使用 **`layout.projectDirectory.dir("src/test/snapshots")`** 而非 `file(...)`，以规避 clean build 配置缓存失效（Issue `#968`）；如后续引入多任务串联则预留 `separateOutputDirs.set(true)`（Issue `#830`）；确保与 AGP 9.4.1 的 `android.newDsl=true` 兼容、不回退旧 Variant API。
5. **`CbzMetaRewriter` 与 `CbzWriter` 的安全守卫**：直通流式拷贝必须显式判断 **`source.method == ZipEntry.STORED && source.size >= 0 && source.crc >= 0`**；`CbzWriter` 的单趟内存缓冲必须严格封顶 **≤ 8 MiB** 且跨页复用，不得削弱 256 MB 堆 OOM 保护。
6. **Compose Test `junit4.v2` 弃用迁移波**：`android/build/gate-last.log:109-177` 显示现有 59 个 Compose UI 测试类均已触发 `androidx.compose.ui.test.junit4.createComposeRule` → `androidx.compose.ui.test.v2.createComposeRule`（`StandardTestDispatcher`）弃用警告。所有新增的 RNG / Roborazzi 测试须从第一天起直接使用 `junit4.v2`，旧测试可按模块渐进迁移。

---

## 二、无 ADB 测试方案

### 2.1 核心实证突破：`LEGACY` vs `NATIVE` 根因

此前 `.agents/skills/pixfold-device-testing/SKILL.md:10` 与 `ChipRowWrappingTest.kt:16-19`、`ChipLayoutRulesTest.kt:28-31` 认为「Robolectric 测不出真实文字宽度（如 `trip 34.9dp / scan 34.6 / misc 34.3 / cn 34.3`），因此所有布局放不放得下、折行与溢出结论都只能真机断」。

**本次在本机实证查明的真正根因**：
1. 工程没有 `robolectric.properties`，导致 **67/77** 个 Robolectric 测试类（包括 `ChipLayoutRulesTest.kt:42-44`）默认运行在 **`GraphicsMode.Mode.LEGACY`**，该模式下 `ShadowPaint` 将文字测量打桩为 ~0 宽。
2. 本地 Gradle 缓存中的 **`nativeruntime-dist-compat-1.0.19.jar`**（151.77 MB，已由 `robolectric:4.17` 自动拉取）**已完整内置**：
   - `native/windows/x86_64/robolectric-nativeruntime.dll`（Windows x64 原生 Skia + Minikin 渲染/排版引擎）；
   - `fonts/fonts.xml` 与 **234 个 AOSP 字体**，包含全部 7 个字重的 **`NotoSansCJK-*.ttc`**（其中 `NotoSansCJK-Regular.ttc` 17.88 MB）以及全套 `Roboto-*.ttf`。
3. 现有 `HomeCardStyleTest.kt:85`（标注 `@GraphicsMode(GraphicsMode.Mode.NATIVE)`）**已在本机 Windows 上 100% 通过**：`TEST-com.cyanix.pixfold.ui.HomeCardStyleTest.xml` 显示 4 条用例共耗时 **0.325s**，真实读出 `edge=#46464F background=#000000 distance=219` 与 `card height = 88.0 dp`。

> **结论**：零新增依赖、零 ADB，仅需在布局/视觉测试类启用 `@GraphicsMode(GraphicsMode.Mode.NATIVE)` + 真实屏幕限定符，即可在纯 JVM 上获得真实中英文排版测量、折行、截断与视口边界断言。注意不要盲目在全局 `robolectric.properties` 一键开启 `NATIVE`（先按测试类/基类显式启用并观测内存水位）。

---

### 2.2 四层无 ADB 测试架构（+ 可选 Tier-3 GMD）

#### 第一层（Pillar A）：纯 JVM Compose 布局、溢出与无障碍守卫（零新增依赖）
- **配置组合**：`@GraphicsMode(GraphicsMode.Mode.NATIVE)` + `@Config(sdk = [36], qualifiers = "zh-rCN-w361dp-h794dp-560dpi")`（精确对齐真机 PJX110 几何；按 `ResourceFallbackTest.kt:27` 语言限定符放首位）+ `junit4.v2`。
- **多维度矩阵**：
  - 语言：`zh-rCN` / `en` / `ja`。
  - 主题：`Light` / `Dark` / `PureBlack`（统一包裹 `PixFoldTheme + Surface(color = MaterialTheme.colorScheme.background)`，避开 `HomeCardStyleTest.kt:122-132` 的 Robolectric `#FAFAFA` 窗口底色陷阱）。
  - 字号缩放：`fontScale = 1.0f / 1.3f / 1.5f`（经 `RuntimeEnvironment.setFontScale(...)` 或 `CompositionLocalProvider(LocalDensity provides Density(density, fontScale))`）。
- **断言能力**：
  - 真实 `ChipFlow` 折行数、首尾省略宽度、节点右边界 `right <= 361.dp`、同级零重叠，逐步以行为几何断言替代易碎的源码正则扫描。
  - 自动化 `HANDBOOK.md` §6.5 无障碍护栏：每个可点击节点满足 `width >= 48.dp && height >= 48.dp`；纯图标按钮必有非空 `contentDescription`。
- **OEM 字体安全裕度**：ColorOS/OnePlus 字体（`OnePlus Sans`/`OPPO Sans`）与 AOSP `Roboto`+`NotoSansCJK` 存在细微字宽差异，接近视口边缘的断言保留 **~5–8% 宽度裕度**；仅当布局距边缘 < 5% 时交真机抽检。

#### 第二层（Pillar B）：JVM 截图视觉回归对比与选型

| 方案 | 与 PixFold 工具链兼容性（`AGP 9.4.1`、`Gradle 9.8.0` + CC、`compileSdk 37`、`material3 1.5.0-rc01`、Windows） | 交互流与 M3E 支持 | 是否强制 `@Preview` | 结论 |
| --- | --- | --- | --- | --- |
| **Roborazzi** `io.github.takahirom.roborazzi:1.79.0`（要求 `>= 1.78.0`） | **兼容**：复用现有 `Robolectric 4.17` + RNG；v1.76.0+ 修复 Gradle 9 `roborazziImageInput` 报错；v1.78.0+ 修复配置缓存失效（`#968` / PR `#969`、`#976`）；需配 `outputDir.set(layout.projectDirectory.dir("src/test/snapshots"))` | **支持**：完整 `compose-ui-test` 点击/滚动/Nav3/弹窗/二级芯片浮层截图（`onRoot().captureRoboImage()`）；完整渲染 `MaterialExpressiveTheme` 与 M3 2021 vs M3E 2025 色板 | **否**（直接复用现有 Compose UI 测试） | **主推采用** |
| **Paparazzi** `app.cash.paparazzi:2.0.0-alpha05.1` | **风险高**：仍处 `2.0.0-alpha`；虽在 `alpha05`/`alpha05.1` 加入 AGP 9 `application` 与 Windows 路径修复，但内置 LayoutLib v16.2.3（SDK 36）与 `compileSdk 37` / `material3 1.5.0-rc01`（`minCompileSdk=37`）存在版本错位 | **不支持**：仅静态单帧，无法跑 `compose-ui-test` 交互与 Nav3，且引入第二套独立渲染器 | 否 | **不采用** |
| **官方 Compose Preview Screenshot**（`com.android.compose.screenshot` → AGP Test Suites） | **当前不兼容**：独立插件已在 AGP 9 废弃；替代的 AGP 原生测试套件（`0.0.1-alpha16+`）要求 **AGP `9.5.0-alpha03+`** 与 Canary，与锁定稳定版 `AGP 9.4.1` 冲突 | 仅静态 `@Preview`，且为 alpha | **是**（当前仓内 `@Preview` 为 **0**） | **暂缓，待 AGP 9.5+ 稳定再评** |

- **落地要点**：暖 RNG 下单张截图约 `0.1–0.3s`，以整屏/组件黄金图 diff（`recordRoborazziDebug` / `verifyRoborazziDebug`）替代易碎的单点 `captureToImage()` 像素探针；黄金图固定在 Windows 侧生成（若后续加 Linux CI，可配 `CompareOptions(changeThreshold = 0.01f)` 吸收 FreeType/Skia 子像素抗锯齿差异）。

#### 第三层（Pillar C）：`FakeDocumentsProvider` + `@TempDir` 离线 SAF 与媒体集成测试
- 利用 Robolectric 的 `Robolectric.setupContentProvider(...)` / `ShadowContentResolver.registerProviderInternal("com.android.externalstorage.documents", fakeProvider)`，以 JUnit `@TempDir` 为真实磁盘后端实现 `FakeDocumentsProvider : DocumentsProvider`，打通当前 `data/saf/` 盲区：
  - **真实执行** `DocumentsContract` URI 构造（`buildChildDocumentsUriUsingTree`、`buildDocumentUriUsingTree`、`getTreeDocumentId`）与 `ContentResolver.query/openInputStream/openOutputStream`。
  - 覆盖对象：`AndroidSafSource.loadCollections`（`MAX_DEPTH = 4` 递归、相对路径、`SecurityException` → `LoadResult.Denied`）、`AndroidSafSource.watchChanges`（`ShadowContentResolver.notifyChange` + `ShadowLooper.idleFor(400, MILLISECONDS)` 验证 400ms 防抖与 `close()` 取消）、`SafCbzOutputSink`（新建 `"w"` vs 覆盖 `"wt"`、中途异常 `discardFor` 清理）、`SafOutputExistsProbe`、`SafPageBytes`、`SafExistingComicInfoProbe`（缺失返回 `null`、无 XML 返回空 `ComicInfoData`、坏 ZIP 返回 `null` 回归防线）、`SafMetaUpdater.update` 与 `SafUndoActions`（输出直接用真实 `ZipFile` 校验中央目录与条目顺序）。
  - **缩略图解码**：在 `@GraphicsMode(NATIVE)` 下以真实小尺寸 JPEG/PNG 经 `FakeDocumentsProvider` 覆盖 `CoilThumbnailSource` 与 `ThumbBudget` 降采样（已删死代码 `AndroidThumbnailSource` 不纳入）。

#### 第四层（Pillar D）：混合变异策略（`:domain` 自动化 PITest + UI/SAF 保持手工语义变异）
- **`:domain`（纯 JVM + JUnit 5）**：接入 **`info.solidsoft.pitest:1.19.0`**（支持 Gradle 9 + 配置缓存，默认 PIT `1.22.1`，`junit5PluginVersion = "1.2.1"`）。针对 `:domain` 5.3s 单测集按需运行高性价比自动变异。
  - *开源约束*：`com.arcmutate:pitest-kotlin-plugin:1.5.0` 需商业许可证（`arcmutate-licence.txt`），因此采用纯开源配置：`avoidCallsTo = setOf("kotlin.jvm.internal.Intrinsics")` + `mutators = setOf("DEFAULTS")`。
- **`:app`（AGP 9 + Compose/Robolectric）**：`info.solidsoft.pitest` 不支持 AGP 9 Android 变体；`pl.droidsonroids.pitest:0.3.0` 虽支持 AGP 9，但在 77 个 Robolectric Compose 类上跑字节码变异过慢且充满 `$changed`/`$dirty` 噪声。
- **混合结论**：`:domain:pitest` 作为按需/合入前闸门；UI / Roborazzi / SAF 继续保持 PixFold 现有的**定向手工语义变异纪律**（`pixfold-device-testing/SKILL.md:55`）。

#### 可选 Tier-3（非纯 JVM 零 ADB）：Gradle Managed Devices（GMD）
- `android.testOptions.managedDevices.localDevices` + `aosp-atd` / `google-atd`（API 35/36）可在无实体机时跑 `androidTest`（验证真实 AOSP `ExternalStorageProvider` 与 `WindowInsets`/IME）。
- **明确边界**：底层仍是无头虚拟 ADB + 模拟器，需 Windows WHPX/Hyper-V 与 2–4 GB 额外内存（在 6 GB 余量下偏紧），且 ATD 关闭硬件渲染（不可验 GPU 动画观感）。仅列为发版前/CI 可选 Tier-3，**绝不进日常 `gate.sh`**。

---

### 2.3 迁移边界矩阵（对齐 `pixfold-device-testing` 与 `HANDBOOK.md` §10）

| 验证类别 | (a) 纯 JVM 无 ADB | (b) JVM 视觉截图（Roborazzi） | (c) 仍须真机 / 模拟器（原因） |
| --- | --- | --- | --- |
| 领域逻辑（排序/命名/ComicInfo/`ZipFile` 兼容） | **已覆盖**（`:domain:test` 986 条）+ 新增按需 `:domain:pitest` | — | 仅在格式语义变更时用第三方阅读器（Kuro Reader / Mihon）抽验 |
| 结构与接线（节点/点击/Nav3/槽位/弹窗/语言同步） | **已覆盖**（59 个 Compose 测试；补 `fontScale` 与 §6.5 ≥48dp 守卫） | 可选状态黄金图 | — |
| **布局是否放得下 / 视口溢出 / 折行 / CJK 截断**（`SKILL.md` 第 3 行） | **迁至 `@GraphicsMode(NATIVE)`**（`w361dp-h794dp-560dpi` + `zh-rCN`/`en`/`ja` + `fontScale`，留 ~5–8% OEM 字体裕度） | **黄金图回归**捕获裁切/重叠/截断 | 仅当布局距视口边缘 < 5% 时抽验（ColorOS OEM 字体度量差） |
| **视觉规格**（纯黑描边、深浅/M3E 2021 vs 2025 色板、按钮填充/高度，`SKILL.md` 第 4 行） | 保留少量精确数值断言 | **替代易碎单点像素探针**，全屏/组件黄金图覆盖 Light/Dark/PureBlack/M3E | OLED 实际观感与 Android 12+ **壁纸动态取色**（`AccentChoice.System`） |
| **SAF 适配层与媒体**（`data/saf/*`、`CoilThumbnailSource`、`PackTaskService`） | **新增 `FakeDocumentsProvider` + `@TempDir` + `ShadowLooper`**（遍历、覆盖/新建/清理、ComicInfo 探测、400ms 防抖、Coil 解码） | — | 真实 `ExternalStorageProvider`/ColorOS URI 怪癖、`createDocument` 自动加 `(1)`、真实 `StatFs`、**1 GB+ Zip64 流式写盘与 256 MB 堆 OOM 压测**（`HANDBOOK.md` §10） |
| **动画与手势**（`Modifier.animateItem`、M3E 弹簧手感、预测性返回，`SKILL.md` 第 5 行） | 规格单测（`DragReorderAnimationSpecTest.kt`）+ `boundsInRoot`/状态转换 | 仅首尾关键帧黄金图 | **不可替代**：真机 60/120Hz 流畅度与主观手感、Android 16 边缘手势冲突、中文 IME 软键盘 |
| **Android 16 Live Updates 通知**（`OngoingProgressNotification`） | 通知构造 / `ProgressStyle` 字段单测（已有） | — | 真实 SystemUI / ColorOS 状态栏与通知栏渲染 |

---

### 2.4 Phase 1–4 分阶段落地路线与 `gate.sh` 集成草案

#### Phase 1（零新增依赖，约 0.5 天）— 解锁纯 JVM RNG 布局与无障碍断言
- 新建共享测试辅助 `PixFoldUiTestDefaults`（基于 `junit4.v2`，封装 `w361dp-h794dp-560dpi`、`PixFoldTheme + Surface(background)`、`assertFitsViewport()`、`assertMinTouchTargets(48.dp)`）。
- 为 `SingleSelectChipRow` / `TwoLevelChipRows`、`MetadataPage`、`PackPreviewPage`、`SettingsScreen` 增补 `@GraphicsMode(GraphicsMode.Mode.NATIVE)` 布局与溢出测试，覆盖 `zh-rCN` / `en` 及 `fontScale = 1.0f / 1.3f`。
- 更新 `.agents/skills/pixfold-device-testing/SKILL.md`，区分 `LEGACY`（0 宽打桩）与 `NATIVE`（真实 Minikin/Skia + `NotoSansCJK`），把强制真机布局核对收窄至近边缘 OEM 字体场景。

#### Phase 2（约 1 天）— 引入 Roborazzi `1.79.0` 视觉回归
- `android/gradle/libs.versions.toml`：
  ```toml
  [versions]
  roborazzi = "1.79.0" # >= 1.78.0 修复 Gradle 9 + Configuration Cache (#969/#976)

  [plugins]
  roborazzi = { id = "io.github.takahirom.roborazzi", version.ref = "roborazzi" }

  [libraries]
  roborazzi = { group = "io.github.takahirom.roborazzi", name = "roborazzi", version.ref = "roborazzi" }
  roborazzi-compose = { group = "io.github.takahirom.roborazzi", name = "roborazzi-compose", version.ref = "roborazzi" }
  roborazzi-junit-rule = { group = "io.github.takahirom.roborazzi", name = "roborazzi-junit-rule", version.ref = "roborazzi" }
  ```
- `android/app/build.gradle.kts`：应用 `alias(libs.plugins.roborazzi)`，仅加入 `testImplementation` 依赖，并配置：
  ```kotlin
  roborazzi {
      outputDir.set(layout.projectDirectory.dir("src/test/snapshots"))
  }
  ```
- 首批黄金图：Home（Light / Dark / PureBlack）、Organize 芯片行（收口 / 展开 / 二级浮层）、计划页底栏（Light / Dark）、Settings（M3 2021 vs M3E 2025）、核心弹窗，并逐步替换易碎的单点像素探针。

#### Phase 3（约 1–2 天）— 离线 SAF 与媒体集成测试底座
- 在 `android/app/src/test/.../data/saf/` 建立 `@TempDir` 驱动的 `FakeDocumentsProvider`，端到端覆盖 `AndroidSafSource`（扫描 + 400ms 防抖）、`SafCbzOutputSink`、`SafOutputExistsProbe`、`SafPageBytes`、`SafExistingComicInfoProbe`、`SafMetaUpdater`、`SafUndoActions` 与 `CoilThumbnailSource`，并用真实 `ZipFile` 核验输出归档。

#### Phase 4（约 0.5 天，按需闸门）— `:domain` 自动化 PITest 与 `gate.sh` 集成
- `android/domain/build.gradle.kts`：
  ```kotlin
  plugins {
      alias(libs.plugins.kotlin.jvm)
      id("info.solidsoft.pitest") version "1.19.0" // Gradle 9 + CC 兼容
  }
  pitest {
      pitestVersion.set("1.22.1")
      junit5PluginVersion.set("1.2.1")
      targetClasses.set(setOf("com.cyanix.pixfold.domain.*"))
      avoidCallsTo.set(setOf("kotlin.jvm.internal.Intrinsics"))
      outputFormats.set(setOf("HTML", "XML"))
      threads.set(4)
  }
  ```
- **`android/gate.sh` 集成草案**（保留 `gate-last.rc`、零 `| tail`、`:app:lintDebug` 零告警）：
  - 日常 `DEFAULT_TASKS` 维持快速四道闸门，新增具名子档位：
    - `./gate.sh --visual` → `:app:verifyRoborazziDebug`
    - `./gate.sh --record` → `:app:recordRoborazziDebug`
    - `./gate.sh --mutate` → `:domain:pitest`
  - 待验证 `:app:verifyRoborazziDebug` 增量耗时 < 15s、CC 零失效且 `:app:lintDebug` 对 `src/test/snapshots` 零告警后，再评估是否并入默认闸门。

---

### 2.5 一手来源清单

- **仓库源码与实证产物**：`android/gradle/libs.versions.toml`、`android/gradle/wrapper/gradle-wrapper.properties`、`android/app/build.gradle.kts`、`android/domain/build.gradle.kts`、`android/gradle.properties`、`android/gate.sh`、`android/build/gate-last.log`、`.mise.toml`、`docs/product/HANDBOOK.md`（§2/§6.5/§7/§8/§10）、`.agents/skills/pixfold-build-gates/SKILL.md`、`.agents/skills/pixfold-device-testing/SKILL.md`、`PixFoldApp.kt`、`SafPackPlatform.kt`、`SafPackExecutor.kt`、`SafMetaUpdater.kt`、`AndroidSafSource.kt`、`AndroidThumbnailSource.kt`、`ThumbnailCache.kt`、`CachingThumbnailSource.kt`、`PackTaskBus.kt`、`PackTaskService.kt`、`PackPreviewPage.kt`、`LibrarySteps.kt`、`LibraryState.kt`、`Theme.kt`、`NamingPage.kt`、`CbzWriter.kt`、`CbzMetaRewriter.kt`、`WorkspaceController.kt`、`OrganizeSteps.kt`、`ItemThumb.kt`、`ImagePreview.kt`、`DragReorderGrid.kt`、`SingleSelectChipRow.kt`、`TwoLevelChipRows.kt`、`PixFoldDialog.kt`、`PixFoldNav.kt`、`HomeCardStyleTest.kt` + `TEST-com.cyanix.pixfold.ui.HomeCardStyleTest.xml`、`ChipRowWrappingTest.kt`、`ChipLayoutRulesTest.kt`、`CbzReaderCompatTest.kt`，以及本机 Gradle 缓存 `nativeruntime-dist-compat-1.0.19.jar`。
- **上游官方文档与发布记录**：
  - Roborazzi GitHub Releases 与 PR `#969`/`#976` / Issue `#968`/`#830`（`io.github.takahirom.roborazzi@1.76.0–1.79.0`，Gradle 9 与 Configuration Cache 修复）
  - Cash App Paparazzi GitHub Releases（`app.cash.paparazzi@2.0.0-alpha05`/`alpha05.1`，AGP 9 与 LayoutLib v16.2.3）
  - Android Developers Compose Preview Screenshot Testing（`com.android.compose.screenshot` 废弃与 AGP `9.5.0-alpha03+` 测试套件）
  - Android Developers Gradle Managed Devices（`aosp-atd`）
  - `gradle-pitest-plugin`（`info.solidsoft.pitest@1.19.0`，Gradle 9 + CC，PIT `1.22.1`）
  - Arcmutate 文档（`com.arcmutate:pitest-kotlin-plugin@1.5.0` 商业许可证要求）
  - `pl.droidsonroids.pitest@0.3.0`（AGP 9 Android 分支）

---

## 三、按序实施清单与落地实证（分支 `d4/opt-and-non-adb-tests` 已合回 `dev`：`d1dc9a3` · 真机核对待补）

后续实施自 `dev`（`ad48f1d`）切出阶段分支 `d4/opt-and-non-adb-tests`，按 **M1 → M9** 顺序全部落地；每步均按「先红后绿 → 四道/五道闸门非缓存全绿（`gate-last.rc=0` + `--rerun-tasks`）→ 变异验证」推进。已于 2026-11-24 通过 `--no-ff` 合回 `dev`（合并提交 **`d1dc9a3`**，分支提交 `1f2e866`..`1b9db00`）并删除阶段分支。

> **真机核对状态（重要）**：M1–M9 **尚未在真机上核对**（**真机核对待补，与 M3E UI 优化一并真机核对**，待核项见 §3.2）。

| 里程碑 | 对应条目 | 提交 | 状态与核心交付物 |
| --- | --- | --- | --- |
| **M1** | **P0 #1** | `1f2e866` | ✅ 消除组合期主线程 SAF/ZIP I/O：引入 `LibraryVolumeLoader` 将卷探测移至 `Dispatchers.IO` 懒加载、`SafVolumeProbeSession` 按父目录批量缓存 `childrenUri` 并合并存在性与 CBZ URI 查询、删除 `SafExistingComicInfoProbe` 重复辅助函数；新增 3 组单测/UI 守卫 |
| **M2** | **P0 #2–#4** | `a64b057` | ✅ ① `ThumbnailCache` 同步化、`CachingThumbnailSource` 重抛结构化取消、`PackTaskBus` `update {}` 原子 CAS、`PackTaskService.snapshot` `@Volatile`；② `LibrarySteps` 改 `collectAsStateWithLifecycle()` 并隔离逐页进度弹窗、`PackPreviewPage`/`NamingPage`/`PixFoldTheme` 补 `remember` 与 `LazyColumn` `key`/`contentType` 虚拟化；③ 新增 `android/app/compose_stability.conf` 覆盖 `:domain` 值对象 + 缓存 `LibraryState.seriesGroups`（`AdapterBoundaryTest` 全绿） |
| **M3** | **P1 #5–#6** | `15500bd` | ✅ ① `CbzWriter`/`CbzMetaRewriter` 改 64 KiB `BufferedOutputStream` 与缓冲区复用、`CbzWriter` 落地 ≤ 8 MiB 有界单趟缓冲、`CbzMetaRewriter` 守卫 `STORED && size >= 0 && crc >= 0` 直通流式透传（`CbzReaderCompatTest` 全绿）；② `WorkspaceController.pick`/`rememberIfLoaded` 下沉 `ioDispatcher`、`move()` 单次 `persistentGrants()` 快照、`SafMetaUpdater` SAF 遍历 4→2 |
| **M4** | **P1 #7–#8** | `ca5ebe1` | ✅ ① `CancellationBridge` 接通协程 `isActive` 消除工作流 C / Organize 孤儿 IO、引入 `PixFoldSession` ViewModel 跨配置变更持态并收敛 `PixFoldApp`；② `ThumbnailSource.peek()` 消除 1 帧闪烁、`ImagePreview`/`DragGhost` 改 lambda `graphicsLayer`、`ChipFlow` 短路 `Probe` 趟、新增 `PreviewImageCacheSource` 小 LRU 并清理 `coilImageLoader` |
| **M5** | **P2 #9** | `3a50ba8` | ✅ 删除死代码 `AndroidThumbnailSource.kt` 与 `app/src/debug/nav3spike/`；硬编码 `RoundedCornerShape` 统一迁至 `MaterialTheme.shapes`；清理过期 `minSdk 34` 注释与 `PixFoldApp.kt` 编译警告/条件 `remember`；维持 `maxParallelForks = 1`；配置 Release R8 压缩与资源裁剪（**实测 release R8 APK 3.59 MB**） |
| **M6** | **无 ADB Phase 1** | `fa9005f` | ✅ 建立 `PixFoldUiTestDefaults`（`junit4.v2`、`w361dp-h794dp-560dpi`、`Surface(background)`、视口与 48dp 无障碍断言），补齐芯片/元数据/预览/设置 `@GraphicsMode(NATIVE)` 多语言与 `fontScale` 测试，并同步 `pixfold-device-testing/SKILL.md`。**当场抓出并修复真实无障碍缺陷**：`SettingsScreen.kt` 中 `RadioRow` 与 `ChoiceRow` 对话框选项同时存在 `Row.clickable` 与子 `RadioButton(onClick = ...)`，导致 TalkBack 暴露缺少 `contentDescription` 的重复可点击节点；改为 M3 推荐的 `Row.heightIn(min = 48.dp).selectable(role = Role.RadioButton)` + `RadioButton(onClick = null)` |
| **M7** | **无 ADB Phase 2** | `e13e3ad` | ✅ 接入 Roborazzi `1.79.0`（`layout.projectDirectory.dir("src/test/snapshots")`），落地 **14 张**核心页面与弹窗黄金图，验证 Configuration Cache 与 `:app:lintDebug` 零告警 |
| **M8** | **无 ADB Phase 3** | `5b4ff71` | ✅ 搭建 `@TempDir` 驱动的 `FakeDocumentsProvider` 离线 SAF 测试底座，端到端覆盖 `AndroidSafSource`（含 400ms 防抖）、`SafPackPlatform`、`SafMetaUpdater`、`SafRenameActions`/`SafUndoActions` 与 `CoilThumbnailSource`（真实 `ZipFile` 校验） |
| **M9** | **无 ADB Phase 4** | `202a0a9` | ✅ `:domain` 接入 `info.solidsoft.pitest:1.19.0`（开源配置，PIT `1.22.1` + `junit5PluginVersion = "1.2.1"`、`threads = 4`），扩展 `android/gate.sh` 支持 `--visual` / `--record` / `--mutate`（保持默认四道闸门与显式参数 100% 向后兼容），同步 `pixfold-build-gates/SKILL.md` 与 `docs/INDEX.md` 台账 |

### 3.1 M1–M9 实测总览（2026-11-24）

| 指标 | 基线（`ad48f1d`） | M1–M9 完成后（`d4/opt-and-non-adb-tests`） | 来源与判据 |
| --- | --- | --- | --- |
| `:app` 单测（JUnit XML） | 97 类 / **909** pass（+1 负向对照排除） | **117** 类 / **1,020** pass（0 fail / 0 error / 0 skip） | `android/app/build/test-results/testDebugUnitTest/TEST-*.xml` |
| `:domain` 单测（JUnit XML） | 65 类 / **986** pass | **66** 类 / **993** pass（0 fail / 0 error / 0 skip） | `android/domain/build/test-results/test/TEST-*.xml` |
| Roborazzi 视觉回归黄金图 | 0 张 | **14** 张（`:app:verifyRoborazziDebug` 全绿） | `android/app/src/test/snapshots/` |
| `:domain` PITest 自动变异（`./gate.sh --mutate`） | 未接入 | **62** 源码类（149 变异单元）/ 行覆盖 **98%**（`2,690/2,751`）/ 变异击杀 **83%**（**`1,449/1,736`**，`KILLED=1,448`、`TIMED_OUT=1`、`SURVIVED=191`、`NO_COVERAGE=96`）/ 测试强度 **88%**（`1,449/1,640`）/ 引擎耗时 **144s**（Gradle `3m 15s`，CC stored） | `android/domain/build/reports/pitest/index.html`、`mutations.xml` |
| 五道闸门全量非缓存（`./gate.sh --visual --rerun-tasks`） | —（仅四道闸门） | **`gate-last.rc = 0`**、`BUILD SUCCESSFUL in 3m 30s`、`63 actionable tasks: 63 executed`、`:app:lintDebug` **0 告警**、`Configuration cache entry stored.` | `android/build/gate-last.rc`、`android/build/gate-last.log` |
| Release R8 产物 | 未配置 minify/shrink | **3.59 MB**（启用 `isMinifyEnabled = true` + `isShrinkResources = true`） | M5（`3a50ba8`）实测 |
| 真实缺陷捕获 | — | **1 条真实无障碍缺陷**：M6 无障碍守卫捕获 `SettingsScreen.kt` `RadioRow`/`ChoiceRow` 重复可点击 `RadioButton`（TalkBack 缺 `contentDescription`），已按 M3 `selectable` 规范修复 | M6（`fa9005f`） |
| 真机核对状态 | — | **真机核对待补**（M1–M9 尚未经真机核对；将与下一轮 Material 3 Expressive UI 优化一并真机核对） | 见 §3.2 |

### 3.2 待补真机核对清单（与下一轮 Material 3 Expressive UI 优化一并核对）

M1–M9 均通过无 ADB 纯 JVM / Roborazzi / `FakeDocumentsProvider` / PITest 离线验证，但**尚未执行真机核对**。以下涉及真实设备配置变更、OEM SAF 行为、R8 运行期反射/序列化与 OLED/TalkBack 实感的 5 项须与下一轮 Material 3 Expressive UI 优化一并真机取证：

- [ ] **屏幕旋转 / 分屏配置变更状态保活**：验证 `PixFoldSessionViewModel` 跨配置变更保留手工排序、改名覆盖、元数据草稿与缩略图缓存，不重置工作流进度。
- [ ] **书架页 `LibraryRoute` 进页懒探测流畅度与大目录无卡顿**：验证 `LibraryVolumeLoader` + `SafVolumeProbeSession` 在大目录/多卷书库下按需异步探测无主线程掉帧或 ANR。
- [ ] **真实 ColorOS SAF 下大体积 CBZ 打包流式写盘与工作流 C 就地重写**：验证 `CbzWriter`（≤ 8 MiB 有界单趟缓冲）、`CbzMetaRewriter`（`STORED` 透传）与 `SafMetaUpdater` 在真实 `ExternalStorageProvider` 下的大包写入、中断取消与产物完整性。
- [ ] **Release R8 APK 安装与冷启冒烟（3.59 MB）**：真机安装并遍历核心路径，确认 Nav3 `@Serializable NavKey`、`AboutLibraries` 与 `PixFoldSessionViewModel` 混淆压缩后运行期无崩溃。
- [ ] **设置页单选弹窗 TalkBack 无障碍聚焦与 OLED 纯黑观感**：验证 M6 修复后的 `RadioRow`/`ChoiceRow` 在 TalkBack 下单次聚焦播报选中态、无重复可点击节点，并复核 OLED 纯黑模式实机观感。
