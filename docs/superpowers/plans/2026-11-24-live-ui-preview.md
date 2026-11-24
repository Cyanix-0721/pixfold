# PixFold 跨平台实时 UI 预览画廊实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建一套零依赖 `android-cli-plugin`、在 Windows PowerShell/CMD、WSL、DSH 与 Antigravity 中命令完全一致的免手机实时 UI 预览画廊，支持增量按组/全站出图、浏览器 SSE 无闪刷新与改前/改后像素对比。

**Architecture:** 复用已兼容 `material3 1.5.0-rc01` 的 Roborazzi NATIVE `VisualRegressionSnapshotTest` 渲染内核，通过 Gradle Configuration Cache 安全的 `-Dpixfold.preview.outDir` 系统属性与 `methods.txt` 过滤规则将实时出图无污染地重定向至 `android/app/build/live-preview/`；上层由 PEP 723 零第三方依赖脚本 `scripts/live_preview.py`（搭配纯静态页面模块 `scripts/live_preview_ui.py`）统一提供 `serve`、`render`、`watch` 命令与本地 HTTP/SSE 画廊服务。

**Tech Stack:** Kotlin + JUnit 4 + Gradle 9.8.0 (Provider API) + Roborazzi 1.79.0 (`@GraphicsMode(NATIVE)`) + Python ≥3.10 (PEP 723, `uv run`, pure stdlib) + Modern HTML5/CSS/JS (`<dialog closedby="any">`, `overscroll-behavior`, `light-dark()`, `EventSource`, Canvas pixel diff)。

**Spec:** [`docs/superpowers/specs/2026-11-24-live-ui-preview-design.md`](../specs/2026-11-24-live-ui-preview-design.md)

## Global Constraints

- **分支隔离**：从 `dev` 切出 `d4/live-ui-preview` 分支（`git branch --show-current == d4/live-ui-preview`），严禁在 `dev` 直接修改。
- **生产零侵入**：不改动 `android/app/src/main/` 与 `android/domain/` 下任何代码，不新增任何 Gradle 第三方依赖，不修改 `android/app/src/test/snapshots/` 中的 25 张基线黄金图。
- **Python 规范**：所有 Python 代码放于 `scripts/`，带 PEP 723 `# /// script` 元数据且 `dependencies = []`（纯标准库），一律通过 `uv run` 运行。
- **Gradle 与脚本安全**：Gradle 调用在 Windows 侧执行（`cmd.exe /c run-gradle.bat`），Configuration Cache 下只使用 `providers.systemProperty(...)` 读取属性；禁止使用未校验的 `innerHTML`，禁止让 0 测试通过成为假绿。
- **代码卫生**：Kotlin 测试文件中绝不使用 emoji（受 `EmojiFreeSourceTest` 约束）；`:app:lintDebug` 必须保持零告警（`warningsAsErrors = true`）。
- **验证门禁**：必须跑通 `uv run python -m unittest discover -s scripts -p "test_*.py" -v`、五道闸门 `android/gate.sh --visual --rerun-tasks` 以及真实 `render` + 本地 HTTP 端到端验收；收尾前关闭所有临时后台进程，不擅自提交或推送。

## Review Focus

1. **过滤后 0 测试假绿**：当 `methods.txt` 写错名或 `--tests` 过滤不匹配时，`cmd.exe` 返回 0 但实际 0 个用例运行；`parse_junit_result` 与 `run_render` 必须将 `executed == 0` 视为硬失败。
2. **Configuration Cache 冲突与污染**：`build.gradle.kts` 若直接用 `System.getProperty` 读取或无条件禁用 up-to-date，会破坏 CC 或拖慢日常测试；必须使用 `providers.systemProperty` 且仅在属性非空时注入并关闭测试缓存。
3. **跨目录路径穿透与外域触发渲染**：`/img/{kind}/{name}` 若未限制严格文件名模式可能被 `../` 越权读文件，`/api/render` 若不校验 `Origin` 可能被恶意网页触发本地 Gradle 构建（CSRF）。
4. **Windows 文件并发写读撕裂**：`render` 写入 `status.json` 与 SSE 轮询线程同时读取时，Windows 可能出现短暂写锁或半截 JSON；写侧必须同目录临时文件 + `os.replace`，读侧需安全容错解析。
5. **非 preview 模式下 Rule 误拦截或被跳过用例仍启动 Robolectric**：`PreviewFilterRule` 未传 `pixfold.preview.outDir` 时必须恒放行；启用子集过滤时必须以 `@get:Rule(order = -1)` 早于 `createComposeRule()` 跳过。

---

### Task 1: Kotlin 无污染预览配置与过滤 Rule（TDD）

**Files:**
- Create: `android/app/src/test/kotlin/com/cyanix/pixfold/ui/LivePreviewConfigTest.kt`
- Create: `android/app/src/test/kotlin/com/cyanix/pixfold/ui/LivePreviewConfig.kt`
- Modify: `android/app/build.gradle.kts:50-81`
- Modify: `android/app/src/test/kotlin/com/cyanix/pixfold/ui/VisualRegressionSnapshotTest.kt:80-95`

**Interfaces:**
- Produces: `LivePreviewConfig.snapshotPath(name: String, outDirOverride: String? = ...): String`
- Produces: `LivePreviewConfig.readMethodFilter(outDirOverride: String? = ...): Set<String>`
- Produces: `LivePreviewConfig.shouldRunMethod(methodName: String, outDirOverride: String? = ..., allowedMethods: Set<String> = ...): Boolean`
- Produces: `PreviewFilterRule : TestRule`

- [ ] **Step 1: Write failing unit tests in `LivePreviewConfigTest.kt`**
  - 断言：
    - `default_snapshot_path_falls_back_to_baseline_directory`: `snapshotPath("home_dark", null)` 与 `snapshotPath("home_dark", "  ")` 返回 `"src/test/snapshots/home_dark.png"`。
    - `custom_output_dir_trims_trailing_separators`: `snapshotPath("home_dark", "build/live-preview/")` 与 `"build/live-preview\\"` 返回 `"build/live-preview/home_dark.png"`。
    - `without_output_override_all_methods_run_even_if_allowlist_non_empty`: `shouldRunMethod("anyTest", outDirOverride = null, allowedMethods = setOf("onlyOne")) == true`。
    - `with_output_override_empty_allowlist_runs_all_and_non_empty_filters_exact_method`: 断言空集全放行，非空集仅放行匹配方法名。
    - `read_method_filter_ignores_comments_and_blank_lines_and_returns_empty_when_missing`: `@TempDir` 写临时 `methods.txt` 验证解析与缺失回退。
- [ ] **Step 2: Run test to verify it fails (RED)**
  - Run: `cmd.exe /c "android\run-gradle.bat :app:testDebugUnitTest --tests com.cyanix.pixfold.ui.LivePreviewConfigTest"`
  - Expected: FAIL with unresolved reference `LivePreviewConfig`.
- [ ] **Step 3: Implement `LivePreviewConfig.kt` and `PreviewFilterRule`**
  - 实现 `PROP_OUT_DIR = "pixfold.preview.outDir"`、路径归一化、方法过滤列表读取与 `PreviewFilterRule`（`Assume.assumeTrue("Skipped by pixfold.preview.outDir methods filter: ${description.methodName}", ...)`）。
- [ ] **Step 4: Wire `android/app/build.gradle.kts` and `VisualRegressionSnapshotTest.kt`**
  - 在 `android/app/build.gradle.kts` 用 `val previewOutDirProvider = providers.systemProperty("pixfold.preview.outDir")` 读取，非空白时配置 `it.systemProperty(...)` 与 `it.outputs.upToDateWhen { false }`。
  - 在 `VisualRegressionSnapshotTest.kt` 挂载 `@get:Rule(order = -1) val previewFilterRule = PreviewFilterRule()` 并把 `snapshotPath(name)` 委托给 `LivePreviewConfig.snapshotPath(name)`。
- [ ] **Step 5: Verify GREEN and run targeted mutation**
  - Run: `cmd.exe /c "android\run-gradle.bat :app:testDebugUnitTest --tests com.cyanix.pixfold.ui.LivePreviewConfigTest"` → PASS (5/5)。
  - Mutation: `cp` 备份 `LivePreviewConfig.kt`，把 `resolveOutputDir` 改为永远返回 `DEFAULT_SNAPSHOT_DIR`，重跑测试确认 RED（KILLED），再用 `cp` 还原并复跑确认 GREEN。

---

### Task 2: Python 核心 CLI、JUnit XML 守卫与安全 HTTP/SSE 服务端（TDD）

**Files:**
- Create: `scripts/test_live_preview.py`
- Create: `scripts/live_preview.py`
- Consumes from Task 3: `scripts/live_preview_ui.py` (`GALLERY_HTML`)

**Interfaces:**
- Produces in `scripts/live_preview.py`:
  - `PREVIEW_GROUPS: dict[str, tuple[str, ...]]` (6 个分组 + `all` 共 22 个唯一方法)
  - `SNAPSHOT_ENTRIES: tuple[dict[str, str], ...]` (25 张快照及其分组、标题)
  - `resolve_target(target: str) -> tuple[str, list[str]]`
  - `parse_junit_result(xml_path: Path) -> dict[str, int]`
  - `safe_snapshot_name(raw_name: str) -> str | None`
  - `is_allowed_origin(origin: str | None, port: int) -> bool`
  - `build_state_payload(baseline_dir: Path, live_dir: Path, status_file: Path) -> dict`
  - `scan_watch_fingerprint(watch_root: Path) -> tuple[tuple[str, int, int], ...]`
  - `run_render(target: str, ...) -> int`
  - `create_server(host: str, port: int, ...) -> ThreadingHTTPServer`

- [ ] **Step 1: Write failing unit & HTTP integration tests in `scripts/test_live_preview.py`**
  - 测试覆盖：
    - `test_resolve_target_groups_and_method_substring_and_rejection`: 验证全部 7 个预设 target、单方法子串匹配、空串/含空格/特殊字符抛 `ValueError`。
    - `test_parse_junit_result_rejects_missing_zero_executed_and_failures`: 验证正常计数、缺失文件/0 执行/存在 failure 或 error 均抛 `RuntimeError`。
    - `test_safe_snapshot_name_blocks_traversal_and_non_png`: 验证合法名放行，`../a.png`、`a/b.png`、`a.jpg`、`UPPER.png` 全返回 `None`。
    - `test_is_allowed_origin`: `None`、`http://127.0.0.1:4173`、`http://localhost:4173` 放行；不同端口、不同 host 或 `https://evil.example` 返回 `False`。
    - `test_build_state_payload_tracks_live_and_baseline`: 验证仅部分图片有 live 时 `has_live` 与 `baseline_url`/`live_url` 精确反映。
    - `test_scan_watch_fingerprint_filters_kt_and_xml_only`: 忽略 `.txt`/`.png`，感知 `.kt`/`.xml` mtime/size 变化。
    - `test_http_server_endpoints_and_security`: 起临时服务器（端口 0），验证 `GET /`、`GET /api/state`、`GET /img/baseline/...`、`GET /img/live/...`、越权路径返回 400/404、跨站 `POST /api/render` 返回 403、并发 `POST /api/render` 返回 409。
- [ ] **Step 2: Run Python tests to verify RED**
  - Run: `uv run python -m unittest discover -s scripts -p "test_*.py" -v`
  - Expected: FAIL (`ModuleNotFoundError: No module named 'live_preview'`).

---

### Task 3: 现代浏览器实时画廊 UI 模块（`scripts/live_preview_ui.py`）

**Files:**
- Create: `scripts/live_preview_ui.py`

**Interfaces:**
- Produces: `GALLERY_HTML: str`（被 `scripts/live_preview.py` 导入并在 `GET /` 返回）

- [ ] **Step 1: Implement `scripts/live_preview_ui.py` following `modern-web-guidance`**
  - 应用 `light-dismiss-a-dialog`：`<dialog id="inspectorDialog" closedby="any" aria-labelledby="inspectorTitle">` + `showModal()` + 可见关闭按钮 + `!('closedBy' in HTMLDialogElement.prototype)` 回退脚本。
  - 应用 `prevent-page-scroll-under-modal-dialog`：`dialog:modal { overscroll-behavior: contain; }` + `dialog::backdrop { overflow: hidden; overscroll-behavior: contain; }`。
  - 应用 `dark-mode-toggle`：`<meta name="color-scheme" content="light dark">` + `:root { color-scheme: light dark; }` + `light-dark(...)`，加上截图舞台背景切换（中性灰/棋盘格/浅底/纯黑）。
  - 应用 `brand-consistent-forms`：`accent-color` + `@supports not (accent-color: auto)` 回退 + `--progress` 更新。
  - 实现 `EventSource('/events')` 自动刷新、Canvas 逐像素比对算变更率、4 种视图模式（实时/并排/滑块/热力图）、1×/2× 缩放、分组/仅改动筛选、错误尾部抽屉与 `POST /api/render` 按钮，所有动态内容走 `textContent`。

---

### Task 4: 完成 `scripts/live_preview.py` 并完成 Python 侧绿 + 变异

**Files:**
- Create: `scripts/live_preview.py`

**Interfaces:**
- Consumes: `GALLERY_HTML` from `live_preview_ui`

- [ ] **Step 1: Implement `scripts/live_preview.py`**
  - 添加 PEP 723 `# /// script` 头（`requires-python = ">=3.10"`, `dependencies = []`）。
  - 实现精准 22 方法 / 25 快照定义、`resolve_target`、`parse_junit_result`、`safe_snapshot_name`、`is_allowed_origin`、`atomic_write_json` / `read_json_safe`、`run_render`（写 `methods.txt` + `status.json` + `cmd.exe /c run-gradle.bat` 到 `last-render.log`）、SSE `/events` 推送、`serve`/`render`/`watch` CLI。
- [ ] **Step 2: Run Python test suite to verify GREEN**
  - Run: `uv run python -m unittest discover -s scripts -p "test_*.py" -v`
  - Expected: All tests PASS (0 failures, 0 errors).
- [ ] **Step 3: Targeted mutation verification on `scripts/live_preview.py`**
  - 用 `cp` 备份 `scripts/live_preview.py` → 破坏 `parse_junit_result` 中 `executed == 0` 抛错校验（或 `safe_snapshot_name` 防穿越）→ 重跑 `unittest` 确认 FAIL（KILLED）→ 用 `cp` 还原并复跑确认 GREEN。

---

### Task 5: 跨 Agent 技能、文档索引同步、全量闸门与端到端验收

**Files:**
- Create: `.agents/skills/pixfold-live-ui/SKILL.md`
- Modify: `docs/INDEX.md`
- Modify: `docs/notes/d4-m3e-ui-polish.md`

- [ ] **Step 1: Author `.agents/skills/pixfold-live-ui/SKILL.md`**
  - 遵循 `writing-for-agents`：清晰前置触发条件（改完 Compose UI 想在浏览器实时预览/对比黄金图时加载）、Windows/WSL/DSH 统一 `uv run scripts/live_preview.py serve|render|watch` 用法、7 个分组速查表、成功判据（退出码 0 + `status.json` `executed > 0`）、与 `pixfold-device-testing` 的分工边界。
- [ ] **Step 2: Sync `docs/INDEX.md` and `docs/notes/d4-m3e-ui-polish.md`**
  - 在 `docs/INDEX.md` 的「项目级 skill」表登记 `pixfold-live-ui`，在「第四层：规格与计划」表登记 `2026-11-24-live-ui-preview-design.md` 与 `2026-11-24-live-ui-preview.md`。
  - 在 `docs/notes/d4-m3e-ui-polish.md` §6.2 补充当前已落地的 `scripts/live_preview.py` 与 `pixfold-live-ui` 指针。
- [ ] **Step 3: Run Python tests + full 5-gate verification (`--visual --rerun-tasks`)**
  - Run: `uv run python -m unittest discover -s scripts -p "test_*.py" -v`
  - Run: `"C:\Users\Administrator\scoop\apps\git\current\bin\bash.exe" android/gate.sh --visual --rerun-tasks`
  - Verify: `android/build/gate-last.rc` == `0`, `android/build/gate-last.log` 含 `BUILD SUCCESSFUL`, 无 `UP-TO-DATE`/`FROM-CACHE` 假绿，统计 JUnit XML 实际执行用例数，`:app:lintDebug` 零告警。
- [ ] **Step 4: End-to-end Live Preview smoke & HTTP verification**
  - Run: `uv run scripts/live_preview.py render home` → 确认退出码 `0`、`android/app/build/live-preview/status.json` 为 `state == "ok"` 且 `executed == 3`、生成 4 张 `home_*.png`，`git status -- android/app/src/test/snapshots` 为空。
  - 启动短时 `serve --port 4179` 进程，实测 `GET /`（200 含 `closedby="any"` 与 `overscroll-behavior: contain`）、`GET /api/state`（25 条记录含 4 条 `has_live: true`）、`GET /img/live/home_dark.png`（200）、`GET /img/live/../evil.png`（400/404）、跨站 `POST /api/render`（403），验证后立即关闭该临时 server 进程，检查无残留后台任务。
- [ ] **Step 5: Hand back change summary & commit prompt to user (do not commit/push)**
  - 汇总新增/修改文件行数、测试与闸门读数、`Modern Web Guidance` 披露段，询问用户是否现在提交。
