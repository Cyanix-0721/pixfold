---
name: pixfold-build-gates
description: PixFold 的构建与提交闸门——四道闸门（gate.sh 一次跑 lintDebug / testDebugUnitTest / domain:test / assembleDebug）的跑法、判绿硬判据（看 gate-last.rc 与 BUILD SUCCESSFUL，不看作业状态、不看 UP-TO-DATE/FROM-CACHE 的假绿）、JUnit XML 计数口径、lint 零告警，以及提交后推送前的 betterleaks 密钥扫描参数与判绿口径。当要跑 gradle 闸门、判断构建/lint/测试是否真的通过、或准备提交推送时使用。
---

# PixFold 构建与提交闸门

## 四道闸门（Gradle 必须在 Windows 侧跑）

```bash
cd android && ./gate.sh
```

`gate.sh` 内部就是一次 gradle 调用：

```
run-gradle.bat :app:lintDebug :app:testDebugUnitTest :domain:test :app:assembleDebug
```

**为什么必须经 gate.sh —— 退出码。** 以前写 `cmd.exe /c "run-gradle.bat …" | tail -20`，管道的 RC 来自 `tail` 而非 gradle ⇒ 构建失败也报成功（实测：故意漏一个 `}`，gradle `BUILD FAILED` 但 RC=0）。`gate.sh` 把输出重定向进 `build/gate-last.log`、直接取 gradle 退出码、失败时打出行号。**不要在它外面再套 `| tail`** —— `tail` 等 EOF 而 `run-gradle.bat` 的句柄不关，会导致命令早已结束、作业一直报 running。

**判绿三条硬判据（防止用缓存/作业状态冒充通过）：**

1. 只看 `android/build/gate-last.rc` 与日志里的 `BUILD SUCCESSFUL|BUILD FAILED`，**不看作业状态**
2. 相关 task 不是 `UP-TO-DATE` / `FROM-CACHE`，否则必须加 `--rerun-tasks` 重跑。build cache 开启后 JUnit XML 的 mtime 不再能证明「本次跑过」（命中缓存不重写 XML）
3. lint 判绿看 `rc`，**不是** `grep -c warning`（日志里那两条是 Gradle 自己的 `--warning-mode all` 文案）

## 其他硬规则

- **绝不在 WSL 里跑 Gradle**；cmd.exe 下**禁用 `--tests`**（会静默假绿 0 测试）
- **测试数只认 JUnit XML**：`android/app/build/test-results/testDebugUnitTest/TEST-*.xml`、`android/domain/build/test-results/test/TEST-*.xml`（用 Python 解析；本机无 `bc`）
- `app` 的 `@Test` 总数比 XML 多 1 是**正常的** —— `NegativeControlTest` 是故意失败的负向对照，默认排除，仅 `-PincludeNegativeControl` 时运行
- **lint 必须零告警**：`warningsAsErrors = true` + `abortOnError = true`，一条 warning 即硬失败。新增资源 / 权限 / API 后尤其容易多出 `ObsoleteSdkInt`、`UnusedQuantity`、`ExtraTranslation`

## 提交后、推送前的密钥扫描（WSL 侧，读的是 git 对象）

```
betterleaks git . --redact --revision="origin/dev..dev" --include=merges --include=commit-messages
```

- 参数随 betterleaks 2.0.0-rc.3 变了：改走 go-git，`--log-opts` **已被移除**，限定范围用 `--revision`（`A..B`），含合并提交用 `--include=merges`。用旧参数会直接报 unknown flag，别照抄旧笔记
- **必须在提交之后跑**：该命令读的是 git 对象，工作树里未提交的改动不在扫描范围内——提交前跑会恰好漏掉正要提交的内容。要扫工作树须用 `betterleaks filesystem <path>`
- 只扫**未推送**部分（已推送的无法追回；`origin/dev..dev` 随推送自动收敛）
- **判绿口径**：真扫描必有 `INF scanned ~NNNN bytes`（百万级）且退出码 0；见到 `scanned ~0 bytes` + `incomplete scan` 一律当**失败**，不许当绿
- `--redact` **不可省**（否则命中值明文落盘）；回报只给 `rule_id` / 路径 / 行号，不复述 `match.value`
