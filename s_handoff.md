# Handoff · PixFold 设计阶段环境交接摘要

> 用途：记录当前开发环境，并把设计阶段入口交给 [HANGOFF.md](HANGOFF.md)。
> 状态：2026-09-03 更新 · 项目定位、工作流、架构边界和技术决策门槛以 [HANGOFF.md](HANGOFF.md) 为准。

---

## 1. 一句话现状

Windows 开发机工具链已基本就位（git / VS Code / scoop / winget / mise / **Flutter 3.47.2** / **VS Build Tools 18.9** 全部 ✅），但 PixFold 目前处于设计阶段，下一步先做交互原型和 Android 平台能力验证，不直接创建正式工程。

## 2. 环境实况清单（2026-09-03 实测）

| 组件 | 状态 | 版本 / 位置 |
|---|---|---|
| Git | ✅ | 2.55.0（scoop） |
| VS Code | ✅ | 1.136（scoop apps/vscode） |
| scoop | ✅ | main / extras / versions / sysinternals / nerd-fonts 桶 |
| winget | ✅ | v1.29.290 |
| mise | ✅ | 2026.9.1（全局配置 `C:\Users\Administrator\.config\mise\config.toml`） |
| **Flutter SDK** | ✅ | **3.47.2 / Dart 3.13.2**，mise 全局管理<br>实际路径：`C:\Users\Administrator\AppData\Local\mise\installs\flutter\3.47.2` |
| **VS Build Tools** | ✅ | **18.9.12112.369**（VS 2026，v145 工具集）<br>路径：`C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools` |
| JDK | ⬜ 待确认 | 计划 `mise use -g java@temurin-17`（mise 自动配 JAVA_HOME） |
| android-clt（cmdline-tools） | ⬜ 待装 | `scoop install android-clt` → 提供 sdkmanager / avdmanager |
| adb | ⚠️ 过渡态 | 现为 scoop 独立包 37.0.1 → **收尾时卸载**，改用 SDK 内 platform-tools |
| WSL | ✅（远期用） | Debian 13 (trixie)，podman 5.4.2 ——留给 Linux 目标预览/容器构建 |

## 3. Android 平台验证准备（设计阶段优先）

```powershell
# ① JDK 17（sdkmanager 是 Java 程序，必须先有它）
mise use -g java@temurin-17
java -version

# ② Android 命令行工具
scoop install android-clt
scoop prefix android-clt          # 记下输出 → 作为 ANDROID_HOME

# ③ 设 ANDROID_HOME（新终端生效）
setx ANDROID_HOME "（②的输出路径）"

# ④ SDK 组件（Flutter 3.47.2 默认 compileSdk=36）
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"

# ⑤ 同意许可证（之后 Gradle 能自动补装缺失组件）
flutter doctor --android-licenses    # 一路 y

# ⑥ 卸独立 adb，避免双 adb 版本漂移
scoop uninstall adb

# ⑦ 体检（验收标准）
flutter doctor -v
# 期望：Flutter ✓ / Android toolchain ✓ / Visual Studio ✓
# （新终端里若想裸敲 adb，把 %ANDROID_HOME%\platform-tools 加进用户 PATH）
```

**环境准备完成** → 插上 Android 16 真机（开发者选项 + USB 调试），先只验证设备和 SAF 所需基础能力：
```powershell
flutter devices        # 能看到手机
flutter doctor -v      # 确认 Android toolchain 可用
```

正式工程框架暂不创建。先完成设计阶段的交互原型，再根据 Android SAF、缩略图、拖拽排序和后台任务验证结果决定技术栈。

## 4. 每日必用命令速查

```powershell
flutter doctor -v        # 环境体检（第一排查手段）
flutter devices          # 列出可用设备
flutter run              # 热重载开发（r 热重载 / R 全重启 / q 退出）
flutter create --platforms=windows,android app  # 技术栈确定后再创建正式工程
sdkmanager --list        # 看 SDK 组件可用版本
mise ls                  # 看 mise 管的工具版本
```

## 5. 踩坑速查

- **Visual Studio ≠ VS Code**：编译 Windows 桌面要的是 Build Tools 的 C++ 工作负载，VS Code 只是编辑器。
- **flutter doctor 认 SDK 目录结构**（`$ANDROID_HOME\platform-tools\adb` 等），不认 PATH 上的散装 adb → platform-tools 必装。
- **双 adb 会打架**：scoop adb 与 SDK platform-tools adb 版本漂移 → 报 `adb server version mismatch`，已定方案是卸 scoop 版。
- **licenses 不点** → Android toolchain 永远 ❌；`flutter doctor --android-licenses` 一路 y。
- **compileSdk 不必 ≥ 手机版本**：手机 Android 16 = API 36，装 `platforms;android-36` 恰好对齐；以后想用新 API 再追加装更高 platform（可多版本并存）。
- **mise 装 Flutter 若在 Windows 报错** → 回退 `scoop bucket add extras && scoop install flutter`（本次未遇到，mise 3.47.2 一次成功）。
- **VS Code 报找不到 Flutter** → 设置 `dart.flutterSdkPaths` 填 `mise where flutter` 的输出。

## 6. 版本锚点（本机已验证）

| 项 | 版本 |
|---|---|
| Flutter stable | 3.47.2（2026-08-26 revision d3b14c87） |
| Dart | 3.13.2（随 Flutter 捆绑） |
| compileSdk / minSdk / targetSdk | 36 / 24 / 36（源码 `flutter_tools/.../FlutterExtension.kt` 核实） |
| Android platform | `platforms;android-36`（Android 16，与真机一致） |
| VS Build Tools | 18.9.12112.369（VS 2026 / v145） |
| Material/Cupertino | 以当前 Flutter SDK / 项目模板实际生成结果为准，后续创建工程时再确认依赖 |

## 7. 接下来（设计阶段）

1. **固化需求**：从两个 Python 脚本整理功能矩阵、真实样例和异常样例，标出必须人工确认的字段。
2. **做交互原型**：优先完成图片缩略图/拖拽排序、命名结构编辑、CBZ 元数据编辑和执行计划预览，不连接真实文件系统也可以开始。
3. **验证 Android**：优先验证 SAF 选目录、持久化授权、遍历图片、读取字节、创建文件、重命名、缩略图和后台任务。
4. **再定技术栈**：比较候选方案在 Android、Windows、Linux、UI 交互、文件访问和维护成本上的结果，不预设 Flutter 或 Rust。
5. **实现领域核心**：技术方案确定后，再拆出排序、命名、元数据、ComicInfo.xml、CBZ、计划和报告模块。

> 本文件只记录环境和平台验证准备；项目目标、工作流、领域模型和技术决策门槛统一维护在 [HANGOFF.md](HANGOFF.md)。
