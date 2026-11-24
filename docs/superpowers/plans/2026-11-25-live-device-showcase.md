# PixFold 方案 2 实施计划：真机 `scrcpy` 投屏 + Debug 15 场景直达 + Delta 推包

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在零侵入 Release 产物、不新建 `src/debug/AndroidManifest.xml`、不依赖 `android-cli-plugin` 的前提下，落地 Debug 15 场景一秒直达入口与跨平台 `scripts/live_device.py`（`scrcpy` 置顶投屏 + delta 增量推包 + `am start` 热切场景）。

**Architecture:** 利用 `src/debug/kotlin/` 与 `src/release/kotlin/` 同名源码集多态让 `MainActivity` 在 Debug 下识别 `pixfold_scene` 等 Intent extra 并渲染真实可交互 `DebugShowcaseScreen`（复用 `MockData` / `MockDataB` 与真机 `safeDrawing` insets）；上层通过 PEP 723 纯标准库脚本 `scripts/live_device.py` 统一封装 Windows/WSL 侧的 `run-gradle.bat`、`android install --use-delta-install`、`adb am start` 与 `scrcpy`。

**Tech Stack:** Kotlin + Jetpack Compose + Material3 Expressive (`1.5.0-rc01`) + JUnit 4 + Robolectric `@GraphicsMode(NATIVE)` + Python ≥3.10 (PEP 723 stdlib) + `android` CLI + `adb` + `scrcpy 5.0.1`。

**Spec:** [`docs/superpowers/specs/2026-11-25-live-device-showcase-design.md`](../specs/2026-11-25-live-device-showcase-design.md)

## Global Constraints

- **分支与提交纪律**：在现有 `d4/live-ui-preview` 分支继续实施；不擅自 `commit` / `merge` / `push`。
- **禁止未经授权连真机**：未获得用户当轮连机授权前，严禁运行 `adb connect`、`adb install`、`android install`、`scrcpy` 等实际设备操作命令。
- **严禁创建 `android/app/src/debug/AndroidManifest.xml`**：保持 `HygieneAndShapeTokensTest` 全绿零放宽。
- **Release 零污染**：Release 变体走 `src/release/kotlin/.../DebugShowcaseEntry.kt` 空桩，`compileReleaseKotlin` 必须无错通过；仅将 `ExecutionReportPage.kt` 的 `UndoScopeDialog` 由 `private` 放宽为 `internal`。
- **代码与 UI 卫生**：Kotlin 文件零 emoji（`EmojiFreeSourceTest`）、零硬编码 `RoundedCornerShape(...)`、纯图标按钮必设 `contentDescription` 与 `>= 48.dp` 触控框、`:app:lintDebug`（`warningsAsErrors = true`）零告警。
- **Python 规范**：`scripts/live_device.py` 与 `scripts/test_live_device.py` 带 PEP 723 元数据且 `dependencies = []`，全部通过 `uv run` 运行。

## Review Focus

1. **配置变更重复跳回与退出失效**：`MainActivity` 若未在读取 `pixfold_*` extra 后立即 `removeExtra`，屏幕旋转后将重复强制跳场景；`onNewIntent` 若未先处理 `pixfold_exit_showcase`，则无法从外部命令退出展示模式。
2. **`pack_progress` 测试挂起风险**：`DebugShowcaseScreen` 内若默认启用无限自增动画/协程延迟，Robolectric `rule.waitForIdle()` 会卡住超时；必须默认 `autoAdvance = false`，仅由真机用户手动打开开关触发。
3. **Debug vs Release 符号签名漂移**：`MainActivity` 同时编译于 Debug 与 Release 变体；两份 `DebugShowcaseEntry.kt` 中被 `MainActivity` 调用的符号与类型签名必须完全一致，并通过 `:app:compileReleaseKotlin` 实证。
4. **多设备或无设备盲推风险**：`resolve_serial` 在 0 台在线设备或 2+ 台在线设备且未传 `--serial`/`ADB_SERIAL` 时必须明确报错终止，严禁静默猜测或退回硬编码 IP。
5. **Command 注入与 `/sdcard` 污染**：`validate_scene`、`validate_options`、`validate_serial` 必须用白名单正则严格拦截特殊字符；`scripts/live_device.py` 不得向 `/sdcard` 或 `/storage/emulated` 写任何文件。

---

### Task 1: Kotlin Debug/Release 场景直达入口与测试（TDD）

**Files:**
- Modify: `android/app/src/main/kotlin/com/cyanix/pixfold/ui/plan/ExecutionReportPage.kt:372`
- Create: `android/app/src/test/kotlin/com/cyanix/pixfold/debug/DebugShowcaseTest.kt`
- Create: `android/app/src/debug/kotlin/com/cyanix/pixfold/debug/DebugShowcaseEntry.kt`
- Create: `android/app/src/debug/kotlin/com/cyanix/pixfold/debug/DebugShowcaseScreen.kt`
- Create: `android/app/src/release/kotlin/com/cyanix/pixfold/debug/DebugShowcaseEntry.kt`
- Modify: `android/app/src/main/kotlin/com/cyanix/pixfold/MainActivity.kt`

- [ ] **Step 1: Write failing tests in `DebugShowcaseTest.kt` (RED)**
  - 覆盖 15 个 `DebugScene` 全解析、`pure_black` -> `ThemeMode.Dark + pureBlack=true`、`colorSpec`/`accent`/`fontScale` 边界与回落、非法 scene 返 `null`、`isExitShowcaseIntent` 与 `clearDebugShowcaseExtras`。
  - 覆盖 Robolectric `@GraphicsMode(NATIVE)` UI 冒烟：`catalog` 渲染并可点击进入子场景、`BackHandler` 返回目录并退出回调、`home` / `settings` / `workspace_dialog` / `chips` / `chips_two_level` / `sort` / `library` / `metadata` / `pack_preview` / `pack_progress` / `rename_plan` / `execution_report` / `undo_dialog` / `image_preview` 全场景正常组合无崩溃，且满足 `assertAccessibleTargets` 与 `assertAllNodesFitViewport`（对 `catalog` 与关键交互页）。
- [ ] **Step 2: Run `:app:testDebugUnitTest --tests com.cyanix.pixfold.debug.DebugShowcaseTest` to verify RED**
- [ ] **Step 3: Implement `DebugShowcaseEntry.kt` (debug & release), `DebugShowcaseScreen.kt`, relax `UndoScopeDialog`, and wire `MainActivity.kt`**
- [ ] **Step 4: Run `DebugShowcaseTest` (GREEN) + `:app:compileReleaseKotlin` + targeted mutation on `DebugShowcaseEntry.kt`**
  - 变异：备份 `src/debug/.../DebugShowcaseEntry.kt`，破坏非法 scene 返回 `null` 或 `pure_black` 映射，确认 `DebugShowcaseTest` FAIL（KILLED），还原后复绿。

---

### Task 2: Python 真机投屏与 Delta 推包 CLI（TDD）

**Files:**
- Create: `scripts/test_live_device.py`
- Create: `scripts/live_device.py`

- [ ] **Step 1: Write failing unit tests in `scripts/test_live_device.py` (RED)**
  - 覆盖：`validate_scene`、`validate_options`、`validate_serial`、`parse_adb_devices`、`resolve_serial`、`build_am_start_cmd`、`build_install_cmd`（`android --use-delta-install` 与 `adb` 回退）、`build_scrcpy_cmd`、`scan_device_watch_fingerprint`（`src/main` + `src/debug`）、`scenes` CLI 冒烟。
- [ ] **Step 2: Run `uv run python -B -m unittest discover -s scripts -p "test_*.py" -v` to verify RED**
- [ ] **Step 3: Implement `scripts/live_device.py`**
- [ ] **Step 4: Run Python tests (GREEN) + targeted mutation on `scripts/live_device.py`**
  - 变异：备份 `scripts/live_device.py`，破坏 `validate_serial` 或 `build_install_cmd` 的 `--use-delta-install`，确认测试 FAIL（KILLED），还原后复绿。

---

### Task 3: 升级技能与文档同步

**Files:**
- Modify: `.agents/skills/pixfold-live-ui/SKILL.md`
- Modify: `docs/INDEX.md`
- Modify: `docs/notes/d4-m3e-ui-polish.md`

- [ ] **Step 1: Update `pixfold-live-ui/SKILL.md`, `docs/INDEX.md`, and `docs/notes/d4-m3e-ui-polish.md` §6.2**

---

### Task 4: 离线全量验证与收尾汇报（不碰真机，不擅自提交）

- [ ] **Step 1: Run `uv run python -B -m unittest discover -s scripts -p "test_*.py" -v` & `uv run python -B scripts/live_device.py scenes`**
- [ ] **Step 2: Run `cmd.exe /c "android\run-gradle.bat :app:compileReleaseKotlin"`**
- [ ] **Step 3: Run clean 5-gate `"C:\Users\Administrator\scoop\apps\git\current\bin\bash.exe" android/gate.sh --visual --rerun-tasks` (no concurrent Gradle)**
- [ ] **Step 4: Inspect `gate-last.rc`, `gate-last.log`, JUnit XML totals, `manage_task list`, `git status -sb`, `git diff --stat`, and report to user**
