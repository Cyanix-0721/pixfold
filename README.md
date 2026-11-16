# PixFold

图片整理与 CBZ 制作工作台（Android 图形应用）。

PixFold 把「图片排序、命名结构、漫画分组、CBZ 元数据、输出策略」这些需要人工判断的步骤，做成可预览、可逐项编辑、可批量设置后单项覆盖、执行前可审阅完整计划的图形化流程；自动化只提出建议，最终结果由用户确认。

> 当前阶段 **D4a 进行中**（UI 细节打磨）。逐轮裁决、真实缺陷、闸门读数见 [`docs/INDEX.md`](docs/INDEX.md) 历轮台账；产品权威与路线见 [`docs/product/HANDBOOK.md`](docs/product/HANDBOOK.md)。

## 功能概览

- **扫描 / 决策 / 执行分离**：扫描阶段不改文件，用户确认计划后才执行；默认保留源文件，删除须独立确认并列出影响范围。
- **建议不等于事实**：自动识别的排序、名称、卷号、语言都必须可见、可改。
- **图片排序**：缩略图预览、自然排序、拖拽调整、逐项固定位置。
- **命名结构可编排**：前缀、目录片段、序号、原名、自定义文本自由组合与调整。
- **CBZ 元数据逐卷确认**：title / series / writer / volume / language / 输出名逐项编辑，生成与更新 `ComicInfo.xml`，打包 CBZ（含 Zip64）。
- **UI 符合 Material Design**：material3 语义角色（不硬编码）、深色模式、Android 12+ 动态取色、触控 ≥48dp。

## 路线（ROADMAP）

- **D1 交互原型** — ✅ 完成
- **D2 平台能力（Android SAF）** — ✅ 完成
- **D3 领域核心与打包** — ✅ 完成
- **工作流 A/B/C**（整理 / 打包 / 就地更新 CBZ 元数据）— ✅ 完成
- **D4a UI 打磨** — 进行中
- **D4b 真实 CBZ 产出** — ✅ 完成
- **发版（`dev` → `main`）** — 待 MVP 彻底实现之后

> 逐轮细节与闸门读数见 [`docs/INDEX.md`](docs/INDEX.md) 历轮台账；阶段定义与验收见 [`docs/product/HANDBOOK.md`](docs/product/HANDBOOK.md) §9。

## 架构

- **平台与栈**：**Kotlin + Jetpack Compose（Android 原生）**，material3 1.5.0-rc01（非 stable，版本锁死）。**GUI 只做 Android**；Windows / Linux 仅作手机空间不足时的命令行退路，不提供 GUI。
- **`android/`**：Android 工程（包名 `com.cyanix.pixfold`）。`domain/` 为纯 Kotlin JVM（排序 / 命名 / 打包 / 计划等领域逻辑，可 JVM 单测）；`app/` 为 Compose UI 与平台层（SAF 目录授权、缩略图、写入）。
- **`scripts/`**：两个 Python 脚本（`batch_rename_images.py`、`batch_pack_cbz.py`），作为既有行为参考、回归样例与命令行备用，**不是最终规范**（GUI 需补全交互、避免静默危险操作）。
- **`docs/`**：见文末「文档」。

## 开发环境部署（⚠️ 暂定）

> 完整清单、版本锚点与踩坑见 [`docs/notes/environment.md`](docs/notes/environment.md) §12。构建 / Android SDK / adb 都在 **Windows 构建机**；agent 宿主当前为 Windows，未来可切 WSL——那只是跑 agent 的地方，构建口径不变。

- **JDK**：mise 管理，`.mise.toml` 声明 `java = "temurin-21"`（Robolectric 的 SDK 36 sandbox 硬性要求 Java 21）；构建经 `run-gradle.bat` 的 `mise exec` 切到它，不依赖外部 `JAVA_HOME`。
- **Android Studio**：scoop 安装（自带 JBR，无需外部 Java 即可启动）。
- **Android SDK**：由 Studio 的 SDK Manager 装到默认位置；组件含 platform-tools、build-tools、platforms android-36 / 37、cmdline-tools。
- **环境变量**：`ANDROID_HOME` 指向 SDK 根；用户 PATH 加 `<sdk>\platform-tools`、`<sdk>\cmdline-tools\latest\bin`。
- **adb**：一律来自 SDK 的 platform-tools，不装独立 adb 包；验证用 `Get-Command adb -All` 确认只剩一份。

> 具体安装命令（scoop / mise / sdkmanager、`ANDROID_HOME` 与 PATH 设置）与踩坑见 [`docs/notes/environment.md`](docs/notes/environment.md) §12.2。

⚠️ 安装 `sources;*` 这类含上万个小文件的包前，先把 SDK 目录加入杀软信任区，否则可能报 `AccessDeniedException`。

## 文档

- [`docs/INDEX.md`](docs/INDEX.md)：**文档地图**，先看这里再决定读哪份。
- [`docs/product/HANDBOOK.md`](docs/product/HANDBOOK.md)：**产品决策唯一权威**——定位、工作流、领域模型、平台策略、路线（§8–§9）、验收标准。
- [`docs/notes/environment.md`](docs/notes/environment.md)：开发环境实况与 Android 验证（原 §12）。
- [`docs/notes/decisions-archive.md`](docs/notes/decisions-archive.md)：已裁决问题台账（§11 第 9–18 条）。
- [`docs/phases/`](docs/phases/)：各阶段实现留痕，只查不常读。
- [`docs/notes/`](docs/notes/)：调研与实证笔记。
- [`docs/superpowers/`](docs/superpowers/)：设计规格与实施计划（均为已完成阶段的产物，溯源时读）。
- [`scripts/`](scripts/)：Python 行为参考与回归样例。

## License

[MIT](LICENSE) © Cyanix-0721
