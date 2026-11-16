# PixFold 开发环境实况与 Android 验证（§12）

> 自 `HANGOFF.md` §12 迁出（2026-11-16），小节号 §12.x 沿用原编号以便交叉引用。产品决策见 [`docs/product/HANDBOOK.md`](../product/HANDBOOK.md)。

## 12. 开发环境实况与 Android 验证准备(2026-09-07 由 s_handoff.md 并入)

> 来源:原 `s_handoff.md`,2026-09-07 用户决定并入本文件后删除原文件。此后环境与验证准备以本节为准。

> ✅ **2026-09-10 已按换栈重写本节**：技术栈由 Flutter + Dart 改为 **Kotlin + Jetpack Compose(Android 原生)**(§8.1)，原 Flutter 相关条目(Flutter SDK 声明、`flutter doctor` 流程、Impeller/CJK 字体与 mise shim 踩坑、Flutter/Dart 版本锚点)**已全部移除**。(当时标注的"Kotlin/Gradle 工程侧尚未在本机验证"**已于 2026-09-16 复检更新**,见下条横幅。)

> ✅ **2026-09-12 起 SDK 根改由 Android Studio 管理**:弃用 `scoop android-clt`(已卸载,含 persist 回收)。SDK 根 = `C:\Users\Administrator\AppData\Local\Android\Sdk`(Studio 默认位置);`ANDROID_HOME` 与 `platform-tools` / `cmdline-tools` 的 PATH **由手工设置**(Studio 不会设它们)。

> ✅ **2026-09-16 环境复检(逐项实测,修正下列文档漂移)**:① 上文标注 ⬜ 的 `platforms;android-36` **实为已装**,cmdline-tools **已是 23.0.0**(非 19.0);② Gradle/AGP/Kotlin **并非"未跑通"**——2026-09-12 曾有一次 `BUILD SUCCESSFUL`(Studio 模板工程,详见 §12.2 末行);③ **adb/fastboot 曾被 `C:\Windows\System32` 的第三方残留遮蔽**,已清除,详见 §12.5;④ 归档分支**已推远程**(§12.7)。**结论:环境比原记录更完整,唯一需动手项(adb 遮蔽)已修复。**

### 12.1 一句话现状

Windows 开发机的**基础设施**已就位(git / VS Code / scoop / winget / mise),**Android SDK 侧已就绪**:SDK 根 = `C:\Users\Administrator\AppData\Local\Android\Sdk`(Android Studio 的默认位置,由 Studio 的 SDK Manager 管理),`ANDROID_HOME`、`JAVA_HOME`、用户 PATH 均已落盘,`adb` / `fastboot` / `sdkmanager` 裸命令可用,真机走无线调试。**Kotlin / Gradle 工程侧已具备构建能力**(2026-09-12 有一次成功构建实证;JDK 门槛见 §12.6)。PixFold 仍处设计阶段,不直接创建正式工程。

> 2026-09-16 复检补注:该机主机名为 **`SLAYER`**(走透明代理,无需配 Gradle 代理;`~/.gradle/gradle.properties` 确实不存在——与 §12.5 代理条目的机器区分一致)。

### 12.2 环境实况清单(2026-09-16 复检更新)

| 组件 | 状态 | 版本 / 位置 |
| --- | --- | --- |
| Git | ✅ | 2.55.0.windows.5(scoop) |
| VS Code | ✅ | 1.138.0(scoop apps/vscode) |
| Android Studio | ✅ | 2026.1.4.7(scoop;自带 JBR **25.0.3**,不依赖外部 Java 启动) |
| scoop | ✅ | main / extras / versions / sysinternals / nerd-fonts 桶 |
| winget | ✅ | v1.29.290 |
| mise | ✅ | 2026.9.9(Windows) |
| JDK | ✅ | temurin-17.0.20+101(mise 声明 `temurin-17`);`JAVA_HOME` 已 setx |
| Android SDK 根 | ✅ | `C:\Users\Administrator\AppData\Local\Android\Sdk`(Studio 默认位置) |
| ANDROID_HOME | ✅ | 指向上述 SDK 根;2026-09-12 手工设为**用户级**(Studio 不会设它,见机制注) |
| ANDROID_SDK_ROOT | ✅ 留空 | 官方要求:与 `ANDROID_HOME` 二者只设其一,或取值一致 |
| ANDROID_SDK_HOME | ⚠️ 非 SDK 路径变量 | Studio 自动设为同一目录;它只影响老工具创建 `.android` 用户数据的位置,**不能当 SDK 路径用** |
| 用户 PATH | ✅ | `<sdk>\platform-tools`(adb / fastboot)、`<sdk>\cmdline-tools\latest\bin`(sdkmanager) |
| SDK 组件 | ✅ | platform-tools **37.0.1**;build-tools **35.0.1 + 36.0.0**;platforms **android-36 + android-37.0**;sources **android-36 + android-37.0**;emulator **37.1.11**;system-images **android-36**(google_apis_playstore / x86_64);licenses(android-sdk-license) |
| cmdline-tools | ✅ | SDK 根内 `cmdline-tools\latest` = **23.0.0**(2026-09-16 复检;原记录 19.0 已过期) |
| platforms;android-36 | ✅ 已装 | 2026-09-16 复检确认已存在(Platform 16,rev 2),与真机 Android 16 对齐;原 ⬜ 标记作废 |
| adb / fastboot | ✅ | 均来自 SDK `platform-tools` **37.0.1**,PATH 中各仅一份;未装独立 scoop adb(防双 adb,见 §12.5) |
| Gradle / Kotlin 工程侧 | ✅ 有构建实证 | 2026-09-12 一次 `BUILD SUCCESSFUL in 4m 45s`(Studio 模板 `MyApplication`,Gradle **9.6.0** / AGP **9.4.0** / Kotlin **2.2.10**,跑在 Studio JBR 25 上);**2026-09-16 在 `temurin-17` 下完整复验通过**(探针工程:assembleDebug + 纯 JVM 单测 + Robolectric UI 测试 + lint 全绿);工程事后已删,无残留代码。**AGP 9 起 Kotlin 为内置**(不再需要单独装 kotlinc) |
| WSL | ✅ 仅作 agent 宿主 | 跑 Codex,经互操作驱动 Windows 侧构建;WSL 不装 Android SDK、不承担构建(依据见 §12.5 的 I/O 实测) |
| 模拟器加速 | ⚠️ 未就绪 | 无任何 AVD(`~/.android/avd` 不存在);`HypervisorPlatform`(WHPX)= Disabled 而 Hyper-V 已启用;`aehd` 驱动已装但服务 STOPPED(AEHD 与 Hyper-V 互斥)。**D1 走真机,暂不需处理** |
| 真机连接 | ⬜ 当前无设备 | `adb devices` 为空;配对记录仍在(`~/.android/adb_known_hosts.pb`),重开无线调试即可 |

> 机制注(2026-09-12 实测核实):**Android Studio 只把 SDK 路径记在自己的配置里——既不设 `ANDROID_HOME`,也不改 PATH**(IDE 设置项与各工程的 `local.properties` 都在 Studio 侧)。所以命令行侧的 `adb` / `fastboot` / `sdkmanager` 完全依赖上表那两条手工设置。**SDK 组件由 Studio 的 SDK Manager(或 SDK 根内的 `sdkmanager`)安装**;环境变量改动需**新开终端**才生效(旧进程是旧快照)。

> 历史(2026-09-12 之前,已弃用):曾用 `scoop android-clt` 的包目录当 SDK 根——它的 manifest 会 `env_set ANDROID_HOME` 到包目录、并把 `cmdline-tools/latest/bin`、`platform-tools` 写进 PATH,装完即全局可用。但它与"Studio 管理 SDK"并存会产生**两份 SDK 根**,且 Studio 会把 junction 解析成带版本号的实体路径(`...\android-clt\15859902`),`current` 的"跟随最新版"特性对 Studio 无效 → 2026-09-12 卸载并回收。

> 项目级配置(2026-09-12 更新):仓库根 `.mise.toml` **保持纯配置无注释**,说明统一在本注维护。当前内容仅 `java = "temurin-17"`(Android/Gradle 构建所需)。**声明 ≠ 已安装**:新机器上需 `mise install` 才按声明下载。**边界约定**(用户级,勿破坏):Python 由 uv 管理、Node 由 fnm 管理,mise 均不接管;全局工具声明不属本仓库管辖范围。工具版本请求变更请同步维护本节(§12.2)。

> **JDK 归属(2026-09-16 复检)**:Gradle **不读** `.mise.toml`——mise 只负责"把 JDK 拉下来",构建期用的是 `JAVA_HOME`(现指向 mise 的 temurin-17)或 Studio 内置 JBR。本机存在**两个可用 JDK**:mise temurin-17.0.20+101 与 Studio JBR 25.0.3(**是完整 JDK**,含 `javac` / `jlink` / `jmod` / `jar`,仅缺 `jpackage`)。Gradle 9.6.0 在两者下均实测可启动;AGP 9.x 官方兼容表要求 **JDK 最低 17**(默认 17),故两者都满足。**取舍**:Studio 的 JBR 路径随 IDE 升级/重装漂移(`current` 是 junction,实体目录带版本号),不宜当系统 JDK;`.mise.toml` 的声明提供与 IDE 解耦的稳定构建 JDK,**建议保留**。若确要去掉 mise,须同步把 `JAVA_HOME` 改指 JBR,否则命令行 `gradlew` 会因 `JAVA_HOME` 悬空而失败。

### 12.3 Android 平台验证准备(设计阶段优先)

> **进度(2026-09-16 复检)**:SDK 侧已由 Android Studio 装齐基础组件(见 §12.2),**原记录的待补项(`platforms;android-36`、cmdline-tools 升级)均已完成**。下面 ①–⑤ 是**新机器**上的等价步骤。⑥ 的 Kotlin/Gradle 侧**已于 2026-09-16 在 `temurin-17` 下完整实证**(见 ⑥ 与 `docs/notes/d1-toolchain-evidence.md`)。

```powershell
# ① JDK 17(sdkmanager 是 Java 程序,必须先有它)
mise install

# ② Android Studio(SDK 由它自己的 SDK Manager 安装,默认位置 %LOCALAPPDATA%\Android\Sdk)
scoop install android-studio
#   首次启动完成 Setup Wizard,勾选 Android SDK Platform-Tools 等组件

# ③ 命令行环境变量(Studio 不会设,必须手工设;用户级,新终端才生效)
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
[Environment]::SetEnvironmentVariable("ANDROID_HOME", $sdk, "User")
$entries = @((Join-Path $sdk "platform-tools"), (Join-Path $sdk "cmdline-tools\latest\bin"))
$p = @([Environment]::GetEnvironmentVariable("Path", "User") -split ';' | Where-Object { $_ })
foreach ($e in $entries) { if ($p -notcontains $e) { $p += $e } }
[Environment]::SetEnvironmentVariable("Path", ($p -join ';'), "User")

# ④ 平台组件(新终端;目标 compileSdk 36)
sdkmanager "platforms;android-36" "build-tools;36.0.0"

# ⑤ 体检(验收标准)
sdkmanager --list        # 组件齐全
adb devices              # 能看到手机
# 注意:裸 `adb` 必须解析到 <sdk>\platform-tools\adb.exe —— 先 `Get-Command adb` 确认,
#       若指向 C:\Windows\System32\adb.exe 说明有第三方残留遮蔽,见 §12.5
```

**环境准备完成** → 插上 Android 16 真机(开发者选项 + 无线调试),验证设备与 SAF 所需基础能力。

> **⑥ Kotlin / Gradle 侧(2026-09-16 已实证,遗留项关闭)**:2026-09-12 曾有一次 `BUILD SUCCESSFUL`(Studio 模板工程,Gradle 9.6.0 / AGP 9.4.0 / Kotlin 2.2.10,跑在 Studio JBR 25 上)。**2026-09-16 用一次性探针工程在 `temurin-17` 下复验完成**:`:app:assembleDebug`、`:domain:test`(纯 Kotlin JVM)、Robolectric Compose UI 测试、`:app:lintDebug` **全部通过**,故原"temurin-17 下的完整构建待 D1 确认"**已关闭**。同批实测确认两条换栈坑:① **AGP 9 内置 Kotlin**,不可再应用 `kotlin-android`,但 Compose 编译器插件(`org.jetbrains.kotlin.plugin.compose`)仍需单独应用且**版本必须 = AGP 内置 KGP(2.2.10)**;② Kotlin `Regex.split` **丢弃捕获组**(与 Python `re.split` 不同),移植脚本的自然排序会**静默返回原序**。完整证据与复现命令见 `docs/notes/d1-toolchain-evidence.md`。**AGP 9 起 Kotlin 内置**(见 §12.5 相应条目),无需单独安装 Kotlin 工具链。

正式工程框架暂不创建。

### 12.4 每日必用命令速查

```powershell
adb devices              # 列出可用设备(含无线调试)
adb logcat               # 看运行日志
sdkmanager --list        # 看 SDK 组件可用版本(来自 <sdk>\cmdline-tools\latest\bin)
mise ls                  # 看 mise 管的工具版本
```

**项目内两条固定入口**(2026-10-21 起):

```bash
cd android && ./gate.sh          # 四道闸门(lint + app 单测 + domain 单测 + APK)
cd android && ./gate.sh :app:compileDebugKotlin   # 只编译,快速查语法
```

`gate.sh` 存在的原因是**退出码必须可信**:以前在 WSL 里写
`cmd.exe /c "run-gradle.bat …" | tail -N`,退出码来自 `tail` 而不是 gradle,
**构建失败也报成功**(实测:故意漏一个 `}` ⇒ gradle `BUILD FAILED`,但管道 RC=0)。
`gate.sh` 一律把输出重定向进 `build/gate-last.log`、直接取 gradle 的退出码、
并在失败时打出行号和日志位置。

⚠️ **不要在 `gate.sh` 外面再套 `| tail`** —— `tail` 等的是 EOF,而
`run-gradle.bat` 经 `cmd.exe` 启动后句柄不关,于是命令实际早已结束、作业却一直
报 running(同一坑已踩过两次:2026-10-21 09:33 与 09:51)。看结果用
`tail build/gate-last.log`,不要把 `tail` 塞进管道。

### 12.4b 构建性能配置(2026-10-21 实测定)

`android/gradle.properties` 原有两行,四项性能开关全未开。现配置与**每项的实测依据**:

| 配置 | 值 | 依据 |
| --- | --- | --- |
| `org.gradle.jvmargs` | `-Xmx4g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8` | 2 GB 时 Kotlin 编译频繁 full GC;四道闸门全程采样 Windows 可用内存 4046 MB → 2471 MB,**峰值增量仅 ~1.6 GB** ⇒ 6 GB 上限从未被逼近 ⇒ 定 4 GB(本机 `.wslconfig` 给 WSL 10 GB,Windows 侧只剩 ~6 GB,堆上限设高会触发交换) |
| `org.gradle.parallel` | `true` | `:app` 与 `:domain` 的 task 图仅编译依赖相关,可重叠 |
| `org.gradle.caching` | `true` | `lintDebug` 与两个 `test*` 最贵,输入未变时直接复用 |
| `org.gradle.configuration-cache` | `true` + `problems=warn` | 实测 `:app:lintDebug` 连跑:首次 `entry stored` 4s → 第二次 `entry reused` 2s |

⚠️ **build cache 让 JUnit XML 时间戳失去证明力**:命中缓存时不重写 XML。
判「本次真的跑过」看 `build/gate-last.log` 的 task 行是否为
`UP-TO-DATE`/`FROM-CACHE`,不要只看 `test-results/*.xml` 的 mtime。

⚠️ **configuration cache 的 key 含任务集**:换一组 gradle 参数(如临时加
`--tests`)会 miss 一次,属预期,不是配置失效。

### 12.5 踩坑速查

- **Gboard 中文模式下 `adb shell input text` 不落字**(2026-09-19 实测,用户点破):
  字段已聚焦(`focused="true"`)、`mInputShown=true`、键盘可见,但 `input text` **完全无效**;
  **切到英文输入法后同样的命令立刻生效**。根因是中文 IME 的 composing 态吞掉了注入事件。
  **教训:凡"输入进不去"的现象,先确认输入法语言状态,再怀疑控件缺陷** ——
  本轮曾据此误判"批量值字段不可输入",实际是输入法问题。
  (另一条相关教训:`KEYCODE_DEL` 会被 IME 吞掉故"清空字段"不可靠;`input text` 本身是 IME 无关的,
  前提是**输入法不在中文/组词状态**。)
- **`input keyevent 111`(ESC)可收起键盘**;`KEYCODE_BACK` 会**退出应用**而不是收键盘
  (本项目已用 `BackHandler` 做层级返回,但用 BACK 收键盘仍会触发返回动作 -> 页面层级改变),
  收键盘请用 ESC。
- **uiautomator dump 的坐标会随键盘/滚动变化** —— 每次点击前必须用**新 dump** 取坐标;
  且 `bounds` 的 `y1` 是**顶边不是中心**,点击要取 `(y1+y2)/2`(本轮曾因直接用顶边导致点击落空)。

- **adb 认 SDK 目录结构**(`$ANDROID_HOME\platform-tools\adb` 等),不认 PATH 上的散装 adb → platform-tools 必装。
- **同一类工具只留一份权威(adb 只留一份)**:历史上 scoop 独立 adb(37.0.1)与 SDK platform-tools 的 adb 版本漂移 → 报 `adb server version mismatch`(2026-09-07 已卸 scoop 版)。现行约定:**adb / fastboot 一律来自 SDK `platform-tools`**;只有在"要 adb 但不做 Android 开发"的机器上才装独立 adb 包,且**不要**与 SDK 那份并存。
- **第三方驱动/模拟器会把 adb 塞进系统目录,靠 PATH 顺序遮蔽 SDK 那份**(2026-09-16 实测发现并修复;比上一条更隐蔽):本机 `C:\Windows\System32\` 与 `C:\Windows\SysWOW64\` 下各有一份 **adb 33.0.0(2023-06,32 位)+ fastboot 36.0.0**,来源是历史安装的 **OnePlus USB Drivers**(`C:\Program Files (x86)\OnePlus USB Drivers\Android\`,内含 2016 年版 adb/fastboot;MuMu 模拟器也自带 adb 但不在 PATH 上)。**根因是 PATH 求值顺序**:Windows 先拼完 Machine PATH 再拼 User PATH,`C:\Windows\system32` 在 Machine 段第 3 位、`<sdk>\platform-tools` 在 User 段第 29 位 → **后者永远追不上**,裸 `adb` 实际跑的是 32 位的 `SysWOW64\adb.exe`,与 Studio 用的 SDK 版并存(实测曾出现两个 server 交替接管:先由 33.0.0 启动、kill 后由 37.0.1 启动)。**修复**:删除系统目录下这 4 个文件(`adb.exe` / `fastboot.exe` / `AdbWinApi.dll` / `AdbWinUsbApi.dll`,System32 与 SysWOW64 各一份,需管理员),**备份后删**,不卸载 OnePlus 驱动本身。**验收**:`Get-Command adb` 应指向 `<sdk>\platform-tools\adb.exe`;`adb version` 应为 37.0.1;反复 `adb devices` 时 server PID 不变(不再重启)。**教训:凡"版本对不上/行为诡异",先 `Get-Command adb -All` 看有几份、谁在 PATH 前面,不要只看 `adb version`。**
- **licenses 不点** → Gradle 构建会停在缺组件;用 `sdkmanager --licenses` 处理(不再经 flutter doctor)。
- **Android Studio 会把 junction 解析成实体路径 → SDK 根必须是不随包改名的地方**(2026-09-12 实证):把 Studio 的 SDK Location 指向 `...\android-clt\current`,存盘后变成 `...\android-clt\15859902`(实体版本目录),工程内 `local.properties` 的 `sdk.dir` 同样被写成实体路径。→ SDK 根必须是**稳定路径**(现行 = Studio 默认位置);"用 junction 跟随最新版"这套对 Studio 无效。
- **`ANDROID_SDK_HOME` 不是 SDK 路径变量**(2026-09-12 查官方"环境变量"文档核实):它只决定**老工具(Studio 4.3 及更早)把 `.android` 用户数据建在哪**;Studio 会自动把它设成 SDK 目录,于是 `<sdk>\.android\` 下出现 `avd/`、`cache/`、`debug.keystore`、`studio/`。**SDK 位置只认 `ANDROID_HOME`**(`ANDROID_SDK_ROOT` 已废弃;若两者都设,官方要求取值一致)。
- **杀软实时防护会拦 SDK 解包**(2026-09-12 实证;本机为火绒,Defender 已被接管):安装 `sources;*` 这类含上万个小文件的包时随机报 `java.nio.file.AccessDeniedException`(实测卡在 `ScreenCaptureCallbackHandler.java`;zip 本体 CRC 完好,只解出 533/16421)。→ 把 **SDK 目录、`~/.gradle`、工程目录**加入杀软信任区/排除列表(官方文档同样建议);临时关防护可确认因果。
- **Android 16 引入"次版本号"36.0 / 36.1**(2026-09-12 查一手资料):Android 16 QPR2 是首个带次版本的版本,SDK 版本由 36 → **36.1**;在 SDK 里两者是**独立 platform 包**、可并存(目录名形如 `android-36` 与 `android-36.1`,与现有 `android-37.0` 同类)。运行时用 `Build.getMinorSdkVersion(VERSION_CODES_FULL.BAKLAVA)` 查询;Gradle 侧 DSL 是 `compileSdkMinor`(AGP 9.1+);AGP 9.0 兼容表写明"最高支持 API 36.1",要打 37 需 AGP 9.4+。
- **构建与 SDK 都放 Windows 侧,不给 WSL**(2026-09-12 实测):WSL 经 `/mnt/d`(NTFS)解包 1500 个 4KB 小文件耗时 **2864ms**,同一操作在 ext4 上只要 **18ms**(159×),而 Gradle 构建全是这类小文件操作。→ 工程留在 D 盘、构建走 Windows 原生;WSL 只作 agent 宿主与代码编辑。
- **代理:按机器区分,勿混用**(2026-09-08 记 R7P21 / 2026-09-16 复检 SLAYER):**R7P21** 需 Clash 代理(127.0.0.1:7897),Gradle/Java **不读** `HTTP_PROXY` 环境变量,须写 `~/.gradle/gradle.properties` 的 `systemProp.http(s).proxyHost/Port`(已配,仅 R7P21 用户级,不随工程文件),否则 gradle wrapper 下载发行版与依赖解析会超时。**SLAYER(本机)**走透明代理**无需配置**——2026-09-16 实测 `dl.google.com` 与 `repo1.maven.org` 均 HTTP 200,且 `~/.gradle/gradle.properties` **不存在**;勿在 SLAYER 上照抄代理配置。工程内一律不写死任何代理/镜像配置。
- **AGP 9 起 Kotlin 内置,别再手工加 kotlin-android 插件**(2026-09-16 查一手资料 + **探针实测**核实,D1 重建必读):AGP 9.0 引入 built-in Kotlin 并**默认开启**,对 KGP **2.2.10** 有运行时依赖——**不再需要声明 KGP 版本,也不需要单独安装 Kotlin 工具链**(本机确实没有 `kotlinc`,KGP/Compose 编译器均由 Gradle 自动拉取)。**关键坑**:`org.jetbrains.kotlin.android`(`kotlin-android`)插件**与新 DSL 不兼容**,照抄旧教程会撞 `ClassCastException`;实测还会撞 `Cannot add extension with name 'kotlin'`。官方逃生门是 `gradle.properties` 里设 `android.newDsl=false`(+ `android.builtInKotlin=false`),但**AGP 10 会移除该选项**,不应作为长期方案。来源:[AGP 9.0 release notes](https://developer.android.com/build/releases/agp-9-0-0-release-notes)(`JDK 最低 17` / built-in Kotlin / 运行时依赖 KGP 2.2.10 均见其兼容表与 Built-in Kotlin 节)。
  > **两条配套实测结论(2026-09-16)**:① **Compose 编译器插件仍需单独应用**——内置 Kotlin 只取代 `kotlin-android`,不取代 `org.jetbrains.kotlin.plugin.compose`;其**版本必须等于 AGP 内置的 KGP 版本**(AGP 9.4.0 → **2.2.10**),不匹配是**运行期**报错。来源:[Kotlin Compose 编译器迁移指南](https://kotlinlang.org/docs/compose-compiler-migration-guide.html)。② **子模块声明 Kotlin 插件不能带版本号**——否则报 `The request for this plugin could not be satisfied because the plugin is already on the classpath with an unknown version`;正确做法是根 `build.gradle.kts` 以 `apply false` 声明版本,子模块只写 id。
- **Kotlin `Regex.split` 丢弃捕获组,与 Python `re.split` 不同(移植陷阱)**(2026-09-16 探针实测,静默失败):`Regex("(\\d+)").split(s)` **不会**把捕获的数字段放进结果(与 Python `re.split` 保留捕获组的行为相反),于是自然排序的"数字段 vs 字符段"切分全部失效、每次比较返回 0 → **排序静默返回原序**。表象是"顺序没变",极易误判为 UI 或状态通知问题。**正确做法**:用 `Regex("\\d+").findAll()` 手动扫描切记号。`scripts/batch_pack_cbz.py` 的自然排序是 Python 实现,移植时**必须**配一条"切分行为"单测钉死。
- **compileSdk 不必 ≥ 手机版本**:手机 Android 16 = API 36,装 `platforms;android-36` 恰好对齐;以后想用新 API 再追加装更高 platform(可多版本并存)。
- **Android SAF:tree URI 不能直接当 parent 传给 `DocumentsContract.createDocument`**(2026-09-08 D2 spike 实证):一加/部分国产 ROM 严格校验 parent 必须是 document URI,直接传 tree URI 会抛 `IllegalArgumentException: Invalid URI`(AOSP 行为宽松,国产 ROM 收紧)。**必须先转换**:
  ```kotlin
  val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
  val parentDocUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
  DocumentsContract.createDocument(resolver, parentDocUri, mime, name)
  ```
  读/列/改名/删直接用 document URI 无需转换;只有"在树里建子项"才需要这步。`DocumentFile.fromTreeUri(ctx, treeUri).createFile(...)` 内部用同样的 tree URI 路径,在严格 ROM 上也会同样失败,务必手动转换。
- **拖拽排序:自研,弃用三方包(交互结论,与实现无关)**(2026-09-10 用户决策,原在 Flutter 原型上验证)。三方包(`reorderable_grid_view 2.2.8`)实测 drop 不落地、拖拽视觉需大量兜底,调优成本超过自建 → **弃用;D1 原型自此零三方依赖**。三条**设计结论**(换栈后继续有效,Kotlin/Compose 侧需重新实现):
  1. **整卡可拖不必抢点击**:只要“指针移动超过 slop 才接受拖拽手势”,静止点按仍是点击(看图/按钮照常)→ 无需额外拖拽把手。
  2. ~~**不要做“悬停实时重排”**~~ —— **⚖️ 已改判(2026-10-14):改为「实时让位」**(用户逐字 m01154:「拖拽应该在拖拽后的位置挤压旁边的」)。
     **旧结论(2026-09-10 记,已被推翻,保留备查)**:"拖拽期间指针微动会反复触发重排 → 顺序来回换位,用户看到的是'拖了但没变'。正确语义 = 松手落地(拖动中仅高亮目标格,松手才提交)"。
     **改判后的口径**:旧结论混淆了「重排」与「拖动中不断提交」。**正确分工 = 界面实时变、数据只提交一次** ——
     拖动中只重排本地展示顺序(`dragItems`),`onMoveTo` 一次都不调,松手时按最终索引提交一次;
     抖动由**格矩形包含判定**消除(重排后被拖项已占住指针所在格 ⇒ 目标 == 自身 ⇒ 立即稳定),
     缝隙处判 `null` 不兜底、容器外才退化为最近格。详见 §11 第 9 条「当前有效裁决」与 §9 P3 收口记录。
     **✅ 动效(过渡)已于 2026-10-15/16 补齐**:`Modifier.animateItem()`(foundation 1.12.1 有,`javap` 实证)
     + `spring(DampingRatioNoBouncy, StiffnessHigh, visibilityThreshold = IntOffset.VisibilityThreshold)`,
     只在拖动期间挂载。另修掉一个由此暴露的真缺陷:**命中几何原先由 `onGloballyPositioned` 累积,
     会被 `animateItem` 的飞行中位移污染** ⇒ 边缘反复换位 = 自我维持抖动;改为
     `layoutSlotBounds(...)` 每次从 `gridState.layoutInfo` 现算。详见 §11 第 9 条。
  3. **统一重排入口**:`PageOrder.moveItemTo(id, targetIndex)` 是唯一重排入口,UI 不各自改顺序。
  > 原 Flutter 侧的具体坑(`StackFit.expand` 遇无界高度压成 0、`Draggable`/`LongPressDraggable` 分支)随换栈作废,不在此保留。
- **调试产物不得写入用户存储**(2026-09-17 用户指出,操作纪律):`uiautomator dump` 默认写
  `/sdcard/xxx.xml` —— **`/sdcard` 就是用户的内部存储根目录**,在文件管理器与 USB 里**直接可见**,
  等于往用户设备里丢垃圾(实测一次走查留下 **59 个** xml)。**规则**:
  1. 一切临时产物写 **`/data/local/tmp/`**(Android 标准调试临时目录,用户不可见、不被媒体扫描);
  2. 走查结束**清理**设备侧临时文件;
  3. 实测注意:`/sdcard/*.xml` 的删除若经 `cmd.exe` 传参,**中文文件名会因编码丢失而删不掉** ——
     需单独处理;`ls /sdcard/` 在部分 shell 下输出为空,判断是否存在应用 `ls /sdcard/*.xml`
     (无匹配时报 `No such file or directory`,即为干净)。
  4. **`.bat` 脚本必须纯 ASCII**:`cmd.exe` 按本地 ANSI 代码页(本机 GBK)读 `.bat`,
     UTF-8 中文注释会被解析成乱码并**当作命令执行**(实测报"不是内部或外部命令");
     说明文字写英文,或改放 `.md`。已新增 `android/dumpui.bat` 统一走 `/data/local/tmp`。
- **真机实证的四个"假阴性"陷阱**(2026-09-16 P1/P2、2026-09-17 P3 实证,方法教训):用 `adb` 自动走查 GUI 时,
  以下四种情况会**误报缺陷**,须先排除再下结论:
  1. **截图时序**:`input tap` 后立刻 `screencap`,可能读到**上一帧**(实测:点"列表"后截图仍显示网格选中,
     实际已切换)。→ 点击后 `sleep 2~3` 再截,或用 `uiautomator dump` 读**控件树**佐证。
  2. **坐标取自缩放图**:截图预览会被缩放显示,直接照抄上面的像素坐标会点空(实测:把 y=476 误读成 210,
     点了两次都不跳转)。→ **用 `uiautomator dump` 取真实 bounds 中心**,再 `input tap`。
  3. **像素法测不出单色内容的变化**:程序化缩略图是大面积纯色,2× 缩放后采样点颜色**完全相同**,
     故像素对比**无法**判定缩放是否生效。→ 缩放/手势类改动以**手动实测**或 Compose 语义断言为准。
  4. **`input swipe` 不会"先按住不动",故触发不了长按手势**(2026-09-17 P3 实证):
     `adb shell input swipe` 是**立即开始移动**的,`detectDragGesturesAfterLongPress` 需要
     先静止超过长按阈值才启动 → 用 swipe 测拖拽会**误判为"拖不动"**。
     **正确做法 = `adb shell input draganddrop <x1> <y1> <x2> <y2> <ms>`**(它会先按住再拖);
     P3 用它一次成功(顺序确实改变)。
  > **实例**:P2 曾据像素采样误判"双击未放大",实际是 `adb shell input tap` 两次调用间隔**超过双击超时(~300ms)**,
  > 根本不构成双击;用户手动实测确认双击缩放与切页复位**均正常**。**结论:自动化脚本测不了的手势,
  > 明确交给人工走查,不要把"自动化没测出"当成"功能有问题"**。
  > **研判顺序(建议)**:先怀疑**测试手段**(坐标/时序/事件类型)→ 再看 `uiautomator dump` 的**控件树**佐证 →
  > 最后才判定为产品缺陷。P3 的三次"拖不动"全部是手段问题,改对事件类型后立即通过。
- **深色模式下窗口底色不能靠 Compose 兜底**(2026-09-16 真机实证,P1):只写 `values/themes.xml` 的
  `Theme.Material.Light.NoActionBar` 时,**设备处于深色模式也不会切换窗口底色**——实测窗口背景恒为
  `(250,250,250)` 近白,而 Compose 卡片已是深色,观感割裂。**修复 = 同时提供 `values-night/themes.xml`
  (parent 用非 Light 的 `Theme.Material.NoActionBar`)**,并让 Compose 根节点用
  `Surface(color = colorScheme.background)`。**验证方式**:`adb shell cmd uimode night yes|no` 切换后
  `screencap` + 取空白区像素,应分别得到深/浅底色。**教训:XML 窗口主题与 Compose 主题是两套东西,
  前者不会随 Compose 的 `darkColorScheme` 自动变**。
- **targetSdk 36 起强制 edge-to-edge,系统栏 inset 必须应用自己消费**(2026-09-16 真机实证,P1):
  布局直接顶到顶部会被**状态栏/挖孔遮挡**(实测标题 `PixFold` 被压住一半)。**修复 = 页面消费
  `WindowInsets.safeDrawing`**(或 `Scaffold` 的 `contentWindowInsets`)。**测试注意**:Robolectric 下系统
  inset 恒为 0,只断言 `top > 0` 会因内边距而**假通过**;正确做法是**把 inset 做成可注入参数**,
  注入已知值(如 40dp)后断言偏移量(已验证:不消费 inset 时该用例失败)。
- **拖拽必须用 UI 层测试自测,不能只测数据**(2026-09-10 教训,用户反馈“能拖但不改顺序”反复两轮):GUI 交互无法靠日志/截图验证,须**模拟完整手势**(按下 → 超过 slop 移动 → 移到目标 → 抬起)并断言“拖动中顺序不变 + 松手后落在目标下标”。**换栈后须用 Compose 的测试 API 重建等价用例**——这是“修复自证三层”里 UI 层的要求(§9 D1)。
  > **2026-09-16 补充(探针实测)**:该层**可以离线跑**——`Robolectric 4.17 + createComposeRule` 在纯 JVM 单测里跑通 Compose UI 断言(首跑约 65s)。**但"能通过"不等于"能失败"**:探针专门做了负向对照(用普通 `var` 而非 `mutableStateOf` 承载状态,制造"数据变了但界面不动"),断言**如期失败** → 证明该层不是空转。新栈的 UI 断言应照此**同时保留一个负向对照用例**。
- **“数据对了但界面不动”先查状态通知链**(2026-09-10 D1 实测,最隐蔽的一个):当时根因是组合式状态对象(controller 持有子 notifier)只转发了部分通知,导致**数据重排成功、界面永不重建**(日志里 drop/enabled/index 全部正常,极易误判为拖拽组件 bug)。**教训与实现无关:凡“数据对但界面不动”,先查状态变更是否真的传播到了 UI**;换栈到 Compose 后对应的是状态提升 / `mutableStateOf` 的可见性,须在新实现里重新验证。
- **“可选回调传了但没被使用”是静默缺口**(2026-09-10 晚走查反馈 ④,原在 Flutter 原型上发现;**教训与实现无关,2026-09-13 自归档分支补回**):当时网格卡片 `ThumbCard` 收了 `onPin` 参数,却只在“已固定”时画一个静态角标,**没有任何可点入口**(列表行有自己的 `IconButton`,所以只有网格模式缺)——**类型检查与常规单测都发现不了**。修复:网格卡片右下角改为可点圆形按钮(未固定=空心图钉 / 已固定=实心,带 tooltip),并补逻辑 + UI 两项测试钉死(固定 → 换排序规则仍在原位;点空心图钉 → 变实心)。
  > **对应 D1 验收清单第 6 项**(固定位置项在重新应用排序后保持原位)。**Compose 侧同样会踩**:`Modifier.clickable`/回调参数“声明了但没接到可点区域”与 Flutter 一样不会被编译器发现。**唯一可靠的发现方式是按验收清单逐条真机核对**(或 UI 层断言“点得中”),不能只靠“组件已接收该参数”。

#### 12.5.1 真机走查的环境陷阱(2026-09-19 P7 走查实测)

> 这三条都会**伪装成应用缺陷**,踩过一次就要记牢。

1. **设备休眠 → dump 到的是通知栏/锁屏,不是你的应用**

   **现象**:走查中两次 `dumpui` 读出的是通知栏内容(快递通知等),
   `mCurrentFocus=Window{... NotificationShade}`,且点哪都不对。
   **根因**:`mWakefulness=Dozing`(**屏幕已熄灭**),并非应用崩溃或界面异常。
   **处置**:走查前先 `adb shell svc power stayon true` 保持常亮(走查后 `false` 复原);
   每次解读 dump 前先确认这两个值:

   ```
   adb shell dumpsys power  | grep mWakefulness=      # 期望 Awake
   adb shell dumpsys window | grep mCurrentFocus      # 期望 com.cyanix.pixfold/.MainActivity
   ```

   **教训**:`input keyevent 224`(唤醒)、`111`(ESC)、`4`(BACK)对
   `NotificationShade` **都无效** —— 屏幕没亮时,怎么按都"没反应",
   很容易误判成"应用死了"。

2. **dump 里没有 ≠ 界面没有**

   同一个 ⓘ 图标,在某个滚动位置**不在 dump 中**,滚动后即出现。
   语义树只含**已组合**节点,`LazyColumn`/`LazyGrid` 尤其如此。
   **处置**:找控件要**边滚边找**(滑动 → 立即 dump → 查找 → 再滑),
   不要因一次 dump 缺失就判定"入口缺失"或"功能没做"。

3. **点击要取 `clickable="true"` 节点的中心**

   承载点击的节点与 `text` 节点**可能不是同一个**,两者的 bounds 可以不同
   (实测:对话框按钮的 `text` 节点在 (1007,1736),而真正可点节点是 (1006,1736)、
   bounds 更宽;点 text 节点那次**没有任何反应**,一度以为是"按钮失效")。
   **处置**:定位交互控件时**优先匹配 `clickable="true"`**,取其中点。

### 12.6 版本锚点

> **2026-11-16 订正**：仓库根 `.mise.toml` 现为 `java = "temurin-21"`（Robolectric 4.17 的 SDK 36 sandbox 硬性要 Java 21，见 `android/run-gradle.bat` 注释）；下表 `temurin-17` 条目为 2026-09-16 的历史记录，当前构建 JDK 以 **21** 为准。

| 项 | 版本 |
| --- | --- |
| Git / VS Code / Android Studio | 2.55.0.windows.5 / 1.138.0 / 2026.1.4.7 |
| JDK | temurin-17.0.20+101(mise;Android/Gradle 构建所需);另有 Studio 内置 **JBR 25.0.3** 可用(完整 JDK) |
| Gradle / AGP / Kotlin / Compose BOM | **9.8.0 / 9.4.1 / 2.4.20 / 2026.09.00** —— **2026-09-20 主动升级到当时最新 stable**(用户决策:"尽量都用新的稳定版本",考虑后期维护)。原值 9.6.0 / 9.4.0 / 2.2.10 / 2026.02.01 来自 Studio 2026.1.4 模板。**升级动因**:引入 AboutLibraries 15.2.0 需 Kotlin ≥ 2.4.10。**升级经探针实测**(临时分支验证后弃用):编译通过 + **既有 461 项测试全绿** + assembleDebug + lint 零告警。证据见 `docs/notes/d1-toolchain-evidence.md` §8 |
| compileSdk / minSdk / targetSdk | **37** / **34** / **36** —— `compileSdk` 于 2026-09-20 由 36 升到 37 (新依赖链 AboutLibraries → Compose 1.12.1 要求;`platforms;android-37.0` 本机已装)。**`targetSdk` 仍锁 36**(与真机 Android 16 对齐,§12.6 策略不变)—— 二者分离:`compileSdk` 只决定"用哪个 SDK 编译",`targetSdk` 决定运行时行为契约。故 `OldTargetApi` 的 lint 豁免理由继续成立。**`minSdk` 于 2026-09-25 由 30 再提到 34**(见下一行) |
| minSdk 由 24 提到 **30** | **2026-09-22 用户裁决**。起因:用户问"为什么要做 API 24/25 回退,我的安卓手机最低安卓 16"。**功能面实测**(查 `android-36/data/api-versions.xml` 类级 `since`):roadmap 所需 API 在 minSdk=30 已全部到手 —— `java/nio/file/Files`=26(D2a 归档)、`android/graphics/ImageDecoder`=28(D3/D4 解码)、`android/app/NotificationChannel`=26(D4 进度通知);`LocaleManager`=33 未到手但**已有 `Build.VERSION` 分支**(`Theme.kt:48`、`PixFoldApp.kt:192`);`ZipOutputStream`/`ZipFile`/`ZipEntry` 全部 since=1、SAF `DocumentsContract`=19 ⇒ **CBZ 打包与 SAF 在任意 minSdk 都可行**。**依赖面实测**:activity-compose 1.13.0→23、material3/material3-ripple/navigation-compose 2.10.2→24、material-color-utilities→21、aboutlibraries-compose/-m3→21 ⇒ **无任何依赖阻止 minSdk=30**。**连带改动**:①`mipmap-anydpi-v26/` 必须删、三层 `<adaptive-icon>` 移入 `mipmap-anydpi/`(否则 lint 报 `UnnecessaryQualifier` 而本项目 `warningsAsErrors=true` 直接失败);②原先给 API 24/25 兜底的 48dp 普通 vector 回退档及其源码结构用例**一并删除**。**Java 工具链不动**(`JavaVersion.VERSION_17` + `:domain` 的 `jvmToolchain(17)`)。**已否决的 minSdk=36**:虽可删尽三处版本分支并与真机完全对齐,但 Robolectric 的 SDK 36 sandbox 硬性要求 **Java 21**(逐字 `Failed to create a Robolectric sandbox: Android SDK 36 requires Java 21 (have Java 17)`),且 29 个测试文件的 `@Config(sdk=[34])` 需全部改 `[36]`,代价不成比例。**连带现象(非缺陷)**:minSdk≥28 时 AGP **故意不压缩 dex**,debug APK 由 13 MB 变为 33 MB —— AGP 的 `DexPackaging` DSL 源码逐字「If null, dex files will be uncompressed when minSdk >= 28」,用意是 ART 可直接 mmap 加载、省安装期解压。逐档实测 26→13,040,830 B(全 DEFLATED)、28/29/30→33.2 MB(全 STORED);加 `packaging { dex { useLegacyPackaging = true } }` 可回到 13,007,710 B,但**用户裁决保持默认**。详见 `docs/notes/d1-toolchain-evidence.md` §9.7 |
| minSdk 由 30 提到 **34** | **2026-09-25 用户裁决**(用户问「minSDK34有什么新的功能可以用吗要不直接切34」)。**起因**:`docs/notes/d1-toolchain-evidence.md:292` 记录应用内语言依赖 `LocaleManager`/`LocaleConfig`(API 33+),minSdk=30 时需要 `Build.VERSION` 分支兜底。**33 与 34 的代码清理成本完全相同** —— 两者都要求删掉同一批 3 处 `ObsoleteSdkInt` 守卫:`AndroidManifest.xml` 的 `tools:targetApi="33"`、`PixFoldApp.kt` 的 `SDK_INT < TIRAMISU`、`Theme.kt` 的 `SDK_INT >= S`。⇒ 既然成本一样,取 34 可额外拿到 **HDR gainmap**(`android/graphics/Gainmap`、`Bitmap.getGainmap()/hasGainmap()/setGainmap()`),供 D3/D4 图片管线用。**连带删除**:`AndroidManifest.xml` 的 `xmlns:tools` 与 `tools:targetApi`、`PixFoldApp.kt` 的 `import android.os.Build` + 版本守卫、`Theme.kt` 的 `import android.os.Build` + 版本守卫(`settings.monetEnabled && Build.VERSION.SDK_INT >= S` → `if (settings.monetEnabled)`)。⚠️ **纠正一处早先的错判**:上一行里"29 个测试文件的 `@Config(sdk=[34])` 必须全改 `[36]`"是 **minSdk=36 的代价**;minSdk=34 与测试用的 `@Config(sdk=[34])` **完全一致,零测试改动**(实测 `app` 292 / `domain` 229 全绿、无一条用例需改)。**未受影响**:`compileSdk=37`/`targetSdk=36`/Java 17 全部不动。**最小真机验证**:APK 由 33,549,783 B → **33,046,763 B**(−503,020 B,dex 压缩策略随 minSdk 档位变化)。详见 `docs/notes/d1-toolchain-evidence.md` §9(以 30→34 为准) |
| **16 KB 页大小支持** | ✅ **已达标,零配置**(2026-09-24 核查)。权威判定 = SDK 自带 `zipalign -c -P 16 -v 4 app-debug.apk` → `Verification successful`、`exit=0`,4 条 `lib/**/*.so` 偏移 `% 16384` 全为 **0**;**ELF 侧** `readelf -lW` 的 3 个 `LOAD` 段 `Align` 全为 **`0x4000`**;4 条 `.so` 在 APK 内均 **STORED 未压缩**(对齐的前提)。**未做任何配置** —— AGP ≥ 8.5.1 会自动做(官方逐字「align them on a 16 KB zip-aligned boundary」),本项目 AGP **9.4.1** 远高于门槛;`gradle.properties` 与 `app/build.gradle.kts` 内**无** `packaging`/`useLegacyPackaging`/`alignment` 任何配置(已 grep 确认)。**唯一 `.so` 来源** = 传递依赖 `androidx.graphics:graphics-path:1.0.1`(经 `androidx.compose.ui:ui-graphics-android:1.12.1`),本项目**无** `jniLibs/`、**无** `externalNativeBuild`/`ndkVersion`/`cmake`。⚠️ **教训**:按 ZIP 结构自算 data 偏移会得出**错误结论**(arm64-v8a 自算 `% 16384 = 1536` 看似未对齐),因为没计入 AGP 注入的 alignment extra field ⇒ **对齐判定只认 `zipalign -c -P 16`**。Play 对该项的强制要求(2025-11-01 起)**不适用**(本项目不上架,`docs/product/HANDBOOK.md`),但零成本达标,如实登记。详见 `docs/notes/d1-toolchain-evidence.md` §10 |
| Android platform | `platforms;android-36`(**已装**,与真机 Android 16 对齐);`android-37.0` 也在(模板默认带的) |
| Android SDK 根 | `C:\Users\Administrator\AppData\Local\Android\Sdk` |
| ✅ **已完成:Gradle wrapper 9.7.1 → 9.8.0**(2026-09-27 同日落地) | **2026-09-27 用户裁决立项**(`ask_user_question` 选项「A:升级 Gradle wrapper 到 9.8.0,单独一轮处理」)。**起因**:`:app:lintDebug` 报出 `gradle-wrapper.properties:3: Error: A newer version of Gradle than 9.7.1 is available: 9.8.0 [AndroidGradlePluginVersion]` —— 该检查是 2026-09-20 工具链收紧时**刻意重新启用**的三项之一(见 `app/build.gradle.kts:52-73` 注释),上游一发新版就必然翻红。**未在本轮处置**:该 error 对当轮改动 **0 命中**(`grep -c -E 'SortAndPreviewPage\|SortPagePolishTest\|ic_check'` 报告 = 0),属纯环境噪声;用户裁决留作单独一轮。**升级前须先取的一手结论**:① **AGP 9.4.1 × Gradle 9.8.0 兼容性未获一手证据**(`web_fetch` 取 Gradle 兼容矩阵页被截断,不得按印象判定);② 已从 AGP jar 抽出的一手事实是 **AGP 只设下限、无上限** —— `com/android/build/gradle/internal/plugins/VersionCheckPlugin.class` 内字符串为 `GRADLE_MIN_VERSION` / `Minimum supported Gradle version is ` / ` Current version is ` / `9.6.0` / `VERSION_CHECK_OVERRIDE_PROPERTY` / `isGradleWrapperTaskRunning` / `gradle/wrapper/gradle-wrapper.properties`,⇒ 9.8.0 在允许区间内但仍需实测。**升级步骤**:改 `pixfold-d1/gradle/wrapper/gradle-wrapper.properties` 第 3 行 `distributionUrl` → 重跑四道闸门 → 若 lint 转绿则同时评估能否**再收紧**豁免清单(`warningsAsErrors = true` 要求清单尽可能小)。**已下载的 wrapper 发行版**:`C:\Users\Administrator\.gradle\wrapper\dists\` 现只有 `gradle-9.6.0-bin`、`gradle-9.7.1-bin`(`9.8.0` **未下载**)。**2026-09-27 完成**:改 `pixfold-d1/gradle/wrapper/gradle-wrapper.properties` 第 3 行 `distributionUrl` 为 `gradle-9.8.0-bin.zip`(旧文件留档 `pixfold-d1/artifacts/gradle-wrapper.properties.bak`),重跑四道闸门 ⇒ **全绿**:`BUILD SUCCESSFUL`、`:app:testDebugUnitTest` 42 files / 399 tests / 0 failed、`:domain:test` 20 files / 247 tests / 0/0/0、**lint 由 1 error 转 `0 errors, 0 warnings, 4 hints`**(prev 的 `AndroidGradlePluginVersion` error 消失)、APK **33,368,725 B**(与升级前逐字节同尺寸)。结论:AGP 9.4.1 与 Gradle 9.8.0 兼容,实测通过;豁免清单 `app/build.gradle.kts:52-73` 本轮**未动**。 |

### 12.7 验证工程归档(2026-09-13)

> 背景:换栈后 `pixfold-d1`(Flutter 原型)与 `pixfold-saf-spike`(SAF spike)已作废,2026-09-13 按用户指令从磁盘删除。两者**从未进入 `ready` 分支**——删除前先整树提交到**归档分支**,再删除工作区目录。

| 项 | 内容 |
| --- | --- |
| 分支 | **`archive-flutter-verify`** —— ✅ **已推远程**(2026-09-16 复检:`origin/archive-flutter-verify` = `9efef5b` 存在);⚠️ **本地无同名分支**,取用请走 `origin/archive-flutter-verify` |
| tip | `9efef5b`(`feat(verify): 验证工程入库 ready——D1 交互原型…与 SAF 可行性 spike…`) |
| 相对 `ready` | 领先 3 个提交(`ready` 为其祖先):`81d84b2`(走查反馈④) → `556107b`(§4 排序键 + 走查反馈⑤) → `9efef5b`(两工程入库) |
| 内容 | `pixfold-d1/`(Flutter D1 原型,零三方依赖 + 自研拖拽,**16 项测试**,含 `lib/domain/` 下 `comic.dart` / `models.dart` / `naming.dart` 领域层)、`pixfold-saf-spike/`(SAF spike) |

> 注:归档 tip 的测试为 **16 项**,与 §9 D1 执行注所述"14 项测试随换栈作废"不矛盾——换栈决策时是 14 项,其后为走查反馈 ④(网格图钉入口)又补了 2 项,归档保存的是补齐后的最终快照。

**归档中仍有价值的资产**(换栈后不迁移代码,只作参考):

- **`pixfold-saf-spike` 的 Kotlin SAF 实现** —— `android/app/src/main/kotlin/com/example/pixfold_saf_spike/MainActivity.kt`,含 `openTree` / `listImages` / `readBytes` / `renameDoc` / `createAndWrite` / `deleteDoc` 六方法及 tree URI→document URI 转换;**新栈平台层可直接参考**(§8.3 的方法表在正文保留)。
- **`pixfold-d1` 的领域层语义与测试意图** —— 代码本身作废,但测试覆盖的语义(拖拽落位、固定位置、危险项默认关闭等)须在新栈重建(§9 D1 执行注)。

**备份状态与取用**(2026-09-16 复检):归档分支**已在 `origin` 上**,原先"唯一副本只在本地、有丢失风险"的隐患**已消除**(§12.7 原"建议 push"一项已完成)。取用方式:`git show origin/archive-flutter-verify:<路径>`(或直接 `git show 9efef5b:<路径>`,该 commit 对象在本地已存在),需要整树则 `git worktree add <目录> origin/archive-flutter-verify`。

> **⚠️ 教训与约定变更(2026-09-16)**:上一次的丢失风险根因是**原型代码放在仓库外的 `C:\Personal\` 目录**、且不在任何分支上——目录一删就只剩事后补的归档分支。故**本次 D1 重建改为在仓库内开阶段分支开发**(见 §9.2):代码从第一天起就在版本控制里,且每阶段完成即提交。**归档分支从此只作历史参考,不再是"唯一副本"的存放处。**
> 归档中**已提取为独立笔记**的部分(便于新栈直接取用,不必再读 Dart 源码):领域语义与 16 项测试意图 → `docs/notes/d1-archive-domain-semantics.md`。

