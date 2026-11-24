# 2026-11-24 跨平台实时 UI 预览画廊设计规格

> **状态**：已批复并进入实施（分支 `d4/live-ui-preview`）。
> **定位**：D4a UI 持续打磨期的开发提效工具链设计，覆盖 DSH（WSL/终端）、Antigravity、Codex、Windows PowerShell/CMD，零改动生产 APK 字节码。

---

## 1. 背景与核心诉求

在 D4a 阶段打磨 PixFold Android 原生 Jetpack Compose UI（`material3 1.5.0-rc01` Expressive）时，存在三个现实约束：

1. **多 Agent / 多宿主通用性**：日常开发同时使用终端型 agent（DSH，无 `android-cli-plugin` 侧栏 UI，仅具备 `android-cli` 技能）与 Antigravity，且运行环境横跨 **Windows PowerShell/CMD 与 WSL**。Windows 默认 `bash` 为 WSL 启动器，`.sh` 脚本在原生 Windows 终端下不可直接复用。
2. **本机资源边界**：当前主机 `SLAYER` 总物理内存 15.2 GB（`.wslconfig` 限制后 Windows 侧可用构建内存约 6 GB，见 `android/gradle.properties`），常开 Android 36 AVD 模拟器易触发换页抖动；同时仓库内尚无 `@Preview` 注解与 `debugImplementation(libs.compose.ui.tooling)` 依赖。
3. **已有资产**：`VisualRegressionSnapshotTest`（22 个测试方法 / 25 张全屏黄金图，位于 `android/app/src/test/snapshots/`）已基于 `MockData` 与 `PixFoldNativeTestHost`（Robolectric `@GraphicsMode(NATIVE)` + `zh-rCN-w361dp-h794dp-560dpi`）解耦了所有核心页面、弹窗及点击后浮层态，并经实测 100% 兼容 `material3 1.5.0-rc01`。

**核心目标**：构建一套不依赖真机、不依赖 `android-cli-plugin`、在 Windows 与 WSL 下命令完全一致的免手机实时静态 UI 预览画廊，改完 UI 后可按页面组或全站增量出图，并在浏览器常驻画廊自动无闪刷新与像素级对比改前/改后差异。

---

## 2. 非目标与反目标

- **非目标（本轮不做）**：
  - 不替代真机动态核对（60/120Hz 弹簧/波浪动效、ColorOS OEM 字体微差、壁纸动态取色、真实 SAF 与全面屏手势仍由 `pixfold-device-testing` 承担；真机 `scrcpy` 置顶 + Debug 场景直达作为互补方案留待后续独立推进）。
  - 不引入 `compose-ui-tooling` 与 `@Preview` 注解（本轮路线 1a 不依赖 Layoutlib）。
  - 不覆盖或修改 `android/app/src/test/snapshots/` 中的 25 张基线黄金图。
- **反目标**：
  - 绝不引入仅能在 Antigravity 侧栏或仅能在 Linux bash 运行的方案。
  - 绝不因过滤参数失配出现「0 个测试执行却返回 0（假绿）」。
  - 绝不向浏览器暴露任意文件读取或来自跨站页面的构建执行入口。

---

## 3. 方案对比与实测依据

| 维度 | 方案 1a：Roborazzi NATIVE + Python 画廊（本轮选定） | 方案 1b：`android preview render` + `@Preview` | 方案 2：`scrcpy` + Debug 直达 + delta install |
| --- | --- | --- | --- |
| 依赖 `android-cli-plugin` | 否 | 否（可走纯 CLI） | 否（走 `android.exe` + `scrcpy`） |
| 兼容 `material3 1.5.0-rc01` | **已实测 100% 通过**（25 张黄金图现成） | 未实测（Layoutlib 风险 + 需补 `ui-tooling`） | 真机原生运行 |
| 交互后状态（弹窗/展开态） | **天然支持**（已有点击后截图用例） | 仅单组合函数静态帧 | 支持真实交互 |
| 改前/改后像素差异对比 | **内置**（直接对照 `src/test/snapshots/`） | 无现成基线 | 无像素 diff |
| 动效 / 真实手感 | 不支持（静态帧） | 不支持 | **完整支持** |
| 实测本机增量耗时 | 见下表（4 屏 9.8s / 全站 25 屏 11.8–16.1s） | 待实测 | 需连手机 + 增量推包 |

### 本机实测耗时读数（2026-11-24，`SLAYER`）

- 首次冷启动（Gradle Daemon 拉起 + 编译）：`149.6s`
- 热态单图（`homeScreen_light`）：`17.5s`（首次 CC 存储）
- 热态首页组 4 图（`homeScreen_*`）：`9.8s`
- 热态全站 22 用例 / 25 图（CC 复用）：`11.8s–16.1s`（JVM 内测试套件执行仅 `7.211s`）

**关键选型调整**：由于本机 Windows PowerShell 下直接执行 `.sh` 不顺手，且本机已安装 `uv 0.13.9`（Windows 与 WSL 通用），按 `AGENTS.md` §5 约定放弃 `.sh` 包装，统一采用 PEP 723 零第三方依赖 Python 入口 `scripts/live_preview.py`。

---

## 4. 系统架构与模块接口

```text
DSH / Antigravity / 人工终端
  │  uv run scripts/live_preview.py render [group]
  ▼
scripts/live_preview.py (PEP 723, 纯标准库)
  ├── 写 methods.txt + 原子写 status.json (state="rendering")
  ├── 在 android/ 调 cmd.exe /c run-gradle.bat :app:recordRoborazziDebug
  │     --tests com.cyanix.pixfold.ui.VisualRegressionSnapshotTest
  │     -Dpixfold.preview.outDir=build/live-preview
  ├── 解析 JUnit XML 强制校验 executed > 0 && failures == 0 && errors == 0
  ├── 原子写 status.json (state="ok" | "error", 含失败末尾 60 行)
  │
  └── 浏览器画廊服务 (127.0.0.1:4173, serve / watch)
        ├── GET /            -> scripts/live_preview_ui.py 单页画廊 HTML
        ├── GET /api/state   -> 当前状态 + 25 张快照基线/实时图元数据
        ├── GET /events      -> SSE 推送（0.3s 轮询状态与目录 mtime，15s 心跳）
        ├── GET /img/{baseline|live}/<name>.png  (严格白名单防路径穿越)
        └── POST /api/render -> 触发指定分组渲染（Origin 校验 + 并发锁 409）
```

### 4.1 Android 侧改动（零生产代码改动）

- **`android/app/src/test/kotlin/com/cyanix/pixfold/ui/LivePreviewConfig.kt`**：
  - `resolveOutputDir(rawOverride: String?): String`：未设或空白时固定返回 `"src/test/snapshots"`；否则裁剪首尾空白与末尾 `/`、`\` 后返回相对路径。
  - `snapshotPath(name: String, outDirOverride: String? = System.getProperty(PROP_OUT_DIR))`：返回 `"$dir/$name.png"`。
  - `readMethodFilter(outDirOverride: String? = ...)`: 仅当启用 `pixfold.preview.outDir` 时读取 `<outDir>/methods.txt` 非空去重行（忽略 `#` 注释与空行）；文件不存在或为空返回 `emptySet()`（表示全量放行）。
  - `shouldRunMethod(methodName: String, outDirOverride: String?, allowedMethods: Set<String>)`：未启用 `outDirOverride` 或 `allowedMethods.isEmpty()` 时返回 `true`；否则检查 `methodName in allowedMethods`。
  - `PreviewFilterRule : TestRule`：在测试执行前调用 `Assume.assumeTrue(...)` 跳过未选中的方法，并通过 `@get:Rule(order = -1)` 确保早于 `createComposeRule()` 触发，避免为被跳过用例初始化 Robolectric 沙箱。
- **`android/app/build.gradle.kts`**：
  - 使用 Gradle 9 Configuration Cache 安全的 `providers.systemProperty("pixfold.preview.outDir")`；
  - 仅当非空白值存在时向 `testOptions.unitTests.all` 注入 `systemProperty("pixfold.preview.outDir", previewOutDir)` 并设 `outputs.upToDateWhen { false }`。
  - 未传该属性时，任务输入/输出与缓存行为保持 100% 不变。
- **`android/app/src/test/kotlin/com/cyanix/pixfold/ui/VisualRegressionSnapshotTest.kt`**：
  - 挂载 `@get:Rule(order = -1) val previewFilterRule = PreviewFilterRule()`；
  - `snapshotPath(name)` 委托 `LivePreviewConfig.snapshotPath(name)`。

### 4.2 Python CLI 与服务端（`scripts/live_preview.py`）

- **PEP 723 元数据**：`requires-python = ">=3.10"`，`dependencies = []`（纯标准库：`http.server`、`threading`、`subprocess`、`xml.etree.ElementTree`、`pathlib`、`json`、`argparse`、`webbrowser` 等）。
- **分组与 22 个方法 / 25 张快照的精确映射**：
  - `home`（3 方法 / 4 图）：`homeScreen_light`、`homeScreen_dark`、`homeScreen_pureBlack`
  - `settings`（5 方法 / 5 图）：`settingsScreen_colorSpec2021`、`settingsScreen_colorSpec2025`、`settingsScreen_light`、`settingsScreen_pureBlack`、`workspaceListDialog_dark`
  - `chips`（2 方法 / 4 图）：`chips_singleSelect_collapsedAndExpanded`、`chips_twoLevel_seriesPopupOpen`
  - `workflows`（5 方法 / 5 图）：`sortPage_dark`、`libraryPage_dark`、`metadataPage_light`、`packPreview_dark`、`imagePreview_dark`
  - `plan`（4 方法 / 4 图）：`bottomBar_workflowAndReport_light`、`bottomBar_workflowAndReport_dark`、`renamePlan_dark`、`executionReport_dark`
  - `dialogs`（4 方法 / 4 图）：`workspaceListDialog_dark`、`undoScopeDialog_dark`、`undoScopeDialog_pureBlack`、`packProgressDialog_dark`
  - `all`（全部 22 方法 / 25 图）
  - 同时支持安全子串匹配单个方法名（校验 `^[A-Za-z0-9_]+$`，拒绝任意非字母数字下划线输入）。
- **子命令**：
  - `serve [--port 4173] [--open]`：启动本地监听 `127.0.0.1` 的画廊服务。
  - `render [target=all] [--port ...]`：执行一次完整渲染并更新 `status.json`；严格校验 `rc == 0` 且 JUnit XML `executed > 0`、`failures == 0`、`errors == 0`。
  - `watch [target=all] [--port 4173] [--debounce 1.2] [--open] [--no-initial]`：单命令启动画廊 + 轮询监听 `android/app/src/main/` 下 `.kt`/`.xml` 改动防抖重渲。
- **安全与并发**：
  - 只绑 `127.0.0.1`；
  - `POST /api/render` 校验 `Origin` 仅允许缺失或 `http://127.0.0.1:<port>` / `http://localhost:<port>`（跨站返回 403），并发调用返回 409；
  - 图片路由校验 `safe_snapshot_name`（仅 `[a-z0-9_]+\.png`），拒绝 `..`、斜杠与非常规文件名；
  - `status.json` 使用同目录临时文件 + `os.replace` 原子替换；输出重定向到 `last-render.log` 且 `stdin=DEVNULL` 避免管道阻塞。

### 4.3 浏览器画廊 UI（`scripts/live_preview_ui.py`）

- **现代 Web 规范落地**：
  - `<dialog id="inspectorDialog" closedby="any" aria-labelledby="inspectorTitle">` 配合 `showModal()` 及 `if (!('closedBy' in HTMLDialogElement.prototype))` 点击遮罩回退关闭；
  - `dialog:modal { overscroll-behavior: contain; }` 与 `dialog::backdrop { overflow: hidden; overscroll-behavior: contain; }` 防止背景滚动穿透（不动 `html`/`body` 的 `overflow`）；
  - `<meta name="color-scheme" content="light dark">` + `:root { color-scheme: light dark; }` + `light-dark(...)` 自适应系统深浅色；
  - 滑块/复选框使用 `accent-color` 并在 `@supports not (accent-color: auto)` 提供兼容回退；
  - 动态文本全部走 `textContent` 与 DOM API，绝不使用 `innerHTML` 拼接动态字段。
- **调试能力**：
  - 4 种预览模式：实时单图、并排改前/改后、卷帘滑块（Swipe）、像素差异红点图（Diff）；
  - 浏览器 `<canvas>` 本地计算每个页面的真实变更像素数与百分比徽章，支持「仅看有改动」与「有变化优先排序」；
  - 截图舞台底色切换（默认中性灰 / 棋盘格 / 浅底 / 纯黑）与 1×/2× 放大；
  - 顶部状态栏（空闲 / 编译渲染中 / 成功耗时 / 失败日志折叠抽屉）与分组触发渲染按钮。

### 4.4 项目技能与文档登记

- 新建 `.agents/skills/pixfold-live-ui/SKILL.md`：向 DSH、Antigravity、Codex 统一暴露启动画廊、按分组渲染、结果核对口径与真机边界。
- 同步登记至 `docs/INDEX.md` 与 `docs/notes/d4-m3e-ui-polish.md` §6.2。

---

## 5. 验证策略

1. **Kotlin 单测与定向变异**：`LivePreviewConfigTest` 覆盖默认回退、尾斜杠、空/无过滤文件放行与子集精确过滤；变异破坏 `outDirOverride` 覆盖分支必须被击杀。
2. **Python 单测与定向变异**：`scripts/test_live_preview.py` 覆盖目标解析、注入拦截、JUnit XML `0 tests` 硬拒绝、安全文件名、Origin 校验、状态负载与真实局部 HTTP 集成测试；变异破坏关键守卫必须被击杀。
3. **五道闸门**：`"C:\Users\Administrator\scoop\apps\git\current\bin\bash.exe" android/gate.sh --visual --rerun-tasks` 全绿（`gate-last.rc == 0`、`BUILD SUCCESSFUL`、`:app:lintDebug` 零告警、`verifyRoborazziDebug` 非缓存通过）。
4. **端到端验收**：执行 `uv run scripts/live_preview.py render home` 后核对 `status.json`、`executed == 3`、4 张实时图生成、`git status` 下黄金图目录零改动，并在临时端口实测画廊 HTTP/API/安全响应后清理进程。
