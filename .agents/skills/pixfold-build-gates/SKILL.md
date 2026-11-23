---
name: pixfold-build-gates
description: PixFold 的构建与提交闸门——四道闸门（gate.sh 一次跑 lintDebug / testDebugUnitTest / domain:test / assembleDebug）的跑法、判绿硬判据（看 gate-last.rc 与 BUILD SUCCESSFUL，不看作业状态、不看 UP-TO-DATE/FROM-CACHE 的假绿）、JUnit XML 计数口径、lint 零告警，以及提交后推送前的 betterleaks 密钥扫描参数与判绿口径。当要跑 gradle 闸门、判断构建/lint/测试是否真的通过、或准备提交推送时使用。
---

# PixFold 构建与提交闸门

## 四道闸门（Gradle 必须在 Windows 侧跑）

**平台前提**：Gradle、Android SDK、adb **始终在 Windows 侧**执行；agent 宿主可以是 Windows 或 WSL。`gate.sh` 是 bash 包装，内部 `cmd.exe /c run-gradle.bat` 驱动 Windows 构建，两种宿主都能用，差别只在「怎么拿到一个能调 `cmd.exe` 的 bash」：

| agent 宿主 | 跑闸门 |
| --- | --- |
| WSL | 用 **bash** 执行：`cd android && ./gate.sh`（⚠️ 本机 WSL 默认 shell 是 **fish**，直接 `./gate.sh` 会解析失败；用 `wsl -d Debian -- bash -lc 'cd <repo>/android && ./gate.sh'`）。gate.sh 内部经 `cmd.exe` 调 **Windows** Gradle，Linux 侧无需 JDK/SDK。2026-11-16 实测：WIN 与 WSL(Debian) 两宿主均 BUILD SUCCESSFUL |
| Windows | 用 Git Bash 执行：`"<Git>\bin\bash.exe" android/gate.sh`（用户级 scoop 装的 Git 在 `%USERPROFILE%\scoop\apps\git\current\bin\bash.exe`，如 `C:\Users\Administrator\scoop\apps\git\current\bin\bash.exe`）。⚠️ Windows 上裸 `bash` 常被 `C:\Windows\System32\bash.exe`（WSL 启动器）占用，那不是 Git Bash，要显式用绝对路径。`gate.sh` 已内置 `export MSYS_NO_PATHCONV=1`——**否则** Git Bash 会把 `cmd.exe /c "run-gradle.bat …"` 里的 `/c` 当路径转义成 `C:\`，cmd 进入交互模式、`run-gradle.bat` 根本不执行却返回 0（**假绿**：日志里只有 cmd 启动横幅、没有 `BUILD SUCCESSFUL`，2026-11-16 实测踩到）。**已验证**：WIN 宿主经此路径跑通四道闸门（`BUILD SUCCESSFUL in 1m57s`、`gate-last.rc=0`、app 909 / domain 986 全绿） |

`gate.sh` 内部就是一次 gradle 调用：

```
run-gradle.bat :app:lintDebug :app:testDebugUnitTest :domain:test :app:assembleDebug
```

`gate.sh` 同时支持无 ADB 扩展档位（可后接 `--rerun-tasks` 等 Gradle 参数，显式任务参数写法保持原样兼容）：

| 命令 | 实际执行任务 | 用途 |
| --- | --- | --- |
| `./gate.sh`（或 `./gate.sh --rerun-tasks`） | 默认四道闸门 | 日常构建、lint、单测与 debug APK |
| `./gate.sh --visual`（可加 `--rerun-tasks`） | 默认四道闸门 + `:app:verifyRoborazziDebug` | 含 Roborazzi 视觉回归黄金图校验的五道闸门 |
| `./gate.sh --record` | `:app:recordRoborazziDebug` | 录制或更新 Roborazzi 视觉回归黄金图 |
| `./gate.sh --mutate` | `:domain:pitest` | `:domain` 纯 JVM PITest 自动变异测试（报告输出至 `android/domain/build/reports/pitest/`） |

**为什么必须经 gate.sh —— 退出码。** 以前写 `cmd.exe /c "run-gradle.bat …" | tail -20`，管道的 RC 来自 `tail` 而非 gradle ⇒ 构建失败也报成功（实测：故意漏一个 `}`，gradle `BUILD FAILED` 但 RC=0）。`gate.sh` 把输出重定向进 `build/gate-last.log`、直接取 gradle 退出码、失败时打出行号。**不要在它外面再套 `| tail`** —— `tail` 等 EOF 而 `run-gradle.bat` 的句柄不关，会导致命令早已结束、作业一直报 running。

**判绿三条硬判据（防止用缓存/作业状态冒充通过）：**

1. 只看 `android/build/gate-last.rc` 与日志里的 `BUILD SUCCESSFUL|BUILD FAILED`，**不看作业状态**
2. 相关 task 不是 `UP-TO-DATE` / `FROM-CACHE`，否则必须加 `--rerun-tasks` 重跑。build cache 开启后 JUnit XML 的 mtime 不再能证明「本次跑过」（命中缓存不重写 XML）
3. lint 判绿看 `rc`，**不是** `grep -c warning`（日志里那两条是 Gradle 自己的 `--warning-mode all` 文案）

## 其他硬规则

- **绝不在 WSL 的 Linux 侧直接跑 gradle / gradlew**（WSL 无 Android SDK，必失败）。从 WSL 跑闸门要经 `gate.sh` → `cmd.exe` → **Windows** Gradle，别在 Linux 里直接 `./gradlew`；cmd.exe 下**禁用 `--tests`**（会静默假绿 0 测试）
- **测试数只认 JUnit XML**：`android/app/build/test-results/testDebugUnitTest/TEST-*.xml`、`android/domain/build/test-results/test/TEST-*.xml`（用 Python 解析；本机无 `bc`）
- `app` 的 `@Test` 总数比 XML 多 1 是**正常的** —— `NegativeControlTest` 是故意失败的负向对照，默认排除，仅 `-PincludeNegativeControl` 时运行
- **lint 必须零告警**：`warningsAsErrors = true` + `abortOnError = true`，一条 warning 即硬失败。新增资源 / 权限 / API 后尤其容易多出 `ObsoleteSdkInt`、`UnusedQuantity`、`ExtraTranslation`

## 提交后、推送前的密钥扫描（agent 宿主侧，读的是 git 对象）

```
betterleaks git . --redact --revision="origin/dev..dev" --include=merges --include=commit-messages
```

- 参数随 betterleaks 2.0.0-rc.x 变了（当前 Windows PATH 上是 **2.0.0-rc.8**）：rc.3 起改走 go-git，`--log-opts` **已被移除**，限定范围用 `--revision`（`A..B`），含合并提交用 `--include=merges`。用旧参数会直接报 unknown flag，别照抄旧笔记
- **必须在提交之后跑**：该命令读的是 git 对象，工作树里未提交的改动不在扫描范围内——提交前跑会恰好漏掉正要提交的内容。要扫工作树须用 `betterleaks filesystem <path>`
- **在哪里跑**：Windows 宿主 `betterleaks` 已在 PATH（2.0.0-rc.8），直接跑；WSL 宿主**WSL 侧也直接有 2.0.0-rc.8**（2026-11-16 实测），直接 `betterleaks ...` 即可，或 `cmd.exe /c betterleaks ...` 走 Windows 版
- 只扫**未推送**部分（已推送的无法追回；`origin/dev..dev` 随推送自动收敛）
- **判绿口径**：真扫描必有 `INF scanned ~NNNN bytes`（百万级）且退出码 0；见到 `scanned ~0 bytes` + `incomplete scan` 一律当**失败**，不许当绿
- `--redact` **不可省**（否则命中值明文落盘）；回报只给 `rule_id` / 路径 / 行号，不复述 `match.value`
