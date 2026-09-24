# D1 导航迁移到 Navigation Compose —— 任务记录与实施方案

> **记录时间**:2026-09-22 起草;同日**写码完成**并回写结果(见 §7)。
> **状态**:**已实施完成,验收矩阵全绿**(`:app:testDebugUnitTest` 273 / `:domain:test` 224 /
> `assembleDebug` / `lintDebug` 均通过)。**未提交、未 push**(push 一律由用户处理)。
> **开工前 3 问已于 2026-09-22 全部裁决**(route 形式 = type-safe / 步骤容器 = 嵌套 NavHost /
> 开关关闭语义 = 保留普通转场)—— 见 §4.1;另 3 项待实现中探针确定,见 §4.2。**§4.2 的 3 项均已实测收敛,结论见 §7.2**。
> **工作分支**:`d1/settings-impl`(自 `dev` 起算 **18** 提交,**未合回 `dev`,未 push**)。
> **相关**:`HANGOFF.md:1246`(被本迁移取代的「第 3 步」原始描述)、
> `docs/superpowers/specs/2026-09-20-d1-settings-theme-design.md`(设置规格)。

---

## 0. 为什么迁移(用户裁决与真因)

**用户提问(m01556 原话)**:

> 「继续，另外先回答我，预测性返回手势适配都要单独处理吗，感觉有点繁琐」

**回答要点(已答,此处存档)**:

- **不是每页都要处理**。迁移前全项目只有 **3 个注册点**:
  1. 根容器 —— `pixfold-d1/app/src/main/kotlin/com/pixfold/d1/PixFoldApp.kt:104`
     (`PredictiveBackContainer`);
  2. 步骤容器 —— `pixfold-d1/app/src/main/kotlin/com/pixfold/d1/ui/workflowa/WorkflowASteps.kt`;
  3. 步骤容器 —— `pixfold-d1/app/src/main/kotlin/com/pixfold/d1/ui/workflowb/WorkflowBSteps.kt`。

  首页 / 设置 / 许可 / 预览**全部继承根容器,一行没写**。步骤容器必须自己声明,
  是因为它们**自己拥有返回语义**(步骤 2 → 步骤 1,而不是退出工作流)——
  「谁拥有返回去处,谁就声明」。
- **繁琐感的真因不是预测性返回**,而是**本项目根导航是手写 `when (screen)` 状态切换**
  (`PixFoldApp.kt:74` 的 `var screen by remember { mutableStateOf<Screen>(Screen.Home) }`),
  没有 back stack、没有转场引擎,所以"背后画哪一页""手势进度怎么映射"都得自己写。
- **官方路径**:`androidx.navigation` 的 `NavHost` 自己订阅手势进度、**自己同时渲染两层**
  (目标页在背后是它内部行为),应用侧只需声明转场:
  ```kotlin
  NavHost(
      navController = navController,
      startDestination = Home,
      popExitTransition = {
          scaleOut(
              targetScale = 0.9f,
              transformOrigin = TransformOrigin(pivotFractionX = 0.5f, pivotFractionY = 0.5f)
          )
      },
      popEnterTransition = { EnterTransition.None },
      modifier = modifier,
  )
  ```
  (源码出处 `PredictiveBackSnippets.kt`,取自
  <https://developer.android.com/develop/ui/compose/system/predictive-back-setup>)

**用户裁决(m01628)**:选 **「现在就迁移到 Navigation Compose」** ——
明确放弃「先手工做完第 3 步(两层渲染)」与「等导航模型变大再迁移」两条备选。

---

## 1. 已核实的源码级事实(决定性,决定怎么写代码)

> 来源:navigation-compose 2.10.1 及其依赖的 **-sources.jar** 实读
> (`NavHost.kt` / `NavHostEventHandler.kt` / `NavigationEventProcessor.kt` /
> `DefaultNavTransitions.android.kt` / `NavGraphBuilder.kt` / `ComposeNavigator.kt` /
> `activity/OnBackPressedDispatcher.kt`)。**这些是本次迁移最有价值的产出,勿丢。**

| # | 事实 | 依据 |
| --- | --- | --- |
| F1 | `NavHost` **全自动**接管预测性返回,**应用不得再装自己的 `PredictiveBackHandler`** | `NavHost.kt:825-829` `rememberNavHostEventHandler(composeNavigator, enabled = currentBackStack.size > 1)`;`NavHostEventHandler.kt:74-147` `onBackStarted→prepareTransition()`、`onBackCompleted→navigator.popBackStack(entry, false)`、`onBackCancelled→resetState()` |
| F2 | **嵌套 NavHost 回落免费**:内层在自身起始目的地时,返回自动落到外层 | `NavHost.kt:826/838` 闸门 `isBackEnabled = currentBackStack.size > 1`;`NavigationEventProcessor.kt:552-562` `TRANSITIONING_BACK -> findHandler { it.isBackEnabled }`。**无需** `enableOnBackPressed` |
| F3 | ⚠️ **只设 `popExitTransition` 对手势无效** | 手势走**专用**参数 `predictivePopEnterTransition`/`predictivePopExitTransition`(`NavHost.kt:866-900`,`if (inPredictiveBack)` 分支)。这是相对 2.8/2.9 的**行为回归**(旧版 `finalExit` 分支含 `\|\| inPredictiveBack`)。**官方文档那段示例在 2.10 上已不足以让手势有动画** |
| F4 | **目标页在背后是 nav 内部行为** | `NavHost.kt:967-999`:`composeNavigator.isPop.value \|\| inPredictiveBack` 时 `targetZIndex = initialZIndex - 1f` |
| F5 | 转场全设 `None` **仍能正常 pop** | 完成由 dispatcher 回调驱动(`onBackCompleted()`),与转场 lambda 无关 |
| F6 | 系统默认预测性转场 | `DefaultNavTransitions.android.kt:62-80`:enter = `fadeIn(spring(dampingRatio = 1.0f, stiffness = 1600.0f))`,exit = `scaleOut(targetScale = 0.7f)` |
| F7 | 环境门槛:**`androidx.activity` ≥ 1.12.0** | 1.9.0/1.10.0/1.11.0 **无** `NavigationEventDispatcherOwner`;1.12.0/1.13.0 有。**POM 里声明的 `activity 1.8.0` 不够**。本项目 `activity-compose 1.13.0` ✅ |
| F8 | **最低 navigation 版本 = 2.10.0** | `predictivePop*` 参数与 navigationevent 桥接都在 2.10.0 出现(2.8/2.9 用 activity-compose 的 `PredictiveBackHandler`) |
| F9 | manifest 无需改动 | 只需已有的 `android:enableOnBackInvokedCallback="true"` + targetSdk 33。API 33 = 仅完成回调;**API 34+ 才有可 seek 的交互动画**(真机 Android 16 ✅) |
| F10 | ⚠️ **per-destination 的 predictive 转场没有公开 API** | `NavGraphBuilder.kt` 里 `predictivePop` 匹配数 **0**;`composable` 只暴露 5 个经典参数(`ComposeNavigator.kt:132/137` 内部字段存在但未接出)。**预测性观感只能在 NavHost 级配置** |
| F11 | 旧 `OnBackPressedDispatcher` 与 NavHost **共用同一个 handler 队列**(LIFO) | `activity/OnBackPressedDispatcher.kt` → `eventDispatcher.addHandler`;`NavigationEventProcessor.kt:260-284` `defaultHandlers.addFirst(handler)`。**遗留的手写容器会与 NavHost 抢队列** → 必须删干净 |
| F12 | 已知竞态(库自认) | `isBackEnabled` 翻 false 的**同一帧**内发起的手势仍可能命中陈旧 handler(b/375343407、b/384186542) |
| F13 | 测试可用两条路径 | ① `activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(progress = 0.5f, swipeEdge = EDGE_LEFT))` → `dispatchOnBackProgressed(...)` → `onBackPressed()` / `dispatchOnBackCancelled()`;② `LocalNavigationEventDispatcherOwner provides TestNavigationEventDispatcherOwner(...)` + 公开的 `DirectNavigationEventInput`(backStarted/Progressed/Completed/Cancelled)。**navigation 自身零预测性返回测试 API** |

**版本选择**:`androidx.navigation:navigation-compose:2.10.1`(最新 stable;
`dl.google.com` maven-metadata lastUpdated `20260909170654`,其上为 2.10.0 → 2.10.1)。
其 POM 请求 compose `animation/ui/runtime 1.10.5`,**低于**本项目 BOM 的 **1.12.1** →
由 BOM 胜出,不会降级。(BOM 2026.09.00 实测映射:`animation/animation-core/foundation/runtime/ui/ui-test-junit4` = **1.12.1**,
`material3` = **1.4.0**。)

**已排除的备选**:Navigation 3 的
[`NavDisplay.PredictivePopTransitionKey`](https://developer.android.com/reference/kotlin/androidx/navigation3/ui/NavDisplay.PredictivePopTransitionKey)
(1.1.0 起,放在 `NavEntry.metadata`/`Scene.metadata` 的 DSL 里)—— 用户选的是 Navigation Compose。

---

## 2. 影响面(开工前的代码事实,已逐一读过;✅ 均已按本表动作完成)

> 本节的"现状"列是**迁移前**的读数,保留以说明改动依据。实际改动结果见 §7.6。

| 文件 | 现状 | 迁移动作 |
| --- | --- | --- |
| `pixfold-d1/app/src/main/kotlin/com/pixfold/d1/PixFoldApp.kt` | 195 行。`sealed interface Screen`(:38-45:Home/Settings/Licenses/WorkflowA/WorkflowB/`data class Preview(val index: Int)`);`var screen by remember { mutableStateOf<Screen>(Screen.Home) }`(:74);`rootBack` 的 `when (screen)`(:95-102);`PredictiveBackContainer(enabled = settings.predictiveBackAnimation, onBack = rootBack)`(:104-107);`when (val current = screen)` 的 6 个分支(:108-158)。**顶部注释(:33-34)仍写「不引入 Navigation 组件 —— 保持"零三方依赖"」,必须改写** | 整块改用 `NavHost`;`Screen` 或被 route 取代;`rootBack` 删除 |
| `pixfold-d1/app/src/main/kotlin/com/pixfold/d1/ui/workflowa/WorkflowASteps.kt` | 278 行。`enum class WorkflowAStep(val label: String) { Sort("步骤1 排序与预览"); Naming("步骤2 命名结构"); Plan("步骤3 执行计划") }`;`var stepIndex by remember { mutableIntStateOf(0) }`;`PredictiveBackContainer` 包住整页(含 `PrimaryTabRow`);签名末尾 `predictiveBackAnimation: Boolean = true` | 改**嵌套 NavHost**(或继续用 stepIndex——见 §4 待决) |
| `pixfold-d1/app/src/main/kotlin/com/pixfold/d1/ui/workflowb/WorkflowBSteps.kt` | 同上结构(步骤:library → metadata → pack) | 同上 |
| `pixfold-d1/app/src/main/kotlin/com/pixfold/d1/ui/components/PredictiveBackContainer.kt` | 141 行,**整文件删除**(NavHost 全接管;留着会抢同一 LIFO 队列,见 F11) | **删除** |
| `pixfold-d1/app/src/test/kotlin/com/pixfold/d1/BackNavigationTest.kt` | 148 行,6 条。`@RunWith(RobolectricTestRunner)` + `@Config(sdk = [34], qualifiers = "w400dp-h1000dp")` + `createAndroidComposeRule<ComponentActivity>()`,用 `onBackPressedDispatcher.onBackPressed()` 驱动 | 断言层级后退的用例**多半能保留**,但驱动方式需按 F13 调整 |
| `pixfold-d1/app/src/test/kotlin/com/pixfold/d1/PredictiveBackTest.kt` | 277 行,9 条。依赖 `SemanticsNode.boundsInRoot` 反映 `graphicsLayer` 缩放来离线断言"变换随进度发生" | **重写为主**:NavHost 的缩放来自 nav 内部 `SeekableTransitionState`,不再是我们自己的 `graphicsLayer` → 需重新找可离线断言的量(见 §4) |
| `pixfold-d1/app/src/test/kotlin/com/pixfold/d1/AppNavigationTest.kt` | 78 行 | 视 route 重构情况调整 |
| `pixfold-d1/gradle/libs.versions.toml` | **无任何 navigation 条目** | 新增 `navigationCompose = "2.10.1"` + `navigation-compose` library |
| `pixfold-d1/app/build.gradle.kts` | 依赖清单见下;lint `disable += setOf("OldTargetApi")`、`warningsAsErrors = true` | 加 `implementation(libs.navigation.compose)` |
| `AGENTS.md` / `HANGOFF.md` / `README.md` / 设置规格 | 「零三方依赖」相关表述与「第 3 步」描述须随迁移改写 | 见 §5 |

---

## 3. 实施方案(已按 §4.1 裁决更新;建议顺序)

1. **依赖**:`pixfold-d1/gradle/libs.versions.toml` 增
   `navigationCompose = "2.10.1"`、
   `navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigationCompose" }`,
   以及 serialization 插件与依赖:
   `kotlinSerialization = "2.4.20"`(与项目 Kotlin 同版本)、
   `kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlinSerialization" }`、
   `kotlinxSerializationCore = "1.11.0"`、
   `kotlinx-serialization-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-core", version.ref = "kotlinxSerializationCore" }`。
   `pixfold-d1/app/build.gradle.kts` 增插件 `alias(libs.plugins.kotlin.serialization)` +
   `implementation(libs.navigation.compose)` + `implementation(libs.kotlinx.serialization.core)`。
2. **根导航 → `NavHost`**,**type-safe route**(§4.1 第 1 项):
   `@Serializable` 的 route 用 `data object`(无参)/ `data class`(带参,即 `Preview(val index: Int)`);
   配 `composable<Home> { … }` / `navController.navigate(Home)` / `entry.toRoute<Preview>()`。
   `Screen` sealed interface 由 route 类型取代(见 §4.2 第 5、6 项)。
3. **步骤容器 → 嵌套 `NavHost`**(§4.1 第 2 项):回落免费(F2),**不要**再写 `enableOnBackPressed`。
4. **转场(NavHost 级,见 F3/F10 与 §4.1 第 3 项)**:
   - 开:`predictivePopExitTransition = { scaleOut(targetScale = 0.9f, transformOrigin = TransformOrigin(0.5f, 0.5f)) }`
     + `predictivePopEnterTransition = { EnterTransition.None }`(目标页静止);
   - 关:上述两个 `predictivePop*` 设 `None`,**但保留普通 `popEnterTransition`/`popExitTransition`**
     (仍有转场,只是不跟手;F5:`None` 不阻断 pop)。
   - ⚠️ 普通 `popExitTransition` 也要一并设,否则**非手势**的返回(返回键、代码 pop)观感不一致。
5. **删除** `PredictiveBackContainer.kt`(连同其 `behind` 参数)。
6. **测试重写**:按 F13 的两条路径驱动;`PredictiveBackTest` 的 9 条需按 §4.2 第 4 项**先探针再落断言**。
7. **验收**:`:domain:test` + `:app:testDebugUnitTest` + `:app:assembleDebug` + `:app:lintDebug` 全绿,
   并对关键接线做**变异验证**(沿用 `pixfold-d1/app/build/mutation-check.sh` 的做法:
   变异与还原必须在**同一次** bash 调用内,因为 bash 沙箱不跨调用保留 `/tmp`)。
8. **真机**(须用户授权):核对"设置返回时**能看到目标页在背后**"这一原始诉求是否真的达成。

---

## 4. 开工前待决 —— 3 项已裁决,3 项待探针/实现中定

### 4.1 已裁决(2026-09-22,用户拍板)

1. **route 形式 = type-safe**(用户 2026-09-22 选定,经我给出对比分析后)。
   - **是官方插件**:`org.jetbrains.kotlin.plugin.serialization` 由 JetBrains/Kotlin 官方发布,
     与 Kotlin 编译器同源同版本;本项目用 **2.4.20**(与项目 Kotlin 严格同版本)。
   - **依赖**:`org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0`(最新 stable;1.12.0 仍 RC)。
     **实测纠正**:`navigation-common-android:2.10.1` 的 `classes.jar` 中引用
     `kotlinx/serialization/json` 的 class **数 = 0**,只有 `androidx/navigation/serialization/*`
     用到 **core** ⇒ 严格只需 core。官方 set-up 页写 `kotlinx-serialization-json` 属保守写法。
   - **编译期不能指望 navigation 自带**:它只把 `kotlinx-serialization-core:1.7.3` 放在
     **runtime** 变体;`androidApiElements-published` **编译期不含任何 serialization**。
   - **无混淆风险**:`pixfold-d1/app/build.gradle.kts` **没有 `buildTypes` 块**(当前不 minify),
     且 navigation 的 consumer `proguard.txt` 无任何 `@Serializable` 相关 keep。
     将来开 minify 时需复核一次。
   - **决定理由(成本不对称)**:今天 6 个 route 里只有 `data class Preview(val index: Int)` 带参,
     单看当下 String route 足够;但**本次迁移本来就要把这批文件 + 15 条返回相关测试全部重写**,
     此刻顺手定 type-safe 的增量 ≈ 一个插件 + 一行依赖 + 6 个 route 加 `@Serializable`;
     将来单独再换 = 把同一批文件再翻一遍。省下的不是"未来参数数量",而是**第二遍重写**。
     (注:我前一版记录倾向 String route,基于"今天只有 1 个带参 route";把路线图摊开后结论翻转 ——
     工作流 C 更新已有 CBZ `HANGOFF.md:171-180`、D5 多系列批处理 / 命名模板复用
     `HANGOFF.md:1557-1565` 都会新增带参 route。)
2. **步骤容器 = 也换嵌套 NavHost**(用户选定)。步骤 2→1 同样拿到 nav 的两层渲染。
3. **设置开关关闭语义 = "保留普通转场,只是不跟手"**(用户选定,即选项 b)。
   具体:`predictivePopEnterTransition`/`predictivePopExitTransition` 设 `None`,
   **保留**普通 `popEnterTransition`/`popExitTransition`。理由:开关名是"预测性返回**手势**动画",
   关掉它不该让页面切换变成生硬闪断。
   - (未选选项 a = `predictivePop*` 与 `pop*` 全设 `None`,瞬间硬切。)

### 4.2 探针项(写码中已全部实测收敛,结论见 §7.2)

4. **`PredictiveBackTest` 的新可断言量**:nav 的缩放发生在内部 `SeekableTransitionState` 上,
   此前"`boundsInRoot` 反映 `graphicsLayer`"的经验**可能不再适用**,需先写探针确认能断言什么,
   **不得凭猜写断言**(沿用本项目"先探针量化、再落断言"的既有教训)。
   → **已实测:仍然适用**,见 §7.2 第 1 项。
5. **`Screen.Preview(index)` 的参数传递**:改 route 后 index 走 `toRoute<Preview>()`,
   预览页的 `initialIndex` 取值方式随之变化。
   → **已落定**:`val route = entry.toRoute<PreviewRoute>()`,index 从 route 取。
6. **根级 `when` 里的共享状态**:`collections` / `library` / `activeCollectionId` / `previewItems`
   (`PixFoldApp.kt:78-86`)目前在根 composable 内 —— 迁到 NavHost 后放在 NavHost **外**还是提成 ViewModel?
   → **已落定**:`collections`/`library`/`activeCollectionId` 留在 NavHost **外**;
   `previewItems` **在 destination lambda 内**计算(见 §7.3 状态捕获)。

---

## 5. 文档连带改动(迁移完成后必须做,现在未做)

- `pixfold-d1/app/src/main/kotlin/com/pixfold/d1/PixFoldApp.kt:33-34` 的「不引入 Navigation 组件 / 零三方依赖」注释。
- `HANGOFF.md:1246`「**第 3 步(大):让目标页显示在背后**」—— 该步**已被本迁移取代**
  (不再是"手写两层渲染",而是"换成 NavHost,两层由导航库提供")。
- `HANGOFF.md:1059`「后续三步 …… 第 1、2 步已完成,剩第 3 步」的标题。
- `AGENTS.md:4` 头部快照的「换机续接点」(分支提交数、待办项)。
- `README.md` 状态行的验证数(迁移会改测试计数)。
- `docs/superpowers/specs/2026-09-20-d1-settings-theme-design.md` §14。

---

## 6. 交接状态(写码前的快照,已被 §7 取代 —— 仅作存档)

> ⚠️ 本节记录的是**开工前**的状态,其中"`PredictiveBackContainer.kt` 已改未提交、
> 建议迁移时删除"等描述**已经过时**:该文件已随本次迁移**整体删除**,
> 最终交付物与验收结果见 **§7**。保留本节是为了存证当时的判断与工作区实况。

- **分支**:`d1/settings-impl`,HEAD `351acdf docs(d1): 回写预测性返回真机量化核对结论(含 A/B 对照)`,
  领先 `dev` **18** 提交;**未 push**(push 一律由用户处理)。
- **验证基线**:`domain:test` **224** + `app:testDebugUnitTest` **273** 全绿;
  `assembleDebug` 成功;`lintDebug` 零告警。
- **本轮另交付**:`pixfold-d1/adb.bat` 增加**存储守卫** ——
  拒绝含 `uiautomator` 且含 `/sdcard` 或 `/storage/emulated` 的调用(`exit /b 3`),
  并提示改用 `pixfold-d1/dumpui.bat`(后者把 dump 写到 `/data/local/tmp`,
  不污染用户可见存储)。起因:用户两次投诉"存储根目录被写了 xml"
  (实测残留 `1.xml`/`2.xml`/`3.xml`/`f.xml`/`ui.xml`,已全部删除)。
  **实现要点(踩过的坑,勿重蹈)**:批处理里 `echo %* | findstr ...` **不能**放进
  `if ... ( ... )` 括号块(cmd.exe 会把块内管道解析成 findstr 的参数,守卫静默失效)。
  正确写法是**扁平 goto + 一行一个 findstr**。
  **更正(2026-09-22,实测)**:本条原写"且 .bat 必须 **CRLF** 行尾"——**该断言已证伪**。
  本仓库 `core.autocrlf = input` 且**无 `.gitattributes`**,四个 `.bat`(`adb.bat`/
  `dumpui.bat`/`run-gradle.bat`/`gradlew.bat`)的 HEAD blob **全是纯 LF**;把守卫的
  LF 版与 CRLF 版各跑危险/安全两个用例,输出与退出码**逐字节一致**(危险 `exit=3`,
  安全 `exit=0`)。⇒ `goto`/标签/扁平结构在 LF 下正常工作,**LF 与 CRLF 皆可**;
  真正会坏的只有上面那条"管道放进括号块"。
- **工作区(截至本记录,如实)**:
  - `pixfold-d1/adb.bat` —— **已改未提交**。存储守卫(见上条),实现并 5 例验证通过。
  - `pixfold-d1/app/src/main/kotlin/com/pixfold/d1/ui/components/PredictiveBackContainer.kt` ——
    **已改未提交**(`+80/−13`),是**"手工做第 3 步"路线的半成品**,已被本迁移决策取代。
    `behind` 参数**没有任何调用方**(全仓库 grep 只命中它自身的注释与实现),
    故它对当前行为**是无操作**的 —— 不会让现有 273+224 测试变红。
    **处置建议**:迁移开工时随 `PredictiveBackContainer.kt` 一起删除即可,无需先单独回退。
  - `docs/notes/d1-navigation-compose-migration.md` —— 本文件(新增)。
  - 以上**均未提交**;按约定 **push 一律由用户处理**,agent 只提交。
- **被放弃路线存档(以免将来重新推导)**:上面那份 `behind` 半成品的设计要点是 ——
  给容器加第三个参数 `behind: (@Composable () -> Unit)? = null`;
  `behind == null` 维持旧行为(缩放 0.9 + 淡出 0.35、矩形不透明);
  `behind != null` 时手势中把目标页画在下层,当前页作为**不透明卡片**
  (圆角 `16.dp`、自铺 `MaterialTheme.colorScheme.background` 底色,避免与目标页混色)绕中心缩放;
  且目标页**只在 `progress > 0f` 时合成**(省一份组合,也避免语义节点重复)。
  若迁移受阻需回退到手工路线,可据此重建。

---

## 7. 实施结果(2026-09-22 写码完成)

### 7.1 验收矩阵(全绿)

| 项 | 迁移前 | 迁移后 |
| --- | --- | --- |
> ⚠️ **本表是"迁移刚写完"那一刻的快照**。随后用户推翻 §7.5 第 3 条的转场形态,
> 又做了一轮**分层转场改造**(§7.8),测试类有一次拆分与数量变动,最新计数见 §7.8。

| 项 | 迁移前 | 迁移后 |
| --- | --- | --- |
| `:app:testDebugUnitTest` | 273 | **273**(271 + 新增 2 条返回链线性断言) |
| `:domain:test` | 224 | **224** |
| `:app:assembleDebug` | OK | OK |
| `:app:lintDebug` | No issues found | **No issues found** |
| APK 大小 | 12,748,647 B | **13,049,197 B**(+300,550 B ≈ navigation + serialization) |

**计数为何仍是 273**:`PredictiveBackTest` 删掉 2 条直接渲染旧容器的用例(−2),
`BackNavigationTest` 补了 2 条反向 Tab 跳转用例(+2),净变化 0。

**依赖版本修正**:起草时写的 `navigation-compose = "2.10.1"` 会让 `lintDebug` 报
`GradleDependency` 错误(项目基线是"依赖保持最新"),已改为 **2.10.2**(当前 stable release)。

### 7.2 §4.2 探针项的实测结论(均为一手探针,不是推断)

1. **`boundsInRoot` 仍能量到 nav 的缩放 —— 成立**。
   `scaleOut` 作用在 nav 内部被 seek 的 `AnimatedContent` 转场上时,语义节点的 `boundsInRoot`
   **确实**跟着变。实测值:
   - 工作流 A(`TAG_STEP_BACK`):before `Rect.fromLTRB(4.0, 56.0, 62.0, 108.0)`
     → during `Rect.fromLTRB(23.6, 103.2, 75.8, 150.0)`;
   - 工作流 B(`TAG_PACK_PAGE`):before `Rect.fromLTRB(0.0, 108.0, 400.0, 1000.0)`
     → during `Rect.fromLTRB(20.0, 152.6, 380.0, 955.4)` —— 宽 400 → 360 = **恰好 0.90**,
     与 `PREDICTIVE_MIN_SCALE` 一致;中心不动。
   - 开关**关**时 before / during **逐字节相同**。
2. **`dispatchOnBack*` 能到达 NavHost 的 `NavHostEventHandler` —— 成立**
   (此前担心 activity 1.13.0 的 dispatcher 与 navigationevent 1.0 不桥接,实测无问题)。
3. **700 ms 默认转场与 `waitForIdle()`**:三个走 `PixFoldApp()` 的测试类均**未**出现需要
   `waitUntil` 包裹的情况(默认 `mainClock.autoAdvance = true` 会自动推进)。
   ⚠️ 若将来加 `mainClock.autoAdvance = false` 的测试,此处需重新评估。

### 7.3 实现要点(与起草方案的有意偏离,勿重新推导)

- **转场策略收窄为"只覆盖两条 predictive"**:
  - `enterTransition = EnterTransition.None` / `exitTransition = ExitTransition.None`
    —— 前进保持迁移前的**瞬切**(库默认是 700 ms 淡入淡出,会给每次切页凭空加动画);
    因 `popEnterTransition` 由 `enterTransition` 派生,故**不传**它,自动得 `None`。
  - `popExitTransition = { scaleOut(PREDICTIVE_MIN_SCALE) }` —— 后退**永远**有动画,
    这正是 §4.1 第 3 项选项 (b) 要的"不硬切"。
  - `predictivePopEnterTransition = { EnterTransition.None }`(目标页静止在背后 = 第 3 步诉求);
  - `predictivePopExitTransition = { if (predictiveBackAnimation) scaleOut(PREDICTIVE_MIN_SCALE) else ExitTransition.None }`
    —— 开关只控制**手势跟手**这一段。
- **`DestinationSurface` 是强制的**:nav 把目标页画在 `initialZIndex - 1f`(下层),
  缩小中的上层若不透明,会与下层文字叠成一团。全仓库除 `ImagePreview` 自带黑底外
  **没有任何根级不透明背景**,所以每个 destination 都必须套 `DestinationSurface`。
- **状态捕获**:builder lambda 被 `remember(route, startDestination, builder)` 记忆化,
  故 `settings`/`activeCollectionId`/`collections` 的读取必须发生在 **destination 内容 lambda 内**
  (委托的 `MutableState` 读取是快照跟踪的、实时的);`previewItems` 因此从根级搬进了
  `PreviewRoute` 的 destination lambda。
- **Tab 反向跳必须 `popBackStack` 而非 `navigate`**,否则返回栈非线性
  (见 `pixfold-d1/app/src/main/kotlin/com/pixfold/d1/nav/StepNavigation.kt` 的 KDoc)。
  该约束原本**无测试守护**,由变异实验发现并补测(见 §7.4)。
- **两个步骤容器的 `onBack: () -> Unit` 参数已删** —— 嵌套 NavHost 在栈深为 1 时
  `currentBackStack.size > 1` 为 false,**自动禁用**自己的返回处理器,外层接管。

### 7.4 变异验证(证明新断言非空洞)

| 变异 | 结果 |
| --- | --- |
| `PixFoldNav.kt` 的 `predictivePopExitTransition` 恒返回 `ExitTransition.None` | **2 条失败** —— 两条 `boundsInRoot` 手势跟随断言被杀死 ✅ |
| `StepNavigation.kt` 两个 `goTo…Step` 的往回分支 `popBackStack` → `navigate` | **2 条失败** —— 新增的两条返回链线性断言被杀死 ✅ |

**后者是补测的直接原因**:该变异在**补测之前是存活的**(原 6 条 `BackNavigationTest` 用例
+ 全部其它测试**全绿**),说明"Tab 反向跳转后返回链仍线性"此前无人守护 ——
原用例 5/6 都是"逐步后退"或"前进式打开",没有"先跳到步骤 3 再点 Tab 跳回步骤 1 然后按返回"。

⚠️ **踩过的坑**:变异脚本里 anchor 写错会抛 `AssertionError` 而**静默不施加变异**,
此时测试结果无效,**不可解读为"变异存活"**。施加变异后必须 `grep` 确认代码真的改了,
再解读结果。另:bash 沙箱不跨调用保留 `/tmp`,**变异与还原必须在同一次 bash 调用内**。

**分层转场改造后的补测式变异**(2026-09-22,§7.8 落地时重跑;6 组全部被杀死):

| 变异 | 杀死它的用例 |
| --- | --- |
| 根级 `scaleOut(0.90) + fadeOut()` → 去掉 `fadeOut`(只剩缩放) | `TransitionStyleTest.the page level back gesture both fades and scales` |
| 步骤级 `fadeOut()` → `ExitTransition.None`(什么都不做) | `TransitionStyleTest.the step level back gesture fades without scaling` |
| 步骤级 `fadeOut()` → `scaleOut(0.90) + fadeOut()`(退回改造前的形态) | 同上(几何维断言杀) |
| `if (predictiveBackAnimation) … else None` → 恒 `style.exitTransition()`(开关失效) | `TransitionStyleTest.with the switch off neither level transforms` |
| `StepNavigation.goToWorkflowAStep` 往回分支 `popBackStack` → `navigate` | `BackNavigationTest.a backward tab jump in workflow A keeps the back chain linear` |
| `StepNavigation.goToWorkflowBStep` 往回分支 `popBackStack` → `navigate` | `BackNavigationTest.a backward tab jump in workflow B keeps the back chain linear` |

第一组是**关键一条**:只靠"宽度变小 + 像素有变化",去掉 `fadeOut` 的变异**能存活**
(缩小本身就会改变像素)。是新增的第三条断言"手势拉满时画面必须已等于落地后的画面"
(`settledDiff ≈ 0`)把它杀死的 —— 少了 `fadeOut`,满进度时缩小中的设置页仍盖在首页上。

### 7.5 已知可接受落差(won't fix,如实登记)

- **16 dp 圆角丢失**:旧容器把缩小中的页面裁成 `RoundedCornerShape(16.dp)` 卡片,
  NavHost 的 `ExitTransition` 只有 uniform 的 `scale`(+可选 `fade`),**无法表达圆角**。
  观感落差极小(只在手势进行中的那几十毫秒可见),接受。
- **无目标页时的淡出 alpha 丢失**:旧容器有 `FADE_WHEN_NO_TARGET = 0.35f`(首页等无背后页时
  边缩边淡)。NavHost 在根 start destination 上不会发起预测性返回(由系统接管退出应用),
  该 hack 不再需要,故一并消失。
- **「虚化」只能是透明度,不是模糊**:仓库所依赖的 compose animation 1.12.1 里
  `grep renderEffect|BlurEffect` **零命中**,`ExitTransition` 没有模糊原语,
  故两级的"虚化"一律以 `fadeOut` 的 alpha 近似。用户的措辞是"虚化",落地语义是**淡出/透视**,
  如实登记以免日后被当成 bug 追。
- ~~**前进现在是瞬切**、**后退有 0.9 缩放动画**~~ —— **本条已被 §7.8 取代**:
  2026-09-22 用户看了真机录像后判定「动画有点长且生硬」,随后给出分层规格。
  前进仍瞬切(不变),但后退形态已按层级分档,见 §7.8。

### 7.6 本次交付的文件

**新增**
- `pixfold-d1/app/src/main/kotlin/com/pixfold/d1/nav/PixFoldRoutes.kt`(12 个 `@Serializable` route)
- `pixfold-d1/app/src/main/kotlin/com/pixfold/d1/nav/PixFoldNav.kt`(`PixFoldNavHost` + `DestinationSurface`)
- `pixfold-d1/app/src/main/kotlin/com/pixfold/d1/nav/StepNavigation.kt`(步骤序号派生 + 线性栈跳转)
- `docs/notes/d1-navigation-compose-migration.md`(本文件)

**改写**
- `pixfold-d1/app/src/main/kotlin/com/pixfold/d1/PixFoldApp.kt`(手写 `when (screen)` → type-safe 根 NavHost)
- `pixfold-d1/app/src/main/kotlin/com/pixfold/d1/ui/workflowa/WorkflowASteps.kt`(→ 嵌套 NavHost)
- `pixfold-d1/app/src/main/kotlin/com/pixfold/d1/ui/workflowb/WorkflowBSteps.kt`(→ 嵌套 NavHost)
- `pixfold-d1/app/src/test/kotlin/com/pixfold/d1/PredictiveBackTest.kt`(9 → 7 条)
- `pixfold-d1/app/src/test/kotlin/com/pixfold/d1/BackNavigationTest.kt`(6 → 8 条)
- `pixfold-d1/gradle/libs.versions.toml`、`pixfold-d1/app/build.gradle.kts`、`pixfold-d1/build.gradle.kts`(依赖 + serialization 插件)

**删除**
- `pixfold-d1/app/src/main/kotlin/com/pixfold/d1/ui/components/PredictiveBackContainer.kt`
  (141 行;F11:与 NavHost 共用 `OnBackPressedDispatcher` 的 LIFO 队列,不能共存)

### 7.7 尚未做

- ~~**真机核对**(须用户授权):"设置返回时能看到目标页在背后"这一原始诉求是否真的达成;
  以及 0.9 缩放的手感、前进瞬切是否自然。~~ → **已于 2026-09-22 完成并通过,见 §7.8.6**
  (转场形态当轮又被用户改成分层规格,最终配方的真机结论记在 §7.8.6)。
- §5 的文档连带改动**已全部完成**(2026-09-22):`PixFoldApp.kt` 顶部注释随改写更新;
  `HANGOFF.md:1059` / `HANGOFF.md:1246` / `AGENTS.md:4` / `README.md:5` /
  `docs/superpowers/specs/2026-09-20-d1-settings-theme-design.md` §14 均已回写。
- **提交尚未做** —— 按用户流程「测试成功后再回写文档提交」,真机核对是本轮提交的前置条件。

## 7.8 分层转场改造(2026-09-22,取代 §7.5 第 3 条)

### 7.8.1 起因(用户真机录像后的裁决)

迁移后 agent 录制了真机返回动画(`ffprobe` 逐帧量化),用户看完逐字反馈:

- (m03574)「动画有点长且生硬」;
- (m03608)「而且页面的切换动画我觉得虚化当前页面显示上个页面比较好看,侧面拖动越多当前页越透明然后显示上级页面,向中心松开拖拽就彻底切换过去」
- (m03625)「刚才说的切换适合工作流内部这种带步骤的,而大的页面切换就当前页面虚化缩小这样」

即**两条规格分属两级**:带步骤的工作流内部 = 拖得越多越透明、露出上级页;根级大页面 = 当前页虚化 + 缩小。
这**推翻了 §7.5 第 3 条**把「前进瞬切/后退 0.9 缩放」登记为"有意设计"的说法 —— 用户要的是分档。

随后 agent 就"进入动画要不要一起改"提问,用户裁决:`forward_anim` = 「**只做返回/手势,进入保持瞬切(推荐)**」
⇒ `enterTransition`/`exitTransition` 维持 `None`,本轮只改**返回 + 手势**两条路径。

配方二选一由用户拍板,选中 **A**:「步骤级 = 只淡出;根级 = 淡出 + 缩小到 0.90」
(B = 步骤级也缩到 0.96、C = 两级都只淡出、D = 根级缩到 0.70 均未选)。

### 7.8.2 最终配方

| 层级 | 手势跟手(开关开) | 普通返回 | 前进 |
| --- | --- | --- | --- |
| 工作流步骤内(`NavTransitionStyle.Step`) | `fadeOut()` | `fadeOut()` | 瞬切(`None`) |
| 根级大页面(`NavTransitionStyle.Page`) | `scaleOut(0.90) + fadeOut()` | `scaleOut(0.90) + fadeOut()` | 瞬切(`None`) |

开关关闭:`predictivePopExitTransition` → `ExitTransition.None`(手势中纹丝不动),
普通返回仍走 `popExitTransition`(不硬切)= §4.1 第 3 项的 (b) 案不变。

**为什么根级必须显式 `+ fadeOut()`**:compose animation 1.12.1 里 `scaleOut` 的产物**只有**
`TransitionData(scale = …)`(`EnterExitTransition.kt:447`),不含任何 alpha;而"虚化"只能靠 alpha。
`ExitTransition.plus` 见同文件 `:212`。这是**代码级**约束,不是风格选择 —— 漏掉 `fadeOut`
就退化成"只缩小不虚化",而**只看宽度和像素变化的断言抓不住**(见 §7.4 第一组变异)。

### 7.8.3 代码落点

- `pixfold-d1/app/src/main/kotlin/com/pixfold/d1/nav/PixFoldNav.kt`:
  新增 `enum class NavTransitionStyle { Step, Page }`(KDoc 记录两条用户规格)、
  `PixFoldNavHost` 加第 4 个必填参数 `style: NavTransitionStyle`,
  新增 `private fun NavTransitionStyle.exitTransition(): ExitTransition`;
  `popExitTransition = { style.exitTransition() }`、
  `predictivePopExitTransition = { if (predictiveBackAnimation) style.exitTransition() else ExitTransition.None }`。
- 三个调用点各加一行 `style = …`:
  - `pixfold-d1/app/src/main/kotlin/com/pixfold/d1/PixFoldApp.kt`(根图)→ `NavTransitionStyle.Page`
  - `pixfold-d1/app/src/main/kotlin/com/pixfold/d1/ui/workflowa/WorkflowASteps.kt`(嵌套图)→ `NavTransitionStyle.Step`
  - `pixfold-d1/app/src/main/kotlin/com/pixfold/d1/ui/workflowb/WorkflowBSteps.kt`(嵌套图)→ `NavTransitionStyle.Step`

### 7.8.4 测试改造:形态断言必须**同时**看几何与像素

原先 `PredictiveBackTest` 里 3 条用例用 `boundsInRoot` 断言**步骤内容变窄** —— 与新规格
"步骤级不缩放"**直接相反**,故整体删除。原 `PredictiveBackTest` **7 → 4 条**,只保留
层级语义(两种开关状态下都逐级后退)+ 取消不导航;类 KDoc 的"能/不能断言什么"同步改写。

形态断言迁入**新建**的 `pixfold-d1/app/src/test/kotlin/com/pixfold/d1/TransitionStyleTest.kt`
(`@GraphicsMode(GraphicsMode.Mode.NATIVE)`,3 条)。判别式:

| | 宽度(`boundsInRoot`) | 画面(`captureToImage`) | 满进度 vs 落地 |
| --- | --- | --- | --- |
| 步骤级 | 不变 | 必须变化 | `settledDiff ≈ 0` |
| 根级 | 必须变小 | 必须变化 | `settledDiff ≈ 0` |
| 开关关闭 | 不变 | **逐字节不变** | — |

**为什么三条都要**:`boundsInRoot` 看得到缩放但**看不到 alpha**,而步骤级恰恰只剩 alpha 这一维;
只看像素又分不清"淡出"和"淡出+缩小"。第三条("手势拉满时画面必须已等于真的退回去之后的画面")
是 §7.4 那组变异逼出来的 —— 它专杀"只剩缩放、丢了虚化"。

**祖先图层 alpha 可观测性**:生产环境里 alpha 是 `AnimatedContent` 打在内容**外层**
(不是节点自己身上),故先写一次性探针实测:祖先 `graphicsLayer { alpha = 0.5f }` 包住的红块
采到 `(255,127,127)`,无 alpha 的对照组 `(255,0,0)` ⇒ **祖先图层 alpha 确实可被 `captureToImage` 观测**。
探针用完即删(全仓库此前**无任何** `captureToImage` 调用,先例现在只有 `TransitionStyleTest`)。

实测探针值(3 条用例全绿):

```
PageStyle probe:    widthBefore=400.0 widthDuring=360.0 changedPixels=74351 settledDiff=0
StepStyle probe:    widthBefore=99.0  widthDuring=99.0  changedPixels=283299 settledDiff=598
SwitchOff root probe: widthBefore=400.0 widthDuring=400.0 changedPixels=0
```

根级 400 → 360 = **恰好 0.90**,与 `PREDICTIVE_MIN_SCALE` 一致;开关关闭**逐字节相同**。

### 7.8.5 本轮验收矩阵(全绿)

| 项 | 值 |
| --- | --- |
| `:app:testDebugUnitTest` | **273** / 0 失败 / 0 错误 / 0 跳过 |
| `:domain:test` | **224** / 0 失败 / 0 错误 / 0 跳过 |
| `:app:lintDebug` | **No issues found.** |
| `:app:assembleDebug` | OK |
| 变异 | 6 组全部被杀死(§7.4 末表) |

计数仍是 273:`PredictiveBackTest` 7 → 4(−3),新建 `TransitionStyleTest` +3,净变化 0。

### 7.8.6 真机核对(用户授权,2026-09-22 —— ✅ 通过)

**过程**:agent 在用户授权后装当前 debug 构建上真机(PJX110 / Android 16 / 1264×2780 px / 560dpi),
确认三档动画缩放均为 `1.0`(未被开发者选项缩短)后,录了根级一段(设置页 → 首页,慢速边缘返回手势,
`rec_root.mp4`,归档在 gitignored 的 `pixfold-d1/app/build/device-verify/`)。

**结论(用户逐字)**:「**实测现在这样很不错了,后续还要改就得到走查阶段了**」
⇒ 分层形态**判定达标、本轮定稿**;后续形态调整**推迟到走查阶段**,不在本轮继续迭代。

⚠️ **这条结论的性质要说清**:它是**用户真机观感判定**,不是 agent 的量化读数 ——
用户看完实际效果后即确认,agent 遂**停止**逐帧量化(已抽帧但未完成度量),
故不要把它记成"agent 量到某个数值所以合格"。离线侧的量化证据是 §7.8.4 的探针值
(根级 400→360 px = 恰 0.90、步骤级 99→99 px、开关关闭逐字节不变),
两者互补:离线证"形态确实分档且幅度正确",真机证"观感对"。

**已接受的落差**(真机确认可接受,见 §7.5):「虚化」是 alpha 淡出而非高斯模糊(该 compose 版本无模糊原语);
旧的 16 dp 圆角卡片在 `ExitTransition` 里无法表达。

### 7.8.7 本轮收尾

- 设备侧临时文件已清理(`/data/local/tmp/rec_root.mp4`、`dev1.xml`、`dev2.xml`),
  `svc power stayon` 已复位为 `false`;`/sdcard` 根目录复检**只有系统标准目录**,无调试产物。
- 用于验证断言非空洞的变异脚本(`mutate.py` / `mutate2.py`,置于 gitignored 的 `app/build/`)已删除,
  两处被测源码已 `grep` 复核**无变异残留**(`StepNavigation.kt:48-49`/`:63-64` 仍是 `popBackStack`,
  `PixFoldNav.kt:108`/`:110` 仍是 `Step -> fadeOut()` / `Page -> scaleOut(0.90) + fadeOut()`)。
- **提交尚未做** —— 待用户确认改动集后提交(只提交,不推送)。
