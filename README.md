# PixFold

图片整理与 CBZ 制作工作台。

> 当前状态：**设计阶段——D1 交互原型主线**（D2 Android SAF spike 已 5/5 验收；2026-09-10 技术栈重审换栈为 Kotlin + Jetpack Compose；**2026-09-16 D1 在新栈启动重建，2026-09-17 P1–P3 已交付、2026-09-18 P4 已交付、2026-09-19 P5 离线交付、真机已接入**；分支拓扑与七阶段切分见 HANGOFF §9.1；**验收进度 11/20**（第 1–10、20 项；P3、P4 已收口，**P5 的第 11–14 项完成领域+UI 两层自证、真机实证待补**），逐项状态见 HANGOFF §9）
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

> 进度(2026-09-17，**P1–P3 交付 / 真机接入**)：**真机已接入**（用户手机固定无线调试地址 `192.168.43.1:4444`，PJX110 / Android 16），依赖真机的验收项不再挂起。**P1**（AGP 9.4 / Gradle 9.6 / Compose BOM 2026.02.01 + material3 1.4.0 脚手架、领域地基、确定性 mock 数据）与 **P2**（缩略图网格/列表双视图、大图预览含自适应/双击缩放/平移/翻页）**完成**；**P3 完成**——网格与列表双视图拖拽（松手落地）、图钉入口、人工调整标记与一键重置、多级排序规则编辑器（含批次默认 / 本组独立）已交付并真机实证。**P4 完成**——命名结构编辑器（组件启用 / 顺序 / 分隔符 / 大小写 / 补零 / 扩展名策略 / 自动清洗）、命名建议表（原路径→新名）、逐项覆盖（改结构不丢失）、冲突与警告（重名 / 非法字符 / 大小写冲突 / 超长，含筛选）已交付并真机实证；根目录名默认**跟随集合**，可改为手工指定。**P5 离线交付**——工作流 B 的三步流程（漫画库分组与勾选 → 逐卷元数据（建议值带来源 / 语言默认「未设置」须人工确认 / 批量设置不覆盖逐项例外）→ ComicInfo.xml 与页码列表预览）已实现并完成**领域单测 + UI 断言**两层自证；**该阶段的真机实证本轮按用户要求跳过**，故第 11–14 项记 🟡 而非 ✅。**D1 验收清单当前 11/20 通过**（第 1–10、20 项），逐项状态与证据见 HANGOFF §9。离线自证：领域 143 项 + UI 205 项测试全绿，`lint` 零告警。**真机走查方法提醒**：`adb shell input swipe` 触发不了长按手势，拖拽须用 `adb shell input draganddrop`（详见 HANGOFF §12.5 的"假阴性陷阱"）。

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
