# 2026-11-13 迁移设计:DataStore / Coil / Navigation3

> 状态:待用户评审。评审通过后才进 writing-plans 与实施。

## 0. 背景与目标

- 2026-11-13 用户提供 8 个 Android 官方/主流库链接,要求评估是否适合 PixFold,确认后引入以「方便后期维护」。
- 逐项结论(版本以官方发布页为准,查询日 2026-11-13):
  - **已在用,无需动作**:Material3(`1.5.0-rc01` M3E,见 `android/gradle/libs.versions.toml`)、kotlinx.serialization(core+json `1.11.0`)。
  - **不需要**:OkHttp(`android/app/src/main/AndroidManifest.xml` 未声明 `INTERNET`,全 app 无网络);Room(无关系型需求,持久化= SharedPreferences + 文件)。
  - **需要迁移**:DataStore、Coil、Navigation3。
- 目标:**后期维护性优先**,分三阶段迁移,每阶段独立分支 + 四道闸门 + 真机核对。

## 1. 总原则

- **分步,不并做**。顺序:DataStore → Coil → Nav3。理由:风险/收益比递增,Nav3 回归面最大且对维护增益最小。
- 每阶段:单开分支(从 `dev` 开,前缀 `d4/…`,见 AGENTS §0.1 #4)→ 红绿 → `android/gate.sh` 四道闸门 → 真机核对(需用户逐轮授权)→ 文档回写 → 提交(只提交不推送)。
- 保留领域边界:`domain/` 纯 Kotlin 不引 Android 依赖;新库实现放 `app/` 层。

## 2. 阶段一:DataStore 替换 SharedPreferences

> **2026-11-13 用户裁决:本阶段取消。** 设置与工作区列表体量小、单写者、启动即读,`SharedPreferences`(同步读、`apply()` 已异步)更合适;DataStore 的 Flow/异步/事务在当前场景拿不到收益,还引入首帧闪烁 + SplashScreen 兜底的额外复杂度。**保留现实现**,§2 余下内容仅作历史参考。

### 2.1 范围
- 迁移:`android/app/src/main/kotlin/com/cyanix/pixfold/data/SharedPrefsSettingsStore.kt`(设置)、`android/app/src/main/kotlin/com/cyanix/pixfold/data/saf/SafTreeStore.kt`(工作区列表)。
- **不迁**:`android/app/src/main/kotlin/com/cyanix/pixfold/data/task/PackTaskStore.kt` —— KDoc 已写明「几个标量加短列表,SharedPreferences 更合适」,且为前台服务高频写。
- 依赖:`androidx.datastore:datastore-preferences:1.3.0`(stable,2026-10-21)。

### 2.2 接口变更(关键取舍)
- 现状:`android/domain/src/main/kotlin/com/cyanix/pixfold/domain/settings/SettingsStore.kt` 为同步 `fun load(): PixFoldSettings` / `fun save(settings)`;`SettingsStore.kt` 内另有 `InMemorySettingsStore`。
- **方案 A(推荐)**:`SettingsStore` 改为 `val settings: Flow<PixFoldSettings>` + `suspend fun save(PixFoldSettings)`;`android/app/src/main/kotlin/com/cyanix/pixfold/PixFoldApp.kt:150` 的 `var settings by remember { mutableStateOf(settingsStore.load()) }` 改为 `val settings by settingsStore.settings.collectAsStateWithLifecycle(initial = PixFoldSettings())`。代价:首帧用 default 值,暗色/语言可能闪一下。
- **方案 B(否决)**:保留同步门面,内部 `runBlocking` 读 DataStore —— 主线程阻塞,违背 DataStore 初衷。
- `SafTreeStore` 的读改写(remember/forget/move/setPinned)必须迁进 DataStore `edit {}` 事务,否则并发读改写丢失(现 SharedPreferences 是单进程内存映射 + 串行 apply)。

### 2.3 数据迁移与约束
- 用 `SharedPreferencesMigration`(datastore-preferences 提供),首次启动把旧 prefs 迁到 DataStore,键名映射与现 `KEY_*` 一致。
- ⚠️ DataStore **只支持单进程**。需确认无多进程读写(`PackTaskService` 未声明 `android:process`,默认同进程,可)。

### 2.4 影响文件
- `android/gradle/libs.versions.toml`、`android/app/build.gradle.kts`
- `android/domain/src/main/kotlin/com/cyanix/pixfold/domain/settings/SettingsStore.kt`
- `android/app/src/main/kotlin/com/cyanix/pixfold/data/SharedPrefsSettingsStore.kt`(改 DataStore 实现)
- `android/app/src/main/kotlin/com/cyanix/pixfold/data/saf/SafTreeStore.kt`
- `android/app/src/main/kotlin/com/cyanix/pixfold/PixFoldApp.kt:148-150`
- 测试:`android/app/src/test/kotlin/com/cyanix/pixfold/data/SharedPrefsSettingsStoreTest.kt`、`android/app/src/test/kotlin/com/cyanix/pixfold/data/saf/SafTreeStoreTest.kt`、`android/domain/src/test/kotlin/com/cyanix/pixfold/domain/settings/SettingsStoreTest.kt`

### 2.5 验证
- 单测:DataStore 用 `PreferenceDataStoreFactory.create(scope, produceFile = { tmp })`,断言读写/默认回退/迁移。
- 四道闸门 `android/gate.sh`(lintDebug / testDebugUnitTest / domain:test / assembleDebug)。
- 真机:设置项(主题/纯黑/语言/列数)持久化、杀进程重开保留;工作区列表增删/排序/钉住持久化。

### 2.6 回滚
- 单分支,`git revert`。注意 `SharedPreferencesMigration` 默认迁完删旧 key ⇒ 回滚会丢数据。**设计上让迁移保留旧 prefs 一个版本周期**(手动清理),回滚即恢复。

## 3. 阶段二:Coil 替换自研缩略图缓存(接口不变)

### 3.1 范围
- `android/app/src/main/kotlin/com/cyanix/pixfold/ui/workspace/CachingThumbnailSource.kt`(159 行)+ `android/app/src/main/kotlin/com/cyanix/pixfold/ui/workspace/ThumbnailCache.kt`(93 行)内部换 Coil;`android/app/src/main/kotlin/com/cyanix/pixfold/ui/workspace/ThumbnailSource.kt` 接口**不变**(`suspend fun thumbnail(uri: String, widthPx: Int, heightPx: Int): ImageBitmap?`)。
- UI 5 个调用点(`DraggableThumbCard` / `ThumbRow` / `DragGhost` ×2 / `ImagePreview`)与 `LocalThumbnailSource` 一行不改。

### 3.2 依赖与配置(避免网络/Compose 耦合)
- 只引 core:`io.coil-kt.coil3:coil:3.6.3`(Android 后端)。**不引** `coil-network-*`,**不引** `coil-compose`(避免与 Compose BOM 版本耦合;我们只需 `ImageLoader.execute`)。
- 关磁盘缓存:`ImageLoader.Builder(context).diskCache(null)`(或 `diskCachePolicy(CachePolicy.DISABLED)`)。
- 内存预算:`MemoryCache.Builder().maxSizePercent(context, 0.25)`,或字节上限对齐现 `thumbBudgetFor`(DEFAULT_BUDGET:512px / 32 张 / 8 路)。
- SAF:Coil 内置 `ContentUriFetcher` 支持 `content://`(SAF tree/document URI)。
- 取 `ImageBitmap`:`imageLoader.execute(ImageRequest.Builder(context).data(uri).size(px).build())` → `SuccessResult` → `(drawable as BitmapDrawable).bitmap.asImageBitmap()`。
- 并发/去重:Coil `DeDupeConcurrentRequestStrategy` + 默认调度器;是否复刻现 `Semaphore(maxConcurrent)` 待压测。

### 3.3 影响文件
- `android/gradle/libs.versions.toml`、`android/app/build.gradle.kts`
- `android/app/src/main/kotlin/com/cyanix/pixfold/ui/workspace/CachingThumbnailSource.kt`(重写内部,类名/接口保留以最小化 diff)
- `android/app/src/main/kotlin/com/cyanix/pixfold/ui/workspace/ThumbnailCache.kt`(删除或降级为不再使用)

### 3.4 验证与风险
- 真机:千级缩略图(历史基线 862 张)滚动帧率、内存峰值、快速滚动无重复加载、切工作区清缓存;需在同场景复测对比现 `CachingThumbnailSource` 读数。
- 风险:Coil 3.6.x 声明的 Compose/Kotlin/Okio 版本与项目(Kotlin 2.4.20、Compose BOM 2026.09.00)可能有传递依赖升级,`gate.sh` 验证 lint/R8;`ContentUriFetcher` 大图 downsampling 需实测。
- **若真机性能不如自研,允许否决、保留自研** —— Coil 不是必须。

## 4. 阶段三:Navigation3(先做受限 spike)

### 4.1 结论
- Nav3 已 stable `1.2.1`(2026-11-04,`developer.android.com/jetpack/androidx/releases/navigation3`)。API:`NavBackStack` + `NavDisplay` + `entryProvider` + `rememberNavBackStack`,无 NavController/NavHost。
- 项目 3 处 NavHost(`PixFoldApp.kt:555` 根、`OrganizeSteps.kt:420`、`LibrarySteps.kt:700`)+ `android/app/src/main/kotlin/com/cyanix/pixfold/nav/PixFoldNav.kt` 137 行预测性返回/转场定制 + 15 条返回相关测试,需重写。

### 4.2 先 spike,不全量
- 只迁「根导航 6 目的地」到 Nav3,独立 spike 分支,产出工作量/风险报告;重点验证预测性返回 handoff(Nav3 1.3.0-alpha 仍在修)。
- 若回归面过大(预测性返回 / 嵌套 NavHost / type-safe route),**保留 nav-compose 2.10.2** —— 它本身是官方库,维护性不差。

### 4.3 影响文件(spike 范围)
- `android/app/src/main/kotlin/com/cyanix/pixfold/nav/PixFoldRoutes.kt`(Nav3 用 `NavKey`)
- `android/app/src/main/kotlin/com/cyanix/pixfold/nav/PixFoldNav.kt`、`PixFoldApp.kt:339/555`
- 相关导航测试

### 4.4 spike 实测结果(2026-11-13,1.2.1 真机)

**结论:通过。** 根导航 6 目的地可迁、两档预测性返回可复刻、`predictiveBackAnimation` 开关有效。

#### API 差异表(均以 javap 核实真实签名)

| 关注点 | navigation-compose 2.10.2(现版) | Navigation3 1.2.1 |
| --- | --- | --- |
| 路由标识 | `@Serializable` type-safe route + `NavHost`/`composable<T>` | `@Serializable` 类型实现 `NavKey` + `entryProvider { entry<T> { } }` |
| 返回栈 | `NavController` 隐式栈;`navigate`/`popBackStack`/`hasRoute` | 显式 `NavBackStack<NavKey>`(可变的 List);`add`/`removeAt`/`rememberNavBackStack` 可保存 |
| 嵌套图 | `NavGraphBuilder.nestedGraph` | **无 nested graph**;单一扁平栈,多栈需多个 `NavDisplay` 或自行分段 |
| 前进转场 | `NavHost(enterTransition/exitTransition)`,lambda 收 `AnimatedContentTransitionScope<NavBackStackEntry>` | `NavDisplay(transitionSpec: (AnimatedContentTransitionScope<Scene<T>>) -> ContentTransform)` |
| 返回转场 | `popExitTransition`/`popEnterTransition` | `popTransitionSpec`,同上 `ContentTransform(None, exit)` |
| 预测性返回 | `predictivePop*Transition`;NavHost 内建按手指 progress 驱动 | `predictivePopTransitionSpec: AnimatedContentTransitionScope<Scene<T>>.(Int) -> ContentTransform`;实测同样跟手,Int 为手势进度 |
| 默认时长 | 700ms 淡入淡出(现版已覆盖成 None) | `DEFAULT_TRANSITION_DURATION_MILLISECOND` + `defaultTransitionSpec()`(同样可覆盖) |
| ViewModel 装饰器 | 无此概念,ViewModel 走 lifecycle-viewmodel-compose | `rememberViewModelStoreNavEntryDecorator` 在 `lifecycle-viewmodel-navigation3`,**仅 2.12.0-alpha05,无稳定版**;本项目未用 ViewModel,不受影响 |
| 返回事件 | `NavHost` + `BackHandler` | `NavDisplay(onBack = …)` |
| 返回相关测试 | 可拿到 `NavController` 做断言 | 直接操作 List,导航 API 断言需改写;现有 15 条多为 UI 行为测试,语义可复用 |

#### 复刻方案(已真机逐帧验证)

- 前进:`transitionSpec = { ContentTransform(EnterTransition.None, ExitTransition.None) }` —— 与现版「进入瞬切」一致。
- 返回:`popTransitionSpec = { ContentTransform(EnterTransition.None, style.exit() ) }`;Page 档 `scaleOut(0.90f) + fadeOut()`、Step 档 `fadeOut()`。
- 手势:`predictivePopTransitionSpec = { _ -> ContentTransform(EnterTransition.None, if (predictiveBackAnimation) exit else ExitTransition.None) }` —— 关开关时拖动期间页面纹丝不动,松手仍 pop。
- 每个目的地仍套 `DestinationSurface`(M3 background 底板),否则缩小的上层页会与下层文字重叠。

#### 全量迁移工作量与风险

1. 13 个 route 改 `NavKey`(机械)。
2. 3 个 `NavHost`(根 + 工作流 A/B)→ 建议 1 根 `NavDisplay` + 每个工作流一个子 `NavDisplay`(各自 back stack);这是**主要设计点**,需用户确认工作流内转场/返回语义如何映射。
3. `nav/StepNavigation.kt`(104 行,步骤序号由返回栈派生、双击 pop 防护、Tab 线性跳转)整段重写。
4. 15 条返回测试重跑;两条直接断言 nav 行为的测试(`StepBackGuardTest`、`BackNavigationTest`)需按新 API 改写。
5. 设置项 `predictiveBackAnimation` 直接接到 `predictivePopTransitionSpec`,已验证有效。
6. 风险:Nav3 1.2.1 较新、生态组件(ViewModel 装饰器)仍 alpha;若将来需要 per-destination ViewModel,要接受 alpha 或自行实现。

### 4.5 全量迁移落地（2026-11-14，用户升级范围）

**结论：全量迁移完成，代码与四道闸门已绿并已提交（不推送，提交号 `d240f53`）；真机核对顺延到 2026-11-15（用户 2026-11-14 裁决先提交、明天确认后再 push）。**

- **范围**：13 个 route 全部实现 `NavKey`；3 个 `NavHost` 全部换成 `NavDisplay`——根导航一个、工作流 A/B 各一个，各自持 `NavBackStack<NavKey>`（不再有 nested graph）。
- **依赖**：`androidx.navigation3:navigation3-runtime:1.2.1` + `navigation3-ui:1.2.1` 进入生产依赖；旧 `navigation-compose:2.10.2` 已从依赖与 catalog 移除（全仓无 `androidx.navigation.*` 旧 API 引用）。
- **`nav/PixFoldNav.kt`**：`PixFoldNavHost(backStack: NavBackStack<NavKey>, predictiveBackAnimation, style, modifier, builder)`；`NavDisplay(onBack={ backStack.popOne() }, entryDecorators=listOf(rememberSaveableStateHolderNavEntryDecorator()), transitionSpec=ContentTransform(None,None), popTransitionSpec=ContentTransform(None, style.exitTransition()), predictivePopTransitionSpec={ _ -> ContentTransform(None, if (predictiveBackAnimation) style.exitTransition() else None) })`；保留 `PREDICTIVE_MIN_SCALE=0.90f` 与 `DestinationSurface`。
- **`nav/StepNavigation.kt`**：从 `NavController` 扩展改成 `NavBackStack<NavKey>` 扩展；`organizeStepIndex` / `libraryStepIndex` 用 `when (key)`；`popWorkflowStep` = `if (size <= 1) false else removeAt(lastIndex)`；`popOne` = `if (size > 1) removeAt`；`popTo(key)` 弹到指定 key；`goToOrganizeStep` / `goToLibraryStep` 往回 `popTo`、往前逐级 `add`。
- **实测缺陷与修复**：`NavDisplay` 只以 `backStack` 的 equals 作 NavEntry 缓存键，`entryProvider` 不参与 key ⇒ entryProvider 只在栈变化时调用；原先在首次组合时捕获 `workspaceState=None`，工作区变 Ready 后 Home 内容不重组，首页入口一直 `[Disabled]`，31 条测试红。修法：`rememberUpdatedState(builder)`，content 每次重组时重新 apply builder。清理临时插桩与调试测试后，`:app:testDebugUnitTest` 896 / 0、`:domain:test` 986 / 0，四道闸门 `--rerun-tasks` 62 executed、BUILD SUCCESSFUL、lint 零告警。
- **保留物**：`android/app/src/debug/` 的 Nav3 spike Activity 作参考，release 不含。

## 5. 验收与顺序
- 阶段一(DataStore)→ 阶段二(Coil)→ 阶段三(Nav3 spike)。
- 每阶段独立分支,独立四道闸门 + 真机 + `docs/INDEX.md` 登记。

## 6. 待用户拍板的决策点
1. DataStore:是否连带 `SafTreeStore` 一起迁(否则只迁设置)。
2. DataStore:改 Flow 后的首帧异步(可能闪一下默认主题/语言)是否可接受。
3. Coil:是否接受引入 `okio` 传递依赖 + core artifact 体积;真机不如自研时是否允许放弃。
4. Nav3:是否只做 spike(推荐),还是确定全量迁移。
5. 迁移兼容:DataStore 迁移是否保留旧 SharedPreferences 一个版本周期。

## 7. 用户裁决(2026-11-13)

1. DataStore:**取消**(2026-11-13 用户重新判断):设置与工作区列表保留 `SharedPreferences` 更合适,详见 §2 顶部裁决。
2. 首帧:候选方案见 §7.1,待定。
3. Coil:**换**(接受 `okio` 传递依赖;真机若不达标再评估,不硬换)。
4. Nav3:**先 spike,不全量**(2026-11-14 用户升级为**全量迁移**,见 §4.5)。
5. 迁移兼容:DataStore 迁移**保留**旧 SharedPreferences 一个版本周期,便于回滚。

### 7.1 首帧候选(对应 §2.2 DataStore Flow)
- 现象:冷启动第一帧用默认 `PixFoldSettings`(暗色用户闪浅色、英文用户闪中文),DataStore 异步读完 emit 真实值后切换,约几十毫秒~一两帧;热启动因内存缓存几乎无感。
- **A(推荐)**:Android 12+ `installSplashScreen()` + `setKeepOnScreenCondition { !settingsLoaded }`,splash 保持到首值到达,用户无感;minSdk 36 完全可用。
- B:接受闪烁,最省事。
- C:`runBlocking` 首读 —— 否决,阻塞主线程。
