---
name: pixfold-live-ui
description: PixFold 双轨实时 UI 预览与真机直达调试——(1) 修改 Compose 界面/主题/组件后免手机在浏览器画廊实时查看静态效果与像素对比（live_preview.py）；(2) 在已获真机授权时，用 scrcpy 置顶投屏 + delta 增量推包 + 15 场景一秒直达查看动效与触控手感（live_device.py）。DSH / Antigravity / Windows / WSL 通用，零依赖 android-cli-plugin。
---

# PixFold 实时 UI 预览与真机直达

> **两种模式分工**：静态排版、纯黑描边、色板与黄金图 diff 走 **模式 A（免手机画廊）**；60/120Hz 动效、波浪进度、12dp 高斯模糊、OEM 字体与触控手感走 **模式 B（真机直达）**。**调用模式 B 连真机前必须先取得用户当轮授权**（见 `pixfold-device-testing`）。

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
| 增量编译 + `android install --use-delta-install` + 直达场景 | `uv run scripts/live_device.py push [scene] [--theme dark\|light\|pure_black] [--spec 2025\|2021] [--accent default\|system\|...] [--font-scale 1.0\|1.3]` |
| 不重装包，一秒热切场景或外观 | `uv run scripts/live_device.py jump [scene] [外观参数]` |
| 监听 `src/main` 与 `src/debug` 自动 delta 推包 | `uv run scripts/live_device.py watch [scene] [外观参数]` |
| 退出展示模式回到真实 `PixFoldApp` | `uv run scripts/live_device.py exit` |

- **常用 `scene`**：`catalog`、`home`、`settings`、`workspace_dialog`、`chips`、`chips_two_level`、`sort`、`library`、`metadata`、`pack_preview`、`pack_progress`、`rename_plan`、`execution_report`、`undo_dialog`、`image_preview`（按系统返回回目录或退出，不遮挡真实 insets）。
- **默认设备与自动连接**：省略 `--serial` 与 `ADB_SERIAL` 时，优先选用唯一在线设备，否则默认 `192.168.43.1:4444`；任意 `host:port` 目标未在线时自动执行 `adb connect` 并校验上线。
