#!/usr/bin/env bash
# 闸门的稳定调用入口(WSL 侧)。替代手写的 `cmd.exe /c "run-gradle.bat ..." 2>&1 | tail -15`。
#
# ## 为什么需要这个文件(2026-10-21 实测)
#
# 原写法有两个问题:
#
# 1. **`tail` 要等 EOF 才吐字**。它在整个构建期间不给任何输出,所以从外部
#    观察者的视角看,"正在跑"与"已经死了"完全一样。2026-10-21 早上就这样空了
#    十几个 job_output 调用,最后靠 `ps` + 产物时间戳才判定进程早已不在。
# 2. 退出码经由管道后,`$?` 是 `tail` 的,不是 gradle 的 ⇒ **构建失败也报成功**。
#    实测:故意漏一个 `}`,gradle 打出 `BUILD FAILED` 而整条命令返回 0。
#    (`PIPESTATUS[0]` 确实能拿到真实值,但前提是每次手打都记得 —— 靠不住。)
#    这就是"假绿"的来源:测试红着,流水线却继续往下走。
#
# 改成"重定向到文件",于是:
# - 构建期间可以随时 `tail android/build/gate-last.log` 看进度(← 这是关键改进)
# - 退出码直接是 gradle 的
# - 日志留在盘上,失败后可复查,不必重跑
#
# ## 用法
#
#   ./gate.sh                              # 四道闸门(默认任务集)
#   ./gate.sh :app:testDebugUnitTest       # 指定任务
#   ./gate.sh :app:lintDebug               # 同上
#
# 调用方若看到命令尚未返回,可另起一次调用 `tail -5 android/build/gate-last.log`
# 判断它是"在跑"还是"卡死"—— 这是本脚本存在的全部理由。
#
# 注意:**Gradle 必须在 Windows 侧跑**(WSL 里跑会踩文件系统与路径的坑),
# 本脚本只是 `cmd.exe` 的包装,真正的构建仍在 Windows 上。
set -uo pipefail

cd "$(dirname "$0")"

LOG="build/gate-last.log"
mkdir -p build

# 四道闸门:lint / app 单测 / domain 单测 / 打包 APK。
# 顺序不动 —— 与 HANGOFF §7.2 一致,且 lint 最便宜、先失败先止损。
DEFAULT_TASKS=":app:lintDebug :app:testDebugUnitTest :domain:test :app:assembleDebug"

TASKS="${*:-$DEFAULT_TASKS}"
: > "$LOG"

echo "任务:$TASKS"
echo "日志:$LOG"

# 不用管道:stdout/stderr 都进文件,退出码就是 gradle 的。
# Gradle 检测不到 TTY 会切 plain console —— 输出变长但更利于事后 grep。
cmd.exe /c "run-gradle.bat $TASKS" >> "$LOG" 2>&1
rc=$?
echo "$rc" > build/gate-last.rc

if [ "$rc" -eq 0 ]; then
    echo "=== 成功 (RC=0) ==="
    tail -n 8 "$LOG"
else
    echo "=== 失败 (RC=$rc) ==="
    # 失败时给足上下文:编译错误往往在尾部几十行之内。
    tail -n 60 "$LOG"
fi
exit "$rc"
