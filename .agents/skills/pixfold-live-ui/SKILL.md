---
name: pixfold-live-ui
description: PixFold 三轨实时 UI 预览与真机直达调试——(1) 修改 Compose 界面/主题/组件后免手机在浏览器画廊实时查看静态效果与像素对比（live_preview.py）；(2) 在已获真机授权时，用 scrcpy 置顶投屏 + delta 增量推包 + 15 场景一秒直达查看动效与触控手感（live_device.py）；(3) 在 Antigravity Android Compose Preview 侧栏或 Android Studio Split Preview 中通过 src/debug 17 个无参 @Preview 入口实时出图与悬停查看组件树（PixFoldComposePreviews.kt）。
---

# PixFold 实时 UI 预览与真机直达

> **三种模式分工**：静态排版、纯黑描边、色板与黄金图 diff 走 **模式 A（免手机画廊，无插件通用）**；60/120Hz 动效、波浪进度、12dp 高斯模糊、OEM 字体与触控手感走 **模式 B（真机直达）**；在 Antigravity 侧栏边看组件树边界边调页面或用 AS Split Preview 走 **模式 C（Compose Preview 侧栏）**。**调用模式 B 连真机前必须先取得用户当轮授权**（见 `pixfold-device-testing`）。

## 模式 A：免手机浏览器画廊（`scripts/live_preview.py`）

输出重定向到 `android/app/build/live-preview/`（已 gitignored），不修改 `src/test/snapshots/` 黄金图：

| 用途 | 命令（Windows / WSL / DSH 通用） |
| --- | --- |
| 启动本地 SSE 画廊（默认 `127.0.0.1:4173`） | `uv run scripts/live_preview.py serve --open` |
| 改完 UI 后增量渲染并自动刷新浏览器 | `uv run scripts/live_preview.py render [target]`（`all`/`home`/`settings`/`chips`/`workflows`/`plan`/`dialogs` 或方法子串） |
| 监听 `src/main` 变更防抖自动重渲 | `uv run scripts/live_preview.py watch [target]` |

- **判绿口径**：`render` 退出码为 `0` 且 `android/app/build/live-preview/status.json` 中 `"state": "ok"`、`"executed" > 0`。

## 模式 B：真机 `scrcpy` 投屏 + 15 场景一秒直达（`scripts/live_device.py`）

依赖 `src/debug/.../DebugShowcaseEntry.kt`（Release 为零开销空桩，**不建 `src/debug/AndroidManifest.xml`**）：

| 用途 | 命令（除 `scenes` 外均需当轮授权连机） |
| --- | --- |
| 列出全部 15 个可直达场景与外观参数（离线） | `uv run scripts/live_device.py scenes` |
| 拉起置顶 `scrcpy` 投屏小窗 | `uv run scripts/live_device.py mirror [--serial ...]` |
| 增量编译 + `android install --use-delta-install` + 直达场景 | `uv run scripts/live_device.py push [scene] [--theme dark\|light\|pure_black] [--spec 2025\|2021] [--accent default\|system\|...] [--style tonal_spot\|vibrant\|expressive\|...] [--font-scale 1.0\|1.3]` |
| 不重装包，一秒热切场景或外观 | `uv run scripts/live_device.py jump [scene] [外观参数]` |
| 监听 `src/main` 与 `src/debug` 自动 delta 推包 | `uv run scripts/live_device.py watch [scene] [外观参数]` |
| 退出展示模式回到真实 `PixFoldApp` | `uv run scripts/live_device.py exit` |

- **常用 `scene`**：`catalog`、`home`、`settings`、`workspace_dialog`、`chips`、`chips_two_level`、`sort`、`library`、`metadata`、`pack_preview`、`pack_progress`、`rename_plan`、`execution_report`、`undo_dialog`、`image_preview`（按系统返回回目录或退出，不遮挡真实 insets）。
- **默认设备与自动连接**：省略 `--serial` 与 `ADB_SERIAL` 时，优先选用唯一在线设备，否则默认 `192.168.43.1:4444`；任意 `host:port` 目标未在线时自动执行 `adb connect` 并校验上线。

## 模式 C：Compose Preview 侧栏与 AS Split Preview（`PixFoldComposePreviews.kt`）

依赖 `android-cli-plugin` 的 `compose-preview` sidecar 与 `android/app/src/debug/kotlin/com/cyanix/pixfold/debug/PixFoldComposePreviews.kt`（17 个无参顶层 `@Preview` 复用 `DebugShowcaseScreen`，`debugImplementation(libs.compose.ui.tooling)` 零侵入 Release，**不建 `src/debug/AndroidManifest.xml`**）：

- **前提版本**：`android` CLI 需 `>= 1.1.16919734`（旧版可通过 `android update` 升级），sidecar 配置与 HTTP API 详见 `compose-preview` 技能。
- **必传参数**（调 `compose-preview` sidecar `POST /api/session`）：
  - Header：`X-Sidecar-Conversation: <conversationId>`
  - `project_dir`：`android/` 绝对路径；`reference`：`"gradle://.?:app@debug"`
  - `build_args`：`["-Dorg.gradle.java.home=<mise where java 的 JDK 21 路径>"]`（因 `:domain:compileKotlin` 要求 Java 21，而 Windows/WSL 默认全局常见为 Java 17）。
- **离线列出全部 `@Preview`**：在 `android/` 下执行 `android preview list "gradle://.?:app@debug" --format=json -- "--no-daemon" "-Dorg.gradle.java.home=<JDK21>"`（不直接手跑 `android preview render`，渲染统一交由 sidecar）。
- **Windows 首次解压 30s 超时坑**：首次触发渲染会下载并自解压约 276 MB `preview` pack 中的 `renderer`（内置 JRE + Layoutlib）；Windows 首次解压常超过 `android-cli` 硬编码的 `30000 ms` 启动超时而被杀在 `.extract` 临时目录。若遇到 `Timed out after 30000 ms waiting for renderer server to start` / `Unpacking embedded installation...`，先在终端运行一次 `~/.android/cli/packs/<ver>/preview/renderer --help` 等其完成落盘至 `~/.android/preview/bundles/<hash>`（该步骤报缺认证凭证退出属正常），再调用 sidecar `POST /api/render` 即可秒级出图。
