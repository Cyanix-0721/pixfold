# 2026-11-25 真机 scrcpy 投屏与 Debug 15 场景直达方案设计

> **状态**：已批复并进入实施（分支 `d4/live-ui-preview`，与方案 1 共同构成双轨实时 UI 调试体系）。
> **定位**：D4a UI 持续打磨期的真机实时调试链路（方案 2），零侵入 Release 产物、0 依赖 `android-cli-plugin`，Windows / WSL / DSH / Antigravity 通用。

---

## 1. 背景与核心问题

方案 1（`scripts/live_preview.py` 浏览器画廊）解决了「不连手机看静态排版/色板/像素对比」，但仍有两类问题必须看真机：
1. **动效与手感**：M3E `MotionScheme.expressive()` 弹簧形变、`CircularWavyProgressIndicator` 双波浪环、`ContainedLoadingIndicator`、12dp 弹窗高斯模糊、拖拽让位与大图 2× 缩放的 60/120Hz 观感。
2. **深层页面直达痛点**：每次改完 UI 重新打包推送后，从首页手点 4 步才能进「打包预览」「双波浪进度框」或「撤销弹窗」，迭代效率低。

**主机与工程约束**：
- 当前机器 `SLAYER`（15.2 GB 内存，Windows 侧 ~6 GB 给 Gradle），不适合常驻 AVD 或 Android Studio；本机已就绪 `scrcpy 5.0.1`、`android.exe`（支持 `install --use-delta-install`）及 `adb.exe`。
- `HygieneAndShapeTokensTest` 显式要求 `android/app/src/debug/AndroidManifest.xml` **必须保持不存在**。
- 未获当轮授权前，不得触碰物理设备。

---

## 2. 方案设计：源码集多态 + `scripts/live_device.py`

### 2.1 为何用 `src/debug/` + `src/release/` 同名函数多态（而非新建 Activity）

- 新建 `DebugShowcaseActivity` 必须写进 `src/debug/AndroidManifest.xml`，直接触发既有守卫 `HygieneAndShapeTokensTest` 失败。
- 改用 **`MainActivity` 读取 Intent extra + `src/debug/kotlin/` 与 `src/release/kotlin/` 同名顶层函数多态**：
  - `src/release/kotlin/com/cyanix/pixfold/debug/DebugShowcaseEntry.kt` 全部为恒返 `null`/`false`/空 `@Composable` 的极简桩，R8 裁剪后对 Release APK 为 0 字节开销，且不增加任何 `AndroidManifest.xml` 组件。
  - `MainActivity` 在 `onCreate`（仅 `savedInstanceState == null`）与 `onNewIntent` 读取 `pixfold_scene`、`pixfold_theme`、`pixfold_color_spec`、`pixfold_accent`、`pixfold_font_scale`、`pixfold_exit_showcase`，读取后立即 `removeExtra` 防止屏幕旋转配置变更后重跳。

### 2.2 15 个可直达场景与状态协议

| `scene` | 目标界面 | 说明 |
| --- | --- | --- |
| `catalog` | 场景目录页 | 内置外观快捷切换（浅色/深色/纯黑、1.0×/1.3× 字号、默认/壁纸动态取色、2025/2021 色板）与 14 个场景入口 |
| `home` | 首页 | 含就绪工作区卡片与胶囊栏 |
| `settings` | 设置页 | 真机可交互切换主题、纯黑、壁纸动态取色与色彩标准 |
| `workspace_dialog` | 工作区列表弹窗 | 叠于首页之上，可验 12dp 高斯模糊、分段列表与图钉按钮 |
| `chips` | 单层芯片瀑布流 | 96–156dp 定宽、3 行收口与原地展开 |
| `chips_two_level` | 二级系列芯片浮层 | 顶层系列行 + 非焦点子芯片 Popup 浮层 |
| `sort` | 工作流 A · 排序与预览 | 连接式网格/列表切换与拖拽排序 |
| `library` | 工作流 B · 书架页 | 卷列表、工作流 C 入口与警告条 |
| `metadata` | 工作流 B · 元数据编辑页 | 常驻卷芯片 + 字段红框警告 |
| `pack_preview` | 工作流 B · 打包预览页 | XML 预览容器与展开计划区 |
| `pack_progress` | 双波浪打包进度弹窗 | 叠于打包预览页之上；真机可开「自动步进」看双层波浪环推进（默认关闭防测试挂起） |
| `rename_plan` | 工作流 A · 改名计划页 | 摘要卡、筛选条与删除源目录二次确认 |
| `execution_report` | 工作流 A · 执行报告页 | 三态计数胶囊与分段报告行 |
| `undo_dialog` | 撤销范围弹窗 | 叠于执行报告页之上，验证居中 56dp `SoftBurst` 与 12dp 高斯模糊 |
| `image_preview` | 全屏大图预览 | 顶部胶囊、48dp 关闭按钮与底部 `HorizontalFloatingToolbar` |

**导航返回策略**：不叠任何常驻悬浮按钮以免遮挡真机全面屏 insets 与底栏；按系统返回手势（`BackHandler`）时，子场景回到 `catalog`，`catalog` 返回则退出展示模式回到正常 `PixFoldApp`。

### 2.3 Python 跨平台 CLI（`scripts/live_device.py`）

- PEP 723 纯标准库，Windows / WSL / DSH 统一通过 `uv run scripts/live_device.py <cmd>` 调用：
  - `scenes`：离线列出全部 15 个场景与外观选项（不连设备）。
  - `mirror [--serial ...] [--width 420] [--stay-awake] [--turn-screen-off]`：拉起 `scrcpy` 置顶小窗（`--always-on-top --window-title "PixFold Live"`）。
  - `jump [scene] [--theme ...] [--spec ...] [--accent ...] [--font-scale ...] [--serial ...]`：通过 `adb shell am start -W --activity-single-top --activity-clear-top` 零重启直达目标场景。
  - `push [scene] [...]`：在 `android/` 跑 `cmd.exe /c run-gradle.bat :app:assembleDebug` → 优先 `android.exe install --use-delta-install`（不可用时回退 `adb install -r`）→ `am start` 直达目标场景。
  - `exit [--serial ...]`：发送 `pixfold_exit_showcase=true` 退出展示模式回真实 `PixFoldApp`。
  - `watch [scene] [...]`：监听 `android/app/src/main` 与 `src/debug` 下 `.kt`/`.xml` 改动防抖自动 `push`。
- **安全与护栏**：
  - 严格校验 `scene`、`theme`、`spec`、`accent`、`font_scale`（`0.85..2.0`）与 `serial`（仅 `[A-Za-z0-9._:-]+`），防 shell 拼接注入；
  - 多设备未指定 `--serial` 或 0 在线设备时显式报错引导，不猜测目标；
  - 严禁触碰 `/sdcard` 或 `/storage/emulated`。

---

## 3. 验证与分工边界

- **离线可自证部分**：`DebugShowcaseTest`（参数解析单测 + `@GraphicsMode(NATIVE)` 全场景组合与返回导航测试 + 定向变异 KILLED）、`:app:compileReleaseKotlin`（验证 Release 空桩多态无误）、`scripts/test_live_device.py`（参数校验/命令组装/设备解析单测 + 定向变异 KILLED）、五道闸门 `android/gate.sh --visual --rerun-tasks`。
- **真机待授权部分**：物理机 `scrcpy` 投屏、`android install --use-delta-install` 增量推包与 `am start` 场景切换需在获用户本轮授权后再执行验证。
