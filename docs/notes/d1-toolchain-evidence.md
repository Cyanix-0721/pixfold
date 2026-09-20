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
| compileSdk / minSdk / targetSdk | **36 / 24 / 36** | 与 §12.6 目标值一致 |
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

- **真机实证**:当前无设备,`adb devices` 为空 → D1 验收清单中依赖真机的条目**尚未验证**(见计划文档)。
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
| minSdk | 24 | 24(不变) | — |
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
