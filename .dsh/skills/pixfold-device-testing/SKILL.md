---
name: pixfold-device-testing
description: PixFold 的 Android 真机核对规约——授权、adb 连接与唤醒、dumpui 取证、adb 已知坑（边缘手势/中文 IME/管道/调试产物路径）、收尾清理，以及「哪类结论只能真机断」的判定表。当需要连真机验证 UI（布局是否放得下/是否挤出视口、视觉规格、动画观感）、或判断某个结论能否离线下结论时使用。用户没有当轮授权时先别连设备。
---

# PixFold 真机核对规约

真机核对是「修复自证三层」的第三层：领域单测 → UI 层断言 → 真机实证（埋点 + 模拟输入 + 像素/快照对比）。不得只凭「测试通过」交付。

Robolectric 不做真实文本测量与真实布局——芯片宽度一律算成约 0（实测 trip 34.9dp / scan 34.6 / misc 34.3 / cn 34.3）。所以「放得下 / 是否挤出视口」这类结论**只能真机断**：删掉 `horizontalScroll` 后 312 项全绿也只是假保障。

## 授权

动手机前必须先通知用户并取得明确授权。**每轮单独授权，不继承上一轮。** 用户未授权时，可先把领域单测与 UI 断言两层做完并汇报，不要自行连设备。

## 设备与环境（固定）

**入口按宿主**：adb 来自 **Windows 侧 SDK**（WSL 里不装）。Windows 宿主直接 `adb ...`；WSL 宿主用 `cmd.exe /c adb.bat ...`（`adb.bat` 在 `android/`，已固定 SDK 路径）。⚠️ WSL 侧 **`adb` 不在 Linux PATH**、默认 shell 可能是 **fish**（2026-11-16 实测），别在 WSL 里直接 `adb`。

- 无线调试：`adb connect 192.168.43.1:4444`
- 机型 PJX110 / Android 16 / `1264×2780px` / `560dpi` = **361dp × 794dp**；系统 locale `zh-CN`
- 唤醒常亮：`input keyevent KEYCODE_WAKEUP` → `input keyevent 82`（解锁）→ `svc power stayon true`
- 安装：`adb install -r android/app/build/outputs/apk/debug/app-debug.apk`
- 启动：`adb shell monkey -p com.cyanix.pixfold -c android.intent.category.LAUNCHER 1`（⚠️ `input keyevent 4` 可能直接退出应用，不是回首页）

## 取证

- `dumpui.bat NAME`（在 `android/`）→ dump 到 `/data/local/tmp/NAME.xml` → 落入 `android/artifacts/`（已 gitignored）。Windows 宿主 `android\dumpui.bat NAME`；WSL 宿主 `cmd.exe /c dumpui.bat NAME`
- 读节点 `bounds` 坐标是唯一能证伪「控件放不下 / 落在视口外」的场合
- `uiautomator dump` 的坐标会过期，**每次 tap 前重新 dump**

## 已知坑（必须遵守）

- **边缘 x 不可用于应用内滑动**：`input swipe 1200 …` 的 x=1200 落在手势导航边缘区，会被系统当成返回手势、直接退出应用；应用内滑动用 **x ≤ 1000**
- **中文 IME 下 `adb shell input text` 抛异常**：`NullPointerException: Attempt to get length of null array`（`InputShellCommand.sendText:410`）⇒ 只能输入 ASCII；键盘弹出会把页面整体上移，**点按钮前必须先收键盘**（ESC = `input keyevent 111`），否则坐标落在 IME 上
- `input swipe` 触发不了长按 → 用 `input draganddrop`
- 经 `cmd.exe /c "adb.bat … | grep …"` 时管道会被 cmd.exe 吞掉 → 把管道放到宿主 shell 侧（WSL 宿主就放 WSL；Windows 宿主直接用 PowerShell 管道）
- **调试产物绝不写 `/sdcard` / `/storage/emulated`**（`adb.bat` 已有守卫）；只走 `/data/local/tmp`
- 汇报要给**读数**（坐标 / 尺寸 / 数值），不能只说「已通过」

## 收尾（每次必做）

清理 `/data/local/tmp` 内的 `.xml` / `.png`（计数须为 0）、`svc power stayon false` 复位、`am force-stop` 退出应用。

## 哪类结论必须真机，哪类可以离线

| 结论类型 | 能否离线断 | 说明 |
| --- | --- | --- |
| 领域逻辑（排序 / 命名 / 元数据） | ✅ 领域单测 | 第一层 |
| 结构 / 接线（节点存在、点击推进、按钮左右槽位） | ✅ UI 断言 | `getBoundsInRoot()` 能反映 `graphicsLayer` 缩放 |
| 「放得下」「是否挤出视口」 | ❌ 只能真机 | 删掉 `horizontalScroll` 后 312 项全绿 = 纯假保障 |
| 视觉规格（底色、高度、危险红） | ⚠️ 像素断言 + 真机 | 需 `@GraphicsMode(NATIVE)` + `captureToImage()`；LEGACY 后端画不出真实颜色 |
| 动画观感 / 是否生硬 | ❌ 只能真机（用户判定） | 用户观感判定才算定稿 |

⚠️ 变异能存活就说明断言没有杀伤力——删掉它，并写明为什么刻意不设这条断言，留着只会给假保障。变异纪律：先用 assert 确认旧串真的消失、再解读结果；备份 + 变异 + 还原必须在同一次调用内完成；还原一律用 `cp` 备份，**绝不用 `git checkout --`**（会连未提交改动一起丢掉）。
