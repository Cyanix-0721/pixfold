# D1 新栈构建链实证(2026-09-16)

> **性质**:一次性探针工程的实测记录。探针目录 `.probe-agp/` 是**丢弃物**,不进入任何分支。
> **目的**:在写 D1 实施计划前,把"换栈后能否构建"从**文档推断**变成**实测事实**——
> HANGOFF §12.3 ⑥ 明确把"temurin-17 下完整编译 AGP 9.4 工程"列为**留给 D1 确认**的遗留项。

## 1. 结论摘要

| # | 待验证前提 | 结果 | 证据 |
| --- | --- | --- | --- |
| 1 | temurin-17 下能否完整构建 AGP 9.4 + Compose 工程 | ✅ **可以** | `:app:assembleDebug` → `BUILD SUCCESSFUL in 39s`(36 tasks) |
| 2 | 纯 Kotlin JVM 领域模块能否独立跑单测(不依赖 Android) | ✅ **可以** | `:domain:test` → `BUILD SUCCESSFUL`(2 用例) |
| 3 | UI 层断言能否**离线**跑(无真机) | ✅ **可以** | Robolectric + `createComposeRule` → 1 用例通过,~65s |
| 4 | UI 层断言能否抓住"数据对了但界面不动" | ✅ **能** | 负向对照**如期失败**(见 §4) |
| 5 | 静态检查能否零告警通过(验收清单第 20 项) | ✅ **通过** | `:app:lintDebug` → `BUILD SUCCESSFUL` |
| 6 | WSL 能否驱动 Windows 侧构建(§12.5 要求构建走 Windows) | ✅ **可以** | `cmd.exe /c gradlew.bat` 经互操作直接跑通 |

## 2. 锁定的版本组合(实测可用)

> **⚠️ 本节记录的是 2026-09-16 的组合,已于 2026-09-20 被 §8 取代**(工具链升级到当时最新 stable)。
> **保留原文作为当时的历史证据**,不作为当前版本依据 —— 当前以 §8 与 `HANGOFF.md` §12.6 为准。

| 组件 | 版本 | 备注 |
| --- | --- | --- |
| Gradle | **9.6.0** | 发行版**已在 Windows 侧缓存**(`C:\Users\Administrator\.gradle\wrapper\dists\gradle-9.6.0-bin`),无需下载 |
| AGP | **9.4.0** | 与 §12.6 版本锚点一致 |
| Kotlin | **2.2.10** | **由 AGP 9.4.0 内置**,见 §3.1——不可自行声明 KGP 版本 |
| Compose 编译器插件 | **2.2.10** | **必须与 AGP 内置 KGP 版本一致**,见 §3.2 |
| Compose BOM | **2026.02.01** | 与 §12.6 一致 |
| JDK | **temurin-17.0.20+101** | mise 管理的 Windows 侧 JDK;`JAVA_HOME` 指向它 |
| compileSdk / minSdk / targetSdk | **36 / 34 / 36** | 与 §12.6 目标值一致。**注**:本行是 §2 探针期的快照,`minSdk` 已于 2026-09-25 由 24→30→**34**,以 §9 与 §12.6 为准;`compileSdk` 亦已升到 **37** |
| Robolectric | **4.17** | 离线 UI 断言用 |
| AndroidX test ext junit | **1.3.0** | |

## 3. 三个必须避开的坑(本次实测撞到/核实)

### 3.1 AGP 9 内置 Kotlin:**不要**应用 `kotlin-android`

AGP 9.0 起 Kotlin 内置并**默认开启**([官方迁移文档](https://raw.githubusercontent.com/android/skills/392fd3bc/build/agp/agp-9-upgrade/references/android/build/migrate-to-built-in-kotlin.md))。
照抄旧教程加 `org.jetbrains.kotlin.android` 会撞:

```
Failed to apply plugin 'org.jetbrains.kotlin.android'.
> Cannot add extension with name 'kotlin', as there is an extension already registered with that name.
```

**实测撞到的变体**:在**子模块**里写 `id("org.jetbrains.kotlin.jvm") version "2.2.10"`(带版本号)会报:

```
Error resolving plugin [id: 'org.jetbrains.kotlin.jvm', version: '2.2.10']
> The request for this plugin could not be satisfied because the plugin is already on the classpath
  with an unknown version, so compatibility cannot be checked.
```

→ **解法**:根 `build.gradle.kts` 里以 `apply false` 声明版本,子模块**只写 id 不带版本**(或走版本目录)。
**逃生门**:`android.builtInKotlin=false` + `android.newDsl=false` 可临时退回,但 **AGP 10 会移除**,不可作为长期方案。

### 3.2 Compose 编译器插件仍需**单独**应用

内置 Kotlin 只取代 `kotlin-android`,**不取代** Compose 编译器插件
([Kotlin 官方迁移指南](https://kotlinlang.org/docs/compose-compiler-migration-guide.html)):
"if you're using AGP 9.0.0 or later, you no longer need the `org-jetbrains-kotlin-android` plugin
because the AGP has built-in Kotlin support" —— 但 `org.jetbrains.kotlin.plugin.compose` 照旧要加。

**版本必须 = AGP 内置的 KGP 版本**。实测 AGP 9.4.0 的 POM 声明 `kotlin-gradle-plugin:2.2.10`,
故 Compose 插件锁 **2.2.10**。版本不匹配是运行期报错,不是配置期。

### 3.3 Kotlin `Regex.split` ≠ Python `re.split`(自然排序移植陷阱)

探针第一版自然排序**静默返回原序**——因为 Kotlin `Regex("(\\d+)").split()` **丢弃捕获组**,
切不出数字段,导致每次比较都返回 0(顺序"看起来没变",极易误判为 UI 问题)。
Python 的 `re.split` 捕获组**保留**在结果里,而 `scripts/batch_pack_cbz.py` 的自然排序正是 Python 实现。

→ **正确做法**:用 `Regex("\\d+").findAll()` 手动切记号(探针第二版,已通过)。
**这条要写进 D1 计划的领域层任务**,并配一条"切分行为"单测钉死。

## 4. 负向对照:证明 UI 层断言不是空转

本项目的核心教训是"拖拽不改顺序连修 4 轮,前 3 轮都停在'测试通过'的假象上"(§9 / §12.5)。
一个**能通过**的 UI 测试不能证明它**能失败**,所以探针专门做了负向对照:

- 写一个用**普通 `var`**(而非 `mutableStateOf`)承载状态的 Composable —— 点击真的改数据,但 Compose 不重组。
  这正是"数据对了但界面不动"的缺陷类别。
- 断言"点击后界面文本应变为 count=1"。
- **结果:如期 FAIL**(`AssertionError at SilentStateNegativeControlTest.kt:43`,`failures="1"`)。

→ 证明 Robolectric + Compose 测试 API **确实能捕获该缺陷类别**,可作为"修复自证三层"的**第 2 层**离线手段。
→ 真机层(第 3 层)仍需真机,当前 `adb devices` 为空(§12.2),故真机走查项挂起。

## 5. 复现命令(WSL → Windows)

```bash
# 1) 生成 wrapper(一次性;之后用 gradlew.bat)
GB=$(ls -d /mnt/c/Users/Administrator/.gradle/wrapper/dists/gradle-9.6.0-bin/*/gradle-9.6.0/bin | head -1)
cmd.exe /c "<把 $GB/gradle.bat 转成 Windows 路径> wrapper --gradle-version 9.6.0"

# 2) 构建 / 测试(注意:必须经 cmd.exe 走 Windows 原生,不要在 WSL 里跑 gradlew)
cmd.exe /c "D:\\Programing\\Personal\\pixfold\\.probe-agp\\run-gradle.bat :domain:test        --no-daemon --console=plain"
cmd.exe /c "D:\\Programing\\Personal\\pixfold\\.probe-agp\\run-gradle.bat :app:assembleDebug --no-daemon --console=plain"
cmd.exe /c "D:\\Programing\\Personal\\pixfold\\.probe-agp\\run-gradle.bat :app:testDebugUnitTest --no-daemon --console=plain"
cmd.exe /c "D:\\Programing\\Personal\\pixfold\\.probe-agp\\run-gradle.bat :app:lintDebug  --no-daemon --console=plain"
```

**耗时基线**(首次,含依赖下载):`assembleDebug` 39s(依赖已缓存后)、Robolectric 首跑 3m32s、lint 59s。

## 6. 待确认 / 未验证

> ⚠️ **本节是 2026-09-16 换机前的时点记录,其中「真机实证」一条已被后续事实取代。**
> 保留原文是为了留下"当时确实没有设备"的取证链,不表示现在仍缺设备。
> 真机现状以 `HANGOFF.md` §9 / §9.1 与 `docs/notes/d1-ui-friction-inventory.md` 为准。

- ~~**真机实证**:当前无设备,`adb devices` 为空 → D1 验收清单中依赖真机的条目**尚未验证**(见计划文档)。~~
  → **已不成立(2026-09-19 起)**:真机 PJX110 / Android 16 已接入无线调试
  (`adb connect 192.168.43.1:4444`),D1 验收清单 20 项已**全部真机通过**;
  此后每轮交互或数据改动都按「领域单测 → UI 断言 → 真机核对」三层自证交付。
  规约见 `AGENTS.md` §7。
- **模拟器**:无 AVD 且 WHPX/AEHD 未就绪(§12.2),不作为替代路径。
- **首次依赖下载**:本次依赖多来自缓存/直连;全新机器上首次解析耗时未测(SLAYER 走透明代理,§12.5 已确认无需配代理)。

## 7. Material 3 Expressive 可用性核实(2026-09-16)

> **背景**:用户要求"UI 要符合 MD 最新设计"。这属版本敏感事实,故按调研纪律只认一手来源并**实测**核实,
> 而非沿用文档印象。结论直接决定 D1 的 UI 基准(HANGOFF §6.5)。

### 7.1 结论

| 问题 | 实测结论 |
| --- | --- |
| 当前"最新 MD"是什么 | **Material 3 Expressive(M3E)**——`MaterialExpressiveTheme`、`MotionScheme`、`ButtonGroup`、`SplitButton`、`ToggleButton`、`LoadingIndicator`、`FloatingToolbar`、`WideNavigationRail` 等 |
| 我们锁定的 Compose BOM 2026.02.01 提供哪个 material3 | **1.4.0** |
| 最新 **stable** material3 | **仍是 1.4.0**(此后全是 `1.5.0-alphaNN`) |
| 最新 Compose BOM 2026.09.00 呢 | **也仍锁 material3 1.4.0** |
| stable 1.4.0 里 M3E 能用吗 | **不能**——M3E 类型全部 `internal`,应用层调用会编译失败 |
| 哪个版本能用 | **只有 `material3:1.5.0-alpha28`**(alpha) |
| 有无独立 `material3-expressive` 构件 | **无**(仓库 404) |

### 7.2 关键证据(可复现)

**① stable 1.4.0 含 M3E 类文件,但为 internal**：

```bash
# 下载 material3 1.4.0 并列出相关类
curl -s -o m3.aar https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3-android/1.4.0/material3-android-1.4.0.aar
unzip -o -q m3.aar classes.jar
unzip -l classes.jar | grep -iE 'Expressive|MotionScheme'
# -> 出现 ExperimentalMaterial3ExpressiveApi / MaterialThemeKt$MaterialExpressiveTheme$1 /
#    MotionScheme / MotionScheme$ExpressiveMotionSchemeImpl / ExpressiveMotionTokens 等
```

**② 但编译直接失败**(在真实工程里写 `MaterialExpressiveTheme` + `MotionScheme.expressive()`)：

```
e: Cannot access 'annotation class ExperimentalMaterial3ExpressiveApi': it is internal in file.
e: Cannot access 'fun MaterialExpressiveTheme(...)': it is internal in file.
e: Cannot access 'interface MotionScheme': it is internal in file.
e: Cannot access 'fun expressive(): MotionScheme': it is internal in 'MotionScheme.Companion'.
```

**③ 同一份代码在 1.5.0-alpha28 上编译通过**(`BUILD SUCCESSFUL`),且可见公开签名：

```
MotionScheme$Companion:
  public final MotionScheme standard();
  public final MotionScheme expressive();
```

**④ stable 1.4.0 下确实可用的 M3 能力**(探针编译通过,即本阶段实现手段)：

`lightColorScheme()` / `darkColorScheme()`(静态配色)、`dynamicLightColorScheme(ctx)` /
`dynamicDarkColorScheme(ctx)`(**动态取色,Android 12+**)、`MaterialTheme.colorScheme` /
`MaterialTheme.typography` / `MaterialTheme.shapes`。

### 7.3 影响与决定

- **决定(用户 2026-09-16)**:本阶段用 **stable `1.4.0`** 按 HANGOFF §6.5 的 6 条做实 M3 合规;
  **M3E 留到 P7 真机走查后评估**。若届时确需 M3E 观感,再单独决策是否引入 alpha 依赖——
  那将是"零三方依赖"(规格 §2 约束 3)的**显式例外**,须记录并锁版本。
- **反面教训**:M3E 类文件**存在于** stable 构件里,单看 `unzip -l` 会误判"stable 已支持";
  必须**编译**才能确认可见性。这与本项目"断言必须实证"的一贯要求一致。

## 8. 工具链升级到最新 stable(2026-09-20)

> **动因**:用户要求"尽量都用新的稳定版本"(考虑后期维护),并引入 AboutLibraries 做开源许可页。
> AboutLibraries `15.2.0` 声明 `kotlin-stdlib 2.4.10`,而当时项目 Kotlin 为 2.2.10
> (编译器最多读 metadata 2.3),**直接编译失败**:
>
> ```
> e: Module was compiled with an incompatible version of Kotlin.
>    The binary version of its metadata is 2.4.0, expected version is 2.2.0.
>    The actual metadata version is 2.4.0, but the compiler version 2.2.0 can read versions up to 2.3.0.
> ```
>
> 即**不是"要不要用三方库"的问题,而是"它的最低 Kotlin 要求超过了我们的工具链"**。

### 8.1 升级前后对照

| 组件 | 升级前(2026-09-16) | **升级后** | 说明 |
| --- | --- | --- | --- |
| Gradle | 9.6.0 | **9.7.1** | lint 报出过期后一并升 |
| AGP | 9.4.0 | **9.4.1** | 补丁级 |
| Kotlin | 2.2.10 | **2.4.20** | 由 AGP 9.4.1 内置(见 §3.1) |
| Compose BOM | 2026.02.01 | **2026.09.00** | — |
| activity-compose | 1.12.4 | **1.13.0** | lint 报出过期后一并升 |
| compileSdk | 36 | **37** | 新依赖链要求(见 8.3) |
| targetSdk | 36 | **36(不变)** | 刻意锁定,与真机 Android 16 对齐 |
| minSdk | 24 | **30** | **2026-09-22 用户裁决**(见 §9);此前 24 是脚手架期的随手取值,新栈未用 `java.time` 故 desugaring 理由从未真正成立 |
| minSdk(再升) | 30 | **34** | **2026-09-25 用户裁决**(见 §9.8);33 与 34 的清理成本相同(都删同 3 处版本守卫),取 34 额外拿到 HDR gainmap |
| Robolectric | 4.17 | 4.17(已最新) | — |
| AboutLibraries | — | **15.2.0** | 新增(core + compose-m3 + 插件) |

### 8.2 验证证据(探针分支 → 正式升级,均实测)

**先在临时分支 `probe/toolchain-upgrade` 验证**(验证后**已删除**,`dev` 未受影响),
确认组合成立再正式改 —— 避免"改坏主分支再回滚"。

| 验证项 | 结果 |
| --- | --- |
| 主代码 + 测试代码编译 | ✅ |
| **既有 461 项测试**(domain 219 + app UI 241) | ✅ **0 失败** |
| `assembleDebug` | ✅ |
| `lintDebug` | ✅ **No issues found** |

**关键**:先证明"**升级本身没有破坏任何既有行为**"(461 项全绿),
后续功能测试若失败才能确定是新代码的问题 —— 否则归因会变难。

### 8.3 撞到并解决的坑:compileSdk 必须升到 37

引入 AboutLibraries 后构建报错:

```
Dependency 'com.mikepenz:aboutlibraries-compose-m3-android:15.2.0' requires
libraries and applications that depend on it to compile against version 37
or later of the Android APIs. :app is currently compiled against android-36.
```

传导链:AboutLibraries 15.2.0 → Compose 1.12.1 → 要求 `compileSdk ≥ 37`。

**`compileSdk` 与 `targetSdk` 必须分开看**(此处容易搞混):

| 项 | 是否改 | 理由 |
| --- | --- | --- |
| `compileSdk` | **36 → 37** | 只决定"用哪个 SDK 编译",不改变运行时行为 |
| `targetSdk` | **保持 36** | 决定运行时行为契约(如 targetSdk 36 起强制 edge-to-edge),§12.6 明确与真机 Android 16 对齐 |

`platforms;android-37.0` 本机**已装**,无需额外下载。

### 8.4 伴随的 lint 收紧(从"关 4 项"到"只关 1 项")

升级前 `lint` 因"刻意锁定旧版本"关闭了 4 项版本检查
(`NewerVersionAvailable` / `GradleDependency` / `AndroidGradlePluginVersion` / `OldTargetApi`)。
升级后**重新启用前 3 项**并真实通过:

> **这 3 项检测确实在工作**(不是被忽略):启用后它立刻报出
> `Gradle 9.6.0 → 9.7.1` 与 `activity-compose 1.12.4 → 1.13.0` 两项过期,
> 升级后归零。**若无这次重新启用,这两个过期项不会被发现。**

仅保留 `OldTargetApi` 关闭(targetSdk 36 是刻意策略)。
约束 `warningsAsErrors = true` 要求豁免清单尽可能小,每次升级工具链后应重新评估。

### 8.5 附带确认:material3 仍是 1.4.0

最新 Compose BOM `2026.09.00` 锁定的 material3 **仍是 1.4.0**
(实测其 BOM pom)。故 **§7 关于 M3E 不可用的结论不变** ——
升级 BOM 不会带来 M3E,stable 仍是唯一可用规范。

### 8.6 一条操作教训

升级 Gradle wrapper 时,我在 WSL 侧用 `curl` 下载分发包**连续 TLS 中断**,
一度误判"网络不通"。实际上 **Gradle 跑在 Windows 侧,其网络路径与 WSL 不同** ——
Windows 侧的 wrapper **一直在正常下载**(缓存目录里已有 108MB 的 `.part`)。

> **教训**:排查 Gradle/Android 的网络问题,要在**实际执行构建的那一侧**验证,
> 不要用 WSL 侧的连通性推断 Windows 侧。本次还因此在缓存里留下 `.lck` 残留,
> 导致后续报 `Timeout ... waiting for exclusive access to file`,清理后才恢复。

## 9. minSdk 由 24 提到 30(2026-09-22)

> **⚠️ 本节 9.1–9.7 记录的是 2026-09-22 的裁决与当时的实测。**
> **2026-09-25 用户再次裁决把 `minSdk` 由 30 提到 `34`**(提交 `f3cfb08`),
> 本节内所有「当前 minSdk = 30」的结论**均已过期**,以 **§9.8** 为准。
> 保留原文作为当时的取证记录。

### 9.1 起因与裁决

用户提问(逐字):**「为什么要做API24/25回退，我的安卓手机最低安卓16，没必要低版本适配」**,
随后追问「minTarget写太低了?」并定调
「根据功能思考下，是否还会有什么会受到minSDK影响，没必要做太低版本兼容，不过也可以稍微放低一些，
至少30以下完全没必要，33/34/35还是升级JDK用36」。

**用户最终裁决:`minSdk = 30` + 保持 Java 17。**

原先的 24 来自脚手架提交,当时的理由是"归档用 `java.time` 需 core library desugaring,
改用 epoch millis 即可避开"——**该理由在新栈里从未真正成立**(全仓库无 `java.time` 使用),
24 更像模板默认值的延续。产品本身**不上架、自用为主**(HANGOFF §9),
且唯一真机是 Android 16,低版本适配没有收益。

### 9.2 功能面:minSdk 到底卡什么(实测,非推断)

查 `platforms/android-36/data/api-versions.xml` 的**类级** `since`(该文件只有类级数据可靠):

| API | since | 本项目用途 | minSdk=30 是否到手 |
| --- | --- | --- | --- |
| `java/nio/file/Files` | **26** | D2a 归档 | ✅ |
| `android/graphics/ImageDecoder` | **28** | D3/D4 图片解码 | ✅ |
| `android/app/NotificationChannel` | **26** | D4 进度通知 | ✅ |
| `android/app/LocaleManager` | **33** | 应用内语言 | ❌ —— 但**已有 `Build.VERSION` 分支**(`PixFoldApp.kt:192`);**2026-09-25 后 minSdk=34 ⇒ ✅ 且分支已删**(见 §9.8) |
| `android/provider/DocumentsContract` | 19 | SAF | ✅(任意版本) |
| `java/util/zip/ZipOutputStream` / `ZipFile` / `ZipEntry` | **1** | CBZ 打包 | ✅(任意版本) |

⇒ **minSdk=30 已覆盖 roadmap 所需全部 API**;CBZ 打包与 SAF 在任意 minSdk 都可行。
(**2026-09-25 更新**:minSdk 已提到 **34**,本表 `LocaleManager` 一行的 ❌ 随之变为 ✅ 且版本分支已删 —— 见 §9.8。)
主源码只有 2 处 `Build.VERSION` 分支(上表 `Theme.kt:48` / `PixFoldApp.kt:192`),
无 `@RequiresApi` / `@TargetApi`,Manifest 不申请任何权限。

### 9.3 依赖面:无阻挡

从各 AAR 的 manifest 实测最低要求:
activity-compose 1.13.0 → **23**;material3 / material3-ripple / navigation-compose 2.10.2 → **24**;
material-color-utilities → 21;aboutlibraries-compose / -compose-m3 → 21。
⇒ **无任何依赖阻止 minSdk=30**。

### 9.4 lint 代价(逐档实测)

本项目 `warningsAsErrors = true` + `abortOnError = true`,故每条 lint 建议都是**阻断错误**:

| minSdk | 新增 lint 错误 |
| --- | --- |
| ≤ 25 | 0 |
| **26 … 30** | **1 条**:`mipmap-anydpi-v26/` — `This folder configuration (v26) is unnecessary; minSdkVersion is 30.`(`ObsoleteSdkInt`/`UnnecessaryQualifier`) |
| ≥ 31 | + `Theme.kt:48`(`Build.VERSION.SDK_INT >= Build.VERSION_CODES.S`) |
| ≥ 33 | + `AndroidManifest.xml:34`(`tools:targetApi="33"`)+ `PixFoldApp.kt:192`(`SDK_INT < TIRAMISU`) |
| ≥ 34 | 3 条 `ObsoleteSdkInt` |

**minSdk=30 只需把三层 `<adaptive-icon>` 从 `mipmap-anydpi-v26/` 合并进 `mipmap-anydpi/`
即可清零**,同时可删掉原先给 API 24/25 兜底的 48dp 普通 vector 回退档。
`AndroidManifest.xml` 的 `tools:targetApi="33"` **必须保留**(30 < 33,注解仍必要)。

### 9.5 为什么没选 minSdk=36(用户提过的方向)

用户曾说"33/34/35 还是升级 JDK 用 36"。**36 在原理上可行但代价不成比例**:

1. **Robolectric 的 SDK 36 sandbox 硬性要求 Java 21**。逐字报错:
   `java.lang.UnsupportedOperationException: Failed to create a Robolectric sandbox: Android SDK 36 requires Java 21 (have Java 17)`
   (`DefaultSdkProvider$DefaultSdk.verifySupportedSdk:174`)。
2. 换 JBR21(Studio 内置 `jbr/release` = `JAVA_VERSION="21.0.5"`)后仍会撞
   `RuntimeException: Failed to interact with raw FileDescriptor internals; perhaps JRE has changed?`
   —— 根因是 Robolectric `sandbox-4.17.jar` 的 `AndroidInterceptors$FileDescriptorInterceptor`
   用 `Class.forName("jdk.internal.access.SharedSecrets")` 反射,需补
   `--add-exports=java.base/jdk.internal.access=ALL-UNNAMED`(**这是先前"SDK 36 是硬墙"错判的真正原因**)。
3. 即使上述都解决,29 个测试文件的 `@Config(sdk = [34])` 必须全部改 `[36]`,
   否则 `PackageParserException: apk-for-local-test.ap_ ... Requires newer sdk version #36 (current version is #34)`。
4. minSdk=36 的收益仅为"删掉 3 处版本分支",而 minSdk=30 的收益
   (删掉 API 24/25 回退档)**已经到手**。

⇒ 维持 Java 17 + minSdk 30。若日后工具链整体迁到 Java 21,可再评估 36。
(**2026-09-25 更新**:minSdk 实际定为 **34** —— 上面第 3 条「29 个测试文件 `@Config(sdk=[34])` 须全改 `[36]`」
是 **minSdk=36 的代价**,与 34 无关;34 与既有 `@Config(sdk=[34])` 完全一致,**零测试改动**。见 §9.8。)

### 9.6 一条测试教训(顺带发现)

追查 SDK 35/36 下 `TransitionStyleTest` 的 8000 像素残差时,定位出**真实测试缺陷**:
第③条断言(`settledDiff < settledTolerance = 5_000`)比较的是**整屏**,而步骤页签行
`PrimaryTabRow` 在嵌套 NavHost 子树**之外**,松手返回时其选中项**合法地**从「命名」切回「排序」。
差异定位探针证明 **8153 个差异像素 100% 落在 `box=(15,0)-(265,59)`** 即 `TAG_STEP_TABS`
的 `Rect.fromLTRB(0.0, 0.0, 400.0, 60.0)` 带内;裁掉页签带后内容区差 **0**。
修法 = 用 `rule.onNodeWithTag(TAG_STEP_TABS).fetchSemanticsNode().boundsInRoot.bottom`
裁掉页签带再比内容区。**修后杀伤力反而更强**:干净跑 0、变异 M1(`fadeOut()` →
`fadeOut(targetAlpha = 0.5f)`)283041、变异 M2(`Step -> scaleOut(...)`)宽度断言先失败。

### 9.7 连带发现:minSdk≥28 时 debug APK 由 13 MB 涨到 33 MB(2026-09-22)

**这不是 bug,是 AGP 面向 minSdk≥28 的有意设计。** 用户已裁决**保持默认**。

**现象**:`minSdk = 30` 落地后 `app-debug.apk` 由迁移完成时的基线 **13,069,573 B**
变为 **33,176,414 B**。逐档扫描(`:app:assembleDebug`,用 `zipfile` 统计 dex 的 `compress_type`):

| minSdk | APK 大小 | 19 个 dex 的打包方式 |
| --- | --- | --- |
| 26 | **13,040,830** | `STORED=0  DEFLATED=19` |
| 28 | **33,209,290** | `STORED=19 DEFLATED=0` |
| 29 | **33,192,906** | `STORED=19 DEFLATED=0` |
| 30 | **33,176,414** | `STORED=19 DEFLATED=0` |

⇒ **阈值在 26 与 28 之间**(未测 27)。`minSdk = 24/25` 另已不可能:
三层 `<adaptive-icon>` 按 §9.4 移入无版本限定符的 `mipmap-anydpi/` 后,aapt2 直接报
`error: <adaptive-icon> elements require a sdk version of at least 26.`

**根因(一手来源,AGP 自己的 DSL 源码)**:`com.android.build.api.dsl.DexPackaging` 逐字写着
「Whether to use the legacy convention of compressing all dex files in the APK.
**If null, dex files will be uncompressed when minSdk >= 28.**」
(`gradle-api-9.4.0-sources.jar` → `com/android/build/api/dsl/DexPackaging.kt`;
`DexPackagingOptions.kt` 复述同一规则:「If this property and the DSL are unset, dex files will be
uncompressed when minSdk >= 28.」)。该字段未设置时即为 `null` ⇒ 走不压缩分支。
官方参考页:[DexPackaging](https://developer.android.com/reference/tools/gradle-api/8.7/com/android/build/api/dsl/DexPackaging)。

不压缩的用意 = **ART 直接从 APK 内 mmap 加载 dex**,省掉安装期解压,安装与首次启动更快
(见 [Run embedded DEX code directly from APK](https://developer.android.google.cn/privacy-and-security/security-dex?hl=en))。

**已排除的其它解释**:不是代码膨胀(总类数 17,785,`classes.dex` 9,132 + `classes19.dex` 7,315,
其余 17 个各 2~462)、不是重复打包(跨 dex 重复描述符 **0** 个)、不是增量残留
(APK 总未压缩 33,264,637 vs 总压缩 33,110,954;`git diff` 确认 `app/build.gradle.kts` 只有
minSdk 与注释这一处改动,`build.gradle.kts`/`libs.versions.toml` 无 diff,`gradle.properties`
仅 `-Xmx2048m` + `android.useAndroidX=true`)。

**若要压回去**(实测有效):在 `android { }` 内加

```kotlin
packaging {
    dex {
        useLegacyPackaging = true
    }
}
```

实测 `assembleDebug` 回到 **13,007,710 B**(`STORED=0 DEFLATED=19`),代价是回到 Android P
之前的加载路径(首次启动略慢)。**本项目不采用** —— 自用侧载、不经 Play 分发,多出的传输量
无实际代价;且该开关**只影响 APK,AAB 不受影响**。

### 9.8 minSdk 由 30 再提到 34(2026-09-25)

**起因**:用户看到本节 `:292` 表格里 `LocaleManager`=33 那一行仍带 ❌,问
「这个应用内语言，再把minSDK升到33吧,minSDK34有什么新的功能可以用吗要不直接切34」。
**用户裁决:`minSdk = 34`**。

**为什么 33 与 34 的成本完全相同(这是选 34 的关键)**:两者都要求删掉同一批 3 处
`ObsoleteSdkInt` 守卫 —— `AndroidManifest.xml` 的 `tools:targetApi="33"`、
`PixFoldApp.kt` 的 `SDK_INT < TIRAMISU`、`Theme.kt` 的 `SDK_INT >= S`。
**33 不会少删一处,34 也不会多删一处** ⇒ 成本一样时取更高的那个。

**34 相对 33 的增量收益**:HDR gainmap —— `android/graphics/Gainmap` 类、
`Bitmap.getGainmap()/hasGainmap()/setGainmap()`(类级 `since=34`,据
`platforms/android-36/data/api-versions.xml`),与 D3/D4 的图片解码管线相关。

**连带删除(3 处)**:

| 文件 | 删除内容 |
| --- | --- |
| `AndroidManifest.xml` | `xmlns:tools="http://schemas.android.com/tools"` 声明与 `tools:targetApi="33"` 属性(34 ≥ 33,不再需要豁免) |
| `PixFoldApp.kt` | `import android.os.Build` + `if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect` |
| `Theme.kt` | `import android.os.Build`;`settings.monetEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S` → **`if (settings.monetEnabled)`** |

**⚠️ 纠正本节 9.5 的一处错判**:"29 个测试文件的 `@Config(sdk = [34])` 必须全部改 `[36]`"
是 **minSdk=36 的代价**,不能套到 34 上。minSdk=34 与测试既有的 `@Config(sdk = [34])`
**完全一致 ⇒ 零测试改动**(实测复跑 `app` **292** / `domain` **229** 全绿,无一条用例需改)。
另:9.5 提到"SDK 36 需 Java 21"的硬墙也与 34 无关 —— Robolectric 只有 34/35/36 的
`android-all-instrumented` 缓存在本机,34 在列。

**未受影响**:`compileSdk = 37`、`targetSdk = 36`、Java 目标 17 全部不动;
`docs/notes/d1-toolchain-evidence.md` §9.7 记的"minSdk≥28 时 dex 不压缩"现象**依旧成立**
(minSdk 仍在 ≥28 档)。

**APK 体积**:33,549,783 B → **33,046,763 B**(−503,020 B)。dex 压缩策略随 minSdk 档位变化所致。

**落地**:`f3cfb08`「feat(d1): minSdk 30 → 34（应用内语言 LocaleManager + HDR gainmap）」,
5 files changed, 56 insertions(+), 21 deletions(-)(含本节 §9.8 的新增)。
与之相邻的 `1c95db9`(底栏视觉规格 + 撤销危险红)刻意携带**中间态 `Theme.kt`** ——
保留 `Build.VERSION` 守卫但已含 `dangerActionButtonColors()`,使该提交**独立可过 lint**
(否则 `ObsoleteSdkInt` 在 `warningsAsErrors = true` 下硬失败,而删守卫只在 minSdk ≥ 31 时合法);
守卫的删除落在本提交。⇒ 两个提交各自独立绿(方案 C)。

---

## 10. 16 KB 页大小支持(2026-09-24 核查,结论:**已达标,无需任何配置**)

**结论先行**:本 APK **已经**满足 16 KB 页大小要求 —— 全部 4 个 `.so` 既满足 ELF 段对齐,
也满足 ZIP 内 16 KB 对齐。**没有做任何专门配置,是 AGP 自动做的**。

### 10.1 权威判定(不靠自算,用官方工具)

自算「data 偏移 `% 16384`」会得出**错误结论**(我算出 arm64-v8a = 1536,看似未对齐)——
因为自算没有计入 AGP 注入的 **alignment extra field**。权威判定必须用 SDK 自带的
`zipalign`:

```bash
# build-tools/36.0.0/zipalign.exe
zipalign -c -P 16 -v 4 app-debug.apk
```

实测输出(逐字):

```text
32522240 lib/arm64-v8a/libandroidx.graphics.path.so (OK)
32538624 lib/armeabi-v7a/libandroidx.graphics.path.so (OK)
32555008 lib/x86/libandroidx.graphics.path.so (OK)
32571392 lib/x86_64/libandroidx.graphics.path.so (OK)
Verification successful
```

`exit=0`;四条偏移 `% 16384` 实测均为 **0**。**教训:对齐判定只认 `zipalign -c -P 16`,
不要信自己按 ZIP 结构手算的 data 偏移。**

### 10.2 逐项证据

| 检查项 | 方法与读数 | 结论 |
| --- | --- | --- |
| ZIP 内 16 KB 对齐 | `zipalign -c -P 16 -v 4` → `Verification successful`,`exit=0` | ✅ |
| 共享库未压缩 | 4 条 `lib/**/*.so` 在 APK 内 `compress_type = 0`(STORED) | ✅(对齐的前提) |
| ELF 段对齐 | `readelf -lW` → 三个 `LOAD` 段的 `Align` 全为 **`0x4000`**(含末尾 `FileSiz=0x0 / MemSiz=0x10` 的 RW 段) | ✅ |
| 自建 native 代码 | 无 `jniLibs/` 目录,无 `externalNativeBuild` / `ndkVersion` / `cmake` 配置 | ✅ 无 |
| APK 内 `.so` 来源 | **唯一**来源是传递依赖 `androidx.graphics:graphics-path:1.0.1`(经 `androidx.compose.ui:ui-graphics-android:1.12.1` 引入),4 个 ABI 各一个 | — |

### 10.3 为什么不用做任何事(一手依据)

- **AGP ≥ 8.5.1 自动处理**:官方页 [Support 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes)
  在「Update the packaging of your shared libraries」一节写明 **AGP 8.5.1 或更高**会
  「align them on a 16 KB zip-aligned boundary」;官方博客亦述
  「AGP 8.5.1 or higher automatically enables 16 KB alignment by default (during packaging) for
  uncompressed shared libraries」([Transition to using 16 KB page sizes…](https://android-developers.googleblog.com/2025/07/transition-to-16-kb-page-sizes-android-apps-games-android-studio.html))。
  **本项目 AGP = 9.4.1**,远高于 8.5.1 门槛。
- 只有在 **AGP ≤ 8.5** 时才需要手工 `packagingOptions { jniLibs { useLegacyPackaging true } }`,
  本项目**不需要**,也没有写任何 `packaging` 配置(已 grep 确认)。
- 本项目 `.so` 是**预编译的 AAR 产物**(非本地 NDK 构建),其 ELF 对齐由 androidx 上游保证 ——
  实测 `0x4000` 已达标。

### 10.4 与「不上架、自用为主」的关系

16 KB 对齐是 **Google Play 的强制要求**(2025-11-01 起新应用/更新必须支持),但本项目
**不上架、自用侧载**(见 `HANGOFF.md:1719`),故该要求对分发**不适用**。
不过既然已知达标且**零成本**,如实登记为「已满足」即可 —— 未来若真上架也不会成为阻塞项。

**风险提示(唯一需留意的)**:若将来引入**新的含 native 代码的库**(尤其是自建 NDK 或
未适配 16 KB 的三方库),需重跑 `zipalign -c -P 16` 复验;AGP 会自动对齐 **ZIP 层**,
但**无法修复库自身 ELF 段未按 `0x4000` 对齐**的情况(那要库重新编译)。
