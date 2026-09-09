# PixFold

图片整理与 CBZ 制作工作台。

> 当前状态：**设计阶段——D1 交互原型主线**（D2 Android SAF spike 已 5/5 验收）
> 目标平台：**Windows、Android（优先）、Linux**
> 技术栈：**Flutter + Dart（2026-09-08 锁定）**；决策依据与对照方案见 HANGOFF §8

## 项目目标

PixFold 不是简单地把两个命令行脚本包进一个窗口。它要把图片排序、命名结构、漫画分组、CBZ 元数据和输出策略等需要人工判断的过程，做成一个：

- 可以预览；
- 可以逐项编辑；
- 可以批量设置后单项覆盖；
- 可以在执行前检查完整计划；
- 可以保留源文件并支持回退；
- 能在 Windows、Android、Linux 上工作的图形化应用。

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

## 设计阶段路线

```text
D0  固化需求与真实样例
 ↓
D1  完成交互原型，不连接真实文件系统
 ↓
D2  优先验证 Android SAF，再验证 Windows/Linux 文件访问
 ↓
D3  拆分可测试的领域核心与平台适配层
 ↓
D4  交付 Android / Windows / Linux 基础闭环
 ↓
D5  增加模板、撤销、更新 CBZ、多系列批处理等增强功能
```

> 进度(2026-09-08):D0 已完成;D2 的 Android SAF spike 按 HANGOFF §8.2 提前执行并 **5/5 验收**,证伪条件 ①② 排除,**Flutter + Dart 已锁定**;当前主线为 D1 交互原型(不连真实文件系统)。执行顺序与判定以 HANGOFF §8.2 / §8.3 / §9 为准。
> 进度(2026-09-09):**D1 交互原型已启动**——原型工程 `C:\Personal\pixfold-d1`(仓库外,确定性 mock 数据,Windows + Android 双端验收;唯一三方依赖 `reorderable_grid_view 2.2.8`)。D1 验收走查(HANGOFF §9 D1 验收点 + §10)通过前不宣称完成。

## 开发环境部署(⚠️ 暂定)

> 本节按已锁定的 **Flutter + Dart** 准备(2026-09-08,见 HANGOFF §8.1),本机环境已验收(HANGOFF §12);因正式工程尚未创建,部署细节仍可能随 D1/D3 微调。完整步骤、进度与踩坑见 [HANGOFF.md](HANGOFF.md) §12。

- **Flutter / Dart**:由 **mise** 管理,PixFold 在 `.mise.toml` **固定 `3.47.2`**(当前 stable 解析;升级 = 改 `.mise.toml` 版本号 → `mise install`)。Windows 命令行直接敲 `flutter`(pwsh profile 适配,git-bash/CI 用 `flutter.bat`),详见 HANGOFF §12。
- **JDK**:mise 管理,请求 `temurin-17`,当前 17.0.20+101;`JAVA_HOME` 已 setx 指向 mise 目录。
- **Android SDK**:`scoop install android-clt`(15859902),其 `current` 目录即完整 SDK 根;`ANDROID_HOME` 由该包 manifest `env_set` **自动写入**(装完新终端生效);`platforms;android-36`、`build-tools;36.0.0` 已装。
- **adb**:该包**不自带 adb**,需 `sdkmanager "platform-tools"` 装入;PATH 已由 scoop 自动含 `cmdline-tools/latest/bin` 与 `platform-tools`(裸敲 adb/sdkmanager 可用),独立 scoop adb 包已卸载(防双 adb)。
- **验收(⑦)**:pwsh 新终端直接 `flutter doctor -v`(已配 profile 适配函数:Windows 自动转调 `flutter.bat`,Linux/macOS 原生,见 HANGOFF §12;git-bash/CI 场景仍用 `flutter.bat`),期望 Flutter ✓ / Android toolchain ✓ / Visual Studio ✓。

```powershell
# ═══ PixFold 开发环境重建命令(项目相关步骤;基础工具链 scoop/mise/pwsh 视为已就绪)═══

# 1) 按项目 .mise.toml 安装版本声明(flutter 3.47.2 + java temurin-17;幂等,只补缺失)
mise install

# 2) Android SDK(android-clt manifest 自动写入 ANDROID_HOME 与 PATH)
scoop install android-clt
#   ↑ 新开终端再继续(sdkmanager 才在 PATH)

# 3) Android SDK 组件(与 Flutter 3.47.2 默认 compileSdk=36 对齐)
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"

# 4) Android licenses(首次)
flutter.bat doctor --android-licenses

# 5) VS Build Tools —— Windows 桌面构建与 doctor 的 Visual Studio ✓(体积大,可选)
winget install -e --id Microsoft.VisualStudio.BuildTools --override "--add Microsoft.VisualStudio.Workload.VCTools --includeRecommended --passive --norestart"

# 6) 验收:期望 Flutter ✓ / Android toolchain ✓ / Visual Studio ✓
flutter.bat doctor -v
```

## 文档

- [`HANGOFF.md`](HANGOFF.md)：产品定位、工作流、领域模型、平台策略、设计路线、验收标准与开发环境实况(§12)；
- [`scripts/`](scripts/)：现有 Python 行为参考和回归样例。

## License

[MIT](LICENSE) © Cyanix-0721
