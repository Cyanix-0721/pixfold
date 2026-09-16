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
